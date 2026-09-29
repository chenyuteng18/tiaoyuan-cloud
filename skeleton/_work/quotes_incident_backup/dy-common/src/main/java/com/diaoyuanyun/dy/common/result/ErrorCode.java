package com.diaoyuanyun.dy.common.result;

/**
 * 可编程错误码枚举 (ADR-06)。
 *
 * <p><b>权威来源</b>：契约 §2.0 错误码表
 * ({@code _work/contract-t6-api-freeze-2026-09-19.md} L107–119)。
 * <b>本枚举是契约的逐字映射，不得增删、不得改名、不得自造码位。</b>
 *
 * <p><b>为何把 HTTP 状态内聚在枚举里</b>：初版实现把"码 -> HTTP"的映射写成
 * {@code GlobalExceptionHandler} 里的 if-else 分段判断（如 {@code 5xxx -> 502}），
 * 该写法把两份契约拆到两个文件，任一处改动都会静默漂移。内聚后，
 * "码值 + 语义 + HTTP 状态 + 默认文案"是同一个原子事实，测试可逐条比对契约。
 *
 * <p><b>分段语义（注意 5xxx 不是"依赖故障"）</b>：
 * <ul>
 *   <li>{@code 1xxx} 通用 / 认证参数</li>
 *   <li>{@code 2xxx} 权限与租户（<b>403 三态</b>：2001 / 2002 / 2003）</li>
 *   <li>{@code 3xxx} 资源</li>
 *   <li>{@code 4xxx} 契约 / 并发冲突</li>
 *   <li>{@code 5xxx} <b>业务规则</b>（HTTP <b>422</b>）</li>
 *   <li>{@code 6xxx} 限流</li>
 *   <li>{@code 9xxx} 系统</li>
 * </ul>
 *
 * <p>{@code message} 是<b>给开发者看的</b>，严禁直接进客户端 UI（合规红线，竞析 H.1-11）；
 * 客户端文案须经"错误码 -> 客户端文案"的独立映射层，并先过合规词表。
 */
public enum ErrorCode {

    // ===== 1xxx 通用 / 认证参数 =====
    VALIDATION_FAILED(1001, 400, "参数校验失败"),
    UNAUTHENTICATED(1002, 401, "未认证"),

    // ===== 2xxx 权限与租户（403 三态）=====
    VISIBILITY_DENIED(2001, 403, "可见性不足"),
    GATE_MISSING(2002, 403, "门禁未通过"),
    TENANT_MISMATCH(2003, 403, "租户不匹配"),
    PLACEHOLDER_OUT_OF_SCOPE(2004, 403, "占位符越界"),

    // ===== 3xxx 资源 =====
    NOT_FOUND(3001, 404, "资源不存在"),

    // ===== 4xxx 契约 / 并发冲突 =====
    VERSION_CONFLICT(4001, 409, "版本冲突(不可覆盖)"),
    IDEMPOTENT_REPLAY(4002, 409, "幂等键命中(返回首次结果)"),

    // ===== 5xxx 业务规则（HTTP 422！不是 502、不是 500）=====
    BUSINESS_RULE_VIOLATED(5001, 422, "业务规则不满足"),

    // ===== 6xxx 限流 =====
    RATE_LIMITED(6001, 429, "请求过于频繁"),

    // ===== 9xxx 系统 =====
    INTERNAL_ERROR(9001, 500, "系统内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    /** 按数值码查找；未登记返回 {@link #INTERNAL_ERROR}（fail-safe，不抛异常以免掩盖原始错误）。 */
    public static ErrorCode of(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return INTERNAL_ERROR;
    }
}