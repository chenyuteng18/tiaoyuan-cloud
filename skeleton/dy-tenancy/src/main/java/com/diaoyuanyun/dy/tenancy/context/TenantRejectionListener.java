package com.diaoyuanyun.dy.tenancy.context;

/**
 * 租户拒绝事件监听（S1-3 验收④：跨租户拒绝须留痕）。
 *
 * <h2>为什么是一个接口放在 dy-tenancy，而不是直接依赖 dy-audit</h2>
 * 拒绝发生在 {@link TenantContextFilter}（servlet 过滤器）里。若在该处直接调用
 * {@code AuditLogService}，则 dy-tenancy 必须依赖 dy-audit —— 而依赖方向是
 * <b>单向声明并被 ArchUnit 强制</b>的（见 {@code ArchitectureBoundaryTest}）。
 * 为一行日志把 tenancy 钉死在 audit 上，会让 tenancy 无法独立复用，
 * 也把"网络/IO 依赖"引入了本应最纯粹的租户上下文层。
 *
 * <p>故此处只留<b>一个窄接口</b>（单一方法、无返回值、无审计领域类型）：
 * <ul>
 *   <li>dy-tenancy 只声明"发生了拒绝"这一事实，不关心谁去记、怎么记；</li>
 *   <li>dy-app 提供实现，把它桥接到 {@code AuditLogService}；</li>
 *   <li>未装配实现时 {@link TenantContextFilter} 使用"空实现"（见其构造器），行为与改造前一致。</li>
 * </ul>
 *
 * <h2>实现方的硬纪律（违反则本接口失去意义）</h2>
 * <ol>
 *   <li><b>不得抛异常</b>。本方法在拒绝路径上被调用，此时请求<b>已经被拒</b>；
 *       若留痕失败又把异常抛出去，会把"403 租户不匹配"变成"500 系统异常"——
 *       对外语义被一个附属动作篡改，是比"没记上日志"严重得多的故障。
 *       实现必须自行吞掉异常并降级记录（见 {@code TenantRejectionAuditBridge}）。</li>
 *   <li><b>不得阻塞过久</b>。它在请求线程上同步执行。</li>
 *   <li><b>不得回写/修改请求状态</b>。响应已成既定事实。</li>
 * </ol>
 *
 * @see TenantContextFilter#doFilter
 */
@FunctionalInterface
public interface TenantRejectionListener {

    /**
     * 一次租户相关拒绝已发生。
     *
     * @param errorCode 拒绝所用的业务码（如 {@code 2003} TENANT_MISMATCH / {@code 1002} UNAUTHENTICATED）
     * @param reason    可供排查的原因（<b>不得含 token 原文、密钥等敏感内容</b>）
     * @param path      请求路径（用于定位"谁在跨租户访问"）
     */
    void onRejected(int errorCode, String reason, String path);

    /** 空实现：未装配审计桥时的默认值，行为等价于"本机制不存在"。 */
    TenantRejectionListener NOOP = (errorCode, reason, path) -> {
        // 有意留空：未装配监听器时不留痕，但绝不因此改变拒绝结果。
    };
}