package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.domain.StoreAnchor;
import com.diaoyuanyun.dy.app.identity.domain.StoreListPage;
import com.diaoyuanyun.dy.app.identity.domain.StoreRow;
import com.diaoyuanyun.dy.app.identity.domain.StoreScopeResolver;
import com.diaoyuanyun.dy.app.identity.repository.StoreRepository;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * A3 {@code GET /stores} —— 门店列表（按行级范围过滤的分页面）。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   A3 GET /stores   x-callable-roles: [therapist, meridian, admin]
 *                    x-client-forbidden: false
 *     参数: Page / PageSize（?page=&lt;int&gt;&amp;page_size=&lt;int≤100&gt;）
 *     200 → StoreListData { items[], total, page, page_size }
 *     403 → VisibilityDenied
 * </pre>
 *
 * <h2>🛑 三道闸，各守一件不同的事（不得互相替代）</h2>
 * <ol>
 *   <li><b>功能权限</b>（{@code store:read}，贴在本端点所在控制器上）——
 *       "这个岗位能不能调门店列表"；客户不持该码，故客户在这一层就会被拒；</li>
 *   <li><b>可调用角色</b>（本类的 {@link #requireCallable}）——显式对齐契约的
 *       {@code x-callable-roles}，<b>点名拒绝</b>客户与调理师之外的未登记角色。
 *       它与第 1 层<b>不重合</b>：权限码是"岗位能否"，本层是"契约允许的端角色集合"，
 *       而合同里这两件事分别冻结在两个不同的键上；</li>
 *   <li><b>行级范围</b>（{@link StoreScopeResolver} + 仓储的 WHERE）——
 *       "即便能调，也只看到自己范围内的那几家"。</li>
 * </ol>
 * 🛑 只留其中任意一条都会洞开：只留 1 则"权限码配错就全量"；只留 2 则
 * "任何 staff 都能拉全租户门店台账"；只留 3 则"客户也能调，只是范围极小"。
 *
 * <h2>🛑 客户为什么在<b>本类</b>还会被拒一次（而非只靠权限码）</h2>
 * 契约 A3 的 {@code x-callable-roles} <b>不含 {@code client}</b>，
 * 故"客户不可调"是契约的<b>显式条款</b>，不是"权限码恰好没发给他"的副产物。
 * 把条款只表达成"他碰巧没这个码"，会让"某天有人给客户补了一个码"
 * （例如为实现某个新功能而放宽）静默地让客户能拉门店台账 —— 而那时没有任何一处会红。
 * 故本类<b>显式点名</b>拒绝客户，使这条契约条款有自己的落点。
 *
 * <h2>🛑 分页越界"拒"而不是"夹逼"</h2>
 * {@code page_size > 100} 或 {@code page < 1} 一律 400（{@code VALIDATION_FAILED}）。
 * 夹逼（静默改成 100）看起来更友好，代价是<b>客户端无从知道自己拿到的是第几页</b> ——
 * 它以为自己要了 500 条、实际拿到 100 条，于是把"还有 400 条"当成"只有 100 条"。
 * 这类静默改动在分页上表现为"数据看起来少了一截"，且不会报错。
 * 🛑 同理不接受 {@code 0} 或负数页码：它们没有对应的分页语义。
 */
@Service
public class StoreListService {

    private final StoreRepository stores;

    public StoreListService(StoreRepository stores) {
        this.stores = stores;
    }

    /**
     * 取一页门店。
     *
     * @param page     页码（可空 ⇒ 缺省 1）
     * @param pageSize 每页条数（可空 ⇒ 缺省 {@value StoreListPage#DEFAULT_PAGE_SIZE}）
     * @throws BizException {@code VISIBILITY_DENIED(2001)} 角色不可调｜
     *                      {@code VALIDATION_FAILED(1001)} 分页参数越界｜
     *                      {@code TENANT_MISMATCH(2003)} 无租户上下文 / 锚点缺失
     */
    public StoreListPage list(Integer page, Integer pageSize) {
        String tokenRole = TenantContext.role();

        // 闸 2：契约 x-callable-roles（客户在此被显式点名拒）
        // 🛑 顺序纪律：身份校验先于租户校验 —— "未携带身份"应归因到身份（2001 VISIBILITY_DENIED，
        //    映射 403，正是 A3 契约声明的那个响应），而不是被 requireTenant 抢答成 2003。
        //    两者都映射 403，但排查时"没带凭证"与"没带租户"是两件事。
        requireCallable(tokenRole);

        String tenantId = requireTenant();

        int effectivePage = validatePage(page);
        int effectiveSize = validatePageSize(pageSize);

        // 闸 3：行级范围 —— 唯一解算点（两来源取舍 + 越宽拒绝）
        StoreScopeResolver.Resolved resolved = StoreScopeResolver.resolveCurrent();
        RowScope rowLevel = resolved.rowLevel();

        // 锚点：staff.store_id → store.region_id（服务端解算，客户端无从提供）
        UUID staffId = parseStaffIdOrNull(TenantContext.staffId());
        StoreAnchor anchor = staffId == null
                ? StoreAnchor.none()
                : stores.findAnchor(tenantId, staffId);

        // 🛑 让"需锚点而缺锚点"在<b>开查询之前</b>就失败（诊断消息更贴近成因），
        //    而不是等仓储拼出一条必然空结果的 WHERE（那会被读成"你没有门店"）。
        StoreScopeResolver.assertAnchorSatisfies(rowLevel, anchor);

        List<StoreRow> items = stores.listByScope(tenantId, rowLevel, anchor,
                effectivePage, effectiveSize);
        // 🛑 total 与 items 出自同一份 WHERE（由仓储共用同一片段保证）；
        //    两者不同源是分页接口最典型的静默缺陷，见 StoreRepository 注释。
        int total = stores.countByScope(tenantId, rowLevel, anchor);

        return new StoreListPage(items, total, effectivePage, effectiveSize);
    }

    /**
     * 可调用角色校验（闸 2）。
     *
     * <h2>🛑 它为什么判"是不是 client"而不判"有没有 store:read"</h2>
     * 因为两者的失效模式不同：
     * <ul>
     *   <li>判权限码 ⇒ 本层与控制器上的 {@code @RequirePermission} <b>同源同效</b>，
     *       等于同一个判据写两遍 —— 一遍改坏另一遍兜底，两层退化成一层；</li>
     *   <li>判契约角色集 ⇒ 本层守护的是<b>契约条款本身</b>（{@code x-callable-roles}），
     *       与"权限矩阵怎么配"无关。即使某次权限矩阵调整，客户仍被这一层挡住。</li>
     * </ul>
     *
     * <p>fail-closed：未登记角色一律拒，绝不回落为"当作 staff 放行"。
     *
     * @throws BizException {@code VISIBILITY_DENIED(2001)} —— 403 语义
     */
    public VisibilityRole requireCallable(String tokenRole) {
        if (tokenRole == null || tokenRole.isBlank()) {
            // 🛑 A3 的契约声明是 '200' + '403' VisibilityDenied（无 401）——
            //    故"未携带身份"在本端点归入 403（身份不足以调用），
            //    与 A2 的 401 处置刻意不同：两者契约声明的响应集不同。
            //    （过滤器对无 Authorization 的请求不建立上下文，故此处角色为空。）
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "请求未携带身份，无法确定行级范围 —— 门店列表不设匿名通道："
                            + "契约 A3 声明 '200' 与 '403'（VisibilityDenied）；"
                            + "行级过滤依赖 token 里的角色与 scope，无身份即无从过滤");
        }
        VisibilityRole endRole = VisibilityRole.tryOf(tokenRole.trim())
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        "token 角色未登记，无法确定端角色: '" + tokenRole + "'"
                                + "（已登记: " + VisibilityRole.allTokenRoles() + "）—— fail-closed，"
                                + "不回落为任何端角色：回落会让一次角色码拼写错误"
                                + "静默变成一次权限判定"));

        // 🛑 契约 A3 的 x-callable-roles 不含 client —— 显式点名，使条款有自己的落点
        if (endRole == VisibilityRole.CLIENT) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "客户不可调 A3 GET /stores —— 契约 A3 的 x-callable-roles: [therapist, meridian, admin] "
                            + "不含 client（客户对门店台账没有数据面：他只能看本人）。"
                            + "🛑 本拒绝刻意不依赖『客户不持有 store:read』这一事实："
                            + "权限矩阵是可变配置，而契约条款是冻结项 —— "
                            + "若把条款只表达成『他碰巧没这个码』，某天有人为别的功能给客户补码时，"
                            + "客户就会静默地拿到门店台账的拉取能力");
        }
        return endRole;
    }

    /**
     * 校验 {@code page}（契约 {@code parameters.Page: minimum: 1}）。
     *
     * <p>🛑 缺省值只在<b>真的没传</b>时生效（{@code null}）；
     * 传了 {@code 0} 或负数一律拒 —— 把它们"当作没传"是另一种静默改动。
     */
    static int validatePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page 必须 ≥ 1：实际=" + page
                            + "（契约 parameters.Page: minimum: 1）。"
                            + "🛑 不把 0 / 负数当作『没传』来兜底：那是静默改动用户输入，"
                            + "会让『我要了第 0 页』与『我要了第 1 页』拿到同一个结果而不报错");
        }
        return page;
    }

    /**
     * 校验 {@code page_size}（契约 {@code parameters.PageSize: maximum: 100}）。
     *
     * <p>🛑 上界之上<b>直接拒</b>而不夹逼到 100：见类注释第 3 节。
     */
    static int validatePageSize(Integer pageSize) {
        if (pageSize == null) {
            return StoreListPage.DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > StoreListPage.MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page_size 必须在 1.." + StoreListPage.MAX_PAGE_SIZE + " 之间：实际=" + pageSize
                            + "（契约 parameters.PageSize: maximum: 100）。"
                            + "🛑 不夹逼到 100：夹逼会让客户端以为自己拿到了 page_size=500 的结果，"
                            + "把『还有 400 条』读成『一共就这些』—— 而它不会报错");
        }
        return pageSize;
    }

    /**
     * 解析 token 里的 {@code staffId}（可为空 —— 客户没有 staff_id）。
     *
     * <p>🛑 与 {@code AuthMeService} 同一纪律：空 ⇒ {@code null}（合法），
     * 非法 ⇒ 抛。<b>不得</b>把坏值当空处理 —— 那会让一次签发/配置错误
     * 静默变成"没有锚点"，进而变成一次含义不明的范围判定。
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

    /** 请求必须携带租户上下文（A3 不设无租户通道）。 */
    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— A3 不设无租户通道："
                            + "行级过滤依赖租户内的组织锚点（staff → store → region），"
                            + "且门店读取由 RLS 按租户圈定");
        }
        return tenantId;
    }

    /** 自描述（供端点自描述与断言 —— 不含任何数据）。 */
    public java.util.Map<String, Object> describeRouting() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("endpoint", "A3 GET /stores（按行级范围过滤的门店分页）");
        m.put("callable_roles", List.of("therapist", "meridian", "admin"));
        m.put("declared_responses", List.of("200", "403"));
        m.put("declared_responses_note",
                "契约 A3 声明 '200' 与 '403'（VisibilityDenied）——【没有 401】。"
                        + "故『未携带身份』在本端点归入 403（身份不足以调用），"
                        + "与 A2 的 401 处置刻意不同：两者契约声明的响应集不同，"
                        + "端点的对外语义必须各自对齐自己那一行");
        m.put("client_callable", false);
        m.put("client_rejection", "显式点名拒绝（契约 x-callable-roles 不含 client），"
                + "不依赖『客户不持有 store:read』这一可变事实");
        m.put("permission_code", "store:read");
        m.put("staff_only_attached", false);
        m.put("staff_only_note",
                "契约 A3 是 x-client-forbidden: false 且无 x-client-explicitly-denied —— "
                        + "`x-client-forbidden` 与 `x-client-explicitly-denied` 语义不同，"
                        + "不得据前者贴 @StaffOnly");
        m.put("row_scope_source", "token scope 优先，未声明时回落角色默认（两来源冲突即拒）");
        m.put("page_minimum", 1);
        m.put("page_size_maximum", StoreListPage.MAX_PAGE_SIZE);
        m.put("page_size_default", StoreListPage.DEFAULT_PAGE_SIZE);
        m.put("out_of_range_handling", "拒绝（400）而非夹逼 —— 夹逼是静默改动用户输入");
        return m;
    }
}