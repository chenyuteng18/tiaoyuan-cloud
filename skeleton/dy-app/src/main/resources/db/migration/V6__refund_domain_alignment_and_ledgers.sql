-- ============================================================================
-- V6 · 退款域（契约域 G）字段补齐与留痕账本
--
-- 来由：V5 已建 refund / retention / case_archive 三表，但按 PRD P0-19 逐条核对后
--       发现三类**字段级缺口**（编码前输入已冻结，属补齐而非新增能力）：
--         ① refund 缺 requested_at_source —— P0-19 C1-2 明文要求的「来源标注」三取值
--            与 requested_at_source_ref（客户自证须附凭据引用字段）。
--         ② 「客户原话 append-only、不可编辑（更正须追加）」在 V5 无处承载 ——
--            若把原话做进 refund 的一个可 UPDATE 列，"不可编辑"就只剩注释里的承诺。
--            故建独立账本 refund_statement（更正 = 追加 + supersedes 指针，不改旧行）。
--         ③ 「回执三态留痕、三态都落库、不可删除」在 V5 无任何表 ——
--            P0-19 逐字要求「未授权」是一个**独立留痕记录**（推送事件根本没发生），
--            若系统只在"已推送"之后才记到达/未到达，未授权就永远查无此事，
--            而 P1-10 的覆盖率分母 = 已推送 + 未授权 + 推送失败 → 分母缺一块、
--            覆盖率系统性偏高且偏高得看不出来。
--
-- 🛑 本迁移**不动**任何 V5 已冻结的 CHECK 字面（含 refund.entry 的 'A 门店代录'）。
--    契约 RefundCreateRequest.entry 的枚举字面是 'A门店代录'（无空格），与库内
--    'A 门店代录'（有空格）不一致 —— 该差异**不在库侧静默改名**，而是由领域枚举
--    RefundEntry 显式持两套字面（contractCode = 出站权威 / dbCode = 持久化权威）
--    并登记进契约差异清单，待 owner 裁定后统一。理由：库侧改名会让"契约与库
--    不一致"这件事从可追溯的登记项变成一次无声的历史改写。
--
-- 🔴 本链全部接口：客户端与调理师端一律 403（PRD §2.2 + §2.4 X-1/X-2/X-3）。
-- ============================================================================

-- ============================================================================
-- 一、refund 补两列（P0-19 C1-2）
-- ============================================================================

ALTER TABLE refund ADD COLUMN IF NOT EXISTS requested_at_source VARCHAR(32);

ALTER TABLE refund ADD COLUMN IF NOT EXISTS requested_at_source_ref VARCHAR(512);

COMMENT ON COLUMN refund.requested_at_source IS
    'requested_at 的来源标注：客户自证 / 调理师转交 / 经络师受理（P0-19 C1-2）。'
    '与 requested_at 成对：无来源标注的 requested_at 视为不可核实，不得进 24h 计时。';

COMMENT ON COLUMN refund.requested_at_source_ref IS
    '来源凭据引用（P0-19 C1-2）：客户自证时必附（截图 / 通话记录的引用标识）。'
    '经络师受理 / 调理师转交时指向转交代办实体。';

-- CHECK 幂等写法：PostgreSQL 的 ADD CONSTRAINT 无 IF NOT EXISTS，
-- 故先 DROP IF EXISTS 再 ADD（重复执行不报错）。
ALTER TABLE refund DROP CONSTRAINT IF EXISTS refund_requested_at_source_check;
ALTER TABLE refund ADD CONSTRAINT refund_requested_at_source_check
    CHECK (requested_at_source IS NULL OR requested_at_source IN ('客户自证', '调理师转交', '经络师受理'));

-- 🛑 三字段拆分纪律的库层堵口：requested_at_claimed（客户主张）**永不进 24h 计时**。
--    它若被写进 requested_at，"时间戳可以随手改"就成了我方自证；
--    而只留 requested_at 又会让"门店未记录、客户却能自证更早"逃掉责任。
--    故两者互斥可空、语义在列注释中钉死（计时逻辑见 RefundRecordingPolicy）。
COMMENT ON COLUMN refund.requested_at_claimed IS
    '客户主张的时间，仅留存、**永不进 24h 计时、不进任何超时判定**（P0-19 C1-2）。'
    '不得作为 requested_at 的替代；两者必须分别写入，不得合并。';

CREATE INDEX IF NOT EXISTS idx_refund_source ON refund (tenant_id, requested_at_source);

-- ============================================================================
-- 二、refund_statement —— 客户原话账本（append-only）
--   P0-19：「代录人、代录时间、客户原话（customer_statement）全程留痕、不可删除；
--           已提交原话不可编辑（如确需更正，追加更正记录而非覆盖）」
-- ============================================================================

CREATE TABLE IF NOT EXISTS refund_statement
(
    statement_id           UUID        PRIMARY KEY,
    tenant_id              UUID        NOT NULL REFERENCES tenant (id),
    refund_id              UUID        NOT NULL REFERENCES refund (refund_id),
    -- 客户原话本体（逐字，不得摘改）
    statement_text         TEXT        NOT NULL,
    -- 原话的取得方式：与 requested_at_source 同源，但独立登记
    --（同一工单可先有调理师转交原话、后有经络师受理转述）
    statement_source       VARCHAR(32) NOT NULL CHECK (statement_source IN (
                               '客户原话', '调理师转交', '经络师受理转述')),
    -- 🛑 append-only 的机械保证：更正不改旧行，而是新行声明"我取代了谁"。
    --    反向指针（旧行指向新行）会要求一次 UPDATE，那正是"不可编辑"被破的口子。
    supersedes_statement_id UUID       REFERENCES refund_statement (statement_id),
    recorded_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             VARCHAR(128)
);

