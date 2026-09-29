package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>留存探针原语的返回值口径</b> —— 与 {@code V22__band_refetch_provisioning.sql}
 * 里 {@code register_sync_probe()} 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑 为什么必须枚举化（而不是透传字符串）</h2>
 * 与 {@code DeviceOutcome} / {@code CaseArchiveOutcome} / {@code AgreementOutcome} 逐字同款：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"，而所有分支判断静默走 {@code else}。
 *       {@link #fromDb(String)} 的处置是<b>未登记的值即抛错</b>；</li>
 *   <li><b>{@link #ALREADY_EXISTS} 不是失败</b>：它是幂等命中（没有重复建行）。
 *       若透传字符串，调用方容易把它和异常混为一谈，
 *       于是"有人重放同一次探测"这件正常的事会被当成故障上报。</li>
 * </ol>
 *
 * <h2>🛑🛑 为什么这里是【两态】，且两态之间<b>没有</b>"已存在但我看不见"这一态</h2>
 * 跨租户撞号在 V22 里是 <b>RAISE</b>（{@code P0001}），<b>不是</b>返回值。
 * 本枚举<b>刻意缺少</b> {@code OWNED_BY_OTHER_TENANT} 一态：
 * 一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"。
 * <p>🛑 <b>本域的后果比 device / scale / archive / agreement 四批都更直接</b>
 * （这一句是本枚举最该被读到的地方）：
 * <pre>
 *   若 register_sync_probe() 对"probe_id 被别的租户占用"返回 ALREADY_EXISTS：
 *     → 调用方以为「探针已登记」
 *     → 而本租户内【没有任何 N 的留痕】
 *     → 补拉按「未探到 N」运行
 *     → 它会去拉一批设备根本不会返回的日期
 *     → 那些日期被记成缺口
 *     → 缺口里有一类是 not_worn，而它是【全 7 值里唯一可扣分的】
 *     → 客户被扣分
 * </pre>
 * ⇒ 前四批的跨租户撞号后果都落在<b>系统</b>上（某条链失败 / 某个指标为 0 / 某份证据不存在），
 * 而本批的后果落在<b>客户</b>身上。这不是程度差异，是<b>类别差异</b> ——
 * 故 {@code V22} 的 (6) ③ 是本迁移不可让渡的红线。
 *
 * <h2>🛑 为什么"重放"这件事在本域【必须】被留痕（与 upsert 侧不同）</h2>
 * 本原语是<b>追加</b>语义（同一 {@code (device_id, history_type)} 可以有多条探针，
 * 见 {@link SyncProbeRecord} 类注释）。故 {@code ALREADY_EXISTS} 在语义上
 * 意味着"<b>有人拿了同一个 probe_id 又登了一次</b>" —— 那多半是
 * 上游工具重放了一次同一批探测，也可能是误传了 id。
 * <p>两者都值得在事后被看见：前者说明"这批探针被跑了两次"，
 * 后者说明"有一份探针数据被挂到了错的 id 上"。故 {@code BandRefetchService}
 * 对两态<b>都</b>写审计（见该类注释）。
 */
public enum SyncProbeOutcome {

    /** 本次调用建出了这一行（真的落了一条 N 的留痕）。 */
    CREATED("探针已登记（新建）"),

    /**
     * 幂等命中：这一行<b>已在本租户名下</b>（重放同一次探测，未改动任何数据）。
     *
     * <p>🛑 "未改动任何数据"是<b>可被断言</b>的，不是措辞修饰：探针是<b>证据</b>
     * （"这个 N 是在什么时点、用什么来源探到的"），而"重放探测"退化成
     * "用新参数改写既有证据"会让 {@code probed_at} 与 {@code retention_window_days}
     * 的历史轨迹<b>被抹掉</b> —— 而 §2.8.7③ 逐字要求「N 随探测刷新，<b>须留痕</b>」。
     * <p>V22 的 (6) 用 {@code ON CONFLICT (probe_id) DO NOTHING} 保证这一点
     * （<b>不是</b> {@code DO UPDATE}），且自证 (e2) 用"重放后
     * {@code retention_window_days} 必须还是第一次那个值"来钉住它。
     *
     * <p>🛑 注意措辞里"<b>本租户</b>"三个字是必要的：
     * 若该 {@code probe_id} 属于别的租户，函数会 {@code RAISE} 而<b>不</b>返回本态
     * （见类注释「为什么没有 OWNED_BY_OTHER_TENANT 这一态」）。
     * 少了这三个字，读代码的人无法从本态本身判断"已存在"是相对于谁的。
     */
    ALREADY_EXISTS("幂等命中：该探针已在本租户名下（重放，未改动数据）");

    private final String zh;

    SyncProbeOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_sync_probe()} 的取值范围（本迁移的写原语之一）。 */
    public static final List<SyncProbeOutcome> REGISTER_OUTCOMES = List.of(CREATED, ALREADY_EXISTS);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #ALREADY_EXISTS}（"没有写入"）被误当成 {@link #CREATED}（"写入成功"），
     * 于是调用方的审计会<b>记录一次不存在的探针登记</b>。
     * <p>在本域这个后果还要更重一层：一次"不存在的探针"被记成成功，
     * 意味着<b>补拉会按一个并不存在的 N 去决定回溯窗口</b> ——
     * 而那个窗口要么太大（制造假缺失、扣客户分），要么太小（覆盖不到应戴天）。
     * <p>这与 {@code CaseArchiveOutcome#fromDb} / {@code AgreementOutcome#fromDb}
     * 的处置逐字同款。
     *
     * <p>🛑 附带价值：若有人把 {@code register_sync_probe} 改成
     * "跨租户时返回某个第三态"，本方法会先红 —— 即"用缺失来表达不该继续"
     * 这条设计意图是被代码守住的，不只写在注释里。
     */
    public static SyncProbeOutcome fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "留存探针原语返回了空值 —— 取值范围已冻结为 " + REGISTER_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (SyncProbeOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "留存探针原语返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + REGISTER_OUTCOMES
                        + "。🛑 特别注意：跨租户撞号在设计上是【异常】而不是一态 —— "
                        + "若某个第三态出现在这里，说明库函数把它改成了返回值。"
                        + "那会让调用方以为『探针已登记』而本租户内没有任何 N 的留痕，"
                        + "于是补拉按『未探到 N』运行、去拉一批设备不会返回的日期，"
                        + "而那些日期会被记成缺口 —— 其中 not_worn 是唯一可扣分的一类，"
                        + "最终【代价落在客户身上】。新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED;
    }

    /** 是否属于幂等重放（数据未变，但"有人又登了一次探针"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_EXISTS;
    }
}