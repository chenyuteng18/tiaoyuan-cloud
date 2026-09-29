package com.diaoyuanyun.dy.config.validation;

import com.diaoyuanyun.dy.config.domain.ConfigSlot;
import com.diaoyuanyun.dy.config.domain.SysConfig;

/**
 * 配置保存时校验器 (ADR-08, H-13)。非法组合必须拒绝, 不得静默存下。
 */
public interface ConfigValidator {

    /**
     * 保存前校验候选配置（骨架期：按键名约定校验）。非法组合抛出
     * {@link com.diaoyuanyun.dy.common.exception.BizException}。
     */
    void validate(SysConfig candidate);

    /**
     * 按【声明】校验取值 —— 这是运行时真正使用的那条规则（ADR-08）。
     *
     * <p>规则完全由 {@code config_slot} 驱动（值类型 / 允许集合 / 禁止组合），
     * 方法体内不出现任何具体编号或具体取值：新增配置项只需插一行声明。
     *
     * <p>它与数据库触发器 {@code app_config_enforce_slot()} 是同一套规则的
     * 两个落点（服务层给调用方清晰的 BizException；DB 层堵住绕过服务的写入路径）。
     */
    void validateValue(ConfigSlot slot, String value);
}