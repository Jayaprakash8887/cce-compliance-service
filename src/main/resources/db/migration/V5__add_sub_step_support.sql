-- V5: Sub-step support.
-- Add parent_step_id to step_instance for parent-child step relationships.

ALTER TABLE step_instance ADD COLUMN parent_step_id UUID REFERENCES step_instance(id);

CREATE INDEX idx_step_instance_parent_step_id ON step_instance(parent_step_id);
