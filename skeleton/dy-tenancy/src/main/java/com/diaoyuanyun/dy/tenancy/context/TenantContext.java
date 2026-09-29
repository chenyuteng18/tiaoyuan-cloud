package com.diaoyuanyun.dy.tenancy.context;

/**
 * 租户上下文 (ThreadLocal 持有 tenant_id / staff_id / role / scope)。
 *
 * <p>请求入口 (TenantContextFilter) 写入, 出口清理。RLS 切面与审计切面均从此读取。
 * 租户上下文贯穿一次请求, 不得跨请求残留 (ADR-02 防止连接池串租户)。
 */
public final class TenantContext {

    private static final ThreadLocal<Holder> CTX = ThreadLocal.withInitial(Holder::new);

    private TenantContext() {
    }

    /** 请求级上下文载体 */
    public static final class Holder {
        private String tenantId;
        private String staffId;
        private String role;
        private String scope;

        public String tenantId() {
            return tenantId;
        }

        public String staffId() {
            return staffId;
        }

        public String role() {
            return role;
        }

        public String scope() {
            return scope;
        }
    }

    public static void set(String tenantId, String staffId, String role, String scope) {
        Holder h = CTX.get();
        h.tenantId = tenantId;
        h.staffId = staffId;
        h.role = role;
        h.scope = scope;
    }

    public static Holder current() {
        return CTX.get();
    }

    public static String tenantId() {
        return CTX.get().tenantId;
    }

    public static String staffId() {
        return CTX.get().staffId;
    }

    public static String role() {
        return CTX.get().role;
    }

    public static String scope() {
        return CTX.get().scope;
    }

    public static boolean isSet() {
        return CTX.get().tenantId != null;
    }

    /** 请求结束务必清理, 避免线程复用串租户 */
    public static void clear() {
        CTX.remove();
    }
}
