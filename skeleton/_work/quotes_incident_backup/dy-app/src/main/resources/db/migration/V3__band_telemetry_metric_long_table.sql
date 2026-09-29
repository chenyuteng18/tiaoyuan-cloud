-- ============================================================================
-- V3 迁移: band_telemetry —— 客户级穿戴手环遥测（长表 metric 化 · 方案 A）
--
-- 本迁移的【裁定依据】= 2026-09-23 技术负责人对 F-2 的定案:
--   · F-2「band_telemetry 建模: 宽表 vs 长表」→ 裁定 = 【方案 A · 长表 metric 化】
--   · 判据: J3 决定性(contract §4.3 双分支密钥已按 metric 为键成员冻结;
--           选 B 会与已冻结契约分叉); J1 是; J2 否; J4 是(需要时用视图, 不用宽表落库);
--           J5 否(PRD §8 宽表写法 = 呈现层口径, 与落库非同层)
--   · 拍板人 = 技术负责人; 契约侧联动 = 析客(选 A ⇒ 契约幂等键无需改动)
--   · 回写件: _work/data-dict-entities-ddl-2026-09-19.md §2.17【已定案 · F-2】
--
-- 字段级定义的【唯一权威来源】= 同上字典文件的 §附 DDL 示意 band_telemetry 段
--   (该段自述"字段 / 类型 / 约束为准"); 本文件按 §附 DDL 现值落库,
--   仅对下表【显式适配】处做必要变更, 每处均注明理由。
--
-- 与 V2 的关系:
--   · V2 第 4 节曾做"条件式 FK 闭合": 若 band_telemetry 已存在, 则追加
--     device_id -> band(band_id)。当时该表【不存在】(F-2 未定案), 故跳过。
--   · 本文件【建出该表并自带 FK】→ V2 的条件式动作此后成为幂等 no-op(已存在则跳过),
--     两条迁移链不冲突、可任意顺序重放。
--
-- 【入场纪律】迁移可幂等重复执行(全部 IF NOT EXISTS / DROP POLICY IF EXISTS)。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) band_telemetry —— 一行一 metric (长表)
--    🛑 与 device (门店级调理设备 · 下行) 两本台账、不得合并。
--    🛑 属【客户级】属性: 随客户跨店移动, 不做门店锁定(与 V2 的 band 同规则)。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS band_telemetry
(
    telemetry_id   UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    customer_id    UUID         NOT NULL REFERENCES customer (id),

    -- 客户级手环 (≠ device.device_id); FK 目标 = band.band_id (§2.26 · BD-3 · F-3)
    -- 悬空 FK 于本表落库时【闭合】(R-4 关闭)
    device_id      UUID         NOT NULL REFERENCES band (band_id),

    -- metric 枚举: 13 值逐项落库 (D-10「逐项落库」; 不得只存中文标签)
    metric         VARCHAR(32)  NOT NULL CHECK (metric IN (
                       'sleep','steps','hr','resting_hr','spo2','workout',
                       'bp','temp','pressure','met','mai','respiration','exercise')),

    -- 业务日 (设备本地时区) —— 幂等键成员
    date           DATE         NOT NULL,
    -- 分时点 —— 幂等键成员; 日聚合型退化为 NULL (见下方唯一索引的 COALESCE 处理)
    hour           INT,
    minute         INT,

    -- 标量指标值 (睡眼分期等结构化值入 sleep_json)
    value_num      NUMERIC(12,4),

    -- 睡眠分期 (结构化); 字典 §2.17 字段表列名即 sleep_json。
    -- ⚠️ 字典 F-2 对比表曾提"结构化值入 value_json"(通用位), 但 §附 DDL 现值用
    --    sleep_json 专名承载 —— 本文件从 §附 DDL。若后续需通用 value_json, 走增项流程。
    sleep_json     JSONB,

    -- 🛑 NULL = 缺失; 严禁补 0 (硬纪律 #4)
    coverage_flag  BOOLEAN,

    -- ⚠️ DEFECT FIX (2026-09-23, 由本迁移的真库反向验证发现):
    --    字典 §附 DDL 原文为 `data_source text NOT NULL DEFAULT 'band' CHECK (... IN ('手环','未接入'))`。
    --    该 DEFAULT 与自身 CHECK 【互相矛盾】: DEFAULT 'band' 不在 ('手环','未接入') 内
    --    ⇒ 任何【不显式给 data_source】的 INSERT 都会被 23514 拒绝(实测复现)。
    --    本文件把 DEFAULT 收敛为 CHECK 内的 '手环'(不改变取值集, 只堵死"默认值永远非法"这一死结)。
    --    🛑 遗留待裁: `data_source` 是否应改用【英文枚举 token】(如 'band'/'not_connected')
    --       而非中文标签 —— 中文标签与 D-10「逐项落库(不得只存中文标签)」及 §2.16 的
    --       `self-report/device` 英文 token 风格不一致; 因该字段契约可见(§4.5 R6),
    --       改口径须另立裁定, 本迁移【不擅自改口径】。
    data_source    VARCHAR(16)  NOT NULL DEFAULT '手环'
                               CHECK (data_source IN ('手环', '未接入')),

    -- 缺口归因 —— 🛑 7 值 (2026-09-23 R1 裁定: 与契约 §3.3 统一为 7 值 + 拼写 compliant_removal)
    -- 🛑 仅门店 / 管理端可见, 客户端一律 403 (可见性由服务端判定, 见契约 §3)
    gap_reason     VARCHAR(32)  CHECK (gap_reason IN (
                       'no_open','sync_failed','not_worn','compliant_removal',
                       'involuntary_technical','beyond_retention_window','unknown')),

    -- 落库四态 (客户端契约为 no_data_today, 二者显式映射 · ADR 十五 F-8)
    sync_state     VARCHAR(16)  NOT NULL DEFAULT 'synced'
                               CHECK (sync_state IN ('syncing','synced','sync_failed','no_data')),

    -- 🛑 必填: 无它则"延迟"与"缺失"不可分辨 (字典 §4.5)
    synced_at      TIMESTAMPTZ  NOT NULL,

    -- isWear 三值: 1 佩戴 / 0 脱腕 / (-1,255) 无效(一律不判行为性)
    is_wear        INT          CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255)),

    -- 设备本地时区偏移 (分钟)
    local_tz_offset INT,

    -- 运动游标型分支的幂等键成员 (契约 §4.3 双分支密钥); 日型为 NULL。
    -- 契约 §4.3 明定: 运动数据单开游标型 (device_id,'sport',currentSportId) ——
    -- 该分支必须落库承载, 否则"日型单一键"无法与运动分支区分。故本列属契约实现, 非新增口径。
    sport_id       VARCHAR(64),

    -- 审计字段 (全表必带, 见 V2 文件头纪律 ⑤)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(128)
);

