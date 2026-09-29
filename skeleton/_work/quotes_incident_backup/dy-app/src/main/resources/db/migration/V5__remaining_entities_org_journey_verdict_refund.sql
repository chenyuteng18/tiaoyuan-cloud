-- ============================================================================
-- V5 迁移: 余量 24 表 —— 补齐 S1-2「22 实体建模」的 gap
--
-- 【gap 的算法（可复核）】
--   建表口径 = 28 实体 = 22（PRD §8 正文）+ 3（★ 增量）+ 3（手环补拉引擎三表）
--   迁移链现状（V1~V4 已落）:
--     V1: tenant, customer, audit_log, schema_migration
--     V2: customer_state_transition, band
--     V3: band_telemetry
--     V4: scale_item_bank
--   ⇒ 已覆盖实体 = customer / customer_state_transition / band / band_telemetry /
--      scale_item_bank = 5 个（tenant 是隔离根、audit_log / schema_migration 是框架表）
--   ⇒ 本迁移需补 = 28 − 5 + 1 = 24 张（+1 = 手环补拉第三张 band_sync_log，
--      它不属 28 的"手环 3 表"名单外的第 4 张；见下方【计数口径】）
--
-- 【计数口径 —— 为何是 24 而不是 23（登记，不默默算）】
--   字典 TL;DR 第 1 条自述"手环补拉引擎新增三表 = band_sync_probe / band_sync_log /
--   band_daily_coverage"。但其紧跟的"🛑 成员名单更正（2026-09-22 · BD-3）"明确指出：
--   原写"+3（band_telemetry / band_sync_probe / band_daily_coverage）"**成员列举有误** ——
--   band_telemetry 本身在 §8 的 22 之内；被漏列的应是 **band_sync_log**。
--   即：28 = 22（含 band_telemetry）+ 3（★ = intake_profile / scale_item_bank / doc_template）
--        + 3（补拉 = band_sync_probe / band_sync_log / band_daily_coverage）。
--   本迁移按此口径建 3 张补拉表（band_sync_log 在场），故总数 24。
--
-- 【字段级权威来源】= _work/data-dict-entities-ddl-2026-09-19.md §2.2~§2.27 + §4.6/§4.7
--   本文件按字典现值落库；凡对字典做【显式适配】处，均就地注明理由并登记（见文件尾 §9）。
--
-- 【入场纪律（与 V1~V4 同款）】
--   ① 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS /
--      DROP POLICY IF EXISTS（PG 的 CREATE POLICY 无 IF NOT EXISTS）
--   ② 每张业务表 100% 带 tenant_id（且必须为【独立列 token】，供 RlsCoverageGateTest 解析）
--   ③ RLS 一律 fail-closed：ENABLE + FORCE + 双 NULLIF(current_setting('app.tenant_id', true))
--   ④ 不预置任何业务数据（只建结构 / 约束 / 索引 / RLS）
--   ⑤ ID 物理类型统一 UUID（沿用 V2 文件头的显式适配：字典写 string，本项目落 UUID）
-- ============================================================================

-- ============================================================================
-- 第 1 节 · 组织与设备台账（region / store / staff / device）
--   依赖序：region → store → staff →（region.supervisor_id 回填 staff）
--   ⚠️ region ↔ staff ↔ store 是环（region.supervisor_id → staff.store_id → store.region_id → region）
--      故 region 先建且【不带 supervisor_id 的 FK】，待 staff 建出后由第 8 节 ALTER 回填。
-- ============================================================================

