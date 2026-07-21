package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProtocolInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolInstanceService.class);

    private static final Set<StepState> TERMINAL_STATES = Set.of(
            StepState.COMPLETED, StepState.MISSED, StepState.SKIPPED);

    private static final Set<StepState> ACTIONABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);

    private final ProtocolInstanceRepository protocolInstanceRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final AuditService auditService;
    private final StateTransitionHistoryService stateTransitionHistoryService;
    private final PlanDefinitionParser planDefinitionParser;

    public ProtocolInstanceService(ProtocolInstanceRepository protocolInstanceRepository,
                                   StepInstanceRepository stepInstanceRepository,
                                   AuditService auditService,
                                   StateTransitionHistoryService stateTransitionHistoryService,
                                   PlanDefinitionParser planDefinitionParser) {
        this.protocolInstanceRepository = protocolInstanceRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.auditService = auditService;
        this.stateTransitionHistoryService = stateTransitionHistoryService;
        this.planDefinitionParser = planDefinitionParser;
    }

    /**
     * Enroll a patient in a protocol. If the patient already has an ACTIVE instance
     * for the same protocol definition, the existing instance is returned (skip re-enrollment).
     */
    public ProtocolInstance enrollPatient(String patientId, ProtocolDefinition protocolDef,
                                         OffsetDateTime enrolledAt) {
        // Check for existing ACTIVE enrollment
        Optional<ProtocolInstance> existing = protocolInstanceRepository
                .findByPatientIdAndProtocolDefinitionIdAndStatus(
                        patientId, protocolDef.getId(), ProtocolInstanceStatus.ACTIVE);

        if (existing.isPresent()) {
            log.info("Patient {} already enrolled in protocol {} — skipping",
                    patientId, protocolDef.getCanonical());
            return existing.get();
        }

        ProtocolInstance instance = ProtocolInstance.builder()
                .patientId(patientId)
                .protocolDefinition(protocolDef)
                .protocolCanonical(protocolDef.getCanonical())
                .enrolledAt(enrolledAt)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        instance = protocolInstanceRepository.save(instance);

        // Capture the initial ACTIVE status in append-only history.
        stateTransitionHistoryService.recordProtocolInstanceTransition(instance, instance.getEnrolledAt());

        auditService.audit("COMPLIANCE", "PROTOCOL_ENROLLED", "system",
                "ProtocolInstance", instance.getId().toString(),
                Map.of("patientId", patientId,
                        "protocolCanonical", protocolDef.getCanonical(),
                        "protocolDefinitionId", protocolDef.getId().toString()));

        log.info("Enrolled patient {} in protocol {} (instanceId={})",
                patientId, protocolDef.getCanonical(), instance.getId());

        return instance;
    }

    /**
     * Complete the protocol instance only when there is no outstanding mandatory work.
     *
     * <p>Under progressive instantiation a step can be created directly from its own trigger
     * (see {@code ComplianceEngine.createInitialStep}) without its mandatory predecessors ever
     * being instantiated. Judging completion purely from the materialized step rows would then
     * mark such an instance COMPLETED after a single terminal step (e.g. a lone
     * {@code consultation} in emr-service-protocol). So completion requires BOTH:
     * <ol>
     *   <li>no materialized step is still actionable (PENDING/DUE/OVERDUE), and</li>
     *   <li>every mandatory ("must") action on the path leading to any observed step has a
     *       terminal step instance — where "on the path" means the action itself, a transitive
     *       predecessor (ancestor) of an observed action, or a mandatory action nested under the
     *       same top-level PlanDefinition action as an observed action (a "group sibling") —
     *       even one whose own trigger never fired and was therefore never materialized. A
     *       mandatory step is therefore only required once progress that depends on it, or that
     *       shares its nesting group, has actually been seen, which keeps genuinely short
     *       journeys completable while blocking premature completion when a mandatory
     *       prerequisite or sibling sub-step was skipped.</li>
     * </ol>
     *
     * <p><b>Disabled:</b> the criteria above is not yet finalized, so the COMPLETED transition
     * itself is switched off for now — this method only logs when an instance would otherwise
     * qualify. Remove the early return below once the criteria is agreed.
     */
    public void checkAndCompleteProtocol(UUID instanceId) {
        ProtocolInstance instance = findByIdOrThrow(instanceId);

        if (instance.getStatus() != ProtocolInstanceStatus.ACTIVE) {
            return;
        }

        List<StepInstance> materializedSteps = stepInstanceRepository.findByProtocolInstanceId(instanceId);
        if (materializedSteps.isEmpty()) {
            return;
        }

        // (1) any still-actionable step means there is outstanding work
        boolean anyActionable = materializedSteps.stream()
                .anyMatch(s -> ACTIONABLE_STATES.contains(s.getState()));
        if (anyActionable) {
            return;
        }

        // (2) every mandatory action expected by the progress observed so far must be terminal
        Set<String> terminalActionIds = materializedSteps.stream()
                .filter(s -> TERMINAL_STATES.contains(s.getState()))
                .map(StepInstance::getActionId)
                .collect(Collectors.toSet());

        List<PlanDefinitionParser.StepMetadata> steps = planDefinitionParser.extractSteps(
                planDefinitionParser.parse(
                        instance.getProtocolDefinition().getDefinition().toString()));

        List<String> unsatisfiedMustActions = computeExpectedMustActions(materializedSteps, steps).stream()
                .filter(actionId -> !terminalActionIds.contains(actionId))
                .toList();

        if (!unsatisfiedMustActions.isEmpty()) {
            log.info("Protocol instance {} not completed — mandatory steps outstanding: {}",
                    instanceId, unsatisfiedMustActions);
            return;
        }

        log.info("Protocol instance {} satisfies all known completion criteria ({} steps materialized), " +
                "but auto-completion is currently disabled pending finalized criteria",
                instanceId, materializedSteps.size());
    }

    /**
     * The mandatory ("must") action ids the instance is expected to satisfy given the progress
     * observed so far: for every materialized step, itself, all its transitive predecessors, and
     * all mandatory actions nested under the same top-level PlanDefinition action (group
     * siblings) — restricted to actions whose requiredBehavior is "must".
     */
    private Set<String> computeExpectedMustActions(List<StepInstance> materializedSteps,
                                                   List<PlanDefinitionParser.StepMetadata> steps) {
        Set<String> mustActionIds = steps.stream()
                .filter(a -> "must".equals(a.requiredBehavior()))
                .map(PlanDefinitionParser.StepMetadata::id)
                .collect(Collectors.toSet());
        if (mustActionIds.isEmpty()) {
            return Set.of();
        }

        Set<String> observedActionIds = materializedSteps.stream()
                .map(StepInstance::getActionId)
                .collect(Collectors.toSet());

        Set<String> expected = new HashSet<>();
        for (String actionId : observedActionIds) {
            if (mustActionIds.contains(actionId)) {
                expected.add(actionId);
            }
            for (String ancestorId : PlanDefinitionParser.computeAncestors(actionId, steps)) {
                if (mustActionIds.contains(ancestorId)) {
                    expected.add(ancestorId);
                }
            }
            expected.addAll(PlanDefinitionParser.computeMustGroupActions(actionId, steps));
        }
        return expected;
    }

    @Transactional(readOnly = true)
    public ProtocolInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    private ProtocolInstance findByIdOrThrow(UUID id) {
        return protocolInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol instance not found: " + id));
    }
}
