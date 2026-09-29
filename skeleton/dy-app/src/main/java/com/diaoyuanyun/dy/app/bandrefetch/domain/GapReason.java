package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code band_daily_coverage.gap_reason} 的<b>7 值封闭词汇</b> —— "这一天为什么没有数据"。
 *
 * <h2>权威来源（三处逐字一致，本枚举是第四处、由门禁机械核对）</h2>
 * <ol>
 *   <li>契约 {@code contract/openapi-v1.0.0.yaml} 的 {@code BandDailyData.gap_reason}
 *       {@code enum: [no_open, sync_failed, not_worn, compliant_removal,
 *       involuntary_technical, beyond_retention_window, unknown]}；</li>
 *   <li>{@code V5} 第 995~997 行的 CHECK（同 7 值）；</li>
 *   <li>{@code V22} 的 {@code register_daily_coverage()} 内 {@code v_gap_reasons} 数组。</li>
 * </ol>
 * <p>一致性由 {@code BandRefetchGateTest} 机械核对（枚举 ↔ V22 数组 ↔ 契约 enum）。</p>
 *
 * <h2>🛑🛑 {@link #NOT_WORN} 是【全 7 值里唯一可扣分】的一类</h2>
 * §2.8.7⑤ 第②行逐字：「缺失天计 <b>0</b>（扣分）—— <b>唯一应扣的一类</b>」。
 * A3 进「依从性」维度、并进一步影响 {@code AS_refund} ⇒
 * <b>把这一天错判成 {@code not_worn}，代价落在客户身上</b>。
 * <p>故本枚举<b>不提供</b>任何"从缺失信号推断原因"的方法 ——
 * §2.8.7⑤ 的三分表判据（"有脱腕记录 {@code isWear=0}" ∧ "无技术性信号"）
 * 需要<b>两列合看</b>，而本枚举只看 {@code gap_reason} 一列。
 * 把它做成 {@code GapReason.inferFrom(...)} 会把一个<b>跨列</b>判定
 * 伪装成<b>单列</b>判定，从而与 V22 的 (5c) 形成两处口径。
 *
 * <h2>🛑🛑 一处【登记待裁】的真实冲突：第「① 客户未同意佩戴（结构性）」无槽位</h2>
 * §2.8.7⑤ 的三分表第 ① 行逐字：
 * <pre>
 *   | ① | 客户未同意佩戴 | 意愿字段 = 暂不佩戴 | **结构性** |
 *       **applicable=False、权重重归一、不扣分** |
 * </pre>
 * 而本 7 值里<b>没有任何一个</b>表示"客户未同意佩戴"：
 * <ul>
 *   <li>{@link #NO_OPEN} 是"客户<b>根本没打开小程序</b>"——§2.8.7② 明确定义它为
 *       「不是失败，是『客户没来』」，与"未同意佩戴"是两件事（后者是<b>已表达的意愿</b>）；</li>
 *   <li>{@link #COMPLIANT_REMOVAL} 是"住院/洗浴/桑拿按说明书摘下"——
 *       有 {@code pause_period} 申报，是<b>已同意佩戴者</b>的合规摘除；</li>
 *   <li>{@link #UNKNOWN} 是兜底，但用它表达"结构性"会违反
 *       {@code config #44} 的硬约束：<b>结构性不可观测占比须按采集模式单列</b> ——
 *       一旦这类被记成 {@code unknown}，它就<b>无法被单列</b>，
 *       于是"结构性不可观测"与"原因未知"在台账里合并成一个数。</li>
 * </ul>
 * <p>🛑 <b>增删枚举属契约变更</b> ⇒ 按本仓纪律<b>登记待裁、不代拍</b>。
 * A-3 只做<b>逻辑自洽</b>层面的断言（见 {@link #NOT_WORN} 与 {@code WearState} 的组合门禁），
 * <b>不</b>替上游新增取值。裁定请求见
 * {@code _work/a3-band-refetch-ruling-request.md} §三点五（三个候选方案 A/B/C）。
 * <p>⚠️ 在裁定落地前，「① 结构性」这一类<b>报不出来</b> ——
 * 这是一个<b>已知且登记过的</b>口径缺口（同 V22 文件头硬边界①），不是本枚举的疏漏。
 *
 * <h2>🛑 有一条纪律写在本枚举里、但不属于本域：客户恒不可见</h2>
 * 契约该字段的 {@code x-visible-to: [therapist, meridian, admin]} +
 * {@code description: 🔴 客户恒 403 / 不下发（硬约束，配置不得放开）}。
 * 本枚举<b>不</b>实现可见性（那在 {@code dy-security} 的
 * {@code DerivedFields} 白名单里，已登记 {@code gap_reason}）——
 * 此处只<b>转述</b>它，因为读本枚举的人正是在写"往这一列写值"的代码，
 * 而"这一列客户永远看不到"会直接影响"该不该在这里放可读文案"的判断。
 */
public enum GapReason {

    /**
     * 客户<b>根本没打开小程序</b>。§2.8.7② 逐字：「<b>否</b>（不是失败，是『客户没来』）|
     * 仅作覆盖率分母说明」。
     * <p>🛑 <b>不扣分</b>。且它与"客户没戴"是两件完全不同的事 ——
     * §2.8.7 把这一点列为「本方案最大的静默风险：<b>把『客户没打开』读成『客户没戴』</b>」。
     * 故本常量与 {@link #NOT_WORN} 在类型上并列而<b>不</b>互相派生。
     */
    NO_OPEN("no_open", Nature.NOT_CHARGEABLE),

    /**
     * 触发<b>但同步失败</b>（技术性：蓝牙 / 授权 / 电量 / 占用 / 干扰 / 平台）。
     * §2.8.7② 逐字：「<b>否 —— 不怪客户</b> | 并入既有技术性缺失（G7）」。
     * <p>🛑 对应客户端四态里的「同步失败（原因类别 + 可执行下一步）」，
     * 且 §2.8.7② 有一条<b>文案层</b>硬要求：「②类失败<b>绝不显示</b>『没戴/未佩戴/缺失』
     * （技术失败与未佩戴须在<b>文案层彻底分开</b>）」——
     * 本枚举把两件事分成两个常量，正是让那条文案要求有落脚处。
     */
    SYNC_FAILED("sync_failed", Nature.TECHNICAL),

    /**
     * 同步成功但<b>确实没戴</b>（行为性）。§2.8.7② 逐字：
     * 「<b>是 —— 唯一可扣分情形</b> | 进 A3 行为性」。
     *
     * <p>🛑🛑 <b>这是全 7 值里唯一 {@link Nature#CHARGEABLE} 的一类</b>。
     * 写入它的前置条件是 §2.8.7⑤ 第②行的<b>两条合取</b>：
     * <pre>
     *   有前置有效记录  ∧  有脱腕记录 isWear=0 / 长段规律性零数据  ∧  无技术性信号
     * </pre>
     * 其中第三条由 {@code V22} 的 (5c) 在库层强制（{@code not_worn} 与技术性
     * {@code is_wear} 不得共存）；前两条需要历史上下文，<b>不在本枚举与
     * 本迁移的判定能力内</b>（V22 只收单日事实）。
     * <p>⇒ 故本迁移保证的是"<b>不会把技术性写成行为性</b>"，
     * 而<b>不</b>保证"任何写成 not_worn 的都真的成立" ——
     * 后者需要一个跨日的判定引擎，属另一条链路。这条边界必须写清，
     * 否则读代码的人会以为库层已经守住了 not_worn 的全部前置条件。
     */
    NOT_WORN("not_worn", Nature.CHARGEABLE),

    /**
     * <b>合规摘除</b>（住院 / 洗浴 / 桑拿，按说明书摘下）。§2.8.7⑤ 附表逐字：
     * 「有 {@code pause_period} 申报 | 合规摘除 | <b>暂停期从分母剔除</b> + 不扣分」。
     * <p>🛑 注意它的处置<b>不是</b>"不计缺口"，而是"<b>从分母里剔除</b>"——
     * 两者差别很大：前者分母仍含该天（于是它不是缺口但占着分母），
     * 后者分母直接少一天。这一点由 {@code band.pause_period_json} 承担（既有表，
     * 本迁移不碰），本枚举只负责让这一天的原因<b>可归因</b>。
     */
    COMPLIANT_REMOVAL("compliant_removal", Nature.NOT_CHARGEABLE),

    /**
     * <b>技术性</b>缺失（§2.8.7⑤ 第③行：设备/同步取不到）。
     * <p>🛑 这是 {@code is_wear ∈ (-1,255)} 时<b>应该写进本列</b>的取值 ——
     * 见 V22 的 (5c) 报错消息：「技术性缺失请写 gap_reason = 'involuntary_technical'」。
     * <p>§2.8.7⑤ 逐字：性质 = 技术性；处置 = {@code applicable=False}、不扣分（并入 G7）。
     */
    INVOLUNTARY_TECHNICAL("involuntary_technical", Nature.TECHNICAL),

    /**
     * <b>超出留存窗口</b>。§2.8.7③「起始日」行逐字警告：
     * 「🔴 <b>不得回溯超过 {@code floor}</b>（超窗口日期设备不返回 → 只会制造<b>假缺失</b>）」。
     * <p>🛑 本常量的存在意义就是<b>把假缺失标出来</b>：超窗口的日期设备必然不返回数据，
     * 若那被记成 {@link #NOT_WORN}，就是本仓反复记载的"<b>对客户不利</b>"形态；
     * 若被记成 {@link #UNKNOWN}，它就<b>无法与"原因真的未知"区分</b>。
     * <p>⚠️ 另有 §2.8.7③ 的 A6 警告：「补拉必然遇到『某些天设备不返回数据』；
     * 若无法区分『超出留存窗口』与『客户确实没戴』，就会把『窗口外丢失』误记为
     * 『客户没戴』」—— 本常量正是那条警告的落点。
     */
    BEYOND_RETENTION_WINDOW("beyond_retention_window", Nature.TECHNICAL),

    /**
     * 原因<b>未知</b>（兜底）。
     *
     * <p>🛑 它的处置<b>不是</b>随便的兜底：§2.8.7⑤ 末段「A6 未解时的保守记账（C1~C6）」
     * 逐字要求 ——
     * <pre>
     *   不补 0 ／ 标 missing_reason=unknown 【优先归技术性】 ／ 未知缺失天不进分子 ／
     *   分母仍按台账应戴天保留、不得逐天剔除 ／
     *   设「未知缺失占比」门（建议初始 30%，待校准），超阈 → A3 整维 applicable=False ／
     *   评估页显式暴露「原因未知缺口 N 天」（中性措辞）
     * </pre>
     * ⇒ 即：未知<b>按技术性处理</b>（不扣分），但必须<b>单列计数</b>并在超阈时让整维失效。
     * 故本常量的 {@link Nature} 是 {@link Nature#TECHNICAL} 方向 ——
     * 落在 {@link Nature#fallbackTechnical()} 上（与真正的技术性分开登记，
     * 因为"未知占比门"要能把它单独数出来）。
     */
    UNKNOWN("unknown", Nature.UNKNOWN_FALLBACK);

    /**
     * A3 处置方向 —— 它决定"这一天能不能扣客户的分"。
     *
     * <h2>🛑🛑 为什么是【四个取值】而不是"可扣分 / 不可扣分"两个</h2>
     * §2.8.7⑤ 的处置列实际有<b>四种</b>互不相同的后果：
     * <ol>
     *   <li>{@link #CHARGEABLE} —— 缺失天计 0（<b>唯一扣分</b>）；</li>
     *   <li>{@link #TECHNICAL} —— {@code applicable=False} 那一类，<b>不扣分、并入 G7</b>；</li>
     *   <li>{@link #NOT_CHARGEABLE} —— 不扣分，<b>且不并入 G7</b>（"客户没来"甚至不是缺口）；</li>
     *   <li>{@link #UNKNOWN_FALLBACK} —— 不扣分，但<b>要进"未知缺失占比"门</b>，
     *       超阈（建议初始 30%）⇒ A3 <b>整维</b> {@code applicable=False}。</li>
     * </ol>
     * 把它们压成两个布尔，第 ③ 与第 ④ 类就会被合并 —— 而第 ④ 类是
     * <b>唯一会触发"整维失效"的</b>，合并后那个门就失去了它的输入。
     */
    public enum Nature {
        /** <b>唯一可扣分</b>：缺失天计 0。 */
        CHARGEABLE,
        /** 技术性：{@code applicable=False}、不扣分、并入 G7。 */
        TECHNICAL,
        /** 不扣分、也不并入 G7（"客户没来"甚至不属于缺口）。 */
        NOT_CHARGEABLE,
        /**
         * 未知：<b>按技术性方向处理</b>（{@link #fallbackTechnical()} 为 {@code true}），
         * 但必须单列计数并进"未知缺失占比"门。
         */
        UNKNOWN_FALLBACK;

        /**
         * 本性质在 A3 取数时是否<b>按技术性方向</b>处置（= 不扣分）。
         *
         * <p>🛑 包含 {@link #TECHNICAL} <b>与</b> {@link #UNKNOWN_FALLBACK} 两类 ——
         * 依据是 §2.8.7⑤ 末段「标 {@code missing_reason=unknown} <b>优先归技术性</b>」。
         * <p>🛑 {@link #NOT_CHARGEABLE} <b>不</b>在此列：它是"客户没来"
         * 或"合规摘除"，两者都<b>不是</b>技术性缺失 —— 把它们并进 G7
         * 会让"技术性缺失率"这个指标虚高，而它正是 G7 的取数项。
         */
        public boolean fallbackTechnical() {
            return this == TECHNICAL || this == UNKNOWN_FALLBACK;
        }
    }

    private final String code;
    private final Nature nature;

    GapReason(String code, Nature nature) {
        this.code = code;
        this.nature = nature;
    }

    /** 落库字面量（= 契约 enum + 表 CHECK 的取值）。 */
    public String code() {
        return code;
    }

    public Nature nature() {
        return nature;
    }

    /** 是否<b>唯一可扣分</b>的那一类（{@code not_worn}）。 */
    public boolean chargeable() {
        return nature == Nature.CHARGEABLE;
    }

    /**
     * 🛑 本取值是否与"技术性"这一性质冲突 —— 即 {@code not_worn} 的专属判据。
     *
     * <p>它被单独命名（而不是让调用方写 {@code reason == GapReason.NOT_WORN}）的理由：
     * {@code V22} 的 (5c) 是这个判据的库层落点，而"哪一侧是判据"这件事
     * 在两侧代码里必须是同一句话。把它命名出来，两处就都指向同一个概念。
     */
    public boolean meansCustomerDidNotWear() {
        return this == NOT_WORN;
    }

    /** 全部落库字面量（供门禁与契约逐字比对）。 */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(GapReason::code).toList();
    }

    /**
     * 由落库字面量解析；<b>未登记的值即抛错</b>（fail-closed，与 V22 的 (5a) 同力度）。
     *
     * @param raw 允许 {@code null}（该列可空 = 该日未判定原因）；非空则必须在 7 值内
     * @return {@code null} 表示"该日未判定原因"——🛑 它与 {@link #UNKNOWN}
     *         <b>不是一回事</b>：前者是"系统<b>还没判</b>"，后者是"系统判了、结论是
     *         <b>原因不可知</b>"。合并二者会让"还没跑的判定"凭空获得
     *         {@code unknown} 的处置（进未知占比门），从而把一个<b>未处理</b>的日子
     *         算成一个<b>已分类</b>的日子 —— 而"未知缺失占比"门的分子正是
     *         "已分类为未知"的数量。
     */
    public static GapReason parseNullable(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            // 空串归一成 null —— 与 V22 函数里 `nullif(btrim(...), '')` 的口径一致
            return null;
        }
        Optional<GapReason> hit = Arrays.stream(values()).filter(r -> r.code.equals(v)).findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "gap_reason = '" + raw + "' 不在契约登记的 7 值内。已登记 = "
                        + String.join(", ", allCodes())
                        + "。🛑 契约 BandDailyData.gap_reason 的 enum 与本表 CHECK 逐字一致，"
                        + "新值属【契约变更】—— 不得在应用层悄悄扩权。"
                        + "⚠️ 已知缺口（登记待裁）：§2.8.7⑤ 的第「① 客户未同意佩戴（结构性）」"
                        + "在现行 7 值里没有槽位 ⇒ 该类的『结构性不可观测占比』目前无法单列"
                        + "（违反 config #44 的『须按采集模式单列』）"));
    }
}