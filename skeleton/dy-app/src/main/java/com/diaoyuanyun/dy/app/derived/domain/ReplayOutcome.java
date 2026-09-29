package com.diaoyuanyun.dy.app.derived.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 判定依据回放的<b>结论四态</b>（ADR-11 · S2-8）。
 *
 * <h2>🛑 它为什么住在 domain 而不是 service</h2>
 * 本枚举与 {@link ReplayResult} 是一组<b>值对象</b>（无依赖、可独立测试），
 * 而 {@code ThresholdVersionReplay} 是<b>过程</b>（读快照、比版本、重算路由）。
 * 两者的变化原因不同：加一态是"复盘的语义变了"，改重算是"算法变了"。
 * 放在一起会让 {@link ReplayResult}（一个 record）依赖 service 包内的类型 ——
 * 而 {@code ArchitectureBoundaryTest} 的 R4（DIP）逐字禁止
 * 「领域模型（实体 / DO / record）不得依赖服务层」。
 * 本条不是形式主义：值对象能脱离服务被单测，是本域"判定链可在无数据库环境复核"
 * 这一验收形态的基础。
 *
 * <h2>四态的顺序即语义（不得调换）</h2>
 * {@code ThresholdVersionReplay.replay} 的判定顺序是
 * ① {@link #INCOMPARABLE_DOMAIN} → ② {@link #DRIFTED} → ③ {@link #REPRODUCED} / {@link #DIVERGED}。
 * 其中②排在③之前是本域最要紧的一处设计：一次"口径已变、而新旧口径恰好同结果"的重算
 * 只是巧合，把它说成可复现等于用巧合给漂移背书。
 */
public enum ReplayOutcome {

    /** 可复现：版本号一致 且 重算路由与落库一致。 */
    REPRODUCED("可复现"),

    /** 口径已漂移：版本号不一致（🛑 不重算路由 —— 见类注释）。 */
    DRIFTED("口径已漂移"),

    /** 同版本算出不同分支 ⇒ 算法漂移（指纹覆盖口径值、不覆盖算法）。 */
    DIVERGED("同版本不同分支"),

    /** 域外：本次判定由人录入（E5）或依据不完整，不由口径决定。 */
    INCOMPARABLE_DOMAIN("域外不可比");

    private final String code;

    ReplayOutcome(String code) {
        this.code = code;
    }

    /** 人话结论码（出参靠它做断言）。 */
    public String code() {
        return code;
    }

    /** 是否认定"该条判定可回放得出" —— 🛑 只有 {@link #REPRODUCED} 为真。 */
    public boolean isReproducible() {
        return this == REPRODUCED;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(ReplayOutcome::code).toList();
    }
}