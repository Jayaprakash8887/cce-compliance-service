package org.openphc.cce.compliance.domain.enums;

/**
 * Valid type codings for PlanDefinition actions.
 * Each action must declare one of these as {@code type.coding[0].code}.
 */
public enum PlanDefinitionActionType {

    STEP("step", "http://openphc.org/fhir/CodeSystem/action-type"),
    FIRE_EVENT("fire-event", "http://terminology.hl7.org/CodeSystem/action-type");

    private final String code;
    private final String system;

    PlanDefinitionActionType(String code, String system) {
        this.code = code;
        this.system = system;
    }

    public String getCode() {
        return code;
    }

    public String getSystem() {
        return system;
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
