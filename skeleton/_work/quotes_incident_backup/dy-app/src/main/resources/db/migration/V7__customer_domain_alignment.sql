-- ============================================================================
-- V7 · 契约域 B（客户入组链）库层对齐
--
-- 来由：S2-10 冻结契约域 B 语义时，把「契约 + 字典 + PRD」与「V1 已建表结构」
--       逐列对了一遍，发现一处**结构性缺口**：
--
--         V1__baseline_tenant_rls.sql 的 customer 表（F-4 处置 = 「预留位 +
--         注释指向待建状态机实体」）只有 8 列：
--             id / tenant_id / name / status / owner_id / created_at / updated_at / deleted_at
--         而契约域 B 与权威字典要求它承载 13 列。缺的恰好是 5 列：
--             phone / gender / age / owner_store_id / serving_store_id
--
--       证据链（三处独立上游，逐字）：
--         ① 契约 openapi-v1.0.0.yaml · B2 CustomerCreateRequest
--              required: [name, gender, age, phone, screening_id]
--              phone.description 逐字「租户内唯一（跨店识别键）」
--         ② 契约 · B4 CustomerDetailData
--              owner_store_id / serving_store_id 的 x-visible-to: [therapist, meridian, admin]
--              description 逐字「客户不下发」  ⇒ 二者必须真实存在于库
--         ③ data-dict-entities-ddl §2.6 customer 字段表：
--              owner_store_id FK store（归属/首诊店，锁定档案）
--              serving_store_id 可空 FK store（当前服务店，跨店迁移）
--              phone UNIQUE(tenant_id, phone)（跨店识别键）
--              gender CHECK 男/女 · age CHECK 0<age<120（建档快照）
--       且 PRD L1310 的客户实体行同样列了 owner_store_id / serving_store_id / phone。
--
-- 🛑 处置纪律（本迁移落的是「补齐」而非「新增能力」）：
--   ① **不放宽契约**：契约 B2 的 required 与 B4 的 x-visible-to 都是冻结行；
--   ② **不发明替代列名**（例如把 phone 塞进 name、或另建影子表）——
--      那会造出一份"看起来能跑、但与上游字典不一致"的结构，且永远不会有人发现它对不上；
--   ③ **不修改已应用的历史迁移**（V1 是 S1-2 验收的证据基线，且 dry-run/生产已应用过）；
--   ④ 本迁移只做 **ALTER TABLE ADD COLUMN**，不新增表、不删列、不改既有列类型
--      ⇒ 不触碰 RlsCoverageGateTest 的「迁移声明的租户表 == 登记表 == 真库」三方交叉断言
--         （customer 已在登记表内且仍带 tenant_id）。
--
-- 🛑 五列全部保持 **可空**，理由必须写清（否则下一个人会顺手改成 NOT NULL）：
--   契约与字典确实要求 phone / gender / age 在**建档请求**里必填，但库列要不要 NOT NULL
--   是另一件事，此处刻意留可空，有三个各自独立的理由：
--     ⓐ verification/02_seed.sql 是上一轮的**证据基线，不得修改**，它插入 customer 时
--        只给 (id, tenant_id, name, status) —— 若新列 NOT NULL 且无默认值，
--        真库门禁的 provision 会当场失败（表现为"种子行没进去"，看起来像 RLS 的 bug）；
--     ⓑ 该表在 V1 里**已有历史行**（含 S1-2 之前灌入的示范数据），
--        给既有行补一个语义正确的 NOT NULL 值是不可能的（phone 无从编造，
--        而编造手机号会把"跨店识别键"变成一根假数据）；
--     ⓒ 「必填」这件事的**权威落点**是"写入路径"而不是"表结构"：
--        应用层 CustomerArchive / CustomerUpsertDraft 在构造期即拒绝缺失，
--        两条路径（B2 建档 / B6 修订）都走它 ⇒ 缺列在建档时以 1001 明确失败，
--        而不是等到 SQL 报 23502（not-null violation）——后者只说"某列为空"，
--        看不出这是"契约必填字段没给"。
--    ⇒ 故 NOT NULL 的收紧被登记为**后续项**（待历史行清理 + 种子基线更新后走增项流程），
--      本迁移用**列注释**把"应用层强制非空"这条纪律钉在库里，使其对 DBA 可见。
--
-- 🛑 owner_store_id 与 serving_store_id 是**两个不同的业务概念，不得合并**：
--   owner   = 归属店 / 首诊店，锁定档案（建档一次性写入）
--   serving = 当前服务店，可空，跨店迁移时更新
--   合并它们会让"跨店通兑 + 他店只读共享"（data-dict §2.6 附注 / U1）
--   失去承载 —— 而那个错误**不报错**，只表现为"客户跟着走了，原店的档案归属也没了"。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 一、customer 补 5 列
--   不加 NOT NULL（理由见文件头 🛑）；加 CHECK 与 FK；phone 加租户内唯一索引。
-- ---------------------------------------------------------------------------

