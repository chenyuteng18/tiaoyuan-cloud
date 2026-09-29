package com.diaoyuanyun.dy.config.service;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.config.validation.DefaultConfigValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ConfigServiceFailClosedTest: 配置 fail-closed 与保存时校验 (ADR-08, H-13/H-14)。
 */
class ConfigServiceFailClosedTest {

    private ConfigServiceImpl service() {
        return new ConfigServiceImpl(new DefaultConfigValidator());
    }

    @Test
    void missing_config_is_rejected_not_defaulted() {
        BizException ex = assertThrows(BizException.class, () -> service().getRequired("cfg:missing"));
        // 契约中无"配置缺失"专用码位; fail-closed 语义归属 VISIBILITY_DENIED(2001), 不得自造码
        assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), ex.getCode());
    }

    @Test
    void valid_config_reads_back() {
        ConfigServiceImpl svc = service();
        svc.set("cfg:threshold.max_retention_days", "90");
        assertEquals("90", svc.getRequired("cfg:threshold.max_retention_days"));
    }

    @Test
    void illegal_threshold_value_is_rejected_on_save() {
        ConfigServiceImpl svc = service();
        // 阈值类配置必须非负整数, "abc" 非法 -> 保存即拒绝
        BizException ex = assertThrows(BizException.class,
                () -> svc.set("cfg:threshold.max_retention_days", "abc"));
        // 取值非法属"业务规则不满足" -> 5001 (HTTP 422), 非参数格式错 1001
        assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), ex.getCode());
        assertEquals(422, ErrorCode.BUSINESS_RULE_VIOLATED.getHttpStatus());
    }

    @Test
    void illegal_key_naming_is_rejected_on_save() {
        ConfigServiceImpl svc = service();
        assertThrows(BizException.class, () -> svc.set("BADKEY", "1"));
    }
}
