-- =============================================
-- 3. facility
-- =============================================
CREATE TABLE facility (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    facility_id                 VARCHAR         NOT NULL,
    facility_name               VARCHAR,
    expected_patients_per_day   INTEGER,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT facility_pkey PRIMARY KEY (id),
    CONSTRAINT facility_facility_id_key UNIQUE (facility_id)
);

ALTER TABLE facility REPLICA IDENTITY FULL;
