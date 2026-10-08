# 端 B · 调理师 / 经络师 APP（原生 Android · Kotlin）

本目录是"调元云"四端之一的**原生 Android 工程**。它不是"名字叫 APP 的网页"——
产物是真能安装的 `app-debug.apk`（见下方「构建」）。

| 事实 | 取值 |
|---|---|
| `applicationId` / `namespace` | `com.diaoyuanyun.therapist` |
| 版本 | `versionCode=1` / `versionName=0.1.0` |
| SDK | `compileSdk=36` · `targetSdk=36` · `minSdk=24` |
| 入口 | `com.diaoyuanyun.therapist.MainActivity`（唯一 LAUNCHER） |
| Application | `com.diaoyuanyun.therapist.DyTherapistApp`（启动自检 + 依赖装配） |
| 规模 | 38 个 Kotlin 文件 · 8 个页面 · 6 个 res XML（含 15 个图标位图） |
| 契约端点 | 29 个（与裁剪契约逐条一致，由 `android-check` 核验） |

## 一、为什么是原生，而不是把 Web 端套壳

端 B（Web）已经存在，把它的产物塞进 WebView 是最省事的做法。**刻意没有那么做**：

- WebView 壳在**离线 / 弱网**下的表现是"白屏"，而一线的使用场景是门店内、
  信号不稳、需要"打开就能用"。原生端有真实的本地状态与页面栈。
- 令牌存储、网络安全配置、明文放行范围这些**安全边界**，在原生端是
  `AndroidManifest` + 源集（`src/debug/`）级别的事实；在 WebView 壳里则是
  "JS 运行时的一个约定"，随时会被一层注入绕过。
- 上架要求的是原生工程（图标、签名、权限声明、隐私清单）。套壳包在这些
  面上每一项都要额外解释。

代价是同一个端有两份实现。这个代价**由门禁兜住**：两端共用同一份契约生成物
（`contract/Endpoints.kt` 与 Web 端的 `contract/*.ts` 同源），且都跑
"端点全接上 / 必需参数全转出 / 协议无字面量 / 枚举与契约同源"这同一批判据。
⇒ 差异是"渲染方式"，不是"业务口径"。

## 二、本机实测工具链组合

**这三个版本是"一起验证过的一个组合"**，任何一个单改都必须重跑构建复验：

| 组件 | 版本 | 实测证据 |
|---|---|---|
| JDK | Temurin **17.0.20.1** | `./gradlew --version` → `Launcher JVM: 17.0.20.1` |
| Gradle | **9.3.1** | 同上 → `Gradle 9.3.1` |
| AGP | **8.12.0** | `build.gradle.kts`；`assembleDebug` 成功出包 |
| Kotlin | **2.1.20** | `build.gradle.kts`；`compileDebugKotlin` 成功 |
| Android SDK | `C:/Users/lenovo/Android` | `local.properties`（**不入版本库**） |

SDK 组件：`platforms;android-36` + `build-tools;35.0.0`、`36.0.0`。

> 用别的机器构建时，先确认这五个都到位；Gradle 会自动处理 AGP/Kotlin 的下载，
> 但 JDK 版本与 SDK 组件必须由环境提供。

### 仓库源：镜像在前，但理由已经变了

`settings.gradle.kts` 里 `maven.aliyun.com` 排在三家官方源**之前**，
`gradle-wrapper.properties` 的 `distributionUrl` 指向**腾讯云镜像**。
理由是**本机在中国大陆**（国内 CDN 更近、首次构建更快）——
但这是**修正后**的理由，原来不是这个。

> ⚠️ **实测翻案记录（保留它本身是个教训）**
>
> 最早一轮实测时，官方源超时**不通**，只有镜像可用 ——
> 那时的排序理由是"**唯一可达**"。2026-10-08 用 `curl -L` 拉**真实 artifact**
> 复测，四家源**全部 200**：
>
> | 源 | 拉的 artifact | 结果 |
> |---|---|---|
> | `dl.google.com` | AGP 8.12.0 的 pom | **200** |
> | `repo1.maven.org` | Kotlin 2.1.20 插件的 pom | **200** |
> | `maven.aliyun.com/repository/google` | 同一批 AGP pom | **200** |
> | `maven.aliyun.com/repository/public` | 同一批 Kotlin pom | **200** |
>
> **探根路径会得出错误结论**：探 `dl.google.com/` 得 404、探 aliyun 仓库根路径
> 也 404 —— 那是"禁止列目录"的正常行为，**不能**据此判定不可达。
> 必须拉具体 artifact 才算实测。
>
> 排序**不变**（对国内开发者仍是更优路径），但理由与注释都按复测结果改准了。
> 官方源留在列表里的作用是**兜底**：镜像不可达时 Gradle 会继续往后试，
> 客户 CI 若走官方源同样能构建。
>
> ⇒ 教训：**不要把某一次网络实测结论当成永久事实**（本轮就复测翻案了一次）。

## 三、构建

```bash
# 出开发包（必须注入后端基址，缺失即运行期拒绝启动）
./gradlew -Pdy.apiBaseUrl=http://10.0.2.2:18080 assembleDebug

# 仅编译（不注入基址也可以——构建期刻意不抛错，见 app/build.gradle.kts 的说明）
./gradlew --offline :app:compileDebugKotlin

# 产物（实测 7 197 065 字节 ≈ 6.9 MiB）
# app/build/outputs/apk/debug/app-debug.apk
```

`10.0.2.2` 是 Android 模拟器访问宿主机 `127.0.0.1` 的固定地址。

后端基址与环境的取值优先级：

| 配置 | 优先级 | 缺省 |
|---|---|---|
| 后端基址 | `-Pdy.apiBaseUrl=` > 环境变量 `DY_API_BASE_URL` > **空** | 空 ⇒ **启动时抛错** |
| 环境名 | `-Pdy.env=` > 按构建类型推断 | debug→`dev` / release→`prod` |

**刻意不回落默认值**：与 Web 端 `VITE_API_BASE_URL` 逐字同一纪律 ——
回落会让一次配错的部署看上去正常，直到有人发现数据写到了错误的库。
留空 + 启动即炸，把"忘记配基址"从"第一次下单时"提前到"第一次启动时"。

环境名是**三值白名单**（`dev` / `staging` / `prod`），未知取值抛错而不是回落 `dev`：
拼错成 `prodction` 会产出一个"既不是 prod 也不是 staging 的产物"，
而所有按环境分支的逻辑都会静默走 else。

## 四、门禁

两条命令，都在 `skeleton/frontends/` 下跑：

```bash
node tools/android-check.mjs            # 结构自检：25 条判据
node tools/android-reverse-check.mjs    # 反向验证：43 组注入
```

