package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics for the compliance plane.
 *
 * <p>The counters this service moves are registered where they are incremented
 * ({@code cce.sla.transitions.*}, {@code cce.sla.evaluator.*}, {@code cce.intelligence.actions.*}). What
 * belongs here is the one thing only a gauge can express: how much due work is still outstanding.
 *
 * <p>{@code cce.sla.transitions.unprocessed} is the service's primary health signal. In a steady state it
 * hovers near zero; a rising value means transitions are falling due faster than they are being applied,
 * or that rows are failing and backing off.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder complianceMetrics(SlaTransitionClaimRepository transitionRepository) {
        return registry -> Gauge.builder("cce.sla.transitions.unprocessed",
                        transitionRepository,
                        SlaTransitionClaimRepository::countUnprocessed)
                .description("SLA transition rows not yet applied — the evaluator's backlog")
                .register(registry);
    }
}
