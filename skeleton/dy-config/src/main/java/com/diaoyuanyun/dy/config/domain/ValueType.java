package com.diaoyuanyun.dy.config.domain;

/**
 * 配置值类型 —— 与 DB 约束 {@code config_slot_value_type_known} 的枚举取值逐一对应。
 *
 * <p>它是<b>声明侧</b>的类型（"这个配置项的值应当长什么样"），
 * 不是 Java 侧业务类型（"读出来之后怎么用"）。二者的分离是刻意的：
 * 类型校验规则只有一份（见 {@code DefaultConfigValidator#validateValue}),
 * 无论是"总部灌初始值"还是"租户改值"，走的都是同一份规则。
 */
public enum ValueType {

    /** 非负整数，如 7 / 48 / 20。 */
    INT,
    /** 非负小数，如 0.80 / 0.30。 */
    DECIMAL,
    /** true / false。 */
    BOOL,
    /** 枚举取值，必须落在 config_slot.allowed_values 内。 */
    ENUM,
    /** 合法 JSON（结构由 PRD §10 语义决定，类型层只管"是不是合法 JSON"）。 */
    JSON,
    /** 自由文本。 */
    TEXT
}