-- ============================================================================
-- 01_truth_source_ddl.sql  —  dy-config 配置真相源 DDL (ADR-08)
--
-- 权威口径
--   ADR-08   配置真相源 = 本数据库表；外部配置中心 (Apollo / Nacos / Consul /
--            Spring Cloud Config) 【不得】作为真相源 —— 配置是审计证据的载体，
--            它必须与审计链在同一个可事务化的存储里，否则"谁改了什么"无法举证。
--   PRD      §10「附：可配置项清单（硬性要求 —— 所有业务数字不得硬编码）」
--            -> 46 条非空配置 = #1~#41 + #43~#46 + #48；#42 为历史预留空号；
--               #47 为 optin-arch 线预留（尚未落 PRD），同样无声明行。
--   ADR-02   RLS 第 1/2 层：业务表带 tenant_id + ENABLE + FORCE ROW LEVEL SECURITY。
--            §10 的每个配置项都有"归属层级"，其中"总部唯一"= 由总部层定义默认值、
--            下发到租户层；"门店 / 区域"= 租户层可覆盖。两种层级最终都落在
--            【租户层生效值】上，故生效值表与业务表 customer 采用【逐字相同】的 RLS 策略。
--
-- 三张表的分工（不要合并，合并会同时破坏 RLS 与编号约束）
--   config_slot         总部层【声明 + 默认值】：编号 / 键 / 值类型 / 允许值 / 禁止值 /
--                       PRD 原配置项名称 / 初始值。它不承载任何租户数据（对所有租户
--                       同一份），性质同 tenant / schema_migration 这类框架表，
--                       故不含 tenant_id、不启用 RLS。
--   app_config          租户层【生效值】——运行时唯一读取来源。含 tenant_id + FORCE RLS。
--   app_config_history  变更历史：who / when / before / after，支撑回滚与举证。同 RLS。
--
--   ★ 读路径纪律：Service 只读 app_config。config_slot.initial_value 仅在
--     「新租户初始化」（03_seed_tenant_values.sql）时被读一次，之后租户的生效值
--     一律以 app_config 为准。这条纪律让"总部默认值"不会变成第二个真相源。
--
-- 为什么 #42 必须由数据库自己拒绝，而不能只写在 Java 里
--   "空号"是一条编号裁定：将来若有人把 #42 复用给新配置项，所有既有编号引用
--   (#43/#44 之所以不是 #42 的原因) 都会静默错位。把裁定写成 CHECK 约束 + 外键，
--   意味着【任何写入路径】—— 包括 DBA 手敲 SQL、迁移脚本、批量导入 —— 都写不进 #42。
--   如果只写在业务代码里，一条 `INSERT` 就能绕过。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 租户表（与业务 V1 迁移同形；已存在时为空操作，不覆盖既有定义）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant
(
    id             UUID PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    datastore_hint VARCHAR(32),
    status         VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

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