package com.diaoyuanyun.dy.app.bandrefetch.repository;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageSnapshot;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeSnapshot;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>手环历史补拉写入仓储</b> —— 本仓<b>唯一</b>一个往 {@code band_sync_probe} /
 * {@code band_daily_coverage} 写行的生产代码。
 *
 * <h2>🛑 它补的是什么：{@code ProvisioningBoundaryGateTest.NOT_PROVISIONED} 账的最后 2 张</h2>
 * 这两张表是 PRD §2.8.7「设备端历史补拉」的落库载体：
 * <pre>
 *   band_sync_probe      —— 留存窗口 N 的运行时留痕（"可回捞最近 N 天"的唯一证据）
 *   band_daily_coverage  —— 逐日覆盖 / 缺口明细（A3「观测率」的直接取值来源）
 * </pre>
 * 缺它们的后果<b>不是任何错误码</b>，而是：
 * <pre>
 *   §2.8.7③ 起始日公式 start = max(last_synced_date + 1, today − N_retain)
 *     中 N_retain 无处落库
 *   → 补拉按「未探到 N」运行
 *   → 去拉一批设备根本不会返回的日期（§2.8.7③ 逐字警告「超窗口 → 只会制造假缺失」）
 *   → 那些日期被记成缺口
 *   → 缺口里有一类 not_worn，而它是【全 7 值里唯一可扣分的】
 *   → 【客户被扣分】
 * </pre>
 * 🛑 <b>本批是五批边界移动里唯一一批"缺口后果落在客户身上"的</b>
 * （B-10/B-11 是 23503、B-12 是 422、B-13 是静默、A-1 是指标恒为 0）——
 * 这不是程度差异，是类别差异。它决定了本类的两处设计：
 * <b>写入侧必须守住"技术性不得被记为行为性"</b>，
 * 且<b>N 的可信度必须可观测</b>。
 *
 * <h2>🛑🛑 两条原语的幂等形态【刻意相反】，不得统一</h2>
 * <table border="1">
 *   <caption>两条写原语的形态对照（下一批改账的人会需要）</caption>
 *   <tr><th></th><th>{@code register_sync_probe}</th><th>{@code register_daily_coverage}</th></tr>
 *   <tr><td>语义</td><td><b>追加</b>（同一 device+history 允许多条）</td>
 *       <td><b>覆盖</b>（upsert）</td></tr>
 *   <tr><td>冲突键</td><td>{@code probe_id}（单列全局主键）</td>
 *       <td>{@code (device_id, date)}（唯一约束）</td></tr>
 *   <tr><td>冲突动作</td><td>{@code DO NOTHING}</td><td>{@code DO UPDATE … WHERE}</td></tr>
 *   <tr><td>冲突键含租户维度？</td><td>否</td><td>否</td></tr>
 *   <tr><td>⇒ 需补跨租户 RAISE？</td><td>必须</td><td>必须<b>且多一类</b>（跨客户）</td></tr>
 *   <tr><td>返回值</td><td>两态（CREATED / ALREADY_EXISTS）</td>
 *       <td>两态（CREATED / UPDATED，<b>两态都改了数据</b>）</td></tr>
 *   <tr><td>冲突时是否改数据？</td><td><b>不改</b>（证据不可改写）</td>
 *       <td><b>改</b>（当日观测事实可刷新）</td></tr>
 *   <tr><td>依据</td><td>§2.8.7③「N 随探测刷新，<b>须留痕</b>」→ 证据不可覆盖</td>
 *       <td>§2.8.7③「幂等」行「重复拉取 = <b>覆盖、非追加</b>」</td></tr>
 * </table>
 * <p>🛑 把它们统一成同一种形态，会让"N 的历史轨迹"或"当日的最终观测"其中之一
 * 被静默丢弃 —— 而两者都各自被 PRD 逐字要求。故本类<b>刻意不提供</b>
 * 任何"统一的幂等写入口"。
 *
 * <h2>🛑 为什么它【不】是 Controller，也不该变成 Controller</h2>
 * 逐条核对了契约全部 40 个 path：<b>没有任何</b> path 是"登记留存探针"或
 * "写入逐日覆盖"。E 域（手环数据）的端点是 E1/E2（遥测上报）与 E4（派生结果），
 * 而本类写的是<b>补拉引擎的内部台账</b>。
 * <p>🛑 更重要的是 §2.8.7② 的一条硬要求：「②类失败<b>绝不显示</b>『没戴/未佩戴/缺失』
 * （技术失败与未佩戴须在<b>文案层彻底分开</b>）」+ 契约对
 * {@code gap_reason} 的 {@code x-visible-to: [therapist, meridian, admin]} 与
 * 「🔴 <b>客户恒 403 / 不下发</b>（硬约束，配置不得放开）」。
 * ⇒ 给这两张表加对外端点，会把一个<b>客户恒不可见</b>的判定面暴露到契约上，
 * 而那需要先回答契约回答不了的问题：「客户端凭什么能看到自己的缺口原因」。
 * <p>故本类被刻意做成一个<b>无 HTTP 映射的组件</b>
 * （{@code BandRefetchGateTest} 会机械断言它不带任何 HTTP 注解）——
 * 与 B-7 / B-10 / B-11 / B-12 / B-13 / A-1 同型：这是<b>契约化决策</b>，不是遗漏。
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO ...}，而全部下沉到 V22 的函数</h2>
 * 四条理由，每条都对应一类会静默出错的东西：
 * <ol>
 *   <li><b>门禁的判定必须与写入原子</b>：分两步（先查组合、再插行）在两次调用
 *       之间失败会留下"行已写入、而它的门禁校验发生在另一个事务里"这类不可解释的状态。
 *       对 {@code (5c)} 那条门禁尤其致命 —— 它是"客户被错扣一天"的唯一拦截点；</li>
 *   <li><b>{@code ON CONFLICT} 的推断语法 + 跨租户 / 跨客户判定</b>：
 *       见上表。把"冲突了但我看不见 ⇒ 是别人的"写在 Java 侧意味着两次 JDBC 往返
 *       拼出来 —— 而那是"先读后写"，会引入窗口。
 *       封在库层就是<b>一条语句内部</b>的事（{@code ON CONFLICT … DO UPDATE … WHERE}
 *       + {@code RETURNING (xmax = 0)}）；</li>
 *   <li><b>租户上下文的自建与自证</b>：函数用
 *       {@code set_config(..., is_local := true)} + {@code assert_tenant_context()}
 *       + 上下文一致性守卫，三者顺序不可调换；</li>
 *   <li><b>口径只有一处</b>：幂等写入<b>只在一处</b>发生（迁移里的函数），
 *       应用侧不再重复。两处都写会造出"口径分叉"：平时无害，
 *       一旦有人只改了其中一处，就会出现"同一个动作有两种结果"的静默不一致。</li>
 * </ol>
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 与 {@code BandLedger} / {@code DeviceLedger} / {@code CaseArchiveLedger} /
 * {@code AgreementLedger} 逐字同款：本类的每个公开写方法都只做<b>一次</b>数据库函数调用，
 * 看似不需要显式事务。但那个函数内部会调
 * {@code set_config(..., is_local := true)} 并依赖它活到本次调用结束 ——
 * 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态。
 *
 * <h2>🛑 本类刻意不提供的三件事（都是决定，不是遗漏）</h2>
 * <ol>
 *   <li><b>没有"删除探针"</b>：探针是 N 的<b>证据</b>（§2.8.7③「须留痕」）——
 *       提供删除入口等于给了"抹掉一个不好看的 N"的路，而那正是取证场景最不能有的形态；</li>
 *   <li><b>没有"改写历史覆盖"</b>：本类的写方法只能写<b>由调用方给出的一次观测</b>，
 *       不提供"把某天改成某个值"的定向改写入口。
 *       覆盖能力<b>只</b>通过 upsert 的当日观测面获得（且受 {@code WHERE} 限缩）；</li>
 *   <li><b>没有任何"按 N 回溯日期"的读法</b>：{@code start = max(last_synced_date + 1,
 *       today − N_retain)} 这条公式的<b>实现</b>属补拉引擎（需要
 *       {@code band_sync_log} 的 {@code date_done} 上下文、13 条接口分派、游标翻页）——
 *       而 §2.8.7 明示那些「<b>文档自证、待真机复核</b>」（外部依赖）。
 *       本类只提供<b>库层原语</b>：它接收调用方已经算好的结果，不替它做补拉决策。
 *       这也正是 V22 文件头硬边界②「不实现厂商 SDK 调用 / 13 条接口 / 游标翻页」的落点。</li>
 * </ol>
 */
