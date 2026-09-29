-- ============================================================================
-- V17 迁移: 手环绑定通路（客户级 band 台账的写入原语）
--
-- 【这个迁移为什么存在 —— 它补的是一条比 B-7 更严重的功能缺口】
--
--   `ProvisioningBoundaryGateTest` 把 `band` 登记在「未开通账」里已经很久了，
--   当时它只是一条"边界事实"。本轮（B-10）勘察后发现它不只是边界事实，
--   而是【会让两个契约端点在生产上必然失败】的缺陷：
--
--     ① `band_telemetry`      .device_id → REFERENCES band (band_id)   [V3 L38]
--     ② `band_sync_probe`     .device_id → REFERENCES band (band_id)   [V5 L904]
--     ③ `band_sync_log`       .device_id → REFERENCES band (band_id)   [V5 L951]
--     ④ `band_daily_coverage` .device_id → REFERENCES band (band_id)   [V5 L995]
--
--   这四张表的 device_id 都是 NOT NULL + 单列外键指向 `band`。而 `band`
--   在生产代码里【零写入方】（全仓 9 处 `INSERT INTO band` 全部在 src/test 的夹具里）。
--   ⇒ 一条 band 行都没有 ⇒ 任何写入这四张表的请求都会以
--     `23503 foreign_key_violation: band_telemetry_device_id_fkey ... 仍被引用`
--     的形式失败。
--   ⇒ 而 E1 `POST /band/sync-batches`（写 band_sync_log）与
--     E2 `POST /band/telemetry`（写 band_telemetry）正是**客户端的主数据通路**
--     （契约 `x-callable-roles: [client]`，E1/E2 是客户 App 上报的入口）。
--   ⇒ 结论：**测试全绿、门禁全 PASS、BUILD SUCCESS，而 E1/E2 在真库上是死的。**
--
--   🛑 为什么这个缺口能一直潜伏：因为每个 band 相关的测试类（BandEndpointsE2ETest /
--      BandIdempotencyConcurrencyTest / BandTelemetryEncryptionTest /
--      RlsBEntityIsolationTest / RlsCrossTenantReferenceGateTest）
--      都**自己在夹具里 INSERT 一行 band**。于是"带子从哪来"这个问题
--      在测试里永远有一个答案，而在生产里没有 —— 这正是本仓反复出现的
--      「单测全绿而功能不可用」形态的又一次复现。
--
-- 【为什么绑定通路【不】是 HTTP 端点（这是有意的形态，不是没做完）】
--   本轮逐条核对了契约 `openapi-v1.0.0.yaml` 的**全部 40 个 path**：
--     · 域 E（E1~E6）只有 sync-batches / telemetry / derived / available-dates /
--       sync-status —— **没有任何绑定 / 解绑端点**；
--     · D6 `POST /device-dispatches` 的 description 反而**逐字把它排除**了：
--       「🛑 本接口操作的是【门店级调理设备 device】（下行、可追责），
--         与【客户级手环 band】是两本台账、不得合并。」
--     · `/customers` 段（POST 建档 / GET 列表）也不产生 band。
--   ⇒ 与 B-7（组织开通）完全同型：这是**契约化决策**，不是遗漏。
--      给绑定加一个对外端点属契约 **MAJOR** 变更（需产品共签 + 定义
--      `x-callable-roles` —— 而"谁有权给客户绑带子"这件事，
--      现行契约的角色码里没有任何一项覆盖）。
--   ⇒ 故本迁移提供的是【数据库层原语】：两个幂等的 plpgsql 函数。
--      它可被运维 psql 直接调用，也可被应用内**不对外暴露**的
--      BandBindingService 调用（与 B-7 的 OrganizationProvisioningService 同款）。
--      两种用法共用同一份实现，"绑定"这条路径从此存在且只有一处定义。
--
-- 【🛑 核心机制一：绑定必须【自己】建立 RLS 上下文】
--   band 表（V2 L187-195）是 ENABLE + FORCE ROW LEVEL SECURITY，策略 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 WITH CHECK 为 false ⇒ INSERT **抛错**（不是静默零行 ——
--   静默零行发生在 SELECT 的 USING 上，这个区别很容易记反）。
--   故本函数把「建立上下文」+「自证上下文已生效」绑在写入之前，
--   使"没设上下文就绑带子"这件事在函数层面不可达。
--
-- 【🛑 核心机制二：部分唯一索引 `uq_band_active_customer` 决定幂等必须下沉到库层】
--   V2 L173-174 建的是【部分】唯一索引：
--       CREATE UNIQUE INDEX uq_band_active_customer
--           ON band (tenant_id, customer_id) WHERE status = 'active';
--   即"1 客户 : 1 **有效**手环"—— 历史（unbound）行可以有很多条。
--   这条索引带来一个**极易踩的并发缺陷**，本函数刻意用库层原子性绕开它：
--     · 错误形态（check-then-act）：先 `SELECT count(*) ... WHERE status='active'`
--       判断"该客户还没有有效带子"，再 INSERT ——
--       两个并发绑定会双双读到 0，然后其中一条撞唯一索引（23505）⇒ 500。
--       🛑 这与 E2 `upsertTelemetry` 在 2026-09-26 修掉的缺陷**完全同型**
--          （见 BandService「此处原为"先查后写"」一节），故此处不再重犯。
--     · 正确形态：单条 `INSERT ... ON CONFLICT (tenant_id, customer_id)
--       WHERE status = 'active' DO NOTHING` + `GET DIAGNOSTICS ROW_COUNT`——
--       判定与写入在同一条语句里完成，无窗口。
--       🛑 `ON CONFLICT` 的**推断目标必须带 WHERE**，否则 PG 无法确定
--          "要规避哪条唯一约束"（部分索引的推断语法要求逐字重述谓词）。
--          本文件的写法已在本机 PG 17.11 上实测通过（详见下方自证 (b)）。
--
-- 【🛑 核心机制三：为什么要挡"调用方已持有另一个租户上下文"】
--   见 `bind_band` 内 `v_ctx_before` 那段。一句话：pgbouncer / 连接池下
--   `set_config(..., is_local := true)` 是事务级设置，但它**不会**在函数入口
--   自动重置。若调用方在一个"已经设成租户 B"的事务里调用本函数传租户 A，
--   不做检查就会把上下文**静默改写成 A**：后面的写是 A 的，而调用方以为是 B 的。
--   本函数显式拒绝这种"覆盖既有上下文"的调用（fail-closed）。
--
-- 【幂等口径（与 V1~V16 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 函数天然支持 replace）
--   ② 写入侧 ON CONFLICT DO NOTHING + 返回三态
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（自证探针在一个子事务里跑，结束整体撤销）
--
-- 【🛑 本文件语句顺序不可调换：授权 → 登记 → 自证 → 完成】
--   自证 (d) 断言 schema_migration 里 V17 登记行数 = 1，故【登记必须排在自证之前】。
--   与 V15 同款（V14 把登记放在末尾是因为它的自证不读登记表）。
--   这类"顺序依赖"是文件内注释无法自保的，故在此逐字写明。
--
-- 【回滚说明】
--   本迁移只新增两个函数与一个索引（已存在则跳过）、不建表、不写业务行。
--     DROP FUNCTION IF EXISTS bind_band(uuid, uuid, uuid, text, text, date, boolean, text);
--     DROP FUNCTION IF EXISTS unbind_band(uuid, uuid, text, date);
--     DELETE FROM schema_migration     WHERE version = 'V17';
--     DELETE FROM flyway_schema_history WHERE version = '17';
--   🛑 已绑定的 band 行【不受回滚影响】—— 它们是数据不是 schema。
--      若要清理，须在租户上下文内按 FK 序删除（band_telemetry/band_sync_log/
--      band_sync_probe/band_daily_coverage → band → customer → tenant）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立，否则后面的一切都是空中楼阁
--
--   本迁移的全部设计建立在两个已存在的 schema 事实之上：
--     ① band 表是 FORCE RLS（否则"建立上下文"是多余动作，而本函数的主要价值就在这）；
--     ② 部分唯一索引 uq_band_active_customer 存在且谓词确实是 status = 'active'
--        （否则 ON CONFLICT 的推断目标会失效 —— 而它的失败形态是**运行期**的
--          "没有与 ON CONFLICT 说明匹配的唯一约束"，不是迁移期错误）。
--   ⇒ 这两条在此机械断言。它们保护的是"前提"，而前提失效时后面的自证
--      全都会以**误导性**的方式失败（例如 e1 报 'CREATED' 之外的第三个值），
--      故单独提前断言，让归因准确。
-- ============================================================================

