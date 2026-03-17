package org.openphc.cce.compliance.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "protocol_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProtocolDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtocolDefinitionStatus status;

    @Type(JsonType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> definition;

    @Column(name = "loaded_at", nullable = false)
    private OffsetDateTime loadedAt;

    @PrePersist
    protected void onCreate() {
        if (loadedAt == null) {
            loadedAt = OffsetDateTime.now();
        }
    }

    public String getCanonical() {
        return url + "|" + version;
    }
}
