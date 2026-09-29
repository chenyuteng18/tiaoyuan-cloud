package com.diaoyuanyun.dy.app.band.repository;

import com.diaoyuanyun.dy.app.band.domain.BandBindingOutcome;
import com.diaoyuanyun.dy.app.band.domain.BandBindingRecord;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>手环台账写入仓储</b> —— 本仓的第一个（也是唯一一个）往 {@code band} 表写行的生产代码。
 *
 * <h2>🛑 它补的是什么：一张表的"零写入方"如何变成一个功能缺口</h2>
 * 在 B-10 之前，{@code band} 在生产代码里零写入方
 * （全仓 9 处 {@code INSERT INTO band} 全部在 {@code src/test} 的夹具里）。
 * 这不是一条"边界事实"那么轻 —— 它会让两个契约端点在生产上必然失败：
 * <pre>
 *   band_telemetry      .device_id UUID NOT NULL REFERENCES band (band_id)   [V3  L38]
 *   band_sync_probe     .device_id UUID NOT NULL REFERENCES band (band_id)   [V5  L904]
 *   band_sync_log       .device_id UUID NOT NULL REFERENCES band (band_id)   [V5  L951]
 *   band_daily_coverage .device_id UUID NOT NULL REFERENCES band (band_id)   [V5  L995]
 * </pre>
 * ⇒ 一条 band 行都没有 ⇒ 写入这四张表的请求全部以 {@code 23503} 失败。
 * 而 E1 {@code POST /band/sync-batches}（写 band_sync_log）与
 * E2 {@code POST /band/telemetry}（写 band_telemetry）正是客户端上报的入口
 * （契约 {@code x-callable-roles: [client]}）。
 *
 * <h2>🛑 为什么它不是一个 Controller，也不该变成 Controller</h2>
 * 本轮逐条核对了契约的<b>全部 40 个 path</b>：域 E（E1~E6）只有上报与读取，
 * <b>没有任何绑定 / 解绑端点</b>。且 D6 {@code POST /device-dispatches} 的
 * description <b>逐字把它排除了</b>：
 * <blockquote>
 * 「🛑 本接口操作的是【门店级调理设备 device】（下行、可追责），
 *  与【客户级手环 band】是两本台账、不得合并。」
 * </blockquote>
 * ⇒ 与 B-7（组织开通）完全同型：这是<b>契约化决策</b>，不是遗漏。
 * 给绑定加对外端点属契约 <b>MAJOR</b> 变更 —— 而且要先回答一个现行契约
 * 回答不了的问题：「谁有权给客户绑带子」？{@code x-callable-roles} 里
 * 没有任何一项覆盖它（client 是被绑的对象而不是操作者；
 * therapist / meridian / admin 是否都可以？门店客服呢？）。
 * 故本类被刻意做成一个<b>无 HTTP 映射的组件</b>（{@code ProvisioningBoundaryGateTest}
 * 的第 ⑦ 例会机械断言它不带任何 HTTP 注解）。
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO band}，而全部下沉到 V17 的函数</h2>
 * 三条理由，每条都对应一类会静默出错的东西：
 * <ol>
 *   <li><b>部分唯一索引的 {@code ON CONFLICT} 推断语法</b>：
 *       {@code uq_band_active_customer} 是<b>部分</b>唯一索引
 *       （{@code UNIQUE (tenant_id, customer_id) WHERE status = 'active'}），
 *       要命中它，{@code ON CONFLICT} 必须<b>逐字重述谓词</b>。
 *       写成 {@code ON CONFLICT (tenant_id, customer_id) DO NOTHING}（不带 WHERE）
 *       会以「没有与 ON CONFLICT 说明匹配的唯一约束」失败 —— 而那是<b>运行期</b>错误。
 *       把它封在迁移的自证里，就变成了迁移期可判定的事实。</li>
 *   <li><b>换机路径的"先作废再插入"必须原子且顺序正确</b>：
 *       若写成两条独立语句，在两次调用之间失败（进程被杀 / 连接断）会留下
 *       <b>该客户一支 unbound 带子、零支 active 带子</b> 的状态 ——
 *       那是"客户在戴带子但台账说没有"，而 A3（应戴天）会因此偏低，
 *       直接影响退款资格。函数体保证它们在同一个事务的同一段里。</li>
 *   <li><b>口径只有一处</b>：{@code OrganizationProvisioningRepository} 的类注释
 *       已付费记录过这个教训 —— tenant 行的幂等写入<b>只在一处</b>发生（V15 函数），
 *       应用侧不再重复。两处都写会造出"口径分叉"：平时无害，
 *       一旦有人只改了其中一处（例如给 Java 侧那条加上 {@code DO UPDATE}），
 *       就会出现"同一个动作有两种结果"的静默不一致。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code bind_band} 的返回值必须被校验成枚举，而不能透传</h2>
 * 见 {@link BandBindingOutcome#fromDb(String, String)}：未登记的值即抛错。
 * 理由与 {@code OrganizationProvisioningRepository} 对 {@code provision_tenant()}
 * 返回值的处置逐字同款 —— <b>若函数被改成别的口径，这里会先红</b>，
 * 而不是让一个无法解释的字符串流进审计 payload。
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 本类的每个公开方法都只做<b>一次</b>数据库函数调用，看似不需要显式事务。
 * 但那个函数内部会调 {@code set_config(..., is_local := true)} 并依赖它活到
 * 本次调用结束 —— 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态
 * （{@code TransactionTemplate}）—— 让"上下文活到写完成"成为<b>代码结构</b>上的事实，
 * 而不是一条关于注解传播行为的隐式推理。
 */
@Component
public class BandBindingLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public BandBindingLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧：两个 V17 原语
    // ==================================================================

    /**
     * 绑定一支手环给客户（幂等；可选换机）。
     *
     * <p>整个动作 = V17 的 {@code bind_band()} 一次调用。它内部完成：
     * 建立并自证租户上下文 → 校验客户在本租户内存在 → （换机时）作废旧带子 →
     * 单条 {@code INSERT ... ON CONFLICT} 原子写入 → 返回四态。
     *
     * @return 四态之一（见 {@link BandBindingOutcome}）；
     *         {@code CUSTOMER_ALREADY_HAS_ACTIVE_BAND} <b>不是错误</b>
     */
    public BandBindingOutcome bind(BandBindingRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "绑定手环：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT bind_band(?::uuid, ?::uuid, ?::uuid, ?, ?, ?::date, ?, ?)",
                String.class,
                record.tenantId(),
                record.bandId().toString(),
                record.customerId().toString(),
                record.vendor(),
                record.model(),
                // 🛑 日期回落放在 Java 侧（record.effectiveBoundAt）：审计 payload 里
                //    必须出现具体日期，否则会出现"审计说 null、库里是某一天"的不一致。
                record.effectiveBoundAt().toString(),
                record.rebind(),
                record.rebind() ? record.effectiveRebindReason() : null));

        return BandBindingOutcome.fromDb(raw, "bind");
    }

    /**
     * 解绑一支手环（幂等）。
     *
     * <p>🛑 是<b>状态迁移</b>（{@code status → 'unbound'}）而不是删除行。
     * 理由见 V17 的 {@code unbind_band()} 注释与迁移文件头：
     * {@code unbound_at} 是"应戴天"分母的终点、{@code unbind_reason} 是
     * "主动放弃 vs 技术性缺失"（data-spec M6）的唯一载体 ——
     * 删掉一行等于抹掉一段应戴天，而 A3 是退款资格的输入。
     *
     * @param reason    写进 {@code unbind_reason}（应取 {@code 主动放弃 / 换机 /
     *                  设备损坏 / 其他} 之一，见 {@code band} 表的 CHECK）
     * @param unboundAt 解绑日；{@code null} → 库函数回落 {@code CURRENT_DATE}
     */
    public BandBindingOutcome unbind(String tenantId, UUID bandId, String reason, LocalDate unboundAt) {
        BandLedger.validateTenantId(tenantId);
        if (bandId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "解绑手环：band_id 必填");
        }

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT unbind_band(?::uuid, ?::uuid, ?, ?::date)",
                String.class,
                tenantId,
                bandId.toString(),
                reason,
                unboundAt == null ? null : unboundAt.toString()));

        return BandBindingOutcome.fromDb(raw, "unbind");
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 该客户当前的<b>有效</b>手环 id（无则 {@code null}）。
     *
     * <p>🛑 这是"1 客户 : 1 有效手环"这条不变量的<b>可观测面</b>。
     * 与 {@link BandLedger#findActiveDeviceByCustomer} 的差别只在<b>读的时机</b>：
     * 那一个是 E6 同步状态卡的前置（运行时读），本方法是绑定后的核对读
     * （运维 / 门禁）。两者都走 {@code inTenant}，读同一张表 —— 刻意不另造一条
     * "因为我是运维所以不设上下文"的旁路：那种旁路迟早会被某个人接进一个对外接口。
     */
    public UUID findActiveBandId(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询有效手环：customer_id 必填");
        }
        return inTenant(tenantId, () -> {
            List<UUID> ids = jdbc.query(
                    "SELECT band_id FROM band WHERE customer_id = ?::uuid AND status = 'active'"
                            + " ORDER BY bound_at DESC LIMIT 1",
                    (rs, i) -> rs.getObject("band_id", UUID.class),
                    customerId.toString());
            return ids.isEmpty() ? null : ids.get(0);
        });
    }

    /**
     * 该客户在本租户内的<b>全部</b>手环行摘要 —— {@code [band_id, status, unbind_reason, bound_at, unbound_at]}。
     *
     * <p>🛑 为什么需要"全部"而不只是"有效的那支"：本迁移的全部价值之一就是
     * <b>台账历史完整</b>（解绑是状态迁移而非删除）。只提供"有效手环"这一个读法，
     * 会让"历史行还在不在"这件事没有可断言的载体 ——
     * 而"解绑后历史行仍在"正是 {@code uq_band_active_customer} 之所以是
     * <b>部分</b>唯一索引的原因。故本方法让历史可观测。
     */
    public List<String[]> historyOf(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询手环台账：customer_id 必填");
        }
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT band_id::text, status, coalesce(unbind_reason, ''), "
                        + "       bound_at::text, coalesce(unbound_at::text, '') "
                        + "  FROM band WHERE customer_id = ?::uuid ORDER BY bound_at, band_id",
                (rs, i) -> new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)},
                customerId.toString()));
    }

    /**
     * 该租户内当前 {@code active} 手环的行数。
     *
     * <p>供"绑定确实生效"与"解绑确实生效"两侧各有一个可断言的正整数 ——
     * 一个只报 {@code 0} 的读法无法区分"没有有效手环"与"我什么也看不见"。
     */
    public int countActiveInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM band WHERE status = 'active'", Integer.class);
            return n == null ? 0 : n;
        });
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code BandLedger.inTenant} / {@code OrganizationProvisioningRepository.inTenant}
     * 同款，理由逐一相同：{@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"
     * 收敛成一个短事务，使「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立。
     *
     * <p>{@code validateTenantId} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL。
     */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        BandLedger.validateTenantId(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }
}