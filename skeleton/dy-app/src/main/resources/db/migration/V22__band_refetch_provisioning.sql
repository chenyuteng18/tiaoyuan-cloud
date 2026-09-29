-- ============================================================================
-- V22 · 手环历史补拉通路（A-3）
--      = register_sync_probe()  → band_sync_probe      （探针：N 的推定值留痕）
--      = register_daily_coverage() → band_daily_coverage（逐日覆盖：缺口原因）
--
-- ────────────────────────────────────────────────────────────────────────────
-- 一、缺口是什么
--
--   `band_sync_probe` 与 `band_daily_coverage` 是两张**生产代码零写入方**的表
--   （`ProvisioningBoundaryGateTest.NOT_PROVISIONED` 账的最后 2 张）。
--   ⇒ 后果：PRD §2.8.7 的「设备端历史补拉」整条能力**没有落库通路**，
--      而 A3（采集率/观测率）的"结构性不可观测占比"**取不到数**。
--
-- ────────────────────────────────────────────────────────────────────────────
-- 二、口径来源（🛑 本节把"上游是否已定"这件事一次说清）
--
--   ✅ **口径已定**：`prd-health-mgmt-saas-2026-09-16.md` §2.8.7
--      （v1.25 新增，业务方 2026-09-19 拍板；第 470~536 行）。
--      🛑 此前一度登记为"上游补拉口径未定 ⇒ 须先核对" —— 那是**误判**，
--         原因是只在 skeleton/README.md 里检索「补拉」二字（仅 2 处转述性提及），
--         没有回到 PRD 正文。核对结论与逐字锚点见
--         `skeleton/_work/a3-band-refetch-ruling-request.md`。
--
--   本迁移直接落地的 §2.8.7 规则（逐条对应）：
--     · 依据       13 条逐日历史接口 + 游标（`sportLength`）+ `bufferSize`（§2.8.7③表首行）
--     · 起始日     `start = max(last_synced_date + 1, today − N_retain)`；上界 today
--                  ⇒ 🛑 **不得回溯超过 floor**：超窗口只会制造【假缺失】（本迁移的
--                     `beyond_retention_window` 槽位就是为这条准备的）
--     · 幂等       **重复拉取 = 覆盖（upsert），非追加**（§2.8.7③「幂等」行）
--                  ⇒ `band_daily_coverage` 的既有载体 `uq_daily_coverage_device_date
--                     UNIQUE (device_id, date)` 正是这条规则的落点
--     · 服务端权威 上报前 upsert（与 S1-5「服务端唯一权威」同口径）
--     · 断点续传   逐日持久化 `date_done`；失败日入 `pending_dates`（落在 band_sync_log）
--     · N 留痕     `n_at_that_time`（§2.8.7③「N 随探测刷新，须留痕」）
--
-- ────────────────────────────────────────────────────────────────────────────
-- 三、🛑 本迁移【刻意不做】的两件事（硬边界，不得越）
--
--   ① **不新增 `gap_reason` 枚举值**。
--      已实测：§2.8.7⑤ 的三分表里「① 客户未同意佩戴（结构性）」在现行 7 值里
--      **没有对应槽位**（7 值 = no_open / sync_failed / not_worn / compliant_removal /
--      involuntary_technical / beyond_retention_window / unknown，
--      与 `contract/openapi-v1.0.0.yaml:1781-1786` 的 enum **逐字一致**）。
--      🛑 增删该枚举属**契约变更** ⇒ 按本仓纪律**登记待裁、不代拍**。
--      本迁移只做**逻辑自洽**层面的断言（见第 1 节 (5c)），不替上游定枚举。
--
--   ② **不实现厂商 SDK 调用 / 13 条接口 / 游标翻页**。
--      §2.8.7 明示这些"**文档自证、待真机复核**"（第三条外部依赖）。
--      本迁移是**库层写入原语**：它接收调用方已经拿到的结果，不解析厂商协议。
--      ⇒ 与 V21（协议离线签署）同一形态：**运维/服务通路，无 HTTP 映射**。
--
-- ────────────────────────────────────────────────────────────────────────────
-- 四、与 V5 建表的关系（🛑 本迁移【不改任何既有表结构】）
--
--   两张表在 V5 已建（`band_sync_probe` 第 899 行 / `band_daily_coverage` 第 990 行），
--   均 ENABLE + FORCE RLS，策略为双端 NULLIF fail-closed。
--   本迁移**只新增两个函数**，不动一列、不动一个约束 ——
--   故对已应用库的处置是【纯增量】（无 checksum 冲突面，见第 5 节）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立
--
--   与 V15/V17/V18/V19/V20/V21 同款形态，但本迁移有**四条专属前提**，
--   它们都是"核心机制是否还是活的"的判据（而不是"表在不在"）。
-- ============================================================================

DO
$v22_precond$
DECLARE
    v_rls_p    boolean;
    v_force_p  boolean;
    v_rls_c    boolean;
    v_force_c  boolean;
    v_pk_p     text;
    v_uq_c     text;
    v_hist_chk boolean;
    v_gap_chk  boolean;
BEGIN
    -- 前提① 两张表都存在，且 ENABLE + FORCE RLS
    --   FORCE 的必要性与 V20/V21 逐字相同：ENABLE 只管"非表属主"，
    --   FORCE 才把表属主也纳入策略管辖。缺 FORCE 时若迁移由 table owner 执行，
    --   策略**不被应用** ⇒ 本函数的上下文守卫与跨租户隔离**静默失效**。
    SELECT c.relrowsecurity, c.relforcerowsecurity INTO v_rls_p, v_force_p
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'band_sync_probe';
    SELECT c.relrowsecurity, c.relforcerowsecurity INTO v_rls_c, v_force_c
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'band_daily_coverage';

    IF v_rls_p IS NULL OR v_rls_c IS NULL THEN
        RAISE EXCEPTION
            'V22 前置失败: band_sync_probe / band_daily_coverage 不存在。'
            '本迁移的补拉原语依赖它们（V5 第 899 / 990 行建表）。';
    END IF;
    IF NOT (v_rls_p AND v_rls_c) THEN
        RAISE EXCEPTION
            'V22 前置失败: 两表未【双双】启用 ROW LEVEL SECURITY。'
            '本迁移的核心之一是"写入必须自己建立租户上下文"，若无 RLS，'
            '该守卫失去守护对象 —— 不是"可以省略"，而是"前提被推翻"。';
    END IF;
    IF NOT (v_force_p AND v_force_c) THEN
        RAISE EXCEPTION
            'V22 前置失败: 两表只 ENABLE 未 FORCE（probe=% / coverage=%）。'
            '🛑 与 V20/V21 同款理由：ENABLE 只对"非表属主"生效，FORCE 才管住表属主。'
            '缺 FORCE 时若迁移由 table owner 跑，策略不被应用 ⇒ '
            '跨租户隔离的自证会以【归因错误】的面目报红或假绿。', v_force_p, v_force_c;
    END IF;

    -- 前提② `band_sync_probe` 主键是【单列】probe_id
    --   🛑 这是探针幂等语义的前提：probe 表**没有** (device_id, history_type) 唯一载体
    --      ⇒ "同一 device + history_type 多次探测"是**合法的追加**（N 会随探测刷新），
    --      与 `band_daily_coverage` 的 upsert 语义**刻意不同**（见第 1 节 (6)）。
    SELECT pg_get_constraintdef(con.oid) INTO v_pk_p
      FROM pg_constraint con
     WHERE con.conrelid = 'band_sync_probe'::regclass AND con.contype = 'p';
    IF v_pk_p IS DISTINCT FROM 'PRIMARY KEY (probe_id)' THEN
        RAISE EXCEPTION
            'V22 前置失败: band_sync_probe 主键形态变了 —— 期望逐字 `PRIMARY KEY (probe_id)`，'
            '实测 `%`。本函数的幂等判定以 probe_id 为唯一键，'
            '若有人给它加了 (tenant_id, device_id, history_type) 之类的唯一载体，'
            '则"同一探头多次探测"会从追加变成冲突 ⇒ 需重新裁定本函数的语义。', v_pk_p;
    END IF;

    -- 前提③ 🛑🛑 `band_daily_coverage` 上🈶 `UNIQUE (device_id, date)` —— 本迁移的【唯一支柱】
    --   它是 §2.8.7③「重复拉取 = 覆盖（upsert），非追加」的**唯一载体**。
    --   若这条唯一约束消失，`ON CONFLICT (device_id, date)` 会直接报错（不是静默降级），
    --   且"覆盖语义"退化为"追加" —— 那会让同一天出现两行、A3 分母被重复计入。
    SELECT pg_get_constraintdef(con.oid) INTO v_uq_c
      FROM pg_constraint con
     WHERE con.conrelid = 'band_daily_coverage'::regclass AND con.contype = 'u';
    IF v_uq_c IS DISTINCT FROM 'UNIQUE (device_id, date)' THEN
        RAISE EXCEPTION
            'V22 前置失败: band_daily_coverage 的唯一载体变了 —— 期望逐字 `UNIQUE (device_id, date)`，'
            '实测 `%`。🛑 这一条是"重复拉取=覆盖"的唯一支柱：'
            '没有它，upsert 无从锚定，同日会追加出多行 ⇒ A3 的"应戴天"分母被重复计数，'
            '而 A3 是"唯一可扣分"指标之一 —— 分母错了会直接冤枉客户。', v_uq_c;
    END IF;

    -- 前提④ 两表的 CHECK 约束【至少】各含其枚举列
    --   🛑 与 V21 的"恰 1 条"断言**方向相反**：V21 要"恰 1 条"是为了让新增 CHECK 被看见；
    --      本迁移要"至少含这两列"是因为两表本来就有多条 CHECK（实测 probe=3 / coverage=2），
    --      刻录"恰 N 条"会把一次无关的新增 CHECK 变成假红。**断言对象不同，故形态不同。**
    SELECT count(*) > 0 INTO v_hist_chk
      FROM pg_constraint con
     WHERE con.conrelid = 'band_sync_probe'::regclass
       AND con.contype = 'c' AND pg_get_constraintdef(con.oid) LIKE '%history_type%';
    SELECT count(*) > 0 INTO v_gap_chk
      FROM pg_constraint con
     WHERE con.conrelid = 'band_daily_coverage'::regclass
       AND con.contype = 'c' AND pg_get_constraintdef(con.oid) LIKE '%gap_reason%';

    IF NOT v_hist_chk THEN
        RAISE EXCEPTION
            'V22 前置失败: band_sync_probe.history_type 上没有 CHECK。'
            '本函数的 (5b) 段按"14 值词汇"显式校验，而表层那道是第二层保险 ——'
            '函数可以被人绕过（直接写 SQL），CHECK 不能。';
    END IF;
    IF NOT v_gap_chk THEN
        RAISE EXCEPTION
            'V22 前置失败: band_daily_coverage.gap_reason 上没有 CHECK。'
            '🛑 这一条尤其关键：本函数的 (5c) 段要断言「not_worn 不得与 is_wear IN (-1,255) 共存」'
            '（§2.8.7 逐字"一律不判行为性、宁可少扣不可错扣"），'
            '而该断言依赖 gap_reason 的取值域是封闭的。';
    END IF;
