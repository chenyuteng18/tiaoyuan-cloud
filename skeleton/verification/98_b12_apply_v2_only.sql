-- ============================================================================
-- 98_b12_apply_v2_only.sql — 仅应用 V2（幂等性证明用）
-- 以 postgres 连 diaoyuanyun_rls_test_b12 执行。
--
-- 用途：整链应用一次后，只重复应用本任务交付物 V2，
--       证明 V2 可幂等重复执行（schema 指纹不变、无重复插行）。
-- 与 96_b12_apply.sql 的差别：96 是"V1+V2 整链 + 移交 owner"，本脚本只做 V2 重放。
-- ============================================================================
\set ON_ERROR_STOP on

\if :{?v2}
\else
  \echo '错误: 必须传 -v v2=<V2迁移绝对路径>'
  \quit 2
\endif

\echo '-- 98: re-applying V2 only (idempotency probe) --'
\i :v2

\if :{?approle}
-- owner 移交（重放后再次确保；幂等）
SELECT format('ALTER TABLE public.%I OWNER TO %I;', c.relname, :'approle')
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
 ORDER BY c.relname
\gexec
\endif

\echo '98_b12_apply_v2_only OK: V2 re-applied with no error (idempotent)'