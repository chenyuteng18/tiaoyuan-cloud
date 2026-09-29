package com.diaoyuanyun.dy.app.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-3 证人套件 · 契约域 G 的<b>写路径角色矩阵</b>（G1 代录 / G3 挽留 / G5 回执）。
 *
 * <h1>为什么必须新开一套（而不是往 {@code RefundDomainGEndpointsE2ETest} 里加断言）</h1>
 *
 * <p>既有的 S2-5 套件已经覆盖了两件不同的事：
 * <ul>
 *   <li>G2（读）的 <b>4 角色可调矩阵</b>（{@code meridian/manager/area/hq} 都必须走过装配链）；</li>
 *   <li><b>客户</b>在 G1/G3/G4/G5 四个写端点上一律 403 且回显 {@code denied_fields}；</li>
 *   <li><b>调理师</b>（therapist）在 G2 上 403。</li>
 * </ul>
 * 但<b>"契约点名可调的 staff 角色，在三个写端点上是否真的都能调"这件事从未被断言过</b> ——
 * 客户侧与调理师侧是"必须被拒"的方向，而
 * {@code meridian/manager/area/hq} 在 <b>写</b>端点上的放行方向是空白的。
 *
 * <p>这是一条<b>真实且已被证明会复发</b>的盲区。S2-4 的 55 条服务层单测与整个套件曾全绿，
 * 而线上退款域<b>整体不可用</b>：{@code PermissionRegistry} 里一个 {@code refund:*} 都没登记
 * ⇒ {@code hasPermission} 对全部角色恒 false ⇒ 契约声明可调的端点对全部 staff 一律 403。
 * 那个缺陷正是"用真 JWT 发真 HTTP"才看得见的（见 S2-5 类注释）。
 *
 * <p>故本套件的存在理由是：把<b>写</b>路径的同一个矩阵补齐 ——
 * 并且用"走到了哪一层"这一判据（他走过了权限层与可见性层、一路到业务层），
 * 而不是"拿到 200"（那需要真的落一张工单，引入底数据与清理，且证明力更弱 ——
 * 200 无法排除"他被放行了但业务逻辑恰好走了别的分支"）。
 *
 * <h1>🛑 三个端点的判据【必然不同】—— 这是本套件第一次运行后修正的核心结论</h1>
 * <pre>
 *  G1 创建端点（无工单 ID 可指） → 判据 200（+ 回显 refund_id）
 *  G3 挽留 / G5 回执（按 ID 操作） → 判据 404·3001（查无此单，说明走过了全部三层）
 * </pre>
 * 初版对三个端点统一断言 404，那是<b>错的</b>：G1 在"没有任何工单"的情况下也能成功立案，
 * 断言它 404 会把一次正确行为判成缺陷。**归一化的判据比没有判据更坏** ——
 * 它会逼人去改正确的代码。这正是本套件第一次运行（2026-09-27）暴露的第一件事。
 *
 * <h1>🛑 本套件还顺带抓出了三个真缺陷（均为"走过装配链"才可见）</h1>
 * <ol>
 *   <li><b>G1 不校验引用存在性</b> ⇒ 不存在的 {@code customer_id} 让外键裸抛
 *       ⇒ <b>500·9001</b>，而契约明列 <b>400·1001</b>。端侧会把"输入错误"
 *       当成"服务端故障"去重试。已修：服务层加 ⓪ 存在性校验（{@code RefundSubjectVerifier}）。</li>
 *   <li><b>畸形请求体一律 500</b> ⇒ {@code analysis} 传对象（端侧最自然的写法）触发
 *       {@code HttpMessageNotReadableException} ⇒ <b>500·9001</b>。
 *       这是 45 端点<b>共有</b>的缺陷。已修：全局处理器补 400·1001 映射，
 *       且 G3 的 JSON 字段改收 {@code JsonNode}。</li>
 *   <li><b>G5「推送失败」态 100% 不可用</b> ⇒ 控制器恒传 {@code pushed_at=now()}，
 *       违反库层 CHECK『非已推送 ⇒ pushed_at IS NULL』⇒ 恒 422·5001
 *       ⇒ 覆盖率分母缺一态（P0-19 点名的那一类）。已修：仅在成功时给 {@code pushed_at}。</li>
 * </ol>
 * 这三条都<b>不可能</b>被服务层单测发现：单测直调服务层、自己构造入参，
 * 永远不经过控制器的参数传递与全局异常处理链。只有真 JWT + 真 HTTP 才看得见。
 *
 * <h1>🛑 为什么 G4 不在本矩阵里</h1>
 * G4（审批）的 {@code x-callable-roles} 是 {@code [admin]} 而<b>不含 meridian</b>，
 * 且 PRD §2.2 逐字「区域督导可见、<b>不审批</b>」。G4 的可调性是"权限层过 + 白名单闸再拒"
 * 的两段式，S2-5 已就 area 那一侧立过专门断言（{@code area_supervisor_passes_the_permission_layer_on_g4}）。
 * 把 G4 混进本矩阵会把"四个角色都该通过"这个统一判据破坏掉。
 *
 * <h1>落库范围（🛑 不是零落库 —— 初版注释写错了）</h1>
 * G3/G5 用<b>不存在的工单 UUID</b>（{@link #ABSENT_REFUND}），故它们零落库、可反复跑。
 * 但 <b>G1 正向矩阵会真的立案 4 张</b>（每角色一张）—— 这是 G1 作为创建端点的固有代价，
 * 换不来"零落库"。故本套件的 {@code cleanup()} 以 <b>{@code customer_id = S_CUSTOMER} 为边界</b>
 * 划定清理范围（本套件的每个 G1 探针都引用同一个自建客户，故"本租户内引用该客户的工单"
 * 恰等于"本套件创建的工单"），并<b>先子后父</b>删除三张从属表。
 * 这比"按 tenant 全删"精确，也比"逐条登记 ID"不易漏（矩阵在循环里创建，登记点容易漏）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("C-3 · 契约域 G 写路径（G1/G3/G5）角色矩阵真请求回归")
class RefundWritePathMatrixE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    /** 本套件自建租户（与既有 E2E 零交集，便于精确清理）。 */
    private static final String TENANT = "e2e00000-0000-0000-0000-00000000c3c3";

    /** 必然不存在的工单 ID —— 判据载体。 */
    private static final String ABSENT_REFUND = "e2e00000-0000-0000-0000-0000000000cc";

    private static final String S_REGION = "e2e00000-0000-0000-0000-0000000000c0";
    private static final String S_STORE = "e2e00000-0000-0000-0000-0000000000c1";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000c2";
    private static final String S_CUSTOMER = "e2e00000-0000-0000-0000-0000000000c3";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource springDataSource;

    @BeforeAll
    void seed() {
        dataSource = springDataSource;
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-C3-退款写矩阵') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        // 🛑 本套件必须种下 region / store / customer 三行 —— 它们在 C-3 之前【不需要】，
        //    因为 G1 当时不校验引用存在性；C-3 给 G1 补上该校验后，
        //    "不存在的客户/门店 ⇒ 400·1001"成了契约行为，而本套件的正向矩阵
        //    需要一次【能走到业务层】的请求（否则 400 会掩盖 404/403 的判据）。
        //
        //    这也解释了为什么这三个 ID 必须与 TENANT 同租户：
        //    存在性查询走 FORCE RLS，跨租户的 customer 在库层不可见 ⇒ 会被判"不存在"。
        inTenant(() -> {
            jdbc.update("INSERT INTO region (region_id, tenant_id, name) "
                            + "VALUES (?::uuid, ?::uuid, 'C3区域') ON CONFLICT DO NOTHING",
                    S_REGION, TENANT);
            jdbc.update("INSERT INTO store (store_id, tenant_id, region_id, name, franchise_type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, 'C3门店', '直营') ON CONFLICT DO NOTHING",
                    S_STORE, TENANT, S_REGION);
            // 🛑 列名是 customer.id，【不是】 customer.customer_id ——
            //    本表是全库唯一用裸 id 作主键的表（其余实体都是 <实体>_id）。
            jdbc.update("INSERT INTO customer (id, tenant_id, name, status) "
                            + "VALUES (?::uuid, ?::uuid, 'C3客户', 'active') ON CONFLICT DO NOTHING",
                    S_CUSTOMER, TENANT);
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, store_id, role) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING",
                    S_STAFF, TENANT, S_STORE);
            return null;
        });
    }

    @AfterAll
    void cleanup() {
        inTenant(() -> {
            // 一、清掉 G1 真实落下的工单及其从属行。
            //
            // 🛑 范围用 customer_id = S_CUSTOMER 划定 —— 这是本套件的【精确边界】：
            //    本套件的每一个 G1 探针（正向矩阵 4 张 + SelfProof 1 张）
            //    都引用同一个自建客户，故"本租户内引用该客户的工单"恰等于
            //    "本套件创建的工单"，一条不多一条不少。
            //
            //    🛑 为什么不用"按 tenant 全删"：那是一条过宽语句 ——
            //    一旦将来有人在别处用同一个 tenant 常量做探针，它会把对方的行删掉，
            //    而症状是"另一个套件偶发 404"，排查方向完全被带偏。
            //    为什么也不用"逐条登记 ID"：矩阵用例在循环里创建，
            //    登记点容易漏（初版即漏掉矩阵的 4 张）；按客户边界划定不依赖登记。
            //
            // 🛑 顺序必须先子后父：三张从属表都以 refund_id 为外键。
            jdbc.update("DELETE FROM refund_statement WHERE tenant_id = ?::uuid "
                    + "AND refund_id IN (SELECT refund_id FROM refund WHERE customer_id = ?::uuid)",
                    TENANT, S_CUSTOMER);
            jdbc.update("DELETE FROM retention WHERE tenant_id = ?::uuid "
                    + "AND refund_id IN (SELECT refund_id FROM refund WHERE customer_id = ?::uuid)",
                    TENANT, S_CUSTOMER);
            jdbc.update("DELETE FROM refund_receipt WHERE tenant_id = ?::uuid "
                    + "AND refund_id IN (SELECT refund_id FROM refund WHERE customer_id = ?::uuid)",
                    TENANT, S_CUSTOMER);
            jdbc.update("DELETE FROM refund WHERE tenant_id = ?::uuid AND customer_id = ?::uuid",
                    TENANT, S_CUSTOMER);

            // 二、清掉自建的种子行。🛑 删除顺序必须【先子后父】：
            //    staff / customer 引用 store，store 引用 region。反之会以一次 23503
            //    （外键违例）失败 —— 而那是清理阶段的 500 形态失败，会掩盖真正的测试结论。
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid AND staff_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM customer WHERE tenant_id = ?::uuid AND id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM store WHERE tenant_id = ?::uuid AND store_id::text LIKE 'e2e00000-%'",
                    TENANT);
            jdbc.update("DELETE FROM region WHERE tenant_id = ?::uuid AND region_id::text LIKE 'e2e00000-%'",
                    TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static <T> T inTenant(java.util.function.Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // 一、矩阵：契约点名可调的 staff 角色必须【走过装配链】（404 而非 403）
    // ==================================================================

    @Nested
    @DisplayName("一 · 三个写端点 × {meridian, manager, area, hq} → 404·3001")
    class CallableStaffMatrix {

        /**
         * 🛑 判据是「<b>404 而不是 403</b>」。
         *
         * <p>角色带的是<b>一个不存在的工单 ID</b>。若装配链某一环失守（权限码未登记 /
         * 可见性档位缺失 / 上下文未写入），请求会<b>停在半路</b>返回 403；
         * 而"走过全部三层、一路到业务层"的表现是 404（查无此单）。
         * 故 404 是"他真的被放行了"的<b>唯一可判定证据</b> ——
         * 只断言"非 403"会把 401/500 也算通过。
         *
         * <p>反之，若拿到 403，最常见成因仍是"权限码没登记进 {@code PermissionRegistry}"
         * （S2-4 曾整体栽在这里），其次是可见性档位表没登记该角色。
         */
        @Test
        @DisplayName("G1 代录 × 4 角色 → 200（契约 x-callable-roles: [meridian, admin]）")
        void g1_is_reachable_by_all_contract_callable_staff_roles() {
            // 🛑 G1 与 G3/G5 的判据【必然不同】，这不是笔误：
            //    G1 是【创建】端点 —— 它没有工单 ID 可指，故不存在"查无此单 ⇒ 404"这条路。
            //    它的成功形态是 200 + 一个新 refund_id。故本矩阵对 G1 用 200 作判据。
            //
            //    曾用统一判据（三端点都断言 404）是【错的】：G1 在不存在任何工单的情况下
            //    也能成功立案（这正是"代录"的语义），断言它 404 会把一次 200 判成失败，
            //    而"它真的落库了"这件事反而被当成缺陷。C-3 初版即栽在此处。
            //
            //    ⚠️ 代价：本用例会真的落 4 张工单（每角色一张）。
            //    故它必须自清理 —— 走 cleanup() 里按 tenant 兜底删（见 @AfterAll）。
            assertEndpointReachableWithStatus("G1 代录", "POST", "/api/v1/refunds",
                    () -> createBody(), 200);
        }

        @Test
        @DisplayName("G3 挽留 × 4 角色 → 404·3001（契约 x-callable-roles: [meridian, admin]）")
        void g3_is_reachable_by_all_contract_callable_staff_roles() {
            assertEndpointReachableWithStatus("G3 挽留", "POST",
                    "/api/v1/refunds/" + ABSENT_REFUND + "/retentions",
                    () -> "{\"attempts\":1,\"result\":\"接受继续服务\",\"analysis\":{},\"communication\":{}}",
                    404);
        }

        @Test
        @DisplayName("G5 回执 × 4 角色 → 404·3001（契约 x-callable-roles: [meridian, admin]）")
        void g5_is_reachable_by_all_contract_callable_staff_roles() {
            // 🛑 G5 的探针必须带 push_succeeded=true —— 见 bodyOf 的注释：
            //    subscription_quota=1 且不带 push_succeeded 时 pushSucceeded=false，
            //    于是走的是"推送失败"分支。那条分支在修复前【100% 报 5001】
            //    （控制器恒传 pushed_at=now()，违反『非已推送 ⇒ pushed_at IS NULL』），
            //    而修复后它是合法路径 —— 但它回的是"工单不存在"之前还是之后，
            //    取决于服务层先做哪一步。本矩阵要求它先撞 404（查无此单），
            //    因为 G5 的第一道业务动作就是 pushAndRecord 里的 requireUuid + 存在性。
            //    故探针取"成功推送"这一支，避免把一个无关分支的行为混进本矩阵的判据。
            assertEndpointReachableWithStatus("G5 回执", "POST",
                    "/api/v1/refunds/" + ABSENT_REFUND + "/receipts",
                    () -> "{\"subscription_quota\":1,\"push_succeeded\":true}",
                    404);
        }

        /**
         * 矩阵的统一判据执行器。
         *
         * <p>把它抽出来是为了让三条用例共用<b>同一份</b>断言逻辑 ——
         * 若三个端点各写一遍，某天只改了其中一条的判据（例如把 404 放宽成"非 403"），
         * 那个端点就会静默失去牙齿，而另外两条仍绿，症状表现为"时红时绿"。
         *
         * <p>🛑 但 {@code expectedStatus} 必须<b>逐端点显式传入</b>，不能统一：
         * 三个端点的成功/失败形态本来就不同（G1 创建 → 200；G3/G5 按 ID 操作 → 404）。
         * 强行统一成一个值，就是在用一个错误判据去覆盖三个端点 ——
         * 而错误的判据比没有判据更坏（它会把正确行为判成缺陷，逼人去改正确的代码）。
         */
        private void assertEndpointReachableWithStatus(String label, String method, String path,
                                                       java.util.function.Supplier<String> body,
                                                       int expectedStatus) {
            // 🛑 角色集【从契约机械展开】，不再手写 List.of(...)。
            //
            //    此前本处逐字写着 List.of("meridian", "manager", "area", "hq") ——
            //    而那是【手抄】的展开结果。手抄的问题不是"抄错了"（当时是对的），
            //    而是它一旦与契约分叉就【没有任何东西会红】：
            //    若上游把 G1 的 x-callable-roles 改成 [meridian, therapist, admin]，
            //    契约变了而本矩阵仍只测那 4 个 ⇒ therapist 那一侧【静默漏测】，
            //    表现为"矩阵全绿但某个契约声明可调的角色实际 403"。
            //
            //    故改为：从契约 x-callable-roles 读角色名，再按 x-roles 的
            //    token-role 展开（admin → manager/area/hq）。契约是本矩阵的【唯一真相源】，
            //    它的变化会在下一次构建时立刻反映到被测角色集上。
            List<String> roles = ContractRoles.callableTokenRoles(path);
            assertFalse(roles.isEmpty(),
                    "🛑 从契约展开不出 " + path + " 的可调 token 角色 —— "
                            + "契约路径写错、或 x-callable-roles/x-roles 结构变了，"
                            + "本矩阵会退化成空循环（恒绿）。");
            List<String> failures = new ArrayList<>();

            for (String role : roles) {
                ResponseEntity<String> resp = send(method, path, token(role, "own_store"), body.get());
                int sc = resp.getStatusCodeValue();
                int code = codeOrZero(resp);

                if (sc != expectedStatus) {
                    failures.add(role + " → HTTP " + sc + " code " + code
                            + " body " + resp.getBody());
                    continue;
                }
                // 预期 404 时，必须同时带契约信封 code=3001 —— 否则那是 Spring 的
                // "路径不存在"404，而非业务层"查无此单"，两者的分辨见 SelfProof。
                if (expectedStatus == 404 && code != 3001) {
                    failures.add(role + " → HTTP 404 但 code=" + code + "（应为 3001 NOT_FOUND）"
                            + " body " + resp.getBody());
                }
            }

            assertTrue(failures.isEmpty(),
                    "🛑 " + label + " 的契约 x-callable-roles 展开为 " + roles + "，"
                            + "这些角色都必须【走过装配链】后返回 " + expectedStatus
                            + "。若有角色拿到 403，说明装配链把契约声明可调的端点拒了 —— "
                            + "最常见成因是 @RequirePermission 的权限码未登记进 PermissionRegistry"
                            + "（服务层单测直调 requireVisible，永远看不见这一层；S2-4 曾整体栽在此处）。"
                            + "若拿到 400/500，多半是入参校验或装配层的问题。"
                            + "不达标的角色: " + failures);
        }
    }

    // ==================================================================
    // 一·补 · 契约驱动：角色集由 x-callable-roles × x-roles 机械展开
    // ==================================================================

    /**
     * <b>契约驱动的可调角色展开器</b>（C-3 收口的关键件）。
     *
     * <h2>🛑 它替换掉了什么，以及为什么那样做是错的</h2>
     * 本套件的矩阵原先逐字写死 {@code List.of("meridian", "manager", "area", "hq")}。
     * 那 4 个值当时是对的，但它们是 {@code x-callable-roles: [meridian, admin]} 的
     * <b>手抄展开结果</b>。手抄清单的失效方式是<b>静默分叉</b>：
     * 上游改了契约的一侧（增删角色），矩阵不会红，于是"契约声明可调但实际被拒"的角色
     * 在真请求上<b>从未被检查过</b> —— 而这恰是本套件存在的理由
     * （S2-4 的整体 403 事故正是这一类）。
     *
     * <h2>展开规则（与契约 x-roles 同源，不另立口径）</h2>
     * <ol>
     *   <li>{@code x-callable-roles} 列出的是<b>契约角色名</b>（client / therapist / meridian / admin / store_customer_service）；</li>
     *   <li>每个角色名按 {@code x-roles.<名>.token-role} 展开为 <b>JWT 里真正携带的 token 角色</b>：
     *       {@code admin → [manager, area, hq]}（三类管理员共用一个契约名）、
     *       {@code meridian → "meridian"}（单值）、
     *       {@code store_customer_service → null}（<b>无端</b>，展开为空 ⇒ 不进矩阵）；</li>
     *   <li>{@code null} 的角色<b>被显式跳过</b>而不是当成某个默认角色 ——
     *       把一个"无端"角色降级成可调角色会凭空造出一条提权面。</li>
     * </ol>
     * 展开顺序保持契约声明的顺序（{@code [meridian, admin]} ⇒ {@code meridian, manager, area, hq}），
     * 使失败信息里第一个角色恒为契约里第一个被点名的角色。
     */
    static final class ContractRoles {

        private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";
        private static final String CONTRACT_MD_REL = "_work/contract-t6-api-freeze-2026-09-19.md";
        /** 测试路径带 /api/v1 前缀，契约路径不带 —— 本常量是其映射锚。 */
        private static final String API_PREFIX = "/api/v1";

        private ContractRoles() {
        }

        /** 由测试用的完整路径（含 /api/v1）展开可调 token 角色。 */
        static List<String> callableTokenRoles(String fullPath) {
            String contractPath = fullPath.startsWith(API_PREFIX)
                    ? fullPath.substring(API_PREFIX.length()) : fullPath;
            // 把测试里的具体 UUID 还原成契约的路径模板变量
            contractPath = contractPath.replace(RefundWritePathMatrixE2ETest.ABSENT_REFUND, "{id}");
            return callableTokenRolesForContractPath(contractPath);
        }

        private static List<String> callableTokenRolesForContractPath(String contractPath) {
            Map<String, Object> doc = load();
            Map<String, Object> paths = asMap(doc.get("paths"), "paths");
            Object itemRaw = paths.get(contractPath);
            if (itemRaw == null) {
                return List.of();
            }
            Map<String, Object> item = asMap(itemRaw, contractPath);
            List<String> out = new ArrayList<>();
            Map<String, Object> rolesDecl = asMap(doc.get("x-roles"), "x-roles");
            for (String verb : List.of("get", "post", "put", "patch", "delete")) {
                Object opRaw = item.get(verb);
                if (!(opRaw instanceof Map)) {
                    continue;
                }
                Object callable = asMap(opRaw, verb).get("x-callable-roles");
                if (!(callable instanceof List)) {
                    continue;
                }
                for (Object roleNameRaw : (List<?>) callable) {
                    String roleName = String.valueOf(roleNameRaw);
                    Object roleDeclRaw = rolesDecl.get(roleName);
                    if (!(roleDeclRaw instanceof Map)) {
                        continue;
                    }
                    Object tokenRole = asMap(roleDeclRaw, roleName).get("token-role");
                    if (tokenRole instanceof List) {
                        for (Object t : (List<?>) tokenRole) {
                            out.add(String.valueOf(t));
                        }
                    } else if (tokenRole != null && !"null".equals(String.valueOf(tokenRole))) {
                        out.add(String.valueOf(tokenRole));
                    }
                    // token-role == null ⇒ 该角色无端：显式跳过（绝不降级为某个默认角色）
                }
                break; // 每个路径在本契约里只有一个动词
            }
            return out;
        }

        private static Map<String, Object> load() {
            try {
                Path root = resolveRepoRoot();
                String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);
                Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
                return asMap(parsed, "openapi 顶层");
            } catch (IOException e) {
                throw new IllegalStateException("读取契约失败: " + OPENAPI_REL, e);
            }
        }

        private static Path resolveRepoRoot() {
            for (String key : List.of("dy.docs.root", "dy.repo.root")) {
                String explicit = System.getProperty(key);
                if (explicit != null && !explicit.isBlank()) {
                    Path p = Path.of(explicit).toAbsolutePath().normalize();
                    if (isRepoRoot(p)) {
                        return p;
                    }
                }
            }
            Path cursor = Path.of("").toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
                if (isRepoRoot(cursor)) {
                    return cursor;
                }
            }
            throw new IllegalStateException("无法定位仓库根（需存在 " + CONTRACT_MD_REL + " 与 " + OPENAPI_REL + "）");
        }

        private static boolean isRepoRoot(Path p) {
            return Files.isRegularFile(p.resolve(CONTRACT_MD_REL.replace('/', File.separatorChar)))
                    && Files.isRegularFile(p.resolve(OPENAPI_REL.replace('/', File.separatorChar)));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object o, String where) {
            if (!(o instanceof Map)) {
                throw new IllegalStateException(where + " 应为 mapping，实为: "
                        + (o == null ? "null" : o.getClass().getName()));
            }
            return (Map<String, Object>) o;
        }
    }

    /**
     * <b>契约驱动自证</b>：把"角色集来自契约"这件事本身变成断言 ——
     * 使契约一旦改动，本套件立刻红并指出该改哪里。
     *
     * <h2>🛑 为什么需要这一层（而不是直接信任展开器）</h2>
     * 展开器若因契约结构变化而返回了<b>偏窄</b>的集合（例如 {@code x-roles} 被重排、
     * {@code x-callable-roles} 被改名），矩阵会安静地少测几个角色 —— 而它仍然全绿。
     * 故下面逐端点把"展开结果"钉成<b>显式期望值</b>：
     * <ul>
     *   <li>展开结果与期望<b>逐字相同</b> ⇒ 契约与矩阵同步；</li>
     *   <li>上游改了契约的 {@code x-callable-roles} ⇒ 本断言红，提示"契约面变了，"
     *       "请同步本期望值与 README 的 G 域角色矩阵"；</li>
     *   <li>展开器自身退化（返回空/漏角色）⇒ 也红 —— 而不会让矩阵恒绿。</li>
     * </ul>
     */
    @Nested
    @DisplayName("一·补 · 角色集来自契约（x-callable-roles × x-roles 展开自证）")
    class ContractDrivenRoleSet {

        @Test
        @DisplayName("🛑 G1/G3/G5 的可调 token 角色 = [meridian, manager, area, hq]（由契约展开，非手抄）")
        void callable_token_roles_are_derived_from_the_contract() {
            for (String path : writePaths().values()) {
                List<String> derived = ContractRoles.callableTokenRoles(path);
                assertEquals(List.of("meridian", "manager", "area", "hq"), derived,
                        "🛑 " + path + " 的契约可调 token 角色展开结果变了。\n"
                                + "实际: " + derived + "\n"
                                + "期望: [meridian, manager, area, hq]（= x-callable-roles [meridian, admin]，"
                                + "admin 按 x-roles 展开为 manager/area/hq）。\n"
                                + "若上游改了契约的这一侧，请同步本期望值、README 的 G 域角色矩阵，"
                                + "以及矩阵用例的注释 —— 而不是让『契约可调角色』与『被测角色』静默分叉。");
            }
        }

        @Test
        @DisplayName("🛑 store_customer_service 无 token-role ⇒ 展开为空、绝不进矩阵（N-5 在展开器上再钉一次）")
        void the_endless_role_expands_to_nothing() {
            // N-5：契约 x-roles 把 store_customer_service 的 token-role 定为 null（无端）。
            // 展开器必须【跳过】它，而不是把它降级成某个默认角色 ——
            // 否则本矩阵会凭空去测一个不存在的 JWT 角色，且症状是"莫名其妙 401"。
            Map<String, Object> doc = ContractRoles.load();
            Map<String, Object> roles = ContractRoles.asMap(doc.get("x-roles"), "x-roles");
            Map<String, Object> scs = ContractRoles.asMap(
                    roles.get("store_customer_service"), "x-roles.store_customer_service");
            assertEquals("null", String.valueOf(scs.get("token-role")),
                    "🛑 store_customer_service 的 token-role 不再是 null —— "
                            + "N-5 的整条论证前提（它无端、不可能带此身份发请求）就不成立了，"
                            + "本展开器的『跳过 null』分支需要重新裁定。实际: " + scs.get("token-role"));

            // 直接验证展开器对该角色的处理：造一个含它的 x-callable-roles 视角 ——
            // 由于契约现在不含它，这里改为断言【所有展开结果都不含它】。
            for (String path : writePaths().values()) {
                assertFalse(ContractRoles.callableTokenRoles(path).contains("store_customer_service"),
                        "🛑 " + path + " 的展开结果里出现了 store_customer_service —— "
                                + "无端角色不得进入可调矩阵（否则是一条凭空造出的提权面）。");
            }
        }
    }

    // ==================================================================
    // 二、负向面：必须被拒的角色（三端点全一致）
    // ==================================================================

    @Nested
    @DisplayName("二 · 负向面：无档位角色 / 客户 / 匿名 / 伪签名 / 未知角色")
    class NegativeMatrix {

        @Test
        @DisplayName("therapist × 三写端点 → 403·2001（契约域 G 上游：客户端与调理师端一律 403）")
        void therapist_is_denied_on_all_three_write_endpoints() {
            assertAllEndpointsDenied("调理师", "therapist", 403, 2001);
        }

        @Test
        @DisplayName("🛑 store_customer_service × 三写端点 → 403（N-5：该角色无端，且不持任何 refund:*）")
        void store_customer_service_role_is_denied_on_all_three_write_endpoints() {
            // 🛑 N-5 登记过：「store_customer_service 角色无端」是契约侧缺口，
            //    PermissionCodeRegistrationGateTest 已钉住它不持任何 refund:*。
            //    本用例把同一事实在【真请求】上再钉一次 —— 码级门禁证明的是"它没码"，
            //    真请求证明的是"因此它被拒"。两者成立才构成"它确实不可达"的完整证据。
            assertAllEndpointsDenied("门店客服", "store_customer_service", 403, 2001);
        }

        @Test
        @DisplayName("client × 三写端点 → 403·2001（契约 x-client-forbidden: true）")
        void client_is_denied_on_all_three_write_endpoints() {
            // 客户必须被【端点级】拒绝，而不是"恰好查无此单"（那会返回 404）。
            // 故此处同时断言 403 而非 404 —— 与 S2-5 的同款判据保持一致。
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, String> e : writePaths().entrySet()) {
                ResponseEntity<String> resp = send(methodOf(e.getKey()), e.getValue(),
                        token("client", "own_store"), bodyOf(e.getKey()));
                if (resp.getStatusCodeValue() != 403 || codeOrZero(resp) != 2001) {
                    failures.add(e.getKey() + " → HTTP " + resp.getStatusCodeValue()
                            + " code " + codeOrZero(resp));
                }
            }
            assertTrue(failures.isEmpty(),
                    "🛑 客户在三个写端点上必须被端点级 403·2001 拦下（不是 404 —— 404 说明防线没拦住，"
                            + "请求只是恰好因数据不存在而失败，两者是完全不同的失守）。不达标: " + failures);
        }

        @Test
        @DisplayName("无 token × 三写端点 → 403·2001（既有基线：落到权限层）")
        void anonymous_is_denied_on_all_three_write_endpoints() {
            assertAllEndpointsDenied("匿名", null, 403, 2001);
        }

        @Test
        @DisplayName("🛑 伪签名 token × 三写端点 → 401（绝不降级为匿名请求）")
        void tampered_token_is_unauthenticated_on_all_three_write_endpoints() {
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, String> e : writePaths().entrySet()) {
                String bad = tampered(token("meridian", "own_store"));
                ResponseEntity<String> resp = send(methodOf(e.getKey()), e.getValue(), bad,
                        bodyOf(e.getKey()));
                // 🛑 必须 401，不能是 403/404/200 ——
                //    若被"降级为匿名"，攻击者带一个坏 token 就能绕到权限层之前的某些路径；
                //    若被"降级为有效"，那就是身份伪造。
                if (resp.getStatusCodeValue() != 401) {
                    failures.add(e.getKey() + " → HTTP " + resp.getStatusCodeValue()
                            + " body " + resp.getBody());
                }
            }
            assertTrue(failures.isEmpty(),
                    "🛑 验签失败必须 401，绝不降级为匿名（否则带坏 token 会变成一条绕过路径）。"
                            + "不达标: " + failures);
        }

        @Test
        @DisplayName("🛑 契约外未知角色 × 三写端点 → 403（fail-closed，不得被当作 hq）")
        void unknown_role_is_denied_on_all_three_write_endpoints() {
            // 把 meridian 加后缀构造一个"签名有效但角色不在契约里"的 token。
            // 它必须被 fail-closed 拒绝 —— 若某个实现把"未知角色"默认成最高档，
            // 这就是一条提权路径（症状：403 变 404）。
            assertAllEndpointsDenied("未知角色", "meridian_typo_c3", 403, 2001);
        }

        @Test
        @DisplayName("🛑 G5 三态都必须能留痕（覆盖率分母三态齐全）—— 用【真实工单】而非不存在的 ID")
        void g5_all_three_receipt_states_must_be_recordable() {
            // 🛑 这是 C-3 抓到的第三个真缺陷的【固化断言】（2026-09-27），
            //    且它在第一版写法上【没有牙齿】—— 值得记下来：
            //
            //    ❌ 第一版用 ABSENT_REFUND（不存在的工单）作探针，断言"推送失败不该 5001"。
            //       反向验证（把 pushed_at 改回恒 now()）证明它【仍然全绿】：
            //       因为 G5 现在先校验工单存在性（那道闸本身是第 5 个缺陷的修复），
            //       请求在冲到推送分支【之前】就 404 了 —— 缺陷被前面一道闸屏蔽，
            //       探针根本走不到它要测的那行代码。**门禁因此变成摆设。**
            //
            //    ✅ 故必须用【真实工单】：立案拿到 refund_id，再对它调 G5。
            //       此时请求会真的走到推送分支，pushed_at 的形态才成为可判定的事实。
            //
            //    🛑 三态全测（而不是只测"推送失败"）：P0-19 逐字规定
            //       「覆盖率分母 = 已推送 + 未授权（转线下） + 推送失败」——
            //       少任何一态，分母就缺一块，覆盖率会系统性偏高且看不出来。
            //       只测一态无法发现"另外两态也录不进去"。
            String refundId = createRefund("C-3 G5 三态探针");

            // ---- 态一：已推送（push_succeeded=true，必须有 pushed_at）----
            assertReceiptState(refundId, "已推送", "已推送",
                    "{\"subscription_quota\":1,\"push_succeeded\":true}");

            // ---- 态二：推送失败（push_succeeded=false，必须【没有】pushed_at）----
            assertReceiptState(refundId, "推送失败", "推送失败",
                    "{\"subscription_quota\":1,\"push_succeeded\":false,"
                            + "\"failure_reason\":\"用户拒收订阅消息\"}");

            // ---- 态三：未授权（额度 ≤ 0，推送事件根本不发生）----
            assertReceiptState(refundId, "未授权", "未授权（转线下）",
                    "{\"subscription_quota\":0}");
        }

        /**
         * 对一张真实工单调 G5，断言落库后的 {@code receipt_state}。
         *
         * <p>🛑 出站字面含全角括号（{@code 未授权（转线下）}）—— 与契约
         * {@code RefundReceiptData.receipt_state} 逐字一致。断言用它而非库侧缩写，
         * 是因为端侧逐字比对的是出站值；库侧字面由 {@code refund_receipt} 的 CHECK 守着。
         */
        private void assertReceiptState(String refundId, String label,
                                        String expectedState, String body) {
            ResponseEntity<String> resp = send("POST",
                    "/api/v1/refunds/" + refundId + "/receipts",
                    token("meridian", "own_store"), body);
            assertEquals(200, resp.getStatusCodeValue(),
                    "🛑 G5 的『" + label + "』态必须能落库（P0-19 覆盖率分母三态之一）。"
                            + "拿到 422·5001 说明控制器给这次推送塞了一个不该有的 pushed_at"
                            + "（库层 CHECK 要求『非已推送 ⇒ pushed_at IS NULL』）——"
                            + "而那个态会在库里【永不出现】，分母缺一块。实际: " + resp.getBody());
            JsonNode root = parseOrNull(resp.getBody());
            assertNotNull(root, "G5 响应必须是 JSON 信封: " + resp.getBody());
            assertEquals(expectedState, root.path("data").path("receipt_state").asText(null),
                    "🛑 落库态应为『" + expectedState + "』。实际: " + resp.getBody());
        }

        /**
         * 立案一张工单并返回其 ID（G5 三态断言的前置）。
         *
         * <p>🛑 它必须真的落库（不是构造一个假 ID）—— 见
         * {@link #g5_all_three_receipt_states_must_be_recordable} 的注释：
         * 用不存在的工单会让探针在存在性闸处短路，从而测不到推送分支。
         * 这些工单由 {@code cleanup()} 按 {@code customer_id} 边界清理。
         */
        private String createRefund(String statement) {
            ResponseEntity<String> resp = send("POST", "/api/v1/refunds",
                    token("meridian", "own_store"),
                    "“"
                    {
                      "customer_id": "%s",
                      "entry": "A门店代录",
                      "refund_route": "效果类",
                      "reason_code": "效果未达预期",
                      "liable_store_id": "%s",
                      "customer_statement": "%s"
                    }
                    "”".formatted(S_CUSTOMER, S_STORE, statement));
            assertEquals(200, resp.getStatusCodeValue(),
                    "G5 三态探针的前置立案必须成功。实际: " + resp.getBody());
            String id = parseOrNull(resp.getBody()).path("data").path("refund_id").asText(null);
            assertNotNull(id, "立案必须回显 refund_id。实际: " + resp.getBody());
            return id;
        }

        @Test
        @DisplayName("🛑 G3 的 analysis 传 JSON 对象必须被接受（不是 500）—— 端侧最自然的写法")
        void g3_accepts_json_object_for_analysis_and_communication() {
            // 🛑 这是 C-3 抓到的第二个真缺陷的【固化断言】（2026-09-27）。
            //
            //    analysis / communication 在库层是 JSONB，业务上是结构化对象，
            //    端侧最自然的写法就是传对象。而请求体 record 原先把它们声明为 String
            //    ⇒ Jackson 抛 HttpMessageNotReadableException ⇒ 全局处理器兜成 500·9001。
            //
            //    判别：走到业务层（404 查无此单）说明入参被接受；
            //    500（或 400）说明"传对象"这个合法写法仍被拒。
            ResponseEntity<String> resp = send("POST",
                    "/api/v1/refunds/" + ABSENT_REFUND + "/retentions",
                    token("meridian", "own_store"),
                    "{\"attempts\":1,\"result\":\"接受继续服务\","
                            + "\"analysis\":{\"五维\":\"频次不足\"},"
                            + "\"communication\":{\"渠道\":\"电话\",\"时长分钟\":12}}");

            assertEquals(404, resp.getStatusCodeValue(),
                    "🛑 analysis 传 JSON 对象必须被接受（端侧最自然的写法）。"
                            + "拿到 500 说明请求体 record 又把 JSON 字段声明回 String 了；"
                            + "拿到 400 说明 jsonText 的字符串分支把对象漏掉了。"
                            + "实际: " + resp.getBody());
            assertEquals(3001, codeOrZero(resp),
                    "应为 3001（查无此单，说明入参已被接受并进入业务层）。实际: " + resp.getBody());
        }

        /** 统一执行"三端点 × 该角色必须被拒"。 */
        private void assertAllEndpointsDenied(String label, String role, int expectHttp, int expectCode) {
            String jwt = role == null ? null : token(role, "own_store");
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, String> e : writePaths().entrySet()) {
                ResponseEntity<String> resp = send(methodOf(e.getKey()), e.getValue(), jwt,
                        bodyOf(e.getKey()));
                if (resp.getStatusCodeValue() != expectHttp || codeOrZero(resp) != expectCode) {
                    failures.add(e.getKey() + " → HTTP " + resp.getStatusCodeValue()
                            + " code " + codeOrZero(resp) + " body " + resp.getBody());
                }
            }
            assertTrue(failures.isEmpty(),
                    label + " 在三个写端点上必须被 " + expectHttp + "·" + expectCode
                            + " 拒绝。不达标: " + failures);
        }
    }

    // ==================================================================
    // 三、端点存在性自证（防"矩阵恒真"）
    // ==================================================================

    @Nested
    @DisplayName("三 · 矩阵自证：三个端点必须真实存在（防『全 403 也算通过』）")
    class SelfProof {

        @Test
        @DisplayName("🛑 三写端点真实存在 ⇒ G1 返回 200 带 refund_id，G3/G5 返回 404·3001")
        void the_three_endpoints_actually_exist() {
            // 🛑 这条用例防的是一个很隐蔽的假绿：若三个路径**根本不存在**，
            //    Spring 会为未匹配路径返回 404 —— 而本套件的 G3/G5 正向矩阵断言的正是 404！
            //    于是"端点没实现"会被误判成"角色被放行了"。
            //
            //    分辨方法：真正的端点在工单不存在时返回的 404 带【契约信封】
            //    （{code:3001, message, trace_id}）；而"路径不存在"的 404 是
            //    Spring 的默认错误页/裸错误体，没有 code 字段。
            //    故这里断言"404 必须带 code=3001"，从而把两者区分开。
            //
            //    G1 的分辨方式不同（它不返回 404）：真实存在的创建端点会返回 200
            //    且信封里带 refund_id。C-3 初版误把 G1 也断言成 404，已修正。

            // ---- G1：创建端点，判据是 200 + refund_id ----
            ResponseEntity<String> g1 = send("POST", "/api/v1/refunds",
                    token("meridian", "own_store"), createBody());
            assertEquals(200, g1.getStatusCodeValue(),
                    "🛑 G1 对经络师应 200（创建成功）。若 404/405，说明端点根本不存在 ——"
                            + " 而 SelfProof 的存在就是为了把这种假绿挡掉。实际: " + g1.getBody());
            JsonNode g1Root = parseOrNull(g1.getBody());
            assertNotNull(g1Root, "G1 响应必须是 JSON 信封: " + g1.getBody());
            assertTrue(g1Root.hasNonNull("trace_id"),
                    "🛑 200 也必须是标准四字段信封（含 trace_id）。实际: " + g1.getBody());
            String newRefundId = g1Root.path("data").path("refund_id").asText(null);
            assertNotNull(newRefundId,
                    "🛑 G1 成功必须回显 refund_id（契约 RefundData.refund_id）。"
                            + "缺它则端侧无法追踪这张工单。实际: " + g1.getBody());

            // ---- G3 / G5：按 ID 操作，判据是 404 带 code=3001 ----
            for (Map.Entry<String, String> e : absentIdWritePaths().entrySet()) {
                ResponseEntity<String> resp = send("POST", e.getValue(),
                        token("meridian", "own_store"), bodyOf(e.getKey()));
                assertEquals(404, resp.getStatusCodeValue(),
                        e.getKey() + " 对经络师应 404: " + resp.getBody());
                assertEquals(3001, codeOrZero(resp),
                        "🛑 " + e.getKey() + " 的 404 必须带契约信封 code=3001。"
                                + "若 code 缺失/为 0，说明这是 Spring 的『路径不存在』404 —— "
                                + "那种情况下正向矩阵的断言全是假绿（端点根本没实现）。"
                                + "实际 body: " + resp.getBody());
                JsonNode root = parseOrNull(resp.getBody());
                assertNotNull(root, e.getKey() + " 响应必须是 JSON 信封: " + resp.getBody());
                assertTrue(root.has("trace_id"),
                        "🛑 404 也必须是标准四字段信封（含 trace_id）——"
                                + "裸错误体说明响应没走统一异常处理链。实际: " + resp.getBody());
            }
        }

        @Test
        @DisplayName("🛑 反向自证：G1 的客户/门店不存在 ⇒ 400·1001（而不是 500·9001）")
        void g1_rejects_absent_references_with_400_not_500() {
            // 🛑 这是 C-3 抓到的一个真缺陷的【固化断言】（2026-09-27）：
            //    G1 原先不校验 customer_id / liable_store_id 的存在性，
            //    于是外键违例以 DataIntegrityViolationException 冒上来，
            //    被全局处理器兜成 500·9001「系统异常」——
            //    而契约 G1 明列 '400' → ValidationFailed。
            //
            //    这条用例把"参数错误必须报 400 而不是 500"钉死。
            //    它的分辨力来自 ID 的构造：用一个【格式合法但必然不存在】的 UUID。
            //    若将来有人把存在性校验删掉，它会变回 500 并立刻变红。
            String ghost = "e2e00000-0000-0000-0000-0000000000ff";
            ResponseEntity<String> resp = send("POST", "/api/v1/refunds",
                    token("meridian", "own_store"),
                    "“"
                    {
                      "customer_id": "%s",
                      "entry": "A门店代录",
                      "refund_route": "效果类",
                      "reason_code": "效果未达预期",
                      "liable_store_id": "%s",
                      "customer_statement": "C-3 反向自证：不存在的客户"
                    }
                    "”".formatted(ghost, S_STORE));

            assertEquals(400, resp.getStatusCodeValue(),
                    "🛑 引用不存在的客户必须 400·1001（契约 G1 的 responses 声明 400）。"
                            + "拿到 500 说明存在性校验缺失 ⇒ 外键裸抛 ⇒ 调用方会去重试一个"
                            + "永远不可能成功的请求。实际: " + resp.getBody());
            assertEquals(1001, codeOrZero(resp),
                    "🛑 应为 1001 VALIDATION_FAILED（不是 3001 NOT_FOUND —— "
                            + "G1 的契约 responses 只声明 200/400/403）。实际: " + resp.getBody());
        }

        @Test
        @DisplayName("🛑 反向自证：把路径改一个字母，404 就不带 code ⇒ 证明上面的判据有分辨力")
        void the_self_proof_discriminates_a_nonexistent_path() {
            // 这是对上面那条用例的"反向验证"：若"404 带 code=3001"这个判据
            // 对不存在的路径也成立，那它就毫无分辨力。
            ResponseEntity<String> resp = send("POST", "/api/v1/refunds-typo-c3",
                    token("meridian", "own_store"), createBody());
            assertTrue(resp.getStatusCodeValue() >= 400,
                    "不存在的路径应当失败: " + resp.getStatusCodeValue());
            assertTrue(codeOrZero(resp) != 3001,
                    "🛑 不存在的路径【不得】返回 code=3001 —— 否则上面那条自证用例没有分辨力"
                            + "（它对任何 404 都会通过）。实际 body: " + resp.getBody());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 三个写端点的 标签 → 路径（方法统一为 POST）。 */
    private static Map<String, String> writePaths() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("G1 代录", "/api/v1/refunds");
        m.put("G3 挽留", "/api/v1/refunds/" + ABSENT_REFUND + "/retentions");
        m.put("G5 回执", "/api/v1/refunds/" + ABSENT_REFUND + "/receipts");
        return m;
    }

    /**
     * 仅"按 ID 操作"的两个端点（G3/G5）—— 它们有 404 语义。
     *
     * <p>🛑 G1 刻意<b>不</b>在此表里：它是创建端点，没有工单 ID 可指，
     * 成功形态是 200 而不是 404。把它混进来会让 SelfProof 变成"对 G1 断言 404"，
     * 而那是错的（C-3 初版即栽在此处）。
     */
    private static Map<String, String> absentIdWritePaths() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("G3 挽留", "/api/v1/refunds/" + ABSENT_REFUND + "/retentions");
        m.put("G5 回执", "/api/v1/refunds/" + ABSENT_REFUND + "/receipts");
        return m;
    }

    private static String methodOf(String label) {
        return "POST";
    }

    private static String bodyOf(String label) {
        switch (label) {
            case "G1 代录":
                return createBody();
            case "G3 挽留":
                return "{\"attempts\":1,\"result\":\"接受继续服务\",\"analysis\":{},\"communication\":{}}";
            case "G5 回执":
                // 🛑 必须显式带 push_succeeded=true。
                //
                //    原先只传 subscription_quota=1：控制器把 push_succeeded 缺席读成
                //    Boolean.TRUE.equals(null) = false ⇒ 走"推送失败"分支。
                //    而那条分支在修复前【100% 报 5001】（控制器恒传 pushed_at=now()，
                //    违反库层 CHECK『非已推送 ⇒ pushed_at IS NULL』）——
                //    于是本矩阵的 G5 用例测的根本不是"G5 是否可达"，
                //    而是"G5 的推送失败分支能不能用"。两个完全不同的结论被混在一起。
                //
                //    修复后"推送失败"是合法路径，但本矩阵要的是"走到 404（查无此单）"，
                //    故探针取成功推送这一支：它不引入与"可达性"无关的分支行为。
                //    "推送失败能留痕"由下方专门的用例单独断言。
                return "{\"subscription_quota\":1,\"push_succeeded\":true}";
            default:
                throw new IllegalArgumentException("未知端点标签: " + label);
        }
    }

    private static String createBody() {
        return "“"
                {
                  "customer_id": "%s",
                  "entry": "A门店代录",
                  "refund_route": "效果类",
                  "reason_code": "效果未达预期",
                  "liable_store_id": "%s",
                  "customer_statement": "C-3 矩阵探针：客户原话"
                }
                "”".formatted(S_CUSTOMER, S_STORE);
    }

    private ResponseEntity<String> send(String method, String path, String jwt, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange(url(path), HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static int codeOrZero(ResponseEntity<String> resp) {
        JsonNode root = parseOrNull(resp.getBody());
        return root == null ? 0 : root.path("code").asInt(0);
    }

    private static JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String tampered(String jwt) {
        // 改掉签名末 4 位 —— 保持 JWT 三段结构合法，只破坏签名
        return jwt.substring(0, jwt.length() - 4) + "AAAA";
    }

    private static String token(String role, String scope) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + S_STAFF + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"scope\":\"" + scope + "\""
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
}