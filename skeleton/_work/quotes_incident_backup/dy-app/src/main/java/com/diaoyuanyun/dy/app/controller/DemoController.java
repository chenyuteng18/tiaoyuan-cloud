package com.diaoyuanyun.dy.app.controller;

import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.gate.RequireGate;
import com.diaoyuanyun.dy.security.mask.FieldMasker;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.web.idempotent.Idempotent;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 演示控制器: 串联骨架能力 (仅用于可运行演示, 非业务流程实现)。
 *
 * <p>展示: 权限注解 / 门禁守卫 / 幂等键 / 字段裁剪 / 统一信封 / trace_id 回填。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    public record RefundView(String id, long refund, long amount, String note) {
    }

    /**
     * 无 token 的匿名请求可访问 (TenantContextFilter 不建立上下文, fail-closed)。
     *
     * <p><b>为何不能直接用 {@code Map.of(...)}</b>：{@code Map.of} 的键/值<b>一律拒收 null</b>
     * （抛 {@code NullPointerException}）。匿名访问时三个字段都是 null ⇒ NPE ⇒ 被全局处理器
     * 兜成 {@code 500 系统异常: NullPointerException} —— 而契约要求本端点返回标准信封。
     * 匿名可见的租户信息本就应当为 null（这正是 fail-closed 的证据），
     * null 是<b>真实状态</b>而非错误, 故必须用允许 null 值的 Map。
     */
    @GetMapping("/me")
    public Result<Object> me() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("tenantId", TenantContext.tenantId());
        ctx.put("role", TenantContext.role());
        ctx.put("scope", TenantContext.scope());
        return Result.ok(ctx, MDC.get("traceId"));
    }

    @RequirePermission("customer:read")
    @GetMapping("/customer")
    public Result<Object> customer() {
        return Result.ok(Map.of("customer", "c-1"), MDC.get("traceId"));
    }

    @RequireGate(items = "gate:refund_view")
    @GetMapping("/refund")
    public Result<String> refund() {
        // 字段裁剪: 无 refund 权限时 refund 字段整体不出现在 JSON (ADR-07)
        boolean canSeeRefund = "SUPER_ADMIN".equals(TenantContext.role());
        RefundView view = new RefundView("c-1", 8800L, 12000L, "ok");
        String json = FieldMasker.mask(view, canSeeRefund ? Set.of() : Set.of("refund"));
        return Result.ok(json, MDC.get("traceId"));
    }

    @Idempotent
    @PostMapping("/order")
    public Result<Object> order(@RequestBody Map<String, Object> body) {
        return Result.ok(Map.of("created", true, "echo", body), MDC.get("traceId"));
    }
}
