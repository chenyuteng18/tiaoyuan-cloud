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
--     🛑 为什么用**小写** hex 作为判据：SHA-256 的十六进制表示有两种大小写写法，
--        而"同一份正文"在两种写法下会得到两个**字符串不同**的 hash ⇒
--        幂等对账（"这一版签过没有"）会因大小写而给出不同答案。
--        本仓既有 `DocFileService` 的比对用了 `equalsIgnoreCase`（宽容），
--        而**入库**统一下写（严格）—— 这两处**不是**不一致：
--        入库是一个写点（应规范），比对是多个读点（应宽容）。
--        本函数把规范点放在入库侧，并在自证 (e9) 钉住。
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

    IF p_rendered_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION
            'register_agreement: p_rendered_hash 形态非法（当前: %）。'
            '要求 = **64 位小写十六进制**（SHA-256 的规范写法）。'
            '🛑 三种常见非法形态与它们各自的真因：'
            '① 长度不对（32 位 = MD5 / 40 位 = SHA-1 / 128 位 = SHA-512）—— 算法用错；'
            '② 含大写字母 —— 同一份正文会有两个字符串不同的 hash，'
            '   幂等对账（"这一版签过没有"）会因大小写给出不同答案；'
            '③ 带 "sha256:" 前缀或 base64 —— 那是某种协议封装，不是裸摘要。'
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
--   本行的量法（python len() 对整条字符串计数，中文字符除外 —— 本行全 ASCII）+ 结果：
--        实测 = 228 字符（上限 256，余量 28）
--   🛑 为什么必须"量"而不是"目测"：这条字符串全是英文短横与括号，视觉上"看起来不长"，
--      而 V17 那条超了 102 个字符 —— 目测在这件事上不可靠。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V21', 'agreement offline signing primitive: register_agreement() + latest_agreement_of(). agreement had no writer, so PRD G1 signature-compliance could not be sourced. Four-party signer gate + clause/rendered snapshot+hash gate. Ops path, no HTTP mapping, no vendor callback (H1 stays frozen).')
ON CONFLICT (version) DO NOTHING;


-- __APPEND_ANCHOR_V21_SELFPROOF__