\set ON_ERROR_STOP on
BEGIN;
-- ============================================================================
-- V18 迁移: 设备建档通路（门店级调理设备 device 台账的写入原语）
--
-- 【这个迁移为什么存在 —— 与 B-10（band）同型，但严重度更高一线】
--
--   `ProvisioningBoundaryGateTest` 把 `device` 登记在「未开通账」里，
--   当时它同样被写成一条"边界事实"。本轮（B-11）勘察后确认它也是缺陷：
--
--     device_dispatch.device_id → NOT NULL REFERENCES device (device_id)   [V5 L441]
--
--   而 `device` 在生产代码里【零写入方】、真库【零行】（实测：count(*) = 0），
--   全仓唯一的 `INSERT INTO device` 也只在测试夹具里。
--   ⇒ 任何一次 D6 `POST /device-dispatches` 都会失败。
--
--   【🛑 与 B-10 的关键差别：这里**连存在性预检都没有**】
--     B-10（band）时，四张 band_* 表还有各自的测试类在夹具里建 band；
--     而 `device` 这条链上，**生产代码里没有任何 device 存在性检查**：
--       · `PlanService.createDispatch`（L230）只检查 plan 已审核（P0-16），
--         然后直接把 `req.deviceId()` 解析成 UUID 就 INSERT；
--       · `FulfillmentLedger.insertDeviceDispatch`（L358）是一条裸 INSERT；
--       · 没有任何 `deviceExists()` 之类的方法存在（实测 grep 全仓 main = 0 命中）。
--     ⇒ 于是"设备不存在"这件事**完全没有可读的业务错误**，
--       它只能以一条 PostgreSQL 的 `23503` 出现在生产日志里。
--
--   【实测证据（2026-09-27，真库 diaoyuanyun_dev，应用角色 diaoyuanyun 非超级用户）】
--     探针 `_work/v18_device_gap_probe.sql` 按 D6 的**真实生产语句序列**走：
--       ✅ ① 缺口复现            SQLSTATE=23503
--                                消息=插入或更新表 "device_dispatch" 违反外键约束 "device_dispatch_device_id_fkey"
--       🛑 ② 建档后【仍然失败】  SQLSTATE=23503
--                                消息=插入或更新表 "device_dispatch" 违反外键约束 "fk_device_dispatch_plan_version"
--       ✅ ③ 无上下文            SQLSTATE=42501（与 23503 可区分）
--     ② 那条暴露了一个**第二前置**：`device_dispatch` 除 device 外还引用
--       `plan(plan_id, version)`（`fk_device_dispatch_plan_version`）⇒ 本迁移
--       【不】负责 plan（它已有写入方 PlanService），但它决定了本迁移自证 (e) 的
--       E2E 段必须同时备好 plan 行才有意义 —— 否则"建档后成功"证不出来。
--       🛑 这正是"一条缺口修好了不等于端到端通了"的形态，故本文件把它写成注释。
--
-- 【为什么建档通路【不】是 HTTP 端点（有意形态，不是没做完）】
--   逐条核对契约 `openapi-v1.0.0.yaml` 的全部 40 个 path：
--     · 没有任何 path 是"创建设备"；
--     · 唯一的设备相关 path 是 D6 `POST /device-dispatches`，而它的
--       summary 逐字是「D6 设备参数下发（需方案已审核）」——
--       操作的是**已存在的**门店设备（下发参数），不是"创建设备"；
--     · 它自己的 description 还逐字把 band 排除：
--       「🛑 本接口操作的是【门店级调理设备 device】（下行、可追责），
--         与【客户级手环 band】是两本台账、不得合并。」
--   ⇒ 与 B-7（组织开通）/ B-10（手环绑定）完全同型：**契约化决策**。
--     给建档加对外端点属契约 MAJOR 变更，且要先回答契约回答不了的问题：
--     「谁有权往门店建设备台账」？（x-callable-roles 里 therapist / meridian /
--     admin 都被 D6 覆盖，但那是"下发"的权，不是"建档"的权。）
--   ⇒ 故本迁移提供【数据库层原语】：两个幂等的 plpgsql 函数。可被运维 psql
--     直接调用，也可被应用内**不对外暴露**的 DeviceService 调用（与 B-7 / B-10 同款）。
--
-- 【🛑 核心机制一：建档必须【自己】建立 RLS 上下文】
--   device 表（V5 L145-152）是 ENABLE + FORCE ROW LEVEL SECURITY，策略 fail-closed：
--       tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   未设上下文时 WITH CHECK 为 false ⇒ INSERT **抛错**（不是静默零行 ——
--   静默零行发生在 SELECT 的 USING 上，这个区别很容易记反）。
--   故本函数把「建立上下文」+「自证上下文已生效」绑在写入之前。
--
-- 【🛑🛑 核心机制二（本迁移最重要的一处，且它是【实测抓出来的】）：
--     `ON CONFLICT (device_id)` 在【跨租户撞号】时会【静默 DO NOTHING】，
--     若只按 ROW_COUNT 判定，就会对一个手里一行都没有的租户报 'ALREADY_EXISTS'】
--
--   起因：`device_pkey` 是 **单列** 主键 `PRIMARY KEY (device_id)`（实测 device 表
--   的 6 条约束里 device_pkey = PRIMARY KEY (device_id)，而 `uq_tenant_device_device_id`
--   = UNIQUE (tenant_id, device_id) 只是**给复合外键用的载体**）。
--   ⇒ 语义上「同一台设备的 device_id 一生只属于一个租户」是**既有 schema 的既定事实**。
--
--   而 `INSERT ... ON CONFLICT (device_id) DO NOTHING` 的推断目标是那个**全局**主键，
--   故当 device_id 已被【别的租户】占用时：
--     · 本租户上下文里 SELECT 看不见那一行（RLS 的 USING 把它挡在外面）；
--     · 但主键冲突仍然发生 ⇒ PG 走 DO NOTHING ⇒ **ROW_COUNT = 0**；
--     · 且它**不报错**、**不改任何行**。
--
--   【实测（本机 PG 17.11，真库同构事务，`_work/v18_onconflict_crosstenant_probe.sql`）】
--     · 租户 A 上下文建 device_id=X ⇒ INSERT 0 1
--     · 租户 A 重复同一 device_id    ⇒ ROW_COUNT=0    ← 正确的幂等命中
--     · 租户 B 上下文用同一 device_id ⇒ ROW_COUNT=0    ← 🛑 静默！且租户 B 里可见行数 = 0
--   ⇒ 若函数写成"ROW_COUNT=0 ⇒ ALREADY_EXISTS"，则对租户 B 的回答是
--     「你已经有这台设备了」—— 而租户 B 里**一行都没有**。
--     后果不是报错，而是：调用方据此认为"设备已就绪，继续下发"，
--     紧接着的 `INSERT INTO device_dispatch` 以 **23503** 失败。
--     **即：本迁移会造出一个"返回值说 ALREADY_EXISTS、下游仍然 23503"的缺陷** ——
--     正是本仓反复要防的"返回值与行为不一致"形态（与 E2 在 2026-09-26 修掉的
--     "看起来不覆盖、实际没覆盖"同族）。
--
--   【怎么修：用一个**无窗口**的判定，而不是"先读后写"】
--     判定链（三段，全部来自库层的原子事实）：
--       ① INSERT ... ON CONFLICT (device_id) DO NOTHING
--          · ROW_COUNT = 1 ⇒ 本次真的建出来了 ⇒ 'CREATED'
--       ② ROW_COUNT = 0 ⇒ 全局已存在这一行。**它在谁名下？**
--          在已自证的**本租户上下文**里 `SELECT count(*) FROM device WHERE device_id = ...`
--          · 看得见（=1）⇒ 是本租户的 ⇒ 'ALREADY_EXISTS'（幂等，如实）
--          · 看不见（=0）⇒ 🛑 插不进去却又看不见 ⇒ **它在别的租户名下** ⇒ RAISE
--     为什么这**没有**"先读后写"的并发窗口：
--       "存在"这个前提不是来自我的读，而是来自**主键冲突**这个库层原子事实 ——
--       能冲突就证明它确实存在。我的读只用来回答"它是不是我的"，
--       而"读不到"这件事在 RLS 下是**稳定**的（别人的行永远读不到，不是时序问题）。
--     🛑 与 V17 `bind_band` 的对比值得写清：那里用 `ON CONFLICT (tenant_id, customer_id)`
--        —— 推断目标是**部分**唯一索引，**含 tenant_id** ⇒ 天然不会跨租户误判；
--        这里推断目标是**全局**主键 ⇒ 必须自己补上这一层判定。
--        两处的差别**不在风格，在推断目标里有没有租户维度**。这是可迁移的教训。
--
-- 【为什么跨租户撞号用 RAISE 而不用返回值】
--   B-10 的 `bind_band` 有第四态 `CUSTOMER_ALREADY_HAS_ACTIVE_BAND`，
--   理由是"业务不变量胜出"是一种**正常结果**（客户已有带子，不该再绑一支）。
--   建档没有这种"正常结果"：同一台物理设备不可能同时属于两个租户。
--   它是**数据冲突**，需要人工介入（要么 device_id 传错了，要么设备被错误调拨）。
--   故用 RAISE，让"这件事发生了"无法被调用方当成一个可以继续流程的返回值。
--   ⇒ 附带结论（值得逐字写清，免得下一个人以为漏了三态）：
--     **register_device 是两态**（CREATED / ALREADY_EXISTS）+ 两类异常
--     （入参/门店不存在、跨租户撞号）。四态是 `bind_band` 因"换机"
--     这件业务选择才需要的 —— 建档没有对应的选择。
--
-- 【🛑 核心机制三：为什么不暴露 status = 'maintenance'】
--   device.status 的 CHECK 冻结了三个值 active / maintenance / retired（V5 L138）。
--   本函数暴露两个原语：register（建 / 幂等）与 retire（active → retired）。
--   🛑 刻意**没有** enter_maintenance / leave_maintenance ⇒ 'maintenance' 在任何
--      生产路径上都**不可达**（实测：全仓 main 目录里 'maintenance' 对 device 表的
--      写入 = 0 命中）。这种"CHECK 里有、但没人能写进去"的值是**死枚举值**，
--      它的问题不是"少了功能"，而是**读侧会为它写出永远不成立的判断**
--      （例如"设备在维保期所以不下发"这条规则，永远为假 ⇒ 一条静默的死规则）。
--   ⇒ 处置：本迁移**不**假装实现维保流程（那需要一份状态机 + 责任定义，
--      属契约 MAJOR 变更）；而是把这条事实登记进 README 的缺口清单，
--      并在本文件里逐字写明"若将来要支持维保，必须同时定义：谁置入、谁置出、
--      维保期是否阻断下发"—— 否则它又会变成一个没人负责的半成品。
--   🛑 同理不暴露 retired 作为入参 status：归档有独立原语与独立返回值语义，
--      把它塞进 register 会让"建档"与"归档"这两件事共用一个入口。
--
-- 【为什么不在函数里冻结 model 的取值集】
--   model 的 CHECK 已冻结 `杠2 / 现有`（V5 L134）。函数**不重复冻结**：
--   两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
--   与 V17 `bind_band` 对 vendor 的处置**结论相同、理由不同**：
--     · vendor：取值集"待厂商确认"⇒ 冻结一个明确未定的集合属代拍口径；
--     · model：取值集已由 CHECK 冻结 ⇒ 再写一遍是重复定义。
--   两种理由都指向"不要在函数里再写一遍"，但它们不是同一条理由 —— 故都写出。
--
-- 【幂等口径（与 V1~V17 同款，四件套）】
--   ① CREATE OR REPLACE FUNCTION（PG 函数天然支持 replace）
--   ② 写入侧 ON CONFLICT DO NOTHING + 返回两态（+ 两类 fail-closed 异常）
--   ③ 自证块：不满足即 RAISE EXCEPTION，整个迁移回滚
--   ④ 不预置任何业务数据（自证探针在一个子事务里跑，结束整体撤销）
--
-- 【🛑 本文件语句顺序不可调换：授权 → 登记 → 自证 → 完成】
--   自证 (d) 断言 schema_migration 里 V18 登记行数 = 1，故【登记必须排在自证之前】。
--   与 V15 / V17 同款（V14 把登记放在末尾是因为它的自证不读登记表）。
--   ⚠️ 这是本仓**第三次**在文件头写这句话。它与 description 长度上限一样，
--      属于"文件内注释无法自保"的纪律 —— 故 119 反向验证把它做成了**受控注入**
--      （把登记挪到自证之后 ⇒ 必须报 (d)）。
--
-- 【回滚说明】
--   本迁移只新增两个函数、不建表、不写业务行。
--     DROP FUNCTION IF EXISTS register_device(uuid, uuid, uuid, text, text);
--     DROP FUNCTION IF EXISTS retire_device(uuid, uuid);
--     DELETE FROM schema_migration     WHERE version = 'V18';
--     DELETE FROM flyway_schema_history WHERE version = '18';
--   🛑 已建档的 device 行【不受回滚影响】—— 它们是数据不是 schema。
--      若要清理，顺序必须是 device_dispatch → device → store → tenant
--      （device_dispatch.device_id → device.device_id 是 NOT NULL 引用；
--        实测外键名 `device_dispatch_device_id_fkey`，V16 后已是复合形态
--        `FOREIGN KEY (tenant_id, device_id) REFERENCES device(tenant_id, device_id)`）。
-- ============================================================================


