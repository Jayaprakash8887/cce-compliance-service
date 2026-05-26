package org.openphc.cce.compliance.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnumValuesTest {

    @Test
    void protocolDefinitionStatus_hasCorrectValues() {
        assertEquals(2, ProtocolDefinitionStatus.values().length);
        assertNotNull(ProtocolDefinitionStatus.valueOf("ACTIVE"));
        assertNotNull(ProtocolDefinitionStatus.valueOf("RETIRED"));
    }

    @Test
    void protocolInstanceStatus_hasCorrectValues() {
        assertEquals(4, ProtocolInstanceStatus.values().length);
        assertNotNull(ProtocolInstanceStatus.valueOf("ACTIVE"));
        assertNotNull(ProtocolInstanceStatus.valueOf("COMPLETED"));
        assertNotNull(ProtocolInstanceStatus.valueOf("WITHDRAWN"));
        assertNotNull(ProtocolInstanceStatus.valueOf("EXPIRED"));
    }

    @Test
    void stepState_hasCorrectValues() {
        assertEquals(6, StepState.values().length);
        assertNotNull(StepState.valueOf("PENDING"));
        assertNotNull(StepState.valueOf("DUE"));
        assertNotNull(StepState.valueOf("OVERDUE"));
        assertNotNull(StepState.valueOf("MISSED"));
        assertNotNull(StepState.valueOf("COMPLETED"));
        assertNotNull(StepState.valueOf("SKIPPED"));
    }

    @Test
    void completionStatus_hasCorrectValues() {
        assertEquals(3, CompletionStatus.values().length);
        assertNotNull(CompletionStatus.valueOf("EARLY"));
        assertNotNull(CompletionStatus.valueOf("ON_TIME"));
        assertNotNull(CompletionStatus.valueOf("LATE"));
    }

    @Test
    void deviationType_hasCorrectValues() {
        assertEquals(3, DeviationType.values().length);
        assertNotNull(DeviationType.valueOf("OVERDUE"));
        assertNotNull(DeviationType.valueOf("MISSED"));
        assertNotNull(DeviationType.valueOf("ORDER_VIOLATION"));
    }

    @Test
    void processingStatus_hasCorrectValues() {
        assertEquals(3, ProcessingStatus.values().length);
        assertNotNull(ProcessingStatus.valueOf("MATCHED"));
        assertNotNull(ProcessingStatus.valueOf("ZERO_MATCH"));
        assertNotNull(ProcessingStatus.valueOf("DUPLICATE"));
    }

    @Test
    void failureStage_hasCorrectValues() {
        assertEquals(3, FailureStage.values().length);
        assertNotNull(FailureStage.valueOf("KAFKA_PUBLISH"));
        assertNotNull(FailureStage.valueOf("PROCESSING"));
        assertNotNull(FailureStage.valueOf("VALIDATION"));
    }

    @Test
    void actionDefinitionStatus_hasCorrectValues() {
        assertEquals(2, ActionDefinitionStatus.values().length);
        assertNotNull(ActionDefinitionStatus.valueOf("ACTIVE"));
        assertNotNull(ActionDefinitionStatus.valueOf("RETIRED"));
    }

    @Test
    void actionDefinitionKind_hasCorrectValues() {
        assertEquals(3, ActionDefinitionKind.values().length);
        assertNotNull(ActionDefinitionKind.valueOf("CommunicationRequest"));
        assertNotNull(ActionDefinitionKind.valueOf("Task"));
        assertNotNull(ActionDefinitionKind.valueOf("ServiceRequest"));
    }

    @Test
    void intelligenceSeverity_hasCorrectValues() {
        assertEquals(4, IntelligenceSeverity.values().length);
        assertNotNull(IntelligenceSeverity.valueOf("LOW"));
        assertNotNull(IntelligenceSeverity.valueOf("MEDIUM"));
        assertNotNull(IntelligenceSeverity.valueOf("HIGH"));
        assertNotNull(IntelligenceSeverity.valueOf("CRITICAL"));
    }

    @Test
    void actionType_hasCorrectValues() {
        assertEquals(2, ActionType.values().length);
        assertEquals("step", ActionType.STEP.getCode());
        assertEquals("fire-event", ActionType.FIRE_EVENT.getCode());
        assertEquals(ActionType.STEP, ActionType.fromCode("step"));
        assertEquals(ActionType.FIRE_EVENT, ActionType.fromCode("fire-event"));
        assertNull(ActionType.fromCode("unknown"));
    }
}
