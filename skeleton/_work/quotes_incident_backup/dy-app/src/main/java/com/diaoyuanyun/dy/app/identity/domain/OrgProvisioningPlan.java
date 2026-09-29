package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 组织开通的入参领域对象 —— 租户 / 区域 / 门店 / 员工四张表的写入意图。
 *
 * <h2>为什么用 record 而不是四个散参</h2>
 * 一次开通要建的是一棵<b>组织树</b>（租户 → 区域 → 门店 → 员工），
 * 四层之间靠 id 互相引用。散参最容易出的错是<b>参数顺序错位</b>：
 * 把 <em>regionId</em> 与 <em>storeId</em> 两个相邻的 UUID 写反时，
 * 编译器一句话都不会说，而库里会静静地建出一棵挂错父节点的树 ——
 * 直到某个门店在 A3 里查不到，才以"数据不对"的面目出现。
 * 用具名 record 后，这种错位在<b>编译期</b>就不成立。
 *
 * <h2>🛑 本类只做「形态校验」，不做「业务校验」</h2>
 * 这里只挡三类：必填缺失、枚举越界、id 重复。
 * <b>不</b>做的：不判 franchise_type 与 device_model 的组合是否合规、
 * 不判角色与门店的搭配是否合理、不判督导是否真的带过这个辖区 ——
 * 那些是<b>业务裁定</b>，其中一部分至今仍是卡点清单上的未决项。
 * 本类把它们原样送进数据库，由 CHECK 约束与业务评审各自负责。
 * 在这里"顺手加一条业务规则"的代价是：规则的真实来源变成一处代码注释，
 * 而产品文档里没有它 —— 那正是本仓 N 系列登记反复要防的形态。
 */
