package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 回执三态（PRD P0-19「发送留痕进证据链 · 三态全覆盖、三态都落库」）。
 *
 * <h2>三态的字面里，第二个最容易被人"顺手改短"</h2>
 * 契约 {@code RefundReceiptData.receipt_state} 与库 CHECK 都是
 * {@code 未授权（转线下）} —— 用的是<b>全角括号</b>，且括号内是<b>转线下</b>而非"转线下告知"。
 * 这不是排版洁癖：端侧按契约逐字渲染状态位，字面一改，端上就显示成一个契约里不存在的值。
 *
 * <h2>🛑「未授权」是一个独立留痕记录，不是"发送失败的到达状态"</h2>
 * 这是 P0-19 里写得最重的一条，也是本枚举必须存在的根本理由：
 * <pre>
 *   未授权 = 推送事件根本没发生
 *            （客户无订阅额度 → 在【尝试发送之前、判定无额度时】即落一条记录）
 * </pre>
 * 若系统只在"已推送"之后才记"到达 / 未到达"，<b>未授权就根本不会产生一条记录</b>，
 * 留痕里查无此事。后果是分子分母一起出问题：
 * <pre>
 *   P1-10 覆盖率分母 = 已推送 + 未授权（转线下） + 推送失败
 *   若「未授权」从不落库 → 分母缺一块 → 覆盖率系统性偏高，且偏高得看不出来
 * </pre>
 * 故 {@link #UNAUTHORIZED} 在 {@link #isPushAttempted()} 上恒为 {@code false} ——
 * 它落库的时刻在"判定无额度"之后、"尝试发送"之前，系统里<b>没有</b>一次推送尝试。
 * 任何把"未授权"当成"推送了但没到"的实现，都会让本字段与库层
 * {@code refund_receipt_pushed_at_iff_pushed} 约束互相矛盾。
 */
public enum ReceiptState {

    /** 已推送（推送事件已发生；有 pushed_at）。 */
    PUSHED("已推送", true),

    /**
     * 未授权（转线下）——
     * 🛑 <b>推送事件根本没发生</b>：客户无订阅额度，在尝试发送<b>之前</b>判定即落库。
     * 它不是"发送失败的到达状态"；它没有 {@code pushed_at}，只有 {@code decided_at}。
     */
    UNAUTHORIZED("未授权（转线下）", false),

    /**
     * 推送失败（推送已尝试但失败；有失败原因，无 pushed_at）。
     *
     * <p>🛑 第二参数是 {@code true} —— 它此前被写成了 {@code false}，而那是本枚举<b>唯一</b>
     * 一处真实缺陷：类注释（L29）与 {@link #isPushAttempted()} 的 javadoc（L69）都逐字声明
     * 「{@link #UNAUTHORIZED} 为 {@code false} —— 这是三态语义的<b>唯一分歧点</b>」，
     * 而 {@code PUSH_FAILED} 的值与这句话矛盾：它自己的 javadoc 写着「推送<b>已尝试</b>但失败」。
     *
     * <h2>三处后果（都属"隐蔽"，所以先写在最显眼处）</h2>
     * <ol>
     *   <li><b>同一个量承载了两个语义</b>：它同时表达着「有没有尝试推送」与
     *       「值是不是 false」。于是"三态语义的唯一分歧点"这句话变成假的 ——
     *       下一个读它的人会以为另有第二个分歧点，从而对三态的语义失去把握。</li>
     *   <li><b>把"上过场但没成功"与"没上场"混成一类</b>：分流逻辑若以本方法区分
     *       「推送是否被尝试过」，{@link #PUSH_FAILED} 会被归到"从未尝试"那一侧 ——
     *       而它明明产生了一次真实的通道调用（可能有厂商计费、gonc 限流计数、
     *       以及"模板被拒"与"从未发送"完全不同的处置路径）。</li>
     *   <li><b>库层恰好提供不了对照</b>：{@code refund_receipt} 的 CHECK 只钉住
     *       「已推送 ⟺ 有 pushed_at」，对另两态不加区分 —— 所以这个错误
     *       <b>不会被任何库约束、任何既有测试发现</b>。它是靠"枚举值与自身文档矛盾"
     *       这一条被读出来的（本轮 S2-3 测试首次断言它，立刻报红）。</li>
     * </ol>
     *
     * <p>真正的分歧点仍然唯一：{@link #UNAUTHORIZED} 是唯一"推送事件<b>根本没发生</b>"的态
     * （无额度 → 在尝试发送<b>之前</b>判定即落库）；{@link #PUSH_FAILED} 是"发生了但没成功"，
     * 与 {@link #PUSHED} 同侧。
     */
    PUSH_FAILED("推送失败", true);

    private final String code;
    private final boolean pushAttempted;

    ReceiptState(String code, boolean pushAttempted) {
        this.code = code;
        this.pushAttempted = pushAttempted;
    }

    /** 状态位字面（契约 {@code RefundReceiptData.receipt_state} 与库 CHECK 逐字一致）。 */
    public String code() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(ReceiptState::code).toList();
    }

    /**
     * 本态是否意味着"确实发生了一次推送尝试"。
     *
     * <p>{@link #UNAUTHORIZED} 为 {@code false} —— 这是三态语义的<b>唯一分歧点</b>，
     * 也是"未授权必须独立落库"这条约束在代码里的落点：解释器（{@code ReceiptInterpreter}）
     * 在判定无额度时走的是"落 {@link #UNAUTHORIZED}"这条分支，
     * 而<b>不是</b>走"先建推送记录、再把到达状态标成未到达"。
     */
    public boolean isPushAttempted() {
        return pushAttempted;
    }

    /** 是否须有 {@code pushed_at}（与库约束一致：只有"已推送"有）。 */
    public boolean requiresPushedAt() {
        return this == PUSHED;
    }

    public static ReceiptState parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执状态（receipt_state）必填 —— 三态之一缺落即证据链断链（P0-19）");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(s -> s.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "回执状态不在允许值内: '" + code + "'（合法值: " + allCodes() + "）"));
    }
}