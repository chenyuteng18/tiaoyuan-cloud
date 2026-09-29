package com.diaoyuanyun.dy.app.refund.service;

import com.diaoyuanyun.dy.app.refund.domain.RefundProfileSource;
import com.diaoyuanyun.dy.app.refund.domain.RefundRawConfig;
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
 * 退款域口径来源的<b>过渡实现</b>：直接读 config 声明表种子文件里的
 * {@code #10 / #26 / #27 / #28 / #29 / #38 / #40} 七行初始值。
 *
 * <h2>它为什么存在（而不是直接读 DB 配置表）</h2>
 * 与 {@code ConfigSeedDerivedProfileSource} / {@code ConfigSeedScaleProfileSource} 同源：
 * 配置真相源（{@code app_config} / {@code config_slot}）目前只建在 dy-config 的
 * 独立门禁库里，应用库 {@code diaoyuanyun_dev} 里还没有这两张表 ——
 * 这正是骨架 README「未完成项与 TODO」里已登记的收尾令
 * 「配置真相源落库（{@code ConfigServiceImpl} 仍内存实现）」。
 *
 * <p>若本域硬绑 DB 配置服务，退款域会因一条<b>与本任务无关</b>的收尾令而无法开工。
 * 而本域的验收要求是「退款口径外置、有代码之外的唯一来源」，不是「配置必须已落库」。
 *
 * <h2>读的是声明文件的哪一段，为什么这样不算"抄一份"</h2>
 * {@code dy-config/src/main/resources/db/config/02_slots_seed.sql} 是 PRD §10
 * 「附：可配置项清单（<b>硬性要求 —— 所有业务数字不得硬编码</b>）」的唯一声明载体，
 * 且它在 dy-app 的 classpath 上。本实现<b>解析</b>该文件的七行取值，而<b>不是</b>
 * 抄一份 JSON 进 dy-app：
 * <ul>
 *   <li>抄一份会在两处形成同源数据，改一处不改另一处时<b>不报错</b>，只让退款口径分叉
 *       —— 而分叉的是"客户能不能退、退多少、谁看得见"；</li>
 *   <li>解析则把声明值变成唯一真相源 —— 改声明文件，退款行为立刻跟着变。</li>
 * </ul>
 * 这一点的机械保证见 {@code RefundProfileSourceTest}：它断言"能解析出七段值"，
 * 并在文件被改名 / 移走、或某一行被删掉时<b>立刻红</b>
 * （而不是静默退化成抛错被上层吞掉）。
 *
 * <h2>🛑 收尾令完成后的替换方式</h2>
 * 配置真相源落库后，把本类换成一个读 {@code app_config} 的实现即可
 * （{@link RefundProfileSource} 是为此留的端口）。
 * {@code RefundPolicy} / 挽留 / 审批 / 可见性四块策略<b>零改动</b>。
 *
 * <h2>fail-closed</h2>
 * 任一段解析不出值一律抛（口径缺失 = 拒绝处理退款），<b>绝不</b>返回默认口径 ——
 * 七段里有五段直接决定面客行为。特别地，{@code #40}（可见性）若取不到就回落，
 * "客户看得见退款字样"会以配置缺省的形式静默发生 —— 而那正是 §2.2 用
 * 「两项定值冻结」要锁死的东西。
 */
@Component
public class ConfigSeedRefundProfileSource implements RefundProfileSource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /**
     * 一次抓一行的方法：{@code (编号, '键', '类型', …, …, '初始值', 'PRD 名称', '语义')}。
     *
     * <p>形态（02_slots_seed.sql 实际写法，取值列前面有 4 列：
     * config_no / key / value_type / allowed_values / forbidden_values）：
     * <pre>
     *   (27, 'cfg:refund.retention_sla_hours', 'INT', NULL, NULL,
     *    '48',
     *    '挽留响应 SLA',
     *    '…'),
     * </pre>
     * 正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目。
     * 这一点在本域<b>尤其</b>关键：{@code #10} 与 {@code #38} 的取值都是 JSON 且都以
     * {@code {"} 开头，只锚编号很容易在文件被重排后抓到错行。
     *
     * <p>取值列自身不含单引号：数值型是纯数字；{@code JSON} 型用双引号；
     * {@code ENUM} 型（如 {@code #26}）是 {@code 'immediate'} 这样的裸枚举字面。
     * 故"单引号对之间的内容"即完整取值。
     */
    private static Pattern rowPattern(int configNo, String key) {
        return Pattern.compile(
                "\\(\\s*" + configNo + "\\s*,\\s*'" + Pattern.quote(key) + "'\\s*,\\s*'[A-Z]+'\\s*,"
                        + "\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,\\s*(?:ARRAY\\[[^\\]]*\\]|NULL|'[^']*')\\s*,"
                        + "\\s*'([^']*)'",
                Pattern.DOTALL);
    }

    /**
     * 七段声明的 (编号, 键) —— 顺序与 {@link RefundRawConfig} 的字段顺序一致。
     *
     * <p>🛑 顺序不得随手调整：{@link #raw()} 按下标取值，
     * 顺序与 record 字段错位会让"#27 的 48"被读成"#28 的 7"，
     * 且两者都是合法正整数 —— 不报错，只让到账承诺变成 48 天。
     * 故此处以 {@link Spec} 同时承载编号与键，解析时按同一列表遍历。
     */
    private static final List<Spec> SPECS = List.of(
            new Spec(10, "cfg:refund.gate_rules", "#10 退款门禁规则"),
            new Spec(26, "cfg:refund.verdict_latency", "#26 退款资格结论时效"),
            new Spec(27, "cfg:refund.retention_sla_hours", "#27 挽留响应 SLA"),
            new Spec(28, "cfg:refund.arrival_commitment_days", "#28 退款到账承诺"),
            new Spec(29, "cfg:refund.concession_approval_threshold", "#29 退款让步审批阈值"),
            new Spec(38, "cfg:refund.fulfillment_direct_formula", "#38 履约类退款直退算法"),
            new Spec(40, "cfg:refund.visibility", "#40 退款可见性矩阵"));

    @Override
    public RefundRawConfig raw() {
        String sql = readSeedResource();
        return new RefundRawConfig(
                valueOf(sql, SPECS.get(0)),
                valueOf(sql, SPECS.get(1)),
                valueOf(sql, SPECS.get(2)),
                valueOf(sql, SPECS.get(3)),
                valueOf(sql, SPECS.get(4)),
                valueOf(sql, SPECS.get(5)),
                valueOf(sql, SPECS.get(6)));
    }

    /**
     * 抓一行并返回其取值；抓不到即抛，并在消息里点名是哪个编号。
     *
     * <p>🛑 不回落到 {@code null}：返回 {@code null} 会把"这一行被删了"
     * 静默转成上游的 {@code isComplete()} 判定，而那条判定说出的理由是"配置缺失"、
     * 不指向具体编号 —— 排查要从七个编号里一个个试。
     */
    private static String valueOf(String sql, Spec spec) {
        Matcher m = rowPattern(spec.configNo(), spec.key()).matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config " + spec.describe()
                            + " 的声明行 —— 退款口径来源断开，拒绝按默认口径处理");
        }
        String v = m.group(1);
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config " + spec.describe() + " 的声明值为空 —— 退款口径缺失，拒绝按默认口径处理");
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
                                + "退款口径来源断开。若该文件被移动，请同步更新本类的 SEED_RESOURCE。");
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