package org.openphc.cce.compliance.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionRunContextDto {

    private UUID id;
    private UUID actionRunId;
    private UUID deviationId;
    private String triggerReason;
    private String stepActionId;
    private String evaluationExpression;
    private JsonNode evaluationContext;
    private OffsetDateTime createdAt;
}
