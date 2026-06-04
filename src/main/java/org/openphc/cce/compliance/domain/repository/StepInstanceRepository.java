package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface StepInstanceRepository extends JpaRepository<StepInstance, UUID> {

    List<StepInstance> findByProtocolInstanceId(UUID protocolInstanceId);

    List<StepInstance> findByProtocolInstanceIdAndActionIdAndStateIn(
            UUID protocolInstanceId, String actionId, Collection<StepState> states);

    @Query("SELECT COUNT(s) FROM StepInstance s WHERE s.protocolInstance.id = :instanceId AND s.state NOT IN :terminalStates")
    long countNonTerminalSteps(@Param("instanceId") UUID instanceId,
                              @Param("terminalStates") Collection<StepState> terminalStates);

    @Query("SELECT COUNT(s) FROM StepInstance s WHERE s.protocolInstance.id = :instanceId")
    long countByProtocolInstanceId(@Param("instanceId") UUID instanceId);
}
