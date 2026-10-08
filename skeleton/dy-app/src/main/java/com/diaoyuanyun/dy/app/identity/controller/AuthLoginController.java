package com.diaoyuanyun.dy.app.identity.controller;

import com.diaoyuanyun.dy.app.identity.service.LoginService;
import com.diaoyuanyun.dy.common.result.Result;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A1 {@code POST /api/v1/auth/login} —— 签发 token（契约域 A 第 1 行）。
 *
 * <h2>🛑 路径两段式（与 {@code AuthMeController} 同律，教训逐字继承）</h2>
 * 契约 paths 键是 {@code /auth/login}，基路径 {@code /api/v1} 由 servers.url 承载。
 * 本类必须写 {@code @RequestMapping("/api/v1")} + {@code @PostMapping("/auth/login")}，
 * <b>不得</b>只写 {@code @RequestMapping("/auth/login")} —— 那会让三端 UI 按
 * 契约请求 {@code /api/v1/auth/login} 拿到 404（A2 的教训，单测直调不经过路由故无测试红）。
 *
 * <h2>🛑 不贴 {@code @RequirePermission} / {@code @StaffOnly}（理由见 LoginService 类注释）</h2>
 * 登录发生在鉴权之前；客户（mp 端）是本端点的第一调用方。
 * 三层防线收敛为服务层的参数校验（400）+ 凭证比对（401，统一文案防账号枚举）。
 *
 * <h2>🛑 请求体逐键校验在服务层，不在控制器</h2>
 * 控制器只做 {@code Result.ok(...)} —— 校验逻辑唯一落点是 {@code LoginService}，
 * 避免"控制器一处、服务层一处"的两份口径（A2 的同款纪律）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthLoginController {

    /**
     * 控制器调用计数 —— 供 E2E 断言"请求真的打到了本控制器"
     * （与 {@code AuthMeController.CONTROLLER_INVOCATIONS} 同款可观测量）。
     */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final LoginService service;

    public AuthLoginController(LoginService service) {
        this.service = service;
    }

    /**
     * A1 —— 登录，签发 token。
     *
     * <p>契约 200 → {@code LoginData}；错误码：{@code 1001}（参数不合法 / client_end 越界）
     * ｜{@code 1002}（账号或凭证不匹配，统一文案）。403（TenantMismatch）在本端点
     * 无触发路径：登录请求不带 token，{@code TenantContextFilter} 不会建立上下文，
     * 也就不存在"头与 token 租户不一致"的比较对象。
     */
    @PostMapping("/auth/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        Map<String, Object> data = service.login(
                text(body, "account"),
                text(body, "credential"),
                text(body, "client_end"));
        return Result.ok(data, MDC.get("traceId"));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    /** 自证计数（供 E2E 断言；不参与任何授权判定）。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    /** 重置计数器（供测试隔离）。 */
    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
    }
}
