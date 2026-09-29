#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把对抗性验证探针从 dy-crypto 的模块测试树迁出到 verification/crypto/。

动机（team-lead 裁定第 1 步）：
  探针原先位于 dy-crypto/src/test/... 下，会被 `mvn -pl dy-crypto test` 编译进
  dy-crypto 自己的 test-classes，使 surefire 的 `Tests run` 由 23 抬到 24 ——
  实现者与验证者的证据混进同一个计数，等于自证。本脚本把它移出模块测试树。

纪律：本脚本【不改探针逻辑】。只做四类机械手术，以保证迁移前后语义等价：
  1) 去掉 JUnit 四件套（import / @Test / @AfterAll / @DisplayName）
  2) 把 @Test 入口 `adversarialProbeMatrix()` 去注解，新增 `public static void main`
     （门禁形状需要：全 PASS → exit 0，任一 FAIL → exit 1）
  3) 内联原先借用的 CryptoTestHarness 成员（TENANT_*/CUSTOMER_*/Stack/newStack/
     TestOnlyAlternateAlgorithmProvider/repoRoot）—— 使验证者代码与实现者的测试
     辅助类零耦合，而不是"少耦合"
  4) 报告落盘位置改为 verification/crypto/ 自己的目录

用法：
  python migrate_probe.py            # 生成 verification/crypto/AdversarialVerificationProbe.java

⚠️ 【已退役，不可再运行】本脚本是一次性迁移工具。迁移第 6 步已删除源文件
  （dy-crypto/src/test/.../adversarial/AdversarialVerificationProbe.java），
  故 SRC 不再存在，直接运行会读文件失败并非零退出。保留本文件仅为留存
  "迁移做了哪四类机械手术" 的证据链；生成物
  verification/crypto/AdversarialVerificationProbe.java 现已是唯一权威版本，
  不要再由本脚本覆盖（覆盖只会回退到含前向引用编译错误的旧形态）。
