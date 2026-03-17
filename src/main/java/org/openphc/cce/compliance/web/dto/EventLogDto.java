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
public class EventLogDto {

    private UUID id;
    private String cloudeventsId;
    private String source;
    private String subject;
    private String type;
    private OffsetDateTime eventTime;
    private OffsetDateTime receivedAt;
    private String processingStatus;
    private JsonNode data;
    private UUID protocolInstanceId;
    private String actionId;
    private String facilityId;
}
