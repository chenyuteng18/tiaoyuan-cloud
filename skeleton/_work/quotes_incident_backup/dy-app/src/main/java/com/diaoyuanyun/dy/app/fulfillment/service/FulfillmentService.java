package com.diaoyuanyun.dy.app.fulfillment.service;

import com.diaoyuanyun.dy.app.fulfillment.domain.DailyReportRow;
import com.diaoyuanyun.dy.app.fulfillment.domain.FulfillmentGateGuard;
import com.diaoyuanyun.dy.app.fulfillment.domain.VisitRow;
import com.diaoyuanyun.dy.app.fulfillment.repository.FulfillmentLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 履约域（契约域 D · D1~D4）的<b>唯一业务落点</b>。
 *
 * <h2>端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  D1 POST /customers/{id}/visits          → {@link #createVisit}
 *  D2 GET  /customers/{id}/visits          → {@link #listVisits}
 *  D3 POST /customers/{id}/daily-reports   → {@link #submitDailyReport}
 *  D4 GET  /customers/{id}/daily-reports   → {@link #listDailyReports}
 * </pre>
 *
 * <h2>错误码（对齐各端点 responses 声明集，失效模式 18）</h2>
 * <pre>
 *  D1 = 200 + 403(GateMissing) + 409(VersionConflict)  ⇒ 四道闸门 2002；visit_no 冲突 4001
 *  D2 = 200                                           ⇒ 仅 200（无 403/404/401）
 *  D3 = 200 + 400(ValidationFailed)                   ⇒ 参数错 1001；重复填报 4001(replay)？
 *  D4 = 200                                           ⇒ 仅 200
 * </pre>
 * 尤其 D1：四个前置缺任一的 403 归 {@link GateMissingException}（2002）+ 逐字
 * {@code data.missing_items[]}，由 {@link FulfillmentGateGuard} 承载顺序与字面。
 *
 * <h2>🛑 D1 的四道闸门（PRD P0-08）</h2>
 * 禁忌通过 / 知情同意书已签 / 调理协议已签 / 方案已确认且有效 四者全过才核销，
 * 缺任一 403 并逐字给缺失项名。见 {@link FulfillmentGateGuard} 类注释。
 *
 * <h2>🛑 D2/D4 含 client（不贴码），但出参有字段裁剪</h2>
 * <ul>
 *   <li>{@code VisitData.gate_check_json} / {@code abnormal_note} 的
 *       {@code x-visible-to} <b>不含 client</b> ⇒ 客户响应里这两个键<b>缺席</b>；</li>
 *   <li>{@code DailyReportData} 无客户不可见字段 ⇒ 全量下发。</li>
 * </ul>
 * 与 B4/C3 同款：含 client 的端点按"字段档位"裁剪，不在端点级关门。
 *
 * <h2>🛑 客户维度全局唯一账本（U2）</h2>
 * D1 的 {@code visit_no} 是<b>客户维度</b>递增序号（不是门店本地计数），
 * 由 {@link FulfillmentLedger#nextVisitNo} 事务内取号、{@code UNIQUE(customer_id, visit_no)}
 * 兜底并发。跨店通兑只认这个中央计数。
 *
 * <h2>D3 的重复提交（唯一键 uq_daily_report_customer_date）</h2>
 * 同一客户同一业务日重复填报，库层唯一索引会挡（23505）。服务层前置判断
 * {@link FulfillmentLedger#reportExists}，把冲突翻译成可读的"该日已填报"，
 * 归 {@code VERSION_CONFLICT(4001)}（契约 D3 无 409 声明，但该码是幂等/重复的既定语义——
 * 此处用 4001 且文案明确"重复"，见方法注释）。
 */
@Service
public class FulfillmentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FulfillmentLedger ledger;

    public FulfillmentService(FulfillmentLedger ledger) {
        this.ledger = ledger;
    }

    // ==================================================================
    // D1 服务核销（四道闸门 + 全局账本）
    // ==================================================================

    /**
     * D1 入参 —— 一次核销的原始事实。
     *
     * @param servingStoreId 服务门店（跨店通兑时 ≠ 归属店）
     * @param planId         方案
     * @param planVersion    方案版本（CHECK ≥ 1）
     * @param partMethod     部位/手法（可空）
     * @param durationMin    时长（可空，>0）
     * @param preFeedback    服务前反馈（可空）
     * @param postFeedback   服务后反馈（可空）
     * @param abnormalNote   异常记录（可空，客户不下发）
     */
    public record CreateVisitRequest(
            String servingStoreId,
            String planId,
            Integer planVersion,
            String partMethod,
            Integer durationMin,
            String preFeedback,
            String postFeedback,
            String abnormalNote) {
    }

    /**
     * D1 服务核销。
     *
     * <p>顺序（每步失败归不同码，见类注释）：
     * <ol>
     *   <li>解析 UUID（serving_store_id / plan_id；非法归 400）；</li>
     *   <li><b>四道闸门</b>（缺任一 403 GATE_MISSING + 逐字 missing_items）；</li>
     *   <li>事务内取号 visit_no（客户维度 MAX+1）+ 落库；</li>
     * </ol>
     *
     * @return 落库后的核销（供控制器回显 VisitData 形状）
     */
    public VisitRow createVisit(String tenantId, UUID customerId,
                                CreateVisitRequest req, String createdBy) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失（路径参数）");
        }
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "核销请求体缺失");
        }
        UUID servingStore = uuid(req.servingStoreId(), "serving_store_id");
        UUID planId = uuid(req.planId(), "plan_id");
        int planVersion = req.planVersion() == null ? 1 : req.planVersion();
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "plan_version 须 ≥ 1：实际=" + planVersion);
        }
        if (req.durationMin() != null && req.durationMin() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "duration_min 须 > 0：实际=" + req.durationMin());
        }

        // 🛑 四道闸门（顺序即语义：禁忌→同意书→协议→方案）
        FulfillmentGateGuard.assertAllGatesPassed("D1 POST /customers/{id}/visits",
                new FulfillmentGateGuard.GateState(
                        ledger.screeningPassed(tenantId, customerId),
                        ledger.consentSigned(tenantId, customerId),
                        ledger.agreementSigned(tenantId, customerId),
                        ledger.planApproved(tenantId, customerId)));

        // 四道闸门结果快照（落库，客户不下发）
        String gateCheckJson = buildGateCheckJson(customerId);

        int visitNo = ledger.nextVisitNo(tenantId, customerId);

        VisitRow row = new VisitRow(
                UUID.randomUUID(), customerId, servingStore, planId, planVersion,
                gateCheckJson, visitNo,
                req.partMethod(), req.durationMin(),
                req.preFeedback(), req.postFeedback(), req.abnormalNote(),
                Instant.now(), createdBy);

        try {
            ledger.insertVisit(tenantId, row);
        } catch (DuplicateKeyException e) {
            // 🛑 visit_no 并发冲突：UNIQUE(customer_id, visit_no) 兜底。
            //    归 409 VERSION_CONFLICT —— "第 N 次的账本序号已被并发占用"。
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "客户维度服务序号并发冲突（visit_no=" + visitNo + "）—— 请重试。"
                            + "U2：服务次数是全局唯一账本，序号由服务端事务内取号，"
                            + "并发下的重复由唯一索引兜底");
        }
        return row;
    }

    // ==================================================================
    // D2 服务记录
    // ==================================================================

    /**
     * D2 —— 客户维度服务记录分页（按 visit_no 升序）。
     *
     * <p>含 client（不贴码）。返回 {@code items[] + total + page + page_size}。
     * 🛑 {@code gate_check_json} / {@code abnormal_note} 客户不下发，由控制器裁剪。
     */
    public List<VisitRow> listVisits(String tenantId, UUID customerId, int page, int pageSize) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失（路径参数）");
        }
        return ledger.findByCustomer(tenantId, customerId, page, pageSize);
    }

    public int countVisits(String tenantId, UUID customerId) {
        return ledger.countByCustomer(tenantId, customerId);
    }

    /** 客户本周（周一为起点）填报天数 —— D3 出参 weekly_count 的 X。 */
    public int weeklyReportDayCount(String tenantId, UUID customerId, LocalDate today) {
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        return ledger.weeklyReportDayCount(tenantId, customerId, monday);
    }

    // ==================================================================
    // D3 每日填报
    // ==================================================================

    /**
     * D3 入参 —— 契约 {@code DailyReportRequest} 的冻结形状。
     */
    public record SubmitDailyReportRequest(
            LocalDate date,
            String answersJson,
            String source) {
    }

    /**
     * D3 每日填报提交。
     *
     * <p>含 client（不贴码）。顺序：
     * <ol>
     *   <li>date / source / answers 校验（缺/非法归 400，契约 D3 声明了 400）；</li>
     *   <li>重复填报判断（同客户同日 → 409 VERSION_CONFLICT，见类注释）；</li>
     *   <li>落库。</li>
     * </ol>
     */
    public DailyReportRow submitDailyReport(String tenantId, UUID customerId,
                                            SubmitDailyReportRequest req, String createdBy) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失（路径参数）");
        }
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "填报请求体缺失");
        }
        LocalDate date = req.date();
        if (date == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "date 缺失（业务日）");
        }
        if (req.source() == null || req.source().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "source 缺失 —— 必须标『客户』或『代核』（代录须标『代核』）");
        }
        if (req.answersJson() == null || req.answersJson().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "answers_json 缺失");
        }

        // 重复填报：同客户同日唯一键
        if (ledger.reportExists(tenantId, customerId, date)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "该客户在业务日 " + date + " 已有填报 —— 每日填报按 (customer_id, date) 唯一，"
                            + "重复提交被拒（不改写历史）");
        }

        DailyReportRow row;
        try {
            row = new DailyReportRow(
                    UUID.randomUUID(), customerId, date, req.answersJson(),
                    req.source(), Instant.now(), createdBy);
        } catch (BizException e) {
            // 领域构造期校验（source 两值等）已在上面挡，这里兜底重抛
            throw e;
        }

        try {
            ledger.insertDailyReport(tenantId, row);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "该客户在业务日 " + date + " 已有填报（并发冲突）—— 请勿重复提交");
        }
        return row;
    }

    // ==================================================================
    // D4 填报记录
    // ==================================================================

    /** D4 —— 客户填报记录（按 date 降序）。含 client（不贴码）。 */
    public List<DailyReportRow> listDailyReports(String tenantId, UUID customerId) {
        FulfillmentLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失（路径参数）");
        }
        return ledger.findReports(tenantId, customerId);
    }

    // ==================================================================
    // 出站裁剪（D2 的 gate_check_json / abnormal_note 客户不下发）
    // ==================================================================

    /**
     * 判断指定角色是否可见 {@code gate_check_json} / {@code abnormal_note}。
     *
     * <p>契约 {@code VisitData} 两字段 {@code x-visible-to: [therapist, meridian, admin]}。
     * fail-closed：{@code role == null}（匿名）按客户处理 —— 不下发。
     */
    public static boolean visitInternalVisibleTo(VisibilityRole role) {
        return role != null && role != VisibilityRole.CLIENT;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 四道闸门结果快照（落 gate_check_json；只记状态，不含任何客户 PII）。 */
    private String buildGateCheckJson(UUID customerId) {
        Map<String, Object> gate = new LinkedHashMap<>();
        // 🛑 四道闸门已在上面 assert 全过，此处快照恒 true ——
        //    这是"执行时点"的四道结果，供 D2 追溯"这次核销是通过哪四道闸门进来的"
        gate.put("screening_passed", true);
        gate.put("consent_signed", true);
        gate.put("agreement_signed", true);
        gate.put("plan_approved", true);
        gate.put("customer_confirmed", true);
        try {
            return MAPPER.writeValueAsString(gate);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "四道闸门结果快照序列化失败: " + e.getMessage());
        }
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 缺失");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }
}