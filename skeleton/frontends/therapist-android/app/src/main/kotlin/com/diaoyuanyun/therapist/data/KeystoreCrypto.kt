package com.diaoyuanyun.therapist.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 端 B · 本地敏感值加密（AndroidKeyStore + AES-256-GCM）
 * ============================================================================
 *
 * 🛑 为什么**不**引 `androidx.security:security-crypto`
 * ---------------------------------------------------------------------------
 * 那是"给 EncryptedSharedPreferences 用的库"，但它**已被 Google 整体废弃**：
 * `EncryptedSharedPreferences` / `MasterKey` / `EncryptedFile` 在 **1.1.0** 全部
 * 标记 `@Deprecated`，官方 API 文档给的方向是「Use android.content.SharedPreferences
 * instead」/「Use javax.crypto.KeyGenerator with AndroidKeyStore instance instead」，
 * 即**直接用平台能力**，并声明 *no further releases planned*。
 *
 * 除了"引一个不会再更新的库"本身，它还有两个与本项目场景**直接冲突**的已知问题：
 *   · 在**特定 OEM 设备**上出现 keyset 损坏 —— 表现为解密抛异常、而数据还在；
 *   · 主线程 StrictMode 违规 —— 它在调用线程上直接做重加密运算。
 * 本端的现实场景是"门店一线设备型号杂"，恰好是这两条最痛的地方。
 *
 * ⇒ 本类自己实现，只用两个**平台内置**能力（零新增依赖、完全离线可用）：
 *   · `AndroidKeyStore` —— 密钥材料的持有者，密钥**不出安全硬件**；
 *   · `javax.crypto` —— JDK 内置的 AES/GCM。
 *
 * 🛑 一条必须写明的"不对称"设计：写失败就抛，读失败就回 null
 * ---------------------------------------------------------------------------
 *   · [encrypt] 失败 ⇒ **抛异常**，绝不回落明文。
 *     写入是我们自己发起的；失败意味着设备密钥库真的不可用。此时把令牌明文
 *     写下去会造出**最坏形态**："看起来加密了、实际没有" —— 而它不会再被任何
 *     后续检查发现（值本身就是个合法字符串）。宁可让写入失败暴露出来。
 *   · [decrypt] 失败 ⇒ **返回 null**，由调用方当作"没有会话"。
 *     读取面对的是"可能损坏 / 被篡改 / 来自旧版本"的数据，必须容错；
 *     这与 `SessionStore.me()` 既有的「缓存损坏当没有」口径一致。
 * ⇒ 方向相反是因为：**写入的失败值得暴露，读取的失败必须容错**。
 *
 * 🛑 密文格式（自描述，便于排查与将来换代）
 * ---------------------------------------------------------------------------
 *     base64(iv) + ":" + base64(ciphertext ‖ tag)
 *   · IV **12 字节**（GCM 标准长度），由系统在**每次**加密时随机生成
 *     （`setRandomizedEncryptionRequired` 默认 true ⇒ 同一明文两次加密得到
 *      不同密文 —— 这正是我们要的：密文可比较就等于泄露了"两人令牌相同"）；
 *   · tag **128 bit**，由 GCM 附加在密文尾部，**不单独存储**；
 *   · 用 `android.util.Base64` 而非 `java.util.Base64` —— 后者需要 API 26，
 *     而本端 `minSdk = 24`。
 *
 * 🛑 与 `AndroidManifest` 的一条联动（不是巧合）
 * ---------------------------------------------------------------------------
 * 本端 `android:allowBackup="false"`，这与本类**必须**联动：
 * 密钥在安全硬件里、**不随备份迁移**，若允许 Auto Backup 把密文恢复到另一台
 * 设备，那台设备解不开它 —— 表现为"恢复备份后一直要求重新登录"。
 * 官方文档对 EncryptedSharedPreferences 有同样的警告，我们是主动堵在前面。
 *
 * 🛑 两处如实登记的取舍（不得当成已完成）
 * ---------------------------------------------------------------------------
 * ① 同步 API：`KeyStore.load` / `getEntry` 有 I/O 开销，本类在**首次**访问某 alias
 * 时走一次（之后经 [keyCache] 复用）。会话读写只发生在"启动恢复 / 登录 / 退出"三个时机，
 * 频率极低，故当前保持**同步 API**（与 `SharedPreferences` 本身的同步 I/O 同级）。
 * ⚠️ 若将来出现"每帧读会话"这类高频路径，必须改为后台线程 + 内存快照，
 *    不得沿用本类的同步形态。
 *
 * ② 不可纯 JVM 单测：本类用到 `android.util.Base64`（平台类），在没有 Robolectric
 * 的纯 JVM 测试里会抛 `Stub!`。当前本端没有 JVM 单测，故不构成问题；
 * **若将来要为加解密写单测**，需 Robolectric，或先把 [encode] / [decode] 抽成
 * 可替换的接口再注入。现在不抽 —— 为了一个还不存在的测试加一层间接，
 * 与 `di/Graph.kt` 拒绝 DI 框架是同一条理由。
 */