> 🛑 **生成物缺失时不要自己敲 `python`。** 四端共用同一个生成器入口：
> 在 `skeleton/` 下 `node frontends/tools/gen-endpoints.mjs`，
> 或在任一端目录内 `npm run gen:endpoints`（`--check` 只校验不写）。
> `tools/py.mjs` 会解析出**带 PyYAML 的那个解释器**；直接敲
> `python ../tools/gen-endpoints.py` 在本机会 `No module named 'yaml'`。
> 理由见第八节第 10 条与 `frontends/README.md` §8.28。

**`android-check.mjs`** 管的是"写下的东西 == 契约里那个东西"：

| 判据 | 防的是哪一类真实缺陷 |
|---|---|
| `parse` / `parse-coverage` | 生成物与裁剪契约的 operation 数、id 逐条对得上（第 53 条） |
| `required-args-parsed` | 契约侧必需参数与生成物逐条一致，**覆盖面是等式而非"非空"** |
| `roles` | 每个端点都有非空且属于本端 token-roles 的授权角色 |
| `entry-declared` | 主清单声明 Activity，LAUNCHER 唯一，Application 指向自检类 |
| `nav-tab-render-triangulation` | Tab 表 ↔ `when` 分支 ↔ 页文件**三方一致**（第 63/64 条②） |
| `endpoint-wired` | 29 个端点都真被源码引用（无"生成了没人用"） |
| `wrapper-own-endpoint` | 出站封装与端点 id **一对一**（无两函数共用一个端点） |
| `no-hardcoded-endpoint-list` | 准入层不得手抄端点 id（第二份权威） |
| `anonymous-call-single-site` | 匿名出站恰 1 处，且必须在 `authLogin()` 里 |
| `session-key-single-source` | 会话键名只定义于一处，且只有一处 `getSharedPreferences` |
| `secure-storage-required` | 本地存储必须全程加密（写入按实参形态判 / 读取按与 `decrypt` 等数判） |
| `no-protocol-literal` | 头名 / 令牌前缀 / Base Path / 分页参数名零字面量（第 56/57/58/61 条） |
| `protocol-fields-wired` | 协议字段名（信封 4 + 分页容器键）必须有生成物**具名常量**，且出站层必须**引用**它 |
| `base-path-wired` | 出站前缀 = 网关根 + 契约 Base Path；生产禁明文 |
| `no-color-literal` | 色值只出现在 `res/values/colors.xml` |
| `cleartext-debug-only` | 明文放行只存在于 `src/debug/`（**判源集归属**） |
| `named-enum-verbatim` | `Dimension(7)` / `AgeGroup(8)` 与契约逐值逐序一致 |
| `enum-labels-verbatim` | 3 张标签表与契约内联枚举逐值一致 |
| `required-args-wired` | 契约必需参数（path/query/body）在调用链上真的带上了（判实参形态，非判词出现） |
| `release-gate-present` | release 闸门必须完整（缺签名不得静默产出 unsigned 包） |
| `launcher-icon-complete` | 启动图标能从 manifest 解析到真实资源（名从 manifest 现算，不硬编码） |
| `gson-reflect-surface` | Gson 反序列化目标必须被 keep 规则覆盖（**R8 会抹掉裸字段**，见第六节 P0 记录） |
| `contract-schema-fields` | 响应 DTO 与契约 `components.schemas` 的**字段集合双向相等**（DTO 是手写的**第二份权威**，见第五节注） |
| `edge-to-edge-insets` | **targetSdk ≥ 35 强制边到边**：启用落点 + 三类 inset（`systemBars` / `displayCutout` / `ime`）+ `onCreate` 接线且早于 `setContentView` + 主题不得再留已失效的系统栏属性（见第六节留痕） |

**`android-reverse-check.mjs`** 证明门禁**有牙齿**：对 43 类注入逐一断言
"注入 → 必须变红 → 命中**指定判据** → 还原 → 必须变绿"。
其中 I10 是本工程抓出的真实缺陷的**回归用例**：`patchIntakeProfile()` 曾误用
`ENDPOINT_INTAKE`（= `"getIntakeProfile"`，一个 GET 端点）—— 一个 GET 端点被当成
PATCH 用，body 与幂等键发出去而服务端根本不读。它能编译、能构建、
`gen-endpoints.py --check` 也绿，**只有真发一次请求才会暴露**。

I26 / I27（明文存储）、I28 / I29（release 闸门）、I30–I32（启动图标）、
I33 / I34（R8 与 Gson）、I35 / I36（协议字段名常量）、I37–I39（DTO ↔ 契约 schema）、
I40–I43（边到边与窗口 inset）
是**新增保护措施的回归用例**：它们把"已收口的缺口"重新退回缺陷形态，
断言门禁抓得住 —— 因为**登记不等于阻止**，
新增的保护若不钉住，会在某次重构中被顺手删掉。

这几组尤其说明一件事：**它们的缺陷形态全都不报错**（18 个注入、无一例外）。
明文写盘只是 `putString` 少套一层；闸门被注释掉后 debug 构建照常；
`android:icon` 退回矢量、自适应图标少一个 `<background>` 都能编译通过；
R8 抹掉 Gson 裸字段更是 **debug 包完全正常、只有 release 包失效**；
而 DTO 的 `@SerializedName` 抄错一个字母（I37）会让该字段**恒为 null**，
界面上就是"这里永远是空的"；
边到边没接对（I40–I43：少一行接线 / 顺序挪反 / 少消费一类 inset /
主题里塞回已失效的属性）同样**编译与调试全程零告警**，
只有 API 35/36 真机才显示为"顶栏被状态栏压住、底部被导航栏压住"。
共同点是"构建日志是绿的，问题只在上线后或被别人看见"。
⇒ 这正是本仓反复强调的 **"写下的检查 ≠ 存在的检查"**：
这类缺口只能靠判据 + 反向验证来守，review 和编译都指望不上。

## 五、与 Web 端的结构对照

| 关注点 | 端 B Web（`therapist-app`） | 本端（原生） |
|---|---|---|
| 端点来源 | `contract/*.ts`（生成） | `contract/Endpoints.kt`（生成，**同源同契约**） |
| 响应模型来源 | **生成**：`../sdk/therapist-app` 的 SDK 类型（`generator-matrix.yaml` 声明） | **手写** `domain/Models.kt` —— 本端无 Kotlin 代码生成，故 DTO 是契约的**第二份手抄体**，由 `contract-schema-fields` 判据钉住（见第四节与第六节留痕） |
| 环境 | `import.meta.env.MODE`（运行期） | `BuildConfig`（**构建期烙进产物**） |
| 导航 | `App.tsx` 的 NAV 表（手写 7 项） | `ui/Nav.kt` 的 Tab 表（**逐项同构**） |
| 异步形态 | `async` 只出现在 `services/` 层 | `suspend` 只出现在出站/域层，**页面里零 `suspend`** |
| 明文放行 | dev server 配置 | `src/debug/` 源集（**不进 release**） |
| 令牌存储 | `localStorage` | `SharedPreferences`，且值经 `AndroidKeyStore` 加密（见第六节留痕） |
| 启动图标 | **未配置**：无 `public/` 目录，`index.html` 也没有 favicon / webmanifest 引用 | `mipmap` 5 档位图 + `mipmap-anydpi-v26` 自适应（见第六节第 2 条） |

