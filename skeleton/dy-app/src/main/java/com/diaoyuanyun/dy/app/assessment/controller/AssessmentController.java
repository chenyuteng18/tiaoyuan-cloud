package com.diaoyuanyun.dy.app.assessment.controller;

import com.diaoyuanyun.dy.app.assessment.domain.BaselineAssessmentRow;
import com.diaoyuanyun.dy.app.assessment.service.AssessmentService;
import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.service.VerdictService;
import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评估与题库域控制器 —— 契约域 C 的 C1~C4 四个端点。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *  C1 GET  /scale-item-banks               x-callable-roles: [client, therapist, meridian, admin]
 *     参数: age_group(required枚举) / dimension(可选) / version(可选)
 *     200 → ResultEnvelope（题库题组）
 *  C2 POST /customers/{id}/assessments/baseline
 *     x-callable-roles: [therapist, meridian, admin]
 *     requestBody: BaselineAssessmentRequest（同源题组锁定）
 *     200 → BaselineAssessmentData；422 → BusinessRuleViolated
 *  C3 GET  /customers/{id}/assessments/{assessment_id}
 *     x-callable-roles: [client, therapist, meridian, admin]
 *     200 → BaselineAssessmentData；404 → NotFound
 *  C4 POST /customers/{id}/cycle-assessments
 *     x-callable-roles: [therapist, meridian, admin]
 *     无 requestBody（未冻结）；200 → ResultEnvelope；422 → BusinessRuleViolated
 * </pre>
 *
 * <h2>🛑 C4 为什么落在这个类，而它的持久化却走 F 判定域的服务</h2>
 * <ul>
 *   <li><b>端点归属按契约 {@code tags}</b>：C4 的 {@code tags: [C-评估与题库]}，
 *       故它由本类（域 C 控制器）暴露 —— 与 C1/C2/C3 同一入口、同一套权限码。</li>
 *   <li><b>持久化归属按下游服务</b>：C4 落的行与 F1 补判定的行是<b>同一张</b>
 *       {@code cycle_assessment} 表（PK = {@code cycle_id}）。若本类自己写这张表，
 *       就会出现"依据由域 C 写、判定由域 F 写"的同表两套真相 —— 故统一委托
 *       {@link VerdictService#submitCycleAssessment}，它是这张表的<b>唯一</b>写入方。
 *       这与 R2（控制器不直连持久化）一致：本类只依赖服务层。</li>
 * </ul>
 *
 * <h2>🛑 为什么 C1 / C3 <b>不贴</b> {@code @RequirePermission}，而 C2 / C4 贴</h2>
 * 与域 B 的 B4/B5 完全同款：
 * <ul>
 *   <li>C1 / C3 的 {@code x-callable-roles} <b>含 client</b>，而注册表
 *       <b>刻意不登记 client</b>（任何码都会让客户的合法请求恒 403）⇒
 *       不贴码，让它们与 A2/B4/B5 一样"含 client 的端点刻意缺第三层"。</li>
 *   <li>C2 / C4 的 {@code x-callable-roles} <b>不含 client</b>，是标准的
 *       "给 staff 发码、不给客户发码"形态 ⇒ 贴 {@code assessment:write}。</li>
 * </ul>
 *
 * <h2>🛑 C3 的字段裁剪（{@code migratable} 客户不下发）</h2>
 * 契约 {@code BaselineAssessmentData.migratable} 的 {@code x-visible-to}
 * 不含 client。故客户调 C3 时该字段<b>不出现在响应里</b> ——
 * 这是"无权限字段不下发"（契约硬约束①），不是前端不渲染。
 * 落点在本类（由 {@link AssessmentService#migratableVisibleTo} 判定），
 * 因为它是"字段档位"而非"端点档位"。
 *
 * <h2>🛑 控制器不承载业务判定</h2>
 * 全部顺序与错误码归属在 {@link AssessmentService} / {@link VerdictService}。
 * 控制器只取参、调服务、包信封。
 */
@RestController
@RequestMapping("/api/v1")
public class AssessmentController {

    /** 控制器调用计数 —— 供 E2E 断言"请求真的打到了本控制器"。 */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssessmentService service;
    private final VerdictService verdicts;

    public AssessmentController(AssessmentService service, VerdictService verdicts) {
        this.service = service;
        this.verdicts = verdicts;
    }

    // ==================================================================
    // C1 · GET /scale-item-banks
    // ==================================================================

    /**
     * C1 题库拉取（按分龄组 + 维度）。
     *
     * <p>契约 200 → 题组 {@code ResultEnvelope}。三个参数：{@code age_group} 必填，
     * {@code dimension} / {@code version} 可选。
     *
     * <h2>🛑 不贴 {@code @RequirePermission} 的理由</h2>
     * C1 的 {@code x-callable-roles} <b>含 client</b>（评测量表是客户要亲自作答的），
     * 而注册表刻意不登记 client。贴任何码都会让客户拉不到题组 ——
     * 与 A2/B4/B5 同款"含 client 的端点刻意缺第三层"。
     *
     * <h2>🛑 参数非法（age_group 不在 8 组）报 400 而非"查无"</h2>
     * age_group 是契约 {@code required} 枚举，传一个不在枚举里的值属调用方错误 ⇒ 400。
     * 但"题库里真的没题"（低龄组未完成症状向改写）返回 200 + 空列表 ——
     * 两者语义相反，见 {@link AssessmentService} / {@code ScaleItemBankService#listItems}。
     */
    @GetMapping("/scale-item-banks")
    public Result<Map<String, Object>> listScaleItemBanks(
            @RequestParam(value = "age_group", required = false) String ageGroup,
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "version", required = false) String version) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        List<ScaleItemRow> items = service.listItems(tenantId, ageGroup, dimension, version);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("age_group", ageGroup);
        data.put("dimension", dimension);
        data.put("version", version);
        data.put("item_count", items.size());
        data.put("items", toRowList(items));
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // C2 · POST /customers/{id}/assessments/baseline
    // ==================================================================

    /**
     * C2 请求体 —— 契约 {@code BaselineAssessmentRequest} 的逐字段承接。
     *
     * <p>🛑 用 {@code @JsonProperty} 显式钉 snake_case：Jackson 默认把
     * {@code ageGroupLocked} 序列化成 {@code ageGroupLocked}，而契约字段名是
     * {@code age_group_locked} —— 不钉则客户端按契约键取值拿到 null。
     *
     * <p>🛑 本 record <b>不</b>持有任何方法返回 service 层类型 ——
     * R4（DIP）断言「record 不得依赖服务层」。若在这里加 {@code toDomain()}
     * 返回 {@code AssessmentService.SubmitBaselineRequest}，就制造了
     * 「契约形状 record → 服务层内部类型」的依赖边。转换逻辑放在控制器
     * 的私有静态方法 {@link #toDomain(BaselineRequest)} 里（控制器依赖服务层是
     * 允许的入站方向，R4 只管 record 的字段/方法签名）。
     */
    public record BaselineRequest(
            @JsonProperty("scale_id") String scaleId,
            @JsonProperty("item_group_id") String itemGroupId,
            @JsonProperty("age_group_locked") String ageGroupLocked,
            @JsonProperty("dimension_scores") List<Integer> dimensionScores,
            @JsonProperty("total_score") Integer totalScore,
            @JsonProperty("measure_operator") String measureOperator) {
    }

    /** record → 服务层入参（转换逻辑住在控制器类，而非 record 内）。 */
    private static AssessmentService.SubmitBaselineRequest toDomain(BaselineRequest body) {
        if (body == null) {
            return null;
        }
        return new AssessmentService.SubmitBaselineRequest(
                body.scaleId(), body.itemGroupId(), body.ageGroupLocked(),
                body.dimensionScores(), body.totalScore(), body.measureOperator());
    }

    /**
     * C2 基线评估提交（{@code POST /customers/{id}/assessments/baseline}）。
     *
     * <p>契约 200 → {@code BaselineAssessmentData}；422 → {@code BusinessRuleViolated}。
     * 贴 {@code assessment:write}（x-callable-roles 不含 client）。
     *
     * <h2>🛑 为什么路径里的 {@code id} 就是 {@code customer_id}</h2>
     * 契约路径逐字 {@code /customers/{id}/assessments/baseline}，且 C2 逐字
     * 是"基线评估提交"—— 评估归属的客户即路径里的 {@code id}。
     * 不额外从 requestBody 收 customer_id（两处来源会让"路径一个、体里另一个"分叉）。
     */
    @RequirePermission("assessment:write")
    @PostMapping("/customers/{id}/assessments/baseline")
    public Result<Map<String, Object>> submitBaseline(@PathVariable("id") String id,
                                                      @RequestBody(required = false) BaselineRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        UUID customerId = uuid(id, "id");
        AssessmentService.SubmitBaselineRequest req = toDomain(body);

        BaselineAssessmentRow row = service.submitBaseline(tenantId, customerId, req,
                TenantContext.staffId());

        return Result.ok(toBaselineData(row, true), MDC.get("traceId"));
    }

    // ==================================================================
    // C3 · GET /customers/{id}/assessments/{assessment_id}
    // ==================================================================

    /**
     * C3 评估详情（{@code GET /customers/{id}/assessments/{assessment_id}））。
     *
     * <p>契约 200 → {@code BaselineAssessmentData}；404 → {@code NotFound}。
     * 不贴权限码（含 client，理由见类注释）。
     *
     * <h2>🛑 字段裁剪（migratable 客户不下发）</h2>
     * 由 {@link AssessmentService#migratableVisibleTo} 按当前端角色判定。
     * 客户拿到的响应体<b>没有</b> {@code migratable} 键（不是 false）。
     */
    @GetMapping("/customers/{id}/assessments/{assessment_id}")
    public Result<Map<String, Object>> getAssessment(@PathVariable("id") String id,
                                                     @PathVariable("assessment_id") String assessmentId) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        BaselineAssessmentRow row = service.getAssessment(tenantId,
                uuid(id, "id"), uuid(assessmentId, "assessment_id"));

        boolean migratableVisible =
                AssessmentService.migratableVisibleTo(VisibilityRole.tryOf(TenantContext.role()).orElse(null));
        return Result.ok(toBaselineData(row, migratableVisible), MDC.get("traceId"));
    }

    // ==================================================================
    // C4 · POST /customers/{id}/cycle-assessments
    // ==================================================================

    /**
     * C4 请求体。
     *
     * <h2>🛑 契约未冻结本体的形状 —— 故字段名在此处冻结</h2>
     * 契约 C4 只声明了"200 → {@code ResultEnvelope}"与"422 → BusinessRuleViolated"，
     * <b>没有</b> {@code requestBody} 定义。这是一处<b>已登记的契约缺口</b>
     * （与 F1 同款）。本 record 按域内语义把入参形状冻结下来，
     * 使"http 客户端能生成什么样的 C4 请求"有一个可断言的地方。
     *
     * <h2>🛑 它<b>不</b>接收 {@code branch} / {@code effect_verdict}</h2>
     * 这两项是<b>判定侧产物</b>（由 F1 产出）。C4 只提交"评估侧事实" ——
     * 若本体自带 {@code branch}，阶段一就能写下结论，两阶段模型随即瓦解，
     * 而 PRD P0-12「不得混入单一 enum」的含义正是"结论必须由服务端按组合算"。
     *
     * @param cycleId          周期评估主键（必填 —— 使 F1 能指向同一行；
     *                         服务端不代生成，否则调用方无法在同一周期上补判定）
     * @param sequenceNo       第 N 次评估（≥ 1）
     * @param adherence        依从四维（可空 ⇒ 样本不足）
     * @param expectedDays     应填天数（样本护栏）
     * @param moduleScores     模块分（M1–M5）
     * @param bandTrendNote    手环趋势说明（U-15）
     * @param thresholdVersion 🛑 断言位（可空 = 服务端填；非空须逐字等于服务端指纹）
     */
    public record CycleAssessmentRequest(
            @JsonProperty("cycle_id") String cycleId,
            @JsonProperty("sequence_no") Integer sequenceNo,
            @JsonProperty("adherence") AdherenceBody adherence,
            @JsonProperty("expected_days") Integer expectedDays,
            @JsonProperty("module_scores") Map<String, Integer> moduleScores,
            @JsonProperty("band_trend_note") String bandTrendNote,
            @JsonProperty("threshold_version") String thresholdVersion) {
    }

    /** C4 的依从四维（与 F1 的同一形状 —— 两侧对同一事实不得有两套字段名）。 */
    public record AdherenceBody(
            @JsonProperty("expected_days") Integer expectedDays,
            @JsonProperty("dimensions") Map<String, DimensionBody> dimensions) {
    }

    /** C4 的单个依从维度（语义与 F1 的 {@code DimensionBody} 逐条相同，映射到同一引擎入参）。 */
    public record DimensionBody(
            @JsonProperty("applicable") Boolean applicable,
            @JsonProperty("value") java.math.BigDecimal value,
            @JsonProperty("structural_missing") Boolean structuralMissing) {

        /** 🛑 三态映射与 F1 逐条同构 —— 必须走引擎工厂，不得自己 new（见 F1 的同名方法）。 */
        AdherenceEngine.DimensionInput toDomain() {
            if (Boolean.TRUE.equals(applicable)) {
                if (value == null) {
                    return AdherenceEngine.DimensionInput.ofBehavioralMissing();
                }
                return AdherenceEngine.DimensionInput.ofApplicable(value);
            }
            return Boolean.TRUE.equals(structuralMissing)
                    ? AdherenceEngine.DimensionInput.ofStructuralMissing()
                    : AdherenceEngine.DimensionInput.ofBehavioralMissing();
        }
    }

    /**
     * record → 服务层入参（转换逻辑住在控制器类，而非 record 内 —— 与 C2 同款，理由见 {@link BaselineRequest}）。
     */
    private static VerdictService.CreateCycleAssessmentRequest toDomain(CycleAssessmentRequest body,
                                                                      String cycleId) {
        Integer expectedDays = body == null || body.adherence() == null
                ? (body == null ? null : body.expectedDays())
                : (body.adherence().expectedDays() != null
                        ? body.adherence().expectedDays() : body.expectedDays());

        AdherenceEngine.AdherenceInput adherence = null;
        if (body != null && (body.adherence() != null || body.expectedDays() != null)) {
            java.util.Map<String, AdherenceEngine.DimensionInput> dims = new LinkedHashMap<>();
            if (body.adherence() != null && body.adherence().dimensions() != null) {
                body.adherence().dimensions().forEach((code, db) -> {
                    if (db != null) {
                        dims.put(code, db.toDomain());
                    }
                });
            }
            // 🛑 expected_days 缺省为 0：AdherenceEngine 会因样本不足而**挂起**
            //    （而不是猜一个天数）—— 这与"缺省即保守"的纪律一致。
            adherence = new AdherenceEngine.AdherenceInput(
                    expectedDays == null ? 0 : expectedDays, dims);
        }

        return new VerdictService.CreateCycleAssessmentRequest(
                cycleId == null ? null : UUID.fromString(cycleId),
                body == null || body.sequenceNo() == null ? 0 : body.sequenceNo(),
                adherence,
                expectedDays,
                body == null || body.moduleScores() == null ? null : writeJson(body.moduleScores()),
                body == null ? null : body.bandTrendNote(),
                body == null ? null : body.thresholdVersion());
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "module_scores 无法序列化: " + e.getMessage());
        }
    }

    /**
     * C4 周期评估提交（{@code POST /customers/{id}/cycle-assessments}，每 7 次触发）。
     *
     * <p>契约 200 → {@code ResultEnvelope}；422 → {@code BusinessRuleViolated}。
     * 贴 {@code assessment:write}（x-callable-roles 不含 client）。
     *
     * <h2>🛑 它<b>只落依据</b>，不落结论 —— 这是两阶段模型的阶段一</h2>
     * 落库后 {@code cycle.verdict IS NULL}（待判定），F1 之后可在<b>同一行</b>上补判定。
     * 详见 {@link VerdictService#submitCycleAssessment}。
     *
     * <h2>🛑 为什么 {@code id} 就是 {@code customer_id}</h2>
     * 契约路径逐字 {@code /customers/{id}/cycle-assessments}，与 C2 同一约定：
     * 客户归属由路径给定，不额外从 requestBody 收 {@code customer_id}。
     */
    @RequirePermission("assessment:write")
    @PostMapping("/customers/{id}/cycle-assessments")
    public Result<Map<String, Object>> submitCycleAssessment(
            @PathVariable("id") String id,
            @RequestBody(required = false) CycleAssessmentRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        UUID customerId = uuid(id, "id");
        UUID cycleId = uuid(body == null ? null : body.cycleId(), "cycle_id");

        CycleAssessmentRow row = verdicts.submitCycleAssessment(tenantId, customerId,
                toDomain(body, cycleId.toString()), TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cycle_id", row.cycleId().toString());
        data.put("customer_id", row.customerId().toString());
        data.put("sequence_no", row.sequenceNo());
        // 🛑 阶段一：判定尚未发生 —— branch 显式为 null，而不是"人工复核"
        //    （后者是一个真实结论，把它回填到待判定行上会把"还没判"说成"判了"）
        data.put("branch", null);
        data.put("phase", "awaiting_verdict");
        data.put("adherence_state", row.adherenceState() == null ? null : row.adherenceState().label());
        data.put("recorded_at", row.recordedAt().atOffset(java.time.ZoneOffset.UTC).toString());
        data.put("threshold_version", row.thresholdVersion());
        data.put("note",
                "🛑 本次只落了【评估侧依据】，判定结论尚未产生（cycle.verdict IS NULL）。"
                        + "下一步由 F1 POST /cycle-assessments/{cycle_id}/verdicts 在同一行上补判定。"
                        + "在 F1 到达之前，该周期在 F2 判定历史里以 pending[]（branch=null）出现");
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述（只读、免权限、不含任何数据）
    // ==================================================================

    /**
     * 域 C 的契约自描述。
     *
     * <p>把几件事变成可被回归用例集读取的运行时事实：
     * <ol>
     *   <li>C1/C3 不贴码（含 client）、C2/C4 贴 {@code assessment:write}；</li>
     *   <li>两个 schema 缺口（diagnosis / baseline_conclusion 未冻结入参）；</li>
     *   <li>C3 的 migratable 客户不下发；</li>
     *   <li>C4 的两阶段语义（只落依据 ⇒ cycle.verdict IS NULL）与其持久化落点（域 F 服务）。</li>
     * </ol>
     */
    @GetMapping("/scale-item-banks/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "C-评估与题库（本类实现 C1/C2/C3/C4）");
        m.put("endpoints", List.of(
                "C1 GET /scale-item-banks",
                "C2 POST /customers/{id}/assessments/baseline",
                "C3 GET /customers/{id}/assessments/{assessment_id}",
                "C4 POST /customers/{id}/cycle-assessments"));
        m.put("callable_roles", Map.of(
                "C1", List.of("client", "therapist", "meridian", "admin"),
                "C2", List.of("therapist", "meridian", "admin"),
                "C3", List.of("client", "therapist", "meridian", "admin"),
                "C4", List.of("therapist", "meridian", "admin")));
        m.put("annotation_policy", List.of(
                "C1/C3 不贴任何权限码（x-callable-roles 含 client，而注册表刻意不登记 client）",
                "C2/C4 贴 @RequirePermission(\"assessment:write\")（x-callable-roles 不含 client）"));
        m.put("c4_two_phase", Map.of(
                "stage1", "C4 只落评估侧依据 ⇒ cycle_assessment.verdict IS NULL（待判定）",
                "stage2", "F1 POST /cycle-assessments/{id}/verdicts 在同一行上补判定侧四列 + 落 verdict 行",
                "discriminator", "cycle_assessment.verdict 是否为 NULL",
                "persistence_owner",
                "🛑 C4 的落库走 VerdictService.submitCycleAssessment（域 F 的服务）—— "
                        + "C4 与 F1 共用 cycle_assessment 一张表，故该表只有一个写入方，"
                        + "避免『依据由域 C 写、判定由域 F 写』的同表两套真相"));
        m.put("c4_request_body_note",
                "🛑 契约 C4 未冻结 requestBody —— 入参字段名在 CycleAssessmentRequest 里冻结；"
                        + "它不含 branch/effect_verdict（判定侧产物，只能由 F1 产出）");
        m.put("schema_gaps", List.of(
                "diagnosis_json 库层 NOT NULL，但契约 C2 无 diagnosis 输入 → 落 _missing 标记",
                "baseline_conclusion.haozhuan 在 C2 入参无对应输入 → C3 该字段按未提供返回"));
        m.put("migratable_client_invisible", true);
        m.put("migratable_note",
                "BaselineAssessmentData.migratable 的 x-visible-to 不含 client —— "
                        + "客户调 C3 时该字段不下发（不是 false/null）。四要素推导："
                        + "题组ID + 量程版本 + 测量人 + 时间戳");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数（供 E2E 断言；不参与任何授权判定）。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    /** 重置计数器（供测试隔离）。 */
    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
    }

    // ==================================================================
    // 出站构造与入参解算
    // ==================================================================

    /**
     * 一行题 → 出参（C1 的 items[] 元素）。
     *
     * <p>🛑 不下发题面的"签核人"内部字段（{@code reviewer_id} / {@code reviewed_at}）——
     * 领取客户要的是"这套题里的题面 + 锚点"，签核信息是内容治理的痕迹，客户无需且不应看到。
     */
    private static Map<String, Object> toRow(ScaleItemRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", row.itemId().toString());
        m.put("age_group", row.ageGroup());
        m.put("dimension", row.dimension());
        m.put("item_no", row.itemNo());
        m.put("item_text", row.itemText());
        m.put("anchors", List.of(row.anchor0(), row.anchor1(), row.anchor2(),
                row.anchor3(), row.anchor4()));
        m.put("version", row.version());
        return m;
    }

    private static List<Map<String, Object>> toRowList(List<ScaleItemRow> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (ScaleItemRow row : items) {
            out.add(toRow(row));
        }
        return out;
    }

    /**
     * 一行基线评估 → 出参（契约 {@code BaselineAssessmentData} 形状）。
     *
     * <p>🛑 {@code migratable} 按 {@code migratableVisible} 决定是否出现——
     * 客户不可见时<b>键整体缺席</b>（契约硬约束①：无权限字段不下发）。
     */
    private Map<String, Object> toBaselineData(BaselineAssessmentRow row, boolean migratableVisible) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assessment_id", row.assessmentId().toString());
        data.put("assessed_at", row.assessedAt().atOffset(java.time.ZoneOffset.UTC).toString());

        // 🛑 baseline_conclusion：契约 C3 字段，但 C2 入参无对应输入 → 按"未提供"承载
        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("haozhuan", null);
        conclusion.put("_note", "好转判断在契约 C2 入参里未冻结，服务端不臆造（见 AssessmentService 类注释）");
        data.put("baseline_conclusion", conclusion);

        // migratable：客户不下发（不是 false、不是 null）
        if (migratableVisible) {
            data.put("migratable", row.migratable());
        }

        // dimension_scores：客户仅见本人填答维度 —— 这里从 metrics_json 解析维度分
        data.put("dimension_scores", parseDimensionScores(row.metricsJson()));
        return data;
    }

    /** 从 metrics_json 解析维度分（C3 出参 dimension_scores）。 */
    @SuppressWarnings("unchecked")
    private List<Integer> parseDimensionScores(String metricsJson) {
        try {
            Map<String, Object> metrics = MAPPER.readValue(metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object raw = metrics.get("dimension_scores");
            if (raw instanceof List<?> list) {
                List<Integer> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    out.add(o instanceof Number n ? n.intValue() : null);
                }
                return out;
            }
            return List.of();
        } catch (Exception e) {
            // 🛑 解析失败按"读不到"承载为空，不抛 500（详情端点不应因历史数据炸掉）
            return List.of();
        }
    }

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— 评估/题库不设无租户通道："
                            + "题库与评估记录都按租户隔离（RLS FORCE）");
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
}