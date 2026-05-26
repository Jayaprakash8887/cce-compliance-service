package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.EventLogRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration tests for the inbound event processing pipeline:
 * Kafka → Consumer → ComplianceEngine → DB.
 */
class InboundEventIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    private UUID protocolDefId;

    @BeforeEach
    void loadProtocol() throws Exception {
        // Load protocol if not already loaded (check by url+version to avoid duplicates across tests)
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        // Use a unique version per test class
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"10.0.0-inbound\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        try {
            String response = mockMvc.perform(post("/v1/compliance/protocol-definitions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andReturn().getResponse().getContentAsString();
            protocolDefId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        } catch (Exception e) {
            // Protocol may already be loaded from a previous test — find it
            var existing = protocolInstanceRepository.findAll();
            if (!existing.isEmpty()) {
                protocolDefId = existing.get(0).getProtocolDefinition().getId();
            }
        }
    }

    @Test
    void matchingEvent_createsEnrollmentAndStep() {
        String eventId = UUID.randomUUID().toString();
        String patientId = "patient-inbound-" + UUID.randomUUID();

        // An Encounter event matches "any-encounter-log" (F1 only — resource type match)
        CloudEventMessage event = buildEncounterEvent(eventId, patientId, "in-progress");

        kafkaTemplate.send(inboundTopic, event);

        // Wait for event to be processed
        await().atMost(30, SECONDS).untilAsserted(() -> {
            var logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.MATCHED);
        });

        // Verify enrollment
        var instances = protocolInstanceRepository.findByPatientId(patientId);
        assertThat(instances).isNotEmpty();

        // Verify step created
        var steps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getActionId()).isEqualTo("any-encounter-log");
    }

    @Test
    void duplicateEvent_recordedAsDuplicate() {
        String eventId = "dup-" + UUID.randomUUID();
        String patientId = "patient-dup-" + UUID.randomUUID();

        CloudEventMessage event = buildEncounterEvent(eventId, patientId, "in-progress");

        // Send first
        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            var logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
        });

        // Send duplicate
        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            var logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId())
                            && el.getProcessingStatus() == ProcessingStatus.DUPLICATE)
                    .toList();
            assertThat(logs).isNotEmpty();
        });
    }

    @Test
    void nonMatchingEvent_recordedAsZeroMatch() {
        String eventId = "nomatch-" + UUID.randomUUID();
        String patientId = "patient-nomatch-" + UUID.randomUUID();

        // Send a Medication resource — no trigger matches this type
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Medication");
        data.put("status", "active");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Medication.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(data)
                .build();

        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            var logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.ZERO_MATCH);
        });
    }

    @Test
    void observationEvent_matchesBpCheck() {
        String eventId = "bp-" + UUID.randomUUID();
        String patientId = "patient-bp-" + UUID.randomUUID();

        // First, enroll patient by sending an Encounter (matches any-encounter-log)
        String enrollEventId = "enroll-bp-" + UUID.randomUUID();
        kafkaTemplate.send(inboundTopic, buildEncounterEvent(enrollEventId, patientId, "in-progress"));

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(protocolInstanceRepository.findByPatientId(patientId)).isNotEmpty();
        });

        // Now send a blood-pressure Observation (LOINC 85354-9)
        ObjectNode codeNode = objectMapper.createObjectNode();
        codeNode.set("coding", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("system", "http://loinc.org")
                        .put("code", "85354-9")));

        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Observation");
        data.put("status", "final");
        data.set("code", codeNode);
        data.put("subject", patientId);

        CloudEventMessage bpEvent = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Observation.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(data)
                .build();

        kafkaTemplate.send(inboundTopic, bpEvent);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            var logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.MATCHED);
        });

        // Verify a blood-pressure-check step was created
        var instances = protocolInstanceRepository.findByPatientId(patientId);
        assertThat(instances).isNotEmpty();
        var allSteps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
        assertThat(allSteps.stream().map(s -> s.getActionId()))
                .contains("blood-pressure-check");
    }

    private CloudEventMessage buildEncounterEvent(String eventId, String patientId, String status) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", status);
        data.put("id", UUID.randomUUID().toString());

        return CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Encounter.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(data)
                .build();
    }
}
