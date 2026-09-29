package com.diaoyuanyun.dy.audit.aspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🛑 <b>审计字段自动填充的"接线状态"门禁</b>（批次十三实测新增）。
 *
 * <h2>为什么需要这个门禁</h2>
 * {@link AuditFillAspect} 被骨架 README 的 ADR 对照表列为"ADR-09 可观测/审计"的落地物之一。
 * 但批次十三对"距离商用还差什么"做实测盘点时，用静态扫描抓出它是<b>一对从未接线的死代码</b>：
 *
 * <ol>
 *   <li><b>切点零匹配</b>：它的 {@code @Before} 切点是
 *       {@code execution(* com.diaoyuanyun..*.save(..)) || execution(* com.diaoyuanyun..*.persist(..))}，
 *       而本仓的持久化全部走 {@code *Ledger} + {@code JdbcTemplate.update("INSERT INTO ...")}，
 *       方法名是 {@code insertXxx} / {@code append} / {@code upsertXxx} ——
 *       <b>全库没有任何 {@code save(...)} / {@code persist(...)} 方法</b> ⇒ 切点永不匹配。</li>
 *   <li><b>载体零实现</b>：切面只对 {@code AuditableEntity} 类型的入参生效，
 *       而 {@code AuditableEntity}（{@code dy-common}）<b>没有任何子类</b> ⇒ 即使切点命中，
 *       也不会有入参 {@code instanceof AuditableEntity}。</li>
 * </ol>
 *
 * 后果：这条"自动填充 {@code created_by}/{@code updated_at}"的纪律<b>事实上从未生效</b>，
 * 而它此前<b>没有被任何门禁覆盖</b> —— 这正是"以为做了其实没接线"这类缺陷的典型形态：
 * 代码存在、注释完备、被文档引用，唯独没有人在运行时路径上验证过它。
 *
 * <h2>本门禁证明什么（以及如实登记"未做什么"）</h2>
 * <b>它证明</b>的是"<b>接线状态</b>"这一可机械核对的事实：切点模式在全库方法名上的匹配数 = 0、
 * {@code AuditableEntity} 子类数 = 0。这让"死代码"从一句人写的结论变成构建期可断言的事实。
 *
 * <p><b>它不</b>主张"应当立刻删除或立刻接线" —— 那是架构裁定：
 * <ul>
 *   <li>本仓 {@code created_by} <b>已由各 Ledger 显式写入</b>（86 处 {@code INSERT} 列 + 实参），
 *       即审计字段<b>实际有值</b>，只是不由本切面填充。故"删除切面"不造成字段缺失，
 *       但会撤掉"ADR-A5/A-6 自动填充"这条声明；</li>
 *   <li>"保留切面并让它真接线"意味着把所有 Ledger 的写路径改造成"走实体 save()"，
 *       是一次<b>架构级改动</b>（且与"Ledger + JdbcTemplate 直写"这条既有实现范式冲突）。</li>
 * </ul>
 * 二者选一属架构 owner 裁定，<b>本门禁不代拍</b>；它只保证"接线状态"不会再悄悄漂移。
 *
 * <h2>🛑 门禁的"牙齿"在哪（反向自证）</h2>
 * 若有人<b>真去接线</b>（新增 {@code save(..)} 方法或让某实体继承 {@code AuditableEntity}），
 * 本类会立刻变红并<b>点名新出现的匹配</b> ⇒ 迫使"接线"成为一次<b>显式动作</b>
 * （连同 README 的登记一起改），而不是"悄悄接上、文档仍说没接"。
 * 这与 {@code ConfigSlotConsumptionLedgerTest} 的"差异真在"纪律同款。
 */
class AuditFillAspectWiringGateTest {

    /** 仓库根：本模块（dy-audit）的上级目录即 skeleton 根。 */
    private static final Path SKEL = Paths_helper.skeletonRoot();

    /** 生产源码根（只扫 main，不扫 test —— 测试里的假方法不该让本门禁变绿）。 */
    private static final List<Path> MAIN_ROOTS = List.of(
            SKEL.resolve("dy-common/src/main/java"),
            SKEL.resolve("dy-tenancy/src/main/java"),
            SKEL.resolve("dy-security/src/main/java"),
            SKEL.resolve("dy-web/src/main/java"),
            SKEL.resolve("dy-audit/src/main/java"),
            SKEL.resolve("dy-config/src/main/java"),
            SKEL.resolve("dy-crypto/src/main/java"),
            SKEL.resolve("dy-app/src/main/java"));