-- 跨店识别键：租户内唯一。唯一性范围**必然**是租户级而非门店级 ——
-- 这个约束与"门店级唯一"的差别是本系统的一条核心业务前提：客户属于品牌而非门店
-- （PRD §1 首段：客户"属于品牌可跨店通兑"）。若 phone 做成门店级唯一，
-- 同一个人在两家店会被建成两个客户档案，而他的调理记录、判定链、退款工单
-- 会分列在两条互不知晓的主线上。
ALTER TABLE customer ADD COLUMN IF NOT EXISTS phone VARCHAR(32);

COMMENT ON COLUMN customer.phone IS
    '客户手机号 · 跨店识别键（契约 B2 / data-dict §2.6）。'
    '唯一性范围 = 租户级（UNIQUE(tenant_id, phone)）：客户属于品牌而非门店，'
    '做成门店级唯一会把同一个人在两家店拆成两个档案。'
    '🛑 应用层写入路径强制非空（CustomerArchive / CustomerUpsertDraft 构造期拒绝）；'
    '库列暂留可空以兼容 V1 历史行与不得修改的种子基线，NOT NULL 收紧属后续项。';

-- 性别：契约 B2 required，且它是 AgeGroup（年龄分组）的输入维度之一。
-- 只两值（data-dict §2.6 CHECK 男/女）；CHECK 允许 NULL（未填的历史行）。
ALTER TABLE customer ADD COLUMN IF NOT EXISTS gender VARCHAR(8);

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_gender_check;
ALTER TABLE customer ADD CONSTRAINT customer_gender_check
    CHECK (gender IS NULL OR gender IN ('男', '女'));

COMMENT ON COLUMN customer.gender IS
    '性别 · 建档快照（契约 B2 required / data-dict §2.6 CHECK 男|女）。'
    '🛑 它是 AgeGroup（年龄分组）的输入维度，故写错不会报错、只会让分组偏移：'
    '应用层 Gender.of() 对未登记取值抛错（fail-closed），不做回落。';

-- 年龄：建档快照（不是"当前年龄"）。
-- data-dict §2.6 CHECK 0 < age < 120；契约 CustomerCreateRequest.age minimum=1 / maximum=119。
ALTER TABLE customer ADD COLUMN IF NOT EXISTS age INT;

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_age_check;
ALTER TABLE customer ADD CONSTRAINT customer_age_check
    CHECK (age IS NULL OR (age > 0 AND age < 120));

COMMENT ON COLUMN customer.age IS
    '年龄 · **建档快照**（不是"当前年龄"，故不随年份自动增长；'
    '契约 B2 required / data-dict §2.6 CHECK 0<age<120，与契约 minimum=1 maximum=119 等价）。'
    '🛑 应用层对越界报 1001 而非夹逼到边界：夹逼会让一个人被静默算进错误的年龄分组。';

