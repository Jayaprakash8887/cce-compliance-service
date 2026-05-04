package org.openphc.cce.compliance.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.web.dto.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DtoMapperTest {

    private DtoMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = new DtoMapper();
    }

    @Test
    void toDto_protocolDefinition_mapsAllFields() {
        JsonNode definition = objectMapper.valueToTree(Map.of("resourceType", "PlanDefinition"));
        ProtocolDefinition entity = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://example.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .loadedAt(OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                .definition(definition)
                .build();

        ProtocolDefinitionDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("http://example.org/PlanDefinition/anc-high-risk", dto.getUrl());
        assertEquals("1.0.0", dto.getVersion());
        assertEquals("http://example.org/PlanDefinition/anc-high-risk|1.0.0", dto.getCanonical());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(entity.getLoadedAt(), dto.getLoadedAt());
        assertSame(definition, dto.getDefinition());
    }

    @Test
    void toDto_protocolInstance_mapsAllFieldsIncludingNestedLists() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://example.org/PlanDefinition/anc")
                .version("2.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .build();

        ProtocolInstance entity = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-123")
                .protocolCanonical("http://example.org/PlanDefinition/anc|2.0.0")
                .protocolDefinition(protocolDef)
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.of(2026, 2, 1, 8, 0, 0, 0, ZoneOffset.UTC))
                .createdAt(OffsetDateTime.of(2026, 2, 1, 8, 0, 0, 0, ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.of(2026, 2, 1, 9, 0, 0, 0, ZoneOffset.UTC))
                .steps(Collections.emptySet())
                .deviations(Collections.emptySet())
                .build();

        ProtocolInstanceDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("patient-123", dto.getPatientId());
        assertEquals("http://example.org/PlanDefinition/anc|2.0.0", dto.getProtocolCanonical());
        assertEquals(protocolDef.getId(), dto.getProtocolDefinitionId());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(entity.getEnrolledAt(), dto.getEnrolledAt());
        assertEquals(entity.getCreatedAt(), dto.getCreatedAt());
        assertEquals(entity.getUpdatedAt(), dto.getUpdatedAt());
        assertNotNull(dto.getSteps());
        assertTrue(dto.getSteps().isEmpty());
        assertNotNull(dto.getDeviations());
        assertTrue(dto.getDeviations().isEmpty());
    }

    @Test
    void toDto_protocolInstance_mapsNestedStepsAndDeviations() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .build();

        ProtocolInstance instance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-456")
                .protocolCanonical("http://example.org/test|1.0")
                .protocolDefinition(protocolDef)
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        StepInstance step = StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(instance)
                .actionId("action-1")
                .repeatIndex(0)
                .state(StepState.PENDING)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .build();

        Deviation deviation = Deviation.builder()
                .id(UUID.randomUUID())
                .protocolInstance(instance)
                .stepInstance(step)
                .deviationType(DeviationType.OVERDUE)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        instance.setSteps(Set.of(step));
        instance.setDeviations(Set.of(deviation));

        ProtocolInstanceDto dto = mapper.toDto(instance);

        assertEquals(1, dto.getSteps().size());
        assertEquals(step.getId(), dto.getSteps().get(0).getId());
        assertEquals("action-1", dto.getSteps().get(0).getActionId());
        assertEquals(1, dto.getDeviations().size());
        assertEquals(deviation.getId(), dto.getDeviations().get(0).getId());
        assertEquals("OVERDUE", dto.getDeviations().get(0).getDeviationType());
    }

    @Test
    void toDto_stepInstance_mapsAllFields() {
        UUID matchedEventId = UUID.randomUUID();
        StepInstance entity = StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("blood-pressure-check")
                .repeatIndex(2)
                .state(StepState.COMPLETED)
                .dueDate(OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                .overdueDate(OffsetDateTime.of(2026, 3, 8, 0, 0, 0, 0, ZoneOffset.UTC))
                .missedDate(OffsetDateTime.of(2026, 3, 15, 0, 0, 0, 0, ZoneOffset.UTC))
                .completedAt(OffsetDateTime.of(2026, 3, 5, 14, 30, 0, 0, ZoneOffset.UTC))
                .completedBySource("urn:source:lab-system")
                .completionStatus(CompletionStatus.ON_TIME)
                .matchedEventId(matchedEventId)
                .requiredBehavior("must")
                .build();

        StepInstanceDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("blood-pressure-check", dto.getActionId());
        assertEquals(2, dto.getRepeatIndex());
        assertEquals("COMPLETED", dto.getState());
        assertEquals(entity.getDueDate(), dto.getDueDate());
        assertEquals(entity.getOverdueDate(), dto.getOverdueDate());
        assertEquals(entity.getMissedDate(), dto.getMissedDate());
        assertEquals(entity.getCompletedAt(), dto.getCompletedAt());
        assertEquals("urn:source:lab-system", dto.getCompletedBySource());
        assertEquals("ON_TIME", dto.getCompletionStatus());
        assertEquals(matchedEventId, dto.getMatchedEventId());
        assertEquals("must", dto.getRequiredBehavior());
    }

    @Test
    void toDto_stepInstance_nullableFieldsHandled() {
        StepInstance entity = StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("check")
                .repeatIndex(0)
                .state(StepState.PENDING)
                .build();

        StepInstanceDto dto = mapper.toDto(entity);

        assertEquals("PENDING", dto.getState());
        assertNull(dto.getDueDate());
        assertNull(dto.getCompletedAt());
        assertNull(dto.getCompletionStatus());
        assertNull(dto.getMatchedEventId());
        assertNull(dto.getRequiredBehavior());
    }

    @Test
    void toDto_deviation_mapsAllFields() {
        JsonNode metadata = objectMapper.valueToTree(Map.of("daysPastDue", 3));
        UUID intelligenceEventId = UUID.randomUUID();
        Deviation entity = Deviation.builder()
                .id(UUID.randomUUID())
                .deviationType(DeviationType.MISSED)
                .detectedAt(OffsetDateTime.of(2026, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .intelligenceEventId(intelligenceEventId)
                .metadata(metadata)
                .build();

        DeviationDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("MISSED", dto.getDeviationType());
        assertEquals(entity.getDetectedAt(), dto.getDetectedAt());
        assertEquals(intelligenceEventId, dto.getIntelligenceEventId());
        assertSame(metadata, dto.getMetadata());
    }

    @Test
    void toDto_eventLog_mapsAllFields() {
        JsonNode data = objectMapper.valueToTree(Map.of("resourceType", "Encounter"));
        UUID protocolInstanceId = UUID.randomUUID();
        EventLog entity = EventLog.builder()
                .id(UUID.randomUUID())
                .cloudeventsId("ce-001")
                .source("urn:source:collector")
                .subject("patient-789")
                .type("org.openphc.clinical.encounter")
                .eventTime(OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .receivedAt(OffsetDateTime.of(2026, 3, 1, 10, 0, 1, 0, ZoneOffset.UTC))
                .processingStatus(ProcessingStatus.MATCHED)
                .data(data)
                .protocolInstanceId(protocolInstanceId)
                .actionId("action-1")
                .facilityId("facility-abc")
                .build();

        EventLogDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("ce-001", dto.getCloudeventsId());
        assertEquals("urn:source:collector", dto.getSource());
        assertEquals("patient-789", dto.getSubject());
        assertEquals("org.openphc.clinical.encounter", dto.getType());
        assertEquals(entity.getEventTime(), dto.getEventTime());
        assertEquals(entity.getReceivedAt(), dto.getReceivedAt());
        assertEquals("MATCHED", dto.getProcessingStatus());
        assertSame(data, dto.getData());
        assertEquals(protocolInstanceId, dto.getProtocolInstanceId());
        assertEquals("action-1", dto.getActionId());
        assertEquals("facility-abc", dto.getFacilityId());
    }

    @Test
    void toDtoProtocolDefinitionList_mapsAll() {
        ProtocolDefinition entity = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://example.org/pd")
                .version("1.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .definition(objectMapper.createObjectNode())
                .build();

        List<ProtocolDefinitionDto> dtos = mapper.toDtoProtocolDefinitionList(List.of(entity));

        assertEquals(1, dtos.size());
        assertEquals(entity.getId(), dtos.get(0).getId());
    }

    @Test
    void toDtoProtocolDefinitionList_nullReturnsEmpty() {
        List<ProtocolDefinitionDto> dtos = mapper.toDtoProtocolDefinitionList(null);
        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }

    @Test
    void toDtoEventLogList_mapsAll() {
        EventLog entity = EventLog.builder()
                .id(UUID.randomUUID())
                .cloudeventsId("ce-002")
                .source("src")
                .subject("subj")
                .type("type")
                .eventTime(OffsetDateTime.now(ZoneOffset.UTC))
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .processingStatus(ProcessingStatus.ZERO_MATCH)
                .data(objectMapper.createObjectNode())
                .build();

        List<EventLogDto> dtos = mapper.toDtoEventLogList(List.of(entity));

        assertEquals(1, dtos.size());
        assertEquals("ZERO_MATCH", dtos.get(0).getProcessingStatus());
    }

    @Test
    void toDtoStepList_nullReturnsEmpty() {
        List<StepInstanceDto> dtos = mapper.toDtoStepList(null);
        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }

    @Test
    void toDtoDeviationList_nullReturnsEmpty() {
        List<DeviationDto> dtos = mapper.toDtoDeviationList(null);
        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }

    @Test
    void toDto_actionDefinition_mapsAllFields() {
        JsonNode definition = objectMapper.valueToTree(Map.of("resourceType", "ActivityDefinition"));
        ActionDefinition entity = ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/ActivityDefinition/escalation-alert")
                .version("1.0")
                .name("escalation-alert")
                .title("Escalation Alert")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.CommunicationRequest)
                .definition(definition)
                .createdAt(OffsetDateTime.of(2026, 4, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.of(2026, 4, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .build();

        ActionDefinitionDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertEquals("http://openphc.org/ActivityDefinition/escalation-alert", dto.getCanonicalUrl());
        assertEquals("1.0", dto.getVersion());
        assertEquals("http://openphc.org/ActivityDefinition/escalation-alert|1.0", dto.getCanonical());
        assertEquals("escalation-alert", dto.getName());
        assertEquals("Escalation Alert", dto.getTitle());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals("CommunicationRequest", dto.getActionType());
        assertSame(definition, dto.getDefinition());
        assertEquals(entity.getCreatedAt(), dto.getCreatedAt());
        assertEquals(entity.getUpdatedAt(), dto.getUpdatedAt());
    }

    @Test
    void toDto_actionDefinition_nullableFieldsHandled() {
        ActionDefinition entity = ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/test")
                .version("1.0")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.Task)
                .definition(objectMapper.createObjectNode())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        ActionDefinitionDto dto = mapper.toDto(entity);

        assertNull(dto.getName());
        assertNull(dto.getTitle());
    }

    @Test
    void toDto_intelligenceEventLog_mapsAllFields() {
        UUID actionDefId = UUID.randomUUID();
        UUID protocolInstanceId = UUID.randomUUID();
        UUID stepInstanceId = UUID.randomUUID();
        UUID deviationId = UUID.randomUUID();
        JsonNode evaluationContext = objectMapper.valueToTree(Map.of("stepState", "overdue"));
        JsonNode eventPayload = objectMapper.valueToTree(Map.of("id", UUID.randomUUID().toString()));
        OffsetDateTime publishedAt = OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        IntelligenceEventLog entity = IntelligenceEventLog.builder()
                .id(UUID.randomUUID())
                .eventPayload(eventPayload)
                .actionDefinitionId(actionDefId)
                .protocolInstanceId(protocolInstanceId)
                .stepInstanceId(stepInstanceId)
                .deviationId(deviationId)
                .subject("patient-1")
                .actionType("CommunicationRequest")
                .intelligenceDestination("sms")
                .stepState("overdue")
                .triggerReason("overdue")
                .stepActionId("bp-check")
                .evaluationExpression("{\">\": [{\"var\": \"daysOverdue\"}, 2]}")
                .evaluationContext(evaluationContext)
                .published(true)
                .publishedAt(publishedAt)
                .createdAt(createdAt)
                .build();

        IntelligenceEventLogDto dto = mapper.toDto(entity);

        assertEquals(entity.getId(), dto.getId());
        assertSame(eventPayload, dto.getEventPayload());
        assertEquals(actionDefId, dto.getActionDefinitionId());
        assertEquals(protocolInstanceId, dto.getProtocolInstanceId());
        assertEquals(stepInstanceId, dto.getStepInstanceId());
        assertEquals(deviationId, dto.getDeviationId());
        assertEquals("patient-1", dto.getSubject());
        assertEquals("CommunicationRequest", dto.getActionType());
        assertEquals("sms", dto.getIntelligenceDestination());
        assertEquals("overdue", dto.getStepState());
        assertEquals("overdue", dto.getTriggerReason());
        assertEquals("bp-check", dto.getStepActionId());
        assertEquals("{\">\": [{\"var\": \"daysOverdue\"}, 2]}", dto.getEvaluationExpression());
        assertSame(evaluationContext, dto.getEvaluationContext());
        assertTrue(dto.isPublished());
        assertEquals(publishedAt, dto.getPublishedAt());
        assertEquals(createdAt, dto.getCreatedAt());
    }

    @Test
    void toDto_intelligenceEventLog_nullOptionalFields() {
        JsonNode eventPayload = objectMapper.valueToTree(Map.of("id", UUID.randomUUID().toString()));

        IntelligenceEventLog entity = IntelligenceEventLog.builder()
                .id(UUID.randomUUID())
                .eventPayload(eventPayload)
                .actionDefinitionId(UUID.randomUUID())
                .protocolInstanceId(UUID.randomUUID())
                .subject("patient-1")
                .actionType("Task")
                .intelligenceDestination("email")
                .stepState("completed")
                .triggerReason("completion")
                .published(false)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        IntelligenceEventLogDto dto = mapper.toDto(entity);

        assertNull(dto.getStepInstanceId());
        assertNull(dto.getDeviationId());
        assertNull(dto.getStepActionId());
        assertNull(dto.getEvaluationExpression());
        assertNull(dto.getEvaluationContext());
        assertFalse(dto.isPublished());
        assertNull(dto.getPublishedAt());
    }

    @Test
    void toDtoActionDefinitionList_mapsAll() {
        ActionDefinition entity = ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/test")
                .version("1.0")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.Task)
                .definition(objectMapper.createObjectNode())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        List<ActionDefinitionDto> dtos = mapper.toDtoActionDefinitionList(List.of(entity));

        assertEquals(1, dtos.size());
        assertEquals(entity.getId(), dtos.get(0).getId());
    }

    @Test
    void toDtoActionDefinitionList_nullReturnsEmpty() {
        List<ActionDefinitionDto> dtos = mapper.toDtoActionDefinitionList(null);
        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }

    @Test
    void toDtoIntelligenceEventLogList_mapsAll() {
        JsonNode eventPayload = objectMapper.valueToTree(Map.of("id", UUID.randomUUID().toString()));
        IntelligenceEventLog entity = IntelligenceEventLog.builder()
                .id(UUID.randomUUID())
                .eventPayload(eventPayload)
                .actionDefinitionId(UUID.randomUUID())
                .protocolInstanceId(UUID.randomUUID())
                .subject("patient-1")
                .actionType("Task")
                .intelligenceDestination("email")
                .stepState("completed")
                .triggerReason("completion")
                .published(true)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        List<IntelligenceEventLogDto> dtos = mapper.toDtoIntelligenceEventLogList(List.of(entity));

        assertEquals(1, dtos.size());
        assertEquals(entity.getId(), dtos.get(0).getId());
    }

    @Test
    void toDtoIntelligenceEventLogList_nullReturnsEmpty() {
        List<IntelligenceEventLogDto> dtos = mapper.toDtoIntelligenceEventLogList(null);
        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }
}
