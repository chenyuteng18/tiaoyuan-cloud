-- ===========================================================================
-- V12 迁移: band_telemetry 信封门禁【按 metric 分流】—— 修正 V10 过严的 CHECK
-- ===========================================================================
--
-- 【为什么需要这个迁移 —— 一个真实的、会让写入 500 的设计冲突】
--   V10 第 4 节给本表加了：
--       CHECK (value_enc IS NULL OR value_enc LIKE 'dy1:%')
--   它的含义是【该列任何非空值都必须是信封】。这是一个刻意的安全门禁（见 V10 文件头）。
--
--   但 B-1 同时在应用层引入了敏感登记表（dy-app .../band/domain/TelemetrySensitivity）：
--       敏感 6 项（hr/resting_hr/spo2/steps/workout/sleep）→ 加密落信封
--       其余 7 项（bp/temp/pressure/met/mai/respiration/exercise）→ 落【明文数字】
--
--   ⇒ 二者【不可能同时成立】：非敏感 metric 一旦上报，写入必被 DB 以 23514 拒绝。
--   实测症状：POST /api/v1/band/telemetry 上报 metric=pressure 返回 500
--   （由 BandTelemetryEncryptionTest 的"非敏感 metric 落明文"用例在真库上抓到）。
--
-- 【为什么不能直接改 V10，而必须新开 V12】
--   V10 已应用到开发库（flyway_schema_history version=10，实测确认），
--   且 Flyway 为 forward-only：已应用迁移的校验和不可变，改文件会让启动即失败。
--   故修正只能落在新迁移。这也是本项目既有的做法（V8/V9 均为对前序的对齐迁移）。
--
-- 【🛑 裁定：不放宽为"信封或任意文本"，而是改成【按 metric 分流】】
--   最省事的修法是把 CHECK 放宽成：
--       CHECK (value_enc IS NULL OR value_enc LIKE 'dy1:%' OR value_enc ~ '[0-9]')
--   【否决】。理由：这让"某段没改到的新代码路径写入明文数字"重新变成【合法且静默】——
--   而"加密是否真的生效"又退化成一个只能靠 grep 猜的问题。这正是 V10 文件头
--   逐字批判的失效模式（"新加的血氧字段忘了加密，它会安静地以明文落库"）。
--   本迁移【不放宽安全不变量】，而是把它【按 metric 精确化】：
--
--       敏感 metric 的值 → 必须是信封（与 V10 同等强度，不是放宽）
--       非敏感 metric 的值 → 必须是【十进制数字文本】（不得是信封，也不得是任意字符串）
--
--   于是两种退化【都在 DB 层被拒绝】：
--       ① 敏感字段忘了加密 → 明文数字撞不满足"必须是信封"分支 ⇒ 23514（与 V10 同）
--       ② 非敏感字段被一律加密 → 信封撞不满足"必须是数字文本"分支 ⇒ 23514（比 V10 更强）
--   ② 是 V10 做不到的：V10 允许"全部加密"，而登记的区分力（性能与合规收益的来源）
--   由本迁移第一次被数据库真正钉住。
--
-- 【🛑 两侧都用【正向列举】，不用 NOT IN —— 这是一条承重的写法选择】
--   若写成 `metric NOT IN ('hr',...) AND value_enc ~ 数字正则`，则将来新增一个 metric 取值
--   （改 V3 的 metric CHECK）而忘记更新本迁移时，新取值会【自动落入明文数字分支】
--   ⇒ 静默地不加密。正向列举两侧后，新取值【两侧都不含】⇒ 它的任何 value_enc 写入
--   都会被拒（因为两个 OR 分支都不匹配）⇒ 症状在第一次写入时立刻可见。
--   这与 TelemetrySensitivity.sensitiveFieldOf 的 fail-closed 方向完全一致：
--   "新增 metric 而不更新登记表"必须在写入时炸，而不是安静地漏。
--
-- 【⚠️ 本迁移的固有代价：metric 清单在 SQL 与 Java 两处出现】
--   两处清单必须恒等，否则会出现"Java 说敏感、DB 说非敏感"的分叉 ——
--   症状是某类 metric 的写入每次都被 23514 拒绝，而错误信息里看不出是清单漂移。
--   故配套 TelemetrySensitivityMigrationGateTest 做 Java ↔ SQL 交叉断言
--   （与 RlsCoverageGateTest 的三方交叉断言同一思路：把"两份清单必须相等"
--   从人的纪律改成构建期的纪律）。🛑 改本文件的 metric 清单，必须同步改
--   TelemetrySensitivity.BY_METRIC / NOT_REGISTERED，否则该门禁立刻红。
--
-- 【sleep_json 的处理：保持 V10 的口径不变】
--   sleep_json 的敏感性【不由 metric 决定】（TelemetrySensitivity 类头逐字写明：
--   契约把结构化明细的承载位固定为 sleep_json 且语义是睡眠分期 ⇒ 只要它非空就必须
--   加密，无论当行 metric 是什么）。故它继续是"非空即信封"，本迁移不动它。
--   🛑 这解释了为什么 value_enc 要分流而 sleep_json 不分流：前者的敏感性由 metric 定，
--   后者的敏感性由"列非空"定。两者判据不同，不得合并。
--
-- 【回滚说明】见文件末。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) value_enc 信封门禁 → 按 metric 分流（幂等：先 DROP 再 ADD）
-- ---------------------------------------------------------------------------
ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_value_enc_envelope;
ALTER TABLE band_telemetry ADD CONSTRAINT ck_bt_value_enc_envelope CHECK (
    value_enc IS NULL
    -- 分支 A：敏感 metric ⇒ 必须是 dy1 信封（与 V10 同等强度）
    OR (metric IN ('hr', 'resting_hr', 'spo2', 'steps', 'workout', 'sleep')
        AND value_enc LIKE 'dy1:%')
    -- 分支 B：非敏感 metric ⇒ 必须是十进制数字文本。
    --   正则刻意【只允许】BigDecimal.toPlainString() 会产出的形态（无正号、无指数、无千分位）——
    --   收紧而非放宽：写入侧 valueEnc 的来源正是 toPlainString()，
    --   任何超出该形态的值都说明写入路径已被改动，应在库层拒绝而不是接受。
    OR (metric IN ('bp', 'temp', 'pressure', 'met', 'mai', 'respiration', 'exercise')
        AND value_enc ~ '^-?[0-9]+(\.[0-9]+)?$')
);

