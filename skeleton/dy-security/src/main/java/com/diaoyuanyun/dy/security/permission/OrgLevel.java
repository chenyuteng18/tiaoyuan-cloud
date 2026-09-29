package com.diaoyuanyun.dy.security.permission;

import com.diaoyuanyun.dy.tenancy.context.RowScope;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 组织层级（三级权限模型 · PRD §2.2）。S1-3 验收②的语义基点。
 *
 * <h2>权威来源</h2>
 * 层级划分与行级范围映射<b>逐条取自冻结契约 §2.1 A1/A2</b>
 * （{@code _work/contract-t6-api-freeze-2026-09-19.md} L153 / L180），不是自拟：
 * <pre>
 *   A1  role: 客户=client / 调理师=therapist / 经络师=meridian
 *             门店负责人=manager / 区域督导=area / 总部运营=hq
 *   A2  store_scope.row_level: 门店负责人 own_store / 区域督导 region / 总部 all
 * </pre>
 * 故「三级」↔ {@link RowScope} 的对应是契约已定事实：{@code manager→own_store}、
 * {@code area→region}、{@code hq→all}。本类只做<b>映射</b>，不重新定义口径。
 *
 * <h2>层级而不是"角色集合"：{@link #covers(OrgLevel)}</h2>
 * PRD §2.2 显式声明 {@code super_admin ⊃ 总部运营}（<b>强化上位子集，非新增平行第四层</b>）。
 * 因此角色→层级是<b>多对一</b>，层级之间天然有包含序，避免"每加一个角色就改一张二维矩阵"。
 *
 * <h2>🛑 登记口径：契约码为主，骨架遗留大写码为显式登记别名（不得静默）</h2>
 * 冻结契约用<b>小写短码</b>（{@code hq} / {@code area} / {@code manager} …）；
 * 而骨架早期代码（{@code PermissionRegistry} / {@code GateRegistry} 与 4 个 RLS 测试助手）
 * 用的是<b>大写码</b>（{@code SUPER_ADMIN} / {@code REGION_ADMIN} / {@code STORE_STAFF} /
 * {@code TENANT_ADMIN}）。两种码<b>同时存在于同一仓库</b>——这是一处真实的口径分叉。
 *
 * <p>处置（遵循硬纪律「<b>显式列出，不默默改</b>」）：
 * <ol>
 *   <li>两者都<b>显式登记</b>在本枚举里，逐一列出，可被静态审计 —— 不写"通配 / 兜底"；</li>
 *   <li>契约码是<b>对外权威</b>（login 下发、SDK 使用）；大写码标注为<b>遗留别名</b>；</li>
 *   <li>分叉本身登记为待裁定项 <b>T-11</b>（本类不代选，见 {@code 待裁定单/}）。</li>
 * </ol>
 * 之所以不直接删掉大写码：它们被 4 个真库测试与两个 Registry 使用，强行替换会把"口径分叉"
 * 变成"大面积编译失败"，反而掩盖问题本身。**把分叉摆到明面上，比假装它不存在更好。**
 * 同理，也<b>不做大小写不敏感的归一</b>——那会让分叉在运行时隐形，是最糟的处置。
 *
 * <h2>fail-closed 纪律（勿回退为"回落门店层"）</h2>
 * 与 {@link RowScope#fromCode} 同一纪律：<b>未登记的角色绝不回落</b>，一律抛
 * {@link TenantMismatchException}。回落会把<b>配置/契约错误</b>变成<b>静默的权限收窄</b>
 * ——某个角色码拼错，系统不报错，该角色只是"莫名什么都看不到"，运维侧极难归因，
 * 且与 PRD「未配置 / 配置非法即拒绝」字面冲突。**加角色必须过评审，不该被静默吸收。**
 *
 * <h2>刻意不在册的角色</h2>
 * {@code client}（客户）与 {@code GUEST} <b>不是组织层级</b>——它们不占组织位置
 * （契约 A1：客户无 {@code staff_id}），其约束由"仅本人"的独立机制承担。
 * 故 {@link #fromRole} 对它们<b>抛错</b>：这是正确行为，不是缺陷。
 * 调用方若在"允许缺省"的场景，应改用 {@link #tryFromRole}。
 */
public enum OrgLevel {

    /**
     * 总部层。契约码 {@code hq}（总部运营）；{@code SUPER_ADMIN} 为租户内最高权限
     * （PRD v1.28：{@code super_admin} = 租户内最高权限、不跨租户，为总部运营的强化上位子集）。
     */
    HEADQUARTERS("headquarters", "总部", RowScope.ALL, "hq", "SUPER_ADMIN", "TENANT_ADMIN"),

    /** 区域层。契约码 {@code area}（区域督导：可见、不审批 —— 业务方 2026-09-16 裁定）。 */
    REGION("region", "区域", RowScope.REGION, "area", "REGION_ADMIN"),

    /**
     * 门店层。契约码 {@code manager}（门店负责人）/ {@code meridian}（经络师）/ {@code therapist}（调理师）。
     *
     * <p>{@code meridian} 挂门店层：PRD P0-19 定其与门店负责人同为代录人集合；
     * 端形态为 APP 属"用哪个端"，与"数据可见层级"是两回事（后者才是 scope）。
     */
    STORE("store", "门店", RowScope.OWN_STORE,
            "manager", "meridian", "therapist", "STORE_STAFF");

    private final String code;
    private final String label;
    private final RowScope defaultRowScope;
    private final Set<String> roles;

    OrgLevel(String code, String label, RowScope defaultRowScope, String... roles) {
        this.code = code;
        this.label = label;
        this.defaultRowScope = defaultRowScope;
        this.roles = new LinkedHashSet<>(Arrays.asList(roles));
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 本层级登记的（含遗留别名）角色码，不可变视图。 */
    public Set<String> roles() {
        return Set.copyOf(roles);
    }

    /**
     * 本层级的<b>默认</b>行级范围 —— 逐条取自契约 A2（{@code hq→all} / {@code area→region} /
     * {@code manager→own_store}）。
     *
     * <p>注意"默认"二字：真实范围以 token 的 {@code store_scope} 为准（见 ADR-07「可见性由服务端解算」），
     * 本方法只用于"契约未给 store_scope 时的层级基线"与断言锚点。
     */
    public RowScope defaultRowScope() {
        return defaultRowScope;
    }

    /**
     * 角色 → 层级。<b>严格解析：未登记角色一律抛出，绝不回落。</b>
     *
     * @throws TenantMismatchException 当 role 为 null / 空串 / 未在任一层级登记时
     */
    public static OrgLevel fromRole(String role) {
        if (role != null) {
            for (OrgLevel lvl : values()) {
                if (lvl.roles.contains(role)) {
                    return lvl;
                }
            }
        }
        throw new TenantMismatchException(
                "未登记的角色无法确定组织层级: " + (role == null ? "<null>" : "\"" + role + "\"")
                        + "（已登记角色: " + allRoles() + "）—— fail-closed，不回落默认层级");
    }

    /**
     * 宽容解析：未登记角色返回空。供"允许缺省"的调用点显式选择，
     * 使"我要缺省行为"与"我忘了处理非法角色"在代码里可区分。
     */
    public static Optional<OrgLevel> tryFromRole(String role) {
        if (role == null) {
            return Optional.empty();
        }
        for (OrgLevel lvl : values()) {
            if (lvl.roles.contains(role)) {
                return Optional.of(lvl);
            }
        }
        return Optional.empty();
    }

    /**
     * 本层级是否覆盖（蕴含）{@code other} 的可见范围。
     *
     * <p>序：{@code HEADQUARTERS} ⊃ {@code REGION} ⊃ {@code STORE}。同级返回 {@code true}。
     * 用于判定"请求方层级是否不低于所需层级"。
     */
    public boolean covers(OrgLevel other) {
        if (other == null) {
            return false;
        }
        return this.ordinal() <= other.ordinal();
    }

    /** 所有已登记角色（含遗留别名）。用于错误信息与"登记表 ↔ 契约"断言锚点。 */
    public static Set<String> allRoles() {
        Set<String> all = new LinkedHashSet<>();
        for (OrgLevel lvl : values()) {
            all.addAll(lvl.roles);
        }
        return all;
    }
}