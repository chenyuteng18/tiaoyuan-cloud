package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.repository.AuthCredentialLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 凭证开通服务 —— {@code auth_credential} 的<b>唯一生产写入方</b>
 * （V23 建表；ProvisioningBoundaryGateTest 的 PROVISIONED 账登记依据）。
 *
 * <h2>为什么它是"服务通路"而不是 HTTP 端点（与 V21/V22 同形态）</h2>
 * 「开通账号」不在契约 45 操作内（45 = 42 已实现 + 3 出范围，见 EndpointCoverageLedgerTest）。
 * 新增端点属契约变更，必须走契约冻结流程，不由本轮代拍。故本类定位为
 * <b>运维/服务通路</b>：被内部调用方（未来的账号管理端点 / 开通脚本）组合使用。
 * 🛑 它<b>不是</b>占位 —— 写入路径真实可用（AuthLoginE2ETest 经它建凭证），
 * 与"迁移建了表但生产零写入方"的 NOT_PROVISIONED 形态有本质区别。
 *
 * <h2>安全边界（四条）</h2>
 * <ol>
 *   <li><b>明文口令永不落库、永不入日志</b> —— 入参明文仅存活于
 *       {@link PasswordHasher#hash} 一次调用；日志只含 account 与 credentialId；</li>
 *   <li><b>account 冲突显式判定</b> —— 用 {@code ON CONFLICT (account) DO NOTHING}
 *       + 受影响行数判定，而非"先查后插"（两步之间存在竞态窗口）：
 *       0 行 = account 已被占用 ⇒ 返回 {@code ALREADY_EXISTS}，绝不静默成功
 *       （"返回值不许撒谎"，V18 教训）；</li>
 *   <li><b>租户必须真实存在</b> —— tenant 是宿主表（无 RLS），插入前显式查存在性，
 *       拒绝给不存在的租户发凭证（fail-closed：不校验就会产出"登录即 404 租户"的死票）；</li>
 *   <li><b>role 白名单来自 V23 CHECK</b> —— 违反枚举由库层 CHECK 拒绝，
 *       应用层不复制一份枚举清单（两份清单必然漂移）。</li>
 * </ol>
 */
@Service
public class CredentialProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(CredentialProvisioningService.class);

    private static final String INSERT_SQL =
            "INSERT INTO auth_credential "
                    + "(credential_id, account, credential_hash, tenant_id, role, ref_kind, ref_id, store_id, status) "
                    + "VALUES (?::uuid, ?, ?, ?::uuid, ?, ?, ?::uuid, ?::uuid, 'active') "
                    + "ON CONFLICT (account) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final PasswordHasher hasher;

    public CredentialProvisioningService(JdbcTemplate jdbc, PasswordHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    /** 开通结果 —— CREATED / ALREADY_EXISTS 必须显式区分（返回值不许撒谎）。 */
    public enum Outcome { CREATED, ALREADY_EXISTS }

    /**
     * 开通一条登录凭证。
     *
     * @param tenantId        租户（必须真实存在）
     * @param account         登录账号（全局唯一 —— V23 唯一索引承担最终裁决）
     * @param role            token 角色码（client/therapist/meridian/manager/area/hq，V23 CHECK 兜底）
     * @param initialPassword 初始口令（明文仅存活于本方法内一次哈希调用）
     * @param refKind         staff / customer（可空 —— 匿名客户凭证）
     * @param refId           关联实体 id（与 refKind 成对，V23 CHECK 约束成对性）
     * @return {@code CREATED}（新开通）/ {@code ALREADY_EXISTS}（账号已占用 —— 调用方自行决定提示口径）
     * @throws BizException 1001/400 参数缺失；9002/500 租户不存在（内部通路调用方是运维面，不给细粒度 4xx）
     */
    public Outcome createCredential(String tenantId, String account, String role,
                                    String initialPassword, String refKind, String refId) {
        if (tenantId == null || tenantId.isBlank()
                || account == null || account.isBlank()
                || role == null || role.isBlank()
                || initialPassword == null || initialPassword.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "tenantId/account/role/initialPassword 均为必填");
        }
        Integer tenant = jdbc.queryForObject(
                "SELECT count(*) FROM tenant WHERE id = ?::uuid", Integer.class, tenantId);
        if (tenant == null || tenant == 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "租户不存在: " + tenantId + " —— 拒绝给不存在的租户发凭证");
        }

        String credentialId = UUID.randomUUID().toString();
        String hash = hasher.hash(initialPassword);
        int rows = jdbc.update(INSERT_SQL, credentialId, account, hash,
                tenantId, role, refKind, refId, null);

        if (rows == 0) {
            log.info("凭证开通: account={} 判定 ALREADY_EXISTS（account 已占用）", account);
            return Outcome.ALREADY_EXISTS;
        }
        log.info("凭证开通: account={} credentialId={} (明文口令不入日志)", account, credentialId);
        return Outcome.CREATED;
    }
}
