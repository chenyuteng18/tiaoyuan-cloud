package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;

/**
 * P0-14 <b>挽留与审批口径</b> —— 挽留的必要性（两处豁免）、结局一致性、审批落点（出口集中）。
 *
 * <h2>它守的是什么</h2>
 * 「入口放权 + 出口集中」是退款权限的两半，而这两半<b>不是同一个方向上的两个刻度</b>：
 * 入口放权要把决定权<b>推下去</b>（门店当场受理、当场出挽留方案），
 * 出口集中要把决定权<b>收上来</b>（终止与打款须总部审批）。
 * 实现时最容易发生的错误，是把它们当成一个"松紧度"来调 ——
 * 于是要么"放开一点"把终止也放给门店（出口失守），
 * 要么"收紧一点"把挽留方案也收上去（门店在客户面前开不了口，Top1 痛点复发）。
 *
 * <p>故本类不提供任何"总松紧度"，只提供<b>逐条路径的落点</b>，
 * 且每条落点都能钉到 PRD 的原文句子。
 *
 * <h2>🛑 三条不依赖缺失配置的确定结论（本类优先给足）</h2>
 * <ol>
 *   <li><b>客户拒绝挽留 → 必上收总部</b>。P0-14 第二层逐字：
 *       「超阈<b>或客户拒挽留</b>才进总部审批」—— 后半句是一个<b>布尔条件</b>，
 *       不需要任何阈值即可判定。</li>
 *   <li><b>首周期主动终止 → 全额免审批</b>。P0-14 第三层 + {@code #10 entries[B].mode=direct}。
 *       这是"出口集中"的<b>唯一例外</b>，且它是被显式写出来的例外 ——
 *       意味着其余终止路径<b>都</b>要审批。</li>
 *   <li><b>健康风险事件类不走挽留</b>。P0-14 末句。它豁免的是<b>挽留</b>，
 *       <b>不是</b>审批 —— 把两处豁免混为一谈，会让安全事件绕过出口集中。</li>
 * </ol>
 *
 * <h2>🛑 一条判不了的：让步金额是否超阈</h2>
 * 「阈内门店自决 / 超阈上收总部」需要"阈"在哪 —— 而 config {@code #29}
 * 只声明了行为、没给分段边界（见 {@link RefundConfigGap#CONCESSION_SEGMENT_BOUNDARIES}）。
 * 本类不发明这个边界：{@link #concessionThresholdEvaluable()} 恒 {@code false}，
 * {@link #assertConcessionDecidableInStore} 恒抛。
 * 目的是让"这一段还判不了"成为<b>可断言的事实</b>，而不是靠一处没有出处的常量。
 */
public final class RetentionPolicy {

    private final RefundPolicy policy;

    private RetentionPolicy(RefundPolicy policy) {
        this.policy = policy;
    }

