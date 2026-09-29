package com.diaoyuanyun.dy.security.visibility;

import com.diaoyuanyun.dy.common.exception.VisibilityDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生字段登记表 + 可见性解算器 · 单元测试（S1-5 验收② 的规则层）。
 *
 * <h2>本类证明什么 / 不证明什么</h2>
 * <ul>
 *   <li><b>证明</b>：字段名判定规则、角色判定规则、被拒字段的解算与回显形态 ——
 *       全部<b>不启 servlet 容器</b>即可钉死。规则一旦被改坏，这里立刻红。</li>
 *   <li><b>不证明</b>：规则被挂上了。那由 {@code DerivedVisibilityE2ETest} 用真请求断言。
 *       两者是两件事（同 {@code OrgLevelTest} 的分工说明），都要有。</li>
 * </ul>
 *
 * <h2>断言锚定外部真相源</h2>
 * 断言里的每个字段名 / 角色码都能追到契约：
 * <ul>
 *   <li>④ 派生结果 5 字段 → {@code components.schemas.BandDerivedData} 的 properties；</li>
 *   <li>{@code denied_fields=["effect_verdict","as_value"]} → 契约 §2.1 B4 逐字；</li>
 *   <li>角色码 → 契约根级 {@code x-roles}；</li>
 *   <li>四档字段组 → 契约根级 {@code x-field-groups}（§3.1）。</li>
 * </ul>
 */
class DerivedFieldsTest {

    // ==================================================================
    // 一、登记表：范围正确且最小
    // ==================================================================

    @Test
    @DisplayName("④ 派生字段登记表与契约字段名逐字一致 —— 不多不少")
    void contract_registry_matches_the_openapi_field_names() {
        // 契约 BandDerivedData（E4 出参）的五个属性 + CustomerDetailData 中同属
        // x-field-group: derived_result 的字段 + ★3 判定链落库列里对客户不可见的派生结论。
        Set<String> contractBandDerived = Set.of(
                "a3_applicable", "a3_value", "as_value", "effect_verdict", "refund_eligibility");
        Set<String> judgmentChainDerived = Set.of(
                "improvement_rate", "adherence_state", "adherence_score",
                "mcid", "confidence", "evidence_snapshot");

        Set<String> registry = DerivedFields.derivedFieldNames();
        assertTrue(registry.containsAll(contractBandDerived),
                "登记表漏掉了契约 BandDerivedData 的字段 —— 少了哪一个，哪个就能被客户带出去。"
                        + " 缺: " + diff(contractBandDerived, registry));
        assertTrue(registry.containsAll(judgmentChainDerived),
                "登记表漏掉了判定链落库列里的派生结论。缺: " + diff(judgmentChainDerived, registry));

        // 反向：不得多登记（多登记的失效模式是"误拦"→ 有人为了让接口可用而放宽这张表）
        Set<String> expected = new LinkedHashSet<>(contractBandDerived);
        expected.addAll(judgmentChainDerived);
        assertEquals(expected, registry,
                "登记表出现了契约之外的字段名 —— 误拦比漏拦更危险："
                        + "它会逼着维护者去放宽这一整张表。多出: " + diff(registry, expected));
    }

    @Test
    @DisplayName("🛑 匹配是『归一 + 精确相等』，绝不做『包含即命中』—— as_value_ops 反例")
    void matching_is_exact_after_normalization_never_substring() {
        // 类注释点名的反例：as_value_ops 是运营口径的另一个字段，
        // 若用"包含 as_value 即命中"，它会被误判为派生字段而遭到误拦。
        assertFalse(DerivedFields.isDerivedField("as_value_ops"),
                "as_value_ops 被判成派生字段 —— 说明匹配退化成了『包含即命中』");
        assertFalse(DerivedFields.isClientForbiddenField("as_value_ops"),
                "as_value_ops 被当成客户不可见字段 —— 同上，这是误拦");
        // 另一个方向的假阳性：effect_verdict_snapshot 同时包含两个登记名，仍必须为否。
        assertFalse(DerivedFields.isDerivedField("effect_verdict_snapshot"),
                "effect_verdict_snapshot 被判成派生字段 —— 子串匹配的典型假阳性");

        // 正向：真正的字段名，各种分隔/大小写形态都必须命中
        for (String spelling : List.of("as_value", "asValue", "AS-Value", "AS_VALUE", " as_value ")) {
            assertTrue(DerivedFields.isDerivedField(spelling),
                    "合法拼写 " + spelling + " 未被识别为派生字段 —— 换个命名风格就能把字段带出去");
        }
    }

