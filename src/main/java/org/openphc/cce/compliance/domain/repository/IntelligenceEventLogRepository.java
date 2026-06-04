package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IntelligenceEventLogRepository extends JpaRepository<IntelligenceEventLog, UUID> {

    List<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId);

    Page<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId, Pageable pageable);

    List<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId);

    Page<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId, Pageable pageable);

    List<IntelligenceEventLog> findByStepInstanceId(UUID stepInstanceId);

    List<IntelligenceEventLog> findByPublished(boolean published);

    Page<IntelligenceEventLog> findByPublished(boolean published, Pageable pageable);

    boolean existsByActionDefinitionId(UUID actionDefinitionId);
}
