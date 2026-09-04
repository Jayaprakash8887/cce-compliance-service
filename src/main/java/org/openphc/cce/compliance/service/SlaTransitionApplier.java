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
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.compliance.domain.repository.SlaTransitionFetchRepository;
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
 * Fetches due {@code step_sla_state_transition} rows and applies them.
 *
 * <p>The Matcher Service writes one row per threshold a step can cross and never touches it again.
 * Everything after that is owned here: deciding which rows are due, writing
 * {@code step_instance.sla_status}, raising the {@code OVERDUE} / {@code MISSED} deviation, recording
 * the transition in {@code step_instance_history}, and marking the row processed. There is no Kafka hop
 * and no HTTP call between the two services — they meet on this one table.
 *
 * <p>Fetch and apply share a transaction. The row lock taken by {@code FOR UPDATE SKIP LOCKED} is what
 * reserves the row, so concurrent instances drain disjoint sets with no lease table and no leader election, and a
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
 * completing event — against the threshold the row stands for. The wall clock never enters into it:
 * what a deadline <em>means</em> depends only on whether the work had happened by then.
 *
 * <p>Which column supplies that threshold depends on the verdict, and the split is deliberate:
 *
 * <ul>
 *   <li>{@code MET} is measured against {@code step_instance.due_date} — the deadline the work was
 *       expected by, a fact about the step, written once at creation and never updated. Whether the
 *       work was <em>on time</em> is a question about the step, so it is asked of the step.</li>
 *   <li>{@code OVERDUE} and {@code MISSED} are measured against the row's {@code process_by}. A breach
 *       is what the schedule exists to detect, and the row is what carries it.</li>
 * </ul>
 *
 * <p>The two normally hold the same instant: the Matcher Service writes {@code due_date} and the
 * {@code DUE_DATE_REACHED} row's {@code process_by} from one value in one transaction, and nothing
 * rewrites either afterwards — a retry defers {@code next_attempt_at}, never {@code process_by}. Where
 * they could diverge, a kept schedule that did not beat the step's own due date is consumed without a
 * status rather than being called on time; see {@link #recordedBeforeDueDate}.
 *
 * <p>Which is why a row is fetched for either of two reasons. Its schedule has come round and the work
 * must be judged against its threshold ({@code fetchDueTransitions}); or its step is already {@code COMPLETED}, in which case
 * {@code completed_at} is fixed, both thresholds are known, and the outcome can be settled immediately
 * rather than at a deadline that would only confirm it ({@code fetchCompletedStepTransitions}). The verdict is
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
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at < process_by} and
 *       {@code < due_date}</td>
 *       <td>{@code MET} — the work beat its deadline</td></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at < process_by} but
 *       {@code >= due_date}</td>
 *       <td>consume — no breach of the schedule, but the due date was not beaten either</td></tr>
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
 * <p>Nothing in the table turns on <em>when</em> a row is applied, which is what makes fetching a
 * completed step's rows early safe: the same columns decide the outcome whether the row is applied at
 * its scheduled time or the moment the completion is seen. Reading the due date off the step rather than
 * off the schedule strengthens that — the value the verdict turns on is one the sweep never rewrites.
 */
@Service
public class SlaTransitionApplier {

    private static final Logger log = LoggerFactory.getLogger(SlaTransitionApplier.class);

    /** Past this many attempts a row is logged as an error every cycle rather than failing quietly. */
    private static final int ATTEMPTS_BEFORE_ALERT = 5;

    private final SlaTransitionFetchRepository transitionRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final DeviationRecorder deviationRecorder;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final StateTransitionHistoryWriter stateTransitionHistoryWriter;
    private final String instanceId;
    private final int batchSize;
    private final Duration maxBackoff;
    private final Counter appliedCounter;
    private final Counter consumedCounter;

