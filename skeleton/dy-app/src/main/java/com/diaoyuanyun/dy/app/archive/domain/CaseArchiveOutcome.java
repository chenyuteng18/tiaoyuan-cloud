package com.diaoyuanyun.dy.app.archive.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>结案归档原语的返回值口径</b> —— 与迁移
 * {@code V20__case_archive_provisioning.sql} 里 {@code register_case_archive()}
 * 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑 为什么必须枚举化（而不是透传字符串）</h2>
 * 与 {@code DeviceOutcome} / {@code BandBindingOutcome} 的处置逐字同款：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"，而所有分支判断静默走 {@code else}。
 *       {@link #fromDb(String)} 的处置是<b>未登记的值即抛错</b>。</li>
 *   <li><b>{@link #ALREADY_EXISTS} 不是失败</b>：它是幂等命中（没有重复建行）。
 *       若透传字符串，调用方容易把它和异常混为一谈，
 *       于是"有人在重放归档"这件正常的事会被当成故障上报。</li>
 * </ol>
 *
 * <h2>🛑🛑 为什么这里是【两态】，比 {@code DeviceOutcome} 还少</h2>
 * {@code DeviceOutcome} 是"两态 + 三态"（register + retire 两条原语），
 * 而本迁移<b>只有一条写原语</b>（{@code register_case_archive}）—— 两态。
 * <p>少的那条（对应 {@code retire_device} / {@code deprecate_scale} 的"归档/停用"）
 * 是<b>有意不做</b>的，三条理由（见 V20 文件头的「为什么没有取消归档原语」）：
 * <ol>
 *   <li><b>归档是终态</b>：PRD P0-14「四种结局全部强制归档」把它定义为<b>收口</b>，
 *       收口不可撤销；</li>
 *   <li><b>"未归档"这个语义不由本表承担</b>，而由 {@code refund.outcome} 承担
 *       （{@code RefundOutcome} 注释逐字：「归档 是结案状态位（{@code case_archive} 落地、
 *       归档后只读）；它不是第四种业务结局，是状态位」）。
 *       若这里提供"删除归档行"的原语，就等于给了<b>第二条</b>把工单从已归档
 *       改回未归档的路 —— 而那条路会绕过 refund 侧的状态机；</li>
 *   <li><b>{@code NOT_FOUND} 一态也没有</b>：那条读路径由 {@code latest_archive_of()}
 *       承担，而它用一个 <b>NULL</b> 表达"尚无归档"。一个"看不到"的返回值态在这里
 *       没有落脚处 —— 本原语是<b>写</b>原语，它不回答"有没有"。
 *       （🛑 这里的 NULL 与 {@code DeviceOutcome.NOT_FOUND} 不冲突：
 *        那边 NOT_FOUND 出现在 <b>retire（写）</b>原语里，因为归档一台不存在的设备
 *        是一次需要被回答的写请求；而本迁移的读原语返回 NULL 而不是枚举，
 *        因为"尚未归档"是一个正常状态而非一次失败的写。）</li>
 * </ol>
 *
 * <h2>🛑🛑 跨租户撞号为什么必须是异常而不是一个返回值</h2>
 * 这是本迁移相对 V17 形态的实质改动，与 V18 同族但<b>后果更隐蔽</b>：
 * <p>{@code ON CONFLICT (archive_id) DO NOTHING} 的推断目标是
 * {@code case_archive_pkey}（<b>单列</b> {@code archive_id}）。当该 {@code archive_id}
 * 已被<b>别的租户</b>占用时（实测真库：{@code case_archive_pkey | p | PRIMARY KEY (archive_id)}，
 * 表上<b>没有</b> {@code (tenant_id, archive_id)} 载体）：
 * <ul>
 *   <li>主键冲突照常发生 ⇒ 走 {@code DO NOTHING} ⇒ <b>{@code ROW_COUNT = 0}</b>；</li>
 *   <li>而本租户上下文里 {@code SELECT} 又<b>看不见</b>那一行（RLS 的 USING 挡在外面）。</li>
 * </ul>
 * ⇒ 若只按 {@code ROW_COUNT} 判定，函数会对一个手里<b>一行都没有</b>的租户
 * 返回 {@link #ALREADY_EXISTS}。调用方据此把 {@code refund.outcome} 置成 {@code '归档'} ⇒
 * <b>一张退款工单被标记为"已归档"，而它的归档档案不存在</b>。
 * <p>🛑 <b>为什么本域比 {@code device} 域更隐蔽</b>（这一句是本枚举最该被读到的地方）：
 * {@code device} 那次的"返回值在撒谎"可以在<b>下游</b>被看见
 * （随后的 {@code INSERT INTO device_dispatch} 以 {@code 23503} 失败）。
 * 而归档是链条的<b>末端收口动作</b> —— 下游没有第二步去证伪它。
 * 这个缺口<b>不会报错、不会 23503、不会被任何下游抓住</b>，
 * 它只在<b>举证时</b>暴露：审计要看归档清单，而清单查不到。
 * <p>⇒ 故本枚举<b>刻意没有</b>一个 {@code OWNED_BY_OTHER_TENANT} 常量 ——
 * 一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"。
 * 这里用<b>缺失</b>来表达"这个情形不该被继续"。
 */
public enum CaseArchiveOutcome {

    /** 本次调用建出了这一行（真正落了一份归档档案）。 */
    CREATED("已归档（新建）"),

    /**
     * 幂等命中：这一行<b>已在本租户名下</b>（重放同一次归档，未改动任何数据）。
     *
     * <p>🛑 "未改动任何数据"是<b>可被断言</b>的，不是措辞修饰：
     * V20 的自证 (e2) 用"重放后 {@code final_conclusion} 必须还是第一次那份"
     * 来钉住它（只查行数抓不住"重放顺手改写了既有档案"）。
     * 因为归档档案是<b>证据快照</b>，而"重放归档"退化成"用新参数改写既有证据"
     * 是本域最不能接受的形态之一。
     *
     * <p>🛑 注意措辞里"<b>本租户</b>"三个字是必要的：
     * 若该 {@code archive_id} 属于别的租户，函数会 {@code RAISE} 而<b>不</b>返回本态
     * （见类注释「跨租户撞号为什么必须是异常」）。
     * 少了这三个字，读代码的人无法从本态本身判断"已存在"是相对于谁的。
     */
    ALREADY_EXISTS("幂等命中：该归档档案已在本租户名下（重放，未改动数据）");

    private final String zh;

    CaseArchiveOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_case_archive()} 的取值范围（本迁移唯一的写原语）。 */
    public static final List<CaseArchiveOutcome> REGISTER_OUTCOMES = List.of(CREATED, ALREADY_EXISTS);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #ALREADY_EXISTS}（"没有写入"）被误当成 {@link #CREATED}（"写入成功"），
     * 于是调用方的审计会<b>记录一次不存在的归档</b>。
     * <p>在本域这个后果还要更重一层：一次"不存在的归档"被记成成功，
     * 意味着<b>一份举证材料被声称存在而实际不存在</b>。
     * <p>这与 {@code DeviceOutcome#fromDb} 的处置逐字同款。
     *
     * <p>🛑 附带价值：若有人把 {@code register_case_archive} 改成
     * "跨租户时返回某个第三态"，本方法会先红 —— 即"用缺失来表达不该继续"
     * 这条设计意图是被代码守住的，不只写在注释里。
     */
    public static CaseArchiveOutcome fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结案归档原语返回了空值 —— 取值范围已冻结为 " + REGISTER_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (CaseArchiveOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "结案归档原语返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + REGISTER_OUTCOMES
                        + "。🛑 特别注意：跨租户撞号在设计上是【异常】而不是一态 —— "
                        + "若某个第三态出现在这里，说明库函数把它改成了返回值，"
                        + "那会让调用方把工单标记为『已归档』而归档档案并不存在，"
                        + "且下游没有任何一步能证伪它（归档是链条末端）。"
                        + "新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED;
    }

    /** 是否属于幂等重放（数据未变，但"有人又调了一次归档"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_EXISTS;
    }
}