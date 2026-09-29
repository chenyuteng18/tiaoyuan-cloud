SELECT 'customer_nn:'||string_agg(column_name, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='customer' AND is_nullable='NO'
UNION ALL SELECT 'tenant_nn:'||string_agg(column_name, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='tenant' AND is_nullable='NO'
UNION ALL SELECT 'band_chk:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band'::regclass AND contype='c'
UNION ALL SELECT 'cust_chk:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='customer'::regclass AND contype='c'
UNION ALL SELECT 'band_fk:'||conname||'='||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='band'::regclass AND contype='f'
UNION ALL SELECT 'cust_nn_defaults:'||coalesce(string_agg(column_name||'~'||coalesce(column_default,'-'), ', '),'-') FROM information_schema.columns WHERE table_name='customer' AND is_nullable='NO';
