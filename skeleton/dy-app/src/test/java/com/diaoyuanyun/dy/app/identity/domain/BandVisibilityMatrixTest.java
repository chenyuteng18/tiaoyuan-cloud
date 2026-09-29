package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.security.visibility.FieldGroup;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S2-9 · 契约域 A 的<b>领域层</b>单测：{@code #43} 解析 / 硬锁 / 端角色映射 / 行级范围解算。
 *
 * <h2>它守什么（与 E2E 的分工）</h2>
 * 本类守的是"<b>解算规则写对了</b>"这一层：
 * <pre>
 *   BandVisibilityMatrix   —— config #43 的归一 + 客户侧 ③④ 硬锁
 *   BandAudienceRole       —— 端角色 ↔ config 键（两套命名不同名的唯一对应点）
 *   StoreScopeResolver     —— row_level 两来源取舍 + 越宽拒绝 + store_ids 的 all⇒[] 决定
 * </pre>
 * 装配层（权限码登记 / 拦截器顺序 / 真实路由）归 {@code DomainAEndpointsE2ETest}。
 * 两者<b>不可互相替代</b>：本类能对着一个非法矩阵断言"它必须被拒"，
 * 而 E2E 只能对着"合法配置"的运行时断言。
 *
 * <h2>🛑 本类里的"反向"断言为什么占一半篇幅</h2>
 * 可见性矩阵的失效方向有两个，且<b>方向相反</b>：
 * <pre>
 *   改松  → 客户看到缺口原因 / 派生结论（内部信息外泄）；
 *   改紧  → 客户看不到自己的手环数据（客户端"什么都渲染不出来"）。
 * </pre>
 * 只测"合法配置能通过"会让第二种失效静默通过 —— 故本类对<b>每一种</b>非法形态
 * 都有一条断言，且断言的是"抛"而不是"返回某个兜底值"。
 */
@DisplayName("S2-9 · 域 A 领域层（#43 矩阵 / 端角色映射 / 行级范围）")
class BandVisibilityMatrixTest {

    /**
     * {@code #43} 的<b>真实声明值</b>（逐字复制自
     * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 的 L306）。
     *
     * <p>🛑 刻意<b>不</b>在测试里"造一个更简单的矩阵"：那样测的是本测试自己的构造，
     * 而不是线上那份会生效的配置。真实值一旦被改动（例如有人放开客户 ③④），
     * 硬锁断言就会红 —— 这正是我们要的。
     *
     * <p>它同时也是 {@code ConfigSeedBandProfileSource} 的"期望输出"：
     * 那条链（读 SQL → 正则抓行 → 归一）若断在任一处，本常量与
     * {@code DomainAEndpointsE2ETest} 的对照会一起红。
     */
    private static final String REAL_43 = """
            {"customer":{"raw_data":true,"capture_status":true,"gap_reason":false,"derived_result":false},
             "therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true,
                          "derived_not_adverse_to_customer":true},
             "meridian_therapist":{"raw_data":true,"capture_status":true,"gap_reason":true,"derived_result":true},
             "admin":{"scope_narrowed_by_row":true,"raw_data":true,"capture_status":true,
                      "gap_reason":true,"derived_result":true},
             "store_customer_service":{"endless":true},
             "customer_gap_reason_and_derived_locked":true,
             "unconfigured_means_deny":true}
            """;

    private static BandVisibilityMatrix realMatrix() {
        return BandVisibilityMatrix.fromConfigJson(REAL_43);
    }

    // ==================================================================
    // 注入助手（并<b>自证注入生效</b>）
    // ==================================================================

    /**
     * 做一次文本替换，并断言<b>替换真的改动了内容</b>。
     *
     * <h2>🛑 为什么"注入本身"也需要一条断言</h2>
     * 本套件里"反向用例"（注入一个坏配置 → 期望抛）比"正向用例"多一倍的证明力，
     * 但它们全都建立在一个脆弱的前提上：<b>那个字符串替换真的命中了</b>。
     * 替换失配（多一个空格、换一行、改一个引号）时，{@code bad} 等于 {@code REAL_43}，
     * 它是一个<b>合法</b>矩阵 ⇒ 断言变成"期望抛但没有抛" ⇒ 失败信息指向
     * <b>被测代码</b>，而真正的成因在测试的替换字符串上。
     *
     * <p>这不是假设：本类初版就有一处跨行替换静默失配，失败信息指向
     * {@code BandVisibilityMatrix}，看起来像"缺角色行没被拦住"。
     * 故这里对每次替换先自证一次 —— 它把"注入失配"从一次方向错误的失败，
     * 变成一次指向自己的失败。
     */
    private static String replaceMust(String json, String from, String to) {
        String out = json.replace(from, to);
        assertNotEquals(json, out,
                "🛑 注入未生效：replace 未命中\n  查找: " + from
                        + "\n  —— 本断言依赖这次替换真的改动了文本；未改动时 bad == REAL_43（合法矩阵），"
                        + "后续『期望抛』会失败并把方向指错到被测代码。"
                        + "若 REAL_43 的排版变了（换行 / 空格 / 引号），请同步更新 from");
        return out;
    }

    /**
     * 从矩阵 JSON 里<b>删掉一个角色行</b>（含其行内标志），并自证删除生效。
     *
     * <p>用正则而非字面 {@code replace}：角色行在 {@code REAL_43} 里可能跨行
     * （把关时的排版差异吃进来），字面匹配因此极易静默失配 —— 正是上一条注释说的那个坑。
     */
    private static String deleteRow(String json, String roleKey) {
        String out = json.replaceAll(
                "\"" + java.util.regex.Pattern.quote(roleKey) + "\"\\s*:\\s*\\{[^}]*\\}\\s*,?\\s*", "");
        assertNotEquals(json, out,
                "🛑 注入未生效：未能删除角色行 '" + roleKey + "' —— "
                        + "后续『缺行必须被拒』的断言会退化成一次方向错误的失败");
        return out;
    }

    // ==================================================================
    // 一、真实 #43 的归一（"读到的就是线上那份"）
    // ==================================================================

    @Nested
    @DisplayName("一 · 真实 #43 归一")
    class RealConfig {

        @Test
        @DisplayName("#43 五个角色键 + 两个行为标志全部解析出来（行数 = 5）")
        void parses_all_five_audience_rows_and_both_behavior_flags() {
            BandVisibilityMatrix m = realMatrix();
            assertEquals(5, m.rows().size(),
                    "config #43 有五个角色键（customer/therapist/meridian_therapist/admin/"
                            + "store_customer_service）—— 行数不等于 5 说明有键被漏解析"
                            + "（漏解析在旧实现里会表现为『该角色无档位』，而它不会报错）。实际: "
                            + m.rows().keySet());
            assertTrue(m.customerGapReasonAndDerivedLocked(),
                    "行为标志 customer_gap_reason_and_derived_locked 必须为 true"
                            + "（它是 ③④ 硬锁的配置侧声明）");
            assertTrue(m.unconfiguredMeansDeny(),
                    "行为标志 unconfigured_means_deny 必须为 true —— "
                            + "『未配置即拒绝』是 fail-closed 的配置侧声明");
        }

        @Test
        @DisplayName("客户侧：①②可见、③④不可见（契约 A2 的 band_visibility 逐字）")
        void customer_sees_groups_one_and_two_only() {
            BandFieldVisibility c = realMatrix().rowOf(BandAudienceRole.CUSTOMER);
            assertTrue(c.canSee(FieldGroup.RAW_DATA), "客户 ① 手环原始数据必须可见（他自己戴的表）");
            assertTrue(c.canSee(FieldGroup.CAPTURE_STATUS), "客户 ② 采集状态必须可见");
            assertFalse(c.canSee(FieldGroup.GAP_REASON), "客户 ③ 缺口原因必须不可见");
            assertFalse(c.canSee(FieldGroup.DERIVED_RESULT), "客户 ④ 派生结果必须不可见");
            assertEquals(2, c.visibleCount(), "客户只有 ①② 两档可见");
        }

        @Test
        @DisplayName("调理师/经络师/管理：四档全可见（但各自带不同的行内标志）")
        void staff_roles_see_all_four_groups() {
            BandVisibilityMatrix m = realMatrix();
            for (BandAudienceRole role : java.util.List.of(
                    BandAudienceRole.THERAPIST,
                    BandAudienceRole.MERIDIAN_THERAPIST,
                    BandAudienceRole.ADMIN)) {
                assertEquals(4, m.rowOf(role).visibleCount(),
                        role.configKey() + " 四档必须全可见（契约 §3.1 矩阵）");
            }
        }

        @Test
        @DisplayName("门店客服：四档全不可见（无端、无账号）")
        void store_customer_service_sees_nothing() {
            BandFieldVisibility s = realMatrix().rowOf(BandAudienceRole.STORE_CUSTOMER_SERVICE);
            assertEquals(0, s.visibleCount(),
                    "门店客服必须四档全不可见 —— 它无端、无账号（契约 x-roles: token-role=null, end=null），"
                            + "业务方 2026-09-16 裁定『完全不可见、不代录』");
        }
    }

    // ==================================================================
    // 二、客户侧 ③④ 硬锁（改松方向）
    // ==================================================================

    @Nested
    @DisplayName("二 · 客户侧 ③④ 硬锁（防『配置放开导致内部信息外泄』）")
    class ClientHardLock {

        @Test
        @DisplayName("把客户 gap_reason 改成 true → 必须抛（且消息点名硬锁）")
        void un_locking_gap_reason_for_customer_throws() {
            String bad = replaceMust(REAL_43,
                    "\"customer\":{\"raw_data\":true,\"capture_status\":true,\"gap_reason\":false",
                    "\"customer\":{\"raw_data\":true,\"capture_status\":true,\"gap_reason\":true");
            BizException e = assertThrows(BizException.class,
                    () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 客户 ③ 可见 = 内部缺口原因外泄。config #43 的注释逐字："
                            + "『客户侧 ③④ 为硬约束、不得通过配置放开 —— 放开属「变更业务裁定」』。"
                            + "该形态必须以【抛】告终，不得静默接受（静默接受的后果不是报错，"
                            + "而是客户端多渲染一个内部枚举值）。");
            assertTrue(String.valueOf(e.getDevMessage()).contains("硬锁"),
                    "拒绝消息必须点名『硬锁』，使事故复盘能一眼看出这是合规约束而非配置笔误。实际: "
                            + e.getDevMessage());
        }

        @Test
        @DisplayName("把客户 derived_result 改成 true → 必须抛")
        void un_locking_derived_result_for_customer_throws() {
            String bad = replaceMust(REAL_43,
                    "\"gap_reason\":false,\"derived_result\":false}",
                    "\"gap_reason\":false,\"derived_result\":true}");
            assertThrows(BizException.class,
                    () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 客户 ④ 可见 = 派生结论外泄（Q10 口径② / PRD P0-13 逐字禁止"
                            + "『判定结论面向客户展示』）");
        }

        @Test
        @DisplayName("把客户 ① 改成 false → 也必须抛（反方向的锁：防『误关掉客户对自己数据的知情权』）")
        void locking_raw_data_away_from_customer_also_throws() {
            String bad = replaceMust(REAL_43,
                    "\"customer\":{\"raw_data\":true",
                    "\"customer\":{\"raw_data\":false");
            BizException e = assertThrows(BizException.class,
                    () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 与 ③④ 的锁【方向相反】：本锁防『把客户对自己数据的知情权误关掉』。"
                            + "只锁『不许放开』而不锁『不许误关』，会让一次配置笔误"
                            + "静默地让全体客户看不到自己的手环数据 —— 而它同样不报错。");
            assertTrue(String.valueOf(e.getDevMessage()).contains("硬锁"),
                    "消息必须点名硬锁。实际: " + e.getDevMessage());
        }
    }

    // ==================================================================
    // 三、结构性 fail-closed（缺键 / 未登记键）
    // ==================================================================

    @Nested
    @DisplayName("三 · 结构性 fail-closed")
    class StructuralFailClosed {

        @Test
        @DisplayName("缺一个角色行 → 抛（不得默认补一行）")
        void missing_audience_row_throws() {
            String bad = deleteRow(REAL_43, "therapist");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 缺角色行不得默认补 —— 默认开会让该角色『意外可见』，"
                            + "默认关会让它『什么都看不到』。两个方向都是错的，唯一正确的动作是拒绝启用。");
        }

        @Test
        @DisplayName("某角色缺一个字段组键 → 抛（防客户端拿到 undefined）")
        void missing_field_group_within_a_row_throws() {
            String bad = replaceMust(REAL_43,
                    "\"meridian_therapist\":{\"raw_data\":true,\"capture_status\":true,\"gap_reason\":true,\"derived_result\":true}",
                    "\"meridian_therapist\":{\"raw_data\":true,\"capture_status\":true,\"derived_result\":true}");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 四档缺一必抛：缺键会让客户端拿到 undefined，"
                            + "而 undefined 与 false 在渲染分支上是【两条不同的路径】——"
                            + "客户端无法区分『这档我看不到』与『这档在协议里不存在』");
        }

        @Test
        @DisplayName("含未登记角色键 → 抛（防『配置侧已改动而代码未跟上』被静默忽略）")
        void unknown_audience_key_throws() {
            String bad = replaceMust(REAL_43, "\"admin\":{", "\"new_role_x\":{");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 未登记键必抛：『矩阵里有个我不认识的角色』与『矩阵里没有这个角色』"
                            + "是【两件事】—— 前者说明配置侧已有授权改动而代码未跟上。"
                            + "静默忽略它等于把一次已发生的授权改动藏起来");
        }

        @Test
        @DisplayName("门店客服缺 endless 标志 → 抛（『无端』这件事必须有配置落点）")
        void store_customer_service_without_endless_flag_throws() {
            String bad = replaceMust(REAL_43, "\"store_customer_service\":{\"endless\":true}",
                    "\"store_customer_service\":{}");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(bad),
                    "🛑 无端角色必须显式声明 endless:true —— "
                            + "缺该标志会让『这个角色在任何端上都不成立』在配置里看不出落点，"
                            + "读配置的人会以为它只是『漏配了四个档位』");
        }

        @Test
        @DisplayName("空 JSON / null → 抛（口径缺失 = 拒绝启用，不返回默认矩阵）")
        void empty_or_null_config_throws() {
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(""),
                    "空串必须抛");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson(null),
                    "null 必须抛");
            assertThrows(BizException.class, () -> BandVisibilityMatrix.fromConfigJson("{}"),
                    "空对象必须抛（缺全部角色行）");
        }
    }

    // ==================================================================
    // 四、端角色 ↔ config 键的映射（两套命名不同名的唯一对应点）
    // ==================================================================

    @Nested
    @DisplayName("四 · 端角色 ↔ config 键映射")
    class EndRoleMapping {

        @Test
        @DisplayName("两套命名不同名，且 A2 解算链经 rowForEndRole 走通（meridian ↔ meridian_therapist）")
        void config_keys_and_end_roles_are_different_namespaces() {
            // 「配置键」与「端角色码」是两套命名 —— 这是"两个口径不同名"的实证。
            assertNotEquals(BandAudienceRole.MERIDIAN_THERAPIST.configKey(),
                    VisibilityRole.MERIDIAN.contractCode(),
                    "config #43 的键 meridian_therapist 与契约端角色码 meridian 不同名 —— "
                            + "BandAudienceRole 是它们的唯一对应点。若两者『恰好相等』，"
                            + "本类这条断言会提醒你：契约或配置改过名了");

            // A2 的解算链：端角色 → 受众行 → 四档
            BandFieldVisibility meridian = realMatrix().rowForEndRole(VisibilityRole.MERIDIAN);
            assertEquals(4, meridian.visibleCount(), "经络师四档全可见");
            assertEquals(BandAudienceRole.MERIDIAN_THERAPIST, meridian.role(),
                    "端角色 meridian 必须落到 config 键 meridian_therapist 那一行");
        }

        @Test
        @DisplayName("admin 端角色四个别名（manager/area/hq + 大写）都落到同一行 admin")
        void all_admin_aliases_map_to_the_same_row() {
            BandVisibilityMatrix m = realMatrix();
            for (String tokenRole : VisibilityRole.ADMIN.tokenRoles()) {
                VisibilityRole end = VisibilityRole.of(tokenRole);
                assertEquals(VisibilityRole.ADMIN, end, "别名 " + tokenRole + " 必须展开为端角色 admin");
                assertEquals(4, m.rowForEndRole(end).visibleCount(),
                        "别名 " + tokenRole + " 必须与 admin 同档位（本域刻意【不】按层级再收窄 —— "
                                + "收窄由 store_scope.row_level 与 A3 的行级过滤承担）");
            }
        }

        @Test
        @DisplayName("门店客服无端 → rowForEndRole 无从得到它（无端角色不得被赋予档位）")
        void endless_role_cannot_be_reached_from_an_end_role() {
            assertTrue(BandAudienceRole.STORE_CUSTOMER_SERVICE.endRole().isEmpty(),
                    "门店客服必须【无端】（契约 x-roles: token-role=null, end=null）——"
                            + "它没有账号，任何请求都带不了这个身份");
            for (VisibilityRole end : VisibilityRole.values()) {
                assertNotEquals(BandAudienceRole.STORE_CUSTOMER_SERVICE,
                        BandAudienceRole.forVisibilityRole(end),
                        "端角色 " + end.contractCode() + " 不得映射到无端角色门店客服 —— "
                                + "它本就不该出现在任何身份链上");
            }
        }

        @Test
        @DisplayName("未登记端角色 / null → 抛（fail-closed，不回落任一受众）")
        void unknown_or_null_end_role_throws() {
            assertThrows(Exception.class, () -> BandAudienceRole.forVisibilityRole(null),
                    "null 端角色必须抛");
            assertThrows(IllegalArgumentException.class, () -> VisibilityRole.of("some_unknown_role"),
                    "未登记 token 角色必须抛（VisibilityRole.of 的 fail-closed）");
            assertTrue(VisibilityRole.tryOf("some_unknown_role").isEmpty(),
                    "宽容版必须返回空而不是回落任一角色");
        }
    }

    // ==================================================================
    // 五、行级范围解算（row_level 两来源 + 越宽拒绝 + all ⇒ []）
    // ==================================================================

    @Nested
    @DisplayName("五 · 行级范围解算（StoreScopeResolver）")
    class RowScopeResolution {

        @Test
        @DisplayName("token 未声明 scope → 回落角色默认（manager→own_store / area→region / hq→all）")
        void falls_back_to_role_default_when_token_declares_nothing() {
            record Case(String role, RowScope expected) {
            }
            for (Case c : java.util.List.of(
                    new Case("manager", RowScope.OWN_STORE),
                    new Case("area", RowScope.REGION),
                    new Case("hq", RowScope.ALL),
                    new Case("therapist", RowScope.OWN_STORE),
                    new Case("meridian", RowScope.OWN_STORE))) {
                StoreScopeResolver.Resolved r = StoreScopeResolver.resolve(c.role(), null);
                assertEquals(c.expected(), r.rowLevel(),
                        c.role() + " 未声明 scope 时应回落为 " + c.expected().getCode()
                                + "（契约 A2 的映射：manager→own_store / area→region / hq→all）");
                assertEquals(StoreScopeResolver.Resolved.Source.ROLE_DEFAULT, r.source(),
                        "来源必须标为 ROLE_DEFAULT —— 两来源语义不同，必须可分辨（供断言与自描述）");
            }
        }

        @Test
        @DisplayName("token 声明了 scope → 以 token 为准（即便比角色默认更窄）")
        void token_declaration_wins_when_not_wider() {
            StoreScopeResolver.Resolved r = StoreScopeResolver.resolve("area", "own_store");
            assertEquals(RowScope.OWN_STORE, r.rowLevel(),
                    "token 把督导收窄成 own_store 时必须【生效】——"
                            + "取较宽者（角色默认 region）会让『签发方主动收窄范围』静默失效");
            assertEquals(StoreScopeResolver.Resolved.Source.TOKEN, r.source());

            StoreScopeResolver.Resolved same = StoreScopeResolver.resolve("hq", "all");
            assertEquals(RowScope.ALL, same.rowLevel(), "token 与角色默认同值时应以 token 记为来源");
        }

        @Test
        @DisplayName("🛑 token 声明比角色默认【更宽】→ 必须抛，且消息点名两边的值")
        void token_declaration_wider_than_role_default_throws() {
            BizException e = assertThrows(BizException.class,
                    () -> StoreScopeResolver.resolve("manager", "all"),
                    "🛑 门店负责人的 token 声明 all（角色默认 own_store）必须【拒绝】，"
                            + "且【不得】取较宽者：那是一次真实越权（他能看到全租户门店）；"
                            + "也【不得】取较窄者：那会把『两个真相源不一致』静默变成一次权限收紧，"
                            + "事后排查只能看到『数据好像少了』");
            String msg = String.valueOf(e.getDevMessage());
            assertTrue(msg.contains("own_store") && msg.contains("all"),
                    "拒绝消息必须同时点名角色默认与 token 声明两边的值，"
                            + "使配置负责人能立即知道该改哪一边。实际: " + msg);
        }

        @Test
        @DisplayName("all ⇒ store_ids 为空数组（『全量、不枚举』的显式决定）")
        void all_scope_yields_empty_store_ids_on_purpose() {
            assertEquals(java.util.List.of(), StoreScopeResolver.resolveStoreIds(RowScope.ALL, null),
                    "🛑 row_level=all ⇒ store_ids=[] 是【刻意的决定】，不是漏实现：\n"
                            + "  ① 把全租户门店枚举进 A2 会让一次登录相关调用退化成"
                            + "『拉全量组织台账』；\n"
                            + "  ② 下发一份会过期的快照 —— 门店新增后客户端拿到的仍是旧列表，"
                            + "而它【不会报错】，只会少显示几家；\n"
                            + "  ③ 与 A3 形成两个『门店列表真相源』。真实列表只有一个去处：A3。");
        }

        @Test
        @DisplayName("own_store ⇒ [锚点门店]；region ⇒ [锚点辖区]")
        void narrow_scopes_yield_the_anchor() {
            java.util.UUID store = java.util.UUID.randomUUID();
            java.util.UUID region = java.util.UUID.randomUUID();
            StoreAnchor anchor = new StoreAnchor(store, region);

            assertEquals(java.util.List.of(store.toString()),
                    StoreScopeResolver.resolveStoreIds(RowScope.OWN_STORE, anchor),
                    "own_store 的 store_ids = 锚点门店本身");
            assertEquals(java.util.List.of(region.toString()),
                    StoreScopeResolver.resolveStoreIds(RowScope.REGION, anchor),
                    "region 的 store_ids = 锚点辖区（具体门店清单由 A3 在 SQL 侧过滤，"
                            + "A2 不枚举全区门店 —— 理由与 all 同）");
        }

        @Test
        @DisplayName("🛑 需锚点而锚点缺失 → 抛，绝不回落『全量』")
        void missing_anchor_throws_instead_of_falling_back_to_all() {
            assertThrows(BizException.class,
                    () -> StoreScopeResolver.resolveStoreIds(RowScope.OWN_STORE, StoreAnchor.none()),
                    "🛑 own_store 缺锚点门店必须抛：把它读成『那就给他全量吧』"
                            + "是把一次【数据不完整】变成一次【越权】的最短路径");
            assertThrows(BizException.class,
                    () -> StoreScopeResolver.resolveStoreIds(RowScope.REGION, StoreAnchor.none()),
                    "🛑 region 缺锚点辖区必须抛（同上）");
            assertThrows(BizException.class,
                    () -> StoreScopeResolver.resolveStoreIds(RowScope.REGION,
                            new StoreAnchor(java.util.UUID.randomUUID(), null)),
                    "有门店但门店未挂辖区时，region 范围同样不得回落全量");
        }

        @Test
        @DisplayName("越宽序判定：OWN_STORE ⊂ REGION ⊂ ALL（用 ordinal 而非字符串比较）")
        void within_ordering_is_by_scope_not_by_string() {
            assertTrue(StoreScopeResolver.isWithin(RowScope.OWN_STORE, RowScope.REGION));
            assertTrue(StoreScopeResolver.isWithin(RowScope.REGION, RowScope.ALL));
            assertTrue(StoreScopeResolver.isWithin(RowScope.ALL, RowScope.ALL));
            assertFalse(StoreScopeResolver.isWithin(RowScope.ALL, RowScope.OWN_STORE),
                    "ALL 不窄于 OWN_STORE");
            // 🛑 字符串比较会把 "all" 判得比 "own_store" 小 —— 那会让一次真实越权被判为"不越宽"
            assertTrue("all".compareTo("own_store") < 0,
                    "（自证）字符串比较确实会给错答案，故实现必须用 ordinal");
        }

        @Test
        @DisplayName("未登记角色 → 抛（不回落默认层级）")
        void unknown_role_throws() {
            assertThrows(Exception.class, () -> StoreScopeResolver.resolve("ghost_role", null),
                    "未登记角色必须抛（OrgLevel.fromRole 的 fail-closed）——"
                            + "回落默认层级会让一次角色码拼写错误静默变成一次权限判定");
        }
    }

    // ==================================================================
    // 六、A2 出站声明的装配（「不下发」用 null 而非 false）
    // ==================================================================

    @Nested
    @DisplayName("六 · A2 声明装配（裁剪落点）")
    class DeclarationAssembly {

        private AuthMeDeclaration declaration(Boolean refund, AuthMeDeclaration.StoreScopeView scope) {
            return new AuthMeDeclaration(
                    VisibilityRole.CLIENT.contractCode(),
                    realMatrix().rowOf(BandAudienceRole.CUSTOMER),
                    refund,
                    scope);
        }

        @Test
        @DisplayName("band_visibility 四键【恒完整下发】（含 false）—— 与 store_scope 的裁剪规则相反")
        void band_visibility_is_always_complete_even_when_false() {
            Map<String, Object> data = declaration(null, null).toContractData();
            Map<String, Object> band = castMap(data.get("band_visibility"));
            assertEquals(4, band.size(),
                    "band_visibility 必须【四个键齐备】（契约：每个子键都带 "
                            + "x-visible-to: [client, therapist, meridian, admin]）——"
                            + "缺键会让客户端拿到 undefined，而它与 false 是两条不同的渲染分支");
            assertEquals(Boolean.FALSE, band.get("field_group_3_gap_reason"),
                    "客户的 field_group_3_gap_reason 必须是【显式 false】，不是缺键");
            assertEquals(Boolean.FALSE, band.get("field_group_4_derived"),
                    "客户的 field_group_4_derived 必须是【显式 false】");
            // 键序即契约书写序（①②③④）
            assertEquals(java.util.List.of("field_group_1_raw", "field_group_2_status",
                            "field_group_3_gap_reason", "field_group_4_derived"),
                    new java.util.ArrayList<>(band.keySet()),
                    "键序必须与契约 schema 的书写顺序一致（用 LinkedHashMap 保序，"
                            + "不得用迭代顺序不保证的 Map.of）");
        }

        @Test
        @DisplayName("refund_visibility=null / store_scope=null ⇒ 两个键【不出现在响应里】")
        void null_means_the_key_is_absent_not_false() {
            AuthMeDeclaration d = declaration(null, null);
            Map<String, Object> data = d.toContractData();
            assertFalse(data.containsKey("refund_visibility"),
                    "🛑 refund_visibility 对客户必须【不下发该键】，而不是下发 false：\n"
                            + "  false 等于告诉他『存在这个档位，你的值是 false』；\n"
                            + "  而契约 x-visible-to: [meridian, admin] 的语义是【该键对他不存在】。");
            assertFalse(data.containsKey("store_scope"),
                    "🛑 store_scope 对客户必须【不下发该键】：下发空对象会被读成"
                            + "『我没有范围限制』，方向与事实【完全相反】（客户是 n/a 仅本人）");
            assertEquals(java.util.List.of("role", "band_visibility"), d.emittedKeys(),
                    "客户侧实际下发的键恰好是 role + band_visibility");
        }

        @Test
        @DisplayName("refund_visibility 有值 ⇒ 该键出现；store_scope 的 all ⇒ store_ids 为空数组而非缺键")
        void present_values_are_emitted_with_stable_shape() {
            AuthMeDeclaration d = declaration(Boolean.FALSE,
                    new AuthMeDeclaration.StoreScopeView("all", java.util.List.of()));
            Map<String, Object> data = d.toContractData();
            assertTrue(data.containsKey("refund_visibility"),
                    "有值（含 false）时必须下发该键 —— 对 meridian/admin 它是真实档位");
            Map<String, Object> scope = castMap(data.get("store_scope"));
            assertEquals("all", scope.get("row_level"));
            assertEquals(java.util.List.of(), scope.get("store_ids"),
                    "store_ids 必须是【空数组】而不是缺键（缺键会被读成『没有范围限制』，"
                            + "与 false 之于布尔键同一类误读）");
        }

        @Test
        @DisplayName("role / band_visibility 为 null ⇒ 构造期即抛（不得产出缺键响应）")
        void missing_declaration_core_throws_at_construction() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AuthMeDeclaration(null,
                            realMatrix().rowOf(BandAudienceRole.CUSTOMER), null, null),
                    "role 为 null 必须在构造期拦下 —— 契约 AuthMeData.role 对全部 4 端可见");
            assertThrows(IllegalArgumentException.class,
                    () -> new AuthMeDeclaration("client", null, null, null),
                    "band_visibility 为 null 必须在构造期拦下（四个档位布尔值对全部 4 端可见）");
        }

        @Test
        @DisplayName("store_scope 的 row_level 越界 ⇒ 抛（不下发客户端不认识的枚举值）")
        void unknown_row_level_throws() {
            assertThrows(BizException.class,
                    () -> new AuthMeDeclaration.StoreScopeView("global", java.util.List.of()),
                    "🛑 row_level 不在契约 enum [own_store, region, all] 内必须抛 —— "
                            + "客户端按枚举分支，一个它不认识的值会让它落进未定义行为；"
                            + "而『未定义行为』在权限场景里通常表现为【什么都没过滤】");
            assertThrows(BizException.class,
                    () -> new AuthMeDeclaration.StoreScopeView("all", null),
                    "store_ids 为 null 必须抛（全量应为空数组，见上）");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Object o) {
            if (!(o instanceof Map<?, ?> m)) {
                fail("期望一个对象，实际: " + o);
                return new LinkedHashMap<>();
            }
            return (Map<String, Object>) m;
        }
    }
}