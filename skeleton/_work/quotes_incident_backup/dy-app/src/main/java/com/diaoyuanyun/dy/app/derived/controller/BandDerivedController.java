package com.diaoyuanyun.dy.app.derived.controller;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S1-5 派生结果端点（契约 §2.1 E4）—— <b>仅 staff 可调</b>。
 *
 * <h2>契约逐字（{@code /customers/{id}/band/derived}）</h2>
 * <pre>
 *   summary: E4 派生结果（A3 / AS / effect_verdict / 退款资格）—— 仅 staff
 *   description: 🔴 客户 token 调本接口 → 403 VISIBILITY_DENIED，
 *                data.denied_fields 回显被拒字段名。
 *   x-callable-roles: [therapist, meridian, admin]
 *   x-client-explicitly-denied: true
 * </pre>
 *
 * <h2>它为什么必须真的存在（而不是只写个 403 桩）</h2>
 * S1-5 验收② 是「客户端请求派生字段<b>一律 403</b>（真请求断言，非前端不渲染）」。
 * 若这个端点上只挂一个无条件 403，那么"客户被拒"这件事<b>无法区分</b>是
 * "因为他是客户"还是"因为本端点对谁都拒" —— 后者会通过验收②，却让 E4 对 staff 也失效。
 * 故本端点对 staff 正常返回派生字段，客户则在<b>入站拦截器</b>处就被拒
 * （{@code DerivedVisibilityInterceptor} 按角色判定，不在此控制器里判）。
 *
 * <h2>🛑 它为什么不下发 {@code gap_reason}</h2>
 * 本端点的出参契约是 {@code BandDerivedData}，只含 ④ 派生结果五项
 * （{@code a3_applicable / a3_value / as_value / effect_verdict / refund_eligibility}）。
 * ③ 缺口原因属另一个字段组，不在本接口的 scope 内 ——
 * 多下发一个"顺手就有的"字段，正是可见性裁剪最容易失守的方式。
 *
 * <h2>数据来源：本次为<b>桩</b>，不落库</h2>
 * 派生结果的权威存储是 {@code cycle_assessment} / {@code verdict}（V5 已建表）。
 * 本轮 S1-5 的验收是"只<b>在服务端计算</b> + 客户一律 403 + 响应体不含"，
 * 即<b>计算与防线</b>，不含"判定链落库"（那是后续判定的工单项）。
 * 故本端点用固定的样例入参驱动真实引擎 —— 这样"算出来的数来自配置口径"
 * 是被真请求证过的，而不是只在单测里成立。落库接上后本类换一次取数即可，
 * 引擎与防线零改动。
 */
@RestController
@RequestMapping("/api/v1/customers/{id}/band")
public class BandDerivedController {

    private final AdherenceEngine adherenceEngine;
    private final EffectVerdictEngine verdictEngine;
    private final VerdictConfidenceEngine confidenceEngine;

    public BandDerivedController(AdherenceEngine adherenceEngine,
                                 EffectVerdictEngine verdictEngine,
                                 VerdictConfidenceEngine confidenceEngine) {
        this.adherenceEngine = adherenceEngine;
        this.verdictEngine = verdictEngine;
        this.confidenceEngine = confidenceEngine;
    }

