package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.ReceiptInterpreter;
import com.diaoyuanyun.dy.app.refund.domain.ReceiptState;
import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundOfflineNoticeRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.app.refund.service.RefundReceiptService;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-3「可见性矩阵交叉验证 + 回执三态留痕」的<b>语义守卫</b>。
 *
 * <h2>与 S2-1 / S2-2 测试的分工</h2>
 * <ul>
 *   <li>S2-1 {@code RefundPolicyContractTest}：口径的<b>原始声明</b>是否被约束住
 *       （{@code #10} / {@code #38} / {@code #40}）；</li>
 *   <li>S2-2 {@code RefundDeputyDisciplineAndRetentionTest}：<b>用口径做判断</b>时判断是否可靠
 *       （{@code requested_at} 归一 / 24h 边界 / 升级链 / 挽留 / 审批）；</li>
 *   <li><b>本类</b>：回执三态是否真的"推送事件是否发生"来分，以及可见性矩阵
 *       与契约 / 库 / 端三处是否一致 —— 这三处任一漂移，本类必红。</li>
 * </ul>
 *
 * <h2>🛑 本类的三个核心手法</h2>
 * <ol>
 *   <li><b>把"推送器一次都不被调用"变成计数器事实</b>：未授权路径下
 *       {@link RefundReceiptService.PushAttempt} 的调用次数<b>恒为 0</b> ——
 *       这不是"我写了 if 所以对"，是"它没有第二次被调用的机会"。
 *       用计数器的理由见 {@code InMemoryRefundReceiptPort} 的类注释。</li>
 *   <li><b>用 API 形状做断言</b>：断言 {@link RefundReceiptService.PushAttempt}
 *       与 {@link ReceiptInterpreter} 的方法集里<b>不存在</b>某个形状
 *       （如"一次判完"的单方法、或"能返回 UNAUTHORIZED 的推送后判定"）。
 *       反射扫描方法集，使"有人把两段式合并掉"变成一次构建失败。</li>
 *   <li><b>三处交叉验证可见性</b>：config {@code #40} 解析值 == 契约枚举 == 库 CHECK 字面。
 *       单一来源可被顺带改掉，三处不一致必然暴露。</li>
 * </ol>
 */
class RefundVisibilityAndReceiptTest {

    private final RefundProfileSource source = new ConfigSeedRefundProfileSource();

    private RefundPolicy policy() {
        return RefundPolicy.fromRawConfig(source.raw());
    }

    private RefundVisibilityMatrix matrix() {
        return RefundVisibilityMatrix.fromConfigJson(source.raw().visibilityJson());
    }

    private static final UUID REFUND = UUID.fromString("00000000-0000-0000-0000-00000000abc1");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-00000000abc2");
    private static final String TENANT = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String TEMPLATE = "tpl-receipt-001";

    // ==================================================================
    // 一、可见性矩阵交叉验证
    // ==================================================================

    @Nested
    @DisplayName("一、可见性矩阵：三处交叉验证（config #40 == 契约枚举 == 库字面）")
    class VisibilityMatrixCrossCheck {

        @Test
        @DisplayName("七角色全覆盖，且可见集合恰为 {经络师, 门店负责人, 区域督导, 总部运营}")
        void visible_set_is_exactly_the_four_frozen_roles() {
            RefundVisibilityMatrix m = matrix();
            List<RefundAudienceRole> visible = new ArrayList<>();
            for (RefundAudienceRole r : RefundAudienceRole.values()) {
                if (m.canSee(r)) {
                    visible.add(r);
                }
            }
            assertEquals(List.of(
                            RefundAudienceRole.MERIDIAN_THERAPIST,
                            RefundAudienceRole.STORE_ADMIN,
                            RefundAudienceRole.AREA_SUPERVISOR,
                            RefundAudienceRole.HEADQUARTERS_OPS),
                    visible,
                    "§2.2 冻结的可见集合必须逐字一致（多一个 = 数据泄漏，少一个 = 升级链断链）");
        }

        @Test
        @DisplayName("🛑 不可见集合恰为 {客户, 调理师, 门店客服} —— 且各有一条独立硬锁")
        void hard_invisible_roles_are_exactly_three() {
            Set<RefundAudienceRole> in = RefundVisibilityMatrix.hardInvisibleRoles();
            assertEquals(Set.of(RefundAudienceRole.CUSTOMER,
                            RefundAudienceRole.THERAPIST,
                            RefundAudienceRole.STORE_CUSTOMER_SERVICE),
                    in,
                    "三个恒不可见角色分属三份不同裁定（§2.2 冻结 / U3 / 业务方 2026-09-16），"
                            + "合并成一个集合会让『其中一份被推翻』在代码里看不出落点");
            for (RefundAudienceRole r : in) {
                assertFalse(matrix().canSee(r), r.label() + " 必须不可见");
            }
        }

        @Test
        @DisplayName("审批准入 = 角色白名单 ∩ token 角色，且督导被排除（可见≠可审批）")
        void approvers_exclude_area_supervisor_while_keeping_it_visible() {
            RefundVisibilityMatrix m = matrix();
            assertEquals(List.of(RefundAudienceRole.STORE_ADMIN, RefundAudienceRole.HEADQUARTERS_OPS),
                    m.approvers(),
                    "可审批角色恰为 {门店负责人, 总部运营}");

            // 同一件事的两个视角必须同时成立：督导可见、但不可审批
            assertTrue(m.canSee(RefundAudienceRole.AREA_SUPERVISOR), "督导必须可见（升级链第一跳的前提）");
            assertFalse(m.canApprove(RefundAudienceRole.AREA_SUPERVISOR), "督导不可审批（§2.2 逐字）");

            // 契约域 G4 的推断项：approverTokenRoles 必须是 [admin] 展开集的真子集，差集恰为 {area}
            Set<String> contractAdminExpanded = Set.of("manager", "area", "hq");
            Set<String> approvers = RefundAudienceRole.approverTokenRoles();
            assertTrue(contractAdminExpanded.containsAll(approvers),
                    "审批 token 角色不得越出契约 x-callable-roles[admin] 的展开集：" + approvers);
            Set<String> excluded = new java.util.LinkedHashSet<>(contractAdminExpanded);
            excluded.removeAll(approvers);
            assertEquals(Set.of("area"), excluded,
                    "排除的必须【且仅】是督导 —— 与 §2.2『可见、不审批』逐字一致；"
                            + "若将来 owner 裁定督导可审批，改动点是 RefundAudienceRole.approverTokenRoles（一处）");
        }

        @Test
        @DisplayName("稽核权只归总部运营（可见 + 稽核，与可见 ≠ 稽核区分开）")
        void audit_right_belongs_only_to_headquarters() {
            RefundVisibilityMatrix m = matrix();
            assertEquals(List.of(RefundAudienceRole.HEADQUARTERS_OPS), m.auditors(),
                    "稽核（P0-19 C1-4 两条信号 / P1-04 看板）只归总部运营，"
                            + "对门店不可见 —— 若督导也获得稽核权，"
                            + "『辖区督导既能看异常名单又能稽核本辖区』会让信号失去独立性");
        }

        @Test
        @DisplayName("🛑 库 CHECK 字面与契约枚举逐字一致（含全角括号，不得被规范化）")
        void receipt_state_literals_match_the_contract_exactly() {
            List<String> fromEnum = ReceiptState.allCodes();
            assertEquals(List.of("已推送", "未授权（转线下）", "推送失败"), fromEnum,
                    "三态字面必须与契约 RefundReceiptData.receipt_state 逐字一致");

            // 全角括号是契约字面的一部分："顺手规范化"成的形态必须被拒
            assertThrows(BizException.class, () -> ReceiptState.parse("未授权(转线下)"),
                    "半角括号的『未授权(转线下)』必须被拒 —— 端侧按契约逐字渲染，字面一改就渲染成契约外的值");
            assertThrows(BizException.class, () -> ReceiptState.parse("未授权"),
                    "简写成『未授权』必须被拒 —— 它丢掉了『转线下』这个兜底动作，是同一状态的不同含义");
            assertThrows(BizException.class, () -> ReceiptState.parse("已读"),
                    "契约里不存在的第四态必须被拒");

            // 三态枚举完备性：任何一态缺失都说明覆盖率分母缺一块
            assertEquals(3, ReceiptInterpreter.STATE_COUNT,
                    "P1-10 分母 = 已推送 + 未授权 + 推送失败，三态缺一即分母缺一块");
            assertEquals(ReceiptInterpreter.STATE_COUNT, ReceiptState.values().length,
                    "STATE_COUNT 常量必须与枚举实际成员数一致（否则常量本身在说谎）");
        }

        @Test
        @DisplayName("代录者不可审批硬锁存在于矩阵（动机阀门，配置侧改不掉）")
        void deputy_cannot_approve_lock_is_present() {
            assertTrue(matrix().deputyCannotApprove(),
                    "P0-19 R4b ④『代录者不可审批』是动机阀门：无此条，"
                            + "『原话不可编辑 / 延迟进异常名单 / 超时升级』三条可被同一人自建自批绕过");
            assertTrue(matrix().roleChangesNeedNoCode(),
                    "角色调整不改代码（条款 B 要惩罚的正是『改一个角色要发版』）");
        }

        @Test
        @DisplayName("矩阵硬锁被破坏即构造期抛 —— 客户可见 / 督导可审批两条各验一次")
        void corrupted_matrix_is_rejected_at_construction() {
            String base = source.raw().visibilityJson();
            // 客户可见 → 破硬锁①。
            // 🛑 必须用 config #40 的【真实形态】定位（无空格）—— 用带空格的写法
            //    替换会【一个字符都改不到】，而 assertFalse(...equals(base)) 会当场报红：
            //    那一步是本条测试里最值钱的一句 —— 它使"变异体没生效"不可能伪装成"硬锁生效"。
            String customerKey = "\"customer\":false";
            assertTrue(base.contains(customerKey),
                    "config #40 原文必须是 " + customerKey + " 形态（无空格）；找不到说明 #40 被重写，"
                            + "而本断言的作用是让『变异体没生效』无法伪装成『硬锁生效』");
            String customerVisible = base.replace(customerKey, "\"customer\":true");
            assertFalse(customerVisible.equals(base), "变异体必须真的改动了配置（否则断言在测原值）");
            assertThrows(BizException.class, () -> RefundVisibilityMatrix.fromConfigJson(customerVisible),
                    "客户可见必须被拒（§2.2 + U3，且该规则覆盖推送通道）");

            // 督导可审批 → 破『可见≠可审批』。
            // 🛑 用【无空格】形态定位（与 config #40 原文一致），且不依赖"false 只出现几次"：
            //    带空格的写法会一次改到所有 false（含 customer:false），于是该变异体
            //    会被【硬锁①】拦下 —— 那样测的是客户可见那条锁，而不是督导审批这条。
            //    故这里直接锚定 area_supervisor 的键值对本身。
            String supervisorKey = "\"area_supervisor\":{\"visible\":true,\"approve\":false}";
            assertTrue(base.contains(supervisorKey),
                    "config #40 原文必须含 area_supervisor 的显式 approve:false 声明 —— "
                            + "§2.2『可见 ≠ 可审批』的唯一显式载例；找不到说明 #40 被改写，"
                            + "而改写它等于动了契约域 G4 x-ruling-pending 的依据");
            String supervisorApproves = base.replace(supervisorKey,
                    "\"area_supervisor\":{\"visible\":true,\"approve\":true}");
            assertFalse(supervisorApproves.equals(base),
                    "变异体必须真的改动了配置（否则断言在测原值）");
            assertThrows(BizException.class, () -> RefundVisibilityMatrix.fromConfigJson(supervisorApproves),
                    "督导 approve:true 必须在解析期被拦下 —— 在配置侧放开它等于"
                            + "单方面解决契约域 G4 的待裁定项，而它需要契约 owner + 产品共签");
        }
    }

    // ==================================================================
    // 二、回执三态：两段式形状守卫
    // ==================================================================

    @Nested
    @DisplayName("二、回执三态：两段式 API 形状（形状即纪律）")
    class TwoPhaseShapeGuards {

        @Test
        @DisplayName("🛑 形状断言①：解析器没有『一次判完』的单方法（没有能返回 UNAUTHORIZED 的推送后方法）")
        void there_is_no_single_shot_decide_method() {
            List<String> decideMethods = new ArrayList<>();
            for (Method m : ReceiptInterpreter.class.getDeclaredMethods()) {
                if (m.getName().startsWith("decide")) {
                    decideMethods.add(m.getName() + "/" + m.getParameterCount());
                }
            }
            // 恰好两个，且参数个数不同（第一段有 quota、第二段没有）——
            // 后者是关键：第二段【无法】拿到额度，因此【无法】判未授权。
            decideMethods.sort(String::compareTo);
            assertEquals(2, decideMethods.size(),
                    "决策入口必须【恰为两个】—— 三态里『未授权』只能从第一段返回；"
                            + "一旦有人把它合并成一个方法，那条纪律就退化成运行期检查（实际: " + decideMethods + ")");
            assertTrue(decideMethods.contains("decideBeforePush/2"),
                    "第一段应为 decideBeforePush(quota, decidedAt)");
            assertTrue(decideMethods.contains("decideAfterPush/5"),
                    "第二段应为 decideAfterPush(succeeded, failureReason, pushedAt, decidedAt, templateId)");

            // 第二段的签名里不得出现任何"额度"语义参数 —— 拿不到额度，就判不出未授权。
            // （用参数类型的字符串表示做断言，因为类型本身是 long/boolean，无法靠名字区分）
            Method afterPush = java.util.Arrays.stream(ReceiptInterpreter.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("decideAfterPush")).findFirst().orElseThrow();
            assertEquals(5, afterPush.getParameterCount(),
                    "第二段参数个数固定为 5；若有人加了第 6 个参数（例如 quota），"
                            + "本断言会红 —— 那是『推送后再判未授权』重新变得可写的入口");
        }

        @Test
        @DisplayName("🛑 形状断言②：推送路径的调用顺序由服务决定 —— 未授权时推送器调用次数恒为 0")
        void unauthorized_path_never_invokes_the_pusher() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);
            AtomicInteger calls = new AtomicInteger();

            RefundReceiptService.ReceiptRecord rec = svc.pushAndRecord(
                    TENANT, REFUND, 0L, TEMPLATE,
                    tpl -> {
                        calls.incrementAndGet();
                        return new RefundReceiptService.PushOutcome(true, null, Instant.now());
                    },
                    OPERATOR, "test");

            assertEquals(ReceiptState.UNAUTHORIZED, rec.decision().state());
            assertEquals(0, calls.get(),
                    "🛑 本域最重的一条：未授权 = 推送事件根本没发生。"
                            + "推送器被调用 0 次是【逐字成立的事实】，不是比喻 —— "
                            + "若这里 >0，说明实现变成了『先推送、后判额度』，"
                            + "而那种实现下『未授权』在库里永不出现（覆盖率系统性偏高且看不出来）");
            assertEquals(0, rec.pushAttemptedTimes(), "记录须自证推送尝试次数为 0");
            assertFalse(rec.decision().state().isPushAttempted(),
                    "未授权在 isPushAttempted 上必须为 false");
            assertEquals(1, ledger.totalReceipts(), "未授权必须【独立落库】一条（这是分母的一块）");
        }

        @Test
        @DisplayName("额度 > 0 ⇒ 推送器恰被调用 1 次，且出口只可能是 已推送 / 推送失败")
        void authorized_path_invokes_the_pusher_exactly_once() {
            AtomicInteger calls = new AtomicInteger();
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);

            RefundReceiptService.ReceiptRecord ok = svc.pushAndRecord(
                    TENANT, REFUND, 3L, TEMPLATE,
                    tpl -> {
                        calls.incrementAndGet();
                        assertEquals(TEMPLATE, tpl, "推送器必须收到模板 ID（回执独占一个模板 ID）");
                        return new RefundReceiptService.PushOutcome(true, null, Instant.now());
                    },
                    OPERATOR, "test");

            assertEquals(1, calls.get(), "额度充足时推送器恰被调用 1 次");
            assertEquals(ReceiptState.PUSHED, ok.decision().state());
            assertFalse(ok.offlineFallbackRequired(), "已推送不需要转线下兜底");

            // 失败分支：出口仍是 PUSH_FAILED，永远不会是 UNAUTHORIZED
            RefundReceiptService.ReceiptRecord fail = svc.pushAndRecord(
                    TENANT, REFUND, 3L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(false, "模板被拒", null),
                    OPERATOR, "test");
            assertEquals(ReceiptState.PUSH_FAILED, fail.decision().state());
            assertEquals("模板被拒", fail.decision().failureReason(),
                    "失败原因必须落库 —— 三态留痕的目的是『可提交的凭证』，"
                            + "一条只说『失败了』的记录无法区分模板被拒 / 用户拒收 / 通道超时");
            assertTrue(fail.offlineFallbackRequired(), "推送失败须转线下兜底（不得因订阅失败而漏发回执）");
        }

        @Test
        @DisplayName("推送器返回 null ⇒ 抛，不得静默记成三态之一（未观测 ≠ 失败）")
        void null_push_outcome_is_rejected() {
            RefundReceiptService svc = new RefundReceiptService(new InMemoryRefundReceiptPort());
            assertThrows(BizException.class, () -> svc.pushAndRecord(
                            TENANT, REFUND, 1L, TEMPLATE, tpl -> null, OPERATOR, "test"),
                    "推送器返回 null 必须抛 —— 把 null 当失败或成功，"
                            + "会让一次【未观测到】的推送被静默记成三态之一，而三态都进分母");
        }

        @Test
        @DisplayName("推送成功却缺 pushed_at ⇒ 抛（已推送 ⟺ 有 pushed_at，与库 CHECK 同口径）")
        void pushed_without_timestamp_is_rejected() {
            assertThrows(BizException.class, () -> ReceiptInterpreter.decideAfterPush(
                            true, null, null, Instant.now(), TEMPLATE),
                    "推送成功却无 pushed_at：证据链上无从证明它真的发过（P0-19：凭证须可提交）");
        }

        @Test
        @DisplayName("推送失败却带 pushed_at / 缺失败原因 ⇒ 各自抛（两个方向的互斥都要守）")
        void push_failed_invariants_are_both_enforced() {
            Instant now = Instant.now();
            assertThrows(BizException.class, () -> ReceiptInterpreter.decideAfterPush(
                            false, "timeout", now, now, TEMPLATE),
                    "推送失败却有 pushed_at：会让覆盖率分母把一次未成功的推送算成已达标");
            assertThrows(BizException.class, () -> ReceiptInterpreter.decideAfterPush(
                            false, "  ", null, now, TEMPLATE),
                    "推送失败却没有失败原因：无法区分模板被拒 / 用户拒收 / 通道超时，三者处置完全不同");
        }

        @Test
        @DisplayName("已推送却缺模板 ID ⇒ 抛（模板 ID 是额度治理与『模板被挪作他用』排查的唯一线索）")
        void pushed_without_template_id_is_rejected() {
            Instant now = Instant.now();
            ReceiptInterpreter.ReceiptDecision d =
                    ReceiptInterpreter.decideAfterPush(true, null, now, now, null);
            assertThrows(BizException.class, () -> ReceiptInterpreter.assertPersistable(d),
                    "已推送却没有 template_id 必须被拒 —— P0-19 的订阅授权点设计要求"
                            + "『回执独占一个模板 ID』，这正是化解『额度被稀释』的方式");
        }

        @Test
        @DisplayName("模板 ID 为空 ⇒ 服务层直接拒（不得进入推送路径）")
        void blank_template_id_is_rejected_before_push() {
            RefundReceiptService svc = new RefundReceiptService(new InMemoryRefundReceiptPort());
            AtomicInteger calls = new AtomicInteger();
            assertThrows(BizException.class, () -> svc.pushAndRecord(
                    TENANT, REFUND, 1L, "  ",
                    tpl -> {
                        calls.incrementAndGet();
                        return new RefundReceiptService.PushOutcome(true, null, Instant.now());
                    },
                    OPERATOR, "test"));
            assertEquals(0, calls.get(), "模板 ID 非法时推送器也不得被调用（先校验、后推送）");
        }
    }

    // ==================================================================
    // 三、兜底与"漏发视同未回执"
    // ==================================================================

    @Nested
    @DisplayName("三、转线下兜底：漏发视同未回执")
    class OfflineFallback {

        @Test
        @DisplayName("🛑 未授权 / 推送失败两条路径都被结案门禁拦下（除非有线下告知留痕）")
        void closing_gate_blocks_when_notice_is_missing() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);

            svc.pushAndRecord(TENANT, REFUND, 0L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()),
                    OPERATOR, "test");

            BizException e = assertThrows(BizException.class,
                    () -> svc.assertAllFallbacksSatisfied(TENANT, REFUND));
            assertTrue(e.getMessage().contains("未授权（转线下）"),
                    "报错须点名状态——本表是【漏发兜底】的凭证，不是通用完整性检查");
            assertTrue(e.getMessage().contains("漏发视同未回执"),
                    "报错须点出 P0-19 原文出处，否则下一个人会以为这是可放宽的完整性检查");
        }

        @Test
        @DisplayName("补一次线下告知后，结案门禁放行（且只有【电话/当面】两种通道）")
        void closing_gate_passes_once_notice_is_recorded() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);

            svc.pushAndRecord(TENANT, REFUND, 0L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()),
                    OPERATOR, "test");

            assertThrows(BizException.class,
                    () -> svc.appendOfflineNotice(TENANT, REFUND, "订阅消息", OPERATOR, "x", "test"),
                    "🛑 『订阅消息』不得写进告知表 —— 本表是人工告知动作，"
                            + "写进去会让覆盖率分母被重复计入，并使『漏发』的判定退化成永远为真");

            svc.appendOfflineNotice(TENANT, REFUND, "电话", OPERATOR, "已电话告知", "test");
            svc.assertAllFallbacksSatisfied(TENANT, REFUND);   // 不抛即通过
            assertEquals(1, ledger.totalNotices(), "告知留痕须真实落库（不是只在内存里判断）");
        }

        @Test
        @DisplayName("🛑 hasOfflineNotice 只看人工告知表 —— 不把回执记录算进来")
        void has_offline_notice_ignores_receipt_rows() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);

            // 先灌一条"推送失败"（它本身就是一条回执行）
            svc.pushAndRecord(TENANT, REFUND, 1L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(false, "timeout", null),
                    OPERATOR, "test");
            assertEquals(1, ledger.totalReceipts(), "前置：库里确实有一行回执");
            assertFalse(ledger.hasOfflineNotice(TENANT, REFUND),
                    "🛑 有回执行 ≠ 有人工告知。若二者被合并，『漏发』的判定会永远为真 —— "
                            + "那正是 P0-19 警告的『永远显示成功的开关，比不做更有害』");
        }

        @Test
        @DisplayName("对【已推送】的工单写线下告知 ⇒ 拒（本表不是通用触达记录）")
        void offline_notice_on_a_pushed_refund_is_rejected() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);
            svc.pushAndRecord(TENANT, REFUND, 5L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()),
                    OPERATOR, "test");

            BizException e = assertThrows(BizException.class,
                    () -> svc.appendOfflineNotice(TENANT, REFUND, "当面", OPERATOR, "x", "test"));
            assertTrue(e.getMessage().contains("没有『未授权（转线下）』或『推送失败』"),
                    "报错须说明本表的语义边界 —— 否则下一个人会把它当成通用触达记录，"
                            + "进而让『欠告知清单』的分母失真");
        }

        @Test
        @DisplayName("🛑 欠告知清单：可列出具体工单（而不是一个无法派单的聚合数字）")
        void pending_obligations_are_per_refund_and_actionable() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);
            UUID second = UUID.fromString("00000000-0000-0000-0000-00000000abc9");

            // 工单一：未授权（欠告知）；工单二：已推送（不欠）
            svc.pushAndRecord(TENANT, REFUND, 0L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()),
                    OPERATOR, "test");
            svc.pushAndRecord(TENANT, second, 9L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()),
                    OPERATOR, "test");

            List<RefundReceiptService.FallbackObligation> pending =
                    svc.pendingFallbackObligations(TENANT, List.of(REFUND, second));
            assertEquals(1, pending.size(), "只有未授权那张工单欠告知");
            assertEquals(REFUND, pending.get(0).refundId(),
                    "清单须给出【具体工单 ID】—— 一个『本店欠 1 单』的数字无法派单");

            // 补告知后从清单消失（幂等：告一次即履行义务）
            svc.appendOfflineNotice(TENANT, REFUND, "当面", OPERATOR, "当面确认", "test");
            assertTrue(svc.pendingFallbackObligations(TENANT, List.of(REFUND, second)).isEmpty(),
                    "告知后该工单义务即履行完毕");
        }

        @Test
        @DisplayName("空工单集合 ⇒ 抛（不得返回空清单冒充『无欠告知』）")
        void empty_refund_set_is_rejected_rather_than_reported_as_clear() {
            RefundReceiptService svc = new RefundReceiptService(new InMemoryRefundReceiptPort());
            assertThrows(BizException.class,
                    () -> svc.pendingFallbackObligations(TENANT, List.of()));
            assertThrows(BizException.class,
                    () -> svc.pendingFallbackObligations(TENANT, null),
                    "没传工单的查询与一个真的没有欠告知的店，在报表上长得一样 —— 必须区分");
        }

        @Test
        @DisplayName("refund_offline_notice 的通道白名单恰为 {电话, 当面}（不包括订阅消息）")
        void offline_channel_whitelist_excludes_subscription() {
            assertEquals(List.of("电话", "当面"), RefundOfflineNoticeRow.CHANNELS);
            assertFalse(RefundOfflineNoticeRow.CHANNELS.contains("订阅消息"),
                    "订阅消息是推送事件、不是人工告知动作；两者同表会让覆盖率分母重复计入");
        }
    }

    // ==================================================================
    // 四、覆盖率：三数同显
    // ==================================================================

    @Nested
    @DisplayName("四、覆盖率（P1-10 口径）：三数同显，禁止只报一个数")
    class Coverage {

        @Test
        @DisplayName("三态各 1 ⇒ 送达率 1/3、授权覆盖率 2/3，且三个原始计数都在报告里")
        void three_states_produce_three_numbers_and_two_rates() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);
            UUID r2 = UUID.fromString("00000000-0000-0000-0000-00000000abc2");
            UUID r3 = UUID.fromString("00000000-0000-0000-0000-00000000abc3");

            svc.pushAndRecord(TENANT, REFUND, 1L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()), OPERATOR, "t");
            svc.pushAndRecord(TENANT, r2, 0L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()), OPERATOR, "t");
            svc.pushAndRecord(TENANT, r3, 1L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(false, "timeout", null), OPERATOR, "t");

            ReceiptInterpreter.CoverageReport rep = svc.coverage(TENANT);
            assertEquals(1, rep.pushed(), "已推送计数");
            assertEquals(1, rep.unauthorized(), "未授权计数");
            assertEquals(1, rep.pushFailed(), "推送失败计数");
            assertEquals(3, rep.denominator(), "分母 = 已推送 + 未授权 + 推送失败（三态全进）");
            assertEquals(1.0 / 3.0, rep.deliveryRate(), 1e-9, "送达率 = 已推送 / 分母");
            assertEquals(2.0 / 3.0, rep.authorizationCoverageRate(), 1e-9, "授权覆盖率 = 1 − 未授权占比");
            rep.assertThreeNumbersShown();   // 不抛即"三数同显"
        }

        @Test
        @DisplayName("🛑 只统计『已推送』会让分母缺一块 —— 用同数据对照说明偏高多少")
        void dropping_the_unauthorized_state_inflates_the_rate_invisibly() {
            long pushed = 1;
            long unauthorized = 995;
            long pushFailed = 4;
            ReceiptInterpreter.CoverageReport full = ReceiptInterpreter.coverageOf(pushed, unauthorized, pushFailed);
            ReceiptInterpreter.CoverageReport truncated = ReceiptInterpreter.coverageOf(pushed, 0, pushFailed);

            assertEquals(1000, full.denominator(), "完整分母 = 1000");
            assertEquals(0.001, full.deliveryRate(), 1e-9, "真实送达率 ≈ 0.1%");

            // 漏掉未授权一态：分母从 1000 掉到 5，送达率从 0.1% 跳到 20%
            assertEquals(5, truncated.denominator());
            assertEquals(0.2, truncated.deliveryRate(), 1e-9);
            assertTrue(truncated.deliveryRate() > full.deliveryRate() * 10,
                    "分母缺一块会让送达率【系统性偏高且偏高得看不出来】—— "
                            + "本条把『偏高多少』算出来，使 P0-19 的后果描述不是一句形容词");
        }

        @Test
        @DisplayName("分母为 0 ⇒ assertThreeNumbersShown 抛，且要求显示『无样本』而非 0/100")
        void zero_denominator_must_not_be_shown_as_zero_percent() {
            ReceiptInterpreter.CoverageReport rep = ReceiptInterpreter.coverageOf(0, 0, 0);
            assertEquals(0, rep.denominator());
            BizException e = assertThrows(BizException.class, rep::assertThreeNumbersShown);
            assertTrue(e.getMessage().contains("无样本"),
                    "分母为 0 时不得显示 0% 或 100%，应显示『无样本』——"
                            + "把『没数据』画成『0 或 100』是在制造一个假的结论");
        }

        @Test
        @DisplayName("负计数 ⇒ 抛（符号写错会被读成一次正常统计）")
        void negative_counts_are_rejected() {
            assertThrows(BizException.class, () -> ReceiptInterpreter.coverageOf(-1, 0, 0));
        }

        @Test
        @DisplayName("状态分布含空键 ⇒ 抛（不得默认归入某一态）")
        void null_key_in_distribution_is_rejected() {
            Map<ReceiptState, Long> dist = new LinkedHashMap<>();
            dist.put(ReceiptState.PUSHED, 1L);
            dist.put(null, 2L);
            assertThrows(BizException.class, () -> ReceiptInterpreter.coverageOfDist(dist),
                    "空键无从归入三态之一；默认归到某一态会让一个未知状态静默进入分母或逃出分母");
        }

        @Test
        @DisplayName("空分布 ⇒ 抛（不得按『无记录 = 100% 覆盖』处理）")
        void empty_distribution_is_rejected() {
            assertThrows(BizException.class,
                    () -> ReceiptInterpreter.coverageOfDist(Map.of()));
        }

        @Test
        @DisplayName("服务层 stateDistribution 恒返回三态（缺态补 0，不是缺键）")
        void state_distribution_always_has_all_three_keys() {
            InMemoryRefundReceiptPort ledger = new InMemoryRefundReceiptPort();
            RefundReceiptService svc = new RefundReceiptService(ledger);
            svc.pushAndRecord(TENANT, REFUND, 1L, TEMPLATE,
                    tpl -> new RefundReceiptService.PushOutcome(true, null, Instant.now()), OPERATOR, "t");

            Map<ReceiptState, Long> dist = svc.stateDistribution(TENANT, null, null);
            assertEquals(3, dist.size(), "三态齐备（缺态补 0）—— 缺键会让下游不得不用 getOrDefault，"
                    + "而默认值一旦写错（例如默认 1）会直接给分母注入一根假数据");
            assertEquals(1L, dist.get(ReceiptState.PUSHED));
            assertEquals(0L, dist.get(ReceiptState.UNAUTHORIZED));
            assertEquals(0L, dist.get(ReceiptState.PUSH_FAILED));
        }
    }

    // ==================================================================
    // 五、append-only 纪律（反射扫描：端口与实现都不得有改写方法）
    // ==================================================================

    @Nested
    @DisplayName("五、append-only 纪律：端口与实现都不得提供就地改写")
    class AppendOnlyDiscipline {

        /** 一旦出现这些动词，append-only 就只剩注释里的承诺。 */
        private static final List<String> FORBIDDEN_VERBS =
                List.of("update", "delete", "remove", "upsert", "merge", "saveorupdate", "overwrite", "patch");

        @Test
        @DisplayName("🛑 端口方法集：无 update / delete / upsert（纪律写在端口上，任何实现都拿不到该动作）")
        void port_exposes_no_mutating_method() {
            assertNoForbiddenVerbs(RefundReceiptPort.class,
                    "端口是纪律的第一层：若纪律只在 JDBC 实现上，换一个实现（例如测试用的内存实现）"
                            + "就能悄悄提供 updateState，而『不可删除』会先在测试路径上失效");
        }

        @Test
        @DisplayName("内存实现同样无改写方法（测试路径的语义必须与生产一致）")
        void in_memory_implementation_exposes_no_mutating_method() {
            assertNoForbiddenVerbs(InMemoryRefundReceiptPort.class,
                    "若内存实现能就地改写，测试证明的是【内存实现】的行为，而生产实现可能不同 —— "
                            + "那是测试最不该有的一种绿");
        }

        @Test
        @DisplayName("端口只有追加与查询：方法数与语义逐条对得上（防止偷偷加第 6 个方法）")
        void port_method_count_is_pinned() {
            List<String> names = new ArrayList<>();
            for (Method m : RefundReceiptPort.class.getDeclaredMethods()) {
                // validateTenantId 是端口上的 static default 校验方法，不算业务能力
                //（它被排除的方式必须与"钉死常量"这一手法一致：靠 isStatic||isDefault，而不是靠方法名白名单）
                if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                names.add(m.getName());
            }
            names.sort(String::compareTo);
            assertEquals(RefundReceiptPort.PORT_METHOD_COUNT, names.size(),
                    "端口常量 PORT_METHOD_COUNT 必须与实际方法数一致（否则常量本身在说谎）");
            assertEquals(List.of(
                            "appendOfflineNotice", "appendReceipt",
                            "countByState", "findReceipts", "hasOfflineNotice"),
                    names,
                    "端口方法集被钉死在 5 个（2 追加 + 3 查询）。新增方法必须显式改本断言 —— "
                            + "使『给账本加一个改写入口』变成一次必须表态的改动，而不是一次顺手加方法");
        }

        @Test
        @DisplayName("回执只有三态 CloseSet：新增第四态必须改 STATE_COUNT 与契约（本断言是那个闸门）")
        void receipt_state_is_a_closed_set_of_exactly_three() {
            assertEquals(3, ReceiptState.values().length);
            assertEquals(3, ReceiptInterpreter.allStateCodes().size());
            // 三态的字面必须唯一（不得两态同字面 —— 那会让计数把两件事合并）
            assertEquals(3, Set.copyOf(ReceiptState.allCodes()).size(),
                    "三态字面必须互不相同，否则同一个字符串会同时计进两态或漏掉一态");
        }

        @Test
        @DisplayName("state 解析对 null / 空白一律抛（缺状态既不在分子也不在分母 = 等于没留痕）")
        void parsing_blank_state_is_rejected() {
            assertThrows(BizException.class, () -> ReceiptState.parse(null));
            assertThrows(BizException.class, () -> ReceiptState.parse("   "));
        }

        @Test
        @DisplayName("isPushAttempted 的语义分歧点恰在 UNAUTHORIZED 一态")
        void push_attempted_differs_exactly_on_unauthorized() {
            assertTrue(ReceiptState.PUSHED.isPushAttempted(), "已推送：推送事件已发生");
            assertTrue(ReceiptState.PUSH_FAILED.isPushAttempted(), "推送失败：推送已【尝试】过");
            assertFalse(ReceiptState.UNAUTHORIZED.isPushAttempted(),
                    "🛑 未授权：推送事件【根本没发生】—— 这是三态的唯一语义分歧点，"
                            + "也是『未授权必须独立落库』这条约束在代码里的落点");

            // requiresPushedAt 的分歧点也只在 PUSHED 一态
            assertTrue(ReceiptState.PUSHED.requiresPushedAt());
            assertFalse(ReceiptState.UNAUTHORIZED.requiresPushedAt());
            assertFalse(ReceiptState.PUSH_FAILED.requiresPushedAt());
        }

        @Test
        @DisplayName("requiresOfflineFallback 恰对 未授权 / 推送失败 为真（已推送不兜底）")
        void offline_fallback_required_exactly_for_the_two_unsuccessful_states() {
            assertFalse(ReceiptInterpreter.requiresOfflineFallback(ReceiptState.PUSHED));
            assertTrue(ReceiptInterpreter.requiresOfflineFallback(ReceiptState.UNAUTHORIZED));
            assertTrue(ReceiptInterpreter.requiresOfflineFallback(ReceiptState.PUSH_FAILED));
            assertThrows(BizException.class,
                    () -> ReceiptInterpreter.requiresOfflineFallback(null),
                    "空状态不得默认『需要』或『不需要』—— 默认『不需要』会让一次未授权的工单静默逃过兜底");
        }

        private void assertNoForbiddenVerbs(Class<?> type, String why) {
            List<String> offenders = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                String lower = m.getName().toLowerCase();
                for (String verb : FORBIDDEN_VERBS) {
                    if (lower.contains(verb)) {
                        offenders.add(m.getName());
                        break;
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    type.getSimpleName() + " 出现了就地改写语义的方法: " + offenders + "。" + why);
        }
    }

    // ==================================================================
    // 六、Fail-closed：租户标识
    // ==================================================================

    @Nested
    @DisplayName("六、租户标识 fail-closed（端口上的静态校验，服务与实现共用）")
    class TenantGuard {

        @Test
        @DisplayName("非法租户 ID ⇒ TENANT_MISMATCH(2003)，不是 500")
        void illegal_tenant_id_yields_tenant_mismatch() {
            for (String bad : new String[]{null, "", "not-a-uuid", "aaaaaaaa-1111-1111-1111-1111",
                    "'; DROP TABLE refund_receipt; --"}) {
                BizException e = assertThrows(BizException.class,
                        () -> RefundReceiptPort.validateTenantId(bad),
                        "非法租户标识必须被拒: " + bad);
                // ⚠️ 用 assertEquals 而不是 assertSame：getCode() 返回 int，自动装箱后
                //    2003 落在 Integer 缓存区间（-128..127）之外 ⇒ 每次装箱都是新对象，
                //    assertSame 会【永远失败】且看起来像"错误码不对"。
                assertEquals(com.diaoyuanyun.dy.common.result.ErrorCode.TENANT_MISMATCH.getCode(), e.getCode(),
                        "语义归属是租户不匹配（2003/403），不是系统错误（9001/500）");
            }
        }

        @Test
        @DisplayName("合法 UUID ⇒ 放行（反证校验不是全拒）")
        void legal_tenant_id_passes() {
            RefundReceiptPort.validateTenantId(TENANT);   // 不抛即通过
        }
    }
}