COMMENT ON TABLE refund_statement IS
    '客户原话账本（append-only · PRD P0-19）。本表**只应有 INSERT 与 SELECT**：'
    '任何 UPDATE / DELETE 路径都属违规 —— 更正一律追加新行并以 supersedes_statement_id '
    '反向声明被取代者。若确需撤回某条，追加一条 statement_text 为撤回说明的更正行，'
    '而不是删除原行（中山中院 2026-04 判词：凭证须可提交，见附录 C.7.5）。';

CREATE INDEX IF NOT EXISTS idx_statement_refund   ON refund_statement (tenant_id, refund_id);
CREATE INDEX IF NOT EXISTS idx_statement_recorded  ON refund_statement (tenant_id, recorded_at);

ALTER TABLE refund_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund_statement FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON refund_statement;
CREATE POLICY tenant_isolation ON refund_statement
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 三、refund_receipt —— 回执三态留痕（P0-19「发送留痕进证据链 · 三态全覆盖」）
--   取值 = 已推送 / 未授权（转线下） / 推送失败（与契约 RefundReceiptData.receipt_state 逐字一致）
-- ============================================================================

CREATE TABLE IF NOT EXISTS refund_receipt
(
    receipt_id      UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenant (id),
    refund_id       UUID        NOT NULL REFERENCES refund (refund_id),
    -- 与契约 RefundReceiptData.receipt_state 枚举逐字一致（含全角括号）
    receipt_state   VARCHAR(32) NOT NULL CHECK (receipt_state IN (
                        '已推送', '未授权（转线下）', '推送失败')),
    -- 回执通道：P0-19 裁定 = 小程序订阅消息；电话 / 当面为兜底
    channel         VARCHAR(32) NOT NULL CHECK (channel IN ('订阅消息', '电话', '当面')),
    -- 订阅消息模板 ID（「回执独占一个模板 ID」是化解额度稀释的方式）
    template_id     VARCHAR(64),
    -- 🛑 判定时刻：**三态都落** ——「未授权」是"推送事件根本没发生"，
    --    由"尝试发送之前、判定无额度"时落库，故它没有 pushed_at，但有 decided_at。
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 仅「已推送」有值（库层 CHECK 钉死该等价关系）
    pushed_at       TIMESTAMPTZ,
    failure_reason  VARCHAR(256),
    operator_id     UUID        REFERENCES staff (staff_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(128),
    -- 🛑 「已推送 ⟺ 有 pushed_at」不得被写坏：只记"已推送"而不记时间，
    --    证据链上就无从证明它真的发过；反之记了时间却标"未授权"，
    --    会让覆盖率分母把一次未发生的推送算成已达标。
    CONSTRAINT refund_receipt_pushed_at_iff_pushed CHECK (
        (receipt_state =  '已推送' AND pushed_at IS NOT NULL)
     OR (receipt_state <> '已推送' AND pushed_at IS NULL))
);

COMMENT ON TABLE refund_receipt IS
    '回执发送留痕（三态 · append-only · PRD P0-19）。三态=已推送 / 未授权（转线下） / 推送失败；'
    '随工单落库、**不可删除**。⚠️「未授权」是独立留痕记录、不是"发送失败的到达状态" —— '
    '未授权 = 推送事件根本没发生（尝试发送之前判定无额度即落一条）。'
    'P1-10 的覆盖率分母 = 已推送 + 未授权 + 推送失败，缺一态即分母缺一块。'
    '本表只应有 INSERT 与 SELECT；任何 UPDATE / DELETE 路径属违规。';

CREATE INDEX IF NOT EXISTS idx_receipt_refund  ON refund_receipt (tenant_id, refund_id);
CREATE INDEX IF NOT EXISTS idx_receipt_state   ON refund_receipt (tenant_id, receipt_state);
CREATE INDEX IF NOT EXISTS idx_receipt_decided ON refund_receipt (tenant_id, decided_at);

ALTER TABLE refund_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund_receipt FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON refund_receipt;
CREATE POLICY tenant_isolation ON refund_receipt
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ============================================================================
-- 四、refund_offline_notice —— 转线下兜底留痕（P0-19 兜底子项）
--   「自动转门店电话 / 当面告知 + 系统留痕『已转线下告知 + 操作人 + 时间』」
--   🛑 与 refund_receipt 分表：语义不同（一为推送事件、一为人工告知动作），
--     且覆盖率分母只算推送三态 —— 合并会让"线下告知"被算成一次"已推送"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS refund_offline_notice
(
    notice_id    UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    refund_id    UUID        NOT NULL REFERENCES refund (refund_id),
    channel      VARCHAR(32) NOT NULL CHECK (channel IN ('电话', '当面')),
    noticed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    operator_id  UUID        REFERENCES staff (staff_id),
    note         VARCHAR(512),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(128)
);

COMMENT ON TABLE refund_offline_notice IS
    '转线下告知留痕（append-only · P0-19 兜底子项）：「不得因订阅失败而漏发回执」的执行凭证。'
    '与 refund_receipt 分表 —— 线下告知是人工动作、不是推送事件，不得计入推送覆盖率分母。';

CREATE INDEX IF NOT EXISTS idx_offline_refund  ON refund_offline_notice (tenant_id, refund_id);
CREATE INDEX IF NOT EXISTS idx_offline_noticed ON refund_offline_notice (tenant_id, noticed_at);

ALTER TABLE refund_offline_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund_offline_notice FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON refund_offline_notice;
CREATE POLICY tenant_isolation ON refund_offline_notice
    FOR ALL
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);