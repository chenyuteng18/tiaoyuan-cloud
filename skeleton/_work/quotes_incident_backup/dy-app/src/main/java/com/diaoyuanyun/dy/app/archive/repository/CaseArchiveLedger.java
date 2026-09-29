package com.diaoyuanyun.dy.app.archive.repository;

import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveOutcome;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveRecord;
import com.diaoyuanyun.dy.app.archive.domain.CaseArchiveSnapshot;
import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>结案归档写入仓储</b> —— 本仓<b>唯一</b>一个往 {@code case_archive} 表写行的生产代码。
 *
 * <h2>🛑 它补的是什么：PRD 里最重的一句话，在系统里没有任何实现</h2>
 * 在 B-13 之前，{@code case_archive} 在生产代码里零写入方、真库零行
 * （实测 {@code count(*) = 0}）。四张未开通表里，它的后果<b>最容易漏</b>，
 * 因为前三张（{@code band} / {@code device} / {@code scale}）的缺口都会以
 * <b>另一个动作失败</b>的形式暴露出来（{@code 23503} / {@code 422}），
 * 而本表的缺口不阻断任何动作：
 * <pre>
 *   PRD P0-14 逐字：「四种结局全部强制归档」
 *   PRD C.1.7 逐字：「缺少任一必填门禁项 → 403 阻断结案」
 *   PRD P0-25 逐字：「硬阻断规则缺项 → 403 且给出缺失项名称」
 * </pre>
 * ⇒ 缺失它的后果不是"某个请求失败"，而是
 * <b>「归档」这个动作在系统里根本无法发生</b>：退款工单走到 {@code 终止} 之后
 * 没有任何合法路径把它收口成 {@code 归档}。于是那条"全部强制归档"的规则
 * <b>没有对应的实现</b>，而<b>没有任何一次调用会因此报错</b>——
 * 系统只是"少做了一件事"，安静地少做。
 *
 * <h2>🛑🛑 与前三批（B-10 / B-11 / B-12）的形态差别，以及由此产生的设计后果</h2>
 * <table border="1">
 *   <caption>四批边界移动的形态对照</caption>
 *   <tr><th></th><th>B-10 {@code band}</th><th>B-11 {@code device}</th>
 *       <th>B-12 {@code scale}</th><th>B-13 {@code case_archive}</th></tr>
 *   <tr><td>缺口的对外表现</td>
 *       <td>{@code 23503}（四表外键）</td>
 *       <td>{@code 23503}（D6 下发）</td>
 *       <td>{@code 422}（C2 预检）</td>
 *       <td><b>无</b> —— 没有任何下游动作会因此失败</td></tr>
 *   <tr><td>缺口何时被发现</td>
 *       <td>写入时</td><td>写入时</td><td>评估时</td>
 *       <td><b>举证时</b>（审计要看归档清单，而清单查不到）</td></tr>
 *   <tr><td>{@code ON CONFLICT} 推断目标含租户维度？</td>
 *       <td>含（{@code (tenant_id, customer_id) WHERE status='active'}）</td>
 *       <td colspan="2">不含（单列主键）</td>
 *       <td>不含（{@code case_archive_pkey = (archive_id)}）</td></tr>
 *   <tr><td>⇒ 必须补跨租户撞号 RAISE？</td>
 *       <td>不必</td><td colspan="2">必须</td><td><b>必须，且后果更隐蔽</b></td></tr>
 * </table>
 * <p>🛑 <b>"后果更隐蔽"具体指什么</b>（这一句是本类最该被读到的地方）：
 * {@code device} 那次的"返回值在撒谎"能被<b>下游</b>抓住
 * （紧接着的 {@code INSERT INTO device_dispatch} 以 {@code 23503} 失败）。
 * 而归档是链条的<b>末端收口动作</b> —— 它的下游没有第二步。
 * 若 {@code register_case_archive()} 对"archive_id 被别的租户占用"返回
 * {@code ALREADY_EXISTS}，调用方会把退款工单标记为 {@code 归档}，
 * 而<b>归档档案并不存在</b>；这个缺口不会报错、不会 23503、
 * <b>不会被任何下游抓住</b>，只在举证时暴露。
 * ⇒ 这正是 {@link CaseArchiveOutcome} <b>刻意没有</b>
 * {@code OWNED_BY_OTHER_TENANT} 一态的原因：一态一旦被登记，
 * 就等于允许调用方把它当成"可以继续流程的结果"。这里用<b>缺失</b>表达
 * "这个情形不该被继续"。
 *
 * <h2>🛑 为什么它不是 Controller，也不该变成 Controller</h2>
 * 逐条核对了契约全部 40 个 path：<b>没有任何</b> path 是"创建结案归档"。
 * 归档完全不出现在契约的 path 表里（{@code refund} 相关的端点只到
 * "退款工单 / 结案单 / 回执 / 线下通知"一层，没有归档端点）。
 * 给归档加对外端点属契约 <b>MAJOR</b> 变更，且要先回答契约回答不了的问题：
 * 「谁有权把一个退款工单收口成归档」。⇒ 与 B-7 / B-10 / B-11 / B-12 同型：
 * 这是<b>契约化决策</b>，不是遗漏。
 * 故本类被刻意做成一个<b>无 HTTP 映射的组件</b>
 * （{@code CaseArchiveGateTest} 会机械断言它不带任何 HTTP 注解）。
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO case_archive}，而全部下沉到 V20 的函数</h2>
 * 四条理由，每条都对应一类会静默出错的东西：
 * <ol>
 *   <li><b>6 项门禁的判定必须与写入原子</b>：分两步（先查清单、再插行）在两次调用
 *       之间失败会留下"行已写入、而它的门禁校验发生在另一个事务里"这类不可解释的状态。
 *       函数体保证它们在同一个事务的同一段里；</li>
 *   <li><b>{@code ON CONFLICT (archive_id)} 的推断语法 + 跨租户判定</b>：
 *       见上表。把"冲突了但我看不见 ⇒ 是别人的"写在 Java 侧意味着两次 JDBC 往返
 *       拼出来 —— 而那是"先读后写"，会引入窗口。封在库层就是一条语句内部的事；</li>
 *   <li><b>租户上下文的自建与自证</b>：函数用
 *       {@code set_config(..., is_local := true)} + {@code assert_tenant_context()}
 *       + 上下文一致性守卫，三者顺序不可调换；</li>
 *   <li><b>口径只有一处</b>：幂等写入<b>只在一处</b>发生（迁移里的函数），
 *       应用侧不再重复。两处都写会造出"口径分叉"：平时无害，
 *       一旦有人只改了其中一处，就会出现"同一个动作有两种结果"的静默不一致。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code register_case_archive} 的返回值必须被校验成枚举，而不能透传</h2>
 * 见 {@link CaseArchiveOutcome#fromDb(String)}：未登记的值即抛错。
 * 理由与 {@code DeviceOutcome#fromDb} / {@code BandBindingOutcome#fromDb} 逐字同款。
 * <p>🛑 本域多一层：它同时是"跨租户撞号必须保持为<b>异常</b>"这条设计的守卫 ——
 * 若有人把函数改成"跨租户时返回某个第三态"，枚举解析会先红。
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 与 {@code BandLedger} / {@code BandBindingLedger} / {@code DeviceLedger} 逐字同款：
 * 本类的每个公开写方法都只做<b>一次</b>数据库函数调用，看似不需要显式事务。
 * 但那个函数内部会调 {@code set_config(..., is_local := true)} 并依赖它活到
 * 本次调用结束 —— 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态。
 *
 * <h2>🛑 本类刻意不提供的两件事（都是决定，不是遗漏）</h2>
 * <ol>
 *   <li><b>没有 {@code unarchive} / {@code deleteArchive}</b>：归档是<b>终态</b>
 *       （PRD P0-14 把它定义为收口），而"未归档"这个语义由 {@code refund.outcome}
 *       承担（见 {@code RefundOutcome} 注释逐字：「归档 是结案状态位……
 *       它不是第四种业务结局，是状态位」）。提供"删除归档行"的原语，
 *       等于给了第二条把工单从已归档改回未归档的路 —— 而那条路会绕过
 *       refund 侧的状态机；</li>
 *   <li><b>没有"改写归档"的写方法</b>：库里没有"归档后只读"的库层约束
 *       （无改写通路已实现，但库层禁止改写未实现 —— 见 V20 文件头）。
 *       本类不补那条限制，是为了不让"禁止改写"这件事有一个<b>看起来实现了</b>
 *       的假象：真正的限制应在库层（触发器或权限），而那需要一次独立决策。
 *       本类只保证自己<b>不提供</b>改写入口。见 {@code CaseArchiveGateTest}
 *       的"本类无 UPDATE/DELETE case_archive"静态断言。</li>
 * </ol>
 */
@Component
public class CaseArchiveLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CaseArchiveLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧：V20 的写原语（本迁移唯一的写入口）
    // ==================================================================

    /**
     * 幂等把一张退款工单收口成归档档案。
     *
     * <p>整个动作 = V20 的 {@code register_case_archive()} 一次调用。它内部完成：
     * 入参守卫 → JSONB 形态断言 → 5 项硬门禁断言（缺项即 RAISE）
     * → 手环警告项类型断言（<b>值 false / 键缺失均放行</b>）
     * → 签名块 4 键断言 → 建立并自证租户上下文
     * → 单条 {@code INSERT ... ON CONFLICT (archive_id) DO NOTHING} 原子写入
     * → 按"冲突了但它是不是我的"判两态。
     *
     * <p>🛑 <b>会抛的四种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>硬门禁缺项 ⇒ 函数 RAISE（消息列出缺项名称）——
     *       对应 P0-25「403 且给出缺失项名称」。🛑 正常情况下本方法
     *       <b>不会</b>走到这一条：{@link CaseArchiveRecord} 的构造器已经先拦了。
     *       库层那一道守的是"有人绕过 record 直接写 SQL"；</li>
     *   <li>签名块缺项或为空 ⇒ 函数 RAISE（同上，构造器已先拦）；</li>
     *   <li>客户不在本租户内 ⇒ 函数 RAISE（复合外键 {@code 23503}）；</li>
     *   <li>{@code archive_id} 被<b>另一租户</b>占用 ⇒ 函数 RAISE
     *       （见 {@link CaseArchiveOutcome} 类注释「跨租户撞号为什么必须是异常」）。</li>
     * </ul>
     * 调用方<b>不应</b>把这几条捕获后当成"再试一次"——它们都需要人工确认。
     *
     * @return 两态之一（见 {@link CaseArchiveOutcome}）；
     *         {@code ALREADY_EXISTS} <b>不是错误</b>
     */
    public CaseArchiveOutcome register(CaseArchiveRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "结案归档：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_case_archive(?::uuid, ?::uuid, ?::uuid,"
                        + " ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?)",
                String.class,
                record.tenantId(),
                record.archiveId().toString(),
                record.customerId().toString(),
                record.checklistJson(),
                record.signsJson(),
                record.finalConclusion(),
                record.metricsTrend(),
                record.desensitizeAuthorized(),
                record.createdBy()));

        return CaseArchiveOutcome.fromDb(raw);
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 该客户在本租户内的<b>最新</b>归档档案 id（无则 {@code null}）。
     *
     * <p>🛑 走 V20 的 {@code latest_archive_of()} 而不是在这里重新写一条 SELECT：
     * "最新"的判定需要一个<b>全序</b>（{@code archived_at DESC, archive_id DESC}），
     * 而 {@code archived_at} 单独<b>不够</b> —— 同一时刻写入的两份档案会给出
     * 不确定的先后。若本类自己写 {@code ORDER BY archived_at DESC LIMIT 1}，
     * 就会与函数里那份全序形成两套口径，而它们的差别<b>不会报错</b>，
     * 只会在并发归档时表现为"两次调用返回不同的 archive_id"。
     * 这与 {@code RefundWorkOrderPort.findLatestStatement} 注释里记载的是同一族教训。
     */
    public UUID latestArchiveOf(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询最新归档：customer_id 必填");
        }
        return tx.execute(status -> jdbc.queryForObject(
                "SELECT latest_archive_of(?::uuid, ?::uuid)",
                UUID.class,
                tenantId,
                customerId.toString()));
    }

    /**
     * 读回一整行归档档案（本租户内不可见此行时返回 {@code null}）。
     *
     * <p>🛑 为什么返回 {@link CaseArchiveSnapshot}（整行）而不是几个标量读法：
     * 见该 record 的类注释「为什么必须是一个整行对象」——
     * 标量读法会让"这一行不存在"与"这一行的某个字段为空"共用同一个 {@code null}。
     *
     * <p>🛑 它是本迁移<b>最重要的可观测面</b>：门禁要回答的问题不是
     * "库里有几行"，而是"<b>写进去的那份档案，读回来还是不是那一份</b>"。
     * 只断言行数会漏掉"重放时用新参数改写了既有证据"这一类缺陷
     * （归档档案是证据快照，被改写是本源最不能接受的形态之一）。
     *
     * <p>🛑 两个 JSONB 列在这里<b>即时解析</b>（走
     * {@link CaseArchiveRecord#parseChecklist} / {@code parseSigns}）而不是
     * 原样返回字符串：解析失败会抛 {@code 5001}（口径断裂）。
     * 这是刻意的 —— 见 {@link CaseArchiveSnapshot} 类注释「为什么它不做形态校验」
     * 里那条界限：JSON 的"坏"是<b>读不出来</b>（必须报），
     * 而"值不合规"是<b>读出来了但不达标</b>（照原样承载，供人判断）。
     */
    public CaseArchiveSnapshot snapshotOf(String tenantId, UUID archiveId) {
        BandLedger.validateTenantId(tenantId);
        if (archiveId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取归档档案：archive_id 必填");
        }
        return inTenant(tenantId, () -> {
            List<CaseArchiveSnapshot> rows = jdbc.query(
                    "SELECT tenant_id::text, archive_id, customer_id,"
                            + " archive_checklist::text, staff_signs::text,"
                            + " final_conclusion, metrics_trend::text,"
                            + " desensitize_authorized, archived_at, created_by"
                            + "  FROM case_archive WHERE archive_id = ?::uuid",
                    (rs, i) -> new CaseArchiveSnapshot(
                            rs.getString(1),
                            rs.getObject("archive_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            CaseArchiveRecord.parseChecklist(rs.getString("archive_checklist")),
                            CaseArchiveRecord.parseSigns(rs.getString("staff_signs")),
                            rs.getString("final_conclusion"),
                            rs.getString("metrics_trend"),
                            rs.getBoolean("desensitize_authorized"),
                            toInstant(rs.getTimestamp("archived_at")),
                            rs.getString("created_by")),
                    archiveId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 该租户内的归档档案行数。
     *
     * <p>🛑 它<b>单独不足以</b>作为任何一条验收口径 —— 一个只报 {@code 0}
     * 的读法无法区分"没有归档"与"我什么也看不见"（未设上下文时 RLS 恒拒）。
     * 它只作为 {@link #snapshotOf} 的<b>辅助证据</b>：两者一起用，
     * "行数 ≥ 1 而 snapshotOf 返回 null"这种组合本身就是一个可报警的矛盾。
     */
    public int countInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM case_archive", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /**
     * 该客户在本租户内是否已有归档档案（存在性判定，不做全序 —— 与
     * {@link #latestArchiveOf} 的分工：这个回答"有没有"，那个回答"哪一份"）。
     *
     * <p>🛑 用 {@code EXISTS} 而不是 {@code count(*) > 0}：
     * 前者在命中第一行时即可停止，而归档档案的 JSONB 列（清单 + 签名）
     * 可能不小；更重要的是 {@code EXISTS} 把意图写在语句里 ——
     * 读代码的人不必推理"我只需要知道有没有，为什么要数全部"。
     */
    public boolean isArchived(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询归档存在性：customer_id 必填");
        }
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM case_archive WHERE customer_id = ?::uuid)",
                    Boolean.class,
                    customerId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code BandLedger.inTenant} / {@code BandBindingLedger.inTenant} /
     * {@code OrganizationProvisioningRepository.inTenant} / {@code DeviceLedger.inTenant}
     * / {@code ScaleLedger.inTenant} 同款，理由逐一相同：
     * {@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"收敛成一个短事务，
     * 使「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立。
     *
     * <p>🛑 本类的写侧（{@link #register}）<b>不走</b>这里：它的上下文由
     * V20 的函数自建并自证（{@code set_config} + {@code assert_tenant_context}
     * + 一致性守卫）。故写侧<b>不</b>先 {@code SET LOCAL} —— 若先设了，
     * 函数入口的一致性守卫会看到"事务里已有一个上下文，且与传入租户相同"，
     * 那是允许的，但会让"上下文由谁建立"变得含混。本迁移让函数独占写侧上下文。
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

    // ==================================================================
    // 辅助
    // ==================================================================

    /** {@code java.sql.Timestamp → Instant}，保留可空（列是 NOT NULL，但读侧不假设）。 */
    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}