    @Test
    @DisplayName("别名覆盖 camelCase 与合规词表拼写（as_refund / a3Value / effectVerdict …）")
    void aliases_cover_camel_case_and_compliance_wordlist_spellings() {
        // 合规词表 scan3_derived.words 用 AS_refund（大写域前缀），契约字段名是 as_value。
        assertTrue(DerivedFields.isDerivedField("AS_refund"),
                "合规词表拼写 AS_refund 未命中 as_value 别名");
        assertTrue(DerivedFields.isDerivedField("as_refund"),
                "别名匹配必须大小写无关（归一后比较）");

        Map<String, String> aliasExpectations = Map.of(
                "a3Value", "a3_value",
                "a3Applicable", "a3_applicable",
                "asValue", "as_value",
                "effectVerdict", "effect_verdict",
                "refundEligibility", "refund_eligibility",
                "improvementRate", "improvement_rate",
                "adherenceState", "adherence_state",
                "adherenceScore", "adherence_score");
        aliasExpectations.forEach((alias, canonical) -> assertTrue(
                DerivedFields.isDerivedField(alias),
                "camelCase 别名 " + alias + "（↔ " + canonical + "）未被识别 —— "
                        + "手工 Map 装配 / 第三方序列化配置产出的 camelCase 会被漏掉"));
    }

    @Test
    @DisplayName("归一规则：去非字母数字 + 小写（跨分隔符与大小写）")
    void normalization_strips_separators_and_lowercases() {
        assertEquals("a3value", DerivedFields.normalize("a3_value"));
        assertEquals("a3value", DerivedFields.normalize("A3-Value"));
        assertEquals("a3value", DerivedFields.normalize("  a3.value  "));
        assertEquals("", DerivedFields.normalize(null),
                "null 归一到空串（空串在判定里恒为否 —— fail-closed 的下界）");
        assertEquals("", DerivedFields.normalize("---"),
                "全分隔符归一到空串，不得抛错");
    }

    @Test
    @DisplayName("null / 空白 一律判否且不抛 —— 判定入口不得变成 NPE 源")
    void null_and_blank_are_never_derived() {
        assertFalse(DerivedFields.isDerivedField(null));
        assertFalse(DerivedFields.isDerivedField(""));
        assertFalse(DerivedFields.isDerivedField("   "));
        assertFalse(DerivedFields.isClientForbiddenField(null));
        assertFalse(DerivedFields.isClientForbiddenField(""));
        assertTrue(DerivedFields.classify(null).isEmpty(),
                "classify 对 null 应返回空 Optional（无判定），而不是 Optional.of(false)");
        assertTrue(DerivedFields.classify("  ").isEmpty());
        assertTrue(DerivedFields.classify("step_count").isPresent(),
                "非空名字必须有判定");
    }

    // ==================================================================
    // 二、③ 与 ④ 分开登记（语义不同，拒绝方式相同）
    // ==================================================================

