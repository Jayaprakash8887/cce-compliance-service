package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.service.IntelligenceEventLogService;
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
@ContextConfiguration(classes = {IntelligenceEventLogController.class, DtoMapper.class, GlobalExceptionHandler.class})
class IntelligenceEventLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IntelligenceEventLogService intelligenceEventLogService;

    private static final UUID EVENT_LOG_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440000");
    private static final UUID ACTION_DEF_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");
    private static final UUID PROTOCOL_INSTANCE_ID = UUID.fromString("880e8400-e29b-41d4-a716-446655440000");
    private static final UUID STEP_INSTANCE_ID = UUID.fromString("990e8400-e29b-41d4-a716-446655440000");
    private static final UUID DEVIATION_ID = UUID.fromString("cc0e8400-e29b-41d4-a716-446655440000");

    private IntelligenceEventLog buildEventLog() {
        return IntelligenceEventLog.builder()
                .id(EVENT_LOG_ID)
                .eventPayload(objectMapper.createObjectNode().put("id", UUID.randomUUID().toString()))
                .actionDefinitionId(ACTION_DEF_ID)
                .protocolInstanceId(PROTOCOL_INSTANCE_ID)
                .stepInstanceId(STEP_INSTANCE_ID)
                .deviationId(DEVIATION_ID)
                .subject("patient-1")
                .actionType("CommunicationRequest")
                .intelligenceDestination("sms")
                .stepState("overdue")
                .triggerReason("overdue")
                .stepActionId("bp-check")
                .evaluationExpression("{\">\": [{\"var\": \"daysOverdue\"}, 2]}")
                .evaluationContext(objectMapper.createObjectNode().put("stepState", "overdue"))
                .published(true)
                .publishedAt(OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC))
                .createdAt(OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    // --- GET / (listAll) ---

    @Test
    void listAll_returnsEventLogs() throws Exception {
        IntelligenceEventLog eventLog = buildEventLog();
        when(intelligenceEventLogService.findAll()).thenReturn(List.of(eventLog));

        mockMvc.perform(get("/v1/compliance/intelligence-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(EVENT_LOG_ID.toString()))
                .andExpect(jsonPath("$[0].actionDefinitionId").value(ACTION_DEF_ID.toString()))
                .andExpect(jsonPath("$[0].protocolInstanceId").value(PROTOCOL_INSTANCE_ID.toString()))
                .andExpect(jsonPath("$[0].stepInstanceId").value(STEP_INSTANCE_ID.toString()))
                .andExpect(jsonPath("$[0].published").value(true))
                .andExpect(jsonPath("$[0].triggerReason").value("overdue"))
                .andExpect(jsonPath("$[0].deviationId").value(DEVIATION_ID.toString()));
    }

    @Test
    void listAll_empty_returnsEmptyList() throws Exception {
        when(intelligenceEventLogService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/compliance/intelligence-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listAll_filterByProtocolInstanceId() throws Exception {
        IntelligenceEventLog eventLog = buildEventLog();
        when(intelligenceEventLogService.findByProtocolInstanceId(PROTOCOL_INSTANCE_ID))
                .thenReturn(List.of(eventLog));

        mockMvc.perform(get("/v1/compliance/intelligence-events")
                        .param("protocolInstanceId", PROTOCOL_INSTANCE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].protocolInstanceId").value(PROTOCOL_INSTANCE_ID.toString()));

        verify(intelligenceEventLogService).findByProtocolInstanceId(PROTOCOL_INSTANCE_ID);
        verify(intelligenceEventLogService, never()).findAll();
    }

    @Test
    void listAll_filterByActionDefinitionId() throws Exception {
        IntelligenceEventLog eventLog = buildEventLog();
        when(intelligenceEventLogService.findByActionDefinitionId(ACTION_DEF_ID))
                .thenReturn(List.of(eventLog));

        mockMvc.perform(get("/v1/compliance/intelligence-events")
                        .param("actionDefinitionId", ACTION_DEF_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionDefinitionId").value(ACTION_DEF_ID.toString()));

        verify(intelligenceEventLogService).findByActionDefinitionId(ACTION_DEF_ID);
        verify(intelligenceEventLogService, never()).findAll();
    }

    @Test
    void listAll_filterByPublished() throws Exception {
        IntelligenceEventLog eventLog = buildEventLog();
        when(intelligenceEventLogService.findByPublished(true))
                .thenReturn(List.of(eventLog));

        mockMvc.perform(get("/v1/compliance/intelligence-events")
                        .param("published", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].published").value(true));

        verify(intelligenceEventLogService).findByPublished(true);
        verify(intelligenceEventLogService, never()).findAll();
    }

    // --- GET /{id} (getById) ---

    @Test
    void getById_found_returns200() throws Exception {
        IntelligenceEventLog eventLog = buildEventLog();
        when(intelligenceEventLogService.findById(EVENT_LOG_ID)).thenReturn(eventLog);

        mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", EVENT_LOG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EVENT_LOG_ID.toString()))
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.triggerReason").value("overdue"))
                .andExpect(jsonPath("$.stepActionId").value("bp-check"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(intelligenceEventLogService.findById(EVENT_LOG_ID))
                .thenThrow(new EntityNotFoundException("Intelligence event log not found: " + EVENT_LOG_ID));

        mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", EVENT_LOG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Intelligence event log not found: " + EVENT_LOG_ID));
    }

    @Test
    void getById_nullOptionalFields_returns200() throws Exception {
        IntelligenceEventLog eventLog = IntelligenceEventLog.builder()
                .id(EVENT_LOG_ID)
                .eventPayload(objectMapper.createObjectNode())
                .actionDefinitionId(ACTION_DEF_ID)
                .protocolInstanceId(PROTOCOL_INSTANCE_ID)
                .subject("patient-1")
                .actionType("Task")
                .intelligenceDestination("email")
                .stepState("completed")
                .triggerReason("completion")
                .published(false)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(intelligenceEventLogService.findById(EVENT_LOG_ID)).thenReturn(eventLog);

        mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", EVENT_LOG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepInstanceId").doesNotExist())
                .andExpect(jsonPath("$.deviationId").doesNotExist())
                .andExpect(jsonPath("$.stepActionId").doesNotExist());
    }
}
