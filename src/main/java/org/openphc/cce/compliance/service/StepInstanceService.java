package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               PlanDefinitionParser planDefinitionParser,
                               ProtocolInstanceService protocolInstanceService,
                               DeviationService deviationService,
                               AuditService auditService) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.protocolInstanceService = protocolInstanceService;
        this.deviationService = deviationService;
        this.auditService = auditService;
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

        // Progressive step instantiation
        createDependentSteps(step);

        // Auto-skip preceding optional (could) steps that are still actionable
        autoSkipPrecedingOptionalSteps(step);

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
                createDeviation(step, DeviationType.OVERDUE);
            }
            case "OVERDUE_TO_MISSED" -> {
                if ("could".equals(step.getRequiredBehavior())) {
                    applyTransition(step, StepState.OVERDUE, StepState.SKIPPED);
                    log.info("Optional step {} skipped instead of missed (requiredBehavior=could)",
                            step.getId());
                } else {
                    applyTransition(step, StepState.OVERDUE, StepState.MISSED);
                    createDeviation(step, DeviationType.MISSED);
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

    private void createDeviation(StepInstance step, DeviationType deviationType) {
        ProtocolInstance protocolInstance = step.getProtocolInstance();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (deviationType == DeviationType.OVERDUE && step.getDueDate() != null) {
            metadata.put("daysOverdue",
                    Duration.between(step.getDueDate(), now).toDays());
        }
        if (deviationType == DeviationType.MISSED && step.getMissedDate() != null) {
            metadata.put("daysPastMissedDate",
                    Duration.between(step.getMissedDate(), now).toDays());
        }

        deviationService.recordDeviation(protocolInstance, step, deviationType,
                metadata.isEmpty() ? null : metadata);
    }

    /**
     * Progressive step instantiation: when a step completes, create dependent PENDING
     * steps from relatedAction definitions with calculated due dates.
     */
    private void createDependentSteps(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Parse the protocol definition to get relatedAction info
        var definition = protocolInstance.getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        // Find the completed action's metadata to get its relatedActions
        var completedActionMetadata = actions.stream()
                .filter(a -> completedStep.getActionId().equals(a.id()))
                .findFirst()
                .orElse(null);

        if (completedActionMetadata == null || completedActionMetadata.relatedActions().isEmpty()) {
            return;
        }

        OffsetDateTime completedAt = completedStep.getCompletedAt();

        for (PlanDefinitionParser.RelatedActionInfo relatedAction : completedActionMetadata.relatedActions()) {
            OffsetDateTime dueDate = calculateDueDate(completedAt, relatedAction);

            // Find the target action's timing for overdue/missed dates
            var targetAction = actions.stream()
                    .filter(a -> relatedAction.actionId().equals(a.id()))
                    .findFirst()
                    .orElse(null);

            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            if (targetAction != null && targetAction.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(targetAction.toleranceDays());
                missedDate = overdueDate.plusDays(targetAction.toleranceDays());
            }

            String requiredBehavior = targetAction != null ? targetAction.requiredBehavior() : null;

            createStep(protocolInstance, relatedAction.actionId(),
                    completedStep.getRepeatIndex(), dueDate, overdueDate, missedDate,
                    requiredBehavior);

            log.info("Progressive instantiation: created step {} due at {} (triggered by {})",
                    relatedAction.actionId(), dueDate, completedStep.getActionId());
        }
    }

    /**
     * Auto-skip preceding optional steps when a subsequent step completes.
     * Steps with requiredBehavior=could that are still in PENDING/DUE/OVERDUE
     * within the same protocol instance are automatically skipped.
     */
    private void autoSkipPrecedingOptionalSteps(StepInstance completedStep) {
        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(completedStep.getProtocolInstance().getId());

        for (StepInstance sibling : siblings) {
            if (sibling.getId().equals(completedStep.getId())) continue;
            if (!"could".equals(sibling.getRequiredBehavior())) continue;
            if (!ACTIONABLE_STATES.contains(sibling.getState())) continue;

            sibling.setState(StepState.SKIPPED);
            stepInstanceRepository.save(sibling);

            log.info("Auto-skipped optional step {} (actionId={}, requiredBehavior=could) " +
                            "due to completion of step {} (actionId={})",
                    sibling.getId(), sibling.getActionId(),
                    completedStep.getId(), completedStep.getActionId());
        }
    }

    private OffsetDateTime calculateDueDate(OffsetDateTime completedAt,
                                            PlanDefinitionParser.RelatedActionInfo relatedAction) {
        if (relatedAction.offsetValue() == null) {
            return completedAt;
        }

        long offsetAmount = relatedAction.offsetValue().longValue();
        String unit = relatedAction.offsetUnit();

        if (unit == null) {
            return completedAt;
        }

        return switch (unit) {
            case "d" -> completedAt.plus(offsetAmount, ChronoUnit.DAYS);
            case "h" -> completedAt.plus(offsetAmount, ChronoUnit.HOURS);
            case "min" -> completedAt.plus(offsetAmount, ChronoUnit.MINUTES);
            case "wk" -> completedAt.plus(offsetAmount * 7, ChronoUnit.DAYS);
            case "mo" -> completedAt.plusMonths(offsetAmount);
            case "a" -> completedAt.plusYears(offsetAmount);
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
