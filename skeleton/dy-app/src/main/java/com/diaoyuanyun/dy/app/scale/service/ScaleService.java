package com.diaoyuanyun.dy.app.scale.service;

import com.diaoyuanyun.dy.app.scale.domain.ScaleOutcome;
import com.diaoyuanyun.dy.app.scale.domain.ScaleRecord;
import com.diaoyuanyun.dy.app.scale.repository.ScaleLedger;
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
import java.util.UUID;

/**
 * <b>量表建档服务</b> —— 把「往租户建一份量表台账」从一次裸的数据库写入，
 * 变成一次<b>带审计留痕的、原子的、可重放的</b>口径管理动作。
 *
 * <h2>🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>审计留痕</b>：量表是<b>口径资产</b> —— 它决定基线评估怎么打分，
 *       而基线评估是客户档案的一部分（{@code locked = TRUE}，不可改，见 V5 L353）。
 *       ⇒ 一次无痕的建档，等于一次<b>无主的口径起点</b>：
 *       事后无法回答"这份量表什么时候、由谁建进来的"，
 *       而"某个客户的基线分数为什么是这么算的"这类追溯恰恰要回到这个起点。
 *       <p>🛑 与 B-11（设备建档）的审计理由<b>不同</b>，必须说清：
 *       B-11 的理由是"<b>可追责性需要起点</b>"（D6 下发可追责）；
 *       本类的理由是"<b>口径可追溯到它的来源</b>"（打分依据必须可解释）。
 *       两条理由都指向"必须同事务审计"，但它们不是同一条理由 ——
 *       若混为一谈，下一个人会以为改掉其中一个的审计纪律是可以的。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写
 *       {@code created_by='scale-registration'}。真正的操作者由本类的调用方
 *       （口径管理工具 / 运维脚本）提供，并落进审计 {@code actor} ——
 *       那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 幂等重放【也】写审计 —— 这是裁定，不是疏漏</h2>
 * 两态都写审计：{@link ScaleOutcome#mutatedData()} 与
 * {@link ScaleOutcome#idempotentReplay()}。
 * <p>理由与 B-11 逐字同款：库层 {@code ON CONFLICT DO NOTHING}
 * <b>不报错、不改数据、返回一个正常值</b>，于是"有人试图重复建一份量表"这件事，
 * 若不留痕就<b>在系统里完全不可见</b>。那可能是一次误传的 {@code scale_id}，
 * 也可能是一次真实的重复建档 —— 两者都值得在事后被看见。
 * <p>一句话边界：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>。
 *
 * <h2>🛑🛑 两条"不能继续"的冲突【都不】留审计 —— 这是本类相对 B-11 的增量</h2>
 * B-11 只有一条这种情形（{@code device_id} 被另一租户占用），
 * 本域有<b>两条</b>，且两条都走不到本类的审计语句：
 * <ol>
 *   <li>{@code scale_id} 被<b>另一租户</b>占用；</li>
 *   <li>同一 {@code scale_id} 在<b>本租户</b>名下已存在但<b>版本不同</b>。</li>
 * </ol>
 * 两者在 V19 里都是 {@code RAISE} ⇒ 事务在 {@code ledger.register()} 里就被异常终止，
 * 审计语句<b>根本不会执行</b>。
 * <p>🛑 这不是"漏了两条审计"，而是"这两种情形没有可留痕的成功动作"。
 * 若哪天有人希望它们也被留痕，正确的做法是<b>在库函数里先写一条审计再 RAISE</b>
 * （但那时审计行会被同一个 RAISE 一起回滚 —— 故实际上需要一条独立事务），
 * 或者由调用方在上层捕获后单独记录。
 * 本类刻意<b>不</b>在这里 try/catch 后补一条审计 —— 那会造出
 * "审计记了一次建档、而库里一行都没有"的不一致。
 * <p>⚠️ 为什么第 ② 条尤其容易被人"顺手修坏"：它看起来像一个"该被记下来的业务失败"，
 * 而实际上它是一条<b>数据冲突</b>。若有人给它补一条 {@code SCALE_REGISTER_REJECTED}
 * 审计，那条审计的 payload 里会包含"库中版本 vs 传入版本"——
 * 而 {@code audit_log} <b>无 RLS、全租户可读</b> ⇒ 等于把一个租户的量表版本号
 * 暴露给所有租户。这是一个具体的、可预见的后果，故此处写明。
 *
 * <h2>🛑 为什么审计写入必须与建档【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的 {@code register()} 内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * ⇒ {@code set_config('app.tenant_id', ..., is_local := true)} 建立的上下文
 * 活到<b>本事务提交</b>，而审计条目也落在同一个提交里。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把 {@code register()} 的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么建档会独立提交 —— 于是"审计失败则建档回滚"这条不变量<b>静默失效</b>。
 * 本仓对这一类有既定处置：把前提写成可断言的东西
 * （见 {@code ScaleProvisioningGateTest} 的"建档与审计同事务"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明建档确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与 {@code BandBindingService} / {@code OrganizationProvisioningService} /
 * {@code DeviceService} 逐字相同：
 * {@code AuditLogService.append()} 的契约写明 <b>id 由实现生成、忽略调用方传入的</b>。
 * B-7 的初版曾塞了一个自编的 {@code UUID.randomUUID()} 并当作回执返回 ——
 * 实测 {@code SELECT count(*) FROM audit_log WHERE id = ?} 返回 <b>0</b>。
 * 故本类构造 {@code AuditLog} 时第一个参数<b>刻意留空串</b>（占位、会被忽略），
 * 并显式取 {@code append()} 的返回值。
 */
