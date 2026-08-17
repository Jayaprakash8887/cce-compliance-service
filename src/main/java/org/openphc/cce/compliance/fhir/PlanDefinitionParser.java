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
import java.util.LinkedHashMap;
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
     * How a FHIR {@code relatedAction.relationship} code positions the step that declares it
     * relative to the step it references.
     *
     * <p>{@code relatedAction} is a <strong>relative</strong> pointer: {@code relationship}
     * describes the declaring action's relationship to the referenced one. Which of the two comes
     * first therefore depends on the family:
     *
     * <ul>
     *   <li>{@code AFTER} — {@code lab-results.relatedAction = {actionId: lab-order,
     *       relationship: after-end, offset: 3d}} reads "lab-results happens 3 days after lab-order
     *       ends", so the <em>referenced</em> action is the prerequisite. An absent relationship is
     *       treated as {@code after-end}, the overwhelmingly common intent in authored protocols.</li>
     *   <li>{@code BEFORE} — {@code visit-encounter.relatedAction = {actionId: vitals-recording,
     *       relationship: before}} reads "visit-encounter happens before vitals-recording", so the
     *       <em>declaring</em> action is the prerequisite. Same ordering, stated from the other
     *       end.</li>
     *   <li>{@code UNORDERED} — {@code concurrent-*} says the two happen together and so implies no
     *       ordering. Such an edge drives neither progressive instantiation nor order checks;
     *       {@link #findUnorderedRelationships} reports them at load time so a protocol relying on
     *       one does not fail silently.</li>
     * </ul>
     */
    public enum RelationshipDirection { AFTER, BEFORE, UNORDERED }

    /** Classify a {@code relatedAction.relationship} code. A null relationship means after-end. */
    public static RelationshipDirection classifyRelationship(String relationship) {
        if (relationship == null) {
            return RelationshipDirection.AFTER;
        }
        return switch (relationship) {
            case "after", "after-start", "after-end" -> RelationshipDirection.AFTER;
            case "before", "before-start", "before-end" -> RelationshipDirection.BEFORE;
            default -> RelationshipDirection.UNORDERED;
        };
    }

    /**
     * The directed dependency graph of a protocol, in both directions.
     *
     * @param successors   prerequisite action id → the steps that depend on it, each paired with the
     *                     edge positioning it relative to that prerequisite. Drives progressive
     *                     instantiation, which works forward from a completed step.
     * @param predecessors dependent action id → the ids of the prerequisites it waits on
     */
    public record DependencyGraph(
            Map<String, List<StepDependency>> successors,
            Map<String, List<String>> predecessors
    ) {
        public List<StepDependency> successorsOf(String actionId) {
            return successors.getOrDefault(actionId, List.of());
        }

        public List<String> predecessorsOf(String actionId) {
            return predecessors.getOrDefault(actionId, List.of());
        }
    }

    /**
     * Normalize every ordering {@code relatedAction} into a single directed graph, whichever end of
     * the relationship it was written from (see {@link RelationshipDirection}).
     *
     * <p>Each resulting edge is expressed the {@code after-*} way round — the dependent step paired
     * with a {@link RelatedStepInfo} naming its prerequisite — so consumers need only handle one
     * form. {@code before-*} carries no start/end distinction that survives the flip (there is no
     * "before the prerequisite started"), so it normalizes to {@code after-end}: the dependent is
     * scheduled from the prerequisite's completion, offset by the same duration.
     *
     * <p>Edges naming an action the definition does not declare are skipped — there is no step to
     * schedule or wait on. {@link #findDanglingRelatedActions} reports them at load time.
     */
    public static DependencyGraph buildDependencyGraph(List<StepMetadata> steps) {
        Map<String, StepMetadata> stepsById = steps.stream()
                .collect(Collectors.toMap(StepMetadata::id, s -> s, (a, b) -> a));

        Map<String, List<StepDependency>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();

        for (StepMetadata step : steps) {
            for (RelatedStepInfo edge : step.relatedSteps()) {
                if (edge.actionId() == null || edge.actionId().isBlank()) {
                    continue;
                }

                String prerequisiteId;
                StepMetadata dependent;
                String relationship;

                switch (classifyRelationship(edge.relationship())) {
                    case AFTER -> {
                        prerequisiteId = edge.actionId();
                        dependent = step;
                        relationship = "after-start".equals(edge.relationship())
                                ? "after-start" : "after-end";
                    }
                    case BEFORE -> {
                        prerequisiteId = step.id();
                        dependent = stepsById.get(edge.actionId());
                        relationship = "after-end";
                    }
                    // concurrent-*: no ordering to derive
                    case UNORDERED -> {
                        continue;
                    }
                    default -> {
                        continue;
                    }
                }

                if (dependent == null || !stepsById.containsKey(prerequisiteId)) {
                    continue;
                }

                RelatedStepInfo normalized = new RelatedStepInfo(
                        prerequisiteId, relationship, edge.offsetValue(), edge.offsetUnit());

                successors.computeIfAbsent(prerequisiteId, k -> new ArrayList<>())
                        .add(new StepDependency(dependent, normalized));
                predecessors.computeIfAbsent(dependent.id(), k -> new ArrayList<>())
                        .add(prerequisiteId);
            }
        }

        return new DependencyGraph(successors, predecessors);
    }

    /** Look up a step's metadata by action id, or null when the definition has no such step. */
    public static StepMetadata findStep(List<StepMetadata> steps, String actionId) {
        return steps.stream()
                .filter(s -> actionId.equals(s.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Compute all transitive ancestors of a step in the dependency graph.
     * An ancestor of X is any step X depends on, directly or transitively.
     */
    public static Set<String> computeAncestors(String stepId, List<StepMetadata> steps) {
        return computeAncestors(stepId, buildDependencyGraph(steps));
    }

    public static Set<String> computeAncestors(String stepId, DependencyGraph graph) {
        Set<String> ancestors = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(stepId);
        visited.add(stepId);

        while (!queue.isEmpty()) {
            for (String prerequisiteId : graph.predecessorsOf(queue.poll())) {
                ancestors.add(prerequisiteId);
                if (visited.add(prerequisiteId)) {
                    queue.add(prerequisiteId);
                }
            }
        }
        return ancestors;
    }

    /**
     * The mandatory ("must") steps that should already have been carried out for the observed
     * progress to be legitimate: for every observed step, its transitive {@code relatedAction}
     * predecessors (ancestors) whose requiredBehavior is "must". Observed steps may appear in the
     * result when one is a predecessor of another — callers that only care about unrecorded work
     * filter by "has no step instance".
     *
     * <p>{@code StepInstanceService} materializes these when they have no step instance at all, so
     * a step that arrived without its prerequisites stops leaving them invisible. Mandatory work
     * still ahead is left to progressive instantiation, which creates it with the due dates its own
     * {@code relatedAction} offsets define.
     */
    public static Set<String> computeMustPredecessorSteps(Collection<String> observedStepIds,
                                                         List<StepMetadata> steps) {
        return computeMustPredecessorSteps(observedStepIds, steps, buildDependencyGraph(steps));
    }

    public static Set<String> computeMustPredecessorSteps(Collection<String> observedStepIds,
                                                         List<StepMetadata> steps,
                                                         DependencyGraph graph) {
        Set<String> mustStepIds = mustStepIds(steps);
        if (mustStepIds.isEmpty()) {
            return Set.of();
        }

        Set<String> predecessors = new HashSet<>();
        for (String stepId : observedStepIds) {
            for (String ancestorId : computeAncestors(stepId, graph)) {
                if (mustStepIds.contains(ancestorId)) {
                    predecessors.add(ancestorId);
                }
            }
        }
        return predecessors;
    }

    /**
     * Describe every {@code relatedAction} whose relationship implies no ordering
     * ({@code concurrent-*}), so a protocol that expects one to sequence steps is reported at load
     * time instead of quietly producing a disconnected graph.
     */
    public static List<String> findUnorderedRelationships(List<StepMetadata> steps) {
        List<String> found = new ArrayList<>();
        for (StepMetadata step : steps) {
            for (RelatedStepInfo edge : step.relatedSteps()) {
                if (classifyRelationship(edge.relationship()) == RelationshipDirection.UNORDERED) {
                    found.add("action '" + step.id() + "' -> '" + edge.actionId()
                            + "' (relationship=" + edge.relationship() + ")");
                }
            }
        }
        return found;
    }

    /**
     * Describe every {@code relatedAction} naming an action the definition does not declare. Such an
     * edge can neither schedule a step nor be waited on, so it is silently inert — reported at load
     * time so an authoring typo does not pass unnoticed.
     */
    public static List<String> findDanglingRelatedActions(List<StepMetadata> steps) {
        Set<String> knownIds = steps.stream()
                .map(StepMetadata::id)
                .collect(Collectors.toSet());

        List<String> found = new ArrayList<>();
        for (StepMetadata step : steps) {
            for (RelatedStepInfo edge : step.relatedSteps()) {
                if (edge.actionId() != null && !knownIds.contains(edge.actionId())) {
                    found.add("action '" + step.id() + "' -> unknown action '"
                            + edge.actionId() + "'");
                }
            }
        }
        return found;
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
     * Each nested step-type action becomes a peer entry. Nesting carries no dependency of its
     * own — see the comment on the implicit-link decision below.
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
        // Historically a relatedStep (child -> parent, after-end) was auto-added here, and was
        // removed because the then-inverted reading of relatedAction turned it into "completing
        // the child re-creates the parent", spawning duplicate parent steps. That hazard is gone
        // now that relatedAction is read as a backward pointer — such a link would correctly mean
        // "the child comes after the parent ends" — but it is deliberately still not added:
        // whether a sub-step waits on its parent is a clinical decision for the protocol author,
        // and plenty of sub-steps (e.g. a referral raised during a visit) are meant to be created
        // by their own trigger without waiting.

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

    /**
     * Extract an action's {@code relatedAction} entries verbatim. Each one names a step this action
     * is positioned <em>relative to</em>, not a step this action leads to — see
     * {@link #classifyRelationship} for which end comes first.
     */
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

    /**
     * A forward edge in the dependency graph: {@code step} depends on the prerequisite named by
     * {@code edge.actionId()}, positioned by {@code edge}'s relationship and offset.
     *
     * @param step the dependent step — the one to create once the prerequisite completes
     * @param edge the {@code relatedAction} the dependent step declares toward that prerequisite
     */
    public record StepDependency(
            StepMetadata step,
            RelatedStepInfo edge
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
