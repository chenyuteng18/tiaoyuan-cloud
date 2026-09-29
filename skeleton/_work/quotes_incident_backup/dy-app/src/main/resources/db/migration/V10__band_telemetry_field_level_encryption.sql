-- ===========================================================================
-- V10 迁移: band_telemetry —— 字段级加密真正生效（B-1 收口 · ADR-12 §8.3 / §8.4）
-- ===========================================================================
--
-- 【为什么需要这个迁移】
--   ADR-12 §8.3 裁定「字段级加密用于敏感个人信息」；§8.4 裁定
--   「写入用每主体 DEK 加密 PII → DEK 存 KMS（信封加密）」；
--   §8.1 明确「心率/血氧/睡眠/血压落入医疗健康类别 → 构成敏感个人信息」。
--   dy-crypto 模块（28 测试全绿、能力完整）此前【零调用方】⇒ band_telemetry 的
--   心率 / 血氧 / 睡眠以【明文】落库。这正是「测试全绿 ≠ 可商用」的典型：
--   能力齐备但从未挂接，且没有任何测试会因为"加密没生效"而变红。
--
-- 【本次裁定：改列，而不是旁立一个密文列 —— 数据库里不留第二份明文副本】
--   曾评估的方案：新增 value_enc 密文列、保留 value_num 明文列、读取端优先取密文。
--   【否决】理由：只要 value_num 还在，就始终存在一条能写入明文的路径
--   （某段没改到的代码、一次运维手工 INSERT），而"读取端优先密文"对写侧毫无约束
--   ⇒ "加密是否真的生效"又退化成一个只能靠 grep 猜的问题。这与
--   dy-crypto/SensitiveField 类头所批判的失效模式（"新加的血氧字段忘了加密，
--   它会安静地以明文落库"）完全同构。故本迁移【删除 value_num】，
--   让"明文无处可写"成为数据库层面的硬事实。
--
-- 【✅ 为什么删除 value_num 是安全的：全仓零 SQL 语义依赖】
--   · 列为 NUMERIC(12,4)，但全仓【无】SUM/AVG/MAX/MIN、无范围谓词、无 ORDER BY；
--   · 【无任何索引】引用它（索引只引用 device_id / metric / date / hour / minute / sport_id）；
--   · 唯一引用点是 BandLedger 的 INSERT 列清单与 SELECT（rs.getBigDecimal）——
--     即"原样存取"，不需要数值语义；
--   · E2 双分支幂等键（uq_bt_daily_idempotent / uq_bt_sport_cursor）不引用本列
--     ⇒ 改列【不会】破坏幂等。
--   （核对命令：grep -rn "value_num" --include=*.java --include=*.sql .）
--
-- 【🛑 为什么清单建议的"透明列加密（MyBatis TypeHandler）"在本项目不可行】
--   清单 B-1 建议「选透明列加密（MyBatis TypeHandler 层），业务代码零改动」。
--   现场事实与本建议有两处冲突，均已在代码层核实：
--   ① 本项目【不用 MyBatis】：dy-app/src/main 零 org.apache.ibatis 引用，
--      持久化全部走 JdbcTemplate（14 个 *Ledger 仓储类）⇒ TypeHandler 挂接点不存在；
--   ② 即使存在，"列类型透明"对 NUMERIC 列也不成立：AES-GCM 信封是
--      `dy1:<alg>:<dekVer>:<nonceB64>:<ctB64>` 文本，NUMERIC 列存不下它。
--   ⇒ 本迁移把"透明"取【对 API 透明】的语义：客户端响应的字段形状与取值不变
--     （E3 的 value 仍是 number、sleep_json 仍是对象），加解密全部发生在服务端
--     （写侧 BandService 加密、读侧 BandLedger 解密）。
--
-- 【🛑 存量明文行：不伪造、不静默丢弃 —— 本迁移选择"拒绝并给出处置说明"】
--   迁移层是 SQL，拿不到每主体的 DEK（DEK 在 dy-crypto 的 SubjectKeyStore 中），
--   故【无法】就地重加密存量行。两种"方便"的处理都已被否决：
--     · 伪造一个形似密文的值 ⇒ 读取端解密必失败，把"没迁移"伪装成"数据损坏"；
--     · 直接删掉明文行 ⇒ 静默丢弃客户健康数据。
--   ⇒ 见第 2 节的自证块：存在明文行即 RAISE，表保持原状（原子失败）。
--
-- 【回滚说明】见文件末。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) 新增密文列（幂等）
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry ADD COLUMN IF NOT EXISTS value_enc TEXT;

COMMENT ON COLUMN band_telemetry.value_enc IS
    '敏感指标值的字段级加密信封（AES-256-GCM，dy-crypto FieldCipher）。
     格式恒为 dy1:<algId>:<dekVersion>:<nonceB64>:<ctB64>（CipherEnvelope.PREFIX="dy1"）。
     写入侧 = BandService.upsertTelemetry（per-subject DEK，主体 = customer）；
     读取侧 = BandLedger.mapTelemetry（按信封内的算法位/DEK 版本还原）。
     取代 V3 的 value_num NUMERIC(12,4) 明文列 —— 见 V10 文件头"改列而非旁立新列"。';

