-- ============================================================================
-- V15 迁移: 组织开通通路（租户 / 区域 / 门店 / 员工）
--
-- 【这个迁移为什么存在 —— 它补的是一条被如实登记了很久的边界】
--
--   在此之前，本骨架「建租户」这件事在代码里【不存在】：
--     · 契约 40 个 path 里没有任何租户开通 / 门店新建 / 员工新建端点
--       （{@code /stores} 只有 GET，A3 门店列表是只读）；
--     · tenant / region / store / staff 四张组织主数据表，生产 Java 代码（剥注释后）
--       零 INSERT INTO；应用库实测均为 0 行；
--     · 全仓无 ApplicationRunner / CommandLineRunner 启动钩子。
--   ⇒ 部署起来是个「能跑但空」的系统（ProvisioningBoundaryGateTest 把它钉成了构建期事实）。
--
-- 【为什么开通通路【不】是 HTTP 端点（这是有意的形态，不是没做完）】
--   契约 openapi-v1.0.0.yaml 无开通端点，是【契约化决策】：
--     ① 开通是运维/实施动作，不是业务 API —— 给它一个对外 HTTP 端点，
--        等于在租户边界之外开一个「谁能创建租户」的鉴权面，而契约里
--        没有任何角色/权限声明覆盖它（x-callable-roles 里无对应项）；
--     ② 加这样一个端点属契约 MAJOR 变更，需产品共签，不在本骨架的裁定范围内。
--   ⇒ 故本迁移提供的是【数据库层原语】：一个幂等的 plpgsql 函数。
--      它可被运维 psql 直接调用，也可被应用内不对外暴露的 Provisioning Service 调用。
--      两种用法共用同一份实现，「开通」这条路径从此存在且只有一处定义。
--
-- 【🛑 核心机制：开通租户必须【自己】建立 RLS 上下文，否则紧接着的门店/员工 INSERT 会被 RLS 拒绝】
--   tenant 表【没有】RLS（它是租户的容器，见 V1 脚本与 RlsCoverageGateTest 的
--   TENANT_HOST_TABLES 豁免），故「插 tenant 行」这一步不需要租户上下文。
--   但 region / store / staff 三张表都是 ENABLE + FORCE ROW LEVEL SECURITY，
--   策略是 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 current_setting 返回 NULL ⇒ 策略为 false ⇒ INSERT 被 WITH CHECK
--   拒绝。【关键】PostgreSQL 的 RLS WITH CHECK 拒绝是【抛错】，不是静默零行
--   （静默零行发生在 SELECT 的 USING 上，这个区别很容易被记反）。
--   无论哪种，结论一致：不设上下文就建不出组织 → 故本函数把「写 tenant 行」与
--   「设为租户上下文」绑定在同一个函数体里，使【不存在】「建了租户却没上下文」的中间态。
--
-- 【🛑 为什么用 set_config(..., is_local := true) 而不是 SET LOCAL】
--   SET LOCAL 与 set_config(x, y, true) 在事务内等价，但有一个决定性差别：
--     · SET LOCAL 是【工具语句】，不支持绑定参数，值必须拼进 SQL 文本
--       （本仓各 *Ledger 的 inTenant 都因此必须做 UUID 白名单后再拼接）；
--     · set_config(name, value, is_local) 是【普通函数】，参数可绑定 ——
--       在 plpgsql 里传进去的是已解析的变量，无拼接、无注入面。
--   ⇒ 本迁移统一用 set_config 形态：工程上更硬，且减少一处「必须记得做白名单」的纪律。
--
-- 【幂等口径（与 V1~V14 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 的函数天然支持 replace）
--   ② INSERT ... ON CONFLICT DO NOTHING（租户已存在时不重复建）
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据
--
-- 【回滚说明】
--   本迁移只新增两个函数、不建表、不写业务行。回滚 = DROP FUNCTION +
--   DELETE FROM schema_migration WHERE version='V15'，无数据损失风险。
--   （已开通的租户行不受影响 —— 它们是数据，不是 schema。）
--
-- 【🛑 本文件语句顺序不可调换：授权 → 登记 → 自证 → 完成】
--   自证块 (c) 断言 schema_migration 里 V15 登记行数 = 1，故【登记必须排在自证之前】。
--   V14 把登记放在末尾是因为它的自证不读登记表；本迁移的自证读，故顺序不同。
--   这类"顺序依赖"是文件内注释无法自保的，故在此逐字写明。
-- ============================================================================


-- ============================================================================
-- 第 1 节 · 开通原语函数
-- ============================================================================

