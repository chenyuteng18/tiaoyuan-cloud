package com.diaoyuanyun.dy.security.visibility;

/**
 * 可见性角色（端角色）—— 契约 {@code x-roles} 的逐字映射。
 *
 * <h2>权威来源</h2>
 * 契约 {@code contract/openapi-v1.0.0.yaml} 根级 {@code x-roles}：
 * <pre>
 *   client   → token-role: client              end: mp
 *   therapist→ token-role: therapist           end: app
 *   meridian → token-role: meridian            end: app
 *   admin    → token-role: [manager, area, hq] end: web
 *   store_customer_service → token-role: null  end: null（无端，任何字段组均不成立）
 * </pre>
 *
 * <h2>与 {@code OrgLevel} 是两个正交的轴，不得合并</h2>
 * <ul>
 *   <li>{@code OrgLevel}（S1-3）= <b>数据范围</b>：本店 / 辖区 / 全量（{@code manager→own_store} 等）。</li>
 *   <li>{@code VisibilityRole}（S1-5）= <b>端角色</b>：客户 / 调理师 / 经络师 / 管理员。</li>
 * </ul>
 * 二者<i>看起来</i>重叠（都用 {@code manager}/{@code area}/{@code hq} 这些码），但语义不同：
 * 契约 {@code x-roles} 把三个 <b>token 角色</b>码折叠成一个 <b>端角色</b> {@code admin}，
 * 而 {@code OrgLevel} 把同样三个码摊成两个层级（area 一层、manager 与 hq 分属两层）。
 * 合并成一个枚举，就等于承认"端 = 层级"，而那会让
 * 「区域督导能不能看 ④派生结果」（端角色问题）与「区域督导能看多宽」（范围问题）
 * 再也分不开 —— 契约 §2.1 把行级 scope 单列成 A2 正是为了把这两件事分开。
 *
 * <h2>为什么 {@code admin} 是一个角色而不是三个</h2>
 * 契约 {@code x-visibility-matrix} 的列是<b>端角色</b>：{@code client}/{@code therapist}/
 * {@code meridian}/{@code admin}/{@code store_customer_service}。矩阵不含 hq/area/manager 三列，
 * 故"字段组可见性"这一层<b>不按层级收窄</b> —— 收窄由 A2 的行级 scope 承担（见矩阵附注
 * "admin 档位另按行级 scope 收窄"）。本类严格照此实现，<b>不自行把层级塞进字段组判定</b>。
 *
 * <h2>遗留大写码</h2>
 * 骨架早期代码用大写码（{@code SUPER_ADMIN} / {@code REGION_ADMIN} / {@code STORE_STAFF} /
 * {@code TENANT_ADMIN}）。与 {@code OrgLevel} 同一处置：<b>显式登记为 admin 的遗留别名</b>，
 * 并登记口径分叉为待裁定项 <b>T-11</b>。不做大小写不敏感归一（那会让分叉运行时隐形）。
 *
 * <h2>fail-closed</h2>
 * {@link #of(String)} 对未登记角色一律抛 {@link IllegalArgumentException}，
 * <b>绝不回落</b>。回落会让"角色码拼错"静默变成"这个角色什么都看不到"，
 * 与 PRD「未配置 / 配置非法即拒绝」冲突。
 * 调用方在"允许缺省"的场景应改用 {@link #tryOf(String)}。
 */
public enum VisibilityRole {

    /** 客户（小程序）。契约码 {@code client}。 */
    CLIENT("client", "客户", "mp"),

    /** 调理师（APP）。契约码 {@code therapist}。 */
    THERAPIST("therapist", "调理师", "app"),

    /** 经络师（APP）。契约码 {@code meridian}。 */
    MERIDIAN("meridian", "经络师", "app"),

    /**
     * 管理员（Web）。契约 {@code admin} 对应三个 token 角色 {@code manager} / {@code area} / {@code hq}。
     *
     * <p>{@code SUPER_ADMIN} / {@code REGION_ADMIN} / {@code STORE_STAFF} / {@code TENANT_ADMIN}
     * 为骨架遗留大写别名（T-11）。
     */
    ADMIN("admin", "管理员", "web", "manager", "area", "hq",
            "SUPER_ADMIN", "REGION_ADMIN", "STORE_STAFF", "TENANT_ADMIN");

    private final String contractCode;
    private final String label;
    private final String end;
    private final java.util.Set<String> tokenRoles;

    VisibilityRole(String contractCode, String label, String end, String... tokenRoles) {
        this.contractCode = contractCode;
        this.label = label;
        this.end = end;
        this.tokenRoles = new java.util.LinkedHashSet<>();
        this.tokenRoles.add(contractCode);
        this.tokenRoles.addAll(java.util.Arrays.asList(tokenRoles));
    }

    /** 契约 {@code x-roles} 里的端角色码（小写，对外权威）。 */
    public String contractCode() {
        return contractCode;
    }

    public String label() {
        return label;
    }

    /** 契约 {@code end}：{@code mp} / {@code app} / {@code web}。 */
    public String end() {
        return end;
    }

    /** 本端角色登记的 token 角色码（含遗留大写别名）。 */
    public java.util.Set<String> tokenRoles() {
        return java.util.Set.copyOf(tokenRoles);
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static VisibilityRole of(String tokenRole) {
        return tryOf(tokenRole).orElseThrow(() -> new IllegalArgumentException(
                "未登记的 token 角色无法确定端角色: "
                        + (tokenRole == null ? "<null>" : "\"" + tokenRole + "\"")
                        + "（已登记: " + allTokenRoles() + "）—— fail-closed，不回落默认端角色"));
    }

    /** 宽容解析：未登记返回空。 */
    public static java.util.Optional<VisibilityRole> tryOf(String tokenRole) {
        if (tokenRole == null || tokenRole.isBlank()) {
            return java.util.Optional.empty();
        }
        for (VisibilityRole r : values()) {
            if (r.tokenRoles.contains(tokenRole)) {
                return java.util.Optional.of(r);
            }
        }
        return java.util.Optional.empty();
    }

    /** 所有已登记 token 角色码（含别名）。用于错误信息与登记表断言。 */
    public static java.util.Set<String> allTokenRoles() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (VisibilityRole r : values()) {
            all.addAll(r.tokenRoles);
        }
        return all;
    }
}