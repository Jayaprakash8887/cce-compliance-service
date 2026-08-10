package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviationServiceTest {

    @Mock
    private DeviationRepository deviationRepository;

    @Mock
    private AuditService auditService;

    private DeviationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new DeviationService(deviationRepository, auditService, objectMapper);
    }

    @Test
    void createMissedDeviation_persistsCorrectly() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);
        step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationService.DeviationResult result = service.createDeviation(step, DeviationType.MISSED);

        assertTrue(result.created(), "A newly inserted deviation should signal created=true");
        assertNotNull(result.deviation().getId());
        assertEquals(DeviationType.MISSED, result.deviation().getDeviationType());
        assertEquals(protocolInstance, result.deviation().getProtocolInstance());
        assertEquals(step, result.deviation().getStepInstance());
        assertNotNull(result.deviation().getDetectedAt());
        // Enriched with daysPastMissedDate.
        assertNotNull(result.deviation().getMetadata());

        verify(deviationRepository).save(any(Deviation.class));
    }

    @Test
    void createDeviation_auditsDeviationDetected() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        service.createDeviation(step, DeviationType.MISSED);

        verify(auditService).audit(eq("COMPLIANCE"), eq("DEVIATION_DETECTED"),
                eq("system"), eq("Deviation"), anyString(), anyMap());
    }

    @Test
    void createDeviation_withNoEnrichableMetadata_handlesGracefully() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);
        step.setMissedDate(null);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationService.DeviationResult result = service.createDeviation(step, DeviationType.MISSED);

        assertNotNull(result.deviation().getId());
        assertNull(result.deviation().getMetadata());
    }

    @Test
    void createDeviation_withAdditionalMetadata_mergesMetadata() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Map<String, Object> additional = Map.of("incompletePrerequisites", java.util.List.of("step-a"));
        DeviationService.DeviationResult result = service.createDeviation(step, DeviationType.ORDER_VIOLATION, additional);

        assertNotNull(result.deviation().getId());
        assertNotNull(result.deviation().getMetadata());
    }

    @Test
    void createDeviation_whenSameTypeAlreadyExists_returnsExistingWithoutInserting() {
        // Idempotency: a redelivered / concurrent trigger must not create a second
        // deviation of the same type for the same step.
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);

        Deviation existing = Deviation.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .stepInstance(step)
                .deviationType(DeviationType.MISSED)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(deviationRepository.findByStepInstanceIdAndDeviationType(step.getId(), DeviationType.MISSED))
                .thenReturn(java.util.Optional.of(existing));

        DeviationService.DeviationResult result = service.createDeviation(step, DeviationType.MISSED);

        assertFalse(result.created(), "Should signal the deviation already existed");
        assertSame(existing, result.deviation(), "Should return the pre-existing deviation");
        verify(deviationRepository, never()).save(any(Deviation.class));
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void createDeviation_doesNotPublishIntelligenceTrigger() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationService.DeviationResult result = service.createDeviation(step, DeviationType.MISSED);

        // Intelligence trigger publishing is deferred to a future phase
        assertNull(result.deviation().getIntelligenceEventId());
    }

    // --- Helpers ---

    private ProtocolInstance buildProtocolInstance() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .build();

        ProtocolInstance instance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .protocolDefinition(protocolDef)
                .patientId("patient-123")
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        return instance;
    }

    private StepInstance buildStep(ProtocolInstance protocolInstance, StepState state) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId("bp-check")
                .repeatIndex(0)
                .state(state)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2))
                .build();
    }
}
