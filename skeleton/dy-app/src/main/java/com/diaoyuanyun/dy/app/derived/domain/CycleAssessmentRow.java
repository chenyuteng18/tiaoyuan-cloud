package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code cycle_assessment} 一行 —— <b>判定依据</b>的落库载体（V5 §2.18）。
 *
 * <h2>🛑 本记录的两个"必须落库且不可覆盖"字段</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>V5 约束</th><th>为什么必须</th></tr>
 *   <tr><td>{@code metricSnapshotJson}</td><td>{@code NOT NULL}</td>
 *       <td>判定依据快照 —— PRD §C.1.9 硬约束②「判定依据必须落库、可回放」。
 *           它是<b>当时</b>的分数，不是"当前分数"；没有它，一次判定在复盘时
 *           只能靠"现在重新算一遍"，而客户的数据每天都在变</td></tr>
 *   <tr><td>{@code thresholdVersion}</td><td>{@code NOT NULL}</td>
 *       <td>阈值版本 —— PRD §C.1.9 硬约束③「判定（{@code verdict.threshold_version}）
 *           只可新增版本，不可原地覆盖」。没有它，"这个结论是按哪套口径下的"无从回答，
 *           而口径会因真实数据校准而变（config #4/#33/#45 全部标注"需真实数据校准"）</td></tr>
 * </table>
 *
 * <h2>🛑 本记录<b>不</b>持有 {@code created_at} / {@code updated_at}</h2>
 * 与退款域 {@code RefundStatementRow} 的既定事实同源：写入路径把它们交给库层
 * （{@code DEFAULT now()}），读路径由库回填。但本记录<b>额外</b>持有一个
 * {@link #recordedAt()} 并由调用方显式给定 —— 对 {@code cycle_assessment}
 * 而言，"评估是什么时候提交的"这一业务事实就是 {@code created_at}
 * （V5 该表<b>没有</b>独立的 {@code assessed_at} 列）。
 *
 * <p>故写入路径把 {@code created_at} <b>显式</b>绑成 {@code recordedAt}，
 * 而不是省略它让库取 {@code now()}。理由是一条很具体的纪律：
 * 「两个时间源」会让"评估提交"与"判定落库"两个动作的先后关系
 * 在跨机时钟漂移时出现倒挂 —— 而判定链的取证价值正建立在先后关系上。
 * 用一个调用方给定的时刻贯穿两个动作，是这个链上唯一不会漂移的做法。
 *
 * <h2>🛑 V8 起 {@link #branch()} 可空 —— 本记录因此承载<b>两个阶段</b></h2>
 * 契约把「周期评估提交」（C4）与「判定结论落库」（F1）拆成两个 operationId，
 * 而两阶段的载体是同一张 {@code cycle_assessment} 表。区分点是
 * <b>判定分支是否已经产生</b>：
 * <pre>
 *   阶段一（C4 已提交、判定未发生）  branch == null      ——  "待判定"
 *   阶段二（F1 已落结论）            branch != null      ——  五值之一
 * </pre>
 * <p>🛑 <b>这两个形态在业务上完全不同，不得互相回落</b>：
 * {@code null} 是"系统还没判"（人需要去判），而
 * {@link VerdictBranch#HUMAN_REVIEW 人工复核} 是"系统判了，结论是交给人" ——
 * 后者是一条<b>已经作出的结论</b>，会进判定历史与协商举证。把前者写成后者，
 * 等于让"这一单还没判"在证据链里永久变成"这一单判了、且判为挂起"。
 *
 * <p>同一个区分也解释了 F2 的差集为何仍然成立：「有依据而无结论」
 * （{@code cycle} 有行 + 无 {@code verdict} 行）同时覆盖 C4 待判定与 D5 挂起两形态，
 * 因为 F1 在 C4 之后<b>不新增</b> {@code cycle} 行，只补 {@code verdict} 行。
 *
 * @param cycleId            周期评估主键（PK）
 * @param customerId         客户（必填）
 * @param sequenceNo         第 N 次评估（CHECK ≥ 1）
 * @param asValue            {@code AS_refund} 0–1；{@code null} = 样本不足（<b>不是 0</b>）
 * @param asDimensionsJson   A1/A2/A3/A4 + applicable（{@code NOT NULL}）
 * @param metricSnapshotJson 判定依据快照（{@code NOT NULL}，不可覆盖）
 * @param gapDays            样本护栏：应填天数（&lt; 7 → 标"样本不足"）
 * @param branch             四分支 + 人工复核（5 值）；🛑 {@code null} = <b>判定尚未发生</b>
 *                           （C4 阶段一），V8 起库层该列可空
 * @param effectVerdict      E1–E5；{@code null} = 未定
 * @param adherenceState     达标 / 不足 / 样本不足
 * @param improvementRate    改善率（同源公式，负值不截断）；{@code null} = 不计算
 * @param moduleScoresJson   模块 0–16（M1–M5）（{@code NOT NULL}）
 * @param thresholdVersion   阈值版本（{@code NOT NULL}，不可覆盖）
 * @param bandTrendNote      手环趋势说明（U-15：承载"缺失标 null 不补 0 / 未佩戴不记不利 / 自愿"）
 * @param recordedAt         评估提交时刻（写入路径显式给定，见类注释）
 * @param createdBy          操作人
 */
public record CycleAssessmentRow(
        UUID cycleId,
        UUID customerId,
        int sequenceNo,
        BigDecimal asValue,
        String asDimensionsJson,
        String metricSnapshotJson,
        Integer gapDays,
        VerdictBranch branch,
        EffectVerdict effectVerdict,
        AdherenceState adherenceState,
        BigDecimal improvementRate,
        String moduleScoresJson,
        String thresholdVersion,
        String bandTrendNote,
        Instant recordedAt,
        String createdBy) {

    public CycleAssessmentRow {
        requireNonNull(cycleId, "周期评估主键（cycle_id）");
        requireNonNull(customerId, "客户标识（customer_id）");
        // 🛑 V8 起 branch 不再 requireNonNull：null = "判定尚未发生"（C4 阶段一）。
        //    这条放宽的正当性由库层 V8 的 `ALTER COLUMN verdict DROP NOT NULL` 承担；
        //    应用层在这里【换】一条更强的不变式（见下方"阶段一致性"）。
        requireNonBlank(metricSnapshotJson, "判定依据快照（metric_snapshot）");
        requireNonBlank(asDimensionsJson, "依从四维（as_dimensions_json）");
        requireNonBlank(moduleScoresJson, "模块分（module_scores）");
        requireNonBlank(thresholdVersion, "阈值版本（threshold_version）");
        requireNonNull(recordedAt, "评估提交时刻（recorded_at）");
        // 与 V5 的 CHECK(sequence_no >= 1) 同口径，但报错指向构造点而不是数据库
        if (sequenceNo < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "评估序号必须 ≥ 1：实际=" + sequenceNo
                            + "（库 CHECK 只挡数值范围，而它的成因通常是『忘了填就取了默认 0』"
                            + "这类构造错误，报错应指向构造点）");
        }
        // 🛑 AS 值越界不得截断（截断会静默改分，与 EffectVerdictEngine 同一条纪律）
        if (asValue != null && (asValue.compareTo(BigDecimal.ZERO) < 0
                || asValue.compareTo(BigDecimal.ONE) > 0)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "AS_refund 须在 [0,1] 内：实际=" + asValue.toPlainString()
                            + "（越界不得截断）");
        }
        // 🛑 样本不足时 as_value 必须为 null（不得填 0）：见 AdherenceState 类注释
        if (adherenceState == AdherenceState.SAMPLE_INSUFFICIENT && asValue != null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "依从状态为『样本不足』却带着 AS 值（" + asValue.toPlainString()
                            + "）—— 样本不足是『没算出来』而非『算出来是 0』。"
                            + "把它填成 0 会让『数据不够』静默变成『客户没配合』");
        }
        // 🛑 V8 新立的不变式：**阶段一致性**（未判定 ⇒ 无效果结论）
        //
        //    论证：`branch` 是"判定是否已发生"的判据（null = 未发生）。
        //    而 `effect_verdict` 是判定结论的组成部分（E1~E5 是效果分支的落点）。
        //    若 branch 为 null 却带着 effect_verdict，说明有一处把"效果候选"
        //    当成了"已作出的结论"落进依据行 —— 那一行会让复盘看到
        //    "这一轮没有判定分支，却有一个效果结论"，两者互相矛盾且不可覆盖。
        //
        //    🛑 反向不成立（branch != null 时 effect_verdict 可为 null）：
        //       D5 挂起恰恰就是"分支已定（交给人）而效果未定"—— 这是合法形态，
        //       不得用这条不变式把它拦掉（那会让挂起轮次写不进去，退回缺口③）。
        if (branch == null && effectVerdict != null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "判定分支为空（判定尚未发生）却带着效果结论（" + effectVerdict.label()
                            + "）—— 阶段一（C4 评估提交）只落评估依据，不落任何判定结论。"
                            + "该组合会让复盘看到一条『没有分支却有结论』的依据行，"
                            + "而依据是不可覆盖的");
        }
        // 🛑 对称的另一半：判定分支为空 ⇒ 依从状态也不该被"判定化"
        //
        //    依从状态（达标/不足/样本不足）是判定的**输入**而非结论，
        //    故严格说它可以在阶段一有值。但 D5 挂起的判据之一是"样本不足"，
        //    而阶段一的常见成因是"这一轮评估刚提交、还没走到判定" ——
        //    两处的"样本不足"含义不同（前者是判定的成因，后者是评估的护栏）。
        //    为了不让两者在数据里混同，阶段一【要求】adherenceState 有值
        //    （它是评估侧可算的事实），但【允许】其为任何合法值。
        //    换言之：这里不加额外约束 —— 记下这条推理是为了说明"为何不拦"。
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失，拒绝写入");
        }
    }

    /**
     * 本行是否处于<b>阶段一</b>（C4 已提交、判定尚未发生）。
     *
     * <p>判据就是 {@code branch == null} —— 见类注释「V8 起 branch 可空」。
     * 🛑 本方法<b>不</b>叫 {@code isPending()} 或 {@code isSuspended()}：
     * 那两个词在本域已经被 D5「人工复核」占用（那是一个<b>已作出</b>的结论）。
     * 用同一个词表达"未判定"与"判为挂起"，正是这一改动要防的混淆。
     */
    public boolean isAwaitingVerdict() {
        return branch == null;
    }

    /**
     * 构造<b>阶段一</b>的一行（C4 周期评估提交：依据已落、判定未发生）。
     *
     * <p>它是一个具名工厂而非又一个全参构造调用，理由是可读性即正确性：
     * 全参构造里 {@code branch} 与 {@code effectVerdict} 都传 {@code null}，
     * 读代码的人无法从调用点看出"这是刻意未判定"还是"忘了填"。
     * 具名工厂把这件事写明，并把两个 {@code null} 的语义固定住。
     *
     * <p>🛑 它<b>不</b>提供 {@code improvementRate} 参数（固定为 {@code null}）：
     * 改善率是"本次 vs 基线"的对比量，属判定侧；阶段一尚未判定，故不计算。
     * 留一个可传的改善率会为"评估阶段先算一个改善率"留位置 ——
     * 而那个数一旦落库就不可覆盖，届时它与判定阶段算出的值可能不同，
     * 复盘时无法分辨哪个是判定依据。
     */
    public static CycleAssessmentRow awaitingVerdict(
            UUID cycleId,
            UUID customerId,
            int sequenceNo,
            BigDecimal asValue,
            String asDimensionsJson,
            String metricSnapshotJson,
            Integer gapDays,
            AdherenceState adherenceState,
            String moduleScoresJson,
            String thresholdVersion,
            String bandTrendNote,
            Instant recordedAt,
            String createdBy) {
        return new CycleAssessmentRow(cycleId, customerId, sequenceNo, asValue,
                asDimensionsJson, metricSnapshotJson, gapDays,
                // 🛑 两个 null 是本次构造的全部要点：判定分支未产生、效果结论未产生
                null, null,
                adherenceState, null, moduleScoresJson, thresholdVersion,
                bandTrendNote, recordedAt, createdBy);
    }

    private static void requireNonBlank(String v, String name) {
        requireNonNull(v, name);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    name + " 不得为空白字符串 —— 空白与缺失在库层表现不同"
                            + "（空白会落成一个看起来有值的空串），但业务含义同样是缺依据");
        }
    }
}