    /**
     * 与方法名无关的"假匹配"来源 —— 注释、字符串字面量里的 {@code .save(} / {@code .persist(}。
     *
     * <p>🛑 与 {@code ConfigSlotConsumptionLedgerTest} 的 8.4 纪律一致：
     * <b>静态扫描必须先把注释剥掉</b>，否则 {@link AuditFillAspect} 自己那行
     * "本法匹配 {@code ..save(..)} / {@code ..persist(..)}" 的注释会让扫描"看起来有匹配"。
     */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 方法<b>定义</b>模式：{@code <modifiers> <ret> <name>(} —— 只认定义，不认调用。
     *
     * <p>刻意不用"文本里出现 {@code save(}" 这种弱判据：那会把
     * {@code map.save(key)} 这类调用也算进来。本门禁问的是"<b>有没有这个方法可被切</b>"，
     * 故必须匹配方法定义（含返回类型）。
     */
    private static final Pattern SAVE_OR_PERSIST_DEF = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private|static|final|synchronized|abstract|default|\\s)+"
                    + "[\\w<>,.\\[\\]\\s]+?\\s+(save|persist)\\s*\\(");

    // ==================================================================
    // 一、切点匹配数 = 0（这是"死代码"的机械证据）
    // ==================================================================

    @Test
    @DisplayName("🛑 切点 ..save(..)/..persist(..) 在生产源码里【零匹配】—— 故本切面当前不生效")
    void the_pointcut_matches_no_method_definition_in_production_sources() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path root : MAIN_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                    Matcher m = SAVE_OR_PERSIST_DEF.matcher(src);
                    while (m.find()) {
                        hits.add(root.getParent().getParent().getParent().getFileName()
                                + "/" + root.relativize(f) + " :: " + m.group(1));
                    }
                }
            }
        }

        // 元层自证：扫描面必须非空，否则"零匹配"可能只是"什么都没扫到"
        assertTrue(countJavaFiles(MAIN_ROOTS) > 100,
                "扫描到的 .java 文件过少（" + countJavaFiles(MAIN_ROOTS) + "）—— 目录搬家会让本门禁退化成恒绿");

        assertEquals(List.of(), hits,
                "🛑 生产源码里出现了 save(...)/persist(...) 【方法定义】—— 说明有人真的给 AuditFillAspect "
                        + "接线了（或引入了 JPA 风格的仓储）。这不是坏事，但它是一次【显式动作】：\n"
                        + "  ① 请确认新方法所在仓储的入参类型确实继承 AuditableEntity；\n"
                        + "  ② 请同步更新 README（ADR-09 对照表 + §五 登记）与卡点清单的\"死代码\"登记；\n"
                        + "  ③ 然后把本断言改为\"期望的接线点清单\"（而不是空集）。\n"
                        + "  实测命中：" + hits);
    }

    @Test
    @DisplayName("🛑 AuditableEntity 在生产源码里【零子类】—— 故即使切点命中，也无入参可填")
    void auditable_entity_has_no_subclass_in_production_sources() throws IOException {
        Pattern extendsPattern = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private|abstract|static|final|\\s)*"
                        + "class\\s+\\w+[^{]*?\\bextends\\s+AuditableEntity\\b");

        List<String> subclasses = new ArrayList<>();
        for (Path root : MAIN_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                    if (extendsPattern.matcher(src).find()) {
                        subclasses.add(MAIN_ROOTS.indexOf(root) + ":" + root.relativize(f));
                    }
                }
            }
        }

        assertEquals(List.of(), subclasses,
                "🛑 出现了 AuditableEntity 的子类 —— 自动填充的【载体】到位了，这是一次显式动作，"
                        + "请同步更新 README 登记与本节门禁的期望值。实测命中：" + subclasses);
    }

    // ==================================================================
    // 二、审计字段实际由 Ledger 显式写入（证明"字段有值"不依赖本切面）
    // ==================================================================

    @Test
    @DisplayName("审计字段实际由 Ledger 显式写入 —— created_by 在 dy-app 生产源码里被显式引用（非切面填充）")
    void audit_columns_are_written_explicitly_by_ledgers() throws IOException {
        Path dyAppMain = SKEL.resolve("dy-app/src/main/java");
        int occurrences = 0;
        try (Stream<Path> files = Files.walk(dyAppMain)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                occurrences += countOccurrences(src, "created_by");
            }
        }

        // 这条断言的意义：证明"审计字段有值"是【另外一条路径】保证的 ——
        // 故"删掉死切面"不会造成字段缺失（但它会撤掉 ADR-A5/A-6 的自动填充声明）。
        assertTrue(occurrences >= 50,
                "dy-app 生产源码里 created_by 的出现次数骤降（实测 " + occurrences + "）—— "
                        + "若 Ledger 不再显式写 created_by，而切面又是死的，审计字段会【真的为空】。"
                        + "这从\"死代码无伤\"升级为\"数据缺失\"，必须立刻处理");
    }

    // ==================================================================
    // 三、切面自身仍然语法存在（防止"把切面删了但门禁还绿"）
    // ==================================================================

    @Test
    @DisplayName("切面与其切点注解仍在（删除切面是一次显式动作，不能被静默绕过）")
    void the_aspect_and_its_pointcut_annotation_still_exist() throws IOException {
        Path aspect = SKEL.resolve(
                "dy-audit/src/main/java/com/diaoyuanyun/dy/audit/aspect/AuditFillAspect.java");
        assertTrue(Files.isRegularFile(aspect), "切面文件不在了 —— 若确要删除，请连同本门禁与 README 登记一并改");

        // 🛑 必须先剥注释再断言（纪律 8.4 的镜像）：
        // 本类初版直接对原始文本 `src.contains("@Aspect")` —— 而把注解"注释掉"
        // （`// @Aspect`）会让字面量仍在文本里 ⇒ 断言通过 ⇒ 切面已被静默降级成普通类，
        // 而门禁【仍然绿】。该缺陷由 verification/112 的 C3 注入首次抓出。
        // 问"注解在不在"必须问"未注释的代码里在不在"—— 与"源码里字面出现 ≠ 运行时被消费"同源。
        String code = stripComments(Files.readString(aspect, StandardCharsets.UTF_8));

        assertTrue(code.contains("@Aspect"),
                "🛑 @Aspect 注解被摘掉（或注释掉）—— 它会静默变成普通类，切点永不生效，"
                        + "而本门禁的第一条断言（切点零匹配）就失去了意义（死代码从\"待裁定\"变成\"已删除\"）");
        assertTrue(code.contains("@Component"),
                "🛑 @Component 注解被摘掉 —— 切面不会被容器装配");
        assertTrue(code.contains("execution(* com.diaoyuanyun..*.save(..))"),
                "切点表达式被改动了 —— 请同步复核上面第一条断言的\"零匹配\"前提是否仍成立");

        // 反向自证另一半：切面里【引用】这两个名字（注释与注解），但生产源码里【没有】对应方法定义 ——
        // 注释版（原始文本）与代码版（剥注释）分别含 / 不含，正是"死代码"证据链的两端。
        assertTrue(Files.readString(aspect, StandardCharsets.UTF_8).contains(".persist(..)"),
                "切面文档里对 ..persist(..) 的引用不见了 —— 若切点写法改了，本门禁的前提需一并复核");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 剥掉行注释与块注释（纪律 8.4：静态扫描必须先剥注释，否则切面自己的注释会造成假匹配）。 */
    private static String stripComments(String src) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(src).replaceAll(" ")).replaceAll(" ");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static long countJavaFiles(List<Path> roots) throws IOException {
        long n = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                n += files.filter(p -> p.toString().endsWith(".java")).count();
            }
        }
        return n;
    }

    /** 骨架根定位（与既有测试同款：靠 user.dir 向上找到含 dy-app 的目录）。 */
    private static final class Paths_helper {
        static Path skeletonRoot() {
            Path p = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && p != null; i++) {
                if (Files.isDirectory(p.resolve("dy-app")) && Files.isDirectory(p.resolve("dy-audit"))) {
                    return p;
                }
                if (Files.isDirectory(p.resolve("skeleton").resolve("dy-app"))) {
                    return p.resolve("skeleton");
                }
                p = p.getParent();
            }
            // 兜底：本模块的上级即骨架根
            return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().getParent();
        }
    }
}