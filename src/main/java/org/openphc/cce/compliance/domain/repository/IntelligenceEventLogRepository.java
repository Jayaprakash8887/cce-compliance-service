package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IntelligenceEventLogRepository extends JpaRepository<IntelligenceEventLog, UUID> {

    List<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId);

    List<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId);

    List<IntelligenceEventLog> findByStepInstanceId(UUID stepInstanceId);

    List<IntelligenceEventLog> findByPublished(boolean published);

    boolean existsByActionDefinitionId(UUID actionDefinitionId);
}
