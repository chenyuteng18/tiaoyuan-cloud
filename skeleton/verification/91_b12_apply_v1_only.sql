-- ============================================================================
-- 91_b12_apply_v1_only.sql — 仅重复应用 V1（幂等证明 · V1 侧）
-- 以 postgres 连 diaoyuanyun_rls_test_b12 执行。
--
-- 🔴 2026-09-24 预期翻转（S1-2 验收④「迁移脚本连跑 2 次幂等、无报错」）:
--   旧版本脚本【预期会失败】，依据是"V1 L71 是裸 CREATE POLICY,
--   PG 的 CREATE POLICY 无 IF NOT EXISTS, 故第二次执行必然 42710 duplicate_object,
--   而 V1 是不可动的既有基线"。该依据已被 S1-2 验收④ 明令推翻。
--
--   现已修复: V1 在 CREATE POLICY 前补 `DROP POLICY IF EXISTS tenant_isolation ON customer;`
--   —— 策略定义【一字未改】，只是让重放安全。故本脚本现在【预期成功】。
--
--   期望翻转后，证明强度反而更高:
--     旧: 只证明"V2 幂等"，V1 被显式豁免；
--     新: 整条链（含 V1）都被"重复应用必须成功 + schema 指纹逐字节不变"覆盖。
--
--   反向验证未被删除: 若有人往 V1 塞回非幂等语句（或删掉 DROP POLICY 守卫），
--   99_b12_run.sh 的 04 步会以"重复应用失败"报红。
-- ============================================================================
\set ON_ERROR_STOP on

\if :{?v1}
\else
  \echo '错误: 必须传 -v v1=<V1迁移绝对路径>'
  \quit 2
\endif

\echo '-- 91: re-applying V1 only (EXPECTED TO SUCCEED: V1 now has DROP POLICY IF EXISTS) --'
\i :v1
\echo '91: V1 重复应用成功 —— 幂等成立（S1-2 验收④）'