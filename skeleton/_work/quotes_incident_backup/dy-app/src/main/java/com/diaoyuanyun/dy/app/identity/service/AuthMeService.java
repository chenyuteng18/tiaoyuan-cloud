package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.domain.AuthMeDeclaration;
import com.diaoyuanyun.dy.app.identity.domain.BandFieldVisibility;
import com.diaoyuanyun.dy.app.identity.domain.BandVisibilityMatrix;
import com.diaoyuanyun.dy.app.identity.domain.StoreAnchor;
import com.diaoyuanyun.dy.app.identity.domain.StoreScopeResolver;
import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import java.util.List;
import java.util.UUID;

/**
 * A2 {@code GET /auth/me} —— <b>可见性档位的唯一权威下发点</b>（契约逐字）。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   A2 GET /auth/me   x-callable-roles: [client, therapist, meridian, admin]
 *                     x-client-forbidden: false
 *   description: ⚠️ 本接口只下发「档位布尔值」，不下发任何业务字段 —— 它是声明，不是数据。
 *                三端 UI 依此声明决定渲染分支，但字段仍由服务端裁剪
 *                （X-1：声明 ≠ 授权，二者须一致）。
 * </pre>
 *
 * <h2>🛑 它贴不贴 {@code @RequirePermission}：不贴，且这是刻意的</h2>
 * A2 的 {@code x-callable-roles} <b>含 {@code client}</b>，而 {@code PermissionRegistry}
 * <b>刻意不登记 client</b>（客户对 ④ 派生结果无档位，且 E4 的第二层防线正靠这一点成立）。
 * 两条事实叠加的结论是：<b>A2 一旦贴上功能权限码，客户查自己的可见性档位就会 403</b> ——
 * 而 A2 恰恰是客户端用来决定"渲染哪些区块"的接口，对它关掉等于让客户端什么都渲染不出来。
 *
 * <p>故 A2 的三层防线形态与其它端点<b>镜像相反</b>：
 * <ol>
 *   <li>{@code @StaffOnly} —— <b>不贴</b>（契约 {@code x-client-forbidden: false}
 *       ≠ {@code x-client-explicitly-denied: true}）；</li>
 *   <li>{@code @RequirePermission} —— <b>不贴</b>（理由见上）；</li>
 *   <li><b>可调用角色</b> —— 由本类的 {@link #requireCallable} 承担，
 *       走 {@link VisibilityRole#tryOf} 的 fail-closed 校验（未登记角色一律拒）。
 *       🛑 它报两个<b>不同</b>的错误码：无角色 ⇒ 401，角色不可信 ⇒ 403
 *       （依据是契约 A2 只声明 200/401 与 {@code OrgScopeGuard} 的既有处置，见方法注释）。</li>
 * </ol>
 * 🛑 不得为了"A2 也有权限码"而给 {@code client} 登记一个码：那会让
 * {@code PermissionCodeRegistrationGateTest} 的第 ③/④ 条防回退断言失去
 * "客户不持有任何域权限码"这条基线，而那条基线正是"给客户发码"必须显式经过评审的原因。
 *
 * <h2>解算链：三跳各有唯一落点</h2>
 * <pre>
 *   ① token 角色 → 端角色               {@link StoreScopeResolver#currentEndRole()}
 *                                      （唯一落点 = VisibilityRole.of）
 *   ② 端角色   → 四档档位（band_visibility）
 *                                      {@link BandVisibilityMatrix#rowForEndRole}
 *                                      （依据 = config #43，硬锁见 BandVisibilityMatrix）
 *   ③ 角色 + token scope → 行级范围（store_scope）
 *                                      {@link StoreScopeResolver#resolve}
 *                                      （两来源冲突即拒，见该类注释）
 * </pre>
 * 外加一项<b>跨域</b>解算：{@code refund_visibility} 由 {@link RefundVisibilityMatrix}
 * （config {@code #40}）解出，<b>不</b>与 {@code #43} 合并
 * （PRD / freeze §3.3 C-2 逐字：「#40 管退款（角色级二分）、#43 管手环数据
 * （角色 × 字段组四档），独立配置、不得合并」）。
 *
 * <h2>🛑 出站裁剪：两个键按 {@code x-visible-to} 决定"下不下发"，而非"下发什么值"</h2>
 * <pre>
 *   refund_visibility: x-visible-to: [meridian, admin]        ⇒ 客户 / 调理师【不下发该键】
 *   store_scope:       x-visible-to: [therapist, meridian, admin] ⇒ 客户【不下发该键】
 * </pre>
 * 「不下发」用 {@code null} + 装配时跳过（见 {@code AuthMeDeclaration.toContractData}），
 * 而<b>不是</b>下发一个 {@code false} 或空对象：
 * <ul>
 *   <li>{@code refund_visibility: false} 对客户下发 = 告诉他"存在这个档位" ——
 *       而契约的 {@code x-visible-to} 不含 client，即该键对他不存在；</li>
 *   <li>{@code store_scope: {}} 对客户下发会被读成"我没有范围限制"，方向与事实相反。</li>
 * </ul>
 *
 * <p>⚠️ 与之<b>相反</b>的一条：{@code band_visibility} 的四个布尔子键带
 * {@code x-visible-to: [client, therapist, meridian, admin]} —— <b>必须完整下发</b>
 * （含 {@code false}）。缺键会让客户端拿到 {@code undefined}，而它与 {@code false}
 * 是两条不同的渲染分支。两者的分野是<b>声明 vs 数据</b>。
 */
