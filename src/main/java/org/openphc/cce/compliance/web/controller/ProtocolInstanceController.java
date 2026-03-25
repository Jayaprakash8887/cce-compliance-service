package org.openphc.cce.compliance.web.controller;

import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.service.ProtocolInstanceService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.ProtocolInstanceDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/protocol-instances")
public class ProtocolInstanceController {

    private final ProtocolInstanceService protocolInstanceService;
    private final DtoMapper dtoMapper;

    public ProtocolInstanceController(ProtocolInstanceService protocolInstanceService,
                                      DtoMapper dtoMapper) {
        this.protocolInstanceService = protocolInstanceService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProtocolInstanceDto> getById(@PathVariable UUID id) {
        ProtocolInstance instance = protocolInstanceService.findById(id);
        return ResponseEntity.ok(dtoMapper.toDto(instance));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ProtocolInstanceDto> withdraw(@PathVariable UUID id) {
        ProtocolInstance instance = protocolInstanceService.withdrawProtocol(id);
        return ResponseEntity.ok(dtoMapper.toDto(instance));
    }
}
