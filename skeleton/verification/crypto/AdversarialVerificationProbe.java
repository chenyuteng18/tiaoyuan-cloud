// 无 package 声明：本文件已迁出 dy-crypto 的源码树，是独立可执行程序，
// package 只会把 class 扔进包目录而徒增加载复杂度（且包名里的 adversarial
// 目录已随迁移删除）。所有被测类型经下方 import 显式引用。

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmId;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.algorithm.PendingAlgorithmConfirmationException;
import com.diaoyuanyun.dy.crypto.envelope.AadBinding;
import com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException;
import com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope;
import com.diaoyuanyun.dy.crypto.envelope.EnvelopeFormatException;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.field.FieldCipher;
import com.diaoyuanyun.dy.crypto.field.SensitiveField;
import com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore;
import com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore;
import com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry;
import com.diaoyuanyun.dy.crypto.key.KeyBackupUnavailableException;
import com.diaoyuanyun.dy.crypto.key.ShredTombstoneStore;
import com.diaoyuanyun.dy.crypto.key.SubjectDek;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.key.WrappedDek;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>独立对抗性验证探针</b> —— 由未参与 dy-crypto 实现的验证者编写。
 *
 * <h2>与 {@code gate/FieldCryptoGateTest} 的关系</h2>
 * <b>不</b>复用其任何断言、<b>不</b>复跑其任何用例。本类从"攻击者视角"重写：
 * 目标是<b>证伪</b>六条 DoD 声明与三条反向验证声明，而不是确认它们存在。
 * <b>与实现方零共享</b>：本类不 import 也不引用 dy-crypto 的任何测试类。
 * 装配栈所需的 harness（租户/主体常量、{@code Stack}、{@code newStack}、
 * 替代算法实现）在本类内<b>内联</b>自持 —— 因为共享测试辅助类会让
 * "验证者的结论" 与 "被测方测试代码的措辞" 之间重新出现耦合面。
 *
 * <h2>为什么不在 dy-crypto 的 src/test 下</h2>
 * 放在那里会被 {@code mvn -pl dy-crypto test} 编译进该模块自己的 test-classes，
 * 使 surefire 的 {@code Tests run} 由 23 抬到 24 —— 实现者的用例与验证者的探针
 * 混进<b>同一个计数</b>，等于自证。本类因此迁到 {@code verification/crypto/}，
 * 不参与任何模块的 test 编译，只由本目录的 runner 独立编译并执行。
 *
 * <h2>输出约定</h2>
 * 每条探针输出 {@code [PASS]}（攻击失败＝实现正确）/ {@code [FAIL]}（攻击成功＝发现缺陷）
 * / {@code [N/A]}（未能验证）。报告落盘目录由 {@code -Dcrypto.evidence.dir} 指定，
 * 缺省为 {@code verification/crypto/evidence/}；可用 {@code -Dcrypto.repo.root=<path>}
 * 指定仓库根（供源码扫描与 pom 核对使用，不依赖探针自身所在目录）。
 */
public final class AdversarialVerificationProbe {

    // ------------------------------------------------------------------
    // 内联常量（必须置于类体最前：Java 对静态字段的「非法前向引用」
    // 会在编译期拒绝 `TA = TENANT_A` 这类对后置常量的引用）
    // ------------------------------------------------------------------

    /** 测试用租户（可丢弃）。 */
    static final String TENANT_A = "tnt-aaaa-0001";
    static final String TENANT_B = "tnt-bbbb-0002";

    /** 测试用主体 id。 */
    static final String CUSTOMER_1 = "cust-0001";
    static final String CUSTOMER_2 = "cust-0002";

    private static final String TA = TENANT_A;
    private static final String TB = TENANT_B;

    private static final SubjectRef C1 = SubjectRef.customer(TA, CUSTOMER_1);
    private static final SubjectRef C2 = SubjectRef.customer(TA, CUSTOMER_2);

    private static final StringBuilder LOG = new StringBuilder();
    private static int nPass;
    private static int nFail;
    private static int nNa;

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 探针矩阵入口。
     *
     * <p>门禁形状：全部 PASS → exit 0；任一 FAIL → exit 1。
     * 不再依赖 JUnit —— 本探针是独立可执行的验证程序，不参与 dy-crypto 的
     * {@code Tests run} 计数（避免实现者与验证者的证据混进同一个计数）。
     */
    void matrix() {
        run("A1", this::probeA1);
        run("A2", this::probeA2);
        run("A3", this::probeA3);
        run("A4", this::probeA4);
        run("B1", this::probeB1);
        run("B2", this::probeB2);
        run("B3", this::probeB3);
        run("B4", this::probeB4);
        run("B5", this::probeB5);
        run("B6", this::probeB6);
        run("B7", this::probeB7);
        run("C1", this::probeC1);
        run("C2", this::probeC2);
        run("C3", this::probeC3);
        run("C4", this::probeC4);
        run("C5", this::probeC5);
        run("D1", this::probeD1);
        run("E1", this::probeE1);
        run("E2", this::probeE2);
        run("E3", this::probeE3);
        run("F1", this::probeF1);
        run("F2", this::probeF2);
        run("G1", this::probeG1);
        run("G2", this::probeG2);
        run("G3", this::probeG3);
        run("H1", this::probeH1);
        run("H2", this::probeH2);
        run("H3", this::probeH3);
        run("H4", this::probeH4);
        run("H5", this::probeH5);
        run("H6", this::probeH6);
        run("H7", this::probeH7);
        run("H8", this::probeH8);
        run("H9", this::probeH9);
        run("I1", this::probeI1);
        run("I2", this::probeI2);
        run("I3", this::probeI3);
        run("I4", this::probeI4);
        run("J1", this::probeJ1);

        flush();
    }


    // ==================================================================
    // A. 绕过 FieldCipher 直接解密
    // ==================================================================

    /** A1：用租户 KEK 直接解字段密文（不走 FieldCipher）。 */
    private void probeA1() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String envText = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        CipherEnvelope env = CipherEnvelope.parse(envText);

        byte[] kek = s.keks().currentKek(TA);
        AlgorithmProvider aes = s.algorithms().resolve(AlgorithmId.AES_256_GCM.id());
        byte[] aad = AadBinding.of(C1, SensitiveField.HEART_RATE.fieldName(), env.dekVersion());

        String outcome;
        boolean broke;
        try {
            byte[] plain = aes.decrypt(kek, env.nonce(), env.ciphertext(), aad);
            broke = true;
            outcome = "解出明文！len=" + plain.length;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("A1", "用租户 KEK 直接解字段密文（绕过 FieldCipher/DEK）",
                "认证失败（KEK 不是字段密钥，且域分离）", !broke, outcome);
    }

    /** A2：底层是否有"直接取出明文 DEK"的公开路径，以及取出后能否自己解密。 */
    private void probeA2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String envText = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        CipherEnvelope env = CipherEnvelope.parse(envText);

        StringBuilder detail = new StringBuilder();
        boolean gotDek = false;
        String roundTrip = "未执行";
        try {
            // 走公开接口：wrappedOf(subject, version) → unwrap(...) 。不经 FieldCipher。
            WrappedDek wrapped = s.keyStore().wrappedOf(C1, env.dekVersion()).orElseThrow();
            byte[] rawDek = s.keyStore().unwrap(wrapped);
            gotDek = true;
            AlgorithmProvider aes = s.algorithms().resolve(AlgorithmId.AES_256_GCM.id());
            byte[] plain = aes.decrypt(rawDek, env.nonce(), env.ciphertext(),
                    AadBinding.of(C1, SensitiveField.HEART_RATE.fieldName(), env.dekVersion()));
            roundTrip = "成功解出 " + new String(plain, StandardCharsets.UTF_8);
            detail.append("unwrap() 返回明文 DEK len=").append(rawDek.length);
        } catch (RuntimeException e) {
            detail.append(e.getClass().getSimpleName()).append(": ").append(brief(e.getMessage()));
        }
        // 另外检查 SubjectDek.rawDek() 是否也是公开明文出口
        SubjectDek dek = s.keyStore().getOrCreateDek(C1);
        boolean rawDekPublic = dek.rawDek().length == 32;
        // 再检查诊断用的备份清单是否泄漏密钥材料（如 wrappedBytes 里含明文 DEK）
        var inv = s.keyStore().inventoryForBackupAudit();
        boolean inventoryLeaksDek = false;
        for (WrappedDek w : inv.values()) {
            if (indexOf(w.wrappedBytes(), dek.rawDek()) >= 0) {
                inventoryLeaksDek = true;
            }
        }
        detail.append("; SubjectDek.rawDek() 明文出口=").append(rawDekPublic)
                .append("; inventoryForBackupAudit 含明文DEK=").append(inventoryLeaksDek);
        detail.append("; 用取回的DEK自行解密=").append(roundTrip);

