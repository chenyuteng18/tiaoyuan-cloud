package com.diaoyuanyun.dy.security.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入站扫描器 · 单元测试（S1-5 验收② 的"三种点名写法"覆盖）。
 *
 * <h2>它为什么能不起容器单测</h2>
 * {@link DerivedRequestScanner} 被刻意做成<b>纯逻辑</b>类（类注释）：拦截器只负责
 * "取参数 → 调解算 → 抛异常"。故"规则对不对"在这里钉死，
 * 无需为每一条匹配规则起一次 Spring 容器 —— 否则规则细节就没人在意了。
 *
 * <h2>断言锚定契约</h2>
 * 最关键的一条来自契约 §2.1 B4 逐字：
 * {@code ?include=verdict} → 403，{@code data.denied_fields=["effect_verdict","as_value"]}。
 * 注意回显的是<b>字段名</b>而不是组别名 {@code "verdict"} —— 本类的核心用例即守这一点。
 */
class DerivedRequestScannerTest {

    private static Map<String, List<String>> q(String name, String... values) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(name, List.of(values));
        return m;
    }

    // ==================================================================
    // 一、契约 B4：include=verdict 必须回显字段名，不是组名
    // ==================================================================

    @Test
    @DisplayName("🛑 契约 §2.1 B4 逐字：?include=verdict → denied_fields=[effect_verdict, as_value]")
    void include_verdict_expands_to_the_two_contract_field_names() {
        List<String> denied = DerivedRequestScanner.scan("client",
                q("include", "verdict"), null);

        // 契约逐字给出的是这两个字段名，不是 "verdict"。
        assertEquals(List.of("effect_verdict", "as_value"), denied,
                "回显了组别名而不是字段名 —— 客户端将无从判断自己碰了哪个字段，只能靠猜"
                        + "（P0-08『不得模糊报错』要防的正是这个）");
    }

    @Test
    @DisplayName("组别名是被【展开】的：derived / gap_reason 组都展开成字段名")
    void group_aliases_are_expanded_not_echoed() {
        List<String> derived = DerivedRequestScanner.scan("client", q("include", "derived"), null);
        assertEquals(List.of("a3_applicable", "a3_value", "as_value",
                        "effect_verdict", "refund_eligibility"), derived,
                "derived 组应展开为 ④ 的五个契约字段名");

        assertEquals(List.of("gap_reason"),
                DerivedRequestScanner.scan("client", q("fields", "gap_reason"), null),
                "gap_reason 组应展开为 ③ 的字段名");
    }

    @Test
    @DisplayName("组别名表逐条可核对：键为归一形态，值为契约字段名")
    void group_alias_table_is_inspectable() {
        Map<String, List<String>> aliases = DerivedRequestScanner.groupAliases();
        assertEquals(List.of("effect_verdict", "as_value"), aliases.get("verdict"),
                "verdict 组的展开值必须与契约 B4 逐字一致");
        assertEquals(List.of("effect_verdict", "as_value"), aliases.get("effectverdict"),
                "effectVerdict 归一后与 verdict 同组（键必须已是归一形态）");
        assertTrue(aliases.containsKey("derived"));
        assertTrue(aliases.containsKey("derivedresult"));
        assertTrue(aliases.containsKey("bandderived"));
        assertTrue(aliases.containsKey("gapreason"));
        assertTrue(aliases.containsKey("gapreasons"));

        // 键必须全部是【已归一】形态 —— 否则大小写一变就漏
        for (String key : aliases.keySet()) {
            assertEquals(DerivedFields.normalize(key), key,
                    "组别名键 " + key + " 不是归一形态 —— 大小写/分隔符一变就会漏掉");
        }
        // 值必须全部是契约字段名（且属客户不可见）
        for (List<String> fields : aliases.values()) {
            for (String f : fields) {
                assertTrue(DerivedFields.isClientForbiddenField(f),
                        "组别名展开出了非客户不可见字段 " + f + " —— 会让入站放行不该放的");
            }
        }
    }

    // ==================================================================
    // 二、三种"点名"写法
    // ==================================================================

    @Test
    @DisplayName("写法①参数名：?as_value=… —— 名字本身就是域名")
    void scan_source_one_parameter_name() {
        assertEquals(List.of("as_value"),
                DerivedRequestScanner.scan("client", q("as_value", "1"), null));
        assertEquals(List.of("effect_verdict"),
                DerivedRequestScanner.scan("client", q("effect_verdict", "E1显著改善"), null));
    }

    @Test
    @DisplayName("写法②参数值的词：?include=a,b|c d —— 按分隔符切词逐词判定")
    void scan_source_two_words_inside_a_value() {
        // 半角逗号
        assertEquals(List.of("as_value", "effect_verdict"),
                DerivedRequestScanner.scan("client", q("include", "as_value,effect_verdict"), null));
        // 竖线 / 加号 / 全角逗号 / 顿号 / 空格 都是合法分隔符
        for (String sep : List.of("|", "+", "，", "、", " ")) {
            assertEquals(List.of("as_value", "effect_verdict"),
                    DerivedRequestScanner.scan("client",
                            q("include", "as_value" + sep + "effect_verdict"), null),
                    "分隔符 " + sep + " 未被切词 —— 客户端会以为这样写就能绕过");
        }
    }

    @Test
    @DisplayName("写法③请求体键名：POST body 里出现 \"as_value\": … （含嵌套与数组）")
    void scan_source_three_json_body_keys() {
        assertEquals(List.of("as_value"),
                DerivedRequestScanner.scan("client", null, "{\"as_value\": 1}"),
                "顶层键名未被扫到");

        // 嵌套对象 + 数组里的对象都要递归扫到
        String nested = """
                {"record": {"metrics": [{"mcid": 1}, {"confidence": 0.9}]}}
                """;
        assertEquals(List.of("mcid", "confidence"),
                DerivedRequestScanner.scan("client", null, nested),
                "嵌套对象 / 数组元素的键名未被递归扫到 —— 换一层包装就能带进去");

        // 参数名与体键名同时命中：合并去重，保首次出现序
        List<String> both = DerivedRequestScanner.scan("client",
                q("include", "as_value"), "{\"effect_verdict\": \"E1显著改善\"}");
        assertEquals(List.of("as_value", "effect_verdict"), both,
                "参数与体两处命中应合并去重");
    }

    @Test
    @DisplayName("非法 JSON 体：刻意放过（属 1001 范畴，不越权报 403）")
    void malformed_json_body_is_deliberately_ignored() {
        // 把非法 JSON 报成 403 会让调用方以为是自己要了不该要的字段，排查方向被带偏。
        assertTrue(DerivedRequestScanner.scan("client", null, "{not json").isEmpty(),
                "非法 JSON 被报成了可见性拒绝 —— 应留给 1001 参数校验处理");
        assertTrue(DerivedRequestScanner.scan("client", null, "").isEmpty());
        assertTrue(DerivedRequestScanner.scan("client", null, "   ").isEmpty());
        assertTrue(DerivedRequestScanner.scan("client", null, null).isEmpty());
    }

    // ==================================================================
    // 三、角色：staff 不住拦，匿名按客户
    // ==================================================================

    @Test
    @DisplayName("staff 扫描恒返回空（契约矩阵 ✓ 档，拒它属拦错东西）")
    void staff_scans_are_always_empty() {
        Map<String, List<String>> params = q("include", "derived,verdict");
        String body = "{\"as_value\": 1, \"mcid\": 2}";
        for (String staff : List.of("therapist", "meridian", "manager", "area", "hq", "SUPER_ADMIN")) {
            assertTrue(DerivedRequestScanner.scan(staff, params, body).isEmpty(),
                    staff + " 的派生字段扫描非空 —— 契约里它是 ✓ 档，不是客户");
        }
    }

    @Test
    @DisplayName("匿名扫描与客户同判（fail-closed）")
    void anonymous_scans_like_a_client() {
        assertEquals(List.of("effect_verdict", "as_value"),
                DerivedRequestScanner.scan(null, q("include", "verdict"), null),
                "匿名请求未按客户拦截 —— 去掉 token 即可绕过");
        assertEquals(List.of("as_value"),
                DerivedRequestScanner.scan(null, q("as_value", "1"), null));
    }

    // ==================================================================
    // 四、确定性与去重
    // ==================================================================

    @Test
    @DisplayName("同一请求无论参数迭代顺序如何，结果稳定可比对（排序遍历）")
    void results_are_deterministic_regardless_of_map_order() {
        Map<String, List<String>> a = new LinkedHashMap<>();
        a.put("include", List.of("effect_verdict"));
        a.put("fields", List.of("as_value"));
        Map<String, List<String>> b = new LinkedHashMap<>();
        b.put("fields", List.of("as_value"));   // 插入顺序与 a 相反
        b.put("include", List.of("effect_verdict"));

        assertEquals(DerivedRequestScanner.scan("client", a, null),
                DerivedRequestScanner.scan("client", b, null),
                "参数插入顺序影响了扫描结果 —— 说明遍历未排序，响应体不可稳定比对");
    }

    @Test
    @DisplayName("重复点名只回显一次，保留首次出现的拼写")
    void duplicates_are_collapsed_preserving_first_spelling() {
        // outcome: AS-Value 归一后与 as_value 相同 → 只留首次（AS-Value）
        List<String> denied = DerivedRequestScanner.scan("client",
                q("fields", "AS-Value", "as_value"), null);
        assertEquals(List.of("AS-Value"), denied,
                "同一字段的多种拼写应去重并保留首次拼写（客户端靠它定位自己发的参数）");

        // 组别名与字段名同时出现：verdict 展开含 effect_verdict，
        // 后面再来 effect_verdict 不应重复
        List<String> mixed = DerivedRequestScanner.scan("client",
                q("include", "verdict,effect_verdict"), null);
        assertEquals(List.of("effect_verdict", "as_value"), mixed,
                "组展开与直接点名重叠时应去重");
    }

    @Test
    @DisplayName("未点名任何客户不可见字段：返回空（不误拒）")
    void clean_requests_are_not_denied() {
        assertTrue(DerivedRequestScanner.scan("client",
                q("include", "step_count,sleep_duration"), null).isEmpty(),
                "普通字段的 include 被误判为派生索取 —— 误拦会逼着维护者放宽这张表");
        assertTrue(DerivedRequestScanner.scan("client", null,
                "{\"note\": \"客户备注\"}").isEmpty());
        assertTrue(DerivedRequestScanner.scan("client", Map.of(), null).isEmpty());
    }

    @Test
    @DisplayName("参数值为 null / 空白：跳过而非报错")
    void null_and_blank_values_are_skipped() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("include", java.util.Arrays.asList(null, "  ", "as_value"));
        assertEquals(List.of("as_value"), DerivedRequestScanner.scan("client", params, null),
                "值列表里的 null / 空白项应被跳过，只判有效词");

        Map<String, List<String>> nullValueList = new LinkedHashMap<>();
        nullValueList.put("include", null);
        assertTrue(DerivedRequestScanner.scan("client", nullValueList, null).isEmpty(),
                "值为 null 的参数项不得导致 NPE");
    }
}