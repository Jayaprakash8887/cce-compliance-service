package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;

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
    private IntelligenceTriggerProducer intelligenceTriggerProducer;

    @Mock
    private AuditService auditService;

    private DeviationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new DeviationService(deviationRepository, intelligenceTriggerProducer,
                auditService, objectMapper);
    }

    @Test
    void recordOverdueDeviation_persistsAndPublishes() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.OVERDUE);
        Map<String, Object> metadata = Map.of("transitionType", "DUE_TO_OVERDUE");

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Deviation result = service.recordDeviation(protocolInstance, step, DeviationType.OVERDUE, metadata);

        assertNotNull(result.getId());
        assertEquals(DeviationType.OVERDUE, result.getDeviationType());
        assertEquals(protocolInstance, result.getProtocolInstance());
        assertEquals(step, result.getStepInstance());
        assertNotNull(result.getDetectedAt());
        assertNotNull(result.getIntelligenceEventId());
        assertNotNull(result.getMetadata());

        // Verify deviation saved twice (initial + intelligenceEventId update)
        verify(deviationRepository, times(2)).save(any(Deviation.class));
    }

    @Test
    void recordMissedDeviation_publishesIntelligenceTrigger() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);
        Map<String, Object> metadata = Map.of("transitionType", "OVERDUE_TO_MISSED");

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        service.recordDeviation(protocolInstance, step, DeviationType.MISSED, metadata);

        ArgumentCaptor<IntelligenceTriggerEvent> captor = ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
        verify(intelligenceTriggerProducer).publishTrigger(captor.capture());

        IntelligenceTriggerEvent event = captor.getValue();
        assertNotNull(event.getId());
        assertEquals("cce.compliance.deviation.missed", event.getType());
        assertEquals(protocolInstance.getPatientId(), event.getSubject());
        assertEquals(protocolInstance.getId(), event.getProtocolInstanceId());
        assertEquals(step.getId(), event.getStepInstanceId());
        assertEquals("MISSED", event.getDeviationType());
        assertEquals(step.getState().name(), event.getStepState());
        assertEquals(step.getActionId(), event.getActionId());
        assertEquals(protocolInstance.getProtocolCanonical(), event.getProtocolCanonical());
        assertNotNull(event.getDetectedAt());
    }

    @Test
    void recordDeviation_auditsDeviationDetected() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        service.recordDeviation(protocolInstance, step, DeviationType.OVERDUE, null);

        verify(auditService).audit(eq("COMPLIANCE"), eq("DEVIATION_DETECTED"),
                eq("system"), eq("Deviation"), anyString(), anyMap());
    }

    @Test
    void recordDeviation_withNullMetadata_handlesGracefully() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Deviation result = service.recordDeviation(protocolInstance, step, DeviationType.OVERDUE, null);

        assertNotNull(result.getId());
        assertNull(result.getMetadata());

        ArgumentCaptor<IntelligenceTriggerEvent> captor = ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
        verify(intelligenceTriggerProducer).publishTrigger(captor.capture());
        assertNull(captor.getValue().getMetadata());
    }

    @Test
    void recordDeviation_storesIntelligenceEventIdOnDeviation() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Deviation result = service.recordDeviation(protocolInstance, step, DeviationType.OVERDUE,
                Map.of("key", "value"));

        // Verify the intelligenceEventId matches the event published
        ArgumentCaptor<IntelligenceTriggerEvent> eventCaptor = ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
        verify(intelligenceTriggerProducer).publishTrigger(eventCaptor.capture());

        assertEquals(eventCaptor.getValue().getId(), result.getIntelligenceEventId());
    }

    @Test
    void recordDeviation_eventContainsDeviationId() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, StepState.MISSED);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Deviation result = service.recordDeviation(protocolInstance, step, DeviationType.MISSED, null);

        ArgumentCaptor<IntelligenceTriggerEvent> captor = ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
        verify(intelligenceTriggerProducer).publishTrigger(captor.capture());

        assertEquals(result.getId(), captor.getValue().getDeviationId());
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
