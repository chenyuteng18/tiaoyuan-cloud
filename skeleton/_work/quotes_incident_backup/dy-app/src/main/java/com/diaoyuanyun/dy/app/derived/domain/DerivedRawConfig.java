package com.diaoyuanyun.dy.app.derived.domain;

/**
 * 派生口径的<b>原始声明值</b> —— 从配置真相源读出、<b>尚未解析</b>的一段字面量。
 *
 * <h2>为什么不直接返回已解析的 {@link DerivedMetricProfile}</h2>
 * 与 S1-4 同一条理由（{@code ScaleProfileSource} 类注释）：把"取值"与
 * "解析 + 自洽校验"分成两件事，使<b>解析与校验只有唯一实现</b>
 * （{@link DerivedMetricProfile#fromRawConfig}）。若每个来源实现各解析一遍，
 * 就会各漏一个校验 —— 而漏掉的那个校验正是"配置被改坏了却静默按默认值算"的口子。
 *
 * <h2>为什么是一个 record 而不是五个 String 参数</h2>
 * 五段声明来自配置表的<b>五个不同编号</b>（{@code #4/#5/#6/#7/#33/#45}），
 * 且分属三种值类型（{@code DECIMAL} / {@code ENUM} / {@code JSON} / {@code INT}）。
 * 用 record 承载可让"取到了哪几段"成为一个可打印的事实（{@link #toString} 进日志），
 * 排查"为什么算出来是 TBD"时能一眼看出是<b>哪一段没取到</b>。
 *
 * <p>🛑 本记录<b>不含任何默认值</b>：字段为 {@code null}/空白即代表"这一段没取到"，
 * 由 {@link DerivedMetricProfile#fromRawConfig} 判定为配置缺失并<b>抛出</b>，
 * 绝不回落。
 *
 * @param passThreshold     config {@code #4} {@code cfg:adherence.pass_threshold}（DECIMAL 字面，如 {@code "0.80"}）
 * @param weightsJson       config {@code #5} {@code cfg:adherence.weights}（JSON：AS_refund / AS_ops 两套权重）
 * @param missingPolicy     config {@code #6} {@code cfg:adherence.missing_policy}（ENUM 字面）
 * @param minSampleDays     config {@code #7} {@code cfg:adherence.min_sample_days}（INT 字面）
 * @param mcidThresholdJson config {@code #33} {@code cfg:verdict.mcid_threshold}（JSON）
 * @param confidenceJson    config {@code #45} {@code cfg:verdict.confidence_formula}（JSON）
 */
public record DerivedRawConfig(
        String passThreshold,
        String weightsJson,
        String missingPolicy,
        String minSampleDays,
        String mcidThresholdJson,
        String confidenceJson) {

    /** 六段声明是否齐全（缺一即不可用于计算）。 */
    public boolean isComplete() {
        return notBlank(passThreshold) && notBlank(weightsJson) && notBlank(missingPolicy)
                && notBlank(minSampleDays) && notBlank(mcidThresholdJson) && notBlank(confidenceJson);
    }

    /**
     * 缺失的段名清单（用于报错时点名，而不是只说"配置缺失"）。
     *
     * <p>契约/P0-08 的「不得模糊报错」在配置侧同样成立：运维看到
     * 「config #45 缺失」比看到「派生口径缺失」能直接动手。
     */
    public java.util.List<String> missingKeys() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!notBlank(passThreshold)) {
            missing.add("#4 cfg:adherence.pass_threshold");
        }
        if (!notBlank(weightsJson)) {
            missing.add("#5 cfg:adherence.weights");
        }
        if (!notBlank(missingPolicy)) {
            missing.add("#6 cfg:adherence.missing_policy");
        }
        if (!notBlank(minSampleDays)) {
            missing.add("#7 cfg:adherence.min_sample_days");
        }
        if (!notBlank(mcidThresholdJson)) {
            missing.add("#33 cfg:verdict.mcid_threshold");
        }
        if (!notBlank(confidenceJson)) {
            missing.add("#45 cfg:verdict.confidence_formula");
        }
        return java.util.List.copyOf(missing);
    }

    /**
     * 自描述（<b>刻意不打印取值</b>，只打印段名与长度）。
     *
     * <p>取值里含权重与阈值，属可配置业务参数；把它们整段打进日志会让
     * "日志泄漏内部口径"成为一条难以撤销的事实。排查所需的信息是
     * "哪一段空、哪一段有多长"，不是内容本身。
     */
    @Override
    public String toString() {
        return "DerivedRawConfig{"
                + "#4=" + len(passThreshold)
                + ", #5=" + len(weightsJson)
                + ", #6=" + len(missingPolicy)
                + ", #7=" + len(minSampleDays)
                + ", #33=" + len(mcidThresholdJson)
                + ", #45=" + len(confidenceJson)
                + ", complete=" + isComplete() + '}';
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String len(String s) {
        return s == null ? "null" : (s.isBlank() ? "blank" : s.length() + " chars");
    }
}