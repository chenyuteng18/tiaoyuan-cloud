package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>手环绑定 / 解绑原语的返回值口径</b> —— 与迁移 {@code V17__band_binding_primitive.sql}
 * 里两个 plpgsql 函数 {@code bind_band()} / {@code unbind_band()} 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑 为什么必须枚举化，而不是把一个 {@code String} 一路传下去</h2>
 * 库函数返回的是 {@code text}。若应用侧原样透传这个字符串，会出现两个问题：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"（只是返回了一个新字符串），而所有分支判断静默走 {@code else}。
 *       本仓对这一类有既定处置 —— 见 {@code OrganizationProvisioningRepository} 里
 *       「断言返回值只取两个合法值：若函数被改成别的口径，这里会先红」。
 *       本枚举的 {@link #fromDb(String)} 是同一处置：<b>未登记的返回值 ⇒ 抛错</b>。</li>
 *   <li><b>后两态会被当成错误</b>：{@link #ALREADY_BOUND} 与
 *       {@link #CUSTOMER_ALREADY_HAS_ACTIVE_BAND} <b>都不是失败</b>（没有重复建行）。
 *       若透传字符串，调用方很容易把它们和异常混为一谈，
 *       于是"有人在重放"这件正常的事会被当成故障上报。</li>
 * </ol>
 *
 * <h2>🛑 为什么"客户已有有效手环"不是一个异常</h2>
 * 契约里<b>没有</b>绑定端点（域 E 的 E1~E6 只有上报与读取；D6
 * {@code /device-dispatches} 的 description 反而逐字把门店级 {@code device}
 * 与客户级 {@code band} 划开）。⇒ 没有可对照的 HTTP 错误码，
 * 也就没有"这个结果该映射到哪个状态码"的既定答案。
 * 故本原语把它做成<b>返回值</b>：这是原语与调用方之间的口径，
 * 由调用方（运维脚本 / 内部服务）决定如何处置，而不是由库层替它选一个状态码。
 *
 * <h2>🛑 为什么 CREATED 与 REPLACED 必须分开</h2>
 * 两者都"建出了一行"，看起来可以合并。但它们在<b>台账历史</b>上的含义不同：
 * {@code REPLACED} 意味着<b>另有一行被改成了 unbound</b>
 * （并写入了 {@code unbind_reason}）—— 那是"应戴天"分母的一次<b>截断</b>，
 * 而 A3 是退款资格的输入之一。合并之后，审计上就无法区分
 * "新客户首次接入"与"某客户换了一支带子"，而后者是会影响指标的。
 */
public enum BandBindingOutcome {

    // ------------------------------------------------------------------
    // bind_band() 的四态
    // ------------------------------------------------------------------

    /** 本次调用建出了这一行（该客户此前无有效手环）。 */
    CREATED("已绑定（新建）"),

    /**
     * 换机：本次调用先作废了该客户原有的有效 / 暂停手环，再建出新行。
     *
     * <p>🛑 只有 {@code p_rebind = true} 时才可能返回。
     * 副作用是<b>另一行被改成了 unbound</b> —— 见类注释「为什么 CREATED 与 REPLACED 必须分开」。
     */
    REPLACED("已绑定（换机：原有效手环已作废）"),

    /**
     * 幂等命中：<b>这一支</b>手环自己已经是 {@code active}（重放同一请求，不是冲突）。
     *
     * <p>🛑 与 {@link #CUSTOMER_ALREADY_HAS_ACTIVE_BAND} 的区别是<b>哪支带子</b>：
     * 本态是"同一个人又交了同一支"，那态是"同一个客户被给了<b>另一支</b>"。
     * 审计上这是两件事：前者是重放，后者是对业务不变量的挑战。
     */
    ALREADY_BOUND("幂等命中：该手环已是该客户的有效手环（重放，未改动数据）"),

    /**
     * 该客户已有<b>另一支</b>有效手环，且本次未要求换机。
     *
     * <p>🛑 <b>这不是错误</b>：库层的部分唯一索引 {@code uq_band_active_customer}
     * 保证了"1 客户 : 1 有效手环"这条业务不变量，本态就是它胜出的结果。
     * 新带子<b>没有被写入</b>，既有带子<b>也没有被改动</b>。
     * 要换机须显式传 {@code rebind = true}。
     */
    CUSTOMER_ALREADY_HAS_ACTIVE_BAND("客户已有另一支有效手环（未换机，本次未改动任何数据）"),

    // ------------------------------------------------------------------
    // unbind_band() 的三态
    // ------------------------------------------------------------------

    /** 本次调用把它从 {@code active} / {@code paused} 置为 {@code unbound}。 */
    UNBOUND("已解绑"),

    /** 它已经是 {@code unbound}（重放，幂等；理由与日期<b>未被覆盖</b>）。 */
    ALREADY_UNBOUND("幂等命中：该手环已是解绑状态（重放，未改动数据）"),

    /**
     * 本租户内看不到这一行。
     *
     * <h2>🛑 措辞刻意是 NOT_FOUND 而不是 NOT_EXISTS</h2>
     * 在 {@code FORCE ROW LEVEL SECURITY} 下，<b>"不存在"与"存在但当前上下文看不见"
     * 给出同一个结果</b>（策略的 {@code USING} 静默过滤 → 0 行）。
     * 用"不存在"这个词会把后一种情形<b>说成事实</b>，属对调用方的误导。
     * 调用方看到本态时应先复核租户上下文，再去怀疑数据缺失。
     */
    NOT_FOUND("本租户内查不到该手环（🛑 也可能是租户上下文不对：FORCE RLS 下两者同形）");

    private final String zh;

    BandBindingOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** 供 {@code bind_band()} 与 {@code unbind_band()} 的返回值解析视图（避免两处口径分叉）。 */
    private static final List<BandBindingOutcome> BIND_OUTCOMES =
            List.of(CREATED, REPLACED, ALREADY_BOUND, CUSTOMER_ALREADY_HAS_ACTIVE_BAND);

    private static final List<BandBindingOutcome> UNBIND_OUTCOMES =
            List.of(UNBOUND, ALREADY_UNBOUND, NOT_FOUND);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #CUSTOMER_ALREADY_HAS_ACTIVE_BAND}（一个"没有写入"的结果）被误当成
     * {@link #CREATED}（一个"写入成功"的结果），于是调用方的审计会<b>记录一次不存在的写入</b>。
     * 这与 {@code OrganizationProvisioningRepository} 对 {@code provision_tenant()}
     * 返回值的处置逐字同款。
     *
     * @param raw   库函数返回的文本
     * @param phase 抛错文案里用的阶段名（{@code bind} / {@code unbind}）——
     *              让"哪一侧的口径漂了"在报错里可读
     */
    public static BandBindingOutcome fromDb(String raw, String phase) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "手环绑定原语（" + phase + "）返回了空值 —— 本原语的取值范围已冻结为 "
                            + "bind: " + BIND_OUTCOMES + " / unbind: " + UNBIND_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (BandBindingOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "手环绑定原语（" + phase + "）返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + "bind: " + BIND_OUTCOMES + " / unbind: " + UNBIND_OUTCOMES
                        + "。新增一态必须先在这里登记，否则它会静默流进审计 payload 而无人解释它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED || this == REPLACED || this == UNBOUND;
    }

    /** 是否属于幂等重放（数据未变，但"有人又调了一次"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_BOUND || this == ALREADY_UNBOUND;
    }

    /** 是否属于"业务不变量胜出"（新请求被拒、既有数据未动）。 */
    public boolean invariantWon() {
        return this == CUSTOMER_ALREADY_HAS_ACTIVE_BAND;
    }

    /** 是否属于"看不到目标行"（见 {@link #NOT_FOUND} 的措辞说明）。 */
    public boolean notVisible() {
        return this == NOT_FOUND;
    }
}