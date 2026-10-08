// ============================================================================
// 端 B · 调理师 / 经络师 APP（原生 Android）—— 依赖源配置
// ============================================================================
//
// 🛑 仓库顺序说明（不是随手一排）
// ---------------------------------------------------------------------------
// 镜像在前，是"本机在中国大陆"的**可用性 / 速度优先**排序。
//
// ⚠️ 一条需要定期复测的结论（不要把某次实测当成永久事实）
// ---------------------------------------------------------------------------
//   · 更早一轮实测：dl.google.com / repo1.maven.org / services.gradle.org
//     均超时**不通** —— 当时的排序理由是"**唯一可达**"。
//   · 2026-10-08 复测（`curl -L` 拉**真实 artifact**，不是探根路径）：
//       dl.google.com/dl/android/maven2/…/gradle-8.12.0.pom        → 200
//       repo1.maven.org/maven2/…/kotlin-gradle-plugin-2.1.20.pom   → 200
//       maven.aliyun.com/repository/{google,public}/…（同一批 pom） → 200
//     四家源**全部可达** ⇒ 结论翻案。
//     （注：探 `dl.google.com/` 根路径会得 404、探 aliyun 仓库根路径也是 404，
//       那是"禁止列目录"的正常行为，**不能**据此判定不可达 —— 必须拉具体 pom。）
//
//   ⇒ 镜像在前的理由已从"唯一可达"变为"**国内 CDN 更近、首次构建更快**"。
//      排序**不变**（对国内开发者仍是更优路径），但理由必须写准：
//      官方源留在列表里的作用是**兜底** —— 镜像不可达时 Gradle 会继续往后试，
//      客户 CI 若走官方源同样能构建。
//
// 🛑 不写死任何私有仓库凭据：本文件里没有 token、没有账号。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "dy-therapist-android"
include(":app")
