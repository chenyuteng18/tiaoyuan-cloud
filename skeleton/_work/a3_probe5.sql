SELECT 'band_cols:'||string_agg(column_name||CASE WHEN is_nullable='NO' THEN '!' ELSE '?' END||'~'||coalesce(column_default,'-'), ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='band'
UNION ALL SELECT 'tenant_cols:'||string_agg(column_name||CASE WHEN is_nullable='NO' THEN '!' ELSE '?' END||'~'||coalesce(column_default,'-'), ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='tenant'
UNION ALL SELECT 'xmax_trick:'||('performed');
