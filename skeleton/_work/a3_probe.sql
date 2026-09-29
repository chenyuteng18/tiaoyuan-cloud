SELECT 'probe_pk:'||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band_sync_probe'::regclass AND contype='p'
UNION ALL SELECT 'cov_uq:'||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band_daily_coverage'::regclass AND contype='u'
UNION ALL SELECT 'cov_fk:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band_daily_coverage'::regclass AND contype='f'
UNION ALL SELECT 'rls_probe:'||relrowsecurity||'/'||relforcerowsecurity FROM pg_class WHERE relname='band_sync_probe'
UNION ALL SELECT 'rls_cov:'||relrowsecurity||'/'||relforcerowsecurity FROM pg_class WHERE relname='band_daily_coverage'
UNION ALL SELECT 'fn_assert_ctx:'||count(*) FROM pg_proc WHERE proname='assert_tenant_context'
UNION ALL SELECT 'band_pk:'||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band'::regclass AND contype='p'
UNION ALL SELECT 'cust_fk_target:'||pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='band_daily_coverage_customer_id_fkey';