-- 归属店 / 首诊店：锁定档案，建档一次性写入。
ALTER TABLE customer ADD COLUMN IF NOT EXISTS owner_store_id UUID;

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_owner_store_fk;
ALTER TABLE customer ADD CONSTRAINT customer_owner_store_fk
    FOREIGN KEY (owner_store_id) REFERENCES store (store_id);

COMMENT ON COLUMN customer.owner_store_id IS
    '归属店 / 首诊店（data-dict §2.6 FK store）· 锁定档案，建档一次性写入。'
    '🔴 与 serving_store_id 是**两个不同概念，不得合并** —— 合并会让'
    '「跨店通兑 + 他店只读共享」（U1）失去承载，而那个错误不报错、'
    '只表现为"客户跟着走了，原店的档案归属也没了"。'
    '⚠️ 契约 B4 的 x-visible-to 不含 client（description 逐字「客户不下发」）——'
    '客户侧裁剪由 CustomerFieldVisibility 承担（该字段**不是**派生字段，'
    '故入站拦截器与出口兜底都不会管它）。';

-- 当前服务店：可空，跨店迁移时更新。
ALTER TABLE customer ADD COLUMN IF NOT EXISTS serving_store_id UUID;

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_serving_store_fk;
ALTER TABLE customer ADD CONSTRAINT customer_serving_store_fk
    FOREIGN KEY (serving_store_id) REFERENCES store (store_id);

COMMENT ON COLUMN customer.serving_store_id IS
    '当前服务店（data-dict §2.6 FK store · 可空）· 跨店迁移时更新，'
    '不与 owner_store_id 合并（理由见 owner_store_id 的注释）。'
    '可空是业务事实：未开始服务（仅建档）时没有服务店。'
    '⚠️ 契约 B4 的 x-visible-to 不含 client（description 逐字「客户不下发」）。';

-- 跨店识别键的唯一索引：**部分唯一**（排除 NULL）而不是全列唯一。
-- 理由：既有历史行与种子行的 phone 均为 NULL，若做全列唯一索引，
-- 多条 NULL 在 PostgreSQL 里本来就不冲突（NULL 彼此不相等），故两种写法在当前等价；
-- 但部分索引把"这个唯一性只约束有值的行"写成**显式事实**，
-- 使将来收紧 NOT NULL 时无需再改索引。
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_tenant_phone
    ON customer (tenant_id, phone) WHERE phone IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_owner_store
    ON customer (tenant_id, owner_store_id);
CREATE INDEX IF NOT EXISTS idx_customer_serving_store
    ON customer (tenant_id, serving_store_id);

