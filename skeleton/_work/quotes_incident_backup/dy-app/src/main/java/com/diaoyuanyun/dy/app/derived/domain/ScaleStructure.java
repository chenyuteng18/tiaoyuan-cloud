package com.diaoyuanyun.dy.app.derived.domain;

import com.diaoyuanyun.dy.app.scale.domain.ScaleDomain;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 量表结构的<b>最小事实</b> —— 派生口径做自洽断言时唯一需要知道的三个数。
 *
 * <h2>为什么它单独存在，而不直接在 {@link DerivedMetricProfile} 里写 4 和 7</h2>
 * {@link DerivedMetricProfile} 需要断言三件跨源一致的事：
 * <ol>
 *   <li>config {@code #33} 的 {@code module_total_max} 必须等于"每模块题数 × 单题上限"；</li>
 *   <li>config {@code #45} 的 {@code d.answered}（题组完成度分母）必须等于同源复评题数；</li>
 *   <li>同源复评题数 = 维度数 × 每维题数（PRD 附录 C.1.5 的结构事实）。</li>
 * </ol>
 * 若在 {@link DerivedMetricProfile} 里写 {@code 16} 与 {@code 28}，则量程口径一旦改判
 * （v1.7 就发生过一次：0–3 四级 → <b>0–4 五级</b>）这里会<b>静默失配</b> ——
 * 校验依然通过，但它校验的是旧的尺。故三个数一律<b>推导</b>得到。
 *
 * <h2>为什么这里自己解析 config {@code #35}，而不是复用 S1-4 的解析器</h2>
 * S1-4 的解析器（{@code ConfigSeedScaleProfileSource} + {@code ScaleScoringProfile}）
 * 在 {@code app.scale.service} 包 —— 而领域层<b>不得</b>依赖服务层
 * （{@code ArchitectureBoundaryTest} 的 R4 规则：领域模型必须是无依赖的值对象）。
 * 若为了省一次解析而让 domain 依赖 service，守卫会红，且那是<b>对的</b>：
 * 领域层一旦能反向取服务，它就不再可独立测试。
 *
 * <p>故此处只做<b>一次极小的</b>读取：从 classpath 上的
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 抓 {@code #35} 行的
 * {@code range.max}。结构性事实（每维题数 / 维度数）取自 S1-4 的
 * {@link ScaleDomain}（同层依赖，非反向依赖）。
 *
 * <h2>🛑 fail-closed</h2>
 * 取不到 {@code #35} 即抛（口径缺失 = 拒绝计算），<b>绝不</b>回落到 {@code 4}。
 */
public final class ScaleStructure {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources，dy-app 依赖它故可见）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    private static final String RANGE_RULE_KEY = "cfg:scale.range_rule";

    /**
     * 抓 {@code #35} 行 JSON 取值（形态与 S1-4 的解析器一致：锚定 config_no 与 key 同时出现）。
     *
     * <p>取值本身是 JSON（用双引号），不含单引号，故单引号对之间的内容即完整取值。
     */
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "\\(\\s*35\\s*,\\s*'" + Pattern.quote(RANGE_RULE_KEY) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                    + "\\s*[^,]*,\\s*[^,]*,\\s*'([^']*)'",
            Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScaleStructure() {
    }

    /** 每模块题数 —— 结构性事实（PRD 附录 C.1.5：每维度 4 题）。 */
    public static int itemsPerModule() {
        return ScaleDomain.ITEMS_PER_DIMENSION;
    }

    /** 模块数 / 维度数 —— 结构性事实（PRD 附录 C.1.3：7 维枚举）。 */
    public static int moduleCount() {
        return ScaleDomain.DIMENSION_COUNT;
    }

    /** 单题上限 —— 来自 config {@code #35} 的 {@code range.max}（当前 4）。 */
    public static int itemMax() {
        return readRangeNode().path("max").asInt();
    }

    /** 单题下限 —— 来自 config {@code #35} 的 {@code range.min}（当前 0）。 */
    public static int itemMin() {
        return readRangeNode().path("min").asInt();
    }

    /**
     * 同源复评题数（= 维度数 × 每维题数，当前 28）。
     *
     * <p>与 S1-4 的 {@code ScaleScoringEngine#requiredItemCount()} 同源同值 ——
     * 两者都是"由结构推导"，不是各自写一个 28。
     */
    public static int requiredItemCount() {
        return moduleCount() * itemsPerModule();
    }

    /**
     * 模块满分（= 每模块题数 × 单题上限，当前 16）。
     *
     * <p>规格 §1.4 的 MCID 分档对象就是这个量纲（「每模块 4 题（0–16）」）。
     */
    public static int moduleTotalMax() {
        return itemsPerModule() * itemMax();
    }

    private static JsonNode readRangeNode() {
        String sql;
        try (InputStream is = ScaleStructure.class.getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "量表结构来源断开，拒绝按默认量程断言口径自洽");
            }
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "读取 " + SEED_RESOURCE + " 失败: " + e.getMessage(), e);
        }
        Matcher m = ROW_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config #35（" + RANGE_RULE_KEY + "）的声明行 —— "
                            + "量表结构来源断开");
        }
        try {
            JsonNode range = MAPPER.readTree(m.group(1)).path("range");
            if (!range.path("min").isInt() || !range.path("max").isInt()) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "config #35 的 range 段缺 min/max（须为整数）");
            }
            return range;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #35 的声明值不是合法 JSON: " + e.getMessage());
        }
    }
}