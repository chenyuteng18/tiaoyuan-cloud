package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.FieldGroup;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 手环数据可见性矩阵 —— 归一化自 config {@code #43}（{@code cfg:band.visibility}）。
 *
 * <h2>权威来源（逐字取自声明文件，不是自拟）</h2>
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 的 {@code #43} 行，
 * 其 JSON 形态为：
 * <pre>
 *   {"customer":{"raw_data":true,"capture_status":true,"gap_reason":false,"derived_result":false},
 *    "therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true,
 *                 "derived_not_adverse_to_customer":true},
 *    "meridian_therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true},
 *    "admin":{"scope_narrowed_by_row":true,"raw_data":true,"capture_status":true,
 *             "gap_reason":true,"derived_result":true},
 *    "store_customer_service":{"endless":true},
 *    "customer_gap_reason_and_derived_locked":true,
 *    "unconfigured_means_deny":true}
 * </pre>
 * 与契约 §3.1「四档 × 五角色」矩阵、原型 D1 屏、PRD §2.6.1 <b>四源一致</b>。
 *
 * <h2>🛑 与 config {@code #40}（退款可见性）不得合并</h2>
 * PRD / freeze §3.3 <b>C-2</b> 逐字：「{@code #40} 管退款（<b>角色级二分</b>）、
 * {@code #43} 管手环数据（<b>角色 × 字段组四档</b>），独立配置、<b>不得合并</b>」。
 * 故本类<b>不</b>把 {@code RefundVisibilityMatrix} 的任何一项并进来，
 * A2 的 {@code refund_visibility} 由 {@code RefundVisibilityMatrix} 单独解算
 * （见 {@code AuthMeService}）。两者在 A2 的响应里<b>并列</b>出现，
 * 但它们的真相源是两个配置项。
 *
 * <h2>🛑 三条硬锁（配置改坏即抛，绝不静默执行）</h2>
 * <ol>
 *   <li><b>客户侧 ③④ 恒不可见</b>。依据 {@code #43} 行语义列的逐字警告：
 *       「⚠️ 客户侧 ③④ 为<b>硬约束、不得通过配置放开</b> —— 放开属『变更业务裁定』」，
 *       以及契约 §3.1 附注同款声明。这不是"建议值"，是冻结项。</li>
 *   <li><b>客户 ①② 恒可见</b>。同源于 §3.1 矩阵：客户对"手环原始数据"与"采集状态"
 *       是 ✅。把它改成不可见会让客户端"看不到自己的手环数据"——
 *       PRD §2.8.7② 明确要求客户端能看到同步状态与可执行下一步。
 *       <p>⚠️ 与 ③④ 的区别：③④ 是"内部信息不得外泄"（安全方向），
 *       ①② 是"客户对自己数据有知情权"（权利方向）。两条锁挡的是相反方向的误改。</li>
 *   <li><b>{@code customer_gap_reason_and_derived_locked} 必须为 {@code true}</b>。
 *       它是第 1 条锁在配置侧的<b>自描述开关</b>：若有人把它改成 {@code false}，
 *       说明有人在"允许客户看缺口原因 / 派生结果"的方向上动过手 ——
 *       此时即便四个档位值暂时还没改，也应立刻失败并点名。</li>
 * </ol>
 *
 * <h2>🛑 {@code derived_not_adverse_to_customer} 是"使用纪律"，不是可见性</h2>
 * 调理师行的这个标志（值 = {@code true}）表达的是 freeze §3.1 表里
 * 「调理师 ④ ✅ 可见 · <b>不作不利依据</b>」里的后半句。
 * 它<b>不参与</b> {@code canSee} 判定 —— 调理师对 ④ 就是可见的，
 * 那条纪律约束的是"拿这个数去做什么"（业务规则），不是"能不能看到这个数"（接口可见性）。
 * 本类把它<b>解析出来并保留</b>（{@link #derivedNotAdverseToCustomer()}），
 * 供上层在需要时读取，但<b>不</b>用它改任何一个档位。
 * 若把它读成"调理师看不到 ④"，就直接违反契约矩阵 —— 那是一次方向完全错误的收紧。
 *
 * <h2>fail-closed</h2>
 * 五个角色键缺一即抛；四档布尔值缺一即抛；含未登记的字段组键即抛；
 * 含未登记的角色键即抛。🛑 不提供"未配置即全开"（泄漏）或"未配置即全关"
 * （让客户端看不到自己的数据）的兜底 —— 唯一正确的动作是<b>拒绝启用并点名缺失项</b>。
 *
 * <h2>⚠️ 为什么它<b>不</b>直接读 JSON 字符串，而是接收已解析的 {@link JsonNode}</h2>
 * 与 {@code RefundVisibilityMatrix.fromConfigJson(String)} 的差别在此：
 * 本域的口径来源（{@code ConfigSeedBandProfileSource}）已经要把整行声明解析出来，
 * 若它再交一个字符串给本类、由本类二次解析，就会出现"两处各自 readTree"的形态。
 * 故本类接收<b>已解析的节点</b>，读 JSON 这件事只有一个落点。
 */
public record BandVisibilityMatrix(
        Map<BandAudienceRole, BandFieldVisibility> rows,
        boolean customerGapReasonAndDerivedLocked,
        boolean unconfiguredMeansDeny,
        boolean derivedNotAdverseToCustomer,
        boolean adminScopeNarrowedByRow,
        String source) {

    /** 矩阵里的<b>行为标志位</b>（不是角色键，也不是字段组键）。 */
    private static final Set<String> BEHAVIOR_KEYS = Set.of(
            "customer_gap_reason_and_derived_locked",
            "unconfigured_means_deny");

    /** 角色行里的<b>行内标志位</b>（不是字段组键）。 */
    private static final Set<String> ROW_LEVEL_KEYS = Set.of(
            "derived_not_adverse_to_customer",
            "scope_narrowed_by_row",
            "endless");

    /**
     * 唯一构造入口：从 config {@code #43} 的原始声明值归一化。
     *
     * <p>逐项 fail-closed（见类注释）。解析完成即跑 {@link #assertHardLocks()}。
     */
    public static BandVisibilityMatrix fromConfigJson(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵未配置（cfg:band.visibility / config #43）—— "
                            + "未配置即拒绝：不得默认放行任何角色（数据泄漏），"
                            + "也不得静默拒绝（客户端将看不到自己的手环数据）");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵（config #43）不是合法 JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵（config #43）不是一个 JSON 对象");
        }
        return fromConfigNode(root);
    }

    /** 从已解析的节点归一化（供口径来源复用同一次解析）。 */
    public static BandVisibilityMatrix fromConfigNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵（config #43）不是一个 JSON 对象");
        }
        assertNoUnknownKeys(root);

        Map<BandAudienceRole, BandFieldVisibility> rows = new LinkedHashMap<>();
        for (BandAudienceRole role : BandAudienceRole.values()) {
            JsonNode node = root.path(role.configKey());
            if (node.isMissingNode()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "手环可见性矩阵缺角色行 '" + role.configKey() + "'（" + role.label() + "）—— "
                                + "未配置即拒绝：缺行时不得推断该角色全不可见。"
                                + "把一次配置漏写静默当成一次权限收紧，"
                                + "会让业务方以为是他改的那一项生效了");
            }
            if (!node.isObject()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "手环可见性矩阵角色行 '" + role.configKey() + "' 必须是对象（逐字段组声明），"
                                + "实际节点类型: " + node.getNodeType()
                                + "。🛑 不得接受布尔标量：本矩阵是『角色 × 字段组』二维，"
                                + "一个标量无从表达四档中的哪一档");
            }
            rows.put(role, parseRow(role, node));
        }

        boolean locked = requireTrueFlag(root, "customer_gap_reason_and_derived_locked",
                "客户侧 ③④ 为硬约束、不得通过配置放开（config #43 行语义逐字；开放属『变更业务裁定』）");
        boolean deny = requireTrueFlag(root, "unconfigured_means_deny",
                "未配置即拒绝（fail-closed）—— 若为假，说明有人把『缺配置』的方向调成了放行");

        // 行内标志：逐行读取，读不到取 false（它们不参与档位判定，见类注释）。
        boolean notAdverse = booleanOrFalse(rows.containsKey(BandAudienceRole.THERAPIST)
                ? root.path(BandAudienceRole.THERAPIST.configKey()).path("derived_not_adverse_to_customer")
                : null);
        boolean scopedByRow = booleanOrFalse(
                root.path(BandAudienceRole.ADMIN.configKey()).path("scope_narrowed_by_row"));

        BandVisibilityMatrix matrix = new BandVisibilityMatrix(
                java.util.Collections.unmodifiableMap(rows), locked, deny,
                notAdverse, scopedByRow, "config#43");
        matrix.assertHardLocks();
        return matrix;
    }

    /**
     * 含未登记键即抛。
     *
     * <p>🛑 不得静默忽略：一个未知键说明<b>有人已经在配置侧表达了一项新授权/新收窄</b>，
     * 只是代码还没跟上。"忽略它"等于把一次已发生的改动藏起来 ——
     * 而它可能是"放开客户 ③④"那种（这正是第 1 条硬锁要挡的方向）。
     */
    private static void assertNoUnknownKeys(JsonNode root) {
        Set<String> allowed = new LinkedHashSet<>(BandAudienceRole.allConfigKeys());
        allowed.addAll(BEHAVIOR_KEYS);
        Set<String> unknown = new LinkedHashSet<>();
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵含未登记键: " + unknown
                            + "（已登记角色行: " + BandAudienceRole.allConfigKeys()
                            + "；行为标志: " + BEHAVIOR_KEYS + "）。"
                            + "🛑 未登记键不得默认放行也不得默认忽略 —— "
                            + "它说明配置侧已有改动而代码未跟上；静默忽略等于把该改动藏起来。"
                            + "若属业务方新增角色，须先在 BandAudienceRole 登记该角色"
                            + "（并重走可见性矩阵冻结）");
        }
    }

    /** 解析一个角色行：四档布尔值 + 行内标志校验。 */
    private static BandFieldVisibility parseRow(BandAudienceRole role, JsonNode node) {
        Map<FieldGroup, Boolean> flags = new LinkedHashMap<>();

        // 🛑 无端角色（门店客服）：其行只有 {"endless":true}，没有任何字段组键。
        //    这不是"配置漏写"，而是"该行在语义上不成立"的显式声明。
        //    处理方式：登记为四档全 false，并要求 endless=true 确实存在。
        if (role.endRole().isEmpty()) {
            boolean endless = booleanOrFalse(node.path("endless"));
            if (!endless) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "手环可见性矩阵角色行 '" + role.configKey() + "'（" + role.label()
                                + "）无端、无账号（契约 x-roles: token-role=null, end=null），"
                                + "其行必须显式声明 endless:true —— "
                                + "缺该标志会让『这个角色在任何端上都不成立』这件事在配置里看不出落点");
            }
            for (FieldGroup g : FieldGroup.all()) {
                flags.put(g, false);
            }
            return new BandFieldVisibility(role, flags);
        }

        for (FieldGroup group : FieldGroup.all()) {
            JsonNode v = node.path(group.code());
            if (!v.isBoolean()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "手环可见性矩阵角色行 '" + role.configKey() + "' 缺字段组 '" + group.code()
                                + "'（" + group.label() + "）的布尔值（实际: " + v.getNodeType()
                                + "）—— 四档必须逐档声明：缺一档会让客户端拿到 undefined，"
                                + "而 undefined 与 false 是两条不同的渲染分支");
            }
            flags.put(group, v.asBoolean());
        }

        // 行内标志：允许存在但不参与档位（见类注释）。
        Iterator<String> names = node.fieldNames();
        Set<String> unknownRowKeys = new LinkedHashSet<>();
        while (names.hasNext()) {
            String name = names.next();
            boolean isFieldGroup = false;
            for (FieldGroup g : FieldGroup.all()) {
                if (g.code().equals(name)) {
                    isFieldGroup = true;
                    break;
                }
            }
            if (!isFieldGroup && !ROW_LEVEL_KEYS.contains(name)) {
                unknownRowKeys.add(name);
            }
        }
        if (!unknownRowKeys.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵角色行 '" + role.configKey() + "' 含未登记键: " + unknownRowKeys
                            + "（合法: 四个字段组码 " + FieldGroup.all().stream()
                            .map(FieldGroup::code).toList()
                            + " + 行内标志 " + ROW_LEVEL_KEYS + "）。"
                            + "🛑 不得静默忽略 —— 未登记的键可能正是新的挂载点");
        }
        return new BandFieldVisibility(role, flags);
    }

    private static boolean booleanOrFalse(JsonNode node) {
        return node != null && node.isBoolean() && node.asBoolean();
    }

    private static boolean requireTrueFlag(JsonNode root, String field, String reason) {
        JsonNode v = root.path(field);
        if (!v.isBoolean() || !v.asBoolean()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵标志 " + field + " 必须显式为 true —— " + reason);
        }
        return true;
    }

    // ==================================================================
    // 硬锁
    // ==================================================================

    /**
     * 三条硬锁的一致性断言（见类注释）。
     *
     * <p>🛑 抽成独立方法使"这条锁真的会拦人"可被<b>单独断言</b>：
     * 测试可以构造一个 {@code customer.gap_reason=true} 的矩阵并断言此处抛错，
     * 而不必请求任何一个真实端点。这正是"门禁有牙齿"的检验方式。
     *
     * <p>🛑 逐角色<b>指名</b>断言，而不是遍历一个集合 —— 遍历写法只有一层保护：
     * 把某个角色从集合里删掉，那道锁就整体消失，而集合本身只是个声明、没有守卫。
     * 指名后，删掉其中一条断言 = 那一行源码消失，反向验证必然变红。
     */
    private void assertHardLocks() {
        // ---- 硬锁 1：客户 ③④ 恒不可见（配置不得放开）----
        assertCustomerInvisible(FieldGroup.GAP_REASON,
                "契约 §3.1 矩阵 + config #43 行语义逐字：「⚠️ 客户侧 ③④ 为硬约束、"
                        + "不得通过配置放开 —— 放开属『变更业务裁定』」；"
                        + "且 §3.2 要求客户可调接口的响应体中 ③④ 字段【整体不存在】");
        assertCustomerInvisible(FieldGroup.DERIVED_RESULT,
                "同上（④ 派生结果 / AS 值 / effect_verdict / 退款资格）—— "
                        + "PRD §2.4 X-1 与原型 D1 逐字：「客户端 ③④ 是【不下发】，不是前端隐藏」");

        // ---- 硬锁 2：客户 ①② 恒可见（权利方向，与上面挡的是相反方向的误改）----
        assertCustomerVisible(FieldGroup.RAW_DATA,
                "契约 §3.1 矩阵：客户对 ① 手环原始数据为 ✅；"
                        + "PRD §2.8.7② 要求客户端能看到自己的同步状态与可执行下一步");
        assertCustomerVisible(FieldGroup.CAPTURE_STATUS,
                "契约 §3.1 矩阵：客户对 ② 采集状态为 ✅（含已采集天数 / 同步时间 / 接入状态）；"
                        + "把客户自己的采集状态对他关掉没有任何合规理由，"
                        + "只会让他无法判断『设备到底有没有在传』");

        // ---- 硬锁 3：配置侧自描述开关一致 ----
        if (!customerGapReasonAndDerivedLocked) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵标志 customer_gap_reason_and_derived_locked 不为 true —— "
                            + "它是硬锁 1 在配置侧的自描述开关：有人把它关掉，说明有人在"
                            + "『允许客户看缺口原因 / 派生结果』的方向上动过手。"
                            + "此时即便四个档位值暂时还没改，也应立刻失败并点名。"
                            + "若属业务方正式变更裁定：须同步更新本类的 assertHardLocks "
                            + "并重走 band_visibility 冻结");
        }
    }

    /** 单个"恒不可见"字段组的硬锁（客户）。 */
    private void assertCustomerInvisible(FieldGroup group, String reason) {
        BandFieldVisibility customer = rows.get(BandAudienceRole.CUSTOMER);
        if (customer == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵缺客户行 —— 硬锁无从校验");
        }
        if (customer.canSee(group)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "🛑 手环可见性硬锁被破：客户对 " + group.code() + "（" + group.label()
                            + "）必须恒不可见。" + reason
                            + "。若属业务方正式变更裁定：须同步更新本类的硬锁断言、"
                            + "重走 band_visibility 冻结，并核算对 T-11（契约 x-field-groups "
                            + "的客户端档位）的影响 —— 不得按『体验优化』处理");
        }
    }

    /** 单个"恒可见"字段组的硬锁（客户）。 */
    private void assertCustomerVisible(FieldGroup group, String reason) {
        BandFieldVisibility customer = rows.get(BandAudienceRole.CUSTOMER);
        if (customer == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵缺客户行 —— 硬锁无从校验");
        }
        if (!customer.canSee(group)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "🛑 手环可见性硬锁被破：客户对 " + group.code() + "（" + group.label()
                            + "）必须恒可见。" + reason
                            + "。与 ③④ 的锁方向相反：那两条防『内部信息外泄』，"
                            + "本条防『把客户对自己数据的知情权误关掉』");
        }
    }

    // ==================================================================
    // 便捷视图（上层唯一读取入口）
    // ==================================================================

    /**
     * 某受众角色的四档档位。
     *
     * <p>🛑 取不到直接抛而不返回"全 false"：构造期已断言五行全覆盖，
     * 走到这里取不到只可能是代码被绕过构造实例化 —— 那是 bug，
     * 不是"该角色没有档位"。返回全 false 会把一次 bug 伪装成一次权限收紧。
     */
    public BandFieldVisibility rowOf(BandAudienceRole role) {
        BandFieldVisibility row = rows.get(role);
        if (row == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵缺受众行 " + role.configKey() + " —— "
                            + "该情形应在构造期被拦下，出现即说明矩阵被绕过构造直接实例化");
        }
        return row;
    }

    /**
     * 端角色 → 四档档位（A2 解算链的入口）。
     *
     * <p>两跳：{@link BandAudienceRole#forVisibilityRole} 找到受众行，再取该行的四档。
     * 无端角色（门店客服）走到这里会抛 —— 这是正确行为：它没有端，任何请求都带不了它。
     */
    public BandFieldVisibility rowForEndRole(VisibilityRole endRole) {
        return rowOf(BandAudienceRole.forVisibilityRole(endRole));
    }

    /** 恒不可见的受众角色（供门禁交叉验证）。 */
    public static Set<BandAudienceRole> hardInvisibleRoles() {
        // 门店客服：无端、任何字段组均不成立（契约 x-roles + 业务方 2026-09-16 裁定）
        return Set.of(BandAudienceRole.STORE_CUSTOMER_SERVICE);
    }
}