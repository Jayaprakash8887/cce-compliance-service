package org.openphc.cce.compliance.web;

import ca.uhn.fhir.parser.DataFormatException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.fhir.UnsupportedExpressionLanguageException;
import org.openphc.cce.compliance.web.dto.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void handleEntityNotFound_returns404() {
        EntityNotFoundException ex = new EntityNotFoundException("Protocol not found with id: abc-123");

        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.getStatus());
        assertEquals("Not Found", body.getError());
        assertEquals("Protocol not found with id: abc-123", body.getMessage());
        assertNotNull(body.getTimestamp());
    }

    @Test
    void handleIllegalArgument_returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("URL must not be blank");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.getStatus());
        assertEquals("Bad Request", body.getError());
        assertEquals("URL must not be blank", body.getMessage());
    }

    @Test
    void handleValidation_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "url", "must not be blank"));
        bindingResult.addError(new FieldError("request", "version", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.getStatus());
        assertTrue(body.getMessage().contains("url: must not be blank"));
        assertTrue(body.getMessage().contains("version: must not be null"));
    }

    @Test
    void handleIllegalState_returns409() {
        IllegalStateException ex = new IllegalStateException("Cannot delete protocol with active instances");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.getStatus());
        assertEquals("Conflict", body.getError());
        assertEquals("Cannot delete protocol with active instances", body.getMessage());
    }

    @Test
    void handleUnsupportedExpression_returns422() {
        UnsupportedExpressionLanguageException ex = new UnsupportedExpressionLanguageException("text/cql");

        ResponseEntity<ErrorResponse> response = handler.handleUnsupportedExpression(ex);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(422, body.getStatus());
        assertEquals("Unprocessable Entity", body.getError());
        assertEquals("Unsupported expression language: text/cql", body.getMessage());
    }

    @Test
    void handleFhirValidation_returns422() {
        DataFormatException ex = new DataFormatException("Invalid FHIR resource: missing resourceType");

        ResponseEntity<ErrorResponse> response = handler.handleFhirValidation(ex);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(422, body.getStatus());
        assertEquals("Invalid FHIR resource: missing resourceType", body.getMessage());
    }

    @Test
    void handleGeneric_returns500WithGenericMessage() {
        RuntimeException ex = new RuntimeException("something broke internally");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(500, body.getStatus());
        assertEquals("Internal Server Error", body.getError());
        assertEquals("An unexpected error occurred", body.getMessage());
    }

    @Test
    void correlationId_propagatedWhenPresent() {
        MDC.put("correlationId", "corr-abc-456");
        EntityNotFoundException ex = new EntityNotFoundException("not found");

        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(ex);

        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("corr-abc-456", body.getCorrelationId());
    }

    @Test
    void correlationId_nullWhenNotInMdc() {
        EntityNotFoundException ex = new EntityNotFoundException("not found");

        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(ex);

        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertNull(body.getCorrelationId());
    }
}
