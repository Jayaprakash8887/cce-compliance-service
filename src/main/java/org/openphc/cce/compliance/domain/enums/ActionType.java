package org.openphc.cce.compliance.domain.enums;

/**
 * FHIR R4 ActivityDefinition.kind values used as the action type.
 * Values use PascalCase to match FHIR RequestResourceType codes.
 */
public enum ActionType {
    CommunicationRequest,
    Task,
    ServiceRequest
}
