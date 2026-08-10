-- =============================================================================
-- V8: Reconcile legacy OVERDUE step instances back to DUE
-- =============================================================================
-- WHY THIS EXISTS
--   The DUE → OVERDUE transition has been removed from the step lifecycle: a DUE step now
--   waits for its missed_date and goes straight to MISSED (or SKIPPED for optional steps).
--   The Scheduler evaluates PENDING against due_date and DUE against missed_date, and no
--   longer scans OVERDUE at all (see cce-scheduler-service PR #12).
--
--   That leaves the rows adopted environments are already holding in OVERDUE stranded: never
--   scanned, never advanced, never terminalized. OVERDUE means "due_date passed, still not
--   completed, missed_date not yet acted on" — which is exactly DUE under the new lifecycle —
--   so moving them back to DUE re-enters them into the scan against their own missed_date.
--   Steps whose missed_date is still ahead cross it normally; steps already past it are
--   picked up on the next cycle. Neither due_date, overdue_date nor missed_date is touched:
--   the clinical schedule is unchanged, only the state label is corrected.
--
--   OVERDUE deviations already recorded are deliberately left alone. They are a historical
--   record of a threshold that genuinely was crossed, and DeviationType.OVERDUE is retained
--   read-only so they still map. No new ones are raised.
--
-- OUT-OF-BAND WRITE
--   V4 states that all lifecycle mutations go through the service layer so that
--   step_instance_history stays complete. This one-off reconciliation cannot — it predates
--   any running instance of the new code — so it appends its own history rows in the same
--   statement, keeping the append-only invariant intact.
--
-- IDEMPOTENT
--   Matches on state = 'OVERDUE', so a re-run is a no-op. The step_instance_state_check
--   constraint deliberately keeps 'OVERDUE' as a legal value: a Compliance instance still
--   running the old code during a rolling deploy can write one after this migration lands,
--   and StepInstanceService accepts a legacy OVERDUE step as a source state for MISSED.
-- =============================================================================

WITH reconciled AS (
    UPDATE step_instance
       SET state = 'DUE',
           updated_at = now()
     WHERE state = 'OVERDUE'
    RETURNING id, completion_status
)
INSERT INTO step_instance_history (step_instance_id, state, completion_status, changed_at)
SELECT id, 'DUE', completion_status, now()
  FROM reconciled;
