package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ObservabilityConfigTest {

    @Test
    void registersTheEvaluatorBacklogGauge() {
        // The service's primary health signal: near zero in a steady state, rising when transitions
        // fall due faster than they are applied.
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countDue(any())).thenReturn(7L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeterBinder binder = new ObservabilityConfig().complianceMetrics(repository);
        binder.bindTo(registry);

        assertEquals(7.0, registry.get("cce.sla.transitions.due").gauge().value());
    }

    @Test
    void gaugeTracksTheRepositoryRatherThanASnapshot() {
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countDue(any())).thenReturn(2L, 5L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ObservabilityConfig().complianceMetrics(repository).bindTo(registry);

        assertEquals(2.0, registry.get("cce.sla.transitions.due").gauge().value());
        assertEquals(5.0, registry.get("cce.sla.transitions.due").gauge().value());
    }

    @Test
    void gaugeSumsBothClaimPaths() {
        // The backlog is everything the next cycle would take: fallen deadlines plus the rows of steps
        // already completed. Reporting only the first would hide half the work the evaluator does.
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countDue(any())).thenReturn(7L);
        when(repository.countClaimableForCompletedSteps(any())).thenReturn(3L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ObservabilityConfig().complianceMetrics(repository).bindTo(registry);

        assertEquals(10.0, registry.get("cce.sla.transitions.due").gauge().value());
    }

    @Test
    void bothHalvesAreCountedAsOfTheSameInstant() {
        // Otherwise a row could fall between the two queries and be counted twice, or not at all: the
        // predicates are disjoint only when they share one `now`.
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ObservabilityConfig().complianceMetrics(repository).bindTo(registry);

        registry.get("cce.sla.transitions.due").gauge().value();

        ArgumentCaptor<OffsetDateTime> dueAsOf = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> completedAsOf = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).countDue(dueAsOf.capture());
        verify(repository).countClaimableForCompletedSteps(completedAsOf.capture());
        assertEquals(dueAsOf.getValue(), completedAsOf.getValue());
    }

    @Test
    void gaugeCountsWhatIsDueNowRatherThanEveryUnprocessedRow() {
        // The distinction is the whole point of the metric. Counting every unprocessed row would fold
        // in the entire future schedule, so the gauge would track enrolment volume and could never sit
        // near zero — making it useless as the thing the deployment guide says to alert on.
        SlaTransitionClaimRepository repository = mock(SlaTransitionClaimRepository.class);
        when(repository.countDue(any())).thenReturn(0L);
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ObservabilityConfig().complianceMetrics(repository).bindTo(registry);
        registry.get("cce.sla.transitions.due").gauge().value();

        ArgumentCaptor<OffsetDateTime> asOf = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository, atLeastOnce()).countDue(asOf.capture());
        assertTrue(Duration.between(before, asOf.getValue()).abs().compareTo(Duration.ofMinutes(1)) < 0,
                "the gauge must ask for the backlog as of now, not for an unbounded total");
    }
}
