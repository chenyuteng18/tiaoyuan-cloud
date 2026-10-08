// ============================================================================
// 端 B · 调理师 / 经络师 APP（原生 Android）—— 依赖源配置
// ============================================================================
//
// 🛑 仓库顺序说明（不是随手一排，且随实测结论修订过两次）
// ---------------------------------------------------------------------------
//   · 最初：官方源超时**不通**，镜像在前是"**唯一可达**"。
//   · 2026-10-08 复测（`curl -L` 拉**真实 artifact**，不是探根路径）：
//       dl.google.com / repo1.maven.org / maven.aliyun.com 同一批 pom → 全 200。
//     四家源全部可达 ⇒ 官方源不再是"不可达"。
//   · 2026-10-09 实战教训（CI 首跑抓到）：**Gradle 对 5xx 不做仓库间兜底** ——
//     aliyun 镜像对 grpc-util 的 pom 返回 502 时，构建**直接失败**，
//     排在后面的 google()/mavenCentral() 根本不会被尝试（404 才会换下一个仓库）。
//     即"镜像在前 + 官方兜底"的排序在镜像抖动时等于没有兜底。
//
//   ⇒ 现排序：**官方源在前**。理由：
//     ① 对 CI（GitHub runner）：官方源就是最近最稳的源；502 的 aliyun 在前
//        只会把偶发抖动放大成构建失败（"构建全绿、上线才炸"红线）。
//     ② 对国内开发者：四源实测全部可达，官方源慢一点但**不会失败**；
//        镜像留在后面，恰好兜官方源 404 的角落在镜像上有、官方没有的包。
//     （排序不是信仰：哪天官方源又不可达了，把顺序换回来即可 —— 但必须带着
//       新的实测证据来改，并改掉本注释。）
//
// 🛑 不写死任何私有仓库凭据：本文件里没有 token、没有账号。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "dy-therapist-android"
include(":app")
