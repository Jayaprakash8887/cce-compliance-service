-- =============================================================================
-- V4: State-transition history for protocol_instance and step_instance
-- =============================================================================
-- WHY THIS EXISTS
--   protocol_instance.status and step_instance.state/completion_status are
--   UPDATE-in-place lifecycle columns. The old value is overwritten on every
--   transition, so "what state was this on date D" is unanswerable from the
--   base row alone. These append-only history tables record every transition
--   with its timestamp, making point-in-time analytics (and a full ClickHouse
--   rebuild via CDC re-snapshot) reconstructible.
--
-- DESIGN
--   - Append-only: rows are only ever INSERTed (by triggers). Never UPDATE/DELETE.
--   - Captured by AFTER INSERT OR UPDATE triggers — fires on EVERY code path
--     (event-driven completion, scheduler-driven DUE/OVERDUE/MISSED, auto-skip,
--      manual SQL) and is transactional with the row change (no gaps).
--   - A one-time seed from current state is included but DISABLED by default (section 2) —
--     enable it only when a re-snapshot backfill needs pre-V4 rows. Forward capture does not
--     depend on it.
--   - CDC wiring (publication membership, grants, replica identity) is configured
--     centrally in data-pipeline/cdc/01-configure-replication.sql — NOT here. These
--     tables are append-only, so the default PK replica identity is sufficient.
--
-- ORDER: tables -> (optional seed, disabled) -> triggers.
-- =============================================================================

-- =============================================
-- 1. History tables (append-only)
-- =============================================

CREATE TABLE protocol_instance_history (
    id                      BIGSERIAL       NOT NULL,
    protocol_instance_id    UUID            NOT NULL,
    protocol_definition_id  UUID            NOT NULL,   -- denormalized (immutable on the base row) for backfill grouping
    status                  VARCHAR         NOT NULL,   -- recorded as-is from protocol_instance.status (already validated there)
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_instance_history_pkey PRIMARY KEY (id)
    -- No enum CHECK on purpose: the value is copied by the trigger from the parent row, which
    -- already enforces its own status CHECK. Re-checking here is redundant AND a drift hazard —
    -- the trigger runs in the parent's transaction, so a history CHECK that lags a future parent
    -- enum change would fail the INSERT and roll back the parent write. History records faithfully.
);
-- No secondary indexes on purpose: this is a write-only CDC source table. Nothing in PostgreSQL
-- reads it by these columns (Debezium snapshots it by PK / sequential scan and streams from the WAL;
-- the app never queries it). The as-of-date GROUP BY queries run in ClickHouse, served by its
-- ORDER BY + partitioning — not by PG indexes. Adding indexes here is pure INSERT overhead on a
-- high-churn append-only table. Only the PK is kept (Debezium needs it).

CREATE TABLE step_instance_history (
    id                      BIGSERIAL       NOT NULL,
    step_instance_id        UUID            NOT NULL,
    protocol_instance_id    UUID            NOT NULL,   -- denormalized (immutable on the base row) for backfill grouping
    state                   VARCHAR         NOT NULL,   -- recorded as-is from step_instance.state (already validated there)
    completion_status       VARCHAR,                     -- recorded as-is from step_instance.completion_status
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_instance_history_pkey PRIMARY KEY (id)
    -- No enum CHECK on purpose (same rationale as protocol_instance_history): values are copied by
    -- the trigger from the parent row, which already enforces its state/completion_status CHECKs.
    -- A history CHECK lagging a future parent enum change would roll back the parent write.
);
-- No secondary indexes (same rationale as protocol_instance_history): write-only CDC source,
-- read only by Debezium (PK/seq scan + WAL). Only the PK is kept.

