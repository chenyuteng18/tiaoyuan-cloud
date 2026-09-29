package com.diaoyuanyun.dy.audit.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 审计链哈希的【规范 (canonical) 定义】—— 全系统唯一的哈希真相源。
 *
 * <h1>为何需要一份规范，而不是"随手拼一拼再 SHA-256"</h1>
 * 审计链的价值在于"判定可回放"(ADR-09 / ADR-11)：第三方要在<b>不看代码</b>的前提下，
 * 拿数据库里的行 + 这份规范，独立重算出同一个哈希。若拼接方式只存在于 Java 实现里，
 * 那校验器就成了它自己的裁判 —— 改一处拼接顺序，链"依然有效"，篡改照样溜过去。
 * 因此这里把格式<b>冻结</b>成 {@link #CANONICAL_V1} 标签，并由
 * {@code AuditChainGoldenVectorTest} 用<b>独立于本类</b>的 Python {@code hashlib}
 * 预计算结果做交叉验证（生成器见 {@code src/test/resources/audit-chain-golden-v1.generator.py}）。
 *
 * <h1>规范版本 v1（冻结；改动 = 全库链失效，必须走新版本号）</h1>
 * <pre>
 *  field(v) = v == null ? "~null~" : "~s~" + escape(v)
 *  escape(v):  '\' -> '\\'   '|' -> '\|'   LF -> '\n'   CR -> '\r'      （顺序固定，先反斜杠）
 *  record   = field(tenant_id) | field(actor) | field(action) | field(target_type)
 *           | field(target_id) | field(payload) | field(prev_hash)
 *  subject  = CANONICAL_V1 + '\n' + record
 *  hash     = hex_lower( SHA-256( subject.UTF-8 ) )
 * </pre>
 *
 * <h2>为什么必须转义 + 加空值标记：因为"两条不同记录同一摘要"是伪造路径</h2>
 * 直觉版本（裸拼字段、用 {@code '|'} 当分隔符）有一个致命缺陷 —— <b>它不是单射</b>：
 * <pre>
 *   (actor="a|b", action="c")   -&gt; "…|a|b|c|…"
 *   (actor="a",   action="b|c") -&gt; "…|a|b|c|…"     ← 同一个规范串、同一个摘要
 * </pre>
 * 于是一个能改 {@code actor}/{@code action} 的人，可以把字节挪过字段边界、
 * 让记录内容变了而哈希不变 —— 篡改不被发现。同理 {@code payload=null} 与
 * {@code payload="&lt;null&gt;"} 若共用一个字面标记，也会撞在一起。
 *
 * <p>本规范用两条规则把规范串做成<b>单射</b>（不同字段取值必然得到不同串）：
 * <ol>
 *   <li><b>每个字段先转义</b>，使分隔符 {@code '|'} 与换行<b>永远不出现在转义后的字段值里</b>；
 *       故分隔符位置固定、字段边界无歧义。</li>
 *   <li><b>空值与字符串用不同前缀区分</b>（{@code ~null~} vs {@code ~s~}），
 *       使"未采到 payload"与"payload 恰好是某个字符串"永不混淆 ——
 *       不再依赖"某个字面量不会被人真的用作值"这种约定。</li>
 * </ol>
 * 因为已无歧义，写入路径<b>无需</b>拒绝任何字符：含 {@code '|'} 的 JSON payload、
 * 含换行的备注，都能安全落链。这比"靠写入侧拒绝"更好：
 * 拒绝会在合法业务数据上留下"写不进审计"的坑，而审计写不进去本身就是合规缺陷。
 *
 * <h2>可独立复算</h2>
 * 规则只有转义表 + 一句拼接，任何语言/工具（Python {@code hashlib}、{@code psql}、
 * shell）都能复算；不依赖 Jackson 的字段序、缩进或数字格式等实现细节。
 * <b>编码固定为 UTF-8</b>：本机平台编码是 GBK（{@code mvn -v} 有报），
 * 若用默认编码取字节，中文 payload 的摘要会与第三方算出的值分叉 ——
 * 黄金向量里的 {@code unicode_payload_link} 就是钉死这一点的探针。
 *
 * <h2>版本历史</h2>
 * v1 在首次交付<b>之前</b>被修正过一次：最初版本用裸 {@code '|'} 定界且不区分
 * null/空串（当时打算靠"写入侧拒绝含定界符的值"来兜底，并把该残差记录为已知代价）。
 * 那个方案对 {@code payload} 不成立 —— payload 是 JSON 原文，含 {@code '|'} 完全正常，
 * 拒绝它等于让合法审计写不进去。故改为上述单射设计，把残差<b>消除</b>而不是<b>围堵</b>。
 * 由于此前没有任何链落库，v1 直接采用最终设计，未新增版本号。
 */
public final class AuditChainHash {

    /** 规范版本标签。v1 冻结；任何拼接/转义语义变化都必须换成新标签（如 CANONICAL_V2）。 */
    public static final String CANONICAL_V1 = "dy-audit-chain/v1";

    /**
     * 链首约定值。
     *
     * <p>DDL 注释写明"{@code prev_hash} … 链首为空串"（
     * {@code dy-audit/src/main/resources/db/audit_log.sql} 与
     * {@code dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql} 同款）。
     * 本实现遵从"链首有固定约定值"这一<b>意图</b>，但取值定为 64 个 {@code '0'}：
     * 空串会让"{@code prev_hash} 缺失/未被赋值"和"这是链首"两种状态不可区分，
     * 而全 0 是显式的"我前面没有任何东西"，且与 {@code CHAR(64)} 等宽便于肉眼比对。
     * <p>该字面差异已在 {@code db/audit_log.sql} 的列注释里登记，避免下一位读者以为实现漏了。
     */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    /** 哈希摘要的十六进制长度（SHA-256 = 32 字节 = 64 个 hex 字符），与 {@code CHAR(64)} 对应。 */
    public static final int HASH_HEX_LENGTH = 64;

    /** {@code null} 字段值的编码前缀。与 {@link #STRING_PREFIX} 必须不同，否则 null 会与某字符串撞车。 */
    public static final String NULL_PREFIX = "~null~";

    /** 非 {@code null} 字段值的编码前缀。见类注释"为什么必须转义 + 加空值标记"。 */
    public static final String STRING_PREFIX = "~s~";

    private AuditChainHash() {
    }

    /**
     * 计算一跳链哈希：{@code SHA-256(canonicalV1(字段, prev_hash))}。
     *
     * @param tenantId   租户 id（审计行不可无租户，ADR-02 第 1 层）
     * @param actor      操作者
     * @param action     动作类型
     * @param targetType 目标实体类型
     * @param targetId   目标实体 id
     * @param payload    事件详情（可为 null，编码与空串区分）
     * @param prevHash   前一条记录的 {@code hash}；链首用 {@link #GENESIS_PREV_HASH}
     * @return 64 位小写十六进制摘要
     */
    public static String chainHash(String tenantId,
                                   String actor,
                                   String action,
                                   String targetType,
                                   String targetId,
                                   String payload,
                                   String prevHash) {
        return sha256Hex(canonicalV1(tenantId, actor, action, targetType, targetId, payload, prevHash));
    }

    /**
     * 规范串 v1 —— 权威定义，复制到任何语言都应得同一结果（见类注释）。
     */
    public static String canonicalV1(String tenantId,
                                     String actor,
                                     String action,
                                     String targetType,
                                     String targetId,
                                     String payload,
                                     String prevHash) {
        return CANONICAL_V1 + "\n"
                + field(tenantId) + "|"
                + field(actor) + "|"
                + field(action) + "|"
                + field(targetType) + "|"
                + field(targetId) + "|"
                + field(payload) + "|"
                + field(prevHash);
    }

    /**
     * 单字段编码：先加空值/字符串前缀，再转义。
     *
     * <p>转义顺序固定（<b>先处理反斜杠</b>）：若先转 {@code '|'} 再转 {@code '\'}，
     * 之前生成的 {@code \|} 里的反斜杠会被二次转义成 {@code \\|}，结果随实现顺序而变
     * —— 那就不是规范而是"这个实现的习惯"了。
     */
    public static String field(String value) {
        return value == null ? NULL_PREFIX : STRING_PREFIX + escape(value);
    }

    /** 转义表见类注释；顺序固定，先反斜杠。 */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\|");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 是否是形状合法的链哈希（64 位小写 hex）。用于校验器区分"断链"与"数据坏了"。 */
    public static boolean looksLikeHash(String value) {
        if (value == null || value.length() != HASH_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static String sha256Hex(String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，取不到等于 JVM 坏了 —— 不能静默降级为"不校验"。
            throw new IllegalStateException("JVM 不提供 SHA-256，审计链无法建立", e);
        }
    }
}