-- ============================================================================
-- V9 · 手环同步批次词表对齐（A-5 裁定的落地件）
--
-- 来由：E1（POST /band/sync-batches）与 V5 的 band_sync_log 是**两套词表**：
--
--   契约 §4.1「同步批次契约」（E1 的入参/出参）：
--     state          四态英文 syncing / synced / sync_failed / no_data_today
--     trigger        五值英文 on_show_cold / on_show_hot / checkin / daily_report / manual
--     fail_reason_class  7 值英文（bt_off / … / probe_out_of_window）
--     batch_no       幂等键（UUID，与 Idempotency-Key 同值）
--
--   V5 建的 band_sync_log（PRD §4.6「同步**尝试日志**」语义）：
--     trigger_source 中文 CHECK（'onShow' / '到店核销' / '每日填报' / '手动'）
--     result         两态（'success' / 'failed'）
--     fail_stage     7 值英文（该列 V5 已按契约落库，与契约一致）
--     **没有 batch_no 列**
--
-- 后果（A-5 的"为什么阻塞"）：
--   ① E1 的四态（syncing / no_data_today）**落不进** result 两态；
--   ② E1 的英文 trigger **落不进** trigger_source 的中文 CHECK（硬映射报 23514）；
--   ③ batch_no 在表中无列 ⇒ 幂等去重只能在内存承接 ⇒ **服务重启即丢**。
--   故当前 E1 只做"契约校验 + 内存幂等受理"，**写路径不落库**。
--
-- 裁定（2026-09-26 · 本迁移为落地件）：**以契约为准**（与卡点清单 A-5「建议」逐字一致）。
--   落地方式 = **保留两态/中文物理列不动，另补契约侧映射列**（不迁移既有 CHECK）：
--     · 新增 contract_state / contract_trigger / batch_no 三列；
--     · 三列各带 CHECK，取值集为契约英文枚举逐字；
--     · E1 写路径改为**同时**写两组列（物理侧旧列 + 契约侧新列），
--       E6 读路径按契约侧列直出，不再做两态→四态的近似映射。
--
-- 🛑 为什么不"迁移 CHECK 至英文"（卡点清单里给出的另一支）
--   旧列 trigger_source / result 是 PRD §4.6「同步尝试日志」的语义载体 ——
--   那是**另一件事**（每次 onShow 触发即记的尝试流水），它的中文两态是自洽的。
--   把它的 CHECK 改英文 = 用一次迁移把两个语义不同的东西**合并**成一份，
--   而合并后就再也分不出"这条是批次上报"还是"这条是尝试流水"。
--   本项目反复登记的失效模式就是"同名不同源 / 同表两语义" —— 故**不合并**，
--   而是在同一行里同时登记两侧事实，由 CHECK 各自钉住自己的值域。
--
-- 🛑 为什么三列都**可空**（不是 NOT NULL）
--   既有 band_sync_log 行（V5/V6/V7/V8 期间的真库底数据与测试探针）没有契约侧取值，
--   且「同步尝试日志」这条产线**不产生**契约侧语汇 ⇒ 强行 NOT NULL 会让既有写入路径
--   全部失败。可空 + CHECK（NULL OR IN (...)）是唯一能让两侧共存的表达。
--   ⚠️ 由此产生一条必须登记的形态：**contract_state IS NULL 的行 = 非 E1 批次上报行**
--      （即"尝试流水"）。E6 读路径据此区分——它只读 contract_state IS NOT NULL 的行。
--
-- 幂等写法（与前序迁移同口径）：ADD COLUMN IF NOT EXISTS / DROP CONSTRAINT IF EXISTS
--   + ADD CONSTRAINT（均系可重复执行语句）。
-- ============================================================================

-- ============================================================================
-- 一、contract_state —— 契约 §4.1 四态（E1 的 state / E6 的出参）
--
-- 🛑 语义边界：
--   NULL           = 本行不是 E1 批次上报（是「同步尝试日志」产线写的行）
--   'syncing'      = 同步中（客户端已发起、尚未终态）
--   'synced'       = 同步成功（此时 last_success_date 必有值）
--   'sync_failed'  = 同步失败（此时 fail_reason_class + next_action 必有值）
--   'no_data_today'= 今日无数据（**不是**失败：手环未佩戴等技术性缺失的一类）
--   'no_data_today' 与 'sync_failed' 是**两件事**，不得互相回落 ——
--   把"今日无数据"写成"同步失败"会让一次正常的技术性缺失变成一条故障记录。
-- ============================================================================
ALTER TABLE band_sync_log ADD COLUMN IF NOT EXISTS contract_state VARCHAR(24);

ALTER TABLE band_sync_log DROP CONSTRAINT IF EXISTS band_sync_log_contract_state_values;
ALTER TABLE band_sync_log ADD CONSTRAINT band_sync_log_contract_state_values CHECK (
    contract_state IS NULL OR contract_state IN (
        'syncing', 'synced', 'sync_failed', 'no_data_today')
);

-- ============================================================================
-- 二、contract_trigger —— 契约 §4.1 五值触发源（E1 的 trigger）
--
-- 🛑 与旧列 trigger_source 的关系：**语义不同，不是语言差异**。
--   trigger_source（中文四值）= PRD §4.6 的"尝试日志触发源"（含 '到店核销' 这类业务动作）
--   contract_trigger（英文五值）= 契约 §4.1 的"批次上报触发源"（含 on_show_cold/on_show_hot
--                                这类客户端生命周期事件）
--   两者**取值集大小都不同**（4 vs 5），故绝不是"同一个量的两种写法"——
--   这正是 A-5 说"硬映射会报 23514"的根因，也是本迁移选择**并列登记**而非改写的原因。
-- ============================================================================
ALTER TABLE band_sync_log ADD COLUMN IF NOT EXISTS contract_trigger VARCHAR(24);

