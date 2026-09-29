package com.diaoyuanyun.dy.security.permission;

import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 层级越权拦截器（S1-3 验收②）。读 {@link RequireOrgLevel}，交由 {@link OrgScopeGuard} 判定。
 *
 * <p>本类<b>只做读取与委派</b>，判定逻辑全在 {@link OrgScopeGuard}（纯逻辑、可直接单测）。
 * 这样"规则对不对"与"接线对不对"是两件可分开验证的事 —— 前者用单元测试钉住，
 * 后者用端到端真请求钉住。把两者揉在一个类里，会变成"端到端一红就不知道是规则错还是接线错"。
 *
 * <p>角色取自 {@link TenantContext#role()}（上下文<b>只来自已验签的 token</b>，见 TenantContextFilter）。
 */
@Component
public class OrgScopeInterceptor implements HandlerInterceptor {

    private final OrgScopeGuard guard;

    public OrgScopeInterceptor(OrgScopeGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequireOrgLevel ann = hm.getMethodAnnotation(RequireOrgLevel.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequireOrgLevel.class);
        }
        if (ann == null) {
            return true;
        }
        // 角色不可识别 -> 由 guard 抛 2003；层级不足 -> 抛 2001。两者均 403。
        guard.assertCovers(TenantContext.role(), ann.min());
        return true;
    }
}