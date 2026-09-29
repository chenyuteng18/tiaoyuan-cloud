package com.diaoyuanyun.dy.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一响应信封 (ADR-06)。
 *
 * <p>四字段固定, 不得增删: {@code code / message / data / trace_id}。
 * {@code message} 是给开发者看的服务器消息, 严禁直接进客户端 UI (合规红线, 竞析 H.1-11)。
 *
 * <p><b>字段名必须是 snake_case</b>：契约 §2.0 明确信封为 {@code trace_id}。
 * Java 属性名是 {@code traceId}，Jackson 默认会序列化成 {@code "traceId"}，
 * 客户端按 {@code trace_id} 取值将拿到 {@code undefined}。故显式加 {@link JsonProperty}。
 *
 * <p><b>字段存在性策略（契约 §2.0：四字段"不得增删"）</b>：
 * <ul>
 *   <li>{@code code} / {@code message} / <b>{@code trace_id}</b> —— <b>恒在</b>，永不因 null 被省略。
 *       故 {@code trace_id} 上加 {@code @JsonInclude(ALWAYS)} 覆盖类级 NON_NULL：
 *       初版靠类级 NON_NULL，导致过滤器兜底场景下 {@code trace_id:null} 被整个吞掉，
 *       响应体只剩 {@code {code,message}} —— 客户端取 {@code trace_id} 得到 undefined，
 *       链路追踪断链。这是被测试（TenantContextFilterTest）抓出的真实违约。</li>
 *   <li>{@code data} —— <b>允许省略</b>：NON_NULL 使其在成功无数据/失败时不出现，
 *       符合契约 {@code "code != 0 时 data 为空"} 的表述。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 业务错误码: 0=成功; 其余见 {@link ErrorCode}（分段: 1xxx 通用/认证 · 2xxx 权限租户 · 3xxx 资源 · 4xxx 契约冲突 · 5xxx 业务规则 · 6xxx 限流 · 9xxx 系统） */
    private int code;
    /** 开发者消息 (非客户端文案) */
    private String message;
    /** 业务数据 */
    private T data;
    /**
     * 链路追踪 ID, 由 MDC 回填; 序列化名必须是 trace_id。
     * <b>ALWAYS</b>: 覆盖类级 NON_NULL —— 契约要求该字段恒在, 不允许因 null 被省略。
     */
    @JsonProperty("trace_id")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String traceId;

    public Result() {
    }

    public Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> ok(T data, String traceId) {
        return new Result<>(0, "OK", data, traceId);
    }

    public static <T> Result<T> ok(String traceId) {
        return ok(null, traceId);
    }

    public static <T> Result<T> fail(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId);
    }

    /** 失败且携带业务数据 (如门禁缺失项的 missing_items[]) */
    public static <T> Result<T> failWithData(int code, String message, T data, String traceId) {
        return new Result<>(code, message, data, traceId);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @JsonProperty("trace_id")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
