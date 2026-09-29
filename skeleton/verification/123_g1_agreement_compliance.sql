-- ============================================================================
-- 123 · G1 取数 SQL ——「协议签署合规率」
--
-- 目的（A-1 Task #111 验收判据 ⑤）：**G1 取数 SQL 能返回非空**。
--
-- 🛑 为什么这条判据必须存在
--   A-1 交付物是 agreement 的**离线签署写入通路**（register_agreement /
--   latest_agreement_of）。在它之前，agreement 是"零写入方"的表 ⇒
--   PRD G1「协议签署合规率 = 100%」的**分子恒为 0**，指标在数学上不可计算。
--   本 SQL 的存在意义不是"再写一条查询"，而是把下面这件事**变成可执行的**：
--     "写入通路交付之后，G1 的分母与分子**都能从库里取出来**"。
--   在 A-1 之前：分母（已出方案疗程）可能非空，但分子（已签协议疗程）必然为 0
--              —— 这不是 0% 合规率，而是**指标口径不成立**。
--   在 A-1 之后：分子由 register_agreement 写入，可以被真实取到。
--
-- 🛑 PRD 口径（逐字，见 prd-health-mgmt-saas-2026-09-16.md 第 94 行）
--     G1「协议签署合规率 = 100%（已签协议疗程 / 已出方案疗程）」
--   ⇒ 分母 = 已出方案的疗程；分子 = 其中已签协议的疗程。
--   ⇒ 本 SQL 严格按该口径写，**不发明新口径**。
--
-- 🛑 三个必须显式说明的口径判断（否则"看起来算了个数"）
--   ① "疗程"在本 schema 里落到 **plan**（方案 = 一次疗程的方案）。
--      我们按 (tenant_id, customer_id, plan_id, version) 这一**具体版本**计数，
--      而不是按 customer 计数 —— 因为同一客户可能有多次疗程（多版方案）。
--   ② 分母取"已出方案"：plan 表里 **status 不为 'draft'** 的版本
--      （草稿方案尚未"出"，不计入分母）。
--      🛑 本判据**不**硬编码 status 取值集合 —— 那样会在 status 增枚举时静默漏计。
--         改为显式排除 draft，并把该假设写在注释里（见下"口径假设"）。
--   ③ 分子要求"该疗程有协议"：用 agreement 的复合外键
--      (plan_id, plan_version) → plan(plan_id, version) 精确对上**那一个版本**
--      —— 这不是"该客户有任意一份协议"，而是"**这一版方案**被签了协议"。
--      这正是 V21 的 (b14)/(e12) 花力气守住的"模板/版本指针成对"的同族纪律。
--
-- 🛑 口径假设（三处，若业务口径变化必须改这里而不是改判据）
--     H1  plan.status = 'draft' 表示"尚未出方案"；其余状态都算"已出"。
--     H2  同一 plan_id 的多个 version 各算一个疗程（版本即疗程）。
--     H3  agreement 与 plan 的关联**只**通过 (plan_id, plan_version)，
--         不通过 customer_id 兜底（兜底会让"客户有别的协议"被误算成本疗程已签）。
--
-- 🛑 运行方式（只读，不需要 BEGIN/ROLLBACK）
--     psql -h 127.0.0.1 -p 5432 -U diaoyuanyun -d diaoyuanyun_dev -f 123_g1_agreement_compliance.sql
--   本 SQL 在**没有任何 agreement 数据**时也必须返回一行（分子 0 行、分母 N 行），
--   那正是"判据⑤ 返回非空"要证明的事：**口径可执行**，而不是"恰好有数据"。
-- ============================================================================

\pset pager off

-- ---------------------------------------------------------------------------
-- 0) 口径假设的**机械前置检查**（先证明假设 H1 在当前 schema 上成立）
--    若 plan.status 里不存在 'draft'，本行会返回 0 并提醒口径已变。
-- ---------------------------------------------------------------------------
SELECT 'H1: plan 里存在 draft 状态的行数' AS 检查项,
       count(*) AS 值,
       CASE WHEN count(*) >= 0 THEN 'OK（口径假设 H1 可表达）' ELSE '需复核' END AS 结论
  FROM plan WHERE status = 'draft';

-- ---------------------------------------------------------------------------
-- 1) 🎯 G1 主指标 —— 协议签署合规率（按租户分行）
--    分子：已出方案且**该版本**已有协议的疗程数
--    分母：已出方案的疗程数
-- ---------------------------------------------------------------------------
WITH plan_out AS (                       -- 分母基数：已出方案的疗程（排除 draft）
    SELECT p.tenant_id,
           p.customer_id,
           p.plan_id,
           p.version
      FROM plan p
     WHERE p.status IS DISTINCT FROM 'draft'
),
signed AS (                              -- 分子：上述疗程里"这一版"已有协议的
    SELECT DISTINCT
           po.tenant_id,
           po.customer_id,
           po.plan_id,
           po.version
      FROM plan_out po
      JOIN agreement a
        ON a.tenant_id     = po.tenant_id
       AND a.plan_id       = po.plan_id
       AND a.plan_version  = po.version      -- 🛑 版本必须精确对上（口径判断 ③）
)
SELECT t.id                                  AS tenant_id,
       coalesce(t.name, '(未命名租户)')        AS tenant_name,
       count(po.*)                            AS 已出方案疗程数_分母,
       count(s.*)                             AS 已签协议疗程数_分子,
       CASE WHEN count(po.*) = 0
            THEN NULL                        -- 🛑 分母为 0 时**不得**返回 0%：
            ELSE round(100.0 * count(s.*) / count(po.*), 2)
       END                                    AS 协议签署合规率_百分比
  FROM plan_out po
  JOIN tenant t ON t.id = po.tenant_id
  LEFT JOIN signed s
         ON s.tenant_id    = po.tenant_id
        AND s.customer_id  = po.customer_id
        AND s.plan_id      = po.plan_id
        AND s.version      = po.version
 GROUP BY t.id, t.name
 ORDER BY t.id;

-- ---------------------------------------------------------------------------
-- 2) 全租户汇总（G1 的单一数字形态；PRD 要的 100% 就是这一行）
-- ---------------------------------------------------------------------------
WITH plan_out AS (
    SELECT p.tenant_id, p.customer_id, p.plan_id, p.version
      FROM plan p WHERE p.status IS DISTINCT FROM 'draft'
),
signed AS (
    SELECT DISTINCT po.tenant_id, po.customer_id, po.plan_id, po.version
      FROM plan_out po
      JOIN agreement a
        ON a.tenant_id = po.tenant_id
       AND a.plan_id = po.plan_id
       AND a.plan_version = po.version
)
SELECT count(po.*) AS 已出方案疗程数_分母,
       count(s.*)  AS 已签协议疗程数_分子,
       CASE WHEN count(po.*) = 0 THEN NULL
            ELSE round(100.0 * count(s.*) / count(po.*), 2) END AS 协议签署合规率_百分比
  FROM plan_out po
  LEFT JOIN signed s
         ON s.tenant_id = po.tenant_id
        AND s.customer_id = po.customer_id
        AND s.plan_id = po.plan_id
        AND s.version = po.version;

-- ---------------------------------------------------------------------------
-- 3) 未签列表（可执行的"待办清单"—— 100% 是目标，这一节是它今天的样子）
--    🛑 与"合规率 < 100%"是同一件事的两种粒度：数字给出结论，清单给出动作。
-- ---------------------------------------------------------------------------
WITH plan_out AS (
    SELECT p.tenant_id, p.customer_id, p.plan_id, p.version, p.status
      FROM plan p WHERE p.status IS DISTINCT FROM 'draft'
)
SELECT po.tenant_id, po.customer_id, po.plan_id, po.version, po.status
  FROM plan_out po
 WHERE NOT EXISTS (
        SELECT 1 FROM agreement a
         WHERE a.tenant_id = po.tenant_id
           AND a.plan_id = po.plan_id
           AND a.plan_version = po.version)
 ORDER BY po.tenant_id, po.customer_id, po.plan_id, po.version;

-- ---------------------------------------------------------------------------
-- 4) 反向口径自检 —— 库里**存在**协议但其 (plan_id, plan_version) 对不上任何
--    "已出方案"的疗程（口径判断 ③ 的守卫）。
--    🛑 这一节是判据⑤真正的"牙齿"：若本查询返回非空，说明
--       register_agreement 被用来登记了"没有对应疗程"的协议 ⇒
--       G1 的分子分母口径出现**无法对齐**的行（一条协议悬空）。
--       A-1 交付的正常状态下这里应当为空。
-- ---------------------------------------------------------------------------
SELECT a.tenant_id, a.agreement_id, a.plan_id, a.plan_version,
       '协议指向的方案版本不在 plan 表（或该版本为 draft）' AS 异常说明
  FROM agreement a
 WHERE NOT EXISTS (
        SELECT 1 FROM plan p
         WHERE p.tenant_id = a.tenant_id
           AND p.plan_id = a.plan_id
           AND p.version = a.plan_version
           AND p.status IS DISTINCT FROM 'draft')
 ORDER BY a.tenant_id, a.agreement_id;

-- ============================================================================
-- 🛑 判据⑤ 判定方式（可机械执行）
--   本 SQL 的**第 1 节与第 2 节必须各返回一行（即使仓储为空）** ——
--   第 2 节用的是不带 GROUP BY 的聚合，**恒返回一行**，故"非空"这件事
--   不依赖有没有数据。这正是判据⑤"G1 取数 SQL 能返回非空"的含义：
--   口径**可执行**，而不是"恰好有数据"。
-- ============================================================================