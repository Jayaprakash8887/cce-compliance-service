package org.openphc.cce.compliance.web.controller;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.service.DeviationService;
import org.openphc.cce.compliance.service.EventLogService;
import org.openphc.cce.compliance.service.ProtocolInstanceService;
import org.openphc.cce.compliance.service.StepInstanceService;
import org.openphc.cce.compliance.web.DtoMapper;
import org.openphc.cce.compliance.web.dto.DeviationDto;
import org.openphc.cce.compliance.web.dto.EventLogDto;
import org.openphc.cce.compliance.web.dto.ProtocolInstanceDto;
import org.openphc.cce.compliance.web.dto.StepInstanceDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance/patients")
public class PatientTrackingController {

    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceService stepInstanceService;
    private final DeviationService deviationService;
    private final EventLogService eventLogService;
    private final DtoMapper dtoMapper;

    public PatientTrackingController(ProtocolInstanceService protocolInstanceService,
                                     StepInstanceService stepInstanceService,
                                     DeviationService deviationService,
                                     EventLogService eventLogService,
                                     DtoMapper dtoMapper) {
        this.protocolInstanceService = protocolInstanceService;
        this.stepInstanceService = stepInstanceService;
        this.deviationService = deviationService;
        this.eventLogService = eventLogService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping("/{patientId}/protocol-instances")
    public ResponseEntity<List<ProtocolInstanceDto>> listProtocols(
            @PathVariable String patientId) {
        List<ProtocolInstance> instances = protocolInstanceService.findByPatientId(patientId);
        return ResponseEntity.ok(dtoMapper.toSummaryDtoList(instances));
    }

    @GetMapping("/{patientId}/protocol-instances/active")
    public ResponseEntity<List<ProtocolInstanceDto>> listActiveProtocols(
            @PathVariable String patientId) {
        List<ProtocolInstance> instances = protocolInstanceService.findActiveByPatientId(patientId);
        return ResponseEntity.ok(dtoMapper.toSummaryDtoList(instances));
    }

    @GetMapping("/{patientId}/protocol-instances/{protocolInstanceId}")
    public ResponseEntity<ProtocolInstanceDto> getProtocolDetail(
            @PathVariable String patientId, @PathVariable UUID protocolInstanceId) {
        ProtocolInstance instance = protocolInstanceService.findByIdWithDetails(protocolInstanceId);
        if (!instance.getPatientId().equals(patientId)) {
            throw new EntityNotFoundException(
                    "Protocol instance " + protocolInstanceId + " not found for patient " + patientId);
        }
        return ResponseEntity.ok(dtoMapper.toDto(instance));
    }

    @GetMapping("/{patientId}/protocol-instances/{protocolInstanceId}/steps")
    public ResponseEntity<List<StepInstanceDto>> listSteps(
            @PathVariable String patientId, @PathVariable UUID protocolInstanceId) {
        getInstanceForPatient(patientId, protocolInstanceId);
        List<StepInstance> steps = stepInstanceService.findByProtocolInstanceId(protocolInstanceId);
        return ResponseEntity.ok(dtoMapper.toDtoStepList(steps));
    }

    @GetMapping("/{patientId}/protocol-instances/{protocolInstanceId}/deviations")
    public ResponseEntity<List<DeviationDto>> listDeviations(
            @PathVariable String patientId, @PathVariable UUID protocolInstanceId) {
        getInstanceForPatient(patientId, protocolInstanceId);
        List<Deviation> deviations = deviationService.findByProtocolInstanceId(protocolInstanceId);
        return ResponseEntity.ok(dtoMapper.toDtoDeviationList(deviations));
    }

    @GetMapping("/{patientId}/events")
    public ResponseEntity<Page<EventLogDto>> listEvents(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EventLog> events = eventLogService.findByPatientId(patientId, PageRequest.of(page, size));
        return ResponseEntity.ok(dtoMapper.toDtoEventLogPage(events));
    }

    private ProtocolInstance getInstanceForPatient(String patientId, UUID protocolInstanceId) {
        ProtocolInstance instance = protocolInstanceService.findById(protocolInstanceId);
        if (!instance.getPatientId().equals(patientId)) {
            throw new EntityNotFoundException(
                    "Protocol instance " + protocolInstanceId + " not found for patient " + patientId);
        }
        return instance;
    }
}
