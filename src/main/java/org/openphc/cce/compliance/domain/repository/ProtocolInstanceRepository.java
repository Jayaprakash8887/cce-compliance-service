package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolInstanceRepository extends JpaRepository<ProtocolInstance, UUID> {

    Optional<ProtocolInstance> findByPatientIdAndProtocolDefinitionIdAndStatus(
            String patientId, UUID protocolDefinitionId, ProtocolInstanceStatus status);

    List<ProtocolInstance> findByPatientId(String patientId);

    List<ProtocolInstance> findByPatientIdAndStatus(String patientId, ProtocolInstanceStatus status);

    boolean existsByProtocolDefinitionId(UUID protocolDefinitionId);

    long countByStatus(ProtocolInstanceStatus status);

    @Query("SELECT pi FROM ProtocolInstance pi " +
            "LEFT JOIN FETCH pi.steps " +
            "LEFT JOIN FETCH pi.deviations " +
            "WHERE pi.id = :id")
    Optional<ProtocolInstance> findByIdWithStepsAndDeviations(@Param("id") UUID id);
}
