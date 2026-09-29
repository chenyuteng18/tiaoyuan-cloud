\set ON_ERROR_STOP on
BEGIN;
-- ============================================================================
-- V19 迁移: 量表建档通路（量表 scale 台账的写入原语）
--
-- 【这个迁移为什么存在 —— 与 B-10（band）/ B-11（device）同型，但缺口的对外表现不同】
--
--   `ProvisioningBoundaryGateTest` 把 `scale` 登记在「未开通账」里，
--   当时它同样被写成一条"边界事实"。本轮（B-12）勘察后确认它也是缺陷：
--
--     baseline_assessment.scale_id → FOREIGN KEY (tenant_id, scale_id)
--                                   REFERENCES scale (tenant_id, scale_id)   [V5 L347 + V16 复合化]
--
--   而 `scale` 在生产代码里【零写入方】、真库【零行】（实测：count(*) = 0）。
--   ⇒ 契约 C2 `POST /customers/{id}/assessments/baseline` 在规格级不可用。
--
--   【🛑🛑 与 B-10 / B-11 的关键差别：这里缺口的对外表现【不是 23503】】
--     device（B-11）那条链上没有任何存在性预检 ⇒ 表现为一条 PostgreSQL 的 23503。
--     而 scale 这条链上**有一道业务预检**，它把缺口【翻译】成了一个业务错误：
--
--       AssessmentService.submitBaseline 第 ③ 步（L154）：
--           if (!ledger.scaleExists(tenantId, scaleId)) {
--               throw new BizException(BUSINESS_RULE_VIOLATED,
--                   "scale_id 指向的量表不存在或不属当前租户: " + scaleId ...);
--           }
--       AssessmentLedger.scaleExists（L142）：
--           SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid)
--
--     ⇒ 在 scale 零行的现实下，这道检查【恒为 false】⇒ C2 **恒返回 422 / 5001**，
--       而且它的错误文案是"量表不存在或不属当前租户" —— 一个**会把人引向错误方向**的文案：
--       读它的人会去怀疑"是不是 scale_id 传错了 / 是不是租户上下文不对"，
--       而真因是**库里压根没有任何量表**。
--     🛑 这就是本迁移存在的第二层理由：缺口不仅让端点不可用，还让它的错误文案撒谎。
--
--   【缺口的第二个面：scale_version 恒 null ⇒ migratable 恒 false】
--     AssessmentService L161：String scaleVersion = ledger.scaleVersion(tenantId, scaleId);
--     AssessmentService L178：boolean migratable = itemGroupId != null && scaleVersion != null;
--     ⇒ scale 零行 ⇒ scale_version 恒 null ⇒ **migratable 恒为 false**。
--       而 migratable 是"同源元数据四要素齐备度"的落库判据（V5 L355 NOT NULL）。
--       即：即使有人绕过 scaleExists 那道检查，写进去的基线也会永远标着"不可迁移"。
--       🛑 这类"因为上游一行都没有，所以下游一个布尔值恒假"的形态，
--          在测试里不会被发现 —— 除非测试自己建 scale（而本类刻意不建，见下）。
--
--   【实测证据（2026-09-27，真库 diaoyuanyun_dev，应用角色 diaoyuanyun 非超级用户）】
--     探针 `_work/tmp/v19_schema_probe.sql`：scale 表 5 条约束 + 0 行；
--       · scale_pkey               = PRIMARY KEY (scale_id)              ← 🛑 单列
--       · uq_scale_id_version      = UNIQUE (scale_id, scale_version)
--       · uq_tenant_scale_scale_id = UNIQUE (tenant_id, scale_id)        ← V16 复合外键载体
--       · scale_scale_type_check   = CHECK (scale_type IN ('primary','calibration'))
--       · scale_status_check       = CHECK (status IN ('active','deprecated'))
--     且 baseline_assessment_scale_id_fkey 实测形态 =
--       FOREIGN KEY (tenant_id, scale_id) REFERENCES scale(tenant_id, scale_id)  ← V16 已复合化
--     enable_rls = t / force_rls = t（scale 是 FORCE RLS）
--     count(baseline_assessment) = 0
--
-- 【🛑 核心机制一：建档必须【自己】建立 RLS 上下文（与 V18 逐字同款）】
--   scale 表（V5 L252-258）是 ENABLE + FORCE ROW LEVEL SECURITY，策略 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 WITH CHECK 为 false ⇒ INSERT **抛错**（不是静默零行 ——
--   静默零行发生在 SELECT 的 USING 上，这个区别很容易记反）。
--   故本函数把「建立上下文」+「自证上下文已生效」绑在写入之前。
--
-- 【🛑🛑 核心机制二（与 V18 同型，且这次是【读 schema 读出来的】而非实测抓出来的）：
--     `ON CONFLICT (scale_id)` 在【跨租户撞号】时会【静默 DO NOTHING】，
--     若只按 ROW_COUNT 判定，就会对一个手里一行都没有的租户报 'ALREADY_EXISTS'】
--
--   scale_pkey 实测 = `PRIMARY KEY (scale_id)` —— 与 device_pkey 是**同一种形态**：
--   单列、不含 tenant_id。故"同一 scale_id 被另一租户占用"同样可能发生，
--   而 `INSERT ... ON CONFLICT (scale_id) DO NOTHING` 的推断目标是那个全局主键 ⇒
--   跨租户撞号时 PG 走 DO NOTHING 且 ROW_COUNT = 0，而本租户上下文里 SELECT 又看不见那一行。
--   ⇒ 判定链与 V18 逐字同款（三段，全部来自库层原子事实）：
--       ① ROW_COUNT = 1 ⇒ 'CREATED'
--       ② ROW_COUNT = 0 且本租户看得见 ⇒ 'ALREADY_EXISTS'（幂等，如实）
--       ③ ROW_COUNT = 0 且本租户看不见 ⇒ RAISE（跨租户撞号）
--   🛑 为什么这不依赖"实测抓出来"也可以成立：它是【主键列集合】的纯逻辑推论。
--      V18 是被实测抓到时才发现的（那次事故的代价是"返回值在撒谎"）；
--      V19 是**在读 V18 之后主动检查同族形态**时发现的 —— 这是可迁移的教训：
--      「凡 ON CONFLICT 的推断目标不含 tenant_id 的表，都要过一遍这条判定链」。
--      真库实测已验证 scale_pkey 确实是单列（见上），故本条不是推测。
--
-- 【🛑🛑 核心机制三（V19 特有，V18 没有）：`scale_version` 在单列主键下是【死维度】】
--   V5 L235 的设计注释逐字写着：
--       §2.10: 版本递增·不可覆盖；UNIQUE(scale_id, scale_version)
--   并且 L247 的注释进一步强调：
--       §2.10: UNIQUE(scale_id, scale_version) —— 版本化必须靠唯一键兜住，否则"不可覆盖"失效
--
--   🛑 但实测显示：`scale_pkey` 是 `PRIMARY KEY (scale_id)`。
--   ⇒ 同一 scale_id **只可能有一行**，于是 `uq_scale_id_version` 永远不可能被违反
--     —— 它是这个 schema 上的一条**死索引**：主键已经比它更严。
--   ⇒ 即**"版本递增"这件事在库层根本无法表达**：往同一 scale_id 写第二个版本时，
--     先撞的是主键（23505 / 或被 ON CONFLICT 吞掉），永远轮不到 uq_scale_id_version。
--
--   【这条事实的后果不是"报错"，而是【静默的语义漂移】，两个方向都会出事】
--     · 若函数照抄 V18 形态（只判 ROW_COUNT + 谁的）：
--       调用方传一个**新版本号** v2 想升级量表，函数返回 'ALREADY_EXISTS' ——
--       调用方据此认为"量表已存在（可能以为是自己要的那版）"，
--       而库里那一行的 scale_version 其实还是 v1。
--       🛑 于是 `ledger.scaleVersion()` 会返回 v1，而被写入的基线评估
--          （若它引用的是 v2 的语义）就与量表版本对不上了。
--       这与 V18 的"返回值在撒谎"是**同一族**：返回值说"已存在"，
--       而调用方真正想知道的是"我要的那一版在不在"。
--     · 若函数改成"版本不同就 UPDATE 覆盖"：
--       那就把 V5 注释里的「不可覆盖」**静默违反**了 —— 一次"顺手升级"
--       会改掉一个已被历史 baseline_assessment 引用的量表的版本号，
--       而那些基线评估的 scale_version 快照语义随之失真。
--
--   【处置：让"版本不同"成为一条【显式的 RAISE】，而不是任何一个返回值】
--     在 (5) 的第 ② 段（本租户看得见 ⇒ 它在本租户名下）里追加一步：
--       · 读回本租户那一行的 scale_version；
--       · 若它与 p_scale_version **不同** ⇒ RAISE（消息含「不可覆盖」）。
--     这样：
--       · 传同一版本 ⇒ 'ALREADY_EXISTS'（真·幂等重放，如实）
--       · 传不同版本 ⇒ 异常（需要人工决定：是新建一个 scale_id，还是显式废弃旧版）
--     🛑 为什么是 RAISE 而不是第三态 `VERSION_CONFLICT`：
--       与 V18 的跨租户撞号同理由 —— 它不是"另一种正常结果"，而是数据冲突，
--       需要人工介入。一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"，
--       而"继续流程"在这里恰好是一次版本错配的基线评估。
--     🛑 为什么不是 UPDATE：见上面第二段。覆盖会静默违反 DDL 自己的注释，
--       而 DDL 的注释是本仓里"不可覆盖"这条口径的唯一书面来源 ——
--       函数去改它，等于把口径写反。
--     🛑 为什么不顺手把主键改成 (tenant_id, scale_id)：那是契约 MAJOR 变更级的
--       schema 变更（影响 uq_tenant_scale_scale_id 载体、V16 的复合外键、
--       以及全部按 scale_id 单列的读路径），且它要先回答"同 scale_id 多版本时
--       历史 baseline_assessment 该指向哪一行"。本迁移不代拍这个口径，
--       而是把现状（死索引 + 显式 RAISE）钉住，并把它登记进缺口清单。
--
-- 【为什么建档通路【不】是 HTTP 端点（有意形态，不是没做完）】
--   逐条核对契约 `openapi-v1.0.0.yaml` 的全部 40 个 path：
--     · 没有任何 path 是"创建量表"；
--     · 与量表相关的 path 只有 C1（题库拉取）/ C2（基线评估提交）/ C3（评估详情）——
--       三者都是"使用量表"，不是"定义量表"。
--     · C2 的 description 逐字把 scale 的存在性当成【前提】而非【结果】。
--   ⇒ 与 B-7（组织开通）/ B-10（手环绑定）/ B-11（设备建档）完全同型：**契约化决策**。
--     给建档加对外端点属契约 MAJOR 变更，且要先回答契约回答不了的问题：
--     「谁有权定义一个租户的量表内容」？—— 注意这不是"谁有权提交评估"（那是 C2 的
--     assessment:write），量表的定义与题库版本属**配置/研究**职责，与临床录入不是同一权。
--   ⇒ 故本迁移提供【数据库层原语】：两个幂等的 plpgsql 函数。可被运维 psql
--     直接调用，也可被应用内**不对外暴露**的 ScaleService 调用（与 B-7/B-10/B-11 同款）。
--
-- 【🛑 为什么不在函数里冻结 dimension_set_json 的 7 维取值集】
--   V5 L238 的注释逐字写着「§2.10: 7 维枚举（与 PRD 附录 C.1.3 逐字同字面）」，
--   但 DDL 里 `dimension_set_json JSONB NOT NULL` **没有任何 CHECK**。
--   ⇒ 取值集此刻只存在于 PRD 附录 C.1.3 里，不在库层也不（目前）在函数里。
--   本函数**只**要求它是一个"非空的 JSON 对象"，**不**冻结维度名：
--     · 冻结它 = 把 PRD 附录的取值集抄进第二处 ⇒ 两处口径会各自漂移
--       （一处改维度名、一处没改），而"哪个更严"取决于谁先跑。这与 V18 不冻结
--       model 的**结论相同、理由不同**（V18 的理由是"CHECK 已冻结，再写一遍是重复定义"；
--       这里的理由是"没有第二处真相源可对齐，写进来就是**新造**一个"）。
--     · 但"非空对象"这一条必须守：`{}` 与 `null` 都让"这个量表测哪几维"变成不可答，
--       而 baseline_assessment 的维度分（恰 7 项、每项 0–16）要与之对齐。
--   ⇒ 本迁移把"7 维取值集未被库层冻结"这条事实登记进缺口清单（见文件末），
--     而不是在这里替它做一个半成品的冻结。
--
-- 【🛑 为什么本迁移【没有】V18 自证 (e7) 那样的"跨租户引用被外键拒"】
--   V18 的 (e7) 证的是 `device_store_id_fkey (tenant_id, store_id)` ——
--   device 有一列**可以指向另一个租户的对象**（门店）。
--   而 scale 的引用只有一条：`scale_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id)`
--   —— 它是**单列自引用租户**，不存在"指向别家对象"的那一列。
--   ⇒ 那条判据在 scale 上没有对应物。**本迁移刻意不编造一个对应物**：
--     硬造一条（例如"往 scale 里写一个不存在的 tenant_id"）证的是"外键存在"，
--     与 (e7) 想证的"跨租户引用完整性"是两件不同的事。
--     本仓纪律：判据的适用范围 = 它的锚点范围。故此处**如实留白**，
--     并在自证 (e6) 里保留了对照性的那一条（同租户裸 INSERT 必须成功）。
--
-- 【幂等口径（与 V1~V18 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 函数天然支持 replace）
--   ② 写入侧 ON CONFLICT DO NOTHING + 返回两态（+ 三类 fail-closed 异常）
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（自证探针在一个子事务里跑，结束整体撤销）
--
-- 【🛑 本文件语句顺序不可调换：授权 → 登记 → 自证 → 完成】
--   自证 (d) 断言 schema_migration 里 V19 登记行数 = 1，故【登记必须排在自证之前】。
--   与 V15 / V17 / V18 同款。⚠️ 这是本仓第四次写这句话 —— 故 120 反向验证
--   把它做成了**受控注入**（把登记挪到自证之后 ⇒ 必须报 (d)）。
--
-- 【回滚说明】
--   本迁移只新增两个函数、不建表、不写业务行。
--     DROP FUNCTION IF EXISTS register_scale(uuid, uuid, text, text, text, jsonb);
--     DROP FUNCTION IF EXISTS deprecate_scale(uuid, uuid);
--     DELETE FROM schema_migration     WHERE version = 'V19';
--     DELETE FROM flyway_schema_history WHERE version = '19';
--   🛑 已建档的 scale 行【不受回滚影响】—— 它们是数据不是 schema。
--      若要清理，顺序必须是 baseline_assessment → scale → tenant
--      （baseline_assessment.scale_id 是 NOT NULL 引用，V16 后已是复合形态
--        FOREIGN KEY (tenant_id, scale_id) REFERENCES scale (tenant_id, scale_id)）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立，否则后面的一切都是空中楼阁
--
--   本迁移的设计建立在五个已存在的 schema 事实之上，五条都会在下面机械断言：
--     ① scale 是 FORCE RLS
--        —— 否则"建立上下文"是多余动作，而本函数的价值主要就在这；
--     ② `scale_pkey` 是**单列** `(scale_id)`
--        🛑 这一条是【核心机制二】的**唯一前提**。若哪天有人把它改成
--           `(tenant_id, scale_id)`，那么"跨租户撞号"这件事**就不存在了**，
--           于是本函数的 RAISE 分支变成死代码，而自证 (e4) 会以
--           "竟然成功了"的面目失败 —— 一条归因错误的红。故提前断言。
--     ③ `uq_tenant_scale_scale_id` = UNIQUE (tenant_id, scale_id) 存在
--        —— 它是 `baseline_assessment_scale_id_fkey` 的**引用目标**
--           （PG 要求被引用列是唯一键）。缺了它，V16 的复合外键根本上不去。
--     ④ `uq_scale_id_version` = UNIQUE (scale_id, scale_version) 存在
--        🛑 这一条断言的是**设计意图仍在**，而不是"它有效"：
--           在单列主键下它永远不可能被违反（见文件头「核心机制三」）。
--           但它是 V5 注释里「版本递增·不可覆盖」的**唯一载体**。
--           本迁移的 (5) 第 ② 段用 RAISE 表达那条口径 ——
--           若有人把它删掉（认为"反正不会违反"），那么"不可覆盖"就**只剩注释**了。
--           故在此断言它仍在，把"删除它"变成一次显式动作。
--     ⑤ `baseline_assessment_scale_id_fkey` 是**复合**形态（含 tenant_id）
--        —— V16 的成果必须仍在。它决定"跨租户量表引用"是否被库层拒绝。
--           🛑 这一条对本迁移尤其重要：C2 的 scaleExists 检查是**应用层**的，
--              它可以被绕过（直接写 SQL）；复合外键不能。
-- ============================================================================

