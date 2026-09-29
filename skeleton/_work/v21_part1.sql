-- ============================================================================
-- V21 迁移: 协议签署通路（agreement 的【离线签署】登记原语）
--
-- 【这个迁移为什么存在 —— 与 B-10 / B-11 / B-12 / B-13 同型，但【边界条件最复杂】】
--
--   `ProvisioningBoundaryGateTest` 把 `agreement` 登记在「未开通账」里。
--   这是**第五次边界移动**（tenant/region/store/staff ← B-7 · band ← B-10 ·
--   device ← B-11 · scale ← B-12 · case_archive ← B-13）。
--
--   本迁移与前五次的【共同形态】完全一致：
--     一张 FORCE RLS 的业务表【零写入方】⇒ 依赖它的链在规格级不可用。
--
--   但本迁移的【边界条件】是六次里最复杂的，因为它同时被两条**方向相反**的
--   约束夹着 —— 交付规划里把它列为「A-1 的特殊风险（必须显式登记，不得静默）」：
--
--     约束甲 · PRD G1 逐字要求「协议签署合规率 = 100%」这一指标的**取数**；
--              而 `agreement` 是那 100% 的**唯一载体**。
--     约束乙 · 契约 H1（`POST /esign/callbacks/{provider}`）逐字写着：
--              「🛑 本节只冻结形态……**厂商选定之前，三端不得据本节编码**」，
--              并把「厂商签名算法 / 字段名 / 载荷结构 / {provider} 取值域」
--              全部列在 `x-not-frozen` 里。
--
--   ⇒ 若把"agreement 有写入方"直接理解成"实现电子签"，本迁移就**违反约束乙**：
--     一个收不到验签的回调端点 = 一条"任何人都能构造报文把客户推进到
--     AGREEMENT_SIGNED"的通道（该结论已由 B-5 的 `EsignCallbackContractGateTest`
--     在构建期守护）。AGREEMENT_SIGNED 是 06 屏的门禁前置。
--
--   ⇒ 故本迁移只做**离线签署**（运维通路形态）：
--       · 形态：`register_agreement()` + `latest_agreement_of()` 两个 plpgsql 原语，
--         **无任何 HTTP 映射**；
--       · 它**不**解析、**不**验证、**不**接收任何厂商签名 ——
--         它的输入是"一份已经签好的协议"这件事的**结构化事实**：
--         四方签署人、签署时点、渲染稿快照与 hash、退款条款快照；
--       · `rendered_snapshot` / `rendered_hash` 的来源是**调用方**（离线出具的那一份），
--         不是本函数渲染的 —— 本函数**不做**契约 I8 的渲染（见下一节）。
--
--   ⇒ 于是"G1 取数"这件事在厂商冻结前**只能算「离线签署覆盖率」**，
--     不能算"电子签合规率"。**这条边界必须写进 G1 的口径说明**（已同步进
--     README 与本迁移的待裁登记），不得静默混用。
--
-- 【🛑🛑 本迁移【不是】契约 I8，也不是 H1 —— 三者的边界必须逐字说清】
--
--   契约里与协议签署相关的只有两行，而本迁移**都不是**：
--
--     · **I8 `POST /agreements/{id}/render`**（operationId `renderAgreement`，
--       `x-callable-roles: []`、`x-client-forbidden: true`）——
--       它是"签署时渲染（服务端 → 电子签厂商）"，契约把它标为
--       `x-frontier: 占位待冻结`。它要求"渲染结果必须落快照 + hash"，
--       而那两列正是 `agreement.rendered_snapshot` / `rendered_hash`。
--       🛑 本迁移**不实现 I8**，理由不是"还没做"，而是：
--         I8 的动作是"渲染并推给厂商"，其**出口**是厂商 —— 厂商未选定 ⇒
--         一个只渲染、不推送的 "I8" 是一个**假实现**（它会让调用方以为协议已送达）。
--         本迁移只接收渲染的**结果**（作为入参），把"谁渲染的"留在调用方。
--       🛑 这产生一条必须登记的后果：**`rendered_hash` 的"是否正确"在本层不可证** ——
--         见下方【待裁登记 · 第 2 条】与函数内 (5c)。
--
--     · **H1 `POST /esign/callbacks/{provider}`**（operationId `receiveEsignCallback`，
--       `x-callable-roles: []`）—— 契约逐字禁止此刻编码。**本迁移不实现它**，
--       且本迁移**不提供任何**能替代它的通路（见下方"为什么没有'确认签署'原语"）。
--
-- 【为什么协议签署通路【不】是 HTTP 端点（有意形态，不是没做完）】
--   逐条核对契约 `openapi-v1.0.0.yaml` 的全部 path：
--     · 没有任何 path 是"创建协议"或"登记签署事实"；
--     · 与 agreement 相关的**唯一** path 是 I8（渲染），而它 `x-callable-roles: []`
--       ⇒ 契约自己就写着"无客户端可见面"；
--     · H1 是"厂商回调"，属服务端对服务端，同样 `roles=[]`。
--   ⇒ 即：**契约从一开始就没有把"协议签署"设计成一次客户/门店动作**。
--     这与 B-7（组织开通）/ B-10（手环绑定）/ B-11（设备建档）/ B-12（量表建档）/
--     B-13（结案归档）完全同型：**契约化决策**。给签署登记加对外端点属契约 MAJOR 变更，
--     且要先回答契约回答不了的问题：「谁有权登记一份协议已签」
--     （能读协议 ≠ 有权自证客户已签 —— 后者会推进 AGREEMENT_SIGNED 门禁）。
--   ⇒ 故本迁移提供【数据库层原语】：一个幂等的 plpgsql 写入函数 + 一个读原语。
--     可被运维 psql 直接调用，也可被应用内**不对外暴露**的 AgreementService 调用
--     （与 B-7 / B-10 / B-11 / B-12 / B-13 同款）。
--
-- ============================================================================
-- 【🛑🛑🛑 本迁移与 V20 的【七处实质差异】—— 本文件最重要的对照表】
-- ============================================================================
--
--   V20（case_archive）是本迁移的**主要范式来源**，但 agreement 的表结构与业务
--   语义有七处不同，**每一处都会改变代码**。照抄 V20 会在这七处上出错：
--
--   ┌────┬──────────────────────┬─────────────────────────────┬──────────────────────────────┐
--   │ #  │ 维度                 │ V20 case_archive            │ V21 agreement                │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ①  │ 表的 CHECK 约束      │ **0 条**（断言要求 =0，     │ **恰 1 条**                  │
--   │    │                      │ 保护 RlsV5 的 42501 归因）  │ `ck_agreement_plan_version_  │
--   │    │                      │                             │ positive (plan_version>=1)`  │
--   │    │                      │                             │ ⇒ 断言改为"恰 1 条且逐字"    │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ②  │ 复合外键             │ 1 条（customer）            │ **2 条**（customer +         │
--   │    │                      │                             │ plan(plan_id, version)）     │
--   │    │                      │                             │ ⇒ 探针必须建真 plan 行       │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ③  │ 主键形态             │ 单列 archive_id             │ 单列 agreement_id（同型）    │
--   │    │                      │                             │ ⇒ 跨租户撞号判定**同款**     │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ④  │ 下游引用             │ 无                          │ 无（实测 0 条反向 FK）       │
--   │    │                      │                             │ ⇒ 清理链最前，**同型**       │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ⑤  │ 门禁性质             │ 6 项清单（5 硬 + 1 警告）， │ **四方签署 + 快照齐备**，    │
--   │    │                      │ 含"手环不得反转为阻断"红线  │ **无警告项**（无手环语义）   │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ⑥  │ 哈希列               │ 无                          │ **rendered_hash**            │
--   │    │                      │                             │ varchar(128) / SHA-256 hex   │
--   │    │                      │                             │ ⇒ 库层**只能校验形态**       │
--   ├────┼──────────────────────┼─────────────────────────────┼──────────────────────────────┤
--   │ ⑦  │ 可空引用列           │ effect_confirm_pdf（**不收**│ doc_template_id/version      │
--   │    │                      │ ——依赖未定的存储通路）      │ （**成对收 + 存在性校验**）  │
--   └────┴──────────────────────┴─────────────────────────────┴──────────────────────────────┘
--
--   🛑 ⑦ 的处置与 V20 **方向相反**，理由必须写清（否则看起来像不一致）：
--     V20 不收 `effect_confirm_pdf`，因为**文件存储通路尚未选定** ——
--       收一个"不知道指向哪里"的字符串会让那一列**看起来被填了**，
--       而实际填进去的是一个无法解析的引用（"死字段"形态）。
--     V21 **收** `doc_template_id/version`，因为：
--       · 它们不是文件引用，而是**模板行的主键指针**（无文件通路依赖）；
--       · PRD §2.27 把 `doc_template` 定义为**模板源**，而 agreement 是**快照** ——
--         "签的是当时那一版"这件事要能回答，就必须有这个指针；
--       · 且它们的可空性是 schema 事实（实测两列均 nullable）⇒
--         "未用模板"是一等情形，不是缺口。
--     ⇒ 两条处置不矛盾：**判据是"这一列的值此刻能否被解析"**，
--       而不是"这个字段重不重要"。
--
-- ============================================================================
-- 【🛑 核心机制一：签署登记必须【自己】建立 RLS 上下文】
-- ============================================================================
--   agreement（V5 §2.9）是 ENABLE + FORCE ROW LEVEL SECURITY，策略 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 WITH CHECK 为 false ⇒ INSERT **抛错**（不是静默零行 ——
--   静默零行发生在 SELECT 的 USING 上，这个区别很容易记反）。
--   故本函数把「建立上下文」+「自证上下文已生效」绑在读写之前。
--   🛑 与 V20 逐字同款，此处不复述全文理由。
--
-- ============================================================================
-- 【🛑🛑 核心机制二：`ON CONFLICT (agreement_id)` 在【跨租户撞号】时会【静默 DO NOTHING】】
-- ============================================================================
--
--   起因：`agreement_pkey` 是 **单列** 主键 `PRIMARY KEY (agreement_id)`
--   （实测 pg_constraint：`agreement_pkey | p | PRIMARY KEY (agreement_id)`）。
--   表上**没有** `(tenant_id, agreement_id)` 唯一载体 —— **与 case_archive 逐字同型**
--   （实测：agreement 上的唯一载体只有 pkey 一个）。
--   ⇒ 语义上「一份协议一生只属于一个租户」是**既有 schema 的既定事实**。
--
--   `INSERT ... ON CONFLICT (agreement_id) DO NOTHING` 的推断目标是那个**全局**主键，
--   故当 agreement_id 已被【别的租户】占用时：
--     · 本租户上下文里 SELECT 看不见那一行（RLS 的 USING 把它挡在外面）；
--     · 但主键冲突仍然发生 ⇒ PG 走 DO NOTHING ⇒ **ROW_COUNT = 0**；
--     · 且它**不报错**、**不改任何行**。
--
--   【本域的后果 —— 与 V20 不同，因为下游【存在】一个消费者】
--     V20 说"归档是链的末端，下游没有第二步去证伪它"。
--     本域的形态**更隐蔽一层**：签署登记的下游**有**一个消费者 ——
--     `CustomerGateGuard` 的 `PLAN_APPROVED → AGREEMENT_SIGNED` 跃迁
--     （实测该类第 140/159 行逐字登记了这两个状态）。
--     ⇒ 若照抄 `ROW_COUNT=0 ⇒ ALREADY_EXISTS`，调用方会据此推进该客户到
--       AGREEMENT_SIGNED —— 而**这个客户在本租户下一份协议都没有**。
--       后果不是"一个字段错了"，而是**一个客户被放进了一条它没有资格走的门**：
--       06 屏（首次调理）被放行，而协议从未被签署。
--     🛑 且这条缺口**不会报错、不会 23503、不会被任何下游抓住** ——
--       它只在**追溯时**暴露：G1「协议签署合规率」取数时会发现
--       "已进入已签状态的客户，在 agreement 里查不到协议"。
--
--   【怎么修：用一个**无窗口**的判定，而不是"先读后写"】
--     判定链（三段，全部来自库层的原子事实）—— 与 V20 逐字同款：
--       ① INSERT ... ON CONFLICT (agreement_id) DO NOTHING
--          · ROW_COUNT = 1 ⇒ 本次真的建出来了 ⇒ 'CREATED'
--       ② ROW_COUNT = 0 ⇒ 全局已存在这一行。**它在谁名下？**
--          在已自证的**本租户上下文**里 `SELECT count(*) FROM agreement WHERE agreement_id = ...`
--          · 看得见（=1）⇒ 是本租户的 ⇒ 'ALREADY_EXISTS'（幂等，如实）
--          · 看不见（=0）⇒ 🛑 插不进去却又看不见 ⇒ **它在别的租户名下** ⇒ RAISE
--     为什么这**没有**"先读后写"的并发窗口：与 V20 同款 ——
--       "存在"这个前提不是来自我的读，而是来自**主键冲突**这个库层原子事实。
--     🛑 本仓这一族已有【四次】不同形态，不得互相照抄，此处一并记住：
--       · V17 `bind_band`：推断目标是**部分**唯一索引且**含 tenant_id** ⇒ 天然不误判；
--       · V18 `register_device`：推断目标是**全局**单列主键 ⇒ 必须自己补判定（首次遇到）；
--       · V19 `register_scale`：主要问题不是撞号而是 `scale_version` 是**死维度**；
--       · V20 `register_case_archive`：与 V18 同型（全局主键）；
--       · **V21（本迁移）**：与 V18/V20 同型，但**下游消费者存在** ⇒ 后果最直接。
--       ⇒ 五次里只有两次（V18/V20）是"全局单列主键"，本次是**第三次**。
--         这一族已成为本仓的**常态**（`agreement_id` / `archive_id` / `device_id`
--         都是单列全局主键），故本次不再把它写成"新发现"，而是写成**已确立的纪律**。
--
--   【为什么跨租户撞号用 RAISE 而不用返回值】
--     与 V18 / V20 逐字同款：同一份协议不可能同时属于两个租户。
--     它是**数据冲突**，需要人工介入（要么 agreement_id 传错了，要么协议被错误调拨）。
--     ⇒ 附带结论（值得逐字写清，免得下一个人以为漏了三态）：
--       **register_agreement 是两态**（CREATED / ALREADY_EXISTS）+ 若干类异常
--       （入参非法、客户不存在、方案版本不存在、签署人缺项、快照为空、
--         hash 形态错、模板指针不成对、签署时点缺失、跨租户撞号）。
--
-- ============================================================================
-- 【🛑🛑🛑 核心机制三：三门禁的【粒度】与四签约的纪律 —— 本迁移的合规落点】
-- ============================================================================
--
--   本迁移的门禁来自 PRD 三处，而它们描述的是**不同的东西**：
--
--     ① **PRD C.1.5 §八**（04 表 → agreement 字段映射）逐字：
--        「04.§八 四方签署 | `agreement.signatures` |
--          json{客户/经络师/调理师/门店负责人:签名+日期} | 是 |
--          **未签不得首次调理或退款判定** | **硬门禁**」
--     ② **PRD P0-27 / C.1.9 硬约束③**（文书模板与渲染）逐字：
--        「渲染结果必须落快照 + hash（否则事后无法证明"当时签的是哪一版"）」
--        并逐字给出**占位符白名单全集仅 6 个**：
--        `${store.name}` / `${customer.name}` / `${customer.phone_masked}` /
--        `${agreement.sign_date}` / `${plan.cycle_count}` / `${plan.version}`。
--     ③ **PRD C.1.7 硬门禁③**（第 7 项 gating 动作）：
--        「签调理协议书 | `agreement_signed` | **硬门禁③**」
--
--   ⇒ 本函数落三门禁（**没有任何警告项** —— 这是与 V20 的关键差别）：
--       (5a) **四方签署齐全**：`signer` 必须含且仅含 4 个已登记键，
--            每键值为**非空字符串**；未知键 ⇒ RAISE。
--            🛑 与 V20 的差别必须写清：V20 的清单是**布尔**（是与否），
--              签名块是**文本**（谁）；本域**两样都是文本**（谁 + 姓名/签名）——
--              因为 PRD §八 要求的是"签名+日期"，即**承载签署人身份**，
--              而不是"某人是否签了"。
--       (5b) **退款/终止条款快照必须非空**：`p_refund_clause_snapshot` 必须是
--            **非空 JSON 对象**（`{}` ⇒ RAISE）。理由：PRD §四 与 §2.9 逐字要求
--            「退款/终止条款**必须快照存储**……**未签阻断**」——
--            传 `{}` 等于"快照了零条条款"，那不是快照，是**占位**。
--       (5c) **渲染稿与 hash 齐备**：`rendered_snapshot` 非空；
--            `rendered_hash` 形态必须是 **64 位小写 hex**（SHA-256）。
--
--   🛑🛑 关于 (5c) 的一条**必须逐字写清的边界**（本迁移最容易被误解的地方）：
--
--     库层**不能**保证 `rendered_hash` 真的是 `rendered_snapshot` 的哈希。
--     原因是一条**实测事实**：`pg_extension` 里**只有 plpgsql** ——
--     `pgcrypto` **未安装** ⇒ `digest()` 不存在 ⇒ 库层无法重算 SHA-256。
--     ⇒ 本函数只能断言"这个字符串**看起来像**一个 SHA-256 摘要"，
--       不能断言"它就是这一版正文的摘要"。
--     ⇒ 故"签名与正文一致"这条性质的**真正守卫在应用层**：本仓既有
--       `DocFileService.sha256()`（实测该类在写 blob 前算、在读回后**重算比对**，
--       并逐字写着"完整性 + 防替换"）⇒ `AgreementService` 复用同一口径做重算。
--     🛑 **不允许**把这件事写成"库层已校验 hash"** —— 那是一句假话，
--       而它的后果是：下一个人会以为"只要过了库层门禁，哈希就可信"。
--       本仓对这一族有既定处置：**把不可证的部分显式登记为不可证**
--       （见下方【待裁登记 · 第 2 条】），而不是用一条看起来更严的 CHECK 掩盖它。
--     🛑 顺带说明**为什么不在表上加"hash 必须 64 位"的 CHECK**：
--       与 V20 核心机制四同款理由 —— 库层约束是**全局**的（对夹具/探针/数据修复
--       一并生效），而本判据是"签署动作"的业务门禁。且它会让
--       `RlsV5EntityIsolationTest` 的 agreement 探针（传 `'h'` 作为 hash）撞 CHECK
--       ⇒ 那条门禁会报 **23514** 而它说的是"WITH CHECK 没生效"——**归因错误的红**。
--       故本迁移第 0 节把"表上 CHECK 恰 1 条且为 ck_agreement_plan_version_positive"
--       写成机械断言：想加 CHECK 的人必须先显式改它，从而必然被看见。
--
-- ============================================================================
-- 【🛑 核心机制四：为什么【不】在库层加 CHECK 约束（与 V20 同款，但断言形态不同）】
-- ============================================================================
--   承上。V20 的做法是"断言 CHECK 数 = 0"；本迁移不能照抄，因为
--   agreement **本来就有 1 条 CHECK**（`ck_agreement_plan_version_positive`）。
--   ⇒ 本迁移的断言是"**恰 1 条，且定义逐字为该条**"，并给出这条 CHECK
--     为何**不会**污染既有门禁归因的实测理由：
--       `RlsV5EntityIsolationTest` 的 agreement 探针（实测第 683-685 行）
--       传的是 `plan_version = 1` ⇒ 满足 `plan_version >= 1` ⇒ 该 INSERT 仍会在
--       **RLS 的 WITH CHECK** 上以 42501 失败（而不是 23514）。
--     🛑 于是本断言比 V20 更精确：V20 说"必须 0"，本迁移说"**必须是这一条**"。
--       两者的共同目的相同 —— **让任何新增 CHECK 的改动必然被看见**，
--       因为新增的每一条都可能触发归因错误的红。
--
-- ============================================================================
-- 【为什么没有"确认签署/撤销签署"原语（与 V20 的"没有取消归档"对照）】
-- ============================================================================
--   本迁移只有 register_agreement + latest_agreement_of —— 这是**有意的**，
--   理由三条，其中第三条是本域**独有**的：
--     ① 签署是**终态事实**（它推进 AGREEMENT_SIGNED 门禁）。撤销签署不是本域的
--        合法动作：PRD 的替代路径是"重新出方案 → 重新签署**新的一份**协议"，
--        而不是抹掉旧的那份（旧的那份是**举证材料**）。
--     ② "未签"这个语义不该由 agreement 承担 —— 它由 `customer.state` 承担：
--        没有 agreement 行 = 未签。一行都没有的表**天然表达**了"未签"。
--     ③ 🛑🛑 **本仓最硬的一条理由（本域独有）**：任何"把协议置为已签/未签"的
--        第二通路，都**等于**开设一条绕过 H1 的旁路 —— 而 H1 之所以被禁止编码，
--        恰恰因为"没有验签就无法确认签署是真的"。提供 `confirm_signed()` 之类的
--        原语，等于把"未验签即可自证已签"这件事搬进库里，只是换了个入口。
--        ⇒ 故本迁移**连"更新"语句都不提供**（自证 (b10)/(b11) 静态断言这一点）。
--
-- ============================================================================
-- 【待裁登记（🛑 三条，均不得静默选一个 —— 本仓纪律：不确定就显式登记）】
-- ============================================================================
--
--   第 1 条 · `agreement.signer` 的**键名语言**与**日期归属**（两个子问题）
--     (a) 键名语言：PRD C.1.5 §八 用**中文名**描述（`json{客户/经络师/调理师/
--         门店负责人:签名+日期}`），而本仓既有 JSONB 范式（`ConsentAuthScope`
--         的 `auth_scope_json`、V20 的 `case_archive.staff_signs`）用的是
--         **snake_case 英文 code**。
--         🛑 本迁移的处置：**采用英文 snake_case** = `customer` /
--            `meridian_therapist` / `therapist` / `store_owner`
--            （与本仓既有范式一致、对"未知键必须抛"更可机械判定），
--            并**登记为待裁** —— 因为契约里没有任何地方冻结过这 4 个键的字面。
--     (b) 日期归属 —— 🛑 **这是一个真实的 PRD 落差，不是措辞问题**：
--         PRD §八 逐字写「**签名+日期**」（即每个签署人各自带日期），
--         而 `agreement` 表里只有**一列** `signed_at TIMESTAMPTZ NOT NULL`
--         （实测：16 列中没有 per-signer 的日期列，也没有 `signatures` 列 ——
--          承载四方签署的实际列名是 **`signer`**）。
--         ⇒ 库层**没有载体**表达"每人各自的签署日期"。
--         🛑 本迁移的处置：**`signer` 只承载 4 方身份；日期统一落在 `signed_at`**，
--            并把这条落差登记为待裁。三条候选路径（未定）：
--              (i)  接受现状：`signed_at` = 最后一个签署人的时点；
--              (ii) `signer` 的每个值改成对象 `{name, signed_at}`（JSONB 内嵌结构）；
--              (iii) 加一列 `signatures JSONB` 替代 `signer`（**改名+语义变更**）。
--            🛑 本迁移**不代拍**：选了 (ii)/(iii) 就意味着 `signer` 的结构变更，
--               而它在 PRD 附录 C.1 里是被逐字点名的字段（`04.§八 …… agreement.signatures`）。
--         🛑 另一处同源落差（一并登记）：PRD 附录 C.1 把**列名**写作 `signatures`
--            （复数），而真库列名是 **`signer`**（单数）。本迁移以**真库为准**，
--            并把"PRD 字段名与真库列名不一致"这件事登记在这里 ——
--            因为按 PRD 字面写的代码会在运行时以"列不存在"失败。
--
--   第 2 条 · `rendered_hash` 的**重算校验在库层不可证**（承核心机制三）
--     实测 `pg_extension` 只有 plpgsql ⇒ 无 `digest()` ⇒ 库层无法验 hash。
--     ⇒ 库层只校验**形态**（64 位小写 hex）；**重算比对在应用层**
--       （`AgreementService` 复用 `DocFileService.sha256()` 同一口径）。
--     🛑 三条候选（未定）：
--       (a) 维持现状（库层形态 + 应用层重算）—— 本迁移已实现；
--       (b) 在库里 `CREATE EXTENSION pgcrypto` 后由库层重算 ——
--           🛑 但装扩展是**环境级**变更（需要 superuser、需评估生产影响），
--           且会让迁移的**可移植性**下降（目标库必须允许装扩展）；
--       (c) 应用层算 hash 并把"已重算"flag 一并入库 —— 需要一个新列。
--     🛑 本迁移的处置：**(a)**，并逐字登记"库层不可证"这件事本身。
--       选择 (a) 的理由：它**不假装**库层验过了 —— 而 (b)/(c) 都要先动 schema
--       或环境，属超出本迁移权限的决定。
--
--   第 3 条 · **`agreement` 与 `customer.state` 之间的门禁**是**跨表**的，
--             本迁移**不做**（登记为已知边界，不是遗漏）
--     PRD §八 逐字写「**未签不得首次调理或退款判定**」，且第 7 项 gating 动作
--     逐字写「签调理协议书 → `agreement_signed` → **硬门禁③**」。
--     ⇒ 这条门禁的**消费点**在 `CustomerGateGuard`（实测该类已登记
--       `PLAN_APPROVED → AGREEMENT_SIGNED` 的跃迁），**不在本函数**。
--     🛑 为什么本函数做不到它：本函数**读不到** `customer.state`（那是 customer 域，
--        且本轮 A-1 的范围是"让 agreement 有写入方"）。若在这里硬写一条
--        "客户必须处于 PLAN_APPROVED"的判据，就等价于**代拍**"签署动作必须
--        紧跟方案通过"这条业务规则 —— 而 PRD 没有说"不能在别的状态下补签"
--        （现实中离线纸面协议完全可能晚于方案批准几天才登记）。
--     ⇒ 故本函数把"状态机一致性"**显式留给调用方/门禁层**，并登记。
--       这条登记与 V20 待裁第 3 条（refund ↔ case_archive 无关联列）**同族**：
--       都是"某条 PRD 规则需要一个本层拿不到的输入"。
--
-- 【幂等口径（与 V1~V20 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 函数天然支持 replace）
--   ② 写入侧 ON CONFLICT DO NOTHING + 返回两态（+ 若干 fail-closed 异常）
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（自证探针在一个子事务里跑，结束整体撤销）
--
-- 【🛑 本文件语句顺序不可调换：自检 → 授权 → 登记 → 自证 → 完成】
--   自证 (d) 断言 schema_migration 里 V21 登记行数 = 1，故【登记必须排在自证之前】。
--   与 V15 / V17 / V18 / V19 / V20 同款。
--   ⚠️ 这是本仓**第五次**在文件头写这句话。它与 description 长度上限一样，
--      属于"文件内注释无法自保"的纪律 —— 故 122 反向验证把"把登记挪到自证之后"
--      做成了**受控注入**（必须报 (d)）。本文件沿用同一形态。
--
-- 【回滚说明】
--   本迁移只新增两个函数、不建表、不写业务行。
--     DROP FUNCTION IF EXISTS register_agreement(uuid, uuid, uuid, uuid, int, jsonb,
--                                                timestamptz, jsonb, text, text,
--                                                jsonb, uuid, int, text);
--     DROP FUNCTION IF EXISTS latest_agreement_of(uuid, uuid);
--     DELETE FROM schema_migration     WHERE version = 'V21';
--     DELETE FROM flyway_schema_history WHERE version = '21';
--   🛑 已登记的 agreement 行【不受回滚影响】—— 它们是数据不是 schema。
--      若要清理，顺序必须是 agreement → plan → customer → store → tenant
--      （agreement 有两条复合外键：customer(tenant_id, id) 与 plan(plan_id, version)）。
--      🛑 本表**没有**下游引用它（实测 0 条反向外键），故它排在清理链最前 ——
--         这与 case_archive 同型（V20），而与 device 不同（device 被 device_dispatch 引用）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立，否则后面的一切都是空中楼阁
--
--   本迁移的设计建立在七个已存在的 schema 事实之上，七条都会在下面机械断言：
--     ① agreement 是 ENABLE + **FORCE** RLS
--        —— 与 V20 同款且理由相同：核心机制一（建立上下文）与核心机制二
--           （跨租户撞号）**都**依赖"当前角色真的受 RLS 约束"，而 ENABLE 只管表主、
--           FORCE 才管住了表属主。缺 FORCE 时若迁移由 table owner 跑，
--           策略**不被应用** ⇒ 两条机制静默失效。
--     ② `agreement_pkey` 是**单列** `(agreement_id)`
--        🛑 这一条是【核心机制二】的**唯一前提**。若改为 `(tenant_id, agreement_id)`，
--           则"跨租户撞号"不可能发生 ⇒ 本函数的 RAISE 分支变成**死代码**，
--           且自证 (e11) 会以"竟然成功了"的面目失败 —— 一条**归因错误**的红。
--     ③ 表上**没有** `(tenant_id, customer_id)` 唯一载体
--        🛑 这是"一个客户可以有多份协议"的**唯一前提**。
--           PRD 没有说"一客户一协议"（C.1.5 的字段表里没有这条约束），
--           而 schema 也没有。本函数据此**允许**同一客户被签署多次
--           （每次一个不同的 agreement_id，例如"方案改版后重新签"）。
--     ④ `customer` 上有 `uq_tenant_customer_id = UNIQUE (tenant_id, id)` 载体
--        —— 它是 `agreement_customer_id_fkey` 这条**复合外键**的引用目标。
--     ⑤ `agreement_customer_id_fkey` 是**复合**形态（含 tenant_id）
--        —— V16 的成果。本函数会显式查一次 customer（归因质量），
--           但**库层那道必须仍然在**：函数可以被人绕过（直接写 SQL），外键不能。
--     ⑥ `fk_agreement_plan_version` 是**复合**形态 `(plan_id, plan_version)`
--        → `plan (plan_id, version)`，且 `plan` 上有 `uq_plan_id_version` 唯一载体
--        🛑 这一条是**本迁移特有**的（V20 的 case_archive 没有方案外键）。
--           它决定"跨版本的方案引用"是否被库层拒绝 ——
--           即"给方案 v2 的客户签了一份写着 v1 的协议"这件事能不能发生。
--           本函数会显式查一次 plan（归因质量），库层这道是第二层。
--     ⑦ 表上 CHECK 约束**恰 1 条，且定义逐字为** `ck_agreement_plan_version_positive`
--        🛑🛑 这一条与 V20 的同名断言**结论相反**（V20 要求 =0），理由见文件头
--           「核心机制四」：agreement 本来就有 1 条 CHECK，而本断言的作用是
--           **让任何新增 CHECK 的改动必然被看见** —— 因为新增的每一条都可能让
--           `RlsV5EntityIsolationTest` 的 agreement 探针报出**归因错误**的红。
-- ============================================================================

