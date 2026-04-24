package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser.IntelligenceActionInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
     * Extract all actions from a PlanDefinition with their metadata.
     */
    public List<ActionMetadata> extractActions(PlanDefinition planDefinition) {
        List<ActionMetadata> result = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            result.add(buildActionMetadata(action));
        }
        return result;
    }

    /**
     * Build TriggerIndex entries from a PlanDefinition by decomposing each action's
     * trigger data[].codeFilter[] into individual rows.
     */
    public List<TriggerIndex> buildTriggerIndexEntries(PlanDefinition planDefinition, UUID protocolDefinitionId) {
        List<TriggerIndex> entries = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            String actionId = action.getId();

            for (TriggerDefinition trigger : action.getTrigger()) {
                for (DataRequirement dataReq : trigger.getData()) {
                    String resourceType = dataReq.getType();
                    if (resourceType == null) continue;

                    List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters = dataReq.getCodeFilter();
                    if (codeFilters == null || codeFilters.isEmpty()) {
                        // Scenario 1 (F1 only) or Scenario 3 (F1,F3) — resource type match only
                        // Index with empty path/system/code to mark presence
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
        return entries;
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
            for (TriggerDefinition trigger : action.getTrigger()) {
                if (!hasData(trigger) && getCondition(trigger) == null) {
                    throw new IllegalArgumentException(
                            "Action '" + action.getId() + "' has a trigger with no data[] and no condition. " +
                                    "At least one of data[] or condition must be present.");
                }
            }
        }
    }

    private ActionMetadata buildActionMetadata(PlanDefinition.PlanDefinitionActionComponent action) {
        // Extract triggers info
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

        // Extract related actions
        List<RelatedActionInfo> relatedActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionRelatedActionComponent ra : action.getRelatedAction()) {
            Duration offset = ra.getOffsetDuration();
            RelatedActionInfo info = new RelatedActionInfo(
                    ra.getActionId(),
                    ra.getRelationship() != null ? ra.getRelationship().toCode() : null,
                    offset != null ? offset.getValue() : null,
                    offset != null && offset.getUnit() != null ? offset.getUnit() : null
            );
            relatedActions.add(info);
        }

        // Extract timing
        TimingInfo timingInfo = null;
        if (action.hasTiming() && action.getTiming() instanceof Timing timing) {
            Timing.TimingRepeatComponent repeat = timing.getRepeat();
            if (repeat != null) {
                timingInfo = new TimingInfo(
                        repeat.hasCount() ? repeat.getCount() : null,
                        repeat.hasFrequency() ? repeat.getFrequency() : null,
                        repeat.hasPeriod() ? repeat.getPeriod() : null,
                        repeat.hasPeriodUnit() ? repeat.getPeriodUnit().toCode() : null
                );
            }
        }

        // Extract tolerance-days extension
        Integer toleranceDays = extractToleranceDays(action);

        // Extract requiredBehavior
        String requiredBehavior = action.hasRequiredBehavior()
                ? action.getRequiredBehavior().toCode()
                : null;

        // Extract intelligence actions
        List<IntelligenceActionInfo> intelligenceActions = extractIntelligenceActions(action);

        return new ActionMetadata(
                action.getId(),
                action.getTitle(),
                triggers,
                relatedActions,
                timingInfo,
                toleranceDays,
                requiredBehavior,
                intelligenceActions
        );
    }

    private List<IntelligenceActionInfo> extractIntelligenceActions(PlanDefinition.PlanDefinitionActionComponent action) {
        List<IntelligenceActionInfo> actions = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent intelligenceAction : action.getAction()) {
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
            if (condLanguage == null || condExpression == null) continue;

            // Extract definitionCanonical
            String definitionCanonical = null;
            if (intelligenceAction.hasDefinition() && intelligenceAction.getDefinition() instanceof CanonicalType canonical) {
                definitionCanonical = canonical.getValue();
            }

            // Skip intelligence actions without a definitionCanonical
            if (definitionCanonical == null) continue;

            // Extract severity and target extensions
            String severity = extractCodeExtension(intelligenceAction,
                    "http://openphc.org/fhir/StructureDefinition/intelligence-severity");
            String intelligenceChannel = extractCodeExtension(intelligenceAction,
                    "http://openphc.org/fhir/StructureDefinition/intelligence-channel");

            actions.add(new IntelligenceActionInfo(
                    intelligenceAction.getId(),
                    condLanguage,
                    condExpression,
                    definitionCanonical,
                    severity,
                    intelligenceChannel
            ));
        }

        return actions;
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

    public record ActionMetadata(
            String id,
            String title,
            List<TriggerInfo> triggers,
            List<RelatedActionInfo> relatedActions,
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

    public record RelatedActionInfo(
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
            String intelligenceChannel
    ) {}
}
