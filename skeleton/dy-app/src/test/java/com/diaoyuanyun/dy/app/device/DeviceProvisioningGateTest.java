package com.diaoyuanyun.dy.app.device;

import com.diaoyuanyun.dy.app.device.domain.DeviceOutcome;
import com.diaoyuanyun.dy.app.device.domain.DeviceRecord;
import com.diaoyuanyun.dy.app.device.service.DeviceService;
import com.diaoyuanyun.dy.app.device.service.DeviceService.DeviceProvisioningResult;
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
import java.io.IOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-11 · 设备建档通路的真库真表端到端回归</b> ——
 * 它见证的是 {@code device} 表从「零生产写入方」到「有写入方」这件事
 * <b>真的把 D6 端点救活了</b>，并同时见证 V18 相对 V17 形态的那处<b>实质改动</b>。
 *
 * <h2>为什么必须有一个真库门禁，而不是靠 {@code ProvisioningBoundaryGateTest} 的静态扫描</h2>
 * 那道静态门禁只能证明「生产代码里出现了 {@code INSERT INTO device}」。
 * 它与「D6 {@code POST /device-dispatches} 真的能落库了」之间隔着五道缝，
 * 静态扫描一道都看不见：
 * <ol>
 *   <li>{@code ON CONFLICT (device_id)} 的<b>推断目标</b>是那个<b>单列全局主键</b>
 *       {@code device_pkey}（实测：{@code PRIMARY KEY (device_id)}，而
 *       {@code uq_tenant_device_device_id} 只是给复合外键用的载体）——
 *       故冲突判定必须自己补上「冲突了但它是不是我的」，而这<b>只在运行期才炸</b>；</li>
 *   <li>建档之后，那支 {@code device_id} 是否真的能被
 *       {@code device_dispatch.device_id} 的复合外键
 *       {@code device_dispatch_device_id_fkey} 接受；</li>
 *   <li>建档与审计是否真的在<b>同一个事务</b>里 —— 这条不变量一旦失效
 *       <b>没有任何静态检查会红</b>；</li>
 *   <li>归档到底是<b>状态迁移</b>还是删行 —— 后者会让历史下发记录失去可追溯对象，
 *       而所有计数断言仍然通过；</li>
 *   <li>D6 这条链上<b>还有一个前置</b>：{@code device_dispatch} 除 {@code device} 外
 *       还引用 {@code plan(plan_id, version)}（{@code fk_device_dispatch_plan_version}）。
 *       ⇒ 「建档之后下发就通了」这句话是<b>假的</b>，除非方案也已审核通过
 *       （本类判据 ① 的 ①e 就是为此存在 —— 见「判据设计一」）。</li>
 * </ol>
 *
 * <h2>🛑 判据设计一：先证明「缺口真的存在过」，再证明「它被修好了」，最后证明「断言对它有反应」</h2>
 * 本类最重要的结构是判据 ① 的<b>同一支设备、三次下发</b>对照：
 * <pre>
 *   建档【之前】下发（方案已审核）⇒ HTTP 500，成因含 DataIntegrityViolationException
 *   建档【之后】同一次下发（同方案）⇒ HTTP 200，且真的落了一行 device_dispatch
 *   物理删掉 device 行之后        ⇒ HTTP 500 【重新】出现
 * </pre>
 * 三段缺一不可。第一段证明缺口真实存在；第二段证明修复有效；第三段是本门禁的
 * <b>判别力自证</b> —— 若「500」这条断言在任何状态下都成立，
 * 那它就不是在测缺口，而是一条恒真断言（装饰而非门禁）。
 *
 * <h2>🛑 判据设计二：必须排除「方案未审核」这个替代解释</h2>
 * ①b 在发起下发<b>之前</b>先断言方案确实是 {@code approved}。
 * 没有这一步，「下发 500」就有了一个完全无关的解释：
 * {@code PlanService.createDispatch} 的第一件事就是查 {@code planApproved}，
 * 未过审核会 403（{@code GATE_MISSING}）—— 而 403 与 500 是两件完全不同的事。
 * 更隐蔽的一种误读是：<b>把 500 读成「方案没审核」</b>，
 * 于是去改审核链路，而真正的成因是 {@code device} 一行都没有。
 * 本仓的既定纪律是「根因断言排在后果断言之前」，此处同理：
 * 先钉住「前置都满足了」，再断言「那它为什么还失败」。
 *
 * <h2>🛑 判据设计三：每个「必须成功」旁边都有一条「必须失败」，且失败要给出理由</h2>
 * 沿用 {@code OrganizationProvisioningE2ETest} / {@code BandBindingGateTest} 的比较器纪律：
 * 断言必须穿透 cause 链取 <b>SQLSTATE</b>，而不是断「抛了某个异常」。
 * 🛑 因为 <b>23503（外键）、42501（RLS）、P0001（显式 RAISE）都会让「写失败」这件事成立</b>：
 * <ul>
 *   <li>只断言「失败」⇒ 一条「我忘了设租户上下文」的实现也能通过；</li>
 *   <li>把 42501 当成 23503 ⇒ 那条断言证明的是「行归属」，而不是「引用完整性」；</li>
 *   <li>P0001 与 23503 在<b>本迁移里</b>必须严格分工：函数负责「给可读原因」，
 *       库层负责「兜底」。两者都 fail-closed，区别只在诊断成本 ——
 *       而断言必须把这条分工钉死，否则它可以被静默地换成任何一侧。</li>
 * </ul>
 *
 * <h2>🛑🛑 判据设计四（本类最重要）：跨租户撞号必须是【异常】而不是一个返回值</h2>
 * 这是 V18 相对 V17 形态的<b>唯一实质改动</b>，也是本类判据 ⑤ 的全部内容。
 * 起因是一条<b>实测出来的</b>机制（`_work/v18_onconflict_crosstenant_probe.sql`）：
 * <pre>
 *   ON CONFLICT (device_id) DO NOTHING 的推断目标是【全局】主键
 *   ⇒ 当 device_id 已被【别的租户】占用时：
 *        主键冲突照常发生 ⇒ 走 DO NOTHING ⇒ ROW_COUNT = 0
 *        而本租户上下文里 SELECT 又【看不见】那一行（RLS 的 USING 挡住）
 *   ⇒ 若照抄 B-10（只按 ROW_COUNT 判定），就会对一个手里【一行都没有】的租户
 *     返回 ALREADY_EXISTS
 * </pre>
 * 后果不是报错，而是<b>「返回值说 ALREADY_EXISTS、下游仍然 23503」</b>：
 * 调用方据此认为「设备已就绪，继续下发」，而紧接着的
 * {@code INSERT INTO device_dispatch} 依然失败。
 * <p>故判据 ⑤ 断言三件事：<b>必须 RAISE（P0001）</b>、
 * <b>消息里必须写明「另一租户」</b>、<b>本租户里必须一行都没有</b>；
 * 并附两条对照：另一租户用<b>自己的</b> device_id 必须成功（防「拒绝一切」的实现蒙混过关）、
 * 本租户重放同一支必须仍是 {@code ALREADY_EXISTS}（防「全局拒绝」的实现蒙混过关）。
 * <p>再加一条元层断言：{@link DeviceOutcome} <b>不得</b>出现第三态 ——
 * 「用缺失表达不该继续」这条设计意图必须被代码守住，而不只写在注释里。
 *
 * <h2>判据设计五：审计失败必须让建档回滚（一条易静默失效的不变量）</h2>
 * {@code DeviceService} 的类注释逐字写明：建档与审计同事务，
 * 且点出「若有人把 {@code ledger.register()} 的传播行为改成 {@code REQUIRES_NEW}，
 * 这条不变量会<b>静默失效</b>」。
 * ⇒ 判据 ② 用一个<b>真实的失败模式</b>（超长 {@code operator} 撞
 * {@code audit_log.actor VARCHAR(128)}）去撞这条不变量，断言 {@code device} 行确实被回滚。
 * <p>🛑 为什么用「超长 actor」而不是 mock 掉 {@code AuditLogService}：
 * 替换 bean 会改变装配形态，于是被验的就不再是生产的那套装配。
 * 本仓的既定处置是「用真实存在的失败模式去撞不变量」。
 *
 * <h2>🛑 判据设计六：测试【绝不】代建任何 {@code device} 行</h2>
 * 这正是 B-11 这个缺口的成因本身：在本次收口之前，全仓唯一的
 * {@code INSERT INTO device} 在 {@code src/test} 的夹具里，
 * 于是「设备从哪来」这个问题在测试里永远有一个答案、在生产里没有。
 * ⇒ 本类的每一支设备都由 {@link DeviceService} 建。
 * 唯一的例外是判据 ④ 与 ①f 的<b>负样本</b>：
 * 它们要证明的是「绕过函数在库层会被拒」/「有下发记录时删不掉设备行」，
 * 那两条必须以裸 SQL 形态出现才成立。
 *
 * <h2>零污染 —— 以及一处刻意例外（审计行不清理）</h2>
 * 数据全部用 {@code d1100000-} 前缀自建（与 {@code b0700000-} / {@code b1000000-} /
 * {@code e2e00000-} 均不重叠），{@code @AfterAll} 在租户上下文内按
 * 「子 → 父」外键序清理（{@code device_dispatch} → {@code plan_review} → {@code plan} →
 * {@code device} → {@code customer} → {@code staff} → {@code store} → {@code region} → {@code tenant}）。
 * <p>🛑 清理也必须<b>在租户上下文内</b>：这几张表是 {@code FORCE ROW LEVEL SECURITY}，
 * 无上下文时 {@code DELETE} 会<b>静默删 0 行</b>，于是残留一路累积，
 * 直到某次运行的前置断言报「它已存在」—— 而那时没人知道是清理失效。
 * 本仓 S2-5 已付过一次代价，故 {@code @AfterAll} 里带<b>清理自证</b>。
 * <p>🛑 审计条目刻意不删：{@code audit_log} 是<b>全局单链</b>，
 * 删中间行会为它的后继留下永久的 {@code prev_hash} 断口。
 * 改为断言「证据确实留下了」。
 *
 * <h2>执行顺序</h2>
 * 用 {@link Order} 显式排序：判据 ① 会<b>主动破坏</b>自己那支设备
 * （判别力自证的最后一步是物理删掉它），故它必须自成一个闭环、不与他人共享载体。
 * 其余判据各用独立 device_id，彼此无依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-11 · 设备建档通路真库回归（D6 缺口对照 / 两态三态 / 跨租户撞号 / 同事务审计）")
