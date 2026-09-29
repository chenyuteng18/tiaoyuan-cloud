package com.diaoyuanyun.dy.app.crypto.domain;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * <b>密钥材料自身的加密</b> —— 用主密钥（{@code DY_MASTER_KEY}）包裹 KEK。
 *
 * <h2>它解决的是哪一个问题</h2>
 * 架构规格书 §8.4 要求「DEK 存 KMS（信封加密）」。本仓不引入外部 KMS 客户端
 * （架构文档第 6 条"明确筑底、拒绝超前"），改为 KEK 落 {@code tenant_kek} 表。
 * 但 ADR-08 有一条不可退让的纪律：<b>机密不得入 DB 配置表</b>
 * （"DB 表是业务配置的真相源，而密钥是机密，必须走环境变量 / 密钥管理"）。
 * 两者只能这样调和：<b>真正的主密钥走环境变量；DB 里只存它加出来的中间态</b>。
 * 本类就是那个"加出来"的动作。
 *
 * <h2>为什么它必须独立成一个类，而不是塞进 DbTenantKekProvider</h2>
 * 因为"用什么包裹 KEK"与"KEK 存在哪张表"是两个可以各自变化的问题：
 * 换外部 KMS 时，{@code DbTenantKekProvider} 整个被替换掉，本类不一定；
 * 反过来换存储形态（表 → 外部 KMS 的另一形态）时亦然。
 * 把它并入 provider，会让"KMS 落地形态"这一次替换同时改到密码学代码 ——
 * 而密码学代码是<b>最不该与存储一起变</b>的部分。
 *
 * <h2>与 dy-crypto 的分工（勿混）</h2>
 * <pre>
 *   dy-crypto.FieldCipher       业务字段    ← 用 per-subject DEK 加密      （已有，28 测试全绿）
 *   dy-crypto.SubjectKeyStore   主体 DEK    ← 用租户 KEK 包裹              （Db 实现见 DbSubjectKeyStore）
 *   db.KeyMaterialCipher        租户 KEK    ← 用 masterKey 包裹            （本类）★ 最外一层
 * </pre>
 * 三层同构：每一层都用"上一层的密钥"去包裹"下一层的密钥"，最外层是环境变量里的主密钥。
 *
 * <h2>🛑 诚实登记本层提供的防护与不提供的防护</h2>
 * <ul>
 *   <li><b>提供</b>：数据库备份 / 只读副本 / SQL 注入读取 这三种最常见的泄漏面，
 *       拿到的只是"被 masterKey 加密后的 KEK"，不含任何可直接使用的密钥；
 *       更不含健康数据明文（那还需再过 DEK 与字段层两道）。</li>
 *   <li><b>不提供</b>：若攻击者同时拿到「DB」与「环境变量 DY_MASTER_KEY」，
 *       全部密钥可还原。这是"DB 表作真相源"选型的固有边界（见 V11 文件头）。</li>
 * </ul>
 * 把边界写在这里而不是含糊带过，是因为"以为加密解决了它其实没解决"比"知道没解决"
 * 危险得多 —— 前者会导致防护措施被错误地省略。
 */
public final class KeyMaterialCipher {

    /** KEK 包裹用的 AAD 前缀。🛑 改动它会让全部历史 KEK 解不开（AAD 参与认证）。 */
    public static final String KEK_AAD_PREFIX = "kek-wrap-v1";

    /** 主密钥长度（AES-256）。 */
    public static final int MASTER_KEY_BYTES = 32;

    private final byte[] masterKey;
    private final AlgorithmProvider algorithm;

    /**
     * @param masterKey 32 字节主密钥（由环境变量 {@code DY_MASTER_KEY} 解析而来）
     * @param algorithm 包裹 KEK 用的算法提供者（来自 {@code AlgorithmRegistry}，
     *                  <b>不写死</b>：算法换代时本类零改动）
     */
    public KeyMaterialCipher(byte[] masterKey, AlgorithmProvider algorithm) {
        if (masterKey == null || masterKey.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException("主密钥必须是 " + MASTER_KEY_BYTES
                    + " 字节（实际 " + (masterKey == null ? "null" : masterKey.length)
                    + "）。🛑 宁可启动失败，也不要退化成『密钥不合法时明文写入』—— "
                    + "那会让加密在某次配置事故后静默失效，而所有测试仍然是绿的。");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("算法提供者为 null —— 拒绝猜测默认算法");
        }
        this.masterKey = masterKey.clone();
        this.algorithm = algorithm;
    }

