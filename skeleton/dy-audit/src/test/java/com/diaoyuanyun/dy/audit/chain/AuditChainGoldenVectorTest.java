package com.diaoyuanyun.dy.audit.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链哈希规范的黄金向量交叉验证 —— 本任务"禁止自证式测试"的核心落点。
 *
 * <h2>为什么必须有这个测试类（而不是在集成测试里断言"写入后再读回来一致"）</h2>
 * "写入后用<b>同一个实现</b>重算，结果一致"只证明了实现与其自身一致。
 * 一个把字段顺序写反、把 {@code '|'} 转义漏掉、或用平台默认编码（本机是 GBK）取字节的实现，
 * 在那个断言下依然全绿 —— 因为写和算用的是同一套（错的）规则。
 * 真链条要能被<b>第三方独立复算</b>，所以这里的对照物是
 * {@code src/test/resources/audit-chain-golden-v1.txt} 里的摘要，而那些摘要由
 * {@code audit-chain-golden-v1.generator.py} 用 <b>CPython 的 hashlib</b> 算出 ——
 * 另一个 SHA-256 实现、另一份按 javadoc 规格重写的规范串布局，全程不调用 Java 代码。
 *
 * <h2>这些向量各自堵的是哪一类错误</h2>
 * <table border="1">
 *   <tr><th>向量</th><th>不做对就抓什么</th></tr>
 *   <tr><td>{@code unicode_payload_link}</td>
 *       <td>字符集：GBK 默认编码会把中文 payload 哈希成与参考不同的值。
 *           本机 {@code mvn -v} 报 {@code platform encoding: GBK}，这条路径真实可达。</td></tr>
 *   <tr><td>{@code payload_with_delimiter_is_escaped} / {@code ..._backslash_...} /
 *           {@code ..._newline_...}</td>
 *       <td>转义表漏项，以及转义<b>顺序</b>写反（先转 {@code '|'} 会把生成的
 *           {@code \|} 里的反斜杠二次转义）。</td></tr>
 *   <tr><td>{@code injectivity_actor_holds_delimiter} vs
 *           {@code injectivity_action_holds_delimiter}</td>
 *       <td><b>规范串是否单射</b>。这两条记录字段不同（{@code actor="a|b",action="c"}
 *           与 {@code actor="a",action="b|c"}），不转义就会撞成同一摘要 ——
 *           那是一条真实的伪造路径：能把字节挪过字段边界而不改变哈希。</td></tr>
 *   <tr><td>{@code null_payload_link} vs {@code literal_null_marker_payload_link} vs
 *           {@code empty_payload_link_must_differ_from_null}</td>
 *       <td>"未采到 payload" / "payload 字面量恰为 {@code <null>}" / "payload 为空串"
 *           三者互相碰撞。空值前缀（{@code ~null~} vs {@code ~s~}）就是为此而设。</td></tr>
 *   <tr><td>{@code tampered_*}</td>
 *       <td>摘要对内容的敏感性（防止"摘要是个无关常数"这种低级但会全绿的错误）。</td></tr>
 * </table>
 *
 * <p>本类<b>不连数据库</b>，因此不能证明任何"库里发生的事"；那些由
 * {@code AuditChainGateTest} 负责。两者分工刻意分开：规范正确性（本类）
 * 与行为可观察性（集成测试），失败时能直接定位是哪一类。
 */
class AuditChainGoldenVectorTest {

    private static final String FIXTURE = "/audit-chain-golden-v1.txt";

    /** 一条向量的全部期望值。 */
    private record Vector(String name,
                          String tenantId,
                          String actor,
                          String action,
                          String targetType,
                          String targetId,
                          String payload,
                          String prevHash,
                          String canonical,
                          String hash) {

        /** 用实现算出的规范串与摘要，与该向量的期望值比对。 */
        void assertReproducedBy(String canonical, String hash) {
            assertEquals(this.canonical, canonical,
                    "规范串与独立生成物不一致 [" + name + "]"
                            + "（字段顺序 / 前缀 / 转义表 / 转义顺序 其一被改）");
            assertEquals(this.hash, hash,
                    "摘要与 CPython hashlib 独立算出的值不一致 [" + name + "]"
                            + " —— 若仅 unicode 向量失败，首要怀疑默认字符集不是 UTF-8");
        }

        String implCanonical() {
            return AuditChainHash.canonicalV1(tenantId, actor, action, targetType, targetId, payload, prevHash);
        }

        String implHash() {
            return AuditChainHash.chainHash(tenantId, actor, action, targetType, targetId, payload, prevHash);
        }
    }

