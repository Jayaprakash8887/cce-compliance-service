package org.openphc.cce.compliance.service;

import org.openphc.cce.common.service.DeviationService;
import org.openphc.cce.common.service.IntelligenceActionEvaluator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Claims due {@code step_sla_state_transition} rows and applies them.
 *
 * <p>The Matcher Service writes one row per threshold a step can cross and never touches it again.
 * Everything after that is owned here: deciding which rows are due, advancing
 * {@code step_instance.sla_status}, raising the {@code OVERDUE} / {@code MISSED} deviation, and marking
 * the row processed. There is no Kafka hop and no HTTP call between the two services — they meet on this
 * one table, with one writer per column.
 *
 * <p>Claim and apply share a transaction. The row lock taken by {@code FOR UPDATE SKIP LOCKED} is the
 * claim, so concurrent instances drain disjoint sets with no lease table and no leader election, and a
 * deviation can never be recorded without the row being marked processed in the same commit.
 *
 * <p>Separate bean from {@link SlaTransitionEvaluator}, which drives the polling loop. Not cosmetic:
 * Spring's transaction proxy is bypassed by self-invocation, so a driver calling its own
 * {@code @Transactional} method would silently run it without a transaction.
 *
 * <h2>Rows that outlive their state</h2>
 * Matcher does not cancel a row when the step completes, so a completed step is judged against its
 * {@code completed_at} rather than the wall clock:
 *
 * <table border="1">
 *   <caption>Behaviour by step state when the row comes due</caption>
 *   <tr><th>Step state</th><th>Action</th></tr>
 *   <tr><td>{@code NOT_STARTED}</td><td>advance {@code sla_status}, record the deviation</td></tr>
 *   <tr><td>{@code COMPLETED}, {@code completed_at >= process_by}</td>
 *       <td>SLA already settled at completion — leave it, record the deviation</td></tr>
 *   <tr><td>{@code COMPLETED}, {@code completed_at < process_by}</td>
 *       <td>consume the row; nothing was breached</td></tr>
 * </table>
 *
 * <p>A completed step's SLA is final: Matcher settled it against these same thresholds using the clinical
 * completion time, so this service must not move it — only record a breach the completion left
 * unrecorded. {@code step_status} is never written here at all; crossing a deadline says nothing about
 * whether the event arrived.
 */
@Service
public class SlaTransitionApplier {

    private static final Logger log = LoggerFactory.getLogger(SlaTransitionApplier.class);

    /** Past this many attempts a row is logged as an error every cycle rather than failing quietly. */
    private static final int ATTEMPTS_BEFORE_ALERT = 5;

    private final SlaTransitionClaimRepository transitionRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final DeviationService deviationService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final String instanceId;
    private final int batchSize;
    private final Duration maxBackoff;
    private final Counter appliedCounter;
    private final Counter consumedCounter;

    public SlaTransitionApplier(SlaTransitionClaimRepository transitionRepository,
                                StepInstanceRepository stepInstanceRepository,
                                DeviationService deviationService,
                                IntelligenceActionEvaluator intelligenceActionEvaluator,
                                @Value("${cce.sla.instance-id:${HOSTNAME:local}}") String instanceId,
                                @Value("${cce.sla.batch-size:100}") int batchSize,
                                @Value("${cce.sla.max-backoff-seconds:3600}") long maxBackoffSeconds,
                                MeterRegistry meterRegistry) {
        this.transitionRepository = transitionRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.deviationService = deviationService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.instanceId = instanceId;
        this.batchSize = batchSize;
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
        this.appliedCounter = Counter.builder("cce.sla.transitions.applied")
                .description("SLA transitions that advanced a step's sla_status")
                .register(meterRegistry);
        this.consumedCounter = Counter.builder("cce.sla.transitions.consumed")
                .description("SLA transitions closed without firing, the step having met the deadline")
                .register(meterRegistry);
    }

    /**
     * Claim and apply one batch of due transitions.
     *
     * @param claimed populated with the id of every row claimed, so the caller can back them off if the
     *                transaction rolls back — the list is plain memory and survives the rollback
     * @return how many rows were claimed
     */
    @Transactional
    public int claimAndApply(List<UUID> claimed) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<StepSlaStateTransition> due = transitionRepository.claimDue(now, Limit.of(batchSize));

