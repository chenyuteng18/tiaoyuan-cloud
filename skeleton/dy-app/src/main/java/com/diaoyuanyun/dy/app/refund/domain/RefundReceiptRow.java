package com.diaoyuanyun.dy.app.refund.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一行回执留痕（{@code refund_receipt} 的一行）—— 仓储与领域<b>共用</b>的值对象。
 *
 * <h2>为什么它必须住在 {@code domain}，而不是仓储的嵌套 record</h2>
 * 与 {@code ScaleItemRow} 同一套理由，但本域还多一层：
 * <ol>
 *   <li><b>同构定义会漂移</b>：字段在仓储、服务、控制器三处各写一份，加一个字段只改一处
 *       时<b>不报错</b>，只让"写进去的"与"读出来的"悄悄不一致。而本域"悄悄不一致"的后果
 *       是覆盖率分母少一块（见类注释末）。</li>
 *   <li><b>架构守卫 R2 会直接把嵌套 record 判违规</b>：控制器不得依赖数据访问层。
 *       若本 record 嵌在仓储里，S2-4 的 {@code RefundController} 一旦需要构造它
 *       （例如回执端点回渲染），就会踩 R2 —— 而那条规则的意图是"别绕过服务层"，
 *       不是"别用这个形状"。放进 {@code domain} 让"形状"与"层"解耦。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code state} 是 {@link ReceiptState} 而不是 {@code String}</h2>
 * 库里那一列是 {@code VARCHAR(32)}；读出来若原样传字符串，
 * 一个拼错的状态（例如被"顺手规范化"成半角括号的 {@code 未授权(转线下)}）
 * 会一路流到覆盖率计算里，被当成一个<b>第四态</b>静默计数 —— 而
 * {@link ReceiptInterpreter#coverageOfDist} 只在"未知键"上抛，
 * 对"多出来的合法字符串键"无能为力，因为那时它已不是键。
 * 故读路径在此处<b>立刻</b>做 {@link ReceiptState#parse}：库 CHECK 是最后一道，
 * 这里是"进内存的第一道"，两者都不可省。
 *
 * <h2>字段与 V6 建表的对应</h2>
 * <pre>
 *   receipt_id / refund_id / receipt_state / channel / template_id
 *   decided_at / pushed_at / failure_reason / operator_id / created_by
 * </pre>
 * 不含 {@code tenant_id}：租户上下文由 RLS 会话变量承载，不由行自身携带 ——
 * 让行对象携带租户 ID 会给"用 A 租户的行写进 B 租户"留下口子
 * （与 {@code ScaleItemRow} 同一决定）。
 *
 * @param receiptId     回执记录主键
 * @param refundId      所属退款工单
 * @param state         三态之一（读回时已 parse，不是裸字符串）
 * @param channel       回执通道（推送三态恒为 {@code 订阅消息}）
 * @param templateId    订阅消息模板 ID（"回执独占一个模板 ID"）
 * @param decidedAt     <b>判定时刻</b>：三态都落 —— 这是"未授权"能独立成立的关键字段
 * @param pushedAt      仅"已推送"有值（库层 CHECK {@code refund_receipt_pushed_at_iff_pushed}）
 * @param failureReason 仅"推送失败"有值
 * @param operatorId    操作人（证据链要求，写入侧强制非空）
 * @param createdBy     创建者标识（审计用）
 */
public record RefundReceiptRow(
        UUID receiptId,
        UUID refundId,
        ReceiptState state,
        String channel,
        String templateId,
        Instant decidedAt,
        Instant pushedAt,
        String failureReason,
        UUID operatorId,
        String createdBy) {
}