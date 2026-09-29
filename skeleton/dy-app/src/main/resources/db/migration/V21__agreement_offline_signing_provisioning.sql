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
--   第 4 条 · 🛑🛑 **本迁移在真库应用时当场报红，逐字登记**（本仓第 42 条缺陷）
--     报错原文（首次应用 V21 的实测输出，逐字）：
--         `错误:  无效的类型 boolean 输入语法: "true'therapist'\s*,\s*'store_owner'"`
--         `CONTEXT:  在IF的第203行的PL/pgSQL函数inline_code_block`
--     根因：**PostgreSQL 里 `||`（文本连接）与 `!~` / `~*`（正则匹配）是同一优先级，
--           且左结合** ⇒ 自证 (b7) 与 (b11) 里那种"把一条正则拆成多行、用 || 连接"
--           的写法，被解析成 `(A !~ B) || C` ——
--           先算出一个 boolean，再拿它去和 text 拼接 ⇒ 运行期 22P02。
--     🛑 为什么这条特别值得登记：
--         (1) 错误消息**看起来像正则写错了**（消息里夹着正则片段），
--             而真相是**运算符优先级**。若按字面去"修正正则"，会越修越远。
--         (2) 它是**语法层面**的缺陷，静态读代码时"看起来完全正确"——
--             正则本身没错、|| 也不罕见；错的是两者的**结合方式**。
--         (3) 本仓的纪律是"缺陷要逐字登记"，而这条的可见性极低：
--             V17~V20 的自证块里都没有出现过"跨行拼接的正则"，
--             所以前 4 个迁移**不可能**暴露它 —— 是本域新引入的写法踩到的。
--     处置：(b7) / (b11) 两处改为 **把整条正则用括号包成一个表达式**
--           `IF x !~ ( 'p1' || 'p2' ) THEN`；并在两处注释里写清"括号不是可选的"。
--     🛑 纪律升级：**凡正则跨行拼接，必须加括号** —— 这条已写入两处注释，
--        后续若有人新增同类断言，照抄括号写法即可。
--
--   第 5 条 · 🛑🛑 **"两个不同的量相比/相减"——同一失效模式在真库应用时连报三次**
--     本迁移的自证块在真库应用过程中**连续报红四次**，其中**三次根因完全相同**：
--     「表达式看起来对，但它两侧说的不是同一件事」。逐字登记如下。
--
--     ③（第一次报红）运算符优先级 —— 见第 4 条：(b7)/(b11) 里 `!~ B || C`
--        被解析成 `(A !~ B) || C`。→ 已改为加括号。
--     ④（第二次报红）**判据没剥注释** —— (b7) 假定函数体里 4 个键名逐字连续，
--        而实际每个元素各带一个行内注释 ⇒ 恒红。→ 已改为先剥注释再压空白。
--        同源的第二处：(b8) 断言"函数体不得含警告门禁结构"，而函数体里
--        **恰有一条解释性注释**写了 `v_warn_gate_keys` ⇒ 恒红。→ 同样改用剥注释版。
--     ⑤（第三次报红）**减法两侧口径不一致** —— (b7b) 计数写的是
--        `length(整段文本) - length(replace(分段文本, ...))`，
--        被减数和减数来自**两个不同的量** ⇒ 差值必然 ≠ 8 ⇒ 恒红。
--        → 已改为"先取出 v_seg，再对同一个 v_seg 取长度与替换"。
--     (期间还有一次是 `.md` 侧的数字错：见第 3 条 description 长度。)
--
--     🛑 为什么把这三条**合并登记为第 5 条**而不是拆成三条：
--        它们的**根因是同一个**——"表达式与它声称验证的东西不是同一个量"。
--        分开记会让人以为这是三个无关的笔误；合并记才能让下一个人学到
--        **这类错误的形状**。本仓第 40/41/42 条（换行污染 / 判据被注释满足 /
--        写数字不量）也属于同一族：**"看起来被验证了"≠"被验证了"**。
--
--     🛑 对本迁移的意义：自证块的价值**恰恰在于它会红**。
--        这四次报红全部发生在**迁移应用阶段**（而不是在交付之后），
--        且每一次都精确定位到"哪一条断言、什么原因"——
--        这证明"自证不满足即 RAISE、整个迁移回滚"这套机制**真的在起作用**：
--        若把自证写成"只打印警告"，这四次缺陷会全部**静默通过**。
--
--   第 6 条 · 🛑🛑 **迁移文本里的 `${...}` 会被 Flyway 当【变量】解析 —— 整个应用起不来**
--     （本仓第 43 条缺陷；与自证报红无关，但它比自证报红**后果更重**）
--
--     现象：`psql -f V21...` 直连应用 **完全成功**（EXIT=0，自证全过、两函数建立、
--           schema_migration 登记 V21）。但走 Spring / Flyway 通道时**启动期直接抛**：
--               FlywayException: Unable to parse statement in
--                 db/migration/V21__agreement_offline_signing_provisioning.sql at line 202 col 1.
--               Caused by: No value provided for placeholder: ${store.name}.
--           ⇒ **整个 ApplicationContext 起不来**，不只是"某条迁移失败"。
--
--     根因：Flyway 默认开启**占位符替换**，把自己文本里形如 `${name}` 的片段当变量。
--           本迁移逐字引用了 **PRD P0-27 的占位符白名单全集**（6 个）：
--               `${store.name}` / `${customer.name}` / `${customer.phone_masked}` /
--               `${agreement.sign_date}` / `${plan.cycle_count}` / `${plan.version}`
--           —— 这些是**文书模板的占位符字面量**（要被写进口径说明与 RAISE 消息里），
--           是**业务内容**，不是 Flyway 变量。
--
--     🛑🛑 为什么这个缺陷差点被漏掉（本仓最该学到的一点）：
--         本仓对迁移的**唯一验收入口**长期是 `psql -f`（因为自证要在真库跑）。
--         而 **psql 不解析 `${...}`** ⇒ 那条通道上这个缺陷**完全不可见**。
--         ⇒ 可迁移的教训：**"迁移能被 psql 应用"不等于"迁移能被 Flyway 应用"** ——
--           两条通道的**解析器不同**，而验收只在其中一条上做过。
--           这与本仓第 42 条（`||` 与 `!~` 优先级）同族：都是"验证通道与运行通道不一致"。
--
--     处置（🛑 三条候选，只有一条无损）：
--       ① **改迁移文本**（把 `${` 写成 `$` + `{` 或全角）：❌ 会让 V21 的 checksum
--          与真库登记值失配；且第 866 行的 `${agreement.sign_date}` 位于**函数体的
--          RAISE 消息里** ⇒ 改它还会改变 `pg_proc.prosrc` ⇒
--          破坏与真库的"DDL 净效果等价"对账（122 的 C8 论据正是靠它）；
--       ② **给 Flyway 传一个同名变量值**：❌ 那是把业务口径**喂成配置**，
--          且 6 个占位符要喂 6 个假值，语义上等于承认"这些是变量"—— 方向错了；
--       ③ **关闭占位符替换**（`spring.flyway.placeholder-replacement: false`）：✅
--          **唯一无损解** —— 迁移文本**逐字不变**（checksum / prosrc 全不变），
--          且本仓 V1..V20 的迁移文本**逐字零处** `${`（机械可证）⇒
--          关闭它对任何既有迁移的语义**没有任何影响**。
--
--     ⇒ 已落地：`dy-app/src/main/resources/application.yml` 的 `spring.flyway` 下
--       显式写 `placeholder-replacement: false`（含一段说明为何这是纪律而非权宜）。
--     ⇒ 防回归：`AgreementGateTest` 判据 ⑦f 机械断言本项在位；
--       ⑦g 断言"V19/V20 里零处 `${`"（把 ⑦f 论据所依赖的**前提**变成可断言的东西 ——
--       前提变了，结论必须重推）。
--
--     🛑 为什么这条登记**必须留在迁移文件里**（而不是只写在测试或 README）：
--        下一个写迁移的人会在**同一个位置**（迁移文件里引用 `${...}`）踩同一脚，
--        而那时他不会去读测试文件。**缺陷登记要落在下一次犯错的地方。**
--
--   第 7 条 · 🛑🛑 **静态判据的载体必须是"剥注释后的代码态"—— 实测复发两处静默假绿**
--     （本仓第 44 条缺陷；与第 41 条"判据被注释满足"同根，但**机制不同**，必须分开记）
--
--     🛑 为什么这条**必须单独立条**（不能并进第 41 条）：
--       第 41 条的形态是"注释里**恰好有**代码要写的那句话 ⇒ 判据被点亮"。
--       本条实测的两处**都不是注释**，而是**同一函数体里别处的字符串字面量/注释**：
--         · (b5)/(b6)：函数体末尾那条跨租户 RAISE 的**消息字符串**里逐字写着
--             `INSERT ... ON CONFLICT (agreement_id) DO NOTHING 被【主键冲突】拦下`。
--           旧载体是 `substring(v_reg_body ... for 3000)` —— 一个**固定长度窗口**，
--           实测该 INSERT 语句止于 2206 字符处，而 3000 的窗口把后面的 RAISE 消息
--           包了进来 ⇒ 判据在**别人的文本**上被满足。
--           ⇒ 把真正的 `ON CONFLICT (agreement_id)` 改成 `(tenant_id, agreement_id)`
--             （错误改法，使跨租户 RAISE 成死代码）后，(b6) **仍然通过**（静默假绿）。
--         · (c1)：latest_agreement_of 的注释里逐字写着
--             `` 🛑 同时写 `tenant_id = p_tenant_id`：… ``
--           旧载体是 v_lat_body（prosrc 原文）⇒ 删掉真正的 `WHERE tenant_id = p_tenant_id`
--           后，(c1) **仍然通过**（实测：注释命中 1 处、代码命中 1 处，两态都为 True）。
--
--     ⇒ 可迁移的教训（本次新得，与第 41 条互补）：
--        **"文本里有这个词" 与 "代码里有这件事" 是两件事。**
--        判据的载体必须与它要断言的对象**同构**：断言"代码有没有某语句"，
--        载体就必须是**代码态**；断言"文档有没有写某口径"，载体才可以是原文。
--        ⇒ 一旦载体选错，"判据存在但从不生效"会**长期不可见** ——
--          因为它每次跑都打印"通过"。
--
--     ⇒ 处置（三处，缺一不可）：
--       ① 新增 (b0)：**统一构造代码态**。v_reg_code / v_lat_code（只剥注释）、
--          v_reg_nocomment（剥注释 + 压空白，**从 v_reg_code 派生**）。
--       ② (b0b)/(b0c)：断言代码态**构造成功**（非空 + 比 prosrc 短）、
--          且语句切片**有语义终点**（`split_part(..., ';', 1)`，不再是拍出来的 3000）。
--          🛑 为什么这两条断言是必须的：`NULL !~ '...'` 返回 NULL（不是 true），
--             而 `IF NULL THEN ... END IF` **不执行** ⇒ 载体一旦为 NULL，
--             **整段静态判据静默消失**，只剩行为段在跑（"看起来跑了很多断言"）。
--       ③ 全部静态形态判据的载体切到代码态：(b1)~(b6)/(b9)~(b14) 用 code，
--          (b7)/(b7b)/(b8)/(b12)/(b13) 用 nocomment，(c1)~(c3) 用 v_lat_code。
--
--     ⇒ 防回归（🛑 本条的分工必须说清，否则下一个人会以为漏了）：
--        · 本节能证的："载体被构造了"（(b0b)/(b0c) 的三条断言）。
--        · 本节能证的："载体职责一对一"（见 (b0) 的职责表 + (c9) 的分工说明）。
--        · **本节能不到、必须由 122 扫源证的**："本节里有没有人把某条判据退回
--          v_reg_body / v_lat_body 原文"。理由：PG 在运行期**读不到迁移文本**。
--          ⇒ 122 必须含一条元门禁：在自证块的【美元引用区间】内，正则匹配的载体
--            不得是 `v_reg_body` / `v_lat_body`；允许的唯一原文用途是
--            `length(v_reg_body)`（长度比较不需要剥注释）。
--          🛑🛑 本条不能把该区间标签逐字写出来（连写在注释里也不行）：
--             实测于真库应用时**当场报语法错误** —— PG 词法分析在识别注释**之前**
--             就先扫美元引用标签 ⇒ 注释里的标签字面量会**提前终止**本 DO 块自身
--             的美元引用，报「语法错误 在 "`" 或附近」。
--             （本仓新得的一条：**美元引用标签是"文件级"的保留串，不是"代码级"的**。）
--          🛑 不这么做的话，最危险的后果是：有人把 (c1) 退回 v_lat_body 后，
--             **自证仍然全绿**（因为注释里还留着那句话），而隔离谓词其实已经没了。
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

-- ============================================================================
-- 第 1 节 · 签署登记原语 + 读原语
-- ============================================================================

