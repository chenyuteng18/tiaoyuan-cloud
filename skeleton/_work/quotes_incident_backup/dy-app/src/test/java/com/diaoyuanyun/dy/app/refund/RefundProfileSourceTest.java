package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundRawConfig;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-1「退款域口径外置」的来源侧守卫 —— 口径<b>从哪来</b>，以及它断掉时会不会大声失败。
 *
 * <h2>它回答的问题，与 {@code RefundPolicyContractTest} 不同</h2>
 * {@code RefundPolicyContractTest} 回答"口径的语义是否被约束住"（配置改坏会不会报错）；
 * 本类回答"<b>那个『外部来源』是否真的存在、真的被读到、断了会不会报错</b>"。
 * 两者不可互相替代：一个把口径写死在代码里的实现，只要它同时也读到了种子文件，
 * 就能骗过前者；而一个只读种子文件却把口径当默认值兜底的实现，能骗过后者。
 *
 * <h2>🛑 为什么"文件被改名 / 移走必须红"要单独断言</h2>
 * {@link ConfigSeedRefundProfileSource} 是<b>过渡实现</b>：它不读 DB，而是解析
 * dy-config 的 {@code 02_slots_seed.sql} 里的七行。这条路径有个特征 ——
 * <b>它是一根字符串连线</b>（{@code SEED_RESOURCE} 常量 ↔ classpath 上的文件位置）。
 * 这种连线断掉时，若实现选择"读不到就用默认口径"，故障就是<b>静默</b>的：
 * 退款照常受理、工单照常生成，只是可见性换成了某个默认矩阵。
 * <p>而本域的默认可见性风险是具体的：一份"看起来合理"的默认矩阵，
 * 最可能的写法是"除了客户都可见"或"都可见"—— 前者放开了调理师端
 * （U3 明令不可），后者直接放开客户端（§2.2 硬锁）。
 * 故本测试要证明的是：<b>连线断掉 = 立刻抛错</b>，而不是"退化成一个看起来正常的默认矩阵"。
 *
 * <h2>为什么"七段"要逐段点名断言</h2>
 * {@link #raw()} 按 {@code SPECS} 的下标取值。若某一行被删、或列表顺序被调整，
 * 最危险的失效形态不是"报错"，而是<b>错位</b>：{@code #27} 的 {@code 48} 被当成
 * {@code #28} 的到账天数 —— 两者都是合法正整数，解析期全部通过，
 * 结果是把"挽留 48 小时首响应"读成"承诺客户 48 天到账"。
 * 故本类逐段断言"这一段的值就是它自己的值"，而不是只断言"能解析出七段"。
 */
class RefundProfileSourceTest {

    /** dy-config 的配置声明文件在 classpath 上的位置（与实现类常量逐字一致）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** 退款域七段声明的 (编号, 键) —— 顺序即 {@link RefundRawConfig} 的字段顺序。 */
    private static final String[][] SLOTS = {
            {"10", "cfg:refund.gate_rules“},
            {”26", "cfg:refund.verdict_latency“},
            {”27", "cfg:refund.retention_sla_hours“},
            {”28", "cfg:refund.arrival_commitment_days“},
            {”29", "cfg:refund.concession_approval_threshold“},
            {”38", "cfg:refund.fulfillment_direct_formula“},
            {”40", "cfg:refund.visibility“},
    };

    private final RefundProfileSource source = new ConfigSeedRefundProfileSource();

    // ==================================================================
    // 一、来源真的存在、真的被读到
    // ==================================================================

    @Test
    @DisplayName(”种子文件必须在 classpath 上，且七段声明的键与编号都在（逐段点名，不只看文件名）")
    void the_seed_file_is_reachable_and_declares_all_seven_slots() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(is, "classpath 上找不到 " + SEED_RESOURCE
                    + " —— 退款口径来源断开。若该文件被移动，需同步更新 "
                    + ConfigSeedRefundProfileSource.class.getSimpleName() + ".SEED_RESOURCE");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String[] slot : SLOTS) {
                String no = slot[0];
                String key = slot[1];
                assertTrue(sql.contains(key),
                        SEED_RESOURCE + " 里找不到键 " + key
                                + " —— 声明的键名被改了，而实现仍按旧键抓取");
                assertTrue(sql.contains("(" + no + ",") || sql.contains("(" + no + " ,"),
                        SEED_RESOURCE + " 里找不到编号 " + no + " 的声明行");
            }
        }
    }

    @Test
    @DisplayName("能解析出七段声明值，且七段齐全（isComplete 为真）")
    void all_seven_slots_are_parsed_and_complete() {
        RefundRawConfig raw = source.raw();
        assertNotNull(raw, "解析结果不得为空");
        assertTrue(raw.isComplete(),
                "七段应齐全；缺失项=" + raw.missingKeys()
                        + "。🛑 任一缺失即不得受理退款：本域七段中五段直接决定"
                        + "『客户能不能退、退多少、谁看得见』");
        assertTrue(raw.missingKeys().isEmpty(), "齐全时缺失清单应为空");
    }

    // ==================================================================
    // 二、解析出的值就是口径本身（逐段对照，专门防"错位"）
    // ==================================================================

    @Test
    @DisplayName("🛑 #27 与 #28 不得错位：挽留 SLA=48（小时）与到账承诺=7（工作日）是两个量纲")
    void retention_sla_and_arrival_days_are_not_swapped() {
        RefundRawConfig raw = source.raw();
        // 声明值（02_slots_seed.sql）：
        //   #27 cfg:refund.retention_sla_hours      = '48'（挽留响应 SLA：自动派单后 48h 内首响应）
        //   #28 cfg:refund.arrival_commitment_days  = '7' （向客户承诺 7 个工作日内到账）
        assertEquals("48", raw.retentionSlaHours().trim(),
                "#27 应为 48（挽留响应 SLA，单位【小时】）");
        assertEquals("7", raw.arrivalCommitmentDays().trim(),
                "#28 应为 7（退款到账承诺，单位【工作日】）");
        // 反向断言：两个值不得相等 —— 相等时"错位"这一失效形态无法被本测试检出，
        // 于是这条守卫会在最需要它的时刻静默失效。
        assertFalse(raw.retentionSlaHours().trim().equals(raw.arrivalCommitmentDays().trim()),
                "前置失效：本断言的判别力来自『两值不同』。若 #27 与 #28 的声明值变成相等，"
                        + "『错位』就无法被检出 —— 请改用『量纲』或『语义』做区分依据");
    }

    @Test
    @DisplayName("#26 的声明值是 ENUM 字面 'immediate'（不是 JSON、不是数字）")
    void verdict_latency_is_the_raw_enum_literal() {
        String v = source.raw().verdictLatency().trim();
        assertEquals("immediate", v,
                "#26 的声明值应为裸枚举字面 immediate（allowed_values=[immediate]）；"
                        + "若变成 {\"late\":...} 或数字，说明该行被改成了别的值类型");
        assertFalse(v.startsWith("{") || v.startsWith("["),
                "#26 不是 JSON 型，取值不应以 '{' 或 '[' 开头");
    }

    @Test
    @DisplayName("三段 JSON 型声明各自是可解析的 JSON 对象，且各含自己独有的键")
    void the_three_json_slots_are_parseable_and_distinguishable() {
        RefundRawConfig raw = source.raw();
        // #10 独有 "routes"；#29 独有 "basis"；#38 独有 "formula"；#40 独有 "meridian_therapist"
        assertTrue(raw.gateRulesJson().contains("\"routes\""),
                "#10 解析结果应含 routes 段（否则可能抓到了相邻条目的 JSON —— "
                        + "本域多个条目的取值都以 '{' 开头，只锚编号易抓错）");
        assertTrue(raw.concessionThresholdJson().contains("\"basis\""),
                "#29 解析结果应含 basis 段");
        assertTrue(raw.fulfillmentFormulaJson().contains("\"formula\""),
                "#38 解析结果应含 formula 段");
        assertTrue(raw.visibilityJson().contains("\"meridian_therapist\""),
                "#40 解析结果应含 meridian_therapist 键");
        for (String json : new String[]{raw.gateRulesJson(), raw.concessionThresholdJson(),
                raw.fulfillmentFormulaJson(), raw.visibilityJson()}) {
            assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"),
                    "四段 JSON 型取值都应是完整对象（从 '{' 到 '}'）。实际: " + json);
        }
    }

    // ==================================================================
    // 三、🛑 连线断掉必须大声失败（不得退化为默认口径）
    // ==================================================================

    @Test
    @DisplayName("实现类里不存在任何『取不到就用默认值』的兜底分支（机械扫描，不靠通读）")
    void the_implementation_has_no_default_value_bailout_branch() throws IOException {
        String rawSource = Files.readString(resolveSource(), StandardCharsets.UTF_8);
        String code = stripCommentsAndStrings(rawSource);

        for (String bailout : new String[]{
                "getOrDefault", "DEFAULT_", "defaultProfile", "defaultPolicy",
                "return null;", "orElse(“}) {
            assertFalse(code.contains(bailout),
                    ”实现类里出现兜底写法 '" + bailout + "' —— 口径读不到时必须抛错，"
                            + "不得退化成默认口径（那会让『客户看得见退款字样』以配置缺省的形式静默发生）");
        }
        // 正向自证：它确实在失败路径上抛 BizException（而不是返回空串 / 默认值）
        assertTrue(code.contains("throw new BizException"),
                "实现类应在其失败路径上抛 BizException（口径缺失 = 拒绝受理退款）");
        // 反证扫描不是恒绿：注释里确实讨论过"默认"这件事，剥完就不该再有
        assertTrue(rawSource.contains("默认"),
                "前置失效：实现类文档里应讨论过『默认口径』这件事；文件已改动，请复核本断言");
        assertFalse(code.contains("默认"),
                "注释剥离失效：剥完仍含『默认』二字 —— 块注释匹配漏了 DOTALL");
    }

    @Test
    @DisplayName("空 / null 原始口径一律拒（5001 业务规则冲突，不是 NPE）")
    void missing_raw_config_is_rejected_not_defaulted() {
        assertEquals(5001,
                assertThrows(BizException.class,
                        () -> com.diaoyuanyun.dy.app.refund.domain.RefundPolicy.fromRawConfig(null))
                        .getCode(),
                "null 口径应属业务规则冲突（5001）—— 不得按默认口径处理退款");
    }

    @Test
    @DisplayName("七段中任一段为空 ⇒ 抛错并点名该段（不是笼统的『配置缺失』）")
    void any_blank_slot_is_rejected_and_named() {
        RefundRawConfig full = source.raw();

        // 逐段做"置空"变异：每次只空一段，断言① 必抛 ② 消息点名声明的编号
        record Mutant(String label, RefundRawConfig raw) {
        }
        java.util.List<Mutant> mutants = java.util.List.of(
                new Mutant("#10", new RefundRawConfig(null, full.verdictLatency(),
                        full.retentionSlaHours(), full.arrivalCommitmentDays(),
                        full.concessionThresholdJson(), full.fulfillmentFormulaJson(), full.visibilityJson())),
                new Mutant("#26", new RefundRawConfig(full.gateRulesJson(), "  ",
                        full.retentionSlaHours(), full.arrivalCommitmentDays(),
                        full.concessionThresholdJson(), full.fulfillmentFormulaJson(), full.visibilityJson())),
                new Mutant("#27", new RefundRawConfig(full.gateRulesJson(), full.verdictLatency(),
                        "", full.arrivalCommitmentDays(),
                        full.concessionThresholdJson(), full.fulfillmentFormulaJson(), full.visibilityJson())),
                new Mutant("#28", new RefundRawConfig(full.gateRulesJson(), full.verdictLatency(),
                        full.retentionSlaHours(), null,
                        full.concessionThresholdJson(), full.fulfillmentFormulaJson(), full.visibilityJson())),
                new Mutant("#29", new RefundRawConfig(full.gateRulesJson(), full.verdictLatency(),
                        full.retentionSlaHours(), full.arrivalCommitmentDays(),
                        " ", full.fulfillmentFormulaJson(), full.visibilityJson())),
                new Mutant("#38", new RefundRawConfig(full.gateRulesJson(), full.verdictLatency(),
                        full.retentionSlaHours(), full.arrivalCommitmentDays(),
                        full.concessionThresholdJson(), null, full.visibilityJson())),
                new Mutant("#40", new RefundRawConfig(full.gateRulesJson(), full.verdictLatency(),
                        full.retentionSlaHours(), full.arrivalCommitmentDays(),
                        full.concessionThresholdJson(), full.fulfillmentFormulaJson(), null)));

        for (Mutant m : mutants) {
            BizException e = assertThrows(BizException.class,
                    () -> com.diaoyuanyun.dy.app.refund.domain.RefundPolicy.fromRawConfig(m.raw()),
                    "置空 " + m.label() + " 后必须抛错（七段缺一即不得受理退款）");
            assertEquals(5001, e.getCode(), m.label() + " 缺失应属业务规则冲突（5001）");
            assertTrue(e.getMessage().contains(m.label()),
                    "报错消息必须点名缺失的配置编号 " + m.label() + "（契约 P0-08『不得模糊报错』）；"
                            + "实际消息: " + e.getMessage());
        }
    }

    // ==================================================================
    // 四、来源可解释
    // ==================================================================

    @Test
    @DisplayName("来源自描述必须点名文件 + 七个编号，并声明这是过渡来源")
    void the_source_description_names_the_file_all_slots_and_that_it_is_transitional() {
        String d = source.describeSource();
        assertTrue(d.contains(SEED_RESOURCE), "来源描述应点名文件；实际: " + d);
        for (String[] slot : SLOTS) {
            assertTrue(d.contains("#" + slot[0]),
                    "来源描述应点名配置编号 #" + slot[0] + "；实际: " + d);
        }
        assertTrue(d.contains("替换") || d.contains("过渡"),
                "来源描述应声明它是过渡实现（配置真相源落库后须替换）—— "
                        + "否则下游会误以为口径已从 DB 读取；实际: " + d);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 定位实现类源码文件（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveSource() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/service/"
                + "ConfigSeedRefundProfileSource.java");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 ConfigSeedRefundProfileSource 源码（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    private static String stripCommentsAndStrings(String src) {
        // (?s) = DOTALL：块注释跨行匹配（Javadoc 里逐字写着"兜底 / 默认"等词，
        // 不剥注释会把文档里的讨论当成兜底代码）。
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }
}