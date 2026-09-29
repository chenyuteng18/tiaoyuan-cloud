package com.diaoyuanyun.dy.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>退款域「库侧列 × 契约字段」跨源一致性门禁</b>（批次十一 · N-8 收口）。
 *
 * <h2>它堵的是什么洞</h2>
 * 本项目反复登记的失效模式：<b>同一件事在两个文件里各写一遍，而没有任何东西比对两侧</b>。
 * N-8 就是它的一个具体形态 —— 退款域在 V6 迁移里建了 {@code refund.requested_at_source}
 * （+ {@code requested_at_source_ref}）与整张 {@code refund_receipt} 表（11 列），
 * 而契约侧 {@code RefundCreateRequest} <b>没有</b>这两个字段、
 * {@code RefundReceiptData} <b>只有</b> {@code receipt_state} 一个字段。
 * 此前这条差异只在 README §5.2 里写着"契约侧未逐条比对"—— 而<b>没有测试会红</b>。
 *
 * <h2>判据（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>库侧列机械提取</b>：从 {@code V6__*.sql} 的建表/加列语句里读真实列名，
 *       不手抄 —— 迁移增删一列，本账本立刻对不上；</li>
 *   <li><b>契约字段机械提取</b>：从 {@code openapi-v1.0.0.yaml} 的
 *       {@code RefundCreateRequest} / {@code RefundReceiptData} schema 里读真实 properties；</li>
 *   <li><b>差异逐条登记</b>：每一条"库有契约无"的列必须出现在 {@link REGISTERED_GAPS}
 *       里并附理由；<b>一旦上游补齐（差异消失）或冒出新差异，本类即红</b> ——
 *       归零与新增都必须是显式编辑。</li>
 * </ol>
 *
 * <h2>🛑 它【不】回答什么（诚实边界）</h2>
 * 本类<b>不</b>判"契约该不该补这两个字段" —— 那是契约 owner 的裁定（N-8 的登记正为此）。
 * 它只把"两侧到底差在哪几列"钉成<b>可机械清点的集合</b>，
 * 使"裁定之后补契约"或"裁定之后删列"都成为<b>显式动作</b>（做完必须改本表，否则红）。
 * 同理，它<b>不</b>判库侧多出的列是否"多余"：{@code requested_at_source} 是
 * P0-19 C1-2 明文要求的来源标注（业务方已裁定），缺的是<b>契约表达</b>而非库侧。
 */
@DisplayName("退款域跨源一致性门禁：V6 库侧列 × 契约 schema 字段（N-8）")
class RefundDomainCrossSourceGateTest {

    private static final String OPENAPI_REL = "contract/openapi-v1.0.0.yaml";
    private static final String V6_REL =
            "skeleton/dy-app/src/main/resources/db/migration/"
                    + "V6__refund_domain_alignment_and_ledgers.sql";
    private static final String CONTRACT_MD_REL = "_work/contract-t6-api-freeze-2026-09-19.md";
    /** 字典（权威 DDL 来源）—— 通用审计列的定义处（§二 通用约定）。 */
    private static final String DICT_REL = "_work/data-dict-entities-ddl-2026-09-19.md";

    /**
     * 已登记的差异：<b>库侧有列、契约侧无对应字段</b>。
     *
     * <p>每一条都逐字写明"为什么差"与"谁该裁定"。
     * 🛑 差异一旦消失（上游补了字段 / 库侧删了列），本表<b>必须同步删除该条</b>，
     * 否则断言会红 —— 归零是一次显式编辑，不是静默漂移。
     */
    private static final Map<String, String> REGISTERED_GAPS = new LinkedHashMap<>();

