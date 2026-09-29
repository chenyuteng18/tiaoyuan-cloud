package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundRawConfig;
import com.diaoyuanyun.dy.app.refund.domain.RefundRoute;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.service.ConfigSeedRefundProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-1「退款域口径」的<b>语义守卫</b> —— 每一条业务铁律都必须"配置改坏即抛"。
 *
 * <h2>本类与 {@code RefundProfileSourceTest} 的分工</h2>
 * 前者答"口径<b>从哪来</b>、断了会不会大声失败"；本类答"口径的<b>语义</b>是否被约束住"。
 * 一个只读种子文件却不做任何自洽校验的实现，能骗过前者；一个把校验写在别处、
 * 但来源可以静默回落的实现，能骗过本类。两者不可互相替代。
 *
 * <h2>🛑 本类的每一条断言都配一个"变异体"（mutation）</h2>
 * 只断言"合法配置能跑通"是没有牙齿的 —— 它不能区分"真的校验了"与"根本没校验"。
 * 故本类的核心手法是：<b>取真实声明值 → 复制 → 精确改一处 → 断言必抛</b>。
 * 变异体本身以真实配置为底本，因此它证明的是"这个真实的、当前生效的口径，
 * 若被这样改动，会被拦下" —— 而不是"我构造了一个假数据，它被拦下了"。
 *
 * <h2>变异体清单（10 条，逐条对应一处已冻结的需求）</h2>
 * <table>
 *   <caption>变异体 → 需求依据</caption>
 *   <tr><td>{@code #10} effect.mode → direct</td>
 *       <td>业务方 2026-09-16 原话「经络师再根据客户得数据看是否同意退款事宜」+ 附录 C.7</td></tr>
 *   <tr><td>{@code #10} entries[A].record_within_hours → 48</td>
 *       <td>P0-19 需求本体「24h 回执」+ P1-10 文案口径①「客户侧与系统侧说法一致」</td></tr>
 *   <tr><td>{@code #10} entries[A].actor → 客户</td>
 *       <td>P0-19「代录唯一性」判定口径：客户表达诉求不算发起方</td></tr>
 *   <tr><td>{@code #10} entries[B].mode → 协商工单</td>
 *       <td>P0-14 第三层「首周期主动终止 = 全额免审批」</td></tr>
 *   <tr><td>{@code #38} effect_judgement_allowed → true</td>
 *       <td>P0-14 / #38 行注释「不得引入任何效果判断」</td></tr>
 *   <tr><td>{@code #38} initiator → 客户</td>
 *       <td>P0-19 代录唯一性</td></tr>
 *   <tr><td>{@code #29} basis → 售价</td>
 *       <td>#29 行注释「按已消耗次数对应的服务成本分段（非按售价）」</td></tr>
 *   <tr><td>{@code #40} customer / therapist / 客服 → true</td>
 *       <td>§2.2 + U3 + 业务方 2026-09-16 客服裁定（三条硬锁）</td></tr>
 *   <tr><td>{@code #40} area_supervisor.approve → true</td>
 *       <td>§2.2「区域督导 ✓（可见、不审批）」</td></tr>
 *   <tr><td>{@code #40} deputy_cannot_approve → false</td>
 *       <td>P0-19 R4b 第④条动机阀门</td></tr>
 * </table>
 */
class RefundPolicyContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RefundProfileSource source = new ConfigSeedRefundProfileSource();

    private RefundRawConfig real() {
        return source.raw();
    }

    // ==================================================================
    // 一、真实配置下的正向语义（先证明"读对了"，再证明"改坏了会拦"）
    // ==================================================================

    @Test
    @DisplayName("真实配置：履约类可自动直出，效果类不可 —— 通路二分与人在环绑定")
    void real_config_keeps_effect_route_human_in_the_loop() {
        RefundPolicy p = RefundPolicy.fromRawConfig(real());

        assertTrue(p.allowsAutoVerdict(RefundRoute.FULFILLMENT),
                "履约类（未交付 / 错交付 / 停交付）必须允许规则直退（可自动、无需人审）");
        assertFalse(p.allowsAutoVerdict(RefundRoute.EFFECT),
                "🛑 效果类必须【不可】自动直出资格结论 —— 依据业务方 2026-09-16 原话"
                        + "「经络师再根据客户得数据看是否同意退款事宜」+ 全行业把退款锚在履约状态而非效果"
                        + "（附录 C.7）。若此处为 true，说明有人在配置里把效果类改成了规则直退");
        assertTrue(p.requiresHumanInLoop(RefundRoute.EFFECT), "效果类必须人在环");
        assertFalse(p.requiresHumanInLoop(RefundRoute.FULFILLMENT), "履约类不得要求人在环（否则直退失去意义）");
    }

    @Test
    @DisplayName("真实配置：24h 代录窗口与 48h 挽留 SLA / 7 天到账承诺各就各位")
    void real_config_holds_the_time_windows() {
        RefundPolicy p = RefundPolicy.fromRawConfig(real());

        assertEquals(24, p.deputyEntry().recordWithinHours(),
                "入口 A 代录窗口必须为 24h（P0-19 需求本体；客户回执文案的时间锚点同为 24h）");
        assertEquals(RefundPolicy.DEPUTY_RECORD_WINDOW_HOURS_REQUIRED, p.deputyEntry().recordWithinHours(),
                "代录窗口必须等于需求锁定的唯一合法值");
        assertEquals(48, p.retentionSlaHours(),
                "挽留响应 SLA 应为 48h（config #27）");
        assertEquals(7, p.arrivalCommitmentDays(),
                "退款到账承诺应为 7 个工作日（config #28）");
        assertFalse(p.fulfillment().effectJudgementAllowed(),
                "🛑 履约类直退不得引入任何效果判断（config #38）");
        assertEquals(RefundPolicy.FulfillmentFormula.FORMULA_REQUIRED, p.fulfillment().formula(),
                "直退公式必须恰为 '未消耗次数 × 单次均价（实付）'");
    }

    @Test
    @DisplayName("真实配置：审批白名单 = {门店负责人, 总部运营}，督导不可审批（可见≠可审批）")
    void real_config_holds_the_approver_whitelist() {
        RefundVisibilityMatrix m = RefundVisibilityMatrix.fromConfigJson(real().visibilityJson());

        assertTrue(m.canSee(RefundAudienceRole.MERIDIAN_THERAPIST), "经络师可见");
        assertTrue(m.canSee(RefundAudienceRole.STORE_ADMIN), "门店负责人可见");
        assertTrue(m.canSee(RefundAudienceRole.AREA_SUPERVISOR), "区域督导可见（可看工单与异常名单）");
        assertTrue(m.canSee(RefundAudienceRole.HEADQUARTERS_OPS), "总部运营可见");

        assertFalse(m.canSee(RefundAudienceRole.CUSTOMER), "客户恒不可见（§2.2 冻结）");
        assertFalse(m.canSee(RefundAudienceRole.THERAPIST), "调理师恒不可见（U3）");
        assertFalse(m.canSee(RefundAudienceRole.STORE_CUSTOMER_SERVICE),
                "门店客服恒不可见（业务方 2026-09-16 裁定；不可见即不可代录）");

        assertTrue(m.canApprove(RefundAudienceRole.STORE_ADMIN), "门店负责人可审批");
        assertTrue(m.canApprove(RefundAudienceRole.HEADQUARTERS_OPS),
                "总部运营可审批（P0-15『终止与打款须总部审批』、P0-14『超阈自动上收总部审批』）");
        assertFalse(m.canApprove(RefundAudienceRole.AREA_SUPERVISOR),
                "🛑 区域督导【可见但不审批】—— §2.2 逐字，且它是『可见 ≠ 可审批』原则的唯一载例");
        assertFalse(m.canApprove(RefundAudienceRole.MERIDIAN_THERAPIST), "经络师不可审批（代录者不可审批）");
        assertTrue(m.canAudit(RefundAudienceRole.HEADQUARTERS_OPS), "总部运营可稽核");
        assertFalse(m.canAudit(RefundAudienceRole.STORE_ADMIN), "门店负责人不可稽核");
        assertTrue(m.deputyCannotApprove(), "代录者不可审批（P0-19 R4b 第④条动机阀门）");
    }

    @Test
    @DisplayName("审批白名单 token 角色 = 契约 x-callable-roles[admin] 展开集减去 {area} —— 与 G4 推断逐字一致")
    void approver_token_roles_match_the_g4_inference_exactly() {
        // 契约域 G4: x-callable-roles: [admin]，而 x-roles 把 admin 展开为 {manager, area, hq}。
        // G4 的 x-ruling-pending 注解：当前取值系「可见≠可审批」推断，故不含 area。
        Set<String> adminExpansion = new TreeSet<>(Set.of("manager", "area", "hq"));
        Set<String> approvers = new TreeSet<>(RefundAudienceRole.approverTokenRoles());

        assertTrue(adminExpansion.containsAll(approvers),
                "审批白名单不得越出契约 x-callable-roles[admin] 的展开集 " + adminExpansion
                        + "：实际=" + approvers);
        Set<String> diff = new TreeSet<>(adminExpansion);
        diff.removeAll(approvers);
        assertEquals(Set.of("area"), diff,
                "🛑 与契约展开集的差集必须恰为 {area}（即：排除的是且仅是区域督导）—— "
                        + "多排除一个是越权收窄（少一个审批人），少排除一个是越权放宽"
                        + "（把 §2.2 明令『不审批』的督导放进审批链）。实际差集=" + diff);
        assertFalse(approvers.contains("meridian"),
                "经络师不得进入审批白名单 —— P0-19『代录者不可审批』：代录人集合 = 经络师 + 门店负责人，"
                        + "其中经络师不可审批");
    }

    // ==================================================================
    // 一′、A-7：config #40 与契约 VisibilityRole 的键集分叉已收口
    // ==================================================================

    @Test
    @DisplayName("🛑 A-7：契约 ADMIN 的全部 token 角色（含 4 个大写别名）在退款受众表【均有落点】")
    void every_contract_admin_token_role_resolves_to_an_audience() {
        // 契约 x-roles.admin.token-role = [manager, area, hq]；
        // VisibilityRole.ADMIN 另登记了 4 个遗留大写别名（T-11 的另一半）。
        // A-7 裁定 = "为大写别名补 #40 受众键"（别名层显式登记，下一轮删除）。
        // 本测试即该裁定的机械保证：契约侧认得的每个码，退款侧都必须答得出"它是谁"。
        Set<String> contractAdminTokens = new TreeSet<>(Set.of("manager", "area", "hq",
                "SUPER_ADMIN", "REGION_ADMIN", "STORE_STAFF", "TENANT_ADMIN"));
        assertEquals(7, contractAdminTokens.size(),
                "断言自身：契约 ADMIN 的 token 角色 = 3 正式码 + 4 遗留大写别名");

        for (String code : contractAdminTokens) {
            RefundAudienceRole role = RefundAudienceRole.ofTokenRole(code);
            // 🛑 三个小写码必须各自落到【不同】受众键 —— 退款可见性要在 admin 内部收窄
            //    （§2.2「督导可见、不审批」）。若它们落到同一行，收窄就无从实现。
            assertNotNull(role, "契约 ADMIN 的 token 角色 " + code + " 必须在退款受众表有落点 —— "
                    + "A-7 之前大写别名在此处抛 2001，走 A2 则报 5001（口径断裂）");
        }

        // 三个正式码必须互不相同（收窄的前提）
        Set<RefundAudienceRole> trio = Set.of(
                RefundAudienceRole.ofTokenRole("manager"),
                RefundAudienceRole.ofTokenRole("area"),
                RefundAudienceRole.ofTokenRole("hq"));
        assertEquals(3, trio.size(),
                "🛑 manager/area/hq 必须落到【三个不同】受众键 —— 若折叠成同一个，"
                        + "§2.2『区域督导可见、不审批』就无从表达（这正是本域刻意不复用"
                        + " VisibilityRole 的原因：它把三者折叠成 admin）");

        // 大写别名必须与对应正式码【同落点】（不是新造第四行）
        assertEquals(RefundAudienceRole.ofTokenRole("hq"), RefundAudienceRole.ofTokenRole("SUPER_ADMIN"),
                "SUPER_ADMIN 是 hq 的遗留别名，必须同落点");
        assertEquals(RefundAudienceRole.ofTokenRole("hq"), RefundAudienceRole.ofTokenRole("TENANT_ADMIN"),
                "TENANT_ADMIN 是 hq 的遗留别名，必须同落点");
        assertEquals(RefundAudienceRole.ofTokenRole("area"), RefundAudienceRole.ofTokenRole("REGION_ADMIN"),
                "REGION_ADMIN 是 area 的遗留别名，必须同落点");
        assertEquals(RefundAudienceRole.ofTokenRole("manager"), RefundAudienceRole.ofTokenRole("STORE_STAFF"),
                "STORE_STAFF 是 manager 的遗留别名，必须同落点");
    }

    @Test
    @DisplayName("🛑 A-7：遗留别名表登记的每个码都必须仍在契约 VisibilityRole.ADMIN 里（删别名时必红）")
    void legacy_alias_table_must_not_outlive_the_contract_aliases() {
        // 本测试防的是"别名表变成僵尸"：若 T-11 那一轮把契约里的大写别名删了，
        // 退款侧的别名表必须同步删 —— 否则它会成为一张无人对照的映射，
        // 而它里面的每个码都还"能用"，于是没人会发现契约已经不认它们了。
        Set<String> registryAliases =
                com.diaoyuanyun.dy.security.visibility.VisibilityRole.ADMIN.tokenRoles();
        for (String alias : RefundAudienceRole.legacyUppercaseAliases().keySet()) {
            assertTrue(registryAliases.contains(alias),
                    "🛑 退款别名表登记了 '" + alias + "'，但契约 VisibilityRole.ADMIN.tokenRoles() 不再含它 —— "
                            + "说明别名已在契约侧删除而退款侧未同步（T-11 的另一半漏了）。"
                            + "当前契约登记: " + new TreeSet<>(registryAliases));
        }
        // 反向：断言确实登记了 4 个（防"表被清空但测试仍然绿"）
        assertEquals(4, RefundAudienceRole.legacyUppercaseAliases().size(),
                "A-7 落地时登记了 4 个遗留大写别名；数量变化必须显式经过本断言");
    }

    @Test
    @DisplayName("🛑 A-7：正式小写码【优先于】别名解析（防别名抢答给错档位）")
    void formal_codes_take_precedence_over_aliases() {
        // 🛑 这是解析顺序的机械保证。若先查别名，则"某个码既是正式码又被误收进别名表"
        //    的情形不会报错、只会给错档位 —— 那正是本域反复警告的"静默给错权限"。
        for (RefundAudienceRole r : RefundAudienceRole.values()) {
            r.tokenRole().ifPresent(code -> {
                RefundAudienceRole resolved = RefundAudienceRole.ofTokenRole(code);
                assertEquals(r, resolved,
                        "正式码 '" + code + "' 必须解析回它自己的受众角色 " + r.configKey()
                                + "，实际 " + resolved.configKey()
                                + " —— 说明它被别名表抢答了（别名表不得含正式码）");
                assertFalse(RefundAudienceRole.legacyUppercaseAliases().containsKey(code),
                        "🛑 正式码 '" + code + "' 不得出现在遗留别名表里 —— 同一码两处登记"
                                + "会让解析顺序成为唯一防线，而顺序一旦被改就静默给错档位");
            });
        }
    }

    // ==================================================================
    // 二、🛑 变异体：把真实配置精确改一处，断言必抛
    // ==================================================================

    @Test
    @DisplayName("变异 #10：效果类改成规则直退 ⇒ 抛（防『效果类架构静默消失』）")
    void mutant_effect_route_as_direct_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        mutateRouteMode(gate, "effect", "direct");
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("效果类"),
                "报错应点名『效果类』并通过；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #10：效果类去掉 human_in_loop ⇒ 抛（『人在环』不得靠模式名推断）")
    void mutant_effect_route_without_human_in_loop_flag_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        for (JsonNode item : gate.path("routes")) {
            if ("effect".equals(item.path("route").asText())) {
                ((ObjectNode) item).remove("human_in_loop");
            }
        }
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("human_in_loop"),
                "报错应点名 human_in_loop；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #10：代录窗口 24h → 48h ⇒ 抛（防『文案承诺 24h、系统按 48h 判合规』）")
    void mutant_deputy_window_other_than_24h_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        mutateEntry(gate, "A", n -> n.put("record_within_hours", 48));
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("24"),
                "报错应点名 24h 这一需求本体；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #10：入口 A 发起方改成客户 ⇒ 抛（P0-19 代录唯一性）")
    void mutant_deputy_actor_as_customer_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        mutateEntry(gate, "A", n -> n.put("actor", "customer"));
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("store_deputy_entry"),
                "报错应点名合法发起方 store_deputy_entry；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #10：入口 B 改成协商工单 ⇒ 抛（首周期主动终止 = 全额免审批）")
    void mutant_first_cycle_entry_as_negotiation_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        mutateEntry(gate, "B", n -> n.put("mode", "negotiation_workorder"));
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("全额免审批") || e.getMessage().contains("入口 B"),
                "报错应说明入口 B 的免审批语义；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #10：去掉一个通路键 ⇒ 抛（多出/缺少的通路会走到未知分支）")
    void mutant_missing_route_key_is_rejected() {
        ObjectNode gate = read(real().gateRulesJson());
        ArrayNode routes = (ArrayNode) gate.path("routes");
        for (int i = 0; i < routes.size(); i++) {
            if ("effect".equals(routes.get(i).path("route").asText())) {
                routes.remove(i);
                break;
            }
        }
        BizException e = assertThrows(BizException.class, () -> policyWithGateRules(gate));
        assertEquals(5001, e.getCode());
    }

    @Test
    @DisplayName("🛑 变异 #38：effect_judgement_allowed → true ⇒ 抛（本域最重要的一条断言）")
    void mutant_fulfillment_with_effect_judgement_is_rejected() {
        ObjectNode formula = read(real().fulfillmentFormulaJson());
        formula.put("effect_judgement_allowed", true);
        BizException e = assertThrows(BizException.class,
                () -> policyWithFormula(formula));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("效果判断"),
                "报错必须点名『效果判断』这件事，而不是只说值非法 —— "
                        + "因为改这个值的人以为自己在做好事（『顺手也看一下效果』）；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #38：公式被『等价改写』 ⇒ 抛（不做等价判定，任何等价结论都由改配置的人单方做出）")
    void mutant_fulfillment_formula_rewrite_is_rejected() {
        ObjectNode formula = read(real().fulfillmentFormulaJson());
        formula.put("formula", "remaining_course_count*unit_price_paid");
        BizException e = assertThrows(BizException.class, () -> policyWithFormula(formula));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("等价"),
                "报错应说明为何不做等价判定；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #38：发起方改成客户 ⇒ 抛（客户不发起任何退款动作）")
    void mutant_fulfillment_initiator_as_customer_is_rejected() {
        ObjectNode formula = read(real().fulfillmentFormulaJson());
        formula.put("initiator", "customer");
        BizException e = assertThrows(BizException.class, () -> policyWithFormula(formula));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("store_staff_deputy"),
                "报错应点名合法发起方；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("变异 #29：让步阈值改成按『售价』分段 ⇒ 抛（防内部风控变成对定价的隐性惩罚）")
    void mutant_concession_basis_as_price_is_rejected() {
        ObjectNode concession = read(real().concessionThresholdJson());
        concession.put("basis", "unit_price");
        BizException e = assertThrows(BizException.class, () -> policyWithConcession(concession));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("服务成本"),
                "报错应点名『服务成本』这一正解；实际: " + e.getMessage());
    }

    // ==================================================================
    // 三、🛑 可见性矩阵的三条硬锁 + 键集完整性
    // ==================================================================

    @Test
    @DisplayName("🛑 硬锁①：客户端可见 → true ⇒ 抛（§2.2 + U3，且覆盖推送通道）")
    void hard_lock_customer_visibility_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("customer", true);
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("客户"),
                "报错应点名客户并给出条款 B 的出处；实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("2026-09-16") && e.getMessage().contains("条款 B"),
                "报错须写明该定值的冻结时点与变更代价（条款 B），"
                        + "使『放开』这件事必须显式经过代码，而不是静默生效；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 硬锁①：调理师可见 → true ⇒ 抛（U3：调理师端无『退款』字样）")
    void hard_lock_therapist_visibility_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("therapist", true);
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("调理师"), "报错应点名调理师；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 硬锁②：门店客服可见 → true ⇒ 抛（业务方裁定完全不可见、不代录）")
    void hard_lock_store_customer_service_visibility_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("store_customer_service", true);
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("客服"), "报错应点名门店客服；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 硬锁③：deputy_cannot_approve → false ⇒ 抛（动机阀门）")
    void hard_lock_deputy_cannot_approve_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("deputy_cannot_approve", false);
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("自建自批"),
                "报错应说明该阀门的用途（防同一人自建自批绕过三条约束）；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 督导 approve → true ⇒ 抛（不得在配置侧单方面解决 G4 的待裁定项）")
    void area_supervisor_approval_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        ((ObjectNode) v.path("area_supervisor")).put("approve", true);
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("x-ruling-pending"),
                "报错应指出该改动等于单方面解决 G4 的待裁定项；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("可见角色被改成不可见 ⇒ 抛（升级链第一跳建立在督导可见之上）")
    void hard_visible_role_turned_invisible_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("area_supervisor", MAPPER.createObjectNode().put("visible", false).put("approve", false));
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("升级"),
                "报错应说明该可见性的用途（超时升级链第一跳）；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("矩阵含未登记角色键 ⇒ 抛（说明配置侧已有访问改动而代码未跟上，不得静默忽略）")
    void unknown_role_key_in_matrix_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.put("customer_service_manager", true);   // 一个"看起来像角色"的未知键
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("customer_service_manager"),
                "报错应点名未知键；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("矩阵缺一个角色键 ⇒ 抛（不得把配置漏写静默成一次权限收紧）")
    void missing_role_key_in_matrix_is_rejected() {
        ObjectNode v = read(real().visibilityJson());
        v.remove("headquarters_ops");
        BizException e = assertThrows(BizException.class, () -> matrixOf(v));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("headquarters_ops"),
                "报错应点名缺失的角色键；实际: " + e.getMessage());
    }

    @Test
    @DisplayName("矩阵不是 JSON 对象（如数组）⇒ 抛，且是 5001 而非 9001")
    void non_object_matrix_is_rejected() {
        BizException e = assertThrows(BizException.class, () -> matrixOf(MAPPER.createArrayNode()));
        assertEquals(5001, e.getCode());
        assertTrue(e.getMessage().contains("不是一个 JSON 对象"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("矩阵声明 null / 空白 ⇒ 抛（未配置即拒绝，不得默认全开或全关）")
    void blank_matrix_is_rejected() {
        assertEquals(5001, assertThrows(BizException.class,
                () -> RefundVisibilityMatrix.fromConfigJson(null)).getCode());
        assertEquals(5001, assertThrows(BizException.class,
                () -> RefundVisibilityMatrix.fromConfigJson("   ")).getCode());
    }

    @Test
    @DisplayName("矩阵含空角色键 ⇒ 抛（空键无从判定可见性）")
    void empty_role_key_is_rejected() {
        assertEquals(5001, assertThrows(BizException.class,
                () -> RefundAudienceRole.parse("  ")).getCode(),
                "空角色键应由 RefundAudienceRole.parse 直接拒绝");
    }

    // ==================================================================
    // 四、通路二分与入口的边界（正反两面）
    // ==================================================================

    @Test
    @DisplayName("通路字面与库/契约两处逐字一致：履约类 / 效果类")
    void route_literals_are_verbatim() {
        assertEquals(Set.of("履约类", "效果类"), new TreeSet<>(RefundRoute.allCodes()),
                "通路字面必须与库 CHECK 及契约 refund_route 枚举逐字一致");
    }

    @Test
    @DisplayName("通路解析必须严格：未知通路即拒，不得回落（回落会让一笔退款走错通路）")
    void route_parse_is_strict() {
        assertEquals(5001, assertThrows(BizException.class, () -> RefundRoute.ofConfigKey("unknown")).getCode());
        assertEquals(1001, assertThrows(BizException.class, () -> RefundRoute.parse("")).getCode(),
                "空通路属入参校验失败（1001），不是配置问题");
    }

    @Test
    @DisplayName("入口字面持两套（契约无空格 / 库有空格），且两套都能解析、出站只用契约字面")
    void entry_holds_two_literals_for_contract_and_db() {
        assertEquals(java.util.List.of("A门店代录", "B首周期"),
                com.diaoyuanyun.dy.app.refund.domain.RefundEntry.allContractCodes(),
                "契约字面（RefundCreateRequest.entry）不带空格");
        assertEquals(java.util.List.of("A 门店代录", "B 首周期"),
                com.diaoyuanyun.dy.app.refund.domain.RefundEntry.allDbCodes(),
                "库 CHECK 字面带空格（V5 §2.20 已应用，不改写历史字面）");
        // 两套都能解析（宽容留在入口）
        assertEquals(com.diaoyuanyun.dy.app.refund.domain.RefundEntry.A,
                com.diaoyuanyun.dy.app.refund.domain.RefundEntry.parse("A门店代录"));
        assertEquals(com.diaoyuanyun.dy.app.refund.domain.RefundEntry.A,
                com.diaoyuanyun.dy.app.refund.domain.RefundEntry.parse("A 门店代录"));
        // 但字面不相等（严格留在出口）—— 若两者相等，本条断言失去了判别力
        assertFalse(com.diaoyuanyun.dy.app.refund.domain.RefundEntry.A.contractCode()
                        .equals(com.diaoyuanyun.dy.app.refund.domain.RefundEntry.A.dbCode()),
                "前置失效：若契约与库的字面变成相同，本断言无法再检出『两套字面』这一事实");
    }

    // ==================================================================
    // 工具：变异
    // ==================================================================

    private static ObjectNode read(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试底本不是合法 JSON: " + json, e);
        }
    }

    private static void mutateRouteMode(ObjectNode gateRules, String routeKey, String mode) {
        for (JsonNode item : gateRules.path("routes")) {
            if (routeKey.equals(item.path("route").asText())) {
                ((ObjectNode) item).put("mode", mode);
                if ("direct".equals(mode)) {
                    ((ObjectNode) item).remove("human_in_loop");
                }
                return;
            }
        }
        throw new IllegalStateException("测试底本缺少通路 " + routeKey);
    }

    private static void mutateEntry(ObjectNode gateRules, String id, java.util.function.Consumer<ObjectNode> mutator) {
        for (JsonNode item : gateRules.path("entries")) {
            if (id.equals(item.path("id").asText())) {
                mutator.accept((ObjectNode) item);
                return;
            }
        }
        throw new IllegalStateException("测试底本缺少入口 " + id);
    }

    private static RefundPolicy policyWithGateRules(ObjectNode gateRules) {
        RefundRawConfig base = new ConfigSeedRefundProfileSource().raw();
        return RefundPolicy.fromRawConfig(new RefundRawConfig(
                gateRules.toString(), base.verdictLatency(), base.retentionSlaHours(),
                base.arrivalCommitmentDays(), base.concessionThresholdJson(),
                base.fulfillmentFormulaJson(), base.visibilityJson()));
    }

    private static RefundPolicy policyWithFormula(ObjectNode formula) {
        RefundRawConfig base = new ConfigSeedRefundProfileSource().raw();
        return RefundPolicy.fromRawConfig(new RefundRawConfig(
                base.gateRulesJson(), base.verdictLatency(), base.retentionSlaHours(),
                base.arrivalCommitmentDays(), base.concessionThresholdJson(),
                formula.toString(), base.visibilityJson()));
    }

    private static RefundPolicy policyWithConcession(ObjectNode concession) {
        RefundRawConfig base = new ConfigSeedRefundProfileSource().raw();
        return RefundPolicy.fromRawConfig(new RefundRawConfig(
                base.gateRulesJson(), base.verdictLatency(), base.retentionSlaHours(),
                base.arrivalCommitmentDays(), concession.toString(),
                base.fulfillmentFormulaJson(), base.visibilityJson()));
    }

    private static RefundVisibilityMatrix matrixOf(JsonNode json) {
        return RefundVisibilityMatrix.fromConfigJson(json.toString());
    }
}