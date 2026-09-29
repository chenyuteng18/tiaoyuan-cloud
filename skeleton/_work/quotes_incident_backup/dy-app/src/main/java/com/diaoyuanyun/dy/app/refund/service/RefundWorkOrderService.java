package com.diaoyuanyun.dy.app.refund.service;

import com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole;
import com.diaoyuanyun.dy.app.refund.domain.RefundEntry;
import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundReasonCode;
import com.diaoyuanyun.dy.app.refund.domain.RefundRecordingPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RefundRetentionRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundRoute;
import com.diaoyuanyun.dy.app.refund.domain.RefundStatementRow;
import com.diaoyuanyun.dy.app.refund.domain.RefundSubjectVerifier;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderPort;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderRow;
import com.diaoyuanyun.dy.app.refund.domain.RetentionPolicy;
import com.diaoyuanyun.dy.app.refund.domain.RetentionResult;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 退款工单服务 —— 契约域 G1~G5 的<b>唯一业务编排点</b>。
 *
 * <h2>🛑 控制器<b>不</b>判业务，这里才判，且顺序固定</h2>
 * G1~G5 五条端点的共同前置是一条<b>可见性判定</b>：
 * <pre>
 *   ① RefundAudienceRole.ofTokenRole(tokenRole)    ← 你是谁（fail-closed，未登记即拒）
 *   ② RefundVisibilityMatrix.canSee(role)          ← 你能否看得见退款域
 *   ③ 具体动作的角色闸（G4 审批白名单 / 代录者不可审批）
 * </pre>
 * 三步都放在本类，而不是控制器里各写一份。理由与 {@code DerivedVisibilityInterceptor}
 * 的类注释同源：<b>散在控制器里的判定，下一个新端点必然漏掉一处</b>，
 * 而漏掉的表现是"这个接口刚好能用"，不报错、不告警。
 *
 * <p>🛑 步骤 ① 与 ② <b>不可合并</b>：前者回答"这个人是谁"，后者回答"他能不能看"。
 * 合并会让"未登记角色"与"已登记但不可见"得到同一个错误 —— 而前者说明
 * 有人用了系统不认识的角色码（配置侧问题），后者说明权限配置生效了（正常拒绝）。
 *
 * <h2>本类不做的事（各有其归处）</h2>
 * <table border="1">
 *   <tr><th>不做</th><th>归处</th><th>理由</th></tr>
 *   <tr><td>口径解析（七段配置 → 已归一对象）</td><td>{@code RefundProfileSource} + {@code RefundPolicy}</td>
 *       <td>解析与自洽校验必须只有唯一实现，否则每个来源各漏一个校验</td></tr>
 *   <tr><td>回执三态留痕</td><td>{@link RefundReceiptService}</td>
 *       <td>推送调用顺序必须由"持有推送器的那个类"决定，不得由调用方决定</td></tr>
 *   <tr><td>归档落 {@code case_archive}</td><td>待落地（S2-5 之后）</td>
 *       <td>归档要同时落六项清单与客户签字，属独立链路</td></tr>
 * </table>
 *
 * <h2>🛑 为什么"代录延迟"由本类算，而不是由库里算或由前端传</h2>
 * {@code refund.recording_delay_h} 是 {@code requested_at} 与 {@code recorded_at} 的差。
 * 若由前端传，它是<b>可伪造的</b>；若由库层触发器算，它的口径与
 * {@code RefundRecordingPolicy.evaluateDelay} 成为两份实现 ——
 * 而两份必然分叉，分叉的那一侧会决定"这张单子进不进异常名单"。
 * 故本类调用策略算出唯一值，库只存结果。
 */
@Service
public class RefundWorkOrderService {

    private final RefundWorkOrderPort ledger;
    private final RefundSubjectVerifier subjects;
    private final RefundPolicy policy;
    private final RefundVisibilityMatrix visibility;
    private final RefundRecordingPolicy recordingPolicy;
    private final RetentionPolicy retentionPolicy;

    public RefundWorkOrderService(RefundWorkOrderPort ledger,
                                  RefundSubjectVerifier subjects,
                                  RefundPolicy policy,
                                  RefundVisibilityMatrix visibility,
                                  RefundRecordingPolicy recordingPolicy,
                                  RetentionPolicy retentionPolicy) {
        this.ledger = ledger;
        this.subjects = subjects;
        this.policy = policy;
        this.visibility = visibility;
        this.recordingPolicy = recordingPolicy;
        this.retentionPolicy = retentionPolicy;
    }

