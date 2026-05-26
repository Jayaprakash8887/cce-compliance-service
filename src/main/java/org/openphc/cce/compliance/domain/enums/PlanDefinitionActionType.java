package org.openphc.cce.compliance.domain.enums;

/**
 * Valid type codings for PlanDefinition actions.
 * Each action must declare one of these as {@code type.coding[0].code}.
 */
public enum PlanDefinitionActionType {

    STEP("step"),
    FIRE_EVENT("fire-event");

    private final String code;

    PlanDefinitionActionType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Resolve an ActionType from its FHIR coding string.
     *
     * @return the matching enum value, or null if not recognized
     */
    public static PlanDefinitionActionType fromCode(String code) {
        for (PlanDefinitionActionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
