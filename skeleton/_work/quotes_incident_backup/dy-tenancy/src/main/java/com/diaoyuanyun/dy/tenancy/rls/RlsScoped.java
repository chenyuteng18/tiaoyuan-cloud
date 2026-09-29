package com.diaoyuanyun.dy.tenancy.rls;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记方法需要在执行前注入 RLS 会话变量 {@code app.tenant_id} (ADR-02 第 2 层)。
 *
 * <p>由 {@link RlsSessionAspect} 拦截, 在事务内执行 {@code SET LOCAL app.tenant_id = ?}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RlsScoped {
}
