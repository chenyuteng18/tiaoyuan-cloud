package com.diaoyuanyun.dy.app.band.service;

import com.diaoyuanyun.dy.app.band.domain.BandProbeRequest;
import com.diaoyuanyun.dy.app.band.domain.BandProbeResult;
import com.diaoyuanyun.dy.app.band.domain.HistoryType;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * S1-7 手环「设备可用日期探测」服务 —— 契约 §4.2（E5 {@code POST /api/v1/band/available-dates}）。
 *
 * <h2>本类唯一要防的事故：把 N 写成常量</h2>
 * N = 设备留存窗口（{@code retention_window_days}）。它在厂商文档里<b>没有值</b>：
 * 协议层测试报告「未测（不可宣称）」一节明确列出「实际留存窗口 N
 * （<b>改为运行时用 {@code getValidHistoryDates} 实测</b>）」。
 * 契约 §4.2 因此把 N 定为<b>运行时探测值</b>，并列出三条硬约束：
 * <ol>
 *   <li>服务端<b>不得</b>内置 {@code N = 7} / {@code N = 14} 等任何常量；</li>
 *   <li><b>不得回溯超过探测到的窗口</b> —— 超窗口日期设备不返回，只会制造"假缺失"；</li>
 *   <li>未探测到值时返回 {@code TBD}，<b>UI 与文案不得给出具体天数</b>，也不得按"数据齐全"预设布局。</li>
 * </ol>
 *
 * <h2>本类怎么保证第 1 条（不是靠自觉）</h2>
 * <ul>
 *   <li>{@link #TBD} 是<b>字符串</b>而非数字 —— 下游任何算术一旦碰它就会立刻失败，
 *       而不是悄悄按 7 天回溯；</li>
 *   <li>{@link #computeRetentionWindowDays(List)} 的返回值<b>只能</b>由入参数组算出 ——
 *       它没有其它输入（看不到配置、看不到常量、看不到环境变量），
 *       故"窗口从哪来"在结构上只有唯一答案；无数据即 {@link Optional#empty()}；</li>
 *   <li>静态扫描守卫见 {@code BandAvailableDatesTest}：扫本文件与控制器源码，
 *       命中形如 {@code retentionWindowDays = <数字>} / {@code N = <数字>} 的常量赋值即失败。</li>
 * </ul>
 *
 * <h2>为什么窗口取「跨度」而不是"日期个数"</h2>
 * 契约 §4.2 出参说明 = 「运行时探测值（<b>= 探测到的可用日期跨度</b>）」。
 * 采用<b>跨度</b>（{@code latest − earliest}；同一天记 0）的理由：留存语义是
 * "能回溯多远"，不是"有几天有数据"。设备可能只对少数几天返回数据（未佩戴 / 未同步），
 * 若按个数计，同一跨度会因设备返回的稀疏程度得出不同窗口，回溯边界随即不稳。
 * 把口径钉在跨度上，使"回溯边界"这件事只有一个定义。
 */
@Service
public class BandAvailableDatesService {

    /**
     * N 未探测到时的对外字面量（契约 §4.2 出参类型：{@code int | TBD}）。
     *
     * <p>它是<b>字符串</b>而不是某个数字，正是为了让下游无法把它当数字消费。
     * 字面值取自 {@link ContractTbd#TBD} —— 全项目唯一字面源，
     * 避免某处写成 {@code "tbd"} 后客户端静默匹配失败。
     */
    public static final String TBD = ContractTbd.TBD;

    /** 探测窗口下限：契约 §4.2 明定「{@code valid_history_dates} 为空<b>且非首次绑定</b>」→ 1001。 */
    private static final int MIN_PROBED_DATES = 1;

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    /**
     * 处理一次探测上报：校验 → 算窗口 → 算回溯起点。
     *
     * <p>顺序刻意如此：先做枚举校验（5001），再做日期校验（1001）——
     * 因为 {@code history_type} 决定"这 13 条里是哪一条"，是解析日期之前必须成立的前提。
     */
    public BandProbeResult probe(BandProbeRequest request) {
        // ① history_type 必须是 13 条之一 —— 不认识一律 5001（契约 §4.2 错误码），不回落到默认值。
        //    回落（例如当"按日型"处理）会让运动数据走错幂等键分支，表现为数据静默错位而非报错。
        HistoryType type = HistoryType.parse(request == null ? null : request.historyType());

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "device_id 必填");
        }
        if (request.probedAt() == null || request.probedAt().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "probed_at 必填（无它则『延迟』与『缺失』不可分辨）");
        }

        List<LocalDate> dates = parseDates(request.validHistoryDates());

        // ② 空数组的两种语义必须分辨（契约 §4.2：仅"非首次绑定"才算 1001）。
        //    首次绑定时设备可能确实还没有任何历史 —— 那是"预期为空"，不是"探测失败"。
        if (dates.size() < MIN_PROBED_DATES && !Boolean.TRUE.equals(request.firstBinding())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "valid_history_dates 为空且非首次绑定 —— 探测未取得任何可用日期，"
                            + "不得按空集推进补拉（否则会制造『假缺失』）");
        }

        // ③ N：只能由入参数组算出。无入参 → 无 N → TBD。
        Optional<Integer> windowDays = computeRetentionWindowDays(dates);

        // ④ 回溯起点：只有拿到 N 才算；拿不到就一并 TBD，绝不用任何默认窗口顶替
        LocalDate pullStart = computePullStartDate(windowDays, parseDateOrNull(request.lastSyncedDate()));

        String probeId = "probe-"
                + type.name().toLowerCase(Locale.ROOT)
                + "-" + Integer.toHexString(Objects.hash(request.deviceId(), request.probedAt(), dates));

        return new BandProbeResult(
                probeId,
                windowDays.<Object>map(d -> (Object) d).orElse(TBD),
                pullStart == null ? TBD : pullStart,
                dates.isEmpty() ? null : dates.get(0),
                dates.isEmpty() ? null : dates.get(dates.size() - 1),
                type.name(),
                windowDays.isPresent());
    }

    // ------------------------------------------------------------------
    // 纯函数：N 与回溯起点
    // 对外可见，便于独立断言 —— 这两条是 S1-7 的验收核心，不该藏在 Spring 容器后面才可测。
    // ------------------------------------------------------------------

    /**
     * 由探测到的日期数组计算留存窗口 N（单位：天，= 跨度 {@code latest − earliest}）。
     *
     * <p><b>无数据 ⇒ 返回 {@link Optional#empty()}（= TBD）。</b>
     * 这是本方法的全部输入 —— 它看不到配置、看不到常量、看不到环境变量，
     * 故"窗口从哪来"在结构上不存在第二种答案。
     *
     * <p>副作用：会把入参排序去重（客户端返回顺序不保证、且可能含重复日）。
     * 排序去重是必要的：跨度必须用最早/最晚<b>真实可用日</b>算，乱序数组会算出错误的窗口。
     */
    public Optional<Integer> computeRetentionWindowDays(List<LocalDate> validHistoryDates) {
        if (validHistoryDates == null || validHistoryDates.isEmpty()) {
            return Optional.empty();
        }
        List<LocalDate> ordered = normalize(validHistoryDates);
        LocalDate earliest = ordered.get(0);
        LocalDate latest = ordered.get(ordered.size() - 1);
        return Optional.of((int) ChronoUnit.DAYS.between(earliest, latest));
    }

    /**
     * 按契约 §4.2 公式计算补拉起点：
     * {@code start = max(last_synced_date + 1, today − retention_window_days)}，
     * 上界 = {@code today}（含当日）。
     *
     * <p><b>fail-closed 三则</b>（都不是猜测，而是"不猜"）：
     * <ol>
     *   <li>N 为 TBD → 返回 {@code null}（调用方输出 TBD）。
     *       🛑 此处正是本方法最容易被"顺手补个默认值"的地方；补默认值 = 直接违反契约 §4.2 红线②。</li>
     *   <li>{@code last_synced_date} 缺失 → 同样返回 {@code null}。
     *       契约公式两个下界缺一不可，缺了无法断言"从哪开始"，故不猜。</li>
     *   <li>算出的起点若晚于 today → 收敛为 today。<b>绝不越过 today 向前看</b>：
     *       未来日期设备必然不返回，越过 today 只会制造"假缺失"。</li>
     * </ol>
     *
     * @return 可提交的起点日期；无法诚实计算时返回 {@code null}
     */
    public LocalDate computePullStartDate(Optional<Integer> retentionWindowDays, LocalDate lastSyncedDate) {
        if (retentionWindowDays == null || retentionWindowDays.isEmpty() || lastSyncedDate == null) {
            return null;
        }
        int windowDays = retentionWindowDays.get();
        LocalDate today = LocalDate.now();

        // 下界①：上次同步日 + 1（不重复拉已确认同步过的区间）
        LocalDate afterLastSynced = lastSyncedDate.plusDays(1);
        // 下界②：today − N（不得回溯超过探测到的窗口 —— 超窗口日期设备不返回）
        LocalDate windowFloor = today.minusDays(windowDays);

        // max(两下界) —— 取更靠后者，才同时满足"不重复"与"不超窗口"
        LocalDate start = afterLastSynced.isAfter(windowFloor) ? afterLastSynced : windowFloor;

        // 上界：today（含当日）。start 若跑到 today 之后即收敛，绝不越过 today
        return start.isAfter(today) ? today : start;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析日期数组，逐项 fail-closed（非法日期一律 1001，不静默丢弃 —— 静默丢弃会把坏数据当"缺数据"）。 */
    private static List<LocalDate> parseDates(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<LocalDate> parsed = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                parsed.add(LocalDate.parse(s.trim()));
            } catch (DateTimeParseException e) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "valid_history_dates 含非法日期字面量（应为 ISO-8601 yyyy-MM-dd）: " + s);
            }
        }
        return normalize(parsed);
    }

    private static LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "last_synced_date 非法日期字面量（应为 ISO-8601 yyyy-MM-dd）: " + raw);
        }
    }

    /** 排序 + 去重（跨度必须基于最早与最晚的真实可用日）。 */
    private static List<LocalDate> normalize(List<LocalDate> dates) {
        return dates.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    /**
     * 供演示/调试用的自描述视图（不含任何窗口数值）。
     *
     * <p>刻意<b>不</b>给"建议回溯天数"之类字段 —— 那会被下游当成 N 的替代品。
     */
    public Map<String, Object> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", "POST /api/v1/band/available-dates");
        m.put("retention_window_days", "运行时探测值；未探测到 -> \"" + TBD + "\"（禁止硬编码 N）");
        m.put("history_type_values", HistoryType.allNames());
        m.put("daily_branch_count", HistoryType.dailyCount());
        m.put("cursor_branch_count", HistoryType.cursorCount());
        return m;
    }
}