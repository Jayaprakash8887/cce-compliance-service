package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class DeviationService {

    private static final Logger log = LoggerFactory.getLogger(DeviationService.class);

    private final DeviationRepository deviationRepository;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DeviationService(DeviationRepository deviationRepository,
                            IntelligenceTriggerProducer intelligenceTriggerProducer,
                            AuditService auditService,
                            ObjectMapper objectMapper) {
        this.deviationRepository = deviationRepository;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Record a deviation, publish an intelligence trigger event to Kafka,
     * and store the intelligence event ID on the deviation.
     *
     * @return the persisted Deviation entity with intelligenceEventId set
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

        // Build and publish intelligence trigger event
        UUID intelligenceEventId = UUID.randomUUID();
        IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                .id(intelligenceEventId)
                .type("cce.compliance.deviation." + deviationType.name().toLowerCase())
                .subject(protocolInstance.getPatientId())
                .protocolInstanceId(protocolInstance.getId())
                .stepInstanceId(step.getId())
                .deviationId(deviation.getId())
                .deviationType(deviationType.name())
                .stepState(step.getState().name())
                .actionId(step.getActionId())
                .protocolCanonical(protocolInstance.getProtocolCanonical())
                .detectedAt(detectedAt)
                .metadata(metadata != null ? objectMapper.valueToTree(metadata) : null)
                .build();

        intelligenceTriggerProducer.publishTrigger(event);

        // Store the intelligence event ID on the deviation
        deviation.setIntelligenceEventId(intelligenceEventId);
        deviation = deviationRepository.save(deviation);

        // Audit
        auditService.audit("COMPLIANCE", "DEVIATION_DETECTED", "system",
                "Deviation", deviation.getId().toString(),
                Map.of("deviationType", deviationType.name(),
                        "stepInstanceId", step.getId().toString(),
                        "actionId", step.getActionId(),
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "protocolCanonical", protocolInstance.getProtocolCanonical(),
                        "intelligenceEventId", intelligenceEventId.toString()));

        log.info("Recorded {} deviation: deviationId={}, stepId={}, protocolInstanceId={}, intelligenceEventId={}",
                deviationType, deviation.getId(), step.getId(), protocolInstance.getId(), intelligenceEventId);

        return deviation;
    }
}
