package org.openphc.cce.compliance.kafka.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.openphc.cce.compliance.config.KafkaTopicProperties;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class IntelligenceTriggerProducer {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTriggerProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;
    private final Counter publishedCounter;
    private final Timer publishDurationTimer;

    public IntelligenceTriggerProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                       KafkaTopicProperties topicProperties,
                                       MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topicProperties.getIntelligenceTriggers();
        this.publishedCounter = meterRegistry.counter("cce.events.intelligence.published");
        this.publishDurationTimer = meterRegistry.timer("cce.intelligence.publish.duration");
    }

    /**
     * Publish an intelligence trigger event to Kafka.
     * Fire-and-forget: logs failures but does not throw, so the main transaction is not affected.
     *
     * @param event the intelligence trigger event
     * @return a CompletableFuture with the send result
     */
    public CompletableFuture<SendResult<String, Object>> publish(IntelligenceTriggerEvent event) {
        String key = event.getActionRunId().toString();

        Timer.Sample sample = Timer.start();
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            sample.stop(publishDurationTimer);
            if (ex == null) {
                publishedCounter.increment();
                log.info("Published intelligence trigger event: id={}, actionRunId={}, topic={}, partition={}, offset={}",
                        event.getId(), key, topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish intelligence trigger event: id={}, actionRunId={}, topic={}",
                        event.getId(), key, topic, ex);
            }
        });

        return future;
    }
}
