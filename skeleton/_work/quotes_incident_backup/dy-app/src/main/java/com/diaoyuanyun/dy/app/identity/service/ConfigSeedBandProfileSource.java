package com.diaoyuanyun.dy.app.identity.service;

import com.diaoyuanyun.dy.app.identity.domain.BandVisibilityMatrix;
import com.diaoyuanyun.dy.app.identity.domain.BandVisibilitySource;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手环可见性口径来源的<b>过渡实现</b>：直接读 config 声明表种子文件里的
 * {@code #43}（{@code cfg:band.visibility}）行。
 *
 * <h2>它为什么存在（而不是直接读 DB 配置表）</h2>
 * 与 {@code ConfigSeedRefundProfileSource} / {@code ConfigSeedDerivedProfileSource}
 * 同源：配置真相源（{@code app_config} / {@code config_slot}）目前只建在 dy-config 的
 * 独立门禁库里，应用库还没有这两张表 —— 这正是骨架 README「未完成项与 TODO」里
 * 已登记的收尾令「配置真相源落库（{@code ConfigServiceImpl} 仍内存实现）」。
 *
 * <p>若本域硬绑 DB 配置服务，A2 会因一条<b>与本任务无关</b>的收尾令而无法开工。
 * 而本域的验收要求是「可见性口径外置、有代码之外的唯一来源」，不是「配置必须已落库」。
 *
 * <h2>读的是声明文件的哪一段，为什么这样不算"抄一份"</h2>
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 是 PRD §10
 * 「附：可配置项清单（<b>硬性要求 —— 所有业务数字不得硬编码</b>）」的唯一声明载体，
 * 且它在 dy-app 的 classpath 上。本实现<b>解析</b>该文件的 {@code #43} 行取值，
 * 而<b>不是</b>抄一份 JSON 进 dy-app：
 * <ul>
 *   <li>抄一份会在两处形成同源数据，改一处不改另一处时<b>不报错</b>，
 *       只让可见性口径分叉 —— 而分叉的是"客户能不能看到自己的手环数据"
 *       与"内部缺口原因会不会外泄"；</li>
 *   <li>解析则把声明值变成唯一真相源 —— 改声明文件，A2 下发的档位立刻跟着变。</li>
 * </ul>
 * 这一点的机械保证见 {@code BandVisibilitySourceTest}：它断言"能解析出 {@code #43} 行"，
 * 并在文件被改名 / 移走、或该行被删掉时<b>立刻红</b>
 * （而不是静默退化成抛错被上层吞掉）。
 *
 * <h2>🛑 与 {@code #40}（退款可见性）的解析互不复用</h2>
 * 两个域各自持有自己的 {@code SEED_RESOURCE} 解析器，<b>不共用一个"通用配置读取器"</b>：
 * 通用读取器会把"读哪一行"变成一个字符串参数，而那个参数正是最容易被改错、
 * 且改错了会静默读到相邻条目（{@code #40} 与 {@code #43} 的取值都是 JSON，
 * 都以 <code>{"</code> 开头）的东西。两份正则各自锚定<b>编号 + 键</b>，
 * 是"读错行"这件事的低成本防线。
 *
 * <h2>fail-closed</h2>
 * 解析不出值一律抛（口径缺失 = 拒绝启用），<b>绝不</b>返回默认口径 ——
 * 本口径的每一格都直接决定"客户能不能看到自己的数据"与"内部信息会不会外泄"。
 */
@Component
public class ConfigSeedBandProfileSource implements BandVisibilitySource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** {@code #43} 的编号与键 —— 两者必须<b>同时</b>锚定，见 {@link #rowPattern()}。 */
    private static final int CONFIG_NO = 43;
    private static final String CONFIG_KEY = "cfg:band.visibility";

    /**
     * 抓 {@code #43} 那一行的方法：{@code (43, 'cfg:band.visibility', 'JSON', NULL, NULL, '<取值>', …)}。
     *
     * <p>形态（02_slots_seed.sql 实际写法，取值列前面有 4 列：
     * config_no / key / value_type / allowed_values / forbidden_values）：
     * <pre>
     *   (43, 'cfg:band.visibility', 'JSON', NULL, NULL,
     *    '{...}',
     *    '手环数据可见性矩阵（band_visibility）',
     *    '…'),
     * </pre>
     * 正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目 ——
     * 这一点在本域尤其关键：{@code #40} 与 {@code #43} 的取值都是 JSON 且都以
     * <code>{"</code> 开头，只锚编号很容易在文件被重排后抓到错行；
     * 只锚键则在键被改名后静默失配（本类会抛，故是安全的失败方向）。
     *
     * <p>取值列自身是 JSON（含双引号与花括号），但<b>不含单引号</b> ——
     * SQL 里 JSON 用双引号，外层用单引号包裹。故"单引号对之间的内容"即完整取值。
     */
    private static Pattern rowPattern() {
        return Pattern.compile(
                "\\(\\s*" + CONFIG_NO + "\\s*,\\s*'" + Pattern.quote(CONFIG_KEY) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                        + "\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,"
                        + "\\s*'([^']*)'",
                Pattern.DOTALL);
    }

    @Override
    public BandVisibilityMatrix matrix() {
        String sql = readSeedResource();
        Matcher m = rowPattern().matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config #" + CONFIG_NO
                            + "（" + CONFIG_KEY + "）的声明行 —— 手环可见性口径来源断开，"
                            + "拒绝按默认口径启用手环档位下发");
        }
        String json = m.group(1);
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #" + CONFIG_NO + "（" + CONFIG_KEY + "）的声明值为空 —— "
                            + "可见性口径缺失，拒绝按默认口径启用");
        }
        return BandVisibilityMatrix.fromConfigJson(json);
    }

    @Override
    public String describeSource() {
        return "classpath:" + SEED_RESOURCE + " #" + CONFIG_NO + "（" + CONFIG_KEY + "）"
                + "（过渡来源；配置真相源落库后应替换为 DB 读取实现）";
    }

    private String readSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "手环可见性口径来源断开。若该文件被移动，"
                                + "请同步更新本类的 SEED_RESOURCE。");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "读取 " + SEED_RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }
}