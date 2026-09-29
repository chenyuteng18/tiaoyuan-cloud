package com.diaoyuanyun.dy.app.identity.domain;

import java.util.UUID;

/**
 * 门店行（{@code store} 表的一行，只取契约 {@code Store} 下发的那三项）。
 *
 * <h2>🛑 为什么只取三项，而不是"把表的所有列都带上"</h2>
 * 契约 {@code Store schema} 逐字：
 * <pre>
 *   store_id       : string
 *   name           : string
 *   franchise_type : string, enum [直营, 加盟]
 * </pre>
 * 表里还有 {@code region_id} / {@code device_model} / {@code created_at} / {@code created_by}
 * 等列 —— 它们<b>不在</b>契约的 {@code Store} 里，故本 record 不携带。
 *
 * <p>这不是"少写几个字段"的洁癖，而是让"多下发一个字段"在<b>类型层面</b>不可能：
 * 若用 {@code Map<String,Object>} 装配，任何一次"顺手把 region_id 也放进去了"
 * 都不会报错，只会在某次契约比对时暴露 —— 而那时它已经在客户端被用上了。
 * 用 record 携带<b>恰好契约的字段</b>，使"响应体多一个键"这件事必须先改本 record。
 *
 * <h2>⚠️ {@code franchise_type} 是中文枚举，不是布尔</h2>
 * 契约的 enum 是 {@code [直营, 加盟]}（中文字面，V5 的 CHECK 约束同值）。
 * 🛑 不要把它转成 {@code is_franchise: boolean} —— 那会丢掉"本系统只承认这两个取值"
 * 这件事，且违反契约的 enum 声明（客户端按枚举做的分支会全部落空）。
 *
 * @param storeId       门店 ID（UUID）
 * @param name          门店名称
 * @param franchiseType 契约 enum 字面（{@code 直营} / {@code 加盟}）
 */
public record StoreRow(UUID storeId, String name, String franchiseType) {

    /** 契约 {@code Store.franchise_type} 的两个合法字面（与 V5 CHECK 同值）。 */
    private static final java.util.Set<String> FRANCHISE_TYPES = java.util.Set.of("直营", "加盟");

    /**
     * 紧凑构造器：断言三字段齐备且枚举合法。
     *
     * <p>🛑 在<b>值对象</b>处校验枚举，而不是在控制器出站时校验：
     * 出站校验意味着"坏值能一路走到响应装配点"，而那时它已经被算进
     * {@code total} 之类的聚合里。在值对象的构造期拦下，使"库里有个非法的
     * franchise_type"这件事在<b>读出的那一刻</b>就暴露 —— V5 的 CHECK 保证了
     * 正常路径不会发生，故一旦发生就说明"有人绕过了 CHECK"（例如手工 UPDATE），
     * 而这正是值得立刻失败的那种情况。
     */
    public StoreRow {
        if (storeId == null) {
            throw new IllegalArgumentException("门店行缺 store_id —— 契约 Store.store_id 必填");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "门店行缺 name（store_id=" + storeId + "）—— 契约 Store.name 必填");
        }
        if (franchiseType == null || !FRANCHISE_TYPES.contains(franchiseType)) {
            throw new IllegalArgumentException(
                    "门店行的 franchise_type 不在契约 enum 内: '" + franchiseType
                            + "'（store_id=" + storeId + "；契约 enum = [直营, 加盟]，"
                            + "与 V5 的 CHECK 同值）。出现此值说明有人绕过了库层 CHECK");
        }
    }
}