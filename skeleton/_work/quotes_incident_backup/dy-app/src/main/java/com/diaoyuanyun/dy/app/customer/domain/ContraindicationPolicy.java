package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;

/**
 * 禁忌筛查的<b>结论推导</b> —— 从 {@code items_json} 推导 {@code result}（S2-10 域 B）。
 *
 * <h2>🛑 为什么 {@code result} 必须由服务端推导，而不能采信请求体</h2>
 * 契约 {@code ScreeningCreateRequest} 的 {@code required} 是
 * {@code [customer_id, items_json, operator_id]} —— <b>没有 {@code result}</b>；
 * 而响应 {@code ScreeningData} 里有 {@code result}。
 * 上游据此把"结论"定为<b>服务端推断量</b>，理由有三条，每条都指向一个具体的误用：
 * <pre>
 *  ① 若采信请求体的 result，则"命任一项禁忌"这条硬门禁就成了**客户端自愿申报** ——
 *     一个想跳过建档的人只要传 result=通过 即可，而 P0-01 要求的是**系统级硬阻断**；
 *  ② 门诊场景下"填写"与"判定"是两个动作：填答人可能不懂清单，
 *     而清单是**总部唯一可改**的配置（config #8 的归属层级=总部唯一，非校准项，安全底线）；
 *  ③ P0-01 逐字：「命任**一项**禁忌 → 档案置 REJECTED」—— 判据是
 *     「答项 ∩ 清单 ≠ ∅」，这是一个可机械计算的命题，不需要人来给结论。
 * </pre>
 *
 * <h2>🔴 当前清单为空 —— 本类把它做成可断言的<b>显式</b>事实，而不是静默行为</h2>
 * 配置骨架里 config #8 = {@code {"count":9,"items":[]}} ——
 * 声明了 9 项、列表却为空（自相矛盾的占位）。
 * 本类**不代拍**那 9 项是什么（安全底线的取值集属总部口径，见 Non-goals #4/#5），
 * 但必须把"清单空 ⇒ 推导结论恒为通过"这条推论摆在明处：
 * <pre>
 *   清单为空  ⇒  命中集为空（空集合的交必空）  ⇒  result = 通过
 * </pre>
 * 这在数学上自洽、在业务上是**一个必须被看见的配置缺口**：清单未落地期间，
 * <b>所有客户都会通过禁忌筛查</b>，而 REJECTED 这条终态<b>永不发生</b>。
 * 故：
 * <ul>
 *   <li>{@link #configured()} 把它变成可查询的布尔，使服务层能把它写进诊断与日志；</li>
 *   <li>{@link #describe()} 把 {@code count=9 vs items=[]} 的自相矛盾逐字带出；</li>
 *   <li>🔴 但<b>不</b>在 B1 报错：契约 B1 的 responses 声明集 = {@code 200 + 400}，
 *       <b>没有 500</b> —— 报一个未声明的码会让客户端落进未定义分支。
 *       处置是"照常推导 + 显式登记 + 告警"，而不是拒服务。</li>
 * </ul>
 *
 * <h2>清单项的形态（本类<b>不发明</b>业务取值，只定义结构）</h2>
 * 每项形如 {@code {"key":"<items_json 里的字段名>", "when": <命中值>}}，{@code when} 省略时默认 {@code true}。
 * 命中判据 = {@code items_json[key] == when}。这样：
 * <pre>
 *   {"key":"pregnancy","when":true}          —— 勾选即命中
 *   {"key":"acute","when":"重度"}            —— 取值等于"重度"才命中
 *   {"key":"risk_history"}                   —— 等价于 when=true
 * </pre>
 * 🛑 未命中任何项 ⇒ 通过；<b>缺字段</b>（客户没答该项）视同<b>未命中</b>，
 * 不视同命中 —— 依据是 P0-01 的措辞是"命任一项禁忌"，而不是"未排除任一项"。
 * 把"未答"算成命中会让一份填了一半的表变成拒诊，而那是不可逆终态。
 */
public final class ContraindicationPolicy {

    /** 清单项的字段名。 */
    private final List<String> keys;
    /** 与 {@link #keys} 同序的命中值。 */
    private final List<Object> whens;
    /** 配置声明的项数（config #8 的 {@code count}）—— 与 {@link #keys} 的规模比对即暴露占位矛盾。 */
    private final int declaredCount;

    private ContraindicationPolicy(List<String> keys, List<Object> whens, int declaredCount) {
        assertAligned(keys, whens);
        this.keys = List.copyOf(keys);
        this.whens = List.copyOf(whens);
        this.declaredCount = declaredCount;
    }

