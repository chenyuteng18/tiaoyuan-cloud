package com.diaoyuanyun.dy.tenancy.exception;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 租户不匹配: X-Tenant-Id 请求头与 JWT 内 tenant_id 不一致 (ADR-05)。
 * 全局异常处理器将其映射为 HTTP 403 + code=2003 TENANT_MISMATCH。
 */
public class TenantMismatchException extends BizException {
    public TenantMismatchException() {
        super(ErrorCode.TENANT_MISMATCH);
    }

    /**
     * 带诊断信息的构造：用于"配置值本身非法"这类需要指出**错在哪**的场景
     * （例如 {@code RowScope.fromCode()} 收到未知的行级范围 code）。
     *
     * <p>仍映射为 2003 TENANT_MISMATCH —— 因为语义上同属"调用方的租户/范围上下文不可信"，
     * 而 fail-closed 的要求是**拒绝**，不是给出默认范围。
     * 错误码保持单一，诊断信息只进日志与排查路径，不改变对外契约。
     */
    public TenantMismatchException(String diagnostic) {
        super(ErrorCode.TENANT_MISMATCH, diagnostic);
    }
}