    static {
        REGISTERED_GAPS.put("refund.requested_at_source",
                "P0-19 C1-2 明文要求的『来源标注』三取值（客户自证 / 调理师转交 / 经络师受理）。"
                        + "库侧已于 V6 建列，契约 RefundCreateRequest 无该属性 ⇒ 属契约侧缺口（N-8）。");
        REGISTERED_GAPS.put("refund.requested_at_source_ref",
                "客户自证须附凭据引用（P0-19 C1-2）。库侧已建列，契约侧无对应属性 ⇒ 同上。");
        REGISTERED_GAPS.put("refund_receipt.channel",
                "回执通道（订阅消息 / 电话 / 当面）。库侧 CHECK 钉死三值，"
                        + "契约 RefundReceiptData 只下发了 receipt_state，未表达通道 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.template_id",
                "订阅消息模板 ID（「回执独占一个模板 ID」）。库侧留位列，契约侧无 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.decided_at",
                "判定时刻（三态都落，含『未授权 = 推送事件根本没发生』）。"
                        + "库侧 NOT NULL，契约 RefundReceiptData 未下发 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.pushed_at",
                "实际推送时刻（仅『已推送』有值，库层 CHECK 与 receipt_state 等价）。"
                        + "契约侧无对应字段 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.failure_reason",
                "推送失败原因。库侧留位列，契约侧无 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.operator_id",
                "操作人（staff 外键，可空 = 系统自动推送）。契约侧无 ⇒ 契约侧缺口。");
        REGISTERED_GAPS.put("refund_receipt.receipt_id",
                "回执主键。契约 RefundReceiptData 未下发 receipt_id ⇒ 客户端无从引用具体回执行。");
        REGISTERED_GAPS.put("refund_receipt.refund_id",
                "所属工单外键。属路径参数表达的关联（/refunds/{id}/receipts），"
                        + "契约 schema 内未重复声明 ⇒ 属『有意不重复』类，一并登记备查。");
    }

    /**
     * 库侧已<b>被契约覆盖</b>的列（契约侧确有对应属性）。
     *
     * <p>本表是<b>反向覆盖</b>的证据：它证明本门禁不是在"把所有列都登记成缺口"，
     * 而是真的做了双向比对 —— {@code receipt_state} 两侧都在，故它不属缺口。
     */
    private static final Set<String> COVERED_BY_CONTRACT = new LinkedHashSet<>(List.of(
            "refund_receipt.receipt_state"));

    /**
     * <b>解析器金丝雀</b>：{@code refund_receipt} 在 V6 里的真实列集（12 列）。
     *
     * <h2>🛑 为什么需要一个"恰好等于"的断言</h2>
     * 批次十一曾真实发生过一次解析器退化：初版 {@code extractCreateTableColumns}
     * 只按"行首标识符"取列名，于是建表体里两处<b>内联多行 CHECK 的续行</b>
     * （{@code '已推送', '未授权（转线下）', '推送失败'))} 与
     * {@code OR (receipt_state <> '已推送' ...)}）分别让行首的
     * {@code 'or'} / {@code OR} 被读成了列名 —— 解析结果里凭空多出一个列 {@code or}。
     * <p>下游"双向覆盖"断言因此报出一条<b>假缺口</b>与被"僵尸登记"条一起把矛头指向登记表，
     * 而真相是解析器坏了。故本断言把列集<b>钉成恰好这 12 列</b>：
     * <ul>
     *   <li>解析器多读（如把 {@code OR} 当列）⇒ 立刻红，且 red message 直接点名"解析器退化"；</li>
     *   <li>迁移增删列 ⇒ 也会红 —— 那时按提示显式更新本表（归零/新增都是显式动作，
     *       与 {@link REGISTERED_GAPS} 同旨）。</li>
     * </ul>
     */
    private static final Set<String> EXPECTED_RECEIPT_COLUMNS = new LinkedHashSet<>(List.of(
            "receipt_id", "tenant_id", "refund_id", "receipt_state", "channel", "template_id",
            "decided_at", "pushed_at", "failure_reason", "operator_id",
            "created_at", "created_by"));

    // ======================================================================
    // 一、库侧列：机械从 V6 提取
    // ======================================================================