    /**
     * 从 config #8 形态的 JSON 解析（{@code {"count":9,"items":[{"key":...},...]}}）。
     *
     * <p>{@code items} 缺失 / 为空 / 非法项（无 {@code key}）一律<b>跳过并计数</b>，
     * 而不是抛错：配置缺陷不应该让端点变成 500（B1 未声明 500）。
     * 缺陷的暴露方式是 {@link #configured()} 与 {@link #describe()}，
     * 以及服务层把它带进诊断的 {@code items_json} 回显。
     *
     * @param countDeclared 配置里声明的项数（{@code count} 字段；缺失按 0）
     * @param rawItems      已解析成 {@code Map} 的 {@code items} 列表（可为 null）
     */
    public static ContraindicationPolicy of(Integer countDeclared, List<?> rawItems) {
        List<String> keys = new java.util.ArrayList<>();
        List<Object> whens = new java.util.ArrayList<>();
        if (rawItems != null) {
            for (Object o : rawItems) {
                if (!(o instanceof java.util.Map<?, ?> m)) {
                    continue;   // 非法项跳过（配置缺陷，不是请求缺陷）
                }
                Object k = m.get("key");
                if (k == null || String.valueOf(k).isBlank()) {
                    continue;
                }
                keys.add(String.valueOf(k));
                whens.add(m.containsKey("when") ? m.get("when") : Boolean.TRUE);
            }
        }
        return new ContraindicationPolicy(keys, whens, countDeclared == null ? 0 : countDeclared);
    }

    /** 清单<b>完全未配置</b>（{@code items} 为空）—— 此时推导结论恒为"通过"。 */
    public boolean configured() {
        return !keys.isEmpty();
    }

    /** 配置声明的项数。 */
    public int declaredCount() {
        return declaredCount;
    }

    /** 实际解析出的清单项数。 */
    public int actualCount() {
        return keys.size();
    }

    /** 清单项名（只读视图）。 */
    public List<String> keys() {
        return keys;
    }

    /**
     * 推导结论：遍历清单，收集命中的项名；命中集非空 ⇒ {@code 不通过}。
     *
     * @param itemsJson 筛查填答（契约 {@code ScreeningCreateRequest.items_json}）
     * @return 命中项名（保序、去重）；空列表 = 未命中任何禁忌
     */
    public List<String> hitKeys(java.util.Map<String, Object> itemsJson) {
        List<String> hits = new java.util.ArrayList<>();
        if (itemsJson == null || itemsJson.isEmpty()) {
            return hits;
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (!itemsJson.containsKey(key)) {
                continue;   // 未答 ≠ 命中（见类注释）
            }
            if (java.util.Objects.equals(itemsJson.get(key), whens.get(i))) {
                hits.add(key);
            }
        }
        return hits;
    }

    /**
     * 推导结论（B1 的唯一落点）。
     *
     * <p>🛑 清单为空时返回 {@link ScreeningResult#PASSED} —— 这不是"我们判断他没问题"，
     * 而是"清单里一项都没有，故无法命中任何项"。两者的差别在于<strong>责任归属</strong>：
     * 前者是一次安全结论，后者是一个配置状态。故调用方<b>必须</b>同时读
     * {@link #configured()} 并把它带进诊断。
     */
    public ScreeningResult derive(java.util.Map<String, Object> itemsJson) {
        return hitKeys(itemsJson).isEmpty() ? ScreeningResult.PASSED : ScreeningResult.REJECTED;
    }

    /** 空清单（未经配置的兜底实例）。 */
    public static ContraindicationPolicy unconfigured() {
        return new ContraindicationPolicy(List.of(), List.of(), 0);
    }

    /**
     * 自描述（供端点自描述与断言）—— 把"清单为空"这条配置缺口带成可读事实。
     */
    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("config_slot", "cfg:safety.contraindication_list（config #8 · 归属层级=总部唯一，非校准项）");
        m.put("declared_count", declaredCount);
        m.put("actual_item_count", actualCount());
        m.put("items", keys);
        m.put("configured", configured());
        m.put("derive_rule",
                "命任【一项】禁忌 ⇒ result=不通过（P0-01 逐字「命任一项禁忌 → 档案置 REJECTED」）；"
                        + "未命中任何项 ⇒ 通过。缺字段（未答）视同**未命中**，"
                        + "不视同命中 —— P0-01 的措辞是『命任一项禁忌』而非『未排除任一项』，"
                        + "把未答算成命中会让一份填了一半的表变成不可逆的拒诊");
        m.put("why_server_side",
                "契约 ScreeningCreateRequest 的 required = [customer_id, items_json, operator_id]，"
                        + "【无 result】—— 结论是服务端推断量。若采信请求体，"
                        + "『命任一项禁忌』这条硬门禁就成了客户端自愿申报，"
                        + "而 P0-01 要求系统级硬阻断");
        if (!configured()) {
            m.put("⚠️_configuration_gap",
                    "禁忌清单【当前为空】: config #8 的骨架值是 {\"count\":9,\"items\":[]} —— "
                            + "声明 9 项、列表为空（自相矛盾的占位）。故推导结论【恒为通过】，"
                            + "REJECTED 这条终态在清单落地前永不发生。"
                            + "本类不代拍那 9 项是什么（安全底线的取值集属总部口径，"
                            + "见 Non-goals #4/#5），只把推论摆在明处。"
                            + "🔴 但不在 B1 报错: 契约 B1 的 responses 声明集 = 200 + 400，"
                            + "【无 500】—— 报未声明的码会让客户端落进未定义分支");
        }
        return m;
    }

    /** 构造期专用：解析后的清单规模必须与命中值规模一致（防两列表错位）。 */
    static void assertAligned(List<String> keys, List<Object> whens) {
        if (keys.size() != whens.size()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "禁忌清单的字段名与命中值两列表长度不一致: " + keys.size() + " vs " + whens.size()
                            + " —— 错位会让命中的项名与被判定的谓词对不上，"
                            + "而那种错误不报错，只让一次拒诊挂在一个无关的项名上");
        }
    }
}