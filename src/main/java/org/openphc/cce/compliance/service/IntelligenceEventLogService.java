package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IntelligenceEventLogService {

    private final IntelligenceEventLogRepository repository;

    public IntelligenceEventLogService(IntelligenceEventLogRepository repository) {
        this.repository = repository;
    }

    public IntelligenceEventLog findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Intelligence event log not found: " + id));
    }

    public List<IntelligenceEventLog> findAll() {
        return repository.findAll();
    }

    public List<IntelligenceEventLog> findByProtocolInstanceId(UUID protocolInstanceId) {
        return repository.findByProtocolInstanceId(protocolInstanceId);
    }

    public List<IntelligenceEventLog> findByActionDefinitionId(UUID actionDefinitionId) {
        return repository.findByActionDefinitionId(actionDefinitionId);
    }

    public List<IntelligenceEventLog> findByPublished(boolean published) {
        return repository.findByPublished(published);
    }
}
