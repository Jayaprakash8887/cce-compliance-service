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
import java.util.Optional;
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

    /**
     * Outcome of a deviation creation request.
     *
     * @param deviation the deviation — the newly inserted row, or the pre-existing one
     *                  when a deviation of this type already existed for the step.
     * @param created   {@code true} if a new row was inserted; {@code false} if a
     *                  deviation of this type already existed (redelivered or concurrent
     *                  trigger). Callers must only fire one-time side effects — e.g.
     *                  intelligence evaluation — when this is {@code true}.
     */
    public record DeviationResult(Deviation deviation, boolean created) {}

    /**
     * Create a deviation with auto-enriched metadata based on deviation type.
     * Delegates to recordDeviation after building metadata.
     */
    public DeviationResult createDeviation(StepInstance step, DeviationType deviationType) {
        return createDeviation(step, deviationType, null);
    }

    /**
     * Create a deviation with auto-enriched metadata plus additional caller-supplied metadata.
     */
    public DeviationResult createDeviation(StepInstance step, DeviationType deviationType,
                                     Map<String, Object> additionalMetadata) {
        ProtocolInstance protocolInstance = step.getProtocolInstance();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> metadata = new LinkedHashMap<>();
        // No enrichment branch for DeviationType.OVERDUE: it is legacy and no longer raised.
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
     * @return a {@link DeviationResult} — {@code created=true} with the new row, or
     *         {@code created=false} with the pre-existing deviation for this (step, type).
     */
    private DeviationResult recordDeviation(ProtocolInstance protocolInstance, StepInstance step,
                                     DeviationType deviationType, Map<String, Object> metadata) {
        // Idempotency guard: a step has at most one deviation per type. A redelivered
        // scheduler trigger (Kafka is at-least-once) or a concurrent consumer thread can
        // reach this point for the same (step, type); return the existing deviation instead
        // of inserting a duplicate. The deviation_step_type_key unique constraint is the
        // ultimate backstop if two inserts genuinely race past this check.
        Optional<Deviation> existing = deviationRepository
                .findByStepInstanceIdAndDeviationType(step.getId(), deviationType);
        if (existing.isPresent()) {
            log.debug("Deviation {} already exists for step {} — skipping duplicate creation",
                    deviationType, step.getId());
            return new DeviationResult(existing.get(), false);
        }

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

        return new DeviationResult(deviation, true);
    }
}
