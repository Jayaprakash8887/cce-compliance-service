package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionKind;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActionDefinitionServiceTest {

    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;

    @Mock
    private IntelligenceEventLogRepository intelligenceEventLogRepository;

    @Mock
    private AuditService auditService;

    private ActionDefinitionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ActionDefinitionService(actionDefinitionRepository, intelligenceEventLogRepository, auditService);
    }

    // ── Create Tests ──

    @Nested
    class Create {

        @Test
        void validDefinition_persistsWithExtractedFields() {
            JsonNode definition = buildDefinition("http://openphc.org/ActivityDefinition/test", "1.0",
                    "test-action", "Test Action", "CommunicationRequest", "HIGH", "SUPERVISOR");

            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(actionDefinitionRepository.save(any(ActionDefinition.class))).thenAnswer(invocation -> {
                ActionDefinition ad = invocation.getArgument(0);
                ad.setId(UUID.randomUUID());
                return ad;
            });

            ActionDefinition result = service.createActionDefinition(definition);

            assertNotNull(result.getId());
            assertEquals("http://openphc.org/ActivityDefinition/test", result.getCanonicalUrl());
            assertEquals("1.0", result.getVersion());
            assertEquals("test-action", result.getName());
            assertEquals("Test Action", result.getTitle());
            assertEquals(ActionDefinitionStatus.ACTIVE, result.getStatus());
            assertEquals(ActionDefinitionKind.CommunicationRequest, result.getActionType());
            assertEquals(definition, result.getDefinition());

            verify(auditService).audit(eq("ACTION_DEFINITION"), eq("ACTION_DEFINITION_CREATED"),
                    eq("system"), eq("ActionDefinition"), anyString(), anyMap());
        }

        @Test
        void validDefinition_withoutExtensions_noSeverityOrDestinationOnEntity() {
            JsonNode definition = buildDefinitionNoExtensions(
                    "http://openphc.org/ActivityDefinition/simple", "1.0", "Task");

            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(actionDefinitionRepository.save(any(ActionDefinition.class))).thenAnswer(invocation -> {
                ActionDefinition ad = invocation.getArgument(0);
                ad.setId(UUID.randomUUID());
                return ad;
            });

            ActionDefinition result = service.createActionDefinition(definition);

            assertEquals(ActionDefinitionKind.Task, result.getActionType());
        }

        @Test
        void duplicateUrlAndVersion_throwsIllegalArgument() {
            JsonNode definition = buildDefinitionNoExtensions(
                    "http://openphc.org/ActivityDefinition/dup", "1.0", "Task");

            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/ActivityDefinition/dup", "1.0"))
                    .thenReturn(Optional.of(ActionDefinition.builder().build()));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createActionDefinition(definition));
            assertTrue(ex.getMessage().contains("already exists"));

            verify(actionDefinitionRepository, never()).save(any());
        }

        @Test
        void missingUrl_throwsIllegalArgument() {
            ObjectNode definition = objectMapper.createObjectNode();
            definition.put("version", "1.0");
            definition.put("kind", "Task");

            assertThrows(IllegalArgumentException.class,
                    () -> service.createActionDefinition(definition));
        }

        @Test
        void missingKind_throwsIllegalArgument() {
            ObjectNode definition = objectMapper.createObjectNode();
            definition.put("url", "http://openphc.org/test");
            definition.put("version", "1.0");

            assertThrows(IllegalArgumentException.class,
                    () -> service.createActionDefinition(definition));
        }

        @Test
        void unsupportedKind_throwsIllegalArgument() {
            JsonNode definition = buildDefinitionNoExtensions(
                    "http://openphc.org/test", "1.0", "MedicationRequest");

            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createActionDefinition(definition));
            assertTrue(ex.getMessage().contains("Unsupported"));
        }
    }

    // ── Update Tests ──

    @Nested
    class Update {

        @Test
        void validUpdate_updatesAllFields() {
            UUID id = UUID.randomUUID();
            ActionDefinition existing = buildExistingActionDef(id);
            JsonNode newDefinition = buildDefinition("http://openphc.org/ActivityDefinition/updated", "2.0",
                    "updated-action", "Updated Action", "ServiceRequest", "CRITICAL", "FACILITY");

            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(existing));
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/ActivityDefinition/updated", "2.0"))
                    .thenReturn(Optional.empty());
            when(actionDefinitionRepository.save(any(ActionDefinition.class))).thenAnswer(i -> i.getArgument(0));

            ActionDefinition result = service.updateActionDefinition(id, newDefinition);

            assertEquals("http://openphc.org/ActivityDefinition/updated", result.getCanonicalUrl());
            assertEquals("2.0", result.getVersion());
            assertEquals("updated-action", result.getName());
            assertEquals(ActionDefinitionKind.ServiceRequest, result.getActionType());

            verify(auditService).audit(eq("ACTION_DEFINITION"), eq("ACTION_DEFINITION_UPDATED"),
                    eq("system"), eq("ActionDefinition"), eq(id.toString()), anyMap());
        }

        @Test
        void updateToExistingUrlVersion_throwsIllegalArgument() {
            UUID id = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            ActionDefinition existing = buildExistingActionDef(id);
            ActionDefinition other = buildExistingActionDef(otherId);
            JsonNode newDefinition = buildDefinitionNoExtensions(
                    "http://openphc.org/ActivityDefinition/other", "1.0", "Task");

            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(existing));
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/ActivityDefinition/other", "1.0"))
                    .thenReturn(Optional.of(other));

            assertThrows(IllegalArgumentException.class,
                    () -> service.updateActionDefinition(id, newDefinition));
        }

        @Test
        void updateSameUrlVersion_allowed() {
            UUID id = UUID.randomUUID();
            ActionDefinition existing = buildExistingActionDef(id);
            JsonNode newDefinition = buildDefinitionNoExtensions(
                    "http://openphc.org/ActivityDefinition/test", "1.0", "Task");

            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(existing));
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/ActivityDefinition/test", "1.0"))
                    .thenReturn(Optional.of(existing));
            when(actionDefinitionRepository.save(any(ActionDefinition.class))).thenAnswer(i -> i.getArgument(0));

            assertDoesNotThrow(() -> service.updateActionDefinition(id, newDefinition));
        }

        @Test
        void updateNotFound_throwsEntityNotFound() {
            UUID id = UUID.randomUUID();
            JsonNode definition = buildDefinitionNoExtensions("http://test", "1.0", "Task");

            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class,
                    () -> service.updateActionDefinition(id, definition));
        }
    }

    // ── Retire Tests ──

    @Nested
    class Retire {

        @Test
        void activeDefinition_setsRetired() {
            UUID id = UUID.randomUUID();
            ActionDefinition actionDef = buildExistingActionDef(id);
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(actionDef));
            when(actionDefinitionRepository.save(any(ActionDefinition.class))).thenAnswer(i -> i.getArgument(0));

            ActionDefinition result = service.retireActionDefinition(id);

            assertEquals(ActionDefinitionStatus.RETIRED, result.getStatus());
            verify(auditService).audit(eq("ACTION_DEFINITION"), eq("ACTION_DEFINITION_RETIRED"),
                    eq("system"), eq("ActionDefinition"), eq(id.toString()), anyMap());
        }

        @Test
        void alreadyRetired_throwsIllegalState() {
            UUID id = UUID.randomUUID();
            ActionDefinition actionDef = buildExistingActionDef(id);
            actionDef.setStatus(ActionDefinitionStatus.RETIRED);
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(actionDef));

            assertThrows(IllegalStateException.class, () -> service.retireActionDefinition(id));

            verify(actionDefinitionRepository, never()).save(any());
        }

        @Test
        void notFound_throwsEntityNotFound() {
            UUID id = UUID.randomUUID();
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.retireActionDefinition(id));
        }
    }

    // ── Delete Tests ──

    @Nested
    class Delete {

        @Test
        void noIntelligenceEvents_deletesSuccessfully() {
            UUID id = UUID.randomUUID();
            ActionDefinition actionDef = buildExistingActionDef(id);
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(actionDef));
            when(intelligenceEventLogRepository.existsByActionDefinitionId(id)).thenReturn(false);

            service.deleteActionDefinition(id);

            verify(actionDefinitionRepository).delete(actionDef);
        }

        @Test
        void withIntelligenceEvents_throwsIllegalState() {
            UUID id = UUID.randomUUID();
            ActionDefinition actionDef = buildExistingActionDef(id);
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(actionDef));
            when(intelligenceEventLogRepository.existsByActionDefinitionId(id)).thenReturn(true);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.deleteActionDefinition(id));
            assertTrue(ex.getMessage().contains("existing intelligence events"));

            verify(actionDefinitionRepository, never()).delete(any());
        }

        @Test
        void notFound_throwsEntityNotFound() {
            UUID id = UUID.randomUUID();
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.deleteActionDefinition(id));
        }
    }

    // ── Read Tests ──

    @Nested
    class Read {

        @Test
        void findById_existing_returnsDefinition() {
            UUID id = UUID.randomUUID();
            ActionDefinition actionDef = buildExistingActionDef(id);
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.of(actionDef));

            ActionDefinition result = service.findById(id);

            assertEquals(id, result.getId());
        }

        @Test
        void findById_notFound_throwsEntityNotFound() {
            UUID id = UUID.randomUUID();
            when(actionDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.findById(id));
        }

        @Test
        void findAll_delegatesToRepository() {
            List<ActionDefinition> defs = List.of(buildExistingActionDef(UUID.randomUUID()));
            when(actionDefinitionRepository.findAll()).thenReturn(defs);

            List<ActionDefinition> result = service.findAll();

            assertEquals(1, result.size());
        }

        @Test
        void findByCanonicalUrl_delegatesToRepository() {
            String url = "http://openphc.org/ActivityDefinition/test";
            when(actionDefinitionRepository.findByCanonicalUrl(url)).thenReturn(List.of());

            List<ActionDefinition> result = service.findByCanonicalUrl(url);

            assertTrue(result.isEmpty());
            verify(actionDefinitionRepository).findByCanonicalUrl(url);
        }
    }

    // ── Resolve by Canonical Tests ──

    @Nested
    class ResolveByCanonical {

        @Test
        void validCanonical_resolvesCorrectly() {
            ActionDefinition actionDef = buildExistingActionDef(UUID.randomUUID());
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/ActivityDefinition/test", "1.0"))
                    .thenReturn(Optional.of(actionDef));

            ActionDefinition result = service.resolveByCanonical(
                    "http://openphc.org/ActivityDefinition/test|1.0");

            assertNotNull(result);
        }

        @Test
        void canonicalNotFound_throwsEntityNotFound() {
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class,
                    () -> service.resolveByCanonical("http://openphc.org/unknown|1.0"));
        }

        @Test
        void invalidCanonicalFormat_noPipe_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolveByCanonical("http://openphc.org/test"));
        }

        @Test
        void invalidCanonicalFormat_endsWithPipe_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolveByCanonical("http://openphc.org/test|"));
        }

        @Test
        void canonicalWithMultiplePipes_usesLastSeparator() {
            ActionDefinition actionDef = buildExistingActionDef(UUID.randomUUID());
            when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                    "http://openphc.org/test|extra", "1.0"))
                    .thenReturn(Optional.of(actionDef));

            ActionDefinition result = service.resolveByCanonical("http://openphc.org/test|extra|1.0");

            assertNotNull(result);
            verify(actionDefinitionRepository).findByCanonicalUrlAndVersion(
                    "http://openphc.org/test|extra", "1.0");
        }
    }

    // ── Helpers ──

    private ActionDefinition buildExistingActionDef(UUID id) {
        return ActionDefinition.builder()
                .id(id)
                .canonicalUrl("http://openphc.org/ActivityDefinition/test")
                .version("1.0")
                .name("test-action")
                .title("Test Action")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionDefinitionKind.CommunicationRequest)
                .definition(objectMapper.createObjectNode())
                .build();
    }

    private JsonNode buildDefinition(String url, String version, String name, String title,
                                     String kind, String severity, String channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceType", "ActivityDefinition");
        node.put("url", url);
        node.put("version", version);
        node.put("name", name);
        node.put("title", title);
        node.put("kind", kind);

        var extensions = node.putArray("extension");

        ObjectNode sevExt = extensions.addObject();
        sevExt.put("url", "http://openphc.org/fhir/StructureDefinition/intelligence-severity");
        sevExt.put("valueCode", severity);

        ObjectNode chExt = extensions.addObject();
        chExt.put("url", "http://openphc.org/fhir/StructureDefinition/intelligence-destination");
        chExt.put("valueCode", channel);

        return node;
    }

    private JsonNode buildDefinitionNoExtensions(String url, String version, String kind) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceType", "ActivityDefinition");
        node.put("url", url);
        node.put("version", version);
        node.put("kind", kind);
        return node;
    }
}