-- ---------------------------------------------------------------------------
-- assert_tenant_context() —— 供调用方在开通后显式自证「本事务内租户上下文已生效」
--
--   为什么不直接读 current_setting 而要多一个函数：因为「设了上下文」这件事
--   必须可被【调用方断言】，否则它会退化成"我以为设好了"。
--   返回已生效的 uuid 文本，便于调用方写进日志 / 审计 payload。
--   缺上下文时 RAISE —— 这是一条 fail-closed 守卫，不是检查工具。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_tenant_context()
    RETURNS text
    LANGUAGE plpgsql
AS
$v15_assert$
DECLARE
    v_ctx text;
BEGIN
    v_ctx := current_setting('app.tenant_id', true);
    IF v_ctx IS NULL OR btrim(v_ctx) = '' THEN
        RAISE EXCEPTION
            '租户上下文未建立（app.tenant_id 为空）—— 拒绝继续执行组织开通。调用方必须先在事务内 set_config(app.tenant_id, <tenant_id>, true)。本守卫存在的理由：region/store/staff 三表 FORCE RLS + fail-closed 策略，缺上下文时任何写入都会被拒绝，而"等到 INSERT 报错才发现"会把一次配置事故伪装成一次数据错误。';
    END IF;
    RETURN v_ctx;
END;
$v15_assert$;


