package com.diaoyuanyun.dy.app.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ConfigTruthSourceMigrationSyncTest: <b>配置真相源「真源文件 ↔ V14 内联段」逐字同步门禁</b>。
 *
 * <h2>这个门禁存在，是因为 V14 自己登记了一个结构性的代价</h2>
 * V14 把 dy-config 的两份真相源资产内联进了迁移文件（而不是用 {@code \i} 引用）：
 * <pre>
 *   db/config/01_truth_source_ddl.sql   （315 行）→ V14 第 1 节 DDL
 *   db/config/02_slots_seed.sql         （408 行）→ V14 第 2 节声明 + $seed_guard$
 * </pre>
 * 内联本身是本仓既有做法（V5 单文件 65KB 建 24 表），但它<b>造出一个真实的漂移点</b>：
 * <ul>
 *   <li>dy-config 的门禁（{@code ConfigTruthSourceIT} 等 5 个 IT）读的是<b>真源文件</b>；</li>
 *   <li>{@code ConfigSeed*ProfileSource} 这 4 个类在 dy-app 里读的也是<b>真源文件</b>
 *       （classpath 上的种子文本）；</li>
 *   <li>而应用库实际执行的 DDL 与声明，来自 <b>V14 里的内联副本</b>。</li>
 * </ul>
 * 于是"改一处不改另一处"会<b>不报错</b>，只让门禁库 / 应用库 / 口径来源三方口径分叉 ——
 * 而分叉的是"判定阈值、退款闸门、可见性矩阵"这些<b>合规边界</b>。
 *
 * <p>V14 文件头逐字登记了这条代价与它的把守方式（本节就是那句话的机械兑现）。
 * 本类把"两份文件内容一致"从<b>人工纪律</b>变成<b>构建期事实</b>。
 *
 * <h2>🛑 为什么不能"整体逐字比较"（那样会红，且红得没有信息量）</h2>
 * V14 与真源文件<b>刻意</b>不同：
 * <ol>
 *   <li><b>切点不同</b>：01 的<b>第 1 节</b>（{@code CREATE TABLE tenant} 自举段）<b>不被内联</b> ——
 *       应用库的 {@code tenant} 由 V1 基线建，内联会与 V1 冲突。故比较从 01 的
 *       「第 2 节」注释行开始。</li>
 *   <li><b>一处已登记的语义偏离</b>：02 原文用 {@code DELETE FROM config_slot;} 再裸
 *       {@code INSERT}；V14 改为 {@code INSERT … ON CONFLICT (config_no) DO UPDATE}。
 *       <b>理由（已实测）</b>：应用库里 {@code app_config.config_no} 外键引用
 *       {@code config_slot}，只要租户已初始化，{@code DELETE FROM config_slot} 立刻以
 *       <b>23503</b> 失败 ⇒ 原写法在应用库<b>不幂等</b>，Flyway 重跑即红。</li>
 * </ol>
 * 因此本类按<b>三段</b>分别比较，并对那<b>唯一一处</b>偏离做<b>结构化断言</b>
 * （把替换文本作为常量登记下来，而不是"跳过一段"）—— 跳过一段会让门禁失去牙齿：
 * 将来有人在那 12 行里改坏一个列名，门禁不会有任何信号。
 *
 * <h2>三段切分（锚点都是<b>定义型</b>的，不是行号）</h2>
 * <pre>
 *   段 A  DDL     01[「-- 2) 总部层声明表」..EOF]  ==  V14[同一行 .. 「第 2 节」横幅前]
 *   段 B  声明    02[「INSERT INTO config_slot」..「DO $seed_guard$」前一行]
 *                 ==  V14[同一行 .. 「DO $seed_guard$」前一行]    （减去已登记的一处偏离）
 *   段 C  守卫    02[「DO $seed_guard$」..「$seed_guard$ LANGUAGE plpgsql;」]
 *                 ==  V14[同一行 .. 同一行]                       （逐字一致）
 * </pre>
 * 段 C 单独列出的理由：{@code $seed_guard$} 是"46 条 / #42 缺席 / #47 缺席 / 尾部 5 条齐备"
 * 的灌入守卫，它是一条<b>独立于 DDL 的裁定</b>（编号域裁定）。把它与 DDL 合成一段比较，
 * 会让"守卫被悄悄删掉"这件事表现为"某段文本不一致"，而不是"编号裁定失守"。
 *
 * <h2>只扫交付物源文件，不扫 classpath 副本</h2>
 * 与 {@link MigrationRegistryGateTest}、{@code RlsCoverageGateTest} 同款：
 * 门禁必须盯着<b>真正要交付的那份文件</b>。读 classpath 副本会在"资源没被复制"
 * （{@code target/classes} 陈旧）时<b>假绿</b> —— 而那时交付物里已经是分叉的版本。
 */
