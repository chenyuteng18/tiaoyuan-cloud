-- ============================================================================
-- V8 · 判定域两阶段拆分库层对齐（C4 周期评估提交 ⇄ F1 判定结论落库）
--
-- 来由：S3 批次收尾时把「契约 C4 + F1」与「V5 已建表结构」逐列对了一遍，
--       判定出两处**结构性缺口**（登记于 README §5.2）：
--
--         契约把两件事拆成**两个 operationId**：
--           C4  POST /customers/{id}/cycle-assessments     ← 周期评估**提交**（每 7 次触发）
--           F1  POST /cycle-assessments/{id}/verdicts      ← 判定**结论**落库
--         而 V5 的 cycle_assessment 表**没有"评估已提交、判定未发生"这一阶段的合法表达位**
--         （F1 的 insertCycleAssessment 是"一次性自建 cycle_id 行 + 落结论"）⇒
--         「提交评估」在数据模型上没有独立落点。
--
--         同一根因还夹逼出第二处（README §5.2 缺口③，本域最尖锐的一处）：
--           verdict.confidence 是 NUMERIC(4,3) NOT NULL，
--           而置信度引擎（VerdictConfidenceEngine）在"测量不可比"时
--           **返回 null 而非 0.000**（红线①：不可判 ≠ 最低档）
--         ⇒ D5「人工复核」这一支**不存在任何可写的 verdict 行**。
--            而"人工复核"本就是 5 个合法分支之一 —— 一个合法分支却写不出记录，
--            这不是设计，是结构缺陷。
--
-- 裁定（2026-09-26 · 本迁移为落地件）：
--   采用「放宽两列可空 + 补 disposition 列 + 补 risk_flag CHECK +
--         F1 判存在性决定写依据还是补结论」方案。
--
--   🛑 缺口①的正解：真正阻塞 C4 的是 cycle_assessment.**verdict** 这一列
--     `cycle_assessment` 共 17 列，逐列核对"提交评估时（判定尚未发生）能否有值"：
--       ┌────────────────────┬──────────┬──────────────────────────────────────┐
--       │ 列                 │ C4 时可填│ 说明                                 │
--       ├────────────────────┼──────────┼──────────────────────────────────────┤
--       │ cycle_id/tenant_id │ ✅       │ 主键与租户                           │
--       │ customer_id        │ ✅       │ 路径参数即客户                       │
--       │ sequence_no        │ ✅       │ 第 N 次评估，提交时已知               │
--       │ as_value           │ ✅ 可空  │ 样本不足时为 null（本就是合法形态）    │
--       │ as_dimensions_json │ ✅       │ 依从四维来自本次作答，可算            │
--       │ metric_snapshot    │ ✅       │ **评估侧**快照（作答事实），可算       │
--       │ gap_days           │ ✅ 可空  │ 应填天数                             │
--       │ **verdict**        │ ❌       │ **判定分支 —— 判定尚未发生，无值**    │
--       │ effect_verdict     │ ✅ 可空  │ 效果候选未定                         │
--       │ adherence_state    │ ✅       │ 依从状态可算                         │
--       │ improvement_rate   │ ✅ 可空  │ 改善率需基线对比，可缺                │
--       │ module_scores      │ ✅       │ 模块分来自本次作答                    │
--       │ threshold_version  │ ✅       │ 阈值版本由当前口径算出                │
--       │ band_trend_note    │ ✅ 可空  │ 手环趋势说明                         │
--       │ created_at/by      │ ✅       │ 提交时刻与操作人                      │
--       └────────────────────┴──────────┴──────────────────────────────────────┘
--     ⇒ **唯一阻塞列是 verdict**。故本迁移只放宽它一列。
--
--   🛑 为什么**不**顺手放宽其余四列（as_dimensions_json / metric_snapshot /
--      module_scores / threshold_version）
--     一度考虑过"把依据四列一起放宽"，但核算后不必要且有害：
--       ① C4 时它们**已经算得出**（上表已逐列核过）⇒ 在 C4 阶段即可满足 NOT NULL；
--       ② 放宽它们会**弱化 PRD §C.1.9 硬约束②③**（依据必须落库 / 阈值版本必须落库）——
--          一旦可空，"一条有结论却没有依据的行"就从"库层不可能"变成"只是没人这么写"，
--          而回放拿到不完整依据**不会报错**。**能不放宽的 NOT NULL 就不放宽**。
--
--   🛑 两阶段的区分点（这是本次改动最容易被误读的一处）
--     阶段一（C4 已提交、判定未发生）：
--         cycle_assessment 有行 **且 verdict IS NULL**
--     阶段二（F1 已落结论）：
--         cycle_assessment 有行 **且 verdict IS NOT NULL**
--     注意区分点<b>不是</b>"有没有 verdict 行"—— 那对 F2 的差集有意义（见下），
--     但对"这一行属于哪个阶段"没有意义，因为 F1 在 C4 之后**不新增 cycle 行**。
--
--     ⚠️ 由此 F2 的差集口径仍成立且更精确：
--        "有依据而无结论" = cycle 有行 且 **无 verdict 行** ——
--        它同时覆盖【C4 已提交待判定】与【D5 挂起】两种形态。
--        （C4→F1 路径下 F1 只补 verdict 行，故差集在 F1 落库后自然闭合。）
--
-- 🛑 处置纪律（与 V6/V7 同口径：只对齐结构，不改语义）：
--   ① **不放宽契约**：C4/F1 的 operationId 与路径都是冻结行，本迁移不动契约；
--   ② **不新建表**：不造"周期评估提交表"—— 那张表会与 cycle_assessment 共用
--      customer_id/sequence_no 语义，形成第二份真相源（本项目反复登记的失效模式）；
--   ③ **不放宽"不可覆盖"**：PRD §C.1.9 硬约束③ 仍然成立 —— 本迁移不新增任何
--      改写路径。C4 是一次 INSERT（依据），F1 是一次 INSERT（结论）；
--      F1 若发现该周期已有结论（verdict 列非空）则**拒绝**，而不是改写它；
--   ④ **不改既有数据**：既有 verdict 行 confidence 全非空，
--      既有 cycle_assessment 行 verdict 全非空 ⇒ 放宽不触及任何历史行。
--
-- 幂等写法（与前序迁移同口径）：ADD COLUMN IF NOT EXISTS / DROP CONSTRAINT IF EXISTS
--   + ADD CONSTRAINT / ALTER COLUMN ... DROP NOT NULL（均系可重复执行语句）。
-- ============================================================================