> 上表最后一行的 Web 端状态是**如实登记的缺口**（实测结论：`ls therapist-app` 无
> `public/`，`index.html` 里 grep 不到 `icon` / `favicon` / `manifest`）。
> 它不影响本端，但是"同一个产品在两个端上品牌资产不齐"这类上架问题，
> 故登记不修 —— 本端 README 不为 Web 端开任务。

**一处如实登记的跨端不一致**（不是本端引入的）：Web 端
`therapist-app/src/contract/access.ts` 手写了 `ROLE_LABEL = { therapist: '调理师',
meridian: '经络师' }`，而契约 `x-roles.display` 给的是 `调理师（APP）` /
`经络师（APP）`。两者都不算错，但同一角色在两端显示不同文本属"五源对齐"漂移。
本端取舍：**以契约为准**（不手写第二份，见 `auth/Access.kt` 的 `roleDisplay`）。
建议 Web 端也收敛到契约 `display`，而不是在原生端再手写一份来"对齐"。

## 六、上架前必须补的三件事

> 这三条**都不是"以后再优化"，是缺了就上不了架 / 有生产风险**。
> 之所以现在就写出来：本仓反复出现"登记了的缺口有人跟，没登记的缺口没人跟"。
>
> ⚠️ 本节原先列的是**四件事**。其中"令牌存储加密"已于早前一轮**收口**。
> 本节末尾另有**三条** **✅ 已收口（留痕）** 小节（R8 与 Gson 的 P0、
> 协议字段名与 DTO ↔ 契约 schema、边到边与窗口 inset）—— 它们同样是
> "写下来但**不**留在待办里充数"的已完成项，
> 留在本节是因为**它们的实测证据与验证命令**对后来者有用。
>
> 本轮把第 2 条（图标）的**工程部分做完了**：目录结构、密度、自适应图标、
> `roundIcon`、判据与回归全部就位。它仍留在本节，因为剩下的
> **设计资产替换与商店位图**不是写代码能解决的 —— 但读的人不该
> 再以为"图标还缺代码"，故在此说清。

### 1. 生成你自己的签名密钥（缺签名时闸门会拦住 release 打包）

**仓库里没有任何 keystore**（`.gitignore` 排除 `*.jks` / `*.keystore`），
CI 里也不配签名 —— 密钥只应存在于你自己的机器上。

`app/build.gradle.kts` 从**四个属性**读签名，四个齐全才创建 `signingConfig`：

| 属性 | 含义 |
|---|---|
| `dy.storeFile` | keystore 的**绝对路径** |
| `dy.storePassword` | keystore 口令 |
| `dy.keyAlias` | 密钥别名 |
| `dy.keyPassword` | 密钥口令（PKCS12 下与 storePassword 相同） |

推荐写在 `~/.gradle/gradle.properties`（**不在任何仓库内**）：

```properties
dy.storeFile=/绝对路径/release.jks
dy.storePassword=<口令>
dy.keyAlias=<别名>
dy.keyPassword=<口令>
```

生成生产密钥：

```bash
keytool -genkeypair \
  -keystore /安全位置/release.jks -storetype PKCS12 \
  -alias <别名> -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass <口令> -keypass <口令> \
  -dname "CN=<组织名>, O=<组织>, L=<城市>, ST=<省>, C=CN"
```

> ⚠️ **`-validity` 必须足够长**：Google Play 要求签名密钥有效期至少覆盖到
> **2033-10-22** 之后，故用 `10000` 天（约 27 年）。
> ⚠️ 密钥一旦丢失，**同一个 `applicationId` 就再也无法更新**（除非已启用
> Play App Signing 的密钥轮换）。务必离线备份，且**不要**放进任何代码仓库。

**⚠️ 一条已被实测证伪的旧说法**（留痕，因为原说法会把风险讲反）

本节原先写着「缺签名时 release 构建会**失败**，这是设计意图」。**它是错的。**
2026-10-08 实测：AGP 在 release 没有 `signingConfig` 时 **BUILD SUCCESSFUL**，
并安静地产出 `app-release-unsigned.apk`（1.94 MB）。

这比"构建失败"危险得多：

- 构建日志是绿的 ⇒ CI 不会拦；磁盘上能看到包 ⇒ 人以为已经出包了；
- 它**装不上**（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`），而报错要等到有人
  拿真机去装才出现 —— 那时包已经流转出去了；
- 更可能发生的那条分支：有人为了"先让它能装"改用 debug key 签名，
  于是产出一个**不可上架、密钥不可控**的包，还被当成了正式包。

⇒ 已在 `app/build.gradle.kts` **末尾加了一道闸门**（`gradle.taskGraph.whenReady`）：
请求 `assembleRelease` / `bundleRelease` / `packageRelease` 而缺签名 ⇒
**当场抛错，不产出任何包**。判定范围刻意收窄到打包任务，
所以「只想给 release 变体跑一次 lint」不会被误伤。

**三条路径都实测过**（不是"应该会"）：

| 情形 | 实测结果 |
|---|---|
| 无签名属性 + `assembleRelease` | `exit=1`，报错**列出缺哪几个属性**，不产出包 |
| 无签名属性 + `assembleDebug` | `exit=0`，BUILD SUCCESSFUL（不受影响） |
| 四属性齐全 + `assembleRelease` | `exit=0`，产出 `app-release.apk`（1,956,356 字节，**无 `-unsigned` 后缀**） |

签名方案经 `apksigner verify` 核实（不是"文件名里没有 unsigned 就算签了"）：

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v1 scheme (JAR signing): false   <- 与 enableV1Signing=false 一致
Signer #1 key algorithm: RSA   key size: 4096
```

`V1=false` 是**配置正确**的证据而非缺陷：minSdk 24 的设备都支持 v2，
故本端显式关掉 V1 signing 以减小包体积。对照：debug 包的签名 DN 是
`CN=Android Debug`，与 release 的 DN 完全不同 —— 两者不会互相替代。

> 💡 上面那次验证用的是**开发验证用**密钥（DN 里写明 `NOT FOR PRODUCTION`），
> 放在仓库外的用户目录，**不是交付物的一部分**。生产密钥请按本节命令自行生成。

### 2. 应用图标与品牌资源

**✅ 结构已完备**（不再是待补项）—— 上架只需替换图形，不需改任何配置：

| 位置 | 内容 |
|---|---|
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 px（API 24 / 25 的回落路径） |
| `…/ic_launcher_round.png` | 同密度。manifest 的 `android:roundIcon` 指向它 |
| `…/ic_launcher_foreground.png` | 108 / 162 / 216 / 324 / 432 px（自适应图标前景） |
| `mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml` | 自适应图标：`@color/dy_brand` 背景 + 前景位图 |

⚠️ **当前图形是一枚几何标记**（同心环 + 中心点，象征经络环流），由脚本生成、
非设计稿。上架前应替换为正式品牌设计。

