package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
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
 * Integration tests for ActionDefinition CRUD API endpoints.
 */
class ActionDefinitionApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActionDefinitionRepository actionDefinitionRepository;

    private String loadActivityDefinition() throws Exception {
        return Files.readString(
                Path.of("src/integrationTest/resources/fhir/activity-definition-escalation.json"));
    }

    private String loadActivityDefinitionWithVersion(String version) throws Exception {
        return loadActivityDefinition().replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"");
    }

    private String createRequestBody(String definitionJson) throws Exception {
        return objectMapper.writeValueAsString(Map.of("definitionJson", definitionJson));
    }

    // ── POST ──

    @Test
    void createActionDefinition_returns201() throws Exception {
        String version = "1.0.0-create-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.canonicalUrl").value("http://openphc.org/ActivityDefinition/escalation-alert"))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.actionType").value("CommunicationRequest"))
                .andExpect(jsonPath("$.name").value("EscalationAlert"));
    }

    @Test
    void createDuplicateActionDefinition_returns400() throws Exception {
        String version = "1.0.0-dup-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ── GET list ──

    @Test
    void listActionDefinitions_includesCreated() throws Exception {
        String version = "1.0.0-list-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/compliance/action-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.version=='" + version + "')]").exists());
    }

    @Test
    void listActionDefinitions_filterByStatus() throws Exception {
        String version = "1.0.0-filter-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/compliance/action-definitions")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.version=='" + version + "')]").exists());

        mockMvc.perform(get("/v1/compliance/action-definitions")
                        .param("status", "RETIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.version=='" + version + "')]").doesNotExist());
    }

    // ── GET by ID ──

    @Test
    void getById_returnsCorrectDefinition() throws Exception {
        String version = "1.0.0-getid-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        String responseJson = mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        mockMvc.perform(get("/v1/compliance/action-definitions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.canonical").value(
                        "http://openphc.org/ActivityDefinition/escalation-alert|" + version));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/v1/compliance/action-definitions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── PUT ──

    @Test
    void updateActionDefinition_returnsUpdated() throws Exception {
        String version = "1.0.0-update-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        String responseJson = mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        // Update: change title
        String updatedDefJson = defJson.replace("\"title\": \"Escalation Alert Action\"",
                "\"title\": \"Updated Escalation Alert\"");
        String updateBody = createRequestBody(updatedDefJson);

        mockMvc.perform(put("/v1/compliance/action-definitions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Escalation Alert"));
    }

    // ── POST retire ──

    @Test
    void retireActionDefinition_setsStatusRetired() throws Exception {
        String version = "1.0.0-retire-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        String responseJson = mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        mockMvc.perform(post("/v1/compliance/action-definitions/{id}/retire", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        // Verify in DB
        ActionDefinition retired = actionDefinitionRepository.findById(id).orElseThrow();
        assertThat(retired.getStatus().name()).isEqualTo("RETIRED");
    }

    // ── DELETE ──

    @Test
    void deleteActionDefinition_returns204() throws Exception {
        String version = "1.0.0-del-" + UUID.randomUUID().toString().substring(0, 8);
        String defJson = loadActivityDefinitionWithVersion(version);
        String requestBody = createRequestBody(defJson);

        String responseJson = mockMvc.perform(post("/v1/compliance/action-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        mockMvc.perform(delete("/v1/compliance/action-definitions/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(actionDefinitionRepository.findById(id)).isEmpty();
    }
}