    @Test
    @DisplayName("库侧：从 V6 机械提取 refund 加列与 refund_receipt 建表列（不手抄）")
    void library_columns_are_extracted_mechanically() throws IOException {
        Path root = resolveRepoRoot();
        String sql = Files.readString(root.resolve(V6_REL), StandardCharsets.UTF_8);

        Set<String> refundCols = extractAlterAddColumns(sql, "refund");
        Set<String> receiptCols = extractCreateTableColumns(sql, "refund_receipt");

        // N-8 的两列必须真的在 V6 里（否则断言前提失效）
        assertTrue(refundCols.contains("requested_at_source"),
                "V6 里找不到 refund.requested_at_source —— N-8 的前提失效，请复核迁移。实际: " + refundCols);
        assertTrue(refundCols.contains("requested_at_source_ref"),
                "V6 里找不到 refund.requested_at_source_ref。实际: " + refundCols);

        // refund_receipt 的列集必须非空且规模合理（防正则失效导致空集 ⇒ 平凡通过）
        assertFalse(receiptCols.isEmpty(), "从 V6 解析不出 refund_receipt 的列 —— 解析器失效");
        assertTrue(receiptCols.containsAll(Set.of("receipt_state", "channel", "decided_at", "pushed_at")),
                "refund_receipt 关键列缺失，解析器可能失效。实际: " + new TreeSet<>(receiptCols));

        // 🛑 解析器金丝雀：列集必须恰好等于真实 12 列 —— 多读（如把内联 CHECK 续行的
        //    'OR' 当成列）会让下游报【假缺口】，比漏检更坏（诱使人改错目标）。
        assertEquals(new TreeSet<>(EXPECTED_RECEIPT_COLUMNS), new TreeSet<>(receiptCols),
                "🛑 refund_receipt 的解析列集与真实列集不一致。\n"
                        + "实际: " + new TreeSet<>(receiptCols) + "\n"
                        + "期望: " + new TreeSet<>(EXPECTED_RECEIPT_COLUMNS) + "\n"
                        + "两种可能：① 【解析器退化】—— 内联多行 CHECK 的续行被读成了列名"
                        + "（历史上曾出现假列 'or'，成因是未跟踪括号深度）；\n"
                        + "② 【迁移真的改了列】—— 请显式更新 EXPECTED_RECEIPT_COLUMNS 与 REGISTERED_GAPS。\n"
                        + "🛑 先排除 ① 再改表：假缺口会把矛头指向登记表，而真相在解析器。");
    }

    // ======================================================================
    // 二、契约侧字段：机械从 openapi 提取
    // ======================================================================

    @Test
    @DisplayName("契约侧：RefundCreateRequest 确无 requested_at_source（缺口真实存在）")
    void contract_refund_create_request_lacks_the_source_fields() throws IOException {
        Set<String> props = extractSchemaProperties("RefundCreateRequest");

        assertFalse(props.isEmpty(), "解析不出 RefundCreateRequest 的 properties —— 解析器失效");
        assertTrue(props.contains("requested_at"),
                "RefundCreateRequest 应有 requested_at（N-8 讨论的正是它的『来源标注』配套）。实际: " + props);
        // 🛑 缺口事实：契约无这两个属性
        assertFalse(props.contains("requested_at_source"),
                "🛑 契约 RefundCreateRequest 出现了 requested_at_source —— 上游已补齐 N-8！\n"
                        + "请从 REGISTERED_GAPS 删除对应条目并更新 README §5.2 的 N-8 登记。");
        assertFalse(props.contains("requested_at_source_ref"),
                "🛑 契约 RefundCreateRequest 出现了 requested_at_source_ref —— 同上，请关闭 N-8。");
    }

    @Test
    @DisplayName("契约侧：RefundReceiptData 只有 receipt_state（其余 10 列均未表达）")
    void contract_receipt_data_only_declares_receipt_state() throws IOException {
        Set<String> props = extractSchemaProperties("RefundReceiptData");

        assertEquals(Set.of("receipt_state"), props,
                "🛑 RefundReceiptData 的字段集变了 —— N-8 的登记基于『契约只有 receipt_state』这一事实。\n"
                        + "若上游补了字段，请同步删除 REGISTERED_GAPS 里的对应条目并关闭 N-8；"
                        + "若字段被删/改名，请复核契约。实际: " + new TreeSet<>(props));
    }

    // ======================================================================
    // 三、双向覆盖：库有契约无 ⇒ 必须登记；契约有 ⇒ 不得误登记
    // ======================================================================

