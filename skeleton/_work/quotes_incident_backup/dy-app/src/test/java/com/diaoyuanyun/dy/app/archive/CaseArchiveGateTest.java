package com.diaoyuanyun.dy.app.archive;

import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveChecklist;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveOutcome;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveRecord;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveSignKey;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveSnapshot;
import com.diaoyuanyun.dy.app.archive.repository.CaseArchiveLedger;
import com.diaoyuanyun.dy.app.archive.service.CaseArchiveService;
import com.diaoyuanyun.dy.app.archive.service.CaseArchiveService.CaseArchiveResult;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-13 · 结案归档通路的真库真表端到端回归</b> ——
 * 它见证的是 {@code case_archive} 表从「零生产写入方」到「有写入方」这件事
 * <b>真的让「归档」这个动作在系统里可以发生</b>，并同时见证 V20 相对前四批形态的
 * 两处<b>实质改动</b>（跨租户撞号必须 RAISE + 手环项不得反转为阻断）。
 *
 * <h2>🛑🛑 判据设计零：这条链的缺口表现【不是任何错误】，而是"某件事安静地没做"</h2>
 * <table border="1">
 *   <caption>四批零写入方缺口的对外表现对照（本仓实测）</caption>
 *   <tr><th>批次</th><th>表</th><th>缺口表现</th><th>谁会因此失败</th></tr>
 *   <tr><td>B-10</td><td>{@code band}</td><td>{@code 23503}</td>
 *       <td>E1/E2 上报（四表 {@code device_id} 外键）</td></tr>
 *   <tr><td>B-11</td><td>{@code device}</td><td>{@code 23503}</td>
 *       <td>D6 下发（{@code device_dispatch.device_id} 外键）</td></tr>
 *   <tr><td>B-12</td><td>{@code scale}</td><td>{@code 422 / 5001}</td>
 *       <td>C2 基线评估（{@code scaleExists} 预检）</td></tr>
 *   <tr><td><b>B-13</b></td><td>{@code case_archive}</td>
 *       <td><b>无</b>（既不报错也不阻断）</td>
 *       <td><b>无人</b> —— 只在举证时暴露</td></tr>
 * </table>
 * ⇒ 故本类的验收口径<b>不能</b>照抄前三批的任何一条：
 * <ul>
 *   <li><b>不是</b>"某个 23503 消失了"：本链根本不产生 23503（一张零写入方的末端表
 *       没有被任何东西引用）；</li>
 *   <li><b>不是</b>"某道预检返回 true 了"：本链的检查点不在下游，
 *       而在"归档动作是否存在"这件事本身；</li>
 *   <li><b>是</b>：<b>「一次完整的归档调用真的落了一份可读回、门禁齐备、
 *       内容未被改写的档案」</b>，且这个动作在<b>没有本通路</b>时是做不到的。</li>
 * </ul>
 * <p>🛑 可迁移的教训（本批新得，比前三批更一般）：
 * <b>零写入方的后果不一定是"某个调用失败"，也可能是"某个调用永远不会被发起"。</b>
 * 后一种形态比前一种难发现得多，因为系统里<b>没有任何东西会为此报警</b>。
 *
 * <h2>🛑🛑 本类的靶心一：跨租户撞号（V20 相对前四批形态的实质改动）</h2>
 * 判据 ④ 是那个封堵点的正面见证。它与前三批同名判据的差别在于<b>后果的隐蔽性</b>：
 * <ul>
 *   <li>{@code device}（B-11）：返回值撒谎能在<b>下游</b>被抓住
 *       （紧接着的 {@code INSERT INTO device_dispatch} 以 23503 失败）；</li>
 *   <li><b>本域</b>：归档是链条的<b>末端收口动作</b> —— 它的下游没有第二步。
 *       若 {@code register_case_archive()} 对"archive_id 被别的租户占用"返回
 *       {@code ALREADY_EXISTS}，调用方会把 {@code refund.outcome} 置成 {@code 归档}，
 *       而<b>归档档案并不存在</b>。这个缺口<b>不报错、不 23503、没有任何下游能抓住它</b>，
 *       只在举证时暴露。</li>
 * </ul>
 * ⇒ 故判据 ④ 除了断言"被拒"，还额外断言<b>本租户内一行都没留下</b>
 * （见 ④c）—— 因为"撒谎"这一形态的后果正是"档案不存在却被当成存在"。
 *
 * <h2>🛑🛑 本类的靶心二：手环项不得反转为阻断（合规红线）</h2>
 * 判据 ⑤ 用<b>两个正例</b>（键缺席 / 值为 false）证明这道红线在行为上成立，
 * 并且它同时用静态断言（判据 ⑦）钉住"硬门禁数组里不得含手环键"。
 * <p>🛑 为什么必须<b>两条防线都有</b>：
 * 行为断言只覆盖走到该分支的调用；而"把 {@code handband_recorded_as_reference}
 * 从警告数组挪进硬门禁数组"这个改动<b>本身</b>是一次文本编辑 ——
 * 它在被某次调用触发之前，行为断言什么都不知道。
 *
 * <h2>判据清单（11 条）</h2>
 * <ol>
 *   <li>① 完整归档：一次调用落一份可读回、6 键齐备、内容未被改写的档案；</li>
 *   <li>② 幂等两态：CREATED → ALREADY_EXISTS，且<b>重放不得改写既有证据</b>；</li>
 *   <li>③ 审计失败 ⇒ 归档回滚（用真实 22001 撞这条易静默失效的不变量）；</li>
 *   <li>④ 跨租户撞号必须 RAISE(P0001)，且本租户内不得留下任何行（对照：本租户自己的
 *       重放必须 ALREADY_EXISTS）；</li>
 *   <li>⑤ 🛑 手环项<b>缺席</b>与<b>false</b> 都必须成功（合规红线，两个正例）；</li>
 *   <li>⑥ 硬门禁缺项必须被拒，且错误消息<b>含缺项名</b>（P0-25 逐字要求）；</li>
 *   <li>⑦ 形态：无 HTTP 映射的组件 + V20 两个原语在位 + <b>静态</b>核对
 *       硬门禁数组与枚举一致（含"手环不得在硬门禁数组里"）；</li>
 *   <li>⑧ 服务层的"退款终止时必填结论"（C.1.7）在 Java 侧真的生效；</li>
 *   <li>⑨ 跨租户客户引用被拒（函数侧可读错误 + 裸 SQL 绕过函数被 23503）；</li>
 *   <li>⑩ 上下文一致性守卫（已持有别的租户上下文必须被拒）；</li>
 *   <li>⑪ 本类【不提供】改写入口（静态：无 UPDATE / DELETE case_archive）。</li>
 * </ol>
 *
 * <h2>数据与清理</h2>
 * 本类用 {@code b1300000-} 前缀（与其它测试类零交集）。收尾时清业务行、
 * <b>不删审计</b>（{@code audit_log} 是全局单链，删中间行会留下永久的
 * {@code prev_hash} 断口 —— 改为断言"证据确实留下了"）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-13 · 结案归档通路真库回归（归档两态 / 跨租户撞号 / 手环不阻断 / 同事务审计）")
class CaseArchiveGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code b1300000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "b1300000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ④ / ⑨ / ⑩ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "b1300000-0000-0000-0000-0000000000f1";

    private static final String REGION = "b1300000-0000-0000-0000-000000000011";
    private static final String STORE = "b1300000-0000-0000-0000-000000000021";
    private static final String STAFF = "b1300000-0000-0000-0000-000000000031";

    /** 本租户客户 —— 归档档案的行级 scope 承载者。 */
    private static final String CUSTOMER = "b1300000-0000-0000-0000-000000000032";

    /** 属于<b>另一个</b>租户的客户 —— 判据 ⑨ 的跨租户引用负样本。 */
    private static final String CUSTOMER_OTHER = "b1300000-0000-0000-0000-0000000000c2";

    // —— 归档档案载体（各自独享，见下方逐个说明）——
    /** 判据 ①：完整归档（6 键齐备 + 全部可选字段）。 */
    private static final String ARC_FULL = "b1300000-0000-0000-0000-000000000041";
    /** 判据 ②：幂等两态 + "重放不得改写证据"。 */
    private static final String ARC_IDEM = "b1300000-0000-0000-0000-000000000042";
    /** 判据 ③：审计失败注入用。 */
    private static final String ARC_TX = "b1300000-0000-0000-0000-000000000043";
    /** 判据 ④：跨租户撞号 —— 租户 A 先占用这一个。 */
    private static final String ARC_SHARED = "b1300000-0000-0000-0000-000000000044";
    /** 判据 ④ 对照：租户 A 用自己的 archive_id 必须能建。 */
    private static final String ARC_OWN = "b1300000-0000-0000-0000-000000000045";
    /** 判据 ⑤：手环项<b>键缺席</b>的正例。 */
    private static final String ARC_HB_ABSENT = "b1300000-0000-0000-0000-000000000046";
    /** 判据 ⑤：手环项<b>值为 false</b> 的正例。 */
    private static final String ARC_HB_FALSE = "b1300000-0000-0000-0000-000000000047";
    /** 判据 ⑨：跨租户客户引用（函数侧）。 */
    private static final String ARC_CROSS_CUST = "b1300000-0000-0000-0000-000000000048";
    /** 判据 ⑩：上下文一致性守卫用。 */
    private static final String ARC_CTX = "b1300000-0000-0000-0000-000000000049";
    /** 判据 ⑧：服务层"退款终止必填结论"的载体。 */
    private static final String ARC_CONC = "b1300000-0000-0000-0000-00000000004a";
    /** 一个<b>从不</b>被归档的 id —— 只作为"不存在"的负样本。 */
    private static final String ARC_GHOST = "b1300000-0000-0000-0000-00000000004f";
    /** 判据 ⑨ 的裸 SQL 载体（绕过函数直插，验证复合外键兜底）。 */
    private static final String ARC_BARE = "b1300000-0000-0000-0000-00000000004b";

    /** 审计动作名与目标类型 —— 与 {@code CaseArchiveService} 的常量逐字一致（那里是包私有）。 */
    private static final String ACTION_ARCHIVED = "CASE_ARCHIVED";
    private static final String ACTION_REPLAYED = "CASE_ARCHIVE_REPLAYED";
    private static final String TARGET_TYPE = "case_archive";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-b13-witness";

    /** 🛑 判据 ③ 的注入载荷：超 {@code audit_log.actor VARCHAR(128)} 的操作者标识。 */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /**
     * 判据 ② 用的"第二次写入的结论"—— 它的唯一作用是作「重放不得改写证据」的靶子。
     *
     * <p>🛑 为什么需要一个可识别、且与首次不同的值：只断言行数抓不住
     * "重放顺手改写了既有档案"这一形态。要证明"未改动数据"这条性质真的成立，
     * 靶子必须在<b>重放时被显式提供</b>、而<b>结果必须仍是首次那个值</b>。
     */
    private static final String REPLAY_OVERWRITE_ATTEMPT = "重放试图改写的结论";

    /** 真正调用过归档的次数（供 {@code @AfterAll} 断言「审计证据确实留下了」，而不删它）。 */
    private static int serviceCalls;

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    @Autowired
    DataSource springDataSource;

    @Autowired
    CaseArchiveService service;

    @Autowired
    CaseArchiveLedger ledger;

    @BeforeEach
    void seedIfNeeded() {
        if (dataSource == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
        if (!seeded) {
            seedOnce();
            seeded = true;
        }
    }

    /**
     * 种两个租户 + 区域/门店/员工/客户各一。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code case_archive} 行 —— 那是本类全部判据的载体，
     * 必须由 {@link CaseArchiveService} 建。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B13租户-结案归档', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B13对照租户', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);

        inTenant(TENANT, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                    + "VALUES (?::uuid, ?::uuid, 'B13区') ON CONFLICT DO NOTHING", REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'B13店', '直营') ON CONFLICT DO NOTHING",
                    STORE, TENANT, REGION);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    STAFF, TENANT, STORE);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B13客户', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER, TENANT);
            return null;
        });

        inTenant(TENANT_OTHER, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'B13对照区') ON CONFLICT DO NOTHING",
                    "b1300000-0000-0000-0000-000000000012", TENANT_OTHER);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'B13对照店', '直营') ON CONFLICT DO NOTHING",
                    "b1300000-0000-0000-0000-000000000022", TENANT_OTHER,
                    "b1300000-0000-0000-0000-000000000012");
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B13对照客户', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER_OTHER, TENANT_OTHER);
            return null;
        });
    }

    /** 在指定租户上下文里执行（短事务 + {@code SET LOCAL}）。 */
    private static <T> T inTenant(String tenantId, Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /** 该归档档案在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int archiveRows(String tenantId, String archiveId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM case_archive WHERE archive_id = ?::uuid",
                Integer.class, archiveId));
        return n == null ? 0 : n;
    }

    /** 该归档档案的 {@code [final_conclusion, archive_checklist::text, created_by]}；无则 {@code null}。 */
    private static String[] archiveState(String tenantId, String archiveId) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT coalesce(final_conclusion, ''), archive_checklist::text,"
                            + "       coalesce(created_by, '')"
                            + "  FROM case_archive WHERE archive_id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                    archiveId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ==================================================================
    // 二、构造工具
    // ==================================================================

    /** 5 项硬门禁全 true 的清单（最常用的一种）。 */
    private static Map<String, Boolean> hardOk() {
        Map<String, Boolean> m = new TreeMap<>();
        for (CaseArchiveChecklist item : CaseArchiveChecklist.hardBlocking()) {
            m.put(item.code(), true);
        }
        return m;
    }

    /** 5 项硬门禁全 true + 手环警告项 = 指定值（{@code null} ⇒ 不收该键）。 */
    private static Map<String, Boolean> hardOkWithHandband(Boolean handband) {
        Map<String, Boolean> m = hardOk();
        if (handband != null) {
            m.put(CaseArchiveChecklist.HANDBAND_RECORDED_AS_REFERENCE.code(), handband);
        }
        return m;
    }

    /** 4 键齐备的签名块。 */
    private static Map<String, String> signsOk() {
        Map<String, String> m = new TreeMap<>();
        m.put(CaseArchiveSignKey.HANDLER.code(), "王经办");
        m.put(CaseArchiveSignKey.MERIDIAN_THERAPIST.code(), "李经络");
        m.put(CaseArchiveSignKey.STORE_OWNER.code(), "赵店主");
        m.put(CaseArchiveSignKey.SIGN_DATE.code(), "2026-09-28");
        return m;
    }

    /** 完整记录（含结论 / 趋势 / 脱敏授权 / 建档者）。 */
    private static CaseArchiveRecord fullRecord(String archiveId, String customerId) {
        return new CaseArchiveRecord(TENANT, UUID.fromString(archiveId),
                UUID.fromString(customerId), hardOkWithHandband(true), signsOk(),
                "退款终止：已按 C.3 全部结论项处理完毕", "{\"a3\":\"1.2\",\"as\":\"0.8\"}",
                true, "b13-witness");
    }

    /** 最简记录（不含可选尾巴）。 */
    private static CaseArchiveRecord minRecord(String archiveId) {
        return CaseArchiveRecord.of(TENANT, UUID.fromString(archiveId),
                UUID.fromString(CUSTOMER), hardOk(), signsOk());
    }

    private CaseArchiveResult archive(CaseArchiveRecord record, String operator) {
        serviceCalls++;
        return service.register(record, operator);
    }

    private CaseArchiveResult archiveOk(String archiveId) {
        return archive(fullRecord(archiveId, CUSTOMER), OPERATOR);
    }

    // ==================================================================
    // 判据 ①
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 完整归档：一次调用真的落一份可读回、6 键齐备、内容未被改写的档案（本条就是「归档这个动作能否发生」的验收）")
    void a_full_archive_call_really_lands_a_readable_intact_record() {
        assertEquals(0, archiveRows(TENANT, ARC_FULL),
                "前置失败：ARC_FULL 已在库里 ⇒ 下面那条 CREATED 断言会以【归因错误】的方式失败");

        // ---- ①a 落库 ----
        CaseArchiveResult r = archiveOk(ARC_FULL);
        assertEquals(CaseArchiveOutcome.CREATED, r.outcome(),
                "首次归档必须 CREATED —— 若返回 ALREADY_EXISTS，说明载体被占用了"
                        + "（那是一次归因错误的红：问题在用例隔离，不在归档逻辑）");
        assertEquals(ARC_FULL, r.archiveId().toString(), "回执必须带 archive_id（调用方据此更新 refund.outcome）");
        assertEquals(CUSTOMER, r.customerId().toString(), "回执必须带 customer_id（同上）");
        assertEquals(1, archiveRows(TENANT, ARC_FULL), "归档后本租户内必须恰有一行");

        // ---- ①b 读回：一整行，不是几个标量（见 CaseArchiveSnapshot 类注释）----
        CaseArchiveSnapshot snap = service.snapshotOf(TENANT, UUID.fromString(ARC_FULL));
        assertNotNull(snap, "🛑 snapshotOf 必须能读回刚写入的那一行 —— "
                + "若它是 null，而 archiveRows 报 1，那就出现了一个可报警的矛盾"
                + "（同一租户上下文里 count=1 却读不到行）");

        // ---- ①c 🛑 6 键必须【全部】在（5 硬 + 1 警告）----
        assertEquals(CaseArchiveChecklist.allCodes(), snap.checklist().keySet(),
                "🛑 读回的清单键集必须与枚举登记【完全相等】—— "
                        + "少一个说明写入时丢了键，多一个说明混进了未登记键。"
                        + "注意本 record 的构造器会把 6 键【全部】补齐（缺的警告项补成 false），"
                        + "故这里的相等是必然的 —— 若不等，说明序列化/反序列化这一环坏了");
        assertTrue(snap.hardGatesSatisfied(),
                "🛑 读回的 5 项硬门禁必须仍全为 true —— "
                        + "这比“行数=1”强得多：它排除“写入时门禁值被静默改写”");
        assertEquals(Boolean.TRUE, snap.handbandWarningValue(),
                "本次显式传了手环项 = true，读回必须还是 true");

        // ---- ①d 签名块 4 键齐备且值原样 ----
        assertEquals(CaseArchiveSignKey.allCodes(), snap.signsOf().keySet(),
                "读回的签名块键集必须与枚举登记完全相等");
        assertEquals("赵店主", snap.signsOf().get(CaseArchiveSignKey.STORE_OWNER.code()),
                "🛑 签名值必须原样读回 —— 一份没有责任人签名的归档档案，"
                        + "在举证场景里等同于“没人对这次结案负责”");

        // ---- ①e 可选字段 ----
        assertEquals("退款终止：已按 C.3 全部结论项处理完毕", snap.finalConclusion(),
                "结论必须原样落库");
        assertEquals("{\"a3\":\"1.2\",\"as\":\"0.8\"}", snap.metricsTrend(),
                "🛑 metrics_trend 必须原样落库（它是归档那一刻的趋势快照）。"
                        + "JSONB 会规范化空白，但键序与值应保持语义等价");
        assertTrue(snap.isDesensitizeAuthorized(),
                "🛑 本次显式传了 desensitizeAuthorized=true ⇒ 必须读回 true。"
                        + "§2.22 逐字：「脱敏须单独授权」—— 若这一位在读回时丢了，"
                        + "那么“是否已授权”就无法事后举证");
        assertEquals("b13-witness", snap.createdBy(), "created_by 必须原样落库（没有走库层回落值）");
        assertNotNull(snap.archivedAt(), "archived_at 必须可读（它是归档时刻，判据 ② 的对照要用）");

        // ---- ①f 🛑 effect_confirm_pdf 必须是【零写入列】（本迁移的显式取舍）----
        String pdf = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT coalesce(effect_confirm_pdf, '') FROM case_archive WHERE archive_id = ?::uuid",
                String.class, ARC_FULL));
        assertEquals("", pdf,
                "🛑 effect_confirm_pdf 必须是空 —— 本迁移【有意】不收它"
                        + "（它是文件引用，而文件存储通路尚未选定）。"
                        + "若它被填了一个值，说明有人塞了一个无法解析的引用进来，"
                        + "于是这一列“看起来被填了”而实际指向虚无 —— 本仓反复要防的死字段形态。"
                        + "它是【已知且登记过的】零写入列，不是静默留下的缺口");

        // ---- ①g 审计 ----
        assertAudit(r.auditId(), ACTION_ARCHIVED, ARC_FULL, "\"mode\":\"CREATED\"");
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 幂等两态：CREATED → ALREADY_EXISTS；🛑 且重放【不得】改写既有证据（归档档案是证据快照）")
    void the_two_states_are_complete_and_replay_never_rewrites_the_evidence() {
        assertEquals(0, archiveRows(TENANT, ARC_IDEM), "前置失败：ARC_IDEM 应尚未归档");

        // ---- 态 1：首次（带结论 A）----
        CaseArchiveRecord first = new CaseArchiveRecord(TENANT, UUID.fromString(ARC_IDEM),
                UUID.fromString(CUSTOMER), hardOkWithHandband(true), signsOk(),
                "首次结论：终止退款并归档", null, false, "b13-first");
        assertEquals(CaseArchiveOutcome.CREATED, archive(first, OPERATOR).outcome());
        String[] s0 = archiveState(TENANT, ARC_IDEM);
        assertNotNull(s0);
        assertEquals("首次结论：终止退款并归档", s0[0], "首次结论必须落库");

        // ---- 态 2：重放，并【显式提供一组不同的参数】----
        //   🛑 这就是本判据的靶心：只断言行数抓不住"重放顺手改写了既有档案"。
        //      必须提供一个"如果被写入就会看得出来"的值。
        CaseArchiveRecord replay = new CaseArchiveRecord(TENANT, UUID.fromString(ARC_IDEM),
                UUID.fromString(CUSTOMER), hardOkWithHandband(false), signsOk(),
                REPLAY_OVERWRITE_ATTEMPT, "{\"a3\":\"9.9\"}", true, "b13-replay");
        CaseArchiveResult rr = archive(replay, OPERATOR);
        assertEquals(CaseArchiveOutcome.ALREADY_EXISTS, rr.outcome(),
                "重放同一个 archive_id 必须返回 ALREADY_EXISTS（幂等命中），"
                        + "而不是把它当成错误或冲突");

        assertEquals(1, archiveRows(TENANT, ARC_IDEM),
                "🛑 重放之后必须仍【恰有一行】—— 若变成 2 行，说明 ON CONFLICT 的推断目标"
                        + "没命中主键（那会以运行期错误炸，而一个“先删后插”的实现则会静默出 2 行）");

        // ---- ②a 🛑🛑 核心断言：重放【不得改写】既有证据 ----
        String[] s1 = archiveState(TENANT, ARC_IDEM);
        assertEquals("首次结论：终止退款并归档", s1[0],
                "🛑🛑 重放【改写了】既有档案的 final_conclusion —— "
                        + "归档档案是【证据快照】，而“重放归档”退化成“用新参数改写既有证据”"
                        + "是本域最不能接受的形态之一（一份可以被顺手改一下的证据不是证据）。"
                        + "这条性质由库层 ON CONFLICT DO NOTHING 给出，故它一旦失效，"
                        + "最可能的原因是有人把它改成了 DO UPDATE。实际读到=" + s1[0]);
        assertFalse(s1[0].contains(REPLAY_OVERWRITE_ATTEMPT),
                "重放的参数值不得出现在库里（靶心值逃逸）");
        assertFalse(s1[1].contains("\"handband_recorded_as_reference\": false"),
                "🛑 重放传入的手环项 = false 不得覆盖首次的 true —— 同上，"
                        + "这是“证据未被改写”这条性质的另一个面");
        assertEquals("b13-first", s1[2], "created_by 也不得被重放改写");

        // ---- ②b 重放仍必须写审计（且 action 与首次【不同】）----
        assertAudit(rr.auditId(), ACTION_REPLAYED, ARC_IDEM, "\"mode\":\"ALREADY_EXISTS\"");

        // ---- ②c 对照：不同 archive_id 必须各自独立可建 ----
        assertEquals(CaseArchiveOutcome.CREATED, archiveOk(ARC_OWN).outcome(),
                "🛑 换一个 archive_id 必须能建 —— 否则上面那条“重放返回 ALREADY_EXISTS”"
                        + "就无法与“这个客户/这个租户本身归档不上”区分（比较器必须对称）");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 🛑 审计失败必须让归档【回滚】—— 用真实的 actor 超长（22001）撞这条易静默失效的不变量")
    void a_failed_audit_write_rolls_the_archive_back() {
        assertEquals(0, archiveRows(TENANT, ARC_TX), "前置失败：ARC_TX 应尚未归档");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> archive(fullRecord(ARC_TX, CUSTOMER), TOO_LONG_OPERATOR),
                "🛑 审计写不进去时，归档调用竟然没有抛错 —— "
                        + "那说明审计失败被静默吞掉了，而“每一份归档档案都有来源”这条不变量已失效");

        assertEquals("22001", deepestSqlState(ex),
                "🛑 本次失败的成因必须是 22001（actor 超列宽），实际=" + deepestSqlState(ex)
                        + "。若成因是别的，那本条证明的就不是“审计失败 ⇒ 归档回滚”，"
                        + "而是其它某处失败导致归档没发生 —— 两个完全不同的结论。异常链="
                        + fullCauseMessages(ex));

        assertEquals(0, archiveRows(TENANT, ARC_TX),
                "🛑🛑 审计失败之后 case_archive 行【仍在】库里 —— "
                        + "“审计失败则归档回滚”这条不变量已静默失效，"
                        + "而本仓没有任何其它检查会发现它。最常见的成因是有人把 "
                        + "CaseArchiveLedger.register() / CaseArchiveService.register() 的 "
                        + "TransactionTemplate 改成了 PROPAGATION_REQUIRES_NEW"
                        + "（归档独立提交，审计失败只回滚审计）—— 此时档案已落库而没有任何来源证据，"
                        + "且业务测试全绿。对本域而言这不是一条审计缺口，"
                        + "而是一份证据缺了它的来源链");

        // ---- 对照 ----
        CaseArchiveResult ok = archive(fullRecord(ARC_TX, CUSTOMER), OPERATOR);
        assertEquals(CaseArchiveOutcome.CREATED, ok.outcome(),
                "🛑 换成合法 operator 之后必须成功 —— 否则上面那条“回滚”断言就无法与"
                        + "“这份档案本身建不上”区分");
        assertEquals(1, archiveRows(TENANT, ARC_TX), "合法路径下必须真的落库（这是上一条的反向对照）");
    }

    // ==================================================================
    // 判据 ④
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 🛑🛑 跨租户撞号必须 RAISE(P0001) 而不是返回 ALREADY_EXISTS —— 照抄 B-10 会造出“返回值在撒谎”的缺陷")
    void a_cross_tenant_archive_id_collision_must_raise_not_return_already_exists() {
        assertEquals(0, archiveRows(TENANT_OTHER, ARC_SHARED),
                "前置失败：ARC_SHARED 应尚未存档");

        // ---- ④a 租户【另一个】先占用这个全局 archive_id ----
        //   走裸逻辑：直接以租户 OTHER 的身份经函数归档（用户与客户都用 OTHER 的）。
        CaseArchiveRecord otherRec = new CaseArchiveRecord(TENANT_OTHER,
                UUID.fromString(ARC_SHARED), UUID.fromString(CUSTOMER_OTHER),
                hardOk(), signsOk(), null, null, false, "b13-other");
        assertEquals(CaseArchiveOutcome.CREATED,
                service.register(otherRec, "ops-b13-other").outcome(),
                "🛑 前提：另一个租户必须先成功占用这个 archive_id。"
                        + "若这里就失败了，本判据的靶心（撞号）根本没有被构造出来");
        assertEquals(1, archiveRows(TENANT_OTHER, ARC_SHARED));

        // ---- ④b 主租户用【同一个】archive_id 归档 ⇒ 必须 RAISE，不是 ALREADY_EXISTS ----
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> archiveOk(ARC_SHARED),
                "🛑🛑 以本租户身份用【已被另一租户占用】的 archive_id 归档竟然成功了 —— "
                        + "这是本域最危险的形态：case_archive_pkey 是【单列】archive_id，"
                        + "而主键冲突会走 ON CONFLICT DO NOTHING ⇒ ROW_COUNT=0，"
                        + "而本租户上下文里 SELECT 又看不见那一行 ⇒ "
                        + "若没有那条 RAISE 判定，函数会对一个手里一行都没有的租户返回 ALREADY_EXISTS，"
                        + "调用方据此把 refund.outcome 置成“归档”，"
                        + "而【归档档案并不存在】—— 且下游没有任何一步能证伪它（归档是链条末端）");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V20 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 主键冲突路径而不是那条跨租户判定 —— "
                        + "两者都 fail-closed，但结论完全不同。异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("另一租户"),
                "🛑 拒绝必须是【写明理由】的一条错误（archive_id 已被另一租户占用），"
                        + "而不是一条随机的 SQL 失败。异常链=" + fullCauseMessages(ex));

        // ---- ④c 🛑 本租户内【不得留下任何行】（"撒谎"这一形态的后果正是"档案不存在"）----
        assertEquals(0, archiveRows(TENANT, ARC_SHARED),
                "🛑 被拒之后本租户内不得留下该 archive_id 的任何行 —— "
                        + "若这里 > 0，那说明主键冲突被绕过（例如先 DELETE 再 INSERT），"
                        + "而那会把另一个租户的档案删掉");

        // ---- ④d 对照：本租户【自己的】档案重放必须 ALREADY_EXISTS（不是 RAISE）----
        //   🛑 这一条让 ④b 的"必须 RAISE"有意义：否则一个"任何冲突都 RAISE"的实现
        //      也能让 ④b 通过，而那会毁掉幂等（重放变成异常）。
        CaseArchiveResult replay = archiveOk(ARC_OWN);
        assertEquals(CaseArchiveOutcome.ALREADY_EXISTS, replay.outcome(),
                "🛑 本租户自己已有这个档案时，重放必须返回 ALREADY_EXISTS 而【不是】抛异常 —— "
                        + "“冲突了 ⇒ 是不是我的”这个判定必须真的区分两种情形，"
                        + "否则一个“任何冲突都 RAISE”的实现也能通过 ④b，而那会毁掉幂等");
    }

    // ==================================================================
    // 判据 ⑤
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 🛑🛑 手环项【缺席】与【false】都必须成功 —— 合规红线：手环类缺项不得反转为阻断")
    void the_handband_warning_must_never_block_the_closing() {
        // ---- ⑤a 手环键【完全缺席】⇒ 必须成功 ----
        assertEquals(0, archiveRows(TENANT, ARC_HB_ABSENT), "前置失败：ARC_HB_ABSENT 应尚未归档");
        CaseArchiveRecord absent = CaseArchiveRecord.of(TENANT,
                UUID.fromString(ARC_HB_ABSENT), UUID.fromString(CUSTOMER),
                hardOk(),           // ← 只有 5 项硬门禁，手环键【不在】里面
                signsOk());
        CaseArchiveResult rAbsent = archive(absent, OPERATOR);
        assertEquals(CaseArchiveOutcome.CREATED, rAbsent.outcome(),
                "🛑🛑 手环键缺席时归档【必须成功】—— 若这里抛了，说明"
                        + "「手环类缺项不得反转为阻断」这条合规红线在行为上被违反了。"
                        + "口径链（四份权威，逐字）："
                        + "PRD C.3 ARC-07「不阻断、不作不利依据」· ARC-11「警告（不阻断）」· "
                        + "P0-25「警告规则仅记缺口 · 手环类缺项不得反转为阻断」· "
                        + "README §5.3「三条不得触碰」③「任何“手环缺项反转为阻断”的写法一律违规」。"
                        + "底层理由：手环数据是“参考之一”，把它变成阻断等于让一条"
                        + "本就不该作为判定依据的数据去阻止结案");

        // 读回：缺的键被补成 false（本 record 构造器的既定口径），仍不阻断
        CaseArchiveSnapshot sAbsent = service.snapshotOf(TENANT, UUID.fromString(ARC_HB_ABSENT));
        assertNotNull(sAbsent);
        assertEquals(Boolean.FALSE, sAbsent.handbandWarningValue(),
                "🛑 缺席的警告键在读回时应是 false（本 record 构造器把 6 键全部补齐），"
                        + "而“值是 false”同样不阻断 —— 见 ⑤b");
        assertTrue(sAbsent.hardGatesSatisfied(), "硬门禁仍须全 true（缺席的只是警告项）");

        // ---- ⑤b 手环项 = false ⇒ 也必须成功 ----
        assertEquals(0, archiveRows(TENANT, ARC_HB_FALSE), "前置失败：ARC_HB_FALSE 应尚未归档");
        CaseArchiveRecord falseVal = new CaseArchiveRecord(TENANT,
                UUID.fromString(ARC_HB_FALSE), UUID.fromString(CUSTOMER),
                hardOkWithHandband(false), signsOk(), null, null, false, null);
        CaseArchiveResult rFalse = archive(falseVal, OPERATOR);
        assertEquals(CaseArchiveOutcome.CREATED, rFalse.outcome(),
                "🛑🛑 手环项显式传 false 时归档【必须成功】—— 与 ⑤a 同一条红线，"
                        + "但载体不同：这次键【在】而值是 false。两者都必须放行，"
                        + "缺任何一个都会让“缺项不阻断”这句话有一半是空的");

        CaseArchiveSnapshot sFalse = service.snapshotOf(TENANT, UUID.fromString(ARC_HB_FALSE));
        assertNotNull(sFalse);
        assertEquals(Boolean.FALSE, sFalse.handbandWarningValue(),
                "本次显式传的 false 必须原样读回（不是被悄悄改成 true）");

        // ---- ⑤c 对照：这【不】意味着"任何值都放行" —— 硬门禁缺项仍必须阻断 ----
        //   🛑 这一条让 ⑤a/⑤b 的"必须成功"有意义：否则一个"整个清单校验被删掉"的实现
        //      也能让前两条通过。
        assertThrows(RuntimeException.class,
                () -> CaseArchiveRecord.of(TENANT, UUID.fromString(ARC_GHOST),
                        UUID.fromString(CUSTOMER),
                        Map.of("reason_recorded", true),  // ← 缺 4 项硬门禁
                        signsOk()),
                "🛑 硬门禁缺 4 项时【必须】被拒 —— 否则 ⑤a/⑤b 的“必须成功”就退化成"
                        + "“校验根本没做”这一更坏的形态。判据 ⑥ 会进一步断言拒绝的消息含缺项名");
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ 硬门禁缺项必须被拒，且错误消息【含缺项名】（P0-25「403 且给出缺失项名称」逐字要求）")
    void a_missing_hard_gate_is_refused_with_the_missing_item_named() {
        // ---- ⑥a 库层（绕过 Java 侧构造器，直接调函数）----
        //   🛑 为什么必须测库层那一道：Java 侧构造器会先拦（判据 ⑤c 已证）。
        //      库层那道守的是"有人绕过 record 直接写 SQL"—— 它是最后一道。
        String sqlState = writeExpectingFailure(TENANT,
                "SELECT register_case_archive(?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?::jsonb,"
                        + " NULL, NULL, false, NULL)",
                TENANT, ARC_GHOST, CUSTOMER,
                "{\"reason_recorded\": true}",          // ← 只填 1 项硬门禁
                "{\"handler\":\"王\",\"meridian_therapist\":\"李\","
                        + "\"store_owner\":\"赵\",\"sign_date\":\"2026-09-28\"}");
        assertEquals("P0001", sqlState,
                "🛑 硬门禁缺项必须由函数 RAISE(P0001)，实际=" + sqlState
                        + "。若这里返回 null（即没失败），那说明库层的门禁判定被删掉了 —— "
                        + "而那正是“绕过应用层直接写 SQL”这条路的唯一防线");

        String chain = failureChain(TENANT,
                "SELECT register_case_archive(?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?::jsonb,"
                        + " NULL, NULL, false, NULL)",
                TENANT, ARC_GHOST, CUSTOMER,
                "{\"reason_recorded\": true}",
                "{\"handler\":\"王\",\"meridian_therapist\":\"李\","
                        + "\"store_owner\":\"赵\",\"sign_date\":\"2026-09-28\"}");
        assertNotNull(chain, "第二次调用也必须失败");
        // 🛑 四个缺项名必须【逐一】出现在消息里 —— P0-25 逐字：「给出缺失项名称」。
        //    只报"缺项"而不报"缺哪几项"是不达标的：调用方无法据此定位。
        for (String missing : List.of("baseline_review_compared", "retention_recorded",
                "owner_signed", "archive_plan_exec_archived")) {
            assertTrue(chain.contains(missing),
                    "🛑 拒绝消息里必须含缺项名 " + missing + " —— "
                            + "P0-25 逐字要求「403 且给出缺失项名称」。"
                            + "只报“缺项”会让调用方无法定位是哪一项。实际消息=" + chain);
        }
        assertFalse(chain.contains("reason_recorded"),
                "🛑 已填的那一项【不得】出现在缺项列表里 —— 若它出现了，"
                        + "说明判定把“已填”也当成了“缺”。实际消息=" + chain);

        // ---- ⑥b 库层拒绝后不得留下任何行 ----
        assertEquals(0, archiveRows(TENANT, ARC_GHOST),
                "🛑 被门禁拒绝之后不得留下任何行 —— RAISE 会中止语句，"
                        + "而 INSERT 在同一个函数体内，故它必须自动回滚");

        // ---- ⑥c Java 侧（构造器）也必须拒，且错误码是 GATE_MISSING(2002/403) ----
        BizException be = assertThrows(BizException.class,
                () -> CaseArchiveRecord.of(TENANT, UUID.fromString(ARC_GHOST),
                        UUID.fromString(CUSTOMER),
                        Map.of("reason_recorded", false),   // ← 显式 false（不是缺失）
                        signsOk()),
                "🛑 硬门禁项显式传 false 也必须被 Java 侧构造器拒绝 —— "
                        + "“值为 false”与“键缺失”在硬门禁上是同一件事（都不可结案）");
        assertEquals(ErrorCode.GATE_MISSING, be.getErrorCode(),
                "🛑 应用侧必须抛 GATE_MISSING(2002, 403) —— "
                        + "与 P0-25「硬阻断规则缺项 → 后端返回 403」逐字一致。"
                        + "若抛的是 1001(400)，语义就变成了“调用方入参不合法”，"
                        + "而这是【门禁未通过】，两者对客户端的含义不同");
    }

    // ==================================================================
    // 判据 ⑦（静态：形态与载体）
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 形态：无 HTTP 映射的组件 + V20 两个原语在位 + 🛑 硬门禁数组与枚举一致（含“手环不得在硬门禁数组里”）")
    void the_archive_path_is_a_component_and_the_wall_of_gate_arrays_matches() throws IOException {
        Path root = skeletonRoot();

        // ---- (a) 五件套必须在位 ----
        List<String> rels = List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/domain/CaseArchiveChecklist.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/domain/CaseArchiveSignKey.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/domain/CaseArchiveOutcome.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/domain/CaseArchiveRecord.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/domain/CaseArchiveSnapshot.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/repository/CaseArchiveLedger.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/service/CaseArchiveService.java");
        for (String rel : rels) {
            assertTrue(Files.isRegularFile(root.resolve(rel)),
                    "结案归档通路缺件：" + rel
                            + "\n若这条通路被有意移除，请同时：把 case_archive 移回 "
                            + "ProvisioningBoundaryGateTest 的 NOT_PROVISIONED 账、"
                            + "恢复本类、并同步文档。"
                            + "🛑 禁止只删代码不改账 —— 那会让两账与代码脱节；"
                            + "🛑 更禁止“只删代码不改账、还把那条判据放宽”—— "
                            + "那会让“四种结局全部强制归档”重新变成一句没有实现的话");
        }

        // ---- (b) 七个类都不得带 HTTP 映射注解 ----
        List<String> offenders = new ArrayList<>();
        for (String rel : rels) {
            String code = stripComments(Files.readString(root.resolve(rel), StandardCharsets.UTF_8));
            for (String marker : List.of(
                    "@RestController", "@Controller", "@RequestMapping",
                    "@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping",
                    "@GetMapping")) {
                if (code.contains(marker)) {
                    offenders.add(rel + " → " + marker);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "结案归档通路被暴露成了 HTTP 端点 —— 这与契约化决策相悖。"
                        + "契约 40 个 path 里【有意】没有任何“创建结案归档”端点（归档根本不出现在"
                        + "path 表里）：给归档加对外端点属契约 MAJOR 变更，"
                        + "且要先回答契约回答不了的问题：「谁有权把一个退款工单收口成归档」。"
                        + "命中：" + offenders);

        // ---- (c) 自证：上面那条检查真的有判别力 ----
        String positive = stripComments(Files.readString(root.resolve(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/controller/"
                        + "RefundWorkOrderController.java"), StandardCharsets.UTF_8));
        assertTrue(positive.contains("@RestController"),
                "自证样本失效：RefundWorkOrderController 应当带 @RestController。"
                        + "若这条不成立，本判据的“零命中”就没有判别力");

        // ---- (d) V20 两个原语在位 ----
        Path migration = root.resolve("dy-app/src/main/resources/db/migration/"
                + "V20__case_archive_provisioning.sql");
        assertTrue(Files.isRegularFile(migration), "V20 结案归档迁移不存在：" + migration);
        String v20 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v20.contains("FUNCTION register_case_archive"),
                "V20 里找不到 register_case_archive 函数定义 —— 迁移的载体变了");
        assertTrue(v20.contains("FUNCTION latest_archive_of"),
                "V20 里找不到 latest_archive_of 函数定义 —— 它是“最新一份”这条读侧口径的"
                        + "可断言载体（需要一个全序，见 CaseArchiveLedger.latestArchiveOf 的说明）");
        assertTrue(v20.contains("已被【另一租户】占用"),
                "🛑 V20 里找不到跨租户撞号的 RAISE 文本 —— 这正是本迁移相对前四批的唯一实质改动"
                        + "（判据 ④ 的靶心）。若它被删掉，register_case_archive 会在"
                        + "“冲突了但我看不见”时返回 ALREADY_EXISTS，而本租户一行都没有 —— "
                        + "调用方会把工单标记为已归档，而归档档案并不存在");

        // ---- (e) 🛑🛑 静态核对：硬门禁数组 == 枚举的 hardBlocking()，且【手环不得在其中】----
        //   这是判据 ⑤ 的静态防线。行为断言只覆盖走到该分支的调用，
        //   而"把 handband 从警告数组挪进硬门禁数组"这个改动【本身】是一次文本编辑 ——
        //   它在被某次调用触发之前，行为断言什么都不知道。
        Set<String> enumHard = new LinkedHashSet<>();
        for (CaseArchiveChecklist item : CaseArchiveChecklist.hardBlocking()) {
            enumHard.add(item.code());
        }
        Set<String> enumWarn = new LinkedHashSet<>();
        for (CaseArchiveChecklist item : CaseArchiveChecklist.warnings()) {
            enumWarn.add(item.code());
        }

        Set<String> sqlHard = quotedKeySetAfter(v20, "v_hard_gate_keys");
        Set<String> sqlWarn = quotedKeySetAfter(v20, "v_warn_gate_keys");

        assertEquals(enumHard, sqlHard,
                "🛑 V20 函数里的【硬门禁数组】与 Java 枚举的 hardBlocking() 必须【完全相等】—— "
                        + "两处不一致会让“哪些项阻断结案”这件事有两个答案。"
                        + "枚举侧=" + enumHard + " 迁移侧=" + sqlHard);
        assertEquals(enumWarn, sqlWarn,
                "🛑 V20 函数里的【警告数组】与 Java 枚举的 warnings() 必须完全相等。"
                        + "枚举侧=" + enumWarn + " 迁移侧=" + sqlWarn);

        // 🛑 合规红线的【静态】断言：手环键绝不允许出现在硬门禁数组里
        assertFalse(sqlHard.contains(
                        CaseArchiveChecklist.HANDBAND_RECORDED_AS_REFERENCE.code()),
                "🛑🛑 手环键 handband_recorded_as_reference 出现在了【硬门禁数组】里 —— "
                        + "这直接违反 README §5.3「三条不得触碰」③"
                        + "「任何“手环缺项反转为阻断”的写法一律违规」。"
                        + "哪怕动机是“更严更安全”也不可以：手环数据是“参考之一”，"
                        + "把它变成阻断会让门店陷入“客户不戴手环就永远无法结案”的境地。"
                        + "静态防线命中 —— 这是一次必须回退的改动");
        assertTrue(sqlWarn.contains(
                        CaseArchiveChecklist.HANDBAND_RECORDED_AS_REFERENCE.code()),
                "🛑 手环键必须【在】警告数组里 —— 与上一条合起来，"
                        + "它的位置是被钉住的两端（不在硬门禁、在警告），"
                        + "而不是“不在硬门禁就行”（那样它可能被整个删掉）");

        // ---- (f) 自证 (e) 有判别力：正样本必须是"能被检出"的形态 ----
        assertTrue(sqlHard.contains("reason_recorded"),
                "自证失败：硬门禁数组解析结果里应当含 reason_recorded —— "
                        + "若这条不成立，说明解析器没读到预期内容，那么 (e) 的"
                        + "“两个集合相等”就没有判别力（可能只是两边都空）");
        assertEquals(5, sqlHard.size(), "硬门禁数组应恰有 5 个元素（实证值）");
        assertEquals(1, sqlWarn.size(), "警告数组应恰有 1 个元素（实证值）");
        assertEquals(6, CaseArchiveChecklist.all().size(), "枚举应恰有 6 个项");
        assertEquals(4, CaseArchiveSignKey.all().size(), "签名键应恰有 4 个");
    }

    // ==================================================================
    // 判据 ⑧
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑧ 服务层的「退款终止时必填结论」(C.1.7) 在 Java 侧真的生效，且【只】在终止态生效")
    void the_service_enforces_conclusion_when_refund_terminated() {
        // ---- ⑧a 终止态 + 无结论 ⇒ 必须拒（GATE_MISSING）----
        CaseArchiveRecord noConclusion = CaseArchiveRecord.of(TENANT,
                UUID.fromString(ARC_CONC), UUID.fromString(CUSTOMER), hardOk(), signsOk());
        assertNull(noConclusion.finalConclusion(), "前提：本 record 的结论为空");

        BizException be = assertThrows(BizException.class,
                () -> service.register(noConclusion, OPERATOR, true),
                "🛑 退款终止态归档时 final_conclusion 为空必须被拒 —— "
                        + "PRD C.1.7 逐字：「case_archive.final_conclusion / refund_amount / "
                        + "amount_basis | 否 | 退款终止时必填」");
        assertEquals(ErrorCode.GATE_MISSING, be.getErrorCode(),
                "🛑 必须抛 GATE_MISSING(2002, 403)，与 C.1.7「退款终止时必填」+ "
                        + "P0-25「硬阻断规则缺项 → 403」同口径");
        assertEquals(0, archiveRows(TENANT, ARC_CONC),
                "被拒之后不得留下任何行（本校验在事务外，故本来也不会落库 —— "
                        + "这一条同时证明它确实在【调用库层之前】就拦住了）");

        // ---- ⑧b 🛑 关键对照：非终止态 + 无结论 ⇒ 必须【成功】----
        //   这一条是 ⑧a 的判别力来源：若这条也拒，那说明实现是"一律要求结论"，
        //   而那是一条【比 PRD 更严的、代拍出来的】规则。
        CaseArchiveResult ok = service.register(noConclusion, OPERATOR, false);
        assertEquals(CaseArchiveOutcome.CREATED, ok.outcome(),
                "🛑🛑 非退款终止态的归档【必须允许结论为空】—— 若这里被拒，"
                        + "说明实现把“退款终止时必填”做成了“一切归档都必须填”，"
                        + "而那是一条比 PRD 更严的、代拍出来的规则（包括“继续”之后归档的档案）。"
                        + "本仓库对“默认值方向”的一贯取舍：默认应让遗漏表现为假红，"
                        + "但【显式传入 false】时必须是放行 —— 否则那个开关就没有意义");
        assertEquals(1, archiveRows(TENANT, ARC_CONC));

        // ---- ⑧c 便捷重载默认 refundTerminated = true ⇒ 漏填参数会得到更严的校验 ----
        BizException be2 = assertThrows(BizException.class,
                () -> service.register(CaseArchiveRecord.of(TENANT,
                        UUID.fromString(ARC_GHOST), UUID.fromString(CUSTOMER),
                        hardOk(), signsOk()), OPERATOR),
                "🛑 两参重载的默认 refundTerminated=true 必须生效 —— "
                        + "这样“少填一个参数”会得到【更严】的校验（一次可读的 403），"
                        + "而不是让一条合规规则被静默跳过");
        assertEquals(ErrorCode.GATE_MISSING, be2.getErrorCode());
    }

    // ==================================================================
    // 判据 ⑨
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("⑨ 跨租户客户引用必须被拒：函数侧给可读业务错误(P0001)，绕过函数则被复合外键 23503 拒")
    void a_cross_tenant_customer_reference_is_refused_with_the_right_reason() {
        // ---- ⑨a 函数侧：以本租户身份引用【另一租户的】客户 ----
        String sqlState = writeExpectingFailure(TENANT,
                "SELECT register_case_archive(?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?::jsonb,"
                        + " NULL, NULL, false, NULL)",
                TENANT, ARC_CROSS_CUST, CUSTOMER_OTHER,
                "{\"reason_recorded\":true,\"baseline_review_compared\":true,"
                        + "\"retention_recorded\":true,\"owner_signed\":true,"
                        + "\"archive_plan_exec_archived\":true}",
                "{\"handler\":\"王\",\"meridian_therapist\":\"李\","
                        + "\"store_owner\":\"赵\",\"sign_date\":\"2026-09-28\"}");
        assertEquals("P0001", sqlState,
                "🛑 函数侧必须给出可读的业务错误 P0001（“客户 X 在租户 Y 内不存在”），"
                        + "实际=" + sqlState + "。若它是 23503，那说明函数里那道客户存在性检查"
                        + "被删掉了、只剩外键兜底 —— 两者都 fail-closed，"
                        + "但 23503 只有一条约束名可读，把缺口翻译成了无信息的失败");

        String chain = failureChain(TENANT,
                "SELECT register_case_archive(?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?::jsonb,"
                        + " NULL, NULL, false, NULL)",
                TENANT, ARC_CROSS_CUST, CUSTOMER_OTHER,
                "{\"reason_recorded\":true,\"baseline_review_compared\":true,"
                        + "\"retention_recorded\":true,\"owner_signed\":true,"
                        + "\"archive_plan_exec_archived\":true}",
                "{\"handler\":\"王\",\"meridian_therapist\":\"李\","
                        + "\"store_owner\":\"赵\",\"sign_date\":\"2026-09-28\"}");
        assertNotNull(chain);
        assertTrue(chain.contains("不存在"),
                "🛑 拒绝消息必须说明“客户…不存在”（本仓纪律：判据必须给出理由）。实际=" + chain);

        assertEquals(0, archiveRows(TENANT, ARC_CROSS_CUST), "被拒后不得留下任何行");

        // ---- ⑨b 对照：裸 SQL 绕过函数 ⇒ 必须被复合外键 23503 拒（不是 42501）----
        //   🛑 23503 而非 42501：本写入在【本租户上下文内】（inTenant 已设 app.tenant_id），
        //      故 RLS 的 WITH CHECK 是满足的（tenant_id = app.tenant_id）；
        //      拦住它的是 V16 的复合外键 (tenant_id, customer_id) → customer(tenant_id, id)。
        //      若这里得到 42501，那说明失败原因是 RLS 而不是外键 —— 两个完全不同的结论。
        String bareState = writeExpectingFailure(TENANT,
                "INSERT INTO case_archive (archive_id, tenant_id, customer_id,"
                        + " archive_checklist, staff_signs, desensitize_authorized)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, '{}'::jsonb, '{}'::jsonb, false)",
                ARC_BARE, TENANT, CUSTOMER_OTHER);
        assertEquals("23503", bareState,
                "🛑 绕过函数直插时，拦住它的必须是 V16 的复合外键（23503），实际=" + bareState
                        + "。这是“库层还有一道兜底”这条性质的证据 —— "
                        + "函数那道是为可读性，外键这道是为【绕过函数时仍然安全】");
        assertEquals(0, archiveRows(TENANT, ARC_BARE),
                "被外键拒后不得留下任何行");
    }

    // ==================================================================
    // 判据 ⑩
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("⑩ 上下文一致性守卫：已持有别的租户上下文时，归档必须被拒（不允许静默改写）")
    void an_existing_foreign_tenant_context_blocks_the_archive() {
        assertEquals(0, archiveRows(TENANT, ARC_CTX), "前置失败：ARC_CTX 应尚未归档");

        // 🛑 在外层事务里先设一个【别的】租户的上下文，再在同一事务内调 register。
        //    CaseArchiveService / CaseArchiveLedger 的 TransactionTemplate 传播缺省为 REQUIRED
        //    ⇒ 它会【加入】本事务，故 V20 的 current_setting('app.tenant_id') 能读到那个值。
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> tx.execute(status -> {
                    jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT_OTHER + "'");
                    return service.register(fullRecord(ARC_CTX, CUSTOMER), OPERATOR);
                }),
                "🛑 已持有租户上下文 " + TENANT_OTHER + " 时，以租户 " + TENANT
                        + " 的身份归档竟然成功了 —— 上下文守卫失效。"
                        + "🛑 归档尤其不可静默改写：它写的是【举证材料】，"
                        + "跨租户的归档档案会随客户 scope 可见 —— "
                        + "后果不是“一条数据脏了”，而是“租户 A 的案卷里出现了一份"
                        + "属于租户 B 客户的归档档案”");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V20 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 而不是那道守卫 —— "
                        + "两者都 fail-closed，但“守卫发现上下文不一致”与"
                        + "“RLS 恰好挡住了”是完全不同的结论。异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("不一致"),
                "🛑 拒绝必须是【写明理由】的一条错误。异常链=" + fullCauseMessages(ex));

        assertEquals(0, archiveRows(TENANT, ARC_CTX), "被拒之后本租户内不得留下任何行");

        // ---- 对照：换一个【干净的】事务必须成功 ----
        assertEquals(CaseArchiveOutcome.CREATED, archiveOk(ARC_CTX).outcome(),
                "🛑 无既有上下文时同一调用必须成功 —— 否则上面那条“被拒”就无法与"
                        + "“这个客户/这个租户本身归档不上”区分");
    }

    // ==================================================================
    // 判据 ⑪（静态：本类刻意【不】提供改写入口）
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("⑪ 🛑 本通路【不提供】任何改写/删除入口（静态）：无 UPDATE / DELETE case_archive，也无 unarchive 原语")
    void the_path_deliberately_provides_no_rewrite_or_delete_entry() throws IOException {
        Path root = skeletonRoot();
        List<String> rels = List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/repository/CaseArchiveLedger.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/archive/service/CaseArchiveService.java");

        for (String rel : rels) {
            String code = stripComments(Files.readString(root.resolve(rel), StandardCharsets.UTF_8));
            for (String forbidden : List.of("UPDATE case_archive", "DELETE FROM case_archive")) {
                assertFalse(code.contains(forbidden),
                        "🛑 " + rel + " 里出现了 `" + forbidden + "` —— "
                                + "本通路【有意】不提供改写或删除归档档案的入口，三条理由："
                                + "① 归档是【终态】（P0-14 把它定义为收口），收口不可撤销；"
                                + "② “未归档”这个语义由 refund.outcome 承担，不由本表承担 ——"
                                + "提供“删除归档行”等于给了第二条把工单从已归档改回未归档的路，"
                                + "而那条路会绕过 refund 侧的状态机；"
                                + "③ 归档档案是【举证材料】，一份可以被顺手改一下的证据不是证据。"
                                + "🛑 注意：库里目前【没有】“归档后只读”的库层约束"
                                + "（无改写通路已实现，库层禁止改写未实现 —— 见 V20 文件头）。"
                                + "本判据只保证本通路不提供入口，不声称库层已禁止改写");
            }
        }

        // ---- 自证：扫描有判别力（正样本 = 一个确实含 UPDATE 的既有账本）----
        String withUpdate = stripComments(Files.readString(root.resolve(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/repository/"
                        + "RefundWorkOrderLedger.java"), StandardCharsets.UTF_8));
        assertTrue(withUpdate.contains("UPDATE refund") || withUpdate.contains("UPDATE_OUTCOME_SQL"),
                "自证失败：RefundWorkOrderLedger 应当含 UPDATE 语句（它是状态机载体）。"
                        + "若这条不成立，本判据的“零命中”就没有判别力");

        // ---- V20 里也不得有 undone / unarchive 原语 ----
        String v20 = Files.readString(root.resolve("dy-app/src/main/resources/db/migration/"
                + "V20__case_archive_provisioning.sql"), StandardCharsets.UTF_8);
        String withoutComments = stripSqlComments(v20);
        for (String forbidden : List.of("unarchive_case", "delete_case_archive",
                "UPDATE case_archive", "DELETE FROM case_archive")) {
            assertFalse(withoutComments.contains(forbidden),
                    "🛑 V20 里出现了 " + forbidden + " —— 本迁移刻意【没有】“取消归档”原语："
                            + "“未归档”由 refund.outcome 承担，本表只做收口");
        }
        // 自证：V20 里确实有 INSERT INTO case_archive（否则上面的"没有 DELETE"是空集对空集）
        assertTrue(withoutComments.contains("INSERT INTO case_archive"),
                "自证失败：V20 里应当有 INSERT INTO case_archive（函数体内的写入）。"
                        + "若这条不成立，说明剥注释把函数体也剥掉了 —— "
                        + "那么上面的“没有 DELETE”与“没有 UPDATE”就没有判别力");
    }

    // ==================================================================
    // 三、辅助断言
    // ==================================================================

    private static int countAudit(String action, String targetId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId);
        return n == null ? 0 : n;
    }

    /**
     * 断言某条审计存在、字段逐项正确、payload 含指定片段；返回 payload 供调用方继续断言。
     *
     * <p>🛑 按 {@code id} 精确取（而不是"按 action 取最新一条"）：后者在同一次运行里
     * 有多条同 action 审计时会取错行，于是"payload 里有没有某个模式"这条断言
     * 就会在别的那一行上成立 —— 一个自己造出来的假通过。
     */
    private static String assertAudit(String auditId, String action, String targetId,
                                      String payloadFragment) {
        assertNotNull(auditId, "归档回执必须带上本次留痕的审计 id —— 它是事后对账的唯一锚点");
        List<String[]> rows = jdbc.query(
                "SELECT action, target_type, target_id, actor, coalesce(payload, '') "
                        + "FROM audit_log WHERE id = ?::uuid",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)},
                auditId);
        assertEquals(1, rows.size(),
                "回执给出的 auditId 必须在库中恰对应一条审计（实际条数=" + rows.size()
                        + "）—— 否则回执是无法用于对账的");
        String[] r = rows.get(0);
        assertEquals(action, r[0], "action 必须逐字等于 " + action);
        assertEquals(TARGET_TYPE, r[1], "target_type 必须是 case_archive");
        assertEquals(targetId, r[2], "target_id 必须是本次归档的 archive_id");
        assertEquals(OPERATOR, r[3],
                "审计 actor 必须是调用方提供的操作者，不是 'case-archive-registration' 这种系统占位值 —— "
                        + "审计里 actor 是唯一有追责含义的一栏");
        assertTrue(r[4].contains(payloadFragment),
                "payload 必须含 " + payloadFragment + "（本次走的是哪条路径）。实际=" + r[4]);
        return r[4];
    }

    /** 🛑 反向断言：结论内容【不得】进审计 payload（audit_log 全租户可读）。 */
    private static void assertConclusionNotInPayload(String payload) {
        assertFalse(payload.contains("退款终止：已按 C.3 全部结论项处理完毕"),
                "🛑 审计 payload 里出现了 final_conclusion 的内容 —— "
                        + "audit_log 是【无 RLS、全租户可读】的（哈希链连续性的要求）。"
                        + "把结论写进去等于把一份客户的处理结论公开给所有租户。"
                        + "与 DeviceService 不写 param_template_id（经营信息）是同一条纪律，"
                        + "但本域的内容更敏感。实际=" + payload);
        assertTrue(payload.contains("conclusionPresent"),
                "🛑 反过来，conclusionPresent 这个【事实性】字段必须在 —— "
                        + "若连它也没有，上面那条“不得含结论”就退化成一条永真断言"
                        + "（一个什么都不写的实现同样能让它通过）。实际=" + payload);
    }

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

    /** 在租户上下文里执行一条<b>必须失败</b>的写语句，返回最深层的 SQLSTATE（未失败则 null）。 */
    private static String writeExpectingFailure(String tenantId, String sql, Object... args) {
        try {
            inTenant(tenantId, () -> {
                jdbc.update(sql, args);
                return null;
            });
            return null;
        } catch (RuntimeException ex) {
            return deepestSqlState(ex);
        }
    }

    /** 与 {@link #writeExpectingFailure} 同款，但返回整条 cause 链文本。 */
    private static String failureChain(String tenantId, String sql, Object... args) {
        try {
            inTenant(tenantId, () -> {
                jdbc.update(sql, args);
                return null;
            });
            return null;
        } catch (RuntimeException ex) {
            return fullCauseMessages(ex);
        }
    }

    // ==================================================================
    // 四、静态工具
    // ==================================================================

    /**
     * 从迁移文本里取出某个 {@code text[]} 常量赋值语句之后的<b>字符串字面量集合</b>。
     *
     * <p>🛑 判据的适用范围 = 它的锚点范围（本仓纪律）。本方法的锚点是
     * {@code v_hard_gate_keys text[] := ARRAY[ … ];} 这一段，故它只回答
     * "这个数组里有哪些键"，不回答"这个数组有没有被用在对的地方"
     * （后者由判据 ⑤ 的行为断言守着）。
     *
     * <p>🛑 实现取"从锚点到第一个 {@code ]} 之间的单引号字面量"：
     * 该形态在 V20 里是窄且稳定的（数组元素只有 {@code '...'} 这一种写法）。
     * 若将来数组写法变了（例如用 {@code array[...]}），本方法会解析出空集 ——
     * 而空集会让 {@code assertEquals(5, sqlHard.size())} 先红，
     * 于是"解析器失效"与"数组真的空了"不会混为一谈。
     */
    private static Set<String> quotedKeySetAfter(String sql, String constName) {
        int at = sql.indexOf(constName);
        assertTrue(at >= 0, "迁移文本里找不到常量 " + constName + " —— 本判据的锚点失效");
        int bracket = sql.indexOf("ARRAY[", at);
        assertTrue(bracket >= 0, "常量 " + constName + " 之后找不到 ARRAY[ —— 数组写法已变，锚点失效");
        int end = sql.indexOf(']', bracket);
        assertTrue(end > bracket, "数组未闭合");
        String seg = sql.substring(bracket, end);

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
            // 🛑 只收 snake_case 形态的键字面量，跳过注释里的中文/其它引号内容：
            //    V20 的数组段里有中文注释（"-- ARC-01 退款原因已记录"）但不含单引号，
            //    故这里以 `^[a-z_][a-z0-9_]*$` 过滤即可把"键"与"别的引号内容"分开。
            if (lit.matches("[a-z_][a-z0-9_]*")) {
                out.add(lit);
            }
            i = q2 + 1;
        }
        return out;
    }

    /**
     * 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>。
     *
     * <p>🛑 不能只做正则替换：本仓注释里大量出现中文与反引号，
     * 且本包的类注释里逐字写着 {@code @RestController}、{@code UPDATE case_archive}
     * 之类的字面（作为"刻意的形态"的说明）。必须真正按状态机扫，
     * 才能区分"注释里提到"与"代码里引用"——
     * 否则判据 ⑦(b)/⑪ 会把一段正确的文档判成一次越界。
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
     * 剥离 SQL 注释（{@code --} 与 {@code /* *\/}），<b>但保留美元引用块</b>与字符串字面量
     * —— 函数体就在美元引用块里，而 {@code INSERT INTO case_archive} 恰恰写在函数体里。
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
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\$[A-Za-z_]*\\$").matcher(src.substring(i));
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

    // ==================================================================
    // 五、收尾
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
                // 🛑 case_archive 引用 customer ⇒ 先删子再删父（顺序错会以 23503 失败）
                j.update("DELETE FROM case_archive WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM store WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM region WHERE tenant_id = ?::uuid", tid);
            });
        }
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);

        // ---- 清理自证 ----------------------------------------------------
        // 🛑 FORCE RLS 下无上下文时 DELETE 会【静默删 0 行】，故必须回头数一次。
        Integer leftTenants = j.queryForObject(
                "SELECT count(*) FROM tenant WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, TENANT, TENANT_OTHER);
        assertTrue(leftTenants == null || leftTenants == 0,
                "清理后仍残留 " + leftTenants + " 个租户 —— 清理逻辑失效");
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                assertTrue(j.queryForObject("SELECT count(*) FROM case_archive", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有归档档案残留（FORCE RLS 会静默删 0 行，"
                                + "这正是最容易被忽略的一类残留）");
                assertTrue(j.queryForObject("SELECT count(*) FROM store", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有门店残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM customer", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有客户残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下 ----------------------------
        // 🛑 audit_log 是一条全局单链，删掉中间任何一行会让【它的后继】prev_hash 对不上，
        //    从而为后续每一次 verifyChain() 留下一个永久的假断口。
        assertTrue(serviceCalls > 0,
                "自证失败：serviceCalls 为 0，说明本类从未真正走过归档路径 —— "
                        + "那么上面所有“必须有审计”的断言都没有载体");
        Integer created = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_ARCHIVED, ARC_FULL);
        assertTrue(created != null && created >= 1,
                "判据 ① 的归档必须留下 CASE_ARCHIVED 审计（action=" + ACTION_ARCHIVED
                        + " target_id=" + ARC_FULL + "）。实际条数=" + created);
        Integer replayed = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REPLAYED, ARC_IDEM);
        assertTrue(replayed != null && replayed >= 1,
                "🛑 判据 ② 的重放必须留下 CASE_ARCHIVE_REPLAYED 审计 —— "
                        + "本域刻意让重放用【独立的 action】（见 CaseArchiveService 的常量注释："
                        + "重放不代表一次新的收口，而“归档率”是一个会被取数的合规指标，"
                        + "共用 action 会让计数虚高）。实际条数=" + replayed);
        // 🛑 反向：重放【不得】污染首次的计数（上面那条"独立 action"的设计意图）
        Integer shouldBeOne = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_ARCHIVED, ARC_IDEM);
        assertEquals(1, shouldBeOne,
                "🛑 ARC_IDEM 只被【首次】归档过一次 ⇒ CASE_ARCHIVED 应恰 1 条"
                        + "（重放那次走的是 CASE_ARCHIVE_REPLAYED）。"
                        + "若这里是 2，说明重放也被记成了 CASE_ARCHIVED —— "
                        + "那会让“归档率”这类取数指标把重放也算进去。实际=" + shouldBeOne);
    }
}