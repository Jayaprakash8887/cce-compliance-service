package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.CompletionStatus;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private static final Set<StepState> SKIPPABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);
    private static final Set<StepState> COMPLETABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);

    private final StepInstanceRepository stepInstanceRepository;
    private final DeviationRepository deviationRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final ProtocolInstanceService protocolInstanceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               DeviationRepository deviationRepository,
                               PlanDefinitionParser planDefinitionParser,
                               ProtocolInstanceService protocolInstanceService,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.deviationRepository = deviationRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.protocolInstanceService = protocolInstanceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new step instance in PENDING state.
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String actionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime overdueDate, OffsetDateTime missedDate) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(repeatIndex)
                .state(StepState.PENDING)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .missedDate(missedDate)
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
        if (!COMPLETABLE_STATES.contains(step.getState())) {
            throw new IllegalStateException(
                    "Cannot complete step in state " + step.getState() + ": " + step.getId());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        step.setState(StepState.COMPLETED);
        step.setCompletedAt(now);
        step.setMatchedEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);
        step.setCompletionStatus(determineCompletionStatus(step, now));

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

        // Check if protocol is now complete
        protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
    }

    /**
     * Skip a step. Only allowed from PENDING, DUE, or OVERDUE states.
     */
    public void skipStep(UUID stepId) {
        StepInstance step = findByIdOrThrow(stepId);

        if (!SKIPPABLE_STATES.contains(step.getState())) {
            throw new IllegalStateException(
                    "Cannot skip step in state " + step.getState() + ": " + stepId);
        }

        step.setState(StepState.SKIPPED);
        stepInstanceRepository.save(step);

        log.info("Skipped step: stepId={}, actionId={}", stepId, step.getActionId());

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
                applyTransition(step, StepState.OVERDUE, StepState.MISSED);
                createDeviation(step, DeviationType.MISSED);
                // Check if protocol is now complete (MISSED is terminal)
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

        Deviation deviation = Deviation.builder()
                .protocolInstance(protocolInstance)
                .stepInstance(step)
                .deviationType(deviationType)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .metadata(objectMapper.valueToTree(Map.of(
                        "actionId", step.getActionId(),
                        "stepState", step.getState().name(),
                        "protocolCanonical", protocolInstance.getProtocolCanonical())))
                .build();

        deviationRepository.save(deviation);

        log.info("Created {} deviation for step {} (protocolInstance={})",
                deviationType, step.getId(), protocolInstance.getId());
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

            createStep(protocolInstance, relatedAction.actionId(),
                    completedStep.getRepeatIndex(), dueDate, overdueDate, missedDate);

            log.info("Progressive instantiation: created step {} due at {} (triggered by {})",
                    relatedAction.actionId(), dueDate, completedStep.getActionId());
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
            default -> completedAt.plus(offsetAmount, ChronoUnit.DAYS);
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
