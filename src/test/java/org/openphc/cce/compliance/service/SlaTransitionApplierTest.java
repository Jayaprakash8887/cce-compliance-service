package org.openphc.cce.compliance.service;

import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;
import org.openphc.cce.common.service.DeviationService;
import org.openphc.cce.common.service.IntelligenceActionEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.compliance.domain.repository.SlaTransitionClaimRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaTransitionApplierTest {

    @Mock private SlaTransitionClaimRepository transitionRepository;
    @Mock private StepInstanceRepository stepInstanceRepository;
    @Mock private DeviationService deviationService;
    @Mock private IntelligenceActionEvaluator intelligenceActionEvaluator;

    private SlaTransitionApplier applier;
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        applier = new SlaTransitionApplier(transitionRepository, stepInstanceRepository,
                deviationService, intelligenceActionEvaluator, "test-instance", 100, 3600,
                new SimpleMeterRegistry());
    }

    // ── the ordinary case: the event never arrived ──

    @Nested
    class OutstandingStep {

        @Test
        void dueDateCrossed_advancesToOverdueAndRaisesOverdueDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.PENDING_TO_OVERDUE,
                    SlaStatus.PENDING, SlaStatus.OVERDUE, now.minusMinutes(1));
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
        void missedDateCrossed_mandatory_advancesToMissedAndRaisesMissedDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationService).createDeviation(step, DeviationType.MISSED);
        }

        @Test
        void missedDateCrossed_optional_settlesAsMetWithNoDeviation() {
            // An optional step breaches nothing by never arriving.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "could", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, now.minusMinutes(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MET, step.getSlaStatus());
            assertEquals(StepStatus.NOT_STARTED, step.getStepStatus(),
                    "stays NOT_STARTED — that is what distinguishes it from a real completion");
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void slaAlreadyAdvanced_consumesWithoutReapplying() {
            // A re-claimed row must not drag a later status back to an earlier one.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.MISSED, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.PENDING_TO_OVERDUE,
                    SlaStatus.PENDING, SlaStatus.OVERDUE, now.minusMinutes(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }
    }

    // ── rows that outlive their state ──

    @Nested
    class CompletedStep {

        @Test
        void completedBeforeThreshold_consumesWithoutFiring() {
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.MET, "must", now.minusHours(3));
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, now.minusHours(1));
            claim(row, step);

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MET, step.getSlaStatus(), "a completed step's SLA is final");
            verify(deviationService, never()).createDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void completedAfterThreshold_leavesSlaButStillRecordsTheBreach() {
            // Matcher already settled the SLA against this same threshold; the deviation is the only
            // thing the completion left unrecorded.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.MISSED, "must", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, now.minusHours(1));
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(stepInstanceRepository, never()).save(any());
            verify(deviationService).createDeviation(step, DeviationType.MISSED);
        }

        @Test
        void completedExactlyAtThreshold_countsAsABreach() {
            OffsetDateTime threshold = now.minusHours(1);
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.MISSED, "must", threshold);
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, threshold);
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            verify(deviationService).createDeviation(step, DeviationType.MISSED);
        }
    }

    @Nested
    class Retry {

        @Test
        void backOff_deferrreRowsExponentiallyInTheAttemptCount() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null),
                    SlaTransitionType.PENDING_TO_OVERDUE, SlaStatus.PENDING, SlaStatus.OVERDUE,
                    now.minusMinutes(1));
            row.setAttempts(3);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            // 2^3 = 8 seconds out, and the attempt count advances
            assertTrue(row.getNextAttemptAt().isAfter(before));
            assertEquals(4, row.getAttempts());
            assertTrue(row.getNextAttemptAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60)));
        }

        @Test
        void backOff_isCappedSoABrokenRowIsStillRetriedOccasionally() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null),
                    SlaTransitionType.PENDING_TO_OVERDUE, SlaStatus.PENDING, SlaStatus.OVERDUE,
                    now.minusMinutes(1));
            row.setAttempts(40);  // 2^40 seconds, far beyond the cap
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            // capped at the configured maxBackoff of 3600s, not 2^40
            assertTrue(row.getNextAttemptAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3700)));
        }

        @Test
        void backOff_leavesAnAlreadyProcessedRowAlone() {
            // The batch may have failed after this row committed in an earlier attempt.
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null),
                    SlaTransitionType.PENDING_TO_OVERDUE, SlaStatus.PENDING, SlaStatus.OVERDUE,
                    now.minusMinutes(1));
            row.setProcessed(true);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertEquals(before, row.getNextAttemptAt());
            verify(transitionRepository, never()).save(row);
        }

        @Test
        void repeatedFailuresAreEscalatedOnClaim() {
            // Past the alert threshold a claim logs an error every cycle rather than failing quietly.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.PENDING_TO_OVERDUE,
                    SlaStatus.PENDING, SlaStatus.OVERDUE, now.minusMinutes(1));
            row.setAttempts(9);
            claim(row, step);
            freshDeviation();

            applier.claimAndApply(new ArrayList<>());

            assertEquals(10, row.getAttempts());
            assertTrue(row.isProcessed(), "escalating must not stop the row being applied");
        }
    }

    @Nested
    class Idempotence {

        @Test
        void duplicateDeviation_doesNotRepublishIntelligence() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.OVERDUE_TO_MISSED,
                    SlaStatus.OVERDUE, SlaStatus.MISSED, now.minusMinutes(1));
            claim(row, step);
            when(deviationService.createDeviation(any(), any())).thenReturn(
                    new DeviationService.DeviationResult(
                            Deviation.builder().id(UUID.randomUUID()).build(), false));

            applier.claimAndApply(new ArrayList<>());

            verify(intelligenceActionEvaluator, never()).evaluateOnDeviation(any(), any());
        }

        @Test
        void missingStep_consumesTheRowRatherThanRetryingForever() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null),
                    SlaTransitionType.PENDING_TO_OVERDUE, SlaStatus.PENDING, SlaStatus.OVERDUE,
                    now.minusMinutes(1));
            when(transitionRepository.claimDue(any(), any())).thenReturn(List.of(row));
            when(stepInstanceRepository.findById(row.getStepInstanceId())).thenReturn(Optional.empty());

            applier.claimAndApply(new ArrayList<>());

            assertTrue(row.isProcessed());
            verify(deviationService, never()).createDeviation(any(), any());
        }

        @Test
        void claimReportsEveryRowItTook_soAFailedBatchCanBeBackedOff() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.PENDING, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.PENDING_TO_OVERDUE,
                    SlaStatus.PENDING, SlaStatus.OVERDUE, now.minusMinutes(1));
            claim(row, step);
            freshDeviation();

            List<UUID> claimed = new ArrayList<>();
            int count = applier.claimAndApply(claimed);

            assertEquals(1, count);
            assertEquals(List.of(row.getId()), claimed);
            assertEquals(1, row.getAttempts(), "attempts is incremented as the row is claimed");
        }
    }

    // ── helpers ──

    private void claim(StepSlaStateTransition row, StepInstance step) {
        when(transitionRepository.claimDue(any(), any())).thenReturn(List.of(row));
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
                                       SlaStatus from, SlaStatus to, OffsetDateTime processBy) {
        return StepSlaStateTransition.builder()
                .id(UUID.randomUUID())
                .stepInstanceId(step.getId())
                .transitionType(type)
                .fromStatus(from.name())
                .toStatus(to.name())
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build();
    }
}
