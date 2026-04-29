package org.openphc.cce.compliance.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to cce.intelligence.triggers when an intelligence action fires.
 * Consumed by the CCE Intelligence Service for intelligence channel routing and delivery.
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
    private UUID intelligenceEventId;
    private UUID actionDefinitionId;
    private UUID protocolDefinitionId;
    private String actionType;
    private String severity;
    private String intelligenceChannel;
    private String stepState;
    private String actionId;
    private String protocolCanonical;
    private OffsetDateTime detectedAt;
}
