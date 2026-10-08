package com.diaoyuanyun.dy.app.settlement.controller;

import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement;
import com.diaoyuanyun.dy.app.settlement.service.SettlementStatementService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.security.permission.RequireOrgLevel;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结算单落账与对账报表端点（商用开发第二批 · E1）。
 *
 * <h2>与预演端点的关系：算、账分离</h2>
 * {@code SettlementController}（既有）的 {@code /settlement/preview} 只算不落；
 * 本类承担"把算出来的结论<b>显式落账</b>"与"按周期<b>取回对账</b>"两个动作。
 * 预演是 cheap 的（可反复），落账是严肃的（有幂等键、有快照、有审计）——
 * 两个动作必须在 API 面上分开，混成一个就会有人把预演当结算。
 *
 * <h2>全部总部专属（M5）</h2>
 * PRD M5：「跨店服务按次数占比拆分业绩；稽核看板<b>总部可见、门店/加盟商不可见</b>」。
 * 三个端点一律 {@code @RequireOrgLevel(HEADQUARTERS)}；SQL 层另有 V24 RLS 兜底。
 *
 * <h2>重算把关</h2>
 * {@code /commit} 携带输入+结果，服务端重算比对后才落账 ——
 * 账本只收"可复算"的账（详见 {@link SettlementStatementService} 类注释）。
 */
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementStatementController {

    private final SettlementStatementService service;

    public SettlementStatementController(SettlementStatementService service) {
        this.service = service;
    }

    /**
     * 落账请求：与 PreviewRequest 同构 + period。
     * 字段名 {@code @JsonProperty} 钉成 snake_case —— 与契约信封的既有形态一致
     * （本仓无全局 naming 策略，记录默认序列化 camelCase，不加注解 = 名字漂移）。
     */
    public record CommitRequest(String period,
                                @JsonProperty("closing_store_id") String closingStoreId,
                                @JsonProperty("visits_by_store") Map<String, Integer> visitsByStore,
                                @JsonProperty("ecc_units") BigDecimal eccUnits,
                                @JsonProperty("loss_yuan") BigDecimal lossYuan,
                                CrossStoreSettlement.SettlementResult result) {
    }

    /** 落账响应：状态 + 单据 ID。 */
    public record CommitResponse(String status,
                                 @JsonProperty("statement_id") String statementId) {
    }

    /**
     * 落账一次结算结论（总部唯一）。
     *
     * <p>同输入重复提交 = 幂等命中，返回 {@code ALREADY_EXISTS} + 既有单据 ID
     * （不覆盖、不产第二份账）。
     */
    @PostMapping("/commit")
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    public ResponseEntity<Result<CommitResponse>> commit(@RequestBody CommitRequest req) {
        if (req == null || req.result() == null) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    ErrorCode.VALIDATION_FAILED, "result（结算结论）不得为空 —— 落账的是结论，不是一次重算请求");
        }
        var outcome = service.commit(req.period(), req.closingStoreId(),
                req.visitsByStore(), req.eccUnits(), req.lossYuan(),
                req.result(), com.diaoyuanyun.dy.tenancy.context.TenantContext.current().staffId());
        return ResponseEntity.ok(Result.ok(new CommitResponse(
                outcome.status(), outcome.statementId().toString()), MDC.get("traceId")));
    }

    /**
     * 按周期取对账清单（总部唯一）。
     *
     * <p>窄记录出站：不含 payload 快照。逐单快照走 {@code GET /settlement/statements/{id}/payload}
     * 定点取 —— 防止列表响应膨胀成"把整本账塞给浏览器"。
     */
    @GetMapping("/statements")
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    public ResponseEntity<Result<List<SettlementStatementLedgerRow>>> statements(
            @RequestParam("period") String period) {
        var rows = service.listByPeriod(period).stream()
                .map(r -> new SettlementStatementLedgerRow(
                        r.statementId().toString(), r.period(), r.closingStoreId(),
                        r.storesInvolved(), r.visitsTotal(), r.eccUnits(), r.lossYuan(),
                        r.splitApplied(), r.otherStoreRatio(), r.requestHash(), r.createdBy()))
                .toList();
        return ResponseEntity.ok(Result.ok(rows, MDC.get("traceId")));
    }

    /** 对账清单行（API 面，snake_case 钉名 —— 对账方按字段名机器解析）。 */
    public record SettlementStatementLedgerRow(
            @JsonProperty("statement_id") String statementId,
            String period,
            @JsonProperty("closing_store_id") String closingStoreId,
            @JsonProperty("stores_involved") int storesInvolved,
            @JsonProperty("visits_total") int visitsTotal,
            @JsonProperty("ecc_units") BigDecimal eccUnits,
            @JsonProperty("loss_yuan") BigDecimal lossYuan,
            @JsonProperty("split_applied") boolean splitApplied,
            @JsonProperty("other_store_ratio") BigDecimal otherStoreRatio,
            @JsonProperty("request_hash") String requestHash,
            @JsonProperty("created_by") String createdBy) {
    }

    /**
     * 周期对账 CSV 导出（总部唯一）。
     *
     * <p>Content-Disposition 带 filename（RFC 6266：filename* 编码非 ASCII 周期名）。
     * 列序冻结 —— 对账方按列序做机器解析（见 Service#csvByPeriod）。
     */
    @GetMapping("/statements.csv")
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    public ResponseEntity<byte[]> statementsCsv(@RequestParam("period") String period) {
        String csv = service.csvByPeriod(period);
        String encoded = java.net.URLEncoder.encode("settlement-" + period + ".csv",
                java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"settlement-" + period + ".csv\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 定点读取单条结算单的不可变快照（总部唯一）。
     * 快照是"落账当时那次的结论"——算法演进后历史报表仍读快照，不重算。
     */
    @GetMapping("/statements/{statementId}/payload")
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    public ResponseEntity<Result<Map<String, Object>>> payload(
            @org.springframework.web.bind.annotation.PathVariable("statementId") String statementId) {
        UUID.fromString(statementId); // 非法 UUID → 400（由异常链统一转译）
        String json = service.payloadOf(UUID.fromString(statementId));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            return ResponseEntity.ok(Result.ok(map, MDC.get("traceId")));
        } catch (Exception e) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    ErrorCode.INTERNAL_ERROR, "结算快照损坏（不可解析的 JSON）");
        }
    }
}
