package com.diaoyuanyun.dy.common.exception;

import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;

/**
 * 可见性档位不足，且<b>必须回显被拒字段名</b>（S1-5 · X-1）。
 *
 * <h2>权威来源</h2>
 * 契约 {@code _work/contract-t6-api-freeze-2026-09-19.md} §3.2：
 * <pre>
 *   客户 token 调 GET /customers/{id}/band/derived、/verdicts、include=derived 等
 *   → 403 VISIBILITY_DENIED；data.denied_fields[] 回显被拒字段名
 * </pre>
 * 契约 §2.1 B4 亦逐字写明 {@code data.denied_fields=["effect_verdict","as_value"]}。
 *
 * <h2>为什么必须带 denied_fields，而不是只报权限不足</h2>
 * 契约 §1.3 / P0-08 的「<b>不得模糊报错</b>」：只说"无权"会让调用方无从判断
 * 该去掉哪个字段再重试，只能靠猜或抓服务端日志 —— 而客户端的每一次盲目重试
 * 都是一次可能命中禁区的尝试。回显字段名把"猜"变成"改一个确定的参数"。
 *
 * <h2>为什么放在 dy-common</h2>
 * 与 {@link GateMissingException} 同一条理由：负责把异常转成契约信封的是
 * {@code dy-web} 的 {@code GlobalExceptionHandler}，而 {@code dy-web} <b>不依赖</b>
 * {@code dy-security}（ADR-03 单向依赖）。异常类型若定义在 dy-security，
 * 全局处理器就只能靠 {@code getData()} 反射取值 —— 那是"两个实现可以不一致"的口子。
 */
public class VisibilityDeniedException extends BizException {

    /** 被拒的字段名（契约要求的 {@code data.denied_fields[]}）。恒非 null；至少一项。 */
    private final List<String> deniedFields;

    public VisibilityDeniedException(List<String> deniedFields, String devMessage) {
        // 🛑 校验必须发生在【构造 super 之前】的位置。
        // Java 要求 super(...) 是构造器第一条语句，而 super 的实参里原本有
        // List.copyOf(deniedFields) —— 传 null 时它在【守卫语句之前】就抛 NPE，
        // 于是"我没有字段可报"这条业务错误被伪装成一个空指针，
        // 调用方拿到的不是一条可诊断的信息，而是一处 500。
        // 故把校验收进 requireFields(...)，让它成为 super 实参求值的一部分：
        // 传 null / 空 / 含 null 元素，一律在构造期以 IllegalArgumentException 明确拒绝。
        super(ErrorCode.VISIBILITY_DENIED, devMessage,
                java.util.Map.of("denied_fields", requireFields(deniedFields)));
        this.deniedFields = List.copyOf(deniedFields);
    }

    /**
     * 校验并复制被拒字段名 —— <b>构造期的唯一入口</b>。
     *
     * <p>三条拒绝理由（各自对应一种真实误用）：
     * <ol>
     *   <li>{@code null} —— 调用方"还没算出字段名"就抛了拒绝；</li>
     *   <li>空列表 —— 等于"无权"式模糊报错，违反契约 §1.3 / P0-08；</li>
     *   <li>含 {@code null} 元素 —— 响应体里就会出现一个 null 字段名，
     *       客户端既无法定位也无法重试。{@code List.copyOf} 本身拒绝 null 元素，
     *       但它抛的是 NPE；此处显式转成可诊断的 IllegalArgumentException。</li>
     * </ol>
     */
    private static List<String> requireFields(List<String> deniedFields) {
        if (deniedFields == null || deniedFields.isEmpty()) {
            // 不带被拒字段名的可见性拒绝等于模糊报错；宁可在此炸掉，也不产出一个不可诊断的 403。
            throw new IllegalArgumentException(
                    "可见性拒绝必须说明被拒字段（denied_fields 不得为空 / 不得为 null）");
        }
        for (String f : deniedFields) {
            if (f == null || f.isBlank()) {
                throw new IllegalArgumentException(
                        "可见性拒绝的字段名不得为 null / 空白（实际: " + deniedFields + "）—— "
                                + "回显出这样的值，客户端既无法定位也无法重试");
            }
        }
        return List.copyOf(deniedFields);
    }

    public List<String> getDeniedFields() {
        return deniedFields;
    }
}