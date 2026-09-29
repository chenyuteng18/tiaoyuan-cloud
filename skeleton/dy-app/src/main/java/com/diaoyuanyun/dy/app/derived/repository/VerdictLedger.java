package com.diaoyuanyun.dy.app.derived.repository;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceState;
import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictPort;
import com.diaoyuanyun.dy.app.derived.domain.VerdictRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 判定链（{@code cycle_assessment} + {@code verdict}）的 JDBC 仓储（V5 建表 · V8 两阶段对齐）。
 *
 * <h2>🛑 本类有【恰好一个】UPDATE —— 它是被架构预留的那一个</h2>
 * 与 {@code RefundReceiptLedger} 同一套论证（append-only 账本），
 * 但本类额外持有一条 PRD 逐字依据：§C.1.9 硬约束③
 * 「版本不可覆盖：题库、方案、判定（{@code verdict.threshold_version}）、
 * 文书模板四处<b>只可新增版本，不可原地覆盖</b>」。
 *
 * <p>🛑 V8 起本类新增 {@link #ATTACH_JUDGMENT_SQL}（{@link #attachJudgment}）——
 * 这是契约 C4/F1 两阶段拆分的唯一可实现形态：C4 先落 {@code cycle_assessment}
 * 一行（{@code verdict IS NULL}），F1 再把<b>判定侧四列</b>补上。
 * 它<b>不是</b>对"不可覆盖"的放宽，理由是一条具体的机械保证 ——
 * WHERE 谓词恒为 {@code cycle_id = ? AND verdict IS NULL}：
 * <b>已判定的行影响 0 行</b>，服务层据此拒绝。故硬约束③收窄为
 * 「只允许'未判定 → 已判定'这一次单向迁移，想改结论只能重开一次判定」。
 *
 * <p>三道防线各守一件事（与 V8 前同构，只是防线 2 的形态更新了）：
 * <ol>
 *   <li><b>本类 SQL 列清单</b>：{@link #ATTACH_JUDGMENT_SQL} 只允许写
 *       {@code verdict / effect_verdict / improvement_rate / metric_snapshot / updated_at}
 *       五列 —— <b>评估侧事实一列都进不去</b>（F1 不可能改写 C4 已落的依据）；</li>
 *   <li><b>本类 INSERT 列清单</b>：{@code cycle_assessment} 与 {@code verdict}
 *       的 INSERT <b>都不含</b> {@code updated_at}（只有 {@link #ATTACH_JUDGMENT_SQL} 写它）；</li>
 *   <li><b>门禁</b>：{@code VerdictPersistenceBoundaryTest} 反射扫描端口方法名
 *       （出现第二个 {@code update/delete/upsert/merge} 即红）+ 扫描 SQL 字面量
 *       （{@code DELETE/MERGE/ON CONFLICT/TRUNCATE/DROP} 一律红）。</li>
 * </ol>
 *
 * <h2>🛑 {@code visible_to_customer} 显式绑 {@code false}</h2>
 * V5 的列定义是 {@code BOOLEAN NOT NULL DEFAULT FALSE CHECK (visible_to_customer = FALSE)}。
 * 本类<b>显式</b>把它写进列清单并绑 {@code false}，而<b>不</b>省略该列让 DEFAULT 生效。
 * 理由见 {@link VerdictRow}：显式绑定后，把常量改成 {@code true} 会被库层 CHECK
 * 立刻拒成 23514（一次构建期/测试期就能抓到的失败）；省略列则把可攻击面
 * 挪到"改 DEFAULT"，那需要一次迁移。
 *
 * <h2>🛑 写入路径的 {@code created_at} 由调用方显式给定</h2>
 * {@code cycle_assessment.created_at} 表定义是 {@code DEFAULT now()}，
 * 但本类<b>显式</b>把它绑成 {@code row.recordedAt()}。理由是一条具体的纪律：
 * 判定链的取证价值建立在"评估提交 → 判定落库"的先后关系上，
 * 而两个动作若各取各的 {@code now()}，跨机时钟漂移会让这个先后关系倒挂。
 * 用一个调用方给定的时刻贯穿两个动作，是这个链上唯一不会漂移的做法。
 *
 * <h2>隔离机制（与 {@code RefundWorkOrderLedger} 同一套）</h2>
 * 每次操作开<b>短事务</b> + {@code SET LOCAL app.tenant_id}；不在 SQL 里写
 * {@code WHERE tenant_id = ?} —— 隔离完全由 V5 的 {@code FORCE ROW LEVEL SECURITY}
 * 承担（ADR-02）。租户 ID 经 {@link #UUID_PATTERN} 白名单后拼接
 * （{@code SET} 不支持绑定参数）。
 *
 * <h2>🛑 为什么"按客户取历史"要写成一条 JOIN，而不是两次查询</h2>
 * {@code verdict} 表没有 {@code customer_id} 列（它经 {@code cycle_id} 关联）。
 * 若拆成"先查周期、再逐个查判定"，N 次查询之间会有 N 个事务边界 ——
 * 两次之间客户可能又产生一次判定，于是返回的历史是"多个时点拼出来的"，
 * 而判定历史要用于协商举证。故 {@link #findVerdictsByCustomer} 用一条
 * {@code JOIN} 完成，使整份历史来自<b>同一时点</b>的快照。
 *
 * <h2>🛑 三个可空枚举解析为什么是 {@code public static}</h2>
 * 它们（{@link #parseEffectOrNull} / {@link #parseAdherenceOrNull} / {@link #parseRiskOrNull}）
 * 是纯函数 —— 输入一个列值、输出一个枚举或 null，不碰连接、不碰事务、无副作用。
 * 而它们承载的是<b>缺口 ② 唯一被登记的应用层防线</b>（{@code risk_flag} 库层无 CHECK）。
 *
 * <p>反向验证暴露过一个具体事实：在测试执行它们之前，把 {@code parseRiskOrNull}
 * 的空值分支从 {@code null} 改成 {@code RiskFlag.NONE}（即把"未登记标签"
 * 静默改写为"无风险"、直接使 D4 该触发而不触发），跑全部判定链测试<b>仍然全绿</b>。
 * 一条没有被任何测试执行过的防线，只能在复盘时被人"读到"，不能被构建拦住。
 *
 * <p>故这里把可见性放宽到 {@code public}，代价为零（它们是纯函数，外部拿到也没有
 * 任何可破坏的不变式），换来的是 {@code VerdictPersistenceBoundaryTest} 能真的
 * 调用它们并断言 fail-closed。类似地，{@code VerdictController.contractBranchLiterals()}
 * 也因同一条理由改成了 public —— "测试的包路径由被测类的可见性决定"是一处
 * 持续制造麻烦的耦合，遇到一次就应当就地解开，而不是把断言降级为源码扫描。
 */
@Repository
public class VerdictLedger implements VerdictPort {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public VerdictLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、周期评估（cycle_assessment）：INSERT only
    // ==================================================================

    /**
     * 🛑 列清单里<b>没有</b> {@code updated_at}（写路径不产生"修改时间"）；
     * {@code created_at} <b>显式</b>绑定（见类注释：两动作共用一个时间源）。
     */
    private static final String INSERT_CYCLE_SQL =
            "INSERT INTO cycle_assessment ("
                    + " cycle_id, tenant_id, customer_id, sequence_no, as_value,"
                    + " as_dimensions_json, metric_snapshot, gap_days, verdict,"
                    + " effect_verdict, adherence_state, improvement_rate, module_scores,"
                    + " threshold_version, band_trend_note, created_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)";

    @Override
    public void insertCycleAssessment(String tenantId, CycleAssessmentRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "周期评估记录为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(row.cycleId().toString());
        requireUuid(row.customerId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_CYCLE_SQL,
                row.cycleId().toString(),
                row.customerId().toString(),
                row.sequenceNo(),
                row.asValue(),
                row.asDimensionsJson(),
                row.metricSnapshotJson(),
                row.gapDays(),
                row.branch() == null ? null : row.branch().dbLabel(),
                row.effectVerdict() == null ? null : row.effectVerdict().label(),
                row.adherenceState() == null ? null : row.adherenceState().label(),
                row.improvementRate(),
                row.moduleScoresJson(),
                row.thresholdVersion(),
                row.bandTrendNote(),
                ts(row.recordedAt()),
                row.createdBy()));
    }

    private static final String SELECT_CYCLE_SQL =
            "SELECT cycle_id, customer_id, sequence_no, as_value, as_dimensions_json,"
                    + " metric_snapshot, gap_days, verdict, effect_verdict, adherence_state,"
                    + " improvement_rate, module_scores, threshold_version, band_trend_note,"
                    + " created_at, created_by "
                    + "  FROM cycle_assessment WHERE cycle_id = ?::uuid";

    @Override
    public CycleAssessmentRow findCycleAssessment(String tenantId, UUID cycleId) {
        requireUuid(tenantId);
        requireNonNull(cycleId, "周期评估主键");
        requireUuid(cycleId.toString());
        List<CycleAssessmentRow> rows = inTenant(tenantId, () -> jdbc.query(
                SELECT_CYCLE_SQL, cycleMapper(), cycleId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按客户取全部周期评估（判定依据侧）。
     *
     * <p>🛑 它与 {@link #findVerdictsByCustomer} 的必要性成对：
     * {@code verdict.confidence} 是 {@code NOT NULL CHECK 0–1}，而 {@code s = 不可比}
     * 时置信度是 {@code null}（不是 0.000）⇒ 该态下没有可写的判定行 ⇒
     * "挂起轮次"只有 {@code cycle_assessment} 这一侧留痕。
     * 若 F2 只读 {@code verdict}，这类轮次会在历史里静默消失 —— 见
     * {@link VerdictPort#findCycleAssessmentsByCustomer} 的说明。
     *
     * <p>排序列与判定历史同族（先时间、再主键）：
     * 只按时间排时，同一时刻分批写入的轮次顺序不确定，会让"第 N 次"读成乱序。
     */
    private static final String SELECT_CYCLES_BY_CUSTOMER_SQL =
            "SELECT cycle_id, customer_id, sequence_no, as_value, as_dimensions_json,"
                    + " metric_snapshot, gap_days, verdict, effect_verdict, adherence_state,"
                    + " improvement_rate, module_scores, threshold_version, band_trend_note,"
                    + " created_at, created_by "
                    + "  FROM cycle_assessment WHERE customer_id = ?::uuid"
                    + " ORDER BY created_at, cycle_id";

    @Override
    public List<CycleAssessmentRow> findCycleAssessmentsByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireNonNull(customerId, "客户标识");
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_CYCLES_BY_CUSTOMER_SQL, cycleMapper(), customerId.toString()));
    }

    /** 周期评估行映射（两处读取共用 —— 与 {@link #verdictMapper()} 同一条理由）。 */
    private static org.springframework.jdbc.core.RowMapper<CycleAssessmentRow> cycleMapper() {
        return (rs, i) -> new CycleAssessmentRow(
                rs.getObject("cycle_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getInt("sequence_no"),
                rs.getBigDecimal("as_value"),
                rs.getString("as_dimensions_json"),
                rs.getString("metric_snapshot"),
                (Integer) rs.getObject("gap_days"),
                // 🛑 V8 起 verdict 列可空：null = "判定尚未发生"（阶段一）。
                //    故用 parseBranchOrNull 而不是 parse ——
                //    VerdictBranch.parse(null) 会抛（视 null 为"必填未给"），
                //    那会让一条完全正常的「C4 已提交、待判定」记录在读回时炸掉。
                //    非空值仍走 parse（未知字面必须报错，不得静默变成"未判定"）。
                parseBranchOrNull(rs.getString("verdict")),
                // 三个枚举列在库里【可空】（挂起时合法为空），故用 parseOrNull：
                // 直接把 null 交给 parse 会让一条"挂起"的合法记录在读回时炸掉。
                parseEffectOrNull(rs.getString("effect_verdict")),
                parseAdherenceOrNull(rs.getString("adherence_state")),
                rs.getBigDecimal("improvement_rate"),
                rs.getString("module_scores"),
                rs.getString("threshold_version"),
                rs.getString("band_trend_note"),
                toInstant(rs.getTimestamp("created_at")),
                rs.getString("created_by"));
    }

    // ==================================================================
    // 一′、周期评估的**受控补全**（C4 → F1 的两阶段第二阶段）
    // ==================================================================

    /**
     * 🛑 本类<b>唯一</b>的 UPDATE —— 列清单与 WHERE 谓词即全部防线。
     *
     * <pre>
     *   SET verdict / effect_verdict / improvement_rate / metric_snapshot / updated_at
     *   WHERE cycle_id = ? AND <b>verdict IS NULL</b>
     * </pre>
     * <ul>
     *   <li><b>列清单</b>：只有"判定侧四列 + updated_at"。评估侧事实
     *       （{@code as_value} / {@code as_dimensions_json} / {@code gap_days} /
     *       {@code adherence_state} / {@code module_scores} / {@code threshold_version} /
     *       {@code band_trend_note}）<b>一列都不能进</b> —— 否则 F1 就能改写 C4 已落的依据。</li>
     *   <li><b>WHERE 谓词</b> {@code verdict IS NULL}：已判定过的行影响 0 行 ⇒
     *       硬约束③「不可原地覆盖」仍成立。想改已作出的结论只能重开一次判定。</li>
     *   <li><b>影响行数必须恰为 1</b>：0 行 = 该周期已判定过（或不存在，或跨租户不可见）；
     *       由服务层转成明确的业务错误，而不是静默成功。</li>
     * </ul>
     */
    private static final String ATTACH_JUDGMENT_SQL =
            "UPDATE cycle_assessment"
                    + "   SET verdict = ?, effect_verdict = ?, improvement_rate = ?,"
                    + "       metric_snapshot = ?::jsonb, updated_at = ?"
                    + " WHERE cycle_id = ?::uuid AND verdict IS NULL";

    @Override
    public void attachJudgment(String tenantId, UUID cycleId,
                               VerdictBranch branch, EffectVerdict effectVerdict,
                               java.math.BigDecimal improvementRate,
                               String metricSnapshotJson, Instant judgedAt) {
        requireUuid(tenantId);
        requireNonNull(cycleId, "周期评估主键");
        requireUuid(cycleId.toString());
        requireNonNull(branch, "判定分支（verdict）");
        requireNonNull(metricSnapshotJson, "判定依据快照（metric_snapshot）");
        requireNonNull(judgedAt, "判定时刻（updated_at）");

        int affected = inTenant(tenantId, () -> jdbc.update(ATTACH_JUDGMENT_SQL,
                branch.dbLabel(),
                effectVerdict == null ? null : effectVerdict.label(),
                improvementRate,
                metricSnapshotJson,
                ts(judgedAt),
                cycleId.toString()));

        if (affected == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "该周期评估已存在判定结论（或不存在），拒绝再次补全: cycle_id=" + cycleId
                            + "。🛑 PRD §C.1.9 硬约束③：判定不可原地覆盖 —— "
                            + "本次写入的 WHERE 谓词是 `verdict IS NULL`，"
                            + "影响 0 行即说明该行已有结论。"
                            + "想改一个已作出的结论，唯一途径是【重开一次判定】（新的 cycle 行），"
                            + "而不是改写旧行");
        }
        if (affected > 1) {
            // 结构上不可能（cycle_id 是 PK），但断言它可让"有人给这表加了非唯一索引/改了主键"
            // 这类结构性破坏在这里立刻暴露，而不是留下两行不一致的依据
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "补全判定影响了 " + affected + " 行 —— cycle_id 是主键，最多只应有 1 行。"
                            + "这是一次表结构破坏：可能有重复主键或视图替代了基表");
        }
    }

    // ==================================================================
    // 二、判定（verdict）：INSERT only
    // ==================================================================

    /**
     * 🛑 {@code visible_to_customer} 在列清单里、绑常量 {@code false}（见类注释）。
     * 🛑 列清单里<b>没有</b> {@code updated_at}。
     * 🛑 V8 起列清单含 {@code disposition}（组合出口，PRD §C.1.7 L1794）。
     */
    private static final String INSERT_VERDICT_SQL =
            "INSERT INTO verdict ("
                    + " verdict_id, tenant_id, cycle_id, branch, confidence,"
                    + " evidence_snapshot, threshold_version, decided_at,"
                    + " effect_verdict, adherence_state, risk_flag, disposition,"
                    + " visible_to_customer, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, FALSE, ?)";

    @Override
    public void insertVerdict(String tenantId, VerdictRow row) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "判定记录为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(row.verdictId().toString());
        requireUuid(row.cycleId().toString());

        inTenant(tenantId, () -> jdbc.update(INSERT_VERDICT_SQL,
                row.verdictId().toString(),
                row.cycleId().toString(),
                row.branch().dbLabel(),
                // 🛑 V8 起本列可空：null = "不可判"（挂起态）。可直接绑 null，
                //    驱动会写 SQL NULL（与"省略该列"不同 —— 后者会让 DEFAULT 生效）。
                row.confidence(),
                row.evidenceSnapshotJson(),
                row.thresholdVersion(),
                ts(row.decidedAt()),
                row.effectVerdict() == null ? null : row.effectVerdict().label(),
                row.adherenceState() == null ? null : row.adherenceState().label(),
                // 🛑 risk_flag 库层 V8 起有 CHECK（纵深防御第二层），
                //    应用层仍是第一层（读回时 fail-closed）。
                row.riskFlag() == null ? null : row.riskFlag().dbLabel(),
                // 🛑 V8 新增列：组合出口。null 表示"未推出"（挂起态合法）。
                row.disposition() == null ? null : row.disposition().dbLabel(),
                row.createdBy()));
    }

    /**
     * 列清单不含 {@code visible_to_customer} / {@code updated_at} / {@code tenant_id}：
     * 前者是恒 false 的内部列（不下发即不需要读回）、中者无意义、后者由 RLS 承担隔离。
     */
    private static final String SELECT_VERDICT_SQL =
            "SELECT verdict_id, cycle_id, branch, confidence, evidence_snapshot,"
                    + " threshold_version, decided_at, effect_verdict, adherence_state,"
                    + " risk_flag, disposition, created_by "
                    + "  FROM verdict WHERE verdict_id = ?::uuid";

    @Override
    public VerdictRow findVerdict(String tenantId, UUID verdictId) {
        requireUuid(tenantId);
        requireNonNull(verdictId, "判定主键");
        requireUuid(verdictId.toString());
        List<VerdictRow> rows = inTenant(tenantId, () -> jdbc.query(
                SELECT_VERDICT_SQL, verdictMapper(), verdictId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 🛑 <b>一条 JOIN 出全部历史</b>（见类注释：不做 N+1）。
     *
     * <p>JOIN 的键是 {@code verdict.cycle_id = cycle_assessment.cycle_id}，
     * 筛选条件是后者的 {@code customer_id}。RLS 对<b>两张表</b>都生效，
     * 故 JOIN 不会让隔离出现缺口 —— 两边的 {@code USING} 子句都要求
     * {@code tenant_id = current_setting('app.tenant_id')}，跨租户行在任一侧都不可见。
     *
     * <p>排序键 {@code decided_at, verdict_id} 显式给出：判定历史要用于协商举证，
     * 顺序倒置会让"先稳定后无改善"读成"先无改善后稳定"。
     */
    private static final String SELECT_VERDICTS_BY_CUSTOMER_SQL =
            "SELECT v.verdict_id, v.cycle_id, v.branch, v.confidence, v.evidence_snapshot,"
                    + " v.threshold_version, v.decided_at, v.effect_verdict, v.adherence_state,"
                    + " v.risk_flag, v.disposition, v.created_by "
                    + "  FROM verdict v"
                    + "  JOIN cycle_assessment c ON c.cycle_id = v.cycle_id"
                    + " WHERE c.customer_id = ?::uuid"
                    + " ORDER BY v.decided_at, v.verdict_id";

    @Override
    public List<VerdictRow> findVerdictsByCustomer(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireNonNull(customerId, "客户标识");
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_VERDICTS_BY_CUSTOMER_SQL, verdictMapper(), customerId.toString()));
    }

    private static final String SELECT_VERDICTS_BY_CYCLE_SQL =
            "SELECT verdict_id, cycle_id, branch, confidence, evidence_snapshot,"
                    + " threshold_version, decided_at, effect_verdict, adherence_state,"
                    + " risk_flag, disposition, created_by "
                    + "  FROM verdict WHERE cycle_id = ?::uuid"
                    + " ORDER BY decided_at, verdict_id";

    @Override
    public List<VerdictRow> findVerdictsByCycle(String tenantId, UUID cycleId) {
        requireUuid(tenantId);
        requireNonNull(cycleId, "周期评估主键");
        requireUuid(cycleId.toString());
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_VERDICTS_BY_CYCLE_SQL, verdictMapper(), cycleId.toString()));
    }

    /** 判定行映射（三处读取共用，避免"三份抄写必然分叉"）。 */
    private static org.springframework.jdbc.core.RowMapper<VerdictRow> verdictMapper() {
        return (rs, i) -> new VerdictRow(
                rs.getObject("verdict_id", UUID.class),
                rs.getObject("cycle_id", UUID.class),
                // 🛑 branch 库层 NOT NULL 且有 CHECK，读到空即数据损坏 → parse 抛正是想要的
                VerdictBranch.parse(rs.getString("branch")),
                rs.getBigDecimal("confidence"),
                rs.getString("evidence_snapshot"),
                rs.getString("threshold_version"),
                toInstant(rs.getTimestamp("decided_at")),
                parseEffectOrNull(rs.getString("effect_verdict")),
                parseAdherenceOrNull(rs.getString("adherence_state")),
                // 🛑 risk_flag V8 起库层有 CHECK（第二层），但应用层这一行仍是第一层：
                //    回落成 NONE 会让一个未登记标签变成"无风险"，使 D4 该触发而不触发。
                parseRiskOrNull(rs.getString("risk_flag")),
                // 🛑 V8 新增列：组合出口。可空（未推出），故用 parseOrNull 形态。
                parseDispositionOrNull(rs.getString("disposition")),
                rs.getString("created_by"));
    }

    // ==================================================================
    // 三、可空枚举的解析（三处共用的唯一实现）
    // ==================================================================

    /**
     * {@code effect_verdict} 在库里可空（挂起时合法为空）。
     *
     * <p>把 {@code null} 交给 {@code EffectVerdict.parse} 会抛（因为它对 null 视为"必填未给"），
     * 于是<b>一条完全正常的挂起判定</b>会在读回时炸掉。本方法把"空"显式转成 {@code null}，
     * 而让非空值仍然走 parse（未知字面必须报错，不得静默变成"未定"）。
     */
    public static EffectVerdict parseEffectOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : EffectVerdict.parse(raw);
    }

    /** {@code adherence_state} 在库里可空（未算时为空），处理同 {@link #parseEffectOrNull}。 */
    public static AdherenceState parseAdherenceOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : AdherenceState.parse(raw);
    }

    /**
     * {@code risk_flag} 在库里可空<b>且无 CHECK</b>，处理同 {@link #parseEffectOrNull}。
     *
     * <p>🛑 这一处尤其要紧：因为库层没有值域约束，"读回时解析"是唯一的防线。
     * 回落成 {@code NONE} 会把一个未登记的临床标签静默改写为"无风险标签"，
     * 从而让 D4（全面评估）该触发而不触发 —— 正是 PRD §7.3 定调句要防的那类事故。
     */
    public static RiskFlag parseRiskOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : RiskFlag.parse(raw);
    }

    /**
     * {@code verdict}（{@code cycle_assessment.verdict} 列）在 V8 起可空
     * （{@code null} = 判定尚未发生，即 C4 阶段一），处理同 {@link #parseEffectOrNull}。
     *
     * <p>🛑 这是本类里<b>唯一</b>一处"可空"直接对应业务阶段而非数据缺口的解析：
     * 其余三个可空枚举列的空值含义都是"该值未算/未录"，而这一列的空值含义是
     * <b>"这一行还没走到判定"</b> —— 它本身就是一个完整的业务状态。
     *
     * <p>回落成 {@link VerdictBranch#HUMAN_REVIEW}（"人工复核"）尤其危险：
     * 它会把"这一轮还没判"静默写成"这一轮判了、结论是交给人"，
     * 而后者是一条<b>已作出的结论</b>，会进判定历史并参与协商举证。
     *
     * <p>反之回落成 {@code STABLE}（"稳定"）更加危险 —— 那等于系统替客户
     * 宣布"情况稳定"，而判定根本还没发生。故两处回落都不存在，
     * 非空值一律走 {@code parse}（未知字面必抛）。
     */
    public static VerdictBranch parseBranchOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : VerdictBranch.parse(raw);
    }

    /**
     * {@code disposition}（V8 新增列）在库里可空（未推出时为空），处理同上三者。
     *
     * <p>🛑 回落成 {@code KEEP_PLAN}（"继续原方案"）看似无害，实则危险：
     * 它会把"处置还没推出"静默写成"系统建议继续原方案" ——
     * 而后者是一条**结论**，会进协商举证。两者的区别正是挂起态语义的核心。
     */
    public static com.diaoyuanyun.dy.app.derived.domain.Disposition parseDispositionOrNull(String raw) {
        return (raw == null || raw.isBlank())
                ? null
                : com.diaoyuanyun.dy.app.derived.domain.Disposition.parse(raw);
    }

    // ==================================================================
    // 四、上下文（与 RefundWorkOrderLedger 同一套：短事务 + SET LOCAL）
    // ==================================================================

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "标识非合法 UUID，拒绝执行判定链 SQL（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    /**
     * {@code java.sql.Timestamp} → {@link Instant}（可空）。
     *
     * <p>🛑 直接写 {@code toInstant(rs.getTimestamp("created_at"))} 会得到 {@code Timestamp}，
     * 而 record 的字段类型是 {@code Instant} —— 两者<b>不</b>可隐式转换（编译期即红）。
     * 这一步转换在退款域 {@code RefundWorkOrderLedger.toInstant} 里已有同一实现；
     * 本类保留自己的那一份而不跨包复用，是因为它只有一行、
     * 而跨模块静态方法复用会把两个域的时间读取口径悄悄绑在一起
     * （任一侧将来改成 {@code getObject(..., OffsetDateTime.class)} 都会影响另一侧）。
     */
    static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}