package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluationServiceTest {

    private ExpressionEvaluationService service;

    @BeforeEach
    void setUp() {
        FhirContext fhirContext = FhirContext.forR4();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ExpressionEvaluationService(fhirContext, objectMapper);
    }

    // ── Null/empty expression ──

    @Test
    void evaluate_nullExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", null, Map.of()));
    }

    @Test
    void evaluate_emptyExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", "", Map.of()));
    }

    @Test
    void evaluate_blankExpression_returnsTrue() {
        assertTrue(service.evaluate("text/jsonlogic", "   ", Map.of()));
    }

    // ── Unsupported language ──

    @Test
    void evaluate_unsupportedLanguage_throwsException() {
        UnsupportedExpressionLanguageException ex = assertThrows(
                UnsupportedExpressionLanguageException.class,
                () -> service.evaluate("text/cql", "{}", Map.of())
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
            Map<String, Object> context = Map.of("event", Map.of("value", 15));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void numericGreaterThan_false() {
            String rule = """
                    {">":[{"var":"event.value"},10]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("value", 5));
            assertFalse(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void numericLessThan() {
            String rule = """
                    {"<":[{"var":"event.age"},65]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("age", 30));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void numericEquals() {
            String rule = """
                    {"==":[{"var":"event.status"},1]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("status", 1));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void stringEquals() {
            String rule = """
                    {"==":[{"var":"event.resourceType"},"Encounter"]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("resourceType", "Encounter"));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void stringEquals_mismatch() {
            String rule = """
                    {"==":[{"var":"event.resourceType"},"Observation"]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("resourceType", "Encounter"));
            assertFalse(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void nestedVarAccess() {
            String rule = """
                    {"==":[{"var":"event.code.coding.0.code"},"high-risk"]}
                    """;
            Map<String, Object> context = Map.of(
                    "event", Map.of(
                            "code", Map.of(
                                    "coding", java.util.List.of(
                                            Map.of("system", "http://example.org", "code", "high-risk")
                                    )
                            )
                    )
            );
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void andOperator() {
            String rule = """
                    {"and":[
                        {">":[{"var":"event.value"},10]},
                        {"<":[{"var":"event.value"},100]}
                    ]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("value", 50));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void andOperator_oneFails() {
            String rule = """
                    {"and":[
                        {">":[{"var":"event.value"},10]},
                        {"<":[{"var":"event.value"},100]}
                    ]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("value", 200));
            assertFalse(service.evaluate("text/jsonlogic", rule, context));
        }

        @Test
        void inOperator() {
            String rule = """
                    {"in":[{"var":"event.category"},["urgent","emergent"]]}
                    """;
            Map<String, Object> context = Map.of("event", Map.of("category", "urgent"));
            assertTrue(service.evaluate("text/jsonlogic", rule, context));
        }
    }

    // ── FHIRPath tests ──

    @Nested
    class FhirPathTests {

        @Test
        void evaluatesPatientResourcePath() {
            Map<String, Object> context = Map.of(
                    "event", Map.of(
                            "resourceType", "Patient",
                            "id", "patient-1",
                            "active", true
                    )
            );
            assertTrue(service.evaluate("text/fhirpath", "Patient.active", context));
        }

        @Test
        void evaluatesPatientResourcePath_false() {
            Map<String, Object> context = Map.of(
                    "event", Map.of(
                            "resourceType", "Patient",
                            "id", "patient-1",
                            "active", false
                    )
            );
            assertFalse(service.evaluate("text/fhirpath", "Patient.active", context));
        }

        @Test
        void evaluatesResourceTypeExists() {
            Map<String, Object> context = Map.of(
                    "event", Map.of(
                            "resourceType", "Encounter",
                            "id", "enc-1",
                            "status", "finished"
                    )
            );
            // Encounter.status.exists() → true
            assertTrue(service.evaluate("text/fhirpath", "Encounter.status.exists()", context));
        }

        @Test
        void noEventInContext_returnsFalse() {
            assertFalse(service.evaluate("text/fhirpath", "Patient.active", Map.of()));
        }
    }
}