        for (StepSlaStateTransition row : due) {
            claimed.add(row.getId());
            row.setAttempts(row.getAttempts() + 1);
            if (row.getAttempts() > ATTEMPTS_BEFORE_ALERT) {
                log.error("SLA transition {} for step {} has now been attempted {} times",
                        row.getId(), row.getStepInstanceId(), row.getAttempts());
            }
            applyRow(row);
        }
        return due.size();
    }

    private void applyRow(StepSlaStateTransition row) {
        StepInstance step = stepInstanceRepository.findById(row.getStepInstanceId()).orElse(null);
        if (step == null) {
            // The step is gone, so there is no schedule left to honour. Close the row rather than
            // retrying something that can never succeed.
            log.warn("SLA transition {} references step {} which no longer exists — consuming",
                    row.getId(), row.getStepInstanceId());
            markProcessed(row);
            return;
        }

        if (step.getStepStatus() == StepStatus.COMPLETED) {
            applyToCompletedStep(row, step);
        } else {
            applyToOutstandingStep(row, step);
        }
        markProcessed(row);
    }

    /**
     * The event arrived before this row was processed. Completion already settled {@code sla_status}
     * against this very threshold, so only the deviation is still in question.
     */
    private void applyToCompletedStep(StepSlaStateTransition row, StepInstance step) {
        OffsetDateTime completedAt = step.getCompletedAt();
        if (completedAt != null && completedAt.isBefore(row.getProcessBy())) {
            consumedCounter.increment();
            log.debug("Step {} completed at {}, before its {} threshold of {} — transition consumed",
                    step.getId(), completedAt, row.getTransitionType(), row.getProcessBy());
            return;
        }

        // Recorded late. Raising the deviation here, rather than at completion, is what lets a late
        // arrival still be reported as the breach it was.
        raiseDeviationFor(row, step);
    }

    /** The ordinary case: the event still has not arrived, so the SLA moves on. */
    private void applyToOutstandingStep(StepSlaStateTransition row, StepInstance step) {
        SlaStatus from = SlaStatus.valueOf(row.getFromStatus());
        SlaStatus to = SlaStatus.valueOf(row.getToStatus());

        if (step.getSlaStatus() != from) {
            // Already advanced — a redelivered claim, or rows applied out of order. Re-applying would
            // overwrite a later status with an earlier one.
            log.debug("Step {} has slaStatus {} but transition {} expects {} — consuming",
                    step.getId(), step.getSlaStatus(), row.getId(), from);
            consumedCounter.increment();
            return;
        }

        // An optional step breaches nothing by never arriving: its missed threshold settles the SLA as
        // met, with no deviation, while step_status stays NOT_STARTED to show the event never came.
        boolean optionalMiss = to == SlaStatus.MISSED && "could".equals(step.getRequiredBehavior());
        SlaStatus resolved = optionalMiss ? SlaStatus.MET : to;

        step.setSlaStatus(resolved);
        stepInstanceRepository.save(step);
        appliedCounter.increment();

        log.info("Step {} (actionId={}) SLA {} -> {} on {}",
                step.getId(), step.getActionId(), from, resolved, row.getTransitionType());

        if (!optionalMiss) {
            raiseDeviationFor(row, step);
        }
    }

    /**
     * The deviation a transition produces: crossing the due date is an {@code OVERDUE}, crossing the
     * missed date a {@code MISSED}. Intelligence is evaluated only for a freshly created deviation, so
     * a re-claimed row cannot publish the same intelligence event twice.
     */
    private void raiseDeviationFor(StepSlaStateTransition row, StepInstance step) {
        DeviationType type = row.getTransitionType() == SlaTransitionType.PENDING_TO_OVERDUE
                ? DeviationType.OVERDUE
                : DeviationType.MISSED;

        DeviationService.DeviationResult result = deviationService.createDeviation(step, type);
        if (result.created()) {
            intelligenceActionEvaluator.evaluateOnDeviation(step, result.deviation());
        }
    }

    private void markProcessed(StepSlaStateTransition row) {
        row.setProcessed(true);
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setProcessedBy(instanceId);
        transitionRepository.save(row);
    }

    /**
     * Defer the given rows after a failed batch, so a broken row backs off instead of being retried on
     * every cycle. Runs in its own transaction because the batch it belongs to has just rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void backOff(List<UUID> transitionIds) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (StepSlaStateTransition row : transitionRepository.findAllById(transitionIds)) {
            if (row.isProcessed()) {
                continue;
            }
            // Exponential in the attempt count, capped so a permanently broken row is still retried
            // occasionally rather than hammering the database.
            long seconds = Math.min(maxBackoff.getSeconds(),
                    (long) Math.pow(2, Math.min(row.getAttempts(), 20)));
            row.setAttempts(row.getAttempts() + 1);
            row.setNextAttemptAt(now.plusSeconds(seconds));
            transitionRepository.save(row);
        }
    }
}
