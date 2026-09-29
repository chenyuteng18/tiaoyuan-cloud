package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictPort;
import com.diaoyuanyun.dy.app.derived.domain.VerdictRow;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.service.VerdictService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code threshold_version} 的<b>落库接线</b> —— 服务端算出的那个版本号，
 * 是否真的被写进了两条记录与依据快照（ADR-11 · S2-8）。
 *
 * <h2>🛑 它填的是反向验证逼出来的一个盲区</h2>
 * S2-8 的其余断言都在验"指纹算得对不对"与"回放判得对不对"，
 * 唯独<b>没有一条</b>断言"算出来的那个版本号真的落到了哪几个字段"。
 * 实测证据：把 {@code createVerdict} 里的
 * <pre>
 *   String thresholdVersion = assertThresholdVersion(req.thresholdVersion());
 * </pre>
 * 改成
 * <pre>
 *   String thresholdVersion = req.thresholdVersion();   // 取自入参
 * </pre>
 * 或把 {@code assertThresholdVersion} 的"不一致即拒"改成"静默改用权威版本"，
 * 跑全部 S2-8 测试<b>仍然全绿</b>。那意味着 S2-8 的核心承诺
 * （"版本号由口径算出，不由调用方给"）<b>当时没有任何测试守着</b>。
 *
 * <h2>为什么用一个手写记录式假端口，而不是 Mockito，也不用真库</h2>
 * <ul>
 *   <li><b>不用 Mockito</b>：本类的关键断言是"落库行里的版本号<b>逐字等于</b>服务端
 *       算出的那个"，需要拿到真实的 {@link VerdictRow} / {@link CycleAssessmentRow}
 *       实例去读它的组件值。一次性捕获对象（ArgumentCaptor）能做，但读起来隔了一层，
 *       而这里恰恰要让"落进去的是哪个值"成为最直白的一行。</li>
 *   <li><b>不用真库</b>：本类要证的是<b>接线</b>（值从哪来、写到哪去），
 *       不是 SQL。真库那一层由 RLS 门禁与 {@code VerdictPersistenceBoundaryTest} 独立覆盖；
 *       把它混进来会让"接线错了"与"驱动/环境不通"两种失败长得一样。</li>
 * </ul>
 */
class ThresholdVersionPersistenceWiringTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final UUID CYCLE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final DerivedMetricProfile PROFILE =
            DerivedMetricProfile.fromRawConfig(new ConfigSeedDerivedProfileSource().raw());

    private static final String COMPUTED = ThresholdVersionFingerprint.of(PROFILE).version();

    /** 记录式假端口：只留下被写入的两行，不做任何持久化。 */
    private static final class RecordingLedger implements VerdictPort {

        CycleAssessmentRow cycleRow;
        VerdictRow verdictRow;
        int cycleInserts;
        int verdictInserts;
        /** 🛑 V8：补判定的调用次数 —— 它必须恒为 0（假端口下 findCycleAssessment 恒 null ⇒ 走 INSERT）。 */
        int attachCalls;
        /** 🛑 V8：C4 落过的依据行（模拟"已存在但未判定"）—— null 时模拟"首次到达"。 */
        CycleAssessmentRow preexistingCycleRow;

        @Override
        public void insertCycleAssessment(String tenantId, CycleAssessmentRow row) {
            this.cycleRow = row;
            this.cycleInserts++;
        }

        @Override
        public CycleAssessmentRow findCycleAssessment(String tenantId, UUID cycleId) {
            // 🛑 默认 null（= C4 未落过该行 ⇒ createVerdict 走一次性 INSERT 的既有路径）。
            //    preexistingCycleRow 被显式设置时，模拟"C4 已落依据、F1 来补判定"的两阶段路径。
            return preexistingCycleRow;
        }

        @Override
        public List<CycleAssessmentRow> findCycleAssessmentsByCustomer(String tenantId, UUID customerId) {
            return List.of();
        }

        @Override
        public void attachJudgment(String tenantId, UUID cycleId,
                                   com.diaoyuanyun.dy.app.derived.domain.VerdictBranch branch,
                                   com.diaoyuanyun.dy.app.derived.domain.EffectVerdict effectVerdict,
                                   java.math.BigDecimal improvementRate,
                                   String metricSnapshotJson, java.time.Instant judgedAt) {
            this.attachCalls++;
            this.cycleRow = CycleAssessmentRow.awaitingVerdict(
                    cycleId, CUSTOMER, 1, null, "{}", metricSnapshotJson, null,
                    com.diaoyuanyun.dy.app.derived.domain.AdherenceState.PASS,
                    "{}", "v1", null, judgedAt, "attach");
        }

        @Override
        public void insertVerdict(String tenantId, VerdictRow row) {
            this.verdictRow = row;
            this.verdictInserts++;
        }

        @Override
        public VerdictRow findVerdict(String tenantId, UUID verdictId) {
            return null;
        }

        @Override
        public List<VerdictRow> findVerdictsByCustomer(String tenantId, UUID customerId) {
            return List.of();
        }

        @Override
        public List<VerdictRow> findVerdictsByCycle(String tenantId, UUID cycleId) {
            return List.of();
        }
    }

    private final RecordingLedger ledger = new RecordingLedger();

    private final VerdictService service = new VerdictService(
            ledger,
            new EffectVerdictEngine(PROFILE),
            new com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine(PROFILE),
            new AdherenceEngine(PROFILE),
            new VerdictBranchEngine(),
            PROFILE);

    // ==================================================================
    // 一、🛑 落库值必须取服务端算出的那个（不是入参）
    // ==================================================================

    @Test
    @DisplayName("🛑 不带版本号 ⇒ 两条记录与依据快照都落服务端算出的版本号")
    void version_is_computed_and_written_to_all_three_places() throws Exception {
        var out = service.createVerdict(TENANT, CYCLE, request(null), "admin:test");

        assertNotNull(ledger.cycleRow, "依据行必须落库");
        assertNotNull(ledger.verdictRow, "非挂起时结论行必须落库");
        assertEquals(COMPUTED, ledger.cycleRow.thresholdVersion(),
                "🛑 cycle_assessment.threshold_version 必须逐字等于服务端算出的指纹");
        assertEquals(COMPUTED, ledger.verdictRow.thresholdVersion(),
                "🛑 verdict.threshold_version 必须逐字等于服务端算出的指纹（硬约束③逐字点名此列）");
        assertTrue(out.evidenceJson().contains(COMPUTED),
                "依据快照里也必须含版本号");

        JsonNode snapshot = new ObjectMapper().readTree(out.evidenceJson());
        assertEquals(COMPUTED, snapshot.path("threshold_version").asText(),
                "🛑 快照的 threshold_version 必须与两条记录同值 —— "
                        + "三处若有一个不同，回放时会拿 A 去比 B，报一次不存在的漂移");
        assertEquals(9, snapshot.path("threshold_version_segments").size(),
                "段级指纹应 9 项齐全（漂移定位的前提）");
    }

    @Test
    @DisplayName("🛑 入参携带正确版本号 ⇒ 照常落库（断言位不影响结果）")
    void matching_assertion_is_accepted() {
        var out = service.createVerdict(TENANT, CYCLE, request(COMPUTED), "admin:test");
        assertEquals(COMPUTED, ledger.cycleRow.thresholdVersion());
        assertEquals(COMPUTED, ledger.verdictRow.thresholdVersion(),
                "入参携带权威版本号是合法形态（例如调用方先查了 /verdicts/contract）");
        assertNotNull(out);
    }

    @Test
    @DisplayName("🛑 入参携带不一致版本号 ⇒ 拒 VERSION_CONFLICT(4001)，且【一行都不落】")
    void mismatched_assertion_is_rejected_before_any_write() {
        BizException e = assertThrows(BizException.class,
                () -> service.createVerdict(TENANT, CYCLE, request("tv1-00000000000000000000"), "admin:test"),
                "🛑 入参版本号与当前口径不一致必须拒 —— 该缺口正是『版本号可随便传』的成因。"
                        + "『静默改用权威版本』也不行：调用方会以为自己指定成功了");
        assertEquals(4001, e.getCode(),
                "🛑 用 VERSION_CONFLICT(4001) 而不是 VALIDATION_FAILED(1001)："
                        + "这不是参数格式不对，而是『你所说的版本与当前权威版本不一致』"
                        + "—— 与硬约束③『版本不可覆盖』同族。实际: " + e.getCode());
        assertTrue(e.getMessage().contains(COMPUTED),
                "拒绝理由必须给出当前权威版本号（否则调用方无从修正）。实际: " + e.getMessage());

        assertEquals(0, ledger.cycleInserts,
                "🛑 断言必须在<b>任何计算与落库之前</b>完成 —— 依据是不可覆盖的，"
                        + "一次落库即永久留在证据链里。故此处应零写入");
        assertEquals(0, ledger.verdictInserts, "🛑 同上：结论行也不得落");
    }

    @Test
    @DisplayName("🛑 入参为空白串 ⇒ 拒 VALIDATION_FAILED（空白的含义与『请服务端填』相同，但它是被显式给了值）")
    void blank_assertion_is_rejected_as_validation_error() {
        BizException e = assertThrows(BizException.class,
                () -> service.createVerdict(TENANT, CYCLE, request("   "), "admin:test"),
                "空白版本号必须拒 —— 不得当成『未携带』");
        assertEquals(1001, e.getCode(), "空白的业务含义与格式错误同类（1001）");
        assertEquals(0, ledger.cycleInserts, "零写入");
    }

    // ==================================================================
    // 二、🛑 服务端算出的那个值，就是 /verdicts/contract 自描述里的那个
    // ==================================================================

    @Test
    @DisplayName("🛑 自描述与落库值必须是同一个版本号（不得两处各算一次）")
    void self_description_and_persisted_value_agree() throws Exception {
        var out = service.createVerdict(TENANT, CYCLE, request(null), "admin:test");

        assertEquals(service.currentThresholdVersion(), ledger.verdictRow.thresholdVersion(),
                "🛑 服务端自报的当前版本号与落库值必须是同一个 —— "
                        + "若两处各算一次，将来任一处口径读取路径变了就会静默分叉，"
                        + "而调用方按自描述里的版本号去断言就会得到一次莫名其妙的 4001");

        Map<String, Object> described = service.describeThresholdVersion();
        assertEquals(COMPUTED, described.get("threshold_version"),
                "自描述里的 threshold_version 应等于落库值");
        JsonNode snapshot = new ObjectMapper().readTree(out.evidenceJson());
        assertEquals(described.get("threshold_version"), snapshot.path("threshold_version").asText());
    }

    @Test
    @DisplayName("🛑 依据快照的段级指纹与自描述的段级指纹逐项一致")
    void snapshot_segments_match_fingerprint_segments() throws Exception {
        var out = service.createVerdict(TENANT, CYCLE, request(null), "admin:test");
        JsonNode snapshot = new ObjectMapper().readTree(out.evidenceJson());
        JsonNode segments = snapshot.path("threshold_version_segments");

        Map<String, String> described = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, String> raw = (Map<String, String>)
                service.describeThresholdVersion().get("segment_fingerprints");
        described.putAll(raw);

        assertEquals(List.copyOf(described.keySet()), fieldNamesInOrder(segments),
                "🛑 快照里段级指纹的 key 顺序应与自描述一致（保序纪律）。"
                        + "顺序不稳定会让同一份证据在不同 JVM 启动间产出字节不同的快照。"
                        + "实际快照顺序: " + fieldNamesInOrder(segments));
        for (Map.Entry<String, String> e : described.entrySet()) {
            assertEquals(e.getValue(), segments.path(e.getKey()).asText(),
                    "段 '" + e.getKey() + "' 的指纹在快照与自描述里必须相同");
        }
    }

    // ==================================================================
    // 三、🛑 快照补的 same_origin_comparable（回放的确定输入）
    // ==================================================================

    @Test
    @DisplayName("🛑 快照必须含 same_origin_comparable（S2-8 之前没有它，回放只能猜）")
    void snapshot_declares_same_origin_comparable() throws Exception {
        var out = service.createVerdict(TENANT, CYCLE, request(null), "admin:test");
        JsonNode snapshot = new ObjectMapper().readTree(out.evidenceJson());

        assertTrue(snapshot.has("same_origin_comparable"),
                "🛑 快照里必须有 same_origin_comparable —— 它是路由的一条真实输入"
                        + "（D5 前置的判据），缺了它回放就无法还原当时的输入");
        assertFalse(snapshot.path("same_origin_comparable").isNull(),
                "它必须是确定的布尔值（不得为 null —— null 会让回放落『不可判』）");
        assertTrue(snapshot.path("same_origin_comparable").asBoolean(),
                "本用例是同源可比，故应为 true");
    }

    @Test
    @DisplayName("🛑 快照里的 branch 与落库 verdict 行的 branch 同值（回放的比对基准）")
    void snapshot_branch_equals_persisted_branch() throws Exception {
        var out = service.createVerdict(TENANT, CYCLE, request(null), "admin:test");
        JsonNode snapshot = new ObjectMapper().readTree(out.evidenceJson());
        assertEquals(ledger.verdictRow.branch().dbLabel(), snapshot.path("branch").asText(),
                "🛑 回放的核心比对是『快照 branch vs 重算 branch』。"
                        + "若快照里的 branch 与落库行的 branch 不同（例如快照记的是 PRD 表述、"
                        + "行里记的是 db 字面），回放会对每一条历史都报一次假的不一致");
    }

    // ==================================================================
    // 四、口径变 ⇒ 落库版本号必变（端到端形态）
    // ==================================================================

    @Test
    @DisplayName("🛑 换一份口径的引擎 ⇒ 落库版本号随之变化（同一服务类，不同剖面）")
    void persisted_version_follows_the_profile() throws Exception {
        String newVersion = ThresholdVersionFingerprint.of(changedProfile()).version();
        assertNotEquals(COMPUTED, newVersion, "前置：改了口径必须得到另一个版本号");

        var out2 = serviceWith(changedProfile()).createVerdict(TENANT, CYCLE, request(null), "admin:test");
        assertNotNull(out2);
        assertTrue(out2.evidenceJson().contains(newVersion),
                "🛑 用一份被改过的口径（门槛 0.85）跑同一个用例，落库的版本号必须与新口径一致，"
                        + "而不是仍然沿用旧口径的版本号 —— 后者会让『新口径的判定』"
                        + "被索引分进旧口径那一组，硬约束③就此落空");
        assertFalse(out2.evidenceJson().contains(COMPUTED),
                "🛑 新口径的依据里【不得】出现旧口径的版本号");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private VerdictService serviceWith(DerivedMetricProfile profile) {
        return new VerdictService(
                new RecordingLedger(),
                new EffectVerdictEngine(profile),
                new com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine(profile),
                new AdherenceEngine(profile),
                new VerdictBranchEngine(),
                profile);
    }

    private static DerivedMetricProfile changedProfile() {
        return new DerivedMetricProfile(new BigDecimal("0.85"), PROFILE.asRefundWeights(),
                PROFILE.asOpsWeights(), PROFILE.missingPolicy(), PROFILE.minSampleDays(),
                PROFILE.mcid(), PROFILE.confidence(), PROFILE.source());
    }

    /**
     * 一份能路由到 D1（稳定·改善）的合法请求：
     * 依从达标 + 无风险 + 核心指标改善 + 同源可比。
     */
    private static VerdictService.CreateVerdictRequest request(String thresholdVersion) {
        Map<String, AdherenceEngine.DimensionInput> dims = new LinkedHashMap<>();
        dims.put("A1", new AdherenceEngine.DimensionInput(true, BigDecimal.ONE, false));
        dims.put("A3", new AdherenceEngine.DimensionInput(true, BigDecimal.ONE, false));
        dims.put("A4", new AdherenceEngine.DimensionInput(true, BigDecimal.ONE, false));

        return new VerdictService.CreateVerdictRequest(
                CUSTOMER,
                1,
                12,
                12,
                new EffectVerdictEngine.SameOriginAssert(true, true, true),
                new AdherenceEngine.AdherenceInput(12, dims),
                RiskFlag.NONE,
                true,
                new com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine.ConfidenceInput(
                        com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine
                                .SameOriginStatus.SAME_ORIGIN,
                        28, 12, 0),
                "{\"M1\":0,\"M2\":0,\"M3\":0,\"M4\":0,\"M5\":0}",
                "手环趋势：平稳",
                thresholdVersion);
    }

    private static List<String> fieldNamesInOrder(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}