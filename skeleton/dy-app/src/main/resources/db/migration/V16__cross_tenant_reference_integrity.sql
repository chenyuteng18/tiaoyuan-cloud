-- ============================================================================
-- V16 迁移: 跨租户引用完整性（把 47 处单列外键升级为租户耦合的复合外键）
--
-- 【这个迁移为什么存在 —— 一条此前从未被登记过的结构性缺口】
--
--   本骨架的租户隔离由 RLS 承担，策略形如
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   ✅ 它保护的是【本行的归属】：你写入/读出的每一行，tenant_id 必须等于你的上下文。
--   ❌ 它【完全】不保护【本行引用的对象归谁】：一行 tenant_id=B 的数据，
--      可以合法地指向一个属于租户 A 的 customer / store / band。
--
--   🛑 【本段是"缺口探测"的留档，不是迁移里会执行的东西】
--      下面这段 `band → customer` 是 2026-09-27 用来【证明缺口存在】的一次性事务，
--      写完就 ROLLBACK 了，它不进入任何交付物。
--      迁移内【常驻】的那条行为探针在第 4 节 (e)，用的是 `store → region` ——
--      因为 `band` 登记在 ProvisioningBoundaryGateTest 的"未开通账"里（生产代码零写入方），
--      迁移若 INSERT 它，就会把一条如实登记的边界静默移动成"有了"。理由详见第 4 节 (e)。
--
--   【实测证据（2026-09-27，真库、应用角色 diaoyuanyun，非超级用户）】
--     BEGIN;
--       -- A 上下文：建 A 的 customer + A 的 band
--       SET LOCAL app.tenant_id = <A>;
--       INSERT INTO band (band_id, tenant_id, customer_id, ...) VALUES (<BAND_A>, <A>, <CUST_A>, ...);
--       -- 切到 B 上下文
--       SET LOCAL app.tenant_id = <B>;
--       -- B 上下文里，A 的 store / customer / band 都【看不见】（各 0 行，证明 RLS 生效）
--       SELECT count(*) FROM store    WHERE store_id = <STORE_A>;   -- 0
--       SELECT count(*) FROM customer WHERE id       = <CUST_A>;    -- 0
--       SELECT count(*) FROM band     WHERE band_id  = <BAND_A>;    -- 0
--       -- 但这一行插得进去：tenant_id=B，customer_id 指向 A 的客户
--       INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status)
--       VALUES (<BAND_B>, <B>, <CUST_A>, 'GTL1', CURRENT_DATE, 'active');   -- INSERT 0 1 ✅ 成功
--     ROLLBACK;
--   ⇒ 结论：外键检查在 PG 内是以【表所有者】身份绕过 RLS 执行的，
--     故 RLS 对"引用指向别家的行"这件事一声不响。
--
--   【这不是理论风险，而是当前 schema 的实测规模】
--     · 源表与目标表【都】 FORCE ROW LEVEL SECURITY、
--       且外键只引用单列（即不含 tenant_id）的外键：**47 处**
--     · 涉及 12 张被引用表：customer / region / store / staff / device / scale /
--       band / band_sync_log / cycle_assessment / refund / refund_statement /
--       intake_profile_revision
--     · 覆盖全部 30 张 FORCE RLS 源表（tenant_id 全部 NOT NULL，见下方"为什么可以放心复合"）
--   ⇒ 也就是说，"跨租户引用"这条路径在全库是【敞开的】，而此前没有任何门禁看着它。
--
-- 【为什么现在必须修：它恰好是本骨架主链路的第一道缝】
--   B-7（组织开通）刚刚落地，它让"往库里建租户 / 门店 / 员工"第一次成为可能。
--   而 A3（门店列表）的行级 scope、B4（客户详情）的字段级可见性、
--   E6（手环同步状态）按客户反查 active 手环 —— 这些读路径的判断依据，
--   全部是"这一行属于我"，没有一个去核对"这一行引用的那些行也属于我"。
--   一旦有一次引用写歪（例如某条批处理拿了错误的 customer_id），
--   后果不是"报错"，而是**跨租户的数据串联**：B 租户的行读出来是 A 租户的内容，
--   而 RLS 一路放行 —— 因为那些行本身确实"都是别家的"。
--
-- ============================================================================
-- 【修法：把 tenant_id 拉进外键，使"引用同租户"成为数据库层的强制事实】
--
--   复合外键（composite FK）：
--       源表 (tenant_id, customer_id)  →  目标表 (tenant_id, id)
--   语义：目标行不仅 id 要存在，**它的 tenant_id 还必须与源行相同**。
--   用法要点：目标列上必须有对应的 UNIQUE 约束（PG 要求引用列是唯一键）。
--
--   【🛑 实测确认方案有效（真库临时表同构复刻，非推测）】
--     单列形态：tenant_id=B 指向 A 的 customer  ⇒ INSERT 0 1（接受了，这正是缺陷）
--     复合形态：同一笔引用                      ⇒ ERROR 23503 违反外键 cc_fk（拒绝了）
--     复合形态：同租户引用                      ⇒ INSERT 0 1（正常路径未被误伤）
--   ⇒ 三个方向都实测过，包括"别把正常路径一起打死"这条对照。
--
--   【为什么用"替换"而不是"叠加"（保留单列 + 新增复合）】
--     叠加会留下两条语义重叠的约束：单列那条对"引用存在性"仍然管用，
--     复合那条额外管"归属一致"。看似更严，实际代价是——**将来放宽任何一条都不完整**：
--     有人只想改归属规则时，得先判断"单列那条是不是还挡着别的"。
--     而替换在语义上只增不减，见下。
--
--   【🛑 为什么替换是"只增不减"，不会放松任何既有保证】
--     所有 30 张源表的 tenant_id 都是 NOT NULL（实测：逐表 attnotnull = t）。
--     外键默认 MATCH SIMPLE 的语义是"任一列为 NULL 则不检查"。故：
--       · tenant_id 恒非空 ⇒ 复合外键【永远会】参与检查；
--       · 唯一可能为 NULL 的是引用列自身（region.supervisor_id、
--         store.region_id、customer.owner_store_id / serving_store_id、
--         intake_profile_revision.supersedes_revision_id、
--         refund_statement.supersedes_statement_id、
--         baseline_assessment.assist_operator / measure_operator）。
--         这些列在【原单列外键】下同样是 MATCH SIMPLE ⇒ NULL 时同样跳过。
--     ⇒ 替换后逐列行为完全一致，只是【额外】加上了 tenant_id 必须相等的条件。
--       没有一条约束被减弱，也没有一条可空列的语义发生变化。
--
--   【🛑 为什么不给可空引用列加 NOT NULL 以"顺便修好"】
--     那些可空是有业务含义的既定状态（未指定督导 / 未分配区域 / 未绑定归属店 /
--     首次修订无前任 / 无赔付门店）。把它们变成必填，是在一条"完整性修复"的迁移里
--     顺手改业务口径 —— 那正是本仓 N 系列登记反复要防的形态。
--     本迁移只做一件事：让已有引用无法跨租户。
--
-- ============================================================================
-- 【幂等口径（与 V1~V15 同款，四件套）】
--   ① 所有 DDL 由 DO 块以"存在性判断 + EXECUTE"完成（PG 无 ADD CONSTRAINT IF NOT EXISTS）
--   ② 目标表补 UNIQUE(tenant_id, pk) 前先查 pg_constraint，已存在则跳过
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（本迁移一行业务数据都不写）
--
-- 【回滚说明（forward-only，本仓不提供自动 down）】
--   DROP 复合外键 → 重建原单列外键 → DROP UNIQUE(tenant_id, pk) → 删登记行。
--   🛑 回滚会【重新打开】跨租户引用这条路径，故它不是一个"安全的回滚"：
--      它把一条安全性质退回未修复状态。任何执行它的人必须知道这一点。
--   语句顺序见文件末第 5 节。
--
-- 【🛑 本文件语句顺序不可调换：DDL → 登记 → 自证 → 完成】
--   自证 (c) 断言 schema_migration 里 V16 登记行数 = 1，故登记必须排在自证之前。
--
-- 【🛑 判据为什么必须用"结构判定"而不是字符串 LIKE（2026-09-27 实测后改）】
--   本迁移的自证原本用 pg_get_constraintdef(...) LIKE '(tenant_id, %' 来判定
--   "载体存在 / 复合外键存在"。这是错的，且错法很隐蔽：PG 的
--   pg_get_constraintdef 输出【按 conkey 顺序】拼列名 ⇒ LIKE 实际断言的
--   是"tenant_id 恰好排在第一位"，而不是"tenant_id 参与了这个键"。
--   实测（真库临时表同构复刻）：
--     · `UNIQUE (id, tenant_id)` 作为载体 + `FK (tenant_id, tgt_id) REFERENCES t(tenant_id, id)`
--       ⇒ 外键【建得起来】，且跨租户引用【被拒】（实测 = t）。即：语义上完全有效。
--     · 但旧判据 (d) 对该载体判为"不存在"（实测 = f）⇒ 假阳性误报。
--   ⇒ 后果不是"报错"，而是"逼后来人把判据继续往实现上凑"：下一个人看到
--     误报，最省事的做法是去找一个 LIKE 得上的写法，而正确的做法是修判据本身。
--     故本文件所有判据一律改为对 pg_constraint.conkey / confkey 的【列集合】判定。
--   ⇒ 同时明确：判据可改（判据脆弱），期望值不可改（期望值变了，断言就失去了
--     "数错了"的能力 —— 本迁移首次试跑报 "(b) = 48（期望 47）" 时，
--     正确的反应就是修判据、而不是把 47 改成 48）。
-- ============================================================================


