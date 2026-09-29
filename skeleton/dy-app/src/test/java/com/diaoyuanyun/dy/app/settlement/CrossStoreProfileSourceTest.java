package com.diaoyuanyun.dy.app.settlement;

import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreProfileSource;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreRawConfig;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreThresholds;
import com.diaoyuanyun.dy.app.settlement.service.ConfigSeedCrossStoreProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨店通兑域「口径从哪来」的来源侧守卫（批次十一 · N-14 收口）。
 *
 * <h2>它回答的问题，与 {@code CrossStoreSettlementTest} 不同</h2>
 * {@code CrossStoreSettlementTest} 回答"给定一组阈值，结算逻辑对不对"；
 * 本类回答"<b>那组阈值是否真的来自配置、连线断了会不会大声失败</b>"。
 * 两者不可互相替代：一个把 {@code 0.30} 写死在代码里的实现，只要测试也传 {@code 0.30}
 * 就能骗过前者；而一个只读 seed 却把口径当默认值兜底的实现，能骗过后者。
 *
 * <h2>🛑 为什么"文件被改名 / 移走必须红"要单独断言</h2>
 * {@link ConfigSeedCrossStoreProfileSource} 是<b>过渡实现</b>：它不读 DB，而是解析
 * dy-config 的 {@code 02_slots_seed.sql} 里的两行。这条路径是一根<b>字符串连线</b>
 * （{@code SEED_RESOURCE} 常量 ↔ classpath 上的文件位置）。这种连线断掉时，
 * 若实现选择"读不到就用默认口径"，故障就是<b>静默</b>的 ——
 * 而对本域，"静默退回默认阈值"意味着总部在配置页上改的数字长期不生效。
 * 故本测试证明的是：<b>连线断掉 = 立刻抛错</b>，而不是"退化成一个看起来正常的默认阈值"。
 *
 * <h2>为什么"两段"要逐段点名断言</h2>
 * {@link CrossStoreProfileSource#raw()} 按 {@code SPECS} 的下标取值。
 * 若某一行被删、或列表顺序被调整，最危险的失效形态不是"报错"而是<b>错位</b>：
 * {@code #21} 的 {@code 0.30} 与 {@code #22} 里的占比数字都是合法数字，
 * 解析期全部通过，结果是拆分阈值变成 {@code 30} 或 {@code 100}。
 * 故本类逐段断言"这一段的值就是它自己的值"。
 */
class CrossStoreProfileSourceTest {

    /** dy-config 的配置声明文件在 classpath 上的位置（与实现类常量逐字一致）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    private final CrossStoreProfileSource source = new ConfigSeedCrossStoreProfileSource();

    // ------------------------------------------------------------------ 连线存在且可解析

    @Test
    @DisplayName("能从 config 声明文件解析出 #21 / #22 两段（连线真的存在）")
    void parses_both_declared_slots() {
        CrossStoreRawConfig raw = source.raw();

        assertNotNull(raw, "raw() 不得返回 null");
        assertTrue(raw.isComplete(), "两段都应齐备；缺失段: " + raw.missingKeys());
        assertFalse(raw.splitThreshold().isBlank(), "#21 取值不得为空");
        assertTrue(raw.anomalyRuleJson().contains("window_days"),
                "#22 应是含 window_days 的 JSON；实际: " + raw.anomalyRuleJson());
    }

    @Test
    @DisplayName("#21 解析值恰为 0.30 —— 逐段点名，防『读错相邻行』")
    void slot_21_value_is_exactly_the_declared_threshold() {
        CrossStoreThresholds t = CrossStoreThresholds.fromRawConfig(source.raw());

        assertEquals(0, new BigDecimal("0.30").compareTo(t.splitThreshold()),
                "#21 cfg:crossstore.split_threshold 声明初值应为 0.30，实际: " + t.splitThreshold());
    }

    @Test
    @DisplayName("#22 解析值恰为声明初值：窗口 30 / 标记 2 / 冻结 3 / 占比 30 —— 逐段点名")
    void slot_22_values_are_exactly_the_declared_rule() {
        CrossStoreThresholds t = CrossStoreThresholds.fromRawConfig(source.raw());

        assertEquals(30, t.windowDays(), "#22 window_days");
        assertEquals(2, t.storeCountMark(), "#22 store_count_mark");
        assertEquals(3, t.storeCountFreeze(), "#22 store_count_freeze");
        assertEquals(30, t.crossSharePct(), "#22 cross_share_pct");
    }

    // ------------------------------------------------------------------ 断线必须大声失败

    @Test
    @DisplayName("🛑 两段中任一段取不到时必须抛（fail-closed），不得回落默认值")
    void missing_segment_must_throw_not_fall_back() {
        // 直接构造"缺段"的原始声明（模拟相对角色：seed 行被删 / 键被改名）
        assertThrows(BizException.class,
                () -> CrossStoreThresholds.fromRawConfig(new CrossStoreRawConfig(null, "{\"window_days\":30}")),
                "缺 #21 时必须抛 —— 『取不到就用默认』会让总部改的数字长期不生效");
        assertThrows(BizException.class,
                () -> CrossStoreThresholds.fromRawConfig(new CrossStoreRawConfig("0.30", " ")),
                "缺 #22 时必须抛");
        assertThrows(BizException.class,
                () -> CrossStoreThresholds.fromRawConfig(null));
    }

    @Test
    @DisplayName("🛑 #22 缺任一字段必须抛，不得用默认值补齐")
    void missing_anomaly_field_must_throw() {
        assertThrows(BizException.class,
                () -> CrossStoreThresholds.fromRawConfig(new CrossStoreRawConfig(
                        "0.30", "{\"window_days\":30,\"store_count_mark\":2,\"store_count_freeze\":3}")),
                "缺 cross_share_pct 时必须抛 —— 补默认会让反刷信号静默失效");
    }

    @Test
    @DisplayName("🛑 与 seed 的连线断掉必须红：SEED_RESOURCE 指向的文件真的在 classpath 上")
    void the_seed_resource_string_is_a_live_wire() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            assertNotNull(is, "classpath 上找不到 " + SEED_RESOURCE
                    + " —— 跨店口径来源断开，本类与实现类的 SEED_RESOURCE 都指向它");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // 两行的 (编号, 键) 必须真的在文件里（文件被重排 / 行被删即红）
            assertTrue(sql.contains("cfg:crossstore.split_threshold"),
                    "seed 里找不到 #21 的键 —— 实现类会抛，但本断言让它红在更靠前的地方");
            assertTrue(sql.contains("cfg:crossstore.anomaly_rule"),
                    "seed 里找不到 #22 的键");
        }
    }

    @Test
    @DisplayName("来源自描述含两段的编号与键（进日志可回溯『按哪份口径做的』）")
    void describe_source_names_both_slots() {
        String d = source.describeSource();
        assertTrue(d.contains("cfg:crossstore.split_threshold"), "自描述应点名 #21；实际: " + d);
        assertTrue(d.contains("cfg:crossstore.anomaly_rule"), "自描述应点名 #22；实际: " + d);
    }

    // ------------------------------------------------------------------ 非法口径不得静默夹取

    @Test
    @DisplayName("🛑 阈值越界 / JSON 非法 / 单调性破坏 —— 一律拒绝，不夹取不回落")
    void illegal_declared_values_are_rejected() {
        // 阈值把百分数当比例（30 而非 0.30）
        assertThrows(BizException.class, () -> CrossStoreThresholds.fromRawConfig(
                new CrossStoreRawConfig("30",
                        "{\"window_days\":30,\"store_count_mark\":2,\"store_count_freeze\":3,\"cross_share_pct\":30}")),
                "阈值 30 越界 —— 夹到 1.0 会让『每次都按占比拆』静默生效");

        // #22 JSON 非法
        assertThrows(BizException.class, () -> CrossStoreThresholds.fromRawConfig(
                new CrossStoreRawConfig("0.30", "{not json")));

        // 冻结线不高于标记线（两条独立信号自相矛盾）
        assertThrows(BizException.class, () -> CrossStoreThresholds.fromRawConfig(
                new CrossStoreRawConfig("0.30",
                        "{\"window_days\":30,\"store_count_mark\":3,\"store_count_freeze\":3,\"cross_share_pct\":30}")),
                "冻结线 == 标记线 ⇒ 先冻后标，两个各自合法的正整数会静默共存");
    }
}