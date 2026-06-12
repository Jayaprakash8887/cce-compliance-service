package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluationServiceTest {

    private ExpressionEvaluationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        FhirContext fhirContext = FhirContext.forR4();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ExpressionEvaluationService(fhirContext, objectMapper);
    }

    private JsonNode toJsonNode(Object obj) {
        return objectMapper.valueToTree(obj);
    }

    // ── Null/empty expression ──

    @Test
    void evaluate_nullExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", null, toJsonNode(Map.of())));
    }

    @Test
    void evaluate_emptyExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", "", toJsonNode(Map.of())));
    }

    @Test
    void evaluate_blankExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", "   ", toJsonNode(Map.of())));
    }

    // ── Unsupported language ──

    @Test
    void evaluate_unsupportedLanguage_throwsException() {
        UnsupportedExpressionLanguageException ex = assertThrows(
                UnsupportedExpressionLanguageException.class,
                () -> service.evaluate("text/cql", "{}", toJsonNode(Map.of()))
        );
        assertEquals("text/cql", ex.getLanguage());
        assertTrue(ex.getMessage().contains("text/cql"));
    }

    // ── JSONLogic tests ──

    @Nested
    class JsonLogicTests {

        @Test
        void numericGreaterThan_true() {
            String rule = """
                    {">":[{"var":"event.value"},10]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("value", 15));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void numericGreaterThan_false() {
            String rule = """
                    {">":[{"var":"event.value"},10]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("value", 5));
            assertFalse(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void numericLessThan() {
            String rule = """
                    {"<":[{"var":"event.age"},65]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("age", 30));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void numericEquals() {
            String rule = """
                    {"==":[{"var":"event.status"},1]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("status", 1));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void stringEquals() {
            String rule = """
                    {"==":[{"var":"event.resourceType"},"Encounter"]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("resourceType", "Encounter"));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void stringEquals_mismatch() {
            String rule = """
                    {"==":[{"var":"event.resourceType"},"Observation"]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("resourceType", "Encounter"));
            assertFalse(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void nestedVarAccess() {
            String rule = """
                    {"==":[{"var":"event.code.coding.0.code"},"high-risk"]}
                    """;
            JsonNode eventData = toJsonNode(Map.of(
                    "code", Map.of(
                            "coding", List.of(
                                    Map.of("system", "http://example.org", "code", "high-risk")
                            )
                    )
            ));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void andOperator() {
            String rule = """
                    {"and":[
                        {">":[{"var":"event.value"},10]},
                        {"<":[{"var":"event.value"},100]}
                    ]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("value", 50));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void andOperator_oneFails() {
            String rule = """
                    {"and":[
                        {">":[{"var":"event.value"},10]},
                        {"<":[{"var":"event.value"},100]}
                    ]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("value", 200));
            assertFalse(service.evaluate("text/jsonlogic", rule, eventData));
        }

        @Test
        void inOperator() {
            String rule = """
                    {"in":[{"var":"event.category"},["urgent","emergent"]]}
                    """;
            JsonNode eventData = toJsonNode(Map.of("category", "urgent"));
            assertTrue(service.evaluate("text/jsonlogic", rule, eventData));
        }
    }

    // ── FHIRPath tests ──

    @Nested
    class FhirPathTests {

        @Test
        void evaluatesPatientResourcePath() {
            JsonNode eventData = toJsonNode(Map.of(
                    "resourceType", "Patient",
                    "id", "patient-1",
                    "active", true
            ));
            assertTrue(service.evaluate("text/fhirpath", "Patient.active", eventData));
        }

        @Test
        void evaluatesPatientResourcePath_false() {
            JsonNode eventData = toJsonNode(Map.of(
                    "resourceType", "Patient",
                    "id", "patient-1",
                    "active", false
            ));
            assertFalse(service.evaluate("text/fhirpath", "Patient.active", eventData));
        }

        @Test
        void evaluatesResourceTypeExists() {
            JsonNode eventData = toJsonNode(Map.of(
                    "resourceType", "Encounter",
                    "id", "enc-1",
                    "status", "finished"
            ));
            // Encounter.status.exists() → true
            assertTrue(service.evaluate("text/fhirpath", "Encounter.status.exists()", eventData));
        }

        @Test
        void noEventInContext_returnsFalse() {
            assertFalse(service.evaluate("text/fhirpath", "Patient.active", null));
        }
    }
}
