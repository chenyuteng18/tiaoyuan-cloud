package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户详情（契约 B4 {@code CustomerDetailData}）的<b>字段登记表</b> —— 逐字段照抄契约的
 * {@code x-visible-to} 与 {@code x-field-group}。
 *
 * <h2>权威来源（contract/openapi-v1.0.0.yaml L1586~L1610，逐字段）</h2>
 * <pre>
 *  customer_id        x-visible-to: [client, therapist, meridian, admin]
 *  name               x-visible-to: [client, therapist, meridian, admin]
 *  gender             x-visible-to: [client, therapist, meridian, admin]
 *  age                x-visible-to: [client, therapist, meridian, admin]
 *  intake_profile     x-visible-to: [client, therapist, meridian, admin]
 *  screening_result   x-visible-to: [client, therapist, meridian, admin]
 *  band_willingness   x-visible-to: [client, therapist, meridian, admin]
 *  owner_store_id     x-visible-to: [therapist, meridian, admin]          ← client 排除
 *  serving_store_id   x-visible-to: [therapist, meridian, admin]          ← client 排除
 *  effect_verdict     x-visible-to: [therapist, meridian, admin]
 *                     x-field-group: derived_result                      ← client 排除 + 派生
 *  as_value           x-visible-to: [therapist, meridian, admin]
 *                     x-field-group: derived_result                      ← client 排除 + 派生
 * </pre>
 *
 * <h2>🛑 「对 staff 可见」与「默认下发」是两件事 —— 派生字段属后者</h2>
 * 契约 B4 的 {@code include} 参数说明逐字：「追加字段（如 verdict）」。
 * 也就是说 {@code effect_verdict} / {@code as_value} 虽然在 {@code x-visible-to} 里
 * <b>含</b>调理师/经络师/管理员，但它们是<b>被点名索取才下发</b>的追加字段。
 * 本枚举用 {@link #isDerived()} 区分这两类，{@link CustomerFieldVisibility#defaultVisible}
 * 只给非派生字段，派生字段必须经 {@code include} 才进结果。
 *
 * <p>🛑 这个区分不是"体验优化"：若把派生字段默认下发，一次普通的"看客户档案"
 * 就会把效果判定与 AS 值带进任何缓存/日志/前端状态里 ——
 * 而契约把它们单列为追加字段，正是为了让它<b>只在明确需要时出现</b>。
 *
 * <h2>🛑 与 {@code DerivedFields} 的关系：判据同源，但本类<b>不</b>复制它的清单</h2>
 * 全局的派生字段登记表是 {@code com.diaoyuanyun.dy.security.visibility.DerivedFields}
 * （出口兜底与入站扫描都用它）。本枚举只登记<b>B4 响应体的字段集</b>，
 * 并在 {@link #isDerived()} 上与全局口径保持一致（{@code effect_verdict} / {@code as_value}
 * 都在 {@code DerivedFields} 里登记过）。本类<b>不</b>另立一份"派生字段清单"，
 * 只标"本字段在本响应体里属追加性质"。
 */
public enum CustomerDetailField {

    /** 客户 ID。四角色可见、默认下发。 */
    CUSTOMER_ID("customer_id", false, true),

    /** 姓名。四角色可见、默认下发。 */
    NAME("name", false, true),

    /** 性别。四角色可见、默认下发。 */
    GENDER("gender", false, true),

    /** 年龄（建档快照）。四角色可见、默认下发。 */
    AGE("age", false, true),

    /** 建档扩展档案（对象）。四角色可见、默认下发。 */
    INTAKE_PROFILE("intake_profile", false, true),

    /** 筛查结论（通过 / 不通过）。四角色可见、默认下发。 */
    SCREENING_RESULT("screening_result", false, true),

    /** 手环佩戴意愿。四角色可见、默认下发。 */
    BAND_WILLINGNESS("band_willingness", false, true),

    /** 归属（首诊）门店 ID。🔴 <b>客户不下发</b>（契约 x-visible-to 不含 client / 描述「客户不下发」）。 */
    OWNER_STORE_ID("owner_store_id", false, false),

    /** 当前服务门店 ID。🔴 <b>客户不下发</b>。 */
    SERVING_STORE_ID("serving_store_id", false, false),

    /**
     * 效果判定（派生）。🔴 客户不可见；对 staff 亦为<b>追加字段</b>。
     *
     * <p>契约 B4 描述逐字点名它与 {@link #AS_VALUE}：
     * 「🔴 客户 token 请求含派生字段的 query（如 {@code ?include=verdict}）→ 403
     * {@code VISIBILITY_DENIED}，{@code data.denied_fields} 回显被拒字段名」。
     */
    EFFECT_VERDICT("effect_verdict", true, false),

    /**
     * 依从性总分 AS（派生）。🔴 客户不可见；对 staff 亦为<b>追加字段</b>。
     *
     * <p>契约描述逐字「派生字段；客户恒不下发」。
     */
    AS_VALUE("as_value", true, false);

    private final String jsonName;
    private final boolean derived;
    private final boolean clientVisible;

    CustomerDetailField(String jsonName, boolean derived, boolean clientVisible) {
        this.jsonName = jsonName;
        this.derived = derived;
        this.clientVisible = clientVisible;
    }

    /** 契约里的字段名（snake_case，即序列化后的 JSON 键）。 */
    public String jsonName() {
        return jsonName;
    }

    /**
     * 是否属"派生结果"（契约 {@code x-field-group: derived_result}）。
     *
     * <p>含义有二：① 客户不可见；② 对 staff 属 {@code include} 追加字段。
     */
    public boolean isDerived() {
        return derived;
    }

    /** 客户（{@code client} 端角色）是否可收到该字段。 */
    public boolean isClientVisible() {
        return clientVisible;
    }

    /** 该字段对给定端角色是否可下发（不问"默认还是追加"，只问"可不可见"）。 */
    public boolean isVisibleTo(VisibilityRole role) {
        if (role == null) {
            // fail-closed：角色不明 ⇒ 只认"四角色全可见"的那批（见 assertClientLikeDefault）
            return clientVisible;
        }
        if (role == VisibilityRole.CLIENT) {
            return clientVisible;
        }
        // 三个 staff 端角色（THERAPIST / MERIDIAN / ADMIN）在契约里对上述全部字段均为可见
        return true;
    }

    /** 全部字段（契约书写序）。 */
    public static List<CustomerDetailField> all() {
        return List.of(values());
    }

    /** 按契约 JSON 名解析；未登记抛错（fail-closed），绝不回落。 */
    public static CustomerDetailField of(String jsonName) {
        for (CustomerDetailField f : values()) {
            if (f.jsonName.equals(jsonName)) {
                return f;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的客户详情字段名: " + (jsonName == null ? "<null>" : "『" + jsonName + "』")
                        + "（已登记: " + Arrays.stream(values()).map(CustomerDetailField::jsonName).toList()
                        + "；权威来源 = 契约 CustomerDetailData）—— 见 CustomerFieldVisibility 的分组别名表");
    }

    /** 宽容解析：未登记返回 {@code null}。 */
    public static CustomerDetailField tryOf(String jsonName) {
        for (CustomerDetailField f : values()) {
            if (f.jsonName.equals(jsonName)) {
                return f;
            }
        }
        return null;
    }

    /** 不可下发字段的 JSON 名集合（供断言：客户对它们的 403 必须逐字回显）。 */
    public static Set<String> clientForbiddenNames() {
        Set<String> out = new LinkedHashSet<>();
        for (CustomerDetailField f : values()) {
            if (!f.clientVisible) {
                out.add(f.jsonName);
            }
        }
        return out;
    }
}