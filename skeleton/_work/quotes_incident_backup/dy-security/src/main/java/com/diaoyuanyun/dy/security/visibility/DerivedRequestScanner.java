package com.diaoyuanyun.dy.security.visibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 入站扫描器 —— 从"请求里点名了哪些字段"解算出应拒的字段名（S1-5 验收②）。
 *
 * <h2>它为什么必须是独立于拦截器的纯逻辑类</h2>
 * 拦截器（{@code DerivedVisibilityInterceptor}）做的是"取参数、调本类、抛异常"。
 * 把解算规则单独拎出来，是为了让"规则对不对"能用<b>不起 servlet 容器</b>的单元测试钉住 ——
 * 否则每改一条匹配规则都要起一次 Spring 上下文，规则细节就没人在意了。
 *
 * <h2>三条扫描来源，覆盖三种"点名"的写法</h2>
 * <ol>
 *   <li><b>参数名</b>：{@code ?effect_verdict=…}。名字本身就是域名。</li>
 *   <li><b>参数值的词</b>：{@code ?include=effect_verdict,as_value} 或
 *       {@code ?fields=as_value}。值里按分隔符切词后逐词判定。</li>
 *   <li><b>请求体里的键名</b>：{@code POST} 的 body 里出现 {@code "as_value": …}
 *       （例如某个"补录"接口照抄了内部字段），递归扫所有键名。</li>
 * </ol>
 *
 * <h2>🛑 {@code include=verdict} 为什么也必须被拒，以及拒什么</h2>
 * 契约 §2.1 B4 逐字：「客户 token 请求含派生字段的 query（如 {@code ?include=verdict}）
 * → 403 {@code VISIBILITY_DENIED}，{@code data.denied_fields=["effect_verdict","as_value"]}」。
 * 注意它回显的是<b>字段名</b>而不是 {@code "verdict"} —— 因为调用方真正需要知道的是
 * "这个请求索取了哪些你无权看的东西"，而 {@code verdict} 只是一个<b>组别名</b>。
 * 故本类维护一张"组别名 → 契约字段名"的表（{@link #GROUP_ALIASES}），
 * 命中别名时展开成字段名回显。若直接回显 {@code "verdict"}，
 * 客户端无从判断自己究竟碰了哪个字段，只能靠猜 —— 那正是 P0-08「不得模糊报错」要防的。
 *
 * <h2>与 {@link DerivedFields} 的分工</h2>
 * {@link DerivedFields} 回答"这个<字段名>是不是派生"；本类回答
 * "这次<请求>是否点名了派生字段、点了哪些"。前者是<b>登记表</b>，后者是<b>解算</b>。
 * 两者的判据同源（本类不另立字段清单，一律问 {@link DerivedFields}），
 * 避免出现"入站按 A 清单拒、出口按 B 清单剥"这种两边都自洽、合起来漏字段的形态。
 */
public final class DerivedRequestScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 组别名 → 契约字段名（<b>逐字来自契约</b>，不是自拟）。
     *
     * <p>键是<b>已归一</b>的形态（见 {@link DerivedFields#normalize}），故
     * {@code verdict} / {@code Verdict} / {@code verdicts} / {@code effectVerdict} 都能命中
     * （各写成独立键，不做"包含即命中"，理由同 {@link DerivedFields} 类注释）。
     */
    private static final Map<String, List<String>> GROUP_ALIASES = buildAliases();

    /** 值里可能用来分隔多个字段名的分隔符（半角/全角逗号、空格、竖线、加号）。 */
    private static final String VALUE_SEPARATORS = "[,\\s，、|+]+";

    private DerivedRequestScanner() {
    }

    private static Map<String, List<String>> buildAliases() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 契约 §2.1 B4 逐字：?include=verdict → denied_fields=["effect_verdict","as_value"]
        List<String> verdictGroup = List.of("effect_verdict", "as_value");
        m.put("verdict", verdictGroup);
        m.put("verdicts", verdictGroup);
        m.put("effectverdict", verdictGroup);
        // 派生结果组整体（契约 §3.1 的 ④ = 依从性维度分/AS/effect_verdict/退款资格）
        List<String> derivedGroup = List.of(
                "a3_applicable", "a3_value", "as_value", "effect_verdict", "refund_eligibility");
        m.put("derived", derivedGroup);
        m.put("derivedresult", derivedGroup);
        m.put("bandderived", derivedGroup);
        m.put("dependent", derivedGroup);   // 兜底拼写变体（同归一结果，不额外放宽语义）
        // 缺口原因组（契约 §3.1 的 ③）—— 客户同样不可见
        m.put("gapreason", List.of("gap_reason"));
        m.put("gapreasons", List.of("gap_reason"));
        return Map.copyOf(m);
    }

    /**
     * 扫描一次请求，返回<b>应拒的字段名</b>（保留调用方原始拼写；去重后按首次出现序）。
     *
     * @param role        当前角色（来自 token；为 {@code null} 表示匿名 —— 按客户处理，见
     *                    {@link DerivedFields#isClientLike(String)}）
     * @param queryParams 查询参数（名 → 值列表）
     * @param bodyJson    请求体的原始 JSON 文本；无体时传 {@code null}
     * @return 被拒字段名；空列表表示该请求未点名任何客户不可见字段
     */
    public static List<String> scan(String role, Map<String, List<String>> queryParams, String bodyJson) {
        if (!DerivedFields.isClientLike(role)) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> denied = new ArrayList<>();

        // ① 参数名 + ② 参数值的词
        if (queryParams != null) {
            // 排序遍历：让同一请求的扫描结果稳定可比对（Map 的迭代序不保证）
            for (Map.Entry<String, List<String>> e : new TreeMap<>(queryParams).entrySet()) {
                record(denied, seen, e.getKey());
                List<String> values = e.getValue();
                if (values == null) {
                    continue;
                }
                for (String v : values) {
                    if (v == null || v.isBlank()) {
                        continue;
                    }
                    for (String token : v.split(VALUE_SEPARATORS)) {
                        record(denied, seen, token);
                    }
                }
            }
        }

        // ③ 请求体键名（递归；含数组元素里的对象）
        if (bodyJson != null && !bodyJson.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(bodyJson);
                collectJsonFieldNames(root, denied, seen);
            } catch (Exception ignored) {
                // 体不是合法 JSON：属入参校验范畴（会由 1001 处理），此处不越权判它。
                // 🛑 刻意"放过"——本类只管可见性，把非法 JSON 也报成 403 会让调用方
                // 以为是自己要了不该要的字段，排查方向被带偏。
            }
        }
        return List.copyOf(denied);
    }

    /**
     * 记一个候选名：命中"客户不可见字段"即记原名；命中"组别名"即展开成字段名记下。
     *
     * <p>匹配一律走 {@link DerivedFields}，本类不另立清单。
     */
    private static void record(List<String> denied, Set<String> seen, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        if (DerivedFields.isClientForbiddenField(candidate)) {
            addIfNew(denied, seen, candidate);
            return;
        }
        List<String> expanded = GROUP_ALIASES.get(DerivedFields.normalize(candidate));
        if (expanded != null) {
            for (String field : expanded) {
                addIfNew(denied, seen, field);
            }
        }
    }

    private static void addIfNew(List<String> denied, Set<String> seen, String name) {
        if (seen.add(DerivedFields.normalize(name))) {
            denied.add(name);
        }
    }

    /** 递归收集 JSON 里所有对象键名。 */
    private static void collectJsonFieldNames(JsonNode node, List<String> denied, Set<String> seen) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                record(denied, seen, e.getKey());
                collectJsonFieldNames(e.getValue(), denied, seen);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectJsonFieldNames(child, denied, seen);
            }
        }
    }

    /** 组别名表（只读；供回归用例逐条比对"入站拒的名字与契约回显一致"）。 */
    public static Map<String, List<String>> groupAliases() {
        return GROUP_ALIASES;
    }
}