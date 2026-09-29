package com.diaoyuanyun.dy.web.idempotent;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 幂等键冲突: 同一 {@code Idempotency-Key} 携带了<b>不同的请求体</b> (ADR-10)。
 *
 * <p><b>码位选择说明（不得改动）</b>：契约 §2.0 中与幂等相关的错误码只有一个 ——
 * {@code 4002 IDEMPOTENT_REPLAY}，其触发条件写作"幂等键命中已有记录（返回首次结果）"，
 * 映射 HTTP <b>409</b>。
 *
 * <p>两种"命中"在契约里共用 4002，靠<b>处理方式</b>区分：
 * <ul>
 *   <li><b>同键同体</b> → 直接返回首次响应 + {@code X-Idempotent-Replayed: true}（<b>不是错误</b>，不抛本异常）；</li>
 *   <li><b>同键不同体</b> → 拒绝，即本异常（409 / 4002）。</li>
 * </ul>
 * <b>严禁</b>为此自造新码位（初版曾自造 {@code 4002 IDEMPOTENT_KEY_ILLEGAL}，与契约 4002 撞号，已纠正）。
 */
public class IdempotencyConflictException extends BizException {

    public IdempotencyConflictException() {
        super(ErrorCode.IDEMPOTENT_REPLAY, "幂等键冲突: 同键携带了不同的请求体");
    }
}