package com.diaoyuanyun.therapist.di

import android.content.Context
import com.diaoyuanyun.therapist.api.ApiClient
import com.diaoyuanyun.therapist.auth.Session
import com.diaoyuanyun.therapist.data.SessionStore
import com.diaoyuanyun.therapist.domain.AssessmentApi
import com.diaoyuanyun.therapist.domain.BandApi
import com.diaoyuanyun.therapist.domain.FulfillmentApi
import com.diaoyuanyun.therapist.domain.IdentityApi
import com.diaoyuanyun.therapist.domain.IntakeApi
import com.diaoyuanyun.therapist.domain.RefundApi
import com.diaoyuanyun.therapist.domain.VerdictApi

/**
 * 端 B · 依赖装配（**手工装配**，唯一一处 new）
 * ============================================================================
 *
 * 🛑 为什么不用 Hilt / Dagger / Koin
 * ---------------------------------------------------------------------------
 *   · 本端依赖图只有 10 个对象、一档生命周期（应用级单例）；
 *   · 引注解处理器会让"构建是否通过"多出一个与本任务无关的变量
 *     （KSP 版本必须与 Kotlin 版本严格配对 —— 多一条会漂移的版本约束，
 *      就多一个「以后升 Kotlin 时先炸在这里」的失败点）——
 *     那会是又一次"为了少写 20 行而引入一个不可控变量"。
 * ⇒ 手工装配。代价是这一处必须被**人工保持正确**；本类只有 10 个字段，可控。
 *
 * ⚠️ 一条已修正的旧理由（留痕在此，因为它本身是个教训）
 * ---------------------------------------------------------------------------
 * 本注释原先还写着「本机 Gradle 缓存里没有 KSP，且当前网络不通
 * `dl.google.com` / `repo1.maven.org`」。**后半句已被 2026-10-08 的复测推翻**
 * （拉真实 artifact 四家源均 200，完整记录见 `settings.gradle.kts` 的仓库顺序说明）。
 * 也就是说，那个曾经的**硬约束已经消失**，但**结论不变** ——
 * 不用 DI 框架是因为上面那条「收益 vs 代价」的判断，而不是因为「拉不到依赖」。
 * ⇒ 教训：不要让一个已经消失的外部约束，继续冒充某个设计决策的理由。
 *    当一条理由只是「当时做不到」时，条件变化后必须重新判断一次；
 *    重新判断后若仍成立，也应把理由**换成真正的那一条**。
 *
 * 🛑 本类的全部字段都是**惰性单例**，且**只在此处 new**
 * ---------------------------------------------------------------------------
 * `ApiClient` 内含 OkHttp 连接池与 Gson —— 每处调用点各 new 一个会让
 * 连接池无法复用、超时参数各自不同，而"到底是谁发的这次请求"在日志里
 * 变得无法回答（`ApiClient` 是契约语义的唯一入口，它必须是单例）。
 *
 * 🛑 `init()` 必须在 `Application.onCreate` 调用，且**只调一次**
 * ---------------------------------------------------------------------------
 * 重复调用即抛错而不是静默覆盖：静默覆盖会让"某处又 init 了一次"表现为
 * "某些页面在用另一个 SessionStore"（最典型的症状是"登录了但别的页面读不到"），
 * 而排查方向会跑到会话层去，真实原因却在装配。
 */
object Graph {

    @Volatile
    private var appContext: Context? = null

    /** 初始化（幂等：同一 Context 重复调用无副作用；换 Context 即抛）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        val existing = appContext
        if (existing === ctx) return
        if (existing != null) {
            throw IllegalStateException(
                "di: Graph 已用另一个 Application Context 初始化过。" +
                    "重复初始化意味着存在两套单例（会话 / 连接池），" +
                    "表现为「某页面登录了、另一页面读不到」——请检查是否有地方又调了 init。"
            )
        }
        appContext = ctx
    }

    private fun ctx(): Context = appContext
        ?: throw IllegalStateException("di: Graph 未初始化 —— 请确认 DyTherapistApp.onCreate 已调用 Graph.init(this)。")

    // -------------------------------------------------------------------------
    // 基础设施
    // -------------------------------------------------------------------------

    val sessionStore: SessionStore by lazy { SessionStore(ctx()) }

    val apiClient: ApiClient by lazy { ApiClient(sessionStore) }

    // -------------------------------------------------------------------------
    // 会话
    // -------------------------------------------------------------------------

    val session: Session by lazy { Session(sessionStore, identityApi) }

    // -------------------------------------------------------------------------
    // 域层（每个域一个，与契约的域划分一一对应）
    // -------------------------------------------------------------------------

    val identityApi: IdentityApi by lazy { IdentityApi(apiClient) }
    val intakeApi: IntakeApi by lazy { IntakeApi(apiClient) }
    val assessmentApi: AssessmentApi by lazy { AssessmentApi(apiClient) }
    val fulfillmentApi: FulfillmentApi by lazy { FulfillmentApi(apiClient) }
    val bandApi: BandApi by lazy { BandApi(apiClient) }
    val verdictApi: VerdictApi by lazy { VerdictApi(apiClient) }
    val refundApi: RefundApi by lazy { RefundApi(apiClient) }
}