internal object KeystoreCrypto {

    /** 平台内置 provider；密钥由系统管理，应用只持有引用。 */
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * `AES/GCM/NoPadding` —— 为什么是 GCM 而不是 CBC
     * ---------------------------------------------------------------------------
     * GCM 是**带认证**的加密（AEAD）：篡改密文会让解密**失败**，而不是解出
     * 一段垃圾明文。CBC 不做认证，攻击者翻转密文比特就能定向篡改明文，
     * 而应用侧完全看不出来。本端存的是会话与档位，"被篡改的档位"意味着
     * 越权面 —— 必须能检测到。
     */
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val KEY_ALGORITHM = "AES"
    private const val KEY_SIZE_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val SEPARATOR = ":"

    /** alias → 密钥引用（不是密钥材料本身；材料始终在安全硬件里）。 */
    private val keyCache = HashMap<String, SecretKey>()

    /**
     * 加密为可安全写入 `SharedPreferences` 的**字符串**。
     *
     * 🛑 失败即抛（见类注释的"不对称设计"）—— 调用方**不得** catch 后回落明文。
     */
    fun encrypt(alias: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // cipher.iv 是本次**随机生成**的 IV（因 setRandomizedEncryptionRequired 默认 true）
        return encode(cipher.iv) + SEPARATOR + encode(cipherText)
    }

    /**
     * 解密；**任何**异常（格式不符 / tag 校验失败 / 密钥已失效）一律返回 `null`。
     *
     * 🛑 返回 `null` 而不是抛：调用方会把它当作"没有这条数据"。
     *    这正是我们想要的 —— GCM 校验失败说明数据被篡改或来自别的设备，
     *    此时**唯一安全**的处理就是丢弃并要求重新登录。
     */
    fun decrypt(alias: String, stored: String): String? {
        val sep = stored.indexOf(SEPARATOR)
        if (sep <= 0 || sep >= stored.length - 1) return null
        val iv = decode(stored.substring(0, sep)) ?: return null
        val cipherText = decode(stored.substring(sep + 1)) ?: return null
        if (iv.size != IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // 密钥
    // -------------------------------------------------------------------------

    private fun key(alias: String): SecretKey = synchronized(keyCache) {
        keyCache.getOrPut(alias) { loadOrCreate(alias) }
    }

    private fun loadOrCreate(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // 🛑 不要求用户认证（指纹 / 锁屏密码）
            // -----------------------------------------------------------------
            // 妥协会降低"设备被物理接触"时的门槛，换取的是**可用性** ——
            // 一线打开 APP 不该先被指纹拦一次（他们会因此把 APP 当成"老是要
            // 认证的破东西"，然后开始共享账号，那才是真正的安全损失）。
            // 本端存的是**有时效、可被服务端吊销的会话令牌**，不是长期凭据，
            // 这个取舍与契约 A1 的设计（登录换令牌、令牌可吊销）一致。
            // ⇒ 若将来本端开始存长期凭据，此处必须改为 true 并重评可用性。
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    // -------------------------------------------------------------------------
    // 编解码
    // -------------------------------------------------------------------------

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()
}
