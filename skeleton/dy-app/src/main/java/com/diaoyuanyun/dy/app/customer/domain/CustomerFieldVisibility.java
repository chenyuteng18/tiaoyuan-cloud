package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.VisibilityDeniedException;
import com.diaoyuanyun.dy.security.visibility.DerivedFields;
import com.diaoyuanyun.dy.security.visibility.DerivedRequestScanner;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4 客户详情的<b>可见性裁剪</b> —— 契约 {@code CustomerDetailData} 的
 * {@code x-visible-to} 解算器（S2-10 域 B）。
 *
 * <h2>一、本类的职责边界（🛑 与既有两处防线<b>不重合、不替代</b>）</h2>
 * <pre>
 *  ① 入站拦截器 DerivedVisibilityInterceptor —— 拒「客户点名【派生字段】」
 *     覆盖：effect_verdict / as_value / a3_value / refund_eligibility / gap_reason…
 *     以及组别名：verdict / derived / gap_reason（展开成字段名回显）
 *     它是【全局】的，对所有端点生效，且【先于】本类执行。
 *  ② 出口兜底 DerivedResponseBodyAdvice —— 挡「派生字段被顺手带出」；
 *     它同样是【全局】的，对任何响应路径生效。
 *  ③ 本类 —— B4 响应体内【非派生的客户不可见字段】的裁剪与 403 归因
 *     覆盖：owner_store_id / serving_store_id
 * </pre>
 *
 * <p>🛑 <b>为什么必须由本类补第 ③ 项</b>：{@code owner_store_id} 与
 * {@code serving_store_id} 的 {@code x-visible-to} <b>不含 client</b>，
 * 但它们<b>不是</b>派生字段 —— 故拦截器（按派生字段判）与出口兜底（按派生字段摘）
 * <b>都不会</b>管它们。若本类也不管，这两个字段就会稳定地泄漏给客户。
 * 而契约 {@code CustomerDetailData} 里对它们的 description 逐字写着「<b>客户不下发</b>」。
 *
 * <h2>二、为什么"客户请求客户不可见字段"要报 403，而不是"静默不下发"</h2>
 * 两条契约依据叠加：
 * <pre>
 *  契约 §2.0 forbidden-403 逐字：「『前置门禁缺失』与『可见性档位不足』统一返回 403，
 *                                message 必须给出缺失项名称 / 档位名称（不得模糊报错）」
 *  契约 B4 responses 声明集 = 200 / 403 / 404 —— 403【已被声明】，故报它不违约
 * </pre>
 * 也就是说：<b>点名索取</b>一个自己无档位的字段 = 「可见性档位不足」⇒ 403。
 * 而"没点名"时该字段只是不出现在响应里（{@code x-visible-to} 的常规语义）——
 * 这两种情形是两件事，本类只处理前者。
 *
 * <h2>三、🛑 组别名表<b>不</b>另立一份：复用 {@link DerivedRequestScanner#groupAliases()}</h2>
 * {@code ?include=verdict} 需要展开成 {@code [effect_verdict, as_value]} 才能逐字回显
 * （契约 B4 描述逐字给出这个回显值）。而"verdict 是这两个字段的组别名"这个事实
 * <b>已经</b>登记在 {@link DerivedRequestScanner#groupAliases()} 里。
 * 若本类自己再写一张，就会得到<b>两份别名表</b> —— 它们在某人补充一个新别名后必然分叉，
 * 而分叉的那一侧（本类，负责 403 回显）会让前端拿到的被拒字段名与它索取的名字对不上。
 * 故本类一律问那张唯一的表（与 {@code DerivedRequestScanner} 类注释里
 * "不另立字段清单"是同一条纪律）。
 *
 * <h2>四、🛑 未知 include token 的处理：<b>忽略</b>，不报错</h2>
 * B4 的声明集是 200/403/404 —— <b>没有 400</b>。故一个无法识别的 include token
 * <b>不得</b>报 400（那是契约未声明的码，客户端会落进未定义分支）。
 * 而它也不该报 403：它没有对应任何字段，谈不上"档位不足"。
 * 故处置是<b>忽略</b>（不追加任何字段），并把该处置登记在
 * {@link #describeIncludePolicy()} 里使其成为可断言的事实。
 *
 * <h2>五、🛑 归一化口径必须与入站扫描器<b>同一份</b>（域 B 追加的硬约束）</h2>
 * 入站拦截器用 {@link DerivedFields#normalize(String)} 做"去非字母数字 + 小写"的归一，
 * 故 {@code effectVerdict} / {@code effect_verdict} / {@code Effect-Verdict}
 * 在<b>入站侧</b>是同一个东西。若本类改用精确字符串比较，就会出现：
 * <pre>
 *   ?include=effect_verdict   → 入站判为派生（403），本类判为"见过"      —— 一致 ✓
 *   ?include=effectVerdict    → 入站判为派生（403），本类判为"不认识"    —— 【分叉】
 * </pre>
 * 分叉的后果不是"多报一次"，而是<b>同一个字段在两条路径上得到两种结论</b>，
 * 使"哪个防线拦住了它"取决于路径 —— 这正是安全边界最不该有的性质。
 * 故本类<b>所有</b>字段名比较一律经 {@link #resolveFieldToken(String)}，
 * 内部即 {@link DerivedFields#normalize(String)}。
 *
 * <h2>六、遍历骨架只有一份（{@link IncludeToken}）</h2>
 * "解析 include"（喂给响应装配）与"拒绝归因"（凑 403 回显）对同一个 token 的判定
 * <b>必须完全一致</b>。故两者共用同一次 {@link #tokenize(List)} 的产物，
 * 而不是各写一个 for 循环 —— 后者在有人补一条规则时必然只改一边。
 */
public final class CustomerFieldVisibility {

    private CustomerFieldVisibility() {
    }

    /** include 值的分隔符（与 {@link DerivedRequestScanner} 的 {@code VALUE_SEPARATORS} 同口径）。 */
    private static final String VALUE_SEPARATORS = "[,\\s，、|+]+";

    // ==================================================================
    // 一、默认下发集（不问、不提，就在响应里）
    // ==================================================================

    /**
     * 给定端角色，B4 响应体<b>默认</b>下发的字段集。
     *
     * <p>构成 = "契约 {@code x-visible-to} 含该角色"<b>且</b>"非派生"。
     * 派生字段（{@code effect_verdict} / {@code as_value}）即便对 staff 可见，
     * 也属 {@code include} 追加（见 {@link CustomerDetailField#isDerived()}）。
     *
     * <p>fail-closed：{@code role == null}（未带身份）按客户处理 ——
     * 与 {@link DerivedFields#isClientLike(String)} 的处置同源（匿名 = 按最严处理）。
     */
    public static Set<CustomerDetailField> defaultVisible(VisibilityRole role) {
        Set<CustomerDetailField> out = new LinkedHashSet<>();
        for (CustomerDetailField f : CustomerDetailField.values()) {
            if (f.isDerived()) {
                continue;   // 派生字段永不默认下发（对 staff 亦是 include 追加）
            }
            if (f.isVisibleTo(role)) {
                out.add(f);
            }
        }
        return out;
    }

    // ==================================================================
    // 二、include 解析（唯一遍历骨架）
    // ==================================================================

    /**
     * 一个 include token 的分类结果（判定的<b>单一事实来源</b>）。
     *
     * @param raw            调用方原始拼写（用于 403 逐字回显）
     * @param field          命中"已登记字段"时的字段；否则 {@code null}
     * @param groupExpansion 命中"组别名"时展开出的契约字段名列表；否则 {@code null}
     */
    private record IncludeToken(String raw, CustomerDetailField field, List<String> groupExpansion) {

        /** 命中已登记字段。 */
        boolean isField() {
            return field != null;
        }

        /** 命中组别名（verdict 等）。 */
        boolean isGroup() {
            return field == null && groupExpansion != null;
        }

        /** 既非字段亦非组别名 —— 无法识别。 */
        boolean isUnrecognized() {
            return field == null && groupExpansion == null;
        }
    }

    /**
     * 把 include 参数拆成 token 并逐个分类（<b>保序</b>）。
     *
     * <p>🛑 分类顺序与入站扫描器的 {@code record()} <b>同序</b>：
     * <b>先按字段名（归一化）判，再查组别名表</b>。两处若不同序，
     * 同一请求在"被入站拦"与"被本类拦"两条路径上会回显不同的字段序列，
     * 而前端若按数组序号取用就会错位。
     */
    private static List<IncludeToken> tokenize(List<String> includes) {
        List<IncludeToken> out = new ArrayList<>();
        if (includes == null) {
            return out;
        }
        for (String raw : includes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String token : raw.split(VALUE_SEPARATORS)) {
                if (token.isBlank()) {
                    continue;
                }
                CustomerDetailField field = resolveFieldToken(token);
                if (field != null) {
                    out.add(new IncludeToken(token, field, null));
                    continue;
                }
                List<String> expanded = DerivedRequestScanner.groupAliases().get(DerivedFields.normalize(token));
                out.add(new IncludeToken(token, null,
                        expanded == null ? null : List.copyOf(expanded)));
            }
        }
        return out;
    }

    /**
     * 按<b>与入站扫描器同一份归一化口径</b>把 token 解成契约字段。
     *
     * <p>先精确匹配（契约里写的就是 snake_case，命中即用）；不中则按归一化串
     * 逐个比对登记表 —— 使 {@code effectVerdict} / {@code Effect-Verdict}
     * 与 {@code effect_verdict} 得到<b>相同</b>的结论。
     *
     * @return 命中的字段；未命中返回 {@code null}（调用方据此转查组别名表）
     */
    private static CustomerDetailField resolveFieldToken(String token) {
        CustomerDetailField exact = CustomerDetailField.tryOf(token);
        if (exact != null) {
            return exact;
        }
        String normalized = DerivedFields.normalize(token);
        if (normalized.isEmpty()) {
            return null;
        }
        for (CustomerDetailField f : CustomerDetailField.values()) {
            if (DerivedFields.normalize(f.jsonName()).equals(normalized)) {
                return f;
            }
        }
        return null;
    }

    /**
     * 解析 include 参数 → 追加字段集（**已按角色裁剪**）。
     *
     * <p>规则：
     * <pre>
     *   token 命中【已登记字段名】（含归一化变体） → 该字段
     *   token 命中【组别名】（verdict 等）         → 展开成契约字段名（复用唯一别名表）
     *   其余（未知 token）                         → 保留原样（对 staff 在下方自然落空 = 忽略）
     * </pre>
     * 返回集合已剔除"该角色不可见"的字段 —— 不可见的情形由
     * {@link #assertIncludeAllowed} 在更早一步以 403 拦下（两者配合，缺一不可：
     * 只靠断言则 staff 调用也要走一遍拒绝逻辑；只靠本方法则客户的越权索取会被静默容忍）。
     * 去重、保序（契约字段名按 {@link CustomerDetailField} 声明序稳定输出）。
     */
    public static Set<CustomerDetailField> resolveIncludes(List<String> includes, VisibilityRole role) {
        Set<String> wanted = new LinkedHashSet<>();
        for (IncludeToken t : tokenize(includes)) {
            if (t.isField()) {
                wanted.add(t.field().jsonName());
            } else if (t.isGroup()) {
                wanted.addAll(t.groupExpansion());
            } else {
                wanted.add(t.raw());   // 未识别：保原样，下方按 jsonName 匹配自然落空
            }
        }
        Set<CustomerDetailField> out = new LinkedHashSet<>();
        for (CustomerDetailField f : CustomerDetailField.values()) {
            if (wanted.contains(f.jsonName()) && f.isVisibleTo(role)) {
                out.add(f);
            }
        }
        return out;
    }

    // ==================================================================
    // 三、越权索取 → 403（B4 的 403 归因）
    // ==================================================================

    /**
     * 断言 include 里的每一项对该角色都可见；命中不可见项即 403 {@code VISIBILITY_DENIED}。
     *
     * <h2>🛑 回显的字段名<b>保留调用方原始拼写</b>（组别名例外，见下）</h2>
     * 契约 §3.2 与 B4 描述都要求 {@code data.denied_fields[]} 回显被拒字段名 ——
     * 客户端要用它定位<b>自己发出的那个参数</b>。故：
     * <pre>
     *   ?include=effect_verdict  → denied_fields = ["effect_verdict"]（原名）
     *   ?include=effectVerdict   → denied_fields = ["effectVerdict"] （原名；归一后仍是同一字段）
     *   ?include=verdict         → denied_fields = ["effect_verdict","as_value"]（组别名 → 展开，
     *                              与契约 B4 描述逐字一致）
     * </pre>
     * 第三行的展开是刻意的：调用方真正需要知道的是"我碰到了哪两样东西"，
     * {@code verdict} 只是一个<b>组别名</b>。若回显 {@code "verdict"}，
     * 客户端无从判断究竟碰了哪个字段，只能靠猜 —— 那正是 P0-08「不得模糊报错」要防的。
     *
     * <p>🛑 顺序：<b>先字段名、后组别名</b>，与 {@link DerivedRequestScanner} 的
     * {@code record()} 同序。两处若不同序，同一请求在"被入站拦"与"被本类拦"
     * 两条路径上会回显不同的字段序列 —— 而前端若按数组序号取用就会错位。
     *
     * @throws VisibilityDeniedException 客户（或匿名）点名了不可见字段
     */
    public static void assertIncludeAllowed(List<String> includes, VisibilityRole role) {
        List<String> denied = deniedIncludes(includes, role);
        if (!denied.isEmpty()) {
            throw new VisibilityDeniedException(denied,
                    "B4 客户详情：请求点名了当前角色无可见性档位的字段（契约 §2.0 forbidden-403："
                            + "『可见性档位不足』统一 403，且必须给出档位/字段名称、不得模糊报错）；"
                            + "被拒字段: " + denied
                            + "。🛑 客户的 handler 是【服务端裁剪】而非前端隐藏："
                            + "这些字段在契约 CustomerDetailData 里的 x-visible-to 不含 client "
                            + "（owner_store_id / serving_store_id 的 description 逐字『客户不下发』）");
        }
    }

    /**
     * 解算"应拒的 include 字段"（不抛）。供单测逐项比对与诊断。
     *
     * <p>三种命名一律回显：已登记字段名（<b>保留原始拼写</b>）/ 组别名展开后的契约字段名 /
     * <b>无法识别的原始 token</b>。
     * 第三种的处置与契约的关系值得写清：一个无法识别的 token（如某人自造的
     * {@code ?include=secret_note}）对客户而言**依旧**是"索取了一个自己没有档位的东西"，
     * 故一并拒绝并回显他真正的拼写 —— 让排查能从日志一眼看出他到底要了什么。
     * （对 staff 该 token 会被忽略，见 {@link #resolveIncludes}。）
     *
     * <p>去重按<b>归一化串</b>：{@code verdict,effect_verdict} 同时出现时，
     * 第二个不会重复记一遍（它们的归一化串相同）。
     */
    public static List<String> deniedIncludes(List<String> includes, VisibilityRole role) {
        boolean isClient = role == null || role == VisibilityRole.CLIENT;
        List<String> denied = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IncludeToken t : tokenize(includes)) {
            if (t.isField()) {
                if (!t.field().isVisibleTo(role) && seen.add(DerivedFields.normalize(t.raw()))) {
                    denied.add(t.raw());   // 回显调用方原始拼写
                }
            } else if (t.isGroup()) {
                for (String name : t.groupExpansion()) {
                    CustomerDetailField f = CustomerDetailField.tryOf(name);
                    boolean allowed = f == null || f.isVisibleTo(role);
                    if (!allowed && seen.add(DerivedFields.normalize(name))) {
                        denied.add(name);   // 展开后的契约字段名（契约 B4 逐字）
                    }
                }
            } else if (isClient && seen.add(DerivedFields.normalize(t.raw()))) {
                // 未识别的 token：对 staff 忽略（放行），对客户拒绝（见方法注释）
                denied.add(t.raw());
            }
        }
        return List.copyOf(denied);
    }

    // ==================================================================
    // 四、一致性守卫（启动期 / 测试期）
    // ==================================================================

    /**
     * 断言本类的登记表与全局派生字段口径<b>不矛盾</b>。
     *
     * <h2>它防的是一种具体的分叉</h2>
     * {@link CustomerDetailField#isDerived()} 说的是"本字段在 B4 响应体里属派生/追加"，
     * 而 {@link DerivedFields#isDerivedField(String)} 说的是"这个字段名全局属派生结论"。
     * 两者对 {@code effect_verdict} / {@code as_value} 必须<b>一致为真</b>。
     * 若哪天有人把 {@code CustomerDetailField.EFFECT_VERDICT} 的 derived 标成 false
     * （例如为了让它默认下发），本方法会在第一次被调用时抛错 ——
     * 而不是让那次改动安静地生效、直到某天客户的响应体里出现了效果判定。
     *
     * <p>同时校验 {@link CustomerDetailField#clientForbiddenNames()} 与全局
     * {@link DerivedFields#clientForbiddenNonDerivedNames()} 的<b>非派生部分</b>一致 ——
     * 这正是本类第 ③ 项职责所覆盖的那批字段（{@code owner_store_id} /
     * {@code serving_store_id}）：若全局表新登记了一个非派生客户禁字段而本类没跟上，
     * 那次登记就会静默失效。
     *
     * @throws IllegalStateException 登记表与全局口径分叉
     */
    public static void assertConsistentWithGlobalDerivedRegistry() {
        List<String> inconsistent = new ArrayList<>();
        for (CustomerDetailField f : CustomerDetailField.values()) {
            boolean global = DerivedFields.isDerivedField(f.jsonName());
            if (f.isDerived() != global) {
                inconsistent.add(f.jsonName() + "(本类=" + f.isDerived() + ", 全局=" + global + ")");
            }
        }
        if (!inconsistent.isEmpty()) {
            throw new IllegalStateException(
                    "CustomerDetailField 的派生标记与全局 DerivedFields 登记表分叉: " + inconsistent
                            + "。🛑 两者必须一致：本类决定『默认下发 or include 追加』，"
                            + "全局表决定『出口兜底摘不摘』。分叉会让同一个字段"
                            + "在『默认下发』与『被兜底摘掉』之间摇摆 —— 表现为"
                            + "『契约说该有、实际没有』或反过来的静默泄漏");
        }

        // 非派生客户禁字段：本类必须与全局表一致（本类第 ③ 项职责的唯一来源）
        Set<String> ours = new LinkedHashSet<>();
        for (CustomerDetailField f : CustomerDetailField.values()) {
            if (!f.isDerived() && !f.isClientVisible()) {
                ours.add(f.jsonName());
            }
        }
        Set<String> globals = new LinkedHashSet<>();
        for (String name : DerivedFields.clientForbiddenNonDerivedNames()) {
            globals.add(name);
        }
        Set<String> onlyGlobal = new LinkedHashSet<>(globals);
        onlyGlobal.removeAll(ours);
        Set<String> onlyOurs = new LinkedHashSet<>(ours);
        onlyOurs.removeAll(globals);
        if (!onlyGlobal.isEmpty() || !onlyOurs.isEmpty()) {
            throw new IllegalStateException(
                    "『非派生的客户禁字段』本类与全局表分叉：全局独有=" + onlyGlobal
                            + "，本类独有=" + onlyOurs
                            + "。🛑 这批字段正是本类第 ③ 项职责（拦截器与出口兜底都按『派生』判，"
                            + "都不会管它们）。任一侧新增而另一侧未跟上，"
                            + "都会让该字段【静默对客户下发】");
        }
    }

    /** include 处置策略的自描述（供端点自描述与断言读取，使"未知 token 被忽略"成为可验证事实）。 */
    public static Map<String, Object> describeIncludePolicy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", "B4 GET /customers/{id}");
        m.put("default_visible_client", namesOf(defaultVisible(VisibilityRole.CLIENT)));
        m.put("default_visible_therapist", namesOf(defaultVisible(VisibilityRole.THERAPIST)));
        m.put("default_visible_meridian", namesOf(defaultVisible(VisibilityRole.MERIDIAN)));
        m.put("default_visible_admin", namesOf(defaultVisible(VisibilityRole.ADMIN)));
        m.put("client_forbidden_names", List.copyOf(CustomerDetailField.clientForbiddenNames()));
        m.put("alias_source", "com.diaoyuanyun.dy.security.visibility.DerivedRequestScanner.groupAliases()");
        m.put("alias_note",
                "组别名（verdict 等）不另立表：复用入站扫描器的唯一别名表，"
                        + "否则同一请求在『入站拦截』与『本类拦截』两条路径上会回显不同的字段序列");
        m.put("normalization",
                "字段名匹配一律走 DerivedFields.normalize()（去非字母数字 + 小写），"
                        + "与入站扫描器同一份口径；否则 effectVerdict 这类变体会在"
                        + "『入站判为派生』与『本类判为不认识』之间分叉");
        m.put("verdict_alias_expands_to", DerivedRequestScanner.groupAliases().get("verdict"));
        m.put("unknown_include_token",
                "忽略（不追加字段）。依据：B4 的 responses 声明集 = 200/403/404，【无 400】—— "
                        + "报 400 会让客户端落进契约未声明的分支；"
                        + "也不报 403（未知 token 没有对应字段，谈不上档位不足）。"
                        + "对【客户】例外：无法识别的 token 依旧拒绝并回显其原始拼写，"
                        + "因为对客户而言『索取了一个自己没有档位的东西』这一点成立，"
                        + "且回显原始拼写使排查能看出他到底要了什么");
        m.put("division_of_labour",
                "派生字段的入站 403 由 DerivedVisibilityInterceptor 承担（全局、先于本类）；"
                        + "本类只补『非派生的客户不可见字段』（owner_store_id / serving_store_id），"
                        + "因为拦截器与出口兜底都按『派生』判，都不会管它们");
        return m;
    }

    private static List<String> namesOf(Set<CustomerDetailField> fields) {
        List<String> out = new ArrayList<>();
        for (CustomerDetailField f : fields) {
            out.add(f.jsonName());
        }
        return List.copyOf(out);
    }
}