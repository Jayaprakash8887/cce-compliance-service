package org.openphc.cce.compliance.service;

import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.ProtocolInstanceHistory;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.entity.StepInstanceHistory;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceHistoryRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Writes append-only state-transition history for protocol and step instances.
 *
 * <p>This replaces the former database triggers (V4) with application-level capture. Callers
 * invoke a {@code record*} method immediately after each status/state change. Because these
 * methods run with {@link Propagation#MANDATORY} inside the caller's already-open transaction,
 * the history INSERT commits atomically with the base-row change — the same no-gap, all-or-nothing
 * guarantee the trigger provided. Any code path that mutates a lifecycle column MUST call the
 * matching method here, or that transition will be missing from history.
 *
 * <p>Rows are only ever INSERTed; the history tables are never updated or deleted.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StateTransitionHistoryService {

    private final ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository;
    private final StepInstanceHistoryRepository stepInstanceHistoryRepository;

    public StateTransitionHistoryService(ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository,
                               StepInstanceHistoryRepository stepInstanceHistoryRepository) {
        this.protocolInstanceHistoryRepository = protocolInstanceHistoryRepository;
        this.stepInstanceHistoryRepository = stepInstanceHistoryRepository;
    }

    /**
     * Record the current status of a protocol instance as a history row.
     * Call this right after persisting an enrollment or a status transition.
     *
     * @param protocolInstance the protocol instance whose status was just (re)set
     * @param changedAt        the moment the transition took effect
     */
    public void recordProtocolInstanceTransition(ProtocolInstance protocolInstance, OffsetDateTime changedAt) {
        protocolInstanceHistoryRepository.save(ProtocolInstanceHistory.builder()
                .protocolInstanceId(protocolInstance.getId())
                .protocolDefinitionId(protocolInstance.getProtocolDefinition().getId())
                .status(protocolInstance.getStatus().name())
                .changedAt(changedAt)
                .build());
    }

    /**
     * Record the current state (and completion status) of a step instance as a history row.
     * Call this right after persisting a step creation or a state/completion-status transition.
     *
     * @param stepInstance the step instance whose state was just (re)set
     * @param changedAt    the moment the transition took effect
     */
    public void recordStepInstanceTransition(StepInstance stepInstance, OffsetDateTime changedAt) {
        stepInstanceHistoryRepository.save(StepInstanceHistory.builder()
                .stepInstanceId(stepInstance.getId())
                .protocolInstanceId(stepInstance.getProtocolInstance().getId())
                .state(stepInstance.getState().name())
                .completionStatus(stepInstance.getCompletionStatus() != null
                        ? stepInstance.getCompletionStatus().name()
                        : null)
                .changedAt(changedAt)
                .build());
    }
}
