package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
@Transactional
public class DeviationService {

    private static final Logger log = LoggerFactory.getLogger(DeviationService.class);

    private final DeviationRepository deviationRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DeviationService(DeviationRepository deviationRepository,
                            AuditService auditService,
                            ObjectMapper objectMapper) {
        this.deviationRepository = deviationRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Record a deviation and persist it.
     * Intelligence trigger publishing is not performed here — it will be driven
     * by PlanDefinition-level configuration in a future phase.
     *
     * @return the persisted Deviation entity
     */
    public Deviation recordDeviation(ProtocolInstance protocolInstance, StepInstance step,
                                     DeviationType deviationType, Map<String, Object> metadata) {
        OffsetDateTime detectedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Deviation deviation = Deviation.builder()
                .protocolInstance(protocolInstance)
                .stepInstance(step)
                .deviationType(deviationType)
                .detectedAt(detectedAt)
                .metadata(metadata != null ? objectMapper.valueToTree(metadata) : null)
                .build();

        deviation = deviationRepository.save(deviation);

        // Audit
        auditService.audit("COMPLIANCE", "DEVIATION_DETECTED", "system",
                "Deviation", deviation.getId().toString(),
                Map.of("deviationType", deviationType.name(),
                        "stepInstanceId", step.getId().toString(),
                        "actionId", step.getActionId(),
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "protocolCanonical", protocolInstance.getProtocolCanonical()));

        log.info("Recorded {} deviation: deviationId={}, stepId={}, protocolInstanceId={}",
                deviationType, deviation.getId(), step.getId(), protocolInstance.getId());

        return deviation;
    }
}
