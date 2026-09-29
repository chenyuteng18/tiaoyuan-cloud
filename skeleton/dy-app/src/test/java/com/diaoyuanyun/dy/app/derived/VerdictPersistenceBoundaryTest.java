package com.diaoyuanyun.dy.app.derived;

import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.domain.Disposition;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.AdherenceState;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictPort;
import com.diaoyuanyun.dy.app.derived.domain.VerdictRow;
import com.diaoyuanyun.dy.app.derived.repository.VerdictLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定链的<b>持久化边界门禁</b> —— 「判定结论只可新增、不可原地覆盖」这条
 * PRD §C.1.9 硬约束③ 的可机械断言形态。
 *
 * <h2>三条独立防线，本类负责其中两条</h2>
 * <ol>
 *   <li><b>端口方法集</b>（本类）：{@link VerdictPort} 的方法名里不得出现
 *       {@code update/delete/upsert/merge}，且方法数被钉死在
 *       {@link VerdictPort#PORT_METHOD_COUNT}；</li>
 *   <li><b>SQL 字面量</b>（本类）：仓储源码里的改写字面量被<b>收窄为一个受控 UPDATE</b>
 *       （{@code attachJudgment}），且它必须带 {@code verdict IS NULL} 谓词；</li>
 *   <li><b>库层</b>（{@code RlsV5EntityIsolationTest} 已覆盖的 RLS FORCE +
 *       本类的表结构断言）：真库里的列定义与 CHECK 约束。</li>
 * </ol>
 *
 * <h2>🛑 V8 起「不可覆盖」的形态变更：从"没有 UPDATE"到"恰好一个受控 UPDATE"</h2>
 * 本类原先断言仓储源码里<b>不得出现</b> {@code UPDATE}。V8 起该断言被收窄为
 * 「<b>恰好一个</b> UPDATE，且它的 WHERE 子句必须含 {@code verdict IS NULL}」。
 * <p>这不是放宽，而是<b>把一条笼统的禁令换成一个更强的结构化约束</b>：
 * 原断言只能证明"没有就地改写"，但它无法表达"允许单向迁移"——
 * 于是 A-1 裁定「C4 先落依据、F1 后补判定」在实现上无路可走
 * （依据行已经落了，判定必须能补上它，而补 = 对同一行的一次更新）。
 * <ul>
 *   <li>原禁令 → 现在有 {@code ledger_source_has_exactly_one_mutating_statement}
 *       断言"改写字面量总数恰好为 1"（多一个就红）；</li>
 *   <li>新增 {@code attach_judgment_update_is_predicated_on_verdict_is_null}
 *       断言那个 UPDATE 的 WHERE 含 {@code verdict IS NULL} ——
 *       于是一次「已判定 → 再判定」不可能发生（影响 0 行 ⇒ 抛 VERSION_CONFLICT）；</li>
 *   <li>新增 {@code attach_judgment_update_touches_only_judgement_side_columns}
 *       断言的 SET 列清单是白名单
 *       （{@code verdict} / {@code effect_verdict} / {@code improvement_rate} /
 *        {@code metric_snapshot} / {@code updated_at}）——
 *       它保证 F1 补判定时<b>不覆盖</b> C4 已落的评估侧事实
 *       （{@code as_value} / {@code as_dimensions_json} / {@code adhrence_state} 等）。</li>
 * </ul>
 *
 * <h2>🛑 为什么要断言"仓储里没有 updated_at 的写入"</h2>
 * {@code cycle_assessment} / {@code verdict} 两张表<b>都有</b> {@code updated_at} 列
 * （与退款域那三张 append-only 账本<b>不同</b> —— 那三张刻意没有）。
 * 差异是刻意的：这两张允许未来有受控更新。而"允许"这件事必须通过
 * <b>新增一个端口方法</b>来表达，不能顺手改一行 SQL —— 故本类断言
 * 两条 INSERT 的列清单里<b>不含</b> {@code updated_at}。
 */
class VerdictPersistenceBoundaryTest {

    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    private static final Path LEDGER = SKEL.resolve(
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/repository/VerdictLedger.java");

    private static final Path MIGRATION_V5 = SKEL.resolve(
            "dy-app/src/main/resources/db/migration/"
                    + "V5__remaining_entities_org_journey_verdict_refund.sql");

    /** V8 —— 两阶段对齐迁移（放宽 verdict/confidence、补 disposition、补 risk_flag CHECK）。 */
    private static final Path MIGRATION_V8 = SKEL.resolve(
            "dy-app/src/main/resources/db/migration/"
                    + "V8__verdict_two_phase_alignment.sql");

    private static final Path CONTROLLER = SKEL.resolve(
            "dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/controller/VerdictController.java");

    private static final Path PRD = SKEL.getParent().resolve("prd-health-mgmt-saas-2026-09-16.md");

    // ==================================================================
    // 一、端口方法集（防线 1）
    // ==================================================================

    @Test
    @DisplayName("🛑 端口方法名里不得出现 update / delete / upsert / merge（append-only）")
    void port_exposes_no_mutating_operations() {
        Set<String> names = new TreeSet<>();
        for (Method m : VerdictPort.class.getDeclaredMethods()) {
            if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                continue;   // validateTenantId 是 static 工具，不是持久化操作
            }
            names.add(m.getName());
        }

        List<String> offending = new ArrayList<>();
        for (String n : names) {
            String lower = n.toLowerCase(Locale.ROOT);
            for (String banned : List.of("update", "delete", "upsert", "merge", "modify", "patch", "replace")) {
                if (lower.contains(banned)) {
                    offending.add(n + "（含 '" + banned + "'）");
                }
            }
        }
        assertTrue(offending.isEmpty(),
                "🛑 判定链端口出现了就地改写类方法：" + offending
                        + "。PRD §C.1.9 硬约束③逐字：『版本不可覆盖：题库、方案、判定"
                        + "（verdict.threshold_version）、文书模板四处只可新增版本，不可原地覆盖』。"
                        + "\n判定被逐字点名在其中，故这里连『受控 update』都不给 —— "
                        + "想改结论的唯一途径是重开一次判定（新行），而不是改写旧行。"
                        + "\n实际方法集: " + names);
    }

    @Test
    @DisplayName("🛑 端口方法数恒为 8（新增方法必须显式表态）")
    void port_method_count_is_pinned() {
        Set<String> names = new TreeSet<>();
        for (Method m : VerdictPort.class.getDeclaredMethods()) {
            if (!m.isSynthetic() && !Modifier.isStatic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        assertEquals(VerdictPort.PORT_METHOD_COUNT, names.size(),
                "端口方法数与 PORT_METHOD_COUNT 不一致 —— 这个常量存在的唯一目的就是"
                        + "让『给判定链加一个入口』变成一次必须改断言的改动。实际方法集: " + names);

        // 正向清单：每个方法名都要在预期集合里（防止"删一个 + 加一个"凑数）
        assertEquals(new TreeSet<>(List.of(
                        "insertCycleAssessment", "findCycleAssessment", "findCycleAssessmentsByCustomer",
                        "attachJudgment",
                        "insertVerdict", "findVerdict", "findVerdictsByCustomer", "findVerdictsByCycle")),
                names,
                "端口方法集与预期不一致。🛑 三个周期侧读方法里有两个是 find —— "
                        + "findCycleAssessmentsByCustomer 的存在理由见 VerdictPort 的注释"
                        + "（挂起/待判定轮次只落依据侧，不返回它会静默消失）。"
                        + "attachJudgment 是 V8 新增的【唯一】受控更新入口 —— "
                        + "它的存在使『C4 先落依据、F1 后补判定』成为可能");
    }

    @Test
    @DisplayName("🛑 写入方法恰好 3 个：两个 INSERT + 一个受控 UPDATE（attachJudgment）")
    void only_two_insert_methods_exist() {
        List<String> inserts = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        for (Method m : VerdictPort.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            if (m.getName().startsWith("insert")) {
                inserts.add(m.getName());
            }
            if (m.getName().startsWith("attach")) {
                updates.add(m.getName());
            }
        }
        assertEquals(List.of("insertCycleAssessment", "insertVerdict"), inserts.stream().sorted().toList(),
                "【新增型】写入方法必须恰好两个：依据一条、结论一条。"
                        + "多出来的任何一个都意味着有人开了第二条新增路径");
        assertEquals(List.of("attachJudgment"), updates,
                "🛑【更新型】写入方法必须恰好一个，且名字必须是 attachJudgment —— "
                        + "attach 这个词是刻意的：它是『把一个判定附到一行已存在的依据上』，"
                        + "而不是『改写那一行』。若这里出现第二个名字，"
                        + "说明有人加了一条不经 verdict IS NULL 谓词约束的写入路径");
    }

    // ==================================================================
    // 二、SQL 字面量（防线 2）
    // ==================================================================

    @Test
    @DisplayName("🛑 仓储源码里改写字面量恰好 1 个（受控 UPDATE = attachJudgment）")
    void ledger_source_has_exactly_one_mutating_statement() throws IOException {
        assertTrue(Files.exists(LEDGER), "仓储源码不存在: " + LEDGER);
        String src = stripCommentsAndStrings(Files.readString(LEDGER, StandardCharsets.UTF_8));
        String upper = src.toUpperCase(Locale.ROOT);

        // ① 结构性禁令（一律为零）：DELETE / MERGE / upsert / DDL
        for (String banned : List.of("DELETE ", "MERGE ", "ON CONFLICT", "TRUNCATE", "DROP ")) {
            assertFalse(upper.contains(banned),
                    "🛑 仓储源码里出现 '" + banned + "' —— "
                            + "判定链只可【新增】或【受控单向补全】，"
                            + "而这一个关键字连『受控』都不可能是（它没有谓词收窄的余地）。"
                            + "ON CONFLICT 也算：它是 upsert 的 SQL 形态，等价于『就地改写任意行』");
        }

        // ② 计数型禁令：UPDATE 只允许 1 个（且下一节断言它带谓词 + 列白名单）
        int updateCount = countOccurrences(upper, "UPDATE ");
        assertEquals(1, updateCount,
                "🛑 仓储源码里的 'UPDATE ' 出现次数必须恰好为 1（实际 " + updateCount + "）。"
                        + "V8 起允许【一个】受控 UPDATE（attachJudgment）—— 不是放宽，"
                        + "而是把笼统禁令换成更强的结构化约束（谓词 + 列白名单，见下两节）。"
                        + "多一个就说明有人在 append-only 账本上重开了就地改写");

        assertTrue(upper.contains("INSERT INTO"),
                "仓储必须含 INSERT（否则这条断言在证明一个空实现）");
    }

    @Test
    @DisplayName("🛑 attachJudgment 的 UPDATE 必须带 `verdict IS NULL` 谓词（单向迁移）")
    void attach_judgment_update_is_predicated_on_verdict_is_null() throws IOException {
        String src = stripCommentsAndStrings(Files.readString(LEDGER, StandardCharsets.UTF_8));
        String updateStmt = extractUpdateStatement(src);

        assertTrue(updateStmt.toUpperCase(Locale.ROOT).contains("VERDICT IS NULL"),
                "🛑 attachJudgment 的 UPDATE WHERE 子句里【没有】'verdict IS NULL' —— "
                        + "这个谓词是 A-1 裁定『评估先落、判定后补』的全部安全边界："
                        + "它把一次 update 收窄成『未判定 → 已判定』的【单向迁移】，"
                        + "于是『已判定 → 再判定』在库层影响 0 行 ⇒ 被拒（VERSION_CONFLICT）。"
                        + "去掉它，PRD §C.1.9 硬约束③『判定不可原地覆盖』立刻失效。"
                        + "实际语句: " + updateStmt.replaceAll("\\s+", " "));

        assertTrue(updateStmt.toUpperCase(Locale.ROOT).contains("CYCLE_ID"),
                "UPDATE 必须按 cycle_id 定位（它是 PK，定位不唯一就等于批量改写）");
    }

    @Test
    @DisplayName("🛑 attachJudgment 的 SET 列清单是白名单：只写判定侧，不覆盖评估侧")
    void attach_judgment_update_touches_only_judgement_side_columns() throws IOException {
        String src = stripCommentsAndStrings(Files.readString(LEDGER, StandardCharsets.UTF_8));
        String updateStmt = extractUpdateStatement(src).toUpperCase(Locale.ROOT);
        int setIdx = updateStmt.indexOf("SET ");
        int whereIdx = updateStmt.indexOf(" WHERE ");
        assertTrue(setIdx > 0 && whereIdx > setIdx, "无法解析 SET 子句边界: " + updateStmt);
        String setClause = updateStmt.substring(setIdx + 4, whereIdx);

        Set<String> allowed = new TreeSet<>(List.of(
                "VERDICT", "EFFECT_VERDICT", "IMPROVEMENT_RATE", "METRIC_SNAPSHOT", "UPDATED_AT"));
        // 逐列抽取（形如 `col = ?,` 或 `col = ?::jsonb,`）
        Matcher m = Pattern.compile("([A-Z_]+)\\s*=").matcher(setClause);
        Set<String> assigned = new TreeSet<>();
        while (m.find()) {
            assigned.add(m.group(1));
        }
        assertEquals(allowed, assigned,
                "🛑 attachJudgment 的 SET 列集合必须是【判定侧四列 + updated_at】这一个白名单。"
                        + "它保证 F1 在 C4 已落的依据行上补判定时【不覆盖】评估侧事实："
                        + "as_value / as_dimensions_json / adherence_state / module_scores / "
                        + "threshold_version / recorded_at（created_at）都不得出现在 SET 里。"
                        + "否则一次补判定会把『当时评估提交了什么』静默改成『判定时系统算了什么』，"
                        + "而依据是不可覆盖的、会永久留在证据链里。实际 SET 列: " + assigned);

        // 反向自证：评估侧核心列必须不在 SET 里（否则上一个断言可能因命名巧合而失真）
        for (String mustNot : List.of("AS_VALUE", "AS_DIMENSIONS_JSON", "ADHERENCE_STATE",
                "THRESHOLD_VERSION", "CREATED_AT")) {
            assertFalse(assigned.contains(mustNot),
                    "🛑 " + mustNot + " 出现在 attachJudgment 的 SET 里 —— 它是 C4 落的评估侧事实，"
                            + "F1 补判定不得触碰它（PRD §C.1.9 硬约束②：判定依据必须落库且不可覆盖）");
        }

        // INSERT 的列清单仍不含 updated_at（新增路径不产生修改时间）
        assertFalse(setClause.isBlank(), "SET 子句不得为空");
    }

    @Test
    @DisplayName("🛑 两个 INSERT 的列清单都【不含】updated_at（写路径不产生修改时间）")
    void insert_column_lists_exclude_updated_at() throws IOException {
        String src = Files.readString(LEDGER, StandardCharsets.UTF_8);

        // 抓出两条 INSERT 的列清单（到 ")" + " VALUES" 为止）
        Pattern p = Pattern.compile("INSERT INTO\\s+(\\w+)\\s*\\((.*?)\\)\\s*\"\\s*\\+\\s*\"\\s*VALUES",
                Pattern.DOTALL);
        Matcher m = p.matcher(src);
        int found = 0;
        while (m.find()) {
            found++;
            String table = m.group(1);
            String cols = m.group(2);
            assertFalse(cols.contains("updated_at"),
                    "🛑 " + table + " 的 INSERT 列清单里含 updated_at —— "
                            + "写路径不产生『修改时间』。表里有这一列是刻意的（允许将来有受控更新），"
                            + "但『允许』必须通过新增一个端口方法表达，不能顺手改一行 SQL。"
                            + "实际列清单: " + cols.replaceAll("\\s+", " "));
        }
        assertEquals(2, found, "必须恰好解析出 2 条 INSERT 语句（实际 " + found + "）");
    }

    @Test
    @DisplayName("🛑 verdict 的 INSERT 显式绑定 visible_to_customer = FALSE（不省略列让 DEFAULT 生效）")
    void verdict_insert_binds_visible_to_customer_explicitly() throws IOException {
        String src = Files.readString(LEDGER, StandardCharsets.UTF_8);
        int idx = src.indexOf("INSERT INTO verdict");
        assertTrue(idx > 0, "未找到 verdict 的 INSERT");

        String seg = src.substring(idx, Math.min(src.length(), idx + 900));
        assertTrue(seg.contains("visible_to_customer"),
                "🛑 verdict 的 INSERT 列清单必须【显式】含 visible_to_customer —— "
                        + "省略列会让『改 DEFAULT』成为唯一攻击面，那需要一次迁移；"
                        + "显式绑常量则让改动立刻被库层 CHECK 拒成 23514");
        assertTrue(seg.contains("FALSE"),
                "🛑 显式绑定的值必须是常量 FALSE（V5：CHECK (visible_to_customer = FALSE)）");
    }

    // ==================================================================
    // 三、行记录（record）构造期 fail-closed
    // ==================================================================

    private static final UUID TENANT_FREE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("🛑 VerdictRow 拒空：六个 NOT NULL 列缺任一即抛")
    void verdict_row_rejects_nulls() {
        assertThrows(BizException.class, () -> new VerdictRow(
                null, TENANT_FREE_ID, VerdictBranch.STABLE, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
        assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, null, VerdictBranch.STABLE, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
        assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, null, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
        assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.STABLE, null,
                "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
        assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.STABLE, BigDecimal.ONE,
                null, "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
        assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.STABLE, BigDecimal.ONE,
                "{}", null, Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
    }

    @Test
    @DisplayName("🛑 VerdictRow 置信度越界不得截断")
    void verdict_row_rejects_out_of_range_confidence() {
        for (BigDecimal bad : List.of(new BigDecimal("1.001"), new BigDecimal("-0.001"))) {
            BizException e = assertThrows(BizException.class, () -> new VerdictRow(
                    TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.STABLE, bad,
                    "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                    RiskFlag.NONE, Disposition.KEEP_PLAN, "u"));
            assertTrue(e.getMessage().contains("截断"),
                    "拒绝理由应点明『不得截断』（截断会静默改分，而置信度是协商排序键）。"
                            + "实际: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("🛑 VerdictRow 拦下『人工复核 + 触发全面评估的风险标签』这一矛盾组合")
    void verdict_row_rejects_human_review_with_triggering_risk_flag() {
        BizException e = assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.HUMAN_REVIEW, BigDecimal.ONE,
                "{}", "v1", Instant.now(), null, AdherenceState.SAMPLE_INSUFFICIENT,
                RiskFlag.HIGH_RISK, Disposition.KEEP_PLAN, "u"));
        assertTrue(e.getMessage().contains("全面评估"),
                "该组合会让一次安全信号以『等人工』的形态被静默搁置 —— "
                        + "PRD §7.3 D4：命中 {高危,新发,同病} 即应落『全面评估』而非挂起。"
                        + "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 VerdictRow 要求 E5 必带 createdBy（防系统自动产出 E5）")
    void verdict_row_requires_operator_for_e5() {
        BizException e = assertThrows(BizException.class, () -> new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.HUMAN_REVIEW, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E5_WORSENED,
                AdherenceState.PASS, RiskFlag.NONE, Disposition.KEEP_PLAN, null));
        assertTrue(e.getMessage().contains("人工录入"),
                "E5 必须人工录入（指标规格 §3.2），而库层没有这条断言"
                        + "（effect_verdict 有 CHECK 但只查值域）—— 故只能在应用层拦。"
                        + "实际: " + e.getMessage());

        // 带操作人则通过（人工录入的合法形态）
        VerdictRow ok = new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.HUMAN_REVIEW, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E5_WORSENED,
                AdherenceState.PASS, RiskFlag.NONE, Disposition.KEEP_PLAN, "meridian:zhang");
        assertEquals("meridian:zhang", ok.createdBy(), "人工录入 E5 是合法路径，必须能落库");
    }

    @Test
    @DisplayName("🛑 VerdictRow.visibleToCustomer 恒 false（它不是字段）")
    void verdict_row_visible_to_customer_is_always_false() {
        VerdictRow row = new VerdictRow(
                TENANT_FREE_ID, TENANT_FREE_ID, VerdictBranch.STABLE, BigDecimal.ONE,
                "{}", "v1", Instant.now(), EffectVerdict.E3_STABLE, AdherenceState.PASS,
                RiskFlag.NONE, Disposition.KEEP_PLAN, "u");
        assertFalse(row.visibleToCustomer(),
                "Q10 口径②：verdict.visible_to_customer=false，仅作对内证据链");
        // 机制证明：record 的组件列表里没有 visibleToCustomer
        Set<String> components = new TreeSet<>();
        for (var r : VerdictRow.class.getRecordComponents()) {
            components.add(r.getName());
        }
        assertFalse(components.contains("visibleToCustomer"),
                "🛑 visibleToCustomer 不得是 record 组件 —— 一个可设置的字段会让"
                        + "『某次写入把它设成 true』在编译期看起来完全正常。实际组件: " + components);
        // 🛑 V8 起 disposition 成为 record 组件（库层 V8 补了该列，见下节表结构断言）
        assertTrue(components.contains("disposition"),
                "🛑 V8 起 VerdictRow 必须持有 disposition：V8 迁移给 verdict 表补了这一列"
                        + "（PRD §C.1.7 L1794 的组合出口），且它是挂起态与正常态都必须落的事实。"
                        + "实际组件: " + components);
    }

    @Test
    @DisplayName("🛑 V8 起 disposition / confidence 的库层缺口已闭合（迁移文件逐字断言）")
    void v8_closes_disposition_and_confidence_gaps() throws IOException {
        assertTrue(Files.exists(MIGRATION_V8), "V8 迁移文件不存在: " + MIGRATION_V8);
        String sql = Files.readString(MIGRATION_V8, StandardCharsets.UTF_8);
        String upper = sql.toUpperCase(Locale.ROOT);

        // ① cycle_assessment.verdict 放宽为可空（A-1 正解：唯一阻塞 C4 的一列）
        assertTrue(upper.contains("ALTER TABLE CYCLE_ASSESSMENT ALTER COLUMN VERDICT DROP NOT NULL"),
                "🛑 V8 必须放宽 cycle_assessment.verdict 为可空 —— 它是 C4 阶段"
                        + "『评估已落、判定未发生』得以在库层表达的唯一前提"
                        + "（17 列逐列核算后，唯一阻塞 C4 落行的就是这一列）");
        // ② verdict.confidence 放宽为可空（挂起也落结论行）
        assertTrue(upper.contains("ALTER TABLE VERDICT ALTER COLUMN CONFIDENCE DROP NOT NULL"),
                "🛑 V8 必须放宽 verdict.confidence 为可空 —— 它是『挂起（D5）也落结论行』"
                        + "的前提：confidence = null 表达『不可判』，与 0.000『最低置信』结构不同");
        // ③ verdict 补 disposition 列
        assertTrue(upper.contains("ADD COLUMN IF NOT EXISTS DISPOSITION"),
                "🛑 V8 必须给 verdict 补 disposition 列（组合出口的落点，PRD §C.1.7 L1794）");
        // ④ disposition 值域 CHECK 的五个字面
        for (Disposition d : Disposition.values()) {
            assertTrue(sql.contains("'" + d.dbLabel() + "'"),
                    "🛑 V8 里找不到 disposition 字面 '" + d.dbLabel() + "'");
        }
        // ⑤ risk_flag 值域 CHECK 的四个字面
        for (RiskFlag r : RiskFlag.values()) {
            assertTrue(sql.contains("'" + r.dbLabel() + "'"),
                    "🛑 V8 里找不到 risk_flag 字面 '" + r.dbLabel() + "'");
        }
        // ⑥ 🛑 不得自动补值（回滚说明里明写 DBA 手工执行）
        assertFalse(upper.contains("UPDATE CYCLE_ASSESSMENT SET VERDICT ="),
                "🛑 V8 不得自动把 null 的 verdict 补成某个值 —— 那会把"
                        + "『尚未判定』静默改写成一条已作出的结论（且不可覆盖）");
    }

    @Test
    @DisplayName("🛑 CycleAssessmentRow：样本不足时 as_value 必须为 null（不得填 0）")
    void cycle_row_forbids_zero_as_value_when_sample_insufficient() {
        BizException e = assertThrows(BizException.class, () -> new CycleAssessmentRow(
                TENANT_FREE_ID, TENANT_FREE_ID, 1, BigDecimal.ZERO,
                "{}", "{}", 6, VerdictBranch.HUMAN_REVIEW, null,
                AdherenceState.SAMPLE_INSUFFICIENT, null, "{}", "v1", null,
                Instant.now(), "u"));
        assertTrue(e.getMessage().contains("样本不足"),
                "🛑 样本不足是『没算出来』而非『算出来是 0』。"
                        + "把它填成 0 会让『数据不够』静默变成『客户没配合』。"
                        + "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("🛑 CycleAssessmentRow：sequence_no 必须 ≥ 1（与 V5 CHECK 同口径，报错指向构造点）")
    void cycle_row_requires_positive_sequence() {
        for (int bad : List.of(0, -1)) {
            BizException e = assertThrows(BizException.class, () -> new CycleAssessmentRow(
                    TENANT_FREE_ID, TENANT_FREE_ID, bad, BigDecimal.ONE,
                    "{}", "{}", 14, VerdictBranch.STABLE, EffectVerdict.E3_STABLE,
                    AdherenceState.PASS, null, "{}", "v1", null,
                    Instant.now(), "u"));
            assertTrue(e.getMessage().contains("构造"),
                    "拒绝理由应点明报错指向构造点（而不是数据库的 CHECK 约束名）。"
                            + "实际: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("🛑 CycleAssessmentRow：两个『不可覆盖』字段（快照 / 阈值版本）缺任一即抛")
    void cycle_row_requires_snapshot_and_threshold_version() {
        assertThrows(BizException.class, () -> new CycleAssessmentRow(
                TENANT_FREE_ID, TENANT_FREE_ID, 1, BigDecimal.ONE,
                "{}", null, 14, VerdictBranch.STABLE, EffectVerdict.E3_STABLE,
                AdherenceState.PASS, null, "{}", "v1", null, Instant.now(), "u"),
                "metric_snapshot 是 PRD §C.1.9 硬约束②的落点（可回放），不得为空");
        assertThrows(BizException.class, () -> new CycleAssessmentRow(
                TENANT_FREE_ID, TENANT_FREE_ID, 1, BigDecimal.ONE,
                "{}", "{}", 14, VerdictBranch.STABLE, EffectVerdict.E3_STABLE,
                AdherenceState.PASS, null, "{}", "  ", null, Instant.now(), "u"),
                "threshold_version 是硬约束③的落点（版本可追溯）—— "
                        + "且空白串与缺失同义（空白会在库里落成一个看起来有值的空串）");
    }

    // ==================================================================
    // 四、表结构缺口（库是真相源）
    // ==================================================================

    @Test
    @DisplayName("🛑 V5 的两张表都【没有】disposition 列 —— 登记缺口而不是假装它存在")
    void v5_tables_have_no_disposition_column() throws IOException {
        assertTrue(Files.exists(MIGRATION_V5), "V5 迁移文件不存在: " + MIGRATION_V5);
        String sql = Files.readString(MIGRATION_V5, StandardCharsets.UTF_8);

        // 🛑 必须先剥 SQL 注释：V5 的 verdict 表体里有一行
        //    `-- §2.19: 组合出口（三 enum × → disposition）` —— 那是一句【说明】，
        //    不是列定义。不剥离注释的话，这条断言会在"文档提到它"上红，
        //    而修法会变成"删掉那句有用的注释"而不是"确认列到底有没有"。
        //    （这条断言第一次运行时就是这样红的 —— 它抓的正是本方法自己的疏漏。）
        String cycleTable = stripSqlComments(extractCreateTable(sql, "cycle_assessment"));
        String verdictTable = stripSqlComments(extractCreateTable(sql, "verdict"));

        assertFalse(hasColumn(cycleTable, "disposition"),
                "🛑 cycle_assessment 里出现了 disposition 列 —— 若真加了，"
                        + "请同步更新 Disposition.SCHEMA_GAP_NOTE（它把这个缺口写进了每一条依据快照）");
        assertFalse(hasColumn(verdictTable, "disposition"),
                "🛑 V5 的 verdict 表里出现了 disposition 列 —— 本缺口由 **V8** 闭合"
                        + "（V5 保持原样不动，这是迁移的既定纪律：已发布迁移不得改写）。"
                        + "若确实在 V5 里加了，请同步更新 Disposition.SCHEMA_GAP_NOTE 的描述");
    }

    @Test
    @DisplayName("🛑 V5 的 risk_flag 确实【无 CHECK】（历史事实），缺口已由 V8 闭合 —— 两侧各守一段")
    void v5_risk_flag_has_no_check_but_others_do() throws IOException {
        String sql = Files.readString(MIGRATION_V5, StandardCharsets.UTF_8);
        String verdictTable = extractCreateTable(sql, "verdict");

        // 🛑 本断言守的是【历史事实】：已发布迁移不得改写，故 V5 永远保持无 CHECK。
        //    "缺口是否已被补上"由 v8_risk_flag_check_is_present_and_matches_enum_labels 守护 ——
        //    两者缺一不可：只留本条的话，删掉 V8 第 4 节不会有任何测试变红。
        String riskLine = findLine(verdictTable, "risk_flag");
        assertFalse(riskLine.toUpperCase(Locale.ROOT).contains("CHECK"),
                "🛑 V5 的 risk_flag 出现了 CHECK —— V5 是已发布迁移，按既定纪律【不得改写】。"
                        + "N-1 的缺口由 **V8** 闭合（见 v8_risk_flag_check_is_present_and_matches_enum_labels）。"
                        + "若确实要在 V5 里改，请先确认是否违反『已发布迁移不改写』纪律。"
                        + "实际行: " + riskLine);

        // 同表另三个枚举列必须有 CHECK（证明这不是"整张表都没约束"）
        for (String col : List.of("branch", "effect_verdict", "adherence_state")) {
            String line = findLine(verdictTable, col);
            assertTrue(line.toUpperCase(Locale.ROOT).contains("CHECK"),
                    "🛑 " + col + " 应有 CHECK —— 它是 risk_flag 那个缺口的【对照组】："
                            + "若四个枚举列都没 CHECK，那是『表整体宽松』；"
                            + "只有 risk_flag 没有，才是『这一列的防线不在库层』。实际行: " + line);
        }

        // 对照组的另一半：visible_to_customer 有一条恒 false 的 CHECK
        assertTrue(verdictTable.contains("visible_to_customer = FALSE"),
                "🛑 visible_to_customer 的恒 false CHECK 是『结论不对外直出』的库层落点（Q10 口径②）");
    }

    /**
     * <b>N-1 收口</b>：V5 的 {@code risk_flag} 缺口<b>已由 V8 闭合</b>，
     * 本断言把"库层已成第二道闸"从注释变成会被守护的事实。
     *
     * <h2>🛑 为什么必须在 V8 侧再断言一次，而不是只留 V5 那条</h2>
     * V5 那条断言的是「<b>V5 里</b> risk_flag 无 CHECK」—— 它是<b>历史事实</b>的守护
     * （已发布迁移不得改写，故 V5 永远保持无 CHECK）。若只有那一条，
     * <b>"缺口是否真的被补上"这件事在仓库里没有任何断言</b>：
     * 有人删掉 V8 的第 4 节，V5 那条照样全绿，而 risk_flag 会<b>静默退回</b>
     * "唯一防线在应用层"的形态 —— 且没有任何测试会红。
     * 故 V5 守"历史"，V8 守"现状"，两者缺一不可。
     *
     * <h2>🛑 断言三件事（值域 / 可空性 / 字面与枚举逐字一致）</h2>
     * <ol>
     *   <li>CHECK 约束<b>确实存在</b>（含约束名，便于真库对账）；</li>
     *   <li>形如 {@code risk_flag IS NULL OR risk_flag IN (...)} ——
     *       {@code IS NULL} 这一支<b>不得</b>被删：{@code null} 表示"未录入"，
     *       是合法业务态（未录入 ⇒ 路由落 D5），删掉它会让"尚未录入"变成库层不可能，
     *       从而把 D5 那一支重新夹死（与 A-4 缺口同型）；</li>
     *   <li>四个字面与 {@link RiskFlag#allDbLabels()} <b>逐字一致</b> ——
     *       若将来 D4 词表扩项而 V8 没跟上，写入会 23514，
     *       而报错里只有约束名，看不出是枚举漂了。</li>
     * </ol>
     */
    @Test
    @DisplayName("🛑 N-1：V8 已为 risk_flag 补上 CHECK（IS NULL 或 4 值），且字面与枚举逐字一致")
    void v8_risk_flag_check_is_present_and_matches_enum_labels() throws IOException {
        assertTrue(Files.exists(MIGRATION_V8), "V8 迁移文件不存在: " + MIGRATION_V8);
        String sql = Files.readString(MIGRATION_V8, StandardCharsets.UTF_8);
        String upper = sql.toUpperCase(Locale.ROOT);

        // ① 约束确实存在（点名约束名，便于真库 \d verdict 对账）
        assertTrue(upper.contains("VERDICT_RISK_FLAG_VALUES"),
                "🛑 V8 里找不到 verdict_risk_flag_values 约束 —— N-1 的缺口又回来了："
                        + "risk_flag 退回『唯一防线在应用层』，而 V5 那条断言不会红"
                        + "（它守的是 V5 的历史事实，不是现状）。");

        // ② 可空性：CHECK 必须含 `risk_flag IS NULL OR` —— null = 未录入，是合法业务态
        String checkBlock = extractRiskFlagCheck(sql);
        assertTrue(checkBlock.toUpperCase(Locale.ROOT).contains("RISK_FLAG IS NULL"),
                "🛑 V8 的 risk_flag CHECK 丢了『IS NULL』这一支 —— "
                        + "null 表示『未录入』（未录入 ⇒ 路由落 D5），是合法业务状态。"
                        + "删掉它会让『尚未录入』变成库层不可能，把 D5 那一支重新夹死"
                        + "（与 A-4 缺口同型）。实际 CHECK 块: " + checkBlock);

        // ③ 四个字面与枚举 allDbLabels 逐字一致（防词表扩项时 V8 没跟上）
        for (RiskFlag r : RiskFlag.values()) {
            assertTrue(sql.contains("'" + r.dbLabel() + "'"),
                    "🛑 V8 里找不到 risk_flag 字面 '" + r.dbLabel() + "' —— "
                            + "枚举与库 CHECK 分叉了，写入会 23514 而报错只有约束名。"
                            + "当前枚举全值: " + RiskFlag.allDbLabels());
        }

        // 反向自证：CHECK 里不得出现第五个不存在的字面（防"顺手多写一个"）
        int literals = 0;
        for (String label : RiskFlag.allDbLabels()) {
            if (checkBlock.contains("'" + label + "'")) {
                literals++;
            }
        }
        assertEquals(RiskFlag.allDbLabels().size(), literals,
                "🛑 CHECK 块里的字面数应恰为 " + RiskFlag.allDbLabels().size()
                        + "（枚举全值），实际 " + literals + " —— 多一个或少一个都说明两侧分叉。"
                        + "CHECK 块: " + checkBlock);
    }

    /** 提取 V8 里 {@code verdict_risk_flag_values} 的 CHECK 块（从约束名起至该语句分号止）。 */
    private static String extractRiskFlagCheck(String sql) {
        Matcher m = Pattern.compile(
                ".*verdict_risk_flag_values.*?CHECK\\s*\\((.*?)\\)\\s*;",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(m.find(), "🛑 无法从 V8 提取 risk_flag 的 CHECK 块 —— 格式变了，"
                + "本断言的提取正则需同步（提取失败会让后续断言假绿）");
        return m.group(1);
    }

    @Test
    @DisplayName("🛑 V5 的两个枚举列 CHECK 的字面与枚举 allDbLabels 逐字一致")
    void v5_check_literals_match_enum_labels() throws IOException {
        String sql = Files.readString(MIGRATION_V5, StandardCharsets.UTF_8);
        Set<String> enumLabels = new LinkedHashSet<>(VerdictBranch.allDbLabels());

        // 在 V5 里逐个找 5 个字面
        for (String label : enumLabels) {
            assertTrue(sql.contains("'" + label + "'"),
                    "🛑 V5 里找不到分支字面 '" + label + "' —— 枚举与库 CHECK 分叉了。"
                            + "写入会得到 23514，而报错里只有约束名，看不出是枚举漂了");
        }
        // effect_verdict 的 5 个 E 字面
        for (EffectVerdict e : EffectVerdict.values()) {
            assertTrue(sql.contains("'" + e.label() + "'"),
                    "🛑 V5 里找不到 effect_verdict 字面 '" + e.label() + "'");
        }
        // adherence_state 的 3 个字面
        for (AdherenceState s : AdherenceState.values()) {
            assertTrue(sql.contains("'" + s.label() + "'"),
                    "🛑 V5 里找不到 adherence_state 字面 '" + s.label() + "'");
        }
    }

    @Test
    @DisplayName("🛑 缺口登记：PRD 对 disposition 的归属与 V5 建表不一致（L1787 vs L1794）")
    void prd_disposition_gap_is_registered() throws IOException {
        // Disposition.SCHEMA_GAP_NOTE 必须真的写明这个缺口（它会被写进每一条依据快照）
        String note = Disposition.SCHEMA_GAP_NOTE;
        assertNotNull(note);
        assertFalse(note.isBlank());
        for (String must : List.of("refund", "verdict", "未建列")) {
            assertTrue(note.contains(must),
                    "🛑 SCHEMA_GAP_NOTE 应含 '" + must + "'（它要能独立说清缺口）。实际: " + note);
        }

        // PRD 里确实有两处不同的归属（这是缺口的证据链，不是我们的臆测）
        if (Files.exists(PRD)) {
            String prd = Files.readString(PRD, StandardCharsets.UTF_8);
            assertTrue(prd.contains("refund.disposition"),
                    "PRD 应有一处把 disposition 挂在 refund（§C.1.7 L1787 一带）—— "
                            + "找不到说明 PRD 被改过，需重新核定缺口");
            assertTrue(prd.contains("verdict") && prd.contains("disposition"),
                    "PRD 应有一处把 disposition 挂在 verdict（§C.1.7 L1794 一带）");
        }
    }

    // ==================================================================
    // 五、可空枚举解析的 fail-closed（缺口 ② 的应用层防线，真的被执行）
    // ==================================================================

    /**
     * 🛑 这一节是<b>反向验证逼出来的</b>，不是为了凑覆盖率。
     *
     * <p>{@link VerdictLedger} 里有三个"读回时可空枚举"的解析方法
     * （{@code parseEffectOrNull} / {@code parseAdherenceOrNull} / {@code parseRiskOrNull}），
     * 它们是缺口 ② 唯一被逐字写在注释里的应用层防线。但在本节存在之前，
     * <b>没有任何测试执行过它们</b> —— 上面的
     * {@link #v5_risk_flag_has_no_check_but_others_do} 读的是 V5 的 SQL 文本，
     * 而不是 Ledger 的 Java 代码。
     *
     * <p>实测证据：把 {@code parseRiskOrNull} 的空值分支从 {@code null} 改成
     * {@code RiskFlag.NONE}（即把"未登记标签"静默改写为"无风险"），跑全部判定链测试
     * <b>仍然全绿</b>。这是一个恒真断言 —— 它守着一个没有测试守着的缺口。
     */
    @Test
    @DisplayName("🛑 读回侧三个可空枚举解析：null 原样为 null（不得交给 parse 去炸）")
    void nullable_enum_readback_maps_null_to_null() {
        // 挂起态（D5）是合法形态：effect/adherence/risk 都可能为空。
        // 若这里直接调 XxxEnum.parse(null)，会把一条完全正常的挂起判定读回时炸掉。
        assertNull(VerdictLedger.parseEffectOrNull(null),
                "effect_verdict 为空是挂起态的正常形态，必须原样返回 null —— "
                        + "交给 EffectVerdict.parse(null) 会抛（它视 null 为『必填未给』）");
        assertNull(VerdictLedger.parseAdherenceOrNull(null),
                "adherence_state 为空是挂起态的正常形态，必须原样返回 null");
        assertNull(VerdictLedger.parseRiskOrNull(null),
                "🛑 risk_flag 为空必须原样返回 null（缺口 ② 的核心）—— 落成 RiskFlag.NONE "
                        + "会把『这单没录风险标签』变成『这单无风险』，直接使 D4 该触发而不触发");
    }

    @Test
    @DisplayName("🛑 空白串与 null 同等对待（不得因空白串落进 parse 而炸）")
    void blank_readback_normalizes_to_null() {
        for (String blank : List.of("", "   ", "\t")) {
            assertNull(VerdictLedger.parseEffectOrNull(blank), "空白串 effect_verdict: " + blank);
            assertNull(VerdictLedger.parseAdherenceOrNull(blank), "空白串 adherence_state: " + blank);
            assertNull(VerdictLedger.parseRiskOrNull(blank), "空白串 risk_flag: " + blank);
        }
    }

    @Test
    @DisplayName("🛑 risk_flag 读回不得回落成 NONE（缺口 ② 的唯一防线）")
    void risk_flag_readback_never_falls_back_to_none() {
        // 正向对照：登记的四个标签必须原样读回（否则"不得回落"会因为全都抛而为真）
        assertEquals(RiskFlag.NONE, VerdictLedger.parseRiskOrNull("无"),
                "登记的『无』必须读回 NONE");
        assertEquals(RiskFlag.HIGH_RISK, VerdictLedger.parseRiskOrNull("高危"));
        assertEquals(RiskFlag.NEW_ONSET, VerdictLedger.parseRiskOrNull("新发"));
        assertEquals(RiskFlag.COMORBID, VerdictLedger.parseRiskOrNull("同病"));

        // 🛑 反面：未知字面必须【抛】。回落成 NONE 会把一个未登记的临床标签
        //    静默改写为"无风险"，从而让 D4（全面评估）该触发而不触发。
        for (String unknown : List.of("极高危", "NONE", "none", "新发？")) {
            BizException e = assertThrows(BizException.class,
                    () -> VerdictLedger.parseRiskOrNull(unknown),
                    "🛑 risk_flag 读回遇未知字面 '" + unknown + "' 必须抛 —— "
                            + "库层对 risk_flag 没有 CHECK（见 v5_risk_flag_has_no_check_but_others_do），"
                            + "读回时解析是唯一防线。回落成 NONE 会让『库里有脏数据』"
                            + "静默变成『这单没有风险标签』，两者在稽核视角下完全不同");
            assertTrue(e.getMessage().contains("4 值枚举"),
                    "拒绝理由要点明值域。实际: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("🛑 读回侧另两个枚举同样 fail-closed（不是只有 risk_flag 特殊对待）")
    void effect_and_adherence_readback_are_fail_closed() {
        assertEquals(EffectVerdict.E1_SIGNIFICANT, VerdictLedger.parseEffectOrNull("E1显著改善"));
        for (String unknown : List.of("E9", "显著改善", "E1")) {
            assertThrows(BizException.class, () -> VerdictLedger.parseEffectOrNull(unknown),
                    "未知 effect_verdict 字面必须抛: " + unknown);
        }
        assertEquals(AdherenceState.PASS, VerdictLedger.parseAdherenceOrNull("达标"));
        for (String unknown : List.of("已达标", "PASS")) {
            assertThrows(BizException.class, () -> VerdictLedger.parseAdherenceOrNull(unknown),
                    "未知 adherence_state 字面必须抛: " + unknown);
        }
    }

    /**
     * 入站侧（控制器）的风险标签解析同样 fail-closed。
     *
     * <p>走源码扫描而非反射调用，因为 {@code VerdictController.parseRiskOrNull}
     * 是 private static 且只被 {@code createVerdict} 的分支使用；反射调用需要
     * 构造完整的 {@code CreateVerdictRequest}，代价与脆弱性都高于其价值。
     * 断言的是那个<b>能证伪的机械事实</b>：代码里不出现 {@code RiskFlag.NONE}
     * 这个枚举常量 ⇒ 未传就不是"无风险"，而是"未定"。
     */
    @Test
    @DisplayName("🛑 入站侧 risk_flag：未传 ⇒ 未定，不得擦成『无』")
    void inbound_risk_flag_is_null_not_none() throws IOException {
        assertTrue(Files.exists(CONTROLLER), "控制器源码不存在: " + CONTROLLER);
        String src = stripCommentsAndStrings(
                Files.readString(CONTROLLER, StandardCharsets.UTF_8));

        // 自证：扫描器确实看到了那个方法（否则下面的"不出现 NONE"是空断言）
        assertTrue(src.contains("private static RiskFlag parseRiskOrNull(String raw)"),
                "扫描器应能在控制器里看到 parseRiskOrNull —— 找不到说明扫描失效，"
                        + "而不是说明代码正确");

        assertFalse(src.contains("RiskFlag.NONE"),
                "🛑 控制器里出现 RiskFlag.NONE —— 唯一的合法位置是『调用方显式传了「无」』，"
                        + "而那也不需要写常量（RiskFlag.parse(『无』) 就得到它）。"
                        + "此处出现常量，几乎必然是给『未传』兜了底："
                        + "把『没录风险标签』擦成『无风险标签』，直接使 D4 该触发而不触发。"
                        + "入参缺失的正确语义是『未定』（null ⇒ 路由进 D5 挂起）");
    }

    /**
     * 入站侧的同源状态解析：不得把"不可比"静默当成"同源"。
     *
     * <p>{@code s} 是置信度合成式的乘性因子，且"不可比"是一票否决并强制转人工的因子
     * （PRD §7.3 D5）。若缺省或被兜成 {@code SAME_ORIGIN}，一次"测的东西不一样"
     * 会被当成"同源可比"，从而给出一个不该给出的置信度与结论。
     */
    @Test
    @DisplayName("🛑 入站侧 same_origin_status：不得回落 SAME_ORIGIN（兜底把不可比当同源）")
    void inbound_same_origin_status_never_defaults_to_same_origin() throws IOException {
        String src = stripCommentsAndStrings(
                Files.readString(CONTROLLER, StandardCharsets.UTF_8));

        // 自证：扫描器看到了这条入站解析链路
        assertTrue(src.contains("sameOriginStatus"),
                "扫描器应能看到 same_origin_status 的入站解析 —— 找不到说明扫描失效");

        assertFalse(src.contains("SameOriginStatus.SAME_ORIGIN"),
                "🛑 控制器里出现 SameOriginStatus.SAME_ORIGIN 枚举常量 —— "
                        + "该值的唯一合法来源是 valueOf(入参)，写常量即是在缺省/异常时兜底。"
                        + "把『不可比』兜成『同源』等于放行一个一票否决因子");

        // 正面：未知取值必须抛（而不是静默取某个默认）
        assertTrue(src.contains("IllegalArgumentException"),
                "🛑 入站 same_origin_status 的未知取值必须被 catch 后抛 BizException —— "
                        + "没有这个 catch 分支，说明有人在未知值上直接取了默认");
        assertTrue(src.contains("不得回落为 SAME_ORIGIN"),
                "拒绝理由应逐字记下『不得回落为 SAME_ORIGIN』，便于复盘时看清拦截的是什么");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 统计子串出现次数（用于"改写字面量恰好 N 个"这类计数断言）。 */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    /**
     * 抽出仓储源码里那<b>唯一</b>一条 UPDATE 语句的<b>拼接后 SQL 文本</b>。
     *
     * <p>🛑 Java 里的 SQL 是多段字符串字面量用 {@code +} 拼起来的，故必须
     * 「取到语句终止分号 → 去掉引号与 {@code +} → 合并空白」，否则拿到的只是第一段
     * （{@code "UPDATE cycle_assessment"}），SET 子句会是空的 —— 那样
     * 「SET 列白名单」这条断言就退化成了一个恒真断言。
     */
    private static String extractUpdateStatement(String src) {
        int start = src.toUpperCase(Locale.ROOT).indexOf("UPDATE ");
        assertTrue(start >= 0, "仓储源码里找不到 UPDATE 语句");
        assertTrue(countOccurrences(src.toUpperCase(Locale.ROOT), "UPDATE ") == 1,
                "本方法假定仓储里恰好有一条 UPDATE —— 多于一条时请显式表态（见端口方法数断言）");

        int end = src.indexOf(';', start);
        assertTrue(end > start, "找不到 UPDATE 语句的终止分号");
        String raw = src.substring(start, end);
        // 去掉 Java 字符串引号与拼接符，再把连续空白合成单个空格
        return raw.replace("\"", "").replace("+", " ").replaceAll("\\s+", " ").trim();
    }

    /** 抽出一段 SQL 里的行注释（{@code -- ...}），使"提及某列"不被误判为"定义某列"。 */
    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.split("\n", -1)) {
            int idx = line.indexOf("--");
            out.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return out.toString();
    }

    /**
     * 该段 SQL 里是否<b>真的定义</b>了这一列（列名出现在行的起始位置）。
     *
     * <p>🛑 不能用 {@code contains(col)}：{@code disposition} 会命中
     * {@code visible_to_customer} 之外的各种提及，也会命中别的列名里含该词的情形。
     * 判据是"这一行以列名开头"，那才是列定义。
     */
    private static boolean hasColumn(String tableBody, String column) {
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(column) + "\\s+\\S", Pattern.MULTILINE);
        return p.matcher(tableBody).find();
    }

    /** 抽出 {@code CREATE TABLE IF NOT EXISTS <name> ( ... );} 的表体。 */
    private static String extractCreateTable(String sql, String table) {
        Pattern p = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS\\s+" + Pattern.quote(table) + "\\s*\\((.*?)\\n\\);",
                Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        assertTrue(m.find(), "V5 里找不到 " + table + " 的建表语句");
        return m.group(1);
    }

    /** 找含某列名的第一行（列定义行）。 */
    private static String findLine(String tableBody, String column) {
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(column) + "\\s+.*$", Pattern.MULTILINE);
        Matcher m = p.matcher(tableBody);
        if (!m.find()) {
            throw new AssertionError("在表体里找不到列 " + column);
        }
        return m.group();
    }

    /**
     * 剥离 Java 注释与字符串字面量 —— 使"提及 SQL 关键字"不被误判为"使用"。
     *
     * <p>🛑 这一步是必须的：本类的类注释与 {@code VerdictLedger} 的注释里都<b>提到</b>
     * {@code UPDATE} / {@code DELETE} / {@code ON CONFLICT}（正是为了说明它们为何不存在）。
     * 不剥离注释的话，那条断言会在自己的说明文字上红 ——
     * 而"因为注释写了不许做的事就报错"会让下一个人删掉注释而不是修问题。
     */
    private static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            // 行注释
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            // 字符/字符串字面量：保留内容（SQL 就写在字符串里！），只跳过转义
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i)).append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append(quote);
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}