package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Error- and edge-path unit tests for {@link ExpressionEvaluationService} — the fail-soft branches
 * (evaluation errors resolve to {@code false}) and the non-boolean FHIRPath truthiness rule. These
 * complement the happy-path cases in {@link ExpressionEvaluationServiceTest}.
 */
class ExpressionEvaluationServiceErrorPathsTest {

    private static final String PATIENT_JSON =
            "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"gender\":\"female\"}";

    private FhirContext fhirContext;
    private ObjectMapper objectMapper;
    private ExpressionEvaluationService service;

    @BeforeEach
    void setUp() {
        fhirContext = FhirContext.forR4();
        objectMapper = new ObjectMapper();
        service = new ExpressionEvaluationService(fhirContext, objectMapper);
    }

    private JsonNode patient() {
        try {
            return objectMapper.readTree(PATIENT_JSON);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void jsonLogic_whenExpressionIsInvalidJson_returnsFalse() {
        // parseJsonValue throws inside computeIfAbsent → swallowed → false.
        assertFalse(service.evaluate("text/jsonlogic", "not-valid-json {", objectMapper.valueToTree(Map.of())));
    }

    @Test
    void jsonLogic_whenEventSerializationFails_returnsFalse() {
        // A mock ObjectMapper whose writeValueAsString fails forces toJsonObject's guard.
        ObjectMapper failing = mock(ObjectMapper.class);
        try {
            when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ExpressionEvaluationService svc = new ExpressionEvaluationService(fhirContext, failing);

        assertFalse(svc.evaluate("text/jsonlogic", "{\"==\":[1,1]}", objectMapper.valueToTree(Map.of())));
    }

    @Test
    void fhirPath_whenResultEmpty_returnsFalse() {
        // birthDate is absent on the patient → empty result list → false.
        assertFalse(service.evaluate("text/fhirpath", "Patient.birthDate", patient()));
    }

    @Test
    void fhirPath_whenNonBooleanResult_returnsTrue() {
        // gender resolves to a non-empty, non-boolean element → treated as truthy.
        assertTrue(service.evaluate("text/fhirpath", "Patient.gender", patient()));
    }

    @Test
    void fhirPath_whenExpressionInvalid_returnsFalse() {
        assertFalse(service.evaluate("text/fhirpath", "@@@ not valid fhirpath (((", patient()));
    }
}
