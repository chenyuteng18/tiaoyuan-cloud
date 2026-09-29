package com.diaoyuanyun.dy.app.bandrefetch.service;

import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageSnapshot;
import com.diaoyuanyun.dy.app.bandrefetch.domain.HistoryMetric;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeSnapshot;
import com.diaoyuanyun.dy.app.bandrefetch.repository.BandRefetchLedger;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <b>手环历史补拉服务</b> —— 把"登记 N 的留痕"与"写逐日覆盖"从两次裸的数据库写入，
 * 变成两次<b>带审计留痕的、原子的、可重放的</b>补拉作业动作。
 *
 * <h2>🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>审计留痕</b>：这两张表<b>不是</b>一份无关紧要的台账 ——
 *       它们是 A3「观测率」的<b>直接取值来源</b>，而 A3 进「依从性」维度、
 *       并进一步影响 {@code AS_refund}。故一次无痕的写入，等于<b>一次无主的扣分依据</b>：
 *       事后无法回答"这一天是谁判的、按哪次同步判的"，
 *       而"客户被错扣"这类申诉恰恰要回到这个起点。
 *       <p>🛑 与前面五批的审计理由<b>不同</b>，必须说清（不能笼统写"必须可追溯"）：
 *       <pre>
 *   B-10 band         → 「应戴天分母的两端 → 影响 A3 → 影响退款资格」
 *   B-11 device       → 「可追责性需要起点」
 *   B-12 scale        → 「基线档案 locked=TRUE 不可修改，无留痕无法事后修正」
 *   B-13 case_archive → 「归档是一次不可撤销的收口，而收口的唯一痕迹就是这条审计」
 *   A-1  agreement    → 「签署是一项合规证据，G1 合规率的取数依据」
 *   A-3  本类         → 「【写入的是一份扣客户分的依据】——
 *                        审计是它唯一的可申诉入口」
 *       </pre>
 *       六条理由都指向"必须同事务审计"，但它们<b>不是同一条理由</b> ——
 *       若混为一谈，下一个人会以为改掉其中一个的审计纪律是可以的。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写
 *       {@code created_by='band-refetch'}。真正的操作者由本类的调用方
 *       （补拉引擎 / 运维脚本）提供，并落进审计 {@code actor} ——
 *       那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑🛑 两条写路径的"重放是否留痕"【刻意不同】—— 这是本类最该被读到的地方</h2>
 * <table border="1">
 *   <caption>两态的审计处置对照（与前面五批的差别就在这里）</caption>
 *   <tr><th></th><th>探针（{@link #registerProbe}）</th>
 *       <th>逐日覆盖（{@link #registerDailyCoverage}）</th></tr>
 *   <tr><td>幂等命中态</td><td>{@code ALREADY_EXISTS}（<b>未改数据</b>）</td>
 *       <td>{@code UPDATED}（<b>改了数据</b>）</td></tr>
 *   <tr><td>该态是否写审计</td><td><b>写</b>（独立 action）</td>
 *       <td><b>写</b>（独立 action）</td></tr>
 *   <tr><td>理由</td>
 *       <td>"有人拿了同一个 {@code probe_id} 又登了一次"——
 *           多半是上游工具重放，也可能是误传 id。<b>两者都值得被看见</b>：
 *           前者说明"这批探针被跑了两次"，后者说明"有一份探针被挂到了错的 id 上"</td>
 *       <td>"这一天被<b>重拉并刷新</b>"——§2.8.7③「节流」行明确关心
 *           重复发起（「须时间窗节流（建议 5 分钟内不重复发起）」），
 *           而<b>本态的计数就是那条节流规则效果的唯一可观测面</b>。
 *           不留痕 = 节流是否生效不可测</td></tr>
 * </table>
 * <p>🛑 <b>为什么两处都用独立的 action，而不是"同一个 action + payload 带 mode"</b>：
 * 与 A-1（{@code CASE_ARCHIVE_REPLAYED}）同一条理由且更强 ——
 * {@code audit_log} 的检索习惯是"按 action 找一批"。
 * 本域的两个计数都有<b>独立的生产用途</b>：
 * <ul>
 *   <li>"新覆盖了多少个业务日"（{@code BAND_COVERAGE_REGISTERED} 的计数）
 *       是 A3 分子扩张速度的直接度量；</li>
 *   <li>"重拉刷新了多少次"（{@code BAND_COVERAGE_REFRESHED} 的计数）
 *       是节流规则的偏差度量。</li>
 * </ul>
 * 若共用 action，这两个数会被混在一个计数里，而<b>它们恰好要回答相反的问题</b>
 * （前者越大越好，后者越大越可疑）。
 *
 * <h2>🛑 跨租户 / 跨客户撞号【不】留审计 —— 与 A-1 同款处置，理由必须写清</h2>
 * V22 对"冲突行在别的租户/别的客户名下"是 <b>RAISE</b> ⇒ 它<b>根本走不到</b>
 * 本类的审计语句：事务在 {@code ledger.registerXxx()} 里就被异常终止了。
 * <p>🛑 这不是"漏了一条审计"，而是"这个情形没有可留痕的成功动作"。
 * 若哪天有人希望它也被留痕，正确做法是<b>在库函数里先写一条审计再 RAISE</b>
 * （但那时审计行会被同一个 RAISE 一起回滚 —— 故实际上需要一条独立事务），
 * 或者由调用方在上层捕获后单独记录。本类刻意<b>不</b>在这里 try/catch 后补一条审计
 * —— 那会造出"审计记了一次覆盖、而库里那一行还是旧的"的不一致，
 * 而那正是本域最危险的形态之一：<b>审计说改了、数据没改</b> ⇒
 * 事后核对会认为"这天的覆盖没问题"。
 *
 * <h2>🛑 为什么审计写入必须与写入【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的写方法内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * ⇒ {@code set_config('app.tenant_id', ..., is_local := true)} 建立的上下文
 * 活到<b>本事务提交</b>，而审计条目也落在同一个提交里。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把写方法的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么写入会独立提交 —— 于是"审计失败则写入回滚"这条不变量<b>静默失效</b>。
 * 本仓对这一类有既定处置：把前提写成可断言的东西
 * （见 {@code BandRefetchGateTest} 的"审计失败必须让写入回滚"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明写确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与 {@code DeviceService} / {@code CaseArchiveService} / {@code AgreementService}
 * 逐字相同：{@code AuditLogService.append()} 的契约写明
 * <b>id 由实现生成、忽略调用方传入的</b>。
 * B-7 的初版曾塞了一个自编的 {@code UUID.randomUUID()} 并当作回执返回 ——
 * 实测 {@code SELECT count(*) FROM audit_log WHERE id = ?} 返回 <b>0</b>。
 * 故本类构造 {@code AuditLog} 时第一个参数<b>刻意留空串</b>（占位、会被忽略），
 * 并显式取 {@code append()} 的返回值。
 *
 * <h2>🛑🛑 第 47 条系统性缺陷：{@code audit_log.target_id} 必须指向【库中真实存在】的 id</h2>
 * <b>本类的初版在这里错过一次，值得逐字记下。</b>
 * <pre>
 *   覆盖态（UPDATED）下：
 *     本次传入的 coverage_id  ≠  库里那一行的 coverage_id
 *   （V22 的 (7) 刻意把 coverage_id 列进"不更新的列" —— 理由逐字：改它等于换行）
 * </pre>
 * 初版把 {@code target_id} 写成 {@code record.coverageId()}（本次传入的值），于是：
 * <ul>
 *   <li>审计指向一个 {@code band_daily_coverage} 里<b>查不到</b>的 id ——
 *       按 coverageId 反查"这一天的审计证据"永远落空；</li>
 *   <li>{@code BAND_COVERAGE_REFRESHED} 的计数被<b>摊薄</b>到每次不同的 id 上 ——
 *       而那个计数是 §2.8.7③「节流」效果的<b>唯一</b>可观测面。</li>
 * </ul>
 * <p>🛑 <b>为什么它特别难被发现</b>（这是它够格被登记为"系统性缺陷"的理由）：
 * 两个 id 都是合法 UUID，<b>没有任何一步会报错</b>；
 * 审计行写成功了、payload 里两个 id 都在、日志也都打了。
 * 唯一的暴露方式是"<b>按 target_id 去表里找那一行</b>"——
 * 而这正是本节的门禁判据 ③ 做的事。
 * <p>🛑 <b>探针侧为什么没有同类问题</b>（必须说清，否则会误改）：
 * {@code probe_id} 是<b>单列全局主键</b>且冲突动作是 {@code DO NOTHING}
 * ⇒ 库里那一行的 {@code probe_id} 恒等于本次传入的值
 * （重放时本次传入的就是同一个 id）。两侧的差别不在"哪个字段"，而在
 * <b>"冲突时是否改写身份列"</b> —— 覆盖改观测面、不改身份面，故只有覆盖侧需要读权威值。
 * <p>🛑 修法选择：<b>读一次库层权威值</b>（{@link BandRefetchLedger#readStoredCoverageId}）
 * 而不是改函数签名让它 {@code RETURNING} 出 {@code coverage_id}。
 * 后者更好，但属<b>迁移变更</b>（改 {@code RETURNS text} 的契约 + 授权段 + 改账），
 * 而本条缺陷的正确修法不必动库层。已登记为待裁项。
 *
 * <h2>🛑 本类【不】实现补拉决策</h2>
 * §2.8.7③ 的起始日公式 {@code start = max(last_synced_date + 1, today − N_retain)}
 * 以及 13 条接口分派 + 游标翻页 + 逐日 {@code date_done} 持久化，
 * 都属<b>补拉引擎</b>的职责，而 §2.8.7 明示那些
 * 「<b>文档自证、待真机复核</b>」（第三条外部依赖，尚未取证）。
 * <p>本类只做"把已算好的结果原子落库 + 留痕"。
 * 🛑 这条边界与 V22 文件头硬边界②逐字一致，不得越。
 */
@Service
public class BandRefetchService {

    private static final Logger log = LoggerFactory.getLogger(BandRefetchService.class);

    /** 探针首次登记的动作名。 */
    static final String ACTION_PROBE_REGISTERED = "BAND_SYNC_PROBE_REGISTERED";

    /** 探针重放的动作名（🛑 刻意与首次不同 —— 它要能单独计数）。 */
    static final String ACTION_PROBE_REPLAYED = "BAND_SYNC_PROBE_REPLAYED";

    /** 逐日覆盖首次登记的动作名（"新覆盖一个业务日"）。 */
    static final String ACTION_COVERAGE_REGISTERED = "BAND_COVERAGE_REGISTERED";

    /**
     * 逐日覆盖重拉刷新的动作名（"同一天被重拉覆盖了一次"）。
     *
     * <p>🛑 它是 §2.8.7③「节流」行（建议 5 分钟内不重复发起）的
     * <b>唯一可观测面</b>：这个计数偏高说明节流没生效或不合理。
     */
    static final String ACTION_COVERAGE_REFRESHED = "BAND_COVERAGE_REFRESHED";

    /** 审计目标的类型名（{@code audit_log.target_type}）—— 两张表各一个，便于按表检索。 */
    static final String TARGET_PROBE = "band_sync_probe";
    static final String TARGET_COVERAGE = "band_daily_coverage";

    private final BandRefetchLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public BandRefetchService(BandRefetchLedger ledger,
                              ObjectProvider<AuditLogService> auditLogService,
                              DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧之一：留存探针
    // ==================================================================

    /**
     * 幂等登记一条留存窗口探针，并在同一个事务里留一条审计。
     *
     * @param record   探针意图（其构造器会先跑一遍形态校验：必填 / 列长 / 租户标识）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的 N 留痕，事后无法回答"这个窗口是谁探的"）
     * @return 登记结果（两态之一 + 本次留痕的审计 id + 是否属"未取证的 N"）
     */
    public ProbeResult registerProbe(SyncProbeRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "留存探针登记：记录不可为空");
        }
        requireOperator(operator, "留存探针登记");

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            SyncProbeOutcome outcome = ledger.registerProbe(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串，防止下一个人
            //       误以为回执取的就是这个字段（B-7 已付费记录过这个缺陷）。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    outcome.mutatedData() ? ACTION_PROBE_REGISTERED : ACTION_PROBE_REPLAYED,
                    TARGET_PROBE,
                    record.probeId().toString(),
                    Instant.now(),
                    probePayload(record, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry, "留存探针登记");

            log.info("[BAND-PROBE] tenant={} probe={} device={} history={} n={} source={}"
                            + " mode={} nUnverified={} operator={} auditId={}",
                    record.tenantId(), record.probeId(), record.deviceId(),
                    record.historyType().code(), record.retentionDays(),
                    record.probeSource() == null ? null : record.probeSource().code(),
                    outcome.name(), record.nIsUnverified(), operator, auditId);

            return new ProbeResult(outcome, auditId, record.probeId(), record.deviceId(),
                    record.nIsUnverified());
        });
    }

    // ==================================================================
    // 写侧之二：逐日覆盖
    // ==================================================================

    /**
     * 幂等登记一条逐日覆盖（upsert），并在同一个事务里留一条审计。
     *
     * <p>🛑 <b>这是本域最需要被谨慎对待的一条写路径</b>：
     * 它写的每一行都可能成为"<b>该客户这一天算 0 分</b>"的依据
     * （当且仅当 {@code gap_reason = not_worn}）。
     * 两层门禁在它之前已经跑过：{@link DailyCoverageRecord} 的构造器
     * （应用层，调用前）与 V22 的 (5c)（库层，写入语句内）。
     * 本类的角色是<b>第三步</b>：把"这一次写入发生过"留痕。
     *
     * @param record   覆盖意图（其构造器会先跑核心门禁：技术性不得配 {@code not_worn}）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的扣分依据，申诉时无从作答）
     * @return 登记结果（两态之一 + 本次留痕的审计 id + 是否属可扣分类）
     */
    public CoverageResult registerDailyCoverage(DailyCoverageRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "逐日覆盖登记：记录不可为空");
        }
        requireOperator(operator, "逐日覆盖登记");

        return tx.execute(status -> {
            DailyCoverageOutcome outcome = ledger.registerDailyCoverage(record);

            // 🛑🛑 审计的 target_id 必须指向【库里真的存在的那一行】。
            //    覆盖态（UPDATED）下，本次传入的 coverageId 与库里那一行的
            //    coverage_id 是**两个不同的 id** —— V22 的 (7) 刻意把 coverage_id
            //    列进"不更新的列"（"改它等于换行"），故库里保留的始终是 **首次插入** 的身份。
            //    若这里直接用 record.coverageId()，就会写出一条
            //    "审计指向一个 band_daily_coverage 里查不到的 id"的记录：
            //      · 依 coverageId 反查"这一天的审计证据" ⇒ 查不到；
            //      · 按 target_id 统计"这一天被刷了几次" ⇒ 每次算到不同的 id 上，
            //        计数被摊薄，而 §2.8.7③「节流」的效果就不可观测。
            //    ⇒ 故读一次**库层权威值**（同一事务内，见仓储 readStoredCoverageId 注释）。
            UUID storedCoverageId = ledger.readStoredCoverageId(
                    record.tenantId(), record.deviceId(), record.date());

            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    outcome.firstTime() ? ACTION_COVERAGE_REGISTERED : ACTION_COVERAGE_REFRESHED,
                    TARGET_COVERAGE,
                    storedCoverageId.toString(),
                    Instant.now(),
                    coveragePayload(record, outcome),
                    null, null);

            String auditId = appendAuditOrFail(entry, "逐日覆盖登记");

            log.info("[BAND-COVERAGE] tenant={} coverage={} storedCoverage={} device={} customer={} date={}"
                            + " flag={} reason={} isWear={} mode={} chargeable={}"
                            + " operator={} auditId={}",
                    record.tenantId(), record.coverageId(), storedCoverageId, record.deviceId(),
                    record.customerId(), record.date(), record.coverageFlag(),
                    record.gapReason() == null ? null : record.gapReason().code(),
                    record.isWear() == null ? null : record.isWear().code(),
                    outcome.name(), record.chargeableByGapReason(), operator, auditId);

            return new CoverageResult(outcome, auditId, record.coverageId(), storedCoverageId,
                    record.customerId(), record.date(), record.chargeableByGapReason());
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 读回一整条探针（本租户内不可见时 {@code null}）。 */
    public SyncProbeSnapshot probeSnapshotOf(String tenantId, UUID probeId) {
        return ledger.probeSnapshotOf(tenantId, probeId);
    }

    /** 该设备该路历史在本租户内的最新探针（无则 {@code null}）。 */
    public SyncProbeSnapshot latestProbeOf(String tenantId, UUID deviceId, HistoryMetric metric) {
        return ledger.latestProbeOf(tenantId, deviceId, metric);
    }

    /** 读回一整行逐日覆盖（本租户内不可见时 {@code null}）。 */
    public DailyCoverageSnapshot coverageSnapshotOf(String tenantId, UUID deviceId, LocalDate date) {
        return ledger.coverageSnapshotOf(tenantId, deviceId, date);
    }

    /** 该租户内探针行数。🛑 单独不足以作为验收口径 —— 见仓储同名方法注释。 */
    public int countProbesInTenant(String tenantId) {
        return ledger.countProbesInTenant(tenantId);
    }

    /** 该租户内逐日覆盖行数。🛑 口径与限制同上。 */
    public int countCoverageInTenant(String tenantId) {
        return ledger.countCoverageInTenant(tenantId);
    }

    /**
     * 🛑 <b>扫出本租户内所有违反核心门禁的覆盖行</b> —— 本域唯一的坏数据发现入口。
     *
     * <p>暴露到 Service 层是为了让"本迁移的验收口径"能被上层直接问出来：
     * 不是"库里有几行"，而是"<b>库里有没有一行把设备故障记成客户没戴</b>"。
     * 见 {@link com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageSnapshot#violatesNotWornGate()}
     * 与仓储同名方法的注释（三条"为什么写侧拦过、读侧还要能扫"的理由）。
     */
    public List<DailyCoverageSnapshot> scanNotWornGateViolations(String tenantId) {
        return ledger.scanNotWornGateViolations(tenantId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator, String what) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    what + "必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。这两张表是 A3「观测率」的直接取值来源，"
                            + "而 A3 进「依从性」维度、并进一步影响 AS_refund："
                            + "一次无主的写入，等于一次事后无从申诉的扣分依据");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与 {@code DeviceService} / {@code CaseArchiveService} / {@code AgreementService}
     * 同一取舍，且本类的理由最直接落在客户身上：
     * 若允许降级，会出现"逐日覆盖存在但没有覆盖记录"——
     * 而那时连"这一天是谁判的"都不可答。
     * <b>对本域而言这不是一条审计缺口，而是一份扣分依据丢掉了它的来源</b>，
     * 故此处不回退为"写入继续、日志降级"。
     */
    private String appendAuditOrFail(AuditLog entry, String what) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行" + what + "。"
                            + "band_sync_probe / band_daily_coverage 是 A3「观测率」的直接取值来源，"
                            + "而 A3 进「依从性」维度、并进一步影响 AS_refund。"
                            + "一次没有审计的写入无法事后补证『这一天是按哪次同步、哪个 N 判出来的』，"
                            + "而客户被错扣的申诉恰恰要回到这个起点。"
                            + "故此处不回退为『写入继续、日志降级』");
        }
        return svc.append(entry);
    }

    /**
     * 探针审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <h2>🛑 本域的克制原则</h2>
     * {@code audit_log} 是<b>无 RLS、全租户可读</b>的
     * （见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 故这里只写<b>标识与模式</b>：{@code probe_id} / {@code device_id}（都是 UUID）
     * + 是哪个历史指标 + N 与它的来源 + 本次走的是哪条路径。
     * <p>🛑 <b>为什么不写 {@code valid_dates_json}</b>：它是"设备在哪些日子还有数据"
     * 这一事实的<b>原始证据</b>，属客户级数据细节。审计只需回答"有这么一次探测"，
     * 不需要回答"这台设备的具体可用日期是哪几天"——后者要查
     * {@code band_sync_probe} 表本身，那里有 RLS 保护。
     * 把证据内容复制到无 RLS 的表里，等于给 RLS 开了一条旁路。
     * <p>🛑 {@code nUnverified} 这个布尔可以入审计（它不含业务内容）：
     * 它是 §2.8.7⑥「未取得前一律按『未缓解』记账」这条纪律的
     * <b>可检索落点</b> —— "本系统里有多少条 N 是未取证的"要靠它答。
     */
    private static String probePayload(SyncProbeRecord r, SyncProbeOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"probeId\":\"" + r.probeId()
                + "\",\"deviceId\":\"" + r.deviceId()
                + "\",\"historyType\":\"" + r.historyType().code()
                + "\",\"retentionDays\":" + r.retentionDays()
                + ",\"probeSource\":" + quote(r.probeSource() == null ? null : r.probeSource().code())
                + ",\"isTest\":" + r.isTest()
                + ",\"nUnverified\":" + r.nIsUnverified()
                + ",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /**
     * 逐日覆盖审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <h2>🛑 本域的克制原则比探针侧更严：{@code gap_reason} 与 {@code is_wear} 入审计的理由</h2>
     * 它们<b>看起来像内容</b>（"这一天为什么没数据"），但它们不是客户信息 ——
     * 它们是<b>枚举值</b>，取值空间只有 7 个与 4 个。而它们的告警价值极高：
     * <ul>
     *   <li>{@code gapReason = not_worn} ⇒ <b>这一行可能扣客户的分</b>，
     *       这是本域最需要被事后检索的一类写入；</li>
     *   <li>{@code isWear} ⇒ 它与 {@code gapReason} 的<b>组合</b>就是核心门禁的判据 ——
     *       审计里留下这两个值，使"这一次写入当时的两侧取值"可被复核，
     *       而不必去猜（库里的行后来可能被覆盖刷新过）。</li>
     * </ul>
     * <p>🛑 <b>为什么不写 {@code wearMinutes} / {@code effectiveWearMinutes}</b>：
     * 时长是<b>客户级行为数据</b>（"他每天戴几个小时"），
     * 属可反推个体作息的信息 —— 与 {@code DeviceService} 不写
     * {@code param_template_id}（经营信息）是同一条纪律，但本域的内容更敏感。
     * 要查时长应查 {@code band_daily_coverage} 表本身。
     * <p>🛑 <b>为什么不写 {@code nAtThatTime} 的值</b>：N 本身不是秘密，
     * 但"这一天按哪个 N 补拉的"是<b>可反推补拉窗口</b>的组合信息 ——
     * 而 N 属未取证的外部依赖（§2.8.7⑥）。改为记录一个布尔
     * {@code nRecorded}（"这次写入有没有留 N"），既保住可核对性、又不暴露窗口值。
     * 窗口值要查表本身（有 RLS 保护）。
     */
    private static String coveragePayload(DailyCoverageRecord r, DailyCoverageOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"coverageId\":\"" + r.coverageId()
                + "\",\"deviceId\":\"" + r.deviceId()
                + "\",\"customerId\":\"" + r.customerId()
                + "\",\"date\":\"" + r.date()
                + "\",\"coverageFlag\":" + r.coverageFlag()
                + ",\"gapReason\":" + quote(r.gapReason() == null ? null : r.gapReason().code())
                + ",\"isWear\":" + (r.isWear() == null ? null : r.isWear().code())
                + ",\"chargeable\":" + r.chargeableByGapReason()
                + ",\"nRecorded\":" + (r.nAtThatTime() != null)
                + ",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /** JSON 字符串值：{@code null} → 字面 {@code null}；否则带引号。 */
    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /**
     * 探针登记结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次 N 留痕的审计证据是哪一条"）。回执存在的唯一理由就是对账，
     * 故它必须是库里真实的那个 id（见类注释「为什么审计条目 id 必须取自返回值」）。
     *
     * <p>🛑 {@code nUnverified} 随回执一起返回<b>而不是让调用方自己算</b>：
     * 补拉引擎的下一步是"用这个 N 决定回溯窗口"，
     * 而"这个 N 能不能用"是本域唯一必须被回答的问题（§2.8.7⑥）。
     * 若让调用方自己从 {@code record} 上再算一遍，
     * 两处口径会各自漂移 —— 而漂移的表现是<b>引擎用了一条本类认为未取证的 N</b>，不报错。
     */
    public record ProbeResult(SyncProbeOutcome outcome, String auditId,
                              UUID probeId, UUID deviceId, boolean nUnverified) {

        /** 本次调用是否真的落了一条新的探针留痕。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }

        /** 是否属于幂等重放（留痕未新增，但这次调用已被审计留痕）。 */
        public boolean replayed() {
            return outcome.idempotentReplay();
        }
    }

    /**
     * 逐日覆盖登记结果。
     *
     * <p>🛑 回执里同时带 {@code coverageId} 与 {@code customerId} 与 {@code date}：
     * 调用方（补拉引擎）需要它们记录"这一天已处理"（§2.8.7③ 的 {@code date_done}）。
     * 若只回 {@code outcome}，调用方就得自己记住这三个值，
     * 而"用自己记的值去更新"与"用回执里的值去更新"在一次重放里可能不同 ——
     * 前者是本地变量，后者是库层确认过的。
     *
     * <p>🛑🛑 <b>两个 id 并存是本回执的靶心，别把它们合并</b>：
     * <table border="1">
     *   <caption>一次覆盖里两个 coverage_id 的语义对照</caption>
     *   <tr><th></th><th>{@code coverageId}</th><th>{@code storedCoverageId}</th></tr>
     *   <tr><td>是什么</td><td><b>本次意图</b>的编号（调用方传进来的）</td>
     *       <td><b>库里那一行</b>的身份（upsert 之后实际保留的）</td></tr>
     *   <tr><td>{@code CREATED} 时</td><td>两者<b>相等</b></td><td>两者<b>相等</b></td></tr>
     *   <tr><td>{@code UPDATED} 时</td><td>本次传入的新 id</td>
     *       <td>🛑 <b>首次插入</b>那次的 id（V22 (7)：{@code coverage_id} 属"不更新的列"）</td></tr>
     *   <tr><td>谁该用它</td><td>日志 / 埋点（"这一次调用是谁发起的"）</td>
     *       <td>🛑 <b>审计 {@code target_id}</b> / 任何"按 id 反查库中那一行"的用途</td></tr>
     * </table>
     * <p>🛑 为什么必须两个都回、而不是只回 {@code storedCoverageId}：
     * "<b>本次传的 id 与库里留的 id 不一致</b>"这件事本身就是一条诊断信号 ——
     * 它说明"这是一次覆盖，不是插入"。调用方（补拉引擎）据此可以判断
     * "这一天我之前已经处理过"，而那正是 §2.8.7③ {@code date_done} 需要的判断。
     * 若只回一个，"覆盖"与"插入"在回执上就没有可区分的痕迹。
     * <p>⚠️ 登记待裁：把两者合并成一个字段是<b>已被识别并拒绝</b>的简化
     * （合并后审计会指向不存在的 id —— 本回执的初版就是这样，由门禁判据 ③ 抓出）。
     *
     * <p>🛑 {@code chargeable} 是 {@link DailyCoverageRecord#chargeableByGapReason()}
     * 的<b>转发</b>（不在本处重算）：见类注释"为什么不能在两处各判一次"。
     * 它的用途是让调用方<b>在日志与埋点里能直接看到"这一行扣分了吗"</b> ——
     * 而这是本域唯一有能力伤害客户的那一位。
     */
    public record CoverageResult(DailyCoverageOutcome outcome, String auditId,
                                 UUID coverageId, UUID storedCoverageId,
                                 UUID customerId, LocalDate date,
                                 boolean chargeable) {

        /** 本次调用是否<b>真的改动了数据</b> —— 🛑 本域两态都为 {@code true}。 */
        public boolean mutated() {
            return true;
        }

        /** 是否属于"这一天首次进入台账"。 */
        public boolean firstTime() {
            return outcome.firstTime();
        }

        /** 是否属于"重拉刷新"（§2.8.7③ 节流的可观测面）。 */
        public boolean refreshed() {
            return outcome.refreshed();
        }
    }
}