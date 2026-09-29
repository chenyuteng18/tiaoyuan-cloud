\pset pager off
\echo '=== device_dispatch 外键 ==='
SELECT con.conname,
       pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
 WHERE c.relname = 'device_dispatch' AND con.contype = 'f'
 ORDER BY con.conname;

\echo '=== device 表约束 ==='
SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
 WHERE c.relname = 'device'
 ORDER BY con.contype, con.conname;

\echo '=== device 上的唯一/主键（复合 FK 引用目标所需） ==='
SELECT i.relname AS index_name, pg_get_indexdef(i.oid) AS def
  FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class t ON t.oid = x.indrelid
 WHERE t.relname = 'device' AND (x.indisunique OR x.indisprimary);

\echo '=== scale 表约束 ==='
SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
 WHERE c.relname = 'scale'
 ORDER BY con.contype, con.conname;

\echo '=== baseline_assessment 外键 ==='
SELECT con.conname, pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
 WHERE c.relname = 'baseline_assessment' AND con.contype = 'f'
 ORDER BY con.conname;

\echo '=== device / scale 行数 ==='
SELECT (SELECT count(*) FROM device) AS device_rows,
       (SELECT count(*) FROM scale)  AS scale_rows,
       (SELECT count(*) FROM device_dispatch) AS dispatch_rows,
       (SELECT count(*) FROM store) AS store_rows,
       (SELECT count(*) FROM plan)  AS plan_rows;

\echo '=== schema_migration 已登记版本 ==='
SELECT version, left(description, 60) FROM schema_migration ORDER BY version;