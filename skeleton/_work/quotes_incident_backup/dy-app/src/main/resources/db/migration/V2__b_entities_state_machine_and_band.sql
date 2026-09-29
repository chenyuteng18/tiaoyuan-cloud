-- ============================================================================
-- V2 迁移: B 类实体落库 —— 14 态状态机实体 + 客户级手环实体
-- 适用: PostgreSQL (RLS / UUID 为 PG 专属语法, 同 V1 口径)
--
-- 字段级定义的【唯一权威来源】= _work/data-dict-entities-ddl-2026-09-19.md
--   §2.25 customer_state_transition (14 态状态机实体 · BD-3 · F-4 落盘)
--   §2.26 band                     (客户级手环实体   · BD-3 · F-3 落盘)
--   §附 DDL 示意 (band / customer_state_transition 两段; 该件自述"字段 / 类型 / 约束为准,
--                 不必逐字可执行")
--
-- 阻塞解除依据 (本文件动工的前提):
--   · F-4 阻塞源 ADR 十五 F-4 处置 = 「预留位 + 注释指向待建状态机实体」→ 已定案
--   · F-3 阻塞源 ADR 十五 F-3 处置 = 「补客户级手环实体 或 改业务键」二选一
--     → 团队按 blocker-root-cause §四 BD-3 行的 R-4 口径【选"补实体"】一支
--
-- ============================ 边界纪律 (违反即返工) ============================
-- ① customer.status 保持 5 值不动 —— 本文件【不】给 customer 加列、【不】改其取值集。
--    14 态与 5 值的显式映射写在 §2.25 ②, 由状态机实体(本文件)承载; 5 值仅为派生聚合。
--    → 本文件对 customer 表【零 DDL 改动】, 仅在 customer_state_transition 的注释里留映射。
-- ② band 与 device 是两本台账, 不得合并 —— device = 门店级调理设备(下行/参数下发/可追责);
--    band = 客户级穿戴手环(上行/1 客户 : 1 手环/缺失不得作不利依据)。
--    → 本文件建的 band 【不含 store_id】, 【不】复用 device.device_id 表示客户手环。
-- ③ 🛑 band_telemetry 的宽表/长表建模「未定案」(§2.17【待评审条目 F-2】, 拍板人 = 技术负责人)。
--    → 本文件【不建】band_telemetry, 【不】对其表结构做任何选择。
--      FK 闭合动作另见本文件末尾第 4 节的"条件式 FK"(仅在目标表已存在时才追加)。
-- ④ 多租户隔离: 两表均带 tenant_id, 行级 scope = tenant_id,
--    RLS 策略写法【逐字照抄 V1__baseline_tenant_rls.sql】的 fail-closed 风格
--    (ENABLE + FORCE + USING/WITH CHECK 双 NULLIF), 不另创一套。
-- ⑤ 审计字段全表必带: tenant_id / created_at timestamptz NOT NULL DEFAULT now() /
--    updated_at timestamptz / created_by。
-- ⑥ 时间一律 timestamptz; 枚举用 text + CHECK, 取值逐项落库(不得只存中文标签)。
--
-- ======================= 与 §附 DDL 示意的两处【显式适配】 =======================
-- 适配-1 (物理类型 string → UUID): §附 DDL 把 *_id / tenant_id 写成 text, 但本仓库
--   真实基线 V1 的 tenant(id) / customer(id) 是 UUID, 且 RLS 策略必须
--   `NULLIF(current_setting('app.tenant_id', true), '')::uuid` —— 租户列若为 text
--   则该转换无法成立。§二 通用约定亦将 "string" 定义为【逻辑类型】(业务字符串 ID)。
--   故本文件把 ID / 租户列物理落为 UUID, 与 V1 基线保持同族, 避免同一 schema 内
--   text/uuid 混用导致后续 join 与 FK 反复类型漂移。字段集合【未增未删】。
-- 适配-2 (FK 目标列名): §附 DDL 写 `REFERENCES customer(customer_id)`, 但 V1 基线的
--   customer 主键列名是 `id`(非 `customer_id`)。照抄会导致迁移直接失败(42703),
--   故 FK 目标写 customer(id)。表/列集合【未增未删】。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) customer_state_transition —— 14 态状态机实体 (append-only, 承载状态历史)
--    §2.25: 一条记录 = 一次状态跃迁。当前态 = 该客户最新一条的 to_state
--           (is_current = true 唯一)。
--    🛑 customer.status 保持 5 值不动; 14 态是唯一权威、5 值是派生聚合。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_state_transition
(
    transition_id  UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    customer_id    UUID         NOT NULL REFERENCES customer (id),

    -- 14 态全值 (逐项落库, 直接取自 §2.25 ① / PRD §7.2):
    --   SCREENING / REJECTED / PROFILED / CONSENTED / ASSESS_BASE / PLAN_APPROVED /
    --   AGREEMENT_SIGNED / CONFIRMED / IN_TREATMENT / CYCLE_ASSESS / PLAN_REVISING /
    --   REFUND_REVIEW / CLOSED / TERMINATED   (其中 REJECTED / CLOSED / TERMINATED 为终态)
    -- from_state 可空: 建档首条为 NULL; 否则 ∈ 14 态
    from_state     VARCHAR(32)  CHECK (from_state IS NULL OR from_state IN (
                       'SCREENING', 'REJECTED', 'PROFILED', 'CONSENTED', 'ASSESS_BASE',
                       'PLAN_APPROVED', 'AGREEMENT_SIGNED', 'CONFIRMED', 'IN_TREATMENT',
                       'CYCLE_ASSESS', 'PLAN_REVISING', 'REFUND_REVIEW', 'CLOSED', 'TERMINATED')),
    to_state       VARCHAR(32)  NOT NULL CHECK (to_state IN (
                       'SCREENING', 'REJECTED', 'PROFILED', 'CONSENTED', 'ASSESS_BASE',
                       'PLAN_APPROVED', 'AGREEMENT_SIGNED', 'CONFIRMED', 'IN_TREATMENT',
                       'CYCLE_ASSESS', 'PLAN_REVISING', 'REFUND_REVIEW', 'CLOSED', 'TERMINATED')),

    -- 当前态标记: 每客户最多 1 条 (见下方 uq_cst_current 部分唯一索引)。
    -- 本表 append-only; 唯一可更新字段 = is_current (旧态置 false、新态置 true, 同事务)。
    is_current     BOOLEAN      NOT NULL DEFAULT false,

    -- ⚠️ trigger_event: §2.25 字段表写 "CHECK | 见下表 ③", 但 §2.25 的表 ③ 是
    --    「跃迁守卫 G1/G2」而非 trigger_event 取值表 —— 该 CHECK 的引用是【悬空的】,
    --    权威件未逐项枚举 trigger_event 取值。按纪律【不自行发明枚举】,
    --    故此处只落 NOT NULL, 暂不加 CHECK; 待技术负责人/业务补齐取值集后走增项流程。
    trigger_event  VARCHAR(64)  NOT NULL,

    -- passed / blocked (§2.25 ③: blocked 记 403 缺失项, 【不写 to_state 变更】;
    -- 但本表 append-only, blocked 的跃迁尝试"须留痕"→ 写一行、不改 is_current)
    guard_result   VARCHAR(16)  NOT NULL DEFAULT 'passed'
                                CHECK (guard_result IN ('passed', 'blocked')),
    -- 403 携带的缺失项名数组 (X-14); guard_result = blocked 时必填 (§2.25 字段表明文)
    missing_items  JSONB,

    -- FK staff; 系统触发为 NULL
    operator_id    UUID,
    -- 跃迁时点 (回答"何时进入某态")
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 触发该跃迁的实体 (如 consent / agreement / plan / refund)
    ref_entity     VARCHAR(64),
    ref_id         VARCHAR(128),

    -- 审计字段 (全表必带, 见文件头纪律 ⑤)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(128),

    -- §2.25 字段表明文: guard_result = blocked 时 missing_items 必填
    CONSTRAINT ck_cst_blocked_requires_missing_items
        CHECK (guard_result <> 'blocked' OR missing_items IS NOT NULL)
);

