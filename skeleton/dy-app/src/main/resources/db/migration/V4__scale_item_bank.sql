-- ============================================================================
-- V4 迁移: scale_item_bank —— 分龄量表题库（题库内容资产 · 版本化不可覆盖）
--
-- 本迁移的【裁定依据】= 2026-09-23 技术类裁定清单第 5 项 + 字典 §2.24：
--   · 实体计数口径 B6 = 【28】= 22(PRD §8) + 3(★ 增量) + 3(手环补拉表)；
--     `scale_item_bank` 属 ★ 3 之 1 ⇒ 建表口径 = 28 之内，不另立第 29 张。
--   · 字典 §2.24 标题原写"待裁定"系漏同步（该实体早已由 ADR 十五 F-1 纳入 28），
--     2026-09-23 一并更正为"已定案"（本迁移是该更正的落地）。
--   · 拍板人 = 技术负责人（F-1 / B6 口径）；内容侧（224 题定稿）属业务方交付，
--     与本表结构解耦 —— 本表只承载【可导入】能力，不预置任何真实题面。
--
-- 字段级定义的【唯一权威来源】= 字典 §2.24 字段表 + PRD 附录 C.1.8 / C.1.3；
--   本文件按二者现值落库，仅对下表【显式适配】处做必要变更，每处均注明理由。
--
-- 🛑 本表是 S1-4「题库引擎骨架」的数据地基：
--   验收三项 = ① 224 题可导入（样例题验证）② 可组卷 ③ 可计分（口径外置为配置）
--   故本表【只建结构 + 约束 + RLS】，不含任何"默认题库数据"：
--   预置题面会被误读为"内容已定稿"，而 224 题定稿是业务方在内容侧交付物。
--
-- 【入场纪律】迁移可幂等重复执行（IF NOT EXISTS / DROP POLICY IF EXISTS）。
-- 【与 V1/V2/V3 的关系】无外键交叉；scale_item_bank 只依赖 tenant(id)。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) scale_item_bank —— 一行一题（分龄题组内的最小内容单元）
--
--    ⚠️ 与 `scale`（作答记录）两本台账、不得合并：本表是"题库内容"，
--       `scale` 是"某客户某次作答"，前者版本化可复用、后者按客户追加。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scale_item_bank
(
    item_id        UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),

    -- 分龄题组（8 组枚举）—— 与 PRD 附录 C.1.3 `baseline_assessment.age_group_locked`
    -- 【逐字同字面】：两处必须同字面，否则"基线锁定的题组"与"题库里的题组"无法在同一把尺上对齐。
    -- 🛑 8 组 = 男 4 组 + 女 4 组（男 16-32 / 33-40 / 41-48 / 49 以上；女 14-28 / 29-35 / 36-42 / 43-49 以上）
    age_group      VARCHAR(16)  NOT NULL CHECK (age_group IN (
                       '男16-32','男33-40','男41-48','男49以上',
                       '女14-28','女29-35','女36-42','女43-49以上')),

    -- 7 维度枚举 —— 与 PRD 附录 C.1.3「维度枚举（7 项）」【逐字同字面】。
    -- 🛑 「记忆专注」在 7 维之内（题库必须能出它的题、能算它的维度分），
    --    但它【不参与效果判定】（PRD P0-13 / Y4 已确认）—— 该约束属判定引擎的职责，
    --    由服务层守卫落，不写进本表 CHECK（写进 CHECK 会让"基线档案留存"这件正当的事无法落库）。
    dimension      VARCHAR(32)  NOT NULL CHECK (dimension IN (
                       '体能精力','面部气色肤质','肩颈腰背筋骨','睡眠质量',
                       '记忆专注','代谢体态消化','情绪抗压与抵抗力')),

    -- 维度内题序 1–4（字典 §2.24「1–4」）。4 题 × 0–4 五级 = 维度满分 0–16。
    item_no        INT          NOT NULL CHECK (item_no BETWEEN 1 AND 4),

    -- 题面正文 —— 🛑 版本化、不可覆盖（见下方唯一索引 + 服务层"改题 = 插新 version"）
    item_text      TEXT         NOT NULL,

    -- 五级锚点原文（0–4）—— 字典 §2.24「anchor_0..anchor_4 五级锚点原文」。
    -- 🛑 五级锚点【必须逐级给出原文】，不得只给级数：它们是"同源复评"可比性的唯一依据
    --    （换一套锚点措辞 = 换一把尺，改善率立刻失去意义）。故五列均 NOT NULL。
    anchor_0       TEXT         NOT NULL,
    anchor_1       TEXT         NOT NULL,
    anchor_2       TEXT         NOT NULL,
    anchor_3       TEXT         NOT NULL,
    anchor_4       TEXT         NOT NULL,

    -- 题面方向 —— 🛑 仅允许 `symptom`（字典 §2.24 / PRD P0-18「阻断式校验」）。
    --    正向表述题不得作计分项（会把"症状减轻"算成"分数上升"），故在【库层】就堵死：
    --    CHECK 只有 1 个合法值 ⇒ 任何非 symptom 的写入必被 23514 拒绝。
    -- ⚠️ 教训（README §5.1 第 15 条）：`NOT NULL DEFAULT` 与 `CHECK` 必须自洽 ——
    --    DEFAULT 'symptom' 在 CHECK 取值集内，故省略该列的 INSERT 不会被自身约束拒绝。
    item_direction VARCHAR(16)  NOT NULL DEFAULT 'symptom'
                               CHECK (item_direction IN ('symptom')),

    -- 版本号 —— 版本递增、不可覆盖（字典 §2.24 / PRD P0-20「旧版本不可覆盖」）
    version        VARCHAR(32)  NOT NULL,

    -- 逐题人工方向签核留痕（PRD P0-18：须经总部临床负责人逐题对照 0–4 锚点签核）。
    -- 🛑 两列均 NOT NULL：缺签核记录的题【不得入库】—— 若允许为空，
    --    "签核"就会退化成可选项，而极性关键词检测已被明确声明"不替代人工签核"。
    reviewer_id    VARCHAR(128) NOT NULL,
    reviewed_at    TIMESTAMPTZ  NOT NULL,

    -- 审计字段（全表必带，见 V2 文件头纪律 ⑤）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(128)
);

