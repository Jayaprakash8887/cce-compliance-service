package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
    @GeneratedValue(strategy = GenerationType.AUTO)
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
    private List<StepInstance> steps = new ArrayList<>();

    @OneToMany(mappedBy = "protocolInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Deviation> deviations = new ArrayList<>();

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
