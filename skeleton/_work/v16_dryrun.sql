\set ON_ERROR_STOP on
BEGIN;
\i C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V16__cross_tenant_reference_integrity.sql
ROLLBACK;
