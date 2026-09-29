package com.diaoyuanyun.dy.app.rls;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RlsMigrationScriptTest: 校验 Flyway 基线脚本满足 fail-closed (ADR-02 第 2 层)。
 *
 * <p>H2 不支持 RLS, 故不连真实 PG; 直接断言脚本文本包含强制要素:
 * ENABLE + FORCE ROW LEVEL SECURITY, 以及 NULLIF(...) 的 fail-closed 策略。
 */
class RlsMigrationScriptTest {

    private String loadScript() throws IOException {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V1__baseline_tenant_rls.sql")) {
            assertTrue(is != null, "V1 迁移脚本必须存在于 classpath:db/migration");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void script_enables_and_forces_rls() throws IOException {
        String sql = loadScript();
        assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"),
                "必须 ENABLE ROW LEVEL SECURITY");
        assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"),
                "必须 FORCE ROW LEVEL SECURITY (防表 owner 绕过)");
    }

    @Test
    void script_uses_fail_closed_nullif_policy() throws IOException {
        String sql = loadScript();
        assertTrue(sql.contains("tenant_isolation"),
                "必须存在 tenant_isolation 策略");
        assertTrue(sql.contains("NULLIF"),
                "必须为 fail-closed: NULLIF(current_setting('app.tenant_id', true), '')");
        assertTrue(sql.contains("current_setting('app.tenant_id', true)"),
                "未设置上下文时应返回 NULL -> 比较恒为 NULL -> 零行");
    }

    /**
     * 策略必须同时声明 USING 与 WITH CHECK。
     *
     * <p>PG 语义下 {@code FOR ALL} 策略省略 {@code WITH CHECK} 会默认沿用 {@code USING},
     * 故缺失它<b>不构成漏洞</b>; 但一旦策略被拆成 {@code FOR SELECT} + {@code FOR INSERT},
     * 或被人误改, <b>写入侧校验会静默消失</b> (不报错, 只让跨租户写入成为可能)。
     * 这里做文本断言是恰当的: 该要素确实只在文本层面存在, 缺失时没有运行时信号。
     */
    @Test
    void script_declares_both_using_and_with_check() throws IOException {
        String sql = loadScript();
        assertTrue(sql.contains("USING"), "策略必须声明 USING (读取侧)");
        assertTrue(sql.contains("WITH CHECK"),
                "策略必须显式声明 WITH CHECK (写入侧); 否则拆分策略时写入校验会静默消失");
    }

    /** fail-closed 的唯一允许语义是"未设上下文=零行"; 不得出现任何默认租户兜底。 */
    @Test
    void script_must_not_contain_tenant_default_fallback() throws IOException {
        String sql = loadScript();
        // 去掉注释行后再断言, 避免误伤解释性文字
        String code = sql.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(code.contains("COALESCE(current_setting"),
                "禁止用 COALESCE 给 tenant_id 兜底默认租户 (fail-open 入口)");
        assertFalse(code.toUpperCase().contains("USING (TRUE)"),
                "禁止 USING (true) (等于不隔离)");
    }

    @Test
    void script_must_not_use_plain_set_for_tenant_context() throws IOException {
        String sql = loadScript();
        // 强制使用 SET LOCAL 而非 SET (ADR-02); 脚本中不应出现裸 SET (租户变量设置由 RlsSessionAspect 在事务内执行)
        assertFalse(sql.toLowerCase().contains("\nset "),
                "基线脚本不应含裸 SET 租户变量语句 (SET LOCAL 由切面在事务内执行)");
    }

    @Test
    void script_has_tenant_table_and_demo_business_table() throws IOException {
        String sql = loadScript();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS tenant"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS customer"));
        assertTrue(sql.contains("tenant_id"));
    }
}
