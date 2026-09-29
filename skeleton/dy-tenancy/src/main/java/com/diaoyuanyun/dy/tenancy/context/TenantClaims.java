package com.diaoyuanyun.dy.tenancy.context;

/**
 * 从 JWT 解析出的租户声明。
 */
public record TenantClaims(String tenantId, String staffId, String role, String scope) {
}
