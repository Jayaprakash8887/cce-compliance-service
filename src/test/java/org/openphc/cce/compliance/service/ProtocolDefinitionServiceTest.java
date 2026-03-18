package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;
import org.openphc.cce.compliance.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.TriggerIndexRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProtocolDefinitionServiceTest {

    @Mock
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Mock
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Mock
    private TriggerIndexRepository triggerIndexRepository;

    @Mock
    private PlanDefinitionParser planDefinitionParser;

    @Mock
    private TriggerMatchingService triggerMatchingService;

    @Mock
    private AuditService auditService;

    private ProtocolDefinitionService service;
    private ObjectMapper objectMapper;
    private String planDefinitionJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ProtocolDefinitionService(
                protocolDefinitionRepository,
                protocolInstanceRepository,
                triggerIndexRepository,
                planDefinitionParser,
                triggerMatchingService,
                auditService,
                objectMapper);

        try (InputStream is = getClass().getResourceAsStream("/fhir/plan-definition-anc-high-risk.json")) {
            assertNotNull(is, "Test fixture not found");
            planDefinitionJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Load Protocol Tests ──

    @Test
    void loadProtocol_validJson_persistsDefinitionAndBuildsIndex() {
        PlanDefinition planDef = mockPlanDefinition("http://openphc.org/PlanDefinition/anc-high-risk", "1.0.0", 6);
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any(ProtocolDefinition.class))).thenAnswer(invocation -> {
            ProtocolDefinition pd = invocation.getArgument(0);
            pd.setId(UUID.randomUUID());
            return pd;
        });

        List<TriggerIndex> indexEntries = List.of(
                buildTriggerIndex("Encounter", "type", "http://openphc.org/encounter-types", "anc-visit"),
                buildTriggerIndex("Encounter", "serviceType", "http://openphc.org/service-types", "high-risk-anc"));
        when(planDefinitionParser.buildTriggerIndexEntries(eq(planDef), any(UUID.class))).thenReturn(indexEntries);
        when(planDefinitionParser.extractConditionOnlyTriggers(planDef)).thenReturn(List.of());

        ProtocolDefinition result = service.loadProtocol(planDefinitionJson);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("http://openphc.org/PlanDefinition/anc-high-risk", result.getUrl());
        assertEquals("1.0.0", result.getVersion());
        assertEquals(ProtocolDefinitionStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getDefinition());
        assertNotNull(result.getLoadedAt());

        verify(planDefinitionParser).validateTriggers(planDef);
        verify(protocolDefinitionRepository).save(any(ProtocolDefinition.class));
        verify(triggerIndexRepository).saveAll(indexEntries);
        verify(auditService).audit(eq("PROTOCOL_MANAGEMENT"), eq("PROTOCOL_LOADED"), eq("system"),
                eq("ProtocolDefinition"), anyString(), anyMap());
    }

    @Test
    void loadProtocol_withConditionOnlyTriggers_registersInCache() {
        PlanDefinition planDef = mockPlanDefinition("http://openphc.org/test", "1.0.0", 1);
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any(ProtocolDefinition.class))).thenAnswer(invocation -> {
            ProtocolDefinition pd = invocation.getArgument(0);
            pd.setId(UUID.randomUUID());
            return pd;
        });
        when(planDefinitionParser.buildTriggerIndexEntries(eq(planDef), any(UUID.class))).thenReturn(List.of());

        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> conditionInfos = List.of(
                new PlanDefinitionParser.ConditionOnlyTriggerInfo("risk-check", "text/jsonlogic", "{\">\": [{\"var\": \"riskScore\"}, 7]}"));
        when(planDefinitionParser.extractConditionOnlyTriggers(planDef)).thenReturn(conditionInfos);

        service.loadProtocol(planDefinitionJson);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConditionOnlyTrigger>> captor = ArgumentCaptor.forClass(List.class);
        verify(triggerMatchingService).registerConditionOnlyTriggers(any(UUID.class), captor.capture());

        List<ConditionOnlyTrigger> registered = captor.getValue();
        assertEquals(1, registered.size());
        assertEquals("risk-check", registered.get(0).actionId());
        assertEquals("text/jsonlogic", registered.get(0).conditionLanguage());
    }

    @Test
    void loadProtocol_duplicateUrlVersion_throwsIllegalArgument() {
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion("http://openphc.org/test", "1.0.0"))
                .thenReturn(Optional.of(ProtocolDefinition.builder().build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(planDefinitionJson));
        assertTrue(ex.getMessage().contains("already exists"));

        verify(protocolDefinitionRepository, never()).save(any());
        verify(triggerIndexRepository, never()).saveAll(anyList());
    }

    @Test
    void loadProtocol_invalidTrigger_throwsIllegalArgument() {
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        doThrow(new IllegalArgumentException("trigger with no data[] and no condition"))
                .when(planDefinitionParser).validateTriggers(planDef);

        assertThrows(IllegalArgumentException.class, () -> service.loadProtocol(planDefinitionJson));

        verify(protocolDefinitionRepository, never()).save(any());
    }

    // ── Retire Protocol Tests ──

    @Test
    void retireProtocol_activeProtocol_setsRetiredAndCleansUp() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));
        when(protocolDefinitionRepository.save(any(ProtocolDefinition.class))).thenAnswer(i -> i.getArgument(0));

        ProtocolDefinition result = service.retireProtocol(id);

        assertEquals(ProtocolDefinitionStatus.RETIRED, result.getStatus());
        verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
        verify(triggerMatchingService).removeConditionOnlyTriggers(id);
        verify(auditService).audit(eq("PROTOCOL_MANAGEMENT"), eq("PROTOCOL_RETIRED"),
                eq("system"), eq("ProtocolDefinition"), eq(id.toString()), anyMap());
    }

    @Test
    void retireProtocol_alreadyRetired_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.RETIRED);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));

        assertThrows(IllegalStateException.class, () -> service.retireProtocol(id));

        verify(protocolDefinitionRepository, never()).save(any());
        verify(triggerIndexRepository, never()).deleteByProtocolDefinitionId(any());
    }

    @Test
    void retireProtocol_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.retireProtocol(id));
    }

    // ── Rebuild Index Tests ──

    @Test
    void rebuildIndex_deletesOldAndRebuilds() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));

        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(protocolDef.getDefinition().toString())).thenReturn(planDef);

        List<TriggerIndex> newEntries = List.of(
                buildTriggerIndex("Encounter", "type", "http://openphc.org/encounter-types", "anc-visit"));
        when(planDefinitionParser.buildTriggerIndexEntries(planDef, id)).thenReturn(newEntries);

        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> conditionInfos = List.of(
                new PlanDefinitionParser.ConditionOnlyTriggerInfo("action-1", "text/jsonlogic", "{\"==\": [1, 1]}"));
        when(planDefinitionParser.extractConditionOnlyTriggers(planDef)).thenReturn(conditionInfos);

        service.rebuildIndex(id);

        verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
        verify(triggerMatchingService).removeConditionOnlyTriggers(id);
        verify(triggerIndexRepository).saveAll(newEntries);
        verify(triggerMatchingService).registerConditionOnlyTriggers(eq(id), anyList());
    }

    @Test
    void rebuildIndex_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.rebuildIndex(id));
    }

    // ── Delete Protocol Tests ──

    @Test
    void deleteProtocol_noInstances_deletesSuccessfully() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));
        when(protocolInstanceRepository.existsByProtocolDefinitionId(id)).thenReturn(false);

        service.deleteProtocol(id);

        verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
        verify(triggerMatchingService).removeConditionOnlyTriggers(id);
        verify(protocolDefinitionRepository).delete(protocolDef);
    }

    @Test
    void deleteProtocol_withInstances_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));
        when(protocolInstanceRepository.existsByProtocolDefinitionId(id)).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.deleteProtocol(id));
        assertTrue(ex.getMessage().contains("existing instances"));

        verify(protocolDefinitionRepository, never()).delete(any());
    }

    @Test
    void deleteProtocol_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.deleteProtocol(id));
    }

    // ── Read Operation Tests ──

    @Test
    void findById_existing_returnsDefinition() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));

        ProtocolDefinition result = service.findById(id);

        assertEquals(id, result.getId());
    }

    @Test
    void findById_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findById(id));
    }

    @Test
    void findAll_delegatesToRepository() {
        List<ProtocolDefinition> defs = List.of(
                buildProtocolDefinition(UUID.randomUUID(), ProtocolDefinitionStatus.ACTIVE));
        when(protocolDefinitionRepository.findAll()).thenReturn(defs);

        List<ProtocolDefinition> result = service.findAll();

        assertEquals(1, result.size());
    }

    @Test
    void findByUrl_delegatesToRepository() {
        String url = "http://openphc.org/test";
        when(protocolDefinitionRepository.findByUrl(url)).thenReturn(List.of());

        List<ProtocolDefinition> result = service.findByUrl(url);

        assertTrue(result.isEmpty());
        verify(protocolDefinitionRepository).findByUrl(url);
    }

    @Test
    void findByUrlAndVersion_delegatesToRepository() {
        String url = "http://openphc.org/test";
        String version = "1.0.0";
        when(protocolDefinitionRepository.findByUrlAndVersion(url, version)).thenReturn(Optional.empty());

        Optional<ProtocolDefinition> result = service.findByUrlAndVersion(url, version);

        assertTrue(result.isEmpty());
        verify(protocolDefinitionRepository).findByUrlAndVersion(url, version);
    }

    // ── Helpers ──

    private PlanDefinition mockPlanDefinition(String url, String version, int actionCount) {
        PlanDefinition planDef = mock(PlanDefinition.class);
        when(planDef.getUrl()).thenReturn(url);
        when(planDef.getVersion()).thenReturn(version);

        List<PlanDefinition.PlanDefinitionActionComponent> actions = new java.util.ArrayList<>();
        for (int i = 0; i < actionCount; i++) {
            actions.add(new PlanDefinition.PlanDefinitionActionComponent());
        }
        when(planDef.getAction()).thenReturn(actions);

        return planDef;
    }

    private PlanDefinition mockPlanDefinitionMinimal(String url, String version) {
        PlanDefinition planDef = mock(PlanDefinition.class);
        lenient().when(planDef.getUrl()).thenReturn(url);
        lenient().when(planDef.getVersion()).thenReturn(version);
        return planDef;
    }

    private ProtocolDefinition buildProtocolDefinition(UUID id, ProtocolDefinitionStatus status) {
        return ProtocolDefinition.builder()
                .id(id)
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(status)
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .loadedAt(OffsetDateTime.now())
                .build();
    }

    private TriggerIndex buildTriggerIndex(String resourceType, String path, String system, String code) {
        return TriggerIndex.builder()
                .id(TriggerIndexId.builder()
                        .resourceType(resourceType)
                        .path(path)
                        .codeSystem(system)
                        .codeValue(code)
                        .protocolDefinitionId(UUID.randomUUID())
                        .actionId("action-1")
                        .build())
                .build();
    }
}
