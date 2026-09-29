package com.diaoyuanyun.dy.security.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 门禁守卫 (ADR-05 配套)。声明调用方需通过的"能力门禁"项。
 *
 * <p>未通过时返回 403 GATE_MISSING, 且响应体必须回显 {@code missing_items[]} 缺失项名称 (严禁模糊报错, H-8)。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireGate {
    String[] items();
}