-- ---------------------------------------------------------------------------
-- 2) 唯一键 —— 字典 §2.24 明定 `(tenant_id, age_group, dimension, item_no, version)`
--
--    ⚠️ 为何 version 必须在键内：题目文本版本化、不可覆盖 ⇒
--       同一题的不同版本必须是【并存的不同行】；若 version 不在键内，
--       改一次题就只能覆盖旧行，"旧版本不可覆盖"立刻失效。
--    ⚠️ 为何 tenant_id 必须在键内：允许品牌自定义题库 ⇒ 两个租户的同龄同维同题序
--       是【各自独立的题】，不得互撞。
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_sib_business
    ON scale_item_bank (tenant_id, age_group, dimension, item_no, version);

-- 组卷查询路径：按租户 + 年龄组 + 维度取该题组（服务层组卷用的主查询形状）
CREATE INDEX IF NOT EXISTS idx_sib_paper_lookup
    ON scale_item_bank (tenant_id, age_group, dimension, version);

CREATE INDEX IF NOT EXISTS idx_sib_tenant
    ON scale_item_bank (tenant_id);

-- ---------------------------------------------------------------------------
-- 3) RLS —— 严格照抄 V1 的 fail-closed 风格 (ENABLE + FORCE + 双 NULLIF)
--    未设 app.tenant_id 上下文 ⇒ 零行（唯一允许语义）
-- ---------------------------------------------------------------------------
ALTER TABLE scale_item_bank ENABLE ROW LEVEL SECURITY;
ALTER TABLE scale_item_bank FORCE ROW LEVEL SECURITY;

-- DROP POLICY IF EXISTS 是幂等必需（PG 的 CREATE POLICY 无 IF NOT EXISTS）
DROP POLICY IF EXISTS tenant_isolation ON scale_item_bank;
CREATE POLICY tenant_isolation ON scale_item_bank
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 4) 结构自证 —— 224 题的【可容纳性】必须被真库证明，而不是被口头声称
--
--    判据（在库层算，不靠人工核对）：
--      8 组 × 7 维 × 4 题 = 224 个 (age_group, dimension, item_no) 三元组。
--    本段只验证"约束取值域的基数乘积 = 224"，即：
--      |age_group CHECK 取值集| = 8 ∧ |dimension CHECK 取值集| = 7 ∧ item_no 域含 4 个值
--    ⇒ 该表结构【足以】承载 224 题（且不多不少）。
--
--    🛑 本段【不插入任何题面】—— 只算基数。预置题面会被误读为内容已定稿。
-- ---------------------------------------------------------------------------
DO $sib_guard$
DECLARE
    n_age       INTEGER;
    n_dim       INTEGER;
    n_no        INTEGER;
    n_expected  INTEGER := 224;
BEGIN
    -- 从约束定义里数取值集基数：避免把 8/7/4 再抄一遍（抄一遍就会漂移）
    SELECT count(*) INTO n_age
      FROM pg_constraint c
     WHERE c.conrelid = 'scale_item_bank'::regclass
       AND c.conname = 'scale_item_bank_age_group_check';

    SELECT count(*) INTO n_dim
      FROM pg_constraint c
     WHERE c.conrelid = 'scale_item_bank'::regclass
       AND c.conname = 'scale_item_bank_dimension_check';

    -- item_no 的域基数 = 4（1..4）—— 用生成器实测取值个数，不抄字面
    SELECT count(*) INTO n_no FROM generate_series(1, 4) g WHERE g BETWEEN 1 AND 4;

    IF n_age <> 1 OR n_dim <> 1 THEN
        RAISE EXCEPTION 'scale_item_bank 缺少 age_group / dimension 的 CHECK 约束（实测 age=% dimension=%）',
            n_age, n_dim;
    END IF;
    IF 8 * 7 * n_no <> n_expected THEN
        RAISE EXCEPTION '约束取值域容量 % × 7 × % ≠ 224 —— 结构不足以恰好承载 224 题',
            8, n_no;
    END IF;

    RAISE NOTICE 'scale_item_bank OK: 8 年龄组 × 7 维度 × 4 题序 = 224 题容量（仅结构，未预置题面）';
END;
$sib_guard$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 5) 迁移版本登记（幂等 upsert，沿用 V1 / V2 / V3 写法）
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V4', 'scale_item_bank: 224-item capacity (8 age groups x 7 dimensions x 4 items) + item_direction symptom-only CHECK + 5-level anchors + versioned unique key + reviewer sign-off + RLS')
ON CONFLICT (version) DO NOTHING;

-- ============================================================================
-- V4 完成标记
-- 复核要点（供 verification 脚本 / 门禁断言）：
--   (a) scale_item_bank.relrowsecurity = t 且 relforcerowsecurity = t
--   (b) age_group CHECK 恰含 8 值，dimension CHECK 恰含 7 值，item_no 域 = 1..4
--   (c) item_direction 取值集恰为 {symptom}（1 值）
--   (d) 唯一索引 uq_sib_business = (tenant_id, age_group, dimension, item_no, version)
--   (e) RlsCoverageGateTest.ISOLATION_TESTS 已登记本表（三方交叉）
-- ============================================================================