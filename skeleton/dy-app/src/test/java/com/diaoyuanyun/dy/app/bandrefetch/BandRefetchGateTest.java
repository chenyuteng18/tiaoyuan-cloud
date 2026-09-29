package com.diaoyuanyun.dy.app.bandrefetch;

import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.DailyCoverageSnapshot;
import com.diaoyuanyun.dy.app.bandrefetch.domain.GapReason;
import com.diaoyuanyun.dy.app.bandrefetch.domain.HistoryMetric;
import com.diaoyuanyun.dy.app.bandrefetch.domain.ProbeSource;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeOutcome;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeRecord;
import com.diaoyuanyun.dy.app.bandrefetch.domain.SyncProbeSnapshot;
import com.diaoyuanyun.dy.app.bandrefetch.domain.WearState;
import com.diaoyuanyun.dy.app.bandrefetch.repository.BandRefetchLedger;
import com.diaoyuanyun.dy.app.bandrefetch.service.BandRefetchService;
import com.diaoyuanyun.dy.app.bandrefetch.service.BandRefetchService.CoverageResult;
import com.diaoyuanyun.dy.app.bandrefetch.service.BandRefetchService.ProbeResult;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-3 · 手环历史补拉通路（{@code band_sync_probe} / {@code band_daily_coverage}）的真库端到端回归</b>
 * —— 它见证的是这两张表从「零生产写入方」到「有写入方」这件事
 * <b>真的让「N 的留痕」与「逐日缺口原因」在系统里可以发生</b>，
 * 从而让 PRD §2.8.7 的「设备端历史补拉」整条能力第一次<b>有落库通路</b>。
 *
 * <h2>🛑🛑 判据设计零：本域缺口的后果<b>落在客户身上</b>，这是它与前五批最根本的差别</h2>
 * <table border="1">
 *   <caption>零写入方缺口的对外表现对照（本仓实测）</caption>
 *   <tr><th>批次</th><th>表</th><th>缺口表现</th><th>谁会因此失败</th></tr>
 *   <tr><td>B-10</td><td>{@code band}</td><td>{@code 23503}</td>
 *       <td>E1/E2 上报（四表 {@code device_id} 外键）</td></tr>
 *   <tr><td>B-11</td><td>{@code device}</td><td>{@code 23503}</td>
 *       <td>D6 下发（{@code device_dispatch.device_id} 外键）</td></tr>
 *   <tr><td>B-12</td><td>{@code scale}</td><td>{@code 422 / 5001}</td>
 *       <td>C2 基线评估（{@code scaleExists} 预检）</td></tr>
 *   <tr><td>B-13</td><td>{@code case_archive}</td>
 *       <td><b>无</b>（既不报错也不阻断）</td><td><b>无人</b> —— 只在举证时暴露</td></tr>
 *   <tr><td>A-1</td><td>{@code agreement}</td>
 *       <td><b>无</b>（不报错、不阻断）</td>
 *       <td><b>无人</b> —— 但 G1 的取数 SQL 返回空集</td></tr>
 *   <tr><td><b>A-3</b></td><td>{@code band_sync_probe} / {@code band_daily_coverage}</td>
 *       <td><b>第三类：一个错误的上界被采用</b>（不报错、不阻断、指标也算得出来）</td>
 *       <td><b>客户</b> —— A3 被扣分，且事后不会报错</td></tr>
 * </table>
 * <p>⇒ 故本类的验收口径<b>不能</b>照抄任何前批：
 * <ul>
 *   <li><b>不是</b>"某个 23503 消失了"：这两张表的写入方不产生 23503（除非引用错）；</li>
 *   <li><b>不是</b>"某道预检返回 true 了"；</li>
 *   <li><b>不是</b>"某个指标从空集变成非空"（那是 A-1 的口径）：<br>
 *       A-3 的指标<b>一直算得出来</b> —— 没有 {@code band_sync_probe} 时，
 *       补拉引擎会用一个<b>硬编码或默认的 N</b>（§2.8.7 逐字：「禁硬编码」）；</li>
 *   <li><b>是</b>：<b>「N 的取值有留痕、且它可被读回；逐日缺口原因有落库通路、
 *       且『技术性缺失』这一形态在写入侧就被挡住」</b>。</li>
 * </ul>
 * <p>🛑 可迁移的教训（本批新得，与 A-1 同族但更进一步）：
 * <b>零写入方的第四类表现</b> —— 不是错误码、不是"静默"、不是"指标恒为 0"，
 * 而是<b>「一个错误的默认值被采用，且指标照常计算」</b>。
 * 恒为 0 至少看着可疑；而一个<b>算得出来的错误值</b>看起来完全正常。
 * 这正是本域为什么必须把门禁落在<b>写入侧的拒绝</b>上（判据 ⑤），
 * 而不是落在"取数时记得排除"上。
 *
 * <h2>🛑🛑 本类的靶心一：{@code not_worn} × 技术性 {@code is_wear} 必须被拒（判据 ⑤）</h2>
 * 依据（§2.8.7⑤ 逐字）：
 * <pre>
 *   `(-1,255)` = 技术性缺失 → **一律不判行为性（宁可少扣、不可错扣）**，
 *   并须向厂商索要「设备异常原因码」把「传感器失败」与「客户脱腕」分开
 * </pre>
 * <p>{@code not_worn} 是全 7 值里<b>唯一可扣分</b>的一类（「缺失天计 0」）。
 * 把 {@code is_wear} 的"技术性"取值配上 {@code not_worn}，
 * 等于<b>把设备侧故障记成客户没戴</b>：A3 会扣分 → 依从性下降 → 进一步影响
 * {@code AS_refund}。而这一切<b>事后不会报错</b> —— 它已经写进库里、已经算过了。
 * <p>⇒ 故本判据必须<b>两侧都证</b>：
 * <ol>
 *   <li><b>被拒</b>：{@code not_worn} × {@code -1} / {@code 255} 必拒（应用层构造器 + 库层函数）；</li>
 *   <li><b>🛑 有判别力</b>：合法的 {@code not_worn} × {@code 0}（行为性脱腕）
 *       <b>必须能写入</b> —— 否则"什么都拒绝"的实现在 ⑤a 上也是绿的，
 *       而那会让本域<b>从"宁可少扣"翻转成"宁可错扣"</b>（方向反了），
 *       同时把「客户确实没戴」这一合法判定彻底堵死。</li>
 * </ol>
 * <p>🛑 第二条是本仓纪律「<b>只断言"被拒"的判据没有判别力</b>」的正向应用。
 * 实测口径：{@code pg_proc} 里两个函数各 1 行、{@code schema_migration} 有 V22 一行
 * （见判据 ⑩ 的在位断言）。
 *
 * <h2>🛑🛑 本类的靶心二：探针是「追加」，逐日覆盖是「upsert」—— 两条原语的幂等形态截然不同（判据 ②/③）</h2>
 * <table border="1">
 *   <caption>两条原语的幂等形态对照</caption>
 *   <tr><th></th><th>{@code register_sync_probe}</th><th>{@code register_daily_coverage}</th></tr>
 *   <tr><td>冲突键</td><td>{@code probe_id}（<b>单列主键</b>）</td>
 *       <td>{@code (device_id, date)}（表级 UNIQUE）</td></tr>
 *   <tr><td>冲突处置</td><td>{@code DO NOTHING}（<b>不改写既有留痕</b>）</td>
 *       <td>{@code DO UPDATE … WHERE c.tenant_id=… AND c.customer_id=…}</td></tr>
 *   <tr><td>两态</td><td>{@code CREATED} / {@code ALREADY_EXISTS}</td>
 *       <td>{@code CREATED} / {@code UPDATED}</td></tr>
 *   <tr><td>第二态是否改数据</td><td><b>否</b>（证据不可改）</td>
 *       <td><b>是</b>（当日观测事实刷新）</td></tr>
 * </table>
 * <p>⇒ 判据 ② 与 ③ 因此<b>必须分别写</b>，不能共用一个"重放"用例：
 * ② 断言"重放<b>不得</b>改写"，③ 断言"重放<b>必须</b>刷新"。
 * 若把两者写成同一个断言，会有一条<b>永远无法同时满足</b>的断言，
 * 而下一个人只会把其中一条删掉。
 *
 * <h2>🛑 本类的靶心三：哪些"跨租户撞号"可达、哪些<b>结构性不可达</b>（判据 ⑦）</h2>
 * 本域的两条原语在这一点上<b>完全不同</b>，必须分开说清（否则下一个人会去找一个不存在的用例）：
 * <ul>
 *   <li><b>{@code probe_id} 跨租户撞号：可达</b>。
 *       {@code probe_id} 由<b>调用方提供</b>的 UUID，表上是单列主键 ⇒ 两个租户用同一个
 *       {@code probe_id} 是必然可能的。⇒ 判据 ⑦a 真构造、真断言 RAISE。</li>
 *   <li><b>{@code (device_id, date)} 跨租户撞号：结构性不可达</b>。
 *       {@code device_id} 是 {@code band.band_id}，而 {@code band_id} 是<b>全局单列主键</b>
 *       ⇒ 同一台设备<b>只可能属于一个租户</b> ⇒ "两个租户共用一台设备"这件事在数据模型上
 *       就构造不出来。V22 里那条 RAISE 是<b>fail-closed 的防御</b>，不是可达路径。
 *       ⇒ 判据 ⑦b 断言的<b>不是"它被拒"</b>，而是<b>"封堵是活的"</b> ——
 *       用裸 SQL 证明那条路真的走不通（复合外键 23503 / 唯一约束 23505 兜底）。</li>
 * </ul>
 * <p>🛑 本仓纪律「<b>判据的适用范围 = 它的锚点范围</b>」的正向应用：
 * 为一条不可达路径编一个假的成功用例，比不写它更坏。</p>
 *
 * <h2>🛑 本类的靶心四：逐日覆盖的"归属客户"不得被一次补拉改写（判据 ⑪）</h2>
 * {@code uq_daily_coverage_device_date} 实测逐字 {@code UNIQUE (device_id, date)} ——
 * 它只保证<b>一行</b>，<b>不保证"同一个客户"</b>。
 * 若允许覆盖改写归属，一次补拉就能把一个客户的观测日<b>悄悄转记</b>到另一个客户名下 ——
 * 而 A3 是"唯一可扣分"的指标，这种转记<b>既可能冤枉甲，也可能放过乙</b>。
 * <p>本判据把 band 重新绑定到新客户（同一租户内合法），
 * 再用<b>新客户</b>去覆盖同一个 {@code (device, date)} ⇒ 必须 RAISE。
 * 这条路径<b>可达</b>（与 ⑦b 不同），故它是本类里除 ⑤ 之外第二条真正的"写入侧红门"。
 *
 * <h2>判据清单（12 条，≥ 验收要求的 9 条）</h2>
 * <ol>
 *   <li>① 完整探针登记：一次调用真的落一份可读回、N 与来源原样、{@code nIsVerified} 自洽的留痕；</li>
 *   <li>② 探针两态：{@code CREATED} → {@code ALREADY_EXISTS}，且<b>重放不得改写既有留痕</b>；</li>
 *   <li>③ 覆盖两态：{@code CREATED} → {@code UPDATED}，且<b>第二态必须真的刷新</b>当日观测事实；</li>
 *   <li>④ 审计失败 ⇒ 写入回滚（用真实 22001 撞这条易静默失效的不变量）；</li>
 *   <li>⑤ 🛑🛑 <b>核心门禁</b>：{@code not_worn} × 技术性 {@code is_wear} 必拒（应用层 + 库层），
 *       且<b>判别力自证</b>（合法 {@code not_worn(0)} 必须能写入）；</li>
 *   <li>⑥ {@code coverage_flag = TRUE} × {@code gap_reason} 必拒（只断言单向）；</li>
 *   <li>⑦ 跨租户：⑦a 探针撞号<b>可达</b> ⇒ RAISE(P0001) 且零残留；
 *       ⑦b 覆盖撞号<b>结构性不可达</b> ⇒ 断言封堵是活的；</li>
 *   <li>⑧ 上下文一致性守卫（已持有别的租户上下文必须被拒）；</li>
 *   <li>⑨ 跨租户引用被拒（设备 / 客户 / 同步日志三种引用）；</li>
 *   <li>⑩ 形态：无 HTTP 映射的组件 + V22 两个原语在位 + 🛑 静态机械核对
 *       枚举 ↔ V22 数组 ↔ 契约 enum ↔ V5 CHECK（四方一致）；</li>
 *   <li>⑪ 🛑 覆盖行的归属客户不得被改写（可达红门）；</li>
 *   <li>⑫ 本类【不提供】改写/删除入口（静态：无 UPDATE / DELETE 这两张表）。</li>
 * </ol>
 *
 * <h2>数据与清理</h2>
 * 本类用 {@code a3000000-} 前缀（与其它测试类零交集）。
 * 收尾时清业务行、<b>不删审计</b>（{@code audit_log} 是全局单链，删中间行会留下永久的
 * {@code prev_hash} 断口 —— 改为断言"证据确实留下了"）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("A-3 · 手环补拉通路真库回归（两态 / 核心门禁 / 跨租户 / 试验证 / 同事务审计）")
class BandRefetchGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code a3000000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "a3000000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ⑦ / ⑧ / ⑨ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "a3000000-0000-0000-0000-0000000000f1";

    private static final String REGION = "a3000000-0000-0000-0000-000000000011";
    private static final String STORE = "a3000000-0000-0000-0000-000000000021";

    /** 本租户客户 C1 —— 覆盖行的行级 scope 承载者。 */
    private static final String CUSTOMER = "a3000000-0000-0000-0000-000000000032";

    /**
     * 本租户客户 C2 —— 🛑 判据 ⑪ 的靶子。
     *
     * <p>它与 {@link #CUSTOMER} 是同一个租户内的两个客户。
     * 判据 ⑪ 把 {@link #BAND} 从 C1 重新绑定到 C2（同一租户内合法），
     * 再用 C2 去覆盖同一个 {@code (device, date)} ⇒ 必须 RAISE。
     */
    private static final String CUSTOMER_2 = "a3000000-0000-0000-0000-000000000033";

    /** 属于<b>另一个</b>租户的客户 —— 判据 ⑨ 的跨租户引用负样本。 */
    private static final String CUSTOMER_OTHER = "a3000000-0000-0000-0000-0000000000c2";

    /** 本租户手环（= {@code band.band_id}，也是两张表的 {@code device_id}）。 */
    private static final String BAND = "a3000000-0000-0000-0000-000000000041";
    /** 属于另一个租户的手环 —— 判据 ⑨ 的跨租户设备引用负样本。 */
    private static final String BAND_OTHER = "a3000000-0000-0000-0000-0000000000c4";

    /** 本租户同步日志 —— 判据 ① 等的合法绑定（{@code source_sync_log_id}）。 */
    private static final String SYNC_LOG = "a3000000-0000-0000-0000-000000000051";
    /** 一个<b>从不</b>建行的同步日志 id —— 判据 ⑨ 的"日志不存在"负样本。 */
    private static final String SYNC_LOG_GHOST = "a3000000-0000-0000-0000-00000000005f";
    /** 属于另一个租户的同步日志 —— 判据 ⑨ 的跨租户日志引用负样本。 */
    private static final String SYNC_LOG_OTHER = "a3000000-0000-0000-0000-0000000000c5";

    // —— 探针载体（各自独享，见下方逐个说明）——
    /** 判据 ①：完整探针登记（N + 来源 + 有效期数组 + created_by 全给）。 */
    private static final String PROBE_FULL = "a3000000-0000-0000-0000-000000000101";
    /** 判据 ②：幂等两态 + "重放不得改写留痕"。 */
    private static final String PROBE_IDEM = "a3000000-0000-0000-0000-000000000102";
    /** 判据 ④：审计失败注入用。 */
    private static final String PROBE_TX = "a3000000-0000-0000-0000-000000000103";
    /** 判据 ⑦a：跨租户撞号 —— 另一个租户先占用这一个。 */
    private static final String PROBE_SHARED = "a3000000-0000-0000-0000-000000000104";
    /** 判据 ⑧：上下文一致性守卫用。 */
    private static final String PROBE_CTX = "a3000000-0000-0000-0000-000000000105";
    /** 判据 ⑨：跨租户设备引用（不应落库）。 */
    private static final String PROBE_CROSS_DEV = "a3000000-0000-0000-0000-000000000106";
    /** 🛑 判据 ⑩ 的在位探针：用一个恒定的 probe_id 断言"两态可重现"。 */
    private static final String PROBE_SENTINEL = "a3000000-0000-0000-0000-000000000107";

    // —— 逐日覆盖载体 ——
    /** 判据 ③：幂等两态（CREATED → UPDATED），且第二态必须刷新。 */
    private static final String COV_IDEM = "a3000000-0000-0000-0000-000000000201";
    /** 判据 ④：审计失败注入用。 */
    private static final String COV_TX = "a3000000-0000-0000-0000-000000000202";
    /** 判据 ⑤：核心门禁的<b>正样本</b>（合法 {@code not_worn(0)}，必须能建）。 */
    private static final String COV_GATE_OK = "a3000000-0000-0000-0000-000000000203";
    /** 判据 ⑥：{@code coverage_flag=TRUE} × {@code gap_reason}（不应落库）。 */
    private static final String COV_FLAG = "a3000000-0000-0000-0000-000000000204";
    /** 判据 ⑨：跨租户客户引用（不应落库）。 */
    private static final String COV_CROSS_CUST = "a3000000-0000-0000-0000-000000000205";
    /** 判据 ⑨：跨租户同步日志引用（不应落库）。 */
    private static final String COV_CROSS_LOG = "a3000000-0000-0000-0000-000000000206";
    /** 判据 ⑧：上下文一致性守卫用。 */
    private static final String COV_CTX = "a3000000-0000-0000-0000-000000000207";
    /** 判据 ⑪：归属客户不得被改写 —— 首次由 C1 建。 */
    private static final String COV_OWNER = "a3000000-0000-0000-0000-000000000208";
    /** 判据 ⑫ 的裸 SQL 载体（绕过函数直插，验证复合外键兜底）。 */
    private static final String COV_BARE = "a3000000-0000-0000-0000-000000000209";

    /** 判据 ③ / ⑪ 用的业务日（各判据自带独立的日期，避免互相污染）。 */
    private static final LocalDate D_IDEM = LocalDate.parse("2026-09-21");
    private static final LocalDate D_GATE = LocalDate.parse("2026-09-22");
    private static final LocalDate D_OWNER = LocalDate.parse("2026-09-23");
    private static final LocalDate D_CROSS = LocalDate.parse("2026-09-24");

    /**
     * 🛑 判据 ⑤ 的<b>负样本</b>专用业务日 —— 它必须与其它判据的日期都不同，且<b>从不</b>有行。
     *
     * <p>🛑 为什么必须独立（本类首跑实测的纪律）：判据 ⑤ 断言"被拒之后不得落库"，
     * 而判据 ④ 在 {@link #D_GATE} 上已经建了<b>一行合法数据</b>。
     * 若 ⑤ 的负样本也用 {@code D_GATE}，那条断言会读到 1（是 ④ 留下的），
     * 于是要么报一条<b>归因错误</b>的红（以为是 ⑤ 漏了），要么被人改成 {@code <= 1} ——
     * 而那会让它失去"非法行有没有漏进来"的能力。
     * <p>⇒ 纪律：<b>断言"某处为零"的判据，其载体所在的坐标必须由它自己独占。</b>
     * 这与 {@code AgreementGateTest} 的"判据之间不得有隐式的数据依赖"是同一条纪律的另一面。
     */
    private static final LocalDate D_GATE_NEG = LocalDate.parse("2026-10-01");

    /**
     * 🛑 判据 ⑥ 的专用业务日 —— 理由与 {@link #D_GATE_NEG} 逐字相同。
     *
     * <p>⑥ 既断言"库层拒了"（要在某一天上确认零行），又断言
     * "NULL / FALSE 两种 flag 都能写入"（要在某一天上确认恰一行）。
     * 若正负样本共用一天，那条"零行"断言会读到负样本之前的行 ——
     * 归因会指向错误的方向（以为是门禁没生效）。
     * <p>⇒ 正样本用 {@code D_FLAG_NEG}（恒零行）、负样本各用
     * {@code D_FLAG_NULL} / {@code D_FLAG_FALSE}（各恰一行）。
     * <p>🛑 纪律同 {@link #D_GATE_NEG}：<b>断言"某处为零"的判据，
     * 其载体坐标必须由它自己独占。</b>
     */
    private static final LocalDate D_FLAG_NEG = LocalDate.parse("2026-10-02");
    private static final LocalDate D_FLAG_NULL = LocalDate.parse("2026-10-03");
    private static final LocalDate D_FLAG_FALSE = LocalDate.parse("2026-10-04");

    /**
     * 🛑 判据 ⑤ 的<b>合法 not_worn</b> 正样本专用业务日（判别力自证用）。
     *
     * <p>与 {@link #D_GATE_NEG} 分开：⑤c 断言"负样本那一天恒零行"，
     * 而 ⑤d 的合法 not_worn 必须真的落库 —— 两者共用一天会互相破坏。
     */
    private static final LocalDate D_GATE_LEGIT = LocalDate.parse("2026-10-05");

    /**
     * 🛑 判据 ⑤e 的"技术性缺失 + involuntary_technical"对照专用业务日。
     *
     * <p>它证的是"<b>被拒的是组合，不是技术性缺失这个事实</b>"——
     * 故它必须真的落库，且不能与任何"恒零行"的坐标共用。
     */
    private static final LocalDate D_GATE_TECH_OK = LocalDate.parse("2026-10-06");

    /**
     * 审计动作名与目标类型 —— 与 {@code BandRefetchService} 的常量逐字一致。
     *
     * <p>🛑 为什么在这里<b>重复一遍字面量</b>而不是去引用：
     * 那四个常量在 {@code ...bandrefetch.service} 包里是包私有，
     * 本类在 {@code ...bandrefetch} 包里，<b>访问不到</b>。
     * ⇒ 这四处字面量是"契约"而非"抄写"：若有人改了 Service 里的常量，
     * 本类会以"审计 action 不符"报红 —— 那正是我们要的（改名必须显式）。
     */
    private static final String ACTION_PROBE_REGISTERED = "BAND_SYNC_PROBE_REGISTERED";
    private static final String ACTION_PROBE_REPLAYED = "BAND_SYNC_PROBE_REPLAYED";
    private static final String ACTION_COVERAGE_REGISTERED = "BAND_COVERAGE_REGISTERED";
    private static final String ACTION_COVERAGE_REFRESHED = "BAND_COVERAGE_REFRESHED";
    private static final String TARGET_PROBE = "band_sync_probe";
    private static final String TARGET_COVERAGE = "band_daily_coverage";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-a3-witness";

    /** 🛑 判据 ④ 的注入载荷：超 {@code audit_log.actor VARCHAR(128)} 的操作者标识。 */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /** 合法的有效期数组（§2.8.7③ 的 {@code getValidHistoryDates} 返回形态：**数组**）。 */
    private static final String VALID_DATES = "[\"2026-09-14\",\"2026-09-15\",\"2026-09-16\"]";

    /**
     * 判据 ② 用的"第二次写入的 N 与来源"—— 它们的唯一作用是作
     * 「重放不得改写既有留痕」的靶子。
     *
     * <p>🛑 为什么需要一个可识别、且与首次不同的值：只断言行数抓不住
     * "重放顺手改写了既有探针"。而探针是 {@code N} 的<b>留痕</b>：
     * 被改写等于<b>证据被抹</b> —— 事后无法回答"当时是用哪个 N 补拉的"，
     * 而客户被错扣的申诉恰恰要回到这个起点。
     */
    private static final int REPLAY_OVERWRITE_N = 999;
    private static final ProbeSource REPLAY_OVERWRITE_SOURCE = ProbeSource.RUNTIME_PROBE;

    /** 正样本探针：N=7 + 运行时探测（{@code verifiable=true} ⇒ 已取证）。 */
    private static final int N_OK = 7;

    /** 真正调用过两条写路径的次数（供 {@code @AfterAll} 断言「审计证据确实留下了」，而不删它）。 */
    private static int probeCalls;
    private static int coverageCalls;

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    private static int baselineProbeFull;
    private static int baselineProbeIdem;
    private static int baselineProbeReplayedIdem;
    private static int baselineCovIdem;

    @Autowired
    DataSource springDataSource;

    @Autowired
    BandRefetchService service;

    @Autowired
    BandRefetchLedger ledger;

    @BeforeEach
    void seedIfNeeded() {
        if (dataSource == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
        if (!seeded) {
            seedOnce();
            captureAuditBaseline();
            seeded = true;
        }
    }

    /**
     * 种两个租户 + 区域/门店/客户 + 一台手环 + 一条同步日志。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code band_sync_probe} / {@code band_daily_coverage} 行
     * —— 那是本类全部判据的载体，必须由 {@link BandRefetchService} 建。
     * 这条纪律与 {@code AgreementGateTest} 逐字同款：判据的载体必须由被测的东西产出，
     * 否则"通路是否存在"这个问题会被夹具掩盖。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'A3租户-补拉', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'A3对照租户', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);

        inTenant(TENANT, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                    + "VALUES (?::uuid, ?::uuid, 'A3区') ON CONFLICT DO NOTHING", REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'A3店', '直营') ON CONFLICT DO NOTHING",
                    STORE, TENANT, REGION);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'A3客户C1', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER, TENANT);
            // 🛑 C2 的存在理由只有一个：判据 ⑪ 的靶子（同一租户内的「另一个客户」）。
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'A3客户C2', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER_2, TENANT);
            // 手环：band 的 NOT NULL = band_id / tenant_id / customer_id / vendor / bound_at
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, model,"
                            + " bound_at, status) VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', 'A3-M',"
                            + " DATE '2026-09-01', 'active') ON CONFLICT DO NOTHING",
                    BAND, TENANT, CUSTOMER);
            // 同步日志（§2.8.7③ 的 source_sync_log_id 引用目标）
            jdbc.update("INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id,"
                            + " trigger_source, result, consecutive_failures)"
                            + " VALUES (?::uuid, ?::uuid, ?::uuid, 'onShow', 'success', 0)"
                            + " ON CONFLICT DO NOTHING",
                    SYNC_LOG, TENANT, BAND);
            return null;
        });

        inTenant(TENANT_OTHER, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'A3对照区') ON CONFLICT DO NOTHING",
                    "a3000000-0000-0000-0000-000000000012", TENANT_OTHER);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'A3对照店', '直营')"
                            + " ON CONFLICT DO NOTHING",
                    "a3000000-0000-0000-0000-000000000022", TENANT_OTHER,
                    "a3000000-0000-0000-0000-000000000012");
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'A3对照客户', 'active')"
                            + " ON CONFLICT DO NOTHING", CUSTOMER_OTHER, TENANT_OTHER);
            jdbc.update("INSERT INTO band (band_id, tenant_id, customer_id, vendor, model,"
                            + " bound_at, status) VALUES (?::uuid, ?::uuid, ?::uuid, 'GTL1', 'A3-M2',"
                            + " DATE '2026-09-01', 'active') ON CONFLICT DO NOTHING",
                    BAND_OTHER, TENANT_OTHER, CUSTOMER_OTHER);
            jdbc.update("INSERT INTO band_sync_log (sync_log_id, tenant_id, device_id,"
                            + " trigger_source, result, consecutive_failures)"
                            + " VALUES (?::uuid, ?::uuid, ?::uuid, 'onShow', 'success', 0)"
                            + " ON CONFLICT DO NOTHING",
                    SYNC_LOG_OTHER, TENANT_OTHER, BAND_OTHER);
            return null;
        });
    }

    /** 记录本次运行前、四条关键审计的既有条数（供 {@code @AfterAll} 算增量）。 */
    private static void captureAuditBaseline() {
        baselineProbeFull = countAudit(ACTION_PROBE_REGISTERED, PROBE_FULL);
        baselineProbeIdem = countAudit(ACTION_PROBE_REGISTERED, PROBE_IDEM);
        baselineProbeReplayedIdem = countAudit(ACTION_PROBE_REPLAYED, PROBE_IDEM);
        baselineCovIdem = countAudit(ACTION_COVERAGE_REFRESHED, COV_IDEM);
    }

    // ==================================================================
    // 二、工具
    // ==================================================================

    /** 在指定租户上下文里执行（短事务 + {@code SET LOCAL}）。 */
    private static <T> T inTenant(String tenantId, Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /** 该探针在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int probeRows(String tenantId, String probeId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM band_sync_probe WHERE probe_id = ?::uuid",
                Integer.class, probeId));
        return n == null ? 0 : n;
    }

    /** 该覆盖行在本租户内的行数（按 {@code (device_id, date)} 定位，即真正的唯一载体）。 */
    private static int coverageRows(String tenantId, String deviceId, LocalDate date) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM band_daily_coverage WHERE device_id = ?::uuid AND date = ?::date",
                Integer.class, deviceId, java.sql.Date.valueOf(date)));
        return n == null ? 0 : n;
    }

    /**
     * 该覆盖行的
     * {@code [coverage_id::text, coverage_flag::text, gap_reason, is_wear::text,
     *   n_at_that_time::text, customer_id::text, created_by]}；无则 {@code null}。
     */
    private static String[] coverageState(String tenantId, String deviceId, LocalDate date) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT coverage_id::text, coalesce(coverage_flag::text, ''),"
                            + " coalesce(gap_reason, ''), coalesce(is_wear::text, ''),"
                            + " coalesce(n_at_that_time::text, ''), customer_id::text,"
                            + " coalesce(created_by, '')"
                            + "  FROM band_daily_coverage WHERE device_id = ?::uuid AND date = ?::date",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)},
                    deviceId, java.sql.Date.valueOf(date));
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 合法的完整探针登记（判据 ① 用；带全部可选字段）。 */
    private static SyncProbeRecord fullProbe(String probeId, String deviceId, String tenantId) {
        return new SyncProbeRecord(tenantId, UUID.fromString(probeId), UUID.fromString(deviceId),
                HistoryMetric.SLEEP, VALID_DATES, N_OK, ProbeSource.RUNTIME_PROBE,
                Instant.parse("2026-09-20T10:00:00Z"), false, "a3-witness");
    }

    /** 最简合法的探针登记（只给必填四项）。 */
    private static SyncProbeRecord plainProbe(String tenantId, String probeId, String deviceId) {
        return SyncProbeRecord.of(tenantId, UUID.fromString(probeId),
                UUID.fromString(deviceId), HistoryMetric.STEPS);
    }

    /** 一个最简合法的覆盖登记：{@code coverage_flag=TRUE}（该日有数据）+ 无缺口原因。 */
    private static DailyCoverageRecord plainCoverage(String coverageId, LocalDate date,
                                                     String customerId) {
        return new DailyCoverageRecord(TENANT, UUID.fromString(coverageId),
                UUID.fromString(BAND), UUID.fromString(customerId), date,
                Boolean.TRUE, null, null, null, null, N_OK, null, "a3-first-cov");
    }

    private ProbeResult probe(SyncProbeRecord r, String operator) {
        probeCalls++;
        return service.registerProbe(r, operator);
    }

    private CoverageResult coverage(DailyCoverageRecord r, String operator) {
        coverageCalls++;
        return service.registerDailyCoverage(r, operator);
    }

    // ==================================================================
    // 判据 ①
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 完整探针登记：一次调用真的落一份可读回、N 与来源原样、nIsVerified 自洽的留痕（本条是「补拉有没有落库通路」的验收）")
    void a_full_probe_call_really_lands_a_readable_record() {
        assertEquals(0, probeRows(TENANT, PROBE_FULL),
                "前置失败：PROBE_FULL 已在库里 ⇒ 下面那条 CREATED 断言会以【归因错误】的方式失败");

        // ---- ①a 落库 ----
        ProbeResult r = probe(fullProbe(PROBE_FULL, BAND, TENANT), OPERATOR);
        assertEquals(SyncProbeOutcome.CREATED, r.outcome(),
                "首次探针登记必须 CREATED —— 若返回 ALREADY_EXISTS，说明载体被占用了"
                        + "（那是一次归因错误的红：问题在用例隔离，不在探针逻辑）");
        assertEquals(PROBE_FULL, r.probeId().toString(), "回执必须带 probe_id");
        assertEquals(BAND, r.deviceId().toString(), "回执必须带 device_id");
        assertEquals(1, probeRows(TENANT, PROBE_FULL), "登记后本租户内必须恰有一行");

        // ---- ①b 回执里的 nUnverified 必须与 record 自算的一致 ----
        assertFalse(r.nUnverified(),
                "本次给了 N=7 且 probe_source='运行时探测'（verifiable=true）"
                        + "⇒ 这是一个【已取证】的 N，nUnverified 必须为 false。"
                        + "🛑 若它为 true，说明判据被改宽了 —— 而 §2.8.7④ 逐字要求"
                        + "「不得写成『补拉可让 A3 达标』」，即未取证的 N 必须被认出来");

        // ---- ①c 读回：一整行，不是几个标量（见 SyncProbeSnapshot 类注释）----
        SyncProbeSnapshot snap = service.probeSnapshotOf(TENANT, UUID.fromString(PROBE_FULL));
        assertNotNull(snap, "🛑 probeSnapshotOf 必须能读回刚写入的那一行 —— "
                + "若它是 null，而 probeRows 报 1，那就出现了一个可报警的矛盾");

        // ---- ①d 🛑 N 与来源必须原样 ----
        assertEquals(HistoryMetric.SLEEP, snap.historyType(),
                "history_type 必须原样读回（枚举 parse 走封闭词汇，未登记即抛）");
        assertEquals(N_OK, snap.retentionWindowDays(),
                "🛑 N（retention_window_days）必须原样读回 —— 它是本表的【存在理由】："
                        + "§2.8.7 逐字「N 运行时探测，禁硬编码」");
        assertTrue(snap.nIsVerified(),
                "🛑🛑 nIsVerified 必须为 true —— 它是「这个 N 可用吗」的读侧答案。"
                        + "判据 = 有 N ∧ 非测试数据 ∧ 来源是『运行时探测』。"
                        + "若它为 false，说明读侧判据与写侧 nIsUnverified() 分叉了");
        assertEquals(ProbeSource.RUNTIME_PROBE, snap.probeSourceOrNull(),
                "probe_source 必须原样读回（3 值中文枚举，与 V5 CHECK 逐字一致）");

        // ---- ①e 有效期数组原样（§2.8.7③ 的 getValidHistoryDates 返回形态：数组）----
        assertEquals(VALID_DATES.replace(" ", ""), snap.validDatesJson().replace(" ", ""),
                "🛑 valid_dates_json 必须原样落库且仍是 JSON **数组** —— "
                        + "口径来源 = §2.8.7③「依据」行的 getValidHistoryDates");

        // ---- ①f 测试标记与 created_by ----
        assertFalse(snap.isTestData(), "本次显式传 is_test=false ⇒ 读回必须为 false");
        assertEquals("a3-witness", snap.createdBy(), "created_by 必须原样落库");

        // ---- ①g created_at / probed_at 都必须可读（两个时刻语义不同）----
        assertNotNull(snap.createdAt(),
                "🛑 created_at 必须可读（由表的 default now() 提供 —— 本域刻意不显式写它，"
                        + "因为它是『登记时刻』，而『探测时刻』是 probed_at）");
        assertNotNull(snap.probedAt(),
                "probed_at 必须可读（业务时刻）—— 表的 default 也是 now()，"
                        + "但本次显式传了值，故应等于传入值");
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), snap.probedAt(),
                "🛑 probed_at 必须原样落库（不是登记时刻！）—— "
                        + "『latestProbeOf 按 probed_at 取最新』这条读侧口径依赖它");

        // ---- ①h 审计 ----
        assertAudit(r.auditId(), ACTION_PROBE_REGISTERED, PROBE_FULL, "\"mode\":\"CREATED\"");
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 探针两态：CREATED → ALREADY_EXISTS；🛑 且重放【不得】改写既有留痕（探针是 N 的证据，改写=抹证据）")
    void the_probe_two_states_are_complete_and_replay_never_rewrites_the_record() {
        assertEquals(0, probeRows(TENANT, PROBE_IDEM), "前置失败：PROBE_IDEM 应尚未登记");

        // ---- 态 1：首次（N=3 + 厂商文档 ⇒ verifiable=false ⇒ nUnverified=true）----
        SyncProbeRecord first = new SyncProbeRecord(TENANT, UUID.fromString(PROBE_IDEM),
                UUID.fromString(BAND), HistoryMetric.HR, null, 3, ProbeSource.VENDOR_DOC,
                Instant.parse("2026-09-21T09:00:00Z"), false, "a3-first");
        ProbeResult r1 = probe(first, OPERATOR);
        assertEquals(SyncProbeOutcome.CREATED, r1.outcome());
        assertTrue(r1.nUnverified(),
                "🛑 首次用的 N 是『厂商文档』来源（verifiable=false）⇒ nUnverified 必须为 true。"
                        + "§2.8.7 明示厂商文档属「文档自证、待真机复核」的外部依赖，"
                        + "未复核前不得当成已取证");

        SyncProbeSnapshot s0 = service.probeSnapshotOf(TENANT, UUID.fromString(PROBE_IDEM));
        assertNotNull(s0);
        assertEquals("a3-first", s0.createdBy(), "首次 created_by 必须落库");
        assertEquals(3, s0.retentionWindowDays(), "首次 N 必须落库");

        // ---- 态 2：重放，并【显式提供一组不同的参数】----
        //   🛑 这就是本判据的靶心：只断言行数抓不住"重放顺手改写了既有留痕"。
        SyncProbeRecord replay = new SyncProbeRecord(TENANT, UUID.fromString(PROBE_IDEM),
                UUID.fromString(BAND), HistoryMetric.SPO2, VALID_DATES,
                REPLAY_OVERWRITE_N, REPLAY_OVERWRITE_SOURCE,
                Instant.parse("2026-09-22T09:00:00Z"), true, "a3-replay");
        ProbeResult rr = probe(replay, OPERATOR);
        assertEquals(SyncProbeOutcome.ALREADY_EXISTS, rr.outcome(),
                "重放同一个 probe_id 必须返回 ALREADY_EXISTS（幂等命中），"
                        + "而不是把它当成错误或冲突");

        assertEquals(1, probeRows(TENANT, PROBE_IDEM),
                "🛑 重放之后必须仍【恰有一行】—— 若变成 2 行，说明 ON CONFLICT 的推断目标"
                        + "没命中主键");

        // ---- ②a 🛑🛑 核心断言：重放【不得改写】既有留痕 ----
        SyncProbeSnapshot s1 = service.probeSnapshotOf(TENANT, UUID.fromString(PROBE_IDEM));
        assertEquals(3, s1.retentionWindowDays(),
                "🛑🛑 重放【改写了】既有探针的 N（" + REPLAY_OVERWRITE_N + " 逃逸进库里）—— "
                        + "探针是 N 的【留痕】：被改写等于【证据被抹】。"
                        + "这条性质由库层 ON CONFLICT DO NOTHING 给出，故它一旦失效，"
                        + "最可能的原因是有人把它改成了 DO UPDATE。"
                        + "对本域而言这不是一条『数据陈旧』缺口，而是"
                        + "『事后无法回答当时是用哪个 N 补拉的』——"
                        + "而客户被错扣的申诉恰恰要回到这个起点");
        assertEquals("a3-first", s1.createdBy(),
                "🛑 created_by 也不得被重放改写（谁在什么时候探的，是不可变更的既成事实）");
        assertEquals(HistoryMetric.HR, s1.historyType(),
                "🛑 history_type 不得被重放改写（『这条 N 适用于哪一路历史』是本行的语义）");
        assertNull(s1.validDatesJson(),
                "🛑 重放传入的有效期数组不得覆盖首次的 NULL（证据未被改写）");
        assertFalse(s1.isTestData(),
                "🛑 is_test 不得被重放的 true 改写 —— 若它被改写，"
                        + "一条生产留痕会【凭空变成测试数据】⇒ 正当清洗会把它删掉");

        // ---- ②b 对照：不同 probe_id 必须各自独立可建 ----
        //   🛑 顺序有讲究：这条断言必须排在 ②c 的审计断言【之前】。
        //      AgreementGateTest 首跑实测证明过：若把审计查询放在前面，
        //      它一旦因类型问题抛错中断，后面的对照断言会【从未执行】，
        //      于是判据 ④ 会以错误归因报红。
        //      ⇒ 纪律：**副作用型断言要放在"可能中断的断言"之前**。
        assertEquals(SyncProbeOutcome.CREATED,
                probe(plainProbe(TENANT, PROBE_SENTINEL, BAND), OPERATOR).outcome(),
                "🛑 换一个 probe_id 必须能建 —— 否则上面那条『重放返回 ALREADY_EXISTS』"
                        + "就无法与『这个设备本身登记不上』区分（比较器必须对称）");

        // ---- ②c 重放仍必须写审计（且 action 与首次【不同】）----
        assertAudit(rr.auditId(), ACTION_PROBE_REPLAYED, PROBE_IDEM, "\"mode\":\"ALREADY_EXISTS\"");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 覆盖两态：CREATED → UPDATED；🛑 且第二态【必须真的刷新】当日观测事实（与探针的 DO NOTHING 形态相反）")
    void the_coverage_two_states_are_complete_and_the_second_state_really_refreshes() {
        assertEquals(0, coverageRows(TENANT, BAND, D_IDEM),
                "前置失败：D_IDEM 那一天应尚无覆盖行");

        // ---- 态 1：首次（有数据、无缺口原因）----
        CoverageResult r1 = coverage(plainCoverage(COV_IDEM, D_IDEM, CUSTOMER), OPERATOR);
        assertEquals(DailyCoverageOutcome.CREATED, r1.outcome(),
                "首次覆盖登记必须 CREATED —— 🛑 与探针侧的差别在这里第一次显现："
                        + "coverage 的冲突键是 (device_id, date)（表级 UNIQUE），不是主键");
        assertEquals(COV_IDEM, r1.coverageId().toString(), "回执必须带 coverage_id");
        assertEquals(CUSTOMER, r1.customerId().toString(), "回执必须带 customer_id");
        assertEquals(D_IDEM, r1.date(), "回执必须带业务日");
        assertFalse(r1.chargeable(),
                "本次 gap_reason 为 NULL ⇒ 不可扣分（唯一可扣分的是 not_worn）");
        assertEquals(1, coverageRows(TENANT, BAND, D_IDEM), "登记后必须恰有一行");

        // ---- ③-0 🛑🛑 首次覆盖必须记【独立 action】BAND_COVERAGE_REGISTERED（不是 REFRESHED）----
        //   🛑 本断言是 2026-09-30 补的缺口修复，理由必须逐字记下：
        //     Service 类注释（"为什么两处都用独立的 action"）逐字写明两个 action 各有
        //     **独立的生产用途**且**要回答相反的问题**：
        //       · BAND_COVERAGE_REGISTERED 的计数 = "新覆盖了多少个业务日"
        //         → A3 分子扩张速度的**直接度量**（越大越好）；
        //       · BAND_COVERAGE_REFRESHED 的计数 = "重拉刷新了多少次"
        //         → §2.8.7③「节流」的偏差度量（越大越可疑）。
        //     若首次覆盖被记成 REFRESHED，这两个数会被混进同一个计数 ⇒
        //     两条相反的问题（"扩张多快" vs "重复发起多频繁"）都不可答。
        //   ⚠️ 在补此断言之前，ACTION_COVERAGE_REGISTERED 在整个测试类里
        //     **只有定义（第 329 行常量）、从未被任何断言使用** ⇒
        //     "首次覆盖记 REGISTERED" 这条性质**无判据守**，
        //     而 125 的反向验证 C18（把 action 恒取 REFRESHED）因此**抓不住**（假绿）。
        //     这正是本仓纪律"每一条被写出来的常量都必须有一条判据在用它"的实例。
        assertAudit(r1.auditId(), ACTION_COVERAGE_REGISTERED, COV_IDEM, "\"mode\":\"CREATED\"");

        String[] s0 = coverageState(TENANT, BAND, D_IDEM);
        assertNotNull(s0);
        assertEquals(COV_IDEM, s0[0], "首次插入时 coverage_id 必须等于传入值");
        assertEquals("true", s0[1], "coverage_flag 必须落库为 TRUE");
        assertEquals("", s0[2], "gap_reason 应为空（本次未给）");

        // ---- 态 2：同 (device, date) 再登记一次，并【显式提供一组不同的观测事实】----
        //   🛑 与探针侧相反：这里**期望**第二态改写数据（upsert 的意义）。
        DailyCoverageRecord second = new DailyCoverageRecord(TENANT,
                // 🛑 用【另一个】coverage_id：它【不是】upsert 的冲突键，
                //    它只是"首次插入那一行"的身份 ⇒ 覆盖时**不得**改写它。
                UUID.fromString("a3000000-0000-0000-0000-0000000002ff"),
                UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_IDEM,
                Boolean.TRUE, null, null,
                // wear_minutes=300 / effective_wear_minutes=280 ⇒ 两者之差 20 即
                // "合规摘除剔除量"的可观测面（§2.8.7⑤ 附表：暂停期从分母剔除）。
                // n_at_that_time=5 ⇒ ③a 断言它【已被刷新】（首次是 3）。
                300, 280, 5,
                UUID.fromString(SYNC_LOG), "a3-refresh");

        CoverageResult r2 = coverage(second, OPERATOR);
        assertEquals(DailyCoverageOutcome.UPDATED, r2.outcome(),
                "同 (device_id, date) 重放必须返回 UPDATED（幂等命中 upsert），"
                        + "而不是把它当成错误或冲突 —— §2.8.7③「幂等」行逐字："
                        + "「上报前 upsert（服务端权威）→ 重复拉取 = 覆盖、非追加」");
        assertTrue(r2.refreshed(), "UPDATED 必须被识别为『重拉刷新』（§2.8.7③ 节流的可观测面）");
        assertTrue(r2.mutated(),
                "🛑 覆盖态【也】改动了数据 —— 两态都为 true。"
                        + "🛑 这是与探针侧最关键的一处不同：探针 ALREADY_EXISTS 恒不改数据，"
                        + "coverage UPDATED 恒改数据。若有人把两者统一成『都不改』，"
                        + "补拉就永远刷不新当日观测事实");
        assertEquals(1, coverageRows(TENANT, BAND, D_IDEM),
                "🛑 覆盖之后必须仍【恰有一行】—— 若变成 2 行，说明冲突键没命中");

        // ---- ③a 🛑 当日观测事实必须真的被刷新 ----
        String[] s1 = coverageState(TENANT, BAND, D_IDEM);
        assertEquals("5", s1[4],
                "🛑 n_at_that_time 必须被刷新为第二次的值（5）—— §2.8.7③ 逐字"
                        + "「N 随探测刷新，须留痕」：N 会变，故这一列必须可被覆盖更新。"
                        + "实际=" + s1[4]);
        // 🛑 created_by 是"谁在什么时候建的"，属不可变更的既成事实 ⇒ 属 V22 (7) 的
        //    "不更新的列"。首次传的是第一份记录里的 "a3-first-cov"。
        assertEquals("a3-first-cov", s1[6],
                "🛑🛑 覆盖【不得改写】created_by —— 它是『谁在什么时候建的这一行』，"
                        + "是不可变更的既成事实（V22 的 (7) 逐字列在『不更新的列』里）。"
                        + "实际=" + s1[6]);
        assertEquals(COV_IDEM, s1[0],
                "🛑🛑 覆盖【不得改写】coverage_id —— 它是『首次插入那一行』的身份"
                        + "（V22 的 (7) 逐字把它列在『不更新的列』里）。"
                        + "若它被改成了第二次传入的值，说明 ON CONFLICT 的 SET 列表里混进了身份位");

        // ---- ③b 🛑 与探针侧的镜像对照（把"两条原语形态不同"钉成事实）----
        //   🛑 探针侧：ALREADY_EXISTS 时 DO NOTHING ⇒ 留痕【未被改写】（判据 ② 已逐字断言）。
        //      覆盖侧：UPDATED 时 DO UPDATE ⇒ 观测事实【已被刷新】（③a 已逐字断言）。
        //      本段只做一次双向的"结论不一致"自证 —— 若两条原语被改成同一形态，
        //      上面两条断言里必有一条红，而本段给出归因。
        SyncProbeSnapshot firstProbe = service.latestProbeOf(TENANT, UUID.fromString(BAND),
                HistoryMetric.HR);
        assertNotNull(firstProbe,
                "对照前提：判据 ② 在 (BAND, hr) 上建的探针必须可被 latestProbeOf 读回 —— "
                        + "若它是 null，说明读侧的『全序取最新』口径与写侧不一致");
        assertEquals(3, firstProbe.retentionWindowDays(),
                "🛑🛑 探针侧与覆盖侧的镜像对照：同一个『重放』动作，"
                        + "探针侧的 N 仍是首次的 3（DO NOTHING，证据未被改写），"
                        + "而覆盖侧的 n_at_that_time 已刷新为 5（DO UPDATE，观测事实被刷新）。"
                        + "两条原语的幂等形态【必须不同】—— 若这里也是 999，"
                        + "说明探针的 ON CONFLICT 被改成了 DO UPDATE（证据被抹）");
        assertNull(firstProbe.validDatesJson(),
                "🛑 同上：探针侧的有效期数组必须仍是 NULL（首次未给）—— "
                        + "重放传入的数组不得逃逸进库");

        assertAudit(r2.auditId(), ACTION_COVERAGE_REFRESHED, COV_IDEM,
                "\"mode\":\"UPDATED\"");
    }

    // ==================================================================
    // 判据 ④
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 🛑 审计失败必须让两条写路径【回滚】—— 用真实的 actor 超长（22001）撞这条易静默失效的不变量")
    void a_failed_audit_write_rolls_both_paths_back() {
        // ---- ④a 探针侧 ----
        assertEquals(0, probeRows(TENANT, PROBE_TX), "前置失败：PROBE_TX 应尚未登记");

        RuntimeException exP = assertThrows(RuntimeException.class,
                () -> probe(fullProbe(PROBE_TX, BAND, TENANT), TOO_LONG_OPERATOR),
                "🛑 审计写不进去时，探针登记竟然没有抛错 —— "
                        + "那说明审计失败被静默吞掉了，而『每一条 N 留痕都有来源』这条不变量已失效");

        assertEquals("22001", deepestSqlState(exP),
                "🛑 本次失败的成因必须是 22001（actor 超列宽），实际=" + deepestSqlState(exP)
                        + "。若成因是别的，那本条证明的就不是『审计失败 ⇒ 探针回滚』。异常链="
                        + fullCauseMessages(exP));

        assertEquals(0, probeRows(TENANT, PROBE_TX),
                "🛑🛑 审计失败之后 band_sync_probe 行【仍在】库里 —— "
                        + "『审计失败则写入回滚』这条不变量已静默失效。"
                        + "最常见的成因是有人把 BandRefetchLedger / BandRefetchService 的 "
                        + "TransactionTemplate 改成了 PROPAGATION_REQUIRES_NEW"
                        + "（写入独立提交，审计失败只回滚审计）—— "
                        + "此时探针已落库而没有任何来源证据，"
                        + "『这个 N 是谁探的、什么时候探的』事后不可答");

        // ---- ④b 覆盖侧 ----
        assertEquals(0, coverageRows(TENANT, BAND, D_GATE),
                "前置失败：D_GATE 那天应尚无覆盖行");

        RuntimeException exC = assertThrows(RuntimeException.class,
                () -> coverage(DailyCoverageRecord.of(TENANT, UUID.fromString(COV_TX),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_GATE,
                        Boolean.TRUE, null, null, N_OK), TOO_LONG_OPERATOR),
                "🛑 同 ④a 的理由 —— 但对本域更贵：逐日覆盖是 A3 的【直接取值来源】，"
                        + "一次无来源的写入等于一次事后无从申诉的扣分依据");

        assertEquals("22001", deepestSqlState(exC),
                "🛑 覆盖侧本次失败的成因也必须是 22001，实际=" + deepestSqlState(exC)
                        + "。异常链=" + fullCauseMessages(exC));

        assertEquals(0, coverageRows(TENANT, BAND, D_GATE),
                "🛑🛑 审计失败之后 band_daily_coverage 行【仍在】库里 —— 同 ④a 的理由，"
                        + "且这一张表写的是『客户那一天算不算 0 分』的依据");

        // ---- ④c 对照：两条路径换合法 operator 之后必须成功 ----
        assertEquals(SyncProbeOutcome.CREATED,
                probe(fullProbe(PROBE_TX, BAND, TENANT), OPERATOR).outcome(),
                "🛑 换成合法 operator 之后探针登记必须成功 —— 否则上面那条『回滚』断言"
                        + "就无法与『这条探针本身建不上』区分");
        assertEquals(1, probeRows(TENANT, PROBE_TX), "合法路径下探针必须真的落库");

        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT, UUID.fromString(COV_TX),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_GATE,
                        Boolean.TRUE, null, null, N_OK), OPERATOR).outcome(),
                "🛑 同上的对照（覆盖侧）");
        assertEquals(1, coverageRows(TENANT, BAND, D_GATE), "合法路径下覆盖必须真的落库");
    }

    // ==================================================================
    // 判据 ⑤ —— 🛑🛑 本迁移的靶心
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 🛑🛑 核心门禁：not_worn × 技术性 is_wear 必拒（应用层+库层），且【判别力自证】合法 not_worn(0) 必须能写入")
    void not_worn_must_never_coexist_with_a_technical_wear_state() {
        // ==============================================================
        // ⑤a 应用层：Java 构造器必须拒（对 -1 与 255 两个技术性取值）
        // ==============================================================
        for (WearState tech : List.of(WearState.TECHNICAL_NEG_ONE, WearState.TECHNICAL_255)) {
            BizException ex = assertThrows(BizException.class,
                    () -> DailyCoverageRecord.of(TENANT, UUID.fromString(COV_GATE_OK),
                            UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_GATE_NEG,
                            Boolean.FALSE, GapReason.NOT_WORN, tech, N_OK),
                    "🛑🛑 gap_reason=not_worn 配上 is_wear=" + tech.code()
                            + "（" + tech.name() + "，技术性缺失）竟然被构造器接受了 —— "
                            + "依据 = §2.8.7⑤ 逐字：「`(-1,255)`=技术性缺失 → "
                            + "**一律不判行为性**（宁可少扣、不可错扣）」。"
                            + "not_worn 是全 7 值里【唯一可扣分】的一类，"
                            + "这个组合等于把设备侧故障记成客户没戴："
                            + "A3 会扣分 → 依从性下降 → 进一步影响 AS_refund，"
                            + "而这一切【事后不会报错】");
            assertTrue(ex.getMessage().contains("not_worn"),
                    "🛑 拒绝消息必须写明是 not_worn 与技术性 is_wear 的冲突"
                            + "（而不是笼统的『参数非法』）—— 两者的排查方向完全不同。"
                            + "实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("involuntary_technical"),
                    "🛑 拒绝消息必须给出【正确处置】—— 它应逐字提示改用 "
                            + "gap_reason='involuntary_technical'（或 beyond_retention_window / "
                            + "sync_failed）。只报错不给替代路径，会让人把这条门禁当成bug绕过。"
                            + "实际=" + ex.getMessage());
        }

        // ---- ⑤b 错误码必须是 GATE_MISSING(2002, 403)，不是 VALIDATION_FAILED ----
        //   🛑 为什么这条要单独写：本域这不是"参数格式错"，而是
        //      "这条数据给客户带来的后果不可接受"。错误码决定了调用方
        //      是"改参数重试"还是"停下来问人"——两者混用会让运营绕过它。
        BizException codeEx = assertThrows(BizException.class,
                () -> DailyCoverageRecord.of(TENANT, UUID.fromString(COV_GATE_OK),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_GATE_NEG,
                        Boolean.FALSE, GapReason.NOT_WORN, WearState.TECHNICAL_255, N_OK));
        Integer code = errorCodeOf(codeEx);
        assertNotNull(code, "门禁异常必须是 BizException 且带 ErrorCode，实际链="
                + fullCauseMessages(codeEx));
        assertEquals(2002, code.intValue(),
                "🛑 核心门禁的错误码必须是 GATE_MISSING(2002)（HTTP 403），实际=" + code
                        + "。若它是 1001（参数校验失败，400），调用方会以为"
                        + "『改一下参数就能过』—— 而这条门禁的语义是"
                        + "『这个组合不可接受，请核对设备给出的原因』");

        // ==============================================================
        // ⑤c 库层：绕过 Java 构造器直调函数 —— 它守的是"有人绕过 record 直接写 SQL"
        // ==============================================================
        //   🛑 为什么必须测库层那一道：Java 侧构造器会先拦（⑤a 已证）。
        //      库层那道是最后一道 —— 没有它，"直写 SQL" 就能把设备故障记成客户没戴。
        for (int techCode : List.of(-1, 255)) {
            String state = writeExpectingFailure(TENANT,
                    CALL_COVERAGE, TENANT, COV_GATE_OK, BAND, CUSTOMER, D_GATE_NEG,
                    Boolean.FALSE, GapReason.NOT_WORN.code(), techCode,
                    null, null, N_OK, null, null);
            assertEquals("P0001", state,
                    "🛑🛑 库层对 not_worn × 技术性 is_wear=" + techCode
                            + " 必须 RAISE(P0001)（V22 的 (5c) 显式门禁），实际=" + state
                            + "。这是绕过 Java 构造器时的最后一道防线 —— "
                            + "而【绕过 Java 是现实的】：本域的两条原语是运维/服务通路，"
                            + "任何持有库连接的脚本都能直调它");
        }
        assertEquals(0, coverageRows(TENANT, BAND, D_GATE_NEG),
                "🛑 被拒之后不得落库 —— D_GATE_NEG 是判据 ⑤ 的【独占载体日】，"
                        + "它只被用来构造『必须被拒』的负样本 ⇒ 它上面合法行数必须恒为 0。"
                        + "（🛑 这里刻意不用 D_GATE：那一天判据 ④ 已留下合法行，"
                        + "两判据共用坐标会让『>0』无法归因 —— 见 D_GATE_NEG 的常量注释）");

        // ==============================================================
        // ⑤d 🛑🛑 判别力自证：合法的 not_worn(0) 必须能写入
        // ==============================================================
        //   🛑 这条是本判据不可或缺的一半。理由有三条，缺一不可：
        //     ① 没有它，一个"凡 not_worn 一律拒绝"的实现也能通过 ⑤a/⑤c
        //        —— 而那会把「客户确实没戴」这一合法判定彻底堵死；
        //     ② §2.8.7⑤ 的方向是「宁可少扣、不可错扣」，不是「一律不扣」。
        //        一律不扣会让 A3 永远满分，那同样是指标失效（只是方向相反）；
        //     ③ 「只断言"被拒"的判据没有判别力」是本仓纪律 —— 一个什么都拒绝的
        //        实现必须无法通过本判据。
        DailyCoverageRecord legitimate = DailyCoverageRecord.of(TENANT,
                UUID.fromString(COV_GATE_OK), UUID.fromString(BAND),
                UUID.fromString(CUSTOMER), D_GATE_LEGIT,
                Boolean.FALSE, GapReason.NOT_WORN, WearState.REMOVED, N_OK);
        CoverageResult ok = coverage(legitimate, OPERATOR);
        assertTrue(ok.firstTime() || ok.refreshed(),
                "🛑 合法的 not_worn(0) 登记必须成功（两态之一），实际=" + ok.outcome());
        assertEquals(DailyCoverageOutcome.CREATED, ok.outcome(),
                "🛑🛑 这条是 ⑤ 的判别力自证：not_worn 配【行为性】is_wear=0（客户脱腕）"
                        + "是**合法且必须可写入**的形态（§2.8.7⑤ 第②行"
                        + "「缺失天计 0（扣分）」正是为它准备的）。"
                        + "若此处失败，说明门禁被判宽成了『凡 not_worn 一律拒绝』—— "
                        + "那是从『宁可少扣』翻转成『宁可不扣』，方向反了");
        assertTrue(ok.chargeable(),
                "🛑 合法的 not_worn 必须被识别为【可扣分】—— "
                        + "这是 A3 里唯一能扣分的那一类，也是『若客户确实没戴、系统要能记下来』的落点");

        // ---- ⑤e 对照：技术性缺失配上【不可扣】的原因必须能写入 ----
        //   🛑 这条把门禁的边界钉死：被拒的是**组合**，不是技术性缺失这个事实本身。
        //      §2.8.7⑤ 明示技术性缺失的处置是「写 involuntary_technical」——
        //      若下面这条建不上，说明门禁把"技术性缺失"本身也一并堵死了。
        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002fe"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        D_GATE_TECH_OK,
                        Boolean.FALSE, GapReason.INVOLUNTARY_TECHNICAL,
                        WearState.TECHNICAL_255, N_OK), OPERATOR).outcome(),
                "🛑 技术性缺失 + gap_reason='involuntary_technical' 必须能写入 —— "
                        + "这正是 §2.8.7⑤ 给出的【正确处置】，"
                        + "也是门禁消息里逐字提示的那条路。若它建不上，"
                        + "说明门禁把『技术性缺失』这个事实本身当成了非法");
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ coverage_flag=TRUE（该日有数据）与 gap_reason 必须互斥 —— 🛑 且只断言单向（NULL/FALSE 都合法）")
    void a_true_flag_must_not_coexist_with_a_gap_reason() {
        // ---- ⑥a 应用层 ----
        BizException ex = assertThrows(BizException.class,
                () -> DailyCoverageRecord.of(TENANT, UUID.fromString(COV_FLAG),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        D_FLAG_NEG,
                        Boolean.TRUE, GapReason.SYNC_FAILED, null, N_OK),
                "🛑 coverage_flag=TRUE（该日有数据）与 gap_reason='sync_failed'（为什么没数据）"
                        + "竟然被接受了 —— 『有数据』与『为什么没数据』是互斥的两个陈述；"
                        + "同时成立会让 A3 取数时两条互斥的分支同时命中");
        assertTrue(ex.getMessage().contains("互斥") || ex.getMessage().contains("不能共存"),
                "🛑 拒绝消息必须写明是【互斥】（而不是笼统的『参数非法』）。实际=" + ex.getMessage());

        // ---- ⑥b 库层（绕过构造器）----
        String state = writeExpectingFailure(TENANT,
                CALL_COVERAGE, TENANT, COV_FLAG, BAND, CUSTOMER, D_FLAG_NEG,
                Boolean.TRUE, GapReason.SYNC_FAILED.code(), null,
                null, null, N_OK, null, null);
        assertEquals("P0001", state,
                "🛑 库层对 coverage_flag=TRUE × gap_reason 必须 RAISE(P0001)（V22 的 (5d)），"
                        + "实际=" + state);
        assertEquals(0, coverageRows(TENANT, BAND, D_FLAG_NEG),
                "🛑 被拒后不得落库 —— D_FLAG_NEG 是判据 ⑥ 的【独占零行坐标】，"
                        + "它只被用来构造『必须被拒』的负样本 ⇒ 它上面合法行数必须恒为 0");

        // ---- ⑥c 🛑🛑 反向：NULL 与 FALSE 都【必须可以】配 gap_reason ----
        //   🛑 这条是本判据的判别力自证，且它的理由直接来自 §4.3：
        //      coverage_flag = NULL 表示「缺失」——**严禁补 0**（§4.3 的核心纪律之一）。
        //      一个"双向断言"会把 NULL 这个【合法且必须存在】的状态判成非法，
        //      从而逼着调用方把缺失写成 FALSE 或 TRUE —— 那正是 §4.3 要防的事。
        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002fd"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        D_FLAG_NULL,
                        null, GapReason.BEYOND_RETENTION_WINDOW, null, N_OK), OPERATOR).outcome(),
                "🛑 coverage_flag=NULL（缺失，§4.3『严禁补 0』）+ gap_reason 必须能写入 —— "
                        + "若建不上，说明门禁被判成了双向，"
                        + "而 NULL 恰恰是 §4.3 要求【必须】能表达的那个状态");

        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002fc"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        D_FLAG_FALSE,
                        Boolean.FALSE, GapReason.NO_OPEN, null, N_OK), OPERATOR).outcome(),
                "🛑 coverage_flag=FALSE（明确判定缺失）+ gap_reason 也必须能写入");
    }

    // ==================================================================
    // 判据 ⑦
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 跨租户：⑦a 探针撞号【可达】⇒ RAISE(P0001) 且零残留；⑦b 覆盖撞号【结构性不可达】⇒ 断言封堵是活的")
    void cross_tenant_collisions_are_handled_differently_by_the_two_primitives() {
        // ==============================================================
        // ⑦a probe_id 跨租户撞号 —— 可达，必须 RAISE
        // ==============================================================
        assertEquals(0, probeRows(TENANT_OTHER, PROBE_SHARED),
                "前置失败：PROBE_SHARED 应尚未登记");

        // ① 另一个租户先占用这个全局 probe_id
        assertEquals(SyncProbeOutcome.CREATED,
                probe(fullProbe(PROBE_SHARED, BAND_OTHER, TENANT_OTHER), "ops-a3-other").outcome(),
                "🛑 前提：另一个租户必须先成功占用这个 probe_id。"
                        + "若这里就失败了，本判据的靶心（撞号）根本没有被构造出来");
        assertEquals(1, probeRows(TENANT_OTHER, PROBE_SHARED));

        // ② 主租户用【同一个】probe_id（但指向本租户自己的设备）⇒ 必须 RAISE
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> probe(fullProbe(PROBE_SHARED, BAND, TENANT), OPERATOR),
                "🛑🛑 以本租户身份用【已被另一租户占用】的 probe_id 登记竟然成功了 —— "
                        + "probe_id 是【全局单列】主键，而主键冲突会走 ON CONFLICT DO NOTHING "
                        + "⇒ ROW_COUNT=0，而本租户上下文里 SELECT 又看不见那一行 ⇒ "
                        + "若没有那条 RAISE 判定，函数会对一个手里一行都没有的租户返回 "
                        + "ALREADY_EXISTS，调用方据此认为『探针已登记』，"
                        + "而实际上该设备在本租户内【没有任何 N 的留痕】⇒ "
                        + "补拉会按『未探到 N』运行，并以【假缺失】的形式出现在 A3 里 "
                        + "—— 即扣了客户的分，而设备侧毫无问题");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V22 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 主键冲突路径而不是那条"
                        + "跨租户判定 —— 两者的可归因性完全不同。异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("另一个租户"),
                "🛑 拒绝必须是【写明理由】的一条错误（probe_id 已被另一租户占用）。"
                        + "异常链=" + fullCauseMessages(ex));

        // ③ 🛑 本租户内【不得留下任何行】
        assertEquals(0, probeRows(TENANT, PROBE_SHARED),
                "🛑 被拒之后本租户内不得留下该 probe_id 的任何行 —— "
                        + "若这里 > 0，说明主键冲突被绕过（例如先 DELETE 再 INSERT），"
                        + "而那会把另一个租户的探针删掉");

        // ④ 对照：本租户【自己的】探针重放必须 ALREADY_EXISTS（不是 RAISE）
        //   🛑 顺序纪律（AgreementGateTest 首跑实测）：④ 自己建自己用，
        //      不依赖 ② 是否跑完 —— 判据之间不得有隐式的"我先替你把数据建好"依赖。
        SyncProbeOutcome built = probe(plainProbe(TENANT, PROBE_SENTINEL, BAND), OPERATOR).outcome();
        assertTrue(built == SyncProbeOutcome.CREATED || built == SyncProbeOutcome.ALREADY_EXISTS,
                "🛑 建/重放的返回只允许 CREATED 或 ALREADY_EXISTS，实际=" + built);

        assertEquals(SyncProbeOutcome.ALREADY_EXISTS,
                probe(plainProbe(TENANT, PROBE_SENTINEL, BAND), OPERATOR).outcome(),
                "🛑 本租户自己已有这个探针时，重放必须返回 ALREADY_EXISTS 而【不是】抛异常 —— "
                        + "『冲突了 ⇒ 是不是我的』这个判定必须真的区分两种情形，"
                        + "否则一个『任何冲突都 RAISE』的实现也能通过 ⑦a②，而那会毁掉幂等");

        // ==============================================================
        // ⑦b (device_id, date) 跨租户撞号 —— 🛑 结构性不可达
        // ==============================================================
        //   🛑🛑 本段是本判据里最需要说清的一段，因为它断言的事情与 ⑦a 相反。
        //
        //   为什么不可达：coverage 的唯一载体是 `uq_daily_coverage_device_date
        //   UNIQUE (device_id, date)` —— 它**不含 tenant_id**，看起来跨租户撞号可行。
        //   但 `device_id` 是 `band.band_id`，而 `band_id` 是【全局单列主键】
        //   ⇒ 同一台设备只可能属于一个租户 ⇒ "两个租户共用一台设备"这件事
        //   在数据模型上就构造不出来。
        //
        //   ⇒ 故 V22 里那条 coverage 跨租户 RAISE 是【fail-closed 的防御】，
        //     不是可达路径。本段要证明的是【那条防御是活的】，
        //     即"绕过函数也走不通" —— 而不是为它编一个假的成功用例。
        //
        //   🛑 判据的适用范围 = 它的锚点范围：声称一个不可达路径"被正确拒绝"
        //      比不写它更坏（下一个人会照着它去找一个不存在的用例）。

        // ① 先证明"另一租户的手环在本租户内确实不可见"（否则下面的结论无意义）
        Integer visibleInTenant = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM band WHERE band_id = ?::uuid", Integer.class, BAND_OTHER));
        assertEquals(0, visibleInTenant == null ? 0 : visibleInTenant.intValue(),
                "🛑 前提：另一租户的手环在【本租户上下文里】必须不可见（band 的 RLS 是 fail-closed）"
                        + "—— 若它可见，说明租户隔离底座本身失效了，"
                        + "而本段的全部结论都建立在这条前提上");

        // ② 函数侧：用另一租户的设备在本租户登记覆盖 ⇒ 被 (4) 拒（可归因的 P0001）
        String fnState = writeExpectingFailure(TENANT,
                CALL_COVERAGE, TENANT, COV_CROSS_CUST, BAND_OTHER, CUSTOMER, D_CROSS,
                Boolean.TRUE, null, null, null, null, N_OK, null, null);
        assertEquals("P0001", fnState,
                "🛑 函数侧必须给出【可归因】的 P0001（『手环在租户内不存在，本租户可见行数=0』），"
                        + "而不是让它去撞复合外键的 23503（那只会给调用方一个约束名）。实际=" + fnState);

        // ③ 🛑 裸 SQL 绕过函数 ⇒ 必须被拒（证明封堵是活的，且不是函数独有的）
        String bareState = writeExpectingFailure(TENANT,
                "INSERT INTO band_daily_coverage (coverage_id, tenant_id, device_id, customer_id,"
                        + " date, coverage_flag) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid,"
                        + " ?::date, TRUE)",
                COV_BARE, TENANT, BAND_OTHER, CUSTOMER, java.sql.Date.valueOf(D_CROSS));
        assertNotNull(bareState,
                "🛑🛑 裸 SQL 用【另一租户的设备】竟然插进去了 —— "
                        + "那说明 V16 的复合外键 (tenant_id, device_id) → band(tenant_id, band_id) 失效了，"
                        + "而 RLS【从不】保证引用归属（它只保护行归属）");
        assertTrue("23503".equals(bareState) || "42501".equals(bareState),
                "🛑 裸 SQL 绕过函数时必须被复合外键(23503)或 RLS(42501)拒，实际=" + bareState);
        assertEquals(0, coverageRows(TENANT, BAND_OTHER, D_CROSS), "被拒后不得落库");

        // ④ 🛑 结构性不可达的正面陈述：把"同一 device_id 只属于一个租户"钉成事实
        //
        //   🛑🛑 本段是本类【改过一次】的地方，改动理由值得逐字记下 ——
        //       它是"断言必须由元数据给出、而不是由 RLS 过滤后的行数给出"的实例。
        //
        //   初版写的是：
        //       jdbc.queryForObject("SELECT count(DISTINCT tenant_id) FROM band WHERE band_id = ?")
        //   然后断言 == 1。**首跑实测返回 0**。两条错，且是同一类：
        //     ① `band` 实测 relforcerowsecurity = true 且属主 diaoyuanyun **不是超级用户**
        //        ⇒ 无 `SET LOCAL app.tenant_id` 时 RLS fail-closed，它返回 **0 行**；
        //     ② 更要命的是：即便在 `TENANT` 上下文里查，它也会**恒返回 1**
        //        —— 因为 RLS 已经把可见集限定成"本租户的那一行"，
        //        于是"只属于一个租户"这句话**根本没有被检验**。
        //        一条恒为真（或恒为假）的断言没有判别力 —— 这是本仓纪律
        //        「只断言『被拒』的判据没有判别力」的另一面。
        //   ⇒ 正确证法：问**目录表**（{@code pg_constraint}），它不受 RLS 影响，
        //      且直接回答"这一列是不是全局唯一"。这正是本节要证的那件事。
        // 🛑 也正因如此，本类**没有**引入任何"跳出 RLS 的连接"——
        //    用元数据就够，而"为测试开一条绕过 RLS 的通道"本身就该避免。
        Map<String, Object> bandPk = jdbc.queryForMap(
                "SELECT c.conname AS name, pg_get_constraintdef(c.oid) AS def"
                        + "  FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE n.nspname = 'public' AND t.relname = 'band' AND c.contype = 'p'");
        assertEquals("PRIMARY KEY (band_id)", bandPk.get("def"),
                "🛑🛑 这就是 ⑦b 那条「结构性不可达」的全部根据："
                        + "`band` 的主键必须是**【单列】band_id**。"
                        + "若它变成复合键（例如 (tenant_id, band_id)），"
                        + "同一台设备的同一条 band_id 就能在两个租户下各存一行 ⇒ "
                        + "`(device_id, date)` 的跨租户撞号从『构造不出来』变成【可达】，"
                        + "V22 里那条 coverage 跨租户 RAISE 也随之从"
                        + "『fail-closed 防御』升级为『必经门禁』—— 本节的全部结论随之作废。"
                        + "实际=" + bandPk.get("def"));

        // 🛑 同一句话的另一半：coverage 的唯一载体**不含** tenant_id。
        //    这半句是"跨租户撞号【看起来】可行"的来源，必须与上一条一起钉住 ——
        //    否则下一个人只看到"设备全局唯一"，会以为不需要那条防御。
        Map<String, Object> covUq = jdbc.queryForMap(
                "SELECT c.conname AS name, pg_get_constraintdef(c.oid) AS def"
                        + "  FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE n.nspname = 'public' AND t.relname = 'band_daily_coverage'"
                        + "   AND c.contype = 'u'");
        assertEquals("uq_daily_coverage_device_date", covUq.get("name"),
                "🛑 coverage 的唯一载体必须仍叫 uq_daily_coverage_device_date，实际=" + covUq.get("name"));
        assertEquals("UNIQUE (device_id, date)", covUq.get("def"),
                "🛑🛑 该唯一载体必须**不含 tenant_id**（实测逐字 UNIQUE (device_id, date)）—— "
                        + "这正是『跨租户撞号在语法上可能』的来源（虽被上面的单列主键在数据上封死）。"
                        + "两个事实合起来才是 ⑦b 的完整论证："
                        + "【语法上可能】+【数据上不可达】⇒ 那条 RAISE 是 fail-closed 的防御。"
                        + "若这里变成 (tenant_id, device_id, date)，"
                        + "则同一台设备同一天在两个租户下可各存一行 —— "
                        + "而那时『本租户这一天的覆盖静默缺失』这条风险就不存在了，"
                        + "V22 的措辞也需重新核对。实际=" + covUq.get("def"));
    }

    // ==================================================================
    // 判据 ⑧
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑧ 上下文一致性守卫：已持有别的租户上下文时，两条写路径都必须被拒（不允许静默改写）")
    void an_existing_foreign_tenant_context_blocks_both_paths() {
        // 🛑 V22 的 (2) 上下文一致性守卫（两个函数各一份）：若本事务已持有别的租户上下文，
        //    直接 RAISE。理由：set_config(..., is_local := true) 是事务级设置且
        //    不会在函数入口自动重置，静默覆盖会让"后续读到的是谁的留痕"变得不可推理。
        //
        //   🛑 本域这条比前几批更贵：探针写的是"补拉窗口"的取证材料（它决定 A3 的
        //      分子能不能取到数）；覆盖写的是"客户那一天算不算 0 分"的依据。
        //      写错租户 = 让另一个租户的客户被按错误的 N 记账 / 被错误扣分。

        // ---- ⑧a 探针侧 ----
        String probeState = writeExpectingFailureCtx(TENANT_OTHER,
                CALL_PROBE, TENANT, PROBE_CTX, BAND, HistoryMetric.SLEEP.code(),
                null, N_OK, ProbeSource.RUNTIME_PROBE.code(), null, false, null);
        assertNotNull(probeState, "🛑 已持有别的租户上下文时竟然登记成功了 —— 上下文一致性守卫失效");
        // 🛑 两者都 fail-closed，但只有 P0001 说明"一致性守卫"这一层在起作用。
        //    （42501 是 RLS 的 WITH CHECK 在拦，那是另一层、理由不同）
        assertTrue(probeState.contains("P0001"),
                "🛑 探针侧必须被 P0001（V22 显式的一致性守卫）拒，实际=" + probeState
                        + "。若是 42501，说明拦住它的是 RLS 而不是那条守卫 —— "
                        + "而两条路径的归因完全不同（前者是『你传错了租户』，后者是『数据越界』）");
        assertEquals(0, probeRows(TENANT, PROBE_CTX), "被拒后不得落库");

        // ---- ⑧b 覆盖侧 ----
        String covState = writeExpectingFailureCtx(TENANT_OTHER,
                CALL_COVERAGE, TENANT, COV_CTX, BAND, CUSTOMER, D_CROSS,
                Boolean.TRUE, null, null, null, null, N_OK, null, null);
        assertNotNull(covState, "🛑 已持有别的租户上下文时竟然登记成功了 —— 守卫失效");
        assertTrue(covState.contains("P0001"),
                "🛑 覆盖侧必须被 P0001 拒，实际=" + covState);
        assertEquals(0, coverageRows(TENANT, BAND, D_CROSS),
                "🛑 被拒后不得落库 —— 若这里 > 0，说明守卫在写入【之后】才判，那就等于没判");
    }

    // ==================================================================
    // 判据 ⑨
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("⑨ 跨租户【引用】必须被拒：设备 / 客户 / 同步日志三种引用各有其可归因的拒绝理由")
    void cross_tenant_references_are_refused_with_the_right_reason() {
        // ---- ⑨a 覆盖：跨租户【客户】引用 ⇒ 函数侧 (4b) 给 P0001 ----
        String custChain = failureChain(TENANT,
                CALL_COVERAGE, TENANT, COV_CROSS_CUST, BAND, CUSTOMER_OTHER, D_CROSS,
                Boolean.TRUE, null, null, null, null, N_OK, null, null);
        assertNotNull(custChain, "🛑 跨租户客户引用竟然成功了 —— RLS + 复合外键双保险都失效了");
        assertTrue(custChain.contains("P0001"),
                "🛑 函数侧应给 P0001（可读的业务错误），而不是让它去撞 23503。链=" + custChain);
        assertTrue(custChain.contains("客户") && custChain.contains("不存在"),
                "🛑 错误消息应说明『客户在租户内不存在』（含『上下文不对会静默返回 0 行』的提示），"
                        + "因为 customer 的 RLS 也是 fail-closed 的。链=" + custChain);

        // ---- ⑨b 覆盖：跨租户【同步日志】引用 ⇒ 函数侧 (4c) 给 P0001 ----
        String logChain = failureChain(TENANT,
                CALL_COVERAGE, TENANT, COV_CROSS_LOG, BAND, CUSTOMER, D_CROSS,
                Boolean.TRUE, null, null, null, null, N_OK, SYNC_LOG_OTHER, null);
        assertNotNull(logChain, "🛑 跨租户同步日志引用竟然成功了");
        assertTrue(logChain.contains("P0001"),
                "🛑 §2.8.7③「断点续传」要求『由哪次同步回捞』可回溯"
                        + "（band_daily_coverage.source_sync_log_id）——"
                        + "指向一条看不见的日志会让这条回溯链断开，故必须被拒。链=" + logChain);

        // ---- ⑨c 覆盖：一个【从不建行】的同步日志 ⇒ 也必须被拒（存在性，不只是租户）----
        String ghostChain = failureChain(TENANT,
                CALL_COVERAGE, TENANT, COV_CROSS_LOG, BAND, CUSTOMER, D_CROSS,
                Boolean.TRUE, null, null, null, null, N_OK, SYNC_LOG_GHOST, null);
        assertNotNull(ghostChain, "🛑 一个不存在的 sync_log_id 竟然被接受了");
        assertTrue(ghostChain.contains("P0001"),
                "🛑 同步日志【不存在】也必须与【跨租户】分开报（两者消息都含『本租户可见行数』）。"
                        + "链=" + ghostChain);

        // ---- ⑨d 探针：跨租户【设备】引用 ⇒ 函数侧 (4) 给 P0001 ----
        String devChain = failureChain(TENANT,
                CALL_PROBE, TENANT, PROBE_CROSS_DEV, BAND_OTHER, HistoryMetric.SLEEP.code(),
                null, N_OK, ProbeSource.RUNTIME_PROBE.code(), null, false, null);
        assertNotNull(devChain, "🛑 跨租户设备引用竟然成功了");
        assertTrue(devChain.contains("P0001"),
                "🛑 探针侧必须也给 P0001（V22 的 (4) 显式检查）—— "
                        + "『探测的对象就是这台设备』，没有它则『这个 N 适用于谁』不可答。链=" + devChain);
        assertEquals(0, probeRows(TENANT, PROBE_CROSS_DEV), "被拒后不得落库");

        // ---- ⑨e 对照：三种引用都合法时必须能成功（否则 ⑨a~⑨d 退化成"什么都拒绝"）----
        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002fb"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        LocalDate.parse("2026-09-29"),
                        Boolean.TRUE, null, null, N_OK), OPERATOR).outcome(),
                "🛑 同租户的设备 + 客户引用必须能成功 —— 否则上面四条"
                        + "『必须被拒』无法与『这条路本身走不通』区分（比较器必须对称）");

        // ---- ⑨f 对照：合法的 source_sync_log_id 也必须能写入（本租户内那条）----
        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(new DailyCoverageRecord(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002fa"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER),
                        LocalDate.parse("2026-09-30"),
                        Boolean.TRUE, null, null, null, null, N_OK,
                        UUID.fromString(SYNC_LOG), null), OPERATOR).outcome(),
                "🟑 本租户内的 sync_log 引用必须能成功 —— 这是 §2.8.7③"
                        + "「由哪次同步回捞」这条可回溯性的正向用例");
    }

    // ==================================================================
    // 判据 ⑩
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("⑩ 形态：无 HTTP 映射 + V22 两原语在位 + 🛑 静态机械核对 枚举↔V22 数组↔契约 enum↔V5 CHECK 四方一致")
    void the_path_is_a_component_and_all_four_vocabularies_agree() throws IOException {
        String ledgerSrc = read("dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/repository/BandRefetchLedger.java");
        String svcSrc = read("dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/service/BandRefetchService.java");

        // ---- ⑩a 🛑 本通路必须【不是】HTTP 端点（A-3 硬边界②）----
        for (String ann : List.of("@RestController", "@Controller", "@RequestMapping",
                "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping")) {
            assertFalse(stripComments(ledgerSrc).contains(ann),
                    "🛑🛑 BandRefetchLedger 上出现了 " + ann + " —— 本迁移是"
                            + "【库层写入原语】（运维/服务通路形态、无 HTTP 映射）。"
                            + "契约 45 个 operationId 里没有任何一个是『登记一条留存探针』"
                            + "或『登记一条逐日覆盖』；给它加对外端点属契约 MAJOR 变更。"
                            + "而 §2.8.7 明示厂商 SDK / 13 条接口 / 游标翻页"
                            + "属『文档自证、待真机复核』的外部依赖 —— "
                            + "本迁移接收调用方已拿到的结果，不解析厂商协议");
            assertFalse(stripComments(svcSrc).contains(ann),
                    "🛑 BandRefetchService 上出现了 " + ann + " —— 同 ⑩a 的理由");
        }
        assertTrue(stripComments(ledgerSrc).contains("@Component"),
                "BandRefetchLedger 必须是 @Component（它要被装配进 Service）");
        assertTrue(stripComments(svcSrc).contains("@Service"),
                "BandRefetchService 必须是 @Service");

        // ---- ⑩b 🛑 V22 的两个原语必须被调用（本通路的存在意义）----
        assertTrue(ledgerSrc.contains("register_sync_probe("),
                "BandRefetchLedger 必须调用 V22 的 register_sync_probe() —— "
                        + "若把它换成裸 INSERT，则上下文守卫 / 跨租户撞号判定 / "
                        + "14 值词表断言 / 自证全部旁落");
        assertTrue(ledgerSrc.contains("register_daily_coverage("),
                "BandRefetchLedger 必须调用 V22 的 register_daily_coverage() —— "
                        + "若把它换成裸 INSERT，则🛑🛑核心门禁（(5c) not_worn × 技术性）"
                        + "与归属客户判定全部旁落，而那两件事正是本迁移的全部意义");

        // ---- ⑩c V22 的两条读侧口径也必须在位（写侧之外还提供读侧核对）----
        assertTrue(ledgerSrc.contains("inTenant("),
                "BandRefetchLedger 的读侧必须走 inTenant（与业务读路径同一套上下文机制）—— "
                        + "少了 SET LOCAL，读会静默返回 0 行，与『这一行不存在』同形（假绿）");

        // ---- ⑩d 🛑 V22 迁移在库层在位（真库事实，不是静态）----
        for (String fn : List.of("register_sync_probe", "register_daily_coverage")) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_proc WHERE proname = ?", Integer.class, fn);
            assertEquals(1, n == null ? 0 : n.intValue(),
                    "🛑 pg_proc 里必须恰有 1 个 " + fn + " —— 若为 0，V22 未应用；"
                            + "若 > 1，说明存在重载（签名分叉会让调用方命中错的那个）");
            String prosrc = jdbc.queryForObject(
                    "SELECT prosrc FROM pg_proc WHERE proname = ?", String.class, fn);
            assertNotNull(prosrc, "找不到 " + fn + " 的 prosrc");
            assertTrue(prosrc.contains("set_config"),
                    "🛑 V22 的 " + fn + " 里必须有 set_config（建立租户上下文）—— "
                            + "没有它，写侧就落在没有上下文的连接上，RLS 的 WITH CHECK 会拒，"
                            + "而那是一条 42501（无归因）");
        }
        String covProsrc = jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'register_daily_coverage'", String.class);
        assertTrue(covProsrc.replaceAll("\\s+", " ").contains("p_gap_reason = 'not_worn'"),
                "🛑🛑 V22 的 register_daily_coverage 里找不到 (5c) 核心门禁 —— "
                        + "库层门禁缺失会让『绕过 Java 构造器直写 SQL』时"
                        + "把设备故障记成客户没戴");
        assertTrue(covProsrc.replaceAll("\\s+", " ").contains("v_tech_iswear"),
                "🛑 那条门禁必须引用 v_tech_iswear（-1 与 255 两个技术性取值）—— "
                        + "只判 -1 会漏掉 255（两个都是技术性，§2.8.7⑤ 逐字写 `(-1,255)`）");

        // ---- ⑩e 🛑🛑 静态机械核对：四方词汇一致 ----
        String v22 = read("dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql");

        // ① 14 值指标：V22 的 v_hist_types ↔ HistoryMetric 枚举
        Set<String> sqlHist = quotedStringSetAfter(v22, "v_hist_types");
        assertEquals(new LinkedHashSet<>(HistoryMetric.allCodes()), sqlHist,
                "🛑🛑 V22 的 v_hist_types 与 HistoryMetric 枚举必须【完全相等】—— "
                        + "两边分叉会让『Java 放行而库层拒绝』（或反之），"
                        + "而那是『同一件事有两处定义』的典型后果。"
                        + "SQL 侧=" + sqlHist + "，枚举侧=" + HistoryMetric.allCodes());
        assertEquals(14, sqlHist.size(),
                "🛑 指标词汇必须【恰 14 个】—— 13 按日型 + 1 游标型（sport）。"
                        + "少一个 = §2.8.7③ 的 min(各 history_type 的 N) 会漏掉一路；"
                        + "多一个 = 有一个未登记的指标能进台账。实际=" + sqlHist);

        // ② 14 值指标：V22 ↔ V5 的 band_sync_probe CHECK
        Set<String> v5Hist = checkInSetOf(read(
                "dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql"),
                "history_type");
        assertEquals(new LinkedHashSet<>(HistoryMetric.allCodes()), v5Hist,
                "🛑 V5 的 band_sync_probe.history_type CHECK 也必须与枚举一致 —— "
                        + "若只有 V22 一致而表层 CHECK 不一致，"
                        + "则『函数放行、表拒绝』的形态会以 23514（无归因）暴露。"
                        + "CHECK 侧=" + v5Hist);

        // ③ 7 值缺口原因：V22 的 v_gap_reasons ↔ GapReason 枚举
        Set<String> sqlGap = quotedStringSetAfter(v22, "v_gap_reasons");
        assertEquals(new LinkedHashSet<>(GapReason.allCodes()), sqlGap,
                "🛑🛑 V22 的 v_gap_reasons 与 GapReason 枚举必须【完全相等】。"
                        + "SQL 侧=" + sqlGap + "，枚举侧=" + GapReason.allCodes());

        // ④ 7 值缺口原因：V22 ↔ V5 CHECK
        Set<String> v5Gap = checkInSetOf(read(
                "dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql"),
                "gap_reason");
        assertEquals(new LinkedHashSet<>(GapReason.allCodes()), v5Gap,
                "🛑 V5 的 gap_reason CHECK 必须与枚举一致。CHECK 侧=" + v5Gap);
        assertEquals(7, v5Gap.size(),
                "🛑 缺口原因必须【恰 7 个】—— 🛑 A-3 的硬边界①明示"
                        + "【不新增枚举值】（属契约变更，登记待裁）。实际=" + v5Gap);

        // ⑤ 🛑🛑 7 值缺口原因：契约 enum ↔ GapReason 枚举（本项是"契约是权威"的落点）
        Set<String> contractGap = contractGapReasonEnum();
        assertEquals(new LinkedHashSet<>(GapReason.allCodes()), contractGap,
                "🛑🛑 契约 BandDailyData.gap_reason.enum 与 GapReason 枚举必须【逐字一致】—— "
                        + "契约侧=" + contractGap + "，枚举侧=" + GapReason.allCodes()
                        + "。（V22 的 (5a) 错误消息里逐字声明了这条一致性关系）");

        // ⑥ 4 值佩戴态：V22 的 v_tech_iswear ↔ WearState
        Set<Integer> sqlTech = intArrayAfter(v22, "v_tech_iswear");
        assertEquals(new LinkedHashSet<>(WearState.technicalCodes()), sqlTech,
                "🛑🛑 V22 的 v_tech_iswear 与 WearState.technicalCodes() 必须【完全相等】—— "
                        + "SQL 侧=" + sqlTech + "，枚举侧=" + WearState.technicalCodes());

        // ⑦ 4 值佩戴态：V22 的显式拒绝了 >{-1,0,1,255} 之外的取值；与枚举 allCodes 对照
        Set<Integer> sqlAllWear = intSetInFirstArrayAfter(v22, "p_is_wear = ANY (ARRAY[");
        assertEquals(new LinkedHashSet<>(WearState.allCodes()), sqlAllWear,
                "🛑 V22 的 is_wear 词表断言（(5b)）与 WearState 枚举必须一致 —— "
                        + "SQL 侧=" + sqlAllWear + "，枚举侧=" + WearState.allCodes());
        assertEquals(4, sqlAllWear.size(),
                "🛑 佩戴态必须【恰 4 个】—— §2.8.7⑤ 逐字：1=佩戴 / 0=脱腕(行为性) / (-1,255)=技术性。"
                        + "实际=" + sqlAllWear);

        // ⑦b 🛑🛑 4 值佩戴态：V5 的**表层 CHECK**（真库目录表）↔ WearState
        //    🛑 为什么必须有这一条（首跑实测暴露的缺口）：
        //      V5 里 is_wear 的写法是 `INT CHECK (is_wear IS NULL OR is_wear IN (-1,0,1,255))`
        //      —— **没有 VARCHAR(n)、没有引号**，与 history_type / probe_source / gap_reason
        //      三种写法都不同。故它**走不了** checkInSetOf（那会报锚点失效），
        //      于是本判据在初版里**完全没核对过这一条** —— 即
        //      "V22 函数里的 (5b) 一致，但表层 CHECK 漂移"这一形态对本判据不可见。
        //      而那道表层 CHECK 是**绕过函数的裸 SQL** 唯一会遇到的东西（判据 ⑦b 已证其存在）。
        //    ⇒ 改问 pg_constraint：它给规范化后的定义，验证的是**真库里生效的那一条**。
        Set<Integer> v5Wear = checkIntSetFromCatalog("band_daily_coverage", "is_wear");
        assertEquals(new LinkedHashSet<>(WearState.allCodes()), v5Wear,
                "🛑🛑 V5 的 band_daily_coverage.is_wear 表层 CHECK 必须与 WearState 枚举一致 —— "
                        + "若只有 V22 一致而表层不一致，"
                        + "则『裸 SQL 绕过函数』时会漏进一个两边都不认的取值，"
                        + "而它**不会报错**（它会安静地待在那里，直到某次 A3 取数）。"
                        + "CHECK 侧（pg_constraint 规范化后）=" + v5Wear
                        + "，枚举侧=" + WearState.allCodes());

        // ⑧ 3 值探测来源：V22 的 v_probe_srcs ↔ ProbeSource 枚举（中文枚举是 V5 既成事实）
        Set<String> sqlSrcs = quotedStringSetAfter(v22, "v_probe_srcs");
        assertEquals(new LinkedHashSet<>(ProbeSource.allCodes()), sqlSrcs,
                "🛑 V22 的 v_probe_srcs 与 ProbeSource 枚举必须一致（3 值中文）。"
                        + "SQL 侧=" + sqlSrcs + "，枚举侧=" + ProbeSource.allCodes());
        Set<String> v5Srcs = checkInSetOf(read(
                "dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql"),
                "probe_source");
        assertEquals(new LinkedHashSet<>(ProbeSource.allCodes()), v5Srcs,
                "🛑 V5 的 probe_source CHECK 也必须与枚举一致。CHECK 侧=" + v5Srcs);

        // ---- ⑩f 🛑 面向客户的硬约束：gap_reason 必须声明"客户恒 403" ----
        String yaml = readContract();
        assertTrue(yaml.contains("客户恒 403"),
                "🛑🛑 契约里 BandDailyData.gap_reason 必须声明"
                        + "『🔴 客户恒 403 / 不下发（硬约束，配置不得放开）』—— "
                        + "它是本域唯一与客户直接相关的合规红线（§3.1③）。"
                        + "若这条注释被删，下一个人会以为它是可配置的可见性");

        // ---- ⑩g 🛑 Flyway 占位符替换必须仍关闭（A-1 实测缺陷的回归哨兵）----
        assertTrue(read("dy-app/src/main/resources/application.yml")
                        .contains("placeholder-replacement: false"),
                "🛑 application.yml 的 spring.flyway 必须仍显式写 "
                        + "`placeholder-replacement: false` —— A-1 的 V21 实测过这条缺陷："
                        + "迁移里引用 ${...} 会让整个 Spring 上下文起不来。"
                        + "V22 里【没有】${，但那条开关是【全局】的，"
                        + "任何后来的迁移都可能踩它 —— 故本判据在此再钉一次");
    }

    // ==================================================================
    // 判据 ⑪
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("⑪ 🛑 覆盖行的【归属客户】不得被一次补拉改写 —— uq 只保证「一行」，不保证「同一个客户」")
    void a_coverage_row_owner_customer_must_not_be_rewritten() {
        // ---- ⑪a 首次：由 C1 建（这一天只可能是 C1 的观测日）----
        assertEquals(DailyCoverageOutcome.CREATED,
                coverage(DailyCoverageRecord.of(TENANT, UUID.fromString(COV_OWNER),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_OWNER,
                        Boolean.TRUE, null, null, N_OK), OPERATOR).outcome(),
                "前提：D_OWNER 这一天必须先由 C1 建出来");
        String[] s0 = coverageState(TENANT, BAND, D_OWNER);
        assertNotNull(s0);
        assertEquals(CUSTOMER, s0[5], "归属客户必须是 C1");

        // ---- ⑪b 🛑 把 band 重新绑定到 C2（同一租户内的合法业务事实变更）----
        //   🛑 为什么这一步必须在【本租户内】做、而不是伪造一个跨租户：
        //      本判据要证的不是"跨租户"，而是"**归属**不得被覆盖改写"。
        //      跨租户那件事已由 ⑦b / ⑨ 守着（且 ⑦b 证明它结构性不可达）。
        inTenant(TENANT, () -> {
            jdbc.update("UPDATE band SET customer_id = ?::uuid WHERE band_id = ?::uuid",
                    CUSTOMER_2, BAND);
            return null;
        });
        String nowOwner = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT customer_id::text FROM band WHERE band_id = ?::uuid", String.class, BAND));
        assertEquals(CUSTOMER_2, nowOwner,
                "前提：band 必须已重新绑定到 C2 —— 若这一步没生效，"
                        + "下面的『归属不得被改写』就无从被构造出来");

        // ---- ⑪c 🛑 用【新客户 C2】去覆盖同一个 (device, date) ⇒ 必须 RAISE ----
        String chain = failureChain(TENANT,
                CALL_COVERAGE, TENANT, COV_OWNER,
                // 🛑 同一个 coverage_id 也用上：本条要证的是"无论换不换 id，归属都不许改"
                BAND, CUSTOMER_2, D_OWNER,
                Boolean.TRUE, null, null, null, null, N_OK, null, null);
        assertNotNull(chain,
                "🛑🛑 一次补拉把一个客户的观测日【悄悄转记】到了另一个客户名下 —— "
                        + "uq_daily_coverage_device_date 实测逐字 UNIQUE (device_id, date)，"
                        + "它只保证『一行』，**不保证『同一个客户』**。"
                        + "而 A3 是『唯一可扣分』的指标 —— 这种转记既可能冤枉甲，也可能放过乙。"
                        + "V22 的 ON CONFLICT … DO UPDATE … WHERE c.customer_id = p_customer_id "
                        + "必须把它挡掉");
        assertTrue(chain.contains("P0001"),
                "🛑 拒绝必须是 P0001（V22 的显式 RAISE），链=" + chain);
        assertTrue(chain.contains("归属") || chain.contains("客户"),
                "🛑 拒绝必须【写明理由】（命中行的归属客户与本次传入的不一致），"
                        + "且应提示『若确属设备被重新绑定，那是一次业务事实变更，"
                        + "须走解绑/重绑流程』。链=" + chain);

        // ---- ⑪d 🛑 归属未被改写（行仍在 C1 名下）----
        String[] s1 = coverageState(TENANT, BAND, D_OWNER);
        assertNotNull(s1, "行不得消失");
        assertEquals(CUSTOMER, s1[5],
                "🛑🛑 归属客户必须【仍是 C1】—— 若这里变成了 C2，说明那条 WHERE 被判漏了，"
                        + "而这一次『转记』不会报任何错：它只会让 A3 在另一个客户身上算错");

        // ---- ⑪e 对照：把 band 重新绑回 C1 之后，同一 (device, date) 必须能正常刷新 ----
        //   🛑 这条是判别力自证：若不做它，"凡归属不符一律拒绝"就无法与
        //      "这条路本身走不通"区分。
        inTenant(TENANT, () -> {
            jdbc.update("UPDATE band SET customer_id = ?::uuid WHERE band_id = ?::uuid",
                    CUSTOMER, BAND);
            return null;
        });
        assertEquals(DailyCoverageOutcome.UPDATED,
                coverage(DailyCoverageRecord.of(TENANT,
                        UUID.fromString("a3000000-0000-0000-0000-0000000002f9"),
                        UUID.fromString(BAND), UUID.fromString(CUSTOMER), D_OWNER,
                        Boolean.TRUE, null, null, 11), OPERATOR).outcome(),
                "🛑 绑回 C1 之后必须能正常覆盖（UPDATED）—— 否则 ⑪c 的『必须被拒』"
                        + "无法与『这个 (device,date) 本身就写不进去』区分");
    }

    // ==================================================================
    // 判据 ⑫
    // ==================================================================

    @Test
    @Order(12)
    @DisplayName("⑫ 🛑 本通路【不提供】任何改写/删除入口（静态）：无 UPDATE / DELETE 这两张表，也无『改判缺口原因』原语")
    void the_path_deliberately_provides_no_rewrite_or_delete_entry() throws IOException {
        for (String rel : List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/repository/BandRefetchLedger.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/service/BandRefetchService.java")) {
            String src = stripComments(read(rel));
            for (String table : List.of("band_sync_probe", "band_daily_coverage")) {
                assertFalse(src.matches("(?s).*\\bDELETE\\s+FROM\\s+" + table + "\\b.*"),
                        "🛑 " + rel + " 里出现了 DELETE FROM " + table + " —— "
                                + "探针是 N 的【举证材料】、覆盖是 A3 的【取值来源】："
                                + "删除会毁掉『当时用哪个 N 补拉的』与『那一天为什么算 0 分』"
                                + "这两条证据链。合法的清理路径是 is_test 标记 + 正当清洗流程，"
                                + "而不是在本通路里开一个删除入口");
                assertFalse(src.matches("(?s).*\\bUPDATE\\s+" + table + "\\b.*"),
                        "🛑 " + rel + " 里出现了 UPDATE " + table + " —— "
                                + "本通路的全部写入都必须经 V22 的原语："
                                + "绕过原语的 UPDATE 会同时绕过上下文守卫 / 跨租户判定 / "
                                + "🛑🛑 核心门禁（not_worn × 技术性 isWear）。"
                                + "覆盖刷新的唯一合法路径是 register_daily_coverage() 的 upsert，"
                                + "它的 DO UPDATE 子句【已含】归属与租户的 WHERE 判定");
            }
        }

        // 🛑 也不得提供"改判缺口原因"这类看似无害的原语名
        String svc = stripComments(read(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/service/BandRefetchService.java"));
        for (String banned : List.of("overrideGapReason", "remarkNotWorn", "correctGapReason",
                "clearCoverage", "resetCoverage", "deleteProbe")) {
            assertFalse(svc.contains(banned),
                    "🛑 BandRefetchService 里出现了 " + banned + " —— 同 ⑫ 的理由："
                            + "任何『改判缺口原因』的第二通路都【等于】开设一条绕过核心门禁的旁路，"
                            + "而那条门禁挡的正是『把设备故障记成客户没戴』这一形态");
        }

        // ---- ⑫b 🛑 本域的坏数据发现入口必须在（弥补"库层门禁刻意不是表约束"）----
        //   核心门禁是**函数内的条件判定**，不是表上的 CHECK ⇒ 它拦得住本函数，
        //   拦不住"在 V22 之前写入的行"或"绕过函数的坏数据"。
        //   故本域必须有且只有一个 read-side 扫描入口，且它真的查库。
        String ledgerSrc = stripComments(read(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/repository/BandRefetchLedger.java"));
        assertTrue(ledgerSrc.contains("scanNotWornGateViolations"),
                "🛑 仓储必须提供 scanNotWornGateViolations —— 本域唯一的坏数据发现入口");
        assertTrue(ledgerSrc.contains("gap_reason = 'not_worn'"),
                "🛑 该扫描必须真的按 gap_reason = 'not_worn' 过滤（SQL 侧）—— "
                        + "若它改成一个恒空的查询，本域就失去了对既有坏数据的可见性。"
                        + "⚠️ 注意：这里的引号是【中文/英文】都要检查的，"
                        + "而本断言用的是英文单引号 —— V22 与仓储 SQL 两侧都是英文单引号");
    }

    // ==================================================================
    // 三、审计断言
    // ==================================================================

    private static int countAudit(String action, String targetId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId);
        return n == null ? 0 : n;
    }

    /**
     * 断言审计条目存在且 action / target 正确（{@code audit_log} 无 RLS，不需上下文）。
     */
    private static void assertAudit(String auditId, String action, String targetId, String payloadFragment) {
        assertNotNull(auditId, "回执必须带 auditId（append() 的返回值，不是调用方自编的）");
        assertFalse(auditId.isBlank(), "auditId 不得为空白");
        Map<String, Object> row = jdbc.query(
                // 🛑 必须显式 `?::uuid`：实测 `audit_log.id` 是 **uuid** 类型，
                //    而绑定参数是 String ⇒ 不转型会报
                //    `操作符不存在: uuid = character varying`（AgreementGateTest 首跑实测）。
                //    🛑 这个坑值得复述：`target_id` 是 varchar(128)、`id` 是 uuid ——
                //      同一张表里两个"看起来都是标识"的列类型不同。
                "SELECT action, target_type, target_id, actor, payload FROM audit_log WHERE id = ?::uuid",
                rs -> rs.next() ? Map.of(
                        "action", String.valueOf(rs.getString("action")),
                        "target_type", String.valueOf(rs.getString("target_type")),
                        "target_id", String.valueOf(rs.getString("target_id")),
                        "actor", String.valueOf(rs.getString("actor")),
                        "payload", String.valueOf(rs.getString("payload"))) : null,
                auditId);
        assertNotNull(row,
                "🛑 回执里的 auditId 在库里查不到 —— 那说明 append() 传来的 id 是调用方自编的、"
                        + "而不是实现生成的（B-7 已付费记录过这个缺陷）");
        assertEquals(action, row.get("action"), "审计 action 必须正确");
        assertEquals(targetId, row.get("target_id"), "审计 target_id 必须正确");
        assertTrue(String.valueOf(row.get("payload")).contains(payloadFragment),
                "审计 payload 应含 " + payloadFragment + "，实际=" + row.get("payload"));
        assertTrue(row.get("target_type").equals(TARGET_PROBE)
                        || row.get("target_type").equals(TARGET_COVERAGE),
                "审计 target_type 必须是两只表之一，实际=" + row.get("target_type"));
    }

    // ==================================================================
    // 四、静态工具
    // ==================================================================

    /** 取出 {@code <constName> text[] := ARRAY[ 'a','b', … ]} 里的字符串字面量集合。 */
    private static Set<String> quotedStringSetAfter(String sql, String constName) {
        Matcher m = Pattern.compile(Pattern.quote(constName)
                + "\\s+text\\[\\]\\s*:=\\s*ARRAY\\s*\\[").matcher(sql);
        assertTrue(m.find(),
                "迁移文本里找不到常量定义式 `" + constName + " text[] := ARRAY[` —— 锚点失效");
        int bracket = m.end() - 1;
        int end = sql.indexOf(']', bracket);
        assertTrue(end > bracket, "数组未闭合");
        return stringLiteralsIn(sql.substring(bracket + 1, end));
    }

    /** 取出 {@code ARRAY[ -1, 255 ]} 里的整数集合（用于 {@code v_tech_iswear}）。 */
    private static Set<Integer> intArrayAfter(String sql, String constName) {
        Matcher m = Pattern.compile(Pattern.quote(constName)
                + "\\s+int\\[\\]\\s*:=\\s*ARRAY\\s*\\[").matcher(sql);
        assertTrue(m.find(),
                "迁移文本里找不到常量定义式 `" + constName + " int[] := ARRAY[` —— 锚点失效");
        int bracket = m.end() - 1;
        int end = sql.indexOf(']', bracket);
        assertTrue(end > bracket, "数组未闭合");
        return intLiteralsIn(sql.substring(bracket + 1, end));
    }

    /** 取出形如 {@code p_is_wear = ANY (ARRAY[0, 1, -1, 255])} 里的整数集合。 */
    private static Set<Integer> intSetInFirstArrayAfter(String sql, String anchor) {
        int at = sql.indexOf(anchor);
        assertTrue(at >= 0, "迁移文本里找不到锚点 `" + anchor + "` —— 锚点失效");
        int bracket = sql.indexOf('[', at);
        int end = sql.indexOf(']', bracket);
        assertTrue(bracket > 0 && end > bracket, "ARRAY[ 未闭合");
        return intLiteralsIn(sql.substring(bracket + 1, end));
    }

    /** 从一段文本里抽出全部 {@code '…'} 字符串字面量，只保留标识符形态。 */
    private static Set<String> stringLiteralsIn(String seg) {
        Set<String> out = new LinkedHashSet<>();
        int i = 0;
        while (i < seg.length()) {
            int q1 = seg.indexOf('\'', i);
            if (q1 < 0) {
                break;
            }
            int q2 = seg.indexOf('\'', q1 + 1);
            if (q2 < 0) {
                break;
            }
            String lit = seg.substring(q1 + 1, q2);
            // 🛑 只收"标识符形态"（小写字母/数字/下划线）—— 中文枚举值
            //    （'运行时探测' 等）不在本方法的用途内（它们在 V5 CHECK 里，
            //    由 checkInSetOf 的另一条路径取出、用同一个"只收标识符"规则会漏掉）。
            //    ⇒ 故 probe_source 的核对必须用【原样字面量】而不是标识符过滤。
            if (lit.matches("[a-z_][a-z0-9_]*") || lit.matches("[\\u4e00-\\u9fff]+")) {
                out.add(lit);
            }
            i = q2 + 1;
        }
        return out;
    }

    /** 从一段文本里抽出全部整数（允许负号与前导逗号/空白）。 */
    private static Set<Integer> intLiteralsIn(String seg) {
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?<![0-9A-Za-z_])-?[0-9]+(?![0-9A-Za-z_])").matcher(seg);
        while (m.find()) {
            out.add(Integer.valueOf(m.group()));
        }
        return out;
    }

    /**
     * 从 V5 里取出 {@code <col> VARCHAR(n) [NOT NULL] CHECK (<col> [IS NULL OR <col>] IN ( 'a','b', … ))}
     * 的字符串集合。
     *
     * <p>🛑 锚点必须落在 {@code CHECK (<col>} 上（定义式），而不是列名本身 ——
     * V5 里 {@code gap_reason} / {@code history_type} / {@code probe_source}
     * 三个词都在注释与 §9 登记段里出现过，用 {@code indexOf} 会锚错。
     *
     * <p>🛑🛑 <b>本方法踩过一个坑，必须写明（否则会反复踩）</b>：
     * 本仓的 V5 里三列有两种<u>不同</u>的写法 ——
     * <pre>
     *   history_type  VARCHAR(32) NOT NULL CHECK (history_type IN (          ← 无 IS NULL OR
     *   probe_source  VARCHAR(32) CHECK (probe_source IS NULL OR probe_source IN (
     *   gap_reason    VARCHAR(32) CHECK (gap_reason   IS NULL OR gap_reason   IN (
     * </pre>
     * 初版正则要求 {@code CHECK (} <b>紧跟</b>列名，于是只匹配得上 {@code history_type} 那种；
     * 另外两列会报"锚点失效"。而 {@code is_wear} 更是第三种：
     * {@code INT CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255))} —— 连 {@code VARCHAR(n)} 都没有。
     * <p>⇒ 故本方法用 {@code (?:IS\s+NULL\s+OR\s+)}? 的可选段统一三种写法，
     * 并把"配对的右括号"定位到 {@code IN (} 的那个左括号上
     * （**不是** {@code CHECK (} 的那个 —— 那是本方法初版最阴的一处：
     * 取错括号会让抽取窗口从 {@code IS NULL OR ...} 开始，
     * 于是 {@code intLiteralsIn} 会多收一个字面量，而 {@code IS NULL} 里的
     * {@code NULL} 又会被字符串过滤规则挡掉 —— 结果是"看起来只多了一个数字"）。
     */
    private static Set<String> checkInSetOf(String sql, String col) {
        Matcher m = Pattern.compile(Pattern.quote(col)
                + "\\s+(?:VARCHAR|TEXT)\\s*\\(\\s*[0-9]+\\s*\\)\\s+(?:NOT\\s+NULL\\s+)?CHECK\\s*\\(\\s*"
                + Pattern.quote(col)
                // 🛑🛑 可选段必须写成 `col (IS NULL OR col)? IN (`——
                //    本方法首跑失败在这里：初版写成 `col (?:(IS NULL OR) )? col IN (`
                //    （即把列名无条件写了两次），于是只有带 IS NULL OR 的两种写法能匹配，
                //    而 `history_type VARCHAR(32) NOT NULL CHECK (history_type IN (`
                //    这种"不带 IS NULL OR"的写法**反而匹配不上** ——
                //    与它想修的 bug 恰好镜像相反。
                + "\\s+(?:IS\\s+NULL\\s+OR\\s+" + Pattern.quote(col) + "\\s+)?IN\\s*\\(").matcher(sql);
        assertTrue(m.find(),
                "V5 里找不到 `" + col + " VARCHAR(n) … CHECK (" + col
                        + " [IS NULL OR " + col + "] IN (` —— 锚点失效");
        int open = m.end() - 1;
        // 🛑 必须是 `IN (` 的那个左括号：上面的正则以 `IN\s*\(` 结尾 ⇒ m.end()-1 就是它。
        //    若把锚点改成 `CHECK\s*\(`，抽取窗口会从 `IS NULL OR` 开始（见方法注释的坑）。
        int end = sql.indexOf(')', open);
        assertTrue(end > open, "CHECK ( … IN ( … ) 未闭合");
        return stringLiteralsIn(sql.substring(open + 1, end));
    }

    /**
     * 从<b>目录表</b>取出某表某列上那条词表 <b>CHECK 约束</b>的整数集合。
     *
     * <h2>🛑 为什么 {@code is_wear} 不能走 {@link #checkInSetOf}</h2>
     * 该列实测是 {@code INT CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255))} ——
     * 既没有 {@code VARCHAR(n)}，也不带引号。用文本正则去匹配它，
     * 就等于"用一条只对某一种书写格式有效的规则去核对一条会被人重排格式的 DDL"。
     * <p>⇒ 改问 {@code pg_constraint}：{@code pg_get_constraintdef} 给的是
     * <b>规范化后</b>的约束定义，格式由 PG 决定，与源码怎么排无关。
     * 它验证的是<b>真库里生效的那一条</b>。
     *
     * <h2>🛑🛑 但"规范化"本身也是一个坑（本方法首跑实测）</h2>
     * PG 会把 {@code IN (a, b, c)} <b>改写成</b>
     * {@code = ANY (ARRAY['a'::integer, b, c])} ——
     * 源码是 {@code IN (…)}，目录里却是 {@code = ANY (ARRAY[…])}。
     * <p>故初版"照抄源码里的 {@code IN (}"去匹配目录表，得到 <b>0 条</b>。
     * 它比"匹配到错的东西"好（0 条会红），但它证不了本方法想证的事。
     * ⇒ 本方法<b>两种形态都认</b>：先试 {@code = ANY (ARRAY[}（规范化形态），
     * 再退回 {@code IN (}（未规范化的形态，例如将来 PG 版本变了）。
     * <p>🛑 这个坑的教训与 {@code checkInSetOf} 那条是<b>同一族</b>：
     * "源码文本"与"库里生效的东西"是两件事；而 <b>{@code pg_get_constraintdef}</b>
     * 又把前者变成了第三种形态。核对词汇表时，锚点必须落在<b>你实际拿到的那份文本</b>上。
     *
     * <p>🛑 本方法能做文本匹配的前提是"这条 CHECK 的形状是词表枚举"——
     * 这是<b>当前实测事实</b>，不是规范；若哪天它被拆成两条 CHECK 或改成别的谓词，
     * 本方法会因为查不到而红（正确行为：红，而不是静默跳过）。
     */
    private static Set<Integer> checkIntSetFromCatalog(String table, String col) {
        List<String> defs = jdbc.queryForList(
                "SELECT pg_get_constraintdef(c.oid)"
                        + "  FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE n.nspname = 'public' AND t.relname = ? AND c.contype = 'c'"
                        // 🛑 用列名在**定义里出现过**来筛选，而不是匹配某一种语法形态 ——
                        //    避免"PG 改了规范化写法就再也筛不到"这一类脆弱性。
                        + "   AND pg_get_constraintdef(c.oid) LIKE '%' || ? || '%'",
                String.class, table, col);
        assertEquals(1, defs.size(),
                "🛑 表 " + table + " 上必须【恰有一条】涉及 `" + col + "` 的 CHECK 约束，实际 "
                        + defs.size() + " 条 —— 0 条说明词表约束被删了"
                        + "（那就没有任何东西挡非法取值）；>1 条说明被拆成了多条"
                        + "（本方法无法机械合并，需人工核对口径）。实际定义=" + defs);
        String def = defs.get(0);

        // 🛑 两种形态都认：PG 规范化的 `= ANY (ARRAY[` 优先，其次是源码形态 `IN (`
        int open;
        int at = def.indexOf("= ANY (ARRAY[");
        if (at >= 0) {
            open = at + "= ANY (ARRAY[".length();
        } else {
            int in = def.indexOf("IN (");
            assertTrue(in >= 0,
                    "🛑 约束定义里既找不到 `= ANY (ARRAY[` 也找不到 `IN (` —— "
                            + "本方法只认这两种词表形态，需人工核对。实际=" + def);
            open = in + 4;
        }
        int end = def.indexOf(']', open);
        if (end < 0) {
            end = def.indexOf(')', open);
        }
        assertTrue(end > open, "词表枚举未闭合，实际=" + def);
        return intLiteralsIn(def.substring(open, end));
    }

    /**
     * 取出契约里 {@code BandDailyData.gap_reason} 的 {@code enum: [...]} 值集。
     *
     * <p>🛑 锚点的选择是本方法唯一需要注意的地方，且它踩过坑：
     * 契约里 {@code gap_reason} 一词出现在<b>三处</b>（实测行号 94 / 115 / 1781）——
     * <ul>
     *   <li>94：{@code x-field-groups} 下的 {@code gap_reason:}（后跟 {@code name:}）</li>
     *   <li>115：{@code x-visibility-matrix} 下的 {@code gap_reason:}（后跟 {@code client:}）</li>
     *   <li><b>1781：{@code BandDailyData} 的 {@code gap_reason:}（后跟 {@code type: string}）</b>
     *       ← 只有这一处是我们要的</li>
     * </ul>
     * 若用 {@code indexOf("gap_reason:")} 会锚到第 94 行，然后向后找第一个
     * {@code enum: [...]} 会拿到 {@code [mp, app, web]}（第 254 行的 {@code platform} 枚举）
     * —— 那会得到一条"看起来在检查、实际在检查别的东西"的断言。
     * <p>⇒ 故锚点 = {@code gap_reason:} <b>且紧接 {@code type: string}</b> 的那一处，
     * 再从其后的窗口内取 {@code enum: [...]}。这是"锚点必须落在定义式上"的正向应用。
     */
    private static Set<String> contractGapReasonEnum() throws IOException {
        String yaml = readContract();
        Matcher m = Pattern.compile(
                "(?m)^(\\s*)gap_reason:\\s*\\n\\1\\s+type:\\s*string\\s*$").matcher(yaml);
        assertTrue(m.find(),
                "契约里找不到 `gap_reason:` + `type: string` 那一处定义式 —— 锚点失效"
                        + "（契约里 gap_reason 出现在 3 处：x-field-groups / "
                        + "x-visibility-matrix / BandDailyData，只有最后一处是判据该锚的）");
        int tail = m.end();
        String window = yaml.substring(tail, Math.min(yaml.length(), tail + 400));
        Matcher em = Pattern.compile("enum:\\s*\\[([^\\]]*)\\]").matcher(window);
        assertTrue(em.find(),
                "契约里 BandDailyData.gap_reason 之后（400 字符窗口内）找不到 `enum: [...]` —— "
                        + "锚点失效。窗口内容=" + window.replaceAll("\\s+", " ").trim());
        Set<String> out = new LinkedHashSet<>();
        for (String part : em.group(1).split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>（与既有门禁同款状态机）。 */
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

    // ==================================================================
    // 五、底层工具
    // ==================================================================

    /** 两条库层原语的调用式（与 {@link BandRefetchLedger} 逐字一致）。 */
    private static final String CALL_PROBE =
            "SELECT register_sync_probe(?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, ?, ?,"
                    + " ?::timestamptz, ?, ?)";
    private static final String CALL_COVERAGE =
            "SELECT register_daily_coverage(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::date,"
                    + " ?, ?, ?, ?, ?, ?, ?::uuid, ?)";

    private static String deepestSqlState(Throwable t) {
        String found = null;
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (c instanceof java.sql.SQLException se && se.getSQLState() != null) {
                found = se.getSQLState();
            }
            c = c.getCause();
            depth++;
        }
        return found;
    }

    private static String fullCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (!sb.isEmpty()) {
                sb.append("\n   ← ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** 取出 {@link BizException} 的数值错误码（无则 {@code null}）。 */
    private static Integer errorCodeOf(Throwable t) {
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (c instanceof BizException be) {
                return be.getCode();
            }
            c = c.getCause();
            depth++;
        }
        return null;
    }

    /**
     * 执行一条<b>必须失败</b>的语句，返回最深层的 SQLSTATE（未失败则 {@code null}）。
     *
     * <h2>🛑🛑 本方法踩过一个"归因污染"的坑，必须写明</h2>
     * 初版无条件用 {@code jdbc.update(sql)} 执行。于是当 {@code sql} 是
     * {@code SELECT register_daily_coverage(…)} 这种<b>返回结果集</b>的调用时：
     * <pre>
     *   ① 函数**成功返回**（即门禁没生效）⇒ update 抛
     *      "传回预期之外的结果 / expected 0 rows"
     *      ⇒ 本方法把"成功"误判成"失败"，返回了一个非 null 的 SQLSTATE；
     *   ② 而那个 SQLSTATE 是 PG 对<b>结果计数不符</b>的包装错，
     *      **不是**任何业务拒绝码 ⇒ 断言 {@code assertEquals("P0001", state)} 会红，
     *      但红的原因是"期望 P0001、拿到包装错"，而**不是**"函数没拒绝"。
     * </pre>
     * 🛑 <b>这为什么危险</b>：它让一条"门禁失效"的注入以<b>归因错误</b>的方式暴露 ——
     * 排查者会去查"为什么不是 P0001"，而真相是"函数压根没抛"。更坏的是：
     * 若某条断言的期望值恰好宽松（例如只断言"非 null"），
     * 那么"函数成功返回"会让这条判据<b>假绿</b>。
     * <p>这正是本仓记录的"<b>只断言『被拒』的判据没有判别力</b>"的变体：
     * 判据必须能区分"被正确拒绝"与"根本没走拒绝路径"。
     * <p>⇒ 修法：按语句形态分派 —— {@code SELECT} 走 {@code query}（把结果集消费掉），
     * 其余走 {@code update}。
     */
    private static String writeExpectingFailure(String tenantId, String sql, Object... args) {
        try {
            inTenant(tenantId, () -> {
                execExpectingNoResult(sql, args);
                return null;
            });
            return null;
        } catch (RuntimeException ex) {
            return deepestSqlState(ex);
        }
    }

    /** 执行语句并丢弃结果：{@code SELECT} 走 {@code query}，其余走 {@code update}。 */
    private static void execExpectingNoResult(String sql, Object... args) {
        String head = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        if (head.startsWith("SELECT") || head.startsWith("WITH")) {
            // 🛑 必须走 query：SELECT 会返回结果集，用 update 会抛"预期之外的结果"。
            jdbc.query(sql, rs -> null, args);
        } else {
            jdbc.update(sql, args);
        }
    }

    /**
     * 在<b>先持有另一个租户上下文</b>的事务里执行函数，返回整条 cause 链（未失败则 {@code null}）。
     *
     * <p>🛑 与 {@link #writeExpectingFailure} 的差别：本方法先 {@code SET LOCAL} 成
     * {@link #TENANT_OTHER}，再调用传入租户 = {@link #TENANT} 的函数 ——
     * 这正是判据 ⑧ 要构造的"上下文不一致"。
     * <p>🛑 保留 {@code tx.execute} 这一层事务边界是<b>必需的</b>：
     * {@code SET LOCAL} 只在事务内有效，若无事务包裹它不会生效，判据 ⑧ 就退化成空断言。
     * <p>🛑 语句形态分派同 {@link #writeExpectingFailure}（同一个坑，见其注释）。
     */
    private static String writeExpectingFailureCtx(String presetTenant, String sql, Object... args) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL app.tenant_id = '" + presetTenant + "'");
                execExpectingNoResult(sql, args);
            });
            return null;
        } catch (RuntimeException ex) {
            return fullCauseMessages(ex);
        }
    }

    /** 与 {@link #writeExpectingFailure} 同款，但返回整条 cause 链文本。 */
    private static String failureChain(String tenantId, String sql, Object... args) {
        try {
            inTenant(tenantId, () -> {
                execExpectingNoResult(sql, args);
                return null;
            });
            return null;
        } catch (RuntimeException ex) {
            return fullCauseMessages(ex);
        }
    }

    private static String read(String rel) throws IOException {
        return Files.readString(skeletonRoot().resolve(rel), StandardCharsets.UTF_8);
    }

    /** 契约文件的相对路径（相对 <b>skeleton 父目录</b>）。 */
    private static final String CONTRACT_RELATIVE = "contract/openapi-v1.0.0.yaml";

    private static String readContract() throws IOException {
        return Files.readString(skeletonRoot().getParent().resolve(CONTRACT_RELATIVE),
                StandardCharsets.UTF_8);
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

    // ==================================================================
    // 六、收尾
    // ==================================================================

    /**
     * 收尾：清业务行 + <b>断言审计证据确实留下</b>（不删审计）+ 清理自证。
     */
    @AfterAll
    static void cleanup(@Autowired DataSource ds) {
        if (ds == null) {
            return;
        }
        JdbcTemplate j = new JdbcTemplate(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));

        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                // 🛑 删除顺序 = 依赖的反序：
                //    band_daily_coverage → band_sync_probe → band_sync_log → band → customer → …
                j.update("DELETE FROM band_daily_coverage WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band_sync_probe WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band_sync_log WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM band WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM store WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM region WHERE tenant_id = ?::uuid", tid);
            });
        }
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);

        // ---- 清理自证 ----
        Integer leftTenants = j.queryForObject(
                "SELECT count(*) FROM tenant WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, TENANT, TENANT_OTHER);
        assertTrue(leftTenants == null || leftTenants == 0,
                "清理后仍残留 " + leftTenants + " 个租户 —— 清理逻辑失效");
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                assertTrue(j.queryForObject("SELECT count(*) FROM band_sync_probe", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有探针残留（FORCE RLS 会静默删 0 行）");
                assertTrue(j.queryForObject("SELECT count(*) FROM band_daily_coverage", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有覆盖残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM band_sync_log", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有同步日志残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM band", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有手环残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM customer", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有客户残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下（用本次运行的增量）----
        assertTrue(probeCalls > 0,
                "自证失败：probeCalls 为 0，说明本类从未真正走过探针登记路径");
        assertTrue(coverageCalls > 0,
                "自证失败：coverageCalls 为 0，说明本类从未真正走过逐日覆盖路径");

        int probeFullDelta = countAudit(ACTION_PROBE_REGISTERED, PROBE_FULL) - baselineProbeFull;
        assertEquals(1, probeFullDelta,
                "判据 ① 的完整探针登记必须【恰好】留下 1 条 " + ACTION_PROBE_REGISTERED + " 审计"
                        + "（target_id=" + PROBE_FULL + "）。"
                        + "本次增量=" + probeFullDelta + "（基线=" + baselineProbeFull + "）");

        int replayedDelta = countAudit(ACTION_PROBE_REPLAYED, PROBE_IDEM) - baselineProbeReplayedIdem;
        assertEquals(1, replayedDelta,
                "🛑 判据 ② 的重放必须【恰好】留下 1 条 " + ACTION_PROBE_REPLAYED + " 审计 —— "
                        + "本域刻意让重放用【独立的 action】："
                        + "『本系统里有多少条 N 留痕』与『有多少次重放』是两个相反的信号"
                        + "（前者说明探针在积累、后者说明调用方在空转），"
                        + "共用 action 会让这两个计数互相污染。"
                        + "本次增量=" + replayedDelta + "（基线=" + baselineProbeReplayedIdem + "）");

        int probeIdemDelta = countAudit(ACTION_PROBE_REGISTERED, PROBE_IDEM) - baselineProbeIdem;
        assertEquals(1, probeIdemDelta,
                "🛑 PROBE_IDEM 本次只被【首次】登记过一次 ⇒ " + ACTION_PROBE_REGISTERED
                        + " 本次增量应恰 1 条。若增量是 2，说明重放也被记成了首次登记 —— "
                        + "那会让『N 留痕的条数』虚高。"
                        + "本次增量=" + probeIdemDelta + "（基线=" + baselineProbeIdem + "）");

        int covIdemDelta = countAudit(ACTION_COVERAGE_REFRESHED, COV_IDEM) - baselineCovIdem;
        assertTrue(covIdemDelta >= 1,
                "🛑 判据 ③ 的覆盖态必须留下至少 1 条 " + ACTION_COVERAGE_REFRESHED + " 审计 —— "
                        + "该 action 是 §2.8.7③「节流」的可观测面（重拉刷新次数）。"
                        + "本次增量=" + covIdemDelta + "（基线=" + baselineCovIdem + "）");
    }
}