public record OrgProvisioningPlan(
        TenantSpec tenant,
        List<RegionSpec> regions,
        List<StoreSpec> stores,
        List<StaffSpec> staff) {

    /**
     * 契约层角色码 —— 与 {@code OrgLevel} 冻结的小写短码逐个对齐。
     *
     * <h2>🛑 为什么必须做这层映射，而不能把 {@code staff.role} 的中文值直接当角色码用</h2>
     * 这是本仓最容易踩的一处口径分叉，两侧都已在代码里冻结：
     * <ul>
     *   <li><b>数据库侧</b>（V5 DDL）：{@code staff.role CHECK (role IN ('店长','调理师','经络师','客服'))}
     *       —— 面向<b>门店人事</b>的可读枚举，是给人看的；</li>
     *   <li><b>契约/鉴权侧</b>（{@code OrgLevel} / {@code PermissionRegistry}）：
     *       {@code manager} / {@code therapist} / {@code meridian} / {@code client} /
     *       {@code area} / {@code hq} —— 面向 <b>token 与权限判定</b>的短码。</li>
     * </ul>
     * 二者<b>不是同一套值</b>：{@code staff.role='店长'} 对应的契约角色是 {@code manager}，
     * 而 {@code area}（区域督导）/ {@code hq}（总部运营）<b>在 staff.role 里根本没有对应枚举值</b>
     * —— 督导与总部不是"某家门店的员工"，它们是挂在 region / 租户层的角色。
     *
     * <p>若不显式映射，写库的人会顺手把契约码塞进 {@code staff.role}
     * （{@code 'manager'}），而这会被 CHECK 拒绝 —— 那还算好的；
     * 更坏的是反过来：有人给 CHECK 加值以"兼容"契约码，
     * 于是同一列里同时存在中文人事枚举与英文契约短码，
     * 而 A2 的权限判定会开始出现"这个店长到底算不算 manager"这种无法回答的问题。
     * 故本枚举是<b>唯一的翻译点</b>，两侧各自保持原样。
     */
    public enum StaffRole {
        /** 店长 —— 契约角色码 {@code manager}（行级范围 own_store）。 */
        STORE_MANAGER("店长", "manager"),
        /** 调理师 —— 契约角色码 {@code therapist}。 */
        THERAPIST("调理师", "therapist"),
        /** 经络师 —— 契约角色码 {@code meridian}。 */
        MERIDIAN("经络师", "meridian"),
        /**
         * 客服 —— 契约角色码<b>不存在</b>。
         *
         * <p>🛑 这不是遗漏：2026-09-16 业务已裁定「门店客服<b>无端、无系统账号</b>」，
         * 故本表<b>不产生客服行</b>（V5 DDL 注释逐字写明"role 枚举保留'客服'仅为枚举完整性，
         * 与'不建行'不矛盾"）。本枚举<b>有意不提供</b>该成员，
         * 使"建一个客服员工"在编译期就不成立 —— 业务裁定由此获得代码级强制。
         */
        NONE(null, null);

        private final String dbValue;
        private final String contractRole;

        StaffRole(String dbValue, String contractRole) {
            this.dbValue = dbValue;
            this.contractRole = contractRole;
        }

        /** 落 {@code staff.role} 列的值（中文人事枚举）。 */
        public String dbValue() {
            return dbValue;
        }

        /**
         * 对应的契约角色码 —— 供调用方<b>了解</b>该员工的鉴权归属，
         * <b>不</b>作为 {@code staff.role} 的写入值（见本枚举类注释）。
         */
        public String contractRole() {
            return contractRole;
        }
    }

    /** 租户规格。 */
    public record TenantSpec(String id, String name, String datastoreHint) {
        public TenantSpec {
            if (id == null || id.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通租户：id 必填");
            }
            if (name == null || name.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通租户：name 必填（tenant.name 有业务含义，不允许空名租户）");
            }
        }
    }

    /** 区域规格。{@code supervisorStaffId} 可空 —— 见习督导未定是合法状态（V5 该列可空）。 */
    public record RegionSpec(String id, String name, String supervisorStaffId) {
        public RegionSpec {
            if (id == null || id.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通区域：id 必填");
            }
            if (name == null || name.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通区域：name 必填");
            }
        }
    }

    /**
     * 门店规格。
     *
     * <p>{@code franchiseType} 与 {@code deviceModel} 的取值与 V5 的 CHECK 约束逐字对齐
     * （{@code 直营/加盟}、{@code 杠2/现有}）。此处显式枚举而非自由字符串，
     * 是为了让越界值在<b>进入数据库之前</b>就变成一条可读的 1001，
     * 而不是一条 PostgreSQL 的 {@code check constraint "store_franchise_type_check" ...}
     * ——后者对调用方不构成有效诊断（他不知道合法值是什么）。
     */
    public record StoreSpec(String id, String regionId, String name,
                            String franchiseType, String deviceModel) {
        public StoreSpec {
            if (id == null || id.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通门店：id 必填");
            }
            if (name == null || name.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通门店：name 必填");
            }
            if (!List.of("直营", "加盟").contains(franchiseType)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "开通门店：franchise_type 必须是 直营 / 加盟 之一（V5 CHECK 冻结），实际=" + franchiseType);
            }
            if (deviceModel != null && !List.of("杠2", "现有").contains(deviceModel)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "开通门店：device_model 必须是 杠2 / 现有 之一或留空（V5 CHECK 冻结），实际=" + deviceModel);
            }
        }
    }

    /**
     * 员工规格。
     *
     * <p>🛑 {@code staffId} 就是<b>登录主体 id</b>：{@code staff.staff_id} 同时是
     * A2 {@code /auth/me} 与 A3 {@code /stores} 解算行级范围的锚点
     * （见 {@code StoreRepository.findAnchor}），故它必须与 token 里的 subject 一致。
     * 开通时把两者建成同一个值，是"员工能登进来"这条链路成立的前提。
     */
    public record StaffSpec(String id, String storeId, StaffRole role) {
        public StaffSpec {
            if (id == null || id.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "开通员工：id 必填");
            }
            if (role == null || role == StaffRole.NONE) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "开通员工：role 必须是 店长 / 调理师 / 经络师 之一。"
                                + "客服【不可开通】—— 2026-09-16 业务裁定其无端、无系统账号，本表不产生客服行");
            }
        }
    }

    /**
     * 整个计划的<b>形态自检</b>：id 不得在层内或跨层重复。
     *
     * <h2>为什么这条值得单独写一个方法，而不是靠数据库主键冲突</h2>
     * 四张表的主键<b>各自独立</b>，故 {@code region_id} 与 {@code store_id} 相同
     * 在数据库层<b>完全合法</b> —— 不会有任何约束报错。但它几乎必然是调用方的笔误，
     * 且后果是隐蔽的：一次 {@code store_id = region_id} 的复制粘贴事故，
     * 会让门店与区域在日志与排障里长得一模一样，而所有断言都仍然通过。
     *
     * <p>故这里做一次跨层查重，把"两个实体的 id 撞了"变成一条立刻可见的 1001。
     * 这是本类唯一一处超出"单字段校验"的检查，它的理由与其它检查一致：
     * <b>能在这里廉价做到、且做错必然产生隐蔽后果的，就在这做</b>。
     */
    public void selfCheck() {
        List<String> ordered = new java.util.ArrayList<>();
        if (tenant != null) {
            ordered.add(tenant.id());
        }
        if (regions != null) {
            regions.forEach(r -> ordered.add(r.id()));
        }
        if (stores != null) {
            stores.forEach(s -> ordered.add(s.id()));
        }
        if (staff != null) {
            staff.forEach(s -> ordered.add(s.id()));
        }
        Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (String id : ordered) {
            seen.merge(id, 1, Integer::sum);
        }
        List<String> dup = new java.util.ArrayList<>();
        seen.forEach((id, n) -> {
            if (n > 1) {
                dup.add(id + " ×" + n);
            }
        });
        if (!dup.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "开通计划里存在重复 id —— 四张表主键各自独立，数据库不会拦，"
                            + "但跨层撞 id 几乎必然是调用方笔误，且后果隐蔽（两个实体在排障时无法区分）。重复：" + dup);
        }
        if (tenant == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "开通计划缺少租户（tenant 不可为空）");
        }
    }
}