package com.diaoyuanyun.dy.app.scale.service;

import com.diaoyuanyun.dy.app.scale.domain.ScaleProfileSource;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分口径来源的<b>过渡实现</b>：直接读 config 声明表种子文件里的 {@code #35} 初始值。
 *
 * <h2>它为什么存在（而不是直接读 DB 配置表）</h2>
 * 配置真相源（{@code app_config} / {@code config_slot}）目前<b>只建在
 * dy-config 的独立门禁库里</b>，应用库 {@code diaoyuanyun_dev} 里还没有这两张表 ——
 * 这正是骨架 README「未完成项与 TODO」里已登记的一条收尾令：
 * 「配置真相源落库（{@code ConfigServiceImpl} 仍内存实现）」。
 *
 * <p>若 S1-4 硬绑 DB 配置服务，它就会因一条<b>与本任务无关</b>的收尾令而无法开工。
 * 而 S1-4 的验收要求是「评分口径<b>外置为配置项</b>（代码内 grep 无硬编码分值）」——
 * 它要求的是"口径有一个<b>代码之外的来源</b>且可替换"，不是"配置必须已经落库"。
 *
 * <h2>读的是声明文件的哪一段，为什么这样不算"抄一份"</h2>
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 是 PRD §10
 * 「附：可配置项清单（<b>硬性要求 —— 所有业务数字不得硬编码</b>）」的<b>唯一声明载体</b>，
 * 且它在 dy-app 的 classpath 上（dy-app 依赖 dy-config，其 resources 随之可见）。
 * 本实现<b>解析</b>该文件的 {@code #35} 行取值，而<b>不是</b>抄一份 JSON 进 dy-app：
 * <ul>
 *   <li>抄一份会在两处形成同源数据，改一处不改另一处时<b>不报错</b>，只让计分口径分叉；</li>
 *   <li>解析则把 {@code #35} 的声明值变成唯一真相源 —— 改声明文件，计分口径立刻跟着变。</li>
 * </ul>
 * 这一点的机械保证见 {@code ScaleProfileSourceTest}：
 * 它断言"能解析出值"，并在文件被改名/移走时立刻红（而不是静默退化成抛错被上层吞掉）。
 *
 * <h2>🛑 收尾令完成后的替换方式</h2>
 * 配置真相源落库后，把本类换成一个读 {@code cfg:scale.range_rule} 的实现即可
 * （{@link ScaleProfileSource} 是为此留的端口）。计分引擎与题库服务<b>零改动</b>。
 *
 * <h2>fail-closed</h2>
 * 解析不出值一律抛（口径缺失 = 拒绝计分），<b>绝不</b>返回默认口径 ——
 * 量程口径已被改判过一次（v1.7：0–3 四级 → 0–4 五级），
 * "取不到就用默认"会让口径丢失静默成"口径是旧的"。
 */
@Component
public class ConfigSeedScaleProfileSource implements ScaleProfileSource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** #35 的键（与 02_slots_seed.sql 逐字一致）。 */
    private static final String CONFIG_KEY = "cfg:scale.range_rule";

    /**
     * 从种子文件里抓 {@code #35} 那一行的 JSON 取值。
     *
     * <p>形态（02_slots_seed.sql 实际写法）：
     * <pre>
     *   (35, 'cfg:scale.range_rule', 'JSON', NULL, NULL,
     *    '{"range":{"min":0,"max":4,"levels":5},"dimension_max":16,"total_max":112,...}',
     *    '量表量程与题组口径',
     *    '...'),
     * </pre>
     * 取值本身不含单引号（是 JSON，用双引号），故单引号对之间的内容即完整取值。
     * 该正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目。
     */
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "\\(\\s*35\\s*,\\s*'" + Pattern.quote(CONFIG_KEY) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                    + "\\s*[^,]*,\\s*[^,]*,\\s*'([^']*)'",
            Pattern.DOTALL);

    @Override
    public String rangeRuleJson() {
        String sql = readSeedResource();
        Matcher m = ROW_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config #35（" + CONFIG_KEY + "）的声明行 —— "
                            + "量程口径来源断开，拒绝按默认口径计分");
        }
        String json = m.group(1);
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #35 的声明值为空 —— 量程口径缺失，拒绝按默认口径计分");
        }
        return json;
    }

    @Override
    public String describeSource() {
        return "classpath:" + SEED_RESOURCE + " #35 " + CONFIG_KEY
                + "（过渡来源；配置真相源落库后应替换为 DB 读取实现）";
    }

    private String readSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "量程口径来源断开。若该文件被移动，请同步更新本类的 SEED_RESOURCE。");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "读取 " + SEED_RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }
}