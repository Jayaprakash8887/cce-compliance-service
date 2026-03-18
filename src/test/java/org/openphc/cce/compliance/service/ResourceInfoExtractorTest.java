package org.openphc.cce.compliance.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceInfoExtractorTest {

    private ResourceInfoExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ResourceInfoExtractor();
    }

    // ── extractResourceType ──

    @Test
    void extractResourceType_encounter() {
        Map<String, Object> data = Map.of("resourceType", "Encounter");
        assertEquals("Encounter", extractor.extractResourceType(data));
    }

    @Test
    void extractResourceType_observation() {
        Map<String, Object> data = Map.of("resourceType", "Observation");
        assertEquals("Observation", extractor.extractResourceType(data));
    }

    @Test
    void extractResourceType_nullData_returnsNull() {
        assertNull(extractor.extractResourceType(null));
    }

    @Test
    void extractResourceType_missingField_returnsNull() {
        assertNull(extractor.extractResourceType(Map.of()));
    }

    @Test
    void extractResourceType_nonStringValue_returnsNull() {
        assertNull(extractor.extractResourceType(Map.of("resourceType", 123)));
    }

    // ── extractCodes — code.coding ──

    @Test
    void extractCodes_fromCodeCoding() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9")
                        )
                )
        );
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("code", "http://loinc.org", "85354-9"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCodings() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9"),
                                Map.of("system", "http://snomed.info/sct", "code", "271649006")
                        )
                )
        );
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
        assertEquals("code", codes.get(0).path());
        assertEquals("code", codes.get(1).path());
    }

    // ── extractCodes — type.coding ──

    @Test
    void extractCodes_fromTypeCoding() {
        Map<String, Object> data = Map.of(
                "resourceType", "Encounter",
                "type", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://snomed.info/sct", "code", "11429006")
                        )
                )
        );
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("type", "http://snomed.info/sct", "11429006"), codes.get(0));
    }

    // ── extractCodes — category[*].coding ──

    @Test
    void extractCodes_fromCategoryArrayCoding() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://terminology.hl7.org/CodeSystem/observation-category",
                                        "code", "vital-signs")
                        ))
                )
        );
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("category", "http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCategoryEntries() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys1.org", "code", "cat-1")
                        )),
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys2.org", "code", "cat-2")
                        ))
                )
        );
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
    }

    // ── extractCodes — combined paths ──

    @Test
    void extractCodes_encounterWithCodeAndType() {
        Map<String, Object> data = Map.of(
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
        );
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
        assertTrue(extractor.extractCodes(Map.of()).isEmpty());
    }

    @Test
    void extractCodes_missingSystemOrCode_skipped() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("display", "Blood Pressure") // no system or code
                        )
                )
        );
        assertTrue(extractor.extractCodes(data).isEmpty());
    }

    @Test
    void extractCodes_noCodingArray_returnsEmptyList() {
        Map<String, Object> data = Map.of(
                "resourceType", "Observation",
                "code", Map.of("text", "BP")
        );
        assertTrue(extractor.extractCodes(data).isEmpty());
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
