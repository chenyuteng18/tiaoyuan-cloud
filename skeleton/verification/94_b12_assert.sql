-- ============================================================================
-- 94_b12_assert.sql — B 类实体（V2）真库端到端断言
--
-- 设计原则（与"断言脚本文本"的本质区别）：
--   每条都用 DO 块做【真断言】：不满足即 RAISE EXCEPTION，
--   配合 psql -v ON_ERROR_STOP=1 → 进程非 0 退出 → 可直接当门禁。
--   断言的是【行为】(插入被拒的 SQLSTATE / 查询返回几行)，不是【文本】。
--
-- 以非超级用户 dy_app_b12 连 diaoyuanyun_rls_test_b12 执行。
-- 超级用户会绕过 RLS，若误用则所有隔离断言"假通过"（B0 前置拒跑）。
--
-- 覆盖任务书要求的五项：
--   ① 迁移幂等可重复执行          → 由 99_b12_run.sh 连跑两次采集（脚本层无法自证）
--   ② 14 态 CHECK 拒绝非法值      → B2 / B3（含反向验证：非法值必须抛 23514）
--   ③ customer.status 5 值未改动  → B6（断言 customer 表零 DDL 改动）
--   ④ 租户隔离零行（含 fail-closed）→ B4 / B5
--   ⑤ band 与 device 未合并       → B7
-- ============================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- B0 前置：确认当前连接【不是】超级用户
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION 'B0 前置失败: 当前用户 % 是超级用户, 会绕过 RLS', current_user;
  END IF;
  RAISE NOTICE 'B0 OK: 当前用户 % 非超级用户', current_user;
END $$;

-- ---------------------------------------------------------------------------
-- B1 两表确实存在，且都带 tenant_id
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text; n int;
BEGIN
  FOREACH t IN ARRAY ARRAY['customer_state_transition','band']
  LOOP
    IF to_regclass('public.' || t) IS NULL THEN
      RAISE EXCEPTION 'B1 失败: 表 % 不存在', t;
    END IF;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema='public' AND table_name=t AND column_name='tenant_id';
    IF n <> 1 THEN
      RAISE EXCEPTION 'B1 失败: 表 % 缺 tenant_id 列 (ADR-02 第1层)', t;
    END IF;
  END LOOP;
  RAISE NOTICE 'B1 OK: customer_state_transition / band 均存在且带 tenant_id';
END $$;

-- ---------------------------------------------------------------------------
-- B2 【反向验证】14 态 CHECK 必须拒绝非法值
--    做法: 在子块里故意插入非法 to_state, 捕获 check_violation(23514)。
--    若 CHECK 缺失/被放宽 → 插入成功 → 断言必须失败(这正是不许有的结果)。
--
--    🛑 必须【在租户上下文内】做这个探针。否则 RLS 的 WITH CHECK 会先以
--       42501(insufficient_privilege) 拒掉整行，CHECK 约束根本轮不到执行 ——
--       那样"被拒"是真的，但拒它的不是 14 态 CHECK，反向验证就没有证明力。
--       （这条坑是实测踩到的: 初版探针在无上下文下跑，报的是行级安全策略错误。）
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false; got_state text;
BEGIN
  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, trigger_event)
    VALUES ('00000000-0000-0000-0000-0000000000ff',
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'SCREENING', 'NOT_A_REAL_STATE', 'reverse_probe');
  EXCEPTION WHEN check_violation THEN
    blocked := true;
    GET STACKED DIAGNOSTICS got_state = RETURNED_SQLSTATE;
  END;

  IF NOT blocked THEN
    RAISE EXCEPTION 'B2 失败: 非法 to_state ''NOT_A_REAL_STATE'' 被接受了 —— 14 态 CHECK 未生效!';
  END IF;
  IF got_state <> '23514' THEN
    RAISE EXCEPTION 'B2 失败: 非法值被拒但 SQLSTATE = %, 期望 23514(check_violation)', got_state;
  END IF;
  RAISE NOTICE 'B2 OK: 非法 to_state 被 CHECK 拒绝 (SQLSTATE 23514)';
