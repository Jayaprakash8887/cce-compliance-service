package org.openphc.cce.compliance.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.service.ComplianceEngine;
import org.openphc.cce.compliance.service.FacilityReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for inbound clinical events from the Collector Service.
 * Each event triggers two independent operations: facility reference capture
 * (best-effort, non-fatal) followed by compliance protocol processing (fatal on failure).
 */
@Component
public class InboundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundEventConsumer.class);

    private final ComplianceEngine complianceEngine;
    private final FacilityReferenceService facilityReferenceService;
    private final Counter errorCounter;

    public InboundEventConsumer(ComplianceEngine complianceEngine,
                                FacilityReferenceService facilityReferenceService,
                                MeterRegistry meterRegistry) {
        this.complianceEngine = complianceEngine;
        this.facilityReferenceService = facilityReferenceService;
        this.errorCounter = meterRegistry.counter("cce.consumer.inbound.errors");
    }

    /**
     * Receives every inbound clinical event from the Collector Service and orchestrates
     * two independent concerns before the Kafka offset is committed:
     *
     * <ol>
     *   <li><b>Facility registration</b> — {@link FacilityReferenceService#registerFacilityIfAbsent}
     *       is called here rather than inside {@link ComplianceEngine} for three reasons:
     *       <ul>
     *         <li>It is a reference-data concern, not a compliance concern — keeping it outside
     *             the engine preserves single responsibility.</li>
     *         <li>{@code ComplianceEngine} is {@code @Transactional}; registering there would
     *             couple the facility INSERT to the compliance transaction and roll it back on
     *             any engine failure. Here, {@code FacilityReferenceService} runs its own
     *             independent transaction and commits regardless of what compliance does.</li>
     *         <li>Registration runs before the engine's duplicate check, so no facility is
     *             missed regardless of how the engine classifies the event.</li>
     *       </ul>
     *       Failures are non-fatal: a warning is logged and compliance processing continues
     *       normally — a transient DB error on the reference table must not cause the event
     *       to be retried or routed to the DLQ.
     *   </li>
     *   <li><b>Compliance processing</b> — delegates to {@link ComplianceEngine#processInboundEvent}
     *       for protocol matching, enrolment, and step progression. Failures here are fatal:
     *       the exception propagates to the container's {@code DefaultErrorHandler}, which
     *       retries with backoff and routes to the DLQ after exhausting retries.</li>
     * </ol>
     */
    @KafkaListener(topics = "${cce.kafka.topics.inbound-events}")
    public void consume(CloudEventMessage event) {
        MDC.put("correlationId", event.getCorrelationid());
        MDC.put("source", event.getSource());
        MDC.put("eventType", event.getType());
        MDC.put("subject", event.getSubject());
        try {
            log.debug("Received inbound event: cloudeventsId={}, source={}", event.getId(), event.getSource());
            try {
                facilityReferenceService.registerFacilityIfAbsent(event);
            } catch (Exception e) {
                log.warn("Facility registration failed for facilityId={} — compliance processing will continue",
                        event.getFacilityid(), e);
            }
            complianceEngine.processInboundEvent(event);
        } catch (Exception e) {
            errorCounter.increment();
            throw e; // Propagate to DefaultErrorHandler for retry + DLQ
        } finally {
            MDC.clear();
        }
    }
}
