package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProtocolInstanceServiceTest {

    @Mock
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Mock
    private AuditService auditService;

    private ProtocolInstanceService service;

    @BeforeEach
    void setUp() {
        service = new ProtocolInstanceService(protocolInstanceRepository, auditService);
    }

    @Nested
    class EnrollPatient {

        @Test
        void newPatient_createsActiveInstance() {
            ProtocolDefinition protocolDef = buildProtocolDefinition();
            OffsetDateTime enrolledAt = OffsetDateTime.now(ZoneOffset.UTC);

            when(protocolInstanceRepository.findByPatientIdAndProtocolDefinitionIdAndStatus(
                    "patient-1", protocolDef.getId(), ProtocolInstanceStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(protocolInstanceRepository.save(any(ProtocolInstance.class))).thenAnswer(invocation -> {
                ProtocolInstance pi = invocation.getArgument(0);
                pi.setId(UUID.randomUUID());
                return pi;
            });

            ProtocolInstance result = service.enrollPatient("patient-1", protocolDef, enrolledAt);

            assertNotNull(result);
            assertNotNull(result.getId());
            assertEquals("patient-1", result.getPatientId());
            assertEquals(ProtocolInstanceStatus.ACTIVE, result.getStatus());
            assertEquals(protocolDef.getCanonical(), result.getProtocolCanonical());
            assertEquals(enrolledAt, result.getEnrolledAt());

            verify(protocolInstanceRepository).save(any(ProtocolInstance.class));
            verify(auditService).audit(eq("COMPLIANCE"), eq("PROTOCOL_ENROLLED"),
                    eq("system"), eq("ProtocolInstance"), anyString(), anyMap());
        }

        @Test
        void existingActiveEnrollment_skipsReEnrollment() {
            ProtocolDefinition protocolDef = buildProtocolDefinition();
            ProtocolInstance existing = ProtocolInstance.builder()
                    .id(UUID.randomUUID())
                    .patientId("patient-1")
                    .protocolDefinition(protocolDef)
                    .status(ProtocolInstanceStatus.ACTIVE)
                    .build();

            when(protocolInstanceRepository.findByPatientIdAndProtocolDefinitionIdAndStatus(
                    "patient-1", protocolDef.getId(), ProtocolInstanceStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));

            ProtocolInstance result = service.enrollPatient("patient-1", protocolDef,
                    OffsetDateTime.now(ZoneOffset.UTC));

            assertSame(existing, result);
            verify(protocolInstanceRepository, never()).save(any());
            verify(auditService, never()).audit(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyMap());
        }
    }

    @Nested
    class CheckAndCompleteProtocol {

        @Test
        void allStepsTerminal_completesProtocol() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);
            instance.getSteps().add(buildStep(StepState.COMPLETED));
            instance.getSteps().add(buildStep(StepState.MISSED));
            instance.getSteps().add(buildStep(StepState.SKIPPED));

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(protocolInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.COMPLETED, instance.getStatus());
            verify(protocolInstanceRepository).save(instance);
        }

        @Test
        void someStepsNotTerminal_doesNotComplete() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);
            instance.getSteps().add(buildStep(StepState.COMPLETED));
            instance.getSteps().add(buildStep(StepState.PENDING));

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.ACTIVE, instance.getStatus());
            verify(protocolInstanceRepository, never()).save(any());
        }

        @Test
        void noSteps_doesNotComplete() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.ACTIVE, instance.getStatus());
            verify(protocolInstanceRepository, never()).save(any());
        }

        @Test
        void alreadyCompleted_skips() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.COMPLETED);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            service.checkAndCompleteProtocol(instanceId);

            verify(protocolInstanceRepository, never()).save(any());
        }
    }

    @Nested
    class WithdrawProtocol {

        @Test
        void activeInstance_withdrawsSuccessfully() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(protocolInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ProtocolInstance result = service.withdrawProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.WITHDRAWN, result.getStatus());
            verify(auditService).audit(eq("COMPLIANCE"), eq("PROTOCOL_WITHDRAWN"),
                    eq("system"), eq("ProtocolInstance"), eq(instanceId.toString()), anyMap());
        }

        @Test
        void completedInstance_throwsIllegalState() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.COMPLETED);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            assertThrows(IllegalStateException.class, () -> service.withdrawProtocol(instanceId));
            verify(protocolInstanceRepository, never()).save(any());
        }

        @Test
        void notFound_throwsEntityNotFound() {
            UUID instanceId = UUID.randomUUID();
            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.withdrawProtocol(instanceId));
        }
    }

    @Nested
    class ReadOperations {

        @Test
        void findById_existing_returnsInstance() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);
            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            ProtocolInstance result = service.findById(instanceId);
            assertEquals(instanceId, result.getId());
        }

        @Test
        void findById_notFound_throwsEntityNotFound() {
            UUID instanceId = UUID.randomUUID();
            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.findById(instanceId));
        }

        @Test
        void findByPatientId_delegatesToRepository() {
            when(protocolInstanceRepository.findByPatientId("patient-1")).thenReturn(List.of());
            List<ProtocolInstance> result = service.findByPatientId("patient-1");
            assertTrue(result.isEmpty());
            verify(protocolInstanceRepository).findByPatientId("patient-1");
        }

        @Test
        void findActiveByPatientId_delegatesToRepository() {
            when(protocolInstanceRepository.findByPatientIdAndStatus("patient-1", ProtocolInstanceStatus.ACTIVE))
                    .thenReturn(List.of());
            List<ProtocolInstance> result = service.findActiveByPatientId("patient-1");
            assertTrue(result.isEmpty());
        }
    }

    // ── Helpers ──

    private ProtocolDefinition buildProtocolDefinition() {
        return ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .build();
    }

    private ProtocolInstance buildProtocolInstance(UUID id, ProtocolInstanceStatus status) {
        return ProtocolInstance.builder()
                .id(id)
                .patientId("patient-1")
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .status(status)
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();
    }

    private StepInstance buildStep(StepState state) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("action-" + UUID.randomUUID().toString().substring(0, 4))
                .state(state)
                .build();
    }
}
