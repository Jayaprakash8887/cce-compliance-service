package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.ActionRunContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionRunContextRepository extends JpaRepository<ActionRunContext, UUID> {

    Optional<ActionRunContext> findByActionRunId(UUID actionRunId);
}
