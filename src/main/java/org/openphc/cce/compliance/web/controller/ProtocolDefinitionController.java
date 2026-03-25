package org.openphc.cce.compliance.web.controller;

import jakarta.validation.Valid;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.service.ProtocolDefinitionService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.LoadProtocolRequest;
import org.openphc.cce.compliance.web.dto.ProtocolDefinitionDto;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/protocol-definitions")
public class ProtocolDefinitionController {

    private final ProtocolDefinitionService protocolDefinitionService;
    private final DtoMapper dtoMapper;

    public ProtocolDefinitionController(ProtocolDefinitionService protocolDefinitionService,
                                        DtoMapper dtoMapper) {
        this.protocolDefinitionService = protocolDefinitionService;
        this.dtoMapper = dtoMapper;
    }

    @PostMapping
    public ResponseEntity<ProtocolDefinitionDto> loadProtocol(
            @Valid @RequestBody LoadProtocolRequest request) {
        ProtocolDefinition protocolDef = protocolDefinitionService.loadProtocol(
                request.getPlanDefinitionJson());
        return ResponseEntity.status(HttpStatus.CREATED).body(dtoMapper.toDto(protocolDef));
    }

    @GetMapping
    public ResponseEntity<List<ProtocolDefinitionDto>> listAll() {
        List<ProtocolDefinition> definitions = protocolDefinitionService.findAll();
        return ResponseEntity.ok(dtoMapper.toDtoProtocolDefinitionList(definitions));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProtocolDefinitionDto> getById(@PathVariable UUID id) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(protocolDef));
    }

    @GetMapping("/by-url")
    public ResponseEntity<List<ProtocolDefinitionDto>> getByUrl(@RequestParam String url) {
        List<ProtocolDefinition> definitions = protocolDefinitionService.findByUrl(url);
        return ResponseEntity.ok(dtoMapper.toDtoProtocolDefinitionList(definitions));
    }

    @GetMapping("/by-url-version")
    public ResponseEntity<ProtocolDefinitionDto> getByUrlAndVersion(
            @RequestParam String url, @RequestParam String version) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findByUrlAndVersion(url, version)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Protocol definition not found for url=" + url + ", version=" + version));
        return ResponseEntity.ok(dtoMapper.toDto(protocolDef));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<ProtocolDefinitionDto> retire(@PathVariable UUID id) {
        ProtocolDefinition protocolDef = protocolDefinitionService.retireProtocol(id);
        return ResponseEntity.ok(dtoMapper.toDto(protocolDef));
    }

    @PostMapping("/{id}/rebuild-index")
    public ResponseEntity<Void> rebuildIndex(@PathVariable UUID id) {
        protocolDefinitionService.rebuildIndex(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        protocolDefinitionService.deleteProtocol(id);
        return ResponseEntity.noContent().build();
    }
}
