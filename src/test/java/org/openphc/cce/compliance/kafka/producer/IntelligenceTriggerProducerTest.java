package org.openphc.cce.compliance.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
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

    private SimpleMeterRegistry meterRegistry;
    private IntelligenceTriggerProducer producer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        KafkaTopicProperties topicProperties = new KafkaTopicProperties();
        topicProperties.setIntelligenceTriggers("cce.intelligence.triggers");
        producer = new IntelligenceTriggerProducer(kafkaTemplate, topicProperties, meterRegistry);
    }

    @Test
    void publish_success_incrementsCounterAndResolvesFuture() throws Exception {
        IntelligenceTriggerEvent event = buildEvent();
        String expectedKey = event.getProtocolInstanceId().toString();

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("cce.intelligence.triggers", 3), 0, 42, 0L, 0, 0);
        SendResult<String, Object> sendResult = new SendResult<>(
                new ProducerRecord<>("cce.intelligence.triggers", expectedKey, event), metadata);

        CompletableFuture<SendResult<String, Object>> completedFuture = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq("cce.intelligence.triggers"), eq(expectedKey), eq(event)))
                .thenReturn(completedFuture);

        CompletableFuture<SendResult<String, Object>> result = producer.publish(event);

        assertNotNull(result);
        SendResult<String, Object> actual = result.get();
        assertEquals(3, actual.getRecordMetadata().partition());
        assertEquals(42, actual.getRecordMetadata().offset());

        // Counter should be incremented on success
        Counter counter = meterRegistry.find("cce.events.intelligence.published").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        // Timer should record the publish duration
        Timer timer = meterRegistry.find("cce.intelligence.publish.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        verify(kafkaTemplate).send("cce.intelligence.triggers", expectedKey, event);
    }

    @Test
    void publish_usesProtocolInstanceIdAsKey() {
        IntelligenceTriggerEvent event = buildEvent();
        String expectedKey = event.getProtocolInstanceId().toString();

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("test failure"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        producer.publish(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("cce.intelligence.triggers"), keyCaptor.capture(), eq(event));
        assertEquals(expectedKey, keyCaptor.getValue());
    }

    @Test
    void publish_failure_doesNotThrowAndDoesNotIncrementCounter() {
        IntelligenceTriggerEvent event = buildEvent();

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture);

        // Should not throw
        CompletableFuture<SendResult<String, Object>> result = assertDoesNotThrow(() -> producer.publish(event));

        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());

        // Counter should NOT be incremented on failure
        Counter counter = meterRegistry.find("cce.events.intelligence.published").counter();
        assertNotNull(counter);
        assertEquals(0.0, counter.count());

        // Timer should still record (measures publish attempt, not just success)
        Timer timer = meterRegistry.find("cce.intelligence.publish.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void publish_multipleSuccessfulEvents_counterIncrementsCorrectly() {
        for (int i = 0; i < 3; i++) {
            IntelligenceTriggerEvent event = buildEvent();
            String key = event.getProtocolInstanceId().toString();

            RecordMetadata metadata = new RecordMetadata(
                    new TopicPartition("cce.intelligence.triggers", 0), 0, i, 0L, 0, 0);
            SendResult<String, Object> sendResult = new SendResult<>(
                    new ProducerRecord<>("cce.intelligence.triggers", key, event), metadata);

            when(kafkaTemplate.send(eq("cce.intelligence.triggers"), eq(key), eq(event)))
                    .thenReturn(CompletableFuture.completedFuture(sendResult));

            producer.publish(event);
        }

        Counter counter = meterRegistry.find("cce.events.intelligence.published").counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count());
    }

    private IntelligenceTriggerEvent buildEvent() {
        return IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .type("cce.intelligence.trigger")
                .subject("patient-1")
                .protocolInstanceId(UUID.randomUUID())
                .stepInstanceId(UUID.randomUUID())
                .deviationId(UUID.randomUUID())
                .deviationType("overdue")
                .stepState("OVERDUE")
                .actionId("blood-pressure-check")
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .facilityId("facility-1")
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
