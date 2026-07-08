-- =============================================
-- Drop DB-side UUID defaults now that ids are generated application-side as v7
-- =============================================
-- The id columns on protocol_instance, step_instance and deviation were
-- declared with DEFAULT gen_random_uuid() (UUID v4). The application now
-- assigns time-ordered UUID v7 values via Hibernate (UuidV7Generator), so the
-- default never fires. Removing it prevents a raw/out-of-band INSERT from
-- silently minting a random v4 id that would break the time-ordering these
-- tables now rely on. Existing rows are unaffected; the column type and NOT
-- NULL constraint are unchanged.

ALTER TABLE protocol_instance ALTER COLUMN id DROP DEFAULT;
ALTER TABLE step_instance ALTER COLUMN id DROP DEFAULT;
ALTER TABLE deviation ALTER COLUMN id DROP DEFAULT;
