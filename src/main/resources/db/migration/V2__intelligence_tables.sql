-- ==============================================================================
-- CCE Compliance Service — Intelligence Tables
-- ==============================================================================
-- Flyway Migration: V2
-- Database: PostgreSQL 16
-- Adds: action_definition, intelligence_event_log
-- ==============================================================================

-- =============================================
-- 8. action_definition
-- =============================================
CREATE TABLE action_definition (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    canonical_url       VARCHAR         NOT NULL,
    version             VARCHAR         NOT NULL,
    name                VARCHAR,
    title               VARCHAR,
    status              VARCHAR         NOT NULL,
    action_type         VARCHAR         NOT NULL,
    severity            VARCHAR,
    intelligence_channel VARCHAR,
    definition          JSONB           NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT action_definition_pkey PRIMARY KEY (id),
    CONSTRAINT action_definition_url_version_key UNIQUE (canonical_url, version),
    CONSTRAINT action_definition_status_check
        CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX idx_action_definition_status ON action_definition (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_action_definition_canonical ON action_definition (canonical_url);

-- =============================================
-- 9. intelligence_event_log
-- =============================================
-- Single flat table replacing action_run + action_run_context.
-- Stores the published event payload (fat event) alongside audit context.
-- No FK relationships — IDs stored as plain UUIDs for decoupling.
CREATE TABLE intelligence_event_log (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),

    -- Published event payload (self-contained, all data needed for debugging & replay)
    event_payload           JSONB           NOT NULL,

    -- Denormalized keys for querying (extracted from event)
    action_definition_id    UUID            NOT NULL,
    protocol_instance_id    UUID            NOT NULL,
    step_instance_id        UUID,
    deviation_id            UUID,
    subject                 VARCHAR         NOT NULL,
    action_type             VARCHAR         NOT NULL,
    intelligence_channel    VARCHAR         NOT NULL,
    step_state              VARCHAR         NOT NULL,

    -- Audit context (not in the event — explains why this action fired)
    trigger_reason          VARCHAR         NOT NULL,
    step_action_id          VARCHAR,
    evaluation_expression   TEXT,
    evaluation_context      JSONB,

    -- Lifecycle
    published               BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at            TIMESTAMPTZ,
    error_message           TEXT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT intelligence_event_log_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_intelligence_event_log_action_definition ON intelligence_event_log (action_definition_id);
CREATE INDEX idx_intelligence_event_log_protocol_instance ON intelligence_event_log (protocol_instance_id);
CREATE INDEX idx_intelligence_event_log_step_instance ON intelligence_event_log (step_instance_id) WHERE step_instance_id IS NOT NULL;
CREATE INDEX idx_intelligence_event_log_subject ON intelligence_event_log (subject);
CREATE INDEX idx_intelligence_event_log_published ON intelligence_event_log (published) WHERE published = FALSE;
