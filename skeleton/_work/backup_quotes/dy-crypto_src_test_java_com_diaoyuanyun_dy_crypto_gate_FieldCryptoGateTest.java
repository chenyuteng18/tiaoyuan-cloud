package com.diaoyuanyun.dy.crypto.gate;

import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmId;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry;
import com.diaoyuanyun.dy.crypto.algorithm.AlgorithmStatus;
import com.diaoyuanyun.dy.crypto.algorithm.PendingAlgorithmConfirmationException;
import com.diaoyuanyun.dy.crypto.envelope.AadBinding;
import com.diaoyuanyun.dy.crypto.envelope.CipherAuthenticationException;
import com.diaoyuanyun.dy.crypto.envelope.CipherEnvelope;
import com.diaoyuanyun.dy.crypto.envelope.EnvelopeFormatException;
import com.diaoyuanyun.dy.crypto.envelope.SubjectRef;
import com.diaoyuanyun.dy.crypto.field.FieldCipher;
import com.diaoyuanyun.dy.crypto.field.SensitiveField;
import com.diaoyuanyun.dy.crypto.key.KeyBackupUnavailableException;
import com.diaoyuanyun.dy.crypto.key.SubjectDek;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.key.WrappedDek;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A8 字段级加密门禁 —— DoD ① ② ③ ⑤ 的<b>可执行证据</b>。
 *
 * <h1>这组测试断言的是什么（以及不是什么）</h1>
 * 全部断言都是<b>可观察行为</b>：真实加密后解密得到的字节、真实抛出的异常类型、
 * 真实从 {@code wrappedDek} 还原出的密钥能否解开真实密文。没有任何一条是
 * "我刚写的常量等于它自己"或"我刚定义的枚举值等于它自己"。
 *
 * <h1>三条刻意写下的反向验证（DoD 要求，见 {@link #reverseVerificationEvidence}）</h1>
 * <ol>
 *   <li><b>拿错 KEK 解不开</b> —— 用 {@link CryptoTestHarness.MisdeliveringKekProvider}
 *       投递一把错误的 KEK，断言解包失败。</li>
 *   <li><b>DEK 销毁后密文不可恢复</b> —— 销毁后再用原密文解密，断言
 *       {@link SubjectKeyDestroyedException}；并<b>撤掉墓碑</b>做对照，
 *       证明"墓碑是承重的"而不是装饰。</li>
 *   <li><b>篡改密文必须被检出</b> —— 逐字节翻转载荷，断言
 *       {@link CipherAuthenticationException} 而非"解出一段错值"。</li>
 * </ol>
 * 每条注入都额外断言"注入确实生效了"（例如篡改后的密文确实与原文不同），
 * 否则"报了错"可能来自别的行，结论无从归因。
 */
class FieldCryptoGateTest {

    private static final StringBuilder EVIDENCE = new StringBuilder();

    private static final SubjectRef CUST_1 =
            SubjectRef.customer(CryptoTestHarness.TENANT_A, CryptoTestHarness.CUSTOMER_1);
    private static final SubjectRef CUST_2 =
            SubjectRef.customer(CryptoTestHarness.TENANT_A, CryptoTestHarness.CUSTOMER_2);

    /**
     * 施加"字面子串检查"所需的最小明文长度。
     *
     * <p>这条检查是<b>概率断言</b>而非逻辑断言，故必须限定适用范围（推导见
     * {@link #dod_required_three_fields_encrypt_and_roundtrip()}）：对 n 字节明文，
     * 在随机的 (n+16) 字节密文中出现该子串的期望次数约为 {@code (n+16)/256^n}。
     * n=2 时约 1.1e-3（不可用），n=4 时约 4.7e-9（可用）。取 4。
     */
    private static final int MIN_LITERAL_SCAN_LENGTH = 4;

    @AfterAll
    static void dump() {
        CryptoTestHarness.dumpEvidence("reverse-verification-evidence.txt", EVIDENCE);
    }

    // ==================================================================
    // DoD ① 敏感个人信息以 per-subject DEK 加密
    // ==================================================================

    @Test
    @DisplayName("DoD1 心率/血氧/睡眠：三项逐一加密后密文不含明文，且能正确解回")
    void dod_required_three_fields_encrypt_and_roundtrip() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        // 按 DoD 的措辞取被测字段（心率/血氧/睡眠），而不是自己挑几个凑集合 ——
        // 自选集合会让"被测对象"与"DoD 要求"悄悄脱钩。
        String[] samples = {"72", "98", "{\"deep\":210,\"light\":180}“};
        int i = 0;
        for (SensitiveField f : SensitiveField.dodRequiredThree()) {
            String plaintext = samples[i++];
            String envelope = s.cipher().encryptText(CUST_1, f.fieldName(), plaintext);

            assertEnvelopeHides(f.label(), plaintext, envelope);

            assertEquals(plaintext, s.cipher().decryptText(CUST_1, f.fieldName(), envelope),
                    f.label() + ” 解密结果与明文不符");
            // 信封必须带算法位与 DEK 版本（DoD ⑤ 与 DEK 轮换的前提）
            CipherEnvelope parsed = CipherEnvelope.parse(envelope);
            assertEquals(AlgorithmId.AES_256_GCM.id(), parsed.algorithmId(), "信封缺少/错写算法位");
            assertEquals(1, parsed.dekVersion(), "首个信封的 DEK 版本应为 v1");
        }
    }

    /**
     * 「密文里没有明文」这一命题的判据。<b>对每个输入都严格强于 {@code envelope.contains(plaintext)}，
     * 且零假阳性。</b>
     *
     * <h2>问题一：检查面错了（原版连"原样回填"都抓不到）</h2>
     * 原版断的是 {@code envelope.contains(plaintext)} —— 而信封里的密文段是
     * <b>Base64</b> 文本。{@code base64("72") == "NzI="}，明文在 base64 里根本不会以原样出现。
     * 换言之：一个把明文原样当密文回填的实现，<b>照样能通过</b>原版断言，
     * 而原版断言唯一会命中的其实是"随机 base64 恰好含有该子串"。
     * <b>检查面应该是解码后的密文字节</b>，不是编码后的落库文本。本方法已改正。
     *
     * <h2>问题二：概率性假红</h2>
     * 原版对 2 字符明文做子串检查，是<b>概率事件</b>而非逻辑判断。实测（20 万次采样）：
     * 单字段假阳性 ≈ 0.94%，三字段合计 ≈ <b>1.9%</b> —— 约每 50 次构建无端红一次。
     * 概率推导：n 字节明文在某段 m 字节随机数据中出现子串的期望次数约 {@code m/256^n}。
     * 故对"子串"这一判据设长度门（{@value #MIN_LITERAL_SCAN_LENGTH} 字符），
     * 而"逐字节相等"与"长度下界"两条<b>与随机性无关</b>，对所有输入恒可用。
     *
     * <h2>本方法实际断言的三件事（逐一零假阳性）</h2>
     * <ol>
     *   <li><b>解码后的密文字节 ≠ 明文字节</b> —— 抓住"原样回填"，与随机性无关。</li>
     *   <li><b>密文长度 ≥ 明文长度 + 16</b> —— AEAD（GCM tag 128 位）的硬约束；
     *       "加密"若只是原样搬运，长度会相等。</li>
     *   <li><b>（明文足够长时）解码后的密文字节不含明文字节，且落库信封文本也不含明文</b>
     *       —— 后者专门抓"有人把明文未编码地塞进了信封"这一真实缺陷类别。</li>
     * </ol>
     * 第 1、2 条覆盖了原版断言的意图，第 3 条在长度足够时覆盖得更严。
     */
    private static void assertEnvelopeHides(String label, String plaintext, String envelope) {
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        CipherEnvelope env = CipherEnvelope.parse(envelope);
        byte[] ct = env.ciphertext();

        // ① 解码后的密文字节不得与明文逐字节相同 —— 对所有输入恒可用。
        assertFalse(Arrays.equals(plainBytes, ct),
                label + " 的解码密文与明文逐字节相同 —— 未真正加密，只是原样回填");

        // ② AEAD 的长度下界：密文 ≥ 明文 + tag(16B)。
        assertTrue(ct.length >= plainBytes.length + 16,
                label + " 密文长度不足（AEAD 至少要含 16 字节 tag）："
                        + "明文=" + plainBytes.length + "B 密文=" + ct.length + "B");

        if (plaintext.length() < MIN_LITERAL_SCAN_LENGTH) {
            return; // 子串判据在短明文下会概率性假红，到此为止（上面两条已足够有判别力）。
        }
        // ③ 长明文：解码后的密文字节中不得出现明文字节。
        assertFalse(indexOf(ct, plainBytes) >= 0,
                label + " 的解码密文中出现了明文字节串 —— 未真正加密");
        // ④ 长明文：落库信封文本里也不得出现明文（抓"明文未编码地塞进信封"这类缺陷）。
        assertFalse(envelope.contains(plaintext),
                label + " 的信封文本里出现了明文 '" + plaintext + "' —— 明文被未编码地写进了信封");
    }

    @Test
    @DisplayName("DoD1 粒度：不同主体必须得到【不同】的 DEK —— 这是『per-subject』的定义")
    void per_subject_means_distinct_deks() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        SubjectDek dek1 = s.keyStore().getOrCreateDek(CUST_1);
        SubjectDek dek2 = s.keyStore().getOrCreateDek(CUST_2);

        assertEquals(SubjectDek.KeyScope.PER_SUBJECT, dek1.scope(),
                "密钥粒度必须是 per-subject —— ADR-12 已否决『仅租户级密钥』");
        assertFalse(Arrays.equals(dek1.rawDek(), dek2.rawDek()),
                "两个主体拿到了同一把 DEK —— per-subject 粒度不成立，"
                        + "这等于退化成被 ADR-12 否决的『仅租户级密钥』");

        // 同一主体重复取必须得到【同一把】DEK（幂等），否则每次加密都用新密钥，
        // 历史密文会在下一次读取时解不开。
        SubjectDek dek1Again = s.keyStore().getOrCreateDek(CUST_1);
        assertTrue(Arrays.equals(dek1.rawDek(), dek1Again.rawDek()),
                "同一主体两次取回的 DEK 不同 —— 密钥未持久化，历史密文将无法解密");
        assertEquals(dek1.version(), dek1Again.version(), "同一主体重复取用不应产生新版本");
    }

    @Test
    @DisplayName("DoD1 粒度：租户相同时，主体 id 不同即密钥不同（不做 id 空间合并）")
    void same_tenant_distinct_subject_ids_are_separate() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        // customer-0001 与 therapist-0001：id 相同、类型不同 —— 不得共用 DEK。
        // 若共用，两类自然人的删除权会互相牵制（删一个连带把另一个变不可读）。
        SubjectDek customer = s.keyStore().getOrCreateDek(
                SubjectRef.customer(CryptoTestHarness.TENANT_A, "x-0001"));
        SubjectDek therapist = s.keyStore().getOrCreateDek(
                SubjectRef.therapist(CryptoTestHarness.TENANT_A, "x-0001"));

        assertFalse(Arrays.equals(customer.rawDek(), therapist.rawDek()),
                "subjectType 未参与密钥作用域 —— customer-0001 与 therapist-0001 共用了 DEK");
        assertEquals(2, s.keyStore().liveKeyCount(), "应为两个主体各建一把 DEK");
    }

    // ==================================================================
    // DoD ② 租户级 KEK 包裹 DEK
    // ==================================================================

    @Test
    @DisplayName("DoD2 信封加密：wrappedDek 不含明文 DEK；用 KEK 能还原出同一把 DEK")
    void dek_is_wrapped_by_tenant_kek() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        SubjectDek dek = s.keyStore().getOrCreateDek(CUST_1);
        WrappedDek wrapped = dek.wrapped();

        // ① 包裹体里不得出现明文 DEK 字节
        byte[] raw = dek.rawDek();
        assertFalse(indexOf(wrapped.wrappedBytes(), raw) >= 0,
                "wrappedDek 里出现了明文 DEK 字节 —— DEK 没有被 KEK 包裹");
        assertEquals(CryptoTestHarness.TENANT_A, wrapped.tenantId(), "包裹体归属租户应为 TENANT_A");
        assertTrue(wrapped.kekId().contains(CryptoTestHarness.TENANT_A),
                "kekId 应可追溯到租户，实际=" + wrapped.kekId());

        // ② 用 KEK 还原出的 DEK 必须能解开用原 DEK 加密的密文 ——
        //    这才是"包裹确实可逆"的证据（而不是"字段长得像密文"）。
        String envelope = s.cipher().encryptText(CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), "97");
        byte[] unwrapped = s.keyStore().unwrap(wrapped);
        assertTrue(Arrays.equals(raw, unwrapped),
                "从 wrappedDek 还原出的 DEK 与原 DEK 不同 —— 包裹/解包不是可逆的");
        assertEquals("97", s.cipher().decryptText(CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), envelope),
                "还原出的 DEK 无法解开原密文");
    }

    @Test
    @DisplayName("DoD2 信封加密：DEK 不落明文 —— 销毁 KEK 后 wrappedDek 无法还原")
    void destroying_tenant_kek_makes_wrapped_dek_unrecoverable() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        SubjectDek dek = s.keyStore().getOrCreateDek(CUST_1);
        WrappedDek wrapped = dek.wrapped();

        assertTrue(s.keyStore().unwrap(wrapped).length == 32, "前置：销毁前应能还原 DEK");

        s.keks().destroyTenantKek(CryptoTestHarness.TENANT_A);

        assertThrows(Exception.class, () -> s.keyStore().unwrap(wrapped),
                "销毁租户 KEK 后仍能还原 DEK —— KEK 不是承重的，wrappedDek 形同明文");
    }

    @Test
    @DisplayName("DoD2 信封加密：KEK 轮换后【历史】wrappedDek 仍可还原（取 kekId 而非当前 KEK）")
    void kek_rotation_keeps_historical_wrapped_dek_readable() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        // 用第 1 代 KEK 包裹的 DEK
        SubjectDek dekV1 = s.keyStore().getOrCreateDek(CUST_1);
        WrappedDek wrappedV1 = dekV1.wrapped();
        String kekIdV1 = wrappedV1.kekId();

        // 轮换 KEK（第 2 代）
        String kekIdV2 = s.keks().rotateKek(CryptoTestHarness.TENANT_A);
        assertNotEquals(kekIdV1, kekIdV2, "轮换后 kekId 应变化");
        assertEquals(2, s.keks().kekVersionCount(CryptoTestHarness.TENANT_A), "应有 2 代 KEK");

        // 历史 wrappedDek 仍必须可还原 —— 若实现用 currentKek，这一步会失败
        byte[] restored = s.keyStore().unwrap(wrappedV1);
        assertTrue(Arrays.equals(dekV1.rawDek(), restored),
                "KEK 轮换后历史 wrappedDek 无法还原 —— 轮换等于让历史数据永久不可读"
                        + "（一次『意外的 crypto-shredding』）");
    }

    // ==================================================================
    // DoD ③ crypto-shredding
    // ==================================================================

    @Test
    @DisplayName("DoD3 删除权：销毁 DEK 后密文【原样保留】但解密抛『已销毁』")
    void crypto_shredding_keeps_ciphertext_but_makes_it_unrecoverable() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        SubjectDek dek = s.keyStore().getOrCreateDek(CUST_1);

        String heartRate = s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "81");
        String sleep = s.cipher().encryptText(CUST_1, SensitiveField.SLEEP_STAGES.fieldName(),
                "{\"deep\":200}");
        assertEquals("81", s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), heartRate),
                "前置：销毁前必须能正常解密");

        // === 执行删除权：销毁 DEK ===
        s.keyStore().destroySubjectKey(SubjectKeyRef.of(CUST_1, dek.version()));

        // ① 密文仍在（调用方手里那份字符串没有被改动）
        assertFalse(heartRate.isBlank(), "密文文本应保留（crypto-shredding 不删密文）");
        assertEquals(AlgorithmId.AES_256_GCM.id(), CipherEnvelope.parse(heartRate).algorithmId(),
                "密文结构应完好 —— 删除的是密钥，不是数据");

        // ② 解密必须失败，且失败类型是"已销毁"而不是泛泛的失败
        SubjectKeyDestroyedException e1 = assertThrows(SubjectKeyDestroyedException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), heartRate),
                "销毁 DEK 后仍能解密 —— crypto-shredding 未生效");
        assertTrue(e1.getMessage().contains("不可恢复"),
                "异常消息应说明不可恢复（PIPL 删除权的预期结果），实际: " + e1.getMessage());

        SubjectKeyDestroyedException e2 = assertThrows(SubjectKeyDestroyedException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.SLEEP_STAGES.fieldName(), sleep),
                "同一主体另一字段也应不可解密");

        CryptoTestHarness.record(EVIDENCE,
                "销毁主体 DEK（destroySubjectKey）",
                "密文保留 + 解密抛 SubjectKeyDestroyedException（不可恢复）",
                "心率密文=" + CipherEnvelope.parse(heartRate).describe()
                        + "; 解密抛=" + e1.getClass().getSimpleName()
                        + "; 睡眠解密抛=" + e2.getClass().getSimpleName());

        // ③ 墓碑已立，且【不得】自动重建 DEK ——
        //    允许重建等于"删一次、下次写入又活过来"。
        assertTrue(s.keyStore().isDestroyed(CUST_1), "应留下墓碑");
        assertThrows(SubjectKeyDestroyedException.class,
                () -> s.keyStore().getOrCreateDek(CUST_1),
                "已销毁的主体不得自动重建 DEK —— 否则删除权会被下一次写入撤销");

        // ④ 其他主体不受影响（per-subject 粒度的意义所在）
        assertTrue(s.keyStore().getOrCreateDek(CUST_2).rawDek().length == 32,
                "销毁 CUST_1 的密钥后 CUST_2 应不受影响 —— 这正是 per-subject 相对租户级密钥的价值");
    }

    @Test
    @DisplayName("DoD3 删除权：同一租户另一个主体的密文仍可解密（删除权不误伤他人）")
    void shredding_one_subject_does_not_break_another() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        String env1 = s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "70");
        String env2 = s.cipher().encryptText(CUST_2, SensitiveField.HEART_RATE.fieldName(), "88");

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(CUST_1, 1));

        assertThrows(SubjectKeyDestroyedException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), env1));
        assertEquals("88", s.cipher().decryptText(CUST_2, SensitiveField.HEART_RATE.fieldName(), env2),
                "销毁 CUST_1 的密钥不应影响 CUST_2 —— 若这里失败，说明粒度退化成了租户级");
    }

    // ==================================================================
    // DoD ④ 备份纪律
    // ==================================================================

    @Test
    @DisplayName("DoD4 备份门：备份未就绪时【拒绝创建 DEK】（fail-closed，门在写入侧）")
    void without_backup_readiness_dek_creation_is_refused() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.keks().initTenant(CryptoTestHarness.TENANT_A);
        // 有意【不】markBackupReady —— 默认必须是"未就绪"（fail-closed 的默认值方向）

        KeyBackupUnavailableException e = assertThrows(KeyBackupUnavailableException.class,
                () -> s.keyStore().getOrCreateDek(CUST_1),
                "备份未就绪却允许创建 DEK —— 一把没被备份的 DEK 一旦丢失，"
                        + "该主体数据永久不可读（架构规格书 §8.4 / 竞析 H.2-9）");
        assertTrue(e.getMessage().contains("永久不可读"),
                "异常应说明后果（永久不可读）而非只说『未就绪』，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("而不是放松本检查"),
                "异常应指明处置方向是修备份而不是放宽开关，实际: " + e.getMessage());

        // 反向对照：标记就绪后必须立刻可用 —— 证明这道门不是"永远拒绝"的摆设
        CryptoTestHarness.record(EVIDENCE,
                "备份未就绪（backupReady=false）时请求创建 DEK",
                "抛 KeyBackupUnavailableException，且不落下任何密钥材料",
                "抛=" + e.getClass().getSimpleName()
                        + "; 拒绝后该租户下的活跃密钥数=" + s.keyStore().liveKeyCount());

        s.keks().markBackupReady(CryptoTestHarness.TENANT_A);
        assertEquals(SubjectDek.KeyScope.PER_SUBJECT,
                s.keyStore().getOrCreateDek(CUST_1).scope(),
                "备份就绪后应能正常创建 DEK —— 否则这道门是恒拒的装饰品");

        // 拒绝时不得留下半成品密钥：另起一个全新栈单独验证（不受上面 markReady 之后的影响）。
        CryptoTestHarness.Stack fresh = CryptoTestHarness.newStack();
        fresh.keks().initTenant(CryptoTestHarness.TENANT_B);
        assertThrows(KeyBackupUnavailableException.class,
                () -> fresh.keyStore().getOrCreateDek(CUST_1));
        assertEquals(0, fresh.keyStore().liveKeyCount(),
                "被拒绝时不应留下任何密钥材料 —— 留下半成品等于绕过了这道门");
    }

    @Test
    @DisplayName("DoD4 备份清单：wrappedDek 必须可枚举（备份范围可确定，不是靠人工回忆）")
    void wrapped_deks_are_enumerable_for_backup() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "70");
        s.cipher().encryptText(CUST_2, SensitiveField.BLOOD_OXYGEN.fieldName(), "96");

        var inventory = s.keyStore().inventoryForBackupAudit();
        assertEquals(2, inventory.size(),
                "备份清单应覆盖全部活跃主体的 wrappedDek —— 漏掉的那个主体，"
                        + "其密钥丢了就永久不可读，而清单是发现漏项的唯一手段");
        for (var entry : inventory.entrySet()) {
            assertTrue(entry.getValue().kekId() != null && !entry.getValue().kekId().isBlank(),
                    entry.getKey() + " 的包裹体缺少 kekId，无法判断用哪代 KEK 还原");
        }
    }

    // ==================================================================
    // DoD ⑤ 算法可替换位（SM4 不得写死）
    // ==================================================================

    @Test
    @DisplayName("DoD5 SM4 未确认：引用它必须 fail-closed 抛异常，【绝不】静默回退到 AES")
    void sm4_fails_closed_and_never_falls_back_to_aes() {
        assertEquals(AlgorithmStatus.PENDING_CONFIRMATION, AlgorithmId.SM4_GCM.status(),
                "SM4 的状态必须是『待确认』（ADR 十五 H.4-1）—— "
                        + "把它标成 IMPLEMENTED 就等于工程侧自行认定了结论");

        AlgorithmRegistry registry = AlgorithmRegistry.standard();
        PendingAlgorithmConfirmationException e = assertThrows(
                PendingAlgorithmConfirmationException.class,
                () -> registry.resolve(AlgorithmId.SM4_GCM),
                "解析待确认算法时必须抛异常 —— 返回 AES 实现就是静默回退，"
                        + "会让对外声称的算法与实际的算法不一致");
        assertTrue(e.getMessage().contains("H.4-1"),
                "异常应指向裁定出处 H.4-1，实际: " + e.getMessage());
        assertEquals(AlgorithmId.SM4_GCM, e.algorithmId());

        // AES 仍可正常解析 —— 证明上面的失败是"因为待确认"，不是因为注册表坏了
        assertTrue(registry.resolve(AlgorithmId.AES_256_GCM) != null,
                "默认算法应可解析；若这里也失败，则上面的异常归因不成立");

        CryptoTestHarness.record(EVIDENCE,
                "AlgorithmRegistry.resolve(SM4_GCM)（状态为 ⚠️ 待确认）",
                "抛 PendingAlgorithmConfirmationException，不回退到 AES",
                "抛=" + e.getClass().getSimpleName() + "; 消息含 H.4-1="
                        + e.getMessage().contains("H.4-1") + "; AES 仍可解析=是");
    }

    @Test
    @DisplayName("DoD5 可替换位：换一个算法实现后，FieldCipher 与调用点【零改动】仍工作")
    void swapping_the_algorithm_implementation_requires_no_caller_change() {
        // 用"假算法"（ChaCha20-Poly1305）顶替注册表里的唯一实现，
        // 装配方式是正式的 AlgorithmRegistry.of(...) —— 一行代码换掉实现。
        // 若任何调用点写死了 AES，这个测试会失败：这正是可替换位的验收方式。
        CryptoTestHarness.TestOnlyAlternateAlgorithmProvider alternate =
                new CryptoTestHarness.TestOnlyAlternateAlgorithmProvider();

        CryptoTestHarness.Stack s = buildStackWith(alternate);
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        String stored = s.cipher().encryptText(CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), "95");
        assertEquals("95", s.cipher().decryptText(CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), stored),
                "替换算法实现后 FieldCipher 无法往返 —— 说明调用点写死了某个算法");
        // 断言"曾被调用"，而不是"恰好调用了 1 次"：替代实现同时承担
        // 【DEK 包裹】与【字段加密】两条路径，故一次写入会调用多次。
        // 写死次数会让这条断言随无关改动（例如加密路径多一次内部调用）变红，
        // 而它真正要证明的只是"用的确实是注入的那个实现"。
        assertTrue(alternate.encryptCalls() >= 1,
                "替代实现未被调用 —— 用的其实是标准实现，本测试什么都没证明");
        assertEquals(2, alternate.encryptCalls(),
                "一次字段写入应产生 2 次加密：① 创建 DEK 时的 KEK 包裹 ② 字段本身加密。"
                        + "实测次数偏离说明装配的算法实现没有被两条路径一致采纳");

        // 信封里写的仍是这个算法标识（适配位），证明替换不影响落库契约
        assertEquals(AlgorithmId.AES_256_GCM.id(), CipherEnvelope.parse(stored).algorithmId());

        CryptoTestHarness.record(EVIDENCE,
                "把算法实现从 AES-256-GCM 换成 ChaCha20-Poly1305（AlgorithmRegistry.of 单行装配）",
                "FieldCipher 与业务调用点零改动仍可加解密往返",
                "替代实现 encryptCalls=" + alternate.encryptCalls()
                        + "; 往返成功=是; 信封算法位=" + CipherEnvelope.parse(stored).algorithmId());
    }

    @Test
    @DisplayName("DoD5 可替换位：FieldCipher 源码不得出现算法字面量（结构性检查）")
    void field_cipher_contains_no_hardcoded_algorithm_literal() throws Exception {
        var src = CryptoTestHarness.moduleRoot()
                .resolve("src/main/java/com/diaoyuanyun/dy/crypto/field/FieldCipher.java");
        String raw = java.nio.file.Files.readString(src, StandardCharsets.UTF_8);

        // 【先剥注释再扫描 —— 这一步必须做，本测试的第一版就是在这里假红的】
        // 第一版直接扫全文的引号串，结果命中了本类 javadoc 里那句
        // "本类全文不出现 {@code "AES"}、{@code "SM4"}…" —— 那句【反例】本身
        // 被当成了违规证据。这类"检查器把说明文字当成违规"的假阳性很危险：
        // 它逼着人要么删掉说明、要么放宽检查，两条路都会让检查失去意义。
        // 正确的做法是把检查面收窄到"真正的代码"。
        String code = stripComments(raw);

        // 只检查"算法/模式名"这类会写死实现的字面量。
        List<String> offenders = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(code);
        while (m.find()) {
            String lit = m.group();
            for (String banned : List.of("AES", "SM4", "GCM", "ChaCha", "NoPadding")) {
                if (lit.contains(banned)) {
                    offenders.add(lit);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "FieldCipher 的【代码】（注释已剥离）里出现了算法名字面量 " + offenders
                        + " —— 算法必须由参数/信封决定，写死会让替换算法需要改承重代码");

        // 反向对照：确认剥离注释这一步本身没有把代码一起剥掉
        // （若 stripComments 写坏了，上面的 offenders 会恒为空，检查就变成恒真）
        assertTrue(code.contains("encryptBytes") && code.contains("decryptBytes"),
                "剥注释后连方法名都不见了 —— 说明 stripComments 剥多了，"
                        + "上面的『无算法字面量』结论是恒真的假通过");
    }

    /**
     * 剥离 Java 的行注释与块注释。<b>有意不处理字符串里的 {@code "//"} </b>：
     * 本检查的目标文件里没有这种内容，且为此写一个完整的词法分析器，
     * 其自身的 bug 风险高于它挡掉的风险。这个取舍写在注释里，
     * 是为了让下一个人知道这不是遗漏，而是有意的范围限定。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ==================================================================
    // 反向验证 ① ② ③（DoD 硬要求）
    // ==================================================================

    @Test
    @DisplayName("反向① 拿错 KEK：投递一把错误的 KEK → 解包必须失败")
    void reverse_1_wrong_kek_cannot_unwrap_dek() {
        // 用真实注册表建栈，但把 KEK provider 换成"会投递错密钥"的包装。
        AlgorithmRegistry registry = AlgorithmRegistry.standard();
        com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry realKeks =
                new com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry(
                        registry.resolve(AlgorithmId.AES_256_GCM));
        CryptoTestHarness.MisdeliveringKekProvider misdelivering =
                new CryptoTestHarness.MisdeliveringKekProvider(realKeks);
        com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore tombstones =
                new com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore();
        com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore keyStore =
                new com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore(
                        misdelivering, tombstones, registry, AlgorithmId.AES_256_GCM.id());

        realKeks.initTenant(CryptoTestHarness.TENANT_A);
        realKeks.markBackupReady(CryptoTestHarness.TENANT_A);

        // 先正常创建一把 DEK（此时用的是正确的 KEK）
        SubjectDek dek = keyStore.getOrCreateDek(CUST_1);
        WrappedDek wrapped = dek.wrapped();
        assertTrue(Arrays.equals(dek.rawDek(), keyStore.unwrap(wrapped)),
                "前置：正确 KEK 下应能正常还原");

        // === 注入：让该 kekId 返回另一把密钥 ===
        // 只换密钥、不改 kekId/AAD，因此失败只能来自密钥本身（归因精确）。
        byte[] wrongKek = new byte[32];
        new java.security.SecureRandom().nextBytes(wrongKek);
        assertFalse(Arrays.equals(wrongKek, realKeks.kekVersion(
                        CryptoTestHarness.TENANT_A, wrapped.kekId())),
                "注入未生效：错误密钥与真实 KEK 恰好相同（概率极低，重新生成）");
        misdelivering.misdeliver(wrapped.kekId(), wrongKek);

        CipherAuthenticationException e = assertThrows(CipherAuthenticationException.class,
                () -> keyStore.unwrap(wrapped),
                "拿错 KEK 却成功还原了 DEK —— 包裹没有实际绑定 KEK，wrappedDek 形同明文");
        assertTrue(e.getMessage().contains("AEAD 认证失败"),
                "失败原因应为 AEAD 认证失败，实际: " + e.getMessage());

        CryptoTestHarness.record(EVIDENCE,
                "反向① 投递错误的租户 KEK（只换密钥、不动 kekId/AAD）",
                "解包抛 CipherAuthenticationException（AEAD 认证失败）",
                "抛=" + e.getClass().getSimpleName()
                        + "; 相同错误时的正确路径仍可用=已在前置断言");
    }

    @Test
    @DisplayName("反向② 墓碑是承重的：撤掉墓碑后同一密文又能解密（证明不是装饰）")
    void reverse_2_tombstone_is_load_bearing() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        SubjectDek dek = s.keyStore().getOrCreateDek(CUST_1);
        String envelope = s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "66");

        // 保留 wrappedDek 的副本 —— 模拟"在线存储已删，但备份里的包裹材料还在"
        // （这正是 DoD ④ 要求认真备份所带来的副作用）。
        WrappedDek backupCopy = dek.wrapped();

        s.keyStore().destroySubjectKey(SubjectKeyRef.of(CUST_1, dek.version()));
        assertThrows(SubjectKeyDestroyedException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), envelope),
                "前置：销毁后应不可解密");

        // === 注入：撤掉墓碑（模拟"墓碑不在抗回滚介质里，被一次恢复冲掉"）===
        s.tombstones().clearForTest();

        // 同时把"备份里的 wrappedDek"放回密钥库（模拟备份恢复）
        byte[] kekOfV1 = s.keks().kekVersion(CryptoTestHarness.TENANT_A, backupCopy.kekId());
        assertTrue(kekOfV1.length == 32, "注入前置：KEK 仍在（备份恢复场景）");

        // 现在墓碑没了 → 密钥库认为该主体"从未销毁"→ 可以还原 DEK → 又能解密。
        // 【这一步是坏消息，故意断言它】：它证明墓碑是承重的。
        byte[] restored = s.keyStore().unwrap(backupCopy);
        assertTrue(Arrays.equals(dek.rawDek(), restored),
                "撤掉墓碑后无法还原 DEK —— 那么墓碑就不是承重件，"
                        + "本测试没有证明它保护了什么（需重写注入方式）");

        CryptoTestHarness.record(EVIDENCE,
                "反向② 撤掉墓碑（InMemoryShredTombstoneStore.clearForTest）+ 用备份中的 wrappedDek 还原",
                "期望[能重新还原出 DEK —— 即『墓碑一丢，删除权就被备份恢复撤销』这一事实]",
                "restored 与原文 DEK 相同=" + Arrays.equals(dek.rawDek(), restored)
                        + " —— 与期望一致；故生产必须把墓碑放在不受备份回滚影响的介质里"
                        + "（append-only 审计日志 / WORM），默认进程内实现【不具备】抗回滚能力");

        // 对照：重新立墓碑后立刻又不可还原 —— 证明能力差异确实来自墓碑
        s.tombstones().record(SubjectKeyRef.of(CUST_1, dek.version()),
                java.time.Instant.now(), "测试：重新立墓碑");
        assertThrows(SubjectKeyDestroyedException.class, () -> s.keyStore().unwrap(backupCopy),
                "重新立墓碑后应又不可还原 —— 否则上面的归因（墓碑承重）不成立");
    }

    @Test
    @DisplayName("反向③ 篡改密文：翻转载荷任一字节 → 必须被检出，而不是解出错值")
    void reverse_3_tampered_ciphertext_is_detected() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        String envelope = s.cipher().encryptText(CUST_1, SensitiveField.SLEEP_STAGES.fieldName(),
                "{\"deep\":180,\"light\":220}");

        CipherEnvelope parsed = CipherEnvelope.parse(envelope);
        byte[] ciphertext = parsed.ciphertext();

        // 逐字节翻转（GCM 的 tag 覆盖全部密文，因此任意一位翻转都必须被检出）。
        // 抽样检查若干位置：首字节、中间、末字节（末字节通常落在 tag 内）。
        int[] probes = {0, ciphertext.length / 2, ciphertext.length - 1};
        for (int idx : probes) {
            byte[] tampered = ciphertext.clone();
            tampered[idx] ^= 0x01;
            String tamperedText = new CipherEnvelope(parsed.algorithmId(), parsed.dekVersion(),
                    parsed.nonce(), tampered).serialize();

            // 注入必须真的改变了信封文本，否则"报错"无从归因
            assertNotEquals(envelope, tamperedText, "注入未生效：信封文本未变");

            CipherAuthenticationException e = assertThrows(CipherAuthenticationException.class,
                    () -> s.cipher().decryptText(CUST_1, SensitiveField.SLEEP_STAGES.fieldName(),
                            tamperedText),
                    "翻转密文第 " + idx + " 字节后未被检出 —— 篡改检测失效");
            CryptoTestHarness.record(EVIDENCE,
                    "反向③ 翻转密文第 " + idx + " 字节（共 " + ciphertext.length + " 字节）",
                    "抛 CipherAuthenticationException（AEAD 认证失败），不得解出错值",
                    "抛=" + e.getClass().getSimpleName());
        }

        // 反向对照：把最后一次翻转再翻回去，必须又能解密 —— 证明失败来自那一位翻转
        byte[] restored = ciphertext.clone();
        restored[ciphertext.length - 1] ^= 0x01;
        restored[ciphertext.length - 1] ^= 0x01;
        String restoredText = new CipherEnvelope(parsed.algorithmId(), parsed.dekVersion(),
                parsed.nonce(), restored).serialize();
        assertEquals("{\"deep\":180,\"light\":220}",
                s.cipher().decryptText(CUST_1, SensitiveField.SLEEP_STAGES.fieldName(), restoredText),
                "还原后再解密仍失败 —— 则上面各次失败的归因（某一位翻转）不成立");
    }

    @Test
    @DisplayName("反向④ AAD 绑定：把密文挪到另一主体名下 → 认证必须失败（归属不可替换）")
    void reverse_4_moving_ciphertext_to_another_subject_is_detected() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        // CUST_1 与 CUST_2 各有独立 DEK，这里模拟"有库写权限的人把 CUST_1 的密文
        // 行复制到 CUST_2 名下"（客户 id 改掉、密文原样搬）。
        // 用 CUST_2 的身份去解 CUST_1 的密文 —— 由于 AAD 含主体标识，
        // 即便两把 DEK 相同也应失败；此处更严格：DEK 本就不同。
        String envCust1 = s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "77");

        assertThrows(RuntimeException.class,
                () -> s.cipher().decryptText(CUST_2, SensitiveField.HEART_RATE.fieldName(), envCust1),
                "把 CUST_1 的密文当作 CUST_2 的数据解密竟然成功 —— 归属绑定失效");

        // 更强的注入：同一主体、把字段名改掉（AAD 里含 fieldName）
        assertThrows(CipherAuthenticationException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), envCust1),
                "把『心率』字段的密文当作『血氧』解 —— AAD 的 fieldName 绑定未生效");

        // AAD 分隔符纪律：含 '|' 的 id 必须在入口被拒（否则可构造 AAD 碰撞）
        assertThrows(IllegalArgumentException.class,
                () -> AadBinding.of(SubjectRef.customer(CryptoTestHarness.TENANT_A, "a|b"),
                        "hr", 1),
                "含分隔符 '|' 的主体 id 未被拒绝 —— 可构造 AAD 碰撞（两个主体共用同一 AAD）");
    }

    @Test
    @DisplayName("反向⑤ 信封损坏：段数不足/前缀不符/base64 非法 → 必须报格式错误，不得猜测修复")
    void reverse_5_corrupt_envelope_is_reported_not_guessed() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        String good = s.cipher().encryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), "70");
        assertEquals("70", s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(), good),
                "前置：正常信封应可解密");

        for (String bad : List.of(
                "dy1:AES-256-GCM:1:onlyfour",
                "dy9:AES-256-GCM:1:AAAA:BBBB",
                "dy1:AES-256-GCM:notanumber:AAAA:BBBB",
                "dy1:AES-256-GCM:1:!!!:BBBB",
                "")) {
            assertThrows(EnvelopeFormatException.class,
                    () -> CipherEnvelope.parse(bad),
                    "损坏信封 '" + bad + "' 未被报为格式错误 —— 猜测性修复会把『信封坏了』"
                            + "表现为『解出来的内容不对』，两种故障的处置完全不同");
        }

        // 未知算法位（可能来自更高版本的写入方）必须明确拒绝，不得回退到默认算法。
        // 分两步断言，因为"解析"与"解密"各有一道拒绝：
        //   ① 解析本身不应崩（信封结构是合法的），
        //   ② 解密时必须因算法位未知而拒绝。
        CipherEnvelope unknownAlgo = CipherEnvelope.parse("dy1:AES-9999-XYZ:1:AAAA:BBBB");
        assertEquals("AES-9999-XYZ", unknownAlgo.algorithmId(),
                "信封的算法位应被原样读出（而不是被悄悄替换成默认算法）");
        assertThrows(IllegalArgumentException.class,
                () -> s.cipher().decryptText(CUST_1, SensitiveField.HEART_RATE.fieldName(),
                        "dy1:AES-9999-XYZ:1:AAAA:BBBB"),
                "未知算法位未被拒绝 —— 静默回退到默认算法会让『实际用的算法』不可判定");
    }

    @Test
    @DisplayName("反向⑥ 换成一个『假装加密』的实现（密文=明文）→ 本门禁必须报警，且旧的字面判据会漏掉")
    void reverse_6_literal_scan_would_catch_a_plaintext_passthrough() {
        // 注入：一个 encrypt() 把明文原样当密文返回的实现。
        CryptoTestHarness.Stack s = buildStackWith(new PlaintextPassthroughProvider());
        s.provisionTenant(CryptoTestHarness.TENANT_A);

        for (String plaintext : List.of("72", "98", "{\"deep\":210,\"light\":180}")) {
            String envelope = s.cipher().encryptText(
                    CUST_1, SensitiveField.BLOOD_OXYGEN.fieldName(), plaintext);

            // ① 前置：确认注入确实生效 —— 解码后的密文段就是明文本身。
            CipherEnvelope env = CipherEnvelope.parse(envelope);
            assertTrue(Arrays.equals(plaintext.getBytes(StandardCharsets.UTF_8), env.ciphertext()),
                    "注入未生效：密文段不等于明文，本反向验证什么都没证明");

            // ② 本门禁的判据必须报警 —— 这正是"改判据没有削弱判别力"的证据。
            assertThrows(AssertionError.class,
                    () -> assertEnvelopeHides("注入样本", plaintext, envelope),
                    "注入『密文=明文』的实现后判据竟然通过了 —— 本门禁对最直白的"
                            + "'没加密'都无感，则它在真实缺陷面前同样无效");

            // ③ 对照：证明"旧的字面判据"的检查面是错的（而不是我在修一个不存在的 bug）。
            //    这里【有意】只对密文段的 base64 文本做检查，而【不】对整串信封做 ——
            //    整串信封里还有一段随机 base64 的 nonce，"随机串恰含 '98'"是概率事件
            //    （实测 ≈0.37%/字段），拿它做断言会把本反向验证本身变成假红。
            //    密文段的 base64 是确定的：base64("98") == "OTg="，明文 '98' 在其中
            //    根本不会以原样出现 —— 于是"旧判据对最该抓到的地方恰好无感"这一点
            //    可以被确定性地证明，而不是靠运气。
            String ctSegment = envelope.substring(envelope.lastIndexOf(':') + 1);
            assertFalse(ctSegment.contains(plaintext),
                    "密文段的 base64 里竟然出现了明文 —— base64 不该原样保留明文，"
                            + "若出现说明信封构造方式与预期不符");
            assertEquals(java.util.Base64.getEncoder().encodeToString(env.ciphertext()), ctSegment,
                    "信封末段应是密文的 base64 —— 前置不成立则上面的结论无效");
        }

        CryptoTestHarness.record(EVIDENCE,
                "反向⑥ 把算法实现换成『密文=明文』的假实现（PlaintextPassthroughProvider）",
                "本门禁的 assertEnvelopeHides 必须抛 AssertionError；"
                        + "同时确认旧字面判据的检查面（base64 密文段）对短明文无感",
                "三样本（2 短 + 1 长）均被新判据拒绝；"
                        + "密文段 base64 不含明文=确定性成立（非概率）；"
                        + "旧判据对短明文无感=是");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * <b>反向验证 ⑥ 的注入器：一个"假装加密"的算法实现。</b>
     *
     * <p>它的 {@code encrypt} 把明文<b>原样</b>当作密文返回（nonce 仍是随机的），
     * 于是可以精确检验"密文里没有明文"这条判据到底有没有牙齿。
     *
     * <p>为什么需要它：本任务的断言曾从 {@code envelope.contains(plaintext)}
     * 改成现在这版。任何"把断言改了个样子"的动作都必须能自证没有削弱判别力 ——
     * 否则它与人把测试改成总是通过无法从外部区分。这个注入器就是那份自证：
     * 在"原样回填"面前，新判据必须报警。
     */
    private static final class PlaintextPassthroughProvider
            implements com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider {

        private final java.security.SecureRandom random = new java.security.SecureRandom();

        @Override
        public AlgorithmId id() {
            return AlgorithmId.AES_256_GCM;
        }

        @Override
        public EncryptResult encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
            // ⚠️ 故意的缺陷实现：密文 = 明文（一点都没加密）。
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            return new EncryptResult(plaintext.clone(), nonce);
        }

        @Override
        public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) {
            return ciphertext.clone();
        }

        @Override
        public byte[] newKey() {
            byte[] k = new byte[32];
            random.nextBytes(k);
            return k;
        }
    }

    /** 用一个替代算法实现装配栈（见 DoD5 可替换位测试，以及反向⑥ 的"假装加密"注入器）。 */
    private static CryptoTestHarness.Stack buildStackWith(
            com.diaoyuanyun.dy.crypto.algorithm.AlgorithmProvider provider) {
        AlgorithmRegistry registry = AlgorithmRegistry.of(provider);
        com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry keks =
                new com.diaoyuanyun.dy.crypto.key.InMemoryTenantKekRegistry(provider);
        com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore tombstones =
                new com.diaoyuanyun.dy.crypto.key.InMemoryShredTombstoneStore();
        com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore keyStore =
                new com.diaoyuanyun.dy.crypto.key.InMemorySubjectKeyStore(
                        keks, tombstones, registry, provider.id().id());
        FieldCipher cipher = new FieldCipher(keyStore, registry, provider.id().id());
        return new CryptoTestHarness.Stack(registry, keks, tombstones, keyStore, cipher,
                new com.diaoyuanyun.dy.crypto.shred.DeletionDag(keyStore));
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
}