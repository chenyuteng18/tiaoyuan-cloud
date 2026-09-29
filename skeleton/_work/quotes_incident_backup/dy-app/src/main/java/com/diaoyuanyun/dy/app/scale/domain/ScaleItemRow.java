package com.diaoyuanyun.dy.app.scale.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 题库中的一行（一道题）—— 仓储与服务<b>共用</b>的领域值对象。
 *
 * <h2>为什么把"行"从仓储 / 服务里抽出来独立成类</h2>
 * 初版把这一行分别定义在 {@code ScaleItemBankRepository} 与 {@code ScaleItemBankService} 内部
 * （两个同构 record）。那造成两个问题：
 * <ol>
 *   <li><b>包依赖成环</b>：仓储的 {@code insert(tenantId, service.ItemRow)} 使
 *       {@code repository → service}，而服务的读取路径是 {@code service → repository}。
 *       两个包互相依赖，ArchUnit 的层规则虽未直接禁止（现规则只禁
 *       controller→repository / service,domain→web / domain,record→service），
 *       但环一旦形成，"谁是下层"就无法回答了。</li>
 *   <li><b>两份同构定义会漂移</b>：加一个字段只改一处时<b>不报错</b>，
 *       只让"写进去的"与"读出来的"悄悄不一致。</li>
 * </ol>
 * 抽成本类后依赖是单向的：{@code repository → domain}、{@code service → domain}，
 * 且只有一份字面定义。
 *
 * <h2>字段来源</h2>
 * 与 {@code V4__scale_item_bank.sql} 的列一一对应（列名 → 字段名），
 * 字段名不含 {@code tenant_id}：租户上下文由 RLS 会话变量承载，
 * 而不是由这一行自身携带 —— 让行对象携带租户 ID 会给"用 A 租户的行写进 B 租户"留下口子。
 */
public record ScaleItemRow(
        UUID itemId,
        String ageGroup,
        String dimension,
        int itemNo,
        String itemText,
        String anchor0,
        String anchor1,
        String anchor2,
        String anchor3,
        String anchor4,
        String itemDirection,
        String version,
        String reviewerId,
        Instant reviewedAt,
        String createdBy) {
}