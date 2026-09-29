package com.diaoyuanyun.dy.config.it;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.config.cache.ConfigCacheKey;
import com.diaoyuanyun.dy.config.domain.ConfigChange;
import com.diaoyuanyun.dy.config.domain.ConfigSlot;
import com.diaoyuanyun.dy.config.repository.JdbcConfigHistoryRepository;
import com.diaoyuanyun.dy.config.repository.JdbcConfigService;
import com.diaoyuanyun.dy.config.repository.JdbcConfigSlotRepository;
import com.diaoyuanyun.dy.config.validation.DefaultConfigValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 配置真相源【真实 PostgreSQL 端到端】门禁 —— DoD A5 / ADR-08。
 *
 * <h2>断言对象是真实数据库行为，不是本类自己写的常量</h2>
 * 断言的落点：查询返回的<b>真实行数</b>、写入被拒的<b>真实 SQLSTATE</b>、
 * {@code pg_class} / {@code pg_policies} 里的<b>真实元数据</b>、
 * 历史表里<b>真实落库的行</b>。没有任何一条断言在核对本类的字面量。
 *
 * <h2>为何必须用非超级用户 dy_app</h2>
 * PostgreSQL 中超级用户总是绕过 RLS。用 {@code postgres} 连接跑隔离断言会全部
 * "假通过"。故业务连接固定 {@code dy_app}，且 {@code @BeforeAll} 第一件事就是
 * 自证"当前不是超级用户"。
 */
class ConfigTruthSourceIT {

    private static final String TENANT_A = ConfigGateSupport.TENANT_A;
    private static final String TENANT_B = ConfigGateSupport.TENANT_B;
    private static final String TENANT_EMPTY = ConfigGateSupport.TENANT_EMPTY;

    private static DataSource appDataSource;
    private static JdbcTemplate jdbc;
    private static JdbcConfigService service;
    private static JdbcConfigSlotRepository slots;

    private static final String KEY_THRESHOLD = "cfg:adherence.pass_threshold";
    private static final String KEY_CAPTURE_MODE = "cfg:band.capture_mode";
    private static final String KEY_MISSING = "cfg:does.not.exist";

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    static void provision() throws Exception {
        if (ConfigGateSupport.gateDisabled()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "配置真库门禁被显式跳过: -Ddy.config.gate.skip=true");
        }

