package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2 {@code GET /auth/me} 的<b>出站声明体</b> —— 可见性档位的唯一权威下发形态。
 *
 * <h2>🛑 它为什么是一个记录，而不是一段 {@code Map} 装配代码</h2>
 * 契约 A2 逐字：「本接口只下发「档位布尔值」，<b>不下发任何业务字段</b> ——
 * 它是<b>声明</b>，不是数据。」把这份约束做成一个<b>有类型</b>的值对象，
 * 使"多下发一个业务字段"必须先改本类 —— 而不是在控制器的某个
 * {@code Map.put(...)} 里顺手发生。契约的 {@code AuthMeData} 只有四个属性：
 * <pre>
 *   role             : string      （x-visible-to: 全 4 端）
 *   band_visibility  : object      （四个 boolean 子键，全 4 端可见）
 *   refund_visibility: boolean     （x-visible-to: [meridian, admin]）  ← 可空（对客户/调理师不下发）
 *   store_scope      : object      （x-visible-to: [therapist, meridian, admin]）← 可空（对客户不下发）
 * </pre>
 * 故本类携带<b>恰好</b>这四项；两个"按角色裁剪"的项用 {@code null} 表达"不下发"。
 *
 * <h2>🛑 「不下发」用 {@code null} + 出站剔除，而不是"下发一个 false"</h2>
 * 两者的区别是实质性的：
 * <ul>
 *   <li>{@code refund_visibility: false} 对客户下发，等于告诉他
 *       "<b>存在</b>一个叫退款可见性的档位，你的值是 false" —— 而契约
 *       {@code x-visible-to: [meridian, admin]} 的语义是<b>该键对客户不存在</b>；</li>
 *   <li>{@code store_scope} 同理：对客户下发一个空对象会被读成"我没有范围限制"，
 *       方向与事实<b>完全相反</b>。</li>
 * </ul>
 * 契约 §2.0 逐字：「每个接口的响应字段按 x-visibility 矩阵在服务端裁剪；
 * 无权限字段不下发（<b>不是 null、不是空串</b>）」。
 *
 * <p>⚠️ 注意与 {@code band_visibility} 的<b>相反</b>规则：那四个布尔子键必须
 * <b>完整下发</b>（含 false），因为客户端要据此选择渲染分支，缺键会得到
 * {@code undefined} —— 与 {@code false} 是两条不同的分支。两者的分野是
 * <b>声明 vs 数据</b>：{@code band_visibility} 是声明，{@code store_scope} 是
 * 关于"哪些门店"的有限数据面。
 *
 * @param role             端角色码（契约 {@code x-roles} 的 4 个之一）
 * @param bandVisibility   四档布尔值（恒非 null；对全部端角色完整下发）
 * @param refundVisibility 退款可见性（{@code null} = 对请求者不下发）
 * @param storeScope       行级范围（{@code null} = 对请求者不下发）
 */
public record AuthMeDeclaration(String role,
                                BandFieldVisibility bandVisibility,
                                Boolean refundVisibility,
                                StoreScopeView storeScope) {

    /**
     * 紧凑构造器：断言"声明"的两项恒在。
     *
     * <p>{@code role} 与 {@code bandVisibility} 对<b>全部</b>端角色可见
     * （契约 {@code x-visible-to} 含 client），故它们为 null 是与契约冲突的形态 ——
     * 在构造期拦下，而不是等到序列化时产出一个缺键的响应。
     */
    public AuthMeDeclaration {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException(
                    "A2 声明缺 role —— 契约 AuthMeData.role 对全部 4 端可见，恒必须存在");
        }
        if (bandVisibility == null) {
            throw new IllegalArgumentException(
                    "A2 声明缺 band_visibility —— 契约的四个档位布尔值对全部 4 端可见，"
                            + "恒必须完整下发（含 false：缺键会让客户端拿到 undefined，"
                            + "而它与 false 是两条不同的渲染分支）");
        }
    }

    /**
     * 契约 {@code AuthMeData} 的逐字装配（<b>唯一</b>出站形态）。
     *
     * <p>两个按角色裁剪的项为 {@code null} 时<b>不进 Map</b>（而非进一个 null 值）。
     * 这一步与 {@code DerivedResponseBodyAdvice}（出口兜底）是<b>互补</b>关系：
     * 本方法负责"我知道不该下发"，兜底负责"万一还是漏出去了"。
     * 🛑 不能只靠兜底 —— 兜底按<b>字段名</b>匹配（{@code store_scope} 不在
     * {@code DerivedFields} 的登记表里，它属可见性声明而非派生字段），
     * 故本方法才是这两项的正确裁剪点。
     *
     * <p>用 {@link LinkedHashMap} 保序（{@code role} → {@code band_visibility}
     * → {@code refund_visibility} → {@code store_scope}），与契约 schema 的书写顺序一致，
     * 使响应体稳定可比对。🛑 不用 {@code Map.of}：它的迭代顺序不保证。
     */
    public Map<String, Object> toContractData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", role);
        data.put("band_visibility", bandVisibility.contractBandVisibility());
        if (refundVisibility != null) {
            data.put("refund_visibility", refundVisibility);
        }
        if (storeScope != null) {
            data.put("store_scope", storeScope.toContractObject());
        }
        return data;
    }

    /** 本声明实际下发的键名（供自描述与断言 —— 无需序列化即可比对契约）。 */
    public List<String> emittedKeys() {
        List<String> keys = new ArrayList<>(List.of("role", "band_visibility"));
        if (refundVisibility != null) {
            keys.add("refund_visibility");
        }
        if (storeScope != null) {
            keys.add("store_scope");
        }
        return List.copyOf(keys);
    }

    /**
     * 契约 {@code store_scope} 对象 —— {@code row_level} + {@code store_ids}。
     *
     * @param rowLevel  契约 enum 字面（{@code own_store} / {@code region} / {@code all}）
     * @param storeIds  应下发的门店 ID 列表（<b>{@code all} 时为空</b> —— 见 {@link StoreScopeResolver}）
     */
    public record StoreScopeView(String rowLevel, List<String> storeIds) {

        /** 契约 {@code row_level} 的三个合法字面（与 {@code RowScope} 同值）。 */
        private static final java.util.Set<String> ROW_LEVELS =
                java.util.Set.of("own_store", "region", "all");

        public StoreScopeView {
            if (rowLevel == null || !ROW_LEVELS.contains(rowLevel)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "store_scope.row_level 不在契约 enum 内: '" + rowLevel
                                + "'（契约 enum = [own_store, region, all]）。"
                                + "🛑 不得下发未登记的范围字面 —— 客户端按枚举分支，"
                                + "一个它不认识的值会让它落进未定义行为");
            }
            if (storeIds == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "store_scope.store_ids 不得为 null —— 全量情形应为【空数组】"
                                + "（见 StoreScopeResolver：all ⇒ [] 表示『全量、不枚举』），"
                                + "而不是该键缺失：缺失会被客户端读成『没有范围限制』，"
                                + "与 false 之于布尔键同一类误读");
            }
            storeIds = List.copyOf(storeIds);
        }

        Map<String, Object> toContractObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("row_level", rowLevel);
            out.put("store_ids", storeIds);
            return out;
        }
    }
}