package com.diaoyuanyun.dy.app.provisioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>组织主数据开通边界门禁</b> —— 把「哪些表有写入方、哪些没有」从
 * 「代码注释里的一句散文」升级为「构建期事实」。
 *
 * <h2>它守的是什么（实测结论，不是推测）</h2>
 * 本骨架已实现 9 个模块、41 / 45 个契约端点。四条独立实测曾指向同一点：
 * <ol>
 *   <li>契约 40 个 path 里<b>没有任何</b>租户开通 / 门店新建 / 员工新建端点；
 *       {@code /stores} 只有 {@code GET}（A3「门店列表」，只读）；</li>
 *   <li>{@code tenant} / {@code store} / {@code staff} / {@code region} / {@code device}
 *       五张组织主数据表，在生产代码（{@code src/main/java}，<b>剥离注释后</b>）
 *       <b>零 {@code INSERT INTO}</b>；</li>
 *   <li>全仓<b>无</b> {@code ApplicationRunner} / {@code CommandLineRunner} 启动钩子
 *       —— 不存在「启动时自举一份默认租户」的隐式通路；</li>
 *   <li>应用库 {@code diaoyuanyun_dev} 实测这五张表<b>均为 0 行</b>。</li>
 * </ol>
 * ⇒ 当时合起来只有一个结论：<b>把本骨架部署起来，它是个"能跑但空"的系统</b> ——
 * 登录后拿不到任何门店、任何员工、任何客户，因为让它们存在的那条路径在彼时范围内不存在。
 *
 * <h2>✅ 2026-09-27 边界移动（B-7 收口）—— 本类的状态随之变更，逐条对齐</h2>
 * 「租户开通 + 组织建档」这条链路<b>已落地</b>，形态是<b>运维通路而非 HTTP 端点</b>：
 * <ul>
 *   <li><b>V15 迁移</b>：{@code provision_tenant()} / {@code assert_tenant_context()}
 *       两个 plpgsql 原语 —— 把「插 tenant 行」与「建立 RLS 上下文」绑成一个原子函数；</li>
 *   <li><b>应用侧</b>：{@code OrganizationProvisioningRepository.provision()}（一个事务内
 *       写 tenant → 建上下文 → 写 region/store/staff → 回填督导）+
 *       {@code OrganizationProvisioningService.provision()}（同事务写哈希链审计）；</li>
 *   <li><b>为什么不给它 HTTP 端点</b>：契约无开通端点是<b>契约化决策</b> ——
 *       开通是运维/实施动作，给它对外端点等于在租户边界之外开一个「谁能创建租户」的
 *       鉴权面，而 {@code x-callable-roles} 里没有任何角色声明覆盖它。
 *       加这样一个端点属契约 <b>MAJOR</b> 变更，需产品共签。</li>
 * </ul>
 * 于是上面四条实测的现状是：
 * <ol>
 *   <li><b>仍成立</b>（这是本轮【刻意保持】的）—— 契约仍无开通端点，第 ④ 例继续守着；</li>
 *   <li><b>已变更</b> —— {@code tenant}/{@code region}/{@code store}/{@code staff} 四张
 *       <b>已有写入方</b>，已从 {@link #NOT_PROVISIONED} 移入 {@link #PROVISIONED}；
 *       {@code device} 仍在未开通账（属门店作业域，不在 B-7 范围）；</li>
 *   <li><b>仍成立</b> —— 仍无启动钩子。这是有意的：开通必须是<b>显式动作</b>，
 *       "启动时自动建一个默认租户"会让生产环境凭空多出一个无人认领的租户；</li>
 *   <li><b>不受影响</b> —— {@code diaoyuanyun_dev} 是开发库，本轮未对它执行开通；
 *       "库里是空的"与"代码里没有通路"从此是两件事，这正是本类要分开的东西。</li>
 * </ol>
 *
 * <h2>✅ 2026-09-27（批次十五）第二次边界移动（B-10 收口）—— {@code band} 移入已开通账</h2>
 * 这是本类自建立起<b>第二次</b>被合法地"改账"，且它的性质与 B-7 那次<b>不同</b>，
 * 两者的差别值得逐字写下来：
 * <ul>
 *   <li><b>B-7（组织开通）</b>移动边界，做的是<b>补一条通路</b> ——
 *       在此之前"建租户"这件事在代码里完全不存在。缺的是<b>能力</b>。</li>
 *   <li><b>B-10（手环绑定）</b>移动边界，做的是<b>修一个功能缺口</b>——
 *       "绑定"这件事的存在性从来不是问题（9 处 {@code INSERT INTO band} 一直在
 *       {@code src/test} 的夹具里），问题在于那些写入方<b>不在生产代码里</b>。</li>
 * </ul>
 * 🛑 而后者比前者更隐蔽，因为它表现为<b>"测试全绿而功能不可用"</b>：
 * 四张表的 {@code device_id} 都是 {@code NOT NULL REFERENCES band (band_id)}
 * （{@code band_telemetry} [V3 L38] / {@code band_sync_probe} [V5 L904] /
 * {@code band_sync_log} [V5 L951] / {@code band_daily_coverage} [V5 L995]），
 * 一条 {@code band} 行都没有 ⇒ 写这四张表的请求全部以 {@code 23503} 失败 ⇒
 * E1 {@code POST /band/sync-batches} 与 E2 {@code POST /band/telemetry}
 * （契约 {@code x-callable-roles: [client]}，客户端上报的入口）在生产上是<b>死的</b>。
 * 而每个手环测试类都<b>自己在夹具里建 band</b>，于是"带子从哪来"这个问题在测试里
 * 永远有一个答案、在生产里没有。
 * <p>⇒ 这正是本类存在理由的第 ① 条（「它会被误报成缺陷」）的<b>反面形态</b>：
 * 那里的风险是"空的成因"被误判成缺陷，这里的风险是"有夹具的成因"被误判成正常。
 * <p>落地件：<b>V17 迁移</b>（{@code bind_band()} / {@code unbind_band()} 两个 plpgsql
 * 原语）+ {@code BandBindingLedger}（{@code band} 表的<b>唯一</b>生产写入方）+
 * {@code BandBindingService}（同事务审计留痕）。形态与 B-7 同款：<b>运维通路而非 HTTP 端点</b> ——
 * 契约域 E（E1~E6）没有任何绑定 / 解绑端点，而 D6 {@code POST /device-dispatches} 的
 * description 反而逐字把它排除了（「本接口操作的是【门店级调理设备 device】……
 * 与【客户级手环 band】是两本台账、不得合并」）。
 * 故上面四条实测的现状随之变更：第 ② 条 —— {@code band} <b>已有写入方</b>，
 * 已从 {@link #NOT_PROVISIONED} 移入 {@link #PROVISIONED}；
 * {@code device} 仍在未开通账（属设备建档，不在 B-10 范围，见 Task「B-11」）。
 *
 * <h2>🛑 本类【不】判断"该不该有开通流程"，只把现状钉成机械事实</h2>
 * 这条边界此前已在 {@code DbTenantKekProvider#currentOrProvision} 的 Javadoc 里被如实写过
 * （逐字：「本项目当前<b>没有租户开通流程</b>（租户由运维/测试直接落 {@code tenant} 表，
 * 全仓 {@code dy-app/src/main} 与 {@code dy-config/src/main} 里没有
 * {@code INSERT INTO tenant}，也没有任何 {@code ApplicationRunner} /
 * {@code CommandLineRunner} 启动钩子）」）。
 * <b>本类把那句散文变成机械事实</b>，理由有两条：
 * <ol>
 *   <li><b>它会被误报成缺陷。</b> 任何人在真库上跑一次冒烟，都会看到"客户表是空的"，
 *       并合理地怀疑数据层坏了。把「空的成因」写成构建期断言，
 *       才能把"缺陷排查"与"范围决策"这两件事分开 —— 前者要修，后者要签字；</li>
 *   <li><b>它会静默地被打破。</b> 一次变更若在别处<b>顺手</b>加一条
 *       {@code INSERT INTO store}（例如只为让某个测试的数据好看一点），
 *       本类会立刻变红，迫使「边界被移动」成为一次<b>显式动作</b>
 *       （更新本表 + 同步 README / 卡点清单），而不是一次无人察觉的漂移。
 *       🛑 本轮（B-7）就是这条路径的<b>合法用例</b>：边界确实移动了，
 *       移动方式是"改两账 + 改类注释 + 同步文档"，而不是让门禁红着过去。</li>
 * </ol>
 *
 * <h2>判据（四条，缺一不可）</h2>
 * <ol>
 *   <li><b>全集侧机械提取</b>：表名从迁移文件里扫出来（剥 SQL 注释后），不是手抄 ——
 *       新增一张迁移表而没登记开通状态，立刻红；</li>
 *   <li><b>登记侧必须有真实写入方</b>：账本记「已开通」的表，生产代码里<b>必须</b>真的
 *       出现 {@code INSERT INTO}（否则账本在骗人）；</li>
 *   <li><b>未开通侧必须真的没写入方</b>：账本记「未开通」的表，生产代码里<b>必须</b>
 *       真的没有任何 {@code INSERT INTO} ——「归零」必须是一次显式编辑；</li>
 *   <li><b>契约侧零开通端点</b>：契约 path 集合里不得出现租户 / 门店 / 员工的写入型端点，
 *       且 {@code /stores} 段内只能有 {@code GET}。
 *       🛑 第 ④ 例与本轮的关系必须说清：组织开通通路<b>已落地</b>，但它走的是
 *       <b>数据库函数 + 无 HTTP 映射的组件</b>（运维通路），契约里<b>仍然</b>没有开通端点。
 *       故第 ④ 例继续守着，且它的红不再是"你少了东西"，而是"你越界了"——
 *       若有人把 {@code OrganizationProvisioningService} 接上 {@code @RestController}，
 *       第 ④ 例会先红。</li>
 * </ol>
 *
 * <h2>它【不】回答什么（诚实边界）</h2>
 * <ul>
 *   <li><b>不判「该不该有开通流程」</b> —— 那是产品范围的裁定，不是代码门禁的事；</li>
 *   <li><b>不判可达性</b> —— 静态扫描证明不了某处 {@code INSERT} 真的会被执行到。
 *       本类与 {@code ConfigSlotConsumptionLedgerTest} 有同一个边界：它排除了"完全没写"，
 *       但排不掉"写了却不可达"。故它承认的断言是<b>单向</b>的：
 *       「有写入方」是必要条件，不是充分条件。
 *       🛑 B-7 之后这条边界对组织主数据尤其要说清：本类只见证
 *       {@code OrganizationProvisioningRepository} 里<b>有</b> INSERT，
 *       不见证"它被谁调用"。后者由 {@code OrganizationProvisioningE2ETest} 的
 *       真库真表断言承担 —— 两道门禁各管一半，不要只看其中一道。</li>
 * </ul>
 *
 * <h2>为什么用「表名出现在 SQL 字面量里」作判据是可靠的</h2>
 * 本仓<b>无 ORM</b>（{@code pom.xml} 里无 {@code spring-boot-starter-data-jpa} /
 * {@code mybatis} / {@code hibernate}；仅 dy-web 引入 {@code spring-data-redis}），
 * 持久化 100% 走 {@code JdbcTemplate} + 静态 SQL 字符串。因此"表名是否出现在源码字面量里"
 * 就是"是否存在写入路径"的<b>完整</b>判据，不存在"框架隐式写表"的漏网。
 * 该前提本身由第 ⑤ 例机械守着 —— 一旦有人引入 ORM，门禁会先红。
 *
 * <p>本类为纯静态检查，<b>不连数据库</b>，归入 {@code dy-app} 的常规单测。
 */
@DisplayName("组织主数据开通边界：44 张迁移表 × 生产代码写入方（未开通的归零必须显式）")
class ProvisioningBoundaryGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 参与写入扫描的模块（只扫生产代码；测试里的 INSERT 是夹具，不算"系统能力"）。 */
    private static final List<String> SCANNED_MODULES = List.of(
            "dy-common", "dy-tenancy", "dy-security", "dy-web",
            "dy-audit", "dy-config", "dy-crypto", "dy-app");

    /** 迁移目录（全集侧的唯一来源）。 */
    private static final String MIGRATION_DIR = "dy-app/src/main/resources/db/migration";

    /** 契约文件相对 <b>skeleton 父目录</b> 的位置。 */
    private static final String CONTRACT_RELATIVE = "contract/openapi-v1.0.0.yaml";

    /**
     * 生产代码里<b>真实存在写入方</b>的表（40 张）。
     *
     * <p>值 = 变更来源说明，只作可读性，断言不依赖它。
     * 每一条都由第 ② 例核「确实有 {@code INSERT INTO}」。
     */
    private static final Set<String> PROVISIONED = new LinkedHashSet<>(List.of(
            // —— 由 Flyway 迁移 / seed 脚本写入（schema 与配置真相源）——
            "config_slot", "app_config", "schema_migration",
            // —— 由组织开通通路写入（B-7 收口，2026-09-27）——
            // 🛑 这四张是【本轮边界移动】的结果：它们从 NOT_PROVISIONED 账移入本账。
            //    载体有两处，各自的形态必须说清（否则下一个人会找错地方）：
            //      · tenant  ← V15 的 provision_tenant() 函数体（PL/pgSQL 内的
            //        INSERT INTO tenant）。它被本类的 SQL 扫描器看到，是因为
            //        stripSqlComments() 【保留美元引用块（$tag$ … $tag$）内的原文】——
            //        这是有意为之，V6 的触发器函数体里就写着 INSERT INTO app_config_history。
            //        🛑 应用侧【刻意不再】重复这一行：同事务内先插 tenant 再调该函数，
            //        会让函数体内的 ON CONFLICT 看到刚插入的行，从而对全新租户
            //        也恒判 ALREADY_EXISTS（实测缺陷，详见 OrganizationProvisioningRepository 类注释）。
            //      · region / store / staff  ← OrganizationProvisioningRepository.provision()
            //        在同一事务内显式 INSERT（三张表的写入方在 Java 侧，V15 不碰它们）。
            "tenant", "region", "store", "staff",
            // —— 由数据库触发器写入（配置变更历史）——
            "app_config_history",
            // —— 由业务 Ledger / 领域服务写入 ——
            "audit_log",
            "customer", "customer_state_transition", "screening_record", "consent",
            "intake_profile", "intake_profile_revision",
            "baseline_assessment", "cycle_assessment", "verdict",
            "plan", "plan_review", "device_dispatch", "visit", "daily_report",
            "refund", "refund_statement", "refund_receipt", "refund_offline_notice", "retention",
            "doc_template",
            "band_telemetry", "band_sync_log",
            "scale_item_bank",
            // —— 由手环绑定通路写入（B-10 收口，2026-09-27）——
            // 🛑 这一张是本轮【第二次边界移动】的结果：它从 NOT_PROVISIONED 账移入本账。
            //    载体 = V17 的 bind_band() 函数体（PL/pgSQL 内的 INSERT INTO band ... ON CONFLICT）。
            //    🛑 为什么它必须进这张账，而不是只"顺手加一行 INSERT"：
            //       band 是 band_telemetry / band_sync_probe / band_sync_log /
            //       band_daily_coverage 四表 device_id 的【唯一引用来源】，
            //       缺了它，E1/E2 两个 client 端点在生产上必然 23503。
            //       故它不是"一张可选的登记表"，而是那两个端点的前置数据。
            //    🛑 具体说明 ON CONFLICT 的形态（下一批改账的人会需要）：
            //       命中目标是【部分】唯一索引 uq_band_active_customer
            //       （UNIQUE (tenant_id, customer_id) WHERE status = 'active'），
            //       故 V17 里写的是 `ON CONFLICT (tenant_id, customer_id) WHERE status = 'active'`
            //       —— 带 WHERE 是语法要求，不是风格选择。
            "band",
            // —— 由设备建档通路写入（B-11 收口，2026-09-27）——
            // 🛑 这一张是本轮【第三次边界移动】的结果：它从 NOT_PROVISIONED 账移入本账。
            //    载体 = V18 的 register_device() 函数体（PL/pgSQL 内的
            //    INSERT INTO device ... ON CONFLICT (device_id) DO NOTHING）。
            //    🛑 为什么它必须进这张账：device_dispatch.device_id 是
            //       NOT NULL REFERENCES device (device_id)，而 D6 POST /device-dispatches
            //       直接写它、链上【没有任何 device 存在性预检】⇒
            //       在 device 零行的现实下每一次下发都以 23503 失败，
            //       且除了一条约束名没有任何可读的业务错误。
            //    🛑 与 B-10（band）的形态差别，下一批改账的人需要知道：
            //       V17 的 ON CONFLICT 推断目标【含租户维度】
            //       （部分唯一索引 uq_band_active_customer = (tenant_id, customer_id) WHERE status='active'），
            //       而 V18 的推断目标是【单列全局主键 device_pkey = (device_id)】——
            //       故跨租户撞号在 V18 里是可能的，必须自己补一条
            //       「冲突了但我在本租户看不见 ⇒ 是别人的 ⇒ RAISE」的判定。
            //       这处差别是被【实测】抓出来的（返回值一度在撒谎）。
            "device",
            // —— 由量表建档通路写入（B-12 收口，2026-09-27）——
            // 🛑 这一张是本轮【第四次边界移动】的结果：它从 NOT_PROVISIONED 账移入本账。
            //    载体 = V19 的 register_scale() / deprecate_scale() 函数体。
            //    🛑 与 B-10 / B-11 的形态差别（两层，都必须说清）：
            //      ① 缺口的对外表现【不是 23503，而是业务校验 422】——
            //         AssessmentService.submitBaseline 第 ③ 步有一道
            //         `if (!ledger.scaleExists(...)) throw BizException(BUSINESS_RULE_VIOLATED, ...)`
            //         预检，它把缺口翻译成了 422/5001「量表不存在或不属当前租户」；
            //         而那句文案【会把人引向错误方向】（读它的人会去怀疑 scale_id 传错 /
            //         租户上下文不对，而真因是库里压根没有任何量表）。
            //         ⇒ 故 B-12 的验收口径是「那道预检返回 true」，而【不是】「23503 消失」
            //           —— 后者在这条链上恒真（这条链根本不产生 23503），是没有判别力的假绿。
            //      ② scale_version 是【死维度】：V5 L235 注释写「版本递增·不可覆盖；
            //         UNIQUE(scale_id, scale_version)」，但实测 scale_pkey 是【单列】
            //         scale_id ⇒ 同一 scale_id 只可能一行 ⇒ uq_scale_id_version
            //         永远不可能被违反。故 V19 额外补了一条「版本不同 ⇒ RAISE（不可覆盖）」
            //         的判定 —— 否则调用方会以为「我要的那一版已存在」，而库里是旧版本。
            "scale",
            // —— 由结案归档通路写入（B-13 收口，2026-09-27）——
            // 🛑 这一张是本轮【第五次边界移动】的结果：它从 NOT_PROVISIONED 账移入本账。
            //    载体 = V20 的 register_case_archive() 函数体。
            //    🛑 与前四批的形态差别（两处，都必须说清）：
            //      ① 跨租户撞号的后果【更隐蔽】：case_archive_pkey 与 device 同型
            //         （单列 archive_id，ON CONFLICT 推断目标不含租户维度），
            //         但归档是业务链条的【末端】—— 一旦跨租户撞号被静默合并成
            //         ALREADY_EXISTS，调用方会把工单标记为已归档，
            //         而【归档档案并不存在】；下游没有任何第二步能证伪它。
            //         ⇒ V20 必须自己 RAISE（"已被【另一租户】占用"），这是本域不可让渡的红线。
            //      ② 读侧口径是【全序取最新】而非单值查询：latest_archive_of()
            //         按 archived_at + 主键 tie-breaker 取最新一份 ——
            //         这使"归档了但读不回来"与"从未归档"在结果上同形（都是 NULL），
            //         故它的真库证据由 CaseArchiveGateTest 判据②（latest_archive_of 三段）
            //         与反向验证 121 守着，而不在本类的机械核对范围内。
            //    🛑 还有一条本域特有的合规红线（不在本类覆盖面内）：
            //       「手环类缺项不得反转为阻断」（P0-25 + README §5.3「三条不得触碰」③）
            //       —— 由 CaseArchiveGateTest 判据⑤（行为）与判据⑦(e)（静态）守着。
            "case_archive",
            // —— 由协议书离线签署通路写入（A-1 收口，2026-09-29）——
            // 🛑 这一张是本轮【第六次边界移动】的结果：它从 NOT_PROVISIONED 账移入本账。
            //    载体 = V21 的 register_agreement() 函数体。
            //    🛑 与前五批的形态差别（三处，都必须说清，否则下一个人会找错地方）：
            //      ① 缺口的对外表现【不是任何错误，而是 G1 的分子恒为 0】——
            //         这与前四批都不同：B-10/B-11 是 23503、B-12 是业务 422、
            //         B-13 是"静默"（只在举证时暴露）。而 agreement 缺写入方时，
            //         签署登记这个动作【在系统里根本无法发生】，于是 PRD G1
            //         「协议签署合规率」的取数 SQL 返回空集 —— 一个恒为 0 的比率
            //         看起来像"业务还没开展"，比"某个调用失败"更难发现。
            //         ⇒ 故 A-1 的验收口径是「G1 取数 SQL 能返回非空」+「一次完整的
            //           签署登记调用真的落了一份可读回的协议」，而不是任何错误码。
            //      ② 本批【刻意不做 HTTP 映射】——它是运维通路形态（离线签署）：
            //         契约 H1 receiveEsignCallback 被【逐字禁止】由本批实现
            //         （那等于开一条"未验签回调即可自证已签"的通道），
            //         且 I8 渲染不在本批范围内（渲染稿与 hash 是入参）。
            //         ⇒ 故本批不动 EndpointCoverageLedgerTest 的 45 个 operationId。
            //      ③ 跨租户撞号的后果【有一个即时的下游消费者】——这是本批最贵的一处：
            //         实测 CustomerGateGuard 已登记 PLAN_APPROVED → AGREEMENT_SIGNED
            //         的跃迁 ⇒ 若 register_agreement() 对"agreement_id 被别的租户占用"
            //         返回 ALREADY_EXISTS，调用方会【立刻】把客户推进已签态，
            //         而协议并不存在 ⇒ "未签不得首次调理"这条硬门禁【在那一刻失效】。
            //         这正是 B-11（device）同型的单列主键撞号，但后果即时且不可逆。
            //    🛑 本域特有的一处"库层做不到"（必须记住，否则会误以为库层已校验）：
            //       rendered_hash 与 rendered_snapshot 的一致性【只能在应用层校验】——
            //       实测真库 pg_extension 只有 plpgsql ⇒ pgcrypto 未装 ⇒
            //       digest() 不存在 ⇒ 库层无法从正文重算 SHA-256。
            //       V21 只能校验 hash 的【形态】，它在注释里逐字声明了这一点。
            //       ⇒ 故本账的"有写入方"这句话的完整含义是：它由
            //         AgreementService（应用层）写，且那条链上的唯一守卫
            //         （hash 重算）在应用层，由 AgreementGateTest 判据⑤守着。
            "agreement",
            // —— 由手环历史补拉通路写入（A-3 收口，2026-09-30）——
            // 🛑 这两张是本轮【第七次边界移动】的结果：它们从 NOT_PROVISIONED 账移入本账。
            //    载体 = V22 的 register_sync_probe() / register_daily_coverage() 两个函数体
            //    （+ BandRefetchLedger / BandRefetchService）。
            //    🛑 与前六批的形态差别（四处，都必须说清，否则下一个人会找错地方）：
            //      ① 本批是【唯一一批"缺口后果直接落在客户身上"的】——
            //         前六批的后果分别是：23503（引用断裂）/ 业务 422 / 静默 /
            //         指标恒为 0（B-13、A-1）。而这两张表缺写入方时，
            //         后果是「§2.8.7③ 的 start 公式取不到 N_retain」
            //         → 补拉按『未探到 N』运行 → 去拉一批设备根本不会返回的日期
            //         → 那些日期被记成缺口 → 缺口里有一类 not_worn
            //         → 【它全 7 值里唯一可扣分的】→ **客户被扣分**。
            //         🛑 这不是程度差异，是类别差异 —— 误差的承受者从"系统"变成了"客户"，
            //         且它是本仓反复记载的"事后不会报错"的形态。
            //      ② 两条原语的幂等形态【刻意相反】，且这一点被单独钉住：
            //         register_sync_probe = ON CONFLICT DO NOTHING（追加，证据不可改写）
            //         register_daily_coverage = ON CONFLICT DO UPDATE（upsert，观测事实可刷新）
            //         ⇒ 把两者统一成同一形态，会让"N 的历史轨迹"或"当日的最终观测"
            //         其中之一被静默丢弃（两者都被 §2.8.7③ 逐字要求）。
            //         由 BandRefetchGateTest 判据 ②/③ 分别断言。
            //      ③ 本批【刻意不做 HTTP 映射】—— 与 A-1 同款但理由不同：
            //         A-1 是因为契约被逐字禁止；本批是因为——
            //         契约全部 40 个 path 里【没有任何一条】是"登记留存探针"或
            //         "写入逐日覆盖"（E 域端点是 E1/E2 遥测上报与 E4 派生结果，
            //         而本批写的是【补拉引擎的内部台账】），
            //         且 gap_reason 在契约上带「🔴 客户恒 403 / 不下发（硬约束，配置不得放开）」——
            //         给它加端点会把一个"客户恒不可见"的判定面暴露到契约上。
            //         ⇒ 故本批不动 EndpointCoverageLedgerTest 的 operationId 计数。
            //      ④ 本批有【两处绕过 Java 也走不通】的库层门禁（前六批都没有）：
            //         (5c) 核心门禁（not_worn 不得配技术性 is_wear）与
            //         (6) 归属一致性（ON CONFLICT … DO UPDATE … WHERE c.customer_id = …）。
            //         二者都刻意**不做成表 CHECK** —— 因为写成语义清晰的表约束需要
            //         一条 NOT (gap_reason='not_worn' AND is_wear IN (-1,255))，
            //         那会让现存的 RlsV5EntityIsolationTest 空值探针（只填 tenant_id）
            //         撞上一条与它无关的约束报 23514，产生**归因完全错误的红**。
            //         ⇒ 故库层门禁的唯一载体就是那两个函数，由 BandRefetchGateTest
            //         判据 ⑤c / ⑥b / ⑪c 绕过 Java 直调函数来证。
            //    🛑 本域特有的一处"结构性不可达"（必须记住，否则会写一条假用例）：
            //       (device_id, date) 的【跨租户撞号不可达】—— device_id 是 band.band_id，
            //       而 band 的 PK 实测逐字 `PRIMARY KEY (band_id)`（单列全局唯一）
            //       ⇒ 同一台设备只可能属于一个租户 ⇒ V22 里那条 coverage 跨租户 RAISE
            //       是 **fail-closed 的防御**，不是可达路径。
            //       （对比：探针的 probe_id 是全局单列主键 ⇒ 跨租户撞号【可达】，须 RAISE。）
            //       ⇒ BandRefetchGateTest 判据 ⑦ 把两者分开断言，并**没有**为不可达路径
            //       编造成功用例（"声称一个不可达路径被正确拒绝"比不写它更坏）。
            "band_sync_probe", "band_daily_coverage",
            // —— 由密钥台账写入 ——
            "tenant_kek", "subject_dek", "subject_key_tombstone",
            // —— 由凭证开通通路写入（2026-10-08，契约 A1 登录闭环落地）——
            //    载体 = V23 建表 + CredentialProvisioningService.createCredential()
            //    （INSERT INTO auth_credential ... ON CONFLICT (account) DO NOTHING，
            //    受影响行数判定 CREATED / ALREADY_EXISTS —— 返回值不撒谎，V18 教训）。
            //    🛑 与 V21/V22 同形态：「服务/运维通路，无 HTTP 映射」—— 开通账号不在
            //    契约 45 操作内，新增端点属契约变更须走冻结流程；本写入方是
            //    内部通路而非占位（AuthLoginE2ETest 经它建凭证，真实可用）。
            //    🛑 本表豁免 RLS（登录先于租户上下文，豁免理由见
            //    RlsCoverageGateTest.NON_TENANT_TABLES 注释），故其写入方
            //    不依赖 RLS 上下文 —— 与本账其他"短事务 SET LOCAL 后写"的表形态不同。
            "auth_credential",
            // —— 由结算落账服务写入（2026-10-09，商用开发第二批 E1）——
            //    载体 = V24 建表 + SettlementStatementService.commit()
            //    （INSERT ... ON CONFLICT (tenant_id, request_hash) DO NOTHING，
            //    幂等键命中回读既有单 —— CREATED / ALREADY_EXISTS，返回值不撒谎）。
            //    预演端点 /settlement/preview 依旧只读不落库（算、账分离）。
            //    本表是租户宿主表（V24 RLS + tenant_isolation），写入方走
            //    短事务 SET LOCAL —— 与本账主流形态一致。
            "settlement_statement"));

    /**
     * 生产代码里<b>确认无任何写入方</b>的表（<b>0 张</b>）。
     *
     * <p>🛑 这不是"待办清单"，而是"<b>差异真的还在</b>"的机械事实：
     * 一旦某张表出现了写入方而没从本表移除，第 ③ 例会红；
     * 一旦迁移里冒出第 44 张表而两账都没登记，第 ① 例会红。
     *
     * <p>🛑🛑 <b>2026-09-30 第七次边界移动（A-3 收口）—— 本账首次归零。</b>
     * <ul>
     *   <li>移动的两张：{@code band_sync_probe} / {@code band_daily_coverage}
     *       → {@link #PROVISIONED}（承载 = V22 的两个函数 + {@code BandRefetchLedger} /
     *       {@code BandRefetchService}）；</li>
     *   <li>🛑 <b>"本账为空"这件事本身必须在代码里显式表达</b>：
     *       一个空的 {@code Set} 与"这行字被误删"在文本上无法区分 ——
     *       故本账下方保留了那道 {@code assertEquals(0, NOT_PROVISIONED.size())} 的
     *       <b>显式自证</b>（见判据 ② 的 {@code ZERO_EXPECTED_DIFF}）。
     *       否则下一个人看到空集合，会以为"这里本来就没内容"而不是
     *       "差异已被走完"，两条路的分支动作完全相反；</li>
     *   <li>🛑 <b>归零不等于门禁失效</b>：本账为 0 之后，第 ① 例（两账并集 == 42）
     *       与第 ③ 例（无写入方的表必须登记）都<b>仍然在跑</b>。
     *       第 ③ 例在"本账为空"时的作用从"发现未登记的表"变成了
     *       "<b>发现有人新加了一张没有写入方的表</b>"——这正是它的原始职责，
     *       只是在归零之前它同时兼任了两件事。这一条必须记住：
     *       <b>两账归零 ~≠~ 边界门禁可以退役</b>。</li>
     * </ul>
     *
     * <p><b>2026-09-27 第一次边界移动（B-7 收口）</b>：原来的 11 张里，
     * {@code tenant} / {@code region} / {@code store} / {@code staff} 四张
     * <b>已移入 {@link #PROVISIONED}</b> —— 组织开通通路落地（V15 +
     * {@code OrganizationProvisioningRepository} / {@code OrganizationProvisioningService}）。
     * 这是一次<b>显式动作</b>：改本表 + 同步 README『明确不做』段与卡点清单的开通边界条目。
     * 本类第 ③ 例的那段错误消息正是为"有人静默移动边界"写的 —— 那次是它的合法用法。
     *
     * <p><b>2026-09-27 第二次边界移动（B-10 收口）</b>：{@code band} 已移入
     * {@link #PROVISIONED} —— 手环绑定通路落地（V17 的 {@code bind_band()} /
     * {@code unbind_band()} + {@code BandBindingLedger} / {@code BandBindingService}）。
     * 🛑 这次的性质与上一次<b>不同</b>，见类注释「第二次边界移动」：
     * 上一次补的是"完全不存在的能力"，这次修的是"写入方只存在于测试夹具里"
     * 而造成的功能缺口（四表 {@code device_id → band(band_id)} 的 NOT NULL 外键
     * 让 E1/E2 在生产上必然 23503）。
     *
     * <p><b>2026-09-27 第三次边界移动（B-11 收口）</b>：{@code device} 已移入
     * {@link #PROVISIONED} —— 设备建档通路落地（V18 的 {@code register_device()} /
     * {@code retire_device()} + {@code DeviceLedger} / {@code DeviceService}）。
     * 🛑 与 B-10 同型（零写入方表被有写入方表引用），但有一条<b>实质差别</b>必须记住：
     * {@code device_pkey} 是<b>单列</b> {@code device_id}，而 B-10 的
     * {@code ON CONFLICT} 推断目标<b>含租户维度</b> ⇒ 跨租户撞号在 device 上是可能的，
     * V18 必须自己补一条"冲突了但我看不见 ⇒ 是别人的 ⇒ RAISE"的判定。
     * 这条差别是被<b>实测</b>抓出来的：初版返回值对一个手里一行都没有的租户
     * 返回了 {@code ALREADY_EXISTS}（"返回值在撒谎"）。
     *
     * <p><b>2026-09-27 第四次边界移动（B-12 收口）</b>：{@code scale} 已移入
     * {@link #PROVISIONED} —— 量表建档通路落地（V19 的 {@code register_scale()} /
     * {@code deprecate_scale()} + {@code ScaleLedger} / {@code ScaleService}）。
     * 🛑 这次与前三批<b>又不同</b>，有一处新形态必须记住：
     * <b>缺口的对外表现不是 23503 而是业务校验 422</b> —— C2 链上有一道
     * {@code AssessmentService.submitBaseline} 第 ③ 步的 {@code scaleExists} 预检，
     * 它把缺口翻译成 422/5001「量表不存在或不属当前租户」。
     * ⇒ 验收口径必须锚在「那道预检返回 true」，而<b>不是</b>照抄前三批的"23503 消失"
     * （后者在本链上恒真 —— 一条没有判别力的假绿）。
     * <p>可迁移的教训（本次新得）：<b>同一个根因（零写入方表）在不同链上会表现成
     * 不同的错误种类，验收口径必须锚在该链真实的表现上。</b>
     *
     * <p><b>2026-09-27 第五次边界移动（B-13 收口）</b>：{@code case_archive} 已移入
     * {@link #PROVISIONED} —— 结案归档通路落地（V20 的 {@code register_case_archive()} /
     * {@code latest_archive_of()} + {@code CaseArchiveLedger} / {@code CaseArchiveService}）。
     * 🛑 与前四批的两处实质差别见 {@link #PROVISIONED} 内的登记注释
     * （① 跨租户撞号后果更隐蔽：归档是链条末端；② 读侧是全序取最新）。
     *
     * <p><b>2026-09-29 第六次边界移动（A-1 收口）</b>：{@code agreement} 已移入
     * {@link #PROVISIONED} —— 协议书离线签署通路落地（V21 的 {@code register_agreement()} /
     * {@code latest_agreement_of()} + {@code AgreementLedger} / {@code AgreementService}）。
     * 🛑 与前五批的三处实质差别见 {@link #PROVISIONED} 内的登记注释
     * （① 缺口表现是"G1 的分子恒为 0"；② 刻意不做 HTTP 映射；③ 跨租户撞号有即时下游消费者）。
     * <p>可迁移的教训（本次新得）：<b>零写入方的第三类表现</b> —— 既不是错误码，
     * 也不是"静默"，而是<b>某个指标永远是 0</b>。前四批的验收口径都锚在"某个错误消失/某个预检返回 true"
     * 上，本批必须锚在"取数 SQL 能返回非空"上。
     *
     * <p><b>2026-09-30 第七次边界移动（A-3 收口）—— 本账归零</b>：最后 2 张
     * {@code band_sync_probe} / {@code band_daily_coverage} 已移入 {@link #PROVISIONED}
     * —— 手环历史补拉通路落地（V22 的 {@code register_sync_probe()} /
     * {@code register_daily_coverage()} + {@code BandRefetchLedger} / {@code BandRefetchService}）。
     * 🛑 与前六批的四处实质差别见 {@link #PROVISIONED} 内的登记注释
     * （① 唯一一批"缺口后果直接落在客户身上"；② 两条原语幂等形态刻意相反；
     * ③ 刻意不做 HTTP 映射；④ 有两处"绕过 Java 也走不通"的库层门禁）。
     * <p>🛑 <b>本批的验收口径与前六批又不同</b>（这一点最该被记住）：
     * 它的靶心不是"某个错误消失"，而是<b>"库层门禁挡得住绕过 Java 的写入"</b>——
     * 即 {@code gap_reason='not_worn'} 与 {@code is_wear IN (-1,255)} <b>不得共存</b>
     * （§2.8.7⑤「一律不判行为性，宁可少扣、不可错扣」）。
     * <p>可迁移的教训（本次新得，两条）：
     * <ol>
     *   <li><b>零写入方的第四类表现</b>：前六批是 23503 / 422 / 静默 / 指标恒 0，
     *       而本批是<b>"一个日后会被用来扣客户分的值取不到"</b> ——
     *       副作用落在客户身上，且<b>当时不报错</b>。
     *       验收口径必须锚在"这两张表能真的落一行、且非法组合被拒"上；</li>
     *   <li><b>两账归零 ≠ 门禁可退役</b>：本账为空之后，
     *       第 ① 例（并集 == 42）与第 ③ 例（无写入方的表必须登记）仍然在跑，
     *       且第 ③ 例的作用回到它的原始职责 ——「发现有人新加了一张没有写入方的表」。</li>
     * </ol>
     * <p>🛑 关于 {@code band_sync_probe} / {@code band_daily_coverage} 为什么<b>不再</b>留在这里：
     * 它们原被登记为 N-13「{@code band_telemetry} ★ 三张补拉表未建/未写」同源项，
     * 且其实现待 A-3 上游补拉口径裁定。裁定结果是
     * 「M-WX-FG 打开即自动同步 + 设备端历史补拉」（PRD §2.8.7，v1.25 新增，
     * 业务方 2026-09-19 拍板），故本批实现了它们的**库层原语与写入方**，
     * 并按纪律显式移入 {@link #PROVISIONED}。
     * <p>🛑 但请注意 A-3 的两条硬边界（不越）：
     * ① <b>不新增 {@code gap_reason} 枚举值</b>（属契约变更，登记待裁）；
     * ② <b>不实现厂商 SDK / 13 条接口 / 游标翻页</b>（运维通路形态，无 HTTP 映射）。
     * ⇒ 故本批交付的是"补拉**结果**的落库通路"，而<b>不是</b>补拉引擎本身。
     * 那条边界与 V22 文件头硬边界②逐字一致。
     */
    private static final Set<String> NOT_PROVISIONED = new LinkedHashSet<>();

    /**
     * 🛑🛑 <b>本账归零的显式声明</b>（A-3 收口，2026-09-30）。
     *
     * <p>为什么一个空 {@code Set} 需要配一条断言：见 {@link #NOT_PROVISIONED} 的注释 ——
     * "空集合"与"这行字被误删"在文本上无法区分，而两条路的分支动作完全相反
     * （前者是"差异已走完"、后者是"门禁失去了它的一半输入"）。
     * <p>🛑 它与第 ① 例的并集断言是<b>两件不同的事</b>：
     * 第 ① 例证"两账合起来覆盖了全部 42 张表"；
     * 本条证"其中【没有】未开通的表"。
     * 若只留第 ① 例，一个把 42 张全登记进 {@code NOT_PROVISIONED} 的改动会全绿。
     */
    private static final int ZERO_EXPECTED_DIFF = 0;

    /** 契约 path 总数（40 个），用作"契约未被悄悄扩张"的哨兵。 */
    private static final int CONTRACT_PATH_COUNT = 40;

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTRACT_PATH = Pattern.compile(
            "(?m)^  (/[A-Za-z0-9_{}/.\\-]*):\\s*$");

    private static final Pattern WRITE_METHOD = Pattern.compile(
            "(?m)^    (post|put|patch|delete):\\s*$");

    /**
     * HTTP 映射注解的<b>简单名</b>清单（判据⑦/⑧/⑨/⑩ 共用）。
     *
     * <p>🛑 清单刻意<b>不含</b> {@code ControllerAdvice} —— 它是全局异常处理，
     * 不是端点暴露。把合法注解误报成违规会诱使人放宽判据，那比没有判据更坏。
     */
    private static final List<String> HTTP_MAPPING_ANNOTATIONS = List.of(
            "RestController", "Controller", "RequestMapping",
            "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "GetMapping");

    /**
     * 注解简单名 → 匹配它的正则（同时覆盖短名与全限定名两种写法）。
     *
     * <h2>🛑 为什么不能直接用 {@code contains("@RestController")}</h2>
     * 那会漏掉<b>全限定名</b>写法：
     * <pre>
     *   @org.springframework.web.bind.annotation.RestController
     * </pre>
     * 子串 {@code "@RestController"} 在这种写法里<b>不存在</b>
     * （{@code @} 与 {@code R} 之间隔着包名）⇒ 一个真的把运维通路暴露成端点的改动
     * 会让门禁<b>全绿通过</b>。这是本轮受控注入实测抓到的一处真实盲区。
     *
     * <h2>正则的构造</h2>
     * <pre>
     *   (?&lt;![A-Za-z0-9_$.])@(\w+\.)*RestController(?![A-Za-z0-9_])
     * </pre>
     * <ul>
     *   <li>{@code @} 前必须是"非标识符、非点、非美元"（或行首）—— 防
     *       {@code someText@RestController} 这种字符串字面量（剥注释后字符串仍在）；</li>
     *   <li>{@code (\w+\.)*} 吃掉可选的包名前缀（全限定名写法）；</li>
     *   <li>尾部 {@code (?![A-Za-z0-9_])} —— 防 {@code @ControllerAdvice} 被
     *       {@code Controller} 那条规则命中（词尾边界，与 V19 自证里的 {@code \M} 同族理由）。</li>
     * </ul>
     * <p>🛑 本仓对"词尾边界"已付过两次代价：PostgreSQL ARE 的 {@code \b} 是退格字符（须用 {@code \M}），
     * 以及 V19 自证里 {@code INSERT INTO scale} 会命中 {@code scale_item_bank}。
     * Java 的 {@code \b} 是真的词边界，故这里可直接用负向先行断言写成等价形式。
     */
    private static final Map<String, Pattern> HTTP_MAPPING_PATTERN_FOR = buildHttpPatterns();

    private static Map<String, Pattern> buildHttpPatterns() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        for (String name : HTTP_MAPPING_ANNOTATIONS) {
            m.put(name, Pattern.compile(
                    "(?<![A-Za-z0-9_$.])@(\\w+\\.)*" + name + "(?![A-Za-z0-9_])"));
        }
        return m;
    }

    // ==================================================================
    // 二、全集侧：机械提取迁移建的表（剥 SQL 注释），不手抄
    // ==================================================================

    @Test
    @DisplayName("① 全集（机械读迁移，剥注释）= 已开通 ∪ 未开通，且两账互斥 —— 不得有第三个")
    void every_migrated_table_is_classified_as_provisioned_or_not() throws IOException {
        Map<String, String> migrated = migratedTables();

        assertFalse(migrated.isEmpty(), "从迁移里解析不出任何建表语句 —— 解析器失效，本门禁形同虚设");
        assertTrue(migrated.size() >= 40,
                "迁移里解析出的表数异常偏少（实测 42），可能是剥注释把真 DDL 也吃掉了：实际=" + migrated.size());

        Set<String> both = new LinkedHashSet<>(PROVISIONED);
        both.retainAll(NOT_PROVISIONED);
        assertTrue(both.isEmpty(),
                "同一张表同时出现在「已开通」与「未开通」两账里 —— 两账必须互斥：" + both);

        Set<String> declared = new LinkedHashSet<>(PROVISIONED);
        declared.addAll(NOT_PROVISIONED);

        Set<String> unaccounted = new LinkedHashSet<>(migrated.keySet());
        unaccounted.removeAll(declared);
        assertTrue(unaccounted.isEmpty(),
                "迁移里存在既未登记「已开通」也未登记「未开通」的表 —— 新增一张表必须显式表态；未登记：" + unaccounted
                        + "（建它的是 " + describe(unaccounted, migrated) + "）");

        Set<String> phantom = new LinkedHashSet<>(declared);
        phantom.removeAll(migrated.keySet());
        assertTrue(phantom.isEmpty(),
                "账本里登记了迁移中并不存在的表 —— 账本已与代码脱节（改名 / 删表未同步）：" + phantom);
    }

    // ==================================================================
    // 三、登记侧：账本与代码的连线必须真的存在
    // ==================================================================

    @Test
    @DisplayName("② 登记为「已开通」的 44 张表，生产代码里必须真的有 INSERT INTO（否则账本在骗人）")
    void tables_declared_as_provisioned_are_actually_written() throws IOException {
        Map<String, String> java = productionSourcesStrippedOfComments();
        Map<String, String> sql = deliverySqlStrippedOfComments();

        // 🛑🛑 归零自证（见 NOT_PROVISIONED / ZERO_EXPECTED_DIFF 的注释）：
        //    空集合与"被误删"在文本上无法区分，故这里把它作为一条显式断言钉住。
        //    ⚠️ 它是本判据里【唯一】一条可能在"两账全空"时仍然失败的断言 ——
        //       因为一个把 42 张全塞进 NOT_PROVISIONED 的改动会让下面那条遍历什么都不检查。
        assertEquals(ZERO_EXPECTED_DIFF, NOT_PROVISIONED.size(),
                "🛑🛑 「未开通」账必须为空（A-3 收口后已归零）—— 实际=" + NOT_PROVISIONED
                        + "。若本账非空，只有两种可能："
                        + "① 有人新加了一张没有写入方的表（正确处置：实现它，或显式登记并说明缺谁）；"
                        + "② 有人把某张表的写入方删了却没动账本（正确处置：回退那处删除）。"
                        + "⚠️ 反向也要小心：若本账被【误清空】，本条会绿，"
                        + "但下面的 liars 遍历会立刻抓到『登记为已开通但找不到写入方』的表。");

        List<String> liars = new ArrayList<>();
        for (String table : PROVISIONED) {
            if (!hasWriterIn(java, sql, table)) {
                liars.add(table);
            }
        }
        assertTrue(liars.isEmpty(),
                "下列表被登记为「已开通」，但生产代码（剥注释后）里找不到任何 INSERT INTO —— "
                        + "账本与代码脱节，必须二选一：补写入方，或把它移到「未开通」账。表=" + liars);
    }

    // ==================================================================
    // 四、未开通侧：归零必须是一次显式动作
    // ==================================================================

    @Test
    @DisplayName("③ 登记为「未开通」的表必须真的没有写入方（本账已归零，本判据转为兜底）")
    void tables_declared_as_not_provisioned_have_no_writer_in_production() throws IOException {
        Map<String, String> java = productionSourcesStrippedOfComments();
        Map<String, String> sql = deliverySqlStrippedOfComments();

        // 🛑 两账归零 ≠ 本判据可退役（见 NOT_PROVISIONED 注释的教训②）：
        //    它现在的职责回到原始形态 ——「发现有人新加了一张没有写入方的表」。
        //    在归零之前它同时兼任"发现边界被静默移动"，那一半已由判据 ② 的
        //    ZERO_EXPECTED_DIFF 显式承担。
        Map<String, String> offenders = new LinkedHashMap<>();
        for (String table : NOT_PROVISIONED) {
            List<String> where = writersIn(java, sql, table);
            if (!where.isEmpty()) {
                offenders.put(table, String.join(" ; ", where));
            }
        }
        assertTrue(offenders.isEmpty(),
                "下列表被登记为「未开通」，但生产代码里已经出现了 INSERT INTO —— "
                        + "说明这条边界已经被移动。这是一件必须显式做的事："
                        + "若新增写入方是有意为之，请把它移入 PROVISIONED 账，"
                        + "并同步 README『明确不做』段与卡点清单的开通边界条目；"
                        + "若不是有意的，请回退那处写入。命中：" + offenders);
    }

    // ==================================================================
    // 五、契约侧：不得存在写入型开通端点
    // ==================================================================

    @Test
    @DisplayName("④ 契约 40 个 path 里没有租户/门店/员工的写入型端点，且 /stores 段内只有 GET")
    void the_contract_exposes_no_provisioning_endpoint() throws IOException {
        Path contract = skeletonRoot().getParent().resolve(CONTRACT_RELATIVE);
        assertNotNull(contract, "契约路径解析失败");
        assertTrue(Files.isRegularFile(contract),
                "找不到契约文件（本门禁的契约侧判据没有载体）：" + contract);
        String text = Files.readString(contract, StandardCharsets.UTF_8);

        Map<String, String> blocks = contractPathBlocks(text);
        assertEquals(CONTRACT_PATH_COUNT, blocks.size(),
                "契约 path 数与冻结值不符（实测 40）—— 契约被扩张/收缩了。"
                        + "若新增了端点，必须同步：README 端点台账、EndpointCoverageLedgerTest、"
                        + "三端 SDK 回归门禁。实际 path=" + blocks.keySet());

        // ① 不得出现组织开通类路径
        List<String> provisioningPaths = new ArrayList<>();
        for (String p : blocks.keySet()) {
            String lower = p.toLowerCase();
            if ((lower.startsWith("/tenants") || lower.startsWith("/staff")
                    || lower.startsWith("/regions"))
                    && hasWriteMethod(blocks.get(p))) {
                provisioningPaths.add(p);
            }
        }
        assertTrue(provisioningPaths.isEmpty(),
                "契约里出现了组织开通类写入端点 —— 这会把本门禁的前提推翻："
                        + "若这是有意的范围扩张，需要同步 README『明确不做』段与开通边界登记；"
                        + "命中的路径=" + provisioningPaths);

        // ② /stores 只能是只读列表（A3）
        assertTrue(blocks.containsKey("/stores"),
                "契约里没有 /stores —— 本门禁的只读断言失去载体（A3 门店列表是唯一与组织主数据相关的端点）");
        assertFalse(hasWriteMethod(blocks.get("/stores")),
                "/stores 段内出现了写方法（post/put/patch/delete）—— "
                        + "A3 冻结的是「门店列表（按行级 scope 过滤）」，只读；"
                        + "它一旦可写，就等于凭空长出一个「建门店」通路，组织开通边界随之失效");
    }

    // ==================================================================
    // 六、元层自证：剥注释真的在工作（否则 tenant 会被误判成"已开通"）
    // ==================================================================

    @Test
    @DisplayName("⑤ 元层自证：stripComments 真的剥掉注释 —— 已知有『注释里写着 INSERT INTO tenant』的真实样本，剥前有、剥后必须没有")
    void the_comment_stripper_actually_works_on_a_known_comment_only_mention() throws IOException {
        // 载体：DbTenantKekProvider 的 Javadoc 里逐字写着「全仓 dy-app/src/main 与
        // dy-config/src/main 里没有 INSERT INTO tenant」—— 这句话本身就是一个
        // 【注释-only 引用】。它同时是本类最好的自证样本与最大的陷阱来源。
        Path probe = skeletonRoot().resolve(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/crypto/repository/DbTenantKekProvider.java");
        assertTrue(Files.isRegularFile(probe), "自证样本文件不存在：" + probe);

        String raw = Files.readString(probe, StandardCharsets.UTF_8);
        String stripped = stripComments(raw);

        assertTrue(raw.contains("INSERT INTO tenant"),
                "自证样本失效：DbTenantKekProvider 的 Javadoc 里应当逐字写着 INSERT INTO tenant。"
                        + "若这条不成立，本类的剥注释自证就没有载体，必须换样本 —— "
                        + "不要让这条断言静默地变成永真");
        assertFalse(stripped.contains("INSERT INTO tenant"),
                "剥注释失败：INSERT INTO tenant 只出现在 Javadoc 里，剥注释后不该残留。"
                        + "若不剥，tenant 会被第 ③ 例误判成『已开通』—— "
                        + "这正是本仓 N-17（AuditFillAspect 用 src.contains(\"@Aspect\") 断言）踩过的坑："
                        + "源码里字面出现 ≠ 它真的生效");
    }

    // ==================================================================
    // 七、前提守卫：一旦引入 ORM，"表名出现在字面量里"这条判据即失效
    // ==================================================================

    @Test
    @DisplayName("⑥ 前提：全仓无 ORM（持久化全走静态 SQL 字符串）—— 判据的成立条件本身也必须被守着")
    void no_orm_is_on_the_classpath_which_is_what_makes_the_literal_scan_sound() throws IOException {
        List<Path> poms = new ArrayList<>();
        try (var walk = Files.walk(skeletonRoot(), 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .forEach(poms::add);
        }
        assertTrue(poms.size() >= 9, "扫到的 pom.xml 数异常偏少（实测 9 个模块 + 父 pom），路径解析可能失败");

        List<String> ormHits = new ArrayList<>();
        for (Path pom : poms) {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            for (String marker : List.of("spring-boot-starter-data-jpa", "mybatis", "hibernate")) {
                if (text.contains(marker)) {
                    ormHits.add(skeletonRoot().relativize(pom) + " → " + marker);
                }
            }
        }
        assertTrue(ormHits.isEmpty(),
                "classpath 上出现了 ORM —— 本类『表名出现在源码字面量里 ⇔ 存在写入路径』"
                        + "这条判据的前提被推翻（ORM 会隐式写表，静态扫描扫不到）。"
                        + "此时不得直接删本类，而应改为：用 ORM 的元数据/拦截器做等效判定。命中：" + ormHits);
    }

    // ==================================================================
    // 六、B-7 新增：开通通路必须存在，且形态必须是【运维通路】而非 HTTP 端点
    // ==================================================================

    /**
     * 判据⑦ —— 把「组织开通走运维通路、不暴露 HTTP」这个<b>决策</b>钉成机械事实。
     *
     * <h2>为什么本轮必须新增这一条（前六条合起来管不住它）</h2>
     * 前六条全是"<b>断言不存在</b>"：不存在开通端点、不存在写入方（未开通账）、不存在 ORM。
     * 而本轮做的是<b>引入</b>——一个门禁体系若只会断言"没有"，它对"有了"这件事
     * <b>完全沉默</b>：开通通路落地后，前六条要么照常绿（第④例查契约，契约没变），
     * 要么被显式改账（第②③例）。也就是说，<b>没有任何一条在问</b>：
     * 「你落地的是不是约定的那个形态？」
     *
     * <h2>它防的具体事（不是假想）</h2>
     * 最可能发生的漂移是：有人发现"开通逻辑已经写好了，只差一个 Controller"，
     * 于是给 {@code OrganizationProvisioningService} 加一个 {@code @RestController}
     * 与 {@code @PostMapping("/tenants")} —— 这在<b>代码层完全说得通</b>，
     * 而且能"顺便"通过所有既有测试。但它会：
     * <ul>
     *   <li>在契约之外长出一个端点（{@code EndpointCoverageLedgerTest} 会红，
     *       但那条红报的是"端点多了"，容易被当成台账没更新而不是设计越界）；</li>
     *   <li>开出一个<b>无角色声明覆盖</b>的鉴权面 —— {@code x-callable-roles} 里
     *       没有"能创建租户"的角色，于是这个端点要么 403 对所有人（等于没有），
     *       要么被某个 {@code permitAll} 放过（等于任何人可建租户）。</li>
     * </ul>
     * 故本判据把"形态"本身变成可判定的事实：<b>存在 + 无 HTTP 注解 + 迁移原语在位</b>。
     */
    @Test
    @DisplayName("⑦ B-7：组织开通通路存在（V15 原语 + 仓储/服务），且不得带任何 HTTP 映射注解")
    void the_provisioning_path_exists_as_an_ops_path_and_exposes_no_http_endpoint() throws IOException {
        Path root = skeletonRoot();

        // ---- (a) 三件套必须在位：迁移原语 + 仓储 + 服务 ----
        Path migration = root.resolve(MIGRATION_DIR).resolve("V15__organization_provisioning.sql");
        assertTrue(Files.isRegularFile(migration),
                "V15 组织开通迁移不存在：" + migration
                        + "\n若组织开通通路被有意移除，请同时：把 tenant/region/store/staff 移回"
                        + " NOT_PROVISIONED 账、恢复本类类注释、并在 README『明确不做』段恢复登记。"
                        + "🛑 禁止只删代码不改账 —— 那会让两账与代码脱节而本类却仍然全绿。");
        String v15 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v15.contains("FUNCTION provision_tenant"),
                "V15 里找不到 provision_tenant 函数定义 —— 迁移的载体变了，本判据的前提不成立");
        assertTrue(v15.contains("FUNCTION assert_tenant_context"),
                "V15 里找不到 assert_tenant_context 守卫函数 —— "
                        + "它是『上下文确实建立』这条不变量的可断言载体，缺了它就只能靠人看代码");

        String repoRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/repository/"
                + "OrganizationProvisioningRepository.java";
        String svcRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/service/"
                + "OrganizationProvisioningService.java";
        Path repo = root.resolve(repoRel);
        Path svc = root.resolve(svcRel);
        assertTrue(Files.isRegularFile(repo), "组织开通仓储不存在：" + repoRel
                + "（它承载 tenant/region/store/staff 四张表的写入方，缺了它两账立即变谎）");
        assertTrue(Files.isRegularFile(svc), "组织开通服务不存在：" + svcRel
                + "（它承载开通的审计留痕——一次无留痕的租户创建不可事后补证）");

        // ---- (b) 两个类都不得带 HTTP 映射注解（这是本判据的核心）----
        // 只剥注释：注解要在【代码态】才算数（注释里提到 @RestController 是文档，不是暴露）。
        // 🛑 判定收敛到 assertNoHttpMapping（与判据⑧/⑨/⑩ 共用同一处实现）——
        //    本仓纪律「同一件事只有一处定义」：三处内联循环会各自漂移，
        //    而漂移的失败形态是「某一条判据看不见全限定名写法」。受控注入已实测过这个盲区。
        List<String> offenders = httpMappingOffenders(root, List.of(repoRel, svcRel));
        assertTrue(offenders.isEmpty(),
                "组织开通通路被暴露成了 HTTP 端点 —— 这与契约化决策相悖。"
                        + "契约 40 个 path 里【有意】没有租户开通端点：开通是运维/实施动作，"
                        + "不是业务 API；给它一个对外端点，等于在租户边界之外开一个"
                        + "『谁能创建租户』的鉴权面，而 x-callable-roles 里没有任何角色声明覆盖它。"
                        + "加这样一个端点属契约 MAJOR 变更，需产品共签。"
                        + "若这是有意的范围扩张，正确顺序是：先改契约 + 定角色 → 再落码 → "
                        + "最后同步 README / 端点台账 / 三端 SDK。命中：" + offenders);

        // ---- (c) 自证：上面那条检查真的有判别力（否则"零命中"可能只是扫描器坏了）----
        // 拿一个确实带 @RestController 的既有类做正样本，证明 marker 匹配是活的。
        String positiveSample = stripComments(Files.readString(
                root.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/controller/"
                        + "StoreListController.java"), StandardCharsets.UTF_8));
        assertTrue(positiveSample.contains("@RestController"),
                "自证样本失效：StoreListController 应当带 @RestController。若这条不成立，"
                        + "本判据的『零命中』就没有判别力（可能只是检查逻辑失效而恰好没报）");
        assertTrue(positiveSample.contains("@GetMapping"),
                "自证样本失效：StoreListController 应当带 @GetMapping（同上，自证判别力）");

        // ---- (d) 契约侧的形态确认（与第 ④ 例互为印证，但它盯的是 /tenants 这个具体形状）----
        Path contract = root.getParent().resolve(CONTRACT_RELATIVE);
        Map<String, String> blocks = contractPathBlocks(
                Files.readString(contract, StandardCharsets.UTF_8));
        for (String p : blocks.keySet()) {
            String lower = p.toLowerCase();
            assertFalse(lower.equals("/tenants") || lower.startsWith("/tenants/"),
                    "契约里出现了 /tenants 端点（" + p + "）—— 组织开通的形态被从"
                            + "『运维通路』改成了『对外 API』。这一变更必须走契约 MAJOR 流程，"
                            + "本判据的存在就是为了让它无法在一次『顺手』中发生");
        }
    }

    // ==================================================================
    // 八、B-10 新增：手环绑定通路的【形态】必须与 B-7 同款（运维通路，不暴露 HTTP）
    // ==================================================================

    /**
     * 判据⑧ —— 把「手环绑定走运维通路、不暴露 HTTP」这个<b>决策</b>钉成机械事实。
     *
     * <h2>为什么必须新增这一条（判据⑦ 管不到它）</h2>
     * 判据⑦ 只盯 {@code OrganizationProvisioningRepository} / {@code ...Service}
     * 两个<b>具体文件</b>。B-10 引入的是一套<b>新的</b>类
     * （{@code BandBindingLedger} / {@code BandBindingService}），
     * 判据⑦ 的清单里没有它们 ⇒ 给它们加 {@code @RestController} 时判据⑦ <b>不会响</b>。
     * <p>🛑 而 B-10 比 B-7 <b>更容易</b>发生这种漂移，理由是具体的：
     * {@code band} 是<b>业务表</b>（客户级台账），而不是 {@code tenant} 那样的
     * "系统级容器"。给一张业务表加"绑定端点"在直觉上完全合理 ——
     * 而 D6 的 description 逐字写着的
     * 「本接口操作的是【门店级调理设备 device】……与【客户级手环 band】是两本台账、
     * 不得合并」，恰恰是<b>防这种直觉</b>的。
     * <p>还有一层更硬的理由：契约 {@code x-callable-roles} 里<b>没有任何角色</b>
     * 覆盖"给客户绑带子"。client 是被绑的对象而不是操作者 ——
     * 于是这个端点要么 403 对所有人（等于没有），要么被某个 {@code permitAll} 放过
     * （等于任何人可给任意客户绑带子，而带子的 {@code bound_at} 是退款指标的输入）。
     *
     * <h2>它同时是"账本改动的合法性"守卫</h2>
     * 本类第 ③ 例的红是「{@code band} 有写入方了，你必须显式改账」。
     * 但"显式改账"本身也可以是一次懒动作：有人可以为了让 ③ 绿，
     * 而<b>接线一个绑带子端点</b>——那时 ③ 确实绿了，而边界是被<b>错误地</b>移动的。
     * 本判据把"移动后的形态"也钉住：<b>有写入方（②）+ 无 HTTP 暴露（⑧）</b>，
     * 两条合起来才等于"按约定落地"。
     */
    @Test
    @DisplayName("⑧ B-10：手环绑定通路存在（V17 原语 + 仓储/服务），且不得带任何 HTTP 映射注解")
    void the_band_binding_path_exists_as_an_ops_path_and_exposes_no_http_endpoint() throws IOException {
        Path root = skeletonRoot();

        // ---- (a) 三件套必须在位：迁移原语 + 仓储 + 服务 ----
        Path migration = root.resolve(MIGRATION_DIR).resolve("V17__band_binding_primitive.sql");
        assertTrue(Files.isRegularFile(migration),
                "V17 手环绑定迁移不存在：" + migration
                        + "\n若手环绑定通路被有意移除，请同时：把 band 移回 NOT_PROVISIONED 账、"
                        + "恢复本类类注释、并在 README 里恢复登记。"
                        + "🛑 禁止只删代码不改账 —— 那会让两账与代码脱节而 ② 例会红。"
                        + "🛑 更禁止『只删代码不改账、还把 ② 例放宽』—— 那会让 band 的"
                        + "零写入方缺口重新静默回来（四表 device_id 外键又会 23503）。");
        String v17 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v17.contains("FUNCTION bind_band"),
                "V17 里找不到 bind_band 函数定义 —— 迁移的载体变了，本判据的前提不成立");
        assertTrue(v17.contains("FUNCTION unbind_band"),
                "V17 里找不到 unbind_band 函数定义 —— 它是『解绑是状态迁移而非删除』"
                        + "这条不变量的可断言载体（unbound_at 是应戴天分母的终点，"
                        + "而 A3 是退款资格的输入），缺了它就只能靠人看代码");

        String ledgerRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/"
                + "BandBindingLedger.java";
        String svcRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/"
                + "BandBindingService.java";
        assertTrue(Files.isRegularFile(root.resolve(ledgerRel)),
                "手环绑定仓储不存在：" + ledgerRel
                        + "（它是 band 表的写入方载体，缺了它 ② 例会立刻指出账本在骗人）");
        assertTrue(Files.isRegularFile(root.resolve(svcRel)),
                "手环绑定服务不存在：" + svcRel
                        + "（它承载绑定的审计留痕 —— band.bound_at / unbound_at 是应戴天分母的两端，"
                        + "一次无留痕的台账变更不可事后补证）");

        // ---- (b) 两个类都不得带 HTTP 映射注解（本判据的核心）----
        // 只剥注释：注解要在【代码态】才算数（注释里提到 @RestController 是文档，不是暴露）。
        // 🛑 判定收敛到 httpMappingOffenders（同判据⑦/⑨/⑩）—— 见那边的说明。
        List<String> offenders = httpMappingOffenders(root, List.of(ledgerRel, svcRel));
        assertTrue(offenders.isEmpty(),
                "手环绑定通路被暴露成了 HTTP 端点 —— 这与契约化决策相悖。"
                        + "契约 40 个 path 里【有意】没有绑定/解绑端点："
                        + "域 E（E1~E6）只有上报与读取，而 D6 POST /device-dispatches 的 description "
                        + "【逐字】写着『本接口操作的是【门店级调理设备 device】……"
                        + "与【客户级手环 band】是两本台账、不得合并』。"
                        + "🛑 更硬的一层：x-callable-roles 里没有任何角色覆盖『给客户绑带子』—— "
                        + "client 是被绑的对象而不是操作者。这个端点要么 403 对所有人（等于没有），"
                        + "要么被某个 permitAll 放过（等于任何人可给任意客户绑带子，"
                        + "而带子的 bound_at 是 A3/退款指标的输入）。"
                        + "加这样一个端点属契约 MAJOR 变更，需产品共签。命中：" + offenders);

        // ---- (c) 自证：上面那条检查真的有判别力 ----
        // 复用判据⑦ 已验证过的正样本（本类里已有一条同型自证，此处不重复引用，
        // 而是换一个【本域内】的正样本 —— 因为"域内样本也能被检出"才是本判据需要的能力：
        // 若有人把 @RestController 加到 band 包里的某个类上，扫描器必须看得见）。
        String bandController = stripComments(Files.readString(
                root.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/band/controller/"
                        + "BandController.java"), StandardCharsets.UTF_8));
        assertTrue(bandController.contains("@RestController"),
                "自证样本失效：BandController 应当带 @RestController。若这条不成立，"
                        + "本判据的『零命中』就没有判别力 —— 尤其无法排除"
                        + "『band 包内的类被加了注解而扫描器没看见』这一情形");
        assertTrue(bandController.contains("@PostMapping"),
                "自证样本失效：BandController 应当带 @PostMapping（同上，自证判别力）");

        // ---- (d) 契约侧的形态确认：域 E 段内不得出现写方法（E1/E2 的 POST 除外）----
        //     🛑 本条的判据必须精确，否则会误报：E1（/band/sync-batches）与
        //        E2（/band/telemetry）与 E5（/band/available-dates）**本来就是 POST**
        //        （它们"上报"而不是"创建台账"）。故不能笼统地"禁止域 E 里有 POST"。
        //        真正要排除的形态是：一个**以 band 为资源创建对象**的路径 ——
        //        即 `/band` 本身（POST 建一支带子）或 `/bands`。
        Path contract = root.getParent().resolve(CONTRACT_RELATIVE);
        Map<String, String> blocks = contractPathBlocks(
                Files.readString(contract, StandardCharsets.UTF_8));

        // 🛑 这三个路径是域 E 里【合法的】写方法 —— 它们表达的是"上报"而非"创建台账"：
        //      E1 /band/sync-batches  上报同步批次
        //      E2 /band/telemetry     上报遥测
        //      E5 /band/available-dates 上报日期探测结果
        //    把它们排除掉，是为了让本判据只命中真正的违规形态 ——
        //    一个判据若对既有合法形态报红，它就会被人"顺手放宽"到失去意义。
        List<String> uploadSemantics = List.of(
                "/band/sync-batches", "/band/telemetry", "/band/available-dates");

        // 🛑 命中条件必须显式写全，不能靠运算符优先级推导：
        //    本判据初版写作 `A || B && C && D`，而 `&&` 优先于 `||` ⇒
        //    实际语义是 `A || (B && C && D)` —— A（`/band` 本身）会**绕开**方法判定，
        //    即"`/band` 这个 path 存在"就报红，与"它是否有写方法"无关。
        //    那会让一条**只读**的 `/band` 也报红（假阳性），而假阳性会诱使人放宽判据。
        //    故拆成显式局部变量，让意图在代码里可见。
        for (String p : blocks.keySet()) {
            String lower = p.toLowerCase();
            boolean isBandResourceWrite = hasWriteMethod(blocks.get(p))
                    && (lower.equals("/band")
                        || lower.startsWith("/band/") && !uploadSemantics.contains(lower));
            assertFalse(isBandResourceWrite,
                    "契约里出现了以 band 为资源创建/修改对象的写端点（" + p + "）—— "
                            + "手环绑定的形态被从『运维通路』改成了『对外 API』。"
                            + "域 E 已有的三个 POST（sync-batches / telemetry / available-dates）"
                            + "都是【上报】语义，不是【创建台账】语义，故不属本条命中范围。"
                            + "这一变更必须走契约 MAJOR 流程（并先定义『谁有权绑带子』），"
                            + "本判据的存在就是为了让它无法在一次『顺手』中发生");
        }
    }

    // ==================================================================
    // 九、B-11 / B-12 新增：设备与量表建档通路的【形态】也必须与 B-7 / B-10 同款
    // ==================================================================

    /**
     * 判据⑨ —— 把「设备建档走运维通路、不暴露 HTTP」这个<b>决策</b>钉成机械事实。
     *
     * <h2>为什么必须新增这一条（判据⑦/⑧ 管不到它）</h2>
     * 判据⑦/⑧ 各只盯<b>一组具体文件</b>（B-7 的两件、B-10 的两件）。
     * B-11 引入的是<b>又一组</b>新类（{@code DeviceLedger} / {@code DeviceService}），
     * 它们在⑦/⑧ 的清单里都没有 ⇒ 给它们加 {@code @RestController} 时
     * <b>两条判据都不会响</b>。
     *
     * <h2>🛑 本类第 ③ 例的红，可以靠"接线一个对外端点"来糊弄 —— 那正是本判据要封的</h2>
     * 第 ③ 例的红是「{@code device} 有写入方了，你必须显式改账」。
     * 但"显式改账"本身也可以是一次懒动作：为了让 ③ 绿，
     * 而<b>接线一个"创建设备"端点</b> —— 那时 ③ 确实绿了，
     * 而边界是被<b>错误地</b>移动的。
     * <p>本判据把"移动后的形态"也钉住：<b>有写入方（②）+ 无 HTTP 暴露（⑨）</b>，
     * 两条合起来才等于"按约定落地"。
     * <p>🛑 这条纪律在 B-10 时期就已经写进判据⑧ 了，但当时只覆盖了 band 那一组文件 ——
     * 每新增一批通路就必须新增一条同型判据，否则"覆盖面"会随批次数增长而<b>相对缩小</b>：
     * 判据总数在涨，被它真正盯住的通路却没涨。本仓把这件事当成"门禁维护的一部分"，
     * 而不是"下次再说"。
     */
    @Test
    @DisplayName("⑨ B-11：设备建档通路存在（V18 原语 + 仓储/服务），且不得带任何 HTTP 映射注解")
    void the_device_provisioning_path_exists_as_an_ops_path_and_exposes_no_http_endpoint()
            throws IOException {
        Path root = skeletonRoot();

        // ---- (a) 三件套必须在位：迁移原语 + 仓储 + 服务 ----
        Path migration = root.resolve(MIGRATION_DIR).resolve("V18__device_provisioning_primitive.sql");
        assertTrue(Files.isRegularFile(migration),
                "V18 设备建档迁移不存在：" + migration
                        + "\n若设备建档通路被有意移除，请同时：把 device 移回 NOT_PROVISIONED 账、"
                        + "恢复本类类注释、并在 README 里恢复登记。"
                        + "🛑 禁止只删代码不改账 —— 那会让 device 的零写入方缺口重新静默回来"
                        + "（D6 每一次下发又会以 23503 失败，且只有一条约束名可读）。");
        String v18 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v18.contains("FUNCTION register_device"),
                "V18 里找不到 register_device 函数定义 —— 迁移的载体变了，本判据的前提不成立");
        assertTrue(v18.contains("FUNCTION retire_device"),
                "V18 里找不到 retire_device 函数定义 —— 它是『归档是状态迁移而非删行』"
                        + "这条不变量的可断言载体（device_dispatch 的引用目标不可凭空消失）");

        String ledgerRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/repository/"
                + "DeviceLedger.java";
        String svcRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/service/"
                + "DeviceService.java";
        assertTrue(Files.isRegularFile(root.resolve(ledgerRel)),
                "设备建档仓储不存在：" + ledgerRel
                        + "（它是 device 表的写入方载体，缺了它 ② 例会立刻指出账本在骗人）");
        assertTrue(Files.isRegularFile(root.resolve(svcRel)),
                "设备建档服务不存在：" + svcRel
                        + "（它承载建档的审计留痕 —— device 是 D6 下发『可追责』语义的引用起点，"
                        + "一次无留痕的建档不可事后补证『这台设备从哪来』）");

        // ---- (b) 两个类都不得带 HTTP 映射注解（本判据的核心）----
        assertNoHttpMapping(root, List.of(ledgerRel, svcRel), "设备建档通路",
                "契约 40 个 path 里【有意】没有『创建设备』端点：唯一的设备相关 path 是 "
                        + "D6 POST /device-dispatches，其 summary 逐字是"
                        + "「D6 设备参数下发（需方案已审核）」—— 操作的是【已存在的】门店设备，"
                        + "不是『创建设备』。给建档加对外端点属契约 MAJOR 变更，"
                        + "且要先回答契约回答不了的问题：「谁有权往门店建设备台账」。");

        // ---- (c) 自证：上面那条检查真的有判别力 ----
        // 🛑 换一个【本域内】的正样本：要证的能力是"device 包里的类被加注解时扫描器看得见"。
        String positive = stripComments(Files.readString(
                root.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/controller/"
                        + "PlanController.java"), StandardCharsets.UTF_8));
        assertTrue(positive.contains("@RestController"),
                "自证样本失效：PlanController 应当带 @RestController。若这条不成立，"
                        + "本判据的『零命中』就没有判别力（可能只是检查逻辑失效而恰好没报）");

        // ---- (d) 契约侧的形态确认：不得出现以 device 为资源创建对象的写端点 ----
        //     🛑 与判据⑧ 同款：命中条件必须显式写全，不能靠运算符优先级推导。
        assertNoContractResourceWrite(root, "device", "/device-dispatches",
                "设备建档的形态被从『运维通路』改成了『对外 API』");
    }

    /**
     * 判据⑩ —— 同上，针对 B-12（{@code scale}）。
     *
     * <h2>🛑 为什么本判据的契约侧断言必须特别小心（否则会误报）</h2>
     * 契约里有一个 {@code /scale-item-banks} 路径 —— 那是 <b>C1 题库</b>，
     * 与"量表台账 {@code scale}"是<b>两张不同的表</b>
     * （{@code scale_item_bank} vs {@code scale}）。
     * 一个粗糙的"路径里含 scale 就当违规"的判据会把它误判成红，
     * 而<b>假阳性会诱使人放宽判据</b> —— 那比没有判据更坏。
     * <p>故本判据的资源写入判定用<b>路径段匹配</b>（{@code /scale} 或 {@code /scales}
     * 作为独立段），而不是子串包含 —— 与判据⑨b 在 Java 侧用词尾边界 {@code \b}
     * 是同一个理由（那时防的是 {@code scale_item_bank}，此时防的是 {@code /scale-item-banks}）。
     */
    @Test
    @DisplayName("⑩ B-12：量表建档通路存在（V19 原语 + 仓储/服务），且不得带任何 HTTP 映射注解")
    void the_scale_provisioning_path_exists_as_an_ops_path_and_exposes_no_http_endpoint()
            throws IOException {
        Path root = skeletonRoot();

        Path migration = root.resolve(MIGRATION_DIR).resolve("V19__scale_provisioning_primitive.sql");
        assertTrue(Files.isRegularFile(migration),
                "V19 量表建档迁移不存在：" + migration
                        + "\n若量表建档通路被有意移除，请同时：把 scale 移回 NOT_PROVISIONED 账、"
                        + "恢复本类类注释、并在 README 里恢复登记。"
                        + "🛑 禁止只删代码不改账 —— 那会让 C2 重新恒返回 422/5001，"
                        + "而且那句『量表不存在或不属当前租户』的文案会继续把人引向错误方向。");
        String v19 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v19.contains("FUNCTION register_scale"),
                "V19 里找不到 register_scale 函数定义 —— 迁移的载体变了，本判据的前提不成立");
        assertTrue(v19.contains("FUNCTION deprecate_scale"),
                "V19 里找不到 deprecate_scale 函数定义 —— 它是『废弃是状态迁移而非删行』"
                        + "这条不变量的可断言载体（baseline_assessment 的引用目标不可凭空消失）");

        String ledgerRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/repository/"
                + "ScaleLedger.java";
        String svcRel = "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/service/"
                + "ScaleService.java";
        assertTrue(Files.isRegularFile(root.resolve(ledgerRel)),
                "量表建档仓储不存在：" + ledgerRel
                        + "（它是 scale 表的写入方载体，缺了它 ② 例会立刻指出账本在骗人）");
        assertTrue(Files.isRegularFile(root.resolve(svcRel)),
                "量表建档服务不存在：" + svcRel
                        + "（它承载建档的审计留痕 —— scale 是 C2 的打分依据，而基線档案 locked=TRUE "
                        + "不可修改，一次无留痕的建档【无法事后修正】）");

        assertNoHttpMapping(root, List.of(ledgerRel, svcRel), "量表建档通路",
                "契约 40 个 path 里【有意】没有『创建量表』端点：与 scale 相关的都是读侧"
                        + "（如 C1 /scale-item-banks，那是【题库】不是【量表台账】）或"
                        + "「用一个已存在的量表做基线」（C2）。给建档加对外端点属契约 MAJOR 变更，"
                        + "且要先回答契约回答不了的问题：「谁有权给租户建量表」—— "
                        + "量表是【口径资产】（它决定打分维度），比设备台账更接近『配置』而不是『作业』。");

        // ---- (c) 自证：本域内的正样本（scale 包内确实有一个 controller）----
        String positive = stripComments(Files.readString(
                root.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/controller/"
                        + "ScaleItemBankController.java"), StandardCharsets.UTF_8));
        assertTrue(positive.contains("@RestController"),
                "自证样本失效：ScaleItemBankController 应当带 @RestController。若这条不成立，"
                        + "本判据的『零命中』就没有判别力 —— 尤其无法排除"
                        + "『scale 包内的类被加了注解而扫描器没看见』这一情形");

        // ---- (d) 契约侧：不得出现以 scale 为资源创建对象的写端点 ----
        assertNoContractResourceWrite(root, "scale", null,
                "量表建档的形态被从『运维通路』改成了『对外 API』");
    }

    /**
     * 扫描给定文件（剥注释后）里的 HTTP 映射注解，返回违规清单（空 = 合规）。
     *
     * <p>🛑 抽成独立方法而不是内联四遍：判据⑦/⑧/⑨/⑩ 各盯一组文件，
     * 但"什么算暴露"这件事<b>只有一处定义</b>。四处内联会各自漂移 ——
     * 而漂移的失败形态恰是"某一条判据看不见某种写法"，即假绿。
     * 本仓纪律：同一件事只有一处定义。
     */
    private static List<String> httpMappingOffenders(Path root, List<String> rels)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String rel : rels) {
            String code = stripComments(Files.readString(root.resolve(rel), StandardCharsets.UTF_8));
            for (String simpleName : HTTP_MAPPING_ANNOTATIONS) {
                if (HTTP_MAPPING_PATTERN_FOR.get(simpleName).matcher(code).find()) {
                    offenders.add(rel + " → @" + simpleName);
                }
            }
        }
        return offenders;
    }

    /**
     * 断言给定文件（剥注释后）里不含任何 HTTP 映射注解。
     *
     * <p>🛑 只剥注释：注解要在<b>代码态</b>才算数（注释里提到 {@code @RestController}
     * 是文档，不是暴露）。这条口径与判据⑦/⑧ 逐字一致。
     *
     * <h2>🛑🛑 判定必须用【注解简单名】而不是字面子串（本轮受控注入抓到的一处真实盲区）</h2>
     * 初版沿用判据⑦/⑧ 的 {@code code.contains("@RestController")} 字面匹配。
     * 受控注入时用<b>全限定名</b>写注解：
     * <pre>
     *   @org.springframework.web.bind.annotation.RestController
     *   public class DeviceService { ... }
     * </pre>
     * ⇒ {@code contains("@RestController")} <b>不成立</b>（子串是
     * {@code ".RestController"}，而 {@code @} 与 {@code R} 之间隔着包名）——
     * 于是<b>一个真的把运维通路暴露成 HTTP 端点的改动，本判据全绿通过</b>。
     * <p>这是本仓反复记录过的形态：<b>「门禁看不见它要看的东西」= 一条假绿</b>。
     * 故修法是让判定正确 —— 见 {@link #HTTP_MAPPING_PATTERN_FOR} 的正则构造说明。
     */
    private static void assertNoHttpMapping(Path root, List<String> rels,
                                            String what, String reason) throws IOException {
        List<String> offenders = httpMappingOffenders(root, rels);
        assertTrue(offenders.isEmpty(),
                what + "被暴露成了 HTTP 端点 —— 这与契约化决策相悖。" + reason
                        + "若这是有意的范围扩张，正确顺序是：先改契约 + 定角色 → 再落码 → "
                        + "最后同步 README / 端点台账 / 三端 SDK。命中：" + offenders);
    }

    /**
     * 断言契约里不存在"以某资源为对象创建/修改"的写端点（判据⑨d / ⑩d 共用）。
     *
     * <h2>🛑 为什么必须用【路径段】匹配而不是子串包含</h2>
     * 契约里有 {@code /scale-item-banks}（C1 题库）与 {@code /device-dispatches}（D6 下发），
     * 它们都以目标资源名开头。若用 {@code contains("/scale")} 之类的子串判定，
     * 会把这两个<b>合法</b>路径误判成红 —— 而<b>假阳性会诱使人放宽判据</b>，
     * 那比没有判据更坏（一条被放宽的判据会继续给出"全绿"的错觉）。
     * <p>故本方法把 path 按 {@code /} 切段，只在<b>恰好等于</b>资源名的那一段上判。
     *
     * <h2>🛑 为什么命中条件是显式的局部变量</h2>
     * 判据⑧ 的初版写作 {@code A || B && C && D}，而 {@code &&} 优先于 {@code ||}
     * ⇒ 实际语义与作者意图不同，会让一条只读路径报红。
     * 故这里把每一维判定拆成具名局部变量，让意图在代码里可见、可被复查。
     *
     * @param resource            目标资源名（如 {@code device}）。判据：路径段恰好等于它
     * @param uploadHttpMethodPath 一个"看起来像写、但语义是上报/下发"的合法路径
     *                            （{@code null} 表示本资源没有这种例外）
     */
    private static void assertNoContractResourceWrite(Path root, String resource,
                                                      String allowPathExactly, String why)
            throws IOException {
        Path contract = root.getParent().resolve(CONTRACT_RELATIVE);
        Map<String, String> blocks = contractPathBlocks(
                Files.readString(contract, StandardCharsets.UTF_8));

        String singular = "/" + resource;
        String plural = "/" + resource + "s";

        for (String p : blocks.keySet()) {
            String lower = p.toLowerCase();
            java.util.List<String> segments = new java.util.ArrayList<>();
            for (String seg : lower.split("/")) {
                if (!seg.isEmpty()) {
                    segments.add(seg);
                }
            }
            // ① 该 path 是否是"以本资源为对象的资源型路径"？
            boolean isResourcePath = segments.contains(resource)
                    || segments.contains(resource + "s")
                    || lower.equals(singular)
                    || lower.equals(plural);
            // ② 该 path 是否带写方法？
            boolean hasWrite = hasWriteMethod(blocks.get(p));
            // ③ 是否是显式豁免的那一条（语义为上报/下发，不是"创建台账"）？
            boolean isAllowed = allowPathExactly != null && lower.equals(allowPathExactly);

            boolean isViolation = isResourcePath && hasWrite && !isAllowed;
            assertFalse(isViolation,
                    "契约里出现了以 " + resource + " 为对象创建/修改的写端点（" + p + "）—— " + why + "。"
                            + "这一变更必须走契约 MAJOR 流程，本判据的存在就是为了让它"
                            + "无法在一次『顺手』中发生。"
                            + "⚠️ 注意本判定按【路径段】匹配而不是子串："
                            + "`/" + resource + "-xxx` 形态的既有合法路径（如 scale-item-banks / "
                            + "device-dispatches）不属本条命中范围 —— "
                            + "子串匹配会误报，而误报会诱使人放宽判据");
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 迁移里建的表 → 建它的迁移文件名（机械提取，剥 SQL 注释）。 */
    private static Map<String, String> migratedTables() throws IOException {
        Path dir = skeletonRoot().resolve(MIGRATION_DIR);
        assertTrue(Files.isDirectory(dir), "迁移目录不存在：" + dir);

        Map<String, String> out = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (var walk = Files.list(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !p.getFileName().toString().contains(".bak"))
                    .forEach(files::add);
        }
        assertFalse(files.isEmpty(), "迁移目录里一个 .sql 都没有：" + dir);

        for (Path f : files) {
            String clean = stripSqlComments(Files.readString(f, StandardCharsets.UTF_8));
            Matcher m = CREATE_TABLE.matcher(clean);
            while (m.find()) {
                out.putIfAbsent(m.group(1).toLowerCase(), f.getFileName().toString());
            }
        }
        return out;
    }

    /** 契约 path → 该 path 到下一个 path 之间的文本块。 */
    private static Map<String, String> contractPathBlocks(String text) {
        List<int[]> marks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = CONTRACT_PATH.matcher(text);
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            names.add(m.group(1));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < marks.size(); i++) {
            int end = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : text.length();
            out.put(names.get(i), text.substring(marks.get(i)[1], end));
        }
        return out;
    }

    private static boolean hasWriteMethod(String block) {
        return WRITE_METHOD.matcher(block).find();
    }

    /** 全部生产 Java 源码（剥注释），键 = 相对 skeleton 根的路径。 */
    private static Map<String, String> productionSourcesStrippedOfComments() throws IOException {
        Path root = skeletonRoot();
        Map<String, String> out = new LinkedHashMap<>();
        for (String mod : SCANNED_MODULES) {
            Path base = root.resolve(mod).resolve("src/main/java");
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (var walk = Files.walk(base)) {
                List<Path> fs = new ArrayList<>();
                walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".java"))
                        .forEach(fs::add);
                for (Path p : fs) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.put(rel, stripComments(Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        assertFalse(out.isEmpty(), "未扫到任何生产 Java 文件 —— 路径解析失败，断言会平凡通过");
        return out;
    }

    /** 交付物 SQL（迁移 + 配置真相源资产），剥注释，键 = 相对 skeleton 根的路径。 */
    private static Map<String, String> deliverySqlStrippedOfComments() throws IOException {
        Path root = skeletonRoot();
        Map<String, String> out = new LinkedHashMap<>();
        for (String dir : List.of(MIGRATION_DIR, "dy-config/src/main/resources/db/config")) {
            Path base = root.resolve(dir);
            if (!Files.isDirectory(base)) {
                continue;
            }
            List<Path> fs = new ArrayList<>();
            try (var walk = Files.list(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".sql"))
                        .filter(p -> !p.getFileName().toString().contains(".bak"))
                        .forEach(fs::add);
            }
            for (Path p : fs) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.put(rel, stripSqlComments(Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertFalse(out.isEmpty(), "未扫到任何交付物 SQL —— 路径解析失败");
        return out;
    }

    private static boolean hasWriterIn(Map<String, String> java, Map<String, String> sql, String table) {
        return !writersIn(java, sql, table).isEmpty();
    }

    /** 返回确实含该表写入方的文件清单（相对路径；java 与 sql 合起来）。 */
    private static List<String> writersIn(Map<String, String> java, Map<String, String> sql, String table) {
        Pattern p = Pattern.compile("INSERT\\s+INTO\\s+" + Pattern.quote(table) + "\\b",
                Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : java.entrySet()) {
            if (p.matcher(e.getValue()).find()) {
                hits.add(e.getKey());
            }
        }
        for (Map.Entry<String, String> e : sql.entrySet()) {
            if (p.matcher(e.getValue()).find()) {
                hits.add(e.getKey());
            }
        }
        return hits;
    }

    private static String describe(Set<String> tables, Map<String, String> migrated) {
        List<String> parts = new ArrayList<>();
        for (String t : tables) {
            parts.add(t + " ← " + migrated.getOrDefault(t, "?"));
        }
        return String.join(" ; ", parts);
    }

    /**
     * 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>（表名就在 SQL 字符串里）。
     *
     * <p>🛑 不能只做正则替换：本仓注释里大量出现中文与反引号，且 Javadoc 里逐字写着
     * {@code INSERT INTO tenant}（正是"注释-only 引用"）。必须真正按状态机扫，
     * 才能区分"注释里提到"与"代码里引用"—— 该能力由第 ⑤ 例机械自证。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        final int n = src.length();
        int state = 0;   // 0=code 1=line 2=block 3=string
        while (i < n) {
            char c = src.charAt(i);
            switch (state) {
                case 0 -> {
                    if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                        state = 1;
                        i += 2;
                    } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                        state = 2;
                        i += 2;
                    } else if (c == '"') {
                        state = 3;
                        out.append(c);
                        i++;
                    } else {
                        out.append(c);
                        i++;
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        state = 0;
                        out.append(c);
                    }
                    i++;
                }
                case 2 -> {
                    if (c == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        state = 0;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                default -> {   // string
                    if (c == '\\' && i + 1 < n) {
                        out.append(c);
                        out.append(src.charAt(i + 1));
                        i += 2;
                    } else {
                        if (c == '"') {
                            state = 0;
                        }
                        out.append(c);
                        i++;
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * 剥离 SQL 注释（{@code --} 与 {@code /* *\/}），但<b>保留美元引用块</b>
     * （{@code $tag$ ... $tag$}）与字符串字面量里的原文 —— 触发器函数体就在前者里，
     * 而 {@code INSERT INTO app_config_history} 恰恰写在函数体里。
     */
    private static String stripSqlComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        final int n = src.length();
        String dollar = null;
        while (i < n) {
            char c = src.charAt(i);
            if (dollar != null) {
                if (src.startsWith(dollar, i)) {
                    out.append(dollar);
                    i += dollar.length();
                    dollar = null;
                } else {
                    out.append(c);
                    i++;
                }
                continue;
            }
            if (c == '$') {
                Matcher m = Pattern.compile("\\$[A-Za-z_]*\\$").matcher(src.substring(i));
                if (m.lookingAt()) {
                    dollar = m.group();
                    out.append(dollar);
                    i += dollar.length();
                    continue;
                }
            }
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    out.append(src.charAt(i));
                    if (src.charAt(i) == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 从 surefire 的 cwd 逐级上溯定位 skeleton 根（不依赖硬编码盘符）。 */
    private static Path skeletonRoot() {
        for (Path cur = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("dy-app").resolve("pom.xml"))
                    && Files.isRegularFile(cur.resolve("dy-config")
                    .resolve("src/main/resources/db/config/02_slots_seed.sql"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "未找到 skeleton 根（需含 dy-app/pom.xml 与 dy-config 的 02_slots_seed.sql）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }
}