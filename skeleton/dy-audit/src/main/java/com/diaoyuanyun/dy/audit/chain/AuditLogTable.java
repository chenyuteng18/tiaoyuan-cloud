package com.diaoyuanyun.dy.audit.chain;

/**
 * {@code audit_log} 表结构的<b>单一定义</b>（列名 / 类型 / 约束）。
 *
 * <h2>为什么把它单独抽出来</h2>
 * 同一张表在三处被需要：数据库迁移（{@code db/audit_log.sql} 作为人读的 DDL 文档）、
 * 生产代码的幂等建表（可独立起测）、测试 Harness 的建表。若三处各抄一份，
 * 迟早会出现"测试建的表和生产建的表不是同一张"——那时所有链断言都成了对着一张假表的结论，
 * 而这种偏差不会以任何红点的形式暴露。故三处引用同一个常量，
 * 并由 {@code AuditLogTableContractTest} 逐列比对本常量与交付物 DDL 文件。
 *
 * <p>列序与 {@code audit_log.sql} 一致；{@code payload} 允许为空（未采到详情）；
 * {@code prev_hash} / {@code hash} 是 {@code CHAR(64)}（SHA-256 的 64 位 hex），
 * 均 {@code NOT NULL} —— 链字段可空会让"忘了算"和"值恰好为空"不可区分。
 */
public final class AuditLogTable {

    /** 表名（校验器与 Harness 都引用此处，避免字符串漂移）。 */
    public static final String NAME = "audit_log";

    /** 链哈希字段宽（与 {@link AuditChainHash#HASH_HEX_LENGTH} 同源含义）。 */
    public static final int HASH_COLUMN_LENGTH = 64;

    /**
     * 幂等建表语句。与 {@code dy-audit/src/main/resources/db/audit_log.sql} 的
     * {@code CREATE TABLE audit_log} 块逐列等价（由契约测试守护）。
     */
    public static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS audit_log
            (
                id          UUID PRIMARY KEY,
                tenant_id   UUID          NOT NULL,
                actor       VARCHAR(128)  NOT NULL,
                action      VARCHAR(64)   NOT NULL,
                target_type VARCHAR(64)   NOT NULL,
                target_id   VARCHAR(128)  NOT NULL,
                payload     TEXT,
                prev_hash   CHAR(64)      NOT NULL,
                hash        CHAR(64)      NOT NULL,
                created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
            )
            """;

    /** 撤销应用侧写权限的清单。{@code TRUNCATE} 必须在列 —— 理由见 {@code db/audit_log.sql}。 */
    public static final String REVOKE_WRITES_SQL_TEMPLATE =
            "REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM %s";

    private AuditLogTable() {
    }
}