-- ============================================================================
-- V23 · 登录凭证表 auth_credential（契约 A1 `POST /auth/login` 的落库前提）
--
-- ────────────────────────────────────────────────────────────────────────────
-- 一、缺口是什么
--
--   契约 A1 早已冻结（openapi-v1.0.0.yaml:315，请求 {account, credential,
--   client_end}，响应 LoginData {token, expires_in, role, client_end}），
--   但全库**没有任何一张凭证表**：
--     · staff 表（V5）刻意无凭证列 —— 且 §2.4 裁定「门店客服无系统账号，不建行」；
--     · customer 表（V1）无 openid / 凭证字段；
--     · E2E 全部走"测试内手签 token"（各测试类私有 sign()），生产登录闭环缺位。
--   ⇒ 后果：三端登录页无真实后端可用 —— 商用发布前最后一块"必须自己长出来"的地基。
--
-- ────────────────────────────────────────────────────────────────────────────
-- 二、为什么凭证表【不启 RLS】（结构性理由，登记于 RlsCoverageGateTest.NON_TENANT_TABLES）
--
--   登录发生在**租户上下文建立之前**（token 还没签发，RLS 策略
--   `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`
--   在空上下文下恒假 ⇒ 登录查询按 account 找行必然 0 行）。
--   这与 audit_log 的豁免同构：**豁免是结构性的，不是疏忽** ——
--   audit_log 因全局单链豁免，auth_credential 因"先于上下文存在"豁免。
--
--   🛑 豁免 RLS ≠ 豁免纪律。本表的安全边界（三道）：
--     ① **credential_hash 永不回显**：登录服务只读比对，仓储层 SELECT 列表
--        显式排除任何明文形态；哈希 = PBKDF2-HMAC-SHA256（JDK 原生，
--        210_000 迭代 / 16B 随机盐，与 JwtVerifier "不引第三方依赖"同纪律）。
--     ② **account 全局唯一**（uq_auth_credential_account）：account → 行 →
--        tenant_id 的解算链是登录的**唯一**租户定位途径；
--        行内 tenant_id NOT NULL —— 一行凭证从属于恰好一个租户。
--     ③ **写入路径唯一**：凭证只经 V23 种子 / 应用内凭证管理服务写入，
--        不存在任何"按租户批量读"的业务读路径（除登录按 account 定点读一行）。
--   🛑 与 audit_log 豁免同一纪律：**豁免是针对 RLS 覆盖的，不是针对数据敏感度的** ——
--      本表不得被任何面向租户/客户的查询 join 暴露。
--
-- ────────────────────────────────────────────────────────────────────────────
-- 三、role 取值口径（与契约 LoginData.role enum 逐字一致）
--
--   contract/openapi-v1.0.0.yaml LoginData.role:
--     enum: [client, therapist, meridian, manager, area, hq]
--   token 角色码与 VisibilityRole.ADMIN.tokenRoles()（manager/area/hq → admin 组）
--   的映射关系由 dy-security 既有体系承担，本表只存 token 原值。
--   🛑 SUPER_ADMIN 不在契约 enum 内 ⇒ 不得入库（ fail-closed CHECK 拒绝）。
--
-- ────────────────────────────────────────────────────────────────────────────
-- 四、幂等口径（与整条迁移链一致）
--
--   CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS /
--   DO 块守卫用 pg_catalog 查询后条件执行（不用 CREATE POLICY，本表无策略）。
--   连跑 2 次幂等由 rls-isolation-gate 的 99_b12_run.sh 既有验收覆盖。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_credential
(
    credential_id   UUID         PRIMARY KEY,
    -- account 全局唯一（跨租户）：登录解算链 account → 行 → tenant_id 的前提。
    -- 🛑 全局唯一不是"租户内唯一"——若租户内唯一，登录第一步就无从定位租户。
    account         VARCHAR(128) NOT NULL,
    -- PBKDF2-HMAC-SHA256 格式串: 'pbkdf2-sha256$<iterations>$<salt_b64>$<hash_b64>'
    -- 🛑 永不存明文；格式由 PasswordHasher 唯一产出与解析。
    credential_hash VARCHAR(256) NOT NULL,
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    -- token 角色码，与契约 LoginData.role enum 逐字一致（见第 三 节）
    role            VARCHAR(16)  NOT NULL CHECK (role IN
                        ('client', 'therapist', 'meridian', 'manager', 'area', 'hq')),
    -- 客户匿名身份 / 员工弱关联：ref_kind 声明 ref_id 指向的实体域。
    -- 🛑 不建 FK：staff 表「客服不建行」裁定 + customer 的匿名访客可先于建档登录，
    --    强 FK 会把登录资格错误地绑定到"必须已有业务行"上。
    ref_kind        VARCHAR(16)  CHECK (ref_kind IN ('staff', 'customer')),
    ref_id          UUID,
    store_id        UUID REFERENCES store (store_id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active'
                                 CHECK (status IN ('active', 'disabled')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(128),
    -- ref_kind 与 ref_id 必须成对（要么都给，要么都空 —— 匿名客户凭证 ref_kind='customer' 可带 ref_id 亦可空）
    CONSTRAINT ck_auth_credential_ref_pair
        CHECK ((ref_kind IS NULL) = (ref_id IS NULL))
);

-- 唯一索引（IF NOT EXISTS 幂等）
CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_credential_account ON auth_credential (account);
CREATE INDEX IF NOT EXISTS idx_auth_credential_tenant ON auth_credential (tenant_id);
CREATE INDEX IF NOT EXISTS idx_auth_credential_ref ON auth_credential (ref_kind, ref_id);

-- ---------------------------------------------------------------------------
-- 自证守卫：表必须以"无 RLS 策略"的形态存在。
--   若有人日后误给它加 ENABLE/FORCE RLS + tenant_isolation 策略，
--   登录查询在空上下文下立刻查不到行 ⇒ 登录全量 401 ——
--   而这个守卫让错误在**迁移期**就暴露，而不是在商用首日。
--   （与 RlsCoverageGateTest.NON_TENANT_TABLES 的登记互为两侧证据：静态侧 +
--     真库侧都断言"本表不在 RLS 覆盖内"。）
-- ---------------------------------------------------------------------------
DO $v23_guard$
DECLARE
    policy_count integer;
    rls_enabled  boolean;
BEGIN
    SELECT count(*) INTO policy_count
      FROM pg_catalog.pg_policies
     WHERE schemaname = 'public' AND tablename = 'auth_credential';
    SELECT c.relrowsecurity INTO rls_enabled
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'auth_credential';

    IF policy_count <> 0 THEN
        RAISE EXCEPTION 'V23 守卫: auth_credential 不得携带任何 RLS 策略（登录先于租户上下文，加策略即全量 401）。当前策略数: %', policy_count;
    END IF;
    IF rls_enabled IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'V23 守卫: auth_credential 不得 ENABLE ROW LEVEL SECURITY（理由同上，见迁移第二节）';
    END IF;
END
$v23_guard$;

-- ---------------------------------------------------------------------------
-- schema_migration 登记（本仓纪律：每个迁移文件自带登记行；
--   🛑 description 列 VARCHAR(256)，先量后写 —— 本行 180 字符，V22 教训）
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V23', 'auth_credential: contract A1 login ledger. account globally unique, PBKDF2 hash, no RLS by design (login precedes tenant context, see migration sec.2); guard asserts zero policies.')
ON CONFLICT (version) DO NOTHING;

DO $v23_guard2$
DECLARE
    v_n integer;
BEGIN
    SELECT count(*) INTO v_n FROM schema_migration WHERE version = 'V23';
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V23 自证失败: schema_migration 里 V23 登记行数 = %（期望恰 1）', v_n;
    END IF;
END
$v23_guard2$;
