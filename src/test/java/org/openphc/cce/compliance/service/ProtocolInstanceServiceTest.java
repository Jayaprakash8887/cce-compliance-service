package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
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
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private StepInstanceRepository stepInstanceRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private StateTransitionHistoryService stateTransitionHistoryService;

    @Mock
    private PlanDefinitionParser planDefinitionParser;

    private ProtocolInstanceService service;

    @BeforeEach
    void setUp() {
        service = new ProtocolInstanceService(protocolInstanceRepository, stepInstanceRepository,
                auditService, stateTransitionHistoryService, planDefinitionParser);
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
            // The initial ACTIVE status is recorded in append-only history at the enrollment time.
            verify(stateTransitionHistoryService).recordProtocolInstanceTransition(result, enrolledAt);
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
            verify(stateTransitionHistoryService, never()).recordProtocolInstanceTransition(any(), any());
        }
    }

    @Nested
    class CheckAndCompleteProtocol {

        // Graph: visit-encounter(could) -> vitals-recording(must) -> consultation(must) -> chief-complaints(could)
        private List<PlanDefinitionParser.StepMetadata> emrGraph() {
            return List.of(
                    step("visit-encounter", "could", "vitals-recording"),
                    step("vitals-recording", "must", "consultation"),
                    step("consultation", "must", "chief-complaints"),
                    step("chief-complaints", "could"));
        }

        private void stubGraph(List<PlanDefinitionParser.StepMetadata> steps) {
            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(steps);
        }

        @Test
        void allMandatoryStepsTerminal_completesProtocol() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of(
                    buildStep("visit-encounter", StepState.COMPLETED),
                    buildStep("vitals-recording", StepState.COMPLETED),
                    buildStep("consultation", StepState.COMPLETED)));
            stubGraph(emrGraph());
            when(protocolInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.COMPLETED, instance.getStatus());
            verify(protocolInstanceRepository).save(instance);
            // The COMPLETED transition is recorded in append-only history.
            verify(stateTransitionHistoryService).recordProtocolInstanceTransition(eq(instance), any(OffsetDateTime.class));
        }

        @Test
        void loneConsultation_mandatoryPredecessorMissing_doesNotComplete() {
            // Regression: the emr-service-protocol bug — a consultation step entered directly
            // via its own trigger, with vitals-recording (a mandatory predecessor) never
            // instantiated, must NOT mark the protocol COMPLETED.
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of(
                    buildStep("consultation", StepState.COMPLETED)));
            stubGraph(emrGraph());

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.ACTIVE, instance.getStatus());
            verify(protocolInstanceRepository, never()).save(any());
            verify(stateTransitionHistoryService, never()).recordProtocolInstanceTransition(any(), any());
        }

        @Test
        void actionableStepPresent_doesNotComplete() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of(
                    buildStep("vitals-recording", StepState.COMPLETED),
                    buildStep("consultation", StepState.DUE)));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.ACTIVE, instance.getStatus());
            verify(protocolInstanceRepository, never()).save(any());
            // early-out before parsing the definition
            verify(planDefinitionParser, never()).parse(anyString());
        }

        @Test
        void noMandatorySteps_allTerminal_completes() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of(
                    buildStep("chief-complaints", StepState.COMPLETED)));
            stubGraph(List.of(step("chief-complaints", "could")));
            when(protocolInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.COMPLETED, instance.getStatus());
            verify(protocolInstanceRepository).save(instance);
        }

        @Test
        void noSteps_doesNotComplete() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of());

            service.checkAndCompleteProtocol(instanceId);

            assertEquals(ProtocolInstanceStatus.ACTIVE, instance.getStatus());
            verify(protocolInstanceRepository, never()).save(any());
            verify(stateTransitionHistoryService, never()).recordProtocolInstanceTransition(any(), any());
        }

        @Test
        void alreadyCompleted_skips() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.COMPLETED);

            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            service.checkAndCompleteProtocol(instanceId);

            verify(protocolInstanceRepository, never()).save(any());
            verify(stateTransitionHistoryService, never()).recordProtocolInstanceTransition(any(), any());
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
    }

    // ── Helpers ──

    private ProtocolDefinition buildProtocolDefinition() {
        return ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(JsonNodeFactory.instance.objectNode())
                .build();
    }

    private ProtocolInstance buildProtocolInstance(UUID id, ProtocolInstanceStatus status) {
        return ProtocolInstance.builder()
                .id(id)
                .patientId("patient-1")
                .protocolDefinition(buildProtocolDefinition())
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .status(status)
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();
    }

    private StepInstance buildStep(String actionId, StepState state) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId(actionId)
                .state(state)
                .build();
    }

    private PlanDefinitionParser.StepMetadata step(String id, String requiredBehavior,
                                                   String... relatedActionIds) {
        List<PlanDefinitionParser.RelatedStepInfo> related = new java.util.ArrayList<>();
        for (String target : relatedActionIds) {
            related.add(new PlanDefinitionParser.RelatedStepInfo(target, "after-end", BigDecimal.ZERO, "d"));
        }
        return new PlanDefinitionParser.StepMetadata(
                id, id, List.of(), related, null, null, requiredBehavior, List.of());
    }
}
