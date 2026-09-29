package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 手环数据可见性矩阵（config {@code #43} {@code cfg:band.visibility}）的<b>受众角色键</b>。
 *
 * <h2>🛑 它为什么<b>不是</b> {@link VisibilityRole}，却又<b>登记了</b>它</h2>
 * 两者粒度不同，且差异是<b>配置本身要求的</b>：
 * <pre>
 *   契约 x-roles / VisibilityRole（端角色，4 个）：
 *       client | therapist | meridian | admin  （+ store_customer_service 无端）
 *   config #43 的键集（受众角色，5 个）：
 *       customer | therapist | meridian_therapist | admin | store_customer_service
 * </pre>
 * 差异落在两处：
 * <ol>
 *   <li><b>命名</b>：{@code client} ↔ {@code customer}；{@code meridian} ↔ {@code meridian_therapist}。
 *       这一层是纯命名，{@code #43} 的声明文件与契约 {@code x-roles} 各用各的写法；</li>
 *   <li><b>无端成员</b>：{@code store_customer_service} 在 {@code #43} 里是一个键
 *       （值 = {@code {"endless":true}}），而在契约 {@code x-roles} 里是
 *       {@code token-role: null, end: null} —— "无端、任何字段组均不成立"。</li>
 * </ol>
 *
 * <p>🛑 与 {@code RefundAudienceRole}（退款域 {@code #40} 的 7 键）的关键区别：
 * 退款域<b>必须在 admin 内部收窄</b>（PRD §2.2「区域督导 ✓（可见、不审批）」），
 * 故它的键集把 {@code manager / area / hq} 摊成三键；
 * 而本域（手环数据）的档位<b>不按层级收窄</b> —— 契约 {@code x-visibility-matrix}
 * 的列就是<b>端角色</b>，且附注逐字写明「admin 档位另按<b>行级 scope</b> 收窄」。
 * 收窄由 {@code store_scope} / A3 行级过滤承担，故本域尊重 {@code #43} 的
 * {@code admin} 一档，<b>不自行把层级塞进字段组判定</b>。
 *
 * <p>本类因此<b>登记 {@link VisibilityRole}</b>（端角色）而不是 token 角色字符串：
 * "token 角色 → 端角色"这一步在 {@link VisibilityRole} 里已有唯一落点
 * （{@code manager/area/hq/SUPER_ADMIN/… → ADMIN}），再抄一份 token 角色清单
 * 必然与它分叉 —— 而分叉的那一侧不会报错，只会安静地多给或少给一份档位。
 *
 * <h2>fail-closed</h2>
 * {@link #ofConfigKey(String)} 与 {@link #forVisibilityRole(VisibilityRole)} 对未登记值
 * <b>一律抛</b>，绝不回落。<b>尤其不得回落成"不可见"</b>：那会让一次配置键拼写错误
 * （如 {@code meridian_therpist}）静默变成"经络师什么都看不到"，而真正的成因
 * （有人写了个我们不认识的键）在日志里查无此事。同理也不得回落成"可见"。
 */
public enum BandAudienceRole {

    /** 客户（小程序）。{@code #43} 键 = {@code customer}；端角色 = {@link VisibilityRole#CLIENT}。 */
    CUSTOMER("customer", "客户", VisibilityRole.CLIENT),

    /** 调理师（APP）。{@code #43} 键 = {@code therapist}；端角色 = {@link VisibilityRole#THERAPIST}。 */
    THERAPIST("therapist", "调理师", VisibilityRole.THERAPIST),

    /**
     * 经络师（APP）。{@code #43} 键 = {@code meridian_therapist}；端角色 = {@link VisibilityRole#MERIDIAN}。
     *
     * <p>🛑 键名带 {@code _therapist} 后缀是 {@code #43} 的原文写法（配置侧有意强调
     * 「同一 APP 内调理师 / 经络师可见范围不同」，见 {@code #43} 的语义列）。
     * 契约侧的端角色码是 {@code meridian} —— 两个写法指同一个端角色，
     * 本枚举是它们的<b>唯一对应点</b>。
     */
    MERIDIAN_THERAPIST("meridian_therapist", "经络师", VisibilityRole.MERIDIAN),

    /**
     * 管理端（Web）。{@code #43} 键 = {@code admin}；端角色 = {@link VisibilityRole#ADMIN}。
     *
     * <p>🛑 {@link VisibilityRole#ADMIN} 覆盖 {@code manager / area / hq} 三个 token 角色
     * <b>及四个遗留大写别名</b>。本域刻意<b>不</b>在此收窄到层级 ——
     * 收窄由 {@code store_scope.row_level}（A2）与 A3 的行级过滤承担（契约附注逐字：
     * 「admin 档位另按行级 scope 收窄」）。
     */
    ADMIN("admin", "管理员", VisibilityRole.ADMIN),

    /**
     * 门店客服。<b>无端</b>（{@code endRoleCode = null}）。
     *
     * <p>契约 {@code x-roles} 对该角色的登记是 {@code token-role: null, end: null} ——
     * 它不使用本系统、没有账号，故<b>任何请求都不可能带着这个身份来</b>。
     * 业务方 2026-09-16 裁定其「完全不可见、不代录」。
     *
     * <p>保留它是为了让 {@code #43} 的键集能被<b>完整解析</b>：少了它，解析器遇到该键会报
     * "未知角色"，把一条<b>已裁定的事实</b>误报成"配置有误"。
     */
    STORE_CUSTOMER_SERVICE("store_customer_service", "门店客服", null);

    private final String configKey;
    private final String label;
    private final VisibilityRole endRole;

    BandAudienceRole(String configKey, String label, VisibilityRole endRole) {
        this.configKey = configKey;
        this.label = label;
        this.endRole = endRole;
    }

    /** config {@code #43} 的角色键（唯一解析入口）。 */
    public String configKey() {
        return configKey;
    }

    public String label() {
        return label;
    }

    /**
     * 对应的端角色；{@code null} = <b>无端</b>（门店客服）。
     *
     * <p>用 {@link Optional} 而非裸 {@code null}：调用方在"有无端"这件事上
     * 必须显式做决定（是拒还是跳过），不能靠 {@code == null} 顺手带过 ——
     * 后者正是"把一个无端角色当成有端角色处理"的典型入口。
     */
    public Optional<VisibilityRole> endRole() {
        return Optional.ofNullable(endRole);
    }

    /**
     * config 键 → 受众角色。<b>未登记即抛</b>（fail-closed）。
     *
     * <p>🛑 不得静默忽略未登记键：一个我们不认识的键，说明<b>有人已经在配置侧
     * 放开了某个角色的可见性</b>，只是代码还没跟上。忽略它等于把一次已发生的
     * 授权改动藏起来 —— 而它可能正是"新增了一个角色"的那种改动。
     */
    public static BandAudienceRole ofConfigKey(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "手环可见性矩阵（config #43）含空角色键 —— 空键无从判定任何字段组");
        }
        String k = configKey.trim();
        return Arrays.stream(values())
                .filter(r -> r.configKey.equals(k))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "手环可见性矩阵（config #43）含未登记角色键: '" + configKey + "'"
                                + "（已登记: " + allConfigKeys() + "）。"
                                + "🛑 不得默认放行也不得默认拒绝后静默通过 —— "
                                + "『矩阵里有个我不认识的角色』与『矩阵里没有这个角色』是两件事："
                                + "前者说明配置侧已有改动而代码未跟上，必须报错"));
    }

    /**
     * 端角色 → 受众角色。<b>端角色无对应时抛</b>（fail-closed）。
     *
     * <h2>为什么它是 A2 解算链上<b>唯一</b>的一跳</h2>
     * A2 要回答的问题顺序是：<b>请求者是谁</b>（token 角色）→ <b>他是哪个端角色</b>
     * （{@link VisibilityRole}）→ <b>他的字段组档位是哪一行</b>（本方法 → {@code #43}）。
     * 三跳各有唯一落点，任一跳散落进调用方就会出现
     * "A 处认为 {@code area} 落在 admin 行、B 处认为它没有档位"这类分叉 ——
     * 而分叉的那一侧不报错，只安静地多给或少给一份可见性。
     *
     * @throws BizException {@code VISIBILITY_DENIED(2001)} —— 语义是 403（身份无档位），不是 500
     */
    public static BandAudienceRole forVisibilityRole(VisibilityRole endRole) {
        if (endRole == null) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "请求未携带端角色 —— 手环数据可见性不设匿名通道："
                            + "档位是契约冻结项，无身份即无从判定（fail-closed，不回落任一受众）");
        }
        return Arrays.stream(values())
                .filter(r -> r.endRole == endRole)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        "端角色 " + endRole.contractCode() + "（" + endRole.label() + "）"
                                + "在手环可见性矩阵（config #43）里没有对应的受众行 —— "
                                + "无端角色不得被赋予任何字段组档位"));
    }

    /** 全部已登记的 config 键（供错误信息与登记表断言）。 */
    public static List<String> allConfigKeys() {
        return Arrays.stream(values()).map(BandAudienceRole::configKey).toList();
    }

    /** 全部<b>有端</b>的受众角色（无端的门店客服不计入）。 */
    public static List<BandAudienceRole> endBearingRoles() {
        return Arrays.stream(values()).filter(r -> r.endRole != null).toList();
    }
}