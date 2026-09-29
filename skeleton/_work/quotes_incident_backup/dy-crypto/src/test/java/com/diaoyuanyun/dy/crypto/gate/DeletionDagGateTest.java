package com.diaoyuanyun.dy.crypto.gate;

import com.diaoyuanyun.dy.crypto.field.SensitiveField;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyDestroyedException;
import com.diaoyuanyun.dy.crypto.key.SubjectKeyRef;
import com.diaoyuanyun.dy.crypto.shred.DeletionDag;
import com.diaoyuanyun.dy.crypto.shred.DerivedStoreRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DoD ⑥ 删除 DAG 门禁 —— <b>顺序是安全性质</b>的可执行证据。
 *
 * <h1>本类要证明的唯一命题</h1>
 * <b>DEK 销毁必须是最后一步，且其前置是"全部衍生存储已确认处理"。</b>
 *
 * <p>证明方式不是"读一遍代码觉得顺序对"，而是两条互补的断言：
 * <ol>
 *   <li><b>衍生存储未确认时，DAG 必须拒绝销毁 DEK</b> —— 且此时数据<b>仍可解密</b>
 *       （"删除未完成"这一状态是可观察的）；</li>
 *   <li><b>全部确认后，DAG 才销毁 DEK</b> —— 且此后再也解不开。</li>
 * </ol>
 * 第 ① 条尤其重要：一个"无论如何都会销毁"的实现能通过第 ② 条，
 * 却会把"删除未完成"静默变成"删除完成但衍生存储残留" —— 那是最坏的结果。
 */
class DeletionDagGateTest {

    private static final StringBuilder EVIDENCE = new StringBuilder();

    private static final com.diaoyuanyun.dy.crypto.envelope.SubjectRef CUST =
            com.diaoyuanyun.dy.crypto.envelope.SubjectRef.customer(
                    CryptoTestHarness.TENANT_A, CryptoTestHarness.CUSTOMER_1);

    @AfterAll
    static void dump() {
        CryptoTestHarness.dumpEvidence("deletion-dag-evidence.txt", EVIDENCE);
    }

    @Test
    @DisplayName("DoD6 数据地图：衍生存储清单必须覆盖架构规格书 §8.4 点名的七类")
    void derived_store_registry_covers_the_named_seven() {
        // 规格书 §8.4 原文点名的七类：主库/备份/日志/缓存/索引/数仓/第三方处理方。
        // 少一类就意味着"删除权已宣告完成，而某一类存储里还留着副本"。
        //
        // 断言用【包含】而不是【相等】：本清单有意多登记一项 audit_log ——
        // 它不是规格书列举的那七类之一，但它与删除权直接冲突（append-only 不可删），
        // 恰恰是"最容易被读漏、必须显式登记"的那一项。
        // 用相等断言会把这个【正确的多余】判成失败，等于逼着人把审计日志从清单里删掉。
        Set<String> required = new LinkedHashSet<>(
                java.util.List.of("primary_db", "backup", "application_log", "cache",
                        "search_index", "analytics_warehouse", "third_party_processor"));
        Set<String> declared = new LinkedHashSet<>();
        for (DerivedStoreRegistry.DerivedStore s : DerivedStoreRegistry.all()) {
            declared.add(s.id());
        }
        assertTrue(declared.containsAll(required),
                "衍生存储清单缺少规格书 §8.4 点名的某些类 —— 漏项会让删除权的覆盖范围无法自证。"
                        + "缺少=" + required.stream().filter(r -> !declared.contains(r)).toList());
        assertEquals(required.size() + 1, declared.size(),
                "当前应恰好比规格书的七类多一项（audit_log，见上）。"
                        + "若新增了别的项，请确认它是【有意的登记】而不是随手加的。实际=" + declared);

        // 每一项都必须给出"为什么它在范围内"，否则清单退化成一张名字列表
        for (DerivedStoreRegistry.DerivedStore s : DerivedStoreRegistry.all()) {
            assertNotNull(s.why(), s.id() + " 缺少 why 说明");
            assertFalse(s.why().isBlank(), s.id() + " 的 why 为空");
        }

        // 审计日志的冲突点必须在清单里显式登记（它是本清单最容易被读漏的一项）
        DerivedStoreRegistry.DerivedStore audit = DerivedStoreRegistry.of("audit_log");
        assertNotNull(audit, "审计日志必须登记为衍生存储 —— 它按 ADR-09 不可删，"
                + "正因为与删除权冲突，更不能从清单里省略");
        assertTrue(audit.why().contains("冲突"),
                "审计日志的 why 应显式写出与 append-only 的冲突，实际: " + audit.why());
    }