END $$;
ROLLBACK;

-- 非法 from_state 同样必须被拒（from_state 可空但非空时必须 ∈ 14 态）
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false; got_state text;
BEGIN
  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, trigger_event)
    VALUES ('00000000-0000-0000-0000-0000000000fe',
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'NOT_A_REAL_STATE', 'PROFILED', 'reverse_probe');
  EXCEPTION WHEN check_violation THEN
    blocked := true;
    GET STACKED DIAGNOSTICS got_state = RETURNED_SQLSTATE;
  WHEN others THEN
    RAISE EXCEPTION 'B2b 失败: 非法 from_state 被拒, 但异常不是 check_violation (SQLSTATE=%, 原文=%) —— 拒它的不是 14 态 CHECK', SQLSTATE, SQLERRM;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'B2b 失败: 非法 from_state 被接受了';
  END IF;
  IF got_state <> '23514' THEN
    RAISE EXCEPTION 'B2b 失败: SQLSTATE = %, 期望 23514', got_state;
  END IF;
  RAISE NOTICE 'B2b OK: 非法 from_state 被 CHECK 拒绝 (SQLSTATE 23514)';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B3 14 个合法态【逐项】被接受（证明 CHECK 的取值集恰好是 14 态，不是更窄）
--    每项插入后立刻删除，不留残留（整块回滚）。
--    ⚠️ 同为"约束探针", 同样必须在租户上下文内跑, 否则 42501 会先于 CHECK 触发。
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE
  st text;
  states text[] := ARRAY['SCREENING','REJECTED','PROFILED','CONSENTED','ASSESS_BASE',
                         'PLAN_APPROVED','AGREEMENT_SIGNED','CONFIRMED','IN_TREATMENT',
                         'CYCLE_ASSESS','PLAN_REVISING','REFUND_REVIEW','CLOSED','TERMINATED'];
  n int;
BEGIN
  IF array_length(states, 1) <> 14 THEN
    RAISE EXCEPTION 'B3 失败: 断言脚本自身列了 % 个态, 应为 14', array_length(states,1);
  END IF;

  FOREACH st IN ARRAY states
  LOOP
    BEGIN
      INSERT INTO customer_state_transition
        (transition_id, tenant_id, customer_id, from_state, to_state, trigger_event)
      VALUES (gen_random_uuid(),
              'aaaaaaaa-1111-1111-1111-111111111111',
              'c1111111-0000-0000-0000-000000000001',
              NULL, st, 'legal_value_probe');
    EXCEPTION
      WHEN check_violation THEN
        RAISE EXCEPTION 'B3 失败: 合法态 % 被 CHECK 拒绝 —— 取值集被收窄', st;
      WHEN others THEN
        RAISE EXCEPTION 'B3 失败: 合法态 % 插入抛出非 CHECK 异常 (SQLSTATE=%, 原文=%)', st, SQLSTATE, SQLERRM;
    END;
  END LOOP;

  SELECT count(*) INTO n FROM customer_state_transition WHERE trigger_event = 'legal_value_probe';
  IF n <> 14 THEN
    RAISE EXCEPTION 'B3 失败: 14 态逐项插入后应有 14 行探针数据, 实际 % 行', n;
  END IF;
  RAISE NOTICE 'B3 OK: 14 态逐项均被接受 (取值集 = 恰好 14 态, 探针行数=14)';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B4 租户隔离：A 看不到 B 的行（两表各验），对称方向亦零行
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE n_cst int; n_band int; n_cross int;
BEGIN
  SELECT count(*) INTO n_cst  FROM customer_state_transition;
  SELECT count(*) INTO n_band FROM band;
  -- 反证"表非空"：本租户必须看得见自己的行，否则下面的跨租户 0 行毫无意义（空表也 0 行）
  IF n_cst < 1 OR n_band < 1 THEN
    RAISE EXCEPTION 'B4 失败: 租户A 看不到自己的行 (cst=%, band=%) —— 策略变成了全表拒绝', n_cst, n_band;
  END IF;

  SELECT count(*) INTO n_cross FROM customer_state_transition
   WHERE tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
  IF n_cross <> 0 THEN
    RAISE EXCEPTION 'B4 失败: 租户A 读到租户B 的 customer_state_transition % 行 —— 串租户!', n_cross;
  END IF;

  SELECT count(*) INTO n_cross FROM band
   WHERE tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
  IF n_cross <> 0 THEN
    RAISE EXCEPTION 'B4 失败: 租户A 读到租户B 的 band % 行 —— 串租户!', n_cross;
  END IF;
  RAISE NOTICE 'B4 OK: 租户A 只见自己的行, 看不到租户B (cst=% band=%)', n_cst, n_band;
