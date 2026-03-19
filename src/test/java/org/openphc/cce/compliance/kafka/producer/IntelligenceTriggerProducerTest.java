package org.openphc.cce.compliance.kafka.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.config.KafkaTopicProperties;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntelligenceTriggerProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaTopicProperties topicProperties;
    private MeterRegistry meterRegistry;
    private IntelligenceTriggerProducer producer;

    @BeforeEach
    void setUp() {
        topicProperties = new KafkaTopicProperties();
        topicProperties.setIntelligenceTriggers("cce.intelligence.triggers");
        meterRegistry = new SimpleMeterRegistry();
        producer = new IntelligenceTriggerProducer(kafkaTemplate, topicProperties, meterRegistry);
    }

    @Test
    void publishTrigger_sendsToCorrectTopicWithProtocolInstanceIdAsKey() {
        IntelligenceTriggerEvent event = buildEvent();
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        producer.publishTrigger(event);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eq(event));

        assertEquals("cce.intelligence.triggers", topicCaptor.getValue());
        assertEquals(event.getProtocolInstanceId().toString(), keyCaptor.getValue());
    }

    @Test
    void publishTrigger_onSuccess_incrementsCounter() {
        IntelligenceTriggerEvent event = buildEvent();
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        producer.publishTrigger(event);

        // Simulate successful send
        RecordMetadata recordMetadata = new RecordMetadata(
                new TopicPartition("cce.intelligence.triggers", 0), 0, 0, 0L, 0, 0);
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);
        future.complete(sendResult);

        Counter counter = meterRegistry.find("cce.events.intelligence.published").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void publishTrigger_onFailure_doesNotIncrementCounter() {
        IntelligenceTriggerEvent event = buildEvent();
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        producer.publishTrigger(event);

        // Simulate failure
        future.completeExceptionally(new RuntimeException("Kafka broker unavailable"));

        Counter counter = meterRegistry.find("cce.events.intelligence.published").counter();
        assertNotNull(counter);
        assertEquals(0.0, counter.count());
    }

    @Test
    void publishTrigger_withNullProtocolInstanceId_sendsNullKey() {
        IntelligenceTriggerEvent event = buildEvent();
        event.setProtocolInstanceId(null);
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), isNull(), any())).thenReturn(future);

        producer.publishTrigger(event);

        verify(kafkaTemplate).send(eq("cce.intelligence.triggers"), isNull(), eq(event));
    }

    // --- Helper ---

    private IntelligenceTriggerEvent buildEvent() {
        return IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .type("cce.compliance.deviation.overdue")
                .subject("patient-123")
                .protocolInstanceId(UUID.randomUUID())
                .stepInstanceId(UUID.randomUUID())
                .deviationId(UUID.randomUUID())
                .deviationType("OVERDUE")
                .stepState("OVERDUE")
                .actionId("bp-check")
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