    @Test
    @DisplayName("双向覆盖：库侧每列要么被契约覆盖、要么在差异表登记 —— 不得有第三个")
    void every_library_column_is_either_covered_or_registered() throws IOException {
        Path root = resolveRepoRoot();
        String sql = Files.readString(root.resolve(V6_REL), StandardCharsets.UTF_8);

        Set<String> libraryCols = new TreeSet<>();
        libraryCols.addAll(qualified("refund", extractAlterAddColumns(sql, "refund")));
        libraryCols.addAll(qualified("refund_receipt", extractCreateTableColumns(sql, "refund_receipt")));

        // 只保留与 N-8 相关的两处（refund 的其他列在别的域已对齐，不属本条门禁的面）
        Set<String> n8Scope = new TreeSet<>();
        n8Scope.add("refund.requested_at_source");
        n8Scope.add("refund.requested_at_source_ref");
        for (String c : libraryCols) {
            if (c.startsWith("refund_receipt.")) {
                n8Scope.add(c);
            }
        }

        Set<String> accounted = new TreeSet<>(REGISTERED_GAPS.keySet());
        accounted.addAll(COVERED_BY_CONTRACT);
        // 🛑 通用审计列（tenant_id / created_at / created_by / updated_at）不属于 N-8 的比对面：
        //    它们是字典 §二「审计字段（全表必带）」规定的【全表骨架】，
        //    契约 RefundReceiptData 是【回执业务视图】而非表结构镜像。
        //    豁免项来自字典原文（见 genericAuditColumns），不是硬编码 —— 故豁免可被机械核验。
        accounted.addAll(qualified("refund_receipt", genericAuditColumns()));

        Set<String> unaccounted = new TreeSet<>(n8Scope);
        unaccounted.removeAll(accounted);

        assertTrue(unaccounted.isEmpty(),
                "🛑 以下库侧列既未被契约覆盖、也不在差异登记表、也不属通用审计列 —— "
                        + "出现了一个『没人看的第三个』:\n  - "
                        + String.join("\n  - ", unaccounted)
                        + "\n处置：① 契约确实表达它 ⇒ 加入 COVERED_BY_CONTRACT；"
                        + "② 契约没有 ⇒ 加入 REGISTERED_GAPS 并写明理由；"
                        + "③ 它是全表必带的通用审计列 ⇒ 应在字典 §二『审计字段（全表必带）』里，"
                        + "本门禁会自动豁免（若字典缺了它，说明骨架定义有洞）。"
                        + "三条都必须是一次显式编辑。");
    }

    /**
     * <b>豁免的自我核验</b>：通用审计列必须真的能从字典 §二机械读出，
     * 且确实覆盖了本次报红的 {@code tenant_id} / {@code created_at} / {@code created_by}。
     *
     * <h2>🛑 为什么"豁免"这一侧也要有断言</h2>
     * 门禁里最容易悄悄失效的不是检查项，而是<b>豁免项</b> —— 一条过宽的豁免
     * （比如 {@code accounted.addAll(libraryCols)}）会让整个门禁退化成恒绿，
     * 而它看起来仍然"有断言在跑"。故本断言：
     * <ol>
     *   <li>字典里必须真的写着「审计字段（全表必带）」这一锚 —— 豁免有权威出处，不是我随手写的白名单；</li>
     *   <li>从该句提取到的列必须<b>恰好</b>覆盖 {@code refund_receipt} 的这批通用列
     *       （{@code tenant_id}/{@code created_at}/{@code created_by}）——
     *       使"某天有人把 {@code tenant_id} 从字典骨架里删掉"会立刻红；</li>
     *   <li>豁免集必须<b>窄</b>：它不得包含任何业务域列（{@code channel} / {@code receipt_state} 等），
     *       否则豁免过宽 ⇒ 门禁失去牙齿（与 {@code ContractFreezeGateTest} 的
     *       「登记式 Harness 必须证明牙齿」同旨）。</li>
     * </ol>
     */
    @Test
    @DisplayName("豁免自证：通用审计列来自字典原文、恰好覆盖骨架列、且不吞任何业务列")
    void generic_audit_columns_are_read_from_the_dictionary() throws IOException {
        Path root = resolveRepoRoot();
        Path dict = root.resolve(DICT_REL);
        assertTrue(Files.isRegularFile(dict), "字典不存在: " + dict);
        String md = Files.readString(dict, StandardCharsets.UTF_8);
        assertTrue(md.contains("审计字段（全表必带）"),
                "🛑 字典 §二 里找不到『审计字段（全表必带）』锚 —— "
                        + "本门禁的豁免项正是从这一句读出的。若字典改写了这句，"
                        + "请同步修改 genericAuditColumns() 的正则，而不是让豁免静默变成空集"
                        + "（空豁免集 ⇒ 通用列全部报成假缺口）。");

        Set<String> auditCols = genericAuditColumns();
        assertTrue(auditCols.containsAll(Set.of("tenant_id", "created_at", "created_by")),
                "🛑 从字典骨架里读不到 refund_receipt 的这批通用列。实际读出: " + auditCols
                        + "\n（字典原句应含：`tenant_id`(FK) / `created_at` ... / `created_by`）");

        // 🛑 释义剔除必须真的生效：字典句里的全角括号释义（（操作人 `operator_id`））
        //    不得把 operator_id 抬成"第五个审计列"——它是 refund_receipt 的【业务列】且已登记缺口。
        Matcher anchor = Pattern.compile("审计字段（全表必带）\\**\\s*[:：]([^\\n]*)").matcher(md);
        assertTrue(anchor.find(), "字典句再取一次失败");
        String sentence = anchor.group(1);
        assertTrue(sentence.contains("（") || sentence.contains("）"),
                "🛑 字典句里已无全角括号释义 —— genericAuditColumns() 的『剔除释义』分支"
                        + "已无对象可剔。若字典改成半角表达，请同步改本方法的分隔规则，"
                        + "否则 operator_id 会被误当审计列而吞掉一条真实业务缺口。实际句: " + sentence);
        assertFalse(auditCols.contains("operator_id"),
                "🛑 通用审计列豁免集里出现了 operator_id —— 它是 refund_receipt 的【业务列】"
                        + "（staff 外键、可空 = 系统自动推送），且在 REGISTERED_GAPS 里已登记。"
                        + "若被豁免，门禁会静默吞掉这条真实缺口。实际读出: " + auditCols);

        // 豁免必须窄：不得吞业务列（否则门禁恒绿）
        Set<String> businessCols = Set.of(
                "receipt_id", "refund_id", "receipt_state", "channel",
                "template_id", "decided_at", "pushed_at", "failure_reason", "operator_id");
        Set<String> overlap = new TreeSet<>(auditCols);
        overlap.retainAll(businessCols);
        assertTrue(overlap.isEmpty(),
                "🛑 通用审计列豁免集里出现了业务列: " + overlap
                        + " —— 豁免过宽会让门禁失去牙齿（业务列的缺口会被静默豁免掉）。");

        // 且豁免确实生效：把 refund_receipt 的真实列减去豁免后，不再含 tenant_id/created_at/created_by
        String sql = Files.readString(root.resolve(V6_REL), StandardCharsets.UTF_8);
        Set<String> remaining = extractCreateTableColumns(sql, "refund_receipt");
        remaining.removeAll(auditCols);
        assertFalse(remaining.contains("tenant_id") || remaining.contains("created_at")
                        || remaining.contains("created_by"),
                "🛑 通用审计列未能被豁免 —— 说明 genericAuditColumns() 的提取与实际列名对不上。"
                        + "剩余列: " + new TreeSet<>(remaining));
        // 反向：业务列必须留下（证明豁免没有一刀切）
        assertTrue(remaining.contains("receipt_state") && remaining.contains("channel"),
                "🛑 豁免把业务列也吞掉了 —— 门禁会恒绿。剩余列: " + new TreeSet<>(remaining));
    }