ALTER TABLE band_sync_log DROP CONSTRAINT IF EXISTS band_sync_log_contract_trigger_values;
ALTER TABLE band_sync_log ADD CONSTRAINT band_sync_log_contract_trigger_values CHECK (
    contract_trigger IS NULL OR contract_trigger IN (
        'on_show_cold', 'on_show_hot', 'checkin', 'daily_report', 'manual')
);

-- ============================================================================
-- 三、batch_no —— 契约幂等键（UUID，与 HTTP Idempotency-Key 同值）
--
-- 🛑 它补上的是"服务重启即丢"那个缺口：E1 的幂等去重此前只在内存 Map 里
--   （24h TTL）。有了本列，幂等可以在库层承载（同 tenant 下 batch_no 唯一）。
--
-- 🛑 唯一索引范围：tenant_id + batch_no（**不是**全局唯一）。
--   幂等键是"同一租户内同一次上报"的标识；跨租户撞同一个 UUID 是合法的
--   （两个租户的客户端各自生成 UUID，概率上不该撞，但"撞了"不该让一方被拒）。
--   WHERE batch_no IS NOT NULL 使既有行（无 batch_no）不参与唯一性。
-- ============================================================================
ALTER TABLE band_sync_log ADD COLUMN IF NOT EXISTS batch_no UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_log_batch_no
    ON band_sync_log (tenant_id, batch_no)
    WHERE batch_no IS NOT NULL;

-- ============================================================================
-- 四、last_success_date —— 契约 state=synced 时【必填】的那个日期
--
-- 🛑 这一列是"E1 落库后跑测试才暴露"的缺口，不是预先想到的 —— 值得记下成因：
--   E1 此前不落库，故 E6 读路径拿到的永远是"探索日志行"（无 last_success_date 语义）；
--   V9 让 E1 真落库后，E6 读回的**批次行** state=synced，而 SyncBatchRow 的构造器
--   逐字实现契约描述「state=synced 时 last_success_date 必填」⇒ 抛出 1001 ⇒ E6 返回 400。
--   即：**"补了写路径"会让"读路径此前从未被触发的校验"第一次真正运行** ——
--   这正是"不落库"长期掩盖的那条链路缺口。
--
-- 🛑 可空性：必须可空。三态下它本就没有值（syncing 未完成 / sync_failed 未成功 /
--   no_data_today 无数据），且既有探索日志行也不适用。NOT NULL 会让这些合法形态写不进去。
--   「synced ⇒ 必填」这条约束由**领域构造器**承担（fail-closed），不在库层——
--   因为它是"条件必填"，写成 CHECK 需要跨列引用而语义上仍属领域规则。
-- ============================================================================
ALTER TABLE band_sync_log ADD COLUMN IF NOT EXISTS last_success_date DATE;

-- ============================================================================
-- 五、自证块（迁移期断言：既有数据必须仍全部合法）
--
-- 与 V5~V8 的自证同口径：把"我以为的"变成"库确认的"。
-- ============================================================================
DO $$
DECLARE
    bad_state    INT;
    bad_trigger  INT;
    null_contract INT;
    total_rows   INT;
BEGIN
    -- ① 新增列的取值域（既有行应全为 null —— 本迁移前这两列不存在）
    SELECT count(*) INTO bad_state
      FROM band_sync_log
     WHERE contract_state IS NOT NULL
       AND contract_state NOT IN ('syncing','synced','sync_failed','no_data_today');
    IF bad_state > 0 THEN
        RAISE EXCEPTION 'V9 自证失败：band_sync_log 存在 % 行 contract_state 不在四态内', bad_state;
    END IF;

    SELECT count(*) INTO bad_trigger
      FROM band_sync_log
     WHERE contract_trigger IS NOT NULL
       AND contract_trigger NOT IN ('on_show_cold','on_show_hot','checkin','daily_report','manual');
    IF bad_trigger > 0 THEN
        RAISE EXCEPTION 'V9 自证失败：band_sync_log 存在 % 行 contract_trigger 不在五值内', bad_trigger;
    END IF;

    -- ② 影响面如实登记：既有行应全部是"非 E1 批次行"（contract_state IS NULL）
    SELECT count(*) INTO total_rows FROM band_sync_log;
    SELECT count(*) INTO null_contract FROM band_sync_log WHERE contract_state IS NULL;
    RAISE NOTICE 'V9 自证通过：contract_state/trigger 取值域 0 违规；'
                 '当前 band_sync_log 共 % 行，其中 contract_state IS NULL（= 非 E1 批次行）= %',
                 total_rows, null_contract;
END $$;

-- ============================================================================
-- 六、回滚说明
--
--   ① DROP INDEX uq_sync_log_batch_no；
--   ② ALTER TABLE band_sync_log DROP CONSTRAINT band_sync_log_contract_trigger_values；
--      ALTER TABLE band_sync_log DROP CONSTRAINT band_sync_log_contract_state_values；
--   ③ ALTER TABLE band_sync_log DROP COLUMN contract_trigger；
--      ALTER TABLE band_sync_log DROP COLUMN contract_state；
--      ALTER TABLE band_sync_log DROP COLUMN batch_no；
--      ALTER TABLE band_sync_log DROP COLUMN last_success_date；
--   ⚠️ 回滚会丢失 E1 的**落库批次记录**与**库层幂等保证**（退回内存 24h TTL）。
--      旧列 trigger_source / result 与既有行不受影响（本迁移只做加法）。
-- ============================================================================