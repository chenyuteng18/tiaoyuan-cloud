package com.diaoyuanyun.dy.app.provisioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>RLS 注入机制的「文档机制 vs 实际机制」门禁</b> —— 把
 * 「README 记为 L2 的那条路径在本仓<b>从未被触发</b>，真正承担 RLS 注入的是另一条路径」
 * 从「读代码才发现的分歧」升级为「构建期事实」。
 *
 * <h2>它堵的是什么洞</h2>
 * README §三 的 ADR-02 逐字写着：
 * <pre>
 * L2 {@code dy-tenancy/RlsSessionAspect}({@code SET LOCAL}) + V1 中 ENABLE+FORCE ROW LEVEL SECURITY
 * </pre>
 * 读这句话的人会合理地推断：只要某个数据访问方法带上 {@code @RlsScoped}，
 * 进它之前 RLS 上下文就会被设好。但本仓的<b>真实</b>机制是另一条：
 * <pre>
 * 23 个类各自定义一个私有的 {@code <T> T inTenant(String tenantId, Supplier<T> body)}，
 * 在 <b>短事务内</b>先 {@code SET LOCAL app.tenant_id = '<uuid>'} 再执行业务 SQL
 * （其中 1 个是 dy-config 的 {@code JdbcConfigSupport}，另 22 个是 dy-app 各域的 {@code *Ledger}
 *  / {@code *Repository}；该数随边界移动而增长，见 {@link #LEDGER_CARRIERS}）。
 * </pre>
 * 两条机制<b>不是覆盖关系，而是替代关系</b>：切面的切点是
 * {@code @Before("@annotation(...RlsScoped)")}，而生产代码里
 * {@code @RlsScoped} 的<b>使用数为 0</b>（唯一一处出现是它自己的定义文件
 * {@code RlsScoped.java}，声明形态 {@code public @interface RlsScoped} 并不构成"使用"）。
 * 切点匹配数为零 ⇒ 那个 {@code @Before} 永远不触发 ⇒ 它对本仓的租户隔离
 * <b>没有贡献</b>。
 *
 * <h2>🛑 这不是安全漏洞（必须先说清楚，否则会被误读）</h2>
 * 实测还显示：<b>覆盖面是完整的</b>——23 个 {@code inTenant} 载体
 * 覆盖了全部需要 RLS 上下文的数据访问路径，且它们是<b>显式传参</b>
 * （{@code inTenant(tenantId, ...)}），比依赖 ThreadLocal 的切面<b>更难被误用</b>：
 * 一个方法想访问租户数据，如果不接 {@code tenantId} 参数，它根本写不出查询。
 * 故本类的定性是「<b>文档与实现的分歧</b>」，不是「静默的安全陷阱」。
 *
 * <h2>那为什么还要有本类（两条具体危害）</h2>
 * <ol>
 *   <li><b>它会误导下一个人做错事。</b>有人照 README 给新仓储方法加上 {@code @RlsScoped}，
 *       以为"上下文有人管了"，于是在事务之外、或不经 {@code inTenant} 直接查 ——
 *       RLS 策略读到空 {@code app.tenant_id}，查询<b>静默返回零行</b>
 *       （fail-closed 策略的形态是"看起来正常的空结果"，不是报错）。
 *       这正是 {@code JdbcConfigSupport} 注释里逐字警告过的那种误用。</li>
 *   <li><b>它会静默地漂移。</b>正因为两条机制是替代关系，任何一侧单独变更都不会红：
 *       把 23 个 {@code inTenant} 删掉一个（改用切面），或在别处新增一处
 *       {@code @RlsScoped}，构建都照样 {@code BUILD SUCCESS}。</li>
 * </ol>
 *
 * <h2>判据（六条，缺一不可）</h2>
 * <ol>
 *   <li><b>注解使用数恒为 0</b>：生产代码（剥离注释与字符串字面量后）
 *       {@code @RlsScoped} 使用数必须为 0；</li>
 *   <li><b>载体侧完整性</b>：每个定义了 {@code <T> T inTenant(...)} 的类，
 *       <b>必须在同文件</b>出现 {@code SET LOCAL app.tenant_id} ——
 *       定义了一个「进入租户上下文」的入口却不设上下文，是最危险的空壳；</li>
 *   <li><b>载体集合双向一致</b>：扫出来的载体集合与 {@link #LEDGER_CARRIERS} 登记表
 *       必须完全相等（漏登记红 + 僵尸登记红），且集合非空（自证）；</li>
 *   <li><b>切面方法零外部调用</b>：{@code applyTenantSession} 在
 *       {@code RlsSessionAspect.java} <b>之外</b>的生产代码里出现 0 次
 *       —— 它既没被切点触发（判据①），也没被手工调用；</li>
 *   <li><b>剥离器有真判别力</b>（元层自证）：在一段合成的"注释态 / 字符串态 / 代码态"
 *       混合样本上，剥离器必须<b>恰好</b>剥掉前两者、保留第三者。
 *       只看仓库现状的话，"剥离器坏了"与"仓库确实没有"无法区分；</li>
 *   <li><b>文档机制的两个类仍然存在</b>：{@code RlsScoped.java} 与
 *       {@code RlsSessionAspect.java} 必须存在，且切面里仍有
 *       {@code @Before} 与 {@code RlsScoped} 字样 ——
 *       使「删掉这条死机制」或「改掉它的切点」都必须成为一次<b>显式编辑</b>
 *       （改本类 + 同步 README ADR-02），而不是一次无人察觉的漂移。</li>
 * </ol>
 *
 * <h2>本类红的正确处置（🛑 不要"改断言让它绿"）</h2>
 * 三条路，每条都是显式动作：
 * <ul>
 *   <li><b>若决定启用切面</b>（真的开始用 {@code @RlsScoped}）：更新 README ADR-02
 *       把 L2 写实，并在此登记首批被标注的方法；</li>
 *   <li><b>若决定删除死机制</b>（删 {@code RlsScoped} + 切面）：同时删除本类判据⑥，
 *       并在 README ADR-02 的 L2 里把描述换成 {@code inTenant} 载体；</li>
 *   <li><b>若新增了一个 {@code inTenant} 载体</b>：把它登记进 {@link #LEDGER_CARRIERS}，
 *       并确认它有真库隔离覆盖（见 {@code RlsCoverageGateTest}）。</li>
 * </ul>
 * 该处置权属<b>架构 owner</b>（已登记为卡点清单 B-9），本类只负责把状态钉住，不替谁拍板。
 *
 * <h2>它【不】回答什么（诚实边界）</h2>
 * <ul>
 *   <li><b>不判"哪条机制更好"</b> —— 那是架构裁定；</li>
 *   <li><b>不判可达性</b>：证明不了某处 {@code inTenant} 真的会被业务路径调用到。
 *       它排除的是"完全没有载体"，排不掉"有载体但没人用"（与
 *       {@code ProvisioningBoundaryGateTest} 同一处边界）。</li>
 * </ul>
 */
class RlsInjectionRealityGateTest {

    /** 生产源码扫描范围（与 {@code ProvisioningBoundaryGateTest} 同一套模块口径）。 */
    private static final List<String> SCANNED_MODULES = List.of(
            "dy-common", "dy-tenancy", "dy-security", "dy-web",
            "dy-audit", "dy-config", "dy-crypto", "dy-app");

    /**
     * 注解<b>使用</b>形态 —— <b>必须同时覆盖短名与全限定名</b>。
     *
     * <p>🛑 这里踩过一个坑（由反向验证 114 的 C1 抓出并修复）：
     * 初版写成 {@code Pattern.compile("@RlsScoped\\b")}，只认短名。
     * 而 Java 合法的注解写法里，<b>全限定名</b>形态
     * （{@code @com.diaoyuanyun.dy.tenancy.rls.RlsScoped}）同样是一次真实使用 ——
     * 它在两个林德类里都不需要 {@code import}，恰恰是"顺手加一个注解"时最省事的写法。
     * 若门禁只认短名，那次真实启用会被<b>静默放过</b>（判据①假绿）。
     *
     * <p>而 {@code @interface RlsScoped}（<b>声明</b>形态）不得被匹配 ——
     * 声明不是使用。故正则要求 {@code @} 与 {@code RlsScoped} 之间
     * 只能是"点分隔的标识符链"，不能是 {@code interface} 这个关键字整体。
     */
    private static final Pattern ANNOTATION_USE =
            Pattern.compile("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*RlsScoped\\b");

    /** {@code inTenant} 的<b>定义</b>形态：泛型方法与形参列表。刻意不匹配 {@code inTenant(tenantId, ...)} 调用。 */
    private static final Pattern IN_TENANT_DEFINITION =
            Pattern.compile("<T>\\s*T\\s+inTenant\\s*\\(");

    /**
     * RLS 上下文注入的唯一有效标志。
     *
     * <p>🛑 这里也踩过一个坑（同样由反向验证 114 的 C2 抓出并修复）：
     * 初版用一个裸字符串常量 + {@code contains(...)} 判断。而
     * {@code "SET LOCAL app.tenant_id_probe = '...'"} <b>包含</b>子串
     * {@code "SET LOCAL app.tenant_id"} ⇒ 把一个真正坏掉的注入点判成了合格。
     * 前缀匹配是"断言有 X"这一类判据的典型假绿来源。
     *
     * <p>修正：改为<b>词法完整</b>的正则 —— 变量名后必须直接跟 {@code =}
     * （允许空白），故任何 {@code app.tenant_id<后缀>} 都不会被误认。
     */
    private static final Pattern SET_LOCAL_STMT =
            Pattern.compile("SET\\s+LOCAL\\s+app\\.tenant_id\\s*=");

    /** 供断言消息使用的人读标志串。 */
    private static final String SET_LOCAL_MARKER = "SET LOCAL app.tenant_id";

    /** 切面入口方法名。 */
    private static final String ASPECT_METHOD = "applyTenantSession";

    // ------------------------------------------------------------------
    // 🛑 两级剥离粒度 —— 本类所有判据都必须明确声明自己用哪一级
    // ------------------------------------------------------------------
    //
    // 为什么必须有两级，而不是"一律剥字符串"：
    //
    //   本仓的 SQL 是 Java 字符串常量。`SET LOCAL app.tenant_id = '...'` 与
    //   切点表达式 `@annotation(...RlsScoped)` 都【住在字符串字面量里】。
    //   于是"剥不剥字符串"会得出相反的结论：
    //
    //     · 判据①（注解使用数为 0）：必须 STRIP_STRINGS。
    //       字符串里写 `"@RlsScoped"` 只是文本，不是注解被使用；
    //       用 KEEP_STRINGS 会把任何一条文档字符串误报成"使用了注解"。
    //
    //     · 判据④（切点表达式里仍有 RlsScoped）：必须 KEEP_STRINGS。
    //       切点形式就是 `@Before("@annotation(...RlsScoped)")`，
    //       用 STRIP_STRINGS 会把切点本身剥掉 → 判据⑥ 会对着一个正确的实现报红。
    //
    //   两个粒度都要有【判别力自证】（判据⑤），否则"扫不到"与"不存在"无法区分。
    //   这一处正是 ProvisioningBoundaryGateTest 那条教训的同一族：
    //   「用全局单一口径去断言两个相反的事实，必然有一半是假的」。

    /** 剥离粒度：连字符串字面量一起剥掉（适合断言"代码里没有 X 的使用"）。 */
    private static final boolean STRIP_STRINGS = true;
    /** 剥离粒度：只剥注释，保留字符串字面量（适合断言"字符串常量里仍有 X"，如切点表达式）。 */
    private static final boolean KEEP_STRINGS = false;

    /**
     * 「字符串里逐字出现 {@code @RlsScoped}」的白名单（相对路径）。
     *
     * <p>为什么需要白名单而不是一律禁止：切面里有一条<b>告警消息</b>
     * （{@code "@RlsScoped 方法必须在事务内调用"}），它是给未来的使用者看的提示，
     * 逐字写出注解名是<b>恰当</b>的。这类"有意的文本提及"要与"误把注解写进字符串"
     * 区分开，故登记在此。
     *
     * <p>白名单必须与扫描结果<b>双向核对</b>（见判据①）：多一个就红（防止有人
     * 往白名单里塞新文件来消红），少一个也红（防止"整类命中"被静默放过）。
     */
    private static final Set<String> STRING_MENTION_ALLOWLIST = new LinkedHashSet<>(List.of(
            "dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/rls/RlsSessionAspect.java"));

    private static final String ASPECT_RELATIVE =
            "dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/rls/RlsSessionAspect.java";
    private static final String ANNOTATION_RELATIVE =
            "dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/rls/RlsScoped.java";

    /**
     * <b>实际机制</b>的载体登记表：定义了 {@code <T> T inTenant(...)} 的类（相对 skeleton 根的路径）。
     *
     * <p>🛑 <b>这张表是"绑住现实"的锚</b>，不是"允许清单"：判据③要求扫描结果与它<b>完全相等</b>。
     * 新增一个载体而不登记 → 红；删掉一个载体而不销账 → 红（僵尸登记）。
     *
     * <p><b>实体来源（实测，非手抄）</b>：注释剥离后扫 8 模块 {@code src/main}，
     * 匹配 {@code <T> T inTenant(} 的文件数随边界移动而增长（每次移动都<b>显式</b>改本表）。
     * 初始口径为 20 个 = 19 个 dy-app 域仓储 + 1 个 dy-config 支撑类，此后
     * B-7 / B-10 / B-11 / B-12 / B-13 各自新增 1 个，<b>当前为 23 个</b>
     * （22 个 dy-app 域仓储 + 1 个 dy-config 支撑类）。
     * 其中 {@code dy-config/JdbcConfigSupport} 是配置真相源的载体
     * （它的注释逐字写明"在【所有】路径上都成立，而不是只在被 {@code @RlsScoped}
     * 标注的方法上成立"——那正是本类要钉住的分歧的原始出处）。
     *
     * <p><b>2026-09-27 新增第 19 个（B-7 组织开通）</b>：{@code OrganizationProvisioningRepository}。
     * 🛑 它与其他 18 个有一个<b>性质上</b>的差别，必须在此写明，否则会误导后来的读者：
     * 其余 18 个都是"<b>先有租户上下文、再读/写该租户的数据</b>"（入口是
     * {@code inTenant(tenantId, …)}）；而本类的 {@code provision()} 是
     * "<b>先写 tenant 行（该表无 RLS，无需上下文），再建立上下文，然后写组织树</b>"。
     * 即它<b>创建</b>那个其他 18 个所依赖的上下文。
     * 两个入口都在同文件：{@code provision()}（自建上下文）与 {@code inTenant()}（复用上下文，
     * 供 {@code countStores} / {@code countStaff} / {@code staffRows} 这类开通后的核对读路径）。
     * 判据②只要求"定义了 {@code inTenant} 的类必须在同文件出现 SET LOCAL"——
     * 本类满足；而 {@code provision()} 那条自建上下文的路径走的是 V15 的
     * {@code provision_tenant()} 函数（用 {@code set_config} 而非 {@code SET LOCAL}），
     * 故它不在这条判据的覆盖面上 —— 它由 {@code OrganizationProvisioningE2ETest} 的真库断言守着。
     *
     * <p><b>2026-09-27 新增第 20 个（B-10 手环绑定通路）</b>：{@code BandBindingLedger}。
     * 它与 {@code BandLedger}（同目录、已在册）是<b>同一张 {@code band} 表的两个体</b>，
     * 差别在<b>写入口的权威层级</b>，这一区分必须在此写明，否则后来者会问"为什么两个都要在"：
     * <ul>
     *   <li>{@code BandLedger} = <b>契约端点</b>的读写方（E1 {@code POST /band/sync-batches}
     *       / E2 {@code POST /band/telemetry}），走的是"客户端上报"链路；</li>
     *   <li>{@code BandBindingLedger} = <b>运维通路</b>的写入方（绑定/解绑），
     *       它是 {@code band} 表在生产代码里的<b>唯一 INSERT 来源</b>，
     *       底层调 V17 的 {@code bind_band()} / {@code unbind_band()} 原语 ——
     *       而那两个原语<b>自己建立并自证租户上下文</b>（{@code set_config(..., is_local := true)}
     *       + {@code assert_tenant_context()} + 上下文一致性守卫）。</li>
     * </ul>
     * 🛑 因此本类的 {@code inTenant()} 在本载体上承担的是<b>读侧</b>
     * （{@code findActiveBandId} / {@code historyOf} / {@code countActiveInTenant}）的上下文，
     * 而<b>写侧</b>的上下文由库层原语自建 —— 两条路径都设了上下文，且各自有真库证据：
     * 读侧由 {@code RlsCoverageGateTest} 的 {@code band} 覆盖（已登记），
     * 写侧由 {@code BandBindingGateTest} 的判据⑦（上下文一致性守卫）与
     * V17 自证 (b1)(b2)(b3)、反向验证 118 的 I(b)/(b7)/(c7) 守着。
     * <p>判据②要求"定义了 {@code inTenant} 的类必须在同文件出现 {@code SET LOCAL}"—— 本类满足。
     *
     * <p><b>2026-09-27 新增第 23 个（B-13 结案归档通路）</b>：{@code CaseArchiveLedger}。
     * 🛑 它与前两批（B-11 {@code DeviceLedger} / B-12 {@code ScaleLedger}）在结构上同形
     * （{@code inTenant} 只承担<b>读侧</b>上下文，写侧由 V20 的
     * {@code register_case_archive} 自建并自证），但有两处实质差别，必须写明：
     * <ol>
     *   <li><b>写侧的并发语义不同。</b>{@code device} / {@code scale} 的
     *       {@code ON CONFLICT} 推断目标<b>不含</b>租户维度（单列主键）但表内有租户列，
     *       撞号时函数必须 {@code RAISE}；{@code case_archive} 同样如此
     *       （{@code archive_id} 是唯一约束），而且它的后果<b>更隐蔽</b>：
     *       归档是业务链条的<b>末端</b>，一旦跨租户撞号被静默合并，
     *       下游没有任何第二步能把它证伪 —— 故 {@code RAISE} 是本域不可让渡的红线。</li>
     *   <li><b>读侧口径不同。</b>{@code latestArchiveOf} 走的是"按
     *       {@code archived_at} + 主键 tie-breaker <b>全序</b>取最新"，
     *       而非单值查询。少了 {@code SET LOCAL}，它会<b>恒返回 NULL</b> ——
     *       与"该客户从未归档"完全同形（fail-closed 的典型假绿形态）。</li>
     * </ol>
     * <p>此外本域的硬门禁数组含 5 项、警告数组 1 项，且「<b>手环类缺项不得反转为阻断</b>」
     * 是合规红线（P0-25 + README §5.3「三条不得触碰」③）——
     * 该红线由 {@code CaseArchiveGateTest} 判据⑤与反向验证 121 守着，不在本类覆盖面内。
     */
    private static final Set<String> LEDGER_CARRIERS = new LinkedHashSet<>(List.of(
            // ---- dy-app · 22 个域仓储 ----
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/repository/AssessmentLedger.java",
            // 🛑 B-13 新增（2026-09-27）：case_archive 结案归档写入方载体。
            //    inTenant 承担【读侧】上下文（latestArchiveOf / snapshotOf /
            //    countInTenant / isArchived），而【写侧】上下文由 V20 的
            //    register_case_archive 自建并自证（set_config + assert_tenant_context
            //    + 上下文一致性守卫）—— 故 register() 刻意【不】走 inTenant。
            //    🛑 与 B-11/B-12 的实质差别（两处，都必须在此写明）：
            //    (a) case_archive 的 ON CONFLICT 推断目标是单列主键 archive_id
            //        （不含租户维度），跨租户撞号必须由函数 RAISE ——
            //        而且它比 device/scale 更隐蔽：归档是链条末端，下游没有第二步能证伪；
            //    (b) 读侧 latestArchiveOf 走「tie-breaker 全序」而非单值查询，
            //        少了 SET LOCAL 它会恒返回 NULL，与"从未归档"同形（假绿）。
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/repository/CaseArchiveLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandBindingLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/crypto/repository/CryptoKeyLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/crypto/repository/DbShredTombstoneStore.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/ConsentLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/CustomerLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/IntakeProfileLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/IntakeProfileRevisionLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/ScreeningLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/repository/VerdictLedger.java",
            // 🛑 B-11 新增（2026-09-27）：device 表写入方载体。
            //    它的 inTenant 承担【读侧】上下文（statusOf / devicesOfStore /
            //    countActiveInTenant / isReferenceable），而【写侧】上下文由 V18 的
            //    register_device/retire_device 自建并自证（set_config + assert_tenant_context）。
            //    🛑 为什么两处都要设上下文：isReferenceable 用 FOR KEY SHARE 取锁，
            //    该锁的可见性同样受 RLS 约束 —— 不设上下文它会恒返回 false，
            //    而那会让"跨租户不可引用"这条断言退化成一条恒真断言（假绿）。
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/repository/DeviceLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/repository/DocTemplateLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/repository/FulfillmentLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/repository/OrganizationProvisioningRepository.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/repository/StoreRepository.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/repository/RefundReceiptLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/repository/RefundSubjectLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/repository/RefundWorkOrderLedger.java",
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/repository/ScaleItemBankRepository.java",
            // 🛑 B-12 新增（2026-09-27）：scale 表写入方载体。
            //    inTenant 承担【读侧】上下文（statusOf / versionOf /
            //    countActiveInTenant / countDeprecatedInTenant / canReferenceBaseline），
            //    而【写侧】上下文由 V19 的 register_scale/deprecate_scale 自建并自证。
            //    🛑 为什么它不能省：canReferenceBaseline 是本迁移【唯一的验收口径】
            //    （C2 那道 scaleExists 预检），而它走 inTenant ⇒
            //    少了这里的 SET LOCAL，那条验收会恒 false，从而在门禁里表现为
            //    "量表永远不可引用"—— 一个与"量表没建"同形的假红。
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/repository/ScaleLedger.java",
            // ---- dy-config · 1 个配置真相源支撑类 ----
            "dy-config/src/main/java/com/diaoyuanyun/dy/config/repository/JdbcConfigSupport.java"));

    // ------------------------------------------------------------------
    // 判据 ①
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生产代码里 @RlsScoped 的使用数恒为 0（切点零匹配 = L2 是文档机制）")
    void the_rls_scoped_annotation_is_never_used_in_production_code() throws IOException {
        // 🔴 粒度选择是关键：本判据必须用【剥掉字符串字面量】的版本。
        //    理由：字符串里出现 "@RlsScoped" 不构成"注解被使用"（那只是文本）。
        //    若用保留字符串的粒度，任何一条文档字符串都会把它误报成使用。
        Map<String, String> sources = productionSources(STRIP_STRINGS);
        assertFalse(sources.isEmpty(), "未扫到任何生产源码 —— 扫描器失效，门禁形同虚设");

        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Matcher m = ANNOTATION_USE.matcher(e.getValue());
            while (m.find()) {
                hits.add(e.getKey() + " (第 " + lineOf(e.getValue(), m.start()) + " 行)");
            }
        }

        // 双粒度自证：两个粒度都必须为 0。若只有"剥字符串"粒度为 0 而"保留字符串"粒度 > 0，
        // 说明命中的全在字符串字面量里 —— 那不是真实使用，但本判据的结论必须对这个差别有意识。
        List<String> hitsKeepingStrings = new ArrayList<>();
        Map<String, String> withStrings = productionSources(KEEP_STRINGS);
        for (Map.Entry<String, String> e : withStrings.entrySet()) {
            if (e.getValue().contains("@RlsScoped")) {
                hitsKeepingStrings.add(e.getKey());
            }
        }

        if (!hits.isEmpty()) {
            throw new AssertionError(
                    "生产代码里出现了 @RlsScoped 的使用，但 README ADR-02 的 L2 描述与"
                            + " RlsInjectionRealityGateTest 的登记表都还把它当作【文档机制】。\n"
                            + "命中: " + hits + "\n"
                            + "这意味着 L2 已被真正启用 —— 请一次性做完三件事，再变绿：\n"
                            + "  1) 更新 README §三 ADR-02 的 L2 描述（从「文档机制」改为「实际机制」）；\n"
                            + "  2) 确认每个被标注的方法确实在事务内、且确实经 inTenant 之外独立取到了"
                            + " 事务绑定连接（否则 SET LOCAL 与业务 SQL 不同连接，RLS 会静默零行）；\n"
                            + "  3) 在 RlsInjectionRealityGateTest 里登记首批被标注的方法（或调整本判据）。");
        }

        // 🛑 双粒度结论必须一致：保留字符串的粒度如果扫到了额外文件，
        //    那些命中就是「字符串里的文本提及」。它不构成「注解被使用」，
        //    但会误导读者 —— 除非它是【有意保留的告警消息】，那就要登记。
        Set<String> unexpected = new TreeSet<>(hitsKeepingStrings);
        unexpected.removeAll(STRING_MENTION_ALLOWLIST);
        assertTrue(unexpected.isEmpty(),
                "有生产源文件在【保留字符串字面量】的粒度下含有 @RlsScoped，且不在登记白名单里:"
                        + unexpected + "\n"
                        + "字符串里的文本提及不构成「注解被使用」，但会误导读者。"
                        + "请改写该字符串（用中文描述替代逐字写出注解名），"
                        + "或确认它是有意保留的告警消息后登记进 STRING_MENTION_ALLOWLIST。"
                        + "当前白名单: " + STRING_MENTION_ALLOWLIST);

        // 自证：确认扫描确实覆盖了 8 个模块（避免"扫了 0 个文件所以 0 命中"的假绿）
        long modulesSeen = SCANNED_MODULES.stream()
                .filter(mod -> sources.keySet().stream().anyMatch(k -> k.startsWith(mod + "/")))
                .count();
        assertEquals(SCANNED_MODULES.size(), modulesSeen,
                "扫描未能覆盖全部模块（部分模块下无源码 = 路径口径错）: " + SCANNED_MODULES);
    }

    // ------------------------------------------------------------------
    // 判据 ②
    // ------------------------------------------------------------------

    @Test
    @DisplayName("每个定义 inTenant 的类都必须在同文件 SET LOCAL app.tenant_id")
    void every_class_defining_in_tenant_also_sets_the_session_variable() throws IOException {
        // 🔴 KEEP_STRINGS：SET LOCAL app.tenant_id 住在 Java 字符串常量里，
        //    剥掉字符串会把它连同真实现一起剥没 → 判据②会对正确的代码报红。
        Map<String, String> sources = productionSources(KEEP_STRINGS);
        Set<String> carriers = carriersIn(sources);
        assertFalse(carriers.isEmpty(),
                "未扫到任何 inTenant 载体 —— 解析器失效，门禁形同虚设");

        List<String> shells = new ArrayList<>();
        for (String rel : carriers) {
            if (!SET_LOCAL_STMT.matcher(sources.get(rel)).find()) {
                shells.add(rel);
            }
        }
        assertTrue(shells.isEmpty(),
                "以下类定义了 inTenant（一个「进入租户上下文」的入口）却没有在同文件出现合格的 "
                        + SET_LOCAL_MARKER + " 语句（`SET LOCAL app.tenant_id = ...`）"
                        + " —— 这是空壳入口，调用它等于在【无上下文】下执行 SQL"
                        + "（RLS 策略会读到空租户 → 静默返回零行）:\n  - "
                        + String.join("\n  - ", shells));
    }

    // ------------------------------------------------------------------
    // 判据 ③
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inTenant 载体集合与登记表双向一致（漏登记红 + 僵尸登记红）")
    void the_set_of_in_tenant_carriers_is_exactly_the_registered_set() throws IOException {
        // 载体识别看的是 `<T> T inTenant(`（代码态），两个粒度结果相同；
        // 与判据② 保持同一粒度，避免"两处用不同口径"的隐性不一致。
        Set<String> actual = carriersIn(productionSources(KEEP_STRINGS));

        Set<String> unregistered = new TreeSet<>(actual);
        unregistered.removeAll(LEDGER_CARRIERS);
        assertTrue(unregistered.isEmpty(),
                "以下类新增了 inTenant 载体但未登记 —— 请登记进 RlsInjectionRealityGateTest.LEDGER_CARRIERS，"
                        + "并确认其访问的表在 RlsCoverageGateTest 里有真库隔离覆盖:\n  - "
                        + String.join("\n  - ", unregistered));

        Set<String> stale = new TreeSet<>(LEDGER_CARRIERS);
        stale.removeAll(actual);
        assertTrue(stale.isEmpty(),
                "登记表里有已不存在的 inTenant 载体（僵尸登记会掩盖真实覆盖缺口）:\n  - "
                        + String.join("\n  - ", stale));

        // 自证：集合非空 + 两侧规模一致（否则上面的"双向差集为空"是空集对空集的假绿）
        assertEquals(LEDGER_CARRIERS.size(), actual.size(),
                "载体数与登记数必须一致；实际=" + actual.size() + " 登记=" + LEDGER_CARRIERS.size());
        assertTrue(actual.size() >= 2,
                "inTenant 载体数异常少（实测应为 23：22 个 dy-app 域仓储 + 1 个 dy-config 支撑类）"
                        + "—— 若确为架构变更请显式更新登记表");
    }

    // ------------------------------------------------------------------
    // 判据 ④
    // ------------------------------------------------------------------

    @Test
    @DisplayName("切面方法 applyTenantSession 在自身定义之外零出现（既未触发、也未被手工调用）")
    void the_aspect_method_is_never_called_outside_its_own_definition() throws IOException {
        // 两个粒度都可：applyTenantSession 只以代码态出现（未在字符串里被提及）。
        Map<String, String> sources = productionSources(STRIP_STRINGS);
        assertTrue(sources.containsKey(ASPECT_RELATIVE),
                "切面源文件必须存在: " + ASPECT_RELATIVE);

        List<String> external = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (ASPECT_RELATIVE.equals(e.getKey())) {
                continue;
            }
            if (e.getValue().contains(ASPECT_METHOD)) {
                int n = countOf(e.getValue(), ASPECT_METHOD);
                external.add(e.getKey() + " ×" + n);
            }
        }
        assertTrue(external.isEmpty(),
                "切面方法 " + ASPECT_METHOD + " 在自己文件之外的生产代码里出现了。\n"
                        + "它本应【只】由 @Before 切点触发；一旦有人手工调用它，说明"
                        + "「RLS 上下文由切面自动兜住」这个前提被当成了事实 —— 而实际机制是"
                        + "各 *Ledger 的 inTenant。命中:\n  - " + String.join("\n  - ", external));

        // 自证：切面自己文件里确实有该方法（否则上面的"外部 0 处"可能因为方法已被改名/删除）
        assertTrue(sources.get(ASPECT_RELATIVE).contains(ASPECT_METHOD),
                "切面自身文件里找不到 " + ASPECT_METHOD + " —— 方法已被改名或删除，"
                        + "本判据的「外部零出现」随之失去意义");
    }

    // ------------------------------------------------------------------
    // 判据 ⑤：元层自证（剥离器判别力）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("剥离器判别力自证：注释态/字符串态被剥除，代码态被保留")
    void the_comment_and_string_stripper_has_real_discriminating_power() {
        // 合成样本：行注释 / 块注释 / 字符串 / 代码态各一（每个标志各 4 处）。
        // 🛑 样本本身不必是可编译的 Java —— 剥离器是纯词法扫描，它只认字符状态。
        //    刻意让【代码态】是裸文本（不进任何字符串），否则"剥字符串"这一级
        //    会把样本里的代码态也一起剥掉，判据⑤ 就变成了自证循环。
        String sample = String.join("\n",
                "// line comment mentions @RlsScoped and SET LOCAL app.tenant_id",
                "/* block comment also mentions @RlsScoped and SET LOCAL app.tenant_id */",
                "String s = \"@RlsScoped lives in a string; SET LOCAL app.tenant_id too\";",
                "@RlsScoped",
                "SET LOCAL app.tenant_id = 'x'");

        String stripped = stripComments(sample, STRIP_STRINGS);
        String kept = stripComments(sample, KEEP_STRINGS);

        assertTrue(sample.contains("@RlsScoped"),
                "样本前提不成立：原文应含 @RlsScoped");
        assertTrue(sample.contains(SET_LOCAL_MARKER),
                "样本前提不成立：原文应含 " + SET_LOCAL_MARKER);
        assertEquals(4, countOf(sample, "@RlsScoped"),
                "样本前提不成立：原文应含 4 处 @RlsScoped（行注释/块注释/字符串/代码态各一）");
        assertEquals(4, countOf(sample, SET_LOCAL_MARKER),
                "样本前提不成立：原文应含 4 处 " + SET_LOCAL_MARKER + "（行注释/块注释/字符串/代码态各一）");

        // 行注释与块注释在两级粒度下都必须被剥除
        assertFalse(stripped.contains("// line comment"),
                "剥离器未能剥掉行注释 —— 判据①会假绿（把注释里的提及当成真实使用）");
        assertFalse(stripped.contains("/* block comment"),
                "剥离器未能剥掉块注释 —— 判据①会假绿");
        assertFalse(kept.contains("// line comment"),
                "KEEP_STRINGS 粒度下也必须剥掉行注释（否则判据①的注释误报无从避免）");
        assertFalse(kept.contains("/* block comment"),
                "KEEP_STRINGS 粒度下也必须剥掉块注释");

        // 字符串字面量：STRIP 粒度剥除、KEEP 粒度保留
        assertFalse(stripped.contains("lives in a string"),
                "STRIP_STRINGS 粒度下字符串字面量内容应被剥除");
        assertTrue(kept.contains("lives in a string"),
                "KEEP_STRINGS 粒度下应保留字符串字面量内容 —— 粒度参数未生效，判据②⑥不可信");

        // 代码态必须【保留】：STRIP 粒度下恰 1 次（只有代码态那一处）
        assertEquals(1, countOf(stripped, "@RlsScoped"),
                "STRIP_STRINGS 粒度下应恰保留 1 处【代码态】@RlsScoped，实际剥后内容: " + stripped);
        assertEquals(1, countOf(stripped, SET_LOCAL_MARKER),
                "STRIP_STRINGS 粒度下应恰保留 1 处【代码态】" + SET_LOCAL_MARKER
                        + "，实际剥后内容: " + stripped);

        // ---- 另一级粒度（KEEP_STRINGS）必须给出【不同的、可预期的】结果 ----
        // 这正是本类需要两级粒度的证明：
        //   · 注释里的提及在两级下都消失（注释一律剥）；
        //   · 代码态的 @RlsScoped 在两级下都保留；
        //   · 字符串里的提及只在 KEEP_STRINGS 下保留 —— 判据① 必须用 STRIP_STRINGS
        //     才不会把"字符串里的文本"误报成"注解被使用"；
        //   · 字符串里的 SET LOCAL 只在 KEEP_STRINGS 下保留 —— 判据② 必须用 KEEP_STRINGS
        //     才不会把真实现剥成空。
        // 若两级给出相同结果，说明粒度参数没生效 → 本类所有判据都不可信。
        assertEquals(2, countOf(kept, "@RlsScoped"),
                "KEEP_STRINGS 粒度下应见 2 处 @RlsScoped（代码态 1 + 字符串态 1），"
                        + "实际剥后内容: " + kept);
        assertEquals(2, countOf(kept, SET_LOCAL_MARKER),
                "KEEP_STRINGS 粒度下应见 2 处 " + SET_LOCAL_MARKER + "（代码态 1 + 字符串态 1），"
                        + "实际剥后内容: " + kept);
        assertNotEquals(countOf(stripped, SET_LOCAL_MARKER), countOf(kept, SET_LOCAL_MARKER),
                "两级粒度对 " + SET_LOCAL_MARKER + " 给出了相同计数 —— 粒度参数失效，"
                        + "「用错粒度」这类真实缺陷将无法被发现");

        // inTenant 定义正则的判别力：只认定义，不认调用
        assertTrue(IN_TENANT_DEFINITION.matcher("<T> T inTenant(String tenantId, Supplier<T> body) {").find(),
                "inTenant 定义正则未能匹配其目标形态 —— 判据②③会因扫不到载体而假绿");
        assertFalse(IN_TENANT_DEFINITION.matcher("inTenant(tenantId, () -> jdbc.update(SQL))").find(),
                "inTenant 定义正则误匹配了【调用】形态 —— 会把调用点当成定义，登记表随之失真");

        // ---- 注解使用正则的判别力（两条，均由反向验证 114 的 C1 抓出的缺口固化为断言）----
        // 🛑 这两条是**反向验证抓出的门禁自身缺陷**的永久守卫。
        //    初版 `@RlsScoped\b` 只认短名 ⇒ 全限定名形态的真实使用会被静默放过（判据①假绿）。
        assertTrue(ANNOTATION_USE.matcher("    @RlsScoped").find(),
                "注解使用正则未匹配【短名】形态");
        assertTrue(ANNOTATION_USE.matcher("    @com.diaoyuanyun.dy.tenancy.rls.RlsScoped").find(),
                "注解使用正则未匹配【全限定名】形态 —— 这是最容易被顺手写下的一次真实启用，"
                        + "漏掉它等于判据①对真实变更视而不见（反向验证 114 · C1 的原始缺口）");
        assertFalse(ANNOTATION_USE.matcher("public @interface RlsScoped {").find(),
                "注解使用正则误匹配了【声明】形态 `@interface RlsScoped` —— 声明不是使用，"
                        + "误匹配会让判据①在本仓立刻假红");

        // ---- SET LOCAL 正则的判别力（两条，均由反向验证 114 的 C2 抓出的缺口固化为断言）----
        // 🛑 初版用 contains("SET LOCAL app.tenant_id") 前缀匹配 ⇒
        //    `SET LOCAL app.tenant_id_probe = ...` 会被误判成合格注入点（判据②假绿）。
        assertTrue(SET_LOCAL_STMT.matcher("jdbc.execute(\"SET LOCAL app.tenant_id = '\" + tenantId + \"'\");").find(),
                "SET LOCAL 正则未匹配真实注入语句形态");
        assertTrue(SET_LOCAL_STMT.matcher("SET LOCAL app.tenant_id='x'").find(),
                "SET LOCAL 正则未匹配【无空格】形态（写法差异不应造成假绿）");
        assertFalse(SET_LOCAL_STMT.matcher("SET LOCAL app.tenant_id_probe = 'x'").find(),
                "SET LOCAL 正则误匹配了【变量名后缀】形态 `app.tenant_id_probe` —— 前缀匹配会把"
                        + "一个真正坏掉的注入点判成合格（反向验证 114 · C2 的原始缺口）");
    }

    // ------------------------------------------------------------------
    // 判据 ⑥
    // ------------------------------------------------------------------

    @Test
    @DisplayName("文档机制的两个类仍存在，使删除死机制成为显式动作")
    void the_documented_mechanism_classes_still_exist_so_deleting_them_must_be_explicit()
            throws IOException {
        Path root = skeletonRoot();

        Path annotation = root.resolve(ANNOTATION_RELATIVE);
        Path aspect = root.resolve(ASPECT_RELATIVE);
        assertTrue(Files.isRegularFile(annotation),
                "注解定义文件不存在: " + ANNOTATION_RELATIVE
                        + "\n若这是有意的清理（删除 L2 死机制），请同时删除本判据，"
                        + "并把 README §三 ADR-02 的 L2 描述改为实际机制（inTenant 载体）——"
                        + "该处置属架构 owner（卡点清单 B-9）。");
        assertTrue(Files.isRegularFile(aspect),
                "切面源文件不存在: " + ASPECT_RELATIVE
                        + "\n若这是有意的清理，处置同上（须与 ADR-02 同步，不能只删代码）。");

        // 🔴 KEEP_STRINGS：切点表达式 `@Before("@annotation(...RlsScoped)")` 就住在字符串里，
        //    剥掉字符串会把切点本身剥掉 → 判据⑥ 对着一个正确的实现报红。
        String aspectCode = stripComments(Files.readString(aspect, StandardCharsets.UTF_8), KEEP_STRINGS);
        assertTrue(aspectCode.contains("@Before"),
                "切面里已没有 @Before —— 切点被移除，L2 的描述更是名存实亡。"
                        + "请同步 README ADR-02 或恢复切点。");
        assertTrue(aspectCode.contains("RlsScoped"),
                "切面的切点表达式里已不含 RlsScoped —— 它拦截的目标变了。"
                        + "请同步 README ADR-02 或恢复切点。");

        // 自证：注解文件里确实是【声明】而非【使用】形态
        // （这同时解释了判据①为何报 0：声明不是使用）
        String annotationCode = stripComments(Files.readString(annotation, StandardCharsets.UTF_8), KEEP_STRINGS);
        assertTrue(annotationCode.contains("@interface RlsScoped"),
                "注解文件里找不到 @interface RlsScoped 声明 —— 文件已不是注解定义。");
        assertEquals(0, countOf(annotationCode, "@RlsScoped "),
                "注解定义文件里出现了形如 `@RlsScoped ` 的【使用】形态 —— 与判据①的口径冲突。");
    }

    // ------------------------------------------------------------------
    // 扫描与解析
    // ------------------------------------------------------------------

    /** 扫 8 模块 {@code src/main} 下的 .java，返回 {@code 相对路径 → 按指定粒度剥离后的源码}。 */
    private static Map<String, String> productionSources(boolean stripStrings) throws IOException {
        Path root = skeletonRoot();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String mod : SCANNED_MODULES) {
            Path srcMain = root.resolve(mod).resolve("src").resolve("main");
            if (!Files.isDirectory(srcMain)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(srcMain)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    out.put(rel, stripComments(Files.readString(f, StandardCharsets.UTF_8), stripStrings));
                }
            }
        }
        return out;
    }

    /** 从已剥离的源码里取出所有 {@code inTenant} 定义所在的文件（相对路径）。 */
    private static Set<String> carriersIn(Map<String, String> strippedSources) {
        Set<String> carriers = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : strippedSources.entrySet()) {
            if (IN_TENANT_DEFINITION.matcher(e.getValue()).find()) {
                carriers.add(e.getKey());
            }
        }
        return carriers;
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 剥掉 Java 的行注释、块注释；字符串字面量按 {@code stripStrings} 决定剥除或原样保留。
     *
     * <h2>为什么必须区分两级粒度（而不是一律剥字符串）</h2>
     * 本仓大量 SQL 是 Java 字符串常量，而 {@code SET LOCAL app.tenant_id}、
     * 切点表达式 {@code @annotation(...RlsScoped)} 都住在字符串里。
     * <ul>
     *   <li>{@code stripStrings=true}：断言「代码里没有 X 的使用」（判据①）——
     *       字符串里写 {@code "@RlsScoped"} 只是文本；</li>
     *   <li>{@code stripStrings=false}：断言「字符串常量里仍有 X」（判据②③ 的 SET LOCAL、
     *       判据⑥ 的切点表达式）—— 剥掉字符串会把真实现剥成空。</li>
     * </ul>
     * 单一口径去断言两个相反事实，必然有一半是假的。这正是本类判据⑤要自证的东西。
     *
     * <p>🛑 为什么剥注释是<b>必须</b>的：{@code ProvisioningBoundaryGateTest} 的解析器 v1
     * 就踩过这个坑 —— 未剥注释把注释里的 {@code CREATE TABLE} 当成了真建表，
     * 凭空造出一张伪表。
     *
     * <p>状态机：0=代码态 1=行注释 2=块注释 3=字符串字面量。
     * 字符串态下遇 {@code \\} 跳过一个字符（转义），遇 {@code "} 回到代码态。
     * 刻意<b>不</b>处理字符字面量 {@code '...'} —— 本仓生产代码里未出现
     * 含 {@code //} / {@code "} 的字符字面量，引入该分支只会增加误判面。
     */
    private static String stripComments(String src, boolean stripStrings) {
        StringBuilder out = new StringBuilder(src.length());
        int state = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            switch (state) {
                case 0 -> {
                    if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                        state = 1;
                        i++;
                    } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                        state = 2;
                        i++;
                    } else if (c == '"' && stripStrings) {
                        state = 3;
                    } else {
                        out.append(c);
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        state = 0;
                        out.append(c);
                    }
                }
                case 2 -> {
                    if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                        state = 0;
                        i++;
                    }
                }
                default -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = 0;
                    }
                }
            }
        }
        return out.toString();
    }

    /** 上溯找到 skeleton 根（含 dy-app/pom.xml 与 dy-config 的 02_slots_seed.sql）。 */
    private static Path skeletonRoot() {
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