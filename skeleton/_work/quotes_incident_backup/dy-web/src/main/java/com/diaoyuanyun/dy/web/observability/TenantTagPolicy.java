package com.diaoyuanyun.dy.web.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 指标标签禁令 (A7 硬门禁): 任何 Meter 都<b>不得</b>携带租户维度的标签。
 *
 * <h2>为什么这是禁令, 而不是"建议别加"</h2>
 * <ol>
 *   <li><b>基数爆炸</b>：时序数据库（Prometheus / 各家云监控）为每个标签组合维护一条独立时间序列。
 *       {@code tenant_id} 的取值随租户数增长（本产品是多门店 SaaS，租户数随门店数增长），
 *       于是指标条数 = 指标数 × 租户数。一次促销带来的门店增长会让监控后端的存储与查询同时过载,
 *       而这类故障的症状是"监控自己挂了" —— 恰恰在最需要它的时候。</li>
 *   <li><b>跨租户信息泄漏面</b>：指标端点通常是<b>不带租户鉴权</b>的（抓取方是监控系统, 不是租户）。
 *       一旦 {@code tenant_id} 成为标签, 任何能读到 {@code /actuator/prometheus} 的主体,
 *       都能通过标签值枚举出<b>全平台的租户清单</b>，并读出租户粒度的业务量。
 *       这与 ADR-02 的租户隔离是同一件事的另一面: 隔离必须在<b>所有</b>出平台上成立,
 *       不能因为"这是监控数据"就开例外。</li>
 * </ol>
 * 排障需要"哪个租户"的时候, 用<b>日志</b>（MDC 里的 {@code tenantId}, 见
 * {@link ObservabilityMdcFilter}）+ {@code traceId} 去关联, 而不是用指标标签。
 * 日志可以逐条鉴权与脱敏, 指标不能。
 *
 * <h2>为什么规则定义在主代码, 而不直接写在测试里</h2>
 * 禁令的<b>定义</b>（禁止哪些键名）与禁令的<b>执行</b>（构建期变红）是两件事。
 * 定义放主代码里, 使"什么算违规"有唯一出处; 执行放测试里
 * （{@code TenantTagGuardTest}）, 使违规表现为<b>构建失败</b>而不是运行期静默丢弃。
 *
 * <h2>为什么不用 MeterFilter 在运行期把租户标签过滤掉（已考虑, 否决）</h2>
 * 运行期过滤看起来更稳妥（"无论如何都泄漏不出去"），但它把<b>构建期可见的失败</b>
 * 换成<b>运行期静默的丢弃</b>：写代码的人加了 {@code tenant_id} 标签, 指标照常工作、
 * 无任何报错, 只是这个维度悄悄没了 —— 排障时他会以为"这个维度本来就没有",
 * 而真正的规则失效（有人关掉了 filter）同样不会有任何症状。
 * 有牙齿的门禁必须在<b>提交时</b>响, 而不是在运行期替人擦掉错误。
 */
public final class TenantTagPolicy {

    /**
     * 被禁的标签键（小写比较）。
     *
     * <p>三种写法都查, 因为 Micrometer 的标签名由调用方自由书写：
     * snake_case（`tenant_id`, 与契约字段名一致, 最可能被人顺手写出来）、
     * camelCase（`tenantId`）、裸 `tenant`。只查一种就是给另外两种留缝。
     */
    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("tenant_id", "tenantid", "tenant");

    private TenantTagPolicy() {
    }

    /** 一次违规: 哪个指标、用了哪个被禁的标签键。带上两个信息才能直接定位到代码位置。 */
    public record Violation(String meterName, String tagKey) {
        @Override
        public String toString() {
            return meterName + " -> 标签 '" + tagKey + "'";
        }
    }

    /**
     * 枚举注册表中<b>全部</b> Meter, 返回所有携带租户维度标签的违规项。
     *
     * @return 违规列表; 空列表 = 合规
     */
    public static List<Violation> findViolations(MeterRegistry registry) {
        List<Violation> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            violations.addAll(findViolations(meter));
        }
        return violations;
    }

    /** 单个 Meter 的违规检查（枚举该 Meter 的所有 tag key）。 */
    public static List<Violation> findViolations(Meter meter) {
        List<Violation> violations = new ArrayList<>();
        for (Tag tag : meter.getId().getTags()) {
            if (isForbidden(tag.getKey())) {
                violations.add(new Violation(meter.getId().getName(), tag.getKey()));
            }
        }
        return violations;
    }

    /** 单个标签键是否被禁。大小写不敏感 —— 标签名大小写不同不该改变结论。 */
    public static boolean isForbidden(String tagKey) {
        return tagKey != null && FORBIDDEN_TAG_KEYS.contains(tagKey.toLowerCase(Locale.ROOT));
    }
}