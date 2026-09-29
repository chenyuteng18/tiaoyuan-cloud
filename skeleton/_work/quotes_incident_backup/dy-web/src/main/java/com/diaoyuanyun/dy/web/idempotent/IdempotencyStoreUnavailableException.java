package com.diaoyuanyun.dy.web.idempotent;

/**
 * 幂等存储不可用 (ADR-10)。
 *
 * <h2>为什么单独建一个异常类型, 而不是直接抛 {@code RedisConnectionFailureException}</h2>
 * 幂等存储的失败语义属于 <b>API 契约</b>（"存储故障时接口整体 5xx, 绝不静默放行"）, 而
 * {@code RedisConnectionFailureException} 属于<b>某个具体介质</b>。若让介质异常直接冒到
 * {@code IdempotencyInterceptor}, 换介质（Redis -> 别的 KV）就会改变对外行为, 且不同介质的
 * 失败形态各不相同（连接超时 / 序列化错误 / 集群槽位漂移…）, 调用方无法用一处判断覆盖。
 * 收敛成单一类型后, "故障面"是可枚举的、可测试的。
 *
 * <h2>继承关系</h2>
 * 继承 {@link RuntimeException} 而<b>不是</b> {@code BizException}。理由：这是基础设施故障,
 * 不是业务规则问题。契约的 4xxx/5xxx 码位都在描述"业务上不允许", 而"幂等存储连不上"的正确
 * 表达是系统级 5xx（{@code 9001 INTERNAL_ERROR}）, 由
 * {@code GlobalExceptionHandler.handleOther} 兜底。故它刻意不携带业务码位 —— 不给它自造码位,
 * 也避免被 {@code @ExceptionHandler(BizException.class)} 误接成 4xx。
 *
 * <p><b>fail-closed 依据</b>：幂等键是重复提交的防线（重复下单 / 重复扣款 / 重复发放）。
 * 防线失效时"报错让客户端重试"的代价可控（客户端会带同一个键重试, 语义仍然是幂等的）;
 * 而"静默放行"的代价是一次真实的重复副作用, 且无法事后区分哪一次是重复的。二者不对称,
 * 故选择 fail-closed。详见 {@link IdempotencyStore} 类注释。
 */
public class IdempotencyStoreUnavailableException extends RuntimeException {

    public IdempotencyStoreUnavailableException(String message) {
        super(message);
    }

    public IdempotencyStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}