-- ---------------------------------------------------------------------------
-- register_agreement(p_tenant_id, p_agreement_id, p_customer_id,
--                    p_plan_id, p_plan_version,
--                    p_refund_clause_snapshot, p_signed_at, p_signer,
--                    p_rendered_snapshot, p_rendered_hash,
--                    p_breach_clause_snapshot, p_doc_template_id,
--                    p_doc_template_version, p_created_by)
--
--   【返回两态（逐字说清，调用方据此审计留痕）】
--     · 'CREATED'        —— 本次调用登记出了这一份协议
--     · 'ALREADY_EXISTS' —— 幂等命中：这一份【已在本租户名下】
--   【若干类 fail-closed 异常】
--     · 入参为空 / 类型不对                   → RAISE
--     · 客户在本租户内不存在                   → RAISE
--     · 方案 (plan_id, plan_version) 不存在     → RAISE（本迁移特有，V20 无）
--     · plan_version < 1                       → RAISE（表 CHECK 是第二层）
--     · signer 非对象 / 未知键 / 4 键缺项或为空 → RAISE
--     · refund_clause_snapshot 非对象或为空     → RAISE
--     · rendered_snapshot 为空                 → RAISE
--     · rendered_hash 形态非 64 位小写 hex      → RAISE（🛑 只校验形态，不可重算）
--     · doc_template_id/version 只给一个        → RAISE（成对）
--     · doc_template 指针在租户内不存在 / 版本不符 → RAISE
--     · agreement_id 已被【另一租户】占用        → RAISE（核心机制二）
--
--   【🛑 校验顺序为什么是"先校验、再写入"，而不是"先写入、后校验"】
--     与 V20 逐字同款的两条理由：
--       ① 落库语义：门禁不齐时**绝不能落库**。若先写后校验，一次失败的签署登记
--          会留下半份协议 —— 而它是**举证材料**。半份举证比没有更坏。
--          自证 (e4)/(e10)/(e12) 都断言"被拒绝的那次没有落库"。
--       ② 🛑 幂等重放时也要校验：若首次登记时签署人齐备、重放时传了一份**不齐**的
--          signer，函数会怎么答？本函数选择 **RAISE**，而不是静默 ALREADY_EXISTS。
--          理由：调用方传了一份不齐的签署块，说明**它以为自己在登记一次签署**；
--          若静默返回 ALREADY_EXISTS，调用方会以为"登记成功、且我这份签署块被接受了"，
--          而库里是**另一份**。这与核心机制二（"返回值在撒谎"）是同一族形态 ——
--          只不过一个来自 RLS，一个来自校验位置。
--
--   【设计取舍一：customer 与 plan 的存在性都用显式查 + RAISE，而不是让外键报 23503】
--     与 V17 / V18 / V19 / V20 对上层实体的处置逐字同款，理由（归因质量）相同：
--       · 库层拒绝（复合外键）⇒ 一条 PostgreSQL 的 23503，调用方要自己解析约束名；
--       · 本检查              ⇒ 一条写明"客户 X / 方案 Y@vZ 在租户 T 内不存在"的业务错误。
--     两者都 fail-closed，区别只在诊断成本。
--     🛑 本迁移比 V20 **多一层**：V20 只查 customer；本迁移要查 customer + plan
--        （因为 agreement 有**两条**复合外键 —— 见文件头差异表 ②）。
--        且 plan 的查询必须带 version：`WHERE plan_id = ... AND version = ...`，
--        否则"方案存在但那一版不存在"会被误判为存在。
--
--   【设计取舍二：为什么把键集抽成 ARRAY 常量而不是散写】
--     四方签署的键集定义成函数内的局部数组，而不是把字面量散在若干个 IF 里。
--     两个好处，都是机械可判定的：
--       · 自证 (b7) 能**锚在数组字面量上**断言"签署键集恰 4 个且逐字为这四个"
--         —— 这样"有人偷偷把某个签署人从必填里去掉"必然被看见；
--       · 待裁登记第 1 条(a)（键名语言未冻结）若最终裁定要改，**改的是这一处**。
--
--   【🛑 设计取舍三：为什么 rendered_hash 的校验是【形态】而不是【重算】】
--     实测 `pg_extension` 里**只有 plpgsql** ⇒ `pgcrypto` 未安装 ⇒ 无 `digest()`。
--     ⇒ 库层无法从 `p_rendered_snapshot` 重算 SHA-256。
--     ⇒ 本函数只能断言"这个字符串是一个合法的 SHA-256 摘要形态"，
--        并**显式承认**它不能证明"它就是这一版正文的摘要"。
--     🛑 这条边界必须留在注释里，不得删：它的作用不是解释代码，
--        而是**阻止下一个人写出"库层已校验 hash"这句假话**。
--        真正的一致性守卫在应用层（`AgreementService` 复用
--        `DocFileService.sha256()` 的同一口径做重算比对）——
--        这是"同一件事只能有一个口径"的落实，不是重复实现。
--
--   【🛑🛑 大小写：为什么"接受混合大小写 + 入库归一"而不是"只收小写"】
--     本条是**本文件初稿的一处内部不一致被自己抓出来后重写的**，
--     故过程与结论都要留下（否则下一个人会把它改回去）：
--
--     初稿同时写了两件事 —— 正则 `^[0-9a-f]{64}$`（**拒大写**）
--     与 INSERT 里的 `lower(p_rendered_hash)`（**归一化**）。
--     🛑 这两者并存 ⇒ `lower()` 永远不可能改变任何东西 ⇒ 它是一条**死代码**。
--        死代码的问题不是"多写了一个函数调用"，而是它**声称**了一件事
--        （"本函数会把 hash 归一化"）而实际不成立 —— 下一个人会依赖那个声称。
--
--     二选一，本函数选**接受混合大小写 + 入库小写归一**，理由三条：
--       ① **语义上它们是同一个值**：SHA-256 的十六进制表示大小写只是书写习惯。
--          拒绝一个大写形态的**正确**摘要，就是本仓记载过的
--          "a gate that cries wolf on correct content gets switched off"
--          （见 `client-zero-derived-gate.py` 源码里对 equality 检查的否决理由）。
--       ② **与本仓既有口径一致**：`DocFileService` 的比对用 `equalsIgnoreCase`
--          （宽容读），而写入是规范形态（严格写）。
--          ⇒ 本函数把**规范点放在入库侧**，与之一致。
--       ③ **让 `lower()` 成为承重的**：归一化在库层发生的意义是
--          "**库里的每一行 hash 都是同一种写法**" ⇒ 事后对账（例如
--          "这一版正文签过几份协议"）可以用**字符串等值**去查，
--          而不必每处都记得 `lower()` 或 `ILIKE`。
--          🛑 这条才是真正的理由：**不确定性会传染** ——
--             若允许库里存两种写法，那么每一个读点都必须自己记得归一，
--             而**漏掉一个读点**就是一个静默的错答。
--     ⇒ 正则改为 `^[0-9a-fA-F]{64}$`（接受两种大小写），
--       入库统一 `lower(...)`，自证 (e9) 与 (b13) 分别从**行为**与**文本**两侧钉住它。
--
--     🛑 仍然**拒绝**的形态，以及为什么它们与大小写不同（必须写清，否则像是双标）：
--       · `sha256:<hex>` / base64 / 带空格 —— 它们是**另一种编码**，
--         不是一个摘要的不同写法。解码它们属"协议解析"，而协议（I8/H1）
--         尚未冻结 ⇒ 库层不得猜。
--       · 长度不对（32 = MD5 / 40 = SHA-1 / 128 = SHA-512）—— 是**算法不同**。
--     ⇒ 判据：**"同一个值的不同写法"接受并归一；"不同的值 / 不同的编码"拒绝。**
--
--   【🛑 设计取舍四：为什么 doc_template 指针要"成对 + 版本必须相符"】
--     见 (6b)/(6c)。两条理由：
--       ① 成对：只给 id 不给 version（或反之）会让"签的是哪一版"不可答 ——
--          而 PRD P0-27 逐字要求「旧版本不可覆盖」「签署时快照」。
--          单边指针是一个**看起来填了、实际答不了**的字段（与 V20 不收
--          effect_confirm_pdf 的理由同族，但结论相反：这里能答，故要求答完整）。
--       ② 版本必须与模板行自身相符 —— `doc_template` 的主键是 `template_id`，
--          即"每一个版本是一行、有自己的 id"（实测 pkey 单列）；
--          故 `doc_template_version` 是**冗余副本**，它必须等于该行的 `version`。
--          这条断言能抓住"指针说 v3、实际指向 v5 那一行"这类**静默错配** ——
--          而那种错配恰好是"事后无法证明当时签的是哪一版"的成因。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION register_agreement(
    p_tenant_id              uuid,
    p_agreement_id           uuid,
    p_customer_id            uuid,
    p_plan_id                uuid,
    p_plan_version           int,
    p_refund_clause_snapshot jsonb,
    p_signed_at              timestamptz,
    p_signer                 jsonb,
    p_rendered_snapshot      text,
    p_rendered_hash          text,
    p_breach_clause_snapshot jsonb   DEFAULT NULL,
    p_doc_template_id        uuid    DEFAULT NULL,
    p_doc_template_version   int     DEFAULT NULL,
    p_created_by             text    DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v21_register$
DECLARE
    -- 🛑 四方签署的键集 —— **恰 4 个，一个都不能少，也不能多**。
    --    来源 = PRD C.1.5 §八 逐字：「json{客户/经络师/调理师/门店负责人:签名+日期}」
    --           + PRD 第 7 项 gating 动作「签调理协议书 → agreement_signed → 硬门禁③」。
    --    🛑 与 V20 的差别（必须记住，否则会照抄错）：
    --       V20 的 staff_signs 是 4 键【文本】+ 6 项【布尔清单】两套；
    --       本域**只有一套**，且**没有警告项** ——
    --       因为协议签署在 PRD 里没有"可以不签"的角色，
    --       四方形同"未签不得首次调理或退款判定"这条硬门禁。
    --       ⇒ 本数组**不存在**对应的"警告键集"（V20 的 v_warn_gate_keys）。
    --       自证 (b7) 会锚在这里断言"本函数体里不出现警告键集的写法"。
    v_sign_keys      text[] := ARRAY[
        'customer',            -- 客户（PRD §八 第 1 方）
        'meridian_therapist',  -- 经络师（PRD §八 第 2 方；P0-19 定其与门店负责人同为代录人集合）
        'therapist',           -- 调理师（PRD §八 第 3 方）
        'store_owner'          -- 门店负责人（PRD §八 第 4 方）
    ];
    v_ctx_before     text;
    v_ctx            text;
    v_cust           int := 0;
    v_plan           int := 0;
    v_tpl            int := 0;
    v_tpl_version    int;
    v_mine           int := 0;
    v_affected       int := 0;
    v_unknown_sign   text;
    v_missing_sign   text;
    v_clause_len     int;
BEGIN
    -- (1) 入参守卫 —— 与 V15 / V17 / V18 / V19 / V20 同款：
    --     DDL 的 NOT NULL 挡不住空串，而空串在语义上同样无意义，且会静默流进台账。
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_agreement: p_tenant_id 不可为空（agreement.tenant_id 是 NOT NULL）';
    END IF;
    IF p_agreement_id IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_agreement_id 不可为空（agreement.agreement_id 是主键，'
            '且它是本函数幂等判定的唯一键 —— 没有它，重放无法与"新建"区分）';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_customer_id 不可为空（agreement.customer_id 是 NOT NULL，'
            '且是复合外键 (tenant_id, customer_id) → customer 的一端）。'
            '🛑 它同时是**行级 scope 的承载者**：agreement 表内没有 store_id，'
            '协议的可见范围随客户走 —— 客户为空等于这份协议没有归属。';
    END IF;
    IF p_plan_id IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_plan_id 不可为空（agreement.plan_id 是 NOT NULL，'
            '且是复合外键 (plan_id, plan_version) → plan(plan_id, version) 的一端）。'
            '🛑 PRD §2.9 逐字要求「绑定方案版本」——'
            '协议必须能回答"它对应哪一版方案"，否则"方案改版不回溯已签协议"失去载体。';
    END IF;
    IF p_plan_version IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_plan_version 不可为空（agreement.plan_version 是 NOT NULL）';
    END IF;
    IF p_signed_at IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_signed_at 不可为空（agreement.signed_at 是 NOT NULL）。'
            '🛑 它是"签署动作发生时点"（PRD §2.27 占位符 ${agreement.sign_date} 的取值来源），'
            '也是 G1「协议签署合规率」按时间切片取数的依据 —— 没有它，'
            '"本季度签了多少份"这个问题不可答。';
    END IF;
    IF p_rendered_snapshot IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_rendered_snapshot 不可为空（agreement.rendered_snapshot 是 NOT NULL）。'
            'PRD P0-27 逐字：「渲染结果必须落快照 + hash（否则事后无法证明"当时签的是哪一版"）」。'
            '🛑 本函数**不做渲染**（渲染属契约 I8，且 I8 的出口是厂商 —— 见文件头），'
            '它接收的是**渲染的结果**。故"快照为空"意味着调用方没有可举证的那一版正文。';
    END IF;
    IF p_rendered_hash IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_rendered_hash 不可为空（agreement.rendered_hash 是 NOT NULL）。'
            '它是与 rendered_snapshot 配套的证据（"签的就是当时那一版、且未被改过"）。'
            '🛑 只有快照没有 hash，等于只有一份**可以被替换而不被察觉**的正文。';
    END IF;
    IF p_refund_clause_snapshot IS NULL THEN
        RAISE EXCEPTION
            'register_agreement: p_refund_clause_snapshot 不可为空（agreement.refund_clause_snapshot 是 NOT NULL）。'
            'PRD §2.9 / §四 逐字：「退款/终止条款**必须快照存储·不可覆盖**」'
            '—— 条款改版**不回溯**已签协议。';
    END IF;

    -- (1b) JSONB 入参必须是 object（不是 array / 不是标量）
    --   🛑 单独断言的理由（与 V20 的 (1b) 同款）：`'[]'::jsonb -> 'key'` 返回 NULL，
    --      `jsonb_typeof` 返回 'array' ⇒ 若只断言"键存在"，一个传了数组的调用方
    --      会看到"4 方全缺"的报错，而真因是"传的不是对象"。归因质量。
    IF jsonb_typeof(p_signer) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION
            'register_agreement: p_signer 必须是 JSON 对象（object），实际类型 = %。'
            'PRD C.1.5 §八 把它定义为 json{客户/经络师/调理师/门店负责人} —— 一个映射，不是数组。',
            coalesce(jsonb_typeof(p_signer), '<NULL>');
    END IF;
    IF jsonb_typeof(p_refund_clause_snapshot) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION
            'register_agreement: p_refund_clause_snapshot 必须是 JSON 对象（object），实际类型 = %。'
            '🛑 与 V20 的清单门禁同理：传数组时下面那条"非空对象"判定会给出'
            '"快照为空"的报错，而真因是类型错 —— 那会把一次类型错误伪装成一次业务错误。',
            coalesce(jsonb_typeof(p_refund_clause_snapshot), '<NULL>');
    END IF;
    IF p_breach_clause_snapshot IS NOT NULL
       AND jsonb_typeof(p_breach_clause_snapshot) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION
            'register_agreement: p_breach_clause_snapshot 给了值就必须是 JSON 对象（object），实际类型 = %。'
            '该列**可空**（实测 nullable）⇒ 不给值是合法的；但给了数组属口径错误。',
            coalesce(jsonb_typeof(p_breach_clause_snapshot), '<NULL>');
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     理由与 V17 / V18 / V19 / V20 的 (2) 逐字相同，此处摘要：
    --       set_config(app.tenant_id, ..., true) 是**事务级**设置，且**不会**在函数入口
    --       自动重置。若调用方在一个"已设成租户 B"的事务里调用本函数传租户 A，
    --       不做检查就会把上下文**静默改写成 A**：后面的写是 A 的，而调用方以为是 B 的。
    --     为什么宁失败不可静默覆盖：协议会写一份**举证材料**，且下游
    --     `CustomerGateGuard` 会据它把客户推进 AGREEMENT_SIGNED 门禁。
    --     它写错租户的后果不是"一条数据脏了"，而是"租户 A 的案卷里出现了一份
    --     属于租户 B 客户的协议，且租户 B 的某个客户被推进了一条没有协议支撑的门"。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_agreement: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, ..., true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '🛑 签署登记尤其不可静默改写 —— 它写的是举证材料，且会推进客户状态机。'
            '调用方须先结束当前事务（或使用全新连接）再登记。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 用 set_config（普通函数、参数可绑定），不用 SET LOCAL
    --        （后者必须把值拼进 SQL 文本 ⇒ 有注入面）。与 V15 / V17 / V18 / V19 / V20 同款。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_agreement 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 🛑 客户必须**在本租户内**存在（见「设计取舍一」）
    --     库层还有 `agreement_customer_id_fkey (tenant_id, customer_id) → customer (tenant_id, id)`
    --     兜底（V16 的成果）。
    SELECT count(*) INTO v_cust
      FROM customer
     WHERE id = p_customer_id AND tenant_id = p_tenant_id;

    IF v_cust <> 1 THEN
        RAISE EXCEPTION
            'register_agreement: 客户 % 在租户 % 内不存在（本租户可见行数 = %），拒绝登记签署。'
            '🔴 若该客户确实存在，最常见的原因是租户上下文不对 —— '
            'customer 的 RLS 是 fail-closed 的，上下文错了 SELECT 会静默返回 0 行，'
            '于是"不是我的客户"与"我看不见我的客户"给出同一个结论。'
            '本次上下文的实际值（已自证）= %。',
            p_customer_id, p_tenant_id, v_cust, v_ctx;
    END IF;

    -- (4b) 🛑 方案**版本**必须在本租户内存在（本迁移特有 —— V20 无此步）
    --      🛑 判据必须带 version：`(plan_id, plan_version)` 是复合外键的一端，
    --         "方案存在但那一版不存在"必须被判为**不存在**。
    --         若这里只查 plan_id，一份写着 plan_version=99 的协议会通过本检查，
    --         然后以一条 **23503** 撞复合外键失败 —— 归因质量退化（见取舍一）。
    --      🛑 这里**同时**是"plan_version >= 1"的显式检查位置：
    --         表上的 `ck_agreement_plan_version_positive` 是第一层，但它给出的
    --         是一条 23514；本检查给出的是一条写明"版本必须从 1 起"的业务错误。
    IF p_plan_version >= 1 THEN
        SELECT count(*) INTO v_plan
          FROM plan
         WHERE plan_id = p_plan_id AND version = p_plan_version AND tenant_id = p_tenant_id;
    ELSE
        v_plan := 0;   -- 版本非法 ⇒ 直接落到下面同一处报错（避免两条语义相近的分支）
    END IF;

    IF p_plan_version < 1 THEN
        RAISE EXCEPTION
            'register_agreement: p_plan_version = % 非法 —— 方案版本必须从 1 起。'
            '口径来源 = PRD §2.9 的"版本递增·不可覆盖"（表上 CHECK 也写着 plan_version >= 1，'
            '实测 `ck_agreement_plan_version_positive`）。'
            '🛑 这里显式先判一次，是为了给出一条**可归因**的错误：'
            '若放任它去撞表 CHECK，调用方只会收到一条 23514 与一个约束名。', p_plan_version;
    END IF;

    IF v_plan <> 1 THEN
        RAISE EXCEPTION
            'register_agreement: 方案 % 的版本 % 在租户 % 内不存在（本租户可见行数 = %），拒绝登记签署。'
            '🔴 两个常见真因，都必须先说清（否则会去错的地方排查）：'
            '① 该版本确实不存在 —— 协议只能绑到**已存在**的那一版方案上'
            '（PRD §2.9 逐字："绑定方案版本"）；'
            '② 上下文不对 ⇒ plan 的 RLS 会静默返回 0 行，'
            '于是"不是我的方案"与"我看不见我的方案"给出同一个结论。'
            '本次上下文的实际值（已自证）= %。',
            p_plan_id, p_plan_version, p_tenant_id, v_plan, v_ctx;
    END IF;

    -- (5a) 🛑🛑 四方签署 —— 本迁移的**核心门禁**（PRD §八 硬门禁）
    --     见文件头「核心机制三」。此处只写实现，理由不重复。
    --
    --   【未登记键 ⇒ RAISE】与 V20 的 (5a) 逐字同款处置，理由也相同：
    --     PRD 逐字列出 4 方，是一个**封闭清单** ⇒ "缺键"与"多出未登记键"**都不合法**。
    --     一个拼错的键（例如 'stores_owner'）若不抛，会同时造成两件事：
    --       ① store_owner 被判为缺失 ⇒ 报"缺项"（归因**部分**正确）；
    --       ② 但调用方以为自己填了 —— 而下一次他会再犯。
    v_unknown_sign := coalesce((
        SELECT string_agg(k, ', ' ORDER BY k)
          FROM jsonb_object_keys(p_signer - v_sign_keys) AS t(k)), '');

    IF v_unknown_sign <> '' THEN
        RAISE EXCEPTION
            'register_agreement: p_signer 含【未登记键】-> %。'
            '已登记 4 键 = %。'
            '🛑 不得静默忽略未登记键 —— 忽略会让一次拼写错误同时产出'
            '"某方被判未签"与"调用方以为已签"两个后果，'
            '而"未签不得首次调理或退款判定"是一条硬门禁。',
            v_unknown_sign, array_to_string(v_sign_keys, ', ');
    END IF;

    --   【4 键必须存在、必须是字符串、且必须非空】
    --   🛑 与 V20 的差别（必须写清，否则看起来像不一致）：
    --      V20 的清单是**布尔**（是与否），签名块是**文本**（谁）；
    --      本域两样**都是文本** —— 因为 PRD §八 要求的是"签名+日期"，
    --      即**承载签署人身份**，而不是"某人是否签了"。
    --      空字符串对签名**没有意义**（一个空白的"门店负责人"不是签字）。
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_missing_sign
      FROM unnest(v_sign_keys) AS t(k)
     WHERE jsonb_typeof(p_signer -> k) IS DISTINCT FROM 'string'
        OR btrim(p_signer ->> k) = '';

    IF v_missing_sign IS NOT NULL THEN
        RAISE EXCEPTION
            'register_agreement: 四方签署【缺项或为空】-> %（共 % 键）。'
            '口径来源 = PRD C.1.5 §八 逐字：「agreement.signatures | '
            'json{客户/经络师/调理师/门店负责人:签名+日期} | 是 | '
            '**未签不得首次调理或退款判定** | **硬门禁**」'
            ' + PRD 第 7 项 gating 动作「签调理协议书 | agreement_signed | 硬门禁③」。'
            '🛑 空串与缺键在这里被一并拒绝，理由：一份没有责任人签名的协议，'
            '在举证场景里等同于"四方里有人没签"—— 而那正是本条门禁要拦的东西。'
            '本函数不产 HTTP 码（库层产不出 403）；应用层据此抛 GATE_MISSING(2002, 403)，'
            '消息里带上本处的缺项名称（与 P0-25「硬规则缺项 → 403 且给出缺失项名称」同口径）。',
            v_missing_sign, array_length(v_sign_keys, 1);
    END IF;

    -- (5b) 🛑 退款/终止条款快照必须是**非空对象**
    --   PRD §2.9 / §四 逐字：「退款/终止条款**必须快照存储·不可覆盖**」。
    --   🛑 为什么把 `{}` 判为不合格（而不是"合法但内容为空"）：
    --     传 `{}` 等于"快照了零条条款"——那不是快照，是**占位**。
    --     而这一列的语义是"签的时候条款原文是什么"，它承担的举证责任是
    --     "后来条款改版了，但这份协议签的是旧版"。
    --     ⇒ 一个空的快照让**这份举证责任无法履行**，而它看起来"填了"。
    --       这正是本仓反复要防的"死字段"形态（与 V20 不收 effect_confirm_pdf 同族）。
    --   ⚠️ 注意与"是否含绝对化表述"无关：那是**内容合规**（ADR-12 词表 + scan 面
    --      管的是**客户端下发面**），不是本函数能判的 —— 本函数只判"是不是空快照"。
    --      不在这里做词表扫描是**有意的**：库层做不了词表治理，
    --      而把它做成"看起来做了"比不做更坏。
    v_clause_len := (SELECT count(*) FROM jsonb_object_keys(p_refund_clause_snapshot));

    IF v_clause_len = 0 THEN
        RAISE EXCEPTION
            'register_agreement: p_refund_clause_snapshot 是【空对象】({}）—— 拒绝登记签署。'
            '口径来源 = PRD §2.9 / §四 逐字：「退款/终止条款**必须快照存储·不可覆盖**；'
            '条款改版**不回溯**已签协议」。'
            '🛑 空对象不是"一条条款都没有的合法情形"，而是**占位**：'
            '这一列的唯一用途是事后证明"签的时候条款原文是什么"，'
            '空快照让这份举证责任无法履行，而它看起来"填了"。'
            '请传入签署当时的那一版条款原文（JSON 对象）。';
    END IF;

    -- (5c) 🛑 渲染稿非空 + hash 形态合法
    --   🛑🛑 见「设计取舍三」：本处**只校验形态，不能重算** ——
    --      实测 pg_extension 只有 plpgsql ⇒ 无 digest() ⇒ 库层无法算 SHA-256。
    --      任何"库层已校验 hash"的说法都是假的，必须留在注释里。
    IF btrim(p_rendered_snapshot) = '' THEN
        RAISE EXCEPTION
            'register_agreement: p_rendered_snapshot 是空白（或仅空格）—— 拒绝登记签署。'
            'PRD P0-27 逐字：「渲染结果必须落快照 + hash（否则事后无法证明"当时签的是哪一版"）」。'
            '🛑 空白正文与"没有正文"在举证上等价：一份空白协议无法证明任何事。';
    END IF;

    IF p_rendered_hash !~ '^[0-9a-fA-F]{64}$' THEN
        RAISE EXCEPTION
            'register_agreement: p_rendered_hash 形态非法（当前: %）。'
            '要求 = **64 位十六进制**（SHA-256 的规范长度）。'
            '🛑 三种常见非法形态与它们各自的真因：'
            '① 长度不对（32 位 = MD5 / 40 位 = SHA-1 / 128 位 = SHA-512）—— 算法用错；'
            '② 带 "sha256:" 前缀 / base64 / 含空格 —— 那是**另一种编码**，'
            '   解码它属"协议解析"，而 I8/H1 协议尚未冻结 ⇒ 库层不得猜；'
            '③ 含非十六进制字符 —— 不是摘要。'
            '🛑 大小写**不在**拒绝之列（见「设计取舍三」）：本函数接受 [0-9a-fA-F]，'
            '并在入库时统一 lower(...) —— 因为大小写是**同一个值的不同写法**，'
            '而"库里每一行 hash 都是同一种写法"这件事是事后对账能用字符串等值的前提。'
            '🛑🛑 本条**只校验形态**：库层无法从 rendered_snapshot 重算 SHA-256'
            '（实测 pg_extension 仅有 plpgsql，pgcrypto 未安装 ⇒ 无 digest()）。'
            '⇒ "这个 hash 确实是这份正文的摘要"这条性质的守卫在**应用层**：'
            'AgreementService 复用 DocFileService.sha256() 的同一口径做重算比对。'
            '不得把本条读成"库层已校验 hash 内容"。',
            coalesce(p_rendered_hash, '<NULL>');
    END IF;

    -- (6a) 🛑 模板指针必须**成对**（见「设计取舍四」①）
    IF (p_doc_template_id IS NULL) <> (p_doc_template_version IS NULL) THEN
        RAISE EXCEPTION
            'register_agreement: 模板指针不成对 —— doc_template_id = %，doc_template_version = %。'
            '两者要么**都给**、要么**都不给**。'
            '🛑 为什么单边指针不可接受：它们的用途是回答"这份协议签的是哪个模板的哪一版"'
            '（PRD P0-27 逐字：「旧版本不可覆盖」「签署时快照」）。'
            '只给 id 不给 version，答案只对了一半；只给 version 不给 id，更是无从查起。'
            '🛑 注意：**两个都不给是合法的**（该列对可空，实测 nullable）——'
            '"未用模板"是一等情形，不是缺口。',
            coalesce(p_doc_template_id::text, '<NULL>'),
            coalesce(p_doc_template_version::text, '<NULL>');
    END IF;

    -- (6b) 🛑 模板指针必须能在本租户内解析，且版本必须**与模板行自身相符**
    --      （见「设计取舍四」②）
    --      🛑 doc_template 的主键是**单列 template_id**（实测 pkey），
    --         即"每一个版本是一行、有自己的 id"⇒ doc_template_version 是**冗余副本**。
    --         故判据是"它必须等于该行的 version"，而不是"存在某一版叫这个名字"。
    IF p_doc_template_id IS NOT NULL THEN
        SELECT count(*), min(version) INTO v_tpl, v_tpl_version
          FROM doc_template
         WHERE template_id = p_doc_template_id AND tenant_id = p_tenant_id;

        IF v_tpl <> 1 THEN
            RAISE EXCEPTION
                'register_agreement: 模板 % 在租户 % 内不存在（本租户可见行数 = %）。'
                '🔴 与客户/方案同款提示：doc_template 的 RLS 也是 fail-closed，'
                '上下文不对会让"不是我的模板"与"我看不见我的模板"给出同一结论。'
                '本次上下文的实际值（已自证）= %。',
                p_doc_template_id, p_tenant_id, v_tpl, v_ctx;
        END IF;

        IF v_tpl_version IS DISTINCT FROM p_doc_template_version THEN
            RAISE EXCEPTION
                'register_agreement: 模板指针版本不符 —— 指针说 version = %，'
                '而模板 % 自己登记的 version = %。'
                '🛑 这条断言能抓住"指针说 v3、实际指向 v5 那一行"这类**静默错配** ——'
                '而那种错配恰好是"事后无法证明当时签的是哪一版"的成因。'
                '口径来源 = PRD P0-27 逐字：「旧版本不可覆盖」「签署时快照、已签不回溯」。',
                p_doc_template_version, p_doc_template_id, v_tpl_version;
        END IF;
    END IF;

    -- (7) 🛑🛑 幂等写入 + 跨租户撞号判定 —— 见文件头「核心机制二」
    --     三段判定全部来自库层原子事实，**没有**"先读后写"的并发窗口：
    --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的
    --       ② ROW_COUNT=0 且本租户看得见 ⇒ 是本租户的行 ⇒ 幂等命中
    --       ③ ROW_COUNT=0 且本租户看不见 ⇒ 插不进去又看不见 ⇒ 在别的租户名下
    --     🛑 `created_at` **不显式写**（与 V20 显式写 archived_at 相反）——
    --        这不是不一致，而是两列的语义不同：
    --          · V20 的 archived_at 是**业务时刻**（"归档发生在何时"）⇒ 必须显式写；
    --          · agreement.created_at 是**登记时刻**（表的 default now() 就是它的定义，
    --            实测 column_default = now()）⇒ 显式写反而会引入第二个来源。
    --        协议的业务时刻是 `signed_at`，它由入参提供（必填）。
    --        ⇒ 判据：**"业务时刻显式写、登记时刻交给 default"**，两处一致。
    --     🛑 `created_by` 用 coalesce 回落：与 V18 / V20 同款，
    --        但允许调用方传入更精确的值（它在库里是可空列）。
    INSERT INTO agreement (agreement_id, tenant_id, customer_id,
                           plan_id, plan_version,
                           refund_clause_snapshot, breach_clause_snapshot,
                           signed_at, signer,
                           doc_template_id, doc_template_version,
                           rendered_snapshot, rendered_hash, created_by)
    VALUES (p_agreement_id, p_tenant_id, p_customer_id,
            p_plan_id, p_plan_version,
            p_refund_clause_snapshot, p_breach_clause_snapshot,
            p_signed_at, p_signer,
            p_doc_template_id, p_doc_template_version,
            p_rendered_snapshot, lower(p_rendered_hash),
            coalesce(nullif(btrim(p_created_by), ''), 'agreement-offline-signing'))
    ON CONFLICT (agreement_id) DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- ① 本次真的建出来了
    IF v_affected = 1 THEN
        RETURN 'CREATED';
    END IF;

    -- ② 未插入 ⇒ 全局已存在。它在谁名下？
    --    🛑 这次读在【已自证的本租户上下文】里，故它只看得到本租户的行。
    SELECT count(*) INTO v_mine
      FROM agreement
     WHERE agreement_id = p_agreement_id;

    IF v_mine = 1 THEN
        RETURN 'ALREADY_EXISTS';
    END IF;

    -- ③ 插不进去却又看不见 ⇒ 它在别的租户名下
    --    🛑 这条 RAISE 就是"若照抄 V17 形态就会造出的缺陷"的封堵点。
    --       没有它，上面会 return 'ALREADY_EXISTS'，而本租户一份协议都没有 ——
    --       调用方据此把客户推进 AGREEMENT_SIGNED，于是
    --       **一个客户被放进了一条它没有资格走的门**（06 屏首次调理被放行，
    --       而协议从未被签署）。这条缺口不会报错、不会 23503、
    --       不会被任何下游抓住 —— 它只在 G1 取数时暴露
    --       （"已进入已签状态的客户，在 agreement 里查不到协议"）。
    RAISE EXCEPTION
        'register_agreement: agreement_id % 已被【另一租户】占用 —— 无法在本租户 % 名下登记。'
        '判定依据（无窗口）：INSERT ... ON CONFLICT (agreement_id) DO NOTHING 被【主键冲突】拦下'
        '（ROW_COUNT=0），但在已自证的本租户上下文里 SELECT 又看不见这一行（可见行数=0）——'
        '"冲突了"证明它全局存在，"看不见"证明它不属本租户 ⇒ 它属于别的租户。'
        '🛑 为什么这不是"另一个 ALREADY_EXISTS"：agreement_pkey 是【单列】agreement_id，'
        '即"一份协议的一生只属于一个租户"是既有 schema 的既定事实。'
        '这不是幂等重放，而是数据冲突，需要人工确认是 agreement_id 传错了、还是协议被错误调拨。'
        '🛑 为什么本域后果最直接：签署登记有一个**下游消费者** —— '
        'CustomerGateGuard 的 PLAN_APPROVED → AGREEMENT_SIGNED 跃迁。'
        '若在此返回 ALREADY_EXISTS，调用方会把客户推进已签态，而协议并不存在。',
        p_agreement_id, p_tenant_id::text;
END;
$v21_register$;


-- ---------------------------------------------------------------------------
-- latest_agreement_of(p_tenant_id, p_customer_id)
--
--   【返回】该客户在本租户内**最新**一份协议的 agreement_id；
--           **NULL = 本租户内该客户尚无协议**（即"未签"）。
--
--   【🛑 为什么 NULL 是一等返回值，而不是异常】
--     "尚未签署"是一个**正常的、可预期的**业务状态 ——
--     客户当然可以在还没签协议的时候被问"签了吗"（这正是 G1 要统计的分母）。
--     把它做成异常会逼调用方用 try/catch 表达一个正常判断，
--     而那正是"异常被吞掉"的温床。
--     🛑 措辞上要与 V17/V18/V20 保持同族纪律：
--        **NULL 不等于"该客户不存在"**。在 FORCE RLS 下，
--        "客户不属于本租户"与"客户存在但尚未签署"都会走到这里返回 NULL
--        —— 前一种情形应由 register_agreement 的 (4) 或 customer 读侧去区分，
--        不该由本函数承担。故本函数的 NULL **只**回答"有没有协议"。
--
--   【🛑 为什么"最新"的判定必须由库层给出一个**全序**】
--     `ORDER BY signed_at DESC` 单独**不够**：同一时刻签署的两份协议
--     （并发登记 / 同一次批量导入）会给出**不确定**的先后 ⇒ 本函数在两次调用中
--     可能返回不同的 agreement_id。这正是 `RefundWorkOrderPort.findLatestStatement`
--     注释里记载的同一族教训，也被 V20 的 latest_archive_of 逐字复述过。
--     ⇒ 这里显式给一个全序：`signed_at DESC, agreement_id DESC`。
--       第二个键不是"随便加的 tie-breaker"，而是让"最新"成为一个**确定**的答案。
--
--   【🛑 为什么按 signed_at 而不是 created_at 排序（本域独有的一处判断）】
--     两列的语义不同（与上面 (7) 的说明同源）：
--       · `signed_at` = **业务时刻**（协议何时被签）—— PRD §2.27 的
--         占位符 `${agreement.sign_date}` 取的就是它；
--       · `created_at` = **登记时刻**（这一行何时落库）。
--     一份**离线**协议完全可能"先签、后补录" ⇒ 若按 created_at 排序，
--     "哪一份是最新的协议"会变成"哪一份是最新录入的"，而补录顺序
--     与签署顺序无关（例如补录三天前签的那一份，会让它**盖过**昨天签的那一份）。
--     ⇒ 本函数的"最新"= **签署时刻最新**，这才与 G1 的取数口径一致。
--     🛑 这条判断的代价必须说清：若两份协议的 signed_at 相同（同一秒补录两版），
--        则由 agreement_id DESC 决定 —— 那个顺序**没有业务含义**，
--        只保证"确定"。需要"哪一版方案更新"的调用方应去看 plan 链，
--        而不是指望本函数。
--
--   【🛑 为什么本函数也建立上下文、也做一致性守卫】
--     它是**读**函数，但 agreement 是 FORCE RLS ⇒ 不建上下文则 USING 恒 false
--     ⇒ 恒返回 NULL —— 一个**"未签"与"我没设上下文"同形**的假绿。
--     一致性守卫同理：读函数也会 set_config，也会静默改写调用方上下文。
--     🛑 "只读所以不需要守卫"是一个很容易犯的错 —— 本仓在 V17 的读侧原语里
--        已经付出过这个代价，V20 也逐字复述过。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION latest_agreement_of(
    p_tenant_id   uuid,
    p_customer_id uuid
)
    RETURNS uuid
    LANGUAGE plpgsql
AS
$v21_latest$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_id         uuid;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'latest_agreement_of: p_tenant_id 不可为空（agreement.tenant_id 是 NOT NULL）';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION 'latest_agreement_of: p_customer_id 不可为空（协议的行级 scope 随客户）';
    END IF;

    -- (2) 上下文一致性守卫（与 register_agreement 同款，逐字同理由）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'latest_agreement_of: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '🛑 即使本函数只读也一样：set_config(app.tenant_id, ..., true) 是事务级设置'
            '且不会在函数入口自动重置，静默覆盖会让"后续读到的是谁的协议"变得不可推理。'
            '在 G1 取数场景下，那会直接产出一个**口径不明**的合规率。',
            v_ctx_before, p_tenant_id::text;
    END IF;

    -- (3) 建立并自证上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'latest_agreement_of 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 取最新一份（全序：signed_at DESC, agreement_id DESC —— 见上方说明）
    --     🛑 同时写 `tenant_id = p_tenant_id`：让这条 SELECT 的**租户维度在 SQL 里可见**，
    --        与 RLS 策略形成两层（RLS 兜底、谓词自证意图）。少写它 RLS 仍会兜住，
    --        但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读。
    SELECT agreement_id INTO v_id
      FROM agreement
     WHERE tenant_id = p_tenant_id
       AND customer_id = p_customer_id
     ORDER BY signed_at DESC, agreement_id DESC
     LIMIT 1;

    RETURN v_id;   -- 无行时是 NULL（见上方"NULL 是一等返回值"）
END;
$v21_latest$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15 / V17 / V18 / V19 / V20 第 2 节同款，理由逐字相同（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，
--       故本节的 GRANT 在常规环境里是 no-op；写它是"显式优于隐式"，
--       以及在"生产环境 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固"后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d2) 用 has_function_privilege 机械断言。
-- ============================================================================

