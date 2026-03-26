package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    private final AuditService auditService;

    public ProtocolInstanceService(ProtocolInstanceRepository protocolInstanceRepository,
                                   AuditService auditService) {
        this.protocolInstanceRepository = protocolInstanceRepository;
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
     */
    public void checkAndCompleteProtocol(UUID instanceId) {
        ProtocolInstance instance = findByIdOrThrow(instanceId);

        if (instance.getStatus() != ProtocolInstanceStatus.ACTIVE) {
            return;
        }

        Set<StepInstance> steps = instance.getSteps();
        if (steps.isEmpty()) {
            return;
        }

        boolean allTerminal = steps.stream()
                .allMatch(step -> TERMINAL_STATES.contains(step.getState()));

        if (allTerminal) {
            instance.setStatus(ProtocolInstanceStatus.COMPLETED);
            protocolInstanceRepository.save(instance);

            log.info("Protocol instance {} completed — all {} steps in terminal state",
                    instanceId, steps.size());
        }
    }

    /**
     * Withdraw a protocol instance — sets status to WITHDRAWN.
     */
    public ProtocolInstance withdrawProtocol(UUID instanceId) {
        ProtocolInstance instance = findByIdOrThrow(instanceId);

        if (instance.getStatus() != ProtocolInstanceStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot withdraw protocol instance in state " + instance.getStatus() + ": " + instanceId);
        }

        instance.setStatus(ProtocolInstanceStatus.WITHDRAWN);
        instance = protocolInstanceRepository.save(instance);

        auditService.audit("COMPLIANCE", "PROTOCOL_WITHDRAWN", "system",
                "ProtocolInstance", instanceId.toString(),
                Map.of("patientId", instance.getPatientId(),
                        "protocolCanonical", instance.getProtocolCanonical()));

        log.info("Withdrew protocol instance {} for patient {}",
                instanceId, instance.getPatientId());

        return instance;
    }

    @Transactional(readOnly = true)
    public ProtocolInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public ProtocolInstance findByIdWithDetails(UUID id) {
        return protocolInstanceRepository.findByIdWithStepsAndDeviations(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol instance not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ProtocolInstance> findByPatientId(String patientId) {
        return protocolInstanceRepository.findByPatientId(patientId);
    }

    @Transactional(readOnly = true)
    public List<ProtocolInstance> findActiveByPatientId(String patientId) {
        return protocolInstanceRepository.findByPatientIdAndStatus(patientId, ProtocolInstanceStatus.ACTIVE);
    }

    private ProtocolInstance findByIdOrThrow(UUID id) {
        return protocolInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol instance not found: " + id));
    }
}
