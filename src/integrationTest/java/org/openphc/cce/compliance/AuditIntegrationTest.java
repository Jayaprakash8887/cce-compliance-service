package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.repository.AuditLogRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the audit subsystem.
 * Verifies that AuditLog records are created asynchronously for key operations
 * (protocol load, event match, enrollment, step completion).
 */
class AuditIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    @Test
    void protocolLoad_createsAuditRecord() throws Exception {
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"40.0.0-audit-" + UUID.randomUUID().toString().substring(0, 8) + "\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String response = mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        // Audit is @Async — wait for it
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var auditLogs = auditLogRepository.findByEventCategory("PROTOCOL_MANAGEMENT");
            assertThat(auditLogs).anyMatch(log ->
                    "PROTOCOL_LOADED".equals(log.getEventType()) &&
                    protocolId.toString().equals(log.getResourceId()));
        });
    }

    @Test
    void eventMatch_createsComplianceAuditRecords() throws Exception {
        // Load protocol
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"41.0.0-audit-" + UUID.randomUUID().toString().substring(0, 8) + "\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // Send matching event
        String patientId = "patient-audit-" + UUID.randomUUID();
        String eventId = "audit-event-" + UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test-audit")
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

        // Wait for enrollment
        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(protocolInstanceRepository.findByPatientId(patientId)).isNotEmpty();
        });

        // Verify audit records for enrollment and matching (async)
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var complianceLogs = auditLogRepository.findByEventCategory("COMPLIANCE");
            assertThat(complianceLogs).anyMatch(log ->
                    "PROTOCOL_ENROLLED".equals(log.getEventType()));
            assertThat(complianceLogs).anyMatch(log ->
                    "EVENT_MATCHED".equals(log.getEventType()));
            assertThat(complianceLogs).anyMatch(log ->
                    "STEP_COMPLETED".equals(log.getEventType()));
        });
    }

    @Test
    void protocolRetire_createsAuditRecord() throws Exception {
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"42.0.0-audit-" + UUID.randomUUID().toString().substring(0, 8) + "\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String response = mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        // Retire
        mockMvc.perform(post("/v1/compliance/protocol-definitions/{id}/retire", protocolId))
                .andExpect(status().isOk());

        // Verify audit entry for retire
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var auditLogs = auditLogRepository.findByEventCategory("PROTOCOL_MANAGEMENT");
            assertThat(auditLogs).anyMatch(log ->
                    "PROTOCOL_RETIRED".equals(log.getEventType()) &&
                    protocolId.toString().equals(log.getResourceId()));
        });
    }
}
