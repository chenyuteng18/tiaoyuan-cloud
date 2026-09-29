package com.diaoyuanyun.dy.app.derived.controller;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.ReplayResult;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictRow;
import com.diaoyuanyun.dy.app.derived.service.ThresholdVersionReplay;
import com.diaoyuanyun.dy.app.derived.service.VerdictService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.web.idempotent.Idempotent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 契约域 F 的 <b>F1 / F2</b> 两个端点 —— 判定结论落库与判定历史。
 *
 * <h2>契约逐字（本类的唯一依据：{@code contract/openapi-v1.0.0.yaml} L888–931）</h2>
 * <pre>
 *   F1  POST /cycle-assessments/{id}/verdicts   x-callable-roles: [meridian, admin]
 *                                               x-client-forbidden: false
 *                                               （🛑 无 x-client-explicitly-denied）
 *   F2  GET  /customers/{id}/verdicts           x-callable-roles: [meridian, admin]
 *                                               x-client-forbidden: false
 *                                               x-client-explicitly-denied: true
 *                                               description 逐字「🔴 客户与调理师端一律 403 VISIBILITY_DENIED」
 * </pre>
 *
 * <h2>🛑 F1 与 F2 的可见性<b>不一样</b>，故本类<b>不能</b>贴类级 {@link StaffOnly}</h2>
 * 这是本类最容易写错的一处，且写错了不报错：
 * <ul>
 *   <li>F2 有 {@code x-client-explicitly-denied: true} ⇒ 客户调它一律 403，
 *       与参数无关（客户可以不带任何参数地 GET，入站字段扫描无从拦起）；</li>
 *   <li>F1 <b>没有</b>这个键 ⇒ 按契约字面，F1 不是"整只端点对客户关闭"。</li>
 * </ul>
 * 若把 {@code @StaffOnly} 贴到<b>类级</b>，F1 会连带变成"客户一律 403" ——
 * 那是一次<b>超出契约的收紧</b>。而这类收紧的形态很隐蔽：它让一个契约允许的调用
 * 变成 403，且因为 403 的语义是"权限不足"，调用方会去查自己的权限配置，
 * 而不是去查"服务端多贴了一层注解"。故：
 * <b>{@code @StaffOnly} 只贴在 F2 的方法上，F1 的方法上不贴。</b>
 *
 * <p>🛑 不贴 {@code @StaffOnly} <b>不等于</b> F1 对客户开放。客户的拒绝由
 * {@link VerdictService#requireCallable} 在前置处完成（契约 F1/F2 的
 * {@code x-callable-roles} 不含 {@code client}），且 {@code client} <b>不持有</b>
 * {@code verdict:write} 权限 ⇒ 还有第二层。三层防线各守一件事：
 * <ol>
 *   <li>{@link StaffOnly}（仅 F2）—— 契约级端点档位，标了 {@code x-client-explicitly-denied} 的才用；</li>
 *   <li>{@link VerdictService#requireCallable}（F1/F2 都调）—— {@code x-callable-roles} 的<b>业务角色</b>档位，
 *       客户与调理师在此显式点名拒绝；</li>
 *   <li>{@link RequirePermission}（F1/F2 都贴）—— <b>功能权限码</b>（岗位允不允许做这件事）。</li>
 * </ol>
 * 三者语义不同，不可互相替代。第 3 层尤其不能省：它对 {@code client} 不成立
 * （注册表刻意不登记 {@code client} 的任何码），故客户即使绕过第 2 层也会在这里被拦。
 *
 * <h2>🛑 出站严格限于契约 {@code VerdictData} 的六项</h2>
 * <pre>
 *   verdict_id / branch / confidence / evidence_snapshot / threshold_version / decided_at
 * </pre>
 * 其中 {@code branch} 的 enum 用<b>契约的五个中文字面</b>
 * （{@code 稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核}）——
 * {@link VerdictBranch#dbLabel()} 恰好与它们逐字相同（本域是"取哪个都一样"的少数派，
 * 与退款域 {@code entry} 的 {@code 'A 门店代录'} vs {@code 'A门店代录'} 相反），
 * 故这里用 {@code dbLabel()} 并<b>不</b>产生契约分叉。该等价关系由
 * {@code VerdictControllerContractTest} 逐值断言，避免它<b>将来</b>在某一侧被改坏。
 *
 * <p>🛑 快照里含 {@code decision_rule} / {@code disposition} / {@code schema_gap_note}
 * 等**内部**字段。契约的 {@code evidence_snapshot} 是一个 {@code type: object}
 * 且无 {@code properties} 声明 —— 即它是<b>不透明的依据快照</b>。
 * 本类<b>不</b>把它的内部键展开成顶层字段：一旦展开，
 * "内部处置"就会变成面客可见的字段（而 Q10 口径②把整个 {@code verdict} 定为对内证据链）。
 */
@RestController
@RequestMapping("/api/v1")
public class VerdictController {

    /** 计数自证：本控制器从<b>不</b>直接触达持久化层（架构守卫 R2 的运行时镜像）。 */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 契约 {@code VerdictData.branch} 的五个中文字面（逐字，与 {@link VerdictBranch#dbLabel()} 同值）。 */
    private static final List<String> CONTRACT_BRANCH_LITERALS =
            List.of("稳定", "依从不足", "达标无效", "全面评估", "人工复核");

    /**
     * F1 的入参白名单（回显给"请求未携带租户/角色"这类错误时用，也供自描述端点）。
     *
     * <p>它同时是一份<b>已登记的接口形状声明</b>：契约 F1 <b>没有</b> {@code requestBody}
     * 定义（未冻结入参），故本类按 {@code CreateVerdictRequest} 的字段名冻结下来，
     * 使"F1 到底收什么"有一个可断言的地方，而不是只能读 Java 源码。
     */
    static final List<String> F1_REQUEST_FIELDS = List.of(
            "customer_id", "sequence_no", "base_total", "current_total", "same_origin",
            "adherence", "risk_flag", "core_metric_improved", "confidence",
            "module_scores", "band_trend_note", "threshold_version");

    /**
     * 模块分缺省值 —— {@code {"M1":0,"M2":0,"M3":0,"M4":0,"M5":0}} 的 JSON。
     *
     * <h2>🛑 为什么它可以被兜底，而 {@code confidence} / {@code risk_flag} 不行</h2>
     * V5 的 {@code module_scores} 是 {@code JSONB NOT NULL}，故必须有一个值。
     * 而它兜底成"全 0"是<b>安全</b>的：模块分参与的是 D1 分支里"核心指标改善"的
     * 人工录入判定（{@code core_metric_improved}），而后者缺省时<b>直接落 D5 挂起</b> ——
     * 即全 0 的模块分<b>不会单独产生任何结论</b>。
     * 反观 {@code risk_flag}：兜成"无"会让 D4 该触发而不触发（安全信号被吞）；
     * {@code confidence}：兜成 0 会把"无从置信"说成"置信度最低"。故那两者一律不兜。
     */
    private static final String EMPTY_MODULE_SCORES =
            "{\"M1\":0,\"M2\":0,\"M3\":0,\"M4\":0,\"M5\":0}";

    private final VerdictService verdicts;
    private final ThresholdVersionReplay replay;

    public VerdictController(VerdictService verdicts, ThresholdVersionReplay replay) {
        this.verdicts = verdicts;
        this.replay = replay;
    }

    // ==================================================================
    // F1 POST /cycle-assessments/{id}/verdicts
    // ==================================================================

    /**
     * F1 请求体。
     *
     * <h2>🛑 契约未冻结本体的形状 —— 故字段名在此处冻结</h2>
     * 契约 F1 <b>只声明了响应</b>（{@code VerdictData}），没有 {@code requestBody}。
     * 这是一处<b>已登记的契约缺口</b>（判定入参无法从契约生成客户端）。
     * 本类按域内语义命名并以 {@code snake_case} 出参（与退款域 G1 同一约定 ——
     * Java 属性名是 camelCase，不显式 {@code @JsonProperty} 会让客户端按
     * {@code customer_id} 取值时拿到 {@code null}）。
     *
     * <h2>🛑 它<b>不</b>接收 {@code branch}</h2>
     * PRD P0-12 逐字：「判定出口 = {@code effect_verdict} × {@code adherence_state} ×
     * {@code risk_flag} 组合 → {@code disposition}，<b>不得混入单一 enum</b>」。
     * 若本体自带 {@code branch}，调用方就能直接指定结论 —— 那等于把判定权从
     * §7.3 决策表挪到了 HTTP 调用点。故本体的每一项都是<b>原始事实</b>，分支由服务端算。
     */
    public record CreateVerdictRequest(
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("sequence_no") Integer sequenceNo,
            @JsonProperty("base_total") Integer baseTotal,
            @JsonProperty("current_total") Integer currentTotal,
            @JsonProperty("same_origin") SameOriginBody sameOrigin,
            @JsonProperty("adherence") AdherenceBody adherence,
            @JsonProperty("risk_flag") String riskFlag,
            @JsonProperty("core_metric_improved") Boolean coreMetricImproved,
            @JsonProperty("confidence") ConfidenceBody confidence,
            @JsonProperty("module_scores") Map<String, Integer> moduleScores,
            @JsonProperty("band_trend_note") String bandTrendNote,
            @JsonProperty("threshold_version") String thresholdVersion) {
    }

    /**
     * 同源断言（对应 {@link EffectVerdictEngine.SameOriginAssert} 的三项）。
     *
     * <p>🛑 三项<b>全部缺省为 {@code false}</b>（不成立）—— 缺省即"不可比"，
     * 于是路由落 D5 挂起。这与"缺省为 true"的差别是一次真实的分叉：
     * 后者会在调用方忘记传同源信息时<b>假装测量可比</b>，从而算出一个不该算的结论。
     */
    public record SameOriginBody(
            @JsonProperty("same_item_group") Boolean sameItemGroup,
            @JsonProperty("range_matches") Boolean rangeMatches,
            @JsonProperty("same_measurer") Boolean sameMeasurer) {

        EffectVerdictEngine.SameOriginAssert toDomain() {
            return new EffectVerdictEngine.SameOriginAssert(
                    Boolean.TRUE.equals(sameItemGroup),
                    Boolean.TRUE.equals(rangeMatches),
                    Boolean.TRUE.equals(sameMeasurer));
        }
    }

    /** 依从四维（键为 {@code A1}/{@code A3}/{@code A4}；🛑 {@code A2} 永不参与 AS）。 */
    public record AdherenceBody(
            @JsonProperty("expected_days") Integer expectedDays,
            @JsonProperty("dimensions") Map<String, DimensionBody> dimensions) {
    }

    /** 单个依从维度。三个信号互斥且各有明确语义（见 {@link AdherenceEngine.DimensionInput}）。 */
    public record DimensionBody(
            @JsonProperty("applicable") Boolean applicable,
            @JsonProperty("value") BigDecimal value,
            @JsonProperty("structural_missing") Boolean structuralMissing) {

        /**
         * 🛑 三态的映射顺序即语义，不得调换，且<b>只能</b>用 {@link AdherenceEngine.DimensionInput}
         * 的三个工厂进入 —— 不要自己 {@code new}，因为它们的紧凑构造器会拦下两种真实错法：
         * <pre>
         *   applicable=true  且 value=null   → 抛（适用维度的值不得缺失，硬纪律 #4）
         *   applicable=false 且 value=0      → 抛（不适用 ≠ 0，两者混用会让 AS 静默偏移）
         * </pre>
         * <ol>
         *   <li>{@code applicable}={@code false} ⇒ 该维对本客户<b>不适用</b>（载体不存在）——
         *       它<b>退出</b>加权分母，引擎按剩余维度重归一，而<b>不是</b>记 0 分；
         *       {@code structural_missing} 进一步区分"结构性"（设备未适配）与"行为性"（客户未做），
         *       后者才记 0 分；</li>
         *   <li>{@code applicable}={@code true} 且 {@code value} 有值 ⇒ 正常取值；</li>
         *   <li>{@code applicable}={@code true} 且 {@code value} 缺失 ⇒ 行为性缺失（记 0 分）。</li>
         * </ol>
         * 🔴 把 1 与 3 弄反会让"设备没传数据"记成"客户没做"—— 而 PRD §C.1.9 逐字要求
         * 「未佩戴不记不利」，故这个区别直接决定结论是否对客户不利。
         */
        AdherenceEngine.DimensionInput toDomain() {
            if (Boolean.TRUE.equals(applicable)) {
                if (value == null) {
                    // 行为性缺失：客户可控制的行为未发生 ⇒ 记 0（这是唯一"扣分"的缺失）
                    return AdherenceEngine.DimensionInput.ofBehavioralMissing();
                }
                return AdherenceEngine.DimensionInput.ofApplicable(value);
            }
            // 不适用：载体不存在（结构性）或明确声明不适用。两种都不记 0。
            return Boolean.TRUE.equals(structuralMissing)
                    ? AdherenceEngine.DimensionInput.ofStructuralMissing()
                    : AdherenceEngine.DimensionInput.ofBehavioralMissing();
        }
    }

    /** 置信度四项因子（对应 {@link VerdictConfidenceEngine.ConfidenceInput}）。 */
    public record ConfidenceBody(
            @JsonProperty("same_origin_status") String sameOriginStatus,
            @JsonProperty("answered_count") Integer answeredCount,
            @JsonProperty("expected_days") Integer expectedDays,
            @JsonProperty("mcid_delta") Integer mcidDelta) {
    }

    /**
     * F1 判定结论落库（{@code POST /api/v1/cycle-assessments/{id}/verdicts}）。
     *
     * <h2>🛑 它上 {@code @Idempotent}，理由是具体的</h2>
     * 契约 §0 逐字：「写接口接受 {@code Idempotency-Key} 请求头（UUID）；
     * 服务端在 24h 窗口内去重」。F1 重复提交的后果<b>不是</b>"多一条记录"那么简单：
     * 它会在 {@code cycle_assessment} 与 {@code verdict} 各留一行，而这两张表
     * <b>都不可覆盖</b>（PRD §C.1.9 硬约束③）。于是"客户只有一次判定"这件事
     * 在证据链里永久变成两次 —— 而判定历史要用于协商举证，
     * 一次重复会把"第 2 次判定"整体后移一位，后续所有 {@code sequence_no} 的语义跟着错。
     *
     * <h2>🛑 路径参数 {@code id} 就是 {@code cycle_id}</h2>
     * 契约路径逐字是 {@code /cycle-assessments/{id}/verdicts}，且 schema
     * {@code verdict.cycle_id} 是 {@code NOT NULL REFERENCES cycle_assessment}。
     * 故这里的 {@code id} <b>只能</b>解释为周期评估主键 —— 不额外收一个
     * {@code cycle_id} 字段（两个来源会给"路径一个、体里另一个"的分叉留位置）。
     */
    @Idempotent
    @RequirePermission("verdict:write")
    @PostMapping("/cycle-assessments/{id}/verdicts")
    public Result<Object> createVerdict(@PathVariable("id") String id,
                                        @RequestBody CreateVerdictRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        // 契约 F1 的 x-callable-roles（客户与调理师在此显式点名拒绝）
        verdicts.requireCallable(TenantContext.role());

        UUID cycleId = uuid(id, "id");
        UUID customerId = uuid(body == null ? null : body.customerId(), "customer_id");

        VerdictService.CreateVerdictRequest req = new VerdictService.CreateVerdictRequest(
                customerId,
                sequenceNo(body == null ? null : body.sequenceNo()),
                body == null ? null : body.baseTotal(),
                body == null ? null : body.currentTotal(),
                body == null || body.sameOrigin() == null
                        ? null : body.sameOrigin().toDomain(),
                toAdherenceInput(body == null ? null : body.adherence()),
                // 🛑 risk_flag 未传 ⇒ null（⇒ 路由落 D5）。不得默认"无"：
                //    默认会让 D4 该触发而不触发，而那正是 PRD §7.3 定调句要防的事。
                parseRiskOrNull(body == null ? null : body.riskFlag()),
                body == null ? null : body.coreMetricImproved(),
                toConfidenceInput(body == null ? null : body.confidence()),
                moduleScoresJson(body == null ? null : body.moduleScores()),
                body == null ? null : body.bandTrendNote(),
                body == null ? null : body.thresholdVersion());

        VerdictService.VerdictOutcome outcome =
                verdicts.createVerdict(tenantId, cycleId, req, TenantContext.staffId());

        return Result.ok(toVerdictData(outcome), MDC.get("traceId"));
    }

    // ==================================================================
    // F2 GET /customers/{id}/verdicts
    // ==================================================================

    /**
     * F2 判定历史（{@code GET /api/v1/customers/{id}/verdicts}）。
     *
     * <h2>🔴 客户与调理师一律 403 —— 由 {@link StaffOnly} 在入站处完成</h2>
     * 契约 F2 是域 F 里<b>唯一</b>标了 {@code x-client-explicitly-denied: true} 且
     * 描述逐字写「客户与调理师端一律 403」的端点。故这里按<b>方法</b>贴 {@code @StaffOnly}
     * （而不是类级）—— 见类注释里"为什么不能贴类级"的论证。
     *
     * <h2>🛑 出参含 {@code pending[]}（"有依据而无结论"的轮次）</h2>
     * 有一类周期"有依据而无结论"，若本端点只返回判定行，它们会<b>整体消失</b> ——
     * 而它们正是"系统在数据不全时没有硬下结论"的唯一证据（PRD P0-13/P0-14）。
     * 故 {@code data.pending[]} 与 {@code data.verdicts[]} 并列返回。
     *
     * <h2>🔴 {@code pending[]} 里有两类，靠 {@code branch} 是否为空区分</h2>
     * <table border="1">
     *   <tr><th>形态</th><th>{@code branch}</th><th>含义</th><th>何时产生</th></tr>
     *   <tr><td>待判定</td><td>{@code null}</td>
     *       <td>C4 已提交依据、F1 尚未落结论（两阶段模型的阶段一）</td>
     *       <td>V8 起新写入</td></tr>
     *   <tr><td>挂起</td><td>{@code 人工复核}</td>
     *       <td>D5 挂起 —— 该轮数据不足 / 测量不可比，需人工补数据</td>
     *       <td>V8 之前的历史数据</td></tr>
     * </table>
     * 🛑 消费方<b>不得</b>把 {@code branch=人工复核} 的条目当作一条判定结论统计
     * （它不是结论，是"待人工补数据"）；也不得把 {@code branch=null} 的条目
     * 渲染成一个空结论（它连结论都还没产生，端侧应显示"待判定"）。
     *
     * <p>🛑 为何这里用差集而非 {@code cycle.verdict IS NULL}：V8 之前的历史挂起轮次，
     * 其 {@code cycle_assessment.verdict} 已被写成 '人工复核'，改用 IS NULL 判据
     * 会让它们从历史里立刻消失。差集口径同时覆盖两类形态，见
     * {@code VerdictService#listCustomerVerdictHistory} 的时点表。
     */
    @StaffOnly(clientDeniedFields = {
            "verdict_id", "branch", "confidence", "evidence_snapshot",
            "threshold_version", "decided_at"})
    @RequirePermission("verdict:read")
    @GetMapping("/customers/{id}/verdicts")
    public Result<Object> listVerdicts(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        verdicts.requireCallable(TenantContext.role());

        VerdictService.CustomerVerdictHistory history =
                verdicts.listCustomerVerdictHistory(tenantId, uuid(id, "id"));

        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (VerdictRow row : history.verdicts()) {
            list.add(verdictRowData(row));
        }
        data.put("verdicts", list);

        // 🛑 "有依据而无结论"的轮次：不返回它们等于让历史少一段。见方法注释的时点表。
        //    两形态靠 branch 是否为空区分（待判定 = null；历史挂起 = 人工复核）。
        int pendingCount = 0;
        int suspendedCount = 0;
        List<Map<String, Object>> pending = new ArrayList<>();
        for (CycleAssessmentRow cycle : history.suspended()) {
            boolean awaiting = cycle.isAwaitingVerdict();
            if (awaiting) {
                pendingCount++;
            } else {
                suspendedCount++;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("cycle_id", cycle.cycleId().toString());
            // 🛑 待判定 ⇒ null；历史挂起 ⇒ '人工复核'。生产代码不得回填"人工复核"
            //    到待判定行上 —— 那会把"结论还没产生"说成一个结论。
            one.put("branch", awaiting ? null : cycle.branch().dbLabel());
            one.put("phase", awaiting ? "awaiting_verdict" : "suspended");
            one.put("sequence_no", cycle.sequenceNo());
            one.put("decided_at", iso(cycle.recordedAt()));
            one.put("threshold_version", cycle.thresholdVersion());
            one.put("note", awaiting
                    ? "待判定轮次（C4 已提交依据、F1 尚未落结论）：该周期的评估依据已入库，"
                    + "判定结论尚未产生。它不是一条判定结论，也不是『数据不足』——"
                    + "而是一次仍可被 F1 补全判定的进行中周期。"
                    : "挂起轮次（V8 前写入的历史数据）：该轮数据不足或测量不可比，"
                    + "系统未下结论。它不是一条判定结论，而是『待人工补数据』的留痕。");
            pending.add(one);
        }
        data.put("pending", pending);
        data.put("verdict_count", history.verdictCount());
        data.put("pending_count", pendingCount);
        data.put("suspended_count", suspendedCount);
        data.put("pending_note",
                "🛑 pending[] 合并了两类『有依据而无结论』的形态，由 branch 区分："
                        + "branch=null ⇒ 待判定（V8 起新数据，C4 已落依据、F1 未落结论）；"
                        + "branch=人工复核 ⇒ 挂起（V8 前的历史数据）。"
                        + "消费方不得把任一类当作判定结论统计。"
                        + "差集口径（而非 cycle.verdict IS NULL）是为兼容历史数据 —— "
                        + "老挂起轮次的 cycle.verdict 已被写成 '人工复核'");

        return Result.ok(data, MDC.get("traceId"));
    }

    /** 取单条判定（staff 侧详情；不存在抛 {@code NOT_FOUND}）。 */
    @StaffOnly(clientDeniedFields = {
            "verdict_id", "branch", "confidence", "evidence_snapshot",
            "threshold_version", "decided_at"})
    @RequirePermission("verdict:read")
    @GetMapping("/verdicts/{id}")
    public Result<Object> detail(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        verdicts.requireCallable(TenantContext.role());
        return Result.ok(verdictRowData(verdicts.detail(tenantId, uuid(id, "id"))),
                MDC.get("traceId"));
    }

    // ==================================================================
    // F2b GET /verdicts/{id}/replay —— ADR-11 回放闭环（S2-8）
    // ==================================================================

    /**
     * 回放一条已落库判定 —— <b>S2-8 / ADR-11</b>。
     *
     * <h2>🛑 为什么它不在契约的 45 个端点里（且这不算"造端点"）</h2>
     * {@code ADR-11 溯源回放} 是本工程<b>已登记的内部架构决策</b>（README §三），
     * 它要求 {@code threshold_version} 当不可变数据管理、可溯源回放。
     * 而"回放"这件事在契约里没有对应的面客端点 —— 因为它是<b>内部证据链的自检动作</b>，
     * 不是一次业务操作。
     *
     * <p>故本端点定位为与 {@code /verdicts/contract} <b>同族的内部自描述端点</b>
     * （那一个同样不在契约 45 端点内）：它只读、只对 staff 开放、
     * 且它存在的目的正是<b>证明契约里那条"可回放"要求确实成立</b>。
     *
     * <p>🛑 它不改任何数据（无 {@code @Idempotent} —— 幂等注解是给写接口的；
     * 一个只读回放加幂等注解会让"它是写操作"这一误解在代码里留下证据）。
     *
     * <h2>🔴 客户与调理师一律 403</h2>
     * 与 F2 同档：贴 {@link StaffOnly}。理由比 F2 更硬 ——
     * 回放结论含<b>漂移分析</b>（"当时的门槛与今天的不是同一套"），
     * 这类内部口径沿革对客户毫无意义，误读却会直接变成一次纠纷。
     */
    @StaffOnly(clientDeniedFields = {
            "outcome", "stored_version", "current_version", "drifted_segments",
            "stored_branch", "recomputed_branch", "note"})
    @RequirePermission("verdict:read")
    @GetMapping("/verdicts/{id}/replay")
    public Result<Object> replayVerdict(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        verdicts.requireCallable(TenantContext.role());

        VerdictRow row = verdicts.detail(tenantId, uuid(id, "id"));
        ReplayResult r = replay.replay(
                tenantId, row.evidenceSnapshotJson(), row.thresholdVersion());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict_id", row.verdictId().toString());
        data.put("outcome", r.outcomeCode());
        data.put("reproducible", r.outcome().isReproducible());
        data.put("stored_version", r.storedVersion());
        data.put("current_version", r.currentVersion());
        data.put("drifted_segments", r.driftedSegments());
        data.put("stored_branch", r.storedBranch());
        data.put("recomputed_branch", r.recomputedBranch());
        data.put("note", r.note());
        // 🛑 恒 false：漂移是内部证据链的属性，不是客户的属性
        data.put("customer_facing", r.customerFacing());
        data.put("scope_note",
                "本回放只重放【由 threshold_version 决定的那一项】= 路由分支（D1~D5）。"
                        + "置信度与 disposition 不重算 —— 它们的一部分因子不在 evidence_snapshot 里，"
                        + "只用版本号一项重算会得到『看似重算过、实则用了臆造因子』的结果");
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 出站构造（严格限于 VerdictData 六项）
    // ==================================================================

    /** F1 出参 —— 契约 {@code VerdictData} 六项，一项不多。 */
    private Map<String, Object> toVerdictData(VerdictService.VerdictOutcome outcome) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 快照只解析一次（下面 threshold_version 也从它取，以保持"同一份依据"的单调性）
        Map<String, Object> snap = parseSnapshot(outcome.evidenceJson());

        // 🛑 V8 起【总有】一条 verdict 行（含挂起轮次，其 confidence = null），
        //    故 verdict_id 恒非 null。保留 verdictPersisted() 这层判断而非直接
        //    outcome.verdictId().toString() 的理由见 VerdictService.VerdictOutcome：
        //    它是"结论行是否存在"的唯一判据，将来真出现不落行的路径时改一处即可。
        data.put("verdict_id", outcome.verdictPersisted() ? outcome.verdictId().toString() : null);
        data.put("branch", outcome.routed().branch().dbLabel());
        data.put("confidence",
                outcome.confidence().confidence() == null
                        ? null : outcome.confidence().confidence().toPlainString());
        // 🛑 evidence_snapshot 是"不透明的依据快照"（契约未声明其 properties），
        //    故此处必须解析成对象，而不是下发一个 JSON 字符串 ——
        //    后者会让端侧拿到二次编码的字符串，且逐字比对全部不一致。
        data.put("evidence_snapshot", snap);
        // threshold_version 从快照里取而非从入参取：快照是本行<b>实际落库</b>的那份依据，
        // 从它取值可保证"回显的版本"与"库里的版本"不可能分叉。
        data.put("threshold_version", snap.get("threshold_version"));
        data.put("decided_at", iso(outcome.decidedAt()));
        // 🛑 挂起信息不放顶层（契约没有这些字段）—— 它们在 evidence_snapshot 里。
        //    调用方据此判断"本轮是否挂起"的契约内依据是：branch = 人工复核
        //    （V8 起 verdict_id 恒非 null，故它不再是判据 —— 改用 branch 与
        //     快照里的 confidence/disposition 三项）。
        return data;
    }

    /** 库里的一行判定 → 出参（同样严格限于 {@code VerdictData} 六项）。 */
    private Map<String, Object> verdictRowData(VerdictRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict_id", row.verdictId().toString());
        data.put("branch", row.branch().dbLabel());
        data.put("confidence",
                row.confidence() == null ? null : row.confidence().toPlainString());
        data.put("evidence_snapshot", parseSnapshot(row.evidenceSnapshotJson()));
        data.put("threshold_version", row.thresholdVersion());
        data.put("decided_at", iso(row.decidedAt()));
        return data;
    }

    /**
     * 解析依据快照为对象。
     *
     * <p>🛑 解析失败时<b>返回一个带 {@code _unparsable} 标记的对象</b>，而不是抛错，
     * 也不是静默返回 {@code null}。理由：快照是<b>不可覆盖</b>的历史依据，
     * 一次解析失败多半意味着"当时写入的 JSON 与现在的读取器不兼容"——
     * 那是需要人看的信息，不应变成一次 500（把"读历史"整个打断），
     * 也不应变成 {@code null}（让"读不到"与"当时没记"不可区分）。
     */
    private static Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("_missing", true,
                    "_note", "依据快照为空 —— 该行不满足 PRD §C.1.9 硬约束②（依据必须可回放）");
        }
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_unparsable", true);
            fallback.put("_note", "依据快照无法按当前读取器解析：" + e.getMessage()
                    + "（快照不可覆盖，故原样保留而不改写）");
            fallback.put("_raw", json);
            return fallback;
        }
    }

    // ==================================================================
    // 入参解算
    // ==================================================================

    /**
     * 组装依从输入。
     *
     * <h2>🛑 缺维不得"按剩余维度凑数"，故本方法<b>补齐三维</b>而不是拒绝</h2>
     * {@link AdherenceEngine#compute} 有一条硬校验：{@code A1/A3/A4} 三维<b>必须齐备</b>，
     * 缺任一项即抛（「缺维不得按剩余维度重归一凑数」）。
     * 若本方法只把调用方给的那几维传下去，"少传了一维"会变成一次
     * {@code VALIDATION_FAILED} —— 而正确的语义其实是<b>那一维对该客户不适用</b>
     * （例如门店未适配手环设备，则 A3 结构性缺失）。
     *
     * <p>故本方法<b>显式补上缺失的维度并标为"结构性缺失"</b>，让它走引擎的
     * "退出加权分母 → 重归一"路径。这个选择有一条可检验的依据：
     * 结构性缺失的语义正是"载体在他这里不存在"，而"调用方没传"在服务端看来
     * <b>无法与"载体不存在"区分</b>；把它当作"不适用"是唯一不会对客户不利的解释
     * （替代解释"记 0 分"会直接压低 AS，从而把缺数据说成不配合 ——
     * 正是 PRD §C.1.9「未佩戴不记不利」要防的）。
     *
     * <p>🛑 但 {@code adherence} 整体缺失时返回 {@code null} 并<b>不</b>补齐 —— 见调用点：
     * 那是"这一次根本没进入依从计算"，服务层会把它记成
     * {@code AdherenceState.SAMPLE_INSUFFICIENT}（没算出来），而不是替它编一套维度。
     */
    private static AdherenceEngine.AdherenceInput toAdherenceInput(AdherenceBody body) {
        if (body == null) {
            return null;
        }
        Map<String, AdherenceEngine.DimensionInput> dims = new LinkedHashMap<>();
        for (String code : new java.util.TreeSet<>(AdherenceEngine.REFUND_DIMENSIONS)) {
            DimensionBody d = body.dimensions() == null ? null : body.dimensions().get(code);
            dims.put(code, d == null
                    ? AdherenceEngine.DimensionInput.ofStructuralMissing()
                    : d.toDomain());
        }
        return new AdherenceEngine.AdherenceInput(
                body.expectedDays() == null ? 0 : body.expectedDays(), dims);
    }

    /**
     * 组装置信度输入。
     *
     * <p>🛑 {@code same_origin_status} 未传 ⇒ 返回 {@code null}（⇒ 置信度不参与），
     * <b>不</b>默认 {@code SAME_ORIGIN} —— 默认会让"缺元数据"或"不可比"被静默当作"同源"，
     * 而 {@code s} 是置信度合成式的乘性因子（不可比时一票否决并强制转人工）。
     */
    private static VerdictConfidenceEngine.ConfidenceInput toConfidenceInput(ConfidenceBody body) {
        if (body == null || body.sameOriginStatus() == null
                || body.sameOriginStatus().isBlank()) {
            return null;
        }
        VerdictConfidenceEngine.SameOriginStatus status;
        try {
            status = VerdictConfidenceEngine.SameOriginStatus.valueOf(
                    body.sameOriginStatus().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "confidence.same_origin_status 不是合法取值: '" + body.sameOriginStatus() + "'"
                            + "（合法: SAME_ORIGIN / MISSING_METADATA / INCOMPARABLE）。"
                            + "🛑 不得回落为 SAME_ORIGIN —— 那会把『不可比』静默当成『同源』，"
                            + "而不可比是一票否决并强制转人工的因子");
        }
        return new VerdictConfidenceEngine.ConfidenceInput(
                status,
                body.answeredCount() == null ? 0 : body.answeredCount(),
                body.expectedDays() == null ? 0 : body.expectedDays(),
                body.mcidDelta() == null ? 0 : body.mcidDelta());
    }

    /** 风险标签；未传 ⇒ {@code null}。 */
    private static RiskFlag parseRiskOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : RiskFlag.parse(raw);
    }

    /**
     * 模块分 → JSON 串（{@code module_scores JSONB NOT NULL}）。
     *
     * <p>缺省时给 {@code {"M1":0,...,"M5":0}} —— 理由见 {@link #EMPTY_MODULE_SCORES}：
     * 模块分不单独产生任何结论（核心指标未录时直接 D5），故它兜底是安全的，
     * 而 {@code risk_flag} / {@code confidence} 兜底是不安全的。
     */
    private static String moduleScoresJson(Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return EMPTY_MODULE_SCORES;
        }
        try {
            return MAPPER.writeValueAsString(new java.util.TreeMap<>(scores));
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "module_scores 无法序列化为 JSON: " + e.getMessage());
        }
    }

    /** 评估序号：缺省 1（首次评估），且与 V5 的 {@code CHECK (sequence_no >= 1)} 同口径。 */
    private static int sequenceNo(Integer raw) {
        if (raw == null) {
            return 1;
        }
        if (raw < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "sequence_no 必须 ≥ 1：实际=" + raw
                            + "（它是『第 N 次评估』的语义，0 与负数都没有对应事实；"
                            + "缺省不传时按 1 处理）");
        }
        return raw;
    }

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— 判定域不设无租户通道："
                            + "依据与结论两条链全部按租户隔离（RLS FORCE）");
        }
        return tenantId;
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 必填");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }

    /**
     * 时间 → ISO-8601（{@code OffsetDateTime}）。
     *
     * <p>🛑 用 {@code OffsetDateTime} 而非 {@code Instant}：契约
     * {@code VerdictData.decided_at} 是 {@code format: date-time}，
     * 而 {@code Instant.toString()} 输出 {@code Z} 结尾的 UTC ——
     * 端侧若按本地时区展示，会得到"提前 8 小时"的时间且无从察觉。
     * 输出带偏移量的形式，让时区信息随值一起走。
     */
    private static String iso(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC).toString();
    }

    /**
     * 供测试断言"本域的分支字面与契约 enum 逐字一致"。
     *
     * <p>它是 {@code public} 而不是包私有的：判定域控制器的守卫测试位于
     * {@code com.diaoyuanyun.dy.app.derived}（与其它 derived 测试同类），
     * 与控制器不同包。把它改成包私有会逼测试类搬到一个只为访问权限而存在的包里 ——
     * 而"测试的包路径由被测类的可见性决定"是一条会持续制造麻烦的耦合。
     */
    public static List<String> contractBranchLiterals() {
        return CONTRACT_BRANCH_LITERALS;
    }

    // ==================================================================
    // 端点自描述
    // ==================================================================

    /** 端点自描述（staff 只读）—— 把契约行与三道防线的落点变成可读的运行时事实。 */
    @StaffOnly(clientDeniedFields = {"endpoints", "deny_layers", "branches", "dispositions"})
    @RequirePermission("verdict:read")
    @GetMapping("/verdicts/contract")
    public Result<Object> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "F 判定与稽核（本类只实现 F1/F2）");
        m.put("endpoints", List.of(
                "F1  POST /api/v1/cycle-assessments/{id}/verdicts",
                "F2  GET  /api/v1/customers/{id}/verdicts",
                "（内部）GET /api/v1/verdicts/{id}          —— staff 详情",
                "（内部）GET /api/v1/verdicts/{id}/replay   —— ADR-11 回放闭环（S2-8）",
                "（内部）GET /api/v1/verdicts/contract      —— 本端点"));
        m.put("c4_implementation_note",
                "🛑 C4 POST /customers/{id}/cycle-assessments 由 AssessmentController 承载"
                        + "（契约 tags: [C-评估与题库]），但其落库走本域 VerdictService.submitCycleAssessment"
                        + " —— 两阶段共用 cycle_assessment 一张表，故读写归一到同一个服务，"
                        + "避免『依据由 A 类写、判定由 B 类写』造成同表两套真相");
        m.put("callable_roles", Map.of(
                "F1", List.of("meridian", "admin"),
                "F2", List.of("meridian", "admin")));
        m.put("client_forbidden_f2", true);
        m.put("client_forbidden_f1", false);
        m.put("visibility_note",
                "F2 契约标注 x-client-explicitly-denied: true（客户与调理师端一律 403），"
                        + "故 @StaffOnly 只贴 F2；F1 没有该键，贴类级会造成一次超出契约的收紧。"
                        + "F1 对客户的拒绝由 requireCallable + verdict:write 两层完成");
        m.put("deny_layers", List.of(
                "StaffOnly（仅 F2；契约级端点档位）",
                "VerdictService.requireCallable（F1/F2 的 x-callable-roles，客户与调理师显式点名拒绝）",
                "RequirePermission（功能权限码 verdict:read / verdict:write）"));
        m.put("branches", CONTRACT_BRANCH_LITERALS);
        m.put("branch_literals_match_db", true);
        m.put("branch_literals_note",
                "本域 dbLabel 与契约 enum 逐字相同（与退款域 entry 的两套字面相反）；"
                        + "由 VerdictControllerContractTest 逐值断言");
        m.put("dispositions", com.diaoyuanyun.dy.app.derived.domain.Disposition.allDbLabels());
        m.put("f1_request_fields", F1_REQUEST_FIELDS);
        // 🛑 V8 起挂起【也落】结论行 —— 见 VerdictService.describeRouting 的同名键。
        //    此处与 describeRouting 保持同值；两处都写是为了让本端点（面 staff 的
        //    契约自描述）不必解析 routing 子对象就能读到这条关键事实。
        m.put("suspended_persists_verdict_row", true);
        m.put("two_phase_model", Map.of(
                "stage1", "C4 POST /customers/{id}/cycle-assessments（落依据；cycle.verdict IS NULL）",
                "stage2", "F1 POST /cycle-assessments/{id}/verdicts（补判定侧四列 + 落 verdict 行）",
                "discriminator", "cycle_assessment.verdict 是否为 NULL"));
        m.put("f2_pending_forms", List.of(
                "awaiting_verdict（branch=null）—— V8 起新数据：C4 已落依据、F1 未落结论",
                "suspended（branch=人工复核）—— V8 前的历史数据：D5 挂起"));
        m.put("routing", verdicts.describeRouting());
        // 🛑 S2-8：版本号溯源 —— 把"它由服务端算出"做成可读的运行时事实
        m.put("threshold_version", verdicts.describeThresholdVersion());
        m.put("replay", replay.describeReplay());
        m.put("f1_request_fields_note",
                "🛑 S2-8 起 threshold_version 的语义变更：它是【断言位】而非【取值位】—— "
                        + "可空（空 = 服务端按当前口径填）；非空时必须逐字等于服务端算出的指纹，"
                        + "否则拒写 VERSION_CONFLICT(4001)。落库值一律取服务端算出的那个");
        m.put("threshold_values_omitted",
                "本响应不含任何阈值数值 —— 避免成为配置值的第二份副本（改配置不改此处时不报错）");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数（供测试断言控制器确实被调用过，且未直连仓储）。 */
    static long invocations() {
        return CONTROLLER_INVOCATIONS.get();
    }
}