package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.ActionRun;
import org.openphc.cce.compliance.domain.enums.ActionRunStatus;
import org.openphc.cce.compliance.domain.repository.ActionRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ActionRunService {

    private final ActionRunRepository actionRunRepository;

    public ActionRunService(ActionRunRepository actionRunRepository) {
        this.actionRunRepository = actionRunRepository;
    }

    public ActionRun findById(UUID id) {
        return actionRunRepository.findWithGraphById(id)
                .orElseThrow(() -> new EntityNotFoundException("Action run not found: " + id));
    }

    public List<ActionRun> findAll() {
        return actionRunRepository.findWithGraphBy();
    }

    public List<ActionRun> findByProtocolInstanceId(UUID protocolInstanceId) {
        return actionRunRepository.findWithGraphByProtocolInstanceId(protocolInstanceId);
    }

    public List<ActionRun> findByActionDefinitionId(UUID actionDefinitionId) {
        return actionRunRepository.findWithGraphByActionDefinitionId(actionDefinitionId);
    }

    public List<ActionRun> findByStatus(ActionRunStatus status) {
        return actionRunRepository.findWithGraphByStatus(status);
    }
}
