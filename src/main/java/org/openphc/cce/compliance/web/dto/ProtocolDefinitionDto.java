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
public class ProtocolDefinitionDto {

    private UUID id;
    private String url;
    private String version;
    private String canonical;
    private String status;
    private OffsetDateTime loadedAt;
    private JsonNode definition;
}
