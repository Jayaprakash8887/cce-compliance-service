package org.openphc.cce.compliance.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.Deviation;
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
import org.springframework.data.domain.Limit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The applier is the only writer of {@code step_instance.sla_status}, so these tests are the whole
 * specification of how a step's timeliness gets decided. Matcher records {@code completed_at}; every
 * judgement made from it is here.
 */
@ExtendWith(MockitoExtension.class)
class SlaTransitionApplierTest {

    @Mock private SlaTransitionClaimRepository transitionRepository;
    @Mock private StepInstanceRepository stepInstanceRepository;
    @Mock private DeviationService deviationService;
    @Mock private IntelligenceActionEvaluator intelligenceActionEvaluator;
    @Mock private StateTransitionHistoryService stateTransitionHistoryService;

    private SlaTransitionApplier applier;
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        applier = new SlaTransitionApplier(transitionRepository, stepInstanceRepository,
                deviationService, intelligenceActionEvaluator, stateTransitionHistoryService,
                "test-instance", 100, 3600, new SimpleMeterRegistry());
    }

    // ── the work never arrived ──

    @Nested
    class OutstandingStep {

        @Test
        void dueDateReached_becomesOverdueWithAnOverdueDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            // crossing a deadline says nothing about whether the event arrived
            assertEquals(StepStatus.NOT_STARTED, step.getStepStatus());
            verify(deviationService).createDeviation(step, DeviationType.OVERDUE);
            assertTrue(row.isProcessed());
            assertEquals("test-instance", row.getProcessedBy());
        }

        @Test
        void missedDateReached_mandatory_becomesMissedWithAMissedDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.MISSED);
        }

        @Test
        void missedDateReached_optional_recordsNeitherStatusNorDeviation() {
            // Nothing was required of an optional step, so nothing was breached by its not happening.
            // It keeps the OVERDUE the due date gave it — being late is still a fact about it.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "could", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void everyStatusWriteIsRecordedInHistory() {
            // Without this the time-driven half of a step's timeline is missing from the CDC stream:
            // a step that went overdue and was never completed would show only its creation.
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            verify(stateTransitionHistoryService)
                    .recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }
    }

    // ── the work arrived; completed_at decides ──

    @Nested
    class CompletedStep {

        @Test
        void completedBeforeItsDueDate_isMet() {
            // The judgement the whole design turns on: the due-date row is what settles an on-time
            // completion, comparing the clinical completion time against the deadline.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MET, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void completedAfterItsDueDate_isOverdueWithADeviation() {
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedBetweenItsThresholds_staysOverdueRatherThanBecomingMet() {
            // The trap in the design: this step beat its missed date, but "did not breach this
            // threshold" only means MET at the due date. Reading it as MET here would relabel a late
            // completion as on time.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusHours(2));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void completedAfterItsMissedDate_isMissedWithADeviation() {
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusHours(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.MISSED);
        }

        @Test
        void completedAfterItsMissedDate_optional_recordsNoMissedDeviation() {
            // must-only, on this path as much as the outstanding one: otherwise optional work done
            // late would be penalised while the same work never done at all was not.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "could", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusHours(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
        }

        @Test
        void completedAfterItsDueDate_optional_stillTakesAnOverdueDeviation() {
            // The exemption is MISSED-only: optional work can still be reported as running late.
            StepInstance step = step(StepStatus.COMPLETED, null, "could", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedExactlyAtItsThreshold_countsAsABreach() {
            OffsetDateTime threshold = now.minusHours(1);
            StepInstance step = step(StepStatus.COMPLETED, null, "must", threshold);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, threshold);
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedWithNoTimestamp_isTreatedAsABreach() {
            // The row is better evidence than a missing timestamp, and letting it pass would hide
            // the gap rather than surface it.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.OVERDUE);
        }
    }

    // ── the work arrived, and its deadlines have not ──

    @Nested
    class CompletedStepClaimedBeforeItsDeadline {

        @Test
        void earlyCompletion_isSettledMetWithoutWaitingForItsDueDate() {
            // The reason the second claim path exists. Nothing about this step can change any more, so
            // holding the verdict back until the due date would only delay recording what is decided.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(1));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.plusDays(7));
            claimForCompletedStep(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MET, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void bothOfAnEarlyCompletionsRowsAreSettledInOneBatch() {
            // Otherwise the missed-date row would sit pending until its own date and show up as backlog
            // on a step whose SLA was settled weeks earlier.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(1));
            StepSlaStateTransition dueRow = row(step, SlaTransitionType.DUE_DATE_REACHED, now.plusDays(7));
            StepSlaStateTransition missedRow =
                    row(step, SlaTransitionType.MISSED_DATE_REACHED, now.plusDays(14));
            when(transitionRepository.claimForCompletedSteps(any(), any()))
                    .thenReturn(List.of(dueRow, missedRow));
            when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.of(step));

            int count = applier.claimAndApply(new ArrayList<>());

            assertEquals(2, count);
            assertEquals(SlaStatus.MET, step.getSlaStatus());
            assertTrue(dueRow.isProcessed());
            assertTrue(missedRow.isProcessed());
            verify(deviationService, never()).createDeviation(any(), any());
        }

        @Test
        void aLateCompletionsFutureMissedRowIsConsumedRatherThanBecomingMissed() {
            // The early claim must not invent a breach: this step was recorded late, but before its
            // missed date, so it keeps the OVERDUE the due-date row gave it and takes no deviation.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusHours(1));
            StepSlaStateTransition row =
                    row(step, SlaTransitionType.MISSED_DATE_REACHED, now.plusDays(5));
            claimForCompletedStep(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void theEarlyClaimIsReportedForBackoffLikeAnyOther() {
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(1));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.plusDays(7));
            claimForCompletedStep(row, step);
            List<UUID> claimed = new ArrayList<>();

            applier.claimAndApply(claimed);

            assertEquals(List.of(row.getId()), claimed);
        }
    }

    // ── the two claims share one batch ──

    @Nested
    class BatchCapacity {

        @Test
        void theSecondClaimOnlyAsksForWhatTheFirstLeftRoomFor() {
            SlaTransitionApplier smallBatch = applierWithBatchSize(3);
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            claim(row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1)), step);
            freshDeviation();

            smallBatch.claimAndApply(new ArrayList<>());

            ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
            verify(transitionRepository).claimForCompletedSteps(any(), limit.capture());
            assertEquals(2, limit.getValue().max());
        }

        @Test
        void aFullDeadlineDrivenBatchSkipsTheSecondClaimEntirely() {
            // A backlog of fallen deadlines is the pressing work; the evaluator drains in further
            // cycles rather than widening one batch past its size.
            SlaTransitionApplier singleRowBatch = applierWithBatchSize(1);
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            claim(row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1)), step);
            freshDeviation();

            singleRowBatch.claimAndApply(new ArrayList<>());

            verify(transitionRepository, never()).claimForCompletedSteps(any(), any());
        }

        private SlaTransitionApplier applierWithBatchSize(int batchSize) {
            return new SlaTransitionApplier(transitionRepository, stepInstanceRepository,
                    deviationService, intelligenceActionEvaluator, stateTransitionHistoryService,
                    "test-instance", batchSize, 3600, new SimpleMeterRegistry());
        }
    }

    // ── the forward-only rule ──

    @Nested
    class OutOfOrderApplication {

        @Test
        void overdueDoesNotOverwriteMissed() {
            // Rows are claimed oldest-deadline-first, but a retried batch can still land out of
            // order. Re-applying the due date must not walk a written-off step back to OVERDUE.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.MISSED, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(2));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(stepInstanceRepository, never()).save(any());
            assertTrue(row.isProcessed());
        }

        @Test
        void metIsNotWrittenOverAnExistingJudgement() {
            // MET is written only from null. A step already found OVERDUE cannot be relabelled as
            // having been on time, however its rows are ordered.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusDays(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(stepInstanceRepository, never()).save(any());
        }

        @Test
        void reappliedBreachDoesNotRaiseASecondDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(2));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            verify(deviationService, never()).createDeviation(any(), any());
        }
    }

    @Nested
    class Retry {

        @Test
        void backOff_defersRowsExponentiallyInTheAttemptCount() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(3);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertTrue(row.getNextAttemptAt().isAfter(before));
            assertEquals(4, row.getAttempts());
        }

        @Test
        void backOff_isCappedSoABrokenRowIsStillRetriedOccasionally() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(40);
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertFalse(row.getNextAttemptAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3601)));
        }

        @Test
        void backOff_leavesAnAlreadyProcessedRowAlone() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setProcessed(true);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertEquals(before, row.getNextAttemptAt());
            verify(transitionRepository, never()).save(row);
        }
    }

    @Nested
    class Bookkeeping {

        @Test
        void duplicateDeviation_doesNotRepublishIntelligence() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);
            when(deviationService.createDeviation(any(), any())).thenReturn(
                    new DeviationService.DeviationResult(
                            Deviation.builder().id(UUID.randomUUID()).build(), false));

            applier.claimAndApply(new ArrayList<>());

            verify(intelligenceActionEvaluator, never()).evaluateOnDeviation(any(), any());
        }

        @Test
        void missingStep_consumesTheRowRatherThanRetryingForever() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            when(transitionRepository.claimDue(any(), any())).thenReturn(List.of(row));
            when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.empty());

            applier.claimAndApply(new ArrayList<>());

            assertTrue(row.isProcessed());
            verify(deviationService, never()).createDeviation(any(), any());
        }

        @Test
        void claimReportsEveryRowItTook_soAFailedBatchCanBeBackedOff() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();
            List<UUID> claimed = new ArrayList<>();

            int count = applier.claimAndApply(claimed);

            assertEquals(1, count);
            assertEquals(List.of(row.getId()), claimed);
        }

        @Test
        void repeatedFailuresAreEscalatedOnClaim() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(9);
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(10, row.getAttempts());
        }
    }

    private void claim(StepSlaStateTransition row, StepInstance step) {
        when(transitionRepository.claimDue(any(), any())).thenReturn(List.of(row));
        when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.of(step));
    }

    /** Claimed because its step is already completed, not because its deadline has passed. */
    private void claimForCompletedStep(StepSlaStateTransition row, StepInstance step) {
        when(transitionRepository.claimForCompletedSteps(any(), any())).thenReturn(List.of(row));
        when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.of(step));
    }

    private void freshDeviation() {
        when(deviationService.createDeviation(any(), any())).thenReturn(
                new DeviationService.DeviationResult(
                        Deviation.builder().id(UUID.randomUUID()).build(), true));
    }

    private StepInstance step(StepStatus stepStatus, SlaStatus slaStatus,
                             String requiredBehavior, OffsetDateTime completedAt) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("anc-visit-1")
                .repeatIndex(0)
                .stepStatus(stepStatus)
                .slaStatus(slaStatus)
                .requiredBehavior(requiredBehavior)
                .completedAt(completedAt)
                .build();
    }

    private StepSlaStateTransition row(StepInstance step, SlaTransitionType type,
                                       OffsetDateTime processBy) {
        return StepSlaStateTransition.builder()
                .id(UUID.randomUUID())
                .stepInstanceId(step.getId())
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build();
    }
}
