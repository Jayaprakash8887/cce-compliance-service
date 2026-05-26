package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.openphc.cce.compliance.domain.enums.PlanDefinitionActionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
     * Sub-steps that don't already have a relatedStep to their parent get one added
     * automatically (relationship: "after-end", no offset).
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
     * Build TriggerIndex entries from a PlanDefinition by decomposing each action's
     * trigger data[].codeFilter[] into individual rows.
     * Sub-step triggers are indexed with their plain action ID; parent derivation
     * happens at runtime via the PlanDefinition tree.
     */
    public List<TriggerIndex> buildTriggerIndexEntries(PlanDefinition planDefinition, UUID protocolDefinitionId) {
        List<TriggerIndex> entries = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            // Index top-level action triggers
            indexActionTriggers(action, protocolDefinitionId, entries);

            // Recursively index sub-step triggers at all nesting levels (plain IDs)
            indexNestedSubStepTriggers(action, protocolDefinitionId, entries);
        }
        return entries;
    }

    /**
     * Recursively index sub-step triggers using each sub-step's own plain action ID.
     * Parent relationship is derived at runtime from the PlanDefinition tree structure.
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
                String resourceType = dataReq.getType();
                if (resourceType == null) continue;

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

        // If this is a nested step and has no explicit relatedStep to its parent, add one
        if (parentActionId != null && relatedSteps.stream()
                .noneMatch(rs -> parentActionId.equals(rs.actionId()))) {
            relatedSteps = new ArrayList<>(relatedSteps);
            relatedSteps.add(new RelatedStepInfo(parentActionId, "after-end", null, null));
        }

        result.add(new StepMetadata(
                action.getId(),
                action.getTitle(),
                triggers,
                relatedSteps,
                timingInfo,
                toleranceDays,
                requiredBehavior,
                intelligenceActions
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

    private TriggerIndex buildTriggerIndex(String resourceType, String path, String codeSystem,
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
            List<IntelligenceActionInfo> intelligenceActions
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
