package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.compliance.domain.entity.EventLog;
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

    private final EventLogService eventLogService;
    private final ResourceInfoExtractor resourceInfoExtractor;
    private final TriggerMatchingService triggerMatchingService;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ProtocolDefinitionService protocolDefinitionService;
    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceService stepInstanceService;
    private final PlanDefinitionParser planDefinitionParser;
    private final AuditService auditService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;

    private final Counter eventsProcessedCounter;
    private final Counter eventsMatchedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsZeroMatchCounter;
    private final Timer matchingDurationTimer;
    private final Timer eventProcessingTimer;

    public ComplianceEngine(EventLogService eventLogService,
                            ResourceInfoExtractor resourceInfoExtractor,
                            TriggerMatchingService triggerMatchingService,
                            ExpressionEvaluationService expressionEvaluationService,
                            ProtocolDefinitionService protocolDefinitionService,
                            ProtocolInstanceService protocolInstanceService,
                            StepInstanceService stepInstanceService,
                            PlanDefinitionParser planDefinitionParser,
                            AuditService auditService,
                            IntelligenceActionEvaluator intelligenceActionEvaluator,
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
        EventLog eventLog = eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH);

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
        Map<UUID, List<PlanDefinitionParser.StepMetadata>> actionCache = new HashMap<>();
        List<MatchedAction> finalMatches = matchingDurationTimer.record(() ->
                performTwoTierMatching(resourceType, codes, data, actionCache));

        // Step 8: Result classification
        if (finalMatches != null && !finalMatches.isEmpty()) {
            for (MatchedAction match : finalMatches) {
                processMatch(match, event, eventLog, actionCache);
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
    void processExplicitMatch(CloudEventMessage event, EventLog eventLog) {
        String actionId = event.getActionid();
        String protocolInstanceIdStr = event.getProtocolinstanceid();

        if (protocolInstanceIdStr == null || protocolInstanceIdStr.isBlank()) {
            log.warn("Explicit match requested but protocolInstanceId is missing: cloudeventsId={}",
                    event.getId());
            return;
        }

        UUID protocolInstanceId = UUID.fromString(protocolInstanceIdStr);
        ProtocolInstance protocolInstance = protocolInstanceService.findById(protocolInstanceId);
        Map<UUID, List<PlanDefinitionParser.StepMetadata>> actionCache = new HashMap<>();

        // Link event_log to the matched protocol instance
        eventLog.setProtocolInstanceId(protocolInstanceId);
        eventLog.setProtocolDefinitionId(protocolInstance.getProtocolDefinition().getId());
        eventLog.setActionId(actionId);

        StepInstance step = stepInstanceService.findActionableStep(protocolInstanceId, actionId);
        if (step == null) {
            step = createInitialStep(protocolInstance, actionId, actionCache);
        }

        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource());

        // Evaluate intelligence actions after step completion
        intelligenceActionEvaluator.evaluateOnCompletion(step, event.getData());

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
        eventsMatchedCounter.increment();

        log.info("Explicit match processed: cloudeventsId={}, actionId={}, protocolInstanceId={}",
                event.getId(), actionId, protocolInstanceId);
    }

    private List<MatchedAction> performTwoTierMatching(String resourceType, List<CodePathTriple> codes,
                                                       JsonNode eventData,
                                                       Map<UUID, List<PlanDefinitionParser.StepMetadata>> actionCache) {
        List<MatchedAction> finalMatches = new ArrayList<>();

        // Step 5: Tier 1 structural match
        List<MatchedAction> tier1Matches = triggerMatchingService.findStructuralMatches(resourceType, codes);

        // Step 6: Collect condition-only triggers
        List<ConditionOnlyTrigger> conditionOnlyTriggers = triggerMatchingService.getConditionOnlyTriggers();

        // Step 7: Tier 2 condition evaluation

        // Evaluate Tier 1 results — check if they have conditions
        for (MatchedAction match : tier1Matches) {
            List<PlanDefinitionParser.StepMetadata> actions = getActionsForProtocol(match.protocolDefinitionId(), actionCache);

            String actionId = match.actionId();

            // Resolve metadata: first try sub-steps, then top-level
            PlanDefinitionParser.StepMetadata stepMetadata = findSubStepInTree(actionId, actions);
            if (stepMetadata == null) {
                stepMetadata = actions.stream()
                        .filter(a -> actionId.equals(a.id()))
                        .findFirst()
                        .orElse(null);
            }

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
                finalMatches.add(new MatchedAction(trigger.protocolDefinitionId(), trigger.actionId()));
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

    private void processMatch(MatchedAction match, CloudEventMessage event, EventLog eventLog,
                              Map<UUID, List<PlanDefinitionParser.StepMetadata>> actionCache) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(match.protocolDefinitionId());
        String patientId = event.getSubject();

        // Enroll patient (idempotent — returns existing if already enrolled)
        ProtocolInstance protocolInstance = protocolInstanceService.enrollPatient(
                patientId, protocolDef, OffsetDateTime.now(ZoneOffset.UTC));

        // Check if this is a sub-step match by deriving parent from PD tree
        String actionId = match.actionId();
        List<PlanDefinitionParser.StepMetadata> actions = getActionsForProtocol(match.protocolDefinitionId(), actionCache);
        List<String> ancestryPath = findAncestryPath(actionId, actions);

        if (ancestryPath != null) {
            processSubStepMatch(actionId, ancestryPath, protocolInstance, event, eventLog, actions);
            return;
        }

        // Link event_log to the matched protocol instance
        eventLog.setProtocolInstanceId(protocolInstance.getId());
        eventLog.setProtocolDefinitionId(protocolDef.getId());
        eventLog.setActionId(actionId);

        // Find or create an actionable step
        StepInstance step = stepInstanceService.findActionableStep(
                protocolInstance.getId(), actionId);
        if (step == null) {
            step = createInitialStep(protocolInstance, actionId, actionCache);
        }

        // Complete the step
        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource());

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
     * Process a sub-step trigger match. The ancestry path is derived from the PlanDefinition tree:
     * e.g., ["topLevelId", "intermediateId"] for a sub-step nested two levels deep.
     * In the step-inside-step model, ancestor steps are COMPLETED (sub-steps are created
     * after parent completion). Finds the leaf sub-step and completes it.
     */
    private void processSubStepMatch(String subStepActionId, List<String> ancestryPath,
                                     ProtocolInstance protocolInstance, CloudEventMessage event,
                                     EventLog eventLog, List<PlanDefinitionParser.StepMetadata> actions) {
        String topLevelActionId = ancestryPath.get(0);

        eventLog.setProtocolInstanceId(protocolInstance.getId());
        eventLog.setProtocolDefinitionId(protocolInstance.getProtocolDefinition().getId());
        eventLog.setActionId(subStepActionId);

        // Find the top-level parent step (may be COMPLETED since sub-steps are post-completion)
        StepInstance currentParent = stepInstanceService.findStepByProtocolAndActionId(
                protocolInstance.getId(), topLevelActionId);
        if (currentParent == null) {
            // Parent step hasn't been created/completed yet — create and complete it on-demand
            currentParent = createInitialStep(protocolInstance, topLevelActionId, new HashMap<>());
            stepInstanceService.completeStep(currentParent, eventLog.getId(), event.getSource());
        }

        // For multi-level ancestry (3+ total depth), resolve intermediate parent steps
        for (int i = 1; i < ancestryPath.size(); i++) {
            String intermediateActionId = ancestryPath.get(i);
            StepInstance intermediateStep = stepInstanceService.findStepByProtocolAndActionId(
                    protocolInstance.getId(), intermediateActionId);
            if (intermediateStep == null) {
                // Intermediate step doesn't exist — create and complete on-demand
                String parentOfIntermediate = ancestryPath.get(i - 1);
                PlanDefinitionParser.StepMetadata intermediateInfo =
                        resolveSubStepInfo(intermediateActionId, parentOfIntermediate, actions);

                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                OffsetDateTime overdueDate = null;
                OffsetDateTime missedDate = null;
                String requiredBehavior = intermediateInfo != null ? intermediateInfo.requiredBehavior() : null;

                if (intermediateInfo != null && intermediateInfo.toleranceDays() != null) {
                    overdueDate = now.plusDays(intermediateInfo.toleranceDays());
                    missedDate = overdueDate.plusDays(intermediateInfo.toleranceDays());
                }

                intermediateStep = stepInstanceService.createStep(protocolInstance, intermediateActionId, 0,
                        now, overdueDate, missedDate, requiredBehavior,
                        currentParent.getId());
            }
            currentParent = intermediateStep;
        }

        // Find the actionable leaf sub-step (should already exist if parent completed normally)
        StepInstance subStep = stepInstanceService.findActionableStep(
                protocolInstance.getId(), subStepActionId);
        if (subStep == null) {
            // Sub-step doesn't exist yet — create on-demand with parent reference
            String immediateParentId = ancestryPath.get(ancestryPath.size() - 1);
            PlanDefinitionParser.StepMetadata subStepInfo =
                    resolveSubStepInfo(subStepActionId, immediateParentId, actions);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            String requiredBehavior = subStepInfo != null ? subStepInfo.requiredBehavior() : null;

            if (subStepInfo != null && subStepInfo.toleranceDays() != null) {
                overdueDate = now.plusDays(subStepInfo.toleranceDays());
                missedDate = overdueDate.plusDays(subStepInfo.toleranceDays());
            }

            subStep = stepInstanceService.createStep(protocolInstance, subStepActionId, 0,
                    now, overdueDate, missedDate, requiredBehavior,
                    currentParent.getId());
        }

        // Complete the sub-step
        stepInstanceService.completeStep(subStep, eventLog.getId(), event.getSource());

        // Evaluate intelligence actions for the sub-step
        intelligenceActionEvaluator.evaluateOnCompletion(subStep, event.getData());

        auditService.audit("COMPLIANCE", "SUB_STEP_MATCHED", "system",
                "EventLog", eventLog.getId().toString(),
                Map.of("protocolDefinitionId", protocolInstance.getProtocolDefinition().getId().toString(),
                        "actionId", subStepActionId,
                        "topLevelActionId", topLevelActionId,
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "patientId", event.getSubject()));
    }

    /**
     * Resolve a StepMetadata by its ID and parent action ID, searching the full action tree.
     */
    private PlanDefinitionParser.StepMetadata resolveSubStepInfo(
            String subStepId, String parentActionId,
            List<PlanDefinitionParser.StepMetadata> actions) {
        for (PlanDefinitionParser.StepMetadata action : actions) {
            if (parentActionId.equals(action.id())) {
                return action.subSteps().stream()
                        .filter(s -> subStepId.equals(s.id()))
                        .findFirst()
                        .orElse(null);
            }
            PlanDefinitionParser.StepMetadata result =
                    resolveSubStepInfoRecursive(subStepId, parentActionId, action.subSteps());
            if (result != null) return result;
        }
        return null;
    }

    private PlanDefinitionParser.StepMetadata resolveSubStepInfoRecursive(
            String subStepId, String parentActionId,
            List<PlanDefinitionParser.StepMetadata> subSteps) {
        if (subSteps == null) return null;
        for (PlanDefinitionParser.StepMetadata subStep : subSteps) {
            if (parentActionId.equals(subStep.id())) {
                if (subStep.subSteps() != null) {
                    return subStep.subSteps().stream()
                            .filter(s -> subStepId.equals(s.id()))
                            .findFirst()
                            .orElse(null);
                }
                return null;
            }
            PlanDefinitionParser.StepMetadata result =
                    resolveSubStepInfoRecursive(subStepId, parentActionId, subStep.subSteps());
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Find a StepMetadata anywhere in the PD tree by its plain action ID.
     * Returns null if the actionId is a top-level action (not a sub-step).
     */
    private PlanDefinitionParser.StepMetadata findSubStepInTree(
            String actionId, List<PlanDefinitionParser.StepMetadata> actions) {
        for (PlanDefinitionParser.StepMetadata action : actions) {
            PlanDefinitionParser.StepMetadata found = findSubStepRecursive(actionId, action.subSteps());
            if (found != null) return found;
        }
        return null;
    }

    private PlanDefinitionParser.StepMetadata findSubStepRecursive(
            String actionId, List<PlanDefinitionParser.StepMetadata> subSteps) {
        if (subSteps == null) return null;
        for (PlanDefinitionParser.StepMetadata subStep : subSteps) {
            if (actionId.equals(subStep.id())) return subStep;
            PlanDefinitionParser.StepMetadata found = findSubStepRecursive(actionId, subStep.subSteps());
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Find the ancestry path (list of ancestor action IDs from top-level to immediate parent)
     * for a sub-step identified by its plain action ID.
     * Returns null if the actionId is a top-level action (not a sub-step).
     * Example: for a sub-step "referral" nested under top-level "anc-visit-1",
     * returns ["anc-visit-1"]. For deeper nesting under "anc-visit-1" → "referral" → "ack",
     * returns ["anc-visit-1", "referral"].
     */
    private List<String> findAncestryPath(String actionId, List<PlanDefinitionParser.StepMetadata> actions) {
        for (PlanDefinitionParser.StepMetadata action : actions) {
            List<String> path = new ArrayList<>();
            path.add(action.id());
            if (findAncestryPathRecursive(actionId, action.subSteps(), path)) {
                return path;
            }
        }
        return null;
    }

    private boolean findAncestryPathRecursive(String targetId,
                                              List<PlanDefinitionParser.StepMetadata> subSteps,
                                              List<String> path) {
        if (subSteps == null) return false;
        for (PlanDefinitionParser.StepMetadata subStep : subSteps) {
            if (targetId.equals(subStep.id())) return true;
            path.add(subStep.id());
            if (findAncestryPathRecursive(targetId, subStep.subSteps(), path)) return true;
            path.remove(path.size() - 1);
        }
        return false;
    }

    private StepInstance createInitialStep(ProtocolInstance protocolInstance, String actionId,
                                           Map<UUID, List<PlanDefinitionParser.StepMetadata>> actionCache) {
        UUID protocolDefId = protocolInstance.getProtocolDefinition().getId();
        List<PlanDefinitionParser.StepMetadata> actions = getActionsForProtocol(protocolDefId, actionCache);

        PlanDefinitionParser.StepMetadata stepMetadata = actions.stream()
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
    private List<PlanDefinitionParser.StepMetadata> getActionsForProtocol(
            UUID protocolDefId, Map<UUID, List<PlanDefinitionParser.StepMetadata>> cache) {
        return cache.computeIfAbsent(protocolDefId, id -> {
            ProtocolDefinition protocolDef = protocolDefinitionService.findById(id);
            PlanDefinition planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());
            return planDefinitionParser.extractActions(planDefinition);
        });
    }
}
