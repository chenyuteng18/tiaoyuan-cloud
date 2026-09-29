package com.diaoyuanyun.dy.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-2 配置门禁：<b>prod 段的 {@code dy.idempotency.backend} 必须显式给一个非 memory 的默认值</b>。
 *
 * <h2>它为什么必须存在（而不是"代码里有自检就够了"）</h2>
 * B-2 有<b>两道</b>防线，各自挡一个方向：
 * <ol>
 *   <li>{@code IdempotencyConfiguration.reject_memory_backend_in_production}（启动自检）
 *       —— 挡的是"配错了还启动起来"（例如有人用环境变量覆盖成 memory，
 *       或把本段删了、环境里也没有本项，于是回落到代码里的 memory 默认值）；</li>
 *   <li>本门禁 —— 挡的是"本段被删掉 / 被改成 memory"。
 *       🛑 关键在于：如果只有防线 ①，本段被删这件事<b>不一定</b>会被 ① 抓住 ——
 *       因为 ① 只在"解析结果为 memory"时才抛，而本段的存在与否是
 *       <b>配置层</b>的事实。删掉本段 ⇒ prod 依赖被 ① 抓住（good）；
 *       但把本段改成 {@code memory} —— 那也会被 ① 抓住。那为什么还要本门禁？</li>
 * </ol>
 * 理由是<b>取证边界</b>：① 跑在运行期（需要启动 context 才能跑到），
 * 而本类断言的是<b>交付物本身</b>（{@code application.yml} 里到底写了什么）。
 * 两者回答的是不同的问题："这份配置会不会被拒" vs "这份配置里写了 redis 吗"。
 * 后者可以在<b>任何一次构建</b>上机械核对，而不必等到部署；
 * 更重要的是：若有人把 ① 的判据弄坏（例如把 profile 判断写反），
 * 本门禁是<b>最后一道</b>仍然独立成立的事实 —— 它断言的是文件内容，不是代码行为。
 *
 * <h2>为什么用 snakeyaml 真解析，而不是 grep</h2>
 * {@code application.yml} 有多个 YAML 文档（{@code ---} 分隔 dev / prod）。
 * grep 只能证明"文件里某处出现过 redis"—— 而 redis 字样完全可能出现在
 * <b>注释里</b>（本文件那段 B-2 说明里就有）。真解析到 prod 文档、再取
 * {@code dy.idempotency.backend} 这个<b>键路径</b>，才能证明"prod 环境下解析出的就是这个值"。
 * 本仓已有先例：{@code DocFileBoundaryGateTest} 用 {@code Yaml.loadAll} 断言 multipart 上限。
 */
@DisplayName("B-2 配置：prod 段的幂等后端默认值必须显式非 memory")
class IdempotencyProdBackendConfigGateTest {

    /** 与 IdempotencyConfiguration.PROD_PROFILE 逐字一致。 */
    private static final String PROD_PROFILE = "prod";

    private static final String BACKEND_KEY = "dy.idempotency.backend";

    /** prod 段默认值必须匹配的形状：字面 {@code redis}，或 {@code ${ENV_VAR:redis}}。 */
    private static final java.util.regex.Pattern REDIS_DEFAULT =
            java.util.regex.Pattern.compile("^(redis|\\$\\{[A-Za-z0-9_]+:redis\\})$");

    @Test
    @DisplayName("🛑 prod 段必须显式给出 dy.idempotency.backend 且默认值为 redis")
    void prod_profile_declares_a_redis_default_for_the_idempotency_backend() {
        Object value = prodBackendValue();
        assertNotNull(value,
                "🛑 application.yml 的 prod 段里找不到 " + BACKEND_KEY + "。\n"
                        + "缺失的后果：prod 会回落到代码里的 memory 默认值 —— 内存幂等后端只能挡住"
                        + "【同一实例内】的重复提交，多实例下重复下单 / 重复扣款 / 重复发放照常发生，"
                        + "而日志与监控全部正常。\n"
                        + "（启动自检 IdempotencyConfiguration.reject_memory_backend_in_production 会兜底，"
                        + "但那是运行期才生效的一道防线；本门禁要求交付物本身就把话说清楚。）");

        String v = String.valueOf(value).trim();
        assertTrue(REDIS_DEFAULT.matcher(v).matches(),
                "🛑 prod 段的 " + BACKEND_KEY + " 默认值必须是 redis（或 ${ENV:redis} 形态），实际: " + v + "。\n"
                        + "若这里是 memory，多实例生产环境的幂等防线会【静默】失效。");
    }

    @Test
    @DisplayName("B-2 门禁自身有判别力：非 prod 文档【不得】被当成 prod 段（元层自证）")
    void the_prod_document_is_identified_by_its_profile_activation_not_by_position() {
        // 元层判别力自证：本门禁靠 "spring.config.activate.on-profile == prod" 定位文档。
        // 若这个定位逻辑退化成"取第一个/最后一个文档"，那么有人调整 YAML 文档顺序后
        // 门禁会静默断言到【dev 或 prod 占位段】上 —— 一个恒绿的门禁。
        // 此处断言：确实存在一个 profile 恰为 prod 的文档，且它【不是】第一个文档
        // （application.yml 现实结构：首文档无 profile，随后是 dev，再是 prod）。
        int prodDocs = 0;
        int index = -1;
        int total = 0;
        int prodIndex = -1;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath 下找不到 application.yml");
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> m) {
                    if (isProdDocument(m)) {
                        prodDocs++;
                        prodIndex = index;
                    }
                }
                index++;
                total++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败: " + e.getMessage(), e);
        }

        assertTrue(total > 1, "application.yml 应有多个 YAML 文档（dev / prod 用 --- 分隔），实际 " + total);
        assertEquals(1, prodDocs, "应恰有一个 on-profile=prod 的文档，实际 " + prodDocs);
        assertTrue(prodIndex > 0,
                "prod 文档不应是第一个文档（首个文档是无 profile 的公共段）。"
                        + "若此处为 0，说明文档结构变了，本门禁的定位逻辑需要复核。");
    }

    /** 取 prod 文档里的 dy.idempotency.backend；找不到 prod 文档或键即返回 null。 */
    @SuppressWarnings("unchecked")
    private static Object prodBackendValue() {
        try (InputStream in = IdempotencyProdBackendConfigGateTest.class
                .getResourceAsStream("/application.yml")) {
            if (in == null) {
                return null;
            }
            for (Object doc : new Yaml().loadAll(in)) {
                if (!(doc instanceof Map<?, ?> m) || !isProdDocument((Map<Object, Object>) m)) {
                    continue;
                }
                Object dy = m.get("dy");
                if (!(dy instanceof Map<?, ?> dyMap)) {
                    return null;
                }
                Object idem = dyMap.get("idempotency");
                if (!(idem instanceof Map<?, ?> idemMap)) {
                    return null;
                }
                return idemMap.get("backend");
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败: " + e.getMessage(), e);
        }
        return null;
    }

    /** 该 YAML 文档是否由 {@code spring.config.activate.on-profile: prod} 激活。 */
    private static boolean isProdDocument(Map<?, ?> doc) {
        Object spring = doc.get("spring");
        if (!(spring instanceof Map<?, ?> springMap)) {
            return false;
        }
        Object config = springMap.get("config");
        if (!(config instanceof Map<?, ?> configMap)) {
            return false;
        }
        Object activate = configMap.get("activate");
        if (!(activate instanceof Map<?, ?> activateMap)) {
            return false;
        }
        Object profile = activateMap.get("on-profile");
        return profile != null && PROD_PROFILE.equalsIgnoreCase(String.valueOf(profile).trim());
    }
}