package org.openphc.cce.compliance.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.service.DeviationService;
import org.openphc.cce.common.service.IntelligenceActionEvaluator;
import org.openphc.cce.common.service.StateTransitionHistoryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claims due {@code step_sla_state_transition} rows and applies them.
 *
 * <p>The Matcher Service writes one row per threshold a step can cross and never touches it again.
 * Everything after that is owned here: deciding which rows are due, writing
 * {@code step_instance.sla_status}, raising the {@code OVERDUE} / {@code MISSED} deviation, recording
 * the transition in {@code step_instance_history}, and marking the row processed. There is no Kafka hop
 * and no HTTP call between the two services — they meet on this one table.
 *
 * <p>Claim and apply share a transaction. The row lock taken by {@code FOR UPDATE SKIP LOCKED} is the
 * claim, so concurrent instances drain disjoint sets with no lease table and no leader election, and a
 * deviation can never be recorded without the row being marked processed in the same commit.
 *
 * <p>Separate bean from {@link SlaTransitionEvaluator}, which drives the polling loop. Not cosmetic:
 * Spring's transaction proxy is bypassed by self-invocation, so a driver calling its own
 * {@code @Transactional} method would silently run it without a transaction.
 *
 * <h2>This service is the only writer of sla_status</h2>
 * Matcher records <em>that</em> a step was completed and when; it never judges whether that was timely.
 * So there is no rule here about not overwriting what Matcher decided — it decided nothing. A step's
 * {@code sla_status} is null until a threshold falls due and this service judges it.
 *
 * <p>The judgement compares {@code step_instance.completed_at} — the clinical occurrence time of the
 * completing event — against the row's {@code process_by}. The wall clock never enters into it: what a
 * deadline <em>means</em> depends only on whether the work had happened by then.
 *
 * <p>Which is why a row is claimed for either of two reasons. Its deadline has passed and the work must
 * be judged against it ({@code claimDue}); or its step is already {@code COMPLETED}, in which case
 * {@code completed_at} is fixed, both thresholds are known, and the outcome can be settled immediately
 * rather than at a deadline that would only confirm it ({@code claimForCompletedSteps}). The verdict is
 * the same either way — the second path only decides it sooner, so an on-time completion does not read as
 * null until its due date arrives.
 *
 * <table border="1">
 *   <caption>Behaviour by threshold and step state</caption>
 *   <tr><th>Row</th><th>Step state when applied</th><th>Action</th></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>not completed</td>
 *       <td>{@code OVERDUE} + {@code OVERDUE} deviation</td></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at >= process_by}</td>
 *       <td>{@code OVERDUE} + {@code OVERDUE} deviation — recorded, but late</td></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at < process_by}</td>
 *       <td>{@code MET} — the work beat its deadline</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>not completed</td>
 *       <td>{@code MISSED} + {@code MISSED} deviation ({@code must} only)</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>{@code completed_at >= process_by}</td>
 *       <td>{@code MISSED} + {@code MISSED} deviation ({@code must} only)</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>{@code completed_at < process_by}</td>
 *       <td>consume — this threshold was not breached, and the due-date row already had its say</td></tr>
 * </table>
 *
 * <p>The last row is the one worth being careful about: a step completed <em>between</em> its two
 * thresholds did not breach the missed date, but it is not {@code MET} either — it is the {@code OVERDUE}
 * the due-date row made it. "Did not breach this threshold" and "met its SLA" are only the same thing at
 * the due date, which is why {@code MET} is written on that row alone.
 *
 * <p>{@code step_status} is never written here. Crossing a deadline says nothing about whether the event
 * arrived.
 *
 * <p>Nothing in the table turns on <em>when</em> a row is applied, which is what makes claiming a
 * completed step's rows early safe: the same three columns decide the outcome whether the row is applied
 * at its deadline or the moment the completion is seen.
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
    private final StateTransitionHistoryService stateTransitionHistoryService;
    private final String instanceId;
    private final int batchSize;
    private final Duration maxBackoff;
    private final Counter appliedCounter;
    private final Counter consumedCounter;

    public SlaTransitionApplier(SlaTransitionClaimRepository transitionRepository,
                                StepInstanceRepository stepInstanceRepository,
                                DeviationService deviationService,
                                IntelligenceActionEvaluator intelligenceActionEvaluator,
                                StateTransitionHistoryService stateTransitionHistoryService,
                                @Value("${cce.sla.instance-id:${HOSTNAME:local}}") String instanceId,
                                @Value("${cce.sla.batch-size:100}") int batchSize,
                                @Value("${cce.sla.max-backoff-seconds:3600}") long maxBackoffSeconds,
                                MeterRegistry meterRegistry) {
        this.transitionRepository = transitionRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.deviationService = deviationService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryService = stateTransitionHistoryService;
        this.instanceId = instanceId;
        this.batchSize = batchSize;
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
        this.appliedCounter = Counter.builder("cce.sla.transitions.applied")
                .description("SLA transitions that wrote a step's sla_status")
                .register(meterRegistry);
        this.consumedCounter = Counter.builder("cce.sla.transitions.consumed")
                .description("SLA transitions closed without writing a status or a deviation")
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
        List<StepSlaStateTransition> batch =
                new ArrayList<>(transitionRepository.claimDue(now, Limit.of(batchSize)));

        // Rows of steps that have already been completed, claimed ahead of their deadline. Nothing about
        // such a step can change any more — completed_at is fixed and its thresholds were written at
        // creation — so its outcome is knowable now, and waiting for the wall clock would only delay
        // recording what is already decided.
        int room = batchSize - batch.size();
        if (room > 0) {
            batch.addAll(transitionRepository.claimForCompletedSteps(now, Limit.of(room)));
        }

        for (StepSlaStateTransition row : batch) {
            claimed.add(row.getId());
            row.setAttempts(row.getAttempts() + 1);
            if (row.getAttempts() > ATTEMPTS_BEFORE_ALERT) {
                log.error("SLA transition {} for step {} has now been attempted {} times",
                        row.getId(), row.getStepInstanceId(), row.getAttempts());
            }
            applyRow(row);
        }
        return batch.size();
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

        if (breachedThreshold(row, step)) {
            applyBreach(row, step);
        } else {
            applyKeptDeadline(row, step);
        }
        markProcessed(row);
    }

    /**
     * Was the work still unrecorded when this threshold fell?
     *
     * <p>A step not completed at all has plainly breached it. A completed one is judged on its
     * {@code completed_at}: at or after {@code process_by} is a breach, before it is not. A completed
     * step with no {@code completed_at} is treated as a breach — the row is the better evidence than a
     * missing timestamp, and silently letting it pass would hide the gap.
     */
    private boolean breachedThreshold(StepSlaStateTransition row, StepInstance step) {
        if (step.getStepStatus() != StepStatus.COMPLETED) {
            return true;
        }
        OffsetDateTime completedAt = step.getCompletedAt();
        return completedAt == null || !completedAt.isBefore(row.getProcessBy());
    }

    /** The deadline was not met: advance the SLA and record the deviation. */
    private void applyBreach(StepSlaStateTransition row, StepInstance step) {
        if (isOptionalMiss(row, step)) {
            // Nothing was required of an optional step, so nothing was breached by its not happening —
            // and its sla_status stays whatever the due date made it.
            consumedCounter.increment();
            log.debug("Step {} is optional — transition {} records no MISSED status or deviation",
                    step.getId(), row.getId());
            return;
        }

        if (!writeSlaStatus(step, row.getTransitionType().breachStatus())) {
            // Already at or past this outcome: a redelivered claim, or rows applied out of order.
            consumedCounter.increment();
            return;
        }

        raiseDeviationFor(row, step);
    }

    /**
     * The deadline was met. Only the due date settles an SLA as {@code MET}: beating the missed date
     * says nothing more than that the step was not written off, and the due-date row has already
     * recorded whether it was on time.
     */
    private void applyKeptDeadline(StepSlaStateTransition row, StepInstance step) {
        if (row.getTransitionType() == SlaTransitionType.DUE_DATE_REACHED
                && writeSlaStatus(step, SlaStatus.MET)) {
            return;
        }
        consumedCounter.increment();
        log.debug("Step {} kept its {} threshold of {} — transition consumed",
                step.getId(), row.getTransitionType(), row.getProcessBy());
    }

    /**
     * Write {@code sla_status}, recording the transition in history, unless the step is already at a
     * status this one must not overwrite.
     *
     * <p>Forward-only. {@code MET} and {@code MISSED} are settled outcomes, and {@code OVERDUE} must
     * never replace {@code MISSED} — which is what would happen if the two rows for a step were applied
     * out of order after a retry. {@code MET} is written only from null, so a step already found
     * {@code OVERDUE} cannot be relabelled as having been on time.
     *
     * @return whether the status was written
     */
    private boolean writeSlaStatus(StepInstance step, SlaStatus target) {
        SlaStatus current = step.getSlaStatus();
        boolean allowed = target == SlaStatus.MET
                ? current == null
                : rank(target) > rank(current);
        if (!allowed) {
            log.debug("Step {} is already {} — not writing {}", step.getId(), current, target);
            return false;
        }

        step.setSlaStatus(target);
        stepInstanceRepository.save(step);
        stateTransitionHistoryService.recordStepInstanceTransition(
                step, OffsetDateTime.now(ZoneOffset.UTC));
        appliedCounter.increment();

        log.info("Step {} (actionId={}) SLA {} -> {}",
                step.getId(), step.getActionId(), current, target);
        return true;
    }

    /** Ordering for the forward-only rule. Null is "not yet judged", so it precedes every outcome. */
    private static int rank(SlaStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case OVERDUE -> 1;
            case MISSED, MET -> 2;
        };
    }

    /**
     * Whether this row is an optional step's missed threshold.
     *
     * <p>A {@code MISSED} deviation is {@code must}-only, so an optional step neither takes the status
     * nor the deviation. An {@code OVERDUE} carries no such exemption: optional work can still be
     * reported as running late.
     */
    private boolean isOptionalMiss(StepSlaStateTransition row, StepInstance step) {
        return row.getTransitionType() == SlaTransitionType.MISSED_DATE_REACHED
                && "could".equals(step.getRequiredBehavior());
    }

    /**
     * The deviation a breach produces: the due date an {@code OVERDUE}, the missed date a
     * {@code MISSED}. Intelligence is evaluated only for a freshly created deviation, so a re-claimed
     * row cannot publish the same intelligence event twice.
     */
    private void raiseDeviationFor(StepSlaStateTransition row, StepInstance step) {
        DeviationType type = row.getTransitionType() == SlaTransitionType.DUE_DATE_REACHED
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
