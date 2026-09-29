package com.diaoyuanyun.dy.web.idempotent;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 幂等键格式非法 (ADR-10)。
 *
 * <p>契约中键格式非法属于"参数校验失败", 对应 {@code 1001 VALIDATION_FAILED}（HTTP 400）。
 * 初版自造了 {@code 4002 IDEMPOTENT_KEY_ILLEGAL} —— 但契约 4002 已被
 * {@code IDEMPOTENT_REPLAY} 占用, 不得自造码位。
 */
public class IdempotencyKeyIllegalException extends BizException {

    public IdempotencyKeyIllegalException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, "幂等键非法: " + detail);
    }
}