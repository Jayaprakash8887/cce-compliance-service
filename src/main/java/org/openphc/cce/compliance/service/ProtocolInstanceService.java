package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ProtocolInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolInstanceService.class);

    private static final Set<StepState> TERMINAL_STATES = Set.of(
            StepState.COMPLETED, StepState.MISSED, StepState.SKIPPED);

    private final ProtocolInstanceRepository protocolInstanceRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final AuditService auditService;

    public ProtocolInstanceService(ProtocolInstanceRepository protocolInstanceRepository,
                                   StepInstanceRepository stepInstanceRepository,
                                   AuditService auditService) {
        this.protocolInstanceRepository = protocolInstanceRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.auditService = auditService;
    }

    /**
     * Enroll a patient in a protocol. If the patient already has an ACTIVE instance
     * for the same protocol definition, the existing instance is returned (skip re-enrollment).
     */
    public ProtocolInstance enrollPatient(String patientId, ProtocolDefinition protocolDef,
                                         OffsetDateTime enrolledAt) {
        // Check for existing ACTIVE enrollment
        Optional<ProtocolInstance> existing = protocolInstanceRepository
                .findByPatientIdAndProtocolDefinitionIdAndStatus(
                        patientId, protocolDef.getId(), ProtocolInstanceStatus.ACTIVE);

        if (existing.isPresent()) {
            log.info("Patient {} already enrolled in protocol {} — skipping",
                    patientId, protocolDef.getCanonical());
            return existing.get();
        }

        ProtocolInstance instance = ProtocolInstance.builder()
                .patientId(patientId)
                .protocolDefinition(protocolDef)
                .protocolCanonical(protocolDef.getCanonical())
                .enrolledAt(enrolledAt)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        instance = protocolInstanceRepository.save(instance);

        auditService.audit("COMPLIANCE", "PROTOCOL_ENROLLED", "system",
                "ProtocolInstance", instance.getId().toString(),
                Map.of("patientId", patientId,
                        "protocolCanonical", protocolDef.getCanonical(),
                        "protocolDefinitionId", protocolDef.getId().toString()));

        log.info("Enrolled patient {} in protocol {} (instanceId={})",
                patientId, protocolDef.getCanonical(), instance.getId());

        return instance;
    }

    /**
     * Check if all steps in a protocol instance have reached terminal states.
     * If so, automatically set the protocol instance status to COMPLETED.
     * Uses count queries to avoid loading the entire step collection.
     */
    public void checkAndCompleteProtocol(UUID instanceId) {
        ProtocolInstance instance = findByIdOrThrow(instanceId);

        if (instance.getStatus() != ProtocolInstanceStatus.ACTIVE) {
            return;
        }

        long totalSteps = stepInstanceRepository.countByProtocolInstanceId(instanceId);
        if (totalSteps == 0) {
            return;
        }

        long nonTerminalSteps = stepInstanceRepository.countNonTerminalSteps(instanceId, TERMINAL_STATES);

        if (nonTerminalSteps == 0) {
            instance.setStatus(ProtocolInstanceStatus.COMPLETED);
            protocolInstanceRepository.save(instance);

            log.info("Protocol instance {} completed — all {} steps in terminal state",
                    instanceId, totalSteps);
        }
    }

    @Transactional(readOnly = true)
    public ProtocolInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    private ProtocolInstance findByIdOrThrow(UUID id) {
        return protocolInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol instance not found: " + id));
    }
}
