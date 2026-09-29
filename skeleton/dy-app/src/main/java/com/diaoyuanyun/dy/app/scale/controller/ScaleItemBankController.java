package com.diaoyuanyun.dy.app.scale.controller;

import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringEngine;
import com.diaoyuanyun.dy.app.scale.domain.ScaleScoringProfile;
import com.diaoyuanyun.dy.app.scale.service.ScaleItemBankService;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.RequirePermission;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S1-4 题库引擎骨架的 HTTP 入口 —— 让三项验收（导入 / 组卷 / 计分）可被端到端演示。
 *
 * <h2>权限与租户上下文</h2>
 * 题库属<b>总部维护</b>的内容资产（PRD §8：责任方 = 总部，可授权品牌自定义），
 * 故导入与组卷要求 {@code customer:write} —— 该码的持有者是<b>管理层级与遗留 staff 码</b>，
 * <b>不含</b>一线角色。
 *
 * <p>🛑 这里是一次刻意的<b>码分离</b>（S2-10）：域 B 的档案写入（B1/B2/B3/B6）另用
 * {@code customer:archive}（契约点名 therapist/meridian/admin 可调），
 * 而 {@code customer:write} <b>专供</b>本模块这类内容资产写动作。
 * 两者若合并，要么契约域 B 对一线角色恒 403，要么题库写权被发给一线角色 ——
 * 两个结果都错。详见 {@code PermissionRegistry} 的域 B 一节。
 *
 * <p>租户标识一律取自 {@link TenantContext}（由 token 建立），
 * <b>绝不</b>从请求体读取 —— 请求体里的租户 ID 是客户端可伪造的，
 * 用它当隔离依据等于把 RLS 的前提交给调用方。
 *
 * <h2>计分口径的可见性（本端点最重要的一个设计决定）</h2>
 * {@code /scale/profile} 返回量程口径的<b>元信息</b>（下限/上限/级数/维度满分/总分上限），
 * 但<b>不</b>返回严重度分档边界 —— 因为后者尚未校准（见 {@link ScaleScoringProfile}）。
 * 计分响应里的 {@code severity_label} 在口径未配置时输出 {@code TBD}
 * （{@link ContractTbd#TBD}）。🛑 这是硬纪律 #6「TBD 不得填数」的对外体现：
 * 宁可让客户看到 TBD，也不给一个未经校准的档位 ——
 * 一个错的分档会被当成结论使用，而 TBD 不会。
 *
 * <h2>可见性（服务端判定，非前端不渲染）</h2>
 * 计分结果里含"维度分 / 总分"，属<b>对内判定辅助</b>，不进客户端。
 * 故本控制器的计分端点要求 {@code customer:read}（读码，一线角色持有），
 * 并<b>不</b>向客户端下发任何派生结论字段（{@code effect_verdict} / 改善率 / 达标评价）——
 * 那些字段在本模块中根本不存在（它们属判定引擎 S1-5 的管辖范围，
 * 且按 PRD P0-13 只对内）。此处的"不存在"由
 * {@code ScaleEngineContractTest#scoring_response_contains_no_derived_conclusion_fields} 断言守住。
 *
 * <h2>包结构（为什么在 {@code controller} 子包而不是 {@code scale} 根包）</h2>
 * 架构守卫 {@code ArchitectureBoundaryTest} 的 R2 规则形如
 * 「{@code app.controller..} 不得依赖 {@code repository..}」。若本类留在 {@code app.scale} 根包，
 * 它<b>不在</b>任何被登记的层里 —— 于是"控制器直接抓仓储"这类捷径能溜过守卫。
 * 放到 {@code app.scale.controller} 子包并登记进守卫的分层清单，
 * 才使新模块与既有模块受<b>同一套</b>约束。这不是目录洁癖，是让守卫真的生效。
 */
@RestController
@RequestMapping("/api/v1/scale")
public class ScaleItemBankController {

    private final ScaleItemBankService service;

    public ScaleItemBankController(ScaleItemBankService service) {
        this.service = service;
    }

    /** 量程口径元信息（只读）。 */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        ScaleScoringProfile p = service.currentProfile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_min", p.itemMin());
        m.put("item_max", p.itemMax());
        m.put("levels", p.levels());
        m.put("items_per_dimension", p.itemsPerDimension());
        m.put("dimension_count", p.dimensionCount());
        m.put("dimension_max", p.dimensionMax());
        m.put("total_max", p.totalMax());
        m.put("age_groups", ScaleDomain.AgeGroup.allLabels());
        m.put("dimensions", ScaleDomain.Dimension.allLabels());
        // 🛑 severity_bands 尚未校准，只报"是否已配置"，绝不下发任何边界数字
        m.put("severity_bands_configured", p.hasSeverityBands());
        m.put("severity_label", p.hasSeverityBands() ? ScaleScoringProfile.SEVERITY_LABELS
                : ContractTbd.TBD);
        m.put("profile_source", p.source());
        m.put("note", "口径来源为 config #35（cfg:scale.range_rule）；"
                + "严重度分档边界未校准前一律 TBD，不得填数（硬纪律 #6）");
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 导入一个题组的样例题（总部维护入口）。 */
    @RequirePermission("customer:write")
    @PostMapping("/items/import")
    public Result<Object> importItems(@RequestBody ImportRequest request) {
        String tenantId = TenantContext.tenantId();
        List<ScaleItemRow> rows = request.rows() == null
                ? null
                : request.rows().stream().map(ImportRequest.Item::toRow).toList();
        return Result.ok(service.importItems(tenantId, rows), MDC.get("traceId"));
    }

    /** 组卷：按锁定年龄组 + 版本取恰好 28 题（7 维 × 4）。 */
    @RequirePermission("customer:write")
    @PostMapping("/paper/compose")
    public Result<Object> composePaper(@RequestBody ComposeRequest request) {
        String tenantId = TenantContext.tenantId();
        ScaleItemBankService.Paper paper =
                service.composePaper(tenantId, request.ageGroup(), request.version());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("age_group", paper.ageGroup());
        m.put("version", paper.version());
        m.put("item_count", paper.size());
        m.put("dimension_item_counts", paper.byDimension().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().size(), (a, b) -> a, LinkedHashMap::new)));
        m.put("item_ids", paper.items().stream().map(i -> i.itemId().toString()).toList());
        return Result.ok(m, MDC.get("traceId"));
    }

    /** 计分：分值上下限全部来自配置口径，调用方无法传入分值参数。 */
    @RequirePermission("customer:read")
    @PostMapping("/score")
    public Result<Object> score(@RequestBody ScaleScoringEngine.Submission submission) {
        String tenantId = TenantContext.tenantId();
        ScaleScoringEngine.ScoreResult r = service.score(tenantId, submission);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("age_group", r.ageGroup());
        m.put("item_version", r.itemVersion());
        m.put("dimension_scores", r.dimensionScores());
        m.put("total_score", r.totalScore());
        m.put("severity_label", r.severityLabel());
        m.put("item_min_max", r.itemMinMax());
        // 明确标注：本响应不含任何派生结论（效果判定 / 改善率 / 达标评价），
        // 那些字段属判定引擎且只对内（PRD P0-13）。
        m.put("derived_conclusions_included", false);
        return Result.ok(m, MDC.get("traceId"));
    }

    // ------------------------------------------------------------------
    // 请求体
    // ------------------------------------------------------------------

    /**
     * 导入请求体。字段名 snake_case，与库列名/契约风格一致。
     *
     * <p>🛑 本嵌套 record 是<b>契约形状</b>（客户端能看到它），故它<b>只</b>依赖
     * {@code domain} 的值对象（{@link ScaleItemRow}），<b>不</b>依赖服务层的类型。
     * 早先它返回 {@code ScaleItemBankService.ItemRow}，触发架构规则 R4
     * （「任何 record 不得依赖服务层」）—— 那是真实回归：它把契约形状绑死在服务层内部类型上。
     */
    public record ImportRequest(List<Item> rows) {

        /** 单题导入项。 */
        public record Item(
                String ageGroup,
                String dimension,
                Integer itemNo,
                String itemText,
                List<String> anchors,
                String itemDirection,
                String version,
                String reviewerId,
                String reviewedAt) {

            ScaleItemRow toRow() {
                Instant reviewed = (reviewedAt == null || reviewedAt.isBlank())
                        ? null
                        : Instant.parse(reviewedAt.trim());
                List<String> a = anchors == null ? List.of() : anchors;
                return new ScaleItemRow(
                        null,
                        ageGroup,
                        dimension,
                        itemNo == null ? 0 : itemNo,
                        itemText,
                        a.size() > 0 ? a.get(0) : null,
                        a.size() > 1 ? a.get(1) : null,
                        a.size() > 2 ? a.get(2) : null,
                        a.size() > 3 ? a.get(3) : null,
                        a.size() > 4 ? a.get(4) : null,
                        itemDirection,
                        version,
                        reviewerId,
                        reviewed,
                        null);
            }
        }
    }

    /** 组卷请求体。 */
    public record ComposeRequest(String ageGroup, String version) {
    }
}