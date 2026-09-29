package com.diaoyuanyun.dy.app.settlement.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨店通兑域口径的<b>已解析 + 已校验</b>形态（值对象）。
 *
 * <h2>它替代了什么（本次改动的真正意义）</h2>
 * 在此之前，{@code CrossStoreSettlement} 把 {@code 0.30} 写成
 * {@code public static final BigDecimal DEFAULT_SPLIT_THRESHOLD}，
 * 并在 {@code detectAnomaly} 里把 {@code 2} / {@code 3} 直接写在代码里；
 * 而 config {@code #21} 的 seed 语义列逐字写着「<b>归属层级=总部唯一，非校准项</b>」。
 * 两者相加 ⇒ 「总部唯一可改」这句<b>当时不成立</b>：总部在配置页上把 0.30 改成 0.40，
 * 结算仍按 0.30 拆 —— 且系统不会有任何提示。
 *
 * <h2>🛑 校验从严，不回落到"看起来合理"的值</h2>
 * 本类的 {@link #fromRawConfig} 对每一段都做<b>显式范围校验</b>，任一处不合法即抛。
 * 不合法时<b>不夹取、不四舍五入、不取默认</b>：
 * <ul>
 *   <li>{@code splitThreshold} 必须落在 {@code [0,1]} —— 越界说明有人把"30%"误写成
 *       {@code 30} 或 {@code 3.0}；夹到 1.0 会让"每次结算都按占比拆"静默生效。</li>
 *   <li>{@code crossSharePct} 必须落在 {@code [0,100]} —— 它是百分数不是比例，
 *       写 {@code 0.3} 与写 {@code 30} 差 100 倍，而这种错不会让任何查询失败。</li>
 *   <li>{@code storeCountFreeze > storeCountMark} 的<b>单调性</b>必须成立 ——
 *       "冻结线不高于标记线"意味着两条独立信号互相矛盾（先冻后标），
 *       但两个值各自都是合法正整数 ⇒ 不校验就会静默共存。</li>
 * </ul>
 *
 * <h2>反刷阈值的归属（本次显式纠正的一处隐性同值）</h2>
 * {@code detectAnomaly} 此前用 {@code DEFAULT_SPLIT_THRESHOLD}（= {@code #21}）比较
 * 反刷占比，而 PRD P1-05 把「跨店占比 &gt;30% 反刷审查」写在<b>跨店异常阈值</b>条目下 ——
 * 该条目的配置载体是 {@code #22} 的 {@code cross_share_pct}。
 * 两者当时同值（都是 30%），故行为无差异；但把归属纠正为 {@code #22} 之后，
 * "改 #22 的 cross_share_pct 会不会影响反刷判定"这个问题才有确定答案
 * （会 → 且这正是 PRD 说的那条规则）。
 *
 * @param splitThreshold  config {@code #21}：他店次数占比 ≥ 此值即按占比拆分（比例，{@code [0,1]}）
 * @param windowDays      config {@code #22}.{@code window_days}：跨店信号观察窗（天）
 * @param storeCountMark  config {@code #22}.{@code store_count_mark}：窗口内门店数 ≥ 此值即标记
 * @param storeCountFreeze config {@code #22}.{@code store_count_freeze}：窗口内门店数 &gt; 此值即冻结
 * @param crossSharePct   config {@code #22}.{@code cross_share_pct}：他店占比 &gt; 此值（百分数）即反刷审查
 */
public record CrossStoreThresholds(BigDecimal splitThreshold,
                                   int windowDays,
                                   int storeCountMark,
                                   int storeCountFreeze,
                                   int crossSharePct) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CrossStoreThresholds {
        if (splitThreshold == null) {
            throw new IllegalArgumentException("splitThreshold 不得为 null（config #21 缺失即拒绝结算）");
        }
        if (splitThreshold.signum() < 0 || splitThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "跨店拆分阈值（config #21）必须在 [0,1] 内, 收到: " + splitThreshold
                            + " —— 若写成 30 或 3.0（把百分数当比例），夹取会让错误的拆分口径静默生效");
        }
        if (windowDays <= 0) {
            throw new IllegalArgumentException(
                    "跨店信号观察窗（config #22 window_days）必须为正, 收到: " + windowDays);
        }
        if (storeCountMark <= 0) {
            throw new IllegalArgumentException(
                    "跨店标记线（config #22 store_count_mark）必须为正, 收到: " + storeCountMark);
        }
        if (storeCountFreeze <= storeCountMark) {
            throw new IllegalArgumentException(
                    "跨店冻结线（config #22 store_count_freeze=" + storeCountFreeze
                            + "）必须严格大于标记线（store_count_mark=" + storeCountMark
                            + "）—— 否则两条独立信号互相矛盾（先冻后标），"
                            + "而两个值各自都是合法正整数 ⇒ 不校验就会静默共存");
        }
        if (crossSharePct < 0 || crossSharePct > 100) {
            throw new IllegalArgumentException(
                    "跨店反刷占比（config #22 cross_share_pct）是百分数、必须在 [0,100] 内, 收到: "
                            + crossSharePct + " —— 写 0.3 与写 30 差 100 倍，且这种错不会让任何查询失败");
        }
    }

    /**
     * 他店占比是否触发反刷审查（{@code >} 语义，逐字取自 PRD P1-05「跨店占比 &gt;30%」）。
     *
     * @param otherStoreRatio 他店次数占比（比例，非百分数）；可空（缺失不判 = 不触发）
     */
    public boolean antiFraudTriggered(BigDecimal otherStoreRatio) {
        if (otherStoreRatio == null) {
            return false;
        }
        return otherStoreRatio.compareTo(BigDecimal.valueOf(crossSharePct)
                .movePointLeft(2)) > 0;
    }

    /** 他店占比是否达到拆分阈值（{@code ≥} 语义，逐字取自 PRD US-8「≥30%」）。 */
    public boolean splitTriggered(BigDecimal otherStoreRatio) {
        return otherStoreRatio != null && otherStoreRatio.compareTo(splitThreshold) >= 0;
    }

    /**
     * 从原始声明值解析并校验（<b>唯一</b>的解析实现）。
     *
     * <p>与 {@code RefundPolicy#fromRawConfig} 同一条纪律：解析 + 校验只有一处，
     * 使"配置被改坏"必然在<b>这一处</b>被拦住，而不是散落在各来源实现里各漏一个。
     *
     * @throws BizException 任一段缺失 / 格式非法 / 范围越界（fail-closed，绝不回落默认值）
     */
    public static CrossStoreThresholds fromRawConfig(CrossStoreRawConfig raw) {
        if (raw == null || !raw.isComplete()) {
            List<String> missing = raw == null ? List.of("<全部>") : raw.missingKeys();
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "跨店通兑口径缺失，拒绝按默认口径结算。缺失段: " + missing
                            + " —— 『取不到就用默认』会让总部在配置页上改的数字长期不生效，"
                            + "且不会有任何报错");
        }

        BigDecimal split;
        try {
            split = new BigDecimal(raw.splitThreshold().trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #21 cfg:crossstore.split_threshold 不是合法十进制数: '"
                            + raw.splitThreshold() + "'");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(raw.anomalyRuleJson());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #22 cfg:crossstore.anomaly_rule 不是合法 JSON: "
                            + e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #22 cfg:crossstore.anomaly_rule 必须是 JSON 对象");
        }

        int windowDays = requiredInt(node, "window_days", "#22 crossstore.anomaly_rule");
        int mark = requiredInt(node, "store_count_mark", "#22 crossstore.anomaly_rule");
        int freeze = requiredInt(node, "store_count_freeze", "#22 crossstore.anomaly_rule");
        int crossPct = requiredInt(node, "cross_share_pct", "#22 crossstore.anomaly_rule");

        try {
            return new CrossStoreThresholds(split, windowDays, mark, freeze, crossPct);
        } catch (IllegalArgumentException e) {
            // 把值对象自身的范围校验统一转成业务拒绝码（HTTP 422），
            // 而不是让它以 500 逃逸 —— 这是配置缺陷，不是系统故障。
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "跨店通兑口径非法（来源 " + raw.anomalyRuleJson() + " / "
                            + raw.splitThreshold() + "）: " + e.getMessage(), e);
        }
    }

    private static int requiredInt(JsonNode node, String field, String where) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber() || !v.canConvertToInt()) {
            List<String> present = new ArrayList<>();
            node.fieldNames().forEachRemaining(present::add);
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    where + " 缺少或非法字段 '" + field + "'（必须为整数）—— 现有字段: " + present
                            + "。🛑 不得用默认值补：缺一个阈值就会让一条信号静默失效");
        }
        return v.asInt();
    }
}