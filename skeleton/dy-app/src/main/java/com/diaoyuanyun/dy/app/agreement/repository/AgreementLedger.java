package com.diaoyuanyun.dy.app.agreement.repository;

import com.diaoyuanyun.dy.app.agreement.domain.AgreementOutcome;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementRecord;
import com.diaoyuanyun.dy.app.agreement.domain.AgreementSnapshot;
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
 * <b>调理协议书签署登记写入仓储</b> —— 本仓<b>唯一</b>一个往 {@code agreement} 表写行的生产代码。
 *
 * <h2>🛑 它补的是什么：PRD G1 指标在系统里没有任何取数来源</h2>
 * 在 A-1 之前，{@code agreement} 在生产代码里<b>零写入方</b>、真库零行
 * （实测 {@code count(*) = 0}），是 {@code ProvisioningBoundaryGateTest}
 * 的 {@code NOT_PROVISIONED} 三张表之一。
 * <pre>
 *   PRD C.1.5 §八 逐字：「04.§八 四方签署 | agreement.signatures |
 *                        json{客户/经络师/调理师/门店负责人:签名+日期} | 是 |
 *                        <b>未签不得首次调理或退款判定</b> | <b>硬门禁</b>」
 *   PRD C.1.7 第 7 项 逐字：「签调理协议书 | agreement_signed | <b>硬门禁③</b>」
 * </pre>
 * ⇒ 缺了写入方，<b>G1 的取数 SQL 无论怎么写都返回空集</b>，
 * 而<b>没有任何一次调用会因此报错</b>。系统只是"少做了一件事"，安静地少做。
 * <p>🛑 <b>本域与归档域的关键差别：本域有【下游消费者】</b>。
 * 归档是链条的<b>末端收口</b>（它的误判只在举证时暴露）；
 * 而签署登记的下游是 {@code CustomerGateGuard} 的
 * {@code PLAN_APPROVED → AGREEMENT_SIGNED} 跃迁（实测该类已登记该跃迁）。
 * ⇒ 一次错误的返回值会<b>立刻</b>把客户推进已签态，
 * 而"未签不得首次调理"这条硬门禁在那一刻失效。
 * 这直接决定了下面「跨租户撞号必须 RAISE」为什么在本域<b>不可让步</b>。
 *
 * <h2>🛑🛑 三条边界（与 {@code AgreementRecord} 类注释同源，此处只写结论）</h2>
 * <ol>
 *   <li><b>本类不是 I8</b>（{@code renderAgreement}）：不渲染。渲染稿与 hash 是<b>入参</b>。
 *       I8 的出口是厂商，而厂商未选定 ⇒ 写"只渲染不推送"是假实现；</li>
 *   <li><b>本类不是 H1</b>（{@code receiveEsignCallback}）：不接回调、<b>不解析任何厂商签名</b>。
 *       契约逐字禁止在厂商冻结前据该节编码，且一个未验签的回调处理器
 *       <b>等于开一条"任何人 POST 一下即可让系统认为某协议已签"的通道</b>；</li>
 *   <li><b>本类无 HTTP 映射</b>：它是<b>运维通路形态</b>的组件
 *       （{@code AgreementGateTest} 会机械断言它不带任何 HTTP 注解）。
 *       契约 45 个 operationId 里没有任何一个是"登记一份已签协议"。</li>
 * </ol>
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO agreement}，而全部下沉到 V21 的函数</h2>
 * 四条理由，每条都对应一类会静默出错的东西：
 * <ol>
 *   <li><b>门禁判定必须与写入原子</b>：分两步（先查 4 方签署、再插行）在两次调用
 *       之间失败会留下"行已写入、而它的门禁校验发生在另一个事务里"这类不可解释的状态；</li>
 *   <li><b>{@code ON CONFLICT (agreement_id)} 的推断语法 + 跨租户判定</b>：
 *       实测 {@code agreement_pkey} 是<b>单列</b> {@code agreement_id}
 *       （表上没有 {@code (tenant_id, agreement_id)} 载体）。
 *       把"冲突了但我看不见 ⇒ 是别人的"写在 Java 侧意味着两次 JDBC 往返
 *       拼出来 —— 而那是"先读后写"，会引入窗口。封在库层就是一条语句内部的事；</li>
 *   <li><b>租户上下文的自建与自证</b>：函数用
 *       {@code set_config(..., is_local := true)} + {@code assert_tenant_context()}
 *       + 上下文一致性守卫，三者顺序不可调换；</li>
 *   <li><b>口径只有一处</b>：幂等写入<b>只在一处</b>发生（迁移里的函数），
 *       应用侧不再重复。两处都写会造出"口径分叉"。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code register_agreement} 的返回值必须被校验成枚举，而不能透传</h2>
 * 见 {@link AgreementOutcome#fromDb(String)}：未登记的值即抛错。
 * <p>🛑 本域多一层：它同时是"跨租户撞号必须保持为<b>异常</b>"这条设计的守卫 ——
 * 若有人把函数改成"跨租户时返回某个第三态"，枚举解析会先红。
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 与 {@code BandLedger} / {@code CaseArchiveLedger} 逐字同款：
 * 本类的每个公开写方法都只做<b>一次</b>数据库函数调用，看似不需要显式事务。
 * 但那个函数内部会调 {@code set_config(..., is_local := true)} 并依赖它活到
 * 本次调用结束 —— 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态。
 *
 * <h2>🛑 本类刻意不提供的两件事（都是决定，不是遗漏）</h2>
 * <ol>
 *   <li><b>没有 {@code revokeSign} / {@code markSigned} / {@code markUnsigned}</b>：
 *       见 V21 文件头「为什么没有'确认签署/撤销签署'原语」——
 *       任何"把协议置为已签 / 未签"的第二通路都<b>等于</b>开设一条
 *       绕过契约 H1 的旁路；</li>
 *   <li><b>没有"改写协议"的写方法</b>：库里没有"签署后只读"的库层约束
 *       （无改写通路已实现，但库层禁止改写未实现 —— 见 V21 文件头）。
 *       本类不补那条限制，是为了不让"禁止改写"这件事有一个<b>看起来实现了</b>
 *       的假象。见 {@code AgreementGateTest} 的"本类无 UPDATE/DELETE agreement"
 *       静态断言。</li>
 * </ol>
 */
@Component
public class AgreementLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AgreementLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧：V21 的写原语（本迁移唯一的写入口）
    // ==================================================================

    /**
     * 幂等登记一次调理协议书签署。
     *
     * <p>整个动作 = V21 的 {@code register_agreement()} 一次调用。它内部完成：
     * 入参守卫 → JSONB 形态断言 → 上下文一致性守卫 → 建立并自证租户上下文
     * → 客户存在性 → <b>方案版本存在性</b>（带 version + {@code plan_version < 1} 显式业务错误）
     * → <b>四方签署断言</b>（未登记键 + 4 键非空字符串）
     * → 条款快照非空对象 → 渲染稿非空 + hash 形态（{@code [0-9a-fA-F]{64}}）
     * → 模板指针成对 + 模板存在性 + 版本相符
     * → 单条 {@code INSERT ... ON CONFLICT (agreement_id) DO NOTHING} 原子写入
     * → 按"冲突了但它是不是我的"判两态（第三态 RAISE）。
     *
     * <p>🛑 <b>会抛的六种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>四方签署缺项或为空 ⇒ 函数 RAISE（消息列出缺项名称）——
     *       对应 P0-25「403 且给出缺失项名称」。🛑 正常情况下本方法
     *       <b>不会</b>走到这一条：{@link AgreementRecord} 的构造器已经先拦了。
     *       库层那一道守的是"有人绕过 record 直接写 SQL"；</li>
     *   <li>条款快照为空对象 / 渲染稿空白 / hash 形态非法 ⇒ 函数 RAISE（同上，构造器已先拦）；</li>
     *   <li>客户不在本租户内 ⇒ 函数 RAISE（复合外键 {@code 23503}）；</li>
     *   <li>方案版本不存在（或 {@code plan_version < 1}）⇒ 函数 RAISE ——
     *       🛑 本域特有，归档域无此步；</li>
     *   <li>模板指针不成对 / 模板不存在 / 版本不符 ⇒ 函数 RAISE —— 🛑 本域特有；</li>
     *   <li>{@code agreement_id} 被<b>另一租户</b>占用 ⇒ 函数 RAISE
     *       （见 {@link AgreementOutcome} 类注释「跨租户撞号为什么必须是异常」）。</li>
     * </ul>
     * 调用方<b>不应</b>把这几条捕获后当成"再试一次"——它们都需要人工确认。
     *
     * <p>🛑 <b>{@code created_at} 不在此处写</b>：它是<b>登记时刻</b>，
     * 由表的 {@code default now()} 提供（实测 {@code column_default = now()}）。
     * 本域的业务时刻是 {@code signed_at}（由入参提供）。两处一致的口径是
     * <b>"业务时刻显式写、登记时刻交给 default"</b>。
     *
     * @return 两态之一（见 {@link AgreementOutcome}）；
     *         {@code ALREADY_EXISTS} <b>不是错误</b>
     */
    public AgreementOutcome register(AgreementRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "协议签署登记：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_agreement(?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::int,"
                        + " ?::jsonb, ?::timestamptz, ?::jsonb, ?::text, ?::text,"
                        + " ?::jsonb, ?::uuid, ?::int, ?::text)",
                String.class,
                record.tenantId(),
                record.agreementId().toString(),
                record.customerId().toString(),
                record.planId().toString(),
                record.planVersion(),
                record.refundClauseJson(),
                record.signedAt().toString(),
                record.signerJson(),
                record.renderedSnapshot(),
                record.renderedHash(),
                record.breachClauseJson(),
                record.docTemplateId() == null ? null : record.docTemplateId().toString(),
                record.docTemplateVersion(),
                record.createdBy()));

        return AgreementOutcome.fromDb(raw);
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 该客户在本租户内的<b>最新</b>协议 id（无则 {@code null}）。
     *
     * <p>🛑 走 V21 的 {@code latest_agreement_of()} 而不是在这里重新写一条 SELECT：
     * "最新"的判定需要一个<b>全序</b>（{@code signed_at DESC, agreement_id DESC}），
     * 而 {@code signed_at} 单独<b>不够</b> —— 同一时刻签署的两份协议会给出
     * 不确定的先后。若本类自己写 {@code ORDER BY signed_at DESC LIMIT 1}，
     * 就会与函数里那份全序形成两套口径，而它们的差别<b>不会报错</b>，
     * 只会在并发签署时表现为"两次调用返回不同的 agreement_id"。
     *
     * <p>🛑 排序键是 {@code signed_at} 而<b>不是</b> {@code created_at}：
     * 前者是<b>业务时刻</b>（"协议何时签的"），后者是<b>登记时刻</b>（"这行何时入库"）。
     * 离线补录会让两者分叉 —— 一份上周签的纸面协议今天补录，
     * 它的 {@code created_at} 是今天而 {@code signed_at} 是上周。
     * V21 自证 (e16) 用"刻意让最后写入的是中间时间那份"来钉住这条语义。
     */
    public UUID latestAgreementOf(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询最新协议：customer_id 必填");
        }
        return tx.execute(status -> jdbc.queryForObject(
                "SELECT latest_agreement_of(?::uuid, ?::uuid)",
                UUID.class,
                tenantId,
                customerId.toString()));
    }

    /**
     * 读回一整行协议（本租户内不可见此行时返回 {@code null}）。
     *
     * <p>🛑 为什么返回 {@link AgreementSnapshot}（整行）而不是几个标量读法：
     * 见该 record 的类注释「为什么必须是一个整行对象」——
     * 标量读法会让"这一行不存在"与"这一行的某个字段为空"共用同一个 {@code null}。
     *
     * <p>🛑 它是本迁移<b>最重要的可观测面</b>：门禁要回答的问题不是
     * "库里有几行"，而是"<b>写进去的那份协议，读回来还是不是那一份</b>"。
     * 只断言行数会漏掉"重放时用新参数改写了既有证据"这一类缺陷。
     *
     * <p>🛑 三个 JSONB 列在这里<b>即时解析</b>（走
     * {@link AgreementRecord#parseSigner} / {@code parseClause}）而不是原样返回字符串：
     * 解析失败会抛 {@code 9001}（口径断裂）—— 见 {@link AgreementSnapshot}
     * 类注释里那条界限：JSON 的"坏"是<b>读不出来</b>（必须报），
     * 而"值不合规"是<b>读出来了但不达标</b>（照原样承载，供人判断）。
     */
    public AgreementSnapshot snapshotOf(String tenantId, UUID agreementId) {
        BandLedger.validateTenantId(tenantId);
        if (agreementId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "读取协议：agreement_id 必填");
        }
        return inTenant(tenantId, () -> {
            List<AgreementSnapshot> rows = jdbc.query(
                    "SELECT tenant_id::text, agreement_id, customer_id, plan_id, plan_version,"
                            + " refund_clause_snapshot::text, breach_clause_snapshot::text,"
                            + " signed_at, signer::text,"
                            + " doc_template_id, doc_template_version,"
                            + " rendered_snapshot, rendered_hash, created_at, created_by"
                            + "  FROM agreement WHERE agreement_id = ?::uuid",
                    (rs, i) -> new AgreementSnapshot(
                            rs.getString(1),
                            rs.getObject("agreement_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getObject("plan_id", UUID.class),
                            rs.getInt("plan_version"),
                            AgreementRecord.parseClause(rs.getString("refund_clause_snapshot")),
                            AgreementRecord.parseClause(rs.getString("breach_clause_snapshot")),
                            toInstant(rs.getTimestamp("signed_at")),
                            AgreementRecord.parseSigner(rs.getString("signer")),
                            rs.getObject("doc_template_id", UUID.class),
                            (Integer) rs.getObject("doc_template_version"),
                            rs.getString("rendered_snapshot"),
                            rs.getString("rendered_hash"),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString("created_by")),
                    agreementId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 该租户内的协议行数。
     *
     * <p>🛑 它<b>单独不足以</b>作为任何一条验收口径 —— 一个只报 {@code 0}
     * 的读法无法区分"没有签署"与"我什么也看不见"（未设上下文时 RLS 恒拒）。
     * 它只作为 {@link #snapshotOf} 的<b>辅助证据</b>。
     */
    public int countInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM agreement", Integer.class);
            return n == null ? 0 : n;
        });
    }

    /**
     * 该客户在本租户内是否已有协议（存在性判定，不做全序 —— 与
     * {@link #latestAgreementOf} 的分工：这个回答"有没有"，那个回答"哪一份"）。
     *
     * <p>🛑 用 {@code EXISTS} 而不是 {@code count(*) > 0}：
     * 前者在命中第一行时即可停止，而协议的 JSONB 列（条款快照 + 签署块 +
     * 渲染稿正文）可能不小；更重要的是 {@code EXISTS} 把意图写在语句里。
     */
    public boolean hasAgreement(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询协议存在性：customer_id 必填");
        }
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM agreement WHERE customer_id = ?::uuid)",
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
     * <p>与 {@code BandLedger.inTenant} / {@code CaseArchiveLedger.inTenant} 同款，
     * 理由逐一相同：{@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"
     * 收敛成一个短事务，使「未设租户上下文 = 零行」这条 fail-closed 性质
     * 在所有路径上都成立。
     *
     * <p>🛑 本类的写侧（{@link #register}）<b>不走</b>这里：它的上下文由
     * V21 的函数自建并自证（{@code set_config} + {@code assert_tenant_context}
     * + 一致性守卫）。故写侧<b>不</b>先 {@code SET LOCAL}。
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