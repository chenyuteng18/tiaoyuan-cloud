package com.diaoyuanyun.dy.app.identity.repository;

import com.diaoyuanyun.dy.app.identity.domain.OrgProvisioningPlan;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * <b>组织主数据开通仓储</b> —— 本仓<b>第一个</b>（也是唯一一个）往
 * {@code tenant} / {@code region} / {@code store} / {@code staff} 写行的生产代码。
 *
 * <h2>🛑 它为什么不是 Controller，也不该变成 Controller</h2>
 * 契约 {@code openapi-v1.0.0.yaml} 的 40 个 path 里<b>没有</b>任何租户开通 /
 * 门店新建 / 员工新建端点，且这是<b>契约化决策</b>而非未完成项：
 * <ul>
 *   <li>开通是<b>运维/实施</b>动作，不是业务 API。给它一个对外 HTTP 端点，等于在租户
 *       边界之外开一个「谁能创建租户」的鉴权面，而契约的 {@code x-callable-roles}
 *       里没有任何角色声明覆盖它 —— 即那个端点会落在现有权限模型的<b>覆盖范围之外</b>；</li>
 *   <li>加这样一个端点属契约 <b>MAJOR</b> 变更，需产品共签，不在本骨架的裁定范围内。</li>
 * </ul>
 * 故本类被刻意做成一个<b>无 HTTP 映射的组件</b>：运维脚本 / 实施工具直接调用它，
 * 或直接调 {@code provision_tenant()} 这个数据库函数（两条通路共用同一份实现）。
 * {@code ProvisioningBoundaryGateTest} 的第 ④ 例会机械断言契约里不出现开通端点 ——
 * 若有人给它加上 {@code @RestController} 与 {@code @PostMapping}，那道门禁会先红。
 *
 * <h2>🛑 一个事务、两步、不可拆（拆开就有一个必然出错的中间态）</h2>
 * <pre>
 *   ① SELECT provision_tenant(…)     —— V15 原语：幂等插 tenant 行【并】建立 app.tenant_id 上下文
 *   ② INSERT region / store / staff  —— 三表 FORCE RLS + fail-closed，【必须】有上下文
 * </pre>
 * 两步落在同一个 {@code TransactionTemplate} 里，理由是第 ① 步的性质：
 * {@code set_config(..., is_local := true)} 是<b>事务级</b>设置，事务一结束即失效。
 * 若把 ① 与 ② 拆成两个事务，第 ① 步建立的上下文活不过第一个事务的提交 ——
 * 这正是各 {@code *Ledger} 的 {@code inTenant} 每次都重设上下文的原因。
 * 拆开还有一个更坏的结果：租户行已提交、组织树未建成，库里留下一批
 * <b>「存在但空」的租户</b>，而它们看起来与正经租户无法区分。故这里宁可
 * 让整棵树在失败时一起回滚。
 *
 * <h2>🛑 为什么【不】在应用侧再写一遍 {@code INSERT INTO tenant}（一条被实测证伪的理由，留档）</h2>
 * 本类初版曾在第 ① 位显式写一行 {@code INSERT INTO tenant ... ON CONFLICT DO NOTHING}，
 * 保留理由是"给静态门禁一个 Java 侧的可断言载体"。<b>该理由经实测证伪，而那一行本身就是 bug。</b>
 * 两条都记下来，因为它们各自都是本仓最容易复发的一类错误：
 * <ol>
 *   <li><b>它是一个真缺陷（不是冗余，是错）</b>：同一事务内先插 tenant 行、紧接着调
 *       {@code provision_tenant()} —— 函数体内那条 {@code INSERT ... ON CONFLICT (id) DO NOTHING}
 *       会<b>看到本事务刚插入的行</b>（同事务可见），于是 {@code GET DIAGNOSTICS ROW_COUNT = 0}，
 *       函数对<b>全新租户也恒判 {@code ALREADY_EXISTS}</b>。
 *       <p>实测证据：{@code OrganizationProvisioningE2ETest} 第 ① 例先 purge 到「库里 0 行」，
 *       再开通，拿到的返回值是 {@code ALREADY_EXISTS}。
 *       <p>🛑 这个缺陷极隐蔽，值得逐字说明它为什么能长期存活：它的<b>表象只是"返回值不对"</b>，
 *       而组织树其实<b>建成了</b>（因为 tenant 行确实存在、上下文也确实建了）——
 *       于是所有计数断言、外键断言、RLS 断言照样全绿，唯一对不上的是那个字符串。
 *       一个只盯着"行数对不对"的测试集完全抓不到它。</li>
 *   <li><b>理由本身不成立</b>：{@code ProvisioningBoundaryGateTest} 的 SQL 扫描器
 *       （{@code stripSqlComments}）<b>保留美元引用块（{@code $tag$ … $tag$}）内的原文</b>
 *       —— 这是有意的，因为 V6 的触发器函数体里就写着 {@code INSERT INTO app_config_history}，
 *       不保留会让那张表被误判成"无写入方"。正因为保留了，V15 里
 *       {@code $v15_provision$ … $v15_provision$} 内部的 {@code INSERT INTO tenant}
 *       本来就在扫描视野内。
 *       <p>实测证据：按同一套剥注释逻辑复算 V15 →
 *       {@code INSERT INTO tenant} 命中 <b>1</b>、{@code region/store/staff} 命中 <b>0</b>。
 *       ⇒ <b>删掉应用侧那一行，判据② 不会变红。</b></li>
 * </ol>
 * 结论：tenant 行的幂等写入<b>只在一处</b>发生 —— V15 的 {@code provision_tenant()}。
 * 应用侧不再重复它。这顺带消除了"两处都写 tenant 行"的口径分叉：
 * 那种分叉平时无害，一旦有人只改了其中一处的幂等语义（例如给应用侧那条加上
 * {@code DO UPDATE}），就会出现"同一个动作有两种结果"的静默不一致。
 *
 * <h2>为什么 {@code tenant_id} 白名单校验后拼接（与 {@code StoreRepository} 同一套）</h2>
 * 见 {@link #inTenant}：{@code SET} 是工具语句、不支持绑定参数，故必须拼接，
 * 拼接前用 {@link #UUID_PATTERN} 做字符集白名单。<b>不得放宽</b>。
 */
@Component
public class OrganizationProvisioningRepository {

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrganizationProvisioningRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 开通主流程
    // ==================================================================

    /**
     * 幂等开通一个租户及其组织树（区域 / 门店 / 员工）。
     *
     * <p>整个动作落在<b>一个</b>事务里（见类注释的三步不可拆）。
     * 重复调用安全：租户行靠 {@code ON CONFLICT DO NOTHING}，
     * 区域 / 门店 / 员工行同样靠 {@code ON CONFLICT DO NOTHING} ——
     * 故"运维脚本跑第二遍"不会报错，也不会产生重复组织。
     *
     * <p>🛑 幂等<b>只保证不重复建</b>，<b>不保证内容一致</b>：若第二遍传了不同的门店名，
     * 已存在的行<b>不会</b>被更新（{@code DO NOTHING} 的语义）。这是有意的 ——
     * 变更既有组织是一次有审计含义的独立操作，不应被一次"顺手的重放"静默执行。
     *
     * @return 各层的实际落库结果（供审计留痕与可观测；调用方无需据此分支）
     */
    public ProvisioningOutcome provision(OrgProvisioningPlan plan) {
        plan.selfCheck();
        final String tenantId = plan.tenant().id();
        requireUuid(tenantId);

        return tx.execute(status -> {
            // ① tenant 行 + 租户上下文 —— 由 V15 原语一次完成（见类注释：
            //    应用侧【不】再写一遍 INSERT INTO tenant，那会让本函数恒判 ALREADY_EXISTS）。
            //    🛑 断言返回值只取两个合法值：若函数被改成别的口径，这里会先红，
            //       而不是让一个无法解释的字符串流进审计 payload。
            String mode = jdbc.queryForObject(
                    "SELECT provision_tenant(?::uuid, ?, ?)",
                    String.class, tenantId, plan.tenant().name(), plan.tenant().datastoreHint());
            if (!"CREATED".equals(mode) && !"ALREADY_EXISTS".equals(mode)) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "provision_tenant() 返回了未登记的口径 '" + mode + "' —— 本仓储只认 "
                                + "CREATED / ALREADY_EXISTS 两态。新增三态必须先在这里登记，"
                                + "否则它会静默流进审计 payload 而无人解释它的含义");
            }

            // ①b 自证上下文真的生效 —— 不靠"我调了函数所以它当然生效"
            String ctx = jdbc.queryForObject("SELECT assert_tenant_context()", String.class);
            if (!tenantId.equals(ctx)) {
                // 这条分支在正常实现下不可达；留着它是因为"不可达"本身要可被断言。
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "开通自证失败：建立上下文后读回的 app.tenant_id=" + ctx
                                + " 与目标租户=" + tenantId + " 不一致 —— 拒绝继续建组织树");
            }

            // ③ 组织树（三表 FORCE RLS，依赖第 ② 步的上下文）
            int nRegions = 0;
            if (plan.regions() != null) {
                for (OrgProvisioningPlan.RegionSpec r : plan.regions()) {
                    nRegions += jdbc.update("INSERT INTO region "
                                    + "(region_id, tenant_id, name, supervisor_id, created_by) "
                                    + "VALUES (?::uuid, ?::uuid, ?, NULL, ?) ON CONFLICT (region_id) DO NOTHING",
                            r.id(), tenantId, r.name(), actor());
                }
            }

            int nStores = 0;
            if (plan.stores() != null) {
                for (OrgProvisioningPlan.StoreSpec s : plan.stores()) {
                    nStores += jdbc.update("INSERT INTO store "
                                    + "(store_id, tenant_id, region_id, name, franchise_type, device_model, created_by) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?) ON CONFLICT (store_id) DO NOTHING",
                            s.id(), tenantId, s.regionId(), s.name(),
                            s.franchiseType(), s.deviceModel(), actor());
                }
            }

            int nStaff = 0;
            if (plan.staff() != null) {
                for (OrgProvisioningPlan.StaffSpec p : plan.staff()) {
                    // 🛑 落库的值是【中文人事枚举】（staff.role 的 CHECK 约束冻结值），
                    //    不是契约角色码。映射与理由见 OrgProvisioningPlan.StaffRole 的类注释。
                    nStaff += jdbc.update("INSERT INTO staff "
                                    + "(staff_id, tenant_id, store_id, role, status, created_by) "
                                    + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'active', ?) ON CONFLICT (staff_id) DO NOTHING",
                            p.id(), tenantId, p.storeId(), p.role().dbValue(), actor());
                }
            }

            // ④ 回填督导（region.supervisor_id → staff.staff_id）
            //    🛑 必须【最后】做：V5 的 region 表刻意【不】带 supervisor_id 的 FK 约束
            //       （region ↔ staff ↔ store 是环：region.supervisor_id → staff.store_id →
            //        store.region_id → region），FK 由 V5 第 8 节在 staff 建出后条件式补上。
            //       故插入顺序必须是 region(无督导) → store → staff → 回填督导。
            //       这里对每条给了 supervisorStaffId 的区域做一次显式 UPDATE。
            int nSupervised = 0;
            if (plan.regions() != null) {
                for (OrgProvisioningPlan.RegionSpec r : plan.regions()) {
                    if (r.supervisorStaffId() != null && !r.supervisorStaffId().isBlank()) {
                        nSupervised += jdbc.update(
                                "UPDATE region SET supervisor_id = ?::uuid, updated_at = now() "
                                        + "WHERE region_id = ?::uuid AND supervisor_id IS NULL",
                                r.supervisorStaffId(), r.id());
                    }
                }
            }

            return new ProvisioningOutcome(mode, nRegions, nStores, nStaff, nSupervised);
        });
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证）
    // ==================================================================

    /**
     * 该租户当前可见的门店数（RLS 生效下的真实行数）。
     *
     * <p>经 {@link #inTenant} 执行 —— 与 {@code StoreRepository.countVisible} 同一形态，
     * 目的是让"读已开通组织"这条路径与业务读路径走<b>同一套</b>上下文机制，
     * 而不是运维侧另开一条"因为我是运维所以不设上下文"的旁路。
     * 后者是隔离体系里最常见的自毁方式：一条绕过上下文的读路径，
     * 迟早会被某个人接进一个对外接口。
     */
    public int countStores(String tenantId) {
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM store", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /** 该租户当前可见的员工数（同上口径）。 */
    public int countStaff(String tenantId) {
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /** 该租户的员工档案（供 A2/A3 锚点解算核对；返回 staff_id → store_id / role）。 */
    public List<String[]> staffRows(String tenantId) {
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT staff_id::text, coalesce(store_id::text, ''), role, status "
                        + "  FROM staff ORDER BY staff_id",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}));
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code StoreRepository.inTenant} 同款，理由逐字相同：
     * {@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"收敛成一个短事务，
     * 使<b>「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立</b>。
     *
     * <p>{@code requireUuid} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL。
     */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    static void requireUuid(String tenantId) {
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            // 语义归属：租户标识非法 = 租户不匹配（2003/403），不是 500
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID，拒绝执行组织开通/查询");
        }
    }

    /** 供服务层在查库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    /**
     * 开通动作的执行者标识 —— 写进各表的 {@code created_by} 列。
     *
     * <p>取值来自系统属性，默认 {@code "provisioning"}。刻意<b>不</b>读
     * {@code TenantContext}：开通时租户上下文是<b>本方法刚建立的</b>，
     * 用被自己建立的东西来标识"谁做的"是循环论证。真正的操作者身份由调用方
     * （运维脚本）在审计 payload 里提供 —— 见 {@code OrganizationProvisioningService}。
     */
    private static String actor() {
        return System.getProperty("dy.provisioning.actor", "provisioning");
    }

    /** 开通结果（各层实际落库的行数 + 租户三态）。 */
    public record ProvisioningOutcome(String tenantMode, int regions, int stores, int staff,
                                      int supervisedRegions) {
    }
}