-- ============================================================================
-- 第 0 节 · 前置自检：本迁移的【前提】必须成立，否则后面的一切都是空中楼阁
--
--   本迁移的设计建立在四个已存在的 schema 事实之上，四条都会在下面机械断言：
--     ① device 是 FORCE RLS
--        —— 否则"建立上下文"是多余动作，而本函数的价值主要就在这；
--     ② `device_pkey` 是**单列** `(device_id)`
--        🛑 这一条是【核心机制二】的**唯一前提**。若哪天有人把它改成
--           `(tenant_id, device_id)`，那么"跨租户撞号"这件事**就不存在了**
--           （两个租户可以合法地各有一台同 id 设备），于是：
--             · 本函数的 RAISE 分支变成**死代码**；
--             · 自证 (e5) 会以"竟然成功了"的面目失败 —— 一条**归因错误**的红。
--           故在此提前断言，让红指向真原因。
--     ③ `uq_tenant_device_device_id` = UNIQUE (tenant_id, device_id) 存在
--        —— 它是 `device_dispatch_device_id_fkey` 与 `device_store_id_fkey`
--           两条复合外键的**引用目标**（PG 要求被引用列是唯一键）。
--           缺了它，V16 的复合外键根本上不去 —— 那会静默退回"引用存在但不归属一致"。
--     ④ `device_store_id_fkey` 是**复合**形态（含 tenant_id）
--        —— V16 的成果。它决定"跨租户门店引用"是否被库层拒绝。
--           本函数会显式查一次 store（归因质量），但**库层那道必须仍然在**：
--           函数可以被人绕过（直接写 SQL），外键不能。
-- ============================================================================

DO
$v18_precond$
DECLARE
    v_rls      boolean;
    v_pk       text;
    v_uq       text;
    v_store_fk text;
