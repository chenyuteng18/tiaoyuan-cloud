package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一行转线下告知留痕（{@code refund_offline_notice} 的一行）。
 *
 * <h2>🛑 为什么它与 {@link RefundReceiptRow} 是<b>两个</b> record、两张表</h2>
 * P0-19 原文把它们写在同一段里（「兜底：自动转门店电话 / 当面告知 + 系统留痕」），
 * 但它们是<b>两种事实</b>：
 * <pre>
 *   refund_receipt          = 一次【推送事件】（系统做的）
 *   refund_offline_notice   = 一次【人工告知动作】（人做的）
 * </pre>
 * 合并成一张表（例如加一个 {@code channel='电话'} 的回执行）会有两个后果，
 * 而两个都是静默的：
 * <ol>
 *   <li><b>覆盖率分母被污染</b>：P1-10 的分母逐字是
 *       「已推送 + 未授权（转线下） + 推送失败」，<b>不含</b>线下告知。
 *       一旦线下告知也记进 {@code refund_receipt}，分母会凭空变大，
 *       送达率随之<b>偏低</b> —— 而偏低会让人以为"推送通道不行"，
 *       于是去优化推送，而真正的问题是它被算重了。</li>
 *   <li><b>"漏发"这件事变得无法判定</b>：{@link ReceiptInterpreter#assertOfflineFallbackRecorded}
 *       要问的是"这条未授权的工单，有没有一次人工告知"。若两者同表，
 *       这个问题会退化成"有没有一行 channel=电话 的记录" ——
 *       而每次推送尝试都会写一行，于是它<b>永远为真</b>。
 *       那正是 P0-19 警告的"变成一个永远显示成功的开关"。</li>
 * </ol>
 * 故分表，且本 record 的 {@code channel} <b>只</b>允许"电话 / 当面"——
 * 这是"人工告知"这一语义在类型上的落点。
 *
 * @param noticeId   告知留痕主键
 * @param refundId   所属退款工单
 * @param channel    告知方式（<b>只允许</b> 电话 / 当面）
 * @param noticedAt  告知时刻
 * @param operatorId 操作人（谁告知的 —— 证据链要求）
 * @param note       备注（如"已电话告知，客户确认")
 * @param createdBy  创建者标识
 */
public record RefundOfflineNoticeRow(
        UUID noticeId,
        UUID refundId,
        String channel,
        Instant noticedAt,
        UUID operatorId,
        String note,
        String createdBy) {

    /**
     * 线下告知的合法通道（与 V6 建表 CHECK 逐字一致）。
     *
     * <p>🛑 刻意<b>不</b>包含"订阅消息"：本表的语义是"人工告知动作"，
     * 而订阅消息是推送事件。若允许它出现在这里，就出现了两个"推送事件"的落点，
     * 上面注释里的第 2 个后果（"漏发"永远为真）会立刻成立。
     */
    public static final List<String> CHANNELS = List.of("电话", "当面");

    /**
     * 校验通道合法（供仓储写入前调用，与库层 CHECK 同口径但报错更早、更可读）。
     *
     * <p>库层 CHECK 报的是 {@code 23514 + 约束名}，而"为什么这个通道不行"
     * 要人去查约束名；更重要的是，库层报错发生在事务提交时，那时
     * "是哪张工单、谁在告知"已经不在手上。故在写入前先说清楚。
     */
    public static String requireLegalChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "转线下告知缺通道 —— P0-19 要求留痕『已转线下告知 + 操作人 + 时间』，"
                            + "通道缺失则该行无法证明告示是通过什么方式完成的");
        }
        String c = channel.trim();
        if (!CHANNELS.contains(c)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "转线下告知的通道不在允许值内: '" + channel + "'（合法值: " + CHANNELS + "）。"
                            + "🛑 本表是【人工告知动作】的留痕，与 refund_receipt 的【推送事件】分表 —— "
                            + "把『订阅消息』写进本表会让覆盖率分母被重复计入，"
                            + "并使『漏发回执』的判定退化成永远为真");
        }
        return c;
    }

    /** 全部合法通道（供门禁断言"与库 CHECK / 契约枚举齐备"）。 */
    public static List<String> allChannels() {
        return CHANNELS;
    }
}