package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.apache.johnzon.jsonlogic.JohnzonJsonLogic;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.BooleanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

@Component
public class ExpressionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluationService.class);

    private static final String LANGUAGE_JSONLOGIC = "text/jsonlogic";
    private static final String LANGUAGE_FHIRPATH = "text/fhirpath";

    private final JohnzonJsonLogic jsonLogic;
    private final IFhirPath fhirPath;
    private final IParser fhirJsonParser;
    private final ObjectMapper objectMapper;

    public ExpressionEvaluationService(FhirContext fhirContext, ObjectMapper objectMapper) {
        this.fhirPath = fhirContext.newFhirPath();
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.objectMapper = objectMapper;
        this.jsonLogic = new JohnzonJsonLogic();
    }

    /**
     * Evaluate an expression against the provided context.
     *
     * @param language   the expression language ("text/jsonlogic" or "text/fhirpath")
     * @param expression the expression string
     * @param context    context variables (event, patient, step, protocol)
     * @return true if the expression evaluates to truthy, or if expression is null/empty
     */
    public boolean evaluate(String language, String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }

        return switch (language) {
            case LANGUAGE_JSONLOGIC -> evaluateJsonLogic(expression, context);
            case LANGUAGE_FHIRPATH -> evaluateFhirPath(expression, context);
            default -> throw new UnsupportedExpressionLanguageException(language);
        };
    }

    private boolean evaluateJsonLogic(String expression, Map<String, Object> context) {
        JsonValue rule = parseJsonValue(expression);
        JsonObject data = toJsonObject(context);
        JsonValue result = jsonLogic.apply(rule, data);
        return jsonLogic.isTruthy(result);
    }

    private boolean evaluateFhirPath(String expression, Map<String, Object> context) {
        @SuppressWarnings("unchecked")
        Map<String, Object> eventData = (Map<String, Object>) context.get("event");
        if (eventData == null) {
            log.warn("FHIRPath evaluation requested but no 'event' key in context");
            return false;
        }

        String resourceJson;
        try {
            resourceJson = objectMapper.writeValueAsString(eventData);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event data for FHIRPath evaluation", e);
            return false;
        }

        IBase resource = fhirJsonParser.parseResource(resourceJson);
        List<IBase> results = fhirPath.evaluate(resource, expression, IBase.class);

        if (results.isEmpty()) {
            return false;
        }

        IBase first = results.get(0);
        if (first instanceof BooleanType booleanType) {
            return booleanType.booleanValue();
        }

        // Non-empty result list with non-boolean first element → truthy
        return true;
    }

    private JsonValue parseJsonValue(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readValue();
        }
    }

    private JsonObject toJsonObject(Map<String, Object> context) {
        String json;
        try {
            json = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize context to JSON", e);
        }
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
