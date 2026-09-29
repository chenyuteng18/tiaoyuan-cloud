package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 退款可见性矩阵 —— 归一化自 config {@code #40}（{@code cfg:refund.visibility}）。
 *
 * <h2>这份矩阵为什么值得一个专门的 record，而不是几个布尔开关</h2>
 * PRD §2.2 把这条规则定成「<b>两项定值已于 2026-09-16 冻结，早于 P0-19 开工</b>」，
 * 并声明条款 B：若业务方在开工后改口（泛化：允许任何新角色在系统内登记 / 转交客户诉求）
 * →「P0-19 <b>+2~4 人日 + 已完工部分返工</b>，须重走 {@code refund_visibility} 冻结」。
 *
 * <p>换句话说：这份矩阵的每一项值都对应着一笔人日成本，而它的操作人是<b>业务方</b>、
 * 不是研发。所以它在代码里的形态必须同时满足两件事：
 * <ol>
 *   <li><b>可被单点修改</b>（改 config 一行，不改代码）—— 由"键名来自配置"保证；</li>
 *   <li><b>改坏时立刻报错，而不是静默改变面客行为</b> —— 由构造期硬锁断言保证。</li>
 * </ol>
 *
 * <h2>🛑 三条硬锁（配置改坏即抛，绝不静默执行）</h2>
 * <ol>
 *   <li><b>客户端与调理师端恒不可见</b>。依据 PRD §2.2 + U3「退款是内部事务，
 *       不在客户端露出：客户端与调理师端<b>无退款入口、无"退款"字样</b>」。
 *       §2.2 把「客户端一律不展示任何退款相关结论、进度与状态」写进了 config
 *       {@code #26} / {@code #28} 的行注释 —— 即这条锁覆盖<b>推送通道</b>
 *       （「推送是最容易被漏掉的一类客户端呈现，页面改了、推送没改等于没改」）。</li>
 *   <li><b>门店客服恒不可见且不代录</b>。依据业务方 2026-09-16 裁定
 *       「完全不可见、不代录」，且该角色<b>无端、无账号</b>。「不可见即不可代录」是裁定的推论。</li>
 *   <li><b>代录者不可审批</b>（{@code deputy_cannot_approve = true}）。
 *       依据 P0-19 的 R4b 第④条 ——「<b>动机阀门</b>：无此条，『原话不可编辑 /
 *       延迟进异常名单 / 超时升级』三条可被同一人自建自批绕过」。
 *       <p>这条的价值全在"它是一道阀门"：同一个经络师既能代录（把 24h 计时做漂亮）
 *       又能审批（把终止批掉），则代录纪律整条链上的三个观测点全部失效 ——
 *       他不需要绕过任何一条规则，只需按规则正常操作两次。</li>
 * </ol>
 *
 * <h2>⚠️ 硬锁不是业务变更的障碍，而是变更的闸门</h2>
 * 每条硬锁的错误消息里都写明了合法出路：若属业务方<b>正式变更裁定</b>，
 * 应当同步更新本类的锁定集合 + 重走 {@code refund_visibility} 冻结 + 触发条款 B 的人日与返工核算。
 * <p>硬锁的作用是让那次变更<b>显式经过代码</b>，而不是让它以一次配置改动的形式
 * 静默发生 —— 后者的问题不是"改了"，而是"没人知道改了、也没人算过代价"。
 *
 * <h2>🛑 「对象形态无 approve 键」≠「不可审批」</h2>
 * config {@code #40} 里三个管理角色的写法不统一：
 * <pre>
 *   "store_admin": true,                              ← 标量，未写审批
 *   "area_supervisor": {"visible":true, "approve":false},
 *   "headquarters_ops": {"visible":true, "audit":true} ← 对象，有 visible/audit、无 approve
 * </pre>
 * 若把"没有 approve 键"读成"不可审批"，总部运营会被判成不可审批 ——
 * 而 PRD P0-15 逐字要求「终止与打款<b>须总部审批</b>」、P0-14 要求
 * 「超阈 / 客户拒挽留<b>自动上收总部审批</b>」。总部运营是该审批的<b>接收方</b>。
 *
 * <p>故本类的语义是：<b>无 approve 键 = 未收窄（取角色登记的默认审批权）</b>；
 * 只有<b>显式</b> {@code approve:false} 才表示收窄。
 * 依据是 §2.2 的写法本身 —— 它对督导<b>特意</b>写了"可见不审批"，
 * 说明其余管理角色的审批权未被特别限制；若"未写"等于"不许"，
 * 就没有必要单独给督导加这一句。
 *
 * @param visible               角色 → 是否可见（7 键全覆盖）
 * @param approveAllowed        角色 → 是否可审批
 * @param auditAllowed          角色 → 是否可稽核（仅总部运营为真）
 * @param deputyCannotApprove   代录者不可审批（硬锁，必须为 true）
 * @param roleChangesNeedNoCode 角色调整不需改代码（必须为 true）
 * @param source                来源自描述
 */
public record RefundVisibilityMatrix(
        Map<RefundAudienceRole, Boolean> visible,
        Map<RefundAudienceRole, Boolean> approveAllowed,
        Map<RefundAudienceRole, Boolean> auditAllowed,
        boolean deputyCannotApprove,
        boolean roleChangesNeedNoCode,
        String source) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 恒不可见的角色（§2.2 冻结 + 业务方 2026-09-16 裁定）。 */
    private static final Set<RefundAudienceRole> HARD_INVISIBLE = Set.of(
            RefundAudienceRole.CUSTOMER,
            RefundAudienceRole.THERAPIST,
            RefundAudienceRole.STORE_CUSTOMER_SERVICE);

    /** 恒可见的角色（§2.2 冻结）。 */
    private static final Set<RefundAudienceRole> HARD_VISIBLE = Set.of(
            RefundAudienceRole.MERIDIAN_THERAPIST,
            RefundAudienceRole.STORE_ADMIN,
            RefundAudienceRole.AREA_SUPERVISOR,
            RefundAudienceRole.HEADQUARTERS_OPS);

    /** 矩阵里的行为标志位（不是角色键）。 */
    private static final Set<String> NON_ROLE_KEYS = Set.of(
            "role_changes_need_no_code", "deputy_cannot_approve", "unconfigured_means_deny");

    /**
     * 唯一构造入口：从 config {@code #40} 的原始 JSON 归一化。
     *
     * <p><b>逐项 fail-closed</b>：七个角色键缺一即抛；硬锁被破即抛；含未登记角色键即抛。
     * <p>🛑 不提供"未配置即全开"或"未配置即全关"的兜底：前者是数据泄漏；
     * 后者会让系统在配置缺失时对所有角色拒绝退款工单 —— 而"门店看不见工单"比"看不到"更糟：
     * 客户已经提出了诉求，门店却因一份没人维护的配置无法录入，而 24h 计时照常在走。
     * 故唯一正确的动作是<b>拒绝接单并点名缺失项</b>。
     */
    public static RefundVisibilityMatrix fromConfigJson(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵未配置（cfg:refund.visibility / config #40）—— "
                            + "未配置即拒绝：不得默认放行任何角色，也不得静默拒绝");
        }
        JsonNode root = readJson(json);
        assertNoUnknownRoleKeys(root);

        Map<RefundAudienceRole, Boolean> visible = new LinkedHashMap<>();
        Map<RefundAudienceRole, Boolean> approve = new LinkedHashMap<>();
        Map<RefundAudienceRole, Boolean> audit = new LinkedHashMap<>();

        for (RefundAudienceRole role : RefundAudienceRole.values()) {
            JsonNode node = root.path(role.configKey());
            if (node.isMissingNode()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵缺角色键 '" + role.configKey() + "'（" + role.label() + "）—— "
                                + "未配置即拒绝：缺键时不得推断该角色不可见。"
                                + "把一次配置漏写静默当成一次权限收紧，会让业务方以为是他改的那一项生效了");
            }
            if (node.isBoolean()) {
                // 标量形态：customer / therapist / store_customer_service
                //            / meridian_therapist / store_admin
                boolean v = node.asBoolean();
                visible.put(role, v);
                // 标量形态下审批权取角色登记值（未收窄）；可见性为假时审批权无意义。
                approve.put(role, v && role.approvesRefund());
                audit.put(role, false);
            } else if (node.isObject()) {
                // 结构化形态：area_supervisor {visible, approve} / headquarters_ops {visible, audit}
                if (!node.path("visible").isBoolean()) {
                    throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "退款可见性矩阵角色 '" + role.configKey() + "' 的结构化声明缺 visible 布尔值 —— "
                                    + "可见性是本矩阵的最小必要信息，不得靠其它键推断");
                }
                boolean v = node.path("visible").asBoolean();
                visible.put(role, v);

                // 🛑 显式 approve 必须与角色登记的审批权一致 —— 两者冲突时【抛】而不是让配置静默失效。
                //    初版把 approve 的取值与 role.approvesRefund() 做了短路与（&&），
                //    结果是「配置里写了 approve:true」被静默吞掉、矩阵行为不变。
                //    这正是本类反复警告的那件事：配置看着改了、行为没变 ——
                //    而"谁可以审批"会以"我记得改过"的形式在事后被误认为已生效。
                boolean hasApproveKey = node.has("approve") && node.path("approve").isBoolean();
                boolean declaredApprove = hasApproveKey && node.path("approve").asBoolean();
                if (hasApproveKey) {
                    assertApproveDeclarationHonored(role, declaredApprove, v);
                }

                // 「无 approve 键」= 未收窄，取角色登记值；显式声明则按声明取。
                boolean ap = v && role.approvesRefund() && (!hasApproveKey || declaredApprove);
                boolean au = node.has("audit") && node.path("audit").isBoolean()
                        && node.path("audit").asBoolean();
                approve.put(role, ap);
                audit.put(role, au);
            } else {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵角色 '" + role.configKey() + "' 的声明既非布尔也非对象: "
                                + node.getNodeType());
            }
        }

        boolean deputyCannotApprove = requireTrueFlag(root, "deputy_cannot_approve",
                "P0-19 R4b 第④条『代录者不可审批』—— 动机阀门：无此条，"
                        + "『原话不可编辑 / 延迟进异常名单 / 超时升级』三条可被同一人自建自批绕过");
        boolean roleChangesNeedNoCode = requireTrueFlag(root, "role_changes_need_no_code",
                "P0-19『新增/调整角色不改代码』—— 若为假说明矩阵退化成代码内白名单，"
                        + "业务方改口就得走发版，正是条款 B 要惩罚的那类改动");

        RefundVisibilityMatrix matrix = new RefundVisibilityMatrix(
                Map.copyOf(visible), Map.copyOf(approve), Map.copyOf(audit),
                deputyCannotApprove, roleChangesNeedNoCode, "config#40");

        matrix.assertHardLocks();
        return matrix;
    }

    /**
     * 含未登记角色键即抛。
     *
     * <p>🛑 不得静默忽略：一个未知键说明<b>有人已经在配置侧放开了某个角色的访问</b>，
     * 只是代码还没跟上。"忽略它"等于把一次已发生的授权改动藏起来 ——
     * 而它可能正是条款 B 所说"泛化：允许任何新角色在系统内登记客户诉求"的那一次。
     */
    private static void assertNoUnknownRoleKeys(JsonNode root) {
        Set<String> known = new TreeSet<>(RefundAudienceRole.allConfigKeys());
        setUnion(known, NON_ROLE_KEYS);
        Set<String> unknown = new TreeSet<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵含未登记键: " + unknown
                            + "（已登记角色: " + RefundAudienceRole.allConfigKeys()
                            + "；行为标志: " + new TreeSet<>(NON_ROLE_KEYS) + "）。"
                            + "🛑 未登记键不得默认放行 —— 它说明配置侧已有访问改动而代码未跟上；"
                            + "静默忽略等于把该改动藏起来。若属业务方新增角色，"
                            + "须先在 RefundAudienceRole 登记该角色（并核算条款 B 的人日与返工）");
        }
    }

    private static void setUnion(Set<String> target, Set<String> extra) {
        target.addAll(extra);
    }

    /**
     * 显式 {@code approve} 声明必须与角色登记的审批权一致，否则抛。
     *
     * <h2>为什么"放行"是错的（本条断言的由来）</h2>
     * 初版实现用短路与把两者取中间值：{@code node.approve && role.approvesRefund()}。
     * 后果是配置里把 {@code area_supervisor.approve} 改成 {@code true} 时
     * <b>什么都不会发生</b> —— 矩阵仍判督导不可审批，而改配置的人以为已生效。
     * 这不是"更安全"，而是把一次<b>越权尝试</b>变成了静默无效果：
     * 事后排查时，配置与行为不一致，且没有任何一处记录过这次改动。
     *
     * <p>故本方法把这类冲突转成一次<b>显式失败</b>，并在消息里给出两条合法出路：
     * 改回配置，或（若属业务方正式变更裁定）同步更新
     * {@link RefundAudienceRole} 的登记值并重走 {@code refund_visibility} 冻结。
     */
    private static void assertApproveDeclarationHonored(RefundAudienceRole role,
                                                        boolean declaredApprove,
                                                        boolean visible) {
        if (declaredApprove && !role.approvesRefund()) {
            // 🛑 督导的理由单独写全 —— 它是『可见 ≠ 可审批』唯一载例，
            //    且放开它等于单方面解决契约域 G4 的待裁定项。
            //    这个 if-else 曾经在 assertHardLocks 里另有一份"督导不可审批"的断言，
            //    但反向验证（注入错误、断言测试必红）证明那份断言【不可达】：
            //    凡是走到它的情形都已在此处被拦下 —— 它看起来是多一道防线，
            //    实际只让守卫的落点变模糊（读者以为有两条防线，实际只有一条，且理由分散两处）。
            //    故合并为唯一一层。
            String specific = (role == RefundAudienceRole.AREA_SUPERVISOR)
                    ? "🛑 特别是区域督导：PRD §2.2 逐字写着『区域督导 ✓（可见、不审批）』—— "
                      + "『可见 ≠ 可审批』是契约域 G4 x-ruling-pending 推断的依据，"
                      + "在配置侧放开督导审批，等于单方面解决了那个待裁定项，"
                      + "而它需要契约 owner + 产品共签。"
                    : "";
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵的 approve 声明与角色登记值冲突：角色 " + role.label()
                            + "（'" + role.configKey() + "'）声明 approve=true，"
                            + "但该角色的登记审批权为 false。" + specific
                            + " 🛑 不得静默取两者中较严的一方 —— 那会让『我改过配置』"
                            + "在事后被误认为已生效，而实际上矩阵行为未变、也无任何记录。"
                            + "两条合法出路：① 改回 approve=false；"
                            + "② 若属业务方正式变更裁定，须同步更新 RefundAudienceRole 的登记值"
                            + "（并核算其对契约域 G4 x-ruling-pending 的影响）后重走 refund_visibility 冻结");
        }
    }

    /**
     * 四条硬锁的一致性断言（见类注释）。
     *
     * <p>🛑 抽成独立方法是刻意的：它使"这条锁真的会拦人"可被<b>单独断言</b> ——
     * 测试可以构造一个 {@code customer=true} 的矩阵并断言此处抛错，
     * 而不必请求任何一个真实端点。这正是"门禁有牙齿"的检验方式。
     */
    private void assertHardLocks() {
        // 🛑 逐角色指名断言（而不是"遍历 HARD_INVISIBLE 集合"）。
        //    初版是遍历集合的写法，反向验证（注入错误、断言测试必红）暴露出它只有【一层】保护：
        //    把某个角色从集合里删掉，那道锁就整体消失，而集合本身只是个声明、没有任何守卫。
        //    改成逐角色指名后，删掉其中一条断言 = 那一行源码消失，反向验证必然变红。
        assertHardInvisible(RefundAudienceRole.CUSTOMER);
        assertHardInvisible(RefundAudienceRole.THERAPIST);
        assertHardInvisible(RefundAudienceRole.STORE_CUSTOMER_SERVICE);

        for (RefundAudienceRole role : HARD_VISIBLE) {
            if (!Boolean.TRUE.equals(visible.get(role))) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵与 §2.2 冻结值冲突：" + role.label() + "（config #40 键 '"
                                + role.configKey() + "'）应为可见。"
                                + "把可见角色改成不可见会让『谁来看这张异常名单』失去答案 —— "
                                + "而 P0-19 的超时升级链第一跳（>24h 推门店负责人 + 区域督导）"
                                + "正是建立在督导可见之上。若属业务方正式变更裁定，"
                                + "须同步更新本类的 HARD_VISIBLE 并重走冻结");
            }
        }
        // 「可见 ≠ 可审批」：督导的 approve:true 在【解析期】就被拦下
        //（见 assertApproveDeclarationHonored —— 那里是唯一落点，理由也写在那里）。
        // 此处不再重复断言：重复会造出一条不可达的防线，让守卫落点变模糊。

        // 反向一致性：不可见却可审批 = 一个无法行使的权力
        for (RefundAudienceRole role : RefundAudienceRole.values()) {
            if (Boolean.TRUE.equals(approveAllowed.get(role)) && !Boolean.TRUE.equals(visible.get(role))) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵自相矛盾：" + role.label() + " 可审批但不可见 —— "
                                + "一个看不见工单的角色却能审批它，等于把审批权交给一个没有信息的人");
            }
        }
        // 无端角色不得可见（门店客服：无账号 → 任何可见性都无从成立）
        for (RefundAudienceRole role : RefundAudienceRole.values()) {
            if (role.endRoleCode().isEmpty() && Boolean.TRUE.equals(visible.get(role))) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵自相矛盾：" + role.label() + " 标记为可见，但该角色"
                                + "无端、无账号（契约 x-roles: token-role=null, end=null）—— "
                                + "可见性无从在任何端上成立");
            }
        }
    }

    /**
     * 单个"恒不可见"角色的硬锁。
     *
     * <p>🛑 每个角色各一条独立断言，且各自带自己的理由文本 —— 三条理由分属三份不同的裁定：
     * 客户是 §2.2 冻结、调理师是 U3、门店客服是业务方 2026-09-16 裁定。
     * 合并成一条循环会让"三份裁定的其中一份被推翻"这件事在代码里看不出落点。
     */
    private void assertHardInvisible(RefundAudienceRole role) {
        if (Boolean.TRUE.equals(visible.get(role))) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "🛑 退款可见性硬锁被破：" + role.label() + "（config #40 键 '"
                            + role.configKey() + "'）必须恒不可见。" + hardInvisibleReason(role)
                            + "。该定值已于 2026-09-16 冻结（早于 P0-19 开工，条款 A 已达成）。"
                            + "若属业务方正式变更裁定：须同步更新 RefundVisibilityMatrix."
                            + "HARD_INVISIBLE、重走 refund_visibility 冻结，"
                            + "并核算条款 B 的代价（P0-19 +2~4 人日 + 已完工返工）—— "
                            + "不得按『体验优化』处理。本锁的作用是让那次变更显式经过代码，"
                            + "而不是以一次配置改动的形式静默发生");
        }
    }

    private static String hardInvisibleReason(RefundAudienceRole role) {
        return switch (role) {
            case CUSTOMER -> "依据 PRD §2.2 + U3「退款是内部事务，不在客户端露出："
                    + "客户端无退款入口、无“退款”字样」，且该规则覆盖推送通道（config #26 / #28 行注释）";
            case THERAPIST -> "依据 U3「调理师端无退款入口、无“退款”字样，"
                    + "只在经络师和管理员端显示操作」—— 调理师对客户诉求的承接走【中性转交】"
                    + "（复用 05 表异常/备注列），不经本矩阵";
            case STORE_CUSTOMER_SERVICE -> "依据业务方 2026-09-16 裁定「门店客服完全不可见、不代录」"
                    + "—— 不可见即不可代录，且该角色无端、无账号";
            default -> "";
        };
    }

    // ==================================================================
    // 便捷视图（上层唯一读取入口）
    // ==================================================================

    /**
     * 某角色能否看到退款工单（含工单详情、异常名单、挽留记录）。
     *
     * <p>🛑 取不到值直接抛而不返回 {@code false}：构造期已断言七键全覆盖，
     * 走到这里取不到值只可能是代码被绕过构造实例化 —— 那是 bug，不是"该角色不可见"。
     */
    public boolean canSee(RefundAudienceRole role) {
        Boolean v = visible.get(role);
        if (v == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵缺角色 " + role.configKey() + " 的判定 —— "
                            + "该情形应在构造期被拦下，出现即说明矩阵被绕过构造直接实例化");
        }
        return v;
    }

    /**
     * 某角色能否审批退款终止（出口集中；"代录者不可审批"由上层按具体人校验）。
     *
     * <p>⚠️ 本方法回答的是<b>角色级</b>问题（"经理这个岗位能不能审批"）；
     * "张三这次能不能审批"还要叠加 {@link #deputyCannotApprove} 与代录人的比对 ——
     * 两者是不同层的判断，不得合并。
     */
    public boolean canApprove(RefundAudienceRole role) {
        return Boolean.TRUE.equals(approveAllowed.get(role));
    }

    /** 某角色能否对退款工单做稽核（仅总部运营）。 */
    public boolean canAudit(RefundAudienceRole role) {
        return Boolean.TRUE.equals(auditAllowed.get(role));
    }

    /** 可审批的角色清单（供错误消息与审批白名单交叉验证）。 */
    public List<RefundAudienceRole> approvers() {
        return Arrays.stream(RefundAudienceRole.values()).filter(this::canApprove).toList();
    }

    /** 可稽核的角色清单（仅总部运营）。 */
    public List<RefundAudienceRole> auditors() {
        return Arrays.stream(RefundAudienceRole.values()).filter(this::canAudit).toList();
    }

    /**
     * 恒不可见的角色集合（供门禁使用）。
     *
     * <p>这是本类对外唯一暴露"硬锁"的地方：客户端零派生门禁 / 契约回归门禁
     * 可据此断言"客户侧不存在任何退款相关字样与字段"，
     * 而无需自己维护第二份"哪些角色不行"的清单 —— 第二份清单必然会与第一份分叉。
     */
    public static Set<RefundAudienceRole> hardInvisibleRoles() {
        return HARD_INVISIBLE;
    }

    private static JsonNode readJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵（config #40）不是一个 JSON 对象");
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵（config #40）不是合法 JSON: " + e.getMessage());
        }
    }

    private static boolean requireTrueFlag(JsonNode root, String field, String reason) {
        JsonNode v = root.path(field);
        if (!v.isBoolean() || !v.asBoolean()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵标志 " + field + " 必须显式为 true —— " + reason);
        }
        return true;
    }

    public RefundVisibilityMatrix {
        visible = Map.copyOf(visible);
        approveAllowed = Map.copyOf(approveAllowed);
        auditAllowed = Map.copyOf(auditAllowed);
    }
}