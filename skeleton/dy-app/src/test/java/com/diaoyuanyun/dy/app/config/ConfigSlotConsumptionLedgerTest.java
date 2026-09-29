package com.diaoyuanyun.dy.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>配置槽位「声明 × 消费」台账</b> —— 把"哪些配置真的被运行时消费、哪些还没有"变成
 * 构建期可机械清点的事实（批次十）。
 *
 * <h2>它堵的是什么洞</h2>
 * 配置真相源（{@code 02_slots_seed.sql}）声明 <b>46</b> 条槽位，而"哪几条真的被生产代码读过"
 * 此前<b>没有人守</b>。这造成两类后果，且症状完全相反：
 * <ul>
 *   <li><b>声明了却没人读</b>（如 {@code #21} 曾标注"总部唯一可改"，而运行代码硬编码 {@code 0.30}
 *       从不读配置 —— <b>2026-09-27 批次十一已接线修复</b>，本条保留为历史成因说明）—— 症状是
 *       "改了配置毫无效果"，而排查者以为配置已生效；</li>
 *   <li><b>读了却没声明</b>（键名打错、按已废弃的键抓取）—— 症状是配置读到 {@code null} 而静默兜底。</li>
 * </ul>
 * 两者都不报错。本类把这条边界从"人读 README"升级为<b>构建期事实</b>。
 *
 * <h2>判据（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>全集侧机械提取</b>：46 条槽位来自 seed 文件本身，不是手抄 ——
 *       seed 增删一条，本账本立刻对不上；</li>
 *   <li><b>消费信号来自生产代码</b>：判定"被消费" = 生产源码（<b>剥离注释后</b>）出现该键字面量。
 *       🛑 剥注释是必须的 —— 本仓已实测到<b>注释-only 引用</b>（{@code cfg:verdict.branch_rules}
 *       只在 Javadoc 里出现），若不剥注释会把它误判为"已消费"，而它其实没有任何运行路径读它；</li>
 *   <li><b>账面逐条对账</b>：
 *       <ul>
 *         <li>账本记"已消费"的项，生产代码里<b>必须</b>真的引用它（否则账本在骗人）；</li>
 *         <li>账本记"未消费"的项，生产代码里<b>必须</b>真的不引用它 ——
 *             一旦有人消费了却没更新账本 ⇒ 红 ⇒ <b>归零必须是一次显式动作</b>。</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>它【不】回答什么（诚实边界）</h2>
 * 本类只判"<b>生产代码里有没有引用这个键</b>"。它<b>不</b>判：
 * <ul>
 *   <li>那处引用<b>是否真的走了运行时路径</b>（静态扫描证明不了可达性，
 *       也证明不了"读到了值并用了它"）；</li>
 *   <li>未消费的槽位<b>该不该</b>被消费 —— 那是 config owner 的裁定。
 *       本账本只把"未消费"钉成显式事实，使"裁定之后"成为显式动作。</li>
 * </ul>
 *
 * <h2>账面里的两条重点（本轮实测暴露）</h2>
 * <ul>
 *   <li>{@code #21 cfg:crossstore.split_threshold}：seed 标注"<b>总部唯一可改</b>"，
 *       而 {@code CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD} 曾硬编码 {@code 0.30}，
 *       {@code WebConfig} 曾直接 {@code new CrossStoreSettlement()} —— <b>从不读配置</b>。
 *       ✅ <b>2026-09-27 批次十一已收口</b>：新增 {@code ConfigSeedCrossStoreProfileSource}
 *       + {@code CrossStoreThresholds}，{@code WebConfig} 改为按配置注入；
 *       {@code #21}/{@code #22} 已从本账"未消费"移入"已消费"（归零是一次显式编辑）。</li>
 *   <li>{@code #30 cfg:verdict.formula_params}：运行时<b>无人消费</b>，且其取值与
 *       {@code #4}（{@code adherence_gate.min=0.8} ≡ {@code pass_threshold=0.80}）、
 *       {@code #35}（{@code range} 逐字相同）、{@code #33}（{@code module_total_max=16}）构成
 *       <b>同一业务数字的多处声明</b> ⇒ 当前重合、未来有分歧风险。</li>
 * </ul>
 *
 * <p>本类为纯静态检查，不连数据库，归入 {@code dy-app} 的常规单测。
 */
@DisplayName("配置槽位消费台账：声明 46 条 × 生产代码引用（未消费的归零必须显式）")
class ConfigSlotConsumptionLedgerTest {

    /** 配置声明文件在 classpath 上的位置（与 dy-config / dy-app 的读取方逐字一致）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** 参与消费扫描的模块（生产代码侧；不含 resources，因为 seed/迁移文本是"副本"不是"消费"）。 */
    private static final List<String> SCANNED_MODULES = List.of(
            "dy-app", "dy-config", "dy-security", "dy-tenancy",
            "dy-web", "dy-audit", "dy-crypto", "dy-common");

    /**
     * 被生产代码<b>真实引用</b>的槽位（键字面量出现在 {@code src/main/java} 中，剥离注释后）。
     *
     * <p>值 = 该键的<b>代表性引用文件</b>（相对 skeleton 根）；断言会核这个文件里确实含该键，
     * 使"账本与代码的连线"本身也被守着（文件改名 / 键改名即红）。
     */
    private static final Map<Integer, String> CONSUMED = new LinkedHashMap<>();

    /**
     * 声明了但<b>生产代码尚未引用</b>的槽位（账面显式登记）。
     *
     * <p>🛑 这里登记不是"待办清单"，而是"<b>差异真的还在</b>"的机械事实：
     * 一旦某条被消费了而没从本表移除，断言会红；一旦全集里冒出未登记的第 47 条，也会红。
     */
    private static final Set<Integer> UNCONSUMED = new LinkedHashSet<>();

    static {
        // ---- 已消费（18 条）：键字面量出现在下列生产代码中 ----
        CONSUMED.put(4, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        CONSUMED.put(5, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        CONSUMED.put(6, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        CONSUMED.put(7, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        CONSUMED.put(8, "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/domain/ContraindicationPolicy.java");
        CONSUMED.put(10, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundPolicy.java");
        CONSUMED.put(26, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundRawConfig.java");
        CONSUMED.put(27, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundPolicy.java");
        CONSUMED.put(28, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundPolicy.java");
        CONSUMED.put(29, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundConfigGap.java");
        CONSUMED.put(33, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        CONSUMED.put(35, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/ScaleStructure.java");
        CONSUMED.put(38, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundPolicy.java");
        CONSUMED.put(40, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain/RefundRawConfig.java");
        CONSUMED.put(43, "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/domain/BandVisibilityMatrix.java");
        CONSUMED.put(45, "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/DerivedMetricProfile.java");
        // ---- 2026-09-27 · 批次十一（N-14 收口）：跨店域两槽从"未消费"真正接线 ----
        // #21/#22 此前是"声明了却没人读"的典型（seed 标注"总部唯一可改"，代码却硬编码 0.30）。
        // 现由 ConfigSeedCrossStoreProfileSource 解析 → CrossStoreThresholds 校验 →
        // WebConfig 注入 CrossStoreSettlement，是真实的运行时消费路径。
        CONSUMED.put(21, "dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/service/ConfigSeedCrossStoreProfileSource.java");
        CONSUMED.put(22, "dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/service/ConfigSeedCrossStoreProfileSource.java");

        // ---- 未消费（28 条）：生产代码（剥注释后）无该键字面量 ----
        UNCONSUMED.addAll(List.of(
                1, 2, 3, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                23, 24, 25, 30, 31, 32, 34, 36, 37, 39, 41, 44, 46, 48));
    }

    // ==================================================================
    // 一、全集侧：机械提取，不手抄
    // ==================================================================

    @Test
    @DisplayName("全集（机械读 seed）= 已消费 ∪ 未消费，且两账互斥 —— 不得有第三个")
    void the_declared_slot_set_equals_consumed_union_unconsumed() throws IOException {
        Map<Integer, String> declared = declaredSlots();   // no -> key

        assertFalse(declared.isEmpty(), "从 seed 解析不出任何槽位 —— 解析器失效，本门禁形同虚设");
        assertTrue(declared.size() > 40,
                "解析出的槽位只有 " + declared.size() + " 条 —— 解析口径可能漏行");

        Set<Integer> consumed = new TreeSet<>(CONSUMED.keySet());
        Set<Integer> unconsumed = new TreeSet<>(UNCONSUMED);

        // ① 两账互斥：一个槽位不可能既"被消费"又"未被消费"
        Set<Integer> overlap = new TreeSet<>(consumed);
        overlap.retainAll(unconsumed);
        assertTrue(overlap.isEmpty(),
                "🛑 以下槽位同时出现在「已消费」与「未消费」两账中: " + overlap
                        + " —— 账面自相矛盾，本门禁的结论不可信");

        // ② 并集 == 全集：不得有"两侧都不在"的槽位（N-3 形态：有意不纳入 vs 忘了纳入无法区分）
        Set<Integer> union = new TreeSet<>(consumed);
        union.addAll(unconsumed);
        Set<Integer> unaccounted = new TreeSet<>(declared.keySet());
        unaccounted.removeAll(union);
        assertTrue(unaccounted.isEmpty(),
                "🛑 以下配置槽位【既不在「已消费」也不在「未消费」账上】: " + unaccounted
                        + " —— 这就是「不纳入」与「忘了纳入」无法区分的形态。\n"
                        + "🛑 处置：二选一 —— ① 生产代码确实引用了它 ⇒ 加进 CONSUMED 并附代表性文件；"
                        + "② 确实没人引用 ⇒ 加进 UNCONSUMED。两条都必须是一次显式编辑。");

        // ③ 反向：账面出现的编号必须在 seed 里真实存在（防追认幻影槽位）
        Set<Integer> phantom = new TreeSet<>(union);
        phantom.removeAll(declared.keySet());
        assertTrue(phantom.isEmpty(),
                "🛑 账面登记了 seed 里不存在的槽位编号: " + phantom
                        + " —— 要么编号写错，要么该槽位已被删除（本账本必须随之更新）");

        // ④ 与批次九的 cfg:verdict.* 闭合自证交叉核对（同一份事实，两个守卫各守一侧）
        Set<Integer> verdictSlots = new TreeSet<>();
        declared.forEach((no, key) -> {
            if (key.startsWith("cfg:verdict.")) {
                verdictSlots.add(no);
            }
        });
        assertEquals(Set.of(9, 30, 33, 45), verdictSlots,
                "cfg:verdict.* 的槽位集合变了 —— 本账本与 ThresholdVersionFingerprintTest 的"
                        + "交叉自证必须同步；实际: " + verdictSlots);
        assertTrue(consumed.containsAll(Set.of(33, 45)),
                "cfg:verdict.* 中 #33 / #45 应被判定为【已消费】（指纹九段用它们）");
        assertTrue(unconsumed.containsAll(Set.of(9, 30)),
                "cfg:verdict.* 中 #9 / #30 应被判定为【未消费】—— 与 EXCLUDED_NOTE 的排除说明一致");
    }

    // ==================================================================
    // 二、已消费侧：账本说"被引用"，代码里就必须真的被引用
    // ==================================================================

    @Test
    @DisplayName("账本记「已消费」的槽位，生产代码（剥注释后）必须真的引用它 —— 账本不得骗人")
    void every_consumed_slot_is_actually_referenced_by_production_code() throws IOException {
        Map<Integer, String> declared = declaredSlots();
        Map<String, String> sources = productionSourcesStrippedOfComments();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<Integer, String> e : CONSUMED.entrySet()) {
            int no = e.getKey();
            String key = declared.get(no);
            if (key == null) {
                problems.add("#" + no + ": 账本记了它，但 seed 里没有该编号");
                continue;
            }
            boolean referencedAnywhere = sources.values().stream().anyMatch(s -> s.contains(key));
            if (!referencedAnywhere) {
                problems.add("#" + no + "（" + key + "）: 账本记为『已消费』，但生产代码里"
                        + "找不到该键字面量（已剥注释）—— 要么笔误，要么该引用被删掉了");
                continue;
            }
            // 连线自证：账本记的代表性文件必须仍含该键（文件改名 / 键改名即红）
            String rep = e.getValue();
            String repText = sources.get(rep);
            if (repText == null) {
                problems.add("#" + no + "（" + key + "）: 账本记的代表性文件不存在: " + rep);
            } else if (!repText.contains(key)) {
                problems.add("#" + no + "（" + key + "）: 代表性文件 " + rep
                        + " 里已不含该键 —— 账本与代码的连线断了");
            }
        }

        assertTrue(CONSUMED.size() >= 10,
                "已消费账目少得可疑（" + CONSUMED.size() + " 条）—— 防「账本被清空 ⇒ 循环不进 ⇒ 平凡通过」");
        assertTrue(problems.isEmpty(),
                "🛑 已消费账目与生产代码不一致:\n  - " + String.join("\n  - ", problems));
    }

    // ==================================================================
    // 三、未消费侧：账本说"没人引用"，代码里就必须真的没人引用
    //     —— 归零必须是一次显式动作
    // ==================================================================

    @Test
    @DisplayName("账本记「未消费」的槽位，生产代码（剥注释后）必须真的不引用它 —— 差异归零须显式")
    void every_unconsumed_slot_is_actually_unreferenced_by_production_code() throws IOException {
        Map<Integer, String> declared = declaredSlots();
        Map<String, String> sources = productionSourcesStrippedOfComments();

        List<String> drift = new ArrayList<>();
        for (int no : UNCONSUMED) {
            String key = declared.get(no);
            if (key == null) {
                drift.add("#" + no + ": 账本记了它，但 seed 里没有该编号");
                continue;
            }
            for (Map.Entry<String, String> s : sources.entrySet()) {
                if (s.getValue().contains(key)) {
                    drift.add("#" + no + "（" + key + "）已被 " + s.getKey()
                            + " 引用 —— 它不再是「未消费」的，请从 UNCONSUMED 移除");
                }
            }
        }

        assertTrue(UNCONSUMED.size() >= 10,
                "未消费账目少得可疑（" + UNCONSUMED.size() + " 条）—— 防「账本被清空 ⇒ 平凡通过」");
        assertTrue(drift.isEmpty(),
                "🛑 以下槽位被生产代码消费了，但仍登记为「未消费」—— 必须显式把它从本表移除：\n  - "
                        + String.join("\n  - ", drift)
                        + "\n（本断言的意义：差异的「归零」不能悄悄发生，否则账本会长期说谎）");
    }

    // ==================================================================
    // 四、🛑 注释-only 引用必须被剥离（否则账本会把"注释里提过"当成"已消费"）
    // ==================================================================

    @Test
    @DisplayName("🛑 剥注释是必要的：cfg:verdict.branch_rules(#9) 只出现在注释里，必须判为「未消费」")
    void comment_only_references_are_not_counted_as_consumption() throws IOException {
        // 本条是"判据读对了载体"的自证：若把注释也算作引用，#9 会被误判为已消费，
        // 而它其实是"已声明但运行时未消费"（与 EXCLUDED_NOTE 的口径一致）。
        Map<String, String> stripped = productionSourcesStrippedOfComments();

        String key9 = "cfg:verdict.branch_rules";
        assertFalse(stripped.values().stream().anyMatch(s -> s.contains(key9)),
                "剥注释后仍能找到 " + key9 + " —— 剥注释失效（块注释匹配可能漏了 DOTALL）");

        // 反证：原文里确实有它（只在注释里）—— 若原文也没有，说明本条前置失效
        boolean presentInRawText = false;
        for (Path p : productionJavaFiles()) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains(key9)) {
                presentInRawText = true;
                break;
            }
        }
        assertTrue(presentInRawText,
                "前置失效：原文里已找不到 " + key9 + " —— 该注释被删了，请复核本断言");
    }

    // ==================================================================
    // 五、🛑 #21 "总部唯一可改"必须【真的成立】—— 接线后由差异登记翻转为正向断言
    // ==================================================================

    @Test
    @DisplayName("🛑 #21 已接线：生产装配必须从配置注入阈值，不得再出现硬编码常量")
    void the_21_hardcoded_default_divergence_is_closed() throws IOException {
        Map<Integer, String> declared = declaredSlots();
        assertEquals("cfg:crossstore.split_threshold", declared.get(21),
                "#21 的键变了，本断言的语义前提失效");

        // 它必须已在"已消费"账上（即：有生产代码按该键去读配置）
        assertTrue(CONSUMED.containsKey(21),
                "🛑 #21 不在「已消费」账上 —— 若接线被回退，请从 CONSUMED 移回 UNCONSUMED 并同步 §5.2 登记");

        // 🛑 硬编码常量必须已消失（它是 N-14「声明总部可改、实际是常量」的事实支撑）
        Path settlement = repoRoot().resolve(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/CrossStoreSettlement.java");
        String text = stripComments(Files.readString(settlement, StandardCharsets.UTF_8));
        assertFalse(text.contains("DEFAULT_SPLIT_THRESHOLD"),
                "CrossStoreSettlement 里又出现了 DEFAULT_SPLIT_THRESHOLD 常量字段 —— "
                        + "这正是 N-14 的成因（声明总部可改、运行时其实是常量）；"
                        + "阈值必须来自 config #21，不得内部硬编码");

        // 生产装配处必须"按配置注入"，且不得再出现无参构造
        Path wiring = repoRoot().resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/config/WebConfig.java");
        String w = stripComments(Files.readString(wiring, StandardCharsets.UTF_8));
        assertTrue(w.contains("CrossStoreThresholds.fromRawConfig"),
                "WebConfig 未按配置注入阈值 —— 『总部唯一可改』会退回成一句空话；"
                        + "必须经 CrossStoreProfileSource → CrossStoreThresholds.fromRawConfig 注入");
        assertTrue(w.contains("CrossStoreProfileSource"),
                "WebConfig 未注入配置来源 —— 阈值接线被断开");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 从 seed（classpath）机械提取 {@code 编号 -> 键}；全集侧的唯一来源。 */
    private static Map<Integer, String> declaredSlots() throws IOException {
        Matcher m = Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*'(cfg:[^']+)'").matcher(seedText());
        Map<Integer, String> out = new LinkedHashMap<>();
        while (m.find()) {
            out.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return out;
    }

    private static String seedText() throws IOException {
        try (InputStream is = ConfigSlotConsumptionLedgerTest.class.getClassLoader()
                .getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(is, "classpath 上找不到 " + SEED_RESOURCE
                    + " —— 全集侧来源断开，本门禁形同虚设");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 全部生产 Java 文件（相对 skeleton 根 -> 绝对路径）。 */
    private static List<Path> productionJavaFiles() throws IOException {
        Path root = repoRoot();
        List<Path> out = new ArrayList<>();
        for (String mod : SCANNED_MODULES) {
            Path base = root.resolve(mod).resolve("src/main/java");
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (var walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".java"))
                        .forEach(out::add);
            }
        }
        assertFalse(out.isEmpty(), "未扫到任何生产 Java 文件 —— 路径解析失败，断言将平凡通过");
        return out;
    }

    /** 生产 Java 源码，<b>剥离注释</b>（保留字符串字面量），键为相对 skeleton 根的路径。 */
    private static Map<String, String> productionSourcesStrippedOfComments() throws IOException {
        Path root = repoRoot();
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : productionJavaFiles()) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            out.put(rel, stripComments(Files.readString(p, StandardCharsets.UTF_8)));
        }
        return out;
    }

    /**
     * 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>（键名就在字符串里）。
     *
     * <p>🛑 为什么不能只做正则替换：本仓的注释里大量出现中文与反引号，
     * 且 Javadoc 里逐字写着配置键（正是"注释-only 引用"）——
     * 必须真正按状态机扫，才能区分"注释里提到"与"代码里引用"。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        final int n = src.length();
        int state = 0;   // 0=code 1=line 2=block 3=string
        while (i < n) {
            char c = src.charAt(i);
            if (state == 0) {
                if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                    state = 1;
                    i += 2;
                    continue;
                }
                if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                    state = 2;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    state = 3;
                    out.append(c);
                    i++;
                    continue;
                }
                out.append(c);
                i++;
            } else if (state == 1) {
                if (c == '\n') {
                    state = 0;
                    out.append(c);
                }
                i++;
            } else if (state == 2) {
                if (c == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                    state = 0;
                    i += 2;
                    continue;
                }
                i++;
            } else {   // string
                if (c == '\\' && i + 1 < n) {
                    out.append(c);
                    i++;
                    if (i < n) {
                        out.append(src.charAt(i));
                        i++;
                    }
                    continue;
                }
                if (c == '"') {
                    state = 0;
                }
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 从 surefire 的 cwd 逐级上溯定位 skeleton 根（不依赖硬编码盘符）。 */
    private static Path repoRoot() {
        for (Path cur = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("dy-app").resolve("pom.xml"))
                    && Files.isRegularFile(cur.resolve("dy-config")
                    .resolve("src/main/resources/db/config/02_slots_seed.sql"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "未找到 skeleton 根（需含 dy-app/pom.xml 与 dy-config 的 02_slots_seed.sql）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }
}