class DeviceProvisioningGateTest {

    // ==================================================================
    // 一、口径常量
    // ==================================================================

    /** 本类主租户（{@code d1100000-} 前缀，与其它测试类零交集）。 */
    private static final String TENANT = "d1100000-0000-0000-0000-000000000001";

    /** 对照租户 —— 判据 ④ / ⑤ / ⑧ 的跨租户负样本用。 */
    private static final String TENANT_OTHER = "d1100000-0000-0000-0000-0000000000f1";

    // —— 门店（device.store_id 是复合外键的一端，必须真存在）——
    private static final String REGION = "d1100000-0000-0000-0000-000000000011";
    private static final String STORE = "d1100000-0000-0000-0000-000000000021";
    private static final String STORE_B = "d1100000-0000-0000-0000-000000000022";
    /** 属于<b>另一个租户</b>的门店 —— 判据 ④ 的跨租户引用负样本。 */
    private static final String STORE_OTHER = "d1100000-0000-0000-0000-000000000023";

    /** 受派员工（plan_review.reviewer_id 的外键目标，必须真存在）。 */
    private static final String STAFF = "d1100000-0000-0000-0000-000000000031";

    /** 客户（plan.customer_id 的外键目标）。 */
    private static final String CUSTOMER = "d1100000-0000-0000-0000-000000000032";

    // —— 设备 ——
    /** 判据 ①：D6 缺口对照用的那一支（本判据结束时它会被物理删除）。 */
    private static final String DEV_GATE = "d1100000-0000-0000-0000-000000000041";
    /** 判据 ③ / ⑥ / ⑦：两态三态与审计流转的载体。 */
    private static final String DEV_STATE = "d1100000-0000-0000-0000-000000000042";
    /** 判据 ②：审计失败注入用。 */
    private static final String DEV_TX = "d1100000-0000-0000-0000-000000000043";
    /** 判据 ④：跨租户门店引用（函数侧）。 */
    private static final String DEV_CROSS = "d1100000-0000-0000-0000-000000000044";
    /** 判据 ④b：跨租户门店引用（裸 SQL 绕过函数）。 */
    private static final String DEV_BARE = "d1100000-0000-0000-0000-000000000045";
    /** 判据 ⑤：跨租户撞号 —— 租户 A 先占用这一支。 */
    private static final String DEV_SHARED = "d1100000-0000-0000-0000-000000000046";
    /** 判据 ⑤ 对照：租户 B 用自己的 device_id 必须能建。 */
    private static final String DEV_OTHER = "d1100000-0000-0000-0000-000000000047";
    /** 判据 ⑧：上下文一致性守卫用。 */
    private static final String DEV_CTX = "d1100000-0000-0000-0000-000000000048";
    /**
     * 判据 ⑦：审计 payload 与幂等重放留痕的载体。
     *
     * <p>🛑 它必须是一支<b>本判据独享</b>的设备，不能借用 {@link #DEV_TX} ——
     * 后者在判据 ② 里已被建成 {@code active}（那是「审计失败回滚」用例的对照产物），
     * 于是判据 ⑦ 的第一次建档会返回 {@code ALREADY_EXISTS} 而不是 {@code CREATED}，
     * 而那条 {@code CREATED} 断言就会以一条<b>归因错误</b>的方式失败
     * （看上去像「建档坏了」，实际是「载体被前一个用例占用了」）。
     */
    private static final String DEV_AUDIT = "d1100000-0000-0000-0000-000000000049";
    /** 一个<b>从不</b>被建档的 id —— 只作为「不存在」的负样本出现。 */
    private static final String DEV_GHOST = "d1100000-0000-0000-0000-00000000004f";

    /** 审计动作名与目标类型 —— 与 {@code DeviceService} 的常量逐字一致（那里是包私有）。 */
    private static final String ACTION_REGISTERED = "DEVICE_REGISTERED";
    private static final String ACTION_RETIRED = "DEVICE_RETIRED";
    private static final String TARGET_TYPE = "device";

    /** 操作者 —— 审计里唯一有追责含义的一栏。 */
    private static final String OPERATOR = "ops-b11-witness";

    /**
     * 🛑 判据 ② 的注入载荷：长度超 {@code audit_log.actor VARCHAR(128)} 的操作者标识。
     *
     * <p>为什么它是一条<b>真实</b>的失败模式而不是「人为抛异常」：
     * {@code DeviceService.requireOperator()} 只挡 null 与空白
     * （那是「有没有说谁做的」，属业务校验），<b>不</b>挡长度
     * （那是列宽问题，应由库层报 22001，而不是让业务层去猜上限）。
     */
    private static final String TOO_LONG_OPERATOR = "x".repeat(200);

    /**
     * 判据 ⑦ 用的参数模板标识 —— 它的唯一作用是作「反向断言」的靶子。
     *
     * <p>🛑 为什么需要一个<b>可识别</b>的值：{@code DeviceService.registerPayload()}
     * 的注释裁定「{@code model} 可以进 payload，{@code param_template_id} 不可以」
     * （前者是不可反推经营细节的分类值，后者是「这个租户在用哪套参数模板」= 经营信息）。
     * 要证明这条边界真的生效，靶子必须是一个<b>唯属于本用例</b>的字符串 ——
     * 用 {@code TPL-G2-V3} 这种真实取值会让断言在 payload 里恰好不含它时
     * 也无法排除「实现把它换成别的了」。
     */
    private static final String PARAM_TPL = "TPL-B11-WITNESS";

