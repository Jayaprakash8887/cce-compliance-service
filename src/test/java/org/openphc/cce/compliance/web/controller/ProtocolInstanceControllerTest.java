package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.service.ProtocolInstanceService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {ProtocolInstanceController.class, DtoMapper.class, GlobalExceptionHandler.class})
class ProtocolInstanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProtocolInstanceService protocolInstanceService;

    private static final UUID INSTANCE_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final UUID PROTOCOL_DEF_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private ProtocolInstance buildProtocolInstance(ProtocolInstanceStatus status) {
        ProtocolDefinition protocolDef = new ProtocolDefinition();
        protocolDef.setId(PROTOCOL_DEF_ID);
        protocolDef.setUrl("http://example.org/PlanDefinition/hiv-treatment");
        protocolDef.setVersion("1.0");
        protocolDef.setStatus(ProtocolDefinitionStatus.ACTIVE);
        protocolDef.setDefinition(objectMapper.createObjectNode());

        ProtocolInstance instance = new ProtocolInstance();
        instance.setId(INSTANCE_ID);
        instance.setPatientId("patient-001");
        instance.setProtocolCanonical("http://example.org/PlanDefinition/hiv-treatment|1.0");
        instance.setProtocolDefinition(protocolDef);
        instance.setStatus(status);
        instance.setEnrolledAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setCreatedAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setUpdatedAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        instance.setSteps(new HashSet<>());
        instance.setDeviations(new HashSet<>());
        return instance;
    }

    // --- GET /{id} ---

    @Test
    void getById_found_returns200() throws Exception {
        ProtocolInstance instance = buildProtocolInstance(ProtocolInstanceStatus.ACTIVE);
        when(protocolInstanceService.findById(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(get("/v1/protocol-instances/{id}", INSTANCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$.patientId").value("patient-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.deviations").isArray());
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(protocolInstanceService.findById(INSTANCE_ID))
                .thenThrow(new EntityNotFoundException("Protocol instance not found: " + INSTANCE_ID));

        mockMvc.perform(get("/v1/protocol-instances/{id}", INSTANCE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Protocol instance not found: " + INSTANCE_ID));
    }

    // --- POST /{id}/withdraw ---

    @Test
    void withdraw_active_returns200() throws Exception {
        ProtocolInstance instance = buildProtocolInstance(ProtocolInstanceStatus.WITHDRAWN);
        when(protocolInstanceService.withdrawProtocol(INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(post("/v1/protocol-instances/{id}/withdraw", INSTANCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void withdraw_notActive_returns409() throws Exception {
        when(protocolInstanceService.withdrawProtocol(INSTANCE_ID))
                .thenThrow(new IllegalStateException(
                        "Cannot withdraw protocol instance in state COMPLETED: " + INSTANCE_ID));

        mockMvc.perform(post("/v1/protocol-instances/{id}/withdraw", INSTANCE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Cannot withdraw protocol instance in state COMPLETED: " + INSTANCE_ID));
    }

    @Test
    void withdraw_notFound_returns404() throws Exception {
        when(protocolInstanceService.withdrawProtocol(INSTANCE_ID))
                .thenThrow(new EntityNotFoundException("Protocol instance not found: " + INSTANCE_ID));

        mockMvc.perform(post("/v1/protocol-instances/{id}/withdraw", INSTANCE_ID))
                .andExpect(status().isNotFound());
    }
}
