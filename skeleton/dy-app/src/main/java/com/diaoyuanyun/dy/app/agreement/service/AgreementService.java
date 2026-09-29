package com.diaoyuanyun.dy.app.agreement.service;

import com.diaoyuanyun.dy.app.agreement.domain.AgreementOutcome;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementRecord;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementSnapshot;
import com.diaoyuanyun.dy.app.agreement.repository.AgreementLedger;
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
 * <b>调理协议书离线签署服务</b> —— 把「把一份已签署的调理协议书登记进系统」
 * 从一次裸的数据库写入，变成一次<b>带审计留痕的、原子的、可重放的</b>动作。
 *
 * <h2>🛑🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>应用层 hash 重算校验 —— 这是本类最重要的职责</b>：
 *       实测真库 {@code pg_extension} 只有 plpgsql ⇒ {@code pgcrypto} 未装
 *       ⇒ {@code digest()} 不存在 ⇒ <b>库层无法验 hash</b>。
 *       V21 的 {@code register_agreement()} 只能校验 hash 的<b>形态</b>，
 *       并在注释与错误消息里逐字声明"不得把本条读成『库层已校验 hash 内容』"。
 *       ⇒ 那句话所指的落点<b>就是本类</b>：{@link #register} 在写入前调用
 *       {@link AgreementRecord#renderedHashMatches()}（复用
 *       {@code DocFileService.sha256()} 的同一口径）做重算比对。
 *       <p>🛑 <b>若把这个校验删掉，"渲染稿 + hash"这套举证设计就退化成
 *       "库里存了两列、而它们的关系从未被验证"</b> ——
 *       一份被篡改的正文配一个旧的 hash 会静默通过。</li>
 *   <li><b>审计留痕</b>：{@code agreement} 是 PRD C.1.5 §八 四方签署硬门禁的落纸载体，
 *       也是 G1「签署合规率」的<b>唯一取数来源</b>。
 *       ⇒ 一次无痕的签署登记，等于<b>一份无主的举证材料</b>：
 *       事后无法回答"这份协议什么时候、由谁登进来的"，
 *       而"已签客户占比"这条指标恰恰要回到这个起点。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写
 *       {@code created_by='agreement-offline-signing'}。真正的操作者由本类的调用方
 *       （运维脚本 / 门店后台上传流程）提供，并落进审计 {@code actor} ——
 *       那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 幂等重放【也】写审计 —— 这是裁定，不是疏漏</h2>
 * 两态都写审计：{@link AgreementOutcome#mutatedData()}（真落了协议）与
 * {@link AgreementOutcome#idempotentReplay()}（重放，数据未变）。
 * <p>理由与归档域逐字同款，但本处有一层特别的含义：
 * 库层 {@code ON CONFLICT DO NOTHING} <b>不报错、不改数据、返回一个正常值</b>，
 * 于是"有人试图重复登记同一份协议"这件事，若不留痕就<b>在系统里完全不可见</b>。
 * 在别的域那可能是误传 id；在本域它还可能意味着
 * <b>"同一个客户被两份协议分别登记"这类举证冲突的早期信号</b>。
 * <p>一句话边界：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>。
 *
 * <h2>🛑 跨租户撞号【不】留审计 —— 与归档域同款处置，理由必须写清</h2>
 * V21 对"agreement_id 被另一租户占用"是 <b>RAISE</b> ⇒ 它<b>根本走不到</b>
 * 本类的审计语句：事务在 {@code ledger.register()} 里就被异常终止了。
 * <p>🛑 这不是"漏了一条审计"，而是"这个情形没有可留痕的成功动作"。
 * 本类刻意<b>不</b>在这里 try/catch 后补一条审计 —— 那会造出
 * "审计记了一次签署、而库里一行都没有"的不一致，
 * 而那正是本域<b>最不能出现</b>的形态（一个被声称已签而实际未签的协议）——
 * 它会立刻让 {@code CustomerGateGuard} 放行首次调理。
 *
 * <h2>🛑 为什么审计写入必须与签署登记【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的 {@code register()} 内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把 {@code register()} 的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么签署登记会独立提交 —— 于是"审计失败则签署回滚"这条不变量<b>静默失效</b>。
 * 本仓对这一类有既定处置：把前提写成可断言的东西
 * （见 {@code AgreementGateTest} 的"签署与审计同事务"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明签署确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与归档域逐字相同：{@code AuditLogService.append()} 的契约写明
 * <b>id 由实现生成、忽略调用方传入的</b>。故本类构造 {@code AuditLog} 时
 * 第一个参数<b>刻意留空串</b>（占位、会被忽略），并显式取 {@code append()} 的返回值。
 *
 * <h2>🛑 本类【不】校验"客户当前状态必须是 PLAN_APPROVED"—— 登记为已知边界</h2>
 * PRD §八 逐字写「<b>未签不得首次调理或退款判定</b>」，且第 7 项 gating 动作
 * 逐字写「签调理协议书 → {@code agreement_signed} → 硬门禁③」。
 * <p>⇒ 这条门禁的<b>消费点</b>在 {@code CustomerGateGuard}
 * （实测该类已登记 {@code PLAN_APPROVED → AGREEMENT_SIGNED} 的跃迁），
 * <b>不在</b>本类。
 * <p>🛑 为什么本类做不到它：本类<b>读不到</b> {@code customer.state}（那是 customer 域），
 * 且若在这里硬写一条"客户必须处于 PLAN_APPROVED"，等价于<b>代拍</b>
 * "签署动作必须紧跟方案通过"这条业务规则 —— 而 PRD <b>没有</b>说
 * "不能在别的状态下补签"（现实中离线纸面协议完全可能晚于方案批准几天才登记）。
 * <p>⇒ 见 V21 文件头「待裁登记」第 3 条 —— 与归档域"refund ↔ case_archive
 * 无关联列"同族：都是"某条 PRD 规则需要一个本层拿不到的输入"。
 *
 * <h2>🛑🛑 本类不是 I8（{@code renderAgreement}），也不是 H1（{@code receiveEsignCallback}）</h2>
 * 契约对这两条都写了 {@code x-client-forbidden: true} + {@code x-callable-roles: []}
 * + {@code x-frontier: 占位待冻结}，并逐字声明
 * 「厂商选定之前，三端不得据本节编码」。本类是<b>离线签署通路</b>的运维组件：
 * <b>无 HTTP 映射、不渲染、不推送、不接回调、不解析任何厂商签名</b>。
 * 渲染稿与 hash 是<b>入参</b>。
 */
@Service
public class AgreementService {

    private static final Logger log = LoggerFactory.getLogger(AgreementService.class);

    /** 签署动作名 —— 落在 {@code audit_log.action}。 */
    static final String ACTION_AGREEMENT_SIGNED = "AGREEMENT_SIGNED";

    /**
     * 🛑 重放动作名<b>刻意与首次不同</b>（不是同一个 action + 不同 payload）。
     *
     * <p>理由与归档域逐字同款：{@code audit_log} 的检索习惯是"按 action 找一批"。
     * 若重放与首次共用 action，那么"本季度签署了多少份协议"这类统计
     * 会因为<b>把重放也算进去</b>而虚高 —— 而重放本来就不代表一次新的签署。
     * 一个独立的 action 让"审计里出现重放"这件事既可见、又不会污染计数。
     * <p>🛑 本域更依赖这条：G1「签署合规率」的取数会回到 {@code agreement} 表，
     * 而运维对账会回到审计。两者都必须能区分"真签了一份"与"有人重放了一次"。
     */
    static final String ACTION_AGREEMENT_REPLAYED = "AGREEMENT_SIGN_REPLAYED";

    /** 审计目标的类型名（{@code audit_log.target_type}）。 */
    static final String TARGET_TYPE = "agreement";

    private final AgreementLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public AgreementService(AgreementLedger ledger,
                            ObjectProvider<AuditLogService> auditLogService,
                            DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 签署登记
    // ==================================================================

    /**
     * 幂等登记一份已签署的协议，并在同一个事务里留一条审计。
     *
     * @param record   签署登记意图（其构造器会先跑一遍形态与门禁校验）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的签署登记等于一份无主的举证材料）
     * @return 登记结果（两态之一 + 本次留痕的审计 id）
     */
    public AgreementResult register(AgreementRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "协议签署登记：记录不可为空");
        }
        requireOperator(operator);

        // 🛑🛑 这一条是本类最不可删的校验 —— 库层做不到它（无 pgcrypto ⇒ 无 digest()）。
        //    放在事务外：它是一次纯计算校验，不该开事务（也不该在库往返之后再发现失败）。
        if (!record.renderedHashMatches()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "协议签署登记被拒绝：rendered_hash 与 rendered_snapshot **重算不一致**。"
                            + "登记 hash = 『" + record.renderedHash() + "』，"
                            + "对正文重算得到 = 『" + record.recomputedRenderedHash() + "』"
                            + "（正文长度 " + record.renderedSnapshot().length() + " 字符）。"
                            + "口径来源 = PRD P0-27 逐字：「渲染结果必须落快照 + hash"
                            + "（否则事后无法证明『当时签的是哪一版』）」。"
                            + "🛑 为什么这条校验在应用层而不是库层：实测真库 pg_extension 仅有 plpgsql，"
                            + "pgcrypto 未安装 ⇒ 库层没有 digest()，无法从正文重算 SHA-256；"
                            + "V21 的 register_agreement() 只能校验 hash 的**形态**（64 位 hex），"
                            + "并已逐字声明『不得把本条读成库层已校验 hash 内容』。"
                            + "🛑 三种常见真因：① 正文被改动而 hash 未同步；② 算 hash 时用了不同编码"
                            + "（CRLF/LF、BOM、字符集）；③ 传错了另一份文档的 hash。"
                            + "报 BUSINESS_RULE_VIOLATED(5001, 422)：这是内容不自洽，不是参数格式错");
        }

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            AgreementOutcome outcome = ledger.register(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    outcome.mutatedData() ? ACTION_AGREEMENT_SIGNED : ACTION_AGREEMENT_REPLAYED,
                    TARGET_TYPE,
                    record.agreementId().toString(),
                    Instant.now(),
                    payload(record, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry);

            log.info("[AGREEMENT] tenant={} agreement={} customer={} plan={}v{}"
                            + " mode={} hashMatched=true operator={} auditId={}",
                    record.tenantId(), record.agreementId(), record.customerId(),
                    record.planId(), record.planVersion(),
                    outcome.name(), operator, auditId);

            return new AgreementResult(outcome, auditId, record.agreementId(),
                    record.customerId());
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该客户在本租户内的最新协议 id（无则 {@code null}）。 */
    public UUID latestAgreementOf(String tenantId, UUID customerId) {
        return ledger.latestAgreementOf(tenantId, customerId);
    }

    /** 读回一整行协议（本租户内不可见时 {@code null}）。 */
    public AgreementSnapshot snapshotOf(String tenantId, UUID agreementId) {
        return ledger.snapshotOf(tenantId, agreementId);
    }

    /** 该租户内的协议行数（🛑 单独不足以作为验收口径 —— 见仓储同名方法注释）。 */
    public int countInTenant(String tenantId) {
        return ledger.countInTenant(tenantId);
    }

    /** 该客户在本租户内是否已有协议。 */
    public boolean hasAgreement(String tenantId, UUID customerId) {
        return ledger.hasAgreement(tenantId, customerId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。签署登记是 G1「签署合规率」的取数起点，"
                            + "而它的唯一痕迹就是这条审计：一次无主的登记，"
                            + "等于一份事后无法说明来源的举证材料");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与归档域同一取舍，且本类的理由同样直接：
     * 若允许降级，会出现"协议存在但没有签署登记审计"——
     * 而那时连"这份协议是谁登进来的"都不可答。<b>对本域而言这不是一条审计缺口，
     * 而是一份证据缺了它的来源链</b>，故此处不回退为"签署继续、日志降级"。
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行协议签署登记。"
                            + "agreement 是 PRD C.1.5 §八 四方签署硬门禁的落纸载体，"
                            + "也是 G1「签署合规率」的唯一取数来源；"
                            + "一次没有审计的登记无法事后补证『这份协议从哪来、谁登的』。"
                            + "故此处不回退为『签署继续、日志降级』");
        }
        return svc.append(entry);
    }

    /**
     * 签署审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <h2>🛑 本域的克制原则：签署人姓名、条款原文、渲染稿一律不入审计</h2>
     * {@code audit_log} 是<b>无 RLS、全租户可读</b>的
     * （见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 故这里只写<b>标识与模式</b>：
     * {@code agreement_id} / {@code customer_id} / {@code plan_id}（都是 UUID）
     * + 本次走的是哪条路径 + 几个<b>不承载业务内容</b>的事实。
     * <p>🛑 <b>为什么不写签署人姓名（{@code signer} 的 4 个值）</b>：
     * 它们是<b>举证材料本身</b>，且含<b>自然人姓名</b>。
     * 审计只需回答"有这么一次签署登记"，不需要回答"这 4 个人分别叫什么"——
     * 后者要查 {@code agreement} 表本身，那里有 RLS 保护。
     * 把证据内容（尤其是个资）复制到无 RLS 的表里，等于给 RLS 开了一条旁路。
     * <p>🛑 <b>为什么不写条款快照 / 渲染稿 / 渲染 hash</b>：同上，属举证内容。
     * <p>🛑 三个计数/布尔的选择理由（它们是本 payload 唯一"像内容"的东西）：
     * {@code signerKeyCount} = 4 是"四方齐备"这一点本身（构造器已保证，此处只是留痕）；
     * {@code refundClauseKeyCount} 是条款快照的<b>规模</b>（不含内容）；
     * {@code hasDocTemplate} 是"这份协议是否指明了模板版本"（P0-27 的可追溯性开关）。
     * 三者都不含客户信息或个资，故可入审计。
     */
    private static String payload(AgreementRecord r, AgreementOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"agreementId\":\"" + r.agreementId()
                + "\",\"customerId\":\"" + r.customerId()
                + "\",\"planId\":\"" + r.planId()
                + "\",\"planVersion\":" + r.planVersion()
                + ",\"mutated\":" + o.mutatedData()
                + ",\"signerKeyCount\":" + r.signer().size()
                + ",\"refundClauseKeyCount\":" + r.refundClauseSnapshot().size()
                + ",\"breachClausePresent\":" + (r.breachClauseSnapshot() != null)
                + ",\"hasDocTemplate\":" + (r.docTemplateId() != null)
                + ",\"renderedLength\":" + r.renderedSnapshot().length()
                + "}";
    }

    /**
     * 签署 / 重放的结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次签署的审计证据是哪一条"）。
     *
     * <p>🛑 回执里同时带 {@code agreementId} 与 {@code customerId}：
     * 调用方（门店后台流程 / 运维脚本）需要它们把客户推进 {@code AGREEMENT_SIGNED}
     * 并写自己的审计。若只回 {@code outcome}，调用方就得自己记住这两个值，
     * 而"用自己记的值去更新"与"用回执里的值去更新"在一次重放里可能不同 ——
     * 前者是本地变量，后者是库层确认过的。
     */
    public record AgreementResult(AgreementOutcome outcome, String auditId,
                                  UUID agreementId, UUID customerId) {

        /** 本次调用是否真的落了一份新的签署登记。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }

        /** 是否属于幂等重放（协议未新增，但这次调用已被审计留痕）。 */
        public boolean replayed() {
            return outcome.idempotentReplay();
        }
    }
}