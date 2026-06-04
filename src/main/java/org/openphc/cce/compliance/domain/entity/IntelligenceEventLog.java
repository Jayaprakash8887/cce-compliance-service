package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "intelligence_event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntelligenceEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode eventPayload;

    @Column(name = "action_definition_id", nullable = false)
    private UUID actionDefinitionId;

    @Column(name = "protocol_instance_id", nullable = false)
    private UUID protocolInstanceId;

    @Column(name = "step_instance_id")
    private UUID stepInstanceId;

    @Column(name = "deviation_id")
    private UUID deviationId;

    @Column(nullable = false)
    private String subject;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "intelligence_destination", nullable = false)
    private String intelligenceDestination;

    @Column(name = "step_state", nullable = false)
    private String stepState;

    @Column(name = "trigger_reason", nullable = false)
    private String triggerReason;

    @Column(name = "step_action_id")
    private String stepActionId;

    @Column(name = "evaluation_expression", columnDefinition = "text")
    private String evaluationExpression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_context", columnDefinition = "jsonb")
    private JsonNode evaluationContext;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
