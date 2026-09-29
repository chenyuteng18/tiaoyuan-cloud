package com.diaoyuanyun.dy.app.band.repository;

import com.diaoyuanyun.dy.app.band.domain.SyncBatchRow;
import com.diaoyuanyun.dy.app.band.domain.TelemetryRow;
import com.diaoyuanyun.dy.app.band.domain.TelemetrySensitivity;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.field.FieldCipher;
import com.diaoyuanyun.dy.crypto.field.SensitiveField;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 手环数据域（契约域 E）的 JDBC 仓储 —— {@code band_telemetry} 的写读（E2/E3）、
 * {@code band_sync_log} 的写读（E1/E6）。
 *
 * <h2>RLS 是隔离的唯一防线（不写 WHERE tenant_id）</h2>
 * 与既有各仓储同款：隔离全由 V3/V5 的 {@code FORCE RLS} 承担。
 *
 * <h2>🛑 V10（B-1）起：本类是"明文 ↔ 密文"的转换边界</h2>
 * ADR-12 §8.1 把心率/血氧/睡眠定性为 PIPL 敏感个人信息，§8.3 裁定字段级加密。
 * 加密挂接点选在<b>本仓储</b>而不是服务层，理由有两条：
 * <ol>
 *   <li><b>「透明」的唯一可落地含义</b>：业务代码（BandService / TelemetryRow /
 *       控制器响应）全程只见明文，加解密只在此处发生。清单建议的
 *       "MyBatis TypeHandler 透明列加密"在本项目不可用（不用 MyBatis；
 *       且 NUMERIC 列存不下文本信封，见 V10 文件头），但"对业务透明"这个目标不变。</li>
 *   <li><b>防止"某一条写路径漏了加密"</b>：若把加密放在服务层，任何绕过服务层
 *       直接调 {@link #insertTelemetry} 的代码都会写出明文。放在仓储入口，
 *       使"进入本方法"与"加密"成为同一件事。</li>
 * </ol>
 * <p>{@code value_enc} / {@code sleep_json} 两列的库里形态恒为密文信封
 * （由 V10 的 {@code CHECK (... LIKE 'dy1:%')} 在 DB 层强制）；本 record 见到的
 * 始终是明文。{@code sport_id} / {@code metric} / {@code date} 等幂等键成员
 * <b>刻意不加密</b> —— 它们是查询键，加密会让唯一索引失去作用。
 *
 * <h2>🛑 E2 双分支幂等键（契约 §4.3）</h2>
 * 按日型 12 条 → (device_id, metric, date, hour, minute)；游标型 1 条 →
 * (device_id, 'sport', sport_id)。由 V3 两个表达式唯一索引兜底：
 * {@code uq_bt_daily_idempotent}（COALESCE(hour,-1) 处理日聚合 NULL）与
 * {@code uq_bt_sport_cursor}（WHERE sport_id IS NOT NULL）。
 *
 * <h2>🛑 E1 的 schema 分叉（V9 起以契约为准并列登记，不静默改表）</h2>
 * 契约 E1 四态（{@code syncing/synced/sync_failed/no_data_today}）与契约五值触发源
 * vs {@code band_sync_log} 旧列的 {@code result} 两态（{@code success/failed}）与
 * {@code trigger_source} 中文四值。<b>两套词表不是同一件事</b>（取值集大小都不同），
 * 故 V9 迁移选择<b>并列登记</b>而非改写旧 CHECK：
 * 新增 {@code contract_state} / {@code contract_trigger} / {@code batch_no} 三列
 * （各含契约值域 CHECK），E1 写路径同时写两侧、E6 读契约侧。
 * 映射见 {@link #insertSyncLog} / {@link #triggerToLegacy} / {@link #stateToLegacyResult}。
 */
@Repository
public class BandLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /**
     * 字段级加密门面（B-1）。🛑 它<b>不为 null</b>，且构造器要求它 ——
     * 这是"加密真的生效"的装配层保证：拿不到 cipher 就启动失败，
     * 而不是静默走明文路径（那种静默正是 B-1 卡点存在的原因）。
     */
    private final FieldCipher cipher;

    public BandLedger(DataSource dataSource, FieldCipher cipher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        if (cipher == null) {
            throw new IllegalArgumentException(
                    "FieldCipher 缺失 —— 拒绝以『无加密』状态构造手环仓储。"
                            + "band_telemetry 承载的是 PIPL 敏感个人信息（心率/血氧/睡眠），"
                            + "无 cipher 可用时必须启动失败，而不是明文落库。");
        }
        this.cipher = cipher;
        CIPHER = cipher;
    }

    // ------------------------------------------------------------------
    // E2 遥测 upsert（band_telemetry，双分支幂等键）
    // ------------------------------------------------------------------

    /**
     * E2 落库 SQL —— 🛑 <b>幂等由库层原子承担（{@code ON CONFLICT DO NOTHING}），不由"先查后写"承担</b>。
     *
     * <h2>🛑🛑 2026-09-26 修复：此处原为裸 INSERT，幂等在【服务层】用 check-then-act 做</h2>
     * 原实现是 {@code BandService} 先调 {@code telemetryIdempotentHit()} 查一次、再调
     * {@code insertTelemetry()} 写一次。这在<b>并发</b>下有一个确定的空窗：
     * <pre>
     *   T1: EXISTS(...) → false
     *   T2: EXISTS(...) → false          ← 两个请求都判"未存在"
     *   T1: INSERT → OK
     *   T2: INSERT → 撞 uq_bt_daily_idempotent ⇒ 23505
     * </pre>
     * 后果<b>不是</b>"多写一行"（V3 的唯一索引挡住了），而是<b>客户端收到 500</b>：
     * 一次本该幂等重放、返回 200 的重复上报，变成了一个服务端错误。
     * 更糟的是它的触发概率随<b>多实例部署</b>而升高（不同实例的访存无法互相看见），
     * 而 B-2 这条卡点关注的正是多实例场景 —— 即"多实例下 E2 会间歇性 500"。
     *
     * <p>修法：把判重交给<b>唯一索引本身</b>（{@code ON CONFLICT DO NOTHING} 是原子判定）。
     * 与 {@link #insertSyncLog} 同一条纪律，唯一区别是<b>冲突后的对外语义</b>：
     * <ul>
     *   <li>E1（{@code batch_no}）：契约声明 409，故冲突 ⇒ 服务层报 409；</li>
     *   <li>E2：契约 <b>未声明</b> 409（responses 只有 200/409-VERSION_CONFLICT/422），
     *       且描述是"幂等 upsert（已存在 → 幂等重放，不覆盖）"⇒ 冲突 ⇒ 仍 200，
     *       并返回<b>已存在那一行</b>的主键（见 {@link #findExistingTelemetryId}）。</li>
     * </ul>
     *
     * <h2>为什么 {@code ON CONFLICT} 带【无目标】形式</h2>
     * 本表有<b>两个</b>部分唯一索引（V3）：{@code uq_bt_daily_idempotent}（日型，含
     * {@code COALESCE(hour,-1)} 表达式）与 {@code uq_bt_sport_cursor}（游标型）。
     * 一次 INSERT 只可能命中其中<b>一个</b>（两者的 {@code WHERE} 谓词互斥：
     * 前者 {@code sport_id IS NULL}、后者 {@code sport_id IS NOT NULL}），
     * 但<b>哪个</b>取决于入参，故写不出单一 conflict target。
     * 用无目标形式让 PG 对"任何"唯一约束冲突都 DO NOTHING —— 这是唯一能同时覆盖两分支的写法。
     *
     * <p>已用真库验证（{@code psql} 手工建同构临时表 + 两个同构部分唯一索引，
     * 两条 INSERT 的第二条 {@code INSERT 0 0}、{@code count(*)=2}）。
     *
     * <p>⚠️ <b>无目标形式的覆盖面具名登记（不假装它只覆盖幂等索引）</b>：它会吞掉本表上
     * <b>任何</b>唯一约束冲突。当前本表的唯一约束全集 =
     * ① 主键 {@code band_telemetry_pkey}（{@code telemetry_id}，由 {@code UUID.randomUUID()}
     * 生成 ⇒ 实际不可能冲突）；② 上述两个部分唯一索引。
     * 故"被吞掉的冲突"只可能是幂等键冲突 —— 这是期望行为。
     * 若将来为本表新增唯一约束，<b>必须回到此处重新评估</b>：新约束的冲突会被静默忽略。
     *
     * <p>🛑 <b>为什么不用 {@code ON CONFLICT ... DO UPDATE}</b>：契约 E2 描述是
     * "已存在 → 幂等重放（<b>不覆盖</b>）"。{@code DO UPDATE} 会把重复上报变成一次覆盖写，
     * 那就等于"后到的数据覆盖先到的"，契约明确否掉。
     */
    private static final String INSERT_TELEMETRY_SQL =
            "INSERT INTO band_telemetry ("
                    + " telemetry_id, tenant_id, customer_id, device_id, metric,"
                    + " date, hour, minute, value_enc, sleep_json, coverage_flag,"
                    + " data_source, gap_reason, sync_state, synced_at, is_wear,"
                    + " local_tz_offset, sport_id, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING";

    /**
     * E2 写入 —— <b>加密发生在这一层</b>（见类头"明文 ↔ 密文 的转换边界"）。
     *
     * <h2>🛑 加密主体是"客户"（{@code SubjectRef.customer}），不是租户也不是设备</h2>
     * ADR-12 §8.3 的裁定是 per-subject DEK，理由逐字写在架构规格书：
     * 「租户级密钥<b>无法</b>删除租户内的单个用户」—— 而 PIPL 删除权是针对<b>个人</b>的。
     * 故主体必须是 {@code customer}：客户被依法删除时，销毁其 DEK 即让本人全部健康数据
     * 计算上不可恢复，同时不影响同租户其他客户。选租户或设备都会让删除权失去落点。
     *
     * <h2>哪些列加密、哪些不加密</h2>
     * <ul>
     *   <li>{@code value_enc} ← 由 {@link TelemetrySensitivity} 判定该 metric 是否敏感；</li>
     *   <li>{@code sleep_json} ← 非空即加密（不由 metric 决定，见
     *       {@link TelemetrySensitivity#requiresEncryptionForSleepDetail}）；</li>
     *   <li>{@code metric}/{@code date}/{@code hour}/{@code minute}/{@code sport_id}
     *       <b>不加密</b> —— 它们是幂等键成员，加密会让唯一索引失去作用。</li>
     * </ul>
     */
    /**
     * E2 写入 —— 加密发生在这一层（见类头"明文 ↔ 密文 的转换边界"）。
     *
     * <h2>🛑 返回值语义：它返回的是"该幂等键下【库里实际存在】的那一行 id"</h2>
     * 这个返回值不是装饰。E2 的响应体里有 {@code telemetry_id}（见 {@code BandController}），
     * 若幂等重放时返回<b>本次入参里那个新生成的 UUID</b>，客户端会拿到一个
     * <b>库里并不存在的 id</b> —— 后续用它做任何事都会 404 或更糟（静默错配）。
     * 故：本次真插入 ⇒ 返回入参 id；幂等命中 ⇒ 返回<b>已存在那一行</b>的 id。
     *
     * <h2>🛑 顺序：快路径（查）→ 加密 → 权威（写）→ 竞争兜底（再查）</h2>
     * <ol>
     *   <li><b>快路径</b>：先查一次（{@link #findTelemetryId}）。命中则直接返回 ——
     *       省掉一次<b>加密</b>与一次写。它<b>不是权威</b>（并发下会漏判），只是优化；</li>
     *   <li><b>加密</b>在事务之外（原样保留的纪律：密码学调用不持有数据库连接，
     *       避免把 KMS/算法耗时摊进事务持有时长而放大锁竞争）；</li>
     *   <li><b>权威</b>：{@code ON CONFLICT DO NOTHING}（见 {@link #INSERT_TELEMETRY_SQL}）。
     *       它是原子的 —— 这才是幂等的真正判据；</li>
     *   <li><b>竞争兜底</b>：若第 ③ 步受影响行数为 0，说明在本方法第 ① 与第 ③ 步之间
     *       有并发请求抢先写入。此时再查一次拿到对方的 id 返回。</li>
     * </ol>
     * 与 E1（{@link #insertSyncLog}）的差别只在<b>冲突后的对外语义</b>：
     * E1 契约声明 409，故返回 {@code false} 让服务层报 409；
     * E2 契约声明"幂等 upsert（已存在 → 重放，不覆盖）"，故返回已存在行的 id，仍 200。
     *
     * <h2>🛑 加密主体是"客户"（{@code SubjectRef.customer}），不是租户也不是设备</h2>
     * ADR-12 §8.3 的裁定是 per-subject DEK，理由逐字写在架构规格书：
     * 「租户级密钥<b>无法</b>删除租户内的单个用户」—— 而 PIPL 删除权是针对<b>个人</b>的。
     * 故主体必须是 {@code customer}：客户被依法删除时，销毁其 DEK 即让本人全部健康数据
     * 计算上不可恢复，同时不影响同租户其他客户。选租户或设备都会让删除权失去落点。
     *
     * <h2>哪些列加密、哪些不加密</h2>
     * <ul>
     *   <li>{@code value_enc} ← 由 {@link TelemetrySensitivity} 判定该 metric 是否敏感；</li>
     *   <li>{@code sleep_json} ← 非空即加密（不由 metric 决定，见
     *       {@link TelemetrySensitivity#requiresEncryptionForSleepDetail}）；</li>
     *   <li>{@code metric}/{@code date}/{@code hour}/{@code minute}/{@code sport_id}
     *       <b>不加密</b> —— 它们是幂等键成员，加密会让唯一索引失去作用。</li>
     * </ul>
     *
     * @return 该幂等键下库里实际存在的行的 {@code telemetry_id}
     */
    public UUID insertTelemetry(String tenantId, TelemetryRow row) {
        // ① 幂等快路径：命中 ⇒ 直接返回已存在行的 id（不做加密、不做写）。
        //    🛑 只是优化，不是权威 —— 权威在 ③ 的 ON CONFLICT。
        UUID existing = findTelemetryId(tenantId, row);
        if (existing != null) {
            return existing;
        }

        // ② 加密在事务之外完成：密码学调用不持有数据库连接，避免把 KMS/算法耗时
        //    摊进事务持有时长（长事务会放大锁竞争）。
        String valueEnc = row.valueNum() == null ? null
                : (row.valueRequiresEncryption()
                        ? encryptField(tenantId, row, SensitiveField.of(
                                metricToFieldName(row.metric())), row.valueNum().toPlainString())
                        : row.valueNum().toPlainString());
        String sleepEnc = TelemetrySensitivity.requiresEncryptionForSleepDetail(row.sleepJson())
                ? encryptField(tenantId, row, TelemetrySensitivity.sleepDetailField(), row.sleepJson())
                : null;

        // ③ 权威：原子写。affected=0 ⇒ 幂等命中
        int affected = inTenant(tenantId, () -> jdbc.update(INSERT_TELEMETRY_SQL,
                row.telemetryId().toString(),
                row.customerId().toString(),
                row.deviceId().toString(),
                row.metric(),
                Date.valueOf(row.date()),
                row.hour(),
                row.minute(),
                valueEnc,
                sleepEnc,
                row.coverageFlag(),
                row.dataSource() == null ? "手环" : row.dataSource(),
                row.gapReason(),
                row.syncState() == null ? "synced" : row.syncState(),
                Timestamp.from(row.syncedAt()),
                row.isWear(),
                row.localTzOffset(),
                row.sportId(),
                row.createdBy()));
        if (affected > 0) {
            return row.telemetryId();
        }

        // ④ 竞争兜底：本方法的 ① 与 ③ 之间被并发请求抢先。
        UUID raced = findTelemetryId(tenantId, row);
        if (raced == null) {
            // 这是"不可能"分支：affected=0 只可能由唯一约束冲突引起，
            // 而冲突 ⇒ 必然存在那行。若真走到这里，说明幂等判定与库层状态不一致 ——
            // 宁可显式失败（5xx）也不返回一个不存在的 id（那会让错误发生在更远的地方）。
            throw new IllegalStateException(
                    "E2 写入未生效却查不到已存在行（device_id=" + row.deviceId()
                            + ", metric=" + row.metric() + ", date=" + row.date()
                            + ", hour=" + row.hour() + ", minute=" + row.minute()
                            + ", sport_id=" + row.sportId()
                            + "）—— 幂等快路径与库层独占状态不一致，拒绝返回不存在的 telemetry_id");
        }
        return raced;
    }

    // ------------------------------------------------------------------
    // 字段级加密的两处原语（写侧 / 读侧）
    // ------------------------------------------------------------------

    /** 明文 → 密文信封。主体恒为 {@code customer}（ADR-12 §8.3 per-subject）。 */
    private String encryptField(String tenantId, TelemetryRow row, SensitiveField field, String plaintext) {
        if (field == null) {
            throw new IllegalStateException("metric '" + row.metric()
                    + "' 需要加密但未在 TelemetrySensitivity 中登记字段名 —— "
                    + "加密登记表与 metric 词表已漂移，拒绝以未定义的方式写入");
        }
        return cipher.encryptText(
                SubjectRef.customer(tenantId, row.customerId().toString()),
                field.fieldName(), plaintext);
    }

    /**
     * 密文信封 → 明文（读侧）。
     *
     * <p>🛑 <b>兼容未迁移的历史明文行</b>：若库里的值不是信封形态
     * （{@code dy1:} 前缀），说明它是 V10 之前写入的明文（或某个环境的库还没迁移到 V10）。
     * 此时直接原样返回，而<b>不</b>让读取整行失败 ——
     * 一条历史数据把接口搞挂，比"这一条暂时以明文读出"严重得多。
     *
     * <p>但这不等于容忍：V10 的 {@code CHECK} 已保证<b>新写入</b>必为信封；
     * 存量明文由 V10 的自证块在迁移时拒绝（要么先重加密、要么清空）。
     * 故本分支只可能在"自证块被绕过"的场景下命中，属于最后一道兜底。
     */
    private String decryptField(String tenantId, UUID customerId, SensitiveField field, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (!raw.startsWith("dy1:")) {
            return raw;
        }
        return cipher.decryptText(
                SubjectRef.customer(tenantId, customerId.toString()), field.fieldName(), raw);
    }

    /** metric → SensitiveField.fieldName()（供 AAD 使用）。未登记返回 null（由调用方拒绝）。 */
    private static String metricToFieldName(String metric) {
        return TelemetrySensitivity.sensitiveFieldOf(metric)
                .map(SensitiveField::fieldName).orElse(null);
    }

    /**
     * 按 E2 双分支幂等键查"已存在那一行的 {@code telemetry_id}"；不存在返回 {@code null}。
     *
     * <h2>🛑 为什么返回 id 而不是 boolean</h2>
     * 本方法的前身 {@code telemetryIdempotentHit(...)} 返回 {@code boolean}，
     * 于是"命中"这件事传到服务层后，服务层手上<b>只有入参里那个新生成的 UUID</b>，
     * 只能把它当结果返回 —— 而那个 id <b>库里并不存在</b>。E2 响应体含
     * {@code telemetry_id}（见 {@code BandController.upsertTelemetry}），
     * 故幂等重放时客户端会拿到一个查不到的行 id。
     * 返回 id 让"重放"这条路径与"首次"这条路径给出<b>同一个 id</b>，
     * 这正是"幂等"这个词在响应层面的含义。
     *
     * <h2>🛑 谓词必须与 UNIQUE 索引逐字对应（含分支谓词）</h2>
     * <pre>
     *  日型   → (device_id, metric, date, COALESCE(hour,-1), COALESCE(minute,-1)) AND sport_id IS NULL
     *  游标型 → (device_id, metric, sport_id)
     * </pre>
     * 日型分支<b>必须带 {@code sport_id IS NULL}</b>：V3 的
     * {@code uq_bt_daily_idempotent} 是部分唯一索引（{@code WHERE sport_id IS NULL}），
     * 若查询不带这个谓词，一条 {@code sport_id} 非空的行在 device/metric/date/hour/minute
     * 恰好相同时会被<b>误判为日型命中</b> —— 两个分支的幂等键被混同。
     * 前身方法漏了这个谓词（本方法一并修正）。
     *
     * <p>{@code COALESCE(hour,-1)} 必须写成与索引<b>同形的表达式</b>（而非
     * {@code (hour = ? OR hour IS NULL)}），否则 PG 用不上表达式索引，退化成全表扫描。
     */
    public UUID findTelemetryId(String tenantId, TelemetryRow row) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<UUID> found;
            if (row.sportId() != null) {
                found = jdbc.queryForList(
                        "SELECT telemetry_id FROM band_telemetry"
                                + " WHERE device_id = ?::uuid AND metric = ? AND sport_id = ?",
                        UUID.class, row.deviceId().toString(), row.metric(), row.sportId());
            } else {
                found = jdbc.queryForList(
                        "SELECT telemetry_id FROM band_telemetry"
                                + " WHERE device_id = ?::uuid AND metric = ? AND date = ?"
                                + " AND COALESCE(hour,-1) = ? AND COALESCE(minute,-1) = ?"
                                + " AND sport_id IS NULL",
                        UUID.class, row.deviceId().toString(), row.metric(),
                        Date.valueOf(row.date()),
                        row.hour() == null ? -1 : row.hour(),
                        row.minute() == null ? -1 : row.minute());
            }
            return found.isEmpty() ? null : found.get(0);
        });
    }

    // ------------------------------------------------------------------
    // E3 遥测读取（四端按档裁剪由服务层负责，此处返回原始行）
    // ------------------------------------------------------------------

    private static final String SELECT_TELEMETRY_COLUMNS =
            "telemetry_id, customer_id, device_id, metric, date, hour, minute,"
                    + " value_enc, sleep_json, coverage_flag, data_source, gap_reason,"
                    + " sync_state, synced_at, is_wear, local_tz_offset, sport_id, created_by";

    /** E3 —— 按客户取遥测（按 date 降序，最新在前）。 */
    public List<TelemetryRow> findByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT " + SELECT_TELEMETRY_COLUMNS + " FROM band_telemetry"
                        + " WHERE customer_id = ?::uuid"
                        + " ORDER BY date DESC, synced_at DESC",
                (rs, i) -> mapTelemetry(tenantId, rs),
                customerId.toString()));
    }

    /** 按租户取全部遥测（证据/盘点用，比 findByCustomer 多一层过滤）。 */
    public List<TelemetryRow> findAllInTenant(String tenantId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT " + SELECT_TELEMETRY_COLUMNS + " FROM band_telemetry"
                        + " ORDER BY date DESC, synced_at DESC",
                (rs, i) -> mapTelemetry(tenantId, rs)));
    }

    /**
     * 读侧解密 —— 从库行还原成"明文形态"的 {@link TelemetryRow}。
     *
     * <p>🛑 主体三元组必须用当行的 {@code customer_id}，而不是"当前登录人"：
     * 遥测数据永随客户（客户级属性，不做门店锁定，见 V2/V3），
     * 用登录人当主体会让换个人读取时解不开 —— 而症状看起来像"数据损坏"。
     */
    private static TelemetryRow mapTelemetry(String tenantId, java.sql.ResultSet rs)
            throws java.sql.SQLException {
        UUID customerId = rs.getObject("customer_id", UUID.class);
        String metric = rs.getString("metric");
        String rawValue = rs.getString("value_enc");
        String rawSleep = rs.getString("sleep_json");

        return new TelemetryRow(
                rs.getObject("telemetry_id", UUID.class),
                customerId,
                rs.getObject("device_id", UUID.class),
                metric,
                rs.getObject("date", java.sql.Date.class).toLocalDate(),
                rs.getObject("hour", Integer.class),
                rs.getObject("minute", Integer.class),
                decryptValue(tenantId, customerId, metric, rawValue),
                decryptSleep(tenantId, customerId, rawSleep),
                rs.getObject("coverage_flag", Boolean.class),
                rs.getString("data_source"),
                rs.getString("gap_reason"),
                rs.getString("sync_state"),
                rs.getTimestamp("synced_at").toInstant(),
                rs.getObject("is_wear", Integer.class),
                rs.getObject("local_tz_offset", Integer.class),
                rs.getString("sport_id"),
                rs.getString("created_by"));
    }

    /**
     * 还原标量值。
     *
     * <p>这一小段代码是本类最容易写错的地方，故把它的三条纪律写在这里：
     * <ol>
     *   <li><b>按当行的 metric 选字段名</b>（AAD 的一部分）。用别的字段名去解会
     *       认证失败 —— 这恰好证明 AAD 的归属绑定是承重的：把 hr 的密文挪到 spo2 列
     *       也解不开，而不是静默给出错误的心率。</li>
     *   <li><b>非敏感 metric 的值是明文数字文本</b>（如 steps 未登记时），
     *       直接 parse 回 BigDecimal；不得当密文去解。</li>
     *   <li><b>明文形态判定用前缀</b>而非 try/catch：靠捕获异常来区分"明文 vs 密文"
     *       会把"密文被篡改"也当成明文接受 —— 那是认证被静默绕过。</li>
     * </ol>
     */
    private static java.math.BigDecimal decryptValue(String tenantId, UUID customerId,
                                                     String metric, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("dy1:")) {
            SensitiveField field = TelemetrySensitivity.sensitiveFieldOf(metric).orElseThrow(
                    () -> new IllegalStateException("metric '" + metric + "' 的值是密文信封，"
                            + "但该 metric 未登记为敏感字段 —— 登记表与数据不一致"));
            return new java.math.BigDecimal(
                    CIPHER.decryptText(SubjectRef.customer(tenantId, customerId.toString()),
                            field.fieldName(), raw));
        }
        return new java.math.BigDecimal(raw);
    }

    /** 还原睡眠分期明细（非空即加密，字段名固定）。 */
    private static String decryptSleep(String tenantId, UUID customerId, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.startsWith("dy1:")) {
            return raw;
        }
        return CIPHER.decryptText(SubjectRef.customer(tenantId, customerId.toString()),
                TelemetrySensitivity.sleepDetailField().fieldName(), raw);
    }

    /**
     * 静态 cipher 引用，使 {@code mapTelemetry} 保持为 {@code RowMapper} 可用的静态方法
     * （{@link org.springframework.jdbc.core.RowMapper} 是函数式接口，用实例方法会需要 lambda 捕获，
     * 而捕获会让"每行都新建一个 lambda"，真库大结果集下是可测量的开销）。
     *
     * <p>🛑 它在构造时被赋值，且构造器已拒绝 null ⇒ 不存在"读到一半发现 cipher 为空"。
     */
    private static volatile FieldCipher CIPHER;

    // ------------------------------------------------------------------
    // E1 同步批次落库（V9 起；契约为准的词表对齐）
    // ------------------------------------------------------------------

    /**
     * E1 批次上报的 INSERT（V9 起）。
     *
     * <h2>🛑 为什么同时写两组列（旧中文两态 + 新契约英文枚举）</h2>
     * {@code band_sync_log} 一身兼两条产线：PRD §4.6 的「同步尝试日志」（旧列
     * {@code trigger_source} 中文 / {@code result} 两态）与契约 §4.1 的「同步批次上报」
     * （V9 新增 {@code contract_trigger} / {@code contract_state}）。
     * 一次 E1 上报**同时**是两种事实：它既是"这次同步尝试"，也是"这个批次的终态"。
     * 故两个值域各自落自己那一列，由各自的 CHECK 钉住 —— 而不是把一方硬塞进另一方
     * （那正是 A-5 说的"硬映射报 23514"）。
     *
     * <h2>旧列取值由契约值**派生**，不是第二份独立输入</h2>
     * {@code trigger_source} 与 {@code result} 从契约值映射而来（见
     * {@link #triggerToLegacy} / {@link #stateToLegacyResult}）——
     * 单点派生，故两组列**不可能分叉**。若让调用方各传一遍，两处就会在某次改动后不一致，
     * 而不一致的那一侧不会报错。
     *
     * <p>⚠️ {@code syncing} / {@code no_data_today} 两态**没有**旧列对应值：
     * 前者不是终态（旧列语义是"这次尝试的结果"）、后者不是失败（旧 result 只有成败两态）。
     * 故这两态下 {@code result} 落 {@code null} —— 这正是旧列刻意可空的用途。
     *
     * <h2>🛑 幂等由库层唯一索引兜底，不由"先查后写"兜底</h2>
     * 用 {@code ON CONFLICT DO NOTHING} 而不是"SELECT 计数为 0 才 INSERT"：
     * 后者在并发下会双写（两个请求同时查到 0），而唯一索引是原子的。
     * 受影响行数为 0 ⇒ 说明同 batch_no 已存在 ⇒ 服务层据此报 409 幂等重放。
     *
     * <p>⚠️ {@code (tenant_id, batch_no) WHERE batch_no IS NOT NULL} 是 V9 建的
     * 部分唯一索引名，故 ON CONFLICT 的目标须与之逐字一致（含 WHERE 谓词）——
     * 写错目标会让 PG 报"无匹配的唯一约束"而不是静默 —— 这是好事，能在第一次执行就暴露。
     */
    private static final String INSERT_SYNC_LOG_SQL =
            "INSERT INTO band_sync_log ("
                    + " sync_log_id, tenant_id, device_id, attempt_at,"
                    + " trigger_source, result, fail_stage,"
                    + " contract_state, contract_trigger, batch_no, last_success_date,"
                    + " created_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?, now(), ?)"
                    + " ON CONFLICT (tenant_id, batch_no) WHERE batch_no IS NOT NULL DO NOTHING";

    /** 写入一条 E1 批次（V9 起）。返回 {@code false} = 幂等命中（库层已有同 tenant+batch_no 行）。 */
    public boolean insertSyncLog(String tenantId, SyncBatchRow row) {
        requireUuid(tenantId);
        requireUuid(row.syncLogId().toString());
        requireUuid(row.deviceId().toString());
        return inTenant(tenantId, () -> {
            int affected = jdbc.update(INSERT_SYNC_LOG_SQL,
                    row.syncLogId().toString(),
                    row.deviceId().toString(),
                    Timestamp.from(row.attemptAt()),
                    row.trigger() == null ? null : triggerToLegacy(row.trigger()),
                    stateToLegacyResult(row.state()),
                    "sync_failed".equals(row.state()) ? row.failReasonClass() : null,
                    row.state(),
                    row.trigger(),
                    row.batchNo(),
                    row.lastSuccessDate() == null ? null : Date.valueOf(row.lastSuccessDate()),
                    row.createdBy());
            return affected > 0;
        });
    }

    /**
     * 契约五值 trigger → 旧列 {@code trigger_source} 中文词表。
     *
     * <h2>🛑 这是一处【显式登记】的跨词表映射，不是"两种写法"</h2>
     * 两套词表取值集不同（4 vs 5），故映射<b>必然</b>是"多对一"：
     * <pre>
     *   on_show_cold  → onShow      （冷启动 onShow —— 与字典 §4.6 的 'onShow' 逐字一致）
     *   on_show_hot   → onShow      （热启动 onShow —— 同上；两态在旧词表里合并）
     *   checkin       → 到店核销
     *   daily_report  → 每日填报
     *   manual        → 手动
     * </pre>
     * ⚠️ {@code on_show_cold} 与 {@code on_show_hot} 在旧词表里**无法区分**（都落 'onShow'）。
     * 这是旧词表的信息损失，**不是本映射的缺陷** —— 契约侧 {@code contract_trigger} 列
     * 完整保留了区分。故 E6 出参读契约侧列、不读旧列（旧列仅供 §4.6 那条产线消费）。
     */
    static String triggerToLegacy(String contractTrigger) {
        return switch (contractTrigger) {
            case "on_show_cold", "on_show_hot“ -> ”onShow";
            case "checkin“ -> ”到店核销";
            case "daily_report“ -> ”每日填报";
            case "manual“ -> ”手动";
            default -> null;   // 领域构造器已挡非法值；此处不回落，返回 null 由 CHECK 拒
        };
    }

    /**
     * 契约四态 state → 旧列 {@code result} 两态。
     *
     * <pre>
     *   synced        → success
     *   sync_failed   → failed
     *   syncing       → null   （非终态，旧列语义是"这次尝试的结果"）
     *   no_data_today → null   （不是失败！旧 result 只有成败两态，不得把"无数据"记成"失败"）
     * </pre>
     */
    static String stateToLegacyResult(String contractState) {
        return switch (contractState) {
            case "synced“ -> ”success";
            case "sync_failed“ -> ”failed";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // E6 同步状态卡（读最新一条 sync_log）
    // ------------------------------------------------------------------

    /** E6 前置：按客户反查唯一 active 手环（band 表 1 客户 : 1 有效手环）。 */
    public UUID findActiveDeviceByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<UUID> ids = jdbc.query(
                    "SELECT band_id FROM band WHERE customer_id = ?::uuid AND status = 'active'"
                            + " ORDER BY bound_at DESC LIMIT 1",
                    (rs, i) -> rs.getObject("band_id", UUID.class),
                    customerId.toString());
            return ids.isEmpty() ? null : ids.get(0);
        });
    }

    /**
     * E6 —— 取某设备最近一次<b>批次上报</b>（四态卡的数据源）。
     *
     * <h2>🛑 V9 起本查询限定 {@code contract_state IS NOT NULL}</h2>
     * {@code band_sync_log} 一身兼两条产线（见类注释）：
     * <pre>
     *   E1 批次上报行（本端点写入）      ：contract_state 非空 ⇒ 满足四态卡的语义
     *   PRD §4.6「同步尝试日志」行      ：contract_state 为 null（不由 E1 写）
     * </pre>
     * E6 的语义是"<b>同步批次</b>状态卡"—— 它以契约四态作答，而"尝试日志"行**根本没有**
     * 契约四态（它的 {@code result} 只有成败两态、{@code trigger_source} 是中文）。
     * 若不限定，读到旧行会得到"用中文触发源/两态构造契约四态行"的局面 ——
     * 而那会让 {@link SyncBatchRow} 的 fail-closed 校验在**读路径**抛异常：
     * 一条历史数据就能让状态卡接口 500，且成因（表里混着两条产线的行）在错误里看不出来。
     *
     * <p>⚠️ 因此"某设备只有尝试日志、没有批次上报"时本方法返回 {@code null} ——
     * 这是<b>正确</b>的：状态卡的答案是"还没有批次上报过"，而不是"拿一条流水硬凑一个状态"。
     */
    public SyncBatchRow findLatestSyncLog(String tenantId, UUID deviceId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            List<SyncBatchRow> rows = jdbc.query(
                    "SELECT sync_log_id, device_id, attempt_at, trigger_source, contract_trigger,"
                            + " result, contract_state, fail_stage, batch_no, last_success_date,"
                            + " date_done, pending_dates, coverage_start, coverage_end, created_by"
                            + " FROM band_sync_log WHERE device_id = ?::uuid"
                            + "   AND contract_state IS NOT NULL"
                            + " ORDER BY attempt_at DESC LIMIT 1",
                    (rs, i) -> new SyncBatchRow(
                            rs.getObject("sync_log_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            // contract_state 非空 ⇒ 本行是 E1 写的 ⇒ batch_no 必然非空（同一条 INSERT）。
                            rs.getString("batch_no"),
                            // 同上：E1 写入时 contract_trigger 必为契约五值之一，无需回落。
                            rs.getString("contract_trigger"),
                            rs.getString("contract_state"),
                            rs.getTimestamp("attempt_at").toInstant(),
                            // V9 起本列在场；synced 行由领域构造器保证非空（契约描述逐字）。
                            rs.getObject("last_success_date", java.sql.Date.class) == null
                                    ? null
                                    : rs.getObject("last_success_date", java.sql.Date.class).toLocalDate(),
                            rs.getString("fail_stage"),
                            null,
                            rs.getTimestamp("attempt_at").toInstant(),
                            rs.getString("created_by")),
                    deviceId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    private static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户/标识非合法 UUID，拒绝执行手环 SQL");
        }
    }

    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }
}