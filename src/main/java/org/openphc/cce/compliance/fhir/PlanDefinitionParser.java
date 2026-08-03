package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.openphc.cce.compliance.domain.enums.PlanDefinitionActionType;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PlanDefinitionParser {

    private final IParser fhirJsonParser;

    public PlanDefinitionParser(FhirContext fhirContext) {
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.fhirJsonParser.setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler());
    }

    /**
     * Parse PlanDefinition JSON into a FHIR PlanDefinition resource.
     */
    public PlanDefinition parse(String json) {
        return fhirJsonParser.parseResource(PlanDefinition.class, json);
    }

    /**
     * Extract all steps from a PlanDefinition with their metadata.
     * Flattens nested sub-steps into a single list — all steps are treated uniformly.
     * Nesting is organizational only and does NOT create an implicit step dependency;
     * ordering between a parent and its sub-steps (and among sub-steps) must be expressed
     * explicitly via relatedAction.
     */
    public List<StepMetadata> extractSteps(PlanDefinition planDefinition) {
        List<StepMetadata> result = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionType(action);
            flattenAction(action, null, result);
        }
        return result;
    }

    /**
     * Compute all transitive ancestors of a step in the dependency graph.
     * An ancestor of X is any step A such that A's relatedSteps (directly or transitively)
     * lead to X being created.
     */
    public static Set<String> computeAncestors(String stepId, List<StepMetadata> steps) {
        Set<String> ancestors = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(stepId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (StepMetadata step : steps) {
                boolean createsTarget = step.relatedSteps().stream()
                        .anyMatch(ra -> current.equals(ra.actionId()));
                if (createsTarget && ancestors.add(step.id())) {
                    queue.add(step.id());
                }
            }
        }
        return ancestors;
    }

    /**
     * Compute the "must" step ids nested under the same top-level PlanDefinition action as the
     * given step — i.e. all mandatory descendants of its root ancestor (the top-level action it
     * is nested under, per {@code action.action} in the PlanDefinition JSON).
     *
     * <p>Nesting groups sub-steps under a parent for indexing purposes only and does not create an
     * implicit step dependency (see {@link #extractSteps}). But once any sub-step in a group has
     * been observed, progress on that group has started, so its other mandatory sub-steps — even
     * ones whose own trigger never fired and were therefore never materialized — must also be
     * satisfied before the protocol instance can be considered complete.
     */
    public static Set<String> computeMustGroupSteps(String stepId, List<StepMetadata> allSteps) {
        Map<String, StepMetadata> stepsById = allSteps.stream()
                .collect(Collectors.toMap(StepMetadata::id, s -> s, (a, b) -> a));

        String rootId = stepId;
        StepMetadata current = stepsById.get(rootId);
        while (current != null && current.parentActionId() != null) {
            rootId = current.parentActionId();
            current = stepsById.get(rootId);
        }

        Set<String> group = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        group.add(rootId);
        while (!queue.isEmpty()) {
            String parentId = queue.poll();
            for (StepMetadata step : allSteps) {
                if (parentId.equals(step.parentActionId()) && group.add(step.id())) {
                    queue.add(step.id());
                }
            }
        }

        Set<String> mustGroupSteps = new HashSet<>();
        for (String memberId : group) {
            StepMetadata member = stepsById.get(memberId);
            if (member != null && "must".equals(member.requiredBehavior())) {
                mustGroupSteps.add(memberId);
            }
        }
        return mustGroupSteps;
    }

    /**
     * The mandatory ("must") step ids a protocol instance is expected to satisfy given the
     * progress observed so far: for every observed step, the step itself, all its transitive
     * predecessors (ancestors), and all mandatory steps nested under the same top-level
     * PlanDefinition action (group siblings) — restricted to steps whose requiredBehavior is
     * "must".
     *
     * <p>A mandatory step is therefore only expected once progress that depends on it, or that
     * shares its nesting group, has actually been observed. That keeps genuinely short journeys
     * completable and keeps mandatory work belonging to a future part of the protocol (e.g. a later
     * ANC visit's referral) out of scope.
     *
     * <p>Used by {@code ProtocolInstanceService.checkAndCompleteProtocol} to gate completion on all
     * of them being terminal. For the narrower "already late" set that the backfill materializes,
     * see {@link #computeMustPredecessorSteps}.
     */
    public static Set<String> computeExpectedMustSteps(Collection<String> observedStepIds,
                                                      List<StepMetadata> steps) {
        Set<String> mustStepIds = mustStepIds(steps);
        if (mustStepIds.isEmpty()) {
            return Set.of();
        }

        Set<String> expected = new HashSet<>(computeMustPredecessorSteps(observedStepIds, steps));
        for (String stepId : observedStepIds) {
            if (mustStepIds.contains(stepId)) {
                expected.add(stepId);
            }
            expected.addAll(computeMustGroupSteps(stepId, steps));
        }
        return expected;
    }

    /**
     * The mandatory ("must") steps that should already have been carried out for the observed
     * progress to be legitimate: for every observed step, its transitive {@code relatedAction}
     * predecessors (ancestors) whose requiredBehavior is "must". Observed steps may appear in the
     * result when one is a predecessor of another — callers that only care about unrecorded work
     * filter by "has no step instance".
     *
     * <p>Deliberately narrower than {@link #computeExpectedMustSteps}: it contains only work that
     * is already late, never work still ahead in the chain. {@code StepInstanceService} materializes
     * these when they have no step instance at all, so a step that arrived without its prerequisites
     * stops leaving them invisible. Mandatory work still ahead is left to progressive instantiation,
     * which creates it with the due dates its own {@code relatedAction} offsets define; the wider
     * expected set still gates protocol completion, so a mandatory step that is never recorded
     * cannot slip through unnoticed.
     */
    public static Set<String> computeMustPredecessorSteps(Collection<String> observedStepIds,
                                                         List<StepMetadata> steps) {
        Set<String> mustStepIds = mustStepIds(steps);
        if (mustStepIds.isEmpty()) {
            return Set.of();
        }

        Set<String> predecessors = new HashSet<>();
        for (String stepId : observedStepIds) {
            for (String ancestorId : computeAncestors(stepId, steps)) {
                if (mustStepIds.contains(ancestorId)) {
                    predecessors.add(ancestorId);
                }
            }
        }
        return predecessors;
    }

    /** The ids of every step whose requiredBehavior is "must". */
    private static Set<String> mustStepIds(List<StepMetadata> steps) {
        return steps.stream()
                .filter(s -> "must".equals(s.requiredBehavior()))
                .map(StepMetadata::id)
                .collect(Collectors.toSet());
    }

    /**
     * Build TriggerIndex entries from a PlanDefinition by decomposing each action's
     * trigger data[].codeFilter[] into individual rows.
     * Sub-step triggers are indexed with their own action ID (flat model).
     */
    public List<TriggerIndex> buildTriggerIndexEntries(PlanDefinition planDefinition, UUID protocolDefinitionId) {
        List<TriggerIndex> entries = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            // Index top-level action triggers
            indexActionTriggers(action, protocolDefinitionId, entries);

            // Recursively index sub-step triggers at all nesting levels
            indexNestedSubStepTriggers(action, protocolDefinitionId, entries);
        }
        return entries;
    }

    /**
     * Recursively index sub-step triggers using each sub-step's own action ID.
     */
    private void indexNestedSubStepTriggers(PlanDefinition.PlanDefinitionActionComponent parentAction,
                                            UUID protocolDefinitionId,
                                            List<TriggerIndex> entries) {
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : parentAction.getAction()) {
            if (isStepAction(nestedAction)) {
                indexActionTriggers(nestedAction, protocolDefinitionId, entries);

                // Recurse for deeper nesting levels
                indexNestedSubStepTriggers(nestedAction, protocolDefinitionId, entries);
            }
        }
    }

    private void indexActionTriggers(PlanDefinition.PlanDefinitionActionComponent action, 
                                     UUID protocolDefinitionId, List<TriggerIndex> entries) {
        String actionId = action.getId();

        for (TriggerDefinition trigger : action.getTrigger()) {
            for (DataRequirement dataReq : trigger.getData()) {
                if (dataReq.getType() == null) continue;
                ResourceType resourceType = parseResourceType(dataReq.getType());

                List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters = dataReq.getCodeFilter();
                if (codeFilters == null || codeFilters.isEmpty()) {
                    entries.add(buildTriggerIndex(resourceType, "", "", "", protocolDefinitionId, actionId));
                    continue;
                }

                for (DataRequirement.DataRequirementCodeFilterComponent cf : codeFilters) {
                    String path = cf.getPath() != null ? cf.getPath() : "";
                    for (Coding coding : cf.getCode()) {
                        String system = coding.getSystem() != null ? coding.getSystem() : "";
                        String code = coding.getCode() != null ? coding.getCode()
                                : (coding.getDisplay() != null ? coding.getDisplay() : "");
                        entries.add(buildTriggerIndex(resourceType, path, system, code, protocolDefinitionId, actionId));
                    }
                }
            }
        }
    }

    /**
     * Extract condition-only triggers — triggers that have no data[] section, only a condition.
     * These are held in-memory and evaluated via Tier 2 for every inbound event.
     */
    public List<ConditionOnlyTriggerInfo> extractConditionOnlyTriggers(PlanDefinition planDefinition) {
        List<ConditionOnlyTriggerInfo> result = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            String actionId = action.getId();

            for (TriggerDefinition trigger : action.getTrigger()) {
                if (hasData(trigger)) continue;

                Expression condition = getCondition(trigger);
                if (condition != null) {
                    result.add(new ConditionOnlyTriggerInfo(
                            actionId,
                            condition.getLanguage(),
                            condition.getExpression()
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Validate triggers at load time. Rejects any trigger that has no data[] AND no condition.
     *
     * @throws IllegalArgumentException if an invalid trigger is found
     */
    public void validateTriggers(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionTriggers(action);
        }
    }

    /**
     * Validate that every action (including nested sub-steps) declares an explicit
     * type coding of 'step' or 'fire-event'. Mirrors the validation that
     * {@link #extractSteps(PlanDefinition)} performs lazily at event-processing time,
     * so a malformed protocol is rejected at load time rather than poisoning the
     * event pipeline (and DLQ-ing inbound events) on first match.
     *
     * <p>Children of step-type actions are validated recursively; fire-event actions
     * are treated as leaves and their children are not inspected — matching the
     * behaviour of {@code flattenAction}.
     *
     * @throws IllegalArgumentException if any action is missing a type coding or has
     *                                  an unsupported one
     */
    public void validateActionTypes(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionType(action);
            validateNestedActionTypes(action);
        }
    }

    private void validateNestedActionTypes(PlanDefinition.PlanDefinitionActionComponent action) {
        for (PlanDefinition.PlanDefinitionActionComponent nested : action.getAction()) {
            validateActionType(nested);
            if (isStepAction(nested)) {
                validateNestedActionTypes(nested);
            }
        }
    }

    /**
     * Validate that all actions (including nested sub-steps) have a non-blank actionId
     * and that all actionIds are unique across the entire PlanDefinition.
     *
     * @throws IllegalArgumentException if an actionId is missing/blank or duplicated
     */
    public void validateActionIds(PlanDefinition planDefinition) {
        List<String> allIds = new ArrayList<>();
        collectActionIds(planDefinition.getAction(), allIds);

        // Check for duplicates
        Set<String> seen = new HashSet<>();
        for (String id : allIds) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                        "Duplicate actionId '" + id + "' found in PlanDefinition. " +
                                "All action IDs must be unique across all levels.");
            }
        }
    }

    private void collectActionIds(List<PlanDefinition.PlanDefinitionActionComponent> actions,
                                  List<String> ids) {
        for (PlanDefinition.PlanDefinitionActionComponent action : actions) {
            String id = action.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "Action with title '" + (action.hasTitle() ? action.getTitle() : "<untitled>")
                                + "' is missing a required actionId.");
            }
            ids.add(id);
            // Recurse into nested actions (both step and fire-event types have IDs)
            if (action.hasAction()) {
                collectActionIds(action.getAction(), ids);
            }
        }
    }

    private void validateActionTriggers(PlanDefinition.PlanDefinitionActionComponent action) {
        for (TriggerDefinition trigger : action.getTrigger()) {
            if (!hasData(trigger) && getCondition(trigger) == null) {
                throw new IllegalArgumentException(
                        "Action '" + action.getId() + "' has a trigger with no data[] and no condition. " +
                                "At least one of data[] or condition must be present.");
            }
        }

        // Recursively validate nested steps
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                validateActionTriggers(nestedAction);
            }
        }
    }

    /**
     * Recursively flatten an action and its nested sub-steps into the result list.
     * Each nested step-type action becomes a peer entry. If a nested step has no explicit
     * relatedStep pointing to its parent, one is added (after-end, no offset) to preserve
     * the progressive instantiation relationship.
     */
    private void flattenAction(PlanDefinition.PlanDefinitionActionComponent action,
                               String parentActionId, List<StepMetadata> result) {
        List<TriggerInfo> triggers = extractTriggerInfos(action);
        List<RelatedStepInfo> relatedSteps = extractRelatedSteps(action);
        TimingInfo timingInfo = extractTimingInfo(action);
        Integer toleranceDays = extractToleranceDays(action);

        // Extract requiredBehavior
        String requiredBehavior = action.hasRequiredBehavior()
                ? action.getRequiredBehavior().toCode()
                : null;

        // Extract intelligence actions from nested fire-event actions
        List<IntelligenceActionInfo> intelligenceActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isIntelligenceAction(nestedAction)) {
                IntelligenceActionInfo intelligenceAction = buildIntelligenceActionInfo(nestedAction);
                if (intelligenceAction != null) {
                    intelligenceActions.add(intelligenceAction);
                }
            } else if (!isStepAction(nestedAction)) {
                throw new IllegalArgumentException(
                        "Nested action '" + nestedAction.getId()
                                + "' must have explicit type coding: 'step' or 'fire-event'");
            }
        }

        // Nesting is organizational only: it groups sub-steps under a parent and lets
        // sub-step triggers be indexed with their own action IDs. It does NOT create an
        // implicit step dependency. Ordering between a parent and its sub-steps (and among
        // sub-steps) must be expressed explicitly via relatedAction.
        //
        // Historically a backward relatedStep (child -> parent, after-end) was auto-added
        // here. Under the forward progressive-instantiation model (see
        // StepInstanceService.createDependentSteps) that link meant "completing the child
        // re-creates the parent", spawning duplicate parent steps. It has been removed.

        result.add(new StepMetadata(
                action.getId(),
                action.getTitle(),
                triggers,
                relatedSteps,
                timingInfo,
                toleranceDays,
                requiredBehavior,
                intelligenceActions,
                parentActionId
        ));

        // Recursively flatten nested step-type actions
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                flattenAction(nestedAction, action.getId(), result);
            }
        }
    }

    private boolean isStepAction(PlanDefinition.PlanDefinitionActionComponent action) {
        return hasTypeCoding(action, PlanDefinitionActionType.STEP);
    }

    private boolean isIntelligenceAction(PlanDefinition.PlanDefinitionActionComponent action) {
        return hasTypeCoding(action, PlanDefinitionActionType.FIRE_EVENT);
    }

    /**
     * Validate that an action has a required type coding.
     * Valid types: "step" (trigger-based, may contain sub-steps) or "fire-event" (intelligence).
     */
    private void validateActionType(PlanDefinition.PlanDefinitionActionComponent action) {
        if (!action.hasType()) {
            throw new IllegalArgumentException(
                    "Action '" + action.getId() + "' must have explicit type coding: 'step' or 'fire-event'");
        }
        if (!isStepAction(action) && !isIntelligenceAction(action)) {
            throw new IllegalArgumentException(
                    "Action '" + action.getId() + "' has unsupported type coding. Must be 'step' or 'fire-event'");
        }
    }

    private boolean hasTypeCoding(PlanDefinition.PlanDefinitionActionComponent action, PlanDefinitionActionType planActionType) {
        if (!action.hasType()) {
            return false;
        }
        CodeableConcept type = action.getType();
        for (Coding coding : type.getCoding()) {
            if (planActionType.getCode().equals(coding.getCode())
                    && planActionType.getSystem().equals(coding.getSystem())) {
                return true;
            }
        }
        return false;
    }


    private List<TriggerInfo> extractTriggerInfos(PlanDefinition.PlanDefinitionActionComponent action) {
        List<TriggerInfo> triggers = new ArrayList<>();
        for (TriggerDefinition trigger : action.getTrigger()) {
            List<DataRequirementInfo> dataReqs = new ArrayList<>();
            for (DataRequirement dr : trigger.getData()) {
                List<CodeFilterInfo> codeFilters = new ArrayList<>();
                if (dr.getCodeFilter() != null) {
                    for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
                        List<CodingInfo> codes = new ArrayList<>();
                        for (Coding c : cf.getCode()) {
                            codes.add(new CodingInfo(c.getSystem(), c.getCode()));
                        }
                        codeFilters.add(new CodeFilterInfo(cf.getPath(), codes));
                    }
                }
                dataReqs.add(new DataRequirementInfo(dr.getType(), codeFilters));
            }

            ConditionInfo conditionInfo = null;
            Expression cond = getCondition(trigger);
            if (cond != null) {
                conditionInfo = new ConditionInfo(cond.getLanguage(), cond.getExpression());
            }
            triggers.add(new TriggerInfo(dataReqs, conditionInfo));
        }
        return triggers;
    }

    private List<RelatedStepInfo> extractRelatedSteps(PlanDefinition.PlanDefinitionActionComponent action) {
        List<RelatedStepInfo> relatedSteps = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionRelatedActionComponent ra : action.getRelatedAction()) {
            Duration offset = ra.getOffsetDuration();
            relatedSteps.add(new RelatedStepInfo(
                    ra.getActionId(),
                    ra.getRelationship() != null ? ra.getRelationship().toCode() : null,
                    offset != null ? offset.getValue() : null,
                    offset != null && offset.getUnit() != null ? offset.getUnit() : null
            ));
        }
        return relatedSteps;
    }

    private TimingInfo extractTimingInfo(PlanDefinition.PlanDefinitionActionComponent action) {
        if (action.hasTiming() && action.getTiming() instanceof Timing timing) {
            Timing.TimingRepeatComponent repeat = timing.getRepeat();
            if (repeat != null) {
                return new TimingInfo(
                        repeat.hasCount() ? repeat.getCount() : null,
                        repeat.hasFrequency() ? repeat.getFrequency() : null,
                        repeat.hasPeriod() ? repeat.getPeriod() : null,
                        repeat.hasPeriodUnit() ? repeat.getPeriodUnit().toCode() : null
                );
            }
        }
        return null;
    }

    private IntelligenceActionInfo buildIntelligenceActionInfo(PlanDefinition.PlanDefinitionActionComponent intelligenceAction) {
        // Extract condition (kind=applicability)
        String condLanguage = null;
        String condExpression = null;
        for (PlanDefinition.PlanDefinitionActionConditionComponent cond : intelligenceAction.getCondition()) {
            if (cond.getKind() == PlanDefinition.ActionConditionKind.APPLICABILITY
                    && cond.hasExpression()
                    && cond.getExpression().hasExpression()) {
                condLanguage = cond.getExpression().getLanguage();
                condExpression = cond.getExpression().getExpression();
                break;
            }
        }

        // Skip intelligence actions without a condition
        if (condLanguage == null || condExpression == null) return null;

        // Extract definitionCanonical
        String definitionCanonical = null;
        if (intelligenceAction.hasDefinition() && intelligenceAction.getDefinition() instanceof CanonicalType canonical) {
            definitionCanonical = canonical.getValue();
        }

        // Skip intelligence actions without a definitionCanonical
        if (definitionCanonical == null) return null;

        // Extract required severity and destination extensions
        String severity = extractCodeExtension(intelligenceAction,
                "http://openphc.org/fhir/StructureDefinition/intelligence-severity");
        String intelligenceDestination = extractCodeExtension(intelligenceAction,
                "http://openphc.org/fhir/StructureDefinition/intelligence-destination");

        // Reject intelligence actions missing required extensions
        if (severity == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + intelligenceAction.getId()
                            + "' is missing required extension: intelligence-severity");
        }
        if (intelligenceDestination == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + intelligenceAction.getId()
                            + "' is missing required extension: intelligence-destination");
        }

        return new IntelligenceActionInfo(
                intelligenceAction.getId(),
                condLanguage,
                condExpression,
                definitionCanonical,
                severity,
                intelligenceDestination
        );
    }

    private Integer extractToleranceDays(PlanDefinition.PlanDefinitionActionComponent action) {
        Extension ext = action.getExtensionByUrl("http://openphc.org/fhir/StructureDefinition/tolerance-days");
        if (ext != null && ext.getValue() instanceof IntegerType intVal) {
            return intVal.getValue();
        }
        return null;
    }

    private String extractCodeExtension(PlanDefinition.PlanDefinitionActionComponent action, String url) {
        Extension ext = action.getExtensionByUrl(url);
        if (ext != null && ext.getValue() instanceof CodeType codeVal) {
            return codeVal.getCode();
        }
        return null;
    }

    private boolean hasData(TriggerDefinition trigger) {
        return trigger.getData() != null && !trigger.getData().isEmpty();
    }

    private Expression getCondition(TriggerDefinition trigger) {
        if (trigger.hasCondition()) {
            Expression cond = trigger.getCondition();
            if (cond.hasExpression() && !cond.getExpression().isBlank()) {
                return cond;
            }
        }
        return null;
    }

    /**
     * Parse a FHIR {@code DataRequirement.type} code into a {@link ResourceType}. Rejected at
     * protocol load time (mapped to 400) if the code is not a known FHIR resource type.
     */
    private ResourceType parseResourceType(String code) {
        try {
            return ResourceType.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown FHIR resource type in trigger data.type: " + code, e);
        }
    }

    private TriggerIndex buildTriggerIndex(ResourceType resourceType, String path, String codeSystem,
                                           String codeValue, UUID protocolDefinitionId, String actionId) {
        return TriggerIndex.builder()
                .id(TriggerIndexId.builder()
                        .resourceType(resourceType)
                        .path(path)
                        .codeSystem(codeSystem)
                        .codeValue(codeValue)
                        .protocolDefinitionId(protocolDefinitionId)
                        .actionId(actionId)
                        .build())
                .build();
    }

    // ── Inner record types for structured extraction ──

    public record StepMetadata(
            String id,
            String title,
            List<TriggerInfo> triggers,
            List<RelatedStepInfo> relatedSteps,
            TimingInfo timing,
            Integer toleranceDays,
            String requiredBehavior,
            List<IntelligenceActionInfo> intelligenceActions,
            String parentActionId
    ) {}

    public record TriggerInfo(
            List<DataRequirementInfo> dataRequirements,
            ConditionInfo condition
    ) {}

    public record DataRequirementInfo(
            String resourceType,
            List<CodeFilterInfo> codeFilters
    ) {}

    public record CodeFilterInfo(
            String path,
            List<CodingInfo> codes
    ) {}

    public record CodingInfo(
            String system,
            String code
    ) {}

    public record ConditionInfo(
            String language,
            String expression
    ) {}

    public record RelatedStepInfo(
            String actionId,
            String relationship,
            java.math.BigDecimal offsetValue,
            String offsetUnit
    ) {}

    public record TimingInfo(
            Integer count,
            Integer frequency,
            java.math.BigDecimal period,
            String periodUnit
    ) {}

    public record ConditionOnlyTriggerInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression
    ) {}

    public record IntelligenceActionInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression,
            String definitionCanonical,
            String severity,
            String intelligenceDestination
    ) {}


}
