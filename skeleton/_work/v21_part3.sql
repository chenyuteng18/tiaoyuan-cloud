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

    -- ---- (b1)(b2)(b3) 逐函数检查三件套（建立上下文 / 自证 / 一致性守卫）----
    --   🛑 这三条**必须对【两个】函数都查**，包括那个只读函数。
    --     理由：本仓在 V17 的读侧原语上付出过代价 —— "只读所以不需要守卫"
    --     是一个很容易犯的错，而它的后果是**静默返回 NULL**（看起来像"未签"）。
    IF v_reg_body !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b1): register_agreement 未建立 app.tenant_id 上下文';
    END IF;
    IF v_reg_body !~ 'assert_tenant_context\s*\(\s*\)' THEN
        RAISE EXCEPTION 'V21 自证失败(b2): register_agreement 未调用 assert_tenant_context() 自证上下文';
    END IF;
    IF v_reg_body !~ 'current_setting\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b3): register_agreement 缺上下文一致性守卫（未读既有 app.tenant_id）';
    END IF;
    IF v_lat_body !~ 'set_config\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b1): latest_agreement_of 未建立 app.tenant_id 上下文'
            '—— 它是读函数，但 agreement 是 FORCE RLS ⇒ 不建上下文则 USING 恒 false'
            ' ⇒ 恒返回 NULL —— 一个"未签"与"我没设上下文"同形的假绿';
    END IF;
    IF v_lat_body !~ 'assert_tenant_context\s*\(\s*\)' THEN
        RAISE EXCEPTION 'V21 自证失败(b2): latest_agreement_of 未调用 assert_tenant_context()';
    END IF;
    IF v_lat_body !~ 'current_setting\s*\(\s*''app\.tenant_id''' THEN
        RAISE EXCEPTION 'V21 自证失败(b3): latest_agreement_of 缺上下文一致性守卫'
            '（读函数也会 set_config，也会静默改写调用方上下文）';
    END IF;

    -- ---- (b4) register_agreement 的写入语句存在且目标是 agreement ----
    IF v_reg_body !~ 'INSERT\s+INTO\s+agreement' THEN
        RAISE EXCEPTION 'V21 自证失败(b4): register_agreement 里没有 `INSERT INTO agreement`';
    END IF;

    -- ---- (b5) 该 INSERT 必须带 ON CONFLICT（且在锚点范围内）----
    --   🛑 判定用位置切片：从 INSERT 起取 3000 字符 —— 保证 ON CONFLICT 属于**这条**
    --      INSERT，而不是碰巧在函数体别处出现（本仓在 V18 上用过同一手法）。
    v_slice := substring(v_reg_body from position('INSERT INTO agreement' in v_reg_body) for 3000);
    IF v_slice !~* 'ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V21 自证失败(b5): register_agreement 的 INSERT 没有 ON CONFLICT —— '
            '那意味着重复登记会以 23505 失败，而不是幂等返回 ALREADY_EXISTS。'
            'PRD G1 的取数是"已签客户 / 全部客户"，而离线补录会重复触发同一份协议。';
    END IF;

    -- ---- (b6) 🛑 推断目标必须逐字是【主键列】agreement_id ----
    --   与 V20 的 (b6) 同款，理由相同：推断目标决定"跨租户撞号"是否可能发生，
    --   而本函数的跨租户判定分支**完全建立**在"推断目标是全局单列主键"之上。
    --   🛑 若有人改成 `ON CONFLICT (tenant_id, agreement_id)`，本函数的 RAISE 分支
    --      变成死代码，而自证 (e15) 会以"竟然成功了"的面目失败（归因错误）。
    IF v_slice !~* 'ON\s+CONFLICT\s*\(\s*agreement_id\s*\)' THEN
        RAISE EXCEPTION
            'V21 自证失败(b6): ON CONFLICT 的推断目标不是逐字的 (agreement_id)。'
            '🛑 本函数的核心机制二（跨租户撞号 ⇒ RAISE）建立在这个推断目标上：'
            '正因为它不含 tenant_id，"同一 agreement_id 被别的租户占用"才可能发生。'
            '若改成 (tenant_id, agreement_id)，该冲突不可能发生 ⇒ '
            'RAISE 分支成为死代码，而 (e15) 会以归因错误的方式失败。'
            '🛑 遇到这条报错，正确反应是重推设计，而不是放宽本判据。';
    END IF;

    -- ---- (b7) 🛑🛑 四方签署键集必须**恰 4 个且逐字为这四个** ----
    --   🛑 锚在函数体的 **ARRAY 字面量**上断言，而不是锚在"函数里有 4 个键"这种模糊说法。
    --      作用：若有人把某一方（例如 store_owner）从必填里去掉，
    --      本断言会红 —— 而那是**PRD §八 硬门禁的实质削弱**。
    --      静态防线 = 本条；行为防线 = (e6)。
    IF v_reg_body !~ '''customer''\s*,\s*''meridian_therapist''\s*,\s*'''
                     || '''therapist''\s*,\s*''store_owner''' THEN
        RAISE EXCEPTION
            'V21 自证失败(b7): register_agreement 里四方签署的键集不再是'
            ' 逐字的 [customer, meridian_therapist, therapist, store_owner]。'
            '口径来源 = PRD C.1.5 §八 逐字：「json{客户/经络师/调理师/门店负责人:签名+日期}'
            ' | **未签不得首次调理或退款判定** | **硬门禁**」。'
            '🛑 去掉任何一方都是对硬门禁的实质削弱 —— 本断言就是为了让它必然可见。'
            '若待裁第 1 条(a) 最终裁定改用中文键名，请**同步改本断言**，'
            '而不是删掉它（本仓纪律：归零要求显式动作）。';
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
    IF v_reg_body ~* 'v_warn_gate_keys?\b|warn_gate|_warn_key' THEN
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
    v_slice := regexp_replace(v_reg_body, '--[^\n]*', '', 'g');

    IF v_slice ~* '\mUPDATE\M\s+agreement\b' THEN
        RAISE EXCEPTION
            'V21 自证失败(b10): register_agreement 里出现了 `UPDATE agreement`。'
            '🛑 本迁移**不提供任何改写通路**，理由三条（见文件头），最硬的一条：'
            '任何"把协议置为已签/未签"的第二通路都**等于**开设一条绕过 H1 的旁路 —— '
            '而 H1 之所以被禁止编码，恰恰因为"没有验签就无法确认签署是真的"。'
            '提供改写能力 = 把"未验签即可自证已签"搬进库里，只是换了个入口。';
    END IF;
    IF v_slice ~* '\mDELETE\M\s+FROM\s+agreement\b' THEN
        RAISE EXCEPTION
            'V21 自证失败(b11): register_agreement 里出现了 `DELETE FROM agreement`。'
            '🛑 协议是**举证材料**：PRD 的替代路径是"重新出方案 → 重新签**新的一份**"，'
            '而不是抹掉旧的那份。删除会毁掉"当时签的是什么"这条证据链。';
    END IF;
    IF v_lat_body ~* '\mUPDATE\M\s+agreement\b|'
                 || '\mDELETE\M\s+FROM\s+agreement\b|'
                 || '\mINSERT\M\s+INTO\s+agreement\b' THEN
        RAISE EXCEPTION
            'V21 自证失败(b11): latest_agreement_of（读原语）里出现了改写语句 —— '
            '一个"读"函数不得写。';
    END IF;

    -- ---- (b12) hash 形态判据必须**同时接受**两种大小写 ----
    --   见「设计取舍三」：初稿写过 `^[0-9a-f]{64}$`（拒大写）+ `lower(...)`（归一），
    --   两者并存 ⇒ lower() 是死代码。修复后正则必须含 A-F。
    --   静态防线 = 本条；行为防线 = (e10) 的两半（大写必须成功 + 短 hash 必须失败）。
    IF v_reg_body !~ '\^\[0-9a-fA-F\]\{64\}\$' THEN
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
    IF v_reg_body !~ 'lower\s*\(\s*p_rendered_hash\s*\)' THEN
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
    IF v_reg_body !~ 'p_doc_template_id\s+IS\s+NULL' THEN
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
    IF v_lat_body !~ 'tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V21 自证失败(c1): latest_agreement_of 的 SELECT 没有显式写 `tenant_id = p_tenant_id`。'
            '🛑 与 (b9) 同款纪律：RLS 会兜住，但可读性即安全。';
    END IF;

    -- ---- (c2) 🛑 "最新"必须有一个**全序**，且排序键必须是 signed_at ----
    --   🛑 两个断言合一，因为它们是**同一个判断**的两半：
    --     ① 必须有 tie-breaker（否则并发/同时刻写入时结果不确定）；
    --     ② 排序键必须是 signed_at（业务时刻）而不是 created_at（登记时刻）——
    --        离线补录会让"最新录入"与"最新签署"分叉。
    --        行为防线 = (e17)（刻意让"最后写入的那份"是中间时间的那份）。
    IF v_lat_body !~* 'ORDER\s+BY\s+signed_at\s+DESC\s*,\s*agreement_id\s+DESC' THEN
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
    IF v_lat_body !~* 'LIMIT\s+1' THEN
        RAISE EXCEPTION
            'V21 自证失败(c3): latest_agreement_of 里没有 LIMIT 1 —— '
            '多行时 INTO 只取第一行，而"第一行"随执行计划变 ⇒ 结果不确定。';
    END IF;

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