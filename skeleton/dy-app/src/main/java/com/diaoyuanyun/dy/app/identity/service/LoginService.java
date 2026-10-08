package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.repository.AuthCredentialLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.jwt.JwtIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * A1 {@code POST /auth/login} —— 签发 token（契约域 A 第 1 行）。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   A1 POST /auth/login   x-callable-roles: [client, therapist, meridian, admin]
 *                         x-client-forbidden: false
 *   请求: {account, credential, client_end}  account=员工号/客户 openid 绑定号
 *         credential=密码/短信码             client_end ∈ {mp, app, web}
 *   200 → LoginData {token, expires_in, role, client_end}
 *   400 ValidationFailed / 401 Unauthenticated / 403 TenantMismatch
 * </pre>
 *
 * <h2>🛑 与 A2 镜像的三层防线形态：本端点不贴任何注解</h2>
 * <ul>
 *   <li><b>不贴 {@code @RequirePermission}</b>：登录发生在鉴权之前 ——
 *       请求方还没有 token，任何权限码都无从校验；
 *       {@code PermissionRegistry} 的登记面是"已认证者可调什么"，不含"如何成为已认证者"。</li>
 *   <li><b>不贴 {@code @StaffOnly}</b>：契约 {@code x-client-forbidden: false}，
 *       客户（mp 端）就是本端点的第一调用方。</li>
 *   <li><b>过滤器放行</b>：无 Authorization 头的请求被 {@code TenantContextFilter}
 *       以匿名放行（不建上下文）—— 登录请求天然无 token，直达本服务。
 *       带 token 打登录端点也是合法请求（token 会被解析建上下文，但本服务不读上下文，
 *       一律按请求体独立解算 —— 避免"用旧身份签新票"的上下文继承歧义）。</li>
 * </ul>
 *
 * <h2>🛑 账号枚举防护：失败只有一种说法</h2>
 * "账号不存在"与"口令不匹配"与"凭证被停用"——三种内部原因，
 * 对外<b>只有一种</b> 401（{@link ErrorCode#UNAUTHENTICATED}，同一 message）。
 * 区分任何一种，都等于向攻击者广播"这个账号存在"。
 * 内部原因只进日志（不含口令、不含哈希），且用同一 trace_id 可回溯。
 *
 * <h2>scope 口径（为什么登录签发 scope=null）</h2>
 * token scope 与组织范围是"两来源冲突即拒"的体系（{@code StoreScopeResolver}），
 * 登录时组织范围以角色默认值为准（{@code resolve} 对 null scope 走 ROLE_DEFAULT 分支，
 * 是既有链路的显式合法输入）。若把登录时点推算的门店范围硬写进 token，
 * 员工调店后 token 里的旧范围会与组织新范围"冲突即拒"——
 * 把"调店后重新登录"变成硬故障。故<b>范围动态性归组织体系，token 不承载快照</b>。
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    /** 契约 client_end 枚举（逐字）。 */
    private static final Set<String> CLIENT_ENDS = Set.of("mp", "app", "web");

    /** 统一 401 文案 —— 三种内部失败原因共用（账号枚举防护，见类注释）。 */
    private static final String AUTH_FAIL_MESSAGE = "账号或凭证不匹配";

    private final AuthCredentialLedger ledger;
    private final PasswordHasher hasher;
    private final JwtIssuer issuer;

    public LoginService(AuthCredentialLedger ledger, PasswordHasher hasher, JwtIssuer issuer) {
        this.ledger = ledger;
        this.hasher = hasher;
        this.issuer = issuer;
    }

    /**
     * 登录解算：凭证比对 → 签发 token → 契约 LoginData。
     *
     * @return 契约 LoginData 的 Map 形态（token / expires_in / role / client_end，四键不多不少）
     * @throws BizException 1001/400 参数不合法；1002/401 凭证不匹配（统一文案）
     */
    public Map<String, Object> login(String account, String credential, String clientEnd) {
        // ---------- 1) 参数校验（400 段）----------
        if (account == null || account.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "account 不得为空");
        }
        if (credential == null || credential.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "credential 不得为空");
        }
        if (clientEnd == null || !CLIENT_ENDS.contains(clientEnd)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "client_end 必须是 mp/app/web 之一");
        }
        if (account.length() > 128) {
            // 与 V23 列宽一致；超长输入在查询前拒绝（不给无界输入进台账的机会）
            throw new BizException(ErrorCode.VALIDATION_FAILED, "account 超长");
        }

        // ---------- 2) 定点读凭证（empty ⇒ 401，见类注释"账号枚举防护"）----------
        AuthCredentialLedger.CredentialRow row =
                ledger.findActiveByAccount(account).orElse(null);
        if (row == null) {
            log.info("登录失败: 账号不存在或已停用 (trace 可回溯)");
            throw new BizException(ErrorCode.UNAUTHENTICATED, AUTH_FAIL_MESSAGE);
        }

        // ---------- 3) 口令比对（常量时间；失败 ⇒ 401 同文案）----------
        if (!hasher.matches(credential, row.credentialHash())) {
            log.info("登录失败: 凭证比对未通过 account={} credentialId={}", account, row.credentialId());
            throw new BizException(ErrorCode.UNAUTHENTICATED, AUTH_FAIL_MESSAGE);
        }

        // ---------- 4) 签发（staffId = ref 弱关联的实体 id；客户匿名可空）----------
        String staffId = row.refId() == null ? null : row.refId().toString();
        JwtIssuer.SignedToken signed =
                issuer.sign(row.tenantId().toString(), staffId, row.role(), null);

        // ---------- 5) 契约 LoginData：四键，不多不少 ----------
        return Map.of(
                "token", signed.token(),
                "expires_in", signed.expiresIn(),
                "role", row.role(),
                "client_end", clientEnd);
    }
}
