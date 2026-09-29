package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 厂商手环历史接口的 {@code HistoryType}（13 条）。
 *
 * <h2>权威来源（逐条可追溯，不是自造）</h2>
 * {@code _work/gtl1-wx-open-sync-sdk-proto-test-2026-09-19.md} 的「13 条逐日历史接口核验」
 * 逐行列出 13 个 SDK 方法及其入参；契约 §4.2 把 {@code history_type} 定义为
 * 「enum{13 条逐日接口对应的 {@code HistoryType}}，与厂商枚举对齐」。
 *
 * <h2>两个刻意的设计决定</h2>
 * <ol>
 *   <li><b>12 + 1 分列，不合并成一个无差别集合</b>：契约 §4.3 / 偏差 D-A 已冻结
 *       「运动数据不得复用按日型幂等键」。{@code getSportHistory} 入参是<b>单 boolean</b>（游标型），
 *       与其余 12 条 {@code {year,month,day}}（按日型）<b>不同构</b>。
 *       本枚举用 {@link Kind} 把这一事实编码进去，使"选了运动却按日型处理"在编译期就难以发生。</li>
 *   <li><b>不含任何留存窗口天数</b>：契约 §4.2 的 🔴 硬性验收要求 N 一律取运行时
 *       {@code getValidHistoryDates} 探测值。把窗口写进枚举（例如给每个 historyType
 *       标一个默认天数）正是该红线要防的形态 —— 静态扫描守卫见
 *       {@code BandAvailableDatesTest#static_scan_finds_no_hardcoded_retention_window_in_the_band_sources}。</li>
 * </ol>
 *
 * <h2>与 {@code band_telemetry.metric} 的关系（刻意不在此处映射）</h2>
 * {@code metric} 的 13 值（{@code sleep/steps/hr/...}）是<b>我方落库口径</b>，
 * 与厂商接口名<b>不是同一个枚举</b>，其映射关系属契约 §4.3 的管辖范围。
 * 本枚举不擅自建立映射：映射错了会表现为"数据对不上"，而不是一条报错，
 * 属最难排查的一类缺陷。故此处只认厂商接口名，映射留给落库链路专门处理。
 */
public enum HistoryType {

    // ---- 按日型 × 12（入参 {year, month, day}）----
    STEP("getStepHistory", Kind.DAILY),
    HEART_RATE("getHeartRateHistory", Kind.DAILY),
    BLOOD_PRESSURE("getBloodPressureHistory", Kind.DAILY),
    BLOOD_OXYGEN("getBloodOxygenHistory", Kind.DAILY),
    PRESSURE("getPressureHistory", Kind.DAILY),
    MET("getMetHistory", Kind.DAILY),
    TEMP("getTempHistory", Kind.DAILY),
    MAI("getMaiHistory", Kind.DAILY),
    SLEEP("getSleepHistory", Kind.DAILY),
    RESPIRATION_RATE("getRespirationRateHistory", Kind.DAILY),
    EXERCISE("getExerciseHistory", Kind.DAILY),
    BLOOD_SUGAR("getBloodSugarHistory", Kind.DAILY),

    // ---- 游标型 × 1（入参单 boolean；靠返回体 sportLength 循环）----
    SPORT("getSportHistory", Kind.CURSOR);

    /** 入参形态。按日型吃 {@code {year,month,day}}；游标型吃单 boolean 并由 {@code sportLength>1} 续拉。 */
    public enum Kind {
        DAILY,
        CURSOR
    }

    private final String sdkMethod;
    private final Kind kind;

    HistoryType(String sdkMethod, Kind kind) {
        this.sdkMethod = sdkMethod;
        this.kind = kind;
    }

    /** 厂商 SDK 上的方法名（证据来源：协议层测试报告的接口核验表首列）。 */
    public String sdkMethod() {
        return sdkMethod;
    }

    public Kind kind() {
        return kind;
    }

    /** 按日型接口数量 —— 契约 §4.3 冻结为 12。 */
    public static long dailyCount() {
        return Arrays.stream(values()).filter(t -> t.kind == Kind.DAILY).count();
    }

    /** 游标型接口数量 —— 契约 §4.3 冻结为 1（运动单开分支）。 */
    public static long cursorCount() {
        return Arrays.stream(values()).filter(t -> t.kind == Kind.CURSOR).count();
    }

    /** 全部取值（供契约回归用例集逐条比对）。 */
    public static List<String> allNames() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * 解析入参。大小写不敏感、两侧空白容忍（客户端实现在不同平台上对枚举序列化不一致）。
     *
     * <p><b>fail-closed</b>：不认识的值一律抛 {@code 5001}（契约 §4.2 明定
     * 「{@code 5001}（{@code history_type} 不在 13 条枚举内）」），
     * <b>绝不回落到某个默认值</b> —— 回落到"按日型"会让运动数据走错幂等键分支，
     * 表现为数据静默错位而不是报错。
     */
    public static HistoryType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "history_type 必填且必须在 13 条厂商枚举内");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        Optional<HistoryType> hit = Arrays.stream(values())
                .filter(t -> t.name().equals(normalized))
                .findFirst();
        return hit.orElseThrow(() -> new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "history_type 不在 13 条厂商枚举内: " + raw
                        + "（合法值: " + String.join(",", allNames()) + "）"));
    }
}