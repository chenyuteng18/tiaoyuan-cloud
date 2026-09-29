package com.diaoyuanyun.dy.app.settlement.controller;

import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.security.permission.RequireOrgLevel;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 跨店通兑 · 结算端点（S1-3 验收②③）。
 *
 * <h2>为什么结算端点是总部专属</h2>
 * PRD **M5**：「跨店服务按次数占比拆分业绩；稽核看板<b>总部可见、门店/加盟商不可见</b>（换账号验证）」。
 * 故 {@link RequireOrgLevel}(HEADQUARTERS) 不是随手加的装饰 —— 它是 M5 那条验收的代码形态。
 * 门店层（{@code manager} / {@code meridian} / {@code therapist}）请求本端点必须 403，
 * 这正是 S1-3 验收② 要"换账号复验"的那个动作。
 *
 * <h2>两个端点、两个层级</h2>
 * <ul>
 *   <li>{@code POST /settlement/preview} —— 业绩拆分，<b>总部唯一</b>（HEADQUARTERS）</li>
 *   <li>{@code POST /settlement/cross-store-anomaly} —— 辖区跨店异常信号，<b>区域及以上</b>（REGION）</li>
 * </ul>
 * 之所以给两个层级各留一个端点：只有一个端点就<b>无法区分</b>"层级判定生效"与"凡是非总部全拒"。
 * 有了区域层端点，测试才能断言"区域层在区域端点上放行、在总部端点上被拒"——
 * 这才证明判据是按<b>层级</b>走的，而不是按"是不是总部"走的。
 *
 * <h2>本控制器不下发任何"率"</h2>
 * 响应里只有绝对量（{@code eccShare} / {@code lossShare}）。理由见
 * {@link CrossStoreSettlement} 类注释（PRD §2.9.6 明令不新建"门店损益率"聚合指标）。
 * 注意 {@code otherStoreVisitRatio} 是<b>判定依据</b>（阈值比较用），不是看板指标，
 * 故随结果一并返回以便解释"为什么这次触发了拆分"——<b>可解释性</b>优先于字段最小化。
 */
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    private final CrossStoreSettlement settlement;

    public SettlementController(CrossStoreSettlement settlement) {
        this.settlement = settlement;
    }

    /** 业绩拆分请求。 */
    public record PreviewRequest(String closingStoreId,
                                 Map<String, Integer> visitsByStore,
                                 BigDecimal eccUnits,
                                 BigDecimal lossYuan) {
    }

    /** 跨店异常请求。 */
    public record AnomalyRequest(int distinctStoresIn30d, BigDecimal otherStoreRatio) {
    }

    /**
     * 跨店异常响应：信号 + <b>请求者身份回显</b>。
     *
     * <p>回显 {@code role} / {@code rowScope} 是为了"换账号复验"（S1-3 验收②）——
     * 同一份请求由总部账号与门店账号各发一次，能<b>一眼看清</b>是不是同一个身份在跑、
     * 范围是不是真的不同。若只返回信号本身，"换账号"这个动作是否真的换了身份
     * 就只能靠日志猜，而"我以为切了账号、其实没切"是这类验证最常见的假通过。
     */
    public record AnomalyResponse(CrossStoreSettlement.CrossStoreAnomaly anomaly,
                                  String role,
                                  String rowScope) {
    }

    /**
     * 业绩拆分（总部唯一）。
     *
     * <p><b>入参校验不在这里做</b>：缺失字段交给 {@link CrossStoreSettlement} 抛
     * {@code UnallocatableException}（→ 422），因为"次数合计为 0"这类是<b>业务规则不满足</b>，
     * 不是参数格式错（400）。两者的区别对排查很重要：400 是调用方写错了，422 是数据不足以出结论。
     */
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    @PostMapping("/preview")
    public Result<CrossStoreSettlement.SettlementResult> preview(@RequestBody PreviewRequest req) {
        List<CrossStoreSettlement.StoreContribution> contributions =
                CrossStoreSettlement.contributions(req.visitsByStore(), req.closingStoreId());
        CrossStoreSettlement.SettlementResult result =
                settlement.allocate(contributions, req.eccUnits(), req.lossYuan());
        return Result.ok(result, MDC.get("traceId"));
    }

    /**
     * 辖区跨店异常信号（区域及以上）。
     */
    @RequireOrgLevel(min = OrgLevel.REGION)
    @PostMapping("/cross-store-anomaly")
    public Result<AnomalyResponse> crossStoreAnomaly(@RequestBody AnomalyRequest req) {
        // 不引入额外查询：role / scope 本就在已验签的上下文里（ADR-07：可见性由服务端解算）。
        AnomalyResponse body = new AnomalyResponse(
                settlement.detectAnomaly(req.distinctStoresIn30d(), req.otherStoreRatio()),
                TenantContext.role(),
                TenantContext.scope());
        return Result.ok(body, MDC.get("traceId"));
    }
}