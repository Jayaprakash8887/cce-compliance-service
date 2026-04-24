-- Add ORDER_VIOLATION to deviation_type CHECK constraint
ALTER TABLE deviation DROP CONSTRAINT deviation_type_check;
ALTER TABLE deviation ADD CONSTRAINT deviation_type_check
    CHECK (deviation_type IN ('OVERDUE', 'MISSED', 'ORDER_VIOLATION'));