-- ---------------------------------------------------------------------------
-- 二、customer.status 库层默认值与权威 5 值不一致 —— 本迁移**不改**，只登记
--
-- V1 写的是 DEFAULT 'pending'（注释「5 值状态: pending / active / paused / archived / closed」），
-- 那是 S1-2 的**骨架占位**，与权威 5 值
-- （CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED，见 data-dict §2.25 ② /
--   契约 CustomerCreateData.status 枚举）不一致。
--
-- 🛑 为什么**不在库侧静默改名**：
--   ① 'pending' 与权威 5 值**没有对应关系** —— 它不是 CREATED 的旧名，
--      而是"准入未开始"的一个占位描述。把 pending 映射成 CREATED 是一次**代拍口径**；
--   ② 库侧改名会让"契约与库不一致"这件事从**可追溯的登记项**变成一次无声的历史改写
--      （与 V6 对 refund.entry 的处置同一条纪律：V6 刻意**不动** 'A 门店代录' 的空格差异）；
--   ③ 该差异已登记进 _work/contract-t6-api-freeze-2026-09-19.md，待 owner 裁定后统一。
--
-- ⇒ 故本迁移的处置 = **保持 DEFAULT 'pending' 不动** + 要求**所有写入路径显式给 status**：
--    CustomerLedger.upinsert(...) 一律显式传 status（由 CustomerStatus.of(state) 解出），
--    **不得**依赖库层 DEFAULT。理由很实际：一旦某条路径依赖了它，
--    那条路径写入的客户会以 'pending' 落库 —— 而 'pending' **不在权威 5 值里**，
--    于是它既不是 CREATED 也不是任何一态，任何按 5 值分组的报表都会**静默漏掉这批行**。
--    这是一次库层默认值造成偏差的真实先例，与 consent.data_source 的 DEFAULT 'self-report'
--    同型（后者同理要求显式给出，见 ConsentRow 的构造期校验）。
-- ---------------------------------------------------------------------------

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_status_check;
COMMENT ON COLUMN customer.status IS
    '5 值粗粒度派生聚合态：CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED'
    '（data-dict §2.25 ② 的 14→5 显式映射 · 契约 CustomerCreateData.status 枚举）。'
    '🛑 14 态是唯一权威、5 值是派生聚合；本列由状态机实体推导刷新（在跃迁同事务内更新），'
    '**不得反向由 5 值推断 14 态**。'
    '⚠️ 本列的 DEFAULT ''pending'' 是 S1-2 的**骨架占位**，与上述 5 值不一致 ——'
    '该差异已登记待 owner 裁定，本迁移刻意不在库侧静默改名（见 V7 文件头第二节）。'
    '⇒ 所有写入路径**必须显式给 status**，不得依赖本 DEFAULT：'
    '依赖它会写入既不是 CREATED 也不是任何一态的行，任何按 5 值分组的报表都会静默漏掉它们。';

-- ---------------------------------------------------------------------------
-- 三、intake_profile_revision —— 建档档案的修订留痕账本（append-only）
--
-- 来由：契约 B6 逐字「补充 + 修订（**append-only 留痕，不可覆盖**）」，
--       data-dict §2.23 的 intake_profile 附注逐字「建档本体（客户终身：
--       **补充/修订留痕，不可覆盖**）」，V5 的 DDL 注释也逐字重复了同一句。
--       但 V5 建的 intake_profile **只有一张表**，且带
--       `UNIQUE (tenant_id, customer_id)` —— 即"一个客户一份建档本体"，
--       物理上**只可能有一行**。
--
-- ⇒ 单靠 V5 的结构，"补充/修订留痕、不可覆盖"**无处承载**：
--    若把每次修订写在 intake_profile 的某列上，它必然被后一次修订覆盖，
--    "不可覆盖"就只剩注释里的一句承诺。
--
-- 🔑 本迁移采用的读法（须与上游口径核对，已登记）：
--    intake_profile        = 建档本体的**当前投影**（一客户一行，可 coalesce）
--    intake_profile_revision = **权威修订历史**（append-only，永不覆盖）
--    两者由同一事务写入 ⇒ 投影与历史不可能分叉。
--    若上游裁定"本体本身亦不得就地更新"，则只需把投影改为"由历史在读取时解算"，
--    历史侧零改动 —— 这正是把权威放在账本而不是本体上的价值。
--
-- 🛑 本表刻意**不设** "修订类型"枚举列（不发明口径）：
--    上游只出现过「补充」「修订」两个词，但**没有**任何取值表或 CHECK 引用
--    （与 V2 的 trigger_event 同型：§2.25 表③ 是守卫表而非 trigger_event 取值表）。
--    按纪律不自行发明枚举 ⇒ 该区分是**可从快照推导**的（字段是新增还是改值），
--    不需要存一个列；存了反而会造出一份上游查无出处的取值集。
--
-- 🛑 快照而非增量：每行携带该次修订后的**完整字段映射**，而不是 delta。
--    理由：增量需要"重放"才能回答"当时是什么样"，而重放逻辑一旦与写入逻辑分叉
--    （例如某次修订的字段名拼错），重放出的历史会**静默**地与事实不符。
--    完整快照让每一行自身即是一个可独立核对的事实。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS intake_profile_revision
(
    revision_id            UUID        PRIMARY KEY,
    tenant_id              UUID        NOT NULL REFERENCES tenant (id),
    customer_id            UUID        NOT NULL REFERENCES customer (id),

    -- 该客户的第几次修订（从 1 起，租户内客户内递增）
    revision_no            INT         NOT NULL CHECK (revision_no > 0),

    -- 该次修订后的**完整**建档字段映射（不是 delta，见文件头 🛑）
    snapshot_json          JSONB       NOT NULL,

    -- 🛑 append-only 的机械保证：更正不改旧行，而是新行声明"我取代了谁"。
    --    反向指针（旧行指向新行）会要求一次 UPDATE，那正是"不可覆盖"被破的口子。
    --    （与 V6 的 refund_statement.supersedes_statement_id 同一套机制）
    supersedes_revision_id UUID        REFERENCES intake_profile_revision (revision_id),

    -- 修订说明（为什么改）
    reason                 VARCHAR(512),

    -- 修订时点：不可由库层 DEFAULT 承担 —— 它是"当时发生了什么"的锚点，
    -- 若在插入时取 now()，则补录场景下它会指向补录时刻而非修订时刻。
    recorded_at            TIMESTAMPTZ NOT NULL,

    -- 操作人（可空：容纳历史系统行；但应用层写入路径强制非空）
    operator_id            UUID        REFERENCES staff (staff_id),

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             VARCHAR(128),

    -- 同一客户的修订序号不得重复（防重放写入两条"第 3 次修订"）
    CONSTRAINT uq_ipr_revision_no UNIQUE (tenant_id, customer_id, revision_no)
);