**替换时必须保持同样的目录结构与密度** —— 这一点有实测依据，不是谨慎起见：

```
$ aapt2 dump resources app-debug.apk | grep -A6 'mipmap/ic_launcher$'
  resource 0x7f0d0000 mipmap/ic_launcher
    (mdpi) (file) res/mipmap-mdpi-v4/ic_launcher.png type=PNG
    …
    (anydpi-v26) (file) res/mipmap-anydpi-v26/ic_launcher.xml type=XML
```

位图变体带的是 `-v4`（API 4+），自适应带的是 `-v26` ⇒ 本端 `minSdk=24` 时
API 24 / 25 **必然回落到位图**。若只放矢量或不放位图，Android 7.x 上就没有图标。

**仍需人工补的两件**（不在工程内，商店要求的位图）：

- 512×512 的应用展示图（PNG，无透明、无圆角 —— 由商店自己裁）
- Feature Graphic 1024×500（Google Play 需要；国内渠道多为截图若干张）

**判据钉住**：`launcher-icon-complete`（本轮新增）。它从 manifest 现算图标名，
逐项核对"5 档位图 + anydpi XML + background/foreground 引用落地"。
回归用例 I30–I32 分别把"退回矢量 drawable"、"前景引用悬空"、"删掉
`<background>`"重新注入一次，证明它抓得住 —— 这三种退化**全都不报构建错误**。

### 3. 上架资质与合规材料

- 隐私政策 URL、用户协议（一线注册时必须可点开）
- 中国大陆上架：软件著作权 / ICP 备案（视分发渠道）
- 应用分类与内容分级：本端属**健康管理**类，不得归入医疗诊断类；
  界面文案已按"非医疗"口径写（无"诊断"表述），但商店填写时的分类声明
  必须与此一致 —— 口径不一致是审核驳回的常见原因。
- 权限声明：主清单当前只声明两个**普通权限**（`INTERNET` /
  `ACCESS_NETWORK_STATE`，均无需运行时授权），**未声明**任何敏感权限
  （无定位 / 相机 / 通讯录 / 存储 / 蓝牙 —— 手环数据取自服务端，
  本机不接蓝牙）。但上架填 Data Safety 表单时，"网络状态"仍须如实勾选：
  **"不需要运行时授权" ≠ "不用声明"**。
  后续若加推送或扫码，需同步补隐私清单与 `POST_NOTIFICATIONS`。

### ✅ 已收口（留痕）：令牌存储加密

**原状**：`data/SessionStore.kt` 用 `SharedPreferences` **明文**存令牌与档位，
在 root 设备上可被直接读取 —— 当时如实登记为待补项。

**现方案**：新增 `data/KeystoreCrypto.kt`（`AndroidKeyStore` + AES-256-GCM，
密钥不出安全硬件），所有落盘值经它加解密。

**为什么**不**是 `EncryptedSharedPreferences`** —— 这是本节最值得留下的一条判断：

该库**已被 Google 整体废弃**：`EncryptedSharedPreferences` / `MasterKey` /
`EncryptedFile` 在 **1.1.0** 全部标记 `@Deprecated`，官方 API 文档给的方向是
「Use `javax.crypto.KeyGenerator` with AndroidKeyStore instance instead」，
并声明 *no further releases planned*。

除"引一个不再更新的库"本身，它还有两个与本项目场景**直接冲突**的已知问题：

| 已知问题 | 为什么对本项目格外致命 |
|---|---|
| 特定 OEM 设备上 **keyset 损坏**（解密抛异常、数据却还在） | 门店一线**设备型号杂**，恰好是最痛的地方 |
| 主线程 **StrictMode 违规**（重加密运算在调用线程上做） | 表现为启动卡顿，且很难归因 |

⇒ 用两个**平台内置**能力自己实现：零新增依赖、完全离线可用、行为可控。

**两个设计决策值得单独记下**（代码注释里有完整版）：

1. **写入失败就抛，读取失败回 `null`** —— 方向相反，因为「写入的失败值得暴露，
   读取的失败必须容错」。写入若回落明文，会造出**最坏形态**："看起来加密了、
   实际没有"，而且它**不会再被任何后续检查发现**（值本身就是个合法字符串）。
2. **不写「先按明文读一次」的兜底分支** —— 那会给"曾经用明文存过"留一条绕过
   加密的旁路。本端尚未发布（`versionCode = 1`），没有历史数据要兼容。

**并且被门禁钉住了**（这一步才是关键）：新增判据 `secure-storage-required`
（写入按实参形态判 / 读取按 `getString` 与 `decrypt` **等数**判），
外加反向验证 **I26 / I27** 两组回归注入 —— 把收口**退回**缺陷形态，断言抓得住。

> 💡 这一步的理由：**登记不等于阻止**。原注释如实写了"这是个待补项"，
> 但只要有人再加一个 `putString`，缺口就再开一个，而门禁不会说话。
> 缺口一旦收口，必须用判据把它钉住 —— 否则那只是"这次改好了"。

### ✅ 已收口（留痕）：R8 抹掉 Gson DTO 字段（P0）

**这是本工程迄今最危险的缺陷 —— 因为它在本地永远测不出来。**

**形态**：`release` 开了 `isMinifyEnabled = true` + `isShrinkResources = true`，
而 Gson 是**按字段名反射**映射的。Gson 的 AAR 里实际生效的 shrink 规则只有：

```proguard
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
```

⇒ 它只保护**带 `@SerializedName` 的字段**。而 `domain/Models.kt` 里
"字段名 == JSON 键名"的字段是按最简写法写的**裸字段**
（`val token: String`，而不是写一句同义反复的注解）—— **不在保护范围内**。

**实测（2026-10-08）**，一条命令即可复现：

```bash
./gradlew assembleRelease
awk '/domain\.LoginData -> /{f=1} f&&/^com\./&&!/LoginData/{exit} f' \
  app/build/outputs/mapping/release/mapping.txt
```

| 观测点 | 修复前 | 修复后 |
|---|---|---|
| `LoginData` 在 mapping 里的**字段条目** | 4 个（`expiresIn` `clientEnd` `tenantId` `staffId`） | **0 个**（全部保留原名 ⇒ 不出现在 mapping 里） |
| `token` / `role` 在 `usage.txt` 里 | 列为**已移除** | 不再出现 |
| `dexdump` 看 DEX 里该类的 `Instance fields` | **4 个**（`a` `b` `c` `d`） | **6 个**（`token` `role` `expiresIn` `clientEnd` `tenantId` `staffId`） |

⇒ `IdentityApi.authLogin()` 的 `data.token.isBlank()` 必然失败
⇒ **A1 登录在 release 包上根本走不通**。

> 💡 R8 之所以敢删：`LoginData` **没有任何代码调用它的构造函数**
> （实例只由 Gson 反射创建）。R8 看不到反射 ⇒ 判定这些字段"从未被写入"
> ⇒ 按常量折叠处理。这不是 R8 的 bug，是"反射对静态分析不可见"的必然结果。