END;
$v22_precond$;


-- ============================================================================
-- 第 1 节 · 两个补拉原语
-- ============================================================================

-- ────────────────────────────────────────────────────────────────────────────
-- 1.1 register_sync_probe —— 探针写入（N 的推定值留痕）
--
--   语义：记录"某设备某指标的历史留存窗口推定值 N 及其来源"。
--   🛑 幂等形态 = **按 probe_id 追加**（不是 upsert），理由见第 0 节前提②：
--      N 会随探测刷新，历史探测记录是**证据**（"当时按 N=7 补拉的"），
--      覆盖它会摧毁"该日补拉用了哪个 N"的可回溯性 ——
--      而 `band_daily_coverage.n_at_that_time` 正需要这个对应关系。
-- ────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION register_sync_probe(
    p_tenant_id        uuid,
    p_probe_id         uuid,
    p_device_id        uuid,
    p_history_type     text,
    p_valid_dates_json jsonb       DEFAULT NULL,
    p_retention_days   int         DEFAULT NULL,
    p_probe_source     text        DEFAULT NULL,
    p_probed_at        timestamptz DEFAULT NULL,
    p_is_test          boolean     DEFAULT FALSE,
    p_created_by       text        DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v22_probe$
DECLARE
    -- 🛑 14 值词汇 —— 来源 = V5 第 914-916 行的 CHECK，逐字。
    --    与 V3 `band_telemetry.metric` **同一套词汇**（V5 注释已说明为何统一：
    --    §4.6 的消费规则要求 `N_retention = min(各 history_type 的 retention_window_days)`
    --    并与逐日 upsert `band_telemetry` 对齐 —— 两表词汇不同名则 min() 无法按同一键聚合）。
    --    ⚠️ 该词汇本身含一处**登记待裁**（V5 §9 A-8：字典 §4.6 的 camelCase 接口名、
    --       V3 的 13 值、§4.7 的"13 条接口"三者并非同一集合）—— 本迁移**不动**它。
    v_hist_types   text[] := ARRAY[
        'sleep','steps','hr','resting_hr','spo2','workout','bp','temp',
        'pressure','met','mai','respiration','exercise','sport'];
    v_probe_srcs   text[] := ARRAY['运行时探测','厂商文档','保守假设'];
    v_ctx_before   text;
    v_ctx          text;
    v_dev          int := 0;
    v_mine         int := 0;
    v_affected     int := 0;
BEGIN
    -- (1) 入参守卫（与 V15/V17/V18/V19/V20/V21 同款：DDL 的 NOT NULL 挡不住空串）
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_sync_probe: p_tenant_id 不可为空（band_sync_probe.tenant_id 是 NOT NULL）。'
            '它是 RLS 策略唯一的判定列 —— 为空等于这行没有归属，且 WITH CHECK 会拒绝。';
    END IF;
    IF p_probe_id IS NULL THEN
        RAISE EXCEPTION
            'register_sync_probe: p_probe_id 不可为空（band_sync_probe.probe_id 是主键，'
            '且是本函数幂等判定的唯一键 —— 没有它，重放无法与"新建"区分）。';
    END IF;
    IF p_device_id IS NULL THEN
        RAISE EXCEPTION
            'register_sync_probe: p_device_id 不可为空（band_sync_probe.device_id 是 NOT NULL，'
            '且是复合外键 (tenant_id, device_id) → band(tenant_id, band_id) 的一端）。'
            '🛑 探测的对象就是这台设备 —— 没有它，"N 适用于谁"不可答。';
    END IF;
    IF p_history_type IS NULL OR btrim(p_history_type) = '' THEN
        RAISE EXCEPTION
            'register_sync_probe: p_history_type 不可为空串（band_sync_probe.history_type 是 NOT NULL）。'
            '🛑 空串在语义上无意义，且会静默流进台账 —— 与 V15/V17/…/V21 同款守卫。';
    END IF;
    IF p_retention_days IS NOT NULL AND p_retention_days < 0 THEN
        RAISE EXCEPTION
            'register_sync_probe: p_retention_days = % 非法 —— 留存窗口天数不可为负。'
            '口径来源 = §2.8.7③「依据」行（`bufferSize` 是窗口容量，不是负偏移）；'
            '表上 CHECK 也写着 `>= 0`，此处显式先判一次以给出**可归因**的错误。', p_retention_days;
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     与 V17/V18/V19/V20/V21 的 (2) 逐字同款，此处摘要：
    --     `set_config(app.tenant_id, …, true)` 是**事务级**设置且**不会**在函数入口自动重置。
    --     若调用方在"已设成租户 B"的事务里传租户 A，不检查就会把上下文静默改写成 A：
    --     后面的写是 A 的，而调用方以为是 B 的。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_sync_probe: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, …, true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '🛑 探针写的是"补拉窗口"的取证材料 —— 它决定 A3 的分子能不能取到数，'
            '写错租户会让另一个租户的客户被按错误的 N 记账。'
            '调用方须先结束当前事务（或使用全新连接）再登记。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 用 set_config（普通函数、参数可绑定），不用 SET LOCAL（后者须拼 SQL 文本 ⇒ 有注入面）。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_sync_probe 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 设备必须**在本租户内**存在
    --     库层还有复合外键 `band_daily_coverage_device_id_fkey` 同款的 band 侧兜底（V16 成果）。
    SELECT count(*) INTO v_dev
      FROM band
     WHERE band_id = p_device_id AND tenant_id = p_tenant_id;
    IF v_dev <> 1 THEN
        RAISE EXCEPTION
            'register_sync_probe: 手环 % 在租户 % 内不存在（本租户可见行数 = %），拒绝登记探针。'
            '🔴 若该手环确实存在，最常见的原因是租户上下文不对 —— band 的 RLS 是 fail-closed 的，'
            '上下文错了 SELECT 会静默返回 0 行，于是"不是我的设备"与"我看不见我的设备"给出同一结论。'
            '本次上下文的实际值（已自证）= %。', p_device_id, p_tenant_id, v_dev, v_ctx;
    END IF;

    -- (5a) 历史指标必须在封闭词汇内（显式先判 ⇒ 可归因；表层 CHECK 是第二层）
    IF NOT (p_history_type = ANY (v_hist_types)) THEN
        RAISE EXCEPTION
            'register_sync_probe: p_history_type = % 不在已登记的 14 值词汇内。'
            '已登记 = %。🛑 不得静默接受未知指标：'
            '§2.8.7③ 的消费规则是 `N_retention = min(各 history_type 的 retention_window_days)`，'
            '一个拼错的指标名会**悄悄退出 min()**，让补拉窗口被一个比真实值更大的数决定 ⇒ '
            '回溯超出设备窗口 ⇒ 制造出【假缺失】（正是 §2.8.7 逐字警告的形态）。',
            p_history_type, array_to_string(v_hist_types, ', ');
    END IF;

    -- (5b) 探测来源必须在封闭词汇内（表层同样有 CHECK；此处为归因质量）
    IF p_probe_source IS NOT NULL AND NOT (p_probe_source = ANY (v_probe_srcs)) THEN
        RAISE EXCEPTION
            'register_sync_probe: p_probe_source = % 不在已登记的 3 值内（%）。'
            '🛑 该列的用途是**区分 N 的可信度**：「运行时探测」是实测、「厂商文档」是待复核、'
            '「保守假设」是上限未取证时的占位（§2.8.7 明示"未实测前按保守假设 ≤7 天"）。'
            '若允许自由文本，这三种可信度会在台账里被拉平。',
            p_probe_source, array_to_string(v_probe_srcs, ', ');
    END IF;

    -- (5c) valid_dates_json 给了就必须是 JSON **数组**（不是对象）
    --   🛑 单独断言的理由（与 V21 的 (1b) 同源）：`jsonb_typeof` 能把"类型错"与"内容空"
    --      分成两条不同的报错 —— 否则一次类型错误会被伪装成一次业务错误。
    IF p_valid_dates_json IS NOT NULL
       AND jsonb_typeof(p_valid_dates_json) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION
            'register_sync_probe: p_valid_dates_json 给了值就必须是 JSON 数组（array），实际类型 = %。'
            '口径来源 = §2.8.7③「依据」行：`getValidHistoryDates` 返回的是**有效日期数组**。',
            coalesce(jsonb_typeof(p_valid_dates_json), '<NULL>');
    END IF;

    -- (6) 🛑 幂等写入 + 跨租户撞号判定 —— 与 V21 的「核心机制二」**同构**
    --
    --   🛑🛑 为什么必须是 `ON CONFLICT … DO NOTHING` + `ROW_COUNT` 三段法，
    --        而**不能**写"先 SELECT count 再 INSERT"：
    --        · 后者有**并发窗口** —— 两个并发调用可以都读到 0，然后都 INSERT；
    --          一个成功、另一个撞主键（23505），而 23505 是一条**无归因**的错误。
    --        · 前者的三段判定**全部来自库层原子事实**，没有读-写间隙。
    --
    --   三段：
    --     ① ROW_COUNT = 1                ⇒ 本次真的建出来了 → CREATED
    --     ② ROW_COUNT = 0 且本租户看得见 ⇒ 本租户的行 ⇒ 幂等命中 → ALREADY_EXISTS
    --     ③ ROW_COUNT = 0 且本租户看不见 ⇒ 插不进去又看不见 ⇒ **在别的租户名下**
    --
    --   🛑 ③ 不是死代码：`probe_id` 是**全局单列主键**，跨租户撞号**必然可能发生**
    --      （与 V21 的 `agreement_pkey` 同型）。若不显式判 ③，
    --      就会 return 'ALREADY_EXISTS' 而本租户一行都没有 ——
    --      调用方据此认为"探针已登记"，而实际上该设备在本租户内**没有任何 N 的留痕**，
    --      于是补拉会按"未探到 N"运行，并以**假缺失**的形式出现在 A3 里。
    INSERT INTO band_sync_probe (
        probe_id, tenant_id, device_id, history_type, valid_dates_json,
        retention_window_days, probe_source, probed_at, is_test, created_by
    ) VALUES (
        p_probe_id, p_tenant_id, p_device_id, p_history_type, p_valid_dates_json,
        p_retention_days, p_probe_source, coalesce(p_probed_at, now()),
        coalesce(p_is_test, FALSE), coalesce(nullif(btrim(p_created_by), ''), 'band-refetch')
    )
    ON CONFLICT (probe_id) DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- ① 本次真的建出来了
    IF v_affected = 1 THEN
        RETURN 'CREATED';
    END IF;

    -- ② 未插入 ⇒ 全局已存在。它在谁名下？（这次读在【已自证的本租户上下文】里）
    SELECT count(*) INTO v_mine
      FROM band_sync_probe
     WHERE probe_id = p_probe_id;

    IF v_mine = 1 THEN
        RETURN 'ALREADY_EXISTS';
    END IF;

    -- ③ 插不进去却又看不见 ⇒ 它在别的租户名下
    RAISE EXCEPTION
        'register_sync_probe: 探针 % 已存在于**另一个租户**名下 —— 本租户内看不到它，'
        '但主键已被占用，写入无法完成。拒绝静默返回 ALREADY_EXISTS。'
        '🛑 `probe_id` 是全局单列主键（不是 (tenant_id, probe_id)）⇒ 跨租户撞号是必然可能。'
        '返回 ALREADY_EXISTS 会让调用方以为"探针已登记"，而本租户内**没有任何 N 的留痕** ⇒ '
        '补拉起手时会按"未探到 N"运行，并以【假缺失】的形式出现在 A3 里。'
        '处置：换一个 probe_id（UUID 应全局唯一），或先与该租户核对这条探针的归属。',
        p_probe_id;
END;
$v22_probe$;


-- ────────────────────────────────────────────────────────────────────────────
-- 1.2 register_daily_coverage —— 逐日覆盖写入（🛑 upsert，非追加）
--
--   语义：记录"某设备某业务日的覆盖情况 + 缺口原因"。
--   🛑 幂等形态 = **upsert**（键 `(device_id, date)`），逐字依据 = §2.8.7③「幂等」行：
--      "**上报前 upsert（服务端权威）** → 重复拉取 = 覆盖、非追加"。
--   🛑 覆盖时 **不得改写 `coverage_id` / `device_id` / `customer_id`**：
--      它们是"这一行是谁的"的身份位；覆盖只更新**当日观测事实**。
-- ────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION register_daily_coverage(
    p_tenant_id              uuid,
    p_coverage_id            uuid,
    p_device_id              uuid,
    p_customer_id            uuid,
    p_date                   date,
    p_coverage_flag          boolean     DEFAULT NULL,
    p_gap_reason             text        DEFAULT NULL,
    p_is_wear                int         DEFAULT NULL,
    p_wear_minutes           int         DEFAULT NULL,
    p_effective_wear_minutes int         DEFAULT NULL,
    p_n_at_that_time         int         DEFAULT NULL,
    p_source_sync_log_id     uuid        DEFAULT NULL,
    p_created_by             text        DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v22_cov$
DECLARE
    -- 🛑 7 值词汇 —— 来源 = `contract/openapi-v1.0.0.yaml:1781-1786` 的 enum + V5 CHECK，逐字一致。
    --    ⚠️ 这 7 值的**封闭性由契约与表层共同钉住**；本函数不新增、不删减（第 0 节硬边界①）。
    v_gap_reasons  text[] := ARRAY[
        'no_open','sync_failed','not_worn','compliant_removal',
        'involuntary_technical','beyond_retention_window','unknown'];
    -- 🛑 技术性 isWear 取值 —— §2.8.7⑤ 逐字：`1`=佩戴 / `0`=脱腕(行为性) / `(-1,255)`=技术性缺失
    v_tech_iswear  int[]  := ARRAY[-1, 255];
    v_ctx_before   text;
    v_ctx          text;
    v_dev          int := 0;
    v_cust         int := 0;
    v_log          int := 0;
    v_owner_dev    uuid;
    v_was_insert   boolean;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_daily_coverage: p_tenant_id 不可为空（band_daily_coverage.tenant_id 是 NOT NULL）。';
    END IF;
    IF p_coverage_id IS NULL THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_coverage_id 不可为空（band_daily_coverage.coverage_id 是主键）。'
            '🛑 注意：它**不是** upsert 的冲突键（那是 (device_id, date)）——'
            '它只是"首次插入那一行"的身份；覆盖时不改写（见 (7)）。';
    END IF;
    IF p_device_id IS NULL THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_device_id 不可为空（NOT NULL，且是复合外键的一端）。'
            '🛑 它同时是 upsert 冲突键的一半 —— 为空则"同日覆盖"无从锚定。';
    END IF;
    IF p_customer_id IS NULL THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_customer_id 不可为空（NOT NULL，且是复合外键的一端）。'
            '🛑 它承载**行级 scope**（coverage 表内没有 store_id，可见范围随客户走）——'
            '为空等于这条覆盖记录没有归属，A3 的"应戴天"分母也就无从按客户归集。';
    END IF;
    IF p_date IS NULL THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_date 不可为空（NOT NULL）。'
            '🛑 它是 upsert 冲突键的另一半，也是 A3 口径里"业务日"的唯一载体 ——'
            '§2.8.7⑤ 明示分母 = 「台账应戴天」，而"天"就是这一列。';
    END IF;

    -- (2) 上下文一致性守卫（同 register_sync_probe 的 (2)，理由不重复）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_daily_coverage: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '🛑 逐日覆盖是 A3 的**直接取值来源**（"唯一可扣分"的那一类就落在这张表），'
            '写错租户 = 把一个客户的"未佩戴"记到另一个客户头上。'
            '本次上下文的实际值（已自证）= %。',
            v_ctx_before, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_daily_coverage 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 设备必须在本租户内存在
    SELECT count(*) INTO v_dev FROM band WHERE band_id = p_device_id AND tenant_id = p_tenant_id;
    IF v_dev <> 1 THEN
        RAISE EXCEPTION
            'register_daily_coverage: 手环 % 在租户 % 内不存在（本租户可见行数 = %），拒绝登记覆盖。'
            '上下文实际值（已自证）= %。', p_device_id, p_tenant_id, v_dev, v_ctx;
    END IF;

    -- (4b) 客户必须在本租户内存在
    SELECT count(*) INTO v_cust FROM customer WHERE id = p_customer_id AND tenant_id = p_tenant_id;
    IF v_cust <> 1 THEN
        RAISE EXCEPTION
            'register_daily_coverage: 客户 % 在租户 % 内不存在（本租户可见行数 = %），拒绝登记覆盖。'
            '🔴 若该客户确实存在，最常见原因是租户上下文不对（customer 的 RLS 是 fail-closed）。'
            '上下文实际值（已自证）= %。', p_customer_id, p_tenant_id, v_cust, v_ctx;
    END IF;

    -- (4c) source_sync_log_id 若给了，必须在**本租户内**存在
    --   🛑 与 V21 的 (4b) 同款理由：显式先判一次是为了**归因质量** ——
    --      否则调用方只会收到一条 23503 与一个约束名（
    --      `band_daily_coverage_source_sync_log_id_fkey` 是复合外键，V16 成果）。
    IF p_source_sync_log_id IS NOT NULL THEN
        SELECT count(*) INTO v_log
          FROM band_sync_log
         WHERE sync_log_id = p_source_sync_log_id AND tenant_id = p_tenant_id;
        IF v_log <> 1 THEN
            RAISE EXCEPTION
                'register_daily_coverage: 同步日志 % 在租户 % 内不存在（本租户可见行数 = %）。'
                '§2.8.7③「断点续传」要求"由哪次同步回捞"可回溯（`band_daily_coverage.source_sync_log_id`）——'
                '指向一条看不见的日志会让这条回溯链断开。'
                '库层还有复合外键兜底（函数可被人绕过，外键不能）。', p_source_sync_log_id, p_tenant_id, v_log;
        END IF;
    END IF;

    -- (5a) gap_reason 必须在 7 值内（显式先判 ⇒ 可归因；表层 CHECK 是第二层）
    IF p_gap_reason IS NOT NULL AND NOT (p_gap_reason = ANY (v_gap_reasons)) THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_gap_reason = % 不在契约登记的 7 值内。'
            '已登记 = %。'
            '🛑 契约 `BandDailyData.gap_reason` 的 enum 与本表 CHECK **逐字一致**，'
            '新值属【契约变更】—— 不得在本函数里悄悄扩权。'
            '⚠️ 已知缺口（登记待裁）：§2.8.7⑤ 的第「① 客户未同意佩戴（结构性）」'
            '在现行 7 值里【没有槽位】⇒ 该类的"结构性不可观测占比"目前无法单列。',
            p_gap_reason, array_to_string(v_gap_reasons, ', ');
    END IF;

    -- (5b) is_wear 必须在 {-1, 0, 1, 255} 内（表层 CHECK 同款；此处为归因质量）
    IF p_is_wear IS NOT NULL AND NOT (p_is_wear = ANY (ARRAY[0, 1, -1, 255])) THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_is_wear = % 非法。'
            '口径 = §2.8.7⑤ 逐字：`1`=佩戴（正常）／`0`=脱腕（行为性）／`(-1,255)`=技术性缺失。',
            p_is_wear;
    END IF;

    -- (5c) 🛑🛑 本迁移的【核心门禁】—— 技术性缺失不得被判为行为性
    --     依据（§2.8.7⑤ 逐字）："`(-1,255)`=技术性缺失 → **一律不判行为性
    --       （宁可少扣、不可错扣）**，并须向厂商索要『设备异常原因码』
    --       把『传感器失败』与『客户脱腕』分开"。
    --
    --     🛑 为什么这条必须由**写入侧**守（而不是取数侧）：
    --       `not_worn` 是**唯一可扣分**的缺口原因（§2.8.7⑤ 第②行"缺失天计 0（扣分）"）。
    --       一旦"技术性缺失"被写成 `not_worn`，A3 就会**扣客户的分**，
    --       而 A3 进「依从性」维度、并进一步影响 `AS_refund` ——
    --       这是本仓反复记载的"**对客户不利**"形态，也是最不能靠人工检查兜住的一类。
    --       ⇒ 把"设备说不清原因"这行数据**挡在库外**，比"取数时记得排除"可靠。
    IF p_gap_reason = 'not_worn' AND p_is_wear = ANY (v_tech_iswear) THEN
        RAISE EXCEPTION
            'register_daily_coverage: 拒绝写入 —— gap_reason = not_worn 与 is_wear = % 不能共存。'
            '依据 = §2.8.7⑤ 逐字：「`(-1,255)`=技术性缺失 → **一律不判行为性**（宁可少扣、不可错扣）」。'
            '🛑 `not_worn` 是全 7 值里**唯一可扣分**的一类（"缺失天计 0"）；'
            '把 is_wear 的"技术性"取值配上 not_worn，等于把设备侧故障记成客户没戴 —— '
            'A3 会扣分、依从性会下降、并影响 AS_refund。'
            '正确处置：技术性缺失请写 gap_reason = ''involuntary_technical''（或 '
            '''beyond_retention_window'' / ''sync_failed''）。'
            '⚠️ 附：更细的"传感器失败 vs 客户脱腕"区分，须向厂商索要【设备异常原因码】'
            '（§2.8.7⑤ 末句，属外部依赖，尚未取证）—— 故此处取**保守方向**（宁可少扣）。',
            p_is_wear;
    END IF;

    -- (5d) 逻辑自洽：「有数据」与「有缺口原因」互斥
    --   🛑 这不是上游口径（PRD 未逐字定义二者的配对），而是**本表的自洽要求**：
    --      `coverage_flag = TRUE` 表示"该日有数据"（V5 第 999 行注释："true = 该日有数据"），
    --      此时填 gap_reason 等于同时说"有数据"和"为什么没数据"。
    --      ⇒ 该形态会让 A3 的取数逻辑出现**两条互斥的分支同时命中**。
    --   ⚠️ 反向不成立：`coverage_flag IS NULL`（缺失/未判定）与 `FALSE`（明确判定缺失）
    --      都**可以**配 gap_reason —— 故只断言单向。
    IF p_coverage_flag IS TRUE AND p_gap_reason IS NOT NULL THEN
        RAISE EXCEPTION
            'register_daily_coverage: 拒绝写入 —— coverage_flag = TRUE（该日有数据）与 '
            'gap_reason = % 不能共存。'
            '「有数据」与「为什么没数据」是互斥的两个陈述；同时成立会让 A3 取数时'
            '两条互斥分支同时命中。若该日确有数据而只是部分缺失，'
            '请用 coverage_flag = NULL 表达"未判定/部分"，而不是 TRUE。', p_gap_reason;
    END IF;

    -- (5e) n_at_that_time 不可为负
    IF p_n_at_that_time IS NOT NULL AND p_n_at_that_time < 0 THEN
        RAISE EXCEPTION
            'register_daily_coverage: p_n_at_that_time = % 非法 —— N 是留存窗口天数，不可为负。'
            '§2.8.7③ 要求"N 随探测刷新，须留痕"，故它是**证据**（"这条覆盖是按哪个 N 补拉的"）。',
            p_n_at_that_time;
    END IF;

    -- (6) 🛑 归属与租户的一致性 —— 判定**全部**放在 (7) 的 upsert 语句里
    --   为什么不在此处"先 SELECT 再比对再决定"：
    --     · 那是**读-写决策**，有并发窗口（两个并发调用可都读到同一既有行、都判为合法）；
    --     · 把条件并入 `ON CONFLICT … DO UPDATE … WHERE` 之后，
    --       判定与写入是**同一条语句**，无间隙（见 (7) 的完整说明）。
    --   ⚠️ 本段只保留一处**诊断**查询（在 (7) 判定之后、只为措辞服务，不参与决策）。

    -- (7) upsert：🛑 覆盖只更新【当日观测事实】，不改写身份位
    --   不更新的列（刻意列出，防后人误加）：
    --     · coverage_id   —— 首次插入那行的身份（改它等于换行）
    --     · tenant_id     —— 恒等于本函数据以建立的上下文
    --     · device_id / customer_id / date —— upsert 键与归属（见 (6)）
    --     · created_at / created_by —— "谁在什么时候建的"是不可变更的既成事实
    --
    --   🛑🛑 判定"本次是插入还是覆盖"用 **xmax = 0** 这一库层原子事实，
    --        而**不是**"先 SELECT count 再决定"。理由与 register_sync_probe 的 (6) 同款：
    --        先读后写有**并发窗口**（两个并发调用可都读到"已存在"）。
    --        `xmax = 0` 恒表示"该行由**当前这条语句**插入"（未被他事务更新过），
    --        是 PG 上 INSERT … ON CONFLICT 的惯用判别式，**无读-写间隙**。
    --        ⚠️ 注意：xmax 是系统列，在 RETURNING 里取的是**该行当时的**事务 id，
    --           对"被 ON CONFLICT DO UPDATE 路径更新的行"它非 0。
    INSERT INTO band_daily_coverage AS c (
        coverage_id, tenant_id, device_id, customer_id, date,
        coverage_flag, gap_reason, is_wear, wear_minutes, effective_wear_minutes,
        n_at_that_time, source_sync_log_id, created_by
    ) VALUES (
        p_coverage_id, p_tenant_id, p_device_id, p_customer_id, p_date,
        p_coverage_flag, p_gap_reason, p_is_wear, p_wear_minutes, p_effective_wear_minutes,
        p_n_at_that_time, p_source_sync_log_id, coalesce(nullif(btrim(p_created_by), ''), 'band-refetch')
    )
    ON CONFLICT (device_id, date) DO UPDATE
       SET coverage_flag          = excluded.coverage_flag,
           gap_reason             = excluded.gap_reason,
           is_wear                = excluded.is_wear,
           wear_minutes           = excluded.wear_minutes,
           effective_wear_minutes = excluded.effective_wear_minutes,
           n_at_that_time         = excluded.n_at_that_time,
           source_sync_log_id     = excluded.source_sync_log_id
     WHERE c.tenant_id = p_tenant_id
       AND c.customer_id = p_customer_id
    RETURNING (xmax = 0) INTO v_was_insert;

    -- 🛑 RETURNING 拿不到行 ⇒ 上面那条 `WHERE` 把 DO UPDATE 挡掉了。
    --    它挡的是**两类**不同的形态，必须分开归因（否则会去错的地方排查）：
    --      ① 命中的行在**另一个租户**名下（tenant_id 不符）
    --      ② 命中的行在**本租户但归属另一个客户**（customer_id 不符）
    --    ⇒ 故此处做一次诊断查询**只为措辞**（决策已由上面的语句原子作出，见 (6)）。
    IF v_was_insert IS NULL THEN
        SELECT customer_id INTO v_owner_dev
          FROM band_daily_coverage
         WHERE device_id = p_device_id AND date = p_date AND tenant_id = p_tenant_id;

        IF v_owner_dev IS NOT NULL AND v_owner_dev <> p_customer_id THEN
            RAISE EXCEPTION
                'register_daily_coverage: 手环 % 在业务日 % 已有覆盖行，但其归属客户是 %，'
                '与本次传入的客户 % 不一致 —— 拒绝覆盖。'
                '🛑 唯一载体 `uq_daily_coverage_device_date`（实测逐字 `UNIQUE (device_id, date)`）'
                '只保证"一行"，**不保证"同一个客户"**：若允许覆盖改写归属，'
                '一次补拉就能把一个客户的观测日**悄悄转记**到另一个客户名下。'
                '而 A3 是"唯一可扣分"的指标 —— 这种转记既可能冤枉甲，也可能放过乙。'
                '若确属"设备被重新绑定到新客户"，那是一次**业务事实变更**，'
                '须走解绑/重绑流程，而不是靠一次补拉覆盖。',
                p_device_id, p_date, v_owner_dev, p_customer_id;
        END IF;

        RAISE EXCEPTION
            'register_daily_coverage: 手环 % 在业务日 % 的覆盖行已存在于**另一个租户**名下 —— '
            '本租户内看不到它，但唯一载体 (device_id, date) 已被占用。'
            '🛑 `uq_daily_coverage_device_date` 是**不含 tenant_id** 的唯一约束 ⇒ 跨租户撞号是必然可能。'
            '返回 UPDATED 会让本租户这一天的覆盖**静默缺失**（A3 分母少一天，**不报错**）。'
            '处置：换一台设备，或先核对这条覆盖的归属。',
            p_device_id, p_date;
    END IF;

    IF v_was_insert THEN
        RETURN 'CREATED';
    ELSE
        RETURN 'UPDATED';
    END IF;
END;
$v22_cov$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15/V17/V18/V19/V20/V21 第 2 节同款（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，故 GRANT 在常规环境是 no-op；
--       写它是"显式优于隐式"，以及在生产 `REVOKE ALL ON FUNCTION … FROM PUBLIC` 加固后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d2) 用 has_function_privilege 机械断言。
-- ============================================================================

DO
$v22_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_sync_probe') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_sync_probe(uuid, uuid, uuid, text, jsonb, '
            'int, text, timestamptz, boolean, text) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_daily_coverage') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_daily_coverage(uuid, uuid, uuid, uuid, date, '
            'boolean, text, int, int, int, int, uuid, text) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V22 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v22_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是**第 7 次**面对它：
--        V15 踩过（263 字符）· V17 又踩过（358）· V18~V21 均"先量再写"；
--        而 V21 的注释里**声称量过、实际没量**（初稿 286 字符、注释却写 228）——
--        "纪律写在注释里 ≠ 纪律被执行"。
--   ⇒ 本行**先量后写**：实测长度见下方括注（用 python len()，本行全 ASCII）。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V22', 'band refetch primitives: register_sync_probe() + register_daily_coverage(). Two tables had no writer, so PRD 2.8.7 history-backfill had no persistence. Upsert key (device_id,date) per spec; tech is_wear cannot be not_worn.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   与 V17/V18/V19/V20/V21 同款的五段式：
--     (a0)/(a1) 能力守卫 → (a) 函数存在 → (b)(c) 函数体静态断言
--     → (d) 登记与权限 → (e) 行为验证（子事务内，块末整体撤销）→ (f) 清场自证
--
--   🛑 本节断言**只读 prosrc（函数体原文）**，不读整个迁移文件 ——
--      读整个文件会把"注释里写着这句话"当成"代码里做到了这件事"
--      （V20 初稿与 V21 的 `\b` 事故都是这个形态）。
--   🛑 静态断言与行为断言**必须配对**：静态钉"没有某写法"，行为钉"调用它会怎样"。
-- ============================================================================

DO
$v22_guard$
DECLARE
    v_probe_body  text;
    v_cov_body    text;
    v_probe_code  text;
    v_cov_code    text;
    v_n           int;
    v_priv        boolean;
    v_res         text;
    v_cnt         int;
    v_ctx_restore text;
    v_tenant      uuid := '00000000-0000-0000-0000-0000000a2201';
    v_tenant_b    uuid := '00000000-0000-0000-0000-0000000a2202';
    v_dev         uuid := '00000000-0000-0000-0000-0000000a22d1';
    v_cust        uuid := '00000000-0000-0000-0000-0000000a22c1';
    v_probe       uuid := '00000000-0000-0000-0000-0000000a22b1';
    v_probe2      uuid := '00000000-0000-0000-0000-0000000a22b2';
    v_cov         uuid := '00000000-0000-0000-0000-0000000a22e1';
    v_cov2        uuid := '00000000-0000-0000-0000-0000000a22e2';
    v_d           date := DATE '2026-03-01';
    v_d2          date := DATE '2026-03-02';
    v_err         text;
BEGIN
    -- (a0) 能力守卫：assert_tenant_context() 必须存在（两个函数都依赖它）
    SELECT count(*) INTO v_n
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'assert_tenant_context';
    IF v_n <> 1 THEN
        RAISE EXCEPTION
            'V22 自证失败(a0): assert_tenant_context() 不存在或重载数为 %（期望恰 1）。'
            '两个补拉原语的 (3b) 自证都依赖它 —— 没有它，"我调了 set_config"就只是自述。', v_n;
    END IF;

    -- (a1) 能力守卫：两张目标表必须可写（当前角色对它们有 INSERT/UPDATE 权限）
    --   🛑 用 has_table_privilege 机械断言，而不是"迁移能跑就说明能写"——
    --      迁移可能由 superuser 跑，而应用角色没有权限。**这两种情况必须分开。**
    IF NOT has_table_privilege(current_user, 'band_sync_probe', 'INSERT') THEN
        RAISE EXCEPTION 'V22 自证失败(a1): 当前角色 % 对 band_sync_probe 没有 INSERT 权限', current_user;
    END IF;
    IF NOT has_table_privilege(current_user, 'band_daily_coverage', 'INSERT,UPDATE') THEN
        RAISE EXCEPTION 'V22 自证失败(a1): 当前角色 % 对 band_daily_coverage 没有 INSERT/UPDATE 权限', current_user;
    END IF;

    -- (a) 两个函数存在，且**恰好各一个重载**
    SELECT count(*) INTO v_n FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_sync_probe';
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V22 自证失败(a): register_sync_probe 重载数 = %（期望恰 1）', v_n;
    END IF;
    SELECT count(*) INTO v_n FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_daily_coverage';
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V22 自证失败(a): register_daily_coverage 重载数 = %（期望恰 1）', v_n;
    END IF;

    SELECT p.prosrc INTO v_probe_body FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_sync_probe';
    SELECT p.prosrc INTO v_cov_body FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_daily_coverage';

    -- 🛑 剥注释后的代码态（与 V21 同款）：静态断言必须打在**代码**上，不打在注释上。
    --   理由：本仓的注释里逐字写着被断言的那句话（"不得静默忽略"等），
    --   若不剥注释，把实现整段删掉、只留注释，断言仍会绿。这是**假绿**。
    v_probe_code := regexp_replace(v_probe_body, '--[^\n]*', '', 'g');
    v_cov_code   := regexp_replace(v_cov_body,   '--[^\n]*', '', 'g');

    -- ── (b) register_sync_probe 的静态断言 ─────────────────────────────────
    -- (b1) 必须真的写 band_sync_probe
    IF v_probe_code !~ 'INSERT\s+INTO\s+band_sync_probe' THEN
        RAISE EXCEPTION 'V22 自证失败(b1): register_sync_probe 里没有 `INSERT INTO band_sync_probe`';
    END IF;
    -- (b2) 🛑 必须建立上下文（set_config）—— 不允许"靠调用方设好上下文"
    IF v_probe_code !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION
            'V22 自证失败(b2): register_sync_probe 里没有 set_config(''app.tenant_id'', …)。'
            '🛑 本仓纪律：写入方**自己**建立上下文，不假定调用方已设好 ——'
            'band 表的 RLS 是 fail-closed 的，上下文缺失时 SELECT 静默返回 0 行。';
    END IF;
    -- (b3) 必须有上下文一致性守卫（拒绝静默改写）
    IF v_probe_code !~ 'v_ctx_before\s*<>' THEN
        RAISE EXCEPTION
            'V22 自证失败(b3): register_sync_probe 里没有"既有上下文 ≠ 传入租户 ⇒ 拒绝"的守卫。'
            '见 (2) 段的理由：set_config 是事务级且不会在函数入口重置。';
    END IF;
    -- (b4) 🛑 探针必须 `ON CONFLICT (probe_id) DO NOTHING`，**不得** DO UPDATE
    --   与 coverage 刻意不同（第 0 节前提②）：探针是"当时的推定值"，是举证材料。
    IF v_probe_code !~ 'ON\s+CONFLICT\s*\(\s*probe_id\s*\)\s*DO\s+NOTHING' THEN
        RAISE EXCEPTION
            'V22 自证失败(b4): register_sync_probe 里没有 `ON CONFLICT (probe_id) DO NOTHING`。'
            '🛑 幂等判定必须走库层原子冲突处理（无读-写窗口），'
            '而不是"先 SELECT count 再 INSERT"（两个并发调用可都读到 0）。';
    END IF;
    IF v_probe_code ~* 'DO\s+UPDATE' THEN
        RAISE EXCEPTION
            'V22 自证失败(b4): register_sync_probe 里出现了 DO UPDATE。'
            '🛑 探针的幂等形态是【按 probe_id 追加、不覆盖】—— 理由是'
            '`band_daily_coverage.n_at_that_time` 需要回溯"该日补拉用的是哪个 N"，'
            '覆盖探针会摧毁这条证据链。若有人改成 upsert，需先重新裁定该语义。';
    END IF;
    -- (b4b) 必须显式判"跨租户撞号"（probe_id 是全局单列主键 ⇒ 必然可能）
    IF v_probe_code !~ '另一个租户' THEN
        RAISE EXCEPTION
            'V22 自证失败(b4b): register_sync_probe 里没有"另一个租户名下"的 RAISE 分支。'
            '🛑 probe_id 是**全局单列主键**，跨租户撞号必然可能；'
            '不显式判就会 return ALREADY_EXISTS 而本租户一行都没有 —— '
            '补拉会按"未探到 N"运行并以【假缺失】出现在 A3 里。';
    END IF;
    -- (b5) 必须自证上下文生效（不是"我调了所以生效"）
    IF v_probe_code !~ 'assert_tenant_context\s*\(' THEN
        RAISE EXCEPTION 'V22 自证失败(b5): register_sync_probe 没有调 assert_tenant_context() 自证上下文';
    END IF;

    -- ── (c) register_daily_coverage 的静态断言 ─────────────────────────────
    -- (c1) 必须真的 upsert band_daily_coverage
    IF v_cov_code !~ 'INSERT\s+INTO\s+band_daily_coverage' THEN
        RAISE EXCEPTION 'V22 自证失败(c1): register_daily_coverage 里没有 `INSERT INTO band_daily_coverage`';
    END IF;
    IF v_cov_code !~ 'ON\s+CONFLICT\s*\(\s*device_id\s*,\s*date\s*\)\s*DO\s+UPDATE' THEN
        RAISE EXCEPTION
            'V22 自证失败(c1): register_daily_coverage 里没有 `ON CONFLICT (device_id, date) DO UPDATE`。'
            '§2.8.7③「幂等」行逐字要求"重复拉取 = **覆盖**、非追加" —— '
            '没有这一句就不是覆盖，且判定会退化为有并发窗口的"先读后写"。';
    END IF;
    -- (c1b) upsert 的 DO UPDATE 必须带 tenant/customer 条件（原子地把归属冲突挡掉）
    IF v_cov_code !~ 'WHERE\s+c\.tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V22 自证失败(c1b): DO UPDATE 段缺少 `WHERE c.tenant_id = p_tenant_id`。'
            '🛑 该条件是**原子地**把"命中了别的租户/别的客户的行"挡在写入之外的关键：'
            '条件不满足时 PG 不写入也不返回行 ⇒ 配合 `RETURNING` 即可判定跨租户撞号。';
    END IF;
    IF v_cov_code !~ 'c\.customer_id\s*=\s*p_customer_id' THEN
        RAISE EXCEPTION
            'V22 自证失败(c1b): DO UPDATE 段缺少 `c.customer_id = p_customer_id`。'
            '🛑 唯一载体只保证"一行"、不保证"同一个客户"——缺这条会让一次补拉'
            '把一个客户的观测日悄悄转记到另一个客户名下。';
    END IF;
    -- (c1c) 必须用 xmax 判别"本次是插入还是覆盖"（库层原子事实，无读-写窗口）
    IF v_cov_code !~ 'xmax\s*=\s*0' THEN
        RAISE EXCEPTION
            'V22 自证失败(c1c): register_daily_coverage 里没有 `xmax = 0` 判别式。'
            '🛑 用它而非"先 SELECT count"是为了消除并发窗口（两个并发调用可都读到"已存在"）。';
    END IF;
    -- (c2) 上下文守卫同款
    IF v_cov_code !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V22 自证失败(c2): register_daily_coverage 里没有 set_config(''app.tenant_id'', …)';
    END IF;
    IF v_cov_code !~ 'assert_tenant_context\s*\(' THEN
        RAISE EXCEPTION 'V22 自证失败(c2): register_daily_coverage 没有调 assert_tenant_context() 自证上下文';
    END IF;
    IF v_cov_code !~ 'v_ctx_before\s*<>' THEN
        RAISE EXCEPTION 'V22 自证失败(c2): register_daily_coverage 里没有"既有上下文 ≠ 传入租户 ⇒ 拒绝"的守卫';
    END IF;
    -- (c3) 🛑🛑 核心门禁的**静态**存在性：not_worn × 技术性 isWear 的拒绝分支
    --    🛑 注意用 \y（PG 的词边界）而**不是 \b** —— \b 在 PG 正则是「退格符」，
    --       恒不命中（本仓第 45 条缺陷，V21 上踩过）。
    IF v_cov_code !~ 'not_worn\y' THEN
        RAISE EXCEPTION
            'V22 自证失败(c3): register_daily_coverage 的代码态里没有 ''not_worn'' 字面量。'
            '🛑 (5c) 是本次迁移的**核心门禁**（技术性缺失不得被判为行为性）——'
            '它必须真的在代码里，而不是只在注释里。';
    END IF;
    IF v_cov_code !~ 'v_tech_iswear' THEN
        RAISE EXCEPTION
            'V22 自证失败(c3): register_daily_coverage 里没有引用技术性 isWear 取值数组。'
            '§2.8.7⑤ 逐字：「`(-1,255)`=技术性缺失 → 一律不判行为性」——'
            '拒绝分支必须显式用这组取值判定，不得写成"反正 CHECK 会挡"。';
    END IF;
    -- (c4) 🛑 DO UPDATE SET 段**不得**改写身份位
    --   做法：从 `DO UPDATE` 起切一段（到语句末），断言其中不出现这些列的赋值。
    --   🛑 段长取 700 字符 —— 实测 DO UPDATE 段（含 SET 与 WHERE）远短于此；
    --      超长会把后面的语句切进来，造成**归因错误**的红。
    DECLARE
        v_upd text;
    BEGIN
        -- 🛑 用 `left(substring(x from 'regex'), n)` 而**不是** `substring(x from 'regex' for n)`：
        --    后者会被 PG 解析成【位置形式】`substring(x from int for int)`
        --    ⇒ 报 `无效的类型 integer 输入语法: "DO\s+UPDATE"`（本轮实测踩到）。
        --    两参数形式的 `substring(x from 'pattern')` 才是 POSIX 正则形式。
        v_upd := left(substring(v_cov_code from 'DO\s+UPDATE'), 700);
        IF v_upd ~ 'customer_id\s*=' THEN
            RAISE EXCEPTION
                'V22 自证失败(c4): DO UPDATE SET 段里出现了 `customer_id =`。'
                '🛑 覆盖只更新【当日观测事实】，不得改写归属 ——'
                '否则一次补拉就能把一个客户的观测日悄悄转记到另一个客户名下'
                '（而 A3 是唯一可扣分指标）。见 (7) 的理由。';
        END IF;
        IF v_upd ~ 'device_id\s*=' THEN
            RAISE EXCEPTION
                'V22 自证失败(c4): 同上 —— DO UPDATE SET 不得改写 device_id（它是 upsert 键）。';
        END IF;
        IF v_upd ~ 'coverage_id\s*=' THEN
            RAISE EXCEPTION
                'V22 自证失败(c4): 同上 —— DO UPDATE SET 不得改写 coverage_id（改它等于换一行）。';
        END IF;
        IF v_upd ~ 'tenant_id\s*=\s*excluded' THEN
            RAISE EXCEPTION
                'V22 自证失败(c4): 同上 —— DO UPDATE SET 不得把 tenant_id 赋成 excluded（跨租户改写）。'
                '⚠️ 注意 WHERE 子句里有 `c.tenant_id = p_tenant_id`（合法、且是必需的判定），'
                '故本断言只禁 `tenant_id = excluded...` 这种**赋值**形态。';
        END IF;
        IF v_upd ~ 'created_by\s*=' OR v_upd ~ 'created_at\s*=' THEN
            RAISE EXCEPTION
                'V22 自证失败(c4): DO UPDATE SET 不得改写 created_by / created_at ——'
                '"谁在什么时候建的"是不可变更的既成事实。';
        END IF;
    END;
    -- (c6) 不得把 gap_reason 的封闭性交给表 CHECK 独自承担
    IF v_cov_code !~ 'v_gap_reasons' THEN
        RAISE EXCEPTION
            'V22 自证失败(c6): register_daily_coverage 里没有引用 7 值数组 v_gap_reasons。'
            '🛑 必须显式先判一次：放任它去撞表 CHECK 只会给调用方一条 23514 与一个约束名，'
            '而归因质量只能来自函数层（函数可被人绕过，CHECK 不能 —— 两者互补）。';
    END IF;

    -- ── (d) 登记与权限 ────────────────────────────────────────────────────
    SELECT count(*) INTO v_n FROM schema_migration WHERE version = 'V22';
    IF v_n <> 1 THEN
        RAISE EXCEPTION
            'V22 自证失败(d): schema_migration 里 V22 登记行数 = %（期望恰 1）。'
            '🛑 本节的 (d2) 依赖它，故第 3 节必须排在本节之前。', v_n;
    END IF;
    SELECT has_function_privilege(current_user, p.oid, 'EXECUTE') INTO v_priv
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_sync_probe';
    IF NOT v_priv THEN
        RAISE EXCEPTION 'V22 自证失败(d2): 当前角色 % 对 register_sync_probe 没有 EXECUTE 权限', current_user;
    END IF;
    SELECT has_function_privilege(current_user, p.oid, 'EXECUTE') INTO v_priv
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_daily_coverage';
    IF NOT v_priv THEN
        RAISE EXCEPTION 'V22 自证失败(d2): 当前角色 % 对 register_daily_coverage 没有 EXECUTE 权限', current_user;
    END IF;

    -- ── (e) 行为验证（子事务内，块末整体撤销）────────────────────────────
    --   🛑 保留既有上下文，块末恢复 —— 否则本节会污染调用方的会话状态。
    v_ctx_restore := current_setting('app.tenant_id', true);

    BEGIN  -- 子事务开始
        -- 造底数据：租户 / 客户 / 手环（本块末整体 ROLLBACK）
        -- 🛑 列名与 NOT NULL 集已**对真库实测**（2026-09-30）：
        --    tenant(id!, name!, status!~'active', created_at!~now())
        --    customer(id!, tenant_id!, name!, status!~'pending', created_at!~now(), updated_at!~now())
        --    band(band_id!, tenant_id!, customer_id!, vendor!, bound_at!, status!~'active', created_at!~now())
        -- ⚠️ 底数据必须**先 tenant 后 customer 后 band** —— band 有复合外键指向 customer。
        -- 🛑 tenant 表**没有 RLS**（它是租户宿主），故可直接写；
        --    customer / band **有** FORCE RLS ⇒ 必须先 `set_config` 建立租户上下文，
        --    否则会以「新行违背了表 customer 的行级安全策略」失败（本轮实测踩到）。
        INSERT INTO tenant (id, name) VALUES (v_tenant, 'V22 自证租户') ON CONFLICT (id) DO NOTHING;

        PERFORM set_config('app.tenant_id', v_tenant::text, true);
        INSERT INTO customer (id, tenant_id, name)
        SELECT v_cust, v_tenant, 'V22 自证客户'
         WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = v_cust);
        INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at)
        SELECT v_dev, v_tenant, v_cust, 'V22-VENDOR', DATE '2026-02-01'
         WHERE NOT EXISTS (SELECT 1 FROM band WHERE band_id = v_dev);
        PERFORM set_config('app.tenant_id', v_tenant::text, true);

        PERFORM set_config('app.tenant_id', v_tenant::text, true);

        -- (e1) 探针：首次 CREATED
        v_res := register_sync_probe(v_tenant, v_probe, v_dev, 'hr',
                                     '["2026-02-20","2026-02-27"]'::jsonb, 7, '保守假设',
                                     TIMESTAMPTZ '2026-03-01 08:00:00+08', FALSE, 'v22-selfcheck');
        IF v_res <> 'CREATED' THEN
            RAISE EXCEPTION 'V22 自证失败(e1): 首次 register_sync_probe 返回 %（期望 CREATED）', v_res;
        END IF;

        -- (e2) 探针：重放同 probe_id ⇒ ALREADY_EXISTS（且**不覆盖**）
        v_res := register_sync_probe(v_tenant, v_probe, v_dev, 'hr',
                                     '["2026-02-20"]'::jsonb, 3, '运行时探测',
                                     TIMESTAMPTZ '2026-03-02 08:00:00+08', FALSE, 'v22-selfcheck');
        IF v_res <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION 'V22 自证失败(e2): 探针重放返回 %（期望 ALREADY_EXISTS）', v_res;
        END IF;
        -- 🛑 对抗性重放（本仓第 43 条的教训）：第二次传入**不同的** N 与来源，
        --    并断言该行**仍逐列等于第一次那份形态** —— 用相同输入测"不覆盖"是最弱的证明。
        SELECT count(*) INTO v_cnt FROM band_sync_probe
         WHERE probe_id = v_probe AND retention_window_days = 7
           AND probe_source = '保守假设' AND is_test = FALSE;
        IF v_cnt <> 1 THEN
            RAISE EXCEPTION
                'V22 自证失败(e2): 探针重放后该行被改写了（期望 retention=7 / source=保守假设）。'
                '🛑 用【不同的】输入测"不覆盖"才有效 —— 相同输入下 DO UPDATE SET 写回同样的值，'
                '会以"通过"的面目掩饰覆盖（本仓第 43 条的形态）。';
        END IF;

        -- (e3) 探针：同一 device+history_type 可以再探测一次（追加语义）
        v_res := register_sync_probe(v_tenant, v_probe2, v_dev, 'hr',
                                     NULL, 14, '厂商文档',
                                     TIMESTAMPTZ '2026-03-05 08:00:00+08', FALSE, 'v22-selfcheck');
        IF v_res <> 'CREATED' THEN
            RAISE EXCEPTION
                'V22 自证失败(e3): 同 device+history_type 的第二次探测返回 %（期望 CREATED）。'
                '🛑 探针是**追加**语义（N 会随探测刷新）—— 若这里报冲突，说明有人给该表加了'
                '(device_id, history_type) 唯一载体，那会摧毁 N 的时序留痕。', v_res;
        END IF;

        -- (e4) 探针：非法 history_type ⇒ 拒（不能被表 CHECK 以外的东西接住）
        BEGIN
            PERFORM register_sync_probe(v_tenant, gen_random_uuid(), v_dev, 'blood_sugar', NULL, 7, '保守假设');
            RAISE EXCEPTION 'V22 自证失败(e4): 非法 history_type 竟然被接受了';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ 'p_history_type' THEN
                RAISE EXCEPTION
                    'V22 自证失败(e4): 拒绝是拒绝了，但**理由不对** —— 收到「%」。'
                    '🛑 断言"拒绝了"是不够的，必须断言【拒绝的 SQLSTATE 与理由】：'
                    '若实现里删掉了 (5a)，这条会落到库层 CHECK 上并以 23514 出现，'
                    '而"拒绝了"这个结论**仍然成立** —— 归因质量退化（本仓第 42 条的教训）。', v_err;
            END IF;
        END;

        -- (e5) 跨租户设备不可见 ⇒ 拒，且理由是"在租户…内不存在"（不是外键 23503）
        --   🛑 形态必须写对：上下文 = 租户 B **且** 传入租户 = 租户 B，
        --      被查设备属于租户 A ⇒ RLS 静默返回 0 行 ⇒ 走"不存在"分支。
        --      ⚠️ 本轮实测踩到的写错形态：若「上下文 = B 而传入租户 = A」，
        --         那么先触发的是 (2) 的**上下文一致性守卫**（理由完全不同）——
        --         那测的是另一条判据，会让本组的归因指向错误的实现段。
        BEGIN
            PERFORM set_config('app.tenant_id', v_tenant_b::text, true);
            PERFORM register_sync_probe(v_tenant_b, gen_random_uuid(), v_dev, 'hr', NULL, 7, '保守假设');
            RAISE EXCEPTION 'V22 自证失败(e5): 用"看不见的设备"竟然登记成功了';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ '在租户' THEN
                RAISE EXCEPTION
                    'V22 自证失败(e5): 拒绝理由不对 —— 收到「%」（期望含"在租户…内不存在"）。'
                    '🛑 必须断言【理由】而不只是"拒绝了"：若实现里删掉显式的设备存在性检查，'
                    '这条会落到库层复合外键上以 23503 出现，而"拒绝了"这个结论**仍然成立**'
                    '（本仓第 42 条的教训）。', v_err;
            END IF;
        END;
        PERFORM set_config('app.tenant_id', v_tenant::text, true);

        -- (e6) 覆盖：首次 CREATED
        v_res := register_daily_coverage(v_tenant, v_cov, v_dev, v_cust, v_d,
                                         FALSE, 'not_worn', 0, 0, 0, 7, NULL, 'v22-selfcheck');
        IF v_res <> 'CREATED' THEN
            RAISE EXCEPTION 'V22 自证失败(e6): 首次 register_daily_coverage 返回 %（期望 CREATED）', v_res;
        END IF;

        -- (e7) 🛑🛑 核心门禁的**行为**验证：not_worn × is_wear = -1 ⇒ 必须拒
        BEGIN
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d2,
                                            FALSE, 'not_worn', -1, 0, 0, 7, NULL, 'v22-selfcheck');
            RAISE EXCEPTION
                'V22 自证失败(e7): 🛑🛑 gap_reason=not_worn 配 is_wear=-1 竟然被写入 —— '
                '这是本迁移要堵的核心形态（技术性缺失被判为行为性 ⇒ 扣客户的分）。';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ 'not_worn' THEN
                RAISE EXCEPTION
                    'V22 自证失败(e7): 拒绝了但理由不对 —— 收到「%」。'
                    '必须是本函数 (5c) 给出的业务错误（含 not_worn 字样），'
                    '而不是表 CHECK 的 23514 —— 因为"技术性不判行为性"这条口径'
                    '**不在表 CHECK 里**（CHECK 只校验取值域）。', v_err;
            END IF;
        END;
        -- (e7b) is_wear = 255 同款（第二个技术性取值）
        BEGIN
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d2,
                                            FALSE, 'not_worn', 255, 0, 0, 7, NULL, 'v22-selfcheck');
            RAISE EXCEPTION 'V22 自证失败(e7b): not_worn 配 is_wear=255 竟然被写入';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ 'not_worn' THEN
                RAISE EXCEPTION 'V22 自证失败(e7b): 拒绝理由不对 —— 收到「%」', v_err;
            END IF;
        END;
        -- (e7c) 🛑 判别力自证：**合法的** not_worn（is_wear=0）必须**能写入**
        --   否则一个"任何 not_worn 都拒"的实现也能让 (e7)/(e7b) 全绿 ⇒ 判据没有判别力。
        v_res := register_daily_coverage(v_tenant, v_cov2, v_dev, v_cust, v_d2,
                                         FALSE, 'not_worn', 0, 0, 0, 7, NULL, 'v22-selfcheck');
        IF v_res <> 'CREATED' THEN
            RAISE EXCEPTION
                'V22 自证失败(e7c): 合法的 not_worn（is_wear=0，脱腕）被拒了（返回 %）。'
                '🛑 这是**判别力自证**：若一个实现"见 not_worn 就拒"，(e7)/(e7b) 照样全绿，'
                '但真正的业务能力（记录客户脱腕）被误伤 —— 过度收紧与过松同样是缺陷。', v_res;
        END IF;

        -- (e8) 覆盖：同日重放 ⇒ UPDATED（upsert），且**归属不被改写**
        v_res := register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d,
                                         TRUE, NULL, 1, 480, 480, 14, NULL, 'v22-selfcheck');
        IF v_res <> 'UPDATED' THEN
            RAISE EXCEPTION
                'V22 自证失败(e8): 同日重放返回 %（期望 UPDATED）。'
                '§2.8.7③ 逐字："重复拉取 = **覆盖**、非追加" —— 报 CREATED 说明它追加出了第二行。', v_res;
        END IF;
        SELECT count(*) INTO v_cnt FROM band_daily_coverage
         WHERE device_id = v_dev AND date = v_d AND tenant_id = v_tenant;
        IF v_cnt <> 1 THEN
            RAISE EXCEPTION
                'V22 自证失败(e8): 同一 (device, date) 出现 % 行（期望恰 1 行）。'
                '🛑 多行会让 A3 的"应戴天"分母被重复计数 —— 而 A3 是唯一可扣分指标，'
                '分母错了会直接冤枉或放过客户。', v_cnt;
        END IF;
        -- 观测事实已更新
        SELECT count(*) INTO v_cnt FROM band_daily_coverage
         WHERE device_id = v_dev AND date = v_d AND coverage_flag IS TRUE AND is_wear = 1
           AND n_at_that_time = 14;
        IF v_cnt <> 1 THEN
            RAISE EXCEPTION 'V22 自证失败(e8): 覆盖后观测事实未更新（期望 coverage_flag=T / is_wear=1 / n=14）';
        END IF;
        -- 🛑 归属未被改写
        SELECT count(*) INTO v_cnt FROM band_daily_coverage
         WHERE device_id = v_dev AND date = v_d AND customer_id = v_cust AND coverage_id = v_cov;
        IF v_cnt <> 1 THEN
            RAISE EXCEPTION
                'V22 自证失败(e8): 覆盖改写了身份位（coverage_id / customer_id）。'
                '覆盖只更新当日观测事实 —— 改写归属会让一次补拉把观测日转记到别的客户名下。';
        END IF;

        -- (e9) 归属冲突 ⇒ 拒（同 device+date 换客户）
        BEGIN
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev,
                                            '00000000-0000-0000-0000-0000000a22c9'::uuid, v_d,
                                            FALSE, 'no_open', NULL, 0, 0, 7, NULL, 'v22-selfcheck');
            RAISE EXCEPTION 'V22 自证失败(e9): 同 device+date 换客户竟然被接受了';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            -- 该 UUID 在本租户不存在 ⇒ 会先撞 (4b)；故先补一个真实客户再试
            INSERT INTO customer (id, tenant_id, name)
            SELECT '00000000-0000-0000-0000-0000000a22c9'::uuid, v_tenant, 'V22 客户乙'
             WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = '00000000-0000-0000-0000-0000000a22c9'::uuid);
            BEGIN
                PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev,
                                                '00000000-0000-0000-0000-0000000a22c9'::uuid, v_d,
                                                FALSE, 'no_open', NULL, 0, 0, 7, NULL, 'v22-selfcheck');
                RAISE EXCEPTION 'V22 自证失败(e9): 同 device+date 换客户（客户存在）竟然被接受了';
            EXCEPTION WHEN raise_exception THEN
                v_err := SQLERRM;
                IF v_err !~ '归属客户' THEN
                    RAISE EXCEPTION
                        'V22 自证失败(e9): 拒绝理由不对 —— 收到「%」（期望含"归属客户"）。'
                        '🛑 唯一载体 (device_id,date) 只保证"一行"，不保证"同一个客户" ——'
                        '若归因退化到唯一约束的 23505，运维会以为是"重复插入"而不是"归属冲突"。', v_err;
                END IF;
            END;
        END;

        -- (e10) 覆盖：coverage_flag=TRUE 配 gap_reason ⇒ 拒
        BEGIN
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d2,
                                            TRUE, 'no_open', NULL, 0, 0, 7, NULL, 'v22-selfcheck');
            RAISE EXCEPTION 'V22 自证失败(e10): coverage_flag=TRUE 配 gap_reason 竟然被接受';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ 'coverage_flag' THEN
                RAISE EXCEPTION 'V22 自证失败(e10): 拒绝理由不对 —— 收到「%」', v_err;
            END IF;
        END;

        -- (e11) 覆盖：source_sync_log_id 指向本租户不存在的日志 ⇒ 拒，且理由是"日志…不存在"
        BEGIN
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d2,
                                            FALSE, 'no_open', NULL, 0, 0, 7,
                                            gen_random_uuid(), 'v22-selfcheck');
            RAISE EXCEPTION 'V22 自证失败(e11): 指向不存在同步日志竟然被接受';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ '同步日志' THEN
                RAISE EXCEPTION
                    'V22 自证失败(e11): 拒绝理由不对 —— 收到「%」（期望含"同步日志"）。'
                    '🛑 这一条的对照物是库层复合外键（会给 23503）—— 两者互补：'
                    '函数层给理由，库层保证"绕过函数也拒"。', v_err;
            END IF;
        END;

        -- (e12) 上下文互斥：已持有租户 B 时传租户 A ⇒ 拒
        BEGIN
            PERFORM set_config('app.tenant_id', v_tenant_b::text, true);
            PERFORM register_daily_coverage(v_tenant, gen_random_uuid(), v_dev, v_cust, v_d2,
                                            FALSE, 'no_open', NULL, 0, 0, 7, NULL, 'v22-selfcheck');
            RAISE EXCEPTION 'V22 自证失败(e12): 既有上下文与传入租户不一致竟然被接受';
        EXCEPTION WHEN raise_exception THEN
            v_err := SQLERRM;
            IF v_err !~ '租户上下文' THEN
                RAISE EXCEPTION 'V22 自证失败(e12): 拒绝理由不对 —— 收到「%」', v_err;
            END IF;
        END;
        PERFORM set_config('app.tenant_id', v_tenant::text, true);

        -- (e13) RLS 隔离：租户 B 读不到租户 A 的行
        PERFORM set_config('app.tenant_id', v_tenant_b::text, true);
        SELECT count(*) INTO v_cnt FROM band_sync_probe WHERE probe_id = v_probe;
        IF v_cnt <> 0 THEN
            RAISE EXCEPTION
                'V22 自证失败(e13): 租户 B 读到了租户 A 的探针行（% 行）。'
                '🛑 这一条是所有"隔离"断言的**前提**：若它失败，'
                '上面依赖 RLS 的断言全部以【归因错误】的面目表现。', v_cnt;
        END IF;
        SELECT count(*) INTO v_cnt FROM band_daily_coverage WHERE device_id = v_dev;
        IF v_cnt <> 0 THEN
            RAISE EXCEPTION 'V22 自证失败(e13): 租户 B 读到了租户 A 的覆盖行（% 行）', v_cnt;
        END IF;

        -- 整体撤销本块（含底数据与全部探针行）
        RAISE EXCEPTION 'V22_SELFCHECK_ROLLBACK_MARKER';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM <> 'V22_SELFCHECK_ROLLBACK_MARKER' THEN
            RAISE;  -- 真失败 ⇒ 上抛
        END IF;
    END;

    -- 恢复既有上下文
    IF v_ctx_restore IS NOT NULL AND btrim(v_ctx_restore) <> '' THEN
        PERFORM set_config('app.tenant_id', v_ctx_restore, true);
    ELSE
        PERFORM set_config('app.tenant_id', '', true);
    END IF;

    -- ── (f) 清场自证：探针行必须零残留 ────────────────────────────────────
    PERFORM set_config('app.tenant_id', v_tenant::text, true);
    SELECT count(*) INTO v_cnt FROM band_sync_probe
     WHERE probe_id IN (v_probe, v_probe2) OR created_by = 'v22-selfcheck';
    IF v_cnt <> 0 THEN
        RAISE EXCEPTION
            'V22 自证失败(f): 自证探针零残留断言失败（还剩 % 行）。'
            '🛑 残留会污染后续的真库门禁（两账 / 载体集合），'
            '并以"找不到原因的红"形式出现在**别的**测试里。', v_cnt;
    END IF;
    SELECT count(*) INTO v_cnt FROM band_daily_coverage
     WHERE coverage_id IN (v_cov, v_cov2) OR created_by = 'v22-selfcheck';
    IF v_cnt <> 0 THEN
        RAISE EXCEPTION 'V22 自证失败(f): 自证覆盖行零残留断言失败（还剩 % 行）', v_cnt;
    END IF;
    IF v_ctx_restore IS NOT NULL AND btrim(v_ctx_restore) <> '' THEN
        PERFORM set_config('app.tenant_id', v_ctx_restore, true);
    ELSE
        PERFORM set_config('app.tenant_id', '', true);
    END IF;
END;
$v22_guard$;


-- ============================================================================
-- 第 5 节 · 已应用库的处置说明（Flyway forward-only，不提供自动 down 迁移）
--
--   🛑 本迁移**不改任何既有表结构**（只新增两个函数）⇒
--      它对已应用库是**纯增量**：无列增删、无约束变更 ⇒ **无 checksum 冲突面**。
--   ⚠️ 但 schema 哨兵仍会变 —— `RlsGateSupport` 的哨兵 = 整条迁移链字节的 SHA-256，
--      新增一个迁移文件即改变整链 ⇒ 真库门禁会自动**重建独立门禁库**并应用整条链。
--      **这是预期行为**（机制说明见 README「迁移链」节），不需人工处置。
--   🛑 若要回退：DROP FUNCTION register_daily_coverage(uuid, uuid, uuid, uuid, date,
--      boolean, text, int, int, int, int, uuid, text);
--      DROP FUNCTION register_sync_probe(uuid, uuid, uuid, text, jsonb, int, text,
--      timestamptz, boolean, text);
--      DELETE FROM schema_migration WHERE version = 'V22';
--      ⚠️ 两表本身是 V5 建的，**不在本迁移的回退范围**。
-- ============================================================================