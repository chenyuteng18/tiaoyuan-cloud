package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.ReplayOutcome;
import com.diaoyuanyun.dy.app.derived.domain.ReplayResult;
import com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.app.derived.repository.VerdictLedger;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.service.ThresholdVersionReplay;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定依据<b>回放闭环</b>的断言（ADR-11 · S2-8）。
 *
 * <h2>它守的是什么（逐字引用 PRD §C.1.9 硬约束②）</h2>
 * <pre>
 *   「判定依据必须落库：verdict / cycle_assessment / refund 的结论必须携
 *     evidence_snapshot + threshold_version，可回放」
 * </pre>
 * 在 S2-8 之前，"可回放"在实现上是空的：没有任何代码会去读
 * {@code threshold_version}、更没有任何代码会用它重算一次。本类断言
 * {@link ThresholdVersionReplay} 把"回放"做成了一次<b>真实的复算</b>，并给出四态结论。
 *
 * <h2>🛑 本类最要紧的一条：漂移判定必须【先于】重算</h2>
 * 见 {@link #drift_beats_coincidental_recompute()}。若顺序颠倒，就会出现：
 * 今天口径改了、而某条历史结论恰好在新旧口径下都路由到 D1 ⇒ 回放器报"可复现"——
 * 那句话是错的（它不是"按今天口径复现出来的"，而是"碰巧两套口径同结果"），
 * 等于用一次巧合给一个已经漂移的口径背书。故本类专门构造"旧版本号 + 新旧口径同结果"
 * 的快照，断言它是 {@code DRIFTED} 而非 {@code REPRODUCED}。
 *
 * <h2>🛑 它为什么用一个真 {@link VerdictLedger} 而不是 Mockito</h2>
 * 回放服务只依赖 {@code VerdictLedger} 的三个 {@code public static} 纯函数
 * （{@code parseEffectOrNull} 等）与 {@code validateTenantId}，<b>不触碰</b>任何
 * 需要数据库的方法。故这里用 {@link DriverManagerDataSource}（<b>懒连接</b> ——
 * 不调 {@code getConnection} 就不会尝试建连）构造一个真实的 {@code VerdictLedger}。
 * 用 Mockito 替身反而会掩盖一个真实约束：回放器与账本共用<b>同一份</b>解析实现
 * （抄一份会让"读回时的语义"出现第二个实现，而两个实现必然在某次改动后分叉）。
 */
class ThresholdVersionReplayTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static final DerivedMetricProfile BASE =
            DerivedMetricProfile.fromRawConfig(new ConfigSeedDerivedProfileSource().raw());

    private final VerdictBranchEngine branchEngine = new VerdictBranchEngine();

    private final ThresholdVersionReplay replay = new ThresholdVersionReplay(
            ledger(), branchEngine, BASE);

    /** 懒连接的数据源：本类不调用任何需要连接的方法，故不会真的建连。 */
    private static VerdictLedger ledger() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/none", "none", "none");
        return new VerdictLedger(ds);
    }

    private final String current = ThresholdVersionFingerprint.of(BASE).version();

    // ==================================================================
    // 一、四态各自一例
    // ==================================================================

    @Test
    @DisplayName("REPRODUCED：版本号一致 + 重算路由与落库一致 ⇒ 可复现")
    void reproduced_when_version_and_branch_both_match() {
        // D1 稳定·改善：达标 + 无风险 + 核心指标改善 + 可比
        String snapshot = snapshot("E3稳定", "达标", "无", true, true, "稳定", "D1", current);

        ReplayResult r = replay.replay(TENANT, snapshot, current);
        assertEquals(ReplayOutcome.REPRODUCED, r.outcome(),
                "🛑 版本号一致且重算分支一致 ⇒ 应判『可复现』。实际: " + r.note());
        assertTrue(r.outcomeCode().contains("可复现"), "结论码应为人话: " + r.outcomeCode());
        assertTrue(r.outcome().isReproducible(), "REPRODUCED 应被认定为可回放得出");
        assertEquals("稳定", r.storedBranch());
        assertEquals("稳定", r.recomputedBranch());
        assertTrue(r.branchMatches());
    }

    @Test
    @DisplayName("DIVERGED：版本号一致，但重算分支与落库不同 ⇒ 算法漂移（指纹覆盖口径、不覆盖算法）")
    void diverged_when_version_matches_but_branch_differs() {
        // 版本号 = 当前（口径没变），但落库的 branch 是"依从不足"（D2），
        // 而快照的事实（达标 + 改善 + 无风险）今天重算得到 D1 ⇒ 不一致。
        String snapshot = snapshot("E3稳定", "达标", "无", true, true, "依从不足", "D2", current);

        ReplayResult r = replay.replay(TENANT, snapshot, current);
        assertEquals(ReplayOutcome.DIVERGED, r.outcome(),
                "🛑 版本号一致却算不出同一个分支，说明『branch 的算法』被改过而"
                        + "『参与指纹的口径』没被改 —— 这是一次真实的算法漂移，"
                        + "必须报 DIVERGED 而不是静默通过。实际: " + r.note());
        assertFalse(r.outcome().isReproducible(), "DIVERGED 不是可回放");
        assertEquals("依从不足", r.storedBranch());
        assertEquals("稳定", r.recomputedBranch());
        assertFalse(r.branchMatches());
        assertTrue(r.note().contains("算法"),
                "说明应点明这是『算法漂移』（指纹覆盖不到算法本身），实际: " + r.note());
    }

    @Test
    @DisplayName("DRIFTED：版本号不一致 ⇒ 报漂移段，且【不重算路由】")
    void drifted_reports_segments_and_does_not_recompute() {
        Map<String, String> storedSegments = new LinkedHashMap<>(
                ThresholdVersionFingerprint.of(BASE).segmentFingerprints());
        // 伪装一个"当时的口径"：min_sample_days 那一段与今天不同
        storedSegments.put("min_sample_days", "0123456789abcdef0123");
        String stored = "tv1-0000000000000000000f";
        String snapshot = snapshotWithSegments("E3稳定", "达标", "无", true, true,
                "稳定", "D1", stored, storedSegments);

        ReplayResult r = replay.replay(TENANT, snapshot, stored);
        assertEquals(ReplayOutcome.DRIFTED, r.outcome(),
                "版本号不一致 ⇒ 口径已漂移。实际: " + r.note());
        assertNull(r.recomputedBranch(),
                "🛑 漂移时【不得】重算路由 —— 一次口径已变的『重算一致』只是巧合。实际: " + r.recomputedBranch());
        assertFalse(r.branchMatches(), "未重算时 branchMatches 必须为假（不得默认 true）");
        assertEquals(List.of("min_sample_days"), List.copyOf(r.driftedSegments().keySet()),
                "应精确定位到漂移的那一段。实际: " + r.driftedSegments());
        assertTrue(r.note().contains("不重算路由") || r.note().contains("巧合"),
                "说明应写明『不重算路由』及其理由，实际: " + r.note());
    }

    @Test
    @DisplayName("INCOMPARABLE_DOMAIN：E5（人工录入）⇒ 域外不可比，不比对不重算")
    void incomparable_domain_for_human_entered_effect_verdict() {
        // 🛑 路由引擎的 BranchInput 会拒 E5（fail-closed），故这里证明：
        //    回放器【在到达重算之前】就把它识别为域外 —— 才是安全的顺序。
        String stored = current;
        String snapshot = snapshot("E5加重", "达标", "无", true, true, "人工复核", "D5", stored);

        ReplayResult r = replay.replay(TENANT, snapshot, stored);
        assertEquals(ReplayOutcome.INCOMPARABLE_DOMAIN, r.outcome(),
                "🛑 E5 由人录入、不由本域口径产出，『按 threshold_version 重放』对它不成立。"
                        + "实际: " + r.note());
        assertNull(r.recomputedBranch(), "域外不得重算");
        assertTrue(r.note().contains("人工"),
                "说明应点明该值由人录入（PRD P0-12『E5 强制人工录入』），实际: " + r.note());
    }

    // ==================================================================
    // 二、🛑 本类最要紧的一条：漂移先于重算
    // ==================================================================

    @Test
    @DisplayName("🛑 漂移先于重算：旧版本号 + 新旧口径恰好同结果 ⇒ 必须报 DRIFTED，不得报 REPRODUCED")
    void drift_beats_coincidental_recompute() {
        // 构造：落库的版本号是「另一个口径」的（口径确实已变），
        // 而快照里的事实与落库 branch 在【今天的口径】下恰好也能算出同一个分支。
        // 若回放器先重算再比版本，它会报"可复现" —— 这是本类要证伪的那件事。
        String otherVersion = "tv1-ffffffffffffffffffff";
        assertFalse(otherVersion.equals(current), "前置：两个版本号必须不同（口径确实变了）");

        // 快照里 D1 的三件事实齐全 ⇒ 今天重算也是 D1 ⇒ 落库也是 D1 ⇒ "重算一致"
        String snapshot = snapshot("E3稳定", "达标", "无", true, true, "稳定", "D1", otherVersion);

        ReplayResult r = replay.replay(TENANT, snapshot, otherVersion);
        assertEquals(ReplayOutcome.DRIFTED, r.outcome(),
                "🛑 回放器把一次『口径已变的巧合重算一致』报成了可复现 —— "
                        + "这正是本类存在的理由：漂移即漂移，不因为『重算刚好一样』而降级。"
                        + "复盘恰恰要知道『这条结论是在旧口径下、且旧口径已失传』。"
                        + "实际: " + r.outcome() + " / " + r.note());
        assertNotEquals(ReplayOutcome.REPRODUCED, r.outcome());
        assertNull(r.recomputedBranch(),
                "🛑 漂移路径必须在重算之前返回（recomputedBranch 应为 null）。"
                        + "若它非空，说明顺序被调换成了『先重算、后比版本』");
    }

    @Test
    @DisplayName("🛑 早于 S2-8 的行（无段级指纹）⇒ 漂移照报，但如实说明『定位不到段』")
    void older_rows_without_segment_fingerprints_degrade_honestly() {
        String stored = "tv1-99999999999999999999";
        // 快照里【没有】threshold_version_segments（S2-8 之前的行就是这样）
        String snapshot = "{\"effect_verdict\":\"E3稳定\",\"adherence_state\":\"达标\","
                + "\"risk_flag\":\"无\",\"core_metric_improved\":true,\"same_origin_comparable\":true,"
                + "\"branch\":\"稳定\",\"threshold_version\":\"" + stored + "\"}";

        ReplayResult r = replay.replay(TENANT, snapshot, stored);
        assertEquals(ReplayOutcome.DRIFTED, r.outcome(),
                "没有段级指纹也要照报漂移（总版本号不一致就是不一致）");
        assertFalse(r.driftedSegments().isEmpty(),
                "🛑 不得返回空 Map —— 空会被读成『没有漂移』。"
                        + "定位不到的退化必须显式说出来，实际: " + r.driftedSegments());
        assertTrue(r.note().contains("段级指纹") || r.note().contains("无法定位"),
                "说明应如实写明『当时的快照没有段级指纹，只能报不一致』，实际: " + r.note());
    }

    // ==================================================================
    // 三、入参校验：依据不可读 / 版本号为空不得静默
    // ==================================================================

    @Test
    @DisplayName("🛑 版本号为空 ⇒ 拒（该行不满足硬约束②，无从回放）")
    void blank_stored_version_is_rejected() {
        for (String bad : new String[]{null, "", "   "}) {
            BizException e = assertThrows(BizException.class,
                    () -> replay.replay(TENANT, "{}", bad),
                    "🛑 空版本号必须拒 —— 它意味着该行未携 threshold_version，"
                            + "硬约束②在该行上已被破坏。返回『回放失败但一切正常』会把一次"
                            + "证据链缺口说成一次正常结果");
            assertEquals(1001, e.getCode(), "应属参数校验失败（1001）。实际: " + e.getCode());
        }
    }

    @Test
    @DisplayName("🛑 依据快照不可解析 ⇒ 拒（依据不可读本身就是硬约束②的破坏）")
    void unparseable_evidence_is_rejected() {
        BizException e = assertThrows(BizException.class,
                () -> replay.replay(TENANT, "{这不是 JSON", current),
                "🛑 依据不可读不得静默通过");
        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("硬约束"),
                "拒绝理由应点明这是硬约束②的破坏，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 租户 ID 非法 ⇒ 拒（RLS 上下文的第一道闸）")
    void invalid_tenant_is_rejected() {
        assertThrows(BizException.class,
                () -> replay.replay("not-a-uuid", "{}", current),
                "租户 ID 非法必须拒 —— 它是 RLS 上下文，短事务里会 SET LOCAL，"
                        + "非法值会让上下文静默为空");
    }

    // ==================================================================
    // 四、边界：不可判（缺输入）不得算出一个臆造的分支
    // ==================================================================

    @Test
    @DisplayName("🛑 快照缺 same_origin_comparable ⇒ 不可判（不得回落 true 去重算）")
    void missing_same_origin_makes_it_incomparable() {
        // 快照没有 same_origin_comparable → 重算无法确定输入 → 应报 INCOMPARABLE_DOMAIN
        String snapshot = "{\"effect_verdict\":\"E3稳定\",\"adherence_state\":\"达标\","
                + "\"risk_flag\":\"无\",\"core_metric_improved\":true,"
                + "\"branch\":\"稳定\",\"threshold_version\":\"" + current + "\"}";

        ReplayResult r = replay.replay(TENANT, snapshot, current);
        assertEquals(ReplayOutcome.INCOMPARABLE_DOMAIN, r.outcome(),
                "🛑 缺一条路由输入即不可判 —— 回落 true 会把『不可比』说成『可比』，"
                        + "从而算出一个从未存在过的分支。实际: " + r.note());
        assertNull(r.recomputedBranch(), "不可判时不得给出重算分支");
    }

    @Test
    @DisplayName("🛑 快照缺 branch ⇒ 依据不完整（branch 是 P0-12 组合出口的输入）")
    void missing_branch_makes_it_incomplete() {
        String snapshot = "{\"effect_verdict\":\"E3稳定\",\"adherence_state\":\"达标\","
                + "\"risk_flag\":\"无\",\"core_metric_improved\":true,\"same_origin_comparable\":true,"
                + "\"threshold_version\":\"" + current + "\"}";

        ReplayResult r = replay.replay(TENANT, snapshot, current);
        assertEquals(ReplayOutcome.INCOMPARABLE_DOMAIN, r.outcome(),
                "缺 branch 的行不是一条完整的判定依据，实际: " + r.note());
        assertNull(r.recomputedBranch());
    }

    // ==================================================================
    // 五、🛑 不对外 / 自描述完整性
    // ==================================================================

    @Test
    @DisplayName("🛑 customerFacing 恒 false（漂移是内部证据链的属性，不是客户的属性）")
    void replay_is_never_customer_facing() {
        for (String snapshot : List.of(
                snapshot("E3稳定", "达标", "无", true, true, "稳定", "D1", current),
                snapshot("E5加重", "达标", "无", true, true, "人工复核", "D5", current))) {
            ReplayResult r = replay.replay(TENANT, snapshot, current);
            assertFalse(r.customerFacing(),
                    "🛑 回放结论一律不对外（Q10 口径②：verdict 整表为对内证据链，"
                            + "visible_to_customer 库层 CHECK 恒假）。实际结论: " + r.outcome());
        }
    }

    @Test
    @DisplayName("🛑 自描述：点名四态 / 顺序 / 漂移先于重算 / 回放范围 / 算法漂移不覆盖")
    void describe_replay_declares_scope_and_boundaries() {
        Map<String, Object> d = replay.describeReplay();
        assertEquals(ReplayOutcome.allCodes(), d.get("outcomes"),
                "四态清单应逐项列出，实际: " + d.get("outcomes"));
        assertEquals(4, ReplayOutcome.allCodes().size(),
                "恰为四态");
        for (String key : List.of("drift_before_recompute", "replayed_scope",
                "algorithm_drift_not_covered_by_fingerprint", "customer_facing")) {
            assertTrue(d.containsKey(key), "自描述应含键 '" + key + "'，实际键集: " + d.keySet());
        }
        assertEquals(Boolean.FALSE, d.get("customer_facing"),
                "自描述应显式声明不对外");
        assertTrue(String.valueOf(d.get("algorithm_drift_not_covered_by_fingerprint")).contains("算法"),
                "🛑 必须显式登记『指纹覆盖口径值、不覆盖算法』这一边界 —— "
                        + "否则读者会以为版本号一致就意味着一切可复现");
    }

    @Test
    @DisplayName("🛑 describeFingerprint 与回放器算出的当前版本号一致（同一份口径，不得两算）")
    void fingerprint_description_matches_current_version() {
        assertEquals(current, replay.currentVersion(),
                "回放器算出的当前版本号应与独立算出的指纹一致");
        assertEquals(current, replay.describeFingerprint().get("threshold_version"),
                "🛑 自描述里的版本号必须与回放用的版本号是同一个 —— "
                        + "若两处各算一次，就会出现『自描述说 A、回放用 B』的分叉");
    }

    @Test
    @DisplayName("🛑 四态枚举：码为人话，且只有 REPRODUCED 被认定为可回放")
    void outcome_enum_semantics_are_exact() {
        for (var o : ReplayOutcome.values()) {
            assertNotNull(o.code());
            assertFalse(o.code().isBlank(), "每态都应有非空的人话码: " + o);
        }
        assertEquals(List.of("可复现", "口径已漂移", "同版本不同分支", "域外不可比"),
                ReplayOutcome.allCodes(),
                "四态码应逐字稳定（出参靠它做断言）");
        assertTrue(ReplayOutcome.REPRODUCED.isReproducible());
        for (var o : List.of(ReplayOutcome.DRIFTED,
                ReplayOutcome.DIVERGED,
                ReplayOutcome.INCOMPARABLE_DOMAIN)) {
            assertFalse(o.isReproducible(),
                    "🛑 只有 REPRODUCED 可被认定为『该条判定可回放得出』。"
                            + "把 DRIFTED/DIVERGED 也算成可回放，等于让一次口径或算法漂移"
                            + "在报表上显示为『正常』。实际: " + o);
        }
    }

    @Test
    @DisplayName("🛑 结论码不等于『一切正常』：四态都不含『成功』语义（防误读为通过）")
    void no_outcome_implies_success_other_than_reproduced() {
        // 这条断言的存在理由：回放器最容易被误用的方式是"看到有结论就当通过"。
        // 四态里只有 REPRODUCED 是真正的通过；其余三态都是需要人看的信号。
        assertEquals(1L, List.of(ReplayOutcome.values()).stream()
                        .filter(ReplayOutcome::isReproducible).count(),
                "🛑 恰好只有一态被认定为通过 —— 多一个都意味着一次漂移会被显示成正常");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String snapshot(String effect, String adherence, String risk,
                                   Boolean coreImproved, Boolean comparable,
                                   String branch, String rule, String version) {
        Map<String, String> segs = new LinkedHashMap<>(
                ThresholdVersionFingerprint.of(BASE).segmentFingerprints());
        return snapshotWithSegments(effect, adherence, risk, coreImproved, comparable,
                branch, rule, version, segs);
    }

    private static String snapshotWithSegments(String effect, String adherence, String risk,
                                               Boolean coreImproved, Boolean comparable,
                                               String branch, String rule, String version,
                                               Map<String, String> segments) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"effect_verdict\":").append(str(effect)).append(',');
        sb.append("\"adherence_state\":").append(str(adherence)).append(',');
        sb.append("\"risk_flag\":").append(str(risk)).append(',');
        sb.append("\"core_metric_improved\":").append(coreImproved).append(',');
        sb.append("\"same_origin_comparable\":").append(comparable).append(',');
        sb.append("\"branch\":").append(str(branch)).append(',');
        sb.append("\"decision_rule\":").append(str(rule)).append(',');
        sb.append("\"threshold_version\":").append(str(version)).append(',');
        sb.append("\"threshold_version_segments\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : segments.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
}