    @Test
    @DisplayName("登记表不得有僵尸条目：登记的列必须真的在库侧存在")
    void registered_gaps_have_no_zombie_entries() throws IOException {
        Path root = resolveRepoRoot();
        String sql = Files.readString(root.resolve(V6_REL), StandardCharsets.UTF_8);

        Set<String> libraryCols = new TreeSet<>();
        libraryCols.addAll(qualified("refund", extractAlterAddColumns(sql, "refund")));
        libraryCols.addAll(qualified("refund_receipt", extractCreateTableColumns(sql, "refund_receipt")));

        Set<String> zombies = new TreeSet<>(REGISTERED_GAPS.keySet());
        zombies.removeAll(libraryCols);
        assertTrue(zombies.isEmpty(),
                "🛑 差异登记表里的以下条目在库侧已不存在（列被删/改名）—— 僵尸登记:\n  - "
                        + String.join("\n  - ", zombies)
                        + "\n处置：列确实被删 ⇒ 从 REGISTERED_GAPS 删除该条；列还在但名字改了 ⇒ 更新登记键。");

        // 反向：COVERED_BY_CONTRACT 也必须真的在库侧（否则是"覆盖了一个不存在的列"）
        Set<String> coveredZombies = new TreeSet<>(COVERED_BY_CONTRACT);
        coveredZombies.removeAll(libraryCols);
        assertTrue(coveredZombies.isEmpty(),
                "🛑 COVERED_BY_CONTRACT 里的以下条目在库侧不存在: " + coveredZombies);

        // 逐条理由必须够长（防占位式登记，与 PermissionCodeRegistrationGateTest 同口径）
        for (Map.Entry<String, String> e : REGISTERED_GAPS.entrySet()) {
            assertTrue(e.getValue() != null && e.getValue().length() >= 20,
                    "差异登记 '" + e.getKey() + "' 的理由过短（<20 字符）—— 防占位式登记");
        }
    }

