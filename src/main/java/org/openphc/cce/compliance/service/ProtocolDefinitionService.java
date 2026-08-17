package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;
import org.openphc.cce.compliance.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.TriggerIndexRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ProtocolDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDefinitionService.class);

    private final ProtocolDefinitionRepository protocolDefinitionRepository;
    private final ProtocolInstanceRepository protocolInstanceRepository;
    private final TriggerIndexRepository triggerIndexRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final TriggerMatchingService triggerMatchingService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProtocolDefinitionService(ProtocolDefinitionRepository protocolDefinitionRepository,
                                     ProtocolInstanceRepository protocolInstanceRepository,
                                     TriggerIndexRepository triggerIndexRepository,
                                     PlanDefinitionParser planDefinitionParser,
                                     TriggerMatchingService triggerMatchingService,
                                     AuditService auditService,
                                     ObjectMapper objectMapper) {
        this.protocolDefinitionRepository = protocolDefinitionRepository;
        this.protocolInstanceRepository = protocolInstanceRepository;
        this.triggerIndexRepository = triggerIndexRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.triggerMatchingService = triggerMatchingService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Load a PlanDefinition JSON, parse it, persist the protocol definition,
     * build trigger index entries, and register condition-only triggers.
     *
     * @param planDefinitionJson the raw FHIR PlanDefinition JSON
     * @return the persisted ProtocolDefinition entity
     * @throws IllegalArgumentException if the JSON is invalid or a duplicate (url, version) exists
     */
    public ProtocolDefinition loadProtocol(String planDefinitionJson) {
        // Parse and validate
        PlanDefinition planDefinition = planDefinitionParser.parse(planDefinitionJson);
        planDefinitionParser.validateActionIds(planDefinition);
        planDefinitionParser.validateActionTypes(planDefinition);
        planDefinitionParser.validateTriggers(planDefinition);
        warnOnInertRelatedActions(planDefinition);

        String url = planDefinition.getUrl();
        String version = planDefinition.getVersion();

        // Check for duplicate (url, version)
        if (protocolDefinitionRepository.findByUrlAndVersion(url, version).isPresent()) {
            throw new IllegalArgumentException(
                    "Protocol definition already exists for url=" + url + ", version=" + version);
        }

        // Persist the protocol definition
        JsonNode definitionNode;
        try {
            definitionNode = objectMapper.readTree(planDefinitionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse PlanDefinition JSON for storage", e);
        }

        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .url(url)
                .version(version)
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(definitionNode)
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        protocolDef = protocolDefinitionRepository.save(protocolDef);
        UUID protocolDefId = protocolDef.getId();

        // Build and persist trigger index entries
        List<TriggerIndex> indexEntries = planDefinitionParser.buildTriggerIndexEntries(planDefinition, protocolDefId);
        triggerIndexRepository.saveAll(indexEntries);

        // Register condition-only triggers in in-memory cache
        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> conditionOnlyInfos =
                planDefinitionParser.extractConditionOnlyTriggers(planDefinition);
        if (!conditionOnlyInfos.isEmpty()) {
            List<ConditionOnlyTrigger> conditionOnlyTriggers = conditionOnlyInfos.stream()
                    .map(info -> new ConditionOnlyTrigger(protocolDefId, info.actionId(),
                            info.conditionLanguage(), info.conditionExpression()))
                    .toList();
            triggerMatchingService.registerConditionOnlyTriggers(protocolDefId, conditionOnlyTriggers);
        }

        // Audit
        int actionCount = planDefinition.getAction().size();
        auditService.audit("PROTOCOL_MANAGEMENT", "PROTOCOL_LOADED", "system",
                "ProtocolDefinition", protocolDefId.toString(),
                Map.of("url", url, "version", version,
                        "actionCount", actionCount,
                        "triggerIndexEntries", indexEntries.size(),
                        "conditionOnlyTriggers", conditionOnlyInfos.size()));

        log.info("Loaded protocol definition: {} (id={}, actions={}, indexEntries={}, conditionOnly={})",
                protocolDef.getCanonical(), protocolDefId, actionCount, indexEntries.size(), conditionOnlyInfos.size());

        return protocolDef;
    }

    /**
     * Retire a protocol definition. Sets status to RETIRED, deletes trigger index entries,
     * and removes condition-only triggers. Existing enrollments are not affected.
     */
    public ProtocolDefinition retireProtocol(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        if (protocolDef.getStatus() == ProtocolDefinitionStatus.RETIRED) {
            throw new IllegalStateException("Protocol definition is already retired: " + id);
        }

        protocolDef.setStatus(ProtocolDefinitionStatus.RETIRED);
        protocolDef = protocolDefinitionRepository.save(protocolDef);

        // Delete trigger index entries
        triggerIndexRepository.deleteByProtocolDefinitionId(id);

        // Remove condition-only triggers from in-memory cache
        triggerMatchingService.removeConditionOnlyTriggers(id);

        auditService.audit("PROTOCOL_MANAGEMENT", "PROTOCOL_RETIRED", "system",
                "ProtocolDefinition", id.toString(),
                Map.of("url", protocolDef.getUrl(), "version", protocolDef.getVersion()));

        log.info("Retired protocol definition: {} (id={})", protocolDef.getCanonical(), id);

        return protocolDef;
    }

    /**
     * Rebuild the trigger index for a protocol definition from its stored definition JSONB.
     */
    public void rebuildIndex(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        // Delete existing entries
        triggerIndexRepository.deleteByProtocolDefinitionId(id);
        triggerMatchingService.removeConditionOnlyTriggers(id);

        // Re-parse the stored definition
        PlanDefinition planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());

        // Rebuild trigger index
        List<TriggerIndex> indexEntries = planDefinitionParser.buildTriggerIndexEntries(planDefinition, id);
        triggerIndexRepository.saveAll(indexEntries);

        // Re-register condition-only triggers
        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> conditionOnlyInfos =
                planDefinitionParser.extractConditionOnlyTriggers(planDefinition);
        if (!conditionOnlyInfos.isEmpty()) {
            List<ConditionOnlyTrigger> conditionOnlyTriggers = conditionOnlyInfos.stream()
                    .map(info -> new ConditionOnlyTrigger(id, info.actionId(),
                            info.conditionLanguage(), info.conditionExpression()))
                    .toList();
            triggerMatchingService.registerConditionOnlyTriggers(id, conditionOnlyTriggers);
        }

        log.info("Rebuilt trigger index for protocol definition: {} (id={}, indexEntries={}, conditionOnly={})",
                protocolDef.getCanonical(), id, indexEntries.size(), conditionOnlyInfos.size());
    }

    /**
     * Delete a protocol definition. Fails if any ProtocolInstance exists for it.
     */
    public void deleteProtocol(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        if (protocolInstanceRepository.existsByProtocolDefinitionId(id)) {
            throw new IllegalStateException(
                    "Cannot delete protocol definition with existing instances: " + id);
        }

        // Clean up trigger index and condition-only triggers
        triggerIndexRepository.deleteByProtocolDefinitionId(id);
        triggerMatchingService.removeConditionOnlyTriggers(id);

        protocolDefinitionRepository.delete(protocolDef);

        log.info("Deleted protocol definition: {} (id={})", protocolDef.getCanonical(), id);
    }

    @Transactional(readOnly = true)
    public ProtocolDefinition findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<ProtocolDefinition> findAll() {
        return protocolDefinitionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<ProtocolDefinition> findAll(Pageable pageable) {
        return protocolDefinitionRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<ProtocolDefinition> findByUrl(String url) {
        return protocolDefinitionRepository.findByUrl(url);
    }

    @Transactional(readOnly = true)
    public Optional<ProtocolDefinition> findByUrlAndVersion(String url, String version) {
        return protocolDefinitionRepository.findByUrlAndVersion(url, version);
    }

    private ProtocolDefinition findByIdOrThrow(UUID id) {
        return protocolDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol definition not found: " + id));
    }

    /**
     * Warn about {@code relatedAction} entries that establish no step ordering, so a protocol that
     * expects one to sequence its steps is flagged at load time rather than quietly producing a
     * disconnected graph — no progressive instantiation, no ORDER_VIOLATION, no backfill along that
     * edge, and no error to explain why.
     *
     * <p>Warnings rather than rejections: both shapes were accepted (and equally inert) before, so
     * failing the load would break an upstream publisher for something already in the wild.
     */
    private void warnOnInertRelatedActions(PlanDefinition planDefinition) {
        List<PlanDefinitionParser.StepMetadata> steps = planDefinitionParser.extractSteps(planDefinition);

        for (String dangling : PlanDefinitionParser.findDanglingRelatedActions(steps)) {
            log.warn("Protocol {} has a relatedAction naming an action that does not exist — it is "
                            + "ignored and establishes no ordering: {}",
                    planDefinition.getUrl(), dangling);
        }

        for (String unordered : PlanDefinitionParser.findUnorderedRelationships(steps)) {
            log.warn("Protocol {} has a concurrent-* relatedAction, which states no ordering — it "
                            + "will NOT sequence these steps. Use after-*/before-* if one must "
                            + "follow the other: {}",
                    planDefinition.getUrl(), unordered);
        }
    }
}
