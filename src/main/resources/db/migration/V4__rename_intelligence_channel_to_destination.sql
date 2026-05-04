-- V4: Intelligence destination cleanup.
-- 1. action_definition: drop severity and intelligence_channel (no longer needed —
--    values are now required on each PlanDefinition intelligence action extension).
-- 2. intelligence_event_log: rename intelligence_channel → intelligence_destination.

ALTER TABLE action_definition DROP COLUMN IF EXISTS severity;
ALTER TABLE action_definition DROP COLUMN IF EXISTS intelligence_channel;

ALTER TABLE intelligence_event_log
    RENAME COLUMN intelligence_channel TO intelligence_destination;
