package org.openphc.cce.compliance.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.ProtocolInstanceHistory;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.entity.StepInstanceHistory;
import org.openphc.cce.compliance.domain.enums.CompletionStatus;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceHistoryRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceHistoryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link StateTransitionHistoryService}, which appends immutable history rows for
 * protocol- and step-instance transitions. Verifies the captured history row mirrors the current
 * instance state, including the nullable step completion status.
 */
@ExtendWith(MockitoExtension.class)
class StateTransitionHistoryServiceTest {

    @Mock
    private ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository;

    @Mock
    private StepInstanceHistoryRepository stepInstanceHistoryRepository;

    private StateTransitionHistoryService service;

    private final OffsetDateTime changedAt = OffsetDateTime.of(2026, 6, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new StateTransitionHistoryService(
                protocolInstanceHistoryRepository, stepInstanceHistoryRepository);
    }

    @Test
    void recordProtocolInstanceTransition_savesHistoryMirroringInstance() {
        UUID protocolInstanceId = UUID.randomUUID();
        ProtocolInstance instance = ProtocolInstance.builder()
                .id(protocolInstanceId)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        service.recordProtocolInstanceTransition(instance, changedAt);

        ArgumentCaptor<ProtocolInstanceHistory> captor =
                ArgumentCaptor.forClass(ProtocolInstanceHistory.class);
        verify(protocolInstanceHistoryRepository).save(captor.capture());
        ProtocolInstanceHistory saved = captor.getValue();
        assertEquals(protocolInstanceId, saved.getProtocolInstanceId());
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(changedAt, saved.getChangedAt());
    }

    @Test
    void recordStepInstanceTransition_withCompletionStatus_savesAllFields() {
        UUID stepInstanceId = UUID.randomUUID();
        StepInstance step = StepInstance.builder()
                .id(stepInstanceId)
                .state(StepState.COMPLETED)
                .completionStatus(CompletionStatus.ON_TIME)
                .build();

        service.recordStepInstanceTransition(step, changedAt);

        ArgumentCaptor<StepInstanceHistory> captor = ArgumentCaptor.forClass(StepInstanceHistory.class);
        verify(stepInstanceHistoryRepository).save(captor.capture());
        StepInstanceHistory saved = captor.getValue();
        assertEquals(stepInstanceId, saved.getStepInstanceId());
        assertEquals("COMPLETED", saved.getState());
        assertEquals("ON_TIME", saved.getCompletionStatus());
        assertEquals(changedAt, saved.getChangedAt());
    }

    @Test
    void recordStepInstanceTransition_withNullCompletionStatus_savesNullCompletion() {
        UUID stepInstanceId = UUID.randomUUID();
        StepInstance step = StepInstance.builder()
                .id(stepInstanceId)
                .state(StepState.DUE)
                .completionStatus(null)
                .build();

        service.recordStepInstanceTransition(step, changedAt);

        ArgumentCaptor<StepInstanceHistory> captor = ArgumentCaptor.forClass(StepInstanceHistory.class);
        verify(stepInstanceHistoryRepository).save(captor.capture());
        StepInstanceHistory saved = captor.getValue();
        assertEquals("DUE", saved.getState());
        assertNull(saved.getCompletionStatus());
    }
}