-- ---------------------------------------------------------------------------
-- 2) 存量明文行自证块（🛑 必须在改类型 / 加 CHECK / 删旧列【之前】）
--    放在此处的理由：若本块 RAISE，其后语句均不执行 ⇒ 表保持 V9 原状，
--    不会留下"加了密文列却没删明文列"的半成品 schema（Flyway 事务内原子失败）。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    plain_value_rows BIGINT := 0;
    plain_sleep_rows BIGINT := 0;
    has_value_num    BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'band_telemetry' AND column_name = 'value_num'
    ) INTO has_value_num;

    -- ① 明文标量指标（value_num）
    IF has_value_num THEN
        EXECUTE 'SELECT count(*) FROM band_telemetry WHERE value_num IS NOT NULL'
            INTO plain_value_rows;
    END IF;

    -- ② 明文睡眠分期（sleep_json，尚未迁到 TEXT 形态）
    --    已加密的行在 JSONB 里表现为字符串 "dy1:..." ⇒ ::text 后以 "dy1: 开头；
    --    对象/数组/数字形态一律判为明文。
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'band_telemetry'
          AND column_name = 'sleep_json' AND data_type = 'jsonb'
    ) THEN
        EXECUTE $q$
            SELECT count(*) FROM band_telemetry
            WHERE sleep_json IS NOT NULL
              AND left(sleep_json::text, 5) <> '"dy1:'
        $q$ INTO plain_sleep_rows;
    END IF;

    IF plain_value_rows > 0 OR plain_sleep_rows > 0 THEN
        RAISE EXCEPTION
            'band_telemetry 存在明文敏感数据（value_num % 行 / sleep_json % 行）—— 本迁移无法就地重加密：'
            '迁移层拿不到每主体的 DEK。处置方式（二选一）：'
            '① 生产：先执行一次"读取 → FieldCipher 加密 → 回写"的重加密任务（需 dy-crypto 可用）；'
            '② 开发/测试：DELETE FROM band_telemetry; 后重跑本迁移。'
            '本迁移刻意【不】伪造密文值、【也】不静默丢弃数据行。',
            plain_value_rows, plain_sleep_rows;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3) sleep_json: JSONB → TEXT（承载信封文本）
--    信封是 `dy1:...` 平文本，不是 JSON 值；留在 JSONB 会让 rs.getString 取到带引号
--    的 JSON 串（"dy1:..."）而解析失败。第 2 节已保证此刻只剩两种行：
--    sleep_json IS NULL，或已是 JSON 字符串形态的密文 ⇒ 用 #>> '{}' 取出裸文本。
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry
    ALTER COLUMN sleep_json TYPE TEXT
    USING (
        CASE
            WHEN sleep_json IS NULL THEN NULL
            -- jsonb #>> '{}' 把标量（含字符串）取为不带引号的文本
            ELSE (sleep_json #>> '{}')
        END
    );

COMMENT ON COLUMN band_telemetry.sleep_json IS
    '睡眠分期明细的字段级加密信封（同 value_enc，格式 dy1:<algId>:<dekVersion>:<nonceB64>:<ctB64>）。
     V10 由 JSONB 改为 TEXT：信封是平文本而非 JSON 值，用 JSONB 会让 ResultSet.getString
     取到带引号的 JSON 串而解析失败。字段名保留（data-dict §2.17 列名即 sleep_json）。';

-- ---------------------------------------------------------------------------
-- 4) 信封格式硬门禁（DB 层，而非应用层）—— 本迁移最重要的产出
--    CipherEnvelope.serialize() 恒产出 dy1:<alg>:<ver>:<nonce>:<ct>（PREFIX = "dy1"）。
--    故 CHECK (LIKE 'dy1:%') 保证：任何绕过加密的写入 —— 包括某段忘了改的新代码路径、
--    一次运维手工 INSERT、一次数据导入 —— 都会以 23514 被数据库【拒绝】，
--    而不是安静地写入明文。
--    🛑 与 V1 的 RLS「ENABLE + FORCE」是同一种思路：把安全不变量放在数据层，
--       而不是放在"大家记得遵守"的纪律层。
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_value_enc_envelope;
ALTER TABLE band_telemetry ADD CONSTRAINT ck_bt_value_enc_envelope
    CHECK (value_enc IS NULL OR value_enc LIKE 'dy1:%');

ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_sleep_json_envelope;
ALTER TABLE band_telemetry ADD CONSTRAINT ck_bt_sleep_json_envelope
    CHECK (sleep_json IS NULL OR sleep_json LIKE 'dy1:%');

-- ---------------------------------------------------------------------------
-- 5) 删除明文列
--    执行到这里，第 4 节的 CHECK 已经生效 ⇒ 之后写入的每一行都必须是信封形态。
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry DROP COLUMN IF EXISTS value_num;

-- ---------------------------------------------------------------------------
-- 6) 自证块：迁移后 schema 必须满足的不变量（不满足即 RAISE，整个迁移回滚）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    col_type TEXT;
    col_nullable TEXT;
    cons_count INT;