@DisplayName("配置真相源同步纪律：真源文件 ↔ V14 内联段 必须逐字一致（除一处已登记的偏离）")
class ConfigTruthSourceMigrationSyncTest {

    /** skeleton 根（surefire 的工作目录 = 模块目录 dy-app，故用 {@code ..}）。 */
    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    private static final Path TRUTH_DDL =
            SKEL.resolve("dy-config/src/main/resources/db/config/01_truth_source_ddl.sql");
    private static final Path TRUTH_SEED =
            SKEL.resolve("dy-config/src/main/resources/db/config/02_slots_seed.sql");
    private static final Path V14 =
            SKEL.resolve("dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql");

    /** 段 A 的起点锚（01 与 V14 里逐字相同的注释行）。 */
    private static final String ANCHOR_SECTION_2 = "-- 2) 总部层声明表";

    /** 段 B 的起点锚。 */
    private static final String ANCHOR_INSERT_SLOT = "INSERT INTO config_slot";

    /** 段 C 的起点锚。 */
    private static final String ANCHOR_SEED_GUARD = "DO $seed_guard$";

    /** 段 B/段 C 的终点锚（{@code $seed_guard$} 块的收尾行）。 */
    private static final String ANCHOR_GUARD_LANG = "$seed_guard$ LANGUAGE plpgsql;";

    /** V14 第 2 节横幅的标记（段 A 的终点）。 */
    private static final String ANCHOR_SECTION_2_BANNER = "第 2 节";

    /**
     * V14 相对 {@code 02_slots_seed.sql} 的<b>唯一一处刻意偏离</b>，逐字登记。
     *
     * <p>02 的写法：
     * <pre>
     *   … '⚠️ 本项为待评审增项（…）。');
     * </pre>
     * V14 的写法：把那个终止 {@code );} 拆成 {@code )} 与下面的 {@code ON CONFLICT} 块。
     *
     * <p>🛑 <b>为什么这里要逐字登记而不是"允许一段差异"</b>：
     * "允许一段差异"等于在这 12 行上关掉门禁。逐字登记后，任何一列名被改坏、
     * {@code DO UPDATE} 被改成 {@code DO NOTHING}（那会让"改了声明值却不生效"重现）、
     * 或 {@code SET} 列表漏掉一列，都会让本类立刻红。
     *
     * <p>⚠️ 02 里被替换掉的是<b>唯一一行</b>以 {@code "');"} 结尾的行 —— 且它必须是
     * {@code INSERT INTO config_slot} 语句的<b>最后一行</b>（该行前一个字段是带单引号的
     * 描述字面量，故终止符是 {@code "');"} 而不是裸 {@code ");"}）。
     * 若 02 将来加了第二条 {@code INSERT}（例如把新增 #49 单列一段），
     * 本段内以 {@code "');"} 结尾的行会从 1 变成 2 ⇒ 本类会以"偏离处数不为 1"报红，
     * 从而强迫落盘者显式表态。这正是"偏离必须可数"的价值。
     */
    private static final List<String> REGISTERED_DIVERGENCE = List.of(
            "-- 🛑 ON CONFLICT DO UPDATE —— 本迁移相对 02_slots_seed.sql 的唯一一处刻意偏离。",
            "--    目的：① 幂等可重跑；② 声明值改动仍然生效（这是 02 原注释真正想要的效果）。",
            "--    已初始化租户的生效值不受影响：它们在 app_config，按 01 的「读路径纪律」",
            "--    只在【新租户初始化】时读一次 initial_value。",
            "ON CONFLICT (config_no) DO UPDATE",
            "   SET config_key       = EXCLUDED.config_key,",
            "       value_type       = EXCLUDED.value_type,",
            "       allowed_values   = EXCLUDED.allowed_values,",
            "       forbidden_values = EXCLUDED.forbidden_values,",
            "       initial_value    = EXCLUDED.initial_value,",
            "       prd_item_name    = EXCLUDED.prd_item_name,",
            "       description      = EXCLUDED.description;");

