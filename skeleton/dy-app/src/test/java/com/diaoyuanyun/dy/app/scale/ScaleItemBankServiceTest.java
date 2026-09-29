package com.diaoyuanyun.dy.app.scale;

import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringEngine;
import com.diaoyuanyun.dy.app.scale.repository.ScaleItemBankRepository;
import com.diaoyuanyun.dy.app.scale.service.ConfigSeedScaleProfileSource;
import com.diaoyuanyun.dy.app.scale.service.ScaleItemBankService;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-4 题库服务的三项验收行为断言：<b>可导入 / 可组卷 / 可计分（口径外置）</b>。
 *
 * <h2>为什么这里用一个"手写假仓储"，而不是 Mockito，也不是真库</h2>
 * <ul>
 *   <li><b>不用真库</b>：真库能证明的"隔离有效性"由
 *       {@code RlsScaleItemBankIsolationTest} 负责。本类要证明的是<b>服务层校验链</b>
 *       （方向 / 签核 / 锚点 / 查重 / 事务语义）—— 那是纯逻辑，用真库反而把
 *       "校验是否生效"与"数据库是否连上"两件事混在一起，失败时看不出真因。</li>
 *   <li><b>不用 Mockito</b>：本类的关键断言之一是"<b>校验失败时仓储一次都没被调用</b>"。
 *       假仓储把"被调用了几次、收到的行是什么"变成可直接读的字段，
 *       比 {@code verify(never())} 更直白，也不依赖工具的行为细节。</li>
 * </ul>
 *
 * <h2>本类最重要的一条：整批导入必须原子</h2>
 * {@link #duplicate_key_during_import_rolls_back_the_whole_batch()} 断言的是
 * "冲突时整批回滚、不留半成功题组"。这与"逐条插"是两种可观察的行为差异：
 * 后者在第 200 行失败时会留下 199 行 —— 而那个中间态<b>不会报错</b>，
 * 只会在某天以"题组不完整"暴露，且说不清是哪次导入造成的。
 */
class ScaleItemBankServiceTest {

    private static final String TENANT = "aaaaaaaa-1111-1111-1111-111111111111";

    private FakeRepository repository;
    private ScaleItemBankService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new ScaleItemBankService(repository, new ConfigSeedScaleProfileSource());
        // 前置自证：假仓储从空开始，且默认不模拟冲突 ——
        // 否则"某条断言其实什么都没测"会被一个非空起点掩盖。
        assertTrue(repository.inserted.isEmpty(), "每例应从空假仓储开始");
        assertFalse(repository.failOnInsertAll, "每例默认不应开启冲突模拟");
    }

    // ==================================================================
    // 一、可导入 —— 逐条 fail-closed
    // ==================================================================

    @Test
    @DisplayName("导入：合法题组通过，且仓储收到的是【已 trim 且方向归一为 symptom】的行")
    void import_accepts_a_valid_batch_and_normalizes_the_rows() {
        ScaleItemBankService.ImportResult r = service.importItems(TENANT, List.of(
                draft("男16-32", "体能精力", 1, "symptom"),
                draft("男16-32", "体能精力", 2, "symptom")));

        assertEquals(2, r.requested(), "请求数应等于入参行数");
        assertEquals(2, r.inserted(), "插入数应等于请求数");
        assertEquals(2, repository.inserted.size(), "假仓储应收到 2 行");
        assertTrue(r.profileSource().contains("#35") || r.profileSource().contains("config"),
                "导入结果应报告口径来源，使『口径外置』这件事可被调用方读到；实际=" + r.profileSource());

        ScaleItemRow first = repository.inserted.get(0);
        assertEquals("symptom", first.itemDirection(), "方向应被归一为 symptom");
        assertNotNull(first.itemId(), "未传主键时应由服务层生成，使后续可按主键追溯");
        assertEquals("男16-32", first.ageGroup(), "年龄组应归一为枚举的字面值");
    }

    @Test
    @DisplayName("导入：题面方向非 symptom 一律拒（正向题会把『症状减轻』算成『分数上升』）")
    void import_rejects_non_symptom_item_direction() {
        for (String bad : List.of("positive", "正向", "SYMPTOM", "")) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.importItems(TENANT, List.of(draft("男16-32", "体能精力", 1, bad))),
                    "非 symptom 方向被接受了: " + bad);
            assertEquals(1001, ex.getCode(), "方向非法属入参校验失败（1001）");
            assertTrue(ex.getMessage().contains("symptom"),
                    "错误信息必须点明合法值，便于内容侧改题；实际: " + ex.getMessage());
        }
        assertTrue(repository.inserted.isEmpty(), "校验失败的批次不得触库");
    }

    @Test
    @DisplayName("导入：缺人工签核（reviewer_id / reviewed_at）一律拒（PRD P0-18）")
    void import_rejects_missing_human_sign_off() {
        BizException noReviewer = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        new ScaleItemRow(null, "男16-32", "体能精力", 1, "题面",
                                "a0", "a1", "a2", "a3", "a4", "symptom", "v1", null, Instant.now(), null))),
                "缺 reviewer_id 被接受了");
        assertEquals(1001, noReviewer.getCode());
        assertTrue(noReviewer.getMessage().contains("签核"),
                "错误信息应点明这是签核缺失（P0-18），而不是一个泛泛的『字段必填』");

        BizException noTime = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        new ScaleItemRow(null, "男16-32", "体能精力", 1, "题面",
                                "a0", "a1", "a2", "a3", "a4", "symptom", "v1", "reviewer", null, null))),
                "缺 reviewed_at 被接受了");
        assertEquals(1001, noTime.getCode());
        assertTrue(repository.inserted.isEmpty(), "校验失败的批次不得触库");
    }

    @Test
    @DisplayName("导入：五级锚点必须齐备且与口径级数一致（缺一级即不可比）")
    void import_requires_all_five_anchors_matching_the_profile_levels() {
        // 缺 anchor_4
        BizException missingAnchor = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        new ScaleItemRow(null, "男16-32", "体能精力", 1, "题面",
                                "a0", "a1", "a2", "a3", null, "symptom", "v1", "reviewer", Instant.now(), null))),
                "缺一级锚点被接受了 —— 锚点是『同源可比』的唯一依据");
        assertEquals(1001, missingAnchor.getCode());
        assertTrue(missingAnchor.getMessage().contains("anchor_4"),
                "错误信息应点名缺的是哪一级锚点；实际: " + missingAnchor.getMessage());

        // 锚点为空白字符串（不是 null，但同样不可用）
        BizException blankAnchor = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        new ScaleItemRow(null, "男16-32", "体能精力", 1, "题面",
                                "a0", "a1", "  ", "a3", "a4", "symptom", "v1", "reviewer", Instant.now(), null))),
                "空白锚点被接受了");
        assertEquals(1001, blankAnchor.getCode());
    }

    @Test
    @DisplayName("导入：item_no 越界 / 年龄组或维度非枚举内取值 一律拒")
    void import_rejects_out_of_domain_item_no_and_enum_values() {
        assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(draft("男16-32", "体能精力", 0, "symptom"))),
                "item_no = 0 被接受了（域为 1..4）");
        assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(draft("男16-32", "体能精力", 5, "symptom"))),
                "item_no = 5 被接受了（域为 1..4）");
        assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(draft("男16-33", "体能精力", 1, "symptom"))),
                "非枚举内的年龄组被接受了（8 组之外的不得入库）");
        assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(draft("男16-32", "不存在的维度", 1, "symptom"))),
                "非枚举内的维度被接受了（7 维之外的不得入库）");
        assertTrue(repository.inserted.isEmpty(), "校验失败的批次不得触库");
    }

    @Test
    @DisplayName("导入：空批次 / 非法租户 ID 一律拒（租户 ID 非法 = 2003 而非 500）")
    void import_rejects_empty_batch_and_illegal_tenant_id() {
        assertEquals(1001, assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of())).getCode(),
                "空批次应属入参校验失败");
        assertEquals(1001, assertThrows(BizException.class,
                () -> service.importItems(TENANT, null)).getCode(),
                "null 批次应属入参校验失败");

        BizException badTenant = assertThrows(BizException.class,
                () -> service.importItems("not-a-uuid", List.of(draft("男16-32", "体能精力", 1, "symptom"))),
                "非法租户 ID 被接受了 —— 它会以字符串拼接进入 SET 语句");
        assertEquals(2003, badTenant.getCode(),
                "非法租户 ID 语义上是『租户不匹配』（2003/403），不是 500");
    }

    @Test
    @DisplayName("导入：同批内重复题目槽位在应用层就被指出（含是哪两行），不必等数据库 23505")
    void duplicate_slots_within_a_batch_are_caught_in_the_application_layer() {
        BizException ex = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        draft("男16-32", "体能精力", 1, "symptom"),
                        draft("男16-32", "体能精力", 1, "symptom"))),
                "同批重复槽位被接受了");
        assertEquals(1001, ex.getCode());
        assertTrue(ex.getMessage().contains("重复"),
                "错误信息应点明是重复槽位；实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("第 1 行") && ex.getMessage().contains("第 2 行"),
                "错误信息应指出是【哪两行】重复 —— 导入 224 题时这是最需要的信息；实际: " + ex.getMessage());
        assertTrue(repository.inserted.isEmpty(), "查重失败不得触库");
    }

    @Test
    @DisplayName("导入：唯一键冲突时【整批回滚】，不留半成功题组")
    void duplicate_key_during_import_rolls_back_the_whole_batch() {
        repository.failOnInsertAll = true;

        BizException ex = assertThrows(BizException.class,
                () -> service.importItems(TENANT, List.of(
                        draft("男16-32", "体能精力", 1, "symptom"),
                        draft("男16-32", "体能精力", 2, "symptom"),
                        draft("男16-32", "体能精力", 3, "symptom"))),
                "唯一键冲突应被翻译成明确的业务错误");
        assertEquals(4001, ex.getCode(),
                "同键冲突属 VERSION_CONFLICT(4001)，不是 500");
        assertTrue(ex.getMessage().contains("version"),
                "错误信息必须给出可执行处置（改用新 version，因为旧版本不可覆盖）；实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("整体回滚") || ex.getMessage().contains("回滚"),
                "错误信息应说明本次导入未写入任何题，避免调用方以为『部分成功』");

        assertEquals(1, repository.insertAllCalls,
                "写库只应被调用一次（一次整批），而不是逐条多次 —— 逐条会导致部分已提交");
        assertTrue(repository.inserted.isEmpty(),
                "冲突时不得留下任何行（假仓储在这里模拟整批回滚）");
    }

    @Test
    @DisplayName("导入：单条 insert 入口与批量入口共用同一套 SQL（避免两份字段清单各自漂移）")
    void single_insert_and_batch_insert_share_one_sql_shape() {
        ScaleItemRow d = draft("女14-28", "睡眠质量", 3, "symptom");
        repository.insert(TENANT, d);
        assertEquals(1, repository.inserted.size(), "单条插入应产生 1 行");
        assertEquals(d.dimension(), repository.inserted.get(0).dimension(),
                "单条插入写入的应是同一份行数据");
    }

    // ==================================================================
    // 二、可组卷
    // ==================================================================

    @Test
    @DisplayName("组卷：恰好 28 题（7 维 × 4）时成功，且按维度归组、每维 4 题")
    void compose_paper_requires_and_returns_exactly_twenty_eight_items() {
        repository.paperRows = fullPaper();

        ScaleItemBankService.Paper paper = service.composePaper(TENANT, "男16-32", "v1");
        assertEquals(28, paper.size(), "组卷应返回 28 题（7 维 × 4 题）");
        assertEquals(7, paper.byDimension().size(), "应按 7 个维度归组");
        for (Map.Entry<String, List<ScaleItemRow>> e : paper.byDimension().entrySet()) {
            assertEquals(4, e.getValue().size(),
                    "维度 " + e.getKey() + " 应为 4 题（总题数对但分布错同样会让维度分失真）");
        }
    }

    @Test
    @DisplayName("组卷：题数不足（残缺题组）即拒，不返回部分题组")
    void compose_paper_rejects_incomplete_paper() {
        List<ScaleItemRow> partial = new ArrayList<>(fullPaper());
        partial.remove(partial.size() - 1);   // 27 题
        repository.paperRows = partial;

        BizException ex = assertThrows(BizException.class,
                () -> service.composePaper(TENANT, "男16-32", "v1"),
                "残缺题组被接受了 —— 返回残缺题组会让维度分偏低且不报错");
        assertEquals(5001, ex.getCode(),
                "题组不完整属业务规则冲突（5001），不是 404/入参错误");
        assertTrue(ex.getMessage().contains("28"),
                "错误信息应给出所需题数，便于内容侧核对；实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("组卷：总数对但某维度分布错（5+3）同样拒")
    void compose_paper_rejects_uneven_distribution_even_when_the_total_matches() {
        List<ScaleItemRow> uneven = new ArrayList<>(fullPaper());
        // 把「体能精力」的第 4 题改成「睡眠质量」的第 4 题 → 体能精力 3 题、睡眠质量 5 题，总数仍是 28
        ScaleItemRow moved = uneven.remove(3);
        uneven.add(new ScaleItemRow(moved.itemId(), moved.ageGroup(), "睡眠质量", 4,
                moved.itemText(), moved.anchor0(), moved.anchor1(), moved.anchor2(),
                moved.anchor3(), moved.anchor4(), moved.itemDirection(), moved.version(),
                moved.reviewerId(), moved.reviewedAt(), moved.createdBy()));
        repository.paperRows = uneven;

        BizException ex = assertThrows(BizException.class,
                () -> service.composePaper(TENANT, "男16-32", "v1"),
                "总数对但分布错被接受了 —— 维度分会失真");
        assertEquals(5001, ex.getCode());
        assertTrue(ex.getMessage().contains("分布") || ex.getMessage().contains("题数"),
                "错误信息应指出分布不均；实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("组卷：必须指定 version（版本不一致会破坏同源可比）")
    void compose_paper_requires_a_version() {
        repository.paperRows = fullPaper();
        BizException ex = assertThrows(BizException.class,
                () -> service.composePaper(TENANT, "男16-32", "  "),
                "空白 version 被接受了 —— 同源比较要求版本一致");
        assertEquals(1001, ex.getCode());
        assertTrue(ex.getMessage().contains("version"), "错误信息应点明 version 必填");
    }

    // ==================================================================
    // 三、可计分（口径外置）
    // ==================================================================

    @Test
    @DisplayName("计分：分值上下限来自配置口径，调用方无法传入分值参数")
    void scoring_uses_the_configured_profile_and_takes_no_score_parameters() {
        assertNotNull(service.engine(), "当前配置口径应能构造出引擎");

        ScaleScoringEngine.ScoreResult r = service.score(TENANT,
                new ScaleScoringEngine.Submission("男16-32", fullAnswers(2), "v1"));
        assertEquals(7 * 4 * 2, r.totalScore(), "7 维 × 4 题 × 2 分 = 56 分");
        // 响应的 itemMinMax 回显的是【配置口径】的上下限：min=0（症状向自评量表下限 =「无症状」）、
        // max=4（0–4 五级）—— 它证明"分值来自配置"，而不是"代码里写了个 2"。
        // 🛑 别把 min 断言成 2：2 是上面作答用的分值，不是量程下限。
        assertEquals(0, r.itemMinMax().get("min"), "单题下限应等于配置口径的 min（0）");
        assertEquals(4, r.itemMinMax().get("max"), "单题上限应等于配置口径的 max（4）");
        assertEquals("TBD", r.severityLabel(),
                "分档未校准应输出 TBD（硬纪律 #6），不得兜底一个档位");
    }

    @Test
    @DisplayName("计分：越界分值被拒（分值上限来自口径，不是代码里的常量）")
    void scoring_rejects_scores_above_the_configured_maximum() {
        BizException ex = assertThrows(BizException.class,
                () -> service.score(TENANT,
                        new ScaleScoringEngine.Submission("男16-32", fullAnswers(5), "v1")),
                "5 分被接受了 —— 口径是 0–4 五级");
        assertEquals(1001, ex.getCode());
        assertTrue(ex.getMessage().contains("4"),
                "错误信息应给出实际量程上限；实际: " + ex.getMessage());
    }

    // ==================================================================
    // 假仓储 / 构造工具
    // ==================================================================

    /**
     * 手写假仓储：记录"被要求写入的行"，并可模拟唯一键冲突。
     *
     * <p>它继承真实仓储但<b>覆盖掉所有会触库的方法</b>，构造时用的 DataSource 指向一个
     * 不存在的端口 —— 若某条路径漏了覆盖，它会以连接失败<b>立刻炸</b>，
     * 而不是静默走到某个真库上。这个"故意连不上"的设计是有意的：
     * 它让"忘记覆盖"变成显式失败，而不是一个可能连上生产库的隐患。
     */
    private static final class FakeRepository extends ScaleItemBankRepository {

        final List<ScaleItemRow> inserted = new ArrayList<>();
        List<ScaleItemRow> paperRows = List.of();
        boolean failOnInsertAll;
        int insertAllCalls;

        FakeRepository() {
            super(new SingleConnectionDataSource(
                    "jdbc:postgresql://127.0.0.1:1/never-connected", "nobody", "nothing", true));
        }

        @Override
        public void insert(String tenantId, ScaleItemRow row) {
            inserted.add(row);
        }

        @Override
        public int insertAll(String tenantId, List<ScaleItemRow> rows) {
            insertAllCalls++;
            if (failOnInsertAll) {
                // 模拟唯一键冲突：整批回滚（不把任何行放进 inserted）
                throw new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_sib_business\"");
            }
            inserted.addAll(rows);
            return rows.size();
        }

        @Override
        public List<ScaleItemRow> findByPaperKey(String tenantId, String ageGroup, String version) {
            return paperRows;
        }

        @Override
        public int countVisible(String tenantId) {
            return inserted.size();
        }

        @Override
        public int countByPaperKey(String tenantId, String ageGroup, String version) {
            return paperRows.size();
        }
    }

private static ScaleItemRow draft(String ageGroup, String dimension,
                                                          int itemNo, String direction) {
        return new ScaleItemRow(UUID.randomUUID(), ageGroup, dimension, itemNo,
                "题面 " + ageGroup + "/" + dimension + "#" + itemNo,
                "a0", "a1", "a2", "a3", "a4", direction, "v1",
                "reviewer:总部临床负责人", Instant.parse("2026-09-23T00:00:00Z"), null);
    }

    private static final List<String> DIMENSIONS = List.of("体能精力", "面部气色肤质",
            "肩颈腰背筋骨", "睡眠质量", "记忆专注", "代谢体态消化", "情绪抗压与抵抗力");

    /** 一份完整题组：7 维 × 4 题 = 28。 */
    private static List<ScaleItemRow> fullPaper() {
        List<ScaleItemRow> rows = new ArrayList<>();
        int seq = 0;
        for (String dim : DIMENSIONS) {
            for (int no = 1; no <= 4; no++) {
                rows.add(new ScaleItemRow(
                        new UUID(0L, ++seq), "男16-32", dim, no, "题面 " + dim + "#" + no,
                        "a0", "a1", "a2", "a3", "a4", "symptom", "v1",
                        "reviewer:总部临床负责人", Instant.parse("2026-09-23T00:00:00Z"), null));
            }
        }
        return rows;
    }

    /** 7 维齐备、每维 4 题、全部同一分值。 */
    private static Map<String, List<Integer>> fullAnswers(int value) {
        Map<String, List<Integer>> m = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            m.put(dim, List.of(value, value, value, value));
        }
        return m;
    }
}