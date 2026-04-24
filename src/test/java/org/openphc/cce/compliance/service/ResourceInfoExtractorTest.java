package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceInfoExtractorTest {

    private ResourceInfoExtractor extractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        extractor = new ResourceInfoExtractor();
    }

    private JsonNode toJsonNode(Object obj) {
        return objectMapper.valueToTree(obj);
    }

    // ── extractResourceType ──

    @Test
    void extractResourceType_encounter() {
        JsonNode data = toJsonNode(Map.of("resourceType", "Encounter"));
        assertEquals("Encounter", extractor.extractResourceType(data));
    }

    @Test
    void extractResourceType_observation() {
        JsonNode data = toJsonNode(Map.of("resourceType", "Observation"));
        assertEquals("Observation", extractor.extractResourceType(data));
    }

    @Test
    void extractResourceType_nullData_returnsNull() {
        assertNull(extractor.extractResourceType(null));
    }

    @Test
    void extractResourceType_missingField_returnsNull() {
        assertNull(extractor.extractResourceType(toJsonNode(Map.of())));
    }

    @Test
    void extractResourceType_nonStringValue_returnsNull() {
        assertNull(extractor.extractResourceType(toJsonNode(Map.of("resourceType", 123))));
    }

    // ── extractCodes — code.coding ──

    @Test
    void extractCodes_fromCodeCoding() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("code", "http://loinc.org", "85354-9"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCodings() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9"),
                                Map.of("system", "http://snomed.info/sct", "code", "271649006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
        assertEquals("code", codes.get(0).path());
        assertEquals("code", codes.get(1).path());
    }

    // ── extractCodes — type (array of CodeableConcepts in FHIR Encounter) ──

    @Test
    void extractCodes_fromTypeArray() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "type", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://openphc.org/encounter-types",
                                        "code", "VISIT_ENCOUNTER")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("type", "http://openphc.org/encounter-types", "VISIT_ENCOUNTER"))));
    }

    @Test
    void extractCodes_fromTypeSingleObject() {
        // Fallback: type as single CodeableConcept (some resource types)
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "type", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://snomed.info/sct", "code", "11429006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("type", "http://snomed.info/sct", "11429006"))));
    }

    // ── extractCodes — category[*].coding ──

    @Test
    void extractCodes_fromCategoryArrayCoding() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://terminology.hl7.org/CodeSystem/observation-category",
                                        "code", "vital-signs")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("category", "http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCategoryEntries() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys1.org", "code", "cat-1")
                        )),
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys2.org", "code", "cat-2")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
    }

    // ── extractCodes — combined paths ──

    @Test
    void extractCodes_encounterWithCodeAndType() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "LP173418-7")
                        )
                ),
                "type", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://snomed.info/sct", "code", "11429006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());

        assertTrue(codes.stream().anyMatch(c -> c.path().equals("code")));
        assertTrue(codes.stream().anyMatch(c -> c.path().equals("type")));
    }

    // ── extractCodes — edge cases ──

    @Test
    void extractCodes_nullData_returnsEmptyList() {
        assertTrue(extractor.extractCodes(null).isEmpty());
    }

    @Test
    void extractCodes_emptyData_returnsEmptyList() {
        assertTrue(extractor.extractCodes(toJsonNode(Map.of())).isEmpty());
    }

    @Test
    void extractCodes_missingCode_fallsBackToDisplay() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("display", "Blood Pressure") // no system or code — falls back to display
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals("code", codes.get(0).path());
        assertEquals("", codes.get(0).system());
        assertEquals("Blood Pressure", codes.get(0).code());
    }

    @Test
    void extractCodes_noCodingArray_returnsEmptyList() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of("text", "BP")
        ));
        assertTrue(extractor.extractCodes(data).stream().noneMatch(c -> c.path().equals("code")));
    }

    // ── extractCodes — clinicalStatus (Condition resource) ──

    @Test
    void extractCodes_fromClinicalStatus() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Condition",
                "clinicalStatus", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://terminology.hl7.org/CodeSystem/condition-clinical",
                                        "code", "active")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("clinicalStatus",
                        "http://terminology.hl7.org/CodeSystem/condition-clinical", "active"))));
    }

    // ── extractCodes — plain string status ──

    @Test
    void extractCodes_fromStringStatus() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "status", "in-progress"
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("status", "", "in-progress"))));
    }

    // ── extractCodes — coding without system ──

    @Test
    void extractCodes_codingWithoutSystem() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Procedure",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("code", "some-code")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("code", "", "some-code"))));
    }

    // ── CodePathTriple.toConcatenated ──

    @Test
    void codePathTriple_toConcatenated() {
        CodePathTriple triple = new CodePathTriple("code", "http://loinc.org", "85354-9");
        assertEquals("code|http://loinc.org|85354-9", triple.toConcatenated());
    }

    @Test
    void codePathTriple_toConcatenated_emptyFields() {
        CodePathTriple triple = new CodePathTriple("", "", "");
        assertEquals("||", triple.toConcatenated());
    }
}