@Service
public class ScaleService {

    private static final Logger log = LoggerFactory.getLogger(ScaleService.class);

    /** 建档动作名 —— 落在 {@code audit_log.action}。 */
    static final String ACTION_REGISTERED = "SCALE_REGISTERED";

    /** 废弃动作名。 */
    static final String ACTION_DEPRECATED = "SCALE_DEPRECATED";

    /** 审计目标的类型名（{@code audit_log.target_type}）。 */
    static final String TARGET_TYPE = "scale";

    private final ScaleLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public ScaleService(ScaleLedger ledger,
                        ObjectProvider<AuditLogService> auditLogService,
                        DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 建档
    // ==================================================================

    /**
     * 幂等往租户建一份量表，并在同一个事务里留一条审计。
     *
     * @param record   建档意图（其构造器会先跑一遍形态校验：必填 / 列长 / 租户标识 / 非空维度集）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的口径起点，事后无法解释"这份量表从哪来"）
     * @return 建档结果（两态之一 + 本次留痕的审计 id）
     */
    public ScaleProvisioningResult register(ScaleRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "量表建档：记录不可为空");
        }
        requireOperator(operator);

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            ScaleOutcome outcome = ledger.register(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串，防止下一个人
            //       误以为回执取的就是这个字段（B-7 已付费记录过这个缺陷）。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    ACTION_REGISTERED,
                    TARGET_TYPE,
                    record.scaleId().toString(),
                    Instant.now(),
                    registerPayload(record, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry);

            log.info("[SCALE-REGISTER] tenant={} scale={} type={} version={} mode={} operator={} auditId={}",
                    record.tenantId(), record.scaleId(), record.scaleType(),
                    record.scaleVersion(), outcome.name(), operator, auditId);

            return new ScaleProvisioningResult(outcome, auditId, record.scaleId());
        });
    }

    // ==================================================================
    // 废弃
    // ==================================================================

    /**
     * 幂等废弃一份量表，并在同一个事务里留一条审计。
     *
     * <p>🛑 是状态迁移（{@code status → 'deprecated'}）而不是删除行 —— 见
     * {@link ScaleLedger#deprecate} 与 V19 {@code deprecate_scale()} 的注释。
     * <p>⚠️ {@link ScaleOutcome#NOT_FOUND} 在这里是<b>正常返回</b>而非异常：
     * 它与"存在但当前上下文看不见"同形（FORCE RLS），
     * 故把它当成异常会让"租户上下文设错了"这条本来可查的问题变成一个 500。
     * 调用方看到该态应先复核租户上下文。
     */
    public ScaleProvisioningResult deprecate(String tenantId, UUID scaleId, String operator) {
        requireOperator(operator);
        if (scaleId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "废弃量表：scale_id 必填");
        }

        return tx.execute(status -> {
            ScaleOutcome outcome = ledger.deprecate(tenantId, scaleId);

            AuditLog entry = new AuditLog(
                    "",
                    tenantId,
                    operator,
                    ACTION_DEPRECATED,
                    TARGET_TYPE,
                    scaleId.toString(),
                    Instant.now(),
                    deprecatePayload(outcome),
                    null, null);

            String auditId = appendAuditOrFail(entry);

            log.info("[SCALE-DEPRECATE] tenant={} scale={} mode={} operator={} auditId={}",
                    tenantId, scaleId, outcome.name(), operator, auditId);

            return new ScaleProvisioningResult(outcome, auditId, scaleId);
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该量表在本租户内的状态（无则 {@code null}）。 */
    public String statusOf(String tenantId, UUID scaleId) {
        return ledger.statusOf(tenantId, scaleId);
    }

    /** 该量表在本租户内的版本号（无则 {@code null}）。 */
    public String versionOf(String tenantId, UUID scaleId) {
        return ledger.versionOf(tenantId, scaleId);
    }

    /** 该租户内当前有效量表数。 */
    public int countActiveInTenant(String tenantId) {
        return ledger.countActiveInTenant(tenantId);
    }

    /** 该租户内已废弃量表数（供"废弃行仍占主键"这条性质可断言）。 */
    public int countDeprecatedInTenant(String tenantId) {
        return ledger.countDeprecatedInTenant(tenantId);
    }

    /**
     * 🛑🛑 <b>契约 C2 端点是否已解锁</b> —— 见
     * {@link ScaleLedger#canReferenceBaseline}。
     *
     * <p>暴露到 Service 层是为了让"本迁移的验收口径"能被上层直接问出来：
     * 「这个 {@code scale_id} 现在能不能被 C2 基线评估引用」。
     * <p>🛑 它回答的是 <b>C2 那道业务预检会不会通过</b>
     * （即 {@code AssessmentService} 第 ③ 步的 {@code scaleExists} 结果），
     * <b>不是</b>"库层会不会报 23503"—— 这条链根本不产生 23503。
     * 若照抄 B-11 的验收话术，这条断言会恒真。
     */
    public boolean canBeUsedForBaseline(String tenantId, UUID scaleId) {
        return ledger.canReferenceBaseline(tenantId, scaleId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "量表建档/废弃必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。量表是口径资产（决定基线评估怎么打分），"
                            + "而 baseline_assessment 是客户档案的一部分（locked=TRUE，不可改）："
                            + "一次无主的建档，等于一次事后无法解释『这份量表从哪来』的口径起点");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与 {@code DeviceService#appendAuditOrFail} 同款。
     * <p>🛑 本类多一条理由而<b>不</b>只是照抄：{@code scale} 是 C2 的打分依据，
     * 而 C2 的产物（baseline_assessment）<b>不可修改</b>（{@code locked = TRUE}）。
     * 若允许降级，会出现"量表存在、但其来源不可考"——
     * 而那时连"某个客户的基线分数按哪一版量表算的"都无法回答，
     * 且因为档案已锁定，<b>无法事后修正</b>。
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行量表建档/废弃。"
                            + "scale 是 C2 基线评估的打分依据，而 baseline_assessment 是"
                            + "客户档案的一部分（locked=TRUE，不可修改）。"
                            + "一次没有审计的建档无法事后补证『这份量表从哪来、谁建的』，"
                            + "且档案已锁定、无法事后修正。故此处不回退为『建档继续、日志降级』");
        }
        return svc.append(entry);
    }

    /**
     * 建档审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <p>🛑 克制原则与 {@code DeviceService#registerPayload} 同款，但边界<b>不同</b>：
     * 那里是"不写 {@code param_template_id}"，这里是"<b>不写 {@code dimension_set_json}</b>"。
     * <p>理由：{@code audit_log} 是<b>无 RLS、全租户可读</b>的
     * （见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * {@code dimension_set_json} 是"这个租户的基线评估按哪几个维度打分"——
     * 那是<b>评分口径</b>，属经营信息（够据此反推出该租户的评估方法论）。
     * <p>🛑 为什么 {@code scale_type} 与 {@code scale_version} 可以写：
     * 两者都只有有限取值（{@code primary / calibration}；版本号如 {@code v1}），
     * 是"这份量表属于哪一类 / 第几版"这种不可反推经营细节的标识。
     * 要查量表内容应查 {@code scale} 表本身，那里有 FORCE RLS 保护。
     * <p>⚠️ 本仓对这个形态的教训：{@code scale_version} 在<b>冲突诊断</b>里是有价值的
     * （它能立刻回答"库里是 v1、你传的是 v2"），故很容易被人"顺手加进 payload"。
     * 但冲突那条根本走不到这里（V19 里是 RAISE），
     * 所以把它加进来<b>只会增加泄漏面而换不到任何诊断能力</b>。
     */
    private static String registerPayload(ScaleRecord r, ScaleOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"scaleId\":\"" + r.scaleId()
                + "\",\"scaleType\":\"" + r.scaleType()
                + "\",\"scaleVersion\":\"" + r.scaleVersion()
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /** 废弃审计 payload。 */
    private static String deprecatePayload(ScaleOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /**
     * 建档 / 废弃的结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次变更的审计证据是哪一条"）。回执存在的唯一理由就是对账，
     * 故它必须是库里真实的那个 id（见类注释「为什么审计条目 id 必须取自返回值」）。
     */
    public record ScaleProvisioningResult(ScaleOutcome outcome, String auditId, UUID scaleId) {

        /** 本次调用是否真的改动了台账数据。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }
    }
}