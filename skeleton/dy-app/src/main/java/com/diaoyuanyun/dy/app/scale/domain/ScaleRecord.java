package com.diaoyuanyun.dy.app.scale.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.UUID;

/**
 * <b>量表建档意图</b> —— 一次"往租户建设量表台账"的完整输入。
 *
 * <h2>🛑 它补的是什么：{@code scale} 表的"零写入方"为什么是一个功能缺口</h2>
 * 在 B-12 之前，{@code scale} 在生产代码里零写入方、真库零行（实测 {@code count(*) = 0}）：
 * <pre>
 *   baseline_assessment.scale_id → FOREIGN KEY (tenant_id, scale_id)
 *                                 REFERENCES scale (tenant_id, scale_id)   [V5 L347 + V16 复合化]
 * </pre>
 * 而契约 C2 {@code POST /customers/{id}/assessments/baseline} 走的
 * {@code AssessmentService.submitBaseline} 第 ③ 步<b>有一道业务预检</b>：
 * <pre>
 *   if (!ledger.scaleExists(tenantId, scaleId)) {
 *       throw new BizException(BUSINESS_RULE_VIOLATED,
 *           "scale_id 指向的量表不存在或不属当前租户: " + scaleId);
 *   }
 * </pre>
 * ⇒ 在 scale 零行的现实下那道检查<b>恒为 false</b> ⇒ C2 <b>恒返回 422 / 5001</b>。
 *
 * <h2>🛑🛑 与 {@code DeviceRecord}（B-11）的关键差别：缺口表现不是 23503，而是 422</h2>
 * <table border="1">
 *   <caption>同一类缺口（零写入方表被有写入方表引用）的两种对外表现</caption>
 *   <tr><th></th><th>B-11 {@code device}</th><th>B-12 {@code scale}</th></tr>
 *   <tr><td>链上有没有存在性预检</td>
 *       <td><b>没有</b>（实测全仓 {@code src/main} 零命中）</td>
 *       <td><b>有</b>（{@code AssessmentService} 第 ③ 步）</td></tr>
 *   <tr><td>⇒ 缺口表现</td>
 *       <td>一条 PostgreSQL 的 {@code 23503}，除了约束名没有可读信息</td>
 *       <td>一条业务错误 {@code 422 / 5001}，文案是
 *           「量表不存在或不属当前租户」</td></tr>
 *   <tr><td>为什么这个区别重要</td>
 *       <td>诚实地报"外键违反"，只是不好读</td>
 *       <td>🛑 <b>文案会把人引向错误方向</b>：读它的人会去怀疑
 *           "scale_id 是否传错 / 租户上下文是否不对"，
 *           而真因是<b>库里压根没有任何量表</b></td></tr>
 * </table>
 * ⇒ 故 B-12 的验收口径不是"23503 消失"，而是"C2 那道预检能返回 true"——
 * 见 {@code ScaleLedger#canReferenceBaseline}。
 *
 * <h2>🛑 为什么本类【不】冻结 scale_type / status 的取值集</h2>
 * 与 {@link com.diaoyuanyun.dy.app.device.domain.DeviceRecord} 的处置同款，理由<b>不同</b>：
 * <ul>
 *   <li>{@code scale.scale_type} 的 CHECK 已冻结 {@code primary / calibration}（V5 L245）；</li>
 *   <li>{@code scale.status} 的 CHECK 已冻结 {@code active / deprecated}（V5 L250）。</li>
 * </ul>
 * 若本类再写一份白名单，两处口径会各自漂移（一处放宽、一处没放宽），
 * 而"哪个更严"取决于谁先跑 —— 这类分叉的失败形态是<b>静默的</b>。
 * <p>🛑 {@code status} 这里更有一层：建档只可能产出 {@code active}
 * （V19 的 INSERT 直接写死 {@code 'active'}），故 Java 侧<b>完全没有</b>
 * 需要表达 status 的地方 —— 若本类收一个 {@code status} 入参，
 * 就等于给了调用方一个"能不能直接建一个 deprecated 量表"的错觉。
 * 状态迁移的唯一入口是 {@code deprecate_scale}。
 *
 * <h2>🛑 为什么 {@code dimensionSetJson} 只校验"是不是非空 JSON 对象"</h2>
 * {@code scale.dimension_set_json} 是 {@code JSONB NOT NULL}，
 * 但<b>没有任何 CHECK 冻结它的形状</b>（实测：scale 表 5 条约束里只有 2 条 CHECK，
 * 都是枚举列上的；见 V19 前置自检节）。V5 L241 的注释描述它是"7 维数组"，
 * 但那是<b>注释</b>而不是约束。
 * <p>⇒ 本类<b>刻意不</b>在 Java 侧把"7 维"写成白名单：
 * 那会把一条<b>没有约束力</b>的注释升格成一个执行期判据，
 * 于是"库层没有的约束、应用侧却有"这种分叉就出现了 ——
 * 而分叉的失败形态恰是：库层接受的一行，应用侧拒绝；
 * 或反过来，应用侧通过的一行，库层的读路径解释不了它。
 * <p>处置：本类只做<b>形态</b>校验（非空 + 是对象 + 不是 {@code {}}），
 * 把"7 维取值集"登记进缺口清单（V19 第 5 节第 ② 条），
 * 由 schema 层决定要不要收紧，而不是由本类代它收紧。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 与 {@code DeviceRecord} 逐字同款：不另写一份白名单正则。
 * 两处口径会各自漂移，"哪个更严"取决于谁先跑。
 * 复用的是<b>同一个</b>租户标识形态校验 —— 这是"同一件事只有一处定义"的应用。
 *
 * @param tenantId        量表所属租户（必填）
 * @param scaleId         量表主键（必填）；🛑 它是<b>全局</b>主键
 *                        （{@code scale_pkey = PRIMARY KEY (scale_id)}，实测单列），
 *                        故"同一个量表标识的一生只属于一个租户"
 * @param scaleType       量表类型（必填）；取值集由库层 CHECK 冻结为
 *                        {@code primary / calibration}
 * @param scaleVersion    量表版本（必填）；🛑 在单列主键下它是<b>不可递增</b>的
 *                        （见 {@link ScaleOutcome} 与 V19 文件头「核心机制三」），
 *                        故它<b>必须由调用方显式给出</b>，并由库函数如实判定
 * @param name            量表名称（必填）
 * @param dimensionSetJson 维度集（必填）；🛑 本类不冻结其形状 —— 库层没有对应 CHECK
 */
