package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阈值版本号 —— <b>内容寻址指纹</b>（ADR-11 · S2-8）。
 *
 * <h2>🛑 它要堵的是一个具体的洞：版本号是"自由字符串"</h2>
 * 在 S2-8 之前，{@code threshold_version} 是 F1 请求体里调用方传入的一个字符串，
 * 服务端<b>只校验它非空白</b>（见 {@link VerdictService.CreateVerdictRequest} 的历史形态）。
 * 于是：
 * <pre>
 *   POST /cycle-assessments/{id}/verdicts
 *   { …, "threshold_version": "随便什么串" }
 * </pre>
 * 这段调用<b>能成功落库</b>。而 PRD §C.1.9 硬约束②逐字要求
 * 「{@code verdict} / {@code cycle_assessment} / {@code refund} 的结论必须携
 * {@code evidence_snapshot} + {@code threshold_version}，<b>可回放</b>」——
 * 一个与任何口径都不绑定的字符串<b>无法回放任何东西</b>：
 * 复盘时拿它去问"当时是按哪套门槛下的结论"，得到的只有那个字符串本身。
 *
 * <p>更隐蔽的一面：{@code idx_verdict_threshold} / {@code idx_cycle_threshold}
 * 两个索引把 {@code threshold_version} 当**分组键**用。调用方随手传的串会让
 * "同一口径"的判定被索引分成若干互不相干的组 —— 而口径漂移核查正是靠这个分组做的。
 *
 * <h2>本类给出的定义：版本号 = 口径内容的 SHA-256 前缀</h2>
 * 版本号不再由人给，而是<b>算出来</b>：
 * <pre>
 *   threshold_version = "tv1-" + sha256(规范化口径串)[0..20)
 * </pre>
 * 于是两条原本无法断言的事实变成可机械断言：
 * <ol>
 *   <li><b>同一口径 ⇒ 同一版本号</b>（确定性：无随机、无时间戳、无对象哈希）；</li>
 *   <li><b>口径变 ⇒ 版本号变</b>（内容寻址：改任何一个参与计算的声明值，哈希必变）。</li>
 * </ol>
 * 第 2 条正是硬约束③「{@code verdict.threshold_version} <b>只可新增版本，
 * 不可原地覆盖</b>」的机械形态：新口径天然得到一个<b>新</b>版本号，
 * 而旧版本号在库里仍然指向旧口径 —— 不存在"同一个版本号指向两套口径"的可能。
 *
 * <h2>🛑 为什么版本号是哈希，而不是把口径串直接落库</h2>
 * {@code evidence_snapshot} 是契约 F1 的出站字段（{@code VerdictData} 六项之一），
 * 它会被下发到端侧。若把规范化口径串（含门槛 {@code 0.80}、权重 {@code 0.571}、
 * MCID {@code Δ=3}）直接写进去，就等于<b>把内部口径下发给端侧</b> ——
 * 而 {@link DerivedRawConfig#toString()} 刻意不打印取值，正是同一条纪律的另一处落点。
 * 哈希是单向的：它足以回答"是不是同一套口径"，但回答不了"门槛是多少"。
 *
 * <p>故本类同时产出<b>段级指纹</b>（{@link #segmentFingerprints()}）：口径漂移时
 * 能精确指出<b>是哪一段变了</b>，而每一段同样只是哈希 —— 定位能力与不泄漏取值两者兼得。
 *
 * <h2>参与指纹的九段（逐段有来源）</h2>
 * <table border="1">
 *   <tr><th>段名</th><th>来源</th><th>为什么它必须进指纹</th></tr>
 *   <tr><td>{@code pass_threshold}</td><td>config {@code #4}</td>
 *       <td>达标门槛直接决定 {@code adherence_state}（达标/不足）</td></tr>
 *   <tr><td>{@code as_refund_weights}</td><td>config {@code #5}</td>
 *       <td>{@code AS_refund} 权重决定 AS 数值，且合规锁（不含 A2）在此段</td></tr>
 *   <tr><td>{@code as_ops_weights}</td><td>config {@code #5}</td>
 *       <td>运营看板口径；虽不直接判定，但它与 {@code AS_refund} 同源声明，
 *           改一处而不改另一处是一次真实的口径事件</td></tr>
 *   <tr><td>{@code missing_policy}</td><td>config {@code #6}</td>
 *       <td>缺失值策略决定行为性缺失是否记 0 —— 它的方向直接决定结论是否对客户不利</td></tr>
 *   <tr><td>{@code min_sample_days}</td><td>config {@code #7}</td>
 *       <td>样本护栏决定"样本不足"的边界（该态下不产结论）</td></tr>
 *   <tr><td>{@code mcid}</td><td>config {@code #33}</td>
 *       <td>Δ 分档边界决定 {@code effect_verdict}</td></tr>
 *   <tr><td>{@code confidence}</td><td>config {@code #45}</td>
 *       <td>置信度合成式与各因子取值；{@code s} 一票否决决定是否挂起</td></tr>
 *   <tr><td>{@code scale_structure}</td><td>config {@code #35} 经 {@link ScaleStructure}</td>
 *       <td>量程（0–4）与题数（4/维、7 维）是 Δ 与完成度分母的量纲来源 ——
 *           假定 v1.7 那次「0–3 → 0–4」的量程改判发生时本段缺失，
 *           那么改判前后的判定会共用一个版本号</td></tr>
 *   <tr><td>{@code module_mapping}</td><td>PRD §2.9.6 的 7 维 → M1–M5 映射表</td>
 *       <td>🛑 <b>PRD L856 逐字</b>：「模块级 {@code ΔH_m} 必须携带『映射版本』
 *           （建议把『模块映射版本』并入 {@code threshold_version} 的语义，
 *           <b>而非新开字段</b>）」。本段即该要求的落点</td></tr>
 * </table>
 *
 * <h2>🛑 三段"边界"说明（避免把不在指纹里的东西误认为在）</h2>
 * <ol>
 *   <li><b>不参与</b>：{@code significant_threshold} 与 {@code backtest_gates}
 *       （PRD 未给值 / 人工金标准未产生，恒 {@code TBD}）。它们不是"口径值"，
 *       而是"尚未产生的值"；把它们算进指纹会让版本号在一个未定项上摇摆。</li>
 *   <li><b>不参与</b>：{@code #30} 的 {@code core_metric} / {@code window_count} 等
 *       更宽的效果判定参数组。它们<b>应当</b>参与，但当前骨架里
 *       {@link DerivedMetricProfile} 并不解析 {@code #30} —— 把这层扩进来是一次
 *       独立的解析器改动（须同步 {@code #4} 的段数、{@code DerivedRawConfig} 的
 *       字段与来源测试）。本类<b>不</b>顺手扩，而是把它登记为后续项，
 *       以免"版本号声称覆盖了 #30、实际没有"这种比缺失更糟的形态。</li>
 *   <li><b>模块映射段的当前值</b>：散见于 PRD §2.9.6 的映射表<b>尚未</b>落成
 *       配置项或数据表（骨架里没有对应的 config 编号）。本类按 PRD 表逐字
 *       重建它并算指纹，同时以 {@link #MODULE_MAPPING_SOURCE_NOTE}
 *       显式标注"来源是 PRD 文档、尚未落库" —— 而不是假装它已从配置读取。
 *       映射落库后，本段应改为读配置（版本号会随之变化，这正是它该有的行为）。</li>
 * </ol>
 */
public final class ThresholdVersionFingerprint {

    /** 版本号前缀 —— 让"这是内容寻址指纹"这件事在库里一眼可辨（与人工填的串区分）。 */
    public static final String PREFIX = "tv1-";

    /** 指纹取 SHA-256 十六进制的前多少位。20 位十六进制 = 80 bit，碰撞概率对单体系统可忽略。 */
    private static final int HEX_LENGTH = 20;

    private static final String ALGORITHM = "SHA-256";

    /**
     * 参与指纹的九段 —— <b>顺序即语义</b>（规范串按此顺序拼接）。
     *
     * <p>顺序写死而不是"遍历 Map"：{@code HashMap} 的迭代顺序在不同 JVM / 不同
     * 插入历史下可能不同，那会让<b>同一份口径算出两个版本号</b> ——
     * 而本类的全部价值就是"同一口径必得同一版本号"。
     */
    public static final List<String> SEGMENTS = List.of(
            "pass_threshold",
            "as_refund_weights",
            "as_ops_weights",
            "missing_policy",
            "min_sample_days",
            "mcid",
            "confidence",
            "scale_structure",
            "module_mapping");

    /** 模块映射段的来源说明（进日志与自描述；🛑 不含映射细节，故可安全外发）。 */
    public static final String MODULE_MAPPING_SOURCE_NOTE =
            "PRD §2.9.6「7 维 → M1–M5 模块映射」表（含 2 个孤儿维）；"
                    + "该映射当前尚未落成配置项或数据表，故本段按 PRD 表逐字重建后取指纹。"
                    + "映射落库后本段应改为读配置 —— 届时版本号会变，这是正确行为";

    /** 本类不纳入指纹的两项，及其理由（自描述用）。 */
    public static final String EXCLUDED_NOTE =
            "未纳入指纹：significant_threshold 与 backtest_gates（PRD 未给值 / 人工金标准未产生，恒 TBD —— "
                    + "它们不是口径值而是『尚未产生的值』，纳入会让版本号在未定项上摇摆）；"
                    + "config #9 的 branch_rules（其 branches 五字面与 VerdictBranch 枚举逐字一致，"
                    + "而分支顺序/条件判断 D1~D5 写死在 VerdictService 内；"
                    + "它是『已声明但运行时未消费』—— 将来改为配置驱动时必须纳入本指纹）；"
                    + "config #30 的 core_metric / window_count 等更宽参数组"
                    + "（DerivedMetricProfile 当前不解析 #30，扩进来是一次独立的解析器改动）";

    private final String version;
    private final Map<String, String> segmentFingerprints;

    private ThresholdVersionFingerprint(String version, Map<String, String> segmentFingerprints) {
        this.version = version;
        // 🛑 保序：Map.copyOf 的迭代顺序不保证（JDK 的不可变集合用 per-JVM 随机盐），
        //    而本 Map 会被写进 evidence_snapshot 的 threshold_version_segments（JSON 对象）。
        //    用 Map.copyOf 会让同一份口径在不同 JVM 启动间写出 key 顺序不同的快照 ——
        //    语义相同但字节不同，与"同一口径 ⇒ 同一结果"的纪律相悖。
        //    这里包一层可控的不可变视图：既保序，又不给外部留下改回去的口子。
        this.segmentFingerprints =
                Collections.unmodifiableMap(new LinkedHashMap<>(segmentFingerprints));
    }

    // ==================================================================
    // 唯一构造入口
    // ==================================================================

    /**
     * 从已归一化的口径剖面算出内容寻址指纹。
     *
     * <p>确定性保证：无随机数、无时间戳、无 {@code Object.hashCode()}、
     * {@code BigDecimal} 一律规范化（见 {@link #normalizeDecimal}）、
     * 所有 Map 按字典序展开、段顺序取自 {@link #SEGMENTS}。
     */
    public static ThresholdVersionFingerprint of(DerivedMetricProfile profile) {
        if (profile == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径剖面为空 —— 无法为本次判定生成 threshold_version。"
                            + "版本号是判定依据可回放的前提（PRD §C.1.9 硬约束②），"
                            + "故此处不生成任何占位版本号，直接拒绝");
        }
        Map<String, String> canonical = canonicalSegments(profile);
        Map<String, String> segmentFingerprints = new LinkedHashMap<>();
        List<String> joined = new ArrayList<>();
        // 按 SEGMENTS 的固定顺序拼接 —— 而不是遍历 canonical 的键集
        for (String name : SEGMENTS) {
            String body = canonical.get(name);
            if (body == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "口径指纹缺少段 '" + name + "' —— 段的集合与 SEGMENTS 不一致。"
                                + "这是本类的内部不变式被破坏（新增段时必须同时更新 SEGMENTS 与 canonicalSegments）");
            }
            segmentFingerprints.put(name, hex(body));
            joined.add(name + "=" + body);
        }
        String overall = PREFIX + hex(String.join("\n", joined));
        return new ThresholdVersionFingerprint(overall, segmentFingerprints);
    }

    // ==================================================================
    // 对外视图
    // ==================================================================

    /** 版本号（{@code "tv1-" + 20 位十六进制}）—— 即落库到 {@code threshold_version} 的值。 */
    public String version() {
        return version;
    }

    /** 段级指纹（段名 → 20 位十六进制）—— 漂移定位用，<b>不含任何口径取值</b>。 */
    public Map<String, String> segmentFingerprints() {
        return segmentFingerprints;
    }

    /** 参与指纹的段数（供自描述与测试断言，避免"少了一段却没人发现"）。 */
    public static int segmentCount() {
        return SEGMENTS.size();
    }

    /**
     * 判断一个已落库的版本号是否可由当前口径复现。
     *
     * @return {@code true} = 当前口径算出的版本号与该串逐字相同（可回放）
     */
    public boolean matches(String storedVersion) {
        return storedVersion != null && version.equals(storedVersion.trim());
    }

    /**
     * 漂移段清单 —— 给定"当时落库的段级指纹"，指出哪些段的指纹在今天变了。
     *
     * <h2>为什么必须靠段级指纹（而不是总指纹）</h2>
     * 总指纹只能回答"变了没变"。而复盘要回答的是"<b>哪里</b>变了"——
     * 例如"只是 {@code as_ops_weights} 动了"（不影响判定）与
     * "`pass_threshold` 动了"（直接改变本次结论的达标判定）是完全不同的两件事。
     * 只报总差异会把这两种情形说成同一句话。
     *
     * @param storedSegmentFingerprints 当时落库的段级指纹；{@code null}/空 = 无从比对
     * @return 段名 → {@code "旧值→新值"}；空 Map = 无差异或无从比对
     */
    public Map<String, String> driftedSegments(Map<String, String> storedSegmentFingerprints) {
        Map<String, String> drifted = new LinkedHashMap<>();
        if (storedSegmentFingerprints == null || storedSegmentFingerprints.isEmpty()) {
            return drifted;
        }
        for (String name : SEGMENTS) {
            String was = storedSegmentFingerprints.get(name);
            String now = segmentFingerprints.get(name);
            if (was != null && !was.equals(now)) {
                drifted.put(name, was + "→" + now);
            }
        }
        return drifted;
    }

    /** 自描述（进日志与自描述端点；🛑 不含口径取值）。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threshold_version", version);
        m.put("prefix_note", "版本号由口径内容寻址算出（" + PREFIX + " + SHA-256 前 " + HEX_LENGTH
                + " 位十六进制），不是调用方传入的自由字符串");
        m.put("segments", SEGMENTS);
        m.put("segment_count", SEGMENTS.size());
        m.put("segment_fingerprints", segmentFingerprints);
        m.put("module_mapping_source", MODULE_MAPPING_SOURCE_NOTE);
        m.put("excluded_from_fingerprint", EXCLUDED_NOTE);
        m.put("no_values_emitted",
                "本响应不含任何口径取值（门槛 / 权重 / Δ 边界 / 指数 / 天数）—— "
                        + "哈希是单向的：足以回答『是不是同一套口径』，回答不了『门槛是多少』。"
                        + "把口径串直接下发等于泄漏内部口径（与 DerivedRawConfig.toString 同一条纪律）");
        return m;
    }

    // ==================================================================
    // 规范串（内部）
    // ==================================================================

    /**
     * 九段规范串 —— 每段一个确定性字符串。
     *
     * <p>🛑 本方法<b>只</b>产确定性文本，<b>绝不</b>把它写进任何落库字段或响应体
     * （见类注释「为什么版本号是哈希」）。它只进哈希。
     */
    private static Map<String, String> canonicalSegments(DerivedMetricProfile p) {
        Map<String, String> out = new LinkedHashMap<>();

        out.put("pass_threshold", "v=" + normalizeDecimal(p.passThreshold()));
        out.put("as_refund_weights", weights(p.asRefundWeights()));
        out.put("as_ops_weights", weights(p.asOpsWeights()));
        out.put("missing_policy", "v=" + p.missingPolicy().code());

        DerivedMetricProfile.Mcid mcid = p.mcid();
        out.put("min_sample_days", "v=" + p.minSampleDays());
        out.put("mcid", "module_total_max=" + mcid.moduleTotalMax()
                + ";improved.delta_min=" + mcid.improvedDeltaMin()
                + ";improved.pct=" + normalizeDecimal(mcid.improvedPct())
                + ";improved.no_item_rise_ge=" + mcid.noItemRiseGe()
                + ";stable.delta_min=" + mcid.stableDeltaMin()
                + ";stable.delta_max=" + mcid.stableDeltaMax()
                + ";worsened.delta_rise_min=" + mcid.worsenedRiseMin());

        DerivedMetricProfile.Confidence c = p.confidence();
        out.put("confidence", "exponents=" + weights(c.exponents())
                + ";s.same_origin=" + normalizeDecimal(c.sameOrigin())
                + ";s.missing_metadata=" + normalizeDecimal(c.missingMetadata())
                + ";s.incomparable=" + normalizeDecimal(c.incomparable())
                + ";s.incomparable_forces_human=" + c.incomparableForcesHuman()
                + ";d.answered=" + c.answered()
                + ";n.min_sample_days=" + c.nMinSampleDays()
                + ";n.expected_days_denominator=" + c.expectedDaysDenominator()
                + ";n.reweight_when_na=" + weights(c.reweightWhenNa())
                + ";m.base=" + normalizeDecimal(c.mBase())
                + ";m.span=" + normalizeDecimal(c.mSpan())
                + ";m.delta_gap=" + c.mDeltaGap()
                + ";bands.high_min=" + normalizeDecimal(c.highMin())
                + ";bands.medium_min=" + normalizeDecimal(c.mediumMin()));

        // 量程与结构事实（config #35 经 ScaleStructure —— 它自己 fail-closed 读配置）
        out.put("scale_structure", "item_min=" + ScaleStructure.itemMin()
                + ";item_max=" + ScaleStructure.itemMax()
                + ";items_per_module=" + ScaleStructure.itemsPerModule()
                + ";module_count=" + ScaleStructure.moduleCount()
                + ";required_item_count=" + ScaleStructure.requiredItemCount()
                + ";module_total_max=" + ScaleStructure.moduleTotalMax());

        out.put("module_mapping", moduleMappingSegment());
        return out;
    }

    /**
     * 模块映射段 —— PRD §2.9.6「7 维 → M1–M5 模块映射」表（含两个孤儿维）。
     *
     * <h2>🛑 它为什么要进指纹（PRD L856 逐字）</h2>
     * <pre>
     *   「模块级的可比性前置：baseline_assessment 的模块分并非直接存在 ——
     *     基线存的是 dimension_scores[7]，要得到基线模块分必须先做 7 维 → M1–M5 映射
     *     （该映射有 2 个孤儿维）；模块级 ΔH_m 必须携带"映射版本"
     *     （建议把"模块映射版本"并入 threshold_version 的语义，而非新开字段）」
     * </pre>
     * 逐条依据 PRD §2.9.6 的映射表：
     * <pre>
     *   睡眠质量          → M1 睡眠          （完整）
     *   肩颈腰背筋骨      → M2 疼痛筋骨      （完整）
     *   代谢体态消化      → M3 代谢消化      （完整）
     *   情绪抗压与抵抗力  → M4 情绪压力      （部分：抵抗力 → M5）
     *   体能精力          → M5 疲劳体能      （完整）
     *   面部气色肤质      → （M5 观察项含"气色"）（孤儿 · 部分承载）
     *   记忆专注          → —                （孤儿 · 明确不参与效果判定）
     * </pre>
     * <p>🛑 两个孤儿维<b>不是</b>"漏了"，而是 PRD 明示的：孤儿维给它一个"损益"
     * 就等于把一个不可归因的指标放上判定台（PRD 逐字「禁止给它一个损益」）。
     * 故它们在规范串里以 {@code orphan} 显式出现 —— 缺席与"声明为孤儿"是两件事。
     *
     * <p>🛑 <b>缺口登记</b>：本段内容当前<b>只存在于 PRD 文档</b>，尚未落成 config 编号
     * 或数据表。本方法把它按 PRD 表逐字重建（如同 {@link ScaleStructure} 重建量程事实），
     * 并以 {@link #MODULE_MAPPING_SOURCE_NOTE} 标注来源。映射落库后本段应改为读配置。
     */
    private static String moduleMappingSegment() {
        // 顺序固定（按 PRD 表自上而下），不遍历 enum 的 values() ——
        // values() 的顺序是声明顺序，看似稳定，但它与"PRD 表的顺序"是两件事；
        // 显式列出可让"PRD 表改了而代码没改"从一次静默漂移变成一次显式改动。
        return "睡眠质量=M1:SLEEP;"
                + "肩颈腰背筋骨=M2:PAIN;"
                + "代谢体态消化=M3:METABOLISM;"
                + "情绪抗压与抵抗力=M4:MOOD(partial:抵抗力→M5);"
                + "体能精力=M5:FATIGUE;"
                + "面部气色肤质=orphan(partial:M5观察项含气色);"
                + "记忆专注=orphan(excluded:不参与效果判定)";
    }

    /**
     * 权重图 → 确定性串：<b>键按字典序</b>，值规范化。
     *
     * <p>{@code TreeMap} 而非直接遍历：{@code Map.copyOf} 的迭代顺序不保证，
     * 直接遍历会让同一份权重在不同 JVM 上拼出不同串 ⇒ 两个版本号。
     */
    private static String weights(Map<String, BigDecimal> w) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BigDecimal> e : new TreeMap<>(w).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(normalizeDecimal(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * {@code BigDecimal} 规范化 —— 让"写法不同、数值相同"的声明值得到同一个指纹。
     *
     * <p>{@code 0.80} 与 {@code 0.8} 是同一个门槛，若指纹把它们当两个口径，
     * 就会出现"把声明从 {@code 0.80} 改写成 {@code 0.8}，判定结论毫无变化，
     * 但版本号变了、索引分组断了"—— 这类噪音会让真实的漂移淹没在伪漂移里。
     *
     * <p>🛑 {@code stripTrailingZeros()} 在整数上会给出科学计数法
     * （{@code 16} → {@code 1.6E+1}），故必须再接 {@code toPlainString()}。
     */
    private static String normalizeDecimal(BigDecimal v) {
        if (v == null) {
            return "null";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    private static String hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制提供的算法；取不到说明运行环境被削过。
            // 🛑 绝不回落到"用 String.hashCode 当指纹"—— 那会造出一个
            //    看起来正常、实则不具备内容寻址性质的版本号。
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "运行环境缺少 " + ALGORITHM + " —— 无法为判定生成内容寻址的 threshold_version。"
                            + "版本号是判定依据可回放的前提（PRD §C.1.9 硬约束②），"
                            + "故不回落到任何替代算法");
        }
    }

    @Override
    public String toString() {
        return "ThresholdVersionFingerprint{" + version + ", segments=" + segmentFingerprints.size() + '}';
    }
}