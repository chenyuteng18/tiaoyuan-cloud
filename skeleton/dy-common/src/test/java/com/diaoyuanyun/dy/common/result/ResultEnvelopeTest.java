package com.diaoyuanyun.dy.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResultEnvelopeTest: 信封四字段完整性 + <b>契约一致性</b> (ADR-06)。
 *
 * <h2>为何这个文件被重写</h2>
 * 初版仅断言 {@code assertEquals(2003, ErrorCode.TENANT_MISMATCH.getCode())} ——
 * 它断言的<b>是自己的枚举, 不是契约</b>。于是当枚举整表偏离契约时,
 * 实现错、测试也错, 两者一起绿。这类"自证式测试"把 bug 忠实锁进了基线。
 *
 * <p>现在改为: 把契约 §2.0 的 11 个码做成<b>独立的期望表</b>(不引用枚举),
 * 逐个比对"码值 / 名称 / HTTP 状态"。任何一处与契约不符即失败。
 *
 * <p><b>期望表来源</b>: {@code _work/contract-t6-api-freeze-2026-09-19.md} §2.0 L107–119。硬编码在此,
 * 是为了让"契约变更"必须显式改这个文件并留下痕迹, 而不是被实现悄悄带走。
 * 2026-09-26（S3-4 / 契约域 I）新增第 12 码 {@code 2004 PLACEHOLDER_OUT_OF_SCOPE}
 * （契约 openapi §2.0 权威表 L222-224 自域 I 起已含该码；本表去重后同步至 12 个）。
 */
class ResultEnvelopeTest {

    /**
     * 契约权威表: {code, 枚举名, HTTP}。改这里 = 声明契约变更, 须同步契约文档。
     */
    private static final List<Object[]> CONTRACT = List.of(
            new Object[]{1001, "VALIDATION_FAILED", 400},
            new Object[]{1002, "UNAUTHENTICATED", 401},
            new Object[]{2001, "VISIBILITY_DENIED", 403},
            new Object[]{2002, "GATE_MISSING", 403},
            new Object[]{2003, "TENANT_MISMATCH", 403},
            new Object[]{2004, "PLACEHOLDER_OUT_OF_SCOPE", 403},
            new Object[]{3001, "NOT_FOUND", 404},
            new Object[]{4001, "VERSION_CONFLICT", 409},
            new Object[]{4002, "IDEMPOTENT_REPLAY", 409},
            new Object[]{5001, "BUSINESS_RULE_VIOLATED", 422},
            new Object[]{6001, "RATE_LIMITED", 429},
            new Object[]{9001, "INTERNAL_ERROR", 500}
    );

    @Test
    void error_codes_match_contract_exactly() {
        assertEquals(CONTRACT.size(), ErrorCode.values().length,
                "枚举项数必须与契约一致 (不得增删码位); 契约共 " + CONTRACT.size() + " 个");
        for (Object[] row : CONTRACT) {
            int expectedCode = (int) row[0];
            String expectedName = (String) row[1];
            int expectedHttp = (int) row[2];

            ErrorCode actual = ErrorCode.valueOf(expectedName);
            assertEquals(expectedCode, actual.getCode(),
                    expectedName + " 码值不符契约");
            assertEquals(expectedHttp, actual.getHttpStatus(),
                    expectedName + " 的 HTTP 状态不符契约");
        }
    }

    @Test
    void five_xxx_is_business_rule_violated_with_422_not_dependency_failure() {
        // 回归守护: 初版把 5xxx 误标为"依赖故障"并映射 502; 契约中 5001 是业务规则违反, HTTP 422
        assertEquals(5001, ErrorCode.BUSINESS_RULE_VIOLATED.getCode());
        assertEquals(422, ErrorCode.BUSINESS_RULE_VIOLATED.getHttpStatus());
    }

    @Test
    void of_resolves_known_code_and_falls_back_safely() {
        assertEquals(ErrorCode.TENANT_MISMATCH, ErrorCode.of(2003));
        // 未登记码不得抛异常 (否则会掩盖原始错误)
        assertEquals(ErrorCode.INTERNAL_ERROR, ErrorCode.of(12345));
    }

