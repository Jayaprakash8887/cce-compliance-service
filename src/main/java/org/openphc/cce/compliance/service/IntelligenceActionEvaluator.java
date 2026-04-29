package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
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
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry,
                                     @Value("${cce.intelligence.plan-definition-cache-size:256}") int maxPlanDefinitionCacheSize) {
        this.planDefinitionParser = planDefinitionParser;
        this.expressionEvaluationService = expressionEvaluationService;
        this.actionDefinitionService = actionDefinitionService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.intelligenceEventLogRepository = intelligenceEventLogRepository;
        this.deviationRepository = deviationRepository;
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
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        PlanDefinition planDefinition = getCachedPlanDefinition(protocolDef);

        JsonNode context = objectMapper.valueToTree(buildDeviationContext(step, deviation));
        String triggerReason = deviation.getDeviationType().name().toLowerCase();

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        // Iterate over protocol steps (PlanDefinition.action) to find the matching step
        for (PlanDefinitionParser.ActionMetadata protocolStep : planDefinitionParser.extractActions(planDefinition)) {
            if (!step.getActionId().equals(protocolStep.id())) continue;

            // Evaluate each intelligence action (PlanDefinition.action.action) defined under this step
            for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : protocolStep.intelligenceActions()) {
                IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, deviation, context, triggerReason);
                if (eventLog != null) {
                    eventLogs.add(eventLog);
                }
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
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        PlanDefinition planDefinition = getCachedPlanDefinition(protocolDef);

        JsonNode context = objectMapper.valueToTree(buildCompletionContext(step));

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        // Iterate over protocol steps (PlanDefinition.action) to find the matching step
        for (PlanDefinitionParser.ActionMetadata protocolStep : planDefinitionParser.extractActions(planDefinition)) {
            if (!step.getActionId().equals(protocolStep.id())) continue;

            // Evaluate each intelligence action (PlanDefinition.action.action) defined under this step
            for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : protocolStep.intelligenceActions()) {
                IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, null, context, "completion");
                if (eventLog != null) {
                    eventLogs.add(eventLog);
                }
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

    // ── Per intelligence action evaluation ──

    private IntelligenceEventLog evaluateAction(PlanDefinitionParser.IntelligenceActionInfo action,
                                     StepInstance step, Deviation deviation,
                                     JsonNode context, String triggerReason) {
        if (!conditionMatches(action, context)) return null;

        ActionDefinition definition = resolveActionDefinition(action);
        if (definition == null) return null;

        return recordAndPublish(action, step, deviation, definition, triggerReason, context);
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
                                       JsonNode evaluationContext) {
        actionsFiredCounter.increment();
        UUID eventId = UUID.randomUUID();
        ProtocolInstance protocol = step.getProtocolInstance();

        MDC.put("intelligenceEventId", eventId.toString());
        try {
            // Resolve intelligence channel and severity (action-level overrides definition-level)
            String intelligenceChannel = action.intelligenceChannel() != null
                    ? action.intelligenceChannel()
                    : definition.getIntelligenceChannel();
            String severity = action.severity() != null
                    ? action.severity()
                    : (definition.getSeverity() != null ? definition.getSeverity().name() : null);

            // Build the intelligence trigger event
            IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                    .id(eventId)
                    .subject(protocol.getPatientId())
                    .intelligenceEventId(null) // Set after eventLog is saved
                    .actionDefinitionId(definition.getId())
                    .protocolDefinitionId(protocol.getProtocolDefinition().getId())
                    .actionType(definition.getActionType().name())
                    .severity(severity)
                    .intelligenceChannel(intelligenceChannel)
                    .stepState(step.getState().name().toLowerCase())
                    .actionId(step.getActionId())
                    .protocolCanonical(protocol.getProtocolCanonical())
                    .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
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
                    .intelligenceChannel(intelligenceChannel)
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
