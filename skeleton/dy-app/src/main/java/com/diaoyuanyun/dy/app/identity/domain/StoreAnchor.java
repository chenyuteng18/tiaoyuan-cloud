package com.diaoyuanyun.dy.app.identity.domain;

import java.util.UUID;

/**
 * 请求者的<b>组织锚点</b> —— 解算 {@code store_ids} 与 A3 行级过滤所需的两个归属坐标。
 *
 * <h2>为什么需要它（而不是直接从 token 取）</h2>
 * 本骨架的 token 载荷是 {@code TenantClaims(tenantId, staffId, role, scope)} ——
 * 它说明"这个人被授予了多大范围"，但<b>不说明"从哪个点开始算"</b>。
 * 而 {@code own_store} 与 {@code region} 都是相对锚点的范围：
 * <pre>
 *   own_store → 锚点门店本身
 *   region    → 锚点门店所属辖区的全部门店
 *   all       → 全租户（不需要锚点）
 * </pre>
 * 锚点来自<b>员工档案</b>（{@code staff.store_id → store.region_id}），
 * 是一条服务端解算的事实，客户端无从提供、也<b>不得</b>提供
 * （否则调用方可以自选锚点 = 自选可见范围）。
 *
 * <h2>两个字段都可空，且"可空"本身是有信息量的</h2>
 * <ul>
 *   <li>{@code storeId} 为空 ⇒ 该员工档案里没有门店归属（新入职未分配 / 数据不完整）；</li>
 *   <li>{@code regionId} 为空 ⇒ 其门店未挂辖区（{@code store.region_id} 可空，见 V5 DDL）。</li>
 * </ul>
 * 🛑 两者都<b>不得</b>被读成"无限制"。由 {@link StoreScopeResolver#resolveStoreIds}
 * 在需要锚点而锚点缺失时<b>拒绝</b> —— 那比"给个默认范围"安全得多：
 * 默认范围无论取哪个值都是错的（取窄了静默少给，取宽了静默越权）。
 *
 * @param storeId  锚点门店 ID（可为 null）
 * @param regionId 锚点辖区 ID（可为 null；由 {@code store.region_id} 解出）
 */
public record StoreAnchor(UUID storeId, UUID regionId) {

    /** 无锚点（员工档案未挂门店）—— 供仓储层在"查无此人"时显式返回。 */
    public static StoreAnchor none() {
        return new StoreAnchor(null, null);
    }

    /** 是否可用于 {@code own_store} 范围。 */
    public boolean hasStore() {
        return storeId != null;
    }

    /** 是否可用于 {@code region} 范围。 */
    public boolean hasRegion() {
        return regionId != null;
    }
}