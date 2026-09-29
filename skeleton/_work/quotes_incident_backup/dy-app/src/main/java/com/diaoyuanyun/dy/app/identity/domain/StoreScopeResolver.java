package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A2 {@code store_scope} 的<b>唯一</b>解算点（ADR-07「可见性由服务端解算」）。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   AuthMeData.store_scope:  x-visible-to: [therapist, meridian, admin]
 *     row_level:  enum [own_store, region, all]
 *     store_ids:  array of string
 * </pre>
 * freeze §2.1 A2 的可见性表逐字：
 * <pre>
 *   store_scope.row_level：客户 n/a（仅本人）｜调理师·经络师 own_store
 *                        ｜门店负责人 own_store／区域督导 region／总部 all
 * </pre>
 *
 * <h2>🛑 两个来源必须显式取舍，不得"两个都试"</h2>
 * {@code row_level} 有两个可能的来源：
 * <ol>
 *   <li><b>token 的 {@code scope} 声明</b>（{@code TenantContext.scope()}）——
 *       "这个 token 被授予的范围"，由签发方写定；</li>
 *   <li><b>角色 → 层级的默认映射</b>（{@link OrgLevel#defaultRowScope()}）——
 *       "这个岗位通常的范围"。</li>
 * </ol>
 * 本类的取舍：<b>以 token 的 {@code scope} 为准，未声明时才回落角色默认值</b>；
 * 回落只在"该角色确实有默认范围"时发生（客户 / 未登记角色没有默认，一律拒）。
 *
 * <p>为什么不是"取两者中较宽的那个"：那会让"签发方把范围收窄"这件事<b>静默失效</b> ——
 * 某督导的 token 被收窄成 {@code own_store}（只被授权看一家店），角色默认却是
 * {@code region}；取较宽者会让他继续看到全区，且没有任何一处报错。
 * 反过来"取较窄者"虽更安全，但它让 token 声明与角色默认<b>同时生效</b> ——
 * 而契约 A2 只允许存在一个答案。
 *
 * <h2>🛑 token 声明比角色默认<b>宽</b>时一律拒（不是"取较窄"）</h2>
 * 若 token 说 {@code all} 而角色默认是 {@code own_store}，这不是"配置宽了一点"，
 * 而是<b>两个真相源不一致</b>。取较窄会把它变成一次静默的权限收紧
 * （"我记得我给过他全量"）；取较宽则是一次真实越权。
 * 本类选择<b>拒绝并点名两边的值</b>：让不一致在第一次调用时暴露，而不是让某一边悄悄赢。
 *
 * <h2>🛑 {@code store_ids} 的来源：锚点门店，不是 token</h2>
 * 本骨架的 token 载荷是 {@code TenantClaims(tenantId, staffId, role, scope)}
 * —— <b>没有</b> {@code store_ids}。故 {@code store_ids} 必须由服务端
 * <b>从锚点解算</b>：
 * <pre>
 *   own_store  → [ 该员工所属门店 ]              （staff.store_id）
 *   region     → [ 该员工所属门店的辖区内的门店 ]  （store.region_id = 锚点 region）
 *   all        → []  ← 🛑 刻意留空，见下
 * </pre>
 *
 * <p>🛑 <b>{@code all} 时 {@code store_ids} 为空数组是一个显式决定</b>，不是漏实现：
 * A2 是<b>声明</b>端点，不是数据面（契约逐字：「本接口只下发「档位布尔值」，
 * 不下发任何业务字段 —— 它是声明，不是数据」）。把全租户门店枚举进 A2 会让：
 * <ol>
 *   <li>一次登录相关调用退化成"拉全量组织台账"；</li>
 *   <li>下发一份<b>会过期的快照</b> —— 门店新增后客户端拿到的仍是旧列表，
 *       而它<b>不会报错</b>，只会少显示几家；</li>
 *   <li>与 A3 形成两个"门店列表真相源"。真实列表只有一个去处：<b>A3</b>。</li>
 * </ol>
 * 故 {@code row_level=all} ⇒ {@code store_ids=[]}，语义为「全量、不枚举」。
 * 客户端必须以 {@code row_level} 为准做分支，{@code store_ids} 只在其非空时用于收敛。
 * 这一点由 {@code AuthMeEndpointTest} 显式断言，避免它将来被"顺手补全"成一次全量枚举。
 *
 * <h2>fail-closed 与可选形态</h2>
 * {@link #resolve(String, String)} 解析不出即抛。A2 的做法是：
 * <b>客户根本不调用本类</b>（契约 {@code store_scope.x-visible-to} 不含 client）；
 * staff 侧必须解析成功 —— 解析不出即 403，<b>不返回空对象</b>
 * （空对象会被客户端读成"我没有范围限制"，方向恰好相反）。
 */
public final class StoreScopeResolver {

    private StoreScopeResolver() {
    }

    /**
     * 解算结果 —— {@code row_level} 与它为什么是这个值。
     *
     * @param rowLevel     契约 enum 之一（{@code own_store} / {@code region} / {@code all}）
     * @param source       本值来自"token 声明"还是"角色默认"（<b>供自描述与断言</b>，不参与授权）
     * @param declaredScope token 里声明的 scope 原文（可能为 null）
     */
    public record Resolved(RowScope rowLevel, Source source, String declaredScope) {

        /** 取值来源 —— 两来源语义不同，故必须可分辨（见类注释）。 */
        public enum Source {
            /** 来自 token 的 {@code scope} 字段（签发方写定）。 */
            TOKEN,
            /** 来自角色 → 层级的默认映射（{@link OrgLevel#defaultRowScope()}）。 */
            ROLE_DEFAULT
        }

        /** 契约 {@code row_level} 的字面（{@code RowScope.getCode()} 与契约 enum 逐字同值）。 */
        public String rowLevelLiteral() {
            return rowLevel.getCode();
        }
    }

    /**
     * 解算 {@code row_level}。
     *
     * @param tokenRole  令牌里的角色码（可含遗留大写别名）
     * @param tokenScope 令牌里的 {@code scope} 声明（可为 null —— 表示该 token 未声明范围）
     * @throws BizException / {@link TenantMismatchException} 角色未登记 / 两来源冲突
     */
    public static Resolved resolve(String tokenRole, String tokenScope) {
        OrgLevel level = OrgLevel.fromRole(tokenRole);
        RowScope roleDefault = level.defaultRowScope();

        Optional<RowScope> declared = RowScope.tryFromCode(
                tokenScope == null ? null : tokenScope.trim());

        if (declared.isEmpty()) {
            // token 未声明（或声明为空）。回落角色默认 —— 这是契约 A2 写明的映射：
            //   manager→own_store / area→region / hq→all
            return new Resolved(roleDefault, Resolved.Source.ROLE_DEFAULT, tokenScope);
        }

        RowScope token = declared.get();
        if (!isWithin(token, roleDefault)) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "组织层级与 token 声明的行级范围冲突：角色 '" + tokenRole
                            + "'（" + level.getLabel() + "）的默认范围为 "
                            + roleDefault.getCode() + "，而 token 声明为 " + token.getCode()
                            + "（更宽）。🛑 两种处置都被刻意排除："
                            + "① 取较宽者 = 一次真实越权，且无任何一处报错；"
                            + "② 取较窄者 = 把一次『两个真相源不一致』静默变成一次权限收紧，"
                            + "事后排查只能看到『数据好像少了』。"
                            + "故此处拒绝并点名两边的值 —— 若不是签发方笔误，"
                            + "请改角色 → 层级映射（OrgLevel）后重发 token");
        }
        return new Resolved(token, Resolved.Source.TOKEN, tokenScope);
    }

    /**
     * 宽容解析（供"我只想知道有没有"的调用点，如自描述端点）。
     *
     * <p>🛑 它<b>不得</b>用于任何授权判定 —— 返回空被当成"没有范围"会变成拒，
     * 被当成"默认范围"会变成放行，两种读法都与"我不知道"混淆。
     */
    public static Optional<Resolved> tryResolve(String tokenRole, String tokenScope) {
        try {
            return Optional.of(resolve(tokenRole, tokenScope));
        } catch (BizException e) {
            // 🛑 只捕 BizException：TenantMismatchException 是它的子类，
            //    写 multi-catch（`BizException | TenantMismatchException`）会编译失败
            //    ——「替代无法通过子类化关联」。这一点在本项目已踩过一次。
            return Optional.empty();
        }
    }

    /** 从当前请求上下文解算（{@link TenantContext}）—— 供服务层调用。 */
    public static Resolved resolveCurrent() {
        return resolve(TenantContext.role(), TenantContext.scope());
    }

    /**
     * 当前请求者的端角色（fail-closed）。
     *
     * <p>它是 A2 解算链的第一跳：token 角色 → 端角色。
     * 未登记角色报 <b>403（2003）</b>而不是 500 —— 语义是"身份不可信"，
     * 与 {@code OrgScopeGuard} 对未登记角色的处置同源。
     */
    public static VisibilityRole currentEndRole() {
        String role = TenantContext.role();
        if (role == null || role.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED,
                    "请求未携带 token 角色 —— A2 是可见性档位的权威下发点，不设匿名通道："
                            + "档位是契约冻结项，无身份即无从下发");
        }
        try {
            return VisibilityRole.of(role.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求携带的 token 角色未登记，无法确定端角色: '" + role + "'"
                            + "（已登记: " + VisibilityRole.allTokenRoles() + "）—— fail-closed，"
                            + "不回落默认端角色：回落会让一次角色码拼写错误"
                            + "静默变成『这个角色什么都看不到』");
        }
    }

    /**
     * 行级范围 {@code a} 是否<b>不宽于</b> {@code b}。
     *
     * <p>序：{@code OWN_STORE} ⊂ {@code REGION} ⊂ {@code ALL}（与 {@code OrgLevel.covers} 同序）。
     * <p>用 ordinal 而非字符串比较：{@code RowScope} 的声明顺序即范围序，
     * 字符串比较会把 {@code "all"} 判得比 {@code "own_store"} 小。
     */
    static boolean isWithin(RowScope a, RowScope b) {
        return a.ordinal() <= b.ordinal();
    }

    // ==================================================================
    // store_ids 解算（A2 下发 与 A3 过滤 复用同一份规则）
    // ==================================================================

    /**
     * 由 {@code row_level} + 锚点门店解算出应下发的 {@code store_ids}。
     *
     * <h2>为什么规则在这里，而不在服务层</h2>
     * A2 要把它<b>下发</b>，A3 要按同一份范围<b>过滤</b>。两处若各写一遍，
     * 就会出现"下发的列表与过滤用的列表不同"这种极难发现的形态 ——
     * 客户端按 A2 的列表展示门店，而 A3 实际返回的是另一批。
     *
     * <h2>🛑 锚点缺失即拒，不回落全量</h2>
     * {@code own_store} / {@code region} 都必须有一个锚点门店（{@code staff.store_id}）。
     * 锚点缺失说明"这个员工的档案里没有门店"，而框架绝不把它读成"那就给他全量吧" ——
     * 那是把一次<b>数据不完整</b>变成一次<b>越权</b>的最短路径。
     *
     * @param rowLevel 已解算的行级范围
     * @param anchor   请求者的锚点门店（可空）；{@code all} 时不使用
     * @return 去重保序的门店 ID 字面列表；{@code all} 时恒为<b>空列表</b>（见类注释）
     */
    public static List<String> resolveStoreIds(RowScope rowLevel, StoreAnchor anchor) {
        if (rowLevel == null) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "解算 store_ids 时行级范围为 null —— 无范围即无从枚举门店");
        }
        switch (rowLevel) {
            case ALL:
                // 🛑 刻意的空列表（"全量、不枚举"），理由见类注释第 3 节。
                return List.of();
            case OWN_STORE:
                return List.of(requireAnchorStore(anchor, rowLevel).toString());
            case REGION:
                // 区域范围需要一个锚点辖区；具体门店清单由 A3 在 SQL 侧按下发
                // 的 region 过滤（见 StoreRepository）——本方法不在这里枚举全区门店，
                // 理由与 ALL 同：A2 是声明端点，不应退化成组织台账导出。
                return List.of(requireAnchorRegion(anchor, rowLevel).toString());
            default:
                throw new BizException(ErrorCode.TENANT_MISMATCH,
                        "未登记的行级范围: " + rowLevel + " —— fail-closed，不回落任一范围");
        }
    }

    private static java.util.UUID requireAnchorStore(StoreAnchor anchor, RowScope level) {
        if (anchor == null || anchor.storeId() == null) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "行级范围 " + level.getCode() + " 需要锚点门店（staff.store_id），"
                            + "但请求者的员工档案里没有门店 —— 🛑 不得因此回落为『全量』："
                            + "那是把一次数据不完整变成一次越权的最短路径。"
                            + "请补齐员工 → 门店的归属后重试");
        }
        return anchor.storeId();
    }

    private static java.util.UUID requireAnchorRegion(StoreAnchor anchor, RowScope level) {
        if (anchor == null || anchor.regionId() == null) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "行级范围 " + level.getCode() + " 需要锚点辖区（staff.store_id → store.region_id），"
                            + "但请求者的门店未挂归属区域 —— 同上，不得回落为『全量』。"
                            + "请补齐门店 → 区域的归属后重试");
        }
        return anchor.regionId();
    }

    /**
     * 开查询<b>之前</b>断言锚点足以支撑该行级范围（A3 用；A2 走 {@link #resolveStoreIds}）。
     *
     * <h2>🛑 为什么服务层要单独调它一次，而不是等仓储抛</h2>
     * 仓储的 {@code filterOf} 在锚点缺失时<b>也会</b>抛（同一套判据），
     * 但那时已经开过一个事务、拼过一条 SQL。在服务层入口先断言一次，收益是：
     * <ol>
     *   <li>非法输入在开事务之前被拒（与 {@code ScaleItemBankRepository.requireUuid}
     *       的"事务外校验"同一条理由）；</li>
     *   <li>诊断消息更贴近成因 —— 服务层能说清"你的档案里没有门店归属"，
     *       而不是让调用方看到一句来自 SQL 构造处的通用拒绝。</li>
     * </ol>
     * 两处判据<b>必须同源</b>（都走本类），否则会出现
     * "服务层放行、仓储拒绝"或反之 —— 而两者都表现为 403，
     * 事后排查无法分辨是哪一层拒的。故本方法与仓储的 {@code filterOf} 共用
     * {@link #requireAnchorStore}/{@link #requireAnchorRegion}。
     *
     * <p>{@code all} 不需要锚点（全租户）；客户不走本路径。
     */
    public static void assertAnchorSatisfies(RowScope rowLevel, StoreAnchor anchor) {
        if (rowLevel == null) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "行级范围为 null，拒绝执行门店查询 —— fail-closed，不回落全量");
        }
        switch (rowLevel) {
            case ALL:
                return;
            case OWN_STORE:
                requireAnchorStore(anchor, rowLevel);
                return;
            case REGION:
                requireAnchorRegion(anchor, rowLevel);
                return;
            default:
                throw new BizException(ErrorCode.TENANT_MISMATCH,
                        "未登记的行级范围: " + rowLevel + " —— fail-closed，不回落任一范围");
        }
    }

    /**
     * 校验一批门店 ID 字面是否可安全用于 SQL 过滤。
     *
     * <p>与 {@code ScaleItemBankRepository.UUID_PATTERN} 同一套白名单：
     * PostgreSQL 的 {@code SET} 不支持绑定参数，且当门店 ID 需要拼进
     * {@code IN (...)} 的场合也必须先过字符集白名单。
     * 🛑 校验放在<b>服务层入口</b>（而非只在仓储内部），使非法值在开事务之前就被拒 ——
     * 与 {@code ScaleItemBankRepository.requireUuid} 的"事务外校验"同一条理由。
     */
    public static List<java.util.UUID> requireStoreIds(List<String> rawIds) {
        List<java.util.UUID> out = new ArrayList<>();
        for (String raw : rawIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                out.add(java.util.UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.TENANT_MISMATCH,
                        "门店 ID 非合法 UUID，拒绝用于行级过滤: '" + raw + "'");
            }
        }
        return out;
    }
}