package com.diaoyuanyun.dy.app.customer.controller;

import com.diaoyuanyun.dy.app.customer.domain.BandWillingness;
import com.diaoyuanyun.dy.app.customer.domain.ConsentAuthScope;
import com.diaoyuanyun.dy.app.customer.domain.ConsentDataSource;
import com.diaoyuanyun.dy.app.customer.domain.CustomerArchive;
import com.diaoyuanyun.dy.app.customer.domain.Gender;
import com.diaoyuanyun.dy.app.customer.service.CustomerService;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户与档案域控制器 —— 契约域 B 的六行端点（B1~B6）。
 *
 * <h2>🛑 为什么六行<b>都不贴</b> {@code @StaffOnly}</h2>
 * 契约域 B 六行的 {@code x-client-forbidden} <b>全部为 false</b>，
 * 且<b>没有一行</b>带 {@code x-client-explicitly-denied}。
 * 这两个键语义不同：
 * <pre>
 *   x-client-forbidden: false        → 客户端小程序【是否生成调用面】（生成 = false）
 *   x-client-explicitly-denied: true → 【仅 staff 可调】（契约里只有 E4 / F2 / 域 G 标了它）
 * </pre>
 * 把前者读成后者会贴出一个"仅 staff"的注解，使客户拿到
 * 错误码语义（{@code @StaffOnly} 版）而非契约声明的 403 语义 ——
 * 而错误码是客户端分支的依据。
 *
 * <p>🛑 这也正是 B4/B5 对客户开放的实现前提：它们含 client，
 * 且它们的"客户能看什么"由 {@code CustomerFieldVisibility} 在<b>字段级</b>裁剪，
 * 而<b>不是</b>在端点级关门。两件事必须分清：
 * <pre>
 *   端点级关门（@StaffOnly）    = 客户完全不能调 —— B4/B5【不】这样
 *   字段级裁剪（x-visible-to）  = 客户能调，但只拿到他有档位的字段 —— B4 这样
 * </pre>
 *
 * <h2>🛑 权限码只贴给<b>不含 client</b> 的四行（B1/B2/B3/B6）</h2>
 * 这四行的 {@code x-callable-roles: [therapist, meridian, admin]} 不含 client，
 * 是标准的"该给 staff 发码、不给客户发码"形态。
 * <p>B4/B5 的行含 client，故<b>不贴</b>任何域码 —— 与 A2 {@code GET /auth/me}
 * 的处置同款（见 {@code PermissionRegistry} 的类注释）：
 * 注册表刻意不登记 client，故一旦贴码，客户会被权限层拒掉，
 * 而他本该能调这两个端点。B4/B5 的"可调用角色"由服务层
 * {@code requireCallable} 做 fail-closed 校验。
 *
 * <h2>🛑 本控制器不承载任何业务判定</h2>
 * 六行端点的全部顺序与错误码归属都在 {@link CustomerService}。
 * 控制器只做三件事：取参（含把 request body 映射成领域对象）、
 * 调服务、包 {@link Result} 信封。把判定写在控制器里会立刻制造第二份口径 ——
 * 而它与服务层的分叉不报错。
 */
@RestController
@RequestMapping("/api/v1")
public class CustomerController {

    /** 控制器调用计数 —— 供 E2E 断言"请求真的打到了本控制器"。 */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    // ==================================================================
    // B1 · POST /screening-records
    // ==================================================================

