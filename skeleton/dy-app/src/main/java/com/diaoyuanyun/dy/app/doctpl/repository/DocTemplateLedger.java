package com.diaoyuanyun.dy.app.doctpl.repository;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 文书模板（契约域 I · doc_template）的 JDBC 仓储（V5 建表）。
 *
 * <h2>RLS 是隔离的唯一防线（不写 WHERE tenant_id）</h2>
 * 与既有各仓储同款：隔离全由 V5 的 {@code FORCE RLS} 承担。
 *
 * <h2>🛑 版本不可覆盖 + 发布独占</h2>
 * 唯一键 {@code uq_doc_template_tenant_type_version} 兜底"同 doc_type 下版本不重复"；
 * 部分唯一索引 {@code uq_doc_template_active}（WHERE is_active）兜底"同 doc_type 唯一活跃版本"。
 * 故"发布" = 事务内先置同 doc_type 其他版本 is_active=false，再置目标版本 true。
 */
@Repository
public class DocTemplateLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DocTemplateLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static final String INSERT_SQL =
            "INSERT INTO doc_template ("
                    + " template_id, tenant_id, doc_type, title, content, version, is_active,"
                    + " source_type, file_ref, file_name, mime_type, file_size, file_hash,"
                    + " placeholder_schema, change_reason, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    public void insert(String tenantId, DocTemplateRow row) {
        inTenant(tenantId, () -> jdbc.update(INSERT_SQL,
                row.templateId().toString(),
                row.docType(),
                row.title(),
                row.content(),
                row.version(),
                row.isActive(),
                row.sourceType(),
                row.fileRef(),
                row.fileName(),
                row.mimeType(),
                row.fileSize(),
                row.fileHash(),
                row.placeholderSchema(),
                row.changeReason(),
                row.createdBy()));
    }

    /** 全列投影（B-4 新增方法共用，避免"每个查询各列一次列名"的漂移）。 */
    private static final String SELECT_COLUMNS =
            "SELECT template_id, doc_type, title, content, version, is_active, source_type,"
                    + " file_ref, file_name, mime_type, file_size, file_hash, placeholder_schema,"
                    + " change_reason, created_at, created_by FROM doc_template";

    /** I1 列表（按 doc_type + is_active 过滤）。 */
    public List<DocTemplateRow> list(String tenantId, String docType, Boolean isActive) {
        requireUuid(tenantId);
        StringBuilder sql = new StringBuilder(
                "SELECT template_id, doc_type, title, content, version, is_active, source_type,"
                        + " file_ref, file_name, mime_type, file_size, file_hash, placeholder_schema,"
                        + " change_reason, created_at, created_by FROM doc_template WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (docType != null) {
            sql.append(" AND doc_type = ?");
            args.add(docType);
        }
        if (isActive != null) {
            sql.append(" AND is_active = ?");
            args.add(isActive);
        }
        sql.append(" ORDER BY doc_type, version DESC");
        return inTenant(tenantId, () -> jdbc.query(sql.toString(),
                (rs, i) -> map(rs), args.toArray()));
    }

    /** I5 版本列表（按 doc_type —— 版本链归属 = doc_type，见类注释唯一键语义）。 */
    public List<DocTemplateRow> listVersions(String tenantId, UUID templateId) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            // 先反查该 template_id 的 doc_type（版本链归属键）
            List<String> types = jdbc.query(
                    "SELECT doc_type FROM doc_template WHERE template_id = ?::uuid",
                    (rs, i) -> rs.getString("doc_type"), templateId.toString());
            if (types.isEmpty()) {
                return java.util.List.<DocTemplateRow>of();
            }
            String docType = types.get(0);
            return jdbc.query(
                    "SELECT template_id, doc_type, title, content, version, is_active, source_type,"
                            + " file_ref, file_name, mime_type, file_size, file_hash, placeholder_schema,"
                            + " change_reason, created_at, created_by FROM doc_template"
                            + " WHERE doc_type = ? ORDER BY version ASC",
                    (rs, i) -> map(rs), docType);
        });
    }

    /** 取指定 doc_type 的当前最大版本号（I4 新版本 = max+1）。 */
    public int currentVersionByDocType(String tenantId, String docType) {
        requireUuid(tenantId);
        return inTenant(tenantId, () -> {
            Integer v = jdbc.queryForObject(
                    "SELECT coalesce(max(version), 0) FROM doc_template WHERE doc_type = ?",
                    Integer.class, docType);
            return v == null ? 0 : v;
        });
    }

    /** I6 发布：同 doc_type 其他版本置 false，目标版本置 true（单事务）。 */
    public void publish(String tenantId, UUID templateId, int version) {
        inTenant(tenantId, () -> {
            // 找到目标版本的 doc_type（发布动作的"同 doc_type"边界依据）
            String docType = jdbc.queryForObject(
                    "SELECT doc_type FROM doc_template WHERE template_id = ?::uuid AND version = ?",
                    String.class, templateId.toString(), version);
            // 同 doc_type 下所有版本置 false（含目标版本），再单独置目标为 true ——
            // 保证"同 doc_type 唯一活跃版本"（部分唯一索引 uq_doc_template_active）
            jdbc.update("UPDATE doc_template SET is_active = false WHERE doc_type = ?",
                    docType);
            jdbc.update("UPDATE doc_template SET is_active = true"
                    + " WHERE template_id = ?::uuid AND version = ?",
                    templateId.toString(), version);
            return null;
        });
    }

    // ==================================================================
    // B-4 追加（文件通道 I3/I7 的存储侧支撑）—— 2026-09-26
    // ==================================================================

    /**
     * I3 幂等判定：同 {@code (tenant_id, doc_type, file_hash)} 是否已有版本。
     *
     * <p>🛑 判定走 {@code doc_template} 自身，而<b>不</b>另立一张幂等表：
     * 「同 hash 已上传」这件事本身就是本表的持久事实（{@code file_hash} 列），
     * 另立副本会多出一处<b>可能失同步</b>的状态（副本被清、主表还在 ⇒ 幂等失效）。
     * 这与 B-2 的处置<b>恰好相反</b>（B-2 必须另立幂等表，因为它没有这样一个天然承载列）——
     * 差异写在这里，以免后人以为两处不一致是疏漏。
     */
    public java.util.Optional<DocTemplateRow> findByDocTypeAndHash(String tenantId, String docType,
                                                                   String fileHash) {
        requireUuid(tenantId);
        if (docType == null || fileHash == null) {
            return java.util.Optional.empty();
        }
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_COLUMNS + " WHERE doc_type = ? AND file_hash = ? ORDER BY version DESC LIMIT 1",
                (rs, i) -> map(rs), docType, fileHash).stream().findFirst());
    }

    /**
     * I7 定位指定 {@code (template_id, version)} 行（下载用）。
     *
     * <p>🛑 下载<b>不</b>复用 {@link #listVersions}：后者按 doc_type 链返回全链，下载只需一行。
     * 且 I7 入参是 {@code template_id}（每版本一行的主键），按主键直查才与
     * "template_id 是行主键" 这一语义一致（见 {@code DocTemplateService.newVersion} 的说明）。
     */
    public java.util.Optional<DocTemplateRow> findVersionRow(String tenantId, UUID templateId,
                                                             int version) {
        requireUuid(tenantId);
        if (templateId == null || version < 1) {
            return java.util.Optional.empty();
        }
        return inTenant(tenantId, () -> jdbc.query(
                SELECT_COLUMNS + " WHERE template_id = ?::uuid AND version = ?",
                (rs, i) -> map(rs), templateId.toString(), version).stream().findFirst());
    }

    /** 取指定版本行的 file_ref（对账用：孤儿对象回收按登记反查）。 */
    public java.util.Optional<String> fileRefOf(String tenantId, UUID templateId, int version) {
        return findVersionRow(tenantId, templateId, version).map(DocTemplateRow::fileRef);
    }

    private static DocTemplateRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocTemplateRow(
                rs.getObject("template_id", UUID.class),
                rs.getString("doc_type"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getInt("version"),
                rs.getBoolean("is_active"),
                rs.getString("source_type"),
                rs.getString("file_ref"),
                rs.getString("file_name"),
                rs.getString("mime_type"),
                rs.getObject("file_size", Long.class),
                rs.getString("file_hash"),
                rs.getString("placeholder_schema"),
                rs.getString("change_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"));
    }

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    private static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户/标识非合法 UUID，拒绝执行文书模板 SQL");
        }
    }

    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }
}