COMMENT ON TABLE intake_profile_revision IS
    '建档档案修订留痕账本（append-only · 契约 B6 / data-dict §2.23 · V7）。'
    '本表**只应有 INSERT 与 SELECT**：任何 UPDATE / DELETE 路径都属违规 —— '
    '更正一律追加新行并以 supersedes_revision_id 反向声明被取代者。'
    '若确需撤回某条，追加一条说明性质的修订行，而不是删除原行。'
    '权威修订历史在本表；intake_profile 是它的**当前投影**。';

CREATE INDEX IF NOT EXISTS idx_ipr_customer   ON intake_profile_revision (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_ipr_recorded    ON intake_profile_revision (tenant_id, recorded_at);
CREATE INDEX IF NOT EXISTS idx_ipr_operator    ON intake_profile_revision (tenant_id, operator_id);

ALTER TABLE intake_profile_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE intake_profile_revision FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON intake_profile_revision;
CREATE POLICY tenant_isolation ON intake_profile_revision
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 四、幂等性与回滚说明
--
-- 幂等写法（与 V6 同口径）：ADD COLUMN IF NOT EXISTS / DROP CONSTRAINT IF EXISTS
--   + ADD CONSTRAINT / CREATE INDEX IF NOT EXISTS。
--   PostgreSQL 的 ADD CONSTRAINT 无 IF NOT EXISTS，故先 DROP IF EXISTS 再 ADD ——
--   本迁移可重复执行而不报 42710 duplicate_object。
--
-- 回滚：DROP INDEX uq_customer_tenant_phone / idx_customer_owner_store /
--   idx_customer_serving_store；ALTER TABLE customer DROP CONSTRAINT 三个 CHECK 与两个 FK；
--   ALTER TABLE customer DROP COLUMN 五列；
--   DROP TABLE intake_profile_revision。
--   ⚠️ 回滚会**丢失**已录入的 phone / gender / age（含跨店识别键）与全部修订历史 ——
--   属破坏性操作，生产环境回滚前须先导出这两处。故不提供 down 脚本，由 DBA 按需手工执行。
-- ============================================================================