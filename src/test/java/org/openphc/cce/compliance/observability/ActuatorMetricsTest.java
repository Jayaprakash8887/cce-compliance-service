package org.openphc.cce.compliance.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.config.ObservabilityConfig;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies all custom Micrometer metrics are registered correctly with
 * expected names, tags, and descriptions. These metrics are exposed via
 * the /actuator/prometheus endpoint at runtime.
 */
class ActuatorMetricsTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ProtocolInstanceRepository repo = mock(ProtocolInstanceRepository.class);
        when(repo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(3L);

        ObservabilityConfig config = new ObservabilityConfig();
        MeterBinder binder = config.cceMetrics(repo);
        binder.bindTo(registry);
    }

    @Test
    void eventsProcessedCounter_isRegistered() {
        var counter = registry.find("cce.events.processed").counter();
        assertNotNull(counter);
        counter.increment();
        assertEquals(1.0, counter.count());
    }

    @Test
    void eventsMatchedCounter_hasMatchedTag() {
        assertNotNull(registry.find("cce.events.matched").tag("status", "matched").counter());
    }

    @Test
    void eventsMatchedCounter_hasZeroMatchTag() {
        assertNotNull(registry.find("cce.events.matched").tag("status", "zero_match").counter());
    }

    @Test
    void eventsDuplicateCounter_isRegistered() {
        assertNotNull(registry.find("cce.events.duplicate").counter());
    }

    @Test
    void intelligencePublishedCounter_isRegistered() {
        assertNotNull(registry.find("cce.events.intelligence.published").counter());
    }

    @Test
    void stepMatchingDurationTimer_isRegistered() {
        var timer = registry.find("cce.step.matching.duration").timer();
        assertNotNull(timer);
        assertEquals(0, timer.count());
    }

    @Test
    void eventProcessingDurationTimer_isRegistered() {
        var timer = registry.find("cce.events.processing.duration").timer();
        assertNotNull(timer);
        assertEquals(0, timer.count());
    }

    @Test
    void protocolInstancesActiveGauge_isRegistered() {
        var gauge = registry.find("cce.protocol.instances.active").gauge();
        assertNotNull(gauge);
        assertEquals(3.0, gauge.value());
    }

    @Test
    void protocolInstancesActiveGauge_reflectsUpdates() {
        ProtocolInstanceRepository repo = mock(ProtocolInstanceRepository.class);
        when(repo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(0L).thenReturn(10L);

        MeterRegistry freshRegistry = new SimpleMeterRegistry();
        new ObservabilityConfig().cceMetrics(repo).bindTo(freshRegistry);

        var gauge = freshRegistry.find("cce.protocol.instances.active").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value());
        // Second call returns 10
        assertEquals(10.0, gauge.value());
    }
}