-- ============================================================================
-- 第 1 节 · 给被引用表补 UNIQUE (tenant_id, <被引用列>)
--
--   为什么必须先做这一步：PG 要求外键的【引用列】是一个唯一键
--   （唯一约束或主键，可跨列）。当前 12 张被引用表的主键都是单列
--   （customer(id) / band(band_id) / region(region_id) / …），
--   没有一张带 (tenant_id, pk) 的唯一约束 —— 故复合外键加不上去。
--
--   🛑 这个约束在语义上是"白加"的：pk 已经是主键 ⇒ (tenant_id, pk) 天然唯一，
--      不可能因既有数据而失败。它存在的唯一理由是让 PG 接受复合外键。
--      故本节是纯粹的"为了能加约束而加的约束"，不承载任何独立业务语义 ——
--      这一点必须写清，否则下一个人会以为它在表达"同一个 tenant 下 id 唯一"
--      这类他不知道的规则。
-- ============================================================================

DO
$v16_unique$
DECLARE
    r      record;
    v_uq   text;
    v_tgt  text;
    v_col  text;
    v_made int := 0;
    v_had  int := 0;
BEGIN
    FOR r IN
        SELECT DISTINCT
               t.relname AS tgt_table,
               (SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = con.confrelid AND a.attnum = con.confkey[1]) AS tgt_col
        FROM pg_constraint con
        JOIN pg_class s ON s.oid = con.conrelid
        JOIN pg_class t ON t.oid = con.confrelid
        JOIN pg_namespace n ON n.oid = s.relnamespace
        WHERE n.nspname = 'public'
          AND con.contype = 'f'
          AND array_length(con.conkey, 1) = 1
          AND s.relrowsecurity
          AND t.relrowsecurity
        ORDER BY t.relname, tgt_col
    LOOP
        v_tgt := r.tgt_table;
        v_col := r.tgt_col;

        -- 约束命名：uq_tenant_<目标表>_<列>。🛑 PG 标识符上限 63 字符，
        -- 最长的一条是 uq_tenant_intake_profile_revision_revision_id（48），有余量。
        v_uq := 'uq_tenant_' || v_tgt || '_' || v_col;

        -- 🛑 幂等判据用【列集合】判定，不用字符串 LIKE。理由见文件头
        --    「判据为什么必须用结构判定」一节：LIKE 匹配列序，
        --    而 UNIQUE (id, tenant_id) 与 UNIQUE (tenant_id, id) 在
        --    【约束语义上完全等价】（都让 (tenant_id, id) 成为可引用键），
        --    用 LIKE 会把前一种判为"没有载体" —— 那是假阳性。
        IF EXISTS (
            SELECT 1
            FROM pg_constraint c2
            WHERE c2.conrelid = to_regclass('public.' || quote_ident(v_tgt))
              AND c2.contype IN ('u', 'p')
              AND (
                  SELECT count(*)
                  FROM unnest(c2.conkey) AS k(attnum)
                  JOIN pg_attribute a
                    ON a.attrelid = c2.conrelid AND a.attnum = k.attnum
                  WHERE a.attname IN ('tenant_id', v_col)
              ) = 2
        ) THEN
            v_had := v_had + 1;
            CONTINUE;   -- 幂等：已经有了就跳过（重跑本迁移时走这条）
        END IF;

        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id, %I)',
                       r.tgt_table, v_uq, r.tgt_col);
        v_made := v_made + 1;
    END LOOP;

    RAISE NOTICE 'V16 第 1 节: 新建 (tenant_id, pk) 唯一约束 % 个，已有 % 个', v_made, v_had;
