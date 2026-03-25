package org.openphc.cce.compliance.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.service.ComplianceEngine;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundEventConsumerTest {

    @Mock private ComplianceEngine complianceEngine;
    @Mock private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InboundEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InboundEventConsumer(complianceEngine, new SimpleMeterRegistry());
        MDC.clear();
    }

    @Nested
    class SuccessfulProcessing {

        @Test
        void successfulProcessing_acknowledged() {
            CloudEventMessage event = buildEvent();

            consumer.consume(event, acknowledgment);

            verify(complianceEngine).processInboundEvent(event);
            verify(acknowledgment).acknowledge();
        }

        @Test
        void mdcClearedAfterSuccess() {
            CloudEventMessage event = buildEvent();

            consumer.consume(event, acknowledgment);

            assertNull(MDC.get("correlationId"), "MDC should be cleared after processing");
            assertNull(MDC.get("source"));
            assertNull(MDC.get("eventType"));
            assertNull(MDC.get("subject"));
        }
    }

    @Nested
    class FailedProcessing {

        @Test
        void processingException_propagatesToErrorHandler() {
            CloudEventMessage event = buildEvent();
            doThrow(new RuntimeException("Processing failed"))
                    .when(complianceEngine).processInboundEvent(event);

            assertThrows(RuntimeException.class, () -> consumer.consume(event, acknowledgment));

            verify(complianceEngine).processInboundEvent(event);
            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        void mdcClearedAfterFailure() {
            CloudEventMessage event = buildEvent();
            doThrow(new RuntimeException("Processing failed"))
                    .when(complianceEngine).processInboundEvent(event);

            assertThrows(RuntimeException.class, () -> consumer.consume(event, acknowledgment));

            assertNull(MDC.get("correlationId"), "MDC should be cleared even after failure");
        }
    }

    @Nested
    class MdcPopulation {

        @Test
        void mdcPopulatedDuringProcessing() {
            CloudEventMessage event = buildEvent();
            doAnswer(invocation -> {
                assertEquals(event.getCorrelationid(), MDC.get("correlationId"));
                assertEquals(event.getSource(), MDC.get("source"));
                assertEquals(event.getType(), MDC.get("eventType"));
                assertEquals(event.getSubject(), MDC.get("subject"));
                return null;
            }).when(complianceEngine).processInboundEvent(event);

            consumer.consume(event, acknowledgment);

            verify(complianceEngine).processInboundEvent(event);
        }
    }

    private CloudEventMessage buildEvent() {
        return CloudEventMessage.builder()
                .id("ce-" + UUID.randomUUID())
                .source("http://test-facility.openphc.org/ehr")
                .type("org.openphc.clinical.observation.created")
                .specversion("1.0")
                .subject("patient-1")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation")))
                .build();
    }
}
