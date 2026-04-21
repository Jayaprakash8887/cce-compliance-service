package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.service.IntelligenceEventLogService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.IntelligenceEventLogDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/intelligence-events")
public class IntelligenceEventLogController {

    private final IntelligenceEventLogService service;
    private final DtoMapper dtoMapper;

    public IntelligenceEventLogController(IntelligenceEventLogService service, DtoMapper dtoMapper) {
        this.service = service;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public ResponseEntity<List<IntelligenceEventLogDto>> listAll(
            @RequestParam(required = false) UUID protocolInstanceId,
            @RequestParam(required = false) UUID actionDefinitionId,
            @RequestParam(required = false) Boolean published) {
        List<IntelligenceEventLog> events;
        if (protocolInstanceId != null) {
            events = service.findByProtocolInstanceId(protocolInstanceId);
        } else if (actionDefinitionId != null) {
            events = service.findByActionDefinitionId(actionDefinitionId);
        } else if (published != null) {
            events = service.findByPublished(published);
        } else {
            events = service.findAll();
        }
        return ResponseEntity.ok(dtoMapper.toDtoIntelligenceEventLogList(events));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntelligenceEventLogDto> getById(@PathVariable UUID id) {
        IntelligenceEventLog event = service.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(event));
    }
}
