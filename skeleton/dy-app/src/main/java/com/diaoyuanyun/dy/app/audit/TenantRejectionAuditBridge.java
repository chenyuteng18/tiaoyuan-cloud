package com.diaoyuanyun.dy.app.audit;

import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.TenantRejectionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * 租户拒绝 → 审计留痕桥（S1-3 验收④）。
 *
 * <h2>这个类是为了不把依赖方向搞反</h2>
 * dy-tenancy 只声明 {@link TenantRejectionListener}，本类在 <b>dy-app</b> 把它接到
 * {@link AuditLogService}。于是"租户上下文层"保持无审计依赖，
 * 而"拒绝要留痕"这条要求仍然落地。依赖方向由
 * {@code ArchitectureBoundaryTest} 强制，本类正是让那条约束与业务要求同时成立的那块垫片。
 *
 * <h2>三条实现纪律（对应接口里的三条硬纪律）</h2>
 * <ol>
 *   <li><b>绝不抛异常</b>：拒绝路径上任何抛出的异常都会把 403 变成 500。
 *       本类把整段写库包在 try/catch 里，失败只降级为 WARN 日志（并带上原始原因）。</li>
 *   <li><b>绝不阻塞过久</b>：写一条记录，不做全链重算。</li>
 *   <li><b>绝不回写请求状态</b>。</li>
 * </ol>
 *
 * <h2>为什么必须自己开事务（这不是可选优化，缺了它整条留痕链路是死的）</h2>
 * {@code JdbcAuditLogService.append()} 的实现有一条硬前置：<b>必须在事务中执行</b>——
 * 它取 {@code pg_advisory_xact_lock} 作为链写入的跨实例互斥量，而<b>事务级</b>锁
 * 只在事务内有效；因此实现里显式检查 {@code conn.getAutoCommit()}，
 * 一旦为 {@code true} 就直接抛 {@code IllegalStateException}（宁可写入报错，
 * 也不要一条会自己断的链）。
 *
 * <p>而本类被调用的位置是 {@code TenantContextFilter} 的拒绝分支 ——
 * 那是 servlet 过滤器链，<b>没有任何 Spring 事务</b>，连接必然 {@code autoCommit=true}。
 * 于是一个看似正确的实现（直接调 {@code append}）会<b>100% 抛异常</b>，
 * 被本类的 catch 吞成一条 WARN —— 请求照旧 403，日志里有一行警告，
 * <b>而审计表里一行都没有</b>。这正是 S1-3 验收④ 要防的形态：
 * 门禁绿、功能"看着正常"、合规证据缺失。故本类显式开一个
 * {@code REQUIRES_NEW} 事务把 {@code append} 包起来。
 *
 * <p><b>为什么用 {@code REQUIRES_NEW} 而不是 {@code REQUIRED}</b>：
 * <ul>
 *   <li>本类只允许在拒绝路径上被调用，那里本就无事务，两者行为相同 ——
 *       所以这个选择不是为了让当前调用点能跑，而是为了<b>让语义在错误调用点也安全</b>。</li>
 *   <li>若将来有人误在<b>业务事务内</b>调用本方法（例如"业务失败 → 顺手记一条拒绝留痕"），
 *       {@code REQUIRED} 会加入那个事务：业务事务一旦回滚，审计记录被一起回滚 ——
 *       而"某次访问被拒绝"这件事<b>客观发生过</b>，不该因为业务回滚而消失。
 *       {@code REQUIRES_NEW} 让审计写入独立提交，从机制上消除这条路径。</li>
 * </ul>
 *
 * <p><b>与"绝不阻塞过久"的关系（如实说明）</b>：开事务 + 取 advisory 锁 =
 * 在审计链的全局单写者上排队，高并发下会让过滤器线程等待。
 * 这是当前设计的<b>已知取舍</b>：这条通道只承载"拒绝事件"（异常流量，频率低），
 * 不是业务热路径；用一点延迟换"证据必然落库"。后续若要进一步解耦，
 * 应改为有界队列 + 独立写线程（见 {@code AuditLogService} 的实现注释里
 * "业务热路径不应在事务里同步写审计链"那一段），属 backlog。</p>
 *
 * <h2>租户字段怎么填（关键，别填错）</h2>
 * 跨租户拒绝发生时<b>没有可信的租户上下文</b>——这正是被拒的原因。
 * 因此<b>不</b>读 {@code TenantContext} 当 tenant_id：那会把"攻击者自己声称的租户"
 * 写成审计归属，等于让被审计者决定审计归类。此处固定写入
 * {@link #UNATTRIBUTED_TENANT}（全 0 UUID），语义是"<b>不归属于任何租户</b>"。
 *
 * <p>这与 RLS 的 fail-closed 同构：<b>无法确定归属时，宁可标记为"无归属"，
 * 也不猜测一个归属</b>。审计表本身豁免 RLS（全局单链哈希，见 T-09），
 * 故写入本条不会因缺上下文而失败。
 *
 * <h2>不落敏感内容</h2>
 * {@code targetId} 放请求路径（定位"谁在跨租户访问"），{@code payload} 只放
 * {@code errorCode} 与 {@code reason}；<b>绝不</b>把 token、密钥、请求体写进去 ——
 * 审计日志是全租户可读的（T-09 敞口），写进去等于放大泄漏面。
 */
@Component
public class TenantRejectionAuditBridge implements TenantRejectionListener {

    private static final Logger log = LoggerFactory.getLogger(TenantRejectionAuditBridge.class);

    /**
     * 归属未知时的占位租户（全 0 UUID）。语义 = "不归属于任何租户"，不是"默认租户"。
     *
     * <p>声明为 {@code public} 是刻意的：它是一个<b>对外可断言的契约值</b>——
     * 跨包的真库门禁（{@code RlsRejectionAuditTrailGateTest}）必须直接引用它来断言
     * "审计归属未被攻击者声称的租户污染"。若改成字符串字面量再断言，
     * 改错一方不会报错，断言会静默失去意义。
     */
    public static final String UNATTRIBUTED_TENANT = "00000000-0000-0000-0000-000000000000";

    private final ObjectProvider<AuditLogService> auditLogService;
    private final ObjectProvider<PlatformTransactionManager> txManagerProvider;

    @Autowired
    public TenantRejectionAuditBridge(ObjectProvider<AuditLogService> auditLogService,
                                      ObjectProvider<PlatformTransactionManager> txManagerProvider) {
        this.auditLogService = auditLogService;
        this.txManagerProvider = txManagerProvider;
    }

    /**
     * 测试用构造：不装配事务管理器 ⇒ 直接调用 {@code append}。
     *
     * <p>存在的意义：让"漏开事务"这一缺陷<b>可以被断言出来</b>
     * （见 {@code TenantRejectionAuditTransactionTest} 的反向验证）。
     * 若本类没有这个入口，就只能靠人读代码发现，而那正是缺陷溜过去的方式。
     */
    public TenantRejectionAuditBridge(ObjectProvider<AuditLogService> auditLogService) {
        this(auditLogService, null);
    }

    @Override
    public void onRejected(int errorCode, String reason, String path) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            // 未装配审计服务：留痕降级为一条 WARN，绝不因此改变拒绝结果。
            log.warn("[TENANT-REJECT-AUDIT] 审计服务未装配, 拒绝事件未落库: code={} path={}", errorCode, path);
            return;
        }
        try {
            String action = actionFor(errorCode);
            AuditLog entry = new AuditLog(
                    UUID.randomUUID().toString(),
                    UNATTRIBUTED_TENANT,
                    "anonymous",           // 拒绝发生在身份确立之前，没有可靠 actor
                    action,
                    "http_request",
                    path,
                    Instant.now(),
                    "{\"errorCode\":" + errorCode + ",\"reason\":\"" + escape(reason) + "\"}",
                    null, null);           // 链字段由 AuditLogService 实现自算，不得由调用方提供
            appendInOwnTx(svc, entry);
        } catch (RuntimeException e) {
            // 纪律①：留痕失败绝不篡改"拒绝"这一既定结果，只降级记录。
            log.warn("[TENANT-REJECT-AUDIT] 拒绝事件落库失败(已降级, 不影响 403 结果): code={} path={} cause={}",
                    errorCode, path, e.getMessage());
        }
    }

    /**
     * 在独立事务里追加审计条目（见类注释"为什么必须自己开事务"）。
     *
     * <p>事务管理器缺席时<b>不静默跳过</b>：直接调用 {@code append} 并让它的
     * "必须在事务中"异常浮出来，由调用方的 catch 降级为 WARN。
     * 这样在装配不完整的场景里，日志里留下的是<b>一条说明原因的 WARN</b>，
     * 而不是"看似成功其实没写"的假象。
     */
    private void appendInOwnTx(AuditLogService svc, AuditLog entry) {
        PlatformTransactionManager tm = txManagerProvider == null ? null : txManagerProvider.getIfAvailable();
        if (tm == null) {
            svc.append(entry);
            return;
        }
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> svc.append(entry));
    }

    /**
     * 错误码 → 审计动作名。
     *
     * <p>用动作名而非裸码：审计读的人需要一眼看懂"发生了什么事"，
     * {@code 2003} 对非开发读者不构成信息。
     */
    private String actionFor(int errorCode) {
        if (errorCode == ErrorCode.TENANT_MISMATCH.getCode()) {
            return "TENANT_CROSS_ACCESS_REJECTED";
        }
        if (errorCode == ErrorCode.UNAUTHENTICATED.getCode()) {
            return "AUTH_REJECTED";
        }
        return "REQUEST_REJECTED";
    }

    /** 最小 JSON 转义：reason 可能来自异常消息，直接拼串会产出非法 JSON。 */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}