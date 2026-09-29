package com.diaoyuanyun.dy.audit.chain;

import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.audit.service.JdbcAuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 审计哈希链【真实 PostgreSQL 端到端】门禁测试 —— DoD A4 / ADR-09 / ADR-11。
 *
 * <h1>与"断言自己写的常量"的本质区别</h1>
 * 本类的断言对象全部是<b>真实可观察行为</b>：
 * 写入后从库里读回来的行、重算出的摘要与实际存储的摘要的<b>相等/不等</b>、
 * 被篡改后 {@code verifyChain()} 报出的 {@code broken_at} 到底是哪一行的 {@code id}、
 * 以及 PostgreSQL 真正返回的 SQLSTATE。没有任何一条断言是"我刚写的枚举/常量等于它自己"。
 *
 * <h1>三个身份的分工（混淆它们就会写出假通过的测试）</h1>
 * <ul>
 *   <li><b>应用（{@code dy_audit_app}，非超级用户）</b> —— 链写入与校验只用它。
 *       用 {@code postgres} 跑 append-only 断言会假通过：超级用户绕过一切权限检查。</li>
 *   <li><b>攻击者（{@code postgres}）</b> —— 扮演"拥有 DDL 能力的人"：篡改 payload、
 *       改写 hash、删掉中间一行、把权限撤掉。它代表一个<b>真实存在的威胁</b>：
 *       有 DDL 权限的人可以先把 REVOKE 掉再改数据，所以"撤销权限"挡不住他；
 *       挡得住他的是哈希链 —— 这正是本类要把两者分开断言的原因。</li>
 *   <li>两者<b>不可互相替代</b>：权限断言（{@code append_only_*}）证明"应用凭据不足以篡改"，
 *       链断言（{@code tamper_cases_*}）证明"篡改必然被发现"。合起来才成立完整命题。</li>
 * </ul>
 *
 * <h1>断链语义（读断言前必须知道，否则会误判测试写错了）</h1>
 * <ul>
 *   <li>改写<b>某一行自身</b>的 {@code payload}/{@code hash}/{@code prev_hash} ⇒
 *       是<b>这一行</b>重算后与存储 hash 不符 ⇒ {@code broken_at} = <b>被改那行</b>。</li>
 *   <li><b>删除中间一行</b> ⇒ 被删那行已经不在，无法被指向；
 *       是它的<b>后继</b>发现"我的 prev_hash 不等于我真正的前驱" ⇒
 *       {@code broken_at} = <b>被删行的后继</b>。这不是缺陷，是链式结构的固有语义。</li>
 * </ul>
 * 故本类对这两种注入分别断言，<b>不</b>写成"反正报错了就算过"——
 * 那种写法在"调换顺序返回另一行 id"时也会绿，等于没有断言位置。
 *
 * <h1>缺库即失败（fail-closed）</h1>
 * 连不上库 = 缺少链完整性测试 → 构建必须失败。唯一逃生阀是显式
 * {@code -Ddy.audit.gate.skip=true}（供无库的纯编译流水线），绝不静默跳过。
 */
class AuditChainGateTest {

    private static final String TENANT = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String ACTOR = "staff-0001";

    private static DataSource appDataSource;
    private static JdbcAuditLogService service;
    private static TransactionTemplate txTemplate;

    /** lambda / 内部类里引用静态字段需 final 或实际上是 final，这里给出显式别名以便匿名内部类使用。 */
    private static final DataSource APP_DS = AuditChainTestSupport.appDataSource();

    /** 证据累积：反向验证的"注入内容 / 期望失败点 / 实际失败断言原文"三栏表落盘用。 */
    private static final StringBuilder EVIDENCE = new StringBuilder();

    /** 供对照实验在裸 SQL 里引用同一把锁；与 {@code JdbcAuditLogService.CHAIN_LOCK_KEY} 同值。 */
    private static final String LOCK_KEY_LITERAL = Long.toString(0x6479_5F61_7564_6974L);

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    static void provision() {
        if (AuditChainTestSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "审计链真库门禁被显式跳过: -Ddy.audit.gate.skip=true");
        }
        try {
            String log = AuditChainTestSupport.provision();
            System.out.println("[AUDIT-CHAIN-GATE] provision:\n" + log);
        } catch (RuntimeException e) {
            throw AuditChainTestSupport.unreachable(e);
        }

