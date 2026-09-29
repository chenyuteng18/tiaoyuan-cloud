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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游契约缺口登记门禁（批次五 · A-10 / N-2 / N-5 / N-6）——
 * 把"这四条差异已登记且不会静默漂移"从<b>文档纪律</b>变成<b>每次构建都会跑的断言</b>。
 *
 * <h2>🛑 为什么登记类事项也需要门禁，而不是只写在 README §5.2</h2>
 * 本项目反复出现过一个形态：<b>登记写在文档里，而文档不会被构建读取</b>。
 * 于是"登记"退化成一句注释 —— 有人改了契约/字典/注册表的对应一侧，
 * 文档仍然写着旧结论，而<b>没有任何测试会红</b>。
 * 本类把四条登记各配一条<b>机械断言</b>，使：
 * <ul>
 *   <li><b>差异真实存在</b>（不是我们记错了）—— 断言两侧的原文事实；</li>
 *   <li><b>一侧被改动时本类立刻红</b> —— 红的信息里给出"该关闭哪条阻塞项"；</li>
 *   <li><b>差异被消除后也不算绿到底</b> —— 归零要求显式改登记表（与
 *       {@code ContractFreezeGateTest.KNOWN_DIFFERENCES} / {@code ContractConsistencyTest}
 *       的登记式 Harness 同构）。</li>
 * </ul>
 *
 * <h2>登记的四条</h2>
 * <table>
 *   <tr><th>编号</th><th>事项</th><th>本类断言</th></tr>
 *   <tr><td><b>A-10</b></td><td>F3 {@code /audit/signals} / F4 {@code /audit/coverage}
 *       未立项（明确出范围）</td><td>{@link #f3_f4_are_registered_as_out_of_scope_with_contract_row_anchors}</td></tr>
 *   <tr><td><b>N-2</b></td><td>{@code band_telemetry.data_source} 取值语言（中文 vs 英文 token）</td>
 *       <td>{@link #data_source_language_matches_the_frozen_contract_enum}</td></tr>
 *   <tr><td><b>N-5</b></td><td>{@code store_customer_service} 角色无端（契约侧缺口）</td>
 *       <td>{@link #store_customer_service_has_no_endpoint_in_the_contract}</td></tr>
 *   <tr><td><b>N-6</b></td><td>契约 §2.0 缺「未知角色」错误码</td>
 *       <td>{@link #contract_error_code_table_has_no_unknown_role_code}</td></tr>
 * </table>
 *
 * <p>🛑 本类的全部判据都读<b>真实文件</b>（契约 YAML / 迁移 SQL / 字典 md），
 * 不依赖任何运行时状态，也不手抄清单 —— 手抄的清单会在某次"一侧改了另一侧没改"时分叉，
 * 而分叉的那一侧恰好是本类要检查的东西。
 */
@DisplayName("上游契约缺口登记门禁（A-10 / N-2 / N-5 / N-6）")
class UpstreamGapRegistryTest {

    private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";
    private static final String CONTRACT_MD_REL = "_work/contract-t6-api-freeze-2026-09-19.md";
    private static final String DICT_REL = "_work/data-dict-entities-ddl-2026-09-19.md";
    private static final String MIG_V3_REL =
            "skeleton/dy-app/src/main/resources/db/migration/V3__band_telemetry_metric_long_table.sql";

    // ======================================================================
    // 一、A-10 · F3 / F4 明确出范围（Non-goal 登记）
    // ======================================================================

    /**
     * <b>A-10</b>：F3 {@code GET /audit/signals} 与 F4 {@code GET /audit/coverage}
     * 依赖上游口径（"稽核信号"的定义 / "覆盖率"三数的分子分母时窗），口径未定 ⇒ 未立项。
     *
     * <h2>🛑 本断言守的三件事</h2>
     * <ol>
     *   <li><b>两端点确实在契约里</b>（否则"未立项"是无意义的 —— 不会被误认为漏做）；</li>
     *   <li><b>本仓库确实没有实现它们</b>（{@code dy-app/src/main} 下无对应路径映射）——
     *       这是 Non-goal 的<b>事实基础</b>：不是"还没做"，是"明确不做"；</li>
     *   <li><b>登记仍在</b>（{@code AuditChainController} 的自描述端点逐字点名了这两条）——
     *       使"出范围"这件事在运行时也可读，而不只在 README 里。</li>
     * </ol>
     * 若某天产品 owner 补了口径并立项，本断言的第 ② 条会红 —— 那时应把 F3/F4
     * 从 Non-goal 登记表移到"待实现"，而不是删掉本类。
     */
    @Test
    @DisplayName("A-10：F3/F4 在契约里存在、实现侧确无、且出范围登记仍在（三事同证）")
    void f3_f4_are_registered_as_out_of_scope_with_contract_row_anchors() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Object> doc = parseOpenapi(root);

        // ① 两端点在契约里（按 x-contract-row 锚定，不靠路径字符串猜）
        Map<String, String> expectedPaths = Map.of(
                "F3", "/audit/signals",
                "F4", "/audit/coverage");
        Map<String, Object> paths = cast(doc.get("paths"), "paths");
        for (Map.Entry<String, String> e : expectedPaths.entrySet()) {
            String row = e.getKey();
            String path = e.getValue();
            assertTrue(paths.containsKey(path),
                    "🛑 契约里找不到 " + row + " 的路径 " + path + " —— "
                            + "若上游把这两行移出契约，请同步更新本登记（Non-goal 的登记对象是『契约行』）。");
            Map<String, Object> get = cast(cast(paths.get(path), path).get("get"), path + ".get");
            assertEquals(row, str(get.get("x-contract-row")),
                    "🛑 " + path + " 的 x-contract-row 应为 " + row + " —— 契约行锚点漂了。");
        }

        // ② 实现侧确无（在 dy-app / dy-web 的源码里搜不到这两个路径的映射）
        //    🛑 搜索面限定 src/main（生产代码），并排除测试与备份 ——
        //       否则契约自描述端点里【提到】这两个路径的那些字符串会被当成"已实现"。
        List<Path> mainSources = new ArrayList<>();
        for (String mod : List.of("dy-app", "dy-web")) {
            Path p = root.resolve("skeleton").resolve(mod).resolve("src/main/java");
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    s.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".java"))
                            .forEach(mainSources::add);
                }
            }
        }
        assertTrue(mainSources.size() >= 20,
                "生产源码文件数应 ≥ 20，实际 " + mainSources.size()
                        + " —— 扫描根可能失效（工作目录不是 skeleton/？），本断言会退化成恒绿");

        List<String> offenders = new ArrayList<>();
        for (Path f : mainSources) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String cleaned = stripComments(src);
            // 只看【映射注解里的字面路径】：GetMapping(".../audit/signals") 之类
            Matcher m = Pattern.compile(
                    "@(?:Get|Post|Put|Patch|Delete)Mapping\\s*\\(\\s*\"([^\"]*)\"")
                    .matcher(cleaned);
            while (m.find()) {
                String mapped = m.group(1);
                if (mapped.contains("/audit/signals") || mapped.contains("/audit/coverage")) {
                    offenders.add(root.relativize(f) + " → " + mapped);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "🛑 检测到 F3/F4 已被实现: " + offenders + "。\n"
                        + "A-10 的登记是『明确出范围（Non-goal）』—— 若产品 owner 已补口径并立项，"
                        + "请把这两条从出范围登记移入『待实现』并更新 README §5.2，"
                        + "而不是让『未立项』与『已实现』两个事实同时存在。");

        // ③ 出范围登记仍在（运行时自描述端点逐字点名 —— 使事实在接口层可读）
        Path auditCtrl = root.resolve("skeleton/dy-app/src/main/java/com/diaoyuanyun/dy/app/"
                + "audit/controller/AuditChainController.java");
        assertTrue(Files.isRegularFile(auditCtrl), "审计链控制器不存在: " + auditCtrl);
        String ctrl = Files.readString(auditCtrl, StandardCharsets.UTF_8);
        for (String must : List.of("GET /audit/signals", "GET /audit/coverage")) {
            assertTrue(ctrl.contains(must),
                    "🛑 审计链自描述端点里找不到出范围登记 '" + must + "' —— "
                            + "A-10 的登记必须同时存在于『文档』与『运行时自描述』两处，"
                            + "否则它只在人读 README 时存在。");
        }
        assertTrue(ctrl.contains("未立项"),
                "🛑 出范围登记应逐字含『未立项』—— 它是 Non-goal 的登记措辞，"
                        + "改成其它词会让『明确不做』与『还没做』混为一谈。");
    }

    // ======================================================================
    // 二、N-2 · data_source 取值语言（机械裁定：以契约为准）
    // ======================================================================

    /**
     * <b>N-2</b>：{@code band_telemetry.data_source} 的取值是中文 {@code ('手环','未接入')}，
     * 而同表 {@code metric} / {@code gap_reason} / {@code sync_state} 全是英文 token ——
     * "是否统一为英文"曾被登记为待裁。
     *
     * <h2>🛑 本类给出机械裁定依据（不是新拍的口径）</h2>
     * 契约把 {@code BandTelemetryData.data_source} 逐字冻结为
     * {@code enum: [手环, 未接入]}，且该字段 {@code x-visible-to: [client, ...]} ——
     * 它是<b>客户可见的出参枚举</b>。契约 §1.4 明文：<b>可见性档位变更 / 字段删除 /
     * 类型变更 = MAJOR（破坏级）</b>；而枚举取值域的变更对客户端是破坏性的
     * （客户端会按冻结的取值集渲染）。故：
     * <ol>
     *   <li>改动 {@code data_source} 的取值语言 = <b>改契约冻结面</b>，须走 §1.5 变更流程；</li>
     *   <li>在契约未变更之前，<b>库层必须与契约逐字一致</b>（即保持中文），
     *       否则读写往返会在边界处出现第二份词表（本项目反复登记的失效模式）；</li>
     *   <li>故本断言把"库层 = 契约 enum"钉成事实。若将来契约改为英文 token，
     *       本断言会红，提示"先改契约与字典，再改迁移"。</li>
     * </ol>
     * 这条把 N-2 从"待裁"推进为"<b>以契约为准</b>"—— 依据是契约的可见性与 MAJOR 级纪律，
     * 不是审美偏好。真正的待裁点因此收窄为："是否值得为统一风格而对客户可见枚举做一次 MAJOR 变更"。
     */
    @Test
    @DisplayName("N-2：data_source 库层取值与契约冻结 enum 逐字一致（以契约为准的机械裁定）")
    void data_source_language_matches_the_frozen_contract_enum() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Object> doc = parseOpenapi(root);

        // ① 契约侧：BandTelemetryData.data_source 的 enum 逐字读出
        Map<String, Object> schemas = cast(cast(doc.get("components"), "components")
                .get("schemas"), "components.schemas");
        Map<String, Object> telemetry = cast(schemas.get("BandTelemetryData"), "BandTelemetryData");
        Map<String, Object> props = cast(telemetry.get("properties"), "BandTelemetryData.properties");
        Map<String, Object> dataSource = cast(props.get("data_source"), "data_source");
        List<Object> enumValues = list(dataSource.get("enum"), "data_source.enum");

        List<String> contractEnum = enumValues.stream().map(UpstreamGapRegistryTest::str).toList();
        assertEquals(List.of("手环", "未接入"), contractEnum,
                "🛑 契约 BandTelemetryData.data_source 的 enum 变了: " + contractEnum + "。\n"
                        + "N-2 的裁定依据就是这条 enum（见本方法 javadoc）：它是【客户可见出参枚举】，"
                        + "改取值域属 §1.4 的 MAJOR 级（破坏级）变更。"
                        + "若确实改了，请同步更新：① 字典 §2.17 的 DDL 与缺陷登记；"
                        + "② V3 的 CHECK；③ 本断言；④ README §5.2 的 N-2 条目。");

        // 该字段必须是客户可见的（这是"改它属 MAJOR"的前提）
        List<Object> visibleTo = list(dataSource.get("x-visible-to"), "data_source.x-visible-to");
        assertTrue(visibleTo.stream().map(UpstreamGapRegistryTest::str).toList().contains("client"),
                "🛑 data_source 不再对 client 可见了 —— 若如此，N-2 的『MAJOR 级』论证前提就不成立，"
                        + "本登记的措辞需重写（但库层仍须与契约一致）。实际可见集: " + visibleTo);

        // ② 库层侧：V3 的 CHECK 取值集必须与契约 enum 逐字一致
        Path v3 = root.resolve(MIG_V3_REL);
        assertTrue(Files.isRegularFile(v3), "V3 迁移不存在: " + v3);
        String sql = Files.readString(v3, StandardCharsets.UTF_8);
        Matcher check = Pattern.compile(
                "data_source\\s+VARCHAR\\s*\\(\\d+\\)\\s+NOT NULL[^,]*?"
                        + "CHECK\\s*\\(\\s*data_source\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(check.find(),
                "🛑 无法从 V3 提取 data_source 的 CHECK 取值集 —— "
                        + "格式变了，本断言的提取正则需同步（提取失败会让后续断言假绿）");
        List<String> dbEnum = new ArrayList<>();
        Matcher lit = Pattern.compile("'([^']*)'").matcher(check.group(1));
        while (lit.find()) {
            dbEnum.add(lit.group(1));
        }
        assertEquals(contractEnum, dbEnum,
                "🛑 V3 的 data_source CHECK 取值集与契约 enum 不一致。\n"
                        + "契约（客户可见出参枚举）: " + contractEnum + "\n"
                        + "库层（V3 CHECK）:         " + dbEnum + "\n"
                        + "N-2 的裁定是【以契约为准】：契约未改之前库层必须逐字一致，"
                        + "否则读写往返会在边界处多出一份词表（本项目反复登记的失效模式）。");

        // ③ 字典侧：缺陷登记仍在（它不是"我记错了"的证据链）
        Path dict = root.resolve(DICT_REL);
        if (Files.isRegularFile(dict)) {
            String md = Files.readString(dict, StandardCharsets.UTF_8);
            assertTrue(md.contains("data_source"),
                    "🛑 字典里找不到 data_source —— N-2 的登记依据（字典 §2.17）被改动了，需重新核定");
        }
    }

    // ======================================================================
    // 三、N-5 · store_customer_service 角色无端（契约侧缺口）
    // ======================================================================

    /**
     * <b>N-5</b>：契约 {@code x-roles} 把 {@code store_customer_service} 登记为
     * {@code token-role: null, end: null}（"无端，无任何接口"），而在
     * {@code RefundAudienceRole} / {@code BandAudienceRole} 两个域里它都作为键出现。
     *
     * <h2>🛑 本断言守三件事</h2>
     * <ol>
     *   <li><b>契约事实</b>：该角色确实是 {@code token-role: null} + {@code end: null} ——
     *       这是"它没有端、不可能带此身份发请求"的<b>唯一依据</b>；</li>
     *   <li><b>可见性矩阵事实</b>：{@code x-visibility-matrix} 五行里，该角色的
     *       <b>全部四个字段组</b>都必须是 {@code false}（"任何字段组均不成立"）；</li>
     *   <li><b>契约端点事实</b>：全部 45 个 operationId 的 {@code x-callable-roles} 里
     *       <b>不得出现</b>该角色 —— 若哪天出现，说明"无端"这条契约事实被推翻了，
     *       N-5 与 P0-19 都要重新裁定。</li>
     * </ol>
     * 这三条一起把 N-5 钉成<b>可复核的契约事实</b>而非"某处提到过"。
     */
    @Test
    @DisplayName("N-5：store_customer_service 无端 —— x-roles 为 null、矩阵四组全 false、45 端点均不可调")
    void store_customer_service_has_no_endpoint_in_the_contract() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Object> doc = parseOpenapi(root);

        // ① x-roles：token-role / end 双 null
        Map<String, Object> roles = cast(doc.get("x-roles"), "x-roles");
        Map<String, Object> scs = cast(roles.get("store_customer_service"), "x-roles.store_customer_service");
        assertEquals("null", String.valueOf(scs.get("token-role")),
                "🛑 契约 x-roles 里 store_customer_service 的 token-role 不再是 null —— "
                        + "N-5 的整条论证前提（『它不是一个 token 角色』）就不成立了，需重新裁定。"
                        + "实际: " + scs.get("token-role"));
        assertEquals("null", String.valueOf(scs.get("end")),
                "🛑 契约 x-roles 里 store_customer_service 的 end 不再是 null —— 同上。"
                        + "实际: " + scs.get("end"));

        // ② x-visibility-matrix：该角色四个字段组全 false
        Map<String, Object> matrix = cast(doc.get("x-visibility-matrix"), "x-visibility-matrix");
        List<String> groups = List.of("raw_data", "capture_status", "gap_reason", "derived_result");
        for (String g : groups) {
            Map<String, Object> row = cast(matrix.get(g), "x-visibility-matrix." + g);
            assertFalse(Boolean.TRUE.equals(row.get("store_customer_service")),
                    "🛑 可见性矩阵的 " + g + " 组把 store_customer_service 置为 true —— "
                            + "契约的注逐字写着『门店客服无端（不使用系统）—— 该界面不成立，"
                            + "任何字段组均不成立』。实际: " + row.get("store_customer_service"));
        }

        // ③ 45 个 operationId 的 x-callable-roles 里不得出现该角色
        Map<String, Object> paths = cast(doc.get("paths"), "paths");
        List<String> offenders = new ArrayList<>();
        int opCount = 0;
        for (Object pathItemRaw : paths.values()) {
            Map<String, Object> pathItem = cast(pathItemRaw, "pathItem");
            for (String verb : List.of("get", "post", "put", "patch", "delete")) {
                Object opRaw = pathItem.get(verb);
                if (!(opRaw instanceof Map)) {
                    continue;
                }
                Map<String, Object> op = cast(opRaw, verb);
                if (op.get("operationId") == null) {
                    continue;
                }
                opCount++;
                Object rolesRaw = op.get("x-callable-roles");
                if (rolesRaw instanceof List) {
                    for (Object r : list(rolesRaw, "x-callable-roles")) {
                        if ("store_customer_service".equals(str(r))) {
                            offenders.add(str(op.get("operationId")));
                        }
                    }
                }
            }
        }
        assertEquals(45, opCount,
                "🛑 契约 operationId 数应为 45（冻结口径），实际 " + opCount
                        + " —— 端点集变了，请同步核对 D3 冻结面与 README 的覆盖度口径。");
        assertTrue(offenders.isEmpty(),
                "🛑 契约里有端点把 store_customer_service 列为可调角色: " + offenders + "。\n"
                        + "N-5 的登记断言『该角色无端、任何接口均不成立』，"
                        + "与用户记忆中的 P0-19（门店/客服退款文案可见性）互为表里 —— "
                        + "若上游确实给了它端，请同时更新 N-5 与 P0-19 的裁定。");
    }

    // ======================================================================
    // 四、N-6 · 契约 §2.0 缺「未知角色」错误码
    // ======================================================================

    /**
     * <b>N-6</b>：{@code OrgLevel.fromRole} / 角色解算对<b>未登记角色</b> fail-closed 抛错，
     * 但契约 §2.0 的 12 个错误码里<b>没有</b>"未知角色"码 ⇒ 只能复用 {@code VISIBILITY_DENIED(2001)}。
     * 而 {@code TENANT_MISMATCH(2003)} 是<b>跨租户</b>语义 —— 两者排查方向完全不同
     * （一个"角色没登记"，一个"租户不匹配"）。
     *
     * <h2>🛑 本断言守两件事</h2>
     * <ol>
     *   <li><b>契约确实没有</b>一个"未知角色"码 —— 逐条读 §2.0 错误码表的
     *       {@code code} 与描述，确认没有任何一条讲"角色未登记/未知角色"；</li>
     *   <li><b>错误码表恰为契约冻结的 12 条</b> —— 若上游补了第 13 条（正是 N-6 想要的），
     *       本断言会红并要求同步登记表，而不是让"缺一个码"与"已有该码"两个事实并存。</li>
     * </ol>
     */
    @Test
    @DisplayName("N-6：契约 §2.0 错误码表无『未知角色』码，且恰为冻结的 12 条")
    void contract_error_code_table_has_no_unknown_role_code() throws IOException {
        Path root = resolveRepoRoot();
        Map<String, Object> doc = parseOpenapi(root);

        // 契约 §2.0 错误码表落在顶层 x-error-codes（{http, code, name, trigger} 列表）——
        // 逐条读出，判"有没有讲未知角色"。🛑 不按 schema 猜：表的位置本身是契约事实。
        List<Object> raw = list(doc.get("x-error-codes"), "x-error-codes");
        Map<Integer, String> byCode = new TreeMap<>();
        Map<Integer, String> triggerByCode = new TreeMap<>();
        for (Object o : raw) {
            Map<String, Object> row = cast(o, "x-error-codes[]");
            Integer code = asInt(row.get("code"));
            assertNotNull(code, "x-error-codes 里的每行必须有 code。实际行: " + row);
            byCode.put(code, str(row.get("name")));
            triggerByCode.put(code, str(row.get("trigger")));
        }

        // 冻结的 12 条（契约 §2.0 —— 与 ErrorCode 枚举同源）
        Set<Integer> frozen = Set.of(1001, 1002, 2001, 2002, 2003, 2004,
                3001, 4001, 4002, 5001, 6001, 9001);
        assertEquals(new TreeSet<>(frozen), new TreeSet<>(byCode.keySet()),
                "🛑 契约 §2.0 错误码表不再是冻结的 12 条。\n"
                        + "实际: " + new TreeSet<>(byCode.keySet()) + "\n"
                        + "期望: " + new TreeSet<>(frozen) + "\n"
                        + "🛑 若上游补了『未知角色』码（这正是 N-6 请求的），"
                        + "请同步更新 ErrorCode 枚举、本断言与 README §5.2 的 N-6 条目 —— "
                        + "而不是让『缺一个码』与『已有该码』两个事实并存。");

        // 逐条确认没有任何一条的 name / trigger 讲"未知角色 / 角色未登记"
        for (Map.Entry<Integer, String> e : byCode.entrySet()) {
            String name = e.getValue();
            String trigger = triggerByCode.getOrDefault(e.getKey(), "");
            assertFalse(name.contains("ROLE") && (name.contains("UNKNOWN") || name.contains("UNREGISTERED")),
                    "🛑 契约错误码表出现了『未知角色』码: " + e.getKey() + " = " + name + "。\n"
                            + "若上游确实补了该码，请关闭 N-6 阻塞项并更新登记表，"
                            + "而不是让 N-6 继续写着『契约缺该码』。");
            assertFalse(trigger.contains("未知角色") || trigger.contains("角色未登记")
                            || trigger.contains("未知的角色"),
                    "🛑 契约错误码 " + e.getKey() + " 的 trigger 讲到了『未知角色』: " + trigger + "。\n"
                            + "同上：关闭 N-6 并更新登记表。");
        }

        // 反向自证：契约里确实存在 2001 与 2003（N-6 的论证正是围绕这两条的区别）
        assertTrue(byCode.containsKey(2001) && byCode.containsKey(2003),
                "🛑 契约 2001/2003 之一缺失 —— N-6 的核心论证是『两者排查方向完全不同，"
                        + "故不该复用』，这两条必须都在。实际码集: " + byCode.keySet());
    }

    // ======================================================================
    // helpers（与 ContractFreezeGateTest 同口径，刻意不跨类共享：
    //   共享 helper 会把两个门禁耦合成"改一个动全" —— 而它们守的是不同的上游面）
    // ======================================================================

    private static Map<String, Object> parseOpenapi(Path root) throws IOException {
        String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);
        Object parsed = new Yaml().load(text);
        assertNotNull(parsed, "契约解析结果为 null —— 文件为空或格式错误: " + OPENAPI_REL);
        assertTrue(parsed instanceof Map, "契约顶层必须是 mapping，实为: " + parsed.getClass().getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
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
                + "）。请用 -Ddy.docs.root=<abs> 显式指定。cwd=" + Path.of("").toAbsolutePath());
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isRegularFile(p.resolve(CONTRACT_MD_REL.replace('/', File.separatorChar)))
                && Files.isRegularFile(p.resolve(OPENAPI_REL.replace('/', File.separatorChar)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o, String where) {
        assertTrue(o instanceof Map, where + " 应为 mapping，实为: "
                + (o == null ? "null" : o.getClass().getName()));
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String where) {
        assertTrue(o instanceof List, where + " 应为 list，实为: "
                + (o == null ? "null" : o.getClass().getName()));
        return (List<Object>) o;
    }

    private static String str(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 剥 Java 注释（行注释 + 块注释），逐字符并跟踪字符串边界。
     *
     * <p>🛑 为什么必须剥：控制器 javadoc 里会<b>提及</b>路径字面
     * （{@code AuditChainController} 的注释就逐字写着 {@code GET /audit/signals}），
     * 不剥的话"未实现"那条断言会在注释上红 —— 而"因为注释写了路径就报已实现"
     * 会逼着下一个人删掉注释而不是修问题（与 {@code PermissionCodeRegistrationGateTest}
     * 的 {@code stripComments} 存在理由完全相同）。
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
}