    public SlaTransitionApplier(SlaTransitionFetchRepository transitionRepository,
                                StepInstanceRepository stepInstanceRepository,
                                DeviationRecorder deviationRecorder,
                                IntelligenceActionEvaluator intelligenceActionEvaluator,
                                StateTransitionHistoryWriter stateTransitionHistoryWriter,
                                @Value("${cce.sla.instance-id:${HOSTNAME:local}}") String instanceId,
                                @Value("${cce.sla.batch-size:100}") int batchSize,
                                @Value("${cce.sla.max-backoff-seconds:3600}") long maxBackoffSeconds,
                                MeterRegistry meterRegistry) {
        this.transitionRepository = transitionRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
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
     * Fetch and apply one batch of due transitions.
     *
     * @param fetched populated with the id of every row fetched, so the caller can back them off if the
     *                transaction rolls back — the list is plain memory and survives the rollback
     * @return how many rows were fetched
     */
    @Transactional
    public int fetchAndApply(List<UUID> fetched) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<StepSlaStateTransition> batch =
                new ArrayList<>(transitionRepository.fetchDueTransitions(now, Limit.of(batchSize)));

        // Rows of steps that have already been completed, fetched ahead of their deadline. Nothing about
        // such a step can change any more — completed_at is fixed and its thresholds were written at
        // creation — so its outcome is knowable now, and waiting for the wall clock would only delay
        // recording what is already decided.
        int room = batchSize - batch.size();
        if (room > 0) {
            batch.addAll(transitionRepository.fetchCompletedStepTransitions(now, Limit.of(room)));
        }

        for (StepSlaStateTransition row : batch) {
            fetched.add(row.getId());
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
     * {@code completed_at}: at or after the threshold is a breach, before it is not. A completed step
     * with no {@code completed_at} is treated as a breach — the row is the better evidence than a
     * missing timestamp, and silently letting it pass would hide the gap.
     */
    private boolean breachedThreshold(StepSlaStateTransition row, StepInstance step) {
        if (step.getStepStatus() != StepStatus.COMPLETED) {
            return true;
        }
        OffsetDateTime completedAt = step.getCompletedAt();
        return completedAt == null || !completedAt.isBefore(row.getProcessBy());
    }

    /**
     * Was the work recorded before the step's own due date?
     *
     * <p>The {@code MET} question, and the only one asked of {@code step_instance.due_date}: the
     * deadline the work was expected by, fixed when the step was created. {@code OVERDUE} is not asked
     * of it — a breach is decided by the row's {@code process_by}, in {@link #breachedThreshold} — so
     * the two verdicts cite different columns deliberately.
     *
     * <p>They normally agree, because the Matcher Service writes {@code due_date} and the
     * {@code DUE_DATE_REACHED} row's {@code process_by} from one value in one transaction, and nothing
     * afterwards rewrites either: a retry defers {@code next_attempt_at}, never {@code process_by}. So
     * the two open cases below are unreachable while that holds, and defined rather than left implicit
     * in case it stops holding.
     *
     * <ul>
     *   <li>Recorded before {@code process_by} but not before {@code due_date} — no breach, and not
     *       {@code MET} either. The row is consumed and {@code sla_status} is left as it stands, which
     *       is the same treatment a missed-date row gets for keeping its threshold.</li>
     *   <li>Recorded before {@code due_date} but not before {@code process_by} — a breach, so
     *       {@code OVERDUE}, and this method is never reached.</li>
     * </ul>
     *
     * <p>Falls back to {@code process_by} when the step has no due date, which one created before the
     * column existed can lack. That was where the deadline lived, so it is the only evidence left; it
     * is logged because a step created since should always carry one.
     */
    private boolean recordedBeforeDueDate(StepSlaStateTransition row, StepInstance step) {
        OffsetDateTime completedAt = step.getCompletedAt();
        if (completedAt == null) {
            return false;
        }
        OffsetDateTime dueDate = step.getDueDate();
        if (dueDate == null) {
            log.warn("Step {} has no due_date — settling transition {} against its process_by instead",
                    step.getId(), row.getId());
            dueDate = row.getProcessBy();
        }
        return completedAt.isBefore(dueDate);
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
            // Already at or past this outcome: a re-fetched row, or rows applied out of order.
            consumedCounter.increment();
            return;
        }

        raiseDeviationFor(row, step);
    }

    /**
     * The threshold was kept. Only the due date settles an SLA as {@code MET}: beating the missed date
     * says nothing more than that the step was not written off, and the due-date row has already
     * recorded whether it was on time.
     *
     * <p>{@code MET} is measured against {@code step_instance.due_date} rather than the row that
     * brought us here — see {@link #recordedBeforeDueDate}. A due-date row whose threshold was kept but
     * whose step's own due date was not beaten is consumed without a status, the same as a missed-date
     * row that was kept.
     */
    private void applyKeptDeadline(StepSlaStateTransition row, StepInstance step) {
        if (row.getTransitionType() == SlaTransitionType.DUE_DATE_REACHED
                && recordedBeforeDueDate(row, step)
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
        stateTransitionHistoryWriter.recordStepInstanceTransition(
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
     * {@code MISSED}. Intelligence is evaluated only for a freshly created deviation, so a re-fetched
     * row cannot publish the same intelligence event twice.
     */
    private void raiseDeviationFor(StepSlaStateTransition row, StepInstance step) {
        DeviationType type = row.getTransitionType() == SlaTransitionType.DUE_DATE_REACHED
                ? DeviationType.OVERDUE
                : DeviationType.MISSED;

        DeviationRecorder.DeviationResult result = deviationRecorder.recordDeviation(step, type);
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
