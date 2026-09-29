package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * {@code band_daily_coverage.is_wear} 的<b>4 值封闭词汇</b> —— 设备当日自报的佩戴状态。
 *
 * <h2>权威来源（逐字）</h2>
 * {@code V5} 第 1001 行的 {@code CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255))}
 * + §2.8.7⑤ 逐字：
 * <pre>
 *   `1`=佩戴（正常）／`0`=脱腕（行为性）／**`(-1,255)`=技术性缺失 → 一律不判行为性
 *   （宁可少扣、不可错扣）**，并须向厂商索要「设备异常原因码」把「传感器失败」
 *   与「客户脱腕」分开
 * </pre>
 *
 * <h2>🛑🛑 为什么 {@link #TECHNICAL_NEG_ONE} 与 {@link #TECHNICAL_255} 是两个常量、而不是一个</h2>
 * 它们在<b>语义上同一类</b>（技术性缺失），但在<b>落库上是两个不同的整数</b>，
 * 而 {@code is_wear} 是 {@code INT} 列 —— 一个常量表达不了两个整数，
 * 若只留一个，另一个取值就必须在某处被"翻译"，而翻译点正是最容易漏掉的地方。
 * <p>🛑 <b>但本枚举刻意【不】声称这两个值有不同含义</b>（不叫
 * {@code SENSOR_FAILURE} / {@code NO_DATA} 这一类名字）：§2.8.7⑤ 末句明确
 * 「须向厂商索要<b>设备异常原因码</b>把『传感器失败』与『客户脱腕』分开」——
 * 即<b>更细的区分是一份尚未取得的外部依赖</b>，不是已知事实。
 * 现在给它们编造不同的名字，就是<b>把一个未取证的区分写成了系统已知的性质</b>。
 * ⇒ 故两个常量合用同一个 {@link Nature#TECHNICAL}，且对外只有
 * {@link #technical()} 这一个判定入口。
 *
 * <h2>🛑 本枚举是"不判行为性"这条红线的唯一落点</h2>
 * {@code band_daily_coverage.gap_reason = 'not_worn'} 是<b>全 7 值里唯一可扣分</b>的一类
 * （§2.8.7⑤ 第②行「缺失天计 0（扣分）」），而 A3 进「依从性」维度、
 * 并进一步影响 {@code AS_refund} ⇒ <b>错扣的代价落在客户身上</b>。
 * 故"技术性 ⟹ 不得判行为性"必须由<b>类型</b>承载（{@link #behavioral()} 与
 * {@link #technical()} 互斥且穷尽），而不是靠每一处取数逻辑各自记得排除。
 * <p>两处同时守这条：本枚举的 {@code behavioral()} / {@code technical()}，
 * 以及 {@code V22} 的 (5c) 显式 RAISE（表层 CHECK <b>挡不住</b>这个组合 ——
 * 两列各有各的合法取值，只有它们的<b>组合</b>非法）。
 */
public enum WearState {

    /** {@code 1} —— 佩戴（正常）。§2.8.7⑤ 逐字「`1`=佩戴（正常）」。 */
    WORN(1, Nature.NORMAL),

    /**
     * {@code 0} —— 脱腕（<b>行为性</b>）。
     *
     * <p>🛑 它是 {@link #behavioral()} 为 {@code true} 的<b>唯一</b>取值，
     * 因而也是"可以给 {@code not_worn} 作证"的唯一取值 ——
     * 见 §2.8.7⑤ 第②行「有脱腕记录 {@code isWear=0}」。
     */
    REMOVED(0, Nature.BEHAVIORAL),

    /**
     * {@code -1} —— 技术性缺失。§2.8.7⑤ 逐字「{@code (-1,255)}=技术性缺失」。
     * <p>🛑 不声称它比 {@link #TECHNICAL_255} "更技术"或"是传感器故障"——
     * 那是未取证的外部依赖，见类注释。
     */
    TECHNICAL_NEG_ONE(-1, Nature.TECHNICAL),

    /** {@code 255} —— 技术性缺失（同上，同为技术性，<b>不</b>声称区别）。 */
    TECHNICAL_255(255, Nature.TECHNICAL);

    /**
     * 佩戴状态的<b>性质</b> —— 它决定"A3 能不能扣这一天的分"。
     *
     * <h2>🛑 为什么要有这一层，而不是在 {@link WearState} 上直接写两个布尔</h2>
     * 因为"行为性"与"技术性"是<b>互斥且穷尽</b>的一个划分（外加"正常"），
     * 用枚举表达时，编译器会强制新增取值时必须选边；用两个独立布尔表达时，
     * 新增取值可以<b>两个都漏</b>（于是它既不可扣分也不被保护 ——
     * 一个静默穿过后门的取值）。这正是本仓反复要防的形态。
     */
    public enum Nature {
        /** 正常佩戴，不是缺口。 */
        NORMAL,
        /** 行为性 —— <b>唯一可扣分</b>。 */
        BEHAVIORAL,
        /** 技术性 —— <b>一律不判行为性</b>（§2.8.7⑤ 逐字「宁可少扣、不可错扣」）。 */
        TECHNICAL
    }

    private final int code;
    private final Nature nature;

    WearState(int code, Nature nature) {
        this.code = code;
        this.nature = nature;
    }

    /** 落库整数（= {@code band_daily_coverage.is_wear} 的取值）。 */
    public int code() {
        return code;
    }

    public Nature nature() {
        return nature;
    }

    /**
     * 是否<b>技术性缺失</b> —— 即"不得判行为性"的那一类。
     *
     * <p>🛑 取数侧必须用它（而不是自己写 {@code isWear == -1 || isWear == 255}）：
     * 手写一遍就意味着新增一个技术性取值时，这里会静默漏掉。
     */
    public boolean technical() {
        return nature == Nature.TECHNICAL;
    }

    /** 是否<b>行为性</b> —— 唯一可扣分的一类。 */
    public boolean behavioral() {
        return nature == Nature.BEHAVIORAL;
    }

    /** 是否"正常佩戴"（不是缺口）。 */
    public boolean normal() {
        return nature == Nature.NORMAL;
    }

    /**
     * 本状态是否<b>与"客户没戴"这一判定相冲突</b> —— 即禁止与
     * {@code gap_reason='not_worn'} 共存。
     *
     * <p>🛑 <b>返回值恰好等于 {@link #technical()}，而本方法仍然独立存在</b>，
     * 这是一个刻意的决定，理由必须写清（否则下一个人会把它删成一行转发）：
     * <ul>
     *   <li>它把"<b>为什么</b>技术性不能与 not_worn 共存"这件事<b>命名</b>出来了。
     *       前者（{@code technical()}）是<b>事实分类</b>，后者是<b>门禁判据</b>；
     *       今天的判据恰好等价于事实分类，但那是因为 §2.8.7⑤ 的判据就是
     *       "行为性 ∧ 无技术性信号"。若哪天厂商给出"设备异常原因码"、
     *       于是 <b>{@code WORN} 也可能与某个 not_worn 判定冲突</b>，改这里一处即可；</li>
     *   <li>调用点读起来是 {@code if (wear.contradictsNotWorn())} 而不是
     *       {@code if (wear.technical())} —— 前者自解释，后者需要读者去别处
     *       拼出"技术性为什么和 not_worn 有关"。</li>
     * </ul>
     */
    public boolean contradictsNotWorn() {
        return technical();
    }

    /** 全部落库整数（供门禁与 V22 的 (5b) 数组逐项比对）。 */
    public static List<Integer> allCodes() {
        return Arrays.stream(values()).map(WearState::code).toList();
    }

    /** 全部"技术性"取值（供门禁核对 V22 的 {@code v_tech_iswear} 数组）。 */
    public static List<Integer> technicalCodes() {
        return Arrays.stream(values()).filter(WearState::technical).map(WearState::code).toList();
    }

    /**
     * 由落库整数解析；<b>未登记的整数即抛错</b>（fail-closed，与 V22 的 (5b) 同力度）。
     *
     * @param raw 允许 {@code null}（该列可空 = 该日设备未给出佩戴状态）；
     *            非 {@code null} 则必须在 {@code {-1, 0, 1, 255}} 内
     * @return {@code null} 表示"设备未给出该日的佩戴状态"——🛑 它与
     *         {@link #TECHNICAL_255} <b>不是一回事</b>：前者是"什么都没说"，
     *         后者是"明确说了『技术性缺失』"。合并二者会让"没有信号"
     *         悄悄获得"技术性缺失"这个已被定义的语义，从而把一个<b>未知</b>
     *         当成一个<b>已知的技术性</b>去参与 A3 的"未知缺失占比"门（§2.8.7⑤ 末段
     *         「设『未知缺失占比』门（建议初始 30%），超阈 → A3 整维 applicable=False」）——
     *         那个门的整个意义就是<b>把未知单列出来</b>。
     */
    public static WearState parseNullable(Integer raw) {
        if (raw == null) {
            return null;
        }
        for (WearState s : values()) {
            if (s.code == raw) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "is_wear = " + raw + " 非法 —— 只认 " + allCodes()
                        + "。口径 = §2.8.7⑤ 逐字：`1`=佩戴（正常）／`0`=脱腕（行为性）／"
                        + "`(-1,255)`=技术性缺失。🛑 未登记的整数不得静默接受："
                        + "一个越界取值若被放过，取数侧会把它当成『既非行为性也非技术性』的第三种东西 ——"
                        + "而在 A3 的口径里只有三分（正常 / 行为性 / 技术性），"
                        + "没有它的位置，于是它会以『不计入任何一类』的方式静默消失");
    }
}