DO
$v21_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_agreement') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_agreement(uuid, uuid, uuid, uuid, int, jsonb, '
            'timestamptz, jsonb, text, text, jsonb, uuid, int, text) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'latest_agreement_of') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION latest_agreement_of(uuid, uuid) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V21 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v21_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是**第 5 次**面对它：
--        · V15 踩过（初版 263 字符 ⇒ 「对于可变字符类型来说，值太长了(256)」）；
--        · V17 又踩过（初稿 358 字符，原文一模一样）；
--        · V18 把教训升级为"先量长度再写"，V19 / V20 沿用，本行同样**先量再写**。
--   本行的量法（python len() 对整条字符串计数 —— 本行全 ASCII，故 len() 即字符数）+ 结果：
--        实测 = 253 字符（上限 256，余量 **3**）
--   🛑🛑 本行**当场付了一次学费，必须逐字记下**（这是本仓第 6 次面对这条限制）：
--        本文档初稿写的描述是 286 字符，而**注释里声称**"实测 = 228（余量 28）"。
--        两个数字都是错的：**声称值少了 58**，而真实值**超限 30**。
--        即：我在写"必须先量再写"这条注释的同时，**没有量**。
--        这正是本仓反复记载的失效模式 —— **纪律写在注释里 ≠ 纪律被执行**。
--        ⇒ 处置：删掉超限的初稿，换成本行（253 字符，余量 3），
--          并把这次"注释里的数字也是拍的"一并登记 —— 因为下一个人会以为
--          "文件里写了数字 ⇒ 那个数字被量过"。**数字本身也需要被量。**
--   🛑 为什么余量只留 3：描述长度是**已冻结的事实**（这一行已经写进真库），
--      而"留大余量"只是一种偏好。本行的取舍是：把信息量放在优先位
--      （保留 V21 的两个函数名 + 缺口 + 门禁 + 边界），
--      并用**实测**保证它不超限 —— 而不是先写再指望它够短。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V21', 'agreement offline signing primitive: register_agreement() + latest_agreement_of(). agreement had no writer, so PRD G1 signature compliance had no source. Four-party signer + clause/render snapshot+hash gates. Ops path, no HTTP, H1 callback stays frozen.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   【本节的形态与 V20 自证逐节对应，但断言集合按本表的差异重推】
--     与 V17 / V18 / V19 / V20 同款的五段式：
--       (a0)/(a1) 能力守卫 → (a) 函数存在 → (b)(c) 函数体静态断言
--       → (d) 登记与权限 → (e) 行为验证（子事务内，块末整体撤销）→ (f) 清场自证
--
--   🛑 本节的断言**只读 prosrc（函数体原文）**，不读整个迁移文件 ——
--      理由与 V20 同款：读整个文件会把"注释里写着这句话"当成"代码里做到了这件事"。
--      本仓刚好在 V20 的初稿上踩过这个坑（注释先行、代码未落 ⇒ 断言本会假绿）。
--   🛑 本节刻意保留若干**静态**断言（b10/b11/b12/b13）与**行为**断言（e*）配对：
--      静态断言钉"代码里没有某写法"，行为断言钉"调用它会发生什么"。
--      两者缺一不可 —— 静态断言挡不住"用另一种写法达到同一效果"，
--      行为断言挡不住"这条路径恰好没被测到"。
-- ============================================================================

