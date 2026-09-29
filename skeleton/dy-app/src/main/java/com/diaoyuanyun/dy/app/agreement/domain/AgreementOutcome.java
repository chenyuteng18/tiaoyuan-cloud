package com.diaoyuanyun.dy.app.agreement.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>调理协议书离线签署原语的返回值口径</b> —— 与迁移
 * {@code V21__agreement_offline_signing_provisioning.sql} 里 {@code register_agreement()}
 * 的返回值<b>逐字对齐</b>（该函数的两态 {@code RETURN} 字面量被 V21 自证 (b1) 约束）。
 *
 * <h2>🛑 为什么必须枚举化（而不是透传字符串）</h2>
 * 与 {@code CaseArchiveOutcome} / {@code DeviceOutcome} / {@code BandBindingOutcome}
 * 逐字同款：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"，而所有分支判断静默走 {@code else}。
 *       {@link #fromDb(String)} 的处置是<b>未登记的值即抛错</b>。</li>
 *   <li><b>{@link #ALREADY_EXISTS} 不是失败</b>：它是幂等命中（没有重复建行）。
 *       离线补录场景下"同一份协议被登记两次"是<b>常态</b>（运维重跑、纸面协议补录），
 *       若透传字符串，调用方容易把它和异常混为一谈。</li>
 * </ol>
 *
 * <h2>🛑🛑 为什么这里是【两态】—— 与归档域同型，但少的那一态更贵</h2>
 * 本迁移<b>只有一条写原语</b>（{@code register_agreement}）⇒ 两态。
 * <p>🛑 少的是"撤销签署 / 确认签署"，而它是<b>有意不做</b>的，
 * 这是本迁移相对 V20 最需要说清的一处边界（见 V21 文件头
 * 「为什么没有'确认签署/撤销签署'原语」）：
 * 任何"把协议置为已签 / 未签"的第二通路，都<b>等于</b>开设一条
 * <b>绕过契约 H1（{@code receiveEsignCallback}）的旁路</b> ——
 * 而 H1 之所以被契约逐字禁止编码，恰恰因为
 * 「没有验签就无法确认签署是真的」。
 * 提供改写能力 = 把「未验签即可自证已签」搬进库里，只是换了个入口。
 *
 * <h2>🛑🛑 跨租户撞号为什么必须是异常而不是一个返回值（本域后果最直接）</h2>
 * {@code ON CONFLICT (agreement_id) DO NOTHING} 的推断目标是
 * {@code agreement_pkey}（<b>单列</b> {@code agreement_id}；实测真库该表
 * 没有 {@code (tenant_id, agreement_id)} 载体）。当该 {@code agreement_id}
 * 已被<b>别的租户</b>占用时：
 * <ul>
 *   <li>主键冲突照常发生 ⇒ 走 {@code DO NOTHING} ⇒ <b>{@code ROW_COUNT = 0}</b>；</li>
 *   <li>而本租户上下文里 {@code SELECT} 又<b>看不见</b>那一行（RLS 的 USING 挡在外面）。</li>
 * </ul>
 * ⇒ 若只按 {@code ROW_COUNT} 判定，函数会对一个手里<b>一行都没有</b>的租户
 * 返回 {@link #ALREADY_EXISTS}。调用方据此把客户推进 {@code AGREEMENT_SIGNED} ⇒
 * <b>一个客户被放进了一条它没有资格走的门</b>
 * （首次调理被放行、而协议从未被签署）。
 * <p>🛑 <b>为什么本域比归档域后果更直接</b>（这一句是本枚举最该被读到的地方）：
 * 归档域的误判在<b>举证时</b>才暴露（审计要看归档清单，而清单查不到）；
 * 而本域的签署登记<b>有一个下游消费者</b> ——
 * 实测 {@code CustomerGateGuard} 已登记 {@code PLAN_APPROVED → AGREEMENT_SIGNED}
 * 的跃迁。⇒ 一次错误返回值会<b>立刻</b>把客户推进已签态，
 * 而"未签不得首次调理"这条硬门禁就在那一刻失效。
 * <p>⇒ 故本枚举<b>刻意没有</b>一个 {@code OWNED_BY_OTHER_TENANT} 常量 ——
 * 一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"。
 * 这里用<b>缺失</b>来表达"这个情形不该被继续"。
 */
public enum AgreementOutcome {

    /** 本次调用建出了这一行（真落了一份协议签署登记）。 */
    CREATED("已签署（新建）"),

    /**
     * 幂等命中：这一行<b>已在本租户名下</b>（重放同一次签署登记，未改动任何数据）。
     *
     * <p>🛑 "未改动任何数据"是<b>可被断言</b>的，不是措辞修饰：
     * V21 自证 (e2) 用"重放后 4 方签署人与 rendered_hash 必须还是第一次那份"
     * 来钉住它（只查行数抓不住"重放顺手改写了既有证据"）。
     * 因为签署块与渲染稿是<b>证据快照</b>，而"重放签署登记"退化成
     * "用新参数改写既有证据"是本域最不能接受的形态之一。
     *
     * <p>🛑 注意措辞里"<b>本租户</b>"三个字是必要的：
     * 若该 {@code agreement_id} 属于别的租户，函数会 {@code RAISE} 而<b>不</b>返回本态
     * （见类注释「跨租户撞号为什么必须是异常」）。
     */
    ALREADY_EXISTS("幂等命中：该签署登记已在本租户名下（重放，未改动数据）");

    private final String zh;

    AgreementOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_agreement()} 的取值范围（本迁移唯一的写原语）。 */
    public static final List<AgreementOutcome> REGISTER_OUTCOMES = List.of(CREATED, ALREADY_EXISTS);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #ALREADY_EXISTS}（"没有写入"）被误当成 {@link #CREATED}（"写入成功"），
     * 于是调用方的审计会<b>记录一次不存在的签署</b>。
     * <p>在本域这个后果还要更重一层：一次"不存在的签署"被记成成功，
     * 意味着<b>一个客户被标记为已签而它没有签</b>，
     * 而下游 {@code CustomerGateGuard} 会据此放行首次调理。
     * <p>这与 {@code CaseArchiveOutcome#fromDb} 的处置逐字同款。
     *
     * <p>🛑 附带价值：若有人把 {@code register_agreement} 改成
     * "跨租户时返回某个第三态"，本方法会先红 —— 即"用缺失来表达不该继续"
     * 这条设计意图是被代码守住的，不只写在注释里。
     */
    public static AgreementOutcome fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "签署登记原语返回了空值 —— 取值范围已冻结为 " + REGISTER_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (AgreementOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "签署登记原语返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + REGISTER_OUTCOMES
                        + "。🛑 特别注意：跨租户撞号在设计上是【异常】而不是一态 —— "
                        + "若某个第三态出现在这里，说明库函数把它改成了返回值，"
                        + "那会让调用方把客户推进 AGREEMENT_SIGNED 而协议并不存在，"
                        + "且下游 CustomerGateGuard 会立刻放行首次调理"
                        + "（『未签不得首次调理』那条硬门禁在那一刻失效）。"
                        + "新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED;
    }

    /** 是否属于幂等重放（数据未变，但"有人又登记了一次签署"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_EXISTS;
    }
}