DO
$v17_precond$
DECLARE
    v_rls    boolean;
    v_idx    text;
BEGIN
    SELECT c.relrowsecurity INTO v_rls
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'band';

    IF v_rls IS NULL THEN
        RAISE EXCEPTION 'V17 前置失败: 表 band 不存在。本迁移的手环绑定原语依赖它（V2 建表）。';
    END IF;
    IF NOT v_rls THEN
        RAISE EXCEPTION
            'V17 前置失败: 表 band 未启用 ROW LEVEL SECURITY。'
            '本迁移的核心价值之一是"绑定必须自己建立租户上下文"，'
            '若 band 没有 RLS，这个守卫就失去了守护对象 —— 不是"可以省略"，'
            '而是"前提被推翻了"，必须先解释清楚才能继续。';
    END IF;

    SELECT pg_get_indexdef(i.indexrelid) INTO v_idx
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'uq_band_active_customer';

    IF v_idx IS NULL THEN
        RAISE EXCEPTION
            'V17 前置失败: 部分唯一索引 uq_band_active_customer 不存在。'
            '本函数的幂等判定完全依赖它（ON CONFLICT (tenant_id, customer_id) '
            'WHERE status = ''active'' 的推断目标）。缺了它，INSERT 会**照常成功**'
            '而"1 客户 : 1 有效手环"这条业务不变量静默失效 —— '
            '失败形态不是报错，而是库里出现同一客户的第二支 active 带子。';
    END IF;
    IF v_idx NOT LIKE '%WHERE%active%' THEN
        RAISE EXCEPTION
            'V17 前置失败: uq_band_active_customer 的谓词变了（当前定义: %）。'
            '本函数写的是 ON CONFLICT (tenant_id, customer_id) WHERE status = ''active''，'
            'PG 要求推断谓词与被推断索引的谓词一致 —— 不一致时失败在**运行期**'
            '（"没有与 ON CONFLICT 说明匹配的唯一约束"），而不是这里。故在此提前拦。', v_idx;
    END IF;
END;
$v17_precond$;


-- ============================================================================
-- 第 1 节 · 绑定原语函数
-- ============================================================================