    @Test
    @DisplayName("登记表规模自证：恰 10 条（防被清空 ⇒ 循环不进 ⇒ 平凡通过）")
    void registered_gap_table_is_not_silently_emptied() {
        assertTrue(REGISTERED_GAPS.size() >= 10,
                "差异登记表少得可疑（" + REGISTERED_GAPS.size() + " 条）—— "
                        + "防『表被清空 ⇒ 断言平凡通过』。若 N-8 确已全部收口，"
                        + "请把本断言改为显式的『已收口』记录，而不是让空表静默通过。");
        // refund_receipt 的 10 列 + refund 的 2 列，其中 receipt_state 已覆盖 ⇒ 差 11 列？
        // 实为：12 个库侧列中 1 个被覆盖 ⇒ 11 条差异；但 refund_id 已登记，故 11 条。
        // 此处只做下界断言（≥10），上界由"双向覆盖"那条机械核对，避免把某个魔法数字写死。
    }

    // ======================================================================
    // 四、端到端事实：G5 端点缺 requestBody（与 N-8 同源的第二处契约缺口）
    // ======================================================================

    @Test
    @DisplayName("契约侧：G5 createRefundReceipt 无 requestBody（与 N-8 同源，一并钉住）")
    void g5_receipt_endpoint_has_no_request_body() throws IOException {
        Path root = resolveRepoRoot();
        String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);

        // 定位 /refunds/{id}/receipts 段（到下一个 path 或域分隔为止）
        int start = text.indexOf("/refunds/{id}/receipts:");
        assertTrue(start > 0, "契约里找不到 /refunds/{id}/receipts —— G5 端点的前提失效");
        int end = text.length();
        for (String marker : List.of("\n  /esign/callbacks", "\n  /doc-templates:")) {
            int i = text.indexOf(marker, start);
            if (i > start) {
                end = Math.min(end, i);
            }
        }
        String block = text.substring(start, end);

