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
 * The evaluator's claim path over {@code step_sla_state_transition}.
 *
 * <p>Deliberately separate from cce-common-util's {@code StepSlaStateTransitionRepository}, which is the
 * shared read side (a step's thresholds, needed by both services). Claiming rows for processing is this
 * service's alone, so the query that does it — and the pessimistic lock it takes — lives here rather than
 * somewhere the Matcher Service could reach for it.
 */
@Repository
public interface SlaTransitionClaimRepository extends JpaRepository<StepSlaStateTransition, UUID> {

    /**
     * Claim a batch of transitions whose deadline has passed.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — expressed by the {@code -2} lock timeout — is what makes this
     * safe to run on every instance at once: each caller takes rows no one else holds and steps over the
     * rest instead of blocking. The row lock <em>is</em> the claim, so no lease table and no leader
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
    List<StepSlaStateTransition> claimDue(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * Backs the {@code cce.sla.transitions.due} gauge — the evaluator's backlog.
     *
     * <p>Carries the same predicate as {@link #claimDue}, deliberately: a gauge counting every
     * unprocessed row would include the whole future schedule, so it would track enrolment volume
     * rather than lateness and could never sit near zero. What is wanted is the rows that are due
     * <em>now</em> and still unapplied.
     */
    @Query("""
            SELECT COUNT(t) FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt <= :now
            """)
    long countDue(@Param("now") OffsetDateTime now);
}
