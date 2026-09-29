package com.diaoyuanyun.dy.app.band;

import com.diaoyuanyun.dy.app.band.domain.BandProbeRequest;
import com.diaoyuanyun.dy.app.band.domain.BandProbeResult;
import com.diaoyuanyun.dy.app.band.domain.HistoryType;
import com.diaoyuanyun.dy.app.band.service.BandAvailableDatesService;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-7「设备可用日期探测」契约守卫 —— 契约 §4.2（E5）。
 *
 * <h2>本类盯的唯一一件核心事：N 必须是运行时探测值</h2>
 * N = 设备留存窗口（{@code retention_window_days}）。它在厂商文档里<b>没有值</b>
 * （协议层测试报告把它列在「未测（不可宣称）」一节）。契约 §4.2 因此把它定为
 * <b>运行时探测值</b>，并给出三条硬约束，其中第 1 条就是
 * 「服务端<b>不得</b>内置 {@code N = 7} / {@code N = 14} 等任何常量」。
 *
 * <p>本类用三种<b>互不依赖</b>的方式钉住它，任何一条单独失效都不会让这件事失控：
 * <ol>
 *   <li><b>静态扫描</b>（{@code static_scan_...}）：扫服务与控制器源码，命中
 *       {@code retentionWindowDays = <数字>}、{@code N = <数字>}、
 *       {@code retention_window_days = <数字>} 形态的常量赋值即失败。</li>
 *   <li><b>结构性</b>（{@code window_comes_only_from_the_probed_dates}）：
 *       {@link BandAvailableDatesService#computeRetentionWindowDays(List)} 的<b>唯一输入</b>
 *       就是日期数组 —— 它看不到配置、看不到常量、看不到环境变量。
 *       故"窗口从哪来"在结构上不存在第二种答案。</li>
 *   <li><b>类型性</b>（{@code unprobed_value_is_a_string_not_a_number}）：
 *       未探测到时返回的是<b>字符串</b> {@code "TBD"} 而不是某个数字 ——
 *       下游任何算术一旦碰它就会立刻失败，而不是悄悄按 7 天回溯。</li>
 * </ol>
 *
 * <h2>为什么"跨度"而不是"日期个数"</h2>
 * 契约 §4.2 出参说明 = 「运行时探测值（<b>= 探测到的可用日期跨度</b>）」。
 * 本类用 {@code window_uses_span_not_count} 把这条口径钉住：
 * 同一跨度下"有数据的天数"不同，窗口必须相同 —— 否则回溯边界会随设备返回的
 * 稀疏程度漂移，而"回溯多远"这件事就失去了唯一定义。
 */
class BandAvailableDatesTest {

    private static final Path BAND_MAIN_DIR = resolveBandMainDir();

    private final BandAvailableDatesService service = new BandAvailableDatesService();

    // ==================================================================
    // 一、静态扫描：源码里不得出现硬编码的 N
    // ==================================================================

    @Test
    @DisplayName("静态扫描：服务/控制器/DTO 源码内不得硬编码 N（retention_window_days = 数字）")
    void static_scan_finds_no_hardcoded_retention_window_in_the_band_sources() throws IOException {
        // 形态覆盖：赋值 / 常量声明 / JSON 字面量，变量名与字段名的各种写法
        Pattern hardcoded = Pattern.compile(
                "(retentionWindowDays|retention_window_days|RETENTION_WINDOW_DAYS|RETENTION_WINDOW|"
                        + "\\bN\\b)\\s*[=:]\\s*\\d+",
                Pattern.CASE_INSENSITIVE);

        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (String rel : List.of(
                "service/BandAvailableDatesService.java",
                "controller/BandAvailableDatesController.java",
                "domain/BandProbeRequest.java",
                "domain/BandProbeResult.java",
                "domain/HistoryType.java")) {
            Path p = BAND_MAIN_DIR.resolve(rel);
            assertTrue(Files.isRegularFile(p), "待扫描的源文件必须存在: " + p);
            String raw = read(p);
            String code = stripCommentsAndStrings(raw);
            scanned++;

            // 前置自证①：注释与字符串确实被剥掉了。
            // 服务类的 Javadoc 里【逐字写着】「不得内置 N = 7 / N = 14 等任何常量」——
            // 那是【在描述禁止事项】。若不剥注释，这句禁止事项本身会被当成违规命中，
            // 于是扫描器把"写明禁止"判成"违反禁止"。
            // 该字面只存在于 BandAvailableDatesService 的 Javadoc，故只对那个文件设前置。
            if (rel.endsWith("BandAvailableDatesService.java")) {
                assertTrue(raw.contains("N = 7"),
                        "前置失效：期望 " + rel + " 的 Javadoc 里逐字含有「N = 7」这句禁止事项"
                                + "（扫描器正是靠剥掉它来避免自我误报）；文件已被改动，请复核本断言");
                assertFalse(code.contains("N = 7"),
                        "注释剥离失效：" + rel + " 剥完还是含有 'N = 7' —— "
                                + "块注释匹配漏了 DOTALL，扫描器会把『写明禁止』判成『违反禁止』");
            }

            Matcher m = hardcoded.matcher(code);
            while (m.find()) {
                violations.add(rel + " 命中硬编码窗口: " + m.group());
            }
        }

        assertTrue(violations.isEmpty(),
                "源码里出现硬编码的留存窗口 N —— 契约 §4.2 硬约束①：N 必须取运行时探测值，"
                        + "不得内置任何常量（超窗口回溯只会制造『假缺失』）。\n  - "
                        + String.join("\n  - ", violations));
        assertEquals(5, scanned, "必须真的扫到 5 个源文件（少于 5 说明路径解析失效、扫描退化为恒绿）");
    }

    @Test
    @DisplayName("静态扫描：历史类型枚举必须恰好 12 按日 + 1 游标，且枚举值名与 SDK 方法名可枚举")
    void history_types_are_exactly_twelve_daily_plus_one_cursor() {
        assertEquals(13, HistoryType.values().length, "契约 §4.3：13 条接口 = 12 按日 + 1 游标");
        assertEquals(12, HistoryType.dailyCount(), "按日型应恰为 12 条");
        assertEquals(1, HistoryType.cursorCount(), "游标型应恰为 1 条（getSportHistory）");

        assertEquals(HistoryType.Kind.CURSOR, HistoryType.SPORT.kind(),
                "SPORT 必须是游标型（入参为单 boolean，靠 sportLength 循环）");
        assertEquals("getSportHistory", HistoryType.SPORT.sdkMethod(),
                "游标型的 SDK 方法名必须与契约 §4.3 逐字一致");
        assertEquals(HistoryType.Kind.DAILY, HistoryType.STEP.kind(), "STEP 必须是按日型");

        // 13 个名字里不得有重复（重名会让 parse 静默指向错分支）
        assertEquals(13, HistoryType.allNames().size(), "13 条枚举名不得有重复");
    }

    // ==================================================================
    // 二、N 只能来自入参数组（结构性）
    // ==================================================================

    @Test
    @DisplayName("N 只能由探测数组算出：无数据 → empty（= TBD），绝无第二种来源")
    void window_comes_only_from_the_probed_dates() {
        assertEquals(Optional.empty(), service.computeRetentionWindowDays(null),
                "null 入参应得 empty（= TBD），不得回落到任何默认窗口");
        assertEquals(Optional.empty(), service.computeRetentionWindowDays(List.of()),
                "空数组应得 empty（= TBD）");

        LocalDate d = LocalDate.now();
        assertEquals(Optional.of(0), service.computeRetentionWindowDays(List.of(d)),
                "单日 → 跨度 0（留存窗口是『能回溯多远』，只有一天就是 0）");
        assertEquals(Optional.of(3),
                service.computeRetentionWindowDays(List.of(d.minusDays(3), d)),
                "跨度应为 latest − earliest");
    }

    @Test
    @DisplayName("窗口用【跨度】而非【日期个数】：稀疏与密集的同跨度必须得到同一窗口")
    void window_uses_span_not_count() {
        LocalDate d = LocalDate.now();
        List<LocalDate> dense = List.of(d.minusDays(4), d.minusDays(3), d.minusDays(2), d.minusDays(1), d);
        List<LocalDate> sparse = List.of(d.minusDays(4), d);   // 同一跨度，只有 2 天有数据

        assertEquals(4, dense.size() - 1, "前置：密集数组跨度应为 4");
        assertEquals(Optional.of(4), service.computeRetentionWindowDays(dense));
        assertEquals(Optional.of(4), service.computeRetentionWindowDays(sparse),
                "同一跨度下『有数据的天数』不同时，窗口必须相同 —— 否则回溯边界会随稀疏程度漂移");
        assertFalse(service.computeRetentionWindowDays(sparse).get().equals(sparse.size()),
                "窗口不得等于数组长度（那会把『个数』当『跨度』）");
    }

    @Test
    @DisplayName("数组乱序 / 含重复也必须得到同一窗口（跨度基于最早与最晚真实可用日）")
    void window_is_stable_under_unsorted_and_duplicate_input() {
        LocalDate d = LocalDate.now();
        List<LocalDate> ordered = List.of(d.minusDays(5), d.minusDays(3), d);
        List<LocalDate> shuffled = List.of(d, d.minusDays(5), d.minusDays(3), d.minusDays(5));

        assertEquals(service.computeRetentionWindowDays(ordered),
                service.computeRetentionWindowDays(shuffled),
                "乱序/含重复的输入必须得到与有序输入相同的窗口（客户端返回顺序不保证）");
        assertEquals(Optional.of(5), service.computeRetentionWindowDays(shuffled));
    }

    // ==================================================================
    // 三、回溯起点公式（契约 §4.2）
    // ==================================================================

    @Test
    @DisplayName("回溯起点 = max(last_synced+1, today−N)，上界 today 含当日")
    void pull_start_date_follows_the_contract_formula() {
        LocalDate today = LocalDate.now();

        // 情形 ①：窗口下界更靠后（N 小、上次同步很久以前）→ 取 today−N
        assertEquals(today.minusDays(3),
                service.computePullStartDate(Optional.of(3), today.minusDays(30)),
                "max(today−N, lastSynced+1) 应取 today−3");

        // 情形 ②：上次同步下界更靠后 → 取 lastSynced+1（不重复拉已同步区间）
        assertEquals(today.minusDays(1),
                service.computePullStartDate(Optional.of(30), today.minusDays(2)),
                "lastSynced+1 更靠后时应取它（避免重复拉已确认同步过的区间）");

        // 情形 ③：起点跑到 today 之后 → 收敛为 today（绝不越过 today 向前看）
        assertEquals(today,
                service.computePullStartDate(Optional.of(3), today),
                "start 晚于 today 时应收敛为 today（未来日期设备必然不返回）");
    }

    @Test
    @DisplayName("回溯起点 fail-closed：N 为 TBD 或 last_synced_date 缺失 → null，绝不补默认值")
    void pull_start_date_is_null_when_it_cannot_be_honestly_computed() {
        LocalDate today = LocalDate.now();

        assertEquals(null, service.computePullStartDate(Optional.empty(), today.minusDays(3)),
                "N 为 TBD 时不得算出起点 —— 补一个默认窗口就是违反契约 §4.2 红线②");
        assertEquals(null, service.computePullStartDate(null, today.minusDays(3)),
                "N 为 null 时不得算出起点");
        assertEquals(null, service.computePullStartDate(Optional.of(7), null),
                "last_synced_date 缺失时不得算出起点（公式两个下界缺一不可）");
        assertEquals(null, service.computePullStartDate(Optional.empty(), null),
                "两个输入都缺时不得算出起点");

        // 反证本条不是恒绿：给出完整输入时必须能算出非空起点。
        // 少了这一条，上面四个 assertEquals(null, …) 在方法被改成"永远返回 null"时仍会全绿。
        assertNotNull(service.computePullStartDate(Optional.of(3), today.minusDays(1)),
                "反证失败：完整输入（N=3 + last_synced=today−1）必须能算出起点，"
                        + "否则本断言组退化为『永远返回 null 也通过』");
    }

    @Test
    @DisplayName("回溯起点绝不早于 today−N（不得超窗口回溯，超窗口只会制造『假缺失』）")
    void pull_start_date_never_goes_beyond_the_probed_window() {
        LocalDate today = LocalDate.now();
        for (int n : List.of(0, 1, 7, 14, 30)) {
            LocalDate start = service.computePullStartDate(Optional.of(n), today.minusYears(1));
            assertNotNull(start, "有 N 与 lastSynced 时应能算出起点");
            assertFalse(start.isBefore(today.minusDays(n)),
                    "起点 " + start + " 早于 today−" + n + "（超窗口回溯）");
            assertFalse(start.isAfter(today), "起点 " + start + " 晚于 today（越过当日向前看）");
        }
    }

    // ==================================================================
    // 四、E5 端点行为
    // ==================================================================

    @Test
    @DisplayName("E5：正常探测返回窗口数值与探查到的日期边界")
    void probe_returns_the_probed_window_and_date_bounds() {
        LocalDate today = LocalDate.now();
        BandProbeResult r = service.probe(new BandProbeRequest(
                "dev-001", "STEP",
                List.of(today.minusDays(6).toString(), today.minusDays(3).toString(), today.toString()),
                today + "T10:00:00", today.minusDays(10).toString(), false));

        assertEquals(6, r.retentionWindowDays(), "窗口应为跨度 6");
        assertEquals(today.minusDays(6), r.earliestAvailable(), "最早可用日应为数组最早日");
        assertEquals(today, r.latestAvailable(), "最晚可用日应为数组最晚日");
        assertEquals("STEP", r.historyType(), "history_type 应原样回显（归一为大写枚举名）");
        assertTrue(r.retentionWindowProbed(), "已探测到值 → retention_window_probed = true");
        assertNotNull(r.pullStartDate(), "有 N 与 lastSynced 时应算出起点");
    }

    @Test
    @DisplayName("E5：未探测到值 → retention_window_days 与 pull_start_date 均为字符串 TBD")
    void unprobed_value_is_a_string_not_a_number() {
        LocalDate today = LocalDate.now();
        // 首次绑定 + 空数组 = 预期为空（不是探测失败）
        BandProbeResult r = service.probe(new BandProbeRequest(
                "dev-002", "HEART_RATE", List.of(), today + "T10:00:00", null, true));

        assertEquals("TBD", r.retentionWindowDays(),
                "未探测到 → 必须返回字符串 TBD，而不是数字（数字会被下游当窗口用）");
        assertEquals("TBD", r.pullStartDate(), "N 为 TBD → 起点一并 TBD，绝不用默认窗口顶替");
        assertFalse(r.retentionWindowProbed(), "未探测到 → retention_window_probed = false");
        // 类型自证：它是 String 而不是 Integer —— 下游算术会立刻失败而不是静默按某天数回溯
        assertTrue(r.retentionWindowDays() instanceof String,
                "TBD 必须是字符串（instanceof String），使任何算术在运行时立刻暴露");
    }

    @Test
    @DisplayName("E5：valid_history_dates 为空且非首次绑定 → 1001（预期为空与探测失败必须分辨）")
    void empty_dates_without_first_binding_is_a_validation_failure() {
        LocalDate today = LocalDate.now();
        BizException ex = assertThrows(BizException.class,
                () -> service.probe(new BandProbeRequest(
                        "dev-003", "STEP", List.of(), today + "T10:00:00", null, false)),
                "空数组且非首次绑定应被拒 —— 不得按空集推进补拉（会制造『假缺失』）");
        assertEquals(1001, ex.getCode(), "契约 §4.2：该情形错误码为 1001");
    }

    @Test
    @DisplayName("E5：history_type 不在 13 条枚举内 → 5001（不认识不回落到默认分支）")
    void unknown_history_type_is_a_business_rule_violation() {
        LocalDate today = LocalDate.now();
        // 🛑 注意 'STEP '（带尾空格）不在本例内：实现刻意做了大小写不敏感 + 两侧空白容忍
        //    （不同平台对枚举序列化不一致，故容忍空白是<b>有意</b>的健壮化，不是缺陷）。
        //    把它列进"必须被拒"会把一条正确的设计判成 bug。真正必须被拒的是：
        //    不存在的名字、空值、以及【把 SDK 方法名当成枚举名】这种最常见的误用。
        for (String bad : List.of("UNKNOWN", "getSportHistory", "STEP_HISTORY", "")) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.probe(new BandProbeRequest(
                            "dev-004", bad, List.of(today.toString()), today + "T10:00:00", null, true)),
                    "未知 history_type 被接受了: '" + bad + "'");
            assertEquals(5001, ex.getCode(),
                    "契约 §4.2：history_type 不在枚举内错误码为 5001");
        }
        // 反向自证：空白容忍是【有意】行为 —— 带空格的合法值必须被接受，
        // 否则上面那条"拒绝"就可能是靠"一律拒绝"实现的（那会让客户端空格 bug 变成 5001 而非可用）。
        BandProbeResult trimmed = service.probe(new BandProbeRequest(
                "dev-004b", "  step  ", List.of(today.toString()), today + "T10:00:00", null, true));
        assertEquals("STEP", trimmed.historyType(),
                "带空白/小写的合法枚举名应被归一接受（客户端平台差异容错）");
    }

    @Test
    @DisplayName("E5：device_id 与 probed_at 必填（无 probed_at 则『延迟』与『缺失』不可分辨）")
    void device_id_and_probed_at_are_required() {
        LocalDate today = LocalDate.now();
        assertThrows(BizException.class,
                () -> service.probe(new BandProbeRequest(
                        "  ", "STEP", List.of(today.toString()), today + "T10:00:00", null, true)),
                "空白 device_id 被接受了");
        BizException noProbedAt = assertThrows(BizException.class,
                () -> service.probe(new BandProbeRequest(
                        "dev-005", "STEP", List.of(today.toString()), null, null, true)),
                "缺 probed_at 被接受了");
        assertEquals(1001, noProbedAt.getCode());
        assertTrue(noProbedAt.getMessage().contains("probed_at"),
                "错误信息应点明缺的是 probed_at 及其后果；实际: " + noProbedAt.getMessage());
    }

    @Test
    @DisplayName("E5：非法日期字面量一律拒（不静默丢弃 —— 静默丢弃会把坏数据当『缺数据』）")
    void illegal_date_literals_are_rejected_not_silently_dropped() {
        LocalDate today = LocalDate.now();
        BizException ex = assertThrows(BizException.class,
                () -> service.probe(new BandProbeRequest(
                        "dev-006", "STEP",
                        List.of(today.toString(), "2026/09/01", "not-a-date"),
                        today + "T10:00:00", null, true)),
                "非法日期被接受了（或静默丢弃了）");
        assertEquals(1001, ex.getCode());
        assertTrue(ex.getMessage().contains("ISO") || ex.getMessage().contains("yyyy-MM-dd"),
                "错误信息应给出期望的日期格式；实际: " + ex.getMessage());

        BizException badLastSynced = assertThrows(BizException.class,
                () -> service.probe(new BandProbeRequest(
                        "dev-007", "STEP", List.of(today.toString()),
                        today + "T10:00:00", "09/01/2026", true)),
                "非法的 last_synced_date 被接受了");
        assertEquals(1001, badLastSynced.getCode());
    }

    // ==================================================================
    // 五、契约自描述：端点可被客户端读到"13 条枚举 + 12+1 分支 + N 是探测值"
    // ==================================================================

    @Test
    @DisplayName("契约自描述里不得出现『留存窗口天数』（描述特征而非禁掉所有计数）")
    void contract_description_exposes_no_concrete_day_count() {
        var m = service.describeContract();
        assertNotNull(m.get("retention_window_days"), "描述里应说明 retention_window_days 的来源");
        assertTrue(String.valueOf(m.get("retention_window_days")).contains("TBD"),
                "描述里应写明未探测到时的输出是 TBD");

        // 逐键断言：判据 ="疑似窗口天数的键" ∧ "其取值是个数字"。
        // 🛑 两个都不可少：
        //   ① 只看"值是数字"会误杀 daily_branch_count=12 / cursor_branch_count=1
        //      —— 它们是"12 按日 + 1 游标"这一结构事实，与留存窗口无关；
        //   ② 只看"键名含 retention/day"会误杀 retention_window_days 本身
        //      —— 那是契约规定的出参字段名，它的取值是说明文字（含 TBD），不是天数。
        //   真正要禁的是"把天数下发给客户端"，即两者的交集。
        for (var e : m.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            boolean windowish = (k.contains("retention") || k.contains("window") || k.contains("回溯"))
                    && (k.contains("day") || k.contains("days") || k.contains("天数"));
            if (windowish && e.getValue() instanceof Number) {
                throw new AssertionError("契约自描述把窗口天数下发了: " + e.getKey() + "=" + e.getValue()
                        + " —— 一旦下发天数，它就会变成客户端眼中的 N 常量");
            }
        }
        // 反向自证：retention_window_days 这条【正当】字段必须仍在（否则上面的过滤可能把整条都跳过了）
        assertTrue(m.containsKey("retention_window_days"),
                "描述里应保留 retention_window_days 这条契约字段（其取值为说明文字，非天数）");
        assertEquals(13, ((List<?>) m.get("history_type_values")).size(),
                "描述应列出全部 13 条 HistoryType");
        // 明确反证：结构计数（12 / 1）必须仍在，且它们是数字 —— 证明上面的判据不误杀结构事实。
        // 注意 12L / 1L：HistoryType.dailyCount()/cursorCount() 返回 long，
        // 故 map 里存的是 Long —— assertEquals(12, <Long>) 会走 Object 重载而 Integer≠Long 失败。
        assertEquals(12L, m.get("daily_branch_count"), "按日分支数应如实报 12");
        assertEquals(1L, m.get("cursor_branch_count"), "游标分支数应如实报 1");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 定位 {@code app/band} 主源码目录（从 surefire 的 cwd 逐级上溯，不依赖硬编码盘符）。 */
    private static Path resolveBandMainDir() {
        Path anchor = Path.of("dy-app/src/main/java/com/diaoyuanyun/dy/app/band");
        for (Path cur = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
             cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(anchor);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "未找到 app/band 主源码目录（期望 <root>/" + anchor + "）；"
                        + "当前工作目录=" + System.getProperty("user.dir"));
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 去掉注释与字符串字面量，只留"会被编译的代码"。
     *
     * <h2>🛑 块注释必须用 DOTALL，否则扫描会自己制造违规</h2>
     * 本文件（以及被扫的服务类）的 Javadoc 里<b>逐字写着</b>
     * 「不得内置 {@code N = 7} / {@code N = 14} 等任何常量」——
     * 那是<b>在描述禁止事项</b>。若块注释匹配用非 DOTALL 的 {@code /\*.*?\*\//}，
     * {@code .} 不跨行 ⇒ 多行 Javadoc 匹配不上 ⇒ 注释文本留在"代码"里 ⇒
     * 上面那句禁止事项本身被当成违规命中（{@code N = 7}）。
     * 于是<b>扫描器会把"写明禁止"判成"违反禁止"</b>，而修它的自然反应是删掉注释里的例子 ——
     * 那等于把文档改成绕开扫描器，规则的可信度就没了。
     * 故此处用 {@link Pattern#DOTALL}，并配套做一次"注释确实被剥掉"的前置自证。
     */
    private static String stripCommentsAndStrings(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        s = s.replaceAll("'(\\\\.|[^'\\\\])*'", "''");
        return s;
    }
}