DO
$v19_precond$
DECLARE
    v_rls      boolean;
    v_pk       text;
    v_uq_tenant text;
    v_uq_ver    text;
    v_ba_fk    text;
BEGIN
    -- ① scale 存在且 FORCE RLS
    SELECT c.relrowsecurity INTO v_rls
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'scale';

    IF v_rls IS NULL THEN
        RAISE EXCEPTION
            'V19 前置失败: 表 scale 不存在。本迁移的量表建档原语依赖它（V5 L229 建表）。';
    END IF;
    IF NOT v_rls THEN
        RAISE EXCEPTION
            'V19 前置失败: 表 scale 未启用 ROW LEVEL SECURITY。'
            '本迁移的核心价值之一是"建档必须自己建立租户上下文"，'
            '若 scale 没有 RLS，这个守卫就失去了守护对象 —— 不是"可以省略"，'
            '而是"前提被推翻了"，必须先解释清楚才能继续。';
    END IF;

    -- ② scale_pkey 必须是【单列】scale_id —— 核心机制二的唯一前提
    SELECT pg_get_constraintdef(con.oid) INTO v_pk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'scale' AND con.contype = 'p';

    IF v_pk IS NULL THEN
        RAISE EXCEPTION 'V19 前置失败: 表 scale 没有主键 —— 无法建立 scale_id 的冲突判定。';
    END IF;
    IF v_pk !~ 'PRIMARY KEY\s*\(\s*scale_id\s*\)' THEN
        RAISE EXCEPTION
            'V19 前置失败: scale 的主键形态变了（当前: %）。'
            '本迁移的【核心机制二】完全建立在"scale_pkey 是单列 scale_id"这个事实上：'
            '正因为它不含 tenant_id，"同一 scale_id 被另一租户占用"才可能发生。'
            '🛑 而且它同时还承载【核心机制三】：正因为主键是单列，'
            '同一 scale_id 只可能有一行 ⇒ scale_version 是死维度 ⇒'
            '函数必须对"版本不同"显式 RAISE（不能让调用方以为版本升上去了）。'
            '若主键已改为 (tenant_id, scale_id)：'
            '  · 跨租户分支变成死代码，自证 (e4) 会以【归因错误】的方式失败；'
            '  · 但那个改动**恰好会让"版本递增"变得可以表达** ⇒'
            '    应当同时重写 (5) 第 ② 段与自证 (e3)，而不是只改这里。'
            '🛑 遇到这条报错，正确的反应是【重新推导本迁移的设计与自证】，'
            '而不是把这里的判据改宽 —— 前提变了，结论必须重新推导。', v_pk;
    END IF;

    -- ③ uq_tenant_scale_scale_id 存在（复合外键的引用目标）
    --   🛑 判定用【列集合】而不是字符串 LIKE —— V16 在 2026-09-27 已被这条坑过一次：
    --      pg_get_constraintdef 的输出按 conkey 顺序拼列名，故 LIKE '(tenant_id, %'
    --      实际断言的是"tenant_id 恰好排第一"，而不是"tenant_id 参与了这个键"。
    --      正确的判定是 conkey 的**集合相等**。
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        WHERE c.relname = 'scale' AND con.contype = 'u'
          AND (SELECT array_agg(a.attname ORDER BY a.attname)
                 FROM unnest(con.conkey) AS k(attnum)
                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
              = ARRAY['scale_id', 'tenant_id']::name[]
    ) THEN
        RAISE EXCEPTION
            'V19 前置失败: scale 上没有 UNIQUE (tenant_id, scale_id) 载体。'
            '它是 baseline_assessment_scale_id_fkey 的【引用目标】'
            '—— PG 要求被引用列是唯一键。'
            '🛑 实测真库上它叫 uq_tenant_scale_scale_id；本判据【不按名字找】而按列集合找，'
            '因为名字对语义没有任何约束力（改名不该让门禁变红）。';
    END IF;

    -- ④ uq_scale_id_version 仍在 —— 它是「版本递增·不可覆盖」的唯一载体
    --   🛑 本断言不检查它"有效"（在单列主键下它永远不可能被违反），
    --      只检查它"存在"。理由是：删掉它等于把那条口径**从 schema 里抹除**，
    --      只剩 V5 的注释 —— 而注释不是可判定的事实。
    SELECT pg_get_indexdef(i.indexrelid) INTO v_uq_ver
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indrelid
      JOIN pg_class ic ON ic.oid = i.indexrelid
     WHERE c.relname = 'scale' AND i.indisunique
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(i.indkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
           = ARRAY['scale_id', 'scale_version']::name[];

    IF v_uq_ver IS NULL THEN
        RAISE EXCEPTION
            'V19 前置失败: scale 上的 UNIQUE (scale_id, scale_version) 不见了。'
            '🛑 它是 V5 L235 那条设计口径「版本递增·不可覆盖」在 schema 里的**唯一载体**。'
            '在单列主键（scale_pkey）之下它确实永远不可能被违反 —— '
            '但那正是本迁移必须为它补一条【显式 RAISE】的理由（见文件头「核心机制三」）：'
            '口径不能只写在注释里，注释不是可判定的事实。'
            '若确要移除版本化设计，正确顺序是：先改本迁移的 (5) 第 ② 段与自证 (e3)、'
            '再改 V5 的注释与 §2.10 口径、最后才删本索引。';
    END IF;

    -- ⑤ baseline_assessment_scale_id_fkey 是复合形态（V16 的成果必须仍在）
    SELECT pg_get_constraintdef(con.oid) INTO v_ba_fk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'baseline_assessment' AND con.contype = 'f'
       AND (SELECT count(*) FROM unnest(con.conkey)) = 2
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(con.conkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
           = ARRAY['scale_id', 'tenant_id']::name[];

    IF v_ba_fk IS NULL THEN
        RAISE EXCEPTION
            'V19 前置失败: baseline_assessment 上没有复合形态 (tenant_id, scale_id) 的外键。'
            '这说明 V16（跨租户引用完整性）的成果【在 baseline_assessment 上被回退了】。'
            '🛑 对本迁移尤其重要：C2 的 scaleExists 检查住在【应用层】'
            '（AssessmentLedger.scaleExists），一次写歪的 scale_id 可以绕过它；'
            '库层的复合外键是【第二层】—— 应用可以被人绕过（直接写 SQL），外键不能。'
            '缺了它，"在租户 A 的基线评估里引用租户 B 的量表"就重新变成一条敞开的路径。';
    END IF;
END;
$v19_precond$;


-- ============================================================================
-- 第 1 节 · 建档原语函数
-- ============================================================================

-- ---------------------------------------------------------------------------
-- register_scale(p_tenant_id, p_scale_id, p_scale_type, p_scale_version, p_name,
--                p_dimension_set_json)
--
--   【返回两态（逐字说清，调用方据此审计留痕）】
--     · 'CREATED'        —— 本次调用建出了这一行
--     · 'ALREADY_EXISTS' —— 幂等命中：这一行【已在本租户名下】且【版本相同】
--   【三类 fail-closed 异常】
--     · 入参为空 / 空串               → RAISE（含义不明，且空串会静默流进台账）
--     · 本事务已持有【别的】租户上下文 → RAISE（拒绝静默改写）
--     · scale_id 已被【另一租户】占用  → RAISE（核心机制二）
--     · 🛑 本租户已有该 scale_id 但版本【不同】 → RAISE（核心机制三 —— V19 特有）
--
--   【🛑 为什么"版本不同"必须是 RAISE 而不是返回值 —— 完整理由见文件头「核心机制三」】
--     摘要：single-column 主键下"版本递增"在库层无法表达；若照抄 V18 形态，
--     传新版本的调用会拿到 'ALREADY_EXISTS'，而调用方以为"我要的那一版已存在"，
--     实际库里那行的 scale_version 还是旧值 ⇒ `ledger.scaleVersion()` 返回旧版本
--     ⇒ 被写入的基线评估与量表版本口径错配。这与 V18 的"返回值在撒谎"同族。
--
--   【设计取舍一：scale_type 只挡空串，不冻结取值集】
--     V5 L234 的 CHECK 已冻结 primary / calibration。
--     🛑 与 V18 对 model 的处置**结论相同、理由也相同**：CHECK 已冻结，
--        在函数里再写一遍是重复定义，两处口径会各自漂移。
--     🛑 但本函数**不复制**那份取值集，只挡 NULL / 空串
--        （DDL 的 NOT NULL 挡不住空串，而空串在语义上同样无意义）。
--
--   【设计取舍二：dimension_set_json 只要求"非空 JSON 对象"】
--     见文件头「为什么不在函数里冻结 dimension_set_json 的 7 维取值集」：
--     7 维取值集此刻只存在于 PRD 附录 C.1.3，不在库层 ⇒ 写进函数就是**新造**一个真相源。
--     但 `{}` / `null` 会让"这个量表测哪几维"不可答，故必须挡。
--     🛑 判定用 `jsonb_typeof(...) = 'object'` 而不是 `::text <> '{}'` ——
--        后者会把 `'null'::jsonb`（JSON null）放行，而那是与 `{}` 同类的"不可答"。
--
--   【设计取舍三：为什么不把 p_scale_version 做成可选，缺省时自动递增】
--     "自动递增"需要在函数里读 max(version) 再 +1 —— 而在单列主键下，
--     同一 scale_id 只可能有一行，max 就是唯一那一行的版本 ⇒ 自动递增
--     实际会算出一个**永远无法落库**的版本号（撞主键，被 ON CONFLICT 吞掉）。
--     把它做成"看起来会递增"是最坏的一种：调用方以为版本在往前走，实际钉死在原地。
--     ⇒ 故版本**必须由调用方显式给出**，并由 (5) 第 ② 段如实判定。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION register_scale(
    p_tenant_id          uuid,
    p_scale_id           uuid,
    p_scale_type         text,
    p_scale_version      text,
    p_name               text,
    p_dimension_set_json jsonb
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v19_register$
DECLARE
    v_ctx_before   text;
    v_ctx          text;
    v_mine         int := 0;
    v_existing_ver text := '';
    v_affected     int := 0;
BEGIN
    -- (1) 入参守卫 —— 与 V15 provision_tenant / V17 bind_band / V18 register_device 同款：
    --     DDL 的 NOT NULL 挡不住空串，而空串在语义上同样无意义，且会静默流进台账。
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_scale: p_tenant_id 不可为空（scale.tenant_id 是 NOT NULL）';
    END IF;
    IF p_scale_id IS NULL THEN
        RAISE EXCEPTION
            'register_scale: p_scale_id 不可为空（scale.scale_id 是主键，'
            '且它是 baseline_assessment.scale_id 的引用目标 —— 没有它，C2 基线评估无法落库）';
    END IF;
    IF p_scale_type IS NULL OR btrim(p_scale_type) = '' THEN
        -- 🛑 只挡空值，【不】冻结取值集 —— 理由见「设计取舍一」。
        RAISE EXCEPTION
            'register_scale: p_scale_type 不可为空串（scale.scale_type 是 NOT NULL 且有业务含义，'
            '取值集已由 CHECK 冻结为 primary / calibration —— 本函数不重复冻结，避免口径分叉。'
            '🛑 尤其不要在这里硬编码 ''primary''：双库严格隔离（config #39 / ISO-1~4）'
            '要求这个维度是【显式选择】的，而不是函数替他选的）';
    END IF;
    IF p_scale_version IS NULL OR btrim(p_scale_version) = '' THEN
        RAISE EXCEPTION
            'register_scale: p_scale_version 不可为空串（scale.scale_version 是 NOT NULL，'
            '且它是 baseline_assessment 书写 scale_version 快照的来源 —— '
            '缺了它，`migratable` 会恒为 false，基线评估永远标着"不可迁移"）';
    END IF;
    IF p_name IS NULL OR btrim(p_name) = '' THEN
        RAISE EXCEPTION
            'register_scale: p_name 不可为空串（scale.name 是 NOT NULL —— '
            '运维/集成侧按名字找量表是常态，空名会让台账不可辨认）';
    END IF;
    IF p_dimension_set_json IS NULL
       OR jsonb_typeof(p_dimension_set_json) <> 'object'
       OR p_dimension_set_json = '{}'::jsonb THEN
        RAISE EXCEPTION
            'register_scale: p_dimension_set_json 必须是非空的 JSON 对象（实际类型 = %）。'
            '🛑 本函数【不】冻结 7 维取值集（见文件头），但 {} 与 JSON null 都让'
            '"这个量表测哪几维"变成不可答 —— 而 baseline_assessment 的维度分'
            '（恰 7 项、每项 0–16、总分 0–112）必须与它对齐。'
            '一个维度不可答的量表，会让下游那条"Σ 维度分 = 总分"的自洽校验失去对齐对象。',
            coalesce(jsonb_typeof(p_dimension_set_json), '<SQL NULL>');
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     理由与 V17 / V18 逐字相同，此处摘要：
    --       set_config(app.tenant_id, ..., true) 是**事务级**设置，且**不会**在函数入口
    --       自动重置。若调用方在一个"已设成租户 B"的事务里调用本函数传租户 A，
    --       不做检查就会把上下文**静默改写成 A**：后面的写是 A 的，而调用方以为是 B 的。
    --     为什么宁失败不可静默覆盖：量表是**被 baseline_assessment 引用**的对象，
    --     写错租户就是"一个租户的临床评估引用了另一个租户的量表口径"。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_scale: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, ..., true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '调用方须先结束当前事务（或使用全新连接）再建档。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 用 set_config（普通函数、参数可绑定），不用 SET LOCAL
    --        （后者必须把值拼进 SQL 文本 ⇒ 有注入面）。与 V15 / V17 / V18 同款。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_scale 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (5) 🛑🛑 幂等写入 + 跨租户撞号 + 版本冲突判定 —— 本迁移的核心
    --     三段判定全部来自库层原子事实，**没有**"先读后写"的并发窗口：
    --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的
    --       ② ROW_COUNT=0 且本租户看得见 ⇒ 是本租户的行 ⇒
    --          再比版本：相同 ⇒ 幂等命中 'ALREADY_EXISTS'；不同 ⇒ RAISE（核心机制三）
    --       ③ ROW_COUNT=0 且本租户看不见 ⇒ 插不进去又看不见 ⇒ 在别的租户名下 ⇒ RAISE
    --     🛑 为什么第 ③ 段是**正确**的而不是"猜测"：
    --        能冲突就证明"这一行在全局存在"。而 RLS 的可见性是**稳定**的
    --        （别人的行在任何时刻都读不到，不是时序问题），
    --        故"读不到"精确地等价于"不属于本租户"。逻辑上不依赖窗口。
    --     🛑 与 V18 的差别只在第 ② 段：这里多了一次版本比对。原因见文件头「核心机制三」。
    INSERT INTO scale (scale_id, tenant_id, scale_type, scale_version, name,
                       dimension_set_json, status, created_by)
    VALUES (p_scale_id, p_tenant_id, p_scale_type, p_scale_version, p_name,
            p_dimension_set_json, 'active', 'scale-registration')
    ON CONFLICT (scale_id) DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- ① 本次真的建出来了
    IF v_affected = 1 THEN
        RETURN 'CREATED';
    END IF;

    -- ② 未插入 ⇒ 全局已存在。它在谁名下？版本对不对？
    --    🛑 这次读在【已自证的本租户上下文】里，故它只看得到本租户的行。
    --       max() 在这里是安全的：主键是单列 scale_id ⇒ 最多一行 ⇒ max 就是那一行的值。
    SELECT count(*), coalesce(max(scale_version), '') INTO v_mine, v_existing_ver
      FROM scale
     WHERE scale_id = p_scale_id;

    IF v_mine = 1 THEN
        -- 🛑🛑 核心机制三：版本必须【相同】才算幂等重放
        IF v_existing_ver <> p_scale_version THEN
            RAISE EXCEPTION
                'register_scale: scale_id % 在本租户 % 名下已存在，但版本不同'
                '（库中 = %，本次传入 = %）—— 拒绝执行，版本【不可覆盖】。'
                '🛑 为什么这不是一个可以继续流程的返回值：'
                'scale_pkey 是【单列】scale_id ⇒ 同一 scale_id 只可能有一行 ⇒'
                '在库层无法表达"版本递增"（uq_scale_id_version 永远不可能被违反，'
                '它是这个 schema 上的一条死索引）。'
                '若在此返回 ALREADY_EXISTS，调用方会以为"我要的那一版已存在"，'
                '而库里那个字段其实还是 % —— 于是 ledger.scaleVersion() 返回旧版本，'
                '被写入的基线评估与量表版本口径错配。这与 V18 的"返回值在撒谎"同族。'
                '🛑 为什么也不在这里 UPDATE 覆盖：那会把 V5 L235 的「不可覆盖」静默违反，'
                '并改掉一个已被历史 baseline_assessment 引用的量表版本号，'
                '而那些基线评估的版本快照语义随之失真。'
                '正确处置：要么为这一版新建一个 scale_id，'
                '要么先显式 deprecate_scale 旧版、再决定新版如何承载。',
                p_scale_id, p_tenant_id::text, v_existing_ver, p_scale_version,
                v_existing_ver;
        END IF;
        RETURN 'ALREADY_EXISTS';
    END IF;

    -- ③ 插不进去却又看不见 ⇒ 它在别的租户名下
    --    🛑 这条 RAISE 就是"若照抄一个只按 ROW_COUNT 判定的实现就会造出的缺陷"的封堵点。
    --       没有它，上面会 return 'ALREADY_EXISTS'，而本租户一行都没有 ——
    --       调用方据此认为量表已就绪并继续提交基线评估，而 C2 的 scaleExists 仍为 false。
    RAISE EXCEPTION
        'register_scale: scale_id % 已被【另一租户】占用 —— 无法在本租户 % 名下建档。'
        '判定依据（无窗口）：INSERT ... ON CONFLICT (scale_id) DO NOTHING 被【主键冲突】拦下'
        '（ROW_COUNT=0），但在已自证的本租户上下文里 SELECT 又看不见这一行（可见行数=0）——'
        '"冲突了"证明它全局存在，"看不见"证明它不属本租户 ⇒ 它属于别的租户。'
        '🛑 为什么这不是"另一个 ALREADY_EXISTS"：scale_pkey 是【单列】scale_id，'
        '即"一个量表标识的一生只属于一个租户"是既有 schema 的既定事实。'
        '这不是幂等重放，而是数据冲突，需要人工确认是 scale_id 传错了、还是量表被错误分发。'
        '若在此返回 ALREADY_EXISTS，调用方会以为量表已就绪并继续提交基线评估，'
        '而那一次 C2 仍会以"scale_id 指向的量表不存在或不属当前租户"被拒 —— 即"返回值在撒谎"。',
        p_scale_id, p_tenant_id::text;
END;
$v19_register$;


-- ---------------------------------------------------------------------------
-- deprecate_scale(p_tenant_id, p_scale_id)
--
--   【返回三态】
--     · 'DEPRECATED'         —— 本次调用把它从 active 置为 deprecated
--     · 'ALREADY_DEPRECATED' —— 它已经是 deprecated（重放，幂等）
--     · 'NOT_FOUND'          —— 本租户内看不到这一行
--        🛑 措辞刻意是 NOT_FOUND 而不是 "NOT_EXISTS"：在 FORCE RLS 下
--           "不存在"与"存在但当前上下文看不见"**给出同一个结果**。
--           用"不存在"这个词会把后一种情形说成事实，属对调用方的误导。
--           调用方看到 NOT_FOUND 时应先复核租户上下文，再去怀疑数据缺失。
--           （与 V17 unbind_band / V18 retire_device 的三态措辞逐字同款。）
--
--   【🛑 为什么废弃是 UPDATE 而不是 DELETE】
--     三条独立的理由（前两条与 V18 同款，第三条是量表特有）：
--       ① 引用完整性：baseline_assessment 通过
--          `baseline_assessment_scale_id_fkey (tenant_id, scale_id)` 引用本表（NOT NULL）。
--          删掉一行会让历史基线评估失去引用对象 —— 而那些评估是"客户健康轨迹"的起点，
--          它们的 scale_version 快照本就是为了"事后能回答用的是哪一版"而存在的。
--       ② 业务：active 与 deprecated 的区分本身是"这个量表还能不能用于新评估"，
--          它是**状态**而不是"存在与否"。删掉之后"何时废弃"不可答。
--       ③ 🛑 量表特有：本表的语义是**版本化**的（V5 注释「版本递增·不可覆盖」）。
--          在"版本"这套语义里，"废弃"是版本的**生命周期**动作，
--          而"删除"会把"曾经有过这一版"这件事一起抹掉 ——
--          那正好和版本化想解决的问题（"事后能回答用的是哪一版"）相反。
--       🛑 副作用（也是刻意保留的性质）：'deprecated' 行仍然占据 scale_id 主键 ⇒
--          重放建档返回 ALREADY_EXISTS（由自证 (e9) 钉住）。
--          这意味着**主键复用需要先物理删除废弃行**，而那是一个显式的人工决定，
--          不该被一次"顺手重建"静默完成。
--
--   【为什么只允许 active → deprecated】
--     status 的 CHECK 冻结了 active / deprecated 两个值（V5 L241），
--     本函数暴露 active → deprecated 一个方向。
--     🛑 刻意**没有** reactivate_scale ⇒ "复活一个已废弃的量表"在任何生产路径上都不可达。
--        理由与本迁移的核心机制三同源：量表被废弃通常伴随"新版 supersede 旧版"这件事，
--        而"复活"在**版本化**语义下含义不明（复活哪一版？已有的基线评估怎么办？）。
--        故本迁移不假装实现它，而是把这条事实登记进缺口清单。
--     🛑 谓词写成 `status <> 'deprecated'` 而**不是** `status = 'active'`，这是有意的：
--        与 V18 的 retire_device 逐字同理由 —— 若将来 CHECK 里加了第三态，
--        `status = 'active'` 会让"废弃一个非 active 的设备"变成 NOT_FOUND
--        （执行了 UPDATE，但 0 行命中 ⇒ 落到哪一态取决于写法，极易写错）；
--        而 `status <> 'deprecated'` 的语义是"只要还没废弃就能废弃" —— 这才是该有的口径。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION deprecate_scale(
    p_tenant_id uuid,
    p_scale_id  uuid
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v19_deprecate$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_cur_status text;
    v_rows       int := 0;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'deprecate_scale: p_tenant_id 不可为空（scale.tenant_id 是 NOT NULL）';
    END IF;
    IF p_scale_id IS NULL THEN
        RAISE EXCEPTION 'deprecate_scale: p_scale_id 不可为空（scale.scale_id 是主键）';
    END IF;

    -- (2) 上下文一致性守卫（与 register_scale 同款，逐字同理由）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'deprecate_scale: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '同 register_scale 的理由：本函数会覆盖它，从而让"后续写入去了另一个租户"静默发生。'
            '废弃尤其危险 —— 它决定"这个量表还能不能用于新评估"，'
            '而历史 baseline_assessment 的版本快照语义依赖它。',
            v_ctx_before, p_tenant_id::text;
    END IF;

    -- (3) 建立并自证上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'deprecate_scale 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 读当前状态（在已自证的上下文内）
    SELECT status INTO v_cur_status
      FROM scale
     WHERE scale_id = p_scale_id AND tenant_id = p_tenant_id;

    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_cur_status = 'deprecated' THEN
        RETURN 'ALREADY_DEPRECATED';
    END IF;

    -- (5) 状态迁移
    --     🛑 谓词里带 `status <> 'deprecated'` 是为了让这条 UPDATE 在并发下**正确**：
    --        两个并发废弃都会通过第 (4) 步的读（都读到非 deprecated），
    --        若 UPDATE 不带这个条件，两次都会"成功"，第二次的 updated_at 会覆盖第一次的
    --        —— 一次静默的数据回退（"废弃时间"被推后）。
    --        带上它之后，第二次的 ROW_COUNT = 0 ⇒ 落到 ALREADY_DEPRECATED。
    --        （与 V17 unbind_band / V18 retire_device 同族：保证必须由【库层条件】给出，
    --          不能依赖一次可能过期的读。）
    --     🛑 为什么同时写 `tenant_id = p_tenant_id`：让这条 UPDATE 的**租户维度在 SQL 里可见**，
    --        与 RLS 策略形成两层（RLS 兜底、谓词自证意图）。少写它 RLS 仍会兜住，
    --        但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读。
    --        本仓把可读性当成安全属性的一部分。
    UPDATE scale
       SET status     = 'deprecated',
           updated_at = now()
     WHERE scale_id   = p_scale_id
       AND tenant_id  = p_tenant_id
       AND status    <> 'deprecated';

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 1 THEN
        RETURN 'DEPRECATED';
    END IF;
    RETURN 'ALREADY_DEPRECATED';
END;
$v19_deprecate$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15 / V17 / V18 第 2 节同款，理由逐字相同（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，
--       故本节的 GRANT 在常规环境里是 no-op；写它是"显式优于隐式"，
--       以及在"生产环境 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固"后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d2) 用 has_function_privilege 机械断言；
--      "授权段是否真的执行过"由 (d3) 断 proacl 的【显式项】—— 见那里的说明。
-- ============================================================================

DO
$v19_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_scale') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_scale(uuid, uuid, text, text, text, jsonb) TO %I',
            v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'deprecate_scale') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION deprecate_scale(uuid, uuid) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V19 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v19_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是**第 4 次**面对它：
--        · V15 踩过（初版 263 字符 ⇒ 「对于可变字符类型来说，值太长了(256)」）；
--        · V17 又踩过（初稿 358 字符）；
--        · V18 第 3 次（写下时余量 22）。
--   本行的量法（把整条字符串当 ASCII 数一遍）+ 结果：
--        printf 量得 = 237 字符（上限 256，余量 19）
--   🛑 为什么必须"量"而不是"目测"：这条字符串里全是英文短横与括号，视觉上"看起来不长"，
--      而 V17 那条超了 102 个字符 —— 目测在这件事上不可靠。
--   🛑 为什么不在迁移里用 char_length() 断言它：本行是 INSERT 的**值**，
--      要断言就得再读一次（另写一条 SELECT）—— 那会把"一处事实"变成"两处要保持一致"。
--      量长度是**写入前**的动作，属人与本仓纪律之间的约定，不是库层能自证的东西。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V19', 'scale provisioning primitive: register_scale() + deprecate_scale(). scale had no production writer, so C2 baseline assessment could never succeed (422 scale_id not found) and scale_version stayed null. Ops path; no create-scale endpoint.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   (a) 两个函数必须真的存在
--   (b) register_scale 的函数体必须含【六件套】—— 每一件都对应一类已被实测踩过的缺陷：
--        ① 建立上下文          set_config('app.tenant_id', ..., true)
--        ② 上下文自证          assert_tenant_context()
--        ③ 上下文一致性守卫    current_setting('app.tenant_id', true)
--        ④ 幂等写入的原子形态  INSERT INTO scale ... ON CONFLICT (scale_id) DO NOTHING
--        ⑤ 🛑 跨租户撞号的判定分支（与 V18 同型，但本次是【读 schema 推出来的】）
--        ⑥ 🛑🛑 版本冲突的判定分支（V19 特有 —— 见文件头「核心机制三」）
--   (c) deprecate_scale 的函数体必须含 ①②③ 与"状态迁移而非删除"的证据
--   (d) 迁移登记 + 当前角色对两个函数真的有 EXECUTE 权限 + 授权段真的执行过（proacl 显式项）
--   (e) 🛑 行为验证 —— 本迁移最重要的一条。前四条都只检查"函数体长什么样"，
--       而"函数体看着对、跑起来不对"是本仓反复出现过的形态（自证必须证明**行为**）。
--       共 10 段：
--         e1 建档 → CREATED，且行确实落库（6 列同时对上）
--         e2 重放同一 (scale_id, scale_version) → ALREADY_EXISTS（真·幂等），
--            且不得产生第二行
--         e3 🛑🛑 重放【不同】版本 → 必须 RAISE（不是 ALREADY_EXISTS），
--            消息里必须含"不可覆盖"，且库里那一行的 scale_version 【不得被改动】。
--            这一条是 V19 相对 V18 形态的**唯一实质新增**，也是 120 反向验证的靶心。
--         e4 🛑🛑 跨租户撞号：租户 B 用租户 A 已占用的 scale_id →
--            必须 RAISE（**不是** ALREADY_EXISTS），且消息里必须含"另一租户"。
--            与 V18 的 (e5) 同型。
--         e5 对照：租户 B 用自己的 scale_id → 必须 CREATED
--            （防"拒绝一切"的实现蒙混过关）
--         e6 对照：同租户裸 INSERT（不同 scale_id）→ 必须**成功**
--            （防"把正常路径一起打死"）
--         e7 🛑【C2 解锁的行为验证 —— 本迁移的验收口径】
--            在租户 A 上下文里，逐字跑 AssessmentLedger.scaleExists / scaleVersion
--            所发的那两条 SQL，必须得到 true 与【非空】版本号。
--            🛑 为什么必须单独有这一段：e1 证的是"函数写进去了"，
--               而本迁移存在的**理由**是"C2 端点不再恒 422"。
--               两者之间隔着一次读 —— 而"写进去了但读不到"（上下文/RLS/列名错）
--               是完全可能的，且那种情况下 e1 仍会全绿。
--            🛑 这一段同时把 migratable 的根因钉住：
--               它的表达式是 `itemGroupId != null && scaleVersion != null`，
--               而 scaleVersion 来自 scale.scale_version 的反查。
--               scale 零行 ⇒ 恒 null ⇒ 恒 false。e7 断言它现在**非空**。
--         e8 跨租户 C2 场景：租户 B 上下文里 scaleExists(租户 A 的 scale_id) 必须为 false
--            （防 e7 退化成"任何 scale_id 都存在"的假绿 —— 那是 C2 缺口换了个方向）
--         e9 废弃 → DEPRECATED；再废弃一次 → ALREADY_DEPRECATED（幂等），
--            且 updated_at 不得被第二次调用推后；
--            废弃行仍占主键 ⇒ 重放建档（同版本）必须 ALREADY_EXISTS
--         e10 清场前的正向计数：证明确实有行写进去过
--            （否则下面的 DELETE 是空操作，"清场成功"与"什么都没发生"同形）
--   (f) 探针清场 + 清场自证（一行业务数据都不许留下）
--
--   🛑 全部探针在一个 BEGIN ... EXCEPTION 子事务里跑，块结束时整体撤销 ——
--      迁移不得留下任何业务数据（V1~V18 一致的纪律）。
--      子事务的 EXCEPTION 会建立 savepoint，故内部 INSERT 无论成功与否都在块末撤销。
--
--   🛑 注意 e3 / e4 必须用**内层** BEGIN/EXCEPTION 包住那条会抛的语句，
--      否则一次预期的 RAISE 会把外层子事务标记为已回滚，
--      后续断言全部以「当前事务被终止」失败 —— 一条归因完全错误的红。
--      （V18 的 (e3)/(e5) 已踩过这条并留下注释。）
-- ============================================================================

DO
$v19_guard$
DECLARE
    v_register_body text;
    v_deprecate_body text;
    -- 🛑 2026-09-27（120）：两条函数体【剥掉行注释后】的代码态。
    --    见下方 "把两条函数体【先剥掉行注释】再匹配" 那一大段说明 ——
    --    prosrc 原样保留注释 ⇒ 所有语句形态判据都可以被注释满足（实测假绿）。
    v_register_code  text;
    v_deprecate_code text;
    v_one_body      text;
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
    v_updated_first timestamptz;
    v_exists        boolean;
    v_ver           text;
    v_bypass        boolean;
    -- ---- 探针租户 / 量表（19 前缀，与 V18 的 18 前缀互不重叠）----
    v_ta          uuid := '19000000-0000-0000-0000-00000000000a';
    v_tb          uuid := '19000000-0000-0000-0000-00000000000b';
    v_scale_a1    uuid := '19000000-0000-0000-0000-0000000000a1';  -- e1/e2/e7/e9（版本 v1）
    v_scale_a2    uuid := '19000000-0000-0000-0000-0000000000a2';  -- e6 同租户裸 INSERT 对照
    v_scale_b1    uuid := '19000000-0000-0000-0000-0000000000b1';  -- e5 租户 B 自用（对照）
    v_scale_shared uuid := '19000000-0000-0000-0000-0000000000c1'; -- e4 跨租户撞号（租户 A 先占）
    v_scale_b2    uuid := '19000000-0000-0000-0000-0000000000c2';  -- e4 之后租户 B 用自己 id 建
    v_dims        jsonb := '{"probe":"v19-witness"}'::jsonb;        -- 探针维度集（本迁移不冻结 7 维）
BEGIN
    -- ==================================================================
    -- (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
    --
    --   【这条守卫是 2026-09-27 实测抓出来的，不是事先想到的】
    --   初版自证在 (e4) 上以"租户 B 用租户 A 已占用的 scale_id 建档没有抛错
    --   （返回值 = ALREADY_EXISTS）"失败。排查发现：那一次是用 `postgres`
    --   （实测 rolsuper=t, rolbypassrls=t）跑的试跑，而 **BYPASSRLS 会绕过行级安全**
    --   ⇒ `set_config('app.tenant_id', 租户B)` 之后，那条
    --     `SELECT count(*) FROM scale WHERE scale_id = p_scale_id`
    --   **仍然看得见租户 A 的那一行** ⇒ v_mine = 1 ⇒ 判定链落到"是我的"分支
    --   ⇒ 返回 ALREADY_EXISTS。**函数本身完全正确**，是证据的效力前提不成立。
    --
    --   🛑 这是本仓反复出现的同一族形态：「判据的适用范围 = 它的锚点范围」。
    --      本自证里依赖 RLS 的判据共 4 条（(e4) 零行、(e4) 跨租户不可见、
    --      (e7) 可见、(e8) 不可见），它们的有效性**完全建立**在
    --      "当前角色受 RLS 约束"之上。而 BYPASSRLS 会让这 4 条**静默失效**：
    --        · (e4) 会以"竟然返回 ALREADY_EXISTS"的面目报红（归因错误 —— 像函数坏了）；
    --        · (e8) 会以"竟能看见租户 A 的量表"报红（同样归因错误）；
    --        · 而若有人为了"让自证通过"去改函数，会改坏一个**本来正确**的实现。
    --   ⇒ 故在此显式断言，让红指向真原因（角色不对），而不是指向函数。
    --
    --   🛑 为什么必须用 `rolbypassrls` 而不是 `current_setting('is_superuser')`：
    --      两者不等价 —— 一个 **非超级用户** 也可以被授予 BYPASSRLS
    --      （实测应用角色 diaoyuanyun: super=off 且 bypassrls=f，两者恰好一致，
    --        但那是本机现状而不是一般规律）。只查 is_superuser 会漏掉
    --      "次超级用户被授予 BYPASSRLS"这一种环境。
    --   🛑 顺带说明为什么本仓的**应用角色**是对的：
    --      实测 diaoyuanyun 的 super=false / bypassrls=false，
    --      即生产路径上 RLS 是真的在起作用的 —— 本守卫只是要求
    --      "跑迁移/自证的角色"与"跑应用的角色"在这一点上一致。
    -- ==================================================================
    SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;

    IF v_bypass IS NULL THEN
        RAISE EXCEPTION
            'V19 自证失败(a0): 查不到当前角色 % 的 rolbypassrls 属性（pg_roles 无此角色？）', current_user;
    END IF;
    IF v_bypass THEN
        RAISE EXCEPTION
            'V19 自证失败(a0): 当前角色 % 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 4 条判据全部失去效力】，'
            '继续执行只会产出一份"看起来跑了、实际什么都没验"的证据。'
            '🛑 具体失效路径（本机已实测）：BYPASSRLS 会绕过行级安全 ⇒ '
            '以租户 B 的上下文执行 `SELECT count(*) FROM scale WHERE scale_id = <租户A的量表>` '
            '仍会看到那一行 ⇒ register_scale 的判定链落到"是我的"分支 ⇒ 返回 ALREADY_EXISTS ⇒ '
            '自证 (e4) 以"跨租户撞号没有抛错"的面目报红。'
            '🛑 那条红是【归因错误】的：函数是对的，是证据的效力前提不成立。'
            '若照它的字面意思去改函数，会改坏一个本来正确的实现。'
            '正确处置：用一个【不】绕过 RLS 的角色跑本迁移（本仓应用角色 diaoyuanyun '
            '实测 super=false / bypassrls=false，即为正确选择），'
            '而不是放宽本自证或修改函数。', current_user;
    END IF;

    -- ==================================================================
    -- (a1) 🛑 能力守卫二：当前角色必须【看得见该看的行、看不见不该看的行】
    --
    --   (a0) 排除了 BYPASSRLS 这一种"完全绕过"的情形，但还有一种更隐蔽的：
    --   **RLS 策略本身被改动或缺失**。它会让 4 条判据同样静默失效，而角色属性完全正常。
    --   ⇒ 在本自证写入任何行之前，先用一对"已知答案"的探针把 RLS 的实际行为量出来：
    --     `SELECT 1 FROM pg_policies WHERE tablename='scale'` 必须恰有 1 条策略，
    --     且 policydef 里的 USING 与 WITH CHECK 都引用 app.tenant_id。
    --   🛑 为什么是"量行为"而不是"断言策略存在"：
    --      存在性断言挡不住"策略被改成了 USING (true)"这种改动 —— 而那会让
    --      (e8) 静默假绿（所有租户互相可见）。故必须断言策略的【定义内容】。
    --   🛑 这里刻意断言**两条**: USING 与 WITH CHECK 都必须引用 app.tenant_id。
    --      只查其中一条会漏掉"读隔离在、写隔离没了"（或反之）——
    --      而写隔离缺失会让 (f2) 清场后的残留检查静默通过。
    -- ==================================================================
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'scale') <> 1 THEN
        RAISE EXCEPTION
            'V19 自证失败(a1): scale 表上的 RLS 策略数 ≠ 1 个 —— '
            '本自证的 4 条 RLS 判据（(e4) 零行 / (e4) 不可见 / (e7) 可见 / (e8) 不可见）'
            '全部建立在"策略按 app.tenant_id 隔离"之上。'
            '🛑 这种情形下角色属性正常（(a0) 会通过），但判据同样静默失效 ⇒ '
            '必须在写入任何探针数据之前把它变成显式红。';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public' AND tablename = 'scale'
           AND qual        LIKE '%app.tenant_id%'
           AND with_check  LIKE '%app.tenant_id%'
    ) THEN
        RAISE EXCEPTION
            'V19 自证失败(a1): scale 的策略虽然存在，但其 USING / WITH CHECK 未同时引用 app.tenant_id。'
            '🛑 断言【定义内容】而不是存在性：一条 `USING (true)` 的策略能让'
            '(e8)（跨租户不可见）静默假绿 —— 所有租户互相可见，而自证全绿。'
            '而 WITH CHECK 缺失会让写入不受租户约束，使 (f2) 的清场残留检查失去意义。';
    END IF;

    -- ------------------------------------------------------------------
    -- (a) 函数存在
    --   🛑 判空用 `IS NOT NULL`（string_agg 在"无缺失行"时返回 NULL），
    --      不是 `array_length(...) > 0` —— 后者会以一条与 (a) 无关的错误失败
    --      （「有缺陷的数组常量」），V17 已被 118 的 I(a) 抓过这条（本仓教训）。
    -- ------------------------------------------------------------------
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_missing
    FROM (VALUES ('register_scale'), ('deprecate_scale')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V19 自证失败(a): 以下函数未创建成功 -> %', v_missing;
    END IF;

    -- ------------------------------------------------------------------
    -- (b)(c) 函数体断言（🛑 只读 prosrc = 函数体原文，不读整个文件）
    --       读整个文件会把本注释块里的示例文本当成"代码里存在"⇒ 假绿。
    --       本文件的注释里【逐字】写着 set_config / assert_tenant_context /
    --       INSERT INTO scale / ON CONFLICT / 另一租户 / 不可覆盖 ——
    --       这正是必须只读 prosrc 的原因。
    -- ------------------------------------------------------------------
    SELECT p.prosrc INTO v_register_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_scale';

    SELECT p.prosrc INTO v_deprecate_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'deprecate_scale';

    IF v_register_body IS NULL THEN
        RAISE EXCEPTION 'V19 自证失败(b): 读不到 register_scale 的函数体';
    END IF;
    IF v_deprecate_body IS NULL THEN
        RAISE EXCEPTION 'V19 自证失败(c): 读不到 deprecate_scale 的函数体';
    END IF;

    -- (b1)(b2)(b3) 逐函数检查三件套（建立上下文 / 自证 / 一致性守卫）
    --   🛑 逐行剥 -- 行注释后再匹配：PG 会把注释原样存进 prosrc，
    --      不剥的话"注释里提到"与"代码里调用"无法区分（V15 / V17 / V18 已踩过这条）。
    FOREACH v_mode IN ARRAY ARRAY['register_scale', 'deprecate_scale'] LOOP
        EXECUTE format(
            'SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace '
            'WHERE n.nspname = ''public'' AND p.proname = %L', v_mode) INTO v_one_body;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id'''
               AND regexp_replace(ln, '--.*$', '') ~ ',\s*true\s*\)'
        ) THEN
            RAISE EXCEPTION
                'V19 自证失败(b1): 函数 % 的函数体里【代码态】没有以 is_local := true 调用 '
                'set_config(app.tenant_id, ...)。这是本迁移最核心的不变量 —— '
                'scale 是 FORCE RLS + fail-closed，缺上下文时任何 INSERT 都会被 WITH CHECK 拒绝；'
                '而 is_local := false 会让上下文跨请求泄漏给连接池里的下一个请求。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'assert_tenant_context\s*\('
        ) THEN
            RAISE EXCEPTION
                'V19 自证失败(b2): 函数 % 没有调用 assert_tenant_context()。'
                '缺少它，"设了上下文"就退化成"我以为设好了"—— 本仓的判据一贯是'
                '"设置之后必须能断言它生效"，V15 / V17 / V18 的原语也是这么写的。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'current_setting\s*\(\s*''app\.tenant_id'''
        ) THEN
            RAISE EXCEPTION
                'V19 自证失败(b3): 函数 % 没有读 current_setting(app.tenant_id) 做一致性守卫。'
                '缺了它，调用方在一个"已设成租户 B"的事务里调用本函数传租户 A 时，'
                '上下文会被【静默改写成 A】—— 后面的写全落在 A 名下，而调用方以为是 B。'
                '见文件头「核心机制一」与 register_scale 的 (2)。', v_mode;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- 🛑🛑 2026-09-27（120 反向验证）· 把两条函数体【先剥掉行注释】再匹配
    --
    --   【为什么必须这一步 —— 不是风格，是判据的效力】
    --   PG 会把 `AS $tag$ ... $tag$` 之间的内容**原样**存进 pg_proc.prosrc，
    --   注释也在里面。V19 的函数体注释里【逐字】写着它自己要做的事：
    --        --     🛑 谓词里带 `status <> 'deprecated'` 是为了让这条 UPDATE 在并发下**正确**：
    --        --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的
    --   于是所有"函数体里出现过 X"形态的判据都可以被**注释**满足。
    --
    --   【实测证据（120 的 I(c4)）】
    --   注入"删掉回归语里那句 `AND status <> 'deprecated'`"（代码改动，语法仍合法）：
    --       修复前 ⇒ 自证打印"自证通过"    ← 静默假绿：判据被注释满足
    --       修复后 ⇒ 自证报 (c4)
    --   同一形态在 (b5) 上更隐蔽：`ON\s+CONFLICT` 在函数体里有 **2 处代码**
    --   + 1 处注释同时命中 ⇒ 连"哪一处满足了判据"都说不清。
    --
    --   【口径】
    --   · **语句形态判据必须在下述 v_code 上匹配**（= prosrc 逐行剥掉 `--` 之后的部分）；
    --   · 同时保留【锚点】：`INSERT INTO scale\M` / `UPDATE scale\M` 起锚、
    --     以 `;` 为界（`[^;]*` 不跨语句）—— 判据的适用范围 = 它的锚点范围。
    --   · (b1)(b2)(b3) 在执行期就做了逐行剥注释（见上方的 regexp_replace(ln,'--.*$','')），
    --     故它们本来就不受影响；本步只是把同一机制补到语句形态判据上。
    -- ------------------------------------------------------------------
    SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
      INTO v_register_code
      FROM regexp_split_to_table(v_register_body, E'\n') AS t(ln);

    SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
      INTO v_deprecate_code
      FROM regexp_split_to_table(v_deprecate_body, E'\n') AS t(ln);

    -- (b4) register_scale 的幂等写入形态
    --   🛑🛑 用 `\M` 而不是 `\b`：PostgreSQL 的 ARE 正则里 `\b` 是**退格字符**，
    --      不是 PCRE 的"词边界"。V17 / V18 已被这条坑过（正样本不匹配 ⇒ 自证的假红；
    --      更危险的是它的孪生形态：`DELETE FROM scale\M` 变成永假 ⇒ 守卫静默假绿）。
    --      ⇒ 凡"词边界"意图，本仓一律用 `\M`（词尾）。
    --   🛑 这里的 `\M` 尤其必要：`scale_item_bank` 以 "scale" 开头，
    --      不加词尾边界会让"改成往 scale_item_bank 写"这种偷换满足本断言。
    IF v_register_code !~ 'INSERT\s+INTO\s+scale\M' THEN
        RAISE EXCEPTION
            'V19 自证失败(b4): register_scale 的函数体（剥注释后的代码态）里没有 INSERT INTO scale —— '
            '本迁移存在的全部理由就是让 scale 有写入方；没有这一句，'
            'ProvisioningBoundaryGateTest 第②例会在下一个构建立刻报红（账本在骗人）。'
            '🛑 注意 `\M` 的用意：`scale_item_bank`（题库内容，另一本台账）以 "scale" 开头，'
            '不加词尾边界会让"写错表"这件事满足本断言 —— 而那恰好是本仓反复强调的'
            '"两本台账不得合并"。';
    END IF;
    -- (b5) register_scale 的 INSERT 必须带 ON CONFLICT
    --   🛑🛑 2026-09-27 由 120 反向验证的探针抓出：初版判据 `ON\s+CONFLICT`
    --      **既无锚点、也没剥注释**（与 V18 同形）⇒ 判据可被注释/其它语句满足。
    --   ⇒ 双重收紧：① 在【剥注释后的代码态】上匹配；② 从 `INSERT INTO scale` 起锚。
    IF v_register_code !~ 'INSERT\s+INTO\s+scale\M[^;]*ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V19 自证失败(b5): register_scale 的 INSERT 语句没有 ON CONFLICT —— '
            'PL/pgSQL 的 INSERT 没有 ELSE 分支：不加 ON CONFLICT 就只能靠 BEGIN/EXCEPTION '
            '捕获 23505，而"捕获到 conflict 就知道是重复建档"这条推理是错的'
            '（23505 也可能来自其它唯一约束）。故必须用库层的原子 upsert。'
            '🛑 判据在【剥注释后的代码态】上匹配，且从 `INSERT INTO scale` 起锚。';
    END IF;
    -- 🛑 推断目标必须逐字是【主键列】scale_id：本迁移的跨租户判定完全建立在
    --    "推断目标是那个不含 tenant_id 的全局主键"之上（实测 scale_pkey = PRIMARY KEY (scale_id)）。
    --    若有人改成 `ON CONFLICT (tenant_id, scale_id)`，冲突就不会再跨租户发生，
    --    而 (b8) 的判定分支会变成死代码却**不自知** —— 故在此钉住。
    IF v_register_code !~ 'ON\s+CONFLICT\s*\(\s*scale_id\s*\)' THEN
        RAISE EXCEPTION
            'V19 自证失败(b6): register_scale 的 ON CONFLICT 推断目标不是 `(scale_id)`。'
            '🛑 这不是风格问题：本迁移的【核心机制二】完全建立在"推断目标是全局主键"'
            '这个事实上（实测 scale_pkey = PRIMARY KEY (scale_id)，单列、不含 tenant_id）——'
            '正因为它不含 tenant_id，"另一租户占用同一 scale_id"才可能发生；'
            '也正因为会发生，函数才必须补上"冲突了但我看不见 ⇒ 是别人的"这条判定。'
            '若改成 (tenant_id, scale_id)，该冲突不可能发生，(b8) 的分支成为死代码，'
            '而自证 (e4) 会以【归因错误】的方式失败（报"竟然成功了"）。';
    END IF;

    -- (b7) 🛑 跨租户撞号的判定分支必须存在（与 V18 的 (b7) 同型）
    --   【判据必须带锚点：从那条 RAISE 起锚】
    --   🛑 为什么不写成"函数体里出现过'另一租户'四个字"：
    --      "文内任意位置出现过这句话"无法证明"这句话出现在该出现的那条语句里"。
    --      本文件的注释里（prosrc 中会保留）逐字写着"另一租户"，
    --      故不带锚点的判据会被**注释**满足 ⇒ 假绿。
    --      收紧方式：要求 `RAISE EXCEPTION` 与"另一租户"同处一条语句片段
    --      （`[^;]*` 不跨分号 —— RAISE 的参数之间不会出现分号）。
    IF v_register_code !~ 'RAISE\s+EXCEPTION[^;]*另一租户' THEN
        RAISE EXCEPTION
            'V19 自证失败(b7): register_scale 里没有"跨租户撞号"的 RAISE 分支。'
            '🛑 依据（实测）：scale_pkey = PRIMARY KEY (scale_id)，单列、不含 tenant_id ⇒'
            '`ON CONFLICT (scale_id) DO NOTHING` 在 scale_id 被【别的租户】占用时'
            '会【静默 DO NOTHING 且 ROW_COUNT=0】，而本租户里那一行**看不见**。'
            '若只按 ROW_COUNT 判定，函数会对一个手里一行都没有的租户返回 ALREADY_EXISTS —— '
            '调用方据此认为量表已就绪并提交基线评估，而 C2 的 scaleExists 仍为 false。'
            '即："返回值在撒谎"。故必须有这条分支，且它必须是 RAISE（不是返回值）。'
            '🛑 判据在【剥注释后的代码态】上匹配、且从 `RAISE EXCEPTION` 起锚：'
            '本函数体注释里逐字含"另一租户"，不带这两者的写法会被注释满足。';
    END IF;

    -- (b8) 判定必须真的区分"是我的"与"不是我的"：必须在本租户上下文里读一次 scale
    --   🛑 与 (b7) 配合才完整：(b7) 保证有 RAISE 分支，(b8) 保证它前面的判定读真的存在。
    --      只有 RAISE 而没有那次读，RAISE 就成了无条件抛错（会把正常幂等也打死）。
    IF v_register_code !~ 'FROM\s+scale\M[^;]*scale_id\s*=\s*p_scale_id' THEN
        RAISE EXCEPTION
            'V19 自证失败(b8): register_scale 里没有"在已自证的本租户上下文内按 scale_id 读一次"'
            '的语句（形如 `SELECT count(*) ... FROM scale WHERE scale_id = p_scale_id`）。'
            '🛑 缺了它，(b7) 的 RAISE 会变成**无条件抛错** —— 那会把"本租户的幂等重放"'
            '也一起打死（本该返回 ALREADY_EXISTS 的调用变成异常）。'
            '两段判定必须成对：先问"它在谁名下"，才能区分"我的 ⇒ 幂等"与"别人的 ⇒ 冲突"。';
    END IF;

    -- (b9) 建档路径的租户维度必须在 SQL 里可见（INSERT 列清单里含 tenant_id）
    --   🛑 从 `INSERT INTO scale` 起锚、以 `;` 为界，理由同 (b7)：
    --      本函数体的注释里也提到 tenant_id，不带锚点会被注释满足。
    IF v_register_code !~ 'INSERT\s+INTO\s+scale\M[^;]*tenant_id' THEN
        RAISE EXCEPTION
            'V19 自证失败(b9): register_scale 的【INSERT 语句自身】列清单里没有 tenant_id。'
            'scale 是 FORCE RLS（策略 = tenant_id 等于上下文），INSERT 不带 tenant_id '
            '会直接违反 NOT NULL；但更值得防的是"带了却带了别处来的值"——'
            '本断言保证租户维度在写入语句里显式可见，而不是从某个局部变量悄悄带进来。';
    END IF;

    -- (bb) 🛑🛑 V19 特有：版本冲突的判定分支必须存在
    --   【这是本迁移相对 V18 形态的唯一实质新增，故单独一条断言】
    --   🛑 判据在【剥注释后的代码态】上匹配、且从 `RAISE EXCEPTION` 起锚：
    --      本函数体的注释里逐字含"不可覆盖"，不带这两者的写法会被注释满足。
    --   🛑 与 (b12) 配合才完整：(b12) 保证有"读回本租户那一行的版本"这一动作。
    IF v_register_code !~ 'RAISE\s+EXCEPTION[^;]*不可覆盖' THEN
        RAISE EXCEPTION
            'V19 自证失败(bb): register_scale 里没有"版本冲突"的 RAISE 分支。'
            '🛑 依据（实测 + 纯逻辑推论）：scale_pkey = PRIMARY KEY (scale_id)（单列）⇒'
            '同一 scale_id 只可能有一行 ⇒ 库层**无法表达** V5 L235 那条'
            '「版本递增·不可覆盖」（uq_scale_id_version 永远不可能被违反，是死索引）。'
            '若没有这条分支，调用方传一个新版本号会拿到 ALREADY_EXISTS，'
            '而库里那行的 scale_version 还是旧值 ⇒ ledger.scaleVersion() 返回旧版本 ⇒'
            '被写入的基线评估与量表版本口径错配。这与 V18 的"返回值在撒谎"同族。'
            '🛑 判据在【剥注释后的代码态】上匹配、且从 `RAISE EXCEPTION` 起锚。';
    END IF;
    -- (b12) 版本冲突判定必须真的"读回了库里的版本"——否则 (bb) 的 RAISE 是无条件抛错
    --   🛑 这条守的是"返回值在撒谎"的孪生形态：若 RAISE 不看库里的实际版本，
    --      它会把**正确的幂等重放**也一起打死（传同一版本也被拒）。
    --      （(b8) 守的是"跨租户分支不能无条件抛"，(b12) 守的是"版本分支不能无条件抛"。）
    IF v_register_code !~ 'max\s*\(\s*scale_version\s*\)' THEN
        RAISE EXCEPTION
            'V19 自证失败(b12): register_scale 里没有"读回库中那一行的 scale_version"的动作'
            '（形如 `coalesce(max(scale_version), '''')`）。'
            '🛑 缺了它，(bb) 的 RAISE 会变成**无条件抛错** —— 那会把"传同一版本的幂等重放"'
            '也一起打死（本该返回 ALREADY_EXISTS 的调用变成异常），'
            '而那恰好是 C2 之外唯一合法的重复建档路径。'
            '两个分支必须成对：先读回实际版本，才能区分"同版 ⇒ 幂等"与"异版 ⇒ 冲突"。';
    END IF;

    -- (c1) deprecate_scale 必须是【状态迁移】而不是 DELETE
    --   🛑 用 `\M` 而非不加边界：`UPDATE\s+scale` 会匹配 `UPDATE scale_item_bank`
    --      ⇒ 若有人把废弃的实现改成去更新别的表，这条断言会假绿。
    IF v_deprecate_code !~ 'UPDATE\s+scale\M' THEN
        RAISE EXCEPTION
            'V19 自证失败(c1): deprecate_scale 的函数体（剥注释后的代码态）里没有 UPDATE scale —— '
            '废弃必须是状态迁移。三条理由（见函数注释）：'
            '① baseline_assessment 通过复合外键引用本表（NOT NULL），'
            '   删掉一行会让历史基线评估失去引用对象；'
            '② active 与 deprecated 的区分是"还能不能用于新评估"，是**状态**而不是"存在与否"；'
            '③ 量表是版本化的，"废弃"是版本的生命周期动作 —— '
            '删除会把"曾经有过这一版"一起抹掉，正好和版本化想解决的问题相反。';
    END IF;
    IF v_deprecate_code ~ 'DELETE\s+FROM\s+scale\M' THEN
        RAISE EXCEPTION
            'V19 自证失败(c2): deprecate_scale 的函数体里出现了 DELETE FROM scale —— '
            '废弃是状态迁移，永远不是删除（见 (c1) 的理由）。'
            '删除会让历史基线评估失去它引用的量表。';
    END IF;
    IF v_deprecate_code !~ 'SET\s+status\s*=' THEN
        RAISE EXCEPTION 'V19 自证失败(c3): deprecate_scale 没有 SET status = ...（废弃的核心动作）';
    END IF;
    -- (c4) 并发正确性：状态迁移的谓词里必须有 `status <> 'deprecated'`
    --   🛑🛑 2026-09-27 由 120 反向验证的受控注入抓出（与 V18 的 (c4) 同形）：
    --      初版判据 `status\s*<>\s*'deprecated'` **既无锚点、也没剥注释**，
    --      而函数体的注释里（prosrc 会原样保留）逐字含
    --          --     🛑 谓词里带 `status <> 'deprecated'` 是为了让这条 UPDATE 在并发下**正确**：
    --      ⇒ 把代码处那句谓词删掉之后，判据仍被**注释**满足 ⇒ 静默假绿、自证全绿。
    --   ⇒ 双重收紧：① 在【剥注释后的代码态】上匹配；② 从 `UPDATE scale` 起锚。
    IF v_deprecate_code !~ 'UPDATE\s+scale\M[^;]*status\s*<>\s*''deprecated''' THEN
        RAISE EXCEPTION
            'V19 自证失败(c4): deprecate_scale 的【状态迁移 UPDATE 语句自身】谓词里没有 `status <> ''deprecated''`。'
            '缺了它，两个并发废弃都会通过前置读，两次 UPDATE 都"成功"，'
            '第二次会把 updated_at 覆盖成更晚的时刻 —— 一次静默的数据回退。'
            '（与 V17 / V18 同族：保证必须由【库层条件】给出，不能依赖一次可能过期的读。）'
            '🛑 判据在【剥注释后的代码态】上匹配、且从 `UPDATE scale` 起锚：'
            '本函数体注释里逐字含同款字面量（120 的 I(c4) 已实测这条假绿）。';
    END IF;
    -- (c5) 废弃路径的租户维度同样必须可见，且**必须从 UPDATE 起锚**
    --   🛑 这正是 V17 的 (b7)/(c7) 在 2026-09-27 被 118 抓过的形态：
    --      deprecate_scale 的步骤 (4) 有一条
    --          SELECT status ... WHERE scale_id = p_scale_id AND tenant_id = p_tenant_id;
    --      不带锚点的判据（`AND\s+tenant_id\s*=\s*p_tenant_id`）会被它满足 ⇒
    --      "状态迁移 UPDATE 的租户维度被删掉"**完全不会被抓住**（假绿）。
    --      故本断言从 `UPDATE scale` 起锚、以 `;` 为界（`[^;]*` 不跨语句），
    --      且进一步在【剥注释后的代码态】上匹配。
    IF v_deprecate_code !~ 'UPDATE\s+scale\M[^;]*tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V19 自证失败(c5): deprecate_scale 的【状态迁移 UPDATE 语句自身】里缺少 `tenant_id = p_tenant_id`。'
            '只按 scale_id 写时 RLS 仍会兜住（不会真的跨租户），'
            '但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读 —— '
            '本仓把可读性当成安全属性的一部分，故要求租户维度在谓词里显式可见。'
            '🛑 本断言【从 UPDATE scale 起锚】：不带锚点的写法会被步骤 (4) 的 SELECT '
            '里那句同款子句满足（V17 已实测过这条假绿）。';
    END IF;

    -- ------------------------------------------------------------------
    -- (d) 迁移登记 + 权限
    -- ------------------------------------------------------------------
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V19';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V19 自证失败(d): schema_migration 中 V19 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- (d2) 用 has_function_privilege 而非读 proacl：前者把"角色继承 / PUBLIC 授权 /
    --      owner 隐含权限"等所有生效路径都算进去（V15 / V17 / V18 同款理由）。
    SELECT string_agg(x.sig, ', ' ORDER BY x.sig) INTO v_nopriv
    FROM (VALUES
              ('register_scale(uuid,uuid,text,text,text,jsonb)'),
              ('deprecate_scale(uuid,uuid)')) AS x(sig)
    WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');
    IF v_nopriv IS NOT NULL THEN
        RAISE EXCEPTION
            'V19 自证失败(d2): 当前角色 % 对以下函数没有 EXECUTE 权限 -> %。'
            '典型成因：环境做过 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固，'
            '而第 2 节的授权段未覆盖到 —— 那会表现为"建档时报 permission denied"，'
            '属运行期才发现的错误，故在迁移期断言。', current_user, v_nopriv;
    END IF;

    -- 🛑🛑 (d3) 上一条 (d2) 在【按本仓脚本建的库】上**恒真、判别力为零**。
    --   （V17 已被 118 的 I(d2) 抓过；V18 与 V19 沿用同一条加固。）
    --   成因：迁移是用应用角色本身跑的 ⇒ 两个函数的 **owner 就是 current_user**
    --        ⇒ owner 对自有对象的 EXECUTE 是**隐含**的，has_function_privilege 永远为 t。
    --   ⇒ 把"授权段真的执行了"变成可机械判定的事实：断言 proacl 里存在【显式 ACL 项】。
    --      `proacl IS NULL` 意味着"从未 GRANT 也从未 REVOKE"——那正是"授权段被摘掉"的形态。
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_noacl
    FROM (VALUES ('register_scale'), ('deprecate_scale')) AS x(f)
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
            'V19 自证失败(d3): 以下函数的 proacl 为 NULL —— 即【从未被 GRANT/REVOKE 过】：%。'
            '含义：第 2 节的授权段没有真正执行到这两个函数上。'
            '🛑 为什么 (d2) 单独不够：本迁移由应用角色自己执行 ⇒ 函数 owner = 调用者，'
            '而 owner 对自有函数的 EXECUTE 是隐含的 ⇒ (d2) 在该情形下恒为真。'
            '本断言改看【显式 ACL 项是否存在】，故对"授权段被摘掉 / 未生效"有反应。', v_noacl;
    END IF;

    -- ==================================================================
    -- (e) 行为验证 —— 全部在子事务里，块末整体撤销
    -- ==================================================================
    BEGIN
        -- 准备：两个租户（tenant 无 RLS，可直接写）
        INSERT INTO tenant (id, name, status)
        VALUES (v_ta, 'V19 量表建档探针 A', 'active'),
               (v_tb, 'V19 量表建档探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e1 建档 → CREATED，且行确实落库（6 列同时对上）
        -- ---------------------------------------------------------------
        v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v1', 'V19 探针量表', v_dims);

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V19 自证失败(e1): 首次建档返回 %（期望 CREATED）。'
                '若返回 ALREADY_EXISTS，说明该 scale_id 在库里已存在'
                '（探针 id 冲突 / 上次运行的残留）。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM scale
                        WHERE scale_id = v_scale_a1 AND tenant_id = v_ta
                          AND scale_type = 'primary' AND scale_version = 'v1'
                          AND name = 'V19 探针量表' AND status = 'active') THEN
            RAISE EXCEPTION
                'V19 自证失败(e1): register_scale 返回 CREATED，但库里查不到'
                '与之匹配的行（期望 tenant_id/scale_type/scale_version/name/status 全部对上）。'
                '🛑 五列同时断言是刻意的：一个"插了行但 scale_version 用错"的实现'
                '会让下游 baseline_assessment 的版本快照失真 —— 那是本迁移最该防的后果之一；'
                '而"scale_type 用错"会破坏双库严格隔离（config #39 / ISO-1~4）。';
        END IF;
        IF (SELECT dimension_set_json FROM scale WHERE scale_id = v_scale_a1) <> v_dims THEN
            RAISE EXCEPTION
                'V19 自证失败(e1): dimension_set_json 与传入的不一致 —— '
                '它决定"这个量表测哪几维"，而 baseline_assessment 的维度分要与之对齐。';
        END IF;

        -- ---------------------------------------------------------------
        -- e2 幂等：重放同一 (scale_id, scale_version) → ALREADY_EXISTS，且不得产生第二行
        --    🛑 这一条排除了"e1 成功只是因为函数没做任何检查"这种解释。
        -- ---------------------------------------------------------------
        v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v1', 'V19 探针量表', v_dims);

        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V19 自证失败(e2): 重放同一 (scale_id, 同版本) 返回 %（期望 ALREADY_EXISTS）。'
                '返回 CREATED 说明幂等失效；返回异常说明 (bb) 的版本判定写错了'
                '（它把"同版本"也当成了冲突 —— 那会把唯一合法的重复建档路径打死）。', v_mode;
        END IF;
        IF (SELECT count(*) FROM scale WHERE scale_id = v_scale_a1) <> 1 THEN
            RAISE EXCEPTION
                'V19 自证失败(e2): 重放之后同一 scale_id 的行数 ≠ 1。'
                '返回值说"已存在"，但行数不止一行 —— 返回值与行为不一致。';
        END IF;

        -- ---------------------------------------------------------------
        -- e3 🛑🛑 V19 特有：重放【不同】版本 → 必须 RAISE，且库中版本【不得被改动】
        --    🛑 这是本迁移相对 V18 形态的**唯一实质新增**。完整理由见文件头「核心机制三」。
        --    🛑 用内层 BEGIN/EXCEPTION 包住那条会抛的语句（同 e4 的理由）。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        BEGIN
            v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v2', 'V19 探针量表', v_dims);
            -- 走到这里说明没抛
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V19 自证失败(e3): 用【不同版本】(v2) 重放同一 scale_id 建档没有抛错'
                '（返回值 = %）。🛑 这正是"版本递增在库层无法表达"这件事的封堵点：'
                'scale_pkey 是单列 scale_id ⇒ 同一 scale_id 只可能有一行 ⇒'
                '若在版本不同时返回 ALREADY_EXISTS，调用方会以为"我要的那一版已存在"，'
                '而库里 scale_version 其实还是 v1 ⇒ ledger.scaleVersion() 返回旧版本 ⇒'
                '被写入的基线评估与量表版本口径错配。'
                '必须显式报错，而不是说"你已经有了"。', v_mode;
        END IF;
        IF v_msg IS NULL OR v_msg !~ '不可覆盖' THEN
            RAISE EXCEPTION
                'V19 自证失败(e3): 版本冲突虽然抛了错，但消息里没有"不可覆盖"（实际消息 = %）。'
                '🛑 断言消息内容是为了证明抛的是**这条**分支：'
                '一个"任何输入都抛错"的实现同样能让"抛了异常"成立。', coalesce(v_msg, '<NULL>');
        END IF;
        -- 🛑 核心：那次被拒的调用【不得改动库里的版本】
        IF (SELECT scale_version FROM scale WHERE scale_id = v_scale_a1) <> 'v1' THEN
            RAISE EXCEPTION
                'V19 自证失败(e3): 被拒绝的那次调用改动了库中的 scale_version（% → %）—— '
                '说明函数在冲突分支里做了 UPDATE，"不可覆盖"这条口径被静默违反了。',
                'v1', (SELECT scale_version FROM scale WHERE scale_id = v_scale_a1);
        END IF;
        IF (SELECT count(*) FROM scale WHERE scale_id = v_scale_a1) <> 1 THEN
            RAISE EXCEPTION
                'V19 自证失败(e3): 版本冲突之后该 scale_id 的行数 ≠ 1 —— '
                '说明函数在冲突分支里插了新行（那会绕过单列主键，或主键已被改形态）。';
        END IF;

        -- ---------------------------------------------------------------
        -- e4 🛑🛑 跨租户撞号（与 V18 的 e5 同型）
        --    租户 A 先占用 v_scale_shared；然后切到租户 B 用**同一 scale_id** 建档。
        --    必须 RAISE（不是 ALREADY_EXISTS），且消息里必须含"另一租户"。
        --    🛑 为什么必须断言"消息含另一租户"而不只是"抛了异常"：
        --       一个"任何输入都抛错"的实现同样能让"抛了"成立；
        --       断言消息内容证明抛的是**这条**分支，不是别的（尤其不是 e3 的版本分支）。
        -- ---------------------------------------------------------------
        v_mode := register_scale(v_ta, v_scale_shared, 'primary', 'v1', 'V19 撞号探针', v_dims);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V19 自证失败(e4): 准备阶段（租户 A 先占用 %）返回 %（期望 CREATED）',
                v_scale_shared, v_mode;
        END IF;

        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        BEGIN
            -- 🛑 这里必须传【与租户 A 相同的版本 v1】：
            --    否则若哪天判定顺序被改动，本用例会先命中 e3 的版本分支，
            --    于是"抛的是跨租户分支"这件事就证不出来了（一次归因错误的红）。
            --    用同一版本 ⇒ 版本比对必然通过 ⇒ 唯一能抛的只有跨租户分支。
            v_mode := register_scale(v_tb, v_scale_shared, 'calibration', 'v1', 'V19 撞号探针', v_dims);
            -- 走到这里说明没抛
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;
        -- 🛑 断言此前先读回状态：GET STACKED DIAGNOSTICS 的 v_state 只在 EXCEPTION 里被赋值，
        --    若上面没抛，它会是上一次循环留下的值 —— 故这里显式重置过（见三段 v_state := NULL）。
        IF v_state IS NOT NULL AND v_state <> 'P0001' THEN
            RAISE EXCEPTION
                'V19 自证失败(e4): 跨租户撞号的 SQLSTATE = %（期望 P0001 = raise_exception）。'
                '若它是 23505，那说明拦截发生在【主键冲突】这一层而不是函数里的显式 RAISE ——'
                '两者都 fail-closed，但"函数给了可读原因"这条设计就不成立了：'
                '调用方只会拿到一条约束名报错，无法区分"scale_id 传错了"与"量表被错误分发"。',
                v_state;
        END IF;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V19 自证失败(e4): 租户 B 用【租户 A 已占用的 scale_id】建档没有抛错'
                '（返回值 = %）。🛑 这是核心机制二的封堵点：'
                '`ON CONFLICT (scale_id) DO NOTHING` 在这里会【静默 DO NOTHING 且 ROW_COUNT=0】，'
                '若函数只按 ROW_COUNT 判定，就会对租户 B 返回 ALREADY_EXISTS —— '
                '而租户 B 里那一行**看不见**（RLS），即"返回值在撒谎"：'
                '调用方以为量表已就绪并提交基线评估，而 C2 的 scaleExists 仍为 false。'
                '必须在"冲突了但我看不见"时报错，而不是说"你已经有了"。', v_mode;
        END IF;
        IF v_msg IS NULL OR v_msg !~ '另一租户' THEN
            RAISE EXCEPTION
                'V19 自证失败(e4): 跨租户撞号虽然抛了错，但消息里没有"另一租户"（实际消息 = %）。'
                '🛑 断言消息内容是为了证明抛的是**这条**分支而不是 e3 的版本分支：'
                '两者都是 RAISE，只有消息能把它们区分开。', coalesce(v_msg, '<NULL>');
        END IF;
        -- 租户 B 里必须仍然一行关于这个 scale_id 的行都没有
        IF EXISTS (SELECT 1 FROM scale WHERE scale_id = v_scale_shared) THEN
            RAISE EXCEPTION
                'V19 自证失败(e4): 被拒绝的那次跨租户建档竟然在租户 B 里落库了（scale_id = %）',
                v_scale_shared;
        END IF;

        -- e5 对照：租户 B 用自己的 scale_id 建档必须成功
        --   🛑 没有这一条，一个"只要 scale_id 在别处存在就抛错"的实现也能过 e4。
        v_mode := register_scale(v_tb, v_scale_b2, 'calibration', 'v1', 'V19 租户B自用量表', v_dims);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V19 自证失败(e5-对照): 租户 B 用自己的 scale_id 建档返回 %（期望 CREATED）—— '
                '跨租户守卫把正常路径也打死了。', v_mode;
        END IF;

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e6 对照：同租户裸 INSERT（不同 scale_id）必须【成功】
        --    🛑 一个"拒绝一切"的实现同样能让 e3/e4 通过，故必须有本条对照。
        -- ---------------------------------------------------------------
        BEGIN
            INSERT INTO scale (scale_id, tenant_id, scale_type, scale_version, name,
                               dimension_set_json, status)
            VALUES (v_scale_a2, v_ta, 'primary', 'v1', 'V19 裸插对照', v_dims, 'active');
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                v_accepted := false;
        END;
        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V19 自证失败(e6): 同租户裸 INSERT 被误拒 —— 正常路径被挡住了。'
                '最可能的成因：RLS 策略或某条约束在函数之外也把正常写入拒了，'
                '那会让"建档通路"看起来可用而实际上只有函数能写。';
        END IF;

        -- ---------------------------------------------------------------
        -- e7 🛑🛑【C2 解锁的行为验证 —— 本迁移的验收口径】
        --    逐字跑 AssessmentLedger.scaleExists / scaleVersion 所发的两条 SQL。
        --    🛑 为什么必须单独有这一段：e1 证的是"函数写进去了"，
        --       而本迁移存在的**理由**是"C2 端点不再恒 422"。
        --       两者之间隔着一次读 —— 而"写进去了但读不到"（上下文/RLS/列名错）
        --       完全可能，且那种情况下 e1 仍会全绿。
        --    🛑 第一条与学生产代码逐字同款：
        --         SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid)
        --       关键时刻：它【不带】tenant_id 条件，隔离完全由 RLS 承担 ——
        --       故它同时验证了"上下文注入在 read path 上也生效"。
        -- ---------------------------------------------------------------
        SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = v_scale_a1) INTO v_exists;
        IF NOT v_exists THEN
            RAISE EXCEPTION
                'V19 自证失败(e7): 建档之后 scaleExists 形态的查询仍为 false —— '
                'C2 基线评估仍会恒返回 422/5001。🛑 本迁移存在的全部理由就是让这道检查为 true；'
                '若这里为 false，说明"函数写进去了但读路径取不到"（上下文/RLS/列名/主键列名错）—— '
                '而 e1 在那种情形下**仍会全绿**（它只读了一次同事务内的行）。';
        END IF;

        SELECT scale_version INTO v_ver FROM scale WHERE scale_id = v_scale_a1;
        IF v_ver IS NULL OR btrim(v_ver) = '' THEN
            RAISE EXCEPTION
                'V19 自证失败(e7): scaleVersion 形态的反查取到空值（实际 = %）。'
                '🛑 这条钉住的是 migratable 的根因：'
                'AssessmentService L178 的表达式是 `itemGroupId != null && scaleVersion != null`，'
                '而 scaleVersion 来自本列的反查。scale 零行时它恒 null ⇒ migratable 恒 false ⇒'
                '每一条落库的基线评估都永远标着"不可迁移"（V5 L355 是 NOT NULL）。'
                '本断言要求它现在【非空】—— 这就是本迁移的第二层价值。',
                coalesce(v_ver, '<NULL>');
        END IF;

        -- ---------------------------------------------------------------
        -- e8 跨租户 C2 场景：租户 B 上下文里看不见租户 A 的 scale
        --    🛑 这一条防 e7 退化成"任何 scale_id 都存在"的假绿 ——
        --      那只是把 C2 的缺口换了个方向（从"恒 422"变成"恒放行"），
        --      而后者更坏：它会让一个租户的基线评估引用另一个租户的量表口径。
        -- ---------------------------------------------------------------
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = v_scale_a1) INTO v_exists;
        IF v_exists THEN
            RAISE EXCEPTION
                'V19 自证失败(e8): 租户 B 上下文里竟能看见租户 A 的量表 —— '
                '这会让一个租户的基线评估引用另一个租户的量表口径（跨租户数据串联）。'
                '🛑 这条与 e7 必须成对：只有 e7（为 true）而没有 e8（为 false）时，'
                '一个"RLS 策略失效"的实现会全绿。';
        END IF;

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e9 废弃 → DEPRECATED；再废弃一次 → ALREADY_DEPRECATED（幂等），
        --    且 updated_at 不得被推后；废弃行仍占主键 ⇒ 重放建档必须 ALREADY_EXISTS
        -- ---------------------------------------------------------------
        SELECT updated_at INTO v_updated_first FROM scale WHERE scale_id = v_scale_a1;

        v_mode := deprecate_scale(v_ta, v_scale_a1);
        IF v_mode <> 'DEPRECATED' THEN
            RAISE EXCEPTION 'V19 自证失败(e9): 首次废弃返回 %（期望 DEPRECATED）', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM scale WHERE scale_id = v_scale_a1 AND status = 'deprecated') THEN
            RAISE EXCEPTION
                'V19 自证失败(e9): 废弃后 status 不是 deprecated（当前 = %）',
                (SELECT status FROM scale WHERE scale_id = v_scale_a1);
        END IF;
        IF (SELECT updated_at FROM scale WHERE scale_id = v_scale_a1) IS NULL THEN
            RAISE EXCEPTION
                'V19 自证失败(e9): 废弃后 updated_at 仍为 NULL —— '
                '废弃没有留下时间痕迹。"什么时候停用的"永久不可答。';
        END IF;

        v_mode := deprecate_scale(v_ta, v_scale_a1);
        IF v_mode <> 'ALREADY_DEPRECATED' THEN
            RAISE EXCEPTION 'V19 自证失败(e9): 重复废弃返回 %（期望 ALREADY_DEPRECATED，幂等）', v_mode;
        END IF;
        -- 🛑 这一条是 (c4) 那处谓词的行为验证：第二次调用不得把 updated_at 推后
        IF (SELECT updated_at FROM scale WHERE scale_id = v_scale_a1) <> v_updated_first THEN
            RAISE EXCEPTION
                'V19 自证失败(e9): 第二次废弃把 updated_at 改掉了（% → %）。'
                '这是一次静默的数据回退：一次"什么都不该改"的幂等重放，改动了停用时刻。'
                '成因通常是 UPDATE 的谓词里少了 `status <> ''deprecated''`（见自证 c4）。',
                v_updated_first, (SELECT updated_at FROM scale WHERE scale_id = v_scale_a1);
        END IF;

        -- e9 的后半：废弃行仍占主键 ⇒ 重放建档（同版本）必须 ALREADY_EXISTS
        --   🛑 这一条把"废弃是 UPDATE 而非 DELETE"的**可观测后果**钉住：
        --      若有人把 deprecate 改成 DELETE，这里会返回 CREATED，本断言立刻红。
        v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v1', 'V19 探针量表', v_dims);
        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V19 自证失败(e9): 对【已废弃】的 scale_id 重放建档返回 %（期望 ALREADY_EXISTS）。'
                '若返回 CREATED，说明废弃把行删掉了 —— 那就同时毁掉了两件事：'
                '① baseline_assessment 的历史引用对象（NOT NULL 复合外键）；'
                '② "废弃于何时"这个事实。废弃必须是状态迁移。', v_mode;
        END IF;

        -- ---------------------------------------------------------------
        -- e10 清场前的正向计数：证明确实有行写进去过
        --     （否则下面的 DELETE 是空操作，"清场成功"与"什么都没发生"同形）
        -- ---------------------------------------------------------------
        SELECT count(*) INTO v_cnt FROM scale WHERE tenant_id = v_ta;
        IF v_cnt < 3 THEN
            RAISE EXCEPTION
                'V19 自证失败(e10): 租户 A 的 scale 行数 = %（期望 ≥3：e1/e4准备/e6 写入的行）。'
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
        --       baseline_assessment 引用 scale ⇒ 若将来有人在本块里加了基线评估写入，
        --       必须把 baseline_assessment 排在 scale 之前。
        --       本迁移的探针没有写 baseline_assessment（e7 只读），故只需删 scale。
        -- ==============================================================
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        DELETE FROM scale WHERE tenant_id = v_ta;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        DELETE FROM scale WHERE tenant_id = v_tb;

        -- tenant 表无 RLS，可直接删
        DELETE FROM tenant WHERE id IN (v_ta, v_tb);

        -- (f2) 清场自证：逐租户设上下文检查（否则"删干净了"与"我看不见"同形）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        IF EXISTS (SELECT 1 FROM scale WHERE tenant_id = v_ta) THEN
            RAISE EXCEPTION
                'V19 自证失败(f2): 探针清场不彻底（租户 A 的 scale 仍有残留）。'
                '残留会在下一次运行时表现为"INSERT 撞主键"，而那时没人记得它来自本次探针。';
        END IF;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM scale WHERE tenant_id = v_tb) THEN
            RAISE EXCEPTION 'V19 自证失败(f2): 探针清场不彻底（租户 B 的 scale 仍有残留）';
        END IF;

        IF EXISTS (SELECT 1 FROM tenant WHERE id IN (v_ta, v_tb)) THEN
            RAISE EXCEPTION 'V19 自证失败(f2): 探针清场不彻底（探针租户仍有残留）';
        END IF;
    END;

    RAISE NOTICE 'V19 自证通过: 函数 2 / 登记 1 / 函数体断言全中 / 建档两态齐备 / 版本冲突 RAISE 且不改库 / 跨租户撞号 RAISE / C2 读路径解锁（scaleExists=true + scale_version 非空）/ 跨租户不可见 / 废弃幂等且不推后 updated_at / 废弃行占据主键 / 探针零残留';