BEGIN
    -- (a) value_enc 存在
    SELECT data_type, is_nullable INTO col_type, col_nullable
    FROM information_schema.columns
    WHERE table_name = 'band_telemetry' AND column_name = 'value_enc';
    IF col_type IS NULL THEN
        RAISE EXCEPTION '自证失败(a): value_enc 列不存在';
    END IF;
    IF col_type <> 'text' THEN
        RAISE EXCEPTION '自证失败(a): value_enc 应为 text，实际 %', col_type;
    END IF;

    -- (b) value_num 已不存在（明文列必须彻底消失，而非仅"不再使用"）
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'band_telemetry' AND column_name = 'value_num'
    ) THEN
        RAISE EXCEPTION '自证失败(b): value_num 明文列仍然存在 —— 本迁移要求彻底移除';
    END IF;

    -- (c) sleep_json 已迁到 text
    SELECT data_type INTO col_type
    FROM information_schema.columns
    WHERE table_name = 'band_telemetry' AND column_name = 'sleep_json';
    IF col_type <> 'text' THEN
        RAISE EXCEPTION '自证失败(c): sleep_json 应为 text，实际 %', col_type;
    END IF;

    -- (d) 两条信封门禁 CHECK 均已生效
    SELECT count(*) INTO cons_count
    FROM pg_constraint
    WHERE conrelid = 'band_telemetry'::regclass
      AND conname IN ('ck_bt_value_enc_envelope', 'ck_bt_sleep_json_envelope');
    IF cons_count <> 2 THEN
        RAISE EXCEPTION '自证失败(d): 信封门禁 CHECK 数量应为 2，实际 %', cons_count;
    END IF;

    -- (e) 幂等键索引未被本次改列破坏（反向断言：改列不得动到幂等）
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                   WHERE tablename = 'band_telemetry' AND indexname = 'uq_bt_daily_idempotent')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
                      WHERE tablename = 'band_telemetry' AND indexname = 'uq_bt_sport_cursor') THEN
        RAISE EXCEPTION '自证失败(e): E2 双分支幂等索引缺失 —— 改列不应影响幂等键';
    END IF;

    RAISE NOTICE 'V10 自证通过: value_enc(text) 就位 / value_num 已移除 / sleep_json(text) / 信封门禁 x2 / 幂等索引完好';
END $$;

-- ---------------------------------------------------------------------------
-- 7) RLS 未被本次改列影响 —— 反向断言（改列不得削弱隔离）
--    band_telemetry 在 V3 已 ENABLE + FORCE RLS。改列若意外重建表会让行安全失效，
--    本块显式复核，避免"改列把 FORCE 丢掉"这类静默退化。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    rls_on    BOOLEAN;
    rls_force BOOLEAN;
    pol_count INT;
BEGIN
    SELECT relrowsecurity, relforcerowsecurity INTO rls_on, rls_force
    FROM pg_class WHERE oid = 'band_telemetry'::regclass;

    IF NOT rls_on OR NOT rls_force THEN
        RAISE EXCEPTION 'RLS 反向断言失败: band_telemetry relrowsecurity=% relforcerowsecurity=%'
            '（应为 t/t）—— 改列不得削弱租户隔离', rls_on, rls_force;
    END IF;

    SELECT count(*) INTO pol_count FROM pg_policies
    WHERE tablename = 'band_telemetry' AND policyname = 'tenant_isolation';
    IF pol_count <> 1 THEN
        RAISE EXCEPTION 'RLS 反向断言失败: tenant_isolation 策略数应为 1，实际 %', pol_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 8) 迁移版本登记（幂等 upsert，沿用 V1 / V2 / V3 / V9 写法）
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V10', 'band_telemetry field-level encryption (B-1): value_num→value_enc TEXT + sleep_json JSONB→TEXT + envelope-format CHECK guards; plaintext columns removed, not shadowed')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 【回滚说明】（本仓不提供自动 down 迁移；Flyway 为 forward-only。手工回滚步骤）
--
--   🛑 前置认知：回滚【无法】把密文还原为明文 —— 这一列的数据已按设计被加密。
--      回滚只能恢复 schema 形状，恢复不了可读性。这正是 crypto-shredding 的语义。
--
--   ① ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_value_enc_envelope;
--   ② ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_sleep_json_envelope;
--   ③ ALTER TABLE band_telemetry ADD COLUMN IF NOT EXISTS value_num NUMERIC(12,4);
--   ④ ALTER TABLE band_telemetry ALTER COLUMN sleep_json TYPE JSONB USING to_jsonb(sleep_json);
--   ⑤ ALTER TABLE band_telemetry DROP COLUMN IF EXISTS value_enc;
--   ⑥ 清空密文行（回滚后这些行再也解不开，留着只会让读取端报 CipherAuthenticationException）：
--        DELETE FROM band_telemetry;
--   ⑦ DELETE FROM flyway_schema_history WHERE version = '10';
--   ⑧ DELETE FROM schema_migration WHERE version = 'V10';
-- ---------------------------------------------------------------------------