    @Test
    @DisplayName("DoD6 删除 DAG：衍生存储未确认 → 【拒绝】销毁 DEK，且数据仍可解密")
    void dag_refuses_to_shred_when_derived_stores_are_unconfirmed() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        s.keyStore().getOrCreateDek(CUST);
        String envelope = s.cipher().encryptText(CUST, SensitiveField.HEART_RATE.fieldName(), "75");

        // 一个"什么都清不掉"的处理方：模拟衍生存储清理由外部系统负责、尚未就绪。
        DeletionDag.DerivedStoreHandler nothingDone = (storeId, ref) -> false;

        DeletionDag.DeletionReceipt receipt = s.deletionDag().execute(
                SubjectKeyRef.of(CUST, 1), nothingDone);

        assertFalse(receipt.completed(),
                "衍生存储全部未确认时却报告删除完成 —— 这是最坏的失败："
                        + "合规动作已宣告完成，而缓存/数仓/备份里还有可读副本");
        assertFalse(receipt.keyDestroyed(), "前置未完成时不得销毁 DEK");
        assertNotNull(receipt.refusalReason(), "应给出拒绝原因");
        assertFalse(receipt.pending().isEmpty(), "应列出待办步骤");
        // primary_db 不应出现在待办里：它不是处理方要清理的对象（见 DeletionReceipt.pending）。
        assertFalse(receipt.pending().stream().anyMatch(x -> "primary_db".equals(x.storeId())),
                "primary_db 不应出现在待办清单里 —— 它由 DEK 销毁处置，"
                        + "列进去会让重试方以为要去 DELETE 主库密文");
        assertFalse(receipt.primaryDbStep().done(),
                "拒绝时 primary_db 那一步（DEK 销毁）应为未完成");

        // 【关键断言】此时数据必须【仍可解密】。
        // 写成"反正报错了就算过"会让"提前销毁了 DEK"也通过 ——
        // 而提前销毁正是本 DAG 唯一要禁止的行为。
        assertEquals("75",
                s.cipher().decryptText(CUST, SensitiveField.HEART_RATE.fieldName(), envelope),
                "拒绝时 DEK 已被销毁 —— 说明 DAG 提前关掉了总闸，"
                        + "其余存储的清理从此失去定位能力");

        assertFalse(s.keyStore().isDestroyed(CUST), "拒绝时不应留下销毁墓碑");

