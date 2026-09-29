-- ============================================================================
-- 02_seed.sql — 用非超级用户 dy_app 在两个"租户"下各插入一行
-- 目的: 证明①RLS 允许"设对了上下文"的写入（策略连通, 不是全表拒绝）;
--       ②tenant_id 与上下文一致时 WITH CHECK 放行。
-- ============================================================================
\set ON_ERROR_STOP on

-- 租户根表本身不参与 RLS（它是租户的宿主, 见 V1 脚本无策略）
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
INSERT INTO tenant (id, name) VALUES
  ('aaaaaaaa-1111-1111-1111-111111111111', '租户A'),
  ('bbbbbbbb-2222-2222-2222-222222222222', '租户B');
COMMIT;

-- 各租户插入自己的客户 (WITH CHECK 校验写入侧 tenant_id)
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
INSERT INTO customer (id, tenant_id, name, status)
VALUES ('c1111111-0000-0000-0000-000000000001', 'aaaaaaaa-1111-1111-1111-111111111111', 'A店客户-1', 'active');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
INSERT INTO customer (id, tenant_id, name, status)
VALUES ('c2222222-0000-0000-0000-000000000002', 'bbbbbbbb-2222-2222-2222-222222222222', 'B店客户-1', 'active');
COMMIT;

\echo '02_seed OK'