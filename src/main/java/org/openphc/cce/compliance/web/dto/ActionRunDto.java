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
public class ActionRunDto {

    private UUID id;
    private UUID actionDefinitionId;
    private UUID protocolInstanceId;
    private UUID stepInstanceId;
    private String status;
    private UUID intelligenceEventId;
    private JsonNode outputMetadata;
    private ActionRunContextDto context;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
