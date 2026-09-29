-- ===========================================================================
-- V11 迁移: 密钥材料持久化 —— tenant_kek / subject_dek / subject_key_tombstone
-- ===========================================================================
--
-- 【为什么需要这个迁移】
--   架构规格书 §8.4 逐字要求：「写入用每主体 DEK 加密 PII → DEK 存 KMS（信封加密）
--   → 删除请求时销毁 DEK → 密文仍在但计算上不可恢复」。
--   dy-crypto 此前只有 InMemory 实现（InMemorySubjectKeyStore /
--   InMemoryTenantKekRegistry / InMemoryShredTombstoneStore），其类头已明确声明
--   「❌ 未接入生产 KMS …… 本轮走的是『占位』这一条」。
--
--   🛑 这意味着：仅把 dy-crypto 挂进 dy-app（B-1 的第一步）会得到一个
--      【看起来加密了、但一旦重启就永久解不开】的系统 ——
--      进程内生成的 KEK/DEK 随进程消失，而库里已经全是密文。
--      加密数据不可读，与数据丢失等价。故密钥持久化与加密挂接【必须同时完成】，
--      否则 B-1 的"收口"会把一个合规缺口换成一次数据事故。
--
-- 【🗄 落地形态 = 数据库表（本项目约定：生产真相源一律 DB 表）】
--   不引入外部 KMS 客户端库（架构文档第 6 条「明确筑底、拒绝超前」；
--   根 pom 的 ADR-14 注释把"外部配置中心作真相源"类列为本轮不做）。
--   故 KEK 以"主密钥加密后"的形态落 DB —— 即 KEK 的包裹使用
--   DY_MASTER_KEY（环境变量，与 ADR-08「机密不得入 DB 配置表」同一条纪律：
--   真正的主密钥必须走环境变量 / 密钥管理，DB 里只存它加出来的中间态）。
--   生产替换成云 KMS 时，只需替换 dy-app 里 TenantKekProvider 的实现，表结构不动。
--
-- 【三张表的分工（与 dy-crypto 的三个接口一一对应）】
--   tenant_kek            ↔ TenantKekProvider    租户级 KEK，多版本可轮换
--   subject_dek           ↔ SubjectKeyStore      每主体 DEK，含被 KEK 包裹的材料
--   subject_key_tombstone ↔ ShredTombstoneStore  销毁墓碑（删除权的证据）
--
-- 【🛑 加密保护的是什么 —— 必须说清，避免误以为"数据库泄露也没事"】
--   subject_dek 存的是 wrapped material（已被租户 KEK 加密），不是裸 DEK。
--   但要诚实登记：tenant_kek 的 KEK 本身是被 DY_MASTER_KEY 加密的，
--   而 DY_MASTER_KEY 在环境变量里 ⇒ **拿到 DB + 环境变量的攻击者可以还原全部密钥**。
--   这是"DB 表作真相源（无外部 KMS）"这一选型的固有边界，不是本迁移的疏漏。
--   它相对"完全明文落库"的收益是明确的：库备份泄漏 / 只读副本泄漏 /
--   SQL 注入读取 —— 这三种最常见的泄漏面不再直接产出健康数据明文。
--   升级到外部 KMS 后，这个边界消失。此项作为已知限制登记，不得含糊陈述。
--
-- 【回滚说明】见文件末。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) tenant_kek —— 租户级密钥加密密钥（KEK），多版本
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_kek
(
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    kek_id         VARCHAR(128) NOT NULL,
    -- 本代 KEK 的序号（1,2,3…）。轮换 = 新增一行并递增；旧行保留（历史 wrappedDek 要用它解）
    kek_seq        INT         NOT NULL CHECK (kek_seq >= 1),
    -- 算法位：与 dy-crypto AlgorithmId 的 id 逐字对齐（如 'AES-256-GCM'）
    -- 🛑 不写 DEFAULT：算法必须由写入方显式给出，默认值会让"换算法"变成静默行为
    algorithm_id   VARCHAR(32) NOT NULL,
    -- 🛑 包裹 nonce / 密文：KEK 的裸字节【从不】落库，落库的是
    --    masterKey 加密后的材料（见文件头"加密保护的是什么"）
    wrap_nonce     BYTEA       NOT NULL CHECK (octet_length(wrap_nonce) > 0),
    wrapped_bytes  BYTEA       NOT NULL CHECK (octet_length(wrapped_bytes) > 0),
    -- KEK 的 AAD 绑定串（租户 + kekId + kekSeq），落库保存以便还原时逐字重建
    -- 🛑 不存 AAD 就无法重建认证串 ⇒ 解不开；这是"认证绑定的可复现性"要求
    aad_text       TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(128),
    -- 租户 KEK 整体销毁（租户级删除权）时间；非空 ⇒ 该租户全部数据不可恢复
    destroyed_at   TIMESTAMPTZ,

    PRIMARY KEY (tenant_id, kek_id),
    -- 同一租户内序号唯一：轮换的并发保护由应用层"最大 seq + 1"承担，此处兜底
    CONSTRAINT uq_tenant_kek_seq UNIQUE (tenant_id, kek_seq)
);