END;
$v16_unique$;


-- ============================================================================
-- 第 2 节 · 把 47 处单列外键替换为租户耦合的复合外键
--
--   形态（每条外键三步，全部在一个 DO 块内）：
--     ① DROP CONSTRAINT <原名>
--     ② ADD CONSTRAINT <原名> FOREIGN KEY (tenant_id, <源列>)
--                             REFERENCES <目标表> (tenant_id, <目标列>)
--     🛑 【复用原约束名】而不是加后缀。理由：约束名是对账锚点 ——
--        运维脚本、监控、pg_dump 的 diff、以及"这处外键在不在"的核对，
--        都按名字找它。改名会让"同一处约束"在前后两次快照里看起来是两个东西，
--        而本迁移的全部意义恰恰是"同一处约束，更强的条件"。
--     🛑 DROP 与 ADD 之间存在"约束不存在"的瞬时窗口 —— 该窗口【对外不可见】：
--        整个迁移跑在一个事务里，其它会话看不到未提交的中间态。
--        这也是本迁移不能拆成多个文件/多次执行的原因。
--
--   【为什么用 DO 块遍历 pg_constraint 而不是手写 47 条 ALTER】
--     手抄 47 条清单必然会漏（本仓的纪律：清单要机械提取，不手抄）。
--     遍历的判据 = "源表与目标表都是 FORCE RLS 且外键只引用单列" ——
--     这正是缺陷的定义，故它【自动】覆盖全部 47 处，且对新出现的同类外键同样生效。
--     但"自动"不能替代"可核对"：自证 (a)(b) 会断言余量为 0、复合数为 47，
--     使"遍历真的做完了"成为一条可判定的断言，而不是一句"应该都覆盖了"。
-- ============================================================================