-- ---------------------------------------------------------------------------
-- bind_band(p_tenant_id, p_band_id, p_customer_id, p_vendor,
--           p_model, p_bound_at, p_rebind, p_unbind_reason)
--
--   【返回四态（逐字说清，调用方据此审计留痕）】
--     · 'CREATED'                          —— 本次调用建出了这一行（该客户此前无有效手环）
--     · 'REPLACED'                         —— 换机：本次调用先作废了原有的有效/暂停手环，
--                                             再建出新行（p_rebind = true 时才可能返回）
--     · 'ALREADY_BOUND'                    —— 幂等命中：这一支手环**自己**已经是 active 了
--                                             （重放同一请求，不是冲突）
--     · 'CUSTOMER_ALREADY_HAS_ACTIVE_BAND' —— 该客户已有**另一支**有效手环，且未要求换机。
--                                             🛑 这不是错误，是"业务不变量胜出"。
--                                             契约没有绑定端点 ⇒ 没有可对照的错误码；
--                                             故返回值（而非异常）是本原语与调用方之间的口径。
--   后三态都不算失败：一条 band 行都没有被重复创建。要区分它们，
--   是因为"有人在重放"（ALREADY_BOUND）与"有人在给已有带子的客户再绑一支"
--   （CUSTOMER_ALREADY_HAS_ACTIVE_BAND）在审计上是两件不同的事。
--
--   【为什么不抛异常而返回三态】
--     抛异常会让调用方必须用"捕获异常"来表达一个**正常结果**，而
--     `23505`（唯一冲突）这个异常在 PG 里同时可能来自其它唯一约束 ——
--     于是"捕获到 conflict 就知道是客户已有带子"这条推理是错的。
--     返回明确的三态字符串，让"发生了什么"不依赖对异常来源的猜测。
--
--   【为什么 p_rebind 是显式参数而不是自动推断】
--     "这个客户已有一支有效带子"之后该怎么办，有两个都合理的答案：
--     拒绝（防止误绑），或作废旧的再绑新的（换机）。
--     自动推断等于替业务拍板 —— 而在真实门店作业里，"
--     同一个人拿来第二支带子"既可能是换机，也可能是录错人。
--     故把选择权交给调用方，并在返回值里如实回显走了哪条路。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bind_band(
    p_tenant_id     uuid,
    p_band_id       uuid,
    p_customer_id   uuid,
    p_vendor        text,
    p_model         text    DEFAULT NULL,
    p_bound_at      date    DEFAULT NULL,
    p_rebind        boolean DEFAULT false,
    p_unbind_reason text    DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v17_bind$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_superseded int := 0;
    v_affected   int := 0;
    v_customer   int := 0;
BEGIN
    -- (1) 入参守卫 —— 与 V15 provision_tenant 同款：DDL 的 NOT NULL 挡不住空串，
    --     而空串在语义上同样无意义，且会静默流进台账。
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'bind_band: p_tenant_id 不可为空（band.tenant_id 是 NOT NULL）';
    END IF;
    IF p_band_id IS NULL THEN
        RAISE EXCEPTION 'bind_band: p_band_id 不可为空（band.band_id 是主键，'
            '且它是 band_telemetry/band_sync_log/band_sync_probe/band_daily_coverage '
            '四表 device_id 的引用目标）';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION 'bind_band: p_customer_id 不可为空（band.customer_id 是 NOT NULL）';
    END IF;
    IF p_vendor IS NULL OR btrim(p_vendor) = '' THEN
        -- vendor 在 DDL 里是 NOT NULL 且注释写明取值集"待厂商确认"，
        -- 故此处【不】冻结枚举（冻结一个明确未定的集合属代拍口径），只挡空值。
        RAISE EXCEPTION 'bind_band: p_vendor 不可为空串（band.vendor 是 NOT NULL 且有业务含义）';
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     见文件头「核心机制三」。这道守卫是 fail-closed：宁可让一次调用失败，
    --     也不要让"后面所有写入都去了另一个租户"这件事静默发生。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'bind_band: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, ..., true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '这是跨租户写错台账的最短路径。调用方须先结束当前事务（或使用全新连接）再绑定。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 与 V15 的 provision_tenant 同款：set_config 是**普通函数**，参数可绑定，
    --        不像 SET LOCAL 那样必须把值拼进 SQL 文本（无拼接、无注入面）。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'bind_band 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 🛑 客户必须**在本租户内**存在。
    --     它在库层已经由 V16 换成复合外键
    --         band_customer_id_fkey FOREIGN KEY (tenant_id, customer_id)
    --                                REFERENCES customer (tenant_id, id)
    --     故"跨租户引用客户"即使绕过本函数也会被数据库拒（23503）。
    --     但这里仍显式查一次，理由是**归因质量**：
    --       · 库层拒绝 ⇒ 一条 PostgreSQL 的 23503（调用方要自己解析约束名才知道原因）
    --       · 本检查   ⇒ 一条写明"客户 X 在租户 Y 内不存在"的业务错误
    --     两者都 fail-closed，区别只在诊断成本。本仓的判据一贯是"根因断言排在后果断言之前"，
    --     此处同理：先给可读的原因，再让数据库兜底。
    --     🛑 注意 SELECT 在 RLS 下的语义："查不到"可能是"不存在"，也可能是"看不见"
    --        （未设上下文 / 上下文不对）。此处上下文已在 (3) 建立并自证，故两种可能收敛。
    SELECT count(*) INTO v_customer
      FROM customer
     WHERE id = p_customer_id AND tenant_id = p_tenant_id;

    IF v_customer <> 1 THEN
        RAISE EXCEPTION
            'bind_band: 客户 % 在租户 % 内不存在（本租户可见行数 = %），拒绝绑定。'
            '🔴 若该客户确实存在，最常见的原因是租户上下文不对 —— '
            'band 的 RLS 是 fail-closed 的，上下文错了 SELECT 会静默返回 0 行，'
            '于是"不是我的客户"与"我看不见我的客户"给出同一个结论。',
            p_customer_id, p_tenant_id, v_customer;
    END IF;

    -- (5) 换机：作废该客户既有的有效 / 暂停手环
    --     🛑 为什么要显式写 `tenant_id = p_tenant_id AND customer_id = p_customer_id`
    --        而不是只按 customer_id：前者让这条 UPDATE 的**租户维度在 SQL 里可见**，
    --        与 RLS 策略形成两层（RLS 兜底、谓词自证意图）。少写 tenant_id 时
    --        RLS 仍会使它只命中本租户 —— 但那时"这条语句跨不跨租户"就只能靠推理，
    --        不能靠阅读。本仓一贯把可读性当成安全属性的一部分。
    --     🛑 status IN ('active','paused')：paused 是"合规摘除期"（住院/洗浴/桑拿，
    --        V2 L162），它仍是**有效手环**（暂停期从 A3 分母剔除，但带子还在客户身上）。
    --        故换机必须把它一起作废，否则部分唯一索引会因为旧带子仍是 'active'
    --        而挡住新带子 —— 那会表现为"换机失败"（返回值不是 REPLACED）。
    IF p_rebind THEN
        UPDATE band
           SET status        = 'unbound',
               unbound_at    = COALESCE(p_bound_at, CURRENT_DATE),
               unbind_reason = COALESCE(p_unbind_reason, '换机'),
               updated_at    = now()
         WHERE tenant_id   = p_tenant_id
           AND customer_id = p_customer_id
           AND status IN ('active', 'paused');
        GET DIAGNOSTICS v_superseded = ROW_COUNT;
    END IF;

    -- (6) 幂等写入 —— 判定与写入在同一条语句里完成（见文件头「核心机制二」）
    --     🛑 ON CONFLICT 的推断目标必须【带 WHERE】，且谓词与被推断索引逐字一致。
    --        写成 `ON CONFLICT (tenant_id, customer_id) DO NOTHING`（不带 WHERE）会报
    --        「没有与 ON CONFLICT 说明匹配的唯一约束」—— 因为 PG 无法判断你想规避
    --        uq_band_active_customer（部分索引）还是 band_pkey。
    --        🛑 这条约束的失败形态值得记住：它**不是迁移期错误**，
    --           而是**运行期**第一次调用 bind_band 时才炸。
    --           本文件的自证 (b) 会把它变成迁移期可判定的事实。
    INSERT INTO band (band_id, tenant_id, customer_id, vendor, model,
                      bound_at, status, created_by)
    VALUES (p_band_id, p_tenant_id, p_customer_id, p_vendor, p_model,
            COALESCE(p_bound_at, CURRENT_DATE), 'active', 'band-binding')
    ON CONFLICT (tenant_id, customer_id) WHERE status = 'active' DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- (7) 四态判定
    IF v_affected = 1 THEN
        -- 本行确实由本次调用插入
        RETURN CASE WHEN v_superseded > 0 THEN 'REPLACED' ELSE 'CREATED' END;
    END IF;

    -- 未插入：区分"重放同一支带子"与"客户已有另一支有效带子"
    -- 🛑 这里是一次【读】，不是判据 —— 判据是上面的 ROW_COUNT（库层原子结果）。
    --    读只是为了把"没有插入"这个事实翻译成可读的口径。
    --    读错的后果是返回值的**措辞**不准，而不是数据被改坏：这是刻意的取舍，
    --    因为在"判定必须原子"与"措辞必须精确"之间，前者不可让。
    IF EXISTS (SELECT 1 FROM band
                WHERE band_id = p_band_id AND status = 'active') THEN
        RETURN 'ALREADY_BOUND';
    END IF;

    RETURN 'CUSTOMER_ALREADY_HAS_ACTIVE_BAND';
END;
$v17_bind$;


-- ---------------------------------------------------------------------------
-- unbind_band(p_tenant_id, p_band_id, p_reason, p_unbound_at)
--
--   【返回三态】
--     · 'UNBOUND'         —— 本次调用把它从 active/paused 置为 unbound
--     · 'ALREADY_UNBOUND' —— 它已经是 unbound（重放，幂等）
--     · 'NOT_FOUND'       —— 本租户内看不到这一行
--        🛑 措辞刻意是 NOT_FOUND 而不是 "NOT_EXISTS"：在 FORCE RLS 下
--           "不存在"与"存在但当前上下文看不见"**给出同一个结果**。
--           用"不存在"这个词会把后一种情形说成事实，属对调用方的误导。
--           调用方看到 NOT_FOUND 时应先复核租户上下文，再去怀疑数据缺失。
--
--   【🛑 为什么解绑是 UPDATE 而不是 DELETE】
--     V2 L157-158 的注释已给出答案：unbind_reason 的存在是为了
--     "区分【主动放弃】与【技术性缺失】(data-spec M6)"；
--     unbound_at 是"应戴天"分母的**终点**（§4.3 分母护栏：A3 分母 = 台账应戴天）。
--     ⇒ 台账必须是**历史完整**的：删掉一行就等于抹掉一段应戴天，
--       而 A3 这个指标是退款资格的输入之一。故解绑是状态迁移，永远不是删除。
--     🛑 副作用是部分唯一索引也因此"只约束 active"：历史行可以任意多条，
--        这正是 `uq_band_active_customer` 的 `WHERE status = 'active'` 的用意。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION unbind_band(
    p_tenant_id  uuid,
    p_band_id    uuid,
    p_reason     text DEFAULT NULL,
    p_unbound_at date DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v17_unbind$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_cur_status text;
    v_rows       int := 0;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'unbind_band: p_tenant_id 不可为空（band.tenant_id 是 NOT NULL）';
    END IF;
    IF p_band_id IS NULL THEN
        RAISE EXCEPTION 'unbind_band: p_band_id 不可为空（band.band_id 是主键）';
    END IF;

    -- (2) 上下文一致性守卫（与 bind_band 同款，逐字同理由）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'unbind_band: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '同 bind_band 的理由：本函数会覆盖它，从而让""后续写入去了另一个租户""静默发生。'
            '解绑尤其危险 —— 它改的是台账历史，而台账是 A3 分母与退款资格的输入。',
            v_ctx_before, p_tenant_id::text;
    END IF;

    -- (3) 建立并自证上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'unbind_band 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 读当前状态（在已自证的上下文内）
    SELECT status INTO v_cur_status
      FROM band
     WHERE band_id = p_band_id AND tenant_id = p_tenant_id;

    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_cur_status = 'unbound' THEN
        RETURN 'ALREADY_UNBOUND';
    END IF;

    -- (5) 状态迁移
    --     🛑 谓词里带 `status <> 'unbound'` 是为了让这条 UPDATE 在并发下**正确**：
    --        两个并发解绑都会通过第 (4) 步的读（都读到 active），
    --        若 UPDATE 不带这个条件，两次都会"成功"，而第二次会把
    --        unbind_reason **覆盖**掉第一次填的理由 —— 一次静默的数据回退。
    --        带上它之后，第二次的 ROW_COUNT = 0 ⇒ 落到 ALREADY_UNBOUND。
    --        （这与 E2 修掉的"看起来不覆盖、实际没实现不覆盖"是同一类教训：
    --          保证必须由【库层条件】给出，不能依赖一次可能过期的读。）
    UPDATE band
       SET status        = 'unbound',
           unbound_at    = COALESCE(p_unbound_at, CURRENT_DATE),
           unbind_reason = p_reason,
           updated_at    = now()
     WHERE band_id  = p_band_id
       AND tenant_id = p_tenant_id
       AND status <> 'unbound';

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 1 THEN
        RETURN 'UNBOUND';
    END IF;
    RETURN 'ALREADY_UNBOUND';
END;
$v17_unbind$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15 第 2 节同款，理由逐字相同（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，
--       故本节的 GRANT 在常规环境里是 no-op；写它是"显式优于隐式"，
--       以及在"生产环境 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固"后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d) 用 has_function_privilege 机械断言。
-- ============================================================================

DO
$v17_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'bind_band') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION bind_band(uuid, uuid, uuid, text, text, date, boolean, text) TO %I',
            v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'unbind_band') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION unbind_band(uuid, uuid, text, date) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V17 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v17_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是第 2 次踩它：