    private static String b64Decode(String raw) {
        // fixture 以 newline="\n" 写出，但工作树若被 CRLF 化会残留 '\r' —— 显式剥离，
        // 否则 base64 解码会在末尾多出一个 \r，向量整体失配且极难排查。
        return new String(Base64.getDecoder().decode(raw.replace("\r", "")), StandardCharsets.UTF_8);
    }

    private static Map<String, Vector> loadVectors() throws IOException {
        Map<String, String> header = new LinkedHashMap<>();
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> cur = null;

        try (InputStream is = AuditChainGoldenVectorTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(is, "缺少黄金向量文件 " + FIXTURE + "；应由 audit-chain-golden-v1.generator.py 生成");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.equals("[vector]")) {
                    cur = new LinkedHashMap<>();
                    blocks.add(cur);
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1);
                if (cur == null) {
                    header.put(key, value);
                } else {
                    cur.put(key, value);
                }
            }
        }

        // 头部与实现必须一致：不一致说明 fixture 是按另一版规范生成的，全部向量都不能用。
        assertEquals(AuditChainHash.CANONICAL_V1, header.get("canonical_version"),
                "fixture 的规范版本与实现不符");
        assertEquals(AuditChainHash.GENESIS_PREV_HASH, header.get("genesis_prev_hash"),
                "fixture 的创世值与实现不符");
        assertEquals(AuditChainHash.NULL_PREFIX, header.get("null_field_prefix"),
                "fixture 的空值前缀与实现不符");
        assertEquals(AuditChainHash.STRING_PREFIX, header.get("string_field_prefix"),
                "fixture 的字符串前缀与实现不符");

        Map<String, Vector> byName = new LinkedHashMap<>();
        for (Map<String, String> b : blocks) {
            String payloadToken = b.get("payload_b64");
            String payload = "<NULL>".equals(payloadToken) ? null : b64Decode(payloadToken);
            Vector v = new Vector(
                    b.get("name"),
                    b64Decode(b.get("tenant_id_b64")),
                    b64Decode(b.get("actor_b64")),
                    b64Decode(b.get("action_b64")),
                    b64Decode(b.get("target_type_b64")),
                    b64Decode(b.get("target_id_b64")),
                    payload,
                    b64Decode(b.get("prev_hash_b64")),
                    b64Decode(b.get("canonical_b64")),
                    b.get("hash"));
            byName.put(v.name(), v);
        }
        assertFalse(byName.isEmpty(), "fixture 未解析出任何向量");
        return byName;
    }

    private static Vector require(Map<String, Vector> vectors, String name) {
        Vector v = vectors.get(name);
        assertNotNull(v, "fixture 缺少向量 " + name + "（该向量守护的性质将失去覆盖）");
        return v;
    }

    @Test
    @DisplayName("黄金向量：Java 实现产出与 CPython hashlib 独立算出的摘要逐条一致（12 条，含中文/转义/空值）")
    void java_matches_independently_generated_cpython_vectors() throws IOException {
        Map<String, Vector> vectors = loadVectors();
        assertTrue(vectors.size() >= 12,
                "向量数量过少(" + vectors.size() + ")，覆盖不住规范的关键分支");

        for (Vector v : vectors.values()) {
            v.assertReproducedBy(v.implCanonical(), v.implHash());
            assertTrue(AuditChainHash.looksLikeHash(v.implHash()),
                    "摘要应为 64 位小写 hex [" + v.name() + "]");
            assertEquals(AuditChainHash.HASH_HEX_LENGTH, v.implHash().length());
        }
    }

    @Test
    @DisplayName("规范串前缀：字段必须先带 ~null~ / ~s~ 前缀再转义（否则 null 会与某字符串撞车）")
    void every_field_carries_a_null_or_string_prefix() throws IOException {
        Vector v = require(loadVectors(), "null_payload_link");
        String canonical = v.implCanonical();

        // 规范串行首是版本标签，行尾是 7 个字段（6 个分隔符隔开）
        String[] rows = canonical.split("\n", 2);
        assertEquals(AuditChainHash.CANONICAL_V1, rows[0], "规范串首行必须是版本标签");
        List<String> fields = splitOnUnescapedDelimiter(rows[1]);
        assertEquals(7, fields.size(),
                "规范串应有 7 个字段（tenant_id/actor/action/target_type/target_id/payload/prev_hash），"
                        + "实际 " + fields.size() + " —— 转义漏项会让分隔符数量变化");

        for (int i = 0; i < fields.size(); i++) {
            assertTrue(fields.get(i).startsWith(AuditChainHash.STRING_PREFIX)
                            || fields.get(i).equals(AuditChainHash.NULL_PREFIX),
                    "第 " + i + " 个字段未带 null/string 前缀: '" + fields.get(i) + "'");
        }
        // payload 为 null 的那个字段必须编码成 ~null~
        assertTrue(canonical.contains("|" + AuditChainHash.NULL_PREFIX + "|"),
                "null payload 应编码为裸的 ~null~ 字段（不带 ~s~ 前缀）");
    }

    /**
     * 按【未被转义的】 {@code '|'} 切分。
     *
     * <p>不能用 {@code split("(?<!\\\\)\\|")}：那是"前面不是反斜杠"的单字符后行断言，
     * 而 {@code "\|"}（转义出来的分隔符）与 {@code "\\|"}（转义出来的反斜杠 + 裸分隔符）
     * 在这种后行断言下无法区分 —— 前者应保持在一个字段内，后者应断开。
     * 正确规则需要<b>从左到右扫描并跟踪转义状态</b>，这也正是任何独立复算者该做的事。
     */
    private static List<String> splitOnUnescapedDelimiter(String record) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (escaping) {
                cur.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                cur.append(c);
                escaping = true;
                continue;
            }
            if (c == '|') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    @Test
    @DisplayName("单射性：actor 含 '|' 与 action 含 '|' 的两条不同记录，摘要必须不同（否则可跨字段挪字节）")
    void canonical_string_is_injective_across_field_boundaries() throws IOException {
        Map<String, Vector> vectors = loadVectors();
        Vector a = require(vectors, "injectivity_actor_holds_delimiter");
        Vector b = require(vectors, "injectivity_action_holds_delimiter");

        // 两条记录的字段确实不同：(actor="a|b", action="c")  vs  (actor="a", action="b|c")
        assertNotEquals(a.actor(), b.actor());
        assertNotEquals(a.action(), b.action());
        assertEquals("c", a.action());
        assertEquals("b|c", b.action());
        assertEquals("a|b", a.actor());
        assertEquals("a", b.actor());

        // 其余字段相同
        assertEquals(a.tenantId(), b.tenantId());
        assertEquals(a.targetType(), b.targetType());
        assertEquals(a.targetId(), b.targetId());
        assertEquals(a.payload(), b.payload());
        assertEquals(a.prevHash(), b.prevHash());

        // 结论：不转义就会撞车；本规范必须让它们分开。
        assertNotEquals(a.canonical(), b.canonical(),
                "规范串相同 —— 转义失效：这是可跨字段挪字节的伪造路径");
        assertNotEquals(a.hash(), b.hash(),
                "两条不同记录的摘要相同 —— 一条记录可以在不改动哈希的前提下被改成另一条");
        // 且实现复现上述两条（证明它确实按规范做了转义，不是恰好没撞）
        assertNotEquals(a.implHash(), b.implHash(),
                "实现让两条不同记录产生了同一摘要 —— 转义未生效");
        a.assertReproducedBy(a.implCanonical(), a.implHash());
        b.assertReproducedBy(b.implCanonical(), b.implHash());
    }

    @Test
    @DisplayName("转义表：含 '|' / '\\' / 换行 的 payload 都能安全落链（且与未转义时的值不同）")
    void escaping_covers_delimiter_backslash_and_newline() throws IOException {
        Map<String, Vector> vectors = loadVectors();
        Vector delimiter = require(vectors, "payload_with_delimiter_is_escaped");
        Vector backslash = require(vectors, "payload_with_backslash_is_escaped");
        Vector newline = require(vectors, "payload_with_newline_is_escaped");

        for (Vector v : List.of(delimiter, backslash, newline)) {
            v.assertReproducedBy(v.implCanonical(), v.implHash());
        }

        // 【要断言的性质是"字段边界不可移动"，不是"转义后的串里不含 '|' 字符"】
        // 后者是错的：转义的结果恰恰是 '\\|' —— 里面<b>仍然</b>有一个 '|' 字符，
        // 只是它前面多了反斜杠。所以"不含 '|'"这条断言永远不成立，测的是个假命题。
        // 正确的判据：按【未转义的】分隔符切分，转义后的字段值必须仍是一整段（1 段）。
        // 那才说明边界没被挪动 —— 也正是单射性的机械含义。
        assertEquals(1, splitOnUnescapedDelimiter(AuditChainHash.escape("a|b")).size(),
                "含 '|' 的值转义后仍被切成多段 —— 分隔符未被转义，字段边界可被移动");
        assertEquals(1, splitOnUnescapedDelimiter(AuditChainHash.escape("p1|p2|p3")).size(),
                "含多个 '|' 的值转义后仍被切分");
        assertEquals(1, splitOnUnescapedDelimiter(AuditChainHash.escape("line1\nline2")).size(),
                "含换行的值转义后仍被切分");
        assertEquals(1, splitOnUnescapedDelimiter(AuditChainHash.escape("\\|")).size(),
                "反斜杠 + 分隔符 的组合被切开 —— 转义顺序或转义状态跟踪错误");

        // 反向自证：未转义时确实会被切分。否则上面几条是恒真的，没有信息量。
        assertEquals(2, splitOnUnescapedDelimiter("a|b").size(),
                "未转义的值未被切分 —— 切分器本身失效，上面几条断言全都无意义");
        assertEquals(3, splitOnUnescapedDelimiter("p1|p2|p3").size());

        // 转义表逐项核对（值层面，人可读）
        assertNotEquals(AuditChainHash.escape("a|b"), "a|b");
        assertEquals("a\\|b", AuditChainHash.escape("a|b"));
        assertEquals("line1\\nline2", AuditChainHash.escape("line1\nline2"));
        // 转义顺序：反斜杠必须先转，否则 "\" 与 "|" 组合会被二次转义成 "\\\\|"
        assertEquals("\\\\\\|", AuditChainHash.escape("\\|"),
                "转义顺序错误：应先转反斜杠再转 '|'，否则 '\\|' 会变成 '\\\\|'");
        assertEquals("\\|", AuditChainHash.escape("|"));
        assertEquals("\\\\", AuditChainHash.escape("\\"));
    }

    @Test
    @DisplayName("空值语义：null / 字面量 '<null>' / 空串 三者摘要互不相同")
    void null_empty_and_literal_marker_must_all_differ() throws IOException {
        Map<String, Vector> vectors = loadVectors();
        Vector nullPayload = require(vectors, "null_payload_link");
        Vector literal = require(vectors, "literal_null_marker_payload_link");
        Vector emptyPayload = require(vectors, "empty_payload_link_must_differ_from_null");

        // 三条记录的其余字段完全相同，唯一差别是 payload
        assertEquals(nullPayload.tenantId(), literal.tenantId());
        assertEquals(nullPayload.tenantId(), emptyPayload.tenantId());
        assertEquals(nullPayload.actor(), emptyPayload.actor());
        assertEquals(nullPayload.prevHash(), emptyPayload.prevHash());
        assertEquals(literal.prevHash(), emptyPayload.prevHash());

        assertNotEquals(nullPayload.hash(), literal.hash(),
                "null payload 与字面量 '<null>' payload 摘要相同 —— 空值编码与字符串编码混为一谈");
        assertNotEquals(nullPayload.hash(), emptyPayload.hash(),
                "null payload 与空串 payload 摘要相同 —— 两种语义被归一");
        assertNotEquals(literal.hash(), emptyPayload.hash());

        for (Vector v : List.of(nullPayload, literal, emptyPayload)) {
            v.assertReproducedBy(v.implCanonical(), v.implHash());
        }
        // 实现侧的前缀区分必须可见
        assertEquals(AuditChainHash.NULL_PREFIX, AuditChainHash.field(null));
        assertEquals(AuditChainHash.STRING_PREFIX + "<null>", AuditChainHash.field("<null>"));
        assertEquals(AuditChainHash.STRING_PREFIX, AuditChainHash.field(""));
    }

    @Test
    @DisplayName("篡改向量：改动 payload / prev_hash 都会改变摘要（链的敏感性，不是常数）")
    void tamper_vectors_must_differ_from_the_pristine_ones() throws IOException {
        Map<String, Vector> vectors = loadVectors();
        Vector pristine = require(vectors, "unicode_payload_link");
        Vector tamperedPayload = require(vectors, "tampered_payload_of_unicode_link");
        Vector tamperedPrev = require(vectors, "tampered_prev_hash_of_unicode_link");

        assertNotEquals(pristine.hash(), tamperedPayload.hash(),
                "改了 payload 摘要却没变 —— 摘要是个常数，链无意义");
        assertNotEquals(pristine.hash(), tamperedPrev.hash(),
                "改了 prev_hash 摘要却没变 —— prev_hash 未参与哈希，链是断的");

        tamperedPayload.assertReproducedBy(tamperedPayload.implCanonical(), tamperedPayload.implHash());
        tamperedPrev.assertReproducedBy(tamperedPrev.implCanonical(), tamperedPrev.implHash());
    }

    @Test
    @DisplayName("链的属性：prev_hash 参与哈希 ⇒ 换掉前驱必然改变本记录摘要（删除中间记录可被察觉）")
    void changing_a_link_hash_ripples_into_its_successor() throws IOException {
        Vector v = require(loadVectors(), "unicode_payload_link");
        String fakePrev = "f".repeat(64);
        assertNotEquals(fakePrev, v.prevHash());

        String withRealPrev = AuditChainHash.chainHash(
                v.tenantId(), v.actor(), v.action(), v.targetType(), v.targetId(), v.payload(), v.prevHash());
        String withFakePrev = AuditChainHash.chainHash(
                v.tenantId(), v.actor(), v.action(), v.targetType(), v.targetId(), v.payload(), fakePrev);
        assertNotEquals(withRealPrev, withFakePrev,
                "换掉 prev_hash 后本记录摘要不变 —— 链式绑定失效，删掉中间记录将无法被察觉");
    }

    @Test
    @DisplayName("摘要形状：looksLikeHash 只认 64 位小写 hex（校验器靠它区分『断链』与『数据坏了』）")
    void hash_shape_predicate_is_strict() {
        assertTrue(AuditChainHash.looksLikeHash("0".repeat(64)));
        assertTrue(AuditChainHash.looksLikeHash("0123456789abcdef".repeat(4)));
        assertFalse(AuditChainHash.looksLikeHash("0".repeat(63)), "短一位不应通过");
        assertFalse(AuditChainHash.looksLikeHash("0".repeat(65)), "长一位不应通过");
        assertFalse(AuditChainHash.looksLikeHash("A".repeat(64)), "大写不应通过（规范是小写 hex）");
        assertFalse(AuditChainHash.looksLikeHash("g".repeat(64)), "非 hex 字符不应通过");
        assertFalse(AuditChainHash.looksLikeHash(null));
    }
}