BEGIN
    -- ① device 存在且 FORCE RLS
    SELECT c.relrowsecurity INTO v_rls
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'device';

    IF v_rls IS NULL THEN
        RAISE EXCEPTION
            'V18 前置失败: 表 device 不存在。本迁移的设备建档原语依赖它（V5 L127 建表）。';
    END IF;
    IF NOT v_rls THEN
        RAISE EXCEPTION
            'V18 前置失败: 表 device 未启用 ROW LEVEL SECURITY。'
            '本迁移的核心价值之一是"建档必须自己建立租户上下文"，'
            '若 device 没有 RLS，这个守卫就失去了守护对象 —— 不是"可以省略"，'
            '而是"前提被推翻了"，必须先解释清楚才能继续。';
    END IF;

    -- ② device_pkey 必须是【单列】device_id —— 核心机制二的唯一前提
    SELECT pg_get_constraintdef(con.oid) INTO v_pk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'device' AND con.contype = 'p';

    IF v_pk IS NULL THEN
        RAISE EXCEPTION 'V18 前置失败: 表 device 没有主键 —— 无法建立 device_id 的冲突判定。';
    END IF;
    IF v_pk !~ 'PRIMARY KEY\s*\(\s*device_id\s*\)' THEN
        RAISE EXCEPTION
            'V18 前置失败: device 的主键形态变了（当前: %）。'
            '本迁移的【核心机制二】完全建立在"device_pkey 是单列 device_id"这个事实上：'
            '正因为它不含 tenant_id，"同一 device_id 被另一租户占用"才可能发生；'
            '也正因为会发生，函数才必须把"冲突了但我看不见 ⇒ 是别人的"这条判定补上。'
            '若主键已改为 (tenant_id, device_id)，则该冲突不可能发生，'
            '本函数的跨租户分支变成死代码，而自证 (e5) 会以【归因错误】的方式失败。'
            '🛑 遇到这条报错，正确的反应是【修本迁移的设计说明与自证】，'
            '而不是把这里的判据改宽 —— 前提变了，结论必须重新推导。', v_pk;
    END IF;

    -- ③ uq_tenant_device_device_id 存在（复合外键的引用目标）
    --   🛑 判定用【列集合】而不是字符串 LIKE —— V16 在 2026-09-27 已被这条坑过一次：
    --      pg_get_constraintdef 的输出按 conkey 顺序拼列名，故 LIKE '(tenant_id, %'
    --      实际断言的是"tenant_id 恰好排第一"，而不是"tenant_id 参与了这个键"。
    --      正确的判定是 conkey 的**集合相等**。
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        WHERE c.relname = 'device' AND con.contype = 'u'
          AND (SELECT array_agg(a.attname ORDER BY a.attname)
                 FROM unnest(con.conkey) AS k(attnum)
                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
              = ARRAY['device_id', 'tenant_id']::name[]
    ) THEN
        RAISE EXCEPTION
            'V18 前置失败: device 上没有 UNIQUE (tenant_id, device_id) 载体。'
            '它是 device_dispatch_device_id_fkey / device_store_id_fkey 两条复合外键的'
            '【引用目标】—— PG 要求被引用列是唯一键。'
            '🛑 实测真库上它叫 uq_tenant_device_device_id；本判据【不按名字找】而按列集合找，'
            '因为名字对语义没有任何约束力（改名不该让门禁变红）。';
    END IF;

    -- ④ device_store_id_fkey 是复合形态（V16 的成果必须仍在）
    SELECT pg_get_constraintdef(con.oid) INTO v_store_fk
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
     WHERE c.relname = 'device' AND con.contype = 'f'
       AND (SELECT count(*) FROM unnest(con.conkey)) = 2
       AND (SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(con.conkey) AS k(attnum)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
           = ARRAY['store_id', 'tenant_id']::name[];

    IF v_store_fk IS NULL THEN
        RAISE EXCEPTION
            'V18 前置失败: device 上没有复合形态 (tenant_id, store_id) 的外键。'
            '这说明 V16（跨租户引用完整性）的成果【在 device 上被回退了】。'
            'V18 的函数会显式查一次 store 做归因，但那是【第一层】；'
            '库层的复合外键是【第二层】—— 函数可以被人绕过（直接写 SQL），外键不能。'
            '缺了它，"在租户 A 里把设备挂到租户 B 的门店下"就重新变成一条敞开的路径。';
    END IF;
END;
$v18_precond$;


-- ============================================================================
-- 第 1 节 · 建档原语函数
-- ============================================================================

-- ---------------------------------------------------------------------------
-- register_device(p_tenant_id, p_device_id, p_store_id, p_model, p_param_template_id)
--
--   【返回两态（逐字说清，调用方据此审计留痕）】
--     · 'CREATED'        —— 本次调用建出了这一行
--     · 'ALREADY_EXISTS' —— 幂等命中：这一行【已在本租户名下】
--   【两类 fail-closed 异常】
--     · 门店在本租户内不存在          → RAISE（消息含自证过的上下文，便于归因）
--     · device_id 已被【另一租户】占用 → RAISE
--       🛑 后一条是本迁移的核心机制二。它**不能**是返回值 ——
--          它不是"另一种正常结果"，而是数据冲突。见文件头那一节的实测证据。
--
--   【为什么没有"换机"那样的第三、第四态】
--     见文件头「为什么跨租户撞号用 RAISE 而不用返回值」末段：
--     四态是 `bind_band` 因"换机"这件**业务选择**才需要的；建档没有对应的选择。
--
--   【设计取舍一：store 存在性用显式查 + RAISE，而不是让外键报 23503】
--     与 V17 `bind_band` 对 customer 的处置逐字同款，理由（归因质量）也逐字相同：
--       · 库层拒绝（复合外键）⇒ 一条 PostgreSQL 的 23503，调用方要自己解析约束名
--       · 本检查              ⇒ 一条写明"门店 X 在租户 Y 内不存在"的业务错误
--     两者都 fail-closed，区别只在诊断成本。
--     本仓的判据一贯是"根因断言排在后果断言之前"，此处同理：先给可读原因，再让库兜底。
--     🛑 SELECT 在 RLS 下的语义注意：这里**不会**出现"查不到可能是看不见"的歧义 ——
--        因为上下文已在 (3) 建立并自证，且 store 与 device 用**同一条**策略表达式。
--        （与 V17 对 customer 的那段说明同款。）
--
--   【设计取舍二：为什么不把 p_store_id 换成"门店名"之类更好用的入参】
--     建档是**运维/集成**动作（内部通路，无对外端点），调用方本来就持有 UUID。
--     引入"按名找门店"会把一次建档变成一个可能建错门店的动作（同名门店是常态），
--     而 device.store_id 决定"这台设备的参数下发到哪个门店"—— 建错的代价是
--     一台真实设备的参数下发到错误的门店。故坚持 UUID 入参。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION register_device(
    p_tenant_id         uuid,
    p_device_id         uuid,
    p_store_id          uuid,
    p_model             text,
    p_param_template_id text DEFAULT NULL
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v18_register$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_store      int := 0;
    v_mine       int := 0;
    v_affected   int := 0;
BEGIN
    -- (1) 入参守卫 —— 与 V15 provision_tenant / V17 bind_band 同款：
    --     DDL 的 NOT NULL 挡不住空串，而空串在语义上同样无意义，且会静默流进台账。
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'register_device: p_tenant_id 不可为空（device.tenant_id 是 NOT NULL）';
    END IF;
    IF p_device_id IS NULL THEN
        RAISE EXCEPTION
            'register_device: p_device_id 不可为空（device.device_id 是主键，'
            '且它是 device_dispatch.device_id 的引用目标 —— 没有它，D6 下发无法落库）';
    END IF;
    IF p_store_id IS NULL THEN
        RAISE EXCEPTION
            'register_device: p_store_id 不可为空（device.store_id 是 NOT NULL，'
            '且是复合外键 (tenant_id, store_id) → store 的一端）';
    END IF;
    IF p_model IS NULL OR btrim(p_model) = '' THEN
        -- 🛑 只挡空值，【不】冻结取值集 —— 理由见文件头「为什么不在函数里冻结 model」。
        RAISE EXCEPTION
            'register_device: p_model 不可为空串（device.model 是 NOT NULL 且有业务含义，'
            '取值集已由 CHECK 冻结为 杠2 / 现有 —— 本函数不重复冻结，避免口径分叉）';
    END IF;

    -- (2) 🛑 上下文一致性守卫 —— 拒绝"静默改写调用方既有上下文"
    --     理由与 V17 bind_band 的 (2) 逐字相同，此处摘要：
    --       set_config(app.tenant_id, ..., true) 是**事务级**设置，且**不会**在函数入口
    --       自动重置。若调用方在一个"已设成租户 B"的事务里调用本函数传租户 A，
    --       不做检查就会把上下文**静默改写成 A**：后面的写是 A 的，而调用方以为是 B 的。
    --       本函数显式拒绝这种"覆盖既有上下文"的调用（fail-closed）。
    --     为什么宁失败不可静默覆盖：建档会写 device 行（归属由 tenant_id 决定），
    --     而 device.store_id 决定参数下发到哪个门店 —— 写错租户就是"设备挂错门店"。
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_device: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '理由：set_config(app.tenant_id, ..., true) 是事务级设置且【不会】在函数入口自动重置。'
            '若在此静默覆盖，本函数之后的全部写入都会落在 % 名下，而调用方仍以为在 % 名下。'
            '调用方须先结束当前事务（或使用全新连接）再建档。',
            v_ctx_before, p_tenant_id::text, p_tenant_id::text, v_ctx_before;
    END IF;

    -- (3) 建立上下文（is_local := true ⇒ 事务结束自动失效）
    --     🛑 用 set_config（普通函数、参数可绑定），不用 SET LOCAL
    --        （后者必须把值拼进 SQL 文本 ⇒ 有注入面）。与 V15 / V17 同款。
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);

    -- (3b) 自证上下文确实生效 —— 不靠"我调了 set_config 所以它当然生效"
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'register_device 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 🛑 门店必须**在本租户内**存在（见「设计取舍一」）
    --     库层还有 `device_store_id_fkey (tenant_id, store_id) → store (tenant_id, store_id)`
    --     兜底（V16 的成果，实测跨租户引用被 23503 拒）。
    SELECT count(*) INTO v_store
      FROM store
     WHERE store_id = p_store_id AND tenant_id = p_tenant_id;

    IF v_store <> 1 THEN
        RAISE EXCEPTION
            'register_device: 门店 % 在租户 % 内不存在（本租户可见行数 = %），拒绝建档。'
            '🔴 若该门店确实存在，最常见的原因是租户上下文不对 —— '
            'store 的 RLS 是 fail-closed 的，上下文错了 SELECT 会静默返回 0 行，'
            '于是"不是我的门店"与"我看不见我的门店"给出同一个结论。'
            '本次上下文的实际值（已自证）= %。',
            p_store_id, p_tenant_id, v_store, v_ctx;
    END IF;

    -- (5) 🛑🛑 幂等写入 + 跨租户撞号判定 —— 本迁移的核心（见文件头「核心机制二」）
    --     三段判定全部来自库层原子事实，**没有**"先读后写"的并发窗口：
    --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的
    --       ② ROW_COUNT=0 且本租户看得见 ⇒ 是本租户的行 ⇒ 幂等命中
    --       ③ ROW_COUNT=0 且本租户看不见 ⇒ 插不进去又看不见 ⇒ 在别的租户名下
    --     🛑 为什么第 ③ 段是**正确**的而不是"猜测"：
    --        能冲突就证明"这一行在全局存在"。而 RLS 的可见性是**稳定**的
    --        （别人的行在任何时刻都读不到，不是时序问题），
    --        故"读不到"精确地等价于"不属于本租户"。逻辑上不依赖窗口。
    INSERT INTO device (device_id, tenant_id, store_id, model, param_template_id,
                        status, created_by)
    VALUES (p_device_id, p_tenant_id, p_store_id, p_model, p_param_template_id,
            'active', 'device-registration')
    ON CONFLICT (device_id) DO NOTHING;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    -- ① 本次真的建出来了
    IF v_affected = 1 THEN
        RETURN 'CREATED';
    END IF;

    -- ② 未插入 ⇒ 全局已存在。它在谁名下？
    --    🛑 这次读在【已自证的本租户上下文】里，故它只看得到本租户的行。
    SELECT count(*) INTO v_mine
      FROM device
     WHERE device_id = p_device_id;

    IF v_mine = 1 THEN
        RETURN 'ALREADY_EXISTS';
    END IF;

    -- ③ 插不进去却又看不见 ⇒ 它在别的租户名下
    --    🛑 这条 RAISE 就是"若照抄 V17 形态就会造出的缺陷"的封堵点。
    --       没有它，上面会 return 'ALREADY_EXISTS'，而本租户一行都没有 ——
    --       调用方据此认为设备已就绪，紧接着的 device_dispatch 写入以 23503 失败。
    RAISE EXCEPTION
        'register_device: device_id % 已被【另一租户】占用 —— 无法在本租户 % 名下建档。'
        '判定依据（无窗口）：INSERT ... ON CONFLICT (device_id) DO NOTHING 被【主键冲突】拦下'
        '（ROW_COUNT=0），但在已自证的本租户上下文里 SELECT 又看不见这一行（可见行数=0）——'
        '"冲突了"证明它全局存在，"看不见"证明它不属本租户 ⇒ 它属于别的租户。'
        '🛑 为什么这不是"另一个 ALREADY_EXISTS"：device_pkey 是【单列】device_id，'
        '即"同一台设备的一生只属于一个租户"是既有 schema 的既定事实。'
        '这不是幂等重放，而是数据冲突，需要人工确认是 device_id 传错了、还是设备被错误调拨。'
        '若在此返回 ALREADY_EXISTS，调用方会以为设备已就绪并继续下发，'
        '而随后的 INSERT INTO device_dispatch 仍会以 23503 失败 —— 即"返回值在撒谎"。',
        p_device_id, p_tenant_id::text;
END;
$v18_register$;


-- ---------------------------------------------------------------------------
-- retire_device(p_tenant_id, p_device_id)
--
--   【返回三态】
--     · 'RETIRED'         —— 本次调用把它从 active 置为 retired
--     · 'ALREADY_RETIRED' —— 它已经是 retired（重放，幂等）
--     · 'NOT_FOUND'       —— 本租户内看不到这一行
--        🛑 措辞刻意是 NOT_FOUND 而不是 "NOT_EXISTS"：在 FORCE RLS 下
--           "不存在"与"存在但当前上下文看不见"**给出同一个结果**。
--           用"不存在"这个词会把后一种情形说成事实，属对调用方的误导。
--           调用方看到 NOT_FOUND 时应先复核租户上下文，再去怀疑数据缺失。
--           （与 V17 `unbind_band` 的三态措辞逐字同款。）
--
--   【🛑 为什么归档是 UPDATE 而不是 DELETE】
--     两条独立的理由：
--       ① 审计：device_dispatch 通过 `device_dispatch_device_id_fkey` 引用本表
--          （NOT NULL）。删掉一行会让历史下发记录失去可追溯对象 ——
--          而 D6 的语义恰是「下行、**可追责**」（与手环上行"不得作不利依据"相对）。
--          可追责的台账不允许删除被追责的主体。
--       ② 业务：`retired` 与 `active` 的区分本身就是"这台设备还在不在服役"，
--          它是**状态**而不是"存在与否"。删掉之后"归档于何时"不可答。
--       🛑 副作用（也是刻意保留的性质）：'retired' 行仍然占据 device_id 主键 ⇒
--          重放建档返回 ALREADY_EXISTS（由自证 (e8) 钉住）。
--          这意味着**主键复用需要先物理删除归档行**，而那是一个显式的人工决定，
--          不该被一次"顺手重建"静默完成。
--
--   【为什么只允许 active → retired（不含 maintenance）】
--     见文件头「核心机制三」：'maintenance' 在生产路径上不可达，本迁移不假装实现它。
--     谓词写成 `status <> 'retired'` 而**不是** `status = 'active'`，这是有意的：
--     若哪天有人补上了维保通路（把设备置成 maintenance），
--     `status = 'active'` 会让"归档一台维保中的设备"变成 NOT_FOUND
--     （执行了 UPDATE，但 0 行命中 ⇒ 落到哪一态取决于写法，极易写错）；
--     而 `status <> 'retired'` 的语义是"只要还没归档就能归档" ——
--     这才是归档该有的口径。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION retire_device(
    p_tenant_id uuid,
    p_device_id uuid
)
    RETURNS text
    LANGUAGE plpgsql
AS
$v18_retire$
DECLARE
    v_ctx_before text;
    v_ctx        text;
    v_cur_status text;
    v_rows       int := 0;
BEGIN
    -- (1) 入参守卫
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'retire_device: p_tenant_id 不可为空（device.tenant_id 是 NOT NULL）';
    END IF;
    IF p_device_id IS NULL THEN
        RAISE EXCEPTION 'retire_device: p_device_id 不可为空（device.device_id 是主键）';
    END IF;

    -- (2) 上下文一致性守卫（与 register_device 同款，逐字同理由）
    v_ctx_before := current_setting('app.tenant_id', true);
    IF v_ctx_before IS NOT NULL
       AND btrim(v_ctx_before) <> ''
       AND v_ctx_before <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'retire_device: 本事务已持有租户上下文 %，与本次调用传入的租户 % 不一致 —— 拒绝执行。'
            '同 register_device 的理由：本函数会覆盖它，从而让"后续写入去了另一个租户"静默发生。'
            '归档尤其危险 —— 它决定"这台设备还在不在服役"，而 device_dispatch 的可追责性依赖它。',
            v_ctx_before, p_tenant_id::text;
    END IF;

    -- (3) 建立并自证上下文
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    v_ctx := assert_tenant_context();
    IF v_ctx <> p_tenant_id::text THEN
        RAISE EXCEPTION
            'retire_device 自证失败: set_config 之后读回的 app.tenant_id (%) 与传入的租户 (%) 不一致',
            v_ctx, p_tenant_id::text;
    END IF;

    -- (4) 读当前状态（在已自证的上下文内）
    SELECT status INTO v_cur_status
      FROM device
     WHERE device_id = p_device_id AND tenant_id = p_tenant_id;

    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_cur_status = 'retired' THEN
        RETURN 'ALREADY_RETIRED';
    END IF;

    -- (5) 状态迁移
    --     🛑 谓词里带 `status <> 'retired'` 是为了让这条 UPDATE 在并发下**正确**：
    --        两个并发归档都会通过第 (4) 步的读（都读到非 retired），
    --        若 UPDATE 不带这个条件，两次都会"成功"，第二次的 updated_at 会覆盖第一次的
    --        —— 一次静默的数据回退（"归档时间"被推后）。
    --        带上它之后，第二次的 ROW_COUNT = 0 ⇒ 落到 ALREADY_RETIRED。
    --        （这与 V17 的 unbind_band 是同一类教训，也与 E2 在 2026-09-26 修掉的
    --          "看起来不覆盖、实际没覆盖"同族：保证必须由【库层条件】给出，
    --          不能依赖一次可能过期的读。）
    --     🛑 为什么同时写 `tenant_id = p_tenant_id`：让这条 UPDATE 的**租户维度在 SQL 里可见**，
    --        与 RLS 策略形成两层（RLS 兜底、谓词自证意图）。少写它 RLS 仍会兜住，
    --        但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读。
    --        本仓把可读性当成安全属性的一部分。
    UPDATE device
       SET status     = 'retired',
           updated_at = now()
     WHERE device_id  = p_device_id
       AND tenant_id  = p_tenant_id
       AND status    <> 'retired';

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 1 THEN
        RETURN 'RETIRED';
    END IF;
    RETURN 'ALREADY_RETIRED';
END;
$v18_retire$;


-- ============================================================================
-- 第 2 节 · 授权
--
--   与 V15 / V17 第 2 节同款，理由逐字相同（此处摘要，不复述全文）：
--     · PG 对【函数】的默认权限是"给 PUBLIC 授予 EXECUTE"，
--       故本节的 GRANT 在常规环境里是 no-op；写它是"显式优于隐式"，
--       以及在"生产环境 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固"后
--       让应用角色的执行权限仍然到位。
--     · 与 CURRENT_USER + IF EXISTS 组合，使本节在任何环境下都不报错。
--   🛑 权限是否真的够，由第 4 节自证 (d2) 用 has_function_privilege 机械断言；
--      "授权段是否真的执行过"由 (d3) 断 proacl 的【显式项】—— 见那里的说明。
-- ============================================================================

DO
$v18_grant$
DECLARE
    v_role    text;
    v_granted int := 0;
BEGIN
    v_role := current_user;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'register_device') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION register_device(uuid, uuid, uuid, text, text) TO %I',
            v_role);
        v_granted := v_granted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'retire_device') THEN
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION retire_device(uuid, uuid) TO %I', v_role);
        v_granted := v_granted + 1;
    END IF;

    IF v_granted <> 2 THEN
        RAISE EXCEPTION 'V18 授权段: 只授予了 % / 2 个函数的执行权限（函数未创建成功？）', v_granted;
    END IF;
