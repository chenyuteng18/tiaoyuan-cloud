package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Duration;
import java.time.Instant;

/**
 * P0-19 <b>代录纪律</b> —— 「最早且可核实」的 {@code requested_at} 归一、24h 延迟判定、超时升级链。
 *
 * <h2>它守的是什么</h2>
 * 退款入口从客户端撤走之后（§2.2），我们<b>失去了"客户何时提出退款"的自动时间戳</b>，
 * 退款诉求的观测权归零 —— 总部再也拿不到"哪家店在拖、拖了多久"的自动信号。
 * P0-19 的推动力全部由业务方 2026-09-16 原话「客户只会和调理师说，然后调理师告诉经络师」
 * 而来：<b>客户的诉求本来就不经过系统，只能靠门店代录。</b>
 *
 * <p>而代录是<b>门店自己做的动作</b> —— 它既是唯一的证据来源，
 * 也是被考核的对象。这个结构性矛盾（自证 + 被考核）正是本类全部断言的由来：
 * 只要有一个环节允许"时间戳可以随手改"，整条链上的三个观测点就同时失效，
 * 而门店<b>不需要违反任何一条规则</b>，只需按规则正常操作。
 *
 * <h2>🛑 三字段拆分：本类最重要的一条，不能被"简化"</h2>
 * <pre>
 *   requested_at         最早且可核实 → 24h 计时【唯一】起点
 *   requested_at_source  来源标注     → 无标注 = 不可核实 → 不得进计时
 *   requested_at_claimed 客户主张     → 仅留存，【永不】进 24h 计时、不进任何超时判定
 * </pre>
 * PRD 逐字给出了"为何必须是三个字段"的理由，两侧各有一个反面：
 * <ul>
 *   <li>若把<b>不可核实的主张</b>写进 {@code requested_at} —— 我们就是在
 *       <b>自证「时间戳可以随手改」</b>，事后反被质疑；</li>
 *   <li>若<b>只留"最早"</b> —— 则「门店未记录、客户却能自证更早」的情形
 *       <b>逃掉责任</b>，客户拿着一张截图也无处安放。</li>
 * </ul>
 * 故 PRD 的原话是「<b>「最早」必须锁在「可核实」集合内，主张另行留档</b>」。
 * 本类把这句话实现成：可核实集合 = {客户自证（<b>须附凭据</b>）、调理师转交}，
 * <b>取较早者</b>；主张只留档。
 *
 * <h2>🛑 「可核实」为什么恰好是这两个来源</h2>
 * 「可核实」不是"来自客户就算"，而是<b>有第三方或系统可交叉验证</b>：
 * <table border="1">
 *   <caption>可核实集合的构成与理由</caption>
 *   <tr><th>来源</th><th>可核实性来自什么</th><th>证据要求</th></tr>
 *   <tr><td>客户自证</td><td>截图 / 通话记录 —— 是<b>客户手里</b>的物证，
 *       且可由客户在争议时提交（中山中院 2026-04 判词：凭证须可提交）</td>
 *       <td><b>必附</b>凭据引用字段</td></tr>
 *   <tr><td>调理师转交</td><td>转交待办是<b>系统内的业务实体</b>
 *       （落 {@code created_by} + {@code created_at}），时间是系统写的、不是人说的</td>
 *       <td>指向转交实体</td></tr>
 *   <tr><td>经络师受理</td><td>❌ <b>不可核实</b> —— 它就是被考核的那只手的自述时间。
 *       它是"当前最早已知"（兜底），但<b>不进"取较早"的比较域</b></td>
 *       <td>—</td></tr>
 * </table>
 * 这不影响 24h 计时：无论来源如何，<b>计时起点一律是归一后的 {@code requested_at}</b>。
 * 「经络师受理」只是没有更早的可核实来源时的兜底取值 —— 它必须存在，
 * 否则"客户无凭据、调理师也没转交"的情形会算不出延迟。
 *
 * <h2>⚠️ 本类只做"本域可独立判定"的部分</h2>
 * 转交段独立计时（C1-3）需要 {@link RefundConfigGap#HANDOFF_ACCEPT_WINDOW_HOURS}，
 * 升级链第二跳需要 {@link RefundConfigGap#RECORDING_ESCALATION_HQ_THRESHOLD_HOURS} ——
 * 两者在 config 里<b>都还没有值</b>。本类不发明它们：
 * {@link #secondHopFor} 恒抛，{@link #secondHopEvaluable} 恒 false，
 * 而 {@link #handoffWindowConfigured()} 把"这一段还判不了"变成可断言的事实。
 * 目的是让缺口的阻塞后果<b>在代码里可见</b>，而不是靠一份文档里的 TODO。
 */
