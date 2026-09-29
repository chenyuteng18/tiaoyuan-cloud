package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.security.visibility.FieldGroup;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条受众角色的<b>四档布尔值</b> —— config {@code #43} 里某一个角色键的归一化结果。
 *
 * <h2>它对应契约的什么</h2>
 * 契约 {@code AuthMeData.band_visibility} 的四个键：
 * <pre>
 *   field_group_1_raw          ↔ {@link FieldGroup#RAW_DATA}
 *   field_group_2_status       ↔ {@link FieldGroup#CAPTURE_STATUS}
 *   field_group_3_gap_reason   ↔ {@link FieldGroup#GAP_REASON}
 *   field_group_4_derived      ↔ {@link FieldGroup#DERIVED_RESULT}
 * </pre>
 * 契约的键名带 {@code field_group_N} 前缀，而 {@code #43} 的键名是字段组自身的
 * {@code raw_data / capture_status / gap_reason / derived_result}（就是
 * {@link FieldGroup#code()}）。两者在<b>本类是唯一对应点</b> ——
 * 不在控制器里各拼一次（两处各拼一次必然分叉，且分叉的那一侧不报错）。
 *
 * <h2>🛑 为什么是"四档全量"而不是"只带可见的那些"</h2>
 * 契约 {@code band_visibility} 的<b>每个键都带</b>
 * {@code x-visible-to: [client, therapist, meridian, admin]} —— 即四档布尔值
 * <b>对全部端角色都下发</b>，哪怕是 {@code false} 的那些。
 * 这与"派生字段不下发"是<b>相反</b>的两条规则，极易混淆：
 * <ul>
 *   <li>{@code band_visibility} 是<b>声明</b>（"你这类身份能看哪几档"）——<b>必须完整</b>，
 *       因为客户端要据此决定"渲染哪些区块"，缺一个键它就无法区分
 *       "这档我看不到"与"这档在协议里不存在"；</li>
 *   <li>派生<b>字段本身</b>才是<b>数据</b> —— 客户侧整体不下发（契约 §3.2，非 null、非空串）。</li>
 * </ul>
 * 契约 A2 的描述逐字写着这一点：「本接口只下发「档位布尔值」，不下发任何业务字段 ——
 * 它是<b>声明</b>，不是数据。三端 UI 依此声明决定渲染分支，但字段仍由服务端裁剪
 * （X-1：声明 ≠ 授权，二者须一致）」。
 *
 * <h2>⚠️ 由此得到一个反直觉但必须的推论</h2>
 * 客户会收到 {@code field_group_4_derived: false} —— 这个键<b>不是派生结果</b>，
 * 它是一个<b>关于派生结果的布尔声明</b>。{@code DerivedFields}（S1-5 的登记表）
 * 里的字段名不含它，故出站兜底不会摘它。这不是巧合而是语义正确：
 * 摘下它会让客户端不知道"该不该渲染派生区块"，而它本身不泄漏任何判定结论。
 *
 * @param role      受众角色（本行的身份）
 * @param visibility 四档 → 可见性（<b>四档全覆盖</b>，缺一即非法）
 */
public record BandFieldVisibility(BandAudienceRole role,
                                  Map<FieldGroup, Boolean> visibility) {

    /**
     * 🛑 紧凑构造器做两件事，两件都是"缺了就静默漏档"的防线。
     *
     * <ol>
     *   <li><b>四档全覆盖校验</b>：缺任何一档都抛。缺档的后果是响应里少一个键，
     *       而"少一个键"在客户端表现为 {@code undefined} —— 它会走"这档在协议里不存在"
     *       的分支，与 {@code false} 的分支<b>不是同一条</b>。契约要求四个键恒在。</li>
     *   <li><b>不可变 + 保序</b>：用 {@link LinkedHashMap} 包一层再
     *       {@link java.util.Collections#unmodifiableMap}。
     *       🛑 这里<b>不得</b>用 {@code Map.copyOf} ——
     *       JDK 的不可变集合带有 per-JVM 随机盐，<b>迭代顺序不保证</b>
     *       （本项目 S2-8 已因此踩过一次：跨实例比对时同一份数据产出不同顺序）。
     *       本 record 的值会被序列化进响应体与断言比较，顺序稳定是必需的。</li>
     * </ol>
     */
    public BandFieldVisibility {
        if (role == null) {
            throw new IllegalArgumentException(
                    "手环可见性档位必须绑定受众角色 —— 一份不指明『这是谁』的档位无法被使用");
        }
        if (visibility == null) {
            throw new IllegalArgumentException(
                    "手环可见性档位不得为 null（受众 " + role.configKey()
                            + "）—— 未配置即拒绝，不得默认全开也不得默认全关");
        }
        Map<FieldGroup, Boolean> normalized = new EnumMap<>(FieldGroup.class);
        for (FieldGroup g : FieldGroup.all()) {
            Boolean v = visibility.get(g);
            if (v == null) {
                throw new IllegalArgumentException(
                        "手环可见性档位缺字段组 " + g.code() + "（受众 " + role.configKey() + "）—— "
                                + "四档必须全覆盖：缺一个键会让客户端拿到 undefined，"
                                + "而 undefined 与 false 在渲染分支上是两条不同的路径");
            }
            normalized.put(g, v);
        }
        // 排序为枚举声明序（①②③④），与契约 AuthMeData 的书写顺序一致。
        Map<FieldGroup, Boolean> ordered = new LinkedHashMap<>();
        for (FieldGroup g : FieldGroup.all()) {
            ordered.put(g, normalized.get(g));
        }
        visibility = java.util.Collections.unmodifiableMap(ordered);
    }

    /** 某字段组是否可见（取不到即抛 —— 构造期已断言四档全覆盖，取不到只可能是绕过构造）。 */
    public boolean canSee(FieldGroup group) {
        Boolean v = visibility.get(group);
        if (v == null) {
            throw new IllegalStateException(
                    "档位缺字段组 " + group.code() + " —— 该情形应在构造期被拦下，"
                            + "出现即说明本 record 被绕过构造实例化");
        }
        return v;
    }

    /** 四档里为真的档数（<b>仅供日志与断言</b>，不参与任何判定）。 */
    public long visibleCount() {
        return visibility.values().stream().filter(Boolean.TRUE::equals).count();
    }

    /**
     * 契约 {@code AuthMeData.band_visibility} 的<b>逐字键值对</b>。
     *
     * <p>键名 {@code field_group_1_raw} / {@code field_group_2_status} /
     * {@code field_group_3_gap_reason} / {@code field_group_4_derived} 取自契约 schema，
     * 顺序即 {@link FieldGroup#all()}（①②③④）。
     *
     * <p>🛑 用 {@link LinkedHashMap} 而<b>不是</b> {@code Map.of}：
     * ① {@code Map.of} 的迭代顺序不保证；② 它拒收 null 值（本处虽无 null，
     * 但"一套 API 里两种不可变 Map 混用"本身就是分叉的温床）。
     */
    public Map<String, Boolean> contractBandVisibility() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("field_group_1_raw", canSee(FieldGroup.RAW_DATA));
        out.put("field_group_2_status", canSee(FieldGroup.CAPTURE_STATUS));
        out.put("field_group_3_gap_reason", canSee(FieldGroup.GAP_REASON));
        out.put("field_group_4_derived", canSee(FieldGroup.DERIVED_RESULT));
        return out;
    }
}