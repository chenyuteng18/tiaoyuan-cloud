package com.diaoyuanyun.dy.app.refund.domain;

/**
 * 退款域口径的<b>原始声明值</b> —— 从配置真相源读出、<b>尚未解析</b>的七段字面量。
 *
 * <h2>为什么必须与 {@code RefundPolicy} 分开（而不是直接返回已解析对象）</h2>
 * 与 {@code DerivedRawConfig} / {@code ScaleProfileSource} 同一条理由，但这里更硬：
 * 把"取值"与"解析 + 自洽校验"分成两件事，使<b>解析与校验只有唯一实现</b>
 * （{@link RefundPolicy#fromRawConfig}）。若每个来源实现各解析一遍，就各漏一个校验 ——
 * 而漏掉的那个校验正是"配置被改坏了却静默按新值执行"的口子。
 * 在退款域，这个口子的后果是具体的：{@code #38} 的
 * {@code effect_judgement_allowed} 若被改成 {@code true} 而无人报错，
 * 履约类直退就会开始吃效果判断 —— 即把一个"只看交付状态"的算法
 * 变成一个"看客户效果好不好"的算法，而那正是全行业刻意避开的地方（附录 C.7）。
 *
 * <h2>🛑 本记录不含任何默认值</h2>
 * 字段为 {@code null} / 空白即代表"这一段没取到"，由
 * {@link RefundPolicy#fromRawConfig} 判定为配置缺失并<b>抛出</b>，绝不回落。
 * 本域七段里有五段直接决定"客户能不能退、退多少、谁看得见"，
 * "取不到就用默认"会让一个无人复核的默认值进入面客判定。
 *
 * @param gateRulesJson       config {@code #10} {@code cfg:refund.gate_rules}（JSON：通路模式 + 双入口）
 * @param verdictLatency      config {@code #26} {@code cfg:refund.verdict_latency}（ENUM 字面，如 {@code "immediate"}）
 * @param retentionSlaHours   config {@code #27} {@code cfg:refund.retention_sla_hours}（INT 字面）
 * @param arrivalCommitmentDays config {@code #28} {@code cfg:refund.arrival_commitment_days}（INT 字面）
 * @param concessionThresholdJson config {@code #29} {@code cfg:refund.concession_approval_threshold}（JSON）
 * @param fulfillmentFormulaJson config {@code #38} {@code cfg:refund.fulfillment_direct_formula}（JSON）
 * @param visibilityJson      config {@code #40} {@code cfg:refund.visibility}（JSON：可见性矩阵）
 */
public record RefundRawConfig(
        String gateRulesJson,
        String verdictLatency,
        String retentionSlaHours,
        String arrivalCommitmentDays,
        String concessionThresholdJson,
        String fulfillmentFormulaJson,
        String visibilityJson) {

    /** 七段声明是否齐全（缺一即不可用于任何判定）。 */
    public boolean isComplete() {
        return notBlank(gateRulesJson) && notBlank(verdictLatency)
                && notBlank(retentionSlaHours) && notBlank(arrivalCommitmentDays)
                && notBlank(concessionThresholdJson) && notBlank(fulfillmentFormulaJson)
                && notBlank(visibilityJson);
    }

    /**
     * 缺失的段名清单（用于报错点名）。
     *
     * <p>与 {@code DerivedRawConfig#missingKeys} 同一条纪律：契约 P0-08「不得模糊报错」
     * 在配置侧同样成立 —— 运维看到「config #38 缺失」比看到「退款口径缺失」能直接动手。
     */
    public java.util.List<String> missingKeys() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!notBlank(gateRulesJson)) {
            missing.add("#10 cfg:refund.gate_rules");
        }
        if (!notBlank(verdictLatency)) {
            missing.add("#26 cfg:refund.verdict_latency");
        }
        if (!notBlank(retentionSlaHours)) {
            missing.add("#27 cfg:refund.retention_sla_hours");
        }
        if (!notBlank(arrivalCommitmentDays)) {
            missing.add("#28 cfg:refund.arrival_commitment_days");
        }
        if (!notBlank(concessionThresholdJson)) {
            missing.add("#29 cfg:refund.concession_approval_threshold");
        }
        if (!notBlank(fulfillmentFormulaJson)) {
            missing.add("#38 cfg:refund.fulfillment_direct_formula");
        }
        if (!notBlank(visibilityJson)) {
            missing.add("#40 cfg:refund.visibility");
        }
        return java.util.List.copyOf(missing);
    }

    /**
     * 自描述（<b>刻意不打印取值</b>，只打印段名与长度）。
     *
     * <p>取值里含可见性矩阵（哪些角色看得见退款）与直达公式的构成，
     * 属内部口径；整段进日志会让"日志泄漏内部规则"成为一条难以撤销的事实。
     * 排查所需的是"哪一段空、哪一段多长"，不是内容。
     */
    @Override
    public String toString() {
        return "RefundRawConfig{"
                + "#10=" + len(gateRulesJson)
                + ", #26=" + len(verdictLatency)
                + ", #27=" + len(retentionSlaHours)
                + ", #28=" + len(arrivalCommitmentDays)
                + ", #29=" + len(concessionThresholdJson)
                + ", #38=" + len(fulfillmentFormulaJson)
                + ", #40=" + len(visibilityJson)
                + ", complete=" + isComplete() + '}';
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String len(String s) {
        return s == null ? "null" : (s.isBlank() ? "blank" : s.length() + " chars");
    }
}