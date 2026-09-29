-- ===========================================================================
-- V14 迁移: 配置真相源落库（ADR-08 · B-3 收口）
-- ===========================================================================
--
-- 【本迁移解决什么】
--   README「未完成项与 TODO」长期挂着一条收尾令：
--     「配置真相源落库：ConfigServiceImpl 为内存实现；真实以 DB 表为真相源
--       (ADR-08)，并接入 46 条非空配置(#1~#41, #43~#46, #48) + #42 空号 + #47 预留缺席。」
--   本迁移把它落地：把 ADR-08 的三张配置真相源表（config_slot / app_config /
--   app_config_history）建进【应用库】diaoyuanyun_dev，并灌入 46 条声明。
--
--   🛑 收口前后的事实差异（2026-09-26 现场勘察结论，勿照抄旧描述）
--     · 旧描述说"ConfigServiceImpl 为内存实现 ⇒ 真相源缺失"。**只对了一半**：
--       真相源实现（JdbcConfigService + 01/02/03 三份 SQL + 5 个真库 IT）早已完整存在，
--       缺的是【把它落到应用库】—— 这三张表此前只建在 dy-config 的独立门禁库里。
--     · 因此本迁移的产出是【schema + 声明】，不是【Java 实现】。
--       Java 侧读取方（JdbcConfigService，DB 表为唯一真相源）已就绪，本迁移不碰它。
--
-- 【为什么把这些 SQL 内联进本文件，而不是 \i 引用 01/02 两份资产】
--   Flyway 只扫描 classpath:db/migration 下的 V*.sql（见 application.yml），
--   而 psql 的 \i 是客户端元命令、Flyway 不支持；且 \i 需要硬编码绝对路径，
--   在 CI / 任意检出路径下不成立。⇒ 迁移必须是自足的单个文件。
--   内联是本仓既有做法（V5 单文件 65KB 建 24 表）。
--
--   🛑 内联带来的真实代价，显式登记（不装作不存在）：
--     01/02 两份资产仍是【两个消费者的读取源】：
--       ① dy-config 的门禁库：ConfigGateSupport 直接 \i 这两个文件；
--       ② dy-app 的四个 ConfigSeed*ProfileSource：启动期解析 02 的文本取口径；
--       ③ 本迁移：内联【副本】，落到应用库。
--     ①②读源文件、③读副本 ⇒ 二者会漂移，且漂移时不报错，
--     只让"门禁库/口径来源里的配置"与"应用库里的配置"悄悄分叉。
--     防御手段（两道，缺一不可）：
--       a) 本文件头逐字标注内联来源的行号区间（见下）；
--       b) ConfigTruthSourceMigrationSyncTest 在构建期断言
--          「真源文件 ↔ 本迁移内联段」逐字一致 —— 改一处不改另一处即红。
--     （刻意不"另存一份归档副本"：那会造出第三个漂移点。）
--
--     内联来源（本迁移生成时复制，仅一处刻意的、已登记的偏离，见下节）：
--       · 第 1 节 DDL   ← dy-config/src/main/resources/db/config/01_truth_source_ddl.sql
--                          第 47 行 ~ 末行（第 2 节「总部层声明表」起）—— 逐字未改
--       · 第 2 节 声明  ← dy-config/src/main/resources/db/config/02_slots_seed.sql
--                          第 32 行 ~ 末行（正文 `DELETE FROM config_slot;` 起，
--                          含该文件自带的灌入守卫 $seed_guard$）
--                          🛑 唯一偏离：把 02 的 `DELETE FROM config_slot;` + 裸 `INSERT`
--                             改为 `INSERT … ON CONFLICT (config_no) DO UPDATE`。
--                             理由：应用库里 app_config.config_no 外键引用本表，
--                             已实测 `DELETE FROM config_slot` 会被 23503 拦下
--                             ⇒ 原写法在应用库【不幂等】。DO UPDATE 同时达成
--                             02 原注释想要的效果（改了 initial_value 必须生效）。
--
-- 【与 01 的开头「CREATE TABLE IF NOT EXISTS tenant」的关系】
--   01 那段是给"独立门禁库自举"用的（门禁库从空库开始，需要租户宿主表）。
--   应用库的 tenant 表由 V1 基线迁移建成，本迁移【刻意不重复建它】：
--   重复建会连带重建 tenant 的 RLS 策略，把 V1 的裁定覆盖掉
--   （V1 的 tenant 表本身不带策略，见其脚本；但语义上不应有第二处定义）。
--   故第 1 节从 01 的第 2 节开始。
--
-- 【幂等纪律（与 V1~V13 同一口径）】
--     · 建表 / 建索引：IF NOT EXISTS；
--     · CREATE POLICY 无 IF NOT EXISTS ⇒ 前置 DROP POLICY IF EXISTS；
--     · 函数 CREATE OR REPLACE；触发器 DROP TRIGGER IF EXISTS 后再建；
--     · 声明灌入用 `ON CONFLICT (config_no) DO UPDATE`（见上「唯一偏离」小节）。
--       🛑 特别说明：【不】沿用 02 原文件的 `DELETE FROM config_slot` + 裸 INSERT ——
--          在应用库语境下它不幂等（外键 23503，已实测）。
--       🛑 也【不】用 `DO NOTHING`：那会让"改了 02 里的 initial_value"在应用库
--          与门禁库产生不一致（门禁库里 DO NOTHING 语义本就不生效，因为它每次重建库）。
--
-- 【本文件结构】
--   第 1 节  DDL   —— 三张表 + RLS + 取值校验/变更留痕触发器（内联自 01 第 2~8 节）
--   第 2 节  声明  —— 46 条非空配置（内联自 02，含其自带的灌入守卫）
--   第 3 节  自证  —— schema 形状必须真的落库（否则本迁移静默不生效）
--   第 4 节  登记  —— schema_migration
--   第 5 节  回滚说明
-- ===========================================================================

-- ===========================================================================
-- 第 1 节  DDL（内联自 01_truth_source_ddl.sql 第 47 行起）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 2) 总部层声明表：配置编号 / 键 / 值类型 / 允许值 / 禁止值 / 初始值
--    - 不含租户维度；不含任何代码可读写的"运行时值"。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS config_slot
(
    config_no        INTEGER     NOT NULL,
    config_key       VARCHAR(64) NOT NULL,
    value_type       VARCHAR(16) NOT NULL,
    -- 允许集合 (NULL = 不限)。值类型为 ENUM / BOOL 时使用。
    allowed_values   TEXT[],
    -- 禁止组合 (NULL = 无)。用于"技术上不可能 / 合规硬约束"的取值，
    -- 例：#44 禁止任何"小程序后台自动采集"形态（微信 requiredBackgroundModes
    -- 无 bluetooth，该组合物理上不成立）。
    forbidden_values TEXT[],
    -- 新租户初始化时的总部默认值。租户生效值在 app_config（唯一读路径）。
    initial_value    TEXT        NOT NULL,
    -- PRD「附：可配置项清单」的原配置项名称，逐字抄录，便于与 PRD 交叉核对（防两处漂移）。
    -- （注意：那张表不在 §10 —— §10 是「待确认问题」。标题锚点 = `## 附：可配置项清单`。）
    prd_item_name    TEXT        NOT NULL,
    description      TEXT        NOT NULL,

    CONSTRAINT config_slot_pk PRIMARY KEY (config_no),
    CONSTRAINT config_slot_key_uniq UNIQUE (config_key),
    CONSTRAINT config_slot_value_type_known
        CHECK (value_type IN ('INT', 'DECIMAL', 'BOOL', 'ENUM', 'JSON', 'TEXT')),
    -- 编号域：1..48
    --   上界 = 当前已落 PRD 的最大编号（2026-09-21 由 46 放到 48，因新增 #48
    --   `health_pnl_visibility`）。域约束的作用 = 让"编号漂移到域外"在库层直接失败，
    --   而不是等到与 PRD 交叉核对时才发现整体错位。
    --   ⚠️ #47 在本域【内】但当前无声明行 —— 这是刻意的：#47 已被 optin-arch 线预定
    --   （`service_plan_optin_visibility`）、尚未落 PRD，属"暂缺待落盘"，不是空号。
    --   故它【不得】像 #42 那样被 CHECK 永久封死，否则将来 #47 落盘还要再改一次 DDL。
    --   #47 当前的"必须缺席"由 02_slots_seed.sql 的灌入守卫 + 集成测试断言把守。
    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),
    -- ★ #42 空号保留（PRD §10「【#42 编号预留说明（防误读）】」）：
    --   本表 #42 为 card_visibility 的预留编号，当前未启用，属容器内部技术配置项、
    --   非业务政策参数，不进主配置表。2026-09-18 新增的「手环数据可见性矩阵」
    --   因此编为 #43 而非 #42（非跳号、非漏号）。
    --   这条约束是"#42 不得占用 / 不得复用 / 不得让编号位移"的机器保证。
    CONSTRAINT config_slot_no_42_stays_vacant CHECK (config_no <> 42)
);