    /**
     * 上述常量里开头的注释行数。
     *
     * <p>这几行只是"解释偏离为什么存在"，<b>不是</b>迁移里的可执行语句。
     * 故与 V14 文件里那一段（从 {@code ON CONFLICT … DO UPDATE} 起、到第一个
     * 行尾 {@code ;} 止）做逐行比较时，必须先把它们去掉 ——
     * 否则"注释行数"会被误当成"代码行数差"，报出一条看起来像"块被改写"的假红
     * （本条实测踩过：常量 12 行 vs 文件实际 8 行）。
     */
    private static final int DIVERGENCE_LEADING_COMMENTS = 4;

    /**
     * 02 里被上述偏离块替换掉的原文：{@code INSERT INTO config_slot} 语句最后一行末尾的
     * {@code "');"}（前一个字段是带单引号的描述字面量，故终止符含那个闭合单引号）。
     */
    private static final String REPLACED_TERMINATOR = "');";

    // ==================================================================
    // 一、文件在位与来源行号自证
    // ==================================================================

    @Test
    @DisplayName("三份文件都在位（真源两份 + V14），且 V14 头部逐字标注了内联来源")
    void all_three_artifacts_exist_and_v14_declares_its_source() {
        assertTrue(Files.isRegularFile(TRUTH_DDL), "缺真源 DDL: " + TRUTH_DDL);
        assertTrue(Files.isRegularFile(TRUTH_SEED), "缺真源声明: " + TRUTH_SEED);
        assertTrue(Files.isRegularFile(V14), "缺 V14 迁移: " + V14);

        String v14 = read(V14);
        // V14 文件头必须【逐字】标注内联来源的文件名 —— 否则下一个人不知道该跟谁同步
        assertTrue(v14.contains("01_truth_source_ddl.sql"),
                "V14 文件头必须标注内联来源 01_truth_source_ddl.sql（否则无从知道该与谁同步）");
        assertTrue(v14.contains("02_slots_seed.sql"),
                "V14 文件头必须标注内联来源 02_slots_seed.sql");
        assertTrue(v14.contains("唯一一处刻意偏离") || v14.contains("唯一一处偏离"),
                "V14 文件头必须登记那处偏离（不允许把偏离伪装成同步）");
    }

    // ==================================================================
    // 二、段 A：DDL 逐字一致
    // ==================================================================

    @Test
    @DisplayName("段 A DDL：01 第 2 节起 ↔ V14 第 1 节，逐字一致（01 的 tenant 自举段刻意不内联）")
    void section_a_ddl_is_byte_identical() {
        List<String> ddl = lines(read(TRUTH_DDL));
        List<String> v14 = lines(read(V14));

        int from01 = indexOfContains(ddl, ANCHOR_SECTION_2, 0, "01 缺第 2 节锚点");
        int from14 = indexOfContains(v14, ANCHOR_SECTION_2, 0, "V14 缺第 2 节锚点");
        int to14 = indexOfContains(v14, ANCHOR_SECTION_2_BANNER, from14,
                "V14 缺第 2 节横幅锚点（段 A 的终点）");

        // 段 A 终点：横幅所在行的【前一行 '-- ===='】之前的所有非空行
        int end14 = to14 - 1;
        while (end14 > from14 && v14.get(end14).isBlank()) {
            end14--;
        }
        // 该行是横幅的分隔线 `-- ======`，不属于 01 的内容
        if (v14.get(end14).strip().matches("^--\\s*=+$")) {
            end14--;
        }

        List<String> expected = new ArrayList<>(ddl.subList(from01, ddl.size()));
        List<String> actual = new ArrayList<>(v14.subList(from14, end14 + 1));

        // 01 与 V14 的尾部空行数可能不同（V14 后面还要接第 2 节横幅），故统一剥尾部空行
        trimTrailingBlanks(expected);
        trimTrailingBlanks(actual);

        assertSectionEqual("段 A（DDL）", expected, actual,
                "01_truth_source_ddl.sql 第 2 节起", "V14 第 1 节");
    }

    // ==================================================================
    // 三、段 C：$seed_guard$ 守卫逐字一致（编号裁定）
    // ==================================================================

