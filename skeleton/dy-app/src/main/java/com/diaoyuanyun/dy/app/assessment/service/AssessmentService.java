package com.diaoyuanyun.dy.app.assessment.service;

import com.diaoyuanyun.dy.app.assessment.domain.BaselineAssessmentRow;
import com.diaoyuanyun.dy.app.assessment.repository.AssessmentLedger;
import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.app.scale.service.ScaleItemBankService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评估与题库域（契约域 C · C1~C3）的<b>唯一业务落点</b>。
 *
 * <h2>一、端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  C1 GET  /scale-item-banks                       → 复用 {@link ScaleItemBankService#listItems}
 *  C2 POST /customers/{id}/assessments/baseline     → {@link #submitBaseline}
 *  C3 GET  /customers/{id}/assessments/{assessment_id} → {@link #getAssessment}
 *  （C4 POST /customers/{id}/cycle-assessments 依赖 visit 服务次数，属域 D 之后）
 * </pre>
 *
 * <h2>二、🛑 错误码的两层归因（契约 description + 全局约定并存，逐项对齐）</h2>
 * <pre>
 *  C2 = 200 + 422(BusinessRuleViolated)（契约 responses 声明的非 200 码 = 422）
 *  C3 = 200 + 404(NotFound)（契约 responses 声明的非 200 码 = 404）
 * </pre>
 * 关键区分<b>两层</b>，不能混为一谈：
 * <ul>
 *   <li><b>参数格式错误</b>（UUID 非法、字段类型不对）→ {@code 400 VALIDATION_FAILED(1001)}。
 *       依据 = 契约 {@code x-error-codes} 全局约定「400/1001 触发条件 = 参数类型/必填/约束不满足」——
 *       这是<b>全局默认</b>，不因端点 responses 未列出 400 而失效（与 A3 的
 *       {@code page<1 → 400}、F1 的 {@code uuid() 非法 → 400} 同款先例）。</li>
 *   <li><b>业务规则不满足</b>（契约 description 逐字点名的「题组量程 ≠ 0–4 / 缺分龄锁定」、
 *       引用完整性、维度分与总分不自洽）→ {@code 422 BUSINESS_RULE_VIOLATED(5001)}。
 *       这才是契约 responses 里那一个 422 的落点。</li>
 * </ul>
 * 尤其契约 C2 description 逐字「错误码：5001（题组量程 ≠ 0–4 / 题组非 symptom 向 /
 * 缺分龄锁定）→ 提交阻断」—— 主语是<b>题组</b>与<b>分龄锁定</b>，属业务规则层；
 * 而 {@code scale_id} 写成一串非 UUID 垃圾，属参数格式层，二者归码不同。
 *
 * <h2>三、🛑 {@code migratable} 由<b>四要素推导</b>，不由调用方声明</h2>
 * 题组 ID（{@code item_group_id}）+ 量程版本（{@code scale.scale_version} 反查）
 * + 测量人（{@code measure_operator}）+ 时间戳（{@code assessed_at}）。
 * 四要素齐备 ⇒ {@code true}，缺任一 ⇒ {@code false}（后续 {@code effect_verdict = NULL}，
 * 计入 ECC 分母不计入分子）。与 {@code threshold_version} 同一条纪律：
 * <b>可推导的事实不接收调用方声明</b>，否则"声明"可以漂移到与事实相反。
 *
 * <h2>四、🛑 两个已登记的 schema 缺口（不臆造、用标记诚实承载）</h2>
 * <ol>
 *   <li>{@code diagnosis_json} 库层 {@code NOT NULL}，但契约 C2 的
 *       {@code BaselineAssessmentRequest} <b>没有</b> diagnosis 输入字段 ⇒
 *       落 {@code {"_missing": true}} 标记，并在自描述端点登记；</li>
 *   <li>契约 C3 的 {@code baseline_conclusion.haozhuan}（好转/稳定/下降）在 C2 入参里
 *       <b>也没有</b>对应输入 ⇒ C3 该字段按"未提供"返回。</li>
 * </ol>
 * 两者同源：结构化主诉与好转判断在契约里从未冻结入参形状，本服务<b>不</b>替契约发明。
 *
 * <h2>五、🛑 C3 的客户字段裁剪（{@code migratable} 客户不下发）</h2>
 * {@code BaselineAssessmentData.migratable} 的 {@code x-visible-to: [therapist, meridian, admin]}，
 * 不含 client。故客户调 C3 时该字段<b>不出现在响应里</b>（不是 {@code false}、不是 {@code null}）。
 * 这是"无权限字段不下发"（契约硬约束①）的又一落点，与 B4 的
 * {@code owner_store_id}/{@code serving_store_id} 同款，但这里<b>没有 include 参数</b>，
 * 故没有"点名索取 → 403"的问题，只是默认裁剪。
 */
@Service
public class AssessmentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssessmentLedger ledger;
    private final ScaleItemBankService itemBank;

    public AssessmentService(AssessmentLedger ledger, ScaleItemBankService itemBank) {
        this.ledger = ledger;
        this.itemBank = itemBank;
    }

    // ==================================================================
    // 一、C2 基线评估提交
    // ==================================================================

    /**
     * C2 入参 —— 契约 {@code BaselineAssessmentRequest} 的冻结形状。
     *
     * <h2>🛑 为什么它用的字段名就是契约的 snake_case</h2>
     * 契约 C2 有 requestBody（{@code BaselineAssessmentRequest}），字段名已冻结。
     * 本 record 与之逐字对应（{@code scale_id} / {@code item_group_id} /
     * {@code age_group_locked} / {@code dimension_scores} / {@code total_score} /
     * {@code measure_operator}）—— 控制器记录体用 {@code @JsonProperty} 承接，
     * 服务层入参直接复用本 record，避免"控制器一份、服务层一份"的两份同构定义。
     *
     * @param ageGroupLocked 分龄锁定（8 组枚举；缺失 = 校验失败 5001）
     * @param dimensionScores 7 维维度分（每维 0–16；量程 = 0–4 × 4 题）
     * @param totalScore      总分（0–112 = 7 维 × 16）
     */
    public record SubmitBaselineRequest(
            String scaleId,
            String itemGroupId,
            String ageGroupLocked,
            List<Integer> dimensionScores,
            Integer totalScore,
            String measureOperator) {
    }

    /**
     * C2 基线评估提交（{@code POST /customers/{id}/assessments/baseline}）。
     *
     * <p>顺序（每步失败都归 {@code 5001}，见类注释二）：
     * <ol>
     *   <li>解析分龄锁定（8 组枚举，缺失/非法 → 5001「缺分龄锁定」）；</li>
     *   <li>解析 scale_id + measure_operator + item_group_id 为 UUID（非法 → 5001）；</li>
     *   <li>反查 {@code scale} 存在性 + 版本号（量程版本要素）；</li>
     *   <li>校验 {@code measure_operator} 在职（FK 预检，避免 23503 变 500）；</li>
     *   <li>校验维度分：恰 7 项、每项 0–16；总分 0–112（量程 = 0–4 五级 → 16 → 112）；</li>
     *   <li>校验总分与维度分自洽（Σ dimension_scores == total_score）——
     *       不自洽是数据损坏的前兆，落库后会让同源复评的分母漂移；</li>
     *   <li>组装 metrics（维度分 + 总分）与 diagnosis 标记，推导 migratable，落库。</li>
     * </ol>
     *
     * @return 落库后的评估（供控制器回显 C3 形状）
     */
    public BaselineAssessmentRow submitBaseline(String tenantId, UUID customerId,
                                                SubmitBaselineRequest req, String createdBy) {
        AssessmentLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "customer_id 缺失 —— 基线评估必须归属某客户（路径参数）");
        }
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "基线评估请求体缺失（契约 C2 requestBody required）");
        }

        // ① 分龄锁定：缺失/非法 → 5001（契约 description 逐字「缺分龄锁定 → 提交阻断」
        //    —— 这是三类点名的业务规则之一，故归 422 而非 400）
        ScaleDomain.AgeGroup lockedGroup = parseAgeGroup(req.ageGroupLocked());

        // ② 三处 UUID 解析（非法/缺失 → 400 —— 参数格式层，见类注释二）
        UUID scaleId = parseUuid(req.scaleId(), "scale_id");
        UUID measureOperator = parseUuid(req.measureOperator(), "measure_operator");
        UUID itemGroupId = req.itemGroupId() == null || req.itemGroupId().isBlank()
                ? null : parseUuid(req.itemGroupId(), "item_group_id");

        // ③ scale 反查（存在性 + 版本号）—— 引用完整性属业务规则 → 5001
        if (!ledger.scaleExists(tenantId, scaleId)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "scale_id 指向的量表不存在或不属当前租户: " + scaleId
                            + " —— 基线评估必须引用已建的量表（FK scale）。"
                            + "🛑 报 5001（而非 404）：跨租户/不存在按同一归因，"
                            + "避免成为量表存在性的探测口（与契约跨租户资源一律 403 的纪律同族）");
        }
        String scaleVersion = ledger.scaleVersion(tenantId, scaleId);

        // ④ 测量人在职（FK 预检）—— 引用完整性属业务规则 → 5001
        if (!ledger.staffExists(tenantId, measureOperator)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "measure_operator 指向的员工不存在或已离职: " + measureOperator
                            + " —— 基线评估的测量人必须是本租户在职 staff（FK staff）");
        }

        // ⑤ 维度分 + 总分校验
        validateScores(req.dimensionScores(), req.totalScore());

        // ⑥ metrics_json（维度分 + 总分）与 diagnosis 标记
        String metricsJson = buildMetricsJson(req.dimensionScores(), req.totalScore());
        String diagnosisJson = buildDiagnosisJson();

        // ⑦ migratable 四要素推导（不接收调用方声明）
        boolean migratable = itemGroupId != null && scaleVersion != null;

        BaselineAssessmentRow row = new BaselineAssessmentRow(
                UUID.randomUUID(),
                customerId,
                scaleId,
                itemGroupId,
                lockedGroup.label(),
                metricsJson,
                diagnosisJson,
                migratable,
                measureOperator,
                null,  // assist_operator 契约未冻结 → null
                Instant.now(),
                false,  // legacy：上线后新基线，恒 false
                createdBy);

        ledger.insertBaseline(tenantId, row);
        return row;
    }

    // ==================================================================
    // 二、C3 评估详情
    // ==================================================================

    /**
     * C3 —— 按 assessment_id + customer_id 取基线评估详情。
     *
     * <p>🛑 双键过滤（路径两段都参与）：见 {@link AssessmentLedger#findById}。
     * 查无 → {@code 404 NOT_FOUND}（契约 C3 唯一声明的错误 = 404）。
     *
     * @return 详情（控制器据此按角色裁剪 migratable）
     */
    public BaselineAssessmentRow getAssessment(String tenantId, UUID customerId, UUID assessmentId) {
        AssessmentLedger.validateTenantId(tenantId);
        if (customerId == null || assessmentId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "customer_id 与 assessment_id 均必填（C3 路径两段）");
        }
        BaselineAssessmentRow row = ledger.findById(tenantId, assessmentId, customerId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "基线评估不存在: " + assessmentId + "（或不属于客户 " + customerId
                            + " / 当前租户）。🛑 不区分『不存在』与『不属于』——"
                            + "两者统一 404，避免成为资源存在性的探测口");
        }
        return row;
    }

    // ==================================================================
    // 三、出站字段裁剪（C3 的 migratable 客户不下发）
    // ==================================================================

    /**
     * 判断指定角色是否可见 {@code migratable}（契约 {@code x-visible-to} 不含 client）。
     *
     * <p>fail-closed：{@code role == null}（匿名）按客户处理 —— 不下发。
     */
    public static boolean migratableVisibleTo(VisibilityRole role) {
        return role != null && role != VisibilityRole.CLIENT;
    }

    /** C2 校验失败统一归 {@code 5001}（契约只声明 200 + 422）：分龄锁定缺失/非法。 */
    private static ScaleDomain.AgeGroup parseAgeGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "age_group_locked 缺失 —— 契约 C2 description 逐字『缺分龄锁定 → 提交阻断』。"
                            + "分龄锁定是『同源复评调取同一分龄题组』的前提，缺它则后续周期评估无可比性");
        }
        try {
            return ScaleDomain.AgeGroup.parse(raw);
        } catch (BizException e) {
            // 🛑 归 5001 而非 1001：契约 C2 description 点名「缺分龄锁定 → 提交阻断」，
            //    分龄锁定错误属业务规则层（不是参数格式层）
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    e.getDevMessage() + "（契约 C2 三板斧之一『缺分龄锁定』，归 5001/422）");
        }
    }

    private static UUID parseUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 缺失 —— 契约 C2 的 required 字段（参数格式层，归 400）");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'（参数格式层，归 400）");
        }
    }

    /**
     * 校验维度分（恰 7 项，每项 0–16）与总分（0–112 且 = Σ维度分）。
     *
     * <h2>🛑 自洽性校验是必须的</h2>
     * {@code total_score != Σ dimension_scores} 意味着调用方给的两份数据互相矛盾。
     * 落库后，同源复评的改善率分母取总分、分子取模块分，口径一旦漂移会静默失真。
     * 故此处把"总分 = 各维度之和"当作硬校验，而不是相信库里的两个字段各自成真。
     */
    private static void validateScores(List<Integer> dimensionScores, Integer totalScore) {
        if (dimensionScores == null || dimensionScores.size() != ScaleDomain.DIMENSION_COUNT) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "dimension_scores 必须恰为 " + ScaleDomain.DIMENSION_COUNT + " 项（7 维），"
                            + "实际 " + (dimensionScores == null ? "null" : dimensionScores.size())
                            + "。🛑 维度分是『题组量程 0–4 × 4 题 = 0–16』的直接证据，"
                            + "数量不符意味着题组不完整（契约 C2 阻断条件之一）");
        }
        int sum = 0;
        for (int i = 0; i < dimensionScores.size(); i++) {
            Integer v = dimensionScores.get(i);
            if (v == null || v < 0 || v > 16) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "dimension_scores[" + i + "] 须在 [0,16] 内（量程 0–4 × 4 题），"
                                + "实际 " + v + "。🛑 越界意味着题组量程 ≠ 0–4（契约 C2 阻断条件之一）");
            }
            sum += v;
        }
        if (totalScore == null || totalScore < 0 || totalScore > 112) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "total_score 须在 [0,112] 内（7 维 × 16），实际 " + totalScore);
        }
        if (sum != totalScore) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "total_score（" + totalScore + "）与 Σ dimension_scores（" + sum
                            + "）不一致 —— 两份数据互相矛盾。落库后同源复评的改善率分母会漂移，"
                            + "故此处直接阻断（数据自洽是判定链可回放的前提）");
        }
    }

    /** metrics_json：维度分 + 总分（C2 契约形状的落库载体）。 */
    private static String buildMetricsJson(List<Integer> dimensionScores, Integer totalScore) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("dimension_scores", dimensionScores);
        metrics.put("total_score", totalScore);
        return serialise(metrics);
    }

    /**
     * diagnosis_json —— 🛑 契约 C2 无 diagnosis 输入，落显式标记而非编造。
     *
     * <p>库层 {@code NOT NULL} 逼出一个值，而契约从未冻结"结构化主诉 + 优先级"的入参形状。
     * 落 {@code {"_missing": true}} 使"契约未冻结"这件事在数据里可辨认，而不是
     * 落一个看起来像有值、实为臆造的空对象（后者会让复查者误以为主诉已录）。
     */
    private static String buildDiagnosisJson() {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("_missing", true);
        diagnosis.put("_note", "契约 C2 未冻结『结构化主诉 + 优先级』入参形状，"
                + "本服务不替契约发明诊断内容 —— 该缺口已登记（见 AssessmentService 类注释四）");
        return serialise(diagnosis);
    }

    private static String serialise(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "基线评估元数据序列化失败: " + e.getMessage());
        }
    }

    /** C1 入口 —— 复用题库服务的读取面（含 client，不在此做角色门）。 */
    public List<ScaleItemRow> listItems(String tenantId, String ageGroup,
                                        String dimension, String version) {
        return itemBank.listItems(tenantId, ageGroup, dimension, version);
    }
}