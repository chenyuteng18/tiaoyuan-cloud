package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code band_sync_probe.history_type} 的<b>14 值封闭词汇</b> —— 留存窗口探针"探的是哪一路历史"。
 *
 * <h2>权威来源（逐字可追溯，不是自造）</h2>
 * <ol>
 *   <li>{@code V5__remaining_entities_org_journey_verdict_refund.sql} 第 914~916 行的
 *       {@code CHECK (history_type IN (...))} —— <b>14 值</b>；</li>
 *   <li>{@code V22__band_refetch_provisioning.sql} 的 {@code register_sync_probe()} 内
 *       {@code v_hist_types} 数组 —— 与上条<b>逐字一致</b>（同一集合写两遍是刻意的：
 *       一处守库层事实、一处给可归因报错，见 V22 第 0 节硬边界）。</li>
 * </ol>
 * <p>{@link #all()} 与上面两处的一致性由 {@code BandRefetchGateTest} 机械核对
 * （枚举 ↔ V22 函数内数组 ↔ V5 CHECK），不靠人读。</p>
 *
 * <h2>🛑🛑 为什么必须是【14】值，而不是 {@code band_telemetry.metric} 的 13 值</h2>
 * {@code V3__band_telemetry_metric_long_table.sql} 第 41~43 行的
 * {@code band_telemetry.metric} CHECK 只有 <b>13</b> 值（无 {@code sport}）。
 * 而本表是 <b>14</b>。这两者<b>不同名、不同数目</b>，且这个差别是刻意的：
 * <pre>
 *   契约 §4.3 双分支幂等键（{@code TelemetryRow} 类注释逐字引用）：
 *     按日型 12 条 → (device_id, metric, date, hour, minute)
 *     游标型 1 条  → (device_id, 'sport', current_sport_id)
 * </pre>
 * {@code metric}（遥测落库）<b>只收按日型</b>的那 13 个名字，游标型走独立的
 * {@code sport_id} 分支、不落 {@code metric} 列；而 {@code history_type}（本表）
 * 是"要向设备索要哪一路历史"—— 它<b>必须同时能表达游标型</b>，否则
 * "运动历史的留存窗口"这件事在探针表里没有落脚处。
 * <p>🛑 本枚举<b>不建立</b> {@code HistoryMetric ↔ HistoryType}（厂商 SDK 方法名）
 * 的映射：{@code HistoryType} 的类注释已逐字声明"映射留给落库链路专门处理"，
 * 且映射错了会表现为<b>数据对不上</b>而不是一条报错 —— 属最难排查的一类缺陷。
 * 本枚举只认落库词汇。
 *
 * <h2>🛑 一处【登记待裁】必须随本枚举一起被读到（V5 §9 · A-8）</h2>
 * 三个集合<b>并非同一集合</b>，这是既有登记、不是本枚举新引入的问题：
 * <ul>
 *   <li>字典 §4.6 的举例是 SDK 接口名 <b>camelCase</b>（{@code heartRate}/{@code step}/…）；</li>
 *   <li>{@code HistoryType} 是 <b>13</b> 条厂商方法名，且<b>含</b>
 *       {@code getBloodSugarHistory} —— 而 §4.8 明令禁止建模血糖；</li>
 *   <li>本枚举（= V3/V5 落库词汇）是 <b>14</b> 个小写下划线名，<b>不含</b>血糖。</li>
 * </ul>
 * ⇒ 本迁移(A-3) <b>不动</b>这套词汇，也不替上游裁决 —— 若将来统一，须一次改动
 * V3、V5、V22 三处并同步契约。
 */
public enum HistoryMetric {

    // ---- 按日型 × 13（入参 {year, month, day}；与 band_telemetry.metric 同名）----
    SLEEP("sleep", Kind.DAILY),
    STEPS("steps", Kind.DAILY),
    HR("hr", Kind.DAILY),
    RESTING_HR("resting_hr", Kind.DAILY),
    SPO2("spo2", Kind.DAILY),
    WORKOUT("workout", Kind.DAILY),
    BP("bp", Kind.DAILY),
    TEMP("temp", Kind.DAILY),
    PRESSURE("pressure", Kind.DAILY),
    MET("met", Kind.DAILY),
    MAI("mai", Kind.DAILY),
    RESPIRATION("respiration", Kind.DAILY),
    EXERCISE("exercise", Kind.DAILY),

    // ---- 游标型 × 1（入参单 boolean；靠返回体 sportLength 循环翻页）----
    SPORT("sport", Kind.CURSOR);

    /**
     * 取数形态。<b>只作可读性与断言用，不驱动本迁移的任何分支</b>。
     *
     * <p>🛑 与 {@code band.telemetry} 的 {@code HistoryType.Kind} <b>不是</b>同一个枚举，
     * 也不互相转换 —— 两者一个在厂商接口层、一个在落库词汇层（见类注释）。
     * 这里保留 {@code Kind} 的唯一理由是"让『补拉必须逐日 × 13 接口 + 运动单开游标分支』
     * 这条契约事实在类型上可见"，而不是为了在这里分派逻辑。
     */
    public enum Kind {
        /** 按日型：{@code {year,month,day}} 一调用返回一天，逐日持久化。 */
        DAILY,
        /** 游标型：单 boolean 入参，靠 {@code sportLength > 1} 判定继续翻页。 */
        CURSOR
    }

    private final String code;
    private final Kind kind;

    HistoryMetric(String code, Kind kind) {
        this.code = code;
        this.kind = kind;
    }

    /** 落库字面量（= {@code band_sync_probe.history_type} 的取值）。 */
    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    /** 全部落库字面量（供契约回归与门禁逐字比对，<b>不</b>用 {@code name()}）。 */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(HistoryMetric::code).toList();
    }

    /** 按日型数量 —— 应为 13。 */
    public static long dailyCount() {
        return Arrays.stream(values()).filter(m -> m.kind == Kind.DAILY).count();
    }

    /** 游标型数量 —— 应为 1。 */
    public static long cursorCount() {
        return Arrays.stream(values()).filter(m -> m.kind == Kind.CURSOR).count();
    }

    /**
     * 由落库字面量解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>🛑 为什么"未登记就抛"而不是落到某个默认值</h2>
     * 依据 = §2.8.7③「依据」行 + V22 的 (5a)：
     * <pre>
     *   N_retention = min(各 history_type 的 retention_window_days)
     * </pre>
     * 一个<b>拼错的</b>指标名会<b>悄悄退出这个 {@code min()}</b>，于是补拉窗口被一个
     * 比真实值更大的数决定 ⇒ 回溯超出设备窗口 ⇒ 设备不返回该日数据 ⇒
     * <b>制造出「假缺失」</b>。而"假缺失"正是 §2.8.7 逐字警告的形态，
     * 也是"唯一可扣分"那一类（{@code not_worn}）最容易被误判进来的入口。
     * <p>落默认值会让这条漂移静默通过 —— 故此处与 V22 的 (5a) 同力度：
     * 本方法抛 {@code 5001}，函数抛 {@code P0001}。
     *
     * <p>大小写不敏感、两侧空白容忍（不同平台的枚举序列化不一致）—— 与
     * {@code HistoryType.parse} 同款处置，因为两者面对的客户端是同一个。
     */
    public static HistoryMetric parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "history_type 必填且必须在已登记的 14 值词汇内（band_sync_probe.history_type 是 NOT NULL）。"
                            + "🛑 空串在语义上无意义，且会静默流进台账 —— 故与 V22 的 (1) 同力度拒绝");
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        Optional<HistoryMetric> hit = Arrays.stream(values())
                .filter(m -> m.code.equals(v))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "history_type 不在已登记的 14 值词汇内: '" + raw + "'。已登记 = "
                        + String.join(", ", allCodes())
                        + "。🛑 不得静默接受未知指标：§2.8.7③ 的消费规则是 "
                        + "N_retention = min(各 history_type 的 retention_window_days)，"
                        + "一个拼错的指标名会悄悄退出 min()，让补拉窗口被一个比真实值更大的数决定，"
                        + "从而回溯超出设备窗口、制造出【假缺失】"));
    }
}