        String provisionLog = ConfigGateSupport.provisionRealDatabase();
        System.out.println("[CONFIG-GATE] provision:\n" + provisionLog);

        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:postgresql://" + ConfigGateSupport.host() + ":" + ConfigGateSupport.port()
                        + "/" + ConfigGateSupport.DB,
                ConfigGateSupport.APP_USER, ConfigGateSupport.APP_PASSWORD, true);
        ds.setAutoCommit(true);
        appDataSource = ds;
        jdbc = new JdbcTemplate(ds);

        slots = new JdbcConfigSlotRepository(ds);
        service = new JdbcConfigService(
                slots, new JdbcConfigHistoryRepository(ds), new DefaultConfigValidator(), ds);

        try {
            String whoAmI = jdbc.queryForObject("select current_user", String.class);
            assertEquals(ConfigGateSupport.APP_USER, whoAmI,
                    "门禁必须用非超级用户执行；实际连接用户=" + whoAmI);
            Boolean isSuper = jdbc.queryForObject(
                    "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
            assertEquals(Boolean.FALSE, isSuper,
                    "当前连接是超级用户 → 会绕过 RLS → 所有隔离断言将『假通过』");
        } catch (RuntimeException e) {
            throw ConfigGateSupport.unreachable(e);
        }
    }

    @BeforeEach
    void resetSession() {
        Object user = jdbc.queryForObject("select current_user", String.class);
        assertEquals(ConfigGateSupport.APP_USER, user, "连接身份被改动，隔离断言将失去证明力");
        // 复位会话级 GUC：把 app.tenant_id 退回"未定义"状态，使各例起点严格一致。
        jdbc.execute("RESET app.tenant_id");
        jdbc.execute("RESET app.actor");
        jdbc.execute("RESET app.config.op");
    }

    // ------------------------------------------------------------------
    // 1) 46 条可读 + #42 空号 + #47 预留缺席
    // ------------------------------------------------------------------

    @Test
    @DisplayName("声明表：46 条非空、编号恰为 {1..41, 43..46, 48}、#42 与 #47 均不存在")
    void slots_are_46_and_numbers_42_and_47_are_absent() {
        List<ConfigSlot> all = slots.findAll();
        Set<Long> numbers = new LinkedHashSet<>();
        all.forEach(s -> numbers.add(s.getConfigNo()));

        assertEquals(46, all.size(), "声明条数应为 46（#1~#41 + #43~#46 + #48），实际=" + all.size());

        Set<Long> expected = new LinkedHashSet<>();
        for (long i = 1; i <= 41; i++) {
            expected.add(i);
        }
        expected.add(43L);
        expected.add(44L);
        expected.add(45L);
        expected.add(46L);
        // #48：2026-09-21 新增（健康资产损益端可见性）。跳过 #47 —— 该号已由
        // optin-arch 线预定（service_plan_optin_visibility）、尚未落 PRD，不得先行落库。
        expected.add(48L);
        assertEquals(expected, numbers, "编号集合必须恰为 {1..41, 43..46, 48} —— 多一个少一个都是编号位移");

        // ★ #42 空号的核心断言：既不能被读到
        assertTrue(slots.findByNo(42).isEmpty(), "#42 是空号，findByNo(42) 必须返回 empty");
        assertFalse(numbers.contains(42L), "#42 不得出现在声明编号集合中");

        // ★ #47 预留的核心断言：与 #42 性质不同（#42 = 永久空号，#47 = 暂缺待落盘），
        //    但两者在"当前必须缺席"这一点上一致。若 #47 先于 PRD 落盘，即为"实现先于裁定"。
        assertTrue(slots.findByNo(47).isEmpty(),
                "#47 已由 optin-arch 线预定但尚未落 PRD，此时它必须缺席（先于裁定落库 = 带债设计）");
        assertFalse(numbers.contains(47L), "#47 不得出现在声明编号集合中（尚未裁定）");
    }

    @Test
    @DisplayName("#42 不能被写入/复用：真库拒绝 INSERT，SQLSTATE 23514（CHECK 约束）")
    void number_42_cannot_be_inserted_or_reused() {
        DataAccessException caught = assertThrows(DataAccessException.class,
                () -> jdbc.update("INSERT INTO config_slot "
                                + "(config_no, config_key, value_type, initial_value, prd_item_name, description) "
                                + "VALUES (42, 'cfg:card.visibility', 'JSON', '{}', 'card_visibility', '复用空号')"),
                "#42 被成功写入了 —— 空号被占用，编号口径已破坏");
        SQLException se = rootSqlException(caught);
        assertNotNull(se, "被拒原因应来自数据库，实际异常链: " + caught);
        // 23514 = check_violation：config_slot_no_42_stays_vacant 生效
        assertEquals("23514", se.getSQLState(),
                "#42 写入应被 CHECK 约束以 23514 拒绝，实际 SQLSTATE=" + se.getSQLState()
                        + " 原文=" + se.getMessage());

        // 反向自证：#42 确实没落库（被拒 ≠ 只是抛了个无关异常）
        Integer n = jdbc.queryForObject("SELECT count(*) FROM config_slot WHERE config_no = 42",
                Integer.class);
        assertEquals(0, n, "#42 竟然落库了 " + n + " 行");
    }

    @Test
    @DisplayName("#42 也不能在租户层被写入：app_config 真库拒绝（外键 23503）")
    void number_42_cannot_be_written_at_tenant_level() {
        // 用超级用户直连写入 —— 绕过服务层、绕过 RLS，只靠数据库约束。
        // 若约束只写在 Java 里，这一条会写入成功。
        ConfigGateSupport.PsqlResult r = ConfigGateSupport.runSql(
                ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(), ConfigGateSupport.DB,
                "INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) "
                        + "VALUES ('" + TENANT_A + "', 42, '{}', 1, 'attacker')");

        assertFalse(r.ok(), "绕过服务层写入 #42 竟然成功了 —— 空号可被绕过！输出:\n" + r.output());
        assertTrue(r.output().contains("23503") || r.output().contains("外键")
                        || r.output().contains("未被声明"),
                "被拒原因应是外键违约(23503)，实际输出:\n" + r.output());

        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM app_config WHERE config_no = 42", Integer.class);
        assertEquals(0, n, "租户层出现了 #42 行 " + n + " 行");
    }

    // ------------------------------------------------------------------
    // 1b) 与 PRD「附：可配置项清单」交叉核对（独立来源，防止"声明表自己说自己是 46 条"）
    //
    // ⚠️ 命名更正（2026-09-21）：本测试原先叫 ..._match_the_prd_section_10_table，
    //    但那张表**不在 §10** —— §10 是「待确认问题」（Q1~Q17），配置表实际标题是
    //    「## 附：可配置项清单（硬性要求 —— 所有业务数字不得硬编码）」，位于 §9 之后、
    //    附录 B 之前，是一个独立的一级章节而非 §10。**按方法名去 §10 找表的人会白找一趟**
    //    （lead 本人就白找过）。故此处一律改称「附：可配置项清单」，方法名同步更正。
    //    解析器本身用的就是真实标题 `## 附：可配置项清单`，行为不变、仅消除误导命名。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PRD 交叉核对：「附：可配置项清单」的【编号→名称】映射 == 真库声明表（编号对不上或名称错位都红）")
    void slot_numbers_and_names_must_match_the_prd_config_item_list() throws Exception {
        // 断言对象是【PRD 文档本身】—— 独立于声明表。
        //
        // 只比编号是不够的：编号齐 46 条、但把 "依从性权重" 的位置换成别的配置项，
        // 编号集合仍然完全正确。那正是本任务最容易犯的错误（一个配置项被顶替）。
        // 故这里比【编号 -> 配置项名称】的完整映射。
        java.nio.file.Path prd = ConfigGateSupport.prdFile();
        assertTrue(java.nio.file.Files.isRegularFile(prd), "PRD 必须存在: " + prd);

        List<String> lines = java.nio.file.Files.readAllLines(prd, java.nio.charset.StandardCharsets.UTF_8);
        int from = -1;
        int to = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            // 表区间 = 【## 附：可配置项清单 … ## 附录 B），按标题锚点定位，不用行号
            if (from < 0 && l.startsWith("## 附：可配置项清单")) {
                from = i;
            }
            if (from >= 0 && to < 0 && l.startsWith("## 附录 B")) {
                to = i;
            }
        }
        assertTrue(from >= 0 && to > from,
                "未能定位 PRD「附：可配置项清单」的表格区间（from=" + from + ", to=" + to
                        + "）—— 解析器失效，本断言将形同虚设");

        // 行首形如 | 12 | 名称 | 或 | **43** | **名称** |
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|\\s*(.+?)\\s*\\|");
        java.util.Map<Long, String> fromPrd = new java.util.LinkedHashMap<>();
        for (String l : lines.subList(from, to)) {
            var m = row.matcher(l);
            if (m.find()) {
                // 去 markdown 强调符与反引号，保留中文/英文/标点作为名称本体
                String name = m.group(2).replace("**", "").replace("`", "").trim();
                fromPrd.put(Long.parseLong(m.group(1)), name);
            }
        }

        assertEquals(46, fromPrd.size(), "PRD「附：可配置项清单」应解析出 46 条编号，实际 " + fromPrd.size()
                + " —— 解析器口径或 PRD 结构已变，须先修解析器再谈断言");
        assertFalse(fromPrd.containsKey(42L), "PRD 配置编号集合里出现了 #42 —— 空号裁定被改动");
        assertFalse(fromPrd.containsKey(47L), "PRD 配置编号集合里出现了 #47 —— 该号尚未裁定，不得落表");

        java.util.Map<Long, String> fromDb = new java.util.LinkedHashMap<>();
        slots.findAll().forEach(s -> fromDb.put(s.getConfigNo(), s.getPrdItemName()));

        // 逐条比对：先比编号集合，再比每条的名称。分开报错，便于直接定位是哪一类漂移。
        assertEquals(fromPrd.keySet(), fromDb.keySet(),
                "声明表的编号集合与 PRD 配置清单不一致 —— 迁移灌漏/多灌，或编号发生位移。"
                        + " PRD=" + fromPrd.keySet() + " DB=" + fromDb.keySet());

        List<String> mismatches = new java.util.ArrayList<>();
        for (Long no : fromPrd.keySet()) {
            String prdName = fromPrd.get(no);
            String dbName = fromDb.get(no);
            if (!prdName.equals(dbName)) {
                mismatches.add("#" + no + ": PRD=「" + prdName + "」 但声明表=「" + dbName + "」");
            }
        }
        assertTrue(mismatches.isEmpty(),
                "声明表的【编号 -> 配置项名称】映射与 PRD 配置清单不一致 —— 编号对了但配置项被顶替/错位:\n  - "
                        + String.join("\n  - ", mismatches)
                        + "\n（只比编号会漏掉这一类错误：编号齐 46 条、但某一项被别的配置占位）");
    }

    // ------------------------------------------------------------------
    // 2) 46 条全部可读（租户 A / B 各自 46 行）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("46 条配置在租户 A 与租户 B 各自全部可读，且 #42 / #47 不在其中")
    void all_46_configs_are_readable_for_both_tenants() {
        for (String tenant : new String[]{TENANT_A, TENANT_B}) {
            List<ConfigSlot> all = slots.findAll();
            for (ConfigSlot slot : all) {
                String v = service.getRequired(tenant, slot.getConfigKey());
                assertNotNull(v, "配置 #" + slot.getConfigNo() + " 在租户 " + tenant + " 读到 null");
            }
            // 反证"确实读了 46 行"而不是循环空转
            assertEquals(46, all.size(), "租户 " + tenant + " 应能读到 46 条声明");
        }

        // #42（空号）/ #47（预留未裁定）在任何一个租户上都读不到（用行数直查生效值表；数字本身由 RLS 决定）
        for (String tenant : new String[]{TENANT_A, TENANT_B}) {
            int n = service.storedRowCount(tenant);
            assertEquals(46, n, "租户 " + tenant + " 的生效值应为 46 行（无 #42 / #47），实际 " + n);
        }
    }

    @Test
    @DisplayName("租户上下文隔离：租户A 的行数在租户B 上下文下不可见（真库行数断言）")
    void tenant_rows_are_isolated_in_the_real_database() {
        Integer aRows = inTenant(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config", Integer.class));
        Integer bRows = inTenant(TENANT_B, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config", Integer.class));
        assertEquals(46, aRows, "租户A 应看到自己 46 行");
        assertEquals(46, bRows, "租户B 应看到自己 46 行");

        // 交叉直查：A 上下文下按 B 的 tenant_id 查，必须零行
        Integer cross = inTenant(TENANT_A, () ->
                jdbc.queryForObject("SELECT count(*) FROM app_config WHERE tenant_id = ?::uuid",
                        Integer.class, TENANT_B));
        assertEquals(0, cross, "租户A 读到了租户B 的 " + cross + " 行 —— 串租户！");

        // 未设上下文 → 零行（fail-closed），且反证表非空
        Integer leak = jdbc.queryForObject("SELECT count(*) FROM app_config", Integer.class);
        assertEquals(0, leak, "未设 app.tenant_id 时读到 " + leak + " 行 —— 全表泄漏！");
    }

    // ------------------------------------------------------------------
    // 3) fail-closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fail-closed：未配置的租户读必填项即抛，且码值为 2001（绝不返回默认值）")
    void unconfigured_tenant_throws_and_never_defaults() {
        BizException ex = assertThrows(BizException.class,
                () -> service.getRequired(TENANT_EMPTY, KEY_THRESHOLD),
                "未配置的租户竟然读到了值 —— fail-closed 被破坏");
        assertEquals(2001, ex.getCode(),
                "配置缺失语义归属 VISIBILITY_DENIED(2001)，实际=" + ex.getCode());

        // 必须真的没有值，而不是"抛了异常但其实也能拿到"
        assertTrue(service.getOptional(TENANT_EMPTY, KEY_THRESHOLD).isEmpty(),
                "getOptional 在未配置租户上返回了值");
    }

    @Test
    @DisplayName("fail-closed：键未登记即抛（调用方引用不存在的配置项不得静默返回空）")
    void unregistered_key_throws() {
        BizException ex = assertThrows(BizException.class,
                () -> service.getRequired(TENANT_A, KEY_MISSING),
                "未登记的键竟然没抛异常");
        assertEquals(2001, ex.getCode(), "未登记键的码值应为 2001，实际=" + ex.getCode());
    }

    @Test
    @DisplayName("fail-closed：非法租户标识被拒（2003），绝不拼进 SQL")
    void illegal_tenant_id_is_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.getRequired("not-a-uuid'; DROP TABLE app_config; --", KEY_THRESHOLD));
        assertEquals(2003, ex.getCode(),
                "非法租户标识应为 TENANT_MISMATCH(2003)，实际=" + ex.getCode());

        // 反向自证：表还在（注入未生效）
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'app_config'",
                Integer.class);
        assertEquals(1, n, "app_config 表不见了 —— 注入生效");
    }

    // ------------------------------------------------------------------
    // 4) 取值校验（服务层 + DB 层两层）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取值校验·服务层：非 JSON / 越界 DECIMAL 被拒 5001（HTTP 422）")
    void service_side_value_validation_rejects_bad_values() {
        BizException ex = assertThrows(BizException.class,
                () -> service.set(TENANT_A, KEY_THRESHOLD, "0.95abc", "tester"),
                "阈值 0.95abc 竟然被接受");
        assertEquals(5001, ex.getCode(), "取值非法应为 BUSINESS_RULE_VIOLATED(5001)，实际=" + ex.getCode());

        BizException ex2 = assertThrows(BizException.class,
                () -> service.set(TENANT_A, "cfg:hsi.weights", "{not json", "tester"),
                "非法 JSON 竟然被接受");
        assertEquals(5001, ex2.getCode());
    }

    @Test
    @DisplayName("#44 非法组合（小程序后台自动采集）在两层都被拒：服务层 5001 + 真库 23514")
    void capture_mode_forbidden_combination_is_rejected_at_both_layers() {
        // 服务层
        BizException ex = assertThrows(BizException.class,
                () -> service.set(TENANT_A, KEY_CAPTURE_MODE, "小程序后台自动采集", "tester"),
                "#44 禁止组合竟然被服务层接受（微信 requiredBackgroundModes 无 bluetooth，该组合物理上不成立）");
        assertEquals(5001, ex.getCode());

        // 真库层：绕过服务层，直接用超级用户写（同时绕过 RLS）
        ConfigGateSupport.PsqlResult r = ConfigGateSupport.runSql(
                ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(), ConfigGateSupport.DB,
                "UPDATE app_config SET value = 'WX_BACKGROUND_AUTO' WHERE config_no = 44");
        assertFalse(r.ok(), "#44 禁止组合竟然被真库接受 —— 触发器未生效。输出:\n" + r.output());
        assertTrue(r.output().contains("23514") || r.output().contains("禁止组合"),
                "被拒原因应是 23514/禁止组合，实际输出:\n" + r.output());

        // 值未被改动（被拒 ≠ 悄悄写进去）
        assertEquals("M-WX-FG", service.getRequired(TENANT_A, KEY_CAPTURE_MODE),
                "#44 的值被改动了，应仍为冻结值 M-WX-FG");
    }

    // ------------------------------------------------------------------
    // 5) 变更历史 + 回滚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("变更历史：who/when/before/after 均落库，回滚后值恢复且历史继续增长")
    void history_records_who_when_before_after_and_rollback_leaves_a_trace() {
        String original = service.getRequired(TENANT_A, KEY_CAPTURE_MODE);
        int historyBefore = service.history(TENANT_A, KEY_CAPTURE_MODE).size();

        // 改值（合法值 M-APP）
        service.set(TENANT_A, KEY_CAPTURE_MODE, "M-APP", "staff-a1");
        assertEquals("M-APP", service.getRequired(TENANT_A, KEY_CAPTURE_MODE), "改值未生效");

        List<ConfigChange> afterSet = service.history(TENANT_A, KEY_CAPTURE_MODE);
        assertEquals(historyBefore + 1, afterSet.size(), "改值后历史应 +1 条");

        ConfigChange change = afterSet.get(0);
        assertEquals("staff-a1", change.who(), "who 应记录为写入人");
        assertEquals(original, change.beforeValue(), "before_value 应为改动前的值");
        assertEquals("M-APP", change.afterValue(), "after_value 应为改动后的值");
        assertNotNull(change.changedAt(), "changed_at（when）不得为空");
        assertTrue(change.changedAt().isBefore(OffsetDateTime.now().plusMinutes(1)),
                "changed_at 不应是未来时间 —— 说明时间不是由 DB 时钟产生的");
        assertEquals("UPSERT", change.op(), "普通改值的 op 应为 UPSERT");

        // 回滚
        service.rollbackToPrevious(TENANT_A, KEY_CAPTURE_MODE, "staff-a2");
        assertEquals(original, service.getRequired(TENANT_A, KEY_CAPTURE_MODE),
                "回滚后值未恢复到 " + original);

        List<ConfigChange> afterRollback = service.history(TENANT_A, KEY_CAPTURE_MODE);
        assertEquals(historyBefore + 2, afterRollback.size(),
                "回滚本身也必须留下一条历史行（历史不得被抹平）");
        ConfigChange rollback = afterRollback.get(0);
        assertEquals("ROLLBACK", rollback.op(), "回滚行的 op 应为 ROLLBACK");
        assertEquals("staff-a2", rollback.who(), "回滚行的 who 应为执行回滚的人");
        assertEquals("M-APP", rollback.beforeValue(), "回滚行 before 应为回滚前的（错误）值");
        assertEquals(original, rollback.afterValue(), "回滚行 after 应为恢复后的值");

        // 还原现场（让本例可重复执行）
        service.set(TENANT_A, KEY_CAPTURE_MODE, original, "test-cleanup");
    }

    @Test
    @DisplayName("历史由数据库触发器写入：绕过服务层的直改（psql/superuser）同样留痕")
    void history_is_written_by_a_db_trigger_even_when_bypassing_the_service() {
        // 注意：历史表本身也有 RLS，故计数必须在租户上下文内做（同一张表两套约束）。
        int before = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT count(*) FROM app_config_history WHERE config_no = 7", Integer.class));

        // 绕过服务层、绕过 RLS，用超级用户直改（模拟 DBA 手敲 SQL / 批量导入）
        ConfigGateSupport.PsqlResult r = ConfigGateSupport.runSql(
                ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(), ConfigGateSupport.DB,
                "UPDATE app_config SET value = '14', version = version + 1 "
                        + "WHERE tenant_id = '" + TENANT_A + "' AND config_no = 7");
        assertTrue(r.ok(), "直改失败: " + r.output());

        int after = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT count(*) FROM app_config_history WHERE config_no = 7", Integer.class));
        assertEquals(before + 1, after,
                "绕过服务层的直改【没有】留痕 —— 说明历史是应用层写的，而非 DB 触发器写的");

        // 顺手证明 who 的兜底语义：直改没设 app.actor，历史行应回落到数据库角色（而不是空）
        String who = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT who FROM app_config_history WHERE config_no = 7 "
                        + "ORDER BY changed_at DESC, id DESC LIMIT 1", String.class));
        assertNotNull(who, "历史行的 who 为 null —— who 不得为空");
        assertFalse(who.isBlank(), "历史行的 who 为空串 —— who 不得为空");

        // 还原
        ConfigGateSupport.runSql(ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(),
                ConfigGateSupport.DB,
                "UPDATE app_config SET value = '7', version = version + 1 "
                        + "WHERE tenant_id = '" + TENANT_A + "' AND config_no = 7");
    }

    @Test
    @DisplayName("历史表 append-only：UPDATE / DELETE 均被真库拒绝（append-only 触发器生效）")
    void history_table_is_append_only() {
        // 先用租户上下文确认历史确实存在（否则"删不掉"可能只是因为本来就没行）
        int before = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT count(*) FROM app_config_history WHERE config_no = 7", Integer.class));
        assertTrue(before > 0, "历史行为 " + before + " —— 前置条件不成立，本例无判别力");

        ConfigGateSupport.PsqlResult rUpdate = ConfigGateSupport.runSql(
                ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(), ConfigGateSupport.DB,
                "UPDATE app_config_history SET after_value = 'tampered' WHERE config_no = 7");
        assertFalse(rUpdate.ok(), "历史行竟然可被 UPDATE —— append-only 被破坏。输出:\n" + rUpdate.output());
        assertTrue(rUpdate.output().contains("42501") || rUpdate.output().contains("append-only"),
                "应被 42501 拒绝，实际输出:\n" + rUpdate.output());

        ConfigGateSupport.PsqlResult rDelete = ConfigGateSupport.runSql(
                ConfigGateSupport.SUPER_USER, ConfigGateSupport.superPassword(), ConfigGateSupport.DB,
                "DELETE FROM app_config_history WHERE config_no = 7");
        assertFalse(rDelete.ok(), "历史行竟然可被 DELETE —— append-only 被破坏。输出:\n" + rDelete.output());
        assertTrue(rDelete.output().contains("42501") || rDelete.output().contains("append-only"),
                "应被 42501 拒绝，实际输出:\n" + rDelete.output());

        // 反证：历史行数未变（被拒 ≠ 只是抛了个无关异常）
        int after = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT count(*) FROM app_config_history WHERE config_no = 7", Integer.class));
        assertEquals(before, after, "历史行数发生了变化 —— 篡改/删除实际生效了");
    }

    // ------------------------------------------------------------------
    // 6) RLS 与业务表同等严格（真库元数据 + 非超级用户）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置表 RLS 与业务表同等严格：ENABLE + FORCE + 策略，owner 为非超级用户")
    void config_tables_have_force_rls_like_business_tables() {
        for (String table : new String[]{"app_config", "app_config_history"}) {
            var row = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner) AS owner "
                            + "FROM pg_class WHERE relname = ? AND relkind = 'r'", table);
            assertEquals(Boolean.TRUE, row.get("relrowsecurity"),
                    table + " 必须 ENABLE ROW LEVEL SECURITY");
            assertEquals(Boolean.TRUE, row.get("relforcerowsecurity"),
                    table + " 必须 FORCE ROW LEVEL SECURITY（配置是审计证据的载体，owner 绕过等于证据可被伪造）");
            assertEquals(ConfigGateSupport.APP_USER, String.valueOf(row.get("owner")),
                    table + " 的 owner 应为非超级用户 " + ConfigGateSupport.APP_USER
                            + "（否则 FORCE 无从证明，断言会假通过）；实际=" + row.get("owner"));

            Integer policies = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, table);
            assertTrue(policies != null && policies > 0, table + " 没有任何 RLS 策略（等于未隔离）");

            String qual = jdbc.queryForObject(
                    "SELECT qual FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                    String.class, table);
            String check = jdbc.queryForObject(
                    "SELECT with_check FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                    String.class, table);
            assertTrue(qual != null && qual.contains("NULLIF"),
                    table + " 的 USING 必须含 NULLIF（fail-closed 归一），实际: " + qual);
            assertTrue(check != null && check.contains("NULLIF"),
                    table + " 的 WITH CHECK 必须显式含 NULLIF（写入侧校验），实际: " + check);
        }
    }

    @Test
    @DisplayName("FORCE RLS 真的生效：owner（非超用户）自己写入也必须带租户上下文，否则被拒")
    void force_rls_really_applies_to_the_owner() {
        // dy_app 是 app_config 的 owner。FORCE 的意义就是"连 owner 也受策略约束"。
        // 未设 app.tenant_id 时，WITH CHECK 表达式求值为 NULL -> 不等于 TRUE -> 拒绝。
        //
        // 实测 PG 17 的拒绝形态是【RLS 策略违约】（"新行违背了表…的行级安全策略"），
        // 而不是先撞上 NOT NULL：WITH CHECK 在约束检查之前生效，''::uuid 之前先被
        // NULLIF 归一为 NULL。故这里断言的是"被拒 + 原因是策略/非空"，而不是钉死某一个
        // SQLSTATE —— 钉死它会把"更早失败"误判成"没生效"。
        ConfigGateSupport.PsqlResult r = ConfigGateSupport.runSql(
                ConfigGateSupport.APP_USER, ConfigGateSupport.APP_PASSWORD, ConfigGateSupport.DB,
                "INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) "
                        + "VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1, '9', 1, 'x')");
        assertFalse(r.ok(),
                "owner 在无租户上下文时竟然写入了 —— fail-closed 被破坏。输出:\n" + r.output());

        String out = r.output();
        boolean policyRejected = out.contains("42501") || out.contains("行级安全策略")
                || out.contains("ROW LEVEL SECURITY") || out.contains("row-level security");
        boolean notNullRejected = out.contains("23502") || out.contains("null value")
                || out.contains("非空") || out.contains("NOT NULL");
        assertTrue(policyRejected || notNullRejected,
                "被拒原因既不是 RLS 策略违约也不是 NOT NULL —— 说明拒绝来自别处。输出:\n" + out);

        // 反证：同一张表在【设了上下文】时写入是通的（否则"被拒"可能只是"整表不可写"）
        String sql = "BEGIN; SET LOCAL app.tenant_id = '" + TENANT_A + "'; "
                + "INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) "
                + "VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1, '7', 1, 'probe') "
                + "ON CONFLICT (tenant_id, config_no) DO NOTHING; COMMIT;";
        ConfigGateSupport.PsqlResult ok = ConfigGateSupport.runSql(
                ConfigGateSupport.APP_USER, ConfigGateSupport.APP_PASSWORD, ConfigGateSupport.DB, sql);
        assertTrue(ok.ok(),
                "设了租户上下文后写入仍失败 —— 说明上面的拒绝并非来自 RLS，本例无判别力。输出:\n"
                        + ok.output());
    }

    // ------------------------------------------------------------------
    // 7) 缓存键含 tenant_id
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缓存隔离：租户A 改值后，租户B 读到的仍是自己的值（缓存键含 tenant_id）")
    void cache_key_contains_tenant_id_so_tenants_never_cross_hit() {
        String aOriginal = service.getRequired(TENANT_A, KEY_CAPTURE_MODE);
        String bOriginal = service.getRequired(TENANT_B, KEY_CAPTURE_MODE);

        // 先把两个租户的值都读进缓存（同一个 JVM / 同一个 service 实例）
        assertEquals(aOriginal, service.getRequired(TENANT_A, KEY_CAPTURE_MODE));
        assertEquals(bOriginal, service.getRequired(TENANT_B, KEY_CAPTURE_MODE));

        // 改租户 A 的值
        service.set(TENANT_A, KEY_CAPTURE_MODE, "M-APP", "cache-test");

        // 租户 A 读到新值
        assertEquals("M-APP", service.getRequired(TENANT_A, KEY_CAPTURE_MODE),
                "租户A 改值后仍读到旧值 —— 缓存未失效");
        // ★ 租户 B 必须仍然读到自己原来的值（绝不能命中 A 的缓存条目）
        assertEquals(bOriginal, service.getRequired(TENANT_B, KEY_CAPTURE_MODE),
                "租户B 读到了租户A 的值 —— 缓存键缺少 tenant_id 前缀，跨租户命中！");

        // 还原
        service.set(TENANT_A, KEY_CAPTURE_MODE, aOriginal, "cache-test-cleanup");
    }

    @Test
    @DisplayName("缓存键结构：无租户 / 空租户一律拒绝构造（fail-closed，无处可存共享槽位）")
    void cache_key_requires_a_tenant() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigCacheKey(null, KEY_THRESHOLD));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigCacheKey("", KEY_THRESHOLD));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigCacheKey("   ", KEY_THRESHOLD));
        // 不同租户的键必须不相等（这是"跨租户命中"不可能的根据）
        assertFalse(new ConfigCacheKey(TENANT_A, KEY_THRESHOLD)
                        .equals(new ConfigCacheKey(TENANT_B, KEY_THRESHOLD)),
                "不同租户的缓存键相等 —— 跨租户命中成为可能");
    }

    // ------------------------------------------------------------------
    // 8) 真相源纪律：读路径不经过任何外部配置中心
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADR-08：配置读取路径上不存在任何外部配置中心客户端（反射检查字段类型）")
    void no_external_config_center_is_on_the_read_path() throws Exception {
        // 断言对象不是"我写的常量"，而是"这条路径上不存在那个东西"：
        // 检查 JdbcConfigService 与两个 Repository 的字段类型，是否有任何一个
        // 落在外部配置中心的包前缀内。新增 Apollo/Nacos 客户端会立刻让本例变红。
        String[] forbiddenPrefixes = {
                "com.ctrip.framework.apollo",
                "com.alibaba.nacos",
                "com.ecwid.consul",
                "org.springframework.cloud.config",
                "org.springframework.cloud.context",
        };

        for (Class<?> c : new Class<?>[]{
                JdbcConfigService.class,
                JdbcConfigSlotRepository.class,
                JdbcConfigHistoryRepository.class}) {
            for (Field f : c.getDeclaredFields()) {
                for (String prefix : forbiddenPrefixes) {
                    assertFalse(f.getType().getName().startsWith(prefix),
                            "配置读路径上出现了外部配置中心客户端: " + c.getSimpleName()
                                    + "." + f.getName() + " 类型=" + f.getType().getName()
                                    + "（ADR-08：外部配置中心不得作为真相源）");
                }
            }
        }

        // 反向自证：真的有字段被检查过（否则空循环恒真，断言毫无意义）
        int checked = 0;
        for (Class<?> c : new Class<?>[]{JdbcConfigService.class, JdbcConfigSlotRepository.class,
                JdbcConfigHistoryRepository.class}) {
            checked += c.getDeclaredFields().length;
        }
        assertTrue(checked > 0, "没有被检查的字段 —— 上面的循环恒真，断言无效");

        // 再加一条行为证据：改值 → 新值立刻从 DB 读出（说明真相源是 DB，
        // 不是某个需要等待推送的外部中心）。若走外部中心，这里会是旧值。
        String original = service.getRequired(TENANT_A, KEY_THRESHOLD);
        service.set(TENANT_A, KEY_THRESHOLD, "0.85", "truth-source-test");
        assertEquals("0.85", service.getRequired(TENANT_A, KEY_THRESHOLD),
                "改值后立刻读到的不是新值 —— 说明读路径有另一个真相源（外部中心缓存的旧值）");
        service.set(TENANT_A, KEY_THRESHOLD, original, "truth-source-test-cleanup");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private <T> T inTenant(String tenantId, java.util.function.Supplier<T> body) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(appDataSource));
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    private static SQLException rootSqlException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException se) {
                return se;
            }
        }
        return null;
    }
}