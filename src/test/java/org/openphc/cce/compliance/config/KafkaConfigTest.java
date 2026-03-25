package org.openphc.cce.compliance.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KafkaConfigTest {

    @Test
    void errorHandler_returnsDefaultErrorHandler() {
        KafkaConfig config = new KafkaConfig();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaRetryProperties retryProperties = new KafkaRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setBackoffIntervalMs(1000);

        CommonErrorHandler errorHandler = config.errorHandler(kafkaTemplate, retryProperties);

        assertNotNull(errorHandler);
        assertInstanceOf(DefaultErrorHandler.class, errorHandler);
    }
}