    /**
     * 从已归一化的退款域口径构造。
     *
     * <p>🛑 传 null 即抛：挽留是否必填、什么结局配什么审批，
     * 都由 {@code #10} 的双入口与 {@code #29} 的行为声明决定；
     * 口径缺失时按默认接单，等于让门店按一套没人确认过的规则办事。
     */
    public static RetentionPolicy of(RefundPolicy policy) {
        if (policy == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "挽留与审批口径需要退款域口径（config #10 / #29）—— 不得在口径缺失时按默认流程受理："
                            + "「是否须挽留」与「谁审批」是两条会被事后追责的路径，"
                            + "它们的默认值不存在一个安全的取值");
        }
        return new RetentionPolicy(policy);
    }

    // ==================================================================
    // 一、挽留的必要性（两处豁免，均不依赖缺失配置）
    // ==================================================================

    /**
     * 挽留的必要性判定。
     *
     * <pre>
     *   REQUIRED              入口 A + 非健康风险 → 必须先挽留，并留下结论
     *   EXEMPT_FIRST_CYCLE    入口 B（首周期双不达标）→ 不经挽留主动终止
     *   EXEMPT_HEALTH_RISK    健康风险事件类 → 不走挽留直接终止
     * </pre>
     *
     * <p>🛑 两处豁免的<b>理由完全不同</b>，故必须是两个成员而不是一个 {@code EXEMPT}：
     * <ul>
     *   <li>入口 B 的豁免是<b>逻辑矛盾</b> —— 我们已判定"服务对该客户无效"，
     *       此时再劝他继续接受该服务，是自相矛盾；</li>
     *   <li>健康风险的豁免是<b>安全优先</b> —— 客户刚说"做完反而更疼了"，
     *       挽留话术会把它变成为销售机会辩护。这不是流程瑕疵。</li>
     * </ul>
     * 合成一个 {@code EXEMPT} 会让报表与稽核看不出"豁免的到底是哪种"，
     * 而这两种豁免在事后复盘时是完全不同的事件。
     */
    public enum RetentionRequirement {

        /** 须先挽留（入口 A 的常态）。 */
        REQUIRED("须先挽留", false),

        /** 豁免：首周期双不达标（入口 B 不经挽留主动终止）。 */
        EXEMPT_FIRST_CYCLE("豁免 · 首周期双不达标（不经挽留主动终止）", true),

        /** 豁免：健康风险事件类（不走挽留直接终止）。 */
        EXEMPT_HEALTH_RISK("豁免 · 健康风险事件类（不走挽留直接终止）", true);

        private final String label;
        private final boolean exempt;

        RetentionRequirement(String label, boolean exempt) {
            this.label = label;
            this.exempt = exempt;
        }

        public String label() {
            return label;
        }

        /** 是否豁免挽留（两者都豁免，但理由不同）。 */
        public boolean isExempt() {
            return exempt;
        }

        /**
         * 是否属"健康风险"这一种豁免 —— 用于让稽核能区分两种豁免。
         *
         * <p>它与 {@link #isExempt()} 不同：{@code isExempt} 回答"要不要挽留"，
         * 本方法回答"是不是安全事件"。只有后者会触发安全侧的动作。
         */
        public boolean isHealthRiskExemption() {
            return this == EXEMPT_HEALTH_RISK;
        }
    }

    /**
     * 判定本工单是否必须挽留。
     *
     * <p><b>优先级：健康风险 &gt; 入口 B &gt; 须挽留。</b>
     * 健康风险排在入口 B 之前不是随手排的：一个"首周期双不达标"的工单
     * 同时又落在健康风险原因码上时，它首先是一次<b>安全事件</b> ——
     * 走直退虽然结局相同（终止），但豁免的<b>名义</b>不同，
     * 而这个名义决定它进不进安全侧的复盘，也决定稽核读到的是
     * "系统判定无效"还是"客户报告了不适"。
     *
     * @param entry  入口（入口 B 豁免挽留）；为空即抛 —— 入口缺失时无从判定
     * @param reason 原因码（健康风险豁免挽留）；可空（入口 B 由系统触发时可能不填）
     */
    public RetentionRequirement requirementOf(RefundEntry entry, RefundReasonCode reason) {
        if (entry == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "挽留必要性判定缺入口（entry）—— P0-14 双入口的挽留规则不同："
                            + "入口 A 须挽留、入口 B 不经挽留。缺入口即无从判定，"
                            + "不得默认按『须挽留』处理（那会给一个已判无效的工单配上挽留话术）");
        }
        if (reason != null && reason.isHealthRiskEvent()) {
            return RetentionRequirement.EXEMPT_HEALTH_RISK;
        }
        if (!entry.isStoreDeputyEntry()) {
            return RetentionRequirement.EXEMPT_FIRST_CYCLE;
        }
        return RetentionRequirement.REQUIRED;
    }

    // ==================================================================
    // 二、结局一致性（挽留结论 ↔ 工单结局）
    // ==================================================================

    /**
     * 校验"挽留结论"与"工单结局"是否自洽 —— 每条规则都对应一次可发生的矛盾。
     *
     * <ol>
     *   <li><b>须挽留却无结论</b> → 抛。P0-14 入口 A 的流程逐字是
     *       「受理 → 原因码必填 → <b>挽留</b> → 成功继续 / 失败终止」：
     *       没有结论的挽留不是"挽留过了"，而是流程没走完。
     *       🛑 若允许"只留沟通痕迹、不留结论"，挽留就退化成一条可有可无的备注，
     *       而它恰恰是"门店有没有认真处理"的唯一证据。</li>
     *   <li><b>豁免挽留却有挽留结论</b> → 抛。对入口 B（已判定服务无效）
     *       或健康风险（安全事件）做挽留，是两个不同的错误，
     *       但都表现为"出现了一条不该存在的挽留记录"。
     *       保留这条校验，是为了让那两笔工单在数据里保持干净 ——
     *       否则复盘时会看到"系统判定无效的工单仍被挽留"。</li>
     *   <li><b>挽留成功却结局=终止</b> → 抛。自相矛盾。</li>
     *   <li><b>挽留失败却结局=继续</b> → 抛。自相矛盾。</li>
     *   <li><b>终止却无原因码</b> → 抛。P0-14 逐字「<b>未经原因分析不可进入终止</b>；
     *       原因分类为结构化必填」。@code RefundReasonCode.parse} 挡的是"传了空字符串"，
     *       挡不住"调用方直接拿枚举做参数、根本没传"—— 故这里再挡一次。</li>
     * </ol>
     */
    public void assertOutcomeConsistent(RefundEntry entry,
                                        RefundReasonCode reason,
                                        RetentionResult result,
                                        RefundOutcome outcome) {
        if (entry == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "结局一致性校验缺入口（entry）—— 入口决定挽留是否必填，缺之无从校验");
        }
        if (outcome == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "结局一致性校验缺工单结局（outcome）—— PRD P0-14『四种结局全部强制归档』，"
                            + "结局不得留空（空结局 = 工单永久悬停，且归档动作没有触发点）");
        }

        RetentionRequirement req = requirementOf(entry, reason);

        if (outcome == RefundOutcome.TERMINATE && reason == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "进入退款终止但原因码为空 —— P0-14 逐字『未经原因分析不可进入终止；"
                            + "原因分类为结构化必填』。🛑 这条不只是表单校验："
                            + "原因码是后续『效果类 / 履约类』复盘与挽留质量评估的唯一分组依据，"
                            + "一个没有原因的终止在事后无法归因，也无法与同类工单比对");
        }

        if (req == RetentionRequirement.REQUIRED) {
            if (result == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "入口 A 工单没有挽留结论（retention.result）—— P0-14 入口 A 流程为"
                                + "『受理 → 原因码必填 → 挽留 → 成功继续 / 失败终止』。"
                                + "🛑 『挽留过了但没留结论』不是挽留："
                                + "它是把一条决定后续走向的判断，留在了没有任何记录的地方");
            }
            if (result.isSuccess() && outcome != RefundOutcome.CONTINUE) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "挽留结论为『" + result.code() + "』（成功）却结局为『" + outcome.code()
                                + "』—— 自相矛盾：挽留成功的定义就是客户留在服务关系内，"
                                + "其结局只能是『继续』");
            }
            if (result.requiresTermination() && outcome != RefundOutcome.TERMINATE) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "挽留结论为『" + result.code() + "』（挽留失败）却结局为『" + outcome.code()
                                + "』—— 自相矛盾：挽留失败的定义就是进入退款终止。"
                                + "🛑 若允许这两种组合并存，报表上的『挽留成功率』会与"
                                + "『终止率』脱钩，而两者本该是一枚硬币的两面");
            }
        } else if (result != null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "本工单按判定应【" + req.label() + "】，却出现挽留结论『" + result.code()
                            + "』—— 该挽留记录不该存在。"
                            + (req.isHealthRiskExemption()
                                    ? "🛑 尤其注意：这是健康风险事件（客户报告不适），"
                                      + "对一次可能的安全事件做挽留，是把安全事件当成销售机会；"
                                      + "它豁免的只是挽留，终止与打款仍须走出口集中审批"
                                    : "🛑 入口 B 已由系统判定服务无效，"
                                      + "对一个『我们已判定无效』的客户做挽留，语义上自相矛盾"));
        }
    }

    // ==================================================================
    // 三、审批落点（出口集中，逐条路径）
    // ==================================================================

    /**
     * 审批落点。
     *
     * <pre>
     *   NO_APPROVAL              无需审批（挽留成功 → 继续服务，未动钱）
     *   ESCALATE_HEADQUARTERS    须总部审批（出口集中：终止 / 打款 / 客户拒挽留）
     *   FULL_EXEMPT_FIRST_CYCLE  全额免审批（首周期主动终止 —— 出口集中的唯一例外）
     * </pre>
     */
    public enum ApprovalTarget {

        /** 无需审批。 */
        NO_APPROVAL("无需审批"),

        /** 须总部审批（出口集中）。 */
        ESCALATE_HEADQUARTERS("须总部审批（出口集中）"),

        /** 全额免审批 —— 仅"首周期主动终止"一条路径。 */
        FULL_EXEMPT_FIRST_CYCLE("全额免审批（首周期主动终止）");

        private final String label;

        ApprovalTarget(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 是否需要总部出手（决定这条路径会不会挂起等批复）。 */
        public boolean requiresHeadquarters() {
            return this == ESCALATE_HEADQUARTERS;
        }

        /** 该路径是否免于总部审批（保留为显式方法，让"例外只有一条"可被断言）。 */
        public boolean isExemptFromHeadquarters() {
            return this == FULL_EXEMPT_FIRST_CYCLE;
        }
    }

    /**
     * 挽留方案的审批落点 —— <b>不依赖缺失分段边界的那一半</b>。
     *
     * <ul>
     *   <li>挽留成功 → {@link ApprovalTarget#NO_APPROVAL}：客户留在服务关系内，
     *       本路径没有金额变动，无须审批；</li>
     *   <li>挽留失败 → {@link ApprovalTarget#ESCALATE_HEADQUARTERS}：
     *       P0-14 第二层逐字「超阈<b>或客户拒挽留</b>才进总部审批」——
     *       后半句是布尔条件，<b>不需要阈值</b>即可判定。</li>
     * </ul>
     *
     * <p>🛑 本方法<b>只</b>回答"要不要上收"，<b>不</b>回答"让步多少钱超阈" ——
     * 后者见 {@link #assertConcessionDecidableInStore}。把两者合并成一个方法，
     * 会让"客户拒挽留"这条确定结论也被缺失的阈值拖住无法判定。
     */
    public ApprovalTarget retentionPlanApproval(RetentionResult result) {
        if (result == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "挽留方案审批落点需要挽留结论 —— 不得在挽留未出结论时先定审批路径");
        }
        return result.isSuccess() ? ApprovalTarget.NO_APPROVAL : ApprovalTarget.ESCALATE_HEADQUARTERS;
    }

    /**
     * 工单结局的审批落点 —— 出口集中的主判定。
     *
     * <ul>
     *   <li><b>入口 B + 终止</b> → {@link ApprovalTarget#FULL_EXEMPT_FIRST_CYCLE}。
     *       P0-14 第三层逐字「首周期主动终止 = 全额免审批」。
     *       理由不是"想省流程"：首周期双不达标是<b>系统按公式判定</b>的结果，
     *       若它还要人工批准，等于把已判定的结论重新交回给人 ——
     *       那正是 Top1 痛点（判定权在人手里）的复发通道，且金额最大的单子最易扯皮。</li>
     *   <li><b>其余终止</b> → {@link ApprovalTarget#ESCALATE_HEADQUARTERS}。
     *       P0-15 逐字「出口集中（终止与打款须总部审批，超阈值自动上收）」。
     *       ⚠️ <b>健康风险直终止也走这里</b>：PRD 只豁免了它的<b>挽留</b>，
     *       没有豁免它的审批。把两处豁免混成一件事，会让安全事件绕过出口集中。</li>
     *   <li><b>继续</b> → {@link ApprovalTarget#NO_APPROVAL}（未动钱）。</li>
     *   <li><b>归档</b> → 抛。归档是结案状态位、归档后只读（见 {@link RefundOutcome}），
     *       问"已结案工单的审批落点"是问错了对象 —— 而若静默返回某个值，
     *       会掩盖一次对已归档工单的写操作企图。</li>
     * </ul>
     */
    public ApprovalTarget outcomeApproval(RefundEntry entry, RefundOutcome outcome) {
        if (entry == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "结局审批落点缺入口（entry）—— 免审批的唯一例外是『首周期主动终止』，"
                            + "缺入口即无法识别该例外，会把一次免审批的终止误判成须审批");
        }
        if (outcome == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "结局审批落点缺工单结局（outcome）");
        }
        return switch (outcome) {
            case CONTINUE -> ApprovalTarget.NO_APPROVAL;
            case TERMINATE -> entry.isStoreDeputyEntry()
                    ? ApprovalTarget.ESCALATE_HEADQUARTERS
                    : ApprovalTarget.FULL_EXEMPT_FIRST_CYCLE;
            case ARCHIVED -> throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "已归档工单不得再问审批落点 —— 归档后只读（PRD P0-14『四种结局全部强制归档』）。"
                            + "走到这里说明有一次对已结案工单的写操作企图；"
                            + "静默返回某个审批目标会让它看起来像一次正常的流程推进");
        };
    }

    /** 让步审批的行为声明（config {@code #29}，只含行为、不含边界）。 */
    public RefundPolicy.ConcessionThreshold concessionBehavior() {
        return policy.concession();
    }

    /**
     * 让步金额的分段阈值<b>是否已可判定</b> —— 当前恒 {@code false}。
     *
     * <p>🛑 config {@code #29} 声明了 {@code basis}（按服务成本，非售价）、
     * {@code within_threshold}（门店自决）、{@code over_threshold}（上收总部），
     * <b>但没有任何字段给出分段边界</b>。本方法把这件事变成可断言的事实，
     * 而不是让某个调用方静默写一组常量。
     */
    public boolean concessionThresholdEvaluable() {
        return false;
    }

    /** 分段边界缺口（供登记文档与门禁断言引用）。 */
    public RefundConfigGap concessionThresholdGap() {
        return RefundConfigGap.CONCESSION_SEGMENT_BOUNDARIES;
    }

    /**
     * 判定"这笔让步金额能否由门店自决" —— <b>恒抛</b>，因为分段边界是配置缺口。
     *
     * <p>它的存在是为了让"想判阈内 / 超阈"这件事无法静默发生。
     * 若将来边界配置到位，本方法应改为真正判定（读 {@code #29} 的分段字段），
     * 并同步把 {@link #concessionThresholdEvaluable()} 改成读配置的布尔值。
     *
     * @param concessionAmount 让步金额（按 {@code consumed_service_cost} 口径核算）
     */
    public ApprovalTarget assertConcessionDecidableInStore(long concessionAmount) {
        if (concessionAmount < 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "让步金额为负（" + concessionAmount + "）—— 负让步不是让步，"
                            + "它意味着向客户增收费项；若下游按绝对值处理，"
                            + "一次符号写错会被读成一次正常的减免");
        }
        // 走到这里必然抛（requireValue 恒抛）—— 这是设计意图，不是未完成。
        return ApprovalTarget.values()[concessionThresholdGap().requireValue()];
    }

    // ==================================================================
    // 四、代录者不可审批（P0-19「R4b ④ 动机阀门」）
    // ==================================================================

    /**
     * 校验审批人<b>是否具备审批资格</b> —— 它同时承担两件<b>不同</b>的事：
     * <ol>
     *   <li><b>角色级</b>：该角色是否在审批白名单内（{@link RefundAudienceRole#approvesRefund()}）。
     *       这是"可见 ≠ 可审批"的唯一落点 —— 区域督导能看见工单，但不是审批人（§2.2）；</li>
     *   <li><b>人级</b>：本次审批人是否就是本工单的代录人（P0-19 R4b ④ 动机阀门）。</li>
     * </ol>
     *
     * <h2>🛑 为什么两件事必须同处一地</h2>
     * 反向验证（RV-S2-4-4）实测出的一条：服务层原先<b>另写了一份</b>白名单判断
     * （{@code approverTokenRoles().contains(...)}），与这里的角色分支是同一判定的
     * 第二份实现。把服务层那份关掉之后，⑤『区域督导过不了审批闸』<b>仍然红</b> ——
     * 说明真正拦下督导的是本方法，服务层那份的价值是零，代价是"改白名单要改两处"，
     * 而漏改的那一处不会报错、只会安静地按旧白名单放行。
     * 故服务层那份已删除，本方法成为【唯一落点】。
     *
     * <p>⚠️ 两件事的<b>性质</b>不同，故错误码也不同：角色不合格是 {@code VISIBILITY_DENIED(2001)}
     * （身份档位问题），同一人自批是 {@code BUSINESS_RULE_VIOLATED(5001)}（业务规则问题）。
     * 把两者归成同一个码，会让"督导本就不该出现在这里"与"这个人这次不能批"
     * 在排查时无法区分。
     *
     * <p>🛑 校验的是<b>人</b>（操作人 ID），不是角色：代录人可能是门店负责人，
     * 而门店负责人<b>本就有</b>审批权（§2.3）。两者不冲突 ——
     * 冲突的只是<b>同一张工单上</b>这两个身份重合。故"代录者不可审批"那一条
     * 只接受身份、不接受角色，避免有人用"经理本来就能审批"把它绕过去。
     *
     * @param deputyOperatorId 代录操作人 ID（P0-19「代录人全程留痕」）
     * @param approverId       本次审批的操作人 ID
     * @param approverRole     审批人角色 —— <b>参与判定</b>（白名单闸）；{@code null} 表示调用方
     *                         尚未解析出角色，此时跳过角色级判定
     */
    public void assertApproverIsNotDeputy(String deputyOperatorId,
                                          String approverId,
                                          RefundAudienceRole approverRole) {
        if (isBlank(deputyOperatorId) || isBlank(approverId)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "代录者不可审批的校验缺操作人身份（代录人=" + deputyOperatorId
                            + "，审批人=" + approverId + "）—— "
                            + "P0-19 逐字『代录人、代录时间、客户原话全程留痕、不可删除』："
                            + "身份缺失即无从比对。🛑 此处不得因『身份拿不到就放行』——"
                            + "那会让这条动机阀门在最需要它的时候（身份链断了）失效");
        }
        if (approverRole != null && !approverRole.approvesRefund()) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "角色『" + approverRole.label() + "』不属退款审批人集合 —— "
                            + "见 §2.2（区域督导『可见、不审批』）与 config #40");
        }
        if (deputyOperatorId.trim().equals(approverId.trim())) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "代录者不可审批：代录人与审批人为同一人（" + approverId + "）—— "
                            + "P0-19 / R4b ④（动机阀门）。"
                            + "🛑 这不是权限问题（该角色可能本就有审批权 §2.3），"
                            + "而是同一张工单上两个身份不得重合："
                            + "否则『原话不可编辑 / 延迟进异常名单 / 超时升级』"
                            + "三条可被同一人自建自批一次性绕过，且形式上全部合规");
        }
    }

    // ==================================================================
    // 五、归档收口（「四种结局全部强制归档」）
    // ==================================================================

    /**
     * 本结局是否仍需归档动作收口 —— P0-14「四种结局全部强制归档」的可执行形态。
     *
     * <p>🛑 「全部强制归档」若只写在验收条目里、不落到一个布尔判断上，
     * 最可能的失效方式是"工单停在『继续』或『终止』上永远不再推进" ——
     * 而这句话就退化成一句没有排期的话。本方法让"未结案"成为可查询的状态。
     */
    public boolean requiresArchive(RefundOutcome outcome) {
        if (outcome == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "归档收口判定缺工单结局（outcome）");
        }
        return outcome.isOpen();
    }

    /**
     * 校验当前状态<b>允许</b>归档（归档是幂等的：已归档再归档直接返回 false，不抛）。
     *
     * <p>为什么已归档不抛：归档是一个"收口"动作，可能被重试（网络重发、批处理重跑）。
     * 对一个已经收口的工单再收口一次，不产生任何状态变化 ——
     * 抛错会让重试变成一次需要人工介入的故障，而它本可以是幂等的。
     * 但<b>其它写操作</b>（改结局、加挽留）对已归档工单必须拒绝 —— 那是 409 的职责。
     *
     * @return 本次调用是否真的执行了归档（false = 已是归档态，无需动作）
     */
    public boolean archiveIfOpen(RefundOutcome current) {
        if (current == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "归档动作缺当前结局（outcome）");
        }
        return current.isOpen();
    }

    /**
     * 校验"对已归档工单的写操作"必须被拒 —— 供服务层在改结局 / 加挽留前调用。
     *
     * <p>它存在的理由是：{@link #archiveIfOpen} 让归档幂等，
     * 于是"已归档"这件事不再是一道硬墙 —— 必须另有一处明确拒绝其它写入，
     * 否则归档后的工单仍可被改。两者是配套的，缺一会让"归档后只读"落空。
     */
    public void assertWritable(RefundOutcome current) {
        if (current == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED, "写操作校验缺当前结局（outcome）");
        }
        if (current.isClosed()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "工单已归档（outcome=归档），归档后只读 —— 拒绝任何写操作。"
                            + "🛑 不得以『只改一点点』为由放开："
                            + "一旦允许改，『全部强制归档』就不再是一次收口，"
                            + "而只是一个会被人越过的状态位");
        }
    }

    /** 全部豁免挽留的判定（供报表与门禁使用）。 */
    public static List<RetentionRequirement> exemptRequirements() {
        return List.of(RetentionRequirement.EXEMPT_FIRST_CYCLE, RetentionRequirement.EXEMPT_HEALTH_RISK);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}