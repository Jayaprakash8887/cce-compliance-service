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
     * Fetch a batch of transitions belonging to a step already known to be late, ahead of their deadline.
     *
     * <p>A step at {@code OVERDUE} has been completed and judged late, so its remaining
     * {@code MISSED_DATE_REACHED} row is the last one it has. That row's outcome no longer depends on
     * the clock — {@code completed_at} is fixed — so taking it now closes the step's SLA lifecycle
     * instead of leaving a row pending against a date that can only confirm what is already known.
     *
     * <p>Restricted to {@code OVERDUE} rather than to every unsettled status. A step whose
     * {@code sla_status} is still null needs nothing from this query: if it beat its due date, the
     * on-time sweep records {@code MET} from {@code step_instance} and takes the step out of this set
     * entirely; if it did not, its due-date row is already past and {@link #fetchDueTransitions} has it.
     *
     * <p>What a row fetched here can record, in practice, is nothing. {@code completed_at} is clamped to
     * {@code now} when the step completes, and a row that has never been attempted has
     * {@code process_by == next_attempt_at}, which this query requires to be in the future — so
     * {@code completed_at < process_by} always holds and the threshold is kept. The row is consumed. The
     * exception is a row inside a back-off window, where {@code next_attempt_at} has been pushed out
     * while {@code process_by} stayed put and may now be in the past; there a breach is reachable.
     *
     * <p>Complements {@link #fetchDueTransitions} rather than overlapping it: {@code next_attempt_at > :now}
     * excludes the rows that query already takes, so one row cannot be fetched twice in a batch and
     * applied twice.
     *
     * <p>Cheap for the same reason it always was: it drives off
     * {@code idx_step_instance_completed_unjudged}, whose partial predicate still spans both unsettled
     * statuses — this query takes the {@code OVERDUE} half of it and the on-time sweep the null half.
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
                    AND s.slaStatus = org.openphc.cce.common.enums.SlaStatus.OVERDUE)
            ORDER BY t.processBy ASC
            """)
    List<StepSlaStateTransition> fetchLateStepTransitions(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * Half of the {@code cce.sla.transitions.due} gauge: rows whose deadline has passed.
     *
     * <p>Carries {@link #fetchDueTransitions}'s predicate, deliberately — the gauge has to count what the next cycle
     * will fetch, or it stops being a backlog. Counting every unprocessed row would instead fold in the
     * whole future schedule, so it would track enrolment volume rather than lateness and could never sit
     * near zero.
     *
     * <p>Kept as a separate query from {@link #countLateStepTransitions} rather than one predicate
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
     * The other half of the gauge: rows of already-late steps, ready ahead of their deadline.
     *
     * <p>Mirrors {@link #fetchLateStepTransitions} exactly, {@code OVERDUE} restriction included. It has
     * to: a gauge that counted rows the sweep will not take would report a backlog that never drains.
     * The {@code next_attempt_at > :now} clause keeps it disjoint from {@link #countDueTransitions}, so
     * the sum never double-counts.
     */
    @Query("""
            SELECT COUNT(t) FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt > :now
              AND t.stepInstanceId IN (
                  SELECT s.id FROM StepInstance s
                  WHERE s.stepStatus = org.openphc.cce.common.enums.StepStatus.COMPLETED
                    AND s.completedAt IS NOT NULL
                    AND s.slaStatus = org.openphc.cce.common.enums.SlaStatus.OVERDUE)
            """)
    long countLateStepTransitions(@Param("now") OffsetDateTime now);
}
