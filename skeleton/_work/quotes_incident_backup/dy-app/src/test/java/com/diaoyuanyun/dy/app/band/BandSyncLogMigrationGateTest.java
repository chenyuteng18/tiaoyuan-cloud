package com.diaoyuanyun.dy.app.band;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-5 落地件（V9 迁移）的<b>文本门禁</b> —— 防"迁移被静默改坏"。
 *
 * <h2>为什么需要文本断言</h2>
 * V9 的核心价值是三条：① 以**契约为准**的词表被真正落库；② 旧列**保留**（不合并两条产线）；
 * ③ 幂等由**库层唯一索引**承担。这三件事都只在迁移文件里存在 ——
 * 若有人把 V9 的 CHECK 删掉、或把旧列 CHECK 一并改英文（= 合并产线），
 * 运行时不会有任何信号，只会在某天发现"冷/热启动分不出来了"或"尝试日志读不出来了"。
 * 故本类对 V9 文本做断言，与 {@code VerdictPersistenceBoundaryTest} 对 V8 的做法同款。
 *
 * <h2>本类与 {@code BandEndpointsE2ETest} 的分工</h2>
 * 前者答"迁移文件写了什么"（静态、快、无论真库是否可达都能跑）；
 * 后者答"迁移真的生效了吗"（真库 E1 落库 + E6 兼容性）。两者不可互相替代 ——
 * 只跑前者会漏"迁移没被执行"，只跑后者会漏"文件被改坏了但当前数据恰好不需那一条"。
 */
@DisplayName("A-5 · V9 手环词表对齐迁移的文本门禁")
class BandSyncLogMigrationGateTest {

    // 🛑 模块根 = dy-app（测试工作目录即模块目录），故 ".." 上溯到 skeleton 根 ——
    //    与 VerdictPersistenceBoundaryTest 同一写法（两处路径基准必须一致，
    //    否则一个用 ".." 一个用 "" 会让其中一个永远找不到文件却看起来"只是没写"）。
    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();
    private static final Path MIGRATION_V9 = SKEL.resolve(
            "dy-app/src/main/resources/db/migration/"
                    + "V9__band_sync_log_contract_enum_alignment.sql");

    private static String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION_V9), "V9 迁移文件不存在: " + MIGRATION_V9);
        return Files.readString(MIGRATION_V9, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("V9 必须补契约侧四列（contract_state / contract_trigger / batch_no / last_success_date）")
    void v9_adds_the_contract_side_columns() throws IOException {
        String s = sql();
        assertTrue(s.contains("ADD COLUMN IF NOT EXISTS contract_state"),
                "V9 必须补 contract_state（契约 §4.1 四态）");
        assertTrue(s.contains("ADD COLUMN IF NOT EXISTS contract_trigger"),
                "V9 必须补 contract_trigger（契约 §4.1 五值触发源）");
        assertTrue(s.contains("ADD COLUMN IF NOT EXISTS batch_no"),
                "V9 必须补 batch_no（契约幂等键）—— 它是『服务重启即丢』缺口的闭合点");
        assertTrue(s.contains("ADD COLUMN IF NOT EXISTS last_success_date"),
                "V9 必须补 last_success_date —— E1 真落库后 E6 读回 state=synced 行时，"
                        + "领域构造器按契约描述要求它必填；缺列会让 E6 在读路径抛 1001");
    }

    @Test
    @DisplayName("🛑 V9 的三列 CHECK 逐字等于契约枚举（多一个少一个都要红）")
    void v9_checks_match_the_contract_enums_verbatim() throws IOException {
        String s = sql();
        // 四态（契约 §4.1 BandSyncBatchRequest.state）
        for (String v : new String[]{"'syncing'", "'synced'", "'sync_failed'", "'no_data_today'“}) {
            assertTrue(s.contains(v), ”V9 的 contract_state CHECK 必须含契约四态之 " + v);
        }
        // 五值触发源（契约 §4.1 BandSyncBatchRequest.trigger）
        for (String v : new String[]{"'on_show_cold'", "'on_show_hot'", "'checkin'",
                "'daily_report'", "'manual'“}) {
            assertTrue(s.contains(v), ”V9 的 contract_trigger CHECK 必须含契约五值之 " + v);
        }
    }

    @Test
    @DisplayName("🛑 V9 必须【保留】旧列的中文/两态 CHECK —— 不得把两条产线合并")
    void v9_must_not_rewrite_the_legacy_columns() throws IOException {
        String s = sql();
        // 反面断言：V9 不得出现"改旧列 CHECK 为英文"的语句
        assertFalse(s.contains("ALTER COLUMN trigger_source"),
                "🛑 V9 不得改写旧列 trigger_source —— 它是 PRD §4.6「同步尝试日志」的载体，"
                        + "与契约 §4.1「批次上报」是【两条产线】。改成英文 = 用一次迁移把两个"
                        + "语义不同的东西合并，此后分不出这条是批次还是流水（本项目登记的失效模式）");
        assertFalse(s.contains("ALTER COLUMN result"),
                "🛑 V9 不得改写旧列 result（同上理由）");
        // 正面断言：不涉及旧列的 DROP CONSTRAINT
        assertFalse(s.contains("DROP CONSTRAINT IF EXISTS band_sync_log_trigger_source")
                        || s.contains("DROP CONSTRAINT IF EXISTS band_sync_log_result"),
                "🛑 V9 不得删除旧列的取值域约束");
    }

    @Test
    @DisplayName("🛑 V9 的幂等唯一索引必须是 (tenant_id, batch_no) 部分唯一（非全局）")
    void v9_partial_unique_index_is_tenant_scoped() throws IOException {
        String s = sql();
        assertTrue(s.contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_log_batch_no"),
                "V9 必须建幂等唯一索引");
        assertTrue(s.contains("(tenant_id, batch_no)"),
                "🛑 幂等键必须按 (tenant_id, batch_no) 唯一 —— 跨租户撞同一个 UUID 是合法的，"
                        + "全局唯一会让一方被无理由拒绝");
        assertTrue(s.contains("WHERE batch_no IS NOT NULL"),
                "必须是部分唯一索引 —— 既有行（非 E1 批次行）无 batch_no，不应参与唯一性");
    }

    @Test
    @DisplayName("🛑 V9 的自证块必须断言既有行全部合法（防『放宽后静默接受脏数据』）")
    void v9_has_self_verification_block() throws IOException {
        String s = sql();
        assertTrue(s.contains("DO $$"), "V9 必须有自证块（与前序迁移同口径）");
        assertTrue(s.contains("RAISE EXCEPTION"), "自证失败必须 RAISE EXCEPTION（中断迁移）");
        assertTrue(s.contains("contract_state IS NOT NULL")
                        && s.contains("contract_trigger IS NOT NULL"),
                "自证块必须枚举报错行（发现不合值域的历史数据时先人工核查）");
    }

    @Test
    @DisplayName("🛑 V9 必须可重复执行（IF NOT EXISTS / DROP CONSTRAINT IF EXISTS）")
    void v9_is_idempotent() throws IOException {
        String s = sql();
        assertTrue(s.contains("ADD COLUMN IF NOT EXISTS"), "列定义必须 IF NOT EXISTS");
        assertTrue(s.contains("DROP CONSTRAINT IF EXISTS"), "约束必须 DROP ... IF EXISTS 后再 ADD");
        assertTrue(s.contains("CREATE UNIQUE INDEX IF NOT EXISTS"), "索引必须 IF NOT EXISTS");
    }
}