package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.RefundConfigGap;
import com.diaoyuanyun.dy.app.refund.domain.RefundEntry;
import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundReasonCode;
import com.diaoyuanyun.dy.app.refund.domain.RefundRecordingPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource;
import com.diaoyuanyun.dy.app.refund.domain.RetentionPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RetentionResult;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-2「代录纪律与挽留 / 审批口径」的<b>语义守卫</b>。
 *
 * <h2>与 S2-1 测试的分工</h2>
 * {@code RefundPolicyContractTest} 守的是"<b>口径的原始声明</b>是否被约束住"
 * （{@code #10} 的窗口 / 通路、{@code #38} 的公式、{@code #40} 的矩阵键）；
 * 本类守的是"<b>用口径做判断时</b>，判断本身是否可靠"——
 * {@code requested_at} 能不能被随手改、24h 边界在哪、升级链跳到谁、
 * 挽留能不能绕过、谁有资格审批。
 *
 * <h2>🛑 本类的两个核心手法</h2>
 * <ol>
 *   <li><b>变异体</b>：真实配置 → 复制 → 精确改一处 → 断言必抛。
 *       证明的是"当前生效的这条口径，若被这样改动会被拦下"，
 *       而不是"我构造了个假数据、它被拦下了"。</li>
 *   <li><b>把"判不了"变成可断言的事实</b>：三处配置缺口
 *       （{@code handoff_accept_window_hours} / {@code recording_escalation_hq_hours} /
 *       {@code #29} 分段边界）在代码里表现为
 *       {@code secondHopEvaluable()==false} + {@code requireValue()} 恒抛。
 *       故本类<b>断言它们确实抛</b> —— 若有人为了"让流程跑通"填了个默认值，
 *       那些断言会立刻变红。这是"缺口没有被静默填平"的唯一机械保证。</li>
 * </ol>
 */
class RefundDeputyDisciplineAndRetentionTest {

    private final RefundProfileSource source = new ConfigSeedRefundProfileSource();

    private RefundPolicy policy() {
        return RefundPolicy.fromRawConfig(source.raw());
    }

    private RefundRecordingPolicy recording() {
        return RefundRecordingPolicy.of(policy());
    }

    private RetentionPolicy retention() {
        return RetentionPolicy.of(policy());
    }

    private static final Instant T0 = Instant.parse("2026-09-24T02:00:00Z");

    // ==================================================================
    // 一、requested_at 归一（「最早且可核实」，主张另行留档）
    // ==================================================================

    @Nested
    @DisplayName("一、requested_at 归一：最早且可核实")
    class RequestedAtResolution {

        @Test
        @DisplayName("两个可核实来源都存在 ⇒ 取较早者（不理来源类型，只看时间）")
        void earliest_of_the_two_verifiable_sources_wins() {
            Instant proven = T0;
            Instant handoff = T0.plusSeconds(3600);

            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(null, proven, "WX-CHAT-SCREENSHOT-8841", handoff, null);

            assertEquals(proven, r.requestedAt(), "客户自证更早 ⇒ 起点应是客户自证");
            assertEquals(RequestedAtSource.CUSTOMER_PROVEN, r.source());
        }

        @Test
        @DisplayName("调理师转交更早 ⇒ 起点取转交（转交时间是系统写的，同样可核实）")
        void therapist_handoff_can_be_the_earlier_verifiable_source() {
            Instant handoff = T0;
            Instant proven = T0.plusSeconds(7200);

            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(null, proven, "PHONE-LOG-20260924", handoff, null);

            assertEquals(handoff, r.requestedAt(), "转交代办的 created_at 更早 ⇒ 起点应是转交时间");
            assertEquals(RequestedAtSource.THERAPIST_HANDOFF, r.source(),
                    "来源必须如实标注为『调理师转交』—— 它决定这条工单在稽核信号里如何被读");
        }

        @Test
        @DisplayName("同一时刻 ⇒ 取客户自证（更原始来源），且不影响计时")
        void tie_prefers_customer_proven_without_changing_the_clock() {
            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(null, T0, "REF-1", T0, null);

            assertEquals(T0, r.requestedAt());
            assertEquals(RequestedAtSource.CUSTOMER_PROVEN, r.source(),
                    "同一 Instant 下取客户自证：客户是诉求提出者，调理师转交是对诉求的传递；"
                            + "该选择不改变计时（同一时刻）");
        }

        @Test
        @DisplayName("只有调理师转交 ⇒ 用转交时间（PRD「客户无凭据时用调理师转交时间」）")
        void handoff_alone_is_used_when_customer_has_no_evidence() {
            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(null, null, null, T0, T0.plusSeconds(86400));

            assertEquals(T0, r.requestedAt(),
                    "客户无凭据时应取转交时间，而不是受理时间 —— 否则门店可让诉求在调理师处停留以重置计时");
            assertEquals(RequestedAtSource.THERAPIST_HANDOFF, r.source());
        }

        @Test
        @DisplayName("🛑 客户自证缺凭据引用 ⇒ 抛（无凭据的自述与 claimed 是同一件事）")
        void customer_proven_without_evidence_reference_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> recording().resolveRequestedAt(null, T0, "  ", null, null),
                    "『客户自证』定义里逐字含『须附凭据引用字段』—— 无凭据时它只是客户主张，"
                            + "应走 requested_at_claimed 留档，不得进入可核实集合");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("凭据引用"), "报错应点名凭据引用；实际: " + e.getMessage());
        }

        @Test
        @DisplayName("无可核实来源 ⇒ 兜底取受理时间，并如实标注『不可核实』")
        void meridian_accepted_is_the_fallback_and_marked_unverifiable() {
            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(null, null, null, null, T0);

            assertEquals(T0, r.requestedAt());
            assertEquals(RequestedAtSource.MERIDIAN_ACCEPTED, r.source(),
                    "兜底必须如实标注 —— 兜底不等于合规：它不进『取较早』的比较域，"
                            + "故在稽核信号里可被识别为『无可核实来源』");
            assertFalse(r.source().isVerifiableInEarlySet(),
                    "『经络师受理』是当前最早已知（兜底），但不得进入可核实比较域");
        }

        @Test
        @DisplayName("🛑 全空 ⇒ 抛（起点算不出来就不得接单，绝不用 claimed 顶上）")
        void all_empty_is_rejected_rather_than_defaulting_to_claimed() {
            BizException e = assertThrows(BizException.class,
                    () -> recording().resolveRequestedAt(T0, null, null, null, null),
                    "claimed 永不进 24h 计时 —— 起点算不出来时不得用客户主张顶上");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("claimed"), "报错应点名 claimed；实际: " + e.getMessage());
        }

        @Test
        @DisplayName("🛑 主张永不进比较域：claimed 最早也只被留档，起点不动")
        void claimed_never_widens_the_clock_even_when_earliest() {
            Instant claimed = T0;                      // 客户口头说的：最早
            Instant handoff = T0.plusSeconds(86400);   // 系统记录的转交：晚了 24h

            RefundRecordingPolicy.RequestedAtResolution r =
                    recording().resolveRequestedAt(claimed, null, null, handoff, null);

            assertEquals(handoff, r.requestedAt(),
                    "🛑 若 claimed 混进比较域（哪怕只作为候选），一个不可核实的自述就获得了"
                            + "改写计时起点的能力 —— 这正是 PRD『若把不可核实的主张写进 requested_at，"
                            + "我们就是在自证「时间戳可以随手改」』要防的事");
            assertEquals(claimed, r.claimedRetained(), "主张必须原样留档（不丢）");
            assertTrue(r.hasClaimed());
            assertTrue(r.claimedEarlierThanResolution(),
                    "『客户说的比我们能核实的更早』须可观测 —— 它是『门店未记录、客户却能自证更早』"
                            + "情形的处置入口（补充凭据引用后重算）");
            assertEquals(RequestedAtSource.THERAPIST_HANDOFF, r.source());
        }
    }

    // ==================================================================
    // 二、24h 延迟判定与升级链
    // ==================================================================

    @Nested
    @DisplayName("二、24h 延迟判定与升级链")
    class DelayAndEscalation {

        @Test
        @DisplayName("窗口值来自 config #10（构造期已断言恰为 24）")
        void window_comes_from_config() {
            assertEquals(24, recording().recordWindowHours());
        }

        @Test
        @DisplayName("边界：第 24 小时整仍在窗口内（与客户文案『24 小时内』同口径）")
        void exactly_24h_is_still_within_the_window() {
            RefundRecordingPolicy.RecordingDelay d =
                    recording().evaluateDelay(T0, T0.plusSeconds(24 * 3600));

            assertTrue(d.withinWindow(),
                    "🛑 边界取『含 24』：客户回执文案的承诺是『24 小时内会与您联系』，"
                            + "第 24 小时整仍在承诺之内。系统若判超时，客户拿着文案却被告知门店超时 —— "
                            + "那条文案就从承诺变成了陷阱（P1-10 口径①『客户侧与系统侧说法一致』）");
            assertEquals(RefundRecordingPolicy.EscalationLevel.WITHIN_WINDOW, d.escalation());
            assertEquals(24.0, d.delayHours(), 1e-9);
        }

        @Test
        @DisplayName("超窗口一秒 ⇒ 第一跳（推门店负责人 + 区域督导）")
        void one_second_over_the_window_escalates_to_first_hop() {
            RefundRecordingPolicy.RecordingDelay d =
                    recording().evaluateDelay(T0, T0.plusSeconds(24 * 3600 + 1));

            assertFalse(d.withinWindow());
            assertEquals(RefundRecordingPolicy.EscalationLevel.STORE_ADMIN_AND_SUPERVISOR, d.escalation());
        }

        @Test
        @DisplayName("🛑 第一跳受众含区域督导，且这一跳靠『可见』而非『可审批』成立")
        void first_hop_audience_includes_the_supervisor() {
            RefundRecordingPolicy.EscalationLevel hop =
                    RefundRecordingPolicy.EscalationLevel.STORE_ADMIN_AND_SUPERVISOR;

            assertEquals(List.of(RefundAudienceRole.STORE_ADMIN, RefundAudienceRole.AREA_SUPERVISOR),
                    hop.escalationAudience(),
                    "P0-19 逐字『>24h → 推门店负责人 + 区域督导』—— 督导按 §2.2 是可见但不审批，"
                            + "这一跳之所以成立靠的正是『可见』");
            assertTrue(hop.requiresSupervisorVisibility());
            assertFalse(RefundAudienceRole.AREA_SUPERVISOR.approvesRefund(),
                    "🛑 若有人把督导的可见性收掉，这一跳会跳给一个看不见名单的人；"
                            + "若有人顺手给督导加上审批权，§2.2 的『可见 ≠ 可审批』就此失守");
        }

        @Test
        @DisplayName("🛑 记录时间早于提出时间 ⇒ 抛（不取绝对值、不交换参数）")
        void negative_delay_is_rejected_instead_of_silently_absolved() {
            BizException e = assertThrows(BizException.class,
                    () -> recording().evaluateDelay(T0.plusSeconds(3600), T0),
                    "🛑 这个组合本身说明 requested_at 被写成了某个尚未发生的时间，"
                            + "而它正是 24h 计时的起点 —— 静默修正会把一次时间戳写错变成一次合规的判定");
            assertEquals(5001, e.getCode());
        }

        @Test
        @DisplayName("起点 / 代录时间缺失 ⇒ 各自抛（不得跳过计时）")
        void missing_endpoints_are_rejected() {
            assertThrows(BizException.class, () -> recording().evaluateDelay(null, T0));
            assertThrows(BizException.class, () -> recording().evaluateDelay(T0, null));
        }

        @Test
        @DisplayName("🛑 第二跳（>48h → 总部）当前判不了：evaluable=false，取值恒抛")
        void second_hop_is_not_yet_evaluable_and_says_so() {
            RefundRecordingPolicy p = recording();

            assertFalse(p.secondHopEvaluable(),
                    "🛑 阈值 >48h 写在 P0-19 正文里，但 PRD §10 清单没有给它编号 —— "
                            + "本方法必须如实返回 false，而不是让 evaluateDelay 静默按 48 处理");
            assertEquals(RefundConfigGap.RECORDING_ESCALATION_HQ_THRESHOLD_HOURS, p.secondHopGap());

            RefundRecordingPolicy.RecordingDelay over48 =
                    p.evaluateDelay(T0, T0.plusSeconds(50 * 3600));
            assertEquals(RefundRecordingPolicy.EscalationLevel.STORE_ADMIN_AND_SUPERVISOR,
                    over48.escalation(),
                    "在第二跳可判定之前，>48h 的工单落在第一跳 —— 这是已知的、被登记的覆盖不足，"
                            + "而不是一个假装完整的升级链");

            BizException e = assertThrows(BizException.class, () -> p.secondHopFor(over48),
                    "取值必须恒抛：若有人为了让『总部介入』跑通而填了默认 48，这条断言立刻变红");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("#27"),
                    "报错必须点名『为何不能复用 #27』（数值相同、语义不同）；实际: " + e.getMessage());
        }

        @Test
        @DisplayName("🛑 转交段窗口（C1-3 的 X）当前判不了：configured=false，缺口被登记")
        void handoff_window_is_not_yet_configured() {
            RefundRecordingPolicy p = recording();

            assertFalse(p.handoffWindowConfigured(),
                    "C1-3 的 X 小时在 PRD 原文标注『建议值 4h，需真实数据校准』—— "
                            + "即它还没有一个可信取值，不得回落成 4");
            assertEquals(RefundConfigGap.HANDOFF_ACCEPT_WINDOW_HOURS, p.handoffWindowGap());
        }
    }

    // ==================================================================
    // 三、落库前的三字段捆绑校验
    // ==================================================================

    @Nested
    @DisplayName("三、三字段捆绑（防字段被单独写入 / 被合并）")
    class BundleGuards {

        @Test
        @DisplayName("合法捆绑（来源=调理师转交）⇒ 通过")
        void valid_bundle_passes() {
            recording().assertBundleCanBePersisted(new RefundRecordingPolicy.RecordingBundle(
                    T0, RequestedAtSource.THERAPIST_HANDOFF.code(), null, null, T0.plusSeconds(3600)));
        }

        @Test
        @DisplayName("🛑 只有 claimed 而无 requested_at ⇒ 抛")
        void claimed_without_requested_at_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> recording().assertBundleCanBePersisted(
                            new RefundRecordingPolicy.RecordingBundle(
                                    null, null, T0, null, T0.plusSeconds(3600))),
                    "这种组合要么让计时被跳过（等于取消纪律），要么诱导后来者拿 claimed 顶上");
            assertEquals(5001, e.getCode());
        }

        @Test
        @DisplayName("🛑 有 requested_at 却无来源标注 ⇒ 抛（拆分三字段的收益靠这条守住）")
        void requested_at_without_source_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> recording().assertBundleCanBePersisted(
                            new RefundRecordingPolicy.RecordingBundle(
                                    T0, "  ", null, null, T0.plusSeconds(3600))),
                    "🛑 若只校验『有没有时间』而不校验『有没有来源』，"
                            + "三字段拆分的全部收益可以靠『时间照填、来源不填』一次抹掉");
            assertEquals(5001, e.getCode());
        }

        @Test
        @DisplayName("来源=客户自证却无凭据 ⇒ 抛")
        void customer_proven_source_without_evidence_is_rejected() {
            assertThrows(BizException.class,
                    () -> recording().assertBundleCanBePersisted(
                            new RefundRecordingPolicy.RecordingBundle(
                                    T0, RequestedAtSource.CUSTOMER_PROVEN.code(), null, null,
                                    T0.plusSeconds(3600))));
        }

        @Test
        @DisplayName("requested_at 晚于 recorded_at ⇒ 抛")
        void requested_at_after_recorded_at_is_rejected() {
            assertThrows(BizException.class,
                    () -> recording().assertBundleCanBePersisted(
                            new RefundRecordingPolicy.RecordingBundle(
                                    T0.plusSeconds(7200), RequestedAtSource.MERIDIAN_ACCEPTED.code(),
                                    null, null, T0)));
        }

        @Test
        @DisplayName("claimed 恰等于 requested_at 但来源缺失 ⇒ 仍抛（无法区分『主张恰好准确』与『claimed 被顶上』）")
        void claimed_equal_to_requested_at_without_source_still_rejected() {
            assertThrows(BizException.class,
                    () -> recording().assertBundleCanBePersisted(
                            new RefundRecordingPolicy.RecordingBundle(
                                    T0, null, T0, null, T0.plusSeconds(3600))),
                    "要求来源标注把这件事说清楚 —— 这正是三字段拆分存在的意义");
        }
    }

    // ==================================================================
    // 四、配置缺口登记册（含"条目在、值不在"的形态）
    // ==================================================================

    @Nested
    @DisplayName("四、配置缺口登记册")
    class GapRegister {

        @Test
        @DisplayName("缺口总数自证 = 3（增删都是一次需要表态的事件）")
        void gap_count_is_pinned() {
            assertEquals(RefundConfigGap.GAP_COUNT, RefundConfigGap.allGaps().size(),
                    "缺口集合发生增删时必须同步更新 GAP_COUNT —— 新增意味着又有一个数字要硬编码，"
                            + "消除意味着登记册与实现开始分叉，两者都该被看见");
            assertEquals(3, RefundConfigGap.GAP_COUNT);
        }

        @Test
        @DisplayName("🛑 每个缺口的取值恒抛，且消息点名 key / 出处 / 建议值")
        void every_gap_refuses_to_yield_a_value() {
            for (RefundConfigGap gap : RefundConfigGap.allGaps()) {
                BizException e = assertThrows(BizException.class, gap::requireValue,
                        "缺口 " + gap.name() + " 必须拒绝取值 —— 否则它就从『缺口登记册』"
                                + "退化成『伪装成配置的硬编码表』");
                assertEquals(5001, e.getCode());
                assertTrue(e.getMessage().contains(gap.configKey()), "应点名 configKey；实际: " + e.getMessage());
                assertTrue(e.getMessage().contains(gap.prdClause()), "应点名 PRD 出处；实际: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("两类形态可区分：2 条无编号（须先申请 §10 编号）+ 1 条已编号但值空缺（#29）")
        void gap_forms_are_distinguishable() {
            assertEquals(2, RefundConfigGap.unnumberedGaps().size(),
                    "无编号缺口 = handoff_accept_window_hours / recording_escalation_hq_hours");
            assertEquals(1, RefundConfigGap.numberedGapsWithMissingValue().size(),
                    "已编号但值空缺 = #29 的分段边界（这一形态最隐蔽：『#29 已登记』会让人以为缺口不存在）");

            RefundConfigGap numbered = RefundConfigGap.numberedGapsWithMissingValue().get(0);
            assertEquals(RefundConfigGap.CONCESSION_SEGMENT_BOUNDARIES, numbered);
            assertEquals(29, numbered.configNo().orElseThrow(),
                    "须指名它挂在哪个已冻结条目下 —— 补齐动作是往 #29 里补字段，不是申请新编号");
            assertTrue(RefundConfigGap.unnumberedGaps().stream()
                            .allMatch(g -> g.configNo().isEmpty()),
                    "形态 (a) 的 configNo 必须为空");
        }

        @Test
        @DisplayName("建议值与『无建议值』可区分：48 那条有、#29 分段那条没有")
        void advisory_values_are_distinguishable_from_absence() {
            assertEquals(4, RefundConfigGap.HANDOFF_ACCEPT_WINDOW_HOURS.advisoryValue().orElseThrow());
            assertEquals(48, RefundConfigGap.RECORDING_ESCALATION_HQ_THRESHOLD_HOURS
                    .advisoryValue().orElseThrow());
            assertTrue(RefundConfigGap.CONCESSION_SEGMENT_BOUNDARIES.advisoryValue().isEmpty(),
                    "🛑 PRD 未给建议值时须为空 —— 填成 0 或某个数会让登记册读起来像『已有候选值』，"
                            + "而实际上业务方还需要从零给出");

            BizException e = assertThrows(BizException.class,
                    RefundConfigGap.CONCESSION_SEGMENT_BOUNDARIES::requireValue);
            assertTrue(e.getMessage().contains("PRD 未给建议值"),
                    "报错应说明『连一条待校准的建议都没有』；实际: " + e.getMessage());
            assertTrue(e.getMessage().contains("#29"), "应点名 #29；实际: " + e.getMessage());
        }
    }

    // ==================================================================
    // 五、挽留必要性（两处豁免，理由不同）
    // ==================================================================

    @Nested
    @DisplayName("五、挽留必要性")
    class RetentionRequirement {

        @Test
        @DisplayName("入口 A + 非健康风险 ⇒ 须先挽留")
        void entry_a_requires_retention() {
            assertEquals(RetentionPolicy.RetentionRequirement.REQUIRED,
                    retention().requirementOf(RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE));
        }

        @Test
        @DisplayName("入口 B ⇒ 豁免（不经挽留主动终止）")
        void entry_b_is_exempt() {
            assertEquals(RetentionPolicy.RetentionRequirement.EXEMPT_FIRST_CYCLE,
                    retention().requirementOf(RefundEntry.B, null));
        }

        @Test
        @DisplayName("🛑 健康风险优先级高于入口 B：两处豁免的『名义』不同")
        void health_risk_outranks_entry_b() {
            RetentionPolicy p = retention();

            assertEquals(RetentionPolicy.RetentionRequirement.EXEMPT_HEALTH_RISK,
                    p.requirementOf(RefundEntry.A, RefundReasonCode.SYMPTOM_WORSENED));
            assertEquals(RetentionPolicy.RetentionRequirement.EXEMPT_HEALTH_RISK,
                    p.requirementOf(RefundEntry.B, RefundReasonCode.SYMPTOM_WORSENED),
                    "🛑 首周期双不达标 + 健康风险时，它首先是一次【安全事件】："
                            + "走直退虽结局相同，但豁免的名义决定它进不进安全侧复盘 —— "
                            + "稽核读到的应是『客户报告了不适』而不是『系统判定无效』");

            assertTrue(p.requirementOf(RefundEntry.A, RefundReasonCode.SYMPTOM_WORSENED)
                    .isHealthRiskExemption());
            assertFalse(p.requirementOf(RefundEntry.B, null).isHealthRiskExemption(),
                    "两种豁免必须可区分 —— 合成一个 EXEMPT 会让报表看不出豁免的是哪一种");
        }

        @Test
        @DisplayName("🛑 入口缺失 ⇒ 抛（不得默认按『须挽留』处理）")
        void missing_entry_is_rejected() {
            assertThrows(BizException.class, () -> retention().requirementOf(null, null),
                    "缺入口时默认『须挽留』，等于给一个已判无效的工单配上挽留话术");
        }

        @Test
        @DisplayName("两处豁免都在册、且都是豁免")
        void both_exemptions_are_registered() {
            List<RetentionPolicy.RetentionRequirement> exempts = RetentionPolicy.exemptRequirements();
            assertEquals(2, exempts.size());
            assertTrue(exempts.stream().allMatch(RetentionPolicy.RetentionRequirement::isExempt));
            assertTrue(exempts.stream().anyMatch(
                    RetentionPolicy.RetentionRequirement::isHealthRiskExemption));
        }
    }

    // ==================================================================
    // 六、结局一致性
    // ==================================================================

    @Nested
    @DisplayName("六、结局一致性")
    class OutcomeConsistency {

        @Test
        @DisplayName("入口 A：挽留成功 ⇒ 结局只能为继续")
        void entry_a_success_must_end_in_continue() {
            RetentionPolicy p = retention();

            p.assertOutcomeConsistent(RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                    RetentionResult.ACCEPT_CONTINUE, RefundOutcome.CONTINUE);
            p.assertOutcomeConsistent(RefundEntry.A, RefundReasonCode.SERVICE_EXPERIENCE,
                    RetentionResult.ACCEPT_WITH_ADJUSTMENT, RefundOutcome.CONTINUE);

            assertThrows(BizException.class, () -> p.assertOutcomeConsistent(
                    RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                    RetentionResult.ACCEPT_CONTINUE, RefundOutcome.TERMINATE),
                    "挽留成功却结局=终止 —— 自相矛盾");
        }

        @Test
        @DisplayName("入口 A：挽留失败 ⇒ 结局只能为终止")
        void entry_a_rejection_must_end_in_terminate() {
            RetentionPolicy p = retention();

            p.assertOutcomeConsistent(RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE,
                    RetentionResult.REJECT_ENTER_TERMINATION, RefundOutcome.TERMINATE);

            assertThrows(BizException.class, () -> p.assertOutcomeConsistent(
                    RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE,
                    RetentionResult.REJECT_ENTER_TERMINATION, RefundOutcome.CONTINUE),
                    "挽留失败却结局=继续 —— 自相矛盾");
        }

        @Test
        @DisplayName("🛑 入口 A 无挽留结论 ⇒ 抛（『挽留过了但没留结论』不是挽留）")
        void entry_a_without_retention_result_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> retention().assertOutcomeConsistent(
                            RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION, null,
                            RefundOutcome.CONTINUE),
                    "没有结论的挽留不是『挽留过了』，而是流程没走完 —— "
                            + "而挽留结论恰恰是『门店有没有认真处理』的唯一证据");
            assertEquals(5001, e.getCode());
        }

        @Test
        @DisplayName("🛑 豁免挽留的工单却出现挽留结论 ⇒ 抛，且报错区分两种豁免")
        void exempt_order_with_retention_record_is_rejected() {
            RetentionPolicy p = retention();

            BizException health = assertThrows(BizException.class, () -> p.assertOutcomeConsistent(
                    RefundEntry.A, RefundReasonCode.SYMPTOM_WORSENED,
                    RetentionResult.ACCEPT_CONTINUE, RefundOutcome.CONTINUE),
                    "对一次可能的安全事件做挽留，是把安全事件当成销售机会");
            assertTrue(health.getMessage().contains("安全事件"),
                    "健康风险的报错应点出安全事件性质；实际: " + health.getMessage());

            BizException firstCycle = assertThrows(BizException.class, () -> p.assertOutcomeConsistent(
                    RefundEntry.B, null, RetentionResult.ACCEPT_CONTINUE, RefundOutcome.CONTINUE),
                    "对『我们已判定无效』的客户做挽留，语义上自相矛盾");
            assertTrue(firstCycle.getMessage().contains("首周期") || firstCycle.getMessage().contains("无效"),
                    "入口 B 的报错应点出『已判定无效』；实际: " + firstCycle.getMessage());
        }

        @Test
        @DisplayName("🛑 进入终止却无原因码 ⇒ 抛（未经原因分析不可进入终止）")
        void terminate_without_reason_code_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> retention().assertOutcomeConsistent(
                            RefundEntry.B, null, null, RefundOutcome.TERMINATE),
                    "P0-14 逐字『未经原因分析不可进入终止；原因分类为结构化必填』");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("原因"));
        }

        @Test
        @DisplayName("入口 / 结局缺失 ⇒ 各自抛")
        void missing_inputs_are_rejected() {
            assertThrows(BizException.class, () -> retention().assertOutcomeConsistent(
                    null, null, null, RefundOutcome.CONTINUE));
            assertThrows(BizException.class, () -> retention().assertOutcomeConsistent(
                    RefundEntry.B, null, null, null),
                    "空结局 = 工单永久悬停，且归档动作没有触发点");
        }
    }

    // ==================================================================
    // 七、审批落点（出口集中，逐条路径）
    // ==================================================================

    @Nested
    @DisplayName("七、审批落点")
    class ApprovalTargets {

        @Test
        @DisplayName("挽留方案：成功 ⇒ 无需审批；失败 ⇒ 上收总部（布尔条件，不需阈值）")
        void retention_plan_approval_needs_no_threshold() {
            RetentionPolicy p = retention();

            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    p.retentionPlanApproval(RetentionResult.ACCEPT_CONTINUE));
            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    p.retentionPlanApproval(RetentionResult.ACCEPT_WITH_ADJUSTMENT));
            assertEquals(RetentionPolicy.ApprovalTarget.ESCALATE_HEADQUARTERS,
                    p.retentionPlanApproval(RetentionResult.REJECT_ENTER_TERMINATION),
                    "🛑 P0-14 第二层逐字『超阈【或客户拒挽留】才进总部审批』—— "
                            + "后半句是布尔条件；把它和缺失的分段边界绑在一起，"
                            + "会让这条确定结论也被拖住无法判定");

            assertThrows(BizException.class, () -> p.retentionPlanApproval(null));
        }

        @Test
        @DisplayName("🛑 免审批的唯一例外 = 入口 B 的终止；其余终止一律上收总部")
        void full_exemption_has_exactly_one_path() {
            RetentionPolicy p = retention();

            assertEquals(RetentionPolicy.ApprovalTarget.FULL_EXEMPT_FIRST_CYCLE,
                    p.outcomeApproval(RefundEntry.B, RefundOutcome.TERMINATE),
                    "P0-14 第三层『首周期主动终止 = 全额免审批』—— 理由不是想省流程："
                            + "首周期双不达标是系统按公式判定的结果，若还要人工批准，"
                            + "等于把已判定的结论重新交回给人（Top1 痛点复发通道）");

            assertEquals(RetentionPolicy.ApprovalTarget.ESCALATE_HEADQUARTERS,
                    p.outcomeApproval(RefundEntry.A, RefundOutcome.TERMINATE),
                    "P0-15『出口集中（终止与打款须总部审批）』");

            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    p.outcomeApproval(RefundEntry.A, RefundOutcome.CONTINUE));
            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    p.outcomeApproval(RefundEntry.B, RefundOutcome.CONTINUE));

            long exemptCount = List.of(
                            p.outcomeApproval(RefundEntry.A, RefundOutcome.TERMINATE),
                            p.outcomeApproval(RefundEntry.A, RefundOutcome.CONTINUE),
                            p.outcomeApproval(RefundEntry.B, RefundOutcome.TERMINATE),
                            p.outcomeApproval(RefundEntry.B, RefundOutcome.CONTINUE))
                    .stream().filter(RetentionPolicy.ApprovalTarget::isExemptFromHeadquarters).count();
            assertEquals(1, exemptCount, "🛑 免审批的例外【只有一条】—— 多于一条说明有人在放宽出口集中");
        }

        @Test
        @DisplayName("归档态不得问审批落点（问错了对象，且会掩盖一次写操作企图）")
        void archived_outcome_has_no_approval_target() {
            assertThrows(BizException.class, () -> retention().outcomeApproval(
                    RefundEntry.B, RefundOutcome.ARCHIVED));
        }

        @Test
        @DisplayName("入口缺失 ⇒ 抛（否则会把一次免审批的终止误判成须审批）")
        void missing_entry_for_outcome_approval_is_rejected() {
            assertThrows(BizException.class,
                    () -> retention().outcomeApproval(null, RefundOutcome.TERMINATE));
        }

        @Test
        @DisplayName("🛑 让步分段边界当前判不了：evaluable=false，取值恒抛")
        void concession_threshold_is_not_yet_evaluable() {
            RetentionPolicy p = retention();

            assertFalse(p.concessionThresholdEvaluable(),
                    "config #29 声明了行为（阈内自决 / 超阈上收）与基准（按服务成本），"
                            + "但没有任何字段给出【分段边界】—— 本方法必须如实返回 false");
            assertEquals(RefundConfigGap.CONCESSION_SEGMENT_BOUNDARIES, p.concessionThresholdGap());
            assertEquals(RefundPolicy.ConcessionThreshold.BASIS_REQUIRED,
                    p.concessionBehavior().basis(),
                    "行为声明本身是可读的：分段基准必须是『服务成本』而不是『售价』");

            BizException e = assertThrows(BizException.class,
                    () -> p.assertConcessionDecidableInStore(3800L),
                    "取值必须恒抛：若有人为了让『阈内自决』跑通而写了一组常量，这条断言立刻变红");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("#29"), "应点名 #29；实际: " + e.getMessage());
        }

        @Test
        @DisplayName("让步金额为负 ⇒ 抛（负让步是向客户增收费项）")
        void negative_concession_is_rejected() {
            assertThrows(BizException.class,
                    () -> retention().assertConcessionDecidableInStore(-1L),
                    "若下游按绝对值处理，一次符号写错会被读成一次正常的减免");
        }
    }

    // ==================================================================
    // 八、代录者不可审批（R4b ④ 动机阀门）
    // ==================================================================

    @Nested
    @DisplayName("八、代录者不可审批")
    class DeputyCannotApprove {

        @Test
        @DisplayName("代录人与审批人不是同一人 ⇒ 通过")
        void different_people_pass() {
            retention().assertApproverIsNotDeputy("STAFF-MERIDIAN-001", "STAFF-ADMIN-007",
                    RefundAudienceRole.STORE_ADMIN);
        }

        @Test
        @DisplayName("🛑 同一人 ⇒ 抛（自建自批会一次性绕过三条约束）")
        void same_person_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> retention().assertApproverIsNotDeputy("STAFF-ADMIN-007", "STAFF-ADMIN-007",
                            RefundAudienceRole.STORE_ADMIN),
                    "🛑 该角色本就有审批权（§2.3 门店负责人可审批）—— 校验的不是权限而是"
                            + "同一张工单上两个身份不得重合：否则『原话不可编辑 / 延迟进异常名单 / "
                            + "超时升级』三条可被同一人自建自批一次性绕过，且形式上全部合规");
            assertEquals(5001, e.getCode());
            assertTrue(e.getMessage().contains("动机阀门") || e.getMessage().contains("自建自批"),
                    "报错应点出这是动机阀门；实际: " + e.getMessage());
        }

        @Test
        @DisplayName("🛑 身份缺失 ⇒ 抛（不得因『拿不到身份就放行』）")
        void missing_identity_is_rejected() {
            assertThrows(BizException.class, () -> retention().assertApproverIsNotDeputy(
                    null, "STAFF-ADMIN-007", RefundAudienceRole.STORE_ADMIN),
                    "这条动机阀门最需要它的时候正是身份链断了的时候");
            assertThrows(BizException.class, () -> retention().assertApproverIsNotDeputy(
                    "STAFF-MERIDIAN-001", "  ", RefundAudienceRole.STORE_ADMIN));
        }

        @Test
        @DisplayName("🛑 非审批人集合的角色来审批 ⇒ 403（区域督导可见但不审批）")
        void non_approver_role_is_denied() {
            BizException e = assertThrows(BizException.class,
                    () -> retention().assertApproverIsNotDeputy("STAFF-MERIDIAN-001", "STAFF-AREA-003",
                            RefundAudienceRole.AREA_SUPERVISOR),
                    "§2.2『区域督导 ✓（可见、不审批）』");
            assertEquals(2001, e.getCode(), "应为 VISIBILITY_DENIED(2001)");
        }
    }

    // ==================================================================
    // 九、归档收口（「四种结局全部强制归档」）
    // ==================================================================

    @Nested
    @DisplayName("九、归档收口")
    class ArchiveClosure {

        @Test
        @DisplayName("继续 / 终止都是『未结案』，必须被归档动作收口")
        void open_outcomes_require_archive() {
            RetentionPolicy p = retention();

            assertTrue(p.requiresArchive(RefundOutcome.CONTINUE));
            assertTrue(p.requiresArchive(RefundOutcome.TERMINATE),
                    "🛑 若服务层允许 outcome 长期停在『继续 / 终止』而不推进归档，"
                            + "『全部强制归档』就退化成一句没有排期的话");
            assertFalse(p.requiresArchive(RefundOutcome.ARCHIVED));
        }

        @Test
        @DisplayName("归档幂等：未结案 ⇒ 执行；已归档 ⇒ 不执行且不抛")
        void archive_is_idempotent() {
            RetentionPolicy p = retention();

            assertTrue(p.archiveIfOpen(RefundOutcome.TERMINATE),
                    "首周期主动终止 -> 归档，这一步是必须发生的");
            assertFalse(p.archiveIfOpen(RefundOutcome.ARCHIVED),
                    "对已收口的工单再收口一次不产生状态变化 —— 抛错会让一次重试"
                            + "（网络重发 / 批处理重跑）变成需要人工介入的故障");
        }

        @Test
        @DisplayName("🛑 已归档工单的写操作必须被拒（与归档幂等配套）")
        void writes_on_archived_orders_are_rejected() {
            RetentionPolicy p = retention();

            p.assertWritable(RefundOutcome.CONTINUE);
            p.assertWritable(RefundOutcome.TERMINATE);

            BizException e = assertThrows(BizException.class,
                    () -> p.assertWritable(RefundOutcome.ARCHIVED),
                    "🛑 归档幂等让『已归档』不再是一道硬墙，故必须另有一处明确拒绝其它写入 —— "
                            + "否则归档后的工单仍可被改，『归档后只读』落空");
            assertEquals(5001, e.getCode());
        }

        @Test
        @DisplayName("归档收口判定缺结局 ⇒ 抛")
        void missing_outcome_for_archive_is_rejected() {
            RetentionPolicy p = retention();
            assertThrows(BizException.class, () -> p.requiresArchive(null));
            assertThrows(BizException.class, () -> p.archiveIfOpen(null));
            assertThrows(BizException.class, () -> p.assertWritable(null));
        }
    }

    // ==================================================================
    // 十、口径缺失时的 fail-closed
    // ==================================================================

    @Nested
    @DisplayName("十、口径缺失时 fail-closed")
    class FailClosed {

        @Test
        @DisplayName("口径为 null ⇒ 两套策略都拒绝构造（不回落 24h / 不默认流程）")
        void null_policy_is_rejected_by_both_policies() {
            BizException rec = assertThrows(BizException.class,
                    () -> RefundRecordingPolicy.of(null));
            assertEquals(5001, rec.getCode());
            assertTrue(rec.getMessage().contains("24"),
                    "报错应点明『不得按默认 24h 窗口接单』；实际: " + rec.getMessage());

            BizException ret = assertThrows(BizException.class,
                    () -> RetentionPolicy.of(null));
            assertEquals(5001, ret.getCode());
        }
    }

    // ==================================================================
    // 十一、值班场景（把 P0-19 的一整条链跑一遍，包含一次真实的拒收）
    // ==================================================================

    @Test
    @DisplayName("值班场景：客户向调理师说诉求 → 转交 → 门店第 26 小时才代录 ⇒ 判第一跳，且代录者不得审批")
    void on_duty_scenario_walks_the_whole_chain() {
        RefundRecordingPolicy rec = recording();
        RetentionPolicy ret = retention();

        // ① 客户 09-24 02:00 向调理师提出（无凭据 → 不进可核实集合，仅留档）
        Instant claimed = Instant.parse("2026-09-24T02:00:00Z");
        // ② 调理师转交待办（系统写 created_at）—— 这是可核实的起点
        Instant handoff = Instant.parse("2026-09-24T02:20:00Z");
        // ③ 门店到次日 09-25 04:30 才代录
        Instant recorded = Instant.parse("2026-09-25T04:30:00Z");

        RefundRecordingPolicy.RequestedAtResolution res =
                rec.resolveRequestedAt(claimed, null, null, handoff, null);
        assertEquals(handoff, res.requestedAt(), "起点取可核实的转交时间，而非客户主张");
        assertTrue(res.hasClaimed(), "主张仍被留档 —— 它不参与计时，但不丢");

        RefundRecordingPolicy.RecordingDelay delay = rec.evaluateDelay(res.requestedAt(), recorded);
        assertEquals(26.166666, delay.delayHours(), 1e-4, "延迟 ≈ 26.2h");
        assertFalse(delay.withinWindow(),
                "超过 24h ⇒ 进异常名单并自动升级（门店可让诉求自然停留以重置计时的通道被堵）");
        assertEquals(RefundRecordingPolicy.EscalationLevel.STORE_ADMIN_AND_SUPERVISOR, delay.escalation());
        assertEquals(List.of(RefundAudienceRole.STORE_ADMIN, RefundAudienceRole.AREA_SUPERVISOR),
                delay.escalation().escalationAudience(),
                "第一跳推门店负责人 + 区域督导（督导只可见、不审批，故这一跳靠『可见』成立）");

        // ④ 落库前捆绑校验通过
        rec.assertBundleCanBePersisted(new RefundRecordingPolicy.RecordingBundle(
                res.requestedAt(), res.source().code(), res.claimedRetained(), null, recorded));

        // ⑤ 挽留：入口 A + 非健康风险 ⇒ 必须挽留；客户拒绝 ⇒ 结局=终止、上收总部
        assertEquals(RetentionPolicy.RetentionRequirement.REQUIRED,
                ret.requirementOf(RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION));
        ret.assertOutcomeConsistent(RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                RetentionResult.REJECT_ENTER_TERMINATION, RefundOutcome.TERMINATE);
        assertEquals(RetentionPolicy.ApprovalTarget.ESCALATE_HEADQUARTERS,
                ret.retentionPlanApproval(RetentionResult.REJECT_ENTER_TERMINATION));
        assertEquals(RetentionPolicy.ApprovalTarget.ESCALATE_HEADQUARTERS,
                ret.outcomeApproval(RefundEntry.A, RefundOutcome.TERMINATE));

        // ⑥ 🛑 关键一步：那条录用记录是经络师录的，所以他不可以批这张单
        BizException e = assertThrows(BizException.class,
                () -> ret.assertApproverIsNotDeputy("STAFF-MERIDIAN-009", "STAFF-MERIDIAN-009",
                        RefundAudienceRole.STORE_ADMIN),
                "代录者不可审批 —— 否则『延迟进异常名单』这条约束会被同一人自建自批绕过，"
                        + "而他的代录延迟恰恰是 26 小时");
        assertEquals(5001, e.getCode());

        // ⑦ 归档收口
        assertTrue(ret.requiresArchive(RefundOutcome.TERMINATE), "终止仍是未结案，必须被归档动作收口");
    }
}