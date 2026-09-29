package com.diaoyuanyun.dy.app.scale.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * <b>量表建档 / 废弃原语的返回值口径</b> —— 与迁移
 * {@code V19__scale_provisioning_primitive.sql} 里两个 plpgsql 函数
 * {@code register_scale()} / {@code deprecate_scale()} 的返回值<b>逐字对齐</b>。
 *
 * <h2>🛑 为什么必须枚举化（而不是透传字符串）</h2>
 * 与 {@code DeviceOutcome} / {@code BandBindingOutcome} 的处置逐字同款：
 * <ol>
 *   <li><b>口径漂移无人察觉</b>：有人改了函数的某个 {@code RETURN} 字面量，
 *       应用侧仍然"跑得通"，而所有分支判断静默走 {@code else}。
 *       {@link #fromDb(String, String)} 的处置是<b>未登记的值即抛错</b>。</li>
 *   <li><b>{@link #ALREADY_EXISTS} 不是失败</b>：它是幂等命中（没有重复建行）。
 *       若透传字符串，调用方容易把它和异常混为一谈，
 *       于是"有人在重放"这件正常的事会被当成故障上报。</li>
 * </ol>
 *
 * <h2>🛑 两态 + 三态：与 {@code DeviceOutcome} 同形，但第三态【不同名】</h2>
 * <pre>
 *   register_scale()  : CREATED / ALREADY_EXISTS
 *   deprecate_scale() : DEPRECATED / ALREADY_DEPRECATED / NOT_FOUND
 * </pre>
 * <p>与 {@code device} 的 {@code RETIRED / ALREADY_RETIRED / NOT_FOUND} 一一对应，
 * 但刻意<b>换名</b>（{@code deprecated} 而不是 {@code retired}），理由：
 * 两个词在本仓的业务语境里<b>不是同一件事</b>的两种说法 ——
 * <ul>
 *   <li>{@code device.retired} = "这台设备退出服役"，是<b>资产状态</b>；</li>
 *   <li>{@code scale.deprecated} = "这个量表不再用于新建基线"，是<b>口径退役</b>。</li>
 * </ul>
 * 若复用 {@code RETIRED} 这个名字，读代码的人会合理推断"scale 的废弃与 device 的归档
 * 走同一套语义"，而它们连<b>副作用</b>都不同（见下）。本仓对这一点有既定纪律：
 * <b>两个词不同，就不要用同一个常量名假装它们相同。</b>
 *
 * <h2>🛑🛑 为什么跨租户撞号与版本冲突都【不是】返回值（这是本迁移的核心设计）</h2>
 * 两条都是数据冲突，都需人工介入，故在 V19 里都是 {@code RAISE}。
 * 本枚举<b>刻意没有</b> {@code OWNED_BY_OTHER_TENANT} 与 {@code VERSION_CONFLICT}
 * 两个常量 —— <b>用缺失表达"这个情形不该被继续"</b>。
 *
 * <table border="1">
 *   <caption>两种冲突为什么都必须 RAISE</caption>
 *   <tr><th></th><th>跨租户撞号</th><th>版本冲突</th></tr>
 *   <tr><td>成因</td>
 *       <td>{@code scale_pkey} 是<b>单列</b> {@code scale_id}
 *           ⇒ 别的租户可能已占用同一 id</td>
 *       <td>{@code scale_pkey} 是<b>单列</b> {@code scale_id}
 *           ⇒ 同一 id 只可能有一行，{@code uq_scale_id_version} 是死索引</td></tr>
 *   <tr><td>若返回 {@code ALREADY_EXISTS}</td>
 *       <td>调用方以为量表已就绪 → 继续提交基线 → C2 仍 422
 *           ⇒ <b>返回值在撒谎</b></td>
 *       <td>调用方以为"我要的那一版已存在" → 而库里是旧版本 →
 *           {@code ledger.scaleVersion()} 返回旧值 ⇒ <b>版本口径错配</b></td></tr>
 *   <tr><td>正确处置</td>
 *       <td>人工确认 scale_id 传错 / 量表被错误分发</td>
 *       <td>人工决定：新建 scale_id，还是先显式废弃旧版</td></tr>
 * </table>
 * <p>🛑 <b>与 V18 的差别在这里</b>：V18 只有一条"不能继续"的情形（跨租户），
 * V19 有两条。多出来的那条（版本冲突）<b>不是从实测抓出来的，而是读 schema 读出来的</b>——
 * 见 V19 文件头「核心机制三」的可迁移教训：
 * 「凡 {@code ON CONFLICT} 的推断目标不含 {@code tenant_id} 的表，都要过一遍这条判定链。」
 *
 * <h2>🛑 副作用（刻意保留）：{@code deprecated} 行仍占据 {@code scale_id} 主键</h2>
 * 与 {@code DeviceOutcome#RETIRED} 的说明同款：这意味着<b>主键复用需要先物理删除废弃行</b>，
 * 而那是一个显式的人工决定。但它在本域的后果比 device 更重：
 * 一个废弃的量表行会<b>永久占据</b>那个 {@code scale_id}，
 * 于是"同一 scale_id 换一版内容重新启用"在库层<b>不可能</b>实现 ——
 * 这正是 V19 第 5 节登记的第 ③ 条缺口（{@code reactivate_scale} 未提供）。
 */
public enum ScaleOutcome {

    // ------------------------------------------------------------------
    // register_scale() 的两态
    // ------------------------------------------------------------------

    /** 本次调用建出了这一行。 */
    CREATED("已建档（新建）"),

    /**
     * 幂等命中：这一行<b>已在本租户名下、且版本相同</b>（重放同一请求）。
     *
     * <p>🛑 措辞里的"<b>本租户</b>"与"<b>且版本相同</b>"两个限定都是必要的：
     * <ul>
     *   <li>若该 {@code scale_id} 属于别的租户，函数会 {@code RAISE}（见类注释表格）；</li>
     *   <li>若在本租户名下但版本不同，函数同样 {@code RAISE} ——
     *       因为此时 {@code ALREADY_EXISTS} 会让调用方以为"我要的那一版已存在"。</li>
     * </ul>
     * 少了这两个限定，读代码的人无法从本态本身判断"已存在"是相对于谁、相对于哪一版的。
     */
    ALREADY_EXISTS("幂等命中：该量表已在本租户名下且版本相同（重放，未改动数据）"),

    // ------------------------------------------------------------------
    // deprecate_scale() 的三态
    // ------------------------------------------------------------------

    /** 本次调用把它从非 {@code deprecated} 置为 {@code deprecated}。 */
    DEPRECATED("已废弃"),

    /** 它已经是 {@code deprecated}（重放，幂等；{@code updated_at} <b>未被覆盖</b>）。 */
    ALREADY_DEPRECATED("幂等命中：该量表已废弃（重放，未改动数据）"),

    /**
     * 本租户内看不到这一行。
     *
     * <h2>🛑 措辞刻意是 NOT_FOUND 而不是 NOT_EXISTS</h2>
     * 与 {@code DeviceOutcome#NOT_FOUND} 逐字同款：
     * 在 {@code FORCE ROW LEVEL SECURITY} 下，<b>"不存在"与"存在但当前上下文看不见"
     * 给出同一个结果</b>（策略的 {@code USING} 静默过滤 → 0 行）。
     * 用"不存在"这个词会把后一种情形<b>说成事实</b>，属对调用方的误导。
     * <p>⚠️ 本域还有一层别的可能：该 {@code scale_id} 属于<b>别的租户</b>
     * （{@code scale_pkey} 单列 ⇒ 这是可能的）。
     * 在本读法下它与"不存在"同样同形 —— 这正是用 NOT_FOUND 而不是
     * "不存在 / 不属于本租户"这类断言式措辞的原因。
     */
    NOT_FOUND("本租户内查不到该量表（🛑 也可能是租户上下文不对：FORCE RLS 下两者同形）");

    private final String zh;

    ScaleOutcome(String zh) {
        this.zh = zh;
    }

    /** 中文说明（供日志 / 审计 payload / 运维回执；断言不依赖它）。 */
    public String zh() {
        return zh;
    }

    /** {@code register_scale()} 的取值范围。 */
    private static final List<ScaleOutcome> REGISTER_OUTCOMES = List.of(CREATED, ALREADY_EXISTS);

    /** {@code deprecate_scale()} 的取值范围。 */
    private static final List<ScaleOutcome> DEPRECATE_OUTCOMES =
            List.of(DEPRECATED, ALREADY_DEPRECATED, NOT_FOUND);

    /**
     * 由库函数的返回值解析本枚举；<b>未登记的值即抛错</b>（fail-closed）。
     *
     * <h2>为什么"未登记就抛"而不是"落到某个默认态"</h2>
     * 落默认态会让一次口径漂移静默通过 —— 而漂移的那一态恰好可能是
     * {@link #ALREADY_EXISTS}（"没有写入"）被误当成 {@link #CREATED}（"写入成功"），
     * 于是调用方的审计会<b>记录一次不存在的写入</b>。
     * 这与 {@code DeviceOutcome#fromDb} 的处置逐字同款。
     *
     * <p>🛑 附带价值：若有人把 {@code register_scale} 改成"跨租户时返回某个第三态"
     * 或"版本不同时返回 ALREADY_EXISTS"，本方法会先红 ——
     * 即"用缺失表达不该继续"这条设计意图是被代码守住的，不只写在注释里。
     * 报错文案里逐条点名了这两种禁止的第三态，使"谁把口径改错了"无需再查迁移。
     *
     * @param raw   库函数返回的文本
     * @param phase 抛错文案里的阶段名（{@code register} / {@code deprecate}）——
     *              让"哪一侧的口径漂了"在报错里可读
     */
    public static ScaleOutcome fromDb(String raw, String phase) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "量表建档原语（" + phase + "）返回了空值 —— 取值范围已冻结为 "
                            + "register: " + REGISTER_OUTCOMES
                            + " / deprecate: " + DEPRECATE_OUTCOMES
                            + "，空值不在其中");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (ScaleOutcome o : values()) {
            if (o.name().equals(v)) {
                return o;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "量表建档原语（" + phase + "）返回了未登记的口径 '" + raw + "' —— 应用侧只认 "
                        + "register: " + REGISTER_OUTCOMES
                        + " / deprecate: " + DEPRECATE_OUTCOMES
                        + "。🛑 特别注意本域有【两条】在设计上必须是异常而不是返回值的情形："
                        + "① scale_id 被另一租户占用（若变成返回值，调用方会以为量表已就绪并继续"
                        + "提交基线评估，而 C2 的预检仍会以『量表不存在或不属当前租户』把它拒掉 ——"
                        + "即返回值与行为不一致）；"
                        + "② 同一 scale_id 版本不同（若返回 ALREADY_EXISTS，调用方会以为"
                        + "『我要的那一版已存在』，而库里那一行还是旧版本 ⇒ 基线评估与量表版本口径错配）。"
                        + "新增一态必须先在这里登记并写清它的含义");
    }

    /** 本次调用是否<b>真的改动了数据</b>（供审计区分"变更"与"重放/未变"）。 */
    public boolean mutatedData() {
        return this == CREATED || this == DEPRECATED;
    }

    /** 是否属于幂等重放（数据未变，但"有人又调了一次"这件事本身值得留痕）。 */
    public boolean idempotentReplay() {
        return this == ALREADY_EXISTS || this == ALREADY_DEPRECATED;
    }

    /** 是否属于"看不到目标行"（见 {@link #NOT_FOUND} 的措辞说明）。 */
    public boolean notVisible() {
        return this == NOT_FOUND;
    }
}