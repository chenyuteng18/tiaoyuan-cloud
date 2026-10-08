// ============================================================================
// 端 B · 调理师 / 经络师 APP（原生 Android）—— app 模块
// ============================================================================
//
// 🛑 依赖原则：只引"真的用到"的，且每一条都要能说清为什么
// ---------------------------------------------------------------------------
//   appcompat            —— ActivityCompat / AppCompatActivity 基类（本端页面外壳）
//   core-ktx             —— AndroidX 基础扩展（本端仅用 ContextCompat 等）
//   material             —— Material Components：按钮 / 文本输入 / 卡片。
//                           不用它就得自己重造带状态层的按钮与输入框，
//                           那是"看起来像原生、实际一点都不原生"的常见来源。
//   okhttp               —— 出站层（HTTP/2、连接池、超时、拦截器）
//   gson                 —— 信封与请求体的 JSON 编解码
//   kotlinx-coroutines   —— 网络调用不得在主线程（Android NetworkOnMainThreadException）
// 刻意**不引**：Compose（会引入整条 Compose 工具链与运行时）、Retrofit
// （本端出站层是**契约常量驱动**的通用出站，不需要注解式接口生成 —— 引了反而
//  会造出第二份"端点声明"，正是本仓第 54/57 条反复惩罚的那种重复源）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 后端基址注入（fail-closed：缺失即注入空串，由 AppEnv 在启动时拒绝启动）
// ---------------------------------------------------------------------------
// 取值优先级：-Pdy.apiBaseUrl=… > 环境变量 DY_API_BASE_URL > 空串（⇒ 启动即抛错）
//
// 🛑 为什么不在构建期直接 throw
// ---------------------------------------------------------------------------
// 构建期抛错会让"我只想编译一下看看类型对不对"必须先编一个后端地址才行，
// 那会逼着人往版本库里塞一个假地址 —— 比留空更坏。
// 留空 + 运行期拒绝启动，与端 A / 端 B（Web）的 `VITE_API_BASE_URL` 纪律一致：
// **配错的部署必须立刻失败**，而不是静默打到错误的库。
val dyApiBaseUrlRaw: String =
    (project.findProperty("dy.apiBaseUrl") as String?)
        ?: (System.getenv("DY_API_BASE_URL") ?: "")

