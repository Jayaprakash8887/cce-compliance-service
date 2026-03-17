package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class TriggerIndexId implements Serializable {

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(nullable = false)
    private String path;

    @Column(name = "code_system", nullable = false)
    private String codeSystem;

    @Column(name = "code_value", nullable = false)
    private String codeValue;

    @Column(name = "protocol_definition_id", nullable = false)
    private UUID protocolDefinitionId;

    @Column(name = "action_id", nullable = false)
    private String actionId;
}
