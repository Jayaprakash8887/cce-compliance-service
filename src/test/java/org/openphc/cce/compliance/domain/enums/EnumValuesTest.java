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
        assertEquals(2, DeviationType.values().length);
        assertNotNull(DeviationType.valueOf("OVERDUE"));
        assertNotNull(DeviationType.valueOf("MISSED"));
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
}
