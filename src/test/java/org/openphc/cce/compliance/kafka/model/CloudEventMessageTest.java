package org.openphc.cce.compliance.kafka.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CloudEventMessageTest {

    private static ObjectMapper objectMapper;

    @BeforeAll
    static void initMapper() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    // ── CloudEventMessage tests ──

    @Test
    void serialize_cloudEvent_allFieldsPresent() throws Exception {
        CloudEventMessage msg = CloudEventMessage.builder()
                .id("evt-001")
                .source("ebuzima")
                .type("Observation")
                .specversion("1.0")
                .subject("260225-0002-5501")
                .time(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC))
                .datacontenttype("application/fhir+json")
                .correlationid("corr-abc-123")
                .sourceeventid("lab-evt-789")
                .facilityid("0002")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation", "status", "final")))
                .build();

        String json = objectMapper.writeValueAsString(msg);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("evt-001", node.get("id").asText());
        assertEquals("ebuzima", node.get("source").asText());
        assertEquals("Observation", node.get("type").asText());
        assertEquals("1.0", node.get("specversion").asText());
        assertEquals("260225-0002-5501", node.get("subject").asText());
        assertEquals("application/fhir+json", node.get("datacontenttype").asText());
        assertEquals("corr-abc-123", node.get("correlationid").asText());
        assertEquals("lab-evt-789", node.get("sourceeventid").asText());
        assertEquals("0002", node.get("facilityid").asText());
        assertEquals("Observation", node.get("data").get("resourceType").asText());
    }

    @Test
    void serialize_cloudEvent_nullFieldsOmitted() throws Exception {
        CloudEventMessage msg = CloudEventMessage.builder()
                .id("evt-002")
                .source("rhie-mediator")
                .type("Encounter")
                .specversion("1.0")
                .subject("patient-123")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/fhir+json")
                .correlationid("corr-xyz")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Encounter")))
                .build();

        String json = objectMapper.writeValueAsString(msg);
        JsonNode node = objectMapper.readTree(json);

        // Null extension fields should be absent (NON_NULL)
        assertFalse(node.has("sourceeventid"));
        assertFalse(node.has("protocolinstanceid"));
        assertFalse(node.has("protocoldefinitionid"));
        assertFalse(node.has("actionid"));
        assertFalse(node.has("facilityid"));
    }

    @Test
    void deserialize_cloudEvent_roundTrip() throws Exception {
        String json = """
                {
                  "id": "evt-003",
                  "source": "ebuzima",
                  "type": "Observation",
                  "specversion": "1.0",
                  "subject": "patient-456",
                  "time": "2026-03-15T10:30:00Z",
                  "datacontenttype": "application/fhir+json",
                  "correlationid": "corr-round-trip",
                  "actionid": "blood-pressure-check",
                  "facilityid": "0003",
                  "data": {
                    "resourceType": "Observation",
                    "code": { "coding": [{ "system": "http://loinc.org", "code": "85354-9" }] }
                  }
                }
                """;

        CloudEventMessage msg = objectMapper.readValue(json, CloudEventMessage.class);

        assertEquals("evt-003", msg.getId());
        assertEquals("ebuzima", msg.getSource());
        assertEquals("Observation", msg.getType());
        assertEquals("1.0", msg.getSpecversion());
        assertEquals("patient-456", msg.getSubject());
        assertEquals("application/fhir+json", msg.getDatacontenttype());
        assertEquals("corr-round-trip", msg.getCorrelationid());
        assertEquals("blood-pressure-check", msg.getActionid());
        assertEquals("0003", msg.getFacilityid());
        assertNull(msg.getProtocolinstanceid());
        assertNull(msg.getProtocoldefinitionid());
        assertNull(msg.getSourceeventid());
        assertNotNull(msg.getData());
        assertEquals("Observation", msg.getData().get("resourceType").asText());
    }

    @Test
    void deserialize_cloudEvent_lowercaseFieldNames() throws Exception {
        // Verifies that CloudEvents spec lowercase field names are preserved
        String json = """
                {
                  "id": "evt-004",
                  "source": "src",
                  "type": "Encounter",
                  "specversion": "1.0",
                  "subject": "patient-789",
                  "time": "2026-03-15T10:30:00Z",
                  "datacontenttype": "application/json",
                  "correlationid": "corr-lower",
                  "protocolinstanceid": "550e8400-e29b-41d4-a716-446655440001",
                  "protocoldefinitionid": "660e8400-e29b-41d4-a716-446655440002",
                  "data": { "status": "active" }
                }
                """;

        CloudEventMessage msg = objectMapper.readValue(json, CloudEventMessage.class);

        assertEquals("550e8400-e29b-41d4-a716-446655440001", msg.getProtocolinstanceid());
        assertEquals("660e8400-e29b-41d4-a716-446655440002", msg.getProtocoldefinitionid());
    }

    // ── SchedulerTriggerMessage tests ──

    @Test
    void serialize_schedulerTrigger_allFields() throws Exception {
        UUID stepId = UUID.randomUUID();
        SchedulerTriggerMessage msg = SchedulerTriggerMessage.builder()
                .stepInstanceId(stepId)
                .transitionType("DUE_TO_OVERDUE")
                .triggeredAt(OffsetDateTime.of(2026, 3, 25, 0, 0, 0, 0, ZoneOffset.UTC))
                .correlationid("sched-corr-123")
                .build();

        String json = objectMapper.writeValueAsString(msg);
        JsonNode node = objectMapper.readTree(json);

        assertEquals(stepId.toString(), node.get("stepInstanceId").asText());
        assertEquals("DUE_TO_OVERDUE", node.get("transitionType").asText());
        assertEquals("sched-corr-123", node.get("correlationid").asText());
    }

    @Test
    void deserialize_schedulerTrigger_roundTrip() throws Exception {
        String json = """
                {
                  "stepInstanceId": "770e8400-e29b-41d4-a716-446655440002",
                  "transitionType": "PENDING_TO_DUE",
                  "triggeredAt": "2026-03-20T00:00:00Z",
                  "correlationid": "sched-corr-456"
                }
                """;

        SchedulerTriggerMessage msg = objectMapper.readValue(json, SchedulerTriggerMessage.class);

        assertEquals(UUID.fromString("770e8400-e29b-41d4-a716-446655440002"), msg.getStepInstanceId());
        assertEquals("PENDING_TO_DUE", msg.getTransitionType());
        assertEquals("sched-corr-456", msg.getCorrelationid());
        assertNotNull(msg.getTriggeredAt());
    }

    @Test
    void deserialize_schedulerTrigger_allTransitionTypes() throws Exception {
        for (String tt : new String[]{"PENDING_TO_DUE", "DUE_TO_OVERDUE", "OVERDUE_TO_MISSED"}) {
            String json = String.format("""
                    {
                      "stepInstanceId": "770e8400-e29b-41d4-a716-446655440002",
                      "transitionType": "%s",
                      "triggeredAt": "2026-03-20T00:00:00Z",
                      "correlationid": "corr-test"
                    }
                    """, tt);

            SchedulerTriggerMessage msg = objectMapper.readValue(json, SchedulerTriggerMessage.class);
            assertEquals(tt, msg.getTransitionType());
        }
    }

    // ── IntelligenceTriggerEvent tests ──

    @Test
    void serialize_intelligenceTrigger_allFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID ieId = UUID.randomUUID();
        UUID adId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();

        IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                .id(id)
                .subject("260225-0002-5501")
                .intelligenceEventId(ieId)
                .actionDefinitionId(adId)
                .protocolDefinitionId(pdId)
                .actionType("CommunicationRequest")
                .severity("HIGH")
                .intelligenceDestination("supervisor")
                .stepState("overdue")
                .actionId("viral-load-check")
                .protocolCanonical("http://example.org/PlanDefinition/hiv-treatment|1.0")
                .detectedAt(OffsetDateTime.of(2026, 3, 25, 0, 0, 5, 0, ZoneOffset.UTC))
                .build();

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertEquals(id.toString(), node.get("id").asText());
        assertEquals("260225-0002-5501", node.get("subject").asText());
        assertEquals(ieId.toString(), node.get("intelligenceEventId").asText());
        assertEquals(adId.toString(), node.get("actionDefinitionId").asText());
        assertEquals(pdId.toString(), node.get("protocolDefinitionId").asText());
        assertEquals("CommunicationRequest", node.get("actionType").asText());
        assertEquals("HIGH", node.get("severity").asText());
        assertEquals("supervisor", node.get("intelligenceDestination").asText());
        assertEquals("overdue", node.get("stepState").asText());
        assertEquals("viral-load-check", node.get("actionId").asText());
        assertEquals("http://example.org/PlanDefinition/hiv-treatment|1.0", node.get("protocolCanonical").asText());
        assertFalse(node.has("type"));
        assertFalse(node.has("metadata"));
    }

    @Test
    void serialize_intelligenceTrigger_nullFieldsOmitted() throws Exception {
        IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .subject("patient-100")
                .intelligenceEventId(UUID.randomUUID())
                .actionType("Task")
                .stepState("missed")
                .actionId("lab-check")
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        // Null fields should be absent
        assertFalse(node.has("protocolCanonical"));
        assertFalse(node.has("severity"));
        assertFalse(node.has("intelligenceDestination"));
        assertFalse(node.has("actionDefinitionId"));
        assertFalse(node.has("protocolDefinitionId"));
        assertFalse(node.has("metadata"));
    }

    @Test
    void deserialize_intelligenceTrigger_roundTrip() throws Exception {
        String json = """
                {
                  "id": "880e8400-e29b-41d4-a716-446655440099",
                  "subject": "260225-0002-5501",
                  "intelligenceEventId": "990e8400-e29b-41d4-a716-446655440010",
                  "actionDefinitionId": "aad00001-0001-0001-0001-000000000001",
                  "protocolDefinitionId": "660e8400-e29b-41d4-a716-446655440001",
                  "actionType": "CommunicationRequest",
                  "severity": "HIGH",
                  "intelligenceDestination": "supervisor",
                  "stepState": "overdue",
                  "actionId": "bp-check",
                  "protocolCanonical": "http://example.org/pd|1.0",
                  "detectedAt": "2026-03-25T00:00:05Z"
                }
                """;

        IntelligenceTriggerEvent event = objectMapper.readValue(json, IntelligenceTriggerEvent.class);

        assertEquals(UUID.fromString("880e8400-e29b-41d4-a716-446655440099"), event.getId());
        assertEquals("260225-0002-5501", event.getSubject());
        assertEquals(UUID.fromString("990e8400-e29b-41d4-a716-446655440010"), event.getIntelligenceEventId());
        assertEquals("CommunicationRequest", event.getActionType());
        assertEquals("HIGH", event.getSeverity());
        assertEquals("supervisor", event.getIntelligenceDestination());
        assertEquals("overdue", event.getStepState());
        assertEquals("bp-check", event.getActionId());
        assertEquals("http://example.org/pd|1.0", event.getProtocolCanonical());
        assertNotNull(event.getDetectedAt());
    }
}
