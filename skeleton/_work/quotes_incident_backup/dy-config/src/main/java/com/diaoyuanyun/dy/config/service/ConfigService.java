package com.diaoyuanyun.dy.config.service;

import java.util.Optional;

/**
 * 配置服务接口 (ADR-08, 骨架期形态)。
 *
 * <p>接口刻意<b>不带 tenantId</b>：这一组方法服务于 {@code ConfigServiceImpl}
 * （内存语义骨架）与 {@code ConfigServiceFailClosedTest}，
 * 其目的是把"fail-closed 未命中即抛"与"保存时校验"两条语义立成事实，
 * 与租户维度无关。
 *
 * <p>运行时（真实 DB 真相源）的实现见
 * {@link com.diaoyuanyun.dy.config.repository.JdbcConfigService} ——
 * 它的每个方法都显式要求 tenantId，因为 DB 侧的 RLS 上下文与缓存键
 * 必须来自同一个租户标识。
 */
public interface ConfigService {

    /**
     * 读取必填配置; 未配置 -> 抛 {@link com.diaoyuanyun.dy.common.exception.BizException} (fail-closed)。
     */
    String getRequired(String key);

    /**
     * 可选读取; 未配置返回 empty (调用方自行决定)。
     */
    Optional<String> getOptional(String key);

    /**
     * 保存配置; 保存前经 {@link com.diaoyuanyun.dy.config.validation.ConfigValidator} 校验, 非法组合被拒绝。
     */
    void set(String key, String value);
}