package com.diaoyuanyun.dy.app.customer.service;

import com.diaoyuanyun.dy.app.customer.domain.ContraindicationPolicy;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 禁忌清单口径来源的<b>过渡实现</b>：读 config 声明表种子文件里的
 * {@code #8}（{@code cfg:safety.contraindication_list}）行。
 *
 * <h2>与 {@code ConfigSeedBandProfileSource} 同源的三条纪律</h2>
 * <ol>
 *   <li><b>为什么不直连 DB 配置表</b>：配置真相源（{@code app_config} / {@code config_slot}）
 *       目前只建在 dy-config 的独立门禁库里，应用库还没有这两张表 ——
 *       这正是骨架 README「未完成项」已登记的收尾令「配置真相源落库」。
 *       若本域硬绑 DB 配置服务，B1 会因一条<b>与本任务无关</b>的收尾令而无法开工。</li>
 *   <li><b>为什么是"解析"而不是"抄一份 JSON 进 dy-app"</b>：抄一份会在两处形成同源数据，
 *       改一处不改另一处时<b>不报错</b>。而本域的分叉后果是安全级的：
 *       {@code 9 项禁忌} 若在 dy-app 里被抄成另一份（例如某人补了 4 项），
 *       总部在声明文件里改清单、线上却按 dy-app 的那份判定 ⇒
 *       <b>命中了禁忌的客户被放行</b>，且没有任何一处报错。</li>
 *   <li><b>编号与键必须同时锚定</b>：见 {@link #rowPattern()}。</li>
 * </ol>
 *
 * <h2>🛑 本域的 fail-closed 与其它域<b>方向相反</b>，这一点必须写清</h2>
 * 其余配置源（{@code #40} / {@code #43}）的纪律是"解析不出即抛" —— 因为那些口径缺失时
 * "少给档位"比"多给档位"安全。本域<b>不能照搬</b>，理由是契约 B1 的声明集：
 * <pre>
 *   B1 responses = 200 (ScreeningData) + 400 (ValidationFailed) —— 【没有 500】
 * </pre>
 * 也就是说：<b>清单读不出来时，B1 没有一个合法的错误码可以报</b>。
 * 若在此处抛 5001，客户端会收到一个契约未声明的码而落进未定义分支 ——
 * 那比"按空清单判定"更糟（后者至少是一个可解释、可登记、可告警的确定行为）。
 * 故本类的处置是：
 * <pre>
 *   ① 文件读不到 / 解析不出行   → 抛出（这是<b>部署级</b>故障，应用起不来优于静默降级）
 *   ② 行读到了但 items 为空     → 【不抛】，交给 ContraindicationPolicy 显式登记
 * </pre>
 * 第 ② 条正是当前骨架的真实状态（{@code {"count":9,"items":[]}}）：声明 9 项、列表为空。
 * 把它抛成异常会让 B1 当场不可用；把它静默吞掉会让"所有客户都通过禁忌筛查"这件事
 * 无人知晓。故走第三条路：<b>照常推导 + 显式登记（
 * {@link ContraindicationPolicy#describe()}）+ 启动期告警</b>。
 *
 * <h2>🛑 为什么在<b>构造期</b>就解析一次</h2>
 * 让"声明文件被改名 / 该行被删掉"在<b>应用启动时</b>爆出，而不是等到第一个客户来做筛查。
 * 构造期成功 = 该行真实存在且取值列非空 —— 这使 B1 的失败面只剩下"清单项内容"，
 * 而那一项由 {@link ContraindicationPolicy} 承担。
 */
@Component
public class ConfigSeedContraindicationSource {

    /** config 声明表种子文件在 classpath 上的位置（dy-config 的 resources）。 */
    private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";

    /** {@code #8} 的编号与键 —— 两者必须<b>同时</b>锚定，见 {@link #rowPattern()}。 */
    private static final int CONFIG_NO = 8;
    private static final String CONFIG_KEY = "cfg:safety.contraindication_list";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 构造期解析并缓存（口径来自声明文件，进程内不变）。 */
    private final ContraindicationPolicy policy;
    /** 声明行的原始取值（供诊断与断言逐字比对）。 */
    private final String rawValue;

    public ConfigSeedContraindicationSource() {
        this.rawValue = readDeclarationValue();
        this.policy = parsePolicy(rawValue);
    }

    /** 当前生效的禁忌清单口径（B1 的唯一推导输入）。 */
    public ContraindicationPolicy policy() {
        return policy;
    }

    /** 来源描述（写进端点自描述与日志）。 */
    public String describeSource() {
        return "classpath:" + SEED_RESOURCE + " #" + CONFIG_NO + "（" + CONFIG_KEY + "）"
                + "（过渡来源；配置真相源落库后应替换为 DB 读取实现）";
    }

    /**
     * 抓 {@code #8} 那一行的方法。
     *
     * <p>形态（{@code 02_slots_seed.sql} 实际写法，取值列前面有 4 列：
     * config_no / key / value_type / allowed_values / forbidden_values）：
     * <pre>
     *   (8, 'cfg:safety.contraindication_list', 'JSON', NULL, NULL,
     *    '{"count":9,"items":[]}',
     *    '禁忌症清单',
     *    '总部维护（9 项 + 其他）。归属层级=总部唯一，非校准项（安全底线）。'),
     * </pre>
     * 正则刻意<b>锚定 config_no 与 key 同时出现</b>，避免抓错相邻条目：
     * {@code #7} 与 {@code #9} 的取值也是 JSON 且都以 <code>{"</code> 开头，
     * 只锚编号很容易在文件被重排后抓到错行；只锚键则在键被改名后静默失配
     * （本类会抛，故是安全的失败方向）。
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

    /** 读声明行取值；读不到即抛（部署级故障，见类注释"两个方向"一节）。 */
    private String readDeclarationValue() {
        String sql = readSeedResource();
        Matcher m = rowPattern().matcher(sql);
        if (!m.find()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "在 " + SEED_RESOURCE + " 中找不到 config #" + CONFIG_NO
                            + "（" + CONFIG_KEY + "）的声明行 —— 禁忌清单口径来源断开，"
                            + "拒绝以默认口径启用 B1 禁忌筛查（安全底线不得有兜底值）");
        }
        String json = m.group(1);
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "config #" + CONFIG_NO + "（" + CONFIG_KEY + "）的声明值为空 —— "
                            + "安全底线的口径缺失不得静默降级");
        }
        return json;
    }

    /**
     * 解析声明值 → {@link ContraindicationPolicy}。
     *
     * <h2>🛑 解析失败的处理与其它域<b>不同</b>，理由见类注释</h2>
     * 与"读不到声明行"（部署级，抛）分开：取值列若是一个**非 JSON 对象**的形态
     * （例如被误填成 SQL NULL 之外的脏值），那不属"部署级缺件"，
     * 而是"这一条配置的内容有问题"。此时返回 {@link ContraindicationPolicy#unconfigured()}
     * —— 即"清单为空 ⇒ 推导恒为通过"，并由 {@code describe()} 把它登记出来。
     * 🛑 也<b>不</b>在此处抛：B1 没有 500 可报（见类注释）。
     */
    private static ContraindicationPolicy parsePolicy(String json) {
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            // 形态异常：按"未配置"处置，但不掩盖 —— 由 describe() 登记
            return ContraindicationPolicy.unconfigured();
        }
        if (parsed == null) {
            return ContraindicationPolicy.unconfigured();
        }
        Integer count = asInt(parsed.get("count"));
        Object itemsRaw = parsed.get("items");
        List<?> items = itemsRaw instanceof List<?> l ? l : null;
        return ContraindicationPolicy.of(count, items);
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "classpath 上找不到 " + SEED_RESOURCE + "（dy-config 的配置声明文件）—— "
                                + "禁忌清单口径来源断开。若该文件被移动，"
                                + "请同步更新本类的 SEED_RESOURCE。");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "读取 " + SEED_RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 自描述（供端点自描述与回归用例断言）
    // ==================================================================

    /**
     * 口径来源的自描述 —— 把"清单当前为空"这条<b>安全级配置缺口</b>带成可断言事实。
     *
     * <p>🛑 它<b>不</b>包含清单项的具体内容 —— 那属总部口径（config #8 归属层级=总部唯一），
     * 与 {@code IdentityDomainConfig} "日志不打印档位取值"同源纪律：整段进响应体/日志
     * 会让"内部安全规则外泄"成为一条难以撤销的事实。此处只带<b>规模与状态</b>。
     */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config_slot", "#" + CONFIG_NO + " / " + CONFIG_KEY);
        m.put("source", describeSource());
        m.put("policy", policy.describe());
        m.put("raw_value_redacted",
                "声明值原样为 " + (rawValue == null ? "<null>" : "「" + rawValue + "」")
                        + " —— 🔴 该值声明 count=9 而 items 为空（自相矛盾的占位）。"
                        + "本域不代拍那 9 项（安全底线的取值集，见 Non-goals #4/#5），"
                        + "只把它作为【安全级配置缺口】显式登记");
        m.put("fail_closed_direction",
                "本域的 fail-closed 方向与 #40/#43 相反：读不到【声明行】即抛（部署级缺件），"
                        + "但【清单为空】不抛 —— 因为契约 B1 的 responses 声明集 = 200 + 400，"
                        + "【无 500】；报一个未声明的码会让客户端落进未定义分支，"
                        + "比『按空清单判定 + 显式告警』更糟");
        return m;
    }
}