-- V19 勘察：scale 表的真实约束形态 + baseline_assessment 的外键形态 + 真库行数
\echo '=== A. scale 表的全部约束 ==='
SELECT con.contype AS 类型,
       con.conname AS 名称,
       pg_get_constraintdef(con.oid) AS 定义
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'scale'
 ORDER BY con.contype, con.conname;

\echo '=== B. baseline_assessment 上引用 scale 的外键 ==='
SELECT con.conname AS 名称,
       pg_get_constraintdef(con.oid) AS 定义
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
 WHERE c.relname = 'baseline_assessment' AND con.contype = 'f'
 ORDER BY con.conname;

\echo '=== C. scale 的行数（全库，无 RLS 上下文）==='
SELECT count(*) AS 行数 FROM scale;

\echo '=== D. scale 的 RLS 状态 ==='
SELECT c.relrowsecurity AS enable_rls, c.relforcerowsecurity AS force_rls
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'scale';

\echo '=== E. baseline_assessment 的行数 ==='
SELECT count(*) AS 行数 FROM baseline_assessment;

\echo '=== F. scale 的索引 ==='
SELECT indexname, indexdef FROM pg_indexes
 WHERE schemaname = 'public' AND tablename = 'scale' ORDER BY indexname;