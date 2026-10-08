package com.diaoyuanyun.therapist.data

import android.content.Context
import com.diaoyuanyun.therapist.domain.AuthMeData
import com.google.gson.Gson

/**
 * 端 B · 会话存储（**唯一**读写令牌与档位的地方）
 * ============================================================================
 *
 * 🛑 缓存的是**契约模型本人**（`AuthMeData`），不是一个"本端自造的 Profile"
 * ---------------------------------------------------------------------------
 * 本层初版曾自造一个带 `display` / `tier` / `staffId` / `storeId` 的 `Profile`
 * 类 —— 那四个字段契约 `AuthMeData` 里**一个都没有**。那正是本仓第 50 条
 * （"写下的字段名" ≠ "服务端真发的字段名"）：Gson 反序列化缺失字段只落 `null`，
 * 界面把它渲染成 `—`，而排查者永远查不出"这个字段名根本不存在"。
 * ⇒ 缓存契约模型本人，字段名不可能与契约漂移（它**就是**由契约转录的）。
 *
 * 🛑 为什么键名必须集中在一处（本仓实测过的一类缺陷）
 * ---------------------------------------------------------------------------
 * Web 端曾出现"页面 A 写 `dy.token`、页面 B 读 `dy_token`"的键名分裂 ——
 * 表现是"登录成功但一直跳回登录页"，而构建 / 类型检查全绿。
 * 故本端把键名收在 [Keys] 里，任何其它文件**不得**出现键名字面量
 * （android-check 的 `session-key-single-source` 判据会扫）。
 *
 * 🛑 落盘的值一律**加密**（这条从"待补项"变成了"已收口"，留痕在此）
 * ---------------------------------------------------------------------------
 * 本层初版用 `SharedPreferences` **明文**存令牌，并在注释里如实登记为待补项 ——
 * 明文 prefs 在 root 设备上可被直接读取。
 *
 * 现已收口：所有写入经 [KeystoreCrypto]（`AndroidKeyStore` + AES-256-GCM）。
 * 实现路径的选择理由（**不是**随手挑的）见该类注释：`androidx.security:security-crypto`
 * 已被 Google 整体废弃（1.1.0 起全库 `@Deprecated`），且它在特定 OEM 设备上有
 * keyset 损坏、主线程 StrictMode 违规两个已知问题 —— 而本端的使用场景
 * （门店一线、设备型号杂）恰好是这两条最痛的地方。
 *
 * 🛑 由此产生的**硬约束**（改本文件前必读）
 * ---------------------------------------------------------------------------
 * ① **每个 `putString` 的第二个实参必须是加密调用**，不得为了"先赋个变量更可读"
 *    就绕开。`android-check` 的 `secure-storage-required` 判据按此形态逐条核对，
 *    反向验证里有对应的注入用例。
 * ② 解密失败一律当"没有会话"（[KeystoreCrypto.decrypt] 返回 `null`），
 *    **不得**回落明文读取 —— 那会让"曾经用明文存过"的设备绕过加密路径。
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(Keys.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 当前令牌；`null` 表示未登录（或密文解不开 —— 两者处理相同，都得重新登录）。 */
    fun token(): String? = readSecret(Keys.TOKEN)?.takeIf { it.isNotBlank() }

    fun saveToken(token: String) {
        prefs.edit().putString(Keys.TOKEN, KeystoreCrypto.encrypt(Keys.KEY_ALIAS, token)).apply()
    }

    /**
     * A2 下发的档位档案（契约 `AuthMeData`）；`null` 表示尚未拉到
     * —— 此时**不得放行任何业务界面**（档位唯一权威还未到手）。
     */
    fun me(): AuthMeData? {
        val raw = readSecret(Keys.ME) ?: return null
        // 🛑 缓存损坏**不抛**：当作"没有缓存"，由 A2 重新拉一次即可
        //    （与 Web 端 getCachedProfile 同口径）。
        val parsed = runCatching { gson.fromJson(raw, AuthMeData::class.java) }.getOrNull()
        if (parsed == null || parsed.role.isBlank()) {
            prefs.edit().remove(Keys.ME).apply()
            return null
        }
        return parsed
    }

    fun saveMe(me: AuthMeData) {
        val json = gson.toJson(me)
        prefs.edit().putString(Keys.ME, KeystoreCrypto.encrypt(Keys.KEY_ALIAS, json)).apply()
    }

    /**
     * 当前角色。未登录 / 缓存损坏返回 `null` —— 调用方必须 fail-closed
     * （**不得**回落一个默认角色，理由见 `auth.Access.requireAppRole`）。
     */
    fun role(): String? = me()?.role?.takeIf { it.isNotBlank() }

    /** 是否已具备"能出站"的最小条件（有令牌且有档位）。 */
    fun isLoggedIn(): Boolean = token() != null && role() != null

    /**
     * 清空会话。
     * 🛑 幂等：退出登录调两次不应报错（一线可能连点两次）。
     */
    fun clear() {
        prefs.edit().remove(Keys.TOKEN).remove(Keys.ME).apply()
    }

    /**
     * 读一个**加密**存储的值（唯一读取路径，与写入侧的 encrypt 对称）。
     *
     * 🛑 刻意**没有**"先按明文读一次"的兜底分支 —— 那会给"曾经用明文存过"
     *    留一条绕过加密的旁路（第 46 条同型：兼容代码会让新路径的必要性被架空）。
     *    本端尚未发布（`versionCode = 1`），不存在需要兼容的历史数据；
     *    真要兼容也必须写成"读明文 → 立即加密回写 → 之后不再读明文"的**一次性迁移**，
     *    而不是长期共存两条路径。
     */
    private fun readSecret(key: String): String? =
        prefs.getString(key, null)?.let { KeystoreCrypto.decrypt(Keys.KEY_ALIAS, it) }

    private object Keys {
        const val PREFS_NAME = "dy.therapist.session"
        const val TOKEN = "dy.token"
        const val ME = "dy.me"

        /**
         * AndroidKeyStore 里的密钥条目名。
         *
         * 🛑 与 [PREFS_NAME] 分开命名不是啰嗦：前者是**文件**名，后者是
         *    **密钥库条目**名，两者活在完全不同的存储里（一个在 app 私有目录的
         *    XML，一个在安全硬件 / 系统密钥库）。若混用同一个字符串，会让
         *    "清会话"这类操作看起来像也清掉了密钥，而实际上密钥还在 ——
         *    这种"以为清了其实没清"的错觉正是我们要避免的。
         *
         * ⚠️ 改这个值等于**换密钥**：旧密文会全部解不开，表现为所有已登录用户
         *    在升级后需要重新登录。这是可接受的（会话本就是有时效的），
         *    但必须是**有意**为之，不能是顺手重命名。
         */
        const val KEY_ALIAS = "dy.therapist.session.key"
    }
}
