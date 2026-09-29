package com.diaoyuanyun.dy.audit.aspect;

import com.diaoyuanyun.dy.common.audit.AuditableEntity;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 审计字段自动填充切面 (ADR-A5/A-6)。在持久化入口前填充 {@code created_at}/{@code created_by}/
 * {@code updated_at}/{@code updated_at_by}。{@code created_by} 取自 {@link TenantContext} 当前操作者。
 *
 * <p>点切: 各仓储的 save/persist 方法; 骨架阶段匹配 {@code ..save(..)} / {@code ..persist(..)}。
 */
@Aspect
@Component
public class AuditFillAspect {

    @Before("execution(* com.diaoyuanyun..*.save(..)) || execution(* com.diaoyuanyun..*.persist(..))")
    public void fill(JoinPoint jp) {
        Instant now = Instant.now();
        String actor = currentUser();
        for (Object arg : jp.getArgs()) {
            if (arg instanceof AuditableEntity e) {
                if (e.getCreatedAt() == null) {
                    e.setCreatedAt(now);
                    e.setCreatedBy(actor);
                }
                e.setUpdatedAt(now);
                e.setUpdatedAtBy(actor);
            }
        }
    }

    private String currentUser() {
        if (!TenantContext.isSet()) {
            return "system";
        }
        String staff = TenantContext.staffId();
        return staff != null ? staff : TenantContext.tenantId();
    }
}
