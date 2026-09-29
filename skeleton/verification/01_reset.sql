-- ============================================================================
-- 01_reset.sql — 重置 RLS 验证环境
-- 以 postgres 超级用户身份连 postgres 库执行
--
-- 为何需要独立角色 dy_app：PostgreSQL 中【超级用户总是绕过 RLS】，
-- 用 postgres 连接做隔离测试会得到"假通过"。必须用非超级用户验证，
-- 这正是 ADR-02 §2.3.4 陷阱 3（生产用 superuser 连接 → 绕过 RLS）的落地检查。
-- ============================================================================
\set ON_ERROR_STOP on

DROP DATABASE IF EXISTS diaoyuanyun_rls_test WITH (FORCE);
DROP ROLE IF EXISTS dy_app;

CREATE ROLE dy_app LOGIN PASSWORD 'dy_app_local_2026' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE diaoyuanyun_rls_test;

\echo '01_reset OK: database recreated + non-superuser role dy_app created'