DO
$v16_composite$
DECLARE
    r      record;
    v_done int := 0;
BEGIN
    FOR r IN
        SELECT con.conname AS fk_name,
               s.relname   AS src_table,
               t.relname   AS tgt_table,
               (SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = con.conrelid AND a.attnum = con.conkey[1]) AS src_col,
               (SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = con.confrelid AND a.attnum = con.confkey[1]) AS tgt_col
        FROM pg_constraint con
        JOIN pg_class s ON s.oid = con.conrelid
        JOIN pg_class t ON t.oid = con.confrelid
        JOIN pg_namespace n ON n.oid = s.relnamespace
        WHERE n.nspname = 'public'
          AND con.contype = 'f'
          AND array_length(con.conkey, 1) = 1
          AND s.relrowsecurity
          AND t.relrowsecurity
        ORDER BY s.relname, con.conname
    LOOP
        -- ① 摘掉单列形态
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.src_table, r.fk_name);

        -- ② 换成复合形态（租户耦合），复用原约束名
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I '
            'FOREIGN KEY (tenant_id, %I) REFERENCES %I (tenant_id, %I)',
            r.src_table, r.fk_name, r.src_col, r.tgt_table, r.tgt_col);

        v_done := v_done + 1;
    END LOOP;

    RAISE NOTICE 'V16 第 2 节: 已把 % 处单列外键替换为租户耦合的复合外键', v_done;
END;
$v16_composite$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (c) 读本表）
--
--   🛑 description 列是 VARCHAR(256)，不得超过（本仓已因超长失败过一次）。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V16', 'cross-tenant reference integrity: 47 single-column FKs between FORCE-RLS tables upgraded to tenant-coupled composite FKs (tenant_id, ref) -> (tenant_id, pk); RLS protects row ownership, never reference ownership.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   自证五条：
--     (a) 余量必须为 0：不存在任何"源目标都 FORCE RLS、且只引用单列"的外键。
--         这是本迁移【唯一】的目的，故它是第一条断言。若它不为 0，
--         说明第 2 节的遍历漏了（例如某个外键是 deferrable 的子查询形态）。
--     (b) 复合数必须 = 47：把"遍历覆盖了全部"变成机械事实。
--         🛑 与 (a) 互为反证：(a) 说"没有单列的"，(b) 说"有 47 个复合的" ——
--            只看 (a) 的话，一个把外键【全删了】的实现同样满足它。
--         🛑 判据必须【精确到本迁移产生的形态】，否则会数错。本条判据的三个条件：
--            ① conkey 长度 = 2；② 定义以 "FOREIGN KEY (tenant_id, " 开头；
--            ③ 目标表的【主键是单列】。
--            第 ③ 条不是装饰：库中【本来就有】一处以 tenant_id 打头的复合外键 ——
--            {@code app_config_history(tenant_id, config_no) → app_config(tenant_id, config_no)}，
--            它是 V6 触发器产线的一部分，**不属本迁移范畴**，且它的目标表
--            {@code app_config} 的主键本身就是复合的 (tenant_id, config_no)。
--            ⇒ 缺了第 ③ 条，本断言会数出 48 而误报失败。
--              （这不是假设：本迁移的首次试跑【确实】报了 "= 48（期望 47）"，
--               当时正确的反应是修判据、而不是把期望值改成 48 ——
--               改期望值会让这条断言从此失去"数错了"的能力。）
--     (c) schema_migration 里必须有 V16 一行。
--     (d) 12 张被引用表必须都有 (tenant_id, <pk>) 唯一约束 —— 复合外键的载体。
--     (e) 🛑 行为验证：用一个真实的跨租户引用，断言它【必须被拒】。
--         这是本迁移最重要的一条：前四条都只检查"约束的定义形态"，
--         而定义正确 ≠ 约束有效（写错的 referenced 列会让定义看着对、
--         实际不约束）。故此处用一次真实的 INSERT 探它。
--         —— 同时给出同租户的对照，证明这次探针没有把正常路径一起打死。
-- ============================================================================