COMMENT ON TABLE tenant_kek IS
    '租户级 KEK（动态加密密钥）。wrapped_bytes 由 DY_MASTER_KEY 加密；裸 KEK 从不落库。
     保留全部历史版本：KEK 轮换后，历史 wrappedDek 必须用其包裹时的那一代 KEK 才能解开。';

-- 取"当前代"（最大 seq）是每次创建 DEK 的热路径
CREATE INDEX IF NOT EXISTS idx_tenant_kek_current
    ON tenant_kek (tenant_id, kek_seq DESC);

-- ---------------------------------------------------------------------------
-- 2) subject_dek —— 每主体数据加密密钥（DEK），被租户 KEK 包裹
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subject_dek
(
    tenant_id      UUID         NOT NULL,
    -- 主体类型：与 dy-crypto SubjectRef 的 subjectType 逐字对齐（'customer' / 'therapist'）
    subject_type   VARCHAR(32)  NOT NULL,
    -- 主体标识：与 SubjectRef.subjectId 对齐
    -- 🛑 TEXT 而非 UUID：SubjectRef 只要求非空且不含 NUL，不承诺是 UUID；
    --    用 UUID 会替 dy-crypto 收紧一个它没做的承诺
    subject_id     TEXT         NOT NULL,
    dek_version    INT          NOT NULL CHECK (dek_version >= 1),
    -- 包裹本代 DEK 的那一代 KEK —— 🛑 不是"当前代"。
    -- 记错它会让我们用新 KEK 去解老 DEK，报出来却像"密钥丢了"
    kek_id         VARCHAR(128) NOT NULL,
    algorithm_id   VARCHAR(32)  NOT NULL,
    wrap_nonce     BYTEA        NOT NULL CHECK (octet_length(wrap_nonce) > 0),
    wrapped_bytes  BYTEA        NOT NULL CHECK (octet_length(wrapped_bytes) > 0),
    -- 包裹时的 AAD（AadBinding.ofDekWrap 的产出），落库以便逐字重建认证串
    aad_text       TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(128),

    PRIMARY KEY (tenant_id, subject_type, subject_id, dek_version),
    -- 软外键指向同租户的 KEK；不加 FK 约束是因为 KEK 可能被轮换清理（墓碑先于材料）
    CONSTRAINT ck_subject_dek_subject_type
        CHECK (subject_type IN ('customer', 'therapist'))
);

COMMENT ON TABLE subject_dek IS
    '每主体 DEK（数据加密密钥）的包裹材料。per-subject 粒度是 ADR-12 的硬裁定：
     租户级密钥【无法】删除租户内的单个用户（竞析 E.6），故删除权必须落在主体粒度。
     本表只存 wrapped material；裸 DEK 由 SubjectKeyStore 在用时还原、用完即弃。';

-- 解密路径：按 (主体, 版本) 精确取 —— 这是 PK，无需额外索引。
-- 枚举某主体的全部版本（备份清单 / wrappedOf(subject) 取最新）需要它：
CREATE INDEX IF NOT EXISTS idx_subject_dek_latest
    ON subject_dek (tenant_id, subject_type, subject_id, dek_version DESC);