public final class RefundRecordingPolicy {

    private final RefundPolicy policy;

    private RefundRecordingPolicy(RefundPolicy policy) {
        this.policy = policy;
    }

    /**
     * 从已归一化的退款域口径构造。
     *
     * <p>🛑 传 null 即抛，而不是回落 24：24h 窗口来自 {@code #10}，
     * 它是"需求本体"，不是一个可以在缺配置时被默认的常数。
     */
    public static RefundRecordingPolicy of(RefundPolicy policy) {
        if (policy == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "代录纪律需要退款域口径（config #10）—— 不得在口径缺失时按默认 24h 窗口接单："
                            + "窗口值同时是客户回执文案的时间锚点，回落默认会让文案承诺与系统判定分叉");
        }
        return new RefundRecordingPolicy(policy);
    }

    /** 本域的 24h 代录窗口（来自 config {@code #10}，构造期已断言恰为 24）。 */
    public int recordWindowHours() {
        return policy.deputyEntry().recordWithinHours();
    }

    // ==================================================================
    // 一、requested_at 归一（「最早且可核实」）
    // ==================================================================

    /**
     * {@code requested_at} 的归一结果。
     *
     * @param requestedAt    计时<b>唯一</b>起点（最早且可核实；无更早可核实来源时为受理时间）
     * @param source         来源标注（必须落库，见 V6 列注释）
     * @param claimedRetained 客户主张的时间（仅留存，<b>永不</b>参与计时）
     * @param evidenceRef    凭据引用（客户自证时必非空）
     */
    public record RequestedAtResolution(
            Instant requestedAt,
            RequestedAtSource source,
            Instant claimedRetained,
            String evidenceRef) {

        /**
         * <b>无起点</b>的归一结果（三字段全空）—— 供"本工单不存在『客户何时提出』这一问"的入口使用。
         *
         * <h2>🛑 它为什么必须是一个显式工厂，而不是让调用方自己 new 四个 null</h2>
         * 唯一的使用者是入口 B（首周期双不达标 → 不经挽留主动终止）。入口 B 由系统
         * <b>按公式自动触发</b>，客户从未"提出过诉求"，故 {@code requested_at} 对它
         * 不是一个"暂时取不到"的值，而是一个<b>结构上不适用</b>的值。
         *
         * <p>若让入口 B 也走 {@link RefundRecordingPolicy#resolveRequestedAt} 的兜底，
         * 它会拿到 {@code source=MERIDIAN_ACCEPTED} 与一个由受理时刻算出的延迟 ——
         * 于是异常名单里会出现一批<b>"永远超时、却没有可比起点"</b>的单子：
         * 它们的 24h 从一个与客户诉求无关的时刻起算，而那条纪律对它们本不成立。
         * 这个工厂把"不适用"写成一个有名字的取值，使调用方不必自己拼四个 null
         * （拼错一个就会造出"有起点无来源"这类不可落库的组合）。
         */
        public static RequestedAtResolution none() {
            return new RequestedAtResolution(null, null, null, null);
        }

        /**
         * 起点是否成立（{@code requested_at} 与 {@code requested_at_source} 同时有值）。
         *
         * <p>与 {@link RefundWorkOrderRow#hasResolvedRequestedAt()} 同口径 ——
         * 两处都要求"成对"，因为"有起点无来源"这一组合在库层被明确拒绝
         * （V6 列注释：无来源标注的 requested_at 视为不可核实）。
         */
        public boolean isResolved() {
            return requestedAt != null && source != null;
        }

        /** 主张是否存在（存在就必须落库，但不得被当作起点）。 */
        public boolean hasClaimed() {
            return claimedRetained != null;
        }

        /**
         * 主张是否<b>早于</b>归一后的起点 —— 即"客户说的比我们能核实的更早"。
         *
         * <p>这个标志存在的意义是<b>可观测性</b>，不是改判：出现它说明
         * 要么客户手里有更早的凭据（应由门店补充凭据引用后重算），
         * 要么客户记错了。两种情形都该被看见 —— 这正是 PRD 说的
         * 「只留"最早"会让门店未记录、客户却能自证更早的情形逃掉责任」的处置入口。
         * <p>🛑 但它<b>不得</b>被用来放宽 24h 计时：起点始终是 {@code requestedAt}。
         */
        public boolean claimedEarlierThanResolution() {
            return claimedRetained != null && requestedAt != null
                    && claimedRetained.isBefore(requestedAt);
        }
    }

    /**
     * 归一 {@code requested_at} —— 「最早且可核实」，主张另行留档。
     *
     * <p><b>可核实集合</b> = {客户自证（须附凭据）、调理师转交}，<b>取较早者</b>；
     * 两者都缺 → 兜底取经络师受理时间（{@link RequestedAtSource#MERIDIAN_ACCEPTED}）。
     *
     * <p>🛑 {@code claimed} <b>不参与</b>这里的任何比较 —— 这是本方法的核心纪律。
     * 它进出的唯一动作是被原样放进 {@link RequestedAtResolution#claimedRetained()}。
     * 把 claimed 混进比较域（哪怕只是"作为候选取较小时也带上它"）就等于
     * 让一个不可核实的自述获得了改写计时起点的能力。
     *
     * @param claimed         客户主张的时间（可空）
     * @param customerProven  客户可自证的时间（可空；非空则 <b>必须</b>给 evidenceRef）
     * @param evidenceRef     凭据引用（截图 / 通话记录的标识）
     * @param therapistHandoff 调理师转交待办的提交时间（可空）
     * @param meridianAccepted 经络师受理时间（可空；全空时作为兜底起点）
     */
    public RequestedAtResolution resolveRequestedAt(
            Instant claimed,
            Instant customerProven,
            String evidenceRef,
            Instant therapistHandoff,
            Instant meridianAccepted) {

        // 客户自证必须附凭据 —— 否则"客户自证"与"客户主张"就是同一个东西，
        // 而两者的差别（能不能核实）正是本字段拆分存在的全部理由。
        if (customerProven != null && isBlank(evidenceRef)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "requested_at_source=客户自证 必须附凭据引用（requested_at_source_ref）—— "
                            + "P0-19 C1-2：可核实集合里的『客户可自证的时间』逐字要求"
                            + "『截图 / 通话记录，须附凭据引用字段』。"
                            + "🛑 不得因『客户口头说的更早』而放宽：无凭据的自述时间"
                            + "与 claimed 是同一件事，应走 requested_at_claimed 留档，"
                            + "而不是进入可核实集合");
        }

        Instant earliestProven = null;
        RequestedAtSource source;

        boolean hasProven = customerProven != null;
        boolean hasHandoff = therapistHandoff != null;

        if (hasProven && hasHandoff) {
            // 取较早者。相等时取「客户自证」—— 同一时刻下它记录了更原始的来源
            // （客户是诉求的提出者，调理师转交是对诉求的传递），
            // 且这个选择不影响计时（两者是同一 Instant）。
            if (customerProven.isAfter(therapistHandoff)) {
                earliestProven = therapistHandoff;
                source = RequestedAtSource.THERAPIST_HANDOFF;
            } else {
                earliestProven = customerProven;
                source = RequestedAtSource.CUSTOMER_PROVEN;
            }
        } else if (hasProven) {
            earliestProven = customerProven;
            source = RequestedAtSource.CUSTOMER_PROVEN;
        } else if (hasHandoff) {
            earliestProven = therapistHandoff;
            source = RequestedAtSource.THERAPIST_HANDOFF;
        } else {
            // 无可核实来源 → 兜底取受理时间，并如实标注它不可核实。
            // 🛑 兜底不等于"合规"：source=MERIDIAN_ACCEPTED 会让
            //    「能否被纳入可核实比较域」为 false，从而在稽核信号里可被识别出来。
            if (meridianAccepted == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "requested_at 无法归一：既无可核实来源（客户自证 / 调理师转交），也无受理时间。"
                                + "P0-19 C1-2：24h 一律从 requested_at 起算 —— "
                                + "起点算不出来就不得接单，绝不用 claimed（客户主张）顶上（它永不进 24h 计时）");
            }
            earliestProven = meridianAccepted;
            source = RequestedAtSource.MERIDIAN_ACCEPTED;
        }

        return new RequestedAtResolution(earliestProven, source, claimed, evidenceRef);
    }

    // ==================================================================
    // 二、延迟判定与升级链
    // ==================================================================

    /**
     * 代录延迟的判定结果（与 V5 的 {@code refund.recording_delay_h} 列对应）。
     *
     * @param requestedAt  计时起点
     * @param recordedAt   代录时间
     * @param delayHours   延迟小时数（小数，便于稽核看到"23.5h"与"24.5h"的差别）
     * @param withinWindow 是否在 24h 窗口内
     * @param escalation   升级落点（仅"已配置部分"，见 {@link #secondHopEvaluable()}）
     */
    public record RecordingDelay(
            Instant requestedAt,
            Instant recordedAt,
            double delayHours,
            boolean withinWindow,
            EscalationLevel escalation) {
    }

    /**
     * 超时自动升级链的落点（P0-19）。
     *
     * <pre>
     *   WITHIN_WINDOW              ≤24h    —— 合规。仍进异常名单供稽核观察，但不升级
     *   STORE_ADMIN_AND_SUPERVISOR  >24h   —— 第一跳：推门店负责人 + 区域督导
     *   HEADQUARTERS_OPS            >48h   —— 第二跳：总部运营介入
     * </pre>
     *
     * <p>⚠️ 第一跳<b>含区域督导</b>，而督导按 §2.2 是「可见、<b>不参与审批</b>」——
     * 这一跳之所以成立，靠的正是"可见"而非"可审批"。若有人把督导的可见性收掉，
     * 这一跳会跳给一个看不见名单的人。故 {@link #escalationAudience()} 把
     * 跳给的受众显式列出来，供可见性矩阵交叉验证。
     */
    public enum EscalationLevel {

        /** 窗口内（合规）。 */
        WITHIN_WINDOW("窗口内"),

        /** 第一跳：门店负责人 + 区域督导。 */
        STORE_ADMIN_AND_SUPERVISOR("第一跳：门店负责人 + 区域督导"),

        /** 第二跳：总部运营介入。 */
        HEADQUARTERS_OPS("第二跳：总部运营介入");

        private final String label;

        EscalationLevel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 本跳是否要求区域督导可见（第一跳含督导 —— 督导只需可见、不需可审批）。 */
        public boolean requiresSupervisorVisibility() {
            return this == STORE_ADMIN_AND_SUPERVISOR;
        }

        /** 本跳对应的角色级受众（供可见性矩阵交叉验证）。 */
        public java.util.List<RefundAudienceRole> escalationAudience() {
            return switch (this) {
                case WITHIN_WINDOW -> java.util.List.of();
                case STORE_ADMIN_AND_SUPERVISOR -> java.util.List.of(
                        RefundAudienceRole.STORE_ADMIN, RefundAudienceRole.AREA_SUPERVISOR);
                case HEADQUARTERS_OPS -> java.util.List.of(RefundAudienceRole.HEADQUARTERS_OPS);
            };
        }
    }

    /**
     * 判定代录延迟 —— <b>只覆盖升级链里已配置的部分</b>。
     *
     * <p>具体是：≤24h 判为合规，>24h 判为第一跳。第二跳（>48h → 总部）
     * 需要 {@link RefundConfigGap#RECORDING_ESCALATION_HQ_THRESHOLD_HOURS}，
     * 该阈值尚未配置，故本方法返回的 {@code escalation} 最高只到第一跳 ——
     * 这一点由 {@link #secondHopEvaluable()} 显式声明为 false，而不是静默按 48 处理。
     *
     * <p>🛑 边界取"含 24"（{@code delayHours <= 24} 即合规）。理由：
     * PRD 写的是「24 <b>小时内</b>把诉求录入」，而回执文案对客户的承诺是
     * 「老师会在 <b>24 小时内</b>与您联系」（P1-10 模板 A）—— 第 24 小时整
     * 仍在文案承诺之内。两侧同口径是 P1-10 口径①「客户侧与系统侧说法一致」的要求；
     * 若系统判第 24 小时整为超时，客户拿着"24 小时内"的文案却被告知门店超时，
     * 那条文案就从承诺变成了陷阱。
     */
    public RecordingDelay evaluateDelay(Instant requestedAt, Instant recordedAt) {
        if (requestedAt == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "requested_at 为空 —— 24h 计时无起点。P0-19 C1-2：24h 一律从 requested_at 起算；"
                            + "缺失时应先归一（可核实集合取较早），而不是跳过计时或改用 claimed");
        }
        if (recordedAt == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "recorded_at 为空 —— 未代录即无延迟可言（V5 refund.recorded_at NOT NULL）");
        }

        long millis = Duration.between(requestedAt, recordedAt).toMillis();
        double hours = millis / 3_600_000.0;
        if (hours < 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "代录时间早于客户提出时间（requested_at=" + requestedAt
                            + "，recorded_at=" + recordedAt + "）—— 逻辑不可能。"
                            + "🛑 不要在这里取绝对值或交换参数：这个组合本身说明"
                            + "requested_at 被写成了某个尚未发生的时间，"
                            + "而它正是 24h 计时的起点 —— 静默修正会把一次时间戳写错变成一次合规的判定");
        }

        boolean within = hours <= recordWindowHours();
        EscalationLevel level = within
                ? EscalationLevel.WITHIN_WINDOW
                : EscalationLevel.STORE_ADMIN_AND_SUPERVISOR;
        return new RecordingDelay(requestedAt, recordedAt, hours, within, level);
    }

    /**
     * 升级链第二跳（{@code >48h → 总部运营介入}）<b>是否已可判定</b>。
     *
     * <p>🛑 当前恒为 {@code false}：阈值 {@code >48h} 写在 P0-19 正文里，
     * 但 PRD §10 的可配置项清单<b>没有</b>给它编号 —— 它是一个
     * {@link RefundConfigGap}。本方法把这件事变成可断言的事实，
     * 而不是让 {@link #evaluateDelay} 静默把 48 硬编码进去。
     */
    public boolean secondHopEvaluable() {
        return false;
    }

    /** 第二跳的阈值缺口（供登记文档与门禁断言引用）。 */
    public RefundConfigGap secondHopGap() {
        return RefundConfigGap.RECORDING_ESCALATION_HQ_THRESHOLD_HOURS;
    }

    /**
     * 判定第二跳 —— <b>恒抛</b>，因为阈值是未配置的缺口。
     *
     * <p>它的存在是为了让"想判第二跳"这件事无法静默发生：调用方会立刻拿到
     * 缺口名、PRD 出处、建议值与"为何不建议直接用建议值"。
     * 若将来阈值配置到位，本方法应改为真正判定，并同步把
     * {@link #secondHopEvaluable()} 改成读配置的布尔值。
     */
    public EscalationLevel secondHopFor(RecordingDelay delay) {
        if (delay == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "第二跳判定需要延迟判定结果 —— 不得在延迟未判定时判升级");
        }
        // 走到这里必然抛（requireValue 恒抛）—— 这是设计意图，不是未完成。
        return EscalationLevel.values()[secondHopGap().requireValue()];
    }

    /** 转交段独立计时（C1-3）的窗口是否已配置 —— 当前恒 false。 */
    public boolean handoffWindowConfigured() {
        return false;
    }

    /** 转交段窗口缺口（供登记文档引用）。 */
    public RefundConfigGap handoffWindowGap() {
        return RefundConfigGap.HANDOFF_ACCEPT_WINDOW_HOURS;
    }

    // ==================================================================
    // 三、落库前的捆绑校验（防字段被单独写入 / 被合并）
    // ==================================================================

    /**
     * 三字段捆绑（落库前的最后一次自检）。
     *
     * @param requestedAt 归一后的起点
     * @param source      来源标注
     * @param claimed     客户主张（可空）
     * @param evidenceRef 凭据引用（客户自证时必填）
     * @param recordedAt  代录时间
     */
    public record RecordingBundle(
            Instant requestedAt,
            String source,
            Instant claimed,
            String evidenceRef,
            Instant recordedAt) {
    }

    /**
     * 校验三字段捆绑是否可落库 —— 每条规则都对应一次"简化"企图。
     *
     * <ol>
     *   <li><b>无 {@code requested_at} 却有 claimed</b> → 抛。这是最危险的一种写法：
     *       认为"客户说了时间就够了"。后果是 24h 计时要么被跳过（等于取消纪律），
     *       要么被人拿 claimed 顶上（等于让不可核实的主张当起点）。
     *       两者都让 P0-19 的核心约束消失，而形式上都有记录。</li>
     *   <li><b>有 {@code requested_at} 却无来源标注</b> → 抛。依据 P0-19 C1-2
     *       「保留 requested_at_source 标注来源」+ V6 列注释
     *       「无来源标注的 requested_at 视为不可核实，不得进 24h 计时」。
     *       🛑 若只校验"有没有时间"而不校验"有没有来源"，
     *       则拆分三字段的收益可以靠"时间照填、来源不填"整段抹掉。</li>
     *   <li><b>来源=客户自证却无凭据</b> → 抛（可核实性不成立）。</li>
     *   <li><b>{@code requested_at} 晚于 {@code recordedAt}</b> → 抛（不可能的组合，
     *       且它会让延迟为负；取绝对值会把写错变成合规）。</li>
     *   <li><b>claimed 与 requested_at 被写成同一个来源</b>的机械兜底：
     *       当 {@code requested_at} 与 {@code claimed} 恰好相等且来源标注缺失时，
     *       无法区分"客户主张恰好准确"与"claimed 被顶上了" —— 故仍按第 2 条抛，
     *       要求来源标注把这件事说清楚。</li>
     * </ol>
     */
    public void assertBundleCanBePersisted(RecordingBundle b) {
        if (b == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "代录三字段捆绑为空，不得落库");
        }
        boolean hasRequested = b.requestedAt() != null;
        boolean hasSource = !isBlank(b.source());

        if (!hasRequested && b.claimed() != null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "只有客户主张（requested_at_claimed）而无 requested_at —— 不得落库。"
                            + "P0-19：24h 一律从 requested_at 起算，而 claimed『仅留存、"
                            + "永不进 24h 计时、不进任何超时判定』。"
                            + "这种组合要么让计时被跳过（等于取消纪律），要么诱导后来者"
                            + "拿 claimed 顶上（等于让不可核实的主张成为起点）—— 两种情况都必须拒绝");
        }
        if (hasRequested && !hasSource) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "有 requested_at 但缺 requested_at_source（来源标注）—— 不得落库。"
                            + "P0-19 C1-2 要求保留来源标注；V6 列注释逐字写着"
                            + "『无来源标注的 requested_at 视为不可核实，不得进 24h 计时』。"
                            + "🛑 若只校验『有没有时间』而不校验『有没有来源』，"
                            + "三字段拆分的全部收益可以靠『时间照填、来源不填』一次抹掉");
        }
        if (hasSource) {
            RequestedAtSource parsed = RequestedAtSource.parse(b.source());
            if (parsed.requiresEvidenceRef() && isBlank(b.evidenceRef())) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "来源标注为『客户自证』但缺凭据引用（requested_at_source_ref）—— 不得落库。"
                                + "P0-19 C1-2：客户可自证的时间须附凭据引用字段；"
                                + "无凭据的自述时间应走 requested_at_claimed，而不是占据可核实位置");
            }
        }
        if (hasRequested && b.recordedAt() != null && b.requestedAt().isAfter(b.recordedAt())) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "requested_at 晚于 recorded_at（客户提出时间晚于代录时间）—— 不得落库。"
                            + "该组合会让延迟为负；若下游取绝对值，一次时间戳写错"
                            + "就会变成一次合规的判定（这是本类最不想看到的静默修正）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}