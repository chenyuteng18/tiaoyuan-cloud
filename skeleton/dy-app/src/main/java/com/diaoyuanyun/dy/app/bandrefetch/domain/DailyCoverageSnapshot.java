package com.diaoyuanyun.dy.app.bandrefetch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <b>逐日覆盖读侧快照</b> —— 从 {@code band_daily_coverage} 读回来的<b>一整行</b>，
 * 与 {@link DailyCoverageRecord}（写侧意图）互为两个方向。
 *
 * <h2>🛑 为什么必须是一个"整行"对象</h2>
 * 本表的读侧问题比 probe 侧更尖锐：A3 的取数要同时用<b>四列</b>才能回答
 * "这一天算不算有效佩戴天"（{@code coverage_flag} / {@code gap_reason} /
 * {@code is_wear} / {@code effective_wear_minutes}）。
 * 若做成四个标量读法，不但每个都有 null 歧义，
 * 还会出现<b>四次读取之间的时间窗</b> —— 而这四列在 upsert 里是<b>原子刷新</b>的
 * （V22 的 (7) 一条语句更新它们全部）。
 * 分开读会让取数逻辑读到"一半新、一半旧"的组合，而那组合在库里<b>从未存在过</b>。
 *
 * <h2>🛑 它是"客户被扣分的依据"，故字段只读且不提供任何 setter / with</h2>
 * {@link GapReason#NOT_WORN} 是唯一可扣分的一类，而这一行就是它的载体。
 * 一份可以被"顺手改一下"的扣分依据不是依据 —— 它无法回答
 * "这一天是按哪次同步、哪个 N 判出来的"。
 * 本 record 的所有字段都是 {@code final}（Java record 的固有性质），
 * 且<b>刻意不提供 {@code withXxx} 一类的派生方法</b>：需要新值就构造新对象，
 * 让"改动扣分依据"这件事在代码里显眼。
 *
 * <h2>🛑 为什么它【不】做形态校验（与 {@link DailyCoverageRecord} 的关键差别）</h2>
 * 写侧做校验（{@link DailyCoverageRecord} 在构造期阻断"技术性配 not_worn"），
 * 因为那时"拒绝一次非法写入"是<b>正确且可行</b>的。
 * <p>读侧<b>不能</b>做校验：库里可能存在<b>在本门禁上线之前</b>写入的行
 * （V5 建表到 V22 之间，任何直接 SQL 都能写进那个非法组合），
 * 也可能是一次 RLS 失效期间被写进来的。此时"读回来就抛错"会让
 * <b>已经躺在库里的坏数据变得无法被看见</b> —— 于是排障、举证、数据修复
 * 全都失去了入口，而本域尤其不能这样：
 * <b>"客户被错扣的那一天"正是最需要被查出来的东西</b>。
 * <p>⇒ 本 record <b>原样承载</b>库里的形态，并额外提供
 * {@link #violatesNotWornGate()} 让调用方<b>能主动检查</b>它 ——
 * 即把"读侧不能拦"与"读侧要能报"两件事分开。
 * <p>🛑 枚举解析<b>仍然会抛</b>（见 {@link #gapReasonOrNull()} /
 * {@link #isWearOrNull()}）：与 {@code SyncProbeSnapshot} 同一条边界 ——
 * "落库字面量不在登记词汇内"意味着代码的词汇与库已经分叉，属必须立刻被看见的事实。
 *
 * @param tenantId          该行所属租户（🛑 读回它使"我读到的到底是谁的行"可被断言）
 * @param coverageId        覆盖记录主键
 * @param deviceId          手环
 * @param customerId        归属客户（行级 scope 的承载者）
 * @param date              业务日
 * @param coverageFlag      该日是否有数据（可空 —— 🛑 空 = 缺失，<b>严禁补 0</b>）
 * @param gapReasonRaw      {@code gap_reason} 的<b>库内原文</b>（可空）；
 *                          经 {@link #gapReasonOrNull()} 解析
 * @param isWearRaw         {@code is_wear} 的<b>库内原文</b>（可空）；
 *                          经 {@link #isWearOrNull()} 解析
 * @param wearMinutes       当日佩戴时长（可空）
 * @param effectiveWearMinutes 剔除合规摘除后的有效佩戴时长（可空）
 * @param nAtThatTime       该日补拉时使用的 N（可空 —— 🛑 空 = "登记时未记 N"，
 *                          <b>不得读成 0</b>）
 * @param sourceSyncLogId   由哪次同步回捞（可空）
 * @param createdAt         建档时刻
 * @param createdBy         建档者标识（库层回落 {@code band-refetch}）
 */
public record DailyCoverageSnapshot(
        String tenantId,
        UUID coverageId,
        UUID deviceId,
        UUID customerId,
        LocalDate date,
        Boolean coverageFlag,
        String gapReasonRaw,
        Integer isWearRaw,
        Integer wearMinutes,
        Integer effectiveWearMinutes,
        Integer nAtThatTime,
        UUID sourceSyncLogId,
        Instant createdAt,
        String createdBy) {

    /** 解析 {@code gap_reason}；{@code null} = 该日未判定原因（🛑 与 {@link GapReason#UNKNOWN} 不是一回事）。 */
    public GapReason gapReasonOrNull() {
        return GapReason.parseNullable(gapReasonRaw);
    }

    /** 解析 {@code is_wear}；{@code null} = 设备未给出该日的佩戴状态（🛑 与"技术性缺失"不是一回事）。 */
    public WearState isWearOrNull() {
        return WearState.parseNullable(isWearRaw);
    }

    /**
     * 🛑 <b>这一行（读回来之后）是否违反核心门禁</b> —— {@code not_worn} 配技术性 {@code is_wear}。
     *
     * <h2>为什么读侧需要这个方法（写侧明明已经拦过了）</h2>
     * 三条理由，每条都指向"库里可能已经有违反的行"：
     * <ol>
     *   <li><b>门禁上线之前写入的行</b>：{@code band_daily_coverage} 由 V5 建表，
     *       而本门禁由 V22 才落到写入路径上。中间任何直接 SQL /
     *       更早的服务实现都能写进那个组合；</li>
     *   <li><b>绕过函数直接写 SQL</b>：库层的这条门禁<b>刻意不是表约束</b>
     *       （见 {@link DailyCoverageRecord} 类注释：写成表约束会让
     *       {@code RlsV5EntityIsolationTest} 的空值探针撞上一条 23514，
     *       产生归因完全错误的红）⇒ 故它不是"库层不可能被违反"的那一类；</li>
     *   <li><b>数据修复与举证</b>：本域的坏数据形态是"<b>客户被错扣了一天</b>"，
     *       而它<b>不会报错、不会被任何下游抓住</b>。要把它找出来，
     *       唯一的入口就是有人<b>主动扫一遍</b>。</li>
     * </ol>
     * <p>⇒ 故这个方法的存在意义是：<b>把"读侧不能拦"与"读侧要能报"分开</b>。
     * record 自己不抛（读侧不能拦，见类注释），但它<b>提供判据</b>，
     * 让门禁、数据核对脚本、修复工具都能用它扫库。
     *
     * <p>🛑 判据与写侧 {@link DailyCoverageRecord} 的构造器<b>同一句话</b>
     * （{@link GapReason#meansCustomerDidNotWear()} ∧
     * {@link WearState#contradictsNotWorn()}），不另写一遍合取式 ——
     * 两处各写一次会让"哪一侧是判据"这件事分叉，
     * 而分叉的表现是"写侧拒了、读侧不报"或反之，都不报错。
     */
    public boolean violatesNotWornGate() {
        GapReason g = gapReasonOrNull();
        WearState w = isWearOrNull();
        return g != null && w != null
                && g.meansCustomerDidNotWear() && w.contradictsNotWorn();
    }

    /**
     * 🛑 <b>这一行按 A3 口径是否"可扣分"</b> ——
     * 与 {@code DailyCoverageRecord#chargeableByGapReason()} <b>同一判据</b>。
     *
     * <p>🛑 它<b>先查</b> {@link #violatesNotWornGate()}，因为一行同时"可扣分"且
     * "违反门禁"的行，其可扣分性是<b>不可信的</b>：
     * 它的可扣分来自一个已经失效的判定（技术性缺失被记成了客户没戴）。
     * 直接返回 {@code true} 会让核对脚本把这类行<b>算进正常的可扣分统计</b>，
     * 于是它们既被当成合法数据、又没被认作缺陷。
     * <p>⇒ 返回 {@code false} 的语义是"<b>它不能按可扣分处理</b>"，
     * 而它到底属于"正常的不可扣分"还是"需要修复的坏行"，
     * 由 {@link #violatesNotWornGate()} 单独回答 —— 两个问题分开，各自有名字。
     */
    public boolean chargeableByGapReason() {
        if (violatesNotWornGate()) {
            return false;
        }
        GapReason g = gapReasonOrNull();
        return g != null && g.chargeable();
    }

    /**
     * 🛑 这一行是否<b>有数据</b>（{@code coverage_flag = TRUE}）——
     * 用于与 {@link #gapReasonRaw()} 交叉核对"有数据 ∧ 有缺口原因"这条自洽纪律。
     *
     * <p>它读侧也<b>不</b>抛（理由同 {@link #violatesNotWornGate()}），只提供判据；
     * 但这条纪律与 {@code not_worn} 那条有一处关键差别：
     * 它的违反<b>不直接伤害客户</b>（只是让 A3 取数时两条互斥分支同时命中），
     * 故不需要一个 {@code violatesXxxGate()} 那样显眼的命名。
     */
    public boolean contradictoryFlagAndReason() {
        return Boolean.TRUE.equals(coverageFlag) && gapReasonOrNull() != null;
    }
}