-- ---------------------------------------------------------------------------
-- 3) subject_key_tombstone —— 销毁墓碑（crypto-shredding 的证据）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subject_key_tombstone
(
    tenant_id      UUID         NOT NULL,
    subject_type   VARCHAR(32)  NOT NULL,
    subject_id     TEXT         NOT NULL,
    -- 首销毁时间。🛑 重复销毁【不覆盖】首次时间（见 InMemoryShredTombstoneStore 的同款语义）：
    --    覆盖会让"何时依法删除"这一事实随时间漂移
    destroyed_at   TIMESTAMPTZ  NOT NULL,
    reason         VARCHAR(256) NOT NULL
        CHECK (length(btrim(reason)) > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, subject_type, subject_id),
    CONSTRAINT ck_subject_key_tombstone_subject_type
        CHECK (subject_type IN ('customer', 'therapist'))
);

COMMENT ON TABLE subject_key_tombstone IS
    '密钥销毁墓碑。作用不是"记录删除过"，而是【堵死绕行路径】：
     若只把密钥材料删掉，任何拿到备份 wrappedDek + KEK 的人仍可自行还原 DEK。
     墓碑让 SubjectKeyStore.unwrap 在还原前先拒绝 —— 删除权因此不可绕过。
     本表数据【不可删除】（等保 2.0 三级：审计类记录不可篡改、不可删除）。';

-- ---------------------------------------------------------------------------
-- 4) RLS —— 严格照抄 V1 / V3 的 fail-closed 风格（ENABLE + FORCE + 双 NULLIF）
--    未设 app.tenant_id 上下文 ⇒ 零行（唯一允许语义）
--    🛑 密钥表比业务表【更】需要 RLS：越权读到别租户的 wrappedDek 是
--       密钥材料泄漏，而不只是一条业务记录泄漏。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_kek', 'subject_dek', 'subject_key_tombstone']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        -- PG 的 CREATE POLICY 无 IF NOT EXISTS，故先 DROP（幂等必需）
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I FOR ALL '
            || 'USING      (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 5) 只追加纪律：墓碑表禁止 UPDATE / DELETE（DB 层强制，而非应用层自觉）
--    与 audit_log 的"不可篡改、不可删除"同款。用规则（RULE）实现，
--    因为触发器的 RAISE 可以被应用层"捕获后忽略"，而 RULE 直接在计划层改写语句。
-- ---------------------------------------------------------------------------
DROP RULE IF EXISTS tombstone_no_update ON subject_key_tombstone;
CREATE RULE tombstone_no_update AS
    ON UPDATE TO subject_key_tombstone DO INSTEAD NOTHING;

DROP RULE IF EXISTS tombstone_no_delete ON subject_key_tombstone;
CREATE RULE tombstone_no_delete AS
    ON DELETE TO subject_key_tombstone DO INSTEAD NOTHING;

-- subject_dek：允许插入与删除（删除正是 crypto-shredding 的核心动作），
-- 但【禁止更新】：改一行 wrapped material 等于把历史密文与密钥错配，
-- 而错误会以"认证失败"的形式出现，看起来像数据被篡改而不是被误改。
DROP RULE IF EXISTS subject_dek_no_update ON subject_dek;
CREATE RULE subject_dek_no_update AS
    ON UPDATE TO subject_dek DO INSTEAD NOTHING;

-- tenant_kek：禁止更新 wrapped/nonce/aad（同 subject_dek 的理由）；
-- 但 destroyed_at 必须可写（租户级销毁要留痕）⇒ 不用 RULE，
-- 改用列级触发器只拦"密钥材料字段"的变更。
CREATE OR REPLACE FUNCTION tenant_kek_material_is_immutable()
RETURNS TRIGGER AS $fn$
BEGIN
    IF NEW.wrapped_bytes IS DISTINCT FROM OLD.wrapped_bytes
       OR NEW.wrap_nonce IS DISTINCT FROM OLD.wrap_nonce
       OR NEW.aad_text IS DISTINCT FROM OLD.aad_text
       OR NEW.algorithm_id IS DISTINCT FROM OLD.algorithm_id
       OR NEW.kek_id IS DISTINCT FROM OLD.kek_id THEN
        RAISE EXCEPTION
            'tenant_kek 的密钥材料字段不可修改（tenant=% kek=%）。'
            '密钥轮换的正确做法是【新增一行】（kek_seq + 1），不是就地改旧行 —— '
            '就地改会让历史 wrappedDek 永久解不开。', NEW.tenant_id, NEW.kek_id;
    END IF;
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tenant_kek_material_immutable ON tenant_kek;
CREATE TRIGGER trg_tenant_kek_material_immutable
    BEFORE UPDATE ON tenant_kek
    FOR EACH ROW EXECUTE FUNCTION tenant_kek_material_is_immutable();