END $$;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = 'bbbbbbbb-2222-2222-2222-222222222222';
DO $$
DECLARE n_cross int;
BEGIN
  SELECT count(*) INTO n_cross FROM customer_state_transition
   WHERE tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
  SELECT count(*) INTO n_cross FROM band
   WHERE tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
  IF n_cross <> 0 THEN
    RAISE EXCEPTION 'B4b 失败: 租户B 读到租户A 的行 % 条 —— 对称性被破坏!', n_cross;
  END IF;
  RAISE NOTICE 'B4b OK: 租户B 看不到租户A (对称)';
END $$;
COMMIT;

-- ---------------------------------------------------------------------------
-- B5 fail-closed：未设上下文 / 空串上下文 → 两表都必须零行（不得回落全表）
-- ---------------------------------------------------------------------------
DO $$
DECLARE n1 int; n2 int;
BEGIN
  -- 形态①: 上下文未设（本连接此前只在 SET LOCAL 事务内设过，事务外已失效）
  SELECT count(*) INTO n1 FROM customer_state_transition;
  SELECT count(*) INTO n2 FROM band;
  IF n1 <> 0 OR n2 <> 0 THEN
    RAISE EXCEPTION 'B5 失败: 未设上下文时 cst=% band=% 行, 应为 0 行 —— fail-closed 被破坏!', n1, n2;
  END IF;
  RAISE NOTICE 'B5 OK: 未设上下文 -> 两表零行 (fail-closed 生效)';
END $$;

BEGIN;
SET LOCAL app.tenant_id = '';
DO $$
DECLARE n1 int; n2 int;
BEGIN
  -- 形态②: 显式空串 —— NULLIF 的归一作用点；缺 NULLIF 会抛 ''::uuid 转换异常(线上 500)
  SELECT count(*) INTO n1 FROM customer_state_transition;
  SELECT count(*) INTO n2 FROM band;
  IF n1 <> 0 OR n2 <> 0 THEN
    RAISE EXCEPTION 'B5b 失败: 空串上下文 cst=% band=% 行, 应为 0 行', n1, n2;
  END IF;
  RAISE NOTICE 'B5b OK: 空串上下文 -> 两表零行 (NULLIF 归一成功)';
EXCEPTION WHEN invalid_text_representation THEN
  RAISE EXCEPTION 'B5b 失败: 空串上下文抛了类型转换异常 (''::uuid) —— NULLIF 缺失, 线上表现为 500';
END $$;
COMMIT;

