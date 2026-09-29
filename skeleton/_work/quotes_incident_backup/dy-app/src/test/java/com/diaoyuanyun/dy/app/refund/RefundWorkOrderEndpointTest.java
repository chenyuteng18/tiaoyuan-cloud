package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.controller.RefundWorkOrderController;
import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundEntry;
import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundRawConfig;
import com.diaoyuanyun.dy.app.refund.domain.RefundReasonCode;
import com.diaoyuanyun.dy.app.refund.domain.RefundRecordingPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundRoute;
import com.diaoyuanyun.dy.app.refund.domain.RefundStatementRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderRow;
import com.diaoyuanyun.dy.app.refund.domain.RetentionPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RetentionResult;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.app.refund.service.RefundWorkOrderService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import com.diaoyuanyun.dy.web.idempotent.Idempotent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-4 守卫套件 —— 契约域 G 五端点 G1~G5 的<b>装配与门禁</b>。
 *
 * <h2>本类断言的是"哪一层负责哪件事"，而不是"代码长什么样"</h2>
 * 五条端点的正确性靠四层叠加，本类逐层钉死<b>边界</b>：
 * <pre>
 *   ① 客户一律 403    → @StaffOnly 存在且 clientDeniedFields 非空（契约级，与参数无关）
 *   ② staff 侧档位    → RefundAudienceRole.ofTokenRole + Matrix.canSee（可见性）
 *   ③ 岗位权限        → @RequirePermission（功能权限码）
 *   ④ 业务闸          → 服务层（挽留豁免 / 审批落点 / 代录者不可审批 / 归档只读）
 * </pre>
 * 🛑 本类刻意<b>不</b>断言"控制器里写了 if"。断言"哪一层是判定落点"的价值在于：
 * 有人把判定搬到别处时（例如把豁免挽留写进控制器），本类会红 ——
 * 而那种搬迁不会改变任何一次单元测试的结果，只会在将来分叉。
 *
 * <h2>🛑 一处刻意的"反向断言"：测试要证的是守卫有牙齿</h2>
 * {@link ControllerShape#refund_controller_never_mentions_the_jdbc_ledger()}
 * 扫的是控制器<b>源码文本</b>而不是行为。理由是：架构守卫 R2 用 ArchUnit 的
 * <b>编译产物</b>判定依赖，而"有人在控制器里 import 了仓储"与"依赖真的用上了"
 * 之间有一段空隙（漏掉的 import 一旦被用到就会编译失败，但 import 本身不会）。
 * 源码扫描把这段空隙补上，代价是它只覆盖这一个类 —— 这个代价是可接受的，
 * 因为退款域控制器只有这一个。
 */
@DisplayName("S2-4 · 契约域 G 五端点装配与门禁")
class RefundWorkOrderEndpointTest {

    private static final String TENANT = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final UUID CUSTOMER = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID STORE = UUID.fromString("cccccccc-1111-1111-1111-111111111111");
    private static final UUID STAFF = UUID.fromString("dddddddd-1111-1111-1111-111111111111");
    private static final UUID APPROVER = UUID.fromString("eeeeeeee-1111-1111-1111-111111111111");
    private static final UUID DEPUTY = UUID.fromString("ffffffff-1111-1111-1111-111111111111");

    private final RefundProfileSource source = new ConfigSeedRefundProfileSource();

    private RefundWorkOrderService service;
    private InMemoryRefundWorkOrderPort port;
    private RetentionPolicy retentionPolicy;
    private RefundVisibilityMatrix matrix;

    private RefundPolicy policy() {
        return RefundPolicy.fromRawConfig(source.raw());
    }

    @BeforeEach
    void setUp() {
        RefundPolicy p = policy();
        matrix = RefundVisibilityMatrix.fromConfigJson(source.raw().visibilityJson());
        retentionPolicy = RetentionPolicy.of(p);
        port = new InMemoryRefundWorkOrderPort();
        service = new RefundWorkOrderService(
                port,
                // 🛑 存在性替身取 alwaysPresent：本类构造的 CUSTOMER / STORE 是纯符号 ID，
                //    没有真实 customer / store 行。若这里用 alwaysAbsent，全部 create 用例
                //    都会以 400·1001 失败，而那是【另一条】用例该断言的事
                //    （见 RefundSubjectVerifier 的接口注释：两个替身成对存在，
                //     分别钉住"业务层"与"装配层"两侧的判据）。
                com.diaoyuanyun.dy.app.refund.domain.RefundSubjectVerifier.alwaysPresent(),
                p, matrix, RefundRecordingPolicy.of(p), retentionPolicy);
    }

    // ==================================================================
    // 一、可见性两段式（"你是谁"与"你能否看"必须分两步）
    // ==================================================================

    @Nested
    @DisplayName("一 · 可见性两段式")
    class VisibilityTwoStep {

        /**
         * 🛑 本条的判据是「<b>抛错的角色集合</b>恰为三个」而不是"三个都抛"。
         * 后者在"四个可见角色里有两个也被误拒"时仍然绿 ——
         * 而误拒的后果比误放更隐蔽：它会以"这个岗位就是这么设计的"被接受。
         */
        @Test
        @DisplayName("① 被拒角色恰为 {客户, 调理师, 门店客服}，且一个不多一个不少")
        void admitted_and_denied_role_sets_are_exact() {
            List<String> allTokenRoles = new ArrayList<>();
            for (RefundAudienceRole r : RefundAudienceRole.values()) {
                r.tokenRole().ifPresent(allTokenRoles::add);
            }
            // 🛑 门店客服的 tokenRole 为空（无端、无账号），正常遍历【取不到它】——
            //    但本断言要证的恰恰是"它进不来"。故显式把它的 configKey 也当作一次
            //    尝试性的 token 角色喂进去：若 guard 只按"是否在 tokenRole 表里"判定，
            //    它会被拒；若有人哪天给它补了一个 tokenRole，这里会红。
            allTokenRoles.add(RefundAudienceRole.STORE_CUSTOMER_SERVICE.configKey());

            Set<String> admitted = new LinkedHashSet<>();
            Set<String> denied = new LinkedHashSet<>();
            for (String t : allTokenRoles) {
                try {
                    service.requireVisible(t);
                    admitted.add(t);
                } catch (BizException e) {
                    assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), e.getCode(),
                            "被拒原因必须归 2001（可见性），不得是别的码 —— 角色 " + t);
                    denied.add(t);
                }
            }

            // 门槛用【token 角色】表达：门店客服无 token 角色，故它必然在 denied
            //（它连"你是谁"这一步都过不去 —— 这正是"无端 = 无账号"的机械后果）
            assertEquals(Set.of("meridian", "manager", "area", "hq"), admitted,
                    "可见的 staff 侧 token 角色应恰为 {meridian, manager, area, hq} —— "
                            + "经络师 + 门店负责人 + 区域督导 + 总部运营。实得: " + admitted);
            assertEquals(Set.of("client", "therapist", "store_customer_service"), denied,
                    "被拒 token 角色应恰为 {client, therapist, store_customer_service} —— "
                            + "客户 / 调理师 / 门店客服。实得: " + denied);
        }

        @Test
        @DisplayName("② 未登记角色 fail-closed 抛 2001，且拒绝理由落在【身份解析】而非矩阵")
        void unknown_token_role_is_rejected_not_fallen_back() {
            for (String bogus : List.of("", "   ", "mana ger", "MANAGER", "super_admin", "老板")) {
                BizException e = assertThrows(BizException.class,
                        () -> service.requireVisible(bogus),
                        "未登记 / 空角色必须抛 —— 回落会让『有人用了系统不认识的角色』无人知晓: " + bogus);
                assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), e.getCode());

                // 🛑 只断言"抛了 2001"【不足以】证明 fail-closed。反向验证（RV-S2-4-3
                //    把未登记角色回落成客户）实测出这一点：回落同样会抛 2001 ——
                //    因为矩阵也会拒掉"客户"。两者在【错误码】上完全不可分，
                //    只在【出处】上可分：
                //      未登记     → 系统不认识这个角色（配置侧问题）
                //      已登记不可见 → 权限配置生效了（正常拒绝）
                //    故本断言把"必须指名身份解析那一段"钉住 —— 少了它，回落注入
                //    可以静默通过，而它正是 fail-closed 要防的那件事。
                assertTrue(e.getDevMessage().contains("不在退款受众登记表内")
                                || e.getDevMessage().contains("未携带 token 角色"),
                        "拒绝理由必须落在【身份解析】（未登记 / 未携带）这一段，"
                                + "而不是矩阵的『无可见性档位』—— 否则本断言与"
                                + "『已登记但不可见』无法区分。角色=" + bogus
                                + "，实得消息: " + e.getDevMessage());
            }
        }

        @Test
        @DisplayName("③ 硬锁三角色在矩阵层就被拒（不依赖本服务）")
        void hard_invisible_roles_are_locked_at_matrix_level() {
            assertEquals(Set.of(RefundAudienceRole.CUSTOMER,
                            RefundAudienceRole.THERAPIST,
                            RefundAudienceRole.STORE_CUSTOMER_SERVICE),
                    RefundVisibilityMatrix.hardInvisibleRoles(),
                    "硬锁三角色是 §2.2 定值冻结 + 业务方 2026-09-16 裁定的机械落点");
            for (RefundAudienceRole r : RefundVisibilityMatrix.hardInvisibleRoles()) {
                assertFalse(matrix.canSee(r), "硬锁角色不得可见: " + r.configKey());
            }
        }

        @Test
        @DisplayName("④ 门店客服 tokenRole 为 null —— 『无端』不是遗漏")
        void store_customer_service_has_no_token_role() {
            assertTrue(RefundAudienceRole.STORE_CUSTOMER_SERVICE.tokenRole().isEmpty(),
                    "门店客服无端、无账号 → tokenRole 必须为空『Optional.empty』；"
                            + "若有值，说明它被误当成一个可用账号角色");
            assertTrue(RefundAudienceRole.STORE_CUSTOMER_SERVICE.endRoleCode().isEmpty(),
                    "门店客服无端 → 端角色码也必须为空");
            // 但它必须仍在枚举里（否则 #40 的键集解析会把它报成"未知角色"）
            assertTrue(RefundAudienceRole.allConfigKeys().contains("store_customer_service"),
                    "门店客服必须留在受众角色表里 —— 移除会让 #40 的该键被报成『未知角色』，"
                            + "把一次『已裁定的不可见』误报成『配置有误』");
        }
    }

    // ==================================================================
    // 二、G4 审批：可见 ≠ 可审批
    // ==================================================================

    @Nested
    @DisplayName("二 · G4 审批：可见 ≠ 可审批")
    class ApprovalGate {

        @Test
        @DisplayName("⑤ 区域督导【能看见但过不了审批闸】—— 真实请求上可复现")
        void area_supervisor_is_visible_but_not_approvable() {
            // 第一步：他确实看得见（否则本断言退化成"他被拒了"，测不出"可见≠可审批"）
            assertNotNull(service.requireVisible("area"),
                    "区域督导必须能通过可见性判定（§2.2『区域督导 ✓ 可见』）");

            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.A);

            // 第二步：他过不了审批闸
            BizException e = assertThrows(BizException.class,
                    () -> service.approve(TENANT, wo.refundId(), "area", APPROVER, DEPUTY.toString()),
                    "区域督导不得审批（§2.2『可见、不审批』）—— "
                            + "若这里通过，则『可见≠可审批』在最需要它的角色上失效");
            assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("不属退款审批人集合"),
                    "错误消息必须点名『不属审批人集合』而不是笼统的『无权』（P0-08 不得模糊报错）");
            // 🛑 拒绝理由必须落在【唯一落点】上：RetentionPolicy.assertApproverIsNotDeputy。
            //    本断言把"白名单只有一份实现"这件事钉住 —— 若有人哪天又在服务层写一份
            //    contains(...) 判断，那条路径会先命中、错误消息的出处就变了。
            assertTrue(e.getDevMessage().contains("§2.2"),
                    "拒绝理由必须能追到 §2.2（区域督导『可见、不审批』）—— 而不是一条无出处的白名单比较");
        }

        /**
         * 🛑 本条补的是一个<b>真实覆盖缺口</b>，不是"顺手多加一条"。
         *
         * <p>反向验证（RV-S2-4-4）把服务层 approve 里的 {@code requireVisible(tokenRole)}
         * 整行替换成硬编码角色之后，套件里<b>只有 ⑤ 变红</b> —— 而 ⑤ 原本问的是
         * "督导可见却不可审批"。也就是说：把可见性闸从审批路径上<b>整个拆掉</b>，
         * 竟没有一条断言在问"那客户呢？"。
         *
         * <p>而 G4 与其它四条端点共用同一道可见性闸（见 {@code RefundWorkOrderService}
         * 的"三步固定顺序"）。契约逐字要求本域<b>全部</b>端点对客户一律 403 ——
         * 若审批路径跳过它，"客户一律 403"就只在 G1/G2/G3/G5 生效，
         * 而漏掉的那条是<b>金额动作</b>。
         *
         * <p>故本条把两道拒绝都钉在 G4 上：<b>已登记但不可见</b>（客户 → 档位问题）
         * 与<b>未登记</b>（系统不认识 → 配置侧问题）。两者的错误码都是 2001，
         * 但出处不同 —— 只断言错误码会让两者互相掩盖（同 ② 的教训）。
         */
        @Test
        @DisplayName("⑤ter 不可见 / 未登记角色连审批闸都到不了 —— G4 与其它端点同一道闸")
        void non_visible_role_cannot_reach_the_approval_gate() {
            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.A);

            BizException invisible = assertThrows(BizException.class,
                    () -> service.approve(TENANT, wo.refundId(), "client", APPROVER, DEPUTY.toString()),
                    "客户对退款域无可见性 —— 审批路径必须与其它四条端点同一道闸。"
                            + "若这里放行，『本域全部端点对客户一律 403』就只在 G1/G2/G3/G5 生效，"
                            + "而漏掉的那一条是金额动作");
            assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), invisible.getCode());
            assertTrue(invisible.getDevMessage().contains("无可见性档位"),
                    "拒绝理由必须落在【矩阵档位】这一段（已登记但不可见 = 权限配置生效了，正常拒绝）"
                            + "，实得: " + invisible.getDevMessage());

            BizException unknown = assertThrows(BizException.class,
                    () -> service.approve(TENANT, wo.refundId(), "super_admin", APPROVER, DEPUTY.toString()),
                    "未登记角色必须连审批闸都到不了 —— 否则有人用了一个系统不认识的角色码，"
                            + "而它能批金额");
            assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), unknown.getCode());
            assertTrue(unknown.getDevMessage().contains("不在退款受众登记表内"),
                    "拒绝理由必须落在【身份解析】这一段（系统不认识这个角色 = 配置侧问题），"
                            + "实得: " + unknown.getDevMessage());
        }

        @Test
        @DisplayName("⑤bis 白名单判定【只有一个落点】—— 服务层不得再写一份 contains 判断")
        void approver_whitelist_has_a_single_owner() throws Exception {
            // 🛑 本断言是反向验证（RV-S2-4-4）逼出来的产物，不是"顺手加的"。
            //    实测：把服务层原有的那份 contains(...) 关掉，⑤ 仍然红 ——
            //    证明真正拦下督导的是 RetentionPolicy.assertApproverIsNotDeputy，
            //    服务层那份的价值是零，代价是"改白名单要改两处、漏改的那处不报错"。
            //    故服务层那份被删除，本条把删除结果钉住。
            Path svc = Path.of("src/main/java/com/diaoyuanyun/dy/app/refund/service/"
                    + "RefundWorkOrderService.java");
            assertTrue(Files.isRegularFile(svc), "服务层源码必须存在: " + svc.toAbsolutePath());
            String text = stripComments(Files.readString(svc, StandardCharsets.UTF_8));
            assertFalse(text.isBlank(), "剥离注释后不应为空 —— 否则本断言扫的是一份空文本");

            // 允许【注释里】提到 approverTokenRoles（服务层的注释正在解释"为什么把它删掉"），
            // 但【可执行代码】里不得用它做判定。🛑 必须先剥离注释再扫：
            // 本断言的首版直接扫全文，于是"注释里写着 approverTokenRoles().contains(...) 已被删除"
            // 这句话本身把自己判红了 —— 一个恒红的断言立即失去价值（它不再区分好坏代码），
            // 而更糟的是它会诱使人把那段解释性注释删掉，把设计意图一起丢掉。
            // 剥离注释是对的做法：断言的目标是"代码里没有第二份判定"，不是"文档里不许提到它"。
            for (String forbidden : List.of(
                    "approverTokenRoles().contains",
                    "approverTokenRoles().stream",
                    "approvesRefund()")) {
                assertFalse(text.contains(forbidden),
                        "服务层【可执行代码】里出现了第二份白名单判定: " + forbidden
                                + " —— 审批白名单的唯一落点是 RetentionPolicy.assertApproverIsNotDeputy。"
                                + "两份实现必然分叉，而分叉的那一侧直接决定谁能批钱；"
                                + "且漏改的那一处不会报错，只会安静地按旧白名单放行");
            }
            assertTrue(text.contains("assertApproverIsNotDeputy"),
                    "服务层必须经唯一落点做审批资格校验 —— 否则本断言在测一个没做校验的服务层");
        }

        @Test
        @DisplayName("⑥ 审批白名单恰为 {manager, hq}，是契约 [admin] 展开集的真子集，差集恰为 {area}")
        void approver_whitelist_is_proper_subset_of_contract_admin() {
            Set<String> whitelist = RefundAudienceRole.approverTokenRoles();
            Set<String> contractAdminExpanded = Set.of("manager", "area", "hq");

            assertEquals(Set.of("manager", "hq"), whitelist,
                    "审批白名单应恰为 {manager, hq} —— 与 §2.2『督导可见、不审批』逐字一致");
            assertTrue(contractAdminExpanded.containsAll(whitelist),
                    "白名单必须是契约 x-callable-roles: [admin] 展开集的子集 —— 不得越权新增角色");
            Set<String> diff = new LinkedHashSet<>(contractAdminExpanded);
            diff.removeAll(whitelist);
            assertEquals(Set.of("area"), diff,
                    "差集必须恰为 {area} —— 被排除的是且仅是区域督导。"
                            + "若差集多出 manager 或 hq，说明白名单被收窄得超出了上游语义");
        }

        @Test
        @DisplayName("⑦ 代录者不可审批 —— 校验的是【人】而非角色（经理本就有审批权）")
        void deputy_cannot_approve_his_own_work_order() {
            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.A);

            // manager 是合法审批人
            assertNotNull(service.approve(TENANT, wo.refundId(), "manager", APPROVER, DEPUTY.toString()),
                    "门店负责人应可审批 —— 前提是他不是这张单子的代录人");

            // 但代录人与审批人同一人 → 拒
            BizException e = assertThrows(BizException.class,
                    () -> service.approve(TENANT, wo.refundId(), "manager", DEPUTY, DEPUTY.toString()),
                    "代录者不可审批（P0-19 / R4b ④ 动机阀门）—— "
                            + "否则『原话不可编辑 / 延迟进异常名单 / 超时升级』三条可被同一人自建自批绕过");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("代录者不可审批"));
        }

        @Test
        @DisplayName("⑧ 身份链缺失时【抛】而不是放行 —— 动机阀门不得在最需要它时失效")
        void missing_deputy_identity_is_rejected_not_skipped() {
            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.A);
            for (String missing : Arrays.asList(null, "", "   ")) {
                BizException e = assertThrows(BizException.class,
                        () -> service.approve(TENANT, wo.refundId(), "manager", APPROVER, missing),
                        "代录人身份缺失必须抛 —— 放行会让『身份拿不到就跳过』成为一条可用路径"
                                + "（而身份链断掉时正是最需要这道阀门的时候）");
                assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            }
        }

        @Test
        @DisplayName("⑨ 入口 B + 终止 = 全额免审批 —— 出口集中的【唯一例外】")
        void first_cycle_termination_is_exempt_from_headquarters() {
            // 入口 B 的豁免是【按入口+结局】判定的布尔条件，与工单处于继续还是终止无关 ——
            // 故本断言直接在口径层构造「入口 B + 终止」，不在替身上伪造一个终止态：
            // 替身没有入口 B 的"公式判定"这一动作，伪造它等于让测试绕过系统判定的来源。
            RetentionPolicy.ApprovalTarget exempt =
                    retentionPolicy.outcomeApproval(RefundEntry.B, RefundOutcome.TERMINATE);
            assertEquals(RetentionPolicy.ApprovalTarget.FULL_EXEMPT_FIRST_CYCLE, exempt);
            assertTrue(exempt.isExemptFromHeadquarters(),
                    "入口 B + 终止 = 全额免审批（P0-14 第三层）—— 出口集中的唯一例外。"
                            + "🛑 若这里变成须审批，等于把系统按公式判定的结论重新交回给人 —— "
                            + "那正是 Top1 痛点（判定权在人手里）的复发通道");
            assertFalse(exempt.requiresHeadquarters(),
                    "免审批路径不得反过来要求总部出手 —— 两个判定必须互斥");

            // 对照：入口 A + 终止 → 须总部审批（证明"唯一例外"的"唯一"是被真的收窄的）
            assertTrue(retentionPolicy.outcomeApproval(RefundEntry.A, RefundOutcome.TERMINATE)
                            .requiresHeadquarters(),
                    "入口 A 的终止仍须总部审批 —— 其余终止路径都要审批，"
                            + "这正是『唯一例外』这句话的可执行形态");

            // 且服务层对入口 B 的【继续中】工单确实拒审批（未动钱 + 该路径免审批）
            RefundWorkOrderRow b = seedWorkOrder(RefundEntry.B, RefundReasonCode.EFFECT_BELOW_EXPECTATION);
            BizException e = assertThrows(BizException.class,
                    () -> service.approve(TENANT, b.refundId(), "manager", APPROVER, DEPUTY.toString()),
                    "继续中的入口 B 工单无可审批内容 —— 不得因为『它免审批』就允许一次空审批落库");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
        }

        @Test
        @DisplayName("⑩ 继续中的工单无可审批内容（未动钱）")
        void continue_outcome_has_nothing_to_approve() {
            // 入口 A 的工单停在"继续"上（尚未终止）
            RefundWorkOrderRow wo = seedWorkOrder(RefundEntry.A, RefundReasonCode.EFFECT_BELOW_EXPECTATION);
            assertEquals(RefundOutcome.CONTINUE, wo.outcome());
            BizException e = assertThrows(BizException.class,
                    () -> service.approve(TENANT, wo.refundId(), "manager", APPROVER, DEPUTY.toString()),
                    "继续中的工单未动钱，无可审批内容 —— "
                            + "若这里放行，会留下一批『审批过但没批任何东西』的空记录");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
        }

        @Test
        @DisplayName("⑪ 健康风险直终止【不】豁免审批 —— PRD 只豁免了它的挽留")
        void health_risk_termination_is_not_exempt_from_approval() {
            // 健康风险原因码 + 入口 A + 终止 → 仍须总部审批
            RetentionPolicy.ApprovalTarget t = retentionPolicy.outcomeApproval(
                    RefundEntry.A, RefundOutcome.TERMINATE);
            assertTrue(t.requiresHeadquarters(),
                    "健康风险事件类只豁免【挽留】（不走挽留直接终止），"
                            + "不豁免【审批】—— 把两处豁免混成一件事会让安全事件绕过出口集中");
        }
    }

    // ==================================================================
    // 三、G3 挽留的两处豁免
    // ==================================================================

    @Nested
    @DisplayName("三 · G3 挽留的两处豁免")
    class RetentionExemptions {

        @Test
        @DisplayName("⑫ 入口 B 不经挽留 —— 且拒绝理由点明【逻辑矛盾】")
        void entry_b_rejects_retention_with_logical_contradiction() {
            // 🛑 入口 B 在正常流程里会立即进入终止（不经挽留主动终止），
            //    故本断言先把前提推到"终止"，再证明它仍拒挽留 —— 那才是真实路径。
            //    若停在"继续"上测，会正中"入口 B 是拒绝挽留"这一条，
            //    而不是"它对一张已终止的工单也拒绝挽留"（后者才是归档前最后一道防线）。
            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.B);
            BizException e = assertThrows(BizException.class,
                    () -> service.recordRetention(TENANT, wo.refundId(),
                            retentionReq(RetentionResult.ACCEPT_CONTINUE), STAFF, "tester"));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("逻辑矛盾"),
                    "入口 B 的豁免理由是『逻辑矛盾』（已判定服务无效却劝客户继续）—— "
                            + "拒绝消息必须点出这一条，而不是笼统的『不允许』");
            assertEquals(0, port.totalRetentions(), "被拒的挽留不得落库（一次都没写）");
        }

        @Test
        @DisplayName("⑬ 健康风险事件类不走挽留 —— 且拒绝理由点明【安全优先】")
        void health_risk_reason_rejects_retention_with_safety_reason() {
            // 🛑 reason 必须是一个【真的是健康风险】的原因码。若随便取一个非健康风险码，
            //    本断言会命中"入口 A 须挽留"那条正常路径 —— 于是它会红在"没抛"上，
            //    而真正的判定（健康风险豁免）从未被触达。
            RefundWorkOrderRow wo = seedWorkOrder(RefundEntry.A, RefundReasonCode.SYMPTOM_WORSENED);
            BizException e = assertThrows(BizException.class,
                    () -> service.recordRetention(TENANT, wo.refundId(),
                            retentionReq(RetentionResult.ACCEPT_CONTINUE), STAFF, "tester"));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("安全优先"),
                    "健康风险的豁免理由是【安全优先】—— 客户报告症状加重时，"
                            + "挽留话术会把它变成为销售机会辩护。这一点必须在拒绝消息里可读");
            assertEquals(0, port.totalRetentions());
        }

        @Test
        @DisplayName("⑭ 两种豁免必须【可分】—— 合成一个 EXEMPT 会让稽核看不出豁免的是哪种")
        void two_exemptions_are_distinguishable() {
            RetentionPolicy.RetentionRequirement b =
                    retentionPolicy.requirementOf(RefundEntry.B, RefundReasonCode.EFFECT_BELOW_EXPECTATION);
            RetentionPolicy.RetentionRequirement h =
                    retentionPolicy.requirementOf(RefundEntry.A, RefundReasonCode.SYMPTOM_WORSENED);

            assertEquals(RetentionPolicy.RetentionRequirement.EXEMPT_FIRST_CYCLE, b);
            assertEquals(RetentionPolicy.RetentionRequirement.EXEMPT_HEALTH_RISK, h);
            assertTrue(b.isExempt() && h.isExempt(), "两者都豁免挽留");
            assertFalse(b.isHealthRiskExemption(), "入口 B 不是安全事件");
            assertTrue(h.isHealthRiskExemption(), "健康风险必须可被识别为安全事件");
            assertEquals(2, RetentionPolicy.exemptRequirements().size(),
                    "豁免挽留的判定应恰为两个 —— 合成一个会让报表看不出豁免的是哪种");
        }

        @Test
        @DisplayName("⑮ 入口 A + 非健康风险：挽留可记录，且落库一条")
        void normal_case_records_exactly_one_retention() {
            RefundWorkOrderRow wo = seedWorkOrder(RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE);
            var row = service.recordRetention(TENANT, wo.refundId(),
                    retentionReq(RetentionResult.ACCEPT_WITH_ADJUSTMENT), STAFF, "tester");
            assertNotNull(row);
            assertEquals(RetentionResult.ACCEPT_WITH_ADJUSTMENT, row.result());
            assertEquals(1, port.totalRetentions());
            assertEquals(1, port.retentionInsertCount());
        }

        @Test
        @DisplayName("⑯ 挽留结论的审批落点：成功无需审批 / 失败须总部（不依赖缺失的分段阈值）")
        void retention_plan_approval_is_decidable_without_missing_threshold() {
            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    retentionPolicy.retentionPlanApproval(RetentionResult.ACCEPT_CONTINUE));
            assertEquals(RetentionPolicy.ApprovalTarget.NO_APPROVAL,
                    retentionPolicy.retentionPlanApproval(RetentionResult.ACCEPT_WITH_ADJUSTMENT));

            RetentionPolicy.ApprovalTarget rejected =
                    retentionPolicy.retentionPlanApproval(RetentionResult.REJECT_ENTER_TERMINATION);
            assertTrue(rejected.requiresHeadquarters(),
                    "客户拒挽留 → 须总部审批。它是【布尔条件】，不依赖缺失的分段阈值 —— "
                            + "故必须在阈值未配置时仍可判定");
        }
    }

    // ==================================================================
    // 三·b 三字段捆绑自检（端口 / 仓储可被直接调用的那一层）
    // ==================================================================

    /**
     * <h2>🛑 为什么必须单独测这一层，而不是靠 G1 立案的负面用例</h2>
     * {@code assertBundleCanBePersisted} 的五条规则在<b>服务层走不到</b>：
     * {@code resolveRequestedAt} 在此之前就会因"无可核实来源"或"客户自证缺凭据"抛错。
     * 于是这层看起来像"多一道没人用的防线"。
     *
     * <p>但它<b>不是</b>多余的：它是<b>端口与仓储可被直接调用</b>时的最后一道自检 ——
     * 而"直接构造一个行对象写进去"正是绕过服务层最容易发生的形态
     * （例如补数据脚本、将来的导入工具、或有人为了一次紧急修数据而写的方法）。
     * 故本组用例<b>直接调用</b>这一层，让"它有牙齿"成为可断言的事实，
     * 而不是靠服务层那几条间接推想。
     */
    @Nested
    @DisplayName("三·b 三字段捆绑自检")
    class BundleGate {

        private final RefundRecordingPolicy rec = RefundRecordingPolicy.of(policy());

        /** 规则①：无 requested_at 却有 claimed —— 最危险的一种写法。 */
        @Test
        @DisplayName("㊽ 无起点却有客户主张 → 抛（它要么跳过计时，要么诱导后人拿 claimed 顶上）")
        void bundle_gate_rejects_claimed_without_requested_at() {
            BizException e = assertThrows(BizException.class, () -> rec.assertBundleCanBePersisted(
                    new RefundRecordingPolicy.RecordingBundle(
                            null, null, Instant.now(), null, Instant.now()))); 
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("requested_at_claimed"),
                    "拒绝消息必须点名 requested_at_claimed —— "
                            + "否则排查者会以为是『requested_at 必填』而随手补一个时间");
        }

        /** 规则②：有 requested_at 却无来源标注。 */
        @Test
        @DisplayName("㊾ 有起点却无来源标注 → 抛（否则『时间照填、来源不填』可整段抹掉 C1-2）")
        void bundle_gate_rejects_requested_at_without_source() {
            BizException e = assertThrows(BizException.class, () -> rec.assertBundleCanBePersisted(
                    new RefundRecordingPolicy.RecordingBundle(
                            Instant.now().minusSeconds(600), null, null, null, Instant.now())));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("requested_at_source"),
                    "拒绝消息必须点名来源标注这一个字段");
        }

        /** 规则③：来源=客户自证却无凭据。 */
        @Test
        @DisplayName("㊿ 来源为客户自证却无凭据 → 抛（可核实性不成立，应走 claimed 留档）")
        void bundle_gate_rejects_customer_proven_without_evidence() {
            BizException e = assertThrows(BizException.class, () -> rec.assertBundleCanBePersisted(
                    new RefundRecordingPolicy.RecordingBundle(
                            Instant.now().minusSeconds(600),
                            com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource.CUSTOMER_PROVEN.code(),
                            null, null, Instant.now())));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("凭据"));
        }

        /** 规则④：起点晚于代录时间（会让延迟为负）。 */
        @Test
        @DisplayName("⑤① 起点晚于代录时间 → 抛（下游取绝对值会把写错变成一次合规判定）")
        void bundle_gate_rejects_requested_at_after_recorded_at() {
            BizException e = assertThrows(BizException.class, () -> rec.assertBundleCanBePersisted(
                    new RefundRecordingPolicy.RecordingBundle(
                            Instant.now().plusSeconds(600),
                            com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource.CUSTOMER_PROVEN.code(),
                            null, "shot://e.png", Instant.now())));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("晚于"),
                    "拒绝消息必须点明『requested_at 晚于 recorded_at』这一对具体字段 —— "
                            + "笼统的『时间不合法』会让排查者去改错那个字段");
        }

        /** 正例：齐备的捆绑可通过（否则上面四条可能只是"什么都拒"）。 */
        @Test
        @DisplayName("⑤② 齐备的捆绑可通过 —— 上面四条否则可能只是『什么都拒』")
        void bundle_gate_accepts_a_complete_bundle() {
            rec.assertBundleCanBePersisted(new RefundRecordingPolicy.RecordingBundle(
                    Instant.now().minusSeconds(600),
                    com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource.CUSTOMER_PROVEN.code(),
                    Instant.now().minusSeconds(1200), "shot://e.png", Instant.now()));
            // 入口 B 的全空捆绑同样合法（它不存在"客户何时提出"这一问）
            rec.assertBundleCanBePersisted(new RefundRecordingPolicy.RecordingBundle(
                    null, null, null, null, Instant.now()));
        }
    }

    // ==================================================================
    // 四、归档只读与结局推进
    // ==================================================================

    @Nested
    @DisplayName("四 · 归档只读与结局推进")
    class ArchiveAndOutcome {

        @Test
        @DisplayName("⑰ 归档后只读：挽留被拒，且拒绝理由点明『不是一次收口』")
        void archived_work_order_is_read_only() {
            // 🛑 前置必须是【真的走完域路径到达终止】的工单，再置归档 ——
            //    而不是"原地把 outcome 塞成归档"。前者证明"终态 → 归档 → 只读"这条
            //    真实路径被堵住；后者只证明"有人直接把状态改成归档后恰好被拒"。
            RefundWorkOrderRow wo = terminatedWorkOrder(RefundEntry.A);
            port.markArchivedForTest(wo.refundId());

            BizException e = assertThrows(BizException.class,
                    () -> service.recordRetention(TENANT, wo.refundId(),
                            retentionReq(RetentionResult.ACCEPT_CONTINUE), STAFF, "tester"));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("归档后只读"),
                    "归档后拒写的理由必须点明『归档后只读』—— 而不是笼统的『不允许』");
            assertEquals(0, port.totalRetentions(), "被拒的挽留不得落库");
        }

        @Test
        @DisplayName("⑱ 未归档的工单可写；归档后拒写（同一断言的两面）")
        void open_is_writable_closed_is_not() {
            assertEquals(true, retentionPolicy.archiveIfOpen(RefundOutcome.CONTINUE));
            assertEquals(true, retentionPolicy.archiveIfOpen(RefundOutcome.TERMINATE));
            assertEquals(false, retentionPolicy.archiveIfOpen(RefundOutcome.ARCHIVED),
                    "已归档再归档是幂等的（返回 false 而非抛）—— 归档可能被重试（网络重发 / 批处理重跑）");

            // 未归档可写
            retentionPolicy.assertWritable(RefundOutcome.CONTINUE);
            retentionPolicy.assertWritable(RefundOutcome.TERMINATE);

            // 归档后拒写
            BizException e = assertThrows(BizException.class,
                    () -> retentionPolicy.assertWritable(RefundOutcome.ARCHIVED));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("归档后只读"));
        }

        @Test
        @DisplayName("⑲ updateOutcome 拒【同值】—— 不改动任何东西的写入在乐观并发下会静默成功")
        void same_outcome_update_is_rejected() {
            RefundWorkOrderRow wo = seedWorkOrder(RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE);
            BizException e = assertThrows(BizException.class,
                    () -> port.updateOutcome(TENANT, wo.refundId(),
                            RefundOutcome.CONTINUE, RefundOutcome.CONTINUE),
                    "同值写必须被拒 —— 它在乐观并发下会静默命中自己（WHERE outcome = ?），"
                            + "让调用方误以为推进发生过。骑行端口上与真库同一口径："
                            + "确切的拒绝发生在【进入 SQL 之前】，故真库与内存替身都拒");
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode(),
                    "同值写的成因是【调用方构造错误】而不是并发冲突 —— 应归 422（5001）"
                            + "而不是 409（4001）。指错码位会让调用方去重读、重试，"
                            + "而真正该做的是修代码");
        }

        @Test
        @DisplayName("⑳ 乐观并发：期望值不符即 4001（不得是 3001）")
        void version_conflict_is_4001_not_404() {
            RefundWorkOrderRow wo = seedWorkOrder(RefundEntry.A, RefundReasonCode.TRUST_OR_PRICE);
            port.updateOutcome(TENANT, wo.refundId(), RefundOutcome.CONTINUE, RefundOutcome.TERMINATE);

            // 再用【过时的】期望值（CONTINUE）推进 → 冲突
            // 🛑 目标结局取 TERMINATE 而【不是】ARCHIVED：归档目标在进入 SQL 之前
            //    就被"不得经 updateOutcome 抵达归档"拦下（归 5001），
            //    于是本断言会红在 5001 上、而它想证的"乐观并发冲突 = 4001"从未被触达。
            //    两个拒绝理由都是对的，但只有一个是本断言要问的那个 ——
            //    测试的前提必须落在自己那条判定的可达路径上。
            BizException e = assertThrows(BizException.class,
                    () -> port.updateOutcome(TENANT, wo.refundId(),
                            RefundOutcome.CONTINUE, RefundOutcome.TERMINATE));
            assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), e.getCode(),
                    "乐观并发冲突必须归 4001（契约冲突）。若归 3001（未找到），"
                            + "调用方会去核对 ID 而不是重新读取 —— 排查方向被指错");
        }

        @Test
        @DisplayName("㉑ 归档不得经 updateOutcome 抵达（须走归档路径以同时落结案清单）")
        void archive_is_not_reachable_through_plain_outcome_update() {
            assertThrows(BizException.class,
                    () -> retentionPolicy.outcomeApproval(RefundEntry.A, RefundOutcome.ARCHIVED),
                    "对已归档工单问审批落点是把『归档后只读』读丢了；"
                            + "静默返回某个审批目标会让一次对已结案工单的写操作企图看起来正常");
        }

        @Test
        @DisplayName("㉒ 不存在『停在继续或终止、永不归档』的合法工单（requiresArchive 的语义）")
        void every_open_outcome_requires_archive() {
            assertTrue(retentionPolicy.requiresArchive(RefundOutcome.CONTINUE),
                    "继续 = 未结案，必须被归档动作收口");
            assertTrue(retentionPolicy.requiresArchive(RefundOutcome.TERMINATE),
                    "终止 = 未结案，必须被归档动作收口");
            assertFalse(retentionPolicy.requiresArchive(RefundOutcome.ARCHIVED));
            assertEquals(2, Arrays.stream(RefundOutcome.values())
                            .filter(retentionPolicy::requiresArchive).count(),
                    "『四种结局全部强制归档』的可执行形态：未结案的结局应恰为两个");
        }
    }

    // ==================================================================
    // 五、G1 立案：三字段纪律
    // ==================================================================

    @Nested
    @DisplayName("五 · G1 立案：三字段纪律")
    class CreateDiscipline {

        @Test
        @DisplayName("㉓ 客户自证无凭据 → 拒（否则『客户自证』与『客户主张』就变成同一件事）")
        void customer_proven_without_evidence_is_rejected() {
            BizException e = assertThrows(BizException.class,
                    () -> service.create(TENANT, createReq(null, Instant.now().minusSeconds(3600), null),
                            "tester"));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("凭据"),
                    "拒绝消息必须点明『凭据引用』—— P0-19 C1-2 逐字要求客户自证须附凭据");
        }

        @Test
        @DisplayName("㉔ 有 requested_at 却无来源标注 → 拒（『时间照填、来源不填』不得抹掉 C1-2）")
        void requested_at_without_source_is_rejected() {
            // 这一条只能通过"只给 claimed、不给可核实来源"间接验证：
            // 归一后 requested_at 必为 null 而 claimed 有值 → 命中第 1 条拒绝
            BizException e = assertThrows(BizException.class,
                    () -> service.create(TENANT, createReq(Instant.now().minusSeconds(7200), null, null),
                            "tester"));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            assertTrue(e.getDevMessage().contains("requested_at_claimed")
                            || e.getDevMessage().contains("claimed"),
                    "只有客户主张而无 requested_at 时必须拒 —— "
                            + "否则要么计时被跳过（取消纪律），要么诱导后人拿 claimed 顶上");
        }

        @Test
        @DisplayName("㉕ 只带 claimed 也不得落库 —— claimed 永不进 24h 计时")
        void claimed_alone_cannot_be_persisted() {
            int before = port.totalWorkOrders();
            assertThrows(BizException.class,
                    () -> service.create(TENANT, createReq(Instant.now(), null, null), "tester"));
            assertEquals(before, port.totalWorkOrders(), "被拒的立案不得落库");
        }

        @Test
        @DisplayName("㉖ 客户自证 + 凭据 → 落库，且来源标注写入（三字段同时有值）")
        void proven_with_evidence_persists_with_source() {
            Instant proven = Instant.now().minusSeconds(3600);
            RefundWorkOrderRow row = service.create(TENANT,
                    createReq(null, proven, "shot://wechat/2026-09-25/abc.png"), "tester");

            assertNotNull(row.requestedAt(), "归一后的起点必须落库");
            assertEquals(proven, row.requestedAt());
            assertEquals(com.diaoyuanyun.dy.app.refund.domain.RequestedAtSource.CUSTOMER_PROVEN,
                    row.requestedAtSource(), "来源标注必须与 requested_at 成对落库");
            assertEquals("shot://wechat/2026-09-25/abc.png", row.requestedAtSourceRef());
            assertNull(row.requestedAtClaimed(), "未传 claimed 时应为 null（不得被填成 requestedAt）");
            assertEquals(1, port.totalWorkOrders());
        }

        @Test
        @DisplayName("㉗ claimed 落库但【不等于】requested_at（两者必须分别写入，不得合并）")
        void claimed_is_persisted_separately_from_requested_at() {
            Instant claimed = Instant.now().minusSeconds(86400);     // 客户说 24h 前
            Instant proven = Instant.now().minusSeconds(3600);        // 我们能核实的只有 1h 前
            RefundWorkOrderRow row = service.create(TENANT,
                    createReq(claimed, proven, "shot://evidence/1.png"), "tester");

            assertEquals(proven, row.requestedAt(), "起点必须是可核实集合的较早者，不是 claimed");
            assertEquals(claimed, row.requestedAtClaimed(), "claimed 必须原样留档");
            assertFalse(row.requestedAt().equals(row.requestedAtClaimed()),
                    "claimed 与 requested_at 若相等，本测试就测不出『两者分别写入』这条纪律");
            assertTrue(row.requestedAtClaimed().isBefore(row.requestedAt()),
                    "本案刻意构造『客户主张更早』—— 而它【不得】改写起点");
        }

        @Test
        @DisplayName("㉘ 延迟由【归一后的起点】算，且延迟为正（不得为负）")
        void delay_is_computed_from_resolved_start_not_claimed() {
            Instant claimed = Instant.now().minusSeconds(86400 * 5);
            Instant proven = Instant.now().minusSeconds(7200);
            RefundWorkOrderRow row = service.create(TENANT,
                    createReq(claimed, proven, "shot://evidence/2.png"), "tester");

            assertNotNull(row.recordingDelayH());
            assertTrue(row.recordingDelayH().signum() > 0, "正常情形延迟应为正");
            // 延迟应约等于 2h（proven → now），而不是 120h（claimed → now）
            assertTrue(row.recordingDelayH().doubleValue() < 3.0,
                    "延迟必须由归一后的起点算 —— 若用 claimed 算，这里会是 120h 左右。实得: "
                            + row.recordingDelayH());
        }

        @Test
        @DisplayName("㉙ entry 落库用 dbCode（有空格）；出站字面另有其值 —— 两个方向不得混用")
        void entry_has_two_frozen_literals_not_one() {
            RefundWorkOrderRow row = service.create(TENANT,
                    createReq(null, Instant.now().minusSeconds(600), "shot://e.png"), "tester");
            assertEquals(RefundEntry.A, row.entry());
            assertEquals("A 门店代录", row.entry().dbCode(), "持久化字面（V5 CHECK）带空格");
            assertEquals("A门店代录", row.entry().contractCode(), "出站字面（契约枚举）无空格");
            assertFalse(row.entry().dbCode().equals(row.entry().contractCode()),
                    "两套字面若相等，本断言就测不出『两个方向不得混用』这条纪律");
            // 入口 A 必须走 24h 代录计时；入口 B 不走
            assertTrue(row.subjectToDeputyWindow(), "入口 A 受 24h 代录窗口约束");
        }

        @Test
        @DisplayName("㉚ 入口 B 不受代录窗口约束（它不存在『客户何时提出』这一问）")
        void entry_b_is_not_subject_to_deputy_window() {
            // 🛑 入口 B 不得带任何 requested_at 字段 —— 同 seedWorkOrder 的注释。
            RefundWorkOrderRow row = service.create(TENANT, new RefundWorkOrderService.CreateRequest(
                    CUSTOMER, RefundEntry.B, RefundRoute.EFFECT, RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                    null, null, null, null, null, null, STORE), "tester");
            assertFalse(row.subjectToDeputyWindow(),
                    "入口 B 由首周期双不达标自动触发 —— 若被算进 24h 计时，"
                            + "报表上会出现一批永远超时、却没有可比起点的单子");
            assertNull(row.requestedAt(), "入口 B 的 requested_at 合法地为空");
            assertNull(row.requestedAtSource(), "无起点即无来源标注（两者成对）");
            assertNull(row.recordingDelayH(), "无起点即无延迟");
        }

        @Test
        @DisplayName("㉚bis 入口 B 收到任何 requested_at 字段 → 抛（不得静默丢弃）")
        void entry_b_rejects_requested_at_fields_loudly() {
            // 🛑 静默丢弃是最坏的处置：调用方以为主张已录入，而库里三字段全空、无任何报错。
            //    故这里把每个字段单独试一遍 —— 少挡一个，那条路径就会以静默丢失的形式复活。
            List<RefundWorkOrderService.CreateRequest> bad = List.of(
                    new RefundWorkOrderService.CreateRequest(CUSTOMER, RefundEntry.B, RefundRoute.EFFECT,
                            RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                            Instant.now(), null, null, null, null, null, STORE),
                    new RefundWorkOrderService.CreateRequest(CUSTOMER, RefundEntry.B, RefundRoute.EFFECT,
                            RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                            null, Instant.now(), "shot://e.png", null, null, null, STORE),
                    new RefundWorkOrderService.CreateRequest(CUSTOMER, RefundEntry.B, RefundRoute.EFFECT,
                            RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                            null, null, null, Instant.now(), null, null, STORE),
                    new RefundWorkOrderService.CreateRequest(CUSTOMER, RefundEntry.B, RefundRoute.EFFECT,
                            RefundReasonCode.EFFECT_BELOW_EXPECTATION,
                            null, null, null, null, Instant.now(), null, STORE));
            for (var req : bad) {
                BizException e = assertThrows(BizException.class,
                        () -> service.create(TENANT, req, "tester"),
                        "入口 B 带 requested_at 相关字段必须抛 —— 静默丢弃会让主张无声消失");
                assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e.getCode());
            }
            assertEquals(0, port.totalWorkOrders(), "被拒的入口 B 立案不得落库");
        }

        @Test
        @DisplayName("㉛ 带原话 → 追加一条 append-only 记录（且 recordedAt 由调用方给定）")
        void statement_is_appended_when_present() {
            RefundWorkOrderService.CreateRequest req = new RefundWorkOrderService.CreateRequest(
                    CUSTOMER, RefundEntry.A, RefundRoute.EFFECT, RefundReasonCode.TRUST_OR_PRICE,
                    null, Instant.now().minusSeconds(600), "shot://e.png", null, Instant.now(),
                    "客户原话：我觉得没什么变化，想退。", STORE);
            RefundWorkOrderRow row = service.create(TENANT, req, "tester");

            assertEquals(1, port.totalStatements());
            List<RefundStatementRow> list = service.statements(TENANT, row.refundId());
            assertEquals(1, list.size());
            assertEquals("客户原话：我觉得没什么变化，想退。", list.get(0).statementText());
            assertEquals("客户原话", list.get(0).statementSource());
            assertFalse(list.get(0).isCorrection(), "首条原话不是更正");
        }

        @Test
        @DisplayName("㉜ 不带原话 → 一条都不写（不得写空行冒充留痕）")
        void no_statement_when_absent() {
            service.create(TENANT, createReq(null, Instant.now().minusSeconds(600), "shot://e.png"),
                    "tester");
            assertEquals(0, port.totalStatements(),
                    "空原话不得写一行 —— 一行空的原话在证据链里比没有更糟（它看起来像留过痕）");
        }
    }

    // ==================================================================
    // 六、控制器形状与端点清单
    // ==================================================================

    @Nested
    @DisplayName("六 · 控制器形状与端点清单")
    class ControllerShape {

        @Test
        @DisplayName("㉝ 类级映射为 /api/v1/refunds（路径形状逐字对齐契约）")
        void class_level_mapping_matches_contract() {
            RequestMapping rm = RefundWorkOrderController.class.getAnnotation(RequestMapping.class);
            assertNotNull(rm, "控制器必须有类级 @RequestMapping");
            assertEquals(List.of("/api/v1/refunds"), List.of(rm.value()));
            assertNotNull(RefundWorkOrderController.class.getAnnotation(RestController.class));
        }

        @Test
        @DisplayName("㉞ 五条端点的 HTTP 方法与子路径逐字对齐契约 G1~G5")
        void five_endpoints_match_contract_rows() {
            List<String> shape = new ArrayList<>();
            for (Method m : RefundWorkOrderController.class.getDeclaredMethods()) {
                PostMapping post = m.getAnnotation(PostMapping.class);
                GetMapping get = m.getAnnotation(GetMapping.class);
                if (post != null) {
                    shape.add("POST " + String.join(",", post.value()) + "  [" + m.getName() + "]");
                } else if (get != null) {
                    shape.add("GET " + String.join(",", get.value()) + "  [" + m.getName() + "]");
                }
            }
            // 只保留 G1~G5 五条（自描述端点是额外的，不计入契约行）
            List<String> contractRows = shape.stream()
                    .filter(s -> !s.startsWith("GET /contract"))
                    // 🛑 排序后再比：getDeclaredMethods() 的返回顺序【未定义】，
                    //    直接比 List 会让本断言随机红/绿（本仓库实测过：同一份代码
                    //    两次运行给出不同顺序）。形状断言要证的是"五条端点的集合与路径
                    //    逐字对齐契约"，不是"编译器按某个顺序返回方法"。
                    .sorted().toList();

            assertEquals(List.of(
                            "GET /{id}  [detail]",                              // G2
                            "POST   [create]",                                  // G1
                            "POST /{id}/approvals  [approve]",                   // G4
                            "POST /{id}/receipts  [createReceipt]",              // G5
                            "POST /{id}/retentions  [createRetention]")          // G3
                            .stream().sorted().toList(),
                    contractRows,
                    "五条端点的方法与子路径必须逐字对齐契约（缺一条 = 契约行未落地；"
                            + "多一条 = 出现了契约外的端点）。实得: " + contractRows);
        }

        @Test
        @DisplayName("㉟ 四个写端点都必须标 @Idempotent（G2 读端点不需要）")
        void all_write_endpoints_are_idempotent() {
            Set<String> idempotentMethods = new LinkedHashSet<>();
            for (Method m : RefundWorkOrderController.class.getDeclaredMethods()) {
                if (m.getAnnotation(Idempotent.class) != null) {
                    idempotentMethods.add(m.getName());
                }
            }
            assertEquals(Set.of("create", "createRetention", "approve", "createReceipt"),
                    idempotentMethods,
                    "契约 §0：写接口接受 Idempotency-Key。四类重复提交的具体后果："
                            + "G1 开两张工单 / G3 挽留次数虚高 / G4 重复审批（金额）/ "
                            + "G5 回执重复一条（稀释覆盖率分母）。G2 是纯读，天然幂等");
        }

        @Test
        @DisplayName("㊱ 每个端点（含类级）都必须有 @StaffOnly 且 clientDeniedFields 非空")
        void every_endpoint_is_staff_only_with_named_denied_fields() {
            StaffOnly classLevel = RefundWorkOrderController.class.getAnnotation(StaffOnly.class);
            assertNotNull(classLevel, "类级 @StaffOnly 是兜底 —— 新端点默认受保护，"
                    + "要绕过必须显式做点什么（与『忘了贴注解就没人保护』相反）");
            assertTrue(classLevel.clientDeniedFields().length > 0,
                    "类级 clientDeniedFields 不得为空 —— 不带被拒字段名的 403 等于模糊报错（P0-08）");

            for (Method m : RefundWorkOrderController.class.getDeclaredMethods()) {
                if (Modifier.isPrivate(m.getModifiers())) {
                    continue;
                }
                if (m.getAnnotation(PostMapping.class) == null && m.getAnnotation(GetMapping.class) == null) {
                    continue;
                }
                StaffOnly ann = m.getAnnotation(StaffOnly.class);
                assertNotNull(ann, "端点 " + m.getName() + " 缺 @StaffOnly —— "
                        + "契约域 G 全部 5 行均为 x-client-forbidden: true，客户一律 403");
                assertTrue(ann.clientDeniedFields().length > 0,
                        "端点 " + m.getName() + " 的 clientDeniedFields 不得为空");
            }
        }

        @Test
        @DisplayName("㊲ 控制器【不得】提到 JDBC 仓储（源码级，补上 import 与『用上』之间的空隙）")
        void refund_controller_never_mentions_the_jdbc_ledger() throws Exception {
            Path src = Path.of("src/main/java/com/diaoyuanyun/dy/app/refund/controller/"
                    + "RefundWorkOrderController.java");
            assertTrue(Files.isRegularFile(src), "控制器源码必须存在（缺文件不得静默通过）: "
                    + src.toAbsolutePath());
            String text = Files.readString(src, StandardCharsets.UTF_8);

            for (String forbidden : List.of(
                    "RefundWorkOrderLedger", "RefundReceiptLedger",
                    "JdbcTemplate", "javax.sql.DataSource")) {
                assertFalse(text.contains(forbidden),
                        "控制器源码出现了 " + forbidden + " —— 跳层调用绕过服务层的事务 / 校验 / "
                                + "租户上下文（ADR-03 依赖方向）。架构守卫 R2 判的是编译产物，"
                                + "本断言补上『import 了但没用上』这段空隙");
            }
            assertTrue(text.contains("RefundWorkOrderService"),
                    "控制器必须经服务层 —— 否则本断言在测一个没有服务层调用的控制器");
        }

        @Test
        @DisplayName("㊳ 服务层的全部依赖都是构造器注入（按类型集合断言，不用魔术数字）")
        void service_dependencies_are_constructor_injected() {
            var ctor = RefundWorkOrderService.class.getDeclaredConstructors();
            assertEquals(1, ctor.length, "服务层应只有一个公开构造器");

            // 🛑 为什么用【类型集合】而不是"6 个参数"这种魔术数字：
            //    C-3 新增 RefundSubjectVerifier 端口后，本断言的旧版硬编码 5 直接报红
            //    （expected 5 but was 6）。若只是把 5 改成 6，门禁就退化成"数量对得上就行"
            //    —— 增减一个类型、同时凑巧增删另一个，仍然全绿。真正要守的不是数量，
            //    而是【具体哪些协作者必须经构造器注入】这个集合本身：
            //      · 少一个 ⇒ 某个策略被 new 在方法里 ⇒ 『口径唯一实例』失效（原注释的意图）；
            //      · 多一个 ⇒ 有人往服务层塞了新协作者，而这类改动应当被看见、被显式确认。
            var expected = new java.util.HashSet<Class<?>>(java.util.List.of(
                    com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderPort.class,
                    com.diaoyuanyun.dy.app.refund.domain.RefundSubjectVerifier.class,
                    com.diaoyuanyun.dy.app.refund.domain.RefundPolicy.class,
                    com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix.class,
                    com.diaoyuanyun.dy.app.refund.domain.RefundRecordingPolicy.class,
                    com.diaoyuanyun.dy.app.refund.domain.RetentionPolicy.class));
            var actual = new java.util.HashSet<Class<?>>(
                    java.util.Arrays.asList(ctor[0].getParameterTypes()));

            assertEquals(expected.size(), actual.size(),
                    "构造器依赖集合大小应恒为 " + expected.size()
                            + "；实际 " + actual.size() + " —— 见本方法注释：别改成魔术数字");
            assertEquals(expected, actual,
                    "构造器依赖【类型集合】必须逐项吻合 —— 少一个意味着某个协作者被 new 在方法里，"
                            + "多一个意味着有人新增了依赖但未同步本断言；缺失=" + minus(expected, actual)
                            + " 多余=" + minus(actual, expected));
            for (var p : ctor[0].getParameterTypes()) {
                assertFalse(p == Void.class, "参数类型异常");
            }
        }

        private static java.util.Set<Class<?>> minus(java.util.Set<Class<?>> a,
                                                     java.util.Set<Class<?>> b) {
            var r = new java.util.LinkedHashSet<>(a);
            r.removeAll(b);
            return r;
        }
    }

    // ==================================================================
    // 七、配置口径来源（G 域的判定必须来自配置，不是代码常量）
    // ==================================================================

    @Nested
    @DisplayName("七 · 配置口径来源")
    class ConfigSource {

        @Test
        @DisplayName("㊴ 七段口径可解析，且来源自描述点名了每条编号")
        void seven_config_segments_are_resolvable_and_self_describing() {
            RefundRawConfig raw = source.raw();
            assertTrue(raw.isComplete(), "七段口径必须齐备 —— 缺一段即拒绝按默认口径处理退款");
            assertTrue(raw.missingKeys().isEmpty(), "齐备时缺失清单必须为空");

            String desc = source.describeSource();
            for (int no : List.of(10, 26, 27, 28, 29, 38, 40)) {
                assertTrue(desc.contains("#" + no),
                        "来源自描述必须点名 config #" + no + " —— 排查时要能一眼看到『这次判定按哪份口径做的』");
            }
        }

        @Test
        @DisplayName("㊵ 口径缺失时【抛】而不是回落（默认可见性矩阵会让客户看见退款字样）")
        void missing_config_throws_never_falls_back() {
            assertThrows(BizException.class,
                    () -> RefundPolicy.fromRawConfig(new RefundRawConfig(
                            null, null, null, null, null, null, null)));
            assertThrows(BizException.class,
                    () -> RefundVisibilityMatrix.fromConfigJson(null));
            assertThrows(BizException.class,
                    () -> RefundVisibilityMatrix.fromConfigJson("   "));
        }

        @Test
        @DisplayName("㊶ 24h 代录窗口是【需求本体】：配置里出现别的数即抛（与文案口径同值）")
        void deputy_window_is_a_requirement_not_a_tunable() {
            assertEquals(24, RefundPolicy.DEPUTY_RECORD_WINDOW_HOURS_REQUIRED,
                    "P0-19 的 24h 是需求唯一合法值 —— 与 P1-10 文案『老师会在 24 小时内与您联系』同锚点");
            assertEquals(24, policy().deputyEntry().recordWithinHours(),
                    "配置声明的窗口必须等于需求值 —— 两侧不同值会让『两套时间』以配置形式复活");
        }

        @Test
        @DisplayName("㊷ 效果类【不得】自动出结论（行业把退款锚在履约状态而非效果）")
        void effect_route_never_allows_auto_verdict() {
            RefundPolicy p = policy();
            assertFalse(p.allowsAutoVerdict(RefundRoute.EFFECT),
                    "效果类必须人在环 —— 全行业把退款锚在履约状态（附录 C.7）；"
                            + "业务方 2026-09-16 原话『经络师再根据客户得数据看是否同意退款事宜』");
            assertTrue(p.requiresHumanInLoop(RefundRoute.EFFECT));
            assertTrue(RefundRoute.EFFECT.requiresHumanInLoop());
        }

        @Test
        @DisplayName("㊸ 矩阵本身的硬锁：客户 / 调理师 / 门店客服 不得可见（改配置即起不来）")
        void matrix_hard_locks_reject_corrupted_config() {
            String real = source.raw().visibilityJson();
            assertTrue(real.contains("\"customer\":false"),
                    "前置：真实配置应含无空格形态 \"customer\":false —— 否则下面两个变异体没生效");

            // 变异体 ①：客户改为可见
            String corrupted = real.replace("\"customer\":false", "\"customer\":true");
            assertFalse(corrupted.equals(real), "变异体必须真的改动了配置");
            BizException e1 = assertThrows(BizException.class,
                    () -> RefundVisibilityMatrix.fromConfigJson(corrupted));
            assertEquals(ErrorCode.BUSINESS_RULE_VIOLATED.getCode(), e1.getCode());

            // 变异体 ②：区域督导改为可审批
            String anchor = "\"area_supervisor\":{\"visible\":true,\"approve\":false}";
            assertTrue(real.contains(anchor), "前置：真实配置应含督导的锚定片段");
            String corrupted2 = real.replace(anchor,
                    "\"area_supervisor\":{\"visible\":true,\"approve\":true}");
            assertFalse(corrupted2.equals(real), "变异体必须真的改动了配置");
            assertThrows(BizException.class,
                    () -> RefundVisibilityMatrix.fromConfigJson(corrupted2),
                    "督导 approve 改为 true 必须被硬锁拦下 —— 它会让『可见≠可审批』唯一显式载例消失");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 剥离 Java 源码里的<b>注释</b>与<b>字符串/字符字面量</b>，只留可执行代码。
     *
     * <h2>🛑 它为什么必须存在，以及"直接扫全文"为什么会坏</h2>
     * {@link ApprovalGate#approver_whitelist_has_a_single_owner()} 这类断言要证的是
     * "<b>代码里</b>没有第二份实现"。而本仓库的注释风格是<b>大量解释设计意图</b> ——
     * 于是"这里原先有一份 {@code approverTokenRoles().contains(...)}，已删除"
     * 这句解释本身就含被禁字符串。
     *
     * <p>首版直接扫全文，结果该断言<b>恒红</b>。恒红的危害不是"多跑一次"：
     * 它不再区分好代码与坏代码（永远红），于是失去全部价值；
     * 更糟的是它会诱使人去删掉那段解释性注释，把设计意图与反向验证的结论一起丢掉 ——
     * 而那正是本仓库最贵的一类损失（每一段注释都记着一次实测教训）。
     *
     * <p>剥离注释是对的做法：断言的目标是"可执行代码里没有它"，不是"文档里不许提它"。
     *
     * <h2>它为什么不用正则</h2>
     * 正则做不了"字符串里的 {@code //} 不算注释"这件事，而本域源码里
     * 恰好有大量含斜杠的字符串（如 {@code "shot://e.png"}、SQL 片段、JSON 文本）。
     * 用正则剥注释会把一个 URL 后面半行代码一起吃掉 —— 于是断言悄悄扫了一份<b>残缺代码</b>，
     * 而残缺的方向是"删掉了本该被扫的部分"（漏报）。故此处按字符状态机走一遍。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char d = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            // 块注释
            if (c == '/' && d == '*') {
                int end = src.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                continue;
            }
            // 行注释
            if (c == '/' && d == '/') {
                int end = src.indexOf('\n', i + 2);
                i = (end < 0) ? n : end;
                continue;
            }
            // 文本块 """..."""
            if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                int end = src.indexOf("\"\"\"", i + 3);
                i = (end < 0) ? n : end + 3;
                out.append("\"\"");
                continue;
            }
            // 字符串 / 字符字面量（跳过其内容，避免把其中的 // 当注释）
            if (c == '"' || c == '\'') {
                char quote = c;
                int j = i + 1;
                while (j < n) {
                    char x = src.charAt(j);
                    if (x == '\\') {
                        j += 2;
                        continue;
                    }
                    if (x == quote || x == '\n') {
                        break;
                    }
                    j++;
                }
                i = (j < n) ? j + 1 : n;
                out.append(quote).append(quote);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private RefundWorkOrderRow seedWorkOrder(RefundEntry entry, RefundReasonCode reason) {
        // 🛑 入口 B 不走代录：它由首周期双不达标自动触发，不存在"客户何时提出"这一问。
        //    故三字段（requested_at / claimed / evidence）全不传 ——
        //    给入口 B 硬塞一个 requested_at 会让服务层拒绝立案（那正是 P0-19 的口径）。
        RefundWorkOrderService.CreateRequest req = new RefundWorkOrderService.CreateRequest(
                CUSTOMER, entry, RefundRoute.EFFECT, reason,
                null,
                entry.isStoreDeputyEntry() ? Instant.now().minusSeconds(600) : null,
                entry.isStoreDeputyEntry() ? "shot://seed/evidence.png" : null,
                null, entry.isStoreDeputyEntry() ? Instant.now() : null, null, STORE);
        return service.create(TENANT, req, "seeder");
    }

    /**
     * 造一张"已走完域路径到达终止"的工单。
     *
     * <h2>🛑 为什么必须用 {@code updateOutcome} 而不是"直接把 outcome 塞成终止"</h2>
     * 审批闸与归档只读闸的前置都是"工单已终止"。若测试靠直接改字段来构造前提，
     * 它证明的只是"某个字段被改成终止之后，闸门恰好拒了" —— 而不是
     * "沿真实路径走到终止之后，闸门拒了"。两者在乐观并发、归档约束、
     * 只向前推进这三条纪律上完全不同，而后者才是生产里会发生的那一种。
     */
    private RefundWorkOrderRow terminatedWorkOrder(RefundEntry entry) {
        RefundReasonCode reason = entry.isStoreDeputyEntry()
                ? RefundReasonCode.TRUST_OR_PRICE
                : RefundReasonCode.EFFECT_BELOW_EXPECTATION;
        RefundWorkOrderRow wo = seedWorkOrder(entry, reason);
        port.updateOutcome(TENANT, wo.refundId(), RefundOutcome.CONTINUE, RefundOutcome.TERMINATE);
        return port.findWorkOrder(TENANT, wo.refundId());
    }

    private RefundWorkOrderService.CreateRequest createReq(Instant claimed, Instant proven,
                                                           String evidenceRef) {
        // 🛑 meridianAccepted 刻意传 null —— 不替调用方"顺手补一个兜底锚点"。
        //    若这里传 Instant.now()，则「只给 claimed、不给可核实来源」的那些用例
        //    （㉔ / ㉕）会走到 resolveRequestedAt 的兜底分支（source=经络师受理）
        //    而成功立案 —— 于是它们想证的"只有主张不得落库"就永远不会被触达。
        //    这一处踩坑值得留痕：helper 补默认值，最典型的效果就是让负面用例失效。
        return new RefundWorkOrderService.CreateRequest(
                CUSTOMER, RefundEntry.A, RefundRoute.EFFECT, RefundReasonCode.TRUST_OR_PRICE,
                claimed, proven, evidenceRef, null, null, null, STORE);
    }

    private static RefundWorkOrderService.RetentionRequest retentionReq(RetentionResult result) {
        return new RefundWorkOrderService.RetentionRequest(
                1, "v1",
                result,
                "{\"dim1\":\"价格异议\"}",
                "{\"channel\":\"到店面谈\",\"minutes\":12}");
    }

    /** 端口方法集必须被钉死（新增方法即一次必须表态的改动）。 */
    @Test
    @DisplayName("㊹ 工单端口方法集恰为 8 个（3 工单 + 2 挽留 + 3 原话）")
    void work_order_port_method_set_is_pinned() {
        int declared = 0;
        for (Method m : RefundWorkOrderPort.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;   // validateTenantId 是端口上的静态校验，不计入方法集
            }
            declared++;
        }
        assertEquals(RefundWorkOrderPort.PORT_METHOD_COUNT, declared,
                "端口方法集被钉死为 " + RefundWorkOrderPort.PORT_METHOD_COUNT + " —— "
                        + "新增方法必须显式改这条断言（使『给工单端口加一个回执改写入口』"
                        + "变成一次必须表态的改动，而不是一次顺手加方法）");
    }

    @Test
    @DisplayName("㊺ 工单端口【只有一个】改写方法，且它只改结局")
    void port_has_exactly_one_mutator_and_it_only_changes_outcome() {
        List<String> mutators = new ArrayList<>();
        for (Method m : RefundWorkOrderPort.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String n = m.getName();
            if (n.startsWith("insert") || n.startsWith("update") || n.startsWith("delete")
                    || n.startsWith("upsert") || n.startsWith("save")) {
                mutators.add(n);
            }
        }
        assertEquals(List.of("insertRetention", "insertStatement", "insertWorkOrder", "updateOutcome"),
                mutators.stream().sorted().toList(),
                "写方法集应恰为 {insertWorkOrder, updateOutcome, insertRetention, insertStatement}"
                        + "（按字典序）—— 理由见下。"
                        + "🛑 唯一一个 update 是 updateOutcome，且它不改客户 / 原因码 / 金额 "
                        + "—— 那些要重开一张工单（更正留痕而不是改写历史）。"
                        + "🛑 两侧都先 sorted 再比：getDeclaredMethods() 的顺序未定义，"
                        + "直接比会让本断言随机红/绿 —— 而间歇性红的断言会被当成噪声，"
                        + "最后被改成永远绿（本仓库实测过这类退化）");
        // 且不得出现任何删除 / 就地改写别的动作
        for (Method m : RefundWorkOrderPort.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n.contains("delete") || n.contains("upsert") || n.contains("merge"),
                    "端口出现删除 / upsert / merge 语义的方法: " + m.getName());
        }
    }

    @Test
    @DisplayName("㊻ 回执端口方法集恰为 5 个，且【一个改写方法都没有】")
    void receipt_port_has_no_mutator_beyond_append() {
        int declared = 0;
        for (Method m : com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            declared++;
            String n = m.getName().toLowerCase();
            assertFalse(n.startsWith("update") || n.startsWith("delete")
                            || n.startsWith("upsert") || n.startsWith("save"),
                    "回执端口不得有改写语义的方法: " + m.getName()
                            + "（P0-19『随工单落库、不可删除』）");
        }
        assertEquals(com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort.PORT_METHOD_COUNT, declared);
    }

    @Test
    @DisplayName("㊼ 两个端口是【分开】的：append-only 的那一半不得暴露在能改状态的接口里")
    void receipt_port_and_work_order_port_are_separate_types() {
        assertFalse(com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort.class
                        .isAssignableFrom(RefundWorkOrderPort.class),
                "工单端口不得继承回执端口 —— 合并会让实现者看到同一个接口上有 updateOutcome，"
                        + "下次需要『修正一条写错的回执』时就顺手加一个 updateReceipt");
        assertFalse(RefundWorkOrderPort.class
                        .isAssignableFrom(com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort.class),
                "回执端口不得继承工单端口 —— 那会把 updateOutcome 直接送给 append-only 的实现");
    }
}