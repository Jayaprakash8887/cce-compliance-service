package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ActionType;
import org.openphc.cce.compliance.domain.enums.IntelligenceSeverity;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ActionDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ActionDefinitionService.class);

    private final ActionDefinitionRepository actionDefinitionRepository;
    private final IntelligenceEventLogRepository intelligenceEventLogRepository;
    private final AuditService auditService;

    public ActionDefinitionService(ActionDefinitionRepository actionDefinitionRepository,
                                   IntelligenceEventLogRepository intelligenceEventLogRepository,
                                   AuditService auditService) {
        this.actionDefinitionRepository = actionDefinitionRepository;
        this.intelligenceEventLogRepository = intelligenceEventLogRepository;
        this.auditService = auditService;
    }

    /**
     * Create an ActionDefinition from a FHIR ActivityDefinition JSON.
     * Extracts url, version, name, title, kind (→ actionType), and CCE extensions for severity/target.
     *
     * @param definition the FHIR ActivityDefinition JSON
     * @return the persisted ActionDefinition
     * @throws IllegalArgumentException if required fields are missing or a duplicate (url, version) exists
     */
    public ActionDefinition createActionDefinition(JsonNode definition) {
        String url = requireTextField(definition, "url");
        String version = requireTextField(definition, "version");

        if (actionDefinitionRepository.findByCanonicalUrlAndVersion(url, version).isPresent()) {
            throw new IllegalArgumentException(
                    "Action definition already exists for url=" + url + ", version=" + version);
        }

        String name = textField(definition, "name");
        String title = textField(definition, "title");
        ActionType actionType = extractActionType(definition);
        IntelligenceSeverity severity = extractExtensionEnum(definition,
                "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
                IntelligenceSeverity.class);
        String destination = extractExtensionString(definition,
                "http://openphc.org/fhir/StructureDefinition/intelligence-destination");

        ActionDefinition actionDef = ActionDefinition.builder()
                .canonicalUrl(url)
                .version(version)
                .name(name)
                .title(title)
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(actionType)
                .severity(severity)
                .intelligenceDestination(destination)
                .definition(definition)
                .build();

        actionDef = actionDefinitionRepository.save(actionDef);

        auditService.audit("ACTION_DEFINITION", "ACTION_DEFINITION_CREATED", "system",
                "ActionDefinition", actionDef.getId().toString(),
                Map.of("canonicalUrl", url, "version", version, "actionType", actionType.name()));

        log.info("Created action definition: {} (id={})", actionDef.getCanonical(), actionDef.getId());

        return actionDef;
    }

    /**
     * Update an existing ActionDefinition by re-extracting fields from the new definition JSON.
     */
    public ActionDefinition updateActionDefinition(UUID id, JsonNode definition) {
        ActionDefinition actionDef = findByIdOrThrow(id);

        String url = requireTextField(definition, "url");
        String version = requireTextField(definition, "version");

        // Check for duplicate if url/version changed
        Optional<ActionDefinition> existing = actionDefinitionRepository.findByCanonicalUrlAndVersion(url, version);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw new IllegalArgumentException(
                    "Action definition already exists for url=" + url + ", version=" + version);
        }

        actionDef.setCanonicalUrl(url);
        actionDef.setVersion(version);
        actionDef.setName(textField(definition, "name"));
        actionDef.setTitle(textField(definition, "title"));
        actionDef.setActionType(extractActionType(definition));
        actionDef.setSeverity(extractExtensionEnum(definition,
                "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
                IntelligenceSeverity.class));
        actionDef.setIntelligenceDestination(extractExtensionString(definition,
                "http://openphc.org/fhir/StructureDefinition/intelligence-destination"));
        actionDef.setDefinition(definition);

        actionDef = actionDefinitionRepository.save(actionDef);

        auditService.audit("ACTION_DEFINITION", "ACTION_DEFINITION_UPDATED", "system",
                "ActionDefinition", id.toString(),
                Map.of("canonicalUrl", url, "version", version));

        log.info("Updated action definition: {} (id={})", actionDef.getCanonical(), id);

        return actionDef;
    }

    /**
     * Retire an ActionDefinition. Sets status to RETIRED.
     */
    public ActionDefinition retireActionDefinition(UUID id) {
        ActionDefinition actionDef = findByIdOrThrow(id);

        if (actionDef.getStatus() == ActionDefinitionStatus.RETIRED) {
            throw new IllegalStateException("Action definition is already retired: " + id);
        }

        actionDef.setStatus(ActionDefinitionStatus.RETIRED);
        actionDef = actionDefinitionRepository.save(actionDef);

        auditService.audit("ACTION_DEFINITION", "ACTION_DEFINITION_RETIRED", "system",
                "ActionDefinition", id.toString(),
                Map.of("canonicalUrl", actionDef.getCanonicalUrl(), "version", actionDef.getVersion()));

        log.info("Retired action definition: {} (id={})", actionDef.getCanonical(), id);

        return actionDef;
    }

    /**
     * Delete an ActionDefinition. Fails if any intelligence event references it.
     */
    public void deleteActionDefinition(UUID id) {
        ActionDefinition actionDef = findByIdOrThrow(id);

        if (intelligenceEventLogRepository.existsByActionDefinitionId(id)) {
            throw new IllegalStateException(
                    "Cannot delete action definition with existing intelligence events: " + id);
        }

        actionDefinitionRepository.delete(actionDef);

        log.info("Deleted action definition: {} (id={})", actionDef.getCanonical(), id);
    }

    @Transactional(readOnly = true)
    public ActionDefinition findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<ActionDefinition> findAll() {
        return actionDefinitionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ActionDefinition> findByStatus(ActionDefinitionStatus status) {
        return actionDefinitionRepository.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public List<ActionDefinition> findByCanonicalUrl(String canonicalUrl) {
        return actionDefinitionRepository.findByCanonicalUrl(canonicalUrl);
    }

    /**
     * Resolve an ActionDefinition by canonical reference (url|version).
     *
     * @param canonical the canonical reference in "url|version" format
     * @return the matching ActionDefinition
     * @throws IllegalArgumentException if the canonical format is invalid
     * @throws EntityNotFoundException if no matching ActionDefinition is found
     */
    @Transactional(readOnly = true)
    public ActionDefinition resolveByCanonical(String canonical) {
        int separatorIndex = canonical.lastIndexOf('|');
        if (separatorIndex <= 0 || separatorIndex >= canonical.length() - 1) {
            throw new IllegalArgumentException("Invalid canonical format, expected 'url|version': " + canonical);
        }

        String url = canonical.substring(0, separatorIndex);
        String version = canonical.substring(separatorIndex + 1);

        return actionDefinitionRepository.findByCanonicalUrlAndVersion(url, version)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Action definition not found for canonical: " + canonical));
    }

    private ActionDefinition findByIdOrThrow(UUID id) {
        return actionDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Action definition not found: " + id));
    }

    private ActionType extractActionType(JsonNode definition) {
        String kind = requireTextField(definition, "kind");
        try {
            return ActionType.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported ActivityDefinition.kind: " + kind);
        }
    }

    private <E extends Enum<E>> E extractExtensionEnum(JsonNode definition, String url, Class<E> enumClass) {
        JsonNode extensions = definition.get("extension");
        if (extensions == null || !extensions.isArray()) return null;

        for (JsonNode ext : extensions) {
            if (url.equals(ext.path("url").asText(null))) {
                String value = ext.path("valueCode").asText(null);
                if (value != null) {
                    try {
                        return Enum.valueOf(enumClass, value);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown extension value '{}' for {} in {}", value, url, enumClass.getSimpleName());
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private String requireTextField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Required field missing or empty: " + field);
        }
        return value.asText();
    }

    private String extractExtensionString(JsonNode definition, String url) {
        JsonNode extensions = definition.get("extension");
        if (extensions == null || !extensions.isArray()) return null;

        for (JsonNode ext : extensions) {
            if (url.equals(ext.path("url").asText(null))) {
                String value = ext.path("valueCode").asText(null);
                if (value != null) return value;
            }
        }
        return null;
    }

    private String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && value.isTextual()) ? value.asText() : null;
    }
}
