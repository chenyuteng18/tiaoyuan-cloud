package com.diaoyuanyun.dy.app.device.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.UUID;

/**
 * <b>设备建档意图</b> —— 一次"往门店建设备台账"的完整输入。
 *
 * <h2>🛑 它补的是什么：{@code device} 表的"零写入方"为什么是一个功能缺口</h2>
 * 在 B-11 之前，{@code device} 在生产代码里零写入方、真库零行：
 * <pre>
 *   device_dispatch.device_id UUID NOT NULL REFERENCES device (device_id)   [V5 L441]
 * </pre>
 * 而 D6 {@code POST /device-dispatches} 是 {@code FulfillmentLedger.insertDeviceDispatch}
 * 直接写的 —— <b>链上没有任何 device 存在性预检</b>（实测全仓 {@code src/main} 零命中）。
 * ⇒ 任何一次下发都会以 {@code 23503} 失败，而且是唯一的表现形式：
 * 没有可读的业务错误，只有一条 PostgreSQL 的约束名。
 *
 * <h2>🛑 为什么本类【不】冻结 model 的取值集</h2>
 * {@code device.model} 的 CHECK 已冻结 {@code 杠2 / 现有}（V5 L134）。
 * 若本类再写一份白名单，两处口径会各自漂移（一处放宽、一处没放宽），
 * 而"哪个更严"取决于谁先跑 —— 这类分叉的失败形态是<b>静默的</b>：
 * 一边收下了一台设备，另一边的读侧却不知道怎么解释它。
 * <p>故本类只做"非空 / 非法字符"这一层的形态校验，取值集交给库层 CHECK
 * （与 V18 函数不重复冻结 model 的结论一致 —— 但<b>理由不同</b>：
 * 那里是"已由 CHECK 冻结，再写一遍是重复定义"；这里是"Java 侧没有权威来源"。
 * 两条理由都指向同一个做法，但它们不是同一条理由）。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 不另写一份白名单正则。理由与 {@code BandBindingLedger} 逐字相同：
 * 两处口径会各自漂移，"哪个更严"取决于谁先跑。
 * 复用的是<b>同一个</b>租户标识形态校验 —— 这是"同一件事只有一处定义"的应用。
 *
 * @param tenantId        设备所属租户（必填）
 * @param deviceId        设备主键（必填）；🛑 它是<b>全局</b>主键，
 *                        故"同一台设备的一生只属于一个租户"
 * @param storeId         设备所在门店（必填）；必须是<b>本租户内</b>存在的门店
 * @param model           设备型号（必填）；取值集由库层 CHECK 冻结为 {@code 杠2 / 现有}
 * @param paramTemplateId 参数模板标识（可空）；库层注释写明取值 {@code TPL-G2-V3 / TPL-STD-V2}
 *                        但字典未声明 FK，故本类同样不冻结
 */
public record DeviceRecord(
        String tenantId,
        UUID deviceId,
        UUID storeId,
        String model,
        String paramTemplateId) {

    /** {@code device.model} 的 CHECK 上限（{@code VARCHAR(32)}）。 */
    private static final int MODEL_MAX = 32;

    /** {@code device.param_template_id} 的上限（{@code VARCHAR(64)}）。 */
    private static final int TEMPLATE_MAX = 64;

    /**
     * 规范构造器 —— 形态校验都在这里，使"一个非法的建档意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service：构造器是<b>唯一</b>的入口。
     * 放在 Service 里意味着"只要有人绕过 Service 直接 new 一个 record"就能拿到非法值 ——
     * 而那正是这个 record 存在的意义（它把"意图"变成一个<b>自校验</b>的值对象）。
     */
    public DeviceRecord {
        BandLedger.validateTenantId(tenantId);

        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档设备：device_id 必填 —— 它是 device 表主键，"
                            + "且是 device_dispatch.device_id（NOT NULL 外键）的引用目标。"
                            + "没有它，D6 下发无法落库");
        }
        if (storeId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档设备：store_id 必填 —— device.store_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, store_id) → store 的一端。"
                            + "它决定这台设备的参数下发到哪个门店");
        }
        if (model == null || model.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档设备：model 必填（device.model 是 NOT NULL）。"
                            + "🛑 本类不冻结取值集（杠2 / 现有由库层 CHECK 冻结）—— "
                            + "在 Java 侧再写一份白名单会造出两处会各自漂移的口径");
        }
        model = model.trim();
        if (model.length() > MODEL_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档设备：model 长度 " + model.length() + " 超过 device.model 的 VARCHAR("
                            + MODEL_MAX + ")。🛑 在 Java 侧拦是因为库层的失败形态是一条 22001，"
                            + "它会以『数据截断』的面目出现在日志里，而不是『参数太长』");
        }
        if (paramTemplateId != null) {
            paramTemplateId = paramTemplateId.trim();
            if (paramTemplateId.isEmpty()) {
                // 空串与 null 在 DDL 里都合法，但语义上"我传了个空串"与"我没传"是两件事。
                // 归一成 null，避免库里出现"看起来有模板、实际是空串"的行。
                paramTemplateId = null;
            } else if (paramTemplateId.length() > TEMPLATE_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "建档设备：param_template_id 长度 " + paramTemplateId.length()
                                + " 超过 device.param_template_id 的 VARCHAR(" + TEMPLATE_MAX + ")");
            }
        }
    }

    /**
     * 便捷构造：不带参数模板。
     *
     * <p>省掉一个 {@code null} 实参 —— 本仓对这种"全是 null 的尾巴"有过教训：
     * 调用点上一串裸 {@code null} 无法自证"这一个 null 是哪一个字段"。
     */
    public static DeviceRecord of(String tenantId, UUID deviceId, UUID storeId, String model) {
        return new DeviceRecord(tenantId, deviceId, storeId, model, null);
    }
}