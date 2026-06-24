-- =============================================
-- 3. facility_reference
-- =============================================
CREATE TABLE facility_reference (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    facility_id                 VARCHAR         NOT NULL,
    facility_name               VARCHAR         NOT NULL,
    expected_patients_per_day   INTEGER,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT facility_reference_pkey PRIMARY KEY (id),
    CONSTRAINT facility_reference_facility_id_key UNIQUE (facility_id)
);

ALTER TABLE facility_reference REPLICA IDENTITY FULL;