**为什么它能在本地活下来**（三条防线同时失守）：

- `assembleRelease` **BUILD SUCCESSFUL**，构建日志全绿；
- **debug 包完全正常**（`isMinifyEnabled = false`）⇒ 开发全程测不出来；
- 结构门禁判的是**源码形态**，看不见 R8 之后的世界。

⇒ 典型结局：开发全程正常，打完正式包上架，一线反馈"登录没反应"。

**修复** —— `proguard-rules.pro` 改用包级规则：

```proguard
-keepclassmembers class com.diaoyuanyun.therapist.domain.** {
    <fields>;
}
```

**为什么是包级，而不是给每个裸字段补 `@SerializedName`**：包级是
**构造性安全** —— 以后新增的 DTO 自动被覆盖，不依赖"下一个人记得写注解"；
补注解的做法漏一个字段就重开一个缝，而那个缝**不会报错**。

**代价（实测，且刻意控制变量）**：只开关这一条规则各构建一次 ——
禁用 **2,103,380** → 启用 **2,143,892** 字节，净 **+40 KB（+1.9%）**。

> ⚠️ 账要记清：本轮同时新增的 15 个图标位图带来的是 **+144 KB**。
> 若不控制变量，很容易把这两笔混在一起记成"+183 KB 是 keep 的代价"。

**并且被门禁钉住了**：新增判据 `gson-reflect-surface` —— 它**从调用点现算**
Gson 反射面（`type = X::class.java` / `element = X::class.java` /
`fromJson(_, X::class.java)`），再逐个核对是否落在某条 keep 规则的覆盖内。
**新增 DTO 若落到 `domain` 之外的包，当场报红**，而不是等它上正式包丢字段。
回归注入 **I33**（把覆盖包名写错 domain → model）/ **I34**（加一条指向
不存在类的 keep 规则）证明它有牙齿。

**顺带清掉一条死规则**：本文件原本有

```proguard
-keep class com.diaoyuanyun.therapist.api.ApiEnvelope { *; }
```

而 `ApiEnvelope` **全仓不存在**（含生成物，grep 零命中）。R8 对"keep 一个
不存在的类"**静默忽略、不报错**。它的注释写着"信封与请求体 DTO 的字段名必须
保留"，实际保护了**零个类** —— 而真正会被抹掉的 18 个 DTO 一个都不在射程内。
这是本仓"**写下的检查 ≠ 存在的检查**"最典型的一例：一条语法正确、位置合理、
注释充分的规则，可以完全不起作用，且没有任何机制会告诉你。
现已由 `gson-reflect-surface` 的第 ⑤ 项（keep 里的具体类名必须真实存在）钉住。

### ✅ 已收口（留痕）：协议字段名与响应 DTO ↔ 契约 schema

**两件事一起收的，因为它们是同一个病的两面：`Models.kt` 与出站层都是契约的"第二份手抄体"。**

**① 协议字段名改走生成物具名常量。** 生成器现在为契约 `x-api-protocol.envelope-fields`
的每个字段产出 `ENVELOPE_FIELD_CODE` / `_MESSAGE` / `_DATA` / `_TRACE_ID`，
并为 `pagination.response-fields` 首位产出 `ITEMS_FIELD`；`ApiClient` 全部改为引用它们：
之前它手写 `obj.get("code")` / `get("data")` / `get("items")` 五处字面量，而生成物里
`Protocol.ENVELOPE_FIELDS` **零引用**（典型的"**死权威**"：权威声明在生成物里躺着，
取用点是手抄的第二份）。

**② 新增判据 `contract-schema-fields`**：把 `domain/Models.kt` 的 15 个响应 DTO
与契约 `components.schemas` 的字段集合判成**双向等式**（不是"DTO ⊆ 契约"——
那只盖住一半：契约新增字段而 DTO 不接收，同样是静默丢字段）。
判据的两侧数据源**独立解析后对拍**：端点 → schema 取自**生成物** `Endpoint.dataSchema`
（本轮新增的生成物字段），schema 的 properties 取自**契约真源** `_cut`。
实测规模：`components.schemas` 25 个（23 个有 `properties`）、**14 个端点声明了 `data` 形状**、
15 个 DTO 命中同名 schema、**当前漂移 = 0**。

> **为什么原来的 `envelope-field-literals` 不够（它不是"忘了写"，是形态选错了）**
>
> 它原来判的是等式：「`ApiClient` 里出现的信封字段名字面量集合 === `ENVELOPE_FIELDS`」。
> 契约把 `data` 改名后：生成物的列表变成 `[..., payload, ...]`，而代码里的字面量
> 仍是 `"data"` —— **两侧同时"少一个 data"**，等式**依旧成立**。
> 判据的参照物与被判对象**同源、一起动**，于是永远抓不到漂移（第 52 条的一种隐蔽形态）。
> ⇒ 唯一稳的形态是让取用点**引用常量**：契约改名 ⇒ 常量跟着变 ⇒ 取用点自动正确。
> 命名与形态随之改为 `protocol-fields-wired`。
>
> **并且它原来那条"推迟升级"的理由是错的**：原注释写"要同时改 JS / TS / Kotlin
> 三个发射器并重生成四个 target 的产物"，实测（2026-10-08）三个发射器各自独立 ——
> 只改 Kotlin 那一段、重跑生成器后，**另外三端产物逐字节不变**（`git status` 零改动）。
> ⇒ 教训：**一个"以后再做"的理由也要接受一次核实**。理由若只是"当时看着麻烦"，
> 它会把一个 20 行的改动挂很久，而判据一直停在弱形态。

**顺带一个第 53 条的回报**：判据 ⑩ `no-protocol-literal` 是**从生成物现算**所有
`const val X: String = "..."` 的，所以新增的 5 个具名常量**自动进入它的零字面量射程**
（它 watched 的协议字面量：**8 个 → 13 个**）—— 生成物里加一个协议常量，
保护范围自己长大，不需要改判据。

**回归注入**：**I35**（分页容器键退回字面量）/ **I36**（改掉生成物常量的**值**，
证明判据在判"值一致"而不只是"存在"）/ **I37**（`@SerializedName` 抄错一个字母，
复现最真实的一类手写漂移）/ **I38**（DTO 少接收契约字段，证明等式是**双向**的）/
**I39**（把 `dataSchema` 指向另一个**存在**的模型，证明"端点 ↔ 模型"的对应在判）。
**I15 被重写**：它的原锚点是"字面量"，生成器改产常量后锚点已不存在 ⇒
**注入脚本与判据是耦合的，判据换形态必须同步改注入**。

**射程如实登记**：**请求体键名不在射程内** —— 契约声明了 7 个请求体 schema，
而本端请求体是手写的 `linkedMapOf("键" to 值)`（25 个构造点）。实测构造点与 operation
之间**没有可静态追踪的映射**，硬做会产出**假红**判据（假红判据会被删，第 55 条）。
⇒ 已登记在第七节，不硬凑。

