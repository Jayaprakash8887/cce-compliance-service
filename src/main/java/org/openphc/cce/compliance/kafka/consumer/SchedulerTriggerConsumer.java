package org.openphc.cce.compliance.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.openphc.cce.compliance.service.StepInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for scheduler-driven step state transitions from the Scheduler Service.
 * Delegates to StepInstanceService and acknowledges only on successful processing.
 */
@Component
public class SchedulerTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTriggerConsumer.class);

    private final StepInstanceService stepInstanceService;
    private final Counter errorCounter;

    public SchedulerTriggerConsumer(StepInstanceService stepInstanceService, MeterRegistry meterRegistry) {
        this.stepInstanceService = stepInstanceService;
        this.errorCounter = meterRegistry.counter("cce.consumer.scheduler.errors");
    }

    @KafkaListener(
            topics = "${cce.kafka.topics.scheduler-triggers}",
            properties = {
                    "spring.json.value.default.type=org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage"
            }
    )
    public void consume(SchedulerTriggerMessage trigger, Acknowledgment ack) {
        MDC.put("correlationId", trigger.getCorrelationid());
        try {
            log.debug("Received scheduler trigger: stepInstanceId={}, transitionType={}",
                    trigger.getStepInstanceId(), trigger.getTransitionType());
            stepInstanceService.applySchedulerTransition(trigger);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process scheduler trigger: stepInstanceId={}, transitionType={}",
                    trigger.getStepInstanceId(), trigger.getTransitionType(), e);
            errorCounter.increment();
            // DO NOT acknowledge — Kafka will redeliver
        } finally {
            MDC.clear();
        }
    }
}
