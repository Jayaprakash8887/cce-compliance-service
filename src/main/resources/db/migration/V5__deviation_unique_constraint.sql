-- =============================================
-- Deduplicate deviations and enforce one deviation per (step, type)
-- =============================================
-- A step can have at most one deviation of each type (OVERDUE, MISSED,
-- ORDER_VIOLATION). This constraint is a safety net against duplicate
-- deviations arising from redelivered scheduler triggers (Kafka is
-- at-least-once) or concurrent consumer threads processing the same
-- step transition. The application-level guard (applyTransition) is the
-- first line of defence; this constraint guarantees correctness under races.

-- Remove any pre-existing duplicates, keeping the earliest-detected row
-- (ties broken deterministically by id).
DELETE FROM deviation d
USING deviation dup
WHERE d.step_instance_id = dup.step_instance_id
  AND d.deviation_type = dup.deviation_type
  AND (d.detected_at, d.id) > (dup.detected_at, dup.id);

ALTER TABLE deviation
    ADD CONSTRAINT deviation_step_type_key UNIQUE (step_instance_id, deviation_type);
