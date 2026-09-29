package com.diaoyuanyun.dy.security.permission;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限拦截器 (ADR-05)。无权限 -> 403 VISIBILITY_DENIED。
 *
 * <p>从 {@link TenantContext} 读取当前角色, 对照 {@link RequirePermission} 声明与 {@link PermissionRegistry}。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionRegistry registry;

    public PermissionInterceptor(PermissionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequirePermission ann = hm.getMethodAnnotation(RequirePermission.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (ann == null) {
            return true;
        }
        String role = TenantContext.role();
        for (String perm : ann.value()) {
            if (!registry.hasPermission(role, perm)) {
                throw new BizException(ErrorCode.VISIBILITY_DENIED, "权限不足: " + perm);
            }
        }
        return true;
    }
}
