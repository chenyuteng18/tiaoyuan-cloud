package com.diaoyuanyun.dy.security.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法/类级权限要求 (ADR-07 按角色×字段组四档, T-5/T-6)。
 *
 * <p>声明调用方需具备的权限码; 缺任意一项由 {@link PermissionInterceptor} 拦截为
 * 403 VISIBILITY_DENIED。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String[] value();
}
