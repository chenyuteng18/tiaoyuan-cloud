package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * <b>手环绑定意图</b> —— {@code band} 表（客户级手环台账）的写入入参领域对象。
 *
 * <h2>🛑 它为什么存在：这张表此前没有任何写入方</h2>
 * 在 B-10 之前，{@code band} 在生产代码里<b>零写入方</b>
 * （全仓 9 处 {@code INSERT INTO band} 全部在 {@code src/test} 的夹具里）。
 * 而 {@code band_telemetry} / {@code band_sync_log} / {@code band_sync_probe} /
 * {@code band_daily_coverage} 四张表的 {@code device_id} 都是
 * {@code NOT NULL REFERENCES band (band_id)} ⇒ 一条 band 行都没有时，
 * 任何写这四张表的请求都会以 {@code 23503} 失败。
 * 而 E1 {@code POST /band/sync-batches} 与 E2 {@code POST /band/telemetry}
 * 正是<b>客户端的主数据通路</b>（契约 {@code x-callable-roles: [client]}）。
 * ⇒ 这是一个"测试全绿、门禁全 PASS、BUILD SUCCESS，而客户端上报功能是死的"的缺口。
 *
 * <h2>🛑 本类只做「形态校验」，不做「业务校验」</h2>
 * 做的：必填缺失、列长超限、枚举越界。
 * <b>不</b>做的：不判 {@code vendor} 的取值是否在厂商清单里
 * （{@code band.vendor} 的 DDL 注释逐字写着取值集"待厂商确认"——
 * 冻结一个<b>明确未定</b>的集合属代拍口径，是本仓反复禁止的动作）、
 * 不判"这个客户该不该有手环"（那是门店作业判断，不是数据层的事）。
 * 这与 {@code OrgProvisioningPlan} 的边界逐字同款：
 * <b>能在这里廉价做到、且做错必然产生隐蔽后果的，就在这做；其余原样送进数据库，
 * 由 CHECK 约束与业务评审各自负责。</b>
 *
 * <h2>🛑 为什么校验"列长"而不只是"非空"</h2>
 * {@code band.vendor} / {@code band.model} 都是 {@code VARCHAR(64)}。
 * 超长值在数据库层会以 {@code 22001 value too long for type character varying(64)}
 * 失败 —— 那对调用方不构成有效诊断（它不知道上限是多少、也不知道该截断还是该改数据）。
 * 在此挡掉，把它变成一条写明上限的可读 1001。这与
 * {@code OrgProvisioningPlan.StoreSpec} 对 {@code franchise_type} 的处理同一个理由。
 *
 * @param tenantId     租户标识（必填，必须已是合法 UUID —— 由仓储侧的
 *                     {@code BandLedger.validateTenantId} 二次核验）
 * @param bandId       手环主键。🛑 它<b>不只是</b> band 表的主键：它同时是
 *                     {@code band_telemetry} 等四张表 {@code device_id} 的引用目标，
 *                     故它是"设备侧上报"与"客户台账"两条线的<b>唯一接合点</b>。
 *                     这正是必须由调用方显式提供、而不由服务端随机生成的列：
 *                     服务端随机生成会让设备上真实的 band_id 与库里的对不上，
 *                     而"上报数据落不到正确客户名下"这件事没有任何约束会拦住。
 * @param customerId   客户标识（必填）。归属校验由 V16 的复合外键
 *                     {@code band_customer_id_fkey (tenant_id, customer_id)} 在库层强制
 * @param vendor       厂商（必填，≤64）。取值集"待厂商确认"，故不冻结枚举
 * @param model        型号（可空，≤64）
 * @param boundAt      绑定日（可空 → 库函数回落 {@code CURRENT_DATE}）。
 *                     🛑 它是"应戴天"分母的<b>起点</b>（§4.3 分母护栏：
 *                     A3 分母 = 台账应戴天），故允许调用方显式指定
 *                     （补录历史数据时必须指定）；不指定时用当天
 * @param rebind       是否换机：该客户已有有效手环时，先作废旧带子再绑新带子。
 *                     🛑 做成显式开关而不是自动推断 —— 见 {@link BandBindingResult}
 * @param rebindReason 换机时写进旧带子 {@code unbind_reason} 的理由（可空 → 默认"换机"）
 */