-- 写入侧 WITH CHECK：租户A 上下文下不能插入属于租户B 的行（两表各验）
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked_cst boolean := false; blocked_band boolean := false;
BEGIN
  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, trigger_event)
    VALUES ('00000000-0000-0000-0000-0000000000fd',
            'bbbbbbbb-2222-2222-2222-222222222222',
            'c2222222-0000-0000-0000-000000000002', NULL, 'SCREENING', 'x');
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN blocked_cst := true;
  END;

  BEGIN
    INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status)
    VALUES ('00000000-0000-0000-0000-0000000000fb',
            'bbbbbbbb-2222-2222-2222-222222222222',
            'c2222222-0000-0000-0000-000000000002', 'GTL1', DATE '2026-09-01', 'active');
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN blocked_band := true;
  END;

  IF NOT blocked_cst THEN
    RAISE EXCEPTION 'B5c 失败: 越权写入 customer_state_transition 成功 —— WITH CHECK 未生效!';
  END IF;
  IF NOT blocked_band THEN
    RAISE EXCEPTION 'B5c 失败: 越权写入 band 成功 —— WITH CHECK 未生效!';
  END IF;
  RAISE NOTICE 'B5c OK: 两表跨租户写入均被 WITH CHECK 拒绝';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B6 customer.status 的 5 值【未被改动】
--    V2 对 customer 表必须【零 DDL 改动】。可核验形式：
--      (a) customer 上不存在任何涉及 status 的 CHECK 约束（与 V1 基线一致）
--      (b) status 列定义与 V1 逐项一致（varchar(16) / NOT NULL / default 'pending'）
--      (c) customer 的列集合恰为 V1 的 8 列（V2 未加列）
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int; dt text; maxlen int; nullable text; def text; cols text;
BEGIN
  SELECT count(*) INTO n FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
   WHERE t.relname = 'customer' AND c.contype = 'c'
     AND pg_get_constraintdef(c.oid) ILIKE '%status%';
  IF n <> 0 THEN
    RAISE EXCEPTION 'B6 失败: customer 上出现了涉及 status 的 CHECK 约束 (% 条) —— V2 改动了 5 值口径', n;
  END IF;

  SELECT data_type, character_maximum_length, is_nullable, column_default
    INTO dt, maxlen, nullable, def
    FROM information_schema.columns
   WHERE table_schema='public' AND table_name='customer' AND column_name='status';
  IF dt <> 'character varying' OR maxlen <> 16 OR nullable <> 'NO' OR def NOT LIKE '%pending%' THEN
    RAISE EXCEPTION 'B6 失败: customer.status 列定义被改动 (type=% len=% null=% default=%)', dt, maxlen, nullable, def;
  END IF;

  SELECT string_agg(column_name, ',' ORDER BY column_name) INTO cols
    FROM information_schema.columns
   WHERE table_schema='public' AND table_name='customer';
  IF cols <> 'created_at,deleted_at,id,name,owner_id,status,tenant_id,updated_at' THEN
    RAISE EXCEPTION 'B6 失败: customer 列集合被改动, 实际 = %', cols;
  END IF;

  RAISE NOTICE 'B6 OK: customer 表零 DDL 改动 (无 status CHECK / 列定义同 V1 / 列集合 = 8 列)';
END $$;

