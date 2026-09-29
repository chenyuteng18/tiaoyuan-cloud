package com.diaoyuanyun.dy.app.band.domain;

import com.diaoyuanyun.dy.crypto.field.SensitiveField;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>{@code band_telemetry} 的 metric 取值 ↔ 需加密字段 的显式登记表</b>（B-1）。
 *
 * <h2>为什么需要这张表，而不是"在写入处判断一下该不该加密"</h2>
 * {@link SensitiveField} 的类头已经把这件事说透了，此处只是把它落到本域：
 * 「若字段名散落在各调用点，那么『哪些字段真的被加密了』就是一个只能靠 grep 猜的问题，
 * 而 grep 猜不出『某个新加的血氧字段忘了加密』—— 它会安静地以明文落库。」
 *
 * <p>把这个判断集中到一个类、并让它<b>可被测试断言完整</b>，是把"记得加密"从
 * 人的纪律改成类型的纪律。本表被 {@code BandTelemetryEncryptionTest} 逐项验证。
 *
 * <h2>🛑 本表最重要的一列是 <b>未登记</b> 的那 7 个 metric（不静默原则）</h2>
 * {@code band_telemetry.metric} 的 CHECK 是 <b>13 值</b>（见 V3），而 {@link SensitiveField}
 * 只有 <b>6 项</b> ⇒ 差集恰为 <b>7 项</b>。差集不是"忘了"，也不是"都不敏感"——
 * 它是<b>两件不同的事</b>，必须逐项写明理由才能避免两种失效：
 * <ul>
 *   <li>把差集当成"已覆盖" ⇒ 呼吸率等真实健康数据明文落库而无人知晓；</li>
 *   <li>把差集当成"一律加密" ⇒ 给非敏感的派生活动量（MET / MAI）也加密，
 *       徒增密钥轮换与解密开销，且让"敏感字段清单"失去区分力。</li>
 * </ul>
 * 故差集逐项登记在 {@link #notRegisteredReasons()}，并由测试断言
 * {@code 登记项 ∪ 未登记项 = 全部 13 值} 且两者不相交 —— 于是<b>将来新增一个 metric
 * 取值而不更新本表，测试立刻红</b>，而不是悄悄多出一个明文落库的字段。
 *
 * <h2>🛑 修正记录：{@code 'sport'} <b>不是</b> metric 取值（2026-09-26）</h2>
 * 本表初期把 {@code 'sport'} 当作"未登记的 metric"登记过，这是<b>错的</b>：
 * 契约 §4.3 与 {@code BandLedger} 都表明游标型的幂等键是
 * <b>{@code (device_id, 'sport', sport_id)}</b> —— 其中 {@code 'sport'} 是幂等键里的
 * <b>字面量</b>，不是 {@code band_telemetry.metric} 的取值（V3 的 13 值 CHECK 里<b>没有</b>它）。
 * 把它登记进来会让两侧合计变成 14 ≠ 13，而"合计必须等于 13"正是一条测试断言 ——
 * 这条断言的意义就在于此：它抓到了"把一个非 metric 的字面量误当成 metric"这件事。
 *
 * <p>修正后：{@code BY_METRIC}(6) ∪ {@code NOT_REGISTERED}(7) = <b>13</b>，
 * 与 V3 的 CHECK 逐值相等，且与 V12 迁移里的两条 SQL 清单逐值相等
 * （由 {@code TelemetrySensitivityMigrationGateTest} 交叉断言）。
 * ⚠️ 注意游标型遥测行的 {@code metric} 落库值<b>由服务层决定</b>（{@code BandService} 里
 * {@code isSport} 分支并不改写 metric 字段，见其 {@code upsertTelemetry}）——
 * 故本表无需为 {@code 'sport'} 提供分支；若将来契约为游标型新增 metric 取值，
 * 它必须同时进入 V3 的 CHECK 与本表，否则会被 {@link #sensitiveFieldOf} 与
 * V12 的门禁两侧同时拒绝（这正是 fail-closed 的期望行为）。
 *
 * <h2>与扫描面词表的关系（易混淆，故写明）</h2>
 * 与 {@link SensitiveField} 类头登记的同一条纪律：本表管的是
 * <b>服务端需要对哪些字段加密</b>；{@code compliance/wordlists/scan3_derived.words}
 * 管的是<b>客户端不得出现</b>哪些字段名（GTL1 SDK 能力溢出，不是我们的需求）。
 * 两者判据不同，<b>不得</b>互相引用或合并。
 */
public final class TelemetrySensitivity {

    private TelemetrySensitivity() {
    }

    /** metric 取值 → 需加密字段（有映射者）。 */
    private static final Map<String, SensitiveField> BY_METRIC = Map.of(
            "hr", SensitiveField.HEART_RATE,
            "resting_hr", SensitiveField.RESTING_HEART_RATE,
            "spo2", SensitiveField.BLOOD_OXYGEN,
            "steps", SensitiveField.STEPS,
            "workout", SensitiveField.WORKOUT,
            "sleep", SensitiveField.SLEEP_STAGES);

    /**
     * 13 值中<b>不</b>登记为敏感字段的取值，逐项附理由（恰 7 项）。
     *
     * <p>🛑 理由必须具体到"为什么它不需要加密"，不得写"非敏感"这种同义反复 ——
     * 同义反复的登记等于没登记，因为审阅者无法据此判断当 SDK 升级、
     * 该指标变得可推断健康状况时，这条登记是否还成立。
     *
     * <p>⚠️ 本清单必须与 {@link #BY_METRIC} 的键集<b>不相交</b>，且两者并集必须
     * 恰等于 V3 的 13 值 CHECK —— 由 {@code TelemetrySensitivityMigrationGateTest}
     * 断言，并与 V12 迁移里的两条 SQL 清单交叉核对。
     */
    private static final Map<String, String> NOT_REGISTERED = buildNotRegisteredReasons();

    private static Map<String, String> buildNotRegisteredReasons() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bp", "血压：PIPL 明确列为医疗健康敏感信息，但架构规格书 §8.1 已裁定"
                + "【不采集】该指标（『不因 SDK 暴露就建字段；血糖/女性健康/血压/GPS/联系人一律不建』）。"
                + "该取值留在 CHECK 里是为『将来若确需采集则不必改表』，"
                + "🛑 若启用采集，必须先把它登记进 SensitiveField 并加入本表以外的加密侧 —— "
                + "本条登记是『当前不采集』的承诺，不是『血压不敏感』的判断。");
        m.put("temp", "体温：同 bp —— 属敏感但已裁定不采集。"
                + "本条同样是『当前不采集』的承诺，不是『体温不敏感』的判断。");
        m.put("pressure", "压力值：设备侧派生的活动/恢复指标（0-100），"
                + "不构成 PIPL 的『医疗健康』类别，不用于任何判定结论。");
        m.put("met", "MET（代谢当量）：运动强度派生值，属活动量而非健康指标。");
        m.put("mai", "MAI（活力指数）：设备厂商的复合活动分，非医疗指标。");
        m.put("respiration", "呼吸率：⚠️ 本项是【最需要复核】的一条 —— "
                + "呼吸率在临床语境下属医疗健康数据（PIPL 敏感）。"
                + "当前未登记的理由是：手环侧呼吸率为静息估算值、仅作活动恢复参考，"
                + "且契约 E2/E3 未给它任何判定用途。"
                + "🛑 若将来呼吸率进入任何评估量表或判定链，必须立即登记并纳入加密 —— "
                + "本条作为待复核项显式留下，不作『已定性为非敏感』的结论。");
        m.put("exercise", "运动类型标签：类别型数据，无个体健康推断力。");
        // 🛑 此处【不】登记 'sport'：它不是 band_telemetry.metric 的取值，
        //    而是契约 §4.3 游标型幂等键里的字面量（见类头"修正记录"）。
        //    登记它会让两侧合计 14 ≠ V3 的 13，而门禁会立刻红 —— 这正是期望行为。
        return Map.copyOf(m);
    }

    /** 需要加密的 metric 集合（登记项的键集）。 */
    public static Set<String> encryptedMetrics() {
        return new TreeSet<>(BY_METRIC.keySet());
    }

    /** 不加密的 metric 集合（未登记项的键集）。 */
    public static Set<String> notEncryptedMetrics() {
        return new TreeSet<>(NOT_REGISTERED.keySet());
    }

    /**
     * 两侧并集 = {@code band_telemetry.metric} 的全部合法取值（应为 <b>13</b> 个）。
     *
     * <p>供门禁做交叉断言用 —— 与 V3 的 CHECK、与 V12 迁移里的两条 SQL 清单三方核对。
     * 单独给出这个方法而不是让测试自己拼 {@code encryptedMetrics() ∪ notEncryptedMetrics()}，
     * 是因为"并集口径"本身也需要被固定下来：若某天有人新增了一个"第三类"metric
     * （既非加密也非不加密），它必须出现在本方法里，否则门禁会静默漏掉它。
     */
    public static Set<String> allMetrics() {
        Set<String> all = new TreeSet<>(BY_METRIC.keySet());
        all.addAll(NOT_REGISTERED.keySet());
        return all;
    }

    /** 未登记项 → 理由（供证据输出与审阅）。 */
    public static Map<String, String> notRegisteredReasons() {
        return NOT_REGISTERED;
    }

    /**
     * 该 metric 的标量值（{@code value_enc} 列）是否必须加密。
     *
     * <p>fail-closed 方向：<b>未登记于任一侧</b>的 metric 一律抛错，
     * 而不是默默按"不敏感"处理。新增 metric 而忘记更新本表 ⇒ 写入直接被拒，
     * 症状立刻可见；反之（默认不加密）则是一个安静的数据泄漏。
     */
    public static Optional<SensitiveField> sensitiveFieldOf(String metric) {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric 为空，无法判断是否需加密");
        }
        Optional<SensitiveField> hit = Optional.ofNullable(BY_METRIC.get(metric));
        if (hit.isPresent()) {
            return hit;
        }
        if (NOT_REGISTERED.containsKey(metric)) {
            return Optional.empty();
        }
        throw new IllegalStateException(
                "metric '" + metric + "' 既不在加密登记表、也不在未登记说明表 —— "
                        + "新增 metric 取值必须同时更新 TelemetrySensitivity，"
                        + "否则该指标会安静地以【明文】落库（B-1 要堵的正是这条路径）");
    }

    /** 该 metric 的标量值是否需加密。 */
    public static boolean requiresEncryption(String metric) {
        return sensitiveFieldOf(metric).isPresent();
    }

    /**
     * {@code sleep_json} 列（睡眠分期）是否需加密。
     *
     * <p>单独给出这个方法，是因为 {@code sleep_json} 的敏感性<b>不由 metric 决定</b>：
     * 理论上任何 metric 都可以带一个结构化明细。契约把结构化明细的承载位固定为
     * {@code sleep_json} 且语义是睡眠分期 ⇒ 只要它非空就必须加密，
     * 无论当行的 metric 是什么。把这条判断写成独立方法，
     * 是为了避免"metric=steps 所以整行都不加密、连带的 sleep_json 也漏了"。
     */
    public static boolean requiresEncryptionForSleepDetail(String sleepJson) {
        return sleepJson != null && !sleepJson.isBlank();
    }

    /** {@code sleep_json} 对应的登记项。 */
    public static SensitiveField sleepDetailField() {
        return SensitiveField.SLEEP_STAGES;
    }
}