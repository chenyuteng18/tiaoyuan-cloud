package com.diaoyuanyun.dy.tenancy.jwt;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * JWT 校验失败 -> 认证失败 (HTTP 401 + code=1002 UNAUTHENTICATED)。
 *
 * <p><b>为何不复用 TENANT_MISMATCH(2003)</b>：语义不同。2003 是"已认证但租户头与 token 不一致"（403，
 * 授权失败）；本异常是"token 本身不可信"（401，认证失败）。混用会让客户端无法区分
 * "重新登录"与"别乱传租户头"，也会让安全审计丢失关键区分度。
 *
 * <p><b>不回显失败细节</b>：对外只给 {@code UNAUTHENTICATED} 的通用文案，
 * 具体原因（签名错/过期/alg 混淆）只进服务端日志 —— 否则等于给攻击者提供调试反馈。
 */
public class JwtValidationException extends BizException {

    /** 失败原因枚举仅用于服务端日志与测试断言，<b>不下发客户端</b>。 */
    public enum Reason {
        MALFORMED,
        ALG_NOT_ALLOWED,
        SIGNATURE_INVALID,
        EXPIRED,
        NOT_YET_VALID,
        ISSUER_MISMATCH,
        REVOKED,
        MISSING_TENANT
    }

    private final Reason reason;

    public JwtValidationException(Reason reason) {
        super(ErrorCode.UNAUTHENTICATED, "JWT 校验失败: " + reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}