### ✅ 已收口（留痕）：边到边与窗口 inset（targetSdk 36 ⇒ 平台强制）

**这是又一个"构建全绿、调试全好、上线才炸"的缺陷 —— 而且它比前几个更彻底：
平台把老做法直接禁用了。**

**缺陷**：本端 `targetSdk = 36`，而全仓**零** `WindowInsets` 处理、唯一的系统栏
配置是主题里的 `android:statusBarColor`。平台行为变更（apps targeting API 35+）原文：

> · 「Apps are edge-to-edge by default on devices running Android 15 if the app is
>   targeting Android 15.」
> · 「If your app is not already edge-to-edge, portions of your app may be
>   obscured and **you must handle insets**.」
> · 「The top offset is disabled so content draws behind the status bar **unless
>   insets are applied**.」（导航栏同款表述）
> · 且 `R.attr#statusBarColor` / `Window#setStatusBarColor` /
>   `Window#setDecorFitsSystemWindows` 都在该页的
>   **「deprecated and disabled」（废弃且已禁用）**清单里。

⇒ 后果：**顶栏被状态栏压住、底部内容被导航栏压住**，而 `assembleDebug` /
`assembleRelease` / 当时全部 24 条判据**一律是绿的** —— 边到边是**运行期**窗口
行为，构建期零告警；联调又常在旧系统镜像上做。
这与 R8 抹掉 Gson 裸字段是同一族：**编译、构建、调试都指望不上**。

**修复（一个落点，四处改动）**：

| 位置 | 改动 |
|---|---|
| `ui/SystemBars.kt`（**新增，唯一落点**） | `enableEdgeToEdge()` + 把 `systemBars` / `displayCutout` / `ime` 三类 inset 落到根视图 padding |
| `MainActivity.onCreate` | 在 `setContentView(root)` **之前**调用 `SystemBars.setUp(this, root)` |
| `res/values/themes.xml` | **删掉** `android:statusBarColor` / `android:navigationBarColor` / `android:windowLightNavigationBar`（在 35+ 上已失效，留着会造成"主题在管系统栏"的错觉） |
| `app/build.gradle.kts` | 显式声明 `androidx.activity:activity:1.8.0` |

**① 为什么显式调 `enableEdgeToEdge()`，而不是"反正 35+ 已经强制"**：
不调用时，API 35+ 强制边到边而 **API < 35 不是** —— 同一个 APK 在不同设备上走两套
窗口策略。显式调一次的代价是零，换来"所有 API 档位行为一致、同一段 inset 代码被
全量设备走到"。官方 codelab 的原话：`Call enableEdgeToEdge to make this backward compatible.`

**② 为什么不能用 `SystemBarStyle.auto`**：`auto` 按**系统深色模式**决定图标明暗，
而本端主题恒为 `Theme.Material3.Light`、背景恒为浅色 `dy_bg` ——
系统开深色模式时 `auto` 会给**浅色图标**，落在浅底上就是"时间/电量看不见"。
⇒ 必须用 `light(...)`（其实现为 `nightMode = MODE_NIGHT_NO`、`detectDarkMode = { false }`）。

**③ 两个 scrim 参数各在哪档生效 —— 反编译 activity 1.8.0 得到的结论（非推测）**：

| API 档位 | `statusBarColor` | `navigationBarColor` | 图标 flag |
|---|---|---|---|
| 23–25 | `getScrim(isDark)` = **scrim** | **`darkScrim`（恒用，不看 isDark）** | 仅 `LightStatusBars` |
| 26–27 | `getScrim` = **scrim** | `getScrim` = **scrim** | 两个都设 |
| 28 | 不覆盖 `setUp`，继承 26 | | |
| 29+ | `getScrimWithEnforcedContrast` = **scrim** | 同左 | 两个都设 |
| 35+ | setColor **已是 no-op** | setColor **已是 no-op** | 仍生效 |

⇒ `darkScrim` **只被 API 23–25 的导航栏用到**；那几档导航键恒为浅色，
若给它透明底，浅色按钮会落在本端浅色背景上**看不见**。
故本端把 `darkScrim` 设为品牌深绿 `dy_brand_dark`（经 `Palette.brandDark` 令牌，
不在 Kotlin 里写色值）。⚠️ **传错不会报任何错**，只在特定 API 档位上表现为"按钮看不见"。

**④ 输入法**：`android:windowSoftInputMode="adjustResize"` 保留，但**它不再负责
适配键盘**。平台对 `SOFT_INPUT_ADJUST_RESIZE` 的废弃说明原文：

> 「Call `Window#setDecorFitsSystemWindows(boolean)` with `false` and install an
> `OnApplyWindowInsetsListener` on your root content view that fits insets of type
> `Type#ime()`.」

即框架**不再**把内容视图适配到 inset，改由应用负责。故底部取
`max(系统栏 inset, 输入法 inset)`：键盘弹出时用输入法 inset，收起时退回导航栏 inset
—— 不会叠加两次。（本端 5 个页面共 20+ 个输入框，表单是主交互，这不是边缘场景。）

**⑤ 依赖决策**：`enableEdgeToEdge` / `SystemBarStyle` 来自 `androidx.activity`，
它**本来就是** appcompat 的传递依赖（解析版本 1.8.0，已反编译该 AAR 确认
`androidx/activity/EdgeToEdge.class` 与 `SystemBarStyle.class` 均存在）。
仍显式声明它的理由：**直接调用的 API 必须直接声明** —— 否则某天 appcompat 把它
从 `api` 改成 `implementation`（对它自己是非破坏性变更），本端会突然编译失败，
而失败原因看起来与本工程毫无关系。故本次是**零新增功能依赖**。

**验证证据（全部实跑）**：

| 观测点 | 结果 |
|---|---|
| debug DEX 反汇编 `MainActivity.onCreate` | 调用序列里 `SystemBars.setUp(...)` **在 `setContentView(View)` 之前**（字节码顺序，非源码顺序推断） |
| debug DEX 反汇编 `SystemBars` | `EdgeToEdge.enable(...)`、`SystemBarStyle$Companion.light(II)`（实参 `Ui.color(ctx, Palette.getBrandDark())`）、`Type.systemBars/displayCutout/ime` 三者齐备、`Math.max` + `View.setPadding` |
| release 构建 + R8 | BUILD SUCCESSFUL；`androidx.activity.EdgeToEdge`（20 条）与 `SystemBars`（34 条）在 mapping 中存活 |
| `aapt2 dump resources`（release APK） | `Theme.DyTherapist` 变成 14 项，**三个系统栏属性均已消失** |
| `aapt2 dump badging`（release APK） | `targetSdkVersion:'36'` |
| 依赖解析 | `META-INF/androidx.activity_activity.version` = `1.8.0`（显式声明未改变解析结果） |

