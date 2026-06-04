package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.Deviation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeviationRepository extends JpaRepository<Deviation, UUID> {
}
