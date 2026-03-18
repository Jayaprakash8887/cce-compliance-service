package org.openphc.cce.compliance.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts resource type and coded values from FHIR resource payloads.
 * Used to prepare inputs for Tier 1 structural matching.
 */
@Component
public class ResourceInfoExtractor {

    /**
     * Extract the FHIR resource type from the event data payload.
     *
     * @param data the event payload (Map representation of FHIR resource)
     * @return the resourceType string, or null if not present
     */
    public String extractResourceType(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object resourceType = data.get("resourceType");
        return resourceType instanceof String s ? s : null;
    }

    /**
     * Extract coded values from standard FHIR paths: code.coding[*], type.coding[*],
     * category[*].coding[*]. Each code is returned with its FHIR path for matching
     * against trigger_index entries.
     *
     * @param data the event payload (Map representation of FHIR resource)
     * @return list of CodePathTriple with path, system, and code
     */
    public List<CodePathTriple> extractCodes(Map<String, Object> data) {
        List<CodePathTriple> result = new ArrayList<>();
        if (data == null) {
            return result;
        }

        // Extract from data.code.coding[*]
        extractCodingsFromPath(data, "code", result);

        // Extract from data.type.coding[*]
        extractCodingsFromPath(data, "type", result);

        // Extract from data.category[*].coding[*]
        extractCodingsFromArrayPath(data, "category", result);

        return result;
    }

    @SuppressWarnings("unchecked")
    private void extractCodingsFromPath(Map<String, Object> data, String path, List<CodePathTriple> result) {
        Object codeableConcept = data.get(path);
        if (codeableConcept instanceof Map<?, ?> cc) {
            Object codingList = cc.get("coding");
            if (codingList instanceof List<?> codings) {
                for (Object coding : codings) {
                    if (coding instanceof Map<?, ?> c) {
                        addCodePathTriple(path, (Map<String, Object>) c, result);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void extractCodingsFromArrayPath(Map<String, Object> data, String path, List<CodePathTriple> result) {
        Object array = data.get(path);
        if (array instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> cc) {
                    Object codingList = cc.get("coding");
                    if (codingList instanceof List<?> codings) {
                        for (Object coding : codings) {
                            if (coding instanceof Map<?, ?> c) {
                                addCodePathTriple(path, (Map<String, Object>) c, result);
                            }
                        }
                    }
                }
            }
        }
    }

    private void addCodePathTriple(String path, Map<String, Object> coding, List<CodePathTriple> result) {
        Object system = coding.get("system");
        Object code = coding.get("code");
        if (system instanceof String s && code instanceof String c) {
            result.add(new CodePathTriple(path, s, c));
        }
    }
}
