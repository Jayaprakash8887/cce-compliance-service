package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "protocol_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProtocolInstance {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    @Column(name = "protocol_canonical", nullable = false)
    private String protocolCanonical;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_definition_id", nullable = false)
    private ProtocolDefinition protocolDefinition;

    @Column(name = "enrolled_at", nullable = false)
    private OffsetDateTime enrolledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtocolInstanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "protocolInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<StepInstance> steps = new HashSet<>();

    @OneToMany(mappedBy = "protocolInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<Deviation> deviations = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
