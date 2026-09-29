package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>逐日覆盖原语的返回值口径</b> —— 与 {@code V22__band_refetch_provisioning.sql}
 * 里 {@code register_daily_coverage()} 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑🛑 为什么这里是【两态 + 一个"更新"态】，而不是 probe 侧那种纯两态</h2>
 * 本原语的幂等形态是 <b>upsert</b>（键 {@code (device_id, date)}），
 * 依据 = §2.8.7③「幂等」行逐字：「上报前 upsert（服务端权威）→
 * 重复拉取 = <b>覆盖、非追加</b>」。
 * ⇒ 于是"这一次调用做了什么"有<b>三种</b>可能：
 * <pre>
 *   CREATED  —— 该 (device_id, date) 本来没有行，本次插入
 *   UPDATED  —— 本来有行且它属于同一客户，本次覆盖了当日观测事实
 *   （第三种：本来有行但【不属于同一客户 / 不属于本租户】—— 🛑 那是异常，不是一态）
 * </pre>
 *
 * <h2>🛑 为什么 {@code CREATED} 与 {@code UPDATED} 必须分开（不能合并成"OK"）</h2>
 * 两者的<b>业务含义完全不同</b>：
 * <ul>
 *   <li>{@code CREATED} = 这一天第一次被覆盖（新的一天进入 A3 的记账面）；</li>
 *   <li>{@code UPDATED} = 这一天被<b>重拉并刷新</b>（"重复拉取 = 覆盖"这条规则的落点）——
 *       它意味着"设备后来给出了更完整的数据"或"上一次拉取不完整"。</li>
 * </ul>
 * 若合并成一态，"补拉重跑了多少次"与"覆盖了多少个新日子"在审计里就<b>无法区分</b>，
 * 而 §2.8.7③「节流」行明确关心重复发起（「须时间窗节流（建议 5 分钟内不重复发起）」）——
 * 一个分不出"重复"的返回值让那条节流规则的效果不可观测。
 *
 * <h2>🛑🛑 为什么 {@code UPDATED} 里要强调"覆盖的是【当日观测事实】"</h2>
 * V22 的 (7) 逐条列出了<b>刻意不更新</b>的列：
 * <pre>
 *   coverage_id                        —— 首次插入那行的身份（改它等于换行）
 *   tenant_id                          —— 恒等于本函数据以建立的上下文
 *   device_id / customer_id / date     —— upsert 键与归属
 *   created_at / created_by            —— 「谁在什么时候建的」是不可变更的既成事实
 * </pre>
 * 更新的只有：{@code coverage_flag / gap_reason / is_wear / wear_minutes /
 * effective_wear_minutes / n_at_that_time / source_sync_log_id}
 * —— 即"<b>当日观测到什么</b>"。
 * <p>🛑 这个区分是本枚举最该被读到的地方：若覆盖能改 {@code customer_id}，
 * 一次补拉就能把一个客户的观测日<b>悄悄转记</b>到另一个客户名下 ——
 * 而 A3 是"唯一可扣分"的指标，这种转记<b>既可能冤枉甲，也可能放过乙</b>。
 * V22 的 {@code ON CONFLICT ... DO UPDATE ... WHERE c.tenant_id = p_tenant_id
 * AND c.customer_id = p_customer_id} 把这一条作为<b>判定与写入的同一条语句</b>
 * （无读-写间隙）。
 *
 * <h2>🛑 跨租户 / 跨客户撞号为什么是异常而不是一个返回值</h2>
 * {@code uq_daily_coverage_device_date}（实测逐字 {@code UNIQUE (device_id, date)}）
 * <b>不含 {@code tenant_id}</b> ⇒ 跨租户撞号必然可能发生。
 * <p>若对这两种形态返回 {@code UPDATED}：
 * <ul>
 *   <li>跨租户：本租户这一天的覆盖<b>静默缺失</b>（A3 分母少一天，<b>不报错</b>）；</li>
 *   <li>跨客户：一个客户的观测日被记到另一个客户头上（同上，<b>不报错</b>）。</li>
 * </ul>
 * ⇒ 本枚举<b>刻意没有</b> {@code OWNED_BY_OTHER_TENANT} / {@code OWNED_BY_OTHER_CUSTOMER}
 * 两态 —— 一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"。
 * 这里用<b>缺失</b>来表达"这个情形不该被继续"。
 * <p>⚠️ 注意 {@code V22} 对这两种形态的归因是<b>分开的</b>（两类不同的 RAISE 消息），
 * 因为"去哪个方向排查"完全不同：租户上下文不对 vs 设备被重新绑定。
 * 但它们<b>共用同一个"没有返回值"的处置</b> —— 归因分开、结论同一个。
 */
