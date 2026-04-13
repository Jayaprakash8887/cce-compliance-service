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
public class ActionDefinitionDto {

    private UUID id;
    private String canonicalUrl;
    private String version;
    private String canonical;
    private String name;
    private String title;
    private String status;
    private String actionType;
    private String severity;
    private String target;
    private JsonNode definition;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
