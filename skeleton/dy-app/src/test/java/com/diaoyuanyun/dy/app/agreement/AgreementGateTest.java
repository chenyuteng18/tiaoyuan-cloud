package com.diaoyuanyun.dy.app.agreement;

import com.diaoyuanyun.dy.app.agreement.domain.AgreementOutcome;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementRecord;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementSignKey;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementSnapshot;
import com.diaoyuanyun.dy.app.agreement.repository.AgreementLedger;
import com.diaoyuanyun.dy.app.agreement.service.AgreementService;
import com.diaoyuanyun.dy.app.agreement.service.AgreementService.AgreementResult;
import com.diaoyuanyun.dy.app.doctpl.service.DocFileService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-1 · 调理协议书离线签署通路的真库真表端到端回归</b> ——
 * 它见证的是 {@code agreement} 表从「零生产写入方」到「有写入方」这件事
 * <b>真的让「签署登记」这个动作在系统里可以发生</b>，从而让
 * PRD G1「签署合规率」第一次<b>有分子</b>。
 *
 * <h2>🛑🛑 判据设计零：这条链的缺口表现【不是任何错误】，而是"G1 的分子恒为 0"</h2>
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
 *       <td><b>无</b>（既不报错也不阻断）</td>
 *       <td><b>无人</b> —— 只在举证时暴露</td></tr>
 *   <tr><td><b>A-1</b></td><td>{@code agreement}</td>
 *       <td><b>无</b>（不报错、不阻断）</td>
 *       <td><b>无人</b> —— 但 G1 的取数 SQL 返回空集</td></tr>
 * </table>
 * ⇒ 故本类的验收口径<b>不能</b>照抄前四批的任何一条：
 * <ul>
 *   <li><b>不是</b>"某个 23503 消失了"：本链不产生 23503（{@code agreement}
 *       的复合外键只在"客户/方案不存在"时才炸，那是负样本）；</li>
 *   <li><b>不是</b>"某道预检返回 true 了"：本链的检查点不在下游；</li>
 *   <li><b>是</b>：<b>「一次完整的签署登记调用真的落了一份可读回、四方齐备、
 *       内容未被改写、hash 自洽的协议」</b>，且这个动作在<b>没有本通路</b>时是做不到的。</li>
 * </ul>
 * <p>🛑 可迁移的教训（与 B-13 同族，但本批多一层）：
 * <b>零写入方的后果不一定是"某个调用失败"，也可能是"某个指标永远是 0"。</b>
 * 后者比"调用失败"更难发现 —— 一个恒为 0 的比率看起来像"业务还没开展"。
 *
 * <h2>🛑🛑 本类的靶心一：应用层 hash 重算（库层做不到它）</h2>
 * 判据 ⑤ 是那个<b>唯一守卫</b>的正面见证。实测真库 {@code pg_extension} 只有 plpgsql
 * ⇒ {@code pgcrypto} 未装 ⇒ {@code digest()} 不存在 ⇒ <b>库层无法从正文重算 SHA-256</b>。
 * V21 的 {@code register_agreement()} 只能校验 hash 的<b>形态</b>，并在注释里逐字声明
 * "不得把本条读成『库层已校验 hash 内容』"。
 * <p>⇒ 若本判据不存在，"渲染稿 + hash"这套举证设计就退化成
 * <b>"库里存了两列、而它们的关系从未被验证"</b>：
 * 一份被篡改的正文配一个旧的 hash 会<b>静默通过</b>全部库层校验。
 *
 * <h2>🛑🛑 本类的靶心二：跨租户撞号（本域后果比归档域更直接）</h2>
 * 判据 ④ 是那个封堵点的正面见证。与归档域的差别在于<b>后果的即时性</b>：
 * <ul>
 *   <li>归档域：误判在<b>举证时</b>才暴露（审计要看归档清单，而清单查不到）；</li>
 *   <li><b>本域</b>：签署登记<b>有一个下游消费者</b> —— 实测
 *       {@code CustomerGateGuard} 已登记 {@code PLAN_APPROVED → AGREEMENT_SIGNED}
 *       的跃迁。若 {@code register_agreement()} 对"agreement_id 被别的租户占用"
 *       返回 {@code ALREADY_EXISTS}，调用方会<b>立刻</b>把客户推进已签态，
 *       而协议并不存在 ⇒ "未签不得首次调理"这条硬门禁<b>在那一刻失效</b>。</li>
 * </ul>
 * ⇒ 故判据 ④ 除了断言"被拒"，还额外断言<b>本租户内一行都没留下</b>（见 ④c）。
 *
 * <h2>🛑 本类的靶心三：重放不得改写既有证据（协议是证据快照）</h2>
 * 判据 ② 用"重放时显式提供一组不同的参数"来钉住它 —— 只断言行数
 * 抓不住"重放顺手改写了既有协议"。本域这条比归档域更贵：
 * 被改写的若是 {@code signer}（签署人），那就是<b>伪造签名</b>。
 *
 * <h2>判据清单（12 条，≥ 验收要求的 9 条）</h2>
 * <ol>
 *   <li>① 完整签署登记：四方齐备 + 条款/渲染稿/hash 原样可读回；</li>
 *   <li>② 幂等两态：CREATED → ALREADY_EXISTS，且<b>重放不得改写既有证据</b>；</li>
 *   <li>③ 审计失败 ⇒ 签署登记回滚（用真实 22001 撞这条易静默失效的不变量）；</li>
 *   <li>④ 跨租户撞号必须 RAISE(P0001)，且本租户内不得留下任何行；</li>
 *   <li>⑤ 🛑🛑 <b>hash 重算不一致必须被拒</b>（库层做不到这条，是本域唯一守卫）；</li>
 *   <li>⑥ 四方签署缺项必须被拒，且错误消息<b>含缺项名</b>（P0-25 逐字要求）；</li>
 *   <li>⑦ 形态：无 HTTP 映射的组件 + V21 两个原语在位 + <b>静态</b>核对
 *       四方键集与枚举一致（含"恰 4 个"）；</li>
 *   <li>⑧ 方案版本不存在 / {@code plan_version=0} 必须被拒（本域特有门禁）；</li>
 *   <li>⑨ 跨租户客户引用被拒（函数侧可读错误 + 裸 SQL 绕过函数被 23503）；</li>
 *   <li>⑩ 上下文一致性守卫（已持有别的租户上下文必须被拒）；</li>
 *   <li>⑪ 模板指针成对 + 存在性 + 版本相符（本域特有门禁）；</li>
 *   <li>⑫ 本类【不提供】改写入口（静态：无 UPDATE / DELETE agreement）。</li>
 * </ol>
 *
 * <h2>数据与清理</h2>
 * 本类用 {@code a1000000-} 前缀（与其它测试类零交集）。收尾时清业务行、
 * <b>不删审计</b>（{@code audit_log} 是全局单链，删中间行会留下永久的
 * {@code prev_hash} 断口 —— 改为断言"证据确实留下了"）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("A-1 · 协议书离线签署通路真库回归（两态 / hash 重算 / 跨租户撞号 / 四方门禁 / 同事务审计）")
class AgreementGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code a1000000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "a1000000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ④ / ⑨ / ⑩ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "a1000000-0000-0000-0000-0000000000f1";

    private static final String REGION = "a1000000-0000-0000-0000-000000000011";
    private static final String STORE = "a1000000-0000-0000-0000-000000000021";
    private static final String STAFF = "a1000000-0000-0000-0000-000000000031";

    /** 本租户客户 —— 协议的行级 scope 承载者。 */
    private static final String CUSTOMER = "a1000000-0000-0000-0000-000000000032";

    /** 属于<b>另一个</b>租户的客户 —— 判据 ⑨ 的跨租户引用负样本。 */
    private static final String CUSTOMER_OTHER = "a1000000-0000-0000-0000-0000000000c2";

    /** 本租户方案（v1）—— 判据 ① 等的合法绑定。 */
    private static final String PLAN = "a1000000-0000-0000-0000-0000000000e1";
    /** 另一个租户的方案（判据 ④ 的对照租户用）。 */
    private static final String PLAN_OTHER = "a1000000-0000-0000-0000-0000000000e2";
    /** 一个<b>从不</b>建行的方案 id —— 判据 ⑧ 的"方案不存在"负样本。 */
    private static final String PLAN_GHOST = "a1000000-0000-0000-0000-0000000000ef";

    /** 本租户文档模板（editor 型，content 非空）—— 判据 ⑪ 用。 */
    private static final String TPL = "a1000000-0000-0000-0000-0000000000d5";
    /** 一个<b>从不</b>建行的模板 id —— 判据 ⑪ 的"模板不存在"负样本。 */
    private static final String TPL_GHOST = "a1000000-0000-0000-0000-0000000000df";

    // —— 协议载体（各自独享，见下方逐个说明）——
    /** 判据 ①：完整签署登记（全部可选字段）。 */
    private static final String AGR_FULL = "a1000000-0000-0000-0000-000000000041";
    /** 判据 ②：幂等两态 + "重放不得改写证据"。 */
    private static final String AGR_IDEM = "a1000000-0000-0000-0000-000000000042";
    /** 判据 ③：审计失败注入用。 */
    private static final String AGR_TX = "a1000000-0000-0000-0000-000000000043";
    /** 判据 ④：跨租户撞号 —— 租户 A 先占用这一个。 */
    private static final String AGR_SHARED = "a1000000-0000-0000-0000-000000000044";
    /** 判据 ⑤：hash 重算不一致的负样本（不应落库）。 */
    private static final String AGR_HASH_BAD = "a1000000-0000-0000-0000-000000000045";
    /** 判据 ⑥：四方签署缺项（不应落库）。 */
    private static final String AGR_SIGN_MISSING = "a1000000-0000-0000-0000-000000000046";
    /** 判据 ⑧：方案不存在（不应落库）。 */
    private static final String AGR_PLAN_GHOST = "a1000000-0000-0000-0000-000000000047";
    /** 判据 ⑨：跨租户客户引用（不应落库）。 */
    private static final String AGR_CROSS_CUST = "a1000000-0000-0000-0000-000000000048";
    /** 判据 ⑩：上下文一致性守卫用。 */
    private static final String AGR_CTX = "a1000000-0000-0000-0000-000000000049";
    /** 判据 ⑪：模板指针成对/存在性/版本（不应落库）。 */
    private static final String AGR_TPL = "a1000000-0000-0000-0000-00000000004a";
    /** 判据 ④ 对照：本租户自有 id 的建→重放。 */
    private static final String AGR_REPLAY_OWN = "a1000000-0000-0000-0000-00000000004c";
    /** 判据 ⑨ 的裸 SQL 载体（绕过函数直插，验证复合外键兜底）。 */
    private static final String AGR_BARE = "a1000000-0000-0000-0000-00000000004b";

    /** 审计动作名与目标类型 —— 与 {@code AgreementService} 的常量逐字一致（那里是包私有）。 */
    private static final String ACTION_SIGNED = "AGREEMENT_SIGNED";
    private static final String ACTION_REPLAYED = "AGREEMENT_SIGN_REPLAYED";
    private static final String TARGET_TYPE = "agreement";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-a1-witness";

    /** 🛑 判据 ③ 的注入载荷：超 {@code audit_log.actor VARCHAR(128)} 的操作者标识。 */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /**
     * 判据 ② 用的"第二次写入的签署人"—— 它的唯一作用是作「重放不得改写证据」的靶子。
     *
     * <p>🛑 为什么需要一个可识别、且与首次不同的值：只断言行数抓不住
     * "重放顺手改写了既有协议"。本域这条比归档域更贵 ——
     * 被改写的若是 {@code signer}，那就是<b>伪造签名</b>。
     */
    private static final String REPLAY_OVERWRITE_SIGNER = "重放试图伪造的签署人";

    /** 渲染稿正文（判据 ① 等的合法正文；hash 由 {@link #hashOf} 现算）。 */
    private static final String RENDER_OK = "调理协议书正文：甲方（客户）自愿接受调理服务，条款见快照。";
    /** 判据 ② 重放的"另一份正文"（若被写入即可见 —— 🛑 本域不期望它出现）。 */
    private static final String RENDER_REPLAY = "重放试图改写的正文";

    /** 真正调用过签署登记的次数（供 {@code @AfterAll} 断言「审计证据确实留下了」，而不删它）。 */
    private static int serviceCalls;

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    private static int baselineSignedFull;
    private static int baselineSignedIdem;
    private static int baselineReplayedIdem;

    @Autowired
    DataSource springDataSource;

    @Autowired
    AgreementService service;

    @Autowired
    AgreementLedger ledger;

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
     * 种两个租户 + 区域/门店/员工/客户 + 方案 + 文档模板。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code agreement} 行 —— 那是本类全部判据的载体，
     * 必须由 {@link AgreementService} 建。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'A1租户-协议书签署', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'A1对照租户', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);

        inTenant(TENANT, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                    + "VALUES (?::uuid, ?::uuid, 'A1区') ON CONFLICT DO NOTHING", REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'A1店', '直营') ON CONFLICT DO NOTHING",
                    STORE, TENANT, REGION);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    STAFF, TENANT, STORE);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'A1客户', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER, TENANT);
            // 方案 v1（approved）—— agreement 的复合外键 (plan_id, plan_version) 一端
            jdbc.update("INSERT INTO plan (plan_id, tenant_id, customer_id, version,"
                            + " treatment_json, lifestyle_json, intent_params, status)"
                            + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, '{}'::jsonb, '{}'::jsonb,"
                            + " '{}'::jsonb, 'approved') ON CONFLICT DO NOTHING",
                    PLAN, TENANT, CUSTOMER);
            // 文档模板（editor 型 ⇒ CHECK 要求 content 非空、file_ref 为空）
            jdbc.update("INSERT INTO doc_template (template_id, tenant_id, doc_type, title,"
                            + " content, version, is_active, source_type)"
                            + " VALUES (?::uuid, ?::uuid, '调理协议书', 'A1协议书模板',"
                            + " '# 协议书', 3, true, 'editor') ON CONFLICT DO NOTHING",
                    TPL, TENANT);
            return null;
        });

        inTenant(TENANT_OTHER, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'A1对照区') ON CONFLICT DO NOTHING",
                    "a1000000-0000-0000-0000-000000000012", TENANT_OTHER);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'A1对照店', '直营') ON CONFLICT DO NOTHING",
                    "a1000000-0000-0000-0000-000000000022", TENANT_OTHER,
                    "a1000000-0000-0000-0000-000000000012");
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'A1对照客户', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER_OTHER, TENANT_OTHER);
            jdbc.update("INSERT INTO plan (plan_id, tenant_id, customer_id, version,"
                            + " treatment_json, lifestyle_json, intent_params, status)"
                            + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, '{}'::jsonb, '{}'::jsonb,"
                            + " '{}'::jsonb, 'approved') ON CONFLICT DO NOTHING",
                    PLAN_OTHER, TENANT_OTHER, CUSTOMER_OTHER);
            return null;
        });
    }

    /** 记录本次运行前、三条关键审计的既有条数（供 {@code @AfterAll} 算增量）。 */
    private static void captureAuditBaseline() {
        baselineSignedFull = countAudit(ACTION_SIGNED, AGR_FULL);
        baselineSignedIdem = countAudit(ACTION_SIGNED, AGR_IDEM);
        baselineReplayedIdem = countAudit(ACTION_REPLAYED, AGR_IDEM);
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

    /** 该协议在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int agreementRows(String tenantId, String agreementId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM agreement WHERE agreement_id = ?::uuid",
                Integer.class, agreementId));
        return n == null ? 0 : n;
    }

    /**
     * 该协议的 {@code [signer::text, rendered_hash, rendered_snapshot, created_by,
     * plan_version, doc_template_version]}；无则 {@code null}。
     */
    private static String[] agreementState(String tenantId, String agreementId) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT signer::text, rendered_hash, rendered_snapshot,"
                            + "       coalesce(created_by, ''), plan_version::text,"
                            + "       coalesce(doc_template_version::text, '')"
                            + "  FROM agreement WHERE agreement_id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)},
                    agreementId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 现算正文的 SHA-256（与 {@code AgreementRecord} / {@code DocFileService} 同一口径）。 */
    private static String hashOf(String text) {
        return DocFileService.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 合法的 4 方签署块。 */
    private static Map<String, String> signerOk() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AgreementSignKey.CUSTOMER.code(), "赵一");
        m.put(AgreementSignKey.MERIDIAN_THERAPIST.code(), "钱二");
        m.put(AgreementSignKey.THERAPIST.code(), "孙三");
        m.put(AgreementSignKey.STORE_OWNER.code(), "李四");
        return m;
    }

    /** 合法的退款条款快照（非空对象）。 */
    private static Map<String, Object> refundClauseOk() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refund", "协商一致可终止");
        m.put("termination", "提前7日告知");
        return m;
    }

    /** 一个完整合法的签署登记（判据 ① 用；带全部可选字段）。 */
    private static AgreementRecord fullRecord(String agreementId, String customerId) {
        return new AgreementRecord(TENANT, UUID.fromString(agreementId), UUID.fromString(customerId),
                UUID.fromString(PLAN), 1,
                refundClauseOk(),
                Map.of("breach", "违约按合同处理"),
                Instant.parse("2026-09-20T10:00:00Z"),
                signerOk(),
                UUID.fromString(TPL), 3,
                RENDER_OK, hashOf(RENDER_OK),
                "a1-witness");
    }

    /** 一个最简合法的签署登记（无 breach / 无模板 / 无 createdBy）。 */
    private static AgreementRecord plainRecord(String tenantId, String agreementId,
                                               String customerId, String planId) {
        return AgreementRecord.of(tenantId, UUID.fromString(agreementId),
                UUID.fromString(customerId), UUID.fromString(planId), 1,
                refundClauseOk(), Instant.parse("2026-09-20T10:00:00Z"),
                signerOk(), RENDER_OK, hashOf(RENDER_OK));
    }

    private AgreementResult sign(AgreementRecord r, String operator) {
        serviceCalls++;
        return service.register(r, operator);
    }

    // ==================================================================
    // 判据 ①
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 完整签署登记：一次调用真的落一份可读回、四方齐备、内容未被改写的协议（本条就是「G1 分子能否非 0」的验收）")
    void a_full_sign_call_really_lands_a_readable_intact_record() {
        assertEquals(0, agreementRows(TENANT, AGR_FULL),
                "前置失败：AGR_FULL 已在库里 ⇒ 下面那条 CREATED 断言会以【归因错误】的方式失败");

        // ---- ①a 落库 ----
        AgreementResult r = sign(fullRecord(AGR_FULL, CUSTOMER), OPERATOR);
        assertEquals(AgreementOutcome.CREATED, r.outcome(),
                "首次签署登记必须 CREATED —— 若返回 ALREADY_EXISTS，说明载体被占用了"
                        + "（那是一次归因错误的红：问题在用例隔离，不在签署逻辑）");
        assertEquals(AGR_FULL, r.agreementId().toString(), "回执必须带 agreement_id");
        assertEquals(CUSTOMER, r.customerId().toString(), "回执必须带 customer_id");
        assertEquals(1, agreementRows(TENANT, AGR_FULL), "登记后本租户内必须恰有一行");

        // ---- ①b 读回：一整行，不是几个标量（见 AgreementSnapshot 类注释）----
        AgreementSnapshot snap = service.snapshotOf(TENANT, UUID.fromString(AGR_FULL));
        assertNotNull(snap, "🛑 snapshotOf 必须能读回刚写入的那一行 —— "
                + "若它是 null，而 agreementRows 报 1，那就出现了一个可报警的矛盾");

        // ---- ①c 🛑 四方签署必须【全部】在，且值原样 ----
        assertEquals(AgreementSignKey.allCodes(), snap.signerOf().keySet(),
                "🛑 读回的签署块键集必须与枚举登记【完全相等】—— "
                        + "少一个说明写入时丢了键，多一个说明混进了未登记键");
        assertTrue(snap.fourPartySignerComplete(),
                "🛑 读回的四方签署必须仍齐备 —— 这比『行数=1』强得多："
                        + "它排除『写入时签署块被静默改写』（本域被改写 = 伪造签名）");
        assertEquals("李四", snap.signerOf().get(AgreementSignKey.STORE_OWNER.code()),
                "🛑 签署值必须原样读回 —— 一份没有责任人签名的协议，"
                        + "在举证场景里等同于『四方里有人没签』");

        // ---- ①d 条款快照（非空对象，原样）----
        assertEquals(refundClauseOk(), snap.refundClauseOf(),
                "🛑 退款条款快照必须原样落库（PRD §2.9「必须快照存储·不可覆盖」）");
        assertEquals("违约按合同处理", snap.breachClauseOf().get("breach"),
                "违约条款快照必须原样落库");

        // ---- ①e 渲染稿 + hash（🛑 本域的核心举证对）----
        assertEquals(RENDER_OK, snap.renderedSnapshot(), "渲染稿正文必须原样落库");
        assertEquals(hashOf(RENDER_OK), snap.renderedHash(),
                "🛑 hash 必须原样落库（且应是小写 —— V21 的 INSERT 用 lower() 归一）");
        assertTrue(snap.renderedHashRecomputedMatches(),
                "🛑🛑 读回后重算必须自洽 —— 这是『库里的 hash 与正文仍然相符』这条性质的守卫。"
                        + "库层做不到它（无 pgcrypto ⇒ 无 digest()）");

        // ---- ①f 方案 + 模板指针 ----
        assertEquals(1, snap.planVersion(), "方案版本必须原样落库");
        assertTrue(snap.hasDocTemplate(), "本次显式传了模板指针 ⇒ 读回必须成对");
        assertEquals(3, snap.docTemplateVersion(), "模板版本必须原样落库");

        // ---- ①g created_at 由库层 default 提供（本域刻意不显式写）----
        assertNotNull(snap.createdAt(),
                "🛑 created_at 必须可读（由表的 default now() 提供 —— 本域刻意不显式写它，"
                        + "因为它是『登记时刻』，而『业务时刻』是 signed_at）");
        assertNotNull(snap.signedAt(), "signed_at 必须可读（业务时刻）");

        // ---- ①h 审计 ----
        assertAudit(r.auditId(), ACTION_SIGNED, AGR_FULL, "\"mode\":\"CREATED\"");
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 幂等两态：CREATED → ALREADY_EXISTS；🛑 且重放【不得】改写既有证据（协议是证据快照，改写签署人=伪造签名）")
    void the_two_states_are_complete_and_replay_never_rewrites_the_evidence() {
        assertEquals(0, agreementRows(TENANT, AGR_IDEM), "前置失败：AGR_IDEM 应尚未登记");

        // ---- 态 1：首次 ----
        AgreementRecord first = new AgreementRecord(TENANT, UUID.fromString(AGR_IDEM),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                refundClauseOk(), null, Instant.parse("2026-09-21T09:00:00Z"),
                signerOk(), null, null, RENDER_OK, hashOf(RENDER_OK), "a1-first");
        assertEquals(AgreementOutcome.CREATED, sign(first, OPERATOR).outcome());
        String[] s0 = agreementState(TENANT, AGR_IDEM);
        assertNotNull(s0);
        assertEquals("a1-first", s0[3], "首次 created_by 必须落库");

        // ---- 态 2：重放，并【显式提供一组不同的参数】----
        //   🛑 这就是本判据的靶心：只断言行数抓不住"重放顺手改写了既有协议"。
        Map<String, String> forged = new LinkedHashMap<>(signerOk());
        forged.put(AgreementSignKey.STORE_OWNER.code(), REPLAY_OVERWRITE_SIGNER);
        AgreementRecord replay = new AgreementRecord(TENANT, UUID.fromString(AGR_IDEM),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                Map.of("refund", "重放试图改写的条款"), null,
                Instant.parse("2026-09-22T09:00:00Z"),
                forged, null, null, RENDER_REPLAY, hashOf(RENDER_REPLAY), "a1-replay");
        AgreementResult rr = sign(replay, OPERATOR);
        assertEquals(AgreementOutcome.ALREADY_EXISTS, rr.outcome(),
                "重放同一个 agreement_id 必须返回 ALREADY_EXISTS（幂等命中），"
                        + "而不是把它当成错误或冲突");

        assertEquals(1, agreementRows(TENANT, AGR_IDEM),
                "🛑 重放之后必须仍【恰有一行】—— 若变成 2 行，说明 ON CONFLICT 的推断目标"
                        + "没命中主键");

        // ---- ②a 🛑🛑 核心断言：重放【不得改写】既有证据 ----
        String[] s1 = agreementState(TENANT, AGR_IDEM);
        assertFalse(s1[0].contains(REPLAY_OVERWRITE_SIGNER),
                "🛑🛑 重放【改写了】既有协议的 signer —— 协议是【证据快照】，"
                        + "而在本域被改写的若是签署人，那就是【伪造签名】。"
                        + "这条性质由库层 ON CONFLICT DO NOTHING 给出，故它一旦失效，"
                        + "最可能的原因是有人把它改成了 DO UPDATE。实际读到=" + s1[0]);
        assertEquals(RENDER_OK, s1[2],
                "🛑 重放传入的另一份正文不得覆盖首次的正文（同上，证据未被改写）");
        assertFalse(s1[1].equalsIgnoreCase(hashOf(RENDER_REPLAY)),
                "重放的 hash 不得逃逸进库里");
        assertEquals("a1-first", s1[3], "created_by 也不得被重放改写");

        // ---- ②c 对照：不同 agreement_id 必须各自独立可建 ----
        //   🛑 顺序有讲究：这条断言必须排在 ②b 的审计断言【之前】。
        //      本类首跑实测：初版把 ②b（审计查询）放在前面，而它因 `id` 类型问题抛错中断，
        //      于是 ②c **从未执行** ⇒ AGR_REPLAY_OWN 没被建出来 ⇒ 判据 ④d 以错误归因报红。
        //      ⇒ 纪律：**副作用型断言要放在"可能中断的断言"之前**，
        //        否则一条判据的失败会顺延成另一条判据的红。
        assertEquals(AgreementOutcome.CREATED,
                sign(plainRecord(TENANT, AGR_REPLAY_OWN, CUSTOMER, PLAN), OPERATOR).outcome(),
                "🛑 换一个 agreement_id 必须能建 —— 否则上面那条『重放返回 ALREADY_EXISTS』"
                        + "就无法与『这个客户本身登记不上』区分（比较器必须对称）");

        // ---- ②b 重放仍必须写审计（且 action 与首次【不同】）----
        assertAudit(rr.auditId(), ACTION_REPLAYED, AGR_IDEM, "\"mode\":\"ALREADY_EXISTS\"");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 🛑 审计失败必须让签署登记【回滚】—— 用真实的 actor 超长（22001）撞这条易静默失效的不变量")
    void a_failed_audit_write_rolls_the_sign_back() {
        assertEquals(0, agreementRows(TENANT, AGR_TX), "前置失败：AGR_TX 应尚未登记");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sign(fullRecord(AGR_TX, CUSTOMER), TOO_LONG_OPERATOR),
                "🛑 审计写不进去时，签署登记竟然没有抛错 —— "
                        + "那说明审计失败被静默吞掉了，而『每一份协议都有来源』这条不变量已失效");

        assertEquals("22001", deepestSqlState(ex),
                "🛑 本次失败的成因必须是 22001（actor 超列宽），实际=" + deepestSqlState(ex)
                        + "。若成因是别的，那本条证明的就不是『审计失败 ⇒ 签署回滚』。异常链="
                        + fullCauseMessages(ex));

        assertEquals(0, agreementRows(TENANT, AGR_TX),
                "🛑🛑 审计失败之后 agreement 行【仍在】库里 —— "
                        + "『审计失败则签署回滚』这条不变量已静默失效。"
                        + "最常见的成因是有人把 AgreementLedger.register() / "
                        + "AgreementService.register() 的 TransactionTemplate 改成了 "
                        + "PROPAGATION_REQUIRES_NEW（签署独立提交，审计失败只回滚审计）—— "
                        + "此时协议已落库而没有任何来源证据。对本域而言这不是一条审计缺口，"
                        + "而是一份证据缺了它的来源链");

        // ---- 对照 ----
        AgreementResult ok = sign(fullRecord(AGR_TX, CUSTOMER), OPERATOR);
        assertEquals(AgreementOutcome.CREATED, ok.outcome(),
                "🛑 换成合法 operator 之后必须成功 —— 否则上面那条『回滚』断言就无法与"
                        + "『这份协议本身建不上』区分");
        assertEquals(1, agreementRows(TENANT, AGR_TX), "合法路径下必须真的落库");
    }

    // ==================================================================
    // 判据 ④
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 🛑🛑 跨租户撞号必须 RAISE(P0001) 而不是返回 ALREADY_EXISTS —— 本域后果最直接（CustomerGateGuard 会立刻放行）")
    void a_cross_tenant_agreement_id_collision_must_raise_not_return_already_exists() {
        assertEquals(0, agreementRows(TENANT_OTHER, AGR_SHARED),
                "前置失败：AGR_SHARED 应尚未登记");

        // ---- ④a 租户【另一个】先占用这个全局 agreement_id ----
        AgreementRecord otherRec = plainRecord(TENANT_OTHER, AGR_SHARED, CUSTOMER_OTHER, PLAN_OTHER);
        assertEquals(AgreementOutcome.CREATED,
                sign(otherRec, "ops-a1-other").outcome(),
                "🛑 前提：另一个租户必须先成功占用这个 agreement_id。"
                        + "若这里就失败了，本判据的靶心（撞号）根本没有被构造出来");
        assertEquals(1, agreementRows(TENANT_OTHER, AGR_SHARED));

        // ---- ④b 主租户用【同一个】agreement_id ⇒ 必须 RAISE，不是 ALREADY_EXISTS ----
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sign(fullRecord(AGR_SHARED, CUSTOMER), OPERATOR),
                "🛑🛑 以本租户身份用【已被另一租户占用】的 agreement_id 登记竟然成功了 —— "
                        + "这是本域最危险的形态：agreement_pkey 是【单列】agreement_id，"
                        + "而主键冲突会走 ON CONFLICT DO NOTHING ⇒ ROW_COUNT=0，"
                        + "而本租户上下文里 SELECT 又看不见那一行 ⇒ "
                        + "若没有那条 RAISE 判定，函数会对一个手里一行都没有的租户返回 ALREADY_EXISTS，"
                        + "调用方据此把客户推进 AGREEMENT_SIGNED，"
                        + "而【协议并不存在】—— 且 CustomerGateGuard 会【立刻】放行首次调理，"
                        + "『未签不得首次调理』这条硬门禁在那一刻失效");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V21 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 主键冲突路径而不是那条跨租户判定。"
                        + "异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("另一租户"),
                "🛑 拒绝必须是【写明理由】的一条错误（agreement_id 已被另一租户占用）。"
                        + "异常链=" + fullCauseMessages(ex));

        // ---- ④c 🛑 本租户内【不得留下任何行】----
        assertEquals(0, agreementRows(TENANT, AGR_SHARED),
                "🛑 被拒之后本租户内不得留下该 agreement_id 的任何行 —— "
                        + "若这里 > 0，那说明主键冲突被绕过（例如先 DELETE 再 INSERT），"
                        + "而那会把另一个租户的协议删掉");

        // ---- ④d 对照：本租户【自己的】协议重放必须 ALREADY_EXISTS（不是 RAISE）----
        //   🛑🛑 这里刻意【先建一次、再重放】，而不是复用判据 ② 留下的 AGR_REPLAY_OWN。
        //      本类首跑实测证明了这个取舍的必要性：初版把 AGR_REPLAY_OWN 的建行
        //      委托给判据 ②（它末尾建这个载体），于是判据 ④ 的成功**取决于判据 ② 是否跑完**。
        //      当 ② 在审计查询处抛错中断时，④d 读到 CREATED 而断言 ALREADY_EXISTS ⇒
        //      **报了 4 条红，其中这一条完全归因错误**。
        //      ⇒ 纪律：**判据之间不得有隐式的"我先替你把数据建好"依赖** ——
        //        那会让一条判据的失败伪装成另一条判据的失败，排查方向被带偏。
        //        故 ④d 自己建自己用（建→重放），与 ② 的成功与否完全解耦。
        AgreementOutcome built = sign(
                plainRecord(TENANT, AGR_REPLAY_OWN, CUSTOMER, PLAN), OPERATOR).outcome();
        // 幂等三态：CREATED（首次）/ ALREADY_EXISTS（重放）；若已被 ② 建过则是重放。
        // 🛑 两种都合法 —— 本判据的靶心是【④d 那一次重放必须返回 ALREADY_EXISTS】，
        //    而不是"这个载体此刻是不是空的"。故这里不断言 built 的值，只落信息。
        assertTrue(built == AgreementOutcome.CREATED || built == AgreementOutcome.ALREADY_EXISTS,
                "🛑 建/重放的返回只允许 CREATED 或 ALREADY_EXISTS，实际=" + built);

        AgreementResult first = sign(plainRecord(TENANT, AGR_REPLAY_OWN, CUSTOMER, PLAN), OPERATOR);
        assertEquals(AgreementOutcome.ALREADY_EXISTS, first.outcome(),
                "🛑 本租户自己已有这个协议时，重放必须返回 ALREADY_EXISTS 而【不是】抛异常 —— "
                        + "『冲突了 ⇒ 是不是我的』这个判定必须真的区分两种情形，"
                        + "否则一个『任何冲突都 RAISE』的实现也能通过 ④b，而那会毁掉幂等");
    }

    // ==================================================================
    // 判据 ⑤
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 🛑🛑 hash 重算不一致必须被拒 —— 这是本域【唯一】守卫（库层无 pgcrypto ⇒ 只能校验形态）")
    void a_hash_mismatch_must_be_refused_in_the_application_layer() {
        assertEquals(0, agreementRows(TENANT, AGR_HASH_BAD), "前置失败：AGR_HASH_BAD 应尚未登记");

        // ---- ⑤a 形态合法（64 位 hex）但【与正文不符】的 hash ----
        String wrongHash = repeat('a', 64);   // 形态合法、但绝不会是 RENDER_OK 的摘要
        assertFalse(wrongHash.equalsIgnoreCase(hashOf(RENDER_OK)),
                "前提：靶子 hash 必须真的不等于正文摘要（否则本判据的靶心不成立）");

        AgreementRecord bad = new AgreementRecord(TENANT, UUID.fromString(AGR_HASH_BAD),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                refundClauseOk(), null, Instant.parse("2026-09-20T10:00:00Z"),
                signerOk(), null, null, RENDER_OK, wrongHash, "a1-bad-hash");

        // 🛑 record 构造器【不】拦它（形态合法）—— 这正是本判据的意义：
        //    库层也只校验形态，所以若服务层不重算，这行会【静默落库】。
        assertNotNull(bad, "record 构造器只校验形态，不重算 —— 故它必须能构造出来");

        BizException ex = assertThrows(BizException.class,
                () -> sign(bad, OPERATOR),
                "🛑🛑 一个形态合法但与正文不符的 hash 竟然被接受了 —— "
                        + "那说明『渲染稿 + hash』这套举证设计退化成"
                        + "『库里存了两列、而它们的关系从未被验证』："
                        + "一份被篡改的正文配一个旧的 hash 会【静默通过】全部库层校验。"
                        + "🛑 库层做不到这条（实测 pg_extension 只有 plpgsql ⇒ 无 digest()），"
                        + "故本判据是本域唯一守卫");
        assertTrue(ex.getMessage().contains("重算不一致"),
                "🛑 拒绝消息必须写明是【重算不一致】（而不是笼统的『hash 非法』）—— "
                        + "因为两者的排查方向完全不同。实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains(wrongHash),
                "拒绝消息应带上错误的 hash 值，便于定位");

        assertEquals(0, agreementRows(TENANT, AGR_HASH_BAD),
                "🛑 被拒之后不得落库 —— 若落了，说明校验发生在写入之后（那就等于没校验）");

        // ---- ⑤b 反向对照：正确 hash 必须成功（否则 ⑤a 就退化成"什么都拒绝"）----
        AgreementRecord good = new AgreementRecord(TENANT, UUID.fromString(AGR_HASH_BAD),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                refundClauseOk(), null, Instant.parse("2026-09-20T10:00:00Z"),
                signerOk(), null, null, RENDER_OK, hashOf(RENDER_OK), "a1-good-hash");
        assertEquals(AgreementOutcome.CREATED, sign(good, OPERATOR).outcome(),
                "🛑 换成正确 hash 必须成功 —— 否则上面那条『必须被拒』无法与"
                        + "『这条路本身走不通』区分");

        // ---- ⑤c 对照：大写 hash 也必须通过（同值不同写法，见 V21 设计取舍三）----
        String upperHash = hashOf(RENDER_OK).toUpperCase(java.util.Locale.ROOT);
        AgreementRecord upper = new AgreementRecord(TENANT,
                UUID.fromString("a1000000-0000-0000-0000-00000000004d"),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                refundClauseOk(), null, Instant.parse("2026-09-20T11:00:00Z"),
                signerOk(), null, null, RENDER_OK, upperHash, "a1-upper-hash");
        assertEquals(AgreementOutcome.CREATED, sign(upper, OPERATOR).outcome(),
                "🛑 大写 hash 必须通过（重算比对用 equalsIgnoreCase，与 DocFileService 同款）");
        AgreementSnapshot upperSnap = service.snapshotOf(TENANT,
                UUID.fromString("a1000000-0000-0000-0000-00000000004d"));
        assertEquals(hashOf(RENDER_OK), upperSnap.renderedHash(),
                "🛑 但入库必须归一为【小写】（V21 的 INSERT 用 lower()）—— "
                        + "否则库里会同时存在两种写法，每一个读点都要自己记得归一");
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ 四方签署缺项必须被拒，且错误消息【含缺项名】（P0-25「403 且给出缺失项名称」逐字要求）")
    void a_missing_signer_is_refused_with_the_missing_name() {
        // ---- ⑥a Java 侧构造器先拦（逐方缺项）----
        for (AgreementSignKey k : AgreementSignKey.all()) {
            Map<String, String> partial = new LinkedHashMap<>(signerOk());
            partial.remove(k.code());
            BizException ex = assertThrows(BizException.class,
                    () -> AgreementRecord.of(TENANT, UUID.fromString(AGR_SIGN_MISSING),
                            UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                            refundClauseOk(), Instant.parse("2026-09-20T10:00:00Z"),
                            partial, RENDER_OK, hashOf(RENDER_OK)),
                    "🛑 缺【" + k.label() + "】时构造器必须拒绝 —— "
                            + "PRD C.1.5 §八 逐字：「未签不得首次调理或退款判定 | 硬门禁」");
            assertTrue(ex.getMessage().contains(k.code()),
                    "🛑 错误消息必须含缺项名（" + k.code() + "）—— "
                            + "P0-25 逐字：「后端返回 403 且给出缺失项名称」。实际=" + ex.getMessage());
        }

        // ---- ⑥b 值为空白也必须被拒（一个空白的"门店负责人"不是签字）----
        Map<String, String> blank = new LinkedHashMap<>(signerOk());
        blank.put(AgreementSignKey.STORE_OWNER.code(), "   ");
        BizException exBlank = assertThrows(BizException.class,
                () -> AgreementRecord.of(TENANT, UUID.fromString(AGR_SIGN_MISSING),
                        UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                        refundClauseOk(), Instant.parse("2026-09-20T10:00:00Z"),
                        blank, RENDER_OK, hashOf(RENDER_OK)),
                "🛑 空白值必须被拒 —— DDL 的 NOT NULL 只保证 signer 整体不为空，"
                        // 🛑 这里的拼接写法有讲究：Java 没有 C 的「相邻字面量拼接」，
                //    `":"   "}"` 会被 javac 判为"需要')'"，故必须显式 `+` 连接。
                + "挡不住它里面是 {" + AgreementSignKey.STORE_OWNER.code() + ":" + "}");
        assertTrue(exBlank.getMessage().contains("空白"),
                "消息应写明是『值为空白』。实际=" + exBlank.getMessage());

        // ---- ⑥c 未登记键也必须被拒（不得静默丢弃）----
        Map<String, String> unknown = new LinkedHashMap<>(signerOk());
        unknown.put("stores_owner", "错拼的门店负责人");
        BizException exUnknown = assertThrows(BizException.class,
                () -> AgreementRecord.of(TENANT, UUID.fromString(AGR_SIGN_MISSING),
                        UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                        refundClauseOk(), Instant.parse("2026-09-20T10:00:00Z"),
                        unknown, RENDER_OK, hashOf(RENDER_OK)),
                "🛑 未登记键必须被拒 —— 静默丢弃会让一次拼写错误同时产出"
                        + "『某方被判未签』与『调用方以为已签』两个后果");

        // ---- ⑥d 库层（绕过 Java 侧构造器，直接调函数）----
        //   🛑 为什么必须测库层那一道：Java 侧构造器会先拦（⑥a 已证）。
        //      库层那道守的是"有人绕过 record 直接写 SQL"—— 它是最后一道。
        String sqlState = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_SIGN_MISSING, CUSTOMER, PLAN, 1,
                "{\"refund\":\"协商一致可终止\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\"}",   // ← 缺 3 方
                RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
        assertEquals("P0001", sqlState,
                "🛑 库层对四方缺项必须 RAISE(P0001)（V21 的显式门禁），实际=" + sqlState
                        + "。这是绕过 Java 构造器时的最后一道防线");
        assertEquals(0, agreementRows(TENANT, AGR_SIGN_MISSING),
                "被拒后不得落库");
    }

    // ==================================================================
    // 判据 ⑦
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 形态：无 HTTP 映射的组件 + V21 两个原语在位 + 🛑 静态核对四方键集与枚举一致（含“恰 4 个”）")
    void the_sign_path_is_a_component_and_the_key_set_matches() throws IOException {
        String ledgerSrc = read("dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/repository/AgreementLedger.java");
        String svcSrc = read("dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/service/AgreementService.java");

        // ---- ⑦a 🛑 本通路必须【不是】HTTP 端点（三条边界之一）----
        for (String ann : List.of("@RestController", "@Controller", "@RequestMapping",
                "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping")) {
            assertFalse(stripComments(ledgerSrc).contains(ann),
                    "🛑🛑 AgreementLedger 上出现了 " + ann + " —— 本迁移是【离线签署通路】"
                            + "（运维通路形态、无 HTTP 映射）。"
                            + "契约 45 个 operationId 里没有任何一个是『登记一份已签协议』；"
                            + "给它加对外端点属契约 MAJOR 变更，且要先回答契约回答不了的问题："
                            + "「谁有权登记一份已签协议」");
            assertFalse(stripComments(svcSrc).contains(ann),
                    "🛑 AgreementService 上出现了 " + ann + " —— 同 ⑦a 的理由");
        }
        // 组件注解必须在（否则它不会被装配）
        assertTrue(stripComments(ledgerSrc).contains("@Component"),
                "AgreementLedger 必须是 @Component（它要被装配进 Service）");
        assertTrue(stripComments(svcSrc).contains("@Service"),
                "AgreementService 必须是 @Service");

        // ---- ⑦b 🛑 V21 的两个原语必须被调用（本通路的存在意义）----
        assertTrue(ledgerSrc.contains("register_agreement("),
                "AgreementLedger 必须调用 V21 的 register_agreement() —— "
                        + "若把它换成裸 INSERT，则四方门禁 / 跨租户判定 / 上下文自证全部旁落");
        assertTrue(ledgerSrc.contains("latest_agreement_of("),
                "AgreementLedger 必须调用 V21 的 latest_agreement_of() —— "
                        + "『最新』的判定需要一个全序，两套口径的差别不会报错");

        // ---- ⑦c 🛑🛑 静态：V21 迁移里的四方键集必须与枚举【完全一致】----
        String sql = read("dy-app/src/main/resources/db/migration/V21__agreement_offline_signing_provisioning.sql");
        Set<String> sqlKeys = quotedKeySetAfter(sql, "v_sign_keys");
        assertEquals(AgreementSignKey.allCodes(), sqlKeys,
                "🛑🛑 V21 的 v_sign_keys 与 AgreementSignKey 枚举必须【完全相等】—— "
                        + "两边分叉会让『Java 放行而库层拒绝』（或反之），"
                        + "而那是『同一件事有两处定义』的典型后果。"
                        + "SQL 侧=" + sqlKeys + "，枚举侧=" + AgreementSignKey.allCodes());

        // ---- ⑦d 🛑 恰 4 个（钉住"不得多出一个键"）----
        assertEquals(4, sqlKeys.size(),
                "🛑 四方签署必须【恰 4 个】键 —— 多一个 = 凭空多出一方签署人，"
                        + "少一个 = 硬门禁被削弱。实际=" + sqlKeys);

        // ---- ⑦e 🛑 V21 不得含"警告门禁"结构（本域四方全是硬门禁）----
        //   与 V21 自证 (b8) 同款：本域【没有】类似"手环不得反转为阻断"的宽免语句。
        //
        //   🛑🛑 本条的作用域必须是【register_agreement 函数体】+【剥注释】，
        //       两个条件缺一不可 —— 本类首跑实测把它踩了两遍：
        //         ① 初版扫的是【整个迁移文件】⇒ 误中【第 4 节自证块】里的
        //            (b8) 断言本身（它逐字写着 v_warn_gate_keys 作为匹配模式）
        //            ⇒ 恒红。**断言的作用域 = 它声称审计的对象**，
        //            而 (b8) 审的是"函数体里有没有警告门禁结构"，不是"文件里有没有这个词"；
        //         ② 即使切到函数体，**它里面恰有一条解释性注释**写了 v_warn_gate_keys
        //            （"本数组不存在对应的'警告键集'（V20 的 v_warn_gate_keys）"）
        //            ⇒ 不剥注释也会恒红。
        //       —— 这正是 V21 自证 (b8) 注释里逐字记下的"本仓第 41 条缺陷的正向应用"。
        //          Java 侧这道是它的**镜像**，必须用同一套口径，否则两处结论会分叉。
        String regBodyOnly = sql.substring(
                sql.indexOf("$v21_register$") + "$v21_register$".length(),
                sql.indexOf("$v21_register$;"));
        String regBodyNoComment = stripSqlComments(regBodyOnly).replaceAll("\\s+", " ");
        // 先用"有牙齿"的前置断言证明锚点真的切到了函数体（否则 substring 拿空串也能过）
        assertTrue(regBodyNoComment.contains("register_agreement"),
                "🛑 取出的 register_agreement 函数体里竟然没有函数名 —— 锚点失效"
                        + "（$v21_register$ 标签变了？），本条断言会退化成『断言空串』");
        assertFalse(regBodyNoComment.matches("(?s).*\\bv_warn_gate_keys?\\b.*"),
                "🛑 V21 的 register_agreement 【函数体】里出现了 v_warn_gate_keys —— "
                        + "本域四方签署全是硬门禁（PRD §八 逐字「未签不得首次调理或退款判定」），"
                        + "凭空造出一条 PRD 没有的宽免会让硬门禁变空");
        assertFalse(regBodyNoComment.matches("(?s).*\\bwarn_gate\\b.*"),
                "🛑 V21 的 register_agreement 【函数体】里出现了 warn_gate 结构 —— 同 ⑦e 的理由");
        assertFalse(regBodyNoComment.matches("(?s).*_warn_key.*"),
                "🛑 V21 的 register_agreement 【函数体】里出现了 _warn_key 结构 —— 同 ⑦e 的理由");

        // ---- ⑦f 🛑🛑 Flyway 占位符替换必须【关闭】----
        //   🛑 这是 A-1 的实测缺陷（V21 引出）：V21 逐字引用了 PRD P0-27 的占位符白名单
        //      （${store.name} 等 6 个），而 Flyway 默认把它们当【变量】解析 ⇒
        //      "No value provided for placeholder: ${store.name}" ⇒
        //      **整个 Spring 上下文起不来**（不只是某条迁移失败；psql 直连不解析，故此前未暴露）。
        //   🛑 为什么判据落在这里而不是"某条迁移必须成功"：那是【症状】层面。
        //      本判据钉的是【根因开关】—— 关掉它，任何迁移再引用 ${...} 都不会炸。
        String appYml = read("dy-app/src/main/resources/application.yml");
        assertTrue(appYml.contains("placeholder-replacement: false"),
                "🛑🛑 application.yml 的 spring.flyway 必须显式写 `placeholder-replacement: false` —— "
                        + "V21 逐字引用了 PRD P0-27 的占位符白名单（${store.name} / "
                        + "${agreement.sign_date} 等 6 个，是**业务口径原文**），"
                        + "而 Flyway 默认把 ${...} 当变量解析 ⇒ 启动期抛 "
                        + "『No value provided for placeholder』⇒ 整个应用上下文起不来。"
                        + "🛑 两条替代方案的否决理由：① 改迁移文本会让 checksum 与真库登记值失配、"
                        + "且改到函数体（RAISE 消息）还会改 prosrc，破坏『净效果等价』对账；"
                        + "② 本仓 V1..V20 迁移文本【逐字零处】`${`（可机械核对）"
                        + "⇒ 关闭占位符替换不改变任何既有迁移的语义，是唯一无损解。"
                        + "若本断言为红，说明本项被改回 true 或被删除");
        // ---- ⑦g 上一条的反向对照：锁死"本仓不使用 Flyway 占位符"这个前提本身 ----
        //   🛑 若将来 V1..V20 里出现 `${...}`，那么"关闭占位符替换"就不再是无损的 ——
        //      本判据把那个前提变成可断言的东西（前提变了，结论必须重推）。
        for (String rel : List.of(
                "dy-app/src/main/resources/db/migration/V20__case_archive_provisioning.sql",
                "dy-app/src/main/resources/db/migration/V19__scale_provisioning_primitive.sql")) {
            String other = stripSqlComments(read(rel));
            assertFalse(other.contains("${"),
                    "🛑 " + rel + " 里出现了 `${` —— 本判据 ⑦f 的论据『V1..V20 逐字零处 ${』"
                            + "建立在『更早的迁移不使用 Flyway 占位符』这个前提上。"
                            + "前提变了 ⇒ 必须重新论证『关闭占位符替换是否仍然无损』，"
                            + "而不是继续沿用 ⑦f 的结论");
        }
    }

    // ==================================================================
    // 判据 ⑧
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑧ 🛑 方案版本不存在 / plan_version=0 必须被拒（本域特有门禁：判据必须带 version）")
    void a_missing_plan_version_is_refused() {
        // ---- ⑧a 方案 id 存在但那是【不存在】的 → 库层复合外键 (plan_id, plan_version) ----
        String sqlStateGhost = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_PLAN_GHOST, CUSTOMER, PLAN_GHOST, 1,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
        assertEquals("P0001", sqlStateGhost,
                "🛑 方案不存在必须被拒（V21 的 (4b) 显式检查），实际=" + sqlStateGhost
                        + " —— 它比让它去撞复合外键的 23503 更可归因");

        // ---- ⑧b 方案【存在】但版本不对（v99）----
        String sqlStateVer = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_PLAN_GHOST, CUSTOMER, PLAN, 99,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
        assertEquals("P0001", sqlStateVer,
                "🛑 方案存在但版本 99 不存在时必须被拒 —— 判据【必须带 version】："
                        + "(plan_id, plan_version) 是复合外键的一端，"
                        + "『方案存在但那一版不存在』必须被判为不存在。实际=" + sqlStateVer);

        // ---- ⑧c plan_version=0 必须是【业务错误】而不是撞表 CHECK 的 23514 ----
        String sqlStateZero = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_PLAN_GHOST, CUSTOMER, PLAN, 0,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
        assertEquals("P0001", sqlStateZero,
                "🛑 plan_version=0 必须给出【可归因的业务错误】(P0001)，而不是放任它去撞"
                        + "表 CHECK 的 23514（那只会让调用方收到一个约束名）。实际=" + sqlStateZero);

        // ---- ⑧d Java 侧对照：plan_version < 1 也必须在构造器拦（不给库往返机会）----
        assertThrows(BizException.class,
                () -> AgreementRecord.of(TENANT, UUID.fromString(AGR_PLAN_GHOST),
                        UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 0,
                        refundClauseOk(), Instant.parse("2026-09-20T10:00:00Z"),
                        signerOk(), RENDER_OK, hashOf(RENDER_OK)),
                "🛑 Java 侧也应先拦 plan_version=0（失败形态更可读，且省一次库往返）");
    }

    // ==================================================================
    // 判据 ⑨
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("⑨ 跨租户客户引用必须被拒：函数侧给可读业务错误(P0001)，绕过函数则被复合外键 23503 拒")
    void a_cross_tenant_customer_reference_is_refused_with_the_right_reason() {
        // ---- ⑨a 函数侧：用【另一个租户的客户】在【本租户】登记 ⇒ 被 (4) 显式检查拒 ----
        String chain = failureChain(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_CROSS_CUST, CUSTOMER_OTHER, PLAN, 1,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
        assertNotNull(chain, "🛑 跨租户客户引用竟然成功了 —— RLS + 复合外键双保险都失效了");
        assertTrue(chain.contains("P0001"),
                "🛑 函数侧应给 P0001（可读的业务错误），而不是让它去撞 23503。链=" + chain);
        assertTrue(chain.contains("不存在") || chain.contains("租户"),
                "🛑 错误消息应说明『客户在租户内不存在』（含『上下文不对会静默返回 0 行』的提示）。链=" + chain);

        // ---- ⑨b 裸 SQL 绕过函数 ⇒ 复合外键 23503 兜底（库层最后一道）----
        String bareState = writeExpectingFailure(TENANT,
                "INSERT INTO agreement (agreement_id, tenant_id, customer_id, plan_id, plan_version,"
                        + " refund_clause_snapshot, signed_at, signer, rendered_snapshot, rendered_hash)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " '{\"refund\":\"x\"}'::jsonb, ?::timestamptz, '{}'::jsonb, ?, ?)",
                AGR_BARE, TENANT, CUSTOMER_OTHER, PLAN, 1,
                "2026-09-20T10:00:00Z", RENDER_OK, hashOf(RENDER_OK));
        assertNotNull(bareState,
                "🛑 裸 SQL 用跨租户客户竟然插进去了 —— agreement_customer_id_fkey 失效了");
        // 复合外键报 23503；若无 RLS 上下文也会被 42501 拒（两者都是 fail-closed）
        assertTrue("23503".equals(bareState) || "42501".equals(bareState),
                "🛑 裸 SQL 绕过函数时必须被复合外键(23503)或 RLS(42501)拒，实际=" + bareState);
        assertEquals(0, agreementRows(TENANT, AGR_BARE), "被拒后不得落库");
    }

    // ==================================================================
    // 判据 ⑩
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("⑩ 上下文一致性守卫：已持有别的租户上下文时，签署登记必须被拒（不允许静默改写）")
    void an_existing_foreign_tenant_context_blocks_the_sign() {
        // 🛑 V21 的 (2) 上下文一致性守卫：若本事务已持有别的租户上下文，直接 RAISE。
        //    理由：set_config(..., is_local := true) 是事务级设置且不会在函数入口自动重置，
        //    静默覆盖会让"后续读到的是谁的协议"变得不可推理。
        String state = tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT_OTHER + "'");
            try {
                jdbc.queryForObject(
                        "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                                + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                                + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                        String.class,
                        TENANT, AGR_CTX, CUSTOMER, PLAN, 1,
                        "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                        "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                                + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                        RENDER_OK, hashOf(RENDER_OK), null, null, null, null);
                return "NO_ERROR";
            } catch (RuntimeException ex) {
                return deepestSqlState(ex);
            }
        });

        assertNotNull(state, "🛑 已持有别的租户上下文时竟然登记成功了 —— 上下文一致性守卫失效");
        assertTrue(state.contains("P0001") || state.contains("42501"),
                "🛑 必须被拒（P0001 显式守卫 或 42501 RLS），实际=" + state
                        + "。两者都 fail-closed，但只有 P0001 说明『一致性守卫』这一层在起作用");

        // 清场自证：不能因为上一段事务留下的上下文而影响后续（tx 已提交/回滚，SET LOCAL 随之失效）
        assertEquals(0, agreementRows(TENANT, AGR_CTX), "被拒后不得落库");
    }

    // ==================================================================
    // 判据 ⑪
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("⑪ 🛑 模板指针成对 + 存在性 + 版本相符（本域特有门禁：单边指针让“签的是哪一版”只答一半）")
    void the_doc_template_pointer_must_be_paired_and_resolvable() {
        // ---- ⑪a 单边指针（只给 id 不给 version）⇒ Java 侧构造器拒 ----
        assertThrows(BizException.class,
                () -> new AgreementRecord(TENANT, UUID.fromString(AGR_TPL),
                        UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                        refundClauseOk(), null, Instant.parse("2026-09-20T10:00:00Z"),
                        signerOk(), UUID.fromString(TPL), null,   // ← version 缺失
                        RENDER_OK, hashOf(RENDER_OK), null),
                "🛑 单边模板指针必须被拒 —— 它的用途是回答『这份协议签的是哪个模板的哪一版』，"
                        + "只给 id 不给 version，答案只对了一半");

        // ---- ⑪b 让"验证器有牙齿"：库层不含该判定时，下面 ⑪c 会以错误归因红 ----
        //   🛑 用探针确认库层【真的】有这条判定（读 prosrc）
        String prosrc = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'register_agreement'", String.class));
        assertNotNull(prosrc, "找不到 register_agreement 的 prosrc —— V21 未应用？");
        assertTrue(prosrc.contains("doc_template_id IS NULL")
                        || prosrc.replaceAll("\\s+", " ").contains("p_doc_template_id IS NULL"),
                "🛑 V21 的 register_agreement 里找不到模板成对判定 —— "
                        + "库层门禁缺失会让『绕过 Java 构造器直写 SQL』时单边指针可入库");

        // ---- ⑪c 模板不存在 ⇒ 库层拒（P0001）----
        String state = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_TPL, CUSTOMER, PLAN, 1,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, TPL_GHOST, 3, null);
        assertEquals("P0001", state,
                "🛑 模板不存在必须被拒（V21 的 (6b) 显式检查），实际=" + state);

        // ---- ⑪d 模板【存在】但版本不符（指针说 v9、模板是 v3）⇒ 拒 ----
        String stateVer = writeExpectingFailure(TENANT,
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                TENANT, AGR_TPL, CUSTOMER, PLAN, 1,
                "{\"refund\":\"x\"}", "2026-09-20T10:00:00Z",
                "{\"customer\":\"赵一\",\"meridian_therapist\":\"钱二\","
                        + "\"therapist\":\"孙三\",\"store_owner\":\"李四\"}",
                RENDER_OK, hashOf(RENDER_OK), null, TPL, 9, null);
        assertEquals("P0001", stateVer,
                "🛑 指针说 v9 而模板是 v3 时必须被拒 —— 这条断言能抓住"
                        + "『指针说 v3、实际指向 v5 那一行』这类静默错配，"
                        + "而那种错配恰好是『事后无法证明当时签的是哪一版』的成因。实际=" + stateVer);

        // ---- ⑪e 对照：成对 + 存在 + 版本相符必须成功（否则 ⑪c/⑪d 退化成"什么都拒绝"）----
        AgreementRecord ok = new AgreementRecord(TENANT,
                UUID.fromString("a1000000-0000-0000-0000-00000000004e"),
                UUID.fromString(CUSTOMER), UUID.fromString(PLAN), 1,
                refundClauseOk(), null, Instant.parse("2026-09-20T12:00:00Z"),
                signerOk(), UUID.fromString(TPL), 3, RENDER_OK, hashOf(RENDER_OK), "a1-tpl-ok");
        assertEquals(AgreementOutcome.CREATED, sign(ok, OPERATOR).outcome(),
                "🛑 成对 + 存在 + 版本相符的模板指针必须能成功");
    }

    // ==================================================================
    // 判据 ⑫
    // ==================================================================

    @Test
    @Order(12)
    @DisplayName("⑫ 🛑 本通路【不提供】任何改写/删除入口（静态）：无 UPDATE / DELETE agreement，也无撤销签署原语")
    void the_path_deliberately_provides_no_rewrite_or_delete_entry() throws IOException {
        for (String rel : List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/repository/AgreementLedger.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/service/AgreementService.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/domain/AgreementRecord.java")) {
            String src = stripComments(read(rel));
            assertFalse(src.matches("(?s).*\\bUPDATE\\s+agreement\\b.*"),
                    "🛑 " + rel + " 里出现了 UPDATE agreement —— "
                            + "本迁移【不提供任何改写通路】：任何『把协议置为已签/未签』的第二通路"
                            + "都【等于】开设一条绕过契约 H1 的旁路，"
                            + "而 H1 被禁止编码恰恰因为『没有验签就无法确认签署是真的』");
            assertFalse(src.matches("(?s).*\\bDELETE\\s+FROM\\s+agreement\\b.*"),
                    "🛑 " + rel + " 里出现了 DELETE FROM agreement —— "
                            + "协议是【举证材料】：替代路径是『重新出方案 → 重新签新的一份』，"
                            + "而不是抹掉旧的那份。删除会毁掉『当时签的是什么』这条证据链");
        }
        // 也不得提供"撤销签署"这类原语名
        String svc = stripComments(read(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/service/AgreementService.java"));
        for (String banned : List.of("revokeSign", "markSigned", "markUnsigned", "unSign")) {
            assertFalse(svc.contains(banned),
                    "🛑 AgreementService 里出现了 " + banned + " —— 同 ⑫ 的理由："
                            + "任何改写签署状态的通路都是绕过 H1 的旁路");
        }
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

    /** 断言审计条目存在且 action / target 正确（audit_log 无 RLS，不需上下文）。 */
    private static void assertAudit(String auditId, String action, String targetId, String payloadFragment) {
        assertNotNull(auditId, "回执必须带 auditId（append() 的返回值，不是调用方自编的）");
        assertFalse(auditId.isBlank(), "auditId 不得为空白");
        Map<String, Object> row = jdbc.query(
                // 🛑 必须显式 `?::uuid`：实测 `audit_log.id` 是 **uuid** 类型
                //    （见 information_schema 实测：id|uuid），而绑定参数是 String
                //    ⇒ 不转型会报 `操作符不存在: uuid = character varying`（本类首跑实测）。
                //    🛑 这个坑值得登记：`target_id` 是 varchar(128)，`id` 是 uuid ——
                //      同一张表里两个"看起来都是标识"的列类型不同，
                //      按 `target_id` 的直觉去写 `id` 就会踩它。
                "SELECT action, target_type, target_id, actor, payload FROM audit_log WHERE id = ?::uuid",
                rs -> rs.next() ? Map.of(
                        "action", rs.getString("action"),
                        "target_type", rs.getString("target_type"),
                        "target_id", rs.getString("target_id"),
                        "actor", rs.getString("actor"),
                        "payload", rs.getString("payload")) : null,
                auditId);
        assertNotNull(row,
                "🛑 回执里的 auditId 在库里查不到 —— 那说明 append() 传来的 id 是调用方自编的、"
                        + "而不是实现生成的（B-7 已付费记录过这个缺陷）");
        assertEquals(action, row.get("action"), "审计 action 必须正确");
        assertEquals(TARGET_TYPE, row.get("target_type"), "审计 target_type 必须正确");
        assertEquals(targetId, row.get("target_id"), "审计 target_id 必须正确");
        assertTrue(String.valueOf(row.get("payload")).contains(payloadFragment),
                "审计 payload 应含 " + payloadFragment + "，实际=" + row.get("payload"));
    }

    // ==================================================================
    // 四、静态工具
    // ==================================================================

    /**
     * 从迁移文本里取出某个 {@code text[]} 常量赋值语句之后的<b>字符串字面量集合</b>。
     *
     * <p>🛑 判据的适用范围 = 它的锚点范围（本仓纪律）。锚点是
     * {@code v_sign_keys text[] := ARRAY[ … ];} 这一段。
     * <p>🛑 锚点必须落在<b>定义式</b>上 —— V21 在数组定义之前的注释里也提到过
     * {@code v_sign_keys}，用 {@code indexOf} 会锚到那句注释。
     */
    private static Set<String> quotedKeySetAfter(String sql, String constName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(constName)
                        + "\\s+text\\[\\]\\s*:=\\s*ARRAY\\s*\\[").matcher(sql);
        assertTrue(m.find(),
                "迁移文本里找不到常量定义式 `" + constName + " text[] := ARRAY[` —— 锚点失效");
        int bracket = m.end() - 1;
        int end = sql.indexOf(']', bracket);
        assertTrue(end > bracket, "数组未闭合");
        String seg = sql.substring(bracket + 1, end);

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
            if (lit.matches("[a-z_][a-z0-9_]*")) {
                out.add(lit);
            }
            i = q2 + 1;
        }
        return out;
    }

    /** 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>（与归档测试同款状态机）。 */
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

    /** 剥离 SQL 注释（{@code --} 与块注释），保留美元引用块与字符串字面量。 */
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

    // ==================================================================
    // 五、底层工具
    // ==================================================================

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

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private static String read(String rel) throws IOException {
        return Files.readString(skeletonRoot().resolve(rel), StandardCharsets.UTF_8);
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
                // 🛑 agreement 引用 customer + plan + doc_template ⇒ 先删子再删父
                j.update("DELETE FROM agreement WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM plan WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM doc_template WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff WHERE tenant_id = ?::uuid", tid);
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
                assertTrue(j.queryForObject("SELECT count(*) FROM agreement", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有协议残留（FORCE RLS 会静默删 0 行）");
                assertTrue(j.queryForObject("SELECT count(*) FROM plan", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有方案残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM customer", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有客户残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下（用本次运行的增量）----
        assertTrue(serviceCalls > 0,
                "自证失败：serviceCalls 为 0，说明本类从未真正走过签署路径");

        int createdDelta = countAudit(ACTION_SIGNED, AGR_FULL) - baselineSignedFull;
        assertEquals(1, createdDelta,
                "判据 ① 的签署登记必须【恰好】留下 1 条 AGREEMENT_SIGNED 审计"
                        + "（action=" + ACTION_SIGNED + " target_id=" + AGR_FULL + "）。"
                        + "本次增量=" + createdDelta + "（基线=" + baselineSignedFull + "）");

        int replayedDelta = countAudit(ACTION_REPLAYED, AGR_IDEM) - baselineReplayedIdem;
        assertEquals(1, replayedDelta,
                "🛑 判据 ② 的重放必须【恰好】留下 1 条 AGREEMENT_SIGN_REPLAYED 审计 —— "
                        + "本域刻意让重放用【独立的 action】（重放不代表一次新的签署，"
                        + "而『签署合规率』是一个会被取数的合规指标，共用 action 会让计数虚高）。"
                        + "本次增量=" + replayedDelta + "（基线=" + baselineReplayedIdem + "）");

        int signedIdemDelta = countAudit(ACTION_SIGNED, AGR_IDEM) - baselineSignedIdem;
        assertEquals(1, signedIdemDelta,
                "🛑 AGR_IDEM 本次只被【首次】登记过一次 ⇒ AGREEMENT_SIGNED 本次增量应恰 1 条。"
                        + "若增量是 2，说明重放也被记成了 AGREEMENT_SIGNED —— "
                        + "那会让『签署合规率』这类取数指标把重放也算进去。"
                        + "本次增量=" + signedIdemDelta + "（基线=" + baselineSignedIdem + "）");
    }
}