package com.diaoyuanyun.dy.audit.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 审计链完整性校验结果。
 *
 * <p>契约形状固定为 {@code {valid, broken_at?}}：
 * <ul>
 *   <li>{@code valid=true} —— 整条链重算与存储 {@code hash} 逐条一致，
 *       且每条 {@code prev_hash} 与真正的物理前驱相接。此时 {@link #brokenAt()} 为 {@code null}
 *       （Jackson 下表现为<b>字段不出现</b>，见 {@code @JsonInclude(NON_NULL)}）。</li>
 *   <li>{@code valid=false} —— {@link #brokenAt()} 给出<b>第一条断链记录的 {@code id}</b>，
 *       {@link #reason()} 给出机器可判的断链种类。
 *       注意 {@code broken_at} 指向的是"断在哪"，而<b>不</b>断言"哪一行被改了"：
 *       改中间一行的 {@code payload} 会让<b>它自己</b>的 hash 对不上（指向它），
 *       而删掉中间一行会让<b>它的后继</b>对不上（指向后继）。这是链式结构的固有语义，
 *       测试里对这两种注入分别断言，不混为一谈。</li>
 * </ul>
 *
 * <p>{@code checked} 是<b>记录条数</b>，不是"校验通过条数"。它的用途是让
 * "链有效"与"链是空的"两个结论无法被混淆 —— 一个把表读成 0 行然后报 valid=true 的
 * 校验器是灾难性的假通过，这个计数器能让那种情况在断言里现形。
 *
 * <h2>Jackson 形状注解为什么必须在这里（而不是在 Web 层）</h2>
 * 本记录的对外形状是<b>它自己的语义</b>，不是某一个控制器的偏好：
 * <ul>
 *   <li>Java 字段名是 {@code brokenAt}，契约字段名是 {@code broken_at} ——
 *       若靠 Web 层的 {@code PropertyNamingStrategy.SNAKE_CASE} 全局配置来转，
 *       那么"换个 ObjectMapper 配置就换了对外形状"，而决定这个端点长什么样的
 *       权力就跑到了配置里。故用 {@link JsonProperty} <b>显式钉住</b>。</li>
 *   <li>{@code broken_at} 在链有效时<b>必须不出现</b>（{@link JsonInclude.Include#NON_NULL}
 *       施加在 record 组件上，作用于其访问器）。恒返回 {@code broken_at: null}
 *       会让消费方的 {@code if (broken_at in body)} 判不出"有效"与"没校验"的差别。</li>
 *   <li>{@code reason} 与 {@code checked} <b>不</b>标 NON_NULL —— 它们是<b>恒存在</b>的：
 *       {@code checked} 在链有效时也有值（条数），{@code reason} 在链有效时为
 *       JSON {@code null}（字段出现、值为空）。刻意与 {@code broken_at} 区别对待：
 *       前者"没有这个概念"，后者"这个概念存在且当前取空值"。</li>
 * </ul>
 * <p>dy-audit 只依赖 {@code jackson-annotations}（不带 databind）：
 * 形状的<b>声明</b>在领域侧，形状的<b>渲染</b>由 dy-app 的 ObjectMapper 完成。
 *
 * @param valid     链是否完整
 * @param brokenAt  第一条断链记录的 {@code audit_log.id}；链有效时为 null
 * @param reason    断链种类的机器可判标识；链有效时为 null
 * @param checked   实际参与校验的记录条数
 */
public record ChainVerification(
        @JsonProperty("valid") boolean valid,
        @JsonProperty("broken_at") @JsonInclude(JsonInclude.Include.NON_NULL) String brokenAt,
        @JsonProperty("reason") String reason,
        @JsonProperty("checked") int checked) {

    /** 该记录自身的 {@code hash} 与"用其字段 + 其 prev_hash 重算"的结果不一致 —— 记录被改写。 */
    public static final String REASON_HASH_MISMATCH = "HASH_MISMATCH";

    /** 该记录的 {@code prev_hash} 不等于其物理前驱的 {@code hash} —— 中间有记录被删除。 */
    public static final String REASON_PREV_HASH_MISMATCH = "PREV_HASH_MISMATCH";

    /** 链首记录的 {@code prev_hash} 不是约定的创世值 —— 链头被动了。 */
    public static final String REASON_GENESIS_MISMATCH = "GENESIS_MISMATCH";

    /** 存在记录但按全序重排时出现并列（写入顺序不可判定）—— 校验结论本身不可靠。 */
    public static final String REASON_ORDER_AMBIGUOUS = "ORDER_AMBIGUOUS";

    /** 存储的 {@code hash}/{@code prev_hash} 不是 64 位 hex —— 字段被写成垃圾/被截断。 */
    public static final String REASON_MALFORMED_HASH = "MALFORMED_HASH";

    public static ChainVerification ok(int checked) {
        return new ChainVerification(true, null, null, checked);
    }

    public static ChainVerification broken(String brokenAt, String reason, int checked) {
        return new ChainVerification(false, brokenAt, reason, checked);
    }
}