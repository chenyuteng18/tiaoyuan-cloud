-- ============================================================================
-- 015_apply.sql — 以超级用户应用【骨架里的真实迁移脚本】，再把表 owner 移交非超级用户
-- 以 postgres 连 diaoyuanyun_rls_test 执行。
--
-- 两个设计要点（决定验证是否有效）：
--   ① 用 \i / \ir 直接引入骨架交付物里的 V1 文件，【不复制粘贴】。
--      否则验证的是"我手里抄的一份 SQL"，而不是"我们要交付的迁移脚本"。
--      🛑 2026-09-27（C-2）：路径【不再是硬编码绝对路径】。改为双通道 ——
--         优先 -v v1=<绝对路径>（与 91/96/98_b12_*.sql 同一口径），
--         未传参时回落到 \ir 相对路径（相对本文件所在目录，即 verification/）。
--         旧版写死的 'C:/Users/lenovo/...' 使本脚本【只能在这一台机器上跑】：
--         接进 CI（linux runner / 任意检出路径）必然 P0002 / 42P01 失败，
--         而"只能在原作者机器上跑的验证"等于没有验证。
--   ② 把表 owner 移交给 dy_app（非超级用户）。
--      因为 FORCE ROW LEVEL SECURITY 的语义正是"owner 也要受策略约束"。
--      若表 owner 是超级用户，超级用户本就绕过 RLS，FORCE 根本无从验证 —— A6 会变成假通过。
--      移交后 dy_app 同时是 owner + 非超级用户，A6 才有真实证明力。
--
-- 🛑 调用方式（\ir 的解析基准是【本文件所在目录】，不是 cwd）：
--     psql -d <db> -v ON_ERROR_STOP=1 -f verification/015_apply.sql                    # 相对兜底
--     psql -d <db> -v ON_ERROR_STOP=1 -v v1=<abs>/V1__baseline_tenant_rls.sql -f ...    # 显式传参
-- ============================================================================
\set ON_ERROR_STOP on

-- 1) 执行骨架真实迁移（路径指向交付物，非副本）
--    双通道：-v v1 优先；未传则 \ir 相对本文件解析（=> ../dy-app/...）。
\if :{?v1}
  \echo '015: 使用 -v v1 显式路径'
  \i :v1
\else
  \echo '015: 未传 -v v1，回落到 \\ir 相对路径（相对本文件所在目录）'
  \ir ../dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql
\endif

-- 2) dy_app 需要 schema 使用权与建表权（PG15+ 已从 PUBLIC 撤销 public schema 的 CREATE）
GRANT USAGE, CREATE ON SCHEMA public TO dy_app;

-- 3) 移交 owner（使 FORCE RLS 可被真实验证）
ALTER TABLE tenant           OWNER TO dy_app;
ALTER TABLE customer         OWNER TO dy_app;
ALTER TABLE audit_log        OWNER TO dy_app;
ALTER TABLE schema_migration OWNER TO dy_app;

\echo '015 OK: V1 migration applied FROM SKELETON; table owners -> dy_app (non-superuser)'