-- 每客户最多 1 条当前态 (§2.25 字段表: UNIQUE(customer_id) WHERE is_current = true)
CREATE UNIQUE INDEX IF NOT EXISTS uq_cst_current
    ON customer_state_transition (customer_id) WHERE is_current = true;

CREATE INDEX IF NOT EXISTS idx_cst_tenant
    ON customer_state_transition (tenant_id);
CREATE INDEX IF NOT EXISTS idx_cst_customer_time
    ON customer_state_transition (tenant_id, customer_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_cst_from_state
    ON customer_state_transition (tenant_id, from_state);
CREATE INDEX IF NOT EXISTS idx_cst_to_state
    ON customer_state_transition (tenant_id, to_state);
CREATE INDEX IF NOT EXISTS idx_cst_occurred_at
    ON customer_state_transition (tenant_id, occurred_at);

-- RLS (写法照抄 V1 的 fail-closed 风格; 未设上下文 = 零行, 唯一允许语义)
ALTER TABLE customer_state_transition ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_state_transition FORCE ROW LEVEL SECURITY;

-- DROP POLICY IF EXISTS 是【幂等必需】: CREATE POLICY 在 PG 中没有 IF NOT EXISTS,
-- 不先 DROP 则第二次执行必然 42710 duplicate_object → 违反"迁移可幂等重复执行"。
DROP POLICY IF EXISTS tenant_isolation ON customer_state_transition;
CREATE POLICY tenant_isolation ON customer_state_transition
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 2) band —— 客户级手环实体 (1 客户 : 1 有效手环 · 上行)
--    §2.26: 闭合 band_telemetry / band_sync_probe / band_daily_coverage
--           三表 device_id 的悬空 FK (FK 目标 = 本表 band_id)。
--    🛑 与 device (门店级调理设备 · 下行) 两本台账、不得合并。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS band
(
    -- = band_telemetry.device_id 的引用目标 (客户级手环唯一 ID)
    band_id            UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id),
    -- 客户级属性: 1 客户 : 1 有效手环; 部分唯一索引进在下方
    customer_id        UUID        NOT NULL REFERENCES customer (id),

    -- GTL1 / 其他 (待厂商确认) —— §附 DDL 对该列【无 CHECK】, 且取值集自述"待厂商确认",
    -- 故不在此冻结枚举(冻结一个明确未定的集合属代拍口径)
    vendor             VARCHAR(64) NOT NULL,
    -- 型号 (客户级属性, 非门店级)
    model              VARCHAR(64),

    -- 绑定日 ("应戴天"分母起点, §4.3 分母护栏: A3 分母 = 台账应戴天)
    bound_at           DATE        NOT NULL,
    -- 解绑日 (分母终点); 未解绑为 NULL
    unbound_at         DATE,
    -- 区分"主动放弃"与"技术性缺失" (data-spec M6)
    unbind_reason      VARCHAR(32) CHECK (unbind_reason IN ('主动放弃', '换机', '设备损坏', '其他')),

    status             VARCHAR(16) NOT NULL DEFAULT 'active'
                                   CHECK (status IN ('active', 'paused', 'unbound', 'retired')),
    -- 合规摘除期 (住院 / 洗浴 / 桑拿, G8): 数组 [{from, to, reason}];
    -- 暂停期从 A3 分母剔除
    pause_period_json  JSONB,

    -- 审计字段 (全表必带, 见文件头纪律 ⑤)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         VARCHAR(128)
);

