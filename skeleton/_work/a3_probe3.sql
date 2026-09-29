SELECT 'band:'||string_agg(column_name||':'||data_type||CASE WHEN is_nullable='NO' THEN '!' ELSE '' END, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='band'
UNION ALL SELECT 'customer:'||string_agg(column_name, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='customer'
UNION ALL SELECT 'tenant:'||string_agg(column_name, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='tenant';
