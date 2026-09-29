package com.diaoyuanyun.dy.config.validation;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.config.domain.ConfigSlot;
import com.diaoyuanyun.dy.config.domain.SysConfig;
import com.diaoyuanyun.dy.config.domain.ValueType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认配置校验 (ADR-08/H-13)。
 *
 * <h2>两组规则，各自服务于不同阶段</h2>
 * <ul>
 *   <li>{@link #validate(SysConfig)} —— <b>骨架期按键名约定</b>：key 命名格式、
 *       以 {@code threshold.} 开头的键取值必须为非负整数。它不查数据库，
 *       故可在无库环境独立验证（{@code ConfigServiceFailClosedTest}）。</li>
 *   <li>{@link #validateValue(ConfigSlot, String)} —— <b>运行时的真规则</b>：
 *       完全由 {@code config_slot} 声明驱动（值类型 / 允许集合 / 禁止组合）。</li>
 * </ul>
 *
 * <p>两组规则都要保留：前者让"命名与粗粒度取值"在无库时可测，
 * 后者让"逐配置项的精确口径"由数据库的声明表统一裁决 —— 代码里不会出现
 * 任何具体业务数字（也就不会被硬编码，X-18/PRD §10 硬性要求）。
 */
@Component
public class DefaultConfigValidator implements ConfigValidator {

    private static final java.util.regex.Pattern NAMING =
            java.util.regex.Pattern.compile("cfg:[a-z0-9_.]+");

    @Override
    public void validate(SysConfig candidate) {
        if (candidate == null || candidate.getKey() == null || candidate.getKey().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "配置 key 不能为空");
        }
        if (!NAMING.matcher(candidate.getKey()).matches()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "配置 key 命名非法: " + candidate.getKey());
        }
        if (candidate.getKey().startsWith("cfg:threshold.")) {
            if (candidate.getValue() == null || !candidate.getValue().matches("\\d+")) {
                // 阈值类配置取值非法属于"业务规则不满足", 对应 5001 (HTTP 422), 而非参数格式错
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "阈值类配置必须为非负整数: " + candidate.getKey());
            }
        }
    }

    @Override
    public void validateValue(ConfigSlot slot, String value) {
        if (slot == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "配置项未在声明表中登记，拒绝写入");
        }
        if (value == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "配置 #" + slot.getConfigNo() + " 取值不得为 null");
        }

        ValueType type = slot.getValueType();
        boolean typeOk;
        String expectation;
        switch (type) {
            case INT -> {
                typeOk = value.matches("^[0-9]+$");
                expectation = "非负整数";
            }
            case DECIMAL -> {
                typeOk = value.matches("^[0-9]+(\\.[0-9]+)?$");
                expectation = "非负小数";
            }
            case BOOL -> {
                typeOk = value.equals("true") || value.equals("false");
                expectation = "true / false";
            }
            case JSON -> {
                typeOk = isJson(value);
                expectation = "合法 JSON";
            }
            case ENUM, TEXT -> {
                typeOk = true;
                expectation = null;
            }
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未支持的值类型: " + type);
        }
        if (!typeOk) {
            // 取值形态非法 = 业务规则不满足 -> 5001 (HTTP 422)，不是参数格式错 1001。
            // 这个归属与"阈值类配置取值非法"一致，避免同类问题两个码。
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "配置 #" + slot.getConfigNo() + " 取值必须为" + expectation + "，实际: " + value);
        }

        // 禁止组合先判：它的拒绝理由比"不在允许集合内"更具体。
        // 例 #44：禁止的是"小程序后台自动采集"这一【代码组合】，不是某个拼写。
        List<String> forbidden = slot.getForbiddenValues();
        if (forbidden != null && forbidden.contains(value)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "配置 #" + slot.getConfigNo() + " 取值 " + value
                            + " 属禁止组合（技术不可能 / 合规硬约束），拒绝写入");
        }

        List<String> allowed = slot.getAllowedValues();
        if (allowed != null && !allowed.contains(value)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "配置 #" + slot.getConfigNo() + " 取值 " + value + " 不在允许集合 " + allowed + " 内");
        }
    }

    /**
     * 是否为合法 JSON。
     *
     * <p>这里用 Jakcson 的 ObjectMapper 做<b>真正的解析</b>，而不是正则粗判：
     * 正则版会把 {@code {"a":}} 判成合法，而那正是"以为存进去了、读出来解析不了"的
     * 典型缺陷。解析器一改口径，本方法随之改变 —— 与 DB 触发器的
     * {@code ::jsonb} 是同一语义（两边都真解析）。
     */
    private static boolean isJson(String value) {
        if (value.isBlank()) {
            return false;
        }
        try {
            JSON_MAPPER.readTree(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}