package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.compliance.domain.entity.ComplianceEventLog;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central orchestrator through which all inbound event processing flows.
 * Wires together idempotency, resource extraction, two-tier matching,
 * enrollment, step completion, progressive instantiation, and deviation recording.
 */
@Service
@Transactional
public class ComplianceEngine {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEngine.class);

    private final ComplianceEventLogService eventLogService;
    private final ResourceInfoExtractor resourceInfoExtractor;
    private final TriggerMatchingService triggerMatchingService;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ProtocolDefinitionService protocolDefinitionService;
    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceService stepInstanceService;
    private final PlanDefinitionParser planDefinitionParser;
    private final AuditService auditService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final ClinicalEventTimeExtractor clinicalEventTimeExtractor;

    private final Counter eventsProcessedCounter;
    private final Counter eventsMatchedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsZeroMatchCounter;
    private final Timer matchingDurationTimer;
    private final Timer eventProcessingTimer;

    public ComplianceEngine(ComplianceEventLogService eventLogService,
                            ResourceInfoExtractor resourceInfoExtractor,
                            TriggerMatchingService triggerMatchingService,
                            ExpressionEvaluationService expressionEvaluationService,
                            ProtocolDefinitionService protocolDefinitionService,
                            ProtocolInstanceService protocolInstanceService,
                            StepInstanceService stepInstanceService,
                            PlanDefinitionParser planDefinitionParser,
                            AuditService auditService,
                            IntelligenceActionEvaluator intelligenceActionEvaluator,
                            ClinicalEventTimeExtractor clinicalEventTimeExtractor,
                            MeterRegistry meterRegistry) {
        this.eventLogService = eventLogService;
        this.resourceInfoExtractor = resourceInfoExtractor;
        this.triggerMatchingService = triggerMatchingService;
        this.expressionEvaluationService = expressionEvaluationService;
        this.protocolDefinitionService = protocolDefinitionService;
        this.protocolInstanceService = protocolInstanceService;
        this.stepInstanceService = stepInstanceService;
        this.planDefinitionParser = planDefinitionParser;
        this.auditService = auditService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.clinicalEventTimeExtractor = clinicalEventTimeExtractor;

        this.eventsProcessedCounter = meterRegistry.counter("cce.events.processed");
        this.eventsMatchedCounter = Counter.builder("cce.events.matched")
                .tag("status", "matched").register(meterRegistry);
        this.eventsDuplicateCounter = meterRegistry.counter("cce.events.duplicate");
        this.eventsZeroMatchCounter = Counter.builder("cce.events.matched")
                .tag("status", "zero_match").register(meterRegistry);
        this.matchingDurationTimer = meterRegistry.timer("cce.step.matching.duration");
        this.eventProcessingTimer = meterRegistry.timer("cce.events.processing.duration");
    }

    /**
     * Main entry point for all inbound clinical events.
     */
    public void processInboundEvent(CloudEventMessage event) {
        eventProcessingTimer.record(() -> doProcessInboundEvent(event));
    }

    private void doProcessInboundEvent(CloudEventMessage event) {
        eventsProcessedCounter.increment();

        // Step 1: Idempotency check
        if (eventLogService.isDuplicate(event.getId(), event.getSource())) {
            log.info("Duplicate event detected: cloudeventsId={}, source={}", event.getId(), event.getSource());
            eventLogService.recordEvent(event, ProcessingStatus.DUPLICATE);
            eventsDuplicateCounter.increment();
            return;
        }

        // Step 2: Record event with initial ZERO_MATCH status
        ComplianceEventLog eventLog = eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH);

        // Step 3: Extract resource info from payload
        JsonNode data = event.getData();
        String resourceType = resourceInfoExtractor.extractResourceType(data);
        List<CodePathTriple> codes = resourceInfoExtractor.extractCodes(data);

        // Step 4: Check for explicit match
        if (event.getActionid() != null && !event.getActionid().isBlank()) {
            processExplicitMatch(event, eventLog);
            return;
        }

        // Steps 5-7: Two-tier matching
        Map<UUID, List<PlanDefinitionParser.StepMetadata>> stepCache = new HashMap<>();
        List<MatchedStep> finalMatches = matchingDurationTimer.record(() ->
                performTwoTierMatching(resourceType, codes, data, stepCache));

        // Step 8: Result classification
        if (finalMatches != null && !finalMatches.isEmpty()) {
            for (MatchedStep match : finalMatches) {
                processMatch(match, event, eventLog, stepCache);
            }
            eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
            eventsMatchedCounter.increment();

            log.info("Event processing complete: cloudeventsId={}, matches={}",
                    event.getId(), finalMatches.size());
        } else {
            eventsZeroMatchCounter.increment();
            log.info("No matches for event: cloudeventsId={}, resourceType={}",
                    event.getId(), resourceType);
        }
    }

    /**
     * Process an explicit match — bypass Tier 1/2 entirely.
     * Uses actionId and protocolInstanceId from CloudEvent extensions.
     */
    void processExplicitMatch(CloudEventMessage event, ComplianceEventLog eventLog) {
        String actionId = event.getActionid();
        String protocolInstanceIdStr = event.getProtocolinstanceid();

        if (protocolInstanceIdStr == null || protocolInstanceIdStr.isBlank()) {
            log.warn("Explicit match requested but protocolInstanceId is missing: cloudeventsId={}",
                    event.getId());
            return;
        }

        UUID protocolInstanceId = UUID.fromString(protocolInstanceIdStr);
        ProtocolInstance protocolInstance = protocolInstanceService.findById(protocolInstanceId);
        Map<UUID, List<PlanDefinitionParser.StepMetadata>> stepCache = new HashMap<>();

        StepInstance step = stepInstanceService.findActionableStep(protocolInstanceId, actionId);
        if (step == null) {
            step = createInitialStep(protocolInstance, actionId, stepCache);
        }

        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource(), resolveOccurredAt(event));

        // Evaluate intelligence actions after step completion
        intelligenceActionEvaluator.evaluateOnCompletion(step, event.getData());

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
        eventsMatchedCounter.increment();

        log.info("Explicit match processed: cloudeventsId={}, actionId={}, protocolInstanceId={}",
                event.getId(), actionId, protocolInstanceId);
    }

    private List<MatchedStep> performTwoTierMatching(String resourceType, List<CodePathTriple> codes,
                                                       JsonNode eventData,
                                                       Map<UUID, List<PlanDefinitionParser.StepMetadata>> stepCache) {
        List<MatchedStep> finalMatches = new ArrayList<>();

        // Step 5: Tier 1 structural match
        List<MatchedStep> tier1Matches = triggerMatchingService.findStructuralMatches(resourceType, codes);

        // Step 6: Collect condition-only triggers
        List<ConditionOnlyTrigger> conditionOnlyTriggers = triggerMatchingService.getConditionOnlyTriggers();

        // Step 7: Tier 2 condition evaluation

        // Evaluate Tier 1 results — check if they have conditions
        for (MatchedStep match : tier1Matches) {
            List<PlanDefinitionParser.StepMetadata> steps = getStepsForProtocol(match.protocolDefinitionId(), stepCache);

            String actionId = match.actionId();

            PlanDefinitionParser.StepMetadata stepMetadata = steps.stream()
                    .filter(a -> actionId.equals(a.id()))
                    .findFirst()
                    .orElse(null);

            if (stepMetadata == null) {
                continue;
            }

            // Check if this action's trigger has a condition
            boolean hasCondition = stepMetadata.triggers().stream()
                    .anyMatch(t -> t.condition() != null);

            if (!hasCondition) {
                finalMatches.add(match);
            } else {
                boolean conditionMet = evaluateTriggerConditions(stepMetadata, eventData);
                if (conditionMet) {
                    finalMatches.add(match);
                }
            }
        }

        // Evaluate condition-only triggers (Scenario 5: F3 only)
        for (ConditionOnlyTrigger trigger : conditionOnlyTriggers) {
            boolean result = expressionEvaluationService.evaluate(
                    trigger.conditionLanguage(), trigger.conditionExpression(), eventData);
            if (result) {
                finalMatches.add(new MatchedStep(trigger.protocolDefinitionId(), trigger.actionId()));
            }
        }

        return finalMatches;
    }

    private boolean evaluateTriggerConditions(PlanDefinitionParser.StepMetadata stepMetadata,
                                               JsonNode eventData) {
        for (PlanDefinitionParser.TriggerInfo trigger : stepMetadata.triggers()) {
            if (trigger.condition() != null) {
                boolean result = expressionEvaluationService.evaluate(
                        trigger.condition().language(),
                        trigger.condition().expression(),
                        eventData);
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processMatch(MatchedStep match, CloudEventMessage event, ComplianceEventLog eventLog,
                              Map<UUID, List<PlanDefinitionParser.StepMetadata>> stepCache) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(match.protocolDefinitionId());
        String patientId = event.getSubject();

        // Enroll patient (idempotent — returns existing if already enrolled)
        ProtocolInstance protocolInstance = protocolInstanceService.enrollPatient(
                patientId, protocolDef, OffsetDateTime.now(ZoneOffset.UTC));

        String actionId = match.actionId();

        // Find or create an actionable step
        StepInstance step = stepInstanceService.findActionableStep(
                protocolInstance.getId(), actionId);
        if (step == null) {
            step = createInitialStep(protocolInstance, actionId, stepCache);
        }

        // Complete the step
        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource(), resolveOccurredAt(event));

        // Evaluate intelligence actions after step completion
        intelligenceActionEvaluator.evaluateOnCompletion(step, event.getData());

        auditService.audit("COMPLIANCE", "EVENT_MATCHED", "system",
                "EventLog", eventLog.getId().toString(),
                Map.of("protocolDefinitionId", match.protocolDefinitionId().toString(),
                        "actionId", actionId,
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "patientId", patientId));
    }

    /**
     * Resolve the clinical occurrence time to attribute a completion to. Precedence:
     * <ol>
     *   <li>Clinical time extracted from the FHIR payload (when the datacontenttype is FHIR and a
     *       clinical field is present) — the real-world time the act happened;</li>
     *   <li>the CloudEvent envelope {@code time} — the emitter-adaptor's transmission clock, which
     *       is stable across retries/DLQ replay and closer to the event than our processing time;</li>
     *   <li>{@code now()} — defensive last resort (the envelope time is expected to always be present).</li>
     * </ol>
     * Non-FHIR ({@code application/json}) payloads skip extraction and use the envelope time directly.
     * completeStep clamps the result to now(), so a bad/future source clock cannot push schedules out.
     */
    private OffsetDateTime resolveOccurredAt(CloudEventMessage event) {
        if (isFhir(event)) {
            String resourceType = resourceInfoExtractor.extractResourceType(event.getData());
            OffsetDateTime clinical = clinicalEventTimeExtractor.extract(resourceType, event.getData());
            if (clinical != null) {
                return clinical;
            }
        }
        return event.getTime() != null ? event.getTime() : OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Whether the payload is FHIR (and thus a candidate for clinical-time extraction). The Collector
     * defaults to {@code application/fhir+json}, so a null/absent content type is treated as FHIR.
     */
    private boolean isFhir(CloudEventMessage event) {
        String contentType = event.getDatacontenttype();
        return contentType == null || contentType.toLowerCase().contains("fhir");
    }

    private StepInstance createInitialStep(ProtocolInstance protocolInstance, String actionId,
                                           Map<UUID, List<PlanDefinitionParser.StepMetadata>> stepCache) {
        UUID protocolDefId = protocolInstance.getProtocolDefinition().getId();
        List<PlanDefinitionParser.StepMetadata> steps = getStepsForProtocol(protocolDefId, stepCache);

        PlanDefinitionParser.StepMetadata stepMetadata = steps.stream()
                .filter(a -> actionId.equals(a.id()))
                .findFirst()
                .orElse(null);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime overdueDate = null;
        OffsetDateTime missedDate = null;
        String requiredBehavior = null;

        if (stepMetadata != null) {
            requiredBehavior = stepMetadata.requiredBehavior();
            if (stepMetadata.toleranceDays() != null) {
                overdueDate = now.plusDays(stepMetadata.toleranceDays());
                missedDate = overdueDate.plusDays(stepMetadata.toleranceDays());
            }
        }

        return stepInstanceService.createStep(protocolInstance, actionId, 0,
                now, overdueDate, missedDate, requiredBehavior);
    }

    /**
     * Cache-backed lookup of parsed action metadata for a protocol definition.
     * Avoids redundant PlanDefinition JSON parsing within a single event processing cycle.
     */
    private List<PlanDefinitionParser.StepMetadata> getStepsForProtocol(
            UUID protocolDefId, Map<UUID, List<PlanDefinitionParser.StepMetadata>> cache) {
        return cache.computeIfAbsent(protocolDefId, id -> {
            ProtocolDefinition protocolDef = protocolDefinitionService.findById(id);
            PlanDefinition planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());
            return planDefinitionParser.extractSteps(planDefinition);
        });
    }
}
