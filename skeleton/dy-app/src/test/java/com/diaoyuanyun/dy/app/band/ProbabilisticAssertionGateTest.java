package com.diaoyuanyun.dy.app.band;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 概率型断言门禁（本仓第 59 条）—— 禁止对<b>随机字节的文本表示</b>判 {@code contains(明文)}。
 *
 * <h2>它堵的洞</h2>
 * 原 {@code BandTelemetryEncryptionTest} 有两处断言：
 * <pre>
 *   assertFalse(raw.contains("72"),  "密文里出现了明文 '72'");
 *   assertFalse(raw.contains("95"),  "密文里出现了明文 '95'");
 * </pre>
 * 其中 {@code raw} 是 {@code CipherEnvelope.serialize()} 的产物，形态为
 * {@code dy1:算法:版本:<b64 nonce>:<b64 密文>}。判 {@code contains} 有两个问题：
 * <ol>
 *   <li><b>概率型假红</b>：base64 字符集含 {@code 0-9}，而 nonce + 密文约 40 字符随机。
 *       {@code "72"} / {@code "95"} 这对相邻字符会以约 <b>1%</b> 的概率偶然出现
 *       ⇒ 全量回归<b>不定期变红</b>，而那是判据的错、不是加密坏了。</li>
 *   <li><b>判错了对象</b>：{@code serialize()} 的文本里除密文外还含算法标识
 *       （{@code AES-GCM-256}）与 DEK 版本数字 —— 对整条文本判 {@code contains} 会
 *       映到这些<b>非密文段</b>。</li>
 * </ol>
 * 本仓把它登记为第 59 条：<b>一个会随机变红的断言，长期会侵蚀门禁自身的可信度</b>
 * —— 一旦"红了可能是运气"，就没人再认真看红。这与第 52/53/55 条构成同一族的
 * 第四种形态（另三种是：太宽 ⇒ 假绿 / 覆盖面没跟上 ⇒ 静默漏检 / 太窄 ⇒ 假红）。
 *
 * <h2>正确判法</h2>
 * 判<b>解码后的密文字节</b>（{@code CipherEnvelope.parse(raw).ciphertext()}），
 * 而不是它的 base64 文本 —— 后者是"表示"，前者才是"数据有没有被加密"的物理载体。
 *
 * <h2>三道断言</h2>
 * <ol>
 *   <li>{@link #no_test_asserts_plaintext_absence_on_base64_envelope_text()} ——
 *       扫描测试源码，禁止 {@code <x>.contains("...")} 出现在被断定为"信封文本"的变量上；</li>
 *   <li>{@link #scanner_turns_red_when_probabilistic_assertion_is_injected()} ——
 *       <b>反向验证</b>：注入原写法，同一扫描器必须变红；</li>
 *   <li>{@link #scanner_ignores_non_random_sources()} —— 不得把对<b>非随机</b>文本
 *       （源码原文、SQL、常量）的 {@code contains} 判红（第 55 条：太窄 ⇒ 假红）。</li>
 * </ol>
 */
class ProbabilisticAssertionGateTest {

    /**
     * 扫描器 —— 返回"对随机字节文本判 contains"的违规行。
     *
     * <h2>🛑 判据的三次收紧（第 55 条复发记录）</h2>
     * 本判据首版按<b>变量名白名单</b>判别（{@code raw} / {@code envelope} / {@code *_enc} …），
     * 首跑即误判 3 处<b>合法</b>写法：
     * <pre>
     *   BandAvailableDatesTest:97      assertTrue(raw.contains("N = 7"), ...)        // 源码原文
     *   DerivedProfileSourceTest:306   assertTrue(envelope.contains("DerivedRawConfig")) // 配置对象
     *   ProvisioningBoundaryGateTest:701 assertTrue(raw.contains("INSERT INTO tenant"))  // SQL 文本
     * </pre>
     * 这三处的变量<b>恰好也叫</b> {@code raw} / {@code envelope}，但它们装的是
     * <b>确定性文本</b>（源码 / SQL / 配置），判 {@code contains} 完全合法。
     * ⇒ 变量名<b>单看名字无法区分</b>"随机密文"与"确定性文本"，这是判据的根本局限。
     *
     * <p><b>正确判法：判数据来源，不判变量名。</b> 只有当同一文件里该变量
     * 被断言过"是 {@code dy1:} 信封"（{@code <var>.startsWith("dy1:")}）时，
     * 它才是<b>随机字节的文本表示</b>，其 {@code contains} 才是概率型。
     * 这是"判引用形态"而非"判标识符出现"—— 与 ④d 的 {@code PROTOCOL.<KEY>} 判法同构。
     *
     * @param repoRoot 仓库根
     * @return 违规描述列表（空 = 干净）
     */
    static List<String> scanForProbabilisticAssertions(Path repoRoot) throws IOException {
        List<String> violations = new ArrayList<>();
        Path testRoot = repoRoot.resolve("skeleton/dy-app/src/test/java");
        if (!Files.isDirectory(testRoot)) {
            testRoot = repoRoot.resolve("dy-app/src/test/java");
        }
        if (!Files.isDirectory(testRoot)) {
            return violations; // 无测试树 ⇒ 本判据不适用（不假红）
        }
        try (Stream<Path> walk = Files.walk(testRoot)) {
            for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String fn = f.getFileName().toString();
                if (fn.equals("ProbabilisticAssertionGateTest.java")
                        || fn.equals("BandTelemetryEncryptionTest.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                // 第 1 步：找出本文件里"被断言为 dy1: 信封"的变量名（数据来源判据）
                Set<String> envelopeVars = new HashSet<>();
                for (String line : lines) {
                    Matcher m = ENVELOPE_PROOF.matcher(stripComment(line));
                    if (m.find()) {
                        envelopeVars.add(m.group(1));
                    }
                }
                if (envelopeVars.isEmpty()) {
                    continue; // 本文件不含密文信封文本 ⇒ 无从产生概率型断言
                }
                // 第 2 步：只在这些变量上判 contains
                for (int i = 0; i < lines.size(); i++) {
                    String code = stripComment(lines.get(i));
                    if (code.isBlank()) {
                        continue;
                    }
                    Matcher m = CONTAINS_CALL.matcher(code);
                    while (m.find()) {
                        String var = m.group(1);
                        if (envelopeVars.contains(var)) {
                            violations.add(repoRoot.relativize(f) + ":" + (i + 1)
                                    + "  [" + var + " 已被断言为 dy1: 信封文本] " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        return violations;
    }

    /**
     * 数据来源判据：{@code <var>.startsWith("dy1:")} —— 证明该变量装的是密文信封文本。
     *
     * <p>这是本判据的锚：只有出现过这条断言的变量，其内容才是<b>随机字节的文本表示</b>。
     */
    private static final Pattern ENVELOPE_PROOF = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*startsWith\\s*\\(\\s*\"dy1:\"");

    /** 任意 {@code <var>.contains(} 调用。 */
    private static final Pattern CONTAINS_CALL = Pattern.compile("\\b(\\w+)\\s*\\.\\s*contains\\s*\\(");

    private static String stripComment(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
            return "";
        }
        int idx = line.indexOf("//");
        String s = idx >= 0 ? line.substring(0, idx) : line;
        int star = s.indexOf("/*");
        if (star >= 0) {
            s = s.substring(0, star);
        }
        return s;
    }

    @Test
    void no_test_asserts_plaintext_absence_on_base64_envelope_text() throws IOException {
        Path root = locateRepoRoot();
        List<String> violations = scanForProbabilisticAssertions(root);
        assertTrue(violations.isEmpty(),
                "测试里对【密文信封文本】判了 contains —— 那是概率型断言（第 59 条）：\n  "
                        + String.join("\n  ", violations)
                        + "\n  ⇒ base64 字符集含 0-9，随机密文会以约 1% 概率偶然含目标数字串，"
                        + "全量回归不定期变红。请改判【解码后的密文字节】："
                        + "CipherEnvelope.parse(raw).ciphertext()");
    }

    @Test
    void scanner_turns_red_when_probabilistic_assertion_is_injected(@TempDir Path tmp)
            throws IOException {
        Path testRoot = tmp.resolve("dy-app/src/test/java/com/injected");
        Files.createDirectories(testRoot);

        // 注入原写法（第 59 条的原形态）：先断言 dy1: 信封（数据来源判据成立），
        // 再对同一变量判 contains(明文数字串) —— 这就是概率型断言。
        Files.writeString(testRoot.resolve("FlakyLike.java"),
                "package com.injected;\n"
                        + "class FlakyLike {\n"
                        + "  void a(String raw) {\n"
                        + "    org.junit.jupiter.api.Assertions.assertTrue(raw.startsWith(\"dy1:\"));\n"
                        + "    org.junit.jupiter.api.Assertions.assertFalse(raw.contains(\"72\"), \"密文含明文\");\n"
                        + "  }\n"
                        + "  void b(String value_enc) {\n"
                        + "    org.junit.jupiter.api.Assertions.assertTrue(value_enc.startsWith(\"dy1:\"));\n"
                        + "    org.junit.jupiter.api.Assertions.assertFalse(value_enc.contains(\"95\"));\n"
                        + "  }\n"
                        + "}\n", StandardCharsets.UTF_8);

        List<String> violations = scanForProbabilisticAssertions(tmp);
        assertTrue(violations.size() >= 2,
                "注入两份概率型断言后扫描器只抓到 " + violations.size() + " 处 —— 判据没有牙齿: "
                        + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("raw.contains")),
                "未抓到 raw.contains(...): " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("value_enc.contains")),
                "未抓到 value_enc.contains(...): " + violations);
    }

    @Test
    void scanner_ignores_non_random_sources() {
        // 第 55 条（本判据首跑复发）：对【非随机文本】的 contains 是确定性的，不得判红。
        // 这些变量名也叫 raw / envelope，但它们装的是源码原文 / SQL / 配置对象 ——
        // 且**没有**被断言为 "dy1:" 信封。判据必须靠"数据来源"区分，不能靠变量名。
        String[] benign = {
                "assertTrue(raw.contains(\"N = 7\"), \"Javadoc 含禁止事项\");",
                "assertTrue(raw.contains(\"INSERT INTO tenant\"), \"SQL 形状\");",
                "assertTrue(envelope.contains(\"DerivedRawConfig\"), \"配置类名\");",
        };
        for (String line : benign) {
            Set<String> envelopeVars = new HashSet<>();
            Matcher proof = ENVELOPE_PROOF.matcher(line);
            while (proof.find()) {
                envelopeVars.add(proof.group(1));
            }
            Matcher c = CONTAINS_CALL.matcher(line);
            boolean flagged = false;
            while (c.find()) {
                if (envelopeVars.contains(c.group(1))) {
                    flagged = true;
                }
            }
            assertFalse(flagged,
                    "对非随机文本的确定性 contains 被误判 = 判据太窄（第 55 条：假红）: " + line);
        }

        // 而"被断言为 dy1: 信封"的变量上的 contains 必须命中
        String[] flaggedLines = {
                "assertTrue(raw.startsWith(\"dy1:\"));\nassertFalse(raw.contains(\"72\"));",
                "assertTrue(value_enc.startsWith(\"dy1:\"));\nassertFalse(value_enc.contains(\"95\"));",
        };
        for (String src : flaggedLines) {
            Set<String> envelopeVars = new HashSet<>();
            Matcher proof = ENVELOPE_PROOF.matcher(src);
            while (proof.find()) {
                envelopeVars.add(proof.group(1));
            }
            Matcher c = CONTAINS_CALL.matcher(src);
            boolean flagged = false;
            while (c.find()) {
                if (envelopeVars.contains(c.group(1))) {
                    flagged = true;
                }
            }
            assertTrue(flagged,
                    "信封文本变量上的 contains 未被命中 ⇒ 判据没牙齿: " + src.replace("\n", " / "));
        }
    }

    private static Path locateRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("contract/openapi-v1.0.0.yaml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new AssertionError("无法定位仓库根（需存在 contract/openapi-v1.0.0.yaml）");
    }
}