-- ============================================================================
-- 一、cycle_assessment.verdict 放宽为可空 —— C4「评估提交」获得合法表达位
--
-- 🛑 语义边界（红线）：
--     NULL      = **判定尚未发生**（C4 已提交、F1 未落结论）—— "待判定"
--     '人工复核' = **判定已发生，且系统按 D5 挂起**（F1 已落结论）
--   两者**结构上不同**，不得互相回落。把"未判定"写成"人工复核"会让
--   "系统还没判"静默变成"系统判了、结论是交给人" —— 后者是一条**结论**。
--
-- 🛑 为什么不需要重建 CHECK：V5 的内联 CHECK 是 `verdict IN (五值)`，
--    它不含 NOT NULL 语义；SQL 里 NULL IN (...) 求值为 UNKNOWN ⇒ CHECK 通过。
--    故仅 DROP NOT NULL 即可，CHECK 原样保留、继续挡住"第六个中文字面"。
-- ============================================================================
ALTER TABLE cycle_assessment ALTER COLUMN verdict DROP NOT NULL;

-- ============================================================================
-- 二、verdict.confidence 放宽为可空 —— D5「人工复核」获得合法表达位
--
-- 🛑 语义边界（这一条是红线，不是折中）：
--     null   表示"**不可判**"（测量不可比 / 置信度引擎挂起）
--     0.000  表示"**最低置信**"（算得出，但证据链最弱）
--    两者**结构上不同**，不得互相回落。红线①：不可判时返回 null，不回落 0。
--    范围 CHECK 保留（0–1）；越界仍拒，越界**不得截断**。
--    与上一条同理：原内联 CHECK `confidence >= 0 AND confidence <= 1`
--    对 NULL 求值为 UNKNOWN ⇒ 通过，故仅 DROP NOT NULL。
-- ============================================================================
ALTER TABLE verdict ALTER COLUMN confidence DROP NOT NULL;