DO
$v16_guard$
DECLARE
    v_left    int;
    v_comp    int;
    v_reg     int;
    v_no_uq   text;
    v_ta      uuid := '16000000-0000-0000-0000-00000000000a';
    v_tb      uuid := '16000000-0000-0000-0000-00000000000b';
    v_region_a uuid := '16000000-0000-0000-0000-0000000000d1';
    v_store_b  uuid := '16000000-0000-0000-0000-0000000000f1';
    v_store_b2 uuid := '16000000-0000-0000-0000-0000000000f2';
    v_rejected boolean := false;
    v_accepted boolean := false;
BEGIN
    -- (d) 🛑 先查"载体"：12 张被引用表必须有 (tenant_id, <pk>) 唯一约束。
    --     🛑 为什么 (d) 排在 (a)(b)【之前】（2026-09-27 定的序）：
    --        载体缺失与"复合外键不存在"是【同一件事的两种描述】——
    --        没有载体则复合外键根本建不起来。故若把 (d) 排在 (a)(b) 之后，
    --        任何违反 (d) 的状态必然先触发 (a) 或 (b)，(d) 永远轮不到执行。
    --        它就成了死代码：一条永远为真的断言。移前之后，(d) 是【根因断言】，
    --        (a)(b) 是它的后果断言 —— 报错归因变得准确（先告诉你载体缺了）。
    --     🛑 判据用【列集合结构判定】，不用字符串 LIKE：
    --        UNIQUE (id, tenant_id) 与 UNIQUE (tenant_id, id) 语义完全等价，
    --        且实测前者照样让复合外键生效（跨租户引用被拒 = t）。
    --        旧判据 `pg_get_constraintdef LIKE '%(tenant_id, %'` 只认后者 ⇒
    --        对前者假阳性误报"缺少载体"。实测记录见文件头「判据…」一节。
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_no_uq
    FROM (VALUES ('customer'), ('region'), ('store'), ('staff'), ('device'), ('scale'),
                 ('band'), ('band_sync_log'), ('cycle_assessment'), ('refund'),
                 ('refund_statement'), ('intake_profile_revision')) AS x(t)
    WHERE NOT EXISTS (
        SELECT 1
        FROM pg_constraint c2
        WHERE c2.conrelid = to_regclass('public.' || quote_ident(x.t))
          AND c2.contype IN ('u', 'p')
          -- ① 恰好两列
          AND array_length(c2.conkey, 1) = 2
          -- ② 其中一列是 tenant_id
          AND EXISTS (SELECT 1 FROM unnest(c2.conkey) AS k(attnum)
                        JOIN pg_attribute a
                          ON a.attrelid = c2.conrelid AND a.attnum = k.attnum
                       WHERE a.attname = 'tenant_id')
          -- ③ 另一列是该表的主键列（单列主键）
          AND EXISTS (SELECT 1
                        FROM pg_constraint pkc
                        JOIN unnest(pkc.conkey) AS pk(attnum) ON true
                        JOIN pg_attribute pka
                          ON pka.attrelid = pkc.conrelid AND pka.attnum = pk.attnum
                       WHERE pkc.conrelid = c2.conrelid
                         AND pkc.contype = 'p'
                         AND pka.attname IN (
                             SELECT a2.attname
                             FROM unnest(c2.conkey) AS k2(attnum)
                             JOIN pg_attribute a2
                               ON a2.attrelid = c2.conrelid AND a2.attnum = k2.attnum))
    );
    IF v_no_uq IS NOT NULL THEN
        RAISE EXCEPTION
            'V16 自证失败(d): 以下被引用表缺少 (tenant_id, <pk>) 唯一约束（复合外键的载体）-> %. '
            '这是【根因】：没有载体，第 2 节的复合外键根本建不起来，故本条排在 (a)(b) 之前。'
            '🧰 修复句（对每张缺表执行；<col> 取该表单列主键的列名）：'
            'ALTER TABLE <表> ADD CONSTRAINT uq_tenant_<表>_<col> UNIQUE (tenant_id, <col>);',
            v_no_uq;
    END IF;

    -- (a) 余量：不得再有任何单列跨 RLS 外键
    SELECT count(*) INTO v_left
    FROM pg_constraint con
    JOIN pg_class s ON s.oid = con.conrelid
    JOIN pg_class t ON t.oid = con.confrelid
    JOIN pg_namespace n ON n.oid = s.relnamespace
    WHERE n.nspname = 'public'
      AND con.contype = 'f'
      AND array_length(con.conkey, 1) = 1
      AND s.relrowsecurity
      AND t.relrowsecurity;
    IF v_left <> 0 THEN
        RAISE EXCEPTION
            'V16 自证失败(a): 仍有 % 处"源目标都 FORCE RLS、且只引用单列"的外键未被复合化。'
            '这类外键允许跨租户引用（RLS 不检查引用归属）—— 它们正是本迁移要修的东西。'
            '若第 2 节的遍历正常，本值应为 0。', v_left;
    END IF;

    -- (b) 复合数 = 47（防"全删了也算修好"）
    --     🛑 判据全部用 pg_constraint.conkey / confkey 的【列集合】判定，
    --        位置无关、不用字符串匹配。三个条件：
    --        ① conkey 长度 = 2；
    --        ② 🛑 源列集合与目标列集合【都】含 tenant_id —— 这才是
    --           "租户耦合"的定义（引用端与被引用端都被租户约束住）。
    --        ③ 目标表的【主键是单列】，排除既有的 app_config_history → app_config。
    --     🛑 为什么 ② 必须是"含"而不是"第一位是"（2026-09-27 反向验证抓出）：
    --        C3 用例构造了一个【语义完全等价】的形态
    --            FOREIGN KEY (customer_id, tenant_id) REFERENCES customer (id, tenant_id)
    --        它与本迁移产生的 (tenant_id, customer_id)→(tenant_id, id) 做的事一模一样：
    --        都要求目标行的 tenant_id 等于源行的 tenant_id，跨租户【都】被拒（实测 t）。
    --        而判据写成 "conkey[1] = 'tenant_id'"（位置判定）时，本断言会把它数漏
    --        ⇒ 报 "(b) = 46（期望 47）" 假失败。
    --        🛑 这不是假想：本迁移的 (b) 判据【先后两次】踩在同一个坑上 ——
    --           第一次是字符串 LIKE 认列序，第二次是 conkey[1] 认位置。
    --           两次的正确反应都是修判据，不是改期望值。
    SELECT count(*) INTO v_comp
    FROM pg_constraint con
    JOIN pg_class s ON s.oid = con.conrelid
    JOIN pg_class t ON t.oid = con.confrelid
    JOIN pg_namespace n ON n.oid = s.relnamespace
    JOIN pg_constraint tp ON tp.conrelid = t.oid AND tp.contype = 'p'
    WHERE n.nspname = 'public'
      AND con.contype = 'f'
      AND array_length(con.conkey, 1) = 2
      AND s.relrowsecurity
      AND t.relrowsecurity
      AND array_length(tp.conkey, 1) = 1
      -- ②a 源端含 tenant_id（位置无关）
      AND EXISTS (SELECT 1 FROM unnest(con.conkey) AS k(attnum)
                    JOIN pg_attribute a
                      ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                   WHERE a.attname = 'tenant_id')
      -- ②b 目标端含 tenant_id（位置无关）
      AND EXISTS (SELECT 1 FROM unnest(con.confkey) AS k(attnum)
                    JOIN pg_attribute a
                      ON a.attrelid = con.confrelid AND a.attnum = k.attnum
                   WHERE a.attname = 'tenant_id');
    IF v_comp <> 47 THEN
        RAISE EXCEPTION
            'V16 自证失败(b): 租户耦合的复合外键数 = %（期望 47）。'
            '本断言与 (a) 互为反证：(a) 只断言"没有单列的"，而一个把外键全删掉的实现'
            '同样满足 (a) —— 故必须有 (b) 说清"替换成了多少个"。', v_comp;
    END IF;

    -- (c) 迁移登记
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V16';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V16 自证失败(c): schema_migration 中 V16 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- (d) 已【移至本块最前】—— 理由见该处说明（根因断言必须排在后果断言之前，
    --     否则永远轮不到它报错）。此处不再重复检查。

    -- (e) 🛑 行为验证：跨租户引用必须被拒、同租户引用必须成功
    --     🛑 本段用子事务（BEGIN ... EXCEPTION）执行，结束时【整体回滚】——
    --        迁移不得留下任何业务数据（本仓 V1~V15 一致的纪律）。
    --        写法要点：EXCEPTION 子块会建立 savepoint，故内部的 INSERT 无论成功与否
    --        都会在块结束时被撤销；这正是我们要的"探针不留痕"。
    --
    --     🛑 【探针为什么用 store→region 而不是 band→customer】（2026-09-27 全量回归抓出）
    --        初版探针用 `INSERT INTO band (...) REFERENCES customer(...)`。
    --        它在真库上完全正确，却被 `ProvisioningBoundaryGateTest` 第③例报红 ——
    --        因为 `band` 在"未开通账"里（生产代码零写入方），而该门禁的判据是
    --        「未开通的表，生产代码里必须真的没有 INSERT INTO」。
    --        ⇒ **门禁是对的，我的迁移越了界**：一条"完整性修复"的迁移，不应成为
    --          `band` 这张业务表的第一个写入方 —— 那会把"设备/手环台账无写入方"
    --          这条如实登记的边界，静默地移动成"有了"。
    --        ⇒ 修法：换成两端都在【已开通账】里的表对 —— `store(tenant_id, region_id)
    --          → region(tenant_id, region_id)`。store 与 region 都已有真实写入方
    --          （B-7 的组织开通通路），故本探针不再移动任何边界。
    --        ⇒ 🛑 被放弃的选项是"把 band 加进 PROVISIONED 账"：那等于为了让迁移通过
    --          而把账本改成迎合实现 —— 正是本仓 N 系列登记反复要防的形态。
    --          （而且账本改了也没用：band 的写入方仍只有一个迁移探针，
    --            ProvisioningBoundaryGateTest 第②例会立刻指出"账本在骗人"。）
    BEGIN
        -- 准备：两个租户（tenant 表无 RLS，可直接写）
        INSERT INTO tenant (id, name, status) VALUES
            (v_ta, 'V16 跨租户引用探针 A', 'active'),
            (v_tb, 'V16 跨租户引用探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        -- A 上下文建 A 的区域
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        INSERT INTO region (region_id, tenant_id, name)
        VALUES (v_region_a, v_ta, 'V16 探针区域 A');

        -- ① 跨租户：B 上下文里，让 store.region_id 指向 A 的区域 —— 必须被拒
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        BEGIN
            INSERT INTO store (store_id, tenant_id, name, franchise_type, region_id)
            VALUES (v_store_b, v_tb, 'V16 探针门店 B', '直营', v_region_a);
            -- 若走到这里说明没被拒（约束无效），本探针失败
            v_rejected := false;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V16 自证失败(e): 跨租户引用【未被拒绝】——'
                '在租户 B 的上下文里，store.region_id 成功指向了租户 A 的区域。'
                '这说明本迁移的复合外键没有真正生效（定义形态对、约束没起作用）。'
                '🛑 这一条正是本迁移的全部意义所在：RLS 保护行归属，但从不保护引用归属。';
        END IF;

        -- ② 对照组：同租户引用必须【成功】（防"把正常路径一起打死"）
        BEGIN
            INSERT INTO region (region_id, tenant_id, name)
            VALUES ('16000000-0000-0000-0000-0000000000d2', v_tb, 'V16 探针区域 B');
            INSERT INTO store (store_id, tenant_id, name, franchise_type, region_id)
            VALUES (v_store_b2, v_tb, 'V16 探针门店 B2', '直营',
                    '16000000-0000-0000-0000-0000000000d2');
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V16 自证失败(e2): 同租户引用被误拒 —— 复合外键把【正常路径】也挡住了。'
                '一个"拒绝一切"的实现同样能让 (e) 通过，故必须有本条对照。'
                '最可能的成因：引用列写成了目标表的非唯一列，或 tenant_id 未参与匹配。';
        END IF;

        -- ③ 探针清场：🛑 必须【逐个租户在自己的上下文里】删 ——
        --    本迁移的自证块自己踩过一次这个坑（首次试跑报
        --    "在 tenant 上的更新或删除操作违反了在 … 上的外键约束"）：
        --    原因是清场只在 B 上下文里跑，而 A 的行在 B 上下文下
        --    【看不见】（RLS 的 USING 静默过滤）⇒ DELETE 删了 0 行且不报错，
        --    于是 A 的行残留下来，挡住了 tenant 行的删除。
        --    这与本仓 S2-5 / B-7 已付过代价的那个形态完全同型：
        --    **FORCE RLS 下 DELETE 静默删 0 行**，表象是"删不掉"，成因是"我没设上下文"。
        --    🛑 删除顺序必须与外键依赖倒序：store 引用 region ⇒ 先删 store 再删 region。
        --       写反了的表现是"在 region 上的删除违反了 store 上的外键约束"。
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        DELETE FROM store  WHERE tenant_id = v_ta;
        DELETE FROM region WHERE tenant_id = v_ta;
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        DELETE FROM store  WHERE tenant_id = v_tb;
        DELETE FROM region WHERE tenant_id = v_tb;
        -- tenant 表无 RLS，直接在任意上下文下删
        DELETE FROM tenant WHERE id IN (v_ta, v_tb);

        -- ③b 清场自证：残留必须为 0。🛑 没有这一条，"删不掉"会以
        --     "后续 INSERT 撞主键" 的面目在【下一次】运行本迁移时才暴露。
        --     🛑 探针两租户都在 RLS 下 ⇒ 本检查必须【不带上下文】跑（此时
        --        应用角色看不到任何行？不 —— 见下），故改用【逐租户设上下文】检查，
        --        否则"删干净了"与"我看不见"两种情形会给出同一个 0 行结论。
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        IF EXISTS (SELECT 1 FROM region WHERE region_id = v_region_a) THEN
            RAISE EXCEPTION
                'V16 自证失败(e3): 探针清场不彻底（租户 A 的 region % 仍有残留）。'
                '探针必须一行业务数据都不留下 —— 残留会在下一次运行时表现为'
                '"INSERT 撞主键"，而那时没人记得它来自本次探针。', v_region_a;
        END IF;
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM store WHERE store_id IN (v_store_b, v_store_b2)) THEN
            RAISE EXCEPTION
                'V16 自证失败(e3): 探针清场不彻底（租户 B 的 store % / % 仍有残留）。'
                '探针必须一行业务数据都不留下。', v_store_b, v_store_b2;
        END IF;
        -- tenant 表无 RLS ⇒ 本检查不受上下文影响，可直接跑
        IF EXISTS (SELECT 1 FROM tenant WHERE id IN (v_ta, v_tb)) THEN
            RAISE EXCEPTION
                'V16 自证失败(e3): 探针清场不彻底（tenant % / % 仍有残留）。', v_ta, v_tb;
        END IF;
    EXCEPTION
        WHEN others THEN
            -- 任何异常都向上抛（本块不吞错）：探针成功时不应有异常，
            -- 若有，那是真问题（包括上面两条 RAISE），必须让整个迁移回滚。
            RAISE;
    END;

    RAISE NOTICE 'V16 自证通过: 单列余量 0 / 复合 47 / 登记 1 / 载体 12 / 跨租户引用被拒且同租户放行';
