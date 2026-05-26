package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IntelligenceActionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceActionEvaluator.class);

    private final PlanDefinitionParser planDefinitionParser;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ActionDefinitionService actionDefinitionService;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final IntelligenceEventLogRepository intelligenceEventLogRepository;
    private final DeviationRepository deviationRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final ObjectMapper objectMapper;
    private final Counter actionsEvaluatedCounter;
    private final Counter actionsFiredCounter;
    private final int maxPlanDefinitionCacheSize;
    private final Map<UUID, PlanDefinition> parsedPlanDefinitionCache = new ConcurrentHashMap<>();

    public IntelligenceActionEvaluator(PlanDefinitionParser planDefinitionParser,
                                     ExpressionEvaluationService expressionEvaluationService,
                                     ActionDefinitionService actionDefinitionService,
                                     IntelligenceTriggerProducer intelligenceTriggerProducer,
                                     IntelligenceEventLogRepository intelligenceEventLogRepository,
                                     DeviationRepository deviationRepository,
                                     StepInstanceRepository stepInstanceRepository,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry,
                                     @Value("${cce.intelligence.plan-definition-cache-size:256}") int maxPlanDefinitionCacheSize) {
        this.planDefinitionParser = planDefinitionParser;
        this.expressionEvaluationService = expressionEvaluationService;
        this.actionDefinitionService = actionDefinitionService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.intelligenceEventLogRepository = intelligenceEventLogRepository;
        this.deviationRepository = deviationRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.objectMapper = objectMapper;
        this.actionsEvaluatedCounter = meterRegistry.counter("cce.intelligence.actions.evaluated");
        this.actionsFiredCounter = meterRegistry.counter("cce.intelligence.actions.fired");
        this.maxPlanDefinitionCacheSize = maxPlanDefinitionCacheSize;
    }

    /**
     * Evaluate intelligence actions when a deviation is detected (OVERDUE or MISSED).
     *
     * PlanDefinition
     * └─ action (protocol step)        → match by step.actionId
     *    └─ action[] (intelligence actions)  → for each: check condition → resolve definition → record & publish
     */
    public List<IntelligenceEventLog> evaluateOnDeviation(StepInstance step, Deviation deviation) {
        return evaluateOnDeviation(step, deviation, null);
    }

    public List<IntelligenceEventLog> evaluateOnDeviation(StepInstance step, Deviation deviation, JsonNode eventPayload) {
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        PlanDefinition planDefinition = getCachedPlanDefinition(protocolDef);

        JsonNode context = objectMapper.valueToTree(buildDeviationContext(step, deviation));
        String triggerReason = deviation.getDeviationType().name().toLowerCase();

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        List<PlanDefinitionParser.IntelligenceActionInfo> intelligenceActions =
                findIntelligenceActions(step, planDefinition);

        for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : intelligenceActions) {
            IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, deviation, context, triggerReason, eventPayload);
            if (eventLog != null) {
                eventLogs.add(eventLog);
            }
        }

        if (!eventLogs.isEmpty()) {
            log.info("Evaluated intelligence actions for deviation: stepId={}, deviationType={}, fired={}",
                    step.getId(), deviation.getDeviationType(), eventLogs.size());
        }

        return eventLogs;
    }

    /**
     * Evaluate intelligence actions when a step is completed.
     *
     * PlanDefinition
     * └─ action (protocol step)        → match by step.actionId
     *    └─ action[] (intelligence actions)  → for each: check condition → resolve definition → record & publish
     */
    public List<IntelligenceEventLog> evaluateOnCompletion(StepInstance step) {
        return evaluateOnCompletion(step, null);
    }

    public List<IntelligenceEventLog> evaluateOnCompletion(StepInstance step, JsonNode eventPayload) {
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        PlanDefinition planDefinition = getCachedPlanDefinition(protocolDef);

        JsonNode context = objectMapper.valueToTree(buildCompletionContext(step));

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        List<PlanDefinitionParser.IntelligenceActionInfo> intelligenceActions =
                findIntelligenceActions(step, planDefinition);

        for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : intelligenceActions) {
            IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, null, context, "completion", eventPayload);
            if (eventLog != null) {
                eventLogs.add(eventLog);
            }
        }

        if (!eventLogs.isEmpty()) {
            log.info("Evaluated intelligence actions for step completion: stepId={}, fired={}",
                    step.getId(), eventLogs.size());
        }

        return eventLogs;
    }

    // ── Parsed PlanDefinition cache ──

    private PlanDefinition getCachedPlanDefinition(ProtocolDefinition protocolDef) {
        return parsedPlanDefinitionCache.computeIfAbsent(protocolDef.getId(), id -> {
            if (parsedPlanDefinitionCache.size() >= maxPlanDefinitionCacheSize) {
                parsedPlanDefinitionCache.clear();
            }
            return planDefinitionParser.parse(protocolDef.getDefinition().toString());
        });
    }

    public void evictPlanDefinitionCache(UUID protocolDefinitionId) {
        parsedPlanDefinitionCache.remove(protocolDefinitionId);
    }

    // ── Intelligence action lookup ──

    /**
     * Find intelligence actions for a step. Handles both:
     * - Top-level steps (parentStepId == null) → returns StepMetadata.intelligenceActions()
     * - Sub-steps (parentStepId != null) → finds the parent, then the matching sub-step's intelligenceActions()
     */
    private List<PlanDefinitionParser.IntelligenceActionInfo> findIntelligenceActions(
            StepInstance step, PlanDefinition planDefinition) {
        List<PlanDefinitionParser.StepMetadata> actions = planDefinitionParser.extractActions(planDefinition);
        String actionId = step.getActionId();

        // Sub-step: step has a parentStepId → derive parent's actionId and search recursively
        if (step.getParentStepId() != null) {
            String parentActionId = stepInstanceRepository.findById(step.getParentStepId())
                    .map(StepInstance::getActionId)
                    .orElse(null);
            if (parentActionId != null) {
                // Search top-level actions for the parent, then find this sub-step within it
                for (PlanDefinitionParser.StepMetadata protocolStep : actions) {
                    List<PlanDefinitionParser.IntelligenceActionInfo> result =
                            findIntelligenceActionsInSubSteps(actionId, parentActionId, protocolStep);
                    if (result != null) {
                        return result;
                    }
                }
            }
            log.debug("No intelligence actions found for sub-step: actionId={}, parentStepId={}",
                    actionId, step.getParentStepId());
            return List.of();
        }

        // Top-level action lookup
        for (PlanDefinitionParser.StepMetadata protocolStep : actions) {
            if (actionId.equals(protocolStep.id())) {
                return protocolStep.intelligenceActions();
            }
        }
        log.debug("No intelligence actions found for action: actionId={}", actionId);
        return List.of();
    }

    /**
     * Recursively search within a top-level action (and its nested sub-steps) to find
     * intelligence actions for a given sub-step identified by actionId and parentActionId.
     */
    private List<PlanDefinitionParser.IntelligenceActionInfo> findIntelligenceActionsInSubSteps(
            String targetActionId, String targetParentId, PlanDefinitionParser.StepMetadata topAction) {
        // If top-level is the parent, search its sub-steps
        if (targetParentId.equals(topAction.id())) {
            for (PlanDefinitionParser.StepMetadata subStep : topAction.subSteps()) {
                if (targetActionId.equals(subStep.id())) {
                    return subStep.intelligenceActions();
                }
            }
            return null;
        }

        // Otherwise, recurse into nested sub-steps
        return findIntelligenceActionsRecursive(targetActionId, targetParentId, topAction.subSteps());
    }

    private List<PlanDefinitionParser.IntelligenceActionInfo> findIntelligenceActionsRecursive(
            String targetActionId, String targetParentId,
            List<PlanDefinitionParser.StepMetadata> subSteps) {
        if (subSteps == null) return null;
        for (PlanDefinitionParser.StepMetadata subStep : subSteps) {
            // Is this sub-step the parent we're looking for?
            if (targetParentId.equals(subStep.id())) {
                if (subStep.subSteps() != null) {
                    for (PlanDefinitionParser.StepMetadata nested : subStep.subSteps()) {
                        if (targetActionId.equals(nested.id())) {
                            return nested.intelligenceActions();
                        }
                    }
                }
                return null;
            }
            // Recurse deeper
            List<PlanDefinitionParser.IntelligenceActionInfo> result =
                    findIntelligenceActionsRecursive(targetActionId, targetParentId, subStep.subSteps());
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    // ── Per intelligence action evaluation ──

    private IntelligenceEventLog evaluateAction(PlanDefinitionParser.IntelligenceActionInfo action,
                                     StepInstance step, Deviation deviation,
                                     JsonNode context, String triggerReason, JsonNode eventPayload) {
        if (!conditionMatches(action, context)) return null;

        ActionDefinition definition = resolveActionDefinition(action);
        if (definition == null) return null;

        return recordAndPublish(action, step, deviation, definition, triggerReason, context, eventPayload);
    }

    private boolean conditionMatches(PlanDefinitionParser.IntelligenceActionInfo action,
                                     JsonNode context) {
        actionsEvaluatedCounter.increment();
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

    private IntelligenceEventLog recordAndPublish(PlanDefinitionParser.IntelligenceActionInfo action,
                                       StepInstance step, Deviation deviation,
                                       ActionDefinition definition, String triggerReason,
                                       JsonNode evaluationContext, JsonNode eventPayload) {
        actionsFiredCounter.increment();
        UUID eventId = UUID.randomUUID();
        ProtocolInstance protocol = step.getProtocolInstance();

        MDC.put("intelligenceEventId", eventId.toString());
        try {
            // Severity and destination are always present (required at PlanDefinition parse time)
            String intelligenceDestination = action.intelligenceDestination();
            String severity = action.severity();

            // Build the intelligence trigger event
            IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                    .id(eventId)
                    .subject(protocol.getPatientId())
                    .intelligenceEventId(null) // Set after eventLog is saved
                    .actionDefinitionId(definition.getId())
                    .protocolDefinitionId(protocol.getProtocolDefinition().getId())
                    .actionType(definition.getActionType().name())
                    .severity(severity)
                    .intelligenceDestination(intelligenceDestination)
                    .stepState(step.getState().name().toLowerCase())
                    .actionId(step.getActionId())
                    .protocolCanonical(protocol.getProtocolCanonical())
                    .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .eventPayload(eventPayload)
                    .build();

            // Create event log record (published=false initially)
            IntelligenceEventLog eventLog = IntelligenceEventLog.builder()
                    .eventPayload(objectMapper.valueToTree(event))
                    .actionDefinitionId(definition.getId())
                    .protocolInstanceId(protocol.getId())
                    .stepInstanceId(step.getId())
                    .deviationId(deviation != null ? deviation.getId() : null)
                    .subject(protocol.getPatientId())
                    .actionType(definition.getActionType().name())
                    .intelligenceDestination(intelligenceDestination)
                    .stepState(step.getState().name().toLowerCase())
                    .triggerReason(triggerReason)
                    .stepActionId(action.actionId())
                    .evaluationExpression(action.conditionExpression())
                    .evaluationContext(evaluationContext)
                    .published(false)
                    .build();
            eventLog = intelligenceEventLogRepository.save(eventLog);

            // Set intelligenceEventId to the event log ID for cross-service correlation
            event.setIntelligenceEventId(eventLog.getId());
            eventLog.setEventPayload(objectMapper.valueToTree(event));

            // Publish intelligence trigger event to Kafka
            intelligenceTriggerProducer.publish(event);

            eventLog.setPublished(true);
            eventLog.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            eventLog = intelligenceEventLogRepository.save(eventLog);

            if (deviation != null && deviation.getIntelligenceEventId() == null) {
                deviation.setIntelligenceEventId(eventId);
                deviationRepository.save(deviation);
            }

            log.info("Intelligence action fired: actionId={}, definition={}, eventId={}",
                    action.actionId(), definition.getCanonical(), eventId);

            return eventLog;
        } finally {
            MDC.remove("intelligenceEventId");
        }
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
