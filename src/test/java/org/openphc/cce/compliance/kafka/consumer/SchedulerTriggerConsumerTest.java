package org.openphc.cce.compliance.kafka.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.openphc.cce.compliance.service.StepInstanceService;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerTriggerConsumerTest {

    @Mock private StepInstanceService stepInstanceService;
    @Mock private Acknowledgment acknowledgment;

    private SchedulerTriggerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SchedulerTriggerConsumer(stepInstanceService, new SimpleMeterRegistry());
        MDC.clear();
    }

    @Nested
    class SuccessfulProcessing {

        @Test
        void successfulProcessing_acknowledged() {
            SchedulerTriggerMessage trigger = buildTrigger();

            consumer.consume(trigger, acknowledgment);

            verify(stepInstanceService).applySchedulerTransition(trigger);
            verify(acknowledgment).acknowledge();
        }

        @Test
        void mdcClearedAfterSuccess() {
            SchedulerTriggerMessage trigger = buildTrigger();

            consumer.consume(trigger, acknowledgment);

            assertNull(MDC.get("correlationId"), "MDC should be cleared after processing");
        }
    }

    @Nested
    class FailedProcessing {

        @Test
        void processingException_notAcknowledged() {
            SchedulerTriggerMessage trigger = buildTrigger();
            doThrow(new RuntimeException("Transition failed"))
                    .when(stepInstanceService).applySchedulerTransition(trigger);

            consumer.consume(trigger, acknowledgment);

            verify(stepInstanceService).applySchedulerTransition(trigger);
            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        void mdcClearedAfterFailure() {
            SchedulerTriggerMessage trigger = buildTrigger();
            doThrow(new RuntimeException("Transition failed"))
                    .when(stepInstanceService).applySchedulerTransition(trigger);

            consumer.consume(trigger, acknowledgment);

            assertNull(MDC.get("correlationId"), "MDC should be cleared even after failure");
        }
    }

    @Nested
    class MdcPopulation {

        @Test
        void mdcPopulatedDuringProcessing() {
            SchedulerTriggerMessage trigger = buildTrigger();
            doAnswer(invocation -> {
                assertEquals(trigger.getCorrelationid(), MDC.get("correlationId"));
                return null;
            }).when(stepInstanceService).applySchedulerTransition(trigger);

            consumer.consume(trigger, acknowledgment);

            verify(stepInstanceService).applySchedulerTransition(trigger);
        }
    }

    private SchedulerTriggerMessage buildTrigger() {
        return SchedulerTriggerMessage.builder()
                .stepInstanceId(UUID.randomUUID())
                .transitionType("PENDING_TO_DUE")
                .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid(UUID.randomUUID().toString())
                .build();
    }
}
