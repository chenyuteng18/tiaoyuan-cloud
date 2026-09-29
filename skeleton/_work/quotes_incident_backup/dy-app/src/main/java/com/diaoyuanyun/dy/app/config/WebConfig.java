package com.diaoyuanyun.dy.app.config;

import com.diaoyuanyun.dy.app.settlement.CrossStoreSettlement;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreProfileSource;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreThresholds;
import com.diaoyuanyun.dy.security.gate.GateInterceptor;
import com.diaoyuanyun.dy.security.permission.OrgScopeInterceptor;
import com.diaoyuanyun.dy.security.permission.PermissionInterceptor;
import com.diaoyuanyun.dy.security.visibility.DerivedVisibilityInterceptor;
import com.diaoyuanyun.dy.web.idempotent.IdempotencyInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配 (ADR-03/05/07/10)。注册拦截器, 顺序: 幂等 -> 派生字段入站 -> 权限 -> 门禁 -> 层级。
 *
 * <p>过滤器 (TenantContextFilter / TraceIdFilter / IdempotencyBodyCacheFilter) 以 @Component 自动注册,
 * 且 servlet 过滤器先于拦截器执行, 故租户上下文与请求体缓存在控制器前已就绪。
 *
 * <p><b>为何派生字段拦截器排在权限拦截器之前</b>：两者都归 2001，
 * 但错误信息的具体程度不同 —— 权限拦截器只能说"你没有某个功能权限",
 * 派生字段拦截器能说"你索取了 {@code effect_verdict} / {@code as_value}, 这两个你没有档位"。
 * 让<b>更具体的判定</b>先发生, 调用方拿到的错误更贴近他真正要改的东西
 * （与下面"层级拦截器排在最后"同一条理由, 方向一致: 具体判定优先于粗粒度判定）。
 * 它也必须在幂等之后: 幂等需要在 preHandle 读请求体算哈希,
 * 而本拦截器同样要读体 —— 两者都依赖 {@code IdempotencyBodyCacheFilter} 提供的可重读体。
 *
 * <p><b>为何层级拦截器排在最后</b>：{@code OrgLevel.fromRole} 对未登记角色是 fail-closed 抛错（2003）。
 * 若它排在权限拦截器之前，一个"角色没登记"的请求会先被层级拦成 2003，
 * <b>而它其实可能连功能权限都不具备</b>（应报 2001）——报错码会指错方向。
 * 让"更具体的判定"（功能权限）先发生，报错语义更贴近真实原因；
 * 层级判定作为"数据范围"这一层的补充，在其后执行。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;
    private final DerivedVisibilityInterceptor derivedVisibilityInterceptor;
    private final PermissionInterceptor permissionInterceptor;
    private final GateInterceptor gateInterceptor;
    private final OrgScopeInterceptor orgScopeInterceptor;

    public WebConfig(IdempotencyInterceptor idempotencyInterceptor,
                     DerivedVisibilityInterceptor derivedVisibilityInterceptor,
                     PermissionInterceptor permissionInterceptor,
                     GateInterceptor gateInterceptor,
                     OrgScopeInterceptor orgScopeInterceptor) {
        this.idempotencyInterceptor = idempotencyInterceptor;
        this.derivedVisibilityInterceptor = derivedVisibilityInterceptor;
        this.permissionInterceptor = permissionInterceptor;
        this.gateInterceptor = gateInterceptor;
        this.orgScopeInterceptor = orgScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor);
        // S1-5 验收②: 客户请求派生字段一律 403（全局生效, 不依赖端点自己贴注解）
        registry.addInterceptor(derivedVisibilityInterceptor);
        registry.addInterceptor(permissionInterceptor);
        registry.addInterceptor(gateInterceptor);
        registry.addInterceptor(orgScopeInterceptor);
    }

    /**
     * 跨店通兑结算器（无状态, 无 DB 依赖, 但<b>阈值来自配置</b>）。
     *
     * <h2>🛑 本次修复：从"硬编码默认值"改为"读 config #21/#22"</h2>
     * 此前本 Bean 直接 {@code new CrossStoreSettlement()}，而该无参构造内部把
     * {@code 0.30} 写成 {@code public static final DEFAULT_SPLIT_THRESHOLD} ——
     * 而 config {@code #21} 的 seed 语义列逐字写着「<b>归属层级=总部唯一，非校准项</b>」。
     * 两者相加 ⇒ <b>「总部唯一可改」这句当时不成立</b>：总部在配置里把 0.30 改成 0.40，
     * 结算仍按 0.30 拆，且系统不会有任何提示（N-14，由批次十的消费台账首次清空暴露）。
     *
     * <p>现改为经 {@link CrossStoreProfileSource} 读声明值、由
     * {@link CrossStoreThresholds#fromRawConfig} 解析校验后注入。这条链路的
     * "真的会变"由 {@code CrossStoreProfileSourceTest}（来源侧）+ 结算行为断言（消费侧）
     * 双向钉住，不再是注释里的承诺。
     */
    @Bean
    public CrossStoreSettlement crossStoreSettlement(CrossStoreProfileSource profileSource) {
        return new CrossStoreSettlement(
                CrossStoreThresholds.fromRawConfig(profileSource.raw()));
    }
}