public record ScaleRecord(
        String tenantId,
        UUID scaleId,
        String scaleType,
        String scaleVersion,
        String name,
        String dimensionSetJson) {

    /** {@code scale.scale_type} 的上限（{@code VARCHAR(16)}）。 */
    private static final int TYPE_MAX = 16;

    /** {@code scale.scale_version} 的上限（{@code VARCHAR(32)}）。 */
    private static final int VERSION_MAX = 32;

    /** {@code scale.name} 的上限（{@code VARCHAR(128)}）。 */
    private static final int NAME_MAX = 128;

    /**
     * {@code scale.dimension_set_json} 的形态校验常量。
     *
     * <p>🛑 只判"是个非空对象"，<b>不</b>判维度个数 —— 理由见类注释。
     */
    private static final String JSON_EMPTY_OBJECT = "{}";

    /**
     * 规范构造器 —— 形态校验都在这里，使"一个非法的建档意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service（与 {@code DeviceRecord} 逐字同款）：
     * 构造器是<b>唯一</b>的入口。放在 Service 里意味着
     * "只要有人绕过 Service 直接 new 一个 record"就能拿到非法值。
     */
    public ScaleRecord {
        BandLedger.validateTenantId(tenantId);

        if (scaleId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：scale_id 必填 —— 它是 scale 表主键，"
                            + "且是 baseline_assessment 复合外键 "
                            + "(tenant_id, scale_id) → scale(tenant_id, scale_id) 的引用目标。"
                            + "没有它，C2 基线评估无法落库");
        }

        if (scaleType == null || scaleType.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：scale_type 必填（scale.scale_type 是 NOT NULL）。"
                            + "🛑 本类不冻结取值集（primary / calibration 由库层 CHECK 冻结）—— "
                            + "在 Java 侧再写一份白名单会造出两处会各自漂移的口径");
        }
        scaleType = scaleType.trim();
        if (scaleType.length() > TYPE_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：scale_type 长度 " + scaleType.length()
                            + " 超过 scale.scale_type 的 VARCHAR(" + TYPE_MAX + ")。"
                            + "🛑 在 Java 侧拦是因为库层的失败形态是一条 22001，"
                            + "它会以『数据截断』的面目出现在日志里，而不是『参数太长』");
        }

        if (scaleVersion == null || scaleVersion.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：scale_version 必填（scale.scale_version 是 NOT NULL）。"
                            + "🛑 它必须由调用方【显式】给出，不接受缺省或自动递增："
                            + "scale_pkey 是单列 scale_id ⇒ 同一 scale_id 只可能有一行 ⇒ "
                            + "在库层无法表达『版本递增』。若在这里造一个『看起来会递增』的缺省值，"
                            + "它算出的版本号永远落不了库（撞主键被 ON CONFLICT 吞掉），"
                            + "而调用方会以为版本在往前走 —— 实际钉死在原地");
        }
        scaleVersion = scaleVersion.trim();
        if (scaleVersion.length() > VERSION_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：scale_version 长度 " + scaleVersion.length()
                            + " 超过 scale.scale_version 的 VARCHAR(" + VERSION_MAX + ")");
        }

        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：name 必填（scale.name 是 NOT NULL）—— "
                            + "人工核对台账时它是唯一可读的标识（其余是 UUID）");
        }
        name = name.trim();
        if (name.length() > NAME_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：name 长度 " + name.length()
                            + " 超过 scale.name 的 VARCHAR(" + NAME_MAX + ")");
        }

        if (dimensionSetJson == null || dimensionSetJson.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：dimension_set_json 必填（scale.dimension_set_json 是 NOT NULL）");
        }
        dimensionSetJson = dimensionSetJson.trim();
        if (JSON_EMPTY_OBJECT.equals(dimensionSetJson)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档量表：dimension_set_json 不可为空对象 {} —— "
                            + "一个没有任何维度的量表无法被打分，"
                            + "它落库后会成为一个『存在但不可用』的量表，"
                            + "而 C2 的存在性预检会为它返回 true（于是缺口从『量表不存在』"
                            + "变成了更难查的『量表在但打不出分』）");
        }
    }

    /**
     * 便捷构造：把维度集写成紧凑 JSON 对象字面量。
     *
     * <p>🛑 存在的理由与 {@code DeviceRecord.of} 不同：那里是为了省掉尾部的裸 {@code null}；
     * 这里是为了让<b>门禁与运维脚本</b>不必手拼 JSON 字符串
     * （手拼字符串正是 {@code RlsInjectionRealityGateTest} 这类静态门禁最容易误报的地方）。
     *
     * <p>⚠️ 本方法<b>不</b>做 JSON 转义：它只接受已经成形的 JSON 对象文本。
     * 传进来一个非对象、或含未转义引号的片段，会在库层以 {@code 22P02}
     * （{@code invalid input syntax for type json}）失败 ——
     * 那是<b>故意</b>的：本方法不是 JSON 构造器，不该假装自己会转义。
     */
    public static ScaleRecord of(String tenantId, UUID scaleId,
                                 String scaleType, String scaleVersion,
                                 String name, String dimensionSetJson) {
        return new ScaleRecord(tenantId, scaleId, scaleType, scaleVersion, name, dimensionSetJson);
    }
}