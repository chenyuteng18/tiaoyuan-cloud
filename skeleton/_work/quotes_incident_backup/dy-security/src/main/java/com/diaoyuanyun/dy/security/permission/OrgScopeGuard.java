package com.diaoyuanyun.dy.security.permission;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;
import org.springframework.stereotype.Component;

/**
 * 层级越权守卫（S1-3 验收②）。
 *
 * <p>把"请求方层级是否不低于所需层级"这一句话收敛成<b>一个纯逻辑判定</b>，
 * 不碰 servlet、不碰 ThreadLocal —— 便于直接单测，也便于被过滤器 / 切面 / 服务层复用。
 *
 * <h2>两种拒绝，语义不同，不得混用</h2>
 * <table border="1">
 *   <caption>拒绝类型</caption>
 *   <tr><th>情形</th><th>错误码</th><th>HTTP</th><th>理由</th></tr>
 *   <tr>
 *     <td>角色可识别，但层级<b>不够</b>（如门店层请求总部专属资源）</td>
 *     <td>{@link ErrorCode#VISIBILITY_DENIED} 2001</td><td>403</td>
 *     <td>属"档位不足"，与契约 §2.0「可见性档位不足统一 403」一致</td>
 *   </tr>
 *   <tr>
 *     <td>角色<b>无法识别</b>（未登记 / null / 客户角色）</td>
 *     <td>{@link ErrorCode#TENANT_MISMATCH} 2003</td><td>403</td>
 *     <td>属"身份上下文不可信"，{@link OrgLevel#fromRole} 的 fail-closed 结论</td>
 *   </tr>
 * </table>
 * <b>为什么两者必须分开</b>：都是 403，但排查路径完全不同 ——
 * 2001 说明"这个人权限位不够"（业务/配置预期内），2003 说明"这个人是谁我们都没认出来"
 * （契约/配置出了错）。合并成一个码会让运维把"角色码拼错"当成"权限没配够"来查，
 * 方向一开始就是错的。
 */
@Component
public class OrgScopeGuard {

    /**
     * 断言请求方层级覆盖所需层级。
     *
     * @param role        当前请求的角色码（来自 token，经 {@code TenantContext}）
     * @param requiredMin 端点声明的最低层级（{@code null} 视为"未声明"，直接放行 —— 见下）
     * @return 实际层级（供调用方写诊断信息 / 记日志用，不参与判定）
     * @throws BizException 层级不足（2001）或角色不可识别（2003）
     */
    public OrgLevel assertCovers(String role, OrgLevel requiredMin) {
        // 端点未声明所需层级 = 本机制不适用于该端点（例如公开端点 / 仅本人资源）。
        // 此处不抛错：**声明缺失由"注解覆盖率"这类静态门禁去管**，不该在运行时把
        // 每个未标注的端点都判成越权 —— 那会把"忘了标注"变成"全站 403"，更糟。
        if (requiredMin == null) {
            throw new IllegalArgumentException(
                    "assertCovers 不接受 requiredMin == null：调用方应先判空，"
                            + "或用 coversOrNull 做可选校验");
        }

        // 角色不可识别 -> 2003（fail-closed，绝不回落层级）
        OrgLevel actual = OrgLevel.fromRole(role);

        if (!actual.covers(requiredMin)) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "组织层级不足: 当前 " + actual.getLabel() + "(" + actual.getCode()
                            + ") 低于所需 " + requiredMin.getLabel() + "(" + requiredMin.getCode() + ")");
        }
        return actual;
    }

    /**
     * 可选校验：{@code requiredMin} 为 {@code null} 时返回 {@code Optional.empty()} 且不抛错。
     *
     * <p>供"未声明即跳过"的调用点使用，让"我要跳过"与"我忘了判空"在代码里可区分。
     */
    public java.util.Optional<OrgLevel> coversOrNull(String role, OrgLevel requiredMin) {
        if (requiredMin == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(assertCovers(role, requiredMin));
    }

    /**
     * 纯判定（不抛错），供静态门禁与文档一致性断言复用。
     *
     * @return 层级达标返回 {@code true}；角色不可识别或层级不足返回 {@code false}
     */
    public boolean covers(String role, OrgLevel requiredMin) {
        if (requiredMin == null) {
            return true;
        }
        try {
            return OrgLevel.fromRole(role).covers(requiredMin);
        } catch (TenantMismatchException e) {
            return false;
        }
    }
}