package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only state-transition history for {@link StepInstance}.
 * One row is written by the application on each state/completion-status transition
 * (creation and every subsequent change). Rows are only ever INSERTed — never UPDATEd or
 * DELETEd — so the table is a faithful, point-in-time-reconstructible record and a clean
 * CDC source.
 *
 * @see org.openphc.cce.compliance.service.StateTransitionHistoryService
 */
@Entity
@Table(name = "step_instance_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_instance_id", nullable = false)
    private UUID stepInstanceId;

    /** Denormalized (immutable on the base row) for backfill grouping. */
    @Column(name = "protocol_instance_id", nullable = false)
    private UUID protocolInstanceId;

    /** Recorded as-is from step_instance.state (already validated there). */
    @Column(name = "state", nullable = false)
    private String state;

    /** Recorded as-is from step_instance.completion_status (null until the step completes). */
    @Column(name = "completion_status")
    private String completionStatus;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
