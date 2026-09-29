package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.domain.OrgProvisioningPlan;
import com.diaoyuanyun.dy.app.identity.repository.OrganizationProvisioningRepository;
import com.diaoyuanyun.dy.app.identity.repository.OrganizationProvisioningRepository.ProvisioningOutcome;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * <b>组织开通服务</b> —— 把「开通一个租户及其组织树」从一次裸的数据库写入，
 * 变成一次<b>带审计留痕的、原子的、可重放的</b>运维动作。
 *
 * <h2>它比仓储多做的两件事，以及为什么这两件事不能留在仓储里</h2>
 * <ol>
 *   <li><b>审计留痕</b>：开通是<b>系统中权限最高的动作</b>——它在租户边界之外创建租户。
 *       这样一次动作若不留痕，"谁在什么时候建了哪个租户"就无从回答，而这恰恰是
 *       多租户系统里最容易被问、也最难事后补证的问题。故本类在开通的<b>同一事务</b>内
 *       写一条哈希链审计（见下方"为什么必须同事务"）。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写 {@code created_by='provisioning'}。
 *       真正的操作者由本类的调用方（运维脚本 / 实施工具）提供，并落进审计
 *       {@code actor} 字段 —— 那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 为什么审计写入必须与开通【同一个事务】（与「拒绝留痕」的取舍相反，逐字说明）</h2>
 * 本仓已有一处形态相反的取舍：{@code TenantRejectionAuditBridge} 用
 * {@code REQUIRES_NEW} 独立提交，并允许"留痕失败只降级为 WARN"——
 * 那里 <b>拒绝必须照旧发生</b>，因为它是对攻击者的<b>被动响应</b>，
 * 让"写日志失败"改变 HTTP 状态码是把留痕问题放大成可用性问题。
 *
 * <p>这里反过来，因为动作的性质相反：开通是<b>主动的、由人发起的、低频的</b>操作。
 * <ul>
 *   <li>若审计失败而开通已提交，库里就有了一次<b>无法溯源的高权限变更</b>——
 *       这在合规上比"没开通成功"严重得多，且<b>不可事后补</b>（你无法知道该补什么）；</li>
 *   <li>开通失败则可以<b>重试</b>：整个过程幂等，重放不会产生重复组织。
 *       即失败是廉价且可逆的，而在场的人立刻看得到。</li>
 * </ul>
 * ⇒ 结论：<b>宁可开通不成功，也不要一次没有证据的开通</b>。故两者同事务，
 * 由代码结构（同一个 {@code TransactionTemplate} 边界）保证，而不是靠注释提醒。
 *
 * <h2>🛑 幂等重放【也】写审计 —— 这是一个裁定，不是疏漏</h2>
 * 重放（租户已存在）时同样落一条 {@code TENANT_PROVISIONED} 审计，其 payload 的
 * {@code tenantMode} 为 {@code ALREADY_EXISTS}、各 {@code stores} 等实际行数为 0，
 * 而 {@code requestedStores} 等请求规模照常反映调用方的意图。
 * <p>两条可选口径与取舍（裁定为前者）：
 * <ul>
 *   <li><b>都写（采用）</b>：审计能回答"有人重放过、且用的是多大的计划"。
 *       这在一种具体情形下是唯一的线索 —— <b>有人拿一份不同的计划去打一个已存在的租户</b>
 *       （例如想把某租户的组织树"顺手改掉"）。因为 {@code ON CONFLICT DO NOTHING}
 *       不会报错、不会改数据，若不留痕，这次尝试在系统里<b>完全不可见</b>。</li>
 *   <li>只在 {@code CREATED} 时写（否决）：审计更"干净"，但代价是
 *       "开通脚本在反复重放"这个事实不可见，且与"重放不报错"叠加后，
 *       一次失败的错误重放会静默通过 —— 没有任何地方记得它发生过。</li>
 * </ul>
 * 一句话边界：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>；
 * 前者保护数据，后者保护证据，两者不是同一件事，也不该用同一个开关控制。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值，而不是自己编一个</h2>
 * {@code AuditLogService.append()} 的契约写得很明确：<b>id 由实现生成，忽略调用方传入的</b>
 * （逐字见接口 javadoc「{@code @return} 落库记录的 id（供调用方/校验端引用；
 * <b>由实现生成而非调用方提供</b>）」）。{@code JdbcAuditLogService} 的实现是
 * {@code String id = UUID.randomUUID().toString();} 并在 INSERT 里用它。
 * <p>初版本类构造 {@code AuditLog} 时塞了一个自编的 {@code UUID.randomUUID()}，
 * 然后把这个自编值当作回执的 {@code auditId} 返回 —— <b>它一定不等于库里那一行</b>。
 * 实测：{@code OrganizationProvisioningE2ETest} 里
 * {@code SELECT count(*) FROM audit_log WHERE id = ?::uuid} 返回 <b>0</b>；
 * 库里的行存在，只是 id 是另一个。
 * <p>🛑 这个缺陷属于本仓反复出现的那一类：<b>"看起来成功了，而回执是假的"</b>。
 * 它不会让开通失败、不会让任何计数断言变红、日志里那一行照常打出
 * {@code [PROVISION] ... mode=CREATED}。唯一的破口是"拿回执去对账会对不上"，
 * 而<b>对账恰恰是回执存在的唯一理由</b>。故这里必须取返回值，
 * 且 {@code AuditLog} 构造时送进去的那个 id 是<b>占位、会被实现忽略</b> ——
 * 这一点要写在代码里，否则下一个人会以为两者是同一个值。
 *
 * <h2>🛑 为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 注解的事务边界写在方法签名上，而"审计必须与开通同事务"这条不变量是
 * 本类<b>唯一</b>重要的语义。用 {@code TransactionTemplate} 把它写成
 * <b>一段显式的代码块</b>，读代码的人无法忽略它；用注解则它退化成一条
 * 关于"注解是否生效、传播行为是什么、自调用会不会失效"的隐式推理。
 * 本仓各领域仓储的 {@code inTenant} 用同一形态，此处沿用。
 */