    /**
     * B1 禁忌筛查提交（硬门禁①）。
     *
     * <p>契约 200 → {@code ScreeningData{screening_id, result, submitted_at}}；
     * 400 → {@code ValidationFailed}。
     *
     * <h2>🛑 {@code operator_id} 从请求体里读出后<b>立刻丢弃</b></h2>
     * 契约 {@code ScreeningCreateRequest.operator_id} 的 description 逐字
     * 「<b>服务端从 token 覆写</b>」。故本方法<b>不</b>把请求体里的同名字段传给服务 ——
     * 不是"优先用 token"，而是"那一列在这个端点上根本不被采信"。
     * 这里<b>不</b>校验它是否缺失（它在上游 required 里，缺了会在别处暴露），
     * 也<b>不</b>报错 —— 因为它被丢弃，其值不影响任何结果。
     */
    @RequirePermission("customer:archive")
    @PostMapping("/screening-records")
    public Result<Map<String, Object>> createScreeningRecord(@RequestBody Map<String, Object> body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        Map<String, Object> items = asMap(body == null ? null : body.get("items_json"));
        Map<String, Object> data = service.submitScreening(
                asString(body == null ? null : body.get("customer_id")), items);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // B2 · POST /customers
    // ==================================================================

    /**
     * B2 建档。
     *
     * <p>契约 200 → {@code CustomerCreateData}；403 → {@code GateMissing}
     * （{@code missing_items} 逐字）。
     */
    @RequirePermission("customer:archive")
    @PostMapping("/customers")
    public Result<Map<String, Object>> createCustomer(@RequestBody Map<String, Object> body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();

        CustomerArchive archive = new CustomerArchive(
                asString(body == null ? null : body.get("name")),
                genderOf(body == null ? null : body.get("gender")),
                asInteger(body == null ? null : body.get("age")),
                asString(body == null ? null : body.get("phone")),
                asUuid(body == null ? null : body.get("screening_id")));

        UUID ownerStoreId = asUuid(body == null ? null : body.get("owner_store_id"));
        UUID servingStoreId = asUuid(body == null ? null : body.get("serving_store_id"));

        Map<String, Object> data = service.createCustomer(archive, ownerStoreId, servingStoreId);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // B3 · POST /customers/{id}/consents
    // ==================================================================

    /**
     * B3 签知情同意书。
     *
     * <p>契约为该端点<b>未声明 requestBody</b>，而 {@code consent} 表有五列 NOT NULL。
     * 服务层忠实承载它们并强校验非空、不发明来源 ——
     * 见 {@code CustomerService.describeSignConsentGap()}。
     */
    @RequirePermission("customer:archive")
    @PostMapping("/customers/{id}/consents")
    public Result<Map<String, Object>> signConsent(@PathVariable("id") String customerId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();

        Set<ConsentAuthScope> scopes = parseScopes(body == null ? null : body.get("auth_scope"));
        BandWillingness willingness = body != null && body.get("band_willingness") != null
                ? BandWillingness.of(String.valueOf(body.get("band_willingness")))
                : null;
        String evidenceHash = asString(body == null ? null : body.get("evidence_hash"));
        ConsentDataSource dataSource = body != null && body.get("data_source") != null
                ? ConsentDataSource.of(String.valueOf(body.get("data_source")))
                : null;

        Map<String, Object> data = service.signConsent(customerId, scopes, willingness,
                evidenceHash, dataSource);
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // B4 · GET /customers/{id}
    // ==================================================================

    /**
     * B4 客户详情（可见性裁剪重点接口）。
     *
     * <h2>🛑 不贴 {@code @RequirePermission} 的理由</h2>
     * B4 的 {@code x-callable-roles} <b>含 client</b>，而注册表刻意不登记 client ⇒
     * 贴了任何域码都会让客户查自己的详情时 403 —— 而他本该能查
     * （只是 {@code owner_store_id} / {@code serving_store_id} 不下发）。
     *
     * <h2>🛑 不贴 {@code @StaffOnly} 的理由</h2>
     * {@code x-client-forbidden: false} 且无 {@code x-client-explicitly-denied}
     * （见类注释）。客户的越权索取由 {@code CustomerFieldVisibility}
     * 在字段级以 403 VISIBILITY_DENIED 处置（含 {@code data.denied_fields} 回显）。
     *
     * @param include 契约 query 参数 {@code include}（如 {@code verdict}）；
     *                命中客户不可见字段组时 403
     */
    @GetMapping("/customers/{id}")
    public Result<Map<String, Object>> getCustomer(@PathVariable("id") String customerId,
                                                   @RequestParam(value = "include", required = false)
                                                   List<String> include) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        Map<String, Object> data = service.getCustomer(customerId,
                include == null ? List.of() : List.copyOf(include));
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // B5 · GET /customers/{id}/intake-profile
    // ==================================================================

    /**
     * B5 建档扩展档案。
     *
     * <p>契约 responses <b>仅 200</b> ⇒ "客户不存在"与"尚未建档"都是
     * {@code 200 + 空 data}，<b>不得</b>报 404。
     * 同样<b>不贴</b>域码 / {@code @StaffOnly}（含 client，理由同 B4）。
     */
    @GetMapping("/customers/{id}/intake-profile")
    public Result<Map<String, Object>> getIntakeProfile(@PathVariable("id") String customerId) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        return Result.ok(service.getIntakeProfile(customerId), MDC.get("traceId"));
    }

    // ==================================================================
    // B6 · PATCH /customers/{id}/intake-profile
    // ==================================================================

    /**
     * B6 补充 + 修订（append-only 留痕，不可覆盖）。
     *
     * <p>契约 responses <b>仅 200</b> ⇒ 首次写入即建档（{@code revision_no = 1}），
     * <b>不得</b>对"未建档"报 403。
     * <p>该行不含 client，故贴 {@code customer:archive}（与 B1/B2/B3 同款）。
     * 🛑 该码与 {@code customer:write} 是<b>两个不同的语义</b>：
     * 前者承载"档案写入推进"（契约点名 therapist/meridian/admin 可调），
     * 后者承载题库导入这类内容资产写动作（仅管理层级）。
     * 合并两者会把题库写权发给一线角色 —— 详见 {@code PermissionRegistry} 的域 B 一节。
     */
    @RequirePermission("customer:archive")
    @PatchMapping("/customers/{id}/intake-profile")
    public Result<Map<String, Object>> patchIntakeProfile(@PathVariable("id") String customerId,
                                                          @RequestBody(required = false)
                                                          Map<String, Object> body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        return Result.ok(service.patchIntakeProfile(customerId, body), MDC.get("traceId"));
    }

    // ==================================================================
    // 契约自描述（只读、免权限、不含任何数据）
    // ==================================================================

    /**
     * 域 B 的契约自描述。
     *
     * <p>把三件事变成可被回归用例集读取的运行时事实：
     * <ol>
     *   <li>六行端点的 {@code x-callable-roles} 与"哪几行贴码"；</li>
     *   <li>B1 的禁忌推导口径与<b>当前清单为空</b>这条安全级配置缺口；</li>
     *   <li>B3 的"契约无 requestBody 而库层四列 NOT NULL"缺口。</li>
     * </ol>
     * <p>🛑 它<b>不</b>经服务层的角色校验 —— 安全性来自"响应体与请求者无关"
     * （纯粹的契约元信息）。若将来有人往这个 Map 里塞任何按角色变化的值，
     * 就应该把它移出免权限区。
     */
    @GetMapping("/customers/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("domain", "B-客户与档案");
        m.put("operations", List.of(
                "B1 POST /screening-records",
                "B2 POST /customers",
                "B3 POST /customers/{id}/consents",
                "B4 GET /customers/{id}",
                "B5 GET /customers/{id}/intake-profile",
                "B6 PATCH /customers/{id}/intake-profile"));
        m.put("callable_roles", List.of(
                "B1 [therapist, meridian, admin]",
                "B2 [therapist, meridian, admin]",
                "B3 [therapist, meridian, admin]",
                "B4 [client, therapist, meridian, admin]",
                "B5 [client, therapist, meridian, admin]",
                "B6 [therapist, meridian, admin]"));
        m.put("response_sets", List.of(
                "B1 = 200 + 400（无 403 / 无 500）",
                "B2 = 200 + 403(GateMissing)（无 404）",
                "B3 = 200 + 403(GateMissing)",
                "B4 = 200 + 403(VisibilityDenied) + 404(NotFound)",
                "B5 = 仅 200",
                "B6 = 仅 200"));
        m.put("annotations", List.of(
                "B1/B2/B3/B6 贴 @RequirePermission(\"customer:archive\")（x-callable-roles 不含 client）",
                "B4/B5 不贴任何权限码（含 client，而注册表刻意不登记 client）",
                "六行【均不】贴 @StaffOnly（x-client-forbidden 全为 false，且无 x-client-explicitly-denied）",
                "customer:archive 与 customer:write 是两码：前者=档案写入（含一线角色），"
                        + "后者=内容资产写入（仅管理层级）—— 见 PermissionRegistry 域 B 一节"));
        m.put("contraindication", service.describeContraindicationPolicy());
        m.put("b3_request_body_gap", service.describeSignConsentGap());
        // ⚠️ 下面两条直连 domain 层：R2 只禁"控制器 → 数据访问层"，
        //    domain 层的纯口径/枚举是控制器可以引用的（与既有域同口径）。
        m.put("storage_alignment",
                com.diaoyuanyun.dy.app.customer.domain.CustomerArchive.describeStorageGap());
        m.put("provisioning_scan",
                com.diaoyuanyun.dy.app.customer.domain.CustomerFieldVisibility.describeIncludePolicy());
        // 🛑 下面两条经【服务层】转发：直连 CustomerLedger / IntakeProfileLedger 的静态方法
        //    会让 R2（控制器不得依赖数据访问层）报红 —— 而那次报红是对的，
        //    它拦下的是"控制器可以绕过服务层"这条先例，不是"读一个字符串"。
        m.put("key_creation_gap", service.describeKeyCreationGap());
        m.put("projection_vs_history", service.describeProjectionVsHistory());
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 自证计数（供 E2E 断言；不参与任何授权判定）。 */
    public static long invocationCount() {
        return CONTROLLER_INVOCATIONS.get();
    }

    /** 重置计数器（供测试隔离）。 */
    public static void resetInvocationCount() {
        CONTROLLER_INVOCATIONS.set(0);
    }

    // ==================================================================
    // 请求体解析辅助（全部 fail-closed：缺件由领域构造器给出精确消息）
    // ==================================================================

    static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    static Integer asInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            // 交给 CustomerArchive 的构造期给出统一消息（null 亦会被它拒）
            return null;
        }
    }

    static UUID asUuid(Object v) {
        String s = asString(v);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    /**
     * 解析 {@code auth_scope}（契约 {@code ConsentAuthScope} 的四个字面）。
     *
     * <p>🛑 空数组与缺失是<b>两件不同的事</b>：
     * <pre>
     *   "auth_scope": []      → 空集（客户拒绝了全部四项）—— 合法且有意义
     *   （无该字段）           → null（未提供）—— 由服务层拒
     * </pre>
     * 故本方法只在字段<b>存在</b>时返回（可能为空的）集合，缺失时返回 {@code null}。
     * 把两者合并会让"拒绝了全部"被读成"没填"，而那是 consent 表要区分的第一件事。
     */
    static Set<ConsentAuthScope> parseScopes(Object v) {
        if (v == null) {
            return null;
        }
        List<String> raw = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    raw.add(String.valueOf(o));
                }
            }
        } else if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,\\s，、|+]+")) {
                if (!part.isBlank()) {
                    raw.add(part.trim());
                }
            }
        } else {
            return null;
        }
        // parseAll 对未知键整批拒（不静默丢弃）—— 见 ConsentAuthScope 的构造纪律
        return new LinkedHashSet<>(ConsentAuthScope.parseAll(raw));
    }

    static Gender genderOf(Object v) {
        String s = asString(v);
        return s == null ? null : Gender.of(s);
    }
}