package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.AuditLog;
import org.openphc.cce.compliance.domain.repository.AuditLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService auditService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        auditService = new AuditService(auditLogRepository, objectMapper);
    }

    @Test
    void audit_persistsAllFields() {
        Map<String, Object> details = Map.of("actionCount", 6, "triggerIndexEntries", 7);

        auditService.audit("PROTOCOL_MANAGEMENT", "PROTOCOL_LOADED", "system",
                "ProtocolDefinition", "pd-001", details);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals("PROTOCOL_MANAGEMENT", saved.getEventCategory());
        assertEquals("PROTOCOL_LOADED", saved.getEventType());
        assertEquals("system", saved.getActor());
        assertEquals("ProtocolDefinition", saved.getResourceType());
        assertEquals("pd-001", saved.getResourceId());
        assertNotNull(saved.getTimestamp());

        JsonNode detailsNode = saved.getDetails();
        assertNotNull(detailsNode);
        assertEquals(6, detailsNode.get("actionCount").asInt());
        assertEquals(7, detailsNode.get("triggerIndexEntries").asInt());
    }

    @Test
    void audit_nullDetails_persistsWithoutDetails() {
        auditService.audit("COMPLIANCE", "STEP_COMPLETED", "system",
                "StepInstance", "step-001", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertNull(captor.getValue().getDetails());
    }

    @Test
    void audit_repositoryException_doesNotPropagate() {
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        // Should not throw — audit failures are swallowed
        assertDoesNotThrow(() -> auditService.audit(
                "COMPLIANCE", "DEVIATION_DETECTED", "system",
                "Deviation", "dev-001", Map.of("reason", "overdue")));

        verify(auditLogRepository).save(any());
    }

    @Test
    void audit_complianceCategory() {
        auditService.audit("COMPLIANCE", "PROTOCOL_ENROLLED", "system",
                "ProtocolInstance", "pi-001", Map.of("patientId", "patient-123"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("COMPLIANCE", captor.getValue().getEventCategory());
        assertEquals("PROTOCOL_ENROLLED", captor.getValue().getEventType());
    }

    @Test
    void audit_methodHasAsyncAnnotation() throws NoSuchMethodException {
        Method auditMethod = AuditService.class.getMethod("audit",
                String.class, String.class, String.class, String.class, String.class, Map.class);

        assertTrue(auditMethod.isAnnotationPresent(Async.class),
                "audit() must be annotated with @Async");
    }

    @Test
    void audit_methodHasRequiresNewTransactional() throws NoSuchMethodException {
        Method auditMethod = AuditService.class.getMethod("audit",
                String.class, String.class, String.class, String.class, String.class, Map.class);

        Transactional txAnnotation = auditMethod.getAnnotation(Transactional.class);
        assertNotNull(txAnnotation, "audit() must be annotated with @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, txAnnotation.propagation(),
                "audit() must use REQUIRES_NEW propagation");
    }
}
