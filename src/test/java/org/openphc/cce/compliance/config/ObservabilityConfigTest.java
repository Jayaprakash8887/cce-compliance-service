package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void cceMetrics_shouldRegisterAllCountersAndTimers() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterBinder binder = config.cceMetrics();

        binder.bindTo(registry);

        assertNotNull(registry.find("cce.events.processed").counter());
        assertNotNull(registry.find("cce.events.matched").tag("status", "matched").counter());
        assertNotNull(registry.find("cce.events.matched").tag("status", "zero_match").counter());
        assertNotNull(registry.find("cce.events.duplicate").counter());
        assertNotNull(registry.find("cce.events.intelligence.published").counter());
        assertNotNull(registry.find("cce.step.matching.duration").timer());
    }
}
