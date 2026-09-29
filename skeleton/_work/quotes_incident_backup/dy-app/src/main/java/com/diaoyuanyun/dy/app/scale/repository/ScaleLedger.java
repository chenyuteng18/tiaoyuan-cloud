package com.diaoyuanyun.dy.app.scale.repository;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.app.scale.domain.ScaleOutcome;
import com.diaoyuanyun.dy.app.scale.domain.ScaleRecord;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>量表台账写入仓储</b> —— 本仓<b>唯一</b>一个往 {@code scale} 表写行的生产代码。
 *
 * <h2>🛑 它补的是什么：一张表的"零写入方"如何变成一个功能缺口</h2>
 * 在 B-12 之前，{@code scale} 在生产代码里零写入方、真库零行（实测 {@code count(*) = 0}）。
 * 而契约 C2 {@code POST /customers/{id}/assessments/baseline} 的链上<b>有一道存在性预检</b>：
 * <pre>
 *   AssessmentService.submitBaseline 第 ③ 步（L154）：
 *       if (!ledger.scaleExists(tenantId, scaleId)) throw new BizException(BUSINESS_RULE_VIOLATED, ...)
 *   AssessmentLedger.scaleExists（L142）：
 *       SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid)
 * </pre>
 * ⇒ 在 scale 零行的现实下那道检查<b>恒为 false</b> ⇒ C2 <b>恒返回 422 / 5001</b>。
 *
 * <h2>🛑🛑 与 B-11（{@code device}）的关键差别：缺口的对外表现不是 23503，而是 422</h2>
 * 两处同型（零写入方表被有写入方表引用），但<b>链上有无预检</b>决定了缺口的"面孔"：
 * <table border="1">
 *   <caption>同型缺口的两种对外表现</caption>
 *   <tr><th></th><th>B-11 {@code device}</th><th>B-12 {@code scale}</th></tr>
 *   <tr><td>链上有无存在性预检</td><td><b>无</b></td><td><b>有</b></td></tr>
 *   <tr><td>缺口表现</td>
 *       <td>{@code 23503}（只有约束名可读）</td>
 *       <td>{@code 422 / 5001}「量表不存在或不属当前租户」</td></tr>
 *   <tr><td>⇒ 对本类的直接后果</td>
 *       <td>验收口径 = 让 {@code 23503} 消失</td>
 *       <td>🛑 验收口径 = 让<b>那道预检返回 true</b> ——
 *           见 {@link #canReferenceBaseline}。<b>不是</b>"23503 消失"</td></tr>
 * </table>
 * <p>🛑 一句话可迁移的教训：<b>同一个根因（零写入方表）在不同链上会表现成不同的错误种类，
 * 而"验收口径"必须锚在那个链真实的表现上，不能照抄上一条链。</b>
 * 若本迁移把验收写成"23503 消失"，它会<b>恒真</b>（这条链根本不产生 23503）——
 * 那就是一条没有判别力的假绿。
 *
 * <h2>🛑 为什么它不是 Controller，也不该变成 Controller</h2>
 * 逐条核对了契约全部 40 个 path：没有任何 path 是"创建量表"。
 * 唯一与量表相关的读侧 path 是 C1 {@code GET /scales/{id}/item-groups} 一类，
 * 它们操作的是<b>已存在的</b>量表；C2 是"用一个已存在的量表做基线评估"，
 * 不是"创建量表"。给建档加对外端点属契约 <b>MAJOR</b> 变更，
 * 且要先回答契约回答不了的问题：「谁有权给租户建量表」——
 * 量表是<b>口径资产</b>（它决定打分维度），比设备台账更接近"配置"而不是"作业"。
 * ⇒ 与 B-7 / B-10 / B-11 同型：这是<b>契约化决策</b>，不是遗漏。
 * 故本类被刻意做成一个<b>无 HTTP 映射的组件</b>
 * （{@code ScaleProvisioningGateTest} 会机械断言它不带任何 HTTP 注解）。
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO scale}，而全部下沉到 V19 的函数</h2>
 * 三条理由（与 {@code DeviceLedger} 逐字同款，但第 ① 条<b>本域有两条</b>）：
 * <ol>
 *   <li><b>判定链写在库层才原子</b>：V19 的 {@code register_scale} 要在<b>一条语句内</b>
 *       完成「插入 → 冲突 → 冲突了是不是我的 → 是不是同一版」四步。
 *       任何一步搬回 Java 侧都意味着"先读后写"，引入窗口；
 *       而这里的窗口后果是<b>静默的</b>：读到"不是我的"之后对方恰好删行，
 *       我就会把一个正确的跨租户判定变成一个不存在的错误。
 *       <p>🛑 本域有<b>两条</b>判定（跨租户 + 版本冲突），V18 只有一条 ——
 *       判定越多，"拼在应用侧"越容易漏掉一条。</li>
 *   <li><b>口径只有一处</b>：幂等写入<b>只在一处</b>发生（迁移里的函数），应用侧不再重复。
 *       两处都写会造出"口径分叉"：平时无害，一旦有人只改了其中一处，
 *       就会出现"同一个动作有两种结果"的静默不一致。</li>
 *   <li><b>上下文建立与写入必须原子</b>：{@code set_config(..., is_local := true)}
 *       的作用域是<b>事务</b>。分两步会让上下文在一半的时候失效 ⇒ 被 RLS 拒。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code register_scale} 的返回值必须被校验成枚举，而不能透传</h2>
 * 见 {@link ScaleOutcome#fromDb(String, String)}：未登记的值即抛错。
 * 理由与 {@code DeviceOutcome#fromDb} 逐字同款 —— 若函数被改成别的口径，
 * 这里会先红，而不是让一个无法解释的字符串流进审计 payload。
 * <p>🛑 本域还多一层：V19 有<b>两条</b>"必须是异常"的情形，故那条报错文案里
 * 逐条点名了它们。这是刻意的 —— 它使"谁把版本冲突改成了返回值"这件事
 * 无需再去读迁移就能从报错里看懂。
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 与 {@code DeviceLedger} / {@code BandBindingLedger} 逐字同款：本类的每个公开写方法都只做
 * <b>一次</b>数据库函数调用，看似不需要显式事务。但那个函数内部会调
 * {@code set_config(..., is_local := true)} 并依赖它活到本次调用结束 ——
 * 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态。
 */
@Component
public class ScaleLedger {

    /** 量表状态枚举取值（{@code scale.status} 的 CHECK 冻结值）——
     *  供读侧断言用，避免在 SQL 字面量里散落裸字符串。 */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DEPRECATED = "deprecated";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ScaleLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧：两个 V19 原语
    // ==================================================================

    /**
     * 往租户建一份量表台账（幂等，但<b>版本必须一致</b>才算重放）。
     *
     * <p>整个动作 = V19 的 {@code register_scale()} 一次调用。它内部完成：
     * 建立并自证租户上下文 → 单条
     * {@code INSERT ... ON CONFLICT (scale_id) DO NOTHING} 原子写入 →
     * 按"冲突了但它是不是我的" + "是不是同一版"判两态。
     *
     * <p>🛑 <b>会抛的两种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>{@code scale_id} 被<b>另一租户</b>占用 ⇒ 函数 RAISE
     *       （{@code scale_pkey} 是单列 {@code scale_id} ⇒ 别人的 id 会撞上来）；</li>
     *   <li>同一 {@code scale_id} 在<b>本租户</b>名下已存在但<b>版本不同</b> ⇒ 函数 RAISE
     *       （消息含「不可覆盖」）—— 在单列主键下"版本递增"库层无法表达，
     *       见 {@link ScaleOutcome} 类注释的对照表。</li>
     * </ul>
     * 调用方<b>不应</b>把这两条捕获后当成"再试一次"——它们需要人工确认。
     *
     * @return 两态之一（见 {@link ScaleOutcome}）；
     *         {@code ALREADY_EXISTS} <b>不是错误</b>
     */
    public ScaleOutcome register(ScaleRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "量表建档：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_scale(?::uuid, ?::uuid, ?, ?, ?, ?::jsonb)",
                String.class,
                record.tenantId(),
                record.scaleId().toString(),
                record.scaleType(),
                record.scaleVersion(),
                record.name(),
                record.dimensionSetJson()));

        return ScaleOutcome.fromDb(raw, "register");
    }

    /**
     * 废弃一份量表（幂等）。
     *
     * <p>🛑 是<b>状态迁移</b>（{@code status → 'deprecated'}）而不是删除行。
     * 两条理由（见 V19 {@code deprecate_scale()} 的注释）：
     * <ol>
     *   <li>{@code baseline_assessment} 通过复合外键
     *       {@code (tenant_id, scale_id)} 引用本表（{@code scale_id} NOT NULL）——
     *       删掉一行会让历史基线评估失去可追溯的"用的是哪份量表"，
     *       而那些评估是客户档案的一部分（{@code locked = TRUE}，不可改）；</li>
     *   <li>{@code deprecated} 与 {@code active} 的区分是"还能不能用于<b>新建</b>评估"，
     *       是<b>口径</b>而不是"存在与否"。</li>
     * </ol>
     * <p>🛑 副作用（刻意保留，且<b>比 device 更重</b>）：
     * {@code deprecated} 行仍占据 {@code scale_id} 主键 ⇒
     * ① 重放建档返回 {@code ALREADY_EXISTS}（若版本相同）；
     * ② <b>"同一 scale_id 换一版内容重新启用"在库层不可能实现</b> ——
     * 这是 V19 第 5 节登记的第 ③ 条缺口（{@code reactivate_scale} 未提供）。
     * 本方法<b>刻意不</b>顺手实现"复活"：在<b>版本化</b>语义下"复活哪一版"含义不明，
     * 而且已有的基线评估怎么办是业务决策，不该由一个仓储代拍。
     */
    public ScaleOutcome deprecate(String tenantId, UUID scaleId) {
        BandLedger.validateTenantId(tenantId);
        if (scaleId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "废弃量表：scale_id 必填");
        }

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT deprecate_scale(?::uuid, ?::uuid)",
                String.class,
                tenantId,
                scaleId.toString()));

        return ScaleOutcome.fromDb(raw, "deprecate");
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 该量表在本租户内的状态（无则 {@code null}）。
     *
     * <p>🛑 这是 {@code register} / {@code deprecate} 的<b>可观测面</b>。
     * 与 {@code DeviceLedger#statusOf} 同款：走 {@code inTenant}，
     * 刻意不另造一条"因为我是运维所以不设上下文"的旁路 ——
     * 那种旁路迟早会被某个人接进一个对外接口。
     */
    public String statusOf(String tenantId, UUID scaleId) {
        BandLedger.validateTenantId(tenantId);
        requireScaleId(scaleId, "查询量表状态");
        return inTenant(tenantId, () -> {
            List<String> s = jdbc.query(
                    "SELECT status FROM scale WHERE scale_id = ?::uuid",
                    (rs, i) -> rs.getString(1),
                    scaleId.toString());
            return s.isEmpty() ? null : s.get(0);
        });
    }

    /**
     * 该量表在本租户内的版本号（无则 {@code null}）。
     *
     * <p>🛑 与 {@code AssessmentLedger#scaleVersion} 的 SQL <b>逐字相同</b>，
     * 但两者的角色不同，故<b>不是</b>重复实现：
     * <ul>
     *   <li>{@code AssessmentLedger.scaleVersion} 是 <b>C2 写路径的一部分</b>
     *       （算 {@code migratable} 用）；</li>
     *   <li>本方法是<b>建档原语的可观测面</b> —— 它存在的意义是让
     *       "版本冲突那条 RAISE 说的是不是真的"可以被独立断言
     *       （见 {@code ScaleProvisioningGateTest} 的版本冲突用例）。</li>
     * </ul>
     * 若把本方法做成"调用 AssessmentLedger"，两个域的依赖方向会反转
     * （device/scale 侧不该依赖 assessment 侧）—— 那是更坏的选择。
     */
    public String versionOf(String tenantId, UUID scaleId) {
        BandLedger.validateTenantId(tenantId);
        requireScaleId(scaleId, "查询量表版本");
        return inTenant(tenantId, () -> {
            List<String> v = jdbc.query(
                    "SELECT scale_version FROM scale WHERE scale_id = ?::uuid",
                    (rs, i) -> rs.getString(1),
                    scaleId.toString());
            return v.isEmpty() ? null : v.get(0);
        });
    }

    /**
     * 该租户内当前 {@code active} 量表的行数。
     *
     * <p>供"建档确实生效"与"废弃确实生效"两侧各有一个可断言的正整数 ——
     * 一个只报 {@code 0} 的读法无法区分"没有有效量表"与"我什么也看不见"。
     */
    public int countActiveInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM scale WHERE status = '" + STATUS_ACTIVE + "'",
                    Integer.class);
            return n == null ? 0 : n;
        });
    }

    /** 该租户内 {@code deprecated} 量表的行数（供"废弃行仍占主键"这条性质可断言）。 */
    public int countDeprecatedInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM scale WHERE status = '" + STATUS_DEPRECATED + "'",
                    Integer.class);
            return n == null ? 0 : n;
        });
    }

    /**
     * 🛑🛑 <b>契约 C2 端点是否已解锁</b> —— 本迁移<b>唯一</b>最终要回答的问题。
     *
     * <h2>为什么这是本迁移的验收口径，而不是"23503 消失"</h2>
     * {@code device}（B-11）那条链没有存在性预检，故缺口表现是一条 {@code 23503}，
     * 验收口径是"这条 23503 不再发生"。
     * <p>🛑 而 {@code scale} 这条链<b>有一道业务预检</b>（{@code AssessmentService} 第 ③ 步），
     * 它把缺口翻译成 {@code 422 / 5001}「量表不存在或不属当前租户」。
     * ⇒ 本迁移的验收必须是<b>"那道预检会返回 true"</b>。
     * 若照抄 B-11 写成"23503 消失"，这条验收会<b>恒真</b>
     * （这条链根本不产生 23503）—— 那就是一条没有判别力的假绿。
     *
     * <h2>🛑 为什么用 {@code EXISTS(... FOR KEY SHARE)} 而不是 {@code count(*)}</h2>
     * {@code FOR KEY SHARE} 拿的正是外键检查所需的那一类行锁
     * ⇒ 它证明的是"这一行<b>可以</b>被外键引用"，
     * 而一个普通 {@code SELECT} 只证明"我读得到它"。
     * <p>本题里两者的差别<b>不是理论上的</b>：{@code baseline_assessment_scale_id_fkey}
     * 实测是复合外键 {@code (tenant_id, scale_id) → scale(tenant_id, scale_id)}，
     * 其引用目标 {@code uq_tenant_scale_scale_id} 与主键都在 {@code scale} 上 ——
     * 但对一个未来可能把引用目标改成"不含主键的另一个唯一键"的 schema，
     * {@code FOR KEY SHARE} 的意图表达仍然正确。<b>把意图写在语句里</b>，
     * 读代码的人不必再推理一次"读得到是否等于引用得起"。
     *
     * <h2>🛑 它不是"跨租户存在性探测口"</h2>
     * 走 {@code inTenant}，故别的租户的量表在本读法下恒为 {@code false} ——
     * 与 RLS 的可见性一致。
     * <p>⚠️ 本仓对这个形态有过一条实测教训（{@code DeviceProvisioningGateTest} 判据 ⑤f）：
     * <b>只断言"跨租户为 false"是不够的</b> —— 一个"恒返回 false"的实现也能过。
     * 故这里必须<b>同时</b>被断言的还有它的<b>对角线</b>：
     * 在本租户上下文里对<b>本租户</b>的量表必须为 {@code true}。
     * 该对称矩阵由 {@code ScaleProvisioningGateTest} 承担。
     */
    public boolean canReferenceBaseline(String tenantId, UUID scaleId) {
        BandLedger.validateTenantId(tenantId);
        requireScaleId(scaleId, "查询量表可引用性");
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM scale WHERE scale_id = ?::uuid FOR KEY SHARE)",
                    Boolean.class,
                    scaleId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    private static void requireScaleId(UUID scaleId, String what) {
        if (scaleId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, what + "：scale_id 必填");
        }
    }

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code BandLedger.inTenant} / {@code BandBindingLedger.inTenant} /
     * {@code OrganizationProvisioningRepository.inTenant} / {@code DeviceLedger.inTenant}
     * 同款，理由逐一相同：
     * {@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"收敛成一个短事务，
     * 使「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立。
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