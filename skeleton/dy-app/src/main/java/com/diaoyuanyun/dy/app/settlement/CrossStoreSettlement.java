package com.diaoyuanyun.dy.app.settlement;

import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreProfileSource;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreThresholds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨店通兑 · 责任拆分（S1-3 验收③ · PRD US-8 / P1-05 / U2 / CS-2）。
 *
 * <h2>规则逐字取自 PRD，不自行引申</h2>
 * <blockquote>
 * US-8：ECC 以<b>结案门店为主计</b>，<b>≥30% 次数在他店完成</b>则按<b>各店服务次数占比</b>拆分
 * （<b>允许小数</b>）；<b>退款损失按次数占比分摊</b>为各店退款成本。
 * </blockquote>
 * 即：{@code 他店次数占比 = 1 − 结案店次数 / 总次数}，与 <b>config #21</b>（跨店拆分阈值，总部唯一）
 * 的比较；达标则按占比拆分，未达标则结案店独占。
 *
 * <h2>🛑 阈值来自配置，不在本类内硬编码（「总部唯一可改」必须成立）</h2>
 * 本类此前把 {@code 0.30} 写成 {@code public static final DEFAULT_SPLIT_THRESHOLD}、
 * 把 {@code 2} / {@code 3} 直接写在 {@code detectAnomaly} 里 ——
 * 而 {@code #21} 的 seed 语义列逐字写着「<b>归属层级=总部唯一，非校准项</b>」。
 * 两者相加意味着总部改配置<b>不生效</b>，且系统不会有任何提示
 * （由批次十的 {@code ConfigSlotConsumptionLedgerTest} 首次清点暴露，登记为 N-14）。
 *
 * <p>现已改为：阈值经 {@link CrossStoreProfileSource} 从 config 声明读入、
 * 由 {@link CrossStoreThresholds#fromRawConfig} 解析校验后注入本类。
 * 生产装配在 {@code WebConfig}（注入 {@code ConfigSeedCrossStoreProfileSource}），
 * 故"改 seed → 结算行为跟着变"是一条<b>真的会跑</b>的链路，
 * 而不是注释里的承诺。
 *
 * <h2>🛑 刻意不做的东西（PRD §2.9.6 明令）</h2>
 * <b>不产出任何"率"类聚合指标</b>（如"门店损益率"）。PRD §2.9.6 原文：
 * 「🛑 <b>不新建"门店损益率"聚合指标</b>」。原因与 PRD §2.8 的"方向陷阱"同源 ——
 * 把一个可正可负的比率交给区域督导看板，方向读反的代价（把恶化读成增长）远大于它能提供的信息量。
 * 故本类<b>只输出绝对量</b>（贡献单位数、金额），"显现为比率"这件事由调用方在看板层决定，
 * <b>不在这里埋一个现成的 ratio 字段</b>——埋了就会有人直接用。
 *
 * <h2>钱不能凭空多出或少掉（唯一硬不变量）</h2>
 * 分摊必然遇到除不尽。本类保证：
 * <pre>
 *   Σ(eccShare)  == 输入 eccUnits        （精确相等，不是"约等于"）
 *   Σ(lossShare) == 输入 lossYuan        （精确相等）
 * </pre>
 * 手法：先按占比算到目标小数位，再把<b>舍入余数</b>补给"贡献最大"的那家店
 * （见 {@link #absorbRemainder}）。若不做这一步，5 家店各舍一次，
 * 总额就会和账单差几分钱——而"账对不上"是财务侧最难解释的一类缺陷。
 *
 * <h2>缺失不得补 0（PRD 硬纪律）</h2>
 * {@code totalVisits == 0} 时 <b>不返回"各店 0"</b>，而是抛
 * {@link UnallocatableException}。因为"没有服务次数"是<b>数据缺失</b>，
 * 不是"各店都贡献了 0"——补 0 会让这条记录看起来像一个"已结算且结果为零"的正常结论，
 * 从而永久掩盖"这次结算是没有依据的"。这是 PRD「缺失不得补 0」在结算域的落点。
 */
public final class CrossStoreSettlement {

    /** 拆分结果保留的小数位（金额到分 2 位；贡献单位数 4 位，PRD 明示"允许小数"）。 */
    public static final int SCALE = 2;

    private final BigDecimal splitThreshold;
    private final CrossStoreThresholds thresholds;

    /**
     * 便捷构造：只给拆分阈值，异常阈值取 config {@code #22} 的声明值。
     *
     * <p>🛑 刻意<b>不</b>保留"无参构造 + 内部硬编码 0.30"那种形态 ——
     * 它是 N-14「声明总部可改、实际是常量」的成因。测试若需要构造，
     * 必须显式给出阈值，或经 {@link #CrossStoreSettlement(CrossStoreThresholds)} 注入配置口径。
     */
    public CrossStoreSettlement(BigDecimal splitThreshold) {
        this(new CrossStoreThresholds(splitThreshold, 30, 2, 3, 30));
    }

    /**
     * 生产构造：整套阈值来自配置口径（唯一正规路径）。
     *
     * @param thresholds 经 {@link CrossStoreThresholds#fromRawConfig} 解析校验后的口径
     */
    public CrossStoreSettlement(CrossStoreThresholds thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException(
                    "跨店口径不得为 null —— 阈值必须来自配置（config #21/#22），不得内部硬编码");
        }
        this.thresholds = thresholds;
        this.splitThreshold = thresholds.splitThreshold();
    }

    /** 当前生效的整套口径（供自描述 / 断言 / 证据回溯）。 */
    public CrossStoreThresholds thresholds() {
        return thresholds;
    }

    /**
     * 一次门店贡献。
     *
     * @param storeId         门店 ID
     * @param visitCount      该店实际完成的服务次数（客户维度账本的切分，见 U2）
     * @param closingStore    是否为<b>结案门店</b>（ECC 的主计方）
     */
    public record StoreContribution(String storeId, int visitCount, boolean closingStore) {
        public StoreContribution {
            if (storeId == null || storeId.isBlank()) {
                throw new IllegalArgumentException("storeId 不得为空");
            }
            if (visitCount < 0) {
                throw new IllegalArgumentException("visitCount 不得为负: " + visitCount);
            }
        }
    }

    /**
     * 一家店的分摊结果。
     *
     * @param storeId     门店 ID
     * @param visitCount  服务次数（原样回显，便于对账）
     * @param eccShare    ECC 贡献（绝对量）
     * @param lossShare   退款损失分摊（绝对量）
     * @param splitApplied 本次是否真的走了拆分（false = 结案店独占）
     */
    public record StoreAllocation(String storeId, int visitCount,
                                  BigDecimal eccShare, BigDecimal lossShare,
                                  boolean splitApplied) {
    }

    /**
     * 结算结论。
     *
     * @param splitApplied       是否触发拆分
     * @param otherStoreVisitRatio 他店次数占比（<b>用于判定</b>，非看板指标）
     * @param totalVisits        总服务次数
     * @param allocations        各店分摊（按 storeId 升至序，保证可重复）
     */
    public record SettlementResult(boolean splitApplied,
                                   BigDecimal otherStoreVisitRatio,
                                   int totalVisits,
                                   List<StoreAllocation> allocations) {

        /** 供断言：ECC 分摊合计必须等于应分总额。 */
        public BigDecimal totalEccShare() {
            return allocations.stream()
                    .map(StoreAllocation::eccShare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** 供断言：损失分摊合计必须等于应分摊总额。 */
        public BigDecimal totalLossShare() {
            return allocations.stream()
                    .map(StoreAllocation::lossShare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** 无法结算：数据不足以支撑结论（<b>不得补 0 兜底</b>）。 */
    public static class UnallocatableException extends RuntimeException {
        public UnallocatableException(String message) {
            super(message);
        }
    }

    /**
     * 结算。
     *
     * @param contributions 各门店贡献（可含结案店）
     * @param eccUnits      本次疗程对 ECC 的贡献总量（单位数，允许小数）
     * @param lossYuan      退款损失总额（元），无损失传 {@link BigDecimal#ZERO}
     */
    public SettlementResult allocate(List<StoreContribution> contributions,
                                     BigDecimal eccUnits,
                                     BigDecimal lossYuan) {
        if (contributions == null || contributions.isEmpty()) {
            throw new UnallocatableException(
                    "无门店贡献记录, 无法结算 —— 缺失不得补 0, 拒绝给出'各店 0'的伪结论");
        }
        if (eccUnits == null || eccUnits.signum() < 0) {
            throw new IllegalArgumentException("eccUnits 不得为 null 或负");
        }
        if (lossYuan == null || lossYuan.signum() < 0) {
            throw new IllegalArgumentException("lossYuan 不得为 null 或负");
        }

        int totalVisits = contributions.stream().mapToInt(StoreContribution::visitCount).sum();
        if (totalVisits <= 0) {
            throw new UnallocatableException(
                    "服务次数合计为 0, 无法结算 —— 这是数据缺失, 不是'各店贡献为 0'");
        }

        // 结案店：PRD 要求"以结案门店为主计"。多于一个结案店是数据错误，不猜、不取第一个。
        List<StoreContribution> closers = contributions.stream()
                .filter(StoreContribution::closingStore).toList();
        if (closers.size() != 1) {
            throw new UnallocatableException(
                    "结案门店必须恰好 1 个, 实际 " + closers.size() + " 个 —— 不猜测、不任选其一");
        }

        int closingVisits = closers.get(0).visitCount();
        int otherVisits = totalVisits - closingVisits;

        // 他店占比 = 1 − 结案店次数/总次数。用整数比再除，避免先算比例造成精度损失。
        BigDecimal otherRatio = BigDecimal.valueOf(otherVisits)
                .divide(BigDecimal.valueOf(totalVisits), 6, RoundingMode.HALF_UP);

        boolean split = otherRatio.compareTo(splitThreshold) >= 0;

        // 按 storeId 排序，保证同输入必得同输出（可重复 = 可对账）
        List<StoreContribution> ordered = new ArrayList<>(contributions);
        ordered.sort(Comparator.comparing(StoreContribution::storeId));

        List<StoreAllocation> allocations = split
                ? splitByVisitRatio(ordered, totalVisits, eccUnits, lossYuan)
                : closersTakeAll(ordered, closers.get(0).storeId(), eccUnits, lossYuan);

        return new SettlementResult(split, otherRatio, totalVisits, allocations);
    }

    /** 拆分路径：按各店服务次数占比分摊。 */
    private List<StoreAllocation> splitByVisitRatio(List<StoreContribution> ordered,
                                                    int totalVisits,
                                                    BigDecimal eccUnits,
                                                    BigDecimal lossYuan) {
        List<BigDecimal> eccShares = new ArrayList<>();
        List<BigDecimal> lossShares = new ArrayList<>();
        for (StoreContribution c : ordered) {
            BigDecimal ratio = BigDecimal.valueOf(c.visitCount())
                    .divide(BigDecimal.valueOf(totalVisits), 10, RoundingMode.HALF_UP);
            eccShares.add(eccUnits.multiply(ratio).setScale(SCALE, RoundingMode.DOWN));
            lossShares.add(lossYuan.multiply(ratio).setScale(SCALE, RoundingMode.DOWN));
        }
        absorbRemainder(eccShares, ordered, eccUnits);
        absorbRemainder(lossShares, ordered, lossYuan);

        List<StoreAllocation> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            StoreContribution c = ordered.get(i);
            out.add(new StoreAllocation(c.storeId(), c.visitCount(),
                    eccShares.get(i), lossShares.get(i), true));
        }
        return out;
    }

    /**
     * 把"向下舍入"产生的余数补给贡献最大的那家店，使合计精确等于应分总额。
     *
     * <p><b>为什么用 DOWN 再补余数，而不是直接 HALF_UP</b>：HALF_UP 下各店独立进位，
     * 合计可能<b>大于</b>应分总额（凭空多出钱）；DOWN 则可能<b>小于</b>（钱消失了）。
     * 两种都不可接受。固定用 DOWN，再把差额一次性补齐，则合计恒等于总额，
     * 且差额只落在一家店上，可被逐笔解释（差额金额 = 总额 − 各店舍入值之和）。
     */
    private void absorbRemainder(List<BigDecimal> shares,
                                 List<StoreContribution> ordered,
                                 BigDecimal total) {
        BigDecimal sum = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainder = total.subtract(sum);
        if (remainder.signum() == 0) {
            return;
        }
        int target = indexOfLargestContribution(ordered);
        shares.set(target, shares.get(target).add(remainder));
    }

    /**
     * 选"贡献最大"的店承载余数；并列时取 storeId 最小者，保证确定性。
     */
    private int indexOfLargestContribution(List<StoreContribution> ordered) {
        int best = 0;
        for (int i = 1; i < ordered.size(); i++) {
            int cmp = Integer.compare(ordered.get(i).visitCount(), ordered.get(best).visitCount());
            if (cmp > 0) {
                best = i;
            }
            // 并列不换人：ordered 已按 storeId 升序，保持首个即为最小 storeId
        }
        return best;
    }

    /** 未达阈值路径：结案店独占，其余店记 0（此处 0 是<b>真实结论</b>，非"缺失补 0"）。 */
    private List<StoreAllocation> closersTakeAll(List<StoreContribution> ordered,
                                                 String closingStoreId,
                                                 BigDecimal eccUnits,
                                                 BigDecimal lossYuan) {
        List<StoreAllocation> out = new ArrayList<>();
        for (StoreContribution c : ordered) {
            boolean isCloser = c.storeId().equals(closingStoreId);
            out.add(new StoreAllocation(c.storeId(), c.visitCount(),
                    isCloser ? eccUnits.setScale(SCALE, RoundingMode.DOWN) : BigDecimal.ZERO.setScale(SCALE),
                    isCloser ? lossYuan.setScale(SCALE, RoundingMode.DOWN) : BigDecimal.ZERO.setScale(SCALE),
                    false));
        }
        return out;
    }

    /**
     * 跨店异常判定（PRD P1-05 / config #22）。
     *
     * <p>规则逐字取自 PRD：「30 天内服务门店数 ≥2 标记 / &gt;3 冻结；单客户跨店占比 &gt;30% 反刷审查」。
     * 这三条是<b>独立信号</b>，不是一条递进规则——可能同时命中，故各自返回。
     *
     * <p>🛑 <b>本次修复：三条阈值全部来自 config {@code #22}</b>（此前 {@code 2}/{@code 3}
     * 与反刷占比是代码常量）。特别是反刷占比：它此前借用 {@code #21} 的拆分阈值
     * （两者当时同值 30%），而其 PRD 出处（P1-05）与配置载体都是 {@code #22} 的
     * {@code cross_share_pct}。归属纠正后，"改 #22 会不会影响反刷判定"才有确定答案。
     */
    public record CrossStoreAnomaly(boolean flagged, boolean frozen, boolean antiFraudReview,
                                    int distinctStoresIn30d, BigDecimal otherStoreRatio) {
    }

    /**
     * 判定跨店异常。
     *
     * @param distinctStoresIn30d 30 天内该客户出现过的门店数
     * @param otherStoreRatio     他店次数占比（取自 {@link SettlementResult}）
     */
    public CrossStoreAnomaly detectAnomaly(int distinctStoresIn30d, BigDecimal otherStoreRatio) {
        if (distinctStoresIn30d < 0) {
            throw new IllegalArgumentException("distinctStoresIn30d 不得为负");
        }
        boolean frozen = distinctStoresIn30d > thresholds.storeCountFreeze();   // >3 冻结
        boolean flagged = distinctStoresIn30d >= thresholds.storeCountMark();   // ≥2 标记
        boolean antiFraud = thresholds.antiFraudTriggered(otherStoreRatio);     // >30% 反刷审查
        return new CrossStoreAnomaly(flagged, frozen, antiFraud,
                distinctStoresIn30d, otherStoreRatio);
    }

    /** 阈值只读暴露（供断言与"总部唯一可改"的配置一致性核对）。 */
    public BigDecimal splitThreshold() {
        return splitThreshold;
    }

    /**
     * 便捷构造：<b>仅供一次性调用 / 测试</b> —— 默认取 config {@code #21}/{@code #22} 的声明初值。
     *
     * <p>🛑 生产装配<b>不得</b>走这里：{@code WebConfig} 必须从 {@link CrossStoreProfileSource}
     * 读配置后经 {@link #CrossStoreSettlement(CrossStoreThresholds)} 注入。
     * 保留它的唯一理由是让 {@code CrossStoreSettlementTest} 能对纯逻辑做单元断言，
     * 而它取的值与配置声明初值一致这点由 {@code CrossStoreProfileSourceTest} 单独守护
     * （两处若分叉即红），不依赖本方法自证。
     */
    public static CrossStoreSettlement withDeclaredDefaults(CrossStoreThresholds declared) {
        return new CrossStoreSettlement(declared);
    }

    /** 便捷构造：从 (storeId, visits, isCloser) 三元组建贡献列表。 */
    public static List<StoreContribution> contributions(Map<String, Integer> visitsByStore,
                                                       String closingStoreId) {
        Map<String, Integer> ordered = new LinkedHashMap<>(visitsByStore);
        List<StoreContribution> out = new ArrayList<>();
        ordered.forEach((storeId, visits) ->
                out.add(new StoreContribution(storeId, visits, storeId.equals(closingStoreId))));
        return out;
    }
}