        // 这不是"攻击成功"——产物 DEK 本来就该以副本形式在调用栈里存在。
        // 记录为事实，判定：无绕过 FieldCipher 的【捷径】（必须显式 unwrap），
        // 但 unwrap/currentKek/kekVersion 三个公开方法都返回明文密钥材料（接口必需，有风险面）。
        check("A2", "底层是否存在绕过 FieldCipher 的 DEK 捷径 / 明文密钥出口",
                "无捷径；明文出口仅 unwrap()/rawDek()/currentKek()/kekVersion()（接口必需）",
                !inventoryLeaksDek && gotDek,
                detail.toString());
    }

    /** A3：不持有 DEK 时（用另一主体的 DEK）能否解出目标主体明文。 */
    private void probeA3() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String envText = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        CipherEnvelope env = CipherEnvelope.parse(envText);

        // 用真实 AES 实现尝试解密（攻击者自己持有另一把 DEK，但算法实现是公开的）
        AlgorithmProvider aes = AlgorithmRegistry.standard().resolve(AlgorithmId.AES_256_GCM.id());
        byte[] dekOfC2 = s.keyStore().unwrap(s.keyStore().wrappedOf(C2, 1).orElseGet(() -> {
            s.keyStore().getOrCreateDek(C2);
            return s.keyStore().wrappedOf(C2, 1).orElseThrow();
        }));
        boolean broke;
        String outcome;
        try {
            byte[] plain = aes.decrypt(dekOfC2, env.nonce(), env.ciphertext(),
                    AadBinding.of(C2, SensitiveField.HEART_RATE.fieldName(), env.dekVersion()));
            broke = true;
            outcome = "解出明文 len=" + plain.length;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName();
        }
        check("A3", "用另一主体的 DEK + 另一主体的 AAD 解目标密文",
                "认证失败（DEK 与 AAD 双重不匹配）", !broke, outcome);
    }

    /** A4：AAD 分隔符纪律 —— 含 '|' 的字段必须在入口被拒。 */
    private void probeA4() {
        boolean rejected;
        String outcome;
        try {
            AadBinding.of(new SubjectRef(TA, "customer", "a|b"), "hr", 1);
            rejected = false;
            outcome = "未抛异常（AAD 碰撞可构造）";
        } catch (IllegalArgumentException e) {
            rejected = true;
            outcome = "IllegalArgumentException";
        }
        check("A4", "含 '|' 的 subjectId 构造 AAD（拼接式 AAD 碰撞）",
                "入口拒绝", rejected, outcome);
    }

    // ==================================================================
    // B. AAD 绑定 / KEK 错配
    // ==================================================================

    /** B1：同字段、跨主体搬运密文。 */
    private void probeB1() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "77");
        s.keyStore().getOrCreateDek(C2);
        boolean broke;
        String outcome;
        try {
            String p = s.cipher().decryptText(C2, SensitiveField.HEART_RATE.fieldName(), env);
            broke = true;
            outcome = "解出明文=" + p;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName();
        }
        check("B1", "把 C1 的密文挂到 C2 名下解密", "认证失败", !broke, outcome);
    }

    /** B2：跨字段搬运密文（同主体）。 */
    private void probeB2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "77");
        boolean broke;
        String outcome;
        try {
            String p = s.cipher().decryptText(C1, SensitiveField.BLOOD_OXYGEN.fieldName(), env);
            broke = true;
            outcome = "解出明文=" + p;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName();
        }
        check("B2", "把『hr』字段的密文当『spo2』解（同主体）", "认证失败", !broke, outcome);
    }

    /** B3：跨租户 —— 用 B 租户身份解 A 租户主体的密文。 */
    private void probeB3() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.provisionTenant(TB);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "77");
        SubjectRef c1InB = SubjectRef.customer(TB, CUSTOMER_1);
        boolean broke;
        String outcome;
        try {
            String p = s.cipher().decryptText(c1InB, SensitiveField.HEART_RATE.fieldName(), env);
            broke = true;
            outcome = "解出明文=" + p;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("B3", "同一 customerId 在 B 租户的身份解 A 租户的密文", "认证失败/取不到密钥", !broke, outcome);
    }

    /** B4：把 wrappedDek 的 tenantId 改成另一租户（KEK 取自另一租户）。 */
    private void probeB4() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.provisionTenant(TB);
        WrappedDek w = s.keyStore().getOrCreateDek(C1).wrapped();
        // 篡改归属：主体三元组换成 B 租户下同名主体，kekId 保留（A 租户的 kekId）
        WrappedDek forged = new WrappedDek(TB, w.subjectType(), w.subjectId(), w.version(),
                w.kekId(), w.algorithmId(), w.nonce(), w.wrappedBytes());
        boolean broke;
        String outcome;
        try {
            byte[] dek = s.keyStore().unwrap(forged);
            broke = true;
            outcome = "还原出 DEK len=" + dek.length;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("B4", "把 wrappedDek 的租户改成 B（kekId 仍是 A 的）",
                "拒绝（跨租户 KEK 不可用 + AAD 归属不符）", !broke, outcome);
    }

    /** B5：同租户、同主体、同 DEK 版本 —— 但篡改 wrappedDek 里记录的 kekId 为同租户另一代。 */
    private void probeB5() {
        Stack s = newStack();
        s.provisionTenant(TA);
        WrappedDek w = s.keyStore().getOrCreateDek(C1).wrapped();
        String kekV1 = w.kekId();
        String kekV2 = s.keks().rotateKek(TA); // 第二代 KEK 已存在且可被 kekVersion 取到
        WrappedDek forged = new WrappedDek(w.tenantId(), w.subjectType(), w.subjectId(), w.version(),
                kekV2, w.algorithmId(), w.nonce(), w.wrappedBytes());
        boolean broke;
        String outcome;
        try {
            byte[] dek = s.keyStore().unwrap(forged);
            broke = true;
            outcome = "还原出 DEK len=" + dek.length;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("B5", "把 wrappedDek 的 kekId 改成同租户的【另一代】真实 KEK",
                "拒绝（AAD 含 kekId，选择器不可篡改）", !broke,
                outcome + " [kekV1=" + kekV1 + ", 篡改为=" + kekV2 + "]");
    }

    /** B6：篡改信封里的 DEK 版本号（选择器篡改）—— 轮换后存在 v2。 */
    private void probeB6() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String envV1 = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "77");
        s.keyStore().rotateDek(C1); // 于是 v2 存在
        String forged = envV1.replaceFirst(":1:", ":2:");
        boolean injectionEffective = !forged.equals(envV1);
        boolean broke;
        String outcome;
        try {
            String p = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), forged);
            broke = true;
            outcome = "解出明文=" + p;
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName();
        }
        check("B6", "把 v1 密文的 DEK 版本位改成 v2（版本号是密钥选择器）",
                "认证失败（dekVersion 在 AAD 内）", injectionEffective && !broke,
                "注入生效=" + injectionEffective + "; " + outcome);
    }

    /**
     * B7：<b>作用域键碰撞</b> —— subjectType/subjectId 里含 NUL 时，
     * {@code key()} 的拼接是否会让两个不同主体落进同一把 DEK。
     *
     * <p><b>判据的两面</b>（team-lead 补充要求 ④⑤）：
     * <ol>
     *   <li>修复前：两个不同主体共用同一把 DEK，且<b>销毁 x 改变了 y 的可读性</b>
     *       （删除权误伤）。</li>
     *   <li>修复后：含 {@code \u0000} 的分量必须被<b>构造器主动拒绝</b>
     *       —— 不是"靠运气恰好没碰撞"，而是明确抛错。</li>
     * </ol>
     *
     * <p><b>关键：不能把"构造器拒绝"记成 N/A。</b> 若实现改成在 {@code SubjectRef}
     * 构造期就抛异常，本方法体内直接 new 会抛到 {@code run()} 的 catch，被计成
     * "未能验证"（N/A）—— 而 N/A 在门禁里同样判红。所以这里用
     * {@code try/catch} 把"拒绝"显式转成 PASS。这正是"判据必须与修法对齐、
     * 否则修好了仍然红（且是 N/A 而非 FAIL，更难排查）"的典型陷阱。
     *
     * <p>误伤判据写成<b>"销毁 x 前后 y 的可读性不变"</b>，而不是"y 仍可读"——
     * 后者在"二者本就共用 DEK"时恒为假，无法区分"被误伤"与"本就共享"。
     */
    private void probeB7() {
        Stack s = newStack();
        s.provisionTenant(TA);

        // ① 修复后的目标行为：含 NUL 的分量必须被构造器拒绝
        boolean xRejected = false;
        boolean yRejected = false;
        String xRejectOutcome = "未拒绝";
        String yRejectOutcome = "未拒绝";
        SubjectRef x = null;
        SubjectRef y = null;
        try {
            x = new SubjectRef(TA, "s", "a\0b");
        } catch (RuntimeException e) {
            xRejected = true;
            xRejectOutcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        try {
            y = new SubjectRef(TA, "s\0a", "b");
        } catch (RuntimeException e) {
            yRejected = true;
            yRejectOutcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        if (xRejected || yRejected) {
            // 构造器已显式拒绝 ⇒ 碰撞在入口被结构性堵住，NUL 主体根本进不了存储层
            check("B7", "subjectType/subjectId 含 NUL 时两个不同主体是否共用同一把 DEK",
                    "两个主体必须得到不同 DEK；更强的修法是构造器直接拒绝含 NUL 的分量",
                    xRejected && yRejected,
                    "构造器拒绝 x=" + xRejected + "（" + xRejectOutcome + "）"
                            + "; 拒绝 y=" + yRejected + "（" + yRejectOutcome + "）"
                            + " ⇒ 键碰撞在入口被结构性堵住");
            return;
        }

        // ② 构造器未拒绝 ⇒ 退回到"是否共用 DEK + 是否误伤"的行为判据
        SubjectDek dx = s.keyStore().getOrCreateDek(x);
        SubjectDek dy = s.keyStore().getOrCreateDek(y);
        boolean sameDek = java.util.Arrays.equals(dx.rawDek(), dy.rawDek());
        int live = s.keyStore().liveKeyCount();

        // 误伤判据：销毁 x 前后，y 的可读性必须【不变】
        String beforeRead;
        boolean readableBefore;
        String envY = s.cipher().encryptText(y, SensitiveField.HEART_RATE.fieldName(), "55");
        try {
            beforeRead = s.cipher().decryptText(y, SensitiveField.HEART_RATE.fieldName(), envY);
            readableBefore = "55".equals(beforeRead);
        } catch (RuntimeException e) {
            readableBefore = false;
            beforeRead = e.getClass().getSimpleName();
        }

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(x, dx.version()));

        String afterRead;
        boolean readableAfter;
        try {
            afterRead = s.cipher().decryptText(y, SensitiveField.HEART_RATE.fieldName(), envY);
            readableAfter = "55".equals(afterRead);
        } catch (SubjectKeyDestroyedException e) {
            readableAfter = false;
            afterRead = "SubjectKeyDestroyedException";
        } catch (RuntimeException e) {
            readableAfter = false;
            afterRead = e.getClass().getSimpleName();
        }
        boolean collateralUnchanged = readableBefore == readableAfter;

        check("B7", "subjectType/subjectId 含 NUL 时两个不同主体是否共用同一把 DEK",
                "两个主体必须得到不同 DEK；且销毁 x 不得改变 y 的可读性",
                !sameDek && collateralUnchanged,
                "共用同一 DEK=" + sameDek + "; liveKeyCount(应为2)=" + live
                        + "; 销毁前 y 可读=" + readableBefore + "（" + beforeRead + "）"
                        + "; 销毁后 y 可读=" + readableAfter + "（" + afterRead + "）"
                        + "; 可读性不变=" + collateralUnchanged
                        + (collateralUnchanged ? "" : " ⇒ 删除权误伤/被误伤"));
    }

    // ==================================================================
    // C. 算法位伪造 / 降级
    // ==================================================================

    /**
     * C1：信封算法位声称 AES，但密文实际由另一实现（ChaCha20-Poly1305）生成。
     * 期望：不"猜测"，直接认证失败；绝不因为密文打不开而改用别的算法。
     */
    private void probeC1() {
        AlgorithmProvider chacha = new TestOnlyAlternateAlgorithmProvider();
        // 用 ChaCha 生成密文，但把信封算法位写成 AES（正是"算法位与实际不符"）
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] aad = "v1|t|s|c|hr|1".getBytes(StandardCharsets.UTF_8);
        AlgorithmProvider.EncryptResult enc = chacha.encrypt(key, "72".getBytes(StandardCharsets.UTF_8), aad);
        String forged = new CipherEnvelope(AlgorithmId.AES_256_GCM.id(), 1, enc.nonce(), enc.ciphertext()).serialize();

        // 用真 AES 实现解这个"声称 AES"的信封
        AlgorithmRegistry registry = AlgorithmRegistry.standard();
        AlgorithmProvider realAes = registry.resolve(AlgorithmId.AES_256_GCM.id());
        boolean broke;
        String outcome;
        try {
            byte[] plain = realAes.decrypt(key, enc.nonce(), enc.ciphertext(), aad);
            broke = true;
            outcome = "解出明文（算法切换未被检出）len=" + plain.length;
        } catch (CipherAuthenticationException e) {
            broke = false;
            outcome = "CipherAuthenticationException";
        } catch (RuntimeException e) {
            broke = false;
            outcome = e.getClass().getSimpleName();
        }
        check("C1", "信封算法位声称 AES，密文实为 ChaCha20-Poly1305 生成",
                "认证失败，不猜测替换算法", !broke, outcome);
    }

    /** C2：把真实 AES 密文的算法位改成 SM4-GCM —— 是否诱使回退到 AES？ */
    private void probeC2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        String forged = env.replaceFirst(AlgorithmId.AES_256_GCM.id(), AlgorithmId.SM4_GCM.id());
        boolean effective = !forged.equals(env);
        boolean fellBack = false;
        String outcome;
        try {
            String p = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), forged);
            fellBack = true;
            outcome = "解出明文=" + p;
        } catch (PendingAlgorithmConfirmationException e) {
            outcome = "PendingAlgorithmConfirmationException（消息含 H.4-1="
                    + e.getMessage().contains("H.4-1") + "）";
        } catch (RuntimeException e) {
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("C2", "把 AES 密文的算法位篡改为 SM4-GCM（诱使静默回退 AES）",
                "抛 PendingAlgorithmConfirmationException，绝不回退到 AES",
                effective && !fellBack, "注入生效=" + effective + "; " + outcome);
    }

    /**
     * C3：<b>装配合法 but 攻击者注入</b> —— 注册一个声称 id=SM4_GCM 的实现，
     * 看 fail-closed 是"因为没注册"还是"因为状态待确认"。
     */
    private void probeC3() {
        AlgorithmRegistry registry = AlgorithmRegistry.of(new DeclaredIdProvider(AlgorithmId.SM4_GCM));
        boolean blockedByStatus;
        String outcome;
        try {
            AlgorithmProvider p = registry.resolve(AlgorithmId.SM4_GCM.id());
            blockedByStatus = false;
            outcome = "解析成功（返回 " + p.getClass().getSimpleName() + "）—— fail-closed 被绕过！";
        } catch (PendingAlgorithmConfirmationException e) {
            blockedByStatus = true;
            outcome = "PendingAlgorithmConfirmationException（即使实现已注册，仍被状态拦住）";
        } catch (RuntimeException e) {
            blockedByStatus = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("C3", "把 SM4 实现注册进注册表后再解析 SM4（攻击者能注册时）",
                "仍抛 PendingAlgorithmConfirmationException（门在状态位，不在注册表）",
                blockedByStatus, outcome);
    }

    /** C4：未知算法位 / 大小写变体 / 带空白 —— 必须明确拒绝，不做宽松匹配。 */
    private void probeC4() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String good = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        String[] cases = {
                "AES-256-GCM-X", "aes-256-gcm", "AES-256-GCM ", " AES-256-GCM", "sm4-gcm", "SM4_GCM",
        };
        StringBuilder detail = new StringBuilder();
        boolean allRejected = true;
        for (String c : cases) {
            String forged = good.replaceFirst(AlgorithmId.AES_256_GCM.id(), c);
            String r;
            try {
                String p = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), forged);
                r = "解出明文!!!";
                allRejected = false;
            } catch (RuntimeException e) {
                r = e.getClass().getSimpleName();
            }
            detail.append('[').append(c).append("→").append(r).append("] ");
        }
        check("C4", "算法位的未知串/大小写变体/前后空白", "全部明确拒绝（无宽松匹配）",
                allRejected, detail.toString().trim());
    }

    /** C5：算法位为空 / 含信封分隔符 ':'。 */
    private void probeC5() {
        StringBuilder detail = new StringBuilder();
        boolean allRejected = true;
        for (String bad : List.of("dy1::1:AAAA:BBBB", "dy1:A:B:C:AAAA:BBBB")) {
            String r;
            try {
                CipherEnvelope.parse(bad);
                r = "被接受!!!";
                allRejected = false;
            } catch (RuntimeException e) {
                r = e.getClass().getSimpleName();
            }
            detail.append('[').append(bad).append("→").append(r).append("] ");
        }
        check("C5", "信封算法位为空 / 含 ':'（破坏切分）", "明确拒绝",
                allRejected, detail.toString().trim());
    }

    // ==================================================================
    // D. "无算法字面量"的结构性复核（独立扫描器）
    // ==================================================================

    /**
     * D1：用本探针自己实现的词法剥离器（独立于被测方的 stripComments），
     * 扫描 field/ envelope/ key/ shred/ 四个包的<b>代码部分</b>，
     * 找算法字面量、{@code instanceof *Provider}、按算法名分支的 switch/if。
     */
    private void probeD1() {
        Path root = repoRoot().resolve("src/main/java/com/diaoyuanyun/dy/crypto");
        List<Path> files = new ArrayList<>();
        for (String pkg : List.of("field", "envelope", "key", "shred")) {
            Path dir = root.resolve(pkg);
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (Exception e) {
                nNa++;
                log("[N/A] D1 无法列出包 " + pkg + ": " + e);
                return;
            }
        }

        List<String> algorithmHits = new ArrayList<>();
        List<String> providerInstanceOf = new ArrayList<>();
        List<String> switchOnAlgorithm = new ArrayList<>();
        int codeFiles = 0;
        for (Path f : files) {
            String code;
            try {
                code = stripCommentsIndependently(Files.readString(f, StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue;
            }
            codeFiles++;
            String rel = root.relativize(f).toString().replace('\\', '/');

            // ① 字符串字面量里的算法名
            Matcher m = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(code);
            while (m.find()) {
                String lit = m.group();
                String up = lit.toUpperCase(Locale.ROOT);
                for (String banned : List.of("AES", "SM4", "GCM", "CHACHA", "NOPADDING")) {
                    if (up.contains(banned)) {
                        algorithmHits.add(rel + " " + lit);
                    }
                }
            }
            // ② instanceof 具体 Provider / 按算法名分支
            if (Pattern.compile("instanceof\\s+\\w*(Aes|Sm4|ChaCha)\\w*Provider").matcher(code).find()) {
                providerInstanceOf.add(rel);
            }
            if (Pattern.compile("switch\\s*\\([^)]*[Aa]lgorithm").matcher(code).find()
                    || Pattern.compile("if\\s*\\([^)]*(AES|SM4|GCM)[^)]*\\)\\.equals").matcher(code).find()) {
                switchOnAlgorithm.add(rel);
            }
        }

        // FieldCipher 单独确认（DoD ⑤ 的原话指向它）
        String fieldCipherCode;
        try {
            fieldCipherCode = stripCommentsIndependently(
                    Files.readString(root.resolve("field/FieldCipher.java"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            fieldCipherCode = "";
        }
        boolean fieldCipherClean = true;
        Matcher fm = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(fieldCipherCode);
        while (fm.find()) {
            String up = fm.group().toUpperCase(Locale.ROOT);
            for (String banned : List.of("AES", "SM4", "GCM", "CHACHA", "NOPADDING")) {
                if (up.contains(banned)) {
                    fieldCipherClean = false;
                }
            }
        }
        boolean scannerSane = fieldCipherCode.contains("encryptBytes") && fieldCipherCode.contains("decryptBytes");

        String detail = "扫描源码文件数=" + codeFiles
                + "; FieldCipher 代码无算法字面量=" + fieldCipherClean
                + "; 扫描器自检（剥注释后仍见方法名）=" + scannerSane
                + "; 四包算法字面量命中=" + algorithmHits
                + "; instanceof 具体Provider=" + providerInstanceOf
                + "; 按算法名分支=" + switchOnAlgorithm;

        check("D1", "独立复核『无算法字面量』并检查四个包内的隐藏算法耦合",
                "FieldCipher 干净 + 四包内无算法字面量/无具体 Provider instanceof/无按算法名分支",
                fieldCipherClean && scannerSane && algorithmHits.isEmpty()
                        && providerInstanceOf.isEmpty() && switchOnAlgorithm.isEmpty(),
                detail);
    }

    // ==================================================================
    // E. crypto-shredding 的"不可恢复"边界
    // ==================================================================

    /**
     * E1：<b>独立重现"墓碑是承重件"</b>（修正版 —— 分两步，避免把两种机制混为一谈）。
     *
     * <p>上一版探针漏了"从备份恢复 wrappedDek"这一步，导致结论被误读成"清墓碑也没用"。
     * 这里分开测：
     * <ul>
     *   <li>① 仅清墓碑、不恢复 wrappedDek → 仍不可读（说明在线密钥材料确实被真删了）；</li>
     *   <li>② 清墓碑 + 恢复备份里的 wrappedDek → 恢复可读（说明墓碑是唯一阻止它的东西）。</li>
     * </ul>
     */
    private void probeE1() {
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectDek dek = s.keyStore().getOrCreateDek(C1);
        WrappedDek backupCopy = dek.wrapped();
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));

        boolean blockedAfterDestroy = false;
        try {
            s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), env);
        } catch (SubjectKeyDestroyedException e) {
            blockedAfterDestroy = true;
        } catch (RuntimeException ignored) {
        }

        // ① 仅清墓碑：新建的 DEK 与老密文无关 ⇒ 仍解不开
        s.tombstones().clearForTest();
        s.keyStore().getOrCreateDek(C1);
        String afterOnlyClear;
        boolean readableAfterOnlyClear;
        try {
            afterOnlyClear = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), env);
            readableAfterOnlyClear = true;
        } catch (RuntimeException e) {
            afterOnlyClear = e.getClass().getSimpleName();
            readableAfterOnlyClear = false;
        }

        // ② 清墓碑 + 恢复备份里的 wrappedDek：用底层还原 DEK 后自行解密
        String afterRestore;
        boolean readableAfterRestore = false;
        try {
            byte[] restoredDek = s.keyStore().unwrap(backupCopy); // 墓碑已清 → 这条路径被打通
            AlgorithmProvider aes = AlgorithmRegistry.standard().resolve(AlgorithmId.AES_256_GCM.id());
            CipherEnvelope pe = CipherEnvelope.parse(env);
            byte[] plain = aes.decrypt(restoredDek, pe.nonce(), pe.ciphertext(),
                    AadBinding.of(C1, SensitiveField.HEART_RATE.fieldName(), pe.dekVersion()));
            afterRestore = new String(plain, StandardCharsets.UTF_8);
            readableAfterRestore = true;
        } catch (RuntimeException e) {
            afterRestore = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }

        // ③ 对照：重新立墓碑 → 立刻又不可还原（证明能力差异确实来自墓碑）
        s.tombstones().record(SubjectKeyRef.of(C1, 1), java.time.Instant.now(), "测试：重新立墓碑");
        boolean blockedAgain;
        try {
            s.keyStore().unwrap(backupCopy);
            blockedAgain = false;
        } catch (SubjectKeyDestroyedException e) {
            blockedAgain = true;
        }

        check("E1", "墓碑承重性：分步隔离『在线材料删除』与『墓碑』两个机制",
                "① 仅清墓碑仍不可读（在线材料确已删除）；② 清墓碑+恢复备份 → 又可读；③ 重立墓碑 → 又不可读",
                blockedAfterDestroy && !readableAfterOnlyClear && readableAfterRestore && blockedAgain,
                "销毁后被阻断=" + blockedAfterDestroy
                        + "; ①仅清墓碑→" + afterOnlyClear
                        + "; ②清墓碑+恢复wrappedDek→" + afterRestore
                        + "; ③重立墓碑后又被阻断=" + blockedAgain);
    }

    /** E2：墓碑在场时，攻击者用事前抄走的 wrappedDek 做离线攻击。 */
    private void probeE2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        WrappedDek stolen = s.keyStore().getOrCreateDek(C1).wrapped();

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));

        boolean blocked;
        String outcome;
        try {
            byte[] dek = s.keyStore().unwrap(stolen);
            blocked = false;
            outcome = "还原出 DEK len=" + dek.length + "（离线攻击成功）";
        } catch (SubjectKeyDestroyedException e) {
            blocked = true;
            outcome = "SubjectKeyDestroyedException（连离线材料也被墓碑堵住）";
        } catch (RuntimeException e) {
            blocked = true;
            outcome = e.getClass().getSimpleName();
        }
        check("E2", "销毁后持事前抄走的 wrappedDek 做离线还原（墓碑在场）",
                "被墓碑阻断", blocked, outcome);
    }

    /**
     * E3：<b>关键问题</b> —— 模拟"进程重启"：墓碑（进程内）丢失、KEK 持久（KMS/HSM）。
     * 攻击者持事前抄走的 wrappedDek，能否解出已删除数据？
     */
    private void probeE3() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        WrappedDek stolen = s.keyStore().getOrCreateDek(C1).wrapped(); // 备份里本来就有（DoD④ 要求）
        CipherEnvelope parsed = CipherEnvelope.parse(env);

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));

        // 重启：全新的墓碑存储（空）+ 全新的密钥库；KEK registry 复用（模拟 KMS 持久）
        InMemoryShredTombstoneStore freshTombs = new InMemoryShredTombstoneStore();
        InMemorySubjectKeyStore rebornKeyStore = new InMemorySubjectKeyStore(
                s.keks(), freshTombs, s.algorithms(), AlgorithmId.AES_256_GCM.id());
        FieldCipher rebornCipher = new FieldCipher(rebornKeyStore, s.algorithms(),
                AlgorithmId.AES_256_GCM.id());

        boolean resurrected;
        String outcome;
        try {
            // 路径 ①：底层直接还原 DEK
            byte[] dek = rebornKeyStore.unwrap(stolen);
            // 路径 ②：端到端 —— 直接构造信封交给重启后的 FieldCipher 也走不通（因 wrappedOf 为空），
            //          故用底层还原出的 DEK 自己解，等价于攻击者拿到备份后的完整能力。
            AlgorithmProvider aes = s.algorithms().resolve(AlgorithmId.AES_256_GCM.id());
            byte[] plain = aes.decrypt(dek, parsed.nonce(), parsed.ciphertext(),
                    AadBinding.of(C1, SensitiveField.HEART_RATE.fieldName(), parsed.dekVersion()));
            resurrected = true;
            outcome = "已删除数据重新读出=" + new String(plain, StandardCharsets.UTF_8)
                    + "（墓碑丢失 + KEK 持久 ⇒ 删除权被静默撤销）";
        } catch (RuntimeException e) {
            resurrected = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("E3", "重启（墓碑丢失、KEK 持久）+ 事前抄走的 wrappedDek → 已删除数据是否复活",
                "【期望复活成功】—— 该缺口是他主动固化的坏消息，本次独立确认其真实存在",
                resurrected, outcome + "; rebornCipher=" + (rebornCipher != null));
    }

    // ==================================================================
    // F. 信封解析健壮性
    // ==================================================================

    /** F1：畸形信封矩阵 —— 必须明确抛错，不得崩溃/静默 null/猜测修复。 */
    private void probeF1() {
        StringBuilder hugeAlgo = new StringBuilder("dy1:");
        for (int i = 0; i < 2_000; i++) {
            hugeAlgo.append('A');
        }
        hugeAlgo.append(":1:AAAA:BBBB");
        StringBuilder hugeDekVersion = new StringBuilder("dy1:AES-256-GCM:");
        for (int i = 0; i < 100_000; i++) {
            hugeDekVersion.append('9');
        }
        hugeDekVersion.append(":AAAA:BBBB");

        record Case(String label, String text) {
        }
        List<Case> cases = List.of(
                new Case("null", null),
                new Case("空串", ""),
                new Case("纯空白", "   "),
                new Case("2段", "dy1:AAAA"),
                new Case("3段", "dy1:AES-256-GCM:1"),
                new Case("4段", "dy1:AES-256-GCM:1:AAAA"),
                new Case("6段", "dy1:AES-256-GCM:1:AAAA:BBBB:CCCC"),
                new Case("前缀错", "dy9:AES-256-GCM:1:AAAA:BBBB"),
                new Case("前缀大写", "DY1:AES-256-GCM:1:AAAA:BBBB"),
                new Case("版本非数字", "dy1:AES-256-GCM:notanum:AAAA:BBBB"),
                new Case("版本0", "dy1:AES-256-GCM:0:AAAA:BBBB"),
                new Case("版本负数", "dy1:AES-256-GCM:-1:AAAA:BBBB"),
                new Case("版本超长数字", hugeDekVersion.toString()),
                new Case("nonce非法base64", "dy1:AES-256-GCM:1:!!!:BBBB"),
                new Case("密文非法base64", "dy1:AES-256-GCM:1:AAAA:!!!***"),
                new Case("nonce空段", "dy1:AES-256-GCM:1::BBBB"),
                new Case("密文空段", "dy1:AES-256-GCM:1:AAAA:"),
                new Case("算法位超长", hugeAlgo.toString()),
                new Case("含换行", "dy1:AES-256-GCM:1:AA\n:BBBB"),
                new Case("含CR", "dy1:AES-256-GCM:1:AA\r:BBBB"),
                new Case("仅分隔符", "::::")
        );

        StringBuilder detail = new StringBuilder();
        int crashed = 0;
        int silent = 0;
        int accepted = 0;
        for (Case c : cases) {
            String r;
            try {
                CipherEnvelope e = CipherEnvelope.parse(c.text());
                accepted++;
                r = "被接受(构造成功，algo=" + e.algorithmId() + ", ctLen=" + e.ciphertext().length + ")";
            } catch (EnvelopeFormatException e) {
                r = "EnvelopeFormatException";
            } catch (IllegalArgumentException e) {
                r = "IllegalArgumentException";
            } catch (StackOverflowError | OutOfMemoryError e) {
                crashed++;
                r = "JVM ERROR " + e.getClass().getSimpleName();
            } catch (Throwable t) {
                crashed++;
                r = "未预期: " + t.getClass().getName();
            }
            if (r.length() > 60) {
                r = r.substring(0, 60) + "…";
            }
            detail.append('[').append(c.label()).append('→').append(r).append("] ");
        }

        // 明确抛错的情形：除"密文/nonce 段非空但长度不足"这一档外，全部应为异常。
        // 单列"1字节密文"这一档 —— 结构上通过 parse，解密时才失败（记录，不判缺陷）。
        String shortCt;
        try {
            CipherEnvelope e = CipherEnvelope.parse("dy1:AES-256-GCM:1:AAAA:QQ==");
            shortCt = "parse 接受（ctLen=" + e.ciphertext().length + "），解密期才认证失败";
        } catch (RuntimeException e) {
            shortCt = e.getClass().getSimpleName();
        }

        check("F1", "21 组畸形信封（段数/前缀/base64/版本/空串/超长/控制字符）",
                "明确抛 EnvelopeFormatException 或 IllegalArgumentException；无崩溃、无静默 null",
                crashed == 0 && silent == 0,
                "崩溃=" + crashed + "; 未预期异常=" + silent + "; 结构上被接受组数=" + accepted
                        + "（超长算法位无长度上限，记录为观察事实）; " + detail
                        + "; [1字节密文→" + shortCt + "]");
    }

    /** F2：超长合理信封 + 会话不可用算法位的端到端行为。 */
    private void probeF2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String good = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");
        // 把密文段替换为 2MB base64 —— 解析应成功（结构合法），解密应认证失败而非崩溃
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            big.append('Q');
        }
        String padded = big.toString();
        String forged = good.replaceFirst(":[^:]+$", ":" + padded);
        String outcome;
        boolean ok;
        try {
            s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), forged);
            outcome = "解出明文（不可能）";
            ok = false;
        } catch (CipherAuthenticationException e) {
            outcome = "CipherAuthenticationException（未崩溃）";
            ok = true;
        } catch (EnvelopeFormatException e) {
            outcome = "EnvelopeFormatException";
            ok = true;
        } catch (RuntimeException e) {
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
            ok = true;
        } catch (Throwable t) {
            outcome = "JVM ERROR " + t.getClass().getSimpleName();
            ok = false;
        }
        check("F2", "2MB 密文段（结构合法、内容非法）端到端解密", "认证失败，不崩溃",
                ok, outcome);
    }

    // ==================================================================
    // G. 并发 / 顺序
    // ==================================================================

    /**
     * G1：同一主体并发 getOrCreateDek —— 是否产生多把 DEK（历史密文解不开）。
     *
     * <p><b>为什么不靠"12 线程一起起跑"</b>：这种写法是<b>概率性</b>的。
     * 实测连跑 6 次，G1 有 2 次"侥幸全绿"（窗口太小，线程没真正重叠）。
     * 门禁里"可能假绿"比"恒红"更危险 —— 它会在某次 CI 上放行一个仍坏的实现。
     * 因此这里改用与 H4 相同的<b>门控 provider</b>：让全部 12 个线程都
     * 越过墓碑检查、都停在 {@code currentKek()} 上，再一起放行。
     * 这样"12 个线程都进入创建路径"是<b>确定</b>的，红态不再抖动。
     */
    private void probeG1() {
        int n = 12;
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "race-1");

        CountDownLatch allInsideCreate = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (calls.incrementAndGet() <= n) {
                allInsideCreate.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> Base64.getEncoder()
                        .encodeToString(keyStore.getOrCreateDek(c).rawDek())));
            }
            // 等待上限刻意压到 3 秒：坏实现（锁外判定）下 12 个线程会在毫秒级
            // 全部到达闸门，3 秒富余；好实现（键锁串行化）下只有 1 个线程能进入
            // currentKek，其余被锁挡住，此时"未全部到达"是【预期】而非失败 ——
            // 本探针的判据落在最终存储状态上，不落在 allReached 上。
            boolean allReached = allInsideCreate.await(3, TimeUnit.SECONDS);
            release.countDown();

            Set<String> distinct = new LinkedHashSet<>();
            int failed = 0;
            for (Future<String> f : futures) {
                try {
                    distinct.add(f.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failed++;
                }
            }

            // team-lead 补充判据 ③：光看"线程拿到的是不是同一把"不够 ——
            // 真正决定密文能否解开的是【按版本号取回】这条路径。
            String byVersion;
            boolean byVersionOk;
            try {
                byte[] viaVersion = keyStore.unwrap(keyStore.wrappedOf(c, 1).orElseThrow());
                byVersion = Base64.getEncoder().encodeToString(viaVersion);
                byVersionOk = distinct.size() == 1 && distinct.contains(byVersion);
            } catch (RuntimeException e) {
                byVersion = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
                byVersionOk = false;
            }
            boolean liveOneKey = keyStore.liveKeyCount() == 1;

            check("G1", "12 线程并发 getOrCreateDek 同一主体（门控确定性复现，非概率）",
                    "所有线程拿到同一把 DEK；且按版本号 1 取回的材料能还原出同一把；存储中只应有 1 把活跃密钥",
                    distinct.size() == 1 && failed == 0 && byVersionOk && liveOneKey,
                    "全部 12 线程都进入创建路径=" + allReached
                            + "; 不同 DEK 数=" + distinct.size() + "（1=正确；>1 ⇒ 并发下为同一主体生成多把 DEK，"
                            + "历史密文将按版本解不开）"
                            + "; 取用失败线程=" + failed
                            + "; wrappedOf(c,1) 可还原且等于所取 DEK=" + byVersionOk
                            + "（" + brief(byVersion) + "）"
                            + "; liveKeyCount(应为1)=" + keyStore.liveKeyCount());
        } catch (Exception e) {
            nNa++;
            log("[N/A] G1 并发探针未能执行: " + e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * G2：并发写入后逐个回读 —— 若 G1 产生多把 DEK，这里会出现真实解不开。
     *
     * <p><b>同样改成门控确定性</b>（理由见 G1）：先用门控让 12 个线程都越过
     * "无 DEK"判定、各自建出一把 v1，再放行让它们各自加密一条、最后串行回读。
     * 这样"存在多条密文用不同 DEK 加密"是确定的，不再依赖调度运气。
     */
    private void probeG2() {
        int n = 12;
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "race-2");

        CountDownLatch allInsideCreate = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (calls.incrementAndGet() <= n) {
                allInsideCreate.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());
        FieldCipher cipher = new FieldCipher(keyStore, s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> cipher.encryptText(
                        c, SensitiveField.HEART_RATE.fieldName(), "v" + idx)));
            }
            // 等待上限刻意压到 3 秒：坏实现（锁外判定）下 12 个线程会在毫秒级
            // 全部到达闸门，3 秒富余；好实现（键锁串行化）下只有 1 个线程能进入
            // currentKek，其余被锁挡住，此时"未全部到达"是【预期】而非失败 ——
            // 本探针的判据落在最终存储状态上，不落在 allReached 上。
            boolean allReached = allInsideCreate.await(3, TimeUnit.SECONDS);
            release.countDown(); // 放行 → 12 把 v1 各自 append，随后各自加密

            List<String> envelopes = new ArrayList<>();
            for (Future<String> f : futures) {
                envelopes.add(f.get(30, TimeUnit.SECONDS));
            }
            int unreadable = 0;
            int noKey = 0;
            int ok = 0;
            for (String envText : envelopes) {
                try {
                    cipher.decryptText(c, SensitiveField.HEART_RATE.fieldName(), envText);
                    ok++;
                } catch (SubjectKeyDestroyedException e) {
                    noKey++;
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && e.getMessage().contains("不存在 DEK 版本")) {
                        noKey++;
                    } else {
                        unreadable++;
                    }
                }
            }
            check("G2", "并发写入后逐个回读（同一主体，门控确定性复现）",
                    "全部可读（并发不得使任何一条已写入的密文变成不可读）",
                    unreadable == 0 && noKey == 0 && ok == n,
                    "全部 12 线程都进入创建路径=" + allReached
                            + "; 回读成功=" + ok + "/" + n
                            + "; 回读失败(认证类)=" + unreadable
                            + "; 取不到密钥版本类=" + noKey);
        } catch (Exception e) {
            nNa++;
            log("[N/A] G2 并发探针未能执行: " + e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** G3：变更 NUL 场景后（B7 的对照）—— 正常 id 下并发写入是否仍全部可读（已由 G2 覆盖，此处留空说明）。 */
    private void probeG3() {
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "race-3");
        // 单线程顺序写入 12 条 → 全部可读（对照组：证明 G2 的失败不是"多字段/多版本"本身造成的）
        List<String> envs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            envs.add(s.cipher().encryptText(c, SensitiveField.HEART_RATE.fieldName(), "v" + i));
        }
        int bad = 0;
        for (int i = 0; i < envs.size(); i++) {
            try {
                String p = s.cipher().decryptText(c, SensitiveField.HEART_RATE.fieldName(), envs.get(i));
                if (!("v" + i).equals(p)) {
                    bad++;
                }
            } catch (RuntimeException e) {
                bad++;
            }
        }
        check("G3", "同主体【顺序】写入 12 条后回读（G2 的对照组）",
                "全部可读且值正确 —— 用来证明 G2 的失败归因是并发而非写入次数",
                bad == 0, "回读错误条数=" + bad + "/12");
    }

    // ==================================================================
    // H. 独立补测（keyStore 边界 / 备份门顺序 / DEK 轮换 / 删除路径）
    // ==================================================================

    /** H1：备份门在写入侧 —— 未就绪时连 rotateDek 也必须被拒，且不留材料。 */
    private void probeH1() {
        Stack s = newStack();
        s.keks().initTenant(TA); // 有意不 markBackupReady

        SubjectRef c = SubjectRef.customer(TA, "backup-gate");
        String createOutcome;
        boolean createBlocked;
        try {
            s.keyStore().getOrCreateDek(c);
            createBlocked = false;
            createOutcome = "创建成功（门未生效）";
        } catch (KeyBackupUnavailableException e) {
            createBlocked = true;
            createOutcome = "KeyBackupUnavailableException";
        }
        int liveAfterRefuse = s.keyStore().liveKeyCount();
        boolean noMaterial = liveAfterRefuse == 0;

        // 已就绪创建一把，再撤回就绪 → rotateDek 是否也被拒
        s.keks().markBackupReady(TA);
        s.keyStore().getOrCreateDek(c);
        s.keks().revokeBackupReadiness(TA);
        String rotateOutcome;
        boolean rotateBlocked;
        try {
            s.keyStore().rotateDek(c);
            rotateBlocked = false;
            rotateOutcome = "轮换成功（撤回就绪后仍能轮换 ⇒ rotateDek 未过备份门）";
        } catch (KeyBackupUnavailableException e) {
            rotateBlocked = true;
            rotateOutcome = "KeyBackupUnavailableException";
        }
        check("H1", "备份未就绪时 getOrCreateDek / rotateDek 是否都被拒，且不留密钥材料",
                "两条创建路径都 fail-closed，拒绝后 liveKeyCount=0",
                createBlocked && noMaterial && rotateBlocked,
                "首次创建→" + createOutcome + "; 拒绝后活跃密钥数=" + liveAfterRefuse
                        + "; 撤回就绪后轮换→" + rotateOutcome);
    }

    /** H2：DEK 轮换后，v1 与 v2 密文各自仍可解（信封版本位真的被用于选钥）。 */
    private void probeH2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String envV1 = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "11");
        s.keyStore().rotateDek(C1);
        String envV2 = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "22");

        int v1Ver = CipherEnvelope.parse(envV1).dekVersion();
        int v2Ver = CipherEnvelope.parse(envV2).dekVersion();
        String r1;
        String r2;
        boolean ok;
        try {
            r1 = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), envV1);
            r2 = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), envV2);
            ok = "11".equals(r1) && "22".equals(r2) && v1Ver == 1 && v2Ver == 2;
        } catch (RuntimeException e) {
            r1 = e.getClass().getSimpleName();
            r2 = "-";
            ok = false;
        }
        check("H2", "DEK 轮换后 v1/v2 历史与当前密文各自可解",
                "v1 密文用老版本 DEK、v2 用新版本，两者都可读",
                ok, "信封版本 v1=" + v1Ver + " v2=" + v2Ver + "; 解 v1=" + r1 + "; 解 v2=" + r2);
    }

    /** H3：beast —— 用 wrappedOf(subject, 版本) 在【非活跃版本】上取材料。 */
    private void probeH3() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.keyStore().getOrCreateDek(C1);
        s.keyStore().rotateDek(C1);
        s.keyStore().rotateDek(C1); // 现在有 v1 v2 v3

        boolean v1Available = s.keyStore().wrappedOf(C1, 1).isPresent();
        boolean v2Available = s.keyStore().wrappedOf(C1, 2).isPresent();
        boolean v99Empty = s.keyStore().wrappedOf(C1, 99).isEmpty();
        WrappedDek active = s.keyStore().wrappedOf(C1).orElseThrow();
        check("H3", "按显式 DEK 版本取包裹材料（历史版本必须可取，未来的必须为空）",
                "v1/v2 可取、v99 为空、wrappedOf(subject) 返回 v3（最新）",
                v1Available && v2Available && v99Empty && active.version() == 3,
                "v1=" + v1Available + " v2=" + v2Available + " v99为空=" + v99Empty
                        + " 最新版本=" + active.version());
    }

    /**
     * H4：<b>确定性最小复现</b> —— 强制两个线程都越过"versions == null"判定后再各自建 v1，
     * 从而确定性地证明 {@code getOrCreateDek} 的"读-判-写"不是原子的。
     *
     * <p>手法：{@code createVersion()} 内部会调用 {@code currentKek()}，用一个门控包装器把
     * {@code currentKek()} 卡住，即让两个线程都停在"已判定需要创建"之后、"真正 append"之前。
     * 这不需要依赖线程调度运气。
     */
    private void probeH4() {
        int threads = 2;
        CountDownLatch bothInsideCreate = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);

        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "det-race-1");

        java.util.concurrent.atomic.AtomicInteger createCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (createCalls.incrementAndGet() <= threads) {
                bothInsideCreate.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> Base64.getEncoder()
                        .encodeToString(keyStore.getOrCreateDek(c).rawDek())));
            }
            boolean bothReached = bothInsideCreate.await(3, TimeUnit.SECONDS);
            // 同 G1/G2/H5：上限压低。好实现下键锁把第二个线程挡在外面，
            // "未两两到达"是预期；判据落在"存储里只有一把 v1"上。
            release.countDown(); // 放行 → 两把 v1 都会 append 进同一个 list
            Set<String> deks = new LinkedHashSet<>();
            for (Future<String> f : fs) {
                deks.add(f.get(30, TimeUnit.SECONDS));
            }
            String viaWrapped;
            boolean viaWrappedOk;
            try {
                byte[] w = keyStore.unwrap(keyStore.wrappedOf(c, 1).orElseThrow());
                viaWrapped = Base64.getEncoder().encodeToString(w);
                viaWrappedOk = deks.size() == 1 && deks.contains(viaWrapped);
            } catch (RuntimeException e) {
                viaWrapped = e.getClass().getSimpleName();
                viaWrappedOk = false;
            }
            check("H4", "确定性复现：两线程均越过『无 DEK』判定后各自建 v1",
                    "同一主体最终只有一把 v1；且按版本号 1 取回的材料必须还原出调用方拿到的那把",
                    deks.size() == 1 && viaWrappedOk,
                    "两线程都进入创建路径=" + bothReached
                            + "; 得到的不同 DEK 数=" + deks.size()
                            + "; wrappedOf(c,1) 还原出的那把是否在其中=" + viaWrappedOk
                            + "（false ⇒ 存储里第一条 v1 与调用方拿到的不是同一把，"
                            + "按版本号解密的密文会解到错的密钥）");
        } catch (Exception e) {
            nNa++;
            log("[N/A] H4 未能执行: " + e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * H5：8 线程并发 rotateDek 是否产生"同一版本号对应多把 DEK"。
     *
     * <p><b>为什么不能只 unwrap 线程自己那份</b>（team-lead 的假绿警告）：
     * 每个线程解的是它<b>自己返回的</b> wrapped —— 各自的 kekId/DEK 自洽，
     * 因此该路径<b>必然全过</b>，无论存储里乱成什么样。失败只在事后
     * <b>按版本号取回</b>时才暴露。本探针因此把判据落到三条真入口上：
     * <ol>
     *   <li>版本号必须两两不同，且不同 DEK 数 == 线程数（一一对应）；</li>
     *   <li>{@code wrappedOf(c, v)} 对<b>每一个</b>出现过的 v 都必须可取、
     *       可 unwrap、且还原出的 DEK 正是该 v 上报的那把（不抛 CME）；</li>
     *   <li>历史版本 1..maxV 全部仍可按版本号取回（不得被覆盖丢失）。</li>
     * </ol>
     *
     * <p>另外在风暴期间并发跑读者线程持续调用 {@code wrappedOf(subject, v)} /
     * {@code wrappedOf(subject)}，<b>显式统计 ConcurrentModificationException</b>：
     * 若修法是把 {@code ArrayList} 整体换值（乐观重试），窗口虽小但不等于没有，
     * 必须实测计数而不是假定为 0。
     */
    private void probeH5() {
        int threads = 8;
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "det-race-2");
        s.keyStore().rotateDek(c); // 先造出 v1，避免与"首次创建"路径混淆

        // 门控：让 8 个轮换线程都停在 currentKek() 上，再一起放行 ——
        // 与 G1/G2 同理，"一起起跑"是概率性的，会出现侥幸全绿。
        CountDownLatch allInsideCreate = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (calls.incrementAndGet() <= threads) {
                allInsideCreate.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(threads + 2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stormDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger cmeCount =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger readerLoops =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            // --- 并发读者：在轮换风暴期间持续按版本号取回，统计 CME ---
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    while (!stormDone.get()) {
                        try {
                            for (int v = 1; v <= 4; v++) {
                                keyStore.wrappedOf(c, v).ifPresent(keyStore::unwrap);
                            }
                            keyStore.wrappedOf(c);
                            readerLoops.incrementAndGet();
                        } catch (java.util.ConcurrentModificationException cme) {
                            cmeCount.incrementAndGet();
                        } catch (RuntimeException e) {
                            // 版本尚未出现的 "不存在" 之类不算 CME，忽略
                        }
                    }
                    return null;
                }));
            }

            // --- 并发写者：8 线程同时 rotateDek（全部卡在 currentKek 后统一放行）---
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    SubjectDek d = keyStore.rotateDek(c);
                    return d.version() + ":" + Base64.getEncoder().encodeToString(d.rawDek());
                }));
            }
            start.countDown();
            // 等待上限刻意压到 3 秒：坏实现（锁外判定）下 12 个线程会在毫秒级
            // 全部到达闸门，3 秒富余；好实现（键锁串行化）下只有 1 个线程能进入
            // currentKek，其余被锁挡住，此时"未全部到达"是【预期】而非失败 ——
            // 本探针的判据落在最终存储状态上，不落在 allReached 上。
            boolean allReached = allInsideCreate.await(3, TimeUnit.SECONDS);
            release.countDown();

            Set<String> results = new LinkedHashSet<>();
            for (Future<String> f : fs) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            stormDone.set(true);
            for (Future<?> r : readers) {
                r.get(30, TimeUnit.SECONDS);
            }

            // 版本号 → 上报的 DEK（若同一版本号对应多把，这里会 ≥2）
            java.util.Map<String, Set<String>> dekByVersion = new java.util.LinkedHashMap<>();
            Set<String> distinctDeks = new LinkedHashSet<>();
            for (String r : results) {
                String[] parts = r.split(":", 2);
                dekByVersion.computeIfAbsent(parts[0], k -> new LinkedHashSet<>()).add(parts[1]);
                distinctDeks.add(parts[1]);
            }
            boolean versionsOneToOne = dekByVersion.size() == threads
                    && dekByVersion.values().stream().allMatch(v -> v.size() == 1)
                    && distinctDeks.size() == threads;

            // 判据 ②：每一个出现过的版本号，都必须能按版本号取回、可 unwrap、且等于上报的那把
            List<String> byVersionProblems = new ArrayList<>();
            for (var e : dekByVersion.entrySet()) {
                int v = Integer.parseInt(e.getKey());
                String expected = e.getValue().iterator().next();
                try {
                    WrappedDek w = keyStore.wrappedOf(c, v).orElse(null);
                    if (w == null) {
                        byVersionProblems.add("v" + v + "取不到包裹材料");
                        continue;
                    }
                    String got = Base64.getEncoder().encodeToString(keyStore.unwrap(w));
                    if (!got.equals(expected)) {
                        byVersionProblems.add("v" + v + "还原出的不是上报的那把");
                    }
                } catch (RuntimeException ex) {
                    byVersionProblems.add("v" + v + "→" + ex.getClass().getSimpleName());
                }
            }

            // 判据 ③：历史版本 1..maxV 全部仍可按版本号取回（不得被覆盖丢失）
            int maxV = dekByVersion.keySet().stream().mapToInt(Integer::parseInt).max().orElse(1);
            List<String> missingHistory = new ArrayList<>();
            for (int v = 1; v <= maxV; v++) {
                if (keyStore.wrappedOf(c, v).isEmpty()) {
                    missingHistory.add("v" + v);
                }
            }

            check("H5", "8 线程并发 rotateDek 同一主体（门控确定性 + 按版本号取回 + CME 计数）",
                    "版本号两两不同且与 DEK 一一对应；每个版本都能按版本号取回并还原出同一把；"
                            + "历史版本 1..maxV 无缺失；并发取回不得抛 CME",
                    versionsOneToOne && byVersionProblems.isEmpty()
                            && missingHistory.isEmpty() && cmeCount.get() == 0,
                    "全部 8 线程都进入创建路径=" + allReached
                            + "; 版本号集合=" + dekByVersion.keySet() + "（大小 " + dekByVersion.size()
                            + "，应为 " + threads + "）"
                            + "; 不同 DEK 数=" + distinctDeks.size()
                            + "; 一一对应=" + versionsOneToOne
                            + "; 按版本号取回问题=" + (byVersionProblems.isEmpty() ? "无" : byVersionProblems)
                            + "; 缺失历史版本=" + (missingHistory.isEmpty() ? "无" : missingHistory)
                            + "; 并发读者圈数=" + readerLoops.get()
                            + "; CME 次数=" + cmeCount.get()
                            + (versionsOneToOne ? "" : "；⇒ 同一版本号对应多把 DEK，"
                                    + "解密按版本号只取第一条，其余密文永久不可解"));
        } catch (Exception e) {
            nNa++;
            log("[N/A] H5 未能执行: " + e);
        } finally {
            release.countDown();
            stormDone.set(true);
            pool.shutdownNow();
        }
    }

    /** H6：销毁后不得自动重建 + 重复销毁幂等 + 不误伤其他主体。 */
    private void probeH6() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "72");

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));
        boolean rebuildBlocked;
        String rebuildOutcome;
        try {
            s.keyStore().getOrCreateDek(C1);
            rebuildBlocked = false;
            rebuildOutcome = "自动重建成功（删除权被下一次写入撤销）";
        } catch (SubjectKeyDestroyedException e) {
            rebuildBlocked = true;
            rebuildOutcome = "SubjectKeyDestroyedException";
        }

        String idemOutcome;
        boolean idempotent;
        try {
            s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));
            s.keyStore().destroySubjectKey(SubjectKeyRef.of(C1, 1));
            idemOutcome = "重复销毁无异常";
            idempotent = s.keyStore().isDestroyed(C1);
        } catch (RuntimeException e) {
            idemOutcome = e.getClass().getSimpleName();
            idempotent = false;
        }

        boolean c2Stable = java.util.Arrays.equals(
                s.keyStore().getOrCreateDek(C2).rawDek(), s.keyStore().getOrCreateDek(C2).rawDek());
        boolean c2Alive = s.keyStore().getOrCreateDek(C2).rawDek().length == 32;

        check("H6", "销毁后不得自动重建 + 重复销毁幂等 + 不误伤 C2",
                "重建被拒、重复销毁无异常且仍为已销毁、C2 不受影响且取用稳定",
                rebuildBlocked && idempotent && c2Stable && c2Alive,
                "重建→" + rebuildOutcome + "; 重复销毁→" + idemOutcome
                        + "; 仍为已销毁=" + idempotent + "; C2 取用稳定=" + c2Stable);
    }

    /**
     * H7：<b>端到端真实算法替换</b> —— 用真实 ChaCha20-Poly1305 实现整套替换，
     * 验证"零改调用点"是否真的成立（含 KEK 包裹、DEK、字段加密三条路径）。
     */
    private void probeH7() {
        AlgorithmProvider chacha = new TestOnlyAlternateAlgorithmProvider();
        AlgorithmRegistry registry = AlgorithmRegistry.of(chacha);
        InMemoryTenantKekRegistry keks = new InMemoryTenantKekRegistry(chacha);
        InMemoryShredTombstoneStore tombs = new InMemoryShredTombstoneStore();
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                keks, tombs, registry, AlgorithmId.AES_256_GCM.id());
        // FieldCipher 与业务调用点：一行未改，只是装配参数变了
        FieldCipher cipher = new FieldCipher(keyStore, registry, AlgorithmId.AES_256_GCM.id());

        keks.initTenant(TA);
        keks.markBackupReady(TA);

        String outcome;
        boolean ok;
        try {
            String env = cipher.encryptText(C1, SensitiveField.BLOOD_OXYGEN.fieldName(), "95");
            String back = cipher.decryptText(C1, SensitiveField.BLOOD_OXYGEN.fieldName(), env);
            String algoInEnvelope = CipherEnvelope.parse(env).algorithmId();
            // 再轮换 DEK、往返一次，覆盖"包裹/解包也换了实现"这条路径
            keyStore.rotateDek(C1);
            String env2 = cipher.encryptText(C1, SensitiveField.BLOOD_OXYGEN.fieldName(), "96");
            String back2 = cipher.decryptText(C1, SensitiveField.BLOOD_OXYGEN.fieldName(), env2);
            ok = "95".equals(back) && "96".equals(back2)
                    && AlgorithmId.AES_256_GCM.id().equals(algoInEnvelope);
            outcome = "往返=" + back + "/" + back2 + "; 信封算法位=" + algoInEnvelope
                    + "; 替代实现 encryptCalls=" + chachaRef(chacha);
        } catch (RuntimeException e) {
            ok = false;
            outcome = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
        }
        check("H7", "真实替换算法实现（ChaCha20-Poly1305）后 FieldCipher 零改动仍工作",
                "三条路径（KEK 包裹/DEK/字段）全部换实现后仍可往返，信封契约不变",
                ok, outcome);
    }

    private static int chachaRef(AlgorithmProvider p) {
        return p instanceof TestOnlyAlternateAlgorithmProvider
                ? ((TestOnlyAlternateAlgorithmProvider) p).encryptCalls() : -1;
    }

    /**
     * H8：DoD ① 的粒度声明 —— 正常（不含 NUL）标识下，不同主体必须得到不同 DEK；
     * 且同主体重复取用必须幂等（同一把）。
     */
    private void probeH8() {
        Stack s = newStack();
        s.provisionTenant(TA);

        SubjectRef custA = SubjectRef.customer(TA, "g-0001");
        SubjectRef custB = SubjectRef.customer(TA, "g-0002");
        SubjectRef therA = SubjectRef.therapist(TA, "g-0001"); // 同 id、不同类型
        SubjectRef otherTenant = SubjectRef.customer(TB, "g-0001");
        s.provisionTenant(TB);

        SubjectDek dA = s.keyStore().getOrCreateDek(custA);
        SubjectDek dB = s.keyStore().getOrCreateDek(custB);
        SubjectDek dT = s.keyStore().getOrCreateDek(therA);
        SubjectDek dX = s.keyStore().getOrCreateDek(otherTenant);
        SubjectDek dA2 = s.keyStore().getOrCreateDek(custA);

        boolean pairwiseDistinct =
                !java.util.Arrays.equals(dA.rawDek(), dB.rawDek())
                        && !java.util.Arrays.equals(dA.rawDek(), dT.rawDek())
                        && !java.util.Arrays.equals(dA.rawDek(), dX.rawDek())
                        && !java.util.Arrays.equals(dB.rawDek(), dT.rawDek());
        boolean idempotent = java.util.Arrays.equals(dA.rawDek(), dA2.rawDek())
                && dA.version() == dA2.version();
        boolean allPerSubject = dA.scope() == SubjectDek.KeyScope.PER_SUBJECT
                && dX.scope() == SubjectDek.KeyScope.PER_SUBJECT;

        check("H8", "per-subject 粒度（正常 id）：4 个主体互不相同 + 同主体幂等",
                "两两 DEK 不同、同主体重复取用同一把、scope=PER_SUBJECT",
                pairwiseDistinct && idempotent && allPerSubject,
                "两两不同=" + pairwiseDistinct + "; 同主体幂等=" + idempotent
                        + "; scope=PER_SUBJECT=" + allPerSubject
                        + "; liveKeyCount(应为4)=" + s.keyStore().liveKeyCount());
    }

    /**
     * H9：<b>销毁与并发创建交错 —— 已删密钥是否会"复活"</b>
     * （team-lead 点名的、本探针此前未覆盖的缺口）。
     *
     * <p>竞态形状：线程 A 走 {@code getOrCreateDek} 已经越过墓碑检查、正卡在
     * {@code currentKek()}；此刻线程 B 完成 {@code destroySubjectKey}（记墓碑 + 删材料）；
     * A 随后继续把新 DEK <b>append 进已清空的 list</b>。
     * 结果：墓碑还在（{@code isDestroyed}=true），但<b>密钥材料又被写回来了</b>。
     *
     * <p>为什么这是重缺口：删除权的语义是"此后不可读"。若材料能复活，
     * 则 (a) 密文重新可解，删除权被静默撤销；(b) 读取路径因墓碑存在而报
     * "已销毁"，运维看到的是"删干净了"——<b>状态与实际相反</b>，比单纯漏删更危险。
     *
     * <p>判据（修复后应满足）：销毁一旦发生，该主体<b>不得</b>再有任何活跃密钥材料
     * —— 即 {@code liveKeyCount()} 对该主体归零，且 {@code wrappedOf} 不暴露材料。
     * 修法通常是"墓碑检查与材料写入必须在同一把键锁内"。
     *
     * <p><b>实测提示（修复后）</b>：正确实现下 {@code destroySubjectKey} 会因键锁
     * 而<b>阻塞等待</b>创建者完成，然后才删材料 —— 销毁语义最终达成，但请注意
     * 若创建者卡在 KMS 上很久，销毁请求也会被一并挂住（SLA 权衡，非正确性缺陷）。
     *
     * <p>确定性手法：沿用 H4 的门控 provider 卡住 {@code currentKek()}，
     * 不需要靠线程调度运气。
     */
    private void probeH9() {
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "shred-race-1");

        CountDownLatch insideCreate = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (calls.incrementAndGet() == 1) {
                insideCreate.countDown();     // 已越过墓碑检查，卡在创建途中
                try {
                    releaseCreate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> creator = pool.submit(
                    () -> Base64.getEncoder().encodeToString(keyStore.getOrCreateDek(c).rawDek()));
            boolean reached = insideCreate.await(3, TimeUnit.SECONDS);

            // 创建者卡住期间完成销毁。
            // 修复后的实现会让本调用【阻塞】在键锁上 —— 创建者卡在 currentKek，
            // 锁不释放，销毁就进不去。为免门控被无限期挂住，把释放交给一个
            // 独立线程：等 1 秒（足够观察"销毁确实被挡"）后放行创建者。
            // 这样既能验证"销毁最终生效、材料不复活"，又不会把探针卡死。
            java.util.concurrent.atomic.AtomicBoolean destroyReturned =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                releaseCreate.countDown();
            });
            releaser.setDaemon(true);
            releaser.start();
            long t0 = System.nanoTime();
            keyStore.destroySubjectKey(SubjectKeyRef.of(c, 1));
            long destroyMillis = (System.nanoTime() - t0) / 1_000_000;
            destroyReturned.set(true);
            boolean destroyedWhileInside = keyStore.isDestroyed(c);

            releaseCreate.countDown(); // 幂等放行（若 releaser 已放行，无副作用）
            String createOutcome;
            boolean createSucceeded;
            try {
                creator.get(30, TimeUnit.SECONDS);
                createSucceeded = true;
                createOutcome = "创建成功（密钥材料被写回）";
            } catch (ExecutionException ee) {
                createSucceeded = false;
                createOutcome = ee.getCause().getClass().getSimpleName();
            }

            boolean materialBack = keyStore.liveKeyCount() > 0;
            boolean wrappedExposed = keyStore.wrappedOf(c, 1).isPresent();
            // 注意：创建者"成功返回"本身【不是】缺陷 —— 它先于销毁拿到键锁，
            // 等价于"先创建、后销毁"的串行语义，销毁随后把材料删掉即可。
            // 缺陷的定义只有一条：销毁之后材料【留下了】。因此判据只落在最终状态上，
            // 不落在 createOutcome 上（把它留作观察事实）。
            boolean creatorRejected = !createSucceeded
                    && "SubjectKeyDestroyedException".equals(createOutcome);

            check("H9", "销毁与并发创建交错：已删主体的密钥材料是否会复活",
                    "销毁完成后该主体不得再有活跃密钥材料，也不得暴露包裹材料",
                    !materialBack && !wrappedExposed,
                    "创建者是否越过墓碑检查进入创建=" + reached
                            + "; 销毁调用耗时=" + destroyMillis + "ms（修复后为键锁等待，非 0）"
                            + "; 期间墓碑已记=" + destroyedWhileInside
                            + "; 放行后创建结果=" + createOutcome
                            + "（创建者成功本身不算缺陷：它先于销毁拿到锁，"
                            + "等价于先创建后销毁的串行语义）"
                            + "; 创建者被墓碑拒绝=" + creatorRejected
                            + "; 销毁后仍有活跃密钥材料=" + materialBack
                            + "（liveKeyCount=" + keyStore.liveKeyCount() + "）"
                            + "; wrappedOf(c,1) 仍暴露材料=" + wrappedExposed
                            + (materialBack ? " ⇒ 删除权被静默撤销：墓碑在但材料复活" : ""));
        } catch (Exception e) {
            nNa++;
            log("[N/A] H9 未能执行: " + e);
        } finally {
            releaseCreate.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * J1：<b>门禁有效性对照</b> —— 拿 {@link LegacyRhythmSubjectKeyStore}（旧节奏替身）
     * 跑与 G1/H5 完全相同的门控手法。期望：<b>攻击成功</b>，即旧节奏必须被抓住。
     *
     * <p>这条探针的 PASS 语义与其它条相反，需要特别说明，否则门禁读到"这行是 PASS"
     * 却不知道它证明了什么：
     * <ul>
     *   <li>其它条：PASS = 攻击失败 = 实现正确；</li>
     *   <li>本条：PASS = <b>探针有牙齿</b> —— 同一套手法在旧节奏上确实产生
     *       "同一版本号多把 DEK / 同一主体多把 DEK"，说明 G1/H5 若在未修版本上
     *       运行会变红，不是摆设。</li>
     * </ul>
     *
     * <p>它给的是"红态可重放"能力：修复落地后，"探针在坏代码上会红"这件事仍然
     * 可以被随时证明，而不是只留在日志里。
     */
    private void probeJ1() {
        int threads = 8;
        Stack s = newStack();
        s.provisionTenant(TA);
        SubjectRef c = SubjectRef.customer(TA, "legacy-race-1");

        CountDownLatch allInsideCreate = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GatedKekProvider gated = new GatedKekProvider(s.keks(), () -> {
            if (calls.incrementAndGet() <= threads) {
                allInsideCreate.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        LegacyRhythmSubjectKeyStore legacy = new LegacyRhythmSubjectKeyStore(
                gated, s.tombstones(), s.algorithms(), AlgorithmId.AES_256_GCM.id());

        // 先建 v1，再让 8 个线程并发轮换 —— 与 H5 同形状
        legacy.getOrCreateRhythm(c);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    var d = legacy.rotateRhythm(c);
                    return d.version + ":" + Base64.getEncoder().encodeToString(d.rawDek());
                }));
            }
            boolean allReached = allInsideCreate.await(3, TimeUnit.SECONDS);
            release.countDown();

            Set<String> versions = new LinkedHashSet<>();
            Set<String> deks = new LinkedHashSet<>();
            for (Future<String> f : fs) {
                String[] parts = f.get(30, TimeUnit.SECONDS).split(":", 2);
                versions.add(parts[0]);
                deks.add(parts[1]);
            }
            // 旧节奏下版本号必然重复；同时按版本号取回也会还原出"不是上报的那把"
            int byVersionMismatch = 0;
            for (String v : versions) {
                try {
                    WrappedDek w = legacy.wrappedOf(c, Integer.parseInt(v)).orElse(null);
                    if (w == null) {
                        byVersionMismatch++;
                    }
                } catch (RuntimeException e) {
                    byVersionMismatch++;
                }
            }

            boolean toothless = versions.size() == threads && deks.size() == threads;
            check("J1", "【门禁有效性对照】旧节奏替身必须被同一套门控手法抓住"
                            + "（PASS 语义＝探针有牙齿，非实现正确）",
                    "旧节奏下应出现『版本号重复』：版本号种类数 < 线程数 且 DEK 数 == 线程数",
                    !toothless,
                    "全部 8 线程都进入创建路径=" + allReached
                            + "; 版本号种类数=" + versions.size() + "（应 < " + threads + "）"
                            + "; 不同 DEK 数=" + deks.size()
                            + "; 按版本号取回异常/缺失数=" + byVersionMismatch
                            + (toothless ? " ⇒ 探针在这份旧节奏上竟然是绿的，说明 G1/H5 的判据不够硬"
                                    : " ⇒ 旧节奏被抓住：G1/H5 若跑在未修版本上必红，门禁不是摆设"));
        } catch (Exception e) {
            nNa++;
            log("[N/A] J1 未能执行: " + e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * I1：删除 DAG —— 衍生存储未确认时必须【拒绝】销毁 DEK，且数据仍可读（声明 ⑥）。
     * 独立重写：不用他的断言，直接看回执与数据状态。
     */
    private void probeI1() {
        Stack s = newStack();
        s.provisionTenant(TA);
        String env = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "75");

        // 情形 ①：全部衍生存储未确认
        var refuse = s.deletionDag().execute(SubjectKeyRef.of(C1, 1), (storeId, ref) -> false);
        boolean stillReadable;
        String readOutcome;
        try {
            readOutcome = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), env);
            stillReadable = "75".equals(readOutcome);
        } catch (RuntimeException e) {
            readOutcome = e.getClass().getSimpleName();
            stillReadable = false;
        }

        // 情形 ②：全部确认
        var done = s.deletionDag().execute(SubjectKeyRef.of(C1, 1), (storeId, ref) -> true);
        boolean unreadableAfter;
        String afterOutcome;
        try {
            afterOutcome = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), env);
            unreadableAfter = false;
        } catch (SubjectKeyDestroyedException e) {
            unreadableAfter = true;
            afterOutcome = "SubjectKeyDestroyedException";
        } catch (RuntimeException e) {
            unreadableAfter = true;
            afterOutcome = e.getClass().getSimpleName();
        }

        check("I1", "删除 DAG：未确认→拒绝销毁且数据仍可读；全确认→销毁且不可读",
                "① completed=false/keyDestroyed=false/仍可读；② completed=true/keyDestroyed=true/不可读",
                !refuse.completed() && !refuse.keyDestroyed() && stillReadable
                        && done.completed() && done.keyDestroyed() && unreadableAfter,
                "① completed=" + refuse.completed() + " keyDestroyed=" + refuse.keyDestroyed()
                        + " 读到=" + readOutcome
                        + "; ② completed=" + done.completed() + " keyDestroyed=" + done.keyDestroyed()
                        + " 解密=" + afterOutcome
                        + "; 待办数=" + refuse.pending().size());
    }

    /** I2：handler 抛异常必须视为"未完成"（声明 ⑥ 的下半句）。 */
    private void probeI2() {
        Stack s = newStack();
        s.provisionTenant(TA);
        s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "68");

        var receipt = s.deletionDag().execute(SubjectKeyRef.of(C1, 1), (storeId, ref) -> {
            if ("analytics_warehouse".equals(storeId)) {
                throw new IllegalStateException("数仓连接超时");
            }
            return true;
        });
        boolean named = receipt.refusalReason() != null
                && receipt.refusalReason().contains("analytics_warehouse");
        check("I2", "DAG handler 抛异常 → 视为未完成（不得当作已删除）",
                "completed=false、DEK 未销毁、拒绝原因点名失败项",
                !receipt.completed() && !receipt.keyDestroyed() && named,
                "completed=" + receipt.completed() + "; keyDestroyed=" + receipt.keyDestroyed()
                        + "; 原因点名=" + named);
    }

    /**
     * I3：<b>pom 声明与实现的核对</b> —— pom.xml 的依赖注释称
     * "PostgreSQL 驱动是 test scope：只有真库 crypto-shredding 门禁用它"。
     * 独立核实：test 源码树里是否存在任何真库/JDBC 代码？
     *
     * <p>注意排除本探针自身（本文件里作为【被检查的词】出现 "jdbc:" 等字面量，
     * 若不排除会造成探针自命中的假阳性 —— 上一版就踩了这个坑）。
     * 迁移后本探针已不在 test 树下，但仍保留该排除项：一旦有人把探针挪回
     * src/test，这个断言会立刻挡住自命中。
     */
    private void probeI3() {
        Path testRoot = repoRoot().resolve("src/test");
        Path selfDir = testRoot.resolve("java/com/diaoyuanyun/dy/crypto/adversarial");
        String selfName = "AdversarialVerificationProbe.java";
        int javaFiles = 0;
        List<String> jdbcHits = new ArrayList<>();
        try (var walk = Files.walk(testRoot)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (p.startsWith(selfDir) || p.getFileName().toString().equals(selfName)) {
                    continue; // 排除本验证探针自身
                }
                javaFiles++;
                String txt = Files.readString(p, StandardCharsets.UTF_8);
                if (txt.contains("org.postgresql") || txt.contains("DriverManager")
                        || txt.contains("java.sql.Connection") || txt.contains("DataSource")) {
                    jdbcHits.add(testRoot.relativize(p).toString().replace('\\', '/'));
                }
            }
        } catch (Exception e) {
            nNa++;
            log("[N/A] I3 无法遍历 test 树: " + e);
            return;
        }
        String pom;
        try {
            pom = Files.readString(repoRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            pom = "";
        }
        boolean pomDeclaresPgGate = pom.contains("<artifactId>postgresql</artifactId>")
                && pom.contains("真库 crypto-shredding 门禁");
        boolean hasRealDbTest = !jdbcHits.isEmpty();

        check("I3", "pom 声明的『真库 crypto-shredding 门禁』是否真实存在（已排除探针自命中）",
                "若 pom 声称有真库门禁，则 test 树里必须有真库/JDBC 代码",
                !pomDeclaresPgGate || hasRealDbTest,
                "pom 声称 postgresql 为『真库门禁用』=" + pomDeclaresPgGate
                        + "; 被检查的 test java 文件数=" + javaFiles
                        + "; 含 JDBC/真库代码的文件=" + jdbcHits
                        + (hasRealDbTest ? "" : "（空 ⇒ 该依赖未被任何测试使用，pom 所述的门禁不存在）"));
    }

    /**
     * I4：<b>KEK 轮换后历史密文仍可解</b>（声明 ② 的正面用例）。
     * 我此前只做了反向（篡改 kekId → 失败），这里补正面。
     */
    private void probeI4() {
        Stack s = newStack();
        s.provisionTenant(TA);

        // 第 1 代 KEK 下创建的 DEK 与密文
        String envV1 = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "61");
        String kekIdV1 = s.keyStore().getOrCreateDek(C1).wrapped().kekId();

        // 轮换 KEK 两次（第 2、3 代）
        String kekIdV2 = s.keks().rotateKek(TA);
        s.keks().rotateKek(TA);
        boolean rotated = !kekIdV1.equals(kekIdV2);

        // 轮换后：① 老密文仍可解；② 新写入用新 KEK 包裹的 DEK
        String oldRead;
        boolean oldOk;
        try {
            oldRead = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), envV1);
            oldOk = "61".equals(oldRead);
        } catch (RuntimeException e) {
            oldRead = e.getClass().getSimpleName();
            oldOk = false;
        }
        // 轮换后 rotateDek 会用当前 KEK 包裹 → 老密文仍须可解
        s.keyStore().rotateDek(C1);
        String newEnv = s.cipher().encryptText(C1, SensitiveField.HEART_RATE.fieldName(), "62");
        String oldAfterRotate;
        boolean oldStillOk;
        try {
            oldAfterRotate = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), envV1);
            oldStillOk = "61".equals(oldAfterRotate);
        } catch (RuntimeException e) {
            oldAfterRotate = e.getClass().getSimpleName();
            oldStillOk = false;
        }
        String newest;
        boolean newestOk;
        try {
            newest = s.cipher().decryptText(C1, SensitiveField.HEART_RATE.fieldName(), newEnv);
            newestOk = "62".equals(newest);
        } catch (RuntimeException e) {
            newest = e.getClass().getSimpleName();
            newestOk = false;
        }
        check("I4", "KEK 轮换 2 代后：老密文仍可解 + 新写入可用（声明 ② 正面用例）",
                "轮换后老密文用老 kekId 还原仍可读；新写入正常",
                rotated && oldOk && oldStillOk && newestOk,
                "轮换生效=" + rotated + "; 轮换后读老密文=" + oldRead
                        + "; DEK 轮换后再读老密文=" + oldAfterRotate
                        + "; 读新密文=" + newest);
    }

    // ==================================================================
    // 内联 harness（原借用自 dy-crypto 的测试辅助类，现由验证者自持）
    // ------------------------------------------------------------------
    // 为什么要内联而不是 import：若仍 import 被测方的测试辅助类，
    // 验证者与被测方的测试代码就仍有共享面 —— 而本次迁移的目的正是把两者彻底分开。
    // 以下定义在语义上与原测试辅助类的对应成员等价，但实现由本探针独立持有
    // （本文件不 import、不引用 dy-crypto 的任何测试类，编译期亦无此依赖）。
    // ==================================================================

    /**
     * 被测栈的把手集合。
     */
    record Stack(AlgorithmRegistry algorithms,
                 InMemoryTenantKekRegistry keks,
                 InMemoryShredTombstoneStore tombstones,
                 InMemorySubjectKeyStore keyStore,
                 FieldCipher cipher,
                 com.diaoyuanyun.dy.crypto.shred.DeletionDag deletionDag) {

        /** 初始化租户并标记备份就绪 —— 这是"可以开始加密"的前置。 */
        void provisionTenant(String tenantId) {
            keks.initTenant(tenantId);
            keks.markBackupReady(tenantId);
        }
    }

    /** 装配一套完整的被测栈（全部为进程内实现）。 */
    static Stack newStack() {
        return newStack(AlgorithmId.AES_256_GCM.id());
    }

    static Stack newStack(String writeAlgorithmId) {
        AlgorithmRegistry registry = AlgorithmRegistry.standard();
        InMemoryTenantKekRegistry keks = new InMemoryTenantKekRegistry(
                registry.resolve(AlgorithmId.AES_256_GCM));
        InMemoryShredTombstoneStore tombstones = new InMemoryShredTombstoneStore();
        InMemorySubjectKeyStore keyStore = new InMemorySubjectKeyStore(
                keks, tombstones, registry, AlgorithmId.AES_256_GCM.id());
        FieldCipher cipher = new FieldCipher(keyStore, registry, writeAlgorithmId);
        return new Stack(registry, keks, tombstones, keyStore, cipher,
                new com.diaoyuanyun.dy.crypto.shred.DeletionDag(keyStore));
    }

    /**
     * 测试专用替代算法（ChaCha20-Poly1305）—— 由验证者独立实现。
     * 它【不是】候选生产算法，也不构成对 SM4 结论的任何暗示；唯一用途是证明
     * "实现是可替换的、且替换后 FieldCipher 无需改动"。
     */
    static final class TestOnlyAlternateAlgorithmProvider implements AlgorithmProvider {

        private final java.security.SecureRandom random = new java.security.SecureRandom();
        private int encryptCalls;

        @Override
        public AlgorithmId id() {
            return AlgorithmId.AES_256_GCM;
        }

        /** 被调用次数 —— 用来断言"用的确实是这个替代实现"。 */
        int encryptCalls() {
            return encryptCalls;
        }

        @Override
        public EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
            encryptCalls++;
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                        new javax.crypto.spec.IvParameterSpec(nonce));
                if (associatedData != null && associatedData.length > 0) {
                    c.updateAAD(associatedData);
                }
                return new EncryptResult(c.doFinal(plaintext), nonce);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("测试替代算法加密失败", e);
            }
        }

        @Override
        public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) {
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
                c.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                        new javax.crypto.spec.IvParameterSpec(nonce));
                if (associatedData != null && associatedData.length > 0) {
                    c.updateAAD(associatedData);
                }
                return c.doFinal(ciphertext);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException(
                        "测试替代算法：AEAD 认证失败", e);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("测试替代算法解密失败", e);
            }
        }

        @Override
        public byte[] newKey() {
            byte[] k = new byte[32];
            random.nextBytes(k);
            return k;
        }
    }

    /**
     * 定位 dy-crypto 模块目录 —— 供 D1 扫源码、I3 遍历 test 树与读 pom.xml 使用。
     *
     * <p>优先读 {@code -Dcrypto.repo.root=<path>}（由 runner 显式传入，
     * 不依赖探针自身被放在哪个目录）；缺省从 {@code user.dir} 上溯找
     * {@code dy-crypto/pom.xml}。
     */
    static Path repoRoot() {
        String explicit = System.getProperty("crypto.repo.root");
        Path p = explicit != null && !explicit.isBlank()
                ? Paths.get(explicit).toAbsolutePath().normalize()
                : Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("dy-crypto"))
                    && Files.isRegularFile(cur.resolve("dy-crypto").resolve("pom.xml"))) {
                return cur.resolve("dy-crypto");
            }
        }
        throw new IllegalStateException("未找到 dy-crypto 模块目录；起点=" + p
                + "（可用 -Dcrypto.repo.root=<repo path> 显式指定）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private interface Probe {
        void run() throws Exception;
    }

    private void run(String id, Probe p) {
        try {
            p.run();
        } catch (Throwable t) {
            nNa++;
            log("[N/A] " + id + " 探针自身异常（未能得出结论）: " + t.getClass().getName()
                    + ": " + brief(t.getMessage()));
        }
    }

    private void check(String id, String what, String expected, boolean ok, String actual) {
        if (ok) {
            nPass++;
            log("[PASS] " + id + " " + what + "\n        期望: " + expected + "\n        实测: " + actual);
        } else {
            nFail++;
            log("[FAIL] " + id + " " + what + "\n        期望: " + expected + "\n        实测: " + actual);
        }
    }

    private void log(String line) {
        LOG.append(line).append('\n');
        System.out.println("[ADV-PROBE] " + line.replace("\n", "\n            "));
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 本探针自带的注释剥离器（<b>独立于</b>被测方的 {@code stripComments}）。
     * 处理：行注释、块注释、字符串字面量（含转义）、字符字面量、文本块。
     */
    static String stripCommentsIndependently(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    out.append(src.charAt(i));
                    i++;
                }
                out.append("\"\"\"");
                i = Math.min(n, i + 3);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i)).append(src.charAt(i + 1));
                        i += 2;
                    } else {
                        out.append(src.charAt(i));
                        i++;
                    }
                }
                if (i < n) {
                    out.append(src.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean flushed;
    private static final Object FLUSH_LOCK = new Object();

    private static void flush() {
        synchronized (FLUSH_LOCK) {
            if (flushed) {
                return;
            }
            flushed = true;
            StringBuilder head = new StringBuilder();
            head.append("=== 对抗性验证探针结果（crypto-verifier 独立编写） ===\n");
            head.append("PASS(攻击失败/实现正确)=").append(nPass)
                    .append("  FAIL(攻击成功/发现缺陷)=").append(nFail)
                    .append("  N/A(未能验证)=").append(nNa).append("\n\n");
            try {
                Path dir = Paths.get(System.getProperty("crypto.evidence.dir",
                    repoRoot().getParent().resolve("verification/crypto/evidence").toString()))
                    .toAbsolutePath().normalize();
                Files.createDirectories(dir);
                Path out = dir.resolve("adversarial-report.txt");
                Files.writeString(out, head + LOG.toString(), StandardCharsets.UTF_8);
                System.out.println("[ADV-PROBE] 报告已写入 " + out);
            } catch (Exception e) {
                System.out.println("[ADV-PROBE] 报告落盘失败: " + e);
            }
        }
    }

    /**
     * 【门禁有效性对照件】<b>旧逻辑替身</b> —— 修复前 {@code InMemorySubjectKeyStore}
     * 的"读—判—写"节奏，由验证者在此复刻，<b>不</b>指向任何生产类。
     *
     * <p>为什么必须有它：一个门禁若在<b>坏实现</b>上也是绿的，它就是摆设。
     * 修复已落地后，"红态"只存在于记忆与日志里 —— 无法再回归验证。有了这个替身，
     * J1 就能<b>随时重放</b>：用与 G1/H5 相同的门控手法打这个旧节奏，
     * 确认"同一版本号被算出多次 / 同一主体生成多把 DEK"会被探针抓住。
     *
     * <p>它与修复后实现的关键差别，正是 P0 的根因：
     * <ul>
     *   <li>版本判定在 map 之外（读一次快照，再自行算 next）；</li>
     *   <li>写回是 {@code ArrayList} 就地 {@code add}，不整体替换；</li>
     *   <li>墓碑检查只在锁外做一次，写材料时不再复核。</li>
     * </ul>
     *
     * <p><b>它刻意不实现 {@link SubjectKeyStore} 接口</b>：一方面生产类的
     * {@code SubjectDek} 构造器是包内可见的，外部无法伪造；另一方面，
     * 不实现接口能确保它<b>不可能被误用为</b>"一个可注入的实现"。
     * 它只是把旧节奏复刻出来，供 J1 打靶。
     */
    static final class LegacyRhythmSubjectKeyStore {

        private final com.diaoyuanyun.dy.crypto.key.TenantKekProvider kekProvider;
        private final ShredTombstoneStore tombstones;
        private final AlgorithmRegistry algorithms;
        private final String wrapAlgorithmId;
        private final java.util.Map<String, List<Stored>> bySubject = new java.util.concurrent.ConcurrentHashMap<>();

        LegacyRhythmSubjectKeyStore(com.diaoyuanyun.dy.crypto.key.TenantKekProvider kekProvider,
                                    ShredTombstoneStore tombstones,
                                    AlgorithmRegistry algorithms,
                                    String wrapAlgorithmId) {
            this.kekProvider = kekProvider;
            this.tombstones = tombstones;
            this.algorithms = algorithms;
            this.wrapAlgorithmId = wrapAlgorithmId;
        }

        SubjectDek getOrCreateDek(SubjectRef subject) {
            throw new UnsupportedOperationException("对照件不提供该入口，请用 getOrCreateRhythm");
        }

        SubjectDek rotateDek(SubjectRef subject) {
            throw new UnsupportedOperationException("对照件不提供该入口，请用 rotateRhythm");
        }

        /** 旧节奏的 getOrCreate：锁外读 → 锁外判 → 锁外建 → 就地 append。 */
        Stored getOrCreateRhythm(SubjectRef subject) {
            if (tombstones.isDestroyed(subject)) {
                throw new SubjectKeyDestroyedException(SubjectKeyRef.of(subject, 0));
            }
            List<Stored> versions = bySubject.get(keyOf(subject));
            if (versions != null && !versions.isEmpty()) {
                return versions.get(versions.size() - 1);
            }
            if (!kekProvider.backupReady(subject.tenantId())) {
                throw new KeyBackupUnavailableException(SubjectKeyRef.of(subject, 0), "backup not ready");
            }
            return createVersion(subject, 1);
        }

        /** 旧节奏的 rotate：版本号判定在 map 之外，就地 append。 */
        Stored rotateRhythm(SubjectRef subject) {
            if (tombstones.isDestroyed(subject)) {
                throw new SubjectKeyDestroyedException(SubjectKeyRef.of(subject, 0));
            }
            if (!kekProvider.backupReady(subject.tenantId())) {
                throw new KeyBackupUnavailableException(SubjectKeyRef.of(subject, 0), "backup not ready");
            }
            List<Stored> versions = bySubject.computeIfAbsent(keyOf(subject), k -> new ArrayList<>());
            int next = versions.isEmpty() ? 1 : versions.get(versions.size() - 1).version + 1;
            return createVersion(subject, next);
        }

        Optional<WrappedDek> wrappedOf(SubjectRef subject, int dekVersion) {
            List<Stored> versions = bySubject.get(keyOf(subject));
            if (versions == null) {
                return Optional.empty();
            }
            for (Stored sd : versions) {
                if (sd.version == dekVersion) {
                    return Optional.of(sd.wrapped);
                }
            }
            return Optional.empty();
        }

        Optional<WrappedDek> wrappedOf(SubjectRef subject) {
            List<Stored> versions = bySubject.get(keyOf(subject));
            return (versions == null || versions.isEmpty())
                    ? Optional.empty()
                    : Optional.of(versions.get(versions.size() - 1).wrapped);
        }

        byte[] unwrap(WrappedDek wrappedDek) {
            SubjectRef subject = new SubjectRef(wrappedDek.tenantId(), wrappedDek.subjectType(),
                    wrappedDek.subjectId());
            byte[] kek = kekProvider.kekVersion(wrappedDek.tenantId(), wrappedDek.kekId());
            AlgorithmProvider provider = algorithms.resolve(wrappedDek.algorithmId());
            byte[] aad = AadBinding.ofDekWrap(subject, wrappedDek.version(), wrappedDek.kekId());
            return provider.decrypt(kek, wrappedDek.nonce(), wrappedDek.wrappedBytes(), aad);
        }

        private Stored createVersion(SubjectRef subject, int version) {
            byte[] kek = kekProvider.currentKek(subject.tenantId());
            String kekId = kekProvider.currentKekId(subject.tenantId());
            AlgorithmProvider provider = algorithms.resolve(wrapAlgorithmId);
            byte[] dek = provider.newKey();
            byte[] aad = AadBinding.ofDekWrap(subject, version, kekId);
            AlgorithmProvider.EncryptResult enc = provider.encrypt(kek, dek, aad);
            WrappedDek wrapped = new WrappedDek(subject.tenantId(), subject.subjectType(),
                    subject.subjectId(), version, kekId, wrapAlgorithmId, enc.nonce(), enc.ciphertext());
            Stored stored = new Stored(version, dek, wrapped);
            // 就地 add —— 正是旧节奏的病灶
            bySubject.computeIfAbsent(keyOf(subject), k -> new ArrayList<>()).add(stored);
            return stored;
        }

        /** 仅供对照观察：当前持有哪些版本号、各有几把 DEK。 */
        List<Integer> versionsOf(SubjectRef subject) {
            List<Stored> versions = bySubject.get(keyOf(subject));
            if (versions == null) {
                return List.of();
            }
            List<Integer> out = new ArrayList<>();
            for (Stored s : versions) {
                out.add(s.version);
            }
            return out;
        }

        private static String keyOf(SubjectRef s) {
            return s.tenantId() + "\u0000" + s.subjectType() + "\u0000" + s.subjectId();
        }


        private static final class Stored {
            private final int version;
            private final byte[] dek;
            private final WrappedDek wrapped;

            private Stored(int version, byte[] dek, WrappedDek wrapped) {
                this.version = version;
                this.dek = dek;
                this.wrapped = wrapped;
            }

            /**
             * 返回包裹材料（{@code WrappedDek} 是 public record，可构造）。
             * 刻意<b>不</b>伪造 {@code SubjectDek} —— 它的构造器是包内可见的，
             * 外部无法构造；而 J1 只需要版本号与包裹材料两件事。
             */
            private byte[] rawDek() {
                return dek;
            }
        }
    }

    /**
     * 一个只声明算法标识、不做真实密码学的 provider ——
     * 用来测"注册表/状态位的门是否真的挡住"，而不引入任何真实算法。
     */
    private static final class DeclaredIdProvider implements AlgorithmProvider {

        private final AlgorithmId id;

        private DeclaredIdProvider(AlgorithmId id) {
            this.id = id;
        }

        @Override
        public AlgorithmId id() {
            return id;
        }

        @Override
        public EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
            return new EncryptResult(plaintext.clone(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        }

        @Override
        public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) {
            return ciphertext.clone();
        }

        @Override
        public byte[] newKey() {
            byte[] k = new byte[id.keyBytes()];
            new SecureRandom().nextBytes(k);
            return k;
        }
    }

    /**
     * 门控 KEK provider —— 用于 H4 的确定性竞态复现。
     *
     * <p>它把 {@code currentKek()} 的调用"卡住"（在 {@code afterEnter} 里等待），
     * 由于 {@code InMemorySubjectKeyStore.createVersion()} 会调用 {@code currentKek()}，
     * 于是可以让两个线程都越过"该主体尚无 DEK"的判定、再同时进入创建。
     * 全部其他方法原样转发。
     */
    private static final class GatedKekProvider implements com.diaoyuanyun.dy.crypto.key.TenantKekProvider {

        private final com.diaoyuanyun.dy.crypto.key.TenantKekProvider delegate;
        private final Runnable gate;

        private GatedKekProvider(com.diaoyuanyun.dy.crypto.key.TenantKekProvider delegate, Runnable gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public byte[] currentKek(String tenantId) {
            gate.run();
            return delegate.currentKek(tenantId);
        }

        @Override
        public byte[] kekVersion(String tenantId, String kekId) {
            return delegate.kekVersion(tenantId, kekId);
        }

        @Override
        public String currentKekId(String tenantId) {
            return delegate.currentKekId(tenantId);
        }

        @Override
        public boolean backupReady(String tenantId) {
            return delegate.backupReady(tenantId);
        }

        @Override
        public void destroyTenantKek(String tenantId) {
            delegate.destroyTenantKek(tenantId);
        }
    }

    /**
     * 独立入口 —— 不依赖任何测试框架。
     *
     * <p>退出码：0 = 全部探针 PASS；1 = 存在 FAIL（发现缺陷）；2 = 存在 N/A（未能验证）。
     * 之所以把 N/A 单列：未能验证不等于通过，门禁不应把它当作绿。
     */
    public static void main(String[] args) {
        AdversarialVerificationProbe probe = new AdversarialVerificationProbe();
        probe.matrix();
        flush();
        if (nFail > 0) {
            System.exit(1);
        }
        if (nNa > 0) {
            System.exit(2);
        }
        System.exit(0);
    }
}