    @Test
    @DisplayName("gap_reason 属③：对客户不可见，但【不是】派生结论")
    void gap_reason_is_client_forbidden_but_not_derived() {
        // 分开登记的理由：③ 是缺口【分类】，④ 是判定【结论】。
        // PRD §2.4 X-1 的全部论证建立在"派生【结论】不对客户下发"上。
        assertTrue(DerivedFields.isClientForbiddenField("gap_reason"),
                "gap_reason（③）对客户必须不可见");
        assertTrue(DerivedFields.isClientForbiddenField("gap_reasons"),
                "复数形态 gap_reasons 同样不可见");
        assertFalse(DerivedFields.isDerivedField("gap_reason"),
                "gap_reason 被并进了④ —— 那会把『派生结论』这个概念稀释掉，"
                        + "而 X-1 的论证全靠它");
        assertFalse(DerivedFields.isDerivedField("gap_reasons"));

        assertTrue(DerivedFields.clientForbiddenNonDerivedNames().contains("gap_reason"),
                "③ 必须由独立集合承载，而不是靠④兜住");
        assertFalse(DerivedFields.derivedFieldNames().contains("gap_reason"));
    }

    @Test
    @DisplayName("derivedAndAdjacent = ③ ∪ ④（供『客户一律不可见』的粗粒度判定）")
    void derived_and_adjacent_is_the_union_of_both_sets() {
        Set<String> union = DerivedFields.derivedAndAdjacent();
        Set<String> expectedUnion = new LinkedHashSet<>(DerivedFields.derivedFieldNames());
        expectedUnion.addAll(DerivedFields.clientForbiddenNonDerivedNames());
        assertEquals(expectedUnion, union,
                "合并视图必须恰为 ③ ∪ ④ —— 少了会让入站放行，多了会造成误拦");

        // 合并视图里的每一项都必须能在"客户不可见"上成立
        for (String name : union) {
            assertTrue(DerivedFields.isClientForbiddenField(name),
                    name + " 在合并视图里，却不被 isClientForbiddenField 判为不可见 —— 两处判据分叉");
        }
        // 别名不参与合并视图（视图是"字段名"的，不是"拼写"的）
        assertFalse(union.contains("as_refund"),
                "合并视图里不应出现别名拼写（视图登记的是契约字段名）");
    }

    @Test
    @DisplayName("四档字段组：只有 ④ 派生结果 isDerived —— 其余三档不是")
    void field_group_marks_only_derived_result() {
        assertEquals(4, FieldGroup.all().size(), "契约 x-field-groups 固定四档");
        assertEquals(List.of("raw_data", "capture_status", "gap_reason", "derived_result"),
                FieldGroup.all().stream().map(FieldGroup::code).toList(),
                "四档的顺序与码必须与契约 x-field-groups 一致");

        assertTrue(FieldGroup.DERIVED_RESULT.isDerived());
        assertFalse(FieldGroup.RAW_DATA.isDerived(),
                "① 手环原始数据对客户可见 —— 误标为派生会让客户看不到自己的数据");
        assertFalse(FieldGroup.CAPTURE_STATUS.isDerived(), "② 采集状态对客户可见");
        assertFalse(FieldGroup.GAP_REASON.isDerived(),
                "③ 不可见但不属派生档（它是缺口分类，不是判定结论）");

        assertThrows(IllegalArgumentException.class, () -> FieldGroup.of("derived"),
                "组别名 derived 不是字段组码 —— 未登记必须抛错（fail-closed）");
        assertThrows(IllegalArgumentException.class, () -> FieldGroup.of(null));
        assertEquals(FieldGroup.DERIVED_RESULT, FieldGroup.of("derived_result"));
    }

    // ==================================================================
    // 三、可见性登记表不可被就地改写
    // ==================================================================

