package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.StepInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StepInstanceHistoryRepository extends JpaRepository<StepInstanceHistory, Long> {
}
