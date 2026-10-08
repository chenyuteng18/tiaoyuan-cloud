package com.diaoyuanyun.dy.app.identity.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code auth_credential} 凭证台账（V23 迁移建表）—— 登录解算链的唯一读取方。
 *
 * <h2>🛑 本类不设租户上下文、不写 SET LOCAL —— 与其它仓储<b>刻意相反</b></h2>
 * 其它仓储（如 {@code StoreRepository}）每次操作开短事务并 {@code SET LOCAL app.tenant_id}，
 * 依赖 RLS 承担行级隔离；本表（V23）**结构性豁免 RLS**（登录先于租户上下文存在，
 * 加策略即全量 401 —— 理由逐字见 V23 迁移第二节与 {@code RlsCoverageGateTest}
 * 的 NON_TENANT_TABLES 注释）。隔离由两条替代边界承担：
 * <ol>
 *   <li>account 全局唯一 ⇒ 本类<b>只提供</b>"按 account 定点读一行"这一条读路径 ——
 *       不存在"按租户读一批"的方法，租户批量暴露面在接口层即不存在；</li>
 *   <li>出站列显式白名单：{@code credential_hash} 仅供服务层做比对读入，
 *       <b>永不</b>出现在任何面向上层的出站 Map / DTO 里。</li>
 * </ol>
 *
 * <h2>🛑 为什么出站走窄记录而不是 Map</h2>
 * {@link CredentialRow} 只承载登录解算所需的最小字段集。
 * 若出站走 {@code Map<String,Object>}，任何后续维护者往 SELECT 里多加一列
 * （例如把 hash 一并带出再随手塞进响应）都不会有任何编译期/测试信号。
 * 窄记录把"能带出去的字段"物理钉死。
 */
@Repository
public class AuthCredentialLedger {

    /** 查询列 —— 含 credential_hash（比对必需）。出站转 {@link CredentialRow} 时裁剪。 */
    private static final String FIND_SQL =
            "SELECT credential_id, tenant_id, role, store_id, ref_kind, ref_id, status, credential_hash "
                    + "FROM auth_credential WHERE account = ?";

    private final JdbcTemplate jdbc;

    public AuthCredentialLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 account 定点读取一条有效凭证。
     *
     * <p>status != active 的行<b>按不存在处理</b>（返回 empty）：
     * "被停用的账号"与"不存在的账号"对调用方必须不可区分 ——
     * 区分即等于向攻击者广播"这个账号存在，只是被停了"。
     */
    public Optional<CredentialRow> findActiveByAccount(String account) {
        List<CredentialRow> rows = jdbc.query(FIND_SQL, (rs, i) -> new CredentialRow(
                UUID.fromString(rs.getString("credential_id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("role"),
                rs.getString("store_id") == null ? null : UUID.fromString(rs.getString("store_id")),
                rs.getString("ref_kind"),
                rs.getString("ref_id") == null ? null : UUID.fromString(rs.getString("ref_id")),
                rs.getString("status"),
                rs.getString("credential_hash")), account);
        if (rows.size() > 1) {
            // account 全局唯一索引在库层兜底；防御性冗余：一旦唯一性被绕过（索引被删），
            // 绝不"取第一行"——那等于让攻击者插入抢先行接管任意账号。
            throw new IllegalStateException("auth_credential.account 唯一性被破坏: " + rows.size() + " 行");
        }
        return rows.stream()
                .filter(r -> "active".equals(r.status()))
                .findFirst();
    }

    /**
     * 登录解算所需的最小凭证记录。
     *
     * <p>🛑 {@code credentialHash} 字段<b>只</b>被 {@code LoginService} 读去做比对，
     * 不得进入任何日志、响应、审计 payload（契约信封四字段之外不下发任何东西）。
     */
    public record CredentialRow(
            UUID credentialId,
            UUID tenantId,
            String role,
            UUID storeId,
            String refKind,
            UUID refId,
            String status,
            String credentialHash) {
    }
}