    @Test
    @DisplayName("段 C 守卫：$seed_guard$ 灌入守卫逐字一致（46 条 / #42 缺席 / #47 缺席 / 尾部 5 条齐备）")
    void section_c_seed_guard_is_byte_identical() {
        List<String> seed = lines(read(TRUTH_SEED));
        List<String> v14 = lines(read(V14));

        int fromSeed = indexOfContains(seed, ANCHOR_SEED_GUARD, 0, "02 缺 $seed_guard$ 锚点");
        int toSeed = indexOfContains(seed, ANCHOR_GUARD_LANG, fromSeed, "02 缺 $seed_guard$ 收尾行");
        int from14 = indexOfContains(v14, ANCHOR_SEED_GUARD, 0, "V14 缺 $seed_guard$ 锚点");
        int to14 = indexOfContains(v14, ANCHOR_GUARD_LANG, from14, "V14 缺 $seed_guard$ 收尾行");

        List<String> expected = seed.subList(fromSeed, toSeed + 1);
        List<String> actual = v14.subList(from14, to14 + 1);

        assertSectionEqual("段 C（$seed_guard$ 守卫）", expected, actual,
                "02_slots_seed.sql 的守卫块", "V14 的守卫块");

        // 守卫必须真的在断言编号裁定（三条），而不只是一段空壳
        String guard = String.join("\n", actual);
        assertTrue(guard.contains("42"), "守卫必须断言 #42 空号");
        assertTrue(guard.contains("47"), "守卫必须断言 #47 预留缺席");
        assertTrue(guard.contains("RAISE EXCEPTION"),
                "守卫必须用 RAISE EXCEPTION（不满足即让整个迁移回滚），而不是仅打印 NOTICE");
    }

    // ==================================================================
    // 四、段 B：声明正文（除一处已登记偏离外逐字一致）
    // ==================================================================

    @Test
    @DisplayName("段 B 声明：02 的 INSERT 正文 ↔ V14 内联段，除唯一一处已登记偏离外逐字一致")
    void section_b_declaration_matches_except_the_one_registered_divergence() {
        List<String> seed = lines(read(TRUTH_SEED));
        List<String> v14 = lines(read(V14));

        int insSeed = indexOfContains(seed, ANCHOR_INSERT_SLOT, 0, "02 缺 INSERT INTO config_slot");
        int ins14 = indexOfContains(v14, ANCHOR_INSERT_SLOT, 0, "V14 缺 INSERT INTO config_slot");
        int guardSeed = indexOfContains(seed, ANCHOR_SEED_GUARD, insSeed, "02 缺 $seed_guard$ 锚点");
        int guard14 = indexOfContains(v14, ANCHOR_SEED_GUARD, ins14, "V14 缺 $seed_guard$ 锚点");

        List<String> from02 = new ArrayList<>(seed.subList(insSeed, guardSeed));
        List<String> from14 = new ArrayList<>(v14.subList(ins14, guard14));
        trimTrailingBlanks(from02);
        trimTrailingBlanks(from14);

        // ---- ① 偏离处必须【恰好一处】，且必须是 INSERT 语句的最后一行 ----
        // 🛑 判据是"以 `');` 结尾"，不是"整行等于 `);`" —— 该行的前一个字段是带单引号的
        //    描述字面量，故终止符是 `');`。整行形态是 ` '…描述…');`，
        //    用整行相等去数会恒为 0，门禁就成了空壳（本条实测踩过）。
        long terminatorCount = from02.stream().filter(l -> l.strip().endsWith(REPLACED_TERMINATOR)).count();
        assertEquals(1L, terminatorCount,
                "02 的 INSERT 正文里以 " + REPLACED_TERMINATOR + " 结尾的行必须恰出现 1 次（实际 "
                        + terminatorCount + " 次）。多于 1 次说明该段里有第二条 INSERT —— "
                        + "此时替换点不唯一，本门禁的「减去偏离」逻辑不再成立，"
                        + "必须显式重新登记偏离处数。");

        long divergenceCount = countSubsequence(from14, REGISTERED_DIVERGENCE);
        assertEquals(1L, divergenceCount,
                "V14 里已登记的偏离块必须恰出现 1 次（实际 " + divergenceCount + " 次）。"
                        + "0 次 ⇒ 偏离块被删（请确认 ON CONFLICT 是否还在，否则重跑会以 23503 失败）；"
                        + ">1 次 ⇒ 出现了未登记的额外偏离。");

        // ---- ② 减去偏离后逐字一致 ----
        // 找到那唯一一行，把它拆成 `…'` + `)` 两段：02 的 `');` 在语义上
        // = `')` + `;`，而 V14 把 `)` 与 `;` 拆开以插入 ON CONFLICT（`;` 落在偏离块末行）。
        int at = -1;
        for (int i = 0; i < from02.size(); i++) {
            if (from02.get(i).strip().endsWith(REPLACED_TERMINATOR)) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "02 的 INSERT 正文里必须能找到以 " + REPLACED_TERMINATOR + " 结尾的行");

        List<String> rebuilt = new ArrayList<>();
        rebuilt.addAll(from02.subList(0, at));
        String last = from02.get(at);
        // 🛑 剥离长度必须恰为 2（`);`），【保留】那个闭合单引号 ——
        //    该行是 ` '…描述…');`，其中 `'` 属于描述字面量的收尾。
        //    V14 里这一行是 ` '…描述…')`（`;` 移到了偏离块末行），
        //    故重建时应得 `'` + `)` = `')`。剥 3 个字符会连引号一起剥掉，
        //    产生 `…。)` 与真值 `…。')` 差一个字符的假红（本条实测踩过）。
        rebuilt.add(last.substring(0, last.length() - 2) + ")");
        rebuilt.addAll(REGISTERED_DIVERGENCE);
        rebuilt.addAll(from02.subList(at + 1, from02.size()));

        assertSectionEqual("段 B（声明正文，已减去登记在案的一处偏离）", rebuilt, from14,
                "02_slots_seed.sql 的 INSERT 正文（按登记规则重建）", "V14 内联段");
    }

