package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ActionDefinition;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionDefinitionRepository extends JpaRepository<ActionDefinition, UUID> {

    Optional<ActionDefinition> findByCanonicalUrlAndVersion(String canonicalUrl, String version);

    List<ActionDefinition> findByStatus(ActionDefinitionStatus status);
}
