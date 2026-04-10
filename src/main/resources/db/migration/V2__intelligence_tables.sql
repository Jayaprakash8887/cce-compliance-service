-- ==============================================================================
-- CCE Compliance Service — Intelligence Tables
-- ==============================================================================
-- Flyway Migration: V2
-- Database: PostgreSQL 16
-- Adds: action_definition, action_run, action_run_context
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
    target              VARCHAR,
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
-- 9. action_run
-- =============================================
CREATE TABLE action_run (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    action_definition_id    UUID            NOT NULL,
    protocol_instance_id    UUID            NOT NULL,
    step_instance_id        UUID,
    status                  VARCHAR         NOT NULL,
    intelligence_event_id   UUID,
    output_metadata         JSONB,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT action_run_pkey PRIMARY KEY (id),
    CONSTRAINT action_run_action_definition_id_fkey
        FOREIGN KEY (action_definition_id) REFERENCES action_definition(id),
    CONSTRAINT action_run_protocol_instance_id_fkey
        FOREIGN KEY (protocol_instance_id) REFERENCES protocol_instance(id),
    CONSTRAINT action_run_step_instance_id_fkey
        FOREIGN KEY (step_instance_id) REFERENCES step_instance(id)
);

CREATE INDEX idx_action_run_protocol_instance ON action_run (protocol_instance_id);
CREATE INDEX idx_action_run_action_definition ON action_run (action_definition_id);
CREATE INDEX idx_action_run_status ON action_run (status);
CREATE INDEX idx_action_run_step_instance ON action_run (step_instance_id) WHERE step_instance_id IS NOT NULL;

-- =============================================
-- 10. action_run_context
-- =============================================
CREATE TABLE action_run_context (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    action_run_id           UUID            NOT NULL,
    deviation_id            UUID,
    trigger_reason          VARCHAR         NOT NULL,
    step_action_id          VARCHAR,
    evaluation_expression   TEXT,
    evaluation_context      JSONB,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT action_run_context_pkey PRIMARY KEY (id),
    CONSTRAINT action_run_context_action_run_id_fkey
        FOREIGN KEY (action_run_id) REFERENCES action_run(id),
    CONSTRAINT action_run_context_deviation_id_fkey
        FOREIGN KEY (deviation_id) REFERENCES deviation(id),
    CONSTRAINT action_run_context_action_run_id_unique UNIQUE (action_run_id)
);

CREATE INDEX idx_action_run_context_action_run ON action_run_context (action_run_id);
CREATE INDEX idx_action_run_context_deviation ON action_run_context (deviation_id) WHERE deviation_id IS NOT NULL;
