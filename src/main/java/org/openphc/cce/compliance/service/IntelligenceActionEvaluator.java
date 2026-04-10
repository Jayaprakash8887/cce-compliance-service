package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.openphc.cce.compliance.domain.repository.ActionRunContextRepository;
import org.openphc.cce.compliance.domain.repository.ActionRunRepository;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class IntelligenceActionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceActionEvaluator.class);

    private final PlanDefinitionParser planDefinitionParser;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ActionDefinitionService actionDefinitionService;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final ActionRunRepository actionRunRepository;
    private final ActionRunContextRepository actionRunContextRepository;
    private final DeviationRepository deviationRepository;
    private final ObjectMapper objectMapper;

    public IntelligenceActionEvaluator(PlanDefinitionParser planDefinitionParser,
                                     ExpressionEvaluationService expressionEvaluationService,
                                     ActionDefinitionService actionDefinitionService,
                                     IntelligenceTriggerProducer intelligenceTriggerProducer,
                                     ActionRunRepository actionRunRepository,
                                     ActionRunContextRepository actionRunContextRepository,
                                     DeviationRepository deviationRepository,
                                     ObjectMapper objectMapper) {
        this.planDefinitionParser = planDefinitionParser;
        this.expressionEvaluationService = expressionEvaluationService;
        this.actionDefinitionService = actionDefinitionService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.actionRunRepository = actionRunRepository;
        this.actionRunContextRepository = actionRunContextRepository;
        this.deviationRepository = deviationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate intelligence actions when a deviation is detected (OVERDUE or MISSED).
     *
     * PlanDefinition
     * └─ action (step)              → match by step.actionId
     *    └─ action[] (sub-actions)   → for each: check condition → resolve definition → record & publish
     */
    public List<ActionRun> evaluateOnDeviation(StepInstance step, Deviation deviation) {
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        var planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());

        JsonNode context = objectMapper.valueToTree(buildDeviationContext(step, deviation));
        String triggerReason = deviation.getDeviationType().name().toLowerCase();

        List<ActionRun> actionRuns = new ArrayList<>();

        for (PlanDefinitionParser.ActionMetadata action : planDefinitionParser.extractActions(planDefinition)) {
            if (!step.getActionId().equals(action.id())) continue;

            for (PlanDefinitionParser.IntelligenceActionInfo subAction : action.intelligenceActions()) {
                ActionRun actionRun = evaluateAction(subAction, step, deviation, context, triggerReason);
                if (actionRun != null) {
                    actionRuns.add(actionRun);
                }
            }
        }

        if (!actionRuns.isEmpty()) {
            log.info("Evaluated intelligence actions for deviation: stepId={}, deviationType={}, fired={}",
                    step.getId(), deviation.getDeviationType(), actionRuns.size());
        }

        return actionRuns;
    }

    /**
     * Evaluate intelligence actions when a step is completed.
     *
     * PlanDefinition
     * └─ action (step)              → match by step.actionId
     *    └─ action[] (sub-actions)   → for each: check condition → resolve definition → record & publish
     */
    public List<ActionRun> evaluateOnCompletion(StepInstance step) {
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        var planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());

        JsonNode context = objectMapper.valueToTree(buildCompletionContext(step));

        List<ActionRun> actionRuns = new ArrayList<>();

        for (PlanDefinitionParser.ActionMetadata action : planDefinitionParser.extractActions(planDefinition)) {
            if (!step.getActionId().equals(action.id())) continue;

            for (PlanDefinitionParser.IntelligenceActionInfo subAction : action.intelligenceActions()) {
                ActionRun actionRun = evaluateAction(subAction, step, null, context, "completion");
                if (actionRun != null) {
                    actionRuns.add(actionRun);
                }
            }
        }

        if (!actionRuns.isEmpty()) {
            log.info("Evaluated intelligence actions for step completion: stepId={}, fired={}",
                    step.getId(), actionRuns.size());
        }

        return actionRuns;
    }

    // ── Per intelligence sub-action evaluation ──

    private ActionRun evaluateAction(PlanDefinitionParser.IntelligenceActionInfo action,
                                     StepInstance step, Deviation deviation,
                                     JsonNode context, String triggerReason) {
        if (!conditionMatches(action, context)) return null;

        ActionDefinition definition = resolveActionDefinition(action);
        if (definition == null) return null;

        return recordAndPublish(action, step, deviation, definition, triggerReason, context);
    }

    private boolean conditionMatches(PlanDefinitionParser.IntelligenceActionInfo action,
                                     JsonNode context) {
        try {
            boolean matched = expressionEvaluationService.evaluate(
                    action.conditionLanguage(), action.conditionExpression(), context);
            if (!matched) {
                log.debug("Condition not met for intelligence action: actionId={}", action.actionId());
            }
            return matched;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition for intelligence action: actionId={}, error={}",
                    action.actionId(), e.getMessage());
            return false;
        }
    }

    private ActionDefinition resolveActionDefinition(PlanDefinitionParser.IntelligenceActionInfo action) {
        try {
            return actionDefinitionService.resolveByCanonical(action.definitionCanonical());
        } catch (EntityNotFoundException | IllegalArgumentException e) {
            log.warn("Cannot resolve ActionDefinition: actionId={}, canonical={}, error={}",
                    action.actionId(), action.definitionCanonical(), e.getMessage());
            return null;
        }
    }

    private ActionRun recordAndPublish(PlanDefinitionParser.IntelligenceActionInfo action,
                                       StepInstance step, Deviation deviation,
                                       ActionDefinition definition, String triggerReason,
                                       JsonNode evaluationContext) {
        UUID eventId = UUID.randomUUID();
        ProtocolInstance protocol = step.getProtocolInstance();

        // Create ActionRun (TRIGGERED → PUBLISHED after event is sent)
        ActionRun actionRun = ActionRun.builder()
                .actionDefinition(definition)
                .protocolInstance(protocol)
                .stepInstance(step)
                .status(ActionRunStatus.TRIGGERED)
                .intelligenceEventId(eventId)
                .build();
        actionRun = actionRunRepository.save(actionRun);

        // Store evaluation context separately (why this action was triggered)
        ActionRunContext runContext = ActionRunContext.builder()
                .actionRun(actionRun)
                .deviation(deviation)
                .triggerReason(triggerReason)
                .stepActionId(action.actionId())
                .evaluationExpression(action.conditionExpression())
                .evaluationContext(evaluationContext)
                .build();
        actionRunContextRepository.save(runContext);

        // Publish intelligence trigger event to Kafka
        IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                .id(eventId)
                .type("cce.intelligence.trigger")
                .subject(protocol.getPatientId())
                .protocolInstanceId(protocol.getId())
                .stepInstanceId(step.getId())
                .deviationId(deviation != null ? deviation.getId() : null)
                .deviationType(deviation != null ? deviation.getDeviationType().name().toLowerCase() : null)
                .stepState(step.getState().name())
                .actionId(step.getActionId())
                .protocolCanonical(protocol.getProtocolCanonical())
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        intelligenceTriggerProducer.publish(event);

        actionRun.setStatus(ActionRunStatus.PUBLISHED);
        actionRun = actionRunRepository.save(actionRun);

        if (deviation != null && deviation.getIntelligenceEventId() == null) {
            deviation.setIntelligenceEventId(eventId);
            deviationRepository.save(deviation);
        }

        log.info("Intelligence action fired: actionId={}, definition={}, eventId={}",
                action.actionId(), definition.getCanonical(), eventId);

        return actionRun;
    }

    // ── Context builders ──

    private Map<String, Object> buildDeviationContext(StepInstance step, Deviation deviation) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepState", step.getState().name().toLowerCase());
        context.put("deviationType", deviation.getDeviationType().name().toLowerCase());
        context.put("actionId", step.getActionId());
        context.put("repeatIndex", step.getRepeatIndex());

        if (step.getDueDate() != null) {
            context.put("dueDate", step.getDueDate().toString());
            long daysOverdue = ChronoUnit.DAYS.between(step.getDueDate(), OffsetDateTime.now(ZoneOffset.UTC));
            context.put("daysOverdue", Math.max(0, daysOverdue));
        }

        if (step.getMissedDate() != null) {
            long daysPastMissed = ChronoUnit.DAYS.between(step.getMissedDate(), OffsetDateTime.now(ZoneOffset.UTC));
            context.put("daysPastMissedDate", Math.max(0, daysPastMissed));
        }

        return context;
    }

    private Map<String, Object> buildCompletionContext(StepInstance step) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepState", "completed");
        context.put("actionId", step.getActionId());
        context.put("repeatIndex", step.getRepeatIndex());

        if (step.getCompletedAt() != null) {
            context.put("completedAt", step.getCompletedAt().toString());
        }
        if (step.getDueDate() != null) {
            context.put("dueDate", step.getDueDate().toString());
        }
        if (step.getCompletionStatus() != null) {
            context.put("completionStatus", step.getCompletionStatus().name().toLowerCase());
        }

        return context;
    }
}
