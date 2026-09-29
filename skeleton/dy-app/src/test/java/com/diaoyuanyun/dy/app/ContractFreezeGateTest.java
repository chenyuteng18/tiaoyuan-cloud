package com.diaoyuanyun.dy.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约冻结闸门（S1-1 · D3 硬闸门）—— 断言 <b>OpenAPI 契约 ↔ 上游语义母本 ↔ 配置 ↔ 原型</b> 四方一致。
 *
 * <h2>为什么要有这个类</h2>
 * S1-1 的验收条款第 ③ 条要求「字段可见性矩阵与原型 <b>D1 四端矩阵逐格一致</b>」。这句话
 * 无法用"我写的常量 == 我写的常量"证明 —— 那种测试只能证明代码没变，不能证明代码<b>与源头一致</b>。
 * 故本类的输入全部是<b>仓库里的真实文件</b>：
 * <ol>
 *   <li>{@code contract/openapi-v1.0.0.yaml} —— 用 snakeyaml <b>真解析</b>（不是 grep、不是正则）</li>
 *   <li>{@code _work/contract-t6-api-freeze-2026-09-19.md} —— 上游语义母本（§2 行表 / §2.0 错误码 / §3.1 矩阵）</li>
 *   <li>{@code contract/visibility/band-visibility-matrix.json} —— 派生导出</li>
 *   <li>{@code skeleton/dy-config/.../02_slots_seed.sql} —— config #43 实际取值</li>
 *   <li>{@code prototype/index.html} —— 原型 D1 四端矩阵</li>
 * </ol>
 *
 * <h2>它堵的洞（逐条对应 S1-1 验收）</h2>
 * <table>
 *   <tr><th>验收条款</th><th>本类对应断言</th></tr>
 *   <tr><td>① OpenAPI 3.0 文件入仓</td><td>{@link #openapi_is_a_parsable_openapi_3_document_with_frozen_version}</td></tr>
 *   <tr><td>② 三端接口一次冻结并版本化</td><td>{@link #contract_row_set_matches_upstream_section_2_exactly}</td></tr>
 *   <tr><td>③ 字段可见性矩阵与原型 D1 逐格一致</td><td>{@link #visibility_matrix_is_cell_by_cell_identical_across_five_sources}</td></tr>
 *   <tr><td>④ 契约评审通过（S1-7 探测接口并入）</td><td>{@link #s1_7_probe_interface_is_merged_into_the_freeze}</td></tr>
 * </table>
 *
 * <h2>反向验证（证明非摆设 · 硬纪律 #7）</h2>
 * {@link #gate_turns_red_when_any_single_source_is_perturbed} 在 {@code @TempDir} 造 5 份
 * <b>单源扰动副本</b>，逐一证明闸门确实会红：
 * ① 契约少一行 / ② 契约某格矩阵翻转 / ③ 上游少一行 / ④ config #43 翻转 /
 * ⑤ 原型 D1 翻转 / ⑥ 客户禁入行被放行。
 * <p>另有 {@link #parser_must_fail_loudly_on_malformed_contract} 钉住"解析不出来必须抛错，
 * 不能退化成空集静默通过"。
 *
 * <h2>已登记的已知差异（显式列出，不默默改）</h2>
 * {@link #KNOWN_DIFFERENCES} 登记上游 §2.9 与 §2.0 的 <b>同一错误码两义</b> 冲突。
 * 登记式不是恒绿摆设：一旦上游任一侧被改动，本类立刻红并要求「更新登记表 + 关闭阻塞项」。
 */
class ContractFreezeGateTest {

    // ======================================================================
    // 一、输入文件（相对仓库根）
    // ======================================================================

    private static final String CONTRACT_MD_REL = "_work/contract-t6-api-freeze-2026-09-19.md";
    private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";
    private static final String VIS_JSON_REL = "contract/visibility/band-visibility-matrix.json";
    private static final String CONFIG_SEED_REL =
            "skeleton/dy-config/src/main/resources/db/config/02_slots_seed.sql";
    private static final String PROTOTYPE_REL = "prototype/index.html";

    /** 冻结面口径（上游 §2 逐节清点结果）。 */
    private static final Map<String, Integer> EXPECTED_ROWS_PER_SECTION = new TreeMap<>() {{
        put("A", 3);   // 租户与权限
        put("B", 6);   // 客户与档案
        put("C", 4);   // 评估与题库
        put("D", 6);   // 服务与履约（D5 一行含 3 端点）
        put("E", 6);   // 设备与手环
        put("F", 4);   // 判定与稽核
        put("G", 5);   // 退款与挽留
        put("H", 1);   // 文书与电子签（占位）
        put("I", 8);   // 文书模板与渲染（占位）
    }};

    /** 冻结端点总数（D5 一行 3 个端点 → 43 行 45 端点）。 */
    private static final int EXPECTED_TOTAL_ROWS = 43;
    private static final int EXPECTED_TOTAL_ENDPOINTS = 45;

    private static final String EXPECTED_CONTRACT_VERSION = "api-contract-v1.0.0";

    /**
     * 四个字段组 × 五个角色的权威矩阵（上游 §3.1）。
     * {@code null} = 该端「不成立」（门店客服无端）。
     */
    private static final Map<String, Map<String, Boolean>> EXPECTED_MATRIX = new LinkedHashMap<>() {{
        put("client", Map.of(
                "raw_data", true, "capture_status", true,
                "gap_reason", false, "derived_result", false));
        put("therapist", Map.of(
                "raw_data", true, "capture_status", true,
                "gap_reason", true, "derived_result", true));
        put("meridian", Map.of(
                "raw_data", true, "capture_status", true,
                "gap_reason", true, "derived_result", true));
        put("admin", Map.of(
                "raw_data", true, "capture_status", true,
                "gap_reason", true, "derived_result", true));
    }};

    /** 门店客服 = 无端，任何字段组均不成立（上游 §3.1 第 5 行）。 */
    private static final String ENDLESS_ROLE = "store_customer_service";

    /** 四个字段组的固定顺序（上游 §3.1 表头列序，也是 OpenAPI 根级矩阵的 key 序）。 */
    private static final List<String> FIELD_GROUPS =
            List.of("raw_data", "capture_status", "gap_reason", "derived_result");

    /** 客户侧 ③④ 为硬约束（上游 §3.1 注 + config #43 注）。 */
    private static final List<String> CLIENT_HARD_DENY = List.of("gap_reason", "derived_result");

    /** 客户禁入行（契约 §2.7 G 域全部 + H1 + I8）。 */
    private static final Set<String> EXPECTED_CLIENT_FORBIDDEN_ROWS =
            Set.of("G1", "G2", "G3", "G4", "G5", "H1", "I8");

    // ======================================================================
    // 二、已登记的已知差异（登记式 Harness）
    // ======================================================================

    /**
     * 已知差异登记：上游 §2.9 域 I 错误码表把 {@code 2003} <b>复用</b>为
     * 「{@code mime_type} / {@code file_size} 越界」，而 §2.0 统一错误码表定义
     * {@code 2003 = TENANT_MISMATCH}（跨租户 / 租户头不符）。
     *
     * <p><b>这不是"我发现的笔误可以顺手改"</b> —— 错误码语义属 §1.4 的 <b>MAJOR 级</b>
     * （"错误码语义变更"），裁定权在契约 owner + 产品共签，<b>不在测试</b>。故本类
     * <b>登记</b>冲突并守护"它不被静默消除"：任一侧改动 → 立刻红。
     *
     * <p>范畴：语义冲突（同一 code 两义），非拼写。若后续裁定为"域 I 另用新码"，
     * 本表置空即代表"差异归零"（与 {@code ContractConsistencyTest.GAP_REASON_KNOWN_DIFFERENCES} 同构）。
     */
    private static final List<String> KNOWN_DIFFERENCES = List.of(
            "上游 §2.9 域 I 错误码表复用 `2003` 表示『mime_type / file_size 越界』，"
                    + "与 §2.0 统一表 `2003 = TENANT_MISMATCH` 同码两义 —— 待 owner + 产品共签裁定",
            "上游 §2.7 未逐项明示 G4 审批的角色白名单（仅给『区域督导 ✅（可见不审批）』）；"
                    + "契约中 G4 的 x-callable-roles=[admin] 系依据『可见 ≠ 可审批』推断，"
                    + "已挂 x-ruling-pending —— 待 owner + 产品共签确认");

    /** 冲突码本体：用于机械核对"冲突确实还在"。 */
    private static final int COLLIDING_CODE = 2003;

    // ======================================================================
    // 三、解析入参的一次性加载
    // ======================================================================

    private record Sources(
            Path root,
            String contractMd,
            String openapiText,
            Map<String, Object> openapi,
            String visJson,
            String configSeed,
            String prototype) {
    }

    private static Sources load() throws IOException {
        Path root = resolveRepoRoot();
        String openapiText = read(root, OPENAPI_REL);
        Object parsed = new Yaml().load(openapiText);
        assertTrue(parsed instanceof Map, "OpenAPI 顶层必须是 mapping，实为: "
                + (parsed == null ? "null" : parsed.getClass().getName()));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return new Sources(root, read(root, CONTRACT_MD_REL), openapiText, map,
                read(root, VIS_JSON_REL), read(root, CONFIG_SEED_REL), read(root, PROTOTYPE_REL));
    }

    // ======================================================================
    // 四、① 契约本体是合法 OpenAPI 3 + 版本已冻结
    // ======================================================================

    @Test
    void openapi_is_a_parsable_openapi_3_document_with_frozen_version() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        String openapiVersion = str(doc.get("openapi"));
        assertTrue(openapiVersion.startsWith("3.0"),
                "必须声明 OpenAPI 3.0.x，实为: " + openapiVersion);

        // info
        Map<String, Object> info = cast(doc.get("info"), "info");
        assertEquals(EXPECTED_CONTRACT_VERSION, str(info.get("version")),
                "契约版本号必须与冻结口径一致；改版本号即改冻结面，须走 §1.5 变更流程");

        // servers 必须锁定 /api/v1 基路径（上游 §2.0 Base Path）
        List<Object> servers = list(doc.get("servers"), "servers");
        assertTrue(servers.stream().anyMatch(x -> "/api/v1".equals(str(cast(x, "server").get("url")))),
                "servers 必须包含 /api/v1（上游 §2.0 Base Path 约定）");

        // 每个 operation 必须挂全三个自审计注解 + operationId 唯一
        Set<String> operationIds = new TreeSet<>();
        int ops = 0;
        for (Map.Entry<String, Map<String, Object>> pathEntry : operations(doc).entrySet()) {
            Map<String, Object> op = pathEntry.getValue();
            String id = str(op.get("operationId"));
            assertFalse(id.isBlank(), "operation 缺 operationId: " + pathEntry.getKey());
            assertTrue(operationIds.add(id), "operationId 重复（三端生成会撞名）: " + id);
            assertNotNull(op.get("x-contract-row"),
                    "operation 缺 x-contract-row（无法回指上游行号）: " + id);
            assertNotNull(op.get("x-callable-roles"),
                    "operation 缺 x-callable-roles（服务端 403 依据缺失）: " + id);
            assertTrue(op.containsKey("x-client-forbidden"),
                    "operation 缺 x-client-forbidden（无法判定客户小程序是否可生成调用面）: " + id);
            ops++;
        }
        assertEquals(EXPECTED_TOTAL_ENDPOINTS, ops,
                "端点数应为 " + EXPECTED_TOTAL_ENDPOINTS + "（D5 一行含 3 端点）");

        // 两个根级权威块必须存在
        assertNotNull(doc.get("x-visibility-matrix"), "缺根级 x-visibility-matrix（可见性权威矩阵）");
        assertNotNull(doc.get("x-error-codes"), "缺根级 x-error-codes（错误码表）");
        assertNotNull(doc.get("x-wording-discipline"), "缺根级 x-wording-discipline（措辞纪律 R1–R8）");
    }

    // ======================================================================
    // 四之二、跨端协议片段必须是【结构化事实】且与 prose / schema 一致（第 57 条）
    // ======================================================================

    /**
     * 跨端协议片段（鉴权头名 / 令牌前缀 / 租户头 / 追踪头 / 幂等头 / 信封字段与成功码）
     * 必须同时以三种形态存在**且互相一致**：
     * <ol>
     *   <li>{@code x-api-protocol} —— 结构化事实（供生成器机械转录为三端常量）；</li>
     *   <li>{@code x-global-conventions} 的 prose 行 —— 给人读的句子；</li>
     *   <li>{@code components.schemas.ResultEnvelope} / {@code parameters.IdempotencyKey}
     *       —— 真正的 OpenAPI 结构。</li>
     * </ol>
     *
     * <p><b>为什么这条断言非有不可（第 57 条）</b>：在它之前，鉴权头名 / 令牌前缀 /
     * 追踪头只活在 <b>prose 与三端各自手抄的字面量</b>里。{@code X-Trace-Id} 连 prose
     * 都没有 —— 它只出现在后端 {@code TraceIdFilter.HEADER} 与三端出站层，契约全域
     * <b>零声明</b>（实测 6 处字面量）。这类"隐式协议"改一处会静默分叉，且
     * tsc / vite / 全部门禁都不报（它们从不发真实请求）—— 与第 56 条 Base Path 同族。
     *
     * <p>断言方向刻意是 <b>双向</b>：结构化块不得凭空多出 prose 里没有的约定，
     * prose 也不得与结构化块矛盾。任一侧单独改动 ⇒ 立刻红，迫使两处同步。
     */
    @Test
    void cross_end_protocol_fragments_are_structured_and_agree_with_prose() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        Map<String, Object> proto = cast(doc.get("x-api-protocol"), "x-api-protocol");
        assertNotNull(proto, "缺根级 x-api-protocol（跨端协议片段的结构化权威块）");

        // --- ① 结构化块自身的必填键（缺一个 = 生成器无法转录该常量）---
        List<String> requiredKeys = List.of(
                "auth-header", "auth-scheme", "tenant-header", "trace-header",
                "idempotency-header", "envelope-fields", "envelope-ok-code");
        for (String k : requiredKeys) {
            assertNotNull(proto.get(k), "x-api-protocol 缺键 " + k + "（三端出站层无法机械取用）");
        }

        // 取值不得为空串（空串会让前端拼出 " <token>" 这种坏头）
        for (Map.Entry<String, Object> e : proto.entrySet()) {
            if (e.getValue() instanceof String) {
                assertFalse(((String) e.getValue()).isBlank(),
                        "x-api-protocol." + e.getKey() + " 不得为空串");
            }
        }

        String authHeader = str(proto.get("auth-header"));
        String authScheme = str(proto.get("auth-scheme"));
        String tenantHeader = str(proto.get("tenant-header"));
        String traceHeader = str(proto.get("trace-header"));
        String idemHeader = str(proto.get("idempotency-header"));

        // --- ② 与 prose（x-global-conventions）互查 ---
        Map<String, Object> conv = cast(doc.get("x-global-conventions"), "x-global-conventions");
        String authProse = str(conv.get("auth"));
        String tenantProse = str(conv.get("tenant-context"));
        String envelopeProse = str(conv.get("envelope"));
        String idemProse = str(conv.get("idempotency"));

        assertTrue(authProse.contains(authHeader),
                "x-global-conventions.auth 未含结构化 auth-header=" + authHeader
                        + " ⇒ prose 与结构化事实已分叉： " + authProse);
        assertTrue(authProse.contains(authScheme),
                "x-global-conventions.auth 未含结构化 auth-scheme=" + authScheme + ": " + authProse);
        assertTrue(tenantProse.contains(tenantHeader),
                "x-global-conventions.tenant-context 未含结构化 tenant-header=" + tenantHeader);
        assertTrue(idemProse.contains(idemHeader),
                "x-global-conventions.idempotency 未含结构化 idempotency-header=" + idemHeader);

        // 信封：prose 必须逐字含全部字段名 + 成功码
        @SuppressWarnings("unchecked")
        List<Object> envelopeFields = (List<Object>) proto.get("envelope-fields");
        assertNotNull(envelopeFields, "x-api-protocol.envelope-fields 必须是列表");
        for (Object f : envelopeFields) {
            assertTrue(envelopeProse.contains(str(f)),
                    "x-global-conventions.envelope 未含字段 " + f + ": " + envelopeProse);
        }
        assertTrue(envelopeProse.contains(str(proto.get("envelope-ok-code"))),
                "x-global-conventions.envelope 未含成功码 " + proto.get("envelope-ok-code"));

        // --- ③ 与真正的 OpenAPI 结构互查 ---
        Map<String, Object> components = cast(doc.get("components"), "components");
        Map<String, Object> schemas = cast(components.get("schemas"), "schemas");
        Map<String, Object> resultEnvelope = cast(schemas.get("ResultEnvelope"), "ResultEnvelope");
        Map<String, Object> envProps =
                cast(resultEnvelope.get("properties"), "ResultEnvelope.properties");

        // 🛑 判据形态（第 55 条教训 —— 本条判据首跑就复发了一次，记录在此）
        // -------------------------------------------------------------------
        // 初版断言「envelope-fields 的每一项都必须在 ResultEnvelope.required 里」，
        // 首跑即红：`required: [code, message, trace_id]` **不含 data**。
        // 但那不是契约的错 —— 契约自己写明「code != 0 时 data 为空」，
        // 故 data **本就该可选**（失败信封不带 data）。初版把本仓**自己规定的
        // 合法形态**判红 ⇒ 正是第 55 条：判据太窄 ⇒ 假红 ⇒ 判据被删。
        // ⇒ 正确的判法是分开两件事：
        //     ① 「字段存不存在」 —— envelope-fields 每一项必须在 properties 里；
        //     ② 「必填是不是信封的子集」 —— required 不得含信封之外的字段；
        //     ③ 「可选性有没有被文档化」 —— data 不在 required 时，prose 的
        //        envelope-rule 必须说明原因，否则那是【未文档化的约定】。
        for (Object f : envelopeFields) {
            assertNotNull(envProps.get(str(f)),
                    "x-api-protocol.envelope-fields 声明了 " + f
                            + "，但 ResultEnvelope.properties 里没有它 ⇒ 两处已分叉");
        }

        @SuppressWarnings("unchecked")
        List<Object> envRequired = (List<Object>) resultEnvelope.get("required");
        assertNotNull(envRequired, "ResultEnvelope 缺 required —— 信封结构不可机械校验");
        for (Object r : envRequired) {
            assertTrue(envelopeFields.contains(r),
                    "ResultEnvelope.required 含信封字段之外的键 " + r + " ⇒ 与 envelope-fields 分叉");
        }
        // 失败信封不带 data，故 data 不在 required 是**合法的**；但必须写明原因
        if (!envRequired.contains("data")) {
            String rule = str(conv.get("envelope-rule"));
            assertTrue(rule.contains("data"),
                    "data 未列入 ResultEnvelope.required（失败信封不含 data，这本身合理），"
                            + "但 x-global-conventions.envelope-rule 未说明该可选性 ⇒ 它就成了"
                            + "「未文档化的约定」: " + rule);
        }

        // --- ④ 幂等头必须与 parameters.IdempotencyKey 的真实头名一致 ---
        Map<String, Object> parameters = cast(components.get("parameters"), "parameters");
        Map<String, Object> idemParam = cast(parameters.get("IdempotencyKey"), "IdempotencyKey");
        assertEquals(idemHeader, str(idemParam.get("name")),
                "x-api-protocol.idempotency-header 与 parameters.IdempotencyKey.name 不一致 ⇒ "
                        + "前端发的头名与后端读的头名会分叉，且不会有任何测试红");
        assertEquals("header", str(idemParam.get("in")),
                "parameters.IdempotencyKey 必须声明 in=header");

        // --- ⑤ 追踪头必须被 ResultEnvelope 的 trace_id 与 prose 同时承认 ---
        //     （trace_id 在信封里，而 X-Trace-Id 是失败响应/网关侧的同义留痕头 ——
        //      两者并存，故此处只断言它非空且不与信封字段撞名）
        assertFalse(envelopeFields.contains(traceHeader),
                "trace-header 不应与信封字段同名（前者是 HTTP 头、后者是响应体键）");
    }

    // ======================================================================
    // 五、② 行集与上游 §2 完全一致（超集/子集都不行）
    // ======================================================================

    @Test
    void contract_row_set_matches_upstream_section_2_exactly() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        // --- 契约侧：从 x-contract-row 抠出行号 ---
        Set<String> openapiRows = contractRows(doc);
        Map<String, Integer> openapiBySection = countBySection(openapiRows);

        // --- 上游侧：解析 §2.1~§2.9 各表首列的 `A1` 形式行号 ---
        Set<String> upstreamRows = upstreamContractRows(s.contractMd());
        Map<String, Integer> upstreamBySection = countBySection(upstreamRows);

        assertEquals(new TreeSet<>(upstreamRows), new TreeSet<>(openapiRows),
                "契约行集必须与上游 §2 行集【完全相同】—— 少一行=漏冻结，"
                        + "多一行=凭空发明接口。差异见下方分节数字。");

        assertEquals(EXPECTED_ROWS_PER_SECTION, new TreeMap<>(upstreamBySection),
                "上游 §2 分节行数偏离冻结口径（A=3 B=6 C=4 D=6 E=6 F=4 G=5 H=1 I=8）");
        assertEquals(EXPECTED_ROWS_PER_SECTION, new TreeMap<>(openapiBySection),
                "契约分节行数偏离冻结口径");

        assertEquals(EXPECTED_TOTAL_ROWS, openapiRows.size(), "总行数应为 " + EXPECTED_TOTAL_ROWS);

        // --- 上游侧方法+路径 ↔ 契约侧方法+路径 逐条一致 ---
        // 上游 D5 用 `...` 省略基路径、I7 带 query string、B2 说明列有语义别称 ——
        // 故比较前统一归一化（去 base path / 去 `...` / 去 query），并逐端点比对。
        Map<String, Set<String>> upstreamOps = upstreamRowOperations(s.contractMd());
        Map<String, Set<String>> openapiOps = openapiRowOperations(doc);
        for (String row : new TreeSet<>(upstreamRows)) {
            Set<String> up = new TreeSet<>();
            for (String o : upstreamOps.getOrDefault(row, Set.of())) {
                up.add(normalizeEndpoint(o));
            }
            Set<String> ov = new TreeSet<>();
            for (String o : openapiOps.getOrDefault(row, Set.of())) {
                ov.add(normalizeEndpoint(o));
            }
            assertFalse(up.isEmpty(), "上游行 " + row + " 没解析出任何「方法 路径」");
            assertTrue(ov.containsAll(up),
                    "契约行 " + row + " 缺上游声明的端点。上游(归一后)=" + up + " 契约(归一后)=" + ov);
            if (up.size() > 1) {
                // 多端点行（D5 = 3 端点）：契约必须给出同样多的端点，不能合并成 1 个
                assertEquals(up.size(), ov.size(),
                        "行 " + row + " 上游声明 " + up.size() + " 个端点，契约只给了 "
                                + ov.size() + " 个 —— 一行多端点被合并会丢接口");
            }
        }
        // 语义别称必须被显式登记且【不在】端点集里（B2 的 `POST /archive`）
        assertTrue(SEMANTIC_ALIASES.containsKey("B2"),
                "B2 的语义别称（`POST /archive`）未被登记 —— 它必须被显式排除而非静默丢弃，"
                        + "否则日后有人误把它当真实端点");
        assertFalse(upstreamOps.getOrDefault("B2", Set.of()).stream()
                        .anyMatch(x -> normalizeEndpoint(x).equals("POST /archive")),
                "语义别称 `POST /archive` 混进了 B2 的真实端点集");
    }

    // ======================================================================
    // 六、③ 可见性矩阵 —— 五源逐格一致（S1-1 验收第 ③ 条）
    // ======================================================================

    @Test
    void visibility_matrix_is_cell_by_cell_identical_across_five_sources() throws IOException {
        Sources s = load();

        // 源 1：上游 §3.1 Markdown 表格（真解析表格行，不是抄常量）
        Map<String, Map<String, Boolean>> fromUpstream = matrixFromUpstreamMarkdown(s.contractMd());

        // 源 2：OpenAPI 根级 x-visibility-matrix
        Map<String, Map<String, Boolean>> fromOpenapi = matrixFromOpenapi(s.openapi());

        // 源 3：派生 JSON 导出
        Map<String, Map<String, Boolean>> fromJson = matrixFromDerivedJson(s.visJson());

        // 源 4：config #43 实际取值
        Map<String, Map<String, Boolean>> fromConfig = matrixFromConfigSlot43(s.configSeed());

        // 源 5：原型 D1 四端矩阵
        Map<String, Map<String, Boolean>> fromPrototype = matrixFromPrototype(s.prototype());

        assertMatrixEquals(EXPECTED_MATRIX, fromUpstream, "上游 §3.1");
        assertMatrixEquals(EXPECTED_MATRIX, fromOpenapi, "OpenAPI x-visibility-matrix");
        assertMatrixEquals(EXPECTED_MATRIX, fromJson, "contract/visibility 派生导出");
        assertMatrixEquals(EXPECTED_MATRIX, fromConfig, "config #43 cfg:band.visibility");
        assertMatrixEquals(EXPECTED_MATRIX, fromPrototype, "原型 D1 四端矩阵");

        // 门店客服：五源都必须是"无端"（不是 false，而是该行不成立）
        Map<String, Map<String, Map<String, Boolean>>> rolelessSources = new LinkedHashMap<>();
        rolelessSources.put("上游 §3.1", fromUpstream);
        rolelessSources.put("OpenAPI", fromOpenapi);
        rolelessSources.put("派生导出", fromJson);
        for (Map.Entry<String, Map<String, Map<String, Boolean>>> e : rolelessSources.entrySet()) {
            assertFalse(e.getValue().containsKey(ENDLESS_ROLE),
                    e.getKey() + " 不应为「门店客服」给出字段组档位 —— 它是「无端」，"
                            + "该界面不成立（上游 §3.1 第 5 行）");
        }
        assertTrue(s.visJson().contains("\"_endless\": true"),
                "派生导出必须显式标出门店客服 _endless —— 「不成立」不等于「全部 false」");
    }

    @Test
    void client_side_field_groups_3_and_4_are_a_hard_boundary_in_every_source() throws IOException {
        Sources s = load();

        // 五源（每个源单独命名变量，避免 List.of 泛型推断塌成嵌套 Map）
        Map<String, Map<String, Boolean>> fromUpstream = matrixFromUpstreamMarkdown(s.contractMd());
        Map<String, Map<String, Boolean>> fromOpenapi = matrixFromOpenapi(s.openapi());
        Map<String, Map<String, Boolean>> fromJson = matrixFromDerivedJson(s.visJson());
        Map<String, Map<String, Boolean>> fromConfig = matrixFromConfigSlot43(s.configSeed());
        Map<String, Map<String, Boolean>> fromPrototype = matrixFromPrototype(s.prototype());

        Map<String, Map<String, Map<String, Boolean>>> sources = new LinkedHashMap<>();
        sources.put("上游 §3.1", fromUpstream);
        sources.put("OpenAPI", fromOpenapi);
        sources.put("派生导出", fromJson);
        sources.put("config #43", fromConfig);
        sources.put("原型 D1", fromPrototype);

        for (Map.Entry<String, Map<String, Map<String, Boolean>>> entry : sources.entrySet()) {
            Map<String, Boolean> client = entry.getValue().get("client");
            assertNotNull(client, entry.getKey() + " 缺 client 行");
            for (String g : CLIENT_HARD_DENY) {
                assertEquals(Boolean.FALSE, client.get(g),
                        entry.getKey() + " 把客户侧 " + g + " 放开了 —— 这是硬边界，"
                                + "放开属「变更业务裁定」，不得按体验优化处理");
            }
        }
        // 派生导出还必须显式声明"不得通过配置放开"
        assertTrue(s.visJson().contains("不得通过配置放开"),
                "派生导出必须携带客户侧 ③④ 的硬边界声明（防止端侧实现方误以为可配置）");
    }

    // ======================================================================
    // 七、④ S1-7 探测接口并入冻结（消除环路）
    // ======================================================================

    @Test
    void s1_7_probe_interface_is_merged_into_the_freeze() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        // E5 探测接口必须在契约里，且报出上游 §4.2 的 N（运行时可探测）
        Map<String, Object> e5 = findOperationByRow(doc, "E5");
        assertNotNull(e5, "S1-7 的探测接口（E5）未并入冻结 —— 会造成"
                + "「契约要含它、它却等契约」的环路");
        assertEquals("/band/available-dates", pathOfRow(doc, "E5"),
                "E5 路径必须与上游 §2.5 一致");

        // E5 的诚实边界：retention_window_days 必须允许 TBD 字面（不得填数）
        String yaml = s.openapiText();
        assertTrue(yaml.contains("retention_window_days"),
                "E5 响应必须携带 retention_window_days（留存窗口 N）");
        assertTrue(yaml.contains("x-tbd-allowed"),
                "契约缺 x-tbd-allowed 标记 —— 诚实边界（TBD 不得填数）不可丢");

        // E6 客户端同步状态卡也随 S1-7 一并冻结（四态）
        assertNotNull(findOperationByRow(doc, "E6"),
                "E6 客户端同步状态卡未并入冻结（S1-7 契约之一）");

        // 客户端同步态 4 值与内部 gap_reason 7 值必须是【两套独立枚举】（C-3）
        assertTrue(yaml.contains("client_sync_state"),
                "契约必须声明客户端可见同步态枚举（与内部 gap_reason 隔离）");
        Set<String> clientSyncState = new TreeSet<>();
        for (String v : List.of("syncing", "synced", "sync_failed", "no_data_today")) {
            if (yaml.contains(v)) {
                clientSyncState.add(v);
            }
        }
        assertEquals(4, clientSyncState.size(),
                "客户端同步态四值必须齐备且不得含「未佩戴」类行为性取值 —— 实得: " + clientSyncState);
        assertFalse(yaml.contains("not_worn\": true") || yaml.contains("not_worn,") && !yaml.contains("内部"),
                "内部 gap_reason 的行为性取值不得出现在客户端可见面定义中");
    }

    // ======================================================================
    // 八、客户端禁入面（X-1 / X-2 的契约侧）
    // ======================================================================

    @Test
    void client_forbidden_rows_are_exactly_the_refund_and_esign_and_render_rows() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        Set<String> actual = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> e : operations(doc).entrySet()) {
            if (Boolean.TRUE.equals(e.getValue().get("x-client-forbidden"))) {
                actual.add(str(e.getValue().get("x-contract-row")));
            }
        }
        assertEquals(new TreeSet<>(EXPECTED_CLIENT_FORBIDDEN_ROWS), actual,
                "客户禁入行必须恰为退款域全部（G1–G5）+ 电子签回调（H1）+ 文书渲染（I8）—— "
                        + "多一行=客户面被误伤，少一行=客户能调退款，违反 X-2");

        // 退款域：客户端【与调理师】一律 403（上游 §2.7 主张）
        // ⚠️ G4（审批）的角色白名单【上游未逐项明示】，契约中属推断项（已登记 x-ruling-pending），
        //    故此处只守护「客户 / 调理师 403」这条【上游明示】的硬约束，不对审批白名单下断言 ——
        //    「可见 ≠ 可审批」是上游语义，但由此推出的具体白名单属待裁定，测试无权替它定档。
        for (String row : List.of("G1", "G2", "G3", "G4", "G5")) {
            Map<String, Object> op = findOperationByRow(doc, row);
            assertNotNull(op, "缺行 " + row);
            List<Object> roles = list(op.get("x-callable-roles"), row + ".x-callable-roles");
            List<String> roleNames = roles.stream().map(ContractFreezeGateTest::str).toList();
            assertFalse(roleNames.contains("client"),
                    row + " 的可调用角色含 client —— 退款域客户端必须 403 VISIBILITY_DENIED");
            assertFalse(roleNames.contains("therapist"),
                    row + " 的可调用角色含 therapist —— 上游 §2.7 明确调理师端亦 403");
        }
        // G1/G2/G3/G5 对经络师开放（上游 §2.7「G 域全部：经络师 ✅」）
        for (String row : List.of("G1", "G2", "G3", "G5")) {
            List<String> roleNames = list(findOperationByRow(doc, row).get("x-callable-roles"), row)
                    .stream().map(ContractFreezeGateTest::str).toList();
            assertTrue(roleNames.contains("meridian"),
                    row + " 缺 meridian —— 上游 §2.7 明确 G 域对经络师开放");
        }
        // G4 的推断项必须显式登记（不得静默定档）
        Map<String, Object> g4 = findOperationByRow(doc, "G4");
        assertNotNull(g4.get("x-ruling-pending"),
                "G4 审批角色属推断项（上游未逐项明示），契约必须挂 x-ruling-pending 标注 —— "
                        + "不得把推断写成上游结论");
        assertTrue(KNOWN_DIFFERENCES.stream().anyMatch(d -> d.contains("G4")),
                "G4 审批白名单推断项必须同时登记进 KNOWN_DIFFERENCES");
    }

    // ======================================================================
    // 九、措辞纪律（硬纪律：客户可见面不得出现"退款""诊断"）
    // ======================================================================

    @Test
    void client_facing_schemas_never_contain_forbidden_terms() throws IOException {
        Sources s = load();
        Map<String, Object> doc = s.openapi();

        Map<String, Object> components = cast(doc.get("components"), "components");
        Map<String, Object> schemas = cast(components.get("schemas"), "components.schemas");

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Object> e : schemas.entrySet()) {
            Map<String, Object> schema = cast(e.getValue(), e.getKey());
            Map<String, Object> props = optCast(schema.get("properties"));
            if (props == null) {
                continue;
            }
            for (Map.Entry<String, Object> p : props.entrySet()) {
                List<String> visibleTo = optStrList(cast(p.getValue(), p.getKey()).get("x-visible-to"));
                if (visibleTo == null || !visibleTo.contains("client")) {
                    continue;
                }
                String fieldName = p.getKey();
                if (fieldName.contains("refund") || fieldName.contains("退款")) {
                    violations.add(e.getKey() + "." + fieldName + "（客户可见且含退款语义）");
                }
                if (fieldName.contains("diagnos") || fieldName.contains("诊断")) {
                    violations.add(e.getKey() + "." + fieldName + "（客户可见且含诊断语义）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "客户可见字段名不得含「退款」「诊断」（上游 §5.1 R1/R2 + X-2）—— 命中: " + violations);

        // 契约必须自带词表（供端侧构建期 grep 复用）
        String yaml = s.openapiText();
        assertTrue(yaml.contains("client-forbidden-terms"),
                "契约必须内置 client-forbidden-terms 词表（X-2 构建期扫描的唯一来源）");
        for (String term : List.of("退款", "诊断", "未佩戴", "达标", "不达标")) {
            assertTrue(yaml.contains(term),
                    "client-forbidden-terms 缺词 " + term + " —— 词表残缺会让构建期扫描失效");
        }
    }

    // ======================================================================
    // 十、错误码：契约 ↔ 上游 §2.0 一致；已知冲突显式登记
    // ======================================================================

    @Test
    void error_codes_match_upstream_and_the_2003_collision_stays_registered() throws IOException {
        Sources s = load();

        Map<Integer, String> fromUpstream = upstreamErrorCodes(s.contractMd());
        Map<Integer, String> fromOpenapi = openapiErrorCodes(s.openapi());

        // 上游 §2.0 的 11 个码必须逐一在契约中同名
        for (Map.Entry<Integer, String> e : fromUpstream.entrySet()) {
            String contractName = fromOpenapi.get(e.getKey());
            assertNotNull(contractName,
                    "上游 §2.0 定义了 code " + e.getKey() + "（" + e.getValue() + "），契约缺失");
            assertEquals(e.getValue(), contractName,
                    "code " + e.getKey() + " 的名称与上游不一致");
        }

        // 域 I 新增的 2004 必须在契约中（契约 §2.9 新增）
        assertEquals("PLACEHOLDER_OUT_OF_SCOPE", fromOpenapi.get(2004),
                "域 I 新增错误码 2004 PLACEHOLDER_OUT_OF_SCOPE 未入契约");
        assertTrue(fromOpenapi.size() >= fromUpstream.size() + 1,
                "契约错误码数应 ≥ 上游 §2.0 数 + 1（域 I 新增）");

        // ---- 已知差异登记：2003 同码两义 ----
        assertFalse(KNOWN_DIFFERENCES.isEmpty(),
                "登记表为空表示「差异已归零」。若 2003 冲突已裁定，请更新登记表并在阻塞登记册关闭对应项；"
                        + "若尚未裁定，不得清空。");
        assertTrue(KNOWN_DIFFERENCES.stream().anyMatch(d -> d.contains(String.valueOf(COLLIDING_CODE))),
                "登记表必须点名冲突码 " + COLLIDING_CODE);

        // 机械核对：冲突确实"还在"（上游域 I 仍复用 2003），且契约按 §2.0 权威取值
        String sectionI = between(s.contractMd(), "### 2.9 ", "## 3. ");
        assertTrue(sectionI.contains("`" + COLLIDING_CODE + "`"),
                "上游 §2.9 已不再复用 " + COLLIDING_CODE + " —— 冲突消失了，"
                        + "请更新登记表为「差异归零」并关闭阻塞项（不得静默清空登记）");
        assertEquals("TENANT_MISMATCH", fromUpstream.get(COLLIDING_CODE),
                "§2.0 统一表的 " + COLLIDING_CODE + " 语义被改了 —— 错误码语义变更属 MAJOR 级");
        assertEquals("TENANT_MISMATCH", fromOpenapi.get(COLLIDING_CODE),
                "契约须按 §2.0 统一表取值（§2.9 的复用属登记冲突，不得覆盖统一表语义）");
    }

    // ======================================================================
    // 十一、反向验证 —— 单源扰动必须让闸门变红（硬纪律 #7 · 禁自证式测试）
    // ======================================================================

    @Test
    void gate_turns_red_when_any_single_source_is_perturbed(@TempDir Path tmp) throws IOException {
        Sources real = load();

        // ---- 扰动 ①：契约少一行（删掉 E6 的 x-contract-row）----
        String dropRow = real.openapiText().replace(
                "      x-contract-row: E6", "      x-contract-row: E6XX");
        Map<String, Object> dropped = parseMap(dropRow);
        assertNotEquals(new TreeSet<>(contractRows(real.openapi())),
                new TreeSet<>(contractRows(dropped)),
                "反向验证失败：契约少一行，行集却没变 —— 闸门是摆设");
        assertFalse(contractRows(dropped).contains("E6"),
                "反向验证失败：E6 未被真正移除");

        // ---- 扰动 ②：契约矩阵翻转一格（x-visibility-matrix 里 client.gap_reason 变 true）----
        // 精确做法：先定位 x-visibility-matrix 区块，再在其内的 gap_reason 子块里替换
        // 第一处 `client: false` —— 不猜缩进、不依赖行尾符（文件是 LF，原型是 CRLF）。
        String realYaml = real.openapiText();
        int matrixAt = realYaml.indexOf("x-visibility-matrix:");
        assertTrue(matrixAt >= 0, "反向验证前置失败：契约里找不到 x-visibility-matrix 区块");
        int matrixEnd = realYaml.indexOf("\nx-roles:", matrixAt);
        assertTrue(matrixEnd > matrixAt, "反向验证前置失败：找不到 x-visibility-matrix 区块终点");
        String matrixBlock = realYaml.substring(matrixAt, matrixEnd);
        int gapAt = matrixBlock.indexOf("gap_reason:");
        assertTrue(gapAt >= 0, "反向验证前置失败：x-visibility-matrix 内找不到 gap_reason 字段组");
        // gap_reason 子块 = 从 gap_reason: 到 derived_result: 之前（保持缩进原样）
        int derivedAt = matrixBlock.indexOf("derived_result:", gapAt);
        if (derivedAt < 0) {
            derivedAt = matrixBlock.length();
        }
        String gapBlock = matrixBlock.substring(gapAt, derivedAt);
        assertTrue(gapBlock.contains("client: false"),
                "反向验证前置失败：gap_reason 子块里找不到 `client: false` —— "
                        + "说明矩阵未真正把客户 ③ 定为不可见。子块原文=" + gapBlock);
        final String flipMatrix = realYaml.substring(0, matrixAt) + matrixBlock.substring(0, gapAt)
                + gapBlock.replaceFirst("client: false", "client: true")
                + matrixBlock.substring(derivedAt) + realYaml.substring(matrixEnd);
        assertNotEquals(realYaml, flipMatrix,
                "反向验证失败：契约矩阵未被真正翻转（替换未生效）");
        assertThrows(AssertionError.class,
                () -> assertMatrixEquals(EXPECTED_MATRIX, matrixFromOpenapi(parseMap(flipMatrix)),
                        "扰动矩阵"),
                "反向验证失败：契约矩阵被翻转，闸门却没红 —— 逐格比对是假的");

        // ---- 扰动 ③：上游少一行（删掉 §2.6 的 F4 行）----
        String upstreamDrop = real.contractMd().replace("| F4 |", "| F4X |");
        assertNotEquals(new TreeSet<>(upstreamContractRows(real.contractMd())),
                new TreeSet<>(upstreamContractRows(upstreamDrop)),
                "反向验证失败：上游少一行，行集却没变");
        assertThrows(AssertionError.class,
                () -> assertEquals(EXPECTED_ROWS_PER_SECTION,
                        new TreeMap<>(countBySection(upstreamContractRows(upstreamDrop))),
                        "扰动上游"),
                "反向验证失败：上游少一行，分节计数却没红");

        // ---- 扰动 ④：config #43 翻转（customer.gap_reason false → true）----
        String configFlip = real.configSeed().replace(
                "\"customer\":{\"raw_data\":true,\"capture_status\":true,\"gap_reason\":false",
                "\"customer\":{\"raw_data\":true,\"capture_status\":true,\"gap_reason\":true");
        assertThrows(AssertionError.class,
                () -> assertMatrixEquals(EXPECTED_MATRIX, matrixFromConfigSlot43(configFlip),
                        "扰动 config"),
                "反向验证失败：config #43 把客户 ③ 放开了，闸门却没红 —— 硬边界形同虚设");

        // ---- 扰动 ⑤：原型 D1 翻转（客户端行的第 3 格 不可见 → 可见）----
        // 原型文件是 CRLF，Java 源码里的字面串是 LF —— 直接 replace 会静默不命中。
        // 故按【结构定位】而非整行字面匹配：先取客户端那一个 <tr>，再只改它的第 3 个 <td>。
        String protoSrc = real.prototype();
        int clientRowAt = protoSrc.indexOf("<tr><td>客户端（小程序）</td>");
        assertTrue(clientRowAt >= 0, "反向验证前置失败：原型 D1 找不到客户端行");
        int clientRowEnd = protoSrc.indexOf("</tr>", clientRowAt);
        assertTrue(clientRowEnd > clientRowAt, "反向验证前置失败：客户端行没有闭合 </tr>");
        String clientRow = protoSrc.substring(clientRowAt, clientRowEnd);
        // 该行的 <td> 序：1=角色标签，2=① 原始数据，3=② 采集状态，4=③ 缺口原因，5=④ 派生结果。
        // 本扰动要把「③ 缺口原因」从不可见翻成可见 → 目标是第 4 个 <td>。
        int targetIdx = 1 + FIELD_GROUPS.indexOf("gap_reason") + 1;
        int thirdTdAt = indexOfNthTd(clientRow, targetIdx);
        int thirdTdOpenEnd = clientRow.indexOf('>', thirdTdAt);
        int thirdTdEnd = clientRow.indexOf("</td>", thirdTdAt);
        assertTrue(thirdTdAt >= 0 && thirdTdOpenEnd > thirdTdAt && thirdTdEnd > thirdTdOpenEnd,
                "反向验证前置失败：客户端行找不到第 " + targetIdx + " 个 <td>。原行=" + clientRow);
        String thirdTdContent = clientRow.substring(thirdTdOpenEnd + 1, thirdTdEnd);
        assertEquals("${chip('bad','不可见')}", thirdTdContent,
                "反向验证前置失败：客户端「③ 缺口原因」格应为「不可见」—— 矩阵已变，请复核上游 §3.1");
        String protoFlip = protoSrc.substring(0, clientRowAt)
                + clientRow.substring(0, thirdTdOpenEnd + 1)
                + "${chip('ok','可见')}"
                + clientRow.substring(thirdTdEnd)
                + protoSrc.substring(clientRowEnd);
        assertNotEquals(protoSrc, protoFlip,
                "反向验证失败：原型 D1 客户端行未被真正改动");
        assertThrows(AssertionError.class,
                () -> assertMatrixEquals(EXPECTED_MATRIX, matrixFromPrototype(protoFlip),
                        "扰动原型"),
                "反向验证失败：原型 D1 被翻转，闸门却没红 —— 「与原型逐格一致」是空话");

        // ---- 扰动 ⑥：客户禁入行被放行（G2 的 x-client-forbidden true → false）----
        // ⚠️ 必须【替换已存在的那一行】，不能在旁边再插一行 —— YAML 重复键会被 snakeyaml
        //    取后者，插一行等于没改（这正是"扰动必须自证生效"要防的假动作）。
        String realYamlForForbid = real.openapiText();
        int g2At = realYamlForForbid.indexOf("x-contract-row: G2");
        assertTrue(g2At >= 0, "反向验证前置失败：契约里找不到 G2");
        int g2End = realYamlForForbid.indexOf("x-contract-row: G3", g2At);
        assertTrue(g2End > g2At, "反向验证前置失败：找不到 G3（G2 区块终点）");
        String g2Block = realYamlForForbid.substring(g2At, g2End);
        assertTrue(g2Block.contains("x-client-forbidden: true"),
                "反向验证前置失败：G2 区块里没有 `x-client-forbidden: true`。区块原文=" + g2Block);
        String forbidFlip = realYamlForForbid.substring(0, g2At)
                + g2Block.replaceFirst("x-client-forbidden: true", "x-client-forbidden: false")
                + realYamlForForbid.substring(g2End);
        assertNotEquals(real.openapiText(), forbidFlip,
                "反向验证失败：G2 禁入标记未被真正改动");
        Map<String, Object> forbidDoc = parseMap(forbidFlip);
        Set<String> actualForbidden = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> e : operations(forbidDoc).entrySet()) {
            if (Boolean.TRUE.equals(e.getValue().get("x-client-forbidden"))) {
                actualForbidden.add(str(e.getValue().get("x-contract-row")));
            }
        }
        assertNotEquals(new TreeSet<>(EXPECTED_CLIENT_FORBIDDEN_ROWS), actualForbidden,
                "反向验证失败：G2 禁入被放行，禁入集却没变 —— 客户面失守却测不出来");

        // ---- 扰动 ⑦：上游 2003 冲突被"顺手改掉" → 登记表必须红 ----
        String conflictGone = real.contractMd().replace("| `2003` | `mime_type` / `file_size` 越界 |",
                "| `2004` | `mime_type` / `file_size` 越界 |");
        assertNotEquals(real.contractMd(), conflictGone,
                "反向验证失败：上游 §2.9 冲突行未被真正改动（替换串与实际 Markdown 不符）");
        String sectionITouched = between(conflictGone, "### 2.9 ", "## 3. ");
        assertFalse(sectionITouched.contains("`" + COLLIDING_CODE + "`"),
                "反向验证失败：冲突被消除后仍检出 " + COLLIDING_CODE + " —— 登记表不会红");
    }

    @Test
    void parser_must_fail_loudly_on_malformed_contract() {
        // snakeyaml 解析非法 YAML 必须抛异常，绝不能返回 null / 空 map 静默通过
        assertThrows(RuntimeException.class, () -> new Yaml().load("paths:\n  - {\n"),
                "畸形 YAML 必须抛异常 —— 解析不出来必须红");

        // 反向：合法 YAML 正常解析
        Object ok = new Yaml().load("paths: {}");
        assertTrue(ok instanceof Map, "合法 YAML 应正常解析为 Map");
    }

    // ======================================================================
    // 十二、矩阵解析器（每个源一个，独立可测）
    // ======================================================================

    /** 上游 §3.1 Markdown 表：{@code | **客户端（小程序 · 客户）** | ✅ 可见 | ✅ 可见 | ❌ **不可见** | ❌ **不可见** |}。 */
    private static Map<String, Map<String, Boolean>> matrixFromUpstreamMarkdown(String md) {
        String section = between(md, "### 3.1 ", "### 3.2 ");
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        Pattern row = Pattern.compile("(?m)^\\|\\s*\\*\\*(.+?)\\*\\*\\s*\\|([^\\n]*)\\|\\s*$");
        Matcher m = row.matcher(section);
        List<String> groupOrder = List.of("raw_data", "capture_status", "gap_reason", "derived_result");
        while (m.find()) {
            String label = m.group(1);
            String role = roleFromUpstreamLabel(label);
            if (role == null) {
                continue;
            }
            String rest = m.group(2);
            if (rest.contains("无端") || rest.contains("该界面不成立")
                    || rest.contains("colspan")) {
                continue;   // 门店客服：无端，不进矩阵
            }
            List<String> cells = new ArrayList<>();
            for (String c : rest.split("\\|")) {
                if (!c.isBlank()) {
                    cells.add(c);
                }
            }
            assertEquals(groupOrder.size(), cells.size(),
                    "上游 §3.1 " + label + " 行的字段组列数应为 4，实为 " + cells.size()
                            + " —— 矩阵形状变了，解析器必须红而不是硬塞");
            Map<String, Boolean> byGroup = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String cell = cells.get(i);
                boolean visible;
                if (cell.contains("❌")) {
                    visible = false;
                } else if (cell.contains("✅")) {
                    visible = true;
                } else {
                    throw new AssertionError("上游 §3.1 " + label + " 第 " + (i + 1)
                            + " 格既无 ✅ 也无 ❌ —— 解析不出来必须抛错，不得猜测: " + cell);
                }
                byGroup.put(groupOrder.get(i), visible);
            }
            out.put(role, byGroup);
        }
        assertFalse(out.isEmpty(), "上游 §3.1 一行角色都没解析出来");
        assertEquals(4, out.size(), "上游 §3.1 应解析出 4 个有端角色，实为 " + out.keySet());
        return out;
    }

    private static String roleFromUpstreamLabel(String label) {
        if (label.contains("客户端") || label.contains("客户（小程序")) {
            return "client";
        }
        if (label.contains("调理师")) {
            return "therapist";
        }
        if (label.contains("经络师")) {
            return "meridian";
        }
        if (label.contains("管理端") || label.contains("管理员")) {
            return "admin";
        }
        if (label.contains("门店客服")) {
            return null;   // 无端
        }
        throw new AssertionError("上游 §3.1 出现无法归类的角色标签: " + label
                + " —— 新角色必须显式登记到解析器，否则矩阵会静默漏格");
    }

    /** OpenAPI 根级 {@code x-visibility-matrix}：<b>字段组 → 角色 → 布尔</b>（不是角色 → 字段组）。 */
    private static Map<String, Map<String, Boolean>> matrixFromOpenapi(Map<String, Object> doc) {
        Map<String, Object> vm = cast(doc.get("x-visibility-matrix"), "x-visibility-matrix");
        // 先转置成「角色 → 字段组」，与上游 §3.1 的形状对齐，才能逐格比对
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        for (String role : List.of("client", "therapist", "meridian", "admin")) {
            out.put(role, new LinkedHashMap<>());
        }
        int groupsSeen = 0;
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            if (!FIELD_GROUPS.contains(e.getKey())) {
                continue;   // notes 等旁注
            }
            groupsSeen++;
            Map<String, Object> byRole = cast(e.getValue(), "x-visibility-matrix." + e.getKey());
            for (String role : out.keySet()) {
                Object v = byRole.get(role);
                assertNotNull(v, "x-visibility-matrix." + e.getKey() + " 缺角色 " + role
                        + " —— 矩阵缺格不得静默跳过");
                assertTrue(v instanceof Boolean,
                        "x-visibility-matrix." + e.getKey() + "." + role + " 必须是布尔，实为 " + v);
                out.get(role).put(e.getKey(), (Boolean) v);
            }
            // 「门店客服」必须是 false 占位 + notes 里声明「无端」，二者缺一即红
            Object endless = byRole.get(ENDLESS_ROLE);
            assertTrue(endless instanceof Boolean,
                    "x-visibility-matrix." + e.getKey() + " 缺 " + ENDLESS_ROLE + " 占位");
        }
        assertEquals(FIELD_GROUPS.size(), groupsSeen,
                "x-visibility-matrix 的字段组数应为 " + FIELD_GROUPS.size() + "，实为 " + groupsSeen);
        return out;
    }

    /** 派生导出 JSON（手写解析，避免为测试引入 Jackson 依赖到 dy-app 测试域）。 */
    private static Map<String, Map<String, Boolean>> matrixFromDerivedJson(String json) {
        String block = between(json, "\"matrix\": {", "\"enforcement\": {");
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        Pattern role = Pattern.compile("\"(client|therapist|meridian|admin)\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher m = role.matcher(block);
        while (m.find()) {
            String roleName = m.group(1);
            String body = m.group(2);
            Map<String, Boolean> byGroup = new LinkedHashMap<>();
            for (String g : List.of("raw_data", "capture_status", "gap_reason", "derived_result")) {
                Matcher gv = Pattern.compile("\"" + g + "\"\\s*:\\s*(true|false)").matcher(body);
                assertTrue(gv.find(), "派生导出 " + roleName + " 缺字段组 " + g);
                byGroup.put(g, Boolean.parseBoolean(gv.group(1)));
            }
            out.put(roleName, byGroup);
        }
        assertFalse(out.isEmpty(), "派生导出一行角色都没解析出来");
        return out;
    }

    /** config #43 {@code cfg:band.visibility} 的 JSON 取值。 */
    private static Map<String, Map<String, Boolean>> matrixFromConfigSlot43(String seedSql) {
        Matcher m = Pattern.compile(
                "'cfg:band\\.visibility'[^\\n]*\\n\\s*'(\\{.*?\\})',", Pattern.DOTALL)
                .matcher(seedSql);
        assertTrue(m.find(), "config 种子文件里找不到 #43 cfg:band.visibility 的 JSON 取值"
                + " —— 解析不出来必须红");
        String json = m.group(1);

        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        Map<String, String> roleKeys = new LinkedHashMap<>() {{
            put("customer", "client");
            put("therapist", "therapist");
            put("meridian_therapist", "meridian");
            put("admin", "admin");
        }};
        for (Map.Entry<String, String> e : roleKeys.entrySet()) {
            Matcher rm = Pattern.compile("\"" + e.getKey() + "\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL)
                    .matcher(json);
            assertTrue(rm.find(), "config #43 缺角色 " + e.getKey() + " —— 矩阵不完整");
            String body = rm.group(1);
            Map<String, Boolean> byGroup = new LinkedHashMap<>();
            for (String g : List.of("raw_data", "capture_status", "gap_reason", "derived_result")) {
                Matcher gv = Pattern.compile("\"" + g + "\"\\s*:\\s*(true|false)").matcher(body);
                assertTrue(gv.find(), "config #43 " + e.getKey() + " 缺字段组 " + g);
                byGroup.put(g, Boolean.parseBoolean(gv.group(1)));
            }
            out.put(e.getValue(), byGroup);
        }
        // 门店客服必须是「无端」而非全 false
        assertTrue(json.contains("\"store_customer_service\":{\"endless\":true}"),
                "config #43 必须把门店客服标为 endless（无端）—— 「不成立」不等于「全 false」，"
                        + "否则实现方会误给该角色建账号");
        assertTrue(json.contains("\"unconfigured_means_deny\":true"),
                "config #43 必须声明 fail-closed（未配置即拒绝）");
        return out;
    }

    /** 原型 D1 四端矩阵 HTML 表。 */
    private static Map<String, Map<String, Boolean>> matrixFromPrototype(String html) {
        String section = between(html, "四端可见性矩阵（手环数据 × 字段组）", "</tbody>");
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        Pattern row = Pattern.compile("<tr><td>([^<]+)</td>(.*?)</tr>", Pattern.DOTALL);
        Matcher m = row.matcher(section);
        List<String> groupOrder = List.of("raw_data", "capture_status", "gap_reason", "derived_result");
        while (m.find()) {
            String label = m.group(1);
            String rest = m.group(2);
            String role = roleFromPrototypeLabel(label);
            if (role == null) {
                continue;
            }
            List<String> tds = new ArrayList<>();
            Matcher td = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(rest);
            while (td.find()) {
                tds.add(td.group(1));
            }
            assertEquals(groupOrder.size(), tds.size(),
                    "原型 D1 " + label + " 行列数应为 4，实为 " + tds.size());
            Map<String, Boolean> byGroup = new LinkedHashMap<>();
            for (int i = 0; i < tds.size(); i++) {
                String cell = tds.get(i);
                // ⚠️ 判序很关键：中文「不可见」含子串「可见」，若并列判定会两真 → 误判。
                // 故先判否定面，否定面成立即返回 false；否则才看肯定面。
                boolean denied = cell.contains("chip('bad'") || cell.contains("不可见");
                boolean visible = cell.contains("chip('ok'") || cell.contains("可见");
                if (!denied && !visible) {
                    throw new AssertionError("原型 D1 " + label + " 第 " + (i + 1)
                            + " 格既无「可见」也无「不可见」—— 解析不出来必须抛错，不得猜测: " + cell);
                }
                if (denied && visible && !cell.contains("chip(")) {
                    throw new AssertionError("原型 D1 " + label + " 第 " + (i + 1)
                            + " 格文案含歧义（同时出现「可见」与「不可见」且无 chip 标记，无法判定）: " + cell);
                }
                byGroup.put(groupOrder.get(i), !denied);
            }
            out.put(role, byGroup);
        }
        assertFalse(out.isEmpty(), "原型 D1 一行都没解析出来 —— 解析器失效");
        assertEquals(4, out.size(), "原型 D1 应解析出 4 个有端角色，实为 " + out.keySet());
        return out;
    }

    private static String roleFromPrototypeLabel(String label) {
        if (label.contains("客户端")) {
            return "client";
        }
        if (label.contains("调理师")) {
            return "therapist";
        }
        if (label.contains("经络师")) {
            return "meridian";
        }
        if (label.contains("管理端")) {
            return "admin";
        }
        if (label.contains("门店客服")) {
            return null;
        }
        throw new AssertionError("原型 D1 出现无法归类的角色标签: " + label);
    }

    /** 返回第 {@code n} 个（1 基）{@code <td} 的起始下标，找不到返回 -1。 */
    private static int indexOfNthTd(String row, int n) {
        int idx = -1;
        int from = 0;
        for (int i = 0; i < n; i++) {
            idx = row.indexOf("<td", from);
            if (idx < 0) {
                return -1;
            }
            int close = row.indexOf('>', idx);
            if (close < 0) {
                return -1;
            }
            from = close + 1;
        }
        return idx;
    }

    // ======================================================================
    // 十三、上游 / 契约解析器
    // ======================================================================

    /** 上游 §2.x 各表首列的 {@code | A1 |} / {@code | **I1** |} 行号。 */
    private static Set<String> upstreamContractRows(String md) {
        String section = between(md, "### 2.1 域 A", "## 3. 字段可见性矩阵");
        Set<String> rows = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*\\*{0,2}([A-I]\\d+)\\*{0,2}\\s*\\|").matcher(section);
        while (m.find()) {
            rows.add(m.group(1));
        }
        assertFalse(rows.isEmpty(), "上游 §2 一行都没解析出来 —— 解析不出来必须红");
        return rows;
    }

    /** 上游 §2.x 表的 {@code | A1 | `POST /api/v1/x` |} → 行号 → {"POST /x"}。 */
    private static Map<String, Set<String>> upstreamRowOperations(String md) {
        String section = between(md, "### 2.1 域 A", "## 3. 字段可见性矩阵");
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*\\*{0,2}([A-I]\\d+)\\*{0,2}\\s*\\|([^\\n]*)")
                .matcher(section);
        while (m.find()) {
            String row = m.group(1);
            String rest = m.group(2);

            // 按出现顺序收集该行的全部「方法 + 路径」候选（含 `...` 省略写法）
            List<String> tokens = new ArrayList<>();
            Matcher op = Pattern.compile(
                            "`((?:POST|GET|PUT|PATCH|DELETE)\\s+(?:/api/v1|\\.\\.\\.)?/[^`]+)`")
                    .matcher(rest);
            while (op.find()) {
                tokens.add(op.group(1));
            }
            if (tokens.isEmpty()) {
                continue;
            }

            Set<String> ops = new TreeSet<>();
            // 第一个 token 恒为该行的主端点
            ops.add(normalizeEndpoint(tokens.get(0)));
            for (int i = 1; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.contains("/api/v1") || t.contains("...")) {
                    // D5 形态：一行多端点，后续 token 带 /api/v1 或 `...` 省略基路径
                    ops.add(normalizeEndpoint(t));
                } else {
                    // 说明列里的【语义别称】，形如 B2 的「建档（`POST /archive` 语义）」
                    // —— 它不是第二个端点。显式登记而非静默丢弃。
                    SEMANTIC_ALIASES.put(row, normalizeEndpoint(t));
                }
            }
            out.put(row, ops);
        }
        assertFalse(out.isEmpty(), "上游 §2 一行「方法+路径」都没解析出来");
        return out;
    }

    /** 上游说明列里的【语义别称】（非独立端点），显式登记以免与契约行集对不上。 */
    private static final Map<String, String> SEMANTIC_ALIASES = new LinkedHashMap<>();

    /** 端点归一化：去换行 / 去 base path / 把上游的 {@code ...} 省略写法还原 / 去 query。 */
    private static String normalizeEndpoint(String op) {
        String s = op.replace("\r", "").replace("\n", "").replaceAll("\\s+", " ").trim();
        s = s.replace("...", "/api/v1");     // 上游 D5 的省略写法
        s = s.replace("/api/v1", "");
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);           // I7 的 ?version= 不影响端点身份
        }
        return s;
    }

    /** 契约侧：x-contract-row 集合。 */
    private static Set<String> contractRows(Map<String, Object> doc) {
        Set<String> rows = new TreeSet<>();
        for (Map<String, Object> op : operations(doc).values()) {
            rows.add(str(op.get("x-contract-row")));
        }
        assertFalse(rows.isEmpty(), "契约一行都没解析出来");
        return rows;
    }

    /** 契约侧：行号 → {方法 路径}。 */
    private static Map<String, Set<String>> openapiRowOperations(Map<String, Object> doc) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : operations(doc).entrySet()) {
            String path = e.getKey().substring(0, e.getKey().indexOf(' '));
            String method = e.getKey().substring(e.getKey().indexOf(' ') + 1);
            out.computeIfAbsent(str(e.getValue().get("x-contract-row")), k -> new TreeSet<>())
                    .add(method + " " + path);
        }
        return out;
    }

    private static Map<String, Integer> countBySection(Set<String> rows) {
        Map<String, Integer> out = new TreeMap<>();
        for (String r : rows) {
            String sec = r.substring(0, 1);
            out.merge(sec, 1, Integer::sum);
        }
        return out;
    }

    /** 契约侧错误码：code → name。 */
    private static Map<Integer, String> openapiErrorCodes(Map<String, Object> doc) {
        List<Object> codes = list(doc.get("x-error-codes"), "x-error-codes");
        Map<Integer, String> out = new TreeMap<>();
        for (Object o : codes) {
            Map<String, Object> e = cast(o, "x-error-codes[]");
            out.put(((Number) e.get("code")).intValue(), str(e.get("name")));
        }
        return out;
    }

    /** 上游 §2.0 统一错误码表：code → name。形态 {@code | 400 | 1001 | `VALIDATION_FAILED` | ... |}。 */
    private static Map<Integer, String> upstreamErrorCodes(String md) {
        String section = between(md, "**统一错误码表**", "### 2.1 ");
        Map<Integer, String> out = new TreeMap<>();
        Matcher m = Pattern.compile(
                        "(?m)^\\|\\s*\\*{0,2}(\\d{3})\\*{0,2}\\s*\\|\\s*\\*{0,2}(\\d{4})\\*{0,2}\\s*\\|\\s*\\*{0,2}`?([A-Za-z_]+)`?\\*{0,2}\\s*\\|")
                .matcher(section);
        while (m.find()) {
            out.put(Integer.parseInt(m.group(2)), m.group(3));
        }
        assertFalse(out.isEmpty(), "上游 §2.0 错误码表一行都没解析出来（解析不出来必须红，"
                + "绝不能返回空集静默通过）—— 形态为 | HTTP | code | `NAME` | 触发条件 |");
        return out;
    }

    // ======================================================================
    // 十四、操作遍历 / 查找
    // ======================================================================

    /** 展平 {@code paths.*.<method>} → {@code "path METHOD"} → operation。 */
    private static Map<String, Map<String, Object>> operations(Map<String, Object> doc) {
        Map<String, Object> paths = cast(doc.get("paths"), "paths");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : paths.entrySet()) {
            Map<String, Object> item = cast(p.getValue(), "paths." + p.getKey());
            for (Map.Entry<String, Object> m : item.entrySet()) {
                if (!(m.getValue() instanceof Map)) {
                    continue;   // summary / description / parameters
                }
                Map<String, Object> op = optCast(m.getValue());
                if (op == null || !op.containsKey("operationId")) {
                    continue;
                }
                out.put(p.getKey() + " " + m.getKey().toUpperCase(), op);
            }
        }
        return out;
    }

    private static Map<String, Object> findOperationByRow(Map<String, Object> doc, String row) {
        for (Map<String, Object> op : operations(doc).values()) {
            if (row.equals(str(op.get("x-contract-row")))) {
                return op;
            }
        }
        return null;
    }

    private static String pathOfRow(Map<String, Object> doc, String row) {
        for (Map.Entry<String, Map<String, Object>> e : operations(doc).entrySet()) {
            if (row.equals(str(e.getValue().get("x-contract-row")))) {
                return e.getKey().substring(0, e.getKey().indexOf(' '));
            }
        }
        throw new AssertionError("找不到行 " + row + " 的路径");
    }

    // ======================================================================
    // 十五、断言与工具
    // ======================================================================

    private static void assertMatrixEquals(Map<String, Map<String, Boolean>> expected,
                                          Map<String, Map<String, Boolean>> actual,
                                          String sourceName) {
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()),
                sourceName + " 的角色集合与权威矩阵不一致");
        for (String role : expected.keySet()) {
            for (Map.Entry<String, Boolean> g : expected.get(role).entrySet()) {
                assertEquals(g.getValue(), actual.get(role).get(g.getKey()),
                        sourceName + " 的 " + role + "." + g.getKey() + " 档位与权威矩阵不一致 "
                                + "（期望 " + g.getValue() + "，实为 " + actual.get(role).get(g.getKey())
                                + "）—— 可见性档位变更属 MAJOR 级，须走 §1.5 变更流程");
            }
        }
    }

    /** 仓库根（含 {@code _work/} 与 {@code contract/} 的那一层）。三级回退，全败 → 红。 */
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

    private static String read(Path root, String rel) throws IOException {
        Path file = root.resolve(rel.replace('/', File.separatorChar));
        assertTrue(Files.isRegularFile(file), "源文件必须存在（缺文件不得静默通过）: " + file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.isBlank(), "源文件不得为空: " + file);
        return text;
    }

    private static String between(String text, String from, String to) {
        // ⚠️ 行尾符归一：契约 md 为 LF，原型 html 为 CRLF。
        // 若不做归一，「跨行」锚点（内含 \n）在 CRLF 文件里会静默匹配不到 ——
        // 而"匹配不到"在本类里是【报错】而不是"跳过"，所以必须先归一。
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String fromN = from.replace("\r\n", "\n").replace("\r", "\n");
        String toN = to.replace("\r\n", "\n").replace("\r", "\n");
        int a = normalized.indexOf(fromN);
        assertTrue(a >= 0, "找不到段落起点: " + fromN);
        int b = normalized.indexOf(toN, a + fromN.length());
        assertTrue(b > a, "找不到段落终点: " + toN + "（起点之后）");
        return normalized.substring(a, b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String yaml) {
        Object o = new Yaml().load(yaml);
        assertTrue(o instanceof Map, "YAML 顶层应为 mapping");
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> cast(Object o, String what) {
        Map<String, Object> m = optCast(o);
        assertNotNull(m, what + " 必须是 mapping，实为: " + (o == null ? "null" : o.getClass().getName()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optCast(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static List<Object> list(Object o, String what) {
        assertTrue(o instanceof List, what + " 必须是 sequence，实为: "
                + (o == null ? "null" : o.getClass().getName()));
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) o;
        return l;
    }

    private static List<String> optStrList(Object o) {
        if (!(o instanceof List<?> l)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object x : l) {
            out.add(str(x));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void assertNotEquals(Object unexpected, Object actual, String msg) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual, msg);
    }
}