    @Test
    void envelope_must_have_exactly_four_fields() {
        Result<String> ok = Result.ok("payload", "trace-abc");
        assertEquals(0, ok.getCode());
        assertEquals("OK", ok.getMessage());
        assertEquals("payload", ok.getData());
        assertEquals("trace-abc", ok.getTraceId());
    }

    @Test
    void success_code_is_zero_and_message_ok() {
        Result<Void> ok = Result.ok("trace-xyz");
        assertEquals(0, ok.getCode());
        assertEquals("OK", ok.getMessage());
        assertNull(ok.getData());
    }

    @Test
    void fail_maps_error_code_and_dev_message() {
        Result<Void> fail = Result.fail(ErrorCode.TENANT_MISMATCH.getCode(),
                ErrorCode.TENANT_MISMATCH.getMessage(), "trace-1");
        assertEquals(2003, fail.getCode());
        assertEquals(ErrorCode.TENANT_MISMATCH.getMessage(), fail.getMessage());
        assertEquals("trace-1", fail.getTraceId());
        assertNull(fail.getData());
    }

    @Test
    void fail_with_data_carries_extra_payload() {
        Result<Object> fail = Result.failWithData(ErrorCode.GATE_MISSING.getCode(),
                ErrorCode.GATE_MISSING.getMessage(), java.util.Map.of("missing_items", List.of("item_a")), "trace-2");
        assertEquals(2002, fail.getCode());
        assertNotNull(fail.getData());
    }

    /**
     * 信封 JSON 的 key 必须是契约规定的 snake_case: {@code trace_id}。
     * 初版 Java 属性叫 {@code traceId}, 无注解时 Jackson 输出 {@code "traceId"},
     * 客户端按 {@code trace_id} 取值会拿到 undefined。
     */
    @Test
    void envelope_json_uses_snake_case_trace_id() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Result.ok("x", "trace-999"));
        assertTrue(json.contains("\"trace_id\""), "信封必须输出 trace_id (got: " + json + ")");
        assertTrue(!json.contains("\"traceId\""), "不得输出 camelCase traceId (got: " + json + ")");
        assertTrue(json.contains("\"code\""));
        assertTrue(json.contains("\"message\""));
        assertTrue(json.contains("\"data\""));
    }

    /**
     * 回归守护 (契约 §2.0"四字段不得增删"): <b>trace_id 为 null 时也必须出现在 JSON 中。</b>
     *
     * <p>缺陷由来：类级 {@code @JsonInclude(NON_NULL)} 会把 null 的 trace_id 整个吞掉，
     * 于是过滤器兜底场景下响应体只剩 {@code {code,message}} ——
     * 客户端取 trace_id 得到 undefined、链路追踪断链。
     * 这是被 {@code TenantContextFilterTest} 抓出的真实违约（当时 38 测试里只此一项红）。
     *
     * <p>修法：在 {@code traceId} 字段与 getter 上加 {@code @JsonInclude(ALWAYS)} 覆盖类级策略；
     * 同时 {@code TenantContextFilter} 做 trace_id 兜底生成。本测试盯住其中"序列化不吞字段"这一半。
     */
    @Test
    void trace_id_key_must_survive_even_when_null() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Result.fail(1002, "未认证", null));
        assertTrue(json.contains("\"trace_id\""),
                "trace_id 为 null 时也必须保留 key (契约要求四字段恒定存在); got: " + json);
        assertTrue(json.contains("\"code\":1002"));
        assertTrue(json.contains("\"message\""));
    }

    /** 契约 §2.0: "code != 0 时 data 为空" —— 失败信封不应携带 data。 */
    @Test
    void failure_envelope_omits_data_per_contract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Result.fail(2003, "租户不匹配", "t-1"));
        assertTrue(!json.contains("\"data\""),
                "失败信封不应含 data (契约: code != 0 时 data 为空); got: " + json);
    }
}