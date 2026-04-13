package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.service.ActionRunService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {ActionRunController.class, DtoMapper.class, GlobalExceptionHandler.class})
class ActionRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ActionRunService actionRunService;

    private static final UUID ACTION_RUN_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440000");
    private static final UUID ACTION_DEF_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");
    private static final UUID PROTOCOL_INSTANCE_ID = UUID.fromString("880e8400-e29b-41d4-a716-446655440000");
    private static final UUID STEP_INSTANCE_ID = UUID.fromString("990e8400-e29b-41d4-a716-446655440000");
    private static final UUID INTELLIGENCE_EVENT_ID = UUID.fromString("aa0e8400-e29b-41d4-a716-446655440000");

    private ActionRun buildActionRun() {
        ActionDefinition actionDef = ActionDefinition.builder()
                .id(ACTION_DEF_ID)
                .canonicalUrl("http://openphc.org/ActivityDefinition/escalation-alert")
                .version("1.0")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.CommunicationRequest)
                .build();

        ProtocolInstance protocolInstance = ProtocolInstance.builder()
                .id(PROTOCOL_INSTANCE_ID)
                .build();

        StepInstance stepInstance = StepInstance.builder()
                .id(STEP_INSTANCE_ID)
                .build();

        ActionRun run = new ActionRun();
        run.setId(ACTION_RUN_ID);
        run.setActionDefinition(actionDef);
        run.setProtocolInstance(protocolInstance);
        run.setStepInstance(stepInstance);
        run.setStatus(ActionRunStatus.PUBLISHED);
        run.setIntelligenceEventId(INTELLIGENCE_EVENT_ID);
        run.setOutputMetadata(objectMapper.createObjectNode().put("template", "escalation-v1"));
        run.setCreatedAt(OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC));
        run.setUpdatedAt(OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC));
        return run;
    }

    // --- GET / (listAll) ---

    @Test
    void listAll_returnsActionRuns() throws Exception {
        ActionRun run = buildActionRun();
        when(actionRunService.findAll()).thenReturn(List.of(run));

        mockMvc.perform(get("/v1/compliance/action-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ACTION_RUN_ID.toString()))
                .andExpect(jsonPath("$[0].actionDefinitionId").value(ACTION_DEF_ID.toString()))
                .andExpect(jsonPath("$[0].protocolInstanceId").value(PROTOCOL_INSTANCE_ID.toString()))
                .andExpect(jsonPath("$[0].stepInstanceId").value(STEP_INSTANCE_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].intelligenceEventId").value(INTELLIGENCE_EVENT_ID.toString()));
    }

    @Test
    void listAll_empty_returnsEmptyList() throws Exception {
        when(actionRunService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/compliance/action-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listAll_filterByProtocolInstanceId() throws Exception {
        ActionRun run = buildActionRun();
        when(actionRunService.findByProtocolInstanceId(PROTOCOL_INSTANCE_ID))
                .thenReturn(List.of(run));

        mockMvc.perform(get("/v1/compliance/action-runs")
                        .param("protocolInstanceId", PROTOCOL_INSTANCE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].protocolInstanceId").value(PROTOCOL_INSTANCE_ID.toString()));

        verify(actionRunService).findByProtocolInstanceId(PROTOCOL_INSTANCE_ID);
        verify(actionRunService, never()).findAll();
    }

    @Test
    void listAll_filterByActionDefinitionId() throws Exception {
        ActionRun run = buildActionRun();
        when(actionRunService.findByActionDefinitionId(ACTION_DEF_ID))
                .thenReturn(List.of(run));

        mockMvc.perform(get("/v1/compliance/action-runs")
                        .param("actionDefinitionId", ACTION_DEF_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionDefinitionId").value(ACTION_DEF_ID.toString()));

        verify(actionRunService).findByActionDefinitionId(ACTION_DEF_ID);
        verify(actionRunService, never()).findAll();
    }

    @Test
    void listAll_filterByStatus() throws Exception {
        ActionRun run = buildActionRun();
        when(actionRunService.findByStatus(ActionRunStatus.PUBLISHED))
                .thenReturn(List.of(run));

        mockMvc.perform(get("/v1/compliance/action-runs")
                        .param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"));

        verify(actionRunService).findByStatus(ActionRunStatus.PUBLISHED);
        verify(actionRunService, never()).findAll();
    }

    // --- GET /{id} (getById) ---

    @Test
    void getById_found_returns200() throws Exception {
        ActionRun run = buildActionRun();
        when(actionRunService.findById(ACTION_RUN_ID)).thenReturn(run);

        mockMvc.perform(get("/v1/compliance/action-runs/{id}", ACTION_RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ACTION_RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.intelligenceEventId").value(INTELLIGENCE_EVENT_ID.toString()));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(actionRunService.findById(ACTION_RUN_ID))
                .thenThrow(new EntityNotFoundException("Action run not found: " + ACTION_RUN_ID));

        mockMvc.perform(get("/v1/compliance/action-runs/{id}", ACTION_RUN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Action run not found: " + ACTION_RUN_ID));
    }

    @Test
    void getById_nullStepInstance_returns200() throws Exception {
        ActionRun run = buildActionRun();
        run.setStepInstance(null);
        when(actionRunService.findById(ACTION_RUN_ID)).thenReturn(run);

        mockMvc.perform(get("/v1/compliance/action-runs/{id}", ACTION_RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepInstanceId").doesNotExist());
    }
}