-- ---------------------------------------------------------------------------
-- 3) 租户层生效值表（运行时唯一读取来源）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_config
(
    tenant_id  UUID        NOT NULL REFERENCES tenant (id),
    config_no  INTEGER     NOT NULL REFERENCES config_slot (config_no),
    value      TEXT        NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 1,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT app_config_pk PRIMARY KEY (tenant_id, config_no)
);

CREATE INDEX IF NOT EXISTS idx_app_config_tenant ON app_config (tenant_id);

-- ---------------------------------------------------------------------------
-- 4) 变更历史表：who / when / before / after（+ op / version）
--    before_value 允许为空（首次写入没有前值）；after_value 必有。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_config_history
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id    UUID         NOT NULL,
    config_no    INTEGER      NOT NULL,
    config_key   VARCHAR(64)  NOT NULL,
    who          VARCHAR(128) NOT NULL,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    before_value TEXT,
    after_value  TEXT         NOT NULL,
    op           VARCHAR(16)  NOT NULL,
    version      BIGINT       NOT NULL,

    CONSTRAINT app_config_history_pk PRIMARY KEY (id),
    CONSTRAINT app_config_history_op_known CHECK (op IN ('SEED', 'UPSERT', 'ROLLBACK')),
    -- 历史行必须挂在同一租户的同一个配置项上（跨租户留痕在结构上不成立）
    CONSTRAINT app_config_history_parent
        FOREIGN KEY (tenant_id, config_no) REFERENCES app_config (tenant_id, config_no)
);

CREATE INDEX IF NOT EXISTS idx_app_config_history_tenant
    ON app_config_history (tenant_id, config_no, changed_at);

-- ---------------------------------------------------------------------------
-- 5) RLS：与业务表 customer 逐字同形（ADR-02 第 2 层）
--
--    ENABLE + FORCE 两者都要：FORCE 使【表 owner 也受策略约束】。
--    这点在配置表上尤其关键 —— 配置是审计证据的载体，owner 绕过等于证据可被伪造。
--
--    fail-closed 表达式逐字照抄业务表范式，语义见 V1 基线迁移的长注：
--      current_setting('app.tenant_id', true) -> 未设置时 NULL
--      NULLIF(NULL, '') / NULLIF('', '')      -> 统一收敛为 NULL
--      tenant_id = NULL                       -> UNKNOWN (非 TRUE) -> 零行
--    因此"未注入租户上下文"= 读到 0 行，而不是读到全部租户的配置。
-- ---------------------------------------------------------------------------
ALTER TABLE app_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_config FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app_config;
CREATE POLICY tenant_isolation ON app_config
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE app_config_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_config_history FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app_config_history;
CREATE POLICY tenant_isolation ON app_config_history
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 6) 取值校验（数据库层 fail-closed）
--
--    服务层有同一个校验（DefaultConfigValidator.validateValue）—— 两层都要有：
--      服务层  -> 调用方拿到 BizException(5001)，语义清晰；
--      数据库层 -> 任何绕过服务的写入路径（DBA / 脚本 / 批量导入）同样写不进非法值。
--    只做服务层 = 换一条写入路径就失守；只做数据库层 = 调用方拿到的是裸 SQL 异常。
--
--    校验规则【完全由 config_slot 驱动】，函数体内不出现任何具体编号或具体取值，
--    所以新增配置项只需插一行声明，不需要改这个函数，也不会硬编码 45 这个数量。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_config_enforce_slot() RETURNS trigger AS $fn$
DECLARE
    s config_slot%ROWTYPE;
BEGIN
    SELECT * INTO s FROM config_slot WHERE config_no = NEW.config_no;
    IF NOT FOUND THEN
        -- 外键本应拦住，这里再兜一层：给出可读原因而不是裸的 23503。
        RAISE EXCEPTION '配置编号 #% 未被声明，不得占用（#42 为空号）', NEW.config_no
            USING ERRCODE = '23503';
    END IF;

    IF s.value_type = 'INT' THEN
        IF NEW.value !~ '^[0-9]+$' THEN
            RAISE EXCEPTION '配置 #% 取值必须为非负整数，实际为 %', NEW.config_no, NEW.value
                USING ERRCODE = '23514';
        END IF;

    ELSIF s.value_type = 'DECIMAL' THEN
        IF NEW.value !~ '^[0-9]+(\.[0-9]+)?$' THEN
            RAISE EXCEPTION '配置 #% 取值必须为非负小数，实际为 %', NEW.config_no, NEW.value
                USING ERRCODE = '23514';
        END IF;

    ELSIF s.value_type = 'BOOL' THEN
        IF NEW.value NOT IN ('true', 'false') THEN
            RAISE EXCEPTION '配置 #% 取值必须为 true / false，实际为 %', NEW.config_no, NEW.value
                USING ERRCODE = '23514';
        END IF;

    ELSIF s.value_type = 'JSON' THEN
        BEGIN
            PERFORM NEW.value::jsonb;
        EXCEPTION WHEN others THEN
            RAISE EXCEPTION '配置 #% 取值必须为合法 JSON，实际为 %', NEW.config_no, NEW.value
                USING ERRCODE = '23514';
        END;
    END IF;

    -- 禁止组合先判：它的拒绝理由比"不在允许集合内"更具体，先判才能把理由说清楚。
    -- 例 #44：禁止的是"小程序后台自动采集"这一【代码组合】，而不是某个拼写。
    IF s.forbidden_values IS NOT NULL AND NEW.value = ANY (s.forbidden_values) THEN
        RAISE EXCEPTION '配置 #% 取值 % 属禁止组合（技术不可能 / 合规硬约束）', NEW.config_no, NEW.value
            USING ERRCODE = '23514';
    END IF;

    IF s.allowed_values IS NOT NULL AND NOT (NEW.value = ANY (s.allowed_values)) THEN
        RAISE EXCEPTION '配置 #% 取值 % 不在允许集合 % 内', NEW.config_no, NEW.value, s.allowed_values
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS app_config_enforce_slot_trg ON app_config;
CREATE TRIGGER app_config_enforce_slot_trg
    BEFORE INSERT OR UPDATE ON app_config
    FOR EACH ROW
EXECUTE FUNCTION app_config_enforce_slot();

-- ---------------------------------------------------------------------------
-- 8) 变更历史：由【触发器】写入，而不是由应用层写入
--
--    这是本模块最关键的一个选择。若历史由 Java 服务写入，那么
--      - DBA 手敲一条 UPDATE
--      - 迁移脚本批量改值
--      - 任何绕过服务的写入路径
--    都不会留痕 —— 而恰恰是这几种路径最需要留痕（配置是审计证据的载体）。
--    触发器让 who / when / before / after 对【全部写入路径】生效，
--    包括 psql 直连。测试用 superuser 直改表来证明这一点（见 04_assert.sql C10）。
--
--    who     = app.actor（应用在事务内用 set_config 绑定写入人）；
--              未设置时回落到 current_user（数据库角色）——宁可记粗也不能空着。
--    when    = changed_at（DB 时钟，不接受客户端传时间）
--    before  = 旧值（首次写入为 NULL）
--    after   = 新值
--    op      = INSERT -> 'SEED'；UPDATE -> app.config.op（默认 'UPSERT'，回滚时置 'ROLLBACK'）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_config_record_history() RETURNS trigger AS $fn$
DECLARE
    v_key   VARCHAR(64);
    v_actor VARCHAR(128);
    v_op    VARCHAR(16);
BEGIN
    SELECT config_key INTO v_key FROM config_slot WHERE config_no = NEW.config_no;
    IF v_key IS NULL THEN
        RAISE EXCEPTION '配置编号 #% 未被声明，无法留痕（#42 为空号）', NEW.config_no
            USING ERRCODE = '23503';
    END IF;

    v_actor := COALESCE(NULLIF(current_setting('app.actor', true), ''),
                        NULLIF(current_setting('app.tenant_id', true), ''),
                        'unknown');

    IF TG_OP = 'INSERT' THEN
        v_op := 'SEED';
    ELSE
        v_op := COALESCE(NULLIF(current_setting('app.config.op', true), ''), 'UPSERT');
    END IF;

    INSERT INTO app_config_history
        (tenant_id, config_no, config_key, who, before_value, after_value, op, version)
    VALUES
        (NEW.tenant_id, NEW.config_no, v_key, v_actor,
         CASE WHEN TG_OP = 'INSERT' THEN NULL::text ELSE OLD.value END,
         NEW.value, v_op, NEW.version);

    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS app_config_record_history_trg ON app_config;
CREATE TRIGGER app_config_record_history_trg
    AFTER INSERT OR UPDATE ON app_config
    FOR EACH ROW
EXECUTE FUNCTION app_config_record_history();

-- 防篡改：历史表只允许追加。撤销非 owner 的 UPDATE/DELETE 是本表的硬约束；
-- 更彻底的做法是库级 REVOKE（同 audit_log 的处置，见 README 交付说明）。
-- 服务层回滚走的是 app_config 的 UPDATE（自动留 ROLLBACK 历史行），
-- 而不是去改历史行 —— 所以历史行本身永远不需要 UPDATE。
CREATE OR REPLACE FUNCTION app_config_history_append_only() RETURNS trigger AS $fn$
BEGIN
    RAISE EXCEPTION 'app_config_history 是 append-only：历史不得被修改或删除 (尝试 %)', TG_OP
        USING ERRCODE = '42501';
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS app_config_history_append_only_trg ON app_config_history;
-- UPDATE / DELETE 触发器设为 NOT DEFERRABLE 的 BEFORE，保证改动在事务内立即失败
CREATE TRIGGER app_config_history_append_only_trg
    BEFORE UPDATE OR DELETE ON app_config_history
    FOR EACH ROW
EXECUTE FUNCTION app_config_history_append_only();

-- ---------------------------------------------------------------------------
-- 7) 写入前必须已有租户上下文（fail-closed）
--    app_config.tenant_id 为 NOT NULL，且写入语句取
--    NULLIF(current_setting('app.tenant_id', true), '')::uuid —— 未设上下文时得到
--    NULL -> NOT NULL 违约 (23502)。即"没有租户上下文就写不进去"，
--    而不是"默认写到某个租户"。这条不需要额外 DDL，此处仅作机制说明。
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- 第 2 节  声明（内联自 02_slots_seed.sql 第 32 行起）
--           含该文件自带的 $seed_guard$ 灌入守卫：46 条 / #42 缺席 / #47 缺席。
-- ===========================================================================

-- 🛑 此处【刻意偏离】02_slots_seed.sql 一处，理由见文件头「一处刻意偏离」小节：
--    应用库里 app_config.config_no 外键引用本表，`DELETE FROM config_slot` 会被
--    23503 拦下（已实测）⇒ 该写法在应用库【不幂等】。故改用 ON CONFLICT DO UPDATE。
--    （02 原文件的 DELETE 行不在此内联。）

-- ---------------------------------------------------------------------------
-- 逐行抄录 PRD §10。第 8 列 = PRD 中的配置项名称，第 9 列 = 语义与当前值口径。
-- 值类型：INT / DECIMAL / BOOL / ENUM / JSON / TEXT（见 config_slot_value_type_known）。
-- JSON 类型的取值必须真的是合法 JSON —— 由 app_config_enforce_slot() 在真库中强制。
-- ---------------------------------------------------------------------------
INSERT INTO config_slot
    (config_no, config_key, value_type, allowed_values, forbidden_values,
     initial_value, prd_item_name, description)
VALUES
-- ===== #1 ~ #10 =====
(1, 'cfg:assessment.cycle_service_count', 'INT', NULL, NULL,
 '7',
 '周期评估间隔',
 '每 7 次服务触发一次周期评估。归属层级=总部唯一，非校准项（业务制度）。'),

(2, 'cfg:assessment.plan_cycles', 'INT', NULL, NULL,
 '2',
 '调理周期区间',
 '2 个调优周期，其内为有次数上限的调优区间（每周期 7 次）；其外为基础服务期（无次数上限的开放区间）。'
 '原"15~45 次"作废、不降为门店档位（并存档位=两套状态机，且与 Non-goal #4 冲突）。数值为推导值，需业务确认。'),

(3, 'cfg:assessment.first_cycle_checkpoint', 'INT', NULL, NULL,
 '7',
 '首周期主动评估点',
 '第 7 次。归属层级=总部唯一，非校准项（业务制度）。'),

(4, 'cfg:adherence.pass_threshold', 'DECIMAL', NULL, NULL,
 '0.80',
 '依从性达标阈值 AS',
 '0.80（门槛值不变）。判定口径 = AS_refund（固定不含 A2）；运营口径 = AS_ops（仅运营看板）。'
 '建议值，需真实数据校准 —— 不得伪装成事实。'),

(5, 'cfg:adherence.weights', 'JSON', NULL, NULL,
 '{"AS_refund":{"A1":0.571,"A3":0.286,"A4":0.143},"AS_ops":{"A1":0.40,"A2":0.30,"A3":0.20,"A4":0.10},"A2_in_AS_ops_only":true,"AS_refund_mode_independent":true}',
 '依从性权重（拆两套）',
 '① AS_refund（判定 / 退款门禁用，三维重归一）= A1 0.571 / A3 0.286 / A4 0.143'
 '（= 原 {A1,A3,A4} 权重 0.40/0.20/0.10 重归一；0.571+0.286+0.143 = 1.000）；'
 '② AS_ops（运营考核用，四维）= A1 0.40 / A2 0.30 / A3 0.20 / A4 0.10。'
 'A2 小程序填报率仅存在于 AS_ops、不参与判定；AS_refund 与模式无关 → 有端/无端门店同一客户得一致结论。'
 '建议值，需真实数据校准。与 config #4 的判定口径（AS_refund，固定不含 A2）同源。'),

(6, 'cfg:adherence.missing_policy', 'ENUM',
 ARRAY['structural_keep', 'behavioral_zero'], NULL,
 'structural_keep',
 '缺失值处理策略',
 '结构性缺失不扣分 / 行为性缺失计 0。归属层级=总部唯一，非校准项（合规约束）。'
 '允许值 = {structural_keep（结构性缺失不扣分）, behavioral_zero（行为性缺失计 0）}。'),

(7, 'cfg:adherence.min_sample_days', 'INT', NULL, NULL,
 '7',
 'AS 样本护栏',
 '应填天数 < 7 标"样本不足"。归属层级=总部唯一，非校准项。与 config #45 的 n（依从样本量）同源。'),

(8, 'cfg:safety.contraindication_list', 'JSON', NULL, NULL,
 '{"count":9,"items":[]}',
 '禁忌症清单',
 '总部维护（9 项 + 其他）。归属层级=总部唯一，非校准项（安全底线）。'),

(9, 'cfg:verdict.branch_rules', 'JSON', NULL, NULL,
 '{"branches":["稳定","依从不足","达标无效","全面评估","人工复核"],"requires":["effect_verdict","adherence_state","risk_flag"],"precondition":{"range":"0-4","same_origin":true},"branch_literals_owner":"verdict domain (VerdictBranch.dbLabel)","decision_rules":{"稳定":"D1","依从不足":"D2","达标无效":"D3","全面评估":"D4","人工复核":"D5"}}',
 '效果判定分支规则',
 '五分支固定（D1~D5）；分支条件引用 effect_verdict + adherence_state + risk_flag 组合，'
 '含"量程=0–4 且同源题组"前置断言。归属层级=总部唯一，非校准项。'
 '🛑 A-6：branches 数组的五个字面与 VerdictBranch.dbLabel() 逐字一致（"稳定/依从不足/达标无效/全面评估/人工复核"）——'
 '本配置【不持有】分支字面的所有权，唯一所有者是判定域枚举；'
 '此前的 4 个英文键（improved/stable/no_improvement/worsened）漏掉了 D5「人工复核」，'
 '而 D5 是 PRD §7.3 里与 D1~D4 并列的一等分支，漏装会让"配置里没有这个分支"'
 '与"代码里有这个分支"长期分叉。'),

(10, 'cfg:refund.gate_rules', 'JSON', NULL, NULL,
 '{"routes":[{"route":"fulfillment","mode":"direct"},{"route":"effect","mode":"negotiation_workorder","human_in_loop":true}],"entries":[{"id":"A","actor":"store_deputy_entry","record_within_hours":24},{"id":"B","trigger":"first_cycle","mode":"direct"}]}',
 '退款门禁规则',
 '二分通路（与入口位置无关）：履约类规则直退 / 效果类协商工单（人在环）；'
 '入口 A = 门店代客录入（客户诉求 24h 内代录）/ 入口 B = 首周期触发直退。'
 '归属层级=总部唯一，非校准项。'),

-- ===== #11 ~ #20 =====
(11, 'cfg:fill.backfill_window_days', 'INT', NULL, NULL,
 '2',
 '每日填报补填窗口',
 '2 天。归属层级=总部唯一（可门店微调），非校准项。'),

(12, 'cfg:band.field_schema', 'TEXT', NULL, NULL,
 'HUAWEI Health Service Kit',
 '手环字段 Schema',
 'HUAWEI Health Service Kit。归属层级=总部唯一，非校准项。'),

(13, 'cfg:device.template_ids', 'JSON', NULL, NULL,
 '["TPL-G2-V3","TPL-STD-V2"]',
 '设备参数模板',
 'TPL-G2-V3 / TPL-STD-V2。归属层级=总部维护，门店可微调须留痕，非校准项。'),

(14, 'cfg:alert.refund_rate', 'JSON', NULL, NULL,
 '{"warn":{"op":">","pct":15},"critical":{"op":">","pct":25}}',
 '退款率预警线',
 '>15% 黄 / >25% 红。建议值，需真实数据校准。'),

(15, 'cfg:alert.effect_refund_trigger_rate', 'JSON', NULL, NULL,
 '{"warn":{"op":">","pct":10},"critical":{"op":">","pct":20}}',
 '效果类退款触发率预警线',
 '>10% 黄 / >20% 红。建议值，需真实数据校准。'
 '名称随合规口径由"无效退款触发率"改为「效果类退款触发率」，与 HSI 第 3 项一致。'),

(16, 'cfg:alert.retention_success_rate', 'JSON', NULL, NULL,
 '{"warn":{"op":"<","pct":30},"critical":{"op":"<","pct":15}}',
 '挽留成功率预警线',
 '<30% 黄 / <15% 红。建议值，需真实数据校准。'),

(17, 'cfg:alert.refund_dispute_rate', 'JSON', NULL, NULL,
 '{"warn":{"op":">","pct":5},"critical":{"op":">","pct":10}}',
 '退款争议率预警线',
 '>5% 黄 / >10% 红。建议值，需真实数据校准。'),

(18, 'cfg:hsi.weights', 'JSON', NULL, NULL,
 '{"ecc":23,"refund_rate":18,"effect_refund_trigger_rate":13,"agreement_compliance_rate":9,"plan_first_pass_rate":9,"miniprogram_daily_fill_rate":9,"audit_issue_count":9,"decidable_coverage_rate":10,"total":100,"bands":{"green_min":80,"yellow_min":60}}',
 'HSI 权重与分层线',
 '8 项归一化权重（合计 = 100）+ 分层线（绿≥80 / 黄 60~79 / 红<60）。'
 '分步实施：先按 7 项建设 + 预留第 8 项配置位，权重回填后开启第 8 项、不改代码。'
 '建议值，需真实数据校准。'),

(19, 'cfg:hsi.min_course_count', 'INT', NULL, NULL,
 '20',
 'HSI 样本护栏',
 '结案疗程 < 20 不参与排名。归属层级=总部唯一，非校准项。'),

(20, 'cfg:audit.signal_thresholds', 'JSON', NULL, NULL,
 '{"leniency":1.3,"verdict_minutes":5,"similarity_pct":85,"accept_hours":48,"concentration_pct":60,"retention_pct":50}',
 '稽核信号阈值',
 '宽松度>1.3 / 判定<5min / 雷同度>85% / 受理>48h / 集中>60% / 挽留<50%。'
 '归属层级=总部唯一（对门店不可见）。建议值，需真实数据校准。'),

-- ===== #21 ~ #30 =====
(21, 'cfg:crossstore.split_threshold', 'DECIMAL', NULL, NULL,
 '0.30',
 '跨店拆分阈值',
 '≥30%（≥30% 次数他店完成则按占比拆分）。归属层级=总部唯一，非校准项。'),

(22, 'cfg:crossstore.anomaly_rule', 'JSON', NULL, NULL,
 '{"window_days":30,"store_count_mark":2,"store_count_freeze":3,"cross_share_pct":30}',
 '跨店异常阈值',
 '30 天内门店数 ≥2 标记 / >3 冻结；跨店占比 >30%。建议值，需真实数据校准。'),

(23, 'cfg:crossstore.transfer_workorder_required', 'BOOL', NULL, NULL,
 'true',
 '转店工单',
 '强制系统工单。归属层级=总部唯一，非校准项。'),

(24, 'cfg:ops.delegation_scope', 'JSON', NULL, NULL,
 '{"delegatable":["scheduling","reminder_frequency","scripts","assessment_weights"]}',
 '运营层：排班/提醒频次/话术/考核权重',
 '可下放至门店 / 区域。归属层级=门店 / 区域，非校准项。'),

(25, 'cfg:billing.mode', 'ENUM',
 ARRAY['per_store_plus_course_tier'], NULL,
 'per_store_plus_course_tier',
 '计费口径',
 '门店数 + 疗程量阶梯。归属层级=总部/商业侧，待商业确认。'
 '允许值 = {per_store_plus_course_tier}（当前唯一已裁定形态）。'),

(26, 'cfg:refund.verdict_latency', 'ENUM',
 ARRAY['immediate'], NULL,
 'immediate',
 '退款资格结论时效',
 '即时（系统直出，不计时延）；结论仅对内（门店 / 总部可见），客户端不展示任何退款相关结论、进度与状态。'
 '归属层级=总部唯一，非校准项。"即时"指内部计算不排队，不是"结论即时推送给客户"。'),

(27, 'cfg:refund.retention_sla_hours', 'INT', NULL, NULL,
 '48',
 '挽留响应 SLA',
 '自动派单后 48h 内首响应（仅对门店挽留动作计时）。建议值，需真实数据校准。'),

(28, 'cfg:refund.arrival_commitment_days', 'INT', NULL, NULL,
 '7',
 '退款到账承诺',
 '向客户承诺 7 个工作日内到账；内部计时（门店 / 总部可见）+ 电话 / 当面告知；'
 '客户端一律不展示任何退款相关进度（无倒计时、无状态、无字样）。建议值，需真实数据校准。'),

(29, 'cfg:refund.concession_approval_threshold', 'JSON', NULL, NULL,
 '{"basis":"consumed_service_cost","within_threshold":"store_self_decide","over_threshold":"escalate_headquarters"}',
 '退款让步审批阈值',
 '按已消耗次数对应的服务成本分段（非按售价）；阈内门店自决挽留，超阈 / 客户拒挽留自动上收总部。'
 '建议值，需真实数据校准。'),

(30, 'cfg:verdict.formula_params', 'JSON', NULL, NULL,
 '{"core_metric":"same_origin_total_0_112","range":{"min":0,"max":4,"dimensions":7,"items_per_dimension":4,"total":112},"improvement_threshold_requires_mcid":true,"adherence_gate":{"metric":"AS_refund","min":0.8},"window_count":7,"mode":"primary_criterion_plus_gate_not_weighted_sum"}',
 '效果判定公式参数组（对内）',
 '核心指标 = 同源题组 0–4 五级总分（0–112，4 题 × 0–4 × 7 维）+ 改善阈值（须含 MCID 门槛）'
 '+ 依从性门槛（AS_refund ≥ 0.8）+ 次数窗（默认 7）。'
 '为核心指标主判据 + 依从性门槛制，非加权得分。建议值，需真实数据校准。'),

-- ===== #31 ~ #41 =====
(31, 'cfg:backtest.acceptance_thresholds', 'JSON', NULL, NULL,
 '{"human_reliability":{"kappa_pass":0.70,"kappa_conditional":0.60,"absolute_agreement_pass":0.85},"formula":{"overall_agreement_pct":80,"false_negative_max_pct":5,"false_positive_max_pct":15},"sample":{"n_min":100,"branch_stratum_min":15,"dispute_min":30,"dispute_share_pct":20,"stores_min":3,"therapists_min":5},"recompute_on_change":true,"false_negative_denominator_pending":true}',
 '回测验收门槛（联合门槛 · 常设机制）',
 '① 人工信度：κ≥0.70 且绝对一致率≥85% 通过（κ 0.60~0.70 条件通过；κ<0.60 → 降级内部参考）；'
 '② 公式：总体一致率 ≥80%（参考）/ 漏判率 ≤5%（硬门槛）/ 误判率 ≤15%（软）；'
 '③ 样本：N≥100、四分支分层各 ≥15、争议件 ≥30 且占比 ≥20%、≥3 门店 ≥5 经络师；'
 '④ 公式/权重变更须重新回测、旧报告作废。建议值，需真实数据校准。'
 '漏判率分母口径待数析定稿（子池最小样本量 ≥40 / 报 Wilson 置信区间上限 / 小子池改零漏判，三选一），'
 '故本项预留分母口径（false_negative_denominator_pending）。'),

(32, 'cfg:improvement.calc_rule', 'JSON', NULL, NULL,
 '{"same_origin_assert":{"item_group_same":true,"range":"0-4","measurer_same":true},"baseline_zero_excluded":true,"negative_not_truncated":true}',
 '改善率计算口径',
 '同源断言（题组 ID 同源 ∧ 量程 0–4 ∧ 测量人同一）+ baseline_zero 不计入 + 负值不截断。'
 '归属层级=总部唯一，非校准项。'),

(33, 'cfg:verdict.mcid_threshold', 'JSON', NULL, NULL,
 '{"module_total_max":16,"improved":{"delta_min":3,"pct":18.8,"no_item_rise_ge":2},"stable":{"delta_min":0,"delta_max":2},"worsened":{"delta_rise_min":1}}',
 'MCID 最小可察觉改善门槛',
 '模块总分（0–16）下降 ≥3 分（≈18.8%）且无任一题项上升 ≥2 级 → 改善；'
 '下降 1~2 分或不变 → 稳定；上升 ≥1 分 → 无明显改善/加重。建议值，需真实数据校准。'
 '★ stable.delta_min = 0（不是 1）：与规格 §1.4 分档表「无变化 → 稳定（E3）」及本行上一句'
 '「下降 1~2 分【或不变】→ 稳定」同义。若写成 1，则 Δ=0（"无变化"，临床最常见结果）'
 '既不 ≥stable.delta_min 也不 ≤worsened 门槛 → 落不进任何档 → 判定引擎抛 5001。'
 '★ 三条分档必须首尾相接：improved.delta_min = stable.delta_max + 1、'
 'stable.delta_min = 1 − worsened.delta_rise_min（本处 0 = 1 − 1），'
 '二者由 DerivedMetricProfile.Mcid 构造期断言，任一处改动导致接缝出现空洞即启动失败。'
 '★ 本项的 Δ=3 同时是 config #45 置信度合成公式 m(Δ) 的唯一谷底（m(3)=0.150）——'
 '两处必须同源，改一处必须改另一处，否则 #45 的"以 Δ=3 为唯一谷底"不再成立。'),

(34, 'cfg:questionbank.direction_rule', 'ENUM',
 ARRAY['symptom'], NULL,
 'symptom',
 '题库题面方向一致性校验',
 '入库前强制：题面方向须与 02 表 §三 0–4 评分锚点一致（不适/症状向）；'
 'item_direction 仅允许 symptom；缺人工签核记录拒入库。归属层级=总部唯一，非校准项。'),

(35, 'cfg:scale.range_rule', 'JSON', NULL, NULL,
 '{"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,"same_origin_group":"age_band_locked_by_02"}',
 '量表量程与题组口径',
 '量程统一 0–4 五级（维度满分 0–16 / 总分 0–112）；同源题组 = 02 表锁定的分龄题组。'
 '归属层级=总部唯一，非校准项。'),

(36, 'cfg:archive.blocking_switch', 'JSON', NULL, NULL,
 '{"doc_or_signature_missing":"hard_block","band_missing":"warning","band_block_invertible":false}',
 '归档/证据链阻断开关',
 '资料/签字缺项 = 硬阻断；手环缺项 = 警告（不可反转为阻断）。归属层级=总部唯一，非校准项。'),

(37, 'cfg:questionbank.age_group_lock', 'BOOL', NULL, NULL,
 'true',
 '分龄题组锁定',
 '锁定后服务期不因生日跨段更换题组。归属层级=总部唯一，非校准项。'),

(38, 'cfg:refund.fulfillment_direct_formula', 'JSON', NULL, NULL,
 '{"formula":"remaining_course_count * unit_price_paid","full_unused_refundable":true,"effect_judgement_allowed":false,"initiator":"store_staff_deputy"}',
 '履约类退款直退算法',
 '未消耗次数 × 单次均价（实付）；足额未核销可整单退。'
 '该算法只吃"未消耗次数 × 单次均价（实付）"，不得引入任何效果判断'
 '（行业把退款钉在交付状态、而非效果）。发起方 = 门店（经络师 / 门店负责人）代客发起。'
 '非本店核实值，待业务核实。建议值，需真实数据校准。'),

(39, 'cfg:questionbank.dual_bank_isolation', 'JSON', NULL, NULL,
 '{"add_scores_across_banks":false,"substitute_across_banks":false,"replacement":"whole_segment_only","bank_mode_configurable":true}',
 '双题库隔离（混算禁止）',
 '两库分数不得相加、不得互相代入改善率分子/分母；替换须整段替换、不得混用；'
 '"单库 / 双库"为可配置项（非硬编码）。归属层级=总部唯一，非校准项。'),

(40, 'cfg:refund.visibility', 'JSON', NULL, NULL,
 '{"customer":false,"therapist":false,"store_customer_service":false,"meridian_therapist":true,"store_admin":true,"area_supervisor":{"visible":true,"approve":false},"headquarters_ops":{"visible":true,"audit":true},"role_changes_need_no_code":true,"deputy_cannot_approve":true}',
 '退款可见性矩阵（refund_visibility）',
 '按角色配置：客户 ✗ / 调理师 ✗ / 门店客服 ✗（2026-09-16 业务方裁定，完全不可见、不代录）/'
 '经络师 ✓ / 门店负责人（管理员）✓ / 区域督导 ✓（可见、不审批）/ 总部运营 ✓（可见 + 稽核）；'
 '新增/调整角色不改代码。两项定值已于 2026-09-16 冻结，早于 P0-19 开工：条款 A 已达成、条款 B 继续有效。'
 '⚠️ 与 P0-19 第④条绑定的一般化规则：代录者不可审批。归属层级=总部唯一，非校准项（业务方已裁定）。'),

(41, 'cfg:coverage.alert_thresholds', 'JSON', NULL, NULL,
 '{"periodic":{"warn_pct":90,"critical_pct":80,"op":"<"},"cumulative":{"alert":false},"ecc_card_shows":["ecc_count","denominator_closed_courses","decidable_coverage_rate"],"ecc_single_number_forbidden":true,"store_level_sample_min":20,"phase":2}',
 '可判定覆盖率告警阈值',
 '主指标·期间可判定覆盖率（告警用）：<90% 黄 / <80% 红。'
 '副指标·累计覆盖率（含 legacy，仅展示不告警）。'
 'ECC 报表卡片默认同时展示「ECC 数 / 分母（结案疗程数）/ 可判定覆盖率」三数，禁止只报 ECC 一个数。'
 '归属 Phase 2（随 P1-08）。建议值，需真实数据校准。'),

-- ===== ★ #42 = 空号（永久预留）、#47 = 暂缺（待 optin-arch 落盘），两者此处均【故意缺席】 ★ =====

-- ===== #43 ~ #46（2026-09-18 / 09-19 / 09-19 新增；均不进 #42）=====
(43, 'cfg:band.visibility', 'JSON', NULL, NULL,
 '{"customer":{"raw_data":true,"capture_status":true,"gap_reason":false,"derived_result":false},"therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true,"derived_not_adverse_to_customer":true},"meridian_therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true},"admin":{"scope_narrowed_by_row":true,"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true},"store_customer_service":{"endless":true},"customer_gap_reason_and_derived_locked":true,"unconfigured_means_deny":true}',
 '手环数据可见性矩阵（band_visibility）',
 '按「角色 × 字段组」配置（非按端配置 —— 同一 APP 内调理师 / 经络师可见范围不同）。'
 '字段组 = {①手环原始数据 ②采集状态 ③缺口原因分类 gap_reason ④派生结果}。'
 '建议当前值：客户 = ①② 可见，③④ 一律 ✗（2026-09-18 业务方要求下的硬边界，见 §2.6.2 W-1）；'
 '调理师 = ①②③④（但 ④ 不得作对客户不利依据）；经络师 = ①②③④；'
 '管理端 = ①②③④，按行级 scope 收窄（本店 / 辖区 / 全量）；门店客服 = 无端，任何字段组均不成立。'
 '机制要求：新增 / 调整角色不改代码；未配置即拒绝（fail-closed），不得默认全开；变更须留痕。'
 '⚠️ 客户侧 ③④ 为硬约束、不得通过配置放开 —— 放开属「变更业务裁定」。'
 '#43 管"能不能看"，#44 管"谁来采"，两者独立配置、不得互相替代。'),

(44, 'cfg:band.capture_mode', 'ENUM',
 ARRAY['M-APP', 'M-WX-FG', 'M-SVR-PULL'],
 ARRAY['WX_BACKGROUND_AUTO', 'MINIPROGRAM_BACKGROUND_AUTO', '小程序后台自动采集', 'miniprogram-background-auto'],
 'M-WX-FG',
 '手环采集执行模式（band_capture_mode）',
 '决定「谁采集、在什么时序采集」，不决定可见性（可见性见 #43）。'
 '可选值 = {M-APP（厂商 App 自动回传）, M-WX-FG（小程序 onShow 自动发起同步）, '
 'M-SVR-PULL（服务端定时拉取厂商云，前提=厂商云 API 存在，A14 大概率不成立）}。'
 '⛔ 现行冻结值（2026-09-19 业务方拍板）= M-WX-FG —— 业务方裁定「先做小程序、不新增原生 App」。'
 '硬约束：禁止配置出"小程序后台自动采集" —— 微信 requiredBackgroundModes 无 bluetooth，'
 '该组合技术上不可能（§2.8.2 P-2），故由 forbidden_values 在数据库层硬拒绝（fail-closed）。'
 '与 M-WX-FG 绑定的连带约束：① 须实现设备端历史补拉；② 须实现三类失败可分辨 + 客户端同步状态四态可见；'
 '③ A3 未达标时按 applicable=False 重归一。'),

(45, 'cfg:verdict.confidence_formula', 'JSON', NULL, NULL,
 '{"canonical":"continuous_0_1","bands":{"high_min":0.75,"medium_min":0.45},"composition":"s * d^0.25 * n^0.25 * m^0.50","s":{"same_origin":1.0,"missing_metadata":0.5,"incomparable":0.0,"incomparable_forces_human_and_null_verdict":true},"d":{"answered":28},"n":{"min_sample_days":7,"applicable_false_below":7,"formula":"min(1, expected_days/14)","reweight_when_na":{"d":0.3333,"m":0.6667}},"m":{"delta_gap":3,"base":0.15,"span":0.85,"canonical":"0.15 + 0.85 * min(1, abs(delta - 3) / 3)","floor":0.15},"not_in_ecc":true,"not_in_hsi_weights":true}',
 '判定置信度合成口径（verdict_confidence）',
 '表示 = 0–1 连续分为 canonical；三档（高 ≥0.75 / 中 0.45~0.75 / 低 <0.45）仅作展示层。'
 '四项合成 = C = s × d^0.25 × n^0.25 × m^0.50（加权几何、非算术和）。'
 '① s 同源状态 = 一票否决（同源=1 / 缺元数据=0.5 / 不可比=0 → C=0，且 disposition 强制转人工）；'
 '② d 题组完成度 = 实际答题数 / 28；'
 '③ n 依从样本量：应填天数 <7 → applicable=False 退出合成、权重重归一；≥7 → n = min(1, 应填天数/14)；'
 '④ m MCID 裕度（config #33，取最弱模块）：canonical = 0.15 + 0.85 · min(1, |Δ − 3| / 3)，'
 'Δ=3 → m=0.150 为唯一全局最小 / 地板。⚠️ 不进 ECC、不进 HSI 权重（config #18 八项不动）—— 仅作协商辅助。'
 '可见性复用既有 verdict 角色级规则，不挂 config #40/#43。'
 '算法细则以《指标规格》§10.4 为唯一权威源；本行只载可配置默认值。建议值，需真实数据校准。'),

(46, 'cfg:doc.template_editable', 'BOOL', NULL, NULL,
 'true',
 '文书/协议文本模板可编辑（doc_template_editable）',
 '超级管理员（tenant 级）可编辑协议/须知全文；门店只读；每次编辑生成新 version、旧版不可覆盖；'
 '签署快照不回溯；高风险词非阻断提示。归属层级=总部唯一（超管），非校准项（业务方 2026-09-19 裁定）。'),

-- ===== #48（2026-09-21 新增；跳过 #47 = 该号已由 optin-arch 线预定、尚未落 PRD）=====
(48, 'cfg:health_pnl.visibility', 'JSON', NULL, NULL,
 '{"customer":{"health_class":true,"refund_class":false},"therapist":{"health_class":"partial","refund_class":false,"partial_fields":["delta_h","delta_h_module"],"excluded_fields":["improvement_rate","mcid","effect_verdict","refund_eligibility"]},"meridian_therapist":{"health_class":true,"refund_class":true},"admin":{"health_class":true,"refund_class":true,"scope_narrowed_by_row":true},"store_customer_service":{"endless":true},"health_pnl_client_boundary":{"client_only_health_class":true,"fail_closed":true,"testable":true,"auditable":true},"unconfigured_means_deny":true,"customer_refund_class_hard_locked":true}',
 '健康资产损益端可见性（health_pnl_visibility）',
 '按「角色 × 可见内容」配置（继承 #40 / #43 的既有角色矩阵机制）。'
 '内容分两层：①「健康类」= 原始值 / 健康指数 / 变化量 / 维度分 / 手环趋势（字段以 §2.9.5 白名单为准）/ 仅「健康类」结论；'
 '②「退款类」= A3 佩戴率 / AS 值 / 依从性维度分 / effect_verdict / improvement_rate / MCID 判定 / 退款资格 / 达标·未达标评价 / 门槛数字。'
 '建议当前值：客户（小程序）= ① 可见、② 一律 ✗（2026-09-20 业务方裁定 A 的「只到健康类」边界）；'
 '调理师（APP）= 只给 ΔH / ΔH_m，不给 IR / MCID / effect_verdict / 退款资格（避让 #40 与 R-18）；'
 '经络师（APP）= ①② 可见；管理端（门店负责人 / 区域督导 / 总部运营）= ①②，按行级 scope 收窄；门店客服 = 无端，不适用。'
 '条件项（不单独占号）：health_pnl_client_boundary =「只到健康类」边界条件，须可测试、可留痕、fail-closed。'
 '机制要求：新增 / 调整角色不改代码；未配置即拒绝（fail-closed），不得默认全开；变更须留痕。'
 '⚠️ 客户侧 ② 为硬约束、不得通过配置放开 —— 放开属「变更业务裁定」，触发与 §2.2 条款 B 同等的返工与重走冻结流程（§2.6.7 R15 / §2.9.4）；'
 '故本行同时以 customer_refund_class_hard_locked=true 显式登记该锁。'
 '与 #40 / #43 独立配置、不得互相替代、不得合并成一张矩阵；'
 '不得把「健康指数」塞进 #43 的 ④ 派生结果组（展示手环原始数据时字段可见性以 #43 ① 为准 = 消费 #43 的裁定，不是改写它）。'
 '⚠️ 本项为待评审增项（PRD 附录 C.4 · P0-28，Phase 2）：人日为建议值、需研发评审，未经评审不得当承诺排期。')
-- 🛑 ON CONFLICT DO UPDATE —— 本迁移相对 02_slots_seed.sql 的唯一一处刻意偏离。
--    目的：① 幂等可重跑；② 声明值改动仍然生效（这是 02 原注释真正想要的效果）。
--    已初始化租户的生效值不受影响：它们在 app_config，按 01 的「读路径纪律」
--    只在【新租户初始化】时读一次 initial_value。
ON CONFLICT (config_no) DO UPDATE
   SET config_key       = EXCLUDED.config_key,
       value_type       = EXCLUDED.value_type,
       allowed_values   = EXCLUDED.allowed_values,
       forbidden_values = EXCLUDED.forbidden_values,
       initial_value    = EXCLUDED.initial_value,
       prd_item_name    = EXCLUDED.prd_item_name,
       description      = EXCLUDED.description;

-- ---------------------------------------------------------------------------
-- 灌完后自证：#42 / #47 不得存在，且非空条数必须是 46。
-- 这三条断言让"本文件写漏了 / 多写了一行"在迁移阶段就炸，而不是等到运行时。
-- ---------------------------------------------------------------------------
DO $seed_guard$
DECLARE
    n_total   INTEGER;
    n_vacant  INTEGER;
    n_resv47  INTEGER;
    n_tail    INTEGER;
BEGIN
    SELECT count(*) INTO n_total FROM config_slot;
    -- #42 = 永久空号（历史预留，见文件头「编号纪律」）
    SELECT count(*) INTO n_vacant FROM config_slot WHERE config_no = 42;
    -- #47 = 暂缺（已由 optin-arch 线预定、尚未落 PRD；落盘前必须保持缺席，
    --        否则会出现"实现先于裁定"的带债设计 —— ADR 十五）
    SELECT count(*) INTO n_resv47 FROM config_slot WHERE config_no = 47;
    -- 编号域内 43..46 与 48 必须齐备（证明 #42/#47 是"缺席"而非"编号整体位移"）
    SELECT count(*) INTO n_tail FROM config_slot WHERE config_no IN (43, 44, 45, 46, 48);

    IF n_total <> 46 THEN
        RAISE EXCEPTION 'config_slot 非空配置应为 46 条，实际 %', n_total;
    END IF;
    IF n_vacant <> 0 THEN
        RAISE EXCEPTION '#42 为空号，不得出现，实际出现 % 行', n_vacant;
    END IF;
    IF n_resv47 <> 0 THEN
        RAISE EXCEPTION '#47 已由 optin-arch 线预定但尚未落 PRD，不得先行落库，实际出现 % 行', n_resv47;
    END IF;
    IF n_tail <> 5 THEN
        RAISE EXCEPTION '#43~#46 与 #48 应齐备 5 条（证明 #42/#47 是缺席而非编号位移），实际 %', n_tail;
    END IF;

    RAISE NOTICE 'config_slot OK: % 条声明, #42 空号保留, #47 预留保持缺席, #43~#46 + #48 齐备', n_total;
END;
$seed_guard$ LANGUAGE plpgsql;

-- ===========================================================================
-- 第 3 节  自证：schema 形状与声明结果必须真的落库
--   与 V10~V13 同口径：不满足即 RAISE EXCEPTION，整个迁移回滚。
--   🛑 自证断言的【不是代码里的常量】，而是 information_schema / pg_class /
--      pg_policies / pg_trigger / pg_constraint 里的【真实元数据】——
--      DDL 没生效时它们立刻为空，而不是"我写的时候是对的"。
-- ===========================================================================
DO $v14_guard$
DECLARE
    v_missing  TEXT;
    v_noforce  TEXT;
    v_nopolicy TEXT;
    v_notrig   TEXT;
    v_noconstr TEXT;
    v_slots    INTEGER;
    v_42       INTEGER;
    v_47       INTEGER;
BEGIN
    -- (a) 三张表必须存在
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_missing
    FROM (VALUES ('config_slot'), ('app_config'), ('app_config_history')) AS x(t)
    WHERE NOT EXISTS (SELECT 1 FROM information_schema.tables
                      WHERE table_schema = 'public' AND table_name = x.t);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'V14 自证失败(a): 配置真相源表未建成 -> %', v_missing;
    END IF;

    -- (b) 两张【租户层】表必须 ENABLE + FORCE RLS。
    --     config_slot 刻意【不】启用 RLS：它没有 tenant_id（对所有租户同一份，
    --     性质同 tenant / schema_migration 这类框架表）。给它套按租户隔离的策略，
    --     会立刻让"总部声明"变成"按租户各一份"，与 ADR-08 的
    --     「总部唯一 vs 租户层可覆盖」分层直接矛盾。
    --     缺 FORCE 的后果：表 owner（很可能是应用自己的连接用户）绕过策略 ⇒ RLS 形同虚设。
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_noforce
    FROM (VALUES ('app_config'), ('app_config_history')) AS x(t)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_class c
        WHERE c.relname = x.t AND c.relkind = 'r'
          AND c.relrowsecurity AND c.relforcerowsecurity);
    IF v_noforce IS NOT NULL THEN
        RAISE EXCEPTION 'V14 自证失败(b): 以下表未同时 ENABLE+FORCE RLS -> %（缺 FORCE 会让表 owner 绕过策略）',
            v_noforce;
    END IF;

    -- (c) 两张表必须各至少有一条策略（ENABLE 但无策略 = 拒绝一切，不是"隔离"）
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_nopolicy
    FROM (VALUES ('app_config'), ('app_config_history')) AS x(t)
    WHERE NOT EXISTS (SELECT 1 FROM pg_policies
                      WHERE schemaname = 'public' AND tablename = x.t);
    IF v_nopolicy IS NOT NULL THEN
        RAISE EXCEPTION 'V14 自证失败(c): 以下表没有 RLS 策略 -> %（ENABLE 但无策略 = 拒绝一切，不是隔离）',
            v_nopolicy;
    END IF;

    -- (d) app_config 的两个触发器必须在位：取值校验 + 变更留痕。
    --     缺留痕触发器的后果不是"少一行日志"，而是【配置变更不可举证】——
    --     配置是审计证据的载体（ADR-08），who/when/before/after 必须由库层保证，
    --     覆盖 DBA 手改 / 脚本批量改等【绕过服务层】的写入路径。
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_notrig
    FROM (VALUES ('app_config_enforce_slot_trg'), ('app_config_record_history_trg')) AS x(t)
    WHERE NOT EXISTS (SELECT 1 FROM pg_trigger
                      WHERE tgname = x.t AND NOT tgisinternal);
    IF v_notrig IS NOT NULL THEN
        RAISE EXCEPTION 'V14 自证失败(d): app_config 的触发器缺失 -> %（缺留痕触发器 = 配置变更不可举证）',
            v_notrig;
    END IF;

    -- (e) config_slot 的四条关键约束必须在位。
    --     缺 config_slot_no_42_stays_vacant 的后果：一条普通 INSERT 就能占用 #42 空号，
    --     而 #43~#48 的编号引用（#43/#44 之所以不是 #42 的理由）会静默错位。
    SELECT string_agg(x.t, ', ' ORDER BY x.t) INTO v_noconstr
    FROM (VALUES ('config_slot_pk'), ('config_slot_key_uniq'),
                 ('config_slot_value_type_known'), ('config_slot_no_in_domain'),
                 ('config_slot_no_42_stays_vacant')) AS x(t)
    WHERE NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = x.t);
    IF v_noconstr IS NOT NULL THEN
        RAISE EXCEPTION 'V14 自证失败(e): config_slot 的约束缺失 -> %（缺 no_42 约束会让空号可被占用、编号引用静默错位）',
            v_noconstr;
    END IF;

    -- (f) 声明结果：46 条非空，且 #42 / #47 必须缺席。
    --     这与第 2 节自带的 $seed_guard$ 是【同一件事的两处断言】，刻意重复：
    --     第 2 节守的是"种子灌得对"，本节守的是"迁移结束后库里的状态对"——
    --     将来若有人把第 2 节换成别的写法（或删除其守卫），本节仍然独立成立。
    SELECT count(*) INTO v_slots FROM config_slot;
    SELECT count(*) INTO v_42    FROM config_slot WHERE config_no = 42;
    SELECT count(*) INTO v_47    FROM config_slot WHERE config_no = 47;
    IF v_slots <> 46 THEN
        RAISE EXCEPTION 'V14 自证失败(f): config_slot 应为 46 条非空配置，实际 %', v_slots;
    END IF;
    IF v_42 <> 0 THEN
        RAISE EXCEPTION 'V14 自证失败(f): #42 为永久空号，不得出现，实际 % 行', v_42;
    END IF;
    IF v_47 <> 0 THEN
        RAISE EXCEPTION 'V14 自证失败(f): #47 已预留但尚未落 PRD（实现先于裁定），不得先行落库，实际 % 行', v_47;
    END IF;

    RAISE NOTICE 'V14 自证通过: 配置真相源三表已落应用库；config_slot % 条声明；'
                 'app_config / app_config_history 均 ENABLE+FORCE RLS 且有策略；'
                 'app_config 校验与留痕触发器在位；#42 空号保留、#47 预留缺席', v_slots;
END;
$v14_guard$ LANGUAGE plpgsql;

-- ===========================================================================
-- 第 4 节  迁移版本登记
-- ===========================================================================
INSERT INTO schema_migration (version, description)
VALUES ('V14', 'config truth source tables (ADR-08 / B-3): config_slot + app_config + app_config_history into the application DB with FORCE RLS, enforce/history triggers, and the 46-row declaration seed')
ON CONFLICT (version) DO NOTHING;

-- ===========================================================================
-- 第 5 节  回滚说明（本仓不提供自动 down 迁移；Flyway forward-only）
--
--   🛑 回滚本迁移【有】数据损失风险，且比 V13 严重：
--      V13 只往登记表写了几行文字；本迁移建了三张表并灌了 46 条声明。
--      若那时租户层 app_config 已有生效值（生产环境已改过配置），
--      一旦 config_slot 被删，app_config 的外键 (config_no -> config_slot) 会让
--      整张生效值表不可再写。⇒ 生产回滚前【必须先导出】app_config 与 app_config_history。
--
--   ① 若 app_config / app_config_history 为空（仅 schema 落地、尚无业务值）：
--        DROP TABLE  IF EXISTS app_config_history;
--        DROP TABLE  IF EXISTS app_config;
--        DROP FUNCTION IF EXISTS app_config_enforce_slot();
--        DROP FUNCTION IF EXISTS app_config_record_history();
--        DROP FUNCTION IF EXISTS app_config_history_append_only();
--        DROP TABLE  IF EXISTS config_slot;
--
--   ② 若已有业务值：先备份两张表，再评估"回到四个 ConfigSeed*ProfileSource 过渡态"
--      是否可接受（那意味着口径又变回"启动期解析种子文件文本"）。
--      🛑 禁止在未备份的情况下执行 ①。
--
--   ③ DELETE FROM schema_migration     WHERE version = 'V14';
--      DELETE FROM flyway_schema_history WHERE version = '14';
-- ===========================================================================
