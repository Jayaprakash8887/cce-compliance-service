package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.service.DeviationService;
import org.openphc.cce.compliance.service.EventLogService;
import org.openphc.cce.compliance.service.ProtocolInstanceService;
import org.openphc.cce.compliance.service.StepInstanceService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {PatientTrackingController.class, DtoMapper.class, GlobalExceptionHandler.class})
class PatientTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProtocolInstanceService protocolInstanceService;

    @MockitoBean
    private StepInstanceService stepInstanceService;

    @MockitoBean
    private DeviationService deviationService;

    @MockitoBean
    private EventLogService eventLogService;

    private static final String PATIENT_ID = "patient-001";
    private static final UUID INSTANCE_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final UUID PROTOCOL_DEF_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID STEP_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440002");
    private static final UUID DEVIATION_ID = UUID.fromString("880e8400-e29b-41d4-a716-446655440003");

    private ProtocolDefinition buildProtocolDefinition() {
        ProtocolDefinition def = new ProtocolDefinition();
        def.setId(PROTOCOL_DEF_ID);
        def.setUrl("http://example.org/PlanDefinition/hiv-treatment");
        def.setVersion("1.0");
        def.setStatus(ProtocolDefinitionStatus.ACTIVE);
        def.setDefinition(objectMapper.createObjectNode());
        return def;
    }

    private ProtocolInstance buildProtocolInstance() {
        ProtocolInstance instance = new ProtocolInstance();
        instance.setId(INSTANCE_ID);
        instance.setPatientId(PATIENT_ID);
        instance.setProtocolCanonical("http://example.org/PlanDefinition/hiv-treatment|1.0");
        instance.setProtocolDefinition(buildProtocolDefinition());
        instance.setStatus(ProtocolInstanceStatus.ACTIVE);
        instance.setEnrolledAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setCreatedAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setUpdatedAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setSteps(new HashSet<>());
        instance.setDeviations(new HashSet<>());
        return instance;
    }

    private StepInstance buildStepInstance(ProtocolInstance instance) {
        StepInstance step = new StepInstance();
        step.setId(STEP_ID);
        step.setProtocolInstance(instance);
        step.setActionId("viral-load-check");
        step.setRepeatIndex(0);
        step.setState(StepState.PENDING);
        step.setDueDate(OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC));
        step.setRequiredBehavior("must");
        return step;
    }

    private Deviation buildDeviation(ProtocolInstance instance) {
        Deviation deviation = new Deviation();
        deviation.setId(DEVIATION_ID);
        deviation.setProtocolInstance(instance);
        deviation.setDeviationType(DeviationType.OVERDUE);
        deviation.setDetectedAt(OffsetDateTime.of(2026, 3, 25, 0, 0, 0, 0, ZoneOffset.UTC));
        deviation.setMetadata(objectMapper.createObjectNode().put("daysOverdue", 5));
        return deviation;
    }

    // --- GET /{patientId}/protocol-instances ---

    @Test
    void listProtocols_returnsInstances() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findByPatientId(PATIENT_ID)).thenReturn(List.of(instance));

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$[0].patientId").value(PATIENT_ID))
                .andExpect(jsonPath("$[0].steps").doesNotExist())
                .andExpect(jsonPath("$[0].deviations").doesNotExist());
    }

    @Test
    void listProtocols_emptyList_returns200() throws Exception {
        when(protocolInstanceService.findByPatientId(PATIENT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- GET /{patientId}/protocol-instances/active ---

    @Test
    void listActiveProtocols_returnsActiveOnly() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findActiveByPatientId(PATIENT_ID)).thenReturn(List.of(instance));

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/active", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    // --- GET /{patientId}/protocol-instances/{protocolInstanceId} ---

    @Test
    void getProtocolDetail_found_returns200() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findByIdWithDetails(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}",
                        PATIENT_ID, INSTANCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.deviations").isArray());
    }

    @Test
    void getProtocolDetail_notFound_returns404() throws Exception {
        when(protocolInstanceService.findByIdWithDetails(INSTANCE_ID))
                .thenThrow(new EntityNotFoundException("Protocol instance not found: " + INSTANCE_ID));

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}",
                        PATIENT_ID, INSTANCE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getProtocolDetail_wrongPatient_returns404() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findByIdWithDetails(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}",
                        "wrong-patient", INSTANCE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        "Protocol instance " + INSTANCE_ID + " not found for patient wrong-patient"));
    }

    // --- GET /{patientId}/protocol-instances/{protocolInstanceId}/steps ---

    @Test
    void listSteps_returnsSteps() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        StepInstance step = buildStepInstance(instance);
        when(protocolInstanceService.findById(INSTANCE_ID)).thenReturn(instance);
        when(stepInstanceService.findByProtocolInstanceId(INSTANCE_ID)).thenReturn(List.of(step));

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/steps",
                        PATIENT_ID, INSTANCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(STEP_ID.toString()))
                .andExpect(jsonPath("$[0].actionId").value("viral-load-check"))
                .andExpect(jsonPath("$[0].state").value("PENDING"));
    }

    @Test
    void listSteps_wrongPatient_returns404() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findById(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/steps",
                        "wrong-patient", INSTANCE_ID))
                .andExpect(status().isNotFound());
    }

    // --- GET /{patientId}/protocol-instances/{protocolInstanceId}/deviations ---

    @Test
    void listDeviations_returnsDeviations() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        Deviation deviation = buildDeviation(instance);
        when(protocolInstanceService.findById(INSTANCE_ID)).thenReturn(instance);
        when(deviationService.findByProtocolInstanceId(INSTANCE_ID)).thenReturn(List.of(deviation));

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/deviations",
                        PATIENT_ID, INSTANCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DEVIATION_ID.toString()))
                .andExpect(jsonPath("$[0].deviationType").value("OVERDUE"));
    }

    @Test
    void listDeviations_wrongPatient_returns404() throws Exception {
        ProtocolInstance instance = buildProtocolInstance();
        when(protocolInstanceService.findById(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(get("/v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/deviations",
                        "wrong-patient", INSTANCE_ID))
                .andExpect(status().isNotFound());
    }

    // --- GET /{patientId}/events ---

    @Test
    void listEvents_returnsPaginatedEvents() throws Exception {
        EventLog event = EventLog.builder()
                .id(UUID.randomUUID())
                .cloudeventsId("evt-001")
                .source("ebuzima")
                .subject(PATIENT_ID)
                .type("Observation")
                .eventTime(OffsetDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                .receivedAt(OffsetDateTime.of(2026, 3, 15, 10, 0, 1, 0, ZoneOffset.UTC))
                .processingStatus(ProcessingStatus.MATCHED)
                .data(objectMapper.createObjectNode())
                .correlationId("corr-001")
                .build();

        Page<EventLog> page = new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1);
        when(eventLogService.findByPatientId(eq(PATIENT_ID), any(PageRequest.class))).thenReturn(page);

        mockMvc.perform(get("/v1/patients/{patientId}/events", PATIENT_ID)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cloudeventsId").value("evt-001"))
                .andExpect(jsonPath("$.content[0].source").value("ebuzima"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listEvents_defaultPagination() throws Exception {
        Page<EventLog> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(eventLogService.findByPatientId(eq(PATIENT_ID), any(PageRequest.class))).thenReturn(page);

        mockMvc.perform(get("/v1/patients/{patientId}/events", PATIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