COMMENT ON COLUMN band_telemetry.value_enc IS
    '指标值的字段级加密信封（AES-256-GCM，dy-crypto FieldCipher）。
     格式恒为 dy1:<algId>:<dekVersion>:<nonceB64>:<ctB64>（CipherEnvelope.PREFIX="dy1"）。
     V10 由 value_num NUMERIC(12,4) 明文列改建而来；V12 把该列的门禁由"一律信封"
     精确化为"按 metric 分流"（敏感 = 信封 / 非敏感 = 十进制数字文本）——
     见 V12 文件头。AAD 含 fieldName，故同一密文挪到别的 metric 列会认证失败。
     写入侧 = BandLedger.insertTelemetry（per-subject DEK，主体 = customer）；
     读取侧 = BandLedger.mapTelemetry（按信封内的算法位/DEK 版本还原）。';

-- ---------------------------------------------------------------------------
-- 2) 自证块：迁移后必须成立的不变量（不满足即 RAISE，整个迁移回滚）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    def       TEXT;
    validated BOOLEAN;
    bad_rows  BIGINT := 0;
    slp_def   TEXT;
    cons_cnt  INT;
BEGIN
    -- (a) value_enc 门禁已就位，且【有效】（convalidated）——
    --     NOT VALID 状态的 CHECK 不校验存量行，会让"迁移通过"变成假象。
    SELECT pg_get_constraintdef(oid), convalidated INTO def, validated
    FROM pg_constraint
    WHERE conrelid = 'band_telemetry'::regclass AND conname = 'ck_bt_value_enc_envelope';
    IF def IS NULL THEN
        RAISE EXCEPTION '自证失败(a): ck_bt_value_enc_envelope 不存在';
    END IF;
    IF NOT validated THEN
        RAISE EXCEPTION '自证失败(a): ck_bt_value_enc_envelope 处于 NOT VALID 状态 —— '
            '存量行未被校验，"迁移通过"是假象';
    END IF;

    -- (b) 分流的两侧都在定义里出现 —— 只断言"约束存在"会让"被改回一律信封"静默通过
    IF def NOT LIKE '%hr%' THEN
        RAISE EXCEPTION '自证失败(b): 门禁定义里找不到敏感侧 metric 清单。实际定义: %', def;
    END IF;
    IF def NOT LIKE '%dy1:%' THEN
        RAISE EXCEPTION '自证失败(b): 门禁定义里找不到信封前缀判据 dy1: —— '
            '敏感侧将不再要求信封。实际定义: %', def;
    END IF;
    IF def NOT LIKE '%pressure%' OR def NOT LIKE '%[0-9]%' THEN
        RAISE EXCEPTION '自证失败(b): 门禁定义里找不到非敏感侧清单或数字正则 —— '
            '非敏感侧会失去"必须是明文数字"的钉住。实际定义: %', def;
    END IF;

    -- (c) 反向断言：现存行必须【全部】满足新门禁。
    --     CHECK 已生效时这天然成立，但显式重算一遍，是为了区分两种失败：
    --     "约束没生效"与"数据本就不合法"——只看 ADD CONSTRAINT 成功会混为一谈。
    SELECT count(*) INTO bad_rows FROM band_telemetry
    WHERE value_enc IS NOT NULL
      AND NOT (
            (metric IN ('hr', 'resting_hr', 'spo2', 'steps', 'workout', 'sleep')
             AND value_enc LIKE 'dy1:%')
         OR (metric IN ('bp', 'temp', 'pressure', 'met', 'mai', 'respiration', 'exercise')
             AND value_enc ~ '^-?[0-9]+(\.[0-9]+)?$')
      );
    IF bad_rows > 0 THEN
        RAISE EXCEPTION '自证失败(c): 有 % 行不满足按 metric 分流的信封门禁 —— '
            '先人工核查这些行的 metric 与 value_enc 形态（它们可能是 V10 期间被拒绝写入前的残留）',
            bad_rows;
    END IF;

    -- (d) sleep_json 的门禁未被本次改动波及（它不分流，见文件头）
    SELECT pg_get_constraintdef(oid) INTO slp_def
    FROM pg_constraint
    WHERE conrelid = 'band_telemetry'::regclass AND conname = 'ck_bt_sleep_json_envelope';
    IF slp_def IS NULL OR slp_def NOT LIKE '%dy1:%' THEN
        RAISE EXCEPTION '自证失败(d): ck_bt_sleep_json_envelope 缺失或不再要求信封 —— '
            '睡眠分期明细的加密会被静默放开。实际定义: %', slp_def;
    END IF;

    -- (e) 两条门禁都在（数量断言，防"改一条删一条"）
    SELECT count(*) INTO cons_cnt FROM pg_constraint
    WHERE conrelid = 'band_telemetry'::regclass
      AND conname IN ('ck_bt_value_enc_envelope', 'ck_bt_sleep_json_envelope');
    IF cons_cnt <> 2 THEN
        RAISE EXCEPTION '自证失败(e): 信封门禁 CHECK 数量应为 2，实际 %', cons_cnt;
    END IF;

    -- (f) 幂等键索引未被本次改约束破坏（反向断言）
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                   WHERE tablename = 'band_telemetry' AND indexname = 'uq_bt_daily_idempotent')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
                      WHERE tablename = 'band_telemetry' AND indexname = 'uq_bt_sport_cursor') THEN
        RAISE EXCEPTION '自证失败(f): E2 双分支幂等索引缺失 —— 改约束不应影响幂等键';
    END IF;

    RAISE NOTICE 'V12 自证通过: value_enc 门禁已按 metric 分流（敏感=信封 % 项 / 非敏感=明文数字 % 项）/ '
        '门禁有效(validated) / 存量行全部合法 / sleep_json 门禁未受影响 / 幂等索引完好',
        (SELECT count(*) FROM (VALUES ('hr'),('resting_hr'),('spo2'),('steps'),('workout'),('sleep')) t),
        7;