-- ---------------------------------------------------------------------------
-- provision_tenant(p_id, p_name, p_datastore_hint) —— 幂等开通一个租户并建立其上下文
--
--   【这个函数把两件事绑在一起，这是它的全部价值】
--     ① 往 tenant 表插一行（tenant 无 RLS，不需要上下文）
--     ② 在本事务内建立 app.tenant_id 上下文（供随后建 region/store/staff 使用）
--   把二者绑在一起，使「租户存在但没有上下文」这个中间态在代码里不存在。
--
--   【幂等语义（逐字说清）】
--     · 租户不存在      → 插入，返回 'CREATED'
--     · 租户已存在      → 不插入、不改名、不改动任何字段，仍建立上下文，返回 'ALREADY_EXISTS'
--     🛑 关键：无论哪一态，本函数执行完毕后【上下文都已建立】——
--        故调用方无需判断返回值即可继续建组织。返回值只用于审计留痕与可观测。
--        这样设计的好处是：重放开通（运维脚本跑第二遍）不会半途失败。
--     🛑 有意【不】UPDATE 名称：开通是一条"只前向"的动作。改名是一件有审计含义的
--        独立操作，不应被一次"顺手的重放"静默执行。
--
--   【为什么判定用单条 INSERT ... ON CONFLICT 而不是"先 count 再 insert"】
--     先 count 再 insert 之间存在并发窗口：两个并发的开通调用会双双读到 count=0，
--     然后各插一次 —— 第二个插不进去（主键冲突），但函数会错报 'CREATED'。
--     改用单条 INSERT + ON CONFLICT + GET DIAGNOSTICS ROW_COUNT：判定与写入在同一条
--     语句里完成，0/1 就是"是否真由本次调用创建"的精确答案，无窗口。
--
--   【为什么不在这里也建门店/员工】
--     门店依赖 region（可空）、员工依赖 store（可空），三者是【用户的选择】，
--     不是开通的必然步骤。硬绑定会造出"必须一次给全"的接口，
--     而现实中租户开通时往往先建租户、后建门店。故本函数只做租户这一步，
--     门店/员工由调用方在同一事务内追加（见 OrganizationProvisioningRepository）。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION provision_tenant(
    p_id             uuid,
    p_name           text,
    p_datastore_hint text DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v15_provision$
DECLARE
    v_rows   int;
    v_result text;
    v_ctx    text;
BEGIN
    IF p_id IS NULL THEN
        RAISE EXCEPTION 'provision_tenant: p_id 不可为空（tenant.id 是主键）';
    END IF;
    IF p_name IS NULL OR btrim(p_name) = '' THEN
        -- name 在 DDL 里是 NOT NULL，但空串能通过 NOT NULL 检查却在语义上无意义，
        -- 故在函数层显式拒绝 —— 让「没有名字的租户」无法被建成。
        RAISE EXCEPTION 'provision_tenant: p_name 不可为空串（tenant.name 是 NOT NULL 且有业务含义）';
    END IF;

    -- ① 幂等写入：判定与写入在同一条语句里完成（见上方"为什么不用先 count 再 insert"）
    INSERT INTO tenant (id, name, datastore_hint, status)
    VALUES (p_id, p_name, p_datastore_hint, 'active')
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_result := CASE WHEN v_rows = 1 THEN 'CREATED' ELSE 'ALREADY_EXISTS' END;

    -- ② 建立本事务的租户上下文（is_local := true ⇒ 事务结束自动失效）
    --    这一步是后续 region/store/staff 写入能通过 RLS 的【唯一】前提。
    PERFORM set_config('app.tenant_id', p_id::text, true);

    -- ③ 自证：上下文确实生效（而不是"我调用了 set_config 所以它当然生效"）
    --    先取值到变量再比较：若直接在 IF 里调两次 assert_tenant_context()，
    --    错误分支的消息里那一次调用是【在异常路径上】再跑一遍，
    --    一旦它自己也抛（比如被人改成更严的守卫），原始诊断会被覆盖掉。
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_id::text THEN
        RAISE EXCEPTION
            'provision_tenant 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_id::text;
    END IF;

    RETURN v_result;
END;
$v15_provision$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   两个函数的执行权限授予应用连接角色。函数以【调用者】权限执行（默认 SECURITY
--   INVOKER），故它读 tenant 表、写 region/store/staff 时仍受调用者自身的表权限与
--   RLS 约束 —— 本函数【不】提权，它只是把"正确顺序"固化成一处实现。
--
--   【🛑 准确说明这一节在做什么，不夸大它的作用】
--   PostgreSQL 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"
--   （与表/序列的默认行为相反 —— 表默认无权限，函数默认 PUBLIC 可执行）。
--   因此在本骨架的常规环境里，函数一旦创建出来就能被调用，
--   本节的两条 GRANT 实际上是【no-op】。
--   那为什么还要写？两个具体理由：
--     ① 显式优于隐式：把"谁能执行"写在迁移里，而不是依赖"PG 的默认行为恰好如此"。
--        PG 的默认行为可以被一个 `ALTER DEFAULT PRIVILEGES ... REVOKE ... ON FUNCTIONS`
--        改变，那时隐式依赖会静默失效；
--     ② 生产环境把 PUBLIC 权限收紧（`REVOKE ALL ON FUNCTION ... FROM PUBLIC`）
--        是合规加固里的常见做法。在【迁移期】就把应用角色的执行权限补回来，
--        好过让它成为"上线后才发现的运行期错误"。
--   与"CURRENT_USER + IF EXISTS"的组合，是为了让本节在【任何】环境下都不报错：
--   函数不存在时跳过（不该发生，但迁移脚本不该因为一段防御性语句而整体失败）。
--
--   🛑 权限是否真的够，由第 4 节自证 (d) 用 has_function_privilege 机械断言 ——
--      那才是本节真正有价值的部分：把"权限够不够"从运行期前移到迁移期。
-- ============================================================================

DO
$v15_grant$
DECLARE
    v_role text;
    v_granted int := 0;
BEGIN
    -- 找出应用连接角色：用当前连接用户（迁移由应用角色执行）。
    -- 不做硬编码角色名 —— 本骨架的门禁库角色是 dy_app_rls，开发库是 diaoyuanyun，
    -- 而部署库可能是别的名字。
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'provision_tenant') THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION provision_tenant(uuid, text, text) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'assert_tenant_context') THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION assert_tenant_context() TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V15 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v15_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (c) 读本表）
--
--   🛑 description 列是 VARCHAR(256)，不得超过。
--      本行由一次真实的 psql 试跑抓出（初版 263 字符 → 报
--      「对于可变字符类型来说，值太长了(256)」）。迁移在事务里试跑一遍的成本
--      远低于让它进 flyway 后在某个环境的应用阶段失败。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V15', 'organization provisioning primitive: provision_tenant() + assert_tenant_context() - the create-tenant path the contract deliberately does not expose as an HTTP endpoint (ops path); binds tenant-row insert and RLS context into one atomic function.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   自证四条，对应本迁移存在的理由：
--     (a) 两个函数必须真的存在（否则调用方在运行时才发现，而那是运行期的事故）；
--     (b) 函数体必须真的调用 set_config('app.tenant_id', ..., true) —— 这一条是
--         本迁移最关键的断言：若它被改掉，开通就会建出"没有上下文的租户"，
--         随后所有组织写入被 RLS 挡下，而调用方只会看到"建门店失败"这种表象。
--         源码级断言把这条不变量的守卫前移到迁移期。
--         🛑 剥注释后再匹配：本文件的注释里【逐字】写着 set_config('app.tenant_id'...)
--            （就在上面的说明文字里），不剥注释会把"注释里提到"当成"代码里调用"。
--     (b2) is_local 必须为 true —— false/缺省是会话级，会跨请求泄漏租户上下文。
--     (c) schema_migration 里必须有 V15 一行。
--     (d) 当前连接角色必须【真的能执行】这两个函数（has_function_privilege）。
--         这一条是本类最重要的"防已修好"断言：授权段写对了不代表权限真的到位 ——
--         生产环境常有的 `REVOKE ALL ON FUNCTION ... FROM PUBLIC` 加固
--         会把"能建租户"变成"调用时报 permission denied"，
--         而那是运行期才发现的错误。在迁移期断言，把它变成一次可见的失败。
-- ============================================================================

