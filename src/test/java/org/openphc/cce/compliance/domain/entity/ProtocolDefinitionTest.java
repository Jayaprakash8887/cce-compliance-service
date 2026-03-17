package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolDefinitionTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getCanonical_shouldReturnUrlPipeVersion() {
        JsonNode definition = objectMapper.valueToTree(Map.of("resourceType", "PlanDefinition"));

        ProtocolDefinition pd = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/fhir/PlanDefinition/anc-high-risk")
                .version("2.1")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(definition)
                .loadedAt(OffsetDateTime.now())
                .build();

        assertEquals("http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1", pd.getCanonical());
    }

    @Test
    void getCanonical_shouldHandleDifferentVersions() {
        ProtocolDefinition pd = ProtocolDefinition.builder()
                .url("http://example.org/protocol")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(objectMapper.createObjectNode())
                .loadedAt(OffsetDateTime.now())
                .build();

        assertEquals("http://example.org/protocol|1.0.0", pd.getCanonical());
    }

    @Test
    void builder_shouldSetAllFields() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        JsonNode definition = objectMapper.valueToTree(Map.of("resourceType", "PlanDefinition", "status", "active"));

        ProtocolDefinition pd = ProtocolDefinition.builder()
                .id(id)
                .url("http://openphc.org/fhir/PlanDefinition/test")
                .version("1.0")
                .status(ProtocolDefinitionStatus.RETIRED)
                .definition(definition)
                .loadedAt(now)
                .build();

        assertEquals(id, pd.getId());
        assertEquals("http://openphc.org/fhir/PlanDefinition/test", pd.getUrl());
        assertEquals("1.0", pd.getVersion());
        assertEquals(ProtocolDefinitionStatus.RETIRED, pd.getStatus());
        assertEquals(definition, pd.getDefinition());
        assertEquals(now, pd.getLoadedAt());
    }

    @Test
    void enumValues_protocolDefinitionStatus() {
        assertEquals(2, ProtocolDefinitionStatus.values().length);
        assertNotNull(ProtocolDefinitionStatus.valueOf("ACTIVE"));
        assertNotNull(ProtocolDefinitionStatus.valueOf("RETIRED"));
    }
}
