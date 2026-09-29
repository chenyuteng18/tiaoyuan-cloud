\set ON_ERROR_STOP on
SELECT '== 索引差异（已应用 dev vs 重建 recon 各自的索引）==';
SELECT c.relname AS tbl, i.relname AS idx, pg_get_indexdef(i.oid) AS def
  FROM pg_index x JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_class c ON c.oid=x.indrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname<>'flyway_schema_history'
 ORDER BY c.relname, i.relname;
