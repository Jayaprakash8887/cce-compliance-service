package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.compliance.domain.enums.DeviationType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deviation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Deviation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_instance_id", nullable = false)
    private ProtocolInstance protocolInstance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private StepInstance stepInstance;

    @Enumerated(EnumType.STRING)
    @Column(name = "deviation_type", nullable = false)
    private DeviationType deviationType;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "intelligence_event_id")
    private UUID intelligenceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) {
            detectedAt = OffsetDateTime.now();
        }
    }
}