    @Test
    @DisplayName("段 B 声明：唯一偏离不得是【静默丢值】型 —— ON CONFLICT 必须 DO UPDATE 且覆盖全部 7 列")
    void the_registered_divergence_does_not_silently_drop_values() {
        String block = String.join("\n", REGISTERED_DIVERGENCE);

        // 🛑 DO NOTHING 会让"改了 initial_value 却不会生效"重现 —— 那正是 02 原注释
        //    逐字想避免的效果（"否则改了 initial_value 却不会生效会造成迁移看着成功、值没变"）。
        assertTrue(block.contains("DO UPDATE"),
                "偏离块必须是 DO UPDATE —— DO NOTHING 会让「改了声明值却不生效」重现，"
                        + "而那种失败表现为「迁移绿了但口径没变」，极难查");
        assertFalse(block.contains("DO NOTHING"),
                "偏离块不得出现 DO NOTHING（见上一条理由）");

        // 02 的 INSERT 列清单是 8 列（config_no 是冲突键，不参与 SET）
        List<String> settable = List.of("config_key", "value_type", "allowed_values",
                "forbidden_values", "initial_value", "prd_item_name", "description");
        for (String col : settable) {
            assertTrue(block.contains("SET " + col) || block.contains("= EXCLUDED." + col),
                    "偏离块必须覆盖列 " + col + " —— 漏掉一列会让该列的声明值改动静默不生效");
            assertTrue(block.contains("EXCLUDED." + col) || block.contains(col + "       = EXCLUDED."),
                    "列 " + col + " 必须显式取 EXCLUDED." + col + "（否则会写成常量或旧值，语义错）");
        }
        assertEquals(settable.size() + 5, REGISTERED_DIVERGENCE.size(),
                "偏离块行数必须恰为「4 行注释 + 1 行 ON CONFLICT + 7 行 SET」= 12 行 —— "
                        + "行数变了说明块被改动，需同步本常量");

        // 冲突键必须恰好是 (config_no)：若改成 (config_key) 或复合键，
        // 幂等语义会变成"按另一个维度去重"，与 02 的声明模型不符
        assertTrue(block.contains("ON CONFLICT (config_no)"),
                "冲突键必须恰为 (config_no)（与 config_slot 主键一致）");

        // ---- 🛑 同时断言【V14 文件里实际那一段】，而不只是本类常量 ----
        // 只查常量等于"核对我自己写的字"：文件里那 12 行被改坏（少一列 / 改成 DO NOTHING）
        // 时，常量断言不会有任何信号。这一段才是真正盯着交付物的那一条。
        List<String> v14Lines = lines(read(V14));
        // 🛑 锚点必须用【整行相等】而不是 contains：V14 的文件头里也写着
        //    "改为 `INSERT … ON CONFLICT (config_no) DO UPDATE`。" 这类说明句，
        //    用 contains 会命中注释、把整段文件头当成"实际块"（本条实测踩过）。
        int oc = indexOfLineEquals(v14Lines, "ON CONFLICT (config_no) DO UPDATE", 0,
                "V14 里找不到【可执行语句行】ON CONFLICT (config_no) DO UPDATE"
                        + "（偏离块是否被删了，或是否被改成了 DO NOTHING？）");
        List<String> actualBlock = new ArrayList<>();
        for (int i = oc; i < v14Lines.size(); i++) {
            actualBlock.add(v14Lines.get(i));
            if (v14Lines.get(i).strip().endsWith(";")) {
                break;
            }
        }
        String actual = String.join("\n", actualBlock);
        for (String col : settable) {
            assertTrue(actual.contains("= EXCLUDED." + col),
                    "🛑 V14 文件里【实际的】ON CONFLICT 块必须覆盖列 " + col
                            + " —— 本类常量与交付物已分叉（漏一列会让该列的声明值改动静默不生效）。"
                            + "实际块:\n" + actual);
        }
        assertTrue(actual.contains("DO UPDATE"),
                "🛑 V14 文件里实际的 ON CONFLICT 必须写 DO UPDATE，实际块:\n" + actual);
        assertFalse(actual.contains("DO NOTHING"),
                "🛑 V14 文件里实际的 ON CONFLICT 不得写 DO NOTHING（会让「改了声明值却不生效」重现），"
                        + "实际块:\n" + actual);
        assertEquals(REGISTERED_DIVERGENCE.size() - DIVERGENCE_LEADING_COMMENTS, actualBlock.size(),
                "V14 文件里实际的偏离块必须与本类常量的【代码部分】行数一致（"
                        + (REGISTERED_DIVERGENCE.size() - DIVERGENCE_LEADING_COMMENTS) + " 行："
                        + "1 行 ON CONFLICT + 7 行 SET）；实际 " + actualBlock.size() + " 行:\n" + actual);
        assertSectionEqual("V14 实际偏离块",
                REGISTERED_DIVERGENCE.subList(DIVERGENCE_LEADING_COMMENTS, REGISTERED_DIVERGENCE.size()),
                actualBlock, "本类登记的偏离块", "V14 文件里的实际块");
    }