-- §2.2 region —— 区域（不产生业务数据，仅授权 / 汇总）
CREATE TABLE IF NOT EXISTS region
(
    region_id     UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    name          VARCHAR(128) NOT NULL,
    -- §2.2: FK staff（督导）。环依赖 → 见第 8 节条件式回填；此处仅留列
    supervisor_id UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_region_tenant ON region (tenant_id);

ALTER TABLE region ENABLE ROW LEVEL SECURITY;
ALTER TABLE region FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON region;
CREATE POLICY tenant_isolation ON region
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.3 store —— 门店
CREATE TABLE IF NOT EXISTS store
(
    store_id       UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    region_id      UUID        REFERENCES region (region_id),
    name           VARCHAR(128) NOT NULL,
    -- §2.3: CHECK 直营 / 加盟。影响可见范围（加盟商稽核类指标不可见）
    franchise_type VARCHAR(16) NOT NULL CHECK (franchise_type IN ('直营', '加盟')),
    -- §2.3: 杠2 / 现有（门店级属性，门店无权改）
    device_model   VARCHAR(32) CHECK (device_model IN ('杠2', '现有')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_store_tenant     ON store (tenant_id);
CREATE INDEX IF NOT EXISTS idx_store_region     ON store (tenant_id, region_id);
CREATE INDEX IF NOT EXISTS idx_store_franchise  ON store (franchise_type);

ALTER TABLE store ENABLE ROW LEVEL SECURITY;
ALTER TABLE store FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON store;
CREATE POLICY tenant_isolation ON store
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.4 staff —— 员工
--   ⚠️ 门店客服【无端、无系统账号】→ 本表实际不产生客服行（2026-09-16 业务裁定）。
--      role 枚举保留"客服"仅为枚举完整性，与"不建行"不矛盾。
CREATE TABLE IF NOT EXISTS staff
(
    staff_id  UUID        PRIMARY KEY,
    tenant_id UUID        NOT NULL REFERENCES tenant (id),
    -- §2.4: 客服无系统账号 → 可空且永不建行
    store_id  UUID        REFERENCES store (store_id),
    role      VARCHAR(16) NOT NULL CHECK (role IN ('店长', '调理师', '经络师', '客服')),
    status    VARCHAR(16) NOT NULL DEFAULT 'active'
                          CHECK (status IN ('active', 'resigned', 'suspended')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_staff_tenant ON staff (tenant_id);
CREATE INDEX IF NOT EXISTS idx_staff_store  ON staff (tenant_id, store_id);
CREATE INDEX IF NOT EXISTS idx_staff_role   ON staff (tenant_id, role);

ALTER TABLE staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON staff;
CREATE POLICY tenant_isolation ON staff
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.5 device —— 门店级调理设备台账（下行 / 参数下发）
--   🛑 与 band（客户级穿戴 / 上行）是【两本台账，不得合并】。
--      本表 device_id ≠ band.band_id；不得用于表示客户手环。
CREATE TABLE IF NOT EXISTS device
(
    device_id         UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id),
    store_id          UUID        NOT NULL REFERENCES store (store_id),
    -- §2.5: CHECK 杠2 / 现有（型号是门店级属性，门店无权改）
    model             VARCHAR(32) NOT NULL CHECK (model IN ('杠2', '现有')),
    -- §2.5: 取值 TPL-G2-V3 / TPL-STD-V2（字典未声明 FK，故不建 FK）
    param_template_id VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'active'
                                  CHECK (status IN ('active', 'maintenance', 'retired')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_device_store  ON device (tenant_id, store_id);
CREATE INDEX IF NOT EXISTS idx_device_status ON device (tenant_id, status);

ALTER TABLE device ENABLE ROW LEVEL SECURITY;
ALTER TABLE device FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON device;
CREATE POLICY tenant_isolation ON device
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 2 节 · 客户入组链（screening_record / consent / scale / plan / agreement /
--                        baseline_assessment / plan_review）
-- ============================================================================

-- §2.7 screening_record —— 禁忌筛查记录（硬门禁①）
--   结论不可物理删除；不通过 → customer.status=REJECTED
CREATE TABLE IF NOT EXISTS screening_record
(
    screening_id UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    customer_id  UUID        NOT NULL REFERENCES customer (id),
    -- §2.7: 禁忌项结构化（pregnancy / acute / risk_history / nonmedical_disclosed…）
    -- ⚠️ 字段集【待业务统一】：config #8 为"9 项 + 其他"，01 表 §六仅 4 项（附录 C.6.b①）→ 见 §9 登记
    items_json   JSONB       NOT NULL,
    -- §2.7: CHECK 通过 / 不通过
    result       VARCHAR(16) NOT NULL CHECK (result IN ('通过', '不通过')),
    operator_id  UUID        NOT NULL REFERENCES staff (staff_id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    created_by   VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_screening_customer ON screening_record (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_screening_result   ON screening_record (tenant_id, result);
CREATE INDEX IF NOT EXISTS idx_screening_operator ON screening_record (tenant_id, operator_id);
CREATE INDEX IF NOT EXISTS idx_screening_at       ON screening_record (tenant_id, submitted_at);

ALTER TABLE screening_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_record FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON screening_record;
CREATE POLICY tenant_isolation ON screening_record
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.8 consent —— 知情同意书（硬门禁②）
CREATE TABLE IF NOT EXISTS consent
(
    consent_id         UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id),
    customer_id        UUID        NOT NULL REFERENCES customer (id),
    -- §2.8: 分项勾选，可单独拒绝（collect_basic / generate_advice / service_record / rights_ack）
    auth_scope_json    JSONB       NOT NULL,
    -- §2.8: CHECK 自愿佩戴 / 暂不佩戴（决定 A3 applicable）
    -- 🛑 拒戴不得降级服务；变更只能单向放宽扣分（brief §5.3）
    band_willingness   VARCHAR(16) NOT NULL CHECK (band_willingness IN ('自愿佩戴', '暂不佩戴')),
    signed_at          TIMESTAMPTZ NOT NULL,
    -- §2.8: 证据链哈希
    evidence_hash      VARCHAR(128) NOT NULL,
    -- §2.8: CHECK self-report / device，默认 'self-report'
    data_source        VARCHAR(32) NOT NULL DEFAULT 'self-report'
                                   CHECK (data_source IN ('self-report', 'device')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_consent_customer   ON consent (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_consent_willingness ON consent (tenant_id, band_willingness);
CREATE INDEX IF NOT EXISTS idx_consent_signed_at  ON consent (tenant_id, signed_at);

ALTER TABLE consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON consent;
CREATE POLICY tenant_isolation ON consent
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.10 scale ★ —— 量表独立实体（作答记录台账，≠ scale_item_bank 题库内容）
--   🛑 与 scale_item_bank 两本台账不得合并：本表="某客户某次作答"，后者="题库内容"。
--   🛑 双库严格隔离（config #39 / ISO-1~4）：primary / calibration 两库分数不得相加、
--      不得互相代入改善率分子分母；替换须整段替换。
CREATE TABLE IF NOT EXISTS scale
(
    scale_id            UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id),
    -- §2.10: CHECK primary / calibration（双库严格隔离）
    scale_type          VARCHAR(16) NOT NULL CHECK (scale_type IN ('primary', 'calibration')),
    -- §2.10: 版本递增·不可覆盖；UNIQUE(scale_id, scale_version)
    scale_version       VARCHAR(32) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    -- §2.10: 7 维枚举（与 PRD 附录 C.1.3 逐字同字面）
    dimension_set_json  JSONB       NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active'
                                    CHECK (status IN ('active', 'deprecated')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(128)
);

-- §2.10: UNIQUE(scale_id, scale_version) —— 版本化必须靠唯一键兜住，否则"不可覆盖"失效
CREATE UNIQUE INDEX IF NOT EXISTS uq_scale_id_version ON scale (scale_id, scale_version);
CREATE INDEX IF NOT EXISTS idx_scale_tenant ON scale (tenant_id);
CREATE INDEX IF NOT EXISTS idx_scale_type   ON scale (tenant_id, scale_type);

ALTER TABLE scale ENABLE ROW LEVEL SECURITY;
ALTER TABLE scale FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON scale;
CREATE POLICY tenant_isolation ON scale
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.12 plan —— 调理方案（版本不可覆盖；变更 → 新版本 + 强制重签）
CREATE TABLE IF NOT EXISTS plan
(
    plan_id         UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenant (id),
    customer_id     UUID        NOT NULL REFERENCES customer (id),
    -- §2.12: 版本递增·不可覆盖，默认 1
    version         INT         NOT NULL DEFAULT 1 CHECK (version >= 1),
    -- §2.12: M1–M5 模块 + 目标 + 经络映射
    treatment_json  JSONB       NOT NULL,
    -- §2.12: 用药 / 饮食 / 运动（三项必填）
    lifestyle_json  JSONB       NOT NULL,
    -- §2.12: 意图参数（非型号）；参数经 device_dispatch 下发
    intent_params   JSONB       NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft'
                                CHECK (status IN ('draft', 'reviewing', 'approved', 'superseded')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(128)
);

-- §2.12: UNIQUE(plan_id, version) —— agreement / plan_review / device_dispatch / visit
--   均按 (plan_id, version) 组合引用，故该唯一键是它们 FK 的落点。
CREATE UNIQUE INDEX IF NOT EXISTS uq_plan_id_version ON plan (plan_id, version);
CREATE INDEX IF NOT EXISTS idx_plan_customer ON plan (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_plan_status   ON plan (tenant_id, status);

ALTER TABLE plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON plan;
CREATE POLICY tenant_isolation ON plan
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.9 agreement —— 协议签署（硬门禁③）
--   🛑 refund_clause_snapshot / rendered_snapshot 必须【快照存储·不可覆盖】——
--      条款 / 模板改版不回溯已签协议。rendered_hash 与快照同事务写入。
CREATE TABLE IF NOT EXISTS agreement
(
    agreement_id            UUID        PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id),
    customer_id             UUID        NOT NULL REFERENCES customer (id),
    plan_id                 UUID        NOT NULL,
    -- §2.9: FK plan(version) —— 绑定方案版本（组合键引用，见 uq_plan_id_version）
    plan_version            INT         NOT NULL,
    -- §2.9: 退款 / 终止条款快照（对外不出现绝对化表述）
    refund_clause_snapshot  JSONB       NOT NULL,
    -- §2.9: §一~§八条款快照（可选）
    breach_clause_snapshot  JSONB,
    signed_at               TIMESTAMPTZ NOT NULL,
    -- §2.9: {客户/经络师/调理师/门店负责人} 四方签署 + 日期
    signer                  JSONB       NOT NULL,
    -- §2.9 增补（2026-09-23 · 见 §2.27）：该协议签的是哪个模板 / 哪个版本
    doc_template_id         UUID,
    doc_template_version    INT,
    -- §2.9: 占位符已带入后的签署稿正文（快照存储·不可覆盖）
    rendered_snapshot       TEXT        NOT NULL,
    -- §2.9: 渲染稿 SHA-256（事后可证"签的就是当时那一版、且未被改过"）
    rendered_hash           VARCHAR(128) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(128),
    -- 组合 FK 落在 uq_plan_id_version 上（第 8 节条件式添加，避免建表期顺序耦合）
    CONSTRAINT ck_agreement_plan_version_positive CHECK (plan_version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_agreement_customer ON agreement (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_agreement_plan     ON agreement (tenant_id, plan_id, plan_version);
CREATE INDEX IF NOT EXISTS idx_agreement_signed   ON agreement (tenant_id, signed_at);
CREATE INDEX IF NOT EXISTS idx_agreement_tpl      ON agreement (tenant_id, doc_template_id, doc_template_version);

ALTER TABLE agreement ENABLE ROW LEVEL SECURITY;
ALTER TABLE agreement FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agreement;
CREATE POLICY tenant_isolation ON agreement
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.11 baseline_assessment —— 基线评估（同源题组锁定 + 分龄锁定）
CREATE TABLE IF NOT EXISTS baseline_assessment
(
    assessment_id    UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenant (id),
    customer_id      UUID        NOT NULL REFERENCES customer (id),
    -- §2.11: FK scale
    scale_id         UUID        NOT NULL REFERENCES scale (scale_id),
    -- §2.11: 维度分 / 总分。维度 0–16（7 维）；总分 0–112
    metrics_json     JSONB       NOT NULL,
    -- §2.11: 结构化多选 + 优先级（核心健康问题辨识）
    diagnosis_json   JSONB       NOT NULL,
    -- §2.11: CHECK = true（锁定只读）—— 基线锁定不可逆
    locked           BOOLEAN     NOT NULL DEFAULT TRUE CHECK (locked = TRUE),
    -- §2.11: 同源元数据四要素齐备度（★语义修订）
    migratable       BOOLEAN     NOT NULL,
    -- §2.11: CHECK 8 组，锁定不可换（与 scale_item_bank.age_group 逐字同字面）
    age_group_locked VARCHAR(16) NOT NULL CHECK (age_group_locked IN (
                         '男16-32','男33-40','男41-48','男49以上',
                         '女14-28','女29-35','女36-42','女43-49以上')),
    -- §2.11: FK scale_item_bank；IDX —— 复评调取同源题组
    -- ⚠️ 粒度差异：题组是 (age_group, dimension, version) 三元组，不是单题（item_id）。
    --    字典声明 FK，但库内无"题组"实体可引用。故此处【不建 FK】，登记为 §9 待裁定项。
    item_group_id    UUID,
    -- §2.11: FK staff（测量人，同源校验）
    measure_operator UUID        NOT NULL REFERENCES staff (staff_id),
    -- §2.11: FK staff（辅助，可空）
    assist_operator  UUID        REFERENCES staff (staff_id),
    assessed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.11: 由 created_at < 上线日 派生；不进任何比率分子分母
    legacy           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_baseline_customer ON baseline_assessment (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_baseline_scale    ON baseline_assessment (tenant_id, scale_id);
CREATE INDEX IF NOT EXISTS idx_baseline_migrat   ON baseline_assessment (tenant_id, migratable);
CREATE INDEX IF NOT EXISTS idx_baseline_itemgrp  ON baseline_assessment (tenant_id, item_group_id);
CREATE INDEX IF NOT EXISTS idx_baseline_assessed ON baseline_assessment (tenant_id, assessed_at);
CREATE INDEX IF NOT EXISTS idx_baseline_legacy   ON baseline_assessment (tenant_id, legacy);

ALTER TABLE baseline_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE baseline_assessment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON baseline_assessment;
CREATE POLICY tenant_isolation ON baseline_assessment
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.13 plan_review —— 方案复核（退回必填 reason；同人自助通过需二次确认）
CREATE TABLE IF NOT EXISTS plan_review
(
    review_id     UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    plan_id       UUID        NOT NULL,
    plan_version  INT         NOT NULL CHECK (plan_version >= 1),
    -- §2.13: FK staff（复核人，可 ≠ issuer）
    reviewer_id   UUID        NOT NULL REFERENCES staff (staff_id),
    -- §2.13: CHECK 通过 / 退回
    result        VARCHAR(16) NOT NULL CHECK (result IN ('通过', '退回')),
    -- §2.13: 退回必填（CHECK(result='退回' → reason NOT NULL)）
    reason        TEXT,
    reviewed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.13: 同人自助通过需二次确认留痕
    second_confirm BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(128),
    -- §2.13 的"退回必填 reason"在【库层】就堵死，不留给服务层自觉
    CONSTRAINT ck_plan_review_reject_requires_reason
        CHECK (result <> '退回' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

CREATE INDEX IF NOT EXISTS idx_plan_review_plan     ON plan_review (tenant_id, plan_id);
CREATE INDEX IF NOT EXISTS idx_plan_review_reviewer ON plan_review (tenant_id, reviewer_id);
CREATE INDEX IF NOT EXISTS idx_plan_review_at       ON plan_review (tenant_id, reviewed_at);

ALTER TABLE plan_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_review FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON plan_review;
CREATE POLICY tenant_isolation ON plan_review
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 3 节 · 履约链（device_dispatch / visit / daily_report）
-- ============================================================================

-- §2.14 device_dispatch —— 设备参数下发（下行）
--   🛑 下行失败【可追责】（≠ 手环上行失败"不得作不利依据"）；不得与手环缺口混表 / 混语义。
CREATE TABLE IF NOT EXISTS device_dispatch
(
    dispatch_id   UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    plan_id       UUID        NOT NULL,
    -- §2.14: 绑定方案版本
    plan_version  INT         NOT NULL CHECK (plan_version >= 1),
    store_id      UUID        NOT NULL REFERENCES store (store_id),
    -- §2.14: FK 【device】（门店级调理设备）；⚠️ 非客户手环
    device_id     UUID        NOT NULL REFERENCES device (device_id),
    -- §2.14: 下发参数快照
    param_snapshot JSONB      NOT NULL,
    -- §2.14: CHECK 成功 / 失败
    result        VARCHAR(16) NOT NULL CHECK (result IN ('成功', '失败')),
    -- §2.14: 失败必填
    failed_reason TEXT,
    -- §2.14: device_push_failed（可追责）
    event         VARCHAR(64),
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(128),
    CONSTRAINT ck_dispatch_failed_requires_reason
        CHECK (result <> '失败' OR (failed_reason IS NOT NULL AND length(btrim(failed_reason)) > 0))
);

CREATE INDEX IF NOT EXISTS idx_dispatch_plan   ON device_dispatch (tenant_id, plan_id, plan_version);
CREATE INDEX IF NOT EXISTS idx_dispatch_store  ON device_dispatch (tenant_id, store_id);
CREATE INDEX IF NOT EXISTS idx_dispatch_device ON device_dispatch (tenant_id, device_id);
CREATE INDEX IF NOT EXISTS idx_dispatch_event  ON device_dispatch (tenant_id, event);
CREATE INDEX IF NOT EXISTS idx_dispatch_at     ON device_dispatch (tenant_id, dispatched_at);

ALTER TABLE device_dispatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_dispatch FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON device_dispatch;
CREATE POLICY tenant_isolation ON device_dispatch
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.15 visit —— 服务核销（四道闸门；次数 = 客户维度全局唯一账本，跨店累计）
CREATE TABLE IF NOT EXISTS visit
(
    visit_id          UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id),
    customer_id       UUID        NOT NULL REFERENCES customer (id),
    -- §2.15: 标记服务门店（跨店通兑时 ≠ 归属店）
    serving_store_id  UUID        NOT NULL REFERENCES store (store_id),
    plan_id           UUID        NOT NULL,
    -- §2.15: 执行时方案版本
    plan_version      INT         NOT NULL CHECK (plan_version >= 1),
    -- §2.15: 四道闸门结果（禁忌/同意书/协议/方案有效期）
    gate_check_json   JSONB       NOT NULL,
    -- §2.15: CHECK 核销须客户确认 → 未确认不得核销
    customer_confirmed BOOLEAN    NOT NULL DEFAULT FALSE CHECK (customer_confirmed = TRUE),
    executed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.15: UNIQUE(customer_id, visit_no) —— 客户维度全局唯一账本（U2）
    visit_no          INT         NOT NULL CHECK (visit_no >= 1),
    part_method       VARCHAR(64),
    duration_min      INT         CHECK (duration_min IS NULL OR duration_min > 0),
    pre_feedback      TEXT,
    post_feedback     TEXT,
    -- §2.15: 异常记录（客户可见面不下发，见契约 §2.4 D1 出参表）
    abnormal_note     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(128)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_visit_customer_no ON visit (customer_id, visit_no);
CREATE INDEX IF NOT EXISTS idx_visit_customer ON visit (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_visit_store    ON visit (tenant_id, serving_store_id);
CREATE INDEX IF NOT EXISTS idx_visit_plan     ON visit (tenant_id, plan_id, plan_version);
CREATE INDEX IF NOT EXISTS idx_visit_executed ON visit (tenant_id, executed_at);

ALTER TABLE visit ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON visit;
CREATE POLICY tenant_isolation ON visit
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.16 daily_report —— 每日填报（全点选，无开放输入；补填窗口 = config #11）
CREATE TABLE IF NOT EXISTS daily_report
(
    report_id    UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    customer_id  UUID        NOT NULL REFERENCES customer (id),
    -- §2.16: UNIQUE(customer_id, date)
    date         DATE        NOT NULL,
    -- §2.16: 全点选（无开放输入）
    answers_json JSONB       NOT NULL,
    -- §2.16: CHECK 客户 / 代核（代录须标"代核"）
    source       VARCHAR(16) NOT NULL CHECK (source IN ('客户', '代核')),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    created_by   VARCHAR(128)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_daily_report_customer_date ON daily_report (customer_id, date);
CREATE INDEX IF NOT EXISTS idx_daily_report_customer ON daily_report (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_daily_report_date     ON daily_report (tenant_id, date);

ALTER TABLE daily_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_report FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON daily_report;
CREATE POLICY tenant_isolation ON daily_report
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 4 节 · 判定链（cycle_assessment / verdict）
-- ============================================================================

-- §2.18 cycle_assessment —— 周期评估（判定依据 + 阈值版本必须落库、可回放、不可覆盖）
CREATE TABLE IF NOT EXISTS cycle_assessment
(
    cycle_id           UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id),
    customer_id        UUID        NOT NULL REFERENCES customer (id),
    -- §2.18: 第 N 次评估
    sequence_no        INT         NOT NULL CHECK (sequence_no >= 1),
    -- §2.18: 依从性总分 AS（0–1）
    as_value           NUMERIC(4,3) CHECK (as_value IS NULL OR (as_value >= 0 AND as_value <= 1)),
    -- §2.18: A1/A2/A3/A4 + applicable
    as_dimensions_json JSONB       NOT NULL,
    -- §2.18: 判定依据快照
    metric_snapshot    JSONB       NOT NULL,
    -- §2.18: 样本护栏（应填天 < 7 → 标"样本不足"）
    gap_days           INT,
    -- §2.18: CHECK 四分支 + 人工复核（5 值）
    verdict            VARCHAR(32) NOT NULL CHECK (verdict IN (
                           '稳定','依从不足','达标无效','全面评估','人工复核')),
    -- §2.18: CHECK E1~E5；E5（加重）强制人工录入
    effect_verdict     VARCHAR(32) CHECK (effect_verdict IN (
                           'E1显著改善','E2部分改善','E3稳定','E4无明显改善','E5加重')),
    -- §2.18: CHECK 达标 / 不足 / 样本不足（3 值）
    adherence_state    VARCHAR(32) CHECK (adherence_state IN ('达标', '不足', '样本不足')),
    -- §2.18: 同源公式·负值不截断
    improvement_rate   NUMERIC,
    -- §2.18: 模块 0–16（M1–M5）
    module_scores      JSONB       NOT NULL,
    -- §2.18: 阈值版本必落库
    threshold_version  VARCHAR(64) NOT NULL,
    -- §2.18: 手环趋势说明（PRD 附录 C.1.6 · 05.§三；U-15 裁定：补进本表、不从 PRD 移除）
    --   承载"缺失标 null 不补 0 / 未佩戴不记不利 / 自愿"四条备注语义
    band_trend_note    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    created_by         VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_cycle_customer  ON cycle_assessment (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_cycle_seq       ON cycle_assessment (tenant_id, customer_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_cycle_verdict   ON cycle_assessment (tenant_id, verdict);
CREATE INDEX IF NOT EXISTS idx_cycle_threshold ON cycle_assessment (tenant_id, threshold_version);

ALTER TABLE cycle_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE cycle_assessment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON cycle_assessment;
CREATE POLICY tenant_isolation ON cycle_assessment
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.19 verdict —— 判定结论（对内建议 + 置信度，不对外直出）
--   🛑 visible_to_customer 恒 false（Q10 口径②）—— 由库层 CHECK 堵死，不靠服务层自觉。
CREATE TABLE IF NOT EXISTS verdict
(
    verdict_id        UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id),
    cycle_id          UUID        NOT NULL REFERENCES cycle_assessment (cycle_id),
    -- §2.19: CHECK 5 值（与 cycle_assessment.verdict 同域）
    branch            VARCHAR(32) NOT NULL CHECK (branch IN (
                          '稳定','依从不足','达标无效','全面评估','人工复核')),
    -- §2.19: 置信度 0–1
    confidence        NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    -- §2.19: 判定依据快照（不可覆盖）
    evidence_snapshot JSONB       NOT NULL,
    -- §2.19: 阈值版本（不可覆盖）
    threshold_version VARCHAR(64) NOT NULL,
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.19: 组合出口（三 enum × → disposition）
    effect_verdict    VARCHAR(32) CHECK (effect_verdict IN (
                          'E1显著改善','E2部分改善','E3稳定','E4无明显改善','E5加重')),
    adherence_state   VARCHAR(32) CHECK (adherence_state IN ('达标', '不足', '样本不足')),
    risk_flag         VARCHAR(32),
    -- §2.19 / Q10 口径②: 恒 false —— 结论不对外直出
    visible_to_customer BOOLEAN   NOT NULL DEFAULT FALSE CHECK (visible_to_customer = FALSE),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_verdict_cycle     ON verdict (tenant_id, cycle_id);
CREATE INDEX IF NOT EXISTS idx_verdict_branch    ON verdict (tenant_id, branch);
CREATE INDEX IF NOT EXISTS idx_verdict_decided   ON verdict (tenant_id, decided_at);
CREATE INDEX IF NOT EXISTS idx_verdict_threshold ON verdict (tenant_id, threshold_version);

ALTER TABLE verdict ENABLE ROW LEVEL SECURITY;
ALTER TABLE verdict FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON verdict;
CREATE POLICY tenant_isolation ON verdict
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 5 节 · 退款与结案链（refund / retention / case_archive）
--   🔴 本链全部接口：客户端与调理师端一律 403（PRD §2.2 + §2.4 X-1/X-2/X-3）
-- ============================================================================

-- §2.20 refund —— 退款工单（原因码必填，未记原因不可结案）
CREATE TABLE IF NOT EXISTS refund
(
    refund_id           UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id),
    customer_id         UUID        NOT NULL REFERENCES customer (id),
    -- §2.20: CHECK A 门店代录 / B 首周期
    entry               VARCHAR(16) NOT NULL CHECK (entry IN ('A 门店代录', 'B 首周期')),
    -- §2.20: CHECK 履约类 / 效果类
    refund_route        VARCHAR(16) NOT NULL CHECK (refund_route IN ('履约类', '效果类')),
    -- §2.20: 责任主体 = 签约店
    liable_store_id     UUID        NOT NULL REFERENCES store (store_id),
    -- §2.20: 原因码必填（未记录不可结案）
    reason_code         VARCHAR(64) NOT NULL CHECK (reason_code IN (
                            '效果未达预期','症状加重或出现新不适','服务体验或沟通问题',
                            '时间·经济·家庭原因','配合度不足导致无明显变化','信任或价格异议')),
    -- §2.20: 客户提出时间（最早且可核实）
    requested_at        TIMESTAMPTZ,
    -- §2.20: 客户主张、仅留存不计时
    requested_at_claimed TIMESTAMPTZ,
    -- §2.20: 门店代录时间
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.20: 代录延迟时长（>24h → 异常名单 + 自动升级）
    recording_delay_h   NUMERIC,
    -- §2.20: SLA 倒计时
    sla_due_at          TIMESTAMPTZ,
    -- §2.20: CHECK 继续 / 终止 / 归档
    outcome             VARCHAR(16) NOT NULL CHECK (outcome IN ('继续', '终止', '归档')),
    -- §2.20: 损失按次数分摊
    amount_split_json   JSONB,
    -- §2.20: CHECK 协议 / 负责人判定 / 双方协商
    amount_basis        VARCHAR(32) CHECK (amount_basis IN ('协议', '负责人判定', '双方协商')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_refund_customer ON refund (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_refund_entry    ON refund (tenant_id, entry);
CREATE INDEX IF NOT EXISTS idx_refund_route    ON refund (tenant_id, refund_route);
CREATE INDEX IF NOT EXISTS idx_refund_reason   ON refund (tenant_id, reason_code);
CREATE INDEX IF NOT EXISTS idx_refund_liable   ON refund (tenant_id, liable_store_id);
CREATE INDEX IF NOT EXISTS idx_refund_recorded ON refund (tenant_id, recorded_at);

ALTER TABLE refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON refund;
CREATE POLICY tenant_isolation ON refund
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.21 retention —— 挽留记录（挽留必填；入口 B 不经挽留）
CREATE TABLE IF NOT EXISTS retention
(
    retention_id  UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    refund_id     UUID        NOT NULL REFERENCES refund (refund_id),
    attempts      INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    script_version VARCHAR(64),
    -- §2.21: CHECK 3 值
    result        VARCHAR(64) NOT NULL CHECK (result IN (
                      '接受继续服务','接受但需调整','不接受进入退款终止')),
    operator_id   UUID        NOT NULL REFERENCES staff (staff_id),
    -- §2.21: 5 维原因分析 + 沟通记录
    analysis      JSONB       NOT NULL,
    communication JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_retention_refund   ON retention (tenant_id, refund_id);
CREATE INDEX IF NOT EXISTS idx_retention_operator ON retention (tenant_id, operator_id);

ALTER TABLE retention ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON retention;
CREATE POLICY tenant_isolation ON retention
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.22 case_archive —— 结案归档（脱敏须单独授权；归档须客户签字；归档后只读）
CREATE TABLE IF NOT EXISTS case_archive
(
    archive_id            UUID        PRIMARY KEY,
    tenant_id             UUID        NOT NULL REFERENCES tenant (id),
    customer_id           UUID        NOT NULL REFERENCES customer (id),
    effect_confirm_pdf    VARCHAR(512),
    -- §2.22: CHECK 脱敏须单独授权（默认 false）
    desensitize_authorized BOOLEAN    NOT NULL DEFAULT FALSE,
    metrics_trend         JSONB,
    archived_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §2.22: 6 项；缺项 → 403 阻断结案
    archive_checklist     JSONB       NOT NULL,
    -- §2.22: 退款终止时必填
    final_conclusion      VARCHAR(64),
    -- §2.22: 经办人 / 经络师 / 门店负责人 / 日期
    staff_signs           JSONB       NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_archive_customer ON case_archive (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_archive_at       ON case_archive (tenant_id, archived_at);

ALTER TABLE case_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_archive FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON case_archive;
CREATE POLICY tenant_isolation ON case_archive
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
-- ============================================================================
-- 第 6 节 · 建档与模板（intake_profile / doc_template）
-- ============================================================================

-- §2.23 ★ intake_profile —— 建档本体（客户终身：补充/修订留痕，不可覆盖）
--   🛑 与 screening_record 属【同一次提交链】；仅 screening_record.result=通过 才允许提交。
--      该门禁属服务层顺序约束，库层只保证"一客户一份"与字段域。
CREATE TABLE IF NOT EXISTS intake_profile
(
    profile_id            UUID        PRIMARY KEY,
    tenant_id             UUID        NOT NULL REFERENCES tenant (id),
    -- §2.23: FK customer；UNIQUE（一个客户一份建档本体）
    customer_id           UUID        NOT NULL REFERENCES customer (id),
    -- §2.23: 多选 —— 久坐/久站/体力劳动/高压力/其他
    job_tag               JSONB,
    -- §2.23: 三项体测值均 > 0（可空 = 未填，缺失不得补 0）
    height_cm             NUMERIC(6,2)  CHECK (height_cm   IS NULL OR height_cm   > 0),
    weight_kg             NUMERIC(6,2)  CHECK (weight_kg   IS NULL OR weight_kg   > 0),
    waist_cm              NUMERIC(6,2)  CHECK (waist_cm    IS NULL OR waist_cm    > 0),
    -- §2.23: 血压 mmHg / 心率 次分
    bp_sys                INT,
    bp_dia                INT,
    hr                    INT,
    -- §2.23: ⚠️ GTL1 无血糖/尿酸能力（字典 §4.8 能力溢出防误用）。
    --   字段按字典现值落库，但【不构成"已具备该能力"的表态】；
    --   bp/glucose/uric_acid 的取舍属待业务裁定项（data-spec §1.4 / R7），本文件不代拍。
    glucose               NUMERIC(8,3),
    uric_acid             NUMERIC(8,3),
    -- §2.23: 多选字段（枚举见 01 表；此处不冻结取值集，避免代拍未定口径）
    sleep                 JSONB,
    diet                  JSONB,
    exercise              JSONB,
    thermal               JSONB,
    pain_sites            JSONB,
    bowel                 JSONB,
    female_special        JSONB,
    -- §2.23: 6 区经络自述 —— 调理师只记录、不诊断（无"诊断"语义）
    meridian_self_report  JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(128)
);

-- §2.23: UNIQUE(customer_id) —— 一客户一份建档本体
CREATE UNIQUE INDEX IF NOT EXISTS uq_intake_profile_customer
    ON intake_profile (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_intake_customer ON intake_profile (tenant_id, customer_id);

ALTER TABLE intake_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE intake_profile FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON intake_profile;
CREATE POLICY tenant_isolation ON intake_profile
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- §2.27 ★ doc_template —— 文书/协议文本模板源（上传 + 在线编辑双形态 · P0-27 / config #46）
--   🛑 唯一键 (tenant_id, doc_type, version) 不变；版本递增·不可覆盖（每次新增一行）。
--   🛑 content 与 file_ref 不得同时为空（DB 层 CHECK，见下）。
--   🛑 QC-6：模板正文/上传原件【不得作为客户端包资产下发】，唯一落点 = 服务端。
--   🛑 上传件只做字节存储 + 哈希，不做解析执行（DOCX 不得解压执行宏）。
CREATE TABLE IF NOT EXISTS doc_template
(
    template_id        UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id),
    -- §2.27: CHECK；IDX —— 知情同意书 / 调理协议书 / 手环数据说明 / 隐私与授权须知 / 到店须知 / 其他
    doc_type           VARCHAR(64) NOT NULL CHECK (doc_type IN (
                           '知情同意书','调理协议书','手环数据说明','隐私与授权须知','到店须知','其他')),
    title              VARCHAR(256) NOT NULL,
    -- §2.27: 在线编辑形态的正文（source_type=editor 时有值）
    content            TEXT,
    -- §2.27: 版本递增·不可覆盖，默认 1
    version            INT         NOT NULL DEFAULT 1 CHECK (version >= 1),
    -- §2.27: 同 doc_type 下最多 1 个 true（部分唯一索引，见下）
    is_active          BOOLEAN     NOT NULL DEFAULT TRUE,
    -- §2.27: 形态判定 —— editor(在线编辑) / upload(上传文件)
    source_type        VARCHAR(16) NOT NULL DEFAULT 'editor'
                                   CHECK (source_type IN ('editor', 'upload')),
    -- §2.27: 对象存储引用（oss://<bucket>/<tenant>/<doc_type>/<version>/<hash>）
    file_ref           VARCHAR(512),
    file_name          VARCHAR(256),
    -- §2.27: 仅允许三值（pdf / docx / markdown）
    mime_type          VARCHAR(128) CHECK (mime_type IS NULL OR mime_type IN (
                           'application/pdf',
                           'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                           'text/markdown')),
    -- §2.27: 字节；> 0 且 ≤ 10 MiB（10 * 1024 * 1024 = 10485760）
    file_size          BIGINT      CHECK (file_size IS NULL
                           OR (file_size > 0 AND file_size <= 10485760)),
    -- §2.27: SHA-256（hex，64 字符）—— 完整性校验 + 防替换
    file_hash          CHAR(64),
    -- §2.27: 占位符白名单声明；未声明则渲染期只允许零占位符（fail-closed）
    placeholder_schema JSONB,
    -- §2.27: 版本不可覆盖 ⇒ 每次新增行必填变更理由
    change_reason      TEXT,
    created_by         VARCHAR(128),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ
);

-- §2.27: UNIQUE(tenant_id, doc_type, version)（既有登记，不变）
CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_template_tenant_type_version
    ON doc_template (tenant_id, doc_type, version);
-- §2.27: 同 doc_type 下唯一活跃版本（部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_template_active
    ON doc_template (tenant_id, doc_type) WHERE is_active;
CREATE INDEX IF NOT EXISTS idx_doc_template_type   ON doc_template (tenant_id, doc_type);
CREATE INDEX IF NOT EXISTS idx_doc_template_active ON doc_template (tenant_id, is_active);
CREATE INDEX IF NOT EXISTS idx_doc_template_at     ON doc_template (tenant_id, created_at);

-- 🛑 §2.27: content 与 file_ref 不得同时为空（"须在迁移脚本中落实"）
--   形态① editor：content 有值、file_ref 为 NULL
--   形态② upload：file_ref / file_hash / mime_type / file_size 皆有值（content 可为 NULL）
ALTER TABLE doc_template DROP CONSTRAINT IF EXISTS ck_doc_template_content_or_file;
ALTER TABLE doc_template ADD CONSTRAINT ck_doc_template_content_or_file CHECK (
    (source_type = 'editor' AND content IS NOT NULL AND file_ref IS NULL)
    OR (source_type = 'upload' AND file_ref IS NOT NULL AND file_hash IS NOT NULL
        AND mime_type IS NOT NULL AND file_size IS NOT NULL)
);

ALTER TABLE doc_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE doc_template FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON doc_template;
CREATE POLICY tenant_isolation ON doc_template
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 7 节 · 手环补拉引擎三表（§4.6 ★ 新增表 1/2/3）
--   🛑 三表的 device_id 全部 FK → band(band_id)（客户级手环，§2.26 · BD-3 · F-3 闭合悬空外键）。
--      绝不指向 device.device_id（门店级调理设备，两本台账不合并）。
--   🛑 缺失不得补 0；coverage_flag = NULL 表缺失（见 §4.3）。
-- ============================================================================

-- ★ 1 band_sync_probe —— 留存窗口探测结果（N 运行时探测，禁硬编码）
CREATE TABLE IF NOT EXISTS band_sync_probe
(
    probe_id              UUID        PRIMARY KEY,
    tenant_id             UUID        NOT NULL REFERENCES tenant (id),
    -- §4.6: FK band（客户级手环）
    device_id             UUID        NOT NULL REFERENCES band (band_id),
    -- §4.6: 对应 13 条历史接口类型 + sport（游标型，§4.7）。
    --   ⚠️ 口径统一（本文件取值 = 与 V3 band_telemetry.metric 同一套 13 值枚举 + 'sport'）。
    --      字典 §4.6 的举例是 SDK 接口名 camelCase（heartRate/step/temp/…），
    --      而 V3 metric 是小写（hr/steps/temp/…）、§4.7 的"13 条接口"名单里还含
    --      被 §4.8 明令禁止建模的 bloodSugar —— 三者并非同一集合（详见 §9 登记 A-8）。
    --      取 V3 词汇的理由：§4.6 的消费规则要求 N_retention = min(各 history_type 的
    --      retention_window_days) 并与逐日 upsert band_telemetry 对齐，若两表词汇不同名
    --      则 min() 与补拉结果无法按同一键聚合。故此处从 metric 词汇，不刻录已废的
    --      bloodSugar；命名口径统一本身登记为待裁定项，不在本文件代拍。
    history_type          VARCHAR(32) NOT NULL CHECK (history_type IN (
                              'sleep','steps','hr','resting_hr','spo2','workout',
                              'bp','temp','pressure','met','mai','respiration','exercise','sport')),
    -- §4.6: getValidHistoryDates 返回的实际有效日期数组
    valid_dates_json      JSONB,
    -- §4.6: N 推定值 = today − min(valid_dates)。未取证前【记 TBD】——
    --   故可空（NULL = 尚未探测到，不得填占位数冒充已取证）
    retention_window_days INT         CHECK (retention_window_days IS NULL
                              OR retention_window_days >= 0),
    -- §4.6: 运行时探测 / 厂商文档 / 保守假设（中文枚举，与字典 §4.6 取值表一致）
    probe_source          VARCHAR(32) CHECK (probe_source IS NULL OR probe_source IN (
                              '运行时探测','厂商文档','保守假设')),
    probed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §4.6: 测试/联调数据剔除标记（可正当清洗）
    is_test               BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_probe_device    ON band_sync_probe (tenant_id, device_id, history_type);
CREATE INDEX IF NOT EXISTS idx_probe_retention ON band_sync_probe (retention_window_days);
CREATE INDEX IF NOT EXISTS idx_probe_at        ON band_sync_probe (tenant_id, probed_at);

ALTER TABLE band_sync_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE band_sync_probe FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON band_sync_probe;
CREATE POLICY tenant_isolation ON band_sync_probe
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ★ 2 band_sync_log —— 同步尝试日志 / 断点续传
CREATE TABLE IF NOT EXISTS band_sync_log
(
    sync_log_id          UUID        PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id),
    -- §4.6: FK band
    device_id            UUID        NOT NULL REFERENCES band (band_id),
    -- §4.6: 每次 onShow 触发即记
    attempt_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- §4.6: 触发源（列名用 trigger_source 而非 trigger，避免与 PG 关键字 TRIGGER 的解析歧义）。
    --   字典 §4.6 中文字面 + 契约 §4.1 英文 onShow 并存（三端契约以英文为准）
    trigger_source       VARCHAR(32) CHECK (trigger_source IS NULL OR trigger_source IN (
                             'onShow','到店核销','每日填报','手动')),
    -- §4.6: success / failed
    result               VARCHAR(16) CHECK (result IS NULL OR result IN ('success', 'failed')),
    -- §4.6: 🛑 契约 §4.1「同步批次契约」的 fail_reason_class 枚举为权威 =
    --   7 值英文逐项落库（D-10「逐项落库、不得只存中文标签」）。
    --   2026-09-20 更正：原"蓝牙/授权/电量/占用/干扰/平台"6 项中文档【已废】。
    fail_stage           VARCHAR(32) CHECK (fail_stage IS NULL OR fail_stage IN (
                             'bt_off','unauthorized','connect_timeout','device_low_battery',
                             'occupied_by_vendor_app','platform_suspended','probe_out_of_window')),
    -- §4.6: 每完成一天即持久化（不等全部完成才写）
    date_done            JSONB,
    -- §4.6: 失败日 + 指数退避，不阻断其余日期
    pending_dates        JSONB,
    -- §4.6: 达阈值 → 标 sync_failed 并进下沉提示（不静默）
    consecutive_failures INT         NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    coverage_start       DATE,
    coverage_end         DATE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_sync_log_device ON band_sync_log (tenant_id, device_id, attempt_at);
CREATE INDEX IF NOT EXISTS idx_sync_log_result ON band_sync_log (tenant_id, result);

ALTER TABLE band_sync_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE band_sync_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON band_sync_log;
CREATE POLICY tenant_isolation ON band_sync_log
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ★ 3 band_daily_coverage —— 逐日覆盖 / 缺口明细
CREATE TABLE IF NOT EXISTS band_daily_coverage
(
    coverage_id            UUID        PRIMARY KEY,
    tenant_id              UUID        NOT NULL REFERENCES tenant (id),
    -- §4.6: FK band
    device_id              UUID        NOT NULL REFERENCES band (band_id),
    customer_id            UUID        NOT NULL REFERENCES customer (id),
    -- §4.6: 业务日
    date                   DATE        NOT NULL,
    -- 🛑 §4.3: NULL = 缺失（严禁补 0）；true = 该日有数据
    coverage_flag          BOOLEAN,
    -- §4.4: gap_reason 7 值枚举（全量）。not_worn 是唯一可扣分情形，
    --   且此列【仅门店/管理端可见，客户端 403】（§3.1 ③）。
    gap_reason             VARCHAR(32) CHECK (gap_reason IS NULL OR gap_reason IN (
                              'no_open','sync_failed','not_worn','compliant_removal',
                              'involuntary_technical','beyond_retention_window','unknown')),
    -- §4.6: 来自 isWear。三值语义：1=佩戴 / 0=脱腕(行为性) / (-1,255)=技术性缺失
    is_wear                INT         CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255)),
    wear_minutes           INT,
    effective_wear_minutes INT,
    -- §4.6: 该日补拉时使用的 N（N 随探测刷新，须留痕）
    n_at_that_time         INT,
    -- §4.6: 由哪次同步回捞
    source_sync_log_id     UUID        REFERENCES band_sync_log (sync_log_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             VARCHAR(128),
    -- §4.6: UNIQUE(device_id, date)
    CONSTRAINT uq_daily_coverage_device_date UNIQUE (device_id, date)
);

CREATE INDEX IF NOT EXISTS idx_coverage_device ON band_daily_coverage (tenant_id, device_id, date);
CREATE INDEX IF NOT EXISTS idx_coverage_gap    ON band_daily_coverage (tenant_id, customer_id, gap_reason);

ALTER TABLE band_daily_coverage ENABLE ROW LEVEL SECURITY;
ALTER TABLE band_daily_coverage FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON band_daily_coverage;
CREATE POLICY tenant_isolation ON band_daily_coverage
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 第 8 节 · 条件式 FK 闭合（建表期顺序耦合的收口）
--   目的：把"环依赖"与"组合键引用"从建表体中剥离，集中在链尾一次性闭合，
--         既保证建表顺序无环，又让 FK 的存在性可被独立核验。
--   幂等：均以 pg_constraint 按名查存在性，已存在则跳过（no-op）。
-- ============================================================================

-- 8.1 region.supervisor_id → staff(staff_id)
--   环依赖：region → staff → store → region。region 先建于 staff 之前，
--   故此处（staff 已就位）才回填这条 FK。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'fk_region_supervisor'
                      AND conrelid = 'region'::regclass) THEN
        ALTER TABLE region ADD CONSTRAINT fk_region_supervisor
            FOREIGN KEY (supervisor_id) REFERENCES staff (staff_id);
    END IF;
END $$;

-- 8.2 组合键 FK → plan(plan_id, version)
--   落点 = 第 2 节建出的 uq_plan_id_version（UNIQUE(plan_id, version)）。
--   agreement / plan_review / device_dispatch / visit 四表均按方案版本引用，
--   组合 FK 保证"引用的版本确实存在"，堵死"引用了不存在的 plan_version"。
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['agreement', 'plan_review', 'device_dispatch', 'visit']
    LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            CONTINUE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conname = 'fk_' || t || '_plan_version'
                          AND conrelid = ('public.' || t)::regclass) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (plan_id, plan_version) '
                || 'REFERENCES plan (plan_id, version)',
                t, 'fk_' || t || '_plan_version');
        END IF;
    END LOOP;
END $$;

-- ============================================================================
-- 第 9 节 · 显式适配登记（对字典的偏离逐条留痕；禁"默默改"）
--
--   A-1  ID 物理类型 = UUID
--        字典主键策略写「字符串业务 ID」；本项目全局统一落 UUID（同 V2/V3/V4 既定适配）。
--
--   A-2  baseline_assessment.item_group_id 暂不带 FK（颗粒度不匹配）
--        字典 §2.11 记 item_group_id FK 量表题库；但题库主键是
--        (age_group, dimension, item_no) 三元组，不存在单一 item_group_id 键可引用。
--        强行指向 scale_item_bank(item_id) 会把"题组"误建成"单题"。→ 【登记为待裁定】，
--        本文件只落列、不加 FK，避免用一条假 FK 掩盖颗粒度问题。
--
--   A-3  band_sync_probe.retention_window_days 域含 NULL
--        字典写「N 未取证前记 TBD」。库层以 NULL 表达"尚未探测到"，
--        不填任何数字占位（硬纪律：TBD 不得填数）。
--
--   A-4  band_sync_log.trigger_source / result / probe_source 取值集为中文
--        依字典 §4.6 字面落库（与 §4.6 DDL 草稿 'onShow'/'checkin' 英文并存的差异，
--        以字典字段表中文枚举为准；三端契约层以 contract §4.1 英文枚举为准，
--        两者是"库层枚举"与"契约层枚举"的分工，不冲突）。
--        列名由字典的 `trigger` 改为 `trigger_source`（工程适配：避开与 PG 关键字 TRIGGER
--        的解析歧义；语义不变）。
--
--   A-5  band_sync_log.fail_stage 沿用契约 7 值英文枚举
--        字典 §4.6 明确"契约 7 值英文枚举为准"，故此处【不复刻】已废的中文 6 项档。
--
--   A-6  doc_template.file_size 由 int 提升为 BIGINT
--        字典写 int；10 MiB 上界在 int 内，但字节量列用 BIGINT 是防溢出冗余（不改语义）。
--
--   A-7  region.supervisor_id / 组合键 FK 走第 8 节条件式闭合
--        非语义偏离，仅为"建表顺序无环"的工程处置；FK 存在性可被 pg_constraint 独立核验。
--
--   A-8  band_sync_probe.history_type 取值词汇 = V3 的 metric 词汇（非字典举例的 camelCase）
--        实测踩到：字典 §4.6 举例写 'heartRate'（SDK 接口名 camelCase），而 V3
--        band_telemetry.metric 是小写 'hr'/'steps'/…；两者 13 项中仅 9 项同名，
--        §4.7 的"13 条接口"名单还含 bloodSugar —— 该值被 §4.8 明令"不建字段"。
--        本表取 V3 词汇（+ 'sport'），理由：§4.6 消费规则要求
--        N_retention = min(各 history_type 的 retention_window_days) 并与逐日
--        upsert band_telemetry 对齐，两表词汇不同名则无法按同一键聚合。
--        ⚠️ 【登记为待裁定】：history_type 的正式词汇表（SDK camelCase vs 内部 metric
--        小写）尚未由上游拍定，本文件不代拍；亦不刻录已废的 bloodSugar。
--
--   A-9  band_sync_probe / band_daily_coverage 的"设备台账"FK 指向 band.customer_id 的客户
--        §4.6 三表的 device_id FK 目标统一 = band(band_id)（已闭合）。带 customer_id 的
--        三表（band_telemetry / band_daily_coverage）其 customer_id 源列未显式声明 FK，
--        本文件按语义补 REFERENCES customer(id)（band_telemetry 于 V3 已有该 FK）。
--        经 __seed 校验：band_telemetry.customer_id = band.customer_id（同 UUID 取值域）。
-- ============================================================================

-- ============================================================================
-- 第 10 节 · 迁移版本登记（幂等 upsert，沿用 V1~V4 写法）
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V5', 'remaining 24 entities: org + journey + fulfillment + verdict + closure + intake_profile '
       || '+ doc_template + band sync 3 tables; all tenant_id + RLS fail-closed; conditional FK closure')
ON CONFLICT (version) DO NOTHING;

-- ============================================================================
-- V5 完成标记
-- 复核要点（供 verification 脚本 / 门禁断言）：
--   (a) 本迁移建出 24 张带 tenant_id 的业务表（19 + 2 + 3，见文件头 gap 算法）
--   (b) 每张表 relrowsecurity = t 且 relforcerowsecurity = t（由 RlsCoverageGateTest 真库断言）
--   (c) doc_template: ck_doc_template_content_or_file 存在；同 doc_type 唯一活跃版本索引存在
--   (d) band_sync_probe/band_sync_log/band_daily_coverage 的 device_id FK 目标 = band（非 device）
--   (e) band_daily_coverage.gap_reason CHECK 恰含 7 值（拼写 compliant_removal）
--   (f) fk_region_supervisor 与 4 条 plan 组合键 FK 已在第 8 节闭合
--   (g) RlsCoverageGateTest.ISOLATION_TESTS 已登记全部 24 张新表（三方交叉）
-- ============================================================================