    // ==================================================================
    // 零、可见性守卫（G1~G5 的唯一入口，三步固定顺序）
    // ==================================================================

    /**
     * 解析调用方身份并断言其可见退款域。
     *
     * <p>返回解析出的受众角色（供后续用例判断"他能不能审批"等）。
     * <b>不</b>返回 boolean：返回角色使调用方不必二次解析 ——
     * 而二次解析是"两处口径分叉"的经典入口（一处 trim 了、一处没 trim）。
     *
     * @throws BizException {@code VISIBILITY_DENIED(2001)} 角色未登记 / 不可见
     */
    public RefundAudienceRole requireVisible(String tokenRole) {
        // ① 你是谁 —— 未登记即拒，绝不回落（见 RefundAudienceRole.ofTokenRole 的注释）
        RefundAudienceRole role = RefundAudienceRole.ofTokenRole(tokenRole);
        // ② 你能否看见退款域 —— 矩阵是唯一真相源，硬锁（客户 / 调理师 / 门店客服）在构造期已锁死
        if (!visibility.canSee(role)) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "角色『" + role.label() + "』对退款域无可见性档位（config #40 / PRD §2.2）—— "
                            + "被拒角色: " + role.configKey() + "。" +
                            "🛑 本判定与『角色是否登记』是两件事："
                            + "前者说『配置生效了』，后者说『系统不认识这个角色』");
        }
        return role;
    }

    // ==================================================================
    // 一、G1 代录客户退款诉求
    // ==================================================================

    /**
     * G1 入参（对应契约 {@code RefundCreateRequest}）。
     *
     * <h2>🛑 为什么 {@code requestedAt} 与 {@code requestedAtClaimed} 是两个字段</h2>
     * 契约逐字：{@code requested_at}（最早且可核实）/ {@code requested_at_claimed}
     * （客户主张，仅留存不计时）。把它们合成一个字段，就无法回答
     * "客户说的时间为什么没被采纳" —— 而那条恰是 P0-19 拆分三字段的全部理由。
     *
     * <h2>凭据引用与调理师转交时间从哪来</h2>
     * 契约 {@code RefundCreateRequest} 里<b>没有</b>这两个字段，
     * 而 P0-19 C1-2 逐字要求"客户可自证的时间须附凭据引用字段"、
     * "可核实集合 = {客户自证（附凭据）, 调理师转交提交时间}，取较早者"。
     * 这是一处<b>真实的契约缺口</b>，已在登记册登记。本服务不擅自扩契约字段，
     * 而是把两个来源作为<b>本方法的显式参数</b> —— 使缺口可见，
     * 且将来契约补齐时只需在控制器把请求体映射过来，本方法的语义零改动。
     *
     * @param customerId        客户（必填）
     * @param entry             入口（由控制器解析自契约字面）
     * @param route             通路
     * @param reasonCode        原因码（必填 —— 未记录原因不可结案）
     * @param requestedAtClaimed 客户主张的时间（可空；仅留存）
     * @param customerProvenAt  客户可自证的时间（可空；非空则 {@code evidenceRef} 必填）
     * @param evidenceRef       凭据引用（截图 / 通话记录标识）
     * @param therapistHandoffAt 调理师转交待办的提交时间（可空）
     * @param meridianAcceptedAt 经络师受理时间（可空；全空时作为兜底起点）
     * @param customerStatement 客户原话（可空 —— 未记原话不阻塞立案，但会缺一段证据）
     * @param liableStoreId     责任主体 = 签约店（必填）
     */
    public record CreateRequest(
            UUID customerId,
            RefundEntry entry,
            RefundRoute route,
            RefundReasonCode reasonCode,
            Instant requestedAtClaimed,
            Instant customerProvenAt,
            String evidenceRef,
            Instant therapistHandoffAt,
            Instant meridianAcceptedAt,
            String customerStatement,
            UUID liableStoreId) {
    }

    /**
     * 立案（G1）—— 返回落库后的工单行。
     *
     * <h2>🛑 顺序不可调换：先校验引用、再归一 requested_at，再算延迟，最后才落库</h2>
     * <pre>
     *   ⓪ verifyReferences(...)        ← 「引用的客户与门店真的存在吗」（C-3 补，报 400·1001）
     *   ① resolveRequestedAt(...)      ← 「最早且可核实」，claimed 不参与比较
     *   ② assertBundleCanBePersisted() ← 五条拒绝理由（见 RefundRecordingPolicy）
     *   ③ evaluateDelay(...)           ← 延迟由【归一后的起点】算，不用 claimed
     *   ④ insertWorkOrder(...)         ← 落库
     *   ⑤ 若带原话 → insertStatement(...)  ← 原话 append-only，独立追加
     * </pre>
     * <p>🛑 ⓪ 必须在 ① 之前：一次"客户 ID 打错"的请求若先走时间归一，
     * 它可能先因三字段不成对抛 5001（业务规则），于是调用方拿到的是业务错误而非参数错误 ——
     * 而它会据此去改时间字段（那个字段本来是对的）。参数错误必须先于业务规则错误被回答。
     * 若把 ③ 提前到 ① 之前（或让它读 claimed），就会造出"客户说的时间用来判超时"
     * 这一条 —— 而 claimed 的全部纪律是"永不进任何超时判定"。
     *
     * <h2>🛑 为什么原话在最后追加，且失败不回滚工单</h2>
     * 一次原话写入失败（如文本超长）不应让整张工单消失：工单已经在窗口内被录入了，
     * 而"客户提出过诉求"这件事为真。让整单回滚会把一次<b>可补救</b>的失败
     * （补录原话）变成一次<b>不可补救</b>的失败（24h 计时已在走，单子却没了）。
     * 故原话失败时抛错但工单保留 —— 由调用方补录。
     *
     * <p>⚠️ 这一条是本次实现的<b>取舍</b>，不是需求原文。需求只说了
     * 「客户原话全程留痕、不可删除」，未规定"原话写失败时工单是否回滚"。
     * 取舍的判据是：哪一侧的失败更难补救。故此处选"保留工单"。
     */
    public RefundWorkOrderRow create(String tenantId, CreateRequest req, String createdBy) {
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "退款立案请求为空");
        }
        requireNonNull(req.customerId(), "客户标识");
        requireNonNull(req.entry(), "入口（entry）");
        requireNonNull(req.route(), "通路（refund_route）");
        requireNonNull(req.reasonCode(), "原因码（reason_code）");
        requireNonNull(req.liableStoreId(), "责任主体（签约店）");
        RefundWorkOrderPort.validateTenantId(tenantId);

        // ⓪ 引用对象存在性 —— 必须【在写库之前】，且必须报 400·1001 而不是让外键抛
        //
        // 🛑 这一段是本域走过装配链后才暴露的一个真缺陷的修复（C-3 写路径矩阵，
        //    2026-09-27）。缺陷形态：`refund.customer_id` / `liable_store_id` 在库层
        //    都是 NOT NULL REFERENCES，于是"不存在的客户/门店会被数据库拒掉"看似成立 ——
        //    但外键违例以 DataIntegrityViolationException 冒到调用方，被全局异常处理器
        //    兜成 **500·9001「系统异常」**，而契约 G1 逐字声明的是 **400·1001**。
        //    后果不是"报错方向偏了"这么轻：调用方看到 500 会去重试、去报警、
        //    去查服务健康度，而它真正该做的是核对入参 —— 一次"输入错误"被回答成
        //    "服务端故障"，会把排查引向完全错误的方向。
        //
        // 🛑 顺序也是判据的一部分：放在 ① 归一 requested_at 之前。
        //    若放在其后，一次"客户 ID 打错"的请求会先走完时间归一并可能抛
        //    5001（如三字段不成对），于是调用方拿到的是业务规则错误而不是参数错误 ——
        //    两者在契约上是不同的响应码，且前者会让调用方去改时间字段（而它本来是对的）。
        //
        // 🛑 两个校验都报 1001 而不是 3001(NOT_FOUND)：契约 G1 的 responses 只声明了
        //    200/400/403 三个码，没有 404 —— 立案端点的"引用不存在"是【入参错误】，
        //    不是"资源不存在"（后者是 G2 那种按 ID 取详情才有的语义）。
        //    报 3001 会让端侧按契约去找一个不存在于 G1 的响应定义。
        if (!subjects.customerExists(tenantId, req.customerId())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "customer_id 指向的客户在本租户内不存在: " + req.customerId()
                            + "。🛑 本判定不区分『不存在』与『属于其他租户』——"
                            + "隔离由 RLS 承担（customer 表 FORCE ROW LEVEL SECURITY），"
                            + "两者在库层不可见性相同。若让它们可区分，本端点就成了"
                            + "一个跨租户客户 ID 的探测口（拿 ID 问一问，靠状态码差别枚举别家客户）");
        }
        if (!subjects.storeExists(tenantId, req.liableStoreId())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "liable_store_id 指向的门店在本租户内不存在: " + req.liableStoreId()
                            + "。🛑 责任主体 = 签约店，它必须是本租户真实存在的门店 ——"
                            + "退款工单要按它归集门店责任，一个悬空的引用会让这张单子"
                            + "在门店维度报表里消失（既不属任何店，也不被任何店认领）");
        }

        // ① 归一 requested_at —— claimed 只被原样搬进结果，不参与任何比较
        //
        // 🛑 入口 B 走 none() 而【不】走 resolveRequestedAt 的兜底：
        //    入口 B 由首周期双不达标自动触发，客户从未"提出过诉求"，
        //    故它不存在"客户何时提出"这一问 —— 强行归一会让兜底分支
        //    （source=经络师受理）给它安上一个与客户诉求无关的起点，
        //    于是异常名单里出现一批"永远超时、却没有可比起点"的单子。
        //    none() 使三字段全空，与 V6 的可空列一致，也与
        //    RefundWorkOrderRow.subjectToDeputyWindow() 的判定同口径。
        RefundRecordingPolicy.RequestedAtResolution resolved = req.entry().isStoreDeputyEntry()
                ? recordingPolicy.resolveRequestedAt(
                        req.requestedAtClaimed(),
                        req.customerProvenAt(),
                        req.evidenceRef(),
                        req.therapistHandoffAt(),
                        req.meridianAcceptedAt())
                : RefundRecordingPolicy.RequestedAtResolution.none();

        // 🛑 入口 B 收到任何"客户何时提出"的字段 → 抛，而不是静默丢弃。
        //    静默丢弃是最坏的一种处理：调用方以为自己录入了客户主张，
        //    而库里三字段全空 —— 事后对账时"客户说过什么时间"这件事查无此事，
        //    且没有任何一处报错。故此处把"入口 B 不该带这些字段"变成一次显式失败。
        if (!req.entry().isStoreDeputyEntry()
                && (req.requestedAtClaimed() != null || req.customerProvenAt() != null
                || req.therapistHandoffAt() != null || req.meridianAcceptedAt() != null)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "入口 B（首周期自动触发）不接受任何 requested_at 相关字段 —— "
                            + "它不存在『客户何时提出退款』这一问（诉求由系统按公式判定产生，"
                            + "客户从未提出）。P0-19 的 24h 代录纪律只对入口 A 成立。"
                            + "🛑 不得静默丢弃：丢弃会让调用方以为主张已录入，而库里三字段全空，"
                            + "且无任何一处报错。入口=" + req.entry().contractCode());
        }

        // ② 五条拒绝理由（"时间照填、来源不填"这一类在这里被拦下）
        Instant recordedAt = Instant.now();
        RefundRecordingPolicy.RecordingBundle bundle = new RefundRecordingPolicy.RecordingBundle(
                resolved.requestedAt(),
                resolved.source() == null ? null : resolved.source().code(),
                resolved.claimedRetained(),
                resolved.evidenceRef(),
                recordedAt);
        recordingPolicy.assertBundleCanBePersisted(bundle);

        // ③ 延迟由【归一后的起点】算
        BigDecimal delayH = null;
        if (resolved.requestedAt() != null) {
            delayH = BigDecimal.valueOf(
                            Duration.between(resolved.requestedAt(), recordedAt).toMinutes() / 60.0)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        UUID refundId = UUID.randomUUID();
        // ④ 落库：entry 用 dbCode（有空格），出站再用 contractCode —— 方向写死在仓储里
        RefundWorkOrderRow row = new RefundWorkOrderRow(
                refundId,
                req.customerId(),
                req.entry(),
                req.route(),
                req.liableStoreId(),
                req.reasonCode(),
                resolved.requestedAt(),
                resolved.claimedRetained(),
                resolved.source(),
                resolved.evidenceRef(),
                recordedAt,
                delayH,
                null,                       // sla_due_at：SLA 倒计时口径属另一条已登记链路
                RefundOutcome.CONTINUE,     // 立案即"继续"（尚未有结论）
                null,                       // amount_split_json：有待金额核定
                null,                       // amount_basis：同上
                createdBy);
        ledger.insertWorkOrder(tenantId, row);

        // ⑤ 原话（append-only；失败不回滚工单，见方法注释）
        if (req.customerStatement() != null && !req.customerStatement().isBlank()) {
            ledger.insertStatement(tenantId, RefundStatementRow.forInsert(
                    UUID.randomUUID(),
                    refundId,
                    req.customerStatement(),
                    // 🛑 取得方式默认「客户原话」；若来源是调理师转交，调用方应改传该字面。
                    //    本方法不"猜"：猜错会让一段经手人转述被标成客户亲述，
                    //    而两者在争议中的证明力完全不同。
                    "客户原话",
                    null,
                    createdBy));
        }

        return row;
    }

    // ==================================================================
    // 二、G2 工单详情
    // ==================================================================

    /**
     * G2 取工单详情。
     *
     * <p>🛑 不存在返回 {@code null} 由控制器转 404，而<b>不</b>在本方法抛：
     * "查不到"是一个正常的查询结果，把它变成异常会让调用方无法区分
     * "这个 ID 不存在"与"我的查询本身错了"。
     *
     * <p>🛑 本方法<b>不</b>返回挽留记录与原话 —— 它们各有独立端点
     * （G3 是挽留，原话随 G1 写入）。把三条链一次性塞进详情响应，
     * 会让"详情页慢"与"详情页泄漏"成为同一件事的两个症状：
     * 某天有人为了让页面快一点，顺手把原话摘掉，而原话是证据链的一部分。
     */
    public RefundWorkOrderRow detail(String tenantId, UUID refundId) {
        RefundWorkOrderPort.validateTenantId(tenantId);
        requireNonNull(refundId, "工单标识");
        return ledger.findWorkOrder(tenantId, refundId);
    }

    /** 取某工单的全部挽留记录（追加序；供 G3 回显与稽核）。 */
    public List<RefundRetentionRow> retentions(String tenantId, UUID refundId) {
        RefundWorkOrderPort.validateTenantId(tenantId);
        requireNonNull(refundId, "工单标识");
        return ledger.findRetentions(tenantId, refundId);
    }

    /** 取某工单的全部客户原话（追加序，含被取代者 —— 证据链要全过程，不是只留最新）。 */
    public List<RefundStatementRow> statements(String tenantId, UUID refundId) {
        RefundWorkOrderPort.validateTenantId(tenantId);
        requireNonNull(refundId, "工单标识");
        return ledger.findStatements(tenantId, refundId);
    }

    // ==================================================================
    // 三、G3 挽留记录
    // ==================================================================

    /**
     * G3 入参。
     *
     * @param attempts          挽留尝试次数
     * @param scriptVersion     话术版本（可空）
     * @param result            挽留结果（必填）
     * @param analysisJson      五维原因分析（JSON 文本，必填）
     * @param communicationJson 沟通记录（JSON 文本，必填）
     */
    public record RetentionRequest(
            int attempts,
            String scriptVersion,
            RetentionResult result,
            String analysisJson,
            String communicationJson) {
    }

    /**
     * 记录一次挽留（G3）。
     *
     * <h2>🛑 两道业务闸在写库之前，且拒绝理由必须点名依据</h2>
     * <ol>
     *   <li><b>入口 B 不经挽留</b>（首周期双不达标 → 主动终止）。
     *       向一个"我们已判定服务无效"的客户做挽留，语义上自相矛盾；</li>
     *   <li><b>健康风险事件类不走挽留直接终止</b>。
     *       客户刚说"做完反而更疼了"，挽留话术会把它变成为销售机会辩护 ——
     *       这不是流程瑕疵，是把安全事件当成销售机会。</li>
     * </ol>
     * 两道闸都调用 {@link RetentionPolicy#requirementOf} —— <b>不</b>在此重写 if 判断：
     * 重写会让"什么情况豁免挽留"有两份实现，而分叉的那一侧不会报错，
     * 只会安静地让某类工单绕过（或被迫走）挽留。
     *
     * <p>🛑 已归档工单拒写（{@code assertWritable}）：归档后只读。
     * 它对挽留尤其关键 —— 若允许归档后补挽留，那"归档"就不是一次收口，
     * 只是一个会被人越过的状态位（见 {@code RetentionPolicy} 的对应注释）。
     */
    public RefundRetentionRow recordRetention(String tenantId,
                                              UUID refundId,
                                              RetentionRequest req,
                                              UUID operatorId,
                                              String createdBy) {
        RefundWorkOrderPort.validateTenantId(tenantId);
        requireNonNull(refundId, "工单标识");
        requireNonNull(req, "挽留请求");
        requireNonNull(req.result(), "挽留结果");
        requireNonNull(operatorId, "操作人");
        requireNonBlank(req.analysisJson(), "五维原因分析");
        requireNonBlank(req.communicationJson(), "沟通记录");

        RefundWorkOrderRow wo = requireExisting(tenantId, refundId);

        // 归档后只读
        retentionPolicy.assertWritable(wo.outcome());

        // 两处豁免（入口 B / 健康风险）
        RetentionPolicy.RetentionRequirement requirement =
                retentionPolicy.requirementOf(wo.entry(), wo.reasonCode());
        if (requirement.isExempt()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "本工单豁免挽留（" + requirement.label() + "）—— 不接受挽留记录。"
                            + (requirement.isHealthRiskExemption()
                            ? "🛑 这是【安全优先】的豁免，不是流程瑕疵："
                              + "客户报告了症状加重或新不适时，挽留话术会把它变成为销售机会辩护。"
                              + "该工单应直接进入终止流程（审批不因健康风险而豁免）。"
                            : "🛑 这是【逻辑矛盾】的豁免：入口 B 已由系统判定『服务对该客户无效』，"
                              + "此时再劝客户继续接受该服务自相矛盾。")
                            + " 入口=" + wo.entry().contractCode()
                            + "、原因码=" + wo.reasonCode().code());
        }

        RefundRetentionRow row = RefundRetentionRow.forInsert(
                UUID.randomUUID(), refundId, req.attempts(), req.scriptVersion(),
                req.result(), operatorId, req.analysisJson(), req.communicationJson(), createdBy);
        ledger.insertRetention(tenantId, row);
        return row;
    }

    // ==================================================================
    // 四、G4 审批
    // ==================================================================

    /**
     * G4 审批（出口集中）。
     *
     * <h2>🛑 三道闸，缺一即让"出口集中"失效</h2>
     * <ol>
     *   <li><b>可见性闸</b>：{@link RefundAudienceRole#ofTokenRole} + {@code Matrix.canSee}
     *       （闸 ①）—— 看不到工单的人无从审批；</li>
     *   <li><b>审批路径闸</b>：{@link RetentionPolicy#outcomeApproval}（闸 ②）。三种结局三种落点，
     *       其中"入口 B + 终止 = 全额免审批"是出口集中的<b>唯一例外</b>。
     *       🛑 健康风险直终止<b>不</b>豁免审批 —— PRD 只豁免了它的挽留；</li>
     *   <li><b>审批资格闸</b>：{@link RetentionPolicy#assertApproverIsNotDeputy}（闸 ③）——
     *       它同时管两件事：角色是否在审批白名单内（督导在<b>这里</b>被拒，"可见≠可审批"），
     *       以及本次审批人是否就是本工单的代录人（R4b ④ 动机阀门，校验的是<b>人</b>）。
     *       🛑 白名单判定<b>只有这一个落点</b> —— 在此另写一份会在反向验证里被证明为"零价值"，
     *       而代价是改白名单要改两处、漏改处不报错。</li>
     * </ol>
     *
     * <p>🛑 本方法<b>不</b>修改 {@code outcome}：审批是"批准这次终止"的结论，
     * 而结局推进是另一个动作（且有乐观并发保护）。
     * 把两者合并会让一次审批失败连带回滚结局，而"审批通过但结局没推"比
     * "审批没过"更难排查（看起来像成功了）。
     */
    public ApprovalDecision approve(String tenantId,
                                    UUID refundId,
                                    String tokenRole,
                                    UUID approverId,
                                    String deputyOperatorId) {
        RefundWorkOrderPort.validateTenantId(tenantId);
        requireNonNull(refundId, "工单标识");
        requireNonNull(approverId, "审批人");
        requireNonBlank(tokenRole, "token 角色");

        // 闸 ①：角色必须是可见的（否则连工单都看不到）
        RefundAudienceRole role = requireVisible(tokenRole);

        // 🛑 审批白名单闸【不在这里重复实现】—— 唯一落点是
        //    RetentionPolicy.assertApproverIsNotDeputy 的角色分支（见闸 ④）。
        //    本方法原先在此处另写了一份 approverTokenRoles().contains(...) 判断，
        //    与闸 ④ 的那份是同一判定的第二份实现。反向验证（RV-S2-4-4 关掉本处判断）
        //    证明：关掉它，⑤『督导过不了审批闸』【仍然红】—— 真正拦下督导的是闸 ④。
        //    于是本处判断的价值是零，代价是"改白名单要改两处"（而漏改的那一处
        //    不会报错，只会安静地按旧白名单放行）。故删除本处，保留唯一落点。

        RefundWorkOrderRow wo = requireExisting(tenantId, refundId);

        // 闸 ②：审批路径落点（免审批的唯一例外是「入口 B + 终止」）
        RetentionPolicy.ApprovalTarget target = retentionPolicy.outcomeApproval(wo.entry(), wo.outcome());
        if (!target.requiresHeadquarters()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "本工单无需总部审批（落点 = " + target.label() + "）—— 不接受审批动作。"
                            + (target.isExemptFromHeadquarters()
                            ? "🛑 『首周期主动终止 = 全额免审批』不是想省流程："
                              + "首周期双不达标是【系统按公式判定】的结果，"
                              + "若它还要人工批准，等于把已判定的结论重新交回给人 —— "
                              + "而那正是 Top1 痛点（判定权在人手里）的复发通道。"
                            : "继续中的工单未动钱，无可审批内容。"));
        }

        // 闸 ③：角色白名单 + 代录者不可审批（唯一落点：RetentionPolicy.assertApproverIsNotDeputy）
        retentionPolicy.assertApproverIsNotDeputy(
                deputyOperatorId, approverId.toString(), role);

        return new ApprovalDecision(refundId, role, target, approverId, Instant.now());
    }

    /**
     * 一次审批的结论（<b>不落库</b> —— 审批留痕表属另一条已登记链路）。
     *
     * <p>把它显式返回而不是返回 void：调用方需要 {@code target} 来判断
     * "这次审批之后该走哪条路径"（是否还要总部复核）。
     */
    public record ApprovalDecision(UUID refundId,
                                   RefundAudienceRole approverRole,
                                   RetentionPolicy.ApprovalTarget target,
                                   UUID approverId,
                                   Instant decidedAt) {

        /** 本次审批是否为"免审批"路径的一部分（即实际未发生审批动作）。 */
        public boolean isExemptPath() {
            return target != null && target.isExemptFromHeadquarters();
        }
    }

    // ==================================================================
    // 五、内部
    // ==================================================================

    /**
     * 取工单，不存在即抛 {@code NOT_FOUND(3001)}。
     *
     * <p>🛑 与 {@link #detail} 的分工是刻意的：{@code detail} 返回 {@code null}
     * 让控制器转 404（查询语义），本方法直接抛（<b>后续动作</b>语义）。
     * 在"要记录挽留 / 要审批"的场景里，工单不存在是<b>动作无法完成</b>，
     * 而返回 null 会让调用方必须自己写一次判空 —— 而漏写的那次
     * 会以 NPE 的形式暴露，不是以 404 的形式（错误码指错方向）。
     */
    private RefundWorkOrderRow requireExisting(String tenantId, UUID refundId) {
        RefundWorkOrderRow wo = ledger.findWorkOrder(tenantId, refundId);
        if (wo == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "退款工单不存在: " + refundId + "（或不属于当前租户 —— 跨租户不可见由 RLS 承担）");
        }
        return wo;
    }

    /** 当前口径来源自描述（供端点自证"这些判定来自配置而非代码"）。 */
    public String policySource() {
        return policy.source();
    }

    private static void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 缺失");
        }
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, name + " 必填");
        }
    }
}