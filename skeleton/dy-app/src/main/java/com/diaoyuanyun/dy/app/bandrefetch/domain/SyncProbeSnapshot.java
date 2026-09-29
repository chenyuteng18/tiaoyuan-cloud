package com.diaoyuanyun.dy.app.bandrefetch.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>留存探针读侧快照</b> —— 从 {@code band_sync_probe} 读回来的<b>一整行</b>，
 * 与 {@link SyncProbeRecord}（写侧意图）互为两个方向。
 *
 * <h2>🛑 为什么必须是一个"整行"对象，而不是几个各自返回标量的读法</h2>
 * 本域有一个读侧问题，若做成"返回 {@code Integer} 的 N"这一类标量读法，
 * 会立刻出现<b>null 的歧义</b>：
 * <pre>
 *   retentionWindowDaysOf(tenantId, probeId) → null
 *     到底是"这一行不存在 / 我看不见"，还是"这一行存在、而它还没探到 N"？
 * </pre>
 * 这两个答案的<b>后果完全不同</b>：
 * <ul>
 *   <li>前者（不存在）意味着这条 N 的留痕压根没有 ⇒ 应排查写入方；</li>
 *   <li>后者（存在但未探到）意味着<b>有人登记了一条"我还没取证"的探针</b> ——
 *       这是 §2.8.7 明确允许的状态（「未取证前一律按『未缓解』记账」），
 *       但它<b>不能</b>驱动补拉。</li>
 * </ul>
 * 把它们合并成同一个 {@code null}，会造出本仓反复要防的形态 ——
 * <b>一个无法被追问的返回值</b>。
 * <p>⇒ 本 record 把"这一行在不在"编码成<b>对象本身的 null</b>
 * （{@code snapshotOf(...) == null} ⇔ 在本租户内看不见这一行），
 * 而把"这一行里的某个字段为空"编码成<b>字段的 null</b>。
 *
 * <h2>🛑 它是"N 的证据"，故字段只读且不提供任何 setter / with</h2>
 * §2.8.7③ 逐字要求「N 随探测刷新，<b>须留痕</b>」。
 * 一份可以被"顺手改一下"的留痕不是留痕 —— 它无法回答
 * "这个 N 是在<b>什么时点</b>、用<b>什么来源</b>探到的"。
 * 本 record 的所有字段都是 {@code final}（Java record 的固有性质），
 * 且<b>刻意不提供 {@code withXxx} 一类的派生方法</b> —— 需要新值就构造新对象。
 *
 * <h2>🛑 为什么它【不】做形态校验（与 {@link SyncProbeRecord} 的关键差别）</h2>
 * 写侧做校验（{@link SyncProbeRecord} 在构造期阻断非法意图），
 * 因为那时"拒绝一次非法写入"是<b>正确且可行</b>的。
 * <p>读侧<b>不能</b>做校验：库里的历史行可能是在规则收紧之前写入的，
 * 也可能是一次 RLS 失效期间被写进来的。此时"读回来就抛错"会让
 * <b>已经躺在库里的坏数据变得无法被看见</b> —— 于是排障、举证、数据修复
 * 全都失去了入口。这是本仓在 {@code Json#toMapLenient} 与
 * {@code CaseArchiveSnapshot} 的注释里已记录过的同一族纪律：
 * 「宽松读只供诊断 / 自描述路径」—— 本 record 正是那种路径。
 * <p>🛑 但枚举解析<b>仍然会抛</b>（见 {@link #historyType()} 与
 * {@link #probeSourceOrNull()}）：理由是"形态错"与"值不合规"是两件事 ——
 * 前者是<b>落库字面量不在登记词汇内</b>（那意味着库里的取值与代码的词汇
 * <b>已经分叉</b>，是一个必须立刻被看见的事实），
 * 后者是<b>值读出来了但不理想</b>（可以承载，供人判断）。
 * 与本仓 {@code CaseArchiveSnapshot} 对 JSON 的处置逐字同源。
 *
 * @param tenantId      该行所属租户（🛑 读回它使"我读到的到底是谁的行"可被断言）
 * @param probeId       探针主键
 * @param deviceId      被探测的手环
 * @param historyTypeRaw {@code history_type} 的<b>库内原文</b>；
 *                       经 {@link #historyType()} 解析为枚举（未登记即抛）
 * @param validDatesJson {@code getValidHistoryDates} 的有效日期数组原文（可空）
 * @param retentionWindowDays N 推定值（可空 —— 🛑 空 = "尚未探测到"，
 *                       <b>不得读成 0</b>：0 是一个<b>有效的</b>窗口值）
 * @param probeSourceRaw {@code probe_source} 的<b>库内原文</b>（可空）；
 *                       经 {@link #probeSourceOrNull()} 解析
 * @param probedAt      探测时点（库列为 NOT NULL DEFAULT now()）
 * @param isTest        测试/联调数据剔除标记（库列为 NOT NULL DEFAULT false）
 * @param createdAt     建档时刻
 * @param createdBy     建档者标识（库层回落 {@code band-refetch}）
 */
public record SyncProbeSnapshot(
        String tenantId,
        UUID probeId,
        UUID deviceId,
        String historyTypeRaw,
        String validDatesJson,
        Integer retentionWindowDays,
        String probeSourceRaw,
        Instant probedAt,
        boolean isTest,
        Instant createdAt,
        String createdBy) {

    /**
     * 解析 {@code history_type} 为封闭枚举；<b>未登记的字面量即抛</b>。
     *
     * <p>🛑 读侧解析为何<b>不</b>走"宽松"（与类注释里那条纪律的边界）：
     * 本仓对 JSON 的处置是"坏 JSON 必须报"（读不出来），
     * 而"落库字面量不在登记词汇内"属<b>同一类</b> —— 它不是"值不理想"，
     * 而是"这一行所属的语义空间已经和代码分叉了"。
     * 放它过去，会让 {@code min(各 history_type 的 retention_window_days)}
     * 这条消费规则<b>静默少算一路</b>（见 {@link HistoryMetric#parse} 的注释）。
     */
    public HistoryMetric historyType() {
        return HistoryMetric.parse(historyTypeRaw);
    }

    /** 解析 {@code probe_source}；{@code null} = 该列未标注来源（🛑 与"保守假设"不是一回事）。 */
    public ProbeSource probeSourceOrNull() {
        return ProbeSource.parseNullable(probeSourceRaw);
    }

    /**
     * 🛑 <b>这条 N 是否可作 A3 取数依据</b> ——
     * 即"有值 <b>且</b> 来源标明为运行时实测"。
     *
     * <p>转发到 {@link SyncProbeRecord#nIsUnverified()} 的同一判据（<b>不</b>在两处各判一次）：
     * 写侧的那个方法问的是"我打算登的这条取证了吗"，
     * 本方法问的是"我读到的这条能直接用吗" —— 同一个问题的两个时点。
     * 若在这里重写一遍判据，两处就会各自漂移，
     * 而漂移的后果是<b>取数侧用了一条写侧认为未取证的 N</b>，不报错。
     */
    public boolean nIsVerified() {
        return retentionWindowDays != null
                && !isTest
                && ProbeSource.RUNTIME_PROBE == probeSourceOrNull();
    }

    /**
     * 🛑 这条探针是否是<b>测试 / 联调数据</b>（{@code is_test = TRUE}）。
     *
     * <p>V5 第 925 行的注释逐字：「测试/联调数据剔除标记（<b>可正当清洗</b>）」——
     * 即这一列的存在意义是"让这批数据可以被识别出来并排除"。
     * <p>🛑 故取数侧<b>必须</b>过这一关：{@code isTest = TRUE} 的探针
     * 若被当成真实 N 使用，补拉窗口会被一批"联调时随手写的天数"决定。
     * 本方法把这件事命名出来，使调用点读起来是
     * {@code if (!probe.nIsVerified())} 而不是一句需要解释的合取式。
     */
    public boolean isTestData() {
        return isTest;
    }
}