DO
$v15_guard$
DECLARE
    v_fns  text;
    v_body text;
    v_reg  int;
    v_nopriv text;
BEGIN
    -- (a) 两个函数存在且可解析
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_fns
    FROM (VALUES ('provision_tenant'), ('assert_tenant_context')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f);
    IF v_fns IS NOT NULL THEN
        RAISE EXCEPTION 'V15 自证失败(a): 以下函数未创建成功 -> %', v_fns;
    END IF;

    -- (b) / (b2) 函数体必须在【代码态】以 is_local := true 调用 set_config('app.tenant_id', ...)
    SELECT p.prosrc INTO v_body
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname = 'provision_tenant';

    IF v_body IS NULL THEN
        RAISE EXCEPTION 'V15 自证失败(b): 读不到 provision_tenant 的函数体';
    END IF;

    -- 逐行剥 -- 行注释后再匹配，避免注释里的示例文本满足判据（假绿）
    -- 🛑 别名用 ln 而非 line：line 是 PostgreSQL 的几何类型名，
    --    作为别名虽合法但在表达式位置会引起阅读歧义，换名消除。
    IF NOT EXISTS (
        SELECT 1
        FROM regexp_split_to_table(v_body, E'\n') AS t(ln)
        WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id'''
    ) THEN
        RAISE EXCEPTION
            'V15 自证失败(b): provision_tenant 的函数体里【代码态】没有调用 set_config(app.tenant_id, ...)。这是本迁移最核心的不变量 —— 缺了它，开通会建出"没有租户上下文"的租户，随后组织写入被 RLS 挡下，而调用方只会看到"建门店失败"这种误导性表象。';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM regexp_split_to_table(v_body, E'\n') AS t(ln)
        WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id''\s*,[^)]*,\s*true\s*\)'
    ) THEN
        RAISE EXCEPTION
            'V15 自证失败(b2): provision_tenant 里的 set_config 未以 is_local := true 调用。false/缺省是【会话级】设置，会被连接池复用给后续请求 —— 即"上一个租户的上下文泄漏给下一个请求"，是跨租户串号的最短路径。';
    END IF;

    -- (c) 迁移登记
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V15';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V15 自证失败(c): schema_migration 中 V15 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- (d) 当前连接角色必须真的能执行这两个函数（见文件头第 4 节说明 (d)）
    --     用 has_function_privilege 而非读 proacl：前者把"角色继承 / PUBLIC 授权 /
    --     owner 隐含权限"等所有生效路径都算进去，后者只反映显式 ACL 项。
    SELECT string_agg(x.sig, ', ' ORDER BY x.sig) INTO v_nopriv
    FROM (VALUES
              ('provision_tenant(uuid,text,text)'),
              ('assert_tenant_context()')) AS x(sig)
    WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');
    IF v_nopriv IS NOT NULL THEN
        RAISE EXCEPTION
            'V15 自证失败(d): 当前角色 % 对以下函数没有 EXECUTE 权限 -> %。'
            '典型成因：环境做过 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固，'
            '而第 2 节的授权段未覆盖到。这会表现为"开通在建租户时报 permission denied"，'
            '属运行期才发现的错误 —— 故在迁移期断言。',
            current_user, v_nopriv;
    END IF;
END;
$v15_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   本迁移只新增两个函数、不建表、不写业务行 ⇒ 回滚无数据损失风险。
--     DROP FUNCTION IF EXISTS provision_tenant(uuid, text, text);
--     DROP FUNCTION IF EXISTS assert_tenant_context();
--     DELETE FROM schema_migration     WHERE version = 'V15';
--     DELETE FROM flyway_schema_history WHERE version = '15';
--   🛑 已开通的 tenant / region / store / staff 行【不受回滚影响】——
--      它们是数据不是 schema。若要清理测试租户，须在租户上下文内按 FK 序删除。
-- ============================================================================