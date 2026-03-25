package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts resource type and coded values from FHIR resource payloads.
 * Used to prepare inputs for Tier 1 structural matching.
 */
@Component
public class ResourceInfoExtractor {

    /**
     * Extract the FHIR resource type from the event data payload.
     *
     * @param data the event payload (JsonNode representation of FHIR resource)
     * @return the resourceType string, or null if not present
     */
    public String extractResourceType(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        JsonNode resourceType = data.get("resourceType");
        return resourceType != null && resourceType.isTextual() ? resourceType.asText() : null;
    }

    /**
     * Extract coded values from standard FHIR paths: code.coding[*], type.coding[*],
     * category[*].coding[*]. Each code is returned with its FHIR path for matching
     * against trigger_index entries.
     *
     * @param data the event payload (JsonNode representation of FHIR resource)
     * @return list of CodePathTriple with path, system, and code
     */
    public List<CodePathTriple> extractCodes(JsonNode data) {
        List<CodePathTriple> result = new ArrayList<>();
        if (data == null || data.isNull()) {
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

    private void extractCodingsFromPath(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode codeableConcept = data.get(path);
        if (codeableConcept != null && codeableConcept.isObject()) {
            JsonNode codingList = codeableConcept.get("coding");
            if (codingList != null && codingList.isArray()) {
                for (JsonNode coding : codingList) {
                    if (coding.isObject()) {
                        addCodePathTriple(path, coding, result);
                    }
                }
            }
        }
    }

    private void extractCodingsFromArrayPath(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode array = data.get(path);
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item.isObject()) {
                    JsonNode codingList = item.get("coding");
                    if (codingList != null && codingList.isArray()) {
                        for (JsonNode coding : codingList) {
                            if (coding.isObject()) {
                                addCodePathTriple(path, coding, result);
                            }
                        }
                    }
                }
            }
        }
    }

    private void addCodePathTriple(String path, JsonNode coding, List<CodePathTriple> result) {
        JsonNode system = coding.get("system");
        JsonNode code = coding.get("code");
        if (system != null && system.isTextual() && code != null && code.isTextual()) {
            result.add(new CodePathTriple(path, system.asText(), code.asText()));
        }
    }
}
