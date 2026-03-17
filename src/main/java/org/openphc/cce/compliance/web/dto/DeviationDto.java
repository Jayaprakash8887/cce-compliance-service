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
public class DeviationDto {

    private UUID id;
    private String deviationType;
    private OffsetDateTime detectedAt;
    private UUID intelligenceEventId;
    private JsonNode metadata;
}