DO
$v21_precond$
DECLARE
    v_rls      boolean;
    v_force    boolean;
    v_pk       text;
    v_cust_fk  text;
    v_plan_fk  text;
    v_uq_plan  int;
    v_checks   int;
    v_chk_def  text;
BEGIN
    -- ① agreement 存在且 ENABLE + FORCE RLS
    SELECT c.relrowsecurity, c.relforcerowsecurity INTO v_rls, v_force
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'agreement';

    IF v_rls IS NULL THEN
        RAISE EXCEPTION
            'V21 前置失败: 表 agreement 不存在。本迁移的协议签署原语依赖它（V5 §2.9 建表）。';
    END IF;
    IF NOT v_rls THEN
        RAISE EXCEPTION
            'V21 前置失败: 表 agreement 未启用 ROW LEVEL SECURITY。'
            '本迁移的核心价值之一是"签署登记必须自己建立租户上下文"，'
            '若 agreement 没有 RLS，这个守卫就失去了守护对象 —— 不是"可以省略"，'
            '而是"前提被推翻了"，必须先解释清楚才能继续。';
    END IF;
    IF NOT v_force THEN
        RAISE EXCEPTION
            'V21 前置失败: 表 agreement 只 ENABLE 了 RLS，没有 FORCE。'
            '🛑 这一条与上面那条**不是重复**：ENABLE 只对"非表属主"生效，'
            'FORCE 才把表属主也纳入策略管辖。'
            '而本迁移的两条核心机制 —— 核心机制二（跨租户撞号判定，依赖"别人的行读不到"）'
            '与自证 (e13)（租户 B 读不到租户 A 的协议）'
            '—— **全部**建立在"当前角色受 RLS 约束"之上。'
            '缺 FORCE 时若迁移恰由 table owner 执行，策略**不被应用** ⇒ '
            '自证里依赖 RLS 的那几条会以【归因错误】的面目报红或假绿。'
            '（与 V20 的同一断言逐字同款，理由也相同。）';
    END IF;

    -- ② agreement_pkey 必须是【单列】agreement_id —— 核心机制二的唯一前提
    SELECT pg_get_constraintdef(con.oid) INTO v_pk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'agreement' AND con.contype = 'p';

    IF v_pk IS NULL THEN
        RAISE EXCEPTION 'V21 前置失败: 表 agreement 没有主键 —— 无法建立 agreement_id 的冲突判定。';
    END IF;
    IF v_pk !~ 'PRIMARY KEY\s*\(\s*agreement_id\s*\)' THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 的主键形态变了（当前: %）。'
            '本迁移的【核心机制二】完全建立在"agreement_pkey 是单列 agreement_id"这个事实上：'
            '正因为它不含 tenant_id，"同一 agreement_id 被另一租户占用"才可能发生；'
            '也正因为会发生，函数才必须把"冲突了但我看不见 ⇒ 是别人的"这条判定补上。'
            '若主键已改为 (tenant_id, agreement_id)，则该冲突不可能发生，'
            '本函数的跨租户分支变成死代码，而自证 (e11) 会以【归因错误】的方式失败。'
            '🛑 遇到这条报错，正确的反应是【修本迁移的设计说明与自证】，'
            '而不是把这里的判据改宽 —— 前提变了，结论必须重新推导。', v_pk;
    END IF;

    -- ③ 表上不得有 (tenant_id, customer_id) 唯一载体 —— "一客户可多协议"的前提
    --   🛑 判定用【列集合】而不是字符串 LIKE —— V16 在 2026-09-27 已被这条坑过一次：
    --      pg_get_constraintdef 的输出按 conkey 顺序拼列名，故 LIKE '(tenant_id, %'
    --      实际断言的是"tenant_id 恰好排第一"，而不是"tenant_id 参与了这个键"。
    --   🛑 同时查 pg_constraint 与 pg_index —— agreement 的唯一载体实测是 pkey，
    --      但"唯一索引"（非约束）也是可能的形态（V5 的 uq_plan_id_version 就是），
    --      故两处都查，否则会漏掉"用 CREATE UNIQUE INDEX 加的唯一键"。
    IF EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        WHERE c.relname = 'agreement' AND contype IN ('u', 'p')
          AND (SELECT array_agg(a.attname ORDER BY a.attname)
                 FROM unnest(con.conkey) AS k(attnum)
                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
              = ARRAY['customer_id', 'tenant_id']::name[]
    ) OR EXISTS (
        SELECT 1 FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
       WHERE c.relname = 'agreement' AND i.indisunique
         AND (SELECT array_agg(a.attname ORDER BY a.attname)
                FROM unnest(i.indkey) AS k(attnum)
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
             = ARRAY['customer_id', 'tenant_id']::name[]
    ) THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 上出现了 (tenant_id, customer_id) 唯一载体。'
            '🛑 这意味着"一个客户只能有一份协议"成为库层事实，'
            '而本迁移的 register_agreement 是**按 agreement_id 幂等**的'
            '（允许同一客户被多次签署，例如"方案改版后重新签"）。'
            '两者冲突时，重新签署会以一条 **23505** 失败 —— '
            '一条与"签署动作"无关的、无法归因的数据库错误。'
            '若这个唯一约束是**有意**加的（即业务上确实"一客户一协议"），'
            '正确处置是：① 重新推导本函数的幂等键（改成 customer_id）；'
            '② 重新推导文件头「为什么没有确认签署原语」那一节；'
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
            'V21 前置失败: customer 上没有 UNIQUE (tenant_id, id) 载体。'
            '它是 agreement_customer_id_fkey 这条复合外键的【引用目标】'
            '—— PG 要求被引用列是唯一键。'
            '🛑 实测真库上它叫 uq_tenant_customer_id；本判据【不按名字找】而按列集合找，'
            '因为名字对语义没有任何约束力（改名不该让门禁变红）。';
    END IF;

    -- ⑤ agreement_customer_id_fkey 是复合形态（V16 的成果必须仍在）
    SELECT pg_get_constraintdef(con.oid) INTO v_cust_fk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'agreement' AND con.contype = 'f'
       AND (SELECT count(*) FROM unnest(con.conkey)) = 2
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(con.conkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
           = ARRAY['customer_id', 'tenant_id']::name[];

    IF v_cust_fk IS NULL THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 上没有复合形态 (tenant_id, customer_id) 的外键。'
            '这说明 V16（跨租户引用完整性）的成果【在 agreement 上被回退了】。'
            'V21 的函数会显式查一次 customer 做归因，但那是【第一层】；'
            '库层的复合外键是【第二层】—— 函数可以被人绕过（直接写 SQL），外键不能。'
            '缺了它，"在租户 A 里把协议挂到租户 B 的客户名下"就重新变成一条敞开的路径。';
    END IF;

    -- ⑥ fk_agreement_plan_version 是复合形态 (plan_id, plan_version) → plan(plan_id, version)
    --    🛑 本迁移特有（V20 无此断言）：它决定"跨版本的方案引用"是否被库层拒绝。
    SELECT pg_get_constraintdef(con.oid) INTO v_plan_fk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'agreement' AND con.contype = 'f'
       AND (SELECT count(*) FROM unnest(con.conkey)) = 2
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(con.conkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
           = ARRAY['plan_id', 'plan_version']::name[];

    IF v_plan_fk IS NULL THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 上没有复合形态 (plan_id, plan_version) 的外键。'
            '它的存在意味着"协议绑定的方案版本必须真实存在"是库层事实。'
            '🛑 缺了它，一份写着 plan_version=99 的协议可以被插进去，'
            '而"这份协议对应哪一版方案"从此不可答 —— '
            'PRD §2.9 逐字要求「绑定方案版本」（这是"方案改版不回溯已签协议"的载体）。'
            '本函数会显式查一次 plan 做归因（第一层），库层这道是第二层。';
    END IF;

    -- ⑥b plan 上有 uq_plan_id_version 唯一载体（上面那条外键的引用目标）
    --    🛑 实测它是**唯一索引**（pg_indexes 可见 uq_plan_id_version），
    --       不是 pg_constraint 里的 UNIQUE —— 故这里查 pg_index 而不是 pg_constraint。
    --       这正是本仓反复遇到的"名字相同、载体不同"问题（V5 用 CREATE UNIQUE INDEX 建的）。
    SELECT count(*) INTO v_uq_plan
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indrelid
     WHERE c.relname = 'plan' AND i.indisunique
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(i.indkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
           = ARRAY['plan_id', 'version']::name[];

    IF v_uq_plan = 0 THEN
        RAISE EXCEPTION
            'V21 前置失败: plan 上没有 UNIQUE (plan_id, version) 载体。'
            '它是 fk_agreement_plan_version 这条复合外键的【引用目标】'
            '—— PG 要求被引用列是唯一键。'
            '🛑 实测真库上它叫 uq_plan_id_version 且是**唯一索引**（非约束），'
            '故本判据查 pg_index 而不查 pg_constraint。'
            '缺了它，V5 第 8 节的 `ALTER TABLE agreement ADD CONSTRAINT '
            'fk_agreement_plan_version` 根本上不去 —— 那会静默退回'
            '"协议与方案的版本绑定失效"。';
    END IF;

    -- ⑦ 表上 CHECK 约束【恰 1 条】且定义逐字为 ck_agreement_plan_version_positive
    --    🛑🛑 与 V20 同名断言结论相反，理由见文件头「核心机制四」。
    SELECT count(*) INTO v_checks
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'agreement' AND con.contype = 'c';

    IF v_checks <> 1 THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 上 CHECK 约束数 = %（期望恰 1）。'
            '🛑 与 V20 的同名断言结论【相反】，必须读懂再改：'
            'V20 的 case_archive 有 0 条 CHECK，故它断言"=0"；'
            'agreement 本来就有 1 条（ck_agreement_plan_version_positive）。'
            '本条断言的**目的不是记录现状，而是让任何新增 CHECK 的改动必然被看见** ——'
            '因为新增的每一条都可能让 `RlsV5EntityIsolationTest` 的 agreement 探针'
            '报出**归因错误**的红（那条探针断言 SQLSTATE=42501 来验证 RLS 的 WITH CHECK；'
            '新增的 CHECK 会让它先以 23514 失败，而报错信息说的是"WITH CHECK 没生效"）。'
            '🔴 若你确实要加 CHECK：请先显式改掉本断言，并在文件头说明为什么'
            '"对一切写入施加签署门禁"是正确的（本迁移的处置是把门禁写在**函数里**，'
            '因为库层约束是全局的 —— 见核心机制四）。', v_checks;
    END IF;

    SELECT pg_get_constraintdef(con.oid) INTO v_chk_def
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'agreement' AND con.contype = 'c';

    IF v_chk_def IS NULL OR v_chk_def !~ 'plan_version\s*>=\s*1' THEN
        RAISE EXCEPTION
            'V21 前置失败: agreement 上唯一那条 CHECK 的定义变了（当前: %）。'
            '期望它是 `CHECK ((plan_version >= 1))` —— PRD §2.9 的"方案版本从 1 起"。'
            '🛑 本断言钉的是**定义内容**而不只是数量：'
            '"恰 1 条"与"恰是这条"是两件事 —— 前者挡不住"把 A 换成 B"。', v_chk_def;
    END IF;
END;
$v21_precond$;