-- ============================================================================
-- 三、verdict 补 disposition 列（PRD §C.1.7 L1794 的组合出口）
--
-- 裁定依据（README §5.2 缺口①）：PRD **两处**用到 disposition，值域相同、归属不同 ——
--   L1787  refund.disposition   ← 工单结论码（人协商后填，可随协商推进改变）
--   L1794  verdict.disposition  ← 判定结论的组合出口（系统一次写入，不可覆盖）
-- V5/V6 均未建列 ⇒ 本迁移为 **verdict 侧**补列（L1794 一行）。
--
-- 🛑 为什么不给 refund 也补一列（"两表都建"）
--   两张表的 disposition **写入方、可改写性、可见性全都不同**：
--   前者由门店协商后填写、可随协商推进改变；后者由系统按三 enum 组合一次推出、不可覆盖。
--   若两处都建，同一个名字会有两个真相源 —— 正是本项目反复登记的失效模式。
--   PRD L1794 才是"组合出口"的权威表述，故只补这一处。
--
-- 🛑 可空性：本列**可空** —— 挂起态（D5）的处置是"继续原方案"这一收敛值，
--    它进依据快照；但若未来有分支不产出处置，可空比硬填一个假值安全。
--    值域由 CHECK 钉死为 PRD 06.§七 的 5 类处理结论逐字。
-- ============================================================================
ALTER TABLE verdict ADD COLUMN IF NOT EXISTS disposition VARCHAR(32);

ALTER TABLE verdict DROP CONSTRAINT IF EXISTS verdict_disposition_values;
ALTER TABLE verdict ADD CONSTRAINT verdict_disposition_values CHECK (
    disposition IS NULL OR disposition IN (
        '继续原方案', '调整后继续', '转基础服务', '退款终止', '建议就医')
);

-- ============================================================================
-- 四、verdict 补 risk_flag 的 CHECK（与同表另三个枚举列对齐）
--
-- 现状（README §5.2 缺口②）：V5 里 branch / effect_verdict / adherence_state
-- 三个枚举列**都有 CHECK**，唯独 risk_flag 没有（且可空）⇒ 唯一防线在应用层
-- （RiskFlag.parse fail-closed，未知字面必抛、不回落 NONE）。
--
-- 为什么必须补：risk_flag 一旦漂移或回落，会让 **D4（全面评估）该触发而不触发** ——
-- 一次安全信号以"等人工"的形态被静默搁置，正是 PRD §7.3 定调句要防的事。
-- 补 CHECK 后应用层防线**不变**（纵深防御两层），库层成为第二道闸。
--
-- 🛑 可空性保持不变：null 表示"**未录入**"（未录入 ⇒ 路由落 D5），
--    这是一个**合法的业务状态**，不得用 CHECK 排除它。
-- ============================================================================
ALTER TABLE verdict DROP CONSTRAINT IF EXISTS verdict_risk_flag_values;
ALTER TABLE verdict ADD CONSTRAINT verdict_risk_flag_values CHECK (
    risk_flag IS NULL OR risk_flag IN ('无', '高危', '新发', '同病')
);

-- ============================================================================
-- 五、自证块（迁移期断言：既有数据必须仍全部合法）
--
-- 与 V5/V6/V7 的自证同口径：把"我以为的"变成"库确认的"。
-- 若任一条失败，本迁移应中断 —— 那意味着存在不合值域的历史行，
-- 须先人工核查（而不是让 CHECK 静静拒绝后续写入）。
-- ============================================================================

