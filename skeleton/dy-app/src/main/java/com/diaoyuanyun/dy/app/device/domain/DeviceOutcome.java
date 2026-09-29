package com.diaoyuanyun.dy.app.device.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>设备建档 / 归档原语的返回值口径</b> —— 与迁移
 * {@code V18__device_provisioning_primitive.sql} 里两个 plpgsql 函数
 * {@code register_device()} / {@code retire_device()} 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑 为什么必须枚举化（而不是透传字符串）</h2>
 * 与 {@code BandBindingOutcome} 的处置逐字同款：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"，而所有分支判断静默走 {@code else}。
 *       {@link #fromDb(String, String)} 的处置是<b>未登记的值即抛错</b>。</li>
 *   <li><b>{@link #ALREADY_EXISTS} 不是失败</b>：它是幂等命中（没有重复建行）。
 *       若透传字符串，调用方容易把它和异常混为一谈，
 *       于是"有人在重放"这件正常的事会被当成故障上报。</li>
 * </ol>
 *
 * <h2>🛑🛑 为什么这里是【两态 + 两类异常】，而 {@code BandBindingOutcome} 是四态</h2>
 * 这不是"没做完"，而是两条通路面对的业务问题不同：
 * <table border="1">
 *   <caption>态数的来源</caption>
 *   <tr><th></th><th>B-10 {@code bind_band}（四态）</th><th>B-11 {@code register_device}（两态）</th></tr>
 *   <tr><td>第三 / 第四态来自</td>
 *       <td><b>一个业务选择</b>：「同一客户又拿来第二支带子」该怎么办 ——
 *           拒绝（CUSTOMER_ALREADY_HAS_ACTIVE_BAND）还是作废旧的再绑新的（REPLACED）。</td>
 *       <td><b>没有对应的选择</b>。同一台物理设备不可能同时属于两个租户。</td></tr>
 *   <tr><td>故</td>
 *       <td>把选择权交给调用方（{@code p_rebind}），并在返回值里回显走了哪条路。</td>
 *       <td>跨租户撞号不是"另一种正常结果"，而是<b>数据冲突</b>，需人工介入 ⇒ RAISE。</td></tr>
 * </table>
 *
 * <h2>🛑🛑 跨租户撞号为什么必须是异常而不是一个返回值</h2>
 * 这是本迁移相对 V17 形态的<b>唯一实质改动</b>，也是它被实测抓出来的原因：
 * <p>{@code ON CONFLICT (device_id) DO NOTHING} 的推断目标是
 * {@code device_pkey}（<b>单列</b> {@code device_id}）。当该 {@code device_id}
 * 已被<b>别的租户</b>占用时，PG 实测行为是：
 * <ul>
 *   <li>主键冲突照常发生 ⇒ 走 {@code DO NOTHING} ⇒ <b>{@code ROW_COUNT = 0}</b>；</li>
 *   <li>而本租户上下文里 {@code SELECT} 又<b>看不见</b>那一行（RLS 的 USING 挡在外面）。</li>
 * </ul>
 * ⇒ 若照 B-10 的形态只按 {@code ROW_COUNT} 判定，就会对一个手里<b>一行都没有</b>的租户
 * 返回 {@link #ALREADY_EXISTS}。调用方据此认为"设备已就绪，继续下发"，
 * 而紧接着的 {@code INSERT INTO device_dispatch} 仍以 {@code 23503} 失败。
 * <p>即：那会造出一个<b>「返回值说 ALREADY_EXISTS、下游仍然 23503」</b>的缺陷 ——
 * 正是本仓反复要防的"返回值与行为不一致"形态。
 * <p>故 {@code register_device} 在"冲突了但我看不见"时 {@code RAISE}，
 * 而本枚举<b>刻意没有</b>一个 {@code OWNED_BY_OTHER_TENANT} 常量 ——
 * 一态一旦被登记，就等于允许调用方把它当成"可以继续流程的结果"。
 * 这里用<b>缺失</b>来表达"这个情形不该被继续"。
 */
public enum DeviceOutcome {

    // ------------------------------------------------------------------
    // register_device() 的两态
    // ------------------------------------------------------------------

    /** 本次调用建出了这一行。 */
    CREATED("已建档（新建）"),

    /**
     * 幂等命中：这一行<b>已在本租户名下</b>（重放同一请求）。
     *
     * <p>🛑 注意它的措辞里"<b>本租户</b>"三个字是必要的：
     * 若该 {@code device_id} 属于别的租户，函数会 {@code RAISE} 而<b>不</b>返回本态
     * （见类注释「跨租户撞号为什么必须是异常」）。
     * 少了这三个字，读代码的人无法从本态本身判断"已存在"是相对于谁的。
     */
    ALREADY_EXISTS("幂等命中：该设备已在本租户名下（重放，未改动数据）"),

    // ------------------------------------------------------------------
    // retire_device() 的三态
    // ------------------------------------------------------------------

    /** 本次调用把它从非 {@code retired} 置为 {@code retired}。 */
    RETIRED("已归档"),

    /** 它已经是 {@code retired}（重放，幂等；{@code updated_at} <b>未被覆盖</b>）。 */
    ALREADY_RETIRED("幂等命中：该设备已归档（重放，未改动数据）"),

    /**
     * 本租户内看不到这一行。
     *
     * <h2>🛑 措辞刻意是 NOT_FOUND 而不是 NOT_EXISTS</h2>
     * 在 {@code FORCE ROW LEVEL SECURITY} 下，<b>"不存在"与"存在但当前上下文看不见"
     * 给出同一个结果</b>（策略的 {@code USING} 静默过滤 → 0 行）。
     * 用"不存在"这个词会把后一种情形<b>说成事实</b>，属对调用方的误导。
     * 调用方看到本态时应先复核租户上下文，再去怀疑数据缺失。
     * <p>（这一条与 {@code BandBindingOutcome#NOT_FOUND} 逐字同款 ——
     * 两处面对的是同一个 RLS 语义，故处置也必须是同一个。）
     */
    NOT_FOUND("本租户内查不到该设备（🛑 也可能是租户上下文不对：FORCE RLS 下两者同形）");

    private final String zh;

    DeviceOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_device()} 的取值范围。 */
    private static final List<DeviceOutcome> REGISTER_OUTCOMES = List.of(CREATED, ALREADY_EXISTS);

    /** {@code retire_device()} 的取值范围。 */
    private static final List<DeviceOutcome> RETIRE_OUTCOMES =
            List.of(RETIRED, ALREADY_RETIRED, NOT_FOUND);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #ALREADY_EXISTS}（"没有写入"）被误当成 {@link #CREATED}（"写入成功"），
     * 于是调用方的审计会<b>记录一次不存在的写入</b>。
     * 这与 {@code BandBindingOutcome#fromDb} 的处置逐字同款。
     *
     * <p>🛑 附带价值：若有人把 {@code register_device} 改成"跨租户时返回某个第三态"，
     * 本方法会先红 —— 即"用缺失来表达不该继续"这条设计意图是被代码守住的，
     * 不只写在注释里。
     *
     * @param raw   库函数返回的文本
     * @param phase 抛错文案里的阶段名（{@code register} / {@code retire}）——
     *              让"哪一侧的口径漂了"在报错里可读
     */
    public static DeviceOutcome fromDb(String raw, String phase) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "设备建档原语（" + phase + "）返回了空值 —— 取值范围已冻结为 "
                            + "register: " + REGISTER_OUTCOMES + " / retire: " + RETIRE_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (DeviceOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "设备建档原语（" + phase + "）返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + "register: " + REGISTER_OUTCOMES + " / retire: " + RETIRE_OUTCOMES
                        + "。🛑 特别注意：跨租户撞号在设计上是【异常】而不是一态 —— "
                        + "若某个第三态出现在这里，说明库函数把它改成了返回值，"
                        + "那会让调用方误以为设备已就绪并继续下发（而下游仍会 23503 失败）。"
                        + "新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED || this == RETIRED;
    }

    /** 是否属于幂等重放（数据未变，但"有人又调了一次"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_EXISTS || this == ALREADY_RETIRED;
    }

    /** 是否属于"看不到目标行"（见 {@link #NOT_FOUND} 的措辞说明）。 */
    public boolean notVisible() {
        return this == NOT_FOUND;
    }
}