END;
$v18_grant$;


-- ============================================================================
-- 第 3 节 · 迁移版本登记（🛑 必须在第 4 节自证【之前】—— 自证 (d) 读本表）
--
--   🛑🛑 description 列是 VARCHAR(256)，【不得超过】。本仓这是**第 3 次**面对它：
--        · V15 踩过（初版 263 字符 ⇒ 「对于可变字符类型来说，值太长了(256)」）；
--        · V17 又踩过（初稿 358 字符，原文一模一样）；
--        · 故 V17 把教训升级成一个可机械核对的数字：**先量长度再写**。
--   本行的量法（把整条字符串当 ASCII 数一遍）+ 结果：
--        printf 量得 = 234 字符（上限 256，余量 22）
--   🛑 为什么必须"量"而不是"目测"：这条字符串里全是英文短横与箭头，视觉上"看起来不长"，
--      而 V17 那条超了 102 个字符 —— 目测在这件事上不可靠。
--   🛑 为什么不在迁移里用 char_length() 断言它：本行是 INSERT 的**值**，
--      要断言就得再读一次（另写一条 SELECT）—— 那会把"一处事实"变成"两处要保持一致"。
--      量长度是**写入前**的动作，属人与本仓纪律之间的约定，不是库层能自证的东西。
-- ============================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V18', 'device provisioning primitive: register_device() + retire_device(). device had no production writer, so D6 POST /device-dispatches failed with 23503 (FK device_id -> device.device_id). Ops path; contract has no create-device endpoint.')
ON CONFLICT (version) DO NOTHING;


