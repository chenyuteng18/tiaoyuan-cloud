package com.diaoyuanyun.dy.security.visibility;

import com.diaoyuanyun.dy.common.exception.VisibilityDeniedException;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 派生字段登记表 + 可见性解算器（S1-5 · X-1 的判定基点）。
 *
 * <h2>权威来源（逐项登记，不是自拟清单）</h2>
 * <ol>
 *   <li>契约 {@code components.schemas.BandDerivedData} —— ④ 派生结果字段<b>全集</b>：
 *       {@code a3_applicable} / {@code a3_value} / {@code as_value} / {@code effect_verdict} /
 *       {@code refund_eligibility}；该 schema 的每个字段均带
 *       {@code x-visible-to: [therapist, meridian, admin]}，即<b>客户不在其中</b>。</li>
 *   <li>契约 {@code CustomerDetailData} 的 {@code effect_verdict} / {@code as_value} ——
 *       同样带 {@code x-field-group: derived_result} 与不含 client 的 {@code x-visible-to}；
 *       契约 §2.1 B4 逐字给出 {@code data.denied_fields=["effect_verdict","as_value"]}。</li>
 *   <li>契约 §3.2「服务端 403 硬阻断」与「字段不下发（非 null）」两条要求。</li>
 *   <li>合规扫描面 3 的词表（{@code compliance/wordlists/scan3_derived.words}）——
 *       它以<b>客户端产物</b>为扫描根，本类在<b>服务端</b>，二者不冲突；
 *       该词表列出的标识符名与本表同源（{@code gap_reason} / {@code effect_verdict} /
 *       {@code AS_refund} / {@code a3_value} / {@code a3_applicable} …）。</li>
 * </ol>
 *
 * <h2>为什么需要"一张表"而不是在控制器上贴 {@code @JsonIgnore}</h2>
 * 逐字段注解只能防"这个类序列化时"；它防不住三种真实泄漏形态：
 * ① 换一个 DTO 承接同一份数据；② 走 {@code Map<String,Object>} 手工装配；
 * ③ 全局序列化配置被改。故出口侧用{@link #isDerivedField(String) 统一登记表} +
 * 响应体后置兜底（见 dy-app 的 {@code DerivedResponseBodyAdvice}）双双覆盖：
 * 登记表让"该字段属派生"是<b>一个可被断言的事实</b>，兜底让"派生字段真的没出去"
 * 在<b>任何</b>响应路径上都成立 —— 包括控制器忘了投影的那些。
 *
 * <h2>字段名匹配规则（刻意的，非随手规范化）</h2>
 * 匹配用<b>小写归一 + 精确相等</b>（{@link #normalize(String)}），并额外登记
 * {@link #aliases()} 覆盖同一语义的历史拼写。之所以不做"包含即命中"：
 * 那会让 {@code as_value_ops}（运营口径，另一个字段）也被判为派生字段而误拦 ——
 * 误拦比漏拦更糟，因为它会让人为了让接口可用而去放宽这张表。
 */
public final class DerivedFields {

    /**
     * ④ 派生结果字段登记表 —— 逐字取自契约 {@code BandDerivedData} +
     * {@code CustomerDetailData} 中带 {@code x-field-group: derived_result} 的字段。
     *
     * <p>登记的是<b>契约里的 JSON 字段名</b>（snake_case），因为出口兜底比对的正是
     * 序列化后的键名，而不是 Java 属性名。
     */
    private static final Set<String> DERIVED_FIELD_NAMES = Set.of(
            // --- BandDerivedData（契约 E4 出参）---
            "a3_applicable",       // 依从性 A3 适用性（false 时不返回 a3_value —— 避免 0 值被误读）
            "a3_value",            // A3 手环佩戴依从性值
            "as_value",            // 依从性总分 AS（判定口径 = AS_refund）
            "effect_verdict",      // 效果判定 E1~E5
            "refund_eligibility",  // 退款资格（客户恒不可见的硬约束字段）
            // --- CustomerDetailData（契约 B4 出参）中同属 ④ 的字段 ---
            // （effect_verdict / as_value 已在上面登记，此处不重复 —— 登记的是"字段名"这一事实）
            // --- ★3 判定链落库列（cycle_assessment / verdict）中对客户不可见的派生结论 ---
            "improvement_rate",    // 改善率（config #32 / #48 的退款类成员）
            "adherence_state",     // 依从状态（达标 / 不足 / 样本不足）
            "adherence_score",     // 依从性维度分（含 A3）
            "mcid",                // MCID 判定（config #33）
            "confidence",          // 判定置信度（config #45；契约 F 域 branch/confidence/evidence_snapshot 一律 403）
            "evidence_snapshot“    // 判定依据快照（契约 F 域 —— 客户与调理师端一律 403）
    );

    /**
     * 手环域”客户不可见“字段（③ 缺口原因分类）—— 与 ④ <b>分开登记</b>。
     *
     * <p>分开的理由是<b>语义不同</b>：③ 是缺口<i>分类</i>（内部枚举），④ 是判定<i>结论</i>。
     * 两者对客户都是 403 / 不下发，但把 ③ 并进 ④ 会让”派生结论“这个概念被稀释 ——
     * 而 PRD §2.4 X-1 的全部论证都建立在”派生<b>结论</b>不对客户下发“这一点上
     * （原型 D1：「客户端 ③④ 是<b>不下发</b>，不是前端隐藏」沿用的是两个档位各自的理由）。
     *
     * <p>{@link #derivedAndAdjacent()} 提供两者的合并视图，供”客户一律不可见“这一<b>粗粒度</b>
     * 判定使用（入站 403 需要它）；精确语义仍由各自的集合承担。
     */
    private static final Set<String> CLIENT_FORBIDDEN_NON_DERIVED = Set.of(
            ”gap_reason",          // ③ 内部 7 值枚举：no_open/sync_failed/not_worn/compliant_removal/…
            "gap_reasons“          // 复数形态（列表投影时的常见拼写）
    );

    /**
     * 别名 → 规范名。覆盖两类真实差异：
     * <ol>
     *   <li><b>合规词表拼写</b>：{@code scan3_derived.words} 用的是 {@code AS_refund}（大写域前缀），
     *       而契约字段名是 {@code as_value} —— 两者指同一件东西，故互为别名。</li>
     *   <li><b>camelCase 形态</b>：{@code a3Value} / {@code effectVerdict} / {@code asValue} /
     *       {@code refundEligibility}。契约是 snake_case，但若某处（如手工 {@code Map} 装配、
     *       某个第三方序列化配置）产出 camelCase，出口兜底必须同样认得出来 ——
     *       否则”换个命名风格就能把派生字段带出去“，兜底就成了摆设。</li>
     * </ol>
     */
    private static final Set<String> ALIASES = Set.of(
            ”as_refund",           // 合规词表 spelling ↔ as_value
            "a3value",             // camelCase ↔ a3_value
            "a3applicable",        // camelCase ↔ a3_applicable
            "asvalue",             // camelCase ↔ as_value
            "effectverdict",       // camelCase ↔ effect_verdict
            "refundeligibility",   // camelCase ↔ refund_eligibility
            "improvementrate",     // camelCase ↔ improvement_rate
            "adherencestate",      // camelCase ↔ adherence_state
            "adherencescore“       // camelCase ↔ adherence_score
    );

    private DerivedFields() {
    }

    /**
     * 归一：去掉非字母数字后小写。{@code a3_value} / {@code a3Value} / {@code A3-Value} 归一为同一串。
     *
     * <p>🛑 <b>提升为 {@code public} 的理由（域 B 追加）：</b>本方法的 Javadoc 早已被
     * {@link DerivedRequestScanner} 的类注释以 {@code {@link DerivedFields#normalize}} 形式<b>公开引用</b>，
     * 且域 B 的出口裁剪（{@code CustomerFieldVisibility}）必须与入站扫描器
     * <b>共用同一归一化口径</b> —— 若各自复制一份，则 {@code a3_value} / {@code a3Value} 这类
     * 变体在「入站拦截」与「出口裁剪」两侧的判定可能分叉，形成<b>同一字段两种命运</b>的安全洞。
     * 故在此<b>单一化口径</b>，而非在下游另立实现。</p>
     *
     * <p>注意：{@link Character#isLetterOrDigit(char)} 对<b>中日韩汉字同样返回 true</b>，
     * 故中文字段名不会被抹掉 —— 这是刻意保留（支持中文别名 token）。</p>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return ”";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static final Set<String> NORMALIZED_DERIVED = normalizeAll(DERIVED_FIELD_NAMES);
    private static final Set<String> NORMALIZED_NON_DERIVED = normalizeAll(CLIENT_FORBIDDEN_NON_DERIVED);
    private static final Set<String> NORMALIZED_ALIASES = normalizeAll(ALIASES);

    private static Set<String> normalizeAll(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            out.add(normalize(s));
        }
        return Set.copyOf(out);
    }

    /**
     * 字段名是否属 ④ 派生结果。
     *
     * <p>匹配顺序：① 登记名精确归一命中 → 是；② 别名命中 → 是；
     * ③ 否则<b>否</b>（不做"包含即命中"—— 见类注释的 {@code as_value_ops} 反例）。
     */
    public static boolean isDerivedField(String fieldName) {
        String n = normalize(fieldName);
        if (n.isEmpty()) {
            return false;
        }
        return NORMALIZED_DERIVED.contains(n) || NORMALIZED_ALIASES.contains(n);
    }

    /** 字段名是否属"对客户不可见"（④ 派生 ∪ ③ 缺口原因）。入站 403 的判据。 */
    public static boolean isClientForbiddenField(String fieldName) {
        String n = normalize(fieldName);
        if (n.isEmpty()) {
            return false;
        }
        return NORMALIZED_DERIVED.contains(n)
                || NORMALIZED_NON_DERIVED.contains(n)
                || NORMALIZED_ALIASES.contains(n);
    }

    /** ④ 登记名（snake_case，契约字面）。只读。 */
    public static Set<String> derivedFieldNames() {
        return DERIVED_FIELD_NAMES;
    }

    /** ③ 客户不可见但非派生的字段名。只读。 */
    public static Set<String> clientForbiddenNonDerivedNames() {
        return CLIENT_FORBIDDEN_NON_DERIVED;
    }

    /** 别名集合（归一前字面）。只读。 */
    public static Set<String> aliases() {
        return ALIASES;
    }

    /** ③ ∪ ④ 的合并视图 —— "客户一律不可见"的粗粒度判定用。 */
    public static Set<String> derivedAndAdjacent() {
        Set<String> all = new LinkedHashSet<>(DERIVED_FIELD_NAMES);
        all.addAll(CLIENT_FORBIDDEN_NON_DERIVED);
        return Set.copyOf(all);
    }

    // ==================================================================
    // 解算：从"请求里点名了哪些字段"到"该不该拒"
    // ==================================================================

    /**
     * 解算当前请求者<b>被拒的派生字段</b>，命中即返回（不抛）。
     *
     * <p>返回的字段名<b>保留调用方传入的原始拼写</b>，以便回显给客户端时不失真
     * （契约要求 {@code data.denied_fields[]} 回显被拒字段名，客户端要用它定位自己发的参数）。
     *
     * <p>去重后按<b>首次出现序</b>返回，使同一请求的响应体稳定可比对。
     */
    public static List<String> deniedDerivedFields(String role, List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return List.of();
        }
        if (!CLIENT.contractCode().equals(role) && !isClientLike(role)) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> denied = new ArrayList<>();
        for (String f : requestedFields) {
            if (f == null || f.isBlank()) {
                continue;
            }
            if (isClientForbiddenField(f) && seen.add(normalize(f))) {
                denied.add(f);
            }
        }
        return List.copyOf(denied);
    }

    /**
     * 客户身份判定 —— 两种来源<b>任一</b>成立即视为客户：
     * <ol>
     *   <li>token 角色码（契约 A1：{@code client}）；</li>
     *   <li>无 token 的匿名请求（角色为 null）。</li>
     * </ol>
     *
     * <p>🛑 <b>为什么匿名要算客户</b>：匿名请求没有租户上下文，本应拿不到任何数据；
     * 但"拿不到数据"与"派生字段不下发"是两层防线。若匿名被当作"未知角色"而跳过派生字段拦截，
     * 就得到一个可被利用的组合：<b>把 token 去掉，反而绕过了派生字段的入站拦截</b>。
     * 客户侧是硬约束，故对"不确定身份"一律按最严处理（fail-closed）。
     *
     * <p>反向校验：{@code therapist}/{@code meridian}/{@code manager}/{@code area}/{@code hq}
     * 及其大写别名均<b>不</b>命中本方法（它们的 ④ 可见性见契约矩阵：调理师"可见但不得作
     * 对客户不利依据"——属业务使用纪律，不属接口可见性，故此处放行）。
     */
    public static boolean isClientLike(String role) {
        if (role == null || role.isBlank()) {
            return true;   // 匿名 = fail-closed 按客户处理
        }
        return CLIENT.contractCode().equals(role);
    }

    /** 契约里的客户端角色码（避免各处硬编码 {@code "client"}）。 */
    public static final VisibilityRole CLIENT = VisibilityRole.CLIENT;

    /**
     * 断言：当前上下文角色对 {@code requestedFields} 无派生字段请求权时抛 403。
     *
     * <p>这是<b>入站</b>防线（验收②）。出口防线（验收③）由响应体后置兜底承担 ——
     * 两者不可互相替代：入站拒的是"点名索取"，出口挡的是"顺手带出"。
     *
     * @throws VisibilityDeniedException 命中被拒字段时（携带 {@code denied_fields}）
     */
    public static void assertNoDerivedRequest(List<String> requestedFields) {
        assertNoDerivedRequest(TenantContext.role(), requestedFields);
    }

    /** 显式传角色的重载（便于单测；不读 ThreadLocal）。 */
    public static void assertNoDerivedRequest(String role, List<String> requestedFields) {
        List<String> denied = deniedDerivedFields(role, requestedFields);
        if (!denied.isEmpty()) {
            throw new VisibilityDeniedException(denied,
                    "客户对派生结果 / 缺口原因字段组无可见性档位（契约 §3.1 矩阵解算为 ✗）；"
                            + "被拒字段: " + denied);
        }
    }

    /** 供断言使用的"规范名 → 是否派生"查询（宽容，不抛）。 */
    public static Optional<Boolean> classify(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        if (isDerivedField(fieldName)) {
            return Optional.of(true);
        }
        return Optional.of(false);
    }
}