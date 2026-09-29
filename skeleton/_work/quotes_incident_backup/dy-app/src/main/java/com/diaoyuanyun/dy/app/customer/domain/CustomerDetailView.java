package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * B4 客户详情的<b>内部视图</b> —— 携带全部字段，出参由
 * {@link #toContractData(VisibilityRole, java.util.List)} 按 {@code x-visible-to} 裁剪。
 *
 * <h2>为什么"内部视图"与"出参"是两个东西</h2>
 * 若直接拿一张"已裁剪的 Map"当数据载体，那么<b>裁剪逻辑就会散布到每个构造点</b> ——
 * 而这个仓库已有明确教训（{@code AuthMeDeclaration} 的类注释）：
 * "控制器一处、服务层一处"的两份裁剪口径必然分叉，而分叉的那一侧不报错。
 * 故这里让<b>裁剪只有一个落点</b>（本 record 的 {@link #toContractData}），
 * 服务层只管把数据填齐、并按角色调用它。
 *
 * <h2>🛑 契约逐字要求的 {@code denied_fields} 回显</h2>
 * 契约 B4 描述：「🔴 客户 token 请求含派生字段的 query（如 {@code ?include=verdict}）
 * → 403 {@code VISIBILITY_DENIED}，{@code data.denied_fields} 回显被拒字段名」。
 * 该 403 在<b>进入本 record 之前</b>就已由 {@link CustomerFieldVisibility#assertIncludeAllowed}
 * 抛出（服务层调用顺序见 B4 服务层注释），故本类不重复实现回显逻辑 ——
 * 它只负责"能走到这里的请求，响应体应当长什么样"。
 *
 * <h2>🛑 与 {@code CustomerCreateData}（B2 出参）的差别</h2>
 * <pre>
 *   B2 CustomerCreateData：customer_id / status / owner_store_id / serving_store_id
 *                          （后两者 x-visible-to 不含 client ⇒ 对客户不下发）
 *   B4 CustomerDetailData：见 CustomerDetailField 登记表（11 个字段）
 * </pre>
 * 两个 schema 的客户可见集<b>不同</b>，故它们是两个 record，<b>不得合并</b>。
 */
public record CustomerDetailView(
        UUID customerId,
        String name,
        String gender,
        Integer age,
        Map<String, Object> intakeProfile,
        String screeningResult,
        String bandWillingness,
        UUID ownerStoreId,
        UUID servingStoreId,
        String effectVerdict,
        BigDecimal asValue) {

    /** 构造期最小校验：主键非空。 */
    public CustomerDetailView {
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "客户详情缺 customer_id —— B4 的路径参数即客户标识，缺它则响应无主体");
        }
    }

    /**
     * 按端角色与 include 裁剪成契约 {@code CustomerDetailData} 的键值形态。
     *
     * <h2>裁剪规则（逐条对应契约）</h2>
     * <pre>
     *  ① 默认下发 = CustomerFieldVisibility.defaultVisible(role)
     *     —— 非派生 且 x-visible-to 含该角色
     *  ② 追加下发 = CustomerFieldVisibility.resolveIncludes(includes, role)
     *     —— 客户点名且该角色可见（派生字段只能由此路径进入）
     *  ③ 不可见字段【不下发该键】—— 不是 null、不是空串
     *     （契约 §2.0 visibility 逐字：「无权限字段不下发（不是 null、不是空串）」）
     * </pre>
     *
     * <p>🛑 第 ③ 条是本方法的核心：{@code map.put(k, null)} 会让序列化后出现
     * {@code "owner_store_id": null} —— 那等于告诉客户"这里有一个你看不到的门店字段"。
     * 契约选的是<b>该键对他不存在</b>。故一律"不 put"，而不是"put null"。
     *
     * <p>调用前置（由服务层保证，本方法不重复做）：
     * {@link CustomerFieldVisibility#assertIncludeAllowed} 已确认 include 对该角色全部可见。
     * 故此处 {@code resolveIncludes} 的裁剪不会漏字段 —— 两个方法配合，缺一不可。
     */
    public Map<String, Object> toContractData(VisibilityRole role, java.util.List<String> includes) {
        Set<CustomerDetailField> visible = CustomerFieldVisibility.defaultVisible(role);
        visible.addAll(CustomerFieldVisibility.resolveIncludes(includes, role));

        Map<String, Object> out = new LinkedHashMap<>();
        // 按契约 CustomerDetailData 的书写序装配，保证同一请求的响应体稳定可比对
        for (CustomerDetailField f : CustomerDetailField.values()) {
            if (!visible.contains(f)) {
                continue;   // 不下发该键（不是 put(null)）
            }
            Object v = valueOf(f);
            // 🛑 值为 null 时【仍然下发该键】：该字段对他是可见的，只是恰好没值。
            //    与"字段对他不可见"是两件事（前者 = key 在、值为 null；后者 = key 不在）。
            out.put(f.jsonName(), v);
        }
        return out;
    }

    /** 取某字段的值（键名逐字来自契约）。 */
    private Object valueOf(CustomerDetailField f) {
        return switch (f) {
            case CUSTOMER_ID -> customerId == null ? null : customerId.toString();
            case NAME -> name;
            case GENDER -> gender;
            case AGE -> age;
            case INTAKE_PROFILE -> intakeProfile;
            case SCREENING_RESULT -> screeningResult;
            case BAND_WILLINGNESS -> bandWillingness;
            case OWNER_STORE_ID -> ownerStoreId == null ? null : ownerStoreId.toString();
            case SERVING_STORE_ID -> servingStoreId == null ? null : servingStoreId.toString();
            case EFFECT_VERDICT -> effectVerdict;
            case AS_VALUE -> asValue;
        };
    }
}