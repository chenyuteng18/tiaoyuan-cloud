package com.diaoyuanyun.dy.security.permission;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三级权限模型 · 单元测试（S1-3 验收②的规则层）。
 *
 * <h2>断言锚定契约与 PRD 原文</h2>
 * 角色码与层级对应关系逐条比对<b>冻结契约 §2.1 A1/A2</b>；层级包含关系比对
 * <b>PRD §2.2</b>（{@code super_admin ⊃ 总部运营}）。不是在断言"实现返回什么"。
 *
 * <h2>为什么这层单测不能替代端到端测试</h2>
 * 本类证明<b>规则对</b>；端到端（{@code TierAuthorizationE2ETest}）证明<b>规则被挂上了</b>。
 * 两者是两件事，都要有。
 */
class OrgLevelTest {

    private final OrgScopeGuard guard = new OrgScopeGuard();

    // ------------------------------------------------------------------ 角色→层级（对齐契约 A1）

    @Test
    @DisplayName("契约 A1 的六个角色码全部可解析 —— 不得漏登记任何一个")
    void all_contract_roles_are_registered() {
        // 契约 §2.1 A1 原文: 客户=client / 调理师=therapist / 经络师=meridian
        //                     门店负责人=manager / 区域督导=area / 总部运营=hq
        assertEquals(OrgLevel.HEADQUARTERS, OrgLevel.fromRole("hq"));
        assertEquals(OrgLevel.REGION, OrgLevel.fromRole("area"));
        assertEquals(OrgLevel.STORE, OrgLevel.fromRole("manager"));
        assertEquals(OrgLevel.STORE, OrgLevel.fromRole("meridian"));
        assertEquals(OrgLevel.STORE, OrgLevel.fromRole("therapist"));
        // client 不是组织层级（客户无 staff_id）→ 必须抛错，见下一个用例
    }

    @Test
    @DisplayName("客户(client)不是组织层级 —— 必须抛错, 而不是塞进门店层")
    void customer_is_not_an_org_level() {
        // 契约 A1: 客户无 staff_id，不占组织位置。"仅本人"是独立机制，不是某个层级。
        assertThrows(TenantMismatchException.class, () -> OrgLevel.fromRole("client"),
                "把 client 归到门店层会让『客户只能看自己』这条约束失去独立表达");
        assertEquals(java.util.Optional.empty(), OrgLevel.tryFromRole("client"),
                "宽容解析应返回空，而非硬塞一个层级");
    }

