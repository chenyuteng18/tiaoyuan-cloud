package com.diaoyuanyun.dy.app.identity.controller;

import com.diaoyuanyun.dy.app.identity.domain.AuthMeDeclaration;
import com.diaoyuanyun.dy.app.identity.service.AuthMeService;
import com.diaoyuanyun.dy.common.result.Result;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2 {@code GET /api/v1/auth/me} —— 可见性档位的<b>唯一权威下发点</b>（契约域 A）。
 *
 * <h2>🛑 路径为什么写 {@code /api/v1} + {@code /auth/me} 两段</h2>
 * 契约的 {@code paths} 键是 {@code /auth/me}，而基路径由 {@code servers.url: /api/v1}
 * 承载（契约 §2.0 Base Path 约定）。两者相加才是真实 URL —— 这也是
 * {@code ContractFreezeGateTest} 比对上游表格时的口径：
 * 它把 {@code POST /api/v1/x} 归一成 {@code /x} 再与 {@code paths} 比对。
 * 🛑 故本类<b>不得</b>只写 {@code @RequestMapping("/auth/me")}：
 * 那会让端点在 {@code /auth/me} 落地，而三端 UI 按契约请求 {@code /api/v1/auth/me}
 * 会拿到 404 —— 且不会有任何测试红，因为单测直调控制器方法、不经过路由。
 *
 * <h2>🛑 本类<b>不贴</b> {@code @RequirePermission}、也<b>不贴</b> {@code @StaffOnly}</h2>
 * <ul>
 *   <li><b>不贴权限码</b>：A2 的 {@code x-callable-roles} 含 {@code client}，
 *       而 {@code PermissionRegistry} 刻意不登记 client。贴码 ⇒ 客户查自己的档位 403 ⇒
 *       三端 UI 拿不到"该渲染什么"。可调用角色的判定落在
 *       {@link AuthMeService#requireCallable}（fail-closed）。</li>
 *   <li><b>不贴 {@code @StaffOnly}</b>：A2 是 {@code x-client-forbidden: false}
 *       且<b>没有</b> {@code x-client-explicitly-denied}。两个键语义不同 ——
 *       前者说"小程序端是否生成调用面"，后者才是"仅 staff 可调"。
 *       {@code DerivedVisibilityInterceptor} 认的是后者，据前者贴注解会拦错东西。</li>
 * </ul>
 *
 * <h2>🛑 本类不做任何裁剪判断</h2>
 * "哪些键该下发"由 {@link AuthMeDeclaration#toContractData()} 唯一决定 ——
 * 控制器只做 {@code Result.ok(...)}。把裁剪判断放进控制器，就会得到
 * "控制器一处、服务层一处"的两份口径，而分叉的那一侧不报错。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthMeController {

    /**
     * 控制器调用计数 —— 供 E2E 断言"请求真的打到了本控制器"。
     *
     * <p>🛑 为什么需要它：A2 的失败模式里有一类是"被更早的拦截器拒掉"
     * （例如有人日后给它补了一个 {@code @RequirePermission}，客户静默 403）。
     * 只断言响应体不足以分辨"控制器产出了这个响应"与"拦截器短路了"，
     * 故用一个计数器把"控制器确实被进入过"变成可观测量。
     */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final AuthMeService service;

    public AuthMeController(AuthMeService service) {
        this.service = service;
    }

    /**
     * A2 —— 下发当前请求者的可见性档位声明。
     *
     * <p>契约 200 → {@code AuthMeData}；错误码：{@code 2001}（角色不可调 /
     * 无端角色无档位）｜{@code 1002}（未携带身份）｜{@code 2003}（租户 / 范围上下文不可信）。
     */
    @GetMapping("/auth/me")
    public Result<Map<String, Object>> me() {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        AuthMeDeclaration declaration = service.describeCurrent();
        return Result.ok(declaration.toContractData(), MDC.get("traceId"));
    }

    /**
     * 契约自描述（只读、<b>无任何档位取值</b>）。
     *
     * <p>它把"不贴权限码 / 不贴 StaffOnly / 两份口径来源 / 两个键按 x-visible-to 裁剪"
     * 这四件事变成<b>可被回归用例集直接读取的运行时事实</b>，而不是只写在注释里。
     *
     * <p>🛑 它<b>不</b>需要身份：内容是契约元信息，不含任何按角色变化的值。
     * 也正因如此它不能被用来探测"某角色能看到什么" —— 那是 A2 的职责。
     */
    @GetMapping("/auth/me/contract")
    public Result<Map<String, Object>> describeContract() {
        return Result.ok(service.describeRouting(), MDC.get("traceId"));
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