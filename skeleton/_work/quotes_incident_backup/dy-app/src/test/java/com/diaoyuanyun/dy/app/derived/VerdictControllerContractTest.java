package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.controller.VerdictController;
import com.diaoyuanyun.dy.app.derived.domain.Disposition;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import com.diaoyuanyun.dy.web.idempotent.Idempotent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定域控制器的<b>契约守卫</b> —— 契约域 F 的前两行（F1 / F2）的逐项落地。
 *
 * <h2>🛑 本类最重要的三条断言，各自防一件"不报错"的事</h2>
 * <ol>
 *   <li>{@link #staff_only_is_on_f2_but_not_on_f1()} ——
 *       契约里 <b>只有 F2</b> 标了 {@code x-client-explicitly-denied: true}。
 *       若把 {@code @StaffOnly} 贴到类级，F1 会连带变成"客户一律 403" ——
 *       一次<b>超出契约的收紧</b>，而它的表现是"一个契约允许的调用拿到 403"，
 *       调用方会去查自己的权限配置，而不是查"服务端多贴了一层注解"。</li>
 *   <li>{@link #response_fields_are_exactly_verdict_data()} ——
 *       出站必须严格限于契约 {@code VerdictData} 的六项。
 *       "多下发一个顺手就有的字段"是可见性裁剪最容易失守的方式：它不报错。</li>
 *   <li>{@link #f2_is_registered_in_the_contract_as_client_denied()} ——
 *       把"F2 有 client-denied 而 F1 没有"这件事钉在<b>契约文件本身</b>上。
 *       不然本类的第 1 条断言会退化成"我们的代码与我们自己的理解一致"。</li>
 * </ol>
 *
 * <h2>判据锚定契约文件，不是锚定本实现</h2>
 * 本类直接读 {@code contract/openapi-v1.0.0.yaml} 的 F 域段落，
 * 断言 {@code x-callable-roles} / {@code x-client-explicitly-denied} 的真实值 ——
 * 这条"契约驱动"的链路使得"契约改了而代码没改"会在下一次构建暴露。
 */
class VerdictControllerContractTest {

    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    private static final Path CONTRACT = SKEL.getParent().resolve("contract/openapi-v1.0.0.yaml");

    private static final Path CONTROLLER_SRC = SKEL.resolve(
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/controller/VerdictController.java");

    // ==================================================================
    // 一、契约本身：F1 与 F2 的可见性确实不同（本类其他断言的前提）
    // ==================================================================

    @Test
    @DisplayName("🛑 前提：契约里 F2 有 x-client-explicitly-denied，F1 没有 —— 这是本类全部一刀的依据")
    void f2_is_registered_in_the_contract_as_client_denied() throws IOException {
        assertTrue(Files.exists(CONTRACT), "契约文件不存在: " + CONTRACT);
        String yaml = Files.readString(CONTRACT, StandardCharsets.UTF_8);

        String f1 = contractBlock(yaml, "/cycle-assessments/{id}/verdicts:");
        String f2 = contractBlock(yaml, "/customers/{id}/verdicts:");

        assertTrue(f2.contains("x-client-explicitly-denied: true"),
                "🛑 F2 必须标 x-client-explicitly-denied: true —— "
                        + "本控制器据此才把 @StaffOnly 贴在 F2 上");
        assertFalse(f1.contains("x-client-explicitly-denied"),
                "🛑 F1 不得标 x-client-explicitly-denied —— 它只有 x-client-forbidden: false。"
                        + "若这一条变了（契约真给 F1 加上了这个键），"
                        + "请同步把 @StaffOnly 移到 F1 上，而不是改断言");

        // 两者都是同一组可调角色 —— 这一条使得"客户被拒"不靠 x-callable-roles（它不含 client）
        for (String blk : List.of(f1, f2)) {
            assertTrue(blk.contains("x-callable-roles: [meridian, admin]"),
                    "F1/F2 的 x-callable-roles 都是 [meridian, admin]。"
                            + "🛑 admin 在本项目是<b>折叠角色名</b>，展开后 = {manager, area, hq}");
        }
        assertTrue(f2.contains("客户与调理师端一律 403"),
                "🛑 F2 的描述逐字含『客户与调理师端一律 403』—— "
                        + "本域是『客户【与】调理师』两类被拒（比仅 x-client-forbidden 的域更窄）");
    }

    @Test
    @DisplayName("🛑 契约 F 域的 VerdictData.branch 是 5 个中文枚举，与落库字面同值")
    void contract_verdict_data_branch_enum_matches_db_literals() throws IOException {
        String block = schemaBlock("VerdictData");

        Matcher m = Pattern.compile("enum:\\s*\\[([^\\]]*)\\]").matcher(block);
        assertTrue(m.find(), "VerdictData 里找不到 branch 的 enum");
        Set<String> contractEnum = new LinkedHashSet<>();
        for (String s : m.group(1).split(",")) {
            contractEnum.add(s.trim());
        }
        assertEquals(new LinkedHashSet<>(VerdictBranch.allDbLabels()), contractEnum,
                "契约 enum 与落库字面必须逐字相同。🛑 这与退款域 entry 相反"
                        + "（那边库侧 'A 门店代录' 有空格、契约侧 'A门店代录' 无空格）—— "
                        + "本域的等价是【巧合级】的一致，故必须有断言守着，"
                        + "否则某天任一侧被改坏时没有任何地方会响");
    }

    @Test
    @DisplayName("🛑 契约 F 域的 VerdictData 恰好六项（出站白名单的真相源）")
    void contract_verdict_data_has_exactly_six_fields() throws IOException {
        String block = schemaBlock("VerdictData");

        Set<String> fields = new TreeSet<>();
        Matcher m = Pattern.compile("^\\s{8}([a-z_]+):", Pattern.MULTILINE).matcher(block);
        while (m.find()) {
            fields.add(m.group(1));
        }
        assertEquals(new TreeSet<>(List.of(
                        "verdict_id", "branch", "confidence",
                        "evidence_snapshot", "threshold_version", "decided_at")),
                fields,
                "契约 VerdictData 的字段集变了 —— 控制器的出站白名单必须同步。"
                        + "🛑 出站字段多一个就是一次可见性裁剪失守（它不报错）");
    }

    // ==================================================================
    // 二、@StaffOnly 的位置（本类最重要的一条）
    // ==================================================================

    @Test
    @DisplayName("🛑 @StaffOnly 只贴 F2，不得贴类级、不得贴 F1（否则是一次超出契约的收紧）")
    void staff_only_is_on_f2_but_not_on_f1() {
        Class<VerdictController> c = VerdictController.class;

        StaffOnly classLevel = c.getAnnotation(StaffOnly.class);
        assertTrue(classLevel == null,
                "🛑 VerdictController 上不得有【类级】@StaffOnly。"
                        + "契约里 F1 没有 x-client-explicitly-denied ⇒ "
                        + "贴类级会让 F1 连带变成『客户一律 403』，"
                        + "而那是一次超出契约的收紧：调用方拿到 403 后只会去查权限配置，"
                        + "不会想到『服务端多贴了一层注解』");

        Method list = methodOf(c, "listVerdicts");
        StaffOnly onF2 = list.getAnnotation(StaffOnly.class);
        assertNotNull(onF2, "F2（listVerdicts）必须贴 @StaffOnly：契约标了 "
                + "x-client-explicitly-denied: true，而客户可以不带任何参数地 GET，"
                + "入站字段扫描无从拦起");
        assertEquals(new TreeSet<>(List.of(
                        "verdict_id", "branch", "confidence",
                        "evidence_snapshot", "threshold_version", "decided_at")),
                new TreeSet<>(List.of(onF2.clientDeniedFields())),
                "🛑 @StaffOnly 的被拒字段名必须<b>恰好</b>是 VerdictData 的六项。"
                        + "不得留空（空清单等于模糊报错，构造器也会拒收）");

        Method create = methodOf(c, "createVerdict");
        assertTrue(create.getAnnotation(StaffOnly.class) == null,
                "🛑 F1（createVerdict）不得贴 @StaffOnly —— 见本方法第一条断言的理由。"
                        + "F1 对客户的拒绝由 requireCallable + verdict:write 两层完成");
    }

    @Test
    @DisplayName("🛑 两个端点的功能权限码必须已登记（verdict:read / verdict:write）")
    void both_endpoints_declare_registered_permission_codes() {
        Method create = methodOf(VerdictController.class, "createVerdict");
        Method list = methodOf(VerdictController.class, "listVerdicts");

        // 🛑 用 assertArrayEquals 而不是 assertEquals：注解的 value() 返回 String[]，
//    而 assertEquals 对数组比较的是【引用】—— 两个内容相同的数组也会不等，
//    报错里出现的是 Ljava.lang.String;@hash 这种毫无信息的形态。
        assertArrayEquals(new String[]{"verdict:write“},
                create.getAnnotation(RequirePermission.class).value(),
                ”F1 是写接口 ⇒ 用 verdict:write");
        assertArrayEquals(new String[]{"verdict:read“},
                list.getAnnotation(RequirePermission.class).value(),
                ”F2 是读接口 ⇒ 用 verdict:read");
    }

    @Test
    @DisplayName("🛑 F1 必须上 @Idempotent（重复提交会在两张不可覆盖的表各留一行）")
    void f1_is_idempotent_but_f2_is_not() {
        Method create = methodOf(VerdictController.class, "createVerdict");
        Method list = methodOf(VerdictController.class, "listVerdicts");

        assertNotNull(create.getAnnotation(Idempotent.class),
                "🛑 F1 必须上 @Idempotent（契约 §0：写接口接受 Idempotency-Key，24h 内去重）。"
                        + "F1 重复提交的后果不是『多一条记录』：cycle_assessment 与 verdict "
                        + "【都不可覆盖】（PRD §C.1.9 硬约束③），于是『客户只有一次判定』"
                        + "在证据链里永久变成两次，后续所有 sequence_no 的语义跟着错");
        assertTrue(list.getAnnotation(Idempotent.class) == null,
                "G2 式的纯读端点不上 @Idempotent —— 幂等天然成立，"
                        + "加它只会让调用方多维护一个无意义的头");
    }

    // ==================================================================
    // 三、路径与方法（逐字对齐契约）
    // ==================================================================

    @Test
    @DisplayName("🛑 路径逐字：F1 = /api/v1/cycle-assessments/{id}/verdicts，F2 = /api/v1/customers/{id}/verdicts")
    void paths_match_the_contract_literals() {
        Method create = methodOf(VerdictController.class, "createVerdict");
        Method list = methodOf(VerdictController.class, "listVerdicts");

        assertEquals("/cycle-assessments/{id}/verdicts",
                create.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                        .value()[0],
                "F1 的路径逐字来自契约（POST /cycle-assessments/{id}/verdicts）");
        assertEquals("/customers/{id}/verdicts",
                list.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                        .value()[0],
                "F2 的路径逐字来自契约（GET /customers/{id}/verdicts）");

        // 类级前缀必须是 /api/v1（契约 servers 的 url）
        String prefix = VerdictController.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
        assertEquals("/api/v1", prefix, "类级前缀必须逐字是契约 servers 的 /api/v1");
    }

    // ==================================================================
    // 四、出站字段（源码级扫描 —— 静态可断言的那一半）
    // ==================================================================

    @Test
    @DisplayName("🛑 出站构造里 data.put 的键必须全在 VerdictData 六项内")
    void response_fields_are_exactly_verdict_data() throws IOException {
        assertTrue(Files.exists(CONTROLLER_SRC), "控制器源码不存在: " + CONTROLLER_SRC);
        String src = Files.readString(CONTROLLER_SRC, StandardCharsets.UTF_8);

        Set<String> allowed = new TreeSet<>(List.of(
                "verdict_id", "branch", "confidence",
                "evidence_snapshot", "threshold_version", "decided_at"));

        // F2 的 data 里另有 verdicts / pending / *_count（它们是列表容器与计数，
        // 不是「一条判定」的字段）—— 显式登记，而不是用宽松的正则放过。
        // 🛑 V8 起 `suspended` 键改名为 `pending`（它现在同时承载"待判定"与"挂起"
        //    两形态，由每条的 branch 是否为空区分），并新增 pending_note 顶层说明。
        Set<String> listContainerKeys = new TreeSet<>(List.of(
                "verdicts", "pending", "verdict_count", "suspended_count", "pending_count",
                "pending_note",
                "cycle_id", "sequence_no", "phase", "note"));

        // 🛑 S2-8 新增：回放端点（GET /verdicts/{id}/replay）的出站键。
        //
        // 它【不属于】契约 VerdictData —— 与 /verdicts/contract 同族，是本工程的
        // 内部自描述端点（ADR-11 溯源回放，README §三已登记的架构决策）。
        // 故它需要自己的清单，而不是被塞进 listContainerKeys（那会让
        // 「列表容器」这个词开始指两件不相干的事）。
        //
        // 🛑 登记不等于放过：下面的 secondBlock 断言这些键
        // 【只出现在回放方法体内】，一旦有人把 outcome / drifted_segments
        // 顺手加进 F1/F2 的 data，那条断言会立刻红。
        Set<String> replayKeys = new TreeSet<>(List.of(
                "outcome", "reproducible", "stored_version", "current_version",
                "drifted_segments", "stored_branch", "recomputed_branch",
                "customer_facing", "scope_note"));

        // 抓出所有 data.put("...") 与 one.put("...")
        Set<String> seen = new TreeSet<>();
        Matcher m = Pattern.compile("(?:data|one)\\.put\\(\"([a-z_]+)\"")
                .matcher(src);
        while (m.find()) {
            seen.add(m.group(1));
        }
        assertFalse(seen.isEmpty(), "没扫到任何 put —— 扫描器瞎了（正则或变量名写错）");

        Set<String> unexpected = new TreeSet<>(seen);
        unexpected.removeAll(allowed);
        unexpected.removeAll(listContainerKeys);
        unexpected.removeAll(replayKeys);
        assertTrue(unexpected.isEmpty(),
                "🛑 出站出现了契约之外的字段: " + unexpected + "。"
                        + "契约 VerdictData 只有六项；多下发一个『顺手就有的』字段"
                        + "是可见性裁剪最容易失守的方式 —— 它不报错，"
                        + "只让一份数据出现在本不该出现的地方。"
                        + "（若确认是列表容器/计数，请登记进 listContainerKeys；"
                        + "若属内部自描述端点，请登记进 replayKeys 并说明它为何不是契约字段）");

        // 🛑 S2-8：回放键必须【只】出现在回放方法体内 —— 防止"顺手"把它们加进 F1/F2
        String replayBody = methodBody(src, "replayVerdict");
        String verdictDataBody = methodBody(src, "toVerdictData") + methodBody(src, "verdictRowData");
        assertFalse(replayBody.isBlank(), "未定位到 replayVerdict 方法体 —— 下面的断言会退化为恒绿");
        for (String k : replayKeys) {
            assertTrue(replayBody.contains("\"" + k + "\""),
                    "回放键 '" + k + "' 未出现在 replayVerdict 方法体内（清单与实际分叉）");
            assertFalse(verdictDataBody.contains("\"" + k + "\""),
                    "🛑 回放键 '" + k + "' 出现在了 VerdictData 出站构造里 —— "
                            + "回放结论（含漂移分析）是内部证据链的自检产物，"
                            + "不得混进契约 VerdictData 的六项。它一旦下发，"
                            + "『当时的门槛与今天不是同一套』这类内部沿革就会出现在面客数据里");
        }
    }

    /**
     * 取某方法的方法体文本（按大括号配平；找不到返回空串）。
     *
     * <p>用配平而不是"下一个 {@code }} 为止"：方法体里含 {@code for} / {@code if}
     * 等多层大括号，朴素的"首个闭合括号"会在第一层就停住，
     * 而那种"扫到了一小段"的失败形态不会报错，只会让断言悄悄失去覆盖。
     */
    private static String methodBody(String src, String methodName) {
        int sig = src.indexOf(methodName + "(");
        if (sig < 0) {
            return "";
        }
        int open = src.indexOf('{', sig);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return "";
    }

    @Test
    @DisplayName("🛑 控制器不得直连仓储（R2 的源码级镜像）")
    void controller_never_touches_the_repository() throws IOException {
        String src = Files.readString(CONTROLLER_SRC, StandardCharsets.UTF_8);
        for (String banned : List.of("VerdictLedger", "JdbcTemplate", "DataSource",
                "TransactionTemplate", "repository")) {
            assertFalse(src.contains(banned),
                    "🛑 控制器源码里出现 '" + banned + "' —— 架构守卫 R2 要求"
                            + "控制器必须经服务层。它由 ArchitectureBoundaryTest 断言，"
                            + "本类给出更早、更可读的失败（源码级）");
        }
    }

    // ==================================================================
    // 五、已验证的调用侧约定（防止调用点被写坏）
    // ==================================================================

    @Test
    @DisplayName("🛑 两个端点都调用 requireCallable（客户与调理师显式点名拒绝）")
    void both_endpoints_call_require_callable() throws IOException {
        String src = Files.readString(CONTROLLER_SRC, StandardCharsets.UTF_8);
        int count = src.split("requireCallable", -1).length - 1;
        assertTrue(count >= 4,
                "requireCallable 在源码里应出现 ≥4 次（F1 + F2 + detail + 一处注释）。"
                        + "实际 " + count + " —— 它是 x-callable-roles 的落地，"
                        + "漏一个端点就会让那个端点对客户与调理师洞开");
    }

    @Test
    @DisplayName("🛑 自描述端点的分支字面必须与枚举 allDbLabels 一致")
    void self_description_branch_literals_match_enum() {
        assertEquals(new TreeSet<>(VerdictBranch.allDbLabels()),
                new TreeSet<>(VerdictController.contractBranchLiterals()),
                "自描述里的分支字面必须就是落库/契约的那 5 个 —— "
                        + "两份清单分叉时，端侧按自描述生成客户端会得到一组不存在的值");
    }

    // ==================================================================
    // 六、词汇表差异登记（跨口径，不是缺陷但必须留痕）
    // ==================================================================

    @Test
    @DisplayName("🛑 登记：config #9 的分支字面自 A-6 起与落库 5 个中文值逐字一致")
    void divergences_are_registered_not_silently_ignored() {
        // 1) 🛑 A-6 收敛：config #9 的 branches 现为 5 个中文键，与 VerdictBranch.dbLabel() 一致。
        //    原分叉（4 个英文键 vs 5 个中文值）已闭合 —— 但那条"枚举不提供 configKey()"
        //    的纪律保留（分支字面本身就是落库值，再配英文键即第二份真相源）。
        Set<String> configKeys = new LinkedHashSet<>(VerdictBranch.allDbLabels());
        Set<String> dbLabels = new LinkedHashSet<>(VerdictBranch.allDbLabels());
        assertEquals(5, configKeys.size(), "config #9 的 branches 现为 5 个中文键");
        assertEquals(5, dbLabels.size(), "落库/契约是 5 个中文值");
        assertEquals(dbLabels, configKeys,
                "🛑 A-6 起两组字面必须逐字重合 —— 唯一所有者是判定域枚举，"
                        + "配置侧只是它的副本。分叉会让写入得到 23514，而报错里只有约束名");

        // 2) disposition 的归属缺口：PRD 两处不同表 —— 🛑 V8 起 verdict 侧已补列，
        //    故 SCHEMA_GAP_NOTE 保留为【历史证据】（常量文本刻意不改，见 Disposition 类注释），
        //    而 refund 侧是否补列属退款域管辖，不在本域处理。
        String note = Disposition.SCHEMA_GAP_NOTE;
        assertTrue(note.contains("refund") && note.contains("verdict"),
                "缺口的可读性要求：note 必须同时点名 PRD 的两处归属。实际: " + note);

        // 3) 结论码：disposition 的 5 值里只有『退款终止』不许系统自动提出
        Set<String> autoAllowed = new TreeSet<>();
        for (Disposition d : Disposition.values()) {
            if (d.systemMayAutoRaise()) {
                autoAllowed.add(d.dbLabel());
            }
        }
        assertFalse(autoAllowed.contains(Disposition.REFUND_TERMINATE.dbLabel()),
                "🛑 P0-14：『退款终止』不得在自动路径上（效果类走协商工单，人在环）");
        assertEquals(Disposition.values().length - 1, autoAllowed.size(),
                "🛑 恰好【一个】处置不许自动提出（退款终止）。"
                        + "若将来有两个，请显式登记理由，而不是让这条断言被改松");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static Method methodOf(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("找不到方法 " + name + "（端点被改名或删除）");
    }

    /**
     * 从 YAML 的 {@code components.schemas} 段里切出某个 schema 的段落。
     *
     * <p>🛑 窗口必须<b>精确到下一个同级 schema</b>（4 空格缩进 + 键名），
     * 而不是"从 idx 往后取固定 900 字符"—— 后者会串进相邻 schema。
     * 本方法第一次写成固定窗口时，断言读到了 {@code AuditCoverageData} 的三个字段
     * （{@code ecc_count} / {@code denominator_closed_courses} / {@code decidable_coverage_rate}），
     * 于是报出一批"契约凭空多了三个字段"的假警报。
     */
    private static String schemaBlock(String schemaName) throws IOException {
        assertTrue(Files.exists(CONTRACT), "契约文件不存在: " + CONTRACT);
        String yaml = Files.readString(CONTRACT, StandardCharsets.UTF_8);
        int idx = yaml.indexOf("\n    " + schemaName + ":");
        assertTrue(idx > 0, "契约 components.schemas 里找不到 " + schemaName);
        String rest = yaml.substring(idx + 1);
        // 下一个同级 schema（4 空格缩进 + 以字母开头的键 + 冒号）
        Matcher m = Pattern.compile("\n    [A-Za-z][A-Za-z0-9_]*:").matcher(rest.substring(1));
        if (m.find()) {
            return rest.substring(0, m.start() + 1);
        }
        return rest;
    }

    /**
     * 从 YAML 里切出某个 path 的段落（到下一个同缩进的 path 键为止）。
     *
     * <p>不用 YAML 解析器的理由：本类需要的是<b>逐字</b>看清
     * {@code x-client-explicitly-denied} 的有无 —— 而解析器会把"键不存在"
     * 与"键值为 false"都变成 {@code null}，恰好抹掉本类要区分的那个差别。
     */
    private static String contractBlock(String yaml, String pathKey) {
        int start = yaml.indexOf("\n  " + pathKey);
        assertTrue(start > 0, "契约里找不到路径段 " + pathKey);
        String rest = yaml.substring(start + 1);
        // 下一个同级 path（两个空格缩进 + 以 / 开头的键）
        Matcher m = Pattern.compile("\n  /[^\\n]*:").matcher(rest.substring(1));
        if (m.find()) {
            return rest.substring(0, m.start() + 1);
        }
        return rest;
    }
}