// 显式转义后再交给 buildConfigField，避免地址里出现引号/反斜杠时注入坏字面量
fun cfgString(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val dyApiBaseUrlLiteral: String = cfgString(dyApiBaseUrlRaw)

// ---------------------------------------------------------------------------
// 环境名注入（dev / staging / prod）
// ---------------------------------------------------------------------------
// 🛑 与 Web 端的差别必须写明：Web 用 `import.meta.env.MODE`（Vite 注入），
//    原生端没有运行期"模式"这回事 —— 环境是**构建期**烙进产物的。
//    故这里由 `-Pdy.env=` 决定；不给时按构建类型推断（debug→dev / release→prod）。
//    ⇒ 要出 staging 包，必须**显式** `-Pdy.env=staging`，不存在"顺手打了
//      一个 staging 包但环境名还是 dev"的情况 —— 环境名与产物一一对应。
val dyEnvRaw: String = (project.findProperty("dy.env") as String?) ?: ""

// ---------------------------------------------------------------------------
// 发布签名属性（**密钥永不进仓库** —— 只从属性 / 环境读绝对路径）
// ---------------------------------------------------------------------------
// 🛑 为什么不在本文件里写任何默认值
// ---------------------------------------------------------------------------
// 写一个"大概是这个路径"的默认值，会让"还没配签名"的状态**看起来已配好**，
// 直到签名校验失败时才暴露 —— 而那时已经打出了包（见文件末尾的 taskGraph 校验）。
// 缺一个属性就当作"没有签名"，由末尾那道闸门决定放不放行。
val dyStoreFile: String? = (project.findProperty("dy.storeFile") as String?)?.takeIf { it.isNotBlank() }
val dyStorePassword: String? = (project.findProperty("dy.storePassword") as String?)?.takeIf { it.isNotBlank() }
val dyKeyAlias: String? = (project.findProperty("dy.keyAlias") as String?)?.takeIf { it.isNotBlank() }
val dyKeyPassword: String? = (project.findProperty("dy.keyPassword") as String?)?.takeIf { it.isNotBlank() }

/** 缺哪些签名属性（用于报错文案：必须告诉人**差什么**，而不是只说"没配"）。 */
val missingSigningProps: List<String> = listOf(
    "dy.storeFile" to dyStoreFile,
    "dy.storePassword" to dyStorePassword,
    "dy.keyAlias" to dyKeyAlias,
    "dy.keyPassword" to dyKeyPassword,
).filter { it.second == null }.map { it.first }

val hasSigning: Boolean = missingSigningProps.isEmpty()

android {
    namespace = "com.diaoyuanyun.therapist"
    compileSdk = 36

    // -------------------------------------------------------------------------
    // 发布签名（**仅当四个属性齐全时才创建**）
    // -------------------------------------------------------------------------
    // 🛑 为什么是"条件创建"而不是"无条件创建 + 字段留空"
    // -------------------------------------------------------------------------
    // 若无条件 `create("release")` 而在缺属性时留空字段，AGP 仍会把这个**残缺的**
    // signingConfig 挂到 release 上 —— 结果是构建通过、包却是 unsigned，
    // 或者签名阶段报一句很难懂的错。条件创建让"到底有没有签名"在**配置期**
    // 就是一个明确的布尔值（hasSigning），末尾那道闸门可以直接用它。
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(dyStoreFile!!)
                storePassword = dyStorePassword
                keyAlias = dyKeyAlias
                keyPassword = dyKeyPassword
                // minSdk = 24 ⇒ 目标设备都支持 APK Signature Scheme v2，
                // 故 V1（JAR signing）**不需要** —— 它只会增大包体积。
                enableV1Signing = false
                enableV2Signing = true
                // V3 支持密钥轮换（API 28+）；更老的设备会忽略它，向下兼容。
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.diaoyuanyun.therapist"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // 🛑 不在这里写任何后端地址 / 密钥：一切来自 BuildConfig（见上）
    }

    buildFeatures {
        // BuildConfig 必须显式开启（AGP 8 默认关闭）—— 本端的环境注入依赖它
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", dyApiBaseUrlLiteral)
            buildConfigField("String", "ENV_NAME", cfgString(dyEnvRaw.ifEmpty { "dev" }))
            isMinifyEnabled = false
        }
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", dyApiBaseUrlLiteral)
            buildConfigField("String", "ENV_NAME", cfgString(dyEnvRaw.ifEmpty { "prod" }))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 🛑 签名密钥**不提交**（见 README「上架前必须补的三件事」第 1 条）。
            //
            // ⚠️ 这里原先是这么写的：「缺签名时 release 构建会失败，这是**故意**的
            //    —— 让还没配签名就打正式包当场暴露」。**那句话是错的。**
            //    2026-10-08 实测：`assembleRelease` 在无签名配置时 **BUILD SUCCESSFUL**，
            //    并安静地产出 `app-release-unsigned.apk`。而那个包**装不上**
            //    （INSTALL_PARSE_FAILED_NO_CERTIFICATES）——
            //    构建日志是绿的、文件也在，于是它的典型结局是被人用 debug key 签了发出去。
            //
            //    真正的闸门在本文件末尾的 `gradle.taskGraph.whenReady`：
            //    请求了 release 打包任务但缺签名 ⇒ **当场抛错，不产出任何包**。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 🛑 `enableEdgeToEdge` / `SystemBarStyle` 来自 androidx.activity。它虽是 appcompat
    //    的传递依赖，但**直接调用的 API 必须直接声明**：否则某天 appcompat 把它从
    //    `api` 改成 `implementation`（对它自己是非破坏性变更），本端会突然编译失败，
    //    而失败原因看起来与本工程毫无关系。
    //    1.8.0 是引入 `enableEdgeToEdge` 的版本（已反编译 1.8.0 的 AAR 确认
    //    `androidx/activity/EdgeToEdge.class` 与 `SystemBarStyle.class` 均存在）。
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

// ===========================================================================
// 闸门：请求了 release 打包任务、但没有签名配置 ⇒ **当场失败**（不产出 unsigned 包）
// ===========================================================================
// 🛑 这条闸门是**实测逼出来的**，不是设计洁癖
// ---------------------------------------------------------------------------
// 本文件原先只写了一句注释，声称「缺签名时 release 构建会失败，这是故意的」。
// 2026-10-08 实测把这个说法**证伪**了：AGP 在 release 无 signingConfig 时
// **BUILD SUCCESSFUL**，并产出 `app-release-unsigned.apk`（1.94 MB）。
//
// 为什么这比"构建失败"危险得多：
//   · 构建日志是绿的 ⇒ CI 不会拦；磁盘上能看到包 ⇒ 人以为已经出包了；
//   · 它**装不上**（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`），
//     而报错要等到有人拿真机去装才出现 —— 那时包已经流转出去了；
//   · 更可能发生的那条分支：有人为了"先让它能装"直接改用 debug key 签名，
//     于是产出一个**不可上架、密钥不可控**的包，还被当成了正式包。
// ⇒ 唯一安全的做法是让"还没配签名就想打正式包"**在任何产物生成之前**失败。
//
// 🛑 判定范围刻意收窄到**打包任务**，不用 `contains("Release")`
// ---------------------------------------------------------------------------
// 若用后者，`compileReleaseKotlin` / `lintVitalRelease` 之类也会算进来 ——
// 那意味着"只想给 release 变体跑一次 lint"都会被拦住，属于误伤。
// 真正产出（或试图产出）release 包的只有这三个任务。
gradle.taskGraph.whenReady {
    val releasePackaging = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "packageRelease"
    }
    if (releasePackaging && !hasSigning) {
        throw GradleException(
            "已中止：请求了 release 打包，但签名配置不完整 —— **不产出 unsigned 包**。\n" +
                "  缺少的属性：${missingSigningProps.joinToString(", ")}\n" +
                "\n" +
                "请在**不入版本库**的位置提供（推荐 ~/.gradle/gradle.properties）：\n" +
                "  dy.storeFile=/绝对路径/release.jks\n" +
                "  dy.storePassword=<口令>\n" +
                "  dy.keyAlias=<别名>\n" +
                "  dy.keyPassword=<口令>\n" +
                "\n" +
                "或一次性命令行传入：\n" +
                "  ./gradlew -Pdy.storeFile=… -Pdy.storePassword=… -Pdy.keyAlias=… -Pdy.keyPassword=… assembleRelease\n" +
                "\n" +
                "🛑 密钥文件与口令**不得**出现在本仓库的任何文件中（.gitignore 已排除 *.jks / *.keystore）。",
        )
    }
}
