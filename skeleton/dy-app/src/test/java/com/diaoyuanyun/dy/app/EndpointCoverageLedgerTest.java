package com.diaoyuanyun.dy.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>契约端点覆盖总账</b>（批次六）—— 把"系统开发到什么程度"从<b>口头清点</b>
 * 变成<b>每次构建都会跑的机械事实</b>。
 *
 * <h2>🛑 为什么这件事必须有门禁，而不是靠 README 里数一遍</h2>
 * 本项目反复验证过同一个失效模式：<b>写进文档的清单会被构建忽略</b>。
 * 端点覆盖尤其危险，因为它的两种错误方向<b>都不会让任何测试变红</b>：
 * <ul>
 *   <li><b>少了一个端点</b>（契约有、实现无）：现有 698 个用例全绿 ——
 *       因为没有任何用例去数"契约声明了几个端点"；</li>
 *   <li><b>多了一个端点</b>（实现有、契约无）：同样全绿 ——
 *       新开的内部端点不会与任何既有断言冲突，却可能是**未登记的出站面**。</li>
 * </ul>
 * 故本类把两侧都钉住：
 * <ol>
 *   <li><b>契约的 45 个 operationId</b> 必须要么<b>已实现</b>，要么在
 *       {@link #OUT_OF_SCOPE} 里<b>逐条登记了出范围理由</b> ——
 *       "还没做"与"明确不做"是两件事，后者需要一句理由；</li>
 *   <li><b>实现侧多出的端点</b>（契约 45 之外）必须在 {@link #INTERNAL_ENDPOINTS} 里登记 ——
 *       内部自描述端点 / 演示入口 / 自建能力面，**每一类都要说明它为什么可以出站**。</li>
 * </ol>
 *
 * <h2>当前口径（机械清点结果，不是估计）</h2>
 * <pre>
 *   契约 operationId 总数        45
 *   其中已实现                   41
 *   其中登记出范围（Non-goal）     3   （A1 登录 / F3 稽核信号 / F4 稽核覆盖率）
 *   其中登记待上游冻结             1   （H1 电子签回调 —— 契约禁止此刻编码）
 *   ────────────────────────────────
 *   实现侧端点总数               68
 *   其中对应契约行               41
 *   其中登记为内部端点           27
 * </pre>
 *
 * <h2>🛑 三种"出范围"的语义不可混用（这是本台账的核心区分）</h2>
 * <table>
 *   <tr><th>类别</th><th>含义</th><th>典型</th></tr>
 *   <tr><td><b>Non-goal</b></td><td>产品/架构层面<b>明确不做</b>，且已裁定</td>
 *       <td>A1 {@code /auth/login}（身份由外部 IdP + demo 通道提供）</td></tr>
 *   <tr><td><b>未立项</b></td><td>依赖上游口径（稽核信号/覆盖率定义），口径未定 ⇒ 无实现依据</td>
 *       <td>F3 / F4（A-10 明确出范围）</td></tr>
 *   <tr><td><b>待冻结</b></td><td>契约把字段/结构/签名算法列在 {@code x-not-frozen}，
 *       <b>此刻实现 = 开出一条未验签通道</b></td>
 *       <td>H1 {@code /esign/callbacks/{provider}}（B-5）</td></tr>
 * </table>
 * 三者都可写"暂不实现"，但**理由不同**：Non-goal 是"不需要"，未立项是"没依据"，
 * 待冻结是"实现了反而更危险"。混用会让下一次 review 无法判断该找谁解锁。
 *
 * <p>🛑 本类的全部判据读<b>真实文件</b>（契约 YAML + {@code dy-app/src/main/java} 源码文本），
 * 不依赖任何运行时状态，也不手抄路径清单 —— 手抄的清单会在某次"一侧改了另一侧没改"时分叉，
 * 而分叉的那一侧恰好是本类要检查的东西。
 */
@DisplayName("契约端点覆盖总账：45 operationId ↔ 实现侧端点（差额必须逐条登记）")
class EndpointCoverageLedgerTest {

    private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";
    private static final String CONTRACT_MD_REL = "_work/contract-t6-api-freeze-2026-09-19.md";

    /** 服务端统一前缀（实现侧写全、契约侧省略）。 */
    private static final String API_PREFIX = "/api/v1";

    private static final int CONTRACT_OPERATION_COUNT = 45;

    /**
     * <b>出范围的契约 operationId → 理由</b>（键 = operationId）。
     *
     * <p>🛑 这是<b>差额的显式登记面</b>：归零要求显式动作（与
     * {@code ContractFreezeGateTest.KNOWN_DIFFERENCES} / {@code UpstreamGapRegistryTest} 同构）。
     * 某天补了一个端点，本表对应项必须删掉 —— 否则"未实现"与"已实现"两个事实并存。
     */
    private static final Map<String, String> OUT_OF_SCOPE = new LinkedHashMap<>() {{
        put("authLogin",
                "【Non-goal】A1 POST /auth/login。身份认证由外部 IdP 承担，本骨架不实现登录端点；"
                        + "开发/联调期的身份获取走 /api/v1/demo/*（见 INTERNAL_ENDPOINTS 的 demo 组）。"
                        + "契约 x-callable-roles 含 client，而 PermissionRegistry 刻意不登记 client —— "
                        + "若将来要落它，必须先解决『客户持码』这条基线的连带影响（见 PermissionRegistry 类注释）。");
        put("listAuditSignals",
                "【未立项】F3 GET /audit/signals。依赖上游对『稽核信号』的定义（哪些事件算信号、"
                        + "信号的严重度分档），口径未定 ⇒ 写了就是臆造。A-10 已明确出范围，"
                        + "由 UpstreamGapRegistryTest 机械守护。");
        put("getAuditCoverage",
                "【未立项】F4 GET /audit/coverage。依赖上游对『覆盖率』三数的分子/分母及时窗定义，"
                        + "且契约自身把 a3_observability 标为 TBD ⇒ 口径未定。A-10 已明确出范围，"
                        + "由 UpstreamGapRegistryTest 机械守护。");
        put("receiveEsignCallback",
                "【待冻结】H1 POST /esign/callbacks/{provider}。契约逐字写着『厂商选定之前三端不得据本节编码』，"
                        + "并把字段名/结构/厂商签名算法/provider 取值域全部列在 x-not-frozen。"
                        + "此刻实现 = 开出一条『任意请求都能推进 AGREEMENT_SIGNED 门禁』的通道"
                        + "（agreement 表已含 rendered_hash，CustomerGateGuard 已有该转换）⇒ 未验签的回调"
                        + "端点 = 未授权客户可自证已签。故『没有端点』是当前正确状态，不是遗漏（B-5）。");
    }};

    /**
     * <b>实现侧多出（契约 45 之外）的端点 → 理由</b>（键 = {@code "VERB /normalized/path"}）。
     *
     * <p>三类：<b>内部自描述</b>（把契约纪律变成运行时事实）· <b>演示/联调入口</b>（替代未实现的 A1）·
     * <b>自建能力面</b>（域内自检、结算预演等，非契约 45 端点）。
     * 每一类都必须说明"它为什么可以出站"—— 未登记的新端点会让本类红。
     */
    private static final Map<String, String> INTERNAL_ENDPOINTS = new LinkedHashMap<>() {{
        // ---- 内部自描述端点族（13）：/contract 后缀，把契约纪律变成可读的运行时事实 ----
        String selfDesc = "【内部自描述】只读、免权限，把该域的契约纪律（枚举值域 / 红线 / 缺口登记）"
                + "变成客户端与回归用例可直接读取的运行时事实，而不是只写在文档里。"
                + "刻意不下发任何受限字段，也不含任何阈值数值（防它变成配置值的第二份副本）。";
        put("GET /auth/me/contract", selfDesc);
        put("GET /stores/contract", selfDesc);
        put("GET /customers/contract", selfDesc);
        put("GET /customers/fulfillment/contract", selfDesc);
        put("GET /plans/contract", selfDesc);
        put("GET /band/contract", selfDesc);
        put("GET /customers/{}/band/derived/contract", selfDesc);
        put("POST /band/available-dates/contract", selfDesc);
        put("GET /refunds/contract", selfDesc);
        put("GET /verdicts/contract", selfDesc);
        put("GET /scale-item-banks/contract", selfDesc);
        put("GET /doc-templates/contract", selfDesc);
        put("GET /doc-templates/uploads/contract", selfDesc);
        put("GET /audit/log-chain/contract", selfDesc);

        // ---- 演示/联调入口（4）：替代未实现的 A1，只存在于非生产 profile 语义下 ----
        String demo = "【演示/联调入口】FAKE 身份与样例数据通道，用于替代未实现的 A1 POST /auth/login，"
                + "使三端原型可在无真实 IdP 时联调。它不参与任何业务判定，也不读写业务表。";
        put("GET /demo/me", demo);
        put("GET /demo/customer", demo);
        put("POST /demo/order", demo);
        put("GET /demo/refund", demo);

        // ---- 自建能力面（9）：域内自检 / 结算预演 / 判定回放等，非契约 45 端点 ----
        put("GET /audit/log-chain",
                "【自建能力面·C-1】审计日志哈希链自检端点。audit_log 已含 prev_hash/hash（写入侧完备），"
                        + "本端点让『链是否连续』可被外部校验 —— 合规审计的必需件。"
                        + "它不在契约 45 端点内（内部自描述端点族），由 AuditChainController 自描述登记。");
        put("POST /scale/items/import",
                "【自建能力面·S1-4】题库导入（样例题组）。契约只声明 GET /scale-item-banks（拉取），"
                        + "导入属总部维护入口，非契约端点；由 ScaleItemBankController 内部使用。");
        put("POST /scale/paper/compose",
                "【自建能力面·S1-4】组卷（按锁定年龄组 + 版本取恰好 28 题）。契约未声明该端点；"
                        + "它是评估引擎的准备步骤，供内部调用与演示。");
        put("GET /scale/profile",
                "【自建能力面·S1-4】量表 profile 视图（维度与年龄组）。只读；契约未声明。");
        put("POST /scale/score",
                "【自建能力面·S1-4】计分（7 维 × 4 题）。契约未声明；评估引擎的纯函数入口。");
        put("POST /settlement/preview",
                "【自建能力面·S1-3】跨店通兑结算预演（只读计算，不落库）。契约 45 端点未含结算面，"
                        + "它是 P0-15 跨店通兑链路的自检入口。");
        put("POST /settlement/cross-store-anomaly",
                "【自建能力面·S1-3】跨店异常检测（只读计算，不落库）。同上，属自检面。");
        put("GET /verdicts/{}",
                "【自建能力面·S2-8】按判定 ID 直取单条判定。契约只声明 GET /customers/{id}/verdicts（按客户列表），"
                        + "本端点供回放链路与内部查证使用，由 VerdictController 自描述登记。");
        put("GET /verdicts/{}/replay",
                "【自建能力面·S2-8】判定回放闭环（ADR-11 threshold_version）。四态 "
                        + "REPRODUCED/DRIFTED/DIVERGED/INCOMPARABLE_DOMAIN；内部自描述端点，"
                        + "customerFacing 恒 false。契约未声明该端点。");
    }};

    // ======================================================================
    // 一、扫描器自证（先证明它看得见东西，再让它去说话）
    // ======================================================================

    /**
     * 🛑 门禁类测试的<b>第一条</b>永远是"扫描器不是瞎的"。
     *
     * <p>否则一个写坏的正则会让"新增端点集合"恒为空 ⇒ 覆盖率断言一路绿，
     * 读起来像"全部端点都已登记"，而真相是一个注解都没扫到。
     */
    @Test
    @DisplayName("① 扫描器自证：契约恰 45 operationId；实现侧扫到的端点数 ≥ 60")
    void scanner_sees_both_sides() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Set<String>> contract = contractOperations(root);
        Set<String> impl = implementedEndpoints(root);

        int ops = contract.values().stream().mapToInt(Set::size).sum();
        assertEquals(CONTRACT_OPERATION_COUNT, ops,
                "契约 operationId 数应为 " + CONTRACT_OPERATION_COUNT + "，实际 " + ops
                        + " —— 契约被改过？请同步本台账的 CONTRACT_OPERATION_COUNT 与登记表。");
        assertTrue(contract.size() >= 35,
                "契约路径数应 ≥ 35，实际 " + contract.size() + " —— 路径解析正则可能失效");

        assertTrue(impl.size() >= 60,
                "实现侧扫到的端点数应 ≥ 60，实际 " + impl.size()
                        + " —— 扫描根或映射注解正则失效（cwd 不是 dy-app？）");
        // 反向自证：必须能看见几个必然存在的具体端点（比数量更能证明抓对了内容）
        // 🛑 注意锚点用【归一化后】的形态（参数名一律变 {}），与集合口径一致
        for (String must : List.of("GET /stores", "POST /refunds", "GET /customers/{}",
                "POST /doc-templates/uploads", "GET /audit/log-chain")) {
            assertTrue(impl.contains(must),
                    "实现侧必须能扫到端点 " + must + "（它必然存在）。实际样本: "
                            + new TreeSet<>(impl).stream().limit(10).toList());
        }
    }

    // ======================================================================
    // 二、核心断言：契约 45 = 已实现 ∪ 出范围登记（且两者不交）
    // ======================================================================

    /**
     * <b>本类的核心断言</b>：契约的每个 operationId 必须<b>要么已实现、要么在
     * {@link #OUT_OF_SCOPE} 里登记了理由</b>。
     *
     * <p>这条同时防两个方向：漏做（"还没做"却无人知）与静默出范围（"不做了"却没写为什么）。
     */
    @Test
    @DisplayName("② 契约 45 operationId = 已实现 ∪ 出范围登记（差额必须逐条有理由）")
    void every_contract_operation_is_implemented_or_registered_out_of_scope() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Set<String>> contract = contractOperations(root);
        Set<String> impl = implementedEndpoints(root);

        // operationId → normalized path（供定位）
        Map<String, String> opIdToPath = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : contract.entrySet()) {
            for (String verb : e.getValue()) {
                opIdToPath.put(verb + " " + e.getKey(), e.getKey());
            }
        }
        // 反向：VERB path → operationId
        Map<String, String> verbPathToOpId = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : contract.entrySet()) {
            for (String verb : e.getValue()) {
                verbPathToOpId.put(verb + " " + e.getKey(), verb + " " + e.getKey());
            }
        }

        Set<String> covered = new TreeSet<>();
        Set<String> uncovered = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : contract.entrySet()) {
            String path = e.getKey();
            for (String verb : e.getValue()) {
                if (impl.contains(verb + " " + path)) {
                    covered.add(verb + " " + path);
                } else {
                    uncovered.add(verb + " " + path);
                }
            }
        }

        // 未实现的每一条都必须能对上 OUT_OF_SCOPE 里的一个 operationId
        // （契约侧的 operationId 由本类另建映射：从 YAML 读 operationId → path+verb）
        Map<String, String> opStatus = operationIdToVerbPath(root);
        Set<String> registeredOutOfScope = new TreeSet<>(OUT_OF_SCOPE.keySet());
        Set<String> seenOutOfScope = new TreeSet<>();
        for (String verbPath : uncovered) {
            String opId = verbPathToOpId.get(verbPath);
            // 在 opStatus 里找该 verbPath 对应的 operationId
            String found = null;
            for (Map.Entry<String, String> oe : opStatus.entrySet()) {
                if (oe.getValue().equals(verbPath)) {
                    found = oe.getKey();
                    break;
                }
            }
            assertTrue(found != null,
                    "无法把未实现端点 " + verbPath + " 映射到契约 operationId —— 解析器有缺口");
            assertTrue(registeredOutOfScope.contains(found),
                    "🛑 契约端点 " + verbPath + "（operationId=" + found + "）**未实现，且未在 OUT_OF_SCOPE 登记**。\n"
                            + "请二选一：① 实现它；② 在 OUT_OF_SCOPE 里加一条并写明理由"
                            + "（【Non-goal】/【未立项】/【待冻结】三选一，理由须说清『该找谁解锁』）。\n"
                            + "🛑 不得让它静默处于『契约有、实现无』状态 —— 那会让『明确不做』与『忘了做』"
                            + "在下一次 review 时无法区分。");
            seenOutOfScope.add(found);
        }

        // OUT_OF_SCOPE 里不得残留"其实已实现"的项（归零须显式动作）
        Set<String> stale = new TreeSet<>(registeredOutOfScope);
        stale.removeAll(seenOutOfScope);
        assertTrue(stale.isEmpty(),
                "🛑 OUT_OF_SCOPE 里以下 operationId 实际上【已实现】或【已不在契约】：" + stale + "。\n"
                        + "本表是差额登记面：某端点被补上后必须把对应项删掉，"
                        + "否则『未实现』与『已实现』两个事实并存。");

        // 三条出范围理由必须各含其语义标签（防三义混用）
        for (Map.Entry<String, String> e : OUT_OF_SCOPE.entrySet()) {
            String reason = e.getValue();
            boolean tagged = reason.contains("Non-goal") || reason.contains("未立项")
                    || reason.contains("待冻结");
            assertTrue(tagged,
                    "🛑 OUT_OF_SCOPE 里 " + e.getKey() + " 的理由必须含三种标签之一"
                            + "（【Non-goal】/【未立项】/【待冻结】）—— 三者语义不同"
                            + "（不需要 / 没依据 / 实现了更危险），混用会让下一次 review 不知该找谁解锁。"
                            + "实际: " + reason);
            assertTrue(reason.length() >= 30,
                    "🛑 OUT_OF_SCOPE 里 " + e.getKey() + " 的理由过短（" + reason.length()
                            + " 字符）—— 占位式的一条等于没登记。");
        }

        // 冻结口径（与 javadoc 的机械清点结果一致）
        assertEquals(41, covered.size(),
                "已实现的契约端点数应为 41，实际 " + covered.size() + "。" + uncovered);
        assertEquals(4, uncovered.size(),
                "未实现的契约端点数应为 4，实际 " + uncovered.size() + ": " + uncovered);
    }

    // ======================================================================
    // 三、反向断言：实现侧多出的端点必须登记
    // ======================================================================

    /**
     * 实现侧相对契约<b>多出</b>的端点，必须在 {@link #INTERNAL_ENDPOINTS} 里登记。
     *
     * <p>🛑 这条防的是"未登记的出站面"：新开一个内部端点不会与任何既有断言冲突，
     * 却可能带着内部口径出站。登记本身就是"为什么它可以出站"的一次书面决定。
     */
    @Test
    @DisplayName("③ 实现侧多出的端点必须在 INTERNAL_ENDPOINTS 登记（防未登记出站面）")
    void every_extra_endpoint_is_registered_as_internal() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Set<String>> contract = contractOperations(root);
        Set<String> impl = implementedEndpoints(root);

        Set<String> contractVerbPaths = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : contract.entrySet()) {
            for (String verb : e.getValue()) {
                contractVerbPaths.add(verb + " " + e.getKey());
            }
        }

        Set<String> extra = new TreeSet<>(impl);
        extra.removeAll(contractVerbPaths);

        Set<String> unregistered = new TreeSet<>(extra);
        unregistered.removeAll(INTERNAL_ENDPOINTS.keySet());
        assertTrue(unregistered.isEmpty(),
                "🛑 以下实现侧端点既不在契约 45 端点内、也未在 INTERNAL_ENDPOINTS 登记: " + unregistered + "。\n"
                        + "契约未声明的端点 = 未登记的出站面。请在本表加一条并写明理由"
                        + "（内部自描述 / 演示入口 / 自建能力面），"
                        + "说明『它为什么可以出站』—— 登记本身就是一次书面决定。");

        // INTERNAL_ENDPOINTS 里不得有"其实已不存在"的僵尸登记
        Set<String> zombies = new TreeSet<>(INTERNAL_ENDPOINTS.keySet());
        zombies.removeAll(extra);
        assertTrue(zombies.isEmpty(),
                "🛑 INTERNAL_ENDPOINTS 里以下端点已不存在于实现侧（僵尸登记）: " + zombies + "。\n"
                        + "归零要求显式动作 —— 端点被删后必须同步删掉登记，否则本表会慢慢变成"
                        + "一份『曾经存在过什么』的考古记录，而不是当前的边界声明。");

        assertEquals(27, INTERNAL_ENDPOINTS.size(),
                "INTERNAL_ENDPOINTS 应为 27 条，实际 " + INTERNAL_ENDPOINTS.size());
    }

    // ======================================================================
    // 四、汇总：覆盖率口径可核对
    // ======================================================================

    /**
     * 覆盖率口径必须可被一条断言核对 —— 使"系统开发到什么程度"有一个<b>唯一的数字</b>。
     */
    @Test
    @DisplayName("④ 覆盖率口径：45 = 41 已实现 + 4 出范围；实现侧 68 = 41 + 27 内部")
    void coverage_numbers_are_consistent() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Set<String>> contract = contractOperations(root);
        Set<String> impl = implementedEndpoints(root);

        int ops = contract.values().stream().mapToInt(Set::size).sum();
        Set<String> contractVerbPaths = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : contract.entrySet()) {
            for (String verb : e.getValue()) {
                contractVerbPaths.add(verb + " " + e.getKey());
            }
        }
        Set<String> covered = new TreeSet<>(impl);
        covered.retainAll(contractVerbPaths);

        assertEquals(45, ops, "契约operationId 总数变了");
        assertEquals(41, covered.size(), "已实现契约端点数变了");
        assertEquals(4, OUT_OF_SCOPE.size(), "出范围登记数变了");
        assertEquals(ops, covered.size() + OUT_OF_SCOPE.size(),
                "45 ≠ 已实现 + 出范围 —— 覆盖台账与登记面不一致");

        int extraCount = impl.size() - covered.size();
        assertEquals(INTERNAL_ENDPOINTS.size(), extraCount,
                "实现侧多出的端点数（" + extraCount + "）与 INTERNAL_ENDPOINTS 登记数（"
                        + INTERNAL_ENDPOINTS.size() + "）不一致");
        assertEquals(68, impl.size(),
                "实现侧端点总数应为 68（41 契约 + 27 内部），实际 " + impl.size());
    }

    // ======================================================================
    // helpers（与 UpstreamGapRegistryTest / ContractFreezeGateTest 同口径，
    //   刻意不跨类共享：共享 helper 会把多个门禁耦合成"改一个动全"）
    // ======================================================================

    /** 解析契约 → {@code normalizedPath → {VERB}}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> contractOperations(Path root) throws IOException {
        String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);
        Object parsed = new Yaml().load(text);
        assertTrue(parsed instanceof Map, "契约顶层必须是 mapping，实为: "
                + (parsed == null ? "null" : parsed.getClass().getName()));
        Map<String, Object> doc = (Map<String, Object>) parsed;
        Object pathsObj = doc.get("paths");
        assertTrue(pathsObj instanceof Map, "契约缺 paths");
        Map<String, Object> paths = (Map<String, Object>) pathsObj;

        Map<String, Set<String>> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            String path = normalize(e.getKey());
            assertTrue(e.getValue() instanceof Map, "path item 应为 mapping: " + e.getKey());
            Map<String, Object> item = (Map<String, Object>) e.getValue();
            Set<String> verbs = new TreeSet<>();
            for (String verb : List.of("get", "post", "patch", "put", "delete")) {
                Object op = item.get(verb);
                if (op instanceof Map) {
                    Map<String, Object> opMap = (Map<String, Object>) op;
                    if (opMap.get("operationId") != null) {
                        verbs.add(verb.toUpperCase());
                    }
                }
            }
            if (!verbs.isEmpty()) {
                out.put(path, verbs);
            }
        }
        return out;
    }

    /** 解析契约 → {@code operationId → "VERB normalizedPath"}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> operationIdToVerbPath(Path root) throws IOException {
        String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);
        Map<String, Object> doc = (Map<String, Object>) new Yaml().load(text);
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            String path = normalize(e.getKey());
            Map<String, Object> item = (Map<String, Object>) e.getValue();
            for (String verb : List.of("get", "post", "patch", "put", "delete")) {
                Object op = item.get(verb);
                if (op instanceof Map) {
                    Object id = ((Map<String, Object>) op).get("operationId");
                    if (id != null) {
                        out.put(String.valueOf(id), verb.toUpperCase() + " " + path);
                    }
                }
            }
        }
        return out;
    }

    /** 扫 {@code dy-app/src/main/java} 的控制器，返回 {@code "VERB normalizedPath"} 集合。 */
    private static Set<String> implementedEndpoints(Path root) throws IOException {
        Path src = root.resolve("skeleton/dy-app/src/main/java");
        assertTrue(Files.isDirectory(src), "生产源码目录不存在: " + src);

        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(src)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(files::add);
        }

        Set<String> out = new TreeSet<>();
        for (Path f : files) {
            String cleaned = stripComments(Files.readString(f, StandardCharsets.UTF_8));
            // 类级前缀
            String base = "";
            Matcher cm = Pattern.compile(
                    "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"").matcher(cleaned);
            if (cm.find()) {
                base = cm.group(1);
            }
            // 方法级映射（有参 / 无参两种形态）
            Matcher m = Pattern.compile(
                    "@(Get|Post|Patch|Put|Delete)Mapping(?:\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?")
                    .matcher(cleaned);
            while (m.find()) {
                String verb = m.group(1).toUpperCase();
                String p = m.group(2) == null ? "" : m.group(2);
                String full;
                if (p.startsWith("/")) {
                    full = base + p;
                } else if (p.isEmpty()) {
                    full = base;
                } else {
                    full = base + "/" + p;
                }
                out.add(verb + " " + normalize(full));
            }
        }
        return out;
    }

    /** 归一化：剥 {@code /api/v1} 前缀 + 参数名一律变 {@code {}}。 */
    private static String normalize(String p) {
        String s = p.replaceAll("\\{[^}]*}", "{}");
        if (s.startsWith(API_PREFIX)) {
            s = s.substring(API_PREFIX.length());
        }
        if (s.isEmpty()) {
            s = "/";
        }
        return s;
    }

    /** 逐字符剥离注释与字符串边界（与 {@code PermissionCodeRegistrationGateTest} 同口径）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        boolean inLine = false, inBlock = false, inStr = false;
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char next = (i + 1 < n) ? src.charAt(i + 1) : '\0';
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
                }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < n) {
                        out.append(next);
                        i++;
                    }
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

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
        for (int i = 0; i < 5 && cursor != null; i++, cursor = cursor.getParent()) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
        }
        throw new AssertionError("无法定位仓库根（需同时存在 " + CONTRACT_MD_REL + " 与 " + OPENAPI_REL
                + "）。cwd=" + Path.of("").toAbsolutePath());
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isRegularFile(p.resolve(CONTRACT_MD_REL.replace('/', File.separatorChar)))
                && Files.isRegularFile(p.resolve(OPENAPI_REL.replace('/', File.separatorChar)));
    }
}