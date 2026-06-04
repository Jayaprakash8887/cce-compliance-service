package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolInstanceRepository extends JpaRepository<ProtocolInstance, UUID> {

    Optional<ProtocolInstance> findByPatientIdAndProtocolDefinitionIdAndStatus(
            String patientId, UUID protocolDefinitionId, ProtocolInstanceStatus status);

    boolean existsByProtocolDefinitionId(UUID protocolDefinitionId);

    long countByStatus(ProtocolInstanceStatus status);
}
