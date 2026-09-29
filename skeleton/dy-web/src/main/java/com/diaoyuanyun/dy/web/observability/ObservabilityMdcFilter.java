package com.diaoyuanyun.dy.web.observability;

import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * A7 第一支柱（logs）：把 trace_id 之外的上下文注入日志 MDC，使「日志 ↔ trace ↔ 请求 ↔ 租户」可关联。
 *
 * <h2>与 {@code TraceIdFilter} 的分工（故意的，不合并）</h2>
 * <ul>
 *   <li>{@code TraceIdFilter}（{@code dy-web/trace}）只负责 <b>trace_id 本身</b>：生成、写 MDC、
 *       写响应头 {@code X-Trace-Id}、出口清理。它被 <b>契约</b>绑定 —— 响应信封的
 *       {@code trace_id} 必须恒在（§2.0），且它必须最先执行（见该类注释）。</li>
 *   <li>本过滤器只负责<b>可观测性附加字段</b>：HTTP 方法 / 路径 / 入口 / 租户。这些字段只进日志，
 *       <b>不进响应</b>，故可以随时增删而不动契约。</li>
 * </ul>
 * 合并的代价是：以后要给日志加一个字段，就得动那个"必须最先执行、且被契约绑定"的过滤器。
 * 拆开的代价只是一个 {@code @Order} 序号。
 *
 * <h2>租户字段：从已校验的 {@link TenantContext} 复制，不自己解析请求头</h2>
 * 本过滤器<b>不</b>读 {@code X-Tenant-Id} 头、也不 import {@code TenantContextFilter}。理由：
 * <ol>
 *   <li>X-Tenant-Id 的<b>合法性判定</b>（与 JWT 声明是否一致）只有一个权威实现，就是
 *       {@code dy-tenancy} 的 {@code TenantContextFilter}。在日志侧再解析一遍，就等于承认
 *       两个实现可以不一致 —— 日志里的租户与一次请求实际写库所属的租户一旦不同，
 *       排障时会把排障人带向完全错误的方向。</li>
 *   <li>更重要的：若本类自己去读那个头并写进 MDC，就等于把<b>客户端可控的字符串</b>直接
 *       放进日志字段。攻击者发一个带换行的 {@code X-Tenant-Id}，就能在日志里伪造出完整的
 *       假日志行（log injection）。只从已由权威过滤器校验过的 {@link TenantContext} 复制，
 *       这个攻击面就不存在。</li>
 * </ol>
 * 依赖方向也合法：{@code dy-web -> dy-tenancy}（ADR-03 单向依赖），不产生反向依赖。
 *
 * <h2>顺序（关键，算错会静默丢字段）</h2>
 * {@code TraceIdFilter}(+10) &rarr; {@code TenantContextFilter}(+100) &rarr; 本过滤器(+110)。
 * 必须晚于 {@code TenantContextFilter}，否则进入本过滤器时租户上下文尚未建立，
 * 日志里会永远缺 {@code tenantId}（而不报任何错 —— 静默丢字段）。
 * 也必须晚于 {@code TraceIdFilter}，否则 MDC 里还没有 {@code traceId}，日志无法与 trace 关联。
 *
 * <h2>不引入跨租户信息泄漏</h2>
 * 日志里的 {@code tenantId} 是<b>文本字段</b>，用于定位"哪个租户的哪次请求"，
 * 它<b>绝不</b>作为指标标签 —— 后者会基数爆炸并构成跨租户信息泄漏面。
 * 该禁令由 {@code TenantTagGuardTest}（枚举全部 Meter 断言无 tenant 标签）机械守住，不靠口头约定。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 110)
public class ObservabilityMdcFilter implements Filter {

    /** MDC 键名。加了 {@code dy.} 前缀，避免与框架/第三方的同名键（如 Logback 内置的 {@code method}）冲突。 */
    public static final String MDC_METHOD = "dy.method";
    public static final String MDC_PATH = "dy.path";
    public static final String MDC_ENTRY = "dy.entry";
    /**
     * 租户字段的 MDC 键名。
     *
     * <p>沿用无前缀的 {@code tenantId} 是<b>刻意</b>的：日志采集侧（Loki/ELK 的字段提取）通常按
     * 通用字段名建索引，改成 {@code dy.tenantId} 会让既有采集规则失效。这不是笔误。
     */
    public static final String MDC_TENANT = "tenantId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = request instanceof HttpServletRequest h ? h : null;

        put(MDC_METHOD, http == null ? null : http.getMethod());
        put(MDC_PATH, http == null ? null : http.getRequestURI());
        // 入口标识：多入口（H5 / 小程序 / 管理端）共用同一后端时区分流量来源
        put(MDC_ENTRY, http == null ? null : http.getHeader("X-Entry-Channel"));
        // 只复制已校验的租户上下文（见类注释：不解析请求头）
        put(MDC_TENANT, TenantContext.tenantId());

        try {
            chain.doFilter(request, response);
        } finally {
            // 出口清理【本类自己写入】的键。租户键也由本类清理 ——
            // 因为本类只从 TenantContext 复制值、不转移其所有权；TenantContext 的生命周期
            // 由 TenantContextFilter 自己管理，本类不越界去 clear 它。
            MDC.remove(MDC_METHOD);
            MDC.remove(MDC_PATH);
            MDC.remove(MDC_ENTRY);
            MDC.remove(MDC_TENANT);
        }
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, sanitize(value));
        }
    }

    /**
     * 去掉换行/回车/制表符等可用来"伪造日志行"的控制字符（log injection 防护）。
     *
     * <p>即使值来自受信来源（如 request URI）也必须做：URI 在到达这里之前可能经容器解码，
     * 而 {@code %0A} / {@code %0D} 会被解码回换行，使一条日志被拆成两行 ——
     * 于是攻击者可以用一个 URI 在日志里伪造出"另一个租户发生了某事"的记录。
     */
    private static String sanitize(String value) {
        return value.replace('\n', '_').replace('\r', '_').replace('\t', '_');
    }
}