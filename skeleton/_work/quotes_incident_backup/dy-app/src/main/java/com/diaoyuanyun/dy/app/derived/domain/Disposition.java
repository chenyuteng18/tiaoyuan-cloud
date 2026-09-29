package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 处置出口 —— <b>组合判定出口</b>的落点
 * （PRD P0-12 / P0-24：「判定出口 = {@code effect_verdict} × {@code adherence_state}
 * × {@code risk_flag} 组合 → {@code disposition}」）。
 *
 * <h2>🛑 本枚举有两个同值域的使用方，且它们的归属<b>不同一张表</b></h2>
 * PRD 在<b>两处</b>用到了 {@code disposition} 这个名字，值域完全相同
 * （{@code 继续原方案 / 调整后继续 / 转基础服务 / 退款终止 / 建议就医}），
 * 但落点不同：
 * <pre>
 *   PRD §C.1.7 L1787   06.§七 5 类处理结论   → refund.disposition       （工单结论码）
 *   PRD §C.1.7 L1794   verdict.{…,disposition} → 判定结论的组合出口     （组合出口）
 * </pre>
 * 前者是<b>工单</b>在人协商后写下的最终结论，后者是<b>判定</b>由三 enum 组合推出的建议落点。
 * 二者<b>值域同、语义不同源</b>：工单结论可以因协商结果偏离系统建议
 * （业务方原话：「经络师再根据客户得数据看是否同意退款事宜」，见 P0-13/P0-14 依据）。
 *
 * <h2>🛑 V8 起缺口<b>已闭合</b>（组合出口侧）—— 本节保留以说明为何只剩工单侧未接</h2>
 * {@code verdict.disposition} 列已由 V8 建（{@code V8__verdict_two_phase_alignment.sql} 第三节），
 * 裁定依据是 PRD §C.1.7 L1794「{@code verdict.{…, disposition}}」——
 * 组合出口的三个输入（{@code effect_verdict} / {@code adherence_state} / {@code risk_flag}）
 * <b>全在 {@code verdict} 表内</b>，跨表取数只为写一列不合理。
 *
 * <p>⚠️ 但 {@code refund.disposition}（L1787 的工单结论码）<b>仍未建</b> ——
 * 那是<b>另一件事</b>：它是工单人在协商后写下的最终结论，可随协商推进改变，
 * 与"系统按三 enum 组合一次推出、不可覆盖"的组合出口在<b>写入方、可改写性、
 * 可见性</b>三方面全都不同。合并它们就等于让"客户是否同意退款"与
 * "系统认为该怎么办"无法区分。故本版只闭合组合出口侧。
 *
 * <p>🛑 本枚举与 {@link #SCHEMA_GAP_NOTE} 都<b>不改</b>：那条 note 会被写进
 * V8 之前落的历史依据快照 —— 它是那个时点的<b>不可覆盖</b>事实。
 * 复盘时能看出"这一单的处置是按快照内的价码走的，而不是读了一张当时还不存在的列"。
 * 修改它等于篡改历史证据。
 * <ol>
 *   <li><b>不擅自建列</b>：给 {@code verdict} 加一列看似"顺手"，但它会让
 *       "系统建议的处置"与"人工协商后的结论"共用一列 —— 而两者的
 *       写入方、可改写性、可见性<b>全都不同</b>（前者由系统一次写入不可覆盖，
 *       后者由门店在协商后填写且可随协商推进改变）。合并它们，
 *       就等于让"客户是否同意退款"这一事实与"系统认为该怎么办"无法区分。</li>
 *   <li><b>不悄悄丢掉</b>：组合出口是 PRD P0-24 的验收项，若本版完全不产出它，
 *       那条验收就成了空转。故本类<b>产出</b> {@link Disposition}（内存对象），
 *       由 {@code VerdictLedger} 把它落进 {@code evidence_snapshot} ——
 *       {@code evidence_snapshot} 的定义就是「判定依据快照（<b>不可覆盖</b>）」，
 *       把组合出口放进依据快照，既满足"判定即动作须落库可得"，又不需要新建列。</li>
 *   <li><b>缺口登记在案</b>：见 {@link #SCHEMA_GAP_NOTE}，由
 *       {@code VerdictSchemaContractTest} 断言"真库里确实没有这两列" ——
 *       使"缺列"从一句口头欠账变成一条会被守护的事实，且补齐时该断言会红并提示同步。</li>
 * </ol>
 *
 * <h2>组合是真组合，不是"取一个 enum 再查表"</h2>
 * PRD P0-12 逐字：「不得混入单一 enum（E5 强制人工录入）」。
 * 故本类的 {@link #resolve} 入参<b>必须</b>是三件独立的事实，而不是一个已经压平的
 * branch 字符串 —— 若入参是 branch，那么"依从达标但核心指标无改善"
 * 与"依从不足"就会在调用点被提前压成两个字符串，而
 * 「依从不足 + 明显改善」这种组合<b>在压平处不可能被表达</b>，
 * 于是它的处置（调整后继续，而非强化干预）会永远无法推导。
 */
public enum Disposition {

    /** 继续原方案 —— 效果稳定/改善且依从达标且无风险标签。 */
    KEEP_PLAN("继续原方案"),

    /** 调整后继续 —— 有改善但依从不足，或依从达标但无效（须重定方案）。 */
    ADJUST_AND_CONTINUE("调整后继续"),

    /** 转基础服务 —— 效果与依从双双不成立，疗程退回基础服务期。 */
    BASIC_SERVICE("转基础服务"),

    /**
     * 退款终止 —— 仅在<b>效果类协商通路</b>下由人协商后成立。
     *
     * <p>🛑 系统<b>绝不</b>自动产出本值作为最终结论：PRD P0-13 逐字
     * 「判定结论定位为『对内判定建议 + 置信度』，<b>不面向客户展示、
     * 不可作为对外举证材料</b>」，P0-14 逐字「效果类走协商工单，<b>人在环、
     * 不可自动直出资格结论</b>」。
     * 故 {@link #systemMayAutoRaise()} 对本值为假 —— 系统只能把它作为
     * <b>建议候选</b>写进依据快照，等人在协商中确认。
     */
    REFUND_TERMINATE("退款终止"),

    /** 建议就医 —— 出现加重 / 高危等安全信号时的处置。 */
    ADVISE_MEDICAL("建议就医");

    /**
     * 表结构缺口的<b>唯一登记文案</b>（供服务层写进依据快照、供测试断言）。
     *
     * <p>它被写进 {@code evidence_snapshot} 而不是只留在注释里，理由很实际：
     * 依据快照是判定时点的<b>不可覆盖</b>事实。V8 补上列之后，
     * 一条 V8 之前的老判定快照里仍会带着这句话 —— 复盘时能看出
     * "这一单的处置是按快照内的价码走的，而不是读了一张当时还不存在的列"。
     *
     * <p>🛑 <b>本常量的文本自 V8 起不再更新</b>（V8 已建 {@code verdict.disposition} 列）。
     * 这不是"忘了同步"，而是一条纪律：本文案是<b>历史证据的一部分</b> ——
     * 它已经写进了若干条不可覆盖的快照。改动它会让那些快照声称一件
     * 当时并不成立的事。V8 之后新落的快照仍会带上它，含义变为
     * "本条判定的产生时点早于/独立于 disposition 列的补列"这一沿革说明。
     *
     * <p>🛑 因此本常量<b>刻意保留</b> V8 前那处表述。缺口现状（组合出口侧已闭合、
     * 工单侧仍未建）由 {@code V8__verdict_two_phase_alignment.sql} 与
     * README §5.2 承载，不在此处改写历史。
     */
    public static final String SCHEMA_GAP_NOTE =
            "disposition 表结构缺口：PRD §C.1.7 L1787 写 refund.disposition（工单结论码）、"
                    + "L1794 写 verdict.disposition（组合出口），值域相同但归属两张表；"
                    + "V5/V6 均未建列。本版按『不擅自建列、不悄悄丢掉』处理："
                    + "组合出口落进 evidence_snapshot，工单侧保持未接，缺口待裁定后由 V7+ 补列。";

    private final String dbLabel;

    Disposition(String dbLabel) {
        this.dbLabel = dbLabel;
    }

    /** 落库 / 契约字面（PRD 06.§七 5 类处理结论逐字）。 */
    public String dbLabel() {
        return dbLabel;
    }

    /**
     * 系统是否<b>可以自动提出</b>本处置（作为建议候选）。
     *
     * <p>🛑 仅 {@link #REFUND_TERMINATE} 为假。其余四项都是系统按规则可推导的
     * <b>建议</b>，且仍然只进依据快照、不对外直出（P0-13 定位）。
     * 本属性把 P0-14「效果类不可自动直出资格结论」从文档结论变成可断言事实：
     * {@code VerdictDispositionCombinationTest} 断言"四分支 × 组合中，
     * 没有任何一路会让系统自动产出退款终止"。
     */
    public boolean systemMayAutoRaise() {
        return this != REFUND_TERMINATE;
    }

    /** 五值的全部落库字面（顺序固定，供契约与门禁逐字比对）。 */
    public static List<String> allDbLabels() {
        return Arrays.stream(values()).map(Disposition::dbLabel).toList();
    }

    /** 按落库字面解析；未知一律 fail-closed。 */
    public static Disposition parse(String dbLabel) {
        if (dbLabel == null || dbLabel.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "disposition 必填（5 值之一: " + allDbLabels() + "）");
        }
        String v = dbLabel.trim();
        Optional<Disposition> hit = Arrays.stream(values())
                .filter(d -> d.dbLabel.equals(v))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                "disposition 不在 5 值枚举内: " + dbLabel + "（合法值: " + allDbLabels() + "）"));
    }
}