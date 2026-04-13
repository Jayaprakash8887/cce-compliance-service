package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void cceMetrics_shouldRegisterAllCountersTimersAndGauges() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ProtocolInstanceRepository protocolRepo = mock(ProtocolInstanceRepository.class);
        ActionDefinitionRepository actionDefRepo = mock(ActionDefinitionRepository.class);
        when(protocolRepo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(5L);
        when(actionDefRepo.countByStatus(ActionDefinitionStatus.ACTIVE)).thenReturn(3L);

        MeterBinder binder = config.cceMetrics(protocolRepo, actionDefRepo);

        binder.bindTo(registry);

        assertNotNull(registry.find("cce.events.processed").counter());
        assertNotNull(registry.find("cce.events.matched").tag("status", "matched").counter());
        assertNotNull(registry.find("cce.events.matched").tag("status", "zero_match").counter());
        assertNotNull(registry.find("cce.events.duplicate").counter());
        assertNotNull(registry.find("cce.events.intelligence.published").counter());
        assertNotNull(registry.find("cce.intelligence.actions.evaluated").counter());
        assertNotNull(registry.find("cce.intelligence.actions.fired").counter());
        assertNotNull(registry.find("cce.step.matching.duration").timer());
        assertNotNull(registry.find("cce.events.processing.duration").timer());
        assertNotNull(registry.find("cce.intelligence.publish.duration").timer());
        assertNotNull(registry.find("cce.protocol.instances.active").gauge());
        assertEquals(5.0, registry.find("cce.protocol.instances.active").gauge().value());
        assertNotNull(registry.find("cce.action.definitions.active").gauge());
        assertEquals(3.0, registry.find("cce.action.definitions.active").gauge().value());
    }
}
