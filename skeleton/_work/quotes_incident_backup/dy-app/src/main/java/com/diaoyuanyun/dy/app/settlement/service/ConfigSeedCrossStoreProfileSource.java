package com.diaoyuanyun.dy.app.settlement.service;

import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreProfileSource;
import com.diaoyuanyun.dy.app.settlement.domain.CrossStoreRawConfig;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨店通兑域口径来源的<b>过渡实现</b>：直接读 config 声明表种子文件里的
 * {@code #21} / {@code #22} 两行初始值。
 *
 * <h2>它为什么存在（而不是直接读 DB 配置表）</h2>
 * 与 {@code ConfigSeedRefundProfileSource} / {@code ConfigSeedBandProfileSource} /
 * {@code ConfigSeedDerivedProfileSource} 同源：配置真相源（{@code app_config} /
 * {@code config_slot}）的<b>真实生产读路径</b>当前仍是这几个 {@code ConfigSeed*} 解析器
 * （各自类头逐字登记"落库后应替换为 DB 读取实现"）。
 *
 * <p>若本域硬绑 DB 配置服务，{@code CrossStoreSettlement} 会因一条<b>与本任务无关</b>的
 * 收尾令而无法开工。而本域的验收要求是「阈值外置、有代码之外的唯一来源」，
 * 不是「配置必须已落库」。
 *
 * <h2>🛑 本次修复的核心：让「总部唯一可改」这句话成立</h2>
 * 此前 {@code CrossStoreSettlement} 把 {@code 0.30} 硬编码为
 * {@code DEFAULT_SPLIT_THRESHOLD}，{@code detectAnomaly} 又把 {@code 2} / {@code 3}
 * 直接写在代码里 —— 而 {@code #21} 的 seed 语义列逐字写着
 * 「<b>归属层级=总部唯一，非校准项</b>」。两者相加 ⇒ 总部改配置<b>不会有效果</b>。
 * 本实现把 {@code #21} / {@code #22} 变成<b>真实运行时消费点</b>：
 * 改 seed（或未来改 DB 配置）→ 结算行为立刻跟着变。
 * 这条"真的会变"由 {@code CrossStoreProfileSourceTest} 与结算行为测试双向钉住。
 *
 * <h2>🛑 与 {@code #10}/#40/#43 等的解析互不复用</h2>
 * 各域各自持有自己的 {@code SEED_RESOURCE} 解析器，<b>不共用一个"通用配置读取器"</b>：
 * 通用读取器会把"读哪一行"变成一个字符串参数，而那个参数正是最容易被改错、
 * 且改错了会静默读到相邻条目的东西。本域的两行正则各自锚定<b>编号 + 键</b>。
 *
 * <h2>fail-closed</h2>
 * 任一段解析不出值一律抛（口径缺失 = 拒绝结算），<b>绝不</b>返回默认口径 ——
 * 本域两段都直接决定"钱在哪家店、按什么比例"。
 */
@Component
public class ConfigSeedCrossStoreProfileSource implements CrossStoreProfileSource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /**
     * 两段声明的 (编号, 键) —— 顺序与 {@link CrossStoreRawConfig} 的字段顺序一致。
     *
     * <p>🛑 顺序不得随手调整：{@link #raw()} 按下标取值，
     * 顺序与 record 字段错位会让"#21 的 0.30"被读成"#22 的占比数字"——
     * 两者都是合法数字，不报错，只让拆分阈值变成 30 或 100。
     */
    private static final List<Spec> SPECS = List.of(
            new Spec(21, "cfg:crossstore.split_threshold", "#21 跨店拆分阈值"),
            new Spec(22, "cfg:crossstore.anomaly_rule", "#22 跨店异常阈值"));

    /**
     * 抓一行的方法：{@code (编号, '键', '类型', …, …, '初始值', 'PRD 名称', '语义')}。
     *
     * <p>形态（02_slots_seed.sql 实际写法，取值列前面有 4 列：
     * config_no / key / value_type / allowed_values / forbidden_values）：
     * <pre>
     *   (21, 'cfg:crossstore.split_threshold', 'DECIMAL', NULL, NULL,
     *    '0.30',
     *    '跨店拆分阈值',
     *    '…'),
     * </pre>
     * 正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目 ——
     * 本域尤其关键：{@code #22} 的取值是 JSON（以 <code>{"</code> 开头），
     * {@code #23} 的键名与 {@code #21} 同前缀（{@code cfg:crossstore.*}），
     * 只锚编号或只锚前缀都会在文件被重排后抓到错行。
     */
    private static Pattern rowPattern(int configNo, String key) {
        return Pattern.compile(
                "\\(\\s*" + configNo + "\\s*,\\s*'" + Pattern.quote(key) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                        + "\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,"
                        + "\\s*'([^']*)'",
                Pattern.DOTALL);
    }

    @Override
    public CrossStoreRawConfig raw() {
        String sql = readSeedResource();
        return new CrossStoreRawConfig(
                valueOf(sql, SPECS.get(0)),
                valueOf(sql, SPECS.get(1)));
    }

    /**
     * 抓一行并返回其取值；抓不到即抛，并在消息里点名是哪个编号。
     *
     * <p>🛑 不回落到 {@code null}：返回 {@code null} 会把"这一行被删了"
     * 静默转成上游的 {@code isComplete()} 判定，而那条判定说出的理由是"配置缺失"、
     * 不指向具体编号 —— 排查要从若干个编号里一个个试。
     */
    private static String valueOf(String sql, Spec spec) {
        Matcher m = rowPattern(spec.configNo(), spec.key()).matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config " + spec.describe()
                            + " 的声明行 —— 跨店口径来源断开，拒绝按默认口径结算");
        }
        String v = m.group(1);
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config " + spec.describe() + " 的声明值为空 —— 跨店口径缺失，拒绝按默认口径结算");
        }
        return v;
    }

    @Override
    public String describeSource() {
        return "classpath:" + SEED_RESOURCE + " "
                + SPECS.stream().map(Spec::describe).toList()
                + "（过渡来源；配置真相源读路径切换后应替换为 DB 读取实现）";
    }

    private String readSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "跨店口径来源断开。若该文件被移动，请同步更新本类的 SEED_RESOURCE。");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "读取 " + SEED_RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }

    /** 一行声明的标识（编号 + 键 + 人话名）。 */
    private record Spec(int configNo, String key, String humanName) {

        String describe() {
            return "#" + configNo + "（" + key + " · " + humanName + "）";
        }
    }
}