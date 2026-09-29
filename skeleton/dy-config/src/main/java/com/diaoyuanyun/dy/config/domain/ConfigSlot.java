package com.diaoyuanyun.dy.config.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 配置项声明（总部层）—— 编号 / 键 / 值类型 / 允许值 / 禁止值。
 *
 * <p>对应 DB 表 {@code config_slot}（ADR-08）。它<b>不承载配置值</b>：
 * 值只有一处来源 {@code app_config}。故本类没有 {@code value} 字段 ——
 * 这是刻意的，让"从声明里读值"这条路在编译期就不通。
 *
 * <p>一条 {@code config_no} 只有一条声明；PRD「附：可配置项清单」的 46 条非空配置对应 46 行，
 * <b>#42 无对应行</b>（空号，见 {@code config_slot_no_42_stays_vacant}），
 * <b>#47 亦无对应行</b>（已由 optin-arch 线预定、尚未落 PRD —— 暂缺，非空号）。
 */
public final class ConfigSlot {

    private final long configNo;
    private final String configKey;
    private final ValueType valueType;
    private final String[] allowedValues;   // null = 不限
    private final String[] forbiddenValues; // null = 无
    private final String prdItemName;
    private final String description;

    public ConfigSlot(long configNo, String configKey, ValueType valueType,
                      String[] allowedValues, String[] forbiddenValues,
                      String prdItemName, String description) {
        this.configNo = configNo;
        this.configKey = configKey;
        this.valueType = valueType;
        this.allowedValues = allowedValues == null ? null : allowedValues.clone();
        this.forbiddenValues = forbiddenValues == null ? null : forbiddenValues.clone();
        this.prdItemName = prdItemName;
        this.description = description;
    }

    public long getConfigNo() {
        return configNo;
    }

    public String getConfigKey() {
        return configKey;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public List<String> getAllowedValues() {
        return allowedValues == null ? null : Arrays.asList(allowedValues);
    }

    public List<String> getForbiddenValues() {
        return forbiddenValues == null ? null : Arrays.asList(forbiddenValues);
    }

    public String getPrdItemName() {
        return prdItemName;
    }

    public String getDescription() {
        return description;
    }
}