-- ============================================================================
-- 第 4 节 · 自证（不满足即 RAISE ⇒ 整个迁移回滚）
--
--   (a) 两个函数必须真的存在
--   (b) register_device 的函数体必须含【五件套】—— 每一件都对应一类已被实测踩过的缺陷：
--        ① 建立上下文          set_config('app.tenant_id', ...)
--        ② 上下文自证          assert_tenant_context()
--        ③ 上下文一致性守卫    current_setting('app.tenant_id', true)
--        ④ 幂等写入的原子形态  INSERT INTO device ... ON CONFLICT (device_id) DO NOTHING
--        ⑤ 🛑 跨租户撞号的判定分支（本迁移的核心，且**它是实测抓出来的**）
--           若只断言 ④ 的 ROW_COUNT 而不区分"谁的"，就会造出
--           "对本租户报 ALREADY_EXISTS 而本租户一行都没有"的缺陷 ——
--           这条断言把那个分支的存在变成迁移期可判定的事实。
--   (c) retire_device 的函数体必须含 ①②③ 与"状态迁移而非删除"的证据
--   (d) 迁移登记 + 当前角色对两个函数真的有 EXECUTE 权限 + 授权段真的执行过（proacl 显式项）
--   (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
--        【本段于 2026-09-27 由 119 反向验证的实测抓出后从 V19 回补】
--        实测：用 `postgres`（rolsuper=t / rolbypassrls=t）跑本文件的 (e) 段时，
--        自证以 `(e5)` 报错 —— 消息是"租户 B 用租户 A 已占用的 device_id 建档
--        没有抛错（返回值 = ALREADY_EXISTS）"，读起来像【函数坏了】。
--        真因恰好相反：**函数完全正确**，是证据的效力前提不成立 ——
--        BYPASSRLS 绕过行级安全 ⇒ `set_config('app.tenant_id', 租户B)` 之后，
--        那条 `SELECT count(*) FROM device WHERE device_id = p_device_id`
--        **仍然看得见租户 A 的那一行** ⇒ v_mine = 1 ⇒ 判定链落到"是我的"分支 ⇒
--        返回 ALREADY_EXISTS。本迁移依赖 RLS 的判据共 4 条（e3 不落库 / e5 零行 /
--        e5 对照 / e8），它们全部建立在"当前角色受 RLS 约束"之上。
--        ⇒ 没有本守卫时，那 4 条会以【归因错误】的面目报红；而若有人照字面去"修函数"，
--          会改坏一个本来正确的实现。这与 V19 的 (a0) 是同一件事、同一段代码。
--   (a1) 🛑 能力守卫二：RLS 策略本身必须还按 app.tenant_id 隔离
--        (a0) 只排除了"完全绕过"这一种；更隐蔽的是**策略被改动或缺失**
--        （例如被改成 `USING (true)`），那会让同样的 4 条判据静默假绿
--        —— 所有租户互相可见，而自证全绿、连一条报错都没有。
--        ⇒ 断言策略【定义内容】而非存在性，且 USING 与 WITH CHECK **都**必须引用
--          app.tenant_id（只查一条会漏掉"读隔离在、写隔离没了"）。
--   (a) 两个函数必须真的存在（见下方 (a) 段）
--
--   (e) 🛑 行为验证 —— 本迁移最重要的一条。前四条都只检查"函数体长什么样"，
--       而"函数体看着对、跑起来不对"是本仓反复出现过的形态（自证必须证明**行为**）。
--       🛑 依赖 RLS 的 4 条判据（e3 不落库 / e5 零行 / e5 对照 / e8）的效力，
--          由 (a0)/(a1) 两段守卫保证 —— 见文件头说明。
--       共 9 段：
--         e1 建档 → CREATED，且行确实落库（status=active / store_id 对上）
--         e2 重放同一 device_id → ALREADY_EXISTS（幂等），且不得产生第二行
--         e3 门店不在本租户内 → RAISE（不是返回值），且必须证明【没有落库】
--         e4 归档 → RETIRED；再归档一次 → ALREADY_RETIRED（幂等），
--            且 updated_at 不得被第二次调用推后
--         e5 🛑🛑 跨租户撞号：租户 B 用租户 A 已占用的 device_id →
--            必须 RAISE（**不是** ALREADY_EXISTS），且消息里必须含"另一租户"。
--            这一条是本迁移相对 V17 形态的**唯一实质改动**，也是 119 反向验证的靶心。
--         e6 对照：同租户裸 INSERT（不同 device_id）→ 必须**成功**
--            （防"把正常路径一起打死"）
--         e7 🛑 跨租户门店引用：租户 A 里把 device.store_id 指向租户 B 的门店 → 
--            必须被【库层复合外键】拒，且理由必须是 23503 而不是 42501
--            （两者都"拒绝"，但证明的是完全不同的东西：23503 = V16 的外键；
--             42501 = RLS 的 WITH CHECK，说明上下文不对）
--         e8 归档行仍然占据主键 ⇒ 重放建档必须返回 ALREADY_EXISTS（幂等语义的一致性）
--         e9 清场前的正向计数：证明确实有行写进去过
--            （否则下面的 DELETE 是空操作，"清场成功"与"什么都没发生"同形）
--   (f) 探针清场 + 清场自证（一行业务数据都不许留下）
--
--   🛑 全部探针在一个 BEGIN ... EXCEPTION 子事务里跑，块结束时整体撤销 ——
--      迁移不得留下任何业务数据（V1~V17 一致的纪律）。
--      子事务的 EXCEPTION 会建立 savepoint，故内部 INSERT 无论成功与否都在块末撤销。
--
--   🛑 注意 e3 / e5 必须用**内层** BEGIN/EXCEPTION 包住那条会抛的语句，
--      否则一次预期的 RAISE 会把外层子事务标记为已回滚，
--      后续断言全部以「当前事务被终止」失败 —— 一条归因完全错误的红。
-- ============================================================================

DO
$v18_guard$
DECLARE
    v_register_body text;
    v_retire_body   text;
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
    v_bypass        boolean;
    -- ---- 探针租户 / 门店 / 设备 / 客户（18 前缀，与 V17 的 17 前缀互不重叠）----
    v_ta          uuid := '18000000-0000-0000-0000-00000000000a';
    v_tb          uuid := '18000000-0000-0000-0000-00000000000b';
    v_store_a1    uuid := '18000000-0000-0000-0000-0000000000a1';  -- e1/e2/e4/e6/e8
    v_store_b1    uuid := '18000000-0000-0000-0000-0000000000a2';  -- e7 的目标（租户 B 的门店）
    v_cust_a1     uuid := '18000000-0000-0000-0000-0000000000c1';  -- e9 的 plan 前置
    v_dev_a1      uuid := '18000000-0000-0000-0000-0000000000d1';  -- e1/e2/e4/e8
    v_dev_a2      uuid := '18000000-0000-0000-0000-0000000000d2';  -- e6 同租户裸 INSERT 对照
    v_dev_a3      uuid := '18000000-0000-0000-0000-0000000000d3';  -- e3 门店不存在（应不落库）
    v_dev_shared  uuid := '18000000-0000-0000-0000-0000000000d4';  -- e5 跨租户撞号（租户 A 先占）
    v_dev_b_new   uuid := '18000000-0000-0000-0000-0000000000d5';  -- e5 之后租户 B 用自己的 id 建（对照）
    v_dev_a4      uuid := '18000000-0000-0000-0000-0000000000d6';  -- e7 跨租户门店引用（应被拒）
BEGIN
    -- ==================================================================
    -- (a0) 🛑🛑 能力守卫一：执行本自证的角色**不得**绕过 RLS
    --
    --   【本段于 2026-09-27 由 119 反向验证的实测抓出后，从 V19 逐字回补】
    --   实测（119 的 I(a0) 用例）：用 `postgres`（rolsuper=t / rolbypassrls=t）
    --   跑本文件的 (e) 段，自证以 (e5) 报错，消息逐字是
    --       "租户 B 用【租户 A 已占用的 device_id】建档没有抛错（返回值 = ALREADY_EXISTS）"
    --   —— 读起来像 **register_device 坏了**。真因恰好相反：函数完全正确，
    --   是证据的效力前提不成立。BYPASSRLS 会绕过行级安全 ⇒
    --   `set_config('app.tenant_id', 租户B)` 之后那条
    --       SELECT count(*) FROM device WHERE device_id = p_device_id
    --   **仍然看得见租户 A 的那一行** ⇒ v_mine = 1 ⇒ 判定链落到"是我的"分支
    --   ⇒ 返回 ALREADY_EXISTS ⇒ (e5) 报红。
    --
    --   🛑 这是本仓反复出现的同一族形态：「判据的适用范围 = 它的锚点范围」。
    --      本自证里依赖 RLS 的判据共 4 条（(e3) 不落库、(e5) 零行、
    --      (e5) 对照、(e8) 可见性），它们的有效性**完全建立**在
    --      "当前角色受 RLS 约束"之上。BYPASSRLS 会让这 4 条**静默失效**：
    --        · (e5) 会以"竟然返回 ALREADY_EXISTS"的面目报红（归因错误）；
    --        · (e3)/(e8) 同理以归因错误的面目报红或假绿。
    --      而若有人为了"让自证通过"去改函数，会改坏一个**本来正确**的实现。
    --   ⇒ 故在此显式断言，让红指向真原因（角色不对），而不是指向函数。
    --
    --   🛑 为什么必须用 `rolbypassrls` 而不是 `current_setting('is_superuser')`：
    --      两者不等价 —— 一个 **非超级用户** 也可以被授予 BYPASSRLS。
    --    🛑 顺带说明为什么本仓的应用角色是对的：
    --      实测 diaoyuanyun 的 super=false / bypassrls=false，
    --      即生产路径上 RLS 是真的在起作用的 —— 本守卫只是要求
    --      "跑迁移/自证的角色"与"跑应用的角色"在这一点上一致。
    -- ==================================================================
    SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;

    IF v_bypass IS NULL THEN
        RAISE EXCEPTION
            'V18 自证失败(a0): 查不到当前角色 % 的 rolbypassrls 属性（pg_roles 无此角色？）', current_user;
    END IF;
    IF v_bypass THEN
        RAISE EXCEPTION
            'V18 自证失败(a0): 当前角色 % 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 4 条判据全部失去效力】，'
            '继续执行只会产出一份"看起来跑了、实际什么都没验"的证据。'
            '🛑 具体失效路径（本机已实测）：BYPASSRLS 会绕过行级安全 ⇒ '
            '以租户 B 的上下文执行 `SELECT count(*) FROM device WHERE device_id = <租户A的设备>` '
            '仍会看到那一行 ⇒ register_device 的判定链落到"是我的"分支 ⇒ 返回 ALREADY_EXISTS ⇒ '
            '自证 (e5) 以"跨租户撞号没有抛错"的面目报红。'
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
    --   ⇒ 在本自证写入任何行之前，先用已知答案的探针把 RLS 的实际行为量出来：
    --     `SELECT 1 FROM pg_policies WHERE tablename='device'` 必须恰有 1 条策略，
    --     且 policydef 里的 USING 与 WITH CHECK 都引用 app.tenant_id。
    --   🛑 为什么是"量行为"而不是"断言策略存在"：
    --      存在性断言挡不住"策略被改成了 USING (true)"这种改动 —— 而那会让
    --      (e8) 静默假绿（所有租户互相可见）。故必须断言策略的【定义内容】。
    --   🛑 这里刻意断言**两条**: USING 与 WITH CHECK 都必须引用 app.tenant_id。
    --      只查其中一条会漏掉"读隔离在、写隔离没了"（或反之）——
    --      而写隔离缺失会让 (f2) 清场后的残留检查静默通过。
    -- ==================================================================
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'device') <> 1 THEN
        RAISE EXCEPTION
            'V18 自证失败(a1): device 表上的 RLS 策略数 ≠ 1 个 —— '
            '本自证的 4 条 RLS 判据（(e3) 不落库 / (e5) 零行 / (e5) 对照 / (e8) 可见性）'
            '全部建立在"策略按 app.tenant_id 隔离"之上。'
            '🛑 这种情形下角色属性正常（(a0) 会通过），但判据同样静默失效 ⇒ '
            '必须在写入任何探针数据之前把它变成显式红。';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public' AND tablename = 'device'
           AND qual        LIKE '%app.tenant_id%'
           AND with_check  LIKE '%app.tenant_id%'
    ) THEN
        RAISE EXCEPTION
            'V18 自证失败(a1): device 的策略虽然存在，但其 USING / WITH CHECK 未同时引用 app.tenant_id。'
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
    FROM (VALUES ('register_device'), ('retire_device')) AS x(f)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = x.f);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V18 自证失败(a): 以下函数未创建成功 -> %', v_missing;
    END IF;

    -- ------------------------------------------------------------------
    -- (b)(c) 函数体断言（🛑 只读 prosrc = 函数体原文，不读整个文件）
    --       读整个文件会把本注释块里的示例文本当成"代码里存在"⇒ 假绿。
    --       本文件的注释里【逐字】写着 set_config / assert_tenant_context /
    --       INSERT INTO device / ON CONFLICT / 另一租户 ——
    --       这正是必须只读 prosrc 的原因。
    -- ------------------------------------------------------------------
    SELECT p.prosrc INTO v_register_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'register_device';

    SELECT p.prosrc INTO v_retire_body
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'retire_device';

    IF v_register_body IS NULL THEN
        RAISE EXCEPTION 'V18 自证失败(b): 读不到 register_device 的函数体';
    END IF;
    IF v_retire_body IS NULL THEN
        RAISE EXCEPTION 'V18 自证失败(c): 读不到 retire_device 的函数体';
    END IF;

    -- (b1)(b2)(b3) 逐函数检查三件套（建立上下文 / 自证 / 一致性守卫）
    --   🛑 逐行剥 -- 行注释后再匹配：PG 会把注释原样存进 prosrc，
    --      不剥的话"注释里提到"与"代码里调用"无法区分（V15 / V17 已踩过这条）。
    FOREACH v_mode IN ARRAY ARRAY['register_device', 'retire_device'] LOOP
        EXECUTE format(
            'SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace '
            'WHERE n.nspname = ''public'' AND p.proname = %L', v_mode) INTO v_one_body;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'set_config\s*\(\s*''app\.tenant_id'''
               AND regexp_replace(ln, '--.*$', '') ~ ',\s*true\s*\)'
        ) THEN
            RAISE EXCEPTION
                'V18 自证失败(b1): 函数 % 的函数体里【代码态】没有以 is_local := true 调用 '
                'set_config(app.tenant_id, ...)。这是本迁移最核心的不变量 —— '
                'device 是 FORCE RLS + fail-closed，缺上下文时任何 INSERT 都会被 WITH CHECK 拒绝；'
                '而 is_local := false 会让上下文跨请求泄漏给连接池里的下一个请求。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'assert_tenant_context\s*\('
        ) THEN
            RAISE EXCEPTION
                'V18 自证失败(b2): 函数 % 没有调用 assert_tenant_context()。'
                '缺少它，"设了上下文"就退化成"我以为设好了"—— 本仓的判据一贯是'
                '"设置之后必须能断言它生效"，V15 / V17 的原语也是这么写的。', v_mode;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM regexp_split_to_table(v_one_body, E'\n') AS t(ln)
             WHERE regexp_replace(ln, '--.*$', '') ~ 'current_setting\s*\(\s*''app\.tenant_id'''
        ) THEN
            RAISE EXCEPTION
                'V18 自证失败(b3): 函数 % 没有读 current_setting(app.tenant_id) 做一致性守卫。'
                '缺了它，调用方在一个"已设成租户 B"的事务里调用本函数传租户 A 时，'
                '上下文会被【静默改写成 A】—— 后面的写全落在 A 名下，而调用方以为是 B。'
                '见文件头「核心机制一」与 register_device 的 (2)。', v_mode;
        END IF;
    END LOOP;

    -- (b4) register_device 的幂等写入形态
    --   🛑🛑 用 `\M` 而不是 `\b`：PostgreSQL 的 ARE 正则里 `\b` 是**退格字符**，
    --      不是 PCRE 的"词边界"。V17 已被这条坑过（正样本不匹配 ⇒ 一条自证的**假红**；
    --      更危险的是它的孪生形态：`DELETE FROM device\b` 变成永假 ⇒ 守卫静默假绿）。
    --      ⇒ 凡"词边界"意图，本仓一律用 `\M`（词尾）。
    IF v_register_body !~ 'INSERT\s+INTO\s+device\M' THEN
        RAISE EXCEPTION
            'V18 自证失败(b4): register_device 的函数体里没有 INSERT INTO device —— '
            '本迁移存在的全部理由就是让 device 有写入方；没有这一句，'
            'ProvisioningBoundaryGateTest 第②例会在下一个构建立刻报红（账本在骗人）。'
            '🛑 注意 `\M` 的用意：`INSERT INTO device_dispatch` 也含 "device"，'
            '不加词尾边界会让"改成往 device_dispatch 写"这种偷换满足本断言。';
    END IF;
    IF v_register_body !~ 'ON\s+CONFLICT' THEN
        RAISE EXCEPTION
            'V18 自证失败(b5): register_device 的 INSERT 没有 ON CONFLICT —— '
            'PL/pgSQL 的 INSERT 没有 ELSE 分支：不加 ON CONFLICT 就只能靠 BEGIN/EXCEPTION '
            '捕获 23505，而"捕获到 conflict 就知道是重复建档"这条推理是错的'
            '（23505 也可能来自其它唯一约束）。故必须用库层的原子 upsert。';
    END IF;
    -- 🛑 推断目标必须逐字是【主键列】device_id：本迁移的跨租户判定完全建立在
    --    "推断目标是那个不含 tenant_id 的全局主键"之上。若有人改成
    --    `ON CONFLICT (tenant_id, device_id)`，冲突就不会再跨租户发生，
    --    而 (b7) 的判定分支会变成死代码却**不自知** —— 故在此钉住。
    IF v_register_body !~ 'ON\s+CONFLICT\s*\(\s*device_id\s*\)' THEN
        RAISE EXCEPTION
            'V18 自证失败(b6): register_device 的 ON CONFLICT 推断目标不是 `(device_id)`。'
            '🛑 这不是风格问题：本迁移的【核心机制二】完全建立在"推断目标是全局主键"'
            '这个事实上 —— 正因为它不含 tenant_id，"另一租户占用同一 device_id"才可能发生；'
            '也正因为会发生，函数才必须补上"冲突了但我看不见 ⇒ 是别人的"这条判定。'
            '若改成 (tenant_id, device_id)，该冲突不可能发生，(b7) 的分支成为死代码，'
            '而自证 (e5) 会以【归因错误】的方式失败（报"竟然成功了"）。';
    END IF;

    -- (b7) 🛑🛑 跨租户撞号的判定分支必须存在 —— 本迁移相对 V17 形态的唯一实质改动
    --   【判据必须带锚点：从那条 RAISE 起锚】
    --   🛑 为什么不写成"函数体里出现过'另一租户'四个字"：
    --      那是 V17 的 (b7)/(c7) 在 2026-09-27 被抓过的形态 ——
    --      "文内任意位置出现过这句话"无法证明"这句话出现在该出现的那条语句里"。
    --      本文件的注释里（prosrc 中会保留）逐字写着"另一租户"，
    --      故不带锚点的判据会被**注释**满足 ⇒ 假绿。
    --      收紧方式：要求 `RAISE EXCEPTION` 与"另一租户"同处一条语句片段
    --      （`[^;]*` 不跨分号 —— RAISE 的参数之间不会出现分号）。
    IF v_register_body !~ 'RAISE\s+EXCEPTION[^;]*另一租户' THEN
        RAISE EXCEPTION
            'V18 自证失败(b7): register_device 里没有"跨租户撞号"的 RAISE 分支。'
            '🛑 这是本迁移的核心，且它是【实测抓出来的】（见文件头「核心机制二」，'
            '证据 = _work/v18_onconflict_crosstenant_probe.sql）：'
            '`ON CONFLICT (device_id) DO NOTHING` 在 device_id 被【别的租户】占用时'
            '会【静默 DO NOTHING 且 ROW_COUNT=0】，而本租户里那一行**看不见**。'
            '若只按 ROW_COUNT 判定，函数会对一个手里一行都没有的租户返回 ALREADY_EXISTS —— '
            '调用方据此认为设备已就绪并继续下发，而随后的 device_dispatch 仍以 23503 失败。'
            '即："返回值在撒谎"。故必须有这条分支，且它必须是 RAISE（不是返回值）。'
            '🛑 判据从 `RAISE EXCEPTION` 起锚：本函数体注释里逐字含"另一租户"，'
            '不带锚点的写法会被注释满足。';
    END IF;
    -- (b8) 判定必须真的区分"是我的"与"不是我的"：必须在本租户上下文里读一次 device
    --   🛑 与 (b7) 配合才完整：(b7) 保证有 RAISE 分支，(b8) 保证它前面的判定读真的存在。
    --      只有 RAISE 而没有那次读，RAISE 就成了无条件抛错（会把正常幂等也打死）。
    IF v_register_body !~ 'FROM\s+device\M[^;]*device_id\s*=\s*p_device_id' THEN
        RAISE EXCEPTION
            'V18 自证失败(b8): register_device 里没有"在已自证的本租户上下文内按 device_id 读一次"'
            '的语句（形如 `SELECT count(*) ... FROM device WHERE device_id = p_device_id`）。'
            '🛑 缺了它，(b7) 的 RAISE 会变成**无条件抛错** —— 那会把"本租户的幂等重放"'
            '也一起打死（本该返回 ALREADY_EXISTS 的调用变成异常）。'
            '两段判定必须成对：先问"它在谁名下"，才能区分"我的 ⇒ 幂等"与"别人的 ⇒ 冲突"。';
    END IF;
    -- (b9) 建档路径的租户维度必须在 SQL 里可见（INSERT 列清单里含 tenant_id）
    --   🛑 从 `INSERT INTO device` 起锚、以 `;` 为界，理由同 (b7)：
    --      本函数体的注释里也提到 tenant_id，不带锚点会被注释满足。
    IF v_register_body !~ 'INSERT\s+INTO\s+device\M[^;]*tenant_id' THEN
        RAISE EXCEPTION
            'V18 自证失败(b9): register_device 的【INSERT 语句自身】列清单里没有 tenant_id。'
            'device 是 FORCE RLS（策略 = tenant_id 等于上下文），INSERT 不带 tenant_id '
            '会直接违反 NOT NULL；但更值得防的是"带了却带了别处来的值"——'
            '本断言保证租户维度在写入语句里显式可见，而不是从某个局部变量悄悄带进来。';
    END IF;

    -- (c1) retire_device 必须是【状态迁移】而不是 DELETE
    --   🛑 用 `\M` 而非不加边界：`UPDATE\s+device` 会匹配 `UPDATE device_dispatch`
    --      ⇒ 若有人把归档的实现改成去更新别的表，这条断言会假绿。
    IF v_retire_body !~ 'UPDATE\s+device\M' THEN
        RAISE EXCEPTION
            'V18 自证失败(c1): retire_device 的函数体里没有 UPDATE device —— '
            '归档必须是状态迁移。两条理由（见函数注释）：'
            '① device_dispatch 通过 device_dispatch_device_id_fkey 引用本表（NOT NULL），'
            '   删掉一行会让历史下发记录失去可追溯对象，而 D6 的语义恰是「下行、可追责」；'
            '② retired 与 active 的区分是"还在不在服役"，是**状态**而不是"存在与否"。';
    END IF;
    IF v_retire_body ~ 'DELETE\s+FROM\s+device\M' THEN
        RAISE EXCEPTION
            'V18 自证失败(c2): retire_device 的函数体里出现了 DELETE FROM device —— '
            '归档是状态迁移，永远不是删除（见 (c1) 的理由）。'
            '删除会让可追责的下发台账失去主体。';
    END IF;
    IF v_retire_body !~ 'SET\s+status\s*=' THEN
        RAISE EXCEPTION 'V18 自证失败(c3): retire_device 没有 SET status = ...（归档的核心动作）';
    END IF;
    -- (c4) 并发正确性：状态迁移的谓词里必须有 `status <> 'retired'`
    IF v_retire_body !~ 'status\s*<>\s*''retired''' THEN
        RAISE EXCEPTION
            'V18 自证失败(c4): retire_device 的 UPDATE 谓词里没有 `status <> ''retired''`。'
            '缺了它，两个并发归档都会通过前置读，两次 UPDATE 都"成功"，'
            '第二次会把 updated_at 覆盖成更晚的时刻 —— 一次静默的数据回退。'
            '这与 V17 的 (c6) 是同一类教训，也与 E2 在 2026-09-26 修掉的'
            '"看起来不覆盖、实际没覆盖"同族：保证必须由【库层条件】给出，'
            '不能依赖一次可能过期的读。';
    END IF;
    -- (c5) 归档路径的租户维度同样必须可见，且**必须从 UPDATE 起锚**
    --   🛑 这正是 V17 的 (b7)/(c7) 在 2026-09-27 被 118 抓过的形态：
    --      retire_device 的步骤 (4) 有一条
    --          SELECT status ... WHERE device_id = p_device_id AND tenant_id = p_tenant_id;
    --      不带锚点的判据（`AND\s+tenant_id\s*=\s*p_tenant_id`）会被它满足 ⇒
    --      "状态迁移 UPDATE 的租户维度被删掉"**完全不会被抓住**（假绿，已实测）。
    --      故本断言从 `UPDATE device` 起锚、以 `;` 为界（`[^;]*` 不跨语句）。
    IF v_retire_body !~ 'UPDATE\s+device\M[^;]*tenant_id\s*=\s*p_tenant_id' THEN
        RAISE EXCEPTION
            'V18 自证失败(c5): retire_device 的【状态迁移 UPDATE 语句自身】里缺少 `tenant_id = p_tenant_id`。'
            '只按 device_id 写时 RLS 仍会兜住（不会真的跨租户），'
            '但那时"这条语句跨不跨租户"就只能靠推理而不能靠阅读 —— '
            '本仓把可读性当成安全属性的一部分，故要求租户维度在谓词里显式可见。'
            '🛑 本断言【从 UPDATE device 起锚】：不带锚点的写法会被步骤 (4) 的 SELECT '
            '里那句同款子句满足（V17 已实测过这条假绿）。';
    END IF;

    -- ------------------------------------------------------------------
    -- (d) 迁移登记 + 权限
    -- ------------------------------------------------------------------
    SELECT count(*) INTO v_reg FROM schema_migration WHERE version = 'V18';
    IF v_reg <> 1 THEN
        RAISE EXCEPTION 'V18 自证失败(d): schema_migration 中 V18 登记行数 = %（期望 1）', v_reg;
    END IF;

    -- (d2) 用 has_function_privilege 而非读 proacl：前者把"角色继承 / PUBLIC 授权 /
    --      owner 隐含权限"等所有生效路径都算进去（V15 / V17 同款理由）。
    SELECT string_agg(x.sig, ', ' ORDER BY x.sig) INTO v_nopriv
    FROM (VALUES
              ('register_device(uuid,uuid,uuid,text,text)'),
              ('retire_device(uuid,uuid)')) AS x(sig)
    WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');
    IF v_nopriv IS NOT NULL THEN
        RAISE EXCEPTION
            'V18 自证失败(d2): 当前角色 % 对以下函数没有 EXECUTE 权限 -> %。'
            '典型成因：环境做过 REVOKE ALL ON FUNCTION ... FROM PUBLIC 加固，'
            '而第 2 节的授权段未覆盖到 —— 那会表现为"建档时报 permission denied"，'
            '属运行期才发现的错误，故在迁移期断言。', current_user, v_nopriv;
    END IF;

    -- 🛑🛑 (d3) 上一条 (d2) 在【按本仓脚本建的库】上**恒真、判别力为零**。
    --   （V17 已被 118 的 I(d2) 抓过：注入 `REVOKE ... FROM current_user` 之后
    --     自证仍然打印"自证通过"，即该断言对"权限被撤掉"毫无反应。）
    --   成因：迁移是用应用角色本身跑的 ⇒ 两个函数的 **owner 就是 current_user**
    --        ⇒ owner 对自有对象的 EXECUTE 是**隐含**的，has_function_privilege 永远为 t，
    --        且 REVOKE 也撤不掉它（要撤只能改 owner）。
    --   实测 proacl（本机 PG 17.11，diaoyuanyun_dev）：
    --        proacl = `=X/diaoyuanyun | diaoyuanyun=X/diaoyuanyun`
    --        其中 `=X/diaoyuanyun` 是 **PUBLIC 的 EXECUTE（PG 对函数的默认授权）**，
    --        `diaoyuanyun=X/...` 是第 2 节 GRANT 段显式授的那一条。
    --   ⇒ 把"授权段真的执行了"变成可机械判定的事实：断言 proacl 里存在【显式 ACL 项】。
    --      `proacl IS NULL` 意味着"从未 GRANT 也从未 REVOKE"——那正是"授权段被摘掉"的形态。
    --      🛑 它同时覆盖"PUBLIC 被 REVOKE 加固"的环境：那种情况下 proacl 仍非 NULL，
    --         而第 2 节的显式项仍在 ⇒ 本断言依然能区分"授权段跑了"与"没跑"。
    SELECT string_agg(x.f, ', ' ORDER BY x.f) INTO v_noacl
    FROM (VALUES ('register_device'), ('retire_device')) AS x(f)
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
            'V18 自证失败(d3): 以下函数的 proacl 为 NULL —— 即【从未被 GRANT/REVOKE 过】：%。'
            '含义：第 2 节的授权段没有真正执行到这两个函数上。'
            '🛑 为什么 (d2) 单独不够：本迁移由应用角色自己执行 ⇒ 函数 owner = 调用者，'
            '而 owner 对自有函数的 EXECUTE 是隐含的 ⇒ (d2) 在该情形下恒为真。'
            '（V17 的 118 反向验证 I(d2) 已实测：REVOKE ... FROM current_user 之后 (d2) 仍报通过。）'
            '本断言改看【显式 ACL 项是否存在】，故对"授权段被摘掉 / 未生效"有反应。', v_noacl;
    END IF;

    -- ==================================================================
    -- (e) 行为验证 —— 全部在子事务里，块末整体撤销
    -- ==================================================================
    BEGIN
        -- 准备：两个租户（tenant 无 RLS，可直接写）
        INSERT INTO tenant (id, name, status)
        VALUES (v_ta, 'V18 设备建档探针 A', 'active'),
               (v_tb, 'V18 设备建档探针 B', 'active')
        ON CONFLICT (id) DO NOTHING;

        -- 两个租户各建一个门店（store 是 FORCE RLS ⇒ 必须各自在自己的上下文里写）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        INSERT INTO store (store_id, tenant_id, name, franchise_type)
        VALUES (v_store_a1, v_ta, 'V18 探针门店 A', '直营');

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        INSERT INTO store (store_id, tenant_id, name, franchise_type)
        VALUES (v_store_b1, v_tb, 'V18 探针门店 B', '加盟');

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e1 建档 → CREATED，且行确实落库
        -- ---------------------------------------------------------------
        v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '杠2', 'TPL-G2-V3');

        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V18 自证失败(e1): 首次建档返回 %（期望 CREATED）。'
                '若返回 ALREADY_EXISTS，说明该 device_id 在库里已存在'
                '（探针 id 冲突 / 上次运行的残留）。', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM device
                        WHERE device_id = v_dev_a1 AND tenant_id = v_ta
                          AND store_id = v_store_a1 AND model = '杠2'
                          AND status = 'active') THEN
            RAISE EXCEPTION
                'V18 自证失败(e1): register_device 返回 CREATED，但库里查不到'
                '与之匹配的行（期望 tenant_id/store_id/model/status 全部对上）。'
                '🛑 四列同时断言是刻意的：一个"插了行但 store_id 用错"的实现'
                '会让下游参数下发到错误的门店 —— 那是本迁移最该防的后果。';
        END IF;

        -- ---------------------------------------------------------------
        -- e2 幂等：重放同一 device_id → ALREADY_EXISTS，且不得产生第二行
        --    🛑 这一条排除了"e1 成功只是因为函数没做任何检查"这种解释。
        -- ---------------------------------------------------------------
        v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '杠2', 'TPL-G2-V3');

        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V18 自证失败(e2): 重放同一 device_id 返回 %（期望 ALREADY_EXISTS）。'
                '返回 CREATED 说明幂等失效（库层主键没有生效 —— '
                '那意味着"一台设备可以被建两次"，而 device_id 是主键，不该发生）。', v_mode;
        END IF;
        IF (SELECT count(*) FROM device WHERE device_id = v_dev_a1) <> 1 THEN
            RAISE EXCEPTION
                'V18 自证失败(e2): 重放之后同一 device_id 的行数 ≠ 1。'
                '返回值说"已存在"，但行数不止一行 —— 返回值与行为不一致。';
        END IF;

        -- ---------------------------------------------------------------
        -- e3 门店不在本租户内 → RAISE（不是返回值），且必须证明【没有落库】
        --    🛑 用内层 BEGIN/EXCEPTION 包住那条会抛的语句：否则一次预期的 RAISE
        --       会把外层子事务标记为已回滚，后续断言全部以「当前事务被终止」失败
        --       —— 一条归因完全错误的红。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_msg := NULL;
        BEGIN
            v_mode := register_device(v_ta, v_dev_a3, v_store_b1, '杠2', NULL);
            -- 走到这里说明没抛
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V18 自证失败(e3): 用【租户 B 的门店】在租户 A 里建档没有抛错（返回值 %）。'
                '门店必须在本租户内存在（register_device 的 (4)）。'
                '🛑 若它返回 ALREADY_EXISTS，那更坏：说明 (4) 的存在性检查被摘掉了，'
                '而这里本该在写之前就失败。', v_mode;
        END IF;
        IF EXISTS (SELECT 1 FROM device WHERE device_id = v_dev_a3) THEN
            RAISE EXCEPTION
                'V18 自证失败(e3): 被拒绝的那次建档竟然落库了（device_id = %）—— '
                '说明 (4) 的检查发生在写入【之后】，或者根本没有生效。', v_dev_a3;
        END IF;

        -- ---------------------------------------------------------------
        -- e4 归档 → RETIRED；再归档一次 → ALREADY_RETIRED，且 updated_at 不得被推后
        -- ---------------------------------------------------------------
        SELECT updated_at INTO v_updated_first FROM device WHERE device_id = v_dev_a1;

        v_mode := retire_device(v_ta, v_dev_a1);

        IF v_mode <> 'RETIRED' THEN
            RAISE EXCEPTION 'V18 自证失败(e4): 首次归档返回 %（期望 RETIRED）', v_mode;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM device WHERE device_id = v_dev_a1 AND status = 'retired') THEN
            RAISE EXCEPTION
                'V18 自证失败(e4): 归档后 status 不是 retired（当前 = %）',
                (SELECT status FROM device WHERE device_id = v_dev_a1);
        END IF;
        IF (SELECT updated_at FROM device WHERE device_id = v_dev_a1) IS NULL THEN
            RAISE EXCEPTION
                'V18 自证失败(e4): 归档后 updated_at 仍为 NULL —— '
                '归档没有留下时间痕迹。"什么时候退役的"永久不可答。';
        END IF;

        v_mode := retire_device(v_ta, v_dev_a1);

        IF v_mode <> 'ALREADY_RETIRED' THEN
            RAISE EXCEPTION 'V18 自证失败(e4): 重复归档返回 %（期望 ALREADY_RETIRED，幂等）', v_mode;
        END IF;
        -- 🛑 这一条是 (c4) 那处谓词的行为验证：第二次调用不得把 updated_at 推后
        IF (SELECT updated_at FROM device WHERE device_id = v_dev_a1) <> v_updated_first THEN
            RAISE EXCEPTION
                'V18 自证失败(e4): 第二次归档把 updated_at 改掉了（% → %）。'
                '这是一次静默的数据回退：一次"什么都不该改"的幂等重放，改动了归档时刻。'
                '成因通常是 UPDATE 的谓词里少了 `status <> ''retired''`（见自证 c4）。',
                v_updated_first, (SELECT updated_at FROM device WHERE device_id = v_dev_a1);
        END IF;

        -- ---------------------------------------------------------------
        -- e5 🛑🛑 跨租户撞号 —— 本迁移的核心用例
        --    租户 A 先占用 v_dev_shared；然后切到租户 B 用**同一 device_id** 建档。
        --    必须 RAISE（不是 ALREADY_EXISTS），且消息里必须含"另一租户"。
        --    🛑 为什么必须断言"消息含另一租户"而不只是"抛了异常"：
        --       一个"任何输入都抛错"的实现同样能让"抛了"成立；
        --       断言消息内容证明抛的是**这条**分支，不是别的。
        --       同时这也把"返回值在撒谎"这种修法钉死 —— 它不允许返回 ALREADY_EXISTS。
        -- ---------------------------------------------------------------
        v_mode := register_device(v_ta, v_dev_shared, v_store_a1, '杠2', NULL);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V18 自证失败(e5): 准备阶段（租户 A 先占用 %）返回 %（期望 CREATED）',
                v_dev_shared, v_mode;
        END IF;

        v_rejected := false;
        v_msg := NULL;
        v_state := NULL;
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        BEGIN
            v_mode := register_device(v_tb, v_dev_shared, v_store_b1, '现有', NULL);
            -- 走到这里说明没抛
        EXCEPTION
            WHEN others THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V18 自证失败(e5): 租户 B 用【租户 A 已占用的 device_id】建档没有抛错'
                '（返回值 = %）。🛑 这是本迁移的核心机制二的封堵点：'
                '`ON CONFLICT (device_id) DO NOTHING` 在这里会【静默 DO NOTHING 且 ROW_COUNT=0】，'
                '若函数只按 ROW_COUNT 判定，就会对租户 B 返回 ALREADY_EXISTS —— '
                '而租户 B 里那一行**看不见**（RLS），即"返回值在撒谎"：'
                '调用方以为设备已就绪并继续下发，而随后的 device_dispatch 仍以 23503 失败。'
                '必须在"冲突了但我看不见"时报错，而不是说"你已经有了"。', v_mode;
        END IF;
        IF v_msg IS NULL OR v_msg !~ '另一租户' THEN
            RAISE EXCEPTION
                'V18 自证失败(e5): 跨租户撞号虽然抛了错，但消息里没有"另一租户"（实际消息 = %）。'
                '🛑 断言消息内容是为了证明抛的是**这条**分支：'
                '一个"任何输入都抛错"的实现同样能让"抛了异常"成立。', coalesce(v_msg, '<NULL>');
        END IF;
        -- 租户 B 里必须仍然一行关于这个 device_id 的行都没有
        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM device WHERE device_id = v_dev_shared) THEN
            RAISE EXCEPTION
                'V18 自证失败(e5): 被拒绝的那次跨租户建档竟然在租户 B 里落库了（device_id = %）',
                v_dev_shared;
        END IF;

        -- e5 的对照：租户 B 用自己的 device_id 建档必须成功
        --   🛑 没有这一条，一个"只要 device_id 在别处存在就抛错"的实现也能过 e5。
        v_mode := register_device(v_tb, v_dev_b_new, v_store_b1, '现有', NULL);
        IF v_mode <> 'CREATED' THEN
            RAISE EXCEPTION
                'V18 自证失败(e5-对照): 租户 B 用自己的 device_id 建档返回 %（期望 CREATED）—— '
                '跨租户守卫把正常路径也打死了。', v_mode;
        END IF;

        PERFORM set_config('app.tenant_id', v_ta::text, true);

        -- ---------------------------------------------------------------
        -- e6 对照：同租户裸 INSERT（不同 device_id）必须【成功】
        --    🛑 一个"拒绝一切"的实现同样能让 e3/e5 通过，故必须有本条对照。
        -- ---------------------------------------------------------------
        BEGIN
            INSERT INTO device (device_id, tenant_id, store_id, model, status)
            VALUES (v_dev_a2, v_ta, v_store_a1, '现有', 'active');
            v_accepted := true;
        EXCEPTION
            WHEN others THEN
                v_accepted := false;
        END;

        IF NOT v_accepted THEN
            RAISE EXCEPTION
                'V18 自证失败(e6): 同租户裸 INSERT 被误拒 —— 正常路径被挡住了。'
                '最可能的成因：RLS 策略或某条约束在函数之外也把正常写入拒了，'
                '那会让"建档通路"看起来可用而实际上只有函数能写。';
        END IF;

        -- ---------------------------------------------------------------
        -- e7 🛑 跨租户门店引用：租户 A 里把 device.store_id 指向租户 B 的门店
        --    必须被【库层复合外键】拒，且理由必须是 23503 而不是 42501。
        --    🛑 为什么必须绕过函数：函数的 (4) 也会拒绝它 —— 于是"被拒绝了"这件事
        --       无法区分"函数层拦住了"与"库层拦住了"。而 V16 刚把 device_store_id_fkey
        --       换成 (tenant_id, store_id) 复合外键，这里要证的正是【库层那道】仍有效
        --       （防御纵深：函数可以被人绕过，外键不能）。
        --    🛑 为什么区分 23503 与 42501：两者都"拒绝"，但含义完全不同 ——
        --       23503 = 复合外键生效（V16 的成果）；
        --       42501 = RLS 的 WITH CHECK 拒绝（说明上下文不对，而不是外键在起作用）。
        --       若只断言"抛了异常"，一次上下文写错的实现会让本段假通过。
        -- ---------------------------------------------------------------
        v_rejected := false;
        v_state := NULL;
        v_msg := NULL;
        BEGIN
            INSERT INTO device (device_id, tenant_id, store_id, model, status)
            VALUES (v_dev_a4, v_ta, v_store_b1, '杠2', 'active');
            v_rejected := false;
        EXCEPTION
            WHEN foreign_key_violation THEN
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
                v_rejected := true;
            WHEN insufficient_privilege THEN
                v_rejected := false;   -- 42501 不算"被外键拒绝"
                GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        END;

        IF NOT v_rejected THEN
            RAISE EXCEPTION
                'V18 自证失败(e7): 跨租户门店引用【未被外键拒绝】——'
                '在租户 A 的上下文里，device 行成功把 store_id 指向了租户 B 的门店。'
                '这说明 V16 的复合外键 device_store_id_fkey (tenant_id, store_id) '
                '→ store (tenant_id, store_id) 没有生效。'
                '🛑 意义：设备归属的跨租户完整性不能只靠 register_device 的入参检查 —— '
                '函数可以被人绕过（直接写 SQL），外键不能。'
                '（若 SQLSTATE = % 而非 23503，那是 RLS 拒绝，说明上下文不对 —— '
                '它同样"拒绝"，但证明的是完全不同的事。）', coalesce(v_state, '<无>');
        END IF;

        -- ---------------------------------------------------------------
        -- e8 归档行仍占据主键 ⇒ 重放建档必须返回 ALREADY_EXISTS
        --    🛑 这一条把"归档是 UPDATE 而非 DELETE"的**可观测后果**钉住：
        --       若有人把 retire 改成 DELETE，这里会返回 CREATED，本断言立刻红。
        --       同时它也是"主键复用需先物理删除归档行"这条口径的行为证明。
        -- ---------------------------------------------------------------
        v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '杠2', NULL);

        IF v_mode <> 'ALREADY_EXISTS' THEN
            RAISE EXCEPTION
                'V18 自证失败(e8): 对【已归档】的 device_id 重放建档返回 %（期望 ALREADY_EXISTS）。'
                '若返回 CREATED，说明归档把行删掉了 —— 那就同时毁掉了两件事：'
                '① device_dispatch 的历史可追溯性（NOT NULL 外键的主体没了）；'
                '② "归档于何时"这个事实。归档必须是状态迁移。', v_mode;
        END IF;

        -- ---------------------------------------------------------------
        -- e9 清场前的正向计数：证明确实有行写进去过
        --    （否则下面的 DELETE 是空操作，"清场成功"与"什么都没发生"同形）
        -- ---------------------------------------------------------------
        SELECT count(*) INTO v_cnt FROM device WHERE tenant_id = v_ta;
        IF v_cnt < 2 THEN
            RAISE EXCEPTION
                'V18 自证失败(e9): 租户 A 的 device 行数 = %（期望 ≥2：e1/e5准备/e6 写入的行）。'
                '这条断言的作用是让下面的"清场后为 0"有意义 —— 若本来就没写进去，'
                '那么"清场成功"与"什么都没发生"给出同一个 0。', v_cnt;
        END IF;

        -- ==============================================================
        -- (f) 探针清场 + 清场自证
        --   🛑 必须【逐个租户在自己的上下文里】删 —— V16 / V17 的自证块都踩过这个坑：
        --       FORCE RLS 下 DELETE 在错误的上下文里会【静默删 0 行且不报错】，
        --       于是残留下来，在下一次运行本迁移时以"INSERT 撞主键"的面目出现，
        --       而那时没人记得它来自上一次探针。
        --   🛑 删除顺序必须与外键依赖倒序：
        --       device 引用 store / tenant ⇒ 先删 device，再删 store，再删 tenant。
        --       写反了的表现是"在 store 上的删除违反了 device 上的外键约束"。
        --   🛑 本迁移的探针没有写 device_dispatch（e1 只建档），故无需先清它 ——
        --       但若将来有人往本块里加一条下发写入，必须把 device_dispatch 排在最前。
        -- ==============================================================
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        DELETE FROM device WHERE tenant_id = v_ta;
        DELETE FROM store  WHERE store_id  = v_store_a1;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        DELETE FROM device WHERE tenant_id = v_tb;
        DELETE FROM store  WHERE store_id  = v_store_b1;

        -- tenant 表无 RLS，可直接删
        DELETE FROM tenant WHERE id IN (v_ta, v_tb);

        -- (f2) 清场自证：逐租户设上下文检查（否则"删干净了"与"我看不见"同形）
        PERFORM set_config('app.tenant_id', v_ta::text, true);
        IF EXISTS (SELECT 1 FROM device WHERE tenant_id = v_ta) THEN
            RAISE EXCEPTION
                'V18 自证失败(f2): 探针清场不彻底（租户 A 的 device 仍有残留）。'
                '残留会在下一次运行时表现为"INSERT 撞主键"，而那时没人记得它来自本次探针。';
        END IF;
        IF EXISTS (SELECT 1 FROM store WHERE store_id = v_store_a1) THEN
            RAISE EXCEPTION 'V18 自证失败(f2): 探针清场不彻底（租户 A 的门店 % 仍有残留）', v_store_a1;
        END IF;

        PERFORM set_config('app.tenant_id', v_tb::text, true);
        IF EXISTS (SELECT 1 FROM device WHERE tenant_id = v_tb) THEN
            RAISE EXCEPTION 'V18 自证失败(f2): 探针清场不彻底（租户 B 的 device 仍有残留）';
        END IF;
        IF EXISTS (SELECT 1 FROM store WHERE store_id = v_store_b1) THEN
            RAISE EXCEPTION 'V18 自证失败(f2): 探针清场不彻底（租户 B 的门店 % 仍有残留）', v_store_b1;
        END IF;

        IF EXISTS (SELECT 1 FROM tenant WHERE id IN (v_ta, v_tb)) THEN
            RAISE EXCEPTION 'V18 自证失败(f2): 探针清场不彻底（探针租户仍有残留）';
        END IF;
    END;

    RAISE NOTICE 'V18 自证通过: 函数 2 / 登记 1 / 函数体断言全中 / 建档两态齐备（含跨租户撞号 RAISE）/ 门店不存在被拒且不落库 / 归档幂等且不推后 updated_at / 跨租户门店引用被外键拒(23503)且同租户放行 / 归档行占据主键 / 探针零残留';
END;
$v18_guard$;


-- ============================================================================
-- 第 5 节 · 回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   见文件头【回滚说明】。要点重述：
--     · 本迁移不建表、不写业务行 ⇒ 回滚无数据损失风险；
--     · 🛑 但**不要**用回滚来处理"device 表里已经有设备档"这类情况 ——
--       那些行是数据。它们的存在意味着 D6 现在能工作了，
--       把函数删掉会让 device_dispatch.device_id 的外键重新失去引用来源。
-- ============================================================================
ROLLBACK;
