package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Metrics for the compliance plane.
 *
 * <p>The counters this service moves are registered where they are incremented
 * ({@code cce.sla.transitions.*}, {@code cce.sla.evaluator.*}, {@code cce.intelligence.actions.*}). What
 * belongs here is the one thing only a gauge can express: how much due work is still outstanding.
 *
 * <p>{@code cce.sla.transitions.due} is the service's primary health signal. In a steady state it hovers
 * near zero; a rising value means transitions are falling due faster than they are being applied, or that
 * rows are failing and backing off. It counts what the next cycle would claim — rows whose deadline has
 * passed, plus rows of steps already completed and so already judgeable — and nothing else: counting every
 * unprocessed row would fold in the whole future schedule and track enrolment volume instead.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder complianceMetrics(SlaTransitionClaimRepository transitionRepository) {
        return registry -> Gauge.builder("cce.sla.transitions.due",
                        transitionRepository,
                        ObservabilityConfig::claimableNow)
                .description("SLA transition rows the next cycle would claim: past their deadline, "
                        + "or belonging to an already-completed step")
                .register(registry);
    }

    /**
     * What the next cycle would claim: the two claim predicates counted separately and added. Separate
     * queries because an {@code OR} across them plans as a sequential scan of the entire pending
     * schedule; they are disjoint, so the sum is exact.
     */
    private static double claimableNow(SlaTransitionClaimRepository repository) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return repository.countDue(now) + repository.countClaimableForCompletedSteps(now);
    }
}
