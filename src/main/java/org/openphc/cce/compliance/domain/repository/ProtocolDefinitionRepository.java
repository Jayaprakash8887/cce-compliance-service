package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolDefinitionRepository extends JpaRepository<ProtocolDefinition, UUID> {

    Optional<ProtocolDefinition> findByUrlAndVersion(String url, String version);

    List<ProtocolDefinition> findByUrl(String url);
}