DO
$v21_guard$
DECLARE
    v_reg_body      text;
    v_lat_body      text;
    v_reg_code      text;
    v_lat_code      text;
    v_missing       text;
    v_cnt           int;
    v_reg           int;
    v_privilege     text;
    v_bypass        boolean;
    v_mode          text;
    v_msg           text;
    v_state         text;
    v_rejected      boolean := false;
    v_accepted      boolean := false;
    v_latest        uuid;
    v_slice         text;
    v_seg           text;
    v_reg_nocomment text;
    v_signer_after  text;
    v_hash_after    text;
    -- ---- 探针租户 / 客户 / 方案 / 协议（21 前缀，与 V17 的 17 / V18 的 18 /
    --      V19 的 19 / V20 的 20 互不重叠）----
    v_ta            uuid := '21000000-0000-0000-0000-00000000000a';
    v_tb            uuid := '21000000-0000-0000-0000-00000000000b';
    v_cust_a1       uuid := '21000000-0000-0000-0000-0000000000c1';  -- e1/e2/e15
    v_cust_a2       uuid := '21000000-0000-0000-0000-0000000000c2';  -- e17 第二份
    v_cust_a3       uuid := '21000000-0000-0000-0000-0000000000c3';  -- e17 第三份
    v_cust_b1       uuid := '21000000-0000-0000-0000-0000000000c4';  -- e15/e16 的目标
    v_plan_a1       uuid := '21000000-0000-0000-0000-0000000000e1';  -- 租户 A 的方案（v1）
    v_plan_a2       uuid := '21000000-0000-0000-0000-0000000000e2';  -- e17 的方案
    v_plan_b1       uuid := '21000000-0000-0000-0000-0000000000e3';  -- 租户 B 的方案
    v_agr_a1        uuid := '21000000-0000-0000-0000-0000000000d1';  -- e1/e2
    v_agr_a2        uuid := '21000000-0000-0000-0000-0000000000d2';  -- e4 方案版本不存在（不应落库）
    v_agr_a3        uuid := '21000000-0000-0000-0000-0000000000d3';  -- e3 客户不存在（不应落库）
    v_agr_a4        uuid := '21000000-0000-0000-0000-0000000000d4';  -- e5 plan_version=0（不应落库）
    v_agr_a5        uuid := '21000000-0000-0000-0000-0000000000d5';  -- e6 签署缺项（不应落库）
    v_agr_a6        uuid := '21000000-0000-0000-0000-0000000000d6';  -- e7 未登记键（不应落库）
    v_agr_a7        uuid := '21000000-0000-0000-0000-0000000000d7';  -- e8 签署空串（不应落库）
    v_agr_a8        uuid := '21000000-0000-0000-0000-0000000000d8';  -- e9 条款空对象（不应落库）
    v_agr_a9        uuid := '21000000-0000-0000-0000-0000000000d9';  -- e10 短 hash（不应落库）
    v_agr_aa        uuid := '21000000-0000-0000-0000-0000000000da';  -- e10 大写 hash（**应**落库）
    v_agr_ab        uuid := '21000000-0000-0000-0000-0000000000db';  -- e11 带前缀 hash（不应落库）
    v_agr_ac        uuid := '21000000-0000-0000-0000-0000000000dc';  -- e12 模板指针不成对
    v_agr_ad        uuid := '21000000-0000-0000-0000-0000000000dd';  -- e13 同租户裸 INSERT 对照
    v_agr_ae        uuid := '21000000-0000-0000-0000-0000000000de';  -- e14 跨租户撞号（租户 A 先占）
    v_agr_af        uuid := '21000000-0000-0000-0000-0000000000df';  -- e14 之后租户 B 用自己的 id 建
    v_agr_ag        uuid := '21000000-0000-0000-0000-0000000000e4';  -- e17 早签（先写）
    v_agr_ah        uuid := '21000000-0000-0000-0000-0000000000e5';  -- e17 晚签（次写）
    v_agr_ai        uuid := '21000000-0000-0000-0000-0000000000e6';  -- e17 中间签（最后写 ⇒ 陷阱）
    -- 四方签署的键名常量（自证 (b7) 逐项断言用；与函数内数组必须一致）
    v_sign_keys     text[] := ARRAY['customer', 'meridian_therapist', 'therapist', 'store_owner'];
    -- 一份合法的 4 方签署块（探针复用；逐个用例再按需改动）
    v_signer_ok     jsonb := '{"customer":"赵一","meridian_therapist":"钱二",'
                             '"therapist":"孙三","store_owner":"李四"}'::jsonb;
    -- 一份合法的条款快照
    v_clause_ok     jsonb := '{"refund":"协商一致可终止","termination":"提前7日告知"}'::jsonb;
    -- 一份合法的 64 位 hash（小写 / 大写各一，用于 e10 的两半）
    v_hash_lower    text := repeat('a', 64);
    v_hash_upper    text := repeat('A', 64);
