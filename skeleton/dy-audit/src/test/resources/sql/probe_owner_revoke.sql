\set ON_ERROR_STOP on

CREATE TABLE audit_probe (
    id     UUID PRIMARY KEY,
    hash   CHAR(64) NOT NULL
);

INSERT INTO audit_probe (id, hash) VALUES ('11111111-1111-1111-1111-111111111111', repeat('a', 64));

\echo '--- owner (app user, non-superuser) BEFORE revoke is allowed by both USAGE and OWNERSHIP ---'
SELECT current_user, rolsuper FROM pg_roles WHERE rolname = current_user;
UPDATE audit_probe SET hash = repeat('b', 64);
\echo 'UPDATE allowed before revoke: OK (this proves the test to follow is NOT vacuous)'

-- The app must own the table: that is the realistic deployment shape for this
-- skeleton (the real migration is applied by the superuser and ownership handed
-- to the app user), and it is the ONLY shape in which the revoke below is
-- load-bearing -- an ownership grant re-adds every privilege, so no-op'ing the
-- revoke would be invisibly papered over by ownership.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_probe FROM dy_audit_probe;

\echo '--- after REVOKE: these three MUST fail; any that succeeds means append-only is not enforced ---'

-- 1) UPDATE
DO $$
BEGIN
    BEGIN
        UPDATE audit_probe SET hash = repeat('c', 64);
        RAISE EXCEPTION 'PROBE-FAIL: UPDATE was allowed after REVOKE (append-only broken)';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'PROBE-OK: UPDATE denied, SQLSTATE=%', SQLSTATE;
    END;
END $$;

-- 2) DELETE
DO $$
BEGIN
    BEGIN
        DELETE FROM audit_probe;
        RAISE EXCEPTION 'PROBE-FAIL: DELETE was allowed after REVOKE (append-only broken)';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'PROBE-OK: DELETE denied, SQLSTATE=%', SQLSTATE;
    END;
END $$;

-- 3) TRUNCATE (the cheap way to destroy the chain without DELETE)
DO $$
BEGIN
    BEGIN
        TRUNCATE audit_probe;
        RAISE EXCEPTION 'PROBE-FAIL: TRUNCATE was allowed after REVOKE (chain can be wiped)';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'PROBE-OK: TRUNCATE denied, SQLSTATE=%', SQLSTATE;
    END;
END $$;

\echo '--- INSERT must STILL work, otherwise we broke the product to satisfy the test ---'
INSERT INTO audit_probe (id, hash) VALUES ('22222222-2222-2222-2222-222222222222', repeat('d', 64));
\echo 'PROBE SUMMARY: revoke, owner-removed + non-superuser, update/delete/truncate denied, insert allowed'