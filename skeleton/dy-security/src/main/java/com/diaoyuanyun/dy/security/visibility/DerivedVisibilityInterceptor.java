package com.diaoyuanyun.dy.security.visibility;

import com.diaoyuanyun.dy.common.exception.VisibilityDeniedException;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派生字段入站拦截器（S1-5 验收②）——<b>客户请求派生字段一律 403</b>。
 *
 * <h2>为什么是"拦截器"而不是"在控制器里判断"</h2>
 * 契约 §3.2 的要求是「服务端 403 硬阻断」，并逐字说明这是<b>契约级、非 UI 级</b>：
 * <pre>
 *   客户 token 调 GET /customers/{id}/band/derived、/verdicts、include=derived 等
 *   → 403 VISIBILITY_DENIED；data.denied_fields[] 回显被拒字段名
 * </pre>
 * 若把判定写进每个控制器，那么<b>下一个新端点</b>就会漏掉它 ——
 * 而漏掉的表现是"这个接口刚好能用"，不报错、不告警。
 * 挂在全局拦截器上则相反：新端点<b>默认</b>受保护，要绕过必须显式做点什么。
 *
 * <h2>为什么它排在权限拦截器<b>之前</b></h2>
 * 两者都归 2001，但信息量不同：权限拦截器只能说"你没有 {@code customer:read}"，
 * 而本拦截器能说"你索取了 {@code effect_verdict}、{@code as_value}，这两个你没有档位"。
 * 让<b>更具体的判定</b>先发生，调用方拿到的错误更贴近他真正要改的东西。
 * （同一条理由见 {@code WebConfig} 对"层级拦截器排在最后"的说明，方向一致：
 * 具体判定优先于粗粒度判定。）
 *
 * <h2>它拦什么：三种"点名"写法</h2>
 * 查询参数名、查询参数值里的词、请求体里的键名 —— 解算规则全在
 * {@link DerivedRequestScanner}（纯逻辑，可不起容器单测）。本类<b>只</b>做
 * "取参数 → 调解算 → 抛异常"，不自己判断哪个字段算派生。
 *
 * <h2>为什么它不依赖任何注解</h2>
 * 有效载荷是全局的：<b>任何</b>端点上，客户都不该能通过参数点名派生字段。
 * 若做成 {@code @RequireXxx} 式注解，就会出现"忘了贴注解的端点不受保护"——
 * 而"忘贴"是沉默的。故本类对<b>所有</b>落入 Spring MVC 的请求生效。
 *
 * <h2>两种拒绝：按字段 vs 按端点</h2>
 * <ol>
 *   <li><b>按字段</b>（本类的主体）：请求里点名了派生字段 → 403 + 回显<b>被点名的</b>字段。</li>
 *   <li><b>按端点</b>（{@link StaffOnly}）：整个端点对客户不开放（契约
 *       {@code x-client-explicitly-denied: true}，如 E4）→ 403 + 回显<b>该端点的</b>派生字段。
 *       🛑 这一种<b>不能</b>靠按字段的扫描覆盖：客户可以一个参数都不带地调 E4，
 *       此时"未点名任何字段"为真，请求会走到控制器；而出口兜底只会把字段摘空并返回
 *       200 + 空 data —— 调用方会把一次可见性拒绝误读成一次空结果。契约要求的是 403。</li>
 * </ol>
 *
 * <h2>🛑 两种拒绝的<b>次序</b>：按字段先，按端点后</h2>
 * 两者都归 2001，但信息量不同，且契约 §2.1 B4 对"带参数"的情形给出了<b>逐字</b>的回显要求
 * （{@code ?include=verdict → ["effect_verdict","as_value"]}）。
 * 若先按端点拒，B4 的这条回显在真实链路上<b>永远不会发生</b>——
 * 请求会先撞上端点的 {@link StaffOnly} 而拿到整张端点级字段表。
 * 故次序与 {@code WebConfig} 对拦截器整体的排序同源：<b>更具体的判定优先</b>。
 *
 * <h2>🛑 它不替代出口兜底（验收③）</h2>
 * 入站拒的是"<b>点名索取</b>"，出口挡的是"<b>顺手带出</b>"。
 * 一个控制器完全可以把派生字段塞进响应而不出现在任何请求参数里 ——
 * 那种泄漏只有出口兜底能看见。两者不可互相替代，这是 S1-5 两条独立验收的原因。
 */
