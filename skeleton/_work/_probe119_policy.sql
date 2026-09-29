\set ON_ERROR_STOP on
BEGIN;
ALTER POLICY tenant_isolation ON device USING (true) WITH CHECK (true);
SELECT qual FROM pg_policies WHERE tablename='device';
ROLLBACK;
