package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.EventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EventLogRepository extends JpaRepository<EventLog, UUID> {

    boolean existsByCloudeventsIdAndSource(String cloudeventsId, String source);

    Page<EventLog> findBySubject(String subject, Pageable pageable);
}
