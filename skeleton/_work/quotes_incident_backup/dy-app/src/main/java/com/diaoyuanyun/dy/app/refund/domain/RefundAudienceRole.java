package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 退款可见性矩阵的<b>受众角色键</b>（config {@code #40} {@code cfg:refund.visibility} 的键集）。
 *
 * <h2>为什么它<b>不是</b> {@code VisibilityRole}</h2>
 * 这是本域最需要解释的一处结构决定。两者粒度不同，且差异是<b>需求本身要求的</b>：
 * <pre>
 *   契约 x-roles / VisibilityRole（端角色，4 个）：
 *       client | therapist | meridian | admin  （+ store_customer_service 无端）
 *   config #40（受众角色，7 个）：
 *       customer | therapist | store_customer_service
 *       | meridian_therapist | store_admin | area_supervisor | headquarters_ops
 * </pre>
 * 契约把 {@code manager / area / hq} 三个 token 角色<b>折叠</b>成一个端角色 {@code admin}
 * —— 那是对的，因为"字段组可见性"这一层不按层级收窄（收窄由行级 scope 承担）。
 *
 * <p>但退款可见性矩阵<b>恰恰要在这一层收窄</b>：PRD §2.2 逐字写着
 * 「区域督导 ✓（<b>可见</b>、<b>不审批</b>）」，而门店负责人与总部运营<b>都可审批</b>。
 * 三者同属端角色 {@code admin}，却在"能否审批"上分成两派 ——
 * 若把矩阵键直接取成 {@code VisibilityRole}，这三个角色的差别会在枚举构建的那一刻
 * 被<b>折叠掉</b>，而契约 G4 的 {@code x-ruling-pending} 注解正是在记录这个待裁定项
 * （「上游只写审批且给督导可见不审批，未逐项列白名单；当前取值系『可见≠可审批』推断」）。
 *
 * <p>故本枚举按 config {@code #40} 的键建 7 个成员，并各自登记其
 * <b>端角色码</b>与 <b>token 角色码</b> —— 使"矩阵层收窄"与"契约层折叠"
 * 两件事同时成立、且彼此可交叉验证（见 {@link #approverTokenRoles()}）。
 *
 * <h2>🛑 {@link #STORE_CUSTOMER_SERVICE} 的 {@code endRoleCode} 为 {@code null}，不是遗漏</h2>
 * 契约 {@code x-roles} 对该角色的登记是 {@code token-role: null, end: null} ——
 * 即<b>无端</b>：它不使用本系统、没有账号。业务方 2026-09-16 裁定其
 * 「<b>完全不可见、不代录</b>」。故它在本枚举里是一个"存在但在任何端上都不成立"的成员：
 * 保留它是为了让 {@code #40} 的键集能被<b>完整解析</b>（少了它，解析器遇到该键会报
 * "未知角色"，而把"已裁定的不可见"误报成"配置有误"）。
 */
public enum RefundAudienceRole {

    /** 客户（小程序）。{@code #40} 值 = {@code false}。 */
    CUSTOMER("customer", "客户", "client", false, "client"),

    /** 调理师（APP）。{@code #40} 值 = {@code false}；其"中性转交"不经本矩阵。 */
    THERAPIST("therapist", "调理师", "therapist", false, "therapist"),

    /**
     * 门店客服。{@code #40} 值 = {@code false}，且<b>无端</b>（{@code endRoleCode = null}）。
     *
     * <p>业务方 2026-09-16 裁定：完全不可见、<b>不代录</b>，且该角色不使用系统、无账号。
     * 「不可见即不可代录」是这条裁定的推论 —— 不是两条独立规则。
     */
    STORE_CUSTOMER_SERVICE("store_customer_service", "门店客服", null, false, null),

    /** 经络师（APP）。{@code #40} 值 = {@code true}。代录操作人集合的成员。 */
    MERIDIAN_THERAPIST("meridian_therapist", "经络师", "meridian", false, "meridian"),

    /** 门店负责人（管理端）。{@code #40} 值 = {@code true}；token 角色 {@code manager}。 */
    STORE_ADMIN("store_admin", "门店负责人", "admin", true, "manager"),

    /**
     * 区域督导（管理端）。{@code #40} 值 = {@code {visible:true, approve:false}}。
     *
     * <p>token 角色 {@code area}。它是"可见≠可审批"的<b>唯一显式载例</b>：
     * 能看工单与异常名单（含辖区），但不参与审批。
     */
    AREA_SUPERVISOR("area_supervisor", "区域督导", "admin", false, "area"),

    /** 总部运营（管理端）。{@code #40} 值 = {@code {visible:true, audit:true}}；token 角色 {@code hq}。 */
    HEADQUARTERS_OPS("headquarters_ops", "总部运营", "admin", true, "hq");

    /**
     * 🛑 骨架遗留<b>大写 token 角色别名</b> → 受众角色（A-7 裁定的落地件）。
     *
     * <h2>它解决的是什么</h2>
     * 契约 {@code VisibilityRole.ADMIN} 显式登记了 4 个<b>遗留大写别名</b>
     * （{@code SUPER_ADMIN} / {@code REGION_ADMIN} / {@code STORE_STAFF} / {@code TENANT_ADMIN}），
     * 它们能通过 A2 的端角色解析（{@code VisibilityRole.of} 认它们）；
     * 但 {@code #40} 的受众键集是按<b>小写</b> {@code manager/area/hq} 建的 —— 于是同一个请求，
     * 走 {@code ADMIN} 这条链会在 {@link #ofTokenRole} 处撞"未登记"，
     * 报 5001（口径断裂）而不是 2001（身份不可见）。
     *
     * <h2>🛑 为什么不是"把大写转小写"然后照常查</h2>
     * 那会把一次<b>口径分叉</b>在运行时抹平：别名与正式码在库里是两套字面，
     * 一旦将来有人删掉 {@link com.diaoyuanyun.dy.security.visibility.VisibilityRole}
     * 里的别名（T-11 的另一半），这里会<b>静默</b>跟着失效 —— 而失效的表现是
     * "某个管理角色忽然看不到退款"，没人会联想到这次删除。显式登记让每个别名
     * 都有一行可被静态审计的映射，删除任一侧都会让门禁变红。
     *
     * <h2>映射依据（与 {@code OrgLevel} 的别名登记逐条对齐）</h2>
     * <pre>
     *   SUPER_ADMIN  → HEADQUARTERS_OPS   （OrgLevel.HEADQUARTERS 别名）
     *   TENANT_ADMIN → HEADQUARTERS_OPS   （OrgLevel.HEADQUARTERS 别名）
     *   REGION_ADMIN → AREA_SUPERVISOR    （OrgLevel.REGION 别名）
     *   STORE_STAFF  → STORE_ADMIN        （OrgLevel.STORE 别名）
     * </pre>
     * ⚠️ {@code OrgLevel.STORE} 还含 {@code meridian} 与 {@code therapist}，但它们是
     * <b>正式小写码</b>（不是别名），已在枚举成员上各有一条 token 角色，
     * 故此处<b>刻意不重复登记</b>它们 —— 重复登记会让"某个 code 同时是别名又是正式码"
     * 这件事在代码里看不出落点。
     *
     * <p>🛑 这与 T-11「角色码大小写双写」<b>同一根因</b>：本表是别名层，
     * 推荐在下一轮删除大写别名时<b>一并删除</b>本表 —— 届时门禁会提醒
     * （本表登记的每个别名都必须仍在 {@code VisibilityRole.ADMIN.tokenRoles()} 里）。
     */
    private static final java.util.Map<String, RefundAudienceRole> LEGACY_UPPERCASE_ALIASES =
            java.util.Map.of(
                    "SUPER_ADMIN", HEADQUARTERS_OPS,
                    "TENANT_ADMIN", HEADQUARTERS_OPS,
                    "REGION_ADMIN", AREA_SUPERVISOR,
                    "STORE_STAFF", STORE_ADMIN);

    private final String configKey;
    private final String label;
    private final String endRoleCode;
    private final boolean approvesRefund;
    /**
     * 对应的 token 角色码（请求令牌里的角色）。
     *
     * <p>🛑 本字段补上的是 S2-1~S2-3 之间的一处<b>真实缺口</b>：
     * config {@code #40} 有 7 个受众键、契约 {@code x-roles} 有 5 个 token 角色码，
     * 但两者之间<b>此前没有任何登记</b> —— 于是"请求里带着 {@code area} 这个角色，
     * 他能不能看退款工单"这个问题在代码里<b>无处可问</b>。
     * 矩阵只回答"某个受众角色能不能看"，而<b>从 token 到受众角色</b>那一步是空的。
     * 缺这一步，可见性守卫就只能靠正则 / 字符串比较自己拼一个映射 ——
     * 而那种拼法会与 {@link #approverTokenRoles()} 分叉。
     *
     * <p>{@code null} = 该角色<b>无端、无账号</b>（{@link #STORE_CUSTOMER_SERVICE}）——
     * 任何请求都不可能带着这个 token 角色来，故它不该出现在映射里。
     */
    private final String tokenRole;

    RefundAudienceRole(String configKey, String label, String endRoleCode,
                       boolean approvesRefund, String tokenRole) {
        this.configKey = configKey;
        this.label = label;
        this.endRoleCode = endRoleCode;
        this.approvesRefund = approvesRefund;
        this.tokenRole = tokenRole;
    }

    /** config {@code #40} 的角色键（唯一解析入口）。 */
    public String configKey() {
        return configKey;
    }

    public String label() {
        return label;
    }

    /** 对应的契约端角色码（{@code client/therapist/meridian/admin}）；{@code null} = 无端。 */
    public Optional<String> endRoleCode() {
        return Optional.ofNullable(endRoleCode);
    }

    /**
     * 对应的 token 角色码（{@code client/therapist/meridian/manager/area/hq}）。
     *
     * <p>{@code null} = 无端角色（门店客服），任何请求都带不了这个角色。
     */
    public Optional<String> tokenRole() {
        return Optional.ofNullable(tokenRole);
    }

    /**
     * 由<b>请求令牌里的</b> token 角色解析出受众角色 —— 可见性守卫的入口。
     *
     * <h2>🛑 它回答的是"这个人是谁"，不是"他能不能看"</h2>
     * 矩阵 {@link RefundVisibilityMatrix#canSee} 回答后者。两件事必须分两步、
     * 各有一个唯一落点：若把"从 token 到受众角色"的映射散在守卫里，
     * 就会出现"守卫 A 认为 {@code area} 是督导、守卫 B 认为它是门店负责人"这类分叉 ——
     * 而分叉的那一侧不会报错，只会安静地多给或少给一份可见性。
     *
     * <h2>fail-closed：未登记即拒，绝不回落</h2>
     * 未登记的角色<b>抛</b>而不是回落到"不可见"：
     * 回落会让一次角色码拼写错误（如 {@code mana ger}）静默变成"这个人什么都看不到"，
     * 而运维排查时会先怀疑权限配置、再怀疑数据，最后才发现是拼写 ——
     * 更要紧的是，回落使"进来一个我们不知道的角色"这件事<b>无人知晓</b>。
     * 抛错则让它在第一次调用就暴露，且报错直接点名未知角色码。
     *
     * <p>它<b>不</b>复用 {@code VisibilityRole}（端角色）：后者把 {@code manager/area/hq}
     * <b>折叠</b>成 {@code admin}，而退款审批恰恰要在这三者之间收窄
     * （§2.2「督导可见、不审批」）。折叠掉之后就无法回答"是哪个 admin"。
     *
     * <h2>解析顺序（A-7 之后的唯一一处改动）</h2>
     * <pre>
     *   ① 正式小写码（manager/area/hq/meridian/therapist/client）—— 先查；
     *   ② 遗留大写别名（SUPER_ADMIN/REGION_ADMIN/STORE_STAFF/TENANT_ADMIN）—— 次查；
     *   ③ 都查不到 ⇒ 抛 2001（fail-closed，不回落）。
     * </pre>
     * 🛑 顺序不可颠倒：若先查别名，"某个码既是正式码又被误收进别名表"时
     * 别名会抢答，而那个错误不会报错、只会给错档位。正式码优先使该情形难以发生。
     *
     * @param tokenRole 令牌里的角色码（可含空白，内部 trim）
     * @throws BizException {@code VISIBILITY_DENIED(2001)} 未登记 / 空 —— 语义是
     *                      "你的身份无法对应到任何退款受众"，属 403 而非 500
     */
    public static RefundAudienceRole ofTokenRole(String tokenRole) {
        if (tokenRole == null || tokenRole.isBlank()) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "请求未携带 token 角色 —— 退款域不设匿名通道："
                            + "可见性档位是契约冻结项，无身份即无从判定。"
                            + "（fail-closed：空角色不得回落为任一受众）");
        }
        String t = tokenRole.trim();
        Optional<RefundAudienceRole> exact = Arrays.stream(values())
                .filter(r -> t.equals(r.tokenRole))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        // 🛑 第二步才查遗留大写别名（A-7）：正式小写码优先，
        //    避免"某个码同时是正式码与别名"时别名抢答。
        RefundAudienceRole alias = LEGACY_UPPERCASE_ALIASES.get(t);
        if (alias != null) {
            return alias;
        }
        throw new BizException(ErrorCode.VISIBILITY_DENIED,
                "token 角色不在退款受众登记表内: '" + tokenRole + "'"
                        + "（已登记: " + allTokenRoles()
                        + "；遗留大写别名: " + new TreeSet<>(LEGACY_UPPERCASE_ALIASES.keySet()) + "）。"
                        + "🛑 不得回落为『不可见』—— 那会让一个拼错的角色码"
                        + "静默变成一次权限收紧，而真正的成因（有人用了未登记的角色）"
                        + "在日志里查无此事");
    }

    /**
     * 全部已登记的 token 角色码（含遗留大写别名，无端角色不计入）。
     *
     * <p>🛑 与 {@link #allTokenRoles()}</p> 的区别是它<b>包含</b>别名：
     * 供"登记表完整性"门禁使用 —— 门禁要断言"契约 {@code VisibilityRole.ADMIN}
     * 里的每个 token 码在本表都有落点"，那需要的是全量集合（含别名）。
     * 而 {@link #allTokenRoles()} 是错误消息里的"正式码清单"，别名另列一行。
     */
    public static List<String> allTokenRolesIncludingAliases() {
        List<String> out = new java.util.ArrayList<>(allTokenRoles());
        out.addAll(new TreeSet<>(LEGACY_UPPERCASE_ALIASES.keySet()));
        return List.copyOf(out);
    }

    /**
     * 遗留大写别名（A-7）的只读视图 —— 供门禁与自描述使用。
     *
     * <p>返回不可变 Map，键为别名码、值为其落点受众角色。
     */
    public static java.util.Map<String, RefundAudienceRole> legacyUppercaseAliases() {
        return LEGACY_UPPERCASE_ALIASES;
    }

    /** 全部已登记的 token 角色码（无端角色不计入）。 */
    public static List<String> allTokenRoles() {
        return Arrays.stream(values())
                // 🛑 必须取【字段】而不是 tokenRole() 访问器：后者返回 Optional，
                //    而本方法的契约是 List<String>（供错误消息与测试断言直接打印）。
                //    用访问器会让它在编译期就炸（类型不兼容）—— 这是好事，
                //    但值得在注释里留痕，因为"访问器返回 Optional"这件事
                //    在别处（如守卫）是需要 Optional 语义的。
                .map(r -> r.tokenRole)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 本角色是否属"退款审批"的操作人集合（见 {@link #approverTokenRoles()} 的一致性说明）。 */
    public boolean approvesRefund() {
        return approvesRefund;
    }

    public static List<String> allConfigKeys() {
        return Arrays.stream(values()).map(RefundAudienceRole::configKey).toList();
    }

    public static RefundAudienceRole parse(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款可见性矩阵含空角色键 —— 空键无从判定可见性");
        }
        String k = configKey.trim();
        return Arrays.stream(values())
                .filter(r -> r.configKey.equals(k))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "退款可见性矩阵（config #40）含未登记角色键: '" + configKey + "'"
                                + "（已登记: " + allConfigKeys() + "）。"
                                + "🛑 未登记角色不得默认放行也不得默认拒绝后静默通过 —— "
                                + "必须报错，因为『矩阵里有个我不认识的角色』与『矩阵里没有这个角色』"
                                + "是两件事：前者说明有人已在配置侧放开了访问，只是代码还没跟上"));
    }

    /**
     * 审批操作人集合对应的 <b>token 角色码</b>。
     *
     * <h2>它存在的唯一理由：把契约的 {@code x-ruling-pending} 变成可断言的事实</h2>
     * 契约域 G4（{@code POST /refunds/{id}/approvals}）声明 {@code x-callable-roles: [admin]}，
     * 并附注：「上游只写『审批（出口集中）』且给出『区域督导 ✓（可见不审批）』，
     * 未逐一列出审批角色的白名单。下列取值系依『可见 ≠ 可审批』推断（故不含 area / meridian），
     * 属<b>推断项</b>，待契约 owner + 产品共签确认。」
     *
     * <p>于是"推断"这件事在代码里的形态就是本方法：它返回 {@code {manager, hq}} ——
     * 它是 {@code x-callable-roles: [admin]} 展开后的 {@code {manager, area, hq}} 的
     * <b>真子集</b>，且恰好排除 {@code area}。
     * 测试据此断言两件事：① 本集合 ⊆ 契约展开集（不越权）；
     * ② 差集恰为 {@code {area}}（排除的是且仅是督导，与 §2.2 逐字一致）。
     *
     * <p>🛑 若 owner 将来裁定督导也可审批，改动点是本方法（一处），
     * 而不是散落在控制器 / 服务 / 拦截器里的三个白名单。
     */
    public static Set<String> approverTokenRoles() {
        Set<String> out = new LinkedHashSet<>();
        for (RefundAudienceRole r : values()) {
            if (r.approvesRefund) {
                // 取 r.tokenRole 而不是再写一份 switch ——
                // 原先这里手写「STORE_ADMIN -> manager / HEADQUARTERS_OPS -> hq」，
                // 与本枚举的 tokenRole 字段是同一件事的第二份抄写；
                // 两份抄写必然分叉，而分叉的那一侧（审批白名单）直接决定谁能批钱。
                if (r.tokenRole == null) {
                    throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "角色 " + r.configKey() + " 被标记为可审批但未登记其 token 角色码 —— "
                                    + "审批白名单不完备即不得启用该角色");
                }
                out.add(r.tokenRole);
            }
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款审批白名单为空 —— 出口集中的前提下无人可审批，所有终止工单将永久挂起");
        }
        return Set.copyOf(out);
    }
}