-- ---------------------------------------------------------------------------
-- 2) 幂等键 (契约 §4.3 双分支)
--    分支① 按日型 (12 条): (device_id, metric, date, hour, minute)
--      ⚠️ 行业陷阱: hour / minute 日聚合型为 NULL, 而 PG 的 UNIQUE 视 NULL 互不相等
--         ⇒ 裸 UNIQUE(...) 对日聚合行【不生效】, 同一日可插多行 → 幂等被静默破坏。
--         故用【表达式唯一索引】把 NULL 规范为 -1 (设备时区无 -1 时点, 不冲突)。
--    分支② 游标型 (运动): (device_id, metric, sport_id)
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_bt_daily_idempotent
    ON band_telemetry (device_id, metric, date,
                       COALESCE(hour, -1), COALESCE(minute, -1))
    WHERE sport_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bt_sport_cursor
    ON band_telemetry (device_id, metric, sport_id)
    WHERE sport_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bt_tenant
    ON band_telemetry (tenant_id);
CREATE INDEX IF NOT EXISTS idx_bt_customer_date
    ON band_telemetry (tenant_id, customer_id, date);
CREATE INDEX IF NOT EXISTS idx_bt_device_metric_date
    ON band_telemetry (device_id, metric, date);
CREATE INDEX IF NOT EXISTS idx_bt_gap_reason
    ON band_telemetry (tenant_id, gap_reason);

-- ---------------------------------------------------------------------------
-- 3) RLS —— 严格照抄 V1 的 fail-closed 风格 (ENABLE + FORCE + 双 NULLIF)
--    未设 app.tenant_id 上下文 ⇒ 零行 (唯一允许语义)
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE band_telemetry FORCE ROW LEVEL SECURITY;

-- DROP POLICY IF EXISTS 是幂等必需 (PG 的 CREATE POLICY 无 IF NOT EXISTS)
DROP POLICY IF EXISTS tenant_isolation ON band_telemetry;
CREATE POLICY tenant_isolation ON band_telemetry
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 4) 迁移版本登记 (幂等 upsert, 沿用 V1 / V2 写法)
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V3', 'band_telemetry: metric long-table (F-2 option A) + gap_reason 7-value CHECK + dual idempotency keys + RLS + FK to band')
ON CONFLICT (version) DO NOTHING;

-- ============================================================================
-- V3 完成标记
-- 复核要点 (供 verification 脚本断言):
--   (a) band_telemetry.relrowsecurity = t 且 relforcerowsecurity = t
--   (b) gap_reason CHECK 恰含 7 值, 拼写为 compliant_removal (无 compliance_removal)
--   (c) 两幂等索引存在: uq_bt_daily_idempotent / uq_bt_sport_cursor
--   (d) FK fk_band_telemetry_device_id -> band(band_id) 存在 (V2 第 4 节的条件式动作
--       在本表存在后成为 no-op; FK 由本表自带)
-- ============================================================================