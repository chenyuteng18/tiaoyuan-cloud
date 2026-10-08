-- =============================================================================
-- V24 · settlement_statement —— 跨店通兑结算单（对账报表的持久层）
-- =============================================================================
-- 背景（商用开发第二批 · E1）：
--   结算域此前"只算不落"（POST /settlement/preview 是纯计算预演，PRD §2.9.6）。
--   商用阶段总部需要【对账报表】：拆分结论必须能按周期取回、能导出、能对账。
--   本迁移新增 settlement_statement —— 结算结论的持久载体。
--
-- 设计要点（每一条都是纪律，不是偏好）：
--   ① 租户宿主表：tenant_id NOT NULL + ENABLE/FORCE RLS + tenant_isolation 策略
--      （与 V5 的 store/staff 同款）。结算单是租户业务数据，M5 纪律"总部可见、
--      门店/加盟商不可见"在 SQL 层由 RLS 兜底，在 API 层由 @RequireOrgLevel 把守 —— 两层都在。
--   ② 幂等键 UNIQUE (tenant_id, request_hash)：request_hash 是结算输入的 SHA-256
--      规范哈希。同一租户同一份输入重复落库 = 同一张结算单（返回既有行），
--      绝不产出第二份"看起来不同的同一份账"。输入变一点，哈希就变 —— 新账。
--   ③ payload JSONB：SettlementResult 的不可变快照（allocations 逐店分摊）。
--      刻意冗余存储计算结果而不只存输入 —— 算法日后演进（阈值版本变化）时，
--      历史报表读的是【当时那次的结论】，不是"用今天的算法重算一遍"。
--      与 ADR-11（threshold_version 回放纪律）同源：结论可复算，但账面以落库时为准。
--   ④ 唯一生产写入方 = SettlementStatementService（ProvisioningBoundaryGateTest
--      已同步登记 44 张账）。预演端点 /settlement/preview 依旧只读不落库。
-- =============================================================================

-- §1 表
CREATE TABLE IF NOT EXISTS settlement_statement
(
    statement_id      UUID           PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id),
    -- 结算周期 'YYYY-MM'（格式守卫见 §3 CHECK）
    period            VARCHAR(7)     NOT NULL,
    -- 结案门店：单列 FK 在 V16 已被证明跨租户引用可达（RLS 只保护行归属，
    -- 从不保护引用归属）—— 必须用租户耦合复合 FK（V16 纪律），拒绝理由是 23503。
    closing_store_id  UUID           NOT NULL,
    stores_involved   INTEGER        NOT NULL,
    visits_total      INTEGER        NOT NULL,
    ecc_units         NUMERIC(12, 2) NOT NULL,
    loss_yuan         NUMERIC(12, 2) NOT NULL,
    split_applied     BOOLEAN        NOT NULL,
    -- 他店次数占比（判定依据回显，PRD §2.9.6：是判定依据不是看板指标）
    other_store_ratio NUMERIC(8, 4)  NOT NULL,
    -- SettlementResult 不可变快照（含逐店 allocations）
    payload           JSONB          NOT NULL,
    -- 结算输入规范哈希（幂等键的另一半）
    request_hash      VARCHAR(64)    NOT NULL,
    created_by        VARCHAR(128),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_settlement_period
        CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_settlement_stores
        CHECK (stores_involved > 0 AND visits_total > 0),
    -- 钱、贡献单位数不得为负（拆分输入侧已在计算层校验，此处是库层兜底）
    CONSTRAINT ck_settlement_amounts
        CHECK (ecc_units >= 0 AND loss_yuan >= 0),
    -- 幂等：同一租户同一份输入 = 同一张结算单
    CONSTRAINT uq_settlement_request
        UNIQUE (tenant_id, request_hash),
    -- 租户耦合复合外键（V16 纪律）：引用必须同租户，store 侧的
    -- UNIQUE (tenant_id, store_id) 由 V16 第 1 节统一建齐
    CONSTRAINT fk_settlement_closing_store
        FOREIGN KEY (tenant_id, closing_store_id) REFERENCES store (tenant_id, store_id)
);

CREATE INDEX IF NOT EXISTS idx_settlement_period
    ON settlement_statement (tenant_id, period);
CREATE INDEX IF NOT EXISTS idx_settlement_store
    ON settlement_statement (tenant_id, closing_store_id);

-- §2 RLS —— 与 V5 租户宿主表同款
ALTER TABLE settlement_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlement_statement FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON settlement_statement;
CREATE POLICY tenant_isolation ON settlement_statement
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §3 自证守卫：表必须以"RLS 已启用"的形态存在。
--   与 V23（auth_credential 豁免 RLS 的守卫）互为镜像：那张表断言"零策略"，
--   这张表断言"策略恰一条 + relrowsecurity 为真"。
--   有人误删策略 / 关闭 RLS ⇒ 迁移期即炸，而不是租户间串账之后才发现。
DO $v24_guard$
DECLARE
    policy_count integer;
    rls_enabled  boolean;
BEGIN
    SELECT count(*) INTO policy_count
      FROM pg_catalog.pg_policies
     WHERE schemaname = 'public' AND tablename = 'settlement_statement';
    SELECT c.relrowsecurity INTO rls_enabled
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'settlement_statement';

    IF policy_count <> 1 THEN
        RAISE EXCEPTION 'V24 守卫: settlement_statement 必须恰有一条 tenant_isolation 策略，当前 % 条', policy_count;
    END IF;
    IF rls_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'V24 守卫: settlement_statement 必须 ENABLE ROW LEVEL SECURITY（租户业务数据，见迁移头注①）';
    END IF;
END
$v24_guard$;

-- §4 schema_migration 登记（纪律：每迁移自带登记行；description ≤ 256，先量后写 —— 本行 187 字符）
INSERT INTO schema_migration (version, description)
VALUES ('V24', 'settlement_statement: cross-store period ledger. per-tenant RLS, idempotent by (tenant, request_hash), payload JSONB immutable snapshot; writer = SettlementStatementService only; guard asserts RLS on.')
ON CONFLICT (version) DO NOTHING;

DO $v24_guard2$
DECLARE
    v_n integer;
BEGIN
    SELECT count(*) INTO v_n FROM schema_migration WHERE version = 'V24';
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V24 自证失败: schema_migration 里 V24 登记行数 = %（期望恰 1）', v_n;
    END IF;
END
$v24_guard2$;
