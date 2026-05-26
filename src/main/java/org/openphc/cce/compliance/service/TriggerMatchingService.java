package org.openphc.cce.compliance.service;

import org.openphc.cce.compliance.domain.repository.TriggerIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 1 structural matching service. Uses GROUP BY + HAVING queries against the trigger_index
 * table to enforce AND semantics across multiple codeFilter entries. Also manages the in-memory
 * cache of condition-only triggers for Tier 2 evaluation.
 */
@Service
public class TriggerMatchingService {

    private static final Logger log = LoggerFactory.getLogger(TriggerMatchingService.class);

    private final TriggerIndexRepository triggerIndexRepository;

    /**
     * In-memory cache of condition-only triggers, keyed by protocolDefinitionId.
     * These have no data[] section and are evaluated via Tier 2 for every inbound event.
     */
    private final ConcurrentHashMap<UUID, List<ConditionOnlyTrigger>> conditionOnlyTriggerCache =
            new ConcurrentHashMap<>();

    public TriggerMatchingService(TriggerIndexRepository triggerIndexRepository) {
        this.triggerIndexRepository = triggerIndexRepository;
    }

    /**
     * Find structural matches using Tier 1 GROUP BY + HAVING query.
     * Matches actions where ALL codeFilter paths are satisfied (AND semantics).
     * Also matches resource-type-only triggers (F1 scenario) by including an empty-path triple.
     *
     * @param resourceType the FHIR resource type from the event payload
     * @param codes        the coded values extracted from the event payload
     * @return list of matched (protocolDefinitionId, actionId) pairs
     */
    public List<MatchedStep> findStructuralMatches(String resourceType, List<CodePathTriple> codes) {
        if (resourceType == null || resourceType.isBlank()) {
            return List.of();
        }

        // Build the concatenated triples for the JPQL IN clause
        List<String> codeTriples = new ArrayList<>();

        // Always include empty-path triple to match resource-type-only triggers (F1)
        codeTriples.add("||");

        // Add actual code triples from the event
        for (CodePathTriple triple : codes) {
            codeTriples.add(triple.toConcatenated());
        }

        List<Object[]> results = triggerIndexRepository.findStructuralMatches(resourceType, codeTriples);

        return results.stream()
                .map(row -> new MatchedStep((UUID) row[0], (String) row[1]))
                .toList();
    }

    /**
     * Returns all condition-only triggers across all protocol definitions.
     * These are evaluated via Tier 2 for every inbound event.
     */
    public List<ConditionOnlyTrigger> getConditionOnlyTriggers() {
        return conditionOnlyTriggerCache.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Register condition-only triggers for a protocol definition.
     * Called during protocol load.
     *
     * @param protocolDefinitionId the protocol definition UUID
     * @param triggers             the condition-only triggers to register
     */
    public void registerConditionOnlyTriggers(UUID protocolDefinitionId, List<ConditionOnlyTrigger> triggers) {
        if (triggers != null && !triggers.isEmpty()) {
            conditionOnlyTriggerCache.put(protocolDefinitionId, List.copyOf(triggers));
            log.info("Registered {} condition-only triggers for protocol {}", triggers.size(), protocolDefinitionId);
        }
    }

    /**
     * Remove condition-only triggers for a protocol definition.
     * Called during protocol retire/delete.
     *
     * @param protocolDefinitionId the protocol definition UUID to remove triggers for
     */
    public void removeConditionOnlyTriggers(UUID protocolDefinitionId) {
        List<ConditionOnlyTrigger> removed = conditionOnlyTriggerCache.remove(protocolDefinitionId);
        if (removed != null) {
            log.info("Removed {} condition-only triggers for protocol {}", removed.size(), protocolDefinitionId);
        }
    }
}
