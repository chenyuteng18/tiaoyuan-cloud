package com.diaoyuanyun.dy.app.derived.service;

import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.ReplayOutcome;
import com.diaoyuanyun.dy.app.derived.domain.ReplayResult;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.app.derived.repository.VerdictLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 判定依据的<b>回放闭环</b>（ADR-11 · S2-8）。
 *
 * <h2>🛑 它存在的唯一理由：把"可回放"从口号变成一次可执行的重算</h2>
 * PRD §C.1.9 硬约束②逐字：「判定依据必须落库：{@code verdict} / {@code cycle_assessment}
 * / {@code refund} 的结论必须携 {@code evidence_snapshot} + {@code threshold_version}，
 * <b>可回放</b>」。在 S2-8 之前，"可回放"这句话在实现上是<b>空的</b>：
 * <pre>
 *   ① threshold_version 是调用方传的自由字符串（见 ThresholdVersionFingerprint 类注释）；
 *   ② 没有任何代码会去读它、更没有任何代码会用它重算一次；
 *   ③ 于是"依据已落库"这件事，只能靠人打开 JSON 逐字比对 —— 那不是回放，那是阅读。
 * </pre>
 * 本类把"回放"实现为一次<b>真实的复算</b>，并给出三态结论。
 *
 * <h2>回放的三态（顺序即优先级，不得调换）</h2>
 * <pre>
 *   ① INCOMPARABLE_DOMAIN —— 本次判定不在本域的口径范围内（effect_verdict 是 E5 或人工录入）
 *                            ⇒ 不比对、不重算，如实说明"可由人重放的那一项不在本域"
 *   ② DRIFTED            —— 落库的 threshold_version 与今天算出的不一致
 *                            ⇒ 口径已漂移，报出【漂移段】，不重算路由
 *   ③ REPRODUCED         —— 版本号一致，且用今天的口径重算 routing 得到的决策规则
 *                            与当时落库的 branch 一致
 * </pre>
 *
 * <h2>🛑 ②为什么必须排在③之前 —— 这是本类最要紧的一处设计</h2>
 * 若先重算再比对版本，就会出现这种形态：
 * <pre>
 *   今天口径改了（AS 门槛 0.80 → 0.85），但某条历史结论恰好在新旧口径下都路由到 D1，
 *   于是重算"一致" ⇒ 回放器报告"可复现"。
 * </pre>
 * 这句话是<b>错的</b>：那条结论不是"按今天口径复现出来的"，而是"碰巧两套口径同结果"。
 * 把它说成可复现，等于用一次巧合给一个已经漂移的口径背书 ——
 * 而复盘时恰恰要知道"这条结论是在旧口径下、且旧口径已失传"。
 * 故本类<b>先判漂移、再谈复现</b>：漂移即漂移，不因为"重算刚好一样"而降级。
 *
 * <h2>🛑 与 VerdictLedger 的依赖方向</h2>
 * 本类直接依赖 {@link VerdictLedger#parseEffectOrNull} 等 <b>{@code public static}</b>
 * 纯函数（而不是把这两个解析搬过来抄一份）。这是刻意的：
 * 缺口 ②（{@code risk_flag} 库层无 CHECK）的唯一防线就是那一行解析，
 * 抄一份等于让"读回时的语义"出现第二个实现 —— 而两个实现必然在某次改动后分叉。
 * 见 {@code VerdictLedger} 类注释「三个可空枚举解析为什么是 public static」。
 *
 * <h2>🛑 它<b>不</b>重算置信度与 disposition，且这是边界不是省事</h2>
 * 置信度的四项因子（{@code d.answered} / {@code n.expected_days} / {@code m.mcid_delta}）
 * 与 disposition 的三 enum 在 {@code evidence_snapshot} 里的形态各不相同
 * （{@code mcid_delta} 并未落进快照 —— 它是本次请求的中间量）。
 * 只用 {@code threshold_version} 一项去重算它们，会得到一个"看起来重算过、
 * 实则用了臆造的因子"的结果 —— 那比不重算更糟。
 * 故本类明确<b>只重放可由版本号决定的那一项</b>（路由），并把这条边界写进结论。
 *
 * <h2>U-15 / 附表 B 的纪律：不产生任何对客户不利的推断</h2>
 * 漂移是<b>内部证据链的属性</b>，不是客户的属性。故
 * {@link ReplayResult#customerFacing()} 恒为 {@code false}，
 * 且本类的任何出参都不含"客户数据是否合规"之类的判断。
 */
@Service
public class ThresholdVersionReplay {

    /** 本周转阶段的结尾通则（尾巴原文）—— 所有拒答与漂移说明共用同一条收敛句。 */
    private static final String SUSPENDED_NOTE =
            "口径不可回放时不重算路由：一次口径已变的『重算一致』只是巧合，"
                    + "把它说成可复现等于用巧合给漂移背书";

    private final VerdictLedger ledger;
    private final VerdictBranchEngine branchEngine;
    private final DerivedMetricProfile profile;
    private final ThresholdVersionFingerprint current;

    public ThresholdVersionReplay(VerdictLedger ledger,
                                  VerdictBranchEngine branchEngine,
                                  DerivedMetricProfile profile) {
        this.ledger = ledger;
        this.branchEngine = branchEngine;
        this.profile = profile;
        this.current = ThresholdVersionFingerprint.of(profile);
    }

    // ==================================================================
    // 一、回放
    // ==================================================================

    /**
     * 回放一条已落库的判定。
     *
     * @param tenantId    租户（RLS 上下文）
     * @param evidenceJson {@code verdict} / {@code cycle_assessment} 行的
     *                     {@code evidence_snapshot}（两处同值 —— 见
     *                     {@code VerdictService.buildEvidenceSnapshot} 的说明）
     * @param storedVersion 该行落库的 {@code threshold_version} 列值
     */
    public ReplayResult replay(String tenantId, String evidenceJson, String storedVersion) {
        VerdictLedger.validateTenantId(tenantId);
        if (storedVersion == null || storedVersion.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "threshold_version 为空 —— 该行不满足 PRD §C.1.9 硬约束②"
                            + "（依据必须携 threshold_version 且可回放），无从回放");
        }
        Map<String, Object> snapshot = parse(evidenceJson);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "evidence_snapshot 无法解析 —— 依据不可读，回放无从进行。"
                            + "🛑 此处不返回『回放失败但一切正常』：依据不可读本身就是一次硬约束②的破坏");
        }

        // 🛑 ① 域外判定（E5 加重 / 人工录入）排在最前：不是"比不了"，而是"不该比"
        String effectRaw = str(snapshot.get("effect_verdict"));
        boolean domainExcluded = isDomainExcluded(effectRaw);
        if (domainExcluded) {
            return new ReplayResult(ReplayOutcome.INCOMPARABLE_DOMAIN, storedVersion,
                    current.version(), Map.of(), null, null, false,
                    "本次判定的 effect_verdict = '" + effectRaw + "' —— 该值由人录入、不由本域口径产出，"
                            + "故『按 threshold_version 重放』对它不成立（重放一个从不由口径产生的东西是伪命题）。"
                            + "这正是 PRD P0-12「E5 强制人工录入」的应有之义："
                            + "可由人重放的那一项，本就不在自动口径的范围内");
        }

        // 🛑 ② 版本漂移（排在重算之前 —— 见类注释的完整论证）
        boolean versionMatches = current.matches(storedVersion);
        Map<String, String> drifted = Map.of();
        Map<String, String> storedSegments = segmentFingerprintsOf(snapshot);
        if (!versionMatches) {
            drifted = current.driftedSegments(storedSegments);
        }
        if (!versionMatches) {
            return new ReplayResult(ReplayOutcome.DRIFTED, storedVersion, current.version(),
                    drifted.isEmpty() ? driftFallback(snapshot, storedSegments) : drifted,
                    str(snapshot.get("branch")), null, false,
                    "落库版本号 '" + storedVersion + "' 与当前口径算出的 '"
                            + current.version() + "' 不一致 ⇒ 口径已漂移。"
                            + (drifted.isEmpty()
                                    ? "（当时的快照里没有段级指纹，故只能报出『不一致』而无法定位到段 —— "
                                      + "段级指纹是 S2-8 起才写进快照的，更早的行没有这一项。）"
                                    : "漂移段（旧→新段的指纹）: " + new TreeMap<>(drifted))
                            + "。🛑 本条【不重算路由】：" + SUSPENDED_NOTE
                            + "。若需对两个口径都给出结论，正确做法是【重开一次判定】（新行），"
                            + "而不是用新口径去覆盖这条旧结论的解释");
        }

        // 🛑 ③ 复现：用今天的口径重算路由，与当时落库的 branch 比对
        String storedBranch = str(snapshot.get("branch"));
        VerdictBranch recomputed = replayRoute(snapshot);
        if (storedBranch == null || storedBranch.isBlank()) {
            return new ReplayResult(ReplayOutcome.INCOMPARABLE_DOMAIN, storedVersion,
                    current.version(), Map.of(), null, null, false,
                    "快照里没有 branch —— 该行不是一条完整的判定依据（branch 是 P0-12 组合出口的输入），"
                            + "故重算结果无从比对");
        }
        // 🛑 不可判（缺输入）⇒ 同样落 INCOMPARABLE_DOMAIN，且【不】回填重算分支。
        //    这一支在早期实现里曾被写成"与落库 branch 不相等 ⇒ DIVERGED"，
        //    而那是错的：recomputed == null 的含义是"今天也没算出来"（缺一条路由输入），
        //    不是"算出来了但不是这个值"。把两者合并会造出一次【假指控】——
        //    说明文字会宣称"branch 的算法被改过"，而真实成因只是"当时的快照少录了一项"。
        //    这类错误报错的代价极高：它把人指向一次不存在的算法变更。
        //    另：三条不可判路径（无 branch / 缺输入 / 域外）必须一致地不回填
        //    recomputedBranch —— 否则同族的第三个分支会成为一个不一致的例外。
        if (recomputed == null) {
            return new ReplayResult(ReplayOutcome.INCOMPARABLE_DOMAIN, storedVersion,
                    current.version(), Map.of(), storedBranch, null, false,
                    "版本号虽一致，但用今天的口径重算【算不出结果】—— 快照里缺少路由必需的输入"
                            + "（effect_verdict / adherence_state / risk_flag / core_metric_improved / "
                            + "same_origin_comparable 之一）。"
                            + "🛑 这不是『算法漂移』：『算不出来』与『算出来是别的值』是两件事，"
                            + "把前者说成后者会把人引向一次不存在的算法变更。"
                            + "故本条落 INCOMPARABLE_DOMAIN（不可判）而不是 DIVERGED");
        }
        boolean sameBranch = storedBranch.equals(recomputed.dbLabel());
        return new ReplayResult(
                sameBranch ? ReplayOutcome.REPRODUCED : ReplayOutcome.DIVERGED,
                storedVersion, current.version(), Map.of(), storedBranch,
                recomputed.dbLabel(), sameBranch,
                sameBranch
                        ? "版本号一致且重算路由与当时落库一致 ⇒ 该条判定在今天的口径下可回放得出。"
                        : "版本号一致，但用今天的口径重算路由得到『"
                          + recomputed.dbLabel()
                          + "』而落库是『" + storedBranch + "』⇒ 判定链存在不一致。"
                          + "🛑 版本号一致却算不出同一个分支，说明『branch 的算法』被改过而『参与指纹的口径』没被改 —— "
                          + "这是一次真实的算法漂移（指纹覆盖不到算法本身），须人工复核");
    }

    /** 当前口径的版本号（供端点自描述与测试断言）。 */
    public String currentVersion() {
        return current.version();
    }

    /** 当前口径指纹的完整自描述（不含任何口径取值）。 */
    public Map<String, Object> describeFingerprint() {
        return current.describe();
    }

    /** 回放要点自描述（回应"回放到底做了什么、没做什么"）。 */
    public Map<String, Object> describeReplay() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcomes", ReplayOutcome.allCodes());
        m.put("order", "① INCOMPARABLE_DOMAIN → ② DRIFTED → ③ REPRODUCED / DIVERGED（顺序即优先级，不得调换）");
        m.put("drift_before_recompute",
                "漂移判定【先于】重算：一次口径已变的『重算一致』只是巧合，"
                        + "把它说成可复现等于用巧合给漂移背书，而复盘恰恰要知道『这条结论在旧口径下、旧口径已失传』");
        m.put("replayed_scope",
                "本回放只重放【由 threshold_version 决定的那一项】= 路由分支（D1~D5）。"
                        + "置信度与 disposition 不重算：它们的因子（d.answered / n.expected_days / m.mcid_delta / "
                        + "risk_flag / core_metric_improved）并非全部落在 evidence_snapshot 里，"
                        + "只用版本号一项去重算会得到一个『看似重算过、实则用了臆造因子』的结果 —— 那比不重算更糟");
        m.put("algorithm_drift_not_covered_by_fingerprint",
                "🛑 指纹覆盖【口径值】不覆盖【算法】。若有人改了 VerdictBranchEngine 的路由规则而"
                        + "不动任何配置，版本号不变 ⇒ 漂移检测【不会】报 —— 这一形态由 REPRODUCED/DIVERGED 的"
                        + "重算比对来抓（版本一致而重算分支不同）");
        m.put("customer_facing", false);
        m.put("customer_facing_note",
                "漂移是内部证据链的属性、不是客户的属性。故回放结论一律不对外（Q10 口径②："
                        + "verdict 整表为对内证据链，visible_to_customer 库层 CHECK 恒假）");
        return m;
    }

    // ==================================================================
    // 内部：从快照重算路由
    // ==================================================================

    /**
     * 用今天的口径 + 快照里的原始事实重算路由。
     *
     * <p>🛑 它<b>只</b>消费 {@code evidence_snapshot} 里真实存在的字段 ——
     * 缺任一项即返回 {@code null}（"不可判"），而<b>不</b>用某个默认值顶上。
     * 顶一个默认值会让"当时没录"静默变成"当时录了某个值"，从而算出一个
     * 从未存在过的分支并宣布"不一致"（一次假警报），或宣布"一致"（一次假通过）。
     */
    private VerdictBranch replayRoute(Map<String, Object> snapshot) {
        EffectVerdict effect = VerdictLedger.parseEffectOrNull(str(snapshot.get("effect_verdict")));
        var adherence = AdherenceStateLookup.parse(str(snapshot.get("adherence_state")));
        RiskFlag risk = VerdictLedger.parseRiskOrNull(str(snapshot.get("risk_flag")));
        Boolean coreImproved = boolOrNull(snapshot.get("core_metric_improved"));

        // 🛑 挂起态（effect_verdict 为空）不入路由重算：路由在 D5 前置处就返回人工复核，
        //    而那一支的输入是"不可比"，不是"没算出来"。
        //    重算它会得到 D5，但那不是"复现了当时的判定"，而是"复现了一次挂起" ——
        //    两者对复盘的价值不同（挂起本身不落 verdict 行）。
        if (effect == null) {
            return null;
        }
        // 同源可比：快照里同一横向字段缺失即不可判（不回落 true —— 回落会把不可比说成可比）
        Boolean sameOriginHolds = boolOrNull(snapshot.get("same_origin_comparable"));
        if (sameOriginHolds == null) {
            return null;
        }
        return branchEngine.route(new VerdictBranchEngine.BranchInput(
                effect, adherence, risk, coreImproved, sameOriginHolds)).branch();
    }

    // ==================================================================
    // 内部：快照读取
    // ==================================================================

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取快照里的段级指纹（S2-8 起写入）。
     *
     * <p>早于 S2-8 的行没有这一项 —— 故返回空 Map 并<b>不</b>算错，
     * 只是漂移定位退化为"只报不一致、不报是哪个段"。这条退化必须显式说出来（见调用点的消息），
     * 而不是让"定位不到"看起来与"没有漂移"一样。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> segmentFingerprintsOf(Map<String, Object> snapshot) {
        Object raw = snapshot.get("threshold_version_segments");
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /**
     * 无段级指纹时的漂移说明（如实说"定位不到"，不说"没有漂移"）。
     *
     * <p>返回一个单键 Map 而不是空 Map：调用方据此区分
     * "比对过、只是当时没记指纹" 与 "没有任何差异信息"。
     */
    private static Map<String, String> driftFallback(Map<String, Object> snapshot,
                                                     Map<String, String> storedSegments) {
        if (storedSegments.isEmpty()) {
            return Map.of("定位", "当时落库的快照里没有段级指纹，故只能报『版本号不一致』而无法定位到段");
        }
        return Map.of();
    }

    private static boolean isDomainExcluded(String effectRaw) {
        if (effectRaw == null || effectRaw.isBlank()) {
            return false;
        }
        EffectVerdict e = VerdictLedger.parseEffectOrNull(effectRaw);
        // mustBeHumanEntered() = E5（加重）—— 系统绝不自动产出，故不由口径决定
        return e != null && e.mustBeHumanEntered();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Boolean boolOrNull(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    /** 依从状态解析（**只读**：{@code parse} 未知即抛，与落库值域同一份实现）。 */
    private static final class AdherenceStateLookup {
        private AdherenceStateLookup() {
        }

        static com.diaoyuanyun.dy.app.derived.domain.AdherenceState parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return com.diaoyuanyun.dy.app.derived.domain.AdherenceState.parse(raw);
            } catch (RuntimeException e) {
                // 快照里的依从状态无法解析 ⇒ 不可判（返回 null 让路由走"无依从"那条）
                // 🛑 不回落成"不足"：回落会让一条损坏的快照算出一个看起来正常的结论
                return null;
            }
        }
    }

    // ==================================================================
    // 产出（值对象已移入 domain —— 见 ReplayOutcome / ReplayResult 的类注释）
    // ==================================================================
    //
    // 🛑 ReplayOutcome 与 ReplayResult 是【值对象】，住在
    //    com.diaoyuanyun.dy.app.derived.domain。它们曾作为本类的嵌套类型，
    //    但 ArchitectureBoundaryTest 的 R4（DIP）逐字要求
    //    「领域模型（实体 / DO / record）不得依赖服务层」—— 一个 record
    //    引用本包内的枚举即违规，且它是**全量回归**（`mvn -o clean install`）
    //    才暴露的：定向跑判定域测试时根本不加载 ArchitectureBoundaryTest。
    //    拆分后本类只保留"过程"（读快照 → 比版本 → 重算路由），
    //    值对象可脱离服务独立单测。
}