DO $$
DECLARE
    out_of_range_conf INT;
    bad_disposition   INT;
    bad_risk          INT;
    null_conf_total   INT;
    null_cycle_verdict INT;
BEGIN
    -- ① confidence 范围（null 合法，越界不合法）—— 放宽后本条成为唯一范围防线
    SELECT count(*) INTO out_of_range_conf
      FROM verdict
     WHERE confidence IS NOT NULL AND (confidence < 0 OR confidence > 1);
    IF out_of_range_conf > 0 THEN
        RAISE EXCEPTION 'V8 自证失败：verdict 存在 % 行 confidence 越界', out_of_range_conf;
    END IF;

    -- ② disposition 值域（本迁移新增列，既有行应全为 null）
    SELECT count(*) INTO bad_disposition
      FROM verdict
     WHERE disposition IS NOT NULL
       AND disposition NOT IN ('继续原方案','调整后继续','转基础服务','退款终止','建议就医');
    IF bad_disposition > 0 THEN
        RAISE EXCEPTION 'V8 自证失败：verdict 存在 % 行 disposition 不在 5 值枚举内', bad_disposition;
    END IF;

    -- ③ risk_flag 值域
    SELECT count(*) INTO bad_risk
      FROM verdict
     WHERE risk_flag IS NOT NULL AND risk_flag NOT IN ('无','高危','新发','同病');
    IF bad_risk > 0 THEN
        RAISE EXCEPTION 'V8 自证失败：verdict 存在 % 行 risk_flag 不在 4 值枚举内', bad_risk;
    END IF;

    -- ④ 放宽 confidence 的**影响面如实登记**
    SELECT count(*) INTO null_conf_total FROM verdict WHERE confidence IS NULL;
    RAISE NOTICE 'V8 自证通过：confidence 范围 0 违规 · disposition 0 违规 · risk_flag 0 违规；'
                 '当前 confidence IS NULL 的行数 = %（本迁移前该形态在库层不可能，故应为 0）',
                 null_conf_total;

    -- ⑤ 放宽 cycle_assessment.verdict 的影响面如实登记（同理应为 0）
    SELECT count(*) INTO null_cycle_verdict FROM cycle_assessment WHERE verdict IS NULL;
    RAISE NOTICE 'V8 自证：当前 cycle_assessment.verdict IS NULL 的行数 = %'
                 '（本迁移前该形态在库层不可能，故应为 0；此后 C4 提交会合法产出该形态）',
                 null_cycle_verdict;
END $$;

-- ============================================================================
-- 六、回滚说明
--
-- 回滚须**先**处理数据再删约束（顺序反了会被 CHECK/NOT NULL 拒绝）：
--   ① 先处置两处新增的可空形态：
--      · verdict.confidence IS NULL 的行（D5 挂起态）
--      · cycle_assessment.verdict IS NULL 的行（C4 已提交、F1 未落结论）
--      这两类行在回滚后**无法存在**（将恢复 NOT NULL）——
--      须先导出，或补齐取值。🛑 **不得自动补**：
--        confidence 补 0.000 会把"不可判"写成"最低置信"；
--        cycle verdict 补 '人工复核' 会把"未判定"写成"已判为挂起"。
--        两者都是语义破坏。故**只导出、不自动补**。
--   ② ALTER TABLE verdict ALTER COLUMN confidence SET NOT NULL；
--      ALTER TABLE cycle_assessment ALTER COLUMN verdict SET NOT NULL；
--   ③ DROP CONSTRAINT verdict_disposition_values / verdict_risk_flag_values；
--      DROP COLUMN disposition。
--   ⚠️ 回滚会**丢失**两类语义（"不可判"与"未判定"）。属破坏性操作，
--      生产回滚前须先导出。故不提供 down 脚本，由 DBA 按需手工执行。
-- ============================================================================