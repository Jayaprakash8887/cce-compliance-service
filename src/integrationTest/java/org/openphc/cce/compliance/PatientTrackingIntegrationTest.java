package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
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
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for patient tracking REST endpoints.
 * Tests that data written via Kafka events can be queried via the REST API.
 */
class PatientTrackingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    private UUID loadProtocolAndGetId() throws Exception {
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"30.0.0-track-" + UUID.randomUUID().toString().substring(0, 8) + "\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String response = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void enrollPatient(String patientId) {
        String eventId = "track-enroll-" + UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test-tracking")
                .type("org.openphc.fhir.Encounter.create")
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
            assertThat(protocolInstanceRepository.findByPatientId(patientId)).isNotEmpty();
        });
    }

    @Test
    void listProtocolInstances_afterEnrollment() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-list-" + UUID.randomUUID();
        enrollPatient(patientId);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].patientId").value(patientId));
    }

    @Test
    void listActiveInstances_afterEnrollment() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-active-" + UUID.randomUUID();
        enrollPatient(patientId);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/active", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void getInstanceDetail_returnsFullData() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-detail-" + UUID.randomUUID();
        enrollPatient(patientId);

        var instances = protocolInstanceRepository.findByPatientId(patientId);
        UUID instanceId = instances.get(0).getId();

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{instanceId}",
                        patientId, instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceId.toString()))
                .andExpect(jsonPath("$.patientId").value(patientId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getSteps_afterEnrollment() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-steps-" + UUID.randomUUID();
        enrollPatient(patientId);

        var instances = protocolInstanceRepository.findByPatientId(patientId);
        UUID instanceId = instances.get(0).getId();

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{instanceId}/steps",
                        patientId, instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void getEvents_paginatedByPatient() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-events-" + UUID.randomUUID();
        enrollPatient(patientId);

        mockMvc.perform(get("/v1/patients/{patientId}/events", patientId)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].subject").value(patientId));
    }

    @Test
    void getInstanceDetail_wrongPatient_returns404() throws Exception {
        loadProtocolAndGetId();
        String patientId = "patient-track-wrong-" + UUID.randomUUID();
        enrollPatient(patientId);

        var instances = protocolInstanceRepository.findByPatientId(patientId);
        UUID instanceId = instances.get(0).getId();

        // Query with a different patient ID → should not find the instance
        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{instanceId}",
                        "wrong-patient-id", instanceId))
                .andExpect(status().isNotFound());
    }
}
