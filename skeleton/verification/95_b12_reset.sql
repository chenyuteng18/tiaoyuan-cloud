-- ============================================================================
-- 95_b12_reset.sql — 重置 B 类实体（V2）真库验证环境
-- 以 postgres 超级用户身份连 postgres 库执行。
--
-- 🛑 为何用独立库名/角色名（_b12 后缀），而不是 verification/01_reset.sql 的
--    diaoyuanyun_rls_test / dy_app：
--    本机的 diaoyuanyun_rls_test 此刻【已被 dy-config 的落地件占用】
--    （实测其 public schema 内存在 config_slot / app_config / app_config_history）。
--    本脚本的动作是 DROP DATABASE ... WITH (FORCE) —— 属破坏性操作，
--    会强杀另一个 worker 正在使用的连接并删掉它的表。
--    这与 RlsGateSupport 类注释里记载的同名事故完全同型
--    （症状表现为 42P01: 关系 "xxx" 不存在，极易误判成业务 DDL 有 bug）。
--    故按该文件已确立的"模块隔离"约定，本任务使用 diaoyuanyun_rls_test_b12。
-- ============================================================================
\set ON_ERROR_STOP on

DROP DATABASE IF EXISTS diaoyuanyun_rls_test_b12 WITH (FORCE);
DROP ROLE IF EXISTS dy_app_b12;

CREATE ROLE dy_app_b12 LOGIN PASSWORD 'dy_app_b12_local_2026' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE diaoyuanyun_rls_test_b12;

\echo '95_b12_reset OK: database diaoyuanyun_rls_test_b12 recreated + non-superuser role dy_app_b12'