-- ---------------------------------------------------------------------------
-- B7 band 与 device【未合并】
--     判据: ① band 与 device 是两张独立表, 不是同表异名
--           ② band 不带 store_id（客户级、不做门店锁定；device 才是门店级）
--           ③ band 的主键不是 device.device_id 的复用
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int; band_pk text; has_store int;
BEGIN
  -- ③ band 主键必须是 band_id
  SELECT string_agg(a.attname, ',' ORDER BY a.attname) INTO band_pk
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN unnest(c.conkey) k ON true
    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k
   WHERE t.relname = 'band' AND c.contype = 'p';
  IF band_pk <> 'band_id' THEN
    RAISE EXCEPTION 'B7 失败: band 主键 = %, 应为 band_id (不得复用 device.device_id)', band_pk;
  END IF;

  -- ② band 不得带 store_id（客户级属性, 随客户跨店移动, 不做门店锁定）
  SELECT count(*) INTO has_store FROM information_schema.columns
   WHERE table_schema='public' AND table_name='band' AND column_name='store_id';
  IF has_store <> 0 THEN
    RAISE EXCEPTION 'B7 失败: band 出现了 store_id —— 把客户级手环错误地做成了门店级台账';
  END IF;

  -- ① 若本仓库存在 device 表, 则它必须与 band 是两张不同的表（本仓库当前不存在）
  IF to_regclass('public.device') IS NOT NULL THEN
    RAISE NOTICE 'B7 提示: 本库存在 device 表, 已确认其为独立表 (band.pid=% / band 无 store_id)', band_pk;
  ELSE
    RAISE NOTICE 'B7 提示: 本仓库 DDL 内不存在 device 表 (data-dict §2.5 的 device 未入骨架), 无法做双表并存比对';
  END IF;

  -- band 的 customer_id 必须存在（客户级台账的判据）
  SELECT count(*) INTO n FROM information_schema.columns
   WHERE table_schema='public' AND table_name='band' AND column_name='customer_id';
  IF n <> 1 THEN
    RAISE EXCEPTION 'B7 失败: band 缺 customer_id —— 无法证明它是客户级台账';
  END IF;

  RAISE NOTICE 'B7 OK: band 主键=band_id / 无 store_id / 带 customer_id —— 未与 device 合并';
END $$;

-- ---------------------------------------------------------------------------
-- B8 RLS 元数据：两表均 ENABLE + FORCE + 有策略, 且 USING/WITH CHECK 双 NULLIF
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  t text;
  enabled boolean; forced boolean; owner text; pol record;
BEGIN
  FOREACH t IN ARRAY ARRAY['customer_state_transition','band']
  LOOP
    SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner)
      INTO enabled, forced, owner
      FROM pg_class WHERE relname = t AND relkind = 'r';

    IF NOT enabled THEN RAISE EXCEPTION 'B8 失败: % 未 ENABLE ROW LEVEL SECURITY', t; END IF;
    IF NOT forced  THEN RAISE EXCEPTION 'B8 失败: % 未 FORCE ROW LEVEL SECURITY (owner 可绕过)', t; END IF;
    IF owner <> current_user THEN
      RAISE EXCEPTION 'B8 失败: % 的 owner=%, 应为非超级用户 % (否则 FORCE 无从证明)', t, owner, current_user;
    END IF;

    SELECT * INTO pol FROM pg_policies WHERE tablename = t AND policyname = 'tenant_isolation';
    IF pol IS NULL THEN RAISE EXCEPTION 'B8 失败: % 缺 tenant_isolation 策略', t; END IF;
    IF pol.qual IS NULL OR position('NULLIF' in pol.qual) = 0 THEN
      RAISE EXCEPTION 'B8 失败: % 的 USING 未含 NULLIF, 实际: %', t, pol.qual;
    END IF;
    IF pol.with_check IS NULL OR position('NULLIF' in pol.with_check) = 0 THEN
      RAISE EXCEPTION 'B8 失败: % 的 WITH CHECK 未显式含 NULLIF, 实际: %', t, pol.with_check;
    END IF;
  END LOOP;
  RAISE NOTICE 'B8 OK: 两表 ENABLE+FORCE 在位, owner=非超级用户, USING/WITH CHECK 双 NULLIF';
END $$;

