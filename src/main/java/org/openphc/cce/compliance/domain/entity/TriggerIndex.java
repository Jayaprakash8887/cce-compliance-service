package org.openphc.cce.compliance.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "trigger_index")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TriggerIndex {

    @EmbeddedId
    private TriggerIndexId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_definition_id", insertable = false, updatable = false)
    private ProtocolDefinition protocolDefinition;

    public String getResourceType() {
        return id != null ? id.getResourceType() : null;
    }

    public String getPath() {
        return id != null ? id.getPath() : null;
    }

    public String getCodeSystem() {
        return id != null ? id.getCodeSystem() : null;
    }

    public String getCodeValue() {
        return id != null ? id.getCodeValue() : null;
    }

    public UUID getProtocolDefinitionId() {
        return id != null ? id.getProtocolDefinitionId() : null;
    }

    public String getActionId() {
        return id != null ? id.getActionId() : null;
    }
}
