package com.diaoyuanyun.dy.audit.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表结构契约测试：断言 {@link AuditLogTable#CREATE_TABLE}（生产幂等建表与测试 Harness 共用）
 * 与交付物 DDL 文件 {@code dy-audit/src/main/resources/db/audit_log.sql} <b>逐列一致</b>。
 *
 * <h2>这个测试防的是什么</h2>
 * 同一张 {@code audit_log} 现在有两个来源：人读的 DDL 文件（迁移的真相源）与
 * 代码里的建表常量（让模块能独立起测）。若二者漂移，测试跑的就<b>不是</b>生产那张表 ——
 * 于是"链在测试里是好的"变成一个对假表的结论，而且<b>不会有任何红点</b>。
 * 这类偏差不会自己现形，只能靠显式比对。
 *
 * <p>顺带一提，另一个更隐蔽的漂移方向是"有人在 DDL 文件里另加了一列"：
 * 那在文件侧看起来是"加强了约束"，但代码侧不认这一列，
 * 于是生产与测试又开始分叉。故本测试<b>双向</b>比较列集合，而不是只检查子集。
 */
class AuditLogTableContractTest {

    /**
     * 从一段 DDL 文本里抽取 {@code CREATE TABLE audit_log ( ... )} 的列定义。
     *
     * <h3>为什么要先剥注释、再找配平的右括号（而不是正则一把抓）</h3>
     * 交付物文件的列定义里带行内中文注释，而注释文本中含 {@code ')'} 的括号字符
     * （例如"链首约定值 = 64 个 '0'（不是空串）"里的全角括号是安全的，
     * 但注释里出现的 ASCII 右括号会让 {@code \\((.*?)\\)} 提前收尾）。
     * 早先的实现就是这样：正则匹配到第一个 ASCII 右括号就停，
     * 结果只解析出前 3 列 —— 而它<b>不会报错</b>，只会让后续"列不一致"的断言
     * 以看似无关的理由失败（或更糟：两侧都截断到同一处，测试悄悄通过）。
     * 故这里显式做：① 去注释 → ② 手写括号配平扫描取块 → ③ 再按列解析。
     */
    private static Map<String, String> parseColumns(String ddl) {
        String head = "CREATE TABLE audit_log";  // 归一化后再定位，忽略 IF NOT EXISTS
        String normalized = ddl.replaceAll("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?audit_log",
                head);
        int at = normalized.indexOf(head);
        assertTrue(at >= 0, "交付文本里找不到 " + head + " —— 实现将与一个不存在的定义比较，测试失去意义");

        int open = normalized.indexOf('(', at);
        assertTrue(open > at, "CREATE TABLE audit_log 之后没有 '(' : " + head);
        int depth = 0;
        int close = -1;
        for (int i = open; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        assertTrue(close > open, "CREATE TABLE audit_log 的括号未配平");

        Map<String, String> columns = new LinkedHashMap<>();
        for (String rawLine : normalized.substring(open + 1, close).split("\\R")) {
            // 注释在【取块之后】才剥：块内的行内注释可能含 ')'，先剥才安全。
            String line = rawLine.replaceAll("--.*$", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            int space = line.indexOf(' ');
            assertNotEquals(-1, space, "无法解析列定义行: '" + rawLine + "'");
            String name = line.substring(0, space).trim().toLowerCase();
            String rest = line.substring(space).trim().replaceAll("\\s+", " ").toLowerCase();
            columns.put(name, rest);
        }
        assertFalse(columns.isEmpty(), "解析出的列为空");
        return columns;
    }

    private static Path deliverableDdl() {
        return AuditChainTestSupport.auditLogDdl();
    }

    @Test
    @DisplayName("实现侧建表常量与交付物 DDL 文件逐列一致（列名 / 列序 / 类型 / 约束）")
    void implementation_ddl_matches_the_deliverable_ddl_file() throws IOException {
        Path file = deliverableDdl();
        assertTrue(Files.isRegularFile(file), "交付物 DDL 文件不存在: " + file);

        Map<String, String> impl = parseColumns(AuditLogTable.CREATE_TABLE);
        Map<String, String> doc = parseColumns(Files.readString(file, StandardCharsets.UTF_8));

        // 双向比较：既防"实现多了列"，也防"文档多了列"
        assertEquals(new ArrayList<>(doc.keySet()), new ArrayList<>(impl.keySet()),
                "列名或列序与交付物 DDL 不一致 —— 实现可能在操作另一张表");
        for (Map.Entry<String, String> e : doc.entrySet()) {
            assertEquals(e.getValue(), impl.get(e.getKey()),
                    "列 " + e.getKey() + " 的类型/约束与交付物 DDL 不一致（列名相同但语义可能已变）");
        }

        List<String> expected = List.of(
                "id", "tenant_id", "actor", "action", "target_type", "target_id",
                "payload", "prev_hash", "hash", "created_at");
        assertEquals(expected, new ArrayList<>(doc.keySet()),
                "交付物 DDL 的列集合发生变动 —— 链字段（prev_hash/hash）必须存在且顺序稳定");
    }

    @Test
    @DisplayName("链字段宽度是 CHAR(64)（SHA-256 hex）且 NOT NULL —— 与哈希实现绑定")
    void chain_columns_are_char64_and_not_null() throws IOException {
        Map<String, String> doc = parseColumns(Files.readString(deliverableDdl(), StandardCharsets.UTF_8));
        for (String col : List.of("prev_hash", "hash")) {
            String def = doc.get(col);
            assertTrue(def != null, "交付物 DDL 缺少链字段列: " + col);
            assertTrue(def.contains("char(" + AuditLogTable.HASH_COLUMN_LENGTH + ")"),
                    col + " 类型应为 char(64)（SHA-256 hex 宽度），实际=" + def);
            assertTrue(def.contains("not null"),
                    col + " 必须 NOT NULL —— 可空会让『忘了算』与『值恰好为空』不可区分");
        }
        assertEquals(AuditLogTable.HASH_COLUMN_LENGTH, AuditChainHash.HASH_HEX_LENGTH,
                "列宽常量与哈希 hex 长度常量分叉 —— 摘要会被 PostgreSQL 静默截断或报错");
        assertEquals(AuditChainHash.HASH_HEX_LENGTH, AuditChainHash.GENESIS_PREV_HASH.length(),
                "创世值长度必须与 CHAR(64) 等宽");
    }

    @Test
    @DisplayName("DDL 文件必须写明 TRUNCATE 也要撤销（append-only 的第二道防线，漏了就是漏勺）")
    void ddl_documents_the_revoke_of_truncate_too() throws IOException {
        String text = Files.readString(deliverableDdl(), StandardCharsets.UTF_8);
        assertTrue(text.contains("REVOKE"),
                "DDL 文件未记录权限撤销示例 —— append-only 的第二道防线失去出处");
        assertTrue(text.toUpperCase().contains("TRUNCATE"),
                "DDL 只撤 UPDATE/DELETE 而没有 TRUNCATE —— TRUNCATE 无需 WHERE 即可清空整条链，"
                        + "是比 DELETE 更省事的篡改路径，必须一并撤销");
        assertTrue(text.contains("genesis") || text.contains("链首"),
                "DDL 未记录创世约定值 —— 实现与本文件的字面差异必须留下可查的出处");
        // 表现"全 0"这个事实：避免在测试里再硬编码 64 个 0，
        // 从而让"改创世值"这件事必须同时改 DDL 与实现（否则本测试红）。
        assertTrue(text.contains("64 个 '0'") || text.contains("全 0"),
                "DDL 未说明创世值就是全 0，而实现用的是 '0'.repeat(64)");
    }

    @Test
    @DisplayName("DDL 文件必须写明撤销清单是承重的（有真库实验出处），而不是一句自觉")
    void ddl_records_the_basis_of_the_revoke_claim() throws IOException {
        String text = Files.readString(deliverableDdl(), StandardCharsets.UTF_8);
        assertTrue(text.contains("42501"),
                "DDL 未记录真库实测到的 SQLSTATE 42501 —— "
                        + "『撤销后写不进去』这句话需要有可复核的证据出处，而不是断言");
        assertTrue(text.contains("probe_owner_revoke.sql"),
                "DDL 未指向前提实验脚本 —— 下一位读者无法复核『撤销前 UPDATE 可用』这一步");
    }
}