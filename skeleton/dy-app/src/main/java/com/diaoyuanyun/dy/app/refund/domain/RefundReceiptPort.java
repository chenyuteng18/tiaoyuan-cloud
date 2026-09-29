package com.diaoyuanyun.dy.app.refund.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 回执留痕的<b>持久化端口</b>（DIP：服务层依赖本端口，仓储层实现它）。
 *
 * <h2>为什么需要一个接口，而不是直接依赖 {@code RefundReceiptLedger}</h2>
 * 直接影响的是<b>可断言性</b>，而它恰好落在本域最关键的一条性质上：
 * <pre>
 *   未授权路径下，推送器【一次都不被调用】
 * </pre>
 * 这条性质只有在"能把持久化换成内存"的前提下才能被真实断言 ——
 * 否则每次验证都要起一个真库，而代价不是"慢"，是那次验证<b>不会有人反复跑</b>，
 * 于是它慢慢退化成"当初验证过一次"。
 *
 * <p>换掉持久化的能力还带来第二个好处："三态都落库"与"漏发视同未回执"
 * 这两条 P0-19 硬约束可以在<b>内存实现</b>上跑完整的场景（先未授权、后补告知），
 * 而不必先把一个演示租户灌进真库。
 *
 * <h2>🛑 端口刻意<b>只有</b>追加与查询 —— 没有 update / delete</h2>
 * append-only 纪律若只写在实现类上，换一个实现（例如测试用的内存实现）
 * 就能悄悄提供 {@code updateState}，而"不可删除"会在<b>测试路径上先失效</b>，
 * 再被生产路径模仿。故纪律写在<b>端口</b>这一层：任何实现都拿不到
 * "就地改写"这个动作，无论它是 JDBC、内存还是将来的别的什么。
 */
public interface RefundReceiptPort {

    /** 追加一条回执留痕（<b>只追加</b>；一张工单可有多条，每条都是事实）。 */
    void appendReceipt(String tenantId, RefundReceiptRow row);

    /** 追加一条转线下告知留痕（<b>只追加</b>）。 */
    void appendOfflineNotice(String tenantId, RefundOfflineNoticeRow row);

    /** 取某工单的全部回执留痕（按判定时刻稳定排序）。 */
    List<RefundReceiptRow> findReceipts(String tenantId, UUID refundId);

    /**
     * 该工单是否已有转线下告知留痕。
     *
     * <p>🛑 必须<b>只</b>看人工告知记录，不得把回执记录也算进来 ——
     * 否则"有没有人工告知"会退化成"有没有一行回执"，而每次推送都会写一行，
     * 于是它永远为真（P0-19 警告的"永远显示成功的开关"）。
     */
    boolean hasOfflineNotice(String tenantId, UUID refundId);

    /** 三态计数（<b>同一次查询</b>出三数，缺态补 0）。 */
    java.util.Map<ReceiptState, Long> countByState(String tenantId, Instant from, Instant to);

    /**
     * 端口方法集被钉死为 5 个（2 追加 + 3 查询）。
     *
     * <p>供门禁反射断言使用：新增方法必须<b>显式</b>改 {@code RefundVisibilityAndReceiptTest}
     * 里那条断言 —— 使"给账本加一个改写入口"变成一次必须表态的改动，而不是一次顺手加方法。
     *
     * <p>🛑 它<b>不在</b>反射扫描的范围内（{@code RefundReceiptPort#validateTenantId} 是
     * {@code static default} 形态的校验方法，被 {@code isDefault()} 与 {@code isStatic()} 排除）——
     * 这正是"钉死方法集"的手法：让反射可数，而不是靠人记得。
     */
    int PORT_METHOD_COUNT = 5;

    /**
     * 租户标识校验（<b>端口上的静态校验</b>，任何实现与调用方共用同一套白名单）。
     *
     * <p>它刻意放在端口上而不是实现类上：若它只在 JDBC 实现里，服务层为了"提前校验"
     * 就得 import 实现类 —— 那正是 DIP 被倒转的起点，而一旦服务层拿到实现类，
     * "换成内存实现"就不再可能，上面关于可断言性的那一段会整体失效。
     *
     * <p>口径与 {@code ScaleItemBankRepository} 一致：白名单字符集 + 固定 36 位，
     * 非法即抛 {@code TENANT_MISMATCH(2003/403)}（语义归属是租户不匹配，不是 500）。
     * 两处共用同一套正则的理由：口径分叉处最容易出现"一处放宽了、另一处没放"。
     */
    static void validateTenantId(String tenantId) {
        if (tenantId == null
                || !java.util.regex.Pattern
                .compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                .matcher(tenantId).matches()) {
            throw new com.diaoyuanyun.dy.common.exception.BizException(
                    com.diaoyuanyun.dy.common.result.ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID，拒绝执行退款留痕操作（白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }
}