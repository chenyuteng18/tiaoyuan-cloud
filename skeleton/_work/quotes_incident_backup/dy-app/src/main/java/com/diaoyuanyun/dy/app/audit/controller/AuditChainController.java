package com.diaoyuanyun.dy.app.audit.controller;

import com.diaoyuanyun.dy.audit.chain.ChainVerification;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.security.visibility.StaffOnly;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志哈希链的<b>校验端点</b>（C-1）—— 把已存在的 {@link AuditLogService#verifyChain()}
 * 接上 HTTP 面，使合规审计的"证据链自检"成为一次可执行的调用。
 *
 * <h1>🛑 它与契约 F3 / F4 的关系：同前缀、不同面（这是本类最容易被误读的一点）</h1>
 *
 * <p>契约域 F 有两个 {@code /audit/*} 端点，它们<b>不是</b>本类在做的事：
 * <pre>
 *   F3  GET /audit/signals    契约 [admin]   —— 业务稽核<b>信号</b>（哪些疗程该被稽核）
 *   F4  GET /audit/coverage   契约 [admin]   —— 可判定<b>覆盖率</b>（三数同显）
 * </pre>
 * 两者都依赖"稽核信号 / 覆盖率"的上游业务口径，<b>尚未立项</b>（README §5.2 已登记）。
 * 而本端点的对象是 {@code audit_log} 表本身 —— <b>技术性证据链的完整性</b>，
 * 与"业务上该稽核谁"毫无关系。故：
 * <ul>
 *   <li>路径刻意取 {@code /audit/log-chain}（不是 {@code /audit/chain}）——
 *       把 {@code log-chain} 写进路径，使"这是日志链自检、不是业务稽核"在读路径时即成立。
 *       {@code /audit/chain} 会让人以为它是 F3/F4 的同类物。</li>
 *   <li>本类<b>不</b>触碰 F3/F4 的任何语义，也<b>不</b>为它们预留实现。
 *       两者的待办状态不受本类影响（F3/F4 仍是"未立项"，见 README）。</li>
 * </ul>
 *
 * <h1>🛑 为什么它不在契约的 45 个端点里（且这不算"造端点"）</h1>
 *
 * <p>与 {@code GET /verdicts/{id}/replay}（S2-8 / ADR-11 回放闭环）同族：本工程已登记的
 * 架构决策要求审计链可独立复算（{@code AuditChainHash} 的规范串冻结 + 黄金向量交叉验证），
 * 而"复算一次并给出结论"这件事在契约里没有面客对应物 —— 它是<b>内部证据链的自检动作</b>，
 * 不是一次业务操作。故本章程与 {@code /verdicts/contract} / {@code /doc-templates/contract}
 * 同属<b>内部自描述端点族</b>：只读、只对 staff、存在的目的正是"证明契约与架构里那条
 * '可独立复算'的要求确实成立"。
 *
 * <p>🛑 它<b>不</b>改任何数据：无 {@code @Idempotent}。给一个只读校验加幂等注解，
 * 会在代码里留下"它是写操作"的证据 —— 而它连一个 {@code INSERT} 都没有。
 *
 * <h1>🛑 权限：为什么是「仅 hq」而不是「hq + area + manager」</h1>
 *
 * <p>{@code audit_log} 是<b>全局单链</b>：它刻意豁免 RLS（{@code T-09}，
 * 被 {@code RlsCoverageGateTest} 登记为豁免项），因为一条按租户分段的哈希链
 * 在密码学上没有意义 —— 链是<b>跨租户串联</b>的，第 N+1 条的 {@code prev_hash}
 * 接的是第 N 条（可能是别的租户）的 {@code hash}。这带来一个真实的结构性后果：
 *
 * <p><b>行级 scope 语义在这里无法成立。</b>契约 F3 的 {@code x-row-scope} 写着
 * 「门店负责人仅本店、区域督导仅辖区、总部全量」—— 那个机制预设了"对象属于某个租户"。
 * 但审计链不属于任何单一租户。若把 {@code area} 或 {@code manager} 放进来，
 * 就会造出"用一个本店 scope 的身份去读一个全局对象"的语义裂缝：
 * 校验结论（{@code broken_at} 指向的 id、断链种类）是<b>全平台</b>的，
 * 它不是"本店的那部分"。届时"为什么我能看到别的门店的断链"将无答案。
 *
 * <p>故本端点的可调角色 = <b>{@code hq}（总部）</b>，与外部审计方的实际身份一致
 * （合规审计的执行者是总部或受其委托的第三方）。权限码取<b>新码 {@code audit:read}</b>：
 * 它描述的是"读审计与稽核事实"，与 {@code refund:read} / {@code verdict:read} 等
 * 业务域读码不在同一层，混用会让"谁该读审计"这个判断附着在业务域上。
 *
 * <h2>⚠️ 登记待裁（不代拍）</h2>
 * <p>若产品确需"区域督导对辖区做审计自检"，则须先回答一个语义问题，而不是先加一行权限登记：
 * <b>辖区自检的对象是什么？</b>候选答案至少三种，取值不同则实现完全不同 ——
 * <ol>
 *   <li><b>全局链 + 辖区断点过滤</b>：校验全链，但只报"断点是否落在本辖区"。
 *       🛑 这与"单点篡改必被发现"的既有语义冲突：一个辖区外的断点会<b>污染</b>
 *       之后的全部 {@code prev_hash}，故辖区内的"链有效"结论不成立（链已断在前面）。
 *       这种做法会产出<b>几何上不可能为真的"本辖区链有效"</b>。</li>
 *   <li><b>按租户口径分段校验</b>：把辖区内的行单独取出来重算。
 *       🛑 这在密码学上无意义 —— 取出来的行其 {@code prev_hash} 指向的是被抽掉的行，
 *       必然对不上。除非另立"辖区子链"的写入口径（那是一次写入侧改造，不是读侧增强）。</li>
 *   <li><b>只给"本辖区在全局链上的健全性摘要"</b>（例如"本辖区行数 + 是否被任何
 *       全局断点覆盖"），不给断链定位。这是一个<b>新的结论形状</b>，
 *       需要契约 owner + 安全评审共签。</li>
 * </ol>
 * 在这三者中被裁定之前，本端点<b>不开</b> {@code area}/{@code manager} ——
 * 开出去就会让上面第 1 或第 2 种"必然为假的结论"变成对外可调的事实。
 */
@RestController
@RequestMapping("/api/v1")
public class AuditChainController {

    private final AuditLogService auditLogs;

    public AuditChainController(AuditLogService auditLogs) {
        this.auditLogs = auditLogs;
    }

    // ==================================================================
    // C-1 · GET /audit/log-chain
    // ==================================================================

    /**
     * 重算整条审计链并与存储值比对（C-1）。
     *
     * <p>响应 {@code data} <b>就是</b> {@link ChainVerification} 的序列化形态：
     * {@code {valid, broken_at?, reason, checked}}。它<b>不</b>被包成
     * {@code {"verification": {...}}} —— 多套一层会让"契约形状 {@code {valid, broken_at?}}"
     * 这句话在实现里不再逐字成立，而 {@code ChainVerification} 的
     * {@code @JsonProperty("broken_at")} / {@code NON_NULL} 正是为了让它逐字成立才加的。
     * 故这里<b>不</b>做任何字段搬运：搬运一次就多一次把 snake_case 写错的机会。
     *
     * <h2>🛑 能力边界必须随结论一起下发（本方法的合规要点）</h2>
     * {@link AuditLogService#verifyChain()} 的 javadoc 明写了两条对外陈述纪律：
     * 允许说"审计链<b>不可悄然篡改</b>"，<b>禁止</b>说"审计链不可篡改"。
     * 一个只返回 {@code valid=true} 的端点，调用方很容易把它读成后者
     * （"valid ⇒ 数据从未被改过"）。故本方法把能力边界<b>结构化地随响应下发</b>
     * （{@code capability_envelope} / {@code not_proven}），
     * 而不是把这句话只留在源码注释里 —— 注释不会跟着响应走，响应会。
     *
     * <p>这不是"多话"：{@code cascade_rehash_defeats_the_chain} 这个测试用例
     * <b>断言 valid=true</b> 就是断言一个坏消息 —— 即在有 UPDATE 权限的攻击者
     * 逐行重算后，本端点会返回 {@code valid=true}。任何一个读这个端点的人
     * 都必须能读到这件事，否则它就是一次误导性披露。
     */
    @StaffOnly(clientDeniedFields = {
            "valid", "broken_at", "reason", "checked", "capability_envelope", "not_proven“})
    @RequirePermission(”audit:read")
    @GetMapping("/audit/log-chain")
    public Result<Map<String, Object>> verifyLogChain() {
        // 租户上下文仍要求（身份可信链路的一环：JWT → TenantContext → 这里）。
        // 🛑 但校验本身【不】按 tenant 过滤：audit_log 是全局单链（见类注释）。
        // 这里读 tenantId 只是为了确认"调用方是一个已在上下文里的 staff"，
        // 而不是为了拿它当查询条件 —— 后者会静默变成"只校验本租户的行"，
        // 那是一个永远返回 valid=true 的假校验（因为它只看链的中间一段，
        // 而中间段的 prev_hash 指向被排除的行）。这个坑值得在代码里点名。
        String tenantId = requireTenant();

        ChainVerification v = auditLogs.verifyChain();

        Map<String, Object> data = new LinkedHashMap<>();
        // ↓ 契约形状的四项，逐字来自 ChainVerification 的 Jackson 映射
        data.put("valid", v.valid());
        if (v.brokenAt() != null) {
            data.put("broken_at", v.brokenAt());
        }
        data.put("reason", v.reason());
        data.put("checked", v.checked());
        // ↓ 能力边界（结构化，随响应走）
        data.put("capability_envelope", List.of(
                "能证明：链中间任一行的改写 / 删除必被发现并定位（改行 ⇒ 它自己 hash 失配；"
                        + "删中间行 ⇒ 其后继 prev_hash 失配）",
                "不能证明①：数据从未被篡改 —— 已取得 UPDATE 权限者可自断点起逐行重算并回写，"
                        + "此时本端点返回 valid=true（无密钥哈希链的固有性质）",
                "不能证明②：链尾未被截断 —— 删除【最后若干条】记录不留任何后继可验，"
                        + "本端点同样返回 valid=true。唯一可观测迹象是 checked 条数，"
                        + "而 checked 只能说明『现在有多少条』、不能说明『应该有多少条』",
                "外锚定（每条 HMAC / 定期把 (count, tail_hash) 锚定到库外或可信时间戳 / WORM 存储）"
                        + "是上述两条的唯一补救路径，属 ADR-11 后续项，尚未实现"));
        data.put("not_proven",
                "本结论不构成「审计链不可篡改」；可用的表述是「不可悄然篡改」"
                        + "（且『悄然』的边界由上条能力范围限定：中间篡改会被发现，"
                        + "尾部截断与级联重算不会）");
        // ↓ 自省面：让调用方看出"这次校验的对象范围"，避免误读为"本租户的链"
        data.put("scope_note", "校验对象为 audit_log 全局单链（跨租户串联），非本租户子集");
        data.put("caller_tenant", tenantId);

        return Result.ok(data, MDC.get("traceId"));
    }

    // ==================================================================
    // 内部自描述端点（与 /verdicts/contract 同族）
    // ==================================================================

    /**
     * 自描述：本端点的契约位置、结论形状、能力边界与待裁项。
     *
     * <p>与 {@code /verdicts/contract} / {@code /doc-templates/contract} 同一惯例 ——
     * 内部自描述端点以 {@code /contract} 结尾，使"哪些端点在契约里"这件事
     * 在路由表上即可读出。
     */
    @StaffOnly(clientDeniedFields = {
            "valid", "broken_at", "reason", "checked", "capability_envelope", "not_proven“})
    @RequirePermission(”audit:read")
    @GetMapping("/audit/log-chain/contract")
    public Result<Map<String, Object>> describeContract() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", "GET /audit/log-chain");
        m.put("in_frozen_contract", false);
        m.put("why_not_in_contract",
                "内部证据链自检动作，非业务操作。与 GET /verdicts/{id}/replay 同族"
                        + "（同为内部自描述端点族：只读、只对 staff、存在的目的是证明"
                        + "契约与架构里『可独立复算』的要求成立）。");
        m.put("distinct_from", List.of(
                "F3 GET /audit/signals  —— 业务稽核信号（依赖上游口径，未立项）；与本端点同前缀、不同面",
                "F4 GET /audit/coverage —— 可判定覆盖率三数同显（未立项）；同上"));
        m.put("response_shape", "{valid, broken_at?, reason, checked} + 能力边界四项");
        m.put("broken_at_absent_when_valid",
                "链有效时 broken_at 字段【不出现】（@JsonInclude(NON_NULL)）。"
                        + "一个恒返 broken_at:null 的实现会让消费方分不清『链有效』与『未校验』。");
        m.put("reason_values", List.of(
                ChainVerification.REASON_HASH_MISMATCH + "（该行被改写）",
                ChainVerification.REASON_PREV_HASH_MISMATCH + "（中间有行被删除）",
                ChainVerification.REASON_GENESIS_MISMATCH + "（链头被动了）",
                ChainVerification.REASON_ORDER_AMBIGUOUS + "（created_at 并列 ⇒ 顺序不可判定，拒答）",
                ChainVerification.REASON_MALFORMED_HASH + "（链字段被写成垃圾/截断）"));
        m.put("callable_roles", List.of("hq"));
        // 🛑 直接读注解，而不是手抄一份清单 —— 手抄的清单会在某次"注解加了字段但清单没改"
        //    时静默分叉，而本端点的整条价值就建立在"声明的形状 = 实际的形状"之上。
        m.put("client_denied_fields", deniedFieldsFromAnnotation());
        m.put("permission_code", "audit:read（新码；『读审计与稽核事实』是独立于业务域读码的一层）");
        m.put("why_hq_only", List.of(
                "audit_log 是全局单链（跨租户串联，刻意豁免 RLS / T-09）⇒ 不属于任何单一租户",
                "契约 F3 的 x-row-scope『门店负责人仅本店、区域督导仅辖区』预设了『对象属于某租户』",
                "把 area/manager 放进来会造出『本店 scope 的身份读全局对象』的语义裂缝"));
        m.put("pending_ruling",
                "若产品需区域督导自检，须先裁定三门走法之一（全局链+辖区断点过滤 / 按租户分段校验 / "
                        + "仅给辖区健全性摘要）。前两者会产出【必然为假】的『本辖区链有效』结论，"
                        + "详见 AuditChainController 类注释。");
        m.put("pending_ruling_owner", "契约 owner + 安全评审（共签）");
        m.put("writes_data", false);
        return Result.ok(m, MDC.get("traceId"));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH, "请求未携带租户上下文");
        }
        return tenantId;
    }

    /** 从 {@link StaffOnly} 注解读回被拒字段名（唯一真相源是注解，不是这里的列表）。 */
    private static List<String> deniedFieldsFromAnnotation() {
        try {
            StaffOnly ann = AuditChainController.class
                    .getMethod("verifyLogChain").getAnnotation(StaffOnly.class);
            if (ann == null) {
                return List.of();
            }
            return List.of(ann.clientDeniedFields());
        } catch (NoSuchMethodException e) {
            return List.of();
        }
    }
}