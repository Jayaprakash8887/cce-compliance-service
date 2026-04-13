package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.openphc.cce.compliance.service.ActionRunService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.ActionRunDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/action-runs")
public class ActionRunController {

    private final ActionRunService actionRunService;
    private final DtoMapper dtoMapper;

    public ActionRunController(ActionRunService actionRunService, DtoMapper dtoMapper) {
        this.actionRunService = actionRunService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public ResponseEntity<List<ActionRunDto>> listAll(
            @RequestParam(required = false) UUID protocolInstanceId,
            @RequestParam(required = false) UUID actionDefinitionId,
            @RequestParam(required = false) String status) {
        List<ActionRun> actionRuns;
        if (protocolInstanceId != null) {
            actionRuns = actionRunService.findByProtocolInstanceId(protocolInstanceId);
        } else if (actionDefinitionId != null) {
            actionRuns = actionRunService.findByActionDefinitionId(actionDefinitionId);
        } else if (status != null) {
            ActionRunStatus statusEnum = ActionRunStatus.valueOf(status);
            actionRuns = actionRunService.findByStatus(statusEnum);
        } else {
            actionRuns = actionRunService.findAll();
        }
        return ResponseEntity.ok(dtoMapper.toDtoActionRunList(actionRuns));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionRunDto> getById(@PathVariable UUID id) {
        ActionRun actionRun = actionRunService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(actionRun));
    }
}