-- =============================================
-- 2. Seed from current state — DISABLED (intentionally commented out)
-- =============================================
-- The triggers in section 3 capture every transition going FORWARD. This seed only matters for
-- reconstructing rows that existed BEFORE this migration during a full ClickHouse re-snapshot
-- backfill (data-pipeline/schema/09). With no re-snapshot planned, it is not needed yet, so it is
-- left disabled to keep the deploy minimal.
--
-- TO ENABLE:
--   * Before an environment's FIRST deploy — simply uncomment the block below.
--   * AFTER V4 has already been applied (Flyway migrations are immutable — you cannot edit this
--     file) — run the INSERTs manually once, OR add them as a new V5 migration. In BOTH of those
--     later cases add a guard so rows the triggers already captured are not duplicated, e.g.:
--       ... WHERE NOT EXISTS (SELECT 1 FROM protocol_instance_history h WHERE h.protocol_instance_id = protocol_instance.id)
--
-- DR NOTE: an UNPLANNED re-snapshot (DISASTER-RECOVERY scenarios C/D) will not recover history for
-- rows that existed at V4 deploy and never changed afterward unless this seed has been run. This is
-- accepted for UAT; reconsider before PROD if emergency-DR history completeness matters.
/*
-- protocol_instance: EXACT. At most a 2-state path (ACTIVE -> COMPLETED) and both
-- timestamps survive on the base row (enrolled_at, updated_at).

INSERT INTO protocol_instance_history (protocol_instance_id, protocol_definition_id, status, changed_at)
SELECT id, protocol_definition_id, 'ACTIVE', enrolled_at
FROM protocol_instance;                              -- every enrollment started ACTIVE

INSERT INTO protocol_instance_history (protocol_instance_id, protocol_definition_id, status, changed_at)
SELECT id, protocol_definition_id, status, updated_at
FROM protocol_instance
WHERE status <> 'ACTIVE';                            -- + its terminal transition, if it has moved

-- step_instance: BEST-EFFORT. Reconstructed from the row's leftover timestamps.
--   PENDING (created_at) and COMPLETED (completed_at + completion_status) are EXACT.
--   DUE/OVERDUE/MISSED use the PLANNED threshold columns (~actual; scheduler lag may
--     shift the real transition by hours, occasionally a calendar day).
--   SKIPPED has no dedicated timestamp -> updated_at is the best available guess.
-- Intermediate states a step passed through before this migration were overwritten in
-- the base table and are not fully recoverable; this is the closest the columns allow.

INSERT INTO step_instance_history (step_instance_id, protocol_instance_id, state, completion_status, changed_at)
-- (a) every step starts PENDING
SELECT id, protocol_instance_id, 'PENDING', NULL, created_at
FROM step_instance
UNION ALL
-- (b) reached DUE (past PENDING, or completed after its due_date)
SELECT id, protocol_instance_id, 'DUE', NULL, due_date
FROM step_instance
WHERE due_date IS NOT NULL
  AND (state IN ('DUE', 'OVERDUE', 'MISSED')
       OR (state = 'COMPLETED' AND completed_at > due_date))
UNION ALL
-- (c) reached OVERDUE
SELECT id, protocol_instance_id, 'OVERDUE', NULL, overdue_date
FROM step_instance
WHERE overdue_date IS NOT NULL
  AND (state IN ('OVERDUE', 'MISSED')
       OR (state = 'COMPLETED' AND completed_at > overdue_date))
UNION ALL
-- (d) reached MISSED
SELECT id, protocol_instance_id, 'MISSED', NULL, missed_date
FROM step_instance
WHERE state = 'MISSED' AND missed_date IS NOT NULL
UNION ALL
-- (e) COMPLETED — exact
SELECT id, protocol_instance_id, 'COMPLETED', completion_status, completed_at
FROM step_instance
WHERE state = 'COMPLETED' AND completed_at IS NOT NULL
UNION ALL
-- (f) SKIPPED — no dedicated timestamp; updated_at is the best available
SELECT id, protocol_instance_id, 'SKIPPED', NULL, updated_at
FROM step_instance
WHERE state = 'SKIPPED';
*/

-- =============================================
-- 3. Trigger functions + triggers (capture every transition going forward)
-- =============================================

CREATE OR REPLACE FUNCTION log_protocol_instance_status() RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO protocol_instance_history
            (protocol_instance_id, protocol_definition_id, status, changed_at)
        VALUES (NEW.id, NEW.protocol_definition_id, NEW.status,
                COALESCE(NEW.enrolled_at, NEW.created_at, now()));
    ELSIF (NEW.status IS DISTINCT FROM OLD.status) THEN
        INSERT INTO protocol_instance_history
            (protocol_instance_id, protocol_definition_id, status, changed_at)
        VALUES (NEW.id, NEW.protocol_definition_id, NEW.status,
                COALESCE(NEW.updated_at, now()));
    END IF;
    RETURN NULL;  -- AFTER trigger: return value is ignored
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protocol_instance_history
    AFTER INSERT OR UPDATE ON protocol_instance
    FOR EACH ROW EXECUTE FUNCTION log_protocol_instance_status();

CREATE OR REPLACE FUNCTION log_step_instance_state() RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO step_instance_history
            (step_instance_id, protocol_instance_id, state, completion_status, changed_at)
        VALUES (NEW.id, NEW.protocol_instance_id, NEW.state, NEW.completion_status,
                COALESCE(NEW.created_at, now()));
    ELSIF (NEW.state IS DISTINCT FROM OLD.state
           OR NEW.completion_status IS DISTINCT FROM OLD.completion_status) THEN
        INSERT INTO step_instance_history
            (step_instance_id, protocol_instance_id, state, completion_status, changed_at)
        VALUES (NEW.id, NEW.protocol_instance_id, NEW.state, NEW.completion_status,
                COALESCE(NEW.updated_at, now()));
    END IF;
    RETURN NULL;  -- AFTER trigger: return value is ignored
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_step_instance_history
    AFTER INSERT OR UPDATE ON step_instance
    FOR EACH ROW EXECUTE FUNCTION log_step_instance_state();

-- =============================================
-- 4. CDC plumbing — managed centrally in the data-pipeline
-- =============================================
-- REPLICA IDENTITY, publication membership, and CDC-user SELECT grants are all set in
-- data-pipeline/cdc/01-configure-replication.sql (single source of truth for CDC config).
-- These tables are append-only (INSERT only), so the default PK-based replica identity is
-- sufficient — REPLICA IDENTITY FULL only matters for UPDATE/DELETE old-row images, which
-- never occur here. On existing environments, cdc/01 (or these statements) must run:
--   ALTER PUBLICATION cce_analytics_pub ADD TABLE public.protocol_instance_history, public.step_instance_history;
--   GRANT SELECT ON protocol_instance_history, step_instance_history TO cce_cdc_user;