--        V15 的本节注释里【逐字】写着这条教训
--        （逐字：「本行由一次真实的 psql 试跑抓出（初版 263 字符 →
--          报「对于可变字符类型来说，值太长了(256)」）」），
--        而 V17 的初稿是 358 字符 —— 又炸了一次，原文一模一样。
--      ⇒ 这说明"把教训写进上一个文件的注释里"对下一个人【不生效】，
--        因为他不一定会读那个文件的注释。故此处把教训升级成一个可机械核对的数字：
--        🛑 **description 必须先量长度再写**，上限 256。
--          本行当前长度 245（量法：把整条字符串当 ASCII 数一遍）。
--    为什么是"量"而不是"目测"：这条字符串里全是英文短横与箭头，
--    视觉上"看起来不长"，而它超了 102 个字符 —— 目测在这件事上不可靠。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V17', 'band binding primitive: bind_band() + unbind_band(). band had no production writer, so E1 POST /band/sync-batches and E2 POST /band/telemetry failed with 23503 (4 tables FK device_id -> band.band_id). Ops path; contract has no bind endpoint.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   (a) 两个函数必须真的存在
--   (b) bind_band 的函数体必须含【四件套】—— 每一件都对应一类已被实测踩过的缺陷：
--        ① 建立上下文          set_config('app.tenant_id', ...)
--        ② 上下文自证          assert_tenant_context()
--        ③ 上下文一致性守卫    current_setting('app.tenant_id', true)
--        ④ 幂等写入的原子形态  INSERT INTO band ... ON CONFLICT (...) WHERE status = 'active'
--           🛑 ④ 的 OR 谓词的存在本身就值得断言：把它去掉之后
--              迁移仍能跑通、函数仍能创建，而**第一次调用**才会报
--              「没有与 ON CONFLICT 说明匹配的唯一约束」。
--              把它变成迁移期事实，是为了不让它成为上线后才发现的运行期错误。
--   (c) unbind_band 的函数体必须含 ①②③ 与"状态迁移而非删除"的证据
--        （UPDATE band ... SET status = 'unbound' ... unbind_reason ... unbound_at）
--   (d) 迁移登记 + 当前角色对两个函数真的有 EXECUTE 权限
--   (e) 🛑 行为验证 —— 本迁移最重要的一条。前四条都只检查"函数体长什么样"，
--       而"函数体看着对、跑起来不对"是本仓反复出现过的形态（自证必须证明**行为**）。
--       共 8 段：
--         e1 绑定 → CREATED
--         e2 重放同一支带子 → ALREADY_BOUND（幂等）
--         e3 给已有有效带子的客户再绑另一支（不换机）→ CUSTOMER_ALREADY_HAS_ACTIVE_BAND
--             且必须证明：新带子【没有】落库、旧带子【仍】是 active
--         e4 换机（p_rebind = true）→ REPLACED，且旧带子变 unbound、新带子 active
--         e5 🛑 跨租户引用客户：**绕过函数**直接裸 INSERT（band.tenant_id = A，
--            customer_id = B 的客户）→ 必须被数据库拒，且理由必须是 23503
--            而不是 42501（RLS 拒绝是错的原因 —— 这处要测的是 V16 的复合外键，
--            不是 RLS。两者都"拒绝"，但证明的是完全不同的东西）。
--         e6 对照：同租户裸 INSERT → 必须**成功**（防"把正常路径一起打死"）
--         e7 解绑 → UNBOUND；再解一次 → ALREADY_UNBOUND（幂等），
--            且 unbind_reason 不得被第二次调用覆盖
--         e8 解绑后可重新绑定 → CREATED（证明部分唯一索引不阻挡历史行）
--   (f) 探针清场 + 清场自证（一行业务数据都不许留下）
--
--   🛑 全部探针在一个 BEGIN ... EXCEPTION 子事务里跑，块结束时整体撤销 ——
--      迁移不得留下任何业务数据（V1~V16 一致的纪律）。
--      子事务的 EXCEPTION 会建立 savepoint，故内部 INSERT 无论成功与否都在块末撤销。
-- ============================================================================

DO
$v17_guard$
DECLARE
    v_bind_body   text;
    v_unbind_body text;
    v_all         text;
    -- 🛑 类型必须是 text 而不是 text[]：string_agg() 返回 text，而
    --    `INTO v_missing` 若目标是 text[] 则会追加一次隐式 cast —— 空结果时
    --    NULL 能过，但【只有一个函数缺失】时报
    --      「错误: 有缺陷的数组常量:"bind_band"  DETAIL: 数组值必须以 "{" 或者维度信息开始」
    --    （2026-09-27 由 118 反向验证的 I(a) 抓出，本仓新增一条教训）。
    --    这正是"判据本身也会有缺陷"的形态：它想报 (a)，却报了一条与 (a) 无关的错误。
    --    🛑 另注：初版还写了 `:= ARRAY[]::text[]` 默认值 —— 那更坏，
    --       它让 (a) 在 NULL 情形下退化成"空数组 ⇒ 永远不报错"，同时不解决单缺。
    v_missing     text;
    v_cnt         int;
    v_reg         int;
    v_nopriv      text;
    v_noacl       text;
    -- ---- 探针租户 / 客户 / 手环（17 前缀，与 V16 的 16 前缀互不重叠）----
    v_ta        uuid := '17000000-0000-0000-0000-00000000000a';
    v_tb        uuid := '17000000-0000-0000-0000-00000000000b';
    v_cust_a    uuid := '17000000-0000-0000-0000-0000000000c1';
    v_cust_b    uuid := '17000000-0000-0000-0000-0000000000c2';
    v_band_a1   uuid := '17000000-0000-0000-0000-0000000000b1';  -- e1/e2: 绑定
    v_band_a2   uuid := '17000000-0000-0000-0000-0000000000b2';  -- e3: 尝试失败 → e8: 重新绑定
    v_band_a3   uuid := '17000000-0000-0000-0000-0000000000b3';  -- e4: 换机目标 → e7: 解绑
    v_band_a4   uuid := '17000000-0000-0000-0000-0000000000b4';  -- e6: 同租户裸 INSERT 对照
    v_band_b1   uuid := '17000000-0000-0000-0000-0000000000b5';  -- e5: 跨租户裸 INSERT（应被拒）
    v_mode      text;
    v_rejected  boolean := false;
    v_accepted  boolean := false;
