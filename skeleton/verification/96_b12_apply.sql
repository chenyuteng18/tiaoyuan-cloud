-- ============================================================================
-- 96_b12_apply.sql — 以超级用户应用【骨架真实迁移链】V1 + V2，再把 owner 移交非超级用户
-- 以 postgres 连 diaoyuanyun_rls_test_b12 执行。
--
-- 两个设计要点（沿用 015_apply.sql 的既有口径）：
--   ① 用 \i 直接引入骨架交付物里的迁移文件，【不复制粘贴】—— 否则验证的是
--      "我手里抄的一份 SQL"，而不是"我们要交付的迁移脚本"。
--      路径由 -v v1=<abs> / -v v2=<abs> 传入，避免 015_apply.sql L16 的硬编码盘符问题。
--   ② 把表 owner 移交给非超级用户：FORCE ROW LEVEL SECURITY 的语义正是"owner 也受策略约束"；
--      若 owner 是超级用户，超级用户本就绕过 RLS，FORCE 无从验证 —— 会变成假通过。
--
-- 用法：psql -v ON_ERROR_STOP=1 -v v1=<V1绝对路径> -v v2=<V2绝对路径> \
--            -v approle=dy_app_b12 -f 96_b12_apply.sql
-- ============================================================================
\set ON_ERROR_STOP on

\if :{?v1}
\else
  \echo '错误: 必须传 -v v1=<V1迁移绝对路径>'
  \quit 2
\endif
\if :{?v2}
\else
  \echo '错误: 必须传 -v v2=<V2迁移绝对路径>'
  \quit 2
\endif
\if :{?approle}
\else
  \echo '错误: 必须传 -v approle=<应用角色名>'
  \quit 2
\endif

\echo '-- 96: applying V1 (baseline) --'
\i :v1

\echo '-- 96: applying V2 (B entities: customer_state_transition + band) --'
\i :v2

-- dy_app_b12 需要 schema 使用权与建表权（PG15+ 已从 PUBLIC 撤销 public schema 的 CREATE）
GRANT USAGE, CREATE ON SCHEMA public TO :approle;

-- 移交 owner（使 FORCE RLS 可被真实验证）。遍历而非硬编码表名 ——
-- 硬编码会在"新增一张表却忘记移交 owner"时静默漏掉，使那张表的 FORCE 断言失去证明力。
-- 用 \gexec 而不是 DO 块：psql 的 :var 是客户端插值，DO 块体内的 :approle 不会被替换，
-- 而 \gexec 是把查询结果当 SQL 逐条执行，可正常拼进 :approle。
SELECT format('ALTER TABLE public.%I OWNER TO %I;', c.relname, :'approle')
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
 ORDER BY c.relname
\gexec

\echo '96_b12_apply OK: V1 + V2 applied FROM SKELETON; all public tables owner -> dy_app_b12 (non-superuser)'