-- ---------------------------------------------------------------------------
-- 6) 自证块：迁移后的不变量
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t            TEXT;
    rls_on       BOOLEAN;
    rls_force    BOOLEAN;
    pol_count    INT;
    rule_count   INT;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_kek', 'subject_dek', 'subject_key_tombstone']
    LOOP
        -- (a) 三张表均已启用并强制 RLS
        SELECT relrowsecurity, relforcerowsecurity INTO rls_on, rls_force
        FROM pg_class WHERE oid = t::regclass;
        IF NOT rls_on OR NOT rls_force THEN
            RAISE EXCEPTION '自证失败(a): % 的 RLS 未强制 (enable=%, force=%)', t, rls_on, rls_force;
        END IF;

        -- (b) 隔离策略恰一条
        SELECT count(*) INTO pol_count FROM pg_policies
        WHERE tablename = t AND policyname = 'tenant_isolation';
        IF pol_count <> 1 THEN
            RAISE EXCEPTION '自证失败(b): % 的 tenant_isolation 策略数应为 1，实际 %', t, pol_count;
        END IF;

        -- (c) tenant_id 列存在（RLS 依赖它，缺列会让策略退化为"恒真"或报错）
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = t AND column_name = 'tenant_id'
        ) THEN
            RAISE EXCEPTION '自证失败(c): % 缺 tenant_id 列', t;
        END IF;
    END LOOP;

    -- (d) 墓碑的"不可改不可删"规则生效
    SELECT count(*) INTO rule_count FROM pg_rules
    WHERE tablename = 'subject_key_tombstone'
      AND rulename IN ('tombstone_no_update', 'tombstone_no_delete');
    IF rule_count <> 2 THEN
        RAISE EXCEPTION '自证失败(d): 墓碑的只追加规则数应为 2，实际 %', rule_count;
    END IF;

    -- (e) subject_dek 的不可更新规则生效
    IF NOT EXISTS (SELECT 1 FROM pg_rules
                   WHERE tablename = 'subject_dek' AND rulename = 'subject_dek_no_update') THEN
        RAISE EXCEPTION '自证失败(e): subject_dek 缺 no_update 规则';
    END IF;

    RAISE NOTICE 'V11 自证通过: 三张密钥表就位 / RLS 强制 x3 / 墓碑只追加 / DEK 不可更新';
END $$;

-- ---------------------------------------------------------------------------
-- 7) 迁移版本登记（幂等 upsert，沿用既有写法）
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V11', 'key material persistence (B-1): tenant_kek / subject_dek / subject_key_tombstone + RLS + append-only tombstone + immutable key material')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 【回滚说明】（forward-only；手工步骤）
--
--   🛑 回滚本迁移会让【全部已加密数据永久不可读】—— 密钥材料一旦删除，
--      密文就没有任何还原路径。这是设计使然，不是缺陷。
--      故正确顺序是：先解密回明文（导出 → 解密 → 建明文列 → 回写），再删表。
--
--   ① DROP TRIGGER IF EXISTS trg_tenant_kek_material_immutable ON tenant_kek;
--   ② DROP FUNCTION IF EXISTS tenant_kek_material_is_immutable();
--   ③ DROP RULE IF EXISTS subject_dek_no_update ON subject_dek;
--   ④ DROP RULE IF EXISTS tombstone_no_update ON subject_key_tombstone;
--   ⑤ DROP RULE IF EXISTS tombstone_no_delete ON subject_key_tombstone;
--   ⑥ DROP TABLE IF EXISTS subject_key_tombstone;
--   ⑦ DROP TABLE IF EXISTS subject_dek;
--   ⑧ DROP TABLE IF EXISTS tenant_kek;
--   ⑨ DELETE FROM flyway_schema_history WHERE version = '11';
--   ⑩ DELETE FROM schema_migration WHERE version = 'V11';
-- ---------------------------------------------------------------------------