END;
$v19_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   见文件头【回滚说明】。要点重述：
--     · 本迁移不建表、不写业务行 ⇒ 回滚无数据损失风险；
--     · 🛑 但**不要**用回滚来处理"scale 表里已经有量表档"这类情况 ——
--       那些行是数据。它们的存在意味着 C2 现在能工作了，
--       把函数删掉会让 baseline_assessment.scale_id 的外键重新失去引用来源。
--
-- 【本迁移登记进缺口清单的事实（不代拍口径，只如实记录）】
--   ① 「版本递增·不可覆盖」（V5 L235）在单列主键 scale_pkey 之下**在库层无法表达**：
--      uq_scale_id_version 永远不可能被违反（死索引）。本迁移用一条显式 RAISE
--      把这条口径变成可判定的行为，但**没有**改变 schema。
--      若要真正支持多版本，需改主键形态（契约 MAJOR 级变更）并回答
--      "历史 baseline_assessment 该指向哪一行"。
--   ② dimension_set_json 的「7 维枚举（与 PRD 附录 C.1.3 逐字同字面）」在库层
--      **没有 CHECK**，故本函数只要求"非空 JSON 对象"。取值集此刻只存在于 PRD 附录。
--   ③ reactivate_scale（复活已废弃量表）**未提供** ⇒ 'active' 在废弃之后不可达。
--      理由：在版本化语义下"复活哪一版、已有基线评估怎么办"含义不明，
--      需要在做之前先定义清楚（否则会变成一个没人负责的半成品）。
-- ============================================================================
ROLLBACK;