    /**
     * E4 {@code GET /api/v1/customers/{id}/band/derived}。
     *
     * <p>权限用 {@code customer:read}（骨架权限表里 staff 侧角色持有）；
     * 🛑 客户 <b>不</b>持有该权限，故客户即使绕过入站拦截器也会在这里被 2001 拦下 ——
     * 这是"两层防线"在本端点的体现，不是冗余。
     *
     * @param id 客户 ID（本轮为桩，不据此取数；保留以固定契约路径形状）
     */
    @StaffOnly(clientDeniedFields = {
            "a3_applicable", "a3_value", "as_value", "effect_verdict", "refund_eligibility“})
    @RequirePermission(”customer:read")
    @GetMapping("/derived")
    public Result<Object> derived(@PathVariable("id") String id) {
        Map<String, Object> data = new LinkedHashMap<>();

        // 桩数据：A1 到店履约 1.0 / A3 手环 1.0（同意佩戴）/ A4 生活方式核对 1.0，应填 14 天。
        // 用真实引擎算，故 AS 值与判定分支都来自配置口径（config #4/#5/#33/#45）。
        var adherence = adherenceEngine.compute(new AdherenceEngine.AdherenceInput(14, Map.of(
                "A1", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A3", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE),
                "A4", AdherenceEngine.DimensionInput.ofApplicable(BigDecimal.ONE))));

        // A3 是否适用：契约 E4 原文「A3 applicable=False（无有效同步数据）时不返回 a3_value ——
        // 避免『0 值』被误读为『戴了 0 天』」。
        boolean a3Applicable = true;
        data.put("a3_applicable", a3Applicable);
        if (a3Applicable) {
            // 桩：A3 = 1.0（戴满）。真实实现取 band_daily_coverage 的达标比。
            data.put("a3_value", BigDecimal.ONE);
        }
        if (adherence.asValue() != null) {
            data.put("as_value", adherence.asValue());
        }

        // 效果判定：基线 12 → 复评 8（Δ=4 ≥ MCID 3）⇒ 改善候选，E1/E2 需人工确认。
        var verdict = verdictEngine.decide(new EffectVerdictEngine.ImprovementInput(
                new EffectVerdictEngine.SameOriginAssert(true, true, true), 12, 8));
        if (verdict.verdict() != null) {
            data.put("effect_verdict", verdict.verdict().label());
        } else {
            // 候选未定（改善侧需人工分 E1/E2）→ 按硬纪律 #6 给 TBD，不编一个分支
            data.put("effect_verdict", com.diaoyuanyun.dy.common.result.ContractTbd.TBD);
        }

        // 退款资格：🛑 只反映"依从侧门槛是否成立"，不是完整退款判定
        // （完整判定还要叠加效果判定 / 责任主体 / config #10 的入口 A|B 二分通路）。
        data.put("refund_eligibility", adherence.adherenceSideGateMet());

        // 判定依据摘要（不是 evidence_snapshot 全集；后者属 F 域且客户与调理师端一律 403）。
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("delta", verdict.delta());
        basis.put("baseline_zero", verdict.baselineZero());
        basis.put("requires_human", verdict.requiresHuman());
        basis.put("adherence_state", adherence.state().label());
        basis.put("mcid_threshold", com.diaoyuanyun.dy.common.result.ContractTbd.TBD);
        data.put("verdict_basis", basis);

        return Result.ok(data, MDC.get("traceId"));
    }

    /**
     * 口径自描述（staff 只读）—— 把"这些数来自 config 而非代码"变成可读的运行时事实。
     *
     * <p>它<b>不</b>下发具体阈值数字：一旦这里出现 {@code 0.80} 或 {@code 3}，
     * 它就会变成事实上的常量副本，而配置改了它不改时<b>不会报错</b>。
     * 需要看数值的场合是配置表本身。
     */
    @StaffOnly(clientDeniedFields = {"derived_fields", "thresholds_from_config", "engines“})
    @RequirePermission(”customer:read")
    @GetMapping("/derived/contract")
    public Result<Object> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", "E4 /customers/{id}/band/derived");
        m.put("callable_roles", java.util.List.of("therapist", "meridian", "admin"));
        m.put("client_explicitly_denied", true);
        m.put("derived_fields", new java.util.TreeSet<>(
                com.diaoyuanyun.dy.security.visibility.DerivedFields.derivedFieldNames()));
        m.put("thresholds_from_config", true);
        m.put("threshold_values_omitted", "为避免本响应成为配置值的第二份副本（改配置不改此处时不报错）");
        m.put("engines", java.util.List.of("AdherenceEngine", "EffectVerdictEngine", "VerdictConfidenceEngine"));
        return Result.ok(m, MDC.get("traceId"));
    }
}