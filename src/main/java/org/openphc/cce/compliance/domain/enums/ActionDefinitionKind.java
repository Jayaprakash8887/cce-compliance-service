package org.openphc.cce.compliance.domain.enums;

/**
 * FHIR R4 ActivityDefinition.kind values used as the action definition type.
 * Values use PascalCase to match FHIR RequestResourceType codes.
 */
public enum ActionDefinitionKind {
    CommunicationRequest,
    Task,
    ServiceRequest
}
