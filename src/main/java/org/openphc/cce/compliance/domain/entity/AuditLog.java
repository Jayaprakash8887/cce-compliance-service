package org.openphc.cce.compliance.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "event_category", nullable = false)
    private String eventCategory;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    private String actor;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(nullable = false)
    private OffsetDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = OffsetDateTime.now();
        }
    }
}
