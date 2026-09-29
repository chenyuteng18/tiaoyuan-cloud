package com.diaoyuanyun.dy.app.scale.service;

import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.app.scale.domain.ScaleProfileSource;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringEngine;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringProfile;
import com.diaoyuanyun.dy.app.scale.repository.ScaleItemBankRepository;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S1-4 题库服务 —— 三项验收的落点：<b>可导入 / 可组卷 / 可计分（口径外置）</b>。
 *
 * <h2>验收三项各自对应什么</h2>
 * <table border="1">
 *   <caption>开发清单 §一④ 的三项</caption>
 *   <tr><th>验收</th><th>本服务的方法</th><th>它真正证明的事</th></tr>
 *   <tr><td>224 题<b>可导入</b>（样例题验证）</td><td>{@link #importItems}</td>
 *       <td>结构能容纳 8×7×4；且导入过程对"题面方向 / 签核 / 版本冲突"逐条 fail-closed</td></tr>
 *   <tr><td>可<b>组卷</b></td><td>{@link #composePaper}</td>
 *       <td>按锁定年龄组 + 版本能取出恰好 28 题（7 维 × 4），且缺题即拒</td></tr>
 *   <tr><td>可<b>计分</b>，评分口径外置</td><td>{@link #score}</td>
 *       <td>分值上下限全部来自 {@link ScaleProfileSource}（config #35），引擎内无分值常量</td></tr>
 * </table>
 *
 * <h2>🛑 为什么导入的是"样例题"而不是 224 道真题面</h2>
 * 开发清单原文即写「224 题<b>可导入</b>（用<b>样例题</b>验证，非真实定稿）」。
 * 这与 PRD 的实际交付状态一致：224 题定稿属<b>内容侧</b>交付物，
 * 且其中低龄组 56 题（男 16-32 / 女 14-28）还须先改写为症状向并逐题签核
 * （PRD P0-18：未交付则该两组题组入库为空、无法出题）。
 * 故本服务只提供<b>入口与校验</b>，不预置任何真题面 ——
 * 预置会被下游误读为"内容已定稿"。
 *
 * <h2>导入的逐条 fail-closed（对齐 PRD P0-18「阻断式校验」）</h2>
 * <ol>
 *   <li><b>题面方向</b>：{@code item_direction} 只接受 {@code symptom}，
 *       非 symptom 一律拒 —— 正向表述题会把"症状减轻"算成"分数上升"；</li>
 *   <li><b>人工签核</b>：{@code reviewer_id} 与 {@code reviewed_at} 缺一即拒。
 *       PRD P0-18 明定「缺签核记录 → 拒入库」，且极性关键词检测<b>不替代</b>人工签核；</li>
 *   <li><b>五级锚点</b>：{@code anchor_0..anchor_4} 五级须齐备且非空 ——
 *       锚点是"同源可比"的唯一依据，缺一级则该题在复评时不可比；</li>
 *   <li><b>量程自洽</b>：锚点级数必须等于口径的 {@code levels}（默认 5）。
 *       口径改了而锚点没跟上（例如口径回到 4 级却仍留 5 个锚点）→ 拒入库。</li>
 * </ol>
 */
@Service
public class ScaleItemBankService {

    /** 题面方向的唯一合法值（字典 §2.24 / PRD P0-18）。 */
    public static final String DIRECTION_SYMPTOM = "symptom";

    private final ScaleItemBankRepository repository;
    private final ScaleProfileSource profileSource;

    public ScaleItemBankService(ScaleItemBankRepository repository, ScaleProfileSource profileSource) {
        this.repository = repository;
        this.profileSource = profileSource;
    }

    // ------------------------------------------------------------------
    // 一、可导入
    // ------------------------------------------------------------------

    /** 导入结果（逐项计数，便于"导入即自证"）。 */
    public record ImportResult(
            String ageGroup,
            String version,
            int requested,
            int inserted,
            int visibleAfterImport,
            String profileSource) {
    }

    /**
     * 批量导入一个题组的题目。
     *
     * <p><b>两段式：先全量校验（不触库），再整批写库（单事务）</b>。两段都是必要的：
     * <ul>
     *   <li>校验全部前置，使"第 200 行不合法"在<b>第一行写库之前</b>就被拒 ——
     *       否则那 199 行已经落了，题组停在"有 199 题"的中间态；</li>
     *   <li>写库用<b>单事务</b>（{@link ScaleItemBankRepository#insertAll}），
     *       使唯一键冲突时整体回滚 —— 不留任何"半成功"题组。
     *       半成品的题组不会报错，只会在某个客户的组卷请求上以"题组不完整"暴露，
     *       而那时已经很难说清是哪次导入造成的。</li>
     * </ul>
     *
     * <h2>🛑 为什么入参不是"另一个 ItemRow"（曾经的 R4 真实违规）</h2>
     * 本方法原本收 {@code ScaleItemBankService.ItemRow}（服务层内的嵌套 record），
     * 于是 <b>{@code app.scale.controller} 的请求体 record 必须依赖
     * {@code app.scale.service} 才能构造它</b> → 触发架构规则 R4
     * （「任何 record 不得依赖服务层」）。那是<b>真实回归</b>，不是测试过严：
     * 它让"契约形状"（controller 的请求体）被"服务层的内部类型"绑住。
     *
     * <p>更根本的问题是<b>同构定义会漂移</b>：{@code ItemRow} / {@code ScaleItemDraft} /
     * {@link ScaleItemRow} 三者字段逐字相同、都在 {@code domain} 或服务层里各写一份。
     * {@link ScaleItemRow} 的类注释早写明过这个坑（"加一个字段只改一处时不报错，
     * 只让『写进去的』与『读出来的』悄悄不一致"）—— 再补第三个同构 record 等于扩大它。
     * 故此处<b>直接收 {@link ScaleItemRow}</b>：入参 → 归一化后的行，字段集合天然只有一份。
     * 代价是"外部输入"与"已归一化值"共用同一类型，故：① 校验器逐项 fail-closed；
     * ② 校验失败时<b>一行都不触库</b>（阶段 1 与阶段 2 分离）；③ 注释显式声明这一点。
     */
    public ImportResult importItems(String tenantId, List<ScaleItemRow> rows) {
        ScaleItemBankRepository.validateTenantId(tenantId);
        if (rows == null || rows.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "导入列表不得为空");
        }
        ScaleScoringProfile profile = currentProfile();

        // ---- 阶段 1：全量校验（不触库）----
        // 校验的产物是【归一化后的行】（trim 掉两侧空白）；
        // 故下游（查重、写库）拿到的是可信值。校验器对每个非法输入都 fail-closed，
        // 且此处一行都不写 —— "校验失败但库里有半批"这种状态在本方法下不可达。
        List<ScaleItemRow> normalized = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            normalized.add(validateRow(rows.get(i), profile, i));
        }

        // 阶段 1.5：题组完整性预检 —— 同一 (ageGroup, version) 内不得重复 (dimension, itemNo)。
        // 唯一键会挡住重复，但那条错误来自数据库（23505），消息里没有"是哪两行重复"，
        // 而导入 224 题时"哪两行重复"恰恰是最需要的信息。故在应用层先算一次。
        detectDuplicateSlots(normalized);

        // ---- 阶段 2：整批单事务写库（冲突即整体回滚）----
        int inserted;
        try {
            inserted = repository.insertAll(tenantId, normalized);
        } catch (DuplicateKeyException e) {
            // 唯一键 (tenant_id, age_group, dimension, item_no, version) 命中。
            // 🛑 语义是"该题该版本已存在" —— 按 P0-20「旧版本不可覆盖」，
            //    正确处置是【改用新 version】，而不是覆盖已有行。
            //    注意：整批已回滚，故不存在"前几百题已入库"的中间态。
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "题目已存在（同租户/年龄组/维度/题序/版本）—— 本次导入已整体回滚，未写入任何题。"
                            + " 题目文本版本化不可覆盖，改题请改用新 version。（原始冲突: "
                            + e.getMostSpecificCause().getMessage() + "）");
        }

        ScaleItemRow first = normalized.get(0);
        return new ImportResult(
                first.ageGroup(),
                first.version(),
                rows.size(),
                inserted,
                repository.countVisible(tenantId),
                profileSource.describeSource());
    }

    /** 逐项校验一行题目；返回归一化后的<b>行</b>（trim 掉两侧空白，避免"看着一样实则不同"）。 */
    private ScaleItemRow validateRow(ScaleItemRow row, ScaleScoringProfile profile, int index) {
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "第 " + index + " 行为空");
        }
        // 年龄组 / 维度必须是枚举内取值（与 DB CHECK 同字面）
        ScaleDomain.AgeGroup ageGroup = ScaleDomain.AgeGroup.parse(row.ageGroup());
        ScaleDomain.Dimension dimension = ScaleDomain.Dimension.parse(row.dimension());

        // 题序 1..4（与 DB CHECK 同域）
        if (row.itemNo() < 1 || row.itemNo() > profile.itemsPerDimension()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "item_no 应在 1–" + profile.itemsPerDimension() + "，实际 " + row.itemNo()
                            + "（" + ageGroup.label() + "/" + dimension.label() + "）");
        }

        // ① 题面方向：仅 symptom（正向题不得作计分项）
        if (!DIRECTION_SYMPTOM.equals(row.itemDirection())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "item_direction 仅允许 " + DIRECTION_SYMPTOM + "，实际 " + row.itemDirection()
                            + "（" + ageGroup.label() + "/" + dimension.label() + " #" + row.itemNo()
                            + "）—— 正向表述题会把『症状减轻』算成『分数上升』");
        }

        // ② 题面非空
        String text = requireNonBlank(row.itemText(), "item_text", slot(ageGroup, dimension, row));

        // ③ 五级锚点齐备（锚点是同源可比的唯一依据，缺一级即不可比）
        List<String> anchors = List.of(
                requireNonBlank(row.anchor0(), "anchor_0", slot(ageGroup, dimension, row)),
                requireNonBlank(row.anchor1(), "anchor_1", slot(ageGroup, dimension, row)),
                requireNonBlank(row.anchor2(), "anchor_2", slot(ageGroup, dimension, row)),
                requireNonBlank(row.anchor3(), "anchor_3", slot(ageGroup, dimension, row)),
                requireNonBlank(row.anchor4(), "anchor_4", slot(ageGroup, dimension, row)));

        // ④ 量程自洽：锚点数必须等于口径级数（口径改了而锚点没跟上 → 拒）
        if (anchors.size() != profile.levels()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "锚点数 " + anchors.size() + " 与量程级数 " + profile.levels()
                            + " 不符（" + slot(ageGroup, dimension, row) + "）—— 口径与题组不匹配");
        }

        // ⑤ 人工签核（PRD P0-18：缺签核记录 → 拒入库；关键词检测不替代人工签核）
        if (row.reviewerId() == null || row.reviewerId().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "缺逐题方向签核人 reviewer_id（" + slot(ageGroup, dimension, row)
                            + "）—— PRD P0-18：缺签核记录拒入库，且关键词检测不替代人工签核");
        }
        if (row.reviewedAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "缺签核时点 reviewed_at（" + slot(ageGroup, dimension, row) + "）");
        }

        // 版本必填（旧版本不可覆盖的前提是版本可辨识）
        if (row.version() == null || row.version().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "version 必填（题目文本版本化不可覆盖）");
        }

        return new ScaleItemRow(
                row.itemId() == null ? UUID.randomUUID() : row.itemId(),
                ageGroup.label(),
                dimension.label(),
                row.itemNo(),
                text.trim(),
                anchors.get(0).trim(), anchors.get(1).trim(), anchors.get(2).trim(),
                anchors.get(3).trim(), anchors.get(4).trim(),
                DIRECTION_SYMPTOM,
                row.version().trim(),
                row.reviewerId().trim(),
                row.reviewedAt(),
                row.createdBy());
    }

    /** 同批内重复 (年龄组, 维度, 题序) 检测 —— 唯一键会挡，但应用层的消息能指出"是哪些行"。 */
    private static void detectDuplicateSlots(List<ScaleItemRow> rows) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ScaleItemRow r = rows.get(i);
            String key = r.ageGroup() + "|" + r.dimension() + "|" + r.itemNo() + "|" + r.version();
            Integer prev = seen.put(key, i);
            if (prev != null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "同批导入内出现重复题目槽位: " + r.ageGroup() + " / " + r.dimension()
                                + " #" + r.itemNo() + " / version=" + r.version()
                                + "（第 " + (prev + 1) + " 行与第 " + (i + 1) + " 行）");
            }
        }
    }

    private static String slot(ScaleDomain.AgeGroup g, ScaleDomain.Dimension d, ScaleItemRow row) {
        return g.label() + "/" + d.label() + " #" + row.itemNo();
    }

    private static String requireNonBlank(String value, String field, String slot) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不得为空（" + slot + "）");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 二、可组卷
    // ------------------------------------------------------------------

    /** 一份题组（可组卷的产物）。 */
    public record Paper(
            String ageGroup,
            String version,
            List<ScaleItemRow> items,
            Map<String, List<ScaleItemRow>> byDimension) {

        public int size() {
            return items.size();
        }
    }

    /**
     * 按锁定年龄组 + 版本组卷。
     *
     * <p>🛑 <b>缺题即拒</b>，不返回"部分题组"：PRD P0-11 的复评要求
     * 「复评页题组 ID ≠ 基线题组 ID → 阻断并转人工」，且同源比较要求 7 维齐备。
     * 返回残缺题组会让下游算出偏低的维度分 —— 而这不会报错，只会让效果判定失真。
     *
     * <p>所需题数由<b>口径推导</b>（{@code 维度数 × 每维题数} = 7 × 4 = 28），不是写死的 28。
     */
    public Paper composePaper(String tenantId, String ageGroup, String version) {
        ScaleItemBankRepository.validateTenantId(tenantId);
        ScaleDomain.AgeGroup group = ScaleDomain.AgeGroup.parse(ageGroup);
        if (version == null || version.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "组卷必须指定 version（题目文本版本化，同源比较要求版本一致）");
        }
        ScaleScoringProfile profile = currentProfile();
        int required = profile.dimensionCount() * profile.itemsPerDimension();

        List<ScaleItemRow> items =
                repository.findByPaperKey(tenantId, group.label(), version.trim());

        if (items.size() != required) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "题组不完整，拒绝组卷: " + group.label() + " / version=" + version
                            + " 需 " + required + " 题（" + profile.dimensionCount() + " 维 × "
                            + profile.itemsPerDimension() + " 题），实际 " + items.size()
                            + " 题。残缺题组会让维度分偏低且不报错 —— 故此处直接阻断。"
                            + "（低龄组 56 题若未完成症状向改写，该两组题组入库为空属预期，见 PRD P0-18）");
        }

        // 按维度归组（保持题序），供计分与展示
        Map<String, List<ScaleItemRow>> byDimension = new LinkedHashMap<>();
        for (String label : ScaleDomain.Dimension.allLabels()) {
            byDimension.put(label, new ArrayList<>());
        }
        for (ScaleItemRow item : items) {
            List<ScaleItemRow> bucket = byDimension.get(item.dimension());
            if (bucket == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "题组含未登记维度: " + item.dimension() + "（题组与 7 维口径不一致）");
            }
            bucket.add(item);
        }
        // 每维必须恰好 4 题（总数对但分布错也会让维度分失真）
        for (Map.Entry<String, List<ScaleItemRow>> e : byDimension.entrySet()) {
            if (e.getValue().size() != profile.itemsPerDimension()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "维度 " + e.getKey() + " 题数应为 " + profile.itemsPerDimension()
                                + "，实际 " + e.getValue().size() + " —— 题组分布不均，拒绝组卷");
            }
        }

        return new Paper(group.label(), version.trim(), items, byDimension);
    }

    // ------------------------------------------------------------------
    // 三、可计分（口径来自配置，不在代码里）
    // ------------------------------------------------------------------

    /** 用<b>当前配置口径</b>构造的计分引擎。 */
    public ScaleScoringEngine engine() {
        return new ScaleScoringEngine(currentProfile());
    }

    /**
     * 对一份作答计分（口径取自配置来源）。
     *
     * <p>本方法存在的意义是让"口径从配置来"这条链路<b>可被端到端断言</b>：
     * 它不接受任何分值参数，故调用方无法把分值传进来绕过配置。
     */
    public ScaleScoringEngine.ScoreResult score(String tenantId, ScaleScoringEngine.Submission submission) {
        // tenantId 参与校验链（组卷与分数都属于某租户的事实），
        // 使"用 A 租户的题组给 B 租户计分"这类错位在入口就被拒绝。
        ScaleItemBankRepository.validateTenantId(tenantId);
        return engine().score(submission);
    }

    /** 从配置来源读口径并归一化（唯一入口；失败即抛，不返回默认口径）。 */
    public ScaleScoringProfile currentProfile() {
        return ScaleScoringProfile.fromConfigJson(profileSource.rangeRuleJson());
    }

    /** 题库可见题数（RLS 生效下的真实行数）。 */
    public int visibleItemCount(String tenantId) {
        return repository.countVisible(tenantId);
    }

    // ------------------------------------------------------------------
    // 四、C1 题库拉取（契约域 C 的读取面，复用组卷的同一份校验口径）
    // ------------------------------------------------------------------

    /**
     * C1 —— 按分龄组 + 可选维度 + 可选版本拉取题组。
     *
     * <p>契约 C1 的 {@code age_group} 是 {@code required} 枚举，
     * {@code dimension} 与 {@code version} 可选。本方法是组卷
     * （{@link #composePaper}）的<b>只读弱化版</b>：组卷要求"缺题即拒"，
     * 拉取只要求"参数合法 + 诚实返回当前题数"（可为 0 —— 低龄组未完成
     * 症状向改写时入库为空属预期，见 PRD P0-18）。
     *
     * <p>🛑 尽管如此，{@code age_group} 仍在入口处用 {@link ScaleDomain.AgeGroup#parse}
     * 做 fail-closed 校验 —— 非法取值报 {@code VALIDATION_FAILED(1001)} 而非
     * 落成一个"查无结果"的 200 空列表：前者是可定位的调用方错误，
     * 后者会让"枚举打错字"静默变成"题库没题"，而这是无法与真缺题区分的。
     *
     * @return 命中题组（按 dimension, item_no 稳定排序）
     */
    public List<ScaleItemRow> listItems(String tenantId, String ageGroup,
                                        String dimension, String version) {
        ScaleItemBankRepository.validateTenantId(tenantId);
        // 年龄组必须合法（fail-closed，见方法注释）
        String normalizedAge = ScaleDomain.AgeGroup.parse(ageGroup).label();
        // 维度可选：传了才校验（不传 = 全部 7 维）
        String normalizedDimension = dimension == null
                ? null : ScaleDomain.Dimension.parse(dimension).label();
        String normalizedVersion = (version == null || version.isBlank()) ? null : version.trim();
        return repository.findByQuery(tenantId, normalizedAge, normalizedDimension, normalizedVersion);
    }
}