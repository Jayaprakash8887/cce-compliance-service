package org.openphc.cce.compliance.web.dto;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProtocolInstanceDto {

    private UUID id;
    private String patientId;
    private String protocolCanonical;
    private UUID protocolDefinitionId;
    private String status;
    private OffsetDateTime enrolledAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<StepInstanceDto> steps;
    private List<DeviationDto> deviations;
}
