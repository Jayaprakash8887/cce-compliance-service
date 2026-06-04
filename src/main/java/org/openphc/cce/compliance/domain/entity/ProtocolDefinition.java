package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.compliance.domain.enums.ProtocolDefinitionStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode definition;

    @Column(name = "loaded_at", nullable = false)
    private OffsetDateTime loadedAt;

    @PrePersist
    protected void onCreate() {
        if (loadedAt == null) {
            loadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public String getCanonical() {
        return url + "|" + version;
    }
}
