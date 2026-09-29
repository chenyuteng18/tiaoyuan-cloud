package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.app.customer.domain.CustomerDetailView;
import com.diaoyuanyun.dy.app.customer.domain.CustomerState;
import com.diaoyuanyun.dy.app.customer.domain.CustomerStatus;
import com.diaoyuanyun.dy.app.customer.domain.CustomerUpsertDraft;
import com.diaoyuanyun.dy.app.customer.domain.Gender;
import com.diaoyuanyun.dy.app.customer.domain.StateTransitionDraft;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code customer}（V1 §customer + V7 补 5 列）与 {@code customer_state_transition}（V2）
 * 的 JDBC 账本 —— 域 B 的<b>主实体仓储</b>。
 *
 * <h2>一、🛑 本仓储承载的调用链（B1 与 B2 的真实前后关系）</h2>
 * 契约域 B 的六行里有一条<b>隐含的依赖链</b>，不写清就会写出一个跑不通的实现：
 * <pre>
 *  V5 screening_record.customer_id UUID NOT NULL REFERENCES customer (id)
 *  ⇒ 提交筛查（B1）之前，customer 行【必须已存在】—— 即 data-dict §2.25 ②
 *    「CREATED ↔ SCREENING（<b>已建档键</b>、准入未完成）」里的"建键"必须先发生。
 *  ⚠️ 而域 B 六行【没有】暴露"建键"端点 —— 这是一处<b>已登记的缺口</b>
 *     （见 {@link #describeKeyCreationGap()}），不是本类的疏漏。
 * </pre>
 * B2 又<b>没有</b> {@code customer_id} 入参（{@code required: [name, gender, age, phone, screening_id]}），
 * 故它必须由 {@code screening_id} <b>反查</b>出客户标识 —— 见 {@link #findCustomerIdByScreening}。
 * 这条反查是 B1→B2 唯一自洽的连接方式，本类把它落成一个显式方法而非散在服务层。
 *
 * <h2>二、🛑 {@code status} 一律显式写入，绝不依赖库层 {@code DEFAULT 'pending'}</h2>
 * V1 的 {@code customer.status} 有 {@code DEFAULT 'pending'}，而权威 5 值是
 * {@code CREATED/PROFILED/CONSENTED/REJECTED/ARCHIVED} —— {@code 'pending'} 不在其中。
 * 故本类<b>每一条</b> INSERT 都显式带 {@code status}；{@code CustomerUpsertDraft} 的
 * 构造期已强制其非空（见该类注释）。这是"库层默认值坑"在本域的第二次加固
 * （第一次在 {@code consent.data_source}）。
 *
 * <h2>三、🛑 {@code owner_id} 与 {@code owner_store_id} 是两个不同的东西</h2>
 * V1 的 {@code customer} 表有一列 {@code owner_id}，其行尾注释逐字写
 * 「门店/客户级 owner, 不可为空 (<b>ADR-12 owner 不可空</b>)」。而 V7 新增的是
 * {@code owner_store_id}（FK store）。两者<b>不得互相代填</b>：
 * <pre>
 *   owner_store_id —— data-dict §2.6「归属/首诊店，锁定档案」⇒ 归属【门店】
 *   owner_id       —— ADR-12 的"owner 不可空"指的是【合规责任人】（compliance
 *                     owners.csv 里 subject → 实名），骨架期被误挂到本列上
 * </pre>
 * 本类<b>不</b>在写入路径上给 {@code owner_id} 赋门店值 —— 那会让"归属门店"
 * 出现两个来源（{@code owner_id} 与 {@code owner_store_id}），而它们必然分叉。
 * 该列的语义歧义以 {@link #describeOwnerColumnAmbiguity()} 登记为可断言事实。
 *
 * <h2>四、越界与缺失一律不夹逼、不兜底</h2>
 * {@code age} 越界报 1001（见 {@code CustomerUpsertDraft}）；五列在库层可空
 * （V7 刻意不加 NOT NULL，三条理由见 V7 文件头），但<b>写入路径</b>由
 * {@code CustomerUpsertDraft} 强制齐备 —— "必填"的权威落点是写入路径，不是表结构。
 *
 * <h2>五、隔离机制（与域 B 其余三本账本同一套）</h2>
 * 每次操作开<b>短事务</b>并 {@code SET LOCAL app.tenant_id}；<b>不</b>在 SQL 里写
 * {@code WHERE tenant_id = ?}，隔离完全由 V1/V2 的 {@code FORCE ROW LEVEL SECURITY}
 * 承担（ADR-02）。租户 ID 经 {@link #UUID_PATTERN} 白名单后拼接。
 */
@Repository
public class CustomerLedger {

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CustomerLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 一、写：建键（B1 的前置，域 B 六行未暴露的动作）
    // ==================================================================

    private static final String INSERT_KEY_SQL =
            "INSERT INTO customer (id, tenant_id, name, status, owner_store_id) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?::uuid) "
                    + "ON CONFLICT (id) DO NOTHING";

    /**
     * 建立客户键（幂等）—— 状态 {@code CREATED}（派生自入口态 {@code SCREENING}）。
     *
     * <h2>🛑 为什么在建键处就写 {@code CREATED} 而不是等 B2</h2>
     * data-dict §2.25 ② 逐字：「{@code CREATED} ↔ {@code SCREENING}（已建档键、
     * <b>准入未完成</b>）」—— "建键"这个动作正是 {@code CREATED} 的定义。
     * 若建键时不写 status，该行会拿到库层默认 {@code 'pending'}，
     * 而那不在权威 5 值内（见类注释第二节）。
     *
     * <h2>🛑 {@code name} 在建键时只能取 {@code phone}</h2>
     * V1 的 {@code name} 列 NOT NULL，而 B1（筛查）阶段<b>还没有</b>客户姓名
     * （姓名是 B2 的入参）。故建键时以 {@code phone} 作为 provisional name，
     * 由 B2 经 {@link #promoteToProfiled} 覆写为真实姓名。
     * <ul>
     *   <li>用 {@code phone} 而不是空串 / {@code "待补"}：{@code phone} 是
     *       <b>跨店识别键</b>（契约 B2 description），它在建键阶段就已知，
     *       使运维在不查别处的情况下就能辨认这一行是谁；</li>
     *   <li>🛑 这是一个<b>临时占位</b>，且 B2 必然覆写它 —— 见 {@link #promoteToProfiled}
     *       的 SQL 里 {@code name = EXCLUDED.name}。若有人让建键成为终态，
     *       客户姓名会永久停在手机号上，而这一点不会报错。</li>
     * </ul>
     *
     * @return 该 {@code customerId} 在此租户下的实际行数（1 = 本次新建或已存在）
     */
    public int ensureKey(String tenantId, UUID customerId, String phone, UUID ownerStoreId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        if (phone == null || phone.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建键缺 phone —— 它是本阶段的 provisional name（V1 name 列 NOT NULL），"
                            + "也是跨店识别键；无它则这一行在库里无法被辨认");
        }
        if (ownerStoreId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建键缺 owner_store_id —— data-dict §2.6「归属/首诊店，锁定档案」。"
                            + "🛑 建档阶段可以没有姓名，但【必须】有归属店："
                            + "「锁定档案」正是靠它成立，缺它则该客户后续归属无从判定");
        }
        requireUuid(ownerStoreId.toString());
        return inTenant(tenantId, () -> jdbc.update(INSERT_KEY_SQL,
                customerId.toString(),
                phone,
                CustomerStatus.CREATED.code(),
                ownerStoreId.toString()));
    }

    // ==================================================================
    // 二、写：B2 建档（补全五列 + 状态跃迁，同事务）
    // ==================================================================

    private static final String UPDATE_PROFILE_SQL =
            "UPDATE customer SET"
                    + "  name = ?, gender = ?, age = ?, phone = ?,"
                    // 🛑 owner_store_id 必须显式 ::uuid（与 serving_store_id 同款）。
                    //    缺它会报 "字段 owner_store_id 的类型为 uuid, 但表达式的类型为 character varying"
                    //    （PG 42804）→ 被包成 BadSqlGrammarException → B2 恒 500。
                    //    成因：JDBC 的 setString 把参数按 varchar 类型发送，而 PG 的隐式
                    //    "unknown → uuid" 转换只对【未定型】参数生效；一旦参数被声明为 varchar，
                    //    "varchar → uuid" 就不再是隐式转换（它是显式转换）。故 uuid 列一律 `?::uuid`。
                    + "  owner_store_id = ?::uuid, serving_store_id = ?::uuid,"
                    + "  status = ?, updated_at = now()"
                    + " WHERE id = ?::uuid";

    /**
     * B2 建档：在既有客户键上补全五列，并把状态推到 {@code PROFILED}（同一事务）。
     *
     * <h2>🛑 为什么是 UPDATE 而不是 INSERT</h2>
     * 客户键在 B1 之前已由 {@link #ensureKey} 建立（见类注释第一节的依赖链）。
     * 若此处改成 INSERT，会因 PK 冲突失败；若改成"先删后插"，
     * 会连带删掉 {@code screening_record} / {@code consent} 的外键引用 ——
     * 而那是<b>不可删除的证据</b>（契约 B1「记录不可删除」）。
     *
     * <h2>🛑 本方法<b>不</b>自行判定门禁</h2>
     * 门禁（筛查通过 + 未 REJECTED）由服务层经
     * {@code CustomerGateGuard.assertAdmissionChain} 判定后<b>才</b>调用本方法。
     * 理由：门禁回答"缺什么前置"（403），写入回答"怎么落库"。
     * 把门禁塞进仓储会让一次"读路径"也被迫带上数据库写权限的语义。
     *
     * <h2>🛑 三条 SQL 在同一事务内的顺序不可换</h2>
     * <pre>
     *  ① 补全 customer 五列 + status
     *  ② 旧 current 跃迁置 is_current = false（V2 的 uq_cst_current 部分唯一索引要求）
     *  ③ 追加新跃迁（is_current = true）
     * </pre>
     * ②必须先于③：否则③插入时旧行仍 {@code is_current = true}，
     * {@code uq_cst_current} 会以<b>约束名</b>报冲突，而不是"这里顺序写反了"。
     *
     * @return 受影响行数（0 = 该 customerId 不存在 —— 服务层应据 404 语义处置）
     */
    public int promoteToProfiled(String tenantId, CustomerUpsertDraft draft,
                                 CustomerState fromState, UUID transitionId,
                                 String triggerEvent, Instant occurredAt, UUID operatorId,
                                 String createdBy) {
        if (draft == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "建档写入草稿为空，拒绝写入");
        }
        requireUuid(tenantId);
        requireUuid(draft.customerId().toString());

        return inTenant(tenantId, () -> {
            int updated = jdbc.update(UPDATE_PROFILE_SQL,
                    draft.name(),
                    draft.gender().code(),
                    draft.age(),
                    draft.phone(),
                    draft.ownerStoreId() == null ? null : draft.ownerStoreId().toString(),
                    draft.servingStoreId() == null ? null : draft.servingStoreId().toString(),
                    draft.status().code(),
                    draft.customerId().toString());

            if (updated > 0) {
                // ② 旧的当前跃迁让位（唯一可更新字段 = is_current，V2 明文允许）
                jdbc.update("UPDATE customer_state_transition SET is_current = false, updated_at = now()"
                                + " WHERE customer_id = ?::uuid AND is_current = true",
                        draft.customerId().toString());
                // ③ 追加新跃迁
                StateTransitionDraft t = StateTransitionDraft.passed(
                        transitionId, fromState, CustomerState.PROFILED, triggerEvent,
                        operatorId, occurredAt, "customer", draft.customerId().toString(), createdBy);
                insertTransition(draft.customerId().toString(), t);
            }
            return updated;
        });
    }

    // ==================================================================
    // 三、写：状态跃迁（B1 的 REJECTED 副作用也走这里）
    // ==================================================================

    private static final String INSERT_TRANSITION_SQL =
            "INSERT INTO customer_state_transition ("
                    + " transition_id, tenant_id, customer_id, from_state, to_state, is_current,"
                    + " trigger_event, guard_result, missing_items, operator_id, occurred_at,"
                    + " ref_entity, ref_id, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?, ?, ?, ?)";

    /**
     * 追加一条状态跃迁（<b>含 blocked 留痕</b>）。
     *
     * <h2>🛑 {@code blocked} 行写入但<b>不改当前态</b></h2>
     * V2 附注逐字：「{@code passed} / {@code blocked}（§2.25 ③: blocked 记 403 缺失项，
     * 【不写 to_state 变更】；但本表 append-only，blocked 的跃迁尝试"须留痕"
     * → 写一行、不改 {@code is_current}）」。
     * 故本方法据 {@link StateTransitionDraft#blocked()} 分两路：
     * <pre>
     *   passed  → 先让位旧 current，再插 is_current = true
     *   blocked → 只插 is_current = false（不碰旧 current）
     * </pre>
     * 若把 blocked 也写成 {@code is_current = true}，客户的"当前态"会变成
     * 一个<b>他从未到达的态</b>（{@code to_state} 在 blocked 行里是"被尝试的目标"），
     * 而后续所有门禁都会按那个错态判定。
     */
    public void appendTransition(String tenantId, UUID customerId, StateTransitionDraft draft) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        if (draft == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "状态跃迁草稿为空，拒绝写入");
        }
        inTenant(tenantId, () -> {
            if (!draft.blocked()) {
                jdbc.update("UPDATE customer_state_transition SET is_current = false, updated_at = now()"
                                + " WHERE customer_id = ?::uuid AND is_current = true",
                        customerId.toString());
            }
            insertTransition(customerId.toString(), draft);
            return null;
        });
    }

    /** 单行 INSERT（供 {@link #promoteToProfiled} 与 {@link #appendTransition} 共用，避免两份 SQL）。 */
    private void insertTransition(String customerId, StateTransitionDraft t) {
        Object missing = t.missingItems().isEmpty() ? null : Json.toJsonArray(t.missingItems());
        jdbc.update(INSERT_TRANSITION_SQL,
                t.transitionId().toString(),
                customerId,
                t.fromState() == null ? null : t.fromState().code(),
                t.toState().code(),
                !t.blocked(),                       // blocked 行不得成为当前态
                t.triggerEvent(),
                t.blocked() ? "blocked" : "passed",
                missing,
                t.operatorId() == null ? null : t.operatorId().toString(),
                ts(t.occurredAt()),
                t.refEntity(),
                t.refId(),
                t.createdBy());
    }

    // ==================================================================
    // 三-B、写：刷新派生聚合态（状态机的副作用，非独立动作）
    // ==================================================================

    private static final String UPDATE_STATUS_SQL =
            "UPDATE customer SET status = ?, updated_at = now() WHERE id = ?::uuid";

    /**
     * 刷新 {@code customer.status}（<b>5 值派生聚合</b>）。
     *
     * <h2>🛑 它是状态机跃迁的<b>副作用</b>，不是一个独立可调的动作</h2>
     * data-dict §2.25 ② 纪律逐字：「14 态是<b>唯一权威</b>、5 值是<b>派生聚合</b>；
     * {@code customer.status} 由状态机实体推导刷新（<b>在跃迁同事务内更新</b>），
     * <b>不得反向由 5 值推断 14 态</b>」。
     *
     * <p>故本方法的调用点只有一个形态：<b>刚追加完一条 {@code passed} 跃迁之后</b>
     * （见 {@code CustomerService} 的 B1/B2/B3）。它<b>不得</b>被用来"直接改状态" ——
     * 那会让 {@code status} 与 {@code customer_state_transition} 分叉，
     * 而分叉的表现是"客户 status=CONSENTED 但跃迁链停在 PROFILED"，
     * 两者都不报错，只是后续每条门禁按哪一个判不再确定。
     *
     * <h2>🛑 为什么值由调用方给而不是本方法从态推导</h2>
     * {@link CustomerStatus#of(CustomerState)} 是<b>一对多</b>映射
     * （{@code CONSENTED} 聚合 9 个态）。由调用方给值，是为了让"这次跃迁落在
     * 哪个聚合态"在调用点可读；若在本方法内由态推导，则"跃迁到 PLAN_APPROVED 时
     * status 该是什么"这件事会被藏在一个不在调用链上的函数里。
     * 值本身的正确性由 {@link CustomerUpsertDraft} 的构造期与
     * {@code CustomerStatus} 的映射表共同保证。
     *
     * @return 受影响行数（0 = 该客户在本租户内不存在）
     */
    public int updateStatus(String tenantId, UUID customerId, CustomerStatus status) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        if (status == null) {
            // 🛑 不得依赖库层 DEFAULT 'pending'（它不在权威 5 值内，见类注释第二节）
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "刷新 customer.status 时值为空 —— V1 该列有 DEFAULT 'pending'，"
                            + "而 'pending' 不在权威 5 值（CREATED/PROFILED/CONSENTED/REJECTED/ARCHIVED）内；"
                            + "写入路径必须显式给值，不得依赖库层默认值");
        }
        return inTenant(tenantId, () -> jdbc.update(UPDATE_STATUS_SQL,
                status.code(), customerId.toString()));
    }

    // ==================================================================
    // 四、读
    // ==================================================================

    private static final String SELECT_KEY_BY_PHONE_SQL =
            "SELECT id FROM customer WHERE phone = ?";

    /**
     * 按 {@code phone} 查客户标识（<b>跨店识别键</b>，契约 B2 description 逐字）。
     *
     * <p>⚠️ 唯一性由 V7 的 {@code uq_customer_tenant_phone}
     * （{@code UNIQUE (tenant_id, phone) WHERE phone IS NOT NULL}）承担，
     * 且<b>必然</b>是租户级、不是门店级：客户属品牌、可跨店通兑（PRD §1 首段）。
     *
     * <p>返回 {@code null} 表示该租户内无此 phone。多个匹配在唯一索引下不可能，
     * 若真出现（唯一索引被人移除），{@code LIMIT 1} 会把"数据不一致"
     * 静默变成一个任意选择 —— 故本方法同时断言行数，见实现。
     */
    public UUID findIdByPhone(String tenantId, String phone) {
        requireUuid(tenantId);
        if (phone == null || phone.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "按 phone 反查缺 phone");
        }
        return inTenant(tenantId, () -> {
            List<UUID> ids = jdbc.query(SELECT_KEY_BY_PHONE_SQL,
                    (rs, i) -> rs.getObject("id", UUID.class), phone);
            if (ids.size() > 1) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "同一租户内 phone 命中 " + ids.size() + " 行 —— "
                                + "V7 的 uq_customer_tenant_phone 应保证唯一；"
                                + "该索引被移除或少写了 WHERE phone IS NOT NULL，"
                                + "会让『跨店识别键』退化为任取一个");
            }
            return ids.isEmpty() ? null : ids.get(0);
        });
    }

    /**
     * 由 {@code screening_id} 反查 {@code customer_id}（<b>B2 的唯一取数路径</b>）。
     *
     * <h2>🛑 为什么 B2 必须这样取客户标识</h2>
     * 契约 B2 的 {@code required: [name, gender, age, phone, screening_id]} 里
     * <b>没有</b> {@code customer_id}，而 B2 的出参是 {@code CustomerCreateData.customer_id}。
     * 在"建键 → B1 → B2"这条链上，客户标识只能由 {@code screening_id}
     * 经 {@code screening_record.customer_id} 得到 —— 这是唯一自洽的连接方式。
     * 把它落成显式方法（而不是让服务层自己写 SQL）是为了让这条依赖
     * <b>有一处可读的落点</b>，且与 {@link ScreeningLedger} 的隔离口径一致。
     *
     * <p>⚠️ 这一步<b>不</b>校验筛查结论 —— 结论由服务层经 {@code CustomerGateGuard} 判定
     * （契约 B2「服务端校验其 result=通过」）。反查只回答"这条筛查记录挂在谁身上"。
     */
    public UUID findCustomerIdByScreening(String tenantId, UUID screeningId) {
        requireUuid(tenantId);
        requireUuid(screeningId.toString());
        return inTenant(tenantId, () -> {
            List<UUID> ids = jdbc.query(
                    "SELECT customer_id FROM screening_record WHERE screening_id = ?::uuid",
                    (rs, i) -> rs.getObject("customer_id", UUID.class),
                    screeningId.toString());
            return ids.isEmpty() ? null : ids.get(0);
        });
    }

    /**
     * 取客户当前 14 态（{@code customer_state_transition} 里 {@code is_current = true} 的那行）。
     *
     * <p>🛑 返回 {@code null} 有两种成因，且<b>调用方必须能分辨</b>：
     * <pre>
     *   ① 该客户从未有任何跃迁 —— 建键动作漏写首条跃迁（见 ensureKey 的说明）
     *   ② 该客户在本租户下不存在（RLS 零行）
     * </pre>
     * 两者对门禁的影响相同（{@code CustomerGateGuard} 会把 null 态判为"未越过任何态"），
     * 但对<b>排查</b>完全不同。故本方法只返回 null，由服务层结合
     * {@link #exists} 区分 —— 仓储不替调用方做这个判断。
     *
     * <p>⚠️ {@code uq_cst_current}（V2 的部分唯一索引）保证至多一行；
     * 若查出多行说明索引缺失，此时取任意一行会掩盖"当前态有多个"这件事，
     * 故本方法显式断言。
     */
    public CustomerState findCurrentState(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<String> codes = jdbc.query(
                    "SELECT to_state FROM customer_state_transition"
                            + " WHERE customer_id = ?::uuid AND is_current = true",
                    (rs, i) -> rs.getString("to_state"),
                    customerId.toString());
            if (codes.size() > 1) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "客户 " + customerId + " 有多于一个 is_current 跃迁（" + codes.size() + " 行）—— "
                                + "V2 的 uq_cst_current 部分唯一索引应保证至多一行；"
                                + "『当前态』有多个来源会让每条门禁取到的态不确定");
            }
            return codes.isEmpty() ? null : CustomerState.of(codes.get(0));
        });
    }

    /** 该客户（本租户内）是否存在。 */
    public boolean exists(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM customer WHERE id = ?::uuid)",
                    Boolean.class, customerId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    private static final String SELECT_DETAIL_SQL =
            "SELECT id, name, gender, age, owner_store_id, serving_store_id FROM customer WHERE id = ?::uuid";

    /**
     * 取 B4 出参所需的客户主体字段（<b>不含</b>派生字段与档案）。
     *
     * <p>🛑 本方法<b>返回内部视图 {@link CustomerDetailView}，不做任何裁剪</b> ——
     * 裁剪只有一个落点（{@code CustomerDetailView.toContractData}），
     * 且 403 归因在服务层调用它之前（{@code CustomerFieldVisibility.assertIncludeAllowed}）。
     * 若在这里先裁一刀，就会出现"仓储一处、视图一处"的两份口径。
     *
     * <p>{@link CustomerDetailView} 需要 11 个字段，本方法只填其中 6 个（主体 + 两个门店）；
     * 其余（{@code intake_profile} / {@code screening_result} / {@code band_willingness} /
     * {@code effect_verdict} / {@code as_value}）由服务层从其余三本账本与派生引擎装配 ——
     * 本仓储<b>不</b>跨表 JOIN，理由是那会把"哪些表参与 B4"这件事埋进一条 SQL 里，
     * 而它恰好是域 B 依赖最复杂的一处（4 表 + 2 个派生值）。
     *
     * <p>{@code gender} 以<b>库层字面</b>（{@code 男}/{@code 女}）原样带出，
     * 不在仓储内转成 {@code Gender}：B4 出参的 {@code gender} 是契约原文，
     * 而 {@code Gender} 枚举的存在理由是"它是 AgeGroup 的输入维度"
     * （见该枚举注释），不是出参转换器。
     */
    public CustomerDetailView findDetailView(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<CustomerDetailView> rows = jdbc.query(SELECT_DETAIL_SQL, (rs, i) -> {
                String gender = rs.getString("gender");
                // 🛑 库层出现未登记性别时【原样带出】而不 of() 抛错：
                //    B4 是读路径，且 responses 声明集 = 200/403/404（无 500）。
                //    若在此 of() 抛 5001，会让一条"库里有个历史脏值"的读请求 500 ——
                //    而 B4 的契约没有给这个场景留错误码。校验落点见类注释：
                //    写入路径已经拦过（CustomerUpsertDraft 用 Gender 承载）。
                return new CustomerDetailView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        gender,
                        (Integer) rs.getObject("age"),
                        Map.of(),                       // intake_profile —— 服务层装配（域 B5）
                        null,                           // screening_result —— 服务层装配（B1 最新一条）
                        null,                           // band_willingness —— 服务层装配（B3 最新一份）
                        rs.getObject("owner_store_id", UUID.class),
                        rs.getObject("serving_store_id", UUID.class),
                        null,                           // effect_verdict —— 派生，服务层经派生引擎装配
                        null);                          // as_value      —— 派生，服务层经派生引擎装配
            }, customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ==================================================================
    // 五、缺口与歧义的登记（可断言的事实，供回归用例读取）
    // ==================================================================

    /** 「建键端点缺位」的自描述 —— 供端点自描述与回归用例断言该缺口仍被登记。 */
    public static Map<String, Object> describeKeyCreationGap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("gap", "域 B 六行未暴露『建键』端点");
        m.put("why_required",
                "V5 screening_record.customer_id UUID NOT NULL REFERENCES customer (id) ⇒ "
                        + "提交筛查（B1）之前 customer 行必须已存在；"
                        + "data-dict §2.25 ② 也写了 CREATED ↔ SCREENING（『已建档键、准入未完成』）");
        m.put("b2_has_no_customer_id",
                "契约 B2 required = [name, gender, age, phone, screening_id]，"
                        + "【无 customer_id】⇒ 客户标识只能由 screening_id 反查"
                        + "（CustomerLedger.findCustomerIdByScreening）");
        m.put("disposition",
                "本仓储提供 ensureKey(...) 作为可执行落点（幂等、status=CREATED、"
                        + "owner_store_id 必填、name 取 phone 作 provisional）；"
                        + "上游若另有建键动作（如扫码注册），应改为调用同一落点而非另写 INSERT");
        m.put("not_invented",
                "本类【未】自造一个建键端点挂到 /customers —— 契约 paths 里没有它，"
                        + "擅自新增会让 ContractFreezeGateTest 与三端 UI 的路径集分叉");
        return m;
    }

    /** 「{@code owner_id} 语义歧义」的自描述 —— 供回归用例断言该列未被用于承载归属门店。 */
    public static Map<String, Object> describeOwnerColumnAmbiguity() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ambiguous_column", "customer.owner_id (V1)");
        m.put("v1_column_comment", "门店/客户级 owner, 不可为空 (ADR-12 owner 不可空)");
        m.put("two_meanings",
                "① 归属【门店】= V7 的 owner_store_id（data-dict §2.6『归属/首诊店，锁定档案』）；"
                        + "② 合规责任人 = ADR-12 的『owner 不可空』"
                        + "（compliance/owners.csv 的 subject → 实名，见 quality-gates G-7）");
        m.put("disposition",
                "本仓储【不】给 customer.owner_id 赋门店值 —— 那会让『归属门店』出现两个来源"
                        + "（owner_id 与 owner_store_id），而两者必然分叉；"
                        + "写入路径一律写 owner_store_id");
        m.put("owner_id_nullable",
                "V1 该列可空（无 NOT NULL）⇒ 不写它不会导致插入失败；"
                        + "故本轮处置无阻塞，属【待上游澄清】项");
        return m;
    }

    // ==================================================================
    // 上下文（与域 B 其余三本账本同一套）
    // ==================================================================

    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    static void requireUuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "标识非合法 UUID，拒绝执行客户 SQL"
                            + "（UUID 白名单是 SET LOCAL 拼接前的唯一防护）");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}