package org.openphc.cce.compliance.domain.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The evaluator's fetch path over {@code step_sla_state_transition}.
 *
 * <p>Deliberately separate from cce-common-util's {@code StepSlaStateTransitionRepository}, which is the
 * shared read side (a step's thresholds, needed by both services). Fetching rows for processing is this
 * service's alone, so the query that does it — and the pessimistic lock it takes — lives here rather than
 * somewhere the Matcher Service could reach for it.
 */
@Repository
public interface SlaTransitionFetchRepository extends JpaRepository<StepSlaStateTransition, UUID> {

    /**
     * Fetch a batch of transitions whose deadline has passed.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — expressed by the {@code -2} lock timeout — is what makes this
     * safe to run on every instance at once: each caller takes rows no one else holds and steps over the
     * rest instead of blocking. The row lock <em>is</em> what reserves the row, so no lease table and no leader
     * election are needed.
     *
     * <p>Selects on {@code next_attempt_at}, equal to {@code process_by} initially and pushed out by a
     * failure so a retry is deferred without rewriting {@code process_by} — which stays the immutable
     * record of when the deadline fell. Matches the partial index {@code idx_sslt_due}, so the scan
     * covers only the unprocessed backlog. Ordered by {@code process_by} so the oldest deadline is
     * always applied first, however often a row has been deferred.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT t FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt <= :now
            ORDER BY t.processBy ASC
            """)
    List<StepSlaStateTransition> fetchDueTransitions(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * Fetch a batch of transitions whose step has already been completed, whatever their deadline.
     *
     * <p>A completed step's timeliness no longer depends on the clock. Its {@code completed_at} is fixed
     * and both of its thresholds were written at creation, so every row it still has can be judged now —
     * comparing that timestamp against each {@code process_by} — instead of at the deadline. Waiting
     * would leave an already-known outcome unrecorded: an on-time completion reading as null until its
     * due date passed, weeks later for an early one.
     *
     * <p>Complements {@link #fetchDueTransitions} rather than overlapping it: {@code next_attempt_at > :now}
     * excludes the rows that query already takes, so one row cannot be fetched twice in a batch and
     * applied twice.
     *
     * <p>Restricted to steps whose {@code sla_status} is null or {@code OVERDUE} — the two unsettled
     * states — which is also what makes the fetch cheap: it drives off
     * {@code idx_step_instance_completed_unjudged}, a partial index over exactly that transient set,
     * instead of scanning the pending schedule of every step. {@code MET} and {@code MISSED} are settled
     * outcomes, so a row left behind by one of those steps would apply as a no-op; it keeps its own
     * deadline and is consumed then.
     *
     * <p>A completed step with no {@code completed_at} is left to {@link #fetchDueTransitions}: there is no
     * timestamp to judge it early by, so the deadline is the only evidence left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT t FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt > :now
              AND t.stepInstanceId IN (
                  SELECT s.id FROM StepInstance s
                  WHERE s.stepStatus = org.openphc.cce.common.enums.StepStatus.COMPLETED
                    AND s.completedAt IS NOT NULL
                    AND (s.slaStatus IS NULL
                         OR s.slaStatus = org.openphc.cce.common.enums.SlaStatus.OVERDUE))
            ORDER BY t.processBy ASC
            """)
    List<StepSlaStateTransition> fetchCompletedStepTransitions(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * Half of the {@code cce.sla.transitions.due} gauge: rows whose deadline has passed.
     *
     * <p>Carries {@link #fetchDueTransitions}'s predicate, deliberately — the gauge has to count what the next cycle
     * will fetch, or it stops being a backlog. Counting every unprocessed row would instead fold in the
     * whole future schedule, so it would track enrolment volume rather than lateness and could never sit
     * near zero.
     *
     * <p>Kept as a separate query from {@link #countCompletedStepTransitions} rather than one predicate
     * with an {@code OR}: the two branches take their rows from different indexes, and an {@code OR}
     * across them plans as a sequential scan of the whole pending schedule — 100k rows filtered to find
     * ten, on every metrics scrape. Two indexed counts summed by the caller cost microseconds. The
     * {@code fetchDueTransitions} predicate and this one are disjoint, so the sum never double-counts.
     */
    @Query("""
            SELECT COUNT(t) FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt <= :now
            """)
    long countDueTransitions(@Param("now") OffsetDateTime now);

    /**
     * The other half of the gauge: rows of already-completed steps, ready ahead of their deadline.
     *
     * <p>Mirrors {@link #fetchCompletedStepTransitions}, including the {@code next_attempt_at > :now} clause that
     * keeps it disjoint from {@link #countDueTransitions}.
     */
    @Query("""
            SELECT COUNT(t) FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt > :now
              AND t.stepInstanceId IN (
                  SELECT s.id FROM StepInstance s
                  WHERE s.stepStatus = org.openphc.cce.common.enums.StepStatus.COMPLETED
                    AND s.completedAt IS NOT NULL
                    AND (s.slaStatus IS NULL
                         OR s.slaStatus = org.openphc.cce.common.enums.SlaStatus.OVERDUE))
            """)
    long countCompletedStepTransitions(@Param("now") OffsetDateTime now);
}
