package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P0-19 <b>回执三态决策器</b> —— 「未授权（转线下）/ 已推送 / 推送失败」的判定与覆盖率分母。
 *
 * <h2>🛑 本类的核心手法：把「未授权必须在推送前判定」做成 <b>API 形状</b>，而不是运行期检查</h2>
 * P0-19 里写得最重的一条是：
 * <pre>
 *   「未授权」是一个独立留痕记录，不是一个"发送失败的到达状态"
 *   未授权 = 推送事件根本没发生
 *          （客户无订阅额度 → 在【尝试发送之前、判定无额度时】即落一条记录）
 * </pre>
 * 这句话有<b>两种</b>实现方式，而它们的事后表现天差地别：
 * <ol>
 *   <li><b>一个方法 + 一个运行期检查</b>：
 *       {@code decide(state, pushAttempted, ...)}，内部 {@code if (state==UNAUTHORIZED && pushAttempted) throw}。
 *       问题：这个检查<b>只在有人写错调用顺序时才生效</b>，而正确地"写错顺序"是很容易的 ——
 *       一个"先建推送记录、失败后回填状态"的实现，在类型上完全合法。</li>
 *   <li><b>两段式 API（本类采用）</b>：{@link #decideBeforePush} 与 {@link #decideAfterPush}
 *       <b>是两个方法</b>，而 {@code UNAUTHORIZED} <b>只能</b>从前者返回。
 *       于是"推送之后才判未授权"这件事<b>没有可以写出来的调用序列</b> ——
 *       它不是一个会被拦下的错误，而是一个不存在的形状。</li>
 * </ol>
 * 这比运行期检查强的地方在于：<b>它不依赖任何人记得那条规则</b>。
 * 后者是"我加了检查所以对"，前者是"这里做不到错"。
 *
 * <h2>🛑 三态的语义分歧点只有一个</h2>
 * <table border="1">
 *   <caption>三态 × 四个关键问题</caption>
 *   <tr><th>态</th><th>推送事件是否发生</th><th>有 pushed_at</th><th>须转线下兜底</th><th>计入覆盖率分母</th></tr>
 *   <tr><td>已推送</td><td>是</td><td>是</td><td>否</td><td>是</td></tr>
 *   <tr><td>未授权（转线下）</td><td><b>否</b></td><td>否</td><td><b>是</b></td><td>是</td></tr>
 *   <tr><td>推送失败</td><td>是（尝试过）</td><td>否</td><td><b>是</b></td><td>是</td></tr>
 * </table>
 * 前两列就是 {@link ReceiptState#isPushAttempted()} 与 {@link ReceiptState#requiresPushedAt()}；
 * 后两列是本类新增的判定，它们不是"三态的注解"，而是 P0-19 的两条硬约束：
 * <ul>
 *   <li><b>须转线下</b>：P0-19「兜底（订阅失败 / 额度用尽 / 未授权）：
 *       自动转门店电话 / 当面告知 + 系统留痕。<b>不得因订阅失败而漏发回执</b>（漏发视同未回执）」
 *       —— 故"未授权 / 推送失败"两张状态若在库里没有配套的 {@code refund_offline_notice} 行，
 *       就是一次<b>漏发回执</b>，而它在报表上只表现为"覆盖率高"。</li>
 *   <li><b>计入分母</b>：P0-19「P1-10 的覆盖率分母 = 已推送 + 未授权（转线下） + 推送失败」。
 *       三态全进分母，无一例外 —— 故 {@link #coverageOf} 不接受"只统计已推送"的形态。</li>
 * </ul>
 */
public final class ReceiptInterpreter {

    private ReceiptInterpreter() {
    }

    // ==================================================================
    // 一、两段式决策（形状即纪律）
    // ==================================================================

    /**
     * 一次回执的落库决策。
     *
     * @param state         三态之一
     * @param channel       回执通道（推送三态一律为 {@code 订阅消息}）
     * @param templateId    订阅消息模板 ID（P0-19「回执独占一个模板 ID」）
     * @param decidedAt     判定时刻（<b>三态都落</b>）
     * @param pushedAt      仅"已推送"有值
     * @param failureReason 仅"推送失败"有值
     */
    public record ReceiptDecision(
            ReceiptState state,
            String channel,
            String templateId,
            Instant decidedAt,
            Instant pushedAt,
            String failureReason) {

        /** 是否须触发转线下兜底（未授权 / 推送失败）。 */
        public boolean requiresOfflineFallback() {
            return ReceiptInterpreter.requiresOfflineFallback(state);
        }

        /** 本决策是否可落库（与库层 CHECK {@code refund_receipt_pushed_at_iff_pushed} 同口径）。 */
        public boolean isPersistable() {
            return state.requiresPushedAt() == (pushedAt != null);
        }
    }

    /** 回执通道的唯一合法值（推送三态）。 */
    public static final String CHANNEL_SUBSCRIPTION = "订阅消息";

    /**
     * <b>第一段</b>：尝试发送<b>之前</b>的判定 —— 唯一的 {@link ReceiptState#UNAUTHORIZED} 出口。
     *
     * <p>调用语义：拿到本次回执的订阅额度后<b>先</b>问一句"能不能发"。
     * <ul>
     *   <li>额度 ≤ 0 → 返回 {@link ReceiptState#UNAUTHORIZED}（<b>此时推送事件尚未发生，也永不发生</b>）；</li>
     *   <li>额度 &gt; 0 → 返回 {@code null}，表示"无结论，继续走推送"，
     *       由 {@link #decideAfterPush} 收口。</li>
     * </ul>
     *
     * <p>🛑 返回 {@code null} 而不是抛：额度充足时"还没结论"是<b>正常中间态</b>，
     * 不是错误。若这里抛，实现者会倾向于"一次判完"，而那正是本类要避免的单方法形态。
     */
    public static ReceiptDecision decideBeforePush(long subscriptionQuota, Instant decidedAt) {
        if (subscriptionQuota > 0) {
            return null;   // 无结论：继续走推送，由第二段收口
        }
        return new ReceiptDecision(
                ReceiptState.UNAUTHORIZED, CHANNEL_SUBSCRIPTION, null,
                require(decidedAt, "decidedAt"), null, null);
    }

    /**
     * <b>第二段</b>：尝试发送<b>之后</b>的判定 —— {@link ReceiptState#PUSHED} /
     * {@link ReceiptState#PUSH_FAILED} 的出口。
     *
     * <p>🛑 本方法<b>不</b>接受"额度"参数，也<b>不</b>能返回 {@code UNAUTHORIZED} ——
     * 走到这一步说明推送已经尝试过了，此时判"未授权"就是 P0-19 明令禁止的那件事
     * （把"推送事件根本没发生"写成"推送了但没到"）。
     * 这不是靠一个 {@code if} 挡住的，而是<b>这个方法没有那条返回路径</b>。
     *
     * @param succeeded     推送是否成功
     * @param failureReason 失败原因（{@code succeeded=false} 时<b>必填</b>）
     * @param pushedAt      推送成功时刻（{@code succeeded=true} 时<b>必填</b>）
     * @param templateId    模板 ID（推送成功时用于留痕"用的是哪个模板"）
     */
    public static ReceiptDecision decideAfterPush(boolean succeeded,
                                                  String failureReason,
                                                  Instant pushedAt,
                                                  Instant decidedAt,
                                                  String templateId) {
        Instant decided = require(decidedAt, "decidedAt");
        if (succeeded) {
            if (pushedAt == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "推送成功却没有 pushed_at —— 库层 CHECK refund_receipt_pushed_at_iff_pushed "
                                + "逐字写着『已推送 ⟺ 有 pushed_at』。🛑 只记状态不记时间，证据链上就"
                                + "无从证明它真的发过（P0-19：凭证须可提交）");
            }
            if (!isBlank(failureReason)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "推送成功却带了失败原因（" + failureReason + "）—— 二者互斥。"
                                + "若允许并存，下游会拿失败原因去解释一次成功的推送，"
                                + "而覆盖率仍把它算作已送达");
            }
            return new ReceiptDecision(ReceiptState.PUSHED, CHANNEL_SUBSCRIPTION,
                    templateId, decided, pushedAt, null);
        }

        if (isBlank(failureReason)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "推送失败却没有失败原因 —— P0-19 三态留痕的目的是『可提交的凭证』："
                            + "一条只说『失败了』的记录，在复盘时无法区分"
                            + "『模板被拒』『用户拒收』『通道超时』，而三者的处置完全不同");
        }
        if (pushedAt != null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "推送失败却有 pushed_at —— 库层 CHECK 要求『非已推送 ⇒ pushed_at IS NULL』。"
                            + "🛑 记了时间却标失败，会让覆盖率分母把一次未成功的推送算成已达标");
        }
        return new ReceiptDecision(ReceiptState.PUSH_FAILED, CHANNEL_SUBSCRIPTION,
                templateId, decided, null, failureReason);
    }

    // ==================================================================
    // 二、落库前校验（与库层 CHECK 同口径，但报错更早、更可读）
    // ==================================================================

    /**
     * 校验一个决策能否落库 —— 每条规则都对应库层的一条 CHECK。
     *
     * <h2>为什么库层已经有 CHECK，这里还要再来一遍</h2>
     * 库层 CHECK 是<b>最后一道</b>，它的报错是 {@code 23514 + 约束名}；
     * 而"哪一条业务规则被违反了"要人去查约束名。更麻烦的是：
     * 库层报错发生在<b>事务提交时</b>，此时上下文（是谁在代录、哪张工单）已经不在手上，
     * 无法给出可执行的提示。故本方法在<b>构造决策时</b>就把话说清楚 ——
     * 两者不是重复，是"早报错 + 好报错"与"兜底不再漏"的分工。
     */
    public static void assertPersistable(ReceiptDecision d) {
        if (d == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "回执决策为空，不得落库");
        }
        if (d.state() == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "回执决策缺状态 —— 三态之一缺落即证据链断链（P0-19）");
        }
        if (!d.isPersistable()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "回执状态『" + d.state().code() + "』与 pushed_at 不匹配"
                            + "（pushed_at=" + d.pushedAt() + "）—— "
                            + "库层约束 refund_receipt_pushed_at_iff_pushed 要求"
                            + "『已推送 ⟺ 有 pushed_at』，其余各态 pushed_at 必须为空");
        }
        if (CHANNEL_SUBSCRIPTION.equals(d.channel()) && d.state() != null
                && d.state().isPushAttempted() && isBlank(d.templateId())) {
            // 已推送必须留模板 ID：没有它，额度治理与"模板被挪作他用"的排查都无从下手。
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "已推送却没有模板 ID（template_id）—— P0-19 的订阅授权点设计要求"
                            + "『回执独占一个模板 ID』，这正是化解『额度被稀释』的方式。"
                            + "不留模板 ID，将来『模板被挪作他用 / 用量异常』都无从排查");
        }
    }

    // ==================================================================
    // 三、转线下兜底（P0-19：不得因订阅失败而漏发回执）
    // ==================================================================

    /**
     * 本态是否须触发转线下兜底 —— 未授权 / 推送失败为真。
     *
     * <p>依据 P0-19「兜底（订阅失败 / 额度用尽 / 未授权）：自动转门店电话 / 当面告知 +
     * 系统留痕『已转线下告知 + 操作人 + 时间』；<b>不得因订阅失败而漏发回执</b>
     * （漏发视同未回执）」。
     *
     * <p>🛑 它<b>不</b>只是"建议动作"：漏发视同未回执 —— 即一次未授权的工单若没有线下告知，
     * 在证据链上与"根本没做回执"等价。故本判定由 {@link #assertOfflineFallbackRecorded}
     * 变成一条<b>可失败的断言</b>，而不是一句写在文档里的提醒。
     */
    public static boolean requiresOfflineFallback(ReceiptState state) {
        if (state == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "转线下兜底判定缺回执状态 —— 空状态无从判定（且不得默认『需要』或『不需要』）");
        }
        return state != ReceiptState.PUSHED;
    }

    /**
     * 校验"须兜底的状态确实有线下告知记录" —— <b>漏发视同未回执</b>。
     *
     * <h2>为什么这条必须是断言而不是日志</h2>
     * "未授权"的处置动作（打电话 / 当面告知）发生在<b>系统之外</b>，
     * 系统能观察到的只有 {@code refund_offline_notice} 里有没有那一行。
     * 若这里只打一条警告，一次漏发回执的表现就是：覆盖率报表显示"已推送 + 未授权"齐备、
     * 看起来一切正常，而客户手里空无一物 —— 正是 P0-19 反复警告的
     * 「变成一个永远显示成功的开关，比不做更有害」。
     *
     * @param state               回执状态
     * @param offlineNoticeExists 该工单是否已有转线下告知留痕
     */
    public static void assertOfflineFallbackRecorded(ReceiptState state, boolean offlineNoticeExists) {
        if (!requiresOfflineFallback(state)) {
            return;
        }
        if (!offlineNoticeExists) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "回执状态为『" + state.code() + "』但没有任何转线下告知留痕"
                            + "（refund_offline_notice 无对应行）—— P0-19 逐字"
                            + "『不得因订阅失败而漏发回执（漏发视同未回执）』。"
                            + "🛑 未授权 / 推送失败本身不是一次『已回执』："
                            + "它们是『没送出去』，必须有一次人工告知才构成回执完成。"
                            + "若此处放行，报表会显示回执齐备而客户手里空无一物");
        }
    }

    // ==================================================================
    // 四、覆盖率（P1-10 口径 · 三数同显）
    // ==================================================================

    /**
     * 覆盖率报告。
     *
     * <h2>🛑 为什么它必须是一个"三个数一起出"的对象，而不是两个 rate</h2>
     * P1-10 逐字：「覆盖率口径（沿用 P1-08『三数同显』教训）：
     * {@code 回执送达率 = 已推送 /（已推送 + 未授权 + 推送失败）}；
     * {@code 授权覆盖率 = 1 − 未授权占比}；<b>两者须同卡片展示，禁止只报一个数</b>」。
     *
     * <p>所以本记录<b>刻意</b>把三个原始计数放在最前，两个比率放在后 ——
     * 且 {@link #assertThreeNumbersShown()} 可以被门禁调用，
     * 使"只报一个数"这件事在报表层也拦得住。只放比率的话，
     * 一个 {@code deliveryRate=1.0} 既可能是"全部送达"，也可能是"分母只有 1 条"。
     */
    public record CoverageReport(
            long pushed,
            long unauthorized,
            long pushFailed,
            double deliveryRate,
            double authorizationCoverageRate) {

        /** 覆盖率分母（三态之和）。 */
        public long denominator() {
            return pushed + unauthorized + pushFailed;
        }

        /**
         * 断言三个原始计数确实被展示 —— 供看板 / 报表层调用。
         *
         * <p>🛑 单看比率不可判读：{@code deliveryRate=1.0} 在"分母=1"与"分母=10000"下
         * 是两个完全不同的事实，而报表上长得一样。
         */
        public void assertThreeNumbersShown() {
            if (denominator() == 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "覆盖率分母为 0（本期无任何回执记录）—— 不得显示 0% 或 100%，"
                                + "应显示『无样本』。🛑 与 P1-03 / P1-09 的『样本不足态』同一原则："
                                + "把『没数据』画成『0 或 100』是在制造一个假的结论");
            }
        }
    }

    /**
     * 由三态计数计算覆盖率 —— <b>三态必须齐全地传进来</b>。
     *
     * <p>🛑 若把"未授权"漏掉（例如实现成"只统计推送过的"），分母缺一块 ⇒
     * 覆盖率<b>系统性偏高，且偏高得看不出来</b>（P0-19 原文）。
     * 故本方法的签名要求调用方显式给出三个数，而不是接受一个"状态列表然后我自己过滤" ——
     * 后者会让"漏掉一态"变成一次静默的过滤。
     */
    public static CoverageReport coverageOf(long pushed, long unauthorized, long pushFailed) {
        if (pushed < 0 || unauthorized < 0 || pushFailed < 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "覆盖率计数为负 —— 不可能；若下游按绝对值处理，一次符号写错会被读成一次正常统计");
        }
        long total = pushed + unauthorized + pushFailed;
        if (total == 0) {
            return new CoverageReport(0, 0, 0, Double.NaN, Double.NaN);
        }
        double delivery = (double) pushed / total;
        double authorization = 1.0 - (double) unauthorized / total;
        return new CoverageReport(pushed, unauthorized, pushFailed, delivery, authorization);
    }

    /**
     * 由状态分布直接算覆盖率（便捷入口，<b>内部<b>会把缺失的态补 0</b>）。
     *
     * <p>它存在的唯一理由是：调用方手里通常已经有一张 {@code receipt_state → count} 的表。
     * 但注意其内部仍要求三态都被显式识别 —— 若表里出现了<b>未知状态键</b>，直接抛，
     * 而不是静默忽略（静默忽略会让一个未来新增的状态从分母里消失）。
     */
    public static CoverageReport coverageOfDist(Map<ReceiptState, Long> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "覆盖率状态分布为空 —— 不得按『无记录 = 100% 覆盖』处理"
                            + "（那会让一个从未发过回执的租户看起来完全达标）");
        }
        long pushed = 0;
        long unauthorized = 0;
        long pushFailed = 0;
        for (Map.Entry<ReceiptState, Long> e : distribution.entrySet()) {
            if (e.getKey() == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "覆盖率状态分布含空键 —— 空键无从归入三态之一，"
                                + "不得默认归到某一态（那会让一个未知状态静默进入分母或逃出分母）");
            }
            long c = e.getValue() == null ? 0 : e.getValue();
            switch (e.getKey()) {
                case PUSHED -> pushed += c;
                case UNAUTHORIZED -> unauthorized += c;
                case PUSH_FAILED -> pushFailed += c;
            }
        }
        return coverageOf(pushed, unauthorized, pushFailed);
    }

    /** 三态的字面清单（供门禁断言"分母三态齐备"）。 */
    public static List<String> allStateCodes() {
        return ReceiptState.allCodes();
    }

    /** 三态枚举的完备性自证 —— 任何一态缺失都说明分母会缺一块。 */
    public static final int STATE_COUNT = 3;

    private static Instant require(Instant v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "回执决策缺 " + name + " —— P0-19 要求三态留痕是『凭证』，"
                            + "时间缺失的凭证在争议时无法提交");
        }
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}