package org.openphc.cce.compliance.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to cce.intelligence.triggers when an intelligence action fires.
 * Consumed by the CCE Intelligence Service for target subscription routing and delivery.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntelligenceTriggerEvent {

    private UUID id;
    private String subject;
    private UUID actionRunId;
    private UUID protocolInstanceId;
    private UUID stepInstanceId;
    private String deviationType;
    private String stepState;
    private String actionId;
    private String protocolCanonical;
    private OffsetDateTime detectedAt;
}