END $$;

-- ---------------------------------------------------------------------------
-- 3) RLS 未被本次改约束影响 —— 反向断言（改约束不得削弱隔离）
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
            '（应为 t/t）—— 改约束不得削弱租户隔离', rls_on, rls_force;
    END IF;

    SELECT count(*) INTO pol_count FROM pg_policies
    WHERE tablename = 'band_telemetry' AND policyname = 'tenant_isolation';
    IF pol_count <> 1 THEN
        RAISE EXCEPTION 'RLS 反向断言失败: tenant_isolation 策略数应为 1，实际 %', pol_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4) 迁移版本登记（幂等 upsert，沿用前序写法）
--
--   🛑 description 必须 ≤ 256 字符：schema_migration.description 是 VARCHAR(256)
--      （V1 建列）。本迁移首版描述 309 字符，真库以 22001
--      （「对于可变字符类型来说，值太长了(256)」）拒绝了整条语句 ——
--      而 Flyway 是事务性 DDL，整个 V12 因此回滚（库保持 V11 状态，这是好事）。
--      教训已成门禁：MigrationDescriptionWidthGateTest 断言全部迁移的
--      version/description 长度不越界，避免这个坑被重复踩。
-- ---------------------------------------------------------------------------
INSERT INTO schema_migration (version, description)
VALUES ('V12', 'band_telemetry value_enc guard split by metric: sensitive = dy1 envelope, non-sensitive = decimal text; replaces V10 all-envelope CHECK that rejected non-sensitive metric writes with 23514')
ON CONFLICT (version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 【回滚说明】（本仓不提供自动 down 迁移；Flyway 为 forward-only。手工回滚步骤）
--
--   🛑 前置认知：回滚只能把门禁退回 V10 的"一律信封"，而【无法】让已落库的
--      非敏感明文数字变成合法值 —— 回滚后这类行会让任何 UPDATE 本表的语句失败。
--      这正是"把安全不变量放在数据层"的代价：回滚不是无痛的。
--
--   ① 注意：回滚前必须先把非敏感 metric 的明文行处置掉（加密或清空），否则本表被锁在
--      一个自相矛盾的状态里（数据不满足约束、而约束不能被违反）：
--        SELECT metric, count(*) FROM band_telemetry
--         WHERE value_enc IS NOT NULL AND value_enc NOT LIKE 'dy1:%' GROUP BY metric;
--   ② ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS ck_bt_value_enc_envelope;
--   ③ ALTER TABLE band_telemetry ADD CONSTRAINT ck_bt_value_enc_envelope
--          CHECK (value_enc IS NULL OR value_enc LIKE 'dy1:%');
--   ④ DELETE FROM flyway_schema_history WHERE version = '12';
--   ⑤ DELETE FROM schema_migration WHERE version = 'V12';
-- ---------------------------------------------------------------------------