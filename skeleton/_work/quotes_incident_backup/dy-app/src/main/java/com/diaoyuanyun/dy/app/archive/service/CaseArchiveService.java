package com.diaoyuanyun.dy.app.archive.service;

import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveOutcome;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveRecord;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveSnapshot;
import com.diaoyuanyun.dy.app.archive.repository.CaseArchiveLedger;
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
 * <b>结案归档服务</b> —— 把「把一张退款工单收口成归档档案」从一次裸的数据库写入，
 * 变成一次<b>带审计留痕的、原子的、可重放的</b>结案动作。
 *
 * <h2>🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>审计留痕</b>：{@code case_archive} <b>不是</b>一份登记表 —— 它是
 *       PRD P0-14「四种结局全部强制归档」的落纸载体，也是审计回溯
 *       「这次结案有没有按 C.3 走完」时的<b>唯一证据</b>。
 *       ⇒ 一次无痕的归档，等于<b>一份无主的举证材料</b>：
 *       事后无法回答"这份档案什么时候、由谁落进来的"，
 *       而"四种结局是否全部归档"这条合规指标恰恰要回到这个起点。
 *       <p>🛑 与前三批的审计理由<b>不同</b>，必须说清：
 *       B-10（手环）的理由是"应戴天分母的两端 → 影响 A3 → 影响退款资格"；
 *       B-11（设备）的理由是"可追责性需要起点"；
 *       B-12（量表）的理由是"基线档案 locked=TRUE 不可修改，一次无留痕的建档无法事后修正"；
 *       <b>本类的理由是"归档是一次不可撤销的收口，而收口的唯一痕迹就是这条审计"</b>。
 *       四条理由都指向"必须同事务审计"，但它们不是同一条理由 ——
 *       若混为一谈，下一个人会以为改掉其中一个的审计纪律是可以的。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写
 *       {@code created_by='case-archive-registration'}。真正的操作者由本类的调用方
 *       （结案工具 / 运维脚本）提供，并落进审计 {@code actor} ——
 *       那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 幂等重放【也】写审计 —— 这是裁定，不是疏漏</h2>
 * 两态都写审计：{@link CaseArchiveOutcome#mutatedData()}（真落了档案）与
 * {@link CaseArchiveOutcome#idempotentReplay()}（重放，数据未变）。
 * <p>理由与前三批逐字同款，但本处有一层特别的含义：
 * 库层 {@code ON CONFLICT DO NOTHING} <b>不报错、不改数据、返回一个正常值</b>，
 * 于是"有人试图重复归档同一张工单"这件事，若不留痕就<b>在系统里完全不可见</b>。
 * 在别的域那可能是误传 id；在本域它还可能意味着
 * <b>"同一个客户被两份档案分别收口"这类举证冲突的早期信号</b>。
 * <p>一句话边界：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>。
 *
 * <h2>🛑 跨租户撞号【不】留审计 —— 与前三批同款处置，理由必须写清</h2>
 * V20 对"archive_id 被另一租户占用"是 <b>RAISE</b> ⇒ 它<b>根本走不到</b>
 * 本类的审计语句：事务在 {@code ledger.register()} 里就被异常终止了。
 * <p>🛑 这不是"漏了一条审计"，而是"这个情形没有可留痕的成功动作"。
 * 若哪天有人希望它也被留痕，正确做法是<b>在库函数里先写一条审计再 RAISE</b>
 * （但那时审计行会被同一个 RAISE 一起回滚 —— 故实际上需要一条独立事务），
 * 或者由调用方在上层捕获后单独记录。本类刻意<b>不</b>在这里 try/catch 后补一条审计
 * —— 那会造出"审计记了一次归档、而库里一行都没有"的不一致，
 * 而那正是本域<b>最不能出现</b>的形态（一份被声称存在的举证材料）。
 *
 * <h2>🛑 为什么审计写入必须与归档【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的 {@code register()} 内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * ⇒ {@code set_config('app.tenant_id', ..., is_local := true)} 建立的上下文
 * 活到<b>本事务提交</b>，而审计条目也落在同一个提交里。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把 {@code register()} 的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么归档会独立提交 —— 于是"审计失败则归档回滚"这条不变量<b>静默失效</b>。
 * 本仓对这一类有既定处置：把前提写成可断言的东西
 * （见 {@code CaseArchiveGateTest} 的"归档与审计同事务"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明归档确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与 {@code BandBindingService} / {@code DeviceService} /
 * {@code OrganizationProvisioningService} 逐字相同：
 * {@code AuditLogService.append()} 的契约写明 <b>id 由实现生成、忽略调用方传入的</b>。
 * B-7 的初版曾塞了一个自编的 {@code UUID.randomUUID()} 并当作回执返回 ——
 * 实测 {@code SELECT count(*) FROM audit_log WHERE id = ?} 返回 <b>0</b>。
 * 故本类构造 {@code AuditLog} 时第一个参数<b>刻意留空串</b>（占位、会被忽略），
 * 并显式取 {@code append()} 的返回值。
 *
 * <h2>🛑 本类【不】校验 {@code finalConclusion} 的"退款终止时必填"（与 record 同款）</h2>
 * <p>PRD C.1.7 逐字写「{@code case_archive.final_conclusion / refund_amount / amount_basis}
 * | 否 | <b>退款终止时必填</b>」。而本类同样<b>拿不到 refund</b>：
 * {@code refund} 与 {@code case_archive} <b>没有任何关联列</b>
 * （实测：{@code case_archive} 的 3 条约束里既没有 {@code refund_id}，
 * {@code refund} 的 20 列里也没有 {@code archive_id}）。
 * ⇒ 本类<b>没有输入</b>能回答"这次是不是退款终止"。
 * <p>故本类把这个校验点<b>显式地空着</b>，并在
 * {@link #register(CaseArchiveRecord, String, boolean)} 的参数里加一个
 * {@code refundTerminated} 开关 —— <b>把判定权交给唯一知道答案的调用方</b>。
 * 这样做的理由是：调用方（退款链路）<b>手里有</b> {@code refund.outcome}，
 * 而本类看不到；若本类硬写 {@code if (finalConclusion == null) throw}，
 * 那等价于<b>要求一切归档都必须填结论</b>（包括"继续"之后归档的）——
 * 一条比 PRD 更严的、代拍出来的规则。
 * <p>🛑 见 V20 文件头「待裁登记」第 3 条：这条口径落差的<b>根因是表结构缺口</b>
 * （两张表之间没有关联列），不是一个可以用参数默认值绕过去的细节。
 */
@Service
public class CaseArchiveService {

    private static final Logger log = LoggerFactory.getLogger(CaseArchiveService.class);

    /** 归档动作名 —— 落在 {@code audit_log.action}。 */
    static final String ACTION_ARCHIVED = "CASE_ARCHIVED";

    /**
     * 🛑 重放动作名<b>刻意与首次不同</b>（不是同一个 action + 不同 payload）。
     *
     * <p>理由：{@code audit_log} 的检索习惯是"按 action 找一批"。
     * 若重放与首次共用 action，那么"本季度归档了多少张工单"这类统计
     * 会因为<b>把重放也算进去</b>而虚高 —— 而重放本来就不代表一次新的收口。
     * 一个独立的 action 让"审计里出现重放"这件事既可见、又不会污染计数。
     * <p>🛑 这与前三批不同（它们两态共用 action、靠 payload 的 mode 区分）——
     * 差别不是风格选择，而是本域多了一条"审计会被用来计数合规率"的用法
     * （PRD G 类指标里"归档率"是一个取数项）。
     */
    static final String ACTION_ARCHIVE_REPLAYED = "CASE_ARCHIVE_REPLAYED";

    /** 审计目标的类型名（{@code audit_log.target_type}）。 */
    static final String TARGET_TYPE = "case_archive";

    private final CaseArchiveLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public CaseArchiveService(CaseArchiveLedger ledger,
                              ObjectProvider<AuditLogService> auditLogService,
                              DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 归档
    // ==================================================================

    /**
     * 幂等归档一张工单，并在同一个事务里留一条审计（便捷重载：默认
     * {@code refundTerminated = true}）。
     *
     * <p>🛑 <b>为什么默认值是 {@code true}</b>（这个选择必须写清，否则是个陷阱）：
     * 本原语的语义是"<b>把工单收口</b>"，而在 PRD 的四种结局里，
     * 需要落归档档案的路径都发生在<b>退款已被判定终止</b>之后
     * （见 {@code CaseArchiveRecord} 类注释引的 P0-14 与 C.1.7）。
     * 即："调用归档"这件事本身就强烈暗示"这是一个终止态"。
     * <p>但默认不代表免检 —— 若调用方明确知道这次不是终止态归档，
     * 必须走三参重载并显式传 {@code false}。<b>默认为 true 的效果是
     * "少填一个参数会得到更严的校验"，而不是"少填一个参数会放过一条规则"</b>。
     * 这是本仓对"默认值方向"的一贯取舍：默认值应该让遗漏表现为<b>假红</b>
     * （一次可读的 403），而不是假绿（一条合规规则被静默跳过）。
     */
    public CaseArchiveResult register(CaseArchiveRecord record, String operator) {
        return register(record, operator, true);
    }

    /**
     * 幂等归档一张工单，并在同一个事务里留一条审计。
     *
     * @param record           归档意图（其构造器会先跑一遍形态与门禁校验）
     * @param operator         操作者标识（写进审计 {@code actor}；必填 ——
     *                         一次无主的归档等于一份无主的举证材料）
     * @param refundTerminated 🛑 <b>本次归档是否发生在"退款终止"之后</b>。
     *                         由调用方提供，因为只有调用方手里有 {@code refund.outcome}
     *                         （两张表无关联列，见类注释）。
     *                         {@code true} ⇒ 强制要求 {@code finalConclusion} 非空，
     *                         否则抛 {@code GATE_MISSING}（对应 C.1.7「退款终止时必填」）。
     *                         {@code false} ⇒ 不检查结论（"继续"之后归档的档案可以不填）。
     * @return 归档结果（两态之一 + 本次留痕的审计 id）
     */
    public CaseArchiveResult register(CaseArchiveRecord record, String operator,
                                      boolean refundTerminated) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "结案归档：记录不可为空");
        }
        requireOperator(operator);

        // 🛑 这一条"退款终止时必填"的校验【只在这里】—— 见类注释说明为何不能在
        //    record（拿不到 refund）或 V20 函数（同样拿不到）里做。
        //    放在本方法内、事务外：它是一次纯参数校验，不该开事务。
        if (refundTerminated && (record.finalConclusion() == null
                || record.finalConclusion().isBlank())) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "结案归档被阻断：本次归档发生在【退款终止】之后，"
                            + "但 final_conclusion 为空。口径来源 = PRD C.1.7 逐字："
                            + "「case_archive.final_conclusion / refund_amount / amount_basis "
                            + "| 否 | 退款终止时必填」。"
                            + "🛑 本条由服务层执行（record 与 V20 函数都拿不到 refund.outcome —— "
                            + "refund 与 case_archive 之间没有关联列），"
                            + "故判定权交给唯一知道答案的调用方（refund 链路的 refundTerminated 入参）。"
                            + "报 GATE_MISSING(2002, 403) 与 P0-25『硬阻断规则缺项 → 403』同口径");
        }

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            CaseArchiveOutcome outcome = ledger.register(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串，防止下一个人
            //       误以为回执取的就是这个字段（B-7 已付费记录过这个缺陷）。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    outcome.mutatedData() ? ACTION_ARCHIVED : ACTION_ARCHIVE_REPLAYED,
                    TARGET_TYPE,
                    record.archiveId().toString(),
                    Instant.now(),
                    payload(record, outcome, refundTerminated),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry);

            log.info("[CASE-ARCHIVE] tenant={} archive={} customer={} mode={}"
                            + " desensitizeAuthorized={} operator={} auditId={}",
                    record.tenantId(), record.archiveId(), record.customerId(),
                    outcome.name(), record.desensitizeAuthorized(), operator, auditId);

            return new CaseArchiveResult(outcome, auditId, record.archiveId(),
                    record.customerId());
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该客户在本租户内的最新归档档案 id（无则 {@code null}）。 */
    public UUID latestArchiveOf(String tenantId, UUID customerId) {
        return ledger.latestArchiveOf(tenantId, customerId);
    }

    /** 读回一整行归档档案（本租户内不可见时 {@code null}）。 */
    public CaseArchiveSnapshot snapshotOf(String tenantId, UUID archiveId) {
        return ledger.snapshotOf(tenantId, archiveId);
    }

    /** 该租户内的归档档案行数（🛑 单独不足以作为验收口径 —— 见仓储同名方法注释）。 */
    public int countInTenant(String tenantId) {
        return ledger.countInTenant(tenantId);
    }

    /** 该客户在本租户内是否已有归档档案。 */
    public boolean isArchived(String tenantId, UUID customerId) {
        return ledger.isArchived(tenantId, customerId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结案归档必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。归档是一次不可撤销的收口动作，"
                            + "而它的唯一痕迹就是这条审计：一次无主的归档，"
                            + "等于一份事后无法说明来源的举证材料");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与 {@code BandBindingService} / {@code DeviceService} /
     * {@code OrganizationProvisioningService} 同一取舍，且本类的理由最直接：
     * 若允许降级，会出现"归档档案存在但没有归档记录"——
     * 而那时连"这份档案是谁收口的"都不可答。<b>对本域而言这不是一条审计缺口，
     * 而是一份证据缺了它的来源链</b>，故此处不回退为"归档继续、日志降级"。
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行结案归档。"
                            + "case_archive 是 PRD P0-14『四种结局全部强制归档』的落纸载体，"
                            + "也是审计回溯『这次结案有没有按 C.3 走完』时的唯一证据；"
                            + "一次没有审计的归档无法事后补证『这份档案从哪来、谁收口的』。"
                            + "故此处不回退为『归档继续、日志降级』");
        }
        return svc.append(entry);
    }

    /**
     * 归档审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <h2>🛑 本域的克制原则比前三批更严：清单与签名内容一律不入审计</h2>
     * {@code audit_log} 是<b>无 RLS、全租户可读</b>的
     * （见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 故这里只写<b>标识与模式</b>：
     * {@code archive_id} / {@code customer_id}（都是 UUID）+ 本次走的是哪条路径 +
     * 两个<b>不承载业务内容</b>的事实（是否已脱敏授权、硬门禁是否齐备）。
     * <p>🛑 <b>为什么不写 {@code final_conclusion}</b>：它是"这次结案怎么定的"，
     * 属<b>结案内容</b>，落进全租户可读的表等于把一份客户的处理结论公开给所有租户。
     * 与 {@code DeviceService} 不写 {@code param_template_id}（经营信息）
     * 是同一条纪律，但本域的内容更敏感。
     * <p>🛑 <b>为什么不写清单的 6 项与签名的 4 键</b>：它们是<b>举证材料本身</b>。
     * 审计只需回答"有这么一次归档"，不需要回答"这份举证材料里写了什么"——
     * 后者要查 {@code case_archive} 表本身，那里有 RLS 保护。
     * 把证据内容复制到无 RLS 的表里，等于给 RLS 开了一条旁路。
     *
     * <p>🛑 两个布尔值的选择理由（它们是本 payload 唯一"像内容"的东西）：
     * {@code desensitizeAuthorized} 是一条<b>合规开关</b>（§2.22「脱敏须单独授权」），
     * 它的告警价值高于它的敏感性；{@code hardGatesSatisfied} 是
     * "这次归档按 C.3 走完了"这一点本身 —— 而这正是合规率的取数依据。
     * 两者都不含客户信息，故可入审计。
     */
    private static String payload(CaseArchiveRecord r, CaseArchiveOutcome o,
                                  boolean refundTerminated) {
        return "{\"mode\":\"" + o.name()
                + "\",\"archiveId\":\"" + r.archiveId()
                + "\",\"customerId\":\"" + r.customerId()
                + "\",\"mutated\":" + o.mutatedData()
                + ",\"refundTerminated\":" + refundTerminated
                + ",\"conclusionPresent\":" + (r.finalConclusion() != null)
                + ",\"desensitizeAuthorized\":" + r.desensitizeAuthorized()
                + ",\"handbandKeyPresent\":" + (r.checklist().get(
                        com.diaoyuanyun.dy.app.archive.domain.CaseArchiveChecklist
                                .HANDBAND_RECORDED_AS_REFERENCE.code()) != null)
                + "}";
    }

    /**
     * 归档 / 重放的结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次收口的审计证据是哪一条"）。回执存在的唯一理由就是对账，
     * 故它必须是库里真实的那个 id（见类注释「为什么审计条目 id 必须取自返回值」）。
     *
     * <p>🛑 回执里同时带 {@code archiveId} 与 {@code customerId}：
     * 调用方（退款链路）需要它们把 {@code refund.outcome} 置成 {@code 归档}
     * 并写自己的审计。若只回 {@code outcome}，调用方就得自己记住这两个值，
     * 而"用自己记的值去更新"与"用回执里的值去更新"在一次重放里可能不同 ——
     * 前者是本地变量，后者是库层确认过的。
     */
    public record CaseArchiveResult(CaseArchiveOutcome outcome, String auditId,
                                    UUID archiveId, UUID customerId) {

        /** 本次调用是否真的落了一份新的归档档案。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }

        /** 是否属于幂等重放（档案未新增，但这次调用已被审计留痕）。 */
        public boolean replayed() {
            return outcome.idempotentReplay();
        }
    }
}