package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * {@code band_sync_probe.probe_source} 的<b>3 值封闭词汇</b> —— 这条探针的 N 是<b>怎么来的</b>。
 *
 * <h2>权威来源</h2>
 * {@code V5} 第 921~922 行的 {@code CHECK (probe_source IN ('运行时探测','厂商文档','保守假设'))}
 * —— 三个<b>中文</b>枚举值（与字典 §4.6 取值表逐字一致）；V22 的 {@code v_probe_srcs} 同集合。
 *
 * <h2>🛑🛑 这一列不是"备注字段"，它是 N 的【可信度等级】</h2>
 * §2.8.7⑥ 把"N（留存窗口）"与"s（单次同步成功率）"同时列为
 * <b>未取证的外部依赖</b>，并在④里给出明确的引用纪律：
 * <pre>
 *   🛑 「补拉必然跨 f*」不成立；「可能跨」成立且有明确前提（N 与 s）。
 *      N（留存窗口）与 s（单次同步成功率）均未取证
 *      → 不得写成「补拉可让 A3 达标」
 * </pre>
 * ⇒ 于是"这个 N 是怎么来的"决定了它<b>能不能被引用</b>：
 * <ul>
 *   <li>{@link #RUNTIME_PROBE}（运行时探测）—— 实测值，可作 A3 取数依据；</li>
 *   <li>{@link #VENDOR_DOC}（厂商文档）—— §2.8.7③ 明示"<b>文档自证、待真机复核</b>"，
 *       <b>复核前不得当成实测</b>；</li>
 *   <li>{@link #CONSERVATIVE_ASSUMPTION}（保守假设）—— §2.8.7 逐字
 *       「未实测前按保守假设 ≤7 天」。它<b>不是证据</b>，是"上限未取证时的占位"。</li>
 * </ul>
 * <p>🛑 故本枚举是<b>封闭</b>的，且 V22 的 (5b) 显式先判一次（表层已有 CHECK）——
 * 若允许自由文本，这三种可信度会在台账里被<b>拉平成一个字符串</b>，
 * 而后人就无法只靠数据回答"这条 N 我能不能用"。
 *
 * <h2>🛑 为什么它是中文枚举值（与仓库整体"落库用英文"的直觉相反）</h2>
 * 这不是本枚举的选择，而是 <b>V5 既有 DDL 的既成事实</b>（且与字典 §4.6 取值表一致）。
 * A-3 <b>不改</b>它：改一个已落库的枚举值属 schema 变更 + 契约变更，
 * 且会让历史行变成"取值不在枚举内"的脏数据。本枚举<b>原样承载</b>。
 * <p>若将来统一为英文，须一次改动 V5 CHECK、字典 §4.6、契约与历史数据，
 * 并同步本枚举与 {@code RlsV5EntityIsolationTest} 的取值断言。
 */
public enum ProbeSource {

    /** 运行时实测（{@code getValidHistoryDates} 探测出来的跨度）—— <b>可作 A3 取数依据</b>。 */
    RUNTIME_PROBE("运行时探测", true),

    /**
     * 厂商文档给出、<b>待真机复核</b> —— §2.8.7③ 逐字「文档自证、待真机复核」。
     * <p>🛑 {@code verifiable=false}：它<b>不能</b>被当成实测。若当成实测引用，
     * 一旦文档与真机不符（例如真实窗口只有 3 天而文档写 7 天），
     * 超出窗口的日期设备<b>不返回数据</b> ⇒ 被记成<b>假缺失</b>。
     */
    VENDOR_DOC("厂商文档", false),

    /**
     * 保守假设 —— §2.8.7 逐字「未实测前按保守假设 <b>≤7 天</b>」。
     * <p>🛑 {@code verifiable=false}，且它的方向是刻意的：<b>取小而保守</b>。
     * 取大 = 回溯更远 = 更多超窗口日期 = 更多假缺失；取小 = 少拉几天 = 只是少覆盖，
     * 而"少覆盖"是<b>可观测</b>的（分母仍在，分子少算），"假缺失"是<b>会冤枉客户</b>的。
     * 这正是 §2.8.7⑤「宁可少扣、不可错扣」在 N 的方向上的同一条原则。
     */
    CONSERVATIVE_ASSUMPTION("保守假设", false);

    private final String code;
    private final boolean verifiable;

    ProbeSource(String code, boolean verifiable) {
        this.code = code;
        this.verifiable = verifiable;
    }

    /** 落库字面量（= {@code band_sync_probe.probe_source} 的取值，中文）。 */
    public String code() {
        return code;
    }

    /**
     * 这条探针的 N 是否<b>可作 A3 取数依据</b>。
     *
     * <p>🛑 只对 {@link #RUNTIME_PROBE} 为 {@code true}。这个布尔是给
     * <b>取数侧</b>用的守卫：A3 要算"应戴天"与"有效佩戴天"，若拿一条
     * {@code 厂商文档 / 保守假设} 的 N 去决定"回溯到哪一天"，
     * 就把一个<b>未取证的上限</b>当成了实测边界。
     * <p>本方法<b>不</b>阻止写入（{@code band_sync_probe} 的用途正是记录"我还没取证"），
     * 它阻止的是<b>把未取证的值当成取证的值使用</b>。
     */
    public boolean verifiable() {
        return verifiable;
    }

    /** 全部落库字面量（供门禁与契约逐字比对）。 */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(ProbeSource::code).toList();
    }

    /**
     * 由落库字面量解析；<b>未登记的值即抛错</b>（fail-closed，与 V22 的 (5b) 同力度）。
     *
     * @param raw 允许 {@code null}（该列可空 = "尚未标注来源"）；非空则必须在 3 值内
     * @return {@code null} 表示"未标注来源"—— 🛑 它与
     *         {@link #CONSERVATIVE_ASSUMPTION} <b>不是一回事</b>：
     *         前者是"我还不知道"，后者是"我知道但只能给保守上限"。
     *         合并二者会让 {@code null} 悄悄获得"保守假设"这个已被定义的语义。
     */
    public static ProbeSource parseNullable(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            // 空串归一成 null —— 与 V22 函数里 `nullif(btrim(...), '')` 的口径一致
            // （两处都要归一，否则会出现"Java 说是空串、库里是 NULL"的差异）。
            return null;
        }
        for (ProbeSource s : values()) {
            if (s.code.equals(v)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "probe_source = '" + raw + "' 不在已登记的 3 值内（"
                        + String.join(" / ", allCodes()) + "）。"
                        + "🛑 该列的用途是区分 N 的【可信度】：『运行时探测』是实测、"
                        + "『厂商文档』待真机复核、『保守假设』是上限未取证时的占位"
                        + "（§2.8.7 明示『未实测前按保守假设 ≤7 天』）。"
                        + "若允许自由文本，这三种可信度会在台账里被拉平，"
                        + "而后人无法只靠数据回答『这条 N 我能不能用』");
    }
}