@Component
public class DerivedVisibilityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DerivedVisibilityInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = TenantContext.role();
        // staff 与"未登记但非客户"的角色直接放行：本拦截器只解决客户侧硬约束。
        // 调理师/经络师的 ④ 可见性属契约矩阵的 ✓ 档（"可见但不得作对客户不利依据"），
        // 那是业务使用纪律，不是接口可见性 —— 在这里拦它们会拦错东西。
        if (!DerivedFields.isClientLike(role)) {
            return true;
        }

        // ② 按字段：请求里【点名】了派生字段 → 回显被点名的那些字段。
        //
        // 🛑 它必须先于 ① 按端点判定。初版把 ① 放在前面，于是契约 §2.1 B4 逐字要求的
        //    "?include=verdict → data.denied_fields=["effect_verdict","as_value"]"
        //    在真实链路上【永远不会发生】：请求先撞上端点的 @StaffOnly，
        //    回显的是整张端点级字段表。契约 B4 因此形同虚设 ——
        //    而它恰恰是"不得模糊报错"（P0-08）在可见性这一层最具体的一条。
        //    这正是「更具体的判定优先于粗粒度判定」（WebConfig 注释里对本拦截器顺序的说明）
        //    在【本类内部】的同一原则：既然拦截器之间按这个次序排，类内的两种拒绝也应如此。
        List<String> denied = DerivedRequestScanner.scan(role, queryParams(request), readBody(request));
        if (!denied.isEmpty()) {
            // 日志留痕：这条 403 是"有人试图索取派生字段"的信号，值得可检索。
            // 只记字段名与路径，不记查询值（值可能含客户标识）。
            log.warn("客户请求索取派生字段被拒: path={} fields={}", request.getRequestURI(), denied);
            throw new VisibilityDeniedException(denied,
                    "客户对派生结果 / 缺口原因字段组无可见性档位（契约 §3.1 矩阵解算为 ✗）；被拒字段: " + denied);
        }

        // ① 按端点：契约标了 x-client-explicitly-denied 的端点，客户一律拒（与参数无关）
        StaffOnly staffOnly = resolveStaffOnly(handler);
        if (staffOnly != null) {
            List<String> fields = List.of(staffOnly.clientDeniedFields());
            log.warn("客户访问仅 staff 端点被拒: path={} fields={}", request.getRequestURI(), fields);
            throw new VisibilityDeniedException(fields,
                    "本端点在契约上标为仅 staff 可调（x-client-explicitly-denied: true）；"
                            + "客户无可见性档位。被拒字段: " + fields);
        }

        return true;
    }

    /** 取处理器上的 {@link StaffOnly}（方法优先，类级兜底）——与权限拦截器的注解解析口径一致。 */
    private static StaffOnly resolveStaffOnly(Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return null;
        }
        StaffOnly ann = hm.getMethodAnnotation(StaffOnly.class);
        return ann != null ? ann : hm.getBeanType().getAnnotation(StaffOnly.class);
    }

    /** 收集查询参数（名 → 值列表）。 */
    private static Map<String, List<String>> queryParams(HttpServletRequest request) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        var names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String[] values = request.getParameterValues(name);
            out.put(name, values == null ? List.of() : List.of(values));
        }
        return out;
    }

    /**
     * 读请求体（原始 JSON 文本）。
     *
     * <h2>为什么直接 {@code getInputStream()} 是安全的</h2>
     * dy-app 装配了 {@code IdempotencyBodyCacheFilter}（dy-web 的 {@code @Component}），
     * 它把所有请求包成"每次 {@code getInputStream()} 都返回一份位置归零的副本"的包装器。
     * 故本拦截器读一次体，<b>不影响</b>控制器随后的 {@code @RequestBody} 解析。
     * 这一点在 {@code IdempotencyBodyCacheFilter} 的类注释里有实证记录：
     * 初版用 Spring 自带的 {@code ContentCachingRequestWrapper} 会导致
     * 先读体的地方把流读尽、控制器拿到空体。
     *
     * <h2>🛑 读不到体时不抛错，只跳过体扫描（有意为之）</h2>
     * 两种情形会读不到：① 无体（GET）；② 该过滤器未装配（例如某个裁剪过的
     * 测试上下文）。第二种情形下若抛错，会把"体扫描不可用"变成"所有 POST 都 500"——
     * 一个保护性设施不该有这种失败模式。跳过体扫描后，<b>出口兜底仍然成立</b>：
     * 派生字段依然不会出现在响应体里（验收③由另一条防线独立保证）。
     * 这是"两层防线各自独立"在这里的兑现，不是妥协。
     */
    private static String readBody(HttpServletRequest request) {
        try (InputStream is = request.getInputStream()) {
            if (is == null) {
                return null;
            }
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("入站体扫描跳过（无法读取请求体）: {}", e.getMessage());
            return null;
        }
    }

    /** 供测试断言"本拦截器作用于全部路径"（返回受保护路径模式；空 = 全局）。 */
    public static List<String> protectedPathPatterns() {
        return List.of();
    }
}