    // ==================================================================
    // 五、反向验证：错位必须被抓（门禁有牙齿的自证）
    // ==================================================================

    @Test
    @DisplayName("自证：比较函数对「少一行 / 多一行 / 改一行」都必须判为不等（否则本门禁是空壳）")
    void the_comparator_itself_catches_off_by_one_and_single_line_edits() {
        List<String> base = lines(read(TRUTH_SEED));
        int from = indexOfContains(base, ANCHOR_INSERT_SLOT, 0, "02 缺锚点");
        int to = indexOfContains(base, ANCHOR_SEED_GUARD, from, "02 缺守卫锚点");
        List<String> section = new ArrayList<>(base.subList(from, to));
        assertTrue(section.size() > 10, "用于自证的样本段太短: " + section.size() + " 行");

        // ① 相同 → 相等（先证明函数不是恒不等）
        assertEquals(section, new ArrayList<>(section), "同一段自比必须相等");

        // ② 少一行
        List<String> less = new ArrayList<>(section);
        less.remove(less.size() / 2);
        assertFalse(section.equals(less), "少一行必须判为不等");

        // ③ 多一行
        List<String> more = new ArrayList<>(section);
        more.add("-- injected");
        assertFalse(section.equals(more), "多一行必须判为不等");

        // ④ 改一行（改一个字符）
        List<String> edited = new ArrayList<>(section);
        int i = edited.size() / 2;
        edited.set(i, edited.get(i) + " ");
        assertFalse(section.equals(edited), "改一行（尾部多一个空格）必须判为不等 —— "
                + "这正是「改 01/02 而不同步 V14」的最小形态");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 断言两段逐字一致；不一致时给出<b>首处差异的行号与两侧原文</b>（而不是一坨 diff）。 */
    private static void assertSectionEqual(String label, List<String> expected, List<String> actual,
                                           String expectedName, String actualName) {
        int max = Math.max(expected.size(), actual.size());
        for (int i = 0; i < max; i++) {
            String e = i < expected.size() ? expected.get(i) : "<缺行>";
            String a = i < actual.size() ? actual.get(i) : "<缺行>";
            if (!e.equals(a)) {
                fail(label + " 第 " + (i + 1) + " 行不一致 —— " + expectedName + " 与 " + actualName
                        + " 已分叉：\n"
                        + "  " + expectedName + " : " + preview(e) + "\n"
                        + "  " + actualName + "   : " + preview(a) + "\n"
                        + "（两侧行数: " + expected.size() + " / " + actual.size() + "）\n"
                        + "修法：确认是有意改动后，同步更新另一侧；若是有意的结构性偏离，"
                        + "必须在 V14 文件头【登记】它，并在本类里把它写成结构化常量，"
                        + "而不是放宽比较 —— 放宽比较等于在分叉点上关掉门禁。");
            }
        }
        assertEquals(expected.size(), actual.size(), label + " 行数必须一致");
    }

    private static void trimTrailingBlanks(List<String> ls) {
        while (!ls.isEmpty() && ls.get(ls.size() - 1).isBlank()) {
            ls.remove(ls.size() - 1);
        }
    }

    private static int indexOfContains(List<String> ls, String needle, int from, String errMsg) {
        for (int i = from; i < ls.size(); i++) {
            if (ls.get(i).contains(needle)) {
                return i;
            }
        }
        throw new AssertionError(errMsg + "：「" + needle + "」在此行之后未找到");
    }

    /**
     * 整行相等地找一行。
     *
     * <p>🛑 与 {@link #indexOfContains} 的区别在"锚点是否会命中注释"：
     * V14 的文件头用自然语言描述了它自己做的事（"改为 {@code ON CONFLICT … DO UPDATE}"），
     * 故用 {@code contains} 找那段可执行语句时会命中注释。凡是要定位
     * <b>可执行语句</b>的地方，都必须用本方法。
     */
    private static int indexOfLineEquals(List<String> ls, String exact, int from, String errMsg) {
        for (int i = from; i < ls.size(); i++) {
            if (ls.get(i).strip().equals(exact)) {
                return i;
            }
        }
        throw new AssertionError(errMsg + "：「" + exact + "」作为独立行在此行之后未找到");
    }

    /** 子序列在 {@code hay} 中【连续】出现的次数。 */
    private static long countSubsequence(List<String> hay, List<String> needle) {
        long n = 0;
        int i = 0;
        while (true) {
            int at = indexOfSubsequence(hay, needle, i);
            if (at < 0) {
                return n;
            }
            n++;
            i = at + 1;
        }
    }

    private static int indexOfSubsequence(List<String> hay, List<String> needle) {
        return indexOfSubsequence(hay, needle, 0);
    }

    private static int indexOfSubsequence(List<String> hay, List<String> needle, int from) {
        if (needle.isEmpty() || hay.size() < needle.size()) {
            return -1;
        }
        outer:
        for (int i = Math.max(0, from); i + needle.size() <= hay.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!hay.get(i + j).equals(needle.get(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String preview(String s) {
        String flat = s.length() > 160 ? s.substring(0, 160) + "…" : s;
        return "\"" + flat + "\"";
    }

    /**
     * 按行读（保留行内空格，去掉行尾 CR）。
     *
     * <p>🛑 按 {@code \n} 切分而不是 {@code \R}：{@code \R} 会把 {@code \r\n} 当成一个
     * 整体并连带吞掉后续空白，使"行内容"在两侧取法不同 —— 而本类的断言恰恰是逐字符比较。
     * 显式去 {@code \r} 是为了让 CRLF 与 LF 两种落盘形式给出<b>相同</b>的比较结果
     * （本仓历史上出现过 CRLF/LF 混用导致的假红）。
     */
    private static List<String> lines(String text) {
        String[] raw = text.split("\n", -1);
        List<String> out = new ArrayList<>(raw.length);
        for (String s : raw) {
            out.add(s.endsWith("\r") ? s.substring(0, s.length() - 1) : s);
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + p, e);
        }
    }
}