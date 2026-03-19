package org.openphc.cce.compliance.kafka.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.config.KafkaTopicProperties;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class IntelligenceTriggerProducer {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTriggerProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final Counter publishedCounter;

    public IntelligenceTriggerProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                       KafkaTopicProperties topicProperties,
                                       MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicProperties = topicProperties;
        this.publishedCounter = Counter.builder("cce.events.intelligence.published")
                .description("Intelligence trigger events published")
                .register(meterRegistry);
    }

    /**
     * Publish an intelligence trigger event to the cce.intelligence.triggers topic.
     * Uses protocolInstanceId as the Kafka key for partition locality.
     */
    public void publishTrigger(IntelligenceTriggerEvent event) {
        String topic = topicProperties.getIntelligenceTriggers();
        String key = event.getProtocolInstanceId() != null
                ? event.getProtocolInstanceId().toString() : null;

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish intelligence trigger to topic={}, key={}, eventId={}",
                                topic, key, event.getId(), ex);
                    } else {
                        publishedCounter.increment();
                        log.info("Published intelligence trigger: eventId={}, topic={}, partition={}, offset={}",
                                event.getId(), topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
