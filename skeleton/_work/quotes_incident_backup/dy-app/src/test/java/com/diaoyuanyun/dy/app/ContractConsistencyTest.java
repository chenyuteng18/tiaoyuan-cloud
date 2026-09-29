package com.diaoyuanyun.dy.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约一致性测试 —— 断言<b>契约/字典源文件正文</b>，不比对 Java 常量与自己。
 *
 * <h2>它堵什么洞</h2>
 * 本项目 README §四 把"防自证式测试"写成纪律：断言"我写的常量 == 我写的常量"只能证明代码
 * 没变，不能证明代码<b>与契约一致</b>。故本类的唯一输入是 {@code _work/} 下的契约、数据字典
 * 与 PRD 的 Markdown 正文 —— 规则是"从正文把枚举抠出来，再比"。
 *
 * <h2>cwd 无关</h2>
 * 仓库根按三级回退解析，全部失败<b>直接红</b>（不 skip、不静默通过）：
 * <ol>
 *   <li>{@code -Ddy.docs.root=<abs>}（CI 显式喂入）；</li>
 *   <li>{@code -Ddy.repo.root=<abs>}；</li>
 *   <li>从 {@code user.dir} 起<b>逐级向上</b>最多 4 层，找同时含契约与字典的目录。</li>
 * </ol>
 * 全程 {@link Path} + {@code toAbsolutePath().normalize()}，故 Windows 的 {@code C:\a\b}
 * 与 Git Bash 的 {@code /c/a/b} 会落到同一文件。
 *
 * <h2>{@code gap_reason} 口径：裁定已落地（差异归零的显式声明）</h2>
 * <b>2026-09-23 R1 裁定</b>：契约 §3.3 冲突点 C-3 的 {@code gap_reason} 取值集已与字典 §4.4
 * <b>对齐为 7 值</b>（拼写统一 {@code compliant_removal}；{@code device_unbound} 并入
 * {@code involuntary_technical}）。故 {@link #GAP_REASON_KNOWN_DIFFERENCES} 现为<b>空表</b> ——
 * 它不是"没有登记"，而是<b>"两侧无差异"的显式声明</b>。同一张登记表在裁定前承载
 * "待裁定的现状"、裁定后承载"已归零"这一事实，选登记式表达，理由三条：
 * <ol>
 *   <li><b>裁定权不在测试。</b> 这是跨文档口径裁定，裁定前任何一侧单方面改口径都是越权。
 *       （裁定已于 2026-09-23 由技术负责人作出，见上节；此后本测试转为<b>守护</b>该裁定不被静默回退。）</li>
 *   <li><b>登记式不是恒绿摆设。</b> 登记表把差异逐条枚举：契约 / 字典 §4.4 / 字典 DDL CHECK /
 *       blocker R-10 行任一处出现<b>任何</b>改动 —— 补值、删值、甚至只修
 *       {@code compliant_removal} 的拼写 —— 本测试立刻红，并在失败信息里指名
 *       "请更新登记表并在阻塞登记册关闭对应项"。即：<b>口径变更不许静默</b>，
 *       这正是登记式要买的东西（裁定归零后，任何单边漂移都会重新变成差异 → 红）。
 *       三态反证见
 *       {@link #known_difference_registration_turns_red_when_either_side_changes}。</li>
 *   <li><b>与仓库既有惯例一致。</b> {@code RlsCoverageGateTest.ISOLATION_TESTS} 同样是
 *       "登记表 Harness"：价值不在断言本身，而在把"新增 / 变更事件"变成必须表态的动作。</li>
 * </ol>
 *
 * <h2>正向守护「行号引用纪律」元约定（v1.32）</h2>
 * PRD v1.32 已落盘元约定：<b>正文语义区一律不得写行号，引用一律用章节号 / 标题 / 条目 ID 定位</b>。
 * 理由是行号引用会<b>静默漂移</b> —— 正文中段插入内容后，引用不报错、只指向别处。
 * {@link #doc_dictionary_must_not_contain_line_number_anchors_to_md_sources} 用正则扫描字典全文，
 * 一旦出现指向 md 源文件的行号锚点（形如 {@code §4.1 L444} / {@code config #5 L1097}）即<b>断言失败</b>。
 * 该断言<b>不误伤</b>指向 Java 源码的行号（形如 {@code RowScope.java L28-35}）—— 正则限定为
 * "节号 / 文件名 + L + 数字" 的 md 源文件锚点形态。
 *
 * <h2>反向验证（证明非摆设）</h2>
 * {@link #known_difference_registration_turns_red_when_either_side_changes} 在
 * {@code @TempDir}（仓库<b>外</b>，JUnit 自动清理）造三份文档副本：
 * ① 只把契约改成 7 值 → 必须红；② 只把字典改成 5 值 → 必须红；
 * ③ 两侧对齐 → 差异为空，此时"登记表必须非空"那条断言红，迫使裁定落地时更新登记表。
 * 另有 {@link #parser_must_fail_loudly_on_documents_without_any_enum_declaration}
 * 钉住"解析不出来必须抛错，不能退化成空集静默通过"。
 */
class ContractConsistencyTest {

    // ======================================================================
    // 一、被断言的源文件（相对仓库根）
    // ======================================================================

    private static final String CONTRACT_REL = "_work/contract-t6-api-freeze-2026-09-19.md";
    private static final String DATA_DICT_REL = "_work/data-dict-entities-ddl-2026-09-19.md";
    private static final String PRD_REL = "prd-health-mgmt-saas-2026-09-16.md";
    private static final String BLOCKER_REL = "blocker-root-cause-and-today-close-2026-09-20.md";

    private static final List<String> ALL_DOCS = List.of(CONTRACT_REL, DATA_DICT_REL, PRD_REL, BLOCKER_REL);

    // ======================================================================
    // 二、ADR 十五 F-8 的登记（测试运行时逐条在源文件中核对）
    // ======================================================================

    /** 契约 §3.3 冲突点 C-3：客户端可见同步态四值（`client_sync_state` ∈ {...}）。 */
    private static final Set<String> CONTRACT_CLIENT_SYNC_STATE = Set.of(
            "syncing", "synced", "sync_failed", "no_data_today");

    /** 字典 TL;DR / §2.17 字段行 / DDL CHECK：落库侧同步态四值。 */
    private static final Set<String> DICT_STORAGE_SYNC_STATE = Set.of(
            "syncing", "synced", "sync_failed", "no_data");

    /** ADR 十五 F-8 显式映射（客户端 ↔ 落库）：只有第 4 个值需要换名。 */
    private static final Map<String, String> SYNC_STATE_EXPLICIT_MAPPING = Map.of(
            "syncing", "syncing",
            "synced", "synced",
            "sync_failed", "sync_failed",
            "no_data_today", "no_data");

    // ======================================================================
    // 三、gap_reason 三方差异的登记（2026-09-22 实测 · 未裁定）
    // ======================================================================

    /** 契约 §3.3 冲突点 C-3 的 `gap_reason` 声明：7 值（2026-09-23 R1 裁定后与字典对齐）。 */
    private static final Set<String> CONTRACT_GAP_REASON = Set.of(
            "no_open", "sync_failed", "not_worn", "compliant_removal",
            "involuntary_technical", "beyond_retention_window", "unknown");

    /** 字典 §4.4 表 与 DDL CHECK：7 值，字典内部自洽。 */
    private static final Set<String> DICT_GAP_REASON = Set.of(
            "no_open", "sync_failed", "not_worn", "compliant_removal",
            "involuntary_technical", "beyond_retention_window", "unknown");

    /** 两侧共有值：7 值对齐后 = 全集。 */
    private static final Set<String> GAP_REASON_SHARED = Set.of(
            "no_open", "sync_failed", "not_worn", "compliant_removal",
            "involuntary_technical", "beyond_retention_window", "unknown");

    /**
     * <b>{@code gap_reason} 差异登记表 —— 裁定后为「空表」（= 两侧无差异的显式声明）。</b>
     *
     * <p>2026-09-23 R1 裁定把契约 §3.3 的 5 值改为字典的 7 值、拼写统一
     * {@code compliant_removal}、{@code device_unbound} 退役（并入 {@code involuntary_technical}），
     * 两侧取值集已完全对齐 ⇒ 差异集合为空。
     *
     * <p>🛑 <b>空表不是"没登记"</b>：{@link #gap_reason_three_way_comparison_matches_the_registered_difference_table}
     * 同时断言"差异为空"AND"A10 三源各自等于 7 值常量"。因此任一源此后发生
     * <b>单边漂移</b>（补值 / 删值 / 改拼写），差异会重新非空 → 与空表不符 → <b>立刻红</b>。
     * 登记表从"承载待裁决差异"转为"承载已归零声明"，守护力不变。
     */
    private static final List<RegisteredDifference> GAP_REASON_KNOWN_DIFFERENCES = List.of();

    /** blocker R-10 行原文中的声称片段（用于核对「该行描述是否与契约实测相符」）。 */
    private static final String R10_CLAIM_FRAGMENT = "7 值英文枚举";

    // ======================================================================
    // 四、测试
    // ======================================================================

    /**
     * A10-① {@code sync_state} 映射：契约（客户端）↔ 字典（落库）两侧值集必须对齐，
     * 且差异只有 {@code no_data_today ↔ no_data} 一处（ADR 十五 F-8）。
     */
    @Test
    void sync_state_client_and_storage_value_sets_align_under_the_explicit_mapping() throws IOException {
        SourceSet docs = SourceSet.load(resolveRepoRoot());

        Set<String> contractClient = docs.contractClientSyncState();
        Set<String> dictStorage = docs.dictStorageSyncState();

        assertEquals(CONTRACT_CLIENT_SYNC_STATE, contractClient,
                "契约 §3.3 冲突点 C-3 的 `client_sync_state` 值集与登记不符（改动契约须同步更新本类常量）");
        assertEquals(DICT_STORAGE_SYNC_STATE, dictStorage,
                "字典 TL;DR 的 `sync_state ∈ {...}` 落库值集与登记不符");

        assertEquals(contractClient.size(), dictStorage.size(),
                "两侧同步态值数必须一致，否则映射不可能是一一对应");

        Map<String, String> derived = deriveByPosition(contractClient, dictStorage);
        assertEquals(SYNC_STATE_EXPLICIT_MAPPING, derived,
                "客户端 ↔ 落库的显式映射与 ADR 十五 F-8 登记不符");
        assertEquals(contractClient, derived.keySet(), "映射必须覆盖契约侧全集");
        assertEquals(dictStorage, new TreeSet<>(derived.values()), "映射必须覆盖落库侧全集");

        // 字典必须把两套写法都写出来，并冠以"显式映射"（F-8 明令，不是靠读者推断）
        assertEquals(Map.of("no_data", "storage", "no_data_today", "client"), docs.dictExplicitMappingAnchors(),
                "字典 §2.17 字段行必须同时出现落库 `no_data` 与契约 `no_data_today`，且写明二者显式映射");
        assertTrue(docs.dataDict().contains("显式映射"),
                "字典必须出现『显式映射』字样作为口径锚点（ADR 十五 F-8）");

        // 契约侧：客户端枚举必须不含"未佩戴"类语义（冲突点 C-3 的硬约束）
        assertTrue(contractClient.stream().noneMatch(v -> v.contains("not_worn") || v.contains("worn")),
                "客户端 `client_sync_state` 不得含未佩戴语义（契约冲突点 C-3）");

        // 落库侧：字典 DDL CHECK 必须与字段行登记一致（字典内部先自洽）
        assertEquals(dictStorage, docs.dictDdlSyncStateCheck(),
                "字典 DDL `sync_state` CHECK 与字段行登记不一致 —— 字典内部先不自洽");

        System.out.println("[A10-SYNC-STATE] contract(client)=" + new TreeSet<>(contractClient)
                + " dict(storage)=" + new TreeSet<>(dictStorage)
                + " mapping=" + new TreeSet<>(SYNC_STATE_EXPLICIT_MAPPING.keySet()) + " -> ALIGNED");
    }

    /**
     * A10-② {@code gap_reason} 三方对照：核对差异，并断言"实测差异 == 登记差异"。
     *
     * <p>三源：契约 §3.3 冲突点 C-3 / 字典 §4.4 表 / 字典 DDL CHECK；旁证：PRD §2.8.7 与 blocker R-10 行。
     */
    @Test
    void gap_reason_three_way_comparison_matches_the_registered_difference_table() throws IOException {
        SourceSet docs = SourceSet.load(resolveRepoRoot());
        GapReasonFacts f = docs.gapReasonFacts();

        // ---- 逐源核对"实测 vs 登记"（三源均须 = 7 值，拼写 compliant_removal）----
        assertEquals(CONTRACT_GAP_REASON, f.contractValues(),
                "契约 §3.3 冲突点 C-3 的 `gap_reason` 值集与登记不符 —— "
                        + "2026-09-23 R1 裁定已定为 7 值 + `compliant_removal`；"
                        + "若此处再出现 5 值 / `compliance_removal` / `device_unbound`，即为裁定被回退");
        assertEquals(DICT_GAP_REASON, f.dictSection44Values(), "字典 §4.4 枚举表的值集与登记不符");
        assertEquals(DICT_GAP_REASON, f.dictDdlCheckValues(), "字典 DDL CHECK 的值集与登记不符");
        assertEquals(f.dictSection44Values(), f.dictDdlCheckValues(),
                "字典内部不自洽：§4.4 枚举表 与 DDL CHECK 值集必须一致");

        // ---- 差异必须与登记表逐条一致（多一条 / 少一条 / 拼写变了都红）----
        List<RegisteredDifference> actual = computeDifferences(f.contractValues(), f.dictSection44Values());
        assertEquals(diffKeys(sortDiffs(GAP_REASON_KNOWN_DIFFERENCES)), diffKeys(actual),
                "gap_reason 的实测差异与登记表不一致。\n"
                        + "⇒ R1 裁定已把两侧对齐为 7 值，登记表只应为空；实测出现差异说明"
                        + "某一侧发生了【单边漂移】（补值 / 删值 / 改拼写）；\n"
                        + "⇒ 若这是有意的新裁定：请更新 "
                        + ContractConsistencyTest.class.getSimpleName() + ".GAP_REASON_KNOWN_DIFFERENCES，"
                        + "并在 _work/blocker-register-unified-2026-09-22.md 关闭对应项。");

        // ---- 差异【空】是 R1 裁定落地后的期望状态（不是"没登记"）----
        assertTrue(actual.isEmpty(),
                "R1 裁定（2026-09-23）已把契约 §3.3 与字典 §4.4 的 `gap_reason` 对齐为同一 7 值集，"
                        + "差异集合必须为空，实测: " + actual);

        // ---- 共有值必须真的共有 ----
        Set<String> inter = new TreeSet<>(f.contractValues());
        inter.retainAll(f.dictSection44Values());
        assertEquals(GAP_REASON_SHARED, inter, "两侧共有值与登记不符");

        // ---- PRD：实测【不声明】值级枚举，只声明三类语义 —— 钉住这一事实 ----
        assertFalse(f.prdDeclaresGapReasonValueEnum(),
                "PRD 出现了 `gap_reason ∈ {...}` 形式的值级枚举 —— 这是<b>新事实</b>，"
                        + "必须登记进三方对照并核对与契约 / 字典的关系");
        for (String marker : List.of("未触发", "触发但同步失败", "同步成功但确实没戴")) {
            assertTrue(f.prdText().contains(marker), "PRD §2.8.7② 三类失败语义锚点缺失: " + marker);
        }
        // PRD 仅点名 Q-W3 的两个值（`no_open` / `sync_failed`），其余靠语义描述
        assertTrue(f.prdText().contains("no_open") && f.prdText().contains("sync_failed"),
                "PRD 应至少点名 `no_open` / `sync_failed`（Q-W3 锚点）");
        // PRD 里的 `unknown` 只以 `missing_reason=unknown` 形态出现 —— 与 `gap_reason=unknown` 是否同一字段未裁定
        assertTrue(f.prdContainsMissingReasonUnknown(),
                "PRD 应出现 `missing_reason` / `unknown`（§2.8.7⑤ C2 保守记账），"
                        + "这是与字典 `gap_reason=unknown` 口径关系的核对点");
        assertTrue(f.prdText().contains("device_unbind"),
                "PRD §2.8.7⑤ 用 `device_unbind`（动词短语）而非契约的 `device_unbound`（枚举名）—— 钉住这处措辞差异");

        // ---- blocker R-10 行：R-10 裁「以契约 7 值英文枚举为准」—— R1 落地后该声称【成立】 ----
        assertTrue(f.blockerText().contains(R10_CLAIM_FRAGMENT),
                "blocker R-10 行的原文声称片段缺失 —— 该行若被改写，请同步更新本类登记");
        assertEquals(7, f.contractValues().size(),
                "R1 裁定（2026-09-23）已把契约改为 7 值 —— 此时 blocker R-10『以契约 7 值英文枚举为准』"
                        + "的声称与实测【相符】，R-10 可关闭；若此处再变成 5 值，说明 R1 被回退");

        System.out.println("[A10-GAP-REASON] contract(" + f.contractValues().size() + ")="
                + new TreeSet<>(f.contractValues()));
        System.out.println("[A10-GAP-REASON] dict-section-4.4(" + f.dictSection44Values().size() + ")="
                + new TreeSet<>(f.dictSection44Values()));
        System.out.println("[A10-GAP-REASON] dict-ddl-check(" + f.dictDdlCheckValues().size() + ")="
                + new TreeSet<>(f.dictDdlCheckValues()));
        System.out.println("[A10-GAP-REASON] PRD declares value enum=" + f.prdDeclaresGapReasonValueEnum());
        System.out.println("[A10-GAP-REASON] registered differences=" + actual.size() + " -> " + actual);
        System.out.println("[A10-GAP-REASON] blocker R-10 claims 'contract 7 values' while contract has "
                + f.contractValues().size() + " -> claim MATCHES measurement (R-10 closable)");
    }

    /**
     * <b>正向守护 v1.32 元约定</b>：数据字典正文<b>不得</b>含指向 md 源文件的行号锚点。
     *
     * <p>元约定原文（PRD v1.32 · 配置表末【统一机制】后的〔行号引用纪律〕）：
     * <b>正文语义区一律不得写行号，引用一律用章节号 / 标题 / 条目 ID 定位</b>。
     * 理由是行号引用会<b>静默漂移</b> —— 正文中段插入章节后，引用不报错、只指向别处
     * （本项目已实际发生：契约 §2.9 域 I 插入 43 行后，字典里的 {@code §4.1 L444·L466}
     * / {@code §4.1 L447} 全部失效）。
     *
     * <p>扫描规则（正则）：命中 "节号/文件名 + L + 数字" 形态，例如
     * {@code §4.1 L444} / {@code contract §4.1 L447} / {@code config #5 L1097} /
     * {@code RowScope.java L28-35}。注意<b>指向 Java 源码的行号亦一并禁止</b> ——
     * 它们与 md 源文件行号同样会漂移，纪律不区分载体。
     *
     * <p>本测试<b>不是恒绿摆设</b>：注入任一 md 源文件行号锚点即红（反向验证见任务记录）。
     */
    @Test
    void doc_dictionary_must_not_contain_line_number_anchors_to_md_sources() throws IOException {
        SourceSet docs = SourceSet.load(resolveRepoRoot());
        List<String> hits = docs.mdSourceLineAnchorsInDataDict();
        assertTrue(hits.isEmpty(),
                "违反 v1.32 元约定：正文语义区不得写行号，请改用章节号 / 条目 ID 定位。\n"
                        + "数据字典里出现以下行号锚点（会随正文插入静默漂移）：" + hits);
        System.out.println("[A10-ANCHOR] 字典行号锚点扫描 -> 0 命中（v1.32 行号引用纪律）");
    }

    /**
     * <b>反向验证</b>：证明上面两条断言真的在读文件、真的会翻转 —— 不是恒绿摆设。
     *
     * <p>在 {@code @TempDir}（仓库外，自动清理）造三态，各断言其"必须红 / 必须绿"的<b>那一条</b>。
     * <b>R1 裁定（2026-09-23）后，基线 = 两侧对齐（7 值 + {@code compliant_removal}）</b>，
     * 故三态改为「单边漂移必须红」：
     * <ol>
     *   <li>把契约改回 5 值（模拟"有人把 R1 回退"）→ 差异断言必须红；</li>
     *   <li>只把字典改成 5 值（模拟"单边改字典"）→ 差异断言必须红；</li>
     *   <li>把契约的 {@code compliant_removal} 改回旧拼写 {@code compliance_removal}
     *       （数值仍 7 值、只错拼写）→ 差异断言必须红（证明"连笔误都抓得住"）。</li>
     * </ol>
     */
    @Test
    void known_difference_registration_turns_red_when_either_side_changes(@TempDir Path tmp) throws IOException {
        Path realRoot = resolveRepoRoot();
        SourceSet real = SourceSet.load(realRoot);

        // 基线：R1 裁定后两侧应对齐（差异为空，与空登记表一致）
        assertEquals(diffKeys(sortDiffs(GAP_REASON_KNOWN_DIFFERENCES)),
                diffKeys(computeDifferences(real.gapReasonFacts().contractValues(),
                        real.gapReasonFacts().dictSection44Values())),
                "基线上登记表必须与实测一致 —— 否则下面的三态实验失去意义");
        assertTrue(real.gapReasonFacts().contractValues().equals(real.gapReasonFacts().dictSection44Values()),
                "R1 裁定后契约与字典的 `gap_reason` 值集必须相等（差异归零）");

        // R1 裁定前的旧 5 值集（含旧拼写），用于模拟"回退"
        Set<String> legacyFive = new TreeSet<>(List.of(
                "no_open", "sync_failed", "not_worn", "device_unbound", "compliance_removal"));

        // ---- 态 1：把契约改回 5 值（模拟 R1 被回退）----
        Path root1 = copyDocsTo(tmp.resolve("case1"), realRoot);
        rewriteContractGapReason(root1, legacyFive);
        GapReasonFacts f1 = SourceSet.load(root1).gapReasonFacts();
        assertEquals(legacyFive, new TreeSet<>(f1.contractValues()),
                "改写后的契约应读出 5 值（证明解析确实在重读文件，而非缓存）");
        List<RegisteredDifference> d1 = computeDifferences(f1.contractValues(), f1.dictSection44Values());
        assertNotEquals(diffKeys(sortDiffs(GAP_REASON_KNOWN_DIFFERENCES)), diffKeys(d1),
                "【态 1 必须红】契约被改回 5 值后，实测差异必须与空登记表不同 —— "
                        + "否则说明差异断言根本没读契约文件（恒绿摆设）");
        assertFalse(d1.isEmpty(), "态 1 的差异集合不应为空（5 值 vs 7 值）");
        System.out.println("[A10-RED-1] contract reverted to 5 values -> differences=" + d1
                + "  (registered=" + GAP_REASON_KNOWN_DIFFERENCES.size() + ") MISMATCH, as expected");

        // ---- 态 2：只把字典 §4.4 + DDL 改成 5 值 ----
        Path root2 = copyDocsTo(tmp.resolve("case2"), realRoot);
        Set<String> dictFive = new TreeSet<>(List.of(
                "no_open", "sync_failed", "not_worn", "device_unbound", "compliant_removal"));
        rewriteDictGapReason(root2, dictFive, "compliant_removal");
        GapReasonFacts f2 = SourceSet.load(root2).gapReasonFacts();
        assertEquals(dictFive, new TreeSet<>(f2.dictSection44Values()),
                "改写后的字典应读出 5 值");
        assertEquals(dictFive, new TreeSet<>(f2.dictDdlCheckValues()),
                "改写后的字典 DDL 应与 §4.4 同步（字典内部保持自洽）");
        List<RegisteredDifference> d2 = computeDifferences(f2.contractValues(), f2.dictSection44Values());
        assertNotEquals(diffKeys(sortDiffs(GAP_REASON_KNOWN_DIFFERENCES)), diffKeys(d2),
                "【态 2 必须红】只把字典改成 5 值后，实测差异必须与空登记表不同");
        assertFalse(d2.isEmpty(), "态 2 的差异集合不应为空（契约 7 值 vs 字典 5 值）");
        System.out.println("[A10-RED-2] dict-only-changed to 5 values -> differences=" + d2
                + "  (registered=" + GAP_REASON_KNOWN_DIFFERENCES.size() + ") MISMATCH, as expected");

        // ---- 态 3：两侧仍 7 值，只把契约的拼写改回旧的 `compliance_removal` ----
        Path root3 = copyDocsTo(tmp.resolve("case3"), realRoot);
        Set<String> sevenValuesLegacySpelling = new TreeSet<>(List.of(
                "no_open", "sync_failed", "not_worn", "compliance_removal",
                "involuntary_technical", "beyond_retention_window", "unknown"));
        rewriteContractGapReason(root3, sevenValuesLegacySpelling);
        GapReasonFacts f3 = SourceSet.load(root3).gapReasonFacts();
        assertEquals(sevenValuesLegacySpelling, new TreeSet<>(f3.contractValues()),
                "态 3 契约应为 7 值 + 旧拼写");
        List<RegisteredDifference> d3 = computeDifferences(f3.contractValues(), f3.dictSection44Values());
        assertNotEquals(diffKeys(sortDiffs(GAP_REASON_KNOWN_DIFFERENCES)), diffKeys(d3),
                "【态 3 必须红】只改拼写（数值仍 7 值）也必须被抓到 —— "
                        + "否则说明拼写回归无人守护");
        System.out.println("[A10-RED-3] contract spelling regressed -> differences=" + d3
                + "  (registered=" + GAP_REASON_KNOWN_DIFFERENCES.size() + ") MISMATCH, as expected");

        // 三态输入确实不同，排除"三次其实在读同一份文件"：
        //   态 1（契约 5 值）↔ 态 3（契约 7 值）↔ 态 2（契约未改）
        assertNotEquals(f1.contractValues(), f3.contractValues(), "态 1 与态 3 的契约输入必须不同");
        assertNotEquals(f2.contractValues(), f3.contractValues(), "态 2 与态 3 的契约输入必须不同");

        // 临时探针纪律：副本必须落在系统临时目录内（仓库外）
        assertTrue(tmp.toAbsolutePath().normalize().startsWith(tmpRoot()),
                "反向验证副本必须在系统临时目录内（仓库外），实际: " + tmp);
        assertFalse(Files.exists(realRoot.resolve("_work/contract-diff-probe")),
                "仓库内不得留下任何探针残留");
    }

    /**
     * 解析器必须"解析不出来就红"，不能"解析不到 → 空集 → 断言碰巧通过"。
     *
     * <p>用一个<b>故意不含任何枚举声明</b>的临时目录喂进去，断言解析必须抛
     * {@link AssertionError}（而不是返回空集静默通过）。
     */
    @Test
    void parser_must_fail_loudly_on_documents_without_any_enum_declaration(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("empty-docs").toAbsolutePath().normalize();
        for (String rel : ALL_DOCS) {
            Path p = root.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "(空文档，无任何枚举声明)\n", StandardCharsets.UTF_8);
        }
        SourceSet docs = SourceSet.load(root);
        assertThrows(AssertionError.class, docs::contractClientSyncState,
                "契约里没有 `client_sync_state ∈ {...}` 时必须抛错，不能返回空集");
        assertThrows(AssertionError.class, docs::dictStorageSyncState,
                "字典里没有 `sync_state ∈ {...}` 时必须抛错");
        assertThrows(AssertionError.class, docs::gapReasonFacts,
                "文档里没有 `gap_reason` 枚举时必须抛错");
        assertThrows(AssertionError.class, () -> SourceSet.load(tmp.resolve("does-not-exist")),
                "缺失源文件时必须抛错，不能静默通过");
        System.out.println("[A10-PARSER] empty documents -> AssertionError "
                + "(loud failure, never a silent empty set)");
    }

    // ======================================================================
    // 五、差异登记模型
    // ======================================================================

    private enum Kind { CONTRACT_ONLY, DICT_ONLY, SPELLING }

    /** 一条已登记的三方差异；某一侧为 {@code null} 表示该侧不存在此值。 */
    private record RegisteredDifference(Kind kind, String contractValue, String dictValue, String note) {
        @Override
        public String toString() {
            return switch (kind) {
                case CONTRACT_ONLY -> "契约独有:" + contractValue;
                case DICT_ONLY -> "字典独有:" + dictValue;
                case SPELLING -> "拼写差异:" + contractValue + "(契约) vs " + dictValue + "(字典)";
            };
        }
    }

    /**
     * <b>拼写别名登记</b>：契约名 → 字典名。用于把"同一语义、两种拼写"与
     * "两个互不相关的单边值"区分开。
     *
     * <p>🛑 这张表<b>必须显式登记</b>：若不登记，{@code compliance_removal} 与
     * {@code compliant_removal} 会各自被算成一条"单边独有"，从而掩盖"这是同一语义的拼写分歧"。
     * 反过来说，任何<b>未被登记的新拼写变体</b>都会以两条单边差异的形式出现 → 红。
     */
    private static final Map<String, String> SPELLING_ALIASES = Map.of(
            "compliance_removal", "compliant_removal");

    private static List<RegisteredDifference> computeDifferences(Set<String> contract, Set<String> dict) {
        List<RegisteredDifference> diffs = new ArrayList<>();
        Set<String> contractOnly = new LinkedHashSet<>(contract);
        contractOnly.removeAll(dict);
        Set<String> dictOnly = new LinkedHashSet<>(dict);
        dictOnly.removeAll(contract);

        // 先摘出已登记的拼写对，剩下的才是真正的单边独有
        for (Map.Entry<String, String> alias : SPELLING_ALIASES.entrySet()) {
            if (contractOnly.remove(alias.getKey())) {
                if (dictOnly.remove(alias.getValue())) {
                    diffs.add(new RegisteredDifference(Kind.SPELLING, alias.getKey(), alias.getValue(), ""));
                } else {
                    // 契约侧有该拼写、字典侧没有登记的那个拼写 —— 说明拼写被改过，放回单边集合
                    contractOnly.add(alias.getKey());
                }
            }
        }

        contractOnly.forEach(v -> diffs.add(new RegisteredDifference(Kind.CONTRACT_ONLY, v, null, "")));
        dictOnly.forEach(v -> diffs.add(new RegisteredDifference(Kind.DICT_ONLY, null, v, "")));
        return sortDiffs(diffs);
    }

    private static List<RegisteredDifference> sortDiffs(List<RegisteredDifference> in) {
        return in.stream()
                .sorted(Comparator.comparing((RegisteredDifference d) -> d.kind().name())
                        .thenComparing(d -> String.valueOf(d.contractValue()))
                        .thenComparing(d -> String.valueOf(d.dictValue())))
                .toList();
    }

    /**
     * 比对用投影：只取 (kind, 契约值, 字典值) 三元组，<b>不比 {@code note}</b>。
     *
     * <p>为什么：登记表里的 {@code note} 是给人看的解释文本（"字典独有；契约不含…"），
     * 而运行期算出来的差异没有 note。若把 note 纳入相等性，比对会因"说明文字不同"
     * 而恒红 —— 那是断言写错了，不是口径不一致。<b>被断言的是差异集合本身</b>，
     * 说明文字由 code review 保证其准确性。
     */
    private static List<String> diffKeys(List<RegisteredDifference> diffs) {
        return diffs.stream().map(d -> d.kind() + "|" + d.contractValue() + "|" + d.dictValue()).toList();
    }

    /** 按枚举书写的<b>次序</b>把两侧值一一配上（两侧基数已断言相等）。 */
    private static Map<String, String> deriveByPosition(Set<String> contract, Set<String> dict) {
        List<String> left = List.copyOf(contract);
        List<String> right = List.copyOf(dict);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < left.size(); i++) {
            map.put(left.get(i), right.get(i));
        }
        return map;
    }

    // ======================================================================
    // 六、源文件解析（读真实 Markdown，不做 Java 常量自证）
    // ======================================================================

    /** 一次加载四份文档，供各断言复用。 */
    private static final class SourceSet {

        private final Path root;
        private final String contract;
        private final String dataDict;
        private final String prd;
        private final String blocker;

        private SourceSet(Path root, String contract, String dataDict, String prd, String blocker) {
            this.root = root;
            this.contract = contract;
            this.dataDict = dataDict;
            this.prd = prd;
            this.blocker = blocker;
        }

        static SourceSet load(Path root) throws IOException {
            Path normalized = root.toAbsolutePath().normalize();
            return new SourceSet(
                    normalized,
                    read(normalized, CONTRACT_REL),
                    read(normalized, DATA_DICT_REL),
                    read(normalized, PRD_REL),
                    read(normalized, BLOCKER_REL));
        }

        private static String read(Path root, String rel) throws IOException {
            Path file = root.resolve(rel.replace('/', File.separatorChar));
            assertTrue(Files.isRegularFile(file), "源文件必须存在（缺文件不得静默通过）: " + file);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(text.isBlank(), "源文件不得为空: " + file);
            return text;
        }

        String contract() { return contract; }

        String dataDict() { return dataDict; }

        String prdText() { return prd; }

        String blockerText() { return blocker; }

        Path root() { return root; }

        /** 契约：客户端可见 `` `client_sync_state` ∈ {`a`,`b`,...} ``。 */
        Set<String> contractClientSyncState() {
            return backtickedValues(clientSyncStateDecl, contract, "契约 client_sync_state 枚举声明");
        }

        /** 字典 TL;DR：`` `sync_state ∈ {syncing, synced, sync_failed, no_data}` ``（值不带反引号）。 */
        Set<String> dictStorageSyncState() {
            Matcher m = dictSyncStateDecl.matcher(dataDict);
            assertTrue(m.find(), "解析失败（未匹配到字典 sync_state 枚举声明）—— 解析不出来必须红，"
                    + "绝不能返回空集静默通过");
            Set<String> values = splitLooseList(m.group(1));
            assertFalse(values.isEmpty(), "字典 sync_state 枚举里一个值都没抠出来: " + m.group(1));
            return values;
        }

        /** 字典 §2.17 字段行：必须同时给出落库 `no_data` 与契约 `no_data_today`，且写明显式映射。 */
        Map<String, String> dictExplicitMappingAnchors() {
            Matcher m = dictExplicitMapping.matcher(dataDict);
            assertTrue(m.find(), "找不到字典 §2.17 的显式映射声明（`落库用 no_data；面向客户端契约为 "
                    + "no_data_today，二者显式映射`）—— 解析不出来必须红");
            Map<String, String> anchors = new LinkedHashMap<>();
            anchors.put(m.group(1), "storage");
            anchors.put(m.group(2), "client");
            return anchors;
        }

        /** 字典 DDL：`CHECK (sync_state IN ('...','...'))`。 */
        Set<String> dictDdlSyncStateCheck() {
            return quotedValues(dictDdlSyncStateCheck, dataDict, "字典 DDL sync_state CHECK");
        }

        /**
         * 扫描字典全文，返回<b>指向源文件的行号锚点</b>（违反 v1.32 行号引用纪律）。
         *
         * <p>命中形态："节号 / 文件名 + {@code L} + 数字"，例如 {@code §4.1 L444}、
         * {@code contract §4.1 L447}、{@code config #5 L1097}、{@code RowScope.java L28-35}。
         * 返回空列表 = 元约定被遵守。
         *
         * <p>为什么限定这种形态：{@code L} + 数字 单看会误伤正文里的 {@code mmol/L}、
         * {@code GTL1} 等词，故要求 {@code L} 前后是"引用分隔 / 文件名 / 节号"——
         * 即 {@code L} 前须有空格、反引号、斜杠、句点或 {@code #}，且后须紧跟数字。
         */
        List<String> mdSourceLineAnchorsInDataDict() {
            List<String> hits = new ArrayList<>();
            Matcher m = mdSourceLineAnchor.matcher(dataDict);
            while (m.find()) {
                hits.add(m.group(0).trim());
            }
            return hits;
        }

        GapReasonFacts gapReasonFacts() {
            Set<String> contractVals = backtickedValues(contractGapReasonDecl, contract,
                    "契约 gap_reason 枚举声明（§3.3 冲突点 C-3）");

            // 字典 §4.4：只在 "### 4.4 " 与下一个 "### 4.5 " 之间取表行首的 `值`
            String section = between(dataDict, "### 4.4 ", "### 4.5 ");
            Set<String> sectionVals = tableRowValues(section);

            Set<String> ddlVals = quotedValues(dictDdlGapReasonCheck, dataDict, "字典 DDL gap_reason CHECK");

            boolean prdDeclares = gapReasonBraceDecl.matcher(prd).find();
            boolean prdMissingReasonUnknown = prd.contains("missing_reason") && prd.contains("unknown");
            return new GapReasonFacts(contractVals, sectionVals, ddlVals,
                    prdDeclares, prdMissingReasonUnknown, prd, blocker);
        }
    }

    /** 三方对照的事实集合。 */
    private record GapReasonFacts(
            Set<String> contractValues,
            Set<String> dictSection44Values,
            Set<String> dictDdlCheckValues,
            boolean prdDeclaresGapReasonValueEnum,
            boolean prdContainsMissingReasonUnknown,
            String prdText,
            String blockerText) {
    }

    // ---- 正则（全部围绕正文写法，不依赖固定行号）----

    private static final Pattern clientSyncStateDecl = Pattern.compile(
            "客户端可见\\s*`?client_sync_state`?\\s*∈\\s*\\{([^}]*)\\}");

    private static final Pattern dictSyncStateDecl = Pattern.compile(
            "`sync_state\\s*∈\\s*\\{([^}]*)\\}");

    private static final Pattern dictExplicitMapping = Pattern.compile(
            "落库用\\s*`([A-Za-z_][A-Za-z0-9_]*)`[^。]{0,40}面向客户端契约为\\s*`([A-Za-z_][A-Za-z0-9_]*)`");

    private static final Pattern dictDdlSyncStateCheck = Pattern.compile(
            "sync_state\\s+text[^;)]*?CHECK\\s*\\(\\s*sync_state\\s+IN\\s*\\(([^)]*)\\)", Pattern.DOTALL);

    private static final Pattern contractGapReasonDecl = Pattern.compile(
            "内部\\s*`?gap_reason`?\\s*∈\\s*\\{([^}]*)\\}");

    private static final Pattern dictDdlGapReasonCheck = Pattern.compile(
            "gap_reason\\s+text\\s+CHECK\\s*\\(\\s*gap_reason\\s+IN\\s*\\(([^)]*)\\)", Pattern.DOTALL);

    /** 判定 PRD 是否声明值级枚举（用于钉住"PRD 没有"这一事实）。 */
    private static final Pattern gapReasonBraceDecl = Pattern.compile("gap_reason\\s*`?\\s*∈\\s*\\{");

    /**
     * 指向源文件的行号锚点（违反 v1.32 行号引用纪律）。
     *
     * <p>形态："{@code L} + 数字"，且 {@code L} <b>前面</b>是引用分隔 / 节号 / 文件名
     * —— 空格、反引号、斜杠、句点或 {@code #} 之一；<b>后面</b>紧跟数字（可带范围 {@code -35}）。
     * 例：{@code §4.1 L444}、{@code contract §4.1 L447}、{@code config #5 L1097}、
     * {@code RowScope.java L28-35}。
     *
     * <p>该"前导字符"要求用于<b>排除</b>正文里不含行号语义的 {@code mmol/L} / {@code GTL1}：
     * 前者 {@code L} 前是 {@code /} 但后非数字、后者的 {@code L} 后是 {@code 1} 但前非分隔符
     * （{@code GT} 是字母）—— 故两者均不命中。
     */
    private static final Pattern mdSourceLineAnchor =
            Pattern.compile("[`/.#\\s]L\\d+(?:\\s*[-~·]\\s*\\d+)*(?![0-9A-Za-z_])");

    // ---- 解析辅助 ----

    /** 段落截取：{@code from}（含）到 {@code to}（不含）。任一端找不到 → 红。 */
    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        assertTrue(a >= 0, "找不到段落起点: " + from);
        int b = text.indexOf(to, a);
        assertTrue(b > a, "找不到段落终点: " + to + "（起点之后）");
        return text.substring(a, b);
    }

    /** 从 `` `a`,`b` `` 形态里抠出反引号内的标识符。抠不到 → 红。 */
    private static Set<String> backtickedValues(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "解析失败（未匹配到枚举声明）: " + what
                + " —— 解析不出来必须红，绝不能返回空集静默通过");
        Set<String> values = new TreeSet<>();
        Matcher token = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*)`").matcher(m.group(1));
        while (token.find()) {
            values.add(token.group(1));
        }
        assertFalse(values.isEmpty(), "枚举声明里一个值都没抠出来: " + what + " 原文=" + m.group(1));
        return values;
    }

    /** 从 `('a','b')` 形态（DDL CHECK，无反引号）里抠出单引号内的标识符。 */
    private static Set<String> quotedValues(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "解析失败（未匹配到 DDL CHECK）: " + what);
        Set<String> values = new TreeSet<>();
        Matcher token = Pattern.compile("'([A-Za-z_][A-Za-z0-9_]*)'").matcher(m.group(1));
        while (token.find()) {
            values.add(token.group(1));
        }
        assertFalse(values.isEmpty(), "DDL CHECK 里一个值都没抠出来: " + what + " 原文=" + m.group(1));
        return values;
    }

    /** 把 `a, b, c` / `a / b` / `a|b` 这类松散列表切成值集合（去反引号与空白）。 */
    private static Set<String> splitLooseList(String raw) {
        Set<String> values = new TreeSet<>();
        for (String piece : raw.split("[,/|]")) {
            String v = piece.replace("`", "").trim();
            if (v.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                values.add(v);
            }
        }
        return values;
    }

    /** 从 Markdown 表格行首的 `` | `value` | `` 抠出枚举值。 */
    private static Set<String> tableRowValues(String section) {
        Set<String> values = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*`([A-Za-z_][A-Za-z0-9_]*)`\\s*\\|").matcher(section);
        while (m.find()) {
            values.add(m.group(1));
        }
        assertFalse(values.isEmpty(), "§4.4 表里一行枚举都没解析出来");
        return values;
    }

    // ======================================================================
    // 七、仓库根解析（cwd 无关）与临时副本操作
    // ======================================================================

    /** 仓库根（= 含 {@code _work/} 与 PRD 的那一层，即 {@code product-strategy}）。三级回退，全败 → 红。 */
    private static Path resolveRepoRoot() {
        for (String key : List.of("dy.docs.root", "dy.repo.root")) {
            String explicit = System.getProperty(key);
            if (explicit != null && !explicit.isBlank()) {
                Path p = Path.of(explicit).toAbsolutePath().normalize();
                assertTrue(isRepoRoot(p), "-D" + key + " 指向的不是仓库根: " + p);
                return p;
            }
        }
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && cursor != null; i++, cursor = cursor.getParent()) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
        }
        throw new AssertionError(
                "无法定位仓库根（需同时存在 " + CONTRACT_REL + " 与 " + DATA_DICT_REL + "）。"
                        + "请用 -Ddy.docs.root=<abs> 显式指定。cwd=" + Path.of("").toAbsolutePath());
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isRegularFile(p.resolve(CONTRACT_REL.replace('/', File.separatorChar)))
                && Files.isRegularFile(p.resolve(DATA_DICT_REL.replace('/', File.separatorChar)));
    }

    private static Path tmpRoot() {
        String t = System.getProperty("java.io.tmpdir");
        assertFalse(t == null || t.isBlank(), "java.io.tmpdir 必须可用");
        return Path.of(t).toAbsolutePath().normalize();
    }

    /** 把四份只读源文档复制到临时目录，保持相对路径结构。 */
    private static Path copyDocsTo(Path dest, Path srcRoot) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        for (String rel : ALL_DOCS) {
            Path from = srcRoot.resolve(rel.replace('/', File.separatorChar));
            Path to = root.resolve(rel.replace('/', File.separatorChar));
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        return root;
    }

    /** 改写契约里 `` 内部 `gap_reason` ∈ {...} `` 这一行，其余内容不动；改完自证能读出目标值集。 */
    private static void rewriteContractGapReason(Path root, Set<String> values) throws IOException {
        Path file = root.resolve(CONTRACT_REL);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = contractGapReasonDecl.matcher(text);
        assertTrue(m.find(), "改写前必须能找到契约的 gap_reason 声明行: " + file);
        String before = m.group(0);
        String after = "内部 `gap_reason` ∈ {" + join(values, "`") + "}";
        replaceOnce(file, before, after);
        assertEquals(new TreeSet<>(values), SourceSet.load(root).gapReasonFacts().contractValues(),
                "契约改写后重读的值集必须等于期望值集（改写确实生效）");
    }

    /**
     * 改写字典 §4.4 表与 DDL CHECK，使两者都用给定值集（{@code complianceSpelling} 指定拼写）；
     * 改完自证 §4.4 与 DDL 都能读出目标值集。
     */
    private static void rewriteDictGapReason(Path root, Set<String> values, String complianceSpelling)
            throws IOException {
        Path file = root.resolve(DATA_DICT_REL);
        String text = Files.readString(file, StandardCharsets.UTF_8);

        // ① §4.4 表体整体重建（表头保留，行按新值集生成；与 DDL 同步改，保持字典内部自洽）
        String oldSection = between(text, "### 4.4 ", "### 4.5 ");
        StringBuilder rows = new StringBuilder();
        for (String v : values) {
            rows.append("| `").append(spell(v, complianceSpelling)).append("` | 改写占位 | — | — | 否 |\n");
        }
        String newSection = "### 4.4 `gap_reason` 全量取值范围 + 三类失败映射\n\n"
                + "**`gap_reason` 枚举（全量）**：\n\n"
                + "| 取值 | 含义 | 对应\"三类失败\" | A3 性质 | 是否可扣分 |\n|---|---|---|---|---|\n"
                + rows;
        text = text.replace(oldSection, newSection);

        // ② DDL CHECK
        Matcher ddl = dictDdlGapReasonCheck.matcher(text);
        assertTrue(ddl.find(), "改写前必须能找到字典 DDL gap_reason CHECK: " + file);
        String before = ddl.group(0);
        String after = "gap_reason     text CHECK (gap_reason IN (\n                   "
                + joinQuoted(values, complianceSpelling) + "))";
        text = text.replace(before, after);

        Files.writeString(file, text, StandardCharsets.UTF_8);

        // 自证：改写后 §4.4 与 DDL 都必须读出目标值集
        GapReasonFacts reread = SourceSet.load(root).gapReasonFacts();
        Set<String> expected = new TreeSet<>(values.stream().map(v -> spell(v, complianceSpelling)).toList());
        assertEquals(expected, new TreeSet<>(reread.dictSection44Values()),
                "字典 §4.4 改写后重读的值集必须等于期望值集");
        assertEquals(expected, new TreeSet<>(reread.dictDdlCheckValues()),
                "字典 DDL 改写后重读的值集必须等于期望值集");
    }

    private static String spell(String value, String complianceSpelling) {
        return "compliance_removal".equals(value) ? complianceSpelling : value;
    }

    private static String join(Set<String> values, String quote) {
        return values.stream().sorted().map(v -> quote + v + quote)
                .reduce((a, b) -> a + "," + b).orElseThrow();
    }

    private static String joinQuoted(Set<String> values, String complianceSpelling) {
        return values.stream().sorted().map(v -> "'" + spell(v, complianceSpelling) + "'")
                .reduce((a, b) -> a + "," + b).orElseThrow();
    }

    /** 精确替换一次；找不到 / 出现多次 → 红（避免"改错地方却以为改对了"）。 */
    private static void replaceOnce(Path file, String before, String after) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int first = text.indexOf(before);
        assertTrue(first >= 0, "待替换片段不存在: " + before);
        assertEquals(first, text.lastIndexOf(before), "待替换片段出现多次，替换目标不唯一: " + before);
        Files.writeString(file, text.replace(before, after), StandardCharsets.UTF_8);
    }
}