package com.diaoyuanyun.dy.web.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级幂等要求 (ADR-10)。标记后由 {@link IdempotencyInterceptor} 处理 {@code Idempotency-Key} 头。
 *
 * <p>同键重放返回原响应 + {@code X-Idempotent-Replayed: true}; 同键不同体 -> 409; 键非法 -> 400。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
