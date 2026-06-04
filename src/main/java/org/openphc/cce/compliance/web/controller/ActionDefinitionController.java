package org.openphc.cce.compliance.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.openphc.cce.compliance.service.ActionDefinitionService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.ActionDefinitionDto;
import org.openphc.cce.compliance.web.dto.CreateActionDefinitionRequest;
import org.openphc.cce.compliance.web.dto.UpdateActionDefinitionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/action-definitions")
public class ActionDefinitionController {

    private final ActionDefinitionService actionDefinitionService;
    private final DtoMapper dtoMapper;
    private final ObjectMapper objectMapper;

    public ActionDefinitionController(ActionDefinitionService actionDefinitionService,
                                      DtoMapper dtoMapper,
                                      ObjectMapper objectMapper) {
        this.actionDefinitionService = actionDefinitionService;
        this.dtoMapper = dtoMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ActionDefinitionDto> create(
            @Valid @RequestBody CreateActionDefinitionRequest request) {
        JsonNode definition = parseJson(request.getDefinitionJson());
        ActionDefinition actionDef = actionDefinitionService.createActionDefinition(definition);
        return ResponseEntity.status(HttpStatus.CREATED).body(dtoMapper.toDto(actionDef));
    }

    @GetMapping
    public ResponseEntity<Page<ActionDefinitionDto>> listAll(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        Page<ActionDefinition> definitions;
        if (status != null) {
            ActionDefinitionStatus statusEnum = ActionDefinitionStatus.valueOf(status);
            definitions = actionDefinitionService.findByStatus(statusEnum, pageable);
        } else {
            definitions = actionDefinitionService.findAll(pageable);
        }
        return ResponseEntity.ok(definitions.map(dtoMapper::toDto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionDefinitionDto> getById(@PathVariable UUID id) {
        ActionDefinition actionDef = actionDefinitionService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(actionDef));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ActionDefinitionDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateActionDefinitionRequest request) {
        JsonNode definition = parseJson(request.getDefinitionJson());
        ActionDefinition actionDef = actionDefinitionService.updateActionDefinition(id, definition);
        return ResponseEntity.ok(dtoMapper.toDto(actionDef));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<ActionDefinitionDto> retire(@PathVariable UUID id) {
        ActionDefinition actionDef = actionDefinitionService.retireActionDefinition(id);
        return ResponseEntity.ok(dtoMapper.toDto(actionDef));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        actionDefinitionService.deleteActionDefinition(id);
        return ResponseEntity.noContent().build();
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }
}