public class AuthMeService {

    private final BandVisibilityMatrix bandMatrix;
    private final RefundVisibilityMatrix refundMatrix;
    private final com.diaoyuanyun.dy.app.identity.repository.StoreRepository stores;

    public AuthMeService(BandVisibilityMatrix bandMatrix,
                         RefundVisibilityMatrix refundMatrix,
                         com.diaoyuanyun.dy.app.identity.repository.StoreRepository stores) {
        this.bandMatrix = bandMatrix;
        this.refundMatrix = refundMatrix;
        this.stores = stores;
    }

    /**
     * 解算当前请求者的声明体。
     *
     * <p>返回的是<b>可直接序列化</b>的声明 record；控制器只做 {@code Result.ok(...)}，
     * 不在控制器里做任何裁剪判断（裁剪的落点唯一，见类注释）。
     */
    public AuthMeDeclaration describeCurrent() {
        String tokenRole = TenantContext.role();

        // 第 ① 跳：token 角色 → 端角色（无角色即 401；角色不可信即 403）；并校验它在可调集合内
        requireCallable(tokenRole);

        // 🛑 顺序纪律：身份校验【必须先于】租户校验 —— 否则"无 token"会走到 requireTenant
        //    并抛 403（2003），而契约 A2 只声明 '200' 与 '401'，403 是契约未声明的分支。
        //    这两句曾经反序，被 DomainAEndpointsE2ETest 的真请求抓出（单测不会红）。
        String tenantId = requireTenant();

        VisibilityRole endRole = StoreScopeResolver.currentEndRole();

        // 🛑 requireCallable 与 currentEndRole 都用 VisibilityRole 解析同一个角色码，
        //    但两者【都必须留】：前者对 A2 的 x-callable-roles 负责（契约条款），
        //    后者是解算链的"角色 → 端角色"一跳（供后续两跳使用）。
        //    合并它们会让"契约可调集合"这件事失去独立落点 ——
        //    而它正是本端点对客户开放的全部依据。

        // 第 ② 跳：端角色 → 四档档位（config #43）
        BandFieldVisibility bandVisibility = bandMatrix.rowForEndRole(endRole);

        // 跨域：退款可见性（config #40，独立配置，不与 #43 合并）
        Boolean refundVisibility = resolveRefundVisibility(tokenRole, endRole);

        // 第 ③ 跳：角色 + scope → 行级范围；并解算应下发的 store_ids
        AuthMeDeclaration.StoreScopeView storeScope =
                resolveStoreScope(tenantId, endRole, tokenRole);

        return new AuthMeDeclaration(
                endRole.contractCode(), bandVisibility, refundVisibility, storeScope);
    }

