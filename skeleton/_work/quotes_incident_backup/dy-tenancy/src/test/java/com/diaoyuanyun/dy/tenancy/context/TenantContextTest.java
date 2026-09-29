package com.diaoyuanyun.dy.tenancy.context;

import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TenantContextTest: 租户上下文设置/清理 + X-Tenant-Id 不一致抛 403 对应异常 (ADR-02/05)。
 */
class TenantContextTest {

    @Test
    void set_and_clear_roundtrip() {
        assertFalse(TenantContext.isSet());
        TenantContext.set("t-1", "s-1", "STORE_STAFF", "own_store");
        assertTrue(TenantContext.isSet());
        assertEquals("t-1", TenantContext.tenantId());
        assertEquals("s-1", TenantContext.staffId());
        assertEquals("STORE_STAFF", TenantContext.role());
        assertEquals("own_store", TenantContext.scope());

        TenantContext.clear();
        assertFalse(TenantContext.isSet());
    }

    @Test
    void current_holder_isolated_per_thread() {
        TenantContext.set("t-9", null, null, null);
        assertEquals("t-9", TenantContext.current().tenantId());
        TenantContext.clear();
    }

    @Test
    void matching_header_and_token_is_allowed() {
        // 一致不应抛异常
        TenantContextFilter.assertTenantConsistency("t-1", "t-1");
        TenantContextFilter.assertTenantConsistency(null, "t-1");
        TenantContextFilter.assertTenantConsistency("t-1", null);
    }

    @Test
    void mismatched_header_throws_tenant_mismatch() {
        TenantMismatchException ex = assertThrows(TenantMismatchException.class,
                () -> TenantContextFilter.assertTenantConsistency("t-1", "t-2"));
        // 该异常对应 HTTP 403 + code=2003 TENANT_MISMATCH
        assertEquals(ErrorCode.TENANT_MISMATCH.getCode(), ex.getCode());
        assertEquals(2003, ex.getCode());
        assertEquals(403, ErrorCode.TENANT_MISMATCH.getHttpStatus());
    }

    /**
     * 注意: 过滤器级行为测试（含"无 token 不得用 X-Tenant-Id 兜底"、"非法 token 必须 401
     * 且返回标准信封"）已移至 {@link TenantContextFilterTest}，因为那些断言需要真实驱动
     * {@code doFilter} 并检查响应体，放在纯上下文测试里会失去重点。
     */
}
