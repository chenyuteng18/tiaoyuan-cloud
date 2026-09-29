package com.diaoyuanyun.dy.app.security;

import com.diaoyuanyun.dy.security.permission.PermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 权限码登记门禁 —— 任何 {@code @RequirePermission("xxx")} 声明的码，
 * 都必须在 {@link PermissionRegistry} 里<b>至少有一个角色持有</b>。
 *
 * <h2>🛑 本门禁的存在理由：同一个缺口已经发生过两次，且两次都是被"真请求"抓出来的</h2>
 * <ol>
 *   <li><b>第一次 · 角色码大小写分叉</b>：{@code PermissionRegistry} 只登记<b>大写</b>遗留码
 *       （{@code SUPER_ADMIN} / {@code REGION_ADMIN} / {@code STORE_STAFF}），
 *       而契约冻结<b>小写</b>码（{@code client} / {@code therapist} / {@code meridian} /
 *       {@code manager} / {@code area} / {@code hq}）。两者不相等 ⇒ 契约写明可调的端点
 *       对契约角色码<b>全部 403</b>。由 {@code DerivedVisibilityE2ETest} 抓出。</li>
 *   <li><b>第二次 · 权限码本身从未登记</b>：{@code RefundWorkOrderController} 贴了 6 处
 *       {@code @RequirePermission("refund:read"/"write"/"approve")}，而注册表里
 *       <b>一个 {@code refund:*} 都没有</b> ⇒ 退款域 G1~G5 对全部 staff 角色 403，
 *       <b>整个退款域线上不可用</b>。由 {@code RefundDomainGEndpointsE2ETest} 抓出。</li>
 * </ol>
 *
 * <p>两次的<b>共同形态</b>完全相同：<b>注册表与调用点各自演进，没有任何一处同时看着两侧</b>。
 * 前两次都是"写完端点 → 单测全绿 → 真请求才发现"，而发现的代价是一次完整 E2E
 * 加一轮排查。本门禁把这件事从"要么靠真请求、要么靠人记得"变成<b>每次构建都会跑的断言</b>。
 *
 * <h2>🛑 本门禁的边界：它是【码级】的，不是【角色级】的 —— 不要扩大它的宣称</h2>
 * <b>它回答</b>：「这个码有没有主？」即"贴了它的端点会不会对<b>全员</b> 403"。
 * <b>它不回答</b>：「<b>某个特定角色</b>该不该持有某个码？」
 *
 * <p>这个区别不是咬文嚼字。实测过一组同级对照（{@code refund-s2-5-reverse-verification.py}
 * 的 RV-S2-5-6a / -6b）：
 * <pre>
 *   去掉 refund:approve 的【全部】持有者（manager/area/hq）  → 码无主 → 门禁【红】 ✓
 *   只去掉 area 一方的 refund:approve                        → 码仍有主 → 门禁【不红】 ✓
 * </pre>
 * 第二行一度被误读成"门禁没牙齿"，但它其实<b>正确</b>：manager/hq 仍持有该码，
 * 端点并不会对全员 403 —— 而"全员 403"正是本门禁要拦的那件事。
 *
 * <p>🛑 那么"area 该不该持有 {@code refund:approve}"由谁保证？由
 * {@code RefundDomainGEndpointsE2ETest.ApprovalWhitelist} 的真请求断言
 * （它要求督导能过权限层）—— 即<b>角色级的正确性靠 E2E 断言，码级的存在性靠本门禁</b>。
 * 一个被误认为"覆盖了角色级矩阵"的码级门禁，<b>比没有门禁更危险</b>：
 * 它会让人在改动角色矩阵时跳过真正的检查。
 *
 * <h2>🛑 为什么它必须扫【源码】而不是只看注册表</h2>
 * 注册表自己看不出"有人贴了一个它没登记的码"——注册表里根本没有这个信息。
 * 唯一的真相源是<b>注解字面量</b>（写在控制器上）与<b>注册表键值</b>（写在注册表里）的
 * <b>交集校验</b>。故本测试读源码文本，不依赖任何运行时状态。
 *
 * <h2>🛑 为什么必须先剥离注释</h2>
 * 注解形态在别处会被<b>提及</b>而不是<b>使用</b>：javadoc 里写
 * {@code {@code @RequirePermission("refund:read"/"write"/"approve")}}（本仓库
 * {@code PermissionRegistry} 的类注释里就有这样一句）会被朴素扫描当成三次真实声明，
 * 并提取出 {@code "write"} / {@code "approve"} 这类<b>不存在的码</b> ——
 * 于是门禁报出一批假的无主码，而修它们的方式根本不存在（它们不是码）。
 * 故扫描前先做逐字符的注释与字符串边界处理（见 {@link #stripComments}）。
 * 这与 S2-4 反向验证脚本里 {@code stripComments} 的存在理由是同一件事。
 */
@DisplayName("权限码登记门禁：@RequirePermission 的码必须在 PermissionRegistry 有主")
class PermissionCodeRegistrationGateTest {

    /** 骨架根目录（测试的工作目录是模块目录 dy-app，故上一级即骨架根）。 */
    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    /**
     * 扫描根。🛑 是<b>闭合白名单</b>而非"全仓库递归"：
     * 排除 {@code _work_backups/}（历史备份）、{@code target/}（编译产物含拷贝）、
     * {@code src/test}（测试里也会提到注解形态，属"提及"不属"使用"）。
     * 全仓库递归会把备份与产物里的旧代码一起扫进来，那会让门禁对着一份并不参与构建的
     * 文件报红 —— 而修复它对构建毫无影响。
     */
    private static final List<String> SCAN_ROOTS = List.of(
            "dy-app/src/main/java",
            "dy-security/src/main/java",
            "dy-web/src/main/java",
            "dy-audit/src/main/java",
            "dy-config/src/main/java",
            "dy-tenancy/src/main/java",
            "dy-common/src/main/java");

    /** 注解的两种书写形态：全限定名与简单名（控制器里两种都在用）。 */
    private static final java.util.regex.Pattern ANNOTATION = java.util.regex.Pattern.compile(
            "@(?:com\\.diaoyuanyun\\.dy\\.security\\.permission\\.)?RequirePermission\\s*\\(([^)]*)\\)");

    private static final java.util.regex.Pattern STRING_LITERAL =
            java.util.regex.Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    // ==================================================================
    // 一、扫描器自证：先证明它看得见东西，再让它去说话
    // ==================================================================

    /**
     * 🛑 门禁类测试的<b>第一条</b>永远是"扫描器不是瞎的"。
     *
     * <p>理由：本测试的核心断言形态是"无主的码集合为空"。而一个<b>写坏了的正则</b>
     * 会让这个集合<b>恒为空</b> —— 断言一路绿，读起来像"全项目权限码都已登记"，
     * 而真相是"它一个注解都没扫到"。这类假绿比漏报危险得多：
     * 漏报只会让本项 FAIL（有人去看），假绿会让所有人以为有保护。
     *
     * <p>故这里先断言两件独立的事：
     * <ol>
     *   <li>扫到的注解<b>条数</b> ≥ 6（退款域自己就有 6 处，取这个下界即可证伪"正则全灭"）；</li>
     *   <li>扫到的码集合<b>包含</b>几个必然存在的具体码（{@code customer:read} 与
     *       {@code refund:read/write/approve}）—— 具体值比数量更能证明正则抓对了内容。</li>
     * </ol>
     *
     * <p>🔴 2026-09-25（S2-9）：清单加入 {@code store:read}。它是契约域 A3
     * （{@code GET /stores}）新贴的码，<b>必然存在于源码中</b> ——
     * 把它钉进本清单的意义与其它码相同：若哪天有人把 A3 上的
     * {@code @RequirePermission} 摘掉，"必然存在"这条会先红，
     * 而不是等到"无主码集合为空"那条继续绿着。
     *
     * <p>🔴 2026-09-25（S2-10）：清单再加入 {@code customer:archive}。它是本次
     * 为解决"域 B 四行 B1/B2/B3/B6 的码持有者缺失"而新立的码，
     * 现<b>必然存在于源码中</b>（B1/B2/B3/B6 四处控制器方法）。
     * 把它钉进来的意义与前两个码完全相同：域 B 四行是<b>契约点名
     * therapist/meridian/admin 可调</b>的端点，一旦有人把这几处注解
     * 整体摘掉（例如误以为"与 A2 同款，该刻意缺第三层"），
     * {@code customer:archive} 会从码集里消失 —— 本清单先红，
     * 而不是让域 B 在"无权限码"的静默状态下裸奔。
     */
    @Test
    @DisplayName("① 扫描器自证：能看见注解与码（防『正则全灭 → 无主集合恒空 → 假绿』）")
    void scanner_actually_sees_the_annotations_and_their_codes() {
        Scan scan = scanProject();
        assertTrue(scan.occurrences() >= 6,
                "扫描到的 @RequirePermission 声明数应 ≥ 6（退款域控制器自己就有 6 处）；"
                        + "实际 " + scan.occurrences() + " —— 正则或扫描根写错了，本门禁的结论不可采信");
        for (String must : List.of("customer:read", "refund:read", "refund:write", "refund:approve",
                "verdict:read", "verdict:write",
                // 2026-09-25（S2-9 / 契约域 A3）：门店列表的读码。
                "store:read",
                // 2026-09-25（S2-10 / 契约域 B）：档案写入的写码（B1/B2/B3/B6）。
                "customer:archive",
                // 2026-09-26（S3-1 / 契约域 C2）：基线评估提交的写码。
                "assessment:write",
                // 2026-09-26（S3-2 / 契约域 D1）：服务核销的写码。
                "fulfillment:write",
                // 2026-09-26（S3-4 / 契约域 I）：文书模板的写码。
                "doc:write",
                // 2026-09-26（C-1 / 批次四）：审计日志哈希链自检的读码。
                // 🛑 它【不在】契约 45 端点内（内部自描述端点族），但它是本项目
                // 自立的权限码，故必须与本清单同步 —— 否则本门禁的"码有主"覆盖
                // 会漏掉它，而它恰恰是唯一一个【单角色持码】（仅 hq）。
                "audit:read")) {
            assertTrue(scan.codes().contains(must),
                    "扫描结果必须包含码 " + must + "（它必然存在于源码中）。实际码集: " + scan.codes());
        }
        assertTrue(scan.files() >= 10,
                "扫描的源文件数应 ≥ 10，实际 " + scan.files()
                        + " —— 扫描根可能不存在（工作目录不是 dy-app？）");
    }

    // ==================================================================
    // 二、核心断言：每个声明的码都要有主
    // ==================================================================

    /**
     * 🛑 本门禁的<b>唯一核心断言</b>。
     *
     * <p>{@code hasPermission} 的语义是"该角色的权限集里含此码"，
     * 且不登记的角色一律 false（fail-closed）。故一个<b>无主</b>的码
     * 意味着：<b>没有任何角色能通过这个注解下的判定</b> ⇒
     * 贴了它的端点对全员 403 —— 而端点看起来完全正常、编译通过、构建成功。
     */
    @Test
    @DisplayName("② 每个 @RequirePermission 的码都必须在注册表里有主（否则该端点对全员 403）")
    void every_require_permission_code_has_at_least_one_holder() {
        Scan scan = scanProject();
        Map<String, Set<String>> byRole = registeredByRole();

        Set<String> registered = new TreeSet<>();
        byRole.values().forEach(registered::addAll);

        Set<String> orphans = new TreeSet<>(scan.codes());
        orphans.removeAll(registered);

        assertTrue(orphans.isEmpty(),
                "🛑 以下权限码被 @RequirePermission 声明，却在 PermissionRegistry 里【没有任何角色持有】"
                        + " —— 贴了它们的端点会对【全部角色】403（包含契约声明可调的角色）："
                        + orphans
                        + "。\n已登记的码: " + registered
                        + "。\n出处: " + scan.sourcesOf(orphans)
                        + "。\n🛑 这正是本仓库已经发生过两次的同类缺陷"
                        + "（角色码大小写分叉 / refund:* 从未登记）—— "
                        + "两次都只在真请求下暴露，故请在此处补登记，不要放宽本断言。");
    }

    // ==================================================================
    // 三、契约防回退：客户与调理师不得持有退款域任何权限码
    // ==================================================================

    /**
     * 契约域 G 首行逐字：「本域全部接口：<b>客户端与调理师端一律 403 VISIBILITY_DENIED</b>」。
     *
     * <p>该约束在实现里由<b>两条独立防线</b>共同承担（纵深防御）：端点级
     * （{@code @StaffOnly} / 派生字段拦截器）与功能权限级（客户/调理师不持有 {@code refund:*}）。
     * 本断言钉住后者。
     *
     * <p>🛑 它防的是一种很自然的"顺手"：某天有人为了让客户看到自己工单的某个字段，
     * 给 {@code client} 加上 {@code refund:read} —— 那时<b>第二条防线消失</b>，
     * 而第一条（端点级）独自承担全部压力。若第一条哪天也被改坏，
     * 退款域就对客户洞开了，且两次改动各自看起来都很合理。
     *
     * <p>{@code store_customer_service} 一并断言：它是 P0-19 待裁定项，<b>刻意不登记</b>，
     * 故也不得持有任何退款码。
     */
    @Test
    @DisplayName("③ 契约防回退：client / therapist / store_customer_service 不得持有任何 refund:* ")
    void client_and_therapist_hold_no_refund_permission() {
        Map<String, Set<String>> byRole = registeredByRole();

        for (String role : List.of("client", "therapist", "store_customer_service")) {
            Set<String> held = byRole.getOrDefault(role, Set.of());
            Set<String> refundCodes = new TreeSet<>();
            for (String c : held) {
                if (c.startsWith("refund:")) {
                    refundCodes.add(c);
                }
            }
            assertTrue(refundCodes.isEmpty(),
                    "🛑 角色 " + role + " 不得持有退款域权限码（契约域 G 首行："
                            + "『本域全部接口：客户端与调理师端一律 403 VISIBILITY_DENIED』）。"
                            + "实际持有: " + refundCodes + "。\n"
                            + "给 client 发 refund:read 会让『功能权限』这条防线对客户消失，"
                            + "只剩端点级一条 —— 而纵深防御的意义正是任一条被改坏时另一条仍在。");
        }
    }

    // ==================================================================
    // 四、契约防回退：客户与调理师不得持有判定域任何权限码
    // ==================================================================

    /**
     * 契约域 F 的两行（F1/F2）都写 {@code x-callable-roles: [meridian, admin]}，
     * 且 F2 的描述逐字：「🔴 <b>客户与调理师端一律 403 VISIBILITY_DENIED</b>」。
     *
     * <p>🛑 判定域比退款域更需要这条断言。理由是一条业务事实：
     * <b>判定链覆盖每一个客户</b>（含从没提过退款的），而退款域只覆盖提出诉求的那一小部分。
     * 也就是说，"客户拿到了 {@code verdict:read}"的影响面<b>远大于</b>退款域同类问题 ——
     * 而它同样由两条独立防线承担（{@code @StaffOnly} 在 F2 + 本断言钉住的权限级）。
     *
     * <p>它防的仍然是一种很自然的"顺手"：某天有人想让客户在自己的健康档案里
     * 看到"系统对他的评估"，于是给 {@code client} 加上 {@code verdict:read}。
     * 而那正好是 Q10 口径② 与 PRD P0-13 逐字禁止的事：
     * 「判定结论定位为<b>对内判定建议 + 置信度</b>，不面向客户展示、不可作为对外举证材料」。
     */
    @Test
    @DisplayName("④ 契约防回退：client / therapist / store_customer_service 不得持有任何 verdict:* ")
    void client_and_therapist_hold_no_verdict_permission() {
        Map<String, Set<String>> byRole = registeredByRole();

        for (String role : List.of("client", "therapist", "store_customer_service")) {
            Set<String> held = byRole.getOrDefault(role, Set.of());
            Set<String> verdictCodes = new TreeSet<>();
            for (String c : held) {
                if (c.startsWith("verdict:")) {
                    verdictCodes.add(c);
                }
            }
            assertTrue(verdictCodes.isEmpty(),
                    "🛑 角色 " + role + " 不得持有判定域权限码（契约域 F：F1/F2 的 "
                            + "x-callable-roles: [meridian, admin]，F2 描述逐字『客户与调理师端一律 403』）。"
                            + "实际持有: " + verdictCodes + "。\n"
                            + "判定链覆盖每一个客户（不限于提出退款的），故此处开口的影响面比退款域更大；"
                            + "PRD P0-13 / Q10 口径② 逐字把判定结论定为『对内判定建议』，不面向客户展示。");
        }
    }

    // ==================================================================
    // 五、契约防回退：客户不得持有组织域码；A2 刻意不贴码
    // ==================================================================

    /**
     * 契约域 A 的两行（A2/A3）在"客户能不能调"上是<b>相反</b>的，本断言同时钉住两边。
     *
     * <h2>① A3 {@code GET /stores}：客户不得持有 {@code store:read}</h2>
     * A3 的 {@code x-callable-roles: [therapist, meridian, admin]} <b>不含 client</b>。
     * 防的是一种很自然的"顺手"：某天有人做客户端的门店选择器，发现客户拉不到门店列表，
     * 于是给 {@code client} 补上 {@code store:read} —— 那一刻客户就能拉<b>全租户</b>
     * 的门店台账（受行级范围约束，但客户的行级范围在 A2 里是"n/a 仅本人"，
     * 语义上根本没有对应档）。而这条改动看起来完全合理。
     *
     * <h2>② A2 {@code GET /auth/me}：源码里<b>不得出现</b> {@code @RequirePermission}</h2>
     * 这条更微妙，且它是本门禁里唯一一条"断言注解<b>不存在</b>"的规则。
     * A2 的 {@code x-callable-roles} <b>含 {@code client}</b>，而注册表刻意不登记 client ⇒
     * A2 上任何一个 {@code @RequirePermission} 都会让客户查自己的可见性档位时 403 ——
     * 而 A2 恰是「可见性档位<b>唯一权威下发点</b>」，三端 UI 靠它决定渲染分支。
     *
     * <p>🛑 为什么不能靠"无主码"那条断言兜住：见下。
     * 若有人给 A2 贴 {@code @RequirePermission("customer:read")}，
     * 那条断言<b>仍然是绿的</b>（{@code customer:read} 有主，是 manager/therapist 等持有）。
     * 于是"给客户关掉了自己的档位接口"这件事，在<b>所有</b>既有断言下都不可见 ——
     * 只有真请求（客户身份调 A2 拿到 403）才能发现。故这里必须单独钉住。
     *
     * <p>它也顺手钉住另一半：A2 与 A3 都<b>不得</b>贴 {@code @StaffOnly}
     * （契约里两者都是 {@code x-client-forbidden: false} 且无
     * {@code x-client-explicitly-denied}）。贴了会让客户拿到错误码语义而非契约语义。
     */
    @Test
    @DisplayName("⑤ 契约防回退：客户不持 store:read；A2 刻意不贴任何权限码 / 两个端点都不贴 StaffOnly")
    void identity_domain_keeps_client_out_and_auth_me_unannotated() {
        // ---- ① 客户不得持有 store:read（契约 A3 的 x-callable-roles 不含 client）----
        Map<String, Set<String>> byRole = registeredByRole();
        for (String role : List.of("client", "store_customer_service")) {
            Set<String> held = byRole.getOrDefault(role, Set.of());
            Set<String> storeCodes = new TreeSet<>();
            for (String c : held) {
                if (c.startsWith("store:")) {
                    storeCodes.add(c);
                }
            }
            assertTrue(storeCodes.isEmpty(),
                    "🛑 角色 " + role + " 不得持有组织域权限码（契约 A3 的 "
                            + "x-callable-roles: [therapist, meridian, admin] 不含 client）。"
                            + "实际持有: " + storeCodes + "。\n"
                            + "客户对门店台账没有数据面 —— 他只能看本人（A2 的 store_scope "
                            + "对客户是 n/a）。给 client 补 store:read 会静默地为"
                            + "『客户端门店选择器』这类需求打开全租户门店台账的拉取能力。");
        }

        // ---- ② 客户<b>必须</b>不持有任何域码（A2 之所以能对客户开放的全部依据）----
        Set<String> clientHeld = byRole.getOrDefault("client", Set.of());
        assertTrue(clientHeld.isEmpty(),
                "🛑 client 必须不持有任何权限码（本仓库的既有基线）。实际: " + clientHeld + "。\n"
                        + "A2 GET /auth/me 的 x-callable-roles 含 client，而它刻意不贴任何 "
                        + "@RequirePermission —— 那条形态【正确】的全部前提就是"
                        + "『注册表里 client 没有码』。若给 client 补了码，"
                        + "A2 的『不贴码』就从『有意的设计』退化成『碰巧的巧合』。");

        // ---- ③ A2 源码里不得出现 @RequirePermission ----
        String authMe = readMainSource(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/controller/AuthMeController.java");
        String cleaned = stripComments(authMe);
        assertFalse(cleaned.contains("@RequirePermission"),
                "🛑 AuthMeController（A2 GET /auth/me）不得贴任何 @RequirePermission。\n"
                        + "A2 的 x-callable-roles 含 client，而 PermissionRegistry 刻意不登记 client；"
                        + "一旦贴码，客户查自己的可见性档位会 403 —— 而 A2 是"
                        + "『可见性档位唯一权威下发点』，三端 UI 靠它决定渲染分支。\n"
                        + "🛑 注意本断言不能被『无主码』那条（第②条）替代："
                        + "若贴的是 customer:read 这类【有主】的码，第②条仍是绿的，"
                        + "只有真请求才会暴露。");

        // ---- ④ 两个端点都不得贴 @StaffOnly ----
        for (String src : List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/controller/AuthMeController.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/controller/StoreListController.java")) {
            String body = stripComments(readMainSource(src));
            assertFalse(body.contains("@StaffOnly"),
                    "🛑 " + src + " 不得贴 @StaffOnly。\n"
                            + "契约域 A 的 A2/A3 都是 x-client-forbidden: false，"
                            + "且【没有】x-client-explicitly-denied（true 的只有 E4 / F2 / 域 G 五端点）。"
                            + "两个键语义不同：前者说『小程序端是否生成调用面』，"
                            + "后者才是『仅 staff 可调』。据前者贴注解会让客户拿到"
                            + "注解式 403 而不是契约声明的 403 语义。");
        }
    }

    // ==================================================================
    // 七、A-8 延伸 · 遗留大写角色码未持自建码的【登记式差异】
    //     "TENANT_ADMIN / super_admin 未登记 doc:write / audit:read" 此前只写在
    //     SKELETON_DEFINED_CODES 的自由文本里 —— 是"记了一笔"，不是"有人守着"。
    // ==================================================================

    /**
     * <b>遗留大写角色码未持自建码</b>的登记表 —— 键 = 角色码，值 = 该角色缺失的码集合。
     *
     * <h2>它登记的是什么事实</h2>
     * 契约 §2.1 点名的管理层用 {@code hq}，骨架早期用大写 {@code SUPER_ADMIN} /
     * {@code TENANT_ADMIN}。{@link PermissionRegistry} 里 {@code SUPER_ADMIN} = {@code Set.of("*")}
     * （故它持一切码，不在本表内），而 {@code TENANT_ADMIN} <b>根本没有键</b> ⇒
     * {@code hasPermission("TENANT_ADMIN", "doc:write")} 恒 {@code false}。
     * 同一个 {@link com.diaoyuanyun.dy.app.doctpl.service.DocFileService#SUPER_ADMIN_ALIASES}
     * 又把 {@code TENANT_ADMIN} / {@code super_admin} 视为超管 ⇒
     * <b>第一道 {@code @RequirePermission("doc:write")} 会把它们 403 拦下，到不了第二道判定</b>。
     *
     * <h2>🛑 为什么必须落成结构化表，而不是继续写在注释里</h2>
     * 只写注释 = 便宜但会漂移：将来有人给 {@code TENANT_ADMIN} 补上 {@code doc:write} 修好了分叉，
     * <b>注释不会报错</b> —— 于是"未修好的事实"会被继续当作现状引用（属"文档比实现更自信"一类）。
     * 落成表后：<b>修好 ⇒ 本测试红 ⇒ 必须显式把该码从本表移除</b>（归零要求动作，与
     * {@code ContractFreezeGateTest.KNOWN_DIFFERENCES} 同构）；<b>注册表若被误改成更严的形态 ⇒ 也红</b>。
     *
     * <h2>🛑 它【不】回答什么</h2>
     * 它<b>不</b>回答"该不该给 TENANT_ADMIN 补 doc:write / audit:read"—— 那是
     * <b>A-8 契约 owner 的裁定</b>。本表只把<b>现状</b>钉死，使"裁定之后"成为一个显式动作。
     */
    private static final Map<String, Set<String>> LEGACY_ROLE_MISSING_SELF_DEFINED_CODES = Map.of(
            "TENANT_ADMIN", Set.of("doc:write", "audit:read"),
            "super_admin", Set.of("doc:write", "audit:read"));

    @Test
    @DisplayName("A-8 延伸：遗留大写角色码未持自建码的差异【真实存在】且随修复而自动归零")
    void legacy_role_codes_missing_self_defined_permissions_are_registered_not_assumed() {
        PermissionRegistry registry = new PermissionRegistry();

        // ---- ① 差异必须【真的还在】：登记了却在注册表里已持有 ⇒ 登记过期，必须归零 ----
        for (Map.Entry<String, Set<String>> e : LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.entrySet()) {
            String role = e.getKey();
            for (String code : e.getValue()) {
                assertFalse(registry.hasPermission(role, code),
                        "🛑 登记表说角色 " + role + " 不持有 " + code + "，但它现在【已持有】"
                                + " —— 该分叉已被修复（或被无意间改动），请把 " + code
                                + " 从 LEGACY_ROLE_MISSING_SELF_DEFINED_CODES." + role
                                + " 中移除。归零要求显式动作，不是静默漂移。");
            }
        }

        // ---- ② 对照组：被登记的差异必须是【真实的角色级缺口】，不是"谁都不持" ----
        //    🛑 若某个码【全员皆无主】，那是另一种更严重的病（码级缺口），
        //       应由第②条断言（同时每个 @RequirePermission 的码必须有主）去抓，
        //       本表不该把两者混为一谈 —— 故此处反证：被登记缺的码，确实有【其它】角色持有。
        for (Map.Entry<String, Set<String>> e : LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.entrySet()) {
            for (String code : e.getValue()) {
                boolean heldByAnyContractRole = Stream.of("manager", "area", "hq", "therapist", "meridian")
                        .anyMatch(r -> registry.hasPermission(r, code));
                assertTrue(heldByAnyContractRole,
                        "🛑 被登记为『" + e.getKey() + " 缺 " + code + "』，但没有任何契约角色持有 "
                                + code + " —— 这不是角色级缺口，而是【码级无主】，"
                                + "属另一类问题（应由『每个 @RequirePermission 的码必须有主』那条断言处理）。");
            }
        }

        // ---- ③ 登记表必须真的【提到】这两个角色：防"表被清空后断言恒绿" ----
        //    本表若被整表删空，①②的循环体一次都不进 ⇒ 平凡通过。
        //    故显式钉住"当前确实登记了 2 个角色"——清空必须是一次显式编辑（并在此处报红说明）。
        assertEquals(2, LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.size(),
                "🛑 登记表应恰有 2 个角色（TENANT_ADMIN / super_admin）。若分叉已全部修复，"
                        + "请把本断言与整个第七章一并移除 —— 但那是显式动作，不得静默清空。");
        assertTrue(LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.containsKey("TENANT_ADMIN")
                        && LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.containsKey("super_admin"),
                "🛑 登记表必须同时含 TENANT_ADMIN 与 super_admin（它们同源：都是『被 DocFileService "
                        + "视为超管、却未在 PermissionRegistry 登记 doc:write』的写法）。");

        // ---- ④ 与 §六 台账交叉自证：被登记缺的码，必须都是台账里【骨架自立】的码 ----
        //    理由：若某码是"契约已冻结件"，则它的分配属契约问题，不该混进本章的"遗留角色码缺口"。
        for (Set<String> codes : LEGACY_ROLE_MISSING_SELF_DEFINED_CODES.values()) {
            for (String code : codes) {
                assertTrue(SKELETON_DEFINED_CODES.containsKey(code),
                        "🛑 本章登记的码 " + code + " 不在第六章的 A-8 自建码台账里 —— "
                                + "两章口径应一致：本章只管『骨架自立码 × 遗留角色码』的交集。");
            }
        }
    }

    // ==================================================================
    // 六、A-8 · 权限码【拆分】的追认台账（骨架期自立的码必须逐条可追认）
    // ==================================================================

    /**
     * <b>A-8 追认台账</b>：契约中<b>不存在</b>权限码定义（{@code grep permission} 仅一处
     * 无关枚举，见 {@code UpstreamGapRegistryTest} 的同源事实）⇒ 权限码是
     * <b>骨架期自有编码</b>、<b>不是契约冻结件</b>。本台账把本轮为修"域 B 四端点对一线角色
     * 恒 403"及后续各域而<b>新立</b>的码，连同它们的<b>追认点</b>，落成结构化台账。
     *
     * <h2>🛑 为什么需要一份"追认台账"，而不是只在 README §5.2 写一条</h2>
     * "须由契约 owner 追认"这句话，若只写在文档里，它的生命周期就是<b>文档的生命周期</b>：
     * 有人新增第 6 个码时不会想起它，有人把码改名时不会更新它。
     * 本台账把"哪些码是骨架期自立的"变成<b>可机械核对的集合</b>：
     * <ol>
     *   <li>台账里的每个码，<b>必须真的出现在源码的 {@code @RequirePermission} 里</b>
     *       （否则台账在记录一个不存在的码 —— 那会让"追认"追认一个幻影）；</li>
     *   <li>源码里的每个码，<b>必须要么在台账里（骨架期自立），要么在
     *       {@link #CONTRACT_OR_DOMAIN_CONVENTION_CODES} 里（有契约/域惯例依据）</b>
     *       —— 否则就是一个"来历不明的码"（既非契约冻结、也未登记追认点）；</li>
     *   <li>台账条目<b>核对式</b>：码被删除时本测试红，提示"同步台账"——
     *       归零要求显式动作，不是静默消失（与 {@code KNOWN_DIFFERENCES} 同构）。</li>
     * </ol>
     * 这正是 A-8 追认点的可执行形态：<b>"这些码的职责边界是否符合后续『权限矩阵来自配置 /
     * 策略服务』的规划"由契约 owner 回答；而"它们是哪些码"由本台账负责不漏项。</b>
     */
    private static final Map<String, String> SKELETON_DEFINED_CODES = new LinkedHashMap<>() {{
        // 码 → 追认点（为什么它必须被追认）
        put("customer:archive",
                "S2-10 新立（= 档案写入，发给契约点名全部角色含 therapist/meridian）；"
                        + "与既有 customer:write（= 内容资产写入）构成一次语义分码。"
                        + "追认点：职责边界是否符合『权限矩阵来自配置/策略服务』的规划；"
                        + "是否需在权限管理界面作为独立可分配项暴露。");
        put("assessment:write",
                "S3-1 新立（= 基线评估提交，契约域 C2 x-callable-roles: [therapist, meridian, admin]）。"
                        + "追认点：同 customer:archive —— 骨架自立的域写码，非契约冻结件。");
        put("fulfillment:write",
                "S3-2 新立（= 履约写：服务核销 / 每日填报，契约域 D1 "
                        + "x-callable-roles: [therapist, meridian, admin]）。追认点：同上。");
        put("doc:write",
                "S3-4 新立（= 文书模板写，契约域 I x-callable-roles: [admin]，仅管理层三端）。"
                        + "追认点：同上。⚠️ 已知分叉：TENANT_ADMIN / super_admin 未登记该码，"
                        + "契约点名的超管若以 TENANT_ADMIN 角色出现会被 403（T-11 延伸）。");
        put("audit:read",
                "C-1 新立（= 读审计与稽核事实，内部自描述端点族 GET /audit/log-chain，仅 hq 持有）。"
                        + "追认点：同上；理由见 PermissionRegistry 类注释『审计链自检域』一节。");
    }};

    /**
     * 有<b>契约依据或域惯例</b>的既有码 —— 它们不是本轮新立的，故不属 A-8 追认范围，
     * 但必须被显式列出，否则 {@link #skeleton_defined_codes_form_a_closed_registry()}
     * 会把它们当成"来历不明的码"。
     */
    private static final Set<String> CONTRACT_OR_DOMAIN_CONVENTION_CODES = Set.of(
            "customer:read",    // 读码，契约矩阵 ①②③④ 对 staff 全角色可见档的直接落点
            "customer:write",   // 既有码：承载"内容资产写入"（题库导入组卷），非本轮新立
            "store:read",       // S2-9 新立但依据契约 A3 x-callable-roles，属"契约点名"类
            "verdict:read", "verdict:write",   // 契约域 F x-callable-roles 的落点
            "refund:read", "refund:write", "refund:approve");  // 契约域 G x-callable-roles 的落点

    @Test
    @DisplayName("A-8：骨架期自立的码构成闭合台账，且台账与源码【双向】一致")
    void skeleton_defined_codes_form_a_closed_registry() {
        Scan scan = scanProject();

        // ---- ① 台账里的每个码必须真的出现在源码里（防"追认一个幻影"）----
        Set<String> inSource = scan.codes();
        for (String code : SKELETON_DEFINED_CODES.keySet()) {
            assertTrue(inSource.contains(code),
                    "🛑 A-8 台账登记了码 " + code + "，但源码里找不到任何 @RequirePermission 使用它 —— "
                            + "台账在追认一个不存在的码。若该码已被移除，请同时从台账删除"
                            + "（归零要求显式动作，不是静默消失）。实际码集: " + inSource);
        }

        // ---- ② 源码里的每个码必须被归类（台账 ∪ 契约依据），不得有"来历不明"的码 ----
        Set<String> classified = new TreeSet<>();
        classified.addAll(SKELETON_DEFINED_CODES.keySet());
        classified.addAll(CONTRACT_OR_DOMAIN_CONVENTION_CODES);

        Set<String> unclassified = new TreeSet<>(inSource);
        unclassified.removeAll(classified);
        assertTrue(unclassified.isEmpty(),
                "🛑 以下权限码既不在 A-8 追认台账、也不在『有契约/域惯例依据』清单里: "
                        + unclassified + "。\n"
                        + "🛑 新增一个权限码 = 新增一次『骨架侧语义决定』。"
                        + "契约中不存在权限码定义（grep permission 仅一处无关枚举）—— "
                        + "故每个骨架自立的码都必须登记其追认点，否则它在未来『权限码改由契约/"
                        + "策略服务冻结』时会成为一个无人认领的历史决策。\n"
                        + "请在此二选一：① 若它有契约依据（x-callable-roles 点名）→ 加入 "
                        + "CONTRACT_OR_DOMAIN_CONVENTION_CODES；② 若是骨架自立的新语义 → "
                        + "加入 SKELETON_DEFINED_CODES 并写明追认点。");

        // ---- ③ 两类清单不得重叠（一个码不应既"有契约依据"又"待追认"）----
        Set<String> overlap = new TreeSet<>(SKELETON_DEFINED_CODES.keySet());
        overlap.retainAll(CONTRACT_OR_DOMAIN_CONVENTION_CODES);
        assertTrue(overlap.isEmpty(),
                "🛑 以下码同时出现在两个清单里: " + overlap
                        + " —— 一个码要么『有契约依据』（已冻结），要么『骨架自立待追认』，"
                        + "两者互斥。重叠说明清单本身需要重新归类。");

        // ---- ④ 台账里的追认点必须真的写清理由（不许空串/占位）----
        for (Map.Entry<String, String> e : SKELETON_DEFINED_CODES.entrySet()) {
            String note = e.getValue();
            assertTrue(note != null && note.length() >= 20,
                    "🛑 A-8 台账里 " + e.getKey() + " 的追认点过短（"
                            + (note == null ? "null" : note.length())
                            + " 字符）—— 台账的价值在『说清为什么它需要被追认』，"
                            + "占位式的一条等于没登记。");
            assertTrue(note.contains("追认点"),
                    "🛑 A-8 台账里 " + e.getKey() + " 的条目必须含『追认点』字样 —— "
                            + "它是本台账的存在目的，缺它就退化成一个普通清单。");
        }
    }

    /** 读一个相对 SKEL 的主代码源文件（断言"注解不存在"用）。 */
    private static String readMainSource(String relPath) {
        Path p = SKEL.resolve(relPath);
        assertTrue(Files.isRegularFile(p),
                "源文件不存在: " + p + " —— 本断言依赖它的存在（路径写错会让断言假绿）");
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + p, e);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 扫描结果：出现次数 / 文件数 / 码集 / 码 → 出处。 */
    private record Scan(int occurrences, int files, Set<String> codes,
                        Map<String, Set<String>> where) {

        /** 给定一批码，回它们的出处（用于断言失败信息）。 */
        String sourcesOf(Set<String> subset) {
            Map<String, Set<String>> picked = new TreeMap<>();
            subset.forEach(c -> picked.put(c, where.getOrDefault(c, Set.of())));
            return picked.toString();
        }
    }

    private Scan scanProject() {
        List<Path> files = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            Path p = SKEL.resolve(root);
            if (!Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new IllegalStateException("扫描失败: " + p, e);
            }
        }

        int occurrences = 0;
        Set<String> codes = new TreeSet<>();
        Map<String, Set<String>> where = new TreeMap<>();

        for (Path f : files) {
            String src;
            try {
                src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取失败: " + f, e);
            }
            // 🛑 先剥注释：javadoc 里"提及"注解形态（含 {@code @RequirePermission("a"/"b")}）
            //    不是真实声明，不剥就会提取出不存在的码（见类注释）。
            String cleaned = stripComments(src);
            var m = ANNOTATION.matcher(cleaned);
            while (m.find()) {
                occurrences++;
                var lit = STRING_LITERAL.matcher(m.group(1));
                while (lit.find()) {
                    String code = lit.group(1);
                    codes.add(code);
                    where.computeIfAbsent(code, k -> new TreeSet<>())
                            .add(SKEL.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        return new Scan(occurrences, files.size(), codes, where);
    }

    /**
     * 剥离行注释（两个斜杠起）与块注释（斜杠星号起、星号斜杠止）—— 逐字符、跟踪字符串边界。
     *
     * <p>🛑 本注释【刻意不写出】那两个注释定界符的字面形式：一旦在 javadoc 里连着写出
     * "星号 + 斜杠"，就会<b>提前终结本段 javadoc</b>，其后的文字被当成 Java 代码 ⇒
     * 编译报出一串"需要标识符 / 非法字符"（本方法首版正是这么写坏的，
     * 实测 20 余条编译错误全指向下面这几行）。描述式写法既回避该陷阱，也更易读。
     *
     * <p>为什么不用朴素的"先用正则删块注释、再按行删行注释"：
     * <ol>
     *   <li>块注释可能跨行 ⇒ 需要 DOTALL 的正则，而 DOTALL 下的非贪婪匹配在超大文件上慢且易回溯；</li>
     *   <li>更致命的是字符串字面量里的双斜杠（如网址字面量）会被误当成行注释起点，
     *       把该行剩余部分（可能含真实注解）整段删掉 ⇒ <b>漏扫</b>。</li>
     * </ol>
     * 漏扫的直接后果是"无主码集合少了一项"，即门禁静默放过 —— 与假绿同类。
     * 故这里逐字符走：字符串内不认注释符，注释内不认注解。
     */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inLine = false;
        boolean inBlock = false;
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    // 转义字符（\" 等）要连下一个字符一起消费，否则 \" 会被当成字符串结束
                    out.append(s.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(s.charAt(++i));
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else if (c == '"') {
                inStr = true;
                out.append(c);
            } else if (c == '\'') {
                inChar = true;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 从 {@link PermissionRegistry} 读出"角色 → 权限集"。
     *
     * <p>用反射读 private 字段而不是遍历 {@code hasPermission}：
     * 后者需要预先知道全部角色名（而那正是注册表的知识），会引入第二份角色名单 ——
     * 两份名单必然分叉，而分叉的那一侧恰好是本门禁要检查的东西。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> registeredByRole() {
        try {
            PermissionRegistry registry = new PermissionRegistry();
            Field f = PermissionRegistry.class.getDeclaredField("rolePermissions");
            f.setAccessible(true);
            Map<String, Set<String>> map = (Map<String, Set<String>>) f.get(registry);
            if (map == null || map.isEmpty()) {
                fail("PermissionRegistry 的角色→权限映射为空 —— 门禁无法作出任何结论，"
                        + "请先确认注册表构造正常（这本身就是一次真实缺陷）");
            }
            return map;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法读取 PermissionRegistry.rolePermissions —— 字段被改名了？"
                            + "本门禁依赖它作为唯一真相源，改名时请同步更新此处", e);
        }
    }
}