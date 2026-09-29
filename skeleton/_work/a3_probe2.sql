SELECT 'cov_pk:'||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band_daily_coverage'::regclass AND contype='p'
UNION ALL SELECT 'band_uq:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band'::regclass AND contype IN ('p','u')
UNION ALL SELECT 'probe_checks:'||count(*)::text FROM pg_constraint WHERE conrelid='band_sync_probe'::regclass AND contype='c'
UNION ALL SELECT 'cov_checks:'||count(*)::text FROM pg_constraint WHERE conrelid='band_daily_coverage'::regclass AND contype='c'
UNION ALL SELECT 'sync_log_uq:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band_sync_log'::regclass AND contype='u'
UNION ALL SELECT 'band_ver:'||(version() LIKE '%PostgreSQL 17%')::text
UNION ALL SELECT 'cust_uq:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='customer'::regclass AND contype='u';
