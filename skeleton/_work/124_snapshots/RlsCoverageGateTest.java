package com.diaoyuanyun.dy.app.rls;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RLS 隔离覆盖门禁 —— ADR-02 第 3 层："租户表无对应隔离测试即构建失败"。
 *
 * <h2>它堵的是什么洞</h2>
 * 只有 {@link RlsTenantIsolationTest} 时，覆盖是"人肉维护"的：
 * 下一个人新增一张带 {@code tenant_id} 的表，只要不写它的隔离测试，
 * 构建照样 BUILD SUCCESS —— 租户泄漏的缺口就此静默进入主干。
 *
 * <p>本门禁把"覆盖"变成<b>构建期强制事实</b>，两个维度同时校验：
 * <ol>
 *   <li><b>迁移侧（静态）</b>：从交付物的<b>真实迁移文件</b>里解析出所有含 {@code tenant_id} 的表，
 *       每一张都必须在 {@link #ISOLATION_TESTS} 登记；新增表未登记 → 构建失败。</li>
 *   <li><b>真库侧（动态）</b>：每一张这样的表在<b>真实 PostgreSQL</b> 里都必须
 *       {@code ENABLE} + {@code FORCE} ROW LEVEL SECURITY，且至少存在一条策略。
 *       漏了 {@code FORCE}、或忘了建策略 → 构建失败。</li>
 * </ol>
 *
 * <h2>它为什么不是自证式断言</h2>
 * 第 2 维度断言的是 {@code pg_class} / {@code pg_policies} 里<b>真实数据库的真实元数据</b>，
 * 不是代码里的常量。第 1 维度的"登记表"是 Harness，其价值在于把"新增表"变成必须表态的事件；
 * 真正证明隔离的是 {@link RlsTenantIsolationTest} 的行为断言。
 *
 * <h2>删掉隔离测试会怎样</h2>
 * {@link #every_tenant_table_has_a_loadable_isolation_test()} 用 {@code Class.forName} 加载登记的
 * 测试类。删掉或改名 {@code RlsTenantIsolationTest} 会让它抛 {@code ClassNotFoundException} → 构建失败。
 */
class RlsCoverageGateTest {

    /** V5 的 24 张余量表统一由 {@link RlsV5EntityIsolationTest} 覆盖（断言逐表执行，非抽样）。 */
    private static final String V5 = "com.diaoyuanyun.dy.app.rls.RlsV5EntityIsolationTest";

    /**
     * V6 的 3 张退款域留痕账本由 {@link RlsV6RefundLedgerIsolationTest} 覆盖。
     *
     * <p>同样<b>不</b>并入 {@link #V5}：那个类自称"24 张 V5 表"并在内部断言
     * {@code TABLES.size() == 24}，塞进 V6 的表会让它的名字开始说谎。
     */
    private static final String V6 = "com.diaoyuanyun.dy.app.rls.RlsV6RefundLedgerIsolationTest";

    /**
     * V7 的 1 张建档修订留痕账本（{@code intake_profile_revision}）由
     * {@link RlsV7IntakeProfileRevisionIsolationTest} 覆盖。
     *
     * <p>同样<b>不</b>并入 {@link #V5} / {@link #V6}：那两个类各自自称"24 张 V5 表" /
     * "3 张 V6 表"并在内部断言自己的表数，塞进 V7 的表会让它们的名字开始说谎。
     *
     * <p>🛑 V7 与 V5/V6 有一处**结构性差别**，是它独立成类的主要理由之一：
     * V5/V6 的表全是 {@code CREATE TABLE} 新增；而 V7 的主体是
     * 「给 {@code customer} 补 5 列 + 建 1 张新表」—— 前者<b>不触碰</b>
     * 本覆盖门禁的三方交叉断言（{@code customer} 早已登记且仍带 {@code tenant_id}），
     * 后者则**必须**登记。故本类要验证的是"新增的 1 张表到位、且补列这件事
     * 确实没有把既有 {@code customer} 的 RLS 弄坏"。
     */
    private static final String V7 = "com.diaoyuanyun.dy.app.rls.RlsV7IntakeProfileRevisionIsolationTest";

    /**
     * V11 的 3 张<b>密钥材料表</b>（{@code tenant_kek} / {@code subject_dek} /
     * {@code subject_key_tombstone}）由 {@link RlsV11CryptoKeyIsolationTest} 覆盖。
     *
     * <p>同样<b>不</b>并入邻类：V5/V6/V7 各自断言自己的表数（24 / 3 / 1），
     * 而 {@link RlsBEntityIsolationTest} 的名字写着"B 类实体"（客户级业务实体）——
     * 密钥材料既不是客户级，也不是业务实体。塞进去会让那些类的名字开始说谎，
     * 而"名字说谎的测试类"正是下一个排查缺口的人最先被误导的地方。
     *
     * <p>🛑 <b>为什么密钥表更该被登记，而不是"反正有 RLS 就够了"</b>：
     * 登记表的价值在于把"新增租户表"变成<b>必须表态的事件</b>。
     * 对密钥表，这个表态尤其不能省 —— 因为它们的 RLS 一旦缺失，
     * 泄漏的是<b>密钥材料</b>而不是一条业务记录（泄漏性质不同，见该类注释）。
     * 若把它们放进 {@link #NON_TENANT_TABLES} 豁免，就会造出
     * "密钥表没有隔离覆盖"被静默放过的形态 —— 那正是本门禁要堵的洞。
     */
    private static final String V11 = "com.diaoyuanyun.dy.app.rls.RlsV11CryptoKeyIsolationTest";

    /**
     * V14 的 2 张<b>配置真相源租户表</b>（{@code app_config} / {@code app_config_history}）
     * 由 {@link RlsV14ConfigTruthSourceIsolationTest} 覆盖。
     *
     * <p><b>2026-09-26 登记（V14 迁移 · B-3 配置真相源落应用库）</b>：
     * {@code V14__config_truth_source_tables.sql} 把 ADR-08 的配置真相源三表
     * 从 dy-config 的独立门禁库<b>落到应用库</b> —— {@code config_slot}（总部层声明）、
     * {@code app_config}（租户层生效值，运行时唯一读路径）、
     * {@code app_config_history}（who/when/before/after 变更留痕）。
     *
     * <p>🛑 <b>只有 2 张进本登记表，{@code config_slot} 刻意不进</b>（这不是遗漏）：
     * 它是总部层声明表，<b>不含 {@code tenant_id}、不启用 RLS</b>，
     * 性质同 {@code tenant} / {@code schema_migration} 这类框架表（对所有租户同一份，
     * 没有"租户的行"可隔离）。塞进这里会让本类的"僵尸登记"检查报红；
     * 塞进 {@link #NON_TENANT_TABLES} 豁免又会造出"总部声明表无人值守"的形态。
     * 故它的纪律（46 条声明 + {@code #42} 永久空号 + {@code #47} 暂缺）由
     * {@link RlsV14ConfigTruthSourceIsolationTest} 内的专门断言承载，
     * 而不是硬把它登记成一张"租户表"。
     *
     * <p>为什么不并入邻类：V5/V6/V7/V11 各自断言自己的表数与交付范围，
     * 配置真相源既不是客户级业务实体，也不是密钥材料 ——
     * 它是<b>驱动全部判定的政策参数载体</b>，串租户泄漏的是对方的合规边界。
     * 另有一处与邻居<b>恰好相反</b>的纪律值得单独断言：{@code app_config_history}
     * 的 append-only 用 BEFORE 触发器 {@code RAISE 42501}（<b>响亮失败</b>），
     * 而 V11 墓碑表用 RULE {@code DO INSTEAD NOTHING}（<b>静默无效</b>）——
     * 照抄 V11 的断言形态会对一条真正生效的保护给出假绿。
     *
     * <p>同样必须把该类登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate}
     * execution 的 {@code <include>}（并在 {@code default-test} 的 {@code <exclude>} 里排除，
     * 否则会被"默认执行"一遍并与门禁 execution 争用同一条库）。
     */
    private static final String V14 = "com.diaoyuanyun.dy.app.rls.RlsV14ConfigTruthSourceIsolationTest";

    /**
     * 租户表 → 覆盖它的隔离测试类（FQN）。
     *
     * <p>新增带 {@code tenant_id} 的表时，必须在此登记并补齐测试；否则
     * {@link #every_tenant_table_has_a_registered_isolation_test()} 直接红。
     *
     * <p><b>为什么登记"类名字符串"而不是 {@code XxxTest.class}</b>：用 {@code .class} 会建立
     * 编译期引用，隔离测试被删除时本类连编译都过不去（报 {@code cannot find symbol}），
     * 既看不出门禁的意图，也掩盖了"覆盖登记表"本身的语义。用字符串 + {@code Class.forName}
     * 换取一条自解释的失败信息：<i>"租户表 customer 登记的隔离测试类不存在"</i>。
     *
     * <p><b>2026-09-22 登记（V2 迁移 · B 类实体）</b>：{@code customer_state_transition}
     * （14 态状态机实体，data-dict §2.25 / BD-3·F-4 落盘）与 {@code band}
     * （客户级手环实体，§2.26 / BD-3·F-3 落盘）两张表随 V2 迁移进入 schema。
     * 按 ADR-02 第 3 层「租户表无隔离测试即构建失败」，二者必须在此登记并各自有真库隔离断言 ——
     * 故二者均指向 {@link RlsBEntityIsolationTest}（该类的断言对两表逐一执行，不是只测其一）。
     *
     * <p><b>2026-09-23 登记（V3 迁移 · F-2 定案）</b>：技术负责人对 F-2 拍定 = 方案 A（长表
     * {@code metric} 化），骨架新增 {@code V3__band_telemetry_metric_long_table.sql} 建出
     * {@code band_telemetry}（客户级手环遥测，data-dict §2.17【已定案 · F-2】）。
     * 该表带 {@code tenant_id}，按 ADR-02 第 3 层必须在此登记并补齐真库隔离断言 ——
     * 故指向 {@link RlsBEntityIsolationTest}（该类对登记的表逐一执行断言）。
     *
     * <p>⚠️ 历史记录：F-2 未定案前，本表<b>刻意不在登记表内</b>（当时迁移里不存在该表，
     * 登记它会触发下方的"僵尸登记"检查）。F-2 定案并落 V3 后，该豁免随之取消。
     *
     * <p><b>2026-09-23 登记（V4 迁移 · S1-4 题库）</b>：{@code scale_item_bank}
     * （分龄量表题库内容资产，字典 §2.24 / 附录 C.1.8 · ★）随 V4 迁移进入 schema。
     * 该表带 {@code tenant_id}，按 ADR-02 第 3 层必须在此登记并补齐真库隔离断言。
     * 它<b>不</b>并入 {@link RlsBEntityIsolationTest}（那个类的名字与职责写着"B 类实体"，
     * 而本表是<b>总部维护的内容资产</b>，没有 {@code customer_id}、按年龄组组织 ——
     * 塞进去会让那个类的名字开始说谎），故指向专门的
     * {@link RlsScaleItemBankIsolationTest}。该测试类同时登记在
     * {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution 里 ——
     * 不登记 include 它会"存在但不执行"，登记表就变成了一纸空文。
     *
     * <p><b>2026-09-24 登记（V5 迁移 · S1-2 余量 24 表）</b>：{@code V5__remaining_entities_org_
     * journey_verdict_refund.sql} 一次补齐 S1-2「22 实体建模」的全部余量 ——
     * 组织台账（region/store/staff/device）、入组链（screening_record/consent/scale/plan/
     * agreement/baseline_assessment/plan_review）、履约链（device_dispatch/visit/daily_report）、
     * 判定链（cycle_assessment/verdict）、退款结案链（refund/retention/case_archive）、
     * ★ 建档与模板（intake_profile/doc_template）、手环补拉引擎三表
     * （band_sync_probe/band_sync_log/band_daily_coverage）= <b>24 张</b>。
     * 全部带 {@code tenant_id} 且已 FAIL-CLOSED 建策略，按 ADR-02 第 3 层必须逐表登记。
     *
     * <p>它们统一指向 {@link RlsV5EntityIsolationTest}（该类的断言对 24 表<b>逐表各执行一遍</b>，
     * 不是"测了其中一张就代表其余"）。同样必须把该类登记进
     * {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution（含 default-test 的 excludes）。
     *
     * <p><b>2026-09-24 登记（V6 迁移 · S2-1 退款域留痕账本）</b>：{@code V6__refund_domain_
     * alignment_and_ledgers.sql} 为契约域 G（退款与挽留）补齐三张 <b>append-only 留痕账本</b> ——
     * {@code refund_statement}（客户原话账本，P0-19「不可编辑，更正须追加」）、
     * {@code refund_receipt}（回执三态留痕）、{@code refund_offline_notice}（转线下告知留痕）。
     * 三张表全部 {@code ENABLE} + {@code FORCE} RLS 且回填 {@code tenant_id}，
     * 按 ADR-02 第 3 层必须逐表登记并补齐真库读写隔离断言。
     *
     * <p>它们指向 {@link RlsV6RefundLedgerIsolationTest}（<b>独立类</b>，不并入 V5）：
     * V5 类的名字与内部自证都写着"恰 24 张"，把 V6 的 3 张塞进去会让它的名字开始说谎 ——
     * 而"名字说谎的测试类"正是下一个排查 RLS 缺口的人最先被误导的地方。
     * 同样必须把该类登记进 {@code dy-app/pom.xml} 的 {@code rls-isolation-gate} execution
     * 的 {@code <include>}（漏了它会"存在但不执行"，登记表变一纸空文）。
     */
    private static final Map<String, String> ISOLATION_TESTS = Map.ofEntries(
            Map.entry("customer", "com.diaoyuanyun.dy.app.rls.RlsTenantIsolationTest"),
            Map.entry("customer_state_transition", "com.diaoyuanyun.dy.app.rls.RlsBEntityIsolationTest"),
            Map.entry("band", "com.diaoyuanyun.dy.app.rls.RlsBEntityIsolationTest"),
            Map.entry("band_telemetry", "com.diaoyuanyun.dy.app.rls.RlsBEntityIsolationTest"),
            Map.entry("scale_item_bank", "com.diaoyuanyun.dy.app.rls.RlsScaleItemBankIsolationTest"),
            // ---- V5 · S1-2 余量 24 表（2026-09-24）----
            Map.entry("region", V5),
            Map.entry("store", V5),
            Map.entry("staff", V5),
            Map.entry("device", V5),
            Map.entry("screening_record", V5),
            Map.entry("consent", V5),
            Map.entry("scale", V5),
            Map.entry("plan", V5),
            Map.entry("agreement", V5),
            Map.entry("baseline_assessment", V5),
            Map.entry("plan_review", V5),
            Map.entry("device_dispatch", V5),
            Map.entry("visit", V5),
            Map.entry("daily_report", V5),
            Map.entry("cycle_assessment", V5),
            Map.entry("verdict", V5),
            Map.entry("refund", V5),
            Map.entry("retention", V5),
            Map.entry("case_archive", V5),
            Map.entry("intake_profile", V5),
            Map.entry("doc_template", V5),
            Map.entry("band_sync_probe", V5),
            Map.entry("band_sync_log", V5),
            Map.entry("band_daily_coverage", V5),
            // ---- V6 · S2-1 退款域留痕账本 3 表（2026-09-24）----
            Map.entry("refund_statement", V6),
            Map.entry("refund_receipt", V6),
            Map.entry("refund_offline_notice", V6),
            // ---- V7 · S2-10 契约域 B 建档修订留痕账本 1 表（2026-09-26）----
            // 🛑 只登记新表。V7 的其余操作是「给既有 customer 补 5 列」——
            //    那是 ALTER TABLE ADD COLUMN，不新增带 tenant_id 的表，
            //    故 customer 的登记行（早已存在）保持原样、指向 RlsTenantIsolationTest。
            //    "补列也要重新登记"是一个常见的误判：登记表按【表】而不是按【表×迁移】组织，
            //    而 V7 没有让 customer 变成一张新表。
            Map.entry("intake_profile_revision", V7),
            // ---- V11 · B-1 密钥材料表 3 张（2026-09-26）----
            // 🛑 这三张表【不得】放进 NON_TENANT_TABLES：它们都有 tenant_id 且已 FORCE RLS，
            //    放进豁免会让"密钥表无隔离覆盖"被静默放过 —— 而密钥表泄漏的是密钥材料。
            //    它们指向 RlsV11CryptoKeyIsolationTest（断言对 3 张表逐一执行，
            //    且额外覆盖"只追加/不可改"三种不同实现手段的纪律）。
            Map.entry("tenant_kek", V11),
            Map.entry("subject_dek", V11),
            Map.entry("subject_key_tombstone", V11),
            // ---- V14 · B-3 配置真相源租户表 2 张（2026-09-26）----
            // 🛑 只登记这 2 张。config_slot 是总部层声明表（无 tenant_id、无 RLS），
            //    登记它会被下方的"僵尸登记"检查报红 —— 详见 V14 常量的 javadoc。
            Map.entry("app_config", V14),
            Map.entry("app_config_history", V14));

    /** 租户宿主表本身不参与 RLS（它是租户的容器，见 V1 脚本无策略），故豁免。 */
    private static final Set<String> TENANT_HOST_TABLES = Set.of("tenant");

    /**
     * 查找登记测试类源码时的模块搜索面（与 {@code RlsInjectionRealityGateTest} 同一套模块口径）。
     *
     * <p>🛑 为什么需要它：{@code ISOLATION_TESTS} 的值是 <b>FQN 字符串</b>，
     * 而"登记咬合"判据（A-4）要把 FQN 反查回<b>源码文件</b>再扫表名。
     *
     * <p>🛑 为什么必须是<b>全集</b>、不能只留 {@code dy-app}（2026-09-30 更正）：
     * <ul>
     *   <li>实测登记表**当前 38/38 恰好全部**落在 {@code dy-app}
     *       （{@code V5}×24 · {@code V6}×3 · {@code V11}×3 · {@code V14}×2 · {@code V7}×1，
     *       外加直接写 FQN 的 5 条）—— 所以"只留 {@code dy-app}"与"全集"在**今天**是等价的；</li>
     *   <li>但登记表**允许登记任何模块**的测试类。若搜索面收窄到 {@code dy-app}，
     *       一旦未来出现<b>跨模块</b>登记（例如把某张表的隔离测试放到 {@code dy-tenancy}），
     *       本判据会把<b>合法的登记</b>报成"登记了不存在的类"—— 即<b>假红</b>。</li>
     * </ul>
     * ⇒ 故保留全集，使本判据**只在真的找不到源码时**才红。
     *
     * <p>🛑 <b>2026-09-30 更正一处不实陈述</b>：本注释旧版写的是
     * "登记表里已出现 {@code dy-config} 等模块的类" —— <b>与实测不符</b>
     * （当时 38/38 全在 {@code dy-app}，{@code dy-config} 一次都没出现）。
     * 真正的理由是上面的"防假红"，<b>不是</b>"已在使用"。
     */
    private static final List<String> TEST_SOURCE_MODULES = List.of(
            "dy-common", "dy-tenancy", "dy-security", "dy-web",
            "dy-audit", "dy-config", "dy-crypto", "dy-app");

    /**
     * 无 RLS 策略、因而不纳入租户隔离覆盖的<b>框架表</b>。
     *
     * <h2>🛑 这个豁免的真正理由（2026-09-23 更正 —— 旧注释写错了）</h2>
     * 旧注释写的是"不承载业务行、<b>无租户语义</b>的框架表"。**该理由不成立**，
     * 且两处都被 schema 直接否定：
     * <ul>
     *   <li>{@code audit_log.tenant_id} 是 {@code UUID NOT NULL}（见 V1 迁移），
     *       <b>有</b>租户语义；</li>
     *   <li>它还承载业务行：{@code actor} / {@code target_type} / {@code target_id} / {@code payload}
     *       —— 其中 {@code payload} 可含业务详情。</li>
     * </ul>
     * <b>真正的豁免理由</b>：审计日志是一条<b>全局单链</b>哈希链 ——
     * 写入时取"上一行的 hash"作为 {@code prev_hash}
     * （{@code JdbcAuditLogService} 的 {@code SELECT hash FROM audit_log ORDER BY created_at DESC, id DESC LIMIT 1}，
     * <b>无租户过滤</b>）。若给它加上按租户隔离的 RLS 读策略，
     * 写入方就<b>读不到上一租户的那一行 hash</b>，链会当场断掉。
     * 即：本表不加 RLS 是<b>哈希链连续性</b>的要求，与"有没有租户语义"无关。
     *
     * <h2>⚠️ 该豁免带来的真实敞口（已登记为待裁定，勿当成"已解决"）</h2>
     * 既然有 {@code tenant_id} 又无 RLS，则<b>行级读隔离在本表上不存在</b> ——
     * 应用侧若不加 {@code WHERE tenant_id = ...}，A 租户就可能读到 B 租户的审计行。
     * 目前尚未接入真实读路径，且删除权/加密写入路径也未上线，故<b>敞口已识别、尚未泄漏</b>。
     * 处置方向（具约束力）：
     * <ol>
     *   <li><b>不得</b>因为"本表被豁免 RLS"就把租户级敏感个人信息写进 {@code payload}
     *       —— 豁免是针对 <i>RLS 覆盖</i> 的，不是针对 <i>数据敏感度</i> 的；</li>
     *   <li>审计日志的租户读隔离应通过<b>查询层强制</b>（仓储/视图统一注入 {@code WHERE tenant_id}）
     *       或<b>分区 + 逐租户视图</b>实现，而不是在 {@code audit_log} 上直接加行级策略；</li>
     *   <li>该敞口已登记为待裁定项（见交付目录 {@code 待裁定单/}），
     *       不因本注释的更正而关闭。</li>
     * </ol>
     *
     * <p>为什么把这段写在这里而不是留在某份文档里：<b>豁免列表就在本文件内，
     * 下一个要改它的人一定先读到这里。</b>一条写错理由的豁免，比一条没有理由的豁免更危险 ——
     * 它会让人放心地在这张表上继续加不该加的东西。
     */
    private static final Set<String> NON_TENANT_TABLES = Set.of("schema_migration", "audit_log");

    private static JdbcTemplate jdbc;
    private static List<String> migrationSqls;
    /** 构建级互斥锁：覆盖本类整个执行期（Task #18 构建间互斥）。 */
    private static RlsGateSupport.BuildMutex buildMutex;

    @BeforeAll
    static void loadArtifacts() throws Exception {
        // 先取构建级互斥锁：本类也要 provision，若与另一个构建的断言阶段重叠，
        // 双方会把对方的真库内容改掉（症状是随机行数/连接错误，看不出真因）。
        buildMutex = RlsGateSupport.acquireBuildMutex();
        RlsGateSupport.provisionRealDatabase();

        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:postgresql://" + RlsGateSupport.host() + ":" + RlsGateSupport.port() + "/" + RlsGateSupport.DB,
                RlsGateSupport.APP_USER, RlsGateSupport.APP_PASSWORD, true);
        jdbc = new JdbcTemplate(ds);

        // 读【交付物源文件】而不是 classpath 副本：门禁必须盯着真正要交付的那份迁移。
        Path migrationDir = RlsGateSupport.skeletonRoot()
                .resolve("dy-app/src/main/resources/db/migration");
        assertTrue(Files.isDirectory(migrationDir), "迁移目录必须存在: " + migrationDir);
        migrationSqls = new ArrayList<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                migrationSqls.add(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        assertFalse(migrationSqls.isEmpty(), "迁移目录里必须至少有一个 .sql");
    }

    /** 释放构建级互斥锁（Task #18）。 */
    @AfterAll
    static void releaseBuildMutex() {
        if (buildMutex != null) {
            buildMutex.close();
            buildMutex = null;
        }
    }

    @Test
    @DisplayName("迁移中每个含 tenant_id 的表都必须登记了隔离测试（新增表未登记 = 构建失败）")
    void every_tenant_table_has_a_registered_isolation_test() throws IOException {
        Set<String> tenantTables = tenantTablesFromMigrations();
        assertFalse(tenantTables.isEmpty(), "未能从迁移脚本中解析出任何租户表 —— 解析器失效，门禁形同虚设");

        Set<String> unregistered = new LinkedHashSet<>(tenantTables);
        unregistered.removeAll(ISOLATION_TESTS.keySet());
        if (!unregistered.isEmpty()) {
            throw new AssertionError(
                    "以下租户表没有对应的 RLS 隔离测试（ADR-02 L3：无隔离测试即构建失败）: " + unregistered
                            + "\n请在 " + RlsCoverageGateTest.class.getSimpleName() + ".ISOLATION_TESTS 中登记，"
                            + "并补齐真实数据库的读写隔离断言。已登记的: " + tenantTables);
        }

        // 登记表不得包含"迁移里已不存在"的表，避免僵尸登记掩盖真实覆盖
        Set<String> stale = new LinkedHashSet<>(ISOLATION_TESTS.keySet());
        stale.removeAll(tenantTables);
        assertTrue(stale.isEmpty(),
                "登记了迁移中不存在的表（僵尸登记会掩盖真实覆盖缺口）: " + stale);
    }

    @Test
    @DisplayName("三方交叉：迁移声明的租户表 == 登记的租户表 == 真库实际含 tenant_id 的表")
    void three_sources_must_agree_on_the_set_of_tenant_tables() {
        // 单一来源可被"顺带改掉"（改迁移就顺手改登记）。这里用【三个独立来源】交叉校验：
        //   ① 迁移文件文本（解析 CREATE TABLE 体）
        //   ② ISOLATION_TESTS 登记表（人写的覆盖承诺）
        //   ③ 真库 information_schema.columns（DDL 真正落库后的实际状态）
        // 任何一方被单独改动（新增表忘登记 / 删登记 / 加了表没跑迁移）都会立刻不一致 → 构建失败。
        Set<String> fromMigration = tenantTablesFromMigrations();

        Set<String> fromRealDb = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'tenant_id' "
                        + "ORDER BY table_name",
                String.class));
        // 豁免：租户宿主表与框架表（与静态解析同一套豁免口径）
        fromRealDb.removeAll(TENANT_HOST_TABLES);
        fromRealDb.removeAll(NON_TENANT_TABLES);

        Set<String> registered = new LinkedHashSet<>(ISOLATION_TESTS.keySet());

        assertEquals(fromMigration, fromRealDb,
                "迁移脚本声明的租户表与真库实际的租户表不一致 —— "
                        + "要么迁移没被真正应用，要么有表绕过迁移被手工创建。迁移=" + fromMigration
                        + " 真库=" + fromRealDb);
        assertEquals(fromMigration, registered,
                "租户表的【登记覆盖】与迁移不一致（ADR-02 L3：无隔离测试即构建失败）。迁移="
                        + fromMigration + " 登记=" + registered);

        assertFalse(fromMigration.isEmpty(),
                "三方交叉的租户表集合为空 —— 解析/查询失效，门禁形同虚设");
    }

    @Test
    @DisplayName("登记的隔离测试类必须可加载（删掉/改名隔离测试 = 构建失败）")
    void every_tenant_table_has_a_loadable_isolation_test() {
        assertFalse(ISOLATION_TESTS.isEmpty(), "隔离测试登记表不得为空");
        for (Map.Entry<String, String> e : ISOLATION_TESTS.entrySet()) {
            try {
                Class<?> c = Class.forName(e.getValue());
                assertNotNull(c, "隔离测试类必须可加载: " + e.getValue());
                long testMethods = Stream.of(c.getDeclaredMethods())
                        .filter(m -> m.isAnnotationPresent(Test.class))
                        .count();
                assertTrue(testMethods >= 5,
                        "隔离测试类 " + e.getValue() + " 只有 " + testMethods
                                + " 个 @Test —— 覆盖" + e.getKey() + " 的隔离断言过少，不足以作为门禁");
            } catch (ClassNotFoundException ex) {
                throw new AssertionError(
                        "租户表 '" + e.getKey() + "' 登记的隔离测试类不存在: " + e.getValue()
                                + "（ADR-02 L3 要求：缺该测试即构建失败）", ex);
            }
        }
    }

    @Test
    @DisplayName("真库中每个租户表都必须 ENABLE + FORCE RLS 且至少有 1 条策略")
    void every_tenant_table_has_force_rls_in_the_real_database() {
        Set<String> tenantTables = tenantTablesFromMigrations();
        List<String> problems = new ArrayList<>();

        for (String table : tenantTables) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner) AS owner "
                            + "FROM pg_class WHERE relname = ? AND relkind = 'r'", table);

            boolean enabled = Boolean.TRUE.equals(row.get("relrowsecurity"));
            boolean forced = Boolean.TRUE.equals(row.get("relforcerowsecurity"));
            String owner = String.valueOf(row.get("owner"));
            Integer policies = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, table);

            if (!enabled) {
                problems.add(table + ": 未 ENABLE ROW LEVEL SECURITY");
            }
            if (!forced) {
                problems.add(table + ": 未 FORCE ROW LEVEL SECURITY（表 owner 可绕过隔离）");
            }
            if (!RlsGateSupport.APP_USER.equals(owner)) {
                problems.add(table + ": owner=" + owner
                        + "（应为非超级用户 " + RlsGateSupport.APP_USER + "，否则 FORCE 无从证明）");
            }
            if (policies == null || policies == 0) {
                problems.add(table + ": 没有任何 RLS 策略（等于未隔离）");
            }
        }

        assertTrue(problems.isEmpty(),
                "租户表在真库中的 RLS 强制要素不完整（ADR-02 第 2 层）:\n  - " + String.join("\n  - ", problems));

        // 覆盖数自证：至少校验了 1 张表，否则上面的空 problems 毫无意义
        assertEquals(tenantTables.size(), ISOLATION_TESTS.size(),
                "租户表数与登记数必须一致；表=" + tenantTables + " 登记=" + ISOLATION_TESTS.keySet());
    }

    @Test
    @DisplayName("登记咬合：登记的表名必须真在该测试类源码里以标识形态出现（A-4 实测缺口收口）")
    void the_registered_test_class_must_actually_mention_the_table_it_covers() throws IOException {
        // 🛑 本判据堵的洞（A-4 实测抓出，2026-09-29）：
        //    原有三条判据里，第 ③ 条只验「登记的类可加载 且 @Test 数 >= 5」——
        //    它**不验该类真的断言了这张表**。
        //    后果：把一张新表登记到任一既有 >=5 @Test 的类上，
        //    ①②③ 三条判据**全部照常通过**，而该表的**行为隔离断言为零**。
        //    这正是本仓反复防的形态：登记表看起来"满了"，实际是**把新表挂到了邻居名下**。
        //
        // 🛑 判据形态是【实测选出来的】，不是拍的（三选一）：
        //    · F1 双引号 Java 字面量 `"<table>"`  ⇒ 误伤 **1/38**（RlsTenantIsolationTest 用常量拼接）；
        //    · F2 单引号 SQL 字面量  `'<table>'`  ⇒ 误伤 **28/38**（本仓 SQL 普遍是 `" FROM " + table` 拼接）；
        //    · F3 裸标识词（词边界）              ⇒ 误伤 **0/38** ✅
        //    故取 F3。**误伤为 0 是关键** —— 一个把正确实现判红的门禁会被关掉（本仓既有纪律）。
        //
        // 🛑 必须【先剥注释】：本仓各隔离测试类的 javadoc 里逐字列着它覆盖的表名，
        //    不剥注释则"注释里写了表名"会被当成"断言了表名" ⇒ 判据对空壳实现假绿。
        Map<String, String> sourceCache = new LinkedHashMap<>();
        List<String> notMentioned = new ArrayList<>();
        List<String> unloadable = new ArrayList<>();

        for (Map.Entry<String, String> e : ISOLATION_TESTS.entrySet()) {
            String table = e.getKey();
            String fqn = e.getValue();
            Path src = testSourceOf(fqn);
            if (src == null) {
                unloadable.add(table + " -> " + fqn);
                continue;
            }
            String code = sourceCache.computeIfAbsent(fqn,
                    k -> {
                        try {
                            return stripJavaComments(Files.readString(src, StandardCharsets.UTF_8));
                        } catch (IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
            if (!mentionsIdentifier(code, table)) {
                notMentioned.add(table + " -> " + fqn);
            }
        }

        assertTrue(unloadable.isEmpty(),
                "以下登记的隔离测试类找不到源码文件（登记了不存在的类）:\n  - "
                        + String.join("\n  - ", unloadable));

        assertTrue(notMentioned.isEmpty(),
                "以下登记的隔离测试类【没有在自己的源码里提到它被登记覆盖的表名】"
                        + "（剥离注释后、按词边界匹配）—— 这几乎总是「把新表挂到了邻居名下」的形态：\n  - "
                        + String.join("\n  - ", notMentioned)
                        + "\n请为这张表补一个**真的断言它**的隔离测试类并在 ISOLATION_TESTS 中改登记。"
                        + "\n🛑 本判据是【必要性】判据：它证明「表名被提到」，不证明「断言真的执行了该表」 ——"
                        + "后者由各隔离测试类的真库断言本身承载。");

        // 覆盖数自证：登记表非空，否则上面的"未命中为空"是空集对空集的假绿
        assertFalse(ISOLATION_TESTS.isEmpty(), "登记表不得为空");
    }

    @Test
    @DisplayName("登记咬合的判别力自证：词边界与剥离注释都必须真的生效（元层）")
    void the_registration_binding_matcher_has_real_discriminating_power() {
        // ---- ① 词边界判别力：表名不得被"更长标识符里的子串"满足 ----
        // 🛑 本仓有 3 组真实的"表名互为子串"：customer/customer_state_transition、
        //    refund/refund_receipt/refund_statement、band/band_telemetry/band_sync_log/band_sync_probe。
        //    用裸 contains 会让"只提到 refund"就满足"覆盖 refund_receipt" ⇒ 判据失效。
        assertFalse(mentionsIdentifier("\"FROM refund WHERE x=1\"", "refund_receipt"),
                "词边界失效：只提到 refund 就满足了 refund_receipt（本仓真实存在的子串关系）");
        assertFalse(mentionsIdentifier("\"customer_id, tenant_id\"", "customer"),
                "词边界失效：customer_id 满足了 customer");
        assertFalse(mentionsIdentifier("\"band_telemetry\"", "band"),
                "词边界失效：band_telemetry 满足了 band");
        assertTrue(mentionsIdentifier("\"DELETE FROM customer WHERE\"", "customer"),
                "词边界过严：独立的 customer 词元未被匹配 ⇒ 判据会对正确实现假红");
        assertTrue(mentionsIdentifier("\"SELECT count(*) FROM refund LIMIT 1\"", "refund"),
                "词边界过严：独立的 refund 词元未被匹配");

        // ---- ② 剥离注释的必要性：注释里的表名不得算"提到了" ----
        // 🛑 这不是假想：RlsV5EntityIsolationTest 的 javadoc 里【逐字列着 24 张表名】。
        //    不剥注释，判据对它恒真 —— 于是"这一类到底断言了什么"无从区分。
        String commented = String.join("\n",
                "// case_archive 的表在下面被断言",
                "/* 本类覆盖：region / store / staff / device */",
                "int x = 1;");
        assertFalse(mentionsIdentifier(stripJavaComments(commented), "case_archive"),
                "剥离器失效：行注释里的表名被当成了「提到了」");
        assertFalse(mentionsIdentifier(stripJavaComments(commented), "region"),
                "剥离器失效：块注释里的表名被当成了「提到了」");

        // ---- ③ 两级粒度自证：字符串字面量必须【保留】 ----
        // 🛑 本判据的载体恰恰住在字符串里（`" FROM case_archive WHERE"`）。
        //    若把字符串也剥掉，判据会对**每一个正确实现**报红（28/38 误伤，与探针实测吻合）。
        assertTrue(mentionsIdentifier(stripJavaComments("\"FROM case_archive WHERE a=1\""), "case_archive"),
                "剥离器过剥：字符串字面量里的表名被剥掉了 ⇒ 判据会对正确实现大面积假红");
    }

    // ------------------------------------------------------------------
    // 迁移解析
    // ------------------------------------------------------------------

    /** 登记的隔离测试类的源码文件；找不到返回 {@code null}。 */
    private static Path testSourceOf(String fqn) {
        Path root = RlsGateSupport.skeletonRoot();
        String rel = fqn.replace('.', '/') + ".java";
        for (String mod : TEST_SOURCE_MODULES) {
            Path p = root.resolve(mod).resolve("src").resolve("test").resolve("java").resolve(rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** 表名是否以【独立标识形态】出现（词边界；下划线亦视为标识符字符，故 refund ≠ refund_receipt）。 */
    private static boolean mentionsIdentifier(String code, String table) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(table) + "(?![A-Za-z0-9_])")
                .matcher(code).find();
    }

    /**
     * 剥掉 Java 的<b>行注释与块注释</b>，<b>保留</b>字符串字面量。
     *
     * <p>🛑 两级粒度的取舍（与 {@code RlsInjectionRealityGateTest} 同款纪律，但这里的选择相反）：
     * <ul>
     *   <li><b>注释必须剥</b>：本仓各隔离测试类的 javadoc 逐字列着覆盖的表名，
     *       不剥则"注释里写了"被当成"断言了"⇒ 空壳实现假绿；</li>
     *   <li><b>字符串必须留</b>：本判据的载体住在 SQL 字符串里（{@code " FROM case_archive WHERE"}），
     *       剥掉字符串会让 28/38 条正确登记报红（实测值，见探针）。</li>
     * </ul>
     * 单一口径去断言两个相反事实，必然有一半是假的 —— 故此处明写"剥注释但留字符串"，
     * 并由 {@link #the_registration_binding_matcher_has_real_discriminating_power()} 双向自证。
     *
     * <p>状态机：0=代码态 1=行注释 2=块注释；字符串态用转义感知跳过。
     */
    private static String stripJavaComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int state = 0;
        boolean inStr = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (state == 0) {
                if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    state = 1;
                    i++;
                } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                    state = 2;
                    i++;
                } else {
                    if (c == '"') {
                        inStr = true;
                    }
                    out.append(c);
                }
            } else if (state == 1) {
                if (c == '\n') {
                    state = 0;
                    out.append(c);
                }
            } else {
                if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    state = 0;
                    i++;
                }
            }
        }
        return out.toString();
    }

    /**
     * 从真实迁移文件里解析"含 tenant_id 列的表"。
     *
     * <p>解析器刻意保守：只认 {@code CREATE TABLE [IF NOT EXISTS] <name> ( ... )}，
     * 且要求建表语句体内出现独立的 {@code tenant_id} 列名。解析不出来比解析错了更安全。
     */
    private static Set<String> tenantTablesFromMigrations() {
        Pattern createTable = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Pattern tenantColumn = Pattern.compile("(^|[\\s,(])tenant_id\\s+", Pattern.CASE_INSENSITIVE);

        Set<String> found = new LinkedHashSet<>();
        for (String sql : migrationSqls) {
            String code = stripComments(sql);
            Matcher m = createTable.matcher(code);
            while (m.find()) {
                String table = m.group(1).toLowerCase();
                String body = balancedParenBody(code, m.end() - 1);
                if (body == null || !tenantColumn.matcher(body).find()) {
                    continue;
                }
                if (TENANT_HOST_TABLES.contains(table) || NON_TENANT_TABLES.contains(table)) {
                    continue;
                }
                found.add(table);
            }
        }
        return found;
    }

    /** 去掉 {@code --} 行注释，避免把注释里的示例建表语句当成真实表。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    /** 取从 {@code openIdx}（指向 '('）开始的配对括号内容，跳过字符串字面量。 */
    private static String balancedParenBody(String code, int openIdx) {
        if (openIdx < 0 || openIdx >= code.length() || code.charAt(openIdx) != '(') {
            return null;
        }
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '(') {
                depth++;
            } else if (!inQuote && c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(openIdx, i + 1);
                }
            }
        }
        return null;
    }

    /** 保留：便于日后需要"从 classpath 读迁移"时切换来源，当前统一读交付物源文件。 */
    @SuppressWarnings("unused")
    private static Map<String, String> classpathMigrationsByTable() {
        return new LinkedHashMap<>();
    }
}