        CryptoTestHarness.record(EVIDENCE,
                "衍生存储处理方全部返回 false（未确认）",
                "回执 completed=false + DEK 未销毁 + 数据仍可解密",
                "completed=" + receipt.completed() + "; keyDestroyed=" + receipt.keyDestroyed()
                        + "; 待办=" + receipt.pending().stream()
                        .map(DeletionDag.StepResult::storeId).toList()
                        + "; 拒绝原因=" + receipt.refusalReason());
    }

    @Test
    @DisplayName("DoD6 删除 DAG：衍生存储全部确认 → 才销毁 DEK，且此后不可解密")
    void dag_shreds_only_after_all_derived_stores_confirmed() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        s.keyStore().getOrCreateDek(CUST);
        String envelope = s.cipher().encryptText(CUST, SensitiveField.SLEEP_STAGES.fieldName(),
                "{\"deep\":190}");

        // 记录处理方被调用的顺序，用于断言"主库不在可清理清单里"（它由 DEK 销毁处置）
        java.util.List<String> purged = new java.util.ArrayList<>();
        DeletionDag.DerivedStoreHandler allDone = (storeId, ref) -> {
            purged.add(storeId);
            return true;
        };

        DeletionDag.DeletionReceipt receipt = s.deletionDag().execute(
                SubjectKeyRef.of(CUST, 1), allDone);

        assertTrue(receipt.completed(), "全部确认后应报告完成，实际: " + receipt.summary());
        assertTrue(receipt.keyDestroyed(), "全部确认后应销毁 DEK");
        assertEquals(null, receipt.refusalReason(), "成功完成时不应有拒绝原因");

        // 主库不应被当作"可清理项"交给处理方 —— 它由 DEK 销毁处置。
        // 若它出现在 purged 里，说明实现把主库也当成 DELETE 目标，
        // 那会与 ADR-11 的可回放/审计留痕要求冲突。
        assertFalse(purged.contains("primary_db"),
                "主库不应出现在可清理清单里（它由 DEK 销毁处置），实际调用顺序=" + purged);

        // 销毁后必须不可解密
        assertThrows(SubjectKeyDestroyedException.class,
                () -> s.cipher().decryptText(CUST, SensitiveField.SLEEP_STAGES.fieldName(), envelope),
                "DAG 报告完成后密文仍可解密 —— 删除未真正生效");
        assertTrue(s.keyStore().isDestroyed(CUST), "应留下销毁墓碑");

        CryptoTestHarness.record(EVIDENCE,
                "衍生存储处理方全部返回 true",
                "回执 completed=true + DEK 已销毁 + 数据不可解密 + 主库不参与清理",
                "completed=" + receipt.completed() + "; 清理顺序=" + purged
                        + "; 主库被当清理项=" + purged.contains("primary_db")
                        + "; 解密抛=SubjectKeyDestroyedException");
    }

    @Test
    @DisplayName("DoD6 删除 DAG：处理方【抛异常】必须被当作未完成，不得当作已完成")
    void handler_exception_is_treated_as_not_done() {
        CryptoTestHarness.Stack s = CryptoTestHarness.newStack();
        s.provisionTenant(CryptoTestHarness.TENANT_A);
        s.keyStore().getOrCreateDek(CUST);
        String envelope = s.cipher().encryptText(CUST, SensitiveField.HEART_RATE.fieldName(), "68");

        // "无法确认"与"已确认删除"是两件事。把抛异常当作已完成，
        // 等于让一次网络抖动把删除权变成"看起来完成了"。
        DeletionDag.DerivedStoreHandler throwing = (storeId, ref) -> {
            if ("analytics_warehouse".equals(storeId)) {
                throw new IllegalStateException("数仓连接超时");
            }
            return true;
        };

        DeletionDag.DeletionReceipt receipt = s.deletionDag().execute(SubjectKeyRef.of(CUST, 1), throwing);

        assertFalse(receipt.completed(), "处理方抛异常却报告完成 —— 一次网络抖动就能让删除权假完成");
        assertFalse(receipt.keyDestroyed(), "有未确认项时不得销毁 DEK");
        assertTrue(receipt.refusalReason().contains("analytics_warehouse"),
                "拒绝原因应点名失败的那一项，实际: " + receipt.refusalReason());
        assertEquals("68",
                s.cipher().decryptText(CUST, SensitiveField.HEART_RATE.fieldName(), envelope),
                "拒绝时数据应仍可解密");

        CryptoTestHarness.record(EVIDENCE,
                "数仓处理方抛 IllegalStateException（连接超时），其余返回 true",
                "该步视为未完成 → 回执 completed=false + DEK 未销毁",
                "completed=" + receipt.completed() + "; 拒绝原因含 analytics_warehouse="
                        + receipt.refusalReason().contains("analytics_warehouse"));
    }

    @Test
    @DisplayName("DoD6 当前状态：[诚实标注] 多数衍生存储仍为『待人工确认』，不得声称已自动清理")
    void registry_honestly_reports_pending_stores() {
        // 这个测试断言的是一个【坏消息】，故意的。
        //
        // 【措辞纪律 —— 对外陈述必须按这一句】
        //   允许说："删除权的编排骨架（顺序 + 前置 + 回执）已就位，
        //            衍生存储的清理能力待逐项落地。"
        //   禁止说："衍生存储已全部自动清理"或"删除权已完整实现" ——
        //            那会把"登记位"说成"落地"。
        //
        // 本模块对 MANUAL_PENDING 的各项没有自动化清理能力（那些系统不在本仓范围内），
        // 因此这条断言把"还差多少"固化下来：将来落地一项，这个数字就该减一；
        // 若有人把状态改成 CONFIRMED_HANDLED 却没有真正的清理实现，
        // 这条测试不会红 —— 所以它只是"如实登记"，不是"防止说谎"的机制。
        // 真正防说谎的是删除 DAG：未确认就拒绝销毁（见上面两个测试）。
        int pending = DerivedStoreRegistry.pendingCount();
        assertTrue(pending > 0,
                "若 pendingCount 归零，请确认每一类存储都【真的有】清理实现，"
                        + "而不是把枚举改成了 CONFIRMED_HANDLED");

        assertEquals(1, DerivedStoreRegistry.all().stream()
                        .filter(s -> s.handling() == DerivedStoreRegistry.Handling.CONFIRMED_HANDLED)
                        .count(),
                "当前应恰好只有 primary_db 被确证处理（crypto-shredding 直接作用于它）");

        CryptoTestHarness.record(EVIDENCE,
                "盘点衍生存储的处置状态",
                "待人工确认 > 0（如实登记，不得声称已自动清理）",
                "待人工确认=" + pending + "/" + DerivedStoreRegistry.all().size()
                        + "; 已确证处理=" + DerivedStoreRegistry.all().stream()
                        .filter(s -> s.handling() == DerivedStoreRegistry.Handling.CONFIRMED_HANDLED)
                        .map(DerivedStoreRegistry.DerivedStore::id).toList()
                        + "; 外部处理方=" + DerivedStoreRegistry.all().stream()
                        .filter(s -> s.handling() == DerivedStoreRegistry.Handling.EXTERNAL_PROCESSOR)
                        .map(DerivedStoreRegistry.DerivedStore::id).toList());
    }
}