-- ==============================================================================
-- CCE Compliance Service — Initial Database Schema
-- ==============================================================================
-- Flyway Migration: V1
-- Database: PostgreSQL 16
-- ==============================================================================

-- =============================================
-- 1. protocol_definition
-- =============================================
CREATE TABLE protocol_definition (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    url                 VARCHAR         NOT NULL,
    version             VARCHAR         NOT NULL,
    status              VARCHAR         NOT NULL,
    definition          JSONB           NOT NULL,
    loaded_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_definition_pkey PRIMARY KEY (id),
    CONSTRAINT protocol_definition_url_version_key UNIQUE (url, version),
    CONSTRAINT protocol_definition_status_check CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX idx_protocol_definition_triggers ON protocol_definition USING GIN (definition jsonb_path_ops);

ALTER TABLE protocol_definition REPLICA IDENTITY FULL;

-- =============================================
-- 2. protocol_instance
-- =============================================
CREATE TABLE protocol_instance (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    patient_id              VARCHAR         NOT NULL,
    protocol_canonical      VARCHAR         NOT NULL,
    protocol_definition_id  UUID            NOT NULL,
    enrolled_at             TIMESTAMPTZ     NOT NULL,
    status                  VARCHAR         NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_instance_pkey PRIMARY KEY (id),
    CONSTRAINT protocol_instance_protocol_definition_id_fkey
        FOREIGN KEY (protocol_definition_id) REFERENCES protocol_definition(id),
    CONSTRAINT protocol_instance_status_check CHECK (status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN', 'EXPIRED'))
);

CREATE INDEX idx_protocol_instance_patient ON protocol_instance (patient_id);
CREATE INDEX idx_protocol_instance_status ON protocol_instance (status) WHERE status = 'ACTIVE';

ALTER TABLE protocol_instance REPLICA IDENTITY FULL;

-- =============================================
-- 3. step_instance
-- =============================================
CREATE TABLE step_instance (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    protocol_instance_id    UUID            NOT NULL,
    action_id               VARCHAR         NOT NULL,
    repeat_index            INTEGER         NOT NULL DEFAULT 0,
    state                   VARCHAR         NOT NULL,
    due_date                TIMESTAMPTZ,
    overdue_date            TIMESTAMPTZ,
    missed_date             TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    completed_by_source     VARCHAR,
    completion_status       VARCHAR,
    completed_by_event_id   UUID,
    required_behavior       VARCHAR,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_instance_pkey PRIMARY KEY (id),
    CONSTRAINT step_instance_protocol_instance_id_fkey
        FOREIGN KEY (protocol_instance_id) REFERENCES protocol_instance(id),
    CONSTRAINT step_instance_state_check
        CHECK (state IN ('PENDING', 'DUE', 'OVERDUE', 'MISSED', 'COMPLETED', 'SKIPPED')),
    CONSTRAINT step_instance_completion_status_check
        CHECK (completion_status IN ('EARLY', 'ON_TIME', 'LATE')),
    CONSTRAINT step_instance_required_behavior_check
        CHECK (required_behavior IN ('must', 'could', 'must-unless-documented'))
);

CREATE INDEX idx_step_instance_protocol ON step_instance (protocol_instance_id);
CREATE INDEX idx_step_instance_state ON step_instance (state) WHERE state IN ('PENDING', 'DUE', 'OVERDUE');
CREATE INDEX idx_step_instance_due_date ON step_instance (due_date) WHERE state IN ('PENDING', 'DUE', 'OVERDUE');

ALTER TABLE step_instance REPLICA IDENTITY FULL;

-- =============================================
-- 4. deviation
-- =============================================
CREATE TABLE deviation (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    protocol_instance_id    UUID            NOT NULL,
    step_instance_id        UUID            NOT NULL,
    deviation_type          VARCHAR         NOT NULL,
    detected_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    intelligence_event_id   UUID,
    metadata                JSONB,
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT deviation_pkey PRIMARY KEY (id),
    CONSTRAINT deviation_protocol_instance_id_fkey
        FOREIGN KEY (protocol_instance_id) REFERENCES protocol_instance(id),
    CONSTRAINT deviation_step_instance_id_fkey
        FOREIGN KEY (step_instance_id) REFERENCES step_instance(id),
    CONSTRAINT deviation_type_check CHECK (deviation_type IN ('OVERDUE', 'MISSED', 'ORDER_VIOLATION'))
);

CREATE INDEX idx_deviation_protocol ON deviation (protocol_instance_id);
CREATE INDEX idx_deviation_type ON deviation (deviation_type);

ALTER TABLE deviation REPLICA IDENTITY FULL;

-- =============================================
-- 5. trigger_index
-- =============================================
CREATE TABLE trigger_index (
    resource_type           VARCHAR         NOT NULL,
    path                    VARCHAR         NOT NULL,
    code_system             VARCHAR         NOT NULL DEFAULT '',
    code_value              VARCHAR         NOT NULL DEFAULT '',
    protocol_definition_id  UUID            NOT NULL,
    action_id               VARCHAR         NOT NULL,

    CONSTRAINT trigger_index_pkey PRIMARY KEY (resource_type, path, code_system, code_value, protocol_definition_id, action_id),
    CONSTRAINT trigger_index_protocol_definition_id_fkey
        FOREIGN KEY (protocol_definition_id) REFERENCES protocol_definition(id)
);

CREATE INDEX idx_trigger_index_resource ON trigger_index (resource_type);
CREATE INDEX idx_trigger_index_code ON trigger_index (resource_type, path, code_system, code_value);

ALTER TABLE trigger_index REPLICA IDENTITY FULL;

-- =============================================
-- 6. compliance_event_log
-- =============================================
CREATE TABLE compliance_event_log (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    cloudevents_id              VARCHAR         NOT NULL,
    source                      VARCHAR         NOT NULL,
    correlation_id              VARCHAR,
    processing_status           VARCHAR         NOT NULL,
    data                        JSONB,
    received_at                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT compliance_event_log_pkey PRIMARY KEY (id),
    CONSTRAINT compliance_event_log_cloudevents_id_source_key UNIQUE (cloudevents_id, source),
    CONSTRAINT compliance_event_log_processing_status_check CHECK (processing_status IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE'))
);

-- FK from step_instance to compliance_event_log
ALTER TABLE step_instance ADD CONSTRAINT step_instance_completed_by_event_id_fkey
    FOREIGN KEY (completed_by_event_id) REFERENCES compliance_event_log(id);

CREATE INDEX idx_step_instance_completed_event ON step_instance (completed_by_event_id)
    WHERE completed_by_event_id IS NOT NULL;

ALTER TABLE compliance_event_log REPLICA IDENTITY FULL;

-- =============================================
-- 7. audit_log
-- =============================================
CREATE TABLE audit_log (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    event_category      VARCHAR         NOT NULL,
    event_type          VARCHAR         NOT NULL,
    actor               VARCHAR,
    resource_type       VARCHAR,
    resource_id         VARCHAR,
    details             JSONB,
    timestamp           TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_category ON audit_log (event_category);
CREATE INDEX idx_audit_log_actor ON audit_log (actor);
CREATE INDEX idx_audit_log_timestamp ON audit_log (timestamp);

ALTER TABLE audit_log REPLICA IDENTITY FULL;
