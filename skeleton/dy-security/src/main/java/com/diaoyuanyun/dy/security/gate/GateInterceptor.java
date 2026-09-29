package com.diaoyuanyun.dy.security.gate;

import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 门禁守卫拦截器 (ADR-05)。缺失任一项门禁 -> 403 GATE_MISSING, 缺失项写入异常由全局处理器回显。
 */
@Component
public class GateInterceptor implements HandlerInterceptor {

    private final GateRegistry registry;

    public GateInterceptor(GateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequireGate ann = hm.getMethodAnnotation(RequireGate.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequireGate.class);
        }
        if (ann == null) {
            return true;
        }
        String role = TenantContext.role();
        List<String> missing = new ArrayList<>();
        for (String item : ann.items()) {
            if (!registry.hasGate(role, item)) {
                missing.add(item);
            }
        }
        if (!missing.isEmpty()) {
            throw new GateMissingException(missing);
        }
        return true;
    }
}
