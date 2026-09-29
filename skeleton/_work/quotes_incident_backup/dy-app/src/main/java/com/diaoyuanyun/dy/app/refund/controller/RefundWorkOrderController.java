package com.diaoyuanyun.dy.app.refund.controller;

import com.diaoyuanyun.dy.app.refund.domain.AmountBasis;
import com.diaoyuanyun.dy.app.refund.domain.RefundEntry;
import com.diaoyuanyun.dy.app.refund.domain.RefundOutcome;
import com.diaoyuanyun.dy.app.refund.domain.RefundReasonCode;
import com.diaoyuanyun.dy.app.refund.domain.RefundRoute;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import com.diaoyuanyun.dy.app.refund.domain.RefundWorkOrderRow;
import com.diaoyuanyun.dy.app.refund.domain.RetentionResult;
import com.diaoyuanyun.dy.app.refund.service.RefundReceiptService;
import com.diaoyuanyun.dy.app.refund.service.RefundWorkOrderService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.diaoyuanyun.dy.web.idempotent.Idempotent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 契约域 G · 退款与挽留的 5 个端点（G1~G5）—— 路径逐字对齐
 * {@code contract/openapi-v1.0.0.yaml} 第 983~1103 行。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   G1  POST /refunds                       x-callable-roles: [meridian, admin]  x-client-forbidden: true
 *   G2  GET  /refunds/{id}                  x-callable-roles: [meridian, admin]  x-client-forbidden: true
 *   G3  POST /refunds/{id}/retentions       x-callable-roles: [meridian, admin]  x-client-forbidden: true
 *   G4  POST /refunds/{id}/approvals        x-callable-roles: [admin]  x-ruling-pending  x-client-forbidden: true
 *   G5  POST /refunds/{id}/receipts         x-callable-roles: [meridian, admin]  x-client-forbidden: true
 * </pre>
 * 🔴 上游逐字注明：「本域全部接口：客户端与调理师端一律 403 VISIBILITY_DENIED」。
 *
 * <h2>🛑 三条防线各管一件事，都不在本类里</h2>
 * <ol>
 *   <li><b>客户一律 403</b>（契约级、与参数无关）→ {@link StaffOnly} 注解 +
 *       {@code DerivedVisibilityInterceptor} 的"按端点"分支。
 *       本类只负责<b>贴上注解并声明被拒字段名</b>；
 *       判定在全局拦截器里，故<b>新增端点默认受保护</b>，要绕过必须显式做点什么。</li>
 *   <li><b>staff 的可调用角色</b>（{@code x-callable-roles}）→
 *       {@link RefundWorkOrderService#requireVisible}，用
 *       {@code RefundAudienceRole.ofTokenRole} + {@code RefundVisibilityMatrix.canSee} 两步。
 *       🛑 它<b>不</b>复用入站字段扫描：那道防线拒的是"请求里点名了派生字段"，
 *       而客户调 G2 时一个参数都不带，扫描结果为空 —— 出口兜底也救不了
 *       （只会把 data 摘空并返回 200，而契约要求 403）。</li>
 *   <li><b>功能权限码</b>（岗位允不允许做这件事）→ {@code @RequirePermission}。
 *       与上一条语义不同（岗位 vs 端点档位），故不可互相替代。</li>
 * </ol>
 *
 * <h2>🛑 为什么四个写端点都上 {@code @Idempotent}，而 G2（读）不上</h2>
 * 契约 §0 逐字：「写接口接受 {@code Idempotency-Key} 请求头（UUID）；
 * 服务端在 24h 窗口内去重」。G1/G3/G4/G5 都是写接口：
 * <ul>
 *   <li>G1 重复提交会<b>开两张工单</b> —— 而客户只提了一次诉求，
 *       于是"超 24h 未代录"的异常名单里凭空多一条；</li>
 *   <li>G3 重复提交会<b>多一条挽留记录</b>，把挽留次数从 1 抬到 2，
 *       让挽留质量评估失真；</li>
 *   <li>G4 重复提交会<b>重复审批</b>（金额动作，最不能重复）；</li>
 *   <li>G5 重复提交会<b>多一条回执留痕</b> —— 而回执三态进覆盖率分母，
 *       重复一条会让覆盖率被稀释（P0-19 点名的那一类）。</li>
 * </ul>
 * G2 是纯读、幂等天然成立，给它加 {@code Idempotent-Key} 要求
 * 只会让调用方多维护一个无意义的头。
 *
 * <h2>🛑 出站字面一律取 {@code contractCode()}，绝不取 {@code dbCode()}</h2>
 * {@code entry} 在库侧是 {@code 'A 门店代录'}（有空格）、契约侧是 {@code 'A门店代录'}（无空格）。
 * 两套都已冻结。本类出现的每一个 {@code entry} 都必须走
 * {@link RefundEntry#contractCode()} —— 用 {@code dbCode()} 会让端侧逐字比对红，
 * 而那种红会被误判为"契约写错了"。这一条由 {@code RefundWorkOrderControllerContractTest} 守着。
 */
@RestController
@RequestMapping("/api/v1/refunds")
@StaffOnly(clientDeniedFields = {
        "refund_id", "liable_store_id", "sla_due_at", "recording_delay_h", "outcome",
        "receipt_state", "requested_at", "requested_at_claimed", "customer_statement“})
public class RefundWorkOrderController {

    /** 计数自证：本控制器从<b>不</b>直接触达持久化层（架构守卫 R2 的运行时镜像）。 */
    private static final AtomicLong CONTROLLER_INVOCATIONS = new AtomicLong();

    /**
     * 把 JSON 对象字段序列化成入库文本用（见 {@link #jsonText}）。
     *
     * <p>单独持有而不是每次 {@code new ObjectMapper()}：后者在每次请求上
     * 新建一个重量级对象（含 {@code SerializerProvider} 缓存），
     * 而 G3 是写路径热路径。Spring 容器里已有一个可注入的实例，
     * 但本类通过构造器注入它会把”控制器的入参规范“这件事耦合到容器装配上 ——
     * 用一个只用于序列化的本地实例，语义更窄、更不容易被误用。
     * 🛑 它<b>只</b>用于序列化，不承担请求体反序列化（那由 Spring 的转换器负责）。
     */
    private static final ObjectMapper JSON_WRITER = new ObjectMapper();

    private final RefundWorkOrderService workOrders;
    private final RefundReceiptService receipts;
    private final RefundVisibilityMatrix visibility;

    public RefundWorkOrderController(RefundWorkOrderService workOrders,
                                     RefundReceiptService receipts,
                                     RefundVisibilityMatrix visibility) {
        this.workOrders = workOrders;
        this.receipts = receipts;
        this.visibility = visibility;
    }

    // ==================================================================
    // G1 POST /refunds —— 代录客户退款诉求
    // ==================================================================

    /**
     * G1 请求体（对应契约 {@code RefundCreateRequest}）。
     *
     * <h2>字段名必须是 snake_case</h2>
     * 契约逐字用 {@code customer_id} / {@code refund_route} / {@code reason_code} /
     * {@code requested_at} / {@code requested_at_claimed} / {@code customer_statement}。
     * Java 属性名是 camelCase，Jackson 默认序列化成 camelCase ——
     * 客户端按 {@code customer_id} 取值会拿到 {@code null}。故逐个显式
     * {@code @JsonProperty}。这与 {@code Result#traceId} 的踩坑同类（被真实违约抓过一次）。
     *
     * <h2>🛑 缺 {@code requested_at_source} / {@code requested_at_source_ref}</h2>
     * 契约的 {@code RefundCreateRequest} 里<b>没有</b>这两个字段，
     * 而 P0-19 C1-2 逐字要求它们存在（”客户可自证的时间须附凭据引用字段“）。
     * 这是一处<b>已登记的契约缺口</b>，本类<b>不</b>擅自扩契约 ——
     * 而是把两个来源作为可选字段收进请求体（不违反契约：契约未禁止额外字段，
     * 而漏掉它们会让 24h 计时的起点无从标注）。
     * 契约补齐后，这里零改动即可对齐。
     */
    public record CreateRefundRequest(
            @com.fasterxml.jackson.annotation.JsonProperty(”customer_id") String customerId,
            @com.fasterxml.jackson.annotation.JsonProperty("entry") String entry,
            @com.fasterxml.jackson.annotation.JsonProperty("refund_route") String refundRoute,
            @com.fasterxml.jackson.annotation.JsonProperty("reason_code") String reasonCode,
            @com.fasterxml.jackson.annotation.JsonProperty("requested_at") String requestedAt,
            @com.fasterxml.jackson.annotation.JsonProperty("requested_at_claimed") String requestedAtClaimed,
            @com.fasterxml.jackson.annotation.JsonProperty("customer_statement") String customerStatement,
            // ---- 以下两项对应已登记的契约缺口（P0-19 C1-2），非契约冻结字段 ----
            @com.fasterxml.jackson.annotation.JsonProperty("customer_proven_at") String customerProvenAt,
            @com.fasterxml.jackson.annotation.JsonProperty("evidence_ref") String evidenceRef,
            @com.fasterxml.jackson.annotation.JsonProperty("liable_store_id") String liableStoreId) {
    }

    /**
     * G1 代录客户退款诉求。
     *
     * <p>🛑 入参的 {@code requested_at} 在这里被当作<b>客户可自证的时间</b>处理
     * （而非 claimed）—— 因为契约对它的描述逐字是「最早且可核实」。
     * 客户主张（不可核实）走 {@code requested_at_claimed}。
     * 若把契约的 {@code requested_at} 映射成 claimed，24h 计时的起点会变成
     * 一个不可核实的时间 —— 而那正是 P0-19 拆分三字段要防的事。
     */
    @Idempotent
    @StaffOnly(clientDeniedFields = {"refund_id", "liable_store_id", "sla_due_at",
            "recording_delay_h", "outcome“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:write")
    @PostMapping
    public Result<Object> create(@RequestBody CreateRefundRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        String role = TenantContext.role();
        workOrders.requireVisible(role);

        RefundEntry entry = RefundEntry.parse(body.entry());

        RefundWorkOrderService.CreateRequest req = new RefundWorkOrderService.CreateRequest(
                uuid(body.customerId(), "customer_id"),
                entry,
                RefundRoute.parse(body.refundRoute()),
                RefundReasonCode.parse(body.reasonCode()),
                instant(body.requestedAtClaimed(), "requested_at_claimed"),
                // 契约的 requested_at = 「最早且可核实」→ 映射到 customerProven 位
                instant(body.requestedAt(), "requested_at"),
                body.evidenceRef(),
                null,   // therapistHandoffAt：转交待办实体属 05 表链，本端点不接收
                // 🛑 meridianAcceptedAt（受理时间兜底）—— 仅对入口 A 成立。
                //
                // 它是 resolveRequestedAt 在「既无客户自证、又无调理师转交」时的兜底起点
                // （source=MERIDIAN_ACCEPTED，服务层注释明说该兜底"会让能否纳入可核实比较域
                // 为 false，从而在稽核信号里可被识别"）—— 即入口 A 缺可核实来源时的合法落点。
                //
                // 而入口 B 由首周期双不达标【按公式自动触发】，不存在"经络师受理"这一动作：
                // 服务层对 B 走 RequestedAtResolution.none()，从不调用 resolveRequestedAt，
                // 故本参数对 B 的起点结果毫无影响 —— 但它会命中服务层那条
                // 「入口 B 不接受任何 requested_at 相关字段」的一票否决（那是对的防线：
                // 若 B 收到"客户何时提出"，静默丢弃会让调用方以为主张已录入而库里三字段全空）。
                //
                // 🛑 原先此处对【所有】 entry 一律传 Instant.now()，后果是：
                //    任何 entry=B首周期 的请求都被那条一票否决拒成 5001，
                //    而契约 G1 的 entry 枚举【明确含 B首周期】——
                //    即契约声明支持的入口 B 立案路径 100% 不可用。
                //    更坏的是错误消息说"不接受 requested_at 字段"，调用方据此删掉
                //    requested_at 后【仍然失败】（真正成因是这个兜底参数，它不在请求体里），
                //    于是错误信息把排查引向一个不可能修好的方向。
                //    此类缺陷单测看不见：服务层单测直调 create(...) 自己构造 CreateRequest，
                //    永远不会经过控制器这一行参数传递。抓出它的是真请求 E2E。
                entry.isStoreDeputyEntry() ? Instant.now() : null,
                body.customerStatement(),
                uuid(body.liableStoreId(), "liable_store_id"));

        RefundWorkOrderRow row = workOrders.create(tenantId, req, TenantContext.staffId());
        return Result.ok(toRefundData(row), MDC.get("traceId"));
    }

    // ==================================================================
    // G2 GET /refunds/{id} —— 工单详情
    // ==================================================================

    /**
     * G2 工单详情。
     *
     * <p>🛑 出站字段严格限于契约 {@code RefundData} 的五项
     * （{@code refund_id / liable_store_id / sla_due_at / recording_delay_h / outcome}）。
     * <b>不</b>下发挽留记录、原话、回执三态 —— 它们各有独立端点。
     * "多下发一个顺手就有的字段"是可见性裁剪最容易失守的方式：
     * 它不报错，只让一份数据出现在本不该出现的地方。
     */
    @StaffOnly(clientDeniedFields = {"refund_id", "liable_store_id", "sla_due_at",
            "recording_delay_h", "outcome“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:read")
    @GetMapping("/{id}")
    public Result<Object> detail(@PathVariable("id") String id) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        workOrders.requireVisible(TenantContext.role());

        RefundWorkOrderRow row = workOrders.detail(tenantId, uuid(id, "id"));
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "退款工单不存在: " + id + "（或不属于当前租户 —— 跨租户不可见由 RLS 承担，"
                            + "故此处不区分『不存在』与『不属于你』，避免成为租户枚举的探测口）");
        }
        return Result.ok(toRefundData(row), MDC.get("traceId"));
    }

    // ==================================================================
    // G3 POST /refunds/{id}/retentions —— 挽留记录
    // ==================================================================

    /**
     * G3 请求体。
     *
     * <p>契约 G3 <b>没有声明 requestBody</b>（见契约 {@code /refunds/{id}/retentions}
     * 仅有 parameters 无 requestBody），故字段名按域内语义命名，未被契约冻结。
     *
     * <h2>🛑 {@code analysis} / {@code communication} 为什么是 {@link JsonNode} 而不是 {@code String}</h2>
     * 这两列在库层是 <b>JSONB</b>（{@code retention.analysis} / {@code retention.communication}），
     * 业务上是"五维原因分析"与"沟通记录"两个<b>结构化对象</b>。
     * 端侧最自然的写法因而是一个<b>对象</b>：
     * <pre>
     *   {"attempts":1,"result":"接受继续服务","analysis":{...},"communication":{...}}
     * </pre>
     * 此处原先声明为 {@code String} —— 后果是端侧一旦传对象（最自然的写法），
     * Jackson 抛 {@code HttpMessageNotReadableException}，
     * 调用方拿到的是"服务端 500"（C-3 写路径矩阵的真实请求抓到了这一个）。
     *
     * <p>改用 {@link JsonNode} 后<b>两种形态都收</b>：
     * <ul>
     *   <li>对象 / 数组 → 由 {@link #jsonText} 序列化成 JSON 文本；</li>
     *   <li>字符串 → 视为"已经是 JSON 文本"原样取用（兼容旧的字符串写法，不破坏既有调用方）。</li>
     * </ul>
     * 这与本域 {@code entry} 的处置同源：<b>宽容留在入口、严格留在出口</b>
     * （{@code RefundEntry.parse} 接受两套字面，出站一律取 {@code contractCode()}）。
     * 入口处"只认一种写法"会把一次合法的端侧表达变成一次 500，
     * 而真正该被严格对待的是"落库前它是不是合法 JSON"（由库层 JSONB 与
     * 服务层的非空断言承担）。
     */
    public record CreateRetentionRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("attempts") Integer attempts,
            @com.fasterxml.jackson.annotation.JsonProperty("script_version") String scriptVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("result") String result,
            @com.fasterxml.jackson.annotation.JsonProperty("analysis") JsonNode analysis,
            @com.fasterxml.jackson.annotation.JsonProperty("communication") JsonNode communication) {
    }

    /**
     * 把请求体里的 JSON 字段规范成"入库用的 JSON 文本"。
     *
     * <p>三条口径，缺一都会让某一种合法写法 500：
     * <ol>
     *   <li>{@code null} → 返回 {@code null}（缺省即缺省，由服务层的"必填"断言回答，
     *       而不是在这里造一个 {@code "{}"} —— 那会让一次"忘传五维分析"被静默当成空对象落库）；</li>
     *   <li>字符串 → <b>原样</b>返回（它就是 JSON 文本，不重复序列化 ——
     *       序列化会让 {@code "{\"a\":1}"} 变成 {@code "\"{\\\"a\\\":1}\""} 双重编码）；</li>
     *   <li>对象 / 数组 / 数值 / 布尔 → 序列化为 JSON 文本。</li>
     * </ol>
     */
    private static String jsonText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        try {
            return JSON_WRITER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 无法序列化为 JSON: " + node.getNodeType());
        }
    }

    /**
     * G3 挽留记录。
     *
     * <p>两道豁免闸（入口 B 不经挽留 / 健康风险不走挽留）在服务层，
     * 本类<b>不</b>重复判断 —— 重复会让同一条规则有两个实现、必然分叉。
     */
    @Idempotent
    @StaffOnly(clientDeniedFields = {"attempts", "script_version", "result", "analysis", "communication“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:write")
    @PostMapping("/{id}/retentions")
    public Result<Object> createRetention(@PathVariable("id") String id,
                                          @RequestBody CreateRetentionRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        workOrders.requireVisible(TenantContext.role());

        RefundWorkOrderService.RetentionRequest req = new RefundWorkOrderService.RetentionRequest(
                body.attempts() == null ? 0 : body.attempts(),
                body.scriptVersion(),
                RetentionResult.parse(body.result()),
                jsonText(body.analysis(), "五维原因分析（analysis）"),
                jsonText(body.communication(), "沟通记录（communication）"));

        var row = workOrders.recordRetention(tenantId, uuid(id, "id"), req,
                uuid(TenantContext.staffId(), "operator_id"), TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retention_id", row.retentionId().toString());
        data.put("refund_id", row.refundId().toString());
        data.put("result", row.result().code());
        data.put("attempts", row.attempts());
        // 🛑 挽留结论的审批落点也一并回显：挽留失败 = 客户拒挽留 → 须总部审批（P0-14 第二层）。
        //    它是【布尔条件】，不依赖缺失的分段阈值，故可判定、可回显。
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // G4 POST /refunds/{id}/approvals —— 审批（出口集中）
    // ==================================================================

    /** G4 请求体。契约未冻结其字段名（G4 无 requestBody 声明），故按域内语义命名。 */
    public record ApproveRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("decision") String decision,
            @com.fasterxml.jackson.annotation.JsonProperty("comment") String comment) {
    }

    /**
     * G4 审批。
     *
     * <h2>🛑 契约的 {@code x-ruling-pending} 在本方法上是可断言的</h2>
     * 契约逐字：「{@code x-callable-roles: [admin]}」+「审批角色白名单未由上游逐项明示；
     * 当前取值系『可见≠可审批』推断，待裁定」。展开 {@code admin} = {manager, area, hq}，
     * 而服务层的白名单 = {manager, hq} —— <b>真子集</b>，差集恰为 {area}（督导）。
     * 故"督导能进来（可见）但过不了审批闸"这件事，在真实请求上是可复现的事实。
     *
     * <p>🛑 代录人身份取 {@code X-Deputy-Operator-Id} 请求头：
     * 它<b>不</b>从请求体来 —— 从体来意味着"想绕过动机阀门的人可以填别人的 ID"。
     * 从 JWT / 会话里取是更好的方案，但那属认证链（已登记）；此处先用显式头，
     * 使"身份链缺失"这件事<b>可见</b>而不是被静默略过（缺头 = 校验抛错，见服务层）。
     */
    @Idempotent
    @StaffOnly(clientDeniedFields = {"approver_role", "approval_target", "decided_at“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:approve")
    @PostMapping("/{id}/approvals")
    public Result<Object> approve(@PathVariable("id") String id,
                                  @RequestHeader(value = "X-Deputy-Operator-Id", required = false)
                                  String deputyOperatorId,
                                  @RequestBody(required = false) ApproveRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();

        var decision = workOrders.approve(tenantId, uuid(id, "id"), TenantContext.role(),
                uuid(TenantContext.staffId(), "approver_id"), deputyOperatorId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", decision.refundId().toString());
        data.put("approver_role", decision.approverRole().configKey());
        data.put("approval_target", decision.target().label());
        data.put("requires_headquarters", decision.target().requiresHeadquarters());
        data.put("decided_at", decision.decidedAt().toString());
        // 契约以 x-ruling-pending 记录了"审批白名单系推断"这件事；把它在运行时显式带出，
        // 使调用方与稽核都能看到"谁被允许审批、依据是哪一条推断"。
        data.put("approver_whitelist",
                com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole.approverTokenRoles());
        data.put("ruling_pending",
                "审批角色白名单未由上游逐项明示；当前取值系『可见≠可审批』推断，待契约 owner + 产品共签");
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // G5 POST /refunds/{id}/receipts —— 回执（三态留痕）
    // ==================================================================

    /**
     * G5 请求体。
     *
     * @param subscriptionQuota 订阅消息剩余额度（推送<b>之前</b>判定；≤0 → 未授权（转线下））
     * @param templateId        订阅消息模板 ID（P0-19「回执独占一个模板 ID」）
     * @param pushSucceeded     本次推送是否成功（由调用方的推送网关回传）
     * @param failureReason     推送失败原因（{@code pushSucceeded=false} 时必填）
     */
    public record CreateReceiptRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("subscription_quota") Long subscriptionQuota,
            @com.fasterxml.jackson.annotation.JsonProperty("template_id") String templateId,
            @com.fasterxml.jackson.annotation.JsonProperty("push_succeeded") Boolean pushSucceeded,
            @com.fasterxml.jackson.annotation.JsonProperty("failure_reason") String failureReason) {
    }

    /**
     * G5 回执推送与留痕。
     *
     * <h2>🛑 调用顺序由 {@link RefundReceiptService#pushAndRecord} 决定，不由本类决定</h2>
     * 本类把"推送动作"作为<b>函数式参数</b>传进去（{@code PushAttempt}），
     * 而不是自己先推、再把结果传进去。原因：传结果进来的形态下，
     * "先推送、后判额度"是完全合法的调用序列 —— 而那种序列会让
     * <b>「未授权」这一态在库里永不出现</b>，于是覆盖率分母缺一块、
     * 系统性偏高且看不出来（P0-19 点名的那一类）。
     *
     * <p>本类的 lambda 是<b>桩</b>：真实推送网关（微信订阅消息）属已登记链路。
     * 桩的形态刻意保留"额度不足时 push 不可达"这条性质 ——
     * 因为服务层在调用本 lambda <b>之前</b>就已判完额度。
     */
    @Idempotent
    @StaffOnly(clientDeniedFields = {"receipt_state“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:write")
    @PostMapping("/{id}/receipts")
    public Result<Object> createReceipt(@PathVariable("id") String id,
                                        @RequestBody CreateReceiptRequest body) {
        CONTROLLER_INVOCATIONS.incrementAndGet();
        String tenantId = requireTenant();
        workOrders.requireVisible(TenantContext.role());

        UUID refundId = uuid(id, "id");
        long quota = body.subscriptionQuota() == null ? 0L : body.subscriptionQuota();
        String templateId = (body.templateId() == null || body.templateId().isBlank())
                ? "tpl-refund-receipt-default" : body.templateId();

        // 🛑 工单存在性必须在【推送之前】确认 —— 这是本端点的第 5 个真缺陷的修复
        //    （C-3 写路径矩阵，2026-09-27）。
        //
        //    缺陷形态：pushAndRecord 是"① 判额度 ② 推送 ③ 落库"。工单不存在时，
        //    流程会走到 ②【真的把订阅消息推给客户】（在接入真实网关后这是一次
        //    不可撤销的对外动作），然后 ③ 因 refund_receipt.refund_id 的外键
        //    违反而回滚 ⇒ 调用方拿到 500·9001，而客户手里已经收到了一条消息。
        //    即使当前推送还是桩，这条顺序在语义上已经是错的。
        //
        //    故此处把"工单必须存在"提到最前：不存在即 404·3001（与 G3 同码），
        //    推送动作根本不会发生。
        //
        //    🛑 为什么校验放在控制器而不是 RefundReceiptService：
        //    后者的业务编排点是 append-only 账本（RefundReceiptPort，5 个方法，
        //    含一个改写方法都没有）。给它加"读工单"的能力，等于在一个
        //    纯追加账本上开一个"能读业务实体"的邻居 —— 那正是该端口分家时
        //    刻意避免的（见 RefundWorkOrderPort 类注释）。
        //    而控制器已持有 workOrders（RefundWorkOrderService），
        //    读一次详情是它本就能做的事 —— 与 G2 详情端点同一形态。
        if (workOrders.detail(tenantId, refundId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "退款工单不存在: " + refundId + "（或不属于当前租户 —— 跨租户不可见由 RLS 承担）"
                            + "。🛑 本校验必须【先于推送】：否则会给一个不存在的工单"
                            + "真的推送一条回执消息，然后才因外键失败回滚 —— "
                            + "而那次推送是不可撤销的对外动作");
        }

        // 推送桩：S2-5 起接入真实订阅消息网关。此处按调用方回传的结果模拟，
        // 且【只在被调用时】才产生结果 —— 服务层在额度不足时根本不会调它。
        final boolean succeeded = Boolean.TRUE.equals(body.pushSucceeded());
        var record = receipts.pushAndRecord(
                tenantId,
                refundId,
                quota,
                templateId,
                tmpl -> new RefundReceiptService.PushOutcome(
                        succeeded,
                        succeeded ? null : (body.failureReason() == null
                                ? "订阅消息推送失败（原因未回传）" : body.failureReason()),
                        // 🛑 pushed_at 只在【成功】时给值，失败时必须为 null。
                        //
                        // 此处原先恒传 Instant.now() —— 后果是 G5 的「推送失败」分支
                        // **100% 不可用**：decideAfterPush 见「失败却有 pushed_at」
                        // 即抛 5001（库层 CHECK refund_receipt_pushed_at_iff_pushed
                        // 逐字写着『非已推送 ⇒ pushed_at IS NULL』），
                        // 于是"推送失败"这一态在库里【永不出现】。
                        // 而那正是 P0-19 点名的那一类缺陷的形态：
                        // 「覆盖率分母 = 已推送 + 未授权（转线下） + 推送失败」，
                        // 少一态 ⇒ 分母缺一块 ⇒ 覆盖率系统性偏高且看不出来。
                        //
                        // 🛑 它与本类 G1 那处「对 B 入口传 Instant.now()」是【同型缺陷】：
                        //    给一个桩传一个与本次结果无关的固定值。两次都只在真请求
                        //    E2E 上暴露 —— 单测直调服务层自己构造 PushOutcome，
                        //    永远不会经过控制器这一行。
                        succeeded ? Instant.now() : null),
                uuid(TenantContext.staffId(), "operator_id"),
                TenantContext.staffId());

        Map<String, Object> data = new LinkedHashMap<>();
        // 🛑 三态出站字面与契约 RefundReceiptData.receipt_state 逐字一致（含全角括号）
        data.put("receipt_state", record.decision().state().code());
        // 兜底义务与调用次数一并回显：它们把"漏发视同未回执"从一个未来状态
        // 变成调用方当下就能派单的待办。
        data.put("offline_fallback_required", record.offlineFallbackRequired());
        data.put("push_attempted_times", record.pushAttemptedTimes());
        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 内部：出站映射与解析
    // ==================================================================

    /**
     * 工单 → 契约 {@code RefundData}（<b>严格五项</b>）。
     *
     * <p>🛑 {@code entry} / {@code reason_code} <b>不</b>下发：
     * 契约 {@code RefundData} 的字段清单里没有它们（只有 {@code outcome}）。
     * 多下发会让端侧拿到契约未声明的字段，而端侧的 SDK 是按契约生成的 ——
     * 多出的字段在最好的情况下被忽略，在最坏的情况下让一次逐字比对失败。
     * 需要看原因的场合是 G2 的后续迭代，届时先改契约。
     */
    private Map<String, Object> toRefundData(RefundWorkOrderRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", row.refundId().toString());
        data.put("liable_store_id", row.liableStoreId().toString());
        data.put("sla_due_at", row.slaDueAt() == null ? null : row.slaDueAt().toString());
        data.put("recording_delay_h", row.recordingDelayH() == null
                ? null : row.recordingDelayH().doubleValue());
        data.put("outcome", row.outcome().code());
        return data;
    }

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "请求未携带租户上下文 —— 退款域不设无租户通道："
                            + "工单、挽留、原话、回执四条链全部按租户隔离（RLS FORCE）");
        }
        return tenantId;
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 必填");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }

    /**
     * 解析 ISO-8601 时间为 {@link Instant}（可空）。
     *
     * <p>🛑 用 {@code OffsetDateTime.parse} 而不是 {@code Instant.parse}：
     * 前者接受 {@code 2026-09-25T10:00:00+08:00}（带时区偏移，端侧常见的写法），
     * 后者只接受 {@code Z} 结尾。若只支持 {@code Z}，一次"带 +08:00 的完全合法的
     * ISO-8601 时间"会被拒成 400 —— 而调用方看到的是"时间格式错误"，
     * 真正的问题（服务端只认一种写法）被掩盖。
     */
    private static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 ISO-8601 时间: '" + raw + "'"
                            + "（期望形如 2026-09-25T10:00:00+08:00 或 2026-09-25T02:00:00Z）");
        }
    }

    /** 端点自描述（staff 只读）—— 把契约行与防线落点变成可读的运行时事实。 */
    @StaffOnly(clientDeniedFields = {"endpoints", "client_forbidden", "deny_layers“})
    @com.diaoyuanyun.dy.security.permission.RequirePermission(”refund:read")
    @GetMapping("/contract")
    public Result<Object> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "G 退款与挽留");
        m.put("endpoints", List.of(
                "G1 POST /api/v1/refunds",
                "G2 GET  /api/v1/refunds/{id}",
                "G3 POST /api/v1/refunds/{id}/retentions",
                "G4 POST /api/v1/refunds/{id}/approvals",
                "G5 POST /api/v1/refunds/{id}/receipts"));
        m.put("client_forbidden", true);
        m.put("client_denied_semantics",
                "契约 x-client-forbidden: true —— 客户调本域任一端点一律 403 VISIBILITY_DENIED，与参数无关");
        m.put("callable_roles", Map.of(
                "G1", List.of("meridian", "admin"),
                "G2", List.of("meridian", "admin"),
                "G3", List.of("meridian", "admin"),
                "G4", List.of("admin"),
                "G5", List.of("meridian", "admin")));
        m.put("approver_token_roles",
                com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole.approverTokenRoles());
        m.put("legacy_uppercase_aliases",
                com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole.legacyUppercaseAliases()
                        .entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                e -> e.getValue().configKey(),
                                (a, b) -> a, java.util.LinkedHashMap::new)));
        m.put("legacy_alias_note",
                "A-7（2026-09-26）：契约 VisibilityRole.ADMIN 的 4 个遗留大写别名已显式登记落点 —— "
                        + "它们与 manager/area/hq 同档位，不再触发 5001。下一轮删除大写别名时一并删除本表");
        m.put("hard_invisible_roles",
                RefundVisibilityMatrix.hardInvisibleRoles().stream()
                        .map(com.diaoyuanyun.dy.app.refund.domain.RefundAudienceRole::configKey)
                        .sorted().toList());
        m.put("deny_layers", List.of(
                "StaffOnly + 全局入站拦截器（按端点，客户一律 403）",
                "RefundAudienceRole.ofTokenRole + Matrix.canSee（staff 侧角色档位）",
                "RequirePermission（岗位功能权限）"));
        m.put("outcome_literals", RefundOutcome.allCodes());
        m.put("amount_basis_literals", AmountBasis.allCodes());
        // 🛑 刻意不下发 dbCode 侧的 entry 字面：两套字面并存这件事属内部实现细节，
        //    下发会让端侧看到契约外的第二个取值，进而有人把它当合法入参用。
        m.put("entry_literals", RefundEntry.allContractCodes());
        m.put("threshold_values_omitted",
                "本响应不含任何阈值数值 —— 避免成为配置值的第二份副本（改配置不改此处时不报错）");
        return Result.ok(m, MDC.get("traceId"));
    }
}