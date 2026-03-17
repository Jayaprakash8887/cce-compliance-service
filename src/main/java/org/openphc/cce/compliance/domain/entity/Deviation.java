package org.openphc.cce.compliance.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.openphc.cce.compliance.domain.enums.DeviationType;

import java.time.OffsetDateTime;
import java.util.Map;
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

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) {
            detectedAt = OffsetDateTime.now();
        }
    }
}
