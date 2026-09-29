package com.diaoyuanyun.dy.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构边界守护 (ADR-03 / ADR-04): 依赖方向违规 = 测试失败。
 *
 * <h2>两类规则，两层粒度</h2>
 * <ol>
 *   <li><b>R1 模块分层</b>（既有，未削弱）：单向
 *       {@code dy-app -> {dy-security, dy-config, dy-audit, dy-web} -> dy-tenancy -> dy-common}。
 *       粒度 = <b>模块前缀</b>。</li>
 *   <li><b>R2~R4 包边界</b>（本轮扩面）：粒度 = <b>模块内部的子包</b>（controller / service /
 *       domain / repository）。R1 把 {@code app} 与 {@code config} 各自当成一个不可分的层，
 *       因此 R1 <b>结构上无法</b>发现"controller 直连 repository"（同属 app 层之外的可达关系）
 *       与"domain 反依赖 service"（同层内互依赖，layeredArchitecture 不约束层内边）。
 *       这是本轮扩面真正新增的覆盖面，不是为了凑数。</li>
 * </ol>
 *
 * <h2>为什么每条规则都配一条"非摆设"保证</h2>
 * 本项目已有先例教训：<b>恒绿或恒红的检查会被人直接关掉</b>。ArchUnit 的
 * {@code noClasses().that()...should()...} 规则在被改错包名后会<b>静默地永远通过</b>
 * （选中的类集合为空 ⇒ 没有违规可报）。故本类用两条机械保证挡住这种退化：
 * <ul>
 *   <li>{@link #every_rule_points_at_packages_that_really_exist()}：规则引用的每个包
 *       都必须在<b>真实导入的类集合</b>里有类。包名写错 / 包被改名 → 立刻红。</li>
 *   <li>{@link #injected_violations_turn_each_package_rule_red_while_production_stays_green()}：
 *       把<b>违规类现编译</b>进仓库外 {@code @TempDir}，用<b>同一批规则对象</b>重新求值，
 *       逐条断言"确实变红且报出的正是注入类"；随后在生产类集合上求值，断言零违规。
 *       这就是"规则不是恒绿摆设"的证据本身，而不是报告里的一句自述。</li>
 * </ul>
 *
* <h2>包名来自实测，不是教科书照抄</h2>
     * 本仓库实测存在的层包（{@code com.diaoyuanyun.dy} 下，见各模块 {@code src/main/java}）：
     * <pre>
     *   controller : com.diaoyuanyun.dy.app.controller            （DemoController）
     *              : com.diaoyuanyun.dy.app.scale.controller       （S1-4 题库）
     *              : com.diaoyuanyun.dy.app.band.controller        （S1-7 手环）
     *   service    : com.diaoyuanyun.dy.config.service / .audit.service
     *              : com.diaoyuanyun.dy.app.scale.service / .app.band.service
     *   domain     : com.diaoyuanyun.dy.config.domain  / .audit.domain
     *              : com.diaoyuanyun.dy.app.scale.domain / .app.band.domain
     *   repository : com.diaoyuanyun.dy.config.repository        （注意：JdbcConfigService 在 repository 包内）
     *              : com.diaoyuanyun.dy.app.scale.repository
     *   web 模块   : com.diaoyuanyun.dy.web.{exception,idempotent,trace}   （无 .controller 子包）
     * </pre>
     * <p><b>2026-09-23 扩面（S1-4 / S1-7 落码）</b>：新业务模块<b>刻意</b>建在
     * {@code app.<模块>.{controller,service,domain,repository}} 子包下，而不是摊在
     * {@code app.<模块>} 根包里。原因是本守卫的规则按层包清单匹配 ——
     * 摊在根包里的新代码<b>不在任何被登记的层里</b>，于是"控制器直连仓储"、
     * "领域模型反向依赖服务"这类捷径对新模块<b>完全失效</b>，
     * 而守卫会继续绿着，看不出它已经不管新代码了。故：<b>新模块必须登记进这四张清单</b>。
     * 这条不是目录洁癖，是让守卫真的生效。
     *
     * <p>🛑 <b>本项目不存在 {@code ..mapper..} 包</b>（未引入 MyBatis / 任何 ORM mapper）。
     * 规则 R2 仍把 {@code ..mapper..} 写进目标包列表 —— 这是<b>前向守卫</b>：一旦有人引入 mapper 层，
     * "controller 不得直连 mapper" 自动生效，无需再改测试。该规则的<b>非空性由 {@code ..repository..}
     * 保证</b>（实测存在且有类），故它不是"永远为真的规则"；这一点由上面两条保证机械验证。
 *
 * <p>ArchUnit 1.3.0 API 注记：{@code layeredArchitecture()} 返回 DependencySettings，需先调用
 * {@code consideringAllDependencies()} 得到 LayeredArchitecture；{@code layer(String)} 返回
 * LayerDefinition，其 {@code definedBy(String...)} 返回 LayeredArchitecture，可继续 {@code layer(...)}；
 * {@code whereLayer(String)} 返回 LayerDependencySpecification，其 {@code mayOnlyBeAccessedByLayers(...)}
 * 返回 LayeredArchitecture，可继续 {@code whereLayer(...)}。
 */
class ArchitectureBoundaryTest {

    // ======================================================================
    // 一、层包清单（单一真相源：规则引用它、非空性断言也引用它）
    // ======================================================================

    /** 控制器层（入站端点）。实测：dy-app 下的 app.controller + 新模块的 controller 子包。 */
    private static final List<String> CONTROLLER_LAYER = List.of(
            "com.diaoyuanyun.dy.app.controller..",
            // 2026-09-23：S1-4（题库）/S1-7（手环）的业务控制器落在各自模块的 controller 子包。
            // 它们【必须】登记在这里，否则"控制器不得直连仓储"对它俩无效 ——
            // 而"新模块不受既有守卫约束"正是最容易被漏掉的一类退让。
            "com.diaoyuanyun.dy.app.scale.controller..",
            "com.diaoyuanyun.dy.app.band.controller..",
            // 2026-09-24：S1-5（派生结果 E4）控制器 —— 同一条理由。
            "com.diaoyuanyun.dy.app.derived.controller..",
            // 2026-09-25：S2-4（契约域 G 五端点 G1~G5）控制器 —— 同一条理由。
            // 🛑 不登记则 R2（控制器不得直连仓储）对退款域失效：
            //    RefundWorkOrderController 有 5 个端点、4 个写路径，
            //    一旦有人为了"少一层"直接注入仓储，守卫会继续绿着。
            "com.diaoyuanyun.dy.app.refund.controller..",
            // 2026-09-25：S2-9（契约域 A）身份/组织域控制器 —— 同一条理由。
            // 🛑 不登记则 R2（控制器不得直连仓储）对 identity 域失效：
            //    StoreListController 是 A3 的门店列表端点，它要读 store 表 ——
            //    "直接注入 StoreRepository 少一层"在语法上完全可行，
            //    而那正是 R2 存在的意义（控制器必须经服务层，服务层才是
            //    "行级范围 + 可调用角色"两处判定的落点）。
            "com.diaoyuanyun.dy.app.identity.controller..",
            // 2026-09-26：S2-10（契约域 B 六端点 B1~B6）客户档案域控制器 —— 同一条理由。
            // 🛑 不登记则 R2（控制器不得直连仓储）对 customer 域失效：
            //    CustomerController 的六个端点里 B4（详情）/ B5（体测档案读取）
            //    都要读 customer / consent / intake_profile 三张表 ——
            //    "直接注入 CustomerLedger 少一层"在语法上完全可行，
            //    而那正是 R2 存在的意义（服务层才是"可调用角色 + 字段级可见性"
            //    两处判定唯一的落点，控制器不得绕过它去直接取数）。
            "com.diaoyuanyun.dy.app.customer.controller..",
            // 2026-09-26：S3-1（契约域 C1/C2/C3）评估域控制器 —— 同一条理由。
            // 🛑 不登记则 R2（控制器不得直连仓储）对 assessment 域失效：
            //    AssessmentController 的 C2（基线评估写）/ C3（详情读）
            //    都要触达 baseline_assessment / scale / staff 三张表 ——
            //    "直接注入 AssessmentLedger 少一层"在语法上完全可行，
            //    而那正是 R2 存在的意义（服务层才是"业务校验 + migratable 四要素推导"
            //    唯一的落点，控制器不得绕过它去直接取数/写数）。
            "com.diaoyuanyun.dy.app.assessment.controller..",
            // 2026-09-26：S3-2（契约域 D 履约域）控制器 —— 同一条理由。
            "com.diaoyuanyun.dy.app.fulfillment.controller..",
            // 2026-09-26：S3-4（契约域 I 文书模板）控制器 —— 同一条理由。
            "com.diaoyuanyun.dy.app.doctpl.controller..",
            // 2026-09-27：批次十一（N-14 收口）跨店通兑域控制器 —— 同一条理由。
            // 🛑 不登记则 R2（控制器不得直连仓储）对 settlement 域失效。
            "com.diaoyuanyun.dy.app.settlement.controller..");
    // ⚠️ 2026-09-24 备注（S2-1）：退款域的 controller / repository 两个包【暂不登记】——
    //    它们要到 S2-3 / S2-4 才有类。本测试的 every_rule_points_at_packages_that_really_exist
    //    会拦下"登记了但一个类都没有"的包模式（该守卫起了作用：它把"登记过早"
    //    从一次静默的规则恒绿，变成一次可读的构建失败）。
    //    ✅ 2026-09-25：S2-3 落 repository、S2-4 落 controller，两处均已登记。

    /** 服务层。实测：config / audit 两个既有模块 + app 下新模块的 service 子包。 */
    private static final List<String> SERVICE_LAYER = List.of(
            "com.diaoyuanyun.dy.config.service..",
            "com.diaoyuanyun.dy.audit.service..",
            "com.diaoyuanyun.dy.app.scale.service..",
            "com.diaoyuanyun.dy.app.band.service..",
            "com.diaoyuanyun.dy.app.derived.service..",
            // 2026-09-24：S2-1 退款域服务层（配置来源 = 七段口径的过渡读取实现）
            "com.diaoyuanyun.dy.app.refund.service..",
            // 2026-09-25：S2-9（契约域 A）身份/组织域服务层 ——
            // AuthMeService（A2 档位解算 + 出站裁剪）/ StoreListService（A3 行级过滤 + 分页）
            // / ConfigSeedBandProfileSource（config #43 的过渡读取实现）。
            // 🛑 它必须登记：R3 是"服务与领域层不得依赖 web"、R4 是"领域模型不得依赖服务层"。
            //    不登记则 A2 的服务层可以随手 import 一个 Controller 或 web 模块的类型，
            //    而"新模块不受既有守卫约束"正是最容易被漏掉的一类退让。
            "com.diaoyuanyun.dy.app.identity.service..",
            // 2026-09-26：S2-10（契约域 B）客户档案域服务层 ——
            // CustomerService（B1~B6 六端点唯一业务落点）/ ConfigSeedContraindicationSource
            // （config #8 禁忌症清单的过渡读取实现）。
            // 🛑 它必须登记：R3 是"服务与领域层不得依赖 web"、R4 是"领域模型不得依赖服务层"。
            //    不登记则 B1 的服务层可以随手 import 一个 Controller 或 web 模块的类型，
            //    而"新模块不受既有守卫约束"正是最容易被漏掉的一类退让。
            "com.diaoyuanyun.dy.app.customer.service..",
            // 2026-09-26：S3-1（契约域 C）评估域服务层 —— 同一条理由。
            "com.diaoyuanyun.dy.app.assessment.service..",
            // 2026-09-26：S3-2（契约域 D）履约域服务层 —— 同一条理由。
            "com.diaoyuanyun.dy.app.fulfillment.service..",
            // 2026-09-26：S3-4（契约域 I 文书模板）服务层。
            "com.diaoyuanyun.dy.app.doctpl.service..",
            // 2026-09-27：批次十一（N-14 收口）跨店通兑域服务层 ——
            // ConfigSeedCrossStoreProfileSource（config #21/#22 的过渡读取实现）。
            // 🛑 它必须登记：R3 是"服务与领域层不得依赖 web"、R4 是"领域模型不得依赖服务层"。
            "com.diaoyuanyun.dy.app.settlement.service..");

    /** 领域模型层（实体 / DO / record 的家）。 */
    private static final List<String> DOMAIN_LAYER = List.of(
            "com.diaoyuanyun.dy.config.domain..",
            "com.diaoyuanyun.dy.audit.domain..",
            "com.diaoyuanyun.dy.app.scale.domain..",
            "com.diaoyuanyun.dy.app.band.domain..",
            "com.diaoyuanyun.dy.app.derived.domain..",
            // 2026-09-24：S2-1 退款域领域层（枚举 / 口径 record / 可见性矩阵）。
            // 🛑 该层内含 RefundPolicy / RefundVisibilityMatrix 两个"带断言的 record"——
            // 它们的构造期即做合规锁校验，故【必须】留在 domain（不得依赖 service）。
            "com.diaoyuanyun.dy.app.refund.domain..",
            // 2026-09-25：S2-9（契约域 A）身份/组织域领域层 ——
            // 🛑 与 RefundPolicy / RefundVisibilityMatrix 完全同型：本层含
            //    BandVisibilityMatrix / BandFieldVisibility 两个"带断言的 record"，
            //    它们的构造期即做客户侧 ③④ 硬锁校验（客户对缺口原因与派生结果恒不可见）。
            //    故【必须】留在 domain —— R4 断言"领域模型不得依赖服务层"，
            //    若把它们放进 service 包，这条合规锁就落在了 R4 管不到的地方。
            "com.diaoyuanyun.dy.app.identity.domain..",
            // 2026-09-26：S2-10（契约域 B）客户档案域领域层 ——
            // 🛑 与 RefundPolicy / RefundVisibilityMatrix / BandVisibilityMatrix 完全同型：
            //    本层含 CustomerFieldVisibility（"带断言的 record"）与
            //    CustomerGateGuard（门禁 G1/G2 的唯一落点）、ContraindicationPolicy
            //    （禁忌命中判定），它们的构造期/调用期即做合规锁校验
            //    （客户不下发 owner_store_id / serving_store_id；未 PROFILED 不得签同意书）。
            //    故【必须】留在 domain —— R4 断言"领域模型不得依赖服务层"，
            //    若把它们放进 service 包，这些合规锁就落在了 R4 管不到的地方。
            "com.diaoyuanyun.dy.app.customer.domain..",
            // 2026-09-26：S3-1（契约域 C）评估域领域层 —— 同一条理由。
            "com.diaoyuanyun.dy.app.assessment.domain..",
            // 2026-09-26：S3-2（契约域 D）履约域领域层 —— 同一条理由。
            "com.diaoyuanyun.dy.app.fulfillment.domain..",
            // 2026-09-26：S3-4（契约域 I 文书模板）领域层。
            "com.diaoyuanyun.dy.app.doctpl.domain..",
            // 2026-09-27：批次十一（N-14 收口）跨店通兑域领域层 ——
            // 🛑 与 RefundPolicy / BandVisibilityMatrix 同型：本层含
            //    CrossStoreThresholds（"带断言的 record"，构造期即做范围与单调性校验）
            //    与 CrossStoreProfileSource（端口）。故【必须】留在 domain ——
            //    R4 断言"领域模型不得依赖服务层"，若把它放进 service 包，
            //    这条口径校验就落在了 R4 管不到的地方。
            "com.diaoyuanyun.dy.app.settlement.domain..");

    /** 数据访问层（仓储 / 持久化实现）。 */
    private static final List<String> REPOSITORY_LAYER = List.of(
            "com.diaoyuanyun.dy.config.repository..",
            "com.diaoyuanyun.dy.app.scale.repository..",
            // 2026-09-24：S2-3 退款域留痕仓储（回执三态 / 转线下告知，append-only）。
            // 它与 S2-1 的 domain / service 同属退款域，但**必须单独登记在 repository 层** ——
            // 不登记则 R2（控制器不得直连仓储）对退款域失效，
            // 而"新模块不受既有守卫约束"正是最容易被漏掉的一类退让。
            "com.diaoyuanyun.dy.app.refund.repository..",
            // 2026-09-25：判定链仓储（cycle_assessment + verdict，append-only）。
            // 🛑 与退款域同一条理由：derived 的 domain / service / controller 此前已登记，
            //    唯独 repository 没有 —— 因为到本次才有类。
            //    不登记则 R2 对判定链失效，而 VerdictController 恰恰是一个
            //    "直连仓储在语法上完全可行"的位置（它要读判定历史）。
            "com.diaoyuanyun.dy.app.derived.repository..",
            // 2026-09-25：S2-9（契约域 A）门店台账仓储（store / staff 的组织锚点读取）。
            // 🛑 与退款域 / 判定链同一条理由：identity 的 controller / service / domain
            //    都在上面登记了，repository 也【必须】登记 ——
            //    漏了它则 R2（控制器不得直连仓储）对 identity 域失效，
            //    而 StoreListController 恰恰是"直连仓储在语法上完全可行"的位置。
            "com.diaoyuanyun.dy.app.identity.repository..",
            // 2026-09-26：S2-10（契约域 B）客户档案域六本账本 ——
            // ScreeningLedger / ConsentLedger / CustomerLedger / IntakeProfileLedger
            // / IntakeProfileRevisionLedger（+ Json 行映射工具）。
            // 🛑 与退款域 / 判定链 / identity 同一条理由：customer 的 controller / service
            //    / domain 都在上面登记了，repository 也【必须】登记 ——
            //    漏了它则 R2（控制器不得直连仓储）对 customer 域失效，
            //    而 CustomerController 的 B4/B5 恰恰是"直连仓储在语法上完全可行"的位置。
            "com.diaoyuanyun.dy.app.customer.repository..",
            // 2026-09-26：S3-1（契约域 C）评估域仓储（baseline_assessment / scale / staff 访问）——
            //    同一条理由：assessment 的 controller / service / domain 都在上面登记了。
            "com.diaoyuanyun.dy.app.assessment.repository..",
            // 2026-09-26：S3-2（契约域 D）履约域仓储（visit / daily_report + 四道闸门 EXISTS）。
            "com.diaoyuanyun.dy.app.fulfillment.repository..",
            // 2026-09-26：S3-3（契约域 E）手环数据仓储（band_telemetry / band_sync_log）。
            "com.diaoyuanyun.dy.app.band.repository..",
            // 2026-09-26：S3-4（契约域 I 文书模板）仓储。
            "com.diaoyuanyun.dy.app.doctpl.repository..",
            "com.diaoyuanyun.dy.mapper..");   // 实测不存在，前向守卫

    /** 数据访问层中<b>实测存在</b>的部分（非空性守卫只看这一部分）。 */
    private static final List<String> EXISTING_REPOSITORY_LAYER = List.of(
            "com.diaoyuanyun.dy.config.repository..",
            "com.diaoyuanyun.dy.app.scale.repository..",
            "com.diaoyuanyun.dy.app.refund.repository..",
            "com.diaoyuanyun.dy.app.derived.repository..",
            "com.diaoyuanyun.dy.app.identity.repository..",
            "com.diaoyuanyun.dy.app.customer.repository..",
            "com.diaoyuanyun.dy.app.assessment.repository..",
            "com.diaoyuanyun.dy.app.fulfillment.repository..",
            "com.diaoyuanyun.dy.app.band.repository..",
            "com.diaoyuanyun.dy.app.doctpl.repository..");

    /** web 模块整体（过滤器 / 异常处理 / 幂等 / trace），即"web 层"的模块粒度。 */
    private static final List<String> WEB_MODULE_LAYER = List.of(
            "com.diaoyuanyun.dy.web..");

    /** R1 的分层顺序（自底向上）。
     *
     *  <p><b>2026-09-26（B-1 收口）新增 {@code crypto}</b>：此前本表【没有】crypto —— 当时
     *  dy-crypto 零调用方、不在 dy-app 的依赖图上，"层表漏登记"与"依赖不存在"在结果上
     *  无法区分。B-1 把 dy-crypto 挂进 dy-app 后，这个漏登记立刻变成<b>真实缺口</b>：
     *  {@code PRODUCTION_CLASSES} 用 {@code importPackages("com.diaoyuanyun.dy")} 扫描，
     *  会扫到 crypto 的类，而层表里没有 crypto ⇒ 这些类<b>存在但完全不受 R1 约束</b>
     *  （"新模块不受既有守卫约束"这一失效模式，正是本表存在的意义）。
     *
     *  <p>crypto 置于最底：它零第三方编译依赖，且刻意不依赖 dy-common / dy-tenancy
     *  （见 dy-crypto/pom.xml 的依赖纪律与根 pom 的 ADR-12 注释），
     *  故它不含任何对 dy 模块的依赖，放在 common 之下不会形成环。
     */
    private static final List<String> MODULE_LAYERS_BOTTOM_UP = List.of(
            "crypto", "common", "tenancy", "security", "web", "audit", "config", "app");

    /** 需要断言"真的有类"的全部包模式（规则引用到的每个<b>实测存在</b>的包，一个都不放过）。
     * <b>不含</b> {@link #FORWARD_GUARD_PACKAGES} —— 那组是<b>声明为当前为空</b>的前向守卫。 */
    private static final List<String> REFERENCED_PACKAGES = concat(
            CONTROLLER_LAYER, SERVICE_LAYER, DOMAIN_LAYER, EXISTING_REPOSITORY_LAYER,
            WEB_MODULE_LAYER,
            // 受"控制器必须经服务层"约束的子包，全部显式列举（不用 app.. 兜底，
            // 否则本断言会与 R1 的 app 层重合，等于没在检查"每个子包都真的存在"）
            List.of(
                    "com.diaoyuanyun.dy.app.controller..",
                    "com.diaoyuanyun.dy.app.scale.controller..",
                    "com.diaoyuanyun.dy.app.scale.service..",
                    "com.diaoyuanyun.dy.app.scale.domain..",
                    "com.diaoyuanyun.dy.app.scale.repository..",
                    "com.diaoyuanyun.dy.app.band.controller..",
                    "com.diaoyuanyun.dy.app.band.service..",
                    "com.diaoyuanyun.dy.app.band.domain..",
                    // 2026-09-24：S1-5 派生模块（domain=引擎/口径，service=配置来源，
                    // controller=E4 端点，web=出站兜底）
                    "com.diaoyuanyun.dy.app.derived.controller..",
                    "com.diaoyuanyun.dy.app.derived.service..",
                    "com.diaoyuanyun.dy.app.derived.domain..",
                    // 2026-09-25：判定链仓储落地 → 同步登记（理由同 REPOSITORY_LAYER）。
                    "com.diaoyuanyun.dy.app.derived.repository..",
                    // 2026-09-24：S2-1 退款域（domain=枚举/口径/可见性矩阵，
                    // service=配置来源过渡实现）。controller / repository 于 S2-3 / S2-4 落地，
                    // 届时同步登记（登记缺失即等于"新模块不受既有守卫约束"）。
                    "com.diaoyuanyun.dy.app.refund.domain..",
                    "com.diaoyuanyun.dy.app.refund.service..",
                    // 2026-09-24：S2-3 退款域留痕仓储落地 → 同步登记（见 REPOSITORY_LAYER 的理由）。
                    "com.diaoyuanyun.dy.app.refund.repository..",
                    // 2026-09-25：S2-4 退款域控制器（G1~G5）落地 → 同步登记。
                    "com.diaoyuanyun.dy.app.refund.controller..",
                    // 2026-09-25：S2-9（契约域 A）身份/组织域 —— 四层一次登记齐。
                    // 🛑 这是本清单存在的意义所在：任何一层漏登记，
                    //    "每个子包都真的存在"这条断言就少检查一个包，
                    //    而对应的 R2/R3/R4 对该层静默失效。
                    "com.diaoyuanyun.dy.app.identity.controller..",
                    "com.diaoyuanyun.dy.app.identity.service..",
                    "com.diaoyuanyun.dy.app.identity.domain..",
                    "com.diaoyuanyun.dy.app.identity.repository..",
                    // 2026-09-26：S2-10（契约域 B）客户档案域 —— 四层一次登记齐。
                    // 🛑 与 identity 同一条理由：任何一层漏登记，
                    //    "每个子包都真的存在"这条断言就少检查一个包，
                    //    而对应的 R2/R3/R4 对该层静默失效。
                    "com.diaoyuanyun.dy.app.customer.controller..",
                    "com.diaoyuanyun.dy.app.customer.service..",
                    "com.diaoyuanyun.dy.app.customer.domain..",
                    "com.diaoyuanyun.dy.app.customer.repository..",
                    // 2026-09-26：S3-1（契约域 C）评估域 —— 四层一次登记齐。
                    "com.diaoyuanyun.dy.app.assessment.controller..",
                    "com.diaoyuanyun.dy.app.assessment.service..",
                    "com.diaoyuanyun.dy.app.assessment.domain..",
                    "com.diaoyuanyun.dy.app.assessment.repository..",
                    // 2026-09-26：S3-2（契约域 D）履约域 —— 四层一次登记齐。
                    "com.diaoyuanyun.dy.app.fulfillment.controller..",
                    "com.diaoyuanyun.dy.app.fulfillment.service..",
                    "com.diaoyuanyun.dy.app.fulfillment.domain..",
                    "com.diaoyuanyun.dy.app.fulfillment.repository..",
                    // 2026-09-26：S3-3（契约域 E）手环数据域仓储 —— 补登记（此前 band 无 repository）。
                    "com.diaoyuanyun.dy.app.band.repository..",
                    // 2026-09-26：S3-4（契约域 I 文书模板）仓储 —— 补登记。
                    "com.diaoyuanyun.dy.app.doctpl.repository..",
                    // 2026-09-27：批次十一（N-14 收口）跨店通兑域 —— 三层一次登记齐。
                    // 🛑 与 identity / customer / assessment 同一条理由：任何一层漏登记，
                    //    "每个子包都真的存在"这条断言就少检查一个包，
                    //    而对应的 R2/R3/R4 对该层静默失效。
                    "com.diaoyuanyun.dy.app.settlement.controller..",
                    "com.diaoyuanyun.dy.app.settlement.service..",
                    "com.diaoyuanyun.dy.app.settlement.domain.."),
            MODULE_LAYERS_BOTTOM_UP.stream().map(l -> "com.diaoyuanyun.dy." + l + "..").toList());

    /** 前向守卫包：<b>当前实测为空</b>，写进规则是为了让"将来引入"自动受约束。
     *  非空性守卫对这组反过来断言 —— 它们必须<b>仍然为空</b>；一旦有人引入 mapper 层，
     *  该断言会红并提示把它从本表移出（同时 R2 自动开始生效）。 */
    private static final List<String> FORWARD_GUARD_PACKAGES = List.of(
            "com.diaoyuanyun.dy.mapper..");

    // ======================================================================
    // 二、类集合导入
    // ======================================================================

    /** 生产类集合：解析主代码类（排除测试），与既有实现保持一致。 */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.diaoyuanyun.dy");

    // ======================================================================
    // 三、规则定义
    // ======================================================================

    /**
     * R1 模块依赖方向（<b>既有规则逐字保留</b>：原 7 个层 + 6 条 whereLayer 一条未减；
     * 2026-09-26 B-1 收口<b>新增</b> crypto 层与其 1 条 whereLayer，见
     * {@link #MODULE_LAYERS_BOTTOM_UP} 的说明）。
     */
    private static ArchRule module_dependency_direction() {
        return Architectures.layeredArchitecture()
                .consideringAllDependencies()
                .layer("crypto").definedBy("com.diaoyuanyun.dy.crypto..")
                .layer("common").definedBy("com.diaoyuanyun.dy.common..")
                .layer("tenancy").definedBy("com.diaoyuanyun.dy.tenancy..")
                .layer("security").definedBy("com.diaoyuanyun.dy.security..")
                .layer("web").definedBy("com.diaoyuanyun.dy.web..")
                .layer("audit").definedBy("com.diaoyuanyun.dy.audit..")
                .layer("config").definedBy("com.diaoyuanyun.dy.config..")
                .layer("app").definedBy("com.diaoyuanyun.dy.app..")
                // crypto 是最底层：当前仅 dy-app 依赖它（BandLedger 的字段级加密）。
                // 加这条约束不是装饰 —— 它把"加密层不被上层模块的依赖图牵连"这条
                // 设计纪律（dy-crypto/pom.xml 类头）从<b>注释</b>变成<b>构建期断言</b>：
                // 一旦有人让 dy-common / dy-tenancy 去依赖 crypto，本规则立刻红。
                .whereLayer("crypto").mayOnlyBeAccessedByLayers("app")
                // common 可被所有上层依赖
                .whereLayer("common").mayOnlyBeAccessedByLayers("tenancy", "security", "web", "audit", "config", "app")
                // tenancy 仅被其上各层依赖
                .whereLayer("tenancy").mayOnlyBeAccessedByLayers("security", "web", "audit", "config", "app")
                // security/web/audit/config 仅被 app 依赖 (禁止相互反向依赖)
                .whereLayer("security").mayOnlyBeAccessedByLayers("app")
                .whereLayer("web").mayOnlyBeAccessedByLayers("app")
                .whereLayer("audit").mayOnlyBeAccessedByLayers("app")
                .whereLayer("config").mayOnlyBeAccessedByLayers("app")
                // app 不被任何层依赖
                .whereLayer("app").mayNotBeAccessedByAnyLayer();
    }

    /** R2 跳层：控制器层不得直接依赖数据访问层（必须经 service）。 */
    private static ArchRule ruleR2ControllerMustNotReachRepository() {
        return noClasses()
                .that().resideInAnyPackage(CONTROLLER_LAYER.toArray(String[]::new))
                .should().dependOnClassesThat()
                .resideInAnyPackage(REPOSITORY_LAYER.toArray(String[]::new))
                .because("跳层调用绕过服务层的事务/校验/租户上下文（ADR-03 依赖方向）");
    }

    /** R3 方向逆转：服务层 / 领域层不得依赖 web 层（含 controller）。 */
    private static ArchRule ruleR3DomainAndServiceMustNotDependOnWeb() {
        List<String> targets = concat(CONTROLLER_LAYER, WEB_MODULE_LAYER);
        return noClasses()
                .that().resideInAnyPackage(concat(SERVICE_LAYER, DOMAIN_LAYER).toArray(String[]::new))
                .should().dependOnClassesThat()
                .resideInAnyPackage(targets.toArray(String[]::new))
                .because("下层不得反向依赖入站层（ADR-03 单向依赖；层内子包粒度，R1 覆盖不到）");
    }

    /** R4 DIP 违规：领域模型（实体 / DO / record）不得依赖服务层。 */
    private static ArchRule ruleR4DomainModelMustNotDependOnService() {
        return noClasses()
                .that().resideInAnyPackage(DOMAIN_LAYER.toArray(String[]::new))
                .or().areRecords()
                .should().dependOnClassesThat()
                .resideInAnyPackage(SERVICE_LAYER.toArray(String[]::new))
                .because("领域模型必须是无依赖的值对象/实体；反向依赖服务层使模型不可独立测试（DIP）");
    }

    /** 全部规则，按稳定顺序登记（键 = 报告里使用的规则名）。 */
    private static Map<String, ArchRule> allRules() {
        Map<String, ArchRule> rules = new LinkedHashMap<>();
        rules.put("R1-module-layering", module_dependency_direction());
        rules.put("R2-controller-must-not-reach-repository", ruleR2ControllerMustNotReachRepository());
        rules.put("R3-domain-service-must-not-depend-on-web", ruleR3DomainAndServiceMustNotDependOnWeb());
        rules.put("R4-domain-model-must-not-depend-on-service", ruleR4DomainModelMustNotDependOnService());
        return rules;
    }

    // ======================================================================
    // 四、测试
    // ======================================================================

    @Test
    void module_dependency_direction_is_enforced() {
        module_dependency_direction().check(PRODUCTION_CLASSES);
    }

    @Test
    void controller_layer_must_not_depend_on_repository_layer() {
        ruleR2ControllerMustNotReachRepository().check(PRODUCTION_CLASSES);
    }

    @Test
    void domain_and_service_layers_must_not_depend_on_web_layer() {
        ruleR3DomainAndServiceMustNotDependOnWeb().check(PRODUCTION_CLASSES);
    }

    @Test
    void domain_model_types_must_not_depend_on_service_layer() {
        ruleR4DomainModelMustNotDependOnService().check(PRODUCTION_CLASSES);
    }

    /**
     * 非空性守卫：规则引用的每个包在真实类集合里都必须有类。
     *
     * <p>它堵的是 ArchUnit 最阴的一种退化 —— <b>包名打错后规则静默恒绿</b>。
     * 例如把 {@code com.diaoyuanyun.dy.config.repository..} 打成 {@code ...repos..}，
     * 规则不会报错，只会永远通过；这条断言会立刻把那种改动变成红色。
     *
     * <p>同时断言"层与层之间确实有可违规的路径"：至少要能选出一个 controller 类，
     * 否则 R2 的 {@code that(...)} 侧为空，同样退化成摆设。
     */
    @Test
    void every_rule_points_at_packages_that_really_exist() {
        List<String> missing = new ArrayList<>();
        for (String pattern : REFERENCED_PACKAGES) {
            long hits = PRODUCTION_CLASSES.stream()
                    .filter(c -> matchesPackagePattern(c.getPackageName(), pattern))
                    .count();
            if (hits == 0) {
                missing.add(pattern + " (0 个类)");
            }
        }
        assertTrue(missing.isEmpty(),
                "以下包模式在真实类集合里一个类都匹配不到 —— 规则会静默恒绿，必须修正包名或登记豁免:\n  - "
                        + String.join("\n  - ", missing));

        // 前向守卫包必须仍然为空：一旦有人引入 mapper 层，本断言红，
        // 提示把它从 FORWARD_GUARD_PACKAGES 移到 REFERENCED_PACKAGES（R2 同时自动开始生效）。
        List<String> alreadyPopulated = FORWARD_GUARD_PACKAGES.stream()
                .filter(pattern -> countClassesMatching(List.of(pattern)) > 0)
                .toList();
        assertTrue(alreadyPopulated.isEmpty(),
                "前向守卫包已被填充，必须登记进 REFERENCED_PACKAGES: " + alreadyPopulated);

        // 主体侧必须非空：R2 若无 controller 类，等于没有规则。
        assertTrue(countClassesIn(CONTROLLER_LAYER) > 0, "控制器层必须至少有一个类，否则 R2 无主体");
        assertTrue(countClassesIn(SERVICE_LAYER) > 0, "服务层必须至少有一个类，否则 R3/R4 无主体或目标");
        assertTrue(countClassesIn(DOMAIN_LAYER) > 0, "领域层必须至少有一个类，否则 R4 无主体");
        assertTrue(countClassesIn(EXISTING_REPOSITORY_LAYER) > 0,
                "数据访问层必须至少有一个类，否则 R2 无目标（规则退化为恒绿）");

        // 逐层点名（新模块各层都必须真的有类）：这不是重复检查 ——
        // "某新模块的 service 包写了但里面是空的"会让 R3/R4 在该模块上退化为恒绿，
        // 而上面按【合并清单】判非空是看不出来的。
        for (String pkg : List.of(
                "com.diaoyuanyun.dy.app.scale.controller..",
                "com.diaoyuanyun.dy.app.scale.service..",
                "com.diaoyuanyun.dy.app.scale.domain..",
                "com.diaoyuanyun.dy.app.scale.repository..",
                "com.diaoyuanyun.dy.app.band.controller..",
                "com.diaoyuanyun.dy.app.band.service..",
                "com.diaoyuanyun.dy.app.band.domain..",
                "com.diaoyuanyun.dy.app.derived.controller..",
                "com.diaoyuanyun.dy.app.derived.service..",
                "com.diaoyuanyun.dy.app.derived.domain..")) {
            assertTrue(countClassesMatching(List.of(pkg)) > 0,
                    "新模块层包 " + pkg + " 里一个类都没有 —— 该层在守卫中被登记了却是空的，"
                            + "对应规则在此模块上恒绿（等于没管）。请删掉空层登记或补上类。");
        }
    }

    /**
     * <b>反向验证（本轮 DoD 第 3 条的机械保证）</b>：注入违规类 → 规则必须变红；
     * 生产类集合上 → 必须绿。
     *
     * <h2>为什么用"现编译进 {@code @TempDir}"而不是往仓库里塞一个违规类</h2>
     * 往 {@code src/test} 里塞违规类再删掉，一旦中途失败就会留下<b>永久幻影违规</b>
     * （构建从此恒红，且看不出是谁留下的）。{@code @TempDir} 由 JUnit 自动创建、
     * <b>自动清理</b>，且落在仓库<b>之外</b>的系统临时目录 —— 本测试无论成功、失败、
     * 还是被 Ctrl-C，仓库里都不会有残留文件。
     *
     * <h2>注入的四个类是自足的（不依赖任何外部 classpath 编译）</h2>
     * 违规类只引用<b>同批注入的</b>占位类，故 {@code javac} 不需要任何 classpath 参数，
     * 不会因为"target/classes 还是 ~/.m2 里的 jar"这种环境差异而假红/假绿。
     * 每个类只触发<b>一条</b>规则，形成 1:1 的因果证据：
     * <pre>
     *   ReverseProbeRepository (config.repository)  ← 占位目标，自身无依赖
     *   ReverseProbeController (app.controller)     → repository      ⇒ 只触发 R2
     *   ReverseProbeService    (config.service)     → controller      ⇒ 只触发 R3
     *   ReverseProbeEntity     (config.domain, record) → service      ⇒ 只触发 R4
     *   （R1 因 config.* → app.* 反向依赖同时变红，一并验证）
     * </pre>
     *
     * <p><b>关于"为什么红态只在注入类上求值"</b>：绿态已在生产类集合上断言过，
     * 红态用<b>同一批 {@code ArchRule} 对象</b>在注入集合上求值 —— 证明的是"规则的条件
     * 能识别出该违规形态"，这正是反证"恒绿摆设"所需的命题。两态都不掺入对方的类集合，
     * 因果关系因此是干净的。
     */
    @Test
    void injected_violations_turn_each_package_rule_red_while_production_stays_green(@TempDir Path probeDir)
            throws IOException {
        Path srcDir = probeDir.resolve("src");
        Path outDir = probeDir.resolve("out");
        writeInjectedSources(srcDir);
        compileWithJavac(srcDir, outDir);

        JavaClasses injected = new ClassFileImporter().importPath(outDir);
        assertTrue(injected.size() >= 4, "注入类必须被 ArchUnit 真正导入，实际导入 " + injected.size() + " 个");

        Map<String, ArchRule> rules = allRules();

        // ---- 红态：每条规则都必须变红，且报出的正是注入的违规类 ----
        Map<String, String> redEvidence = new LinkedHashMap<>();
        for (Map.Entry<String, ArchRule> e : rules.entrySet()) {
            EvaluationResult result = e.getValue().evaluate(injected);
            assertTrue(result.hasViolation(),
                    "规则 " + e.getKey() + " 在注入违规类之后<b>没有变红</b> —— 该规则是恒绿摆设");
            String details = String.join(" | ", result.getFailureReport().getDetails());
            redEvidence.put(e.getKey(), details);
        }
        // 逐条核对"是谁触发的"，避免"红了但不是因为我要证明的那条边"
        assertTrue(redEvidence.get("R2-controller-must-not-reach-repository").contains("ReverseProbeController"),
                "R2 的违规主体必须是 ReverseProbeController，实际: "
                        + redEvidence.get("R2-controller-must-not-reach-repository"));
        assertTrue(redEvidence.get("R3-domain-service-must-not-depend-on-web").contains("ReverseProbeService"),
                "R3 的违规主体必须是 ReverseProbeService，实际: "
                        + redEvidence.get("R3-domain-service-must-not-depend-on-web"));
        assertTrue(redEvidence.get("R4-domain-model-must-not-depend-on-service").contains("ReverseProbeEntity"),
                "R4 的违规主体必须是 ReverseProbeEntity，实际: "
                        + redEvidence.get("R4-domain-model-must-not-depend-on-service"));

        // 留档：这两行是报告里红态证据的来源（surefire 输出可见）
        redEvidence.forEach((name, details) -> System.out.println("[A9-RED] " + name + " :: " + details));

        // ---- 绿态：同一批规则对象，生产类集合上零违规 ----
        for (Map.Entry<String, ArchRule> e : rules.entrySet()) {
            EvaluationResult result = e.getValue().evaluate(PRODUCTION_CLASSES);
            assertFalse(result.hasViolation(),
                    "生产类集合上规则 " + e.getKey() + " 报出违规（这是<b>真实回归</b>，不是注入造成的）:\n"
                            + result.getFailureReport());
        }
        System.out.println("[A9-GREEN] production classes=" + PRODUCTION_CLASSES.size()
                + " rules=" + rules.size() + " violations=0");

        // 注入集合与生产集合必须确实不同，否则上面两态可能"其实是同一次求值"
        assertNotEquals(PRODUCTION_CLASSES.size(), injected.size(),
                "注入集合与生产集合规模相同，反证失去意义");

        // 临时探针纪律：证据必须落在仓库外。
        // ① 探针目录在系统临时目录内；② 生产类集合里绝不出现任何探针类名。
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertTrue(probeDir.toAbsolutePath().normalize().startsWith(tmpRoot),
                "探针目录必须在系统临时目录内（仓库外），实际: " + probeDir + " tmpRoot=" + tmpRoot);
        List<String> leakedIntoProduction = PRODUCTION_CLASSES.stream()
                .map(c -> c.getName())
                .filter(n -> n.contains("ReverseProbe"))
                .toList();
        assertTrue(leakedIntoProduction.isEmpty(),
                "生产类集合里出现了探针类 —— 仓库内有幻影残留，必须清理: " + leakedIntoProduction);
        assertFalse(Files.exists(tmpRoot.resolve("ReverseProbeRepository.java")),
                "探针源文件不得散落到临时目录根部");
    }

    // ======================================================================
    // 五、辅助
    // ======================================================================

    /**
     * <b>反向验证（新模块扩面的机械保证）</b>：把违规类注入到
     * {@code app.scale.{controller,service,domain,repository}} 四个包，
     * 断言 R2/R3/R4 对新模块<b>同样变红</b>，而生产类集合上仍然零违规。
     *
     * <h2>为什么必须单独加这一条，而不是靠上面那条覆盖</h2>
     * 上面那条注入用的是<b>老的</b>包（{@code app.controller} / {@code config.service} / …）。
     * 它证明的是"规则对这些包有效"。它<b>不能</b>证明"规则对新登记的
     * {@code app.scale.*} 有效" —— 那是另一组字符串。若有人把新模块的层登记写错
     * （例如写成 {@code app.scale.controllers..} 少个 s），R2 对新模块就静默失效，
     * 而上面那条注入测试<b>照样绿</b>。本条的注入点刻意落在新包的<b>真实</b>层包名上，
     * 把"新登记真的生效"变成可验证的事实。
     */
    @Test
    void injected_scale_module_violations_turn_red_while_production_stays_green(@TempDir Path probeDir)
            throws IOException {
        Path srcDir = probeDir.resolve("src");
        Path outDir = probeDir.resolve("out");
        writeScaleInjectedSources(srcDir);
        compileWithJavac(srcDir, outDir);

        JavaClasses injected = new ClassFileImporter().importPath(outDir);
        assertEquals(4, injected.size(), "注入类应恰为 4 个，实际 " + injected.size());

        Map<String, ArchRule> rules = allRules();

        // ---- 红态：三条包规则都必须变红，且报出的正是注入类 ----
        EvaluationResult r2 = rules.get("R2-controller-must-not-reach-repository").evaluate(injected);
        assertTrue(r2.hasViolation(),
                "R2 在新的 app.scale.controller 包上<b>没有变红</b> —— 新模块的层登记未生效（守卫对它是摆设）");
        assertTrue(String.join(" | ", r2.getFailureReport().getDetails()).contains("ScaleProbeController"),
                "R2 的违规主体必须是 ScaleProbeController，实际: " + r2.getFailureReport().getDetails());

        EvaluationResult r3 = rules.get("R3-domain-service-must-not-depend-on-web").evaluate(injected);
        assertTrue(r3.hasViolation(),
                "R3 在新的 app.scale.service 包上<b>没有变红</b> —— 新模块的层登记未生效");
        assertTrue(String.join(" | ", r3.getFailureReport().getDetails()).contains("ScaleProbeService"),
                "R3 的违规主体必须是 ScaleProbeService");

        EvaluationResult r4 = rules.get("R4-domain-model-must-not-depend-on-service").evaluate(injected);
        assertTrue(r4.hasViolation(),
                "R4 在新的 app.scale.domain 包上<b>没有变红</b> —— 新模块的层登记未生效");
        assertTrue(String.join(" | ", r4.getFailureReport().getDetails()).contains("ScaleProbeEntity"),
                "R4 的违规主体必须是 ScaleProbeEntity");

        System.out.println("[A9-SCALE-RED] R2 :: " + String.join(" | ", r2.getFailureReport().getDetails()));
        System.out.println("[A9-SCALE-RED] R3 :: " + String.join(" | ", r3.getFailureReport().getDetails()));
        System.out.println("[A9-SCALE-RED] R4 :: " + String.join(" | ", r4.getFailureReport().getDetails()));

        // ---- 绿态：同一批规则，生产类集合零违规 ----
        for (Map.Entry<String, ArchRule> e : rules.entrySet()) {
            EvaluationResult result = e.getValue().evaluate(PRODUCTION_CLASSES);
            assertFalse(result.hasViolation(),
                    "生产类集合上规则 " + e.getKey() + " 报出违规（真实回归，非注入造成）:\n"
                            + result.getFailureReport());
        }

        // 临时探针纪律：证据落在仓库外（与既有反证测试同一套要求）
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertTrue(probeDir.toAbsolutePath().normalize().startsWith(tmpRoot),
                "探针目录必须在系统临时目录内（仓库外），实际: " + probeDir);
        List<String> leaked = PRODUCTION_CLASSES.stream()
                .map(c -> c.getName())
                .filter(n -> n.contains("ScaleProbe"))
                .toList();
        assertTrue(leaked.isEmpty(), "生产类集合里出现了探针类 —— 仓库内有幻影残留: " + leaked);
    }

    /** 把违规类写到仓库外的临时目录（自足：只互相引用，不引用任何生产类）。 */
    private static void writeScaleInjectedSources(Path srcDir) throws IOException {
        write(srcDir, "com/diaoyuanyun/dy/app/scale/repository/ScaleProbeRepository.java", """
                package com.diaoyuanyun.dy.app.scale.repository;

                /** 占位目标类（注入探针）：给 R2 提供一个新模块 repository 侧目标。 */
                public class ScaleProbeRepository {
                    public String read() { return "probe"; }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/app/scale/controller/ScaleProbeController.java", """
                package com.diaoyuanyun.dy.app.scale.controller;

                import com.diaoyuanyun.dy.app.scale.repository.ScaleProbeRepository;

                /** 注入探针：新模块 controller 直连 repository = 跳层（R2 必须抓住）。 */
                public class ScaleProbeController {
                    private final ScaleProbeRepository repository = new ScaleProbeRepository();

                    public String handle() { return repository.read(); }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/app/scale/service/ScaleProbeService.java", """
                package com.diaoyuanyun.dy.app.scale.service;

                import com.diaoyuanyun.dy.app.scale.controller.ScaleProbeController;

                /** 注入探针：新模块 service 反向依赖 controller = 方向逆转（R3 必须抓住）。 */
                public class ScaleProbeService {
                    private final ScaleProbeController controller = new ScaleProbeController();

                    public String run() { return controller.handle(); }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/app/scale/domain/ScaleProbeEntity.java", """
                package com.diaoyuanyun.dy.app.scale.domain;

                import com.diaoyuanyun.dy.app.scale.service.ScaleProbeService;

                /** 注入探针：新模块领域 record 依赖 service = DIP 违规（R4 必须抓住）。 */
                public record ScaleProbeEntity(String id, ScaleProbeService service) {
                }
                """);
    }

    /** 把违规类写到仓库外的临时目录。内容刻意自足：只互相引用，不引用任何生产类。 */
    private static void writeInjectedSources(Path srcDir) throws IOException {
        write(srcDir, "com/diaoyuanyun/dy/config/repository/ReverseProbeRepository.java", """
                package com.diaoyuanyun.dy.config.repository;

                /** 占位目标类（注入探针）：自身无任何依赖，只为给 R2 提供一个 repository 侧目标。 */
                public class ReverseProbeRepository {
                    public String read() { return "probe"; }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/app/controller/ReverseProbeController.java", """
                package com.diaoyuanyun.dy.app.controller;

                import com.diaoyuanyun.dy.config.repository.ReverseProbeRepository;

                /** 注入探针：controller 直接依赖 repository = 跳层（R2 必须抓住）。 */
                public class ReverseProbeController {
                    private final ReverseProbeRepository repository = new ReverseProbeRepository();

                    public String handle() { return repository.read(); }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/config/service/ReverseProbeService.java", """
                package com.diaoyuanyun.dy.config.service;

                import com.diaoyuanyun.dy.app.controller.ReverseProbeController;

                /** 注入探针：service 反向依赖 controller = 方向逆转（R3 必须抓住）。 */
                public class ReverseProbeService {
                    private final ReverseProbeController controller = new ReverseProbeController();

                    public String run() { return controller.handle(); }
                }
                """);

        write(srcDir, "com/diaoyuanyun/dy/config/domain/ReverseProbeEntity.java", """
                package com.diaoyuanyun.dy.config.domain;

                import com.diaoyuanyun.dy.config.service.ReverseProbeService;

                /** 注入探针：领域 record 依赖 service = DIP 违规（R4 必须抓住）。 */
                public record ReverseProbeEntity(String id, ReverseProbeService service) {
                }
                """);
    }

    private static void write(Path srcDir, String relative, String content) throws IOException {
        Path file = srcDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 用 JDK 自带 javac 现编译（不依赖外部 classpath：注入类自足）。
     *
     * <p>⚠️ 必须用<b>双横线</b> {@code --release}：单横线 {@code -release} 是<span>JDK 9+ 才引入</span>
     * 但仍以单横线形式存在的选项名里<b>没有</b> {@code -release}（只有 {@code --release}），
     * 写成单横线会让 javac 以"无效的标记"退出 2，本测试随即以"编译失败"报红 —— 那是探针自身的
     * bug，不是规则的问题。错误流被捕获进异常消息，便于一眼分辨这两者。
     */
    private static void compileWithJavac(Path srcDir, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "必须运行在 JDK（而非 JRE）上，否则无法现编译注入类");
        Files.createDirectories(outDir);

        List<String> sources;
        try (Stream<Path> walk = Files.walk(srcDir)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        }
        assertEquals(4, sources.size(), "注入源文件应为 4 个，实际: " + sources);

        List<String> args = new ArrayList<>(List.of("-d", outDir.toString(), "-encoding", "UTF-8", "--release", "17"));
        args.addAll(sources);

        java.io.ByteArrayOutputStream diagnostics = new java.io.ByteArrayOutputStream();
        int exit = compiler.run(null, null, diagnostics, args.toArray(String[]::new));
        assertEquals(0, exit, "注入类编译失败（javac 退出码 " + exit + "）—— 反证测试本身失效。诊断:\n"
                + diagnostics.toString(StandardCharsets.UTF_8));
    }

    /** 统计某组包模式下匹配到的生产类数量。 */
    private static long countClassesIn(List<String> patterns) {
        return countClassesMatching(patterns);
    }

    private static long countClassesMatching(List<String> patterns) {
        return PRODUCTION_CLASSES.stream()
                .filter(c -> patterns.stream().anyMatch(p -> matchesPackagePattern(c.getPackageName(), p)))
                .count();
    }

    /**
     * 判断包名是否匹配 ArchUnit 风格的模式（本类只用 {@code prefix..} 一种形态）。
     * 刻意不复用 ArchUnit 内部实现：非空性守卫要独立于被测对象，否则又是自证。
     */
    private static boolean matchesPackagePattern(String packageName, String pattern) {
        assertTrue(pattern.endsWith(".."), "包模式必须以 '..' 结尾: " + pattern);
        String prefix = pattern.substring(0, pattern.length() - 2);
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        Set<T> merged = new LinkedHashSet<>();
        for (List<T> l : lists) {
            merged.addAll(l);
        }
        return List.copyOf(merged);
    }
}