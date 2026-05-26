package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
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
        return createStep(protocolInstance, actionId, repeatIndex, dueDate,
                overdueDate, missedDate, requiredBehavior, null);
    }

    /**
     * Create a new step instance in PENDING state with parent reference (for sub-steps).
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String actionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime overdueDate, OffsetDateTime missedDate,
                                   String requiredBehavior, UUID parentStepId) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(repeatIndex)
                .state(StepState.PENDING)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .missedDate(missedDate)
                .requiredBehavior(requiredBehavior)
                .parentStepId(parentStepId)
                .build();

        step = stepInstanceRepository.save(step);

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}{}",
                actionId, repeatIndex, protocolInstance.getId(), step.getId(),
                parentStepId != null ? ", parentStepId=" + parentStepId : "");

        return step;
    }

    /**
     * Create sub-step instances for a parent step that acts as a group.
     * Sub-steps are created in PENDING state with a reference to the parent step.
     */
    public List<StepInstance> createSubSteps(StepInstance parentStep,
                                             List<PlanDefinitionParser.StepMetadata> subStepInfos) {
        List<StepInstance> subSteps = new ArrayList<>();
        OffsetDateTime parentDueDate = parentStep.getDueDate() != null
                ? parentStep.getDueDate()
                : OffsetDateTime.now(ZoneOffset.UTC);

        for (PlanDefinitionParser.StepMetadata subStepInfo : subStepInfos) {
            // Only create entry-point sub-steps (those that don't depend on a sibling).
            // Dependent sub-steps are created progressively when their prerequisite completes.
            boolean dependsOnSibling = subStepInfo.relatedSteps().stream()
                    .anyMatch(ra -> subStepInfos.stream().anyMatch(s -> s.id().equals(ra.actionId())));

            if (dependsOnSibling) {
                continue;
            }

            OffsetDateTime dueDate = parentDueDate;
            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;

            if (subStepInfo.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(subStepInfo.toleranceDays());
                missedDate = overdueDate.plusDays(subStepInfo.toleranceDays());
            }

            StepInstance subStep = StepInstance.builder()
                    .protocolInstance(parentStep.getProtocolInstance())
                    .actionId(subStepInfo.id())
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .dueDate(dueDate)
                    .overdueDate(overdueDate)
                    .missedDate(missedDate)
                    .requiredBehavior(subStepInfo.requiredBehavior())
                    .parentStepId(parentStep.getId())
                    .build();

            subStep = stepInstanceRepository.save(subStep);
            subSteps.add(subStep);

            log.info("Created sub-step: actionId={}, parentStepId={}",
                    subStepInfo.id(), parentStep.getId());

            // If this sub-step is itself a group (has nested sub-steps), recursively create its entry-point children
            if (subStepInfo.hasSubSteps()) {
                createSubSteps(subStep, subStepInfo.subSteps());
            }
        }

        return subSteps;
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

        // Parse protocol definition once — reused by sub-step creation and progressive instantiation
        var definition = step.getProtocolInstance().getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        if (step.getParentStepId() != null) {
            // Sub-step completion: create dependent siblings via progressive instantiation
            createDependentSubSteps(step, actions);

            // If this sub-step has its own nested sub-steps, create entry-point children
            createEntryPointSubStepsForAction(step, step.getActionId(), actions);
        } else {
            // Top-level step completion logic
            // Detect order violations (must-have prerequisites still incomplete)
            detectOrderViolations(step);

            // Progressive step instantiation for dependent top-level steps
            createDependentSteps(step);

            // Auto-skip preceding optional (could) steps that are still actionable
            autoSkipPrecedingOptionalSteps(step);

            // If this step has sub-steps, create entry-point sub-steps
            createEntryPointSubStepsForAction(step, step.getActionId(), actions);

            // Check if protocol is now complete
            protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
        }
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

    /**
     * Find a step by protocol instance and actionId regardless of state.
     * Returns the most recently relevant step (prefers COMPLETED, then any state).
     */
    @Transactional(readOnly = true)
    public StepInstance findStepByProtocolAndActionId(UUID protocolInstanceId, String actionId) {
        List<StepInstance> steps = stepInstanceRepository
                .findByProtocolInstanceIdAndActionId(protocolInstanceId, actionId);
        if (steps.isEmpty()) return null;
        // Prefer completed steps (parent steps will be completed when sub-steps are triggered)
        return steps.stream()
                .filter(s -> s.getState() == StepState.COMPLETED)
                .findFirst()
                .orElse(steps.get(0));
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
    private void detectOrderViolations(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        JsonNode definition = protocolInstance.getProtocolDefinition().getDefinition();
        PlanDefinition planDefinition = planDefinitionParser.parse(definition.toString());
        List<PlanDefinitionParser.StepMetadata> actions = planDefinitionParser.extractActions(planDefinition);

        String completedActionId = completedStep.getActionId();

        // Find immediate predecessors: actions whose relatedSteps contain this action's id
        // and that have requiredBehavior="must"
        List<String> mustPredecessorIds = actions.stream()
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
    private void createDependentSteps(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Parse the protocol definition to get relatedStep info
        JsonNode definition = protocolInstance.getProtocolDefinition().getDefinition();
        PlanDefinition planDefinition = planDefinitionParser.parse(definition.toString());
        List<PlanDefinitionParser.StepMetadata> actions = planDefinitionParser.extractActions(planDefinition);

        // Find the completed step's metadata to get its relatedSteps
        PlanDefinitionParser.StepMetadata completedStepMetadata = actions.stream()
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
            PlanDefinitionParser.StepMetadata targetAction = actions.stream()
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

                StepInstance newStep = createStep(protocolInstance, relatedStep.actionId(),
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
    private void autoSkipPrecedingOptionalSteps(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Parse protocol to determine the dependency graph
        JsonNode definition = protocolInstance.getProtocolDefinition().getDefinition();
        PlanDefinition planDefinition = planDefinitionParser.parse(definition.toString());
        List<PlanDefinitionParser.StepMetadata> actions = planDefinitionParser.extractActions(planDefinition);

        // Compute all ancestor actionIds of the completed step (transitive predecessors)
        Set<String> ancestorActionIds = computeAncestors(completedStep.getActionId(), actions);

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
                                         List<PlanDefinitionParser.StepMetadata> actions) {
        Set<String> ancestors = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(actionId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            // Find all actions whose relatedSteps contain 'current'
            for (PlanDefinitionParser.StepMetadata action : actions) {
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

    /**
     * Create dependent sub-steps within the same parent group when a sibling sub-step completes.
     * Uses relatedStep definitions scoped within the parent's sub-step list.
     */
    private void createDependentSubSteps(StepInstance completedSubStep,
                                         List<PlanDefinitionParser.StepMetadata> actions) {
        ProtocolInstance protocolInstance = completedSubStep.getProtocolInstance();
        UUID parentStepId = completedSubStep.getParentStepId();
        StepInstance parentStep = findByIdOrThrow(parentStepId);
        String parentActionId = parentStep.getActionId();

        // Find the sub-steps list for the parent action (may be top-level or nested)
        List<PlanDefinitionParser.StepMetadata> parentSubSteps = resolveSubSteps(parentActionId, actions);
        if (parentSubSteps == null || parentSubSteps.isEmpty()) {
            return;
        }

        // Find the completed sub-step's metadata
        PlanDefinitionParser.StepMetadata completedSubStepInfo = parentSubSteps.stream()
                .filter(s -> completedSubStep.getActionId().equals(s.id()))
                .findFirst()
                .orElse(null);

        if (completedSubStepInfo == null || completedSubStepInfo.relatedSteps().isEmpty()) {
            return;
        }

        // Create dependent sub-steps
        for (PlanDefinitionParser.RelatedStepInfo relatedStep : completedSubStepInfo.relatedSteps()) {
            // Guard: skip if this sub-step already exists for the same parent
            StepInstance existing = stepInstanceRepository
                    .findByProtocolInstanceIdAndActionIdAndStateIn(
                            protocolInstance.getId(), relatedStep.actionId(), ACTIONABLE_STATES)
                    .stream()
                    .filter(s -> parentStepId.equals(s.getParentStepId()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                continue;
            }

            OffsetDateTime baseTime = "after-start".equals(relatedStep.relationship())
                    ? completedSubStep.getDueDate()
                    : completedSubStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedSubStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, relatedStep);

            // Find target sub-step info for tolerance
            PlanDefinitionParser.StepMetadata targetSubStep = parentSubSteps.stream()
                    .filter(s -> relatedStep.actionId().equals(s.id()))
                    .findFirst()
                    .orElse(null);

            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            String requiredBehavior = targetSubStep != null ? targetSubStep.requiredBehavior() : null;

            if (targetSubStep != null && targetSubStep.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(targetSubStep.toleranceDays());
                missedDate = overdueDate.plusDays(targetSubStep.toleranceDays());
            }

            StepInstance subStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId(relatedStep.actionId())
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .dueDate(dueDate)
                    .overdueDate(overdueDate)
                    .missedDate(missedDate)
                    .requiredBehavior(requiredBehavior)
                    .parentStepId(parentStepId)
                    .build();

            subStep = stepInstanceRepository.save(subStep);

            log.info("Progressive sub-step instantiation: created sub-step {} due at {} " +
                            "(triggered by sub-step {}, parent={})",
                    relatedStep.actionId(), dueDate,
                    completedSubStep.getActionId(), parentActionId);

            // If the newly created sub-step has its own nested sub-steps, DON'T create them now —
            // they'll be created when this sub-step completes (trigger-driven).
        }
    }

    /**
     * Create entry-point sub-steps for a completed step by resolving its sub-step metadata.
     * Searches top-level actions and recursively through nested sub-steps to find the action.
     */
    private void createEntryPointSubStepsForAction(StepInstance completedStep, String actionId,
                                                    List<PlanDefinitionParser.StepMetadata> actions) {
        List<PlanDefinitionParser.StepMetadata> subSteps = resolveSubSteps(actionId, actions);
        if (subSteps != null && !subSteps.isEmpty()) {
            createSubSteps(completedStep, subSteps);
        }
    }

    /**
     * Resolve the sub-steps list for a given action ID by searching top-level
     * actions and recursively through nested sub-steps.
     */
    private List<PlanDefinitionParser.StepMetadata> resolveSubSteps(
            String actionId, List<PlanDefinitionParser.StepMetadata> actions) {
        for (PlanDefinitionParser.StepMetadata action : actions) {
            if (actionId.equals(action.id())) {
                return action.subSteps();
            }
            List<PlanDefinitionParser.StepMetadata> result =
                    resolveSubStepsRecursive(actionId, action.subSteps());
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private List<PlanDefinitionParser.StepMetadata> resolveSubStepsRecursive(
            String actionId, List<PlanDefinitionParser.StepMetadata> subSteps) {
        if (subSteps == null) return null;
        for (PlanDefinitionParser.StepMetadata subStep : subSteps) {
            if (actionId.equals(subStep.id())) {
                return subStep.subSteps();
            }
            List<PlanDefinitionParser.StepMetadata> result =
                    resolveSubStepsRecursive(actionId, subStep.subSteps());
            if (result != null) {
                return result;
            }
        }
        return null;
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