@Component
public class BandRefetchLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public BandRefetchLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧之一：留存探针（V22 的 register_sync_probe）
    // ==================================================================

    /**
     * 幂等登记一条留存窗口探针（{@code ON CONFLICT DO NOTHING}，<b>追加语义</b>）。
     *
     * <p>整个动作 = V22 的 {@code register_sync_probe()} 一次调用。它内部完成：
     * 入参守卫 → 上下文一致性守卫 → 建立并自证租户上下文 → 设备在本租户内存在性断言
     * → {@code history_type} 词表断言 → {@code probe_source} 词表断言
     * → {@code valid_dates_json} 必须是 JSON <b>数组</b>的断言
     * → 单条 {@code INSERT … ON CONFLICT (probe_id) DO NOTHING} 原子写入
     * → 按"冲突了但它是不是我的"判两态。
     *
     * <p>🛑 <b>会抛的五种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>设备不在本租户内 ⇒ 函数 RAISE（{@code P0001}，消息含"本租户可见行数"）——
     *       🛑 消息里那句"最常见的原因是租户上下文不对"是刻意的：
     *       {@code band} 的 RLS 是 fail-closed 的，上下文错了 SELECT 会静默返回 0 行，
     *       于是"不是我的设备"与"我看不见我的设备"给出<b>同一个</b>结论；</li>
     *   <li>{@code history_type} 不在 14 值内 ⇒ RAISE（🛑 正常情况下走不到：
     *       {@link SyncProbeRecord} 的构造器收的是枚举，编译期已排除）；</li>
     *   <li>{@code probe_source} 不在 3 值内 ⇒ RAISE（同上，构造器收的是枚举）；</li>
     *   <li>{@code valid_dates_json} 不是数组 ⇒ RAISE（🛑 这条 Java 侧<b>不</b>拦 ——
     *       见 {@link SyncProbeRecord} 类注释「三处刻意的不校验」①）；</li>
     *   <li>{@code probe_id} 被<b>另一租户</b>占用 ⇒ RAISE
     *       （见 {@link SyncProbeOutcome} 类注释）。</li>
     * </ul>
     * 调用方<b>不应</b>把这几条捕获后当成"再试一次"——它们都需要人工确认。
     *
     * @return 两态之一（见 {@link SyncProbeOutcome}）；{@code ALREADY_EXISTS} <b>不是错误</b>
     */
    public SyncProbeOutcome registerProbe(SyncProbeRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "留存探针登记：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_sync_probe(?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, ?, ?, ?::timestamptz, ?, ?)",
                String.class,
                record.tenantId(),
                record.probeId().toString(),
                record.deviceId().toString(),
                record.historyType().code(),
                record.validDatesJson(),
                record.retentionDays(),
                record.probeSource() == null ? null : record.probeSource().code(),
                record.probedAt() == null ? null : Timestamp.from(record.probedAt()),
                record.isTest(),
                record.createdBy()));

        return SyncProbeOutcome.fromDb(raw);
    }

    // ==================================================================
    // 写侧之二：逐日覆盖（V22 的 register_daily_coverage）
    // ==================================================================

    /**
     * 幂等登记一条逐日覆盖（<b>upsert</b>，键 {@code (device_id, date)}）。
     *
     * <p>整个动作 = V22 的 {@code register_daily_coverage()} 一次调用。它内部完成：
     * 入参守卫 → 上下文一致性守卫 → 建立并自证租户上下文
     * → 设备存在性断言 → 客户存在性断言 → 同步日志存在性断言（若给了）
     * → {@code gap_reason} 词表断言 → {@code is_wear} 词表断言
     * → 🛑🛑 <b>(5c) 核心门禁</b>（{@code not_worn} 不得配技术性 {@code is_wear}）
     * → (5d) 逻辑自洽（{@code coverage_flag=TRUE} 不得配 {@code gap_reason}）
     * → 单条 {@code INSERT … ON CONFLICT (device_id, date) DO UPDATE … WHERE c.tenant_id = ...
     * AND c.customer_id = ... RETURNING (xmax = 0)} 原子 upsert
     * → 按 {@code xmax} 判两态，或按 {@code WHERE} 未命中分两类 RAISE。
     *
     * <p>🛑 <b>会抛的六种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>设备 / 客户 / 同步日志不在本租户内 ⇒ RAISE（消息含"本租户可见行数"）；</li>
     *   <li>{@code gap_reason} 不在 7 值内 ⇒ RAISE；</li>
     *   <li>🛑🛑 <b>{@code not_worn} 配技术性 {@code is_wear}</b> ⇒ RAISE ——
     *       <b>本迁移的靶心</b>。注意正常情况下本方法<b>不会</b>走到这一条：
     *       {@link DailyCoverageRecord} 的构造器已经先拦了。
     *       库层那一道守的是"有人绕过 record 直接写 SQL"；</li>
     *   <li>{@code coverage_flag=TRUE} 配 {@code gap_reason} ⇒ RAISE（构造器已先拦）；</li>
     *   <li>{@code (device_id, date)} 命中的行属于<b>另一个客户</b> ⇒ RAISE
     *       （"一次补拉就能把一个客户的观测日悄悄转记到另一个客户名下"）；</li>
     *   <li>{@code (device_id, date)} 命中的行属于<b>另一个租户</b> ⇒ RAISE
     *       （"本租户这一天的覆盖静默缺失，A3 分母少一天，<b>不报错</b>"）。</li>
     * </ul>
     *
     * @return 两态之一（见 {@link DailyCoverageOutcome}）；
     *         🛑 <b>两态都改动了数据</b>（upsert vs do-nothing 的关键差别）
     */
    public DailyCoverageOutcome registerDailyCoverage(DailyCoverageRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "逐日覆盖登记：记录不可为空");
        }
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_daily_coverage("
                        + "?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::date,"
                        + " ?, ?, ?, ?, ?, ?, ?::uuid, ?)",
                String.class,
                record.tenantId(),
                record.coverageId().toString(),
                record.deviceId().toString(),
                record.customerId().toString(),
                java.sql.Date.valueOf(record.date()),
                record.coverageFlag(),
                record.gapReason() == null ? null : record.gapReason().code(),
                record.isWear() == null ? null : record.isWear().code(),
                record.wearMinutes(),
                record.effectiveWearMinutes(),
                record.nAtThatTime(),
                record.sourceSyncLogId() == null ? null : record.sourceSyncLogId().toString(),
                record.createdBy()));

        return DailyCoverageOutcome.fromDb(raw);
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 读回一整行探针（本租户内不可见此行时返回 {@code null}）。
     *
     * <p>🛑 为什么返回 {@link SyncProbeSnapshot}（整行）而不是一个
     * {@code retentionWindowDaysOf(...)} 标量读法：见该 record 的类注释
     * 「为什么必须是一个『整行』对象」——标量读法会让"这一行不存在"与
     * "这一行存在、而它还没探到 N" 共用同一个 {@code null}，
     * 而两者的排查方向完全不同（前者查写入方、后者是合法状态）。
     *
     * <p>🛑 它是本迁移<b>最重要的可观测面</b>：门禁要回答的问题不是
     * "库里有几行"，而是"<b>写进去的那条 N，读回来还是不是那一条</b>"。
     * 只断言行数会漏掉"重放时用新参数改写了既有证据"这一类缺陷
     * （探针是 N 的留痕，被改写等于证据被抹）。
     */
    public SyncProbeSnapshot probeSnapshotOf(String tenantId, UUID probeId) {
        BandLedger.validateTenantId(tenantId);
        if (probeId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取留存探针：probe_id 必填");
        }
        return inTenant(tenantId, () -> {
            List<SyncProbeSnapshot> rows = jdbc.query(
                    "SELECT tenant_id::text, probe_id, device_id, history_type,"
                            + " valid_dates_json::text, retention_window_days, probe_source,"
                            + " probed_at, is_test, created_at, coalesce(created_by, '')"
                            + "  FROM band_sync_probe WHERE probe_id = ?::uuid",
                    (rs, i) -> new SyncProbeSnapshot(
                            rs.getString(1),
                            rs.getObject("probe_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            rs.getString("history_type"),
                            rs.getString("valid_dates_json"),
                            (Integer) rs.getObject("retention_window_days"),
                            rs.getString("probe_source"),
                            toInstant(rs.getTimestamp("probed_at")),
                            rs.getBoolean("is_test"),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString(11)),
                    probeId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 该设备该路历史在本租户内的<b>最新</b>探针（无则 {@code null}）。
     *
     * <p>🛑 "最新"的判定需要一个<b>全序</b>（{@code probed_at DESC, probe_id DESC}），
     * 而 {@code probed_at} 单独<b>不够</b> —— 同一时刻写入的两条探针会给出
     * 不确定的先后。若本类自己写 {@code ORDER BY probed_at DESC LIMIT 1}，
     * 就会与别处的口径形成两套全序，而它们的差别<b>不会报错</b>，
     * 只会在并发探测时表现为"两次调用返回不同的 probe_id"。
     * 这与 {@code RefundWorkOrderPort.findLatestStatement} 注释里记载的是同一族教训。
     *
     * <p>🛑 <b>为什么取"最新"而不是"唯一"</b>：本表是<b>追加</b>语义
     * （同一 {@code (device_id, history_type)} 允许多条，见
     * {@link SyncProbeRecord} 类注释），因为 §2.8.7③ 明示"N 随探测刷新，须留痕"。
     * ⇒ 设"当前有效的 N"是"最新的那一条"，而不是"唯一的那一条"。
     * 若哪天有人给它加了唯一约束，本方法会退化成恒返回同一条 ——
     * 而那会让"N 刷新过"这件事在系统里不可见。
     */
    public SyncProbeSnapshot latestProbeOf(String tenantId, UUID deviceId,
                                           com.diaoyuanyun.dy.app.bandrefetch.domain.HistoryMetric metric) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询最新探针：device_id 必填");
        }
        if (metric == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询最新探针：history_type 必填");
        }
        return inTenant(tenantId, () -> {
            List<SyncProbeSnapshot> rows = jdbc.query(
                    "SELECT tenant_id::text, probe_id, device_id, history_type,"
                            + " valid_dates_json::text, retention_window_days, probe_source,"
                            + " probed_at, is_test, created_at, coalesce(created_by, '')"
                            + "  FROM band_sync_probe"
                            + " WHERE device_id = ?::uuid AND history_type = ?"
                            + " ORDER BY probed_at DESC, probe_id DESC LIMIT 1",
                    (rs, i) -> new SyncProbeSnapshot(
                            rs.getString(1),
                            rs.getObject("probe_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            rs.getString("history_type"),
                            rs.getString("valid_dates_json"),
                            (Integer) rs.getObject("retention_window_days"),
                            rs.getString("probe_source"),
                            toInstant(rs.getTimestamp("probed_at")),
                            rs.getBoolean("is_test"),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString(11)),
                    deviceId.toString(), metric.code());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 🛑🛑 <b>读回该 {@code (device_id, date)} 在库里<b>实际保留</b>的 {@code coverage_id}</b>。
     *
     * <h2>为什么必须有这个方法（它补的是一处"审计指向不存在的 id"的静默缺陷）</h2>
     * 本表的 upsert 键是 {@code (device_id, date)}，而 {@code coverage_id} 是<b>行身份</b>。
     * V22 的 {@code (7)} 逐字把 {@code coverage_id} 列进"<b>不更新的列</b>"
     * （理由：{@code 改它等于换行}）⇒ 覆盖态（{@code UPDATED}）下：
     * <pre>
     *   本次传入的 coverage_id  ≠  库里那一行的 coverage_id
     *   后者恒等于【首次插入】那一次传入的值
     * </pre>
     * 而 {@code audit_log.target_id} 的语义是"<b>这条审计说的是哪一行</b>"。
     * 若写入侧直接用本次传入的 id：
     * <ul>
     *   <li>审计指向一个 {@code band_daily_coverage} 里<b>查不到</b>的 id ——
     *       按 coverageId 反查"这一天的审计证据"会落空；</li>
     *   <li>"这一天被重拉刷了几次"（{@code BAND_COVERAGE_REFRESHED} 计数）会被<b>摊薄</b>到
     *       每次不同的 id 上 —— 而那个计数是 §2.8.7③「节流」（建议 5 分钟内不重复发起）
     *       效果的<b>唯一可观测面</b>。</li>
     * </ul>
     * <p>🛑 两个 id 都是合法 UUID、都有意义（前者是"本次意图的编号"、后者是"库中行的编号"）——
     * 这正是它难被发现的理由：<b>没有任何一步会报错</b>，
     * 只有"按 target_id 去表里找那一行"的人才会发现找不到。
     * 该形态由 {@code BandRefetchGateTest} 判据 ③ 逐字断言。
     *
     * <h2>🛑 为什么是"读一次库"而不是"让函数 RETURNING 出 coverage_id"</h2>
     * 两者的取向不同，本仓选后者会更好、但<b>改动了库层契约</b>：
     * 现有函数签名 {@code RETURNS text} 只回两态字符串，
     * 让它多回一个 {@code coverage_id} 属于<b>迁移变更</b>（需新版本号 + 授权段同步 + 改账）。
     * 而本条缺陷的<b>正确修法不必动库层</b> —— 应用侧读一次自己的权威值即可。
     * <p>⚠️ 已登记为待裁项：若将来 V22+ 把函数改为返回 {@code (outcome, coverage_id)}，
     * 本方法可退役。在那之前，本方法是"<b>不改库层即可修对</b>"的最小落点。
     *
     * <h2>🛑 为什么它必须与写入在【同一事务】内</h2>
     * 本方法走 {@link #inTenant}（同一 {@code TransactionTemplate}，传播行为 {@code REQUIRED}），
     * 故它<b>加入</b>调用方已开的事务。三点理由：
     * <ul>
     *   <li>读到的必然是<b>本次写入之后</b>的行（同事务可见自己的写）；</li>
     *   <li>{@code SET LOCAL app.tenant_id} 与写入时的上下文同一套，不会读到别的租户；</li>
     *   <li>若审计写入失败导致整体回滚，这次读也一并消失 ——
     *       不会留下"读了但没写"的中间态。</li>
     * </ul>
     *
     * @return 库里那一行的 {@code coverage_id}；<b>本租户内查不到时返回 {@code null}</b>
     */
    public UUID readStoredCoverageId(String tenantId, UUID deviceId, LocalDate date) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取库中覆盖身份：device_id 必填");
        }
        if (date == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取库中覆盖身份：date 必填");
        }
        return inTenant(tenantId, () -> {
            List<UUID> rows = jdbc.query(
                    "SELECT coverage_id FROM band_daily_coverage"
                            + " WHERE device_id = ?::uuid AND date = ?::date",
                    (rs, i) -> rs.getObject(1, UUID.class),
                    deviceId.toString(), java.sql.Date.valueOf(date));
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 读回一整行逐日覆盖（本租户内不可见此行时返回 {@code null}）。
     *
     * <p>🛑 它是本迁移的<b>第二条可观测面</b>：门禁要回答的问题不是
     * "库里有几行"，而是"<b>upsert 之后，这一行的当日观测面是不是新的、
     * 而身份面（{@code coverage_id} / {@code customer_id} / {@code created_at}）
     * 是不是旧的</b>"。只断言行数会漏掉"覆盖顺手换掉了这一行的身份"这一类缺陷 ——
     * 而那正是 V22 的 (7) 刻意列出"不更新的列"所要防的东西。
     */
    public DailyCoverageSnapshot coverageSnapshotOf(String tenantId, UUID deviceId, LocalDate date) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取逐日覆盖：device_id 必填");
        }
        if (date == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取逐日覆盖：date 必填");
        }
        return inTenant(tenantId, () -> {
            List<DailyCoverageSnapshot> rows = jdbc.query(
                    "SELECT tenant_id::text, coverage_id, device_id, customer_id, date,"
                            + " coverage_flag, gap_reason, is_wear, wear_minutes,"
                            + " effective_wear_minutes, n_at_that_time, source_sync_log_id,"
                            + " created_at, coalesce(created_by, '')"
                            + "  FROM band_daily_coverage WHERE device_id = ?::uuid AND date = ?::date",
                    (rs, i) -> new DailyCoverageSnapshot(
                            rs.getString(1),
                            rs.getObject("coverage_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getObject("date", LocalDate.class),
                            (Boolean) rs.getObject("coverage_flag"),
                            rs.getString("gap_reason"),
                            (Integer) rs.getObject("is_wear"),
                            (Integer) rs.getObject("wear_minutes"),
                            (Integer) rs.getObject("effective_wear_minutes"),
                            (Integer) rs.getObject("n_at_that_time"),
                            rs.getObject("source_sync_log_id", UUID.class),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString(14)),
                    deviceId.toString(), java.sql.Date.valueOf(date));
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 该租户内的探针行数。
     *
     * <p>🛑 它<b>单独不足以</b>作为任何一条验收口径 —— 一个只报 {@code 0}
     * 的读法无法区分"没有探针"与"我什么也看不见"（未设上下文时 RLS 恒拒）。
     * 它只作为 {@link #probeSnapshotOf} 的<b>辅助证据</b>：两者一起用，
     * "行数 ≥ 1 而 snapshotOf 返回 null"这种组合本身就是一个可报警的矛盾。
     */
    public int countProbesInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM band_sync_probe", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /** 该租户内的逐日覆盖行数。🛑 口径与限制同 {@link #countProbesInTenant}。 */
    public int countCoverageInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM band_daily_coverage", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /**
     * 🛑 <b>扫出本租户内所有违反核心门禁的覆盖行</b>（{@code not_worn} 配技术性 {@code is_wear}）。
     *
     * <h2>为什么必须提供这个读法（写侧明明已经拦了）</h2>
     * 三条理由，见 {@link DailyCoverageSnapshot#violatesNotWornGate()} 的类注释：
     * ① 门禁上线（V22）之前写入的行；② 绕过函数直接写 SQL（库层门禁刻意不是表约束）；
     * ③ 本域的坏数据形态是"<b>客户被错扣了一天</b>"，而它<b>不报错、没有下游能抓住</b>。
     * <p>⇒ 故它不是一个"以防万一"的辅助方法，而是本域<b>唯一的坏数据发现入口</b>。
     * 门禁用它断言"干净起点"（新库里必须扫出 0 行），
     * 数据核对脚本用它找出需要修复的行。
     *
     * <p>🛑 SQL 侧的条件与 Java 侧 {@code violatesNotWornGate()} <b>必须是同一句话</b>：
     * {@code gap_reason = 'not_worn' AND is_wear IN (-1, 255)}。
     * 两处各写一次是本仓"同一件事只有一处定义"的<b>有意的例外</b> ——
     * 因为一处是 Java 的判据（走枚举的 {@code contradictsNotWorn()}），
     * 一处是 SQL 的判据（走字面量，无法调 Java 方法）。
     * ⇒ 一致性由 {@code BandRefetchGateTest} 机械核对
     * （枚举 {@code technicalCodes()} ↔ V22 的 {@code v_tech_iswear} ↔ 本方法的 SQL）。
     */
    public List<DailyCoverageSnapshot> scanNotWornGateViolations(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT tenant_id::text, coverage_id, device_id, customer_id, date,"
                        + " coverage_flag, gap_reason, is_wear, wear_minutes,"
                        + " effective_wear_minutes, n_at_that_time, source_sync_log_id,"
                        + " created_at, coalesce(created_by, '')"
                        + "  FROM band_daily_coverage"
                        + " WHERE gap_reason = 'not_worn' AND is_wear IN (-1, 255)",
                (rs, i) -> new DailyCoverageSnapshot(
                        rs.getString(1),
                        rs.getObject("coverage_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getObject("date", LocalDate.class),
                        (Boolean) rs.getObject("coverage_flag"),
                        rs.getString("gap_reason"),
                        (Integer) rs.getObject("is_wear"),
                        (Integer) rs.getObject("wear_minutes"),
                        (Integer) rs.getObject("effective_wear_minutes"),
                        (Integer) rs.getObject("n_at_that_time"),
                        rs.getObject("source_sync_log_id", UUID.class),
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getString(14))));
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code BandLedger.inTenant} / {@code DeviceLedger.inTenant} /
     * {@code CaseArchiveLedger.inTenant} / {@code AgreementLedger.inTenant} 同款，
     * 理由逐一相同：{@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"
     * 收敛成一个短事务，使「未设租户上下文 = 零行」这条 fail-closed 性质
     * 在所有路径上都成立。
     *
     * <p>🛑 本类的两个写方法<b>不走</b>这里：它们的上下文由 V22 的函数
     * 自建并自证（{@code set_config} + {@code assert_tenant_context} +
     * 一致性守卫）。故写侧<b>不</b>先 {@code SET LOCAL} —— 若先设了，
     * 函数入口的一致性守卫会看到"事务里已有一个上下文，且与传入租户相同"，
     * 那是允许的，但会让"上下文由谁建立"变得含混。本迁移让函数独占写侧上下文。
     *
     * <p>🛑 读侧<b>必须</b>走这里（{@code probeSnapshotOf} / {@code latestProbeOf} /
     * {@code coverageSnapshotOf} / 两个 count / {@code scanNotWornGateViolations}）——
     * 少一处 {@code SET LOCAL}，对应的那条门禁判据会<b>恒返回空</b>，
     * 于是"库里没有坏行"与"我什么都看不见"给出同一个结论（一条假绿）。
     * 该纪律由 {@code RlsInjectionRealityGateTest} 的载体登记守着
     * （本类已登记进 {@code LEDGER_CARRIERS}）。
     *
     * <p>{@code validateTenantId} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL。
     */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        BandLedger.validateTenantId(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** {@code java.sql.Timestamp → Instant}，保留可空（列是 NOT NULL，但读侧不假设）。 */
    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}