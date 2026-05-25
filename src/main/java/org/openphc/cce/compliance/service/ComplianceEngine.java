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
        Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache = new HashMap<>();
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
        Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache = new HashMap<>();

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
                                                       Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        List<MatchedAction> finalMatches = new ArrayList<>();

        // Step 5: Tier 1 structural match
        List<MatchedAction> tier1Matches = triggerMatchingService.findStructuralMatches(resourceType, codes);

        // Step 6: Collect condition-only triggers
        List<ConditionOnlyTrigger> conditionOnlyTriggers = triggerMatchingService.getConditionOnlyTriggers();

        // Step 7: Tier 2 condition evaluation

        // Evaluate Tier 1 results — check if they have conditions
        for (MatchedAction match : tier1Matches) {
            List<PlanDefinitionParser.ActionMetadata> actions = getActionsForProtocol(match.protocolDefinitionId(), actionCache);

            String actionId = match.actionId();

            // Handle composite actionId for sub-steps (multi-level: "parent/child" or "grandparent/parent/child")
            if (actionId.contains("/")) {
                String[] segments = actionId.split("/");
                String leafId = segments[segments.length - 1];
                String immediateParentId = segments[segments.length - 2];

                PlanDefinitionParser.SubStepActionInfo subStepInfo =
                        resolveSubStepInfo(leafId, immediateParentId, actions);

                if (subStepInfo == null) {
                    continue;
                }

                // Check sub-step trigger conditions
                boolean hasCondition = subStepInfo.triggers().stream()
                        .anyMatch(t -> t.condition() != null);

                if (!hasCondition) {
                    finalMatches.add(match);
                } else {
                    boolean conditionMet = evaluateSubStepConditions(subStepInfo, eventData);
                    if (conditionMet) {
                        finalMatches.add(match);
                    }
                }
                continue;
            }

            PlanDefinitionParser.ActionMetadata actionMetadata = actions.stream()
                    .filter(a -> actionId.equals(a.id()))
                    .findFirst()
                    .orElse(null);

            if (actionMetadata == null) {
                continue;
            }

            // Check if this action's trigger has a condition
            boolean hasCondition = actionMetadata.triggers().stream()
                    .anyMatch(t -> t.condition() != null);

            if (!hasCondition) {
                // Scenario 1 (F1) or Scenario 2 (F1,F2) — no condition, pass directly
                finalMatches.add(match);
            } else {
                // Scenario 3 (F1,F3) or Scenario 4 (F1,F2,F3) — evaluate condition
                boolean conditionMet = evaluateActionConditions(actionMetadata, eventData);
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

    private boolean evaluateActionConditions(PlanDefinitionParser.ActionMetadata actionMetadata,
                                             JsonNode eventData) {

        for (PlanDefinitionParser.TriggerInfo trigger : actionMetadata.triggers()) {
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

    private boolean evaluateSubStepConditions(PlanDefinitionParser.SubStepActionInfo subStepInfo,
                                              JsonNode eventData) {
        for (PlanDefinitionParser.TriggerInfo trigger : subStepInfo.triggers()) {
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
                              Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(match.protocolDefinitionId());
        String patientId = event.getSubject();

        // Enroll patient (idempotent — returns existing if already enrolled)
        ProtocolInstance protocolInstance = protocolInstanceService.enrollPatient(
                patientId, protocolDef, OffsetDateTime.now(ZoneOffset.UTC));

        // Check if this is a sub-step match (composite actionId: "parentActionId/subStepId")
        String actionId = match.actionId();
        if (actionId.contains("/")) {
            processSubStepMatch(actionId, protocolInstance, event, eventLog, actionCache);
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
     * Process a sub-step trigger match. The composite actionId encodes the full hierarchy path:
     * "topLevelGroupId/subStepId" for 2-level, "topLevelGroupId/nestedGroupId/leafId" for 3-level, etc.
     * Ensures all ancestor steps exist, then finds/creates and completes the leaf sub-step.
     */
    private void processSubStepMatch(String compositeActionId, ProtocolInstance protocolInstance,
                                     CloudEventMessage event, EventLog eventLog,
                                     Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        String[] segments = compositeActionId.split("/");
        String topLevelActionId = segments[0];
        String leafActionId = segments[segments.length - 1];

        eventLog.setProtocolInstanceId(protocolInstance.getId());
        eventLog.setProtocolDefinitionId(protocolInstance.getProtocolDefinition().getId());
        eventLog.setActionId(compositeActionId);

        UUID protocolDefId = protocolInstance.getProtocolDefinition().getId();
        List<PlanDefinitionParser.ActionMetadata> actions = getActionsForProtocol(protocolDefId, actionCache);

        // Ensure all ancestor steps exist (from top-level group down to the immediate parent)
        StepInstance currentParent = stepInstanceService.findActionableStep(
                protocolInstance.getId(), topLevelActionId);
        if (currentParent == null) {
            currentParent = createInitialStep(protocolInstance, topLevelActionId, actionCache);

            // Create sub-steps for the newly created top-level group
            PlanDefinitionParser.ActionMetadata topMetadata = actions.stream()
                    .filter(a -> topLevelActionId.equals(a.id()))
                    .findFirst()
                    .orElse(null);
            if (topMetadata != null && topMetadata.hasSubSteps()) {
                stepInstanceService.createSubSteps(currentParent, topMetadata.subSteps());
            }
        }

        // For multi-level (3+ segments), ensure intermediate group steps exist
        for (int i = 1; i < segments.length - 1; i++) {
            String intermediateActionId = segments[i];
            StepInstance intermediateStep = stepInstanceService.findActionableStep(
                    protocolInstance.getId(), intermediateActionId);
            if (intermediateStep == null) {
                // Resolve sub-step metadata for the intermediate group
                PlanDefinitionParser.SubStepActionInfo intermediateInfo =
                        resolveSubStepInfo(intermediateActionId, segments[i - 1], actions);

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
                        currentParent.getId(), currentParent.getActionId());

                // If the intermediate is a group, create its entry-point sub-steps
                if (intermediateInfo != null && intermediateInfo.hasSubSteps()) {
                    stepInstanceService.createSubSteps(intermediateStep, intermediateInfo.subSteps());
                }
            }
            currentParent = intermediateStep;
        }

        // Find or create the leaf sub-step instance
        String immediateParentActionId = segments[segments.length - 2];
        StepInstance subStep = stepInstanceService.findActionableStep(
                protocolInstance.getId(), leafActionId);
        if (subStep == null) {
            // Resolve leaf sub-step metadata
            PlanDefinitionParser.SubStepActionInfo subStepInfo =
                    resolveSubStepInfo(leafActionId, immediateParentActionId, actions);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            String requiredBehavior = subStepInfo != null ? subStepInfo.requiredBehavior() : null;

            if (subStepInfo != null && subStepInfo.toleranceDays() != null) {
                overdueDate = now.plusDays(subStepInfo.toleranceDays());
                missedDate = overdueDate.plusDays(subStepInfo.toleranceDays());
            }

            subStep = stepInstanceService.createStep(protocolInstance, leafActionId, 0,
                    now, overdueDate, missedDate, requiredBehavior,
                    currentParent.getId(), immediateParentActionId);
        }

        // Complete the sub-step
        stepInstanceService.completeStep(subStep, eventLog.getId(), event.getSource());

        // Evaluate intelligence actions for the sub-step
        intelligenceActionEvaluator.evaluateOnCompletion(subStep, event.getData());

        auditService.audit("COMPLIANCE", "SUB_STEP_MATCHED", "system",
                "EventLog", eventLog.getId().toString(),
                Map.of("protocolDefinitionId", protocolInstance.getProtocolDefinition().getId().toString(),
                        "compositeActionId", compositeActionId,
                        "leafActionId", leafActionId,
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "patientId", event.getSubject()));
    }

    /**
     * Resolve a SubStepActionInfo by its ID and parent action ID, searching the full action tree.
     */
    private PlanDefinitionParser.SubStepActionInfo resolveSubStepInfo(
            String subStepId, String parentActionId,
            List<PlanDefinitionParser.ActionMetadata> actions) {
        for (PlanDefinitionParser.ActionMetadata action : actions) {
            if (parentActionId.equals(action.id())) {
                return action.subSteps().stream()
                        .filter(s -> subStepId.equals(s.id()))
                        .findFirst()
                        .orElse(null);
            }
            PlanDefinitionParser.SubStepActionInfo result =
                    resolveSubStepInfoRecursive(subStepId, parentActionId, action.subSteps());
            if (result != null) return result;
        }
        return null;
    }

    private PlanDefinitionParser.SubStepActionInfo resolveSubStepInfoRecursive(
            String subStepId, String parentActionId,
            List<PlanDefinitionParser.SubStepActionInfo> subSteps) {
        if (subSteps == null) return null;
        for (PlanDefinitionParser.SubStepActionInfo subStep : subSteps) {
            if (parentActionId.equals(subStep.id())) {
                if (subStep.subSteps() != null) {
                    return subStep.subSteps().stream()
                            .filter(s -> subStepId.equals(s.id()))
                            .findFirst()
                            .orElse(null);
                }
                return null;
            }
            PlanDefinitionParser.SubStepActionInfo result =
                    resolveSubStepInfoRecursive(subStepId, parentActionId, subStep.subSteps());
            if (result != null) return result;
        }
        return null;
    }

    private StepInstance createInitialStep(ProtocolInstance protocolInstance, String actionId,
                                           Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        UUID protocolDefId = protocolInstance.getProtocolDefinition().getId();
        List<PlanDefinitionParser.ActionMetadata> actions = getActionsForProtocol(protocolDefId, actionCache);

        PlanDefinitionParser.ActionMetadata actionMetadata = actions.stream()
                .filter(a -> actionId.equals(a.id()))
                .findFirst()
                .orElse(null);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime overdueDate = null;
        OffsetDateTime missedDate = null;
        String requiredBehavior = null;

        if (actionMetadata != null) {
            requiredBehavior = actionMetadata.requiredBehavior();
            if (actionMetadata.toleranceDays() != null) {
                overdueDate = now.plusDays(actionMetadata.toleranceDays());
                missedDate = overdueDate.plusDays(actionMetadata.toleranceDays());
            }
        }

        return stepInstanceService.createStep(protocolInstance, actionId, 0,
                now, overdueDate, missedDate, requiredBehavior);
    }

    /**
     * Cache-backed lookup of parsed action metadata for a protocol definition.
     * Avoids redundant PlanDefinition JSON parsing within a single event processing cycle.
     */
    private List<PlanDefinitionParser.ActionMetadata> getActionsForProtocol(
            UUID protocolDefId, Map<UUID, List<PlanDefinitionParser.ActionMetadata>> cache) {
        return cache.computeIfAbsent(protocolDefId, id -> {
            ProtocolDefinition protocolDef = protocolDefinitionService.findById(id);
            PlanDefinition planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());
            return planDefinitionParser.extractActions(planDefinition);
        });
    }
}
