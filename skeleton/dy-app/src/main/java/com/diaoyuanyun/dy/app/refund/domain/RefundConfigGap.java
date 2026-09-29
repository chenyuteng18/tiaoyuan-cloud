package com.diaoyuanyun.dy.app.refund.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 退款域<b>已知的配置缺口登记册</b> —— 代码需要的、但 config 里<b>还没有</b>的数值。
 *
 * <h2>为什么要有这个枚举，而不是"先写个默认值回头再说"</h2>
 * PRD §10 的硬性要求是「<b>所有业务数字不得硬编码</b>」。本条纪律最容易在一种情形下被破：
 * 代码里需要一个数，配置里没有，于是<b>顺手写个常量</b> —— 它看起来与"临时"无异，
 * 但它已经是一个进入判定链的数字，且没有任何一处记录过"这个数从哪来"。
 *
 * <p>本枚举把这种情形变成一次<b>显式声明</b>：缺口是"已识别的、有出处的、待补的"，
 * 而不是"没人注意到的硬编码"。三者的区别在事后排查时是决定性的 ——
 * 「这个 48 是 config #27 还是 P0-19 的第二个升级阈值？」如果没人写下来，
 * 下一个人只能靠猜，而猜错的后果是升级链跳错人。
 *
 * <h2>🛑 本枚举不代表"可以先用建议值跑起来"</h2>
 * 每个缺口的 {@link #advisoryValue()} 只是把 PRD 原文写的建议值抄下来<b>供业务方校准参考</b>，
 * <b>不得</b>被任何判定路径当作生效值读取。读取缺口的唯一合法方式是
 * {@link #requireValue()} —— 它<b>总是抛</b>，并在消息里点名缺口与出处。
 * 这条设计使"误用建议值"从一次静默的错误取值，变成一次立即的、带出处的失败。
 *
 * <h2>登记口径</h2>
 * 只有在同时满足三条时才登记为本枚举成员：
 * <ol>
 *   <li>PRD 明文要求该数值参与判定（不是"将来可能有用"）；</li>
 *   <li>PRD §10 可配置项清单里<b>取不到可用值</b> —— 两种形态都算：
 *       <b>(a)</b> 整条没有编号（{@link #configNo()} 为空）；
 *       <b>(b)</b> 条目<b>有</b>编号、但清单只声明了"按什么分段"而未给
 *       分段边界（{@link #configNo()} 非空，值仍空缺）；</li>
 *   <li>能把出处钉到具体条款（{@link #prdClause()}）。</li>
 * </ol>
 * ⚠️ 第 2 条的 (b) 形态是<b>后补进来的</b>，理由是它在现实中最隐蔽：
 * 「{@code #29} 已登记」这件事会让人以为缺口不存在，而 {@code #29} 的原文
 * 只写了「按已消耗次数对应的服务成本<b>分段</b>」—— <b>没有写段在哪、每段多宽</b>。
 * 于是"条目存在"与"值可用"被混为一谈，代码作者会顺手写一组分段常量，
 * 而没人能说出那组常量从哪来。故 (b) 形态必须可被登记，并显式携带
 * {@link #configNo()} 以指名"缺的是这条已编号项里的值"。
 *
 * <p>🛑 (b) 形态<b>不</b>等于"可以直接读 {@code #29} 当分段值"：
 * {@code #29} 只声明行为（阈内自决 / 超阈上收），不声明边界。
 * 把它读来当边界，等于把一句"要分段"当成"段已经分好了"。
 */
public enum RefundConfigGap {

    /**
     * P0-19 C1-3「转交段独立计时」的受理窗口 {@code X} 小时。
     *
     * <h2>它堵的是什么</h2>
     * 调理师转交代办创建后，经络师若迟迟不受理，诉求会在调理师处"自然停留"——
     * 门店可以等到第二天再代录，<b>24h 计时被重置，而形式上完全合规</b>。
     * 故 C1-3 要求对"转交 → 受理"这一段<b>独立计时</b>。
     *
     * <p>🛑 但它<b>计时所需的那个 X 还没有值</b>。PRD 逐字写的是
     * 「经络师未在 X 小时内受理（<b>建议值 4h，需真实数据校准</b>）——
     * 即：这个数还没有一个可信的取值，只有一条待校准的建议。
     * 若代码在此处回落成 4，就等于把一个<b>未经业务确认的</b>数值
     * 当成对门店的考核阈值 —— 而 4h 与 8h 对门店意味着完全不同的执行压力。
     */
    HANDOFF_ACCEPT_WINDOW_HOURS(
            "cfg:refund.handoff_accept_window_hours",
            null,
            "P0-19 C1-3（转交段独立计时）",
            4,
            "PRD §10 可配置项清单未给该值编号；PRD 原文标注『建议值 4h，需真实数据校准』"
                    + "—— 即在真实数据到位前它没有可信取值。"
                    + "阻塞：转交待办『超时未受理』推送与升级无法判定，"
                    + "门店可让诉求在调理师处自然停留以重置 24h 计时（形式上合规）"),

    /**
     * P0-19「录入延迟」升级链<b>第二跳</b>的总部介入阈值。
     *
     * <h2>为什么它不能复用 config {@code #27}（值恰好也是 48）</h2>
     * {@code #27 = cfg:refund.retention_sla_hours}，PRD 行注释逐字是
     * 「自动派单后 48h 内首响应（<b>仅对门店挽留动作计时</b>）」——
     * 它计的是<b>挽留动作</b>的钟。
     *
     * <p>P0-19 第二跳计的是「<b>录入延迟</b>」的钟（{@code recording_delay_h}）。
     * 两者数值相同是<b>巧合</b>，不是同源。若代码把 {@code #27} 读来当第二跳阈值：
     * 一旦业务方按"挽留体验"校准 {@code #27}（改 48 → 72），
     * <b>录入延迟的升级链会一起被改掉</b>，而改的人完全不知道 ——
     * 这就是"同一个 48 有两个含义"的典型事故：数值对了，语义错了，且改一处动两处。
     */
    RECORDING_ESCALATION_HQ_THRESHOLD_HOURS(
            "cfg:refund.recording_escalation_hq_hours",
            null,
            "P0-19（录入延迟超时自动升级 · 第二跳）",
            48,
            "PRD §10 可配置项清单未给该值编号；48 与 config #27 数值相同但语义不同"
                    + "（#27 仅对门店挽留动作计时，本阈值对录入延迟计时），"
                    + "数值相同属巧合 —— 复用 #27 会让『按挽留体验校准 #27』"
                    + "无声地改掉录入升级链。"
                    + "阻塞：>48h 未录入的『总部运营介入』跳无法判定"),

    /**
     * 让步金额的<b>分段边界</b>（config {@code #29} 里的值，但 {@code #29} 没有给）。
     *
     * <h2>它与上面两个不同：这一条<b>有编号</b>，缺的是编号里的值</h2>
     * {@code #29} 原文逐字：
     * <pre>
     *   {"basis":"consumed_service_cost",
     *    "within_threshold":"store_self_decide",
     *    "over_threshold":"escalate_headquarters"}
     * </pre>
     * 它声明的是<b>行为</b>（阈内自决 / 超阈上收），以及分段的<b>基准</b>
     * （{@code consumed_service_cost} = 已消耗次数对应的服务成本）。
     * <b>但没有任何一个字段说"段有多大"</b> —— 既无绝对金额、也无消耗次数档位、
     * 更无占比。而 P0-14 第二层逐字要求「让步金额 = 按已消耗次数的服务成本
     * <b>分段阈值</b>上收」，"阈内 / 超阈"这件事<b>必须</b>有一个边界才能判定。
     *
     * <h2>🛑 为什么不能"先拿个想当然的数跑起来"</h2>
     * 这个边界决定的是<b>门店能不能自己拍板减免</b>。设小了，所有让步都被上收总部，
     * 门店在客户面前开不了口、只能"先上报等批复"—— 这恰好是 Top1 痛点
     * （判定权在人手里、当场给不出结论）的复发通道；设大了，减免在门店层面失控，
     * 总部的"出口集中"退化成一句口号。两个方向都不是"略有偏差"，且
     * <b>方向相反、无法用一个折中值兼顾</b>。故本缺口必须由业务方给出，
     * 而不是由代码作者选一个"看起来合理"的数。
     */
    CONCESSION_SEGMENT_BOUNDARIES(
            "cfg:refund.concession_approval_threshold#segments",
            29,
            "P0-14 第二层 + config #29（让步金额分段阈值上收）",
            null,
            "config #29 已编号、已声明 basis/within_threshold/over_threshold 三项行为，"
                    + "但【无任何字段给出分段边界】（既无金额、也无消耗次数档位、也无占比）"
                    + "—— 即『条目在、值不在』。"
                    + "阻塞：P0-14 第二层『阈内门店自决 / 超阈上收总部』无法判定，"
                    + "门店侧只能一律上报（Top1 痛点复发）或一律自决（出口集中失效），"
                    + "两者方向相反、不存在可兼顾的折中值");

    /** 建议新增的 config 键名（形态 (a)），或"已编号项里缺的那个值的定位符"（形态 (b)）。 */
    private final String configKey;

    /**
     * 已存在的 config 编号（形态 (b)）；形态 (a) 为 {@code null}。
     *
     * <p>用 {@code Integer} 而非 {@code int}：{@code 0} 会与"编号就是 0"无从区分，
     * 而编号是"这条缺口挂在哪个已冻结条目下"的唯一线索，不能容忍一个假值。
     */
    private final Integer configNo;

    private final String prdClause;

    /**
     * 建议值（PRD 原文抄录，<b>仅供业务方校准参考，不得被判定路径读取</b>）；
     * PRD <b>未给</b>建议值时为空 —— 不要为了填满字段而编一个数。
     */
    private final Integer advisoryValue;

    private final String why;

    RefundConfigGap(String configKey, Integer configNo, String prdClause, Integer advisoryValue, String why) {
        this.configKey = configKey;
        this.configNo = configNo;
        this.prdClause = prdClause;
        this.advisoryValue = advisoryValue;
        this.why = why;
    }

    /** 建议新增的 config 键名（<b>尚未存在于 02_slots_seed.sql</b>），或已编号项内的值定位符。 */
    public String configKey() {
        return configKey;
    }

    /**
     * 该缺口挂在的 config 编号（形态 (b)）；形态 (a) 返回空。
     *
     * <p>它决定"补齐动作落在哪"：有编号 → 往 {@code 02_slots_seed.sql} 的<b>那一条</b>里
     * 补字段；无编号 → 先申请编号（§10 清单）再落盘。两者的流程不同，报错时应能区分。
     */
    public java.util.Optional<Integer> configNo() {
        return java.util.Optional.ofNullable(configNo);
    }

    /** 是否属"已编号、但值空缺"的形态（{@code #29} 分段边界）。 */
    public boolean isNumberedEntryWithMissingValue() {
        return configNo != null;
    }

    /** PRD 出处（条款号），用于报错点名与人工复核。 */
    public String prdClause() {
        return prdClause;
    }

    /**
     * PRD 原文里写的建议值 —— 参考用，<b>不是</b>生效值；PRD <b>未给</b>建议值时为空。
     *
     * <p>🛑 空值是有意义的：它表示"连一条待校准的建议都没有"。把它填成 0 或某个数
     * 会让登记册读起来像"已有候选值"，而实际上业务方还需要从零给出。
     */
    public java.util.Optional<Integer> advisoryValue() {
        return java.util.Optional.ofNullable(advisoryValue);
    }

    /** 为何未配置 / 阻塞了什么（进错误消息与登记文档）。 */
    public String why() {
        return why;
    }

    /**
     * 读取缺口值 —— <b>总是抛</b>。
     *
     * <p>它存在的唯一目的是让"想用这个数"这件事<b>无法静默发生</b>：
     * 任何试图取值的代码路径都会立即失败，并在消息里拿到缺口名、PRD 出处、
     * 建议值、以及"为什么不建议直接用建议值"。
     *
     * <p>🛑 不要把它改成返回 {@link #advisoryValue()}：那会让本枚举从
     * "缺口登记册"退化成"伪装成配置的硬编码表"，而这正是它要防的事。
     */
    public int requireValue() {
        throw new com.diaoyuanyun.dy.common.exception.BizException(
                com.diaoyuanyun.dy.common.result.ErrorCode.BUSINESS_RULE_VIOLATED,
                "退款域配置缺口：" + configKey + " 尚未配置（出处 " + prdClause + "）。"
                        + configNo().map(n -> "该项 config #" + n + " 已存在，但缺的正是本条所需的取值。")
                                .orElse("该项在 §10 可配置项清单里没有编号。")
                        + "PRD 建议值 = " + advisoryValue().map(String::valueOf)
                                .orElse("（PRD 未给建议值，需业务方从零给出）")
                        + "（仅供业务方校准参考，不得作为生效值使用）。"
                        + "原因：" + why + "。"
                        + "🛑 不得回落默认值：该值直接参与对门店的考核判定，"
                        + "一个未经业务确认的数字进入判定链后，"
                        + "在事后无法与『业务方定的值』区分。"
                        + "处置：由业务方校准后在 config 声明该键（并同步 §10 清单编号），"
                        + "或明确本条暂不实现（登记进待裁定册）");
    }

    /** 全部缺口（供登记文档与门禁断言使用）。 */
    public static List<RefundConfigGap> allGaps() {
        return Arrays.asList(values());
    }

    /** 形态 (a) 的缺口（无编号，须先申请 §10 编号）。 */
    public static List<RefundConfigGap> unnumberedGaps() {
        return allGaps().stream().filter(g -> g.configNo == null).toList();
    }

    /** 形态 (b) 的缺口（已编号、值空缺，补齐落在既有条目内）。 */
    public static List<RefundConfigGap> numberedGapsWithMissingValue() {
        return allGaps().stream().filter(RefundConfigGap::isNumberedEntryWithMissingValue).toList();
    }

    /**
     * 缺口总数自证 —— 供测试断言"缺口集合没有被无声增删"。
     *
     * <p>为什么连"数量"都断言：缺口被<b>新增</b>时若无人注意，说明又有一个数字要硬编码了；
     * 被<b>消除</b>时（补上配置）若无人注意，说明登记册与实现开始分叉。
     * 数量变化本身就该是一次需要表态的事件。
     */
    public static final int GAP_COUNT = 3;
}