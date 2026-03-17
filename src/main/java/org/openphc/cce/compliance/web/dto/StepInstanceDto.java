package org.openphc.cce.compliance.web.dto;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstanceDto {

    private UUID id;
    private String actionId;
    private int repeatIndex;
    private String state;
    private OffsetDateTime dueDate;
    private OffsetDateTime overdueDate;
    private OffsetDateTime missedDate;
    private OffsetDateTime completedAt;
    private String completedBySource;
    private String completionStatus;
    private UUID matchedEventId;
}
