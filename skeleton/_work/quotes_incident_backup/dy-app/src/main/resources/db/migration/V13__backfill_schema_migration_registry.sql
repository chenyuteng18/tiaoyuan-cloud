-- ===========================================================================
-- V13 迁移: 补登记 schema_migration 历史缺口（V1 / V6 / V7 / V8 / V9）
-- ===========================================================================
--
-- 【为什么需要这个迁移 —— 一处既有的真实欠债，不是本次新引入的】
--   项目自有的 schema_migration 表（V1 建的）本应记录"本仓每个迁移都做了什么"。
--   实测真库 diaoyuanyun_dev 里它只有 6 行：V2 / V3 / V4 / V5 / V10 / V11。
--   缺失 5 行：V1（baseline 本身）、V6 / V7 / V8 / V9。
--
--   成因（已核）：V6~V9 四个迁移文件里【根本没有】INSERT INTO schema_migration 语句
--   （grep -c "INSERT INTO schema_migration" 在四个文件上均为 0）；
--   V1 的那条登记是【注释掉的示例】（V1 执行时尚无该表）。
--
--   影响评估（如实说明，不过度渲染也不轻描淡写）：
--   · Flyway 自己的 flyway_schema_history 是【准确】的（11 行，V1~V11 全在）
--     ⇒ 迁移执行本身没有任何问题，"哪些迁移跑过"这件事 Flyway 知道；
--   · 受损的是【项目自有的登记表】：任何"按 schema_migration 对账已应用迁移"的
--     人工审计或运维脚本会得出错误答案（以为 V6~V9 没跑过）；
--   · 两张表分叉后，没人能一眼判断该信哪个 —— 这是登记表存在的意义被削掉的典型。
--
-- 【为什么用新迁移补，而不改 V6~V9 的文件】
--   Flyway 是 forward-only 且【已应用迁移的校验和不可变】：
--   往 V6~V9 里加一行会让 Flyway 在启动时以
--   "Migration checksum mismatch for migration version 6" 直接拒绝启动。
--   故修正只能落在新迁移。这也是本仓既有的做法（V8/V9 均为对前序的对齐迁移）。
--
-- 【为什么连 V1 一起补】
--   V1 是 baseline（application.yml: baseline-on-migrate: true）。把 V1 登记进来，
--   使 schema_migration 覆盖【全部】版本，从而"登记表 = Flyway 表"成为一条
--   可断言的事实。只补 V6~V9 会让门禁不得不为 V1 保留一条永久豁免 ——
--   而豁免是"静默失效"的温床（见 RlsCoverageGateTest 类头对豁免清单的告警）。
--
-- 【🛑 本迁移【只】写登记表，不碰任何 schema】
--   它不建表、不改列、不动约束、不写业务数据。因此：
--   · 不需要自证 schema 形状（没有形状变更）；
--   · 但仍需自证【登记结果】—— 见第 2 节（否则"补登记"本身会静默不生效）。
--
-- 【回滚说明】见文件末。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) 补登记 5 个历史版本（幂等：ON CONFLICT DO NOTHING）
--
--    🛑 description ≤ 256 字符（schema_migration.description = VARCHAR(256)，V1 建列）。
--      超长会让整条语句以 22001 失败并连带回滚整个迁移 —— V12 首版正是踩了这个坑，
--      现已由 MigrationRegistryGateTest 在构建期拦住。
--    ⚠️ 描述文字取自各迁移的文件头主旨，不改写其含义（这里是【补记】不是【重述】）。
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description) VALUES
    ('V1', 'baseline tenant RLS: tenant/customer tables + ENABLE/FORCE ROW LEVEL SECURITY + fail-closed tenant_isolation policy + schema_migration table'),
    ('V6', 'refund domain alignment + refund ledgers (statement / receipt / offline notice)'),
    ('V7', 'customer domain alignment: intake_profile_revision and related customer-domain tables'),
    ('V8', 'verdict two-phase alignment (append-only verdict persistence boundary)'),
    ('V9', 'band_sync_log contract enum alignment: contract_state / contract_trigger / batch_no / last_success_date added alongside legacy columns')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2) 自证块：登记结果必须真的落库（否则"补登记"会静默不生效）
--    与 V10/V11/V12 的自证块同口径：不满足即 RAISE，整个迁移回滚。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    missing TEXT;
    total   INT;
    longest INT;
BEGIN
    -- (a) V1 / V6~V9 五行必须都在（逐项列出缺口，而不是只说"少了几个"）
    SELECT string_agg(v, ', ' ORDER BY v) INTO missing
    FROM (VALUES ('V1'), ('V6'), ('V7'), ('V8'), ('V9')) AS t(v)
    WHERE NOT EXISTS (SELECT 1 FROM schema_migration m WHERE m.version = t.v);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '自证失败(a): 补登记后仍缺版本 % —— 本迁移的核心产出未生效', missing;
    END IF;

    -- (b) 登记行数下界：V1~V11 共 11 个版本（V12/V13 分别由各自迁移登记）。
    --     断言下界而非等值，是为了让本迁移对"将来新增版本"保持无感 ——
    --     否则每加一个迁移都要回来改 V13，那是把门禁变成负担。
    SELECT count(*) INTO total FROM schema_migration;
    IF total < 11 THEN
        RAISE EXCEPTION '自证失败(b): schema_migration 仅 % 行，应至少 11 行（V1~V11）', total;
    END IF;

    -- (c) 描述长度全部合规（与 MigrationRegistryGateTest 的构建期断言互为双保险：
    --     构建期拦"写太长"，运行时拦"已存在的行本来就太长"）
    SELECT max(length(description)) INTO longest FROM schema_migration;
    IF longest > 256 THEN
        RAISE EXCEPTION '自证失败(c): schema_migration 存在长度 % 的 description，'
            '超过列宽 256 —— 说明有版本在写入时被截断或列宽已被改动', longest;
    END IF;

    RAISE NOTICE 'V13 自证通过: schema_migration 现覆盖 V1~V11（补 V1/V6/V7/V8/V9）；总行数 %；最长描述 % 字符',
        total, longest;
END $$;

-- ---------------------------------------------------------------------------
-- 3) 迁移版本登记（本迁移自己）
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V13', 'backfill schema_migration registry for V1 / V6 / V7 / V8 / V9 (those migrations never wrote the registry row); registry-only, no schema change')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 【回滚说明】（本仓不提供自动 down 迁移；Flyway 为 forward-only。手工回滚步骤）
--
--   🛑 回滚本迁移【不会】造成数据损失：它只往登记表写了 5 行描述性文字。
--      回滚的代价是回到"登记表缺 5 个版本、人工对账会得出错误答案"的状态。
--
--   ① DELETE FROM schema_migration WHERE version IN ('V1','V6','V7','V8','V9','V13');
--   ② DELETE FROM flyway_schema_history WHERE version = '13';
-- ---------------------------------------------------------------------------