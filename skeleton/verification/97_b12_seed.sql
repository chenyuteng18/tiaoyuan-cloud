-- ============================================================================
-- 97_b12_seed.sql — 以非超级用户 dy_app_b12 灌入 B 类实体验证的种子数据
-- 以 dy_app_b12 连 diaoyuanyun_rls_test_b12 执行。
--
-- 目的：证明①RLS 允许"设对了上下文"的写入（策略连通，不是全表拒绝）；
--       ②两表在"租户与上下文一致"时 WITH CHECK 放行 —— 为后续隔离断言提供非空底表。
--
-- 幂等：全部走 ON CONFLICT DO NOTHING，可重复执行。
-- ============================================================================
\set ON_ERROR_STOP on

-- 租户根表本身不参与 RLS（V1 中它无策略），可无上下文直写
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
INSERT INTO tenant (id, name) VALUES
  ('aaaaaaaa-1111-1111-1111-111111111111', '租户A'),
  ('bbbbbbbb-2222-2222-2222-222222222222', '租户B')
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- 各租户在自己的上下文下插入自己的客户（V1 baseline 的既有种子口径）
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
INSERT INTO customer (id, tenant_id, name, status)
VALUES ('c1111111-0000-0000-0000-000000000001',
        'aaaaaaaa-1111-1111-1111-111111111111', 'A店客户-1', 'active')
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
INSERT INTO customer (id, tenant_id, name, status)
VALUES ('c2222222-0000-0000-0000-000000000002',
        'bbbbbbbb-2222-2222-2222-222222222222', 'B店客户-1', 'active')
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ---- 租户A: 状态机跃迁（建档首条 from_state = NULL）+ 客户级手环 ----
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';

INSERT INTO customer_state_transition
    (transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event, guard_result)
VALUES
  ('00000000-0000-0000-0000-0000000000a1',
   'aaaaaaaa-1111-1111-1111-111111111111',
   'c1111111-0000-0000-0000-000000000001',
   NULL, 'SCREENING', true, 'intake_created', 'passed')
ON CONFLICT (transition_id) DO NOTHING;

INSERT INTO band
    (band_id, tenant_id, customer_id, vendor, model, bound_at, status)
VALUES
  ('00000000-0000-0000-0000-0000000000b1',
   'aaaaaaaa-1111-1111-1111-111111111111',
   'c1111111-0000-0000-0000-000000000001',
   'GTL1', 'GTL1-Pro', DATE '2026-09-01', 'active')
ON CONFLICT (band_id) DO NOTHING;
COMMIT;

-- ---- 租户B: 各自一行，用于验证隔离（读不到对方） ----
BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';

INSERT INTO customer_state_transition
    (transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event, guard_result)
VALUES
  ('00000000-0000-0000-0000-0000000000a2',
   'bbbbbbbb-2222-2222-2222-222222222222',
   'c2222222-0000-0000-0000-000000000002',
   NULL, 'SCREENING', true, 'intake_created', 'passed')
ON CONFLICT (transition_id) DO NOTHING;

INSERT INTO band
    (band_id, tenant_id, customer_id, vendor, model, bound_at, status)
VALUES
  ('00000000-0000-0000-0000-0000000000b2',
   'bbbbbbbb-2222-2222-2222-222222222222',
   'c2222222-0000-0000-0000-000000000002',
   'GTL1', 'GTL1-Pro', DATE '2026-09-05', 'active')
ON CONFLICT (band_id) DO NOTHING;
COMMIT;

\echo '97_b12_seed OK'