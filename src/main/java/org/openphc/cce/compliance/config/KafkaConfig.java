package org.openphc.cce.compliance.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import org.apache.kafka.clients.admin.NewTopic;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "org.openphc.cce.compliance.kafka.model");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "org.openphc.cce.compliance.kafka.model.CloudEventMessage");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            CommonErrorHandler errorHandler,
            KafkaProperties kafkaProperties) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(kafkaProperties.getListener().getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate,
                                           KafkaRetryProperties retryProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    String dlqTopic = record.topic() + ".dlq";
                    log.error("Sending record to DLQ: topic={}, key={}, offset={}",
                            dlqTopic, record.key(), record.offset(), ex);
                    return new TopicPartition(dlqTopic, -1);
                });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryProperties.getBackoffIntervalMs(), retryProperties.getMaxAttempts()));
        return errorHandler;
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ── Topic declarations ──

    @Bean
    public NewTopic inboundEventsTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getInboundEvents())
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }

    @Bean
    public NewTopic schedulerTriggersTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getSchedulerTriggers())
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }

    @Bean
    public NewTopic intelligenceTriggersTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getIntelligenceTriggers())
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }

    @Bean
    public NewTopic inboundEventsDlqTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getInboundEvents() + ".dlq")
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }

    @Bean
    public NewTopic schedulerTriggersDlqTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getSchedulerTriggers() + ".dlq")
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }
}
