package com.diaoyuanyun.dy.app.band.service;

import com.diaoyuanyun.dy.app.band.domain.BandBindingOutcome;
import com.diaoyuanyun.dy.app.band.domain.BandBindingRecord;
import com.diaoyuanyun.dy.app.band.repository.BandBindingLedger;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * <b>手环绑定服务</b> —— 把「绑 / 解一支手环」从一次裸的数据库写入，
 * 变成一次<b>带审计留痕的、原子的、可重放的</b>门店作业动作。
 *
 * <h2>🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>审计留痕</b>：手环台账<b>不是</b>一份无关紧要的登记表 ——
 *       {@code band.bound_at} 是"应戴天"分母的<b>起点</b>、
 *       {@code band.unbound_at} 是它的<b>终点</b>（V2 的 §4.3 分母护栏逐字写着
 *       「A3 分母 = 台账应戴天」），而 <b>A3 是退款资格的输入之一</b>。
 *       ⇒ 一次无痕的台账变更，等于一次无痕的<b>指标基线变更</b>，
 *         而它会一路传导到"这个客户能不能退"。
 *       <p>🛑 这与 B-7（组织开通）的审计理由是<b>同一类</b>而不是同一件事 ——
 *       见下方「与 B-7 的取舍对照」。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写 {@code created_by='band-binding'}。
 *       真正的操作者由本类的调用方（门店作业工具 / 运维脚本）提供，
 *       并落进审计 {@code actor} —— 那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 与 B-7 的取舍对照（为什么两处"看起来一样"，但理由不同）</h2>
 * <table border="1">
 *   <caption>审计失败时，宁可失去哪一个？</caption>
 *   <tr><th></th><th>B-7 组织开通</th><th>B-10 手环绑定（本类）</th></tr>
 *   <tr><td>动作性质</td>
 *       <td>租户边界<b>之外</b>的最高权限动作</td>
 *       <td>租户<b>之内</b>的门店日常作业</td></tr>
 *   <tr><td>频次</td><td>极低频（一次性实施）</td><td>较高（每个客户接入 / 换机 / 解绑）</td></tr>
 *   <tr><td>无痕的后果</td>
 *       <td>一个<b>无主的租户</b>存在了，事后无法知道该补什么</td>
 *       <td>一段应戴天<b>没有来源</b>，而它会传导到退款资格</td></tr>
 *   <tr><td>裁定</td><td colspan="2"><b>两者都同事务、都不降级</b> —— 但本类多一条理由：</td></tr>
 * </table>
 * 本类多出的那条理由：<b>绑定是幂等的</b>（V17 的库层 {@code ON CONFLICT} 保证），
 * 故"审计失败 ⇒ 绑定失败 ⇒ 重试"这条路径<b>不产生任何副作用</b>。
 * 即代价是"重试一次"，而收益是"台账的每一行都有来源"。
 * 若绑定<b>不</b>幂等，这个取舍就要重新算 —— 故这里把幂等性当作审计纪律的<b>前提</b>写下来。
 *
 * <h2>🛑 幂等重放与「不变量胜出」【也】写审计 —— 这是裁定，不是疏漏</h2>
 * 三态写审计：{@link BandBindingOutcome#mutatedData()}（真改了数据）、
 * {@link BandBindingOutcome#idempotentReplay()}（重放，数据未变）、
 * {@link BandBindingOutcome#invariantWon()}（客户已有有效手环、本次未换机，数据未变）。
 * <p>理由是 B-7「重放也写审计」那条的<b>更强形式</b>：
 * B-7 的场景里，"有人拿不同计划打一个已存在租户"至少会返回一个
 * {@code ALREADY_EXISTS} 给调用方看一眼。而这里 ——
 * 库层 {@code ON CONFLICT DO NOTHING} <b>不报错、不改数据、返回一个正常值</b>，
 * 于是"有人试图给一个已有带子的客户再绑一支"这件事，
 * 若不留痕就<b>在系统里完全不可见</b>。
 * 那可能是一次误操作（录错人）、也可能是一次真实的重复接入事故 ——
 * 两者都值得在事后被看见。
 * <p>一句话边界（沿用 B-7 的措辞）：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>。
 *
 * <h2>🛑 为什么审计写入必须与绑定【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的 {@code bind()} 内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * ⇒ {@code set_config('app.tenant_id', ..., is_local := true)} 建立的上下文
 * 活到<b>本事务提交</b>，而审计条目也落在同一个提交里。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把 {@code bind()} 的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么绑定会独立提交 —— 于是"审计失败则绑定回滚"这条不变量<b>静默失效</b>：
 * 绑定已经落库，而审计没有，且没有任何测试会红。
 * 本仓对这一类有既定处置：把前提写成可断言的东西（见
 * {@code BandBindingGateTest} 的"绑定与审计同事务"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明绑定确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与 {@code OrganizationProvisioningService} 逐字相同：
 * {@code AuditLogService.append()} 的契约写明 <b>id 由实现生成、忽略调用方传入的</b>。
 * 那里的初版本类塞了一个自编的 {@code UUID.randomUUID()} 并当作回执返回 ——
 * 实测 {@code SELECT count(*) FROM audit_log WHERE id = ?} 返回 <b>0</b>：
 * 库里的行存在，只是 id 是另一个。故本类构造 {@code AuditLog} 时
 * 第一个参数<b>刻意留空串</b>（占位、会被忽略），并显式取 {@code append()} 的返回值。
 */
@Service
public class BandBindingService {

    private static final Logger log = LoggerFactory.getLogger(BandBindingService.class);

    /** 绑定动作名 —— 落在 {@code audit_log.action}。换机时同名前缀，由 payload 的 mode 区分。 */
    static final String ACTION_BOUND = "BAND_BOUND";

    /** 解绑动作名。 */
    static final String ACTION_UNBOUND = "BAND_UNBOUND";

    /** 审计目标的类型名（{@code audit_log.target_type}）。 */
    static final String TARGET_TYPE = "band";

    private final BandBindingLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public BandBindingService(BandBindingLedger ledger,
                              ObjectProvider<AuditLogService> auditLogService,
                              DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 绑定
    // ==================================================================

    /**
     * 幂等绑定一支手环给客户，并在同一个事务里留一条审计。
     *
     * @param record   绑定意图（其构造器会先跑一遍形态校验：必填 / 列长 / 枚举 / 日期下界）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的台账变更等于一次无主的指标基线变更）
     * @return 绑定结果（四态之一 + 本次留痕的审计 id）
     */
    public BandBindingResult bind(BandBindingRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "绑定手环：记录不可为空");
        }
        requireOperator(operator);

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            BandBindingOutcome outcome = ledger.bind(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串，防止下一个人
            //       误以为回执取的就是这个字段（B-7 已付费记录过这个缺陷）。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    ACTION_BOUND,
                    TARGET_TYPE,
                    record.bandId().toString(),
                    Instant.now(),
                    bindPayload(record, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry);

            log.info("[BAND-BIND] tenant={} band={} customer={} mode={} operator={} auditId={}",
                    record.tenantId(), record.bandId(), record.customerId(),
                    outcome.name(), operator, auditId);

            return new BandBindingResult(outcome, auditId, record.bandId());
        });
    }

    // ==================================================================
    // 解绑
    // ==================================================================

    /**
     * 幂等解绑一支手环，并在同一个事务里留一条审计。
     *
     * <p>🛑 是状态迁移（{@code status → 'unbound'}）而不是删除行 —— 见
     * {@link BandBindingLedger#unbind} 与 V17 {@code unbind_band()} 的注释。
     *
     * @param reason    写进 {@code unbind_reason}；应取 {@code 主动放弃 / 换机 /
     *                  设备损坏 / 其他} 之一（{@code band} 表 CHECK 冻结值）或留空
     * @param unboundAt 解绑日；{@code null} → 库函数回落 {@code CURRENT_DATE}
     */
    public BandBindingResult unbind(String tenantId, UUID bandId, String reason,
                                    LocalDate unboundAt, String operator) {
        requireOperator(operator);
        if (bandId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "解绑手环：band_id 必填");
        }

        return tx.execute(status -> {
            BandBindingOutcome outcome = ledger.unbind(tenantId, bandId, reason, unboundAt);

            AuditLog entry = new AuditLog(
                    "",
                    tenantId,
                    operator,
                    ACTION_UNBOUND,
                    TARGET_TYPE,
                    bandId.toString(),
                    Instant.now(),
                    unbindPayload(outcome, reason, unboundAt),
                    null, null);

            String auditId = appendAuditOrFail(entry);

            log.info("[BAND-UNBIND] tenant={} band={} mode={} reason={} operator={} auditId={}",
                    tenantId, bandId, outcome.name(), reason, operator, auditId);

            return new BandBindingResult(outcome, auditId, bandId);
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该客户当前的有效手环 id（无则 {@code null}）。 */
    public UUID activeBandOf(String tenantId, UUID customerId) {
        return ledger.findActiveBandId(tenantId, customerId);
    }

    /** 该客户的手环台账全历史（{@code band_id / status / unbind_reason / bound_at / unbound_at}）。 */
    public java.util.List<String[]> historyOf(String tenantId, UUID customerId) {
        return ledger.historyOf(tenantId, customerId);
    }

    /** 该租户内当前有效手环数。 */
    public int countActiveInTenant(String tenantId) {
        return ledger.countActiveInTenant(tenantId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "绑定/解绑手环必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。手环台账是『应戴天』分母的来源，而 A3 是退款资格的输入之一："
                            + "一次无主的台账变更，等于一次无主的指标基线变更");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与 {@code OrganizationProvisioningService#appendAuditOrFail} 同一取舍，
     * 理由及其与"拒绝留痕"的对照见本类头「与 B-7 的取舍对照」。
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行手环绑定/解绑。"
                            + "band.bound_at / unbound_at 是『应戴天』分母的两端，"
                            + "而 A3 是退款资格的输入之一；一次没有审计的台账变更无法事后补证。"
                            + "故此处不回退为『绑定继续、日志降级』（那是拒绝留痕的取舍，性质不同）");
        }
        return svc.append(entry);
    }

    /**
     * 绑定审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <p>🛑 为什么这里也克制（{@code band} 表并没有敏感列）：
     * {@code audit_log} 是被<b>全租户可读</b>的（其无 RLS 是哈希链连续性的要求，
     * 敞口已登记为待裁定 —— 见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 若把 {@code vendor} / {@code model} 也写进来，等于把"这个租户在用哪批设备型号、
     * 什么时候接入的"这类经营信息暴露给每一个能读审计的租户。
     * 故这里只写<b>标识与模式</b>：{@code band_id} / {@code customer_id}（都是 UUID，
     * 不含姓名等 PII）+ 本次走的是哪条路径 + 绑定日。
     * 要查设备细节应查 {@code band} 表本身，那里有 RLS 保护。
     */
    private static String bindPayload(BandBindingRecord r, BandBindingOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"bandId\":\"" + r.bandId()
                + "\",\"customerId\":\"" + r.customerId()
                + "\",\"rebind\":" + r.rebind()
                + ",\"boundAt\":\"" + r.effectiveBoundAt()
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /** 解绑审计 payload（同上一节的克制原则）。 */
    private static String unbindPayload(BandBindingOutcome o, String reason, LocalDate unboundAt) {
        return "{\"mode\":\"" + o.name()
                + "\",\"reason\":\"" + (reason == null ? "" : reason)
                + "\",\"unboundAt\":\"" + (unboundAt == null ? "" : unboundAt.toString())
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /**
     * 绑定 / 解绑的结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次变更的审计证据是哪一条"）。回执存在的唯一理由就是对账，
     * 故它必须是库里真实的那个 id（见类注释「为什么审计条目 id 必须取自返回值」）。
     */
    public record BandBindingResult(BandBindingOutcome outcome, String auditId, UUID bandId) {

        /** 本次调用是否真的改动了台账数据。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }
    }
}