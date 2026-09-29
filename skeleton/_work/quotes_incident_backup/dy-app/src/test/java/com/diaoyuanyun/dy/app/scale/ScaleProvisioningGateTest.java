package com.diaoyuanyun.dy.app.scale;

import com.diaoyuanyun.dy.app.scale.domain.ScaleOutcome;
import com.diaoyuanyun.dy.app.scale.domain.ScaleRecord;
import com.diaoyuanyun.dy.app.scale.repository.ScaleLedger;
import com.diaoyuanyun.dy.app.scale.service.ScaleService;
import com.diaoyuanyun.dy.app.scale.service.ScaleService.ScaleProvisioningResult;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-12 · 量表建档通路的真库真表端到端回归</b> ——
 * 它见证的是 {@code scale} 表从「零生产写入方」到「有写入方」这件事
 * <b>真的把契约 C2 端点救活了</b>，并同时见证 V19 相对 V18 形态的那两处<b>实质改动</b>。
 *
 * <h2>🛑🛑 判据设计零（本类与 {@code DeviceProvisioningGateTest} 最关键的分歧）：
 * 这条链的缺口表现【不是 23503】，而是 422</h2>
 * {@code device}（B-11）那条链上没有任何存在性预检 ⇒ 缺口表现为一条 PostgreSQL 的
 * {@code 23503}，验收口径是「这条 23503 不再发生」。
 * <p>{@code scale} 这条链<b>有</b>一道业务预检：
 * <pre>
 *   AssessmentService.submitBaseline 第 ③ 步（L154）：
 *       if (!ledger.scaleExists(tenantId, scaleId)) throw new BizException(BUSINESS_RULE_VIOLATED, ...)
 *   AssessmentLedger.scaleExists（L142）：
 *       SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid)
 * </pre>
 * ⇒ 缺口表现是 <b>{@code 422 / 5001}「scale_id 指向的量表不存在或不属当前租户」</b>，
 * 且那句文案<b>会把人引向错误方向</b>（读它的人会去怀疑 scale_id 传错 / 租户上下文不对，
 * 而真因是<b>库里压根没有任何量表</b>）。
 * <p>🛑 故本类的验收口径必须是<b>「那道预检能返回 true」</b>，
 * 而<b>不能</b>照抄 B-11 写成「23503 消失」——
 * 后者在这条链上会<b>恒真</b>（这条链根本不产生 23503），
 * 是一条<b>没有判别力的假绿</b>。
 * 可迁移的教训：<b>同一个根因在不同链上会表现成不同的错误种类，
 * 验收口径必须锚在该链真实的表现上。</b>
 *
 * <h2>🛑 判据设计一：先证明「缺口真的存在过」，再证明「它被修好了」，最后证明「断言对它有反应」</h2>
 * 判据 ① 用<b>同一个客户、同一支量表、三次 C2 提交</b>做对照：
 * <pre>
 *   建档【之前】提交 C2 ⇒ 422 + 5001（文案「量表不存在或不属当前租户」）
 *   建档【之后】同一请求 ⇒ 200 + migratable=true + scale_version 非空
 *   物理删掉 scale 行之后 ⇒ 422 【重新】出现
 * </pre>
 * 三段缺一不可。第一段证明缺口真实存在；第二段证明修复有效；第三段是本门禁的
 * <b>判别力自证</b> —— 若「422」这条断言在任何状态下都成立，那它就不是在测缺口。
 * <p>🛑 第二段还必须额外断言 {@code migratable=true} —— 因为本缺口有<b>两个面</b>：
 * 除了 C2 恒 422，还有 {@code scale_version} 恒 null ⇒ {@code migratable} 恒 false。
 * 只断言 200 会漏掉第二个面（一条"能提交但永远不可迁移"的 C2 仍然是坏的）。
 *
 * <h2>🛑🛑 判据设计二：V19 相对 V18 的实质改动之一 —— 版本冲突必须是【异常】</h2>
 * V5 L235 的设计注释逐字写着「§2.10: 版本递增·不可覆盖；UNIQUE(scale_id, scale_version)」，
 * 但实测 {@code scale_pkey} 是<b>单列</b> {@code scale_id}
 * ⇒ 同一 {@code scale_id} 只可能有一行 ⇒ {@code uq_scale_id_version}
 * <b>永远不可能被违反</b>（一条死索引），"版本递增"在库层无法表达。
 * <p>后果不是报错而是<b>静默的语义漂移</b>：调用方传一个新版本 v2 想升级，
 * 若函数照抄 V18 形态只判 ROW_COUNT，就会返回 {@code ALREADY_EXISTS}——
 * 调用方以为"我要的那一版已存在"，而库里那一行还是 v1，
 * 于是 {@code AssessmentService} 里 {@code ledger.scaleVersion()} 返回 v1，
 * 被写入的基线评估与量表版本<b>口径错配</b>。
 * <p>故判据 ④ 断言：传不同版本<b>必须 RAISE（P0001）</b>、
 * 消息里必须含「<b>不可覆盖</b>」、<b>库中版本不得被改动</b>、<b>行数仍为 1</b>。
 *
 * <h2>🛑🛑 判据设计三（靶心）：跨租户撞号必须是【异常】而不是一个返回值</h2>
 * 与 B-11 同型：{@code ON CONFLICT (scale_id)} 的推断目标是那个<b>全局主键</b>
 * （{@code scale_pkey}，实测单列）⇒ 当 {@code scale_id} 已被<b>别的租户</b>占用时，
 * 主键冲突照常发生（走 {@code DO NOTHING}，{@code ROW_COUNT = 0}），
 * 而本租户上下文里 {@code SELECT} 又<b>看不见</b>那一行（RLS 的 USING 挡住）。
 * ⇒ 若只按 {@code ROW_COUNT} 判定，就会对一个手里<b>一行都没有</b>的租户
 * 返回 {@code ALREADY_EXISTS}，调用方据此认为量表已就绪并继续提交 C2 ——
 * 而那一次 C2 仍会以「量表不存在或不属当前租户」被拒。
 * <p>故判据 ⑤ 断言三件事：<b>必须 RAISE（P0001）</b>、
 * <b>消息里必须写明「另一租户」</b>、<b>本租户里必须一行都没有</b>；
 * 并附两条对照：另一租户用<b>自己的</b> scale_id 必须成功、
 * 本租户重放同一支必须仍是 {@code ALREADY_EXISTS}。
 * <p>再加一条元层断言：{@link ScaleOutcome} <b>不得</b>出现
 * {@code OWNED_BY_OTHER_TENANT} / {@code VERSION_CONFLICT} 两个第三态 ——
 * 「用缺失表达不该继续」这条设计意图必须被代码守住。
 *
 * <h2>🛑 判据设计四：审计失败必须让建档回滚（一条易静默失效的不变量）</h2>
 * 与 B-11 逐字同款：用一个<b>真实的失败模式</b>
 * （超长 {@code operator} 撞 {@code audit_log.actor VARCHAR(128)} ⇒ 22001）
 * 去撞这条不变量，断言 {@code scale} 行确实被回滚。
 * <p>🛑 为什么用「超长 actor」而不是 mock 掉 {@code AuditLogService}：
 * 替换 bean 会改变装配形态，于是被验的就不再是生产的那套装配。
 *
 * <h2>🛑 判据设计五：测试【绝不】代建任何 {@code scale} 行</h2>
 * 这正是 B-12 这个缺口的成因本身：在本次收口之前，
 * 生产代码里零 {@code INSERT INTO scale}，而 {@code AssessmentDomainCEndpointsE2ETest}
 * 的夹具里<b>有一条</b>（L157）—— 于是「量表从哪来」这个问题
 * 在测试里永远有一个答案、在生产里没有。
 * <p>⇒ 本类的每一支量表都由 {@link ScaleService} 建；
 * 判据 ① 与判据 ⑤ 的失败侧、判据 ⑥ 的反向自证才以裸 SQL 形态出现
 * （那几条要证的正是「绕过函数在库层会怎样」）。
 *
 * <h2>零污染 —— 以及一处刻意例外（审计行不清理）</h2>
 * 数据全部用 {@code c1200000-} 前缀自建（与 {@code d1100000-}（B-11）/
 * {@code b1000000-} / {@code b0700000-} / {@code e2e00000-} 均不重叠），
 * {@code @AfterAll} 在租户上下文内按「子 → 父」外键序清理
 * （{@code baseline_assessment} → {@code scale} → {@code customer} →
 * {@code staff} → {@code store} → {@code region} → {@code tenant}），并带<b>清理自证</b>。
 * <p>🛑 审计条目刻意不删：{@code audit_log} 是<b>全局单链</b>，
 * 删中间行会为它的后继留下永久的 {@code prev_hash} 断口。改为断言「证据确实留下了」。
 *
 * <h2>执行顺序</h2>
 * 判据 ① 会<b>主动破坏</b>自己那支量表（判别力自证的最后一步是物理删掉它），
 * 故它必须自成一个闭环、不与他人共享载体。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-12 · 量表建档通路真库回归（C2 缺口对照 / 版本冲突 / 跨租户撞号 / 同事务审计）")
class ScaleProvisioningGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code c1200000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "c1200000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ⑤ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "c1200000-0000-0000-0000-0000000000f1";

    // —— 客户 / 员工（C2 的外键目标，必须真存在）——
    private static final String CUSTOMER = "c1200000-0000-0000-0000-000000000032";
    private static final String STAFF = "c1200000-0000-0000-0000-000000000031";

    // —— 量表 ——
    /** 判据 ①：C2 缺口对照用的那一支（本判据结束时它会被物理删除）。 */
    private static final String SCALE_GATE = "c1200000-0000-0000-0000-000000000041";
    /** 判据 ③ / ⑦：两态三态与审计留痕的载体。 */
    private static final String SCALE_STATE = "c1200000-0000-0000-0000-000000000042";
    /** 判据 ②：审计失败注入用。 */
    private static final String SCALE_TX = "c1200000-0000-0000-0000-000000000043";
    /** 判据 ④：版本冲突 —— 先以 v1 建档，再以 v2 重放。 */
    private static final String SCALE_VER = "c1200000-0000-0000-0000-000000000044";
    /** 判据 ⑤：跨租户撞号 —— 租户 A 先占用这一支。 */
    private static final String SCALE_SHARED = "c1200000-0000-0000-0000-000000000046";
    /** 判据 ⑤ 对照：租户 B 用自己的 scale_id 必须能建。 */
    private static final String SCALE_OTHER = "c1200000-0000-0000-0000-000000000047";
    /** 判据 ⑧：上下文一致性守卫用。 */
    private static final String SCALE_CTX = "c1200000-0000-0000-0000-000000000048";
    /** 一个<b>从不</b>被建档的 id —— 只作为「不存在」的负样本出现。 */
    private static final String SCALE_GHOST = "c1200000-0000-0000-0000-00000000004f";

    /** 新建量表的基准版本（判据 ④ 会在这一支上额外试 v2）。 */
    private static final String VER_V1 = "v1";
    private static final String VER_V2 = "v2";

    /** 审计动作名与目标类型 —— 与 {@code ScaleService} 的常量逐字一致（那里是包私有）。 */
    private static final String ACTION_REGISTERED = "SCALE_REGISTERED";
    private static final String ACTION_DEPRECATED = "SCALE_DEPRECATED";
    private static final String TARGET_TYPE = "scale";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-b12-witness";

    /** 判据 ② 的注入载荷：长度超 {@code audit_log.actor VARCHAR(128)}。 */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /**
     * 判据 ⑦ 的反向断言靶子 —— 一个<b>唯属于本用例</b>的维度集标记。
     *
     * <p>🛑 为什么要一个可识别的值：{@code ScaleService.registerPayload()} 的注释裁定
     * 「{@code scale_type} / {@code scale_version} 可以进 payload，
     * {@code dimension_set_json} <b>不可以</b>」（前者是分类值，后者是评分口径 = 经营信息）。
     * 要证明这条边界真的生效，靶子必须是唯属本用例的字符串 ——
     * 用一个真实取值（如 {@code ["A1","A2"]}）会让断言在 payload 恰好不含它时
     * 也无法排除「实现把它换成别的了」。
     */
    private static final String DIM_WITNESS = "SCALE-B12-WITNESS-DIM";

    /** 维度集 JSON（判据 ⑦ 会在里面塞靶子，其余判据用最简形态）。 */
    private static final String DIM_JSON_PLAIN = "{\"dims\":[\"A1\"]}";
    private static final String DIM_JSON_WITNESS = "{\"dims\":[\"" + DIM_WITNESS + "\"]}";

    /** 真正调用过建档/废弃的次数（供 {@code @AfterAll} 断言「审计证据确实留下了」，而不删它）。 */
    private static int serviceCalls;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 application.yml 的占位密钥一致（本地 dev 默认值）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    // ==================================================================
    // 二、装配
    // ==================================================================

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource springDataSource;

    @Autowired
    ScaleService service;

    @Autowired
    ScaleLedger ledger;

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
     * 种两个租户 + 区域/门店/员工/客户。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code scale} 行 —— 那是本类全部判据的载体，
     * 必须由 {@link ScaleService} 建（见类注释「判据设计五」）。
     * <p>🛑 为什么必须种 {@code staff}：{@code baseline_assessment.measure_operator}
     * 是复合外键指向 {@code staff (tenant_id, staff_id)}，而 C2 的第 ④ 步会预检它
     * （{@code ledger.staffExists}）—— 不种它，判据 ① 的 C2 会以
     * 「measure_operator 指向的员工不存在或已离职」失败，
     * 而那与「量表缺口」完全无关 —— 那会是一次<b>归因错误的红</b>。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'B12租户-量表建档') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'B12对照租户') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);

        inTenant(TENANT, () -> {
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, role) "
                            + "VALUES (?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    STAFF, TENANT);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B12客户', 'CREATED') ON CONFLICT DO NOTHING",
                    CUSTOMER, TENANT);
            return null;
        });
    }

    /**
     * 在指定租户上下文里执行（短事务 + {@code SET LOCAL}）—— 与各 {@code *Ledger.inTenant} 同款形态。
     *
     * <p>🛑 {@code SET LOCAL} 只在事务内有效，故「设上下文 + 执行 SQL」必须收敛成一个事务；
     * 无上下文时读是<b>静默 0 行</b>、写是<b>抛 42501</b>（这两个方向很容易记反）。
     */
    private static <T> T inTenant(String tenantId, Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    /**
     * 在租户上下文里执行一条<b>必须失败</b>的写语句，返回最深层的 SQLSTATE。
     *
     * <p>🛑 返回 SQLSTATE 而不是布尔：23503（外键）、42501（RLS）、
     * P0001（显式 RAISE）、22001（截断）都会让「失败」成立，
     * 只断言「失败」会让「我忘了设上下文」这种实现也通过。
     *
     * @return 失败时的 SQLSTATE；<b>若没失败则返回 {@code null}</b> —— 调用方必须对此断言
     */
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

    /** 与 {@link #writeExpectingFailure} 同款，但额外返回整条 cause 链文本（供「哪个约束」这类断言）。 */
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

    /** 该量表在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int scaleRows(String tenantId, String scaleId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM scale WHERE scale_id = ?::uuid", Integer.class, scaleId));
        return n == null ? 0 : n;
    }

    /**
     * 该量表的 {@code [status, updated_at, scale_version, name]}；不存在则 {@code null}。
     *
     * <p>🛑 {@code updated_at} 必须被读出来：判据 ⑥ 要断言「重复废弃不推后 updated_at」——
     * 那是"废弃是一次状态迁移、不是一次写入"这条性质的<b>唯一</b>可观测面。
     */
    private static String[] scaleState(String tenantId, String scaleId) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT status, coalesce(updated_at::text, ''), scale_version, name "
                            + "  FROM scale WHERE scale_id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)},
                    scaleId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 该量表在本租户内的 {@code scale_version}（无则 {@code null}）。 */
    private static String scaleVersion(String tenantId, String scaleId) {
        return inTenant(tenantId, () -> {
            List<String> v = jdbc.query("SELECT scale_version FROM scale WHERE scale_id = ?::uuid",
                    (rs, i) -> rs.getString(1), scaleId);
            return v.isEmpty() ? null : v.get(0);
        });
    }

    private ScaleProvisioningResult register(String tenantId, String scaleId, String version,
                                             String dimJson, String operator) {
        serviceCalls++;
        return service.register(new ScaleRecord(tenantId, UUID.fromString(scaleId),
                "primary", version, "B12量表", dimJson), operator);
    }

    /** 建档（合法操作者、v1、最简维度集）—— 最常用的一种调用。 */
    private ScaleProvisioningResult registerOk(String tenantId, String scaleId) {
        return register(tenantId, scaleId, VER_V1, DIM_JSON_PLAIN, OPERATOR);
    }

    private ScaleProvisioningResult deprecate(String tenantId, String scaleId) {
        serviceCalls++;
        return service.deprecate(tenantId, UUID.fromString(scaleId), OPERATOR);
    }

    // ==================================================================
    // 三、异常链工具（本仓既定形态：判据必须给出【理由】）
    // ==================================================================

    /**
     * 取异常链上<b>最深</b>那个 {@link java.sql.SQLException} 的 SQLSTATE。
     *
     * <p>🛑 用 SQLSTATE 而非消息文本作主判据：它是 PG 的错误码，
     * <b>与数据库语言环境无关</b>。本仓的 PG 是中文环境，
     * 任何写死英文消息片段的断言在这里都会假红。
     * <p>🛑 本域尤其需要它：P0001（函数 RAISE）与 23505（唯一键冲突）
     * 都能让「跨租户撞号」这条断言"失败"，但两者是<b>完全不同</b>的结论 ——
     * 前者说明 V19 的判定链生效，后者说明它<b>没生效</b>（撞在了库层约束上）。
     */
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

    /**
     * 把异常链上每一层的类型、<b>SQLSTATE</b> 与消息拼成一行（顶层 → 最深），供失败信息可读。
     *
     * <p>🛑 <b>必须把 SQLSTATE 拼进来</b>（B-11 实测抓到的一处可观测面不足）：
     * 有些判据要<b>同时</b>断言「SQLSTATE 是什么」与「消息里出现了哪句话」——
     * 前者只有 {@code SQLException.getSQLState()} 能给出，而后者只存在于
     * {@code getMessage()} 里。只拼消息，在中文 PG 环境下会让一条<b>完全正确</b>的失败
     * 被判成红 —— 那是「工具看不见它要看的东西」，不是「产品有问题」。
     * 修工具的可观测面，而不是放宽判据的期望值。
     */
    private static String fullCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 12) {
            if (depth > 0) {
                sb.append(" ← ");
            }
            sb.append(c.getClass().getSimpleName());
            if (c instanceof java.sql.SQLException se && se.getSQLState() != null) {
                sb.append("[SQLSTATE=").append(se.getSQLState()).append(']');
            }
            sb.append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    // ==================================================================
    // 四、HTTP 侧（判据 ① 的后果断言 —— 走真实链路）
    // ==================================================================

    private ResponseEntity<String> post(String path, String body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static String token(String role) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + STAFF + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
        return sign(header, payload);
    }

    private static String sign(String header, String payload) {
        String input = b64(header) + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    /**
     * 一条合法的 C2 请求体（7 维分各 8 → 总分 56；带题组 ID 使四要素齐备）。
     *
     * <p>🛑 这里<b>必须</b>带 {@code item_group_id}：{@code migratable} 的推导是
     * {@code itemGroupId != null && scaleVersion != null}，
     * 而本判据要证的第二个面恰是「{@code scaleVersion} 由 null 变为非 null」——
     * 若不带题组 ID，{@code migratable} 会因第一个条件恒 false，
     * 于是那条断言<b>无法区分</b>「量表版本有了」与「题组 ID 没传」。
     */
    private static String c2Body(String scaleId) {
        return "{\"scale_id\":\"" + scaleId + "\","
                + "\"item_group_id\":\"" + UUID.randomUUID() + "\","
                + "\"age_group_locked\":\"男16-32\","
                + "\"dimension_scores\":[8,8,8,8,8,8,8],"
                + "\"total_score\":56,"
                + "\"measure_operator\":\"" + STAFF + "\"}";
    }

    private ResponseEntity<String> submitBaseline(String scaleId) {
        return post("/api/v1/customers/" + CUSTOMER + "/assessments/baseline",
                c2Body(scaleId), token("meridian"));
    }

    // ==================================================================
    // 判据 ①（判别力三段对照）
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 C2 缺口对照：量表未建档时提交必 422/5001（文案会误导人），建档后必 200 + migratable=true，物理删行后必【重新】422")
    void the_missing_scale_writer_was_the_real_cause_and_provisioning_fixes_it() {
        // ---- ①a 前置：这支量表在库里【不存在】----
        assertEquals(0, scaleRows(TENANT, SCALE_GATE),
                "前置失败：SCALE_GATE 已在库里 —— 那么下面『建档前必失败』就无法与"
                        + "『它本来就在』区分，本例全部门禁意义丢失");

        // ---- ①b 根因断言（库层）：C2 的那道业务预检此刻必须为 false ----
        //   🛑 这一条是本域全部判据的锚：缺口的"根因面"就是它恒为 false。
        //      没有它，下面那个 422 就有了别的解释（比如 measure_operator 不存在）。
        assertFalse(ledger.canReferenceBaseline(TENANT, UUID.fromString(SCALE_GATE)),
                "🛑 前置失败：scale 零行的现实下 canReferenceBaseline 必须为 false。"
                        + "若它为 true，说明库里已有这一行（而不是本判据要证的『零写入方』）");

        // ---- ①c 后果断言（走真实 HTTP 链路）：建档【之前】提交 C2 ----
        ResponseEntity<String> before = submitBaseline(SCALE_GATE);
        assertEquals(422, before.getStatusCodeValue(),
                "🛑 C2 在『量表未建档』时必须 422 —— 这正是本缺口的对外表现。"
                        + "🛑 注意它【不是】23503：这条链上有一道业务预检"
                        + "（AssessmentService 第 ③ 步的 scaleExists）把缺口翻译成了业务错误。"
                        + "实际=" + before.getStatusCodeValue() + " body=" + before.getBody());
        assertEquals(5001, parse(before.getBody()).at("/code").asInt(),
                "业务码必须是 5001（BUSINESS_RULE_VIOLATED），不是 3001/1001: " + before.getBody());
        assertTrue(before.getBody() != null && before.getBody().contains("量表不存在"),
                "🛑 必须确认那句【会把人引向错误方向】的文案真的存在 —— "
                        + "读它的人会去怀疑『scale_id 传错 / 租户上下文不对』，"
                        + "而真因是库里压根没有任何量表。这条断言是本迁移第二层理由的证据。"
                        + "body=" + before.getBody());

        // ---- ①d 修复：由【被测代码】建这支量表（测试绝不代建）----
        ScaleProvisioningResult created = registerOk(TENANT, SCALE_GATE);
        assertEquals(ScaleOutcome.CREATED, created.outcome(),
                "首次建档必须是 CREATED（返回 ALREADY_EXISTS 说明前置未清干净）");
        List<String[]> auditRows = jdbc.query(
                "SELECT action, target_type, target_id FROM audit_log WHERE id = ?::uuid",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                created.auditId());
        assertEquals(1, auditRows.size(),
                "回执的 auditId 必须真的对应库中一条审计（实际条数=" + auditRows.size()
                        + "）—— 否则回执无法用于对账");
        assertEquals(SCALE_GATE, auditRows.get(0)[2], "审计 target_id 必须是本次建档的量表 id");

        // ---- ①e 🛑 建档【之后】：同一请求必须成功 ----
        ResponseEntity<String> after = submitBaseline(SCALE_GATE);
        assertEquals(200, after.getStatusCodeValue(),
                "🛑 C2 在量表建档之后必须回到 200 —— 这是『scale 表零写入方』"
                        + "被修好的最终证据。实际=" + after.getStatusCodeValue()
                        + " body=" + after.getBody());
        JsonNode data = parse(after.getBody()).at("/data");

        //   🛑🛑 第二个面：migratable 必须由恒 false 变为 true
        //   本缺口有两个面，只断言 200 会漏掉第二个 —— 一条"能提交但永远不可迁移"
        //   的 C2 仍然是坏的（V5 L355 是 NOT NULL，而 migratable 是四要素齐备度落库判据）。
        assertTrue(data.at("/migratable").asBoolean(),
                "🛑 migratable 必须为 true —— 本缺口的第二个面是"
                        + "『scale_version 恒 null ⇒ migratable 恒 false』。"
                        + "本例已传 item_group_id，故 migratable=true 反证 scale_version 非空: " + data);
        assertNotNull(scaleVersion(TENANT, SCALE_GATE),
                "🛑 库中 scale_version 必须非空 —— 它是 migratable 四要素之一，"
                        + "也是 C2 写路径第 ③ 步真正要读的那个值");

        // ---- ①f 🛑 判别力自证（两步，缺一不可）----
        //   🛑 第一步：有基线评估引用它时，scale 行【删不掉】。
        //      这不是顺带一提，而是「废弃为什么必须是状态迁移」这条设计的库层理由本身。
        String deleteGuard = writeExpectingFailure(TENANT,
                "DELETE FROM scale WHERE scale_id = ?::uuid", SCALE_GATE);
        assertEquals("23503", deleteGuard,
                "🛑 在 baseline_assessment 仍引用该量表时，DELETE scale 必须被 23503 拒 —— "
                        + "若它成功了，说明 baseline_assessment_scale_id_fkey 的删除行为被改成了 "
                        + "CASCADE/SET NULL，那会让历史基线评估【静默失去可追溯的打分依据】。"
                        + "实际=" + deleteGuard);

        //   🛑 第二步：把引用清掉、再把 scale 行物理删掉 ⇒ 422 必须【重新】出现。
        //      这是本门禁真正的判别力所在：若「422」那条断言在任何状态下都成立，
        //      它就不是在测缺口，而是一条恒真断言（装饰而非门禁）。
        inTenant(TENANT, () -> {
            jdbc.update("DELETE FROM baseline_assessment WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM scale WHERE scale_id = ?::uuid", SCALE_GATE);
            return null;
        });
        assertEquals(0, scaleRows(TENANT, SCALE_GATE),
                "判别力自证的前置：物理删行必须真的生效（FORCE RLS 下无上下文的 DELETE 会静默删 0 行）");

        ResponseEntity<String> again = submitBaseline(SCALE_GATE);
        assertEquals(422, again.getStatusCodeValue(),
                "🛑 物理删掉 scale 行之后，C2 必须【重新】返回 422 —— "
                        + "这一步是判据 ① 全部门禁的判别力自证。"
                        + "若这里仍然是 200，说明上面那个 200 与『量表被建出来了』无关。"
                        + "实际=" + again.getStatusCodeValue() + " body=" + again.getBody());
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 🛑 审计失败必须让建档回滚：超长 operator 撞 audit_log.actor(128) ⇒ 22001，且 scale 行不得留下")
    void audit_failure_rolls_back_registration() {
        assertEquals(0, scaleRows(TENANT, SCALE_TX),
                "前置失败：SCALE_TX 已在库里 —— 本判据要证『回滚后没有行』，"
                        + "而它本来就有行的话这条断言恒真");

        // 🛑 用【真实】的失败模式去撞不变量，而不是人为抛异常 / mock 掉服务：
        //    ScaleService.requireOperator() 只挡 null 与空白（那是业务校验），
        //    不挡长度 —— 长度是列宽问题，应由库层报 22001。
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> register(TENANT, SCALE_TX, VER_V1, DIM_JSON_PLAIN, TOO_LONG_OPERATOR),
                "🛑 超长 operator 必须让建档整体失败 —— 若它成功了，说明审计写入被静默降级，"
                        + "而『建档与审计同事务』这条不变量已经失效");

        assertEquals("22001", deepestSqlState(ex),
                "🛑 失败理由必须是 22001(string_data_right_truncation) —— "
                        + "即 audit_log.actor 的列宽拒绝。异常链=" + fullCauseMessages(ex));

        // 🛑 关键：建档行必须被回滚
        assertEquals(0, scaleRows(TENANT, SCALE_TX),
                "🛑 审计失败之后 scale 行必须被回滚 —— 这正是『建档与审计同事务』这条不变量的断言。"
                        + "它极易静默失效：若有人把 ScaleLedger.register() 的传播行为改成 "
                        + "REQUIRES_NEW，建档会独立提交，而本断言会红。"
                        + "（没有任何静态检查会发现这件事）");

        // 对照：合法操作者必须成功 —— 防「拒绝一切」的实现蒙混过关
        ScaleProvisioningResult ok = registerOk(TENANT, SCALE_TX);
        assertEquals(ScaleOutcome.CREATED, ok.outcome(),
                "对照失败：合法操作者必须能建档（否则上面那条回滚断言无法与"
                        + "『建档功能整个坏了』区分）");
        assertEquals(1, scaleRows(TENANT, SCALE_TX), "对照：建档行必须落下");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 两态 + 三态：CREATED → ALREADY_EXISTS → DEPRECATED → ALREADY_DEPRECATED → NOT_FOUND")
    void two_states_and_three_states() {
        // ---- register：两态 ----
        assertEquals(ScaleOutcome.CREATED, registerOk(TENANT, SCALE_STATE).outcome(),
                "首次必须 CREATED");
        assertEquals("active", scaleState(TENANT, SCALE_STATE)[0], "新建量表必须是 active");

        assertEquals(ScaleOutcome.ALREADY_EXISTS, registerOk(TENANT, SCALE_STATE).outcome(),
                "同版本重放必须 ALREADY_EXISTS（幂等）");
        assertEquals(1, scaleRows(TENANT, SCALE_STATE),
                "🛑 重放之后必须仍然只有 1 行 —— 幂等保证的是『不重复建』");

        // ---- deprecate：三态 ----
        String beforeDep = scaleState(TENANT, SCALE_STATE)[1];
        assertNotNull(beforeDep);

        assertEquals(ScaleOutcome.DEPRECATED, deprecate(TENANT, SCALE_STATE).outcome(),
                "首次废弃必须 DEPRECATED");
        assertEquals("deprecated", scaleState(TENANT, SCALE_STATE)[0],
                "🛑 废弃必须真的改库（status → deprecated）—— "
                        + "『返回 DEPRECATED』这条断言单独会绿在一个只返回不写库的实现上");

        String afterDep = scaleState(TENANT, SCALE_STATE)[1];
        assertNotNull(afterDep);

        assertEquals(ScaleOutcome.ALREADY_DEPRECATED, deprecate(TENANT, SCALE_STATE).outcome(),
                "重复废弃必须 ALREADY_DEPRECATED（幂等）");
        assertEquals(afterDep, scaleState(TENANT, SCALE_STATE)[1],
                "🛑 重复废弃【不得】把 updated_at 推后 —— 那是『废弃是一次状态迁移、不是一次写入』"
                        + "这条性质的唯一可观测面。库层靠 UPDATE 的 `status <> 'deprecated'` 谓词保证，"
                        + "不能依赖一次可能过期的读");

        // ---- deprecate：第三态 ----
        assertEquals(ScaleOutcome.NOT_FOUND, deprecate(TENANT, SCALE_GHOST).outcome(),
                "🛑 查不到必须返回 NOT_FOUND 而【不是】抛异常 —— "
                        + "FORCE RLS 下它与『存在但当前上下文看不见』同形，"
                        + "把它当异常会让『租户上下文设错了』这个本来可查的问题变成一个 500");
    }

    // ==================================================================
    // 判据 ④（V19 核心机制三）
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 🛑🛑 版本冲突必 RAISE：同一 scale_id 传不同版本 ⇒ P0001 + 消息含「不可覆盖」+ 库中版本【不得】被改")
    void version_conflict_must_raise() {
        assertEquals(ScaleOutcome.CREATED,
                register(TENANT, SCALE_VER, VER_V1, DIM_JSON_PLAIN, OPERATOR).outcome(),
                "前置：先以 v1 建档");
        assertEquals(VER_V1, scaleVersion(TENANT, SCALE_VER));

        // 🛑 传【不同】版本：
        String chain = rawRegisterChain(TENANT, SCALE_VER, VER_V2);
        assertNotNull(chain,
                "🛑 传不同版本竟然【成功】了 —— 那说明 V19 的版本比对被删掉了，"
                        + "而后果是『返回值在撒谎』：调用方以为 v2 已存在，"
                        + "而库里那一行还是 v1 ⇒ ledger.scaleVersion() 返回旧值 ⇒ "
                        + "被写入的基线评估与量表版本口径错配。异常链=" + chain);
        assertTrue(chain.contains("P0001"),
                "🛑 拒绝理由必须是 P0001（显式 RAISE），而不是 23505（唯一键冲突）—— "
                        + "23505 说明判定链【没生效】、撞在了库层约束上，"
                        + "那样调用方拿不到那条可读的『不可覆盖』说明。异常链=" + chain);
        assertTrue(chain.contains("不可覆盖"),
                "🛑 消息里必须写明『不可覆盖』—— 它是 V5 L235 那条设计注释的唯一书面来源，"
                        + "函数去改它等于把口径写反。异常链=" + chain);
        assertTrue(chain.contains(VER_V1) && chain.contains(VER_V2),
                "🛑 消息里必须同时给出【库中版本】与【本次传入版本】—— "
                        + "没有这两个值，运维无法在不知道 schema 的情况下判断该新建 id 还是废弃旧版。"
                        + "异常链=" + chain);

        // 🛑 库中状态【不得】被改动
        assertEquals(VER_V1, scaleVersion(TENANT, SCALE_VER),
                "🛑 版本冲突之后库中版本必须仍是 v1 —— 覆盖会静默违反 DDL 自己的注释");
        assertEquals(1, scaleRows(TENANT, SCALE_VER),
                "🛑 行数必须仍是 1（不允许『顺手插一个新版本行』—— 那在单列主键下也不可能，"
                        + "但断言它能守住未来有人改主键形态时不静默通过）");

        // ---- 对照：同一版本重放仍必须 ALREADY_EXISTS ----
        assertEquals(ScaleOutcome.ALREADY_EXISTS,
                register(TENANT, SCALE_VER, VER_V1, DIM_JSON_PLAIN, OPERATOR).outcome(),
                "对照失败：同版本重放必须是 ALREADY_EXISTS 而不是异常 —— "
                        + "否则『版本冲突』这条判据无法与『函数拒绝一切重放』区分");
    }

    /** 直接经 {@link ScaleLedger} 发起一次建档并返回异常链（判据 ④ / ⑤ 用它取 RAISE 文本）。 */
    private static String rawRegisterChain(String tenantId, String scaleId, String version) {
        try {
            tx.execute(status -> {
                jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
                return jdbc.queryForObject("SELECT register_scale(?::uuid, ?::uuid, ?, ?, ?, ?::jsonb)",
                        String.class, tenantId, scaleId, "primary", version, "B12量表",
                        DIM_JSON_PLAIN);
            });
            return null;
        } catch (RuntimeException ex) {
            return fullCauseMessages(ex);
        }
    }

    // ==================================================================
    // 判据 ⑤（靶心）
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 🛑🛑 跨租户撞号必 RAISE（不是 ALREADY_EXISTS）：P0001 + 消息含「另一租户」+ 本租户零行 + 2×2 对称自证")
    void cross_tenant_id_collision_must_raise() {
        // ---- ⑤a 租户 A 先占用这一支 ----
        assertEquals(ScaleOutcome.CREATED, registerOk(TENANT, SCALE_SHARED).outcome(),
                "前置：租户 A 先建档占用 SCALE_SHARED");

        // ---- ⑤b 租户 B 用【同一个 scale_id、同一个版本】建档 ----
        //   🛑 版本必须传【与租户 A 相同】的 v1，否则会先命中判据 ④ 的版本分支
        //      —— 那会是一次归因错误的红（看上去像「跨租户判定坏了」，实际是「版本不同」）。
        String chain = rawRegisterChain(TENANT_OTHER, SCALE_SHARED, VER_V1);
        assertNotNull(chain,
                "🛑🛑 租户 B 用【租户 A 已占用的 scale_id】建档竟然成功了（或返回了正常值）—— "
                        + "这是本判据的靶心。若照抄一个只按 ROW_COUNT 判定的实现，"
                        + "它会对一个手里一行都没有的租户返回 ALREADY_EXISTS，"
                        + "调用方据此认为量表已就绪并继续提交 C2，"
                        + "而那一次 C2 仍会以『量表不存在或不属当前租户』被拒 —— 即返回值在撒谎。"
                        + "异常链=" + chain);
        assertTrue(chain.contains("P0001"),
                "🛑 拒绝理由必须是 P0001（V19 显式 RAISE）—— 而不是 23505（唯一键冲突）："
                        + "23505 说明判定链没生效、撞在了库层主键上，调用方拿不到可读原因。"
                        + "异常链=" + chain);
        assertTrue(chain.contains("另一租户"),
                "🛑 消息里必须写明『另一租户』—— 它是运维判断『scale_id 传错了还是量表被错误分发』"
                        + "的唯一线索。异常链=" + chain);
        assertEquals(0, scaleRows(TENANT_OTHER, SCALE_SHARED),
                "🛑 租户 B 名下必须一行都没有 —— 若有一行，说明这次『拒绝』其实是『建成功了』");

        // ---- ⑤c 对照：租户 B 用自己的 scale_id 必须能建 ----
        //   🛑 必须先做这一步，再跑 ⑤d 的矩阵 —— 理由见 ⑤d 的说明。
        assertEquals(ScaleOutcome.CREATED, registerOk(TENANT_OTHER, SCALE_OTHER).outcome(),
                "对照失败：租户 B 用自己的 scale_id 必须能建档 —— "
                        + "否则上面那条 RAISE 无法与『函数拒绝一切跨租户调用』区分");

        // ---- ⑤d 🛑🛑 2×2 对称自证矩阵 ----
        //   🛑 只断言反对角线的一半（跨租户为 false）是不够的：
        //      一个『恒返回 false』的实现也能过。必须同时钉住对角线。
        //      这条纪律来自 B-11 判据 ⑤f 的一次实测教训 —— 当时是我自己传错了参数，
        //      而错误的表现恰好是『一条看似合理的断言』。
        //   🛑🛑 本矩阵必须整体放在 ⑤c（租户 B 建档）【之后】执行：
        //      若放在之前，那条『租户 A 看不见租户 B 的量表』会因【它压根不存在】而成立 ——
        //      一条恒真的断言。四种组合必须在【四行都真实存在】的同一时刻被断言，
        //      矩阵才有判别力。（本仓纪律：判据的适用范围 = 它的锚点范围。）
        assertTrue(service.canBeUsedForBaseline(TENANT, UUID.fromString(SCALE_SHARED)),
                "🛑 对角线（租户 A 看自己的量表）必须为 true —— 否则读法退化成恒 false");
        assertFalse(service.canBeUsedForBaseline(TENANT_OTHER, UUID.fromString(SCALE_SHARED)),
                "🛑 反对角线：租户 B 上下文里看不见租户 A 的量表");
        assertTrue(service.canBeUsedForBaseline(TENANT_OTHER, UUID.fromString(SCALE_OTHER)),
                "🛑 对角线（租户 B 看自己的量表）必须为 true —— 本条守住上面那条不因"
                        + "『读法整个坏掉』而恒真");
        assertFalse(service.canBeUsedForBaseline(TENANT, UUID.fromString(SCALE_OTHER)),
                "🛑 反向自证：租户 A 里也【看不见租户 B 的】量表 —— "
                        + "只断言一条反对角线，一个『恒返回 false』的实现也能过");

        // ---- ⑤f 元层断言：枚举里【不得】出现第三态 ----
        List<String> names = new ArrayList<>();
        for (ScaleOutcome o : ScaleOutcome.values()) {
            names.add(o.name());
        }
        assertFalse(names.contains("OWNED_BY_OTHER_TENANT"),
                "🛑 ScaleOutcome 不得出现 OWNED_BY_OTHER_TENANT —— "
                        + "『用缺失表达不该继续』这条设计意图必须被代码守住，而不只写在注释里。"
                        + "一态一旦被登记，就等于允许调用方把它当成『可以继续流程的结果』: " + names);
        assertFalse(names.contains("VERSION_CONFLICT"),
                "🛑 同样：版本冲突也不得被登记成一态（判据 ④）。实际枚举=" + names);
        assertEquals(5, names.size(),
                "🛑 枚举必须恰为 5 态（register 2 + deprecate 3）—— "
                        + "数量变化意味着有人新增/删除了口径，必须在此处显式登记: " + names);
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ 废弃行仍占主键：废弃后同版本重放建档仍 ALREADY_EXISTS；且废弃【不】删行")
    void deprecated_row_still_occupies_primary_key() {
        // 🛑 前置：SCALE_TX 在判据 ② 的【成功对照那一次】已被建档 ⇒ 此处必然命中幂等分支。
        //    写成"允许两种态"而不是写死一种，是因为这条前置不是本判据要证的东西 ——
        //    它只负责把载体准备好，把断言写死会引入一条与判据无关的红。
        ScaleProvisioningResult pre = registerOk(TENANT, SCALE_TX);
        assertTrue(pre.outcome() == ScaleOutcome.CREATED || pre.outcome() == ScaleOutcome.ALREADY_EXISTS,
                "前置失败：SCALE_TX 应已存在（判据 ② 已建过），实际=" + pre.outcome());

        assertEquals(ScaleOutcome.DEPRECATED, deprecate(TENANT, SCALE_TX).outcome(),
                "前置：首次废弃 SCALE_TX 必须是 DEPRECATED");

        // 🛑 核心：废弃之后，同版本重放建档仍然必须是 ALREADY_EXISTS（行还在，占着主键）
        assertEquals(ScaleOutcome.ALREADY_EXISTS,
                register(TENANT, SCALE_TX, VER_V1, DIM_JSON_PLAIN, OPERATOR).outcome(),
                "🛑 废弃行【仍然占据 scale_id 主键】⇒ 同版本重放建档必须仍是 ALREADY_EXISTS。"
                        + "⚠️ 本域比 device 更重：一个废弃的量表行会【永久占据】那个 scale_id，"
                        + "于是『同一 scale_id 换一版内容重新启用』在库层不可能实现 —— "
                        + "这正是 V19 第 5 节登记的第 ③ 条缺口（reactivate_scale 未提供）");
        assertEquals(1, scaleRows(TENANT, SCALE_TX),
                "🛑 废弃必须【不】删行 —— 它是状态迁移（status → deprecated）");
        assertEquals("deprecated", scaleState(TENANT, SCALE_TX)[0]);

        // 🛑 反向自证：若有人改成『废弃时物理删除』，本判据上面那条 ALREADY_EXISTS 会变成 CREATED。
        //    这里额外断言『废弃行的存在性是 baseline_assessment 可追溯性的前提』——
        //    但它已有历史引用（判据 ② 那次对照建档未产生基线），故只断言行仍在。
        assertEquals(1, inTenant(TENANT, () -> jdbc.queryForObject(
                        "SELECT count(*) FROM scale WHERE scale_id = ?::uuid AND status = 'deprecated'",
                        Integer.class, SCALE_TX)),
                "🛑 必须存在恰 1 行 deprecated 状态的量_table 行");
    }

    // ==================================================================
    // 判据 ⑦
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 幂等重放也写审计；payload 只含可公开摘要（不写 dimension_set_json）")
    void replay_also_writes_audit_and_payload_is_restrained() {
        // 🛑 用一支本判据独享的量表（SCALE_CTX），避免与判据 ③/⑥ 的载体互相干扰：
        //    后者已被废弃，重放会落进 ALREADY_EXISTS 分支，看不出 CREATED 那一条。
        assertEquals(0, scaleRows(TENANT, SCALE_CTX),
                "前置失败：SCALE_CTX 已在库里 ⇒ 下面那条 CREATED 断言会以【归因错误】的方式失败"
                        + "（看上去像『建档坏了』，实际是『载体被占用』）");

        ScaleProvisioningResult first = register(TENANT, SCALE_CTX, VER_V1, DIM_JSON_WITNESS, OPERATOR);
        assertEquals(ScaleOutcome.CREATED, first.outcome());
        String auditId1 = first.auditId();

        // ---- ⑦a 回执 id 必须对应库中真实一行 ----
        assertNotNull(auditId1, "回执必须给出审计 id（对账用）");
        Integer n1 = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE id = ?::uuid", Integer.class, auditId1);
        assertEquals(1, n1,
                "🛑 回执的 auditId 必须真的对应库中一条审计（实际=" + n1 + "）—— "
                        + "B-7 初版曾自编 UUID 当回执，实测 count(*)=0。"
                        + "本断言就是那条缺陷的固化");

        // ---- ⑦b 重放【也】写审计 ----
        ScaleProvisioningResult replay = register(TENANT, SCALE_CTX, VER_V1, DIM_JSON_WITNESS, OPERATOR);
        assertEquals(ScaleOutcome.ALREADY_EXISTS, replay.outcome());
        Integer regRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REGISTERED, SCALE_CTX);
        assertTrue(regRows != null && regRows >= 2,
                "🛑 幂等重放【也】必须写审计 —— 库层 ON CONFLICT DO NOTHING 不报错、不改数据、"
                        + "返回一个正常值，于是『有人试图重复建一份量表』这件事若不留痕就完全不可见。"
                        + "一句话边界：幂等保证的是『不重复建』，不是『不重复记录』。实际条数=" + regRows);

        // ---- ⑦c payload 边界（正向 + 反向）----
        String payload = jdbc.queryForObject(
                "SELECT payload FROM audit_log WHERE id = ?::uuid", String.class, auditId1);
        assertNotNull(payload);
        assertTrue(payload.contains(VER_V1),
                "🛑 payload 必须含 scale_version（分类值，不可反推经营细节）: " + payload);
        assertTrue(payload.contains("primary"),
                "🛑 payload 必须含 scale_type: " + payload);
        assertFalse(payload.contains(DIM_WITNESS),
                "🛑🛑 payload 【不得】含 dimension_set_json 的内容 —— "
                        + "該字段是『这个租户的基线评估按哪几个维度打分』= 评分口径 = 经营信息，"
                        + "而 audit_log 是【无 RLS、全租户可读】的。"
                        + "靶子=" + DIM_WITNESS + " payload=" + payload);

        // ---- ⑦d 废弃也写审计 ----
        ScaleProvisioningResult dep = deprecate(TENANT, SCALE_CTX);
        assertEquals(ScaleOutcome.DEPRECATED, dep.outcome());
        Integer depRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_DEPRECATED, SCALE_CTX);
        assertTrue(depRows != null && depRows >= 1,
                "废弃必须写审计（action=" + ACTION_DEPRECATED + "），实际=" + depRows);
    }

    // ==================================================================
    // 判据 ⑧
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑧ 上下文一致性守卫：外层事务已持有别的租户上下文时，建档必须被拒")
    void context_consistency_guard_rejects_mismatched_context() {
        // 🛑 为什么这条重要：V19 的函数会 set_config 覆盖上下文。
        //    若外层事务已经建立了另一个租户的上下文，覆盖会让
        //    『后续写入去了另一个租户』静默发生 —— 那是跨租户串号。
        String chain = rawRegisterWithForeignContext(TENANT_OTHER, TENANT, SCALE_OTHER);
        assertNotNull(chain,
                "🛑 在外层上下文为租户 " + TENANT_OTHER + " 时，以租户 " + TENANT
                        + " 的身份建档竟然成功了 —— 说明上下文一致性守卫失效，"
                        + "跨租户串号会静默发生");
        assertTrue(chain.contains("P0001"),
                "🛑 拒绝理由必须是 P0001（函数显式 RAISE）。异常链=" + chain);
        assertTrue(chain.contains("不一致"),
                "🛑 消息里必须写明『不一致』—— 让『我传错了租户』与『函数坏了』可区分。"
                        + "异常链=" + chain);

        // 对照：上下文【一致】时必须成功（防『一律拒绝』的实现蒙混过关）
        ScaleProvisioningResult ok = register(TENANT, SCALE_CTX, VER_V1, DIM_JSON_PLAIN, OPERATOR);
        assertTrue(ok.outcome() == ScaleOutcome.CREATED || ok.outcome() == ScaleOutcome.ALREADY_EXISTS,
                "对照失败：上下文一致时必须能建档，实际=" + ok.outcome());
    }

    /**
     * 在外层事务里先建立租户 {@code ctxTenant} 的上下文，再以 {@code callTenant} 的身份建档。
     *
     * <p>🛑 若 {@code ctxTenant == callTenant} 就构不成这个测试 —— 故调用方必须传两个不同的值。
     */
    private static String rawRegisterWithForeignContext(String ctxTenant, String callTenant,
                                                        String scaleId) {
        try {
            tx.execute(status -> {
                jdbc.execute("SET LOCAL app.tenant_id = '" + ctxTenant + "'");
                return jdbc.queryForObject("SELECT register_scale(?::uuid, ?::uuid, ?, ?, ?, ?::jsonb)",
                        String.class, callTenant, scaleId, "primary", VER_V1, "B12量表",
                        DIM_JSON_PLAIN);
            });
            return null;
        } catch (RuntimeException ex) {
            return fullCauseMessages(ex);
        }
    }

    // ==================================================================
    // 判据 ⑨
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("⑨ 纯静态形态：写入组件不带任何 HTTP 注解；生产 Java 代码零 INSERT INTO scale")
    void provisioning_path_is_not_an_http_endpoint() {
        // ---- ⑨a 无 HTTP 注解 ----
        for (Class<?> c : List.of(ScaleLedger.class, ScaleService.class)) {
            for (java.lang.annotation.Annotation a : c.getAnnotations()) {
                String an = a.annotationType().getName();
                assertFalse(an.contains("RestController") || an.contains("Controller")
                                || an.contains("RequestMapping"),
                        "🛑 " + c.getSimpleName() + " 不得带 HTTP 映射注解 —— "
                                + "契约 40 个 path 里没有『创建量表』，给建档加对外端点属契约 MAJOR 变更，"
                                + "且要先回答『谁有权给租户建量表』。实际注解=" + an);
            }
        }

        // ---- ⑨b 生产 Java 代码零 INSERT INTO scale ----
        //   🛑 这条断言把『口径只有一处』这个设计意图变成构建期事实：
        //      幂等写入只发生在 V19 的库函数里，应用侧不再重复。
        //   🛑🛑 匹配必须用【词尾边界】而不是 `contains("INSERT INTO SCALE")` ——
        //      后者会命中 `INSERT INTO scale_item_bank`（前缀相同！），
        //      而那是 C1 题库表，与量表台账完全无关。
        //      本仓在 V19 自证里踩过同一个坑（当时改用 posix 的 `\M` 词尾锚）；
        //      这里是 Java 侧，故用 `\b` 正则（Java 的 `\b` 是真正的词边界，
        //      与 PostgreSQL ARE 的 `\b`（退格字符）不同 —— 这个差别本仓已付过代价）。
        List<String> offenders = new ArrayList<>();
        java.util.regex.Pattern insertScale = java.util.regex.Pattern.compile(
                "INSERT\\s+INTO\\s+scale\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        Path root = skeletonRoot().resolve("dy-app").resolve("src").resolve("main").resolve("java");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (insertScale.matcher(src).find()) {
                    offenders.add(root.relativize(p).toString());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描生产源码失败: " + e.getMessage(), e);
        }
        assertTrue(offenders.isEmpty(),
                "🛑 生产 Java 代码里不得出现 INSERT INTO scale —— "
                        + "幂等写入只在一处（V19 的 register_scale 函数）发生。"
                        + "两处都写会造出『口径分叉』：平时无害，一旦有人只改了其中一处，"
                        + "就会出现『同一个动作有两种结果』的静默不一致。"
                        + "⚠️ 注意匹配刻意带词尾边界 `\\b`：`INSERT INTO scale_item_bank` "
                        + "（C1 题库表）不属本判据范围，用前缀匹配会让它假红。实际违规=" + offenders);

        // ---- ⑨c 迁移里必须有且只有一个函数定义载体 ----
        Path migration = skeletonRoot().resolve("dy-app").resolve("src").resolve("main")
                .resolve("resources").resolve("db").resolve("migration")
                .resolve("V19__scale_provisioning_primitive.sql");
        assertTrue(Files.isRegularFile(migration),
                "V19 迁移必须存在: " + migration);
        String sql;
        try {
            sql = Files.readString(migration, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 V19 失败: " + e.getMessage(), e);
        }
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION register_scale("),
                "V19 必须定义 register_scale（本类全部写侧判据的载体）");
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION deprecate_scale("),
                "V19 必须定义 deprecate_scale");
    }

    /** 去掉注释与字符串字面量（与 {@code RlsInjectionRealityGateTest} 同款的双粒度剥离）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder();
        int n = src.length();
        int state = 0;   // 0=code, 1=line comment, 2=block comment, 3=string
        int i = 0;
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
    // 七、收尾
    // ==================================================================

    /**
     * 收尾：清数据 + <b>断言审计证据确实留下</b>（不删审计）+ 清理自证。
     *
     * <p>Spring Boot 3.3 支持在 {@code @AfterAll} 上注入 {@link DataSource}
     * （与测试实例生命周期解耦，故本方法可以是 static）。
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
                // SET LOCAL 必须在事务内 —— 事务一结束即失效（各 *Ledger 的 inTenant 同一纪律）
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                // 🛑 顺序 = 子 → 父。baseline_assessment 引用 scale/customer/staff；
                //    scale 引用 tenant。顺序错会以 23503 失败，而那时表象是「清理逻辑坏了」。
                j.update("DELETE FROM baseline_assessment WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM scale WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff WHERE tenant_id = ?::uuid", tid);
            });
        }
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
        j.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT_OTHER);

        // ---- 清理自证 ----------------------------------------------------
        // 🛑 这一条不是形式主义：FORCE RLS 下无上下文时 DELETE 会【静默删 0 行】，
        //    于是「清理」看起来成功、残留却一路累积。故必须回头数一次。
        Integer leftTenants = j.queryForObject(
                "SELECT count(*) FROM tenant WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, TENANT, TENANT_OTHER);
        assertTrue(leftTenants == null || leftTenants == 0,
                "清理后仍残留 " + leftTenants + " 个租户 —— 清理逻辑失效");
        for (String tid : List.of(TENANT, TENANT_OTHER)) {
            t.executeWithoutResult(status -> {
                j.execute("SET LOCAL app.tenant_id = '" + tid + "'");
                assertTrue(j.queryForObject("SELECT count(*) FROM scale", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有量表残留（FORCE RLS 会静默删 0 行，"
                                + "这正是最容易被忽略的一类残留）");
                assertTrue(j.queryForObject("SELECT count(*) FROM baseline_assessment",
                        Integer.class) == 0, "清理后租户 " + tid + " 仍有基线评估残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM customer", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有客户残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下 ----------------------------
        // 🛑 audit_log 是一条全局单链，删掉中间任何一行会让【它的后继】prev_hash 对不上，
        //    从而为后续每一次 verifyChain() 留下一个永久的假断口。
        assertTrue(serviceCalls > 0,
                "自证失败：serviceCalls 为 0，说明本类从未真正走过建档/废弃路径 —— "
                        + "那么上面所有『必须有审计』的断言都没有载体");
        Integer regRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REGISTERED, SCALE_CTX);
        assertTrue(regRows != null && regRows >= 2,
                "审计条目必须留下且不得被清理（action=" + ACTION_REGISTERED
                        + " target_id=" + SCALE_CTX + "，判据 ⑦ 至少产生两条：CREATED + 重放）。"
                        + "实际条数=" + regRows);
        // 🛑 SCALE_TX 的 SCALE_REGISTERED 只有判据 ② 里【成功对照那一次】的 1 条
        //    （失败那一次因 22001 回滚、不落库）—— 这正好反向印证「审计失败 ⇒ 建档回滚」。
        Integer txRegRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REGISTERED, SCALE_TX);
        assertTrue(txRegRows != null && txRegRows >= 1,
                "判据 ② 的成功对照那次必须留下 1 条 SCALE_REGISTERED（失败那一次不落库）。"
                        + "实际条数=" + txRegRows);
    }
}