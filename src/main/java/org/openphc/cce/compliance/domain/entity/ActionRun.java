package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "action_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_definition_id", nullable = false)
    private ActionDefinition actionDefinition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_instance_id", nullable = false)
    private ProtocolInstance protocolInstance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_instance_id")
    private StepInstance stepInstance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionRunStatus status;

    @Column(name = "intelligence_event_id")
    private UUID intelligenceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_metadata", columnDefinition = "jsonb")
    private JsonNode outputMetadata;

    @OneToOne(mappedBy = "actionRun", fetch = FetchType.LAZY)
    private ActionRunContext context;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