public enum DailyCoverageOutcome {

    /** 本次调用建出了这一行（这一天首次进入覆盖台账）。 */
    CREATED("逐日覆盖已登记（新建）"),

    /**
     * 覆盖命中：该 {@code (device_id, date)} 已在本租户、<b>同一客户</b>名下，
     * 本次<b>刷新了当日观测事实</b>。
     *
     * <p>🛑 "刷新"是本态的<b>核心语义</b>，不是修饰：§2.8.7③「幂等」行逐字
     * 「重复拉取 = 覆盖、非追加」，故本态意味着<b>数据被改写了</b> ——
     * 与 {@code SyncProbeOutcome.ALREADY_EXISTS}（"未改动任何数据"）
     * <b>方向相反</b>。两者虽然都叫"幂等命中"，但在本仓里
     * {@link #mutatedData()} 的返回值不同，而这正是审计要区分的东西。
     */
    UPDATED("覆盖命中：该业务日已在本租户同一客户名下，本次刷新了当日观测事实");

    private final String zh;

    DailyCoverageOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_daily_coverage()} 的取值范围（本迁移的写原语之一）。 */
    public static final List<DailyCoverageOutcome> REGISTER_OUTCOMES = List.of(CREATED, UPDATED);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>🛑 本域的漂移后果比 probe 侧更重</h2>
     * 若发生口径漂移、且新值被落到某个默认态，最可能的漂移方向是
     * "跨租户/跨客户撞号被改成了一个返回值" —— 那两个形态一旦返回
     * {@link #UPDATED}，后果分别是"本租户这天的覆盖静默缺失"
     * 与"一个客户的观测日被记到另一个客户头上"。
     * 前者让 A3 分母少一天（把客户的分<b>算高</b>），后者让甲的缺失记到乙头上
     * （把乙的分<b>算低</b>）—— 两个方向都错，且都不报错。
     */
    public static DailyCoverageOutcome fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "逐日覆盖原语返回了空值 —— 取值范围已冻结为 " + REGISTER_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (DailyCoverageOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "逐日覆盖原语返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + REGISTER_OUTCOMES
                        + "。🛑 特别注意：跨租户撞号与跨客户撞号在设计上都是【异常】而不是态 —— "
                        + "若某个第三态出现在这里，说明库函数把它们改成了返回值。"
                        + "跨租户会让本租户这一天的覆盖静默缺失（A3 分母少一天，把客户的分算高）；"
                        + "跨客户会让一个客户的观测日记到另一个客户头上（把乙的分算低）。"
                        + "两个方向都错，且都不会报错。新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b> —— 🛑 本域<b>两态都改动了</b>（upsert vs do-nothing 的关键差别）。 */
    public boolean mutatedData() {
        return true;
    }

    /** 是否属于"这一天首次进入台账"（供审计区分"新覆盖的一天"与"重拉刷新"）。 */
    public boolean firstTime() {
        return this == CREATED;
    }

    /**
     * 是否属于"重拉刷新"（供 §2.8.7③「节流」行的效果观测：
     * "重复发起"在这条链上的可观测面就是本态的计数）。
     */
    public boolean refreshed() {
        return this == UPDATED;
    }
}