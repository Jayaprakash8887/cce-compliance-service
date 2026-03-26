package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.TriggerIndexRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the protocol definition lifecycle:
 * Load → Retire → Rebuild Index → Delete via REST endpoints,
 * with verification against the real database.
 */
class ProtocolLifecycleIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Autowired
    private TriggerIndexRepository triggerIndexRepository;

    private String loadFixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
    }

    @Test
    void loadProtocol_persistsDefinitionAndBuildsIndex() throws Exception {
        String planDefJson = loadFixture();
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", planDefJson));

        String responseJson = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.url").value("http://openphc.org/PlanDefinition/anc-high-risk"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        // Verify DB: ProtocolDefinition persisted
        assertThat(protocolDefinitionRepository.findById(protocolId)).isPresent();

        // Verify DB: TriggerIndex entries created
        int indexCount = triggerIndexRepository.findByIdProtocolDefinitionId(protocolId).size();
        assertThat(indexCount).as("Expected trigger index entries to be created").isGreaterThan(0);
    }

    @Test
    void loadDuplicateProtocol_returns400() throws Exception {
        String planDefJson = loadFixture();
        // Modify version to avoid collision with other tests
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0-dup\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        // First load succeeds
        mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // Second load fails — duplicate
        mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retireProtocol_setsRetiredAndDeletesIndex() throws Exception {
        // Load
        String planDefJson = loadFixture();
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"3.0.0-retire\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String responseJson = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());
        assertThat(triggerIndexRepository.findByIdProtocolDefinitionId(protocolId)).isNotEmpty();

        // Retire
        mockMvc.perform(post("/v1/protocol-definitions/{id}/retire", protocolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        // Verify: trigger index deleted
        assertThat(triggerIndexRepository.findByIdProtocolDefinitionId(protocolId)).isEmpty();
    }

    @Test
    void rebuildIndex_recreatesEntries() throws Exception {
        // Load
        String planDefJson = loadFixture();
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"4.0.0-rebuild\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String responseJson = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());
        int originalCount = triggerIndexRepository.findByIdProtocolDefinitionId(protocolId).size();

        // Rebuild
        mockMvc.perform(post("/v1/protocol-definitions/{id}/rebuild-index", protocolId))
                .andExpect(status().isOk());

        // Verify: same count after rebuild
        assertThat(triggerIndexRepository.findByIdProtocolDefinitionId(protocolId)).hasSize(originalCount);
    }

    @Test
    void deleteProtocol_noInstances_returns204() throws Exception {
        // Load
        String planDefJson = loadFixture();
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"5.0.0-delete\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String responseJson = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        // Delete
        mockMvc.perform(delete("/v1/protocol-definitions/{id}", protocolId))
                .andExpect(status().isNoContent());

        // Verify: gone
        assertThat(protocolDefinitionRepository.findById(protocolId)).isEmpty();
        assertThat(triggerIndexRepository.findByIdProtocolDefinitionId(protocolId)).isEmpty();
    }

    @Test
    void getById_returnsDefinition() throws Exception {
        String planDefJson = loadFixture();
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"", "\"version\": \"6.0.0-get\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        String responseJson = mockMvc.perform(post("/v1/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID protocolId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        mockMvc.perform(get("/v1/protocol-definitions/{id}", protocolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(protocolId.toString()))
                .andExpect(jsonPath("$.canonical").value(
                        "http://openphc.org/PlanDefinition/anc-high-risk|6.0.0-get"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/v1/protocol-definitions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