BEGIN
    -- ==================================================================
    -- (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
    --   理由与 V18 / V20 的 (a0) 逐字相同，此处不复述全文。
    --   摘要：本自证里 (e14)/(e15)/(e16) 的有效性建立在"当前角色受 RLS 约束"上；
    --        BYPASSRLS 会让 (e15) 以"竟然返回 ALREADY_EXISTS"的面目报红，
    --        而那是【归因错误】的红（函数是对的，前提不成立）。
    --   🛑 为什么必须用 `rolbypassrls` 而不是 `current_setting('is_superuser')`：
    --      两者不等价 —— 一个**非超级用户**也可以被授予 BYPASSRLS。
    -- ==================================================================
    SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;

    IF v_bypass IS NULL THEN
        RAISE EXCEPTION
            'V21 自证失败(a0): 查不到当前角色 % 的 rolbypassrls 属性（pg_roles 无此角色？）', current_user;
    END IF;
    IF v_bypass THEN
        RAISE EXCEPTION
            'V21 自证失败(a0): 当前角色 % 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 3 条判据全部失去效力】，'
            '继续执行只会产出一份"看起来跑了、实际什么都没验"的证据。'
            '🛑 具体失效路径：BYPASSRLS 会绕过行级安全 ⇒ '
            '以租户 B 的上下文执行 `SELECT count(*) FROM agreement WHERE agreement_id = <租户A的协议>` '
            '仍会看到那一行 ⇒ register_agreement 的判定链落到"是我的"分支 ⇒ 返回 ALREADY_EXISTS ⇒ '
            '自证 (e15) 以"跨租户撞号没有抛错"的面目报红。'
            '🛑 那条红是【归因错误】的：函数是对的，是证据的效力前提不成立。'
            '🛑 另外说明为什么本仓的应用角色是对的：实测 diaoyuanyun 的 '
            'super=f / bypassrls=f ⇒ 生产路径上 RLS 是真的在起作用。',
            current_user;
    END IF;

    -- ==================================================================
    -- (a1) 🛑🛑 能力守卫二：策略本身必须还按 app.tenant_id 隔离
    --   与 V20 的 (a1) 同款。理由：若有人把 agreement 的策略改成
    --   `USING (true)`，本自证的 RLS 判据会**全部假绿**（写进去的行谁都看得见），
    --   而那是一条比"函数写错"更严重的状态 —— 它是**隔离本身**被拿掉了。
    --   ⇒ 这里读 pg_policies 的 qual/with_check 原文，断言它逐字引用了 app.tenant_id。
    -- ==================================================================
    SELECT count(*) INTO v_cnt
      FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'agreement'
       AND policyname = 'tenant_isolation'
       AND qual       LIKE '%app.tenant_id%'
       AND with_check LIKE '%app.tenant_id%';

    IF v_cnt <> 1 THEN
        RAISE EXCEPTION
            'V21 自证失败(a1): agreement 的 tenant_isolation 策略不存在、'
            '或其 USING / WITH CHECK 未逐字引用 app.tenant_id（命中数 = %）。'
            '🛑 若有人把策略改成 `USING (true)`，本自证的 RLS 判据会全部假绿 —— '
            '写进去的行谁都看得见。那是"隔离本身被拿掉"，比"函数写错"严重得多。'
            '🛑 本判据读的是 pg_policies 的**策略原文**，不是"策略存在"这件事。', v_cnt;
    END IF;

    -- ==================================================================
    -- (a) 函数存在
    -- ==================================================================
    SELECT string_agg(x.n, ', ' ORDER BY x.n) INTO v_missing
      FROM (VALUES ('register_agreement'), ('latest_agreement_of')) AS x(n)
     WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
         WHERE ns.nspname = 'public' AND p.proname = x.n);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V21 自证失败(a): 以下函数未创建成功 -> %', v_missing;
    END IF;

    -- ==================================================================
    -- (b)(c) 函数体断言（🛑 只读 prosrc = 函数体原文，不读整个文件）
    -- ==================================================================
    SELECT prosrc INTO v_reg_body FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_agreement';
    SELECT prosrc INTO v_lat_body FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'latest_agreement_of';

    IF v_reg_body IS NULL THEN
        RAISE EXCEPTION 'V21 自证失败(b): 读不到 register_agreement 的函数体';
    END IF;
    IF v_lat_body IS NULL THEN
        RAISE EXCEPTION 'V21 自证失败(c): 读不到 latest_agreement_of 的函数体';
    END IF;

    -- ==================================================================
    -- (b0) 🛑🛑 先构造【剥注释后的代码态】—— 本迁移全部静态判据的**唯一载体**
    --
    --   🛑🛑 本节为什么必须有这一步（不是风格问题，是正确性问题）：
    --      本仓第 41 条缺陷记载的形态 ——"判据被注释满足"（假绿），它的**另一面**
    --      是"判据被注释挡灭"（假红）。两者同一个根因：**没有区分"文本里有这个词"
    --      与"代码里有这件事"**。
    --      ⇒ 纪律：**凡是断言"代码里有没有某个语句/写法"的判据，其载体必须是
    --        剥掉注释后的代码态**，不得直接用 prosrc 原文。
    --
    --   🛑🛑 本条曾在本迁移上**实测复发两处**（是与 V20 的 121 反向验证抓出的
    --      缺陷①同型，不是理论风险）：
    --      · (b5)/(b6)：函数体末尾那条跨租户 RAISE 的**消息字符串**里逐字写着
    --          `INSERT ... ON CONFLICT (agreement_id) DO NOTHING 被【主键冲突】拦下`。
    --        旧判据取 `substring(v_reg_body ... for 3000)` ⇒ 区间覆盖了这条消息 ⇒
    --        **把真正的 INSERT 改成 `ON CONFLICT (tenant_id, agreement_id)`
    --        （错误改法，使跨租户 RAISE 成死代码）后，(b6) 仍被那个字面量满足
    --        ⇒ 自证照常通过（静默假绿）。**
    --      · (c1)：latest_agreement_of 的注释里逐字写着
    --          `` 🛑 同时写 `tenant_id = p_tenant_id`：… `` ⇒
    --        **删掉真正的 `WHERE tenant_id = p_tenant_id` 后，(c1) 仍被注释满足
    --        ⇒ 静默假绿。**
    --      ⇒ 两处的处置都不是"补一个特判"，而是**把载体换掉**（见下）。
    --
    --   🛑 三个变量的职责必须分清（否则会重蹈 (b7b) 的"两个不同的量相比"）：
    --      · v_reg_code / v_lat_code  —— **只剥注释**，保留原排版。
    --        用途：语句/子句形态判据（b1~b6, b9~b14, c1~c3）。
    --      · v_reg_nocomment          —— 剥注释 **+ 压空白**。
    --        用途：需要"键名序列"这类**排版无关**的逐字匹配（b7），
    --              以及依赖区间边界的计数（b7b）。
    --      · v_slice                  —— 从 v_reg_code 里**按语句边界**切出的一条语句。
    --        用途：(b5)(b6)(b9) 只在**那一条 INSERT** 上断言（见下）。
    --      ⇒ v_reg_nocomment **必须从 v_reg_code 派生**，不得各自 `regexp_replace(v_reg_body)`:
    --        否则两个变量各自从原文派生，就又是"两个不同的量"。
    -- ==================================================================
    v_reg_code := regexp_replace(v_reg_body, '--[^\n]*', '', 'g');
    v_lat_code := regexp_replace(v_lat_body, '--[^\n]*', '', 'g');
    v_reg_nocomment := regexp_replace(v_reg_code, '\s+', ' ', 'g');

    -- ---- (b0b) 代码态构造必须**真的发生了** ----
    --   🛑 为什么必须自证这一步：若有人把上面三行删掉（或写错），
    --      后面的全部静态判据会退化到"在 NULL 上匹配"——
    --      而 `NULL !~ '...'` 的结果是 NULL（不是 true），
    --      `IF NULL THEN ... END IF` **不执行** ⇒ **整段静态判据静默消失**，
    --      只剩行为段在跑 —— 那正是本仓最怕的"看起来跑了很多断言"。
    --      ⇒ 三条断言：非空、确实短于原文（剥注释真的删掉了东西）、且不含注释记号的残留。
    IF v_reg_code IS NULL OR v_lat_code IS NULL OR v_reg_nocomment IS NULL THEN
        RAISE EXCEPTION
            'V21 自证失败(b0b): 代码态构造失败（v_reg_code / v_lat_code / v_reg_nocomment 有 NULL）。'
            '🛑 NULL 载体上的 `!~` 返回 NULL ⇒ IF 不执行 ⇒ 整段静态判据静默消失。';
    END IF;
    IF length(v_reg_code) = 0 OR length(v_lat_code) = 0 THEN
        RAISE EXCEPTION
            'V21 自证失败(b0b): 代码态为空 —— 剥注释把函数体整个吃掉了（正则写错？）。'
            '长度 reg=% / lat=%', length(v_reg_code), length(v_lat_code);
    END IF;
    IF length(v_reg_code) >= length(v_reg_body) THEN
        RAISE EXCEPTION
            'V21 自证失败(b0b): v_reg_code 不比 prosrc 短（% vs %）—— '
            '说明"--[^\n]*"这条剥注释正则**没有匹配到任何注释**。'
            '🛑 本函数体里确实有多条注释（实测 11 条以上）⇒ 这个结果只能是正则写错。',
            length(v_reg_code), length(v_reg_body);
    END IF;

    -- ---- (b0c) 语句切片必须**有终点锚点** ----
    --   🛑🛑 旧判据是 `for 3000`（固定长度窗口）—— 那是一个**没有终点语义**的边界：
    --      窗口开多大是拍出来的，而"开多大才够"取决于后面写了多少注释与消息。
    --      实测本函数体的 INSERT 语句在 2206 字符处结尾，而 3000 的窗口把后面
    --      那条跨租户 RAISE 的消息一并包了进来 ⇒ 判据在**别人的文本**上被满足。
    --      ⇒ 改用 `split_part(..., ';', 1)`：切到该语句的**第一个分号**为止。
    --        这是有语义的边界（SQL 语句以 `;` 结束），不是拍出来的长度。
    --   🛑 若 INSERT 语句内部出现分号（本函数没有：VALUES 与 ON CONFLICT 都不含），
    --      该写法会**截断** ⇒ 因此下面 (b0c) 断言切出来的片段里**必须仍能看到
    --      语句的完整面貌**（列清单 + VALUES），否则报红。
    --
    --   🛑🛑 本条**曾经写错一次，必须逐字记下**（本仓第 45 条②）：
    --      初版第二条断言的是"切片里必须有 ON CONFLICT" —— 理由是"用它区分
    --      '真的删掉了 ON CONFLICT' 与 '切片被截断'"。**实测该设计有缺陷**：
    --        122 的 I(b5)（真的删掉 ON CONFLICT）**首报错是 (b0c) 而不是 (b5)**
    --        ⇒ 一条"语义缺失"的错误被记成了"切片失效"的标签 ⇒ **归因错误**
    --        （这正是本迁移 (e15) 那条 RAISE 反复强调的同一族问题）。
    --      根因：这条断言**先于** (b5) 执行，而它与 (b5) 检查的是**同一件事**。
    --      修法：切片完整性改用 **VALUES** 做信号 —— `VALUES` 在语句中间，
    --        它缺失只能意味着"切片被截断"（一个完整的 INSERT 不可能没有 VALUES）；
    --        而"有没有 ON CONFLICT"由 (b5) **独家**判定 ⇒ 两种含义各有唯一标签。
    v_slice := split_part(
                   substring(v_reg_code from position('INSERT INTO agreement' in v_reg_code)),
                   ';', 1);
    IF v_slice IS NULL OR v_slice !~* 'INSERT\s+INTO\s+agreement' THEN
        RAISE EXCEPTION
            'V21 自证失败(b0c): 未能从代码态里切出 INSERT INTO agreement 语句'
            '（切出长度 = %）。🛑 切片无载体 ⇒ 依赖它的 (b5)(b6)(b9) 会退化为 NULL 比较'
            '（`IF NULL` 不执行）⇒ 三条判据静默消失。', coalesce(length(v_slice), -1);
    END IF;
    IF v_slice !~* '\mVALUES\M' THEN
        RAISE EXCEPTION
            'V21 自证失败(b0c): 切出的 INSERT 片段里没有 VALUES —— '
            '🛑 这只可能是**切片被截断**（INSERT 内部出现了分号？）。'
            '本条的用途是让"切片失效"（本条）与"语义缺失"（(b5) 管 ON CONFLICT）'
            '**各有唯一标签**，不得互相顶替。';
    END IF;

    -- ---- (b1)(b2)(b3) 逐函数检查三件套（建立上下文 / 自证 / 一致性守卫）----
    --   🛑 这三条**必须对【两个】函数都查**，包括那个只读函数。
    --     理由：本仓在 V17 的读侧原语上付出过代价 —— "只读所以不需要守卫"
    --     是一个很容易犯的错，而它的后果是**静默返回 NULL**（看起来像"未签"）。
    --   🛑 载体 = **代码态**（v_reg_code / v_lat_code，见 (b0)）：
    --     这三处的字面量在注释里也出现过描述 ⇒ 用 prosrc 原文会假绿。
    IF v_reg_code !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b1): register_agreement 未建立 app.tenant_id 上下文';
    END IF;
    IF v_reg_code !~ 'assert_tenant_context\s*\(\s*\)' THEN
        RAISE EXCEPTION 'V21 自证失败(b2): register_agreement 未调用 assert_tenant_context() 自证上下文';
    END IF;
    IF v_reg_code !~ 'current_setting\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b3): register_agreement 缺上下文一致性守卫（未读既有 app.tenant_id）';
    END IF;
    IF v_lat_code !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b1): latest_agreement_of 未建立 app.tenant_id 上下文'
            '—— 它是读函数，但 agreement 是 FORCE RLS ⇒ 不建上下文则 USING 恒 false'
            ' ⇒ 恒返回 NULL —— 一个"未签"与"我没设上下文"同形的假绿';
    END IF;
    IF v_lat_code !~ 'assert_tenant_context\s*\(\s*\)' THEN
        RAISE EXCEPTION 'V21 自证失败(b2): latest_agreement_of 未调用 assert_tenant_context()';
    END IF;
    IF v_lat_code !~ 'current_setting\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b3): latest_agreement_of 缺上下文一致性守卫'
            '（读函数也会 set_config，也会静默改写调用方上下文）';
    END IF;

    -- ---- (b4) register_agreement 的写入语句存在且目标是 agreement ----
    IF v_reg_code !~ 'INSERT\s+INTO\s+agreement' THEN
        RAISE EXCEPTION 'V21 自证失败(b4): register_agreement 里没有 `INSERT INTO agreement`';
    END IF;

    -- ---- (b5) 该 INSERT 必须带 ON CONFLICT（且在锚点范围内）----
    --   🛑 判定用**语句切片**（v_slice，见 (b0c)）—— 保证 ON CONFLICT 属于**这条**
    --      INSERT，而不是碰巧在函数体别处出现（本仓在 V18 上用过同一手法）。
    --   🛑🛑 旧版用的是 `substring(v_reg_body ... for 3000)`（固定长度窗口），
    --      实测**被函数体末尾那条跨租户 RAISE 的字符串字面量满足**：
    --      那条消息里逐字写着 `INSERT ... ON CONFLICT (agreement_id) DO NOTHING
    --      被【主键冲突】拦下` ⇒ 把真正的 ON CONFLICT 改成含 tenant_id 的形态后，
    --      本判据仍"通过"（静默假绿）。现改为：载体 = 剥注释代码态 + 切片终点 = 语句分号。
    IF v_slice !~* 'ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V21 自证失败(b5): register_agreement 的 INSERT 没有 ON CONFLICT —— '
            '那意味着重复登记会以 23505 失败，而不是幂等返回 ALREADY_EXISTS。'
            'PRD G1 的取数是"已签客户 / 全部客户"，而离线补录会重复触发同一份协议。';
    END IF;

    -- ---- (b6) 🛑 推断目标必须**只含主键列** agreement_id（语义，非某一种语法）----
    --   与 V20 的 (b6) 同款，理由相同：推断目标决定"跨租户撞号"是否可能发生，
    --   而本函数的跨租户判定分支**完全建立**在"推断目标是全局单列主键"之上。
    --   🛑 若有人改成 `ON CONFLICT (tenant_id, agreement_id)`，本函数的 RAISE 分支
    --      变成死代码，而自证 (e15) 会以"竟然成功了"的面目失败（归因错误）。
    --   🛑🛑 本条是**实测抓出的静默假绿点**（见 (b0) 的说明）：旧版载体含 RAISE 消息，
    --      而那消息里逐字重复了正确的推断目标 ⇒ 错误改法不会被抓住。
    --      现载体 = v_slice（代码态 + 语句边界）⇒ 判据只可能被**真正的那条语句**满足。
    --   🛑🛑 断言对象是**语义**（"推断目标不含 tenant_id"），不是**某一种语法**——
    --      这一条由 122 的 I(b6-b)/C4 实测逼出来：初版只收
    --      `ON CONFLICT (agreement_id)` 一种写法，于是**语义完全等价**的
    --      `ON CONFLICT ON CONSTRAINT agreement_pkey`（本仓 V20 已实测接受的形态）
    --      被判为**假红**。判据接受两种写法，与 V20 (b6) 逐字同款。
    IF v_slice !~* '(ON\s+CONFLICT\s*\(\s*agreement_id\s*\)'
                   '|ON\s+CONFLICT\s+ON\s+CONSTRAINT\s+agreement_pkey\M)' THEN
        RAISE EXCEPTION
            'V21 自证失败(b6): ON CONFLICT 的推断目标不是【全局主键 agreement_id】。'
            '🛑 本函数的核心机制二（跨租户撞号 ⇒ RAISE）建立在这个推断目标上：'
            '正因为它不含 tenant_id，"同一 agreement_id 被别的租户占用"才可能发生。'
            '若改成 (tenant_id, agreement_id)，该冲突不可能发生 ⇒ '
            'RAISE 分支成为死代码，而 (e15) 会以归因错误的方式失败。'
            '🛑 判据接受两种**语义等价**形态：`ON CONFLICT (agreement_id)` 与 '
            '`ON CONFLICT ON CONSTRAINT agreement_pkey`（agreement_pkey 实测为单列主键）。'
            '🛑 遇到这条报错，正确反应是重推设计，而不是放宽本判据。';
    END IF;

    -- ---- (b6-负向) 🛑 锚点段内**不得**把 tenant_id 拉进推断目标 ----
    --   🛑 为什么"正向判据"不够（V20 逐字同理由）：正向判据里那句
    --      `ON\s+CONFLICT\s*\(\s*agreement_id\s*\)` 在
    --      `ON CONFLICT (agreement_id, tenant_id)` 上**不匹配**（agreement_id 后还有逗号），
    --      这一条恰好能兜住它；(b6) 的约束名分支也兜不住 `(agreement_id, tenant_id)`。
    --      两条合起来才完整：(b6) 要求"存在一个只含 agreement_id 的推断目标"，
    --      本条要求"该 INSERT 语句里不存在含 tenant_id 的推断目标"。缺任一条都有一类漏网。
    --   🛑 122 的 I(b6-a)/(b6-c) 分别钉住这两条路径。
    IF v_slice ~* 'ON\s+CONFLICT\s*\([^)]*tenant_id' THEN
        RAISE EXCEPTION
            'V21 自证失败(b6-负向): register_agreement 的 INSERT 把 tenant_id '
            '拉进了 ON CONFLICT 的推断目标。'
            '🛑 这正是本判据唯一要防的事：推断目标一旦含 tenant_id，'
            '"同一 agreement_id 被另一租户占用"就**不可能发生** ⇒ '
            '跨租户撞号的 RAISE 分支成为死代码，而调用方在跨租户撞号时会收到 '
            'ALREADY_EXISTS —— 即"一个客户被放进 AGREEMENT_SIGNED，而协议并不存在"。'
            '这条缺口不会报错、不会 23503，只在 G1 取数时暴露。';
    END IF;

    -- ---- (b7) 🛑🛑 四方签署键集必须**恰 4 个且逐字为这四个** ----
    --   🛑 锚在函数体的 **ARRAY 字面量**上断言，而不是锚在"函数里有 4 个键"这种模糊说法。
    --      作用：若有人把某一方（例如 store_owner）从必填里去掉，
    --      本断言会红 —— 而那是**PRD §八 硬门禁的实质削弱**。
    --      静态防线 = 本条；行为防线 = (e6)。
    --   🛑🛑 本条**当场付过一次学费，必须逐字记下**（本仓第 5 条④）：
    --      初版写的是 `v_reg_body !~ '''customer''\s*,\s*''meridian...` —— 假定
    --      函数体里 ARRAY 的 4 个元素是**逐字连续的一行**。
    --      实际函数体里每个元素**各自带一个行内注释**（`'customer',  -- 客户（…）`），
    --      ⇒ 初版断言**永远匹配不上**，在真库应用时以 (b7) 报红。
    --      ⇒ 教训与第 41 条（判据被注释满足）**同源但方向相反**：
    --         第 41 条是"注释把断言**点亮**"（假绿），
    --         本条是"注释把断言**挡灭**"（假红）。两者都是**没剥注释**造成的。
    --   🛑 处置：先剥注释、再把连续空白压成单空格，然后在**规范化后的文本**上匹配。
    --      为什么压空白：跨行的 `ARRAY[` 折行后元素之间会有换行+缩进，
    --      而"逐字"要钉的是**键名序列**，不是它的排版。
    --   🛑 v_reg_nocomment 已在 (b0) 由 **v_reg_code 派生**（剥注释 + 压空白），
    --      此处不再重复构造 —— 重复构造会让两个变量各自从 prosrc 派生，
    --      那正是"两个不同的量"（见 (b7b) 的教训）在本节的同型再现。
    IF v_reg_nocomment !~ '''customer''\s*,\s*''meridian_therapist''\s*,\s*''therapist''\s*,\s*''store_owner''' THEN
        RAISE EXCEPTION
            'V21 自证失败(b7): register_agreement 里四方签署的键集不再是'
            ' 逐字的 [customer, meridian_therapist, therapist, store_owner]。'
            '口径来源 = PRD C.1.5 §八 逐字：「json{客户/经络师/调理师/门店负责人:签名+日期}'
            ' | **未签不得首次调理或退款判定** | **硬门禁**」。'
            '🛑 去掉任何一方都是对硬门禁的实质削弱 —— 本断言就是为了让它必然可见。'
            '🛑 注意本条已**剥注释+压空白**后再匹配（见文件头第 5 条④）：'
            '函数体里 ARRAY 的每个元素各带一个行内注释，若不剥注释则本断言恒红。'
            '若待裁第 1 条(a) 最终裁定改用中文键名，请**同步改本断言**，'
            '而不是删掉它（本仓纪律：归零要求显式动作）。';
    END IF;

    -- ---- (b7b) 🛑 键集必须**恰 4 个**（不止"包含这四个"）----
    --   作用与 (b7) 互补：(b7) 钉"四个都在"，本条钉"没有第五个"。
    --   🛑 为什么需要它：若有人把键集扩成 5 个（例如加 'witness' 见证人），
    --      (b7) 的子串匹配**仍然通过** —— 而"五方"已不是 PRD 的四方签署。
    --      这是"子串匹配挡不住扩张"这一失效模式的唯一补丁。
    IF v_reg_nocomment !~ 'ARRAY\s*\[\s*''customer''[^\]]*\]' THEN
        RAISE EXCEPTION
            'V21 自证失败(b7b): 找不到形如 ARRAY[ ''customer'', ... ] 的键集字面量。'
            '本自证依赖"能从函数体里读出一个封闭的键集数组"，'
            '若键集被改成别种写法（如拼接生成），本条会红 —— 那是**刻意的**：'
            '一个无法静态读出键集的实现，其"恰 4 个"就无法被机械证明。';
    END IF;
    --   计数：剥注释+压空白后，ARRAY[ ... ] 区间内的**单引号字符**必须恰 8 个
    --         （= 4 个键名 × 每个键名首尾 1 对引号）。
    --   🛑🛑 本条**当场付过一次学费，两次都是"两个不同的量相减/相比"**：
    --      第 1 版：`(...) / 2 <> 8` —— 除 2 得到"键个数"却拿它比 8 ⇒ 恒真。
    --      第 2 版：`length(v_reg_nocomment) - length(replace(substring(...)))`
    --               —— 被减数是**整段文本**、减数是**分段文本**，
    --              两个不同的量相减 ⇒ 差值 = 分段长度 + 整段非引号字符数，必然 ≠ 8。
    --      ⇒ 教训：**减法/比较的两侧必须来自同一个量**。这不是笔误，是本仓反复
    --        记载的失效模式（"量的口径不一致"）在算术层面的同型再现 ——
    --        与待裁第 4 条的 `||` 优先级、description 长度的"拍数字"是同一个根：
    --        **表达式看起来对，但两侧说的不是同一件事。**
    --      ⇒ 处置：先把分段取出来存进变量 v_seg，再对**同一个 v_seg** 取长度和替换。
    v_seg := substring(v_reg_nocomment from 'ARRAY\s*\[\s*''customer''[^\]]*\]');
    IF (length(v_seg) - length(replace(v_seg, '''', ''))) <> 8 THEN
        RAISE EXCEPTION
            'V21 自证失败(b7b): 四方签署键集的**引号数量**不是 8（= 4 个键名 × 每名 2 个引号）。'
            '⇒ 键集不是恰 4 个。🛑 多一个键 = 凭空多出一方签署人；'
            '少一个键 = 硬门禁被削弱。两者都必须可见。';
    END IF;

    -- ---- (b8) 🛑 本函数**不得**包含 V20 那种"警告门禁"结构 ----
    --   🛑 这一条断言的是【本域没有警告项】这件事本身。
    --      背景：V20 的核心机制三是"5 项硬门禁 + 1 项警告门禁（手环）"，
    --      而那条警告的存在是因为 PRD 明确写了"手环缺项不得反转为阻断"。
    --      本域**没有**对应的 PRD 语句 —— PRD §八 对四方签署说的是
    --      "**未签不得首次调理或退款判定**"，四方**全部**是硬门禁。
    --      ⇒ 若有人把 V20 的 `v_warn_gate_keys` 之类的结构照抄进来
    --        （哪怕动机是"更宽容更安全"），他就**凭空造出了一条 PRD 没有的宽免**，
    --        从而使"某一方没签也能过门禁"成为可能。
    --      静态防线 = 本条；行为防线 = (e6)（缺任一方都必须 RAISE）。
    --   🛑🛑 本条**必须**匹配【剥注释后】的文本 —— 这是本仓第 41 条缺陷的正向应用。
    --      实测：函数体里**恰好有一条解释性注释**写了 `v_warn_gate_keys`：
    --          `-- ⇒ 本数组**不存在**对应的"警告键集"（V20 的 v_warn_gate_keys）。`
    --      若在本条里匹配 v_reg_body（含注释），它会**恒红** ——
    --      而那是"注释把断言点亮"造成的**假红**，与第 41 条的假绿是同一个根因
    --      （没剥注释）。本条与 (b7) 因此共用 v_reg_nocomment。
    --   🛑 载体选 v_reg_nocomment（= v_reg_code 压空白）而非 v_reg_code：
    --      本体是"**不得**出现某写法"的**否定**判据，压空白只会让跨行拼接的
    --      变体更容易被抓住（`warn_gate` 与 `v_warn_gate_keys` 跨行不算风险）。
    IF v_reg_nocomment ~* 'v_warn_gate_keys?|warn_gate|_warn_key' THEN
        RAISE EXCEPTION
            'V21 自证失败(b8): register_agreement 里出现了"警告门禁"结构'
            '（匹配到 v_warn_gate_keys / warn_gate / _warn_key 之一）。'
            '🛑 本域**没有警告项**：PRD §八 对四方签署说的是'
            '「**未签不得首次调理或退款判定**」—— 四方全部是硬门禁。'
            'V20 的警告门禁（手环）存在，是因为 PRD 逐字写了'
            '「**手环类缺项不得反转为阻断**」（P0-25 + ARC-07/08/11）——'
            '那是**一条明确的宽免要求**，而本域没有任何对应语句。'
            '⇒ 照抄 V20 的警告结构等于**凭空造出一条 PRD 没有的宽免**，'
            '后果是"某一方没签也能过门禁"—— 而那会把硬门禁②/③ 变空。';
    END IF;

    -- ---- (b9) 归档路径的租户维度必须在 SQL 里可见（INSERT 列清单里含 tenant_id）----
    --   🛑 与 V20 的 (b9) 同款：少写它 RLS 仍会兜住（WITH CHECK 会用策略表达式），
    --      但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读。
    --      本仓把可读性当成安全属性的一部分。
    IF v_slice !~ 'INSERT\s+INTO\s+agreement\s*\(\s*agreement_id\s*,\s*tenant_id' THEN
        RAISE EXCEPTION
            'V21 自证失败(b9): register_agreement 的 INSERT 列清单未以 '
            '`(agreement_id, tenant_id, ...)` 开头 —— 租户维度在 SQL 文本里不可见。'
            '🛑 这不是功能性缺陷（RLS 会兜住），而是**可读性即安全**的纪律：'
            '一个必须靠推理才能确认不跨租户的语句，会在 review 时被跳过。';
    END IF;

    -- ---- (b10)(b11) 🛑 本迁移不提供任何**改写**通路 ----
    --   见文件头「为什么没有确认签署/撤销签署原语」第 ③ 条：
    --   任何"把协议置为已签/未签"的第二通路 = 绕过 H1 的旁路。
    --   🛑 判据必须**先剥掉注释**再匹配 —— 否则上面那一大段解释"为什么不能写 UPDATE"
    --      的注释本身会把本断言点亮。这是本仓第 41 条缺陷（"判据被注释满足"）。
    --   🛑🛑 载体改用 **v_reg_code / v_lat_code**（见 (b0)），不再在此处重新剥注释：
    --      旧版把同一个变量 v_slice 先当"INSERT 语句切片"用（(b5)/(b6)/(b9)），
    --      又在此处**重新赋值**成"整个函数体剥注释版"—— 一个变量两种含义，
    --      是本仓记载的"量的口径不一致"在变量层面的同型再现。
    --      现在职责单一：v_slice 恒为"INSERT 那一条语句"，代码态用 v_reg_code/v_lat_code。
    IF v_reg_code ~* '\mUPDATE\M\s+agreement\M' THEN
        RAISE EXCEPTION
            'V21 自证失败(b10): register_agreement 里出现了 `UPDATE agreement`。'
            '🛑 本迁移**不提供任何改写通路**，理由三条（见文件头），最硬的一条：'
            '任何"把协议置为已签/未签"的第二通路都**等于**开设一条绕过 H1 的旁路 —— '
            '而 H1 之所以被禁止编码，恰恰因为"没有验签就无法确认签署是真的"。'
            '提供改写能力 = 把"未验签即可自证已签"搬进库里，只是换了个入口。';
    END IF;
    IF v_reg_code ~* '\mDELETE\M\s+FROM\s+agreement\M' THEN
        RAISE EXCEPTION
            'V21 自证失败(b11): register_agreement 里出现了 `DELETE FROM agreement`。'
            '🛑 协议是**举证材料**：PRD 的替代路径是"重新出方案 → 重新签**新的一份**"，'
            '而不是抹掉旧的那份。删除会毁掉"当时签的是什么"这条证据链。';
    END IF;
    --   🛑 同 (b7)：正则跨行拼接必须加括号（`||` 与 `~*` 同级左结合，见 (b7) 说明）。
    IF v_lat_code ~* ('\mUPDATE\M\s+agreement\M|'
                 || '\mDELETE\M\s+FROM\s+agreement\M|'
                 || '\mINSERT\M\s+INTO\s+agreement\M') THEN
        RAISE EXCEPTION
            'V21 自证失败(b11): latest_agreement_of（读原语）里出现了改写语句 —— '
            '一个"读"函数不得写。';
    END IF;

    -- ---- (b12) hash 形态判据必须**同时接受**两种大小写 ----
    --   见「设计取舍三」：初稿写过 `^[0-9a-f]{64}$`（拒大写）+ `lower(...)`（归一），
    --   两者并存 ⇒ lower() 是死代码。修复后正则必须含 A-F。
    --   静态防线 = 本条；行为防线 = (e10) 的两半（大写必须成功 + 短 hash 必须失败）。
    --   🛑 用剥注释版：函数体里有一条注释写着 `本函数接受 [0-9a-fA-F]`，
    --      若匹配含注释的原文，则"删掉真正的正则"仍会因注释里的字面而假绿。
    IF v_reg_nocomment !~ '\^\[0-9a-fA-F\]\{64\}\$' THEN
        RAISE EXCEPTION
            'V21 自证失败(b12): rendered_hash 的形态判据不再是接受大小写的 '
            '`^[0-9a-fA-F]{64}$`。'
            '🛑 若它退回到 `^[0-9a-f]{64}$`（只收小写），则 INSERT 里的 lower(...) '
            '成为**死代码** —— 而"死代码"的问题不是浪费一个函数调用，'
            '而是它**声称**了一件不成立的事（"本函数会把 hash 归一化"）。'
            '🛑 另一个方向同样要防：若有人把 lower(...) 删掉而保留宽容正则，'
            '库里就会同时存在两种写法 ⇒ 每一个读点都必须自己记得归一，'
            '**漏掉一个读点**就是一个静默的错答。两处必须成对存在。';
    END IF;

    -- ---- (b13) 入库必须做 lower() 归一（与 (b12) 成对）----
    --   🛑 同样用剥注释版：函数体注释里出现过 `lower(p_rendered_hash)` 的字面描述。
    IF v_reg_nocomment !~ 'lower\s*\(\s*p_rendered_hash\s*\)' THEN
        RAISE EXCEPTION
            'V21 自证失败(b13): register_agreement 的 INSERT 未对 p_rendered_hash 做 '
            'lower(...) 归一。'
            '🛑 与 (b12) 是**一对**：宽容入参 + 规范入库。缺了后者，'
            '库里会同时存在大小写两种写法，"这一版正文签过几份协议"这类'
            '对账就无法用字符串等值完成 —— 而**每一个读点都要自己记得归一**，'
            '漏一个就是静默错答。本条与 (b12) 必须同时成立。';
    END IF;

    -- ---- (b14) 模板指针的成对判定必须存在 ----
    --   🛑 见「设计取舍四」①：单边指针让"签的是哪一版"只答一半。
    --   🛑 载体 = v_reg_code（剥注释）：注释里也出现过 `p_doc_template_id IS NULL`
    --      的描述性文字，用 prosrc 原文会假绿。
    IF v_reg_code !~ 'p_doc_template_id\s+IS\s+NULL' THEN
        RAISE EXCEPTION
            'V21 自证失败(b14): register_agreement 里没有 doc_template_id/version 的'
            '成对判定（未找到 `p_doc_template_id IS NULL` 的形态）。'
            '🛑 单边指针（只给 id 不给 version）会让"这份协议签的是哪个模板的哪一版"'
            '只答一半 —— 而 PRD P0-27 逐字要求「旧版本不可覆盖」「签署时快照」。'
            '行为防线 = (e12)。';
    END IF;

    -- ==================================================================
    -- (c) latest_agreement_of 的读侧断言
    -- ==================================================================

    -- ---- (c1) 租户维度必须在 SQL 里可见 ----
    --   🛑🛑 本条是**实测抓出的第二处静默假绿**：旧版载体是 v_lat_body（prosrc 原文），
    --      而 latest_agreement_of 的注释里逐字写着
    --        `` -- 🛑 同时写 `tenant_id = p_tenant_id`：让这条 SELECT 的… ``
    --      ⇒ 删掉真正的 `WHERE tenant_id = p_tenant_id` 后，本判据仍被**注释**满足
    --      ⇒ 静默假绿（实测：注释命中 1 处，代码命中 1 处，两态都为 True）。
    --      现载体 = v_lat_code（剥注释）⇒ 只可能被真正的谓词满足。
    --   🛑🛑 另一处由 122 的 C5 实测逼出的**收紧过度**：初版只收
    --      `tenant_id = p_tenant_id` 一种**书写顺序**，于是语义完全等价的
    --      `p_tenant_id = tenant_id`（谓词左右交换）被判为**假红** ——
    --      而"假红"与"假绿"同源（都是把断言对象从**语义**误当成**某一种语法**）。
    --      断言对象是"租户维度在 SQL 里显式可见"这个语义，不是"谁写在等号左边"。
    IF v_lat_code !~ 'tenant_id\s*=\s*p_tenant_id|p_tenant_id\s*=\s*tenant_id' THEN
        RAISE EXCEPTION
            'V21 自证失败(c1): latest_agreement_of 的 SELECT 没有显式写租户谓词'
            '（`tenant_id = p_tenant_id` 或等价交换顺序）。'
            '🛑 与 (b9) 同款纪律：RLS 会兜住，但可读性即安全。'
            '🛑 本条载体必须是剥注释后的代码态 —— 注释里恰好有同一字面量的描述，'
            '用原文会让"删掉真谓词"这件事静默通过。'
            '🛑 本条接受两种书写顺序（不是放宽语义，而是不让"写法"架空"语义"）。';
    END IF;

    -- ---- (c2) 🛑 "最新"必须有一个**全序**，且排序键必须是 signed_at ----
    --   🛑 两个断言合一，因为它们是**同一个判断**的两半：
    --     ① 必须有 tie-breaker（否则并发/同时刻写入时结果不确定）；
    --     ② 排序键必须是 signed_at（业务时刻）而不是 created_at（登记时刻）——
    --        离线补录会让"最新录入"与"最新签署"分叉。
    --        行为防线 = (e17)（刻意让"最后写入的那份"是中间时间的那份）。
    --   🛑 载体 = v_lat_code：注释里出现过该排序的字面描述。
    IF v_lat_code !~* 'ORDER\s+BY\s+signed_at\s+DESC\s*,\s*agreement_id\s+DESC' THEN
        RAISE EXCEPTION
            'V21 自证失败(c2): latest_agreement_of 的排序不是逐字的 '
            '`ORDER BY signed_at DESC, agreement_id DESC`。'
            '🛑 两处都必须检查，且理由不同：'
            '① **tie-breaker**：同一时刻签署的两份协议（并发/批量补录）'
            '   若只按 signed_at 排会给出**不确定**的先后 ⇒ 两次调用可能返回不同结果。'
            '   这正是 RefundWorkOrderPort.findLatestStatement 记载的同一族教训。'
            '② **signed_at 而非 created_at**：离线协议可能"先签、后补录"——'
            '   若按 created_at 排，"哪一份是最新的协议"会变成"哪一份是最新录入的"，'
            '   于是补录三天前签的那一份会**盖过**昨天签的那一份。'
            '   本函数的"最新"必须与 G1 的取数口径（签署时刻）一致。'
            '行为防线 = (e17)：那里刻意让**最后写入**的那份是**中间时间**的那份。';
    END IF;

    -- ---- (c3) LIMIT 1 必须在 ----
    --   🛑 否则多行会以 INTO 只取第一行，语义随执行计划变。
    --   🛑 载体 = v_lat_code（同 (c1)/(c2)：注释里提到过该子句）。
    IF v_lat_code !~* 'LIMIT\s+1' THEN
        RAISE EXCEPTION
            'V21 自证失败(c3): latest_agreement_of 里没有 LIMIT 1 —— '
            '多行时 INTO 只取第一行，而"第一行"随执行计划变 ⇒ 结果不确定。';
    END IF;

    -- ==================================================================
    -- (c9) 🛑🛑 元门禁的分工显式化：本节能证什么、不能证什么
    --
    --   🛑 本条**刻意不写成一个 IF 断言** —— 因为库层在运行期**读不到迁移文本**，
    --      于是"本节里有没有人把某条判据退回 prosrc 原文"这件事，
    --      在 PL/pgSQL 里**无法机械判定**。
    --   🛑🛑 写一条"看起来在扫源码、其实是恒假/恒真的查询"来充数，
    --      正是本仓禁止的形态（"把它做成看起来做了，比不做更坏"——
    --      见函数内 (5b) 对词表治理的同一处判断）。
    --      ⇒ 故此处只做两件**真的能做的事**，并把不能做的那件**显式移交**：
    --
    --   ① 【本节能证】代码态载体被构造且非空 —— 由 (b0b)/(b0c) 三条断言完成
    --      （含"v_reg_code 必须比 prosrc 短"这条，它证明剥注释**真的删掉了东西**）。
    --   ② 【本节能证】载体的分工在文本上是一对一的（见 (b0) 的职责表）。
    --   ③ 【本节不能证，移交 122】本节里"是否存在直接作用在 v_reg_body /
    --      v_lat_body 上的 !~ / ~* 形态判据"。
    --      ⇒ 122（反向验证脚本）对**迁移文本**做工，能真的扫源，
    --        故它必须含一条元门禁：在自证块的【美元引用区间】内，
    --        正则匹配载体不得是 v_reg_body / v_lat_body；
    --        允许的唯一原文用途是 `length(v_reg_body)`（长度比较不需要剥注释）。
    --        🛑 本注释**刻意不写出那个美元引用标签** —— 写了会提前终止本 DO 块
    --           自身的美元引用（实测在真库应用时当场报语法错误，见文件头第 7 条的姊妹条目）。
    --      ⇒ 这三条是**同一件事的三个层次**，缺一层就会重蹈：
    --          · 缺 ① ⇒ 载体是 NULL，全部静态判据静默消失（`IF NULL` 不执行）；
    --          · 缺 ② ⇒ 有人把 v_reg_nocomment 退回 `regexp_replace(v_reg_body)`
    --                   ⇒ 又是"两个不同的量"（(b7b) 的算术同型）；
    --          · 缺 ③ ⇒ 有人把某条判据退回原文 ⇒ 静默假绿（本轮实测两处都是这一种）。
    -- ==================================================================

    -- ==================================================================
    -- (d) 迁移登记 + 权限
    -- ==================================================================
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V21';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V21 自证失败(d): schema_migration 中 V21 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- ---- (d2) 用 has_function_privilege 而非读 proacl ----
    --   与 V18 / V19 / V20 的 (d2) 同款：前者把"角色继承 / PUBLIC 授权 / 显式授权"
    --   三种来源一并算进结论，而读 proacl 只能看见显式项。
    --   🛑 本判据问的是"**当前角色能不能执行**"——那是应用真正需要的事实。
    SELECT has_function_privilege(
               current_user,
               'public.register_agreement(uuid, uuid, uuid, uuid, int, jsonb, '
               'timestamptz, jsonb, text, text, jsonb, uuid, int, text)',
               'EXECUTE')::text INTO v_privilege;
    IF v_privilege <> 'true' THEN
        RAISE EXCEPTION
            'V21 自证失败(d2): 当前角色 % 对 register_agreement 没有 EXECUTE 权限（has_function_privilege 判为 %）。',
            current_user, v_privilege;
    END IF;

    SELECT has_function_privilege(
               current_user,
               'public.latest_agreement_of(uuid, uuid)',
               'EXECUTE')::text INTO v_privilege;
    IF v_privilege <> 'true' THEN
        RAISE EXCEPTION
            'V21 自证失败(d2): 当前角色 % 对 latest_agreement_of 没有 EXECUTE 权限（has_function_privilege 判为 %）。',
            current_user, v_privilege;
    END IF;

    -- ---- (d3) 🛑 授权段必须**真的执行过**（proacl 不得为 NULL）----
    --   与 V18 / V19 / V20 的 (d3) 同款，理由逐字相同：
    --     🛑 为什么 (d2) 单独不够：本迁移由应用角色**自己**执行 ⇒
    --        函数 owner = 调用者，而 owner 对自有函数的 EXECUTE 是**隐含**的 ⇒
    --        (d2) 在该情形下**恒为真**，即使第 2 节的 GRANT 段被整段删掉。
    --     ⇒ 本判据问的是**另一个**机械事实：断言 proacl 里存在【显式 ACL 项】。
    --        `proacl IS NULL` 意味着"从未 GRANT 也从未 REVOKE"——
    --        那正是"授权段被摘掉"的形态。
    --   🛑🛑 本迁移初版**漏了本条**（122 的 C8 元门禁扫源时抓出：
    --      "V21 自证失败(d3)" 这个字面量在整个迁移文本里**零命中**）。
    --      漏它的后果是：第 2 节授权段可以被整段删除而自证照常通过 ——
    --      与 (d2) 的"恒为真"叠加，等于**授权是否落地完全无人验证**。
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_missing
    FROM (VALUES ('register_agreement'), ('latest_agreement_of')) AS x(f)
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

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V21 自证失败(d3): 以下函数的 proacl 为 NULL —— 即【从未被 GRANT/REVOKE 过】：%。'
            '含义：第 2 节的授权段没有真正执行到这两个函数上。'
            '🛑 为什么 (d2) 单独不够：本迁移由应用角色自己执行 ⇒ 函数 owner = 调用者，'
            '而 owner 对自有函数的 EXECUTE 是隐含的 ⇒ (d2) 在该情形下恒为真。',
            v_missing;
    END IF;

    -- ==================================================================
    -- (e) 行为验证 —— 全部在子事务里，块末整体撤销
    -- ==================================================================
    BEGIN
        -- 准备：两个租户（tenant 无 RLS，可直接写）
        INSERT INTO tenant (id, name, status)
        VALUES (v_ta, 'V21 协议签署探针 A', 'active'),
               (v_tb, 'V21 协议签署探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        -- 租户 A：客户 ×3 + 方案 ×2（🛑 方案必须建 —— 本迁移有方案版本外键，
        --   这比 V20 的探针多一层依赖；见文件头差异表 ②）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        INSERT INTO customer (id, tenant_id, name)
        VALUES (v_cust_a1, v_ta, 'V21 探针客户 A1'),
               (v_cust_a2, v_ta, 'V21 探针客户 A2'),
               (v_cust_a3, v_ta, 'V21 探针客户 A3');

        INSERT INTO plan (plan_id, tenant_id, customer_id, version,
                          treatment_json, lifestyle_json, intent_params)
        VALUES (v_plan_a1, v_ta, v_cust_a1, 1, '{}', '{}', '{}'),
               (v_plan_a2, v_ta, v_cust_a2, 1, '{}', '{}', '{}');

        -- 租户 B：客户 ×1 + 方案 ×1
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        INSERT INTO customer (id, tenant_id, name)
        VALUES (v_cust_b1, v_tb, 'V21 探针客户 B1');

        INSERT INTO plan (plan_id, tenant_id, customer_id, version,
                          treatment_json, lifestyle_json, intent_params)
        VALUES (v_plan_b1, v_tb, v_cust_b1, 1, '{}', '{}', '{}');

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e1 登记 → CREATED，且行确实落库（六列同时断言）
        -- ---------------------------------------------------------------
        v_mode := register_agreement(
            v_ta, v_agr_a1, v_cust_a1, v_plan_a1, 1,
            v_clause_ok, '2026-07-01 10:00:00+08'::timestamptz, v_signer_ok,
            '调理协议书正文（探针）', v_hash_lower, NULL, NULL, NULL, 'v21-probe');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V21 自证失败(e1): 首次登记返回 %（期望 CREATED）。'
                '若返回 ALREADY_EXISTS，说明该 agreement_id 在库里已存在'
                '（探针 id 冲突 / 上次运行的残留）。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM agreement
                        WHERE agreement_id = v_agr_a1 AND tenant_id = v_ta
                          AND customer_id = v_cust_a1 AND plan_id = v_plan_a1
                          AND plan_version = 1
                          AND (signer ->> 'store_owner') = '李四'
                          AND (refund_clause_snapshot ->> 'refund') IS NOT NULL
                          AND rendered_hash = v_hash_lower
                          AND rendered_snapshot = '调理协议书正文（探针）') THEN
            RAISE EXCEPTION
                'V21 自证失败(e1): register_agreement 返回 CREATED，但库里查不到'
                '与之匹配的行（期望 tenant/customer/plan/版本/签署方/条款快照/正文/hash 全部对上）。'
                '🛑 多列同时断言是刻意的：一个"插了行但签署块用错来源"的实现'
                '会让协议声称"四方已签"而实际某一方是空的 —— 那是本迁移最该防的后果'
                '（它把一个**未签**推进成 AGREEMENT_SIGNED 门禁已放行）。';
        END IF;

        -- ---------------------------------------------------------------
        -- e2 🛑🛑 幂等：重放同一 agreement_id → ALREADY_EXISTS，
        --    且**不得用新参数改写既有证据**
        --   🛑 第二次调用**刻意传入不同的内容**（与 V20 的 e2 同款推理）：
        --     若第二次传入的内容与第一次**逐字相同**，那"重放不得改写证据"
        --     这条判据在 `ON CONFLICT (...) DO UPDATE SET ...` 的实现下**仍会通过**
        --     （UPDATE 写回的正是同样的值）—— 即判据对"用新参数改写既有证据"
        --     这件事毫无反应。那是一个**静默假绿**。
        --     ⇒ 改的是那些"门禁不管、但属于证据"的列：签署人 / 条款快照 / hash / 正文。
        --       这些正是 `DO UPDATE SET ...` 最容易顺手写上的列。
        -- ---------------------------------------------------------------
        v_mode := register_agreement(
            v_ta, v_agr_a1, v_cust_a1, v_plan_a1, 1,
            -- 🛑 条款快照换成另一份（仍是合法非空对象 ⇒ 能通过门禁）
            '{"refund":"改写者塞进来的条款"}'::jsonb,
            '2099-12-31 23:59:59+08'::timestamptz,
            -- 🛑 四方全换成"改写者"（仍是合法非空字符串 ⇒ 能通过门禁）
            '{"customer":"改写者","meridian_therapist":"改写者",'
            '"therapist":"改写者","store_owner":"改写者"}'::jsonb,
            '改写者塞进来的正文', v_hash_upper, NULL, NULL, NULL, 'v21-impostor');

        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V21 自证失败(e2): 重放同一 agreement_id 返回 %（期望 ALREADY_EXISTS）。', v_mode;
        END IF;

        SELECT (signer ->> 'store_owner') || '|' || rendered_hash || '|'
               || rendered_snapshot || '|' || (refund_clause_snapshot ->> 'refund')
          INTO v_signer_after
          FROM agreement WHERE agreement_id = v_agr_a1;

        IF v_signer_after <> ('李四|' || v_hash_lower || '|调理协议书正文（探针）|协商一致可终止') THEN
            RAISE EXCEPTION
                'V21 自证失败(e2): 🛑 重放**改写了既有证据** —— 库里那一行变成了 %。'
                '期望它仍是第一次登记时的那一份（李四|小写hash|探针正文|协商一致可终止）。'
                '这说明 ON CONFLICT 分支里写了 DO UPDATE。'
                '🛑 为什么这比"不幂等"更严重：协议是**举证材料**，'
                '"三年前签的协议"被后一次补录的调用**静默改写**，'
                '等于把举证链从内部破坏 —— 而调用方收到的回执是 ALREADY_EXISTS（一切正常）。'
                '正确形态是 DO NOTHING。', v_signer_after;
        END IF;

        -- ---------------------------------------------------------------
        -- e3 客户不存在 ⇒ RAISE，且不落库
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a3,
                '21000000-0000-0000-0000-0000000000ff'::uuid,   -- 不存在的客户
                v_plan_a1, 1, v_clause_ok, now(), v_signer_ok,
                '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_state := SQLSTATE; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION 'V21 自证失败(e3): 客户不存在却登记成功';
        END IF;
        IF v_msg NOT LIKE '%客户%在租户%内不存在%' THEN
            RAISE EXCEPTION
                'V21 自证失败(e3): 拒绝原因不是"客户不存在"的业务错误（SQLSTATE=% / %）。'
                '归因质量：若这里报的是 23503（外键），调用方要自己解析约束名。', v_state, v_msg;
        END IF;
        IF EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_a3) THEN
            RAISE EXCEPTION 'V21 自证失败(e3): 被拒绝的登记**落了库** —— 校验顺序必须是"先校验、再写入"';
        END IF;

        -- ---------------------------------------------------------------
        -- e4 方案**版本**不存在 ⇒ RAISE，且不落库
        --   🛑 本迁移特有（V20 无方案外键）：它证明"版本"确实被判据覆盖，
        --     而不是只查了 plan_id。
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a2, v_cust_a1,
                v_plan_a1, 99,                                 -- 方案存在但 v99 不存在
                v_clause_ok, now(), v_signer_ok,
                '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_state := SQLSTATE; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e4): 方案版本不存在却登记成功 —— '
                '本判据必须带 version，否则"方案存在但那一版不存在"会被误判为存在。';
        END IF;
        IF v_msg NOT LIKE '%版本%在租户%内不存在%' THEN
            RAISE EXCEPTION
                'V21 自证失败(e4): 拒绝原因不是"方案版本不存在"的业务错误（SQLSTATE=% / %）。'
                '🛑 若这里报 23503，说明本函数的 plan 查询没带 version，'
                '或没查 plan 而直接让外键报错 —— 两种都属归因质量退化。', v_state, v_msg;
        END IF;
        IF EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_a2) THEN
            RAISE EXCEPTION 'V21 自证失败(e4): 被拒绝的登记**落了库**';
        END IF;

        -- ---------------------------------------------------------------
        -- e5 plan_version = 0 ⇒ 必须是**业务错误**，不是 23514
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a4, v_cust_a1, v_plan_a1, 0,
                v_clause_ok, now(), v_signer_ok,
                '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_state := SQLSTATE; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION 'V21 自证失败(e5): plan_version = 0 却登记成功';
        END IF;
        IF v_state = '23514' THEN
            RAISE EXCEPTION
                'V21 自证失败(e5): plan_version = 0 是以 **23514**（表 CHECK）被拒的，'
                '而不是业务错误。🛑 这不是"反正被拒了"—— 归因质量：'
                '23514 只给一个约束名（ck_agreement_plan_version_positive），'
                '而调用方需要的是"版本必须从 1 起"这句话。'
                '本函数应在显式分支里先判它（见函数内 (4b)）。';
        END IF;
        IF v_msg NOT LIKE '%版本必须从 1 起%' THEN
            RAISE EXCEPTION
                'V21 自证失败(e5): 拒绝原因不是"版本必须从 1 起"（SQLSTATE=% / %）。', v_state, v_msg;
        END IF;

        -- ---------------------------------------------------------------
        -- e6 🛑🛑 四方签署**逐方**缺项都必须 RAISE（含移除任一方）
        --   🛑 逐方测，不测一个就代表全部 —— 本仓对 V5 的 24 表 RLS 断言
        --     用的是同一纪律（"逐表各执行一遍，不是测了其中一张就代表其余"）。
        --   🛑 本用例同时是 (b8) 的**行为防线**：若有人给某一方开了宽免
        --     （照抄 V20 的警告门禁），这里会红。
        -- ---------------------------------------------------------------
        DECLARE
            v_one text;
            v_case int := 0;
        BEGIN
            FOREACH v_one IN ARRAY v_sign_keys LOOP
                v_case := v_case + 1;
                v_rejected := false;
                BEGIN
                    PERFORM register_agreement(v_ta, v_agr_a5, v_cust_a1, v_plan_a1, 1,
                        v_clause_ok, now(), (v_signer_ok - v_one),
                        '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
                EXCEPTION WHEN others THEN
                    v_rejected := true; v_msg := SQLERRM;
                END;
                IF NOT v_rejected THEN
                    RAISE EXCEPTION
                        'V21 自证失败(e6): 移除签署方 `%`（第 % 个）后登记**仍然成功**。'
                        '🛑 PRD §八 是**四方全部**必签的硬门禁'
                        '（「未签不得首次调理或退款判定」）—— '
                        '任何一方的宽免都是对硬门禁的实质削弱。'
                        '🛑 若你是照抄了 V20 的"警告门禁"结构，请读 (b8) 的说明：'
                        'V20 的宽免来自 PRD 逐字要求（手环"不得反转为阻断"），'
                        '而本域**没有任何**对应的 PRD 语句。', v_one, v_case;
                END IF;
                IF v_msg NOT LIKE '%四方签署【缺项或为空】%' THEN
                    RAISE EXCEPTION
                        'V21 自证失败(e6): 移除 `%` 后虽被拒，但原因不是"四方签署缺项"（%）——'
                        '归因质量不足。', v_one, v_msg;
                END IF;
            END LOOP;

            IF v_case <> 4 THEN
                RAISE EXCEPTION 'V21 自证失败(e6): 逐方用例只跑了 % 次（期望 4）', v_case;
            END IF;
        END;

        IF EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_a5) THEN
            RAISE EXCEPTION 'V21 自证失败(e6): 被拒绝的登记**落了库**';
        END IF;

        -- ---------------------------------------------------------------
        -- e7 未登记键 ⇒ RAISE（拼写错误的早期发现）
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a6, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(),
                '{"customer":"赵一","meridian_therapist":"钱二","therapist":"孙三",'
                '"stores_owner":"李四"}'::jsonb,             -- 拼错：stores_owner
                '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e7): 含未登记键（stores_owner）却登记成功。'
                '🛑 不得静默忽略未登记键 —— 忽略会让一次拼写错误同时产出'
                '"某方被判未签"与"调用方以为已签"两个后果。';
        END IF;
        IF v_msg NOT LIKE '%未登记键%' THEN
            RAISE EXCEPTION 'V21 自证失败(e7): 拒绝原因不是"未登记键"（%）', v_msg;
        END IF;

        -- ---------------------------------------------------------------
        -- e8 签署值为**空串** ⇒ RAISE（一个空白的"门店负责人"不是签字）
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a7, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(),
                '{"customer":"赵一","meridian_therapist":"钱二","therapist":"孙三",'
                '"store_owner":"   "}'::jsonb,               -- 仅空格
                '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e8): 签署方为空白字符串却登记成功。'
                '🛑 DDL 的 NOT NULL 只能保证 signer 这个 JSONB 不为空，'
                '挡不住它里面是 `{"store_owner":"   "}`。'
                '一个空白的"门店负责人"在举证场景里等同于"没人签"。';
        END IF;

        -- ---------------------------------------------------------------
        -- e9 条款快照 = 空对象 ⇒ RAISE（空快照是**占位**，不是快照）
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a8, v_cust_a1, v_plan_a1, 1,
                '{}'::jsonb,                                  -- 空对象
                now(), v_signer_ok, '正文', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e9): refund_clause_snapshot = {} 却登记成功。'
                '🛑 空对象的唯一用途是**占位** —— 而这一列的唯一用途是'
                '事后证明"签的时候条款原文是什么"。空快照让这份举证责任'
                '无法履行，而它看起来"填了"（死字段形态）。';
        END IF;
        IF v_msg NOT LIKE '%空对象%' THEN
            RAISE EXCEPTION 'V21 自证失败(e9): 拒绝原因不是"空对象"（%）', v_msg;
        END IF;

        -- ---------------------------------------------------------------
        -- e10 🛑🛑 hash 的两半：短 hash 必须拒 / **大写 hash 必须过**
        --   这是 (b12)/(b13) 这对断言的**行为防线**，两半缺一不可：
        --     · 只测"短 hash 被拒"⟹ 挡不住"把正则改回过严（只收小写）"；
        --     · 只测"大写能过"  ⟹ 挡不住"把长度判据整个删掉"。
        -- ---------------------------------------------------------------
        -- 前半：32 位（MD5）⇒ 必须拒
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_a9, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(), v_signer_ok,
                '正文', repeat('a', 32), NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e10): 32 位 hash（MD5 长度）却登记成功 —— '
                '算法用错必须被拦，否则"签的是哪一版"的证据链用的是错的摘要算法。';
        END IF;

        -- 后半：64 位**大写** ⇒ 必须成功，且入库后是**小写**
        v_mode := register_agreement(v_ta, v_agr_aa, v_cust_a1, v_plan_a1, 1,
            v_clause_ok, now(), v_signer_ok,
            '正文（大写 hash 用例）', v_hash_upper, NULL, NULL, NULL, 'v21-probe');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V21 自证失败(e10): 64 位**大写** hash 被拒（返回 %）。'
                '🛑 大小写是**同一个值的不同写法**：拒绝一个形态正确的摘要，'
                '就是本仓记载过的"a gate that cries wolf on correct content '
                'gets switched off"（见 client-zero-derived-gate.py 源码）。'
                '⇒ 函数应接受 [0-9a-fA-F]，并在入库时 lower(...) 归一。', v_mode;
        END IF;

        SELECT rendered_hash INTO v_hash_after
          FROM agreement WHERE agreement_id = v_agr_aa;

        IF v_hash_after <> v_hash_lower THEN
            RAISE EXCEPTION
                'V21 自证失败(e10): 大写入参入库后不是小写归一形态（库里 = %）。'
                '🛑 若库里存的是大写，说明 lower(...) 被删了 ⇒ '
                '库里会同时存在两种写法 ⇒ 每一个读点都必须自己记得归一，'
                '**漏掉一个读点**就是一个静默的错答（"这一版签过几份"会算错）。',
                coalesce(v_hash_after, '<NULL>');
        END IF;

        -- ---------------------------------------------------------------
        -- e11 带 "sha256:" 前缀 ⇒ 必须拒（那是另一种编码，不是摘要）
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_ab, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(), v_signer_ok,
                '正文', 'sha256:' || v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e11): 带 "sha256:" 前缀却登记成功。'
                '🛑 前缀是**另一种编码**（某种协议封装），而 I8/H1 协议尚未冻结 ⇒ '
                '库层不得猜"这个前缀该不该剥"。判据：'
                '"同一个值的不同写法"接受并归一；"不同的编码"拒绝。';
        END IF;

        -- ---------------------------------------------------------------
        -- e12 模板指针：单边必须拒 / 版本不符必须拒 / 成对合法必须过
        -- ---------------------------------------------------------------
        -- 单边（只给 id）
        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_ta, v_agr_ac, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(), v_signer_ok,
                '正文', v_hash_lower, NULL,
                '21000000-0000-0000-0000-0000000000f1'::uuid, NULL, 'v21-probe');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e12): 模板指针**只给 id 不给 version** 却登记成功。'
                '🛑 这让"这份协议签的是哪个模板的哪一版"只答一半 —— '
                '而 PRD P0-27 逐字要求「旧版本不可覆盖」「签署时快照」。';
        END IF;
        IF v_msg NOT LIKE '%不成对%' THEN
            RAISE EXCEPTION 'V21 自证失败(e12): 拒绝原因不是"成对"（%）', v_msg;
        END IF;

        -- 版本不符：建一个真模板行（v1），然后用 version=9 指它
        DECLARE
            v_tpl_id uuid := '21000000-0000-0000-0000-0000000000f1';
        BEGIN
            INSERT INTO doc_template (template_id, tenant_id, doc_type, title, content,
                                      version, is_active, source_type)
            VALUES (v_tpl_id, v_ta, '调理协议书', 'V21 探针模板', '正文v1', 1, true, 'editor');

            v_rejected := false;
            BEGIN
                PERFORM register_agreement(v_ta, v_agr_ac, v_cust_a1, v_plan_a1, 1,
                    v_clause_ok, now(), v_signer_ok,
                    '正文', v_hash_lower, NULL, v_tpl_id, 9, 'v21-probe');
            EXCEPTION WHEN others THEN
                v_rejected := true; v_msg := SQLERRM;
            END;
            IF NOT v_rejected THEN
                RAISE EXCEPTION
                    'V21 自证失败(e12): 模板指针版本**不符**（指针说 v9，模板行自报 v1）却登记成功。'
                    '🛑 doc_template 的主键是单列 template_id ⇒ 每一个版本是一行、有自己的 id ⇒ '
                    'doc_template_version 是**冗余副本**，它必须等于该行的 version。'
                    '不查这条，就抓不住"指针说 v3、实际指向 v5 那一行"这类**静默错配**。';
            END IF;
            IF v_msg NOT LIKE '%版本不符%' THEN
                RAISE EXCEPTION 'V21 自证失败(e12): 拒绝原因不是"版本不符"（%）', v_msg;
            END IF;

            -- 成对且相符 ⇒ 必须成功
            v_mode := register_agreement(v_ta, v_agr_ac, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(), v_signer_ok,
                '正文（带模板指针）', v_hash_lower, NULL, v_tpl_id, 1, 'v21-probe');
            IF v_mode <> 'CREATED' THEN
                RAISE EXCEPTION
                    'V21 自证失败(e12): 成对且版本相符的模板指针被拒（返回 %）。'
                    '🛑 这条反向用例是必须的：只测"错的被拒"挡不住'
                    '"把所有带模板指针的调用都拒掉"这种过严实现。', v_mode;
            END IF;
        END;

        -- ---------------------------------------------------------------
        -- e13 🛑 同租户**显式带 tenant_id** 的裸 INSERT 必须**成功**
        --   （对照用例：证明 (e14) 的"返回 ALREADY_EXISTS"不是函数在作弊）
        --   🛑 这与 V20 的 e7 同款用途：V20 用它证明"同租户撞号返回 ALREADY_EXISTS
        --      而非 RAISE"是**正确**的 —— 即拒的是跨租户，不是同租户。
        -- ---------------------------------------------------------------
        INSERT INTO agreement (agreement_id, tenant_id, customer_id, plan_id, plan_version,
                               refund_clause_snapshot, signed_at, signer,
                               rendered_snapshot, rendered_hash)
        VALUES (v_agr_ad, v_ta, v_cust_a1, v_plan_a1, 1,
                v_clause_ok, now(), v_signer_ok, '裸 INSERT 对照', v_hash_lower);

        IF NOT EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_ad) THEN
            RAISE EXCEPTION 'V21 自证失败(e13): 同租户裸 INSERT 未生效（RLS 上下文不对？）';
        END IF;

        -- ---------------------------------------------------------------
        -- e14 🛑🛑 跨租户撞号 ⇒ RAISE（核心机制二）
        --   租户 A 先用 v_agr_ae；然后租户 B 用**同一个 id** → 必须 RAISE
        -- ---------------------------------------------------------------
        v_mode := register_agreement(
            v_ta, v_agr_ae, v_cust_a1, v_plan_a1, 1,
            v_clause_ok, now(), v_signer_ok,
            '租户A先占', v_hash_lower, NULL, NULL, NULL, 'v21-probe');
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION 'V21 自证失败(e14): 租户 A 首次登记返回 %（期望 CREATED）', v_mode;
        END IF;

        PERFORM set_config('app.tenant_id', v_tb::text, true);

        v_rejected := false;
        BEGIN
            PERFORM register_agreement(v_tb, v_agr_ae, v_cust_b1, v_plan_b1, 1,
                v_clause_ok, now(), v_signer_ok,
                '租户B抢同一个id', v_hash_lower, NULL, NULL, NULL, 'v21-probe-b');
        EXCEPTION WHEN others THEN
            v_rejected := true; v_state := SQLSTATE; v_msg := SQLERRM;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e14): 🛑 跨租户撞号**没有抛错** —— '
                '而 INSERT ... ON CONFLICT (agreement_id) DO NOTHING 在主键冲突时'
                'ROW_COUNT=0。若判定链缺了"看不见 ⇒ 是别人的"这一支，'
                '它会 return ALREADY_EXISTS，而租户 B 一份协议都没有。'
                '🛑 后果：调用方据此把客户推进 AGREEMENT_SIGNED（CustomerGateGuard），'
                '于是**一个客户被放进了一条它没有资格走的门**，而协议从未被签署。'
                '这条缺口不会报错、不会 23503、不会被任何下游抓住 —— '
                '它只在 G1「协议签署合规率」取数时暴露。';
        END IF;
        IF v_msg NOT LIKE '%已被【另一租户】占用%' THEN
            RAISE EXCEPTION
                'V21 自证失败(e14): 跨租户撞号虽被拒，但原因不是"另一租户占用"'
                '（SQLSTATE=% / %）—— 归因质量不足。', v_state, v_msg;
        END IF;

        -- 租户 B 用自己的 id ⇒ 必须成功（证明拒绝的是"撞号"，不是"租户 B 不能签"）
        v_mode := register_agreement(
            v_tb, v_agr_af, v_cust_b1, v_plan_b1, 1,
            v_clause_ok, now(), v_signer_ok,
            '租户B自己的id', v_hash_lower, NULL, NULL, NULL, 'v21-probe-b');
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V21 自证失败(e14): 租户 B 用自己的 agreement_id 登记返回 %（期望 CREATED）。'
                '🛑 这条反向用例证明"被拒的是撞号，而不是租户 B 无权签署"。', v_mode;
        END IF;

        -- ---------------------------------------------------------------
        -- e15 🛑 租户 B **读不到**租户 A 的协议（RLS 的核心断言）
        -- ---------------------------------------------------------------
        IF EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_ae) THEN
            RAISE EXCEPTION
                'V21 自证失败(e15): 租户 B 的上下文里**看得见**租户 A 的协议！'
                '🛑 这说明 RLS 策略未生效（agreement 是 ENABLE + FORCE，'
                '策略按 app.tenant_id 隔离）—— 这是比"函数写错"严重得多的状态：'
                '它是**隔离本身**被拿掉了。';
        END IF;

        -- ---------------------------------------------------------------
        -- e16 🛑🛑 latest_agreement_of 必须按 **signed_at**（业务时刻）取最新，
        --     而不是 created_at（登记时刻）—— 离线补录会让两者分叉
        --   构造：先登记"晚签的那份"（6月），再登记"早签的那份"（1月），
        --         最后登记"中间签的那份"（3月）⇒ **最后写入的是中间时间的**
        --   期望：返回 6月那份（signed_at 最新）而不是 3月那份（created_at 最新）
        -- ---------------------------------------------------------------
        PERFORM set_config('app.tenant_id', v_ta::text, true);

        v_mode := register_agreement(v_ta, v_agr_ah, v_cust_a2, v_plan_a2, 1,
            v_clause_ok, '2026-06-01 10:00:00+08'::timestamptz, v_signer_ok,
            '晚期签的那份（先登记）', v_hash_lower, NULL, NULL, NULL, 'v21-probe');  -- 晚签
        v_mode := register_agreement(v_ta, v_agr_ag, v_cust_a2, v_plan_a2, 1,
            v_clause_ok, '2026-01-01 10:00:00+08'::timestamptz, v_signer_ok,
            '早期签的那份（次登记）', v_hash_lower, NULL, NULL, NULL, 'v21-probe');  -- 早签
        v_mode := register_agreement(v_ta, v_agr_ai, v_cust_a2, v_plan_a2, 1,
            v_clause_ok, '2026-03-01 10:00:00+08'::timestamptz, v_signer_ok,
            '中间签的那份（最后登记）', v_hash_lower, NULL, NULL, NULL, 'v21-probe'); -- 中间

        v_latest := latest_agreement_of(v_ta, v_cust_a2);

        IF v_latest IS DISTINCT FROM v_agr_ah THEN
            RAISE EXCEPTION
                'V21 自证失败(e16): latest_agreement_of 返回 %，期望 %（2026-06-01 那份）。'
                '🛑 本用例刻意让【最后写入的那份】是【中间时间】的那份：'
                '若函数按 created_at（登记时刻）排序，会返回 %（2026-03-01 那份，最后写入）。'
                '而"最新签署的协议"必须按 signed_at（业务时刻）—— '
                '因为离线协议可能"先签、后补录"，补录顺序与签署顺序无关。'
                '🛑 这个分叉不是理论问题：G1「协议签署合规率」按签署时刻取数，'
                '而"哪一份是最新协议"若按录入顺序，会与 G1 的口径不一致。',
                coalesce(v_latest::text, '<NULL>'),
                v_agr_ah::text, v_agr_ai::text;
        END IF;

        -- ---------------------------------------------------------------
        -- e17 无协议的客户 ⇒ NULL（"NULL 是一等返回值"）
        -- ---------------------------------------------------------------
        IF latest_agreement_of(v_ta, v_cust_a3) IS NOT NULL THEN
            RAISE EXCEPTION
                'V21 自证失败(e17): 无协议的客户返回了非 NULL。'
                '"尚未签署"是正常业务状态（正是 G1 的分母）⇒ 必须返回 NULL，不是异常。';
        END IF;

        -- ---------------------------------------------------------------
        -- e18 读函数的上下文一致性守卫必须生效
        -- ---------------------------------------------------------------
        v_rejected := false;
        BEGIN
            -- 当前上下文 = 租户 A；传租户 B ⇒ 必须拒
            PERFORM latest_agreement_of(v_tb, v_cust_b1);
        EXCEPTION WHEN others THEN
            v_rejected := true; v_msg := SQLERRM;
        END;
        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V21 自证失败(e18): latest_agreement_of 静默接受了一个与自己'
                '所在事务上下文不一致的租户 —— 它会静默改写调用方的 app.tenant_id。'
                '🛑 "只读所以不需要守卫"是本仓已经付过代价的错判。';
        END IF;
        IF v_msg NOT LIKE '%不一致%' THEN
            RAISE EXCEPTION 'V21 自证失败(e18): 拒绝原因不是"上下文不一致"（%）', v_msg;
        END IF;

        -- ---------------------------------------------------------------
        -- (f) 清场自证：本块的所有写入必须能被整体撤销
        --   🛑 与 V20 的 (f2) 同款：先断言"我的探针确实写进去了"，
        --      再依赖 BEGIN...EXCEPTION 的撤销。若不先断言，
        --      "什么都没写"与"写了但被撤销"会给出同一个结果（假绿）。
        -- ---------------------------------------------------------------
        IF NOT EXISTS (SELECT 1 FROM agreement WHERE agreement_id = v_agr_a1) THEN
            RAISE EXCEPTION
                'V21 自证失败(f): 探针未写入（末尾清场断言的前提不成立）。';
        END IF;

        -- 撤销本块全部写入（含 tenant / customer / plan / doc_template / agreement）
        RAISE EXCEPTION 'V21_SELFPROOF_ROLLBACK';
    EXCEPTION WHEN others THEN
        IF SQLERRM <> 'V21_SELFPROOF_ROLLBACK' THEN
            RAISE;   -- 真正的失败：向上抛，整个迁移回滚
        END IF;
        -- 否则：正常撤销，继续
    END;

    -- ==================================================================
    -- (f2) 清场自证 —— 撤销必须**彻底**
    --   🛑 与 V20 的 (f2) 同款，且本迁移需检查**五个**表（多一个 plan 与 doc_template
    --      的依赖链：agreement → plan → customer）：
    --      "跑了但没清干净" 的后果是：下一次跑迁移时探针 id 已被占用 ⇒
    --      (e1) 会以"竟然返回 ALREADY_EXISTS"的面目失败 —— 一条归因错误的红。
    -- ==================================================================
    IF EXISTS (SELECT 1 FROM agreement   WHERE tenant_id IN (v_ta, v_tb)) THEN
        RAISE EXCEPTION 'V21 自证失败(f2): 探针清场不彻底（agreement 仍有残留）';
    END IF;
    IF EXISTS (SELECT 1 FROM doc_template WHERE tenant_id IN (v_ta, v_tb)) THEN
        RAISE EXCEPTION 'V21 自证失败(f2): 探针清场不彻底（doc_template 仍有残留）';
    END IF;
    IF EXISTS (SELECT 1 FROM plan        WHERE tenant_id IN (v_ta, v_tb)) THEN
        RAISE EXCEPTION 'V21 自证失败(f2): 探针清场不彻底（plan 仍有残留）';
    END IF;
    IF EXISTS (SELECT 1 FROM customer    WHERE tenant_id IN (v_ta, v_tb)) THEN
        RAISE EXCEPTION 'V21 自证失败(f2): 探针清场不彻底（customer 仍有残留）';
    END IF;
    IF EXISTS (SELECT 1 FROM tenant      WHERE id IN (v_ta, v_tb)) THEN
        RAISE EXCEPTION 'V21 自证失败(f2): 探针清场不彻底（tenant 仍有残留）';
    END IF;

    -- ==================================================================
    -- (g) 🛑🛑 成功标记 —— 让"自证跑完并通过"这件事**可观测**
    --
    --   🛑 本条是**实测抓出的第三处缺陷**（形态与 (b6)/(c1) 不同，但根因同族）：
    --      V20 在 (f2) 之后有一条 `RAISE NOTICE 'V20 自证通过: …'`，
    --      而 V21 的初版**没有** —— 于是 V21 成功时的 psql 输出是
    --          DO / CREATE FUNCTION / CREATE FUNCTION / DO / INSERT 0 0 / DO
    --      六个句子，**没有任何一个字说明自证跑过**。
    --
    --   🛑 为什么这是**真缺陷**而不是"少一条日志"（三条，按严重度递增）：
    --     ① 验收不可判：反向验证脚本 C1（原样重跑必须全绿）的判据是
    --        `ok and "V21 自证通过" in out`。没有这条 NOTICE，
    --        **C1 恒假** ⇒ 脚本会报一条**归因错误**的红（"自证没通过"），
    --        而真相是"通过了但没说话"。
    --     ② "跑过了"与"根本没跑"同形：DO 块若被误删/被条件包住，
    --        输出与正常运行**逐字相同** ⇒ 一个静默的"自证消失"不可见。
    --        （注意这与 (b0b) 防的是同一族：载体为 NULL 时判据静默消失；
    --          本条防的是**整个自证块**静默消失。）
    --     ③ 运维不可判：生产上跑迁移的人看到六个句子，无法回答"自证过了吗"。
    --        本仓对 V15/V17~V20 都有这条 NOTICE ⇒ V21 缺它属**纪律不一致**。
    --
    --   ⇒ 判据内容：把**本迁移真正区别于前五批**的那几件事写进去
    --     （不是"自证通过"四个字，而是"通过了什么"）：
    --       两态 / hash 形态（含大写归一）/ 跨租户撞号 RAISE / 四方逐方门禁 /
    --       方案版本门禁（本域特有）/ 模板指针成对+版本相符（本域特有）/
    --       signed_at 全序取最新（本域特有）/ 上下文一致性守卫 / 探针零残留。
    --   🛑 这一条本身也由 122 守着：`grep "V21 自证通过"` 必须命中，
    --      且 C1 必须同时满足 `ok and 含该字样`（见第 7 条的姊妹条目）。
    -- ==================================================================
    RAISE NOTICE 'V21 自证通过: 函数 2 / 登记 1 / 代码态载体 4 条（剥注释+压空白+语句切片）/ '
                 '函数体断言全中 / 建档两态（CREATED→ALREADY_EXISTS）且重放不改写证据 / '
                 '四方签署逐方缺项皆 RAISE / 未登记键与空串皆 RAISE / 条款空对象 RAISE / '
                 'hash 形态（64 位含大写归一入库、32 位与前缀皆 RAISE）/ '
                 '方案版本门禁（不存在与 0 皆 RAISE 且给业务错误而非 23514/23503）/ '
                 '模板指针成对且版本相符 / 跨租户撞号 RAISE 且本租户零残留 / '
                 '同租户裸 INSERT 对照成功 / 跨租户不可见 / '
                 'latest_agreement_of 按 signed_at 全序取最新（非 created_at）/ '
                 '无协议客户返回 NULL / 读侧上下文一致性守卫生效 / 探针零残留';
END;
$v21_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
-- ============================================================================

-- 见文件头「回滚说明」。要点重述（因为这是运维真正会用到的地方）：
--   DROP FUNCTION IF EXISTS register_agreement(uuid, uuid, uuid, uuid, int, jsonb,
--                                              timestamptz, jsonb, text, text,
--                                              jsonb, uuid, int, text);
--   DROP FUNCTION IF EXISTS latest_agreement_of(uuid, uuid);
--   DELETE FROM schema_migration     WHERE version = 'V21';
--   DELETE FROM flyway_schema_history WHERE version = '21';
--
-- 🛑 两条与本迁移特有的注意：
--   ① agreement 有**两条**复合外键（customer 与 plan）⇒ 清理数据时的顺序是
--      agreement → plan → customer（先删协议，再删方案，最后删客户）。
--      若顺序反了，会撞 23503。
--   ② 本迁移**不建表、不加列、不改既有列** ⇒ 回滚不涉及任何数据迁移，
--      且**不影响**已登记的 agreement 行（它们是数据不是 schema）。