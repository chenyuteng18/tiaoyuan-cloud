package com.diaoyuanyun.dy.tenancy.context;

import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QC-3 反向验证：{@link RowScope#fromCode(String)} 必须 <b>fail-closed</b>，
 * 不得对未知 code 静默回落 {@code OWN_STORE}。
 *
 * <p><b>本测试要抓的"病"</b>：把配置写错（拼错 / 大小写不符 / 值来自旧版本枚举）
 * 变成<b>静默的数据缺失</b>。若 {@code fromCode} 回落到 {@code OWN_STORE}，
 * 区域督导只是永远看不到辖区数据，而系统<b>不报任何错</b> —— 这类故障在运维侧
 * 极难归因（表现为"数据好像少了"，而不是"配置错了"），并与 PRD fail-closed 口径
 * （未配置 / 配置非法即拒绝，不得默认给值）字面冲突。
 *
 * <p><b>反向验证口径</b>：把 {@code fromCode} 改回 {@code return OWN_STORE;}
 * （即注入"静默回落"这一处错误），本类必须变红。这不是自证式测试 ——
 * 断言对象是"非法输入的具体处置方式"，而不是"函数被调用过"。
 *
 * <p>另一个不变量：{@code tryFromCode} 与 {@code fromCode} 对<b>合法</b>输入
 * 必须完全一致（否则"宽容版"会悄悄偏离严格版的取值口径）。
 */
class RowScopeFailClosedTest {

    // ------------------------------------------------------------------
    // 1) 合法输入：正常解析（先证明测试不是"什么都抛"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法 code 正常解析：own_store / region / all")
    void known_codes_resolve_normally() {
        assertEquals(RowScope.OWN_STORE, RowScope.fromCode("own_store"));
        assertEquals(RowScope.REGION, RowScope.fromCode("region"));
        assertEquals(RowScope.ALL, RowScope.fromCode("all"));
    }

    @Test
    @DisplayName("getCode() 与 fromCode() 互为逆运算（三个取值全覆盖）")
    void code_round_trips_for_every_constant() {
        for (RowScope s : RowScope.values()) {
            assertEquals(s, RowScope.fromCode(s.getCode()),
                    "fromCode(getCode()) 未回到原值：" + s);
        }
    }

    // ------------------------------------------------------------------
    // 2) ★ 核心反向验证：非法输入必须拒绝，不得回落
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 拼错的值必须抛错，绝不回落 OWN_STORE（注入静默回落即红）")
    void misspelled_code_throws_instead_of_falling_back() {
        // 「regin」= region 的典型拼写错误。这正是回落方案最难查的那种输入：
        // 它"看起来像"合法值，静默回落成 OWN_STORE 后行为看似合理（只是数据变少）。
        TenantMismatchException ex = assertThrows(TenantMismatchException.class,
                () -> RowScope.fromCode("regin"),
                "拼错的 code 被静默接受了 —— fail-closed 被破坏："
                        + "配置错误会退化为【区域督导看不到辖区数据且无任何报错】的运维事故");
        assertTrue(ex.getDevMessage() != null && ex.getDevMessage().contains("regin"),
                "异常信息未指出出错的值，排查时无法定位：" + ex.getDevMessage());
    }

    @Test
    @DisplayName("大小写不符必须抛错（枚举 code 是全小写，不做隐式归一）")
    void wrong_case_throws() {
        assertThrows(TenantMismatchException.class, () -> RowScope.fromCode("REGION"),
                "大写 REGION 被静默接受了 —— code 口径不唯一，两处写法可产生两种行为");
        assertThrows(TenantMismatchException.class, () -> RowScope.fromCode("Own_Store"),
                "混合大小写被静默接受了");
    }

    @Test
    @DisplayName("null / 空串 / 空白必须抛错（缺值不等于【用默认值】）")
    void null_and_blank_throw() {
        assertThrows(TenantMismatchException.class, () -> RowScope.fromCode(null),
                "null 被静默接受了 —— 「未配置」被当成了「配置为 OWN_STORE」，"
                        + "与 PRD「未配置即拒绝」正面冲突");
        assertThrows(TenantMismatchException.class, () -> RowScope.fromCode(""),
                "空串被静默接受了");
        assertThrows(TenantMismatchException.class, () -> RowScope.fromCode("   "),
                "空白串被静默接受了");
    }

    @Test
    @DisplayName("采集到的实际历史取值形态必须抛错（防【旧版本枚举值】静默降级）")
    void plausible_but_unknown_codes_throw() {
        // 这些是在多租户 / 三级权限系统里**可能真实出现**的取值：
        // 旧版本枚举、前端传的显示名、上游系统的编码习惯。
        // 它们全都不在 {own_store, region, all} 里 —— 必须一律拒绝。
        for (String bad : new String[]{
                "store",            // 旧命名
                "branch",           // 门店的另一种译法
                "all_stores",       // 组合写法
                "global",           // 平台语义
                "OWN_STORE ",       // 尾随空格
                " own_store",       // 前导空格
                "own-store",        // 连字符（与下划线混用）
                "全部门店“           // 中文显示名被当成 code 传入
        }) {
            assertThrows(TenantMismatchException.class, () -> RowScope.fromCode(bad),
                    ”未知取值被静默接受了: \"" + bad + "\" —— 该值会导致范围被悄悄改成默认值");
        }
    }

    // ------------------------------------------------------------------
    // 3) 宽严两版的一致性：tryFromCode 只在"非法"上不同，在"合法"上必须一致
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tryFromCode 对合法输入与 fromCode 完全一致（宽版不得悄悄偏离取值口径）")
    void lenient_variant_agrees_with_strict_one_on_valid_input() {
        for (RowScope s : RowScope.values()) {
            assertEquals(Optional.of(s), RowScope.tryFromCode(s.getCode()),
                    "tryFromCode 在合法值上与 fromCode 不一致：" + s);
        }
    }

    @Test
    @DisplayName("tryFromCode 对非法输入返回空而非抛错（且不得返回 OWN_STORE）")
    void lenient_variant_returns_empty_and_never_own_store() {
        for (String bad : new String[]{"regin", "REGION", "", "   ", "global", "own-store“}) {
            Optional<RowScope> r = RowScope.tryFromCode(bad);
            assertTrue(r.isEmpty(), ”tryFromCode 对非法值 " + bad + " 返回了 " + r);
            assertFalse(Optional.of(RowScope.OWN_STORE).equals(r),
                    "tryFromCode 把非法值 " + bad + " 回落成了 OWN_STORE —— 静默降级复活");
        }
        assertTrue(RowScope.tryFromCode(null).isEmpty(),
                "tryFromCode(null) 应为空，而不是回落默认范围");
    }

    // ------------------------------------------------------------------
    // 4) 三个取值仍然只有三个（防"悄悄加一个更宽的范围"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("枚举取值恰为 3 个，且不含任何默认/未定/自动范围")
    void exactly_three_scopes_and_no_implicit_default() {
        assertEquals(3, RowScope.values().length,
                "RowScope 取值数变了 —— 行级可见范围是权限契约的一部分，"
                        + "新增须走 ADR-02 / T-7 变更，不得随手加");
        for (RowScope s : RowScope.values()) {
            assertFalse(s.name().contains("DEFAULT") || s.name().contains("UNKNOWN")
                            || s.name().contains("AUTO"),
                    "出现了隐式默认/未知范围：" + s + " —— fail-closed 的语义会被它吃掉");
        }
    }
}