-- 1 客户 : 1 有效手环 (§2.26 字段表: UNIQUE(tenant_id, customer_id) WHERE status='active')
CREATE UNIQUE INDEX IF NOT EXISTS uq_band_active_customer
    ON band (tenant_id, customer_id) WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_band_customer
    ON band (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_band_status
    ON band (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_band_bound_at
    ON band (tenant_id, bound_at);
CREATE INDEX IF NOT EXISTS idx_band_unbound_at
    ON band (tenant_id, unbound_at);

-- RLS (同 V1 fail-closed 风格; band 属客户级属性、随客户跨店移动、不做门店锁定)
ALTER TABLE band ENABLE ROW LEVEL SECURITY;
ALTER TABLE band FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON band;
CREATE POLICY tenant_isolation ON band
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 3) 迁移版本登记 (幂等 upsert, 沿用 V1 尾部示例的数据级写法)
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V2', 'B entities: customer_state_transition (14-state) + band (customer-level wearable)')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4) 条件式 FK 闭合: band_telemetry / band_sync_probe / band_daily_coverage
--    的 device_id → band(band_id)
--
--    🛑 本仓库现有 DDL 中【不存在】这三张表 (§2.17【待评审条目 F-2】明令:
--       band_telemetry 宽/长表建模未定案, 拍板人 = 技术负责人)。
--    为闭合 FK 而【新建】这三张表 = 代技术负责人拍板, 【禁止】。
--    故此处不建表, 只做"若目标表已被他处在【同一条迁移链的更早阶段】建出,
--    则把 FK 补上"的条件式动作 —— 未命中即跳过(no-op), 不会报错。
--
--    跳过是可核验的: 本块执行后, 若三表的 FK 目标仍为空,
--    说明它们尚未落库, 属"待 F-2 定案后由该表的建表迁移自带 FK"。
--    见 verification/94_b12_assert.sql 的 B-FK 断言(会明确报告"跳过"而非静默)。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
    missing text[] := ARRAY[]::text[];
BEGIN
    FOREACH t IN ARRAY ARRAY['band_telemetry', 'band_sync_probe', 'band_daily_coverage']
    LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            missing := missing || t;
            CONTINUE;
        END IF;

        -- 幂等: 约束已存在则跳过 (pg_constraint 里按名查, 避免 ADD CONSTRAINT 重复报错)
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conname = 'fk_' || t || '_device_id_band'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (device_id) REFERENCES band (band_id)',
                t, 'fk_' || t || '_device_id_band');
            RAISE NOTICE 'V2: 已为 % 追加 FK device_id -> band(band_id)', t;
        ELSE
            RAISE NOTICE 'V2: % 的 FK 已存在, 跳过 (幂等)', t;
        END IF;
    END LOOP;

    IF array_length(missing, 1) > 0 THEN
        RAISE NOTICE 'V2: 以下表在本仓库 DDL 中不存在, FK 闭合【跳过】(不新建表, 待 F-2 定案): %',
                     array_to_string(missing, ', ');
    END IF;
END $$;

-- ============================================================================
-- V2 完成标记
-- ============================================================================