-- ============================================================================
-- 03_assert.sql — RLS 租户隔离【真实数据库端到端断言】
--
-- 设计原则（这是与"断言脚本文本"的本质区别）：
--   每一条都用 DO 块做【真断言】：不满足即 RAISE EXCEPTION，
--   psql -v ON_ERROR_STOP=1 会使进程返回非 0 → CI 可直接当门禁。
--   断言的是【行为】(查询返回几行)，不是【文本】(脚本里有没有某字符串)。
--
-- 以非超级用户 dy_app 连接 diaoyuanyun_rls_test 执行。
-- ============================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- A0 前置：确认当前连接【不是】超级用户
--     超级用户会绕过 RLS，若误用超级用户跑本脚本，所有隔离断言都会"假通过"。
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION '前置失败: 当前用户 % 是超级用户, 会绕过 RLS (ADR-02 陷阱3)', current_user;
  END IF;
  RAISE NOTICE 'A0 OK: 当前用户 % 非超级用户', current_user;
END $$;

-- ---------------------------------------------------------------------------
-- A1 隔离核心：租户 A 上下文下，读不到租户 B 的行
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE n_total int; n_other int;
BEGIN
  SELECT count(*) INTO n_total FROM customer;
  SELECT count(*) INTO n_other FROM customer
   WHERE tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
  IF n_total <> 1 THEN
    RAISE EXCEPTION 'A1 失败: 租户A 应只见 1 行, 实际 % 行', n_total;
  END IF;
  IF n_other <> 0 THEN
    RAISE EXCEPTION 'A1 失败: 租户A 读到了租户B 的 % 行 —— 串租户!', n_other;
  END IF;
  RAISE NOTICE 'A1 OK: 租户A 只见 1 行, 看不到租户B';
END $$;
COMMIT;

-- ---------------------------------------------------------------------------
-- A2 对称性：租户 B 上下文下，读不到租户 A 的行
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
DO $$
DECLARE n_total int; n_other int;
BEGIN
  SELECT count(*) INTO n_total FROM customer;
  SELECT count(*) INTO n_other FROM customer
   WHERE tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
  IF n_total <> 1 OR n_other <> 0 THEN
    RAISE EXCEPTION 'A2 失败: 租户B 总行=% 越界行=%', n_total, n_other;
  END IF;
  RAISE NOTICE 'A2 OK: 租户B 只见 1 行 (对称)';
END $$;
COMMIT;

-- ---------------------------------------------------------------------------
-- A3 fail-closed：完全未设上下文 → 零行（不是全表！）
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM customer;
  IF n <> 0 THEN
    RAISE EXCEPTION 'A3 失败: 未设上下文时读到 % 行, 应为 0 行 (fail-closed 被破坏!)', n;
  END IF;
  RAISE NOTICE 'A3 OK: 未设上下文 -> 零行 (fail-closed 生效)';
END $$;

-- ---------------------------------------------------------------------------
-- A4 fail-closed：上下文被显式设为【空串】→ 零行（NULLIF 的作用点）
--     这正是 NULLIF 存在的理由：把空串形态也归一到"零行"，
--     而不是让它退化成 ''::uuid 的类型转换异常。
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = '';
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM customer;
  IF n <> 0 THEN
    RAISE EXCEPTION 'A4 失败: 空串上下文读到 % 行, 应为 0 行', n;
  END IF;
  RAISE NOTICE 'A4 OK: 空串上下文 -> 零行 (NULLIF 归一成功)';
END $$;
COMMIT;

-- ---------------------------------------------------------------------------
-- A5 写入侧 WITH CHECK：租户A 上下文下【不能】插入属于租户B 的行
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false;
BEGIN
  BEGIN
    INSERT INTO customer (id, tenant_id, name, status)
    VALUES ('c9999999-0000-0000-0000-000000000009',
            'bbbbbbbb-2222-2222-2222-222222222222', '越权写入', 'active');
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    blocked := true;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'A5 失败: 租户A 上下文成功插入了租户B 的行 —— WITH CHECK 未生效!';
  END IF;
  RAISE NOTICE 'A5 OK: 跨租户写入被 WITH CHECK 拒绝';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- A6 FORCE RLS：表 owner 也不能绕过策略
--     做法: 把表的 owner 设为 dy_app（即当前用户），若未 FORCE，
--     owner 将跳过全部策略 → A3 会读到 2 行。
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int; is_forced boolean;
BEGIN
  SELECT relforcerowsecurity INTO is_forced FROM pg_class WHERE relname = 'customer';
  IF NOT is_forced THEN
    RAISE EXCEPTION 'A6 失败: customer 未启用 FORCE ROW LEVEL SECURITY';
  END IF;
  SELECT count(*) INTO n FROM customer;
  IF n <> 0 THEN
    RAISE EXCEPTION 'A6 失败: FORCE 已开, owner 仍读到 % 行', n;
  END IF;
  RAISE NOTICE 'A6 OK: FORCE RLS 生效, owner 也受策略约束';
END $$;

-- ---------------------------------------------------------------------------
-- A7 策略覆盖面：USING 与 WITH CHECK 都必须存在且非默认
--     直接查 pg_policies 的元数据（比文本 grep 更权威）
-- ---------------------------------------------------------------------------
DO $$
DECLARE pol record;
BEGIN
  SELECT * INTO pol FROM pg_policies WHERE tablename = 'customer' AND policyname = 'tenant_isolation';
  IF pol IS NULL THEN
    RAISE EXCEPTION 'A7 失败: 未找到 tenant_isolation 策略';
  END IF;
  IF pol.qual IS NULL OR position('NULLIF' in pol.qual) = 0 THEN
    RAISE EXCEPTION 'A7 失败: USING 未含 NULLIF, 实际: %', pol.qual;
  END IF;
  IF pol.with_check IS NULL OR position('NULLIF' in pol.with_check) = 0 THEN
    RAISE EXCEPTION 'A7 失败: WITH CHECK 未显式声明 NULLIF, 实际: %', pol.with_check;
  END IF;
  RAISE NOTICE 'A7 OK: 策略元数据含 USING + WITH CHECK 双 NULLIF';
  RAISE NOTICE '     FOR ALL=%', pol.permissive;
END $$;

-- ---------------------------------------------------------------------------
-- A8 行数总账：以超级用户看不到真实全量（受 RLS 约束），
--     用 SET ROLE 到表 owner 也不可见 —— 这里只统计可见行，
--     确认"总可见 = 当前租户"这一不变量在多次切换后仍然成立。
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$ DECLARE a int; BEGIN SELECT count(*) INTO a FROM customer;
  IF a <> 1 THEN RAISE EXCEPTION 'A8 失败: 切回租户A 后可见 % 行', a; END IF;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
DO $$ DECLARE b int; BEGIN SELECT count(*) INTO b FROM customer;
  IF b <> 1 THEN RAISE EXCEPTION 'A8 失败: 切到租户B 后可见 % 行', b; END IF;
END $$;
COMMIT;

\echo '=========================================='
\echo ' ALL RLS ASSERTIONS PASSED (A0-A8)'
\echo '=========================================='