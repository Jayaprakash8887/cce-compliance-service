package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservabilityConfigTest {

    @Test
    void registersTheEvaluatorBacklogGauge() {
        // The service's primary health signal: near zero in a steady state, rising when transitions
        // fall due faster than they are applied.
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countUnprocessed()).thenReturn(7L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeterBinder binder = new ObservabilityConfig().complianceMetrics(repository);
        binder.bindTo(registry);

        assertEquals(7.0, registry.get("cce.sla.transitions.unprocessed").gauge().value());
    }

    @Test
    void gaugeTracksTheRepositoryRatherThanASnapshot() {
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countUnprocessed()).thenReturn(2L, 5L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ObservabilityConfig().complianceMetrics(repository).bindTo(registry);

        assertEquals(2.0, registry.get("cce.sla.transitions.unprocessed").gauge().value());
        assertEquals(5.0, registry.get("cce.sla.transitions.unprocessed").gauge().value());
    }
}
