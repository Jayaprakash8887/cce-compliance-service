package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;
import org.openphc.cce.compliance.service.ProtocolDefinitionService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {ProtocolDefinitionController.class, DtoMapper.class, GlobalExceptionHandler.class})
class ProtocolDefinitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProtocolDefinitionService protocolDefinitionService;

    private static final UUID PROTOCOL_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private ProtocolDefinition buildProtocolDefinition() {
        ProtocolDefinition def = new ProtocolDefinition();
        def.setId(PROTOCOL_ID);
        def.setUrl("http://example.org/PlanDefinition/hiv-treatment");
        def.setVersion("1.0");
        def.setStatus(ProtocolDefinitionStatus.ACTIVE);
        def.setLoadedAt(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC));
        def.setDefinition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"));
        return def;
    }

    // --- POST / (loadProtocol) ---

    @Test
    void loadProtocol_validJson_returns201() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        when(protocolDefinitionService.loadProtocol(any(String.class))).thenReturn(protocolDef);

        String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of("planDefinitionJson", "{\"resourceType\":\"PlanDefinition\"}"));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROTOCOL_ID.toString()))
                .andExpect(jsonPath("$.url").value("http://example.org/PlanDefinition/hiv-treatment"))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(protocolDefinitionService).loadProtocol("{\"resourceType\":\"PlanDefinition\"}");
    }

    @Test
    void loadProtocol_blankJson_returns400() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of("planDefinitionJson", ""));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(protocolDefinitionService);
    }

    @Test
    void loadProtocol_duplicateUrlVersion_returns400() throws Exception {
        when(protocolDefinitionService.loadProtocol(any(String.class)))
                .thenThrow(new IllegalArgumentException(
                        "Protocol definition already exists for url=http://example.org, version=1.0"));

        String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of("planDefinitionJson", "{\"resourceType\":\"PlanDefinition\"}"));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Protocol definition already exists for url=http://example.org, version=1.0"));
    }

    // --- GET / (listAll) ---

    @Test
    void listAll_returnsDefinitions() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        when(protocolDefinitionService.findAll()).thenReturn(List.of(protocolDef));

        mockMvc.perform(get("/v1/compliance/protocol-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROTOCOL_ID.toString()))
                .andExpect(jsonPath("$[0].url").value("http://example.org/PlanDefinition/hiv-treatment"));
    }

    @Test
    void listAll_empty_returnsEmptyList() throws Exception {
        when(protocolDefinitionService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/compliance/protocol-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- GET /{id} (getById) ---

    @Test
    void getById_found_returns200() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        when(protocolDefinitionService.findById(PROTOCOL_ID)).thenReturn(protocolDef);

        mockMvc.perform(get("/v1/compliance/protocol-definitions/{id}", PROTOCOL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROTOCOL_ID.toString()))
                .andExpect(jsonPath("$.canonical").value(
                        "http://example.org/PlanDefinition/hiv-treatment|1.0"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(protocolDefinitionService.findById(PROTOCOL_ID))
                .thenThrow(new EntityNotFoundException("Protocol definition not found: " + PROTOCOL_ID));

        mockMvc.perform(get("/v1/compliance/protocol-definitions/{id}", PROTOCOL_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Protocol definition not found: " + PROTOCOL_ID));
    }

    // --- GET /by-url ---

    @Test
    void getByUrl_returnsVersions() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        when(protocolDefinitionService.findByUrl("http://example.org/PlanDefinition/hiv-treatment"))
                .thenReturn(List.of(protocolDef));

        mockMvc.perform(get("/v1/compliance/protocol-definitions/by-url")
                        .param("url", "http://example.org/PlanDefinition/hiv-treatment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("1.0"));
    }

    // --- GET /by-url-version ---

    @Test
    void getByUrlAndVersion_found_returns200() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        when(protocolDefinitionService.findByUrlAndVersion(
                "http://example.org/PlanDefinition/hiv-treatment", "1.0"))
                .thenReturn(Optional.of(protocolDef));

        mockMvc.perform(get("/v1/compliance/protocol-definitions/by-url-version")
                        .param("url", "http://example.org/PlanDefinition/hiv-treatment")
                        .param("version", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROTOCOL_ID.toString()));
    }

    @Test
    void getByUrlAndVersion_notFound_returns404() throws Exception {
        when(protocolDefinitionService.findByUrlAndVersion("http://example.org/unknown", "1.0"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/compliance/protocol-definitions/by-url-version")
                        .param("url", "http://example.org/unknown")
                        .param("version", "1.0"))
                .andExpect(status().isNotFound());
    }

    // --- POST /{id}/retire ---

    @Test
    void retire_active_returns200() throws Exception {
        ProtocolDefinition protocolDef = buildProtocolDefinition();
        protocolDef.setStatus(ProtocolDefinitionStatus.RETIRED);
        when(protocolDefinitionService.retireProtocol(PROTOCOL_ID)).thenReturn(protocolDef);

        mockMvc.perform(post("/v1/compliance/protocol-definitions/{id}/retire", PROTOCOL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void retire_alreadyRetired_returns409() throws Exception {
        when(protocolDefinitionService.retireProtocol(PROTOCOL_ID))
                .thenThrow(new IllegalStateException("Protocol definition is already retired: " + PROTOCOL_ID));

        mockMvc.perform(post("/v1/compliance/protocol-definitions/{id}/retire", PROTOCOL_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Protocol definition is already retired: " + PROTOCOL_ID));
    }

    // --- POST /{id}/rebuild-index ---

    @Test
    void rebuildIndex_returns200() throws Exception {
        doNothing().when(protocolDefinitionService).rebuildIndex(PROTOCOL_ID);

        mockMvc.perform(post("/v1/compliance/protocol-definitions/{id}/rebuild-index", PROTOCOL_ID))
                .andExpect(status().isOk());

        verify(protocolDefinitionService).rebuildIndex(PROTOCOL_ID);
    }

    @Test
    void rebuildIndex_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Protocol definition not found: " + PROTOCOL_ID))
                .when(protocolDefinitionService).rebuildIndex(PROTOCOL_ID);

        mockMvc.perform(post("/v1/compliance/protocol-definitions/{id}/rebuild-index", PROTOCOL_ID))
                .andExpect(status().isNotFound());
    }

    // --- DELETE /{id} ---

    @Test
    void delete_noInstances_returns204() throws Exception {
        doNothing().when(protocolDefinitionService).deleteProtocol(PROTOCOL_ID);

        mockMvc.perform(delete("/v1/compliance/protocol-definitions/{id}", PROTOCOL_ID))
                .andExpect(status().isNoContent());

        verify(protocolDefinitionService).deleteProtocol(PROTOCOL_ID);
    }

    @Test
    void delete_withInstances_returns409() throws Exception {
        doThrow(new IllegalStateException(
                "Cannot delete protocol definition with existing instances: " + PROTOCOL_ID))
                .when(protocolDefinitionService).deleteProtocol(PROTOCOL_ID);

        mockMvc.perform(delete("/v1/compliance/protocol-definitions/{id}", PROTOCOL_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Cannot delete protocol definition with existing instances: " + PROTOCOL_ID));
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Protocol definition not found: " + PROTOCOL_ID))
                .when(protocolDefinitionService).deleteProtocol(PROTOCOL_ID);

        mockMvc.perform(delete("/v1/compliance/protocol-definitions/{id}", PROTOCOL_ID))
                .andExpect(status().isNotFound());
    }
}