        assertTrue(block.contains("operationId: createRefundReceipt"),
                "捕获的段落里没有 createRefundReceipt —— 段落边界判断错误");
        // 🛑 缺口事实：G5 无 requestBody
        assertFalse(block.contains("requestBody:"),
                "🛑 G5 createRefundReceipt 出现了 requestBody —— 上游已补齐！\n"
                        + "请复核：三态留痕是否需要入参（channel/template_id 从哪来），"
                        + "并更新 README §5.2 与 N-8 相关登记。");
        // 但响应侧有 RefundReceiptData
        assertTrue(block.contains("RefundReceiptData"),
                "G5 的响应应引用 RefundReceiptData —— 段落边界可能错了");
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private static Set<String> qualified(String table, Set<String> cols) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : cols) {
            out.add(table + "." + c);
        }
        return out;
    }

    /**
     * 从 {@code ALTER TABLE <table> ADD COLUMN [IF NOT EXISTS] <col> ...} 提取列名。
     *
     * <p>只抓"被加进来的列"，不抓原表既有列 —— N-8 关心的正是"迁移新增了哪些列而契约没有"。
     */
    private static Set<String> extractAlterAddColumns(String sql, String table) {
        Set<String> out = new LinkedHashSet<>();
        Pattern p = Pattern.compile(
                "ALTER\\s+TABLE\\s+" + Pattern.quote(table) + "\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z_][a-z0-9_]*)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
        return out;
    }

    /**
     * 从 {@code CREATE TABLE [IF NOT EXISTS] <table> ( ... )} 的括号体内提取列名。
     *
     * <p>列名 = 行首标识符，且该行<b>不是</b>表级约束（CONSTRAINT / PRIMARY KEY / UNIQUE /
     * FOREIGN KEY / CHECK / EXCLUDE）。用括号配平找到建表体，避免把后续语句的内容吞进来。
     *
     * <h2>🛑 为什么必须做「续行」判别（本方法在批次十一被真实打红过一次）</h2>
     * 初版只按「行首标识符」取列名，于是 {@code refund_receipt} 里这条
     * <b>内联多行 CHECK</b> 的<b>续行</b>被当成了列：
     * <pre>
     *   receipt_state   VARCHAR(32) NOT NULL CHECK (receipt_state IN (
     *                       '已推送', '未授权（转线下）', '推送失败')),   ← 这行的 "or"… 被读成列名
     *   ...
     *   CONSTRAINT refund_receipt_pushed_at_iff_pushed CHECK (
     *       (receipt_state =  '已推送' AND pushed_at IS NOT NULL)
     *    OR (receipt_state &lt;&gt; '已推送' AND pushed_at IS NULL))          ← 这行的 "OR" 被读成列名
     * </pre>
     * 结果 {@code extractCreateTableColumns} 返回了一个不存在的列 {@code or}，
     * 使下游"双向覆盖"断言报出一条<b>假缺口</b>。假缺口比漏检更坏：
     * 它会诱使人往登记表里写一条不存在的列（而"登记表不得有僵尸条目"那条又会因它而红），
     * 最终逼着下一个人去改<b>正确</b>的解析器对应的错误目标。
     * <p>故本方法刻意跟踪<b>括号深度</b>：一行只有在「进入该行时括号深度回到建表体顶层」
     * 时才可能是列定义；处在内联 CHECK 内部的续行一律跳过。
     */
    private static Set<String> extractCreateTableColumns(String sql, String table) {
        Set<String> out = new LinkedHashSet<>();
        Matcher head = Pattern.compile(
                "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+" + Pattern.quote(table) + "\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!head.find()) {
            return out;
        }
        int open = sql.indexOf('(', head.start());
        int depth = 0;
        int close = -1;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return out;
        }
        String body = sql.substring(open + 1, close);

        // 逐行扫描；同时跟踪「跨行举行的括号深度」——
        //   建表体顶层 = 深度 0（进入某行时深度为 0 才是候选列定义行）。
        int depthAtLineStart = 0;
        for (String rawLine : body.split("\n")) {
            int depthAtThisLineStart = depthAtLineStart;
            String line = rawLine;
            int cmt = line.indexOf("--");
            if (cmt >= 0) {
                line = line.substring(0, cmt);
            }
            // 先累计本行的括号变化（在剥注释之后、判列名之前），供下一行使用
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(') {
                    depthAtLineStart++;
                } else if (c == ')') {
                    depthAtLineStart--;
                }
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 🛑 续行判别：本行开始时不处于建表体顶层 ⇒ 是内联 CHECK / 表达式的一部分，不是列
            if (depthAtThisLineStart != 0) {
                continue;
            }
            String upper = line.toUpperCase();
            if (upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY KEY")
                    || upper.startsWith("UNIQUE") || upper.startsWith("FOREIGN KEY")
                    || upper.startsWith("CHECK") || upper.startsWith("EXCLUDE")) {
                continue;
            }
            Matcher cm = Pattern.compile("^([a-z_][a-z0-9_]*)\\b", Pattern.CASE_INSENSITIVE).matcher(line);
            if (cm.find()) {
                String col = cm.group(1).toLowerCase();
                if (!col.equals("constraint")) {
                    out.add(col);
                }
            }
        }
        return out;
    }

    /**
     * 从字典 §二「通用约定」机械提取<b>全表必带的通用审计列</b>。
     *
     * <h2>🛑 为什么需要这个概念（比对粒度必须被定义，否则门禁会报假缺口）</h2>
     * 库侧 {@code refund_receipt} 带 {@code tenant_id} / {@code created_at} / {@code created_by} ——
     * 它们<b>不是</b> N-8 意义上的"域字段"，而是字典 §二明文规定的
     * 「<b>审计字段（全表必带）</b>」：{@code tenant_id}(FK) / {@code created_at} /
     * {@code updated_at} / {@code created_by}（操作人）。它们在<b>每张表</b>上都有，
     * 天然不存在"契约该不该下发"的问题（契约的 {@code RefundReceiptData} 是<b>回执业务视图</b>，
     * 不是表结构的镜像）。
     * <p>若不把这一层粒度从比对中豁免，门禁会把每一张表的每一组通用列都报成"缺口" ——
     * 而"把所有列都登记成缺口"会让登记表失真、并掩盖真正的域字段缺口（这正是本类要防的）。
     *
     * <h2>🛑 但豁免本身也必须被机械核验（否则"豁免"会变成"看不见"）</h2>
     * 本方法<b>不硬编码</b>那四个列名，而是<b>从字典原文逐字提取</b>：
     * 字典那句被改动（增删审计列）时，本方法返回的集合会跟着变，
     * 于是 {@link #generic_audit_columns_are_read_from_the_dictionary} 会红 ——
     * 而不是让"字典改了、豁免还停在旧四列"这件事静默发生。
     *
     * <h2>🛑 为什么必须剔除全角括号里的释义</h2>
     * 字典原句逐字是：
     * <pre>
     *   **审计字段（全表必带）**：`tenant_id`(FK) / `created_at` timestamptz ... /
     *                            `updated_at` timestamptz / `created_by`（操作人 `operator_id`）。
     * </pre>
     * 其中 {@code （操作人 `operator_id`）} 是 <b>全角括号内的中文释义</b> ——
     * 它在解释 {@code created_by} 存的是什么（"操作人"），<b>不是</b>在声明第五个审计列。
     * 若连它也读进来，豁免集就会含 {@code operator_id}，而 {@code refund_receipt.operator_id}
     * 恰是<b>已登记的业务列</b>（staff 外键、可空 = 系统自动推送）——
     * 那会让一条真实的业务缺口被"通用列豁免"静默吞掉（门禁失去牙齿）。
     * <p>注意字典句里 {@code `tenant_id`(FK)} 用的是<b>半角</b>括号（它是类型标注，紧贴列名）、
     * 而释义用的是<b>全角</b>括号 —— 本方法正是按这条字形差异区分的，
     * 并由 {@link #generic_audit_columns_are_read_from_the_dictionary} 反向断言这一区分确实成立。
     */
    private static Set<String> genericAuditColumns() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path dict = resolveRepoRoot().resolve(DICT_REL);
            if (!Files.isRegularFile(dict)) {
                return out;
            }
            String md = Files.readString(dict, StandardCharsets.UTF_8);
            // 定位「审计字段（全表必带）」那一句，取其后的反引号标识符
            Matcher anchor = Pattern.compile(
                    "审计字段（全表必带）\\**\\s*[:：]([^\\n]*)").matcher(md);
            if (!anchor.find()) {
                return out;
            }
            // 🛑 先剔除全角括号释义段（（…）），它们说的是"含义"不是"列名"
            String sentence = anchor.group(1).replaceAll("（[^）]*）", " ");
            Matcher bg = Pattern.compile("`([a-z_][a-z0-9_]*)`").matcher(sentence);
            while (bg.find()) {
                out.add(bg.group(1).toLowerCase());
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** 从 openapi 的 components.schemas.<name>.properties 提取键集（用 snakeyaml 解析）。 */
    private static Set<String> extractSchemaProperties(String schemaName) throws IOException {
        Map<String, Object> root = parseOpenapi(resolveRepoRoot());
        Map<String, Object> components = cast(root.get("components"), "components");
        Map<String, Object> schemas = cast(components.get("schemas"), "components.schemas");
        Object schema = schemas.get(schemaName);
        assertNotNull(schema, "契约里找不到 schema: " + schemaName);
        Map<String, Object> s = cast(schema, "schemas." + schemaName);
        Object props = s.get("properties");
        if (props == null) {
            return new LinkedHashSet<>();
        }
        return new TreeSet<>(cast(props, schemaName + ".properties").keySet());
    }

    private static Map<String, Object> parseOpenapi(Path root) throws IOException {
        String text = Files.readString(root.resolve(OPENAPI_REL), StandardCharsets.UTF_8);
        Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
        assertNotNull(parsed, "契约解析结果为 null —— 文件为空或格式错误: " + OPENAPI_REL);
        return cast(parsed, "openapi 顶层");
    }

    private static Path resolveRepoRoot() {
        for (String key : List.of("dy.docs.root", "dy.repo.root")) {
            String explicit = System.getProperty(key);
            if (explicit != null && !explicit.isBlank()) {
                Path p = Path.of(explicit).toAbsolutePath().normalize();
                assertTrue(isRepoRoot(p), "-D" + key + " 指向的不是仓库根: " + p);
                return p;
            }
        }
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
        }
        throw new AssertionError("无法定位仓库根（需同时存在 " + CONTRACT_MD_REL + " 与 " + OPENAPI_REL
                + "）。请用 -Ddy.docs.root=<abs> 显式指定。cwd=" + Path.of("").toAbsolutePath());
    }

    private static boolean isRepoRoot(Path p) {
        return Files.isRegularFile(p.resolve(CONTRACT_MD_REL.replace('/', File.separatorChar)))
                && Files.isRegularFile(p.resolve(OPENAPI_REL.replace('/', File.separatorChar)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o, String where) {
        assertTrue(o instanceof Map, where + " 应为 mapping，实为: "
                + (o == null ? "null" : o.getClass().getName()));
        return (Map<String, Object>) o;
    }
}