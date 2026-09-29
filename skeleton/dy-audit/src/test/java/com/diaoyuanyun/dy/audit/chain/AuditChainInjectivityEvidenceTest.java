package com.diaoyuanyun.dy.audit.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 守护 {@code design-evidence/injectivity_proof.py} 这份<b>独立可复算证据</b>本身没坏。
 *
 * <h2>为什么需要守护一份"证明脚本"</h2>
 * {@code AuditChainHash} 的类注释主张："naive 的 {@code '|'.join} 不是单射，
 * 会让人跨字段边界挪字节伪造，故用转义 + 空值标记。" 这句话若只是注释，
 * 就只是一个<b>未经检验的设计主张</b>：代码看着"有转义"，谁也不能保证它真的消除了碰撞
 * （转义顺序写错、漏转义某个字符，都会写出"有转义"的代码却留着碰撞路径）。
 *
 * <p>{@code injectivity_proof.py} 用 CPython 的 {@code hashlib} 独立复算，
 * 构造出 <b>naive 实现下真实碰撞</b>的两对输入，并同时算出 v1 下它们<b>不</b>碰撞。
 * 于是"转义是承重的"有了可复现的<b>阴性对照</b>。
 *
 * <h2>本类守护的是"对照仍然成立"</h2>
 * 一个证明最容易被弄坏的方式不是被删，而是<b>悄悄变成恒真</b>：
 * 有人把脚本里的 naive 实现"顺手修好"（加上转义），于是"naive 会碰撞"的断言
 * 仍然通过（因为它已经不测 naive 了），整份证据就失去了信息量。
 * 故本类断言 <b>naive 一侧必须碰撞</b> —— 若它变成不碰撞，测试红。
 *
 * <h2>与黄金向量测试的分工</h2>
 * <ul>
 *   <li>{@link AuditChainGoldenVectorTest} 管"<b>生产实现</b>算得对不对"（对 CPython 摘要）。</li>
 *   <li>本类管"<b>证明脚本</b>本身仍能区分 naive 与 v1"（阴性对照）。
 *       若有人把生产代码的转义改回 naive 拼接，黄金向量测试会红；本类则保证
 *       那条红的给出的是正确诊断（"你掉进了碰撞路径"），而不是一句含糊的摘要不符。</li>
 * </ul>
 *
 * <h2>缺 Python 即失败（fail-closed）</h2>
 * 找不到可用的 Python 解释器 = 缺少这份证据 -> 构建必须失败。
 * 唯一逃生阀是显式 {@code -Ddy.audit.injectivity.skip=true}，绝不静默跳过
 * —— 与审计链真库门禁同一套纪律（见 {@code AuditChainGateTest}）。
 */
class AuditChainInjectivityEvidenceTest {

    private static final String SCRIPT_NAME = "injectivity_proof.py";
    private static final String SCRIPT_RELATIVE = "src/test/resources/design-evidence/" + SCRIPT_NAME;

    // ------------------------------------------------------------------
    // 定位与执行
    // ------------------------------------------------------------------

    private static Path scriptPath() {
        return AuditChainTestSupport.moduleRoot().resolve(SCRIPT_RELATIVE);
    }

    /** 与门禁一致的逃生阀语义：显式跳过才跳过。 */
    private static boolean skipRequested() {
        return Boolean.parseBoolean(System.getProperty("dy.audit.injectivity.skip", "false"));
    }

