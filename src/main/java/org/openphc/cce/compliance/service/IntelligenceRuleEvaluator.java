package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
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
public class IntelligenceRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceRuleEvaluator.class);

    private final PlanDefinitionParser planDefinitionParser;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ActionDefinitionService actionDefinitionService;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final ActionRunRepository actionRunRepository;
    private final DeviationRepository deviationRepository;
    private final ObjectMapper objectMapper;

    public IntelligenceRuleEvaluator(PlanDefinitionParser planDefinitionParser,
                                     ExpressionEvaluationService expressionEvaluationService,
                                     ActionDefinitionService actionDefinitionService,
                                     IntelligenceTriggerProducer intelligenceTriggerProducer,
                                     ActionRunRepository actionRunRepository,
                                     DeviationRepository deviationRepository,
                                     ObjectMapper objectMapper) {
        this.planDefinitionParser = planDefinitionParser;
        this.expressionEvaluationService = expressionEvaluationService;
        this.actionDefinitionService = actionDefinitionService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.actionRunRepository = actionRunRepository;
        this.deviationRepository = deviationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate intelligence rules when a deviation is detected (OVERDUE or MISSED).
     */
    public List<ActionRun> evaluateOnDeviation(StepInstance step, Deviation deviation) {
        List<PlanDefinitionParser.IntelligenceRuleInfo> rules = extractRulesForStep(step);
        if (rules.isEmpty()) return List.of();

        Map<String, Object> context = buildDeviationContext(step, deviation);
        JsonNode contextNode = objectMapper.valueToTree(context);

        log.info("Evaluating {} intelligence rules for deviation: stepId={}, actionId={}, deviationType={}",
                rules.size(), step.getId(), step.getActionId(), deviation.getDeviationType());

        List<ActionRun> actionRuns = new ArrayList<>();
        for (PlanDefinitionParser.IntelligenceRuleInfo rule : rules) {
            ActionRun actionRun = evaluateRule(rule, step, deviation, contextNode,
                    deviation.getDeviationType().name().toLowerCase());
            if (actionRun != null) {
                actionRuns.add(actionRun);
            }
        }

        return actionRuns;
    }

    /**
     * Evaluate intelligence rules when a step is completed.
     */
    public List<ActionRun> evaluateOnCompletion(StepInstance step) {
        List<PlanDefinitionParser.IntelligenceRuleInfo> rules = extractRulesForStep(step);
        if (rules.isEmpty()) return List.of();

        Map<String, Object> context = buildCompletionContext(step);
        JsonNode contextNode = objectMapper.valueToTree(context);

        log.info("Evaluating {} intelligence rules for step completion: stepId={}, actionId={}",
                rules.size(), step.getId(), step.getActionId());

        List<ActionRun> actionRuns = new ArrayList<>();
        for (PlanDefinitionParser.IntelligenceRuleInfo rule : rules) {
            ActionRun actionRun = evaluateRule(rule, step, null, contextNode, "completion");
            if (actionRun != null) {
                actionRuns.add(actionRun);
            }
        }

        return actionRuns;
    }

    private ActionRun evaluateRule(PlanDefinitionParser.IntelligenceRuleInfo rule,
                                   StepInstance step, Deviation deviation,
                                   JsonNode contextNode, String triggerReason) {
        // Evaluate condition
        boolean matched;
        try {
            matched = expressionEvaluationService.evaluate(
                    rule.conditionLanguage(), rule.conditionExpression(), contextNode);
        } catch (Exception e) {
            log.warn("Failed to evaluate intelligence rule condition: ruleId={}, actionId={}, error={}",
                    rule.ruleId(), step.getActionId(), e.getMessage());
            return null;
        }

        if (!matched) {
            log.debug("Intelligence rule condition not met: ruleId={}, actionId={}",
                    rule.ruleId(), step.getActionId());
            return null;
        }

        // Resolve ActionDefinition
        ActionDefinition actionDefinition;
        try {
            actionDefinition = actionDefinitionService.resolveByCanonical(rule.definitionCanonical());
        } catch (EntityNotFoundException | IllegalArgumentException e) {
            log.warn("Cannot resolve ActionDefinition for intelligence rule: ruleId={}, canonical={}, error={}",
                    rule.ruleId(), rule.definitionCanonical(), e.getMessage());
            return null;
        }

        // Create ActionRun (status=TRIGGERED)
        UUID intelligenceEventId = UUID.randomUUID();
        ActionRun actionRun = ActionRun.builder()
                .actionDefinition(actionDefinition)
                .protocolInstance(step.getProtocolInstance())
                .stepInstance(step)
                .deviation(deviation)
                .status(ActionRunStatus.TRIGGERED)
                .intelligenceEventId(intelligenceEventId)
                .triggerReason(triggerReason)
                .ruleId(rule.ruleId())
                .ruleExpression(rule.conditionExpression())
                .build();
        actionRun = actionRunRepository.save(actionRun);

        // Build and publish IntelligenceTriggerEvent
        IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                .id(intelligenceEventId)
                .type("cce.intelligence.trigger")
                .subject(step.getProtocolInstance().getPatientId())
                .protocolInstanceId(step.getProtocolInstance().getId())
                .stepInstanceId(step.getId())
                .deviationId(deviation != null ? deviation.getId() : null)
                .deviationType(deviation != null ? deviation.getDeviationType().name().toLowerCase() : null)
                .stepState(step.getState().name())
                .actionId(step.getActionId())
                .protocolCanonical(step.getProtocolInstance().getProtocolCanonical())
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        intelligenceTriggerProducer.publish(event);

        // Update ActionRun status to PUBLISHED
        actionRun.setStatus(ActionRunStatus.PUBLISHED);
        actionRun = actionRunRepository.save(actionRun);

        // Update deviation.intelligenceEventId if applicable
        if (deviation != null && deviation.getIntelligenceEventId() == null) {
            deviation.setIntelligenceEventId(intelligenceEventId);
            deviationRepository.save(deviation);
        }

        log.info("Intelligence rule fired: ruleId={}, actionId={}, actionDefinition={}, eventId={}",
                rule.ruleId(), step.getActionId(), actionDefinition.getCanonical(), intelligenceEventId);

        return actionRun;
    }

    private List<PlanDefinitionParser.IntelligenceRuleInfo> extractRulesForStep(StepInstance step) {
        ProtocolInstance protocolInstance = step.getProtocolInstance();
        ProtocolDefinition protocolDef = protocolInstance.getProtocolDefinition();

        var planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());
        List<PlanDefinitionParser.ActionMetadata> actions = planDefinitionParser.extractActions(planDefinition);

        return actions.stream()
                .filter(a -> step.getActionId().equals(a.id()))
                .findFirst()
                .map(PlanDefinitionParser.ActionMetadata::intelligenceRules)
                .orElse(List.of());
    }

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
