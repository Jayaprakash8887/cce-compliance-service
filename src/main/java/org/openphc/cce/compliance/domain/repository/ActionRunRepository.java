package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionRunRepository extends JpaRepository<ActionRun, UUID> {

    List<ActionRun> findByProtocolInstanceId(UUID protocolInstanceId);

    List<ActionRun> findByActionDefinitionId(UUID actionDefinitionId);

    List<ActionRun> findByStepInstanceId(UUID stepInstanceId);

    List<ActionRun> findByDeviationId(UUID deviationId);
}
