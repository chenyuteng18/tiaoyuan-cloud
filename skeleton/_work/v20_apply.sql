\set ON_ERROR_STOP on
BEGIN;
\echo '-- applying V20 --'
\i dy-app/src/main/resources/db/migration/V20__case_archive_provisioning.sql
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum,
   installed_by, installed_on, execution_time, success)
SELECT coalesce(max(installed_rank), 0) + 1, '20',
       'case archive provisioning', 'SQL',
       'V20__case_archive_provisioning.sql', 2041181838,
       current_user, now(), 0, true
  FROM flyway_schema_history;
COMMIT;
\echo '-- V20 applied + registered --'
