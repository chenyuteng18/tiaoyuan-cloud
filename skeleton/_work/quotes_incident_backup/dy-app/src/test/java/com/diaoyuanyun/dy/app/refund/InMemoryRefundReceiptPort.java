package com.diaoyuanyun.dy.app.refund;

import com.diaoyuanyun.dy.app.refund.domain.ReceiptState;
import com.diaoyuanyun.dy.app.refund.domain.RefundOfflineNoticeRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundReceiptRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回执留痕端口的<b>内存实现</b>（测试用）—— 它存在的唯一理由是让 P0-19 的关键性质可断言。
 *
 * <h2>它换来的是什么</h2>
 * 用真库跑 S2-3 的语义断言会带来两个代价，而第二个是致命的：
 * <ol>
 *   <li>慢 —— 可接受；</li>
 *   <li><b>于是它不会被人反复跑</b> —— 断言慢慢退化成"当初验证过一次"。
 *       而"未授权路径下推送器一次都不被调用"这条性质，恰恰是
 *       <b>最容易在重构中被顺手破坏</b>的一条（例如把两段式合并成一个方法、
 *       或"为了减少一次查询"把额度判断挪到推送之后）。</li>
 * </ol>
 * 内存实现让这条断言变成<b>每次构建都跑、毫秒级</b>的常规单测。
 *
 * <h2>🛑 它<b>没有</b> update / delete —— 与生产实现同一套纪律</h2>
 * append-only 若只在生产实现上成立，就会出现一种很难查的事故：
 * 测试路径上有人用内存实现"顺手修了一条状态"，于是那条路径的语义
 * 与生产不一致 —— 而测试是绿的，它证明的是<b>内存实现</b>的行为。
 * 故本类同样只提供追加与查询，且 {@code RefundReceiptLedgerAppendOnlyTest}
 * 会反射扫描本类的公开方法名。
 */
final class InMemoryRefundReceiptPort implements RefundReceiptPort {

    /** 按工单分组的回执留痕（追加序）。 */
    private final Map<UUID, List<RefundReceiptRow>> receipts = new LinkedHashMap<>();
    /** 按工单分组的转线下告知（追加序）。 */
    private final Map<UUID, List<RefundOfflineNoticeRow>> notices = new LinkedHashMap<>();

    @Override
    public void appendReceipt(String tenantId, RefundReceiptRow row) {
        RefundReceiptPort.validateTenantId(tenantId);
        if (row == null) {
            throw new IllegalArgumentException("回执留痕为空");
        }
        receipts.computeIfAbsent(row.refundId(), k -> new ArrayList<>()).add(row);
    }

    @Override
    public void appendOfflineNotice(String tenantId, RefundOfflineNoticeRow row) {
        RefundReceiptPort.validateTenantId(tenantId);
        if (row == null) {
            throw new IllegalArgumentException("转线下告知留痕为空");
        }
        notices.computeIfAbsent(row.refundId(), k -> new ArrayList<>()).add(row);
    }

    @Override
    public List<RefundReceiptRow> findReceipts(String tenantId, UUID refundId) {
        RefundReceiptPort.validateTenantId(tenantId);
        return List.copyOf(receipts.getOrDefault(refundId, List.of()));
    }

    @Override
    public boolean hasOfflineNotice(String tenantId, UUID refundId) {
        RefundReceiptPort.validateTenantId(tenantId);
        return !notices.getOrDefault(refundId, List.of()).isEmpty();
    }

    @Override
    public Map<ReceiptState, Long> countByState(String tenantId, Instant from, Instant to) {
        RefundReceiptPort.validateTenantId(tenantId);
        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        for (ReceiptState s : ReceiptState.values()) {
            out.put(s, 0L);
        }
        for (List<RefundReceiptRow> rows : receipts.values()) {
            for (RefundReceiptRow r : rows) {
                if (from != null && r.decidedAt().isBefore(from)) {
                    continue;
                }
                if (to != null && !r.decidedAt().isBefore(to)) {
                    continue;
                }
                out.merge(r.state(), 1L, Long::sum);
            }
        }
        return out;
    }

    /** 测试自证：本实现确实收到了若干行（防"没写进去所以断言假绿"）。 */
    int totalReceipts() {
        return receipts.values().stream().mapToInt(List::size).sum();
    }

    /** 测试自证：告知条数。 */
    int totalNotices() {
        return notices.values().stream().mapToInt(List::size).sum();
    }
}