package com.diaoyuanyun.dy.app.settlement.service;

import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement;
import com.diaoyuanyun.dy.app.settlement.repository.SettlementStatementLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 结算单落账服务 —— {@code settlement_statement} 的<b>唯一生产写入方</b>
 * （V24 迁移建表；ProvisioningBoundaryGateTest 已登记 44 张账）。
 *
 * <h2>预演与落账是两个显式动作</h2>
 * {@code POST /settlement/preview}（既有）只算不落；本服务的 {@link #commit}
 * 是把一次<b>已经算出来的</b>结论落账。调用方（控制器）必须同时携带
 * <b>输入</b>与<b>计算结果</b> —— 服务端不信任"结果就是输入算出来的"这个口头承诺，
 * 而是在落账前<b>用同一算法重算一遍并逐字段比对</b>（{@link #recomputeMatches}）。
 * 客户端篡改结果字段（比如把别家的贡献改小）会在这一步被拦下 ——
 * 账本只收"可复算"的账。
 *
 * <h2>幂等：同输入 = 同一张单</h2>
 * {@code requestHash = SHA-256(规范串(period | closingStoreId | 按店名升序的 visits | ecc | loss))}。
 * 规范串用 TreeMap 升序、金额 {@code toPlainString}，保证同输入跨次调用哈希一致。
 * 重复 commit 返回 {@code ALREADY_EXISTS} + 既有单据 ID —— 与
 * {@code IDEMPOTENT_REPLAY}(4002) 语义对齐（返回首次结果，不覆盖）。
 *
 * <h2>周期守卫</h2>
 * period 必须是 {@code YYYY-MM} 且<b>不得晚于当前月</b>：给未来月份落账 =
 * 给还没发生的业务记账 —— 财务侧最忌讳的一类幽灵账。当月允许（月内滚动落账）。
 */
@Service
public class SettlementStatementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementStatementService.class);

    /** 状态常量：返回值不撒谎 —— CREATED=新账，ALREADY_EXISTS=命中幂等键。 */
    public static final String CREATED = "CREATED";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";

    private static final String PERIOD_PATTERN = "^\\d{4}-\\d{2}$";

    private final SettlementStatementLedger ledger;
    private final CrossStoreSettlement settlement;
    private final ObjectMapper mapper = new ObjectMapper();

    public SettlementStatementService(SettlementStatementLedger ledger,
                                      CrossStoreSettlement settlement) {
        this.ledger = ledger;
        this.settlement = settlement;
    }

    /** 落账结果：状态 + 单据 ID（ALREADY_EXISTS 时为既有 ID）。 */
    public record CommitOutcome(String status, UUID statementId) {
    }

    /**
     * 落账一次结算结论。
     *
     * @param period        结算周期 YYYY-MM（不得晚于当前月）
     * @param closingStoreId 结案门店
     * @param visitsByStore  各店服务次数（key=storeId）
     * @param eccUnits       应分 ECC 总量
     * @param lossYuan       应分摊退款损失总额
     * @param result         计算结果（服务端重算比对后才允许落账）
     * @param createdBy      操作人（总部账号）
     */
    public CommitOutcome commit(String period, String closingStoreId,
                                Map<String, Integer> visitsByStore,
                                BigDecimal eccUnits, BigDecimal lossYuan,
                                CrossStoreSettlement.SettlementResult result,
                                String createdBy) {
        validatePeriod(period);
        requireNonNegative(eccUnits, "eccUnits");
        requireNonNegative(lossYuan, "lossYuan");
        if (closingStoreId == null || closingStoreId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "closingStoreId 不得为空");
        }
        if (visitsByStore == null || visitsByStore.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "visitsByStore 不得为空（缺失不得补 0，见 CrossStoreSettlement）");
        }
        if (!recomputeMatches(closingStoreId, visitsByStore, eccUnits, lossYuan, result)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "结算结论与输入不匹配：账本只收可复算的账（重算结果与提交结果不一致）");
        }

        String tenantId = com.diaoyuanyun.dy.tenancy.context.TenantContext.current().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH, "无租户上下文，拒绝落账");
        }
        String requestHash = requestHash(period, closingStoreId, visitsByStore, eccUnits, lossYuan);

        // 幂等快路径：同输入已落过账 —— 返回既有单，绝不产第二份
        var existing = ledger.findIdByRequestHash(tenantId, requestHash);
        if (existing.isPresent()) {
            return new CommitOutcome(ALREADY_EXISTS, existing.get());
        }

        String statementId = UUID.randomUUID().toString();
        int inserted = ledger.insert(tenantId, new SettlementStatementLedger.NewStatement(
                statementId, period, closingStoreId,
                visitsByStore.size(), result.totalVisits(),
                eccUnits, lossYuan, result.splitApplied(),
                result.otherStoreVisitRatio(),
                payload(result), requestHash, createdBy));
        if (inserted == 0) {
            // 并发双击：插入瞬间另一事务已落同键 —— 语义仍为"返回既有结果"
            var raced = ledger.findIdByRequestHash(tenantId, requestHash);
            return new CommitOutcome(ALREADY_EXISTS,
                    raced.orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR,
                            "幂等键命中但回读不到既有单据（并发窗口异常）")));
        }
        log.info("结算单落账: period={} closingStore={} stores={} ecc={} loss={} hash={}",
                period, closingStoreId, visitsByStore.size(), eccUnits, lossYuan, requestHash);
        return new CommitOutcome(CREATED, UUID.fromString(statementId));
    }

    /** 按周期取对账清单。 */
    public java.util.List<SettlementStatementLedger.StatementRow> listByPeriod(String period) {
        validatePeriod(period);
        String tenantId = com.diaoyuanyun.dy.tenancy.context.TenantContext.current().tenantId();
        return ledger.listByPeriod(tenantId, period);
    }

    /** 定点读取某单的不可变快照（JSON 文本）。 */
    public String payloadOf(UUID statementId) {
        String tenantId = com.diaoyuanyun.dy.tenancy.context.TenantContext.current().tenantId();
        return ledger.payloadOf(tenantId, statementId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "结算单不存在: " + statementId));
    }

    /**
     * 周期对账 CSV（RFC 4180：含逗号/引号/换行的字段加引号转义）。
     * 表头与列序冻结 —— 对账方（总部财务）按列序做机器解析，列序变动 = 对账脚本全炸。
     */
    public String csvByPeriod(String period) {
        var rows = listByPeriod(period);
        StringBuilder sb = new StringBuilder(
                "statement_id,period,closing_store_id,stores_involved,visits_total,"
                        + "ecc_units,loss_yuan,split_applied,other_store_ratio,request_hash,created_by\r\n");
        for (var r : rows) {
            sb.append(csvField(r.statementId().toString())).append(',')
                    .append(csvField(r.period())).append(',')
                    .append(csvField(r.closingStoreId())).append(',')
                    .append(r.storesInvolved()).append(',')
                    .append(r.visitsTotal()).append(',')
                    .append(r.eccUnits().toPlainString()).append(',')
                    .append(r.lossYuan().toPlainString()).append(',')
                    .append(r.splitApplied()).append(',')
                    .append(r.otherStoreRatio().toPlainString()).append(',')
                    .append(csvField(r.requestHash())).append(',')
                    .append(csvField(r.createdBy() == null ? "" : r.createdBy()))
                    .append("\r\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 服务端重算并逐字段比对（防"结果被客户端改过"）。 */
    private boolean recomputeMatches(String closingStoreId, Map<String, Integer> visitsByStore,
                                     BigDecimal eccUnits, BigDecimal lossYuan,
                                     CrossStoreSettlement.SettlementResult submitted) {
        CrossStoreSettlement.SettlementResult recalculated;
        try {
            recalculated = settlement.allocate(
                    CrossStoreSettlement.contributions(visitsByStore, closingStoreId),
                    eccUnits, lossYuan);
        } catch (RuntimeException e) {
            return false;
        }
        return recalculated.splitApplied() == submitted.splitApplied()
                && recalculated.totalVisits() == submitted.totalVisits()
                && recalculated.otherStoreVisitRatio().compareTo(submitted.otherStoreVisitRatio()) == 0
                && recalculated.totalEccShare().compareTo(submitted.totalEccShare()) == 0
                && recalculated.totalLossShare().compareTo(submitted.totalLossShare()) == 0
                && recalculated.allocations().size() == submitted.allocations().size()
                && recalculated.allocations().equals(submitted.allocations());
    }

    /**
     * 规范哈希：TreeMap 升序 + toPlainString —— 同输入跨次调用哈希恒一致。
     * 🛑 不把 result 纳入哈希：result 是输入的派生物（服务端重算把关），
     * 把它纳入反而让"同输入不同算法版本"各成一张账 —— 那是重算的事，不是落账的事。
     */
    static String requestHash(String period, String closingStoreId,
                              Map<String, Integer> visitsByStore,
                              BigDecimal eccUnits, BigDecimal lossYuan) {
        StringBuilder sb = new StringBuilder();
        sb.append(period).append('|').append(closingStoreId).append('|');
        new TreeMap<>(visitsByStore).forEach((k, v) ->
                sb.append(k).append('=').append(v).append(';'));
        sb.append('|').append(eccUnits.toPlainString())
                .append('|').append(lossYuan.toPlainString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用（JVM 缺陷级异常）", e);
        }
    }

    /** payload 快照：结论的不可变 JSON（含逐店 allocations）。 */
    private String payload(CrossStoreSettlement.SettlementResult result) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("splitApplied", result.splitApplied());
            snapshot.put("otherStoreVisitRatio", result.otherStoreVisitRatio().toPlainString());
            snapshot.put("totalVisits", result.totalVisits());
            var allocations = new java.util.ArrayList<Map<String, Object>>();
            for (var a : result.allocations()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("storeId", a.storeId());
                m.put("visitCount", a.visitCount());
                m.put("eccShare", a.eccShare().toPlainString());
                m.put("lossShare", a.lossShare().toPlainString());
                m.put("splitApplied", a.splitApplied());
                allocations.add(m);
            }
            snapshot.put("allocations", allocations);
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("结算快照序列化失败", e);
        }
    }

    /** period 守卫：格式 + 不得晚于当前月。 */
    private static void validatePeriod(String period) {
        if (period == null || !period.matches(PERIOD_PATTERN)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "period 必须是 YYYY-MM 格式: " + period);
        }
        try {
            LocalDate firstOfMonth = LocalDate.parse(period + "-01");
            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
            if (firstOfMonth.isAfter(currentMonth)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "period 不得晚于当前月（不得给未来月份记账）: " + period);
            }
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "period 不是合法月份: " + period);
        }
    }

    private static void requireNonNegative(BigDecimal v, String name) {
        if (v == null || v.signum() < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 不得为空或为负");
        }
    }

    /** RFC 4180 字段转义。 */
    private static String csvField(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
