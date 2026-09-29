package com.diaoyuanyun.dy.app.identity.controller;

import com.diaoyuanyun.dy.app.identity.domain.StoreListPage;
import com.diaoyuanyun.dy.app.identity.service.StoreListService;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A3 {@code GET /api/v1/stores} —— 门店列表（按行级范围过滤的分页码，契约域 A）。
 *
 * <h2>🛑 三道闸的分工（详见 {@link StoreListService}）</h2>
 * <pre>
 *   {@code @RequirePermission("store:read")}  ← 岗位功能权限（客户不持该码）
 *   {@link StoreListService#requireCallable}  ← 契约 x-callable-roles（显式点名拒客户）
 *   行级范围（StoreScopeResolver + 仓储 WHERE） ← 只能看到自己范围内的门店
 * </pre>
 *
 * <h2>🛑 为什么贴 {@code @RequirePermission} 而<b>不贴</b> {@code @StaffOnly}</h2>
 * <ul>
 *   <li>贴权限码：契约 A3 的 {@code x-callable-roles} <b>不含 client</b>，
 *       是标准的"该给 staff 发码、不给客户发码"形态（与域 F / 域 G 同款）；</li>
 *   <li>不贴 {@code @StaffOnly}：A3 是 {@code x-client-forbidden: false}
 *       且<b>没有</b> {@code x-client-explicitly-denied}。契约里只有 E4、
 *       域 F 的 F2、域 G 五端点标了后者。把"不生成小程序调用面"读成"仅 staff 可调"
 *       会给客户换一个错误码（2001 的 StaffOnly 版而非契约的 403 语义），
 *       而错误码是客户端分支的依据。</li>
 * </ul>
 *
 * <h2>🛑 分页参数用 {@code @RequestParam(required = false)} 而非 {@code Pageable}</h2>
 * 契约的参数名是 {@code page} / {@code page_size}（{@code parameters.Page} /
 * {@code parameters.PageSize}），与 Spring Data 的 {@code Pageable} 约定
 * （{@code page} 从 0 起、{@code size} 而非 {@code page_size}）<b>不兼容</b>。
 * 用 {@code Pageable} 会得到一个"第 0 页是第一页"的端点 ——
 * 与契约 {@code minimum: 1} 冲突，且这种偏差在单测里不难被发现、
 * 在真实客户端上表现为"永远少看到一页"。
 */
@RestController
@RequestMapping("/api/v1")
public class StoreListController {

    /** 控制器调用计数 —— 供 E2E 断言"请求真的打到了本控制器"。 */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final StoreListService service;

    public StoreListController(StoreListService service) {
        this.service = service;
    }

    /**
     * A3 —— 取一页门店。
     *
     * <p>契约 200 → {@code StoreListData{items,total,page,page_size}}；
     * 403 → {@code VisibilityDenied}；400 → 分页参数越界。
     *
     * @param page     页码（缺省 1；契约 {@code parameters.Page.minimum: 1}）
     * @param pageSize 每页条数（缺省 {@value StoreListPage#DEFAULT_PAGE_SIZE}；
     *                 契约 {@code parameters.PageSize.maximum: 100}，越界直接拒）
     */
    @RequirePermission("store:read")
    @GetMapping("/stores")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        StoreListPage result = service.list(page, pageSize);
        return Result.ok(result.toContractData(), MDC.get("traceId"));
    }

    /**
     * 契约自描述（只读、免权限、<b>不含任何数据</b>）。
     *
     * <p>把"贴的是 {@code store:read} 而非 {@code @StaffOnly} / 客户被显式点名拒 /
     * 越界拒而非夹逼"这三件事变成可被回归用例集读取的运行时事实。
     *
     * <p>🛑 它<b>不</b>经过 {@link StoreListService#requireCallable}，故不受行级范围约束 ——
     * 但这是安全的：它的响应体与请求者无关（纯粹的契约元信息）。
     * 若将来有人往这个 Map 里塞任何按角色变化的值，就应该把它移出免权限区。
     */
    @GetMapping("/stores/contract")
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