    @Test
    @DisplayName("未登记角色 fail-closed —— 绝不回落成某个层级")
    void unregistered_role_fails_closed() {
        for (String bogus : new String[]{null, "", "HQ_TYPO", "hq ", "HQ", "unknown“}) {
            assertThrows(TenantMismatchException.class, () -> OrgLevel.fromRole(bogus),
                    ”未登记角色 '" + bogus + "' 必须抛错 —— 回落会把配置错误变成静默的权限收窄");
        }
    }

    @Test
    @DisplayName("遗留大写角色码是【显式登记】的别名, 不是靠通配兜底")
    void legacy_uppercase_codes_are_explicitly_registered() {
        // 骨架早期代码用大写码；契约用小写码。两种并存是真实分叉，故两者都显式登记（见类注释与 T-11）。
        assertEquals(OrgLevel.HEADQUARTERS, OrgLevel.fromRole("SUPER_ADMIN"));
        assertEquals(OrgLevel.HEADQUARTERS, OrgLevel.fromRole("TENANT_ADMIN"));
        assertEquals(OrgLevel.REGION, OrgLevel.fromRole("REGION_ADMIN"));
        assertEquals(OrgLevel.STORE, OrgLevel.fromRole("STORE_STAFF"));

        // 但 GUEST 刻意不在册（未认证者不是组织层级）
        assertFalse(OrgLevel.tryFromRole("GUEST").isPresent(),
                "GUEST 不是组织层级，不得为它编造一个层级");
    }

    @Test
    @DisplayName("登记表大小写敏感 —— 不做不敏感归一（那会让分叉在运行时隐形）")
    void role_matching_is_case_sensitive() {
        assertThrows(TenantMismatchException.class, () -> OrgLevel.fromRole("HQ"),
                "大写 HQ 与契约小写 hq 不是同一个码 —— 静默归一等于把分叉藏起来");
        assertThrows(TenantMismatchException.class, () -> OrgLevel.fromRole("Area"));
    }

    // ------------------------------------------------------------------ 层级包含（对齐 PRD §2.2）

    @Test
    @DisplayName("层级包含: 总部 ⊃ 区域 ⊃ 门店, 反之不成立")
    void hierarchy_is_asymmetric() {
        assertTrue(OrgLevel.HEADQUARTERS.covers(OrgLevel.REGION), "总部覆盖区域");
        assertTrue(OrgLevel.HEADQUARTERS.covers(OrgLevel.STORE), "总部覆盖门店");
        assertTrue(OrgLevel.REGION.covers(OrgLevel.STORE), "区域覆盖门店");

        assertFalse(OrgLevel.STORE.covers(OrgLevel.REGION), "门店不得覆盖区域");
        assertFalse(OrgLevel.STORE.covers(OrgLevel.HEADQUARTERS), "门店不得覆盖总部");
        assertFalse(OrgLevel.REGION.covers(OrgLevel.HEADQUARTERS), "区域不得覆盖总部");

        // 同级覆盖自身
        for (OrgLevel l : OrgLevel.values()) {
            assertTrue(l.covers(l), l + " 应覆盖自身");
        }
        assertFalse(OrgLevel.HEADQUARTERS.covers(null), "null 不得被覆盖");
    }

    // ------------------------------------------------------------------ 默认行级范围（对齐契约 A2）

    @Test
    @DisplayName("三级 ↔ 行级范围映射逐条对齐契约 A2 —— 不得自拟")
    void row_scope_mapping_matches_contract_a2() {
        // 契约 A2 原文: 门店负责人 own_store / 区域督导 region / 总部 all
        assertEquals(RowScope.OWN_STORE, OrgLevel.STORE.defaultRowScope());
        assertEquals(RowScope.REGION, OrgLevel.REGION.defaultRowScope());
        assertEquals(RowScope.ALL, OrgLevel.HEADQUARTERS.defaultRowScope());
        // 不得出现"门店层竟然拿到 all"这类漏配
        assertEquals(3, Set.of(OrgLevel.values()).size());
    }

    // ------------------------------------------------------------------ 拒绝语义（2001 vs 2003）

    @Test
    @DisplayName("层级不足 -> 2001 VISIBILITY_DENIED；角色不可识别 -> 2003 TENANT_MISMATCH")
    void denial_codes_distinguish_insufficient_level_from_unknown_identity() {
        // 角色可识别但层级不够
        BizException denied = assertThrows(BizException.class,
                () -> guard.assertCovers("manager", OrgLevel.HEADQUARTERS));
        assertEquals(ErrorCode.VISIBILITY_DENIED.getCode(), denied.getCode(),
                "层级不足属『档位不足』→ 2001（契约 §2.0）");
        assertTrue(denied.getDevMessage().contains("门店"),
                "错误信息必须给出档位名称（契约 →『不得模糊报错』）；实际: " + denied.getDevMessage());

        // 角色不可识别
        assertThrows(TenantMismatchException.class,
                () -> guard.assertCovers("NO_SUCH_ROLE", OrgLevel.HEADQUARTERS),
                "角色认不出来属『身份上下文不可信』→ 2003，与 2001 排查方向完全不同");
    }

    @Test
    @DisplayName("达标请求不得抛错, 并返回实际层级")
    void sufficient_level_passes_and_reports_actual_level() {
        assertEquals(OrgLevel.HEADQUARTERS, guard.assertCovers("hq", OrgLevel.STORE));
        assertEquals(OrgLevel.REGION, guard.assertCovers("area", OrgLevel.REGION));
        assertEquals(OrgLevel.STORE, guard.assertCovers("manager", OrgLevel.STORE));
    }

    @Test
    @DisplayName("纯判定 covers(): 角色不可识别返回 false, 不抛错")
    void pure_predicate_never_throws() {
        assertTrue(guard.covers("hq", OrgLevel.HEADQUARTERS));
        assertFalse(guard.covers("manager", OrgLevel.HEADQUARTERS));
        assertFalse(guard.covers("NO_SUCH_ROLE", OrgLevel.STORE), "不可识别 → false");
        assertTrue(guard.covers("NO_SUCH_ROLE", null), "未声明所需层级 → 视为通过");
    }

    @Test
    @DisplayName("assertCovers(null) 必须报调用方错误 —— 未声明不等于放行")
    void null_required_level_is_a_caller_bug() {
        assertThrows(IllegalArgumentException.class, () -> guard.assertCovers("hq", null),
                "requiredMin 为 null 是调用方漏判空：应走 coversOrNull，"
                        + "而不是在运行时把『忘了标注』变成『全站 403』");
        assertFalse(guard.coversOrNull("hq", null).isPresent(), "可选校验对 null 返回空且不抛错");
    }
}