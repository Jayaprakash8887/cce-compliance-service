package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.CompletionStatus;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private static final Set<StepState> ACTIONABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);

    private final StepInstanceRepository stepInstanceRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final ProtocolInstanceService protocolInstanceService;
    private final DeviationService deviationService;
    private final AuditService auditService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               PlanDefinitionParser planDefinitionParser,
                               ProtocolInstanceService protocolInstanceService,
                               DeviationService deviationService,
                               AuditService auditService,
                               IntelligenceActionEvaluator intelligenceActionEvaluator) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.protocolInstanceService = protocolInstanceService;
        this.deviationService = deviationService;
        this.auditService = auditService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
    }

    /**
     * Create a new step instance in PENDING state.
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String actionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime overdueDate, OffsetDateTime missedDate,
                                   String requiredBehavior) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(repeatIndex)
                .state(StepState.PENDING)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .missedDate(missedDate)
                .requiredBehavior(requiredBehavior)
                .build();

        step = stepInstanceRepository.save(step);

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}",
                actionId, repeatIndex, protocolInstance.getId(), step.getId());

        return step;
    }

    /**
     * Complete a step instance. Determines completion status based on timing,
     * triggers progressive step instantiation for dependent steps, and checks
     * if the protocol is now complete.
     */
    public void completeStep(StepInstance step, UUID matchedEventId, String completedBySource) {
        if (!ACTIONABLE_STATES.contains(step.getState())) {
            throw new IllegalStateException(
                    "Cannot complete step in state " + step.getState() + ": " + step.getId());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompletionStatus completionStatus = determineCompletionStatus(step, now);
        step.setState(StepState.COMPLETED);
        step.setCompletedAt(now);
        step.setMatchedEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);
        step.setCompletionStatus(completionStatus);

        stepInstanceRepository.save(step);

        auditService.audit("COMPLIANCE", "STEP_COMPLETED", "system",
                "StepInstance", step.getId().toString(),
                Map.of("actionId", step.getActionId(),
                        "completionStatus", step.getCompletionStatus().name(),
                        "protocolInstanceId", step.getProtocolInstance().getId().toString()));

        log.info("Completed step: stepId={}, actionId={}, completionStatus={}",
                step.getId(), step.getActionId(), step.getCompletionStatus());

        // Parse protocol definition once — reused by progressive instantiation
        var definition = step.getProtocolInstance().getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var steps = planDefinitionParser.extractSteps(planDefinition);

        // Detect order violations (must-have prerequisites still incomplete)
        detectOrderViolations(step, steps);

        // Progressive step instantiation for dependent steps
        createDependentSteps(step, steps);

        // Auto-skip preceding optional (could) steps that are still actionable
        autoSkipPrecedingOptionalSteps(step, steps);

        // Check if protocol is now complete
        protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
    }

    /**
     * Apply a scheduler-driven state transition.
     * Creates deviations for OVERDUE and MISSED transitions.
     */
    public void applySchedulerTransition(SchedulerTriggerMessage trigger) {
        StepInstance step = findByIdOrThrow(trigger.getStepInstanceId());

        switch (trigger.getTransitionType()) {
            case "PENDING_TO_DUE" -> applyTransition(step, StepState.PENDING, StepState.DUE);
            case "DUE_TO_OVERDUE" -> {
                applyTransition(step, StepState.DUE, StepState.OVERDUE);
                Deviation deviation = deviationService.createDeviation(step, DeviationType.OVERDUE);
                intelligenceActionEvaluator.evaluateOnDeviation(step, deviation);
            }
            case "OVERDUE_TO_MISSED" -> {
                if ("could".equals(step.getRequiredBehavior())) {
                    applyTransition(step, StepState.OVERDUE, StepState.SKIPPED);
                    log.info("Optional step {} skipped instead of missed (requiredBehavior=could)",
                            step.getId());
                } else {
                    applyTransition(step, StepState.OVERDUE, StepState.MISSED);
                    Deviation deviation = deviationService.createDeviation(step, DeviationType.MISSED);
                    intelligenceActionEvaluator.evaluateOnDeviation(step, deviation);
                }
                // Check if protocol is now complete (MISSED/SKIPPED are terminal)
                protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
            }
            default -> throw new IllegalArgumentException(
                    "Unknown transition type: " + trigger.getTransitionType());
        }
    }

    @Transactional(readOnly = true)
    public StepInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<StepInstance> findByProtocolInstanceId(UUID protocolInstanceId) {
        return stepInstanceRepository.findByProtocolInstanceId(protocolInstanceId);
    }

    /**
     * Find the first actionable step (PENDING, DUE, OVERDUE) for a given protocol instance and actionId.
     * Returns null if no actionable step exists.
     */
    @Transactional(readOnly = true)
    public StepInstance findActionableStep(UUID protocolInstanceId, String actionId) {
        List<StepInstance> steps = stepInstanceRepository
                .findByProtocolInstanceIdAndActionIdAndStateIn(protocolInstanceId, actionId, ACTIONABLE_STATES);
        return steps.isEmpty() ? null : steps.get(0);
    }

    private void applyTransition(StepInstance step, StepState expectedState, StepState newState) {
        if (step.getState() != expectedState) {
            log.warn("Step {} is in state {} — expected {} for transition to {}. Skipping.",
                    step.getId(), step.getState(), expectedState, newState);
            return;
        }

        step.setState(newState);
        stepInstanceRepository.save(step);

        log.info("Transitioned step {} from {} to {} (actionId={})",
                step.getId(), expectedState, newState, step.getActionId());
    }



    /**
     * Detect order violations: when a step completes, check if any immediate
     * predecessor steps (with requiredBehavior="must") are still in
     * non-terminal incomplete states (PENDING, DUE, OVERDUE).
     * A predecessor of action X is any action whose relatedSteps list contains X.
     */
    private void detectOrderViolations(StepInstance completedStep,
                                       List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();
        String completedActionId = completedStep.getActionId();

        // Find immediate predecessors: actions whose relatedSteps contain this action's id
        // and that have requiredBehavior="must"
        List<String> mustPredecessorIds = steps.stream()
                .filter(a -> "must".equals(a.requiredBehavior()))
                .filter(a -> a.relatedSteps().stream()
                        .anyMatch(ra -> completedActionId.equals(ra.actionId())))
                .map(PlanDefinitionParser.StepMetadata::id)
                .toList();

        if (mustPredecessorIds.isEmpty()) {
            return;
        }

        // Check if any must-predecessor steps are still in actionable (incomplete) states
        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(protocolInstance.getId());

        List<String> incompletePrerequisites = new ArrayList<>();
        for (String predecessorId : mustPredecessorIds) {
            boolean hasIncomplete = siblings.stream()
                    .filter(s -> predecessorId.equals(s.getActionId()))
                    .anyMatch(s -> ACTIONABLE_STATES.contains(s.getState()));
            if (hasIncomplete) {
                incompletePrerequisites.add(predecessorId);
            }
        }

        if (!incompletePrerequisites.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("incompletePrerequisites", incompletePrerequisites);
            metadata.put("completedActionId", completedActionId);

            Deviation deviation = deviationService.createDeviation(completedStep,
                    DeviationType.ORDER_VIOLATION, metadata);

            log.warn("Order violation detected: step {} (actionId={}) completed while "
                            + "prerequisite steps {} are still incomplete",
                    completedStep.getId(), completedActionId, incompletePrerequisites);

            intelligenceActionEvaluator.evaluateOnDeviation(completedStep, deviation);
        }
    }

    /**
     * Progressive step instantiation: when a step completes, create dependent PENDING
     * steps from relatedStep definitions with calculated due dates.
     */
    private void createDependentSteps(StepInstance completedStep,
                                      List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Find the completed step's metadata to get its relatedSteps
        PlanDefinitionParser.StepMetadata completedStepMetadata = steps.stream()
                .filter(a -> completedStep.getActionId().equals(a.id()))
                .findFirst()
                .orElse(null);

        if (completedStepMetadata == null || completedStepMetadata.relatedSteps().isEmpty()) {
            return;
        }

        for (PlanDefinitionParser.RelatedStepInfo relatedStep : completedStepMetadata.relatedSteps()) {
            // after-start: offset from when predecessor became active (dueDate)
            // after-end (default): offset from when predecessor completed (completedAt)
            OffsetDateTime baseTime = "after-start".equals(relatedStep.relationship())
                    ? completedStep.getDueDate()
                    : completedStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, relatedStep);

            // Find the target action's timing for overdue/missed dates
            PlanDefinitionParser.StepMetadata targetAction = steps.stream()
                    .filter(a -> relatedStep.actionId().equals(a.id()))
                    .findFirst()
                    .orElse(null);

            String requiredBehavior = targetAction != null ? targetAction.requiredBehavior() : null;

            // Create step instances — if timing specifies recurring, create N instances
            int repeatCount = 1;
            java.math.BigDecimal repeatPeriod = null;
            String repeatPeriodUnit = null;
            if (targetAction != null && targetAction.timing() != null) {
                PlanDefinitionParser.TimingInfo timing = targetAction.timing();
                if (timing.count() != null && timing.count() > 1) {
                    repeatCount = timing.count();
                    repeatPeriod = timing.period();
                    repeatPeriodUnit = timing.periodUnit();
                }
            }

            for (int i = 0; i < repeatCount; i++) {
                OffsetDateTime instanceDueDate = dueDate;
                if (i > 0 && repeatPeriod != null && repeatPeriodUnit != null) {
                    instanceDueDate = addOffset(dueDate, repeatPeriod.longValue() * i, repeatPeriodUnit);
                }

                OffsetDateTime instanceOverdueDate = null;
                OffsetDateTime instanceMissedDate = null;
                if (targetAction != null && targetAction.toleranceDays() != null) {
                    instanceOverdueDate = instanceDueDate.plusDays(targetAction.toleranceDays());
                    instanceMissedDate = instanceOverdueDate.plusDays(targetAction.toleranceDays());
                }

                createStep(protocolInstance, relatedStep.actionId(),
                        i, instanceDueDate, instanceOverdueDate, instanceMissedDate,
                        requiredBehavior);

                log.info("Progressive instantiation: created step {} (repeat {}/{}) due at {} (triggered by {}, relationship={})",
                        relatedStep.actionId(), i, repeatCount, instanceDueDate,
                        completedStep.getActionId(), relatedStep.relationship());
            }
        }
    }

    /**
     * Auto-skip preceding optional steps when a subsequent step completes.
     * Only skips steps that are direct ancestors (predecessors in the dependency graph)
     * of the completed step AND have requiredBehavior=could AND are still actionable.
     *
     * A predecessor of action X is any action whose relatedSteps list contains X
     * (i.e., completing that action would create X). This is computed transitively
     * to cover the full ancestor chain.
     */
    private void autoSkipPrecedingOptionalSteps(StepInstance completedStep,
                                                List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Compute all ancestor actionIds of the completed step (transitive predecessors)
        Set<String> ancestorActionIds = computeAncestors(completedStep.getActionId(), steps);

        if (ancestorActionIds.isEmpty()) {
            return;
        }

        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(protocolInstance.getId());

        for (StepInstance sibling : siblings) {
            if (sibling.getId().equals(completedStep.getId())) continue;
            if (!"could".equals(sibling.getRequiredBehavior())) continue;
            if (!ACTIONABLE_STATES.contains(sibling.getState())) continue;
            if (!ancestorActionIds.contains(sibling.getActionId())) continue;

            sibling.setState(StepState.SKIPPED);
            stepInstanceRepository.save(sibling);

            log.info("Auto-skipped predecessor optional step {} (actionId={}) " +
                            "due to completion of step {} (actionId={})",
                    sibling.getId(), sibling.getActionId(),
                    completedStep.getId(), completedStep.getActionId());
        }
    }

    /**
     * Compute all transitive ancestors of a given actionId in the dependency graph.
     * An ancestor of X is any action A such that A's relatedSteps (directly or transitively)
     * lead to X being created.
     */
    private Set<String> computeAncestors(String actionId,
                                         List<PlanDefinitionParser.StepMetadata> steps) {
        Set<String> ancestors = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(actionId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            // Find all actions whose relatedSteps contain 'current'
            for (PlanDefinitionParser.StepMetadata action : steps) {
                boolean createsTarget = action.relatedSteps().stream()
                        .anyMatch(ra -> current.equals(ra.actionId()));
                if (createsTarget && !ancestors.contains(action.id())) {
                    ancestors.add(action.id());
                    queue.add(action.id());
                }
            }
        }
        return ancestors;
    }

    private OffsetDateTime calculateDueDate(OffsetDateTime baseTime,
                                            PlanDefinitionParser.RelatedStepInfo relatedStep) {
        if (relatedStep.offsetValue() == null) {
            return baseTime;
        }

        long offsetAmount = relatedStep.offsetValue().longValue();
        String unit = relatedStep.offsetUnit();

        if (unit == null) {
            return baseTime;
        }

        return addOffset(baseTime, offsetAmount, unit);
    }

    private OffsetDateTime addOffset(OffsetDateTime base, long amount, String unit) {
        return switch (unit) {
            case "d" -> base.plus(amount, ChronoUnit.DAYS);
            case "h" -> base.plus(amount, ChronoUnit.HOURS);
            case "min" -> base.plus(amount, ChronoUnit.MINUTES);
            case "wk" -> base.plus(amount * 7, ChronoUnit.DAYS);
            case "mo" -> base.plusMonths(amount);
            case "a" -> base.plusYears(amount);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    /**
     * Determine completion status based on timing:
     * EARLY (before dueDate), ON_TIME (between due and overdue), LATE (after overdueDate or state was OVERDUE).
     */
    private CompletionStatus determineCompletionStatus(StepInstance step, OffsetDateTime completedAt) {
        if (step.getState() == StepState.OVERDUE) {
            return CompletionStatus.LATE;
        }

        if (step.getDueDate() != null && completedAt.isBefore(step.getDueDate())) {
            return CompletionStatus.EARLY;
        }

        if (step.getOverdueDate() != null && completedAt.isAfter(step.getOverdueDate())) {
            return CompletionStatus.LATE;
        }

        return CompletionStatus.ON_TIME;
    }

    private StepInstance findByIdOrThrow(UUID id) {
        return stepInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Step instance not found: " + id));
    }
}
