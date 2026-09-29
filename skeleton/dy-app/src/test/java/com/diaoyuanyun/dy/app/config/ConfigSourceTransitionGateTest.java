package com.diaoyuanyun.dy.app.config;

import com.diaoyuanyun.dy.app.customer.service.ConfigSeedContraindicationSource;
import com.diaoyuanyun.dy.app.derived.domain.DerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.service.ConfigSeedDerivedProfileSource;
import com.diaoyuanyun.dy.app.identity.domain.BandVisibilitySource;
import com.diaoyuanyun.dy.app.identity.service.ConfigSeedBandProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.app.scale.domain.ScaleProfileSource;
import com.diaoyuanyun.dy.app.scale.service.ConfigSeedScaleProfileSource;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreProfileSource;
import com.diaoyuanyun.dy.app.settlement.service.ConfigSeedCrossStoreProfileSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-3 过渡态门禁：<b>"配置来源仍是过渡实现"这件事必须始终是显式、可机械核对的</b>。
 *
 * <h2>它守的是什么（一个"不会被任何测试抓住"的真实状态）</h2>
 * B-3 的完整收口（让总部在配置页改的数字<b>在线</b>生效）需要先裁定"总部唯一项"
 * 的承载点（新增总部生效值表 / 用系统租户承载 / 明确不要求在线改，三选一）——
 * 那是架构裁定，不由本门禁代拍。
 *
 * <p>本轮能收口、且<b>必须</b>收口的是另一半：这份"过渡态"此前只写在
 * <b>六个类的 Javadoc 与 README 的 TODO 里</b>，没有任何测试守护它。它的失效方式是
 * <b>静默的</b>，且方向有两种、都危险：
 * <ul>
 *   <li><b>悄悄收口</b>：某天有人新增了一个读 DB 的实现，<b>却没删过渡实现</b> ——
 *       于是同一个端口下有两个 {@code @Component}，Spring 按类型注入时会报
 *       "expected single matching bean"（这还算走运，启动即失败），
 *       或者更糟：两个都在、靠 {@code @Primary} 或 Bean 名顺序决定用哪个，
 *       而"配置到底从哪读"变成一个只有读装配代码才知道的事实；</li>
 *   <li><b>悄悄退场</b>：某天有人把过渡实现删了、换成 DB 版，
 *       但<b>登记册（README §五 / 卡点总清单）没同步</b> ——
 *       此后所有人读到的"当前仍走 seed 文件"是过时信息，而没有任何东西会红。</li>
 * </ul>
 * 本门禁把这条纪律从"注释承诺"升级为<b>构建期事实</b>：过渡来源的<b>全集</b>被机械核对，
 * 多一个、少一个、改一个都必须同时更新 {@link #REGISTERED_TRANSITION_SOURCES} 并显式表态。
 *
 * <h2>🛑 为什么"全集"必须从源树机械派生，而不是手抄一个列表</h2>
 * 手抄的列表有一个已知的、必然发生的失效方式：<b>静默分叉</b>。
 * 本仓已有先例 —— 登记册说"五个 {@code ConfigSeed*ProfileSource}"，
 * 而源树里实际有<b>六个</b> {@code ConfigSeed*} 类
 * （多一个 {@link ConfigSeedContraindicationSource}，且它<b>不实现任何端口</b>）。
 * 手抄口径一错，"哪几条真的在生产链路上"就跟着错，而这个错不会有任何症状。
 * 故本类<b>先</b>扫源树得到真实全集，<b>再</b>与登记集比对 ——
 * 两个集合都必须精确相等，任一方向不等即红。
 *
 * <h2>为什么是构建期（无 DB）</h2>
 * 它断言的对象是"生产代码的形状"（有几个过渡来源、各自实现哪个端口、文档是否声明过渡），
 * 与数据库里有什么无关。放进真库门禁会让"本机没装 PG"变成一个假红，
 * 而这条纪律恰恰应该在<b>每一次构建</b>上跑。
 */
@DisplayName("B-3 配置来源过渡态：全集机械核对 · 端口唯一实现 · 过渡声明在位")
class ConfigSourceTransitionGateTest {

    /** 仓库根（skeleton/）。测试的工作目录是模块目录 dy-app，故上一级即根。 */
    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    /** 生产主源树根 —— 门禁只扫 main，不扫 test（测试替身不是生产装配的一部分）。 */
    private static final Path MAIN_JAVA = SKEL.resolve("dy-app/src/main/java");

    /**
     * 六个过渡来源的<b>登记集</b>（简单类名）—— 必须与源树机械派生出的全集<b>精确相等</b>。
     *
     * <p>🛑 改动它的正确姿势（不是"顺手加一个名字"）：
     * <ul>
     *   <li>新增过渡来源 ⇒ 必须同时在本表登记，并在 {@link #TRANSITION_SOURCES_WITH_A_PORT}
     *       或 {@link #PORTLESS_TRANSITION_SOURCES} 中表态它有没有端口；</li>
     *   <li>把某个过渡来源换成 DB 实现 ⇒ 必须先删掉过渡实现类（否则端口出现两个实现，
     *       {@code no_config_port_has_more_than_one_implementation} 会红），
     *       再把它从本表移出，并同步 README §五与卡点总清单 B-3。</li>
     * </ul>
     */
    private static final Set<String> REGISTERED_TRANSITION_SOURCES = Set.of(
            "ConfigSeedCrossStoreProfileSource",
            "ConfigSeedContraindicationSource",
            "ConfigSeedDerivedProfileSource",
            "ConfigSeedBandProfileSource",
            "ConfigSeedRefundProfileSource",
            "ConfigSeedScaleProfileSource");

    /**
     * 实现了域端口的过渡来源 → 其端口接口（简单名）。
     *
     * <p>这五个各自的端口是"收尾令完成后换一个读 DB 的实现、消费方零改动"这句话的<b>承载物</b>：
     * 端口在，替换才是一次装配改动而不是一次重构。故每个都必须在位且被唯一实现。
     */
    private static final Map<Class<?>, Class<?>> TRANSITION_SOURCES_WITH_A_PORT = Map.of(
            ConfigSeedCrossStoreProfileSource.class, CrossStoreProfileSource.class,
            ConfigSeedDerivedProfileSource.class, DerivedProfileSource.class,
            ConfigSeedBandProfileSource.class, BandVisibilitySource.class,
            ConfigSeedRefundProfileSource.class, RefundProfileSource.class,
            ConfigSeedScaleProfileSource.class, ScaleProfileSource.class);

    /**
     * 不实现任何端口的过渡来源。
     *
     * <p>🛑 刻意<b>不是</b>"遗漏"：{@link ConfigSeedContraindicationSource}（{@code #8} 禁忌清单）
     * 的契约 B1 只有 200 / 400 两个响应码、<b>没有 500</b>，故它的 fail-closed 方向
     * 与其它域<b>相反</b>（清单为空时照常推导 + 显式登记，而不是抛）——
     * 这条差异意味着它无法被塞进一个"统一的端口 + 统一的 fail-closed"抽象。
     * 本门禁把这个"唯一一个没有端口的过渡来源"钉成<b>可核对的事实</b>，
     * 使"将来有没有人给它补一个端口"必须显式表态。
     */
    private static final Set<Class<?>> PORTLESS_TRANSITION_SOURCES = Set.of(
            ConfigSeedContraindicationSource.class);

    // ------------------------------------------------------------------ ① 全集机械核对

    @Test
    @DisplayName("🛑 源树里 ConfigSeed* 类全集，必须与登记集【精确相等】（多一个少一个都红）")
    void transition_sources_are_exactly_the_registered_set() throws IOException {
        Set<String> scanned = scanConfigSeedClassNames();

        // 元层判别力自证：扫描本身必须真的扫到了东西。
        // 若 MAIN_JAVA 路径写错（例如重构后目录搬家），Files.walk 会安静地返回空流，
        // 下面的"两个集合相等"就会退化成"空集 == 空集"—— 一个恒绿的门禁。
        assertFalse(scanned.isEmpty(),
                "扫描不到任何 ConfigSeed* 类 —— 门禁退化成恒绿。检查 MAIN_JAVA 是否仍指向生产主源树: " + MAIN_JAVA);

        assertEquals(new TreeSet<>(REGISTERED_TRANSITION_SOURCES), new TreeSet<>(scanned),
                "🛑 过渡来源的全集与登记集不一致。\n"
                        + "  源树实扫: " + new TreeSet<>(scanned) + "\n"
                        + "  登记集:   " + new TreeSet<>(REGISTERED_TRANSITION_SOURCES) + "\n"
                        + "处置：新增过渡来源 → 登记进 REGISTERED_TRANSITION_SOURCES；\n"
                        + "      换成 DB 实现 → 先删过渡实现类（否则端口双实现也会红），再移出登记集，\n"
                        + "      并同步 README §五「未完成项与 TODO」与卡点总清单 B-3。");
    }

    // ------------------------------------------------------------------ ② 端口唯一实现

    @Test
    @DisplayName("🛑 五个域端口各自恰有一个过渡实现 —— 不得出现『第二个实现』的模糊装配")
    void no_config_port_has_more_than_one_implementation() throws IOException {
        // 从源树派生"谁是端口实现"，而不是信任上面手写的 Map ——
        // 两处口径如果分叉（Map 说 5 个、源树里有 6 个实现某个端口），本断言必须红。
        Map<String, List<String>> implementationsByPort = scanImplementationsByPort();

        for (Map.Entry<Class<?>, Class<?>> e : TRANSITION_SOURCES_WITH_A_PORT.entrySet()) {
            String portName = e.getValue().getSimpleName();
            List<String> impls = implementationsByPort.getOrDefault(portName, List.of());

            assertEquals(1, impls.size(),
                    "🛑 端口 " + portName + " 的过渡实现应恰有 1 个，实际: " + impls + "。\n"
                            + "若有 2 个（例如新增了 DB 版却没删 seed 版），Spring 按类型注入时\n"
                            + "『配置到底从哪读』就变成一个只有读装配代码才知道的事实 —— \n"
                            + "这正是 B-3 收尾时最需要避免的模糊态。");
            assertEquals(e.getKey().getSimpleName(), impls.get(0),
                    "端口 " + portName + " 的实现类与登记不符");
        }
    }

    @Test
    @DisplayName("唯一一个『不实现端口』的过渡来源必须仍是 #8 禁忌清单，且端口实现恰为 5 个")
    void the_portless_transition_source_is_registered_and_unique() throws IOException {
        Map<String, List<String>> byPort = scanImplementationsByPort();
        int totalImplementations = byPort.values().stream().mapToInt(List::size).sum();

        assertEquals(TRANSITION_SOURCES_WITH_A_PORT.size(), totalImplementations,
                "实现端口的过渡来源应恰为 " + TRANSITION_SOURCES_WITH_A_PORT.size()
                        + " 个，实际 " + totalImplementations + " 个 —— "
                        + "PORTLESS_TRANSITION_SOURCES 的登记（" + PORTLESS_TRANSITION_SOURCES.size() + " 个）需要复核。"
                        + "实际分布: " + byPort);

        assertEquals(1, PORTLESS_TRANSITION_SOURCES.size(),
                "『无端口的过渡来源』必须恰有 1 个（#8 禁忌清单，契约 B1 无 500 码，fail-closed 方向相反）");
        assertTrue(PORTLESS_TRANSITION_SOURCES.contains(ConfigSeedContraindicationSource.class),
                "无端口的那一个必须是 ConfigSeedContraindicationSource");
    }

    // ------------------------------------------------------------------ ③ 过渡声明在位

    @Test
    @DisplayName("🛑 五个端口来源的 describeSource() 必须自陈『过渡』（防悄悄退场留下过时登记）")
    void every_transition_source_declares_itself_as_a_transition() {
        // 实例化而非读源码文本：describeSource() 是这条口径进日志 / 进证据时真正被打印的东西。
        // 断言它的返回值，等价于断言"运维在日志里看到的那句话里有没有『过渡』"——
        // 而源码文本里的 Javadoc 不会被打印，只断言文本会让"改了返回值却留着注释"漏网。
        for (Map.Entry<Class<?>, Class<?>> e : TRANSITION_SOURCES_WITH_A_PORT.entrySet()) {
            String described = invokeDescribeSource(e.getKey());
            assertTrue(described.contains("过渡"),
                    "🛑 " + e.getKey().getSimpleName() + ".describeSource() 的返回串里找不到『过渡』字样。\n"
                            + "实际返回: " + described + "\n"
                            + "这几个类的承诺是『配置真相源落库后应替换为 DB 读取实现』——\n"
                            + "若自陈里删掉『过渡』，日志读者会以为它读的就是 DB（而它读的是 classpath 上的 seed 文件），\n"
                            + "这正是 B-3 这类『登记滞后于代码』偏差的成因。");
        }
    }

    @Test
    @DisplayName("🛑 每个过渡来源的 SEED_RESOURCE 常量必须精确指向 classpath 上的声明文件")
    void seed_resource_constant_points_to_the_classpath_seed_file() throws IOException {
        // 用正则抓"常量赋值那一行"的字面量，而不是全文 contains ——
        // 全文 contains 会被 Javadoc 里同名的路径字符串满足（注释里也写着这个路径），
        // 于是"改了常量但没改注释"这种最可能发生的改动会漏网。
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "SEED_RESOURCE\\s*=\\s*\"([^\"]+)\"");
        for (String simpleName : new TreeSet<>(REGISTERED_TRANSITION_SOURCES)) {
            String source = readSourceBySimpleName(simpleName);
            java.util.regex.Matcher m = p.matcher(source);
            assertTrue(m.find(), simpleName + " 里找不到 SEED_RESOURCE 常量赋值");
            assertEquals("db/config/02_slots_seed.sql", m.group(1),
                    "🛑 " + simpleName + " 的 SEED_RESOURCE 常量应精确为 "
                            + "db/config/02_slots_seed.sql（classpath 上的总部层声明文件），"
                            + "实际: " + m.group(1) + "\n"
                            + "点名来源是『这条口径从哪来』可回溯的前提 —— 而 B-3 的核心正是"
                            + "『来源是 seed 文件而不是 DB』这一点尚未对总部兑现。");
        }
    }

    /** 反射实例化 + 调 describeSource()；不依赖任何 DB（describeSource 只返回一个常量串）。 */
    private static String invokeDescribeSource(Class<?> impl) {
        try {
            Object instance = impl.getDeclaredConstructor().newInstance();
            Object r = impl.getMethod("describeSource").invoke(instance);
            return String.valueOf(r);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "无法实例化 / 无法调用 " + impl.getSimpleName() + ".describeSource() —— "
                            + "这几个过渡来源必须保留无参构造，因为它们的行为不依赖任何外部资源"
                            + "（这正是『过渡实现』能被独立验证的前提）", ex);
        }
    }

    // ------------------------------------------------------------------ ④ 端口契约未被偷偷塞进租户维度

    @Test
    @DisplayName("🛑 五个域端口必须仍是无租户维度的『启动期口径端口』")
    void config_ports_carry_no_tenant_dimension() {
        // 这条守的是 B-3 收尾时最可能踩的一个坑：有人为了让 ProfileSource 能读 app_config
        // (租户层生效值)，给端口的方法签名塞进 tenantId。
        // 后果：端口从"总部层声明"漂移成"租户层生效值"，
        // 而 01_truth_source_ddl.sql 的读路径纪律逐字写着
        // 「config_slot.initial_value 仅在新租户初始化时被读一次」——
        // 于是出现一个既不是总部层、也不是规范的租户层的第三类读路径。
        List<Class<?>> ports = List.of(
                CrossStoreProfileSource.class, DerivedProfileSource.class,
                BandVisibilitySource.class, RefundProfileSource.class, ScaleProfileSource.class);

        for (Class<?> port : ports) {
            assertTrue(port.isInterface(), port.getSimpleName() + " 必须是接口（端口）");
            java.util.Arrays.stream(port.getDeclaredMethods()).forEach(m -> {
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(p.getSimpleName().toLowerCase().contains("tenant"),
                            "🛑 " + port.getSimpleName() + "#" + m.getName()
                                    + " 出现租户类型参数（" + p.getName() + "）——\n"
                                    + "这五个端口是『启动期总部层口径』端口，不带租户维度。\n"
                                    + "一旦引入租户维度，它们就从『总部层声明』漂移成『租户层生效值』，\n"
                                    + "而 config_slot.initial_value 的读路径纪律（只在新租户初始化时读一次）\n"
                                    + "会被间接绕过，造出第三类读路径。");
                }
            });
        }
    }

    @Test
    @DisplayName("五个端口各自都声明了 raw()/matrix()/rangeRuleJson() 取值方法与 describeSource()")
    void each_port_exposes_a_value_accessor_and_a_self_description() {
        List<Class<?>> ports = List.of(
                CrossStoreProfileSource.class, DerivedProfileSource.class,
                BandVisibilitySource.class, RefundProfileSource.class, ScaleProfileSource.class);

        for (Class<?> port : ports) {
            Set<String> names = java.util.Arrays.stream(port.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
            assertTrue(names.stream().anyMatch(n -> n.equals("raw") || n.equals("matrix")
                            || n.equals("rangeRuleJson")),
                    port.getSimpleName() + " 必须声明一个取值方法（raw/matrix/rangeRuleJson）");
            assertTrue(names.contains("describeSource"),
                    port.getSimpleName() + " 必须声明 describeSource() —— "
                            + "『这次判定按哪份口径做的』必须可回溯（进日志 / 进证据）");
        }
    }

    // ------------------------------------------------------------------ 扫描实现

    /**
     * 扫生产主源树，返回 {@code ConfigSeed*} 类的简单名全集。
     *
     * <p>判据刻意<b>不看包名</b>：过渡实现散在五个域的 {@code service} 包下，
     * 靠包名匹配会在任何一次包结构调整后静默扫空。判据是<b>类名前缀</b>，
     * 而"前缀"这件事本身由"类名必须是 ConfigSeedXxx"这条可读约定承载。
     */
    private static Set<String> scanConfigSeedClassNames() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("ConfigSeed") && n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * 扫生产主源树，把每个 {@code ConfigSeed*} 类实现的<b>域端口</b>归组。
     *
     * <p>只认"实现了一个接口且该接口是域端口（{@code *ProfileSource} / {@code *VisibilitySource}）"
     * 的情形 —— 不是所有 {@code ConfigSeed*} 类都实现端口（见
     * {@link #PORTLESS_TRANSITION_SOURCES}），把无端口的那一个单独表态。
     *
     * <p>用<b>文本</b>提取 {@code implements} 子句而不是反射：反射需要先加载类，
     * 而"某个类加载不了"会把门禁的失败原因变成 ClassNotFound（一个与门禁无关的错误）；
     * 文本提取的对象就是源文件本身 —— 与"生产装配里有什么"是同一个东西，
     * 且失败原因是可读的。
     */
    private static Map<String, List<String>> scanImplementationsByPort() throws IOException {
        Map<String, List<String>> byPort = new java.util.TreeMap<>();
        for (String simpleName : scanConfigSeedClassNames()) {
            String source = readSourceBySimpleName(simpleName);
            // 抓 "class X ... implements A, B {" 里的接口名（简单名即可，够本门禁用）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("class\\s+\\w+[^{]*?implements\\s+([^{]+)\\{", java.util.regex.Pattern.DOTALL)
                    .matcher(source);
            if (!m.find()) {
                continue; // 无 implements 子句 = 该来源不实现端口
            }
            for (String raw : m.group(1).split(",")) {
                String iface = raw.trim();
                int dot = iface.lastIndexOf('.');
                if (dot >= 0) {
                    iface = iface.substring(dot + 1);
                }
                iface = iface.replaceAll("\\s+", "");
                if (iface.endsWith("ProfileSource") || iface.endsWith("VisibilitySource")) {
                    byPort.computeIfAbsent(iface, k -> new java.util.ArrayList<>()).add(simpleName);
                }
            }
        }
        return byPort;
    }

    /** 按简单类名读生产主源树里的源文件（找不到即抛，绝不返回空串）。 */
    private static String readSourceBySimpleName(String simpleName) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            Path p = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(simpleName + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "生产主源树里找不到 " + simpleName + ".java —— 登记集指向一个不存在的类。"
                                    + "MAIN_JAVA=" + MAIN_JAVA));
            return Files.readString(p, StandardCharsets.UTF_8);
        }
    }
}