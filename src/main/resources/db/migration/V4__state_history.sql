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
--   - Append-only: rows are only ever INSERTed (by the application). Never UPDATE/DELETE.
--   - Captured at the APPLICATION layer by StateTransitionHistoryService, invoked from
--     ProtocolInstanceService / StepInstanceService immediately after every status/state
--     write — enrollment, event-driven completion, scheduler-driven DUE/OVERDUE/MISSED,
--     and auto-skip. The history INSERT runs in the SAME transaction as the base-row change
--     (Propagation.MANDATORY), so it is all-or-nothing with the transition (no gaps).
--     NOTE: this does NOT capture changes made by raw out-of-band SQL — all lifecycle
--     mutations must go through the service layer.
--   - CDC wiring (publication membership, grants, replica identity) is configured centrally
--     in data-pipeline/cdc/01-configure-replication.sql — NOT here. cdc/01 sets REPLICA IDENTITY
--     FULL on every CDC table uniformly; for these append-only tables that is a harmless no-op
--     (the default PK identity would also suffice), kept for consistency.
--
-- ORDER: history tables, then CDC plumbing notes.
-- =============================================================================

-- =============================================
-- 1. History tables (append-only)
-- =============================================

CREATE TABLE protocol_instance_history (
    id                      BIGSERIAL       NOT NULL,
    protocol_instance_id    UUID            NOT NULL,   -- backfill joins protocol_instance on this id to recover protocol_definition_id
    status                  VARCHAR         NOT NULL,   -- recorded as-is from protocol_instance.status (already validated there)
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_instance_history_pkey PRIMARY KEY (id)
    -- No enum CHECK on purpose: the value is copied by the application from the parent row, which
    -- already enforces its own status CHECK. Re-checking here is redundant AND a drift hazard —
    -- the INSERT runs in the parent's transaction, so a history CHECK that lags a future parent
    -- enum change would fail the INSERT and roll back the parent write. History records faithfully.
);
-- No secondary indexes on purpose: this is a write-only CDC source table. Nothing in PostgreSQL
-- reads it by these columns (Debezium snapshots it by PK / sequential scan and streams from the WAL;
-- the app never queries it). The as-of-date GROUP BY queries run in ClickHouse, served by its
-- ORDER BY + partitioning — not by PG indexes. Adding indexes here is pure INSERT overhead on a
-- high-churn append-only table. Only the PK is kept (Debezium needs it).

CREATE TABLE step_instance_history (
    id                      BIGSERIAL       NOT NULL,
    step_instance_id        UUID            NOT NULL,   -- backfill joins step_instance on this id to recover protocol_instance_id
    state                   VARCHAR         NOT NULL,   -- recorded as-is from step_instance.state (already validated there)
    completion_status       VARCHAR,                     -- recorded as-is from step_instance.completion_status
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_instance_history_pkey PRIMARY KEY (id)
    -- No enum CHECK on purpose (same rationale as protocol_instance_history): values are copied by
    -- the application from the parent row, which already enforces its state/completion_status CHECKs.
    -- A history CHECK lagging a future parent enum change would roll back the parent write.
);
-- No secondary indexes (same rationale as protocol_instance_history): write-only CDC source,
-- read only by Debezium (PK/seq scan + WAL). Only the PK is kept.

-- =============================================
-- 2. CDC plumbing — managed centrally in the data-pipeline
-- =============================================
-- REPLICA IDENTITY, publication membership, and CDC-user SELECT grants are all set in
-- data-pipeline/cdc/01-configure-replication.sql (single source of truth for CDC config).
-- cdc/01 sets REPLICA IDENTITY FULL on every CDC table uniformly; for these append-only tables
-- it is a harmless no-op (default PK identity would suffice — FULL only matters for UPDATE/DELETE
-- old-row images, which never occur here), kept for consistency. On existing environments,
-- cdc/01 (or these statements) must run:
--   ALTER PUBLICATION cce_analytics_pub ADD TABLE public.protocol_instance_history, public.step_instance_history;
--   GRANT SELECT ON protocol_instance_history, step_instance_history TO cce_cdc_user;