END;
$v16_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   🛑 回滚会重新打开跨租户引用这条路径 —— 它把一条安全性质退回未修复状态。
--      执行前必须明确知道这一点，而不是"把它当成一次普通的版本回退"。
--
--   语句形态（按外键依赖倒序）：
--     -- ① 复合 → 单列（复用原约束名，与第 2 节对称）
--     --   对每一处：DROP CONSTRAINT <fk_name>;
--     --             ADD CONSTRAINT <fk_name> FOREIGN KEY (<src_col>) REFERENCES <tgt>(<tgt_col>);
--     --   逐处清单可用本语句导出（回滚前先跑它留档）：
--     --     SELECT s.relname, con.conname,
--     --            (SELECT a.attname FROM pg_attribute a WHERE a.attrelid=con.conrelid AND a.attnum=con.conkey[2]),
--     --            t.relname,
--     --            (SELECT a.attname FROM pg_attribute a WHERE a.attrelid=con.confrelid AND a.attnum=con.confkey[2])
--     --       FROM pg_constraint con JOIN pg_class s ON s.oid=con.conrelid
--     --       JOIN pg_class t ON t.oid=con.confrelid
--     --      WHERE con.contype='f' AND array_length(con.conkey,1)=2
--     --        AND pg_get_constraintdef(con.oid) LIKE 'FOREIGN KEY (tenant_id, %';
--     -- ② 摘掉载体唯一约束
--     --    DROP CONSTRAINT uq_tenant_<表>_<列>;  （12 张表）
--     -- ③ 删登记
--     --    DELETE FROM schema_migration      WHERE version = 'V16';
--     --    DELETE FROM flyway_schema_history WHERE version = '16';
--   🛑 本迁移【不建表、不写业务数据】⇒ 回滚无数据损失风险，但它有【安全损失】。
-- ============================================================================