    /**
     * 可调用角色校验（A2 的"第三层"—— 本类承担的那一层）。
     *
     * <h2>🛑 为什么它不查 {@code PermissionRegistry}</h2>
     * 因为注册表<b>刻意不登记 client</b>，而 A2 的契约角色集<b>含 client</b>。
     * 若用 {@code hasPermission} 判，客户会被拒 —— 而那正是本类要避免的。
     * 故判据取自<b>契约本身</b>（{@code x-callable-roles}）的落点
     * {@link VisibilityRole#tryOf}：四个端角色（含 client）全部通过，
     * 未登记角色一律拒。
     *
     * <h2>🛑 "无身份"与"角色不可信"是两个不同的错误码 —— 这一条是契约逐字决定的</h2>
     * <pre>
     *   A2 契约只声明两个响应：'200' 与 '401' ——【没有 '403'】。
     *   components.responses.Unauthenticated 定义逐字：
     *     "401 UNAUTHENTICATED（无 token / token 过期）"
     * </pre>
     * 故"未携带角色"（无 token ⇒ 过滤器不建立上下文）必须报 <b>401（1002）</b>。
     * 初版把它报成 403（2001），那是一个<b>契约未声明的状态码</b> ——
     * 客户端按契约只处理 200/401，拿到 403 会落进未定义分支。
     *
     * <p>反过来，"角色码不可信"（登记表里没有它）报 <b>403（2003）</b> ——
     * 它同样不在 A2 的声明里，但它的语义是"身份不可信"，
     * 与 {@code OrgScopeGuard} / {@code OrgLevel.fromRole} 对未登记角色的处置同源；
     * 而它与"无 token"区分开，使排查能一眼分辨"没带凭证"与"带了但我们不认"。
     *
     * <p>fail-closed：未登记即拒，<b>绝不回落</b> —— 与
     * {@code RefundAudienceRole.ofTokenRole} / {@code VerdictService.requireCallable}
     * 同一条纪律。回落会让一次角色码拼写错误静默变成一次权限判定，
     * 而真正的成因（有人用了未登记的角色）在日志里查无此事。
     */
    public VisibilityRole requireCallable(String tokenRole) {
        if (tokenRole == null || tokenRole.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED,
                    "请求未携带身份 —— A2 是可见性档位的权威下发点，不设匿名通道。"
                            + "🛑 本分支报 401（1002 UNAUTHENTICATED）而不是 403："
                            + "契约 A2 只声明 '200' 与 '401'（无 token / token 过期），"
                            + "报 403 会让客户端落进契约未声明的分支"
                            + "（filter 对无 Authorization 的请求不建立上下文，故此处角色为空）");
        }
        return VisibilityRole.tryOf(tokenRole.trim())
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_MISMATCH,
                        "token 角色不在 A2 可调集合内: '" + tokenRole + "'"
                                + "（契约 A2 的 x-callable-roles: [client, therapist, meridian, admin]；"
                                + "admin 展开 = " + VisibilityRole.ADMIN.tokenRoles() + "）—— "
                                + "报 2003 TENANT_MISMATCH，语义是『身份不可信』，"
                                + "与上一条的『无 token』（401）刻意区分："
                                + "让排查能一眼分辨『没带凭证』与『带了但我们不认』。"
                                + "🛑 不得回落为『拒绝下发』—— 那会让一个拼错的角色码"
                                + "静默变成一次权限收紧，而真正的成因在日志里查无此事"));
    }

    /**
     * 解算 {@code refund_visibility}，并按 {@code x-visible-to} 决定是否下发。
     *
     * <h2>🛑 它为什么<b>必须</b>走 {@link RefundVisibilityMatrix}，而不能自己写个 switch</h2>
     * 退款可见性的真相源是 config {@code #40}，而 {@code RefundVisibilityMatrix}
     * 在构造期已把三条硬锁断言过一遍（客户 / 调理师 / 门店客服恒不可见）。
     * 若本类自己按 token 角色写一个 {@code switch}，就会得到<b>第二份口径</b> ——
     * 它与矩阵在某次配置变更后必然分叉，而分叉的那一侧（A2 下发的档位）
     * 正是客户端用来决定"渲染不渲染退款区块"的依据。
     *
     * <h2>🛑 受众角色的分流必须走 {@link RefundAudienceRole#ofTokenRole}，<b>不</b>由角色名的字面或层级推断</h2>
     * 三点理由（详见 {@link #audienceOf}）：
     * <pre>
     *  ① meridian（经络师）的 OrgLevel 是 STORE —— 按层级推会被当成"门店负责人"。
     *     而 #40 里 meridian_therapist 与 store_admin 恰好都是 true ⇒ 该错误【不报错】；
     *  ② #40 刻意把 admin 摊成三键（manager / area / hq），因为退款可见性
     *     要在 admin 内部收窄（PRD §2.2「区域督导可见、不审批」）；
     *  ③ 唯一落点使"改一处配置、三端一致"成为结构性事实，而不是一次源码搜索。
     * </pre>
     *
     * <h2>三个"无档位"身份的处置</h2>
     * <pre>
     *   客户（client）      → 契约将 x-visible-to 定为不含 client ⇒ null（不下发）
     *   调理师（therapist） → 同上（契约的 x-visible-to 只有 meridian/admin）⇒ null
     *   经络师 / 管理角色   → 查矩阵 #40
     * </pre>
     * ⚠️ 调理师在 {@code #40} 里的值是 {@code false}，但契约的
     * {@code AuthMeData.refund_visibility.x-visible-to} <b>不含 therapist</b> ——
     * 故对他<b>不下发该键</b>，而不是下发一个 {@code false}。两件事的区别是
     * "告诉存在但为假" vs "对他不存在"，契约选的是后者。
     */
    private Boolean resolveRefundVisibility(String tokenRole, VisibilityRole endRole) {
        // 只有 meridian 与 admin 端角色才下发该键（契约 x-visible-to: [meridian, admin]）
        if (endRole != VisibilityRole.MERIDIAN && endRole != VisibilityRole.ADMIN) {
            return null;
        }
        RefundAudienceRole audience = audienceOf(tokenRole);
        return refundMatrix.canSee(audience);
    }

    /**
     * token 角色 → 退款受众角色（config {@code #40} 的键集）。
     *
     * <h2>🛑 必须走 {@link RefundAudienceRole#ofTokenRole}，<b>不得</b>按 {@link OrgLevel} 层级推</h2>
     * 这是本域最容易写错、且写错了<b>不报错</b>的一处，值得写清：
     * <pre>
     *   token 角色 meridian（经络师）：
     *     · 受众角色 = MERIDIAN_THERAPIST（#40 键 meridian_therapist）
     *     · OrgLevel  = STORE（门店层，与 manager 同级）
     *   若按层级分流 → 经络师被当成"门店负责人" → 拿到 store_admin 的档位
     * </pre>
     * 🛑 而在 {@code #40} 里 {@code meridian_therapist} 与 {@code store_admin}
     * <b>恰好都是 true</b> —— 故这个错误在今天的配置下<b>完全不报错</b>。
     * 它只会在某天有人把 {@code store_admin} 改成 false（或把经络师改成 false）时爆出来：
     * 表现是"改了门店负责人的档位，经络师跟着一起变" —— 而那一刻没人会想到是这里。
     *
     * <p>同理，{@code #40} 的键集<b>刻意把 admin 摊成三键</b>
     * （{@code manager→store_admin} / {@code area→area_supervisor} / {@code hq→headquarters_ops}），
     * 因为退款可见性<b>要在 admin 内部收窄</b>（PRD §2.2「区域督导可见、不审批」）。
     * {@code ofTokenRole} 正是这三个映射的唯一落点 —— 它也顺带覆盖
     * {@code client} / {@code therapist}（两者的 #40 值都是 false，本方法在其上游已被裁掉）。
     *
     * <h2>fail-closed：两个"不回落"</h2>
     * <ol>
     *   <li>{@code #40} 表里查不到该 token 角色 ⇒ 抛。
     *       ⚠️ <b>A-7（2026-09-26）之后这一条已收窄</b>：契约 {@code VisibilityRole.ADMIN}
     *       登记的那四个<b>遗留大写别名</b>（{@code SUPER_ADMIN} / {@code REGION_ADMIN} /
     *       {@code STORE_STAFF} / {@code TENANT_ADMIN}）现已在
     *       {@code RefundAudienceRole} 的别名层<b>显式登记</b>（见
     *       {@code RefundAudienceRole.legacyUppercaseAliases()}），故它们<b>不再</b>触发本错误 ——
     *       走完整链路会得到一个与 {@code manager/area/hq} 相同的档位。
     *       本处报 <b>5001（口径断裂）</b>的情形因此只剩一种：出现了一个
     *       <b>两侧都未登记</b>的新角色码（拼写错误，或契约侧新增了角色而 #40/别名层未同步）。
     *       语义仍是"两个配置口径的键集分叉了"，是一处需要有人去补登记的<b>配置缺陷</b>,
     *       不是"这个角色没权限"。</li>
     *   <li>🛑 绝不回落为 {@code false}：那会把上面的口径断裂伪装成一次权限收紧，
     *       事后排查只能看到"这个角色看不到退款"。</li>
     * </ol>
     */
    static RefundAudienceRole audienceOf(String tokenRole) {
        try {
            return RefundAudienceRole.ofTokenRole(tokenRole);
        } catch (BizException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "token 角色 '" + tokenRole + "' 在契约 A2 的 refund_visibility 可见角色范围内"
                            + "（端角色为 meridian 或 admin），"
                            + "但未登记进退款可见性受众表（config #40）—— 两个口径的键集分叉了。"
                            + "⚠️ A-7 裁定后，4 个遗留大写别名（SUPER_ADMIN / REGION_ADMIN / "
                            + "STORE_STAFF / TENANT_ADMIN）已在 RefundAudienceRole 的别名层显式登记、"
                            + "不再触发本错误；故此处若再报 5001，成因<b>不再是</b>那几个别名，"
                            + "而是出现了一个两侧都未登记的新角色码（拼写错误或契约侧新增未同步）。"
                            + "🛑 不得回落为 false：那会把一次配置断裂伪装成一次权限收紧，"
                            + "事后排查只能看到『这个角色看不到退款』", e);
        }
    }

    /**
     * 解算 {@code store_scope}，并按 {@code x-visible-to} 决定是否下发。
     *
     * <p>契约 {@code store_scope.x-visible-to: [therapist, meridian, admin]} ——
     * <b>客户不下发</b>（freeze §2.1 A2 表逐字：「客户 n/a（仅本人）」）。
     *
     * <p>故客户返回 {@code null}（= 该键不出现在响应里），而不是
     * {@code {row_level: "own_store", store_ids: [...]}} —— 后者会告诉客户
     * "你有一个行级范围"，而契约的事实是"客户根本没有行级范围这个概念
     * （他只能看本人）"。
     */
    private AuthMeDeclaration.StoreScopeView resolveStoreScope(String tenantId,
                                                               VisibilityRole endRole,
                                                               String tokenRole) {
        if (endRole == VisibilityRole.CLIENT) {
            return null;   // 客户 n/a（仅本人）—— 该键不出现在响应里
        }
        // 第 ③ 跳：两来源取舍 + 越宽拒绝
        StoreScopeResolver.Resolved resolved = StoreScopeResolver.resolveCurrent();
        RowScope rowLevel = resolved.rowLevel();

        // 锚点：staff.store_id → store.region_id（服务端解算，客户端无从提供）
        UUID staffId = parseStaffIdOrNull(TenantContext.staffId());
        StoreAnchor anchor = staffId == null
                ? StoreAnchor.none()
                : stores.findAnchor(tenantId, staffId);

        List<String> storeIds = StoreScopeResolver.resolveStoreIds(rowLevel, anchor);

        return new AuthMeDeclaration.StoreScopeView(resolved.rowLevelLiteral(), storeIds);
    }

    /**
     * 解析 token 里的 {@code staffId}。
     *
     * <p>🛑 客户<b>没有</b> {@code staff_id}（契约 A1 逐字：「客户无 staff_id（差异项）」），
     * 故它可空。但<b>不允许</b>把"非法的 staff_id"当空处理 —— 那会让一个坏 ID
     * 静默变成"没有锚点"，进而变成一次含义不明的范围判定。
     * 两个 {@link Outcome}：空 → {@code null}（合法：客户）；非法 → 抛（配置/签发错误）。
     */
    private static UUID parseStaffIdOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "token 里的 staff_id 不是合法 UUID: '" + raw + "' —— "
                            + "它不参与鉴权（鉴权只认 token 签名），但它是【锚点解算】的输入："
                            + "一个坏值会静默变成『没有锚点』，进而让行级范围的判定失去依据");
        }
    }

    /** 请求必须携带租户上下文（A2 不设无租户通道）。 */
    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— A2 不设无租户通道："
                            + "档位解算依赖租户内的组织锚点（staff → store → region）");
        }
        return tenantId;
    }

    /** 自描述（供端点自描述与断言，<b>不含</b>任何档位取值）。 */
    public java.util.Map<String, Object> describeRouting() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("endpoint", "A2 GET /auth/me（可见性档位唯一权威下发点）");
        m.put("callable_roles", List.of("client", "therapist", "meridian", "admin"));
        m.put("declared_responses", List.of("200", "401"));
        m.put("declared_responses_note",
                "契约 A2 只声明 '200' 与 '401' ——【没有 '403'】。"
                        + "components.responses.Unauthenticated 定义逐字："
                        + "『401 UNAUTHENTICATED（无 token / token 过期）』。"
                        + "故『无 token ⇒ 无角色』必须报 401（1002），报 403 会让客户端"
                        + "落进契约未声明的分支。『角色码不可信』报 403（2003），"
                        + "与『无 token』刻意区分，使排查能分辨『没带凭证』与『带了但我们不认』");
        m.put("permission_code_attached", false);
        m.put("permission_code_note",
                "🛑 A2 刻意不贴任何功能权限码：其 x-callable-roles 含 client，"
                        + "而 PermissionRegistry 刻意不登记 client。"
                        + "贴码会让客户查自己的档位时 403，而该接口正是客户端决定渲染分支的依据。"
                        + "可调用角色由服务层 VisibilityRole.tryOf 做 fail-closed 校验");
        m.put("staff_only_attached", false);
        m.put("staff_only_note",
                "契约 A2 是 x-client-forbidden: false 且无 x-client-explicitly-denied —— "
                        + "两个键语义不同，不得据前者贴 @StaffOnly");
        m.put("band_visibility_source", "config #43（角色 × 字段组四档）");
        m.put("refund_visibility_source", "config #40（角色级二分，独立配置、不与 #43 合并）");
        m.put("refund_visibility_visible_to", List.of("meridian", "admin"));
        m.put("store_scope_visible_to", List.of("therapist", "meridian", "admin"));
        m.put("all_store_ids_empty_means", "全量、不枚举（见 StoreScopeResolver）");
        return m;
    }
}