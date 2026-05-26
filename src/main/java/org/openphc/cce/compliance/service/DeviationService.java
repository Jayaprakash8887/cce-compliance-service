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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public List<Deviation> findByProtocolInstanceId(UUID protocolInstanceId) {
        return deviationRepository.findByProtocolInstanceId(protocolInstanceId);
    }

    /**
     * Create a deviation with auto-enriched metadata based on deviation type.
     * Delegates to recordDeviation after building metadata.
     */
    public Deviation createDeviation(StepInstance step, DeviationType deviationType) {
        return createDeviation(step, deviationType, null);
    }

    /**
     * Create a deviation with auto-enriched metadata plus additional caller-supplied metadata.
     */
    public Deviation createDeviation(StepInstance step, DeviationType deviationType,
                                     Map<String, Object> additionalMetadata) {
        ProtocolInstance protocolInstance = step.getProtocolInstance();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (deviationType == DeviationType.OVERDUE && step.getDueDate() != null) {
            metadata.put("daysOverdue",
                    Duration.between(step.getDueDate(), now).toDays());
        }
        if (deviationType == DeviationType.MISSED && step.getMissedDate() != null) {
            metadata.put("daysPastMissedDate",
                    Duration.between(step.getMissedDate(), now).toDays());
        }
        if (additionalMetadata != null) {
            metadata.putAll(additionalMetadata);
        }

        return recordDeviation(protocolInstance, step, deviationType,
                metadata.isEmpty() ? null : metadata);
    }

    /**
     * Record a deviation and persist it.
     * Intelligence trigger publishing is not performed here — it will be driven
     * by PlanDefinition-level configuration in a future phase.
     *
     * @return the persisted Deviation entity
     */
    private Deviation recordDeviation(ProtocolInstance protocolInstance, StepInstance step,
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
