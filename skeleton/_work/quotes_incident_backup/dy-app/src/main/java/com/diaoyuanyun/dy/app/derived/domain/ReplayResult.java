package com.diaoyuanyun.dy.app.derived.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次判定依据回放的<b>结果</b>（ADR-11 · S2-8）。
 *
 * <h2>🛑 它为什么住在 domain 而不是 service</h2>
 * 见 {@link ReplayOutcome} 的类注释：本类是一个不可变值对象，
 * 而 {@code ArchitectureBoundaryTest} 的 R4（DIP）禁止 record 依赖 service 包内的类型。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code driftedSegments} 恒为<b>保序</b>不可变视图（段顺序 = 指纹段顺序）；</li>
 *   <li>{@link #customerFacing()} 恒 {@code false} —— 它不是可设置的字段，
 *       而是一个恒定行为（见方法注释）。</li>
 * </ul>
 *
 * @param outcome          四态之一
 * @param storedVersion    当时落库的版本号
 * @param currentVersion   今天口径算出的版本号
 * @param driftedSegments  漂移段（段名 → "旧→新"的段级指纹）；空 = 无漂移或无从比对。
 *                         🛑 "无从比对"时也要非空（显式记一条"定位不到"的说明）——
 *                         返回空 Map 会被读成"没有漂移"
 * @param storedBranch     当时落库的分支
 * @param recomputedBranch 重算得到的分支；{@code null} = 未重算（漂移 / 域外 / 不可判）
 * @param branchMatches    分支是否一致（仅当真的重算过才有意义；未重算恒为 false）
 * @param note             人可读说明（必非空 —— 一条没有说明的回放结论无从复核）
 */
public record ReplayResult(
        ReplayOutcome outcome,
        String storedVersion,
        String currentVersion,
        Map<String, String> driftedSegments,
        String storedBranch,
        String recomputedBranch,
        boolean branchMatches,
        String note) {

    public ReplayResult {
        // 🛑 保序（与 ThresholdVersionFingerprint 同一纪律）：Map.copyOf 的迭代顺序不保证，
        //    而本字段会进端点出参（漂移段定位）。顺序不稳定会让同一份证据在不同 JVM
        //    启动间产出字节不同的响应体，给"同一输入同一输出"的复核带来无谓噪音。
        driftedSegments = Collections.unmodifiableMap(new LinkedHashMap<>(driftedSegments));
    }

    /** 是否可对外 —— 🛑 恒 {@code false}（Q10 口径②：verdict 整表为对内证据链）。 */
    public boolean customerFacing() {
        return false;
    }

    /** 结论码（供出参与断言）。 */
    public String outcomeCode() {
        return outcome.code();
    }
}