package com.diaoyuanyun.dy.app.derived.service;

import com.diaoyuanyun.dy.app.derived.domain.DerivedProfileSource;
import com.diaoyuanyun.dy.app.derived.domain.DerivedRawConfig;
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
 * 派生口径来源的<b>过渡实现</b>：直接读 config 声明表种子文件里的
 * {@code #4 / #5 / #6 / #7 / #33 / #45} 六行初始值。
 *
 * <h2>它为什么存在（而不是直接读 DB 配置表）</h2>
 * 与 S1-4 的 {@code ConfigSeedScaleProfileSource} 同一条理由，此处不重复展开：
 * 配置真相源（{@code app_config} / {@code config_slot}）目前只建在 dy-config 的
 * 独立门禁库里，应用库 {@code diaoyuanyun_dev} 里还没有这两张表 ——
 * 这正是骨架 README「未完成项与 TODO」里已登记的收尾令
 * 「配置真相源落库（{@code ConfigServiceImpl} 仍内存实现）」。
 *
 * <p>若 S1-5 硬绑 DB 配置服务，它会因一条<b>与本任务无关</b>的收尾令而无法开工。
 * 而 S1-5 的验收要求是「依从性 / AS / 效果判定<b>只在服务端计算</b>」——
 * 它要求的是"计算有唯一权威、口径有代码之外的来源"，不是"配置必须已经落库"。
 *
 * <h2>读的是声明文件的哪一段，为什么这样不算"抄一份"</h2>
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 是 PRD §10
 * 「附：可配置项清单（<b>硬性要求 —— 所有业务数字不得硬编码</b>）」的<b>唯一声明载体</b>，
 * 且它在 dy-app 的 classpath 上。本实现<b>解析</b>该文件的六行取值，而<b>不是</b>抄一份
 * 常量进 dy-app：
 * <ul>
 *   <li>抄一份会在两处形成同源数据，改一处不改另一处时<b>不报错</b>，
 *       只让派生口径分叉 —— 而分叉的是"客户能不能退费"的依据；</li>
 *   <li>解析则把声明值变成唯一真相源 —— 改声明文件，判定口径立刻跟着变。</li>
 * </ul>
 * 这一点的机械保证见 {@code DerivedProfileSourceTest}：它断言"能解析出六段值"，
 * 并在文件被改名/移走、或某一行被删掉时<b>立刻红</b>
 * （而不是静默退化成抛错被上层吞掉）。
 *
 * <h2>🛑 收尾令完成后的替换方式</h2>
 * 配置真相源落库后，把本类换成一个读 {@code app_config} 的实现即可
 * （{@link DerivedProfileSource} 是为此留的端口）。三个引擎<b>零改动</b>。
 *
 * <h2>fail-closed</h2>
 * 任一段解析不出值一律抛（口径缺失 = 拒绝计算），<b>绝不</b>返回默认口径 ——
 * 六段中有四段（门槛 / 权重 / 缺失策略 / MCID）直接决定退款门禁与效果判定，
 * "取不到就用默认"会让一个无人复核的默认阈值进入面客判定。
 */
@Component
public class ConfigSeedDerivedProfileSource implements DerivedProfileSource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /**
     * 一次抓一行的方法：{@code (编号, '键', '类型', … , '初始值', 'PRD 名称', '语义')}。
     *
     * <p>形态（02_slots_seed.sql 实际写法，取值列前面有 4 列：config_no / key / value_type /
     * allowed_values / forbidden_values）：
     * <pre>
     *   (4, 'cfg:adherence.pass_threshold', 'DECIMAL', NULL, NULL,
     *    '0.80',
     *    '依从性达标阈值 AS',
     *    '…'),
     * </pre>
     * 正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目
     * （例如 {@code #4} 与 {@code #40} 都以 "4" 开头；只锚编号会抓错）。
     *
     * <p>取值列自身不含单引号：数值型是纯数字；{@code JSON} 型用双引号；
     * {@code ENUM} 型（如 {@code #6}）是 {@code 'structural_keep'} 这样的裸枚举字面。
     * 故"单引号对之间的内容"即完整取值。
     */
    private static Pattern rowPattern(int configNo, String key) {
        return Pattern.compile(
                "\\(\\s*" + configNo + "\\s*,\\s*'" + Pattern.quote(key) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                        + "\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,"
                        + "\\s*'([^']*)'",
                Pattern.DOTALL);
    }

    /** 六段声明的 (编号, 键) —— 顺序与 {@link DerivedRawConfig} 的字段顺序一致。 */
    private static final List<Spec> SPECS = List.of(
            new Spec(4, "cfg:adherence.pass_threshold", "#4 依从性达标阈值"),
            new Spec(5, "cfg:adherence.weights", "#5 依从性权重（拆两套）"),
            new Spec(6, "cfg:adherence.missing_policy", "#6 缺失值处理策略"),
            new Spec(7, "cfg:adherence.min_sample_days", "#7 AS 样本护栏"),
            new Spec(33, "cfg:verdict.mcid_threshold", "#33 MCID 最小可察觉改善门槛"),
            new Spec(45, "cfg:verdict.confidence_formula", "#45 判定置信度合成口径"));

    @Override
    public DerivedRawConfig raw() {
        String sql = readSeedResource();
        return new DerivedRawConfig(
                valueOf(sql, SPECS.get(0)),
                valueOf(sql, SPECS.get(1)),
                valueOf(sql, SPECS.get(2)),
                valueOf(sql, SPECS.get(3)),
                valueOf(sql, SPECS.get(4)),
                valueOf(sql, SPECS.get(5)));
    }

    /**
     * 抓一行并返回其取值；抓不到即抛，并在消息里点名是哪个编号。
     *
     * <p>🛑 不回落到 {@code null}：返回 {@code null} 会把"这一行被删了"
     * 静默转成"上游的 isComplete() 判定"，而那条判定说出的理由是"配置缺失"，
     * 不指向具体编号 —— 排查要从六个编号里一个个试。
     */
    private static String valueOf(String sql, Spec spec) {
        Matcher m = rowPattern(spec.configNo(), spec.key()).matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config " + spec.describe()
                            + " 的声明行 —— 派生口径来源断开，拒绝按默认口径计算");
        }
        String v = m.group(1);
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config " + spec.describe() + " 的声明值为空 —— 派生口径缺失，拒绝按默认口径计算");
        }
        return v;
    }

    @Override
    public String describeSource() {
        return "classpath:" + SEED_RESOURCE + " "
                + SPECS.stream().map(Spec::describe).toList()
                + "（过渡来源；配置真相源落库后应替换为 DB 读取实现）";
    }

    private String readSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "派生口径来源断开。若该文件被移动，请同步更新本类的 SEED_RESOURCE。");
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