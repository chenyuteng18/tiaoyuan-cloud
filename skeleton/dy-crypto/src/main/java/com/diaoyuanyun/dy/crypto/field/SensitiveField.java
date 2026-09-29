package com.diaoyuanyun.dy.crypto.field;

import java.util.List;

/**
 * 必须做字段级加密的<b>敏感个人信息字段名</b>登记表。
 *
 * <h2>为什么需要一份登记表，而不是"在调用点传字段名"</h2>
 * DoD ① 要求"心率 / 血氧 / 睡眠以 per-subject DEK 加密"。若字段名散落在各调用点，
 * 那么"哪些字段真的被加密了"就是一个只能靠 grep 猜的问题，
 * 而 grep 猜不出"某个新加的血氧字段忘了加密"这件事 —— 它会安静地以明文落库。
 *
 * <p>把字段名登记成枚举后，可以<del>对"所有敏感字段都走了加密路径"下断言</del>
 * —— 更精确地说，可以断言"<b>本登记表里的每一项</b>都能被加密/解密往返"，
 * 以及"派生字段/指标字段名的选择与本任务的扫描面词表一致"。
 *
 * <h2>与 ADR-12 扫描面的关系（易混淆，故写明）</h2>
 * 本模块的字段名与 {@code compliance/wordlists/scan3_derived.words} 里那组
 * 「forbidden health-metric field names」（{@code blood_sugar} / {@code uric_acid} /
 * {@code blood_pressure} / {@code body_temp}）是<b>两件不同的事</b>：
 * <ul>
 *   <li>扫描面管的是<b>客户端不得出现</b>这些名字（GTL1 SDK 能力溢出，不是我们的需求）；</li>
 *   <li>本表管的是<b>服务端需要对哪些字段加密</b>。</li>
 * </ul>
 * 两者有交集（都是"健康指标"），但判据完全不同，<b>不得</b>把本表当成扫描面的词源，
 * 也<b>不得</b>据此认为"没加密的指标就等于客户端不许出现"。此处显式区分，
 * 是为了避免将来有人把两份清单合并 —— 合并会立刻让一份合规约束失效。
 *
 * <h2>命名来源</h2>
 * 字段名取自 data-dict 的 {@code band_telemetry}（{@code sleep_json} / {@code hr} /
 * {@code resting_hr} / {@code spo2}）。DoD ① 点名的三项对应关系：
 * <ul>
 *   <li>心率 → {@link #HEART_RATE}（{@code hr}）、{@link #RESTING_HEART_RATE}（{@code resting_hr}）</li>
 *   <li>血氧 → {@link #BLOOD_OXYGEN}（{@code spo2}）</li>
 *   <li>睡眠 → {@link #SLEEP_STAGES}（{@code sleep_json}）</li>
 * </ul>
 * 另有 {@link #STEPS} 与 {@link #WORKOUT} 一并登记：它们同样是可识别的健康数据
 * （步数可推断行踪与身体状况），列进来是因为"是否加密"由数据性质决定，
 * 而不是由 DoD 里恰好点了几个名字决定。
 */
public enum SensitiveField {

    /** 心率（data-dict {@code band_telemetry.hr}）。 */
    HEART_RATE("hr", "心率"),

    /** 静息心率（{@code resting_hr}）。与心率分开登记 —— 不同列不同 AAD，不可互换。 */
    RESTING_HEART_RATE("resting_hr", "静息心率"),

    /** 血氧饱和度（{@code spo2}）。 */
    BLOOD_OXYGEN("spo2", "血氧"),

    /** 睡眠分期明细（{@code sleep_json}，jsonb）。 */
    SLEEP_STAGES("sleep_json", "睡眠分期"),

    /** 步数（{@code steps}）。 */
    STEPS("steps", "步数"),

    /** 运动记录（{@code workout}）。 */
    WORKOUT("workout", "运动记录");

    private final String fieldName;
    private final String label;

    SensitiveField(String fieldName, String label) {
        this.fieldName = fieldName;
        this.label = label;
    }

    /** 落库/AAD 里使用的字段名（与 data-dict 对齐，便于人工对账）。 */
    public String fieldName() {
        return fieldName;
    }

    /** 中文名，仅用于证据输出与报错信息，不参与任何密钥或认证串。 */
    public String label() {
        return label;
    }

    /** 按字段名查找；未登记返回 {@code null}（调用方可据此决定是否加密，fail-open 的部分不在此处）。 */
    public static SensitiveField of(String fieldName) {
        for (SensitiveField f : values()) {
            if (f.fieldName.equals(fieldName)) {
                return f;
            }
        }
        return null;
    }

    /**
     * DoD ① 点名的三项（心率 / 血氧 / 睡眠）。
     *
     * <p>单独给出这个方法，是为了让测试能<b>按 DoD 的措辞</b>断言，
     * 而不是自己挑几个字段凑一个通过的集合 —— 后者会让"被测对象"与"DoD 要求"脱钩。
     */
    public static List<SensitiveField> dodRequiredThree() {
        return List.of(HEART_RATE, BLOOD_OXYGEN, SLEEP_STAGES);
    }
}