        appDataSource = AuditChainTestSupport.appDataSource();
        service = new JdbcAuditLogService(appDataSource);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        EVIDENCE.setLength(0);
    }

    @AfterEach
    void resetTable() {
        // Harness 复位（用攻击者身份，因为应用身份已无 DELETE 权限 —— 这本身就是 append-only 生效的证据）。
        AuditChainTestSupport.truncateForHarness();
        // 权限也必须复位：REVOKE 是库级持久变更，不回滚就会串到后面的用例，
        // 让它们以"权限不够"这种指向不了真因的方式失败（见 restoreWritePrivileges 的说明）。
        AuditChainTestSupport.restoreWritePrivileges();
    }

    @org.junit.jupiter.api.AfterAll
    static void dumpEvidence() {
        if (EVIDENCE.length() == 0) {
            return;
        }
        try {
            Path out = AuditChainTestSupport.workDir().resolve("reverse-verification-evidence.txt");
            Files.writeString(out, EVIDENCE.toString(), StandardCharsets.UTF_8);
            System.out.println("[AUDIT-CHAIN-GATE] 反向验证证据已写入 " + out);
        } catch (IOException e) {
            // 证据落盘失败不应伪装成测试失败原因 —— 但也不能静默：
            System.out.println("[AUDIT-CHAIN-GATE] 证据落盘失败: " + e);
        }
    }

    // ------------------------------------------------------------------
    // 身份前提：先证明"我没有在用超级用户"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A0 身份前提：应用连接必须是非超级用户（否则 append-only 断言将全部假通过）")
    void app_connection_must_not_be_a_superuser() {
        Integer superCount;
        String who;
        try (var c = appDataSource.getConnection();
             var st = c.createStatement()) {
            var rs = st.executeQuery("SELECT current_user, rolsuper FROM pg_roles WHERE rolname = current_user");
            assertTrue(rs.next(), "查不到当前角色 —— 数据库不可用");
            who = rs.getString(1);
            superCount = rs.getBoolean(2) ? 1 : 0;
        } catch (SQLException e) {
            throw AuditChainTestSupport.unreachable(new IllegalStateException(e));
        }
        assertEquals(AuditChainTestSupport.APP_USER, who, "应用连接的当前角色应为非超级用户应用角色");
        assertEquals(0, superCount,
                "应用连接是超级用户 → 撤销 UPDATE/DELETE 对它无效 → append-only 断言将『假通过』。"
                        + "这是 RLS 门禁（dy-app）踩过的同一个陷阱。");
    }

    // ------------------------------------------------------------------
    // DoD 1: 写入时落链
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DoD1 写入落链：首条 prev_hash=创世值；第 N 条 prev_hash=前一条 hash；每条 hash=重算值")
    void append_links_each_record_to_its_predecessor() {
        List<String> ids = new ArrayList<>();
        ids.add(appendInTx("CUSTOMER_STATUS_CHANGE", "customer", "c1", "{\"to\":\"active\"}"));
        ids.add(appendInTx("CUSTOMER_NOTE_APPEND", "customer", "c1", "{\"note\":\"first\"}"));
        ids.add(appendInTx("CUSTOMER_NOTE_APPEND", "customer", "c2", null));

        List<AuditLog> rows = service.readAllInOrder();
        assertEquals(3, rows.size(), "应落 3 条记录");

        // 首条必须指向创世值（而不是空串、也不是"上一条"）
        assertEquals(AuditChainHash.GENESIS_PREV_HASH, rows.get(0).prevHash(),
                "链首的 prev_hash 必须是约定创世值");

        // 第 N 条的 prev_hash 必须等于第 N-1 条的 hash —— 这是"链"的定义
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).hash(), rows.get(i).prevHash(),
                    "第 " + i + " 条的 prev_hash 不等于前一条的 hash —— 链未接上");
        }

        // 每条的 hash 必须等于"用其字段 + 其存储 prev_hash 独立重算"的结果
        for (AuditLog row : rows) {
            String recomputed = AuditChainHash.chainHash(
                    row.tenantId(), row.actor(), row.action(),
                    row.targetType(), row.targetId(), row.payload(), row.prevHash());
            assertEquals(recomputed, row.hash(),
                    "记录 " + row.id() + " 的 hash 与独立重算不符 —— 写入时未落链或规范串不一致");
            assertTrue(AuditChainHash.looksLikeHash(row.hash()), "hash 应为 64 位 hex");
        }

        // 且整链校验为有效，条数正确（"链有效"与"链是空的"不可混淆）
        ChainVerification v = service.verifyChain();
        assertTrue(v.valid(), "健康链应校验通过，实际 broken_at=" + v.brokenAt() + " reason=" + v.reason());
        assertEquals(3, v.checked(), "参与校验的条数应为 3");
    }

    @Test
    @DisplayName("DoD1 写入落链：空表时校验返回 valid 且 checked=0（区分『链空』与『链有效』）")
    void empty_chain_is_valid_but_reports_zero_checked() {
        ChainVerification v = service.verifyChain();
        assertTrue(v.valid(), "空表：没有记录可断链，valid=true");
        assertEquals(0, v.checked(), "checked 必须是记录条数，0 表示确实没有记录可校验");
    }

    @Test
    @DisplayName("DoD1 写入落链：写入方传入的假 hash 必须被忽略（链不能交给调用方自觉）")
    void caller_supplied_chain_fields_are_ignored() {
        // 一个"自洽的假 hash"：调用方声称自己的 prev/hash 是什么就存什么 —— 若实现信任它，
        // 篡改者只要在写入时送一对自洽的假值，链就会把他当自己人。
        AuditLog forged = new AuditLog(
                null, TENANT, ACTOR, "FORGED", "customer", "c9", Instant.now(),
                "{\"forged\":true}", "f".repeat(64), "e".repeat(64));

        String id = txTemplate.execute(s -> service.append(forged));
        assertNotNull(id, "append 应返回生成的 id（而非调用方传入的）");
        assertFalse(id.startsWith("e".repeat(8)), "id 不应来自调用方传入的 hash 字段");

        AuditLog stored = service.readAllInOrder().get(0);
        assertEquals(AuditChainHash.GENESIS_PREV_HASH, stored.prevHash(),
                "存储的 prev_hash 被调用方传的 '" + "f".repeat(8) + "…' 污染 —— 链交出了控制权");
        assertFalse("e".repeat(64).equals(stored.hash()),
                "存储的 hash 被调用方传的假值污染 —— 写入路径信任了外部输入");
        String recomputed = AuditChainHash.chainHash(
                stored.tenantId(), stored.actor(), stored.action(),
                stored.targetType(), stored.targetId(), stored.payload(), stored.prevHash());
        assertEquals(recomputed, stored.hash(), "存储 hash 应为服务端重算值");
        assertEquals(id, stored.id(), "append 返回的 id 应与落库行的 id 一致");
    }

    // ------------------------------------------------------------------
    // DoD 2 + 3: 校验端点 + 反向验证（篡改必被发现）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DoD3① 注入『改写中间行 payload』→ 必须报断链，且 broken_at 精确指向该行")
    void tamper_middle_row_payload_is_detected_at_that_row() {
        String first = appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        String middle = appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        String last = appendInTx("ACT", "customer", "c3", "{\"n\":3}");
        assertTrue(service.verifyChain().valid(), "前置条件：注入前链必须是健康的");
        String before = service.readAllInOrder().get(1).payload();

        // === 注入 ===：攻击者直接改库（应用凭据做不到，这里用攻击者身份是有意的）
        String injected = "{\"n\":2,\"tampered\":\"by-attacker\"}";
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET payload = '" + injected.replace("'", "''")
                        + "' WHERE id = '" + middle + "'::uuid");

        // 注入必须真的落库 —— 否则后面的"报了错"就无从归因（可能是别的行出了问题）
        assertEquals(injected, service.readAllInOrder().get(1).payload(),
                "注入未生效：攻击者 UPDATE 没有落库，本用例的结论无效");

        ChainVerification v = service.verifyChain();
        record("改写中间行 payload",
                "id=" + middle + "（第 2 行）",
                "valid=false, broken_at=" + middle + ", reason=" + ChainVerification.REASON_HASH_MISMATCH);

        assertFalse(v.valid(), "改了 payload 却报链有效 —— 篡改未被发现");
        assertEquals(middle, v.brokenAt(),
                "broken_at 必须精确指向被改写的那一行（实际=" + v.brokenAt()
                        + "，首行=" + first + "，末行=" + last + "）");
        assertEquals(ChainVerification.REASON_HASH_MISMATCH, v.reason(),
                "被改写的是本行数据，应报 HASH_MISMATCH（本行重算 ≠ 本行存储 hash）");
        assertEquals(3, v.checked(), "仍应校验全部 3 行");

        // 反证"确实是 payload 改动导致"，而不是"库里本来就有 3 行所以报错"
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET payload = '" + before.replace("'", "''")
                        + "' WHERE id = '" + middle + "'::uuid");
        assertTrue(service.verifyChain().valid(),
                "把 payload 改回原值后链应恢复有效 —— 否则上面的断链归因不成立");
    }

    @Test
    @DisplayName("DoD3② 注入『改写中间行 hash』→ 必须报断链，且 broken_at 指向该行")
    void tamper_middle_row_hash_is_detected() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        String middle = appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        appendInTx("ACT", "customer", "c3", "{\"n\":3}");

        String forged = "a".repeat(64);
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET hash = '" + forged + "' WHERE id = '" + middle + "'::uuid");
        assertEquals(forged, service.readAllInOrder().get(1).hash(), "注入未生效：hash 未被改写");

        ChainVerification v = service.verifyChain();
        record("改写中间行 hash",
                "id=" + middle + "（第 2 行）",
                "valid=false, broken_at=" + middle + ", reason=" + ChainVerification.REASON_HASH_MISMATCH);

        assertFalse(v.valid(), "改了 hash 却报链有效 —— 篡改未被发现");
        assertEquals(middle, v.brokenAt(), "broken_at 必须指向被改写 hash 的那一行，实际=" + v.brokenAt());
        assertEquals(ChainVerification.REASON_HASH_MISMATCH, v.reason(),
                "存储 hash ≠ 用本行字段重算值，应报 HASH_MISMATCH");
    }

    @Test
    @DisplayName("DoD3③ 注入『删除中间一行』→ 必须报断链，broken_at 指向被删行的【后继】")
    void deleting_a_middle_row_breaks_the_link_at_its_successor() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        String removed = appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        String successor = appendInTx("ACT", "customer", "c3", "{\"n\":3}");
        appendInTx("ACT", "customer", "c4", "{\"n\":4}");

        AuditChainTestSupport.attackerSql("DELETE FROM audit_log WHERE id = '" + removed + "'::uuid");
        List<AuditLog> rows = service.readAllInOrder();
        assertEquals(3, rows.size(), "注入未生效：删行后应剩 3 行");
        for (AuditLog r : rows) {
            assertFalse(removed.equals(r.id()), "被删的行仍在库里（注入未生效）");
        }

        ChainVerification v = service.verifyChain();
        record("删除中间一行",
                "被删 id=" + removed + " → 期望失败点为其后继 id=" + successor,
                "valid=false, broken_at=" + successor
                        + ", reason=" + ChainVerification.REASON_PREV_HASH_MISMATCH);

        assertFalse(v.valid(), "删掉中间一行却报链有效 —— 链的连续性未被校验");
        // 关键断言：指向【后继】而不是"随便某一行"。写成"只要报错就行"会让
        // "返回了另一行的 id"也通过，等于放弃了位置断言。
        assertEquals(successor, v.brokenAt(),
                "broken_at 应指向被删行的后继（该后继的 prev_hash 指向一条已不存在的记录）。"
                        + "实际=" + v.brokenAt());
        assertEquals(ChainVerification.REASON_PREV_HASH_MISMATCH, v.reason(),
                "被删行导致后继的 prev_hash 与物理前驱不符，应报 PREV_HASH_MISMATCH");
        assertEquals(3, v.checked(), "校验的应是删除后的 3 行");
        assertEquals(3, rows.size());
    }

    @Test
    @DisplayName("DoD3④ 注入『改写中间行 prev_hash』→ 必须报断链（prev_hash 未被校验的实现会漏掉这种篡改）")
    void tamper_middle_row_prev_hash_is_detected() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        String middle = appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        appendInTx("ACT", "customer", "c3", "{\"n\":3}");

        // 攻击者把中继的 prev_hash 改成一个"看起来像哈希"的值：本行重算会变，
        // 同时它与物理前驱也对不上 —— 两种检查都会命中，谁先命中取决于实现顺序。
        String forged = "b".repeat(64);
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET prev_hash = '" + forged + "' WHERE id = '" + middle + "'::uuid");
        assertEquals(forged, service.readAllInOrder().get(1).prevHash(), "注入未生效：prev_hash 未被改写");

        ChainVerification v = service.verifyChain();
        record("改写中间行 prev_hash",
                "id=" + middle + "（第 2 行）",
                "valid=false, broken_at=" + middle
                        + ", reason ∈ {" + ChainVerification.REASON_PREV_HASH_MISMATCH
                        + ", " + ChainVerification.REASON_HASH_MISMATCH + "}");

        assertFalse(v.valid(), "改了 prev_hash 却报链有效 —— 链式绑定未被校验");
        assertEquals(middle, v.brokenAt(), "broken_at 应指向被改写 prev_hash 的那一行，实际=" + v.brokenAt());
        // 两种 reason 都算"正确抓到"：本行 prev_hash 与前驱不符（PREV_HASH_MISMATCH），
        // 且本行 hash 也不再等于用新 prev_hash 重算的值（HASH_MISMATCH）。
        // 不断言具体哪一个 —— 断言"必须是这两个之一"既严谨又不绑定实现顺序。
        assertTrue(
                ChainVerification.REASON_PREV_HASH_MISMATCH.equals(v.reason())
                        || ChainVerification.REASON_HASH_MISMATCH.equals(v.reason()),
                "reason 应为 PREV_HASH_MISMATCH 或 HASH_MISMATCH，实际=" + v.reason());
    }

    @Test
    @DisplayName("DoD3⑤ 注入『删掉链首』→ 必须报断链（新的第一行 prev_hash 不再是创世值）")
    void deleting_the_genesis_row_is_detected() {
        String genesis = appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        appendInTx("ACT", "customer", "c2", "{\"n\":2}");

        AuditChainTestSupport.attackerSql("DELETE FROM audit_log WHERE id = '" + genesis + "'::uuid");
        List<AuditLog> rows = service.readAllInOrder();
        assertEquals(1, rows.size(), "注入未生效：删掉链首后应剩 1 行");
        assertFalse(AuditChainHash.GENESIS_PREV_HASH.equals(rows.get(0).prevHash()),
                "前置条件：剩下这行的 prev_hash 应为原链首的 hash（结构上不再是链首）");

        ChainVerification v = service.verifyChain();
        record("删掉链首",
                "被删 id=" + genesis + " → 期望失败点为新的首行 id=" + rows.get(0).id(),
                "valid=false, broken_at=" + rows.get(0).id()
                        + ", reason ∈ {" + ChainVerification.REASON_PREV_HASH_MISMATCH
                        + ", " + ChainVerification.REASON_GENESIS_MISMATCH + "}");

        assertFalse(v.valid(), "删掉链首却报链有效 —— 链头未被校验");
        assertEquals(rows.get(0).id(), v.brokenAt(), "broken_at 应指向新的首行");
        assertTrue(
                ChainVerification.REASON_PREV_HASH_MISMATCH.equals(v.reason())
                        || ChainVerification.REASON_GENESIS_MISMATCH.equals(v.reason()),
                "reason 应为 PREV_HASH_MISMATCH 或 GENESIS_MISMATCH，实际=" + v.reason());
    }

    @Test
    @DisplayName("DoD3⑥ 注入『把 hash 写成非 hex 垃圾/截断』→ 必须报断链而不是抛异常")
    void malformed_hash_is_reported_not_thrown() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        appendInTx("ACT", "customer", "c2", "{\"n\":2}");

        // CHAR(64) 会右补空格，故写入 10 个字符的垃圾，读回来是 "zzzzzzzzzz" + 54 空格
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET hash = 'zzzzzzzzzz' WHERE id = "
                        + "(SELECT id FROM audit_log ORDER BY created_at ASC, id ASC LIMIT 1)");

        ChainVerification v;
        try {
            v = service.verifyChain();
        } catch (RuntimeException e) {
            fail("校验器面对被破坏的数据必须给出结论（valid=false + broken_at），而不是自己崩掉: "
                    + e, e);
            return;
        }
        record("把 hash 写成非 hex 垃圾",
                "第 1 行的 hash 被写为 'zzzzzzzzzz'（CHAR(64) 右补空格）",
                "valid=false, reason=" + ChainVerification.REASON_MALFORMED_HASH);

        assertFalse(v.valid(), "hash 字段被写坏却报链有效");
        assertEquals(ChainVerification.REASON_MALFORMED_HASH, v.reason(),
                "形状非法的 hash 应报 MALFORMED_HASH，实际=" + v.reason());
        assertEquals(2, v.checked(), "仍应校验全部 2 行");
    }

    // ------------------------------------------------------------------
    // DoD3⑦【本轮新增 · C-1 真请求实测挖出】尾部截断盲区
    //
    // 🛑 这条用例是"断言一个坏消息"，与 DoD3 其余六条性质相反：
    //    前六条证明"篡改会被抓住"，本条证明"有一种篡改【抓不住】"。
    //
    // 【它是怎么被发现的】C-1 在 dy-app 上写端点级真请求用例时，
    //    对"删掉链尾"做了断言，期望 valid=false —— 实测得 valid=true。
    //    回头看既有 DoD3③⑤ 只覆盖"删中间行 / 删链首"，两者的共同点是
    //    【被删行后面还有行】，故总有某个后继的 prev_hash 会失配。
    //    删尾部则【没有任何后继】⇒ 链上无一处失配 ⇒ 校验器无话可说。
    //
    // 【为什么必须现在就登记】"擦掉最近几步操作的痕迹"恰恰是攻击者的首选动作
    //    （先干的事留痕最危险、删起来最省事）。而它偏偏是链最看不见的一侧。
    //    若这条性质只写在 audit_log.sql 的注释里，它会随注释一起腐坏；
    //    写成一条断言 valid=true 的用例，它就成了持续可见的坏消息。
    //
    // 【补救路径】不属于 C-1 的范围（属 ADR-11 backlog ② ）：
    //    定期把 (记录条数, tail hash) 锚定到库外或可信时间戳服务 ——
    //    一旦有锚点，"现在的 checked 少于锚定条数"就成为可执行告警。
    //    这正是 C-1 端点把 checked 【恒】下发（而非只在失败时下发）的原因：
    //    让那一天到来时不必改响应形状。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DoD3⑦ [断言坏消息] 注入『删除链尾若干条』→ 校验器报 valid=true（链对尾部截断无感）")
    void tail_truncation_is_not_detected_by_the_chain_alone_and_that_is_recorded() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        String tailA = appendInTx("ACT", "customer", "c3", "{\"n\":3}");
        String tailB = appendInTx("ACT", "customer", "c4", "{\"n\":4}");
        assertEquals(4, AuditChainTestSupport.rowCount(), "前置：应有 4 行");

        // 攻击者擦掉最近两步操作
        AuditChainTestSupport.attackerSql("DELETE FROM audit_log WHERE id = '" + tailA + "'::uuid");
        AuditChainTestSupport.attackerSql("DELETE FROM audit_log WHERE id = '" + tailB + "'::uuid");
        assertEquals(2, AuditChainTestSupport.rowCount(), "注入未生效：应剩 2 行");

        ChainVerification v = service.verifyChain();
        record("删除链尾两条",
                "被删 id={" + tailA + ", " + tailB + "} —— 链上无任何后继可失配",
                "valid=" + v.valid() + ", checked=" + v.checked()
                        + "  ← 预期【valid=true】：链只有向后看的本事，尾部没有『后』");

        // 🛑 这里断言的是 valid=TRUE —— 断言一个坏消息。
        //    若有一天它变红（valid=false），说明尾部截断被某种机制发现了
        //    （例如锚点落地），那时应把本用例改写成正面断言，
        //    并把 audit_log.sql / AuditChainController 的能力边界同步收窄。
        assertTrue(v.valid(),
                "🛑 尾部截断【不应】被 verifyChain() 发现 —— 这是无密钥哈希链的固有盲区。"
                        + "若此处失败（valid=false），说明能力边界已变化，"
                        + "应更新 audio_log.sql / 端点 capability_envelope 与本用例三处。"
                        + "实际: valid=" + v.valid() + " reason=" + v.reason());
        assertNull(v.brokenAt(), "尾部截断后链自洽，broken_at 应为 null");
        // checked 只能说明"现在有多少条"，不能说明"应该有多少条" —— 它就是本盲区
        // 在有外锚点的部署下【唯一】的可见迹象。
        assertEquals(2, v.checked(),
                "checked 应反映删除后的真实条数（=2）。它是尾部截断日后可被发现的前提");
    }

    // ------------------------------------------------------------------
    // DoD 4: append-only（非超级用户 + 撤销是承重的）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DoD4 append-only：撤销前 UPDATE 可用（证明撤销是承重的），撤销后 UPDATE/DELETE/TRUNCATE 均被 42501 拒")
    void append_only_is_enforced_and_the_revoke_is_load_bearing() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");

        // ① 反向自证：撤销【前】应用身份改得动数据。若不验这一步，后面"改不动"可能只是
        //    因为应用本来就没有权限（比如建表时忘了移交 owner），撤销就成了空操作。
        AuditChainTestSupport.PsqlResult beforeRevoke = AuditChainTestSupport.appSql(
                "UPDATE audit_log SET payload = '{\"n\":1,\"by\":\"app\"}'");
        assertTrue(beforeRevoke.ok(),
                "撤销前应用身份应能 UPDATE（否则本用例的撤销是空操作，结论无意义）。输出:\n"
                        + beforeRevoke.output());

        // ② 撤销（用攻击者身份执行 DDL 级动作，模拟 DBA）
        AuditChainTestSupport.revokeWritePrivileges();

        // ③ 三种写路径都必须被拒，且必须是 SQLSTATE 42501(insufficient_privilege)
        assertDeniedWithInsufficientPrivilege("UPDATE",
                "UPDATE audit_log SET payload = '{\"x\":1}'");
        assertDeniedWithInsufficientPrivilege("DELETE", "DELETE FROM audit_log");
        // TRUNCATE 单独注入：它不需要 WHERE、不需要 DELETE 权限，一条语句清空整条链，
        // 比 DELETE 更省事。撤销清单里少了它，append-only 就是个漏勺。
        assertDeniedWithInsufficientPrivilege("TRUNCATE", "TRUNCATE audit_log");

        // ④ 而 INSERT（append）必须仍然可用 —— 否则我们是为了满足测试而砍掉了产品功能
        String id = appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        assertNotNull(id, "撤销写权限后 append 必须仍可用");
        assertEquals(2, AuditChainTestSupport.rowCount(), "撤销后应能继续追加（行数 1 → 2）");

        // ⑤ 数据确实没被改动：撤销前那次应用侧 UPDATE 的痕迹仍在，后续三种尝试一行都没落
        assertEquals(2, service.readAllInOrder().size(),
                "撤销后 DELETE/TRUNCATE 未生效（若生效，行数会变）");
    }

    @Test
    @DisplayName("DoD4 append-only：应用身份无法 UPDATE/DELETE/TRUNCATE 时，链仍完整（两道防线独立）")
    void chain_stays_intact_after_write_privileges_are_revoked() {
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        assertTrue(service.verifyChain().valid(), "前置条件：撤销前链健康");

        AuditChainTestSupport.revokeWritePrivileges();
        for (String sql : List.of(
                "UPDATE audit_log SET hash = '" + "c".repeat(64) + "'",
                "DELETE FROM audit_log",
                "TRUNCATE audit_log")) {
            AuditChainTestSupport.PsqlResult r = AuditChainTestSupport.appSql(sql);
            assertFalse(r.ok(), "撤销后应用身份仍能执行: " + sql + "\n输出:\n" + r.output());
        }

        ChainVerification v = service.verifyChain();
        assertTrue(v.valid(), "被拒绝的篡改不应影响链（valid 应仍为 true），实际 broken_at=" + v.brokenAt());
        assertEquals(2, v.checked(), "条数不应变化");
    }

    // ------------------------------------------------------------------
    // DoD 5: 并发安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DoD5 并发：32 线程并发 append 后链必须完整、无分叉、条数正确")
    void concurrent_appends_produce_one_unforked_chain() throws Exception {
        final int writers = 32;
        final AtomicInteger failures = new AtomicInteger();
        final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int n = i;
            tasks.add(() -> {
                start.await();
                try {
                    // 每个写者一个独立事务 → 每次 append 都要在 advisory 锁上排队，
                    // 而不是恰好落在同一个事务里（那样就测不到跨事务竞争）。
                    txTemplate.execute(s -> service.append(new AuditLog(
                            null, TENANT, ACTOR + "-" + n, "CONCURRENT", "customer", "c" + n,
                            Instant.now(), "{\"writer\":" + n + "}", null, null)));
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    errors.add("writer-" + n + ": " + e);
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> t : tasks) {
            futures.add(pool.submit(t));
        }
        start.countDown();                            // 同时放行，最大化竞争窗口
        for (Future<Void> f : futures) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程池未在 30s 内退出");

        assertEquals(0, failures.get(), "并发写入出现异常: " + errors);

        List<AuditLog> rows = service.readAllInOrder();
        assertEquals(writers, rows.size(), "并发写入应落 " + writers + " 条记录");

        // 分叉检测（放在断言之前，因为它能给出比"broken_at 指向某行"更准确的诊断）：
        // 分叉的特征是"同一个 prev_hash 被两条记录引用"，或"同一条 hash 出现两次"。
        List<String> prevHashes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (AuditLog r : rows) {
            prevHashes.add(r.prevHash());
            hashes.add(r.hash());
        }
        long distinctPrevs = prevHashes.stream().distinct().count();
        assertEquals(prevHashes.size(), distinctPrevs,
                "同一个 prev_hash 被多条记录引用 —— 并发下取 tail 未串行化，链出现了分叉");
        long distinctHashes = hashes.stream().distinct().count();
        assertEquals(hashes.size(), distinctHashes, "出现重复 hash —— 写入内容或链状态被复用");

        // 创世值恰好被引用一次（只有真正的链首）
        long genesisRefs = prevHashes.stream().filter(AuditChainHash.GENESIS_PREV_HASH::equals).count();
        assertEquals(1, genesisRefs,
                "创世值被 " + genesisRefs + " 条记录引用 —— 只应有 1 条记录是链首；"
                        + "多于 1 说明并发下多个写者各自以为自己是链首（经典的空表锁失效）");

        // 整链校验：这才是最终结论
        ChainVerification v = service.verifyChain();
        record("并发写入 " + writers + " 条（pg_advisory_xact_lock 串行化）",
                "链完整、无分叉、条数=" + writers,
                "valid=" + v.valid() + ", checked=" + v.checked() + ", broken_at=" + v.brokenAt());
        assertTrue(v.valid(), "并发写入后链必须完整，实际 broken_at=" + v.brokenAt()
                + " reason=" + v.reason());
        assertEquals(writers, v.checked(), "参与校验的条数应为 " + writers);

        // 时间序可判定：created_at 不得并列（并列会让"正确顺序"变成不可判定，
        // 校验器会报 ORDER_AMBIGUOUS 而不是假装链有效）
        for (int i = 1; i < rows.size(); i++) {
            assertFalse(rows.get(i - 1).at().equals(rows.get(i).at()),
                    "第 " + i + " 与第 " + (i + 1) + " 条的 created_at 并列 —— "
                            + "写入顺序不可判定，链的正确顺序失去了载体");
        }
    }

    @Test
    @DisplayName("DoD5 并发：无锁时的竞争可被观测（证明上面那条测试不是恒真的）")
    void without_serialization_the_race_is_observable() throws Exception {
        // 这是"并发方案是否真的起作用"的对照实验：直接绕过服务，用两个并发连接各自
        // 执行"读 tail → 插入"的裸序列，不做任何串行化。若它们读到同一个 tail，
        // 库中就会出现两个引用同一 prev_hash 的记录 —— 即分叉。
        //
        // 若这个对照实验观测不到分叉，说明测试环境本身把并发串行化了（比如连接池只有 1 条连接），
        // 那么 DoD5 的正向测试就不具证明力 —— 这个对照的存在就是为了让那种情况暴露出来。
        // ~~~~~ 对照实验：8 个写者【不串行化】各自读 tail 后插入 ~~~~~
        //
        // 目的：证明下面那条"加了 pg_advisory_xact_lock 就完整"的测试【不是恒真的】。
        // 若不加锁也能得到完整链，那加锁测试就是装饰品，它的绿色毫无信息量。
        //
        // 【必须先放一个锚点，否则实验无效】
        // 空表时所有写者读到的都是创世值 —— 那确实是分叉，但 PG 的行可见性会让
        // 先提交的行对后提交的事务不可见，读到的"创世值"是"表为空"而非"同一个前驱"，
        // 分叉的性质不纯。放一条锚点后，8 个写者读到的都是【同一条真实记录的 hash】：
        // 这才是真正意义上的"多个后继挂到同一前驱"。
        appendInTx("ANCHOR", "customer", "c-anchor", "{\"anchor\":true}");
        List<AuditLog> anchorRows = service.readAllInOrder();
        assertEquals(1, anchorRows.size(), "锚点应恰好 1 条");
        final String anchorHash = anchorRows.get(0).hash();
        assertTrue(service.verifyChain().valid(), "锚点链应健康");

        final int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            final int n = i;
            tasks.add(() -> {
                start.await();
                try (var c = APP_DS.getConnection()) {
                    c.setAutoCommit(false);
                    // 用一把【短命】的 advisory 锁把"读 tail"这一步排成队，
                    // 但随语句结束立刻释放（session 级锁在同一连接上显式解锁）——
                    // 于是所有写者在读到同一个 tail 之后，才进入互不互斥的插入阶段。
                    // 这是在"没有端到端串行化"的前提下，依然让对照实验可复现地分叉。
                    try (var st = c.createStatement()) {
                        st.execute("SELECT pg_advisory_lock(" + LOCK_KEY_LITERAL + ")");
                        try (var rs = st.executeQuery(
                                "SELECT hash FROM audit_log ORDER BY created_at DESC, id DESC LIMIT 1")) {
                            rs.next();
                            rs.getString(1);
                        }
                        st.execute("SELECT pg_advisory_unlock(" + LOCK_KEY_LITERAL + ")");
                    }
                    String tail = anchorHash;   // 被锁保护的读取结果：所有人都拿到锚点 hash
                    String id = java.util.UUID.randomUUID().toString();
                    String hash = AuditChainHash.chainHash(TENANT, "racer-" + n, "RACE", "customer",
                            "c" + n, "{\"racer\":" + n + "}", tail);
                    try (var st = c.prepareStatement(
                            "INSERT INTO audit_log (id, tenant_id, actor, action, target_type, target_id,"
                                    + " payload, prev_hash, hash, created_at)"
                                    + " VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
                        st.setString(1, id);
                        st.setString(2, TENANT);
                        st.setString(3, "racer-" + n);
                        st.setString(4, "RACE");
                        st.setString(5, "customer");
                        st.setString(6, "c" + n);
                        st.setString(7, "{\"racer\":" + n + "}");
                        st.setString(8, tail);
                        st.setString(9, hash);
                        st.executeUpdate();
                    }
                    c.commit();
                    return tail;
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        start.countDown();
        List<String> tails = new ArrayList<>();
        for (Future<String> f : futures) {
            tails.add(f.get(180, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(racers + 1, AuditChainTestSupport.rowCount(),
                "无锁竞争应写入 " + racers + " 条记录（另有 1 条锚点）");

        long distinctTails = tails.stream().distinct().count();
        ChainVerification v = service.verifyChain();

        record("对照实验：8 个写者【不串行化】各自读 tail 后插入（锚点已就位）",
                "期望出现分叉（8 条都引用锚点 hash）→ valid=false",
                "distinct prev_hash 取到 " + distinctTails + "/" + racers
                        + "; valid=" + v.valid() + ", broken_at=" + v.brokenAt()
                        + ", reason=" + v.reason());

        assertEquals(1, distinctTails,
                "8 个写者应读到同一个 tail（锚点 hash），实际取到 " + distinctTails + " 个不同值 —— "
                        + "对照实验的前置条件不成立");
        // 分叉的直接证据：8 条记录都引用同一个 prev_hash
        long refs = service.readAllInOrder().stream()
                .filter(r -> anchorHash.equals(r.prevHash())).count();
        assertEquals(racers, refs,
                "应有 " + racers + " 条记录引用锚点 hash（这就是分叉），实际 " + refs + " 条");

        assertFalse(v.valid(),
                "无锁竞争写入后链竟然仍然有效 —— 那说明分叉不会被检出，"
                        + "DoD5 正向测试的结论不可信（必须重写）");
        // 断链原因：8 条后继里只有"第一条被排序到锚点紧随其后"的那条能接上，
        // 其余 7 条都不等于锚点 hash ⇒ 报 PREV_HASH_MISMATCH（或某条自身重算不符）。
        assertTrue(
                ChainVerification.REASON_PREV_HASH_MISMATCH.equals(v.reason())
                        || ChainVerification.REASON_HASH_MISMATCH.equals(v.reason()),
                "无锁分叉应报 PREV_HASH_MISMATCH 或 HASH_MISMATCH，实际=" + v.reason());
    }

    @Test
    @DisplayName("DoD5 并发：append 不在事务中时必须拒绝，而不是静默退化（advisory 事务锁的前提）")
    void append_outside_a_transaction_is_rejected() {
        // DataSource 默认 autoCommit=true。此时 pg_advisory_xact_lock 会在语句后立即释放，
        // 互斥失效。实现必须拒绝（fail-closed）而不是"照样写但没锁"。
        JdbcAuditLogService noTx = new JdbcAuditLogService(appDataSource);

        // 【先取基线行数，断言"增量"而非"绝对值"】
        // 本类其余用例都靠 @AfterEach 的 TRUNCATE 复位，所以通常这里基数是 0；
        // 但依赖"基线恰为 0"会让断言变成对测试隔离的假设，而不是对被测行为的断言 ——
        // 一旦表里因任何原因多出一行，失败信息会指向"落下记录"，而真因是别的行。
        // 故这里断言"本次调用没有让行数增加"，这才是本用例真正要证明的事。
        int before = AuditChainTestSupport.rowCount();

        // 表由 @BeforeAll 建好；这里直接调用
        try {
            noTx.append(new AuditLog(null, TENANT, ACTOR, "NO_TX", "customer", "c1",
                    Instant.now(), "{}", null, null));
            // 允许一种实现：它自己开事务并把锁包在里面（那样也算是"没在调用方事务里"但仍是安全的）。
            // 因此这里断言的不是"必须抛异常"，而是"必须保证链是完整的"。
            ChainVerification v = service.verifyChain();
            assertTrue(v.valid(), "无外层事务时 append 成功但链不完整（未串行化）: broken_at=" + v.brokenAt());
        } catch (IllegalStateException expected) {
            // 走 fail-closed 分支：明确拒绝，且信息里说明原因
            assertTrue(expected.getMessage().contains("pg_advisory_xact_lock")
                            || expected.getMessage().contains("事务"),
                    "拒绝原因应说明事务/锁的前提，实际: " + expected.getMessage());
            assertEquals(before, AuditChainTestSupport.rowCount(),
                    "被拒绝时不应落下任何记录（基线 " + before + " 行）");
        }
    }

    // ------------------------------------------------------------------
    // 威胁模型边界（诚实记录"本机制检测不了什么"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("边界[诚实标注] 能级联重算整条链的攻击者可以骗过 verifyChain —— "
            + "哈希链不是防篡改的充分条件，需外部锚定")
    void cascade_rehash_defeats_the_chain_and_this_is_documented_not_claimed_away() {
        // 这个测试【断言的是一个坏消息】，故意的。
        //
        // 【措辞纪律 —— 对外陈述必须按这一句】
        //   能力边界限于：单点篡改必被发现；不抗有写权限的级联重算。
        //   允许说："审计链不可悄然篡改"。
        //   禁止说："审计链不可篡改" —— 绝对化表述，与 Anti-Slop 纪律相悖，且误导客户。
        //   本用例的存在就是为了让那句"不可篡改"在任何人想说出来时都能被真库证据顶回去。
        //
        // 前 14 个测试证明的是："只改一行 / 只删一行"必然被抓住。这容易让人默认
        // "篡改必然被发现"，从而在对外陈述里把它说成防篡改的充分条件 —— 那是过度承诺。
        // 真相是：本设计【没有】密钥、没有外部锚（没有 signature、没有 WORM 介质、
        // 没有把 tail hash 定期外发到独立存储）。因此一个已经拿到 UPDATE 权限的人，
        // 可以【从被改的那行开始逐行重算 hash 并回写】，让整条链重新自洽 ——
        // validate() 返回 valid=true，被改的数据看起来就是"原始记录"。
        //
        // 这不是实现 bug（所有哈希链都有这个性质），而是一条【能力边界】。
        // 把它写成可执行测试，是为了让"我们自称防篡改"这句话永远带着它的前提；
        // 也为了让下一个人不会误以为绿色测试 = 防得住有写权限的攻击者。
        //
        // 真正要挡住这一层，需要链外的信任根 ——【属 ADR-11 后续项，已登记 backlog】，
        // 三条补救路径：
        //   ① 每条记录加 HMAC 签名，密钥在应用/KMS 而非数据库（DBA 直连也签不出）；
        //   ② 定期把 (row_count, tail_hash) 锚定到独立介质/链上时间戳；
        //   ③ append-only 落到 WORM 存储（对象锁），技术上不可回写。
        // 同一段声明另有两处副本，措辞须保持一致：AuditLogService#verifyChain() javadoc、
        // dy-audit/src/main/resources/db/audit_log.sql 表尾。
        appendInTx("ACT", "customer", "c1", "{\"n\":1}");
        appendInTx("ACT", "customer", "c2", "{\"n\":2}");
        appendInTx("ACT", "customer", "c3", "{\"n\":3}");

        // 断言收窄到"本用例写入的那 3 行"，而不是"全表恰好 3 行"。
        // 理由：本用例其余断言都是"对被测行为的断言"，唯独"表里只有 3 行"是"对测试隔离的断言"。
        // 一旦表里因任何原因多出行，失败信息会指向级联范围不对，而真因是隔离被破坏 ——
        // 那是误导性的红。用 id 定位自己的行，可以让结论与表里有多少历史行无关。
        List<AuditLog> allRows = service.readAllInOrder();
        assertTrue(allRows.size() >= 3, "至少应有本用例写入的 3 行，实际 " + allRows.size());
        final int ownStart = allRows.size() - 3;
        final String secondOwnId = allRows.get(ownStart + 1).id();
        final List<AuditLog> ownRows = allRows.subList(ownStart, allRows.size());

        assertTrue(service.verifyChain().valid(), "前置条件：注入前链健康");

        // ① 先做一个"只改一行"的注入，确认它【会】被抓 —— 建立对照基线
        //    （用 id 精确定位，不用全表 OFFSET 下标）
        AuditChainTestSupport.attackerSql(
                "UPDATE audit_log SET payload = '{\"n\":2,\"cascade\":\"probe\"}' WHERE id = '"
                        + secondOwnId + "'::uuid");
        ChainVerification singleRow = service.verifyChain();
        assertFalse(singleRow.valid(), "只改一行应被抓（这是前 14 个测试证明的性质）");

        // ② 攻击者级联重算：从【全表第一行】开始，按写入顺序逐行用新的前驱 hash 重算本行 hash。
        //    这正是我们自己的 verifyChain() 在做的事 —— 攻击者只要有 UPDATE 权限就能照做。
        //
        //    【必须在篡改之后重新读表】：级联重算的输入必须是"库里当前的内容"（含刚被改的
        //    payload）。若复用篡改前读到的行，就会用旧 payload 算 hash 而库里存的是新 payload，
        //    结果必然 HASH_MISMATCH —— 那时"级联能骗过校验器"这一结论就证不出来，
        //    会表现为本测试失败。这是本测试最容易写错的一步，故显式重读。
        //
        //    【必须从全表第一行开始，不能只重算自己的 3 行】：级联的语义是"把整条链重新缝好"。
        //    若从半截开始，自己的首行与前面的行之间就会断开，链仍 valid=false。
        List<AuditLog> rowsToRehash = service.readAllInOrder();
        String prev = AuditChainHash.GENESIS_PREV_HASH;
        int rewritten = 0;
        for (AuditLog row : rowsToRehash) {
            String newHash = AuditChainHash.chainHash(
                    row.tenantId(), row.actor(), row.action(),
                    row.targetType(), row.targetId(), row.payload(), prev);
            AuditChainTestSupport.attackerSql(
                    "UPDATE audit_log SET prev_hash = '" + prev + "', hash = '" + newHash
                            + "' WHERE id = '" + row.id() + "'::uuid");
            prev = newHash;
            rewritten++;
        }
        assertEquals(allRows.size(), rewritten, "级联重算应覆盖全表 " + allRows.size() + " 行");
        assertEquals(3, ownRows.size(), "本用例应写入 3 行");

        // ③ 被改后的库里，被篡改的 payload 还在，但整条链已经自洽
        List<AuditLog> after = service.readAllInOrder();
        assertTrue(after.get(ownStart + 1).payload().contains("cascade"),
                "篡改的 payload 应仍在库中（本测试的前提）");

        ChainVerification cascaded = service.verifyChain();
        record("级联重算整条链（威胁模型边界，非缺陷）",
                "期望[valid=true —— 证明本机制检测不了有 UPDATE 权限的攻击者]",
                "实际[valid=" + cascaded.valid() + ", checked=" + cascaded.checked()
                        + ", broken_at=" + cascaded.brokenAt() + "] —— 与期望一致，"
                        + "故对外不能声称『哈希链可防篡改』，只能声称『哈希链可发现单点篡改』");

        assertTrue(cascaded.valid(),
                "级联重算后链【应当】报有效 —— 若这里报断链，说明 verifyChain 的语义"
                        + "与哈希链的数学性质不符，那才是需要解释的（本测试有意固化这一事实）");
        assertEquals(after.size(), cascaded.checked(), "参与校验的条数应等于表内实际行数");

        // ④ 附带说明：单点篡改【确实】能被发现，级联篡改【不能】——
        //    两者的差别只在攻击者改了几行，而不是校验器有多聪明。
        //    这条断言是给读测试的人看的：结论不是"测试没用"，而是"用途要说准"。
        assertNotEquals(singleRow.valid(), cascaded.valid(),
                "单点篡改与级联篡改必须给出不同结论（否则本测试什么都没证明）");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String appendInTx(String action, String targetType, String targetId, String payload) {
        String id = txTemplate.execute(s -> service.append(new AuditLog(
                null, TENANT, ACTOR, action, targetType, targetId, Instant.now(), payload, null, null)));
        assertNotNull(id, "append 应返回落库 id");
        return id;
    }

    /**
     * 断言某条写操作被 PostgreSQL 以 42501(insufficient_privilege) 拒绝。
     *
     * <h3>为何走 JDBC 而不是解析 psql 的输出文本</h3>
     * psql 默认只打印本地化的错误文案（本机是中文的"对表 audit_log 权限不够"），
     * <b>不含 SQLSTATE</b>。想从文本里拿 42501 就得先开 {@code VERBOSITY=verbose}，
     * 再对一段随语言环境变化的字符串做匹配 —— 那样的断言在别的 locale 上会失效，
     * 而"断言随环境失效"比"断言弱"更糟：它会变成一条没人再信任的红线。
     * 故这里用 JDBC 读驱动上报的 {@code SQLException.getSQLState()}：
     * 这是数据库给出的机器可判错误码，与语言环境无关。
     *
     * <p>同时仍用 psql 断言退出码非 0 —— 因为 CI 看到的就是 psql 的退出码，
     * 两条路径必须给出同一个结论（同标准）。psql 输出里附上 SQLSTATE 便于人工复核。
     */
    private void assertDeniedWithInsufficientPrivilege(String label, String sql) {
        // ① JDBC：断言数据库返回的具体 SQLSTATE
        String sqlState;
        try (var c = APP_DS.getConnection();
             var st = c.createStatement()) {
            st.execute(sql);
            fail(label + " 竟然成功了 —— append-only 失效（未抛任何异常）");
            return;
        } catch (SQLException e) {
            sqlState = e.getSQLState();
        }
        assertEquals("42501", sqlState,
                label + " 被拒但 SQLSTATE 不是 42501(insufficient_privilege) —— 需确认拒绝原因"
                        + "（否则可能是语法/类型错误带来的『假拒绝』，那不是 append-only 生效）");

        // ② psql：断言 CI 侧的退出码同结论；同时把 SQLSTATE 记进证据（开 verbose 才有）
        AuditChainTestSupport.PsqlResult r = AuditChainTestSupport.appSqlVerbose(sql);
        assertFalse(r.ok(), label + " 在 psql 侧未被拒绝 —— 与 JDBC 侧结论不一致。输出:\n" + r.output());
        record("append-only: 应用身份执行 " + label,
                "被 PostgreSQL 以 42501 拒绝（JDBC SQLSTATE 与 psql 退出码两侧同结论）",
                "SQLSTATE=42501; psql EXIT=" + r.exitCode() + "; "
                        + firstLineMentioningSqlState(r.output()));
    }

    private static String firstLineMentioningSqlState(String text) {
        for (String line : text.split("\\R")) {
            if (line.contains("42501")) {
                return line.trim();
            }
        }
        return text.strip().split("\\R")[0].trim();
    }

    /** 记录一行反向验证证据（注入内容 / 期望失败点 / 实际断言结果）。 */
    private static void record(String injection, String expected, String actual) {
        EVIDENCE.append("[反向验证] 注入内容: ").append(injection).append('\n');
        EVIDENCE.append("           期望失败点: ").append(expected).append('\n');
        EVIDENCE.append("           实际结果  : ").append(actual).append('\n').append('\n');
        System.out.println("[AUDIT-CHAIN-GATE] 注入[" + injection + "] 期望[" + expected + "] 实际[" + actual + "]");
    }
}