-- ---------------------------------------------------------------------------
-- B9 §2.26 部分唯一索引: 1 客户 : 1【有效】手环
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false;
BEGIN
  BEGIN
    -- 同一租户 + 同一客户，再插一条 status='active' → 必须撞唯一索引
    INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status)
    VALUES (gen_random_uuid(),
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'GTL1', DATE '2026-09-10', 'active');
  EXCEPTION WHEN unique_violation THEN blocked := true;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'B9 失败: 同一客户被允许存在 2 条 active 手环 —— uq_band_active_customer 未生效';
  END IF;
  RAISE NOTICE 'B9 OK: 1 客户 : 1 active 手环 由部分唯一索引强制';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B10 §2.25 部分唯一索引: 每客户最多 1 条 is_current = true
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false;
BEGIN
  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event)
    VALUES (gen_random_uuid(),
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'SCREENING', 'PROFILED', true, 'second_current_probe');
  EXCEPTION WHEN unique_violation THEN blocked := true;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'B10 失败: 同一客户被允许存在 2 条 is_current=true —— uq_cst_current 未生效';
  END IF;
  RAISE NOTICE 'B10 OK: 每客户最多 1 条当前态 由部分唯一索引强制';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B11 §2.25 guard_result=blocked 时 missing_items 必填（X-14 / 403 回显）
-- ---------------------------------------------------------------------------
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-1111-1111-1111-111111111111';
DO $$
DECLARE blocked boolean := false; ok boolean := false;
BEGIN
  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event,
       guard_result, missing_items)
    VALUES (gen_random_uuid(),
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'SCREENING', 'SCREENING', false, 'guard_probe', 'blocked', NULL);
  EXCEPTION WHEN check_violation THEN blocked := true;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'B11 失败: guard_result=blocked 且 missing_items IS NULL 被接受 —— 403 缺失项回显落库位被架空';
  END IF;

  BEGIN
    INSERT INTO customer_state_transition
      (transition_id, tenant_id, customer_id, from_state, to_state, is_current, trigger_event,
       guard_result, missing_items)
    VALUES (gen_random_uuid(),
            'aaaaaaaa-1111-1111-1111-111111111111',
            'c1111111-0000-0000-0000-000000000001',
            'SCREENING', 'SCREENING', false, 'guard_probe', 'blocked', '["PROFILED"]'::jsonb);
    ok := true;
  EXCEPTION WHEN others THEN
    RAISE EXCEPTION 'B11 失败: blocked + missing_items 齐备时被拒 —— 约束过严: %', SQLERRM;
  END;
  IF NOT ok THEN RAISE EXCEPTION 'B11 失败: blocked + missing_items 齐备的写入未成功'; END IF;
  RAISE NOTICE 'B11 OK: blocked 必须带 missing_items; 齐备时放行';
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- B12 band_telemetry 三表 FK 闭合【状态报告】（不是断言 —— 本任务不得建该表）
--     §2.17【待评审条目 F-2】明令宽/长表建模未定案, 拍板人 = 技术负责人。
--     故此处只【报告】目标表是否存在、FK 是否已闭合、是否按纪律跳过。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
    exists_t boolean;
    has_fk boolean;
    present int := 0; missing int := 0; closed int := 0;
BEGIN
    FOREACH t IN ARRAY ARRAY['band_telemetry','band_sync_probe','band_daily_coverage']
    LOOP
        exists_t := to_regclass('public.' || t) IS NOT NULL;
        IF exists_t THEN
            present := present + 1;
            SELECT count(*) INTO has_fk FROM pg_constraint
             WHERE conname = 'fk_' || t || '_device_id_band';
            IF has_fk = 1 THEN
                closed := closed + 1;
                RAISE NOTICE 'B12: % 存在于本库, FK device_id -> band(band_id) 已闭合', t;
            ELSE
                RAISE EXCEPTION 'B12 失败: % 存在但 FK 未闭合 —— 悬空外键未按预期处理', t;
            END IF;
        ELSE
            missing := missing + 1;
            RAISE NOTICE 'B12: % 在本仓库 DDL 中【不存在】→ FK 闭合按纪律【跳过】(不新建表, 待 F-2 定案)', t;
        END IF;
    END LOOP;
    RAISE NOTICE 'B12 结论: 存在=% 已闭合FK=% 跳过=% (总 3 表)', present, closed, missing;
END $$;

\echo '=========================================='
\echo ' ALL B-ENTITY ASSERTIONS PASSED (B0-B12)'
\echo '=========================================='