package com.diaoyuanyun.dy.crypto.shred;

import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>删除 DAG</b> —— 把"删除权请求"变成一组有顺序、有前置、有证据的步骤。
 *
 * <h2>顺序为什么是安全性质，而不是效率选择</h2>
 * 核心不变式：<b>DEK 销毁必须是最后一步</b>，且其前置是"全部衍生存储已确认处理"。
 * 见 {@link DerivedStoreRegistry} 的说明 —— 先销毁 DEK 会让其余存储的清理任务
 * <b>失去定位能力</b>，于是"删除完成"的回执与"数据仍在"的事实同时成立。
 * 这是本 DAG 唯一真正要紧的顺序约束，其他步骤之间的顺序可以调整。
 *
 * <h2>为什么 DAG 要显式拒绝"未确认就销毁"</h2>
 * 删除权的对外承诺是法律义务（PIPL）。一个"尽力而为"的删除流程，
 * 在出问题时无法回答"你们怎么保证删干净了"。把前置写成代码里的门，
 * 至少能保证：<b>凡是被宣告完成的删除，其每一步都有据可查</b>；
 * 凡是没做完的，流程会<b>明确拒绝</b>进入 DEK 销毁，而不是"跳过去继续"。
 *
 * <h2>⚠️ 本轮的诚实边界</h2>
 * 本 DAG 是<b>可执行的编排骨架</b>：它保证顺序与前置被强制，并产出回执。
 * 但它对 {@link DerivedStoreRegistry.Handling#MANUAL_PENDING} 的各项
 * <b>没有自动化清理能力</b>（那些系统不在本仓的范围内）。因此本轮的真实能力是：
 * "删除请求会走到这里、会被告知还差哪几步、在没差项时完成 DEK 销毁"。
 * 对外陈述不得说成"衍生存储已全部自动清理"。
 */
public final class DeletionDag {

    /** 单步处置结果。 */
    public record StepResult(String storeId, boolean done, String detail) {
    }

    /** 删除回执 —— 可落审计、可回复数据主体。 */
    public record DeletionReceipt(SubjectKeyRef ref, Instant requestedAt, List<StepResult> steps,
                                 boolean keyDestroyed, String refusalReason) {

        /** 是否已完整完成（含 DEK 销毁）。 */
        public boolean completed() {
            return keyDestroyed;
        }

        /**
         * 尚需处理方动作的步骤（用于重试 / 告知"还差什么"）。
         *
         * <p>{@code primary_db} 被<b>排除</b>：它不是处理方要清理的对象，
         * 而是"DAG 的最后一关"。把它列进来会让一个按 {@code pending()} 逐项重试的
         * 调用方去 "重试 primary_db 的清理" —— 那个动作不存在，且它的名字暗示
         * "要去 DELETE 主库密文"，与 ADR-11 的可回放要求相冲突。
         * 它的状态仍完整保留在 {@link #steps()} 里，需要时可按 id 查。
         */
        public List<StepResult> pending() {
            return steps.stream()
                    .filter(s -> !s.done() && !"primary_db".equals(s.storeId()))
                    .toList();
        }

        /** {@code primary_db}（DEK 销毁那一关）的状态。 */
        public StepResult primaryDbStep() {
            return steps.stream()
                    .filter(s -> "primary_db".equals(s.storeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("回执缺少 primary_db 步骤 —— 编排不完整"));
        }

        /** 供审计/日志的单行摘要（不含任何密钥材料）。 */
        public String summary() {
            long done = steps.stream().filter(StepResult::done).count();
            return "删除回执[" + ref.display() + "] 步骤 " + done + "/" + steps.size()
                    + " 完成；DEK 销毁=" + keyDestroyed
                    + (refusalReason == null ? "" : "；未销毁原因: " + refusalReason);
        }
    }

    private final com.diaoyuanyun.dy.crypto.key.SubjectKeyStore keyStore;

    /** 外部系统（缓存/数仓/备份...）的处理结果由调用方注入 —— 本模块无法自行探测它们。 */
    public interface DerivedStoreHandler {
        /**
         * 尝试清理一个衍生存储，返回"是否已确认完成"。
         *
         * <p>返回 {@code false} 表示未完成或无法确认 —— 两种情况都<b>必须</b>阻断 DEK 销毁。
         * 把"无法确认"当作"已完成"是本 DAG 最需要防的一种偷懒。
         */
        boolean purge(String storeId, SubjectKeyRef ref);
    }

    public DeletionDag(com.diaoyuanyun.dy.crypto.key.SubjectKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /**
     * 执行删除 DAG。
     *
     * <h3>步骤</h3>
     * <ol>
     *   <li>按 {@link DerivedStoreRegistry#all()} 的顺序逐项清理（<b>主库之外</b>的存储）；</li>
     *   <li>检查是否全部完成；</li>
     *   <li>全部完成 → 销毁 DEK（crypto-shredding），回执 {@code keyDestroyed=true}；</li>
     *   <li>有未完成 → <b>不销毁</b> DEK，回执里列出待办（{@code refusalReason}）。
     *       注意：此时主库密文仍可读 —— 这是<b>有意的</b>：宁可"删除未完成"
     *       也不"删除完成但衍生存储残留"。</li>
     * </ol>
     */
    public DeletionReceipt execute(SubjectKeyRef ref, DerivedStoreHandler handler) {
        Instant requestedAt = Instant.now();
        Map<String, StepResult> results = new LinkedHashMap<>();

        for (DerivedStoreRegistry.DerivedStore store : DerivedStoreRegistry.all()) {
            if ("primary_db".equals(store.id())) {
                // 主库不是"清理"步骤：它由最后的 DEK 销毁直接处置（密文保留、不可恢复）。
                // 把它排除在排序清理之外，避免有人误以为"要 DELETE 主库密文"
                // —— 那会与 ADR-11 的可回放要求和审计留痕相互冲突。
                results.put(store.id(), new StepResult(store.id(), false,
                        "由 DEK 销毁处置（密文保留、不可恢复）—— 不执行行删除"));
                continue;
            }
            boolean ok;
            String detail;
            try {
                ok = handler.purge(store.id(), ref);
                detail = ok ? "已确认处理" : "未确认处理（处理方返回 false）";
            } catch (RuntimeException e) {
                ok = false;
                detail = "处理失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            results.put(store.id(), new StepResult(store.id(), ok, detail));
        }

        List<StepResult> blocking = results.values().stream()
                .filter(r -> !r.done() && !"primary_db".equals(r.storeId()))
                .toList();

        if (!blocking.isEmpty()) {
            // 拒绝销毁。这是本 DAG 的核心行为，不是异常路径。
            results.put("primary_db", new StepResult("primary_db", false,
                    "因前置未完成而【拒绝】销毁 DEK —— 密文仍可读，删除尚未生效"));
            return new DeletionReceipt(ref, requestedAt, List.copyOf(results.values()), false,
                    "以下衍生存储未确认处理: " + blocking.stream().map(StepResult::storeId).toList()
                            + " —— 先销毁 DEK 会让这些存储的清理失去定位能力，"
                            + "故本 DAG 强制要求先清理后销毁");
        }

        // 全部前置完成 → 关总闸。这一步之后，任何遗漏的副本都变为不可恢复。
        keyStore.destroySubjectKey(ref);
        results.put("primary_db", new StepResult("primary_db", true,
                "DEK 已销毁 → 全部密文（含任何遗漏副本中的）不可恢复"));
        return new DeletionReceipt(ref, requestedAt, List.copyOf(results.values()), true, null);
    }
}