    private static void assumeNotSkipped() {
        if (skipRequested()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "单射性证据测试被显式跳过: -Ddy.audit.injectivity.skip=true");
        }
    }

    /** 在 PATH 上找一个可用的 python 解释器；找不到就按 fail-closed 处理。 */
    private static String pythonExecutable() {
        for (String candidate : List.of("python", "python3", "py")) {
            if (canRun(candidate)) {
                return candidate;
            }
        }
        fail("找不到可用的 Python 解释器（试过 python / python3 / py）—— "
                + "单射性证明脚本 " + SCRIPT_RELATIVE + " 无法执行。"
                + "这是【缺少证据】而不是【证据通过】：请安装 Python，"
                + "或显式 -Ddy.audit.injectivity.skip=true 承担跳过的后果。"
                + "（本仓库已实测 CPython 3.13 可用。）");
        return null; // 不可达
    }

    private static boolean canRun(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private record PyResult(int exitCode, String output) {
    }

    /** 以工作目录 = 脚本所在目录执行（脚本不依赖 cwd，但保持与人工执行一致）。 */
    private static PyResult runProof(String... extraArgs) throws Exception {
        Path script = scriptPath();
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExecutable());
        cmd.add(script.getFileName().toString());
        cmd.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(script.getParent().toFile())
                .redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");   // 避免中文输出在 GBK 控制台下乱码
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        assertTrue(done, "证明脚本 60s 内未结束 —— 疑似挂住（不应发生，它只做纯计算）");
        return new PyResult(p.exitValue(), out);
    }

    private static Map<String, String> parseMachineOutput(String output) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return kv;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("证据脚本作为长期资产存在（已从 target/ 移入 src/test/resources，不会被 clean 清掉）")
    void proof_script_is_a_versioned_asset_not_a_build_artifact() {
        assumeNotSkipped();
        Path script = scriptPath();

        assertTrue(Files.isRegularFile(script),
                "单射性证明脚本不在版本化资源目录中: " + script
                        + " —— 它曾被放在 target/ 下，一次 clean 就会消失，等于证据丢失");

        // 明确守护"不在 target 下"这个属性：target 是构建产物，clean 会清空。
        String abs = script.toAbsolutePath().toString().replace('\\', '/');
        assertFalse(abs.contains("/target/"),
                "证据脚本位于构建产物目录 target/ 下，会被 clean 删除: " + abs);

        // 文本里必须说明它是独立于被测 Java 的（否则读者可能误以为它调用 Java 复算）
        String text;
        try {
            text = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取证据脚本失败: " + script, e);
        }
        assertTrue(text.contains("hashlib"),
                "脚本应显式使用 CPython hashlib（独立 SHA-256 实现），否则不构成独立复算");
        assertTrue(text.contains("naive") && text.contains("v1"),
                "脚本应同时实现 naive 与 v1 两侧，阴性对照才有意义");
        assertTrue(text.contains("AuditChainHash"),
                "脚本应指明它对照的是哪个生产类，便于溯源");
    }

    @Test
    @DisplayName("阴性对照仍然成立：naive 拼接下确实碰撞，且 v1 下不碰撞（证明脚本没被悄悄改成恒真）")
    void negative_control_still_holds() throws Exception {
        assumeNotSkipped();

        PyResult r = runProof("--machine");
        Map<String, String> kv = parseMachineOutput(r.output());

        assertEquals(0, r.exitCode(),
                "证明脚本自报『证明不成立』(exit=" + r.exitCode() + ")。完整输出:\n" + r.output());

        assertEquals("true", kv.get("proof_holds"),
                "脚本未产出 proof_holds=true。完整输出:\n" + r.output());

        // ---- 阴性对照：naive 一侧【必须】碰撞 ----
        // 这是本类存在的核心理由。若有人把脚本里的 naive 实现"顺手修好"（加了转义），
        // 这两个断言会红 —— 因为那时"naive 会碰撞"已不成其为对照。
        assertEquals("true", kv.get("naive_collides_pair1"),
                "阳性对照失效：naive 拼接下 (actor='a|b',action='c') 与 (actor='a',action='b|c') "
                        + "竟然【不】碰撞 —— 说明脚本里的 naive 定义被改过（它已不是 naive 了），"
                        + "阴性对照失去信息量。完整输出:\n" + r.output());
        assertEquals("true", kv.get("naive_collides_pair2"),
                "阳性对照失效：naive 下 null 与空串竟然【不】碰撞 —— 同上，naive 定义被改过。"
                        + "完整输出:\n" + r.output());

        // ---- 目标性质：v1 一侧【必须不】碰撞 ----
        assertEquals("true", kv.get("v1_distinct_pair1"),
                "v1 规范下两个字段布局不同的记录碰撞了 —— 转义失效，这是一条可伪造路径。"
                        + "完整输出:\n" + r.output());
        assertEquals("true", kv.get("v1_distinct_pair2"),
                "v1 规范下 null 与空串碰撞了 —— 空值前缀失效。完整输出:\n" + r.output());

        // ---- 把 CPython 算出的碰撞摘要钉住 ----
        // 若这个值变了，说明脚本的 naive 构造变了（而非哈希实现变了）—— 值得人工看一眼。
        String naiveHash = kv.get("naive_collision_hash_pair1");
        assertNotNull(naiveHash, "脚本未产出 naive 碰撞摘要。完整输出:\n" + r.output());
        assertEquals(64, naiveHash.length(),
                "naive 碰撞摘要应为 64 位 hex，实际=" + naiveHash);
        assertEquals("1457488c4164d5088db6c04e7384b8c057aae49810ebaf3232d3cbb08851a1c6", naiveHash,
                "naive 碰撞摘要与首次记录的值不符 —— 说明脚本里的 naive 规范串构造被改动。"
                        + "若这是有意的（例如换了更清晰的碰撞对），请同步更新此常量并复核结论。");

        // v1 两侧摘要必须不同（把"不碰撞"落到具体值上，而不是只看布尔）
        String v1a = kv.get("v1_hash_pair1_A");
        String v1b = kv.get("v1_hash_pair1_B");
        assertNotNull(v1a, "脚本未产出 v1 摘要 A。完整输出:\n" + r.output());
        assertNotNull(v1b, "脚本未产出 v1 摘要 B。完整输出:\n" + r.output());
        assertFalse(v1a.equals(v1b),
                "v1 两条记录算出了同一个摘要 " + v1a + " —— 转义没有起到分隔字段边界的作用");
    }

    @Test
    @DisplayName("证明脚本的 v1 定义与生产实现 AuditChainHash 一致（防止证据漂移）")
    void script_v1_definition_agrees_with_production() throws Exception {
        assumeNotSkipped();

        // 脚本用 --machine 输出它自己按 v1 规范算的两条摘要；这里用【生产实现】算同样
        // 两条输入，两者必须逐字符相同。若有人改了生产代码的转义顺序/前缀而不改脚本，
        // 这条会红 —— 于是"证据脚本还在描述生产实现"这件事由测试守住，而非靠人记得。
        PyResult r = runProof("--machine");
        Map<String, String> kv = parseMachineOutput(r.output());

        String scriptA = kv.get("v1_hash_pair1_A");
        String scriptB = kv.get("v1_hash_pair1_B");
        assertNotNull(scriptA, "脚本未产出 v1 摘要 A。完整输出:\n" + r.output());
        assertNotNull(scriptB, "脚本未产出 v1 摘要 B。完整输出:\n" + r.output());

        // 与脚本 PAIR1 相同的输入、相同的固定上下文
        final String tenant = "11111111-1111-1111-1111-111111111111";
        final String tType = "customer";
        final String tId = "c-1";
        final String payload = "{\"n\":1}";
        final String prev = "0".repeat(64);

        String prodA = AuditChainHash.chainHash(tenant, "a|b", "c", tType, tId, payload, prev);
        String prodB = AuditChainHash.chainHash(tenant, "a", "b|c", tType, tId, payload, prev);

        assertEquals(scriptA, prodA,
                "证据脚本的 v1 定义与生产 AuditChainHash 不一致（输入 A）—— "
                        + "证据已在描述一个不再存在的实现，需同步（这正是「证据漂移」）");
        assertEquals(scriptB, prodB,
                "证据脚本的 v1 定义与生产 AuditChainHash 不一致（输入 B）—— 同上");

        // 顺带把生产实现"确实区分这两条输入"也钉住（脚本与实现一致 + 脚本不碰撞 => 实现不碰撞）
        assertFalse(prodA.equals(prodB),
                "生产实现把 (actor='a|b',action='c') 与 (actor='a',action='b|c') 哈希成了同一个值 —— "
                        + "字段边界可挪，这是可伪造路径");
    }

    @Test
    @DisplayName("人类可读模式也能跑通并给出结论（留档给人工复核用）")
    void human_readable_mode_runs_and_concludes() throws Exception {
        assumeNotSkipped();

        PyResult r = runProof();
        assertEquals(0, r.exitCode(),
                "人类可读模式退出码非 0。完整输出:\n" + r.output());
        assertTrue(r.output().contains("阴性对照成立: True"),
                "人类可读输出的结论行缺失或为假。完整输出:\n" + r.output());
        assertTrue(r.output().contains("碰撞对 1") && r.output().contains("碰撞对 2"),
                "人类可读输出应包含两对碰撞对的说明。完整输出:\n" + r.output());
    }
}