    @Test
    @DisplayName("登记表是只读视图 —— 调用方拿不到写句柄（防止运行时就地放宽）")
    void registry_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> DerivedFields.derivedFieldNames().add("bogus"),
                "derivedFieldNames() 返回了可写集合 —— 运行时就地放宽这张表将成为可能");
        assertThrows(UnsupportedOperationException.class,
                () -> DerivedFields.clientForbiddenNonDerivedNames().add("bogus"));
        assertThrows(UnsupportedOperationException.class,
                () -> DerivedFields.aliases().add("bogus"));
        // 取两次必须是同一批内容（Set.copyOf 的视图应稳定）
        assertEquals(DerivedFields.derivedFieldNames(), DerivedFields.derivedFieldNames());
    }

    // ==================================================================
    // 四、角色判定：匿名按客户（fail-closed）
    // ==================================================================

    @Test
    @DisplayName("🛑 匿名（角色为 null）按客户处理 —— 否则去掉 token 反而绕过拦截")
    void anonymous_is_treated_as_client() {
        // 论证见类注释 isClientLike：匿名若被当作"未知角色"跳过派生拦截，
        // 就等于"把 token 去掉"成了绕过派生字段入站拦截的途径。
        assertTrue(DerivedFields.isClientLike(null),
                "匿名未被按客户处理 —— 去掉 Authorization 头即可绕过派生字段拦截");
        assertTrue(DerivedFields.isClientLike(""),
                "空角色同样必须 fail-closed 按客户处理");
        assertTrue(DerivedFields.isClientLike("   "));
        assertTrue(DerivedFields.isClientLike("client"), "契约客户角色码 client 必须命中");
    }

    @Test
    @DisplayName("staff 角色（含大写遗留别名）一律【不】按客户处理 —— 不得拦错东西")
    void staff_roles_are_not_client_like() {
        // 契约矩阵：调理师/经络师对 ④ 是 ✓ 档（"可见但不得作对客户不利依据"），
        // 那是业务使用纪律，不属接口可见性 —— 在这里拦它们会拦错东西。
        for (String staff : List.of("therapist", "meridian", "manager", "area", "hq",
                "SUPER_ADMIN", "REGION_ADMIN", "STORE_STAFF", "TENANT_ADMIN")) {
            assertFalse(DerivedFields.isClientLike(staff),
                    staff + " 被按客户处理 —— 会让 staff 的正常读取被 403 拦掉");
        }
    }

    @Test
    @DisplayName("未登记的端角色 fail-closed 抛错（绝不回落默认端角色）")
    void unregistered_visibility_role_fails_closed() {
        assertThrows(IllegalArgumentException.class, () -> VisibilityRole.of("nurse"),
                "未登记角色必须抛错 —— 回落会让『角色码拼错』静默变成『这个角色什么都看不到』");
        assertThrows(IllegalArgumentException.class, () -> VisibilityRole.of(null));
        assertTrue(VisibilityRole.tryOf("nurse").isEmpty(), "宽容解析应返回空");
        assertTrue(VisibilityRole.tryOf(null).isEmpty());
        // 🛑 store_customer_service 在契约里 token-role 为 null（无端），故【不】登记 ——
        // 它出现在这里是为了把"未登记即抛"这条规则也覆盖到这个角色上。
        // 其可见性口径分叉（P0-19 待裁定）不在本类范围内。
        assertTrue(VisibilityRole.tryOf("store_customer_service").isEmpty(),
                "store_customer_service 的 token-role 在契约里为 null，不应被登记为任何端角色");
    }

    @Test
    @DisplayName("VisibilityRole 与契约 x-roles 逐条一致（码 / 端 / token 角色）")
    void visibility_role_matches_contract_x_roles() {
        assertEquals("client", VisibilityRole.CLIENT.contractCode());
        assertEquals("mp", VisibilityRole.CLIENT.end());
        assertEquals("therapist", VisibilityRole.THERAPIST.contractCode());
        assertEquals("app", VisibilityRole.THERAPIST.end());
        assertEquals("meridian", VisibilityRole.MERIDIAN.contractCode());
        assertEquals("app", VisibilityRole.MERIDIAN.end());
        assertEquals("admin", VisibilityRole.ADMIN.contractCode());
        assertEquals("web", VisibilityRole.ADMIN.end());
        assertTrue(VisibilityRole.ADMIN.tokenRoles().containsAll(List.of("manager", "area", "hq")),
                "契约把 manager/area/hq 三个 token 角色折叠成一个端角色 admin");

        assertEquals(VisibilityRole.ADMIN, VisibilityRole.of("hq"));
        assertEquals(VisibilityRole.ADMIN, VisibilityRole.of("SUPER_ADMIN"),
                "大写遗留码（T-11）应登记为 admin 的别名");
        assertEquals(VisibilityRole.CLIENT, VisibilityRole.of("client"));
    }

    // ==================================================================
    // 五、解算：被拒字段的集合、顺序、回显
    // ==================================================================

    @Test
    @DisplayName("staff 索取派生字段：不拒（空列表）")
    void staff_requests_are_never_denied() {
        for (String staff : List.of("therapist", "meridian", "manager", "area", "hq", "SUPER_ADMIN")) {
            assertTrue(DerivedFields.deniedDerivedFields(staff,
                            List.of("effect_verdict", "as_value", "refund_eligibility")).isEmpty(),
                    staff + " 索取派生字段被拒 —— 契约矩阵里它是 ✓ 档，拒它属于拦错东西");
        }
    }

    @Test
    @DisplayName("客户索取：命中即拒，回显【原始拼写】并去重保序")
    void client_denials_echo_original_spelling_and_dedupe_preserving_order() {
        List<String> denied = DerivedFields.deniedDerivedFields("client",
                List.of("as_value", "AS-Value", "effect_verdict", "step_count", "as_value"));
        // step_count 不属派生 → 不出现；AS-Value 与 as_value 归一相同 → 只留首次拼写。
        assertEquals(List.of("as_value", "effect_verdict"), denied,
                "回显必须保留首次出现的原始拼写（客户端要靠它定位自己发的参数），"
                        + "且按首次出现序去重（同一请求的响应体必须稳定可比对）");
    }

    @Test
    @DisplayName("匿名索取：与客户同判（fail-closed）")
    void anonymous_requests_are_denied_like_a_client() {
        assertFalse(DerivedFields.deniedDerivedFields(null, List.of("as_value")).isEmpty(),
                "匿名请求索取派生字段未被拒 —— 去掉 token 即可绕过");
        assertEquals(List.of("gap_reason"),
                DerivedFields.deniedDerivedFields(null, List.of("gap_reason")));
    }

    @Test
    @DisplayName("空 / null 字段列表恒返回空 —— 不抛、不误拒")
    void empty_request_is_never_denied() {
        assertTrue(DerivedFields.deniedDerivedFields("client", List.of()).isEmpty());
        assertTrue(DerivedFields.deniedDerivedFields("client", null).isEmpty());
        assertTrue(DerivedFields.deniedDerivedFields(null, null).isEmpty());
        // 列表里混入 null / 空白项应被跳过，而不是判成命中
        assertTrue(DerivedFields.deniedDerivedFields("client",
                asListWithNulls()).isEmpty(),
                "null / 空白候选名不得被判成命中（否则会出现一条无法诊断的 403）");
    }

    private static List<String> asListWithNulls() {
        List<String> l = new ArrayList<>();
        l.add(null);
        l.add("   ");
        return l;
    }

    @Test
    @DisplayName("🛑 assertNoDerivedRequest：抛出的 403 必带 denied_fields（不得模糊报错）")
    void assertion_throws_a_403_carrying_the_denied_fields() {
        VisibilityDeniedException ex = assertThrows(VisibilityDeniedException.class,
                () -> DerivedFields.assertNoDerivedRequest("client",
                        List.of("effect_verdict", "as_value")),
                "客户索取派生字段必须抛可见性拒绝");

        assertEquals(2001, ex.getCode(),
                "可见性档位不足 = VISIBILITY_DENIED(2001)，不是 1001 参数错 —— "
                        + "契约 §3.2 逐字要求 403 VISIBILITY_DENIED");
        assertEquals(403, com.diaoyuanyun.dy.common.result.ErrorCode
                        .of(ex.getCode()).getHttpStatus(),
                "2001 的 HTTP 状态必须是 403（契约 §3.2 硬阻断）");
        assertEquals(List.of("effect_verdict", "as_value"), ex.getDeniedFields());
        assertEquals(Map.of("denied_fields", List.of("effect_verdict", "as_value")), ex.getData(),
                "信封 data 必须恰为 {denied_fields:[…]} —— 客户端的重试要照它去掉参数");
        assertTrue(ex.getDevMessage().contains("effect_verdict"),
                "开发期信息应点名被拒字段与依据（契约 §1.3 不得模糊报错）");
    }

    @Test
    @DisplayName("assertNoDerivedRequest：未点名派生字段 / staff 一律放行")
    void assertion_passes_when_nothing_derived_is_requested() {
        // 不抛 = 通过
        DerivedFields.assertNoDerivedRequest("client", List.of("step_count", "sync_date"));
        DerivedFields.assertNoDerivedRequest("client", List.of());
        DerivedFields.assertNoDerivedRequest("therapist", List.of("effect_verdict"));
        DerivedFields.assertNoDerivedRequest("hq", List.of("as_value"));
    }

    @Test
    @DisplayName("🛑 不带字段名的可见性拒绝在构造期就炸 —— 不存在『不可诊断的 403』")
    void a_fieldless_denial_cannot_even_be_constructed() {
        // 这条守的是 VisibilityDeniedException 自己的契约：denied_fields 不得为空。
        // 若没有它，"403 但不说是哪个字段"就会成为一种可能的返回值。
        assertThrows(IllegalArgumentException.class,
                () -> new VisibilityDeniedException(List.of(), "无权"),
                "空 denied_fields 被接受了 —— 『无权』式的模糊 403 将可被构造出来");
        // ⚠️ 传 null 时必须是 IllegalArgumentException【而不是 NPE】：
        // 初版把 List.copyOf(deniedFields) 写进了 super(...) 的实参，而 super 是第一条语句，
        // 于是 null 在守卫语句之前就抛了 NPE —— "我没有字段可报"这条业务错误
        // 被伪装成一个空指针，调用方看到的是一处 500 而不是一条可诊断的信息。
        IllegalArgumentException nullCase = assertThrows(IllegalArgumentException.class,
                () -> new VisibilityDeniedException(null, "无权"),
                "传 null 得到的是 NPE 而不是可诊断的 IllegalArgumentException —— "
                        + "业务错误被伪装成了系统故障");
        assertTrue(nullCase.getMessage().contains("denied_fields"),
                "拒绝信息应点名 denied_fields，而不是一句泛泛的『参数非法』");

        // 含 null / 空白元素的列表同样必须在构造期明确拒绝 ——
        // 否则响应体里会出现一个 null 字段名，客户端既无法定位也无法重试。
        assertThrows(IllegalArgumentException.class,
                () -> new VisibilityDeniedException(java.util.Arrays.asList("as_value", null), "无权"),
                "含 null 元素的 denied_fields 被接受了 —— 会回显出一个不可用的字段名");
        assertThrows(IllegalArgumentException.class,
                () -> new VisibilityDeniedException(List.of("  "), "无权"));

        // 正向对照：非空即可构造，且 getDeniedFields 就是它
        VisibilityDeniedException ok =
                new VisibilityDeniedException(List.of("mcid"), "无权");
        assertEquals(List.of("mcid"), ok.getDeniedFields());
        assertEquals(Map.of("denied_fields", List.of("mcid")), ok.getData(),
                "信封 data 必须恰为 {denied_fields:[…]}");
    }

    @Test
    @DisplayName("classify：登记名→true，非登记名→false，空→空")
    void classify_reports_a_verdict_for_every_non_blank_name() {
        assertEquals(java.util.Optional.of(true), DerivedFields.classify("as_value"));
        assertEquals(java.util.Optional.of(false), DerivedFields.classify("step_count"));
        assertTrue(DerivedFields.classify(null).isEmpty());
        assertTrue(DerivedFields.classify("").isEmpty());
    }

    // ------------------------------------------------------------------ 小工具

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new LinkedHashSet<>(a);
        d.removeAll(b);
        return d;
    }
}