"""

from __future__ import annotations

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(REPO, "dy-crypto", "src", "test", "java",
                   "com", "diaoyuanyun", "dy", "crypto", "adversarial",
                   "AdversarialVerificationProbe.java")
DST = os.path.join(HERE, "AdversarialVerificationProbe.java")

# 内联的 harness —— 语义与 dy-crypto/src/test/.../gate/CryptoTestHarness 的
# 对应部分等价，但由验证者自己持有，不 import 被测方的测试类。
INLINED_HARNESS_ANCHOR = "    // ==================================================================\n" \
                         "    // 工具\n" \
                         "    // ==================================================================\n"

INLINED_CONSTANTS = '''    // ==================================================================
    // 内联 harness 的常量部分
    // ------------------------------------------------------------------
    // 必须声明在类体最前：Java 不允许静态字段前向引用，而本类顶部就用到了
    // TA/TB/C1/C2 去构造 SubjectRef —— 常量若放到文件后部会编译失败
    // （这是本脚本第一版迁移的实测报错：非法前向引用）。
    // ==================================================================

    /** 测试用租户（可丢弃）。 */
    static final String TENANT_A = "tnt-aaaa-0001";
    static final String TENANT_B = "tnt-bbbb-0002";

    /** 测试用主体 id。 */
    static final String CUSTOMER_1 = "cust-0001";
    static final String CUSTOMER_2 = "cust-0002";

'''

INLINED_HARNESS = '''    // ==================================================================
    // 内联 harness（原借用自 dy-crypto 的测试辅助类）
    // ------------------------------------------------------------------
    // 为什么要内联而不是 import：若仍 import 被测方的测试辅助类，
    // 验证者与被测方的测试代码就仍有共享面 —— 而本次迁移的目的正是把两者彻底分开。
    // 以下定义在语义上与那些辅助类的对应成员等价。
    // ==================================================================

    /**
     * 被测栈的把手集合（语义等同实现方测试辅助类里的 Stack 记录）。
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
     * 测试专用替代算法（ChaCha20-Poly1305）—— 语义等同 CryptoTestHarness 的同名内部类。
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
     * 定位仓库根 —— 语义等同实现方测试辅助类里的 moduleRoot()，返回的仍是 dy-crypto
     * 模块目录（供 D1 扫源码、I3 遍历 test 树与读 pom.xml 使用）。
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

'''


def migrate(text: str) -> str:
    orig = text

    # ---- 1) 去掉 JUnit 四件套 ----
    for imp in (
        "import org.junit.jupiter.api.AfterAll;\n",
        "import org.junit.jupiter.api.DisplayName;\n",
        "import org.junit.jupiter.api.Test;\n",
    ):
        assert imp in text, f"缺少 import 行: {imp!r}"
        text = text.replace(imp, "")
    assert "import com.diaoyuanyun.dy.crypto.gate.CryptoTestHarness;\n" in text
    text = text.replace("import com.diaoyuanyun.dy.crypto.gate.CryptoTestHarness;\n", "")
    # 迁移后需要 Paths
    text = text.replace(
        "import java.nio.file.Path;\n",
        "import java.nio.file.Path;\nimport java.nio.file.Paths;\n", 1)

    # @AfterAll 静态退出钩子：main 化后不需要
    text = re.sub(
        r"\n    @AfterAll\n    static void dumpOnExit\(\) \{\n        flush\(\);\n    \}\n",
        "\n", text)

    # ---- 2) 入口去注解 + main 化 ----
    x = "    @Test\n" \
        '    @DisplayName("对抗性验证探针矩阵（A~G）—— 独立编写，不复用被测方的断言")\n' \
        "    void adversarialProbeMatrix() {\n"
    assert x in text, "未找到 @Test 入口方法头"
    text = text.replace(
        x,
        "    /**\n"
        "     * 探针矩阵入口。\n"
        "     *\n"
        "     * <p>门禁形状：全部 PASS → exit 0；任一 FAIL → exit 1。\n"
        "     * 不再依赖 JUnit —— 本探针是独立可执行的验证程序，不参与 dy-crypto 的\n"
        "     * {@code Tests run} 计数（避免实现者与验证者的证据混进同一个计数）。\n"
        "     */\n"
        "    void matrix() {\n")

    # 类声明：加 public final，使 runner 可直接由类名启动
    assert "class AdversarialVerificationProbe {" in text
    text = text.replace(
        "class AdversarialVerificationProbe {",
        "public final class AdversarialVerificationProbe {", 1)

    # ---- 3) 内联 harness：替换全部 CryptoTestHarness.X 引用 ----
    text = text.replace("CryptoTestHarness.TestOnlyAlternateAlgorithmProvider",
                        "TestOnlyAlternateAlgorithmProvider")
    text = text.replace("CryptoTestHarness.Stack", "Stack")
    text = text.replace("CryptoTestHarness.newStack", "newStack")
    text = text.replace("CryptoTestHarness.moduleRoot()", "repoRoot()")
    text = text.replace("CryptoTestHarness.TENANT_A", "TENANT_A")
    text = text.replace("CryptoTestHarness.TENANT_B", "TENANT_B")
    text = text.replace("CryptoTestHarness.CUSTOMER_1", "CUSTOMER_1")
    text = text.replace("CryptoTestHarness.CUSTOMER_2", "CUSTOMER_2")

    # ---- 3b) 改写类级 javadoc：迁移后不再借用实测方的测试辅助类 ----
    OLD_DOC = (
        " * 仅复用 {@link CryptoTestHarness} 的<b>装配</b>（把栈搭起来），不依赖它的断言。\n"
        " *\n"
        " * <h2>输出约定</h2>\n"
        " * 每条探针输出 {@code [PASS]}（攻击失败＝实现正确）/ {@code [FAIL]}（攻击成功＝发现缺陷）\n"
        " * / {@code [N/A]}（未能验证）。结果同时落盘到 {@code target/adversarial-verification/}，\n"
        " * 该目录在合规扫描的 EXCLUDED_DIRS 内，不构成扫描面污染。\n"
    )
    assert OLD_DOC in text, "未找到待改写的类级 javadoc 段"
    NEW_DOC = (
        " * <b>与实现方零共享</b>：本类不 import 也不引用 dy-crypto 的任何测试类。\n"
        " * 装配栈所需的 harness（租户/主体常量、{@code Stack}、{@code newStack}、\n"
        " * 替代算法实现）在本类内<b>内联</b>自持 —— 因为共享测试辅助类会让\n"
        " * \"验证者的结论\" 与 \"被测方测试代码的措辞\" 之间重新出现耦合面。\n"
        " *\n"
        " * <h2>为什么不在 dy-crypto 的 src/test 下</h2>\n"
        " * 放在那里会被 {@code mvn -pl dy-crypto test} 编译进该模块自己的 test-classes，\n"
        " * 使 surefire 的 {@code Tests run} 由 23 抬到 24 —— 实现者的用例与验证者的探针\n"
        " * 混进<b>同一个计数</b>，等于自证。本类因此迁到 {@code verification/crypto/}，\n"
        " * 不参与任何模块的 test 编译，只由本目录的 runner 独立编译并执行。\n"
        " *\n"
        " * <h2>输出约定</h2>\n"
        " * 每条探针输出 {@code [PASS]}（攻击失败＝实现正确）/ {@code [FAIL]}（攻击成功＝发现缺陷）\n"
        " * / {@code [N/A]}（未能验证）。报告落盘目录由 {@code -Dcrypto.evidence.dir} 指定，\n"
        " * 缺省为 {@code verification/crypto/evidence/}；可用 {@code -Dcrypto.repo.root=<path>}\n"
        " * 指定仓库根（供源码扫描与 pom 核对使用，不依赖探针自身所在目录）。\n"
    )
    text = text.replace(OLD_DOC, NEW_DOC, 1)

    # 注意：本断言必须在【插入内联 harness 之前】执行 —— 内联文本自身的说明里
    # 会出现 "CryptoTestHarness" 这个词（说明它内联自哪里），否则会自命中。
    assert "CryptoTestHarness" not in text, "仍有未替换的 CryptoTestHarness 引用"

    # 插入内联 harness（放在"工具"分节之前，使引用靠后定义不影响 Java 语义）
    assert INLINED_HARNESS_ANCHOR in text, "未找到插入锚点"
    text = text.replace(INLINED_HARNESS_ANCHOR, INLINED_HARNESS + INLINED_HARNESS_ANCHOR, 1)

    # 常量块插到类体最前（静态字段不得前向引用）
    CLASS_DECL = "public final class AdversarialVerificationProbe {\n"
    assert CLASS_DECL in text
    text = text.replace(CLASS_DECL, CLASS_DECL + "\n" + INLINED_CONSTANTS, 1)

    # ---- 4) 报告落盘位置：改为 verification/crypto/evidence/ ----
    old_dump = ('            Path dir = repoRoot().resolve("target/adversarial-verification");')
    assert old_dump in text, "未找到报告落盘语句"
    text = text.replace(
        old_dump,
        '            Path dir = Paths.get(System.getProperty("crypto.evidence.dir",\n'
        '                    repoRoot().getParent().resolve("verification/crypto/evidence").toString()))\n'
        '                    .toAbsolutePath().normalize();')

    # ---- 5) main 入口：由类名启动，按 nFail 决定退出码 ----
    # 先把原文件末尾的"类闭合花括号"摘掉，再把 main 与本类闭合花括号补回去。
    # （直接字符串拼接会让花括号数量错位 —— 这是本脚本第一版的 bug。）
    stripped = text.rstrip()
    assert stripped.endswith("}"), "文件末尾不是类闭合花括号"
    stripped = stripped[:-1].rstrip("\n")  # 摘掉外层类的闭合 '}'
    text = stripped + "\n\n" + MAIN_ENTRY

    assert text != orig
    return text


MAIN_ENTRY = '''    /**
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
'''


def main() -> int:
    if not os.path.isfile(SRC):
        sys.stderr.write(
            "[migrate] 已退役：源文件不存在，本脚本无法再生成迁移产物。\n"
            f"[migrate] 缺失的源: {SRC}\n"
            "[migrate] 权威版本已是: " + DST + "\n")
        return 2
    with io.open(SRC, "r", encoding="utf-8") as f:
        src_text = f.read()
    out = migrate(src_text)
    sys.stderr.write(
        "[migrate] 警告：本脚本为一次性工具，重新生成会覆盖已人工修正的权威版本。\n"
        f"[migrate] 目标: {DST}\n")
    with io.open(DST, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"[migrate] 源  : {SRC}")
    print(f"[migrate] 目标: {DST}")
    print(f"[migrate] 字节: {len(src_text.encode('utf-8'))} -> {len(out.encode('utf-8'))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())