package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionRunRepository extends JpaRepository<ActionRun, UUID> {

    @EntityGraph(attributePaths = {"actionDefinition", "protocolInstance", "stepInstance", "context", "context.deviation"})
    Optional<ActionRun> findWithGraphById(UUID id);

    @EntityGraph(attributePaths = {"actionDefinition", "protocolInstance", "stepInstance", "context", "context.deviation"})
    List<ActionRun> findWithGraphByProtocolInstanceId(UUID protocolInstanceId);

    @EntityGraph(attributePaths = {"actionDefinition", "protocolInstance", "stepInstance", "context", "context.deviation"})
    List<ActionRun> findWithGraphByActionDefinitionId(UUID actionDefinitionId);

    @EntityGraph(attributePaths = {"actionDefinition", "protocolInstance", "stepInstance", "context", "context.deviation"})
    List<ActionRun> findWithGraphByStatus(ActionRunStatus status);

    @EntityGraph(attributePaths = {"actionDefinition", "protocolInstance", "stepInstance", "context", "context.deviation"})
    List<ActionRun> findWithGraphBy();

    List<ActionRun> findByProtocolInstanceId(UUID protocolInstanceId);

    List<ActionRun> findByActionDefinitionId(UUID actionDefinitionId);

    List<ActionRun> findByStepInstanceId(UUID stepInstanceId);

    List<ActionRun> findByStatus(ActionRunStatus status);

    boolean existsByActionDefinitionId(UUID actionDefinitionId);
}
