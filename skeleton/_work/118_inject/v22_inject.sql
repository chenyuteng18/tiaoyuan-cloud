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
    IF FALSE THEN
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