    /** 用 masterKey 包裹后的 KEK 材料。 */
    public record KekSeal(byte[] nonce, byte[] sealed, String aadText) {
    }

    /**
     * 包裹一把裸 KEK。
     *
     * <p>AAD 绑定 {@code (tenantId, kekId, kekSeq)}：把一行 KEK 材料挪到别的租户或
     * 别的版本名下，必然解不开。这与 dy-crypto 对业务字段与 DEK 包裹用的是同一套纪律
     * （见 {@code AadBinding}）—— 归属绑定必须贯穿每一层，而不只在最外层做一次。
     */
    public KekSeal encryptKek(String tenantId, String kekId, int kekSeq, byte[] rawKek) {
        if (rawKek == null || rawKek.length == 0) {
            throw new IllegalArgumentException("裸 KEK 为空 —— 拒绝包裹一个空密钥");
        }
        String aad = kekAadText(tenantId, kekId, kekSeq);
        AlgorithmProvider.EncryptResult r =
                algorithm.encrypt(masterKey, rawKek, aad.getBytes(StandardCharsets.UTF_8));
        return new KekSeal(r.nonce(), r.ciphertext(), aad);
    }

    /**
     * 解出一把裸 KEK。
     *
     * <p>🛑 参数刻意是<b>离散的库列值</b>而非某个仓储 record：这样本类不依赖
     * {@code CryptoKeyLedger}（存储形态），换存储时密码学代码不动。
     *
     * @throws com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException
     *         masterKey 不匹配 / AAD 不匹配 / 材料被篡改 —— 三种情形都表现为认证失败，
     *         这正是 AEAD 的预期行为，也正因如此<b>不得</b>靠"能不能解开"来区分它们
     */
    public byte[] decryptKek(byte[] nonce, byte[] sealed, String aadText) {
        if (aadText == null || aadText.isBlank()) {
            throw new IllegalArgumentException("AAD 文本为空 —— 认证串缺失，拒绝解密");
        }
        return algorithm.decrypt(masterKey, nonce, sealed, aadText.getBytes(StandardCharsets.UTF_8));
    }

    /** KEK 包裹的 AAD 构造器（写入与读取共用，保证逐字一致）。 */
    public static String kekAadText(String tenantId, String kekId, int kekSeq) {
        if (tenantId == null || tenantId.isBlank()
                || kekId == null || kekId.isBlank() || kekSeq < 1) {
            throw new IllegalArgumentException("KEK 归属三元组不完整: tenant=" + tenantId
                    + " kekId=" + kekId + " seq=" + kekSeq);
        }
        return KEK_AAD_PREFIX + "|" + tenantId + "|" + kekId + "|" + kekSeq;
    }

    /**
     * 解析环境变量里的主密钥。
     *
     * <p>接受两种写法：<b>64 位十六进制</b>或 <b>base64</b>（长度解出来必须是 32 字节）。
     * 两种都收，是为了不迫使部署方为一个格式转换写脚本 —— 而"为了省事直接用弱密钥"
     * 才是真正要避免的结果。
     *
     * <p>🛑 解析失败即抛（fail-closed）。绝不"解析不了就用默认值"：
     * 那会让一次环境变量拼错变成"加密用另一把主密钥"，于是此前加密的数据全部解不开，
     * 而症状看起来像"数据损坏"。
     */
    public static byte[] parseMasterKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("未提供主密钥（dy.crypto.master-key / DY_MASTER_KEY）。"
                    + "🛑 加密路径必须 fail-closed：宁可启动失败，"
                    + "也不要退化为明文落库");
        }
        String t = raw.trim();
        byte[] out = null;
        if (t.matches("^[0-9a-fA-F]{64}$")) {
            out = new byte[MASTER_KEY_BYTES];
            for (int i = 0; i < MASTER_KEY_BYTES; i++) {
                out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            try {
                out = Base64.getDecoder().decode(t);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("主密钥既不是 64 位十六进制，也不是合法 base64: "
                        + t.length() + " 字符", e);
            }
        }
        if (out.length != MASTER_KEY_BYTES) {
            throw new IllegalStateException("主密钥解析后为 " + out.length + " 字节，"
                    + "必须是 " + MASTER_KEY_BYTES + " 字节");
        }
        return out;
    }
}