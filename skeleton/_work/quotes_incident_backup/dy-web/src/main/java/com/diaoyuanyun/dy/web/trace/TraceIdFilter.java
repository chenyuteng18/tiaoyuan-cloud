package com.diaoyuanyun.dy.web.trace;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * trace_id 过滤器 (ADR-09)。每个请求生成 trace_id, 写入 MDC (日志/响应头), 出口清理。
 *
 * <p><b>为何显式声明顺序</b>：{@code TenantContextFilter} 在鉴权失败时会<b>直接写响应信封</b>
 * （过滤器异常不进 {@code @RestControllerAdvice}），它需要 MDC 中已有 trace_id。
 * 若本过滤器晚于它执行，401/403 响应就会带 {@code trace_id:null} 甚至缺字段，
 * 违反契约 §2.0"信封四字段不得增删"。故本类固定为<b>最高优先级</b>（最先执行）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter implements Filter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null) {
            traceId = TraceUtils.newTraceId();
        }
        MDC.put(MDC_KEY, traceId);
        try {
            if (response instanceof HttpServletResponse http) {
                http.setHeader(HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
