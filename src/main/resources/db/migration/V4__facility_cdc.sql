-- =============================================
-- 4. facility — CDC grants
-- =============================================
-- Grants SELECT on the facility table to the CDC replication user and adds the table
-- to the Debezium publication so changes are streamed to ClickHouse.
--
-- Prerequisites (run once per environment via data-pipeline/cdc/01-configure-replication.sql):
--   - cce_cdc_user role must exist
--   - cce_analytics_pub publication must exist

GRANT SELECT ON facility TO cce_cdc_user;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT FROM pg_publication_tables
        WHERE pubname = 'cce_analytics_pub' AND tablename = 'facility'
    ) THEN
        ALTER PUBLICATION cce_analytics_pub ADD TABLE facility;
    END IF;
END $$;
