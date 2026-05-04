-- Rename intelligence_channel to intelligence_destination on both tables.
-- No backward compatibility required (intelligence service not yet in production).

ALTER TABLE action_definition
    RENAME COLUMN intelligence_channel TO intelligence_destination;

ALTER TABLE intelligence_event_log
    RENAME COLUMN intelligence_channel TO intelligence_destination;