@Service
public class OrganizationProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationProvisioningService.class);

    private final OrganizationProvisioningRepository repository;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public OrganizationProvisioningService(OrganizationProvisioningRepository repository,
                                           ObjectProvider<AuditLogService> auditLogService,
                                           DataSource dataSource) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 幂等开通一个租户及其组织树，并留一条审计。
     *
     * <p>整个动作（租户行 + 组织树 + 审计条目）在<b>一个事务</b>内完成：
     * 任一步失败则全部回滚，绝不留"建了租户但没留痕"的中间态（见类注释）。
     *
     * @param plan     开通计划（其 {@link OrgProvisioningPlan#selfCheck()} 会先跑一遍形态自检）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 —— 一次无主的开通等于一次无主的变更）
     */
    public ProvisioningResult provision(OrgProvisioningPlan plan, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "开通必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，不允许为空");
        }
        plan.selfCheck();
        final String tenantId = plan.tenant().id();
        OrganizationProvisioningRepository.validateTenantId(tenantId);

        return tx.execute(status -> {
            // ① 组织树（仓储内部会建立 RLS 上下文；它加入本事务，故上下文活到本事务提交）
            ProvisioningOutcome outcome = repository.provision(plan);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 entry 的第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。这里不传一个"看起来像真 id"的值，
            //       而是刻意留空字符串 —— 防止下一个人误以为回执取的就是这个字段。
            AuditLog entry = new AuditLog(
                    "",
                    tenantId,
                    operator,
                    "TENANT_PROVISIONED",
                    "tenant",
                    tenantId,
                    Instant.now(),
                    auditPayload(plan, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            // ③ 落库并取回【实现生成的】id —— 回执的 auditId 必须是它（见类注释）
            String auditId = appendAuditOrFail(entry);

            log.info("[PROVISION] tenant={} mode={} operator={} regions={} stores={} staff={} supervised={} auditId={}",
                    tenantId, outcome.tenantMode(), operator,
                    outcome.regions(), outcome.stores(), outcome.staff(),
                    outcome.supervisedRegions(), auditId);

            return new ProvisioningResult(outcome, auditId);
        });
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <h2>🛑 这里的"不降级"是刻意的，与 {@code TenantRejectionAuditBridge} 正好相反</h2>
     * 那两个类的取舍差异有一个共同的判据：<b>留痕失败时，宁可失去哪一个？</b>
     * <ul>
     *   <li>拒绝留痕：宁可失去<b>一条记录</b>，不可失去<b>一次正确的 403</b> ⇒ 降级 WARN；</li>
     *   <li>开通留痕：宁可失去<b>一次开通</b>，不可失去<b>一次可溯源的变更</b> ⇒ 抛错回滚。</li>
     * </ul>
     * 若这里也降级，就会造出本仓最不想要的一种状态：<b>一个已存在但没有任何线索说明
     * 它从何而来的租户</b>。它不会引发任何测试失败 —— 它会静默地一直存在到某次审计。
     *
     * @return {@code append()} 返回的<b>实现生成的</b>审计行 id（见类注释"为什么必须取返回值"）
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行开通。开通是租户边界之外的最高权限动作，"
                            + "一次没有审计的开通无法事后补证（你无法知道该补什么），"
                            + "故此处不回退为『开通继续、日志降级』（那是拒绝留痕的取舍，性质不同）");
        }
        return svc.append(entry);
    }

    /**
     * 审计 payload —— 只放<b>可公开摘要</b>，不放任何敏感内容。
     *
     * <h2>为什么不把整个计划序列化进去</h2>
     * {@code audit_log} 是被<b>全租户可读</b>的（其无 RLS 是哈希链连续性的要求，
     * 敞口已登记为待裁定 —— 见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 把完整的组织树写进去，等于把"A 租户有多少门店、门店叫什么名字"
     * 这类经营信息暴露给每一个能读审计的租户。
     * 故这里只写<b>计数与模式</b>：足以回答"这次开通做了什么、规模多大"，
     * 不足以泄露组织细节。要看细节应查 {@code region}/{@code store} 表本身，
     * 那里有 RLS 保护。
     */
    private static String auditPayload(OrgProvisioningPlan plan, ProvisioningOutcome o) {
        return "{\"tenantMode\":\"" + o.tenantMode()
                + "\",\"regions\":" + o.regions()
                + ",\"stores\":" + o.stores()
                + ",\"staff\":" + o.staff()
                + ",\"supervisedRegions\":" + o.supervisedRegions()
                + ",\"requestedRegions\":" + (plan.regions() == null ? 0 : plan.regions().size())
                + ",\"requestedStores\":" + (plan.stores() == null ? 0 : plan.stores().size())
                + ",\"requestedStaff\":" + (plan.staff() == null ? 0 : plan.staff().size())
                + "}";
    }

    // ==================================================================
    // 运维核对（只读，走与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该租户当前可见的门店数（RLS 生效下的真实行数）。 */
    public int countStores(String tenantId) {
        return repository.countStores(tenantId);
    }

    /** 该租户当前可见的员工数。 */
    public int countStaff(String tenantId) {
        return repository.countStaff(tenantId);
    }

    /**
     * 该租户的员工档案（{@code staff_id / store_id / role / status}）。
     *
     * <p>开通之后，"这个人能不能登进来、能看哪家店"取决于 A2/A3 的锚点解算
     * （{@code StoreRepository.findAnchor}：{@code staff.store_id} → {@code store.region_id}）。
     * 本方法让运维在开通后立刻核对锚点是否成形，而不必等一次真实登录失败。
     */
    public java.util.List<String[]> staffRows(String tenantId) {
        return repository.staffRows(tenantId);
    }

    /** 开通结果：仓储结果 + 本次留痕的审计条目 id（供运维回执 / 事后对账）。 */
    public record ProvisioningResult(ProvisioningOutcome outcome, String auditId) {
    }
}