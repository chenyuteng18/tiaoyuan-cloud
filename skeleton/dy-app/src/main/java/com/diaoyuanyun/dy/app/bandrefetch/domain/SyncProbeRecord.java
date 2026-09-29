package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>留存窗口探针意图</b> —— 一次"记录某设备某一路历史的 N 推定值"的完整输入。
 *
 * <h2>🛑 它补的是什么：{@code band_sync_probe} 的"零写入方"为什么是功能缺口</h2>
 * §2.8.7③「起始日」行逐字给出补拉的起点公式：
 * <pre>
 *   start = max(last_synced_date + 1, today − N_retain)
 * </pre>
 * 而 {@code N_retain} 的唯一权威来源是 {@code getValidHistoryDates} 的<b>运行时探测</b>
 * （契约 §4.2 明定「代码 / 契约内<b>无硬编码 N</b>」）。在 A-3 之前，
 * {@code band_sync_probe} 在生产代码里零写入方、真库零行 ⇒
 * <b>{@code N_retain} 无处落库</b> ⇒ 上面那条公式的右端第二项<b>取不到值</b>。
 *
 * <h2>🛑🛑 本表的缺口表现是【第三种】—— 既不是错误码，也不是"静默"</h2>
 * <pre>
 *   B-10 band        → 23503（四表 device_id 外键）
 *   B-12 scale       → 422（C2 那道 scaleExists 预检）
 *   B-13 case_archive→ 静默（只在举证时暴露）
 *   A-1 agreement    → 指标恒为 0（G1 分子）
 *   A-3 本表          → 【一个错误的上界被采用】
 * </pre>
 * 🛑 这是本批最该被读到的一句：{@code band_sync_probe} 零行时，补拉<b>不会报错</b> ——
 * 它会退化成"按一个<b>缺失/默认</b>的窗口运行"。而 §2.8.7③ 逐字警告：
 * 「🔴 <b>不得回溯超过 {@code floor}</b>（超窗口日期设备不返回 → 只会制造<b>假缺失</b>）」。
 * ⇒ 后果不是"少做了一件事"，而是<b>系统会主动去拉一批设备根本不会返回的日期，
 * 然后把这些"设备没返回"记成缺口</b> —— 而缺口里有一类（{@code not_worn}）
 * 是<b>唯一可扣分的</b>。即：<b>本表的缺口会以"客户被扣分"的形式表现</b>。
 * 这比前三批的任何一种都更严重，因为它落在客户身上而非系统身上。
 *
 * <h2>🛑🛑 为什么 {@code probeId} 是【全局单列主键】而不是 {@code (tenant_id, probe_id)}</h2>
 * 实测 {@code band_sync_probe_pkey | PRIMARY KEY (probe_id)}（V5 第 899 行），
 * 表上<b>没有</b> {@code (tenant_id, probe_id)} 载体。
 * ⇒ 跨租户撞号<b>必然可能发生</b>，且 {@code ON CONFLICT (probe_id) DO NOTHING}
 * 对"别人占了这个 id"与"我自己已经有了"给出<b>同一个</b> {@code ROW_COUNT = 0}。
 * <p>若不显式判（见 {@link SyncProbeOutcome} 与 V22 的 (6) ③）：
 * 调用方会以为"探针已登记"，而本租户内<b>没有任何 N 的留痕</b> ⇒
 * 补拉按"未探到 N"运行 ⇒ 上一条说的"假缺失"路径被打开。
 *
 * <h2>🛑 为什么可以有【多条】探针记录同一 {@code (device_id, history_type)}（追加）</h2>
 * 本表<b>没有</b> {@code (device_id, history_type)} 唯一载体，这是刻意的：
 * §2.8.7③ 明示「N 随探测刷新，<b>须留痕</b>」，而 {@code N} 会随设备固件/厂商策略变化。
 * ⇒ 同一路历史在不同时间探到的 N 不同，<b>两条都要在</b>——
 * "当前有效的 N"是"最新的那一条"，而不是"唯一的那一条"。
 * <p>🛑 这与 {@link DailyCoverageRecord} 的 upsert 语义<b>刻意相反</b>：
 * 探针是<b>证据</b>（历史事实，不可覆盖），逐日覆盖是<b>当日观测事实</b>
 * （同一业务日可以因重拉而刷新）。把两者统一成同一种幂等形态，
 * 会让"N 的历史轨迹"或"当日的最终观测"其中之一被静默丢弃。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 不另写一份白名单正则。理由与 {@code DeviceRecord} / {@code CaseArchiveRecord} 逐字相同：
 * 两处口径会各自漂移，"哪个更严"取决于谁先跑。
 *
 * @param tenantId       探针所属租户（必填）
 * @param probeId        探针主键（必填）；🛑 它是<b>全局</b>主键，故"一条探针的一生只属于一个租户"
 * @param deviceId       被探测的手环（必填）；必须是<b>本租户内</b>存在的 {@code band}
 * @param historyType    历史指标（必填）；{@link HistoryMetric} 的 14 值之一
 * @param validDatesJson {@code getValidHistoryDates} 返回的有效日期数组（可空）；
 *                       给了值就必须是 JSON <b>数组</b>（V22 的 (5c) 会断言）
 * @param retentionDays  N 推定值 = 有效日期跨度（可空）；
 *                       🛑 空 = "尚未探测到" —— <b>不得填占位数冒充已取证</b>
 * @param probeSource    N 的来源（可空）；{@link ProbeSource} 的 3 值之一。
 *                       🛑 空与 {@link ProbeSource#CONSERVATIVE_ASSUMPTION} <b>不是一回事</b>
 * @param probedAt       探测时点（可空；库层回落 {@code now()}）
 * @param isTest         测试/联调数据剔除标记（可空；库层回落 {@code FALSE}）
 * @param createdBy      建档者标识（可空；库层回落 {@code band-refetch}）
 */
public record SyncProbeRecord(
        String tenantId,
        UUID probeId,
        UUID deviceId,
        HistoryMetric historyType,
        String validDatesJson,
        Integer retentionDays,
        ProbeSource probeSource,
        Instant probedAt,
        Boolean isTest,
        String createdBy) {

    /** {@code band_sync_probe.created_by} 的上限（{@code VARCHAR(128)}）。 */
    private static final int CREATED_BY_MAX = 128;

    /**
     * 规范构造器 —— 形态校验都在这里，使"一个非法的探针意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service：构造器是<b>唯一</b>的入口。
     * 放在 Service 里意味着"只要有人绕过 Service 直接 new 一个 record"
     * 就能拿到非法值 —— 而那正是这个 record 存在的意义
     * （它把"意图"变成一个<b>自校验</b>的值对象）。
     *
     * <p>🛑 三处刻意的<b>不校验</b>（都是决定，不是遗漏）：
     * <ol>
     *   <li><b>不校验 {@code validDatesJson} 是合法 JSON</b> —— 那需要一次解析，
     *       而解析失败该报 {@code 5001}（口径断裂）还是 {@code 1001}（入参错误）
     *       取决于"这段字符串从哪来"（客户端上报 vs 库里读回）。
     *       库层的 {@code ?::jsonb} 会给出精确的 {@code 22P02}，比 Java 侧猜一次更准；</li>
     *   <li><b>不校验 {@code retentionDays} 与 {@code validDatesJson} 的一致性</b> ——
     *       "跨度 = 数组长度"是<b>一次推导</b>，而 §2.8.7 明示 N 的取证属外部依赖
     *       （厂商文档 / 真机复核）。在这里硬算一次，等于<b>把一个未取证的推导
     *       当成事实</b>写进了写路径；</li>
     *   <li><b>不把 {@code isTest} 默认成 {@code false}</b> —— 默认值放在这里会让
     *       "我没表态"与"我明确说是生产数据"变成同一个值。库层 {@code coalesce(..., FALSE)}
     *       承担默认，而 {@code null} 从 Java 侧透传过去，让那一步<b>只有一个落点</b>。</li>
     * </ol>
     */
    public SyncProbeRecord {
        BandLedger.validateTenantId(tenantId);

        if (probeId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "留存探针登记：probe_id 必填 —— 它是 band_sync_probe 表主键，"
                            + "且是本原语幂等判定的唯一键。没有它，重放无法与『新建』区分。"
                            + "🛑 它还是【全局单列】主键（表上没有 (tenant_id, probe_id) 载体）——"
                            + "故跨租户撞号必然可能发生，函数必须显式判它");
        }
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "留存探针登记：device_id 必填 —— band_sync_probe.device_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, device_id) → band(tenant_id, band_id) 的一端。"
                            + "🛑 探测的对象就是这台设备 —— 没有它，『这个 N 适用于谁』不可答，"
                            + "而补拉起点公式 start = max(last_synced_date + 1, today − N) 里的 N "
                            + "会变成一台设备也落不到的悬空值");
        }
        if (historyType == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "留存探针登记：history_type 必填 —— 它是 band_sync_probe 的 CHECK 约束列，"
                            + "且 §2.8.7③ 的消费规则 N_retention = min(各 history_type 的 "
                            + "retention_window_days) 依赖它做分组键。"
                            + "🛑 不接受字符串入参：HistoryMetric 是 14 值封闭词汇，"
                            + "传字符串会让『拼错的指标名悄悄退出 min()』这条缺陷从库层"
                            + "前移到调用点之前 —— 那里更便宜、归因也更准");
        }
        if (retentionDays != null && retentionDays < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "留存探针登记：retention_window_days = " + retentionDays
                            + " 非法 —— 留存窗口天数不可为负。口径来源 = §2.8.7③「依据」行"
                            + "（bufferSize 是窗口容量，不是负偏移）；表上 CHECK 也写着 >= 0，"
                            + "此处显式先判一次以给出【可归因】的错误");
        }
        if (validDatesJson != null && validDatesJson.isBlank()) {
            // 空串与 null 在 DDL 里都合法，但语义上"我传了个空串"与"我没传"是两件事。
            // 归一成 null —— 与 V22 函数里 nullif(btrim(...), '') 的口径一致
            // （两处都要归一，否则会出现"Java 说是空串、库里是 NULL"的差异）。
            validDatesJson = null;
        }
        if (createdBy != null) {
            createdBy = createdBy.trim();
            if (createdBy.isEmpty()) {
                createdBy = null;
            } else if (createdBy.length() > CREATED_BY_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "留存探针登记：created_by 长度 " + createdBy.length()
                                + " 超过 band_sync_probe.created_by 的 VARCHAR(" + CREATED_BY_MAX + ")。"
                                + "🛑 在 Java 侧拦是因为库层的失败形态是一条 22001，"
                                + "它会以『数据截断』的面目出现在日志里，而不是『参数太长』");
            }
        }
    }

    /**
     * 便捷构造：只给必填四项（其余交给库层默认）。
     *
     * <p>省掉一串 {@code null} 实参 —— 本仓对这种"全是 null 的尾巴"有过教训：
     * 调用点上一串裸 {@code null} 无法自证"这一个 null 是哪一个字段"。
     */
    public static SyncProbeRecord of(String tenantId, UUID probeId, UUID deviceId,
                                     HistoryMetric historyType) {
        return new SyncProbeRecord(tenantId, probeId, deviceId, historyType,
                null, null, null, null, null, null);
    }

    /**
     * 🛑 <b>这条探针登到的是否是一个"未取证的 N"</b> —— 供调用方在开工前自问。
     *
     * <p>判据不是"有没有值"，而是"值可不可信"：{@code retentionDays == null}
     * 或来源不是 {@link ProbeSource#RUNTIME_PROBE} 都算未取证。
     * <p>🛑 它<b>不</b>阻止写入 —— {@code band_sync_probe} 的用途正是记录"我还没取证"。
     * 它阻止的是<b>把未取证的 N 当成取证的值去驱动补拉</b>。
     * §2.8.7⑥ 把 N 与 s 同列为"未取得前一律按『未缓解』记账"的外部依赖，
     * 且④逐字要求「<b>不得写成『补拉可让 A3 达标』</b>」——
     * 本方法就是那条纪律在类型上的落点。
     */
    public boolean nIsUnverified() {
        return retentionDays == null
                || probeSource == null
                || !probeSource.verifiable();
    }
}