**并且被门禁钉住了**：新增判据 **`edge-to-edge-insets`** —— 判据形状刻意全部**现算**：
`targetSdk` 从 `build.gradle.kts` 现算（不写死 35）；"边到边落点文件" = 源码里含
`enableEdgeToEdge(` 的文件（不写死路径与文件名）；落点里声明的类型名再从源码提取，
用来核对 Activity 的 `onCreate` 是否**接线**且**早于 `setContentView`**。
⇒ 落点改名、挪到别的文件、或改成内联调用，判据都跟着走，**不会因为"名字对不上"
而变成一条永远绿的僵尸判据**（第 53 条）。

**回归注入**：**I40**（删掉接线一行）/ **I41**（把启用挪到 `setContentView` 之后，
验证顺序判据有牙齿）/ **I42**（inset 不再消费 `ime` —— 注意 `SystemBars.kt` 的
**注释里**仍写着 `Type#ime()`，判据读的是剥注释后的文本，故不被注释满足，
第 52 条）/ **I43**（往主题里塞回 `android:statusBarColor`）。

**射程如实登记**：本判据只保证**形态**。真机 / API 35+ 模拟器上的**视觉核验**
（顶栏是否真的不被压、键盘是否真的不遮输入框、API 24/25 导航栏按钮是否可读）
**仍是未收口项**，已登记在第七节。形态对 ≠ 观感对，这一点不能含糊。

### ✅ 已收口（留痕）：角色展示名收敛到契约 `x-roles.display`（第 78 条）

**缺口**：同一个角色，在**同一套系统的两个端**上显示成两个名字。

- 契约 `x-roles.<role>.display` 逐字给出 `调理师（APP）` / `经络师（APP）`
  —— 它是"角色 × 端"的**展开名**（带端后缀）。
- **本端**（原生 Android）的 `Access.roleDisplay()` 一直取自
  `Endpoints.ROLE_EXPANSION[*].display`（`x-roles` 的机械转录），不手写中文。
- **Web 端**（`therapist-app/src/contract/access.ts`）却手写了一张短形表
  `ROLE_LABEL = { therapist: '调理师', meridian: '经络师' }`。

两者**都不算错**，但属"五源对齐"的漂移。🛑 **它为什么能在门禁下活很久**：
不报错、不违约、`tsc` / `vite build` / 全部判据 / 全部反向验证**一律是绿的**
—— 这种漂移**只有把两端界面并排看才发现**，属"绿得完整但不一致"。

**收口（2026-10-08）**：Web 端改为与本端**同源的取法**，中文角色名在整个仓库里只剩契约一份。

| 落点 | 收敛前 | 收敛后 |
|---|---|---|
| `contract/access.ts` 的 `ROLE_LABEL` | 手写 `{ therapist: '调理师', meridian: '经络师' }` | `END_TOKEN_ROLES` 遍历 + `roleDisplay(r)`（键取自生成物、值取自契约 `display`） |
| 新增 `roleDisplay(role)` | 不存在 | 遍历 `ROLE_EXPANSION` → 按 **token** 匹配 → **取不到回退原码**（与端 D 语义逐条一致） |
| 4 处界面静态文案（`ServicePage` ×2 / `WorkbenchPage` ×2 / `MeridianActionsPage` ×1） | 硬写「仅经络师」「仅调理师」 | 改走 `roleDisplay('meridian')` / `roleDisplay('therapist')` —— 否则**同一个端内部**又会不一致 |

🛑 **为什么连界面文案也要改**：只改 `ROLE_LABEL` 的话，同一个端里会出现
「经络师（APP）」与「经络师」并存 —— 那是**把跨端漂移换成了端内漂移**，更糟。

**并且被门禁钉住了**：端 B 门禁新增 **`x3-role-label-from-contract`**，三条子规则**互相独立**：

1. `ROLE_LABEL` 的构造体**必须引用** `roleDisplay` / `ROLE_EXPANSION`（真的取自生成物）；
2. 构造体内**不得出现任何 CJK 字符**（手写的才是第二份权威）；
3. **覆盖面自证**：生成物里声明的每个 `ROLE_EXPANSION` 条目都必须被解析到，
   且 `END_TOKEN_ROLES` 的每个角色都必须有**非空** `display`
   —— 否则运行时会**静默回退成原码**，而所有判据都看不见它。

**回归注入**：**I15**（不再取自生成物，仍无中文 ⇒ 只触发规则 1）/
**I16**（仍取自契约但加手写中文兜底 ⇒ 只触发规则 2）/
**I17**（生成物某个 `display` 被清空 ⇒ 只触发规则 3）。
🛑 **为什么拆三组而不是一组**：合成一组会出现"三条里只有一条真在承重、
另两条是装饰"的情况 —— 那正是第 53 条要防的。

## 七、已登记的未收口项（不得当成已完成）

