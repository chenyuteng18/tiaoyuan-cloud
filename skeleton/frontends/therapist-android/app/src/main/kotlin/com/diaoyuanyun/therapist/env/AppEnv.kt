package com.diaoyuanyun.therapist.env

import com.diaoyuanyun.therapist.BuildConfig
import com.diaoyuanyun.therapist.contract.Contract

/**
 * 端 B · 环境配置分离（原生 Android）
 * ============================================================================
 *
 * 与端 A / 端 B(Web) 的 `env/index.ts` **同一条纪律**，只是落点不同：
 *
 * 🛑 Web 端：基址来自 `VITE_API_BASE_URL`，缺失即**模块加载期**抛错。
 * 🛑 原生端：没有"运行期读环境变量"这回事 —— 环境是**构建期**烙进产物的。
 *    故分两段：
 *      · 构建期：`-Pdy.apiBaseUrl=` / `-Pdy.env=` 注入 BuildConfig
 *        （见 app/build.gradle.kts 的 cfgString 与 dyApiBaseUrlRaw）；
 *      · 运行期：本文件在 Application.onCreate 里做**启动自检**，
 *        基址为空即拒绝启动。
 *
 * 🛑 为什么默认值必须是"空"而不是一个"大概是联调地址"
 * ---------------------------------------------------------------------------
 * 与 Web 端逐字一致：回落会让一次配错的部署看上去正常，直到有人发现数据
 * 写到了错误的库。留空 + 启动即炸，把"忘记配基址"从"第一次下单时才发现"
 * 提前到"第一次启动时"。
 *
 * 🛑 为什么环境名走白名单枚举而不是自由字符串
 * ---------------------------------------------------------------------------
 * 自由字符串会让 `-Pdy.env=prodction`（拼错）静默产出一个"名字是 prodction
 * 的环境"——它既不是 prod 也不是 staging，而所有 `if (env == PROD)` 之类的
 * 判断都会**静默走 else 分支**。故三值白名单 + 未知即抛错。
 */
enum class EnvName(val label: String) {
    DEV("dev"),
    STAGING("staging"),
    PROD("prod"),
    ;

    companion object {
        /** 未知取值一律抛错（不得回落到 DEV）—— 拼错环境名必须当场暴露。 */
        fun of(raw: String): EnvName {
            val v = raw.trim().lowercase()
            for (candidate in entries) {
                if (candidate.label == v) return candidate
            }
            throw IllegalStateException(
                "env: 未知环境名 '$raw'。合法取值：${
                    entries.joinToString(" / ") { it.label }
                }。以 -Pdy.env= 指定；未知取值不得回落 —— 否则拼错的环境名会产出一个「" +
                    "既不是 prod 也不是 staging 的产物，而所有按环境分支的逻辑都会静默走 else」。"
            )
        }
    }
}

/**
 * 本端唯一的运行期环境真相源。
 *
 * 🛑 与 Web 端的一处刻意差异（必须写明，否则会被当成"漏了 staging"）
 * ---------------------------------------------------------------------------
 * Web 端有 `import.meta.env.MODE` 三档（Vite 的 `--mode` 直接给出）。
 * 原生端的环境名**只能**由 `-Pdy.env=` 显式指定（debug→dev / release→prod 为缺省）。
 * ⇒ 要出 staging 包必须 `-Pdy.env=staging`，**不存在**"顺手打了个 staging 包但
 *   环境名还是 dev"的情形：环境名与产物一一对应，构建脚本可核验。
 */
object AppEnv {

    /** 环境名（构建期注入，白名单校验）。 */
    val name: EnvName by lazy { EnvName.of(BuildConfig.ENV_NAME) }

    /**
     * 网关根 —— **不含** Base Path。
     *
     * 🛑 运维视角的地址就是它（`-Pdy.apiBaseUrl=http://网关/`）。
     * 出站层**不得**直接用它拼 URL，必须用 [requestBaseUrl]。
     */
    val gatewayBaseUrl: String by lazy { BuildConfig.API_BASE_URL.trim().trimEnd('/') }

    /**
     * 出站 URL 前缀 = 网关根 + 契约 §2.0 Base Path（生成物常量 `Contract.API_BASE_PATH`）。
     *
     * 🛑 这正是本仓第 56 条（跨端隐式协议缺口）在原生端的落点：
     *    契约 `paths` 键是 `/auth/me`，真实 URL 是 `/api/v1/auth/me` —— 前缀由
     *    契约 `servers[0].url` 承载，只写在注释里等于没有约定（运维按注释配置
     *    ⇒ 全量 404，且编译 / 构建 / 门禁全绿）。
     *    故本端从**生成物**取前缀，漏拼即被 android-check 判红。
     */
    val requestBaseUrl: String by lazy { gatewayBaseUrl + Contract.API_BASE_PATH }

    val isProduction: Boolean get() = name == EnvName.PROD

    /**
     * 启动自检（由 Application.onCreate 调用）。
     *
     * 🛑 只检查"能不能启动"，不检查"连不连得通"：
     *    后者是运行期健康检查的事，放在启动期会让"后端短暂不可用"变成"APP 打不开"，
     *    而正确的用户预期是"APP 能打开，登录时报网络异常"。
     */
    fun assertConfigured() {
        if (gatewayBaseUrl.isEmpty()) {
            throw IllegalStateException(
                "env: 缺少后端基址（BuildConfig.API_BASE_URL 为空）。\n" +
                    "请在构建时注入：./gradlew -Pdy.apiBaseUrl=http://<网关地址> assembleDebug\n" +
                    "（模拟器访问宿主机用 http://10.0.2.2:<端口>）\n" +
                    "不得回落默认地址 —— 配错的部署必须立刻失败，而不是打到错误的库。"
            )
        }
        // 生产环境必须走 HTTPS：明文 HTTP 在正式包里是不可接受的
        if (isProduction && gatewayBaseUrl.startsWith("http://")) {
            throw IllegalStateException(
                "env: 生产环境（-Pdy.env=prod）不得使用明文 HTTP 基址：$gatewayBaseUrl\n" +
                    "请配置 HTTPS。若确为受控内网，请先裁定并写入本判据，不得直接放开。"
            )
        }
    }

    /** 供设置页 / 日志使用的一行摘要（**不得含令牌**）。 */
    fun describe(): String =
        "env=${name.label} gateway=$gatewayBaseUrl request=$requestBaseUrl"
}