public record BandBindingRecord(
        String tenantId,
        UUID bandId,
        UUID customerId,
        String vendor,
        String model,
        LocalDate boundAt,
        boolean rebind,
        String rebindReason) {

    /** {@code band.vendor} / {@code band.model} 的列宽（DDL: {@code VARCHAR(64)}）。 */
    private static final int VENDOR_MAX = 64;
    private static final int MODEL_MAX = 64;

    /**
     * {@code band.unbind_reason} 的 CHECK 冻结值（V2 L158 逐字）。
     *
     * <p>🛑 这里<b>必须</b>与 DDL 一致地枚举，不能留成自由字符串：
     * {@code unbind_reason} 是"这支配带子为什么不再戴了"的<b>唯一载体</b>，
     * 而 V2 的注释点明了它的用途 ——
     * 「区分"主动放弃"与"技术性缺失" (data-spec M6)」。
     * 一个拼错的理由（例如"主动放棄"）在 DDL 层会被 {@code 23514} 拒，
     * 但拒绝形态是一条 PostgreSQL 约束名报错，调用方要自己去翻 DDL 才知道合法值。
     */
    private static final List<String> UNBIND_REASONS =
            List.of("主动放弃", "换机", "设备损坏", "其他");

    /** 绑定日不得早于的绝对下界 —— 本骨架交付日之前的数据不可能来自真实设备。 */
    private static final LocalDate EPOCH_FLOOR = LocalDate.of(2020, 1, 1);

    public BandBindingRecord {
        requireText(tenantId, "tenant_id", 64);
        if (bandId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：band_id 必填。它同时是 band_telemetry / band_sync_log / "
                            + "band_sync_probe / band_daily_coverage 四表 device_id 的引用目标 —— "
                            + "服务端不得代生成，否则设备上真实的 band_id 与库里的对不上，"
                            + "而『上报数据落到错误客户名下』没有任何约束会拦住");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "绑定手环：customer_id 必填");
        }
        requireText(vendor, "vendor", VENDOR_MAX);
        requireOptionalText(model, "model", MODEL_MAX);

        if (boundAt != null && boundAt.isBefore(EPOCH_FLOOR)) {
            // 不做"不得晚于今天"的校验：补录/回填场景下未来日期可能是刻意的，
            // 且那种"错误"在库层没有约束会拦 —— 它属于业务判断而不是形态错误。
            // 但"早于本骨架存在"的日期几乎必然是时区/年份笔误，且它会直接
            // 把 A3 的应戴天分母撑到荒谬的量级，而 A3 是退款资格的输入。
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：bound_at 早于 " + EPOCH_FLOOR + "（实际=" + boundAt
                            + "）—— bound_at 是『应戴天』分母的起点，"
                            + "而 A3 是退款资格的输入之一；一个错位的年份会把分母撑到荒谬量级，"
                            + "且库层没有约束会拦它。若确为补录历史数据，请复核年份/时区");
        }

        if (rebindReason != null && !rebindReason.isBlank()
                && !UNBIND_REASONS.contains(rebindReason)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：rebind_reason 必须是 " + UNBIND_REASONS + " 之一或留空"
                            + "（band.unbind_reason 的 CHECK 冻结值，V2 L158），实际=" + rebindReason);
        }
    }

    /**
     * 规范化后的绑定日 —— {@code null} 时回落当天。
     *
     * <p>🛑 为什么回落放在 Java 侧而不是只依赖库函数的 {@code COALESCE}：
     * 返回值与审计 payload 都要写到具体日期。若把回落只放在库层，
     * 审计里就会出现"boundAt=null"而库里是某一天 —— 那是一次
     * <b>审计与事实不一致</b>，而审计的全部价值就在"与事实一致"。
     */
    public LocalDate effectiveBoundAt() {
        return boundAt == null ? LocalDate.now() : boundAt;
    }

    /** 规范化后的换机理由 —— {@code null} 时回落"换机"（与库函数的 {@code COALESCE} 同口径）。 */
    public String effectiveRebindReason() {
        return (rebindReason == null || rebindReason.isBlank()) ? "换机" : rebindReason;
    }

    /** 供审计 payload 用的单行摘要（<b>不含</b>任何敏感内容 —— 本表无敏感列，故可直接摘要）。 */
    public String auditSummary() {
        return "band_id=" + bandId
                + " customer_id=" + customerId
                + " vendor=" + vendor
                + " model=" + (model == null ? "-" : model)
                + " bound_at=" + effectiveBoundAt()
                + " rebind=" + rebind;
    }

    private static void requireText(String v, String field, int max) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "绑定手环：" + field + " 必填");
        }
        if (v.length() > max) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：" + field + " 超过列宽上限 " + max + "（实际 " + v.length()
                            + "）—— 库层会以 22001 拒绝，而那条报错不会告诉调用方上限是多少");
        }
    }

    private static void requireOptionalText(String v, String field, int max) {
        if (v != null && v.length() > max) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：" + field + " 超过列宽上限 " + max + "（实际 " + v.length() + "）");
        }
    }

    /** 供调用方解析日期字符串（契约 JSON 里是 ISO-8601）；解析失败给可读 1001 而非 500。 */
    public static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：" + field + " 不是合法 ISO 日期（yyyy-MM-dd）：'" + raw + "'");
        }
    }

    /** 供调用方解析 UUID 字符串；解析失败给可读 1001 而非 500。 */
    public static UUID parseUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定手环：" + field + " 不是合法 UUID：'" + raw + "'");
        }
    }
}