| 项 | 现状 | 收敛路径 |
|---|---|---|
| 加解密不可纯 JVM 单测 | `KeystoreCrypto` 用到 `android.util.Base64`（平台类），纯 JVM 测试下会抛 `Stub!` | 需要时引 Robolectric，或先把 `encode` / `decode` 抽成可替换接口 |
| **边到边的真机观感未核验** | `edge-to-edge-insets` 只判**形态**（落点存在、顺序正确、三类 inset 齐备、主题无死配置）。本机**没有 API 35+ 的模拟器 / 真机**，故"顶栏是否真的不被状态栏压住、键盘是否真的不遮输入框、API 24/25 导航栏按钮是否可读"**均未实测**。另：`SystemBars` 依赖真窗口 / `ViewRootImpl`，**无法纯 JVM 单测** | 在 API 35/36 真机（含刘海机型）与 API 24/25 老机上各截一次图；若要进 CI，需加 emulator job（本仓当前刻意未加，见第七节 CI 行） |
| 加解密是同步 API | `KeystoreCrypto` 首次访问某 alias 时有一次 `KeyStore` I/O（之后走缓存） | 若出现「每帧读会话」这类高频路径，改后台线程 + 内存快照 |
| 具名枚举手写 | `domain/ContractEnums.kt` 是手写的，但由 `named-enum-verbatim` 判据钉住与契约逐值一致 | 让 `gen-endpoints.py` 直接产出该文件 |
| **请求体键名未核对** | 契约声明了 **7 个请求体 schema**（`ScreeningCreateRequest` / `CustomerCreateRequest` / `RefundCreateRequest` / `CreateVerdictRequest` / `CreateCycleAssessmentRequest` / `BaselineAssessmentRequest` / `DailyReportRequest`），而本端请求体是手写的 `linkedMapOf("键" to 值)`（25 个构造点）。**实测（2026-10-08）：请求体构造点与 operation 之间没有可静态追踪的映射**（同文件多个构造点、map 经形参传递），硬做会产出**假红**判据 —— 而假红判据会被删（第 55 条） | 两条路：① 让生成器为每个**声明了 requestBody** 的端点产出 `requiredBody` 之外的全量 `bodySchema` 字段名，再把"构造点 → 端点"变成显式参数（改形参即可静态追踪）；② 或为这 7 个请求体产出具名 DTO 类，由 ㉑ 同型判据覆盖 |
| `Content-Type: application/json` | 出站层字面量；它**不在**契约 `x-api-protocol` 里，故未纳入判据 | 若契约将来声明它，须同步纳入 |
| R8 与反射的**其余**面 | **已逐项核对**（实测）：① 无 `res/layout/` ⇒ 页面全是代码构造，无 XML inflate 反射；② 5 个 enum 走 `proguard-android-optimize.txt` 内置的 `values()` / `valueOf()` keep 规则；③ `Map::class.java`（5 处）走 Gson 内置 MapTypeAdapter，不反射字段 | 引入 DataBinding / ViewBinding / 新序列化库 / 注解式 DI 时，须**重新逐项核对**并同步判据 |
| 消息触达（推送） | **未实现**：本端无推送依赖。一线要"及时处理客户请求"目前只能靠主动打开 APP | 接推送时，需同步补：隐私清单、`POST_NOTIFICATIONS` 权限、到达率验收口径 |
| Gradle 10 弃用警告 | 构建输出里有 `multi-string notation` 弃用警告，**来源是 AGP 8.12.0 内部**声明 `com.android.tools.lint:lint-gradle` / `com.android.tools.build:aapt2` 的方式，**不是本工程代码**（本端 6 条依赖全部是单字符串形式，已逐条核对） | 等 AGP 9.x；当前 Gradle 9.3.1 下只是警告，不影响构建 |
| CI 出包 | 门禁已接入 CI；**GitHub Actions 本身仍未跑过**（本机无法跑 Actions）。但 CI job 里的**关键是 `assembleDebug` 能否在"干净环境"里出包**，这一条已用**全新克隆**在本机模拟验证：`git clone` 到干净目录 → 端 D 门禁绿 → `./gradlew assembleDebug` **BUILD SUCCESSFUL**（2m51s / 35 tasks / **无 `local.properties`**，仅靠 `ANDROID_HOME`）→ 产出 `app-debug.apk` **7,338,658 字节**。同一轮实跑还补出 CI 的 **Python 准备步整块缺失**（见 `frontends/README.md` §8.28 / 骨架 README §5.1 第 76 条） | 首次真跑 Actions 后回填结论；若 SDK/Gradle 镜像在海外 runner 上不可达，改用 `setup-gradle` 缓存 |

## 八、纪律备忘（写代码前先读）

这几条是本工程**踩过**的，写进 README 是因为它们会以"编译报错指向别处"
的形式出现，排查成本很高：

1. **续行操作符（`+` `.` `?:` `&&` `||`）一律写在行尾。**
   其中只有 `+` 会被 Kotlin 按**一元加**解析，从而在它之前悄悄终止表达式 ——
   而错误会报在几十行之后（`Expecting '->'`）。
2. **Kotlin 字符串里必须转义 `$`**；**不得嵌中文直引号 `"`**（会截断字符串）。
   引用概念一律用 `「」`。
3. **XML 注释里不得出现 `--`**。
4. **端点层必须由生成物提供，不得手抄**；`gen-endpoints.py` 的 `cut` 与 `id`
   刻意分开（同一份契约真相源可产出多份产物）。
5. 本端行尾统一 **LF**（`.gitattributes` 已声明）。这条不是洁癖：
   裁剪契约曾是 CRLF，导致门禁里 `^  \/path:$` 这类分段正则**永不命中**，
   整份文件退化成一段 ⇒ 判据**静默假绿**。
6. **`gradlew` 的可执行位不会被 git 记录** —— 本机实测 `core.filemode=false`
   （Windows 上 git 的默认值）。文件系统上它是 `-rwxr-xr-x`，但版本库里不带这个位。
   ⇒ 在 Linux/macOS 上 clone 后先 `chmod +x gradlew`，否则报 `Permission denied`。
   CI 里这一步是**必需**的（见 `.github/workflows/build-and-test.yml` 的 android job），
   不是保险措施。
7. **不要用 UTF-8 工具去读 Gradle 的重定向日志**（这条是踩出来的）。
   Gradle 在 Windows 上按**平台控制台编码**（本机是 GBK）输出，
   `org.gradle.jvmargs=-Dfile.encoding=UTF-8` **管不到 `System.out`** ——
    JDK 17 没有 `stdout.encoding` 这个属性（那是 JDK 18 才引入的）。
   ⇒ 实测确认：日志文件按 **GBK** 解码得到正常中文，Windows 终端里的显示也是
   正常的；只有用 UTF-8 工具（`grep` 的默认行为、Python 默认 `open()`）去读才乱码。
   这不是缺陷，但**任何要解析构建日志的脚本都必须显式声明编码**。
8. **平台"强制"的行为变更，构建期一条告警都不会给。**
   `targetSdk` 一旦跨过某个版本（如 35 的边到边强制），**运行期**行为就变了：
   内容钻到系统栏下面。而 `assembleDebug`、`assembleRelease`、当时全部 24 条判据
   **一律是绿的**。⇒ 判据必须能表达"平台在这个 `targetSdk` 上要求什么"，
   且 `targetSdk` 要**现算** —— 写死成一个数字，下次升 SDK 时判据就悄悄失效了。
   另：**"已废弃"与"已禁用"是两回事**。`android:statusBarColor` 属于后者，
   留着它不只是无效，还会让下一个维护者以为"系统栏由主题控制"
   （本仓立场：**误导性的死配置比没有更坏**）。
9. **`*.bat` 是第 5 条的唯一例外，必须 CRLF。** `.gitattributes` 首行
   `* text=auto eol=lf` 会把 `gradlew.bat` 也归一成 LF，而 **cmd.exe 对只有 LF 的
   批处理在 `goto` / `:label` 跳转上有已知错行行为**（跳转落到相邻行，症状是
   "明明写了却走进别的分支"）。故文件里显式加了一条反向规则：
   `*.bat text eol=crlf`。实测 `git ls-files --eol`：`gradlew` → `i/lf w/lf`，
   `gradlew.bat` → `i/lf w/crlf`。⇒ 读第 5 条时不要顺手把 `.bat` 也"统一"掉。
10. **不要自己敲 `python .../gen-endpoints.py`。** 本端与另外三端共用同一个生成器
    入口 —— 在 `skeleton/` 下跑 `node frontends/tools/gen-endpoints.mjs`，
    或在任一端目录内 `npm run gen:endpoints`。
    `tools/py.mjs` 会解析出**带 PyYAML 的那个解释器**；而 Windows 上 `python` 与 `py`
    会落到**两个不同解释器**（实测其中一个没有 PyYAML），且 `py` 启动器还会读脚本首行
    shebang 再改一次 ⇒ **探针与真调用不同形**。直接敲会 `No module named 'yaml'`。
    同一轮实跑还发现：**CI 里整块缺 Python 准备步**（两个 job 都没有 `setup-python`），
    详见 `frontends/README.md` **§8.28** 与骨架 README §5.1 **第 77 条**。
