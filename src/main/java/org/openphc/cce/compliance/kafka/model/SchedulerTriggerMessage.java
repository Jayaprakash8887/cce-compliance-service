package org.openphc.cce.compliance.kafka.model;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Timer-based trigger from the CCE Scheduler Service for step state transitions.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchedulerTriggerMessage {

    private UUID stepInstanceId;
    private String transitionType;
    private OffsetDateTime triggeredAt;
    private String correlationid;
}
