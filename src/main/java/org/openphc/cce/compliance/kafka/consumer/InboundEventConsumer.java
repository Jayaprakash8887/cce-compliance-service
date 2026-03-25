package org.openphc.cce.compliance.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.service.ComplianceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for inbound clinical events from the Collector Service.
 * Delegates to ComplianceEngine and acknowledges only on successful processing.
 * On failure, exceptions propagate to the container's DefaultErrorHandler which
 * retries with backoff and routes to DLQ after exhausting retries.
 */
@Component
public class InboundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundEventConsumer.class);

    private final ComplianceEngine complianceEngine;
    private final Counter errorCounter;

    public InboundEventConsumer(ComplianceEngine complianceEngine, MeterRegistry meterRegistry) {
        this.complianceEngine = complianceEngine;
        this.errorCounter = meterRegistry.counter("cce.consumer.inbound.errors");
    }

    @KafkaListener(topics = "${cce.kafka.topics.inbound-events}")
    public void consume(CloudEventMessage event, Acknowledgment ack) {
        MDC.put("correlationId", event.getCorrelationid());
        MDC.put("source", event.getSource());
        MDC.put("eventType", event.getType());
        MDC.put("subject", event.getSubject());
        try {
            log.debug("Received inbound event: cloudeventsId={}, source={}", event.getId(), event.getSource());
            complianceEngine.processInboundEvent(event);
            ack.acknowledge();
        } catch (Exception e) {
            errorCounter.increment();
            throw e; // Propagate to DefaultErrorHandler for retry + DLQ
        } finally {
            MDC.clear();
        }
    }
}