BEGIN
    -- ------------------------------------------------------------------
    -- (a) 函数存在
    --   🛑 判空用 `IS NOT NULL`（string_agg 在"无缺失行"时返回 NULL），
    --      不是 `array_length(...) > 0` —— 后者是初版的写法，见下方 v_missing 的说明。
    -- ------------------------------------------------------------------
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_missing
    FROM (VALUES ('bind_band'), ('unbind_band')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V17 自证失败(a): 以下函数未创建成功 -> %', v_missing;
    END IF;

    -- ------------------------------------------------------------------
    -- (b)(c) 函数体断言（🛑 只读 prosrc = 函数体原文，不读整个文件）
    --       读整个文件会把本注释块里的示例文本当成"代码里存在"⇒ 假绿。
    --       本文件的注释里【逐字】写着 set_config / assert_tenant_context /
    --       INSERT INTO band / ON CONFLICT / WHERE status = 'active' ——
    --       这正是必须只读 prosrc 的原因。
    -- ------------------------------------------------------------------
    SELECT p.prosrc INTO v_bind_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'bind_band';

    SELECT p.prosrc INTO v_unbind_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'unbind_band';

    IF v_bind_body IS NULL THEN
        RAISE EXCEPTION 'V17 自证失败(b): 读不到 bind_band 的函数体';
    END IF;
    IF v_unbind_body IS NULL THEN
        RAISE EXCEPTION 'V17 自证失败(c): 读不到 unbind_band 的函数体';
    END IF;

    v_all := v_bind_body || E'\n' || v_unbind_body;

    -- (b1) 两个函数各自都必须在【代码态】调用 set_config('app.tenant_id', ..., true)
    --      🛑 逐行剥 -- 行注释后再匹配：PG 会把注释原样存进 prosrc，
    --         不剥的话"注释里提到"与"代码里调用"无法区分（V15 已踩过这条）。
    FOREACH v_mode IN ARRAY ARRAY['bind_band', 'unbind_band'] LOOP
        EXECUTE format(
            'SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace '
            'WHERE n.nspname = ''public'' AND p.proname = %L', v_mode) INTO v_all;
        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_all, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id'''
               AND regexp_replace(ln, '--.*$', '') ~ ',\s*true\s*\)'
        ) THEN
            RAISE EXCEPTION
                'V17 自证失败(b1): 函数 % 的函数体里【代码态】没有以 is_local := true 调用 '
                'set_config(app.tenant_id, ...)。这是本迁移最核心的不变量 —— '
                'band 是 FORCE RLS + fail-closed，缺上下文时任何 INSERT 都会被 WITH CHECK 拒绝；'
                '而 is_local := false 会让上下文跨请求泄漏给连接池里的下一个请求。', v_mode;
        END IF;

        -- (b2) 上下文自证
        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_all, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'assert_tenant_context\s*\('
        ) THEN
            RAISE EXCEPTION
                'V17 自证失败(b2): 函数 % 没有调用 assert_tenant_context()。'
                '缺少它，"设了上下文"就退化成"我以为设好了"—— 本仓的判据一贯是'
                '"设置之后必须能断言它生效"，V15 的两个原语也是这么写的。', v_mode;
        END IF;

        -- (b3) 上下文一致性守卫（读 current_setting 并比较）
        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_all, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'current_setting\s*\(\s*''app\.tenant_id'''
        ) THEN
            RAISE EXCEPTION
                'V17 自证失败(b3): 函数 % 没有读 current_setting(app.tenant_id) 做一致性守卫。'
                '缺了它，调用方在一个"已设成租户 B"的事务里调用本函数传租户 A 时，'
                '上下文会被【静默改写成 A】—— 后面的写全落在 A 名下，而调用方以为是 B。'
                '见文件头「核心机制三」。', v_mode;
        END IF;
    END LOOP;

    -- (b4) bind_band 的幂等写入形态：INSERT INTO band + ON CONFLICT + 部分索引谓词
    --   🛑🛑 【为什么是 `\M` 而不是 `\b`】（2026-09-27 首次试跑抓出，本仓新增一条教训）
    --      PostgreSQL 的 ARE 正则里 `\b` 是**退格字符（U+0008）**，
    --      不是 PCRE 的"词边界"！初版写的是
    --          v_bind_body !~ 'INSERT\s+INTO\s+band\b'
    --      实测结果（本机 PG 17.11）：
    --          'INSERT INTO band (x)'           ~ '...band\b'  ->  f   ← 应当为 t
    --          'INSERT INTO band (x)'           ~ '...band\M'  ->  t   ← 正确写法
    --          'INSERT INTO band_telemetry (x)' ~ '...band\M'  ->  f   ← 负样本正确被排除
    --      ⇒ 用 `\b` 时【正样本不匹配】：本断言会以"函数体里没有 INSERT INTO band"
    --        报红，而函数体里明明有 —— 一条自证的**假红**。
    --      🛑🛑 更危险的是它的**孪生形态**：下面 (c2) 的 `DELETE FROM band\b`
    --        因此变成"永假" ⇒ `IF v_unbind_body ~ ...` **永不成立** ⇒
    --        那条"解绑不得删除"的守卫**静默假绿**。假红会被人看见并修掉，
    --        假绿不会 —— 这正是本仓一贯区分这两者的理由。
    --      ⇒ 故凡"词边界"意图，本仓一律用 `\M`（词尾）。`\y` 可作等价替代，
    --        但 `\M` 语义更窄、更贴近"表名到此结束"这个意图。
    IF v_bind_body !~ 'INSERT\s+INTO\s+band\M' THEN
        RAISE EXCEPTION
            'V17 自证失败(b4): bind_band 的函数体里没有 INSERT INTO band —— '
            '本迁移存在的全部理由就是让 band 有写入方；没有这一句，'
            'ProvisioningBoundaryGateTest 第③例会在下一个构建立刻报红（账本在骗人）。';
    END IF;
    IF v_bind_body !~ 'ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V17 自证失败(b5): bind_band 的 INSERT 没有 ON CONFLICT —— '
            'PL/pgSQL 的 INSERT 没有 ELSE 分支：不加 ON CONFLICT 就只能靠 BEGIN/EXCEPTION 捕获 23505，'
            '而"捕获到 conflict 就知道是客户已有带子"这条推理是错的（23505 可能来自任何唯一约束，'
            '例如 band_pkey）。故必须用库层的原子 upsert，而不是异常捕获。';
    END IF;
    -- 🛑 这一条是本迁移里最"脆"的一处：ON CONFLICT 的推断目标必须逐字带
    --    `WHERE status = 'active'`，否则 PG 在**运行期**报
    --    「没有与 ON CONFLICT 说明匹配的唯一约束」。
    --    断言它，是把一处运行期错误前移到迁移期。
    IF v_bind_body !~ 'ON\s+CONFLICT\s*\([^)]*\)\s+WHERE\s+status\s*=\s*''active''' THEN
        RAISE EXCEPTION
            'V17 自证失败(b6): bind_band 的 ON CONFLICT 推断目标缺少 `WHERE status = ''active''` 谓词。'
            '部分唯一索引 uq_band_active_customer 的推断语法要求逐字重述它的谓词 —— '
            '写成 `ON CONFLICT (tenant_id, customer_id) DO NOTHING`（不带 WHERE）'
            '会以「没有与 ON CONFLICT 说明匹配的唯一约束」失败，'
            '而那是【运行期】第一次调用时才发现的错误。故在此拦住。';
    END IF;

    -- (b7) 换机路径的租户维度必须在 SQL 里可见（见函数内 (5) 的说明）
    --   🛑🛑 【为什么必须钉住 `UPDATE band`，而不是只找 `AND tenant_id = p_tenant_id`】
    --      （2026-09-27 由 118 反向验证的预探针抓出，本仓新增一条教训）
    --      初版正则是不带锚点的：
    --          v_bind_body !~ 'AND\s+tenant_id\s*=\s*p_tenant_id'
    --      而 bind_band 的【步骤 (4) SELECT】里就有逐字同样的子句：
    --          SELECT count(*) INTO v_customer FROM customer
    --           WHERE id = p_customer_id AND tenant_id = p_tenant_id;
    --      ⇒ 实测（FALSE 才报错，故 f = 没抓住）：
    --          · 现状                                    → t（不报错，正确）
    --          · 单独删掉换机 UPDATE 的租户维度            → 旧判据 t ⇒ 【没抓住】假绿
    --          · 单独删掉换机 UPDATE 的租户维度            → 新判据 f ⇒ 抓住了
    --      ⇒ 这正是本仓那条反复出现的形态：**判据的适用范围 = 它的锚点范围**。
    --        一个"文内任意位置出现过这句话"的断言，无法证明"这句话出现在该出现的那条语句里"。
    --        收紧方式：从 `UPDATE band` 起锚、以 `;` 为界（`[^;]*` 不跨语句），
    --        要求该 UPDATE【自身】的谓词里含租户维度。
    IF v_bind_body !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V17 自证失败(b7): bind_band 的【换机 UPDATE 语句自身】里没有 `tenant_id = p_tenant_id`。'
            '只按 customer_id 写时 RLS 仍会兜住（不会真的跨租户），'
            '但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读 —— '
            '本仓把可读性当成安全属性的一部分，故要求租户维度在谓词里显式可见。'
            '🛑 注意本断言【从 UPDATE band 起锚】：不带锚点的写法会被步骤 (4) 的 SELECT '
            '里那句同款子句满足，从而对"换机语句被删掉租户维度"完全无反应（118 已实测）。';
    END IF;

    -- (c1) unbind_band 必须是【状态迁移】而不是 DELETE
    --   🛑 同样用 `\M` 而非不加边界：`UPDATE\s+band` 会匹配 `UPDATE band_sync_log`
    --      ⇒ 若有人把解绑的实现改成去更新别的 band_* 表，这条断言会假绿。
    IF v_unbind_body !~ 'UPDATE\s+band\M' THEN
        RAISE EXCEPTION
            'V17 自证失败(c1): unbind_band 的函数体里没有 UPDATE band —— '
            '解绑必须是状态迁移。V2 L157-158 已逐字说明 unbind_reason 的用途是'
            '"区分主动放弃与技术性缺失 (data-spec M6)"，而 unbound_at 是'
            '"应戴天"分母的终点（§4.3 分母护栏：A3 分母 = 台账应戴天）。'
            '删掉一行就等于抹掉一段应戴天，而 A3 是退款资格的输入之一。';
    END IF;
    IF v_unbind_body ~ 'DELETE\s+FROM\s+band\M' THEN
        RAISE EXCEPTION
            'V17 自证失败(c2): unbind_band 的函数体里出现了 DELETE FROM band —— '
            '解绑是状态迁移，永远不是删除（见 (c1) 的理由）。'
            '台账必须历史完整：删除会让 A3 分母变小，而 A3 决定退款资格，'
            '即"修一条数据"会静默变成"改一个客户的退款资格"。';
    END IF;
    IF v_unbind_body !~ 'SET\s+status\s*=' THEN
        RAISE EXCEPTION 'V17 自证失败(c3): unbind_band 没有 SET status = ...（解绑的核心动作）';
    END IF;
    IF v_unbind_body !~ 'unbind_reason' THEN
        RAISE EXCEPTION
            'V17 自证失败(c4): unbind_band 没有写 unbind_reason —— '
            '它是"主动放弃 / 换机 / 设备损坏 / 其他"（V2 L158 的 CHECK 冻结值）的唯一载体，'
            '不写它则"为什么这支带子不再戴了"永久不可答。';
    END IF;
    IF v_unbind_body !~ 'unbound_at' THEN
        RAISE EXCEPTION
            'V17 自证失败(c5): unbind_band 没有写 unbound_at —— 它是应戴天分母的终点，'
            '缺了它 A3 会把"解绑后到现在"也计入应戴天，指标会系统性地偏高。';
    END IF;
    -- (c6) 并发正确性：状态迁移的谓词里必须有 `status <> 'unbound'`
    IF v_unbind_body !~ 'status\s*<>\s*''unbound''' THEN
        RAISE EXCEPTION
            'V17 自证失败(c6): unbind_band 的 UPDATE 谓词里没有 `status <> ''unbound''`。'
            '缺了它，两个并发解绑都会通过前置读，两次 UPDATE 都"成功"，'
            '而第二次会把 unbind_reason 覆盖掉第一次填的理由 —— 一次静默的数据回退。'
            '这与 E2 在 2026-09-26 修掉的"看起来不覆盖、实际没覆盖"是同一类教训：'
            '保证必须由【库层条件】给出，不能依赖一次可能过期的读。';
    END IF;
    -- (c7) 解绑路径的租户维度同样必须可见
    --   🛑 与 (b7) 同一处教训、同一处收紧：必须从 `UPDATE band` 起锚。
    --      unbind_band 的步骤 (4) 同样有一条
    --          SELECT status ... WHERE band_id = p_band_id AND tenant_id = p_tenant_id;
    --      ⇒ 不带锚点时，"状态迁移 UPDATE 的租户维度被删掉"不会被抓住（118 已实测）。
    IF v_unbind_body !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V17 自证失败(c7): unbind_band 的【状态迁移 UPDATE 语句自身】里缺少 `tenant_id = p_tenant_id`。'
            '🛑 与 (b7) 同理：本断言从 `UPDATE band` 起锚、以分号为界，'
            '否则会被步骤 (4) 的 SELECT 里那句同款子句满足 —— 那是一条假绿。';
    END IF;

    -- ------------------------------------------------------------------
    -- (d) 迁移登记 + 权限
    -- ------------------------------------------------------------------
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V17';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V17 自证失败(d): schema_migration 中 V17 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- 用 has_function_privilege 而非读 proacl：前者把"角色继承 / PUBLIC 授权 /
    -- owner 隐含权限"等所有生效路径都算进去，后者只反映显式 ACL 项（V15 同款理由）。
    SELECT string_agg(x.sig, ', ' ORDER BY x.sig) INTO v_nopriv
    FROM (VALUES
              ('bind_band(uuid,uuid,uuid,text,text,date,boolean,text)'),
              ('unbind_band(uuid,uuid,text,date)')) AS x(sig)
    WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');
    IF v_nopriv IS NOT NULL THEN
        RAISE EXCEPTION
            'V17 自证失败(d2): 当前角色 % 对以下函数没有 EXECUTE 权限 -> %。'
            '典型成因：环境做过 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固，'
            '而第 2 节的授权段未覆盖到 —— 那会表现为"绑定时报 permission denied"，'
            '属运行期才发现的错误，故在迁移期断言。', current_user, v_nopriv;
    END IF;

    -- 🛑🛑 (d3) 上一条 (d2) 在【按本仓脚本建的库】上**恒真、判别力为零**。
    --   （2026-09-27 由 118 反向验证的 I(d2) 抓出 —— 注入 `REVOKE ... FROM current_user`
    --     之后自证仍然打印"自证通过"，即该断言对"权限被撤掉"毫无反应。）
    --   成因：迁移是用应用角色本身跑的 ⇒ 两个函数的 **owner 就是 current_user**
    --        ⇒ owner 对自有对象的 EXECUTE 是**隐含**的，has_function_privilege 永远为 t，
    --        且 REVOKE 也撤不掉它（要撤只能改 owner）。
    --   实测 proacl（本机 PG 17.11，diaoyuanyun_dev）：
    --        proacl = `=X/diaoyuanyun | diaoyuanyun=X/diaoyuanyun`
    --        其中 `=X/diaoyuanyun` 是 **PUBLIC 的 EXECUTE（PG 对函数的默认授权）**，
    --        `diaoyuanyun=X/...` 是第 2 节 GRANT 段显式授的那一条。
    --   ⇒ 把"授权段真的执行了"变成可机械判定的事实：断言 proacl 里存在【非 owner 的显式项】。
    --      `proacl IS NULL` 意味着"从未 GRANT 也从未 REVOKE"——那正是"授权段被摘掉"的形态。
    --      🛑 它同时覆盖了"PUBLIC 被 REVOKE 加固"的环境：那种情况下 proacl 仍非 NULL，
    --         而 v_granted 段的显式项仍在 ⇒ 本断言依然能区分"授权段跑了"与"没跑"。
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_noacl
    FROM (VALUES ('bind_band'), ('unbind_band')) AS x(f)
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
            'V17 自证失败(d3): 以下函数的 proacl 为 NULL —— 即【从未被 GRANT/REVOKE 过】：%。'
            '含义：第 2 节的授权段没有真正执行到这两个函数上。'
            '🛑 为什么 (d2) 单独不够：本迁移由应用角色自己执行 ⇒ 函数 owner = 调用者，'
            '而 owner 对自有函数的 EXECUTE 是隐含的 ⇒ (d2) 在该情形下恒为真。'
            '（118 的 I(d2) 已实测：REVOKE ... FROM current_user 之后 (d2) 仍报通过。）'
            '本断言改看【显式 ACL 项是否存在】，故对"授权段被摘掉 / 未生效"有反应。', v_noacl;
    END IF;

    -- ==================================================================
    -- (e) 行为验证 —— 全部在子事务里，块末整体撤销
    -- ==================================================================
    BEGIN
        -- 准备：两个租户（tenant 无 RLS，可直接写）
        INSERT INTO tenant (id, name, status)
        VALUES (v_ta, 'V17 手环绑定探针 A', 'active'),
               (v_tb, 'V17 手环绑定探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        -- 两个租户各建一个客户（customer 是 FORCE RLS ⇒ 必须各自在自己的上下文里写）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        INSERT INTO customer (id, tenant_id, name, status)
        VALUES (v_cust_a, v_ta, 'V17 探针客户 A', 'active');

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        INSERT INTO customer (id, tenant_id, name, status)
        VALUES (v_cust_b, v_tb, 'V17 探针客户 B', 'active');

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e1 绑定 → CREATED
        -- ---------------------------------------------------------------
        v_mode := bind_band(v_ta, v_band_a1, v_cust_a, 'GTL1', 'GTL-1', DATE '2026-09-27');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V17 自证失败(e1): 首次绑定返回 %（期望 CREATED）。'
                '后三态的含义见 bind_band 的返回值说明 —— 若返回 '
                'CUSTOMER_ALREADY_HAS_ACTIVE_BAND，说明该客户在库里已有 active 带子'
                '（探针 id 冲突 / 上次运行的残留）。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM band
                        WHERE band_id = v_band_a1 AND status = 'active' AND tenant_id = v_ta) THEN
            RAISE EXCEPTION 'V17 自证失败(e1): bind_band 返回 CREATED，但库里查不到该 active 行';
        END IF;

        -- ---------------------------------------------------------------
        -- e2 幂等：重放同一支带子 → ALREADY_BOUND
        --    🛑 这一条排除了"e1 成功只是因为函数没做任何检查"这种解释。
        -- ---------------------------------------------------------------
        v_mode := bind_band(v_ta, v_band_a1, v_cust_a, 'GTL1', 'GTL-1', DATE '2026-09-27');

        IF v_mode <> 'ALREADY_BOUND' THEN
            RAISE EXCEPTION
                'V17 自证失败(e2): 重放同一支带子返回 %（期望 ALREADY_BOUND）。'
                '返回 CREATED 说明幂等失效（库层唯一索引没有生效 —— 那就是"1 客户 : 1 有效手环"'
                '这条业务不变量被打破了，而且是静默的）。', v_mode;
        END IF;

        -- ---------------------------------------------------------------
        -- e3 给已有有效带子的客户再绑【另一支】且不换机 → CUSTOMER_ALREADY_HAS_ACTIVE_BAND
        --    🛑 三向断言：① 返回值对 ② 新带子【没有】落库 ③ 旧带子【仍】是 active
        --       只断言①是不够的：一个"返回对但偷偷也插了一行"的实现会通过①。
        -- ---------------------------------------------------------------
        v_mode := bind_band(v_ta, v_band_a2, v_cust_a, 'GTL1', NULL, DATE '2026-09-27');

        IF v_mode <> 'CUSTOMER_ALREADY_HAS_ACTIVE_BAND' THEN
            RAISE EXCEPTION
                'V17 自证失败(e3): 给已有有效带子的客户再绑一支（未要求换机）返回 %'
                '（期望 CUSTOMER_ALREADY_HAS_ACTIVE_BAND）。', v_mode;
        END IF;
        IF EXISTS (SELECT 1 FROM band WHERE band_id = v_band_a2) THEN
            RAISE EXCEPTION
                'V17 自证失败(e3): 返回值说"客户已有有效手环"，但第二支带子 % 竟然落库了 —— '
                '返回值与行为不一致。这说明 ON CONFLICT 没有生效，'
                '而 INSERT 之所以没报唯一冲突，是因为部分唯一索引的谓词写错了'
                '（例如漏掉了 WHERE status = ''active''，于是它退化成一个永远不冲突的普通插入）。', v_band_a2;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM band
                        WHERE band_id = v_band_a1 AND status = 'active') THEN
            RAISE EXCEPTION
                'V17 自证失败(e3): 旧带子 % 不再是 active —— 一次"应当被拒绝"的绑定'
                '竟然改动了既有数据。', v_band_a1;
        END IF;

        -- ---------------------------------------------------------------
        -- e4 换机（p_rebind = true）→ REPLACED，旧带子 unbound、新带子 active
        -- ---------------------------------------------------------------
        v_mode := bind_band(v_ta, v_band_a3, v_cust_a, 'GTL1', 'GTL-2', DATE '2026-09-28',
                            true, '换机');

        IF v_mode <> 'REPLACED' THEN
            RAISE EXCEPTION
                'V17 自证失败(e4): 换机绑定返回 %（期望 REPLACED）。'
                '若返回 CUSTOMER_ALREADY_HAS_ACTIVE_BAND，说明换机路径的 UPDATE '
                '没有把旧带子作废 —— 而它正是部分唯一索引能放行新带子的唯一前提。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM band WHERE band_id = v_band_a3 AND status = 'active') THEN
            RAISE EXCEPTION 'V17 自证失败(e4): 换机后新带子 % 不是 active', v_band_a3;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM band
                        WHERE band_id = v_band_a1 AND status = 'unbound'
                          AND unbind_reason = '换机') THEN
            RAISE EXCEPTION
                'V17 自证失败(e4): 换机后旧带子 % 的状态/理由不对（期望 unbound + 换机）。'
                '状态迁移必须留下理由 —— 否则"这支带子为什么被换掉了"永久不可答。', v_band_a1;
        END IF;

        -- ---------------------------------------------------------------
        -- e5 🛑 跨租户引用客户：**绕过函数**直接裸 INSERT
        --    band.tenant_id = A，但 customer_id 指向 B 的客户 → 必须被【数据库】拒。
        --    🛑 为什么必须绕过函数：本函数的第 (4) 步存在性检查也会拒绝它 ——
        --       于是"被拒绝了"这件事无法区分"函数层拦住了"与"库层拦住了"。
        --       而 V16 刚把 band_customer_id_fkey 换成 (tenant_id, customer_id) 复合外键，
        --       这里要证的正是【库层那道】仍然有效（防御纵深：函数可以被人绕过，
        --       外键不能）。
        --    🛑 为什么区分 23503 与 42501：两者都"拒绝"，但含义完全不同 ——
        --       23503 = 复合外键生效（本迁移与 V16 的成果）；
        --       42501 = RLS 的 WITH CHECK 拒绝（说明上下文不对，而不是外键在起作用）。
        --       若只断言"抛了异常"，一次上下文写错的实现会让本段假通过。
        -- ---------------------------------------------------------------
        BEGIN
            INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status)
            VALUES (v_band_b1, v_ta, v_cust_b, 'GTL1', CURRENT_DATE, 'active');
            -- 走到这里说明没被拒
            v_rejected := false;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_rejected := true;
            WHEN insufficient_privilege THEN
                v_rejected := false;   -- 42501 不算"被外键拒绝"
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V17 自证失败(e5): 跨租户引用【未被外键拒绝】——'
                '在租户 A 的上下文里，band 行成功把 customer_id 指向了租户 B 的客户。'
                '这说明 V16 的复合外键 band_customer_id_fkey (tenant_id, customer_id) '
                '→ customer (tenant_id, id) 没有生效。'
                '🛑 这一条的意义：band 的跨租户引用完整性不能只靠 bind_band 的入参检查 —— '
                '函数可以被人绕过（直接写 SQL），外键不能。';
        END IF;

        -- ---------------------------------------------------------------
        -- e6 对照：同租户裸 INSERT 必须【成功】
        --    🛑 一个"拒绝一切"的实现同样能让 e5 通过，故必须有本条对照。
        --    用 status = 'paused' 是刻意的：它不满足部分唯一索引的谓词
        --    （WHERE status = 'active'），故不会与 e4 建出的 active 行冲突 ——
        --    本条要测的是"外键放行"，不是"唯一索引放行"（后者由 e8 测）。
        -- ---------------------------------------------------------------
        BEGIN
            INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status)
            VALUES (v_band_a4, v_ta, v_cust_a, 'GTL1', CURRENT_DATE, 'paused');
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V17 自证失败(e6): 同租户引用被误拒 —— 复合外键把【正常路径】也挡住了。'
                '最可能的成因：外键的引用列写错了（例如引用了 customer 的非唯一列），'
                '或 tenant_id 未参与匹配。';
        END IF;

        -- ---------------------------------------------------------------
        -- e7 解绑 → UNBOUND；再解一次 → ALREADY_UNBOUND，且理由不得被覆盖
        -- ---------------------------------------------------------------
        v_mode := unbind_band(v_ta, v_band_a3, '设备损坏', DATE '2026-09-29');

        IF v_mode <> 'UNBOUND' THEN
            RAISE EXCEPTION 'V17 自证失败(e7): 首次解绑返回 %（期望 UNBOUND）', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM band
                        WHERE band_id = v_band_a3
                          AND status = 'unbound'
                          AND unbind_reason = '设备损坏'
                          AND unbound_at = DATE '2026-09-29') THEN
            RAISE EXCEPTION
                'V17 自证失败(e7): 解绑后的状态/理由/日期不对（期望 unbound + 设备损坏 + 2026-09-29）。'
                'unbound_at 是应戴天分母的终点，缺了或写错会让 A3 系统性偏高。';
        END IF;

        v_mode := unbind_band(v_ta, v_band_a3, NULL, NULL);

        IF v_mode <> 'ALREADY_UNBOUND' THEN
            RAISE EXCEPTION 'V17 自证失败(e7): 重复解绑返回 %（期望 ALREADY_UNBOUND，幂等）', v_mode;
        END IF;
        -- 🛑 这条是 c6 那处谓词的行为验证：理由不许被第二次调用（NULL）覆盖成 NULL
        IF (SELECT unbind_reason FROM band WHERE band_id = v_band_a3) IS NULL THEN
            RAISE EXCEPTION
                'V17 自证失败(e7): 第二次解绑（理由传 NULL）把第一次的 unbind_reason 覆盖成了 NULL。'
                '这是一次静默的数据回退：一次"什么都不该改"的幂等重放，抹掉了"为什么解绑"这一栏。'
                '成因通常是 UPDATE 的谓词里少了 `status <> ''unbound''`（见自证 c6）。';
        END IF;

        -- ---------------------------------------------------------------
        -- e8 解绑后可重新绑定 → CREATED
        --    证明部分唯一索引【不阻挡历史行】：v_band_a3 已是 unbound，
        --    故新绑一支必须是 CREATED，而不是 CUSTOMER_ALREADY_HAS_ACTIVE_BAND。
        --     🛑 这一条同时排除了"用 UNIQUE 索引（非部分）"这种错误实现 ——
        --        那种实现下 e8 会返回 CUSTOMER_ALREADY_HAS_ACTIVE_BAND，
        --        而"1 客户 : 1 有效手环"会被误读成"1 客户 : 1 手环（终生）"。
        -- ---------------------------------------------------------------
        v_mode := bind_band(v_ta, v_band_a2, v_cust_a, 'GTL1', NULL, DATE '2026-09-30');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V17 自证失败(e8): 解绑后重新绑定返回 %（期望 CREATED）。'
                '若返回 CUSTOMER_ALREADY_HAS_ACTIVE_BAND，说明约束把【历史行】也算进去了 —— '
                '而 uq_band_active_customer 是【部分】唯一索引（WHERE status = ''active''），'
                '历史行本就不该阻挡新绑定。', v_mode;
        END IF;

        -- 清场自证 (f) 之前先记录一行：证明确实有带子写进去过（否则下面的 DELETE 是空操作）
        SELECT count(*) INTO v_cnt FROM band WHERE tenant_id = v_ta;
        IF v_cnt < 3 THEN
            RAISE EXCEPTION
                'V17 自证失败(e9): 租户 A 的 band 行数 = %（期望 ≥3：e1/e4/e6/e8 写入的行）。'
                '这条断言的作用是让下面的"清场后为 0"有意义 —— 若本来就没写进去，'
                '那么"清场成功"与"什么都没发生"给出同一个 0。', v_cnt;
        END IF;

        -- ==============================================================
        -- (f) 探针清场 + 清场自证
        --   🛑 必须【逐个租户在自己的上下文里】删 —— 这个坑 V16 的自证块踩过：
        --       FORCE RLS 下 DELETE 在错误的上下文里会【静默删 0 行且不报错】，
        --       于是残留下来，在下一次运行本迁移时以"INSERT 撞主键"的面目出现，
        --       而那时没人记得它来自上一次探针。
        --   🛑 删除顺序必须与外键依赖倒序：
        --       band 引用 customer ⇒ 先删 band 再删 customer；
        --       customer 引用 tenant ⇒ 先删 customer 再删 tenant。
        --       写反了的表现是"在 customer 上的删除违反了 band 上的外键约束"。
        -- ==============================================================
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        DELETE FROM band WHERE tenant_id = v_ta;
        DELETE FROM customer WHERE id = v_cust_a;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        DELETE FROM band WHERE tenant_id = v_tb;   -- e5 被拒，本租户应为 0 行；仍清一次以防万一
        DELETE FROM customer WHERE id = v_cust_b;

        -- tenant 表无 RLS，可直接删
        DELETE FROM tenant WHERE id IN (v_ta, v_tb);

        -- (f2) 清场自证：逐租户设上下文检查（否则"删干净了"与"我看不见"同形）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        IF EXISTS (SELECT 1 FROM band WHERE tenant_id = v_ta) THEN
            RAISE EXCEPTION
                'V17 自证失败(f2): 探针清场不彻底（租户 A 的 band 仍有残留）。'
                '残留会在下一次运行时表现为"INSERT 撞主键"，而那时没人记得它来自本次探针。';
        END IF;
        IF EXISTS (SELECT 1 FROM customer WHERE id = v_cust_a) THEN
            RAISE EXCEPTION 'V17 自证失败(f2): 探针清场不彻底（租户 A 的客户 % 仍有残留）', v_cust_a;
        END IF;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM band WHERE tenant_id = v_tb) THEN
            RAISE EXCEPTION 'V17 自证失败(f2): 探针清场不彻底（租户 B 的 band 仍有残留）';
        END IF;
        IF EXISTS (SELECT 1 FROM customer WHERE id = v_cust_b) THEN
            RAISE EXCEPTION 'V17 自证失败(f2): 探针清场不彻底（租户 B 的客户 % 仍有残留）', v_cust_b;
        END IF;

        IF EXISTS (SELECT 1 FROM tenant WHERE id IN (v_ta, v_tb)) THEN
            RAISE EXCEPTION 'V17 自证失败(f2): 探针清场不彻底（探针租户仍有残留）';
        END IF;
    END;

    RAISE NOTICE 'V17 自证通过: 函数 2 / 登记 1 / 函数体断言全中 / 绑定四态齐备 / 换机与重绑正确 / 跨租户引用被外键拒(23503)且同租户放行 / 探针零残留';
END;
$v17_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   见文件头【回滚说明】。要点重述：
--     · 本迁移不建表、不写业务行 ⇒ 回滚无数据损失风险；
--     · 🛑 但**不要**用回滚来处理"band 表里已经绑了带子"这类情况 ——
--       带子行是数据。它们的存在意味着 E1/E2 现在能工作了，
--       把函数删掉会让那四张表的 device_id 外键重新失去引用来源。
-- ============================================================================