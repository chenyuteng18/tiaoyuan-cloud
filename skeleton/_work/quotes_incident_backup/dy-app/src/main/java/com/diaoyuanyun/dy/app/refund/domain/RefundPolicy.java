package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * S2-1 退款域口径 —— 归一化自 config {@code #10 / #26 / #27 / #28 / #29 / #38}。
 *
 * <h2>本记录不自行定义任何数值</h2>
 * 全部数值来自 config 声明值，{@link #fromRawConfig} 是唯一构造入口，且<b>逐项 fail-closed</b>。
 * PRD §10 的总要求是「<b>所有业务数字不得硬编码</b>」；退款域是其中"写错了就改变面客行为"
 * 的一批：24h 代录窗口、48h 挽留 SLA、7 天到账承诺、让步审批阈值、直退公式。
 *
 * <h2>本类的真正价值：把五条业务铁律变成"配置改坏即报错"</h2>
 * 配置被改坏时，"取到值了但值是坏组合"比"取不到值"危险得多 ——
 * 后者会抛，前者会<b>静默按新语义执行</b>。故本类在构造期强制下列断言，
 * 每一条都对应一个具体的、已被 PRD 逐字写死的业务铁律：
 *
 * <ol>
 *   <li><b>效果类不得自动出结论</b>（{@code #10}）：{@code routes[effect].mode} 必须为
 *       {@code negotiation_workorder} 且 {@code human_in_loop=true}。
 *       依据：业务方 2026-09-16 原话「经络师再根据客户得数据看是否同意退款事宜」+
 *       全行业把退款锚在履约状态而非效果（附录 C.7）。
 *       🛑 若配置把 effect 改成 direct，"效果类不自动出结论"这条约束会<b>静默消失</b> ——
 *       配置看着被改了、但改动的后果无人知晓，直到某个客户收到一条机器算出的"不给退"。</li>
 *
 *   <li><b>履约类直退不得引入任何效果判断</b>（{@code #38}）：
 *       {@code effect_judgement_allowed} 必须为 {@code false}。
 *       🛑 这是本类最重的一条断言。公式本体只有 {@code 未消耗次数 × 单次均价（实付）}，
 *       一个纯交付状态量。若它开始吃效果判断，退款就与"客户效果好不好"绑定 ——
 *       而那正是行业刻意避开的地方（退款锚在交付，不锚在疗效）：
 *       疗效争议无法在柜台当场判定，一旦绑定，每一个退款都会变成一次疗效辩论。</li>
 *
 *   <li><b>24h 代录窗口是需求本体，不是可调参数</b>（{@code #10}）：
 *       {@code entries[A].record_within_hours} 必须恰为 {@code 24}。
 *       P0-19 的需求名就是「代录与 <b>24h</b> 回执」；客户侧回执文案的时间锚点也取 24h
 *       （P1-10 文案三条随附口径①：「客户侧与系统侧说法一致，避免『两套时间』」）。
 *       把配置改成 12 或 48，会让<b>文案承诺</b>与<b>系统判定</b>当场分叉 ——
 *       客户被承诺 24h，系统按 48h 判合规，且没有任何一处报错。</li>
 *
 *   <li><b>让步阈值按「服务成本」而非「售价」</b>（{@code #29}）：
 *       {@code basis} 必须为 {@code consumed_service_cost}。
 *       🛑 按售价分段会让"卖了高价套餐的门店"在同等服务消耗下被更早触发上收，
 *       把一个内部风控参数变成对销售定价的隐性惩罚。</li>
 *   <li><b>发起方是门店、不是客户</b>（{@code #38 initiator}）：必须为
 *       {@code store_staff_deputy}。P0-19 的「代录唯一性」判定口径逐字写着：
 *       「客户<b>表达诉求</b>不算动作发起方，<b>代录</b>才是」。</li>
 * </ol>
 *
 * <h2>🛑 本域不可协商的三处"看起来可以简化"</h2>
 * <ul>
 *   <li>不把 {@code fulfillment} / {@code effect} 两个键做成枚举常量比较之外的东西 ——
 *       键名来自 {@code #10}，{@link RefundRoute#ofConfigKey} 是唯一映射。</li>
 *   <li>不为 {@code #26} 的 {@code immediate} 提供"其它值也能跑"的路径：
 *       它是 {@code ENUM} 型且 {@code allowed_values = [immediate]}，
 *       出现别的值说明配置被越权改动。</li>
 *   <li>不把 48 / 7 设为默认 —— 两者都标注为「建议值，需真实数据校准」，
 *       即它们<b>还没有</b>一个可信的默认。</li>
 * </ul>
 *
 * @param routes                通路 → 执行模式（config {@code #10} routes 段）
 * @param deputyEntry           入口 A：门店代客录入（config {@code #10} entries[id=A]）
 * @param firstCycleEntry       入口 B：首周期触发直退（config {@code #10} entries[id=B]）
 * @param verdictLatency        退款资格结论时效（config {@code #26}）
 * @param retentionSlaHours     挽留响应 SLA 小时数（config {@code #27}）
 * @param arrivalCommitmentDays 退款到账承诺工作日数（config {@code #28}）
 * @param concession            让步审批阈值（config {@code #29}）
 * @param fulfillment           履约类直退算法（config {@code #38}）
 * @param source                口径来源自描述（进日志 / 证据）
 */
public record RefundPolicy(
        Map<RefundRoute, RouteExecution> routes,
        DeputyEntry deputyEntry,
        FirstCycleEntry firstCycleEntry,
        VerdictLatency verdictLatency,
        int retentionSlaHours,
        int arrivalCommitmentDays,
        ConcessionThreshold concession,
        FulfillmentFormula fulfillment,
        String source) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * P0-19 的 24h 代录窗口 —— <b>需求本体，不是可调参数</b>。
     *
     * <p>🛑 它出现在两个地方，必须同值：{@code #10 entries[A].record_within_hours}
     * 与客户回执文案的时间锚点。既然文案的锚点已由 P1-10 锁死为 24h，
     * 系统侧的判定窗口就不能是别的值 —— 否则"两套时间"会以配置的形式复活。
     *
     * <p>故本常量<b>不是</b>"默认值"，而是<b>需求的唯一合法值</b>：
     * 配置里出现别的数，{@link #fromRawConfig} 直接抛，理由是"与需求冲突"，
     * 而不是"不支持该值"。两者的差别是可排查性 —— 后者会被读成"系统能力不足"。
     */
    public static final int DEPUTY_RECORD_WINDOW_HOURS_REQUIRED = 24;

    /** 通路执行模式（config {@code #10} routes 段）。 */
    public enum RouteExecution {

        /** 规则直退（可自动、无需人审）。 */
        DIRECT("direct", false),

        /** 协商工单（人在环，不可自动直出资格结论）。 */
        NEGOTIATION_WORKORDER("negotiation_workorder", true);

        private final String code;
        private final boolean humanInLoopBound;

        RouteExecution(String code, boolean humanInLoopBound) {
            this.code = code;
            this.humanInLoopBound = humanInLoopBound;
        }

        public String code() {
            return code;
        }

        /** 本模式在语义上是否必然绑定"人在环"。 */
        public boolean isHumanInLoopBound() {
            return humanInLoopBound;
        }

        public static RouteExecution parse(String code, String label) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 routes[" + label + "].mode 缺失 —— 通路模式未知即不得接单");
            }
            String c = code.trim();
            return Arrays.stream(values())
                    .filter(m -> m.code.equals(c))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "config #10 routes[" + label + "].mode 不在允许值内: '" + code
                                    + "'（合法值: " + Arrays.stream(values()).map(RouteExecution::code).toList() + "）"));
        }
    }

    /** 退款资格结论时效（config {@code #26}，{@code ENUM allowed_values = [immediate]}）。 */
    public enum VerdictLatency {

        /** 即时（内部计算不排队）。🛑 「即时」不是"结论即时推送给客户"。 */
        IMMEDIATE("immediate");

        private final String code;

        VerdictLatency(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static VerdictLatency parse(String code) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #26（退款资格结论时效）缺失 —— 时效未定即不得出结论");
            }
            String c = code.trim();
            return Arrays.stream(values())
                    .filter(v -> v.code.equals(c))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "config #26 不在允许值内: '" + code + "'（allowed_values = [immediate]）—— "
                                    + "出现别的值说明配置被越权改动：结论时效直接改变『资格判定是否即时直出』"));
        }
    }

    /**
     * 入口 A：门店代客录入（config {@code #10} {@code entries[id=A]}）。
     *
     * @param actor             发起方（必须为 {@code store_deputy_entry}）
     * @param recordWithinHours 代录窗口小时数（必须为 24）
     */
    public record DeputyEntry(String actor, int recordWithinHours) {

        /** P0-19「发起方 = 门店（经络师 / 门店负责人）代客发起」。 */
        public static final String ACTOR_REQUIRED = "store_deputy_entry";

        public DeputyEntry {
            if (!ACTOR_REQUIRED.equals(actor)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 entries[A].actor 必须为 " + ACTOR_REQUIRED + "：实际=" + actor
                                + " —— P0-19 的『代录唯一性』判定口径逐字写着："
                                + "客户表达诉求不算动作发起方，代录才是");
            }
            if (recordWithinHours != DEPUTY_RECORD_WINDOW_HOURS_REQUIRED) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 entries[A].record_within_hours 必须为 "
                                + DEPUTY_RECORD_WINDOW_HOURS_REQUIRED + "：实际=" + recordWithinHours
                                + "。🛑 24h 是 P0-19 的需求本体，且客户回执文案的时间锚点同为 24h"
                                + "（P1-10 文案口径①：客户侧与系统侧说法一致）—— "
                                + "改成其它值会让文案承诺与系统判定当场分叉，且没有任何一处报错");
            }
        }
    }

    /**
     * 入口 B：首周期双不达标 → 不经挽留主动终止退款（config {@code #10} {@code entries[id=B]}）。
     *
     * @param trigger 触发条件（必须为 {@code first_cycle}）
     * @param mode    执行模式（必须为 {@code direct} —— 首周期主动终止 = 全额免审批）
     */
    public record FirstCycleEntry(String trigger, String mode) {

        public static final String TRIGGER_REQUIRED = "first_cycle";

        public FirstCycleEntry {
            if (!TRIGGER_REQUIRED.equals(trigger)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 entries[B].trigger 必须为 " + TRIGGER_REQUIRED + "：实际=" + trigger
                                + " —— 入口 B 的定义就是『首周期双不达标』");
            }
            if (!RouteExecution.DIRECT.code().equals(mode)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 entries[B].mode 必须为 " + RouteExecution.DIRECT.code()
                                + "：实际=" + mode
                                + " —— 入口 B 由系统判定触发、不经挽留，且 PRD P0-14 第三层明确"
                                + "『首周期主动终止 = 全额免审批』；改成协商工单会让一个已判定的结论重回人工");
            }
        }
    }

    /**
     * 让步审批阈值（config {@code #29}）。
     *
     * <h2>🛑 {@code basis} 是"服务成本"而不是"售价" —— 这条必须断言</h2>
     * {@code #29} 行注释逐字：「按已消耗次数对应的服务成本分段（<b>非按售价</b>）」。
     * 按售价分段的后果不是"略有偏差"：同等服务消耗下，卖了高价套餐的门店会更早触发上收，
     * 于是一个内部风控参数变成了对销售定价的隐性惩罚 —— 而门店会把这读成"不许卖贵的"。
     *
     * @param basis           分段基准（必须为 {@code consumed_service_cost}）
     * @param withinThreshold 阈内行为（必须为 {@code store_self_decide}）
     * @param overThreshold   超阈行为（必须为 {@code escalate_headquarters}）
     */
    public record ConcessionThreshold(String basis, String withinThreshold, String overThreshold) {

        public static final String BASIS_REQUIRED = "consumed_service_cost";

        public ConcessionThreshold {
            if (!BASIS_REQUIRED.equals(basis)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #29 basis 必须为 " + BASIS_REQUIRED + "：实际=" + basis
                                + " —— #29 行注释逐字要求『按已消耗次数对应的服务成本分段（非按售价）』。"
                                + "按售价分段会让同等服务消耗下高价套餐门店更早触发上收，"
                                + "把内部风控参数变成对销售定价的隐性惩罚");
            }
            if (!"store_self_decide".equals(withinThreshold)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #29 within_threshold 必须为 store_self_decide：实际=" + withinThreshold
                                + " —— P0-14『阈内门店自决挽留』");
            }
            if (!"escalate_headquarters".equals(overThreshold)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #29 over_threshold 必须为 escalate_headquarters：实际=" + overThreshold
                                + " —— P0-14『超阈 / 客户拒挽留自动上收总部』（出口集中）");
            }
        }
    }

    /**
     * 履约类直退算法（config {@code #38}）。
     *
     * <h2>🛑 {@code effectJudgementAllowed} 必须为 {@code false} —— 本类最重要的一条断言</h2>
     * {@code #38} 行注释逐字：「该算法只吃『未消耗次数 × 单次均价（实付）』，
     * <b>不得引入任何效果判断</b>（行业把退款钉在交付状态、而非效果）」。
     *
     * <p>为什么这条值得一条断言而不是一句注释：把效果判断引入直退，最可能的路径
     * <b>不是</b>有人故意改，而是某个开发觉得"顺手也看一下效果，避免客户钻空子"。
     * 那一刻起，退款与"疗效好不好"绑定，而疗效争议在柜台当场无法判定 ——
     * 每一个履约类退款都会变成一次疗效辩论，且辩论双方都拿不出当场可验证的证据。
     *
     * <p>公式本体也做精确比对（{@code remaining_course_count * unit_price_paid}）：
     * 允许"等价变形"会打开一个口子 —— 任何"等价"的判断都由改配置的人单方做出。
     *
     * @param formula                 公式字面（必须恰为 {@code remaining_course_count * unit_price_paid}）
     * @param fullUnusedRefundable    足额未核销可整单退（必须为 {@code true}）
     * @param effectJudgementAllowed  是否允许引入效果判断（必须为 {@code false}）
     * @param initiator               发起方（必须为 {@code store_staff_deputy}）
     */
    public record FulfillmentFormula(
            String formula,
            boolean fullUnusedRefundable,
            boolean effectJudgementAllowed,
            String initiator) {

        /** 唯一合法公式字面（config {@code #38}）。 */
        public static final String FORMULA_REQUIRED = "remaining_course_count * unit_price_paid";

        /** P0-19：发起方 = 门店（经络师 / 门店负责人）代客发起。 */
        public static final String INITIATOR_REQUIRED = "store_staff_deputy";

        public FulfillmentFormula {
            if (!FORMULA_REQUIRED.equals(formula)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #38 formula 必须恰为 '" + FORMULA_REQUIRED + "'：实际=" + formula
                                + " —— 不做『等价变形』判定：任何等价与否的结论都由改配置的人单方做出，"
                                + "而该公式决定一笔退款金额");
            }
            if (!fullUnusedRefundable) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #38 full_unused_refundable 必须为 true：实际=" + fullUnusedRefundable
                                + " —— #38 逐字『足额未核销可整单退』");
            }
            if (effectJudgementAllowed) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "🛑 config #38 effect_judgement_allowed 必须为 false —— "
                                + "#38 行注释逐字要求『该算法只吃未消耗次数 × 单次均价（实付），"
                                + "不得引入任何效果判断（行业把退款钉在交付状态、而非效果）』。"
                                + "一旦允许效果判断，退款就与『客户效果好不好』绑定，"
                                + "而疗效争议在柜台当场无法判定 —— 每一次履约类退款都会变成一次疗效辩论");
            }
            if (!INITIATOR_REQUIRED.equals(initiator)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #38 initiator 必须为 " + INITIATOR_REQUIRED + "：实际=" + initiator
                                + " —— P0-19『代录唯一性』：退款工单只能由经络师 / 门店负责人代录创建，"
                                + "客户在小程序不提交、不创建、不发起任何退款动作");
            }
        }
    }

    // ==================================================================
    // 唯一构造入口
    // ==================================================================

    /**
     * 唯一构造入口：从七段原始声明值归一化。
     *
     * <p><b>逐项 fail-closed</b>：任一段缺失即抛并点名缺失项，绝不设默认值。
     */
    public static RefundPolicy fromRawConfig(RefundRawConfig raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款域口径来源为空 —— 拒绝按默认口径处理退款（24h 窗口 / 通路模式 / 直退公式 / 可见性任一缺失都不得兜底）");
        }
        if (!raw.isComplete()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "退款域口径声明缺失，缺失项: " + raw.missingKeys()
                            + " —— 拒绝按默认口径处理（本域七段中五段直接决定『客户能不能退、退多少、谁看得见』）");
        }

        Map<RefundRoute, RouteExecution> routes = parseRoutes(
                readJson(raw.gateRulesJson(), "cfg:refund.gate_rules / config #10"),
                raw.gateRulesJson());

        JsonNode gateRules = readJson(raw.gateRulesJson(), "cfg:refund.gate_rules / config #10");
        DeputyEntry deputyEntry = parseDeputyEntry(gateRules);
        FirstCycleEntry firstCycleEntry = parseFirstCycleEntry(gateRules);

        VerdictLatency latency = VerdictLatency.parse(raw.verdictLatency());
        int retentionSla = parsePositiveInt(raw.retentionSlaHours(), "cfg:refund.retention_sla_hours / config #27");
        int arrivalDays = parsePositiveInt(raw.arrivalCommitmentDays(),
                "cfg:refund.arrival_commitment_days / config #28");

        ConcessionThreshold concession = parseConcession(
                readJson(raw.concessionThresholdJson(), "cfg:refund.concession_approval_threshold / config #29"));
        FulfillmentFormula fulfillment = parseFulfillment(
                readJson(raw.fulfillmentFormulaJson(), "cfg:refund.fulfillment_direct_formula / config #38"));

        // 通路模式与需求语义的同源断言（配置改坏即报错，而不是静默按新语义执行）
        assertRouteMatchesRequirement(routes, RefundRoute.FULFILLMENT, RouteExecution.DIRECT,
                "履约类（未交付 / 错交付 / 停交付）走规则直退 —— PRD P0-14 通路二分");
        assertRouteMatchesRequirement(routes, RefundRoute.EFFECT, RouteExecution.NEGOTIATION_WORKORDER,
                "效果类（改善不明显 / 与购买前期待不符）走协商工单、人在环、不可自动直出资格结论 —— "
                        + "依据：业务方 2026-09-16 原话「经络师再根据客户得数据看是否同意退款事宜」"
                        + "+ 全行业把退款锚在履约状态而非效果（附录 C.7）");

        return new RefundPolicy(routes, deputyEntry, firstCycleEntry, latency,
                retentionSla, arrivalDays, concession, fulfillment,
                "config#10+#26+#27+#28+#29+#38");
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * 解析 {@code #10} 的 {@code routes} 段 → 通路模式映射。
     *
     * <p>🛑 两键<b>恰好</b>都在、且不多不少：多出一个键意味着有人在配置里
     * 增加了一条通路，而代码里没有对应的 {@link RefundRoute} 成员 ——
     * 那条通路会走到"未知通路"分支，最可能的实现是默认放行或静默忽略。
     */
    private static Map<RefundRoute, RouteExecution> parseRoutes(JsonNode root, String rawJson) {
        JsonNode routesNode = root.path("routes");
        if (!routesNode.isArray() || routesNode.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #10 的 routes 段缺失或为空 —— 通路模式未知即不得接单（原始声明: " + brief(rawJson) + "）");
        }
        Map<RefundRoute, RouteExecution> out = new LinkedHashMap<>();
        for (JsonNode item : routesNode) {
            RefundRoute route = RefundRoute.ofConfigKey(text(item, "route", "#10 routes[].route"));
            String modeCode = text(item, "mode", "#10 routes[].mode");
            RouteExecution mode = RouteExecution.parse(modeCode, route.configKey());
            RouteExecution previous = out.put(route, mode);
            if (previous != null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 routes 段含重复通路键 '" + route.configKey()
                                + "' —— 两个模式声明谁生效无从判定");
            }
            // mode 与 human_in_loop 的一致性：协商工单必须显式声明人在环，
            // 直退不得声明人在环（"直退"与"人在环"同时为真会让实现者二选一，且两种选法都能自圆其说）
            if (mode.isHumanInLoopBound()) {
                JsonNode hil = item.path("human_in_loop");
                if (!hil.isBoolean() || !hil.asBoolean()) {
                    throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                            "config #10 routes[" + route.configKey() + "].mode=" + mode.code()
                                    + " 但 human_in_loop 未显式置 true —— "
                                    + "『人在环』不得靠模式名推断，必须显式声明");
                }
            } else if (item.has("human_in_loop") && item.path("human_in_loop").asBoolean(false)) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #10 routes[" + route.configKey() + "].mode=" + mode.code()
                                + " 却声明 human_in_loop=true —— 『规则直退』与『人在环』不可同时成立");
            }
        }
        Set<RefundRoute> expected = new TreeSet<>(Set.of(RefundRoute.values()));
        if (!out.keySet().equals(expected)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #10 routes 段的通路键必须恰为 {fulfillment, effect}：实际="
                            + out.keySet().stream().map(RefundRoute::configKey).toList()
                            + " —— 多出或缺少的通路在代码里没有对应成员，会走到未知分支");
        }
        return Map.copyOf(out);
    }

    private static DeputyEntry parseDeputyEntry(JsonNode gateRules) {
        JsonNode entryA = findByEntryId(gateRules, "A");
        return new DeputyEntry(
                text(entryA, "actor", "#10 entries[A].actor"),
                intOf(entryA, "record_within_hours", "#10 entries[A].record_within_hours"));
    }

    private static FirstCycleEntry parseFirstCycleEntry(JsonNode gateRules) {
        JsonNode entryB = findByEntryId(gateRules, "B");
        return new FirstCycleEntry(
                text(entryB, "trigger", "#10 entries[B].trigger"),
                text(entryB, "mode", "#10 entries[B].mode"));
    }

    /**
     * 在 {@code entries} 数组中按 {@code id} 找一项。
     *
     * <p>🛑 刻意<b>不</b>按下标取：以 {@code entries[0]} 取入口 A 的写法，
     * 在有人往数组前面插一项时会把入口 B 当入口 A 读，且不报错 ——
     * 结果是把"首周期直退"的规则套到"门店代录"上。
     */
    private static JsonNode findByEntryId(JsonNode gateRules, String id) {
        JsonNode entries = gateRules.path("entries");
        if (!entries.isArray()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #10 的 entries 段缺失或非数组 —— 双入口（A 门店代录 / B 首周期）无从判定");
        }
        for (JsonNode item : entries) {
            if (id.equals(item.path("id").asText(null))) {
                return item;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "config #10 的 entries 段找不到 id='" + id + "' 的入口声明 —— "
                        + "双入口是需求本体（A 门店代客录入 / B 首周期触发直退），缺一即不得接单");
    }

    private static ConcessionThreshold parseConcession(JsonNode root) {
        return new ConcessionThreshold(
                text(root, "basis", "#29 basis"),
                text(root, "within_threshold", "#29 within_threshold"),
                text(root, "over_threshold", "#29 over_threshold"));
    }

    private static FulfillmentFormula parseFulfillment(JsonNode root) {
        return new FulfillmentFormula(
                text(root, "formula", "#38 formula"),
                booleanOf(root, "full_unused_refundable", "#38 full_unused_refundable"),
                booleanOf(root, "effect_judgement_allowed", "#38 effect_judgement_allowed"),
                text(root, "initiator", "#38 initiator"));
    }

    private static void assertRouteMatchesRequirement(Map<RefundRoute, RouteExecution> routes,
                                                     RefundRoute route,
                                                     RouteExecution expected,
                                                     String reason) {
        RouteExecution actual = routes.get(route);
        if (actual != expected) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #10 的通路模式与需求语义冲突：" + route.code() + " 应为 " + expected.code()
                            + "，实际 " + (actual == null ? "缺失" : actual.code())
                            + " —— " + reason);
        }
    }

    private static JsonNode readJson(String json, String label) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 不是合法 JSON: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非文本: " + label);
        }
        return v.asText();
    }

    private static int intOf(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isInt() && !v.isLong()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非整数: " + label);
        }
        return v.asInt();
    }

    private static boolean booleanOf(JsonNode node, String field, String label) {
        JsonNode v = node.path(field);
        if (!v.isBoolean()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "口径缺字段或类型非布尔: " + label);
        }
        return v.asBoolean();
    }

    private static int parsePositiveInt(String s, String label) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v <= 0) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        label + " 必须为正整数：实际=" + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    label + " 不是合法整数: '" + s + "'");
        }
    }

    /** 报错时附上原始声明的一小段（不含完整 JSON —— 口径不入日志，见 {@link RefundRawConfig#toString}）。 */
    private static String brief(String rawJson) {
        return rawJson == null ? "null" : rawJson.length() + " chars";
    }

    // ==================================================================
    // 便捷视图
    // ==================================================================

    public RefundPolicy {
        routes = Map.copyOf(routes);
    }

    /** 某通路的执行模式（缺失即抛：通路模式未知不得接单）。 */
    public RouteExecution executionOf(RefundRoute route) {
        RouteExecution m = routes.get(route);
        if (m == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "通路 " + route.code() + " 无执行模式声明 —— config #10 routes 段缺该键");
        }
        return m;
    }

    /** 该通路是否必须人在环（不得自动直出资格结论）。 */
    public boolean requiresHumanInLoop(RefundRoute route) {
        return executionOf(route).isHumanInLoopBound();
    }

    /**
     * 是否允许对某通路"自动直出资格结论"。
     *
     * <p>这是 {@code RefundPolicy} 供上层调用的唯一语义入口 —— 把
     * "效果类不可自动出结论"从一个散落在 if-else 里的判断，收成一个<b>带名字的谓词</b>。
     * 收成一个谓词的理由是可断言：测试可以直接断言
     * {@code policy.allowsAutoVerdict(EFFECT) == false}，而不必构造一次真实的退款请求。
     */
    public boolean allowsAutoVerdict(RefundRoute route) {
        return executionOf(route) == RouteExecution.DIRECT;
    }

    /** 全部合法依据清单（供错误消息使用）。 */
    public static List<String> allAmountBasisCodes() {
        return AmountBasis.allCodes();
    }
}