    /** 真正调用过建档/归档的次数（供 {@code @AfterAll} 断言「审计证据确实留下了」，而不删它）。 */
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
    DeviceService service;

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
     * 种两个租户 + 区域/门店/员工/客户 + 一个方案前置。
     *
     * <p>🛑 这里<b>不</b>种任何 {@code device} 行 —— 那是本类全部判据的载体，
     * 必须由 {@link DeviceService} 建（见类注释「判据设计六」）。
     *
     * <p>🛑 为什么必须种 {@code staff}：{@code plan_review.reviewer_id} 是
     * {@code NOT NULL REFERENCES staff (staff_id)}，而 D5-c 的复核人
     * 取自 JWT 的 {@code staff_id}（{@code TenantContext.staffId()}）。
     * 不种它，判据 ① 的「方案已审核」这一步会以一条<b>外键错</b>失败，
     * 而失败原因与「设备缺口」完全无关 —— 那会是一次归因错误的红。
     */
    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B11租户-设备建档', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?::uuid, 'B11对照租户', 'active') "
                + "ON CONFLICT (id) DO NOTHING", TENANT_OTHER);

        inTenant(TENANT, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                    + "VALUES (?::uuid, ?::uuid, 'B11区') ON CONFLICT DO NOTHING", REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'B11店', '直营') ON CONFLICT DO NOTHING",
                    STORE, TENANT, REGION);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'B11二店', '直营') ON CONFLICT DO NOTHING",
                    STORE_B, TENANT, REGION);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    STAFF, TENANT, STORE);
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'B11客户', 'active') ON CONFLICT DO NOTHING",
                    CUSTOMER, TENANT);
            return null;
        });

        inTenant(TENANT_OTHER, () -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'B11对照区') ON CONFLICT DO NOTHING",
                    "d1100000-0000-0000-0000-000000000012", TENANT_OTHER);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'B11对照店', '直营') ON CONFLICT DO NOTHING",
                    STORE_OTHER, TENANT_OTHER, "d1100000-0000-0000-0000-000000000012");
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

    /** 在租户上下文里执行一条<b>必须成功</b>的写语句。 */
    private static void writeInTenant(String tenantId, String sql, Object... args) {
        inTenant(tenantId, () -> {
            jdbc.update(sql, args);
            return null;
        });
    }

    /**
     * 在租户上下文里执行一条<b>必须失败</b>的写语句，返回最深层的 SQLSTATE。
     *
     * <p>🛑 返回 SQLSTATE 而不是布尔：见类注释「判据设计三」——
     * 23503（外键）、42501（RLS）、P0001（显式 RAISE）都会让「失败」成立，
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

    /** 与 {@link #writeExpectingFailure} 同款，但额外返回整条 cause 链文本（供「哪个外键」这类断言）。 */
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

    /** 该设备在本租户内的行数（走上下文内的读，避免 FORCE RLS 静默 0 行）。 */
    private static int deviceRows(String tenantId, String deviceId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM device WHERE device_id = ?::uuid", Integer.class, deviceId));
        return n == null ? 0 : n;
    }

    /**
     * 该设备的 {@code [status, updated_at, model, store_id, param_template_id]}；
     * 不存在则 {@code null}。
     */
    private static String[] deviceState(String tenantId, String deviceId) {
        return inTenant(tenantId, () -> {
            List<String[]> rows = jdbc.query(
                    "SELECT status, coalesce(updated_at::text, ''), model, store_id::text,"
                            + "       coalesce(param_template_id, '') "
                            + "  FROM device WHERE device_id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)},
                    deviceId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 该租户内某门店名下的设备行数（「建档落到了哪个门店」的可观测面）。 */
    private static int devicesOfStore(String tenantId, String storeId) {
        Integer n = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM device WHERE store_id = ?::uuid", Integer.class, storeId));
        return n == null ? 0 : n;
    }

    private DeviceProvisioningResult register(String tenantId, String deviceId, String storeId,
                                              String model, String tpl, String operator) {
        serviceCalls++;
        return service.register(new DeviceRecord(tenantId, UUID.fromString(deviceId),
                UUID.fromString(storeId), model, tpl), operator);
    }

    /** 建档（合法操作者、无参数模板）—— 最常用的一种调用。 */
    private DeviceProvisioningResult registerOk(String tenantId, String deviceId, String storeId) {
        return register(tenantId, deviceId, storeId, "杠2", null, OPERATOR);
    }

    private DeviceProvisioningResult retire(String tenantId, String deviceId) {
        serviceCalls++;
        return service.retire(tenantId, UUID.fromString(deviceId), OPERATOR);
    }

    // ==================================================================
    // 三、异常链工具（本仓既定形态：判据必须给出【理由】）
    // ==================================================================

    /**
     * 取异常链上<b>最深</b>那个 {@link java.sql.SQLException} 的 SQLSTATE。
     *
     * <p>🛑 用 SQLSTATE 而非消息文本作主判据：它是 PG 的错误码，
     * <b>与数据库语言环境无关</b>。本仓的 PG 是中文环境，RLS 的消息是
     * 「新行违背了表『device』的行级安全策略」，任何写死英文
     * {@code row-level security} 的断言在这里都会假红。
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
     * <p>🛑 <b>必须把 SQLSTATE 拼进来</b>（本轮实测抓到的一处可观测面不足）：
     * 判据 ①c 要同时断言「23503」与「命中哪条外键」——
     * 前者只有 {@code SQLException.getSQLState()} 能给出，而后者只存在于
     * {@code getMessage()} 里。初版只拼消息，于是在中文 PG 环境下
     * （消息形如「违反外键约束 "device_dispatch_device_id_fkey"」）
     * 一条<b>完全正确</b>的失败会被判成红 —— 那是「工具看不见它要看的东西」，
     * 不是「产品有问题」。修工具的可观测面，而不是放宽判据的期望值。
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

    private ResponseEntity<String> get(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
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

    /** 一条合法的 D6 下发体（{@code result} 取 {@code 成功} 时 {@code failed_reason} 不必填）。 */
    private static String dispatchBody(String planId, String deviceId, String storeId) {
        return "{\"plan_id\":\"" + planId + "\",\"plan_version\":1,"
                + "\"store_id\":\"" + storeId + "\",\"device_id\":\"" + deviceId + "\","
                + "\"param_snapshot\":\"{}\",\"result\":\"成功\"}";
    }

    /**
     * 走真实链路把一个方案做到 {@code approved}，返回 plan_id。
     *
     * <p>🛑 为什么必须带 {@code second_confirm=true}：出方案人与审核人都是本 token 的
     * {@code staff_id}，{@code PlanService.reviewPlan} 对「同人自助通过」要求二次确认
     * （PRD US-2：不允许同一账号无痕自助通过），否则 409。
     */
    private String approvedPlan() {
        ResponseEntity<String> created = post("/api/v1/plans",
                "{\"customer_id\":\"" + CUSTOMER + "\",\"treatment_json\":\"{}\","
                        + "\"lifestyle_json\":\"{}\",\"intent_params\":\"{}\"}", token("meridian"));
        assertEquals(200, created.getStatusCodeValue(), "出方案应 200: " + created.getBody());
        String planId = parse(created.getBody()).at("/data/plan_id").asText();

        ResponseEntity<String> reviewed = post("/api/v1/plans/" + planId + "/reviews",
                "{\"result\":\"通过\",\"second_confirm\":true}", token("meridian"));
        assertEquals(200, reviewed.getStatusCodeValue(), "审核通过应 200: " + reviewed.getBody());
        return planId;
    }

    // ==================================================================
    // 判据 ①
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("① 🛑 D6 缺口对照：设备未建档时下发必 500（方案已审核、无预检、只有一条外键报错），建档后必 200，物理删行后必【重新】500")
    void the_missing_device_writer_was_the_real_cause_and_provisioning_fixes_it() {
        // ---- ①a 前置：这支设备在库里【不存在】----
        assertEquals(0, deviceRows(TENANT, DEV_GATE),
                "前置失败：DEV_GATE 已在库里 —— 那么下面『建档前必失败』就无法与"
                        + "『它本来就在』区分，本例全部门禁意义丢失");

        // ---- ①b 🛑 排除替代解释：方案必须【已审核】----
        //   没有这一步，「下发 500」就有一个完全无关的解释：PlanService.createDispatch
        //   的第一件事就是查 planApproved，未过审核会 403 GATE_MISSING —— 而 403 与 500
        //   是两件完全不同的事。先钉住「前置都满足了」，再断言「那它为什么还失败」。
        String planId = approvedPlan();
        ResponseEntity<String> planCheck = get("/api/v1/plans/" + planId, token("meridian"));
        assertEquals(200, planCheck.getStatusCodeValue(), "方案查阅应 200: " + planCheck.getBody());
        assertEquals("approved", parse(planCheck.getBody()).at("/data/status").asText(),
                "🛑 排除替代解释的前置失败：方案状态不是 approved。"
                        + "若不是 approved，下面那次下发的失败原因会是 403 GATE_MISSING（方案未审核），"
                        + "而不是本判据要证的『device 一行都没有』——两者是完全不同的结论");

        // ---- ①c 根因断言（库层）：写 device_dispatch 必须被【外键】拒 ----
        //   🛑 这条以裸 SQL 形态出现，理由是它要证的正是「不加任何预检时库层会怎样」。
        //      注意这里 plan/store 都齐备（①b 已确认），故唯一缺失的就是 device 行。
        String rawChain = failureChain(TENANT,
                "INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version,"
                        + " store_id, device_id, param_snapshot, result, dispatched_at, created_by) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid,"
                        + " '{}'::jsonb, '成功', now(), 'b11-raw-probe')",
                UUID.randomUUID().toString(), TENANT, planId, STORE, DEV_GATE);
        assertNotNull(rawChain,
                "🛑 device_dispatch 竟然接受了一个【不存在】的 device_id —— "
                        + "那说明 device_dispatch_device_id_fkey 不存在了，"
                        + "B-11 这个缺口的前提已经变了，本门禁必须重新设计");
        assertTrue(rawChain.contains("23503"),
                "🛑 拒绝理由必须是 23503(foreign_key_violation)，实际异常链=" + rawChain
                        + "。若不是 23503，本条断言已退化为假阳性 —— "
                        + "尤其要排除 42501(RLS)：那说明真正的原因是『我没设租户上下文』，"
                        + "而不是『设备不存在』");
        assertTrue(rawChain.contains("device_dispatch_device_id_fkey"),
                "🛑 必须命中【device 那一支】外键（device_dispatch_device_id_fkey）—— "
                        + "本判据要证的是设备缺口，若命中的是 fk_device_dispatch_plan_version 或"
                        + " device_dispatch_store_id_fkey，那说明失败原因与设备无关。异常链=" + rawChain);

        // ---- ①d 后果断言（走真实 HTTP 链路）：客户端/门店此时下发的表现 ----
        ResponseEntity<String> before = post("/api/v1/device-dispatches",
                dispatchBody(planId, DEV_GATE, STORE), token("meridian"));
        assertEquals(500, before.getStatusCodeValue(),
                "🛑 D6 在『设备未建档』时必须失败 —— 这正是缺口的对外表现："
                        + "契约声明 200 / 403，实际却是一个 500；而且链上【没有任何 device 存在性预检】"
                        + "（PlanService.createDispatch 只查 planApproved，然后裸 INSERT），"
                        + "故调用方拿不到任何可读的业务错误。实际="
                        + before.getStatusCodeValue() + " body=" + before.getBody());
        assertTrue(before.getBody() != null
                        && before.getBody().contains("DataIntegrityViolationException"),
                "500 的成因必须能看到外键违例的形态（GlobalExceptionHandler.handleOther 回显类名）。"
                        + "body=" + before.getBody());

        // ---- ①e 修复：由【被测代码】建这支设备（测试绝不代建）----
        DeviceProvisioningResult created = registerOk(TENANT, DEV_GATE, STORE);
        assertEquals(DeviceOutcome.CREATED, created.outcome(),
                "首次建档必须是 CREATED（返回 ALREADY_EXISTS 说明前置未清干净）");
        List<String[]> auditRows = jdbc.query(
                "SELECT action, target_type, target_id FROM audit_log WHERE id = ?::uuid",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                created.auditId());
        assertEquals(1, auditRows.size(),
                "回执的 auditId 必须真的对应库中一条审计（实际条数=" + auditRows.size()
                        + "）—— 否则回执无法用于对账");
        assertEquals(DEV_GATE, auditRows.get(0)[2],
                "审计 target_id 必须是本次建档的设备 id");

        // ---- ①e2 🛑 建档【之后】：同一次下发必须成功 ----
        //   🛑 注意这里同时证明了「D6 的两个前置都齐了」：device 行（本次建）+ plan approved（①b）。
        //      这正是 V18 文件头记下的那条实测 —— 「建档后仍然失败：fk_device_dispatch_plan_version」
        //      ⇒「一条缺口修好了不等于端到端通了」，而这句话在本判据里是被两段断言合力钉住的。
        ResponseEntity<String> after = post("/api/v1/device-dispatches",
                dispatchBody(planId, DEV_GATE, STORE), token("meridian"));
        assertEquals(200, after.getStatusCodeValue(),
                "🛑 D6 在设备建档之后必须回到 200 —— 这是 B-11 那条『门店设备台账是空的』"
                        + "被修好的最终证据。实际=" + after.getStatusCodeValue()
                        + " body=" + after.getBody());
        assertTrue(parse(after.getBody()).at("/data/dispatch_id").isTextual(),
                "D6 响应体必须给出 dispatch_id。body=" + after.getBody());

        // 🛑 且必须真的落了一行 —— 「没抛错」这条断言单独会绿在一个静默 DO NOTHING 上
        assertEquals(1, (Integer) inTenant(TENANT, () -> jdbc.queryForObject(
                        "SELECT count(*) FROM device_dispatch WHERE device_id = ?::uuid",
                        Integer.class, DEV_GATE)),
                "🛑 建档之后 device_dispatch 必须真的多出这一行 —— 上一步没抛错还不够："
                        + "若那条 INSERT 被静默忽略，『没抛错』这条断言仍会绿。"
                        + "这是本仓反复出现的一类假通过");

        // ---- ①f 🛑 判别力自证（两步，缺一不可）----
        //   🛑 第一步：有下发记录时，device 行【删不掉】。
        //      这不是顺带一提，而是「归档为什么必须是状态迁移」这条设计的库层理由本身 ——
        //      它把一条注释里的裁定变成可执行事实。
        String deleteGuard = writeExpectingFailure(TENANT,
                "DELETE FROM device WHERE device_id = ?::uuid", DEV_GATE);
        assertEquals("23503", deleteGuard,
                "🛑 在 device_dispatch 仍引用该设备时，DELETE device 必须被 23503 拒 —— "
                        + "若它成功了，说明 device_dispatch_device_id_fkey 的删除行为被改成了 CASCADE/SEF NULL，"
                        + "那会让历史下发记录【静默失去可追溯对象】，而 D6 的语义恰是『下行、可追责』。"
                        + "实际=" + deleteGuard);

        //   🛑 第二步：把引用它的下发记录清掉、再把 device 行物理删掉，
        //      同一条下发请求必须【重新】变红。没有这一步，一个「在任何状态下都返回 500」
        //      的实现同样能让 ①c/①d 全绿 —— 那时的它们就不是在测缺口，而是恒真断言。
        writeInTenant(TENANT, "DELETE FROM device_dispatch WHERE tenant_id = ?::uuid AND device_id = ?::uuid",
                TENANT, DEV_GATE);
        writeInTenant(TENANT, "DELETE FROM device WHERE device_id = ?::uuid", DEV_GATE);
        assertEquals(0, deviceRows(TENANT, DEV_GATE), "自证前提：DEV_GATE 必须已被物理删除");

        ResponseEntity<String> again = post("/api/v1/device-dispatches",
                dispatchBody(planId, DEV_GATE, STORE), token("meridian"));
        assertEquals(500, again.getStatusCodeValue(),
                "🛑 判别力自证失败：把 device 行删掉之后，同一次下发竟然没有回到 500"
                        + "（实际=" + again.getStatusCodeValue() + "）—— "
                        + "那说明本门禁『建档前后』的对照是假的，它对『设备在不在』这件事没有反应");
        assertEquals(before.getStatusCodeValue(), again.getStatusCodeValue(),
                "🛑 而且它必须与建档【前】观察到的是同一个状态码（建档前="
                        + before.getStatusCodeValue() + " 撤销后=" + again.getStatusCodeValue()
                        + "）—— 两个不同的表现会让这条对照失去意义");
    }

    // ==================================================================
    // 判据 ②
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("② 🛑 审计失败必须让建档【回滚】—— 用真实的 actor 超长（22001）撞这条易静默失效的不变量")
    void a_failed_audit_write_rolls_the_provisioning_back() {
        assertEquals(0, deviceRows(TENANT, DEV_TX), "前置失败：DEV_TX 应尚未建档");

        // ---- ②a 注入：审计写入必然失败（operator 超 audit_log.actor VARCHAR(128)）----
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> register(TENANT, DEV_TX, STORE, "杠2", null, TOO_LONG_OPERATOR),
                "🛑 审计写不进去时，建档调用竟然没有抛错 —— "
                        + "那说明审计失败被静默吞掉了，而『台账的每一行都有来源』这条不变量已经失效。"
                        + "device 是 device_dispatch（D6 下发，语义为『下行、可追责』）的引用目标，"
                        + "一次无痕的建档等于一次无主的可追责台账起点");

        String sqlState = deepestSqlState(ex);
        assertEquals("22001", sqlState,
                "🛑 本次失败的成因必须是 22001(string_data_right_truncation，actor 超列宽)，实际="
                        + sqlState + "。若成因是别的，那本条用例证明的就不是"
                        + "『审计失败 ⇒ 建档回滚』，而是其它某处失败导致建档没发生 —— "
                        + "那是两个完全不同的结论。异常链=" + fullCauseMessages(ex));

        // ---- ②b 核心断言：建档必须被回滚（不是「没抛错」，而是「库里没有」）----
        assertEquals(0, deviceRows(TENANT, DEV_TX),
                "🛑🛑 审计失败之后 device 行【仍在】库里 —— "
                        + "『审计失败则建档回滚』这条不变量已静默失效，"
                        + "而本仓没有任何其它检查会发现它。最常见的成因是有人把 "
                        + "DeviceLedger.register() / DeviceService.register() 的 TransactionTemplate "
                        + "改成了 PROPAGATION_REQUIRES_NEW（建档独立提交，审计失败只回滚审计）—— "
                        + "此时设备已落库而没有任何证据，且业务测试全绿");
        assertEquals(0, devicesOfStore(TENANT, STORE),
                "同上：该门店名下也不得留下任何设备行");

        // ---- ②c 对照：同一支设备、同一个门店，用合法操作者必须成功 ----
        DeviceProvisioningResult ok = registerOk(TENANT, DEV_TX, STORE);
        assertEquals(DeviceOutcome.CREATED, ok.outcome(),
                "🛑 换成合法 operator 之后必须成功 —— 否则上面那条『回滚』断言就无法与"
                        + "『这支设备/这个门店本身建不上』区分（比较器必须对称）");
        assertEquals(1, deviceRows(TENANT, DEV_TX),
                "合法路径下 device 行必须真的落库（这是 ②b 的反向对照）");
    }

    // ==================================================================
    // 判据 ③
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("③ 建档两态 + 归档三态：CREATED/ALREADY_EXISTS → RETIRED/ALREADY_RETIRED/NOT_FOUND，且归档是【状态迁移】")
    void the_two_states_and_three_states_are_complete_and_directionally_correct() {
        // ---- 态 1：首次建档（带参数模板，供判据 ⑦ 的反向断言用）----
        assertEquals(DeviceOutcome.CREATED,
                register(TENANT, DEV_STATE, STORE, "杠2", PARAM_TPL, OPERATOR).outcome(),
                "首次建档 = CREATED");

        String[] s0 = deviceState(TENANT, DEV_STATE);
        assertNotNull(s0, "建档后必须能读到这一行");
        assertEquals("active", s0[0], "新建设备必须直接是 active（V18 的 INSERT 字面量）");
        assertEquals("杠2", s0[2], "model 必须原样落库（库层 CHECK 冻结 杠2 / 现有）");
        assertEquals(STORE, s0[3], "store_id 必须指向本次传入的门店 —— "
                + "它决定这台设备的参数下发到哪个门店，写错等于把参数下发到别人的店");
        assertEquals(PARAM_TPL, s0[4],
                "param_template_id 必须原样落库 —— 判据 ⑦ 要反向断言『它不进审计 payload』，"
                        + "若它根本没落库，那条反向断言就是一条永真断言");

        // ---- 态 2：重放同一支（不是冲突）----
        assertEquals(DeviceOutcome.ALREADY_EXISTS,
                registerOk(TENANT, DEV_STATE, STORE).outcome(),
                "重放同一支设备必须返回 ALREADY_EXISTS（幂等命中），"
                        + "而不是把它当成错误或冲突 —— 契约里没有建档端点，故没有状态码可对照，"
                        + "这一态是原语与调用方之间的口径");
        assertEquals(1, deviceRows(TENANT, DEV_STATE),
                "🛑 重放之后该 device_id 必须仍【恰有一行】—— 若变成 2 行，"
                        + "说明 ON CONFLICT 的推断目标没命中主键（那会以运行期错误炸，"
                        + "而一个『先删后插』的实现则会静默出 2 行）");

        // ---- 态 3：归档 ----
        assertEquals(DeviceOutcome.RETIRED, retire(TENANT, DEV_STATE).outcome(),
                "归档必须返回 RETIRED");

        String[] s1 = deviceState(TENANT, DEV_STATE);
        assertEquals("retired", s1[0], "归档后 status 必须是 retired");
        assertFalse(s1[1].isBlank(),
                "🛑 归档必须写 updated_at —— 它是『这台设备最后一次被改动』的唯一载体，"
                        + "而 device 是 D6 下发（可追责）的引用目标，事后要能回答『它是什么时候停用的』");
        assertEquals(1, deviceRows(TENANT, DEV_STATE),
                "🛑🛑 归档之后这一行必须【仍在】—— 归档是状态迁移而不是删行。"
                        + "删掉一行会让历史 device_dispatch 记录失去可追溯对象（判据 ①f 已实测："
                        + "有下发记录时 DELETE 会被 23503 拒），而 D6 的语义恰是『下行、可追责』");

        // ---- 态 4：重放归档 —— 幂等，且 updated_at 【不得被推后】----
        assertEquals(DeviceOutcome.ALREADY_RETIRED, retire(TENANT, DEV_STATE).outcome(),
                "重放归档必须返回 ALREADY_RETIRED");
        assertEquals(s1[1], deviceState(TENANT, DEV_STATE)[1],
                "🛑 重放归档【不得】把 updated_at 推后 —— "
                        + "这条保证必须由库层 UPDATE 的 `status <> 'retired'` 谓词给出，"
                        + "不能依赖一次可能过期的读（并发下两个归档会同时通过那一次读，"
                        + "而第二次会把『最后一次改动时刻』改成一个没有实际改动的时刻）");

        // ---- 态 5：本租户内看不到这一行 ----
        assertEquals(DeviceOutcome.NOT_FOUND, retire(TENANT, DEV_GHOST).outcome(),
                "🛑 措辞刻意是 NOT_FOUND 而不是 NOT_EXISTS：在 FORCE RLS 下，"
                        + "『不存在』与『存在但当前上下文看不见』给出同一个结果，"
                        + "用『不存在』会把后一种情形说成事实");
    }

    // ==================================================================
    // 判据 ④
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("④ 跨租户门店引用必须被拒：函数侧给可读业务错误(P0001)，绕过函数则被外键 23503 拒（不是 42501）")
    void a_cross_tenant_store_reference_is_refused_with_the_right_reason() {
        // ---- ④a 走函数：V18 显式检查门店是否存在【于本租户内】⇒ 可读的业务错误 ----
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> register(TENANT, DEV_CROSS, STORE_OTHER, "杠2", null, OPERATOR),
                "🛑 用租户 A 的身份把设备建到租户 B 的门店上，必须被拒 —— "
                        + "否则一次写错 store_id 就能把一台真实设备的参数下发到别的租户的门店");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 函数侧的显式 RAISE 必须是 P0001(raise_exception) —— "
                        + "若它是 23503，那说明本次拦截发生在外键而不是函数里，"
                        + "『函数先给可读原因、库层再兜底』这条设计就不成立了。异常链="
                        + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("不存在"),
                "🛑 拒绝必须是一条【写明原因】的业务错误（门店 X 在租户 Y 内不存在），"
                        + "而不是一条 PostgreSQL 约束名报错 —— 两者都 fail-closed，"
                        + "区别只在诊断成本。异常链=" + fullCauseMessages(ex));

        assertEquals(0, deviceRows(TENANT, DEV_CROSS),
                "🛑 被拒之后不得留下任何 device 行（RAISE 会使整个函数调用回滚）");

        // ---- ④b 绕过函数（裸 SQL）：库层必须用【复合外键】兜底 ----
        //     🛑 这是本类允许出现裸 INSERT INTO device 的两处之一（另一处见 ①c 的反向形态），
        //        理由正是本判据本身：要证明「绕过函数也拦得住」，那条语句就必须真的绕过函数。
        String state = writeExpectingFailure(TENANT,
                "INSERT INTO device (device_id, tenant_id, store_id, model, status, created_by) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, '杠2', 'active', 'b11-raw-probe')",
                DEV_BARE, TENANT, STORE_OTHER);
        assertNotNull(state,
                "🛑 裸 SQL 在租户 A 的上下文里成功把 device.store_id 指向了租户 B 的门店 —— "
                        + "V16 的跨租户引用完整性已被突破");
        assertEquals("23503", state,
                "🛑 拒绝理由必须是 23503（复合外键 device_store_id_fkey 未命中），实际=" + state
                        + "。🛑 特别要排除 42501 —— 那说明真正的原因是 RLS 拦下了这条 INSERT，"
                        + "于是本判据就没有证明『引用完整性』这件事，只证明了『行归属』"
                        + "（V16 门禁里那条『RLS 只保护行归属、从不保护引用归属』的实测结论）");

        // ---- ④c 对照：同租户的门店必须放行 ----
        writeInTenant(TENANT,
                "INSERT INTO device (device_id, tenant_id, store_id, model, status, created_by) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, '杠2', 'active', 'b11-raw-probe')",
                DEV_BARE, TENANT, STORE);
        assertEquals(1, deviceRows(TENANT, DEV_BARE),
                "🛑 同租户引用必须放行 —— 否则 ④b 的 23503 就无法与"
                        + "『这条语句在任何情况下都失败』区分（比较器必须对称）");
    }

    // ==================================================================
    // 判据 ⑤  —— 本类最重要的一条
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("⑤ 🛑🛑 跨租户撞号必须 RAISE(P0001) 而不是返回 ALREADY_EXISTS —— 照抄 B-10 会造出『返回值在撒谎』的缺陷")
    void a_cross_tenant_device_id_collision_must_raise_not_return_already_exists() {
        // ---- ⑤a 准备：租户 A 先占用这一支 device_id ----
        assertEquals(DeviceOutcome.CREATED, registerOk(TENANT, DEV_SHARED, STORE).outcome(),
                "准备阶段：租户 A 必须先占用 DEV_SHARED");

        // ---- ⑤b 🛑 核心：租户 B 用【同一支 device_id】+ 【自己的门店】建档 ----
        //   🛑 为什么 store 必须换成本租户自己的：register_device 的步骤 (4) 会先校验
        //      「门店在本租户内存在」。若这里仍传租户 A 的门店，会先命中那一条 RAISE，
        //      于是本用例就【根本走不到】撞号判定 —— 一次归因错误的红（这是 V18 自证里
        //      刻意用内层 BEGIN/EXCEPTION 分开 e3 与 e5 的同一个理由）。
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> register(TENANT_OTHER, DEV_SHARED, STORE_OTHER, "杠2", null, OPERATOR),
                "🛑🛑 租户 B 用租户 A 已占用的 device_id 建档竟然【没有抛错】—— "
                        + "这正是 V18 相对 V17 形态的那处实质改动被回退的形态。"
                        + "实测机制：ON CONFLICT (device_id) 的推断目标是【单列全局主键】，"
                        + "别人占用同一 device_id 时 PG 静默走 DO NOTHING 且 ROW_COUNT=0，"
                        + "而本租户上下文里 SELECT 又看不见那一行 ⇒ 只按 ROW_COUNT 判定就会返回"
                        + " ALREADY_EXISTS，而租户 B 手里一行都没有。"
                        + "后果不是报错，而是『返回值说 ALREADY_EXISTS、下游仍然 23503』");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 跨租户撞号的拒绝必须是 P0001（函数里的显式 RAISE），实际="
                        + deepestSqlState(ex) + "。若它是 23503，那说明拦截发生在外键而不是函数里，"
                        + "调用方就只能拿到一条约束名报错，而这条判定的语义"
                        + "（『冲突了但我看不见 ⇒ 它在别的租户名下』）变得不可读。异常链="
                        + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("另一租户"),
                "🛑 拒绝消息必须【写明】它属于另一租户 —— 这是本迁移与 V17 的唯一实质差别所在。"
                        + "少了这几个字，调用方只知道『建不上』，无法区分"
                        + "『device_id 传错了』与『设备被错误调拨』这两件需要完全不同处置的事。"
                        + "异常链=" + fullCauseMessages(ex));

        // ---- ⑤c 🛑 核心的实证形态：本租户里必须【一行都没有】----
        //   这是「返回值在撒谎」这件事唯一可断言的面：声明与事实必须一致。
        assertEquals(0, deviceRows(TENANT_OTHER, DEV_SHARED),
                "🛑🛑 被拒之后租户 B 里不得留下 DEV_SHARED 的任何行 —— "
                        + "这条断言就是『若返回 ALREADY_EXISTS 即撒谎』的机械化形式："
                        + "一个会说出『你已经有这台设备了』的实现，必须能在本租户里读到那一行，"
                        + "而这里读到的是 0");

        // ---- ⑤d 对照一：租户 B 用【自己的】device_id 必须成功 ----
        //   🛑 没有这一条，一个「只要 device_id 在别处存在就抛错」的实现也能过 ⑤b。
        assertEquals(DeviceOutcome.CREATED,
                registerOk(TENANT_OTHER, DEV_OTHER, STORE_OTHER).outcome(),
                "🛑 对照失败：租户 B 用自己的 device_id 建档也不成功 —— "
                        + "那说明 ⑤b 的『抛错』不是撞号判定在起作用，而是这条通路整体坏了"
                        + "（比较器必须对称）");
        assertEquals(1, deviceRows(TENANT_OTHER, DEV_OTHER), "对照路径下 device 行必须真的落库");

        // ---- ⑤e 对照二：租户 A 重放【自己那一支】必须仍是 ALREADY_EXISTS ----
        //   🛑 没有这一条，一个「全局拒绝重复 device_id」的实现也能过 ⑤b ——
        //      而那会把幂等语义一起打死。
        assertEquals(DeviceOutcome.ALREADY_EXISTS,
                registerOk(TENANT, DEV_SHARED, STORE).outcome(),
                "🛑 对照失败：本租户重放自己那一支竟然不返回 ALREADY_EXISTS —— "
                        + "撞号判定必须【区分】『是我的』与『是别人的』，而不是一律拒绝");

        // ---- ⑤f 可见性方向：跨租户不可见（本读法不得成为存在性探测口）----
        //   🛑 这四条合起来是一张完整的 2×2 矩阵（上下文 × 设备归属），缺一条就退化成单方向的断言：
        //        A 的上下文看 A 的设备 → true      A 的上下文看 B 的设备 → false
        //        B 的上下文看 A 的设备 → false     B 的上下文看 B 的设备 → true
        //      对角线必须全 true、反对角线必须全 false。只断言反对角线的一半，
        //      一个「恒返回 false」的实现也能过 —— 而那会让 D6 永远无法下发。
        assertTrue(service.isDispatchable(TENANT, UUID.fromString(DEV_SHARED)),
                "租户 A 里 DEV_SHARED 必须可被 D6 引用（对角线）");
        assertFalse(service.isDispatchable(TENANT_OTHER, UUID.fromString(DEV_SHARED)),
                "🛑 租户 B 里【租户 A 的】device_id 必须不可引用 —— "
                        + "若这里为 true，说明 isReferenceable 的上下文机制失效，"
                        + "它会变成一个跨租户存在性探测口（别人有哪些 device_id 可被枚举）");
        assertFalse(service.isDispatchable(TENANT, UUID.fromString(DEV_OTHER)),
                "🛑 反向自证：租户 A 里也【看不见租户 B 的】设备 —— "
                        + "同一读法必须在两个方向上都给出 false，否则上面那条隔离断言只是单向的"
                        + "（一个『只对本地设备返回 true』的实现要过这一条，"
                        + "就必须真的做对了上下文注入，而不能靠某个 if 分支蒙混）");
        assertTrue(service.isDispatchable(TENANT_OTHER, UUID.fromString(DEV_OTHER)),
                "🛑 自证的另一半（本条守住上面那条不退化）：租户 B 看【自己的】设备必须为 true。"
                        + "没有这一条，一个『恒返回 false』的实现能让两条 false 断言全绿 —— "
                        + "而那意味着 D6 在生产上永远拿不到可引用的设备");

        // ---- ⑤g 元层：设计意图必须被【代码】守住，而不只写在注释里 ----
        //   🛑 这一条守的是「用缺失表达不该继续」：DeviceOutcome 刻意没有
        //      OWNED_BY_OTHER_TENANT 一态 —— 一态一旦被登记，就等于允许调用方
        //      把它当成「可以继续流程的结果」。若有人把它加回来，这里会先红。
        BizException unregistered = assertThrows(BizException.class,
                () -> DeviceOutcome.fromDb("OWNED_BY_OTHER_TENANT", "register"),
                "🛑 DeviceOutcome 竟然接受了 OWNED_BY_OTHER_TENANT —— "
                        + "那意味着跨租户撞号被改成了【返回值】，调用方可以把它当成正常结果继续下发，"
                        + "而紧接着的 INSERT INTO device_dispatch 仍会以 23503 失败。"
                        + "本枚举刻意用【缺失】来表达『这个情形不该被继续』");
        assertTrue(unregistered.getDevMessage().contains("未登记"),
                "🛑 未登记的返回值必须 fail-closed 地抛错（而不是落到某个默认态）—— "
                        + "落默认态会让一次口径漂移静默通过，而漂移的那一态恰好可能是"
                        + "『没有写入』被当成『写入成功』。实际消息=" + unregistered.getDevMessage());

        assertTrue(DeviceOutcome.CREATED.mutatedData() && DeviceOutcome.RETIRED.mutatedData(),
                "CREATED / RETIRED 必须被登记为『真的改动了数据』");
        assertFalse(DeviceOutcome.ALREADY_EXISTS.mutatedData()
                        || DeviceOutcome.ALREADY_RETIRED.mutatedData(),
                "🛑 ALREADY_EXISTS / ALREADY_RETIRED 必须【不】算改动数据 —— "
                        + "否则审计会记录一次并不存在的写入，而这类假记录事后无法被察觉");
        assertTrue(DeviceOutcome.NOT_FOUND.notVisible(),
                "NOT_FOUND 必须被登记为『看不到目标行』（见其措辞说明）");
    }

    // ==================================================================
    // 判据 ⑥
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("⑥ 归档行仍占据主键 ⇒ 重放建档必须 ALREADY_EXISTS（主键复用是一次【显式】的人工决定）")
    void a_retired_row_still_occupies_the_primary_key() {
        // 前置：判据 ③ 已把 DEV_STATE 归档成 retired。这里不重建，直接断言那件事的后果。
        String[] state = deviceState(TENANT, DEV_STATE);
        assertNotNull(state, "前置失败：DEV_STATE 应仍在库里（归档是状态迁移）");
        assertEquals("retired", state[0], "前置失败：DEV_STATE 应是 retired（判据 ③ 的产物）");

        // ---- 重放建档：必须 ALREADY_EXISTS，而不是 CREATED ----
        assertEquals(DeviceOutcome.ALREADY_EXISTS,
                register(TENANT, DEV_STATE, STORE_B, "现有", null, OPERATOR).outcome(),
                "🛑 归档行仍占据 device_pkey ⇒ 重放建档必须返回 ALREADY_EXISTS。"
                        + "🛑 注意本次【刻意换了门店与型号】（STORE_B / 现有）：若实现里存在"
                        + "『状态不是 active 就当作不存在』这类顺手补全，它会在这一条上暴露出来 —— "
                        + "因为那种实现会返回 CREATED 并静默推翻一个已归档的事实");
        assertEquals(1, deviceRows(TENANT, DEV_STATE),
                "🛑 且必须仍然只有一行 —— 不能因为『重建』而多出一行");
        assertEquals("retired", deviceState(TENANT, DEV_STATE)[0],
                "🛑 且 status 必须仍是 retired —— 重放建档【不得】把已归档的设备偷偷复活。"
                        + "若这里变成 active，那就等于一次『顺手重建』静默完成了一次主键复用，"
                        + "而 device 是 D6 下发（可追责）的引用目标："
                        + "历史下发记录会指向一台『看起来一直是活的』设备");
        assertEquals(STORE, deviceState(TENANT, DEV_STATE)[3],
                "🛑 且 store_id 不得被本次调用的 STORE_B 覆盖 —— 同上，幂等命中不产生任何写入");
    }

    // ==================================================================
    // 判据 ⑦
    // ==================================================================

    @Test
    @Order(7)
    @DisplayName("⑦ 幂等重放【也】写审计；且 payload 只含可公开摘要（model 可、param_template_id 不可）")
    void idempotent_replays_also_leave_an_audit_trail_and_the_payload_stays_public_safe() {
        // ---- ⑦a 首次建档（带参数模板）：审计 1 条 ----
        //   🛑 载体是 DEV_AUDIT（本判据独享），不是 DEV_TX —— 理由逐字写在常量声明处。
        assertEquals(0, deviceRows(TENANT, DEV_AUDIT),
                "前置失败：DEV_AUDIT 已在库里 ⇒ 下面那条 CREATED 断言会以【归因错误】的方式失败"
                        + "（看上去像『建档坏了』，实际是『载体被占用』）");
        DeviceProvisioningResult first = register(TENANT, DEV_AUDIT, STORE, "杠2", PARAM_TPL, OPERATOR);
        assertEquals(DeviceOutcome.CREATED, first.outcome(),
                "首次建档必须 CREATED —— 若返回 ALREADY_EXISTS，说明本判据的载体"
                        + "被前一个用例占用了（那是一次归因错误的红：问题在用例隔离，不在建档逻辑）");
        String firstPayload = assertAudit(first.auditId(), ACTION_REGISTERED, DEV_AUDIT,
                "\"mode\":\"CREATED\"");

        // ---- ⑦b 重放：库层 ON CONFLICT DO NOTHING 不报错、不改数据、返回一个正常值 ----
        int before = countAudit(ACTION_REGISTERED, DEV_AUDIT);
        DeviceProvisioningResult replay = register(TENANT, DEV_AUDIT, STORE, "杠2", PARAM_TPL, OPERATOR);
        assertEquals(DeviceOutcome.ALREADY_EXISTS, replay.outcome());
        String replayPayload = assertAudit(replay.auditId(), ACTION_REGISTERED, DEV_AUDIT,
                "\"mode\":\"ALREADY_EXISTS\"");
        assertEquals(before + 1, countAudit(ACTION_REGISTERED, DEV_AUDIT),
                "🛑 幂等重放必须【恰好】新增一条审计。理由是 B-7 那条裁定的更强形式："
                        + "库层 DO NOTHING 不报错、不改数据、返回一个正常值 —— "
                        + "『有人试图重复建一台设备』这件事若不留痕，在系统里就完全不可见。"
                        + "那可能是一次误传的 device_id，也可能是一次真实的重复建档 —— "
                        + "两者都值得在事后被看见。一句话边界："
                        + "幂等保证的是『不重复建』，不是『不重复记录』");

        // ---- ⑦c payload 的 mutated 必须如实反映本次是否真的改了数据 ----
        assertTrue(firstPayload.contains("\"mutated\":true"),
                "首次建档的 payload 必须写明 mutated=true（本次真的建了一行）。实际=" + firstPayload);
        assertTrue(replayPayload.contains("\"mutated\":false"),
                "🛑 重放的 payload 必须写明 mutated=false —— "
                        + "若它是 true，那审计就记录了一次【并不存在的写入】，"
                        + "而这类假记录事后无法被察觉。实际=" + replayPayload);

        // ---- ⑦d 🛑 反向断言：经营信息【不得】进审计 payload ----
        //   🛑 这两条的边界不同，必须分开说清（否则下一个人会以为「都是不该写」）：
        //     · param_template_id —— 是经营信息（『这个租户在用哪一套参数模板』），
        //       与 B-10 不写 vendor 同理 ⇒ 不得进 payload；
        //     · model —— 只有两个取值（杠2 / 现有），是不可反推经营细节的分类值 ⇒ 可以进。
        assertFalse(firstPayload.contains(PARAM_TPL),
                "🛑 审计 payload 里出现了 param_template_id —— audit_log 是被【全租户可读】的"
                        + "（它没有 RLS，那是哈希链连续性的要求，敞口已登记）。"
                        + "把这个值写进去，等于把『这个租户在用哪套参数模板』这类经营信息"
                        + "暴露给每一个能读审计的租户。要查设备细节应查 device 表本身，那里有 RLS。"
                        + "实际 payload=" + firstPayload);
        assertTrue(firstPayload.contains("杠2"),
                "🛑 反过来，model 必须【在】payload 里 —— 若连它也没有，"
                        + "上面那条『不得含 param_template_id』就退化成一条永真断言"
                        + "（一个什么都不写的实现同样能让它通过）。实际=" + firstPayload);

        // ---- ⑦e 归档侧：动作名、目标类型必须正确 ----
        DeviceProvisioningResult retired = retire(TENANT, DEV_AUDIT);
        // 归档前 DEV_AUDIT 是 active（本次 ⑦a 刚建的），故本次应为 RETIRED
        assertEquals(DeviceOutcome.RETIRED, retired.outcome(),
                "归档一台 active 设备必须返回 RETIRED");
        assertAudit(retired.auditId(), ACTION_RETIRED, DEV_AUDIT, "\"mode\":\"RETIRED\"");

        DeviceProvisioningResult retiredAgain = retire(TENANT, DEV_AUDIT);
        assertEquals(DeviceOutcome.ALREADY_RETIRED, retiredAgain.outcome());
        assertAudit(retiredAgain.auditId(), ACTION_RETIRED, DEV_AUDIT, "\"mode\":\"ALREADY_RETIRED\"");
    }

    // ==================================================================
    // 判据 ⑧
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("⑧ 上下文一致性守卫：已持有别的租户上下文时，建档必须被拒（不允许静默改写）")
    void an_existing_foreign_tenant_context_blocks_the_provisioning() {
        assertEquals(0, deviceRows(TENANT, DEV_CTX), "前置失败：DEV_CTX 应尚未建档");

        // 🛑 在外层事务里先设一个【别的】租户的上下文，再在同一事务内调 register。
        //    DeviceService 的 TransactionTemplate 传播缺省为 REQUIRED ⇒ 它会【加入】本事务，
        //    DeviceLedger.register 同理，故 V18 的 current_setting('app.tenant_id') 能读到那个值。
        //    🛑 这正是『跨租户写错台账的最短路径』：set_config(..., true) 是事务级设置，
        //       不会在函数入口自动重置 —— 若在此静默覆盖，函数之后的全部写入都会落在新租户名下，
        //       而调用方仍以为在旧租户名下。
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> tx.execute(status -> {
                    jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT_OTHER + "'");
                    return service.register(new DeviceRecord(TENANT, UUID.fromString(DEV_CTX),
                            UUID.fromString(STORE), "杠2", null), OPERATOR);
                }),
                "🛑 已持有租户上下文 " + TENANT_OTHER + " 时，以租户 " + TENANT
                        + " 的身份建档竟然成功了 —— 上下文守卫失效，"
                        + "那意味着一次调用可以静默地把写入落到另一个租户名下");

        assertEquals("P0001", deepestSqlState(ex),
                "🛑 拒绝必须是 P0001（V18 的显式 RAISE），实际=" + deepestSqlState(ex)
                        + "。若它是 42501，那说明拦住它的是 RLS 而不是那道上下文守卫 —— "
                        + "两者都 fail-closed，但『守卫发现上下文不一致』与"
                        + "『RLS 恰好挡住了』是完全不同的结论。异常链=" + fullCauseMessages(ex));
        assertTrue(fullCauseMessages(ex).contains("不一致"),
                "🛑 拒绝必须是【写明理由】的一条错误（本事务已持有租户上下文 X，"
                        + "与本次调用传入的租户 Y 不一致），而不是一条随机的 SQL 失败。异常链="
                        + fullCauseMessages(ex));

        assertEquals(0, deviceRows(TENANT, DEV_CTX),
                "被拒之后本租户内不得留下该设备的任何行");

        // ---- 对照：换一个【干净的】事务（无既有上下文）必须成功 ----
        assertEquals(DeviceOutcome.CREATED, registerOk(TENANT, DEV_CTX, STORE).outcome(),
                "🛑 无既有上下文时同一调用必须成功 —— 否则上面那条『被拒』就无法与"
                        + "『这支设备/这个门店本身建不上』区分");
    }

    // ==================================================================
    // 判据 ⑨（纯静态：形态与载体 —— 不连库，但没有它前八条管不住「形态漂移」）
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("⑨ 形态：device 通路是【无 HTTP 映射的组件】（运维通路），V18 两个原语在位，且 status='maintenance' 不可达")
    void the_device_path_is_a_component_without_http_mapping_and_the_primitives_are_in_place() throws IOException {
        Path root = skeletonRoot();

        // ---- (a) 四件套必须在位 ----
        List<String> rels = List.of(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/domain/DeviceRecord.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/domain/DeviceOutcome.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/repository/DeviceLedger.java",
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/device/service/DeviceService.java");
        for (String rel : rels) {
            assertTrue(Files.isRegularFile(root.resolve(rel)),
                    "设备建档通路缺件：" + rel
                            + "\n若这条通路被有意移除，请同时：把 device 移回 ProvisioningBoundaryGateTest "
                            + "的 NOT_PROVISIONED 账、恢复障碍登记，并同步文档。"
                            + "🛑 禁止只删代码不改账 —— 那会让两账与代码脱节；"
                            + "🛑 更禁止『只删代码不改账、还把那条判据放宽』—— "
                            + "那会让 device 的零写入方缺口重新静默回来（D6 又会 500）");
        }

        // ---- (b) 四个类都不得带 HTTP 映射注解（本判据的核心）----
        // 只剥注释：注解要在【代码态】才算数（注释里提到 @RestController 是文档，不是暴露）。
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
                "设备建档通路被暴露成了 HTTP 端点 —— 这与契约化决策相悖。"
                        + "契约 40 个 path 里【有意】没有任何『创建设备』端点："
                        + "唯一的设备相关 path 是 D6 POST /device-dispatches，而它的 summary 逐字是"
                        + "『D6 设备参数下发（需方案已审核）』—— 操作的是【已存在的】门店设备，"
                        + "不是『创建设备』。给它一个对外端点属契约 MAJOR 变更，"
                        + "且要先回答契约回答不了的问题：『谁有权往门店建设备台账』。命中：" + offenders);

        // ---- (c) 自证：上面那条检查真的有判别力 ----
        // 换一个【本域外但同形态】的正样本，证明 marker 匹配是活的 ——
        // 否则「零命中」可能只是扫描器坏了。
        String positive = stripComments(Files.readString(root.resolve(
                "dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/controller/PlanController.java"),
                StandardCharsets.UTF_8));
        assertTrue(positive.contains("@RestController") && positive.contains("@PostMapping"),
                "自证样本失效：PlanController 应当同时带 @RestController 与 @PostMapping。"
                        + "若这条不成立，本判据的『零命中』就没有判别力");

        // ---- (d) V18 的两个原语在位 ----
        Path migration = root.resolve("dy-app/src/main/resources/db/migration/"
                + "V18__device_provisioning_primitive.sql");
        assertTrue(Files.isRegularFile(migration), "V18 设备建档迁移不存在：" + migration);
        String v18 = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(v18.contains("FUNCTION register_device"),
                "V18 里找不到 register_device 函数定义 —— 迁移的载体变了，本判据的前提不成立");
        assertTrue(v18.contains("FUNCTION retire_device"),
                "V18 里找不到 retire_device 函数定义 —— 它是『归档是状态迁移而非删除』"
                        + "这条不变量的可断言载体，缺了它就只能靠人看代码");
        // 🛑 跨租户撞号的封堵点必须在函数体里逐字可见 —— 它没有被测试「顺便」覆盖到的风险，
        //    因为它只在【特定数据状态】（别人先占用了同一个 device_id）下才被触发。
        assertTrue(v18.contains("已被【另一租户】占用"),
                "🛑 V18 里找不到跨租户撞号的 RAISE 文本 —— 这正是本迁移相对 V17 的唯一实质改动"
                        + "（判据 ⑤ 的靶心）。若它被删掉，register_device 会在『冲突了但我看不见』时"
                        + "返回 ALREADY_EXISTS，而本租户一行都没有 —— 返回值在撒谎");

        // ---- (e) 🛑 'maintenance' 必须在生产代码里不可达 ----
        //   device.status 的 CHECK 冻结了三个值（active / maintenance / retired），
        //   而 V18 只暴露 register / retire 两个原语 ⇒ 'maintenance' 在任何生产路径上都不可达。
        //   🛑 这不是「少做了一半」，而是本轮的显式取舍：故意留一个不可达的合法状态，
        //      好让它将来被接上时是一次【可被看见】的新增（本判据会先红），
        //      而不是某个实现顺手写进去的一个无人知晓的取值。
        List<String> maintenanceHits = new ArrayList<>();
        try (var walk = Files.walk(root.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/device"))) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
            for (Path p : files) {
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (code.contains("'maintenance'") || code.contains("\"maintenance\"")) {
                    maintenanceHits.add(root.relativize(p).toString().replace('\\', '/'));
                }
            }
        }
        assertTrue(maintenanceHits.isEmpty(),
                "🛑 device 包的生产代码里出现了 maintenance 字面量 —— "
                        + "而 V18 刻意【没有】暴露 enter_maintenance / leave_maintenance 两个原语，"
                        + "故这一取值在任何生产路径上都应当不可达。若这是有意的范围扩张，"
                        + "正确顺序是：先在 V18 里加原语 + 加自证 + 加门禁用例 → 再落 Java → "
                        + "最后同步契约与文档。命中：" + maintenanceHits);

        // 自证 (e) 有判别力：正样本必须是【存在且能被检出】的那种形态
        String deviceLedger = stripComments(Files.readString(root.resolve(rels.get(2)),
                StandardCharsets.UTF_8));
        assertTrue(deviceLedger.contains("STATUS_RETIRED"),
                "自证样本失效：DeviceLedger 应当带 STATUS_RETIRED 常量。"
                        + "若这条不成立，说明本判据的字符串扫描没读到预期内容，"
                        + "那么 (e) 的『零命中』就没有判别力");
    }

    // ==================================================================
    // 五、辅助断言
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
     * <p>🛑 按 {@code id} 精确取（而不是「按 action 取最新一条」）：后者在同一次运行里
     * 有多条同 action 审计时会取错行，于是「payload 里有没有某个模式」这条断言
     * 就会在别的那一行上成立 —— 一个自己造出来的假通过。
     * <p>🛑 {@code actor} 必须是调用方提供的操作者，不能是
     * {@code 'device-registration'} 这种系统占位值 —— 后者是仓储在 {@code created_by}
     * 里写的默认值，它是「谁改的库」而不是「谁做的这个决定」。
     */
    private static String assertAudit(String auditId, String action, String targetId,
                                      String payloadFragment) {
        assertNotNull(auditId, "建档/归档回执必须带上本次留痕的审计 id —— 它是事后对账的唯一锚点");
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
        assertEquals(TARGET_TYPE, r[1], "target_type 必须是 device");
        assertEquals(targetId, r[2], "target_id 必须是本次操作的设备 id");
        assertEquals(OPERATOR, r[3],
                "审计 actor 必须是调用方提供的操作者，不是 'device-registration' 这种系统占位值 —— "
                        + "审计里 actor 是唯一有追责含义的一栏");
        assertTrue(r[4].contains(payloadFragment),
                "payload 必须含 " + payloadFragment + "（本次走的是哪条路径）。实际=" + r[4]);
        return r[4];
    }

    // ==================================================================
    // 六、静态工具（判据 ⑨ 用；形态与 ProvisioningBoundaryGateTest 逐字同款）
    // ==================================================================

    /**
     * 剥离 Java 块注释与行注释，<b>但保留字符串字面量</b>。
     *
     * <p>🛑 不能只做正则替换：本仓注释里大量出现中文与反引号，
     * 且 {@code DeviceLedger} / {@code DeviceService} 的 Javadoc 里逐字写着
     * {@code @RestController} 之类的注解名（作为「刻意的形态」的说明）。
     * 必须真正按状态机扫，才能区分「注释里提到」与「代码里引用」——
     * 否则判据 ⑨(b) 会把一段正确的文档判成一次越界暴露。
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
                // 🛑 顺序 = 子 → 父。device_dispatch 引用 device/store/plan；plan_review 引用 plan/staff；
                //    plan 引用 customer。顺序错会以 23503 失败，而那时表象是「清理逻辑坏了」。
                j.update("DELETE FROM device_dispatch WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM plan_review WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM plan WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM device WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM customer WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM staff WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM store WHERE tenant_id = ?::uuid", tid);
                j.update("DELETE FROM region WHERE tenant_id = ?::uuid", tid);
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
                assertTrue(j.queryForObject("SELECT count(*) FROM device", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有设备残留（FORCE RLS 会静默删 0 行，"
                                + "这正是最容易被忽略的一类残留）");
                assertTrue(j.queryForObject("SELECT count(*) FROM device_dispatch", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有下发记录残留");
                assertTrue(j.queryForObject("SELECT count(*) FROM store", Integer.class) == 0,
                        "清理后租户 " + tid + " 仍有门店残留");
            });
        }

        // ---- 审计【不删】，改为断言证据确实留下 ----------------------------
        // 🛑 audit_log 是一条全局单链，删掉中间任何一行会让【它的后继】prev_hash 对不上，
        //    从而为后续每一次 verifyChain() 留下一个永久的假断口。
        assertTrue(serviceCalls > 0,
                "自证失败：serviceCalls 为 0，说明本类从未真正走过建档/归档路径 —— "
                        + "那么上面所有『必须有审计』的断言都没有载体");
        Integer regRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REGISTERED, DEV_AUDIT);
        assertTrue(regRows != null && regRows >= 2,
                "审计条目必须留下且不得被清理（action=" + ACTION_REGISTERED
                        + " target_id=" + DEV_AUDIT + "，判据 ⑦ 至少产生两条：CREATED + 重放）。"
                        + "实际条数=" + regRows);
        // 🛑 这里刻意用 DEV_AUDIT 而不是 DEV_TX：DEV_TX 的 DEVICE_REGISTERED 只有判据 ② 里
        //    【成功那一次】的 1 条（失败那一次因 22001 回滚、不落库），而它后来被归档——
        //    归档写的是 DEVICE_RETIRED。用 DEV_TX 去断言 >= 2 会得到一条恒假的断言，
        //    而那与「审计证据留下了吗」这个真问题无关。
        Integer txRegRows = j.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                Integer.class, ACTION_REGISTERED, DEV_TX);
        assertTrue(txRegRows != null && txRegRows >= 1,
                "判据 ② 的成功对照那次必须留下 1 条 DEVICE_REGISTERED（失败那一次不落库）—— "
                        + "这正好反向印证了『审计失败 ⇒ 建档回滚』：回滚的提交里没有审计行。"
                        + "实际条数=" + txRegRows);
    }
}