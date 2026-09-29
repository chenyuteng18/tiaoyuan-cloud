-- ============================================================================
-- V20 迁移: 结案归档通路（case_archive 结案归档台账的写入原语）
--
-- 【这个迁移为什么存在 —— 与 B-10 / B-11 / B-12 同型，但【后果的性质不同】】
--
--   `ProvisioningBoundaryGateTest` 把 `case_archive` 登记在「未开通账」里。
--   前三次边界移动（tenant/region/store/staff ← B-7 · band ← B-10 ·
--   device ← B-11 · scale ← B-12）修的都是"某张表没写入方 ⇒ 某条链在规格级不可用"。
--   这一次的后果**更重一层**：
--
--     PRD P0-14 逐字写「四种结局全部强制归档」；
--     PRD P0-25 逐字写「实现 C.3 全部 14 条规则 · 硬阻断规则缺项 → 后端返回 403
--                       且给出缺失项名称 · 警告规则仅记缺口 · 手环类缺项不得反转为阻断」；
--     PRD C.1.7 逐字写「06.§八 归档 6 项 → case_archive.archive_checklist |
--                       map<6bool> | 是 | **缺项→403 阻断结案**」。
--
--   而 `case_archive` 在生产代码里【零写入方】、真库【零行】
--   （实测：count(*) = 0，探针 `_work/v20_precond_probe.sql`）。
--   ⇒ 后果不是"某条链 23503"，而是**「归档」这个动作无法发生**：
--     退款工单走到 终止 之后没有任何合法路径把它收口成 归档，
--     于是「四种结局全部强制归档」这句话在系统里**没有对应的实现**。
--
--   【本仓已把这件事逐字写在代码里 —— 两份证据，都不是我的推断】
--     ① `RefundWorkOrderService` 类注释的"本类不做的事"表里逐字写着：
--          「归档落 case_archive | 待落地（S2-5 之后）| 归档要同时落六项清单与客户签字，属独立链路」
--     ② `InMemoryRefundWorkOrderPort.markArchivedForTest` 的注释逐字写着：
--          「生产侧的归档动作（落 case_archive + 结案清单校验）尚未落地（见
--            RefundWorkOrderService 的"本类不做的事"表）。于是"归档后只读"这条性质
--            **在测试里无从构造** —— 而它是"全部强制归档"的下半句。
--            本方法站在那条未来路径的位置上」
--     ⇒ 即：**"归档后只读"这条性质目前只在测试替身里成立**。
--       生产侧连"把工单置为归档态"的能力都还没有，更不用说要落 6 项清单。
--       本迁移提供的是**下半句的前半**：让 case_archive 有写入方，
--       从而让"归档动作"在库层成为可能。
--
--   【🛑🛑 本迁移【不】负责的那一半，必须逐字说清（否则下一个人会以为做完了）】
--     本迁移只提供【库层原语】。从"原语存在"到"契约上的归档动作可用"之间还差：
--       · 服务层的归档编排（同一次事务里写 case_archive + 把 refund.outcome 置 '归档'）——
--         🛑 它被一条硬缺口阻塞：**refund 与 case_archive 之间没有任何关联列**
--            （实测：case_archive 只有 3 条约束 —— case_archive_pkey 单列、
--              case_archive_tenant_id_fkey 单列、case_archive_customer_id_fkey 复合；
--              没有 refund_id、没有 archive 反向列）。见下方【待裁登记 · 第 3 条】。
--       · C.3 里那些需要**跨表读取**才能判定的规则（ARC-01~06 / ARC-14）——
--         它们要读 refund / consent / baseline_assessment / plan / visit /
--         cycle_assessment，属服务层，不属本原语。见【待裁登记 · 第 1 条】。
--       · 契约层：**没有任何端点**可以触发归档（见下一节）。
--
-- 【为什么归档通路【不】是 HTTP 端点（有意形态，不是没做完）】
--   逐条核对契约 `openapi-v1.0.0.yaml` 的全部 40 个 path：
--     · 没有任何 path 是"创建结案归档"；
--     · 唯一的结案相关 path 是 D6 之后的退款系列，而它们操作的是**退款工单**
--       （refund 的结局推进），不是 `case_archive` 这个台账；
--     · 归档是"工单收口"的**内部一步**，不是一次独立的客户/门店动作。
--   ⇒ 与 B-7（组织开通）/ B-10（手环绑定）/ B-11（设备建档）/ B-12（量表建档）
--     完全同型：**契约化决策**。给归档加对外端点属契约 MAJOR 变更，
--     且要先回答契约回答不了的问题：「谁有权归档一张工单」？
--     （能读退款 ≠ 有权结案。结案牵动「协议签署合规率」类指标与举证链。）
--   ⇒ 故本迁移提供【数据库层原语】：一个幂等的 plpgsql 写入函数 + 一个读原语。
--     可被运维 psql 直接调用，也可被应用内**不对外暴露**的 CaseArchiveService 调用
--     （与 B-7 / B-10 / B-11 / B-12 同款）。
--
-- 【🛑 核心机制一：归档必须【自己】建立 RLS 上下文】
--   case_archive（V5 §2.22）是 ENABLE + FORCE ROW LEVEL SECURITY，策略 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 WITH CHECK 为 false ⇒ INSERT **抛错**（不是静默零行 ——
--   静默零行发生在 SELECT 的 USING 上，这个区别很容易记反）。
--   故本函数把「建立上下文」+「自证上下文已生效」绑在读写之前。
--
-- 【🛑🛑 核心机制二（与 B-11 的 device 同型）：`ON CONFLICT (archive_id)`
--    在【跨租户撞号】时会【静默 DO NOTHING】】
--
--   起因：`case_archive_pkey` 是 **单列** 主键 `PRIMARY KEY (archive_id)`
--   （实测 pg_constraint：`case_archive_pkey | p | PRIMARY KEY (archive_id)`）。
--   表上**没有** `(tenant_id, archive_id)` 唯一载体 —— 与 device 同型。
--   ⇒ 语义上「同一个 archive_id 一生只属于一个租户」是**既有 schema 的既定事实**。
--
--   `INSERT ... ON CONFLICT (archive_id) DO NOTHING` 的推断目标是那个**全局**主键，
--   故当 archive_id 已被【别的租户】占用时：
--     · 本租户上下文里 SELECT 看不见那一行（RLS 的 USING 把它挡在外面）；
--     · 但主键冲突仍然发生 ⇒ PG 走 DO NOTHING ⇒ **ROW_COUNT = 0**；
--     · 且它**不报错**、**不改任何行**。
--
--   【与本迁移的后果差异 —— 这一点比 device 那次更隐蔽】
--     device 那次，"返回值在撒谎"的后果可以在下游被看见（23503）。
--     而归档是**收口动作**：它是链的**末端**，下游没有第二步去证伪它。
--     ⇒ 若照抄"ROW_COUNT=0 ⇒ ALREADY_EXISTS"，对本租户的回答会是
--       「这次归档已存在」—— 而库里在本租户下一行都没有。
--       调用方据此把 refund.outcome 置成 '归档' ⇒
--       **一张退款工单被标记为"已归档"，而它的归档档案不存在**。
--       这个缺口不会报错、不会 23503、不会被任何下游抓住 ——
--       它只在**举证时**暴露：审计要看归档清单，而清单查不到。
--     🛑 这正是本仓"假绿比假红危险得多"的同一族形态。
--
--   【怎么修：用一个**无窗口**的判定，而不是"先读后写"】
--     判定链（三段，全部来自库层的原子事实）：
--       ① INSERT ... ON CONFLICT (archive_id) DO NOTHING
--          · ROW_COUNT = 1 ⇒ 本次真的建出来了 ⇒ 'CREATED'
--       ② ROW_COUNT = 0 ⇒ 全局已存在这一行。**它在谁名下？**
--          在已自证的**本租户上下文**里 `SELECT count(*) FROM case_archive WHERE archive_id = ...`
--          · 看得见（=1）⇒ 是本租户的 ⇒ 'ALREADY_EXISTS'（幂等，如实）
--          · 看不见（=0）⇒ 🛑 插不进去却又看不见 ⇒ **它在别的租户名下** ⇒ RAISE
--     为什么这**没有**"先读后写"的并发窗口：
--       "存在"这个前提不是来自我的读，而是来自**主键冲突**这个库层原子事实 ——
--       能冲突就证明它确实存在。我的读只用来回答"它是不是我的"，
--       而"读不到"这件事在 RLS 下是**稳定**的（别人的行永远读不到，不是时序问题）。
--     🛑 与 V17 `bind_band` 的对比仍然值得写清（这是本仓第 2 次遇到这一族）：
--       那里用 `ON CONFLICT (tenant_id, customer_id)` —— 推断目标是**部分**唯一索引，
--       **含 tenant_id** ⇒ 天然不会跨租户误判；这里推断目标是**全局**主键
--       ⇒ 必须自己补上这一层判定。两处差别**不在风格，在推断目标里有没有租户维度**。
--     🛑 V19（scale）还提供了第三种形态，也一并记住：
--       那里连"跨租户撞号"都不是主要问题，主要问题是 `scale_version` 是**死维度**
--       （pkey 单列 ⇒ 同一 scale_id 只可能一行 ⇒ UNIQUE(scale_id, scale_version)
--        永远不可能被违反）⇒ V19 补的是"版本不同 ⇒ RAISE（不可覆盖）"。
--       三次不同的推断目标，三种不同的补法 —— 这不是可以照抄的模板。
--
-- 【为什么跨租户撞号用 RAISE 而不用返回值】
--   与 V18 逐字同款理由：同一份归档档案不可能同时属于两个租户。
--   它是**数据冲突**，需要人工介入（要么 archive_id 传错了，要么档案被错误调拨）。
--   故用 RAISE，让"这件事发生了"无法被调用方当成一个可以继续流程的返回值。
--   ⇒ 附带结论（值得逐字写清，免得下一个人以为漏了三态）：
--     **register_case_archive 是两态**（CREATED / ALREADY_EXISTS）+ 若干类异常
--     （入参非法、客户不存在、清单缺项、签名缺项、跨租户撞号）。
--
-- ============================================================================
-- 【🛑🛑🛑 核心机制三 —— 本迁移最重要的一处，且它是【合规红线】：
--    PRD 的「6 项缺项→403」与 C.3 的 ARC 规则【粒度不一致】，
--    而"手环类缺项不得反转为阻断"是一条【必须】遵守的红线】
-- ============================================================================
--
--   PRD 里关于这 6 项有两处口径，而它们**说的不是同一件事**：
--
--     ① C.1.7（字段映射表）：`case_archive.archive_checklist | map<6bool> | 是 |
--        **缺项→403 阻断结案**`  —— 这是**一个笼统的汇总口径**；
--     ② C.3（归档完整性校验规则）：14 条 ARC 规则，**逐条**给出"硬阻断 / 警告"：
--          ARC-01 退款原因未记录        → 硬阻断（后端 403）   ← 对应 checklist「原因已记录」
--          ARC-09 基线与当前复评未对照   → 硬阻断               ← 对应「基线复评已对照」
--          ARC-10 挽留过程未记录        → 硬阻断               ← 对应「挽留已记录」
--          ARC-12 最终处理结论未经负责人签字 → 硬阻断            ← 对应「负责人已签字」
--          ARC-13 客户档案/方案/执行/评估未归档 → 硬阻断         ← 对应「档案方案执行评估已归档」
--          ARC-11 手环数据未按"参考之一"口径记录 → **警告（不阻断）** ← 对应「手环按"参考之一"记录」
--          （ARC-07 未佩戴 → 标"未佩戴"、**不阻断、不作不利依据**；
--            ARC-08 已同意佩戴却无记录 → **警告（不阻断）** + 记数据缺口）
--
--   ⇒ 6 项里有 **5 项硬阻断 + 1 项（手环）警告不阻断**。
--     若本函数把 6 项一律要求 true，就**违反了 PRD P0-25 的逐字要求**与
--     §四「三条不得触碰」之 ③：
--        「**任何"手环缺项反转为阻断"的写法一律违规**（P0-25 + ARC-07/08/11）」
--     并同时违反 §5.3 一节的「🛑 三条不得触碰」③。
--
--   ⇒ 本函数的处置（口径来源 = C.3 的逐条细则 + P0-25 的验收项，**优先于** C.1.7 的汇总句）：
--       · 5 项**硬门禁**：键必须存在、值必须是 JSON 布尔、且必须为 true；否则 RAISE（缺项）。
--         它们 = reason_recorded / baseline_review_compared / retention_recorded /
--                owner_signed / archive_plan_exec_archived
--       · 1 项**警告门禁**：handband_recorded_as_reference ——
--         键**可以缺失**、值**可以为 false**，两者都**不阻断**。
--         但若键存在而**不是 JSON 布尔**（例如写成字符串 "true"），仍 RAISE ——
--         那是**口径错误**（"是否记录了参考之一口径"这件事没被表达成一个是/否），
--         而不是"手环缺口"，故它与"不阻断"不冲突。
--   🛑 出一条可迁移的教训：
--      **当一个字段同时被"汇总口径"与"逐条细则"描述，且两者给出不同的严格度时，
--        必须（a）判断哪一份是细则、（b）判断哪一份被验收项逐字引用、
--        （c）判断哪一份触及合规红线 —— 然后显式选一个，并把冲突登记出来。
--        绝不能"取两者更严的那个"**：在这个例子里"取更严"恰好就是违规。
--
-- ============================================================================
-- 【🛑 核心机制四：为什么【不】在库层加 CHECK 约束（一条被保护下来的既有门禁）】
--
--   上面的 6 项校验**全部写在函数里**，而不是写成表上的 CHECK。
--   这不是风格选择，有一条**机械可判定的**理由：
--
--     `RlsV5EntityIsolationTest.cross_tenant_insert_is_rejected_by_with_check`
--     用一条**内容为空**的探针验证 case_archive 的 WITH CHECK：
--         INSERT INTO case_archive (archive_id, tenant_id, customer_id,
--                                   archive_checklist, staff_signs)
--         VALUES (gen_random_uuid(), <租户B>, <租户B客户>, '{}', '{}')
--     并断言 SQLSTATE = **42501**（RLS 的 WITH CHECK 拒绝）。
--
--   🛑 若把"6 项齐备"写成 CHECK，这条 INSERT 会先撞 CHECK ⇒ SQLSTATE = **23514**
--      ⇒ 那条门禁**报红**，而它报出的是**完全归因错误**的红：
--      它说的是"WITH CHECK 没生效"，真因是"表上多了一条 CHECK"。
--      而修它的直觉动作会是"去放宽 RLS 判据"—— 那会真的毁掉一条有效门禁。
--
--   ⇒ 故本迁移把校验放在函数内，并在第 0 节前置自检里**机械断言表上 CHECK 数 = 0**。
--     那个断言的作用不是"记录现状"，而是**让"想加 CHECK 的人"必须先显式改这条断言** ——
--     于是那次改动必然被看见、必然被解释，而不是静默地把一条既有门禁变成假红。
--   🛑 同时这也解释了"为什么库层校验不是越严越好"：
--      库层约束是**全局**的（对所有写入方生效，包括探针与运维修复），
--      而这里的 6 项校验是**归档动作**的业务门禁（只该对"归档"这一件事生效）。
--      把业务门禁下沉成表约束，等于对"一切写入"施加同一个门禁 ——
--      包括那些**本意就不是归档**的写入（夹具、探针、未来的数据修复）。
--      ⇒ 判据的适用范围 = 它的锚点范围。这里的锚点是"归档动作"。
--
-- 【为什么没有"取消归档"原语（与 V18 的 retire_device 对照）】
--   V18 有 register_device + retire_device；V19 有 register_scale + deprecate_scale。
--   本迁移只有 register_case_archive + 一个读原语 —— 这是**有意的**，理由两条：
--     ① 归档是**终态**。"取消归档"不是本域的合法动作：
--        PRD P0-14「四种结局全部强制归档」把归档定义为**收口**，收口不可撤销。
--     ② 🛑 更精确地说：**"未归档"这个语义不由 case_archive 承担，而由
--        refund.outcome 承担**（RefundOutcome 的注释逐字写：
--        「归档 是结案状态位（case_archive 落地、归档后只读）；
--          它不是第四种业务结局，是状态位」）。
--        ⇒ 若这里提供一个"删除归档行"的原语，就等于给了第二条"把工单从已归档改回未归档"的路
--          —— 而那条路会绕过 refund 侧的状态机。
--
-- 【🛑 "归档后只读"在本迁移里只做了一半，必须说清是哪一半】
--   本迁移**不提供**任何 UPDATE / DELETE case_archive 的语句
--   （自证里对此有静态断言 (b10)/(b11)）。这是"只读"的**一半**：**没有改写通路**。
--   另一半（库层**禁止**他人改写）本迁移**有意不做**：
--     · 加一条"禁止 UPDATE"的触发器，会让将来合法的迁移/数据修复
--       （例如补 effect_confirm_pdf 的文件引用）必须**禁用触发器**才能做 ——
--       而那正是"绕过机制"的口子，比不加更危险；
--     · 更诚实的说法：PRD 的"归档后只读"是**相对退款工单**说的
--       （工单一旦归档，不得再被 updateOutcome 推进 —— 那条已由
--         `RefundWorkOrderLedger.UPDATE_OUTCOME_SQL` 的 `AND outcome <> '归档'` 实现）。
--       case_archive 行本身是**证据快照**，它天然不该被改，但那是**契约纪律**，
--       不是本迁移可以用一条触发器替代的东西。
--   ⇒ 故本迁移逐字登记这条边界：**"没有改写通路"已实现；"禁止改写"未实现。**
--
-- 【🛑 为什么不在函数里冻结 metrics_trend / final_conclusion 的结构】
--   两者都是 JSONB / VARCHAR，且它们的**内容结构由上游评估链决定**，
--   不是本域能冻结的。与 V18 对 model 的处置**结论相同、理由不同**：
--     · V18 的 model：取值集已由 CHECK 冻结 ⇒ 再写一遍是重复定义；
--     · 本处的 metrics_trend / final_conclusion：结构**尚未冻结**（上游未定）
--       ⇒ 冻结一个明确未定的结构属代拍口径。
--   两种理由都指向"不要在函数里再写一遍"，但它们不是同一条理由 —— 故都写出。
--
-- 【幂等口径（与 V1~V19 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 函数天然支持 replace）
--   ② 写入侧 ON CONFLICT DO NOTHING + 返回两态（+ 若干 fail-closed 异常）
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（自证探针在一个子事务里跑，结束整体撤销）
--
-- 【🛑 本文件语句顺序不可调换：授权 → 登记 → 自证 → 完成】
--   自证 (d) 断言 schema_migration 里 V20 登记行数 = 1，故【登记必须排在自证之前】。
--   与 V15 / V17 / V18 / V19 同款（V14 把登记放在末尾是因为它的自证不读登记表）。
--   ⚠️ 这是本仓**第四次**在文件头写这句话。它与 description 长度上限一样，
--      属于"文件内注释无法自保"的纪律 —— 故 119 反向验证已把"把登记挪到自证之后"
--      做成了**受控注入**（必须报 (d)）。本文件沿用同一形态。
--
-- ============================================================================
-- 【待裁登记（🛑 三条，均不得静默选一个 —— 本仓纪律：不确定就显式登记）】
--
--   第 1 条 · `archive_checklist`(6 项) 与 C.3(14 条) 的**粒度不一致**
--     14 条里有 8 条（ARC-01~06 / ARC-14）**需要跨表读取**才能判定：
--       ARC-01 读 refund · ARC-02 读 intake_profile/consent · ARC-03 读 baseline_assessment
--       ARC-04 读 plan · ARC-05 读 consent · ARC-06 读 visit/cycle_assessment
--       ARC-14 读 refund.evidence_checklist
--     而 checklist 只有 6 项。⇒ 两条候选口径，**未定**：
--       (a) checklist 是 14 条的**子集投影**（只落库层可判定的 5 硬 + 1 警告），
--           其余 8 条由服务层在调用本原语**之前**校验；
--       (b) checklist 是**独立的一份 6 项自证清单**，与 14 条并行存在。
--     🛑 本迁移的处置：**按 (a) 实现**（因为 C.1.7 把 checklist 定义为 map<6bool>，
--       它承载不了 14 条），但**把 (b) 登记为未排除的候选**。
--       若最终选 (b)，本函数的硬门禁集合需要重新推导（不是把 14 条塞进 6 键）。
--     🛑 另一处更硬的事实：**`refund.evidence_checklist` 在库层不存在**
--       （实测：refund 表 20 列里没有任何 evidence_checklist；V6 也没有加它）。
--       而 C.1.7 逐字要求「06.§三 6 项证据链复核 → refund.evidence_checklist |
--       json{01表,02表,03表,04表,05表,手环} 每项 enum{完整,缺失} | 是 |
--       **缺项即阻断立案**」 ⇒ **ARC-14 目前没有库层载体**，它是一条
--       "写在 PRD 里、代码里不存在"的规则。⇒ 本迁移**不**顺手补它
--       （补列属 refund 域的变更，且要先裁定"立案阻断"与"结案阻断"是不是同一道门）。
--
--   第 2 条 · JSONB 键的**命名语言**（中文 vs 英文 snake_case）**未冻结**
--     PRD C.1.7 用**中文键名**描述（`map<6bool>：原因已记录/…`、
--     `json{经办人,经络师,门店负责人,日期}`），而本仓既有 JSONB 范式
--     （`ConsentAuthScope` 的 `auth_scope_json`）用的是 **snake_case 英文 code**，
--     且它逐字写明"未登记即抛，绝不回落"。
--     🛑 本迁移的处置：**采用英文 snake_case**（与本仓既有范式一致、与
--       `code` 类入参风格一致、且对"未知键必须抛"这一条更可机械判定），
--       并把这一选择**登记为待裁** —— 因为契约里没有任何地方冻结过这 6 个键的字面。
--     ⇒ 若将来裁定用中文键名，**改的必须是这一处**（函数内的两个常量数组），
--       而不是散落各处的字符串。这也是本迁移把键集抽成 ARRAY 常量的理由之一。
--
--   第 3 条 · **refund ↔ case_archive 没有任何关联列**（本域最硬的一条缺口）
--     实测：case_archive 的 3 条约束里既没有 refund_id，也没有反向列；
--     refund 的 20 列里也没有 archive_id。⇒ "哪张归档档案对应哪张退款工单"
--     **在库层无法回答**。三条候选路径，**未定**：
--       (a) case_archive 加 `refund_id`（或复合外键 (tenant_id, refund_id) → refund）；
--       (b) refund 加 `archive_id`（反向指针）；
--       (c) 不做库层关联，靠服务层在**同一次事务**内两次写入，关联只存在于业务层。
--     🛑 本迁移的处置：**什么都不加**，并登记。理由：
--       · (a)/(b) 都是 schema 变更，且它们决定了"归档的幂等键是 archive_id 还是 refund_id"
--         —— 这是本原语的**签名级**决策，不能由本迁移代拍；
--       · (c) 会让"归档动作"在库层不可自证（无法断言"这张工单被归档了"）。
--     🛑 还有一个直接后果必须写清：因为本函数**读不到 refund**，
--       它**无法**判定 PRD 的「case_archive.final_conclusion **退款终止时必填**」
--       （C.1.7 逐字）。⇒ 本函数把 final_conclusion 视为**可空**，
--       并把"退款终止时必填"这条要求**留给服务层**（它才拿得到 refund.outcome）。
--       这正是第 3 条缺口的**具体表现**，不是一个可以靠"加个 IF"绕过去的细节。
--
-- 【回滚说明】
--   本迁移只新增两个函数、不建表、不写业务行。
--     DROP FUNCTION IF EXISTS register_case_archive(uuid, uuid, uuid, jsonb, jsonb,
--                                                   text, jsonb, boolean, text);
--     DROP FUNCTION IF EXISTS latest_archive_of(uuid, uuid);
--     DELETE FROM schema_migration     WHERE version = 'V20';
--     DELETE FROM flyway_schema_history WHERE version = '20';
--   🛑 已归档的 case_archive 行【不受回滚影响】—— 它们是数据不是 schema。
--      若要清理，顺序必须是 case_archive → customer → store → tenant
--      （case_archive.customer_id → customer 是复合外键
--        `FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id)`，
--        V16 之后已是这个形态 —— 实测约束名 `case_archive_customer_id_fkey`）。
--      🛑 本表**没有**下游引用它（实测无任何外键指向 case_archive），
--         故它排在清理链最前 —— 这与 device 不同（device 被 device_dispatch 引用）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立，否则后面的一切都是空中楼阁
--
--   本迁移的设计建立在六个已存在的 schema 事实之上，六条都会在下面机械断言：
--     ① case_archive 是 ENABLE + **FORCE** RLS
--        —— 否则"建立上下文"是多余动作，而本函数的价值主要就在这。
--          与 V18 只断言 relrowsecurity 不同，这里**两条都断言**：
--          因为本迁移的核心机制三（清单门禁）与核心机制二（跨租户撞号）
--          **都**依赖"当前角色真的受 RLS 约束"，而 ENABLE 只管表主、
--          FORCE 才管住了表属主。缺 FORCE 时若迁移由 table owner 跑，
--          策略**不被应用** ⇒ 下面两条机制会静默失效。
--     ② `case_archive_pkey` 是**单列** `(archive_id)`
--        🛑 这一条是【核心机制二】的**唯一前提**。若哪天有人把它改成
--           `(tenant_id, archive_id)`，那么"跨租户撞号"这件事**就不存在了**
--           （两个租户可以合法地各有一份同 id 档案），于是：
--             · 本函数的 RAISE 分支变成**死代码**；
--             · 自证 (e6) 会以"竟然成功了"的面目失败 —— 一条**归因错误**的红。
--           故在此提前断言，让红指向真原因。
--     ③ 表上**没有** `(tenant_id, customer_id)` 唯一载体
--        🛑 这是"一个客户可以有多份归档档案"的**唯一前提**。
--           PRD 没有说"一客户一档"（C.1.7 的字段表里没有这条约束），
--           而 schema 也没有。本函数据此**允许**同一客户被归档多次
--           （每次一个不同的 archive_id，例如"继续"之后再"终止"再归档）。
--           ⇒ 若有人加了这条唯一约束，本函数"允许重复归档"的设计就失效，
--             且失效方式是 23505（而不是一条可读的业务错误）⇒ 归因错误。
--     ④ `customer` 上有 `uq_tenant_customer_id = UNIQUE (tenant_id, id)` 载体
--        —— 它是 `case_archive_customer_id_fkey` 这条**复合外键**的引用目标
--           （PG 要求被引用列是唯一键）。缺了它，V16 的复合外键根本上不去
--           —— 那会静默退回"引用存在但不归属一致"。
--     ⑤ `case_archive_customer_id_fkey` 是**复合**形态（含 tenant_id）
--        —— V16 的成果。它决定"跨租户客户引用"是否被库层拒绝。
--           本函数会显式查一次 customer（归因质量），但**库层那道必须仍然在**：
--           函数可以被人绕过（直接写 SQL），外键不能。
--     ⑥ 表上 CHECK 约束数 = **0**
--        🛑🛑 这一条保护的是**另一个测试类**（见文件头「核心机制四」）：
--           `RlsV5EntityIsolationTest` 用 `archive_checklist='{}'` 的探针
--           验证 WITH CHECK，并断言 SQLSTATE = 42501。
--           若表上出现任何会先于 RLS 生效的 CHECK，那条门禁会报 **23514** ——
--           一条归因完全错误的红（它说的是"WITH CHECK 没生效"）。
--           ⇒ 本断言让"想加 CHECK 的人"必须先显式改它，从而被看见。
-- ============================================================================

DO
$v20_precond$
DECLARE
    v_rls      boolean;
    v_force    boolean;
    v_pk       text;
    v_cust_fk  text;
    v_checks   int;
BEGIN
    -- ① case_archive 存在且 ENABLE + FORCE RLS
    SELECT c.relrowsecurity, c.relforcerowsecurity INTO v_rls, v_force
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'case_archive';

    IF v_rls IS NULL THEN
        RAISE EXCEPTION
            'V20 前置失败: 表 case_archive 不存在。本迁移的结案归档原语依赖它（V5 §2.22 建表）。';
    END IF;
    IF NOT v_rls THEN
        RAISE EXCEPTION
            'V20 前置失败: 表 case_archive 未启用 ROW LEVEL SECURITY。'
            '本迁移的核心价值之一是"归档必须自己建立租户上下文"，'
            '若 case_archive 没有 RLS，这个守卫就失去了守护对象 —— 不是"可以省略"，'
            '而是"前提被推翻了"，必须先解释清楚才能继续。';
    END IF;
    IF NOT v_force THEN
        RAISE EXCEPTION
            'V20 前置失败: 表 case_archive 只 ENABLE 了 RLS，没有 FORCE。'
            '🛑 这一条与上面那条**不是重复**：ENABLE 只对"非表属主"生效，'
            'FORCE 才把表属主也纳入策略管辖。'
            '而本迁移的两条核心机制 —— 核心机制二（跨租户撞号判定，依赖"别人的行读不到"）'
            '与核心机制三（清单门禁，依赖"探针真的被挡在外面"）'
            '—— **全部**建立在"当前角色受 RLS 约束"之上。'
            '缺 FORCE 时若迁移恰由 table owner 执行，策略**不被应用** ⇒ '
            '自证里依赖 RLS 的那几条会以【归因错误】的面目报红或假绿。'
            '（这与 V18/V19 自证里的 (a0) 能力守卫是同一族问题，但那一条查的是角色属性，'
            '这一条查的是表属性 —— 两者必须都有，缺一个就漏一类。）';
    END IF;

    -- ② case_archive_pkey 必须是【单列】archive_id —— 核心机制二的唯一前提
    SELECT pg_get_constraintdef(con.oid) INTO v_pk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'case_archive' AND con.contype = 'p';

    IF v_pk IS NULL THEN
        RAISE EXCEPTION 'V20 前置失败: 表 case_archive 没有主键 —— 无法建立 archive_id 的冲突判定。';
    END IF;
    IF v_pk !~ 'PRIMARY KEY\s*\(\s*archive_id\s*\)' THEN
        RAISE EXCEPTION
            'V20 前置失败: case_archive 的主键形态变了（当前: %）。'
            '本迁移的【核心机制二】完全建立在"case_archive_pkey 是单列 archive_id"这个事实上：'
            '正因为它不含 tenant_id，"同一 archive_id 被另一租户占用"才可能发生；'
            '也正因为会发生，函数才必须把"冲突了但我看不见 ⇒ 是别人的"这条判定补上。'
            '若主键已改为 (tenant_id, archive_id)，则该冲突不可能发生，'
            '本函数的跨租户分支变成死代码，而自证 (e6) 会以【归因错误】的方式失败。'
            '🛑 遇到这条报错，正确的反应是【修本迁移的设计说明与自证】，'
            '而不是把这里的判据改宽 —— 前提变了，结论必须重新推导。', v_pk;
    END IF;

    -- ③ 表上不得有 (tenant_id, customer_id) 唯一载体 —— "一客户可多档"的前提
    --   🛑 判定用【列集合】而不是字符串 LIKE —— V16 在 2026-09-27 已被这条坑过一次：
    --      pg_get_constraintdef 的输出按 conkey 顺序拼列名，故 LIKE '(tenant_id, %'
    --      实际断言的是"tenant_id 恰好排第一"，而不是"tenant_id 参与了这个键"。
    --      正确的判定是 conkey 的**集合相等**。V18/V19 的同类断言也照此写。
    IF EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        WHERE c.relname = 'case_archive' AND contype IN ('u', 'p')
          AND (SELECT array_agg(a.attname ORDER BY a.attname)
                 FROM unnest(con.conkey) AS k(attnum)
                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
              = ARRAY['customer_id', 'tenant_id']::name[]
    ) THEN
        RAISE EXCEPTION
            'V20 前置失败: case_archive 上出现了 (tenant_id, customer_id) 唯一载体。'
            '🛑 这意味着"一个客户只能有一份归档档案"成为库层事实，'
            '而本迁移的 register_case_archive 是**按 archive_id 幂等**的'
            '（允许同一客户被多次归档，例如"继续"之后再"终止"再归档）。'
            '两者冲突时，重复归档会以一条 **23505** 失败 —— '
            '一条与"归档动作"无关的、无法归因的数据库错误。'
            '若这个唯一约束是**有意**加的（即业务上确实"一客户一档"），'
            '正确处置是：① 重新推导本函数的幂等键（改成 customer_id）；'
            '② 重新推导本文件的"为什么没有取消归档原语"那一节；'
            '③ 同步 README 的缺口清单。**不得**只把这条断言删掉。';
    END IF;

    -- ④ customer 上有 UNIQUE (tenant_id, id) 载体（复合外键的引用目标）
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        WHERE c.relname = 'customer' AND con.contype = 'u'
          AND (SELECT array_agg(a.attname ORDER BY a.attname)
                 FROM unnest(con.conkey) AS k(attnum)
                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
              = ARRAY['id', 'tenant_id']::name[]
    ) THEN
        RAISE EXCEPTION
            'V20 前置失败: customer 上没有 UNIQUE (tenant_id, id) 载体。'
            '它是 case_archive_customer_id_fkey 这条复合外键的【引用目标】'
            '—— PG 要求被引用列是唯一键。'
            '🛑 实测真库上它叫 uq_tenant_customer_id；本判据【不按名字找】而按列集合找，'
            '因为名字对语义没有任何约束力（改名不该让门禁变红）。';
    END IF;

    -- ⑤ case_archive_customer_id_fkey 是复合形态（V16 的成果必须仍在）
    SELECT pg_get_constraintdef(con.oid) INTO v_cust_fk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'case_archive' AND con.contype = 'f'
       AND (SELECT count(*) FROM unnest(con.conkey)) = 2
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(con.conkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
           = ARRAY['customer_id', 'tenant_id']::name[];

    IF v_cust_fk IS NULL THEN
        RAISE EXCEPTION
            'V20 前置失败: case_archive 上没有复合形态 (tenant_id, customer_id) 的外键。'
            '这说明 V16（跨租户引用完整性）的成果【在 case_archive 上被回退了】。'
            'V20 的函数会显式查一次 customer 做归因，但那是【第一层】；'
            '库层的复合外键是【第二层】—— 函数可以被人绕过（直接写 SQL），外键不能。'
            '缺了它，"在租户 A 里把归档档案挂到租户 B 的客户名下"就重新变成一条敞开的路径，'
            '而那张归档档案会通过客户侧的行级 scope 泄漏到租户 B 的视图里。';
    END IF;

    -- ⑥ 表上 CHECK 约束数必须 = 0（保护 RlsV5EntityIsolationTest 的归因）
    SELECT count(*) INTO v_checks
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'case_archive' AND con.contype = 'c';

    IF v_checks <> 0 THEN
        RAISE EXCEPTION
            'V20 前置失败: case_archive 上出现了 % 条 CHECK 约束（期望 0）。'
            '🛑 这不是"约束多了更好"的问题，而是它会让一条**既有门禁报出归因错误的红**：'
            '`RlsV5EntityIsolationTest.cross_tenant_insert_is_rejected_by_with_check` '
            '用 `archive_checklist=''{}'' / staff_signs=''{}''` 的探针验证 WITH CHECK，'
            '并断言 SQLSTATE = 42501。若存在任何会先于 RLS 生效的 CHECK，'
            '那条 INSERT 会以 **23514**（check_violation）失败 ⇒ 门禁报红，'
            '而它说的是"WITH CHECK 没生效" —— 完全错误的方向。'
            '🛑 同时也要记住本迁移的设计选择：6 项清单门禁**写在函数里**而不是写成 CHECK，'
            '因为库层约束是**全局**的（对所有写入方生效，含夹具/探针/数据修复），'
            '而这里需要的是"只对归档动作生效"的业务门禁 —— 见文件头「核心机制四」。'
            '若确实要加 CHECK，请先显式改掉本断言，并在文件头说明为什么'
            '"对一切写入施加归档门禁"是正确的。', v_checks;
    END IF;
END;
$v20_precond$;


-- ============================================================================
-- 第 1 节 · 归档写入原语 + 读原语
-- ============================================================================

-- ---------------------------------------------------------------------------
-- register_case_archive(p_tenant_id, p_archive_id, p_customer_id, p_checklist,
--                       p_staff_signs, p_final_conclusion, p_metrics_trend,
--                       p_desensitize_authorized, p_created_by)
--
--   【返回两态（逐字说清，调用方据此审计留痕）】
--     · 'CREATED'        —— 本次调用建出了这一行
--     · 'ALREADY_EXISTS' —— 幂等命中：这一行【已在本租户名下】
--   【若干类 fail-closed 异常】
--     · 入参为空 / 类型不对           → RAISE
--     · 客户在本租户内不存在           → RAISE（消息含自证过的上下文，便于归因）
--     · 5 项硬门禁清单缺项或其值非 true → RAISE（消息含**缺项名称** —— PRD P0-25 逐字要求）
--     · 签名块 4 键缺项或为空串         → RAISE
--     · 清单/签名块含**未登记键**       → RAISE
--     · archive_id 已被【另一租户】占用 → RAISE（核心机制二）
--
--   【🛑 校验顺序为什么是"先校验、再写入"，而不是"先写入、后校验"】
--     两条独立的理由：
--       ① 落库语义：清单不齐时**绝不能落库**。若先写后校验，一次失败的归档
--          会留下半条档案，而它是**举证材料** —— 半份举证比没有更坏。
--          自证 (e4)/(e12) 都断言"被拒绝的那次没有落库"，正是钉住这一点。
--       ② 🛑 幂等重放时也要校验。：若首次写入时清单齐备、重放时传了一份**不齐**的清单，
--          函数会怎么答？本函数选择 **RAISE**，而不是静默 ALREADY_EXISTS。
--          理由：调用方传了一份不齐的清单，说明**它以为自己在做一次归档**；
--          若静默返回 ALREADY_EXISTS，调用方会以为"归档成功、且我这份清单被接受了"，
--          而库里是**另一份**清单。这与本迁移的核心机制二（"返回值在撒谎"）
--          是同一族形态 —— 只不过一个来自 RLS，一个来自校验位置。
--
--   【设计取舍一：customer 存在性用显式查 + RAISE，而不是让外键报 23503】
--     与 V17 `bind_band` / V18 `register_device` 对上层实体的处置逐字同款，
--     理由（归因质量）也逐字相同：
--       · 库层拒绝（复合外键）⇒ 一条 PostgreSQL 的 23503，调用方要自己解析约束名
--       · 本检查              ⇒ 一条写明"客户 X 在租户 Y 内不存在"的业务错误
--     两者都 fail-closed，区别只在诊断成本。
--     🛑 SELECT 在 RLS 下的语义注意：这里**不会**出现"查不到可能是看不见"的歧义 ——
--        因为上下文已在 (3) 建立并自证，且 customer 与 case_archive 用**同一条**策略表达式。
--
--   【设计取舍二：为什么把键集抽成 ARRAY 常量而不是散写】
--     三条键集（5 项硬门禁 / 1 项警告门禁 / 4 键签名块）都定义成函数内的局部数组，
--     而不是把字面量散在若干个 IF 里。两个好处，都是机械可判定的：
--       · 自证 (b7)/(b8) 能**锚在数组字面量上**断言"硬门禁集合不含 handband 项"
--         —— 这是"手环不得反转为阻断"那条合规红线的静态防线（行为防线是 (e5)）；
--       · 待裁登记第 2 条（键名语言未冻结）若最终裁定要改，**改的是这一处**。
--
--   【设计取舍三：为什么 p_effect_confirm_pdf 不在入参里】
--     case_archive 有 `effect_confirm_pdf VARCHAR(512)` 一列。本函数**有意不收它**，
--     理由不是"漏了"，而是：它是**文件引用**，而文件存储通路尚未选定
--     （见缺口修复总规划的 G-C 待裁项）。收一个"不知道指向哪里"的字符串入参，
--     会让这一列**看起来被填了**，而实际填进去的是一个无法解析的引用 ——
--     本仓反复要防的"死字段"形态。
--     ⇒ 本函数把 `effect_confirm_pdf` 与 `metrics_trend` 区别对待：
--       `metrics_trend` **收**（它是归档那一刻的趋势快照，属归档动作天然的输入，
--         不依赖任何未定通路）；`effect_confirm_pdf` **不收**（依赖未定的存储通路）。
--     ⇒ 于是 `effect_confirm_pdf` 在本迁移之后仍然是一个**零写入列**。
--       这是**已知且登记过的**（本文件头 + README 缺口清单），不是静默留下的缺口。
--
--   【🛑 为什么本函数无法校验「final_conclusion 退款终止时必填」】
--     C.1.7 逐字写「case_archive.final_conclusion / refund_amount / amount_basis |
--     否 | **退款终止时必填**」。
--     而本函数**读不到 refund**（refund 与 case_archive 无任何关联列 —— 见待裁第 3 条），
--     故它**没有**任何输入能回答"这次是不是退款终止"。
--     ⇒ 本函数把 final_conclusion 视为**可空**，并把那条要求**显式留给服务层**。
--       🛑 这不是"实现不完整"，而是"把一条无法在本层判定的规则留在了能判定它的层" ——
--          若在这里硬写一个 `IF p_final_conclusion IS NULL THEN RAISE`，
--          就等价于**要求一切归档都必须填结论**（包括"继续"之后归档的），
--          那是一条**比 PRD 更严的、代拍出来的**规则。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION register_case_archive(
    p_tenant_id              uuid,
    p_archive_id             uuid,
    p_customer_id            uuid,
    p_checklist              jsonb,
    p_staff_signs            jsonb,
    p_final_conclusion       text    DEFAULT NULL,
    p_metrics_trend          jsonb   DEFAULT NULL,
    p_desensitize_authorized boolean DEFAULT false,
    p_created_by             text    DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v20_register$
DECLARE
    -- 🛑 5 项【硬门禁】清单键（缺项/非 true ⇒ 阻断结案，403）。
    --    来源 = PRD C.3 的 ARC 细则（逐条判定为"硬阻断"的那 5 条）
    --           + P0-25「硬阻断规则缺项 → 后端返回 403 且给出缺失项名称」。
    --    🛑 **刻意不含** handband_recorded_as_reference —— 见下方 v_warn_gate_keys。
    v_hard_gate_keys text[] := ARRAY[
        'reason_recorded',              -- ARC-01 退款原因已记录
        'baseline_review_compared',     -- ARC-09 基线与当前复评已对照
        'retention_recorded',           -- ARC-10 挽留过程已记录
        'owner_signed',                 -- ARC-12 最终处理结论已经负责人签字
        'archive_plan_exec_archived'    -- ARC-13 客户档案/方案/执行/评估已归档
    ];
    -- 🛑 1 项【警告门禁】清单键（缺失或 false **都不阻断**，但存在时必须是布尔）。
    --    来源 = ARC-07（未佩戴：不阻断、不作不利依据）/ ARC-08（已同意佩戴却无记录：
    --           警告不阻断）/ ARC-11（未按"参考之一"口径记录：警告不阻断）
    --           + P0-25「警告规则仅记缺口」+「**手环类缺项不得反转为阻断**」
    --           + README §5.3「三条不得触碰」③「任何"手环缺项反转为阻断"的写法一律违规」。
    --    🛑 本数组只有 1 个元素，且它**绝不允许**被并进上面那个数组。
    --       自证 (b7)/(b8) 会锚在这里断言这件事。
    v_warn_gate_keys text[] := ARRAY[
        'handband_recorded_as_reference'
    ];
    -- 签名块 4 键（PRD C.1.7 逐字：json{经办人,经络师,门店负责人,日期}）
    v_sign_keys      text[] := ARRAY[
        'handler', 'meridian_therapist', 'store_owner', 'sign_date'
    ];
    v_all_gate_keys  text[];
    v_ctx_before     text;
    v_ctx            text;
    v_cust           int := 0;
    v_mine           int := 0;
    v_affected       int := 0;
    v_missing_gate   text;
    v_unknown_gate   text;
    v_missing_sign   text;
    v_unknown_sign   text;
    v_warn_val       text;
BEGIN
    v_all_gate_keys := v_hard_gate_keys || v_warn_gate_keys;

    -- (1) 入参守卫 —— 与 V15 / V17 / V18 / V19 同款：
    --     DDL 的 NOT NULL 挡不住空串，而空串在语义上同样无意义，且会静默流进台账。
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_case_archive: p_tenant_id 不可为空（case_archive.tenant_id 是 NOT NULL）';
    END IF;
    IF p_archive_id IS NULL THEN
        RAISE EXCEPTION
            'register_case_archive: p_archive_id 不可为空（case_archive.archive_id 是主键，'
            '且它是本函数幂等判定的唯一键 —— 没有它，重放无法与"新建"区分）';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION
            'register_case_archive: p_customer_id 不可为空（case_archive.customer_id 是 NOT NULL，'
            '且是复合外键 (tenant_id, customer_id) → customer 的一端）。'
            '🛑 它同时是**行级 scope 的承载者**：case_archive 表内没有 store_id，'
            '归档档案的可见范围随客户走 —— 客户为空等于这份档案没有归属。';
    END IF;
    IF p_checklist IS NULL THEN
        RAISE EXCEPTION
            'register_case_archive: p_checklist 不可为空（case_archive.archive_checklist 是 NOT NULL，'
            '且它是 P0-25 归档门禁的唯一载体）。'
            'PRD C.1.7 逐字要求这 6 项必须表态（5 项硬 + 1 项警告，见下方 (5)）。';
    END IF;
    IF p_staff_signs IS NULL THEN
        RAISE EXCEPTION
            'register_case_archive: p_staff_signs 不可为空（case_archive.staff_signs 是 NOT NULL，'
            '且 PRD §2.22 逐字要求"归档须客户签字"的落纸载体）。';
    END IF;
    IF p_desensitize_authorized IS NULL THEN
        RAISE EXCEPTION
            'register_case_archive: p_desensitize_authorized 不可为空（列是 NOT NULL DEFAULT false）。'
            '🛑 它是一条合规开关（§2.22 逐字："脱敏须单独授权"）——'
            '把"未授权"表达成 NULL 会让"客户没授权"与"忘了传"变成同一件事，'
            '而库层把它 DEFAULT 成 false，即**默认不授权**。'
            '故这里要求调用方**显式**表态，而不是靠默认值蒙过去。';
    END IF;

    -- (1b) JSONB 入参必须是 object（不是 array / 不是标量）
    --   🛑 单独断言的理由：`'[]'::jsonb -> 'key'` 返回 NULL，`jsonb_typeof` 返回 'array'
    --      ⇒ 若只断言"键存在"，一个传了数组的调用方会看到"6 项全缺"的报错，
    --        而真因是"传的不是对象"。归因质量。
    IF jsonb_typeof(p_checklist) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION
            'register_case_archive: p_checklist 必须是 JSON 对象（object），实际类型 = %。'
            'PRD C.1.7 把它定义为 map<6bool> —— 一个映射，不是数组。'
            '传数组时下面所有键判定都会"不存在"，那会把一次类型错误'
            '伪装成一次"清单全缺"的业务错误。', coalesce(jsonb_typeof(p_checklist), '<NULL>');
    END IF;
    IF jsonb_typeof(p_staff_signs) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION
            'register_case_archive: p_staff_signs 必须是 JSON 对象（object），实际类型 = %。'
            'PRD C.1.7 把它定义为 json{经办人,经络师,门店负责人,日期}。',
            coalesce(jsonb_typeof(p_staff_signs), '<NULL>');
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     理由与 V17 bind_band / V18 register_device 的 (2) 逐字相同，此处摘要：
    --       set_config(app.tenant_id, ..., true) 是**事务级**设置，且**不会**在函数入口
    --       自动重置。若调用方在一个"已设成租户 B"的事务里调用本函数传租户 A，
    --       不做检查就会把上下文**静默改写成 A**：后面的写是 A 的，而调用方以为是 B 的。
    --       本函数显式拒绝这种"覆盖既有上下文"的调用（fail-closed）。
    --     为什么宁失败不可静默覆盖：归档会写一份**举证材料**。
    --     它写错租户的后果不是"一条数据脏了"，而是"租户 A 的案卷里出现了一份
    --     属于租户 B 客户的归档档案" —— 一份不可撤回的、随客户 scope 可见的越权材料。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_case_archive: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, ..., true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '🛑 归档尤其不可静默改写 —— 它写的是举证材料，跨租户的归档档案会随客户 scope 可见。'
            '调用方须先结束当前事务（或使用全新连接）再归档。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 用 set_config（普通函数、参数可绑定），不用 SET LOCAL
    --        （后者必须把值拼进 SQL 文本 ⇒ 有注入面）。与 V15 / V17 / V18 / V19 同款。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_case_archive 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 🛑 客户必须**在本租户内**存在（见「设计取舍一」）
    --     库层还有 `case_archive_customer_id_fkey (tenant_id, customer_id) → customer (tenant_id, id)`
    --     兜底（V16 的成果，实测跨租户引用被 23503 拒）。
    SELECT count(*) INTO v_cust
      FROM customer
     WHERE id = p_customer_id AND tenant_id = p_tenant_id;

    IF v_cust <> 1 THEN
        RAISE EXCEPTION
            'register_case_archive: 客户 % 在租户 % 内不存在（本租户可见行数 = %），拒绝归档。'
            '🔴 若该客户确实存在，最常见的原因是租户上下文不对 —— '
            'customer 的 RLS 是 fail-closed 的，上下文错了 SELECT 会静默返回 0 行，'
            '于是"不是我的客户"与"我看不见我的客户"给出同一个结论。'
            '本次上下文的实际值（已自证）= %。',
            p_customer_id, p_tenant_id, v_cust, v_ctx;
    END IF;

    -- (5) 🛑🛑 归档完整性门禁 —— 本迁移的【核心机制三】，也是合规红线的落点
    --     见文件头那一整节。此处只写实现，理由不重复。
    --
    -- (5a) 未登记键 ⇒ RAISE（两个键集分开判，消息要指明是哪一个键集）
    --   🛑 为什么"未知键必抛"而不是"静默忽略"（借 ConsentAuthScope 的推理，但结论更硬）：
    --     auth_scope_json 那边是"客户可单独拒绝的集合"，故"缺键"合法；
    --     而这里是**封闭的完整性清单**（PRD 逐字列出 6 项），
    --     故"缺键"与"多出未登记键"**都不合法**。
    --     一个拼错的键（例如 'reason_recoded'）若不抛，会同时造成两件事：
    --       ① 那 5 项里的 reason_recorded 被判为缺失 ⇒ 报"缺项"（归因**部分**正确）；
    --       ② 但调用方以为自己填了 —— 而下一次他会再犯。
    v_unknown_gate := coalesce((
        SELECT string_agg(k, ', ' ORDER BY k)
          FROM jsonb_object_keys(p_checklist - v_all_gate_keys) AS t(k)), '');

    IF v_unknown_gate <> '' THEN
        RAISE EXCEPTION
            'register_case_archive: p_checklist 含【未登记键】-> %。'
            '已登记 6 键 = %。'
            '🛑 与 auth_scope_json 的处置不同、结论相同：那边"缺键合法"（客户可单独拒绝），'
            '这边"缺键也不合法"（PRD 逐字列出全部 6 项，是一个封闭清单）。'
            '不得静默忽略未登记键 —— 忽略会让一次拼写错误同时产出'
            '"某项被判缺失"与"调用方以为已填"两个后果。',
            v_unknown_gate, array_to_string(v_all_gate_keys, ', ');
    END IF;

    -- (5b) 5 项硬门禁：键必须存在、必须是 JSON 布尔、必须为 true
    --   🛑 三重严格里最容易被写漏的是第二重（是布尔）：
    --      若只判 `->> key = 'true'`，一个传了**字符串** "true" 的调用方会通过 ——
    --      而 JSON 字符串 "true" 与布尔 true 是**不同**的值（前者在客户端可能被渲染成
    --      "true" 这四个字符）。用 jsonb_typeof 钉住类型，与 ConsentAuthScope
    --      对"未登记键"的严格解析同族：**口径错误不是缺口，是错误。**
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_missing_gate
      FROM unnest(v_hard_gate_keys) AS t(k)
     WHERE jsonb_typeof(p_checklist -> k) IS DISTINCT FROM 'boolean'
        OR (p_checklist ->> k) <> 'true';

    IF v_missing_gate IS NOT NULL THEN
        RAISE EXCEPTION
            'register_case_archive: 归档清单【硬门禁缺项】-> %（共 % 项）。'
            '🛑 口径来源 = PRD C.3 的 ARC 细则（逐条判定为"硬阻断"的那 5 条）'
            ' + P0-25「硬阻断规则缺项 → 后端返回 403 且给出缺失项名称」。'
            '本函数不产 HTTP 码（库层产不出 403）；应用层据此抛 GATE_MISSING(2002, 403)，'
            '消息里带上本处的缺项名称。'
            '🔴 每一项对应的 ARC 规则（便于定位）：reason_recorded=ARC-01 · '
            'baseline_review_compared=ARC-09 · retention_recorded=ARC-10 · '
            'owner_signed=ARC-12 · archive_plan_exec_archived=ARC-13。',
            v_missing_gate, array_length(v_hard_gate_keys, 1);
    END IF;

    -- (5c) 🛑 1 项警告门禁：**键可以缺失、值可以为 false，都不阻断**
    --      唯一的严格点：键**存在**时必须真的是 JSON 布尔。
    --   🛑🛑 这三行就是"手环类缺项不得反转为阻断"的落点。
    --      任何把它并进 (5b) 那个数组的改动 —— 哪怕动机是"更严更安全" ——
    --      都会违反 P0-25 与 README §5.3「三条不得触碰」③。
    --      静态防线 = 自证 (b7)/(b8)；行为防线 = 自证 (e5)。
    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';
    IF (p_checklist ? 'handband_recorded_as_reference')
       AND jsonb_typeof(p_checklist -> 'handband_recorded_as_reference') IS DISTINCT FROM 'boolean' THEN
        RAISE EXCEPTION
            'register_case_archive: 归档清单的警告项 handband_recorded_as_reference '
            '存在但不是 JSON 布尔（实际类型 = %，值 = %）。'
            '🛑 这与"手环缺项不阻断"**不矛盾**：不阻断指的是"它的值是 false 或键缺失时照常结案"，'
            '而这里是"这件事根本没被表达成一个是/否" —— 那是一个口径错误，不是手环缺口。'
            '请把它写成 true 或 false。',
            coalesce(jsonb_typeof(p_checklist -> 'handband_recorded_as_reference'), '<NULL>'),
            coalesce(v_warn_val, '<NULL>');
    END IF;

    -- (5d) 签名块 4 键：必须存在且是非空字符串
    --   🛑 与清单门禁的差别（必须写清，否则看起来像不一致的处理）：
    --      清单是"是与否"（布尔），签名是"谁 + 何时"（文本）——
    --      空字符串对签名**没有意义**（一个空白的"门店负责人"不是签字）。
    --      DDL 的 NOT NULL 只能保证 staff_signs 这个 JSONB 不为空，
    --      挡不住它里面是 `{}` 或 `{"owner_signed":""}`。
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_missing_sign
      FROM unnest(v_sign_keys) AS t(k)
     WHERE jsonb_typeof(p_staff_signs -> k) IS DISTINCT FROM 'string'
        OR btrim(p_staff_signs ->> k) = '';

    IF v_missing_sign IS NOT NULL THEN
        RAISE EXCEPTION
            'register_case_archive: 签名块【缺项或为空】-> %（共 % 键）。'
            'PRD C.1.7 逐字：case_archive.staff_signs = json{经办人,经络师,门店负责人,日期} | 是。'
            '§2.22 同时写"归档须客户签字" —— 一份没有责任人签名的归档档案，'
            '在举证场景里等同于"没人对这次结案负责"。'
            '🛑 空串与缺键在这里被一并拒绝，理由：一个空白的"门店负责人"不是签字。',
            v_missing_sign, array_length(v_sign_keys, 1);
    END IF;

    v_unknown_sign := coalesce((
        SELECT string_agg(k, ', ' ORDER BY k)
          FROM jsonb_object_keys(p_staff_signs - v_sign_keys) AS t(k)), '');

    IF v_unknown_sign <> '' THEN
        RAISE EXCEPTION
            'register_case_archive: p_staff_signs 含【未登记键】-> %。已登记 4 键 = %。'
            '🛑 与 p_checklist 同款处置（见 (5a)）：这是一份**结构固定**的签名块，'
            '多出未登记键会掩盖"某个必签角色改错了名字"这件事。',
            v_unknown_sign, array_to_string(v_sign_keys, ', ');
    END IF;

    -- (6) 🛑🛑 幂等写入 + 跨租户撞号判定 —— 本迁移的核心（见文件头「核心机制二」）
    --     三段判定全部来自库层原子事实，**没有**"先读后写"的并发窗口：
    --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的
    --       ② ROW_COUNT=0 且本租户看得见 ⇒ 是本租户的行 ⇒ 幂等命中
    --       ③ ROW_COUNT=0 且本租户看不见 ⇒ 插不进去又看不见 ⇒ 在别的租户名下
    --     🛑 `archived_at` 显式写 now() 而不是依赖列默认值：
    --        默认值在"幂等命中"那次根本不执行，而显式写让本语句的意图可读
    --        （"归档时刻 = 本次调用时刻"是这条语句的一部分，不是表的副作用）。
    --     🛑 `created_by` 用 coalesce 回落：与 V18 的 'device-registration' 同款，
    --        但允许调用方传入更精确的值（它在库里是可空列）。
    INSERT INTO case_archive (archive_id, tenant_id, customer_id,
                              archive_checklist, staff_signs,
                              metrics_trend, final_conclusion,
                              desensitize_authorized, archived_at, created_by)
    VALUES (p_archive_id, p_tenant_id, p_customer_id,
            p_checklist, p_staff_signs,
            p_metrics_trend, nullif(btrim(p_final_conclusion), ''),
            p_desensitize_authorized, now(),
            coalesce(nullif(btrim(p_created_by), ''), 'case-archive-registration'))
    ON CONFLICT (archive_id) DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- ① 本次真的建出来了
    IF v_affected = 1 THEN
        RETURN 'CREATED';
    END IF;

    -- ② 未插入 ⇒ 全局已存在。它在谁名下？
    --    🛑 这次读在【已自证的本租户上下文】里，故它只看得到本租户的行。
    SELECT count(*) INTO v_mine
      FROM case_archive
     WHERE archive_id = p_archive_id;

    IF v_mine = 1 THEN
        RETURN 'ALREADY_EXISTS';
    END IF;

    -- ③ 插不进去却又看不见 ⇒ 它在别的租户名下
    --    🛑 这条 RAISE 就是"若照抄 V17 形态就会造出的缺陷"的封堵点。
    --       没有它，上面会 return 'ALREADY_EXISTS'，而本租户一行都没有 ——
    --       调用方据此把 refund.outcome 置成 '归档'，
    --       于是**一张退款工单被标记为"已归档"，而它的归档档案不存在**。
    --       这个缺口不会报错、不会 23503、没有任何下游能抓住它 ——
    --       它只在举证时暴露（审计要归档清单，而清单查不到）。
    RAISE EXCEPTION
        'register_case_archive: archive_id % 已被【另一租户】占用 —— 无法在本租户 % 名下归档。'
        '判定依据（无窗口）：INSERT ... ON CONFLICT (archive_id) DO NOTHING 被【主键冲突】拦下'
        '（ROW_COUNT=0），但在已自证的本租户上下文里 SELECT 又看不见这一行（可见行数=0）——'
        '"冲突了"证明它全局存在，"看不见"证明它不属本租户 ⇒ 它属于别的租户。'
        '🛑 为什么这不是"另一个 ALREADY_EXISTS"：case_archive_pkey 是【单列】archive_id，'
        '即"一份归档档案的一生只属于一个租户"是既有 schema 的既定事实。'
        '这不是幂等重放，而是数据冲突，需要人工确认是 archive_id 传错了、还是档案被错误调拨。'
        '🛑 为什么这条在本域比在 device 域更隐蔽：归档是链条的**末端收口动作**，'
        '下游没有第二步去证伪它。若在此返回 ALREADY_EXISTS，调用方会把工单标记为已归档，'
        '而归档档案并不存在 —— 这个缺口只在**举证时**暴露。',
        p_archive_id, p_tenant_id::text;
END;
$v20_register$;


-- ---------------------------------------------------------------------------
-- latest_archive_of(p_tenant_id, p_customer_id)
--
--   【返回】该客户在本租户内**最新**一份归档档案的 archive_id；
--           **NULL = 本租户内该客户尚无归档档案**。
--
--   【🛑 为什么 NULL 是一等返回值，而不是异常】
--     "尚未归档"是一个**正常的、可预期的**业务状态 ——
--     客户当然可以在还没结案的时候被问"归档了吗"。
--     把它做成异常会逼调用方用 try/catch 表达一个正常判断，
--     而那正是"异常被吞掉"的温床。
--     🛑 但措辞上要与 V17/V18 的 NOT_FOUND 保持同族纪律：
--        **NULL 不等于"该客户不存在"**。在 FORCE RLS 下，
--        "客户不属于本租户"与"客户存在但尚无归档"都会走到这里返回 NULL
--        —— 前一种情形应由 register_case_archive 的 (4) 或 customer 读侧去区分，
--        不该由本函数承担。故本函数的 NULL **只**回答"有没有归档档案"，
--        不回答"客户在不在"。
--
--   【🛑 为什么"最新"的判定必须由库层给出一个**全序**】
--     `ORDER BY archived_at DESC` 单独**不够**：同一时刻写入的两份档案
--     （并发归档 / 同一次批量导入）会给出**不确定**的先后 ⇒ 本函数在两次调用中
--     可能返回不同的 archive_id。这正是 `RefundWorkOrderPort.findLatestStatement`
--     注释里记载的同一族教训（那里的"最新"判据是 recorded_at，
--     而列表排序键含 statement_id，两者在并发写入时给出不同答案）。
--     ⇒ 这里显式给一个全序：`archived_at DESC, archive_id DESC`。
--       第二个键不是"随便加的 tie-breaker"，而是让"最新"成为一个**确定**的答案。
--
--   【🛑 为什么本函数也建立上下文、也做一致性守卫】
--     它是**读**函数，但 case_archive 是 FORCE RLS ⇒ 不建上下文则 USING 恒 false
--     ⇒ 恒返回 NULL —— 一个**"没归档"与"我没设上下文"同形**的假绿。
--     一致性守卫同理：读函数也会 set_config，也会静默改写调用方上下文。
--     🛑 "只读所以不需要守卫"是一个很容易犯的错 —— 本仓在 V17 的读侧原语里
--        已经付出过这个代价。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION latest_archive_of(
    p_tenant_id   uuid,
    p_customer_id uuid
)
    RETURNS uuid
    LANGUAGE plpgsql
AS
$v20_latest$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_id         uuid;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'latest_archive_of: p_tenant_id 不可为空（case_archive.tenant_id 是 NOT NULL）';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION 'latest_archive_of: p_customer_id 不可为空（归档档案的行级 scope 随客户）';
    END IF;

    -- (2) 上下文一致性守卫（与 register_case_archive 同款，逐字同理由）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'latest_archive_of: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '🛑 即使本函数只读也一样：set_config(app.tenant_id, ..., true) 是事务级设置'
            '且不会在函数入口自动重置，静默覆盖会让"后续读到的是谁的档案"变得不可推理。',
            v_ctx_before, p_tenant_id::text;
    END IF;

    -- (3) 建立并自证上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'latest_archive_of 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 取最新一份（全序：archived_at DESC, archive_id DESC —— 见上方说明）
    --     🛑 同时写 `tenant_id = p_tenant_id`：让这条 SELECT 的**租户维度在 SQL 里可见**，
    --        与 RLS 策略形成两层（RLS 兜底、谓词自证意图）。少写它 RLS 仍会兜住，
    --        但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读。
    --        本仓把可读性当成安全属性的一部分。
    --     🛑 LIMIT 1 + 无 ORDER BY 的 tie-breaker 会让本函数**非确定**；
    --        自证 (c6) 对此有静态断言。
    SELECT archive_id INTO v_id
      FROM case_archive
     WHERE tenant_id = p_tenant_id
       AND customer_id = p_customer_id
     ORDER BY archived_at DESC, archive_id DESC
     LIMIT 1;

    RETURN v_id;   -- 无行时是 NULL（见上方"NULL 是一等返回值"）
END;
$v20_latest$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15 / V17 / V18 / V19 第 2 节同款，理由逐字相同（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，
--       故本节的 GRANT 在常规环境里是 no-op；写它是"显式优于隐式"，
--       以及在"生产环境 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固"后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d2) 用 has_function_privilege 机械断言；
--      "授权段是否真的执行过"由 (d3) 断 proacl 的【显式项】—— 见那里的说明。
-- ============================================================================

DO
$v20_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_case_archive') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_case_archive(uuid, uuid, uuid, jsonb, jsonb, '
            'text, jsonb, boolean, text) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'latest_archive_of') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION latest_archive_of(uuid, uuid) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V20 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v20_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是**第 4 次**面对它：
--        · V15 踩过（初版 263 字符 ⇒ 「对于可变字符类型来说，值太长了(256)」）；
--        · V17 又踩过（初稿 358 字符，原文一模一样）；
--        · V18 把教训升级为"先量长度再写"，本行沿用同法。
--   本行的量法（把整条字符串当 ASCII 数一遍）+ 结果：
--        printf 量得 = 205 字符（上限 256，余量 51）
--   🛑 为什么必须"量"而不是"目测"：这条字符串全是英文短横与括号，视觉上"看起来不长"，
--      而 V17 那条超了 102 个字符 —— 目测在这件事上不可靠。
--   🛑 为什么不在迁移里用 char_length() 断言它：本行是 INSERT 的**值**，
--      要断言就得再读一次（另写一条 SELECT）—— 那会把"一处事实"变成"两处要保持一致"。
--      量长度是**写入前**的动作，属人与本仓纪律之间的约定，不是库层能自证的东西。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V20', 'case_archive provisioning primitive: register_case_archive() + latest_archive_of(). case_archive had no writer, so P0-14 mandatory archive (5 hard gates + 1 handband warning gate) could not land. Ops path.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
--        【与 V18/V19 的 (a0) 是同一件事、同一段代码 —— 本仓第 3 次写它】
--        本自证里依赖 RLS 的判据共 4 条：
--          (e3) 客户不存在被拒后【不落库】· (e6) 跨租户撞号后租户 B 里【零行】·
--          (e6) 对照（租户 B 用自己的 id 必须成功）· (e12) 清场前正向计数。
--        它们的有效性**完全建立**在"当前角色受 RLS 约束"之上。
--        BYPASSRLS 会让 `set_config('app.tenant_id', 租户B)` 之后仍看得见租户 A 的行
--        ⇒ (e6) 会以"竟然返回 ALREADY_EXISTS"的面目报红 —— 读起来像【函数坏了】，
--        真因恰好相反：**函数完全正确**，是证据的效力前提不成立。
--        ⇒ 没有本守卫时，那条红是【归因错误】的；照它的字面意思去"修函数"，
--          会改坏一个本来正确的实现。
--   (a1) 🛑 能力守卫二：RLS 策略本身必须还按 app.tenant_id 隔离
--        (a0) 只排除了"完全绕过"这一种；更隐蔽的是**策略被改动或缺失**
--        （例如被改成 `USING (true)`），那会让同样的 4 条判据静默假绿
--        —— 所有租户互相可见，而自证全绿、连一条报错都没有。
--        🛑 本表的策略在第 0 节被断言过 FORCE，但 FORCE 只管"是否生效"，
--           不管"策略内容是什么" —— 两者必须都断言。
--   (a) 两个函数必须真的存在
--   (b) register_case_archive 的函数体必须含【核心四件套】+ 合规红线
--        ① 建立上下文 · ② 上下文自证 · ③ 上下文一致性守卫
--        ④ 幂等写入的原子形态（含 ON CONFLICT 推断目标 = 全局主键）
--        ⑤ 跨租户撞号的判定分支（核心机制二，**实测抓出来的**）
--        ⑥ 🛑🛑 **硬门禁数组不含手环项**（核心机制三 = 合规红线）
--        ⑦ 无明显改写通路（无 UPDATE / 无 DELETE）
--   (c) latest_archive_of 的函数体必须含 ①②③ + 全序 tie-breaker + LIMIT 1
--   (d) 迁移登记 + 当前角色对两个函数真的有 EXECUTE 权限 + 授权段真的执行过（proacl 显式项）
--   (e) 🛑 行为验证 —— 本迁移最重要的部分。前面都只检查"函数体长什么样"，
--       而"函数体看着对、跑起来不对"是本仓反复出现过的形态。
--       共 13 段（含 3 段对照）：
--         e1  归档 → CREATED，且行确实落库（清单/签名/客户/租户全部对上）
--         e2  重放同一 archive_id → ALREADY_EXISTS（幂等），且不得产生第二行
--         e3  客户不在本租户内 → RAISE（不是返回值），且必须证明【没有落库】
--         e4  🛑 硬门禁缺项（owner_signed 缺席）→ RAISE，**消息里必须含该项名称**
--             （P0-25 逐字要求"给出缺失项名称"），且必须证明【没有落库】
--         e5  🛑🛑 **合规红线**：手环项【缺席】与手环项【为 false】
--             —— 两种情形都**必须成功结案**（不得阻断）。
--             这一条是 P0-25「手环类缺项不得反转为阻断」的行为防线。
--         e6  🛑🛑 跨租户撞号：租户 B 用租户 A 已占用的 archive_id →
--             必须 RAISE（**不是** ALREADY_EXISTS），且消息里必须含"另一租户"。
--             这一条是本迁移相对 V17 形态的实质改动，也是 121 反向验证的靶心。
--         e6-对照：租户 B 用自己的 archive_id → 必须 CREATED
--         e7  对照：同租户裸 INSERT（不同 archive_id）→ 必须**成功**
--             （防"把正常路径一起打死"）
--         e8  🛑 跨租户客户引用：租户 A 里把 case_archive.customer_id 指向租户 B 的客户
--             → 必须被【库层复合外键】拒，且理由必须是 23503 而不是 42501
--         e9  latest_archive_of 的行为：
--             · 尚未归档的客户 → NULL（不是异常）
--             · 已归档的客户   → 返回**最新**的一份（用同一 archived_at
--               的两份档案验证 tie-breaker —— 没有它本函数非确定）
--         e10 清单含【未登记键】→ RAISE
--         e11 签名块缺项 / 空串 → RAISE
--         e12 清场前的正向计数：证明确实有行写进去过
--   (f) 探针清场 + 清场自证（一行业务数据都不许留下）
--
--   🛑 全部探针在一个 BEGIN ... EXCEPTION 子事务里跑，块结束时整体撤销 ——
--      迁移不得留下任何业务数据（V1~V19 一致的纪律）。
--
--   🛑 注意 e3 / e4 / e5(反例不涉及) / e6 / e10 / e11 必须用**内层** BEGIN/EXCEPTION
--      包住那条会抛的语句，否则一次预期的 RAISE 会把外层子事务标记为已回滚，
--      后续断言全部以「当前事务被终止」失败 —— 一条归因完全错误的红。
--
--   🛑 同一条纪律同样适用于 e5：它是"必须成功"的用例，若它抛了而没被捕获，
--      后面的断言会以"事务被终止"失败，掩盖住"手环被反转为阻断"这个真因。
--      故 e5 也用内层 BEGIN/EXCEPTION 包住，并断言 v_accepted = true。
-- ============================================================================

DO
$v20_guard$
DECLARE
    v_reg_body      text;
    v_lat_body      text;
    v_one_body      text;
    v_reg_code      text;
    v_lat_code      text;
    v_missing       text;
    v_noacl         text;
    v_cnt           int;
    v_reg           int;
    v_nopriv        text;
    v_mode          text;
    v_touched       text;
    v_msg           text;
    v_state         text;
    v_rejected      boolean := false;
    v_accepted      boolean := false;
    v_bypass        boolean;
    v_slice         text;
    v_key           text;
    v_latest        uuid;
    v_ts            timestamptz;
    -- ---- 探针租户 / 客户 / 档案（20 前缀，与 V17 的 17 / V18 的 18 / V19 的 19 互不重叠）----
    v_ta            uuid := '20000000-0000-0000-0000-00000000000a';
    v_tb            uuid := '20000000-0000-0000-0000-00000000000b';
    v_cust_a1       uuid := '20000000-0000-0000-0000-0000000000c1';  -- e1/e2/e5/e9/e10/e11
    v_cust_a2       uuid := '20000000-0000-0000-0000-0000000000c2';  -- e9 尚未归档
    v_cust_b1       uuid := '20000000-0000-0000-0000-0000000000c3';  -- e8 的目标（租户 B 的客户）
    v_arc_a1        uuid := '20000000-0000-0000-0000-0000000000d1';  -- e1/e2
    v_arc_a2        uuid := '20000000-0000-0000-0000-0000000000d2';  -- e4 硬门禁缺项（应不落库）
    v_arc_a3        uuid := '20000000-0000-0000-0000-0000000000d3';  -- e5 手环项缺席（须成功）
    v_arc_a4        uuid := '20000000-0000-0000-0000-0000000000d4';  -- e5 手环项为 false（须成功）
    v_arc_a5        uuid := '20000000-0000-0000-0000-0000000000d5';  -- e3 客户不存在（应不落库）
    v_arc_a6        uuid := '20000000-0000-0000-0000-0000000000d6';  -- e10 未登记键（应不落库）
    v_arc_a7        uuid := '20000000-0000-0000-0000-0000000000d7';  -- e11 签名缺项（应不落库）
    v_arc_a8        uuid := '20000000-0000-0000-0000-0000000000d8';  -- e7 同租户裸 INSERT 对照
    v_arc_a9        uuid := '20000000-0000-0000-0000-0000000000d9';  -- e9 tie-breaker 的较小 id
    v_arc_aa        uuid := '20000000-0000-0000-0000-0000000000da';  -- e9 tie-breaker 的较大 id
    v_arc_ab        uuid := '20000000-0000-0000-0000-0000000000db';  -- e8 跨租户客户引用（应被拒）
    v_arc_shared    uuid := '20000000-0000-0000-0000-0000000000dc';  -- e6 跨租户撞号（租户 A 先占）
    v_arc_b_new     uuid := '20000000-0000-0000-0000-0000000000dd';  -- e6 之后租户 B 用自己的 id 建
    -- 5 项硬门禁的键名常量（自证 (b12) 逐项断言用；与函数内数组必须一致）
    v_hard_keys     text[] := ARRAY[
        'reason_recorded', 'baseline_review_compared', 'retention_recorded',
        'owner_signed', 'archive_plan_exec_archived'];
    v_warn_key      text := 'handband_recorded_as_reference';
BEGIN
    -- ==================================================================
    -- (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
    --   理由与 V18 的 (a0) 逐字相同，此处不复述全文（见该文件）。
    --   摘要：本自证里 4 条判据的有效性建立在"当前角色受 RLS 约束"上；
    --        BYPASSRLS 会让 (e6) 以"竟然返回 ALREADY_EXISTS"的面目报红，
    --        而那是【归因错误】的红（函数是对的，前提不成立）。
    --   🛑 为什么必须用 `rolbypassrls` 而不是 `current_setting('is_superuser')`：
    --      两者不等价 —— 一个**非超级用户**也可以被授予 BYPASSRLS。
    --   🛑 顺带说明为什么本仓的应用角色是对的：
    --      实测 diaoyuanyun 的 super=f / bypassrls=f，
    --      即生产路径上 RLS 是真的在起作用的 —— 本守卫只是要求
    --      "跑迁移/自证的角色"与"跑应用的角色"在这一点上一致。
    -- ==================================================================
    SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;

    IF v_bypass IS NULL THEN
        RAISE EXCEPTION
            'V20 自证失败(a0): 查不到当前角色 % 的 rolbypassrls 属性（pg_roles 无此角色？）', current_user;
    END IF;
    IF v_bypass THEN
        RAISE EXCEPTION
            'V20 自证失败(a0): 当前角色 % 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 4 条判据全部失去效力】，'
            '继续执行只会产出一份"看起来跑了、实际什么都没验"的证据。'
            '🛑 具体失效路径（V18 已在本机实测同型）：BYPASSRLS 会绕过行级安全 ⇒ '
            '以租户 B 的上下文执行 `SELECT count(*) FROM case_archive WHERE archive_id = <租户A的档案>` '
            '仍会看到那一行 ⇒ register_case_archive 的判定链落到"是我的"分支 ⇒ 返回 ALREADY_EXISTS ⇒ '
            '自证 (e6) 以"跨租户撞号没有抛错"的面目报红。'
            '🛑 那条红是【归因错误】的：函数是对的，是证据的效力前提不成立。'
            '若照它的字面意思去改函数，会改坏一个本来正确的实现。'
            '正确处置：用一个【不】绕过 RLS 的角色跑本迁移（本仓应用角色 diaoyuanyun '
            '实测 super=false / bypassrls=false，即为正确选择），'
            '而不是放宽本自证或修改函数。', current_user;
    END IF;

    -- ==================================================================
    -- (a1) 🛑 能力守卫二：策略本身必须还按 app.tenant_id 隔离
    --
    --   (a0) 排除了 BYPASSRLS 这一种"完全绕过"，但还有一种更隐蔽的：
    --   **RLS 策略本身被改动或缺失**。它会让 4 条判据同样静默失效，而角色属性完全正常。
    --   🛑 断言策略的【定义内容】而不是存在性：一条 `USING (true)` 的策略能让
    --      (e12)（清场前正向计数）与 (e6) 静默假绿 —— 所有租户互相可见，而自证全绿。
    --   🛑 这里刻意断言**两条**：USING 与 WITH CHECK 都必须引用 app.tenant_id。
    --      只查其中一条会漏掉"读隔离在、写隔离没了"（或反之）——
    --      而写隔离缺失会让 (f2) 清场后的残留检查静默通过。
    -- ==================================================================
    IF (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'public' AND tablename = 'case_archive') <> 1 THEN
        RAISE EXCEPTION
            'V20 自证失败(a1): case_archive 表上的 RLS 策略数 ≠ 1 个 —— '
            '本自证的 4 条 RLS 判据（(e3) 不落库 / (e6) 零行 / (e6) 对照 / (e12) 正向计数）'
            '全部建立在"策略按 app.tenant_id 隔离"之上。'
            '🛑 这种情形下角色属性正常（(a0) 会通过），但判据同样静默失效 ⇒ '
            '必须在写入任何探针数据之前把它变成显式红。';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public' AND tablename = 'case_archive'
           AND qual       LIKE '%app.tenant_id%'
           AND with_check LIKE '%app.tenant_id%'
    ) THEN
        RAISE EXCEPTION
            'V20 自证失败(a1): case_archive 的策略虽然存在，但其 USING / WITH CHECK 未同时引用 app.tenant_id。'
            '🛑 断言【定义内容】而不是存在性：一条 `USING (true)` 的策略能让'
            '(e6)/(e12) 静默假绿 —— 所有租户互相可见，而自证全绿。'
            '而 WITH CHECK 缺失会让写入不受租户约束，使 (f2) 的清场残留检查失去意义。';
    END IF;

    -- ------------------------------------------------------------------
    -- (a) 函数存在
    --   🛑 判空用 `IS NOT NULL`（string_agg 在"无缺失行"时返回 NULL），
    --      不是 `array_length(...) > 0` —— 后者会以一条与 (a) 无关的错误失败
    --      （「有缺陷的数组常量」），V17 已被 118 的 I(a) 抓过这条（本仓教训）。
    -- ------------------------------------------------------------------
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_missing
    FROM (VALUES ('register_case_archive'), ('latest_archive_of')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20 自证失败(a): 以下函数未创建成功 -> %', v_missing;
    END IF;

    -- ------------------------------------------------------------------
    -- (b)(c) 函数体断言（🛑 只读 prosrc = 函数体原文，不读整个文件）
    --       读整个文件会把本注释块里的示例文本当成"代码里存在"⇒ 假绿。
    --       本文件的注释里【逐字】写着 set_config / assert_tenant_context /
    --       INSERT INTO case_archive / ON CONFLICT / 另一租户 / 'owner_signed' /
    --       'handband_recorded_as_reference' ——
    --       这正是必须只读 prosrc、且必须剥注释的原因。
    -- ------------------------------------------------------------------
    SELECT p.prosrc INTO v_reg_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_case_archive';

    SELECT p.prosrc INTO v_lat_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'latest_archive_of';

    IF v_reg_body IS NULL THEN
        RAISE EXCEPTION 'V20 自证失败(b): 读不到 register_case_archive 的函数体';
    END IF;
    IF v_lat_body IS NULL THEN
        RAISE EXCEPTION 'V20 自证失败(c): 读不到 latest_archive_of 的函数体';
    END IF;

    -- (b1)(b2)(b3) 逐函数检查三件套（建立上下文 / 自证 / 一致性守卫）
    --   🛑 逐行剥 -- 行注释后再匹配：PG 会把注释原样存进 prosrc，
    --      不剥的话"注释里提到"与"代码里调用"无法区分（V15 / V17 / V18 已踩过这条）。
    FOREACH v_mode IN ARRAY ARRAY['register_case_archive', 'latest_archive_of'] LOOP
        EXECUTE format(
            'SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace '
            'WHERE n.nspname = ''public'' AND p.proname = %L', v_mode) INTO v_one_body;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id'''
               AND regexp_replace(ln, '--.*$', '') ~ ',\s*true\s*\)'
        ) THEN
            RAISE EXCEPTION
                'V20 自证失败(b1): 函数 % 的函数体里【代码态】没有以 is_local := true 调用 '
                'set_config(app.tenant_id, ...)。这是本迁移最核心的不变量 —— '
                'case_archive 是 FORCE RLS + fail-closed，缺上下文时任何 INSERT 都会被 WITH CHECK 拒绝'
                '（读侧则静默零行）；而 is_local := false 会让上下文跨请求泄漏给连接池里的下一个请求。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'assert_tenant_context\s*\('
        ) THEN
            RAISE EXCEPTION
                'V20 自证失败(b2): 函数 % 没有调用 assert_tenant_context()。'
                '缺少它，"设了上下文"就退化成"我以为设好了"—— 本仓的判据一贯是'
                '"设置之后必须能断言它生效"，V15 / V17 / V18 / V19 的原语也是这么写的。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'current_setting\s*\(\s*''app\.tenant_id'''
        ) THEN
            RAISE EXCEPTION
                'V20 自证失败(b3): 函数 % 没有读 current_setting(app.tenant_id) 做一致性守卫。'
                '缺了它，调用方在一个"已设成租户 B"的事务里调用本函数传租户 A 时，'
                '上下文会被【静默改写成 A】—— 后面的写全落在 A 名下，而调用方以为是 B。'
                '🛑 对归档尤其危险：它写的是**举证材料**，跨租户的归档档案会随客户 scope 可见。'
                '见文件头「核心机制一」与 register_case_archive 的 (2)。', v_mode;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- 🛑🛑 2026-09-27（119 反向验证的教训）：把两条函数体【先剥掉行注释】再匹配
    --
    --   【为什么必须这一步 —— 不是风格，是判据的效力】
    --   PG 会把 `AS $tag$ ... $tag$` 之间的内容**原样**存进 pg_proc.prosrc，
    --   注释也在里面。V18 的函数体注释里【逐字】写着它自己要做的事，
    --   于是所有"函数体里出现过 X"形态的判据都可以被**注释**满足。
    --   【实测证据（119 的 I(c4) / I(b5)）】
    --   注入"删掉代码处那条谓词"（代码改动，语法仍合法）：
    --       修复前 ⇒ 自证打印"自证通过"    ← 静默假绿：判据被注释满足
    --       修复后 ⇒ 自证报红
    --
    --   【口径（与 V18 逐字同款）】
    --   · **语句形态判据必须在下述 v_code 上匹配**（= prosrc 逐行剥掉 `--` 之后的部分）；
    --   · 同时保留【锚点】：以某条语句的起始关键字起锚、以 `;` 为界
    --     （`[^;]*` 不跨语句）—— 判据的适用范围 = 它的锚点范围。
    --   · (b1)(b2)(b3) 在执行期就做了逐行剥注释，故它们本来就不受影响；
    --     本步只是把同一机制补到语句形态判据上。
    --
    --   🛑 本文件的注释里逐字写着 `'owner_signed'` 与 `'handband_recorded_as_reference'`
    --      （就在上面那两行 v_hard_keys / v_warn_key 的声明注释里），
    --      故 (b12) 这条**合规红线断言**若不剥注释就**必然是假绿** —— 它会被注释满足。
    --      这是本文件里 (b12) 最容易被写错的地方。
    -- ------------------------------------------------------------------
    SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
      INTO v_reg_code
      FROM regexp_split_to_table(v_reg_body, E'\n') AS t(ln);

    SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
      INTO v_lat_code
      FROM regexp_split_to_table(v_lat_body, E'\n') AS t(ln);

    -- (b4) register_case_archive 的写入语句存在
    --   🛑🛑 用 `\M` 而不是 `\b`：PostgreSQL 的 ARE 正则里 `\b` 是**退格字符**，
    --      不是 PCRE 的"词边界"。V17 已被这条坑过（正样本不匹配 ⇒ 一条自证的**假红**；
    --      更危险的是它的孪生形态：永假的守卫会**静默假绿**）。
    --      ⇒ 凡"词边界"意图，本仓一律用 `\M`（词尾）。
    --   🛑 这里用 `\M` 还有一层具体作用：注释里出现过 `case_archive_pkey` /
    --      `case_archive_customer_id_fkey` 这类子串，不加词尾边界会让判据被它们命中。
    IF v_reg_code !~ 'INSERT\s+INTO\s+case_archive\M' THEN
        RAISE EXCEPTION
            'V20 自证失败(b4): register_case_archive 的函数体（剥注释后的代码态）里没有 '
            'INSERT INTO case_archive —— '
            '本迁移存在的全部理由就是让 case_archive 有写入方；没有这一句，'
            'ProvisioningBoundaryGateTest 第②例会在下一个构建立刻报红（账本在骗人）。';
    END IF;

    -- (b5) 该 INSERT 必须带 ON CONFLICT（且在锚点范围内）
    --   🛑 双重收紧：① 在【剥注释后的代码态】上匹配；② 从 `INSERT INTO case_archive` 起锚。
    --      V18 的 (b5) 初版既无锚点也没剥注释，而函数体里有 2 处代码 + 1 处注释都含
    --      `ON CONFLICT` ⇒ "判据的满足来源"与"它要保护的那条语句"之间没有绑定关系。
    IF v_reg_code !~ 'INSERT\s+INTO\s+case_archive\M[^;]*ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V20 自证失败(b5): register_case_archive 的 INSERT 语句没有 ON CONFLICT —— '
            'PL/pgSQL 的 INSERT 没有 ELSE 分支：不加 ON CONFLICT 就只能靠 BEGIN/EXCEPTION '
            '捕获 23505，而"捕获到 conflict 就知道是重复归档"这条推理是错的'
            '（23505 也可能来自其它唯一约束）。故必须用库层的原子 upsert。';
    END IF;

    -- (b6) 🛑 推断目标必须逐字是【主键列】archive_id
    --   本迁移的跨租户判定完全建立在"推断目标是那个不含 tenant_id 的全局主键"之上。
    --   若有人改成 `ON CONFLICT (tenant_id, archive_id)`，冲突就不会再跨租户发生，
    --   而 (b7) 的判定分支会变成死代码却**不自知** —— 故在此钉住。
    --
    --   🛑🛑 2026-09-27（121 反向验证抓出的真实缺陷，与 V19 的 (b5)/(c4) 同族）：
    --     本判据的初版是**裸的** `ON\s+CONFLICT\s*\(\s*archive_id\s*\)` ——
    --     既无锚点，也没意识到**函数体内那条 RAISE 消息的字面量**里逐字写着
    --     `INSERT ... ON CONFLICT (archive_id) DO NOTHING`（在"判定依据"那句里，
    --     它是**给运维看的解释文本**，不是代码）。
    --     实测（本脚本 I(b6)）：
    --       把真正的 INSERT 改成 `ON CONFLICT ON CONSTRAINT case_archive_pkey`
    --       （**语义等价**的正确改法，PG 原生支持）：
    --         修复前 ⇒ 判据被那条 RAISE 字面量满足 ⇒ 报 (b6) —— 这是**假红**
    --         更危险的是它的孪生形态：把真正的 INSERT 改成
    --         `ON CONFLICT (tenant_id, archive_id)`（**错误的**改法），
    --         那条 RAISE 字面量**同样**能命中 ⇒ 自证照常打印"通过" ⇒ **静默假绿**
    --         —— 即本判据对"它唯一要防的那件事"毫无反应。
    --     ⇒ 处置：① **必须带锚点**（从 `INSERT INTO case_archive` 起锚、以 `;` 为界），
    --              使判据的命中范围 = 那条真正的写入语句，而不是函数体内任意文本；
    --            ② **接受两种语义等价形态**（列推断 / 约束名推断），
    --              因为断言的对象是"推断目标不含 tenant_id 这个语义"，
    --              而不是"写成了某一种语法"。
    --   🛑 顺带守住反面：锚点段内不得出现把 tenant_id 拉进推断目标的写法。
    IF v_reg_code !~ 'INSERT\s+INTO\s+case_archive\M[^;]*'
                   '(ON\s+CONFLICT\s+ON\s+CONSTRAINT\s+case_archive_pkey\M'
                   '|ON\s+CONFLICT\s*\(\s*archive_id\s*\))' THEN
        RAISE EXCEPTION
            'V20 自证失败(b6): register_case_archive 的 ON CONFLICT 推断目标不是【全局主键 archive_id】。'
            '🛑 这不是风格问题：本迁移的【核心机制二】完全建立在"推断目标是全局主键"'
            '这个事实上 —— 正因为它不含 tenant_id，"另一租户占用同一 archive_id"才可能发生；'
            '也正因为会发生，函数才必须补上"冲突了但我看不见 ⇒ 是别人的"这条判定。'
            '若改成 (tenant_id, archive_id)，该冲突不可能发生，(b7) 的分支成为死代码，'
            '而自证 (e6) 会以【归因错误】的方式失败（报"竟然成功了"）。'
            '🛑 本判据【带锚点】（从 INSERT INTO case_archive 起锚、以 `;` 为界），'
            '因为函数体内那条 RAISE 消息的字面量里逐字写着'
            '`INSERT ... ON CONFLICT (archive_id) DO NOTHING` —— '
            '不带锚点时本判据会被**它**满足（121 的 I(b6) 已实测：假红与静默假绿并存）。'
            '🛑 判据接受两种语义等价形态：`ON CONFLICT (archive_id)` 与 '
            '`ON CONFLICT ON CONSTRAINT case_archive_pkey` —— '
            '断言的对象是"推断目标不含 tenant_id 这个语义"，不是"某一种语法写法"。';
    END IF;

    -- (b6-负向) 🛑 锚点段内**不得**把 tenant_id 拉进推断目标
    --   🛑 为什么"正向判据"不够：正向判据里那句 `ON\s+CONFLICT\s*\(\s*archive_id\s*\)`
    --      在 `ON CONFLICT (tenant_id, archive_id)` 上**不匹配**（因为 archive_id 前有逗号+
    --      tenant_id）—— 这一条恰好能兜住它。但它兜不住 `ON CONFLICT (archive_id, tenant_id)`。
    --      两条合起来才是完整的：(b6) 要求"存在一个只含 archive_id 的推断目标"，
    --      本条要求"该 INSERT 语句里不存在含 tenant_id 的推断目标"。缺任一条都有一类漏网。
    IF v_reg_code ~ 'INSERT\s+INTO\s+case_archive\M[^;]*ON\s+CONFLICT\s*\([^)]*tenant_id' THEN
        RAISE EXCEPTION
            'V20 自证失败(b6-负向): register_case_archive 的 INSERT 语句把 tenant_id '
            '拉进了 ON CONFLICT 的推断目标。'
            '🛑 这正是本判据唯一要防的事：推断目标一旦含 tenant_id，'
            '"同一 archive_id 被另一租户占用"就**不可能发生** ⇒ '
            '跨租户撞号的 RAISE 分支成为死代码，而调用方在跨租户撞号时会收到 '
            'ALREADY_EXISTS —— 即"一张退款工单被标记为已归档，而它的归档档案不存在"。'
            '这条缺口不会报错、不会 23503，只在举证时暴露。';
    END IF;

    -- (b7) 🛑🛑 跨租户撞号的判定分支必须存在
    --   【判据必须带锚点：从那条 RAISE 起锚】
    --   🛑 为什么不写成"函数体里出现过'另一租户'四个字"：
    --      那是 V17 的 (b7)/(c7) 在 2026-09-27 被抓过的形态 ——
    --      "文内任意位置出现过这句话"无法证明"这句话出现在该出现的那条语句里"。
    --      本文件的注释里逐字写着"另一租户"，故不带锚点的判据会被**注释**满足 ⇒ 假绿。
    --      收紧方式：要求 `RAISE EXCEPTION` 与"另一租户"同处一条语句片段
    --      （`[^;]*` 不跨分号 —— RAISE 的参数之间不会出现分号）。
    IF v_reg_code !~ 'RAISE\s+EXCEPTION[^;]*另一租户' THEN
        RAISE EXCEPTION
            'V20 自证失败(b7): register_case_archive 里没有"跨租户撞号"的 RAISE 分支。'
            '🛑 这是本迁移的核心（见文件头「核心机制二」）：'
            '`ON CONFLICT (archive_id) DO NOTHING` 在 archive_id 被【别的租户】占用时'
            '会【静默 DO NOTHING 且 ROW_COUNT=0】，而本租户里那一行**看不见**。'
            '若只按 ROW_COUNT 判定，函数会对一个手里一行都没有的租户返回 ALREADY_EXISTS —— '
            '调用方据此把 refund.outcome 置成 ''归档'' ⇒ '
            '**一张退款工单被标记为"已归档"，而它的归档档案不存在**。'
            '🛑 本域比 device 域更隐蔽：归档是链条的末端收口动作，下游没有第二步去证伪它，'
            '这个缺口只在举证时暴露。'
            '故必须有这条分支，且它必须是 RAISE（不是返回值）。'
            '🛑 判据在【剥注释后的代码态】上匹配、且从 `RAISE EXCEPTION` 起锚。';
    END IF;

    -- (b8) 判定必须真的区分"是我的"与"不是我的"：必须在本租户上下文里读一次 case_archive
    --   🛑 与 (b7) 配合才完整：(b7) 保证有 RAISE 分支，(b8) 保证它前面的判定读真的存在。
    --      只有 RAISE 而没有那次读，RAISE 就成了无条件抛错（会把正常幂等也打死）。
    --   🛑 2026-09-27（121）：判据接受**两种等价的谓词书写**（`archive_id = p_archive_id`
    --      与 `p_archive_id = archive_id`）。断言的对象是"按 archive_id 做等值匹配"这个语义，
    --      而不是"某个操作数写在左边"—— 后者会把一次**等价重构**报成红（判据过度收紧，
    --      121 的 I(b8) 已实测：`WHERE p_archive_id = archive_id` 在初版判据下报 (b8)）。
    IF v_reg_code !~ 'FROM\s+case_archive\M[^;]*'
                   '(archive_id\s*=\s*p_archive_id|p_archive_id\s*=\s*archive_id)' THEN
        RAISE EXCEPTION
            'V20 自证失败(b8): register_case_archive 里没有"在已自证的本租户上下文内按 archive_id 读一次"'
            '的语句（形如 `SELECT count(*) ... FROM case_archive WHERE archive_id = p_archive_id`）。'
            '🛑 缺了它，(b7) 的 RAISE 会变成**无条件抛错** —— 那会把"本租户的幂等重放"'
            '也一起打死（本该返回 ALREADY_EXISTS 的调用变成异常）。'
            '两段判定必须成对：先问"它在谁名下"，才能区分"我的 ⇒ 幂等"与"别人的 ⇒ 冲突"。';
    END IF;

    -- (b9) 归档路径的租户维度必须在 SQL 里可见（INSERT 列清单里含 tenant_id）
    --   🛑 从 `INSERT INTO case_archive` 起锚、以 `;` 为界，理由同 (b7)。
    IF v_reg_code !~ 'INSERT\s+INTO\s+case_archive\M[^;]*tenant_id' THEN
        RAISE EXCEPTION
            'V20 自证失败(b9): register_case_archive 的【INSERT 语句自身】列清单里没有 tenant_id。'
            'case_archive 是 FORCE RLS（策略 = tenant_id 等于上下文），INSERT 不带 tenant_id '
            '会直接违反 NOT NULL；但更值得防的是"带了却带了别处来的值"——'
            '本断言保证租户维度在写入语句里显式可见，而不是从某个局部变量悄悄带进来。';
    END IF;

    -- (b10)(b11) 🛑 归档【没有改写通路】—— "归档后只读"的一半（见文件头）
    --   🛑 为什么这两条是"必须存在"的断言而不是"可选"：
    --      本节后面 (e1) 会断言"行确实落库"、且整个函数是唯一写入方。
    --      若有人为了"支持重放时更新清单"而加一条 UPDATE，那么
    --      · (e2) 的"不得产生第二行"仍然会过（UPDATE 不改行数）；
    --      · 而"归档是证据快照、不可改写"这条性质**静默消失** —— 没有任何断言会红。
    --      ⇒ 故在此静态钉住：函数体内不得出现 UPDATE / DELETE case_archive。
    --   🛑 措辞必须精确：断言的是**本函数体内**没有改写语句，而不是"库层禁止改写"。
    --      后者本迁移有意不做（会给出一个"必须禁用触发器才能做数据修复"的口子，
    --      比不加更危险）—— 见文件头「"归档后只读"在本迁移里只做了一半」。
    IF v_reg_code ~ 'UPDATE\s+case_archive\M' THEN
        RAISE EXCEPTION
            'V20 自证失败(b10): register_case_archive 的函数体里出现了 UPDATE case_archive。'
            '🛑 归档是**终态**，ARCHIVE 行是**证据快照**：'
            'PRD P0-14 把归档定义为收口动作（收口不可撤销），'
            '而 RefundOutcome 的注释逐字写明"归档 是结案状态位（case_archive 落地、归档后只读）"。'
            '一条 UPDATE 会让"重放归档"从幂等重放退化成"用新参数改写既有证据"—— '
            '而这**不会被任何行为断言抓住**（行数不变、(e2) 仍绿）。'
            '🛑 若确实需要"修正一份写错的归档"，正确处置是：'
            '① 裁定它是不是一条合法业务动作（当前 PRD 里没有）；'
            '② 若合法，为它单独设计一个**有独立返回值语义**的原语，'
            '    而不是让它从"归档"这个入口可达 —— 否则又一次"两件事共用一个入口"。';
    END IF;
    IF v_reg_code ~ 'DELETE\s+FROM\s+case_archive\M' THEN
        RAISE EXCEPTION
            'V20 自证失败(b11): register_case_archive 的函数体里出现了 DELETE FROM case_archive。'
            '🛑 与 (b10) 同族，但更严重：删除一份归档档案会让"这张工单被归档过"这个事实'
            '**在库里消失** —— 而 refund.outcome 可能仍然是 ''归档'' ⇒ '
            '工单说"已归档"，而归档档案查不到。'
            '🛑 同理，"取消归档"不由本表承担，而由 refund 侧的状态机承担'
            '（见文件头「为什么没有取消归档原语」）。';
    END IF;

    -- ------------------------------------------------------------------
    -- (b12) 🛑🛑🛑 合规红线：硬门禁数组**不得**含手环项
    --
    --   【这一条为什么是本迁移最重要的一条静态断言】
    --   PRD C.3 的 6 项清单里，**5 项硬阻断 + 1 项（手环）警告不阻断**；
    --   而 PRD P0-25 与 README §5.3「三条不得触碰」③ 逐字写着：
    --       「**任何"手环缺项反转为阻断"的写法一律违规**」
    --   🛑 "把它并进硬门禁数组"这个改动**看起来是"更安全"的** ——
    --      它有极大的动机被做出（"缺项就阻断"听起来更严、更合规）。
    --      而它恰好是**违规**：手环是"参考之一"，把它变成阻断等于
    --      用一条数据缺口去阻止结案 —— 而这正是 PRD 与合规要求反复禁止的。
    --   ⇒ 故必须有这条断言。且它必须是**锚定**的：
    --      从 `v_hard_gate_keys text[] := ARRAY[` 起锚、以 `;` 为界，
    --      断言这个片段里**没有** handband 键。
    --
    --   🛑🛑 为什么这里【必须】剥注释（否则本断言恒为假绿）：
    --      本文件的注释里逐字写着 `'handband_recorded_as_reference'`
    --      （就在 v_warn_key 声明上方那一大段里），
    --      而这段注释在 prosrc 里**不存在**（它不在函数体内）——
    --      但函数体内的注释里也提到了它。
    --      不剥注释时，即使有人把它从数组移进硬门禁，注释仍会命中 ⇒ 假绿。
    --      这是本文件里最容易被写错的一处，故逐字记录。
    -- ------------------------------------------------------------------
    v_slice := substring(v_reg_code FROM 'v_hard_gate_keys\s+text\[\]\s*:=\s*ARRAY\[[^;]*');

    IF v_slice IS NULL OR v_slice = '' THEN
        RAISE EXCEPTION
            'V20 自证失败(b12): 在 register_case_archive 的函数体（剥注释后）里找不到 '
            '`v_hard_gate_keys text[] := ARRAY[...]`。'
            '🛑 本断言的**锚点是那个数组字面量本身** —— 找不到锚点就无法判断"硬门禁集合是什么"，'
            '而一条"找不到锚点就跳过"的判据等于没有判据。'
            '若确实需要改写该数组的声明形式（例如拆成多个变量），'
            '必须同步改本断言，而不是让它静默失效。';
    END IF;

    IF v_slice ~ 'handband' THEN
        RAISE EXCEPTION
            'V20 自证失败(b12): 🛑🛑🛑 手环门禁键出现在【硬门禁数组】里 —— 这是合规红线违规。'
            'PRD P0-25 与 README §5.3「三条不得触碰」③ 逐字要求：'
            '「**任何"手环缺项反转为阻断"的写法一律违规**」。'
            'PRD C.3 的划定是：ARC-07（未佩戴）/ ARC-08（已同意佩戴却无记录）/ '
            'ARC-11（未按"参考之一"口径记录）**全部为"警告不阻断"**。'
            '🛑 这个改动看起来"更严格、更安全"，而它恰好违规：'
            '手环数据是"参考之一"（PRD W-3 逐字），'
            '把它变成阻断等于用一条数据缺口去阻止结案。'
            '正确处置：把该键放回 v_warn_gate_keys（警告门禁：缺失或 false 都不阻断）。'
            '锚点片段 = %', v_slice;
    END IF;

    -- (b12b) 5 项硬门禁键必须**逐项**在数组字面量里
    --   🛑 为什么逐项断言而不是只数个数：只数个数时，一次"把 A 换成 B"的改动
    --      （数量不变）会被漏掉。逐项断言让"硬门禁集合变了"必然被抓住。
    FOREACH v_key IN ARRAY v_hard_keys LOOP
        IF v_slice !~ ('\''' || v_key || '\''') THEN
            RAISE EXCEPTION
                'V20 自证失败(b12b): 硬门禁数组里缺少键 %。'
                '本迁移的硬门禁集合 = PRD C.3 里逐条判定为"硬阻断"的那 5 条 ARC 规则：'
                'reason_recorded=ARC-01 · baseline_review_compared=ARC-09 · '
                'retention_recorded=ARC-10 · owner_signed=ARC-12 · '
                'archive_plan_exec_archived=ARC-13。'
                '🛑 若这一项被有意移出硬门禁，必须先在 PRD/README 上改口径，'
                '而不是只改这个数组 —— 那会让"哪 5 项是硬门禁"变成一处只存在于代码里的隐性事实。'
                '锚点片段 = %', v_key, v_slice;
        END IF;
    END LOOP;

    -- (b12c) 手环键必须在**警告**门禁数组里（与 (b12) 成对）
    --   🛑 只有 (b12)（不得在硬门禁里）是不够的：把该键**整个删掉**同样能让 (b12) 通过，
    --      而那会让"手环项被记录成参考之一了吗"这件事在清单里**没有载体** ——
    --      ARC-11 也就失去了落点。故必须同时断言它在警告数组里。
    v_slice := substring(v_reg_code FROM 'v_warn_gate_keys\s+text\[\]\s*:=\s*ARRAY\[[^;]*');

    IF v_slice IS NULL OR v_slice = '' THEN
        RAISE EXCEPTION
            'V20 自证失败(b12c): 找不到 `v_warn_gate_keys text[] := ARRAY[...]` 这个锚点。'
            '见 (b12) 对"找不到锚点等于没有判据"的说明。';
    END IF;
    IF v_slice !~ 'handband_recorded_as_reference' THEN
        RAISE EXCEPTION
            'V20 自证失败(b12c): 警告门禁数组里没有 handband_recorded_as_reference。'
            '🛑 只有 (b12)（"不得在硬门禁里"）是不够的 —— 把该键**整个删掉**同样能让 (b12) 通过，'
            '而那会让 ARC-11（手环数据未按"参考之一"口径记录 → 警告不阻断）'
            '**失去清单载体**：归档档案里不再有任何地方回答"这件事被记录了吗"。'
            '🛑 注意本断言同样在【剥注释后的代码态】上匹配 —— 见 (b12) 的说明。'
            '锚点片段 = %', v_slice;
    END IF;

    -- ------------------------------------------------------------------
    -- (c) latest_archive_of 的读侧断言
    -- ------------------------------------------------------------------
    -- (c1) 租户维度必须在 SQL 里可见
    --   🛑 从 `FROM case_archive` 起锚、以 `;` 为界。
    IF v_lat_code !~ 'FROM\s+case_archive\M[^;]*tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V20 自证失败(c1): latest_archive_of 的 SELECT 里缺少 `tenant_id = p_tenant_id`。'
            '只按 customer_id 写时 RLS 仍会兜住（不会真的跨租户），'
            '但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读 —— '
            '本仓把可读性当成安全属性的一部分，故要求租户维度在谓词里显式可见。';
    END IF;

    -- (c2) 🛑 "最新"必须有一个**全序**：ORDER BY archived_at DESC 之后必须有 tie-breaker
    --   没有它，同一 archived_at 的两份档案会让本函数**非确定**
    --   （两次调用可能返回不同的 archive_id）—— 这与
    --   RefundWorkOrderPort.findLatestStatement 注释里记载的是同一族教训。
    IF v_lat_code !~ 'ORDER\s+BY\s+archived_at\s+DESC' THEN
        RAISE EXCEPTION
            'V20 自证失败(c2): latest_archive_of 没有 `ORDER BY archived_at DESC` —— '
            '没有排序就无法回答"最新"，LIMIT 1 会返回一个**任意**行。';
    END IF;
    IF v_lat_code !~ 'ORDER\s+BY\s+archived_at\s+DESC[^;]*archive_id\s+DESC' THEN
        RAISE EXCEPTION
            'V20 自证失败(c2): latest_archive_of 的 ORDER BY 缺少 tie-breaker `archive_id DESC`。'
            '🛑 `ORDER BY archived_at DESC` 单独**不够**：同一时刻写入的两份档案'
            '（并发归档 / 同一次批量导入）会给出**不确定**的先后 ⇒ '
            '本函数在两次调用中可能返回不同的 archive_id。'
            '第二个键不是"随便加的"，而是让"最新"成为一个**确定**的答案。'
            '锚点：从 ORDER BY 起锚、以 `;` 为界。';
    END IF;

    -- (c3) LIMIT 1 必须在（否则多行会以 INTO 只取第一行，语义随执行计划变）
    IF v_lat_code !~ 'LIMIT\s+1' THEN
        RAISE EXCEPTION
            'V20 自证失败(c3): latest_archive_of 没有 LIMIT 1。'
            'PL/pgSQL 的 `SELECT ... INTO` 在多行时**不报错**、只取第一行 —— '
            '而"第一行"取决于执行计划 ⇒ 语义不确定。显式 LIMIT 1 让意图可读。';
    END IF;

    -- (c4) 读函数不得含改写语句（与 (b10)/(b11) 同族）
    IF v_lat_code ~ 'UPDATE\s+case_archive\M' OR v_lat_code ~ 'DELETE\s+FROM\s+case_archive\M' THEN
        RAISE EXCEPTION
            'V20 自证失败(c4): latest_archive_of 是**读**原语，但其函数体里出现了 '
            'UPDATE / DELETE case_archive。一个"读"函数带写入副作用属接口撒谎。';
    END IF;

    -- ------------------------------------------------------------------
    -- (d) 迁移登记 + 权限
    -- ------------------------------------------------------------------
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V20';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V20 自证失败(d): schema_migration 中 V20 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- (d2) 用 has_function_privilege 而非读 proacl：前者把"角色继承 / PUBLIC 授权 /
    --      owner 隐含权限"等所有生效路径都算进去（V15 / V17 / V18 / V19 同款理由）。
    SELECT string_agg(x.sig, ', ' ORDER BY x.sig) INTO v_nopriv
    FROM (VALUES
              ('register_case_archive(uuid,uuid,uuid,jsonb,jsonb,text,jsonb,boolean,text)'),
              ('latest_archive_of(uuid,uuid)')) AS x(sig)
    WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');
    IF v_nopriv IS NOT NULL THEN
        RAISE EXCEPTION
            'V20 自证失败(d2): 当前角色 % 对以下函数没有 EXECUTE 权限 -> %。'
            '典型成因：环境做过 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固，'
            '而第 2 节的授权段未覆盖到 —— 那会表现为"归档时报 permission denied"，'
            '属运行期才发现的错误，故在迁移期断言。', current_user, v_nopriv;
    END IF;

    -- 🛑🛑 (d3) 上一条 (d2) 在【按本仓脚本建的库】上**恒真、判别力为零**。
    --   （V17 已被 118 的 I(d2) 抓过：注入 `REVOKE ... FROM current_user` 之后
    --     自证仍然打印"自证通过"，即该断言对"权限被撤掉"毫无反应。）
    --   成因：迁移是用应用角色本身跑的 ⇒ 两个函数的 **owner 就是 current_user**
    --        ⇒ owner 对自有对象的 EXECUTE 是**隐含**的，has_function_privilege 永远为 t，
    --        且 REVOKE 也撤不掉它（要撤只能改 owner）。
    --   ⇒ 把"授权段真的执行了"变成可机械判定的事实：断言 proacl 里存在【显式 ACL 项】。
    --      `proacl IS NULL` 意味着"从未 GRANT 也从未 REVOKE"——那正是"授权段被摘掉"的形态。
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_noacl
    FROM (VALUES ('register_case_archive'), ('latest_archive_of')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f
          AND p.proacl IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM unnest(p.proacl) AS a(item)
              -- 显式项都带 '=' 与 '/'（形如 'role=X/grantor' 或 '=X/grantor'）；
              -- 只要存在任何一项即证明"对本函数执行过 GRANT 或 REVOKE"。
              -- 🛑 必须显式 `::text` 转换：aclitem 类型**没有 LIKE 运算符**，
              --    不加会报「错误: 操作符不存在: aclitem ~~ unknown」（本机 PG 17.11 实测）。
              WHERE a.item::text LIKE '%=%/%'));

    IF v_noacl IS NOT NULL THEN
        RAISE EXCEPTION
            'V20 自证失败(d3): 以下函数的 proacl 为 NULL —— 即【从未被 GRANT/REVOKE 过】：%。'
            '含义：第 2 节的授权段没有真正执行到这两个函数上。'
            '🛑 为什么 (d2) 单独不够：本迁移由应用角色自己执行 ⇒ 函数 owner = 调用者，'
            '而 owner 对自有函数的 EXECUTE 是隐含的 ⇒ (d2) 在该情形下恒为真。'
            '（V17 的 118 反向验证 I(d2) 已实测：REVOKE ... FROM current_user 之后 (d2) 仍报通过。）'
            '本断言改看【显式 ACL 项是否存在】，故对"授权段被摘掉 / 未生效"有反应。', v_noacl;
    END IF;

    -- ==================================================================
    -- (e) 行为验证 —— 全部在子事务里，块末整体撤销
    -- ==================================================================
    BEGIN
        -- 准备：两个租户（tenant 无 RLS，可直接写）
        INSERT INTO tenant (id, name, status)
        VALUES (v_ta, 'V20 结案归档探针 A', 'active'),
               (v_tb, 'V20 结案归档探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        -- 两个租户各建客户（customer 是 FORCE RLS ⇒ 必须各自在自己的上下文里写）
        -- 🛑 本迁移的探针**不需要建 store**：case_archive 表内没有 store_id，
        --    而行级 scope 随客户走（见文件头与 register_case_archive 的 (1)）。
        --    customer.owner_store_id / serving_store_id 都是**可空**的（实测），
        --    故只写 tenant 与 customer 即可 —— 这比 V18 的探针短一层依赖。
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        INSERT INTO customer (id, tenant_id, name)
        VALUES (v_cust_a1, v_ta, 'V20 探针客户 A1'),
               (v_cust_a2, v_ta, 'V20 探针客户 A2（尚未归档）');

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        INSERT INTO customer (id, tenant_id, name)
        VALUES (v_cust_b1, v_tb, 'V20 探针客户 B1');

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e1 归档 → CREATED，且行确实落库（四列同时断言）
        -- ---------------------------------------------------------------
        v_mode := register_case_archive(
            v_ta, v_arc_a1, v_cust_a1,
            '{"reason_recorded":true,"baseline_review_compared":true,'
            '"retention_recorded":true,"owner_signed":true,'
            '"archive_plan_exec_archived":true,"handband_recorded_as_reference":true}'::jsonb,
            '{"handler":"张三","meridian_therapist":"李四",'
            '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb,
            '退款终止·双方协商一致', '{"trend":"下降"}'::jsonb, false, 'v20-probe');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V20 自证失败(e1): 首次归档返回 %（期望 CREATED）。'
                '若返回 ALREADY_EXISTS，说明该 archive_id 在库里已存在'
                '（探针 id 冲突 / 上次运行的残留）。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM case_archive
                        WHERE archive_id = v_arc_a1 AND tenant_id = v_ta
                          AND customer_id = v_cust_a1
                          AND (archive_checklist ->> 'owner_signed') = 'true'
                          AND btrim(staff_signs ->> 'store_owner') = '王五'
                          AND desensitize_authorized = false) THEN
            RAISE EXCEPTION
                'V20 自证失败(e1): register_case_archive 返回 CREATED，但库里查不到'
                '与之匹配的行（期望 tenant_id/customer_id/清单/签名/脱敏开关全部对上）。'
                '🛑 五列同时断言是刻意的：一个"插了行但清单用错来源"的实现'
                '会让归档档案声称"六项齐备"而实际缺项 —— 那是本迁移最该防的后果'
                '（它把一个**缺项结案**伪装成一次合法结案）。';
        END IF;

        -- ---------------------------------------------------------------
        -- e2 幂等：重放同一 archive_id → ALREADY_EXISTS，且不得产生第二行
        --   🛑🛑 2026-09-27（121 反向验证的补强）：**第二次调用刻意传入不同的内容**。
        --     若第二次传入的内容与第一次**逐字相同**，那么"重放不得改写证据"
        --     这条判据在 `ON CONFLICT (archive_id) DO UPDATE SET ...` 的实现下
        --     **仍然会通过**（UPDATE 写回的正是同样的值）—— 即判据对
        --     "用新参数改写既有证据"这件事毫无反应。这是一个**静默假绿**。
        --     修复后：第二次传入**另一份内容**，再断言"库里那一行仍然是第一次那份"。
        --   🛑 约束：第二次的内容必须**能通过所有门禁**（否则会在第 (5) 步被拒，
        --      根本到不了 ON CONFLICT，本用例就测不到改写）。
        --      ⇒ 5 项硬门禁键**保持 true**；**警告项也保持 true**（见下）；
        --        改的是那些"门禁不管、但属于证据"的列：
        --        签名人 / 结论 / 趋势 / 脱敏授权 / created_by。
        --      这些正是 `DO UPDATE SET ...` 最容易顺手写上的列。
        --   🛑 为什么警告项也保持 true：若把它改成 false，而某条**未来的**门禁
        --      （或某次注入）对 false 有反应，e2 会在到达 ON CONFLICT 之前就失败
        --      —— 那会让本用例以一条与"重放改写"无关的错失败（归因错误）。
        --      本用例要隔离的变量只有一个：**重放的内容与首次不同**。
        -- ---------------------------------------------------------------
        v_mode := register_case_archive(
            v_ta, v_arc_a1, v_cust_a1,
            -- 🛑 6 键全 true（与第一次的清单**逐字相同**）—— 隔离变量：只改其它列
            '{"reason_recorded":true,"baseline_review_compared":true,'
            '"retention_recorded":true,"owner_signed":true,'
            '"archive_plan_exec_archived":true,"handband_recorded_as_reference":true}'::jsonb,
            -- 🛑 签名人全换、日期换到 2099
            '{"handler":"改写者","meridian_therapist":"改写者2",'
            '"store_owner":"改写者3","sign_date":"2099-12-31"}'::jsonb,
            '重放试图改写结论', '{"trend":"上升"}'::jsonb, true, 'attacker');

        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V20 自证失败(e2): 重放同一 archive_id 返回 %（期望 ALREADY_EXISTS）。'
                '返回 CREATED 说明幂等失效（库层主键没有生效 —— '
                '那意味着"一份归档档案可以被建两次"，而 archive_id 是主键，不该发生）。', v_mode;
        END IF;
        IF (SELECT count(*) FROM case_archive WHERE archive_id = v_arc_a1) <> 1 THEN
            RAISE EXCEPTION
                'V20 自证失败(e2): 重放之后同一 archive_id 的行数 ≠ 1。'
                '返回值说"已存在"，但行数不止一行 —— 返回值与行为不一致。';
        END IF;
        -- 🛑 (b10) 的行为佐证：重放不得改写既有档案（清单/签名/结论必须还是第一次那份）
        --    这一条把"归档是证据快照"变成可观测事实：若函数在重放分支里 UPDATE，
        --    这里的清单会被第二份（本次传入的）内容改掉 —— 而 (e2) 只查行数，抓不住它。
        IF (SELECT btrim(final_conclusion) FROM case_archive WHERE archive_id = v_arc_a1)
           <> '退款终止·双方协商一致' THEN
            RAISE EXCEPTION
                'V20 自证失败(e2): 重放把既有归档档案的内容改掉了'
                '（final_conclusion 期望『退款终止·双方协商一致』，实际 = %）。'
                '🛑 归档是**证据快照**，重放必须是纯幂等（不写任何东西）。'
                '若这里变了，说明函数在 ALREADY_EXISTS 分支之前写了什么 —— '
                '而那会让"重放归档"退化成"用新参数改写既有证据"。',
                (SELECT coalesce(btrim(final_conclusion), '<NULL>')
                   FROM case_archive WHERE archive_id = v_arc_a1);
        END IF;
        -- 🛑（121 补强）上面那条只看 final_conclusion 一列 —— 一次只改写
        --   archive_checklist / staff_signs / metrics_trend / desensitize_authorized
        --   的 UPDATE 能绕过它。⇒ 用"整行特征列全匹配"作为判据：
        --   **本次重放传的是不同内容**（见上方）⇒ "重放零写入"的机械判据是 ——
        --   重放之后该行仍然逐列等于**第一次**那份形态（行数 = 1）。
        --   🛑 为什么这比"逐列 IF"更强：任何一列被改写都会让这条 SELECT 返回 0 行
        --      ⇒ 报红，且报错消息里列出**全部**特征列，便于定位是哪一列变了。
        --   🛑 为什么不必语义归一：这里比的是函数自己写进去的同一份 JSONB
        --      （两次传入字面量逐字相同），PG 的键序重排与空格补齐发生在**第一次写入时**，
        --      两次读回的是同一个存储值。跨实现比才有归一问题，自比没有。
        DECLARE
            v_intact int;
        BEGIN
            SELECT count(*) INTO v_intact
              FROM case_archive
             WHERE archive_id = v_arc_a1 AND tenant_id = v_ta AND customer_id = v_cust_a1
               AND (archive_checklist ->> 'owner_signed') = 'true'
               AND (archive_checklist ->> 'handband_recorded_as_reference') = 'true'
               AND (archive_checklist ->> 'reason_recorded') = 'true'
               AND (archive_checklist ->> 'baseline_review_compared') = 'true'
               AND (archive_checklist ->> 'retention_recorded') = 'true'
               AND (archive_checklist ->> 'archive_plan_exec_archived') = 'true'
               AND btrim(staff_signs ->> 'handler') = '张三'
               AND btrim(staff_signs ->> 'meridian_therapist') = '李四'
               AND btrim(staff_signs ->> 'store_owner') = '王五'
               AND btrim(staff_signs ->> 'sign_date') = '2026-09-27'
               AND desensitize_authorized = false
               AND btrim(created_by) = 'v20-probe'
               AND metrics_trend = '{"trend": "下降"}'::jsonb;
            IF v_intact <> 1 THEN
                RAISE EXCEPTION
                    'V20 自证失败(e2): 重放之后"首次写入形态"的整行特征匹配数 = %（期望 1）'
                    '—— 说明重放**改写**了既有证据的某一部分。'
                    '🛑 本次重放传入的是【另一份内容】'
                    '（签名人="改写者" / 签名日期=2099-12-31 / 结论="重放试图改写结论" / '
                    '趋势=上升 / 脱敏授权=true / created_by="attacker"）—— '
                    '故"匹配数 = 1"等价于"重放一个字节都没写进去"。'
                    '🛑 归档是**证据快照**：若这里变了，说明实现在 ALREADY_EXISTS 分支之前'
                    '（或通过 `ON CONFLICT ... DO UPDATE`）写了什么 —— '
                    '那会让"重放归档"退化成"用新参数改写既有证据"，'
                    '且**只有当举证时才会被发现**。'
                    '🛑 本判据覆盖 13 个特征（清单 6 键 + 签名 4 键 + 脱敏开关 + '
                    'created_by + metrics_trend）—— 任何一处被改写都会让它从 1 变 0。', v_intact;
            END IF;
        END;

        -- ---------------------------------------------------------------
        -- e3 客户不在本租户内 → RAISE（不是返回值），且必须证明【没有落库】
        --    🛑 用内层 BEGIN/EXCEPTION 包住那条会抛的语句：否则一次预期的 RAISE
        --       会把外层子事务标记为已回滚，后续断言全部以「当前事务被终止」失败
        --       —— 一条归因完全错误的红。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a5, v_cust_b1,
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true}'::jsonb,
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e3): 用【租户 B 的客户】在租户 A 里归档没有抛错（返回值 %）。'
                '客户必须在本租户内存在（register_case_archive 的 (4)）。'
                '🛑 若它返回 ALREADY_EXISTS，那更坏：说明 (4) 的存在性检查被摘掉了，'
                '而这里本该在写之前就失败。', v_mode;
        END IF;
        -- 🛑🛑 2026-09-27（121 反向验证抓出的真实缺陷）：
        --   【必须断言 SQLSTATE + 消息，而不是只断言"抛了异常"】
        --   本用例的初版只断言 `v_rejected = true`。实测（121 的 I(e3)）：
        --     把函数里 (4) 的存在性检查改成 `IF false THEN`（**摘掉函数层防线**）⇒
        --       INSERT 会落到库层复合外键 `case_archive_customer_id_fkey` 上 ⇒
        --       收到 **23503** ⇒ 内层 EXCEPTION 捕获 ⇒ v_rejected = true ⇒
        --       **自证照常打印"通过"** —— 即 (e3) 对"函数层检查被摘掉"毫无反应（静默假绿）。
        --   🛑 这是本仓最典型的一族形态：**防御纵深**（V16 的库层外键）把一条
        --      函数层判据的失效**掩盖**了 —— 拒绝仍然发生，只是理由完全不同。
        --      而两者的后果天差地别：函数层拒绝会给出"客户不存在 + 实际上下文值"的
        --      可归因消息；(4) 被摘掉后，一条"把归档挂到别的租户客户名下"的调用
        --      只剩下库层那道兜底 —— 而函数可以被人绕过（直接写 SQL），
        --      外键不能——但如果**函数自己**不再检查，语言层的归因质量就没了。
        --   ⇒ 处置：断言 `SQLSTATE = P0001`（PL/pgSQL 的 RAISE EXCEPTION 默认码）
        --      且消息里含"客户"。23503 会在这里报红，且归因正确（"被拒绝了，
        --      但不是函数层的客户存在性检查"）。
        --   🛑 与 (e8) 的分工：e8 断言**库层**复合外键必须给出 23503；
        --      e3 断言**函数层**必须给出 P0001。两条互补，缺任一条都有一类漏网：
        --      只有 e8 ⇒ 函数层检查被摘掉也全绿（本条要修的）；
        --      只有 e3 ⇒ 库层外键被回退也全绿（e8 要修的）。
        --   🛑 为什么断言的是 `IS DISTINCT FROM 'P0001'` 而不是 `<> 'P0001'`：
        --      v_state 可能为 NULL（若某实现吞掉异常），`NULL <> 'P0001'` 结果是
        --      NULL ⇒ IF 不进入 ⇒ **静默假绿**。IS DISTINCT FROM 是 NULL-safe 的。
        IF v_state IS DISTINCT FROM 'P0001' THEN
            RAISE EXCEPTION
                'V20 自证失败(e3): 跨租户客户引用虽然被拒绝了，但 SQLSTATE = %（期望 P0001）'
                '—— 这说明【函数层的客户存在性检查 (4) 没有生效】，'
                '拒绝来自别处（23503 = 库层复合外键 case_archive_customer_id_fkey）。'
                '消息 = %。'
                '🛑 为什么这不是"反正都拒绝了，无所谓"：'
                '函数层的检查给出的是**可归因**的失败（指明"客户 X 在租户 Y 内不存在"'
                '并附上本次上下文的实际值）；库层外键只给出一条 23503。'
                '归档是**举证材料**，一次"挂到别人客户名下"的调用必须由函数层在写之前拒绝，'
                '并告诉调用方原因 —— 而不是让它走到写入、再被外键以一种与业务无关的方式拒掉。'
                '🛑 反向验证（121 的 I(e3)）已实测：把 (4) 摘掉后，'
                '若本判据只断言"抛了异常"，自证会**照常打印通过**（静默假绿）。',
                coalesce(v_state, '<NULL>'), coalesce(v_msg, '<NULL>');
        END IF;
        IF v_msg IS NULL OR v_msg !~ '客户' THEN
            RAISE EXCEPTION
                'V20 自证失败(e3): 抛了 P0001，但消息里没有"客户"二字（实际消息 = %）。'
                '🛑 断言消息内容是为了证明抛的是**这条**分支（客户存在性检查），'
                '而不是函数里别的某条 RAISE —— 一个"任何输入都抛错"的实现同样能让"抛了"成立。',
                coalesce(v_msg, '<NULL>');
        END IF;
        -- 🛑🛑 2026-09-27（121 的补强，与本用例的"函数层 23503 兜底"形态互补）：
        --   (4) 的客户存在性检查被摘掉时，**不一定会**落到库层外键上：
        --   若插入的 customer_id 在本租户内**不存在任何对应行**，库层外键同样会拒
        --   （23503）；但若某实现把 (4) 摘掉却仍在 INSERT 之前做了别的检查，
        --   就可能得到别的 SQLSTATE。⇒ 上面那条 `IS DISTINCT FROM 'P0001'` 已覆盖
        --   "非 P0001"的全部情形。此处再补一条**更严格的语义断言**：
        --   (4) 被摘掉时，v_cust 那个变量**根本不会被用来判定** ⇒ 本断言通过
        --   "函数层是否真的基于 v_cust 做了判定"来区分。但那是静态断言的事
        --   （见 (b) 系列）；行为层能拿到的最强证据就是上面两条。故到此为止。
        IF EXISTS (SELECT 1 FROM case_archive WHERE archive_id = v_arc_a5) THEN
            RAISE EXCEPTION
                'V20 自证失败(e3): 被拒绝的那次归档竟然落库了（archive_id = %）—— '
                '说明 (4) 的检查发生在写入【之后】，或者根本没有生效。', v_arc_a5;
        END IF;

        -- ---------------------------------------------------------------
        -- e4 🛑 硬门禁缺项 → RAISE，且**消息里必须含缺项名称**
        --    P0-25 逐字要求「硬阻断规则缺项 → 后端返回 403 且**给出缺失项名称**」。
        --    应用层要把这个名称放进 403 的响应里，故它必须真的出现在库层消息里 ——
        --    否则应用层只能自己重新推导一遍"缺了哪项"，而那是第二份口径（必然分叉）。
        --    本用例选 owner_signed（ARC-12）缺席，其余 4 项为 true。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a2, v_cust_a1,
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,'
                '"archive_plan_exec_archived":true}'::jsonb,
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e4): 硬门禁缺项（owner_signed 缺席）时归档没有抛错（返回值 %）。'
                'PRD C.1.7 逐字：「case_archive.archive_checklist | 是 | **缺项→403 阻断结案**」，'
                'P0-25 逐字：「硬阻断规则缺项 → 后端返回 403 且给出缺失项名称」。'
                '🛑 这是本迁移**唯一**一条会阻断结案的门禁，缺了它 '
                '"9.1 项齐备"就退化成一句没有实现的话。', v_mode;
        END IF;
        IF v_msg IS NULL OR v_msg !~ 'owner_signed' THEN
            RAISE EXCEPTION
                'V20 自证失败(e4): 硬门禁缺项虽然抛了错，但消息里没有缺项名称 owner_signed（实际消息 = %）。'
                '🛑 断言消息内容有两个作用：'
                '① 证明抛的是**这条**分支（一个"任何输入都抛错"的实现同样能让"抛了"成立）；'
                '② P0-25 逐字要求"给出缺失项名称"，而应用层要把这个名称放进 403 响应 —— '
                '    若库层不给出，应用层只能自己再推导一遍（第二份口径，必然分叉）。',
                coalesce(v_msg, '<NULL>');
        END IF;
        -- 🛑（121 补强）同时断言 SQLSTATE = P0001：把"抛的是 PL/pgSQL 的 RAISE 分支"
        --   变成机械事实，而不是"抛了任何异常都算"。见 (e3) 的同款说明。
        IF v_state IS DISTINCT FROM 'P0001' THEN
            RAISE EXCEPTION
                'V20 自证失败(e4): 硬门禁缺项虽然被拒绝了，但 SQLSTATE = %（期望 P0001）—— '
                '说明拒绝不是来自函数层的门禁 RAISE。消息 = %。',
                coalesce(v_state, '<NULL>'), coalesce(v_msg, '<NULL>');
        END IF;
        IF EXISTS (SELECT 1 FROM case_archive WHERE archive_id = v_arc_a2) THEN
            RAISE EXCEPTION
                'V20 自证失败(e4): 被硬门禁拒绝的那次归档竟然落库了（archive_id = %）—— '
                '说明校验发生在写入【之后】。'
                '🛑 这是本迁移最不能接受的形态：一次失败的归档留下半份档案，'
                '而它是**举证材料** —— 半份举证比没有更坏。', v_arc_a2;
        END IF;

        -- ---------------------------------------------------------------
        -- e5 🛑🛑🛑 合规红线（行为防线）：手环项【缺席】与【为 false】都**不得阻断**
        --
        --   PRD P0-25 逐字：「警告规则仅记缺口 · **手环类缺项不得反转为阻断**」
        --   README §5.3「三条不得触碰」③ 逐字：
        --      「**任何"手环缺项反转为阻断"的写法一律违规**（P0-25 + ARC-07/08/11）」
        --   C.3 的划定：ARC-07 未佩戴（标"未佩戴"、不阻断、不作不利依据）·
        --               ARC-08 已同意佩戴却无记录（警告不阻断）·
        --               ARC-11 未按"参考之一"口径记录（警告不阻断）
        --
        --   ⇒ 本用例是**两条正例**：
        --      e5-a 清单里**完全没有** handband 键 ⇒ 必须 CREATED
        --      e5-b 清单里 handband = **false**       ⇒ 必须 CREATED
        --      两者都要求其余 5 项硬门禁为 true（否则红的是硬门禁，归因错误）。
        --
        --   🛑 为什么这一条必须有独立的"正例"断言，而不能只靠 (b12) 的静态断言：
        --      (b12) 证明的是"键不在硬门禁数组里"，而它**推不出**"传进来的 false 不会被拦"——
        --      拦的位置可能不是那个数组（例如有人在别处补了一句 IF）。
        --      静态断言证明"结构"，行为断言证明"效果"，两者缺一不可。
        --   🛑 为什么必须用内层 BEGIN/EXCEPTION 包住（"必须成功"的用例也要包）：
        --      若它意外抛了，后面的断言会以"事务被终止"失败，
        --      从而**掩盖住"手环被反转为阻断"这个真因** —— 一条归因错误的红。
        -- ---------------------------------------------------------------
        -- e5-a：手环键【缺席】
        v_accepted := false;
        v_msg := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a3, v_cust_a1,
                -- 🛑 这份清单只有 5 个硬门禁键，**没有** handband 键
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true}'::jsonb,
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_msg = MESSAGE_TEXT;
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V20 自证失败(e5-a): 🛑🛑 清单里【完全没有】手环项时，归档被拒绝了 —— '
                '**这是合规红线违规**。消息 = %。'
                'PRD P0-25 逐字：「警告规则仅记缺口 · **手环类缺项不得反转为阻断**」；'
                'README §5.3「三条不得触碰」③ 逐字：'
                '「**任何"手环缺项反转为阻断"的写法一律违规**（P0-25 + ARC-07/08/11）」。'
                'C.3 里 ARC-07/08/11 三条**全部是"警告不阻断"**。'
                '🛑 手环数据是"参考之一"（PRD W-3 逐字），'
                '把它变成阻断等于用一条数据缺口去阻止结案。'
                '正确处置：把该键放回 v_warn_gate_keys（缺失或 false 都不阻断）。',
                coalesce(v_msg, '<无异常>');
        END IF;
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V20 自证失败(e5-a): 手环项缺席时归档返回 %（期望 CREATED）', v_mode;
        END IF;

        -- e5-b：手环键 = false
        v_accepted := false;
        v_msg := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a4, v_cust_a1,
                -- 🛑 这份清单明写 handband_recorded_as_reference = **false**
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true,'
                '"handband_recorded_as_reference":false}'::jsonb,
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_msg = MESSAGE_TEXT;
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V20 自证失败(e5-b): 🛑🛑 手环项为 false 时归档被拒绝了 —— **这是合规红线违规**。'
                '消息 = %。'
                '🛑 与 e5-a 同理：false 的含义是"没有按参考之一口径记录"（ARC-11），'
                '而 ARC-11 是**警告不阻断**。'
                '🛑 注意区分两种 false 的语义：'
                '  · 键在、值 false ⇒ 警告，**不阻断**（本用例）；'
                '  · 键在、值不是布尔（例如字符串 "true"）⇒ 口径错误，**阻断**（见 (5c)）。'
                '    后者不是"手环缺口"，而是"这件事根本没被表达成一个是/否"——'
                '    故它与"不得反转为阻断"不冲突。',
                coalesce(v_msg, '<无异常>');
        END IF;
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V20 自证失败(e5-b): 手环项为 false 时归档返回 %（期望 CREATED）', v_mode;
        END IF;

        -- ---------------------------------------------------------------
        -- e6 🛑🛑 跨租户撞号 —— 本迁移的核心用例
        --    租户 A 先占用 v_arc_shared；然后切到租户 B 用**同一 archive_id** 归档。
        --    必须 RAISE（不是 ALREADY_EXISTS），且消息里必须含"另一租户"。
        --    🛑 为什么必须断言"消息含另一租户"而不只是"抛了异常"：
        --       一个"任何输入都抛错"的实现同样能让"抛了"成立；
        --       断言消息内容证明抛的是**这条**分支，不是别的。
        --       同时这也把"返回值在撒谎"这种修法钉死 —— 它不允许返回 ALREADY_EXISTS。
        -- ---------------------------------------------------------------
        v_mode := register_case_archive(
            v_ta, v_arc_shared, v_cust_a1,
            '{"reason_recorded":true,"baseline_review_compared":true,'
            '"retention_recorded":true,"owner_signed":true,'
            '"archive_plan_exec_archived":true}'::jsonb,
            '{"handler":"张三","meridian_therapist":"李四",'
            '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V20 自证失败(e6): 准备阶段（租户 A 先占用 %）返回 %（期望 CREATED）',
                v_arc_shared, v_mode;
        END IF;

        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        BEGIN
            v_mode := register_case_archive(
                v_tb, v_arc_shared, v_cust_b1,
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true}'::jsonb,
                '{"handler":"赵六","meridian_therapist":"钱七",'
                '"store_owner":"孙八","sign_date":"2026-09-27"}'::jsonb);
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e6): 租户 B 用【租户 A 已占用的 archive_id】归档没有抛错'
                '（返回值 = %）。🛑 这是本迁移的核心机制二的封堵点：'
                '`ON CONFLICT (archive_id) DO NOTHING` 在这里会【静默 DO NOTHING 且 ROW_COUNT=0】，'
                '若函数只按 ROW_COUNT 判定，就会对租户 B 返回 ALREADY_EXISTS —— '
                '而租户 B 里那一行**看不见**（RLS），即"返回值在撒谎"：'
                '调用方据此把 refund.outcome 置成 ''归档'' ⇒ '
                '**一张退款工单被标记为"已归档"，而它的归档档案不存在**。'
                '🛑 本域比 device 域更隐蔽：归档是末端收口动作，下游没有第二步去证伪它，'
                '这个缺口只在举证时暴露。', v_mode;
        END IF;
        IF v_msg IS NULL OR v_msg !~ '另一租户' THEN
            RAISE EXCEPTION
                'V20 自证失败(e6): 跨租户撞号虽然抛了错，但消息里没有"另一租户"（实际消息 = %）。'
                '🛑 断言消息内容是为了证明抛的是**这条**分支：'
                '一个"任何输入都抛错"的实现同样能让"抛了异常"成立。', coalesce(v_msg, '<NULL>');
        END IF;
        -- 🛑（121 补强）SQLSTATE 必须 = P0001（PL/pgSQL 的 RAISE 默认码）。
        --   本用例是**靶心用例**：若有人把跨租户分支从 `RAISE` 改成"写入前的某条
        --   库层拒绝"（例如靠一个额外的唯一索引），SQLSTATE 会变成 23505 —— 
        --   那同样"拒绝了"，但函数层的可归因消息就没了。见 (e3) 的同款说明。
        IF v_state IS DISTINCT FROM 'P0001' THEN
            RAISE EXCEPTION
                'V20 自证失败(e6): 跨租户撞号虽然被拒绝了，但 SQLSTATE = %（期望 P0001）—— '
                '说明拒绝不是来自函数层那条 RAISE 分支。消息 = %。',
                coalesce(v_state, '<NULL>'), coalesce(v_msg, '<NULL>');
        END IF;
        -- 租户 B 里必须仍然一行关于这个 archive_id 的行都没有
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM case_archive WHERE archive_id = v_arc_shared) THEN
            RAISE EXCEPTION
                'V20 自证失败(e6): 被拒绝的那次跨租户归档竟然在租户 B 里落库了（archive_id = %）',
                v_arc_shared;
        END IF;

        -- e6 的对照：租户 B 用自己的 archive_id 归档必须成功
        --   🛑 没有这一条，一个"只要 archive_id 在别处存在就抛错"的实现也能过 e6。
        v_mode := register_case_archive(
            v_tb, v_arc_b_new, v_cust_b1,
            '{"reason_recorded":true,"baseline_review_compared":true,'
            '"retention_recorded":true,"owner_signed":true,'
            '"archive_plan_exec_archived":true}'::jsonb,
            '{"handler":"赵六","meridian_therapist":"钱七",'
            '"store_owner":"孙八","sign_date":"2026-09-27"}'::jsonb);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V20 自证失败(e6-对照): 租户 B 用自己的 archive_id 归档返回 %（期望 CREATED）—— '
                '跨租户守卫把正常路径也打死了。', v_mode;
        END IF;

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e7 对照：同租户裸 INSERT（不同 archive_id）必须【成功】
        --    🛑 一个"拒绝一切"的实现同样能让 e3/e4/e6 通过，故必须有本条对照。
        -- ---------------------------------------------------------------
        v_accepted := false;
        BEGIN
            INSERT INTO case_archive (archive_id, tenant_id, customer_id,
                                      archive_checklist, staff_signs)
            VALUES (v_arc_a8, v_ta, v_cust_a1, '{}', '{}');
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V20 自证失败(e7): 同租户裸 INSERT 被误拒 —— 正常路径被挡住了。'
                '最可能的成因：某条约束在函数之外也把正常写入拒了，'
                '那会让"归档通路"看起来可用而实际上只有函数能写。'
                '🛑 注意本条**故意**传空的清单与签名（''{}'' / ''{}''）：'
                '它同时证明"6 项门禁**只**在函数里、不是表约束"（见文件头「核心机制四」），'
                '并且保护 RlsV5EntityIsolationTest 那条用同款探针的门禁。';
        END IF;

        -- ---------------------------------------------------------------
        -- e8 🛑 跨租户客户引用：租户 A 里把 case_archive.customer_id 指向租户 B 的客户
        --    必须被【库层复合外键】拒，且理由必须是 23503 而不是 42501。
        --    🛑 为什么必须绕过函数：函数的 (4) 也会拒绝它 —— 于是"被拒绝了"这件事
        --       无法区分"函数层拦住了"与"库层拦住了"。而 V16 刚把
        --       case_archive_customer_id_fkey 换成 (tenant_id, customer_id) 复合外键，
        --       这里要证的正是【库层那道】仍有效（防御纵深：函数可以被人绕过，外键不能）。
        --    🛑 为什么区分 23503 与 42501：两者都"拒绝"，但含义完全不同 ——
        --       23503 = 复合外键生效（V16 的成果）；
        --       42501 = RLS 的 WITH CHECK 拒绝（说明上下文不对，而不是外键在起作用）。
        --       只断言"抛了异常"会让一次上下文写错的实现假通过。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_state := NULL;
        v_msg := NULL;
        BEGIN
            INSERT INTO case_archive (archive_id, tenant_id, customer_id,
                                      archive_checklist, staff_signs)
            VALUES (v_arc_ab, v_ta, v_cust_b1, '{}', '{}');
            v_rejected := false;
        EXCEPTION
            WHEN foreign_key_violation THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
            WHEN insufficient_privilege THEN
                v_rejected := false;   -- 42501 不算"被外键拒绝"
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e8): 跨租户客户引用【未被外键拒绝】——'
                '在租户 A 的上下文里，case_archive 行成功把 customer_id 指向了租户 B 的客户。'
                '这说明 V16 的复合外键 case_archive_customer_id_fkey (tenant_id, customer_id) '
                '→ customer (tenant_id, id) 没有生效。'
                '🛑 意义：归档档案的跨租户完整性不能只靠 register_case_archive 的入参检查 —— '
                '函数可以被人绕过（直接写 SQL），外键不能。'
                '（若 SQLSTATE = % 而非 23503，那是 RLS 拒绝，说明上下文不对 —— '
                '它同样"拒绝"，但证明的是完全不同的事。）', coalesce(v_state, '<无>');
        END IF;

        -- ---------------------------------------------------------------
        -- e9 latest_archive_of 的行为（三段）
        -- ---------------------------------------------------------------
        -- e9-a 尚未归档的客户 → NULL（不是异常）
        v_latest := latest_archive_of(v_ta, v_cust_a2);
        IF v_latest IS NOT NULL THEN
            RAISE EXCEPTION
                'V20 自证失败(e9-a): 对一个**尚无归档档案**的客户，latest_archive_of 返回了 %（期望 NULL）。'
                '🛑 "尚未归档"是一个正常的业务状态，必须表达为 NULL 而不是异常 ——'
                '做成异常会逼调用方用 try/catch 表达一个正常判断，'
                '而那正是"异常被吞掉"的温床。', v_latest;
        END IF;

        -- e9-b 已归档的客户 → 返回该客户的档案
        v_latest := latest_archive_of(v_ta, v_cust_a1);
        IF v_latest IS NULL THEN
            RAISE EXCEPTION
                'V20 自证失败(e9-b): 客户 % 已有归档档案，但 latest_archive_of 返回 NULL。'
                '🔴 最常见成因：函数没建上下文（FORCE RLS ⇒ USING 恒 false ⇒ 恒 NULL），'
                '于是"没归档"与"我没设上下文"给出同一个答案 —— 一个典型的假绿。', v_cust_a1;
        END IF;

        -- e9-c 🛑 tie-breaker：同 archived_at 的两份档案，必须返回 archive_id 较大者
        --   本用例专门构造"同一时刻"：两份都显式写同一个 archived_at。
        --   🛑 这一条证明 (c2) 那处 ORDER BY 的第二个键**有效**。
        --      没有它，本断言会以"返回了较小者"的面目**随机**红或绿 ——
        --      即一条时红时绿的门禁（本仓最忌讳的形态之一）。
        v_ts := timestamptz '2026-09-27 12:00:00+08';
        INSERT INTO case_archive (archive_id, tenant_id, customer_id,
                                  archive_checklist, staff_signs, archived_at)
        VALUES (v_arc_a9, v_ta, v_cust_a2, '{}', '{}', v_ts),
               (v_arc_aa, v_ta, v_cust_a2, '{}', '{}', v_ts);

        v_latest := latest_archive_of(v_ta, v_cust_a2);
        IF v_latest IS DISTINCT FROM v_arc_aa THEN
            RAISE EXCEPTION
                'V20 自证失败(e9-c): 同一 archived_at 的两份档案，latest_archive_of 返回 %（期望 %）。'
                '🛑 tie-breaker 由 archive_id 的**字面序**决定（较大者视为较新）——'
                '这里 v_arc_aa 的字面值（…da）大于 v_arc_a9（…d9）。'
                '若这里返回了较小者或 NULL，说明 ORDER BY 的第二个键没生效 ⇒ '
                '本函数在同一时刻写入多份时会**非确定**：两次调用可能给出不同答案。'
                '这与 RefundWorkOrderPort.findLatestStatement 注释里记载的是同一族教训。',
                coalesce(v_latest::text, '<NULL>'), v_arc_aa;
        END IF;

        -- ---------------------------------------------------------------
        -- e10 清单含【未登记键】→ RAISE
        --   🛑 用内层 BEGIN/EXCEPTION 包住（同 e3/e4 的理由）。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a6, v_cust_a1,
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true,'
                '"reason_recoded":true}'::jsonb,   -- 🛑 拼错的键（少了一个 r）
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"王五","sign_date":"2026-09-27"}'::jsonb);
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e10): 清单含【未登记键】（reason_recoded，拼错）时归档没有抛错（返回值 %）。'
                '🛑 不得静默忽略未登记键 —— 忽略会让一次拼写错误同时产出两个后果：'
                '① 对应的合法键 reason_recorded 被判缺失（若它同时也缺席）；'
                '② 调用方以为自己填了 —— 而下一次他会再犯。'
                '这与 ConsentAuthScope 对未知键的严格解析同族（那边"缺键合法、未知键必抛"，'
                '这边更严："缺键与未知键都不合法"）。', v_mode;
        END IF;
        -- 🛑（121 补强）必须证明抛的是**未登记键**那条分支，而不是别的 RAISE：
        --   注意本用例的清单**5 项硬门禁全部为 true**，故若有任何一条别的检查
        --   （例如误把 reason_recoded 当作 reason_recorded 缺失）先抛，本条断言会抓住它。
        IF v_state IS DISTINCT FROM 'P0001' OR v_msg IS NULL OR v_msg !~ '未登记键' THEN
            RAISE EXCEPTION
                'V20 自证失败(e10): 抛了，但不是"未登记键"那条分支'
                '（SQLSTATE = %，消息 = %）。'
                '🛑 未登记键的处理必须是**独立的**一条分支：若它落到"硬门禁缺项"上，'
                '则一次拼写错误会被报成"某项缺失"（归因**部分**正确，但掩盖了真因），'
                '而调用方仍不知道自己填错了键名。',
                coalesce(v_state, '<NULL>'), coalesce(v_msg, '<NULL>');
        END IF;
        IF EXISTS (SELECT 1 FROM case_archive WHERE archive_id = v_arc_a6) THEN
            RAISE EXCEPTION
                'V20 自证失败(e10): 被拒绝的那次归档竟然落库了（archive_id = %）', v_arc_a6;
        END IF;

        -- ---------------------------------------------------------------
        -- e11 签名块缺项 / 空串 → RAISE
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        BEGIN
            v_mode := register_case_archive(
                v_ta, v_arc_a7, v_cust_a1,
                '{"reason_recorded":true,"baseline_review_compared":true,'
                '"retention_recorded":true,"owner_signed":true,'
                '"archive_plan_exec_archived":true}'::jsonb,
                -- 🛑 store_owner 是**空串**（不是缺键）—— 两种都该被拒
                '{"handler":"张三","meridian_therapist":"李四",'
                '"store_owner":"","sign_date":"2026-09-27"}'::jsonb);
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V20 自证失败(e11): 签名块的 store_owner 是空串时归档没有抛错（返回值 %）。'
                'PRD C.1.7 逐字：case_archive.staff_signs = json{经办人,经络师,门店负责人,日期} | 是；'
                '§2.22 逐字："归档须客户签字"。'
                '🛑 DDL 的 NOT NULL 只能保证 staff_signs 这个 JSONB 不为空，'
                '挡不住它里面是 {{}} 或 {{"store_owner":""}} —— 而一个空白的"门店负责人"'
                '不是签字。故必须由本函数拒绝。', v_mode;
        END IF;
        -- 🛑（121 补强）必须证明抛的是**签名块**那条分支（而非清单门禁）。
        --   本用例的清单 5 项全 true ⇒ 若签名检查被摘掉、而 (5b) 误判清单缺失，
        --   本条会报红并指出真因。
        IF v_state IS DISTINCT FROM 'P0001' OR v_msg IS NULL OR v_msg !~ '签名块' THEN
            RAISE EXCEPTION
                'V20 自证失败(e11): 抛了，但不是"签名块缺项或为空"那条分支'
                '（SQLSTATE = %，消息 = %）。'
                '🛑 签名块与清单门禁是**两条**独立分支（一个判"是与否"，一个判"谁+何时"），'
                '它们必须各自可归因 —— 合并成一条会让 403 的响应里说不清"到底缺哪一类"。',
                coalesce(v_state, '<NULL>'), coalesce(v_msg, '<NULL>');
        END IF;
        IF EXISTS (SELECT 1 FROM case_archive WHERE archive_id = v_arc_a7) THEN
            RAISE EXCEPTION
                'V20 自证失败(e11): 被拒绝的那次归档竟然落库了（archive_id = %）', v_arc_a7;
        END IF;

        -- ---------------------------------------------------------------
        -- e12 清场前的正向计数：证明确实有行写进去过
        --    （否则下面的 DELETE 是空操作，"清场成功"与"什么都没发生"同形）
        --    🛑 本迁移的探针不写 store ⇒ 清场只需 case_archive → customer → tenant 三层。
        -- ---------------------------------------------------------------
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        SELECT count(*) INTO v_cnt FROM case_archive WHERE tenant_id = v_ta;
        IF v_cnt < 5 THEN
            RAISE EXCEPTION
                'V20 自证失败(e12): 租户 A 的 case_archive 行数 = %'
                '（期望 ≥5：e1 / e5-a / e5-b / e6准备 / e7 / e9-c 共 6 处写入，'
                '其中 e7 与 e9-c 各 1 或 2 行）。'
                '这条断言的作用是让下面的"清场后为 0"有意义 —— 若本来就没写进去，'
                '那么"清场成功"与"什么都没发生"给出同一个 0。', v_cnt;
        END IF;

        -- ==============================================================
        -- (f) 探针清场 + 清场自证
        --   🛑 必须【逐个租户在自己的上下文里】删 —— V16 / V17 / V18 的自证块都踩过这个坑：
        --       FORCE RLS 下 DELETE 在错误的上下文里会【静默删 0 行且不报错】，
        --       于是残留下来，在下一次运行本迁移时以"INSERT 撞主键"的面目出现，
        --       而那时没人记得它来自上一次探针。
        --   🛑 删除顺序必须与外键依赖倒序：
        --       case_archive 引用 customer / tenant ⇒ 先删 case_archive，再删 customer，
        --       再删 tenant。写反了的表现是"在 customer 上的删除违反了 case_archive 上的外键约束"。
        --   🛑 本表的清理比 V18 简单一层：case_archive 没有 store 依赖，
        --       且**没有下游引用它**（实测无任何外键指向 case_archive）。
        -- ==============================================================
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        DELETE FROM case_archive WHERE tenant_id = v_ta;
        DELETE FROM customer     WHERE tenant_id = v_ta;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        DELETE FROM case_archive WHERE tenant_id = v_tb;
        DELETE FROM customer     WHERE tenant_id = v_tb;

        -- tenant 表无 RLS，可直接删
        DELETE FROM tenant WHERE id IN (v_ta, v_tb);

        -- (f2) 清场自证：逐租户设上下文检查（否则"删干净了"与"我看不见"同形）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        IF EXISTS (SELECT 1 FROM case_archive WHERE tenant_id = v_ta) THEN
            RAISE EXCEPTION
                'V20 自证失败(f2): 探针清场不彻底（租户 A 的 case_archive 仍有残留）。'
                '残留会在下一次运行时表现为"INSERT 撞主键"，而那时没人记得它来自本次探针。';
        END IF;
        IF EXISTS (SELECT 1 FROM customer WHERE tenant_id = v_ta) THEN
            RAISE EXCEPTION 'V20 自证失败(f2): 探针清场不彻底（租户 A 的 customer 仍有残留）';
        END IF;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM case_archive WHERE tenant_id = v_tb) THEN
            RAISE EXCEPTION 'V20 自证失败(f2): 探针清场不彻底（租户 B 的 case_archive 仍有残留）';
        END IF;
        IF EXISTS (SELECT 1 FROM customer WHERE tenant_id = v_tb) THEN
            RAISE EXCEPTION 'V20 自证失败(f2): 探针清场不彻底（租户 B 的 customer 仍有残留）';
        END IF;

        IF EXISTS (SELECT 1 FROM tenant WHERE id IN (v_ta, v_tb)) THEN
            RAISE EXCEPTION 'V20 自证失败(f2): 探针清场不彻底（探针租户仍有残留）';
        END IF;
    END;

    RAISE NOTICE 'V20 自证通过: 函数 2 / 登记 1 / 函数体断言全中 / '
                 '归档两态齐备（含跨租户撞号 RAISE）/ 客户不存在被拒且不落库 / '
                 '硬门禁缺项被拒且消息含缺项名 / 🛑 手环项缺席与 false 均不阻断（合规红线） / '
                 '未登记键与签名空串被拒 / 跨租户客户引用被外键拒(23503)且同租户放行 / '
                 'latest_archive_of 三段（NULL / 命中 / tie-breaker 全序）/ 探针零残留';
END;
$v20_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   见文件头【回滚说明】。要点重述：
--     · 本迁移不建表、不写业务行 ⇒ 回滚无数据损失风险；
--     · 🛑 但**不要**用回滚来处理"case_archive 表里已经有归档档案"这类情况 ——
--       那些行是数据。它们的存在意味着**结案收口现在能落地了**，
--       把函数删掉会让"归档"这个动作重新变成不可实现
--       （而 refund.outcome 可能已经有 '归档' 值 —— 那会造出
--         "工单说已归档、归档档案查不到"的悬空状态）。
--     · 🛑 回滚**不会**恢复"第三种可能"：若某个实现（或运维手工 SQL）
--       依赖了本迁移的跨租户撞号 RAISE，删掉函数后那个保护也不存在了。
--       故回滚前须先确认没有调用方在依赖本原语。
-- ============================================================================