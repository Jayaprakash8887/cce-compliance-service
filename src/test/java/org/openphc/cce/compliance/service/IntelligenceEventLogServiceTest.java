package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.domain.repository.IntelligenceEventLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntelligenceEventLogService}, a read-only query facade over
 * {@link IntelligenceEventLogRepository}. Verifies each accessor delegates to the repository and
 * that {@code findById} surfaces a not-found as {@link EntityNotFoundException}.
 */
@ExtendWith(MockitoExtension.class)
class IntelligenceEventLogServiceTest {

    @Mock
    private IntelligenceEventLogRepository repository;

    private IntelligenceEventLogService service;

    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new IntelligenceEventLogService(repository);
    }

    @Test
    void findById_whenPresent_returnsEntity() {
        UUID id = UUID.randomUUID();
        IntelligenceEventLog log = new IntelligenceEventLog();
        when(repository.findById(id)).thenReturn(Optional.of(log));

        assertSame(log, service.findById(id));
    }

    @Test
    void findById_whenMissing_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> service.findById(id));
        assertTrue(ex.getMessage().contains(id.toString()));
    }

    @Test
    void findAll_delegatesToRepository() {
        List<IntelligenceEventLog> logs = List.of(new IntelligenceEventLog());
        when(repository.findAll()).thenReturn(logs);

        assertSame(logs, service.findAll());
    }

    @Test
    void findAllPaged_delegatesToRepository() {
        Page<IntelligenceEventLog> page = new PageImpl<>(List.of(new IntelligenceEventLog()));
        when(repository.findAll(pageable)).thenReturn(page);

        assertSame(page, service.findAll(pageable));
    }

    @Test
    void findByProtocolInstanceId_delegatesToRepository() {
        UUID pid = UUID.randomUUID();
        List<IntelligenceEventLog> logs = List.of(new IntelligenceEventLog());
        when(repository.findByProtocolInstanceId(pid)).thenReturn(logs);

        assertSame(logs, service.findByProtocolInstanceId(pid));
    }

    @Test
    void findByProtocolInstanceIdPaged_delegatesToRepository() {
        UUID pid = UUID.randomUUID();
        Page<IntelligenceEventLog> page = new PageImpl<>(List.of(new IntelligenceEventLog()));
        when(repository.findByProtocolInstanceId(pid, pageable)).thenReturn(page);

        assertSame(page, service.findByProtocolInstanceId(pid, pageable));
    }

    @Test
    void findByActionDefinitionId_delegatesToRepository() {
        UUID aid = UUID.randomUUID();
        List<IntelligenceEventLog> logs = List.of(new IntelligenceEventLog());
        when(repository.findByActionDefinitionId(aid)).thenReturn(logs);

        assertSame(logs, service.findByActionDefinitionId(aid));
    }

    @Test
    void findByActionDefinitionIdPaged_delegatesToRepository() {
        UUID aid = UUID.randomUUID();
        Page<IntelligenceEventLog> page = new PageImpl<>(List.of(new IntelligenceEventLog()));
        when(repository.findByActionDefinitionId(aid, pageable)).thenReturn(page);

        assertSame(page, service.findByActionDefinitionId(aid, pageable));
    }

    @Test
    void findByPublished_delegatesToRepository() {
        List<IntelligenceEventLog> logs = List.of(new IntelligenceEventLog());
        when(repository.findByPublished(true)).thenReturn(logs);

        assertSame(logs, service.findByPublished(true));
    }

    @Test
    void findByPublishedPaged_delegatesToRepository() {
        Page<IntelligenceEventLog> page = new PageImpl<>(List.of(new IntelligenceEventLog()));
        when(repository.findByPublished(false, pageable)).thenReturn(page);

        assertSame(page, service.findByPublished(false, pageable));
    }
}
