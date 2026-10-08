package com.diaoyuanyun.dy.app.ops.controller;

import com.diaoyuanyun.dy.audit.chain.ChainVerification;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.security.permission.RequireOrgLevel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维健康自检端点（商用开发第二批 · E2）—— 部署包 {@code deploy/DEPLOY.md}
 * 告警手册的机器可读入口：一次调用聚合四项自检，供巡检脚本 / 值班人判断"系统现在对不对"。
 *
 * <h2>四项自检（每项都给结论 + 证据，不给裸 OK）</h2>
 * <ol>
 *   <li>{@code db} —— 数据库连通性（{@code SELECT 1}）+ 已登记迁移数（缺迁移 =
 *       部署半途，最典型的"上线了但没上全"）；</li>
 *   <li>{@code redis} —— 连通性（PING）。<b>可缺失</b>：吊销黑名单是可选装配
 *       （{@code dy.auth.revocation=redis} 才有 Bean），未装配时如实报
 *       {@code not_configured}，不把"没配"谎报成"坏了"或"好的"；</li>
 *   <li>{@code audit_chain} —— 审计哈希链完整性（复用 {@link AuditLogService#verifyChain()}）；
 *       链断 = 证据链失效，合规侧最重的一级信号；</li>
 *   <li>{@code overall} —— UP / DEGRADED 汇总。Redis 未配置算 DEGRADED 吗？不算
 *       —— 它是可选件；DB 或审计链异常才算降级。</li>
 * </ol>
 *
 * <h2>为什么是总部 + StaffOnly</h2>
 * 响应含迁移数与链校验结论 —— 是部署拓扑与审计状态的情报面，不面向门店/客户。
 * 巡检脚本用总部服务账号调用（DEPLOY.md 手册有调用示例）。
 *
 * <h2>只读</h2>
 * 无 INSERT/UPDATE，不加幂等注解；verifyChain 是重算型校验（O(日志行数)），
 * 巡检频率建议 ≥ 60s（手册已写），不做缓存 —— 缓存会让"刚断的链"延迟暴露。
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsHealthController {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AuditLogService auditLogService;

    public OpsHealthController(JdbcTemplate jdbc,
                               ObjectProvider<StringRedisTemplate> redisProvider,
                               AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.redisProvider = redisProvider;
        this.auditLogService = auditLogService;
    }

    /**
     * 聚合健康自检。
     * 任何单项内部异常都被捕获为该项 {@code status=DOWN + error} ——
     * 健康端点自己绝不能 500，否则"系统坏了"和"健康检查坏了"无法区分。
     */
    @GetMapping("/health")
    @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)
    public ResponseEntity<Result<Map<String, Object>>> health() {
        Map<String, Object> report = new LinkedHashMap<>();
        boolean degraded = false;

        // ① DB 连通 + 迁移登记数
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            Integer migrations = jdbc.queryForObject(
                    "SELECT count(*) FROM schema_migration", Integer.class);
            db.put("status", "UP");
            db.put("registered_migrations", migrations);
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", safeMessage(e));
            degraded = true;
        }
        report.put("db", db);

        // ② Redis（可缺失：未装配 = not_configured，不算降级）
        Map<String, Object> redis = new LinkedHashMap<>();
        StringRedisTemplate template = redisProvider.getIfAvailable();
        if (template == null) {
            redis.put("status", "not_configured");
            redis.put("hint", "dy.auth.revocation 未配置为 redis，吊销黑名单未装配（可选项）");
        } else {
            try {
                String pong = template.execute((org.springframework.data.redis.core.RedisCallback<String>)
                        conn -> conn.ping());
                redis.put("status", pong != null ? "UP" : "DOWN");
                if (pong == null) {
                    redis.put("error", "PING 返回空");
                }
            } catch (Exception e) {
                redis.put("status", "DOWN");
                redis.put("error", safeMessage(e));
            }
        }
        report.put("redis", redis);

        // ③ 审计链完整性（重算型校验）
        Map<String, Object> chain = new LinkedHashMap<>();
        try {
            ChainVerification v = auditLogService.verifyChain();
            chain.put("status", v.valid() ? "UP" : "BROKEN");
            chain.put("checked", v.checked());
            if (!v.valid()) {
                chain.put("broken_at", v.brokenAt());
                chain.put("reason", v.reason());
                degraded = true;
            }
        } catch (Exception e) {
            chain.put("status", "DOWN");
            chain.put("error", safeMessage(e));
            degraded = true;
        }
        report.put("audit_chain", chain);

        report.put("overall", degraded ? "DEGRADED" : "UP");
        return ResponseEntity.ok(Result.ok(report, org.slf4j.MDC.get("traceId")));
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 200));
    }
}
