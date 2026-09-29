package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRevisionRow;
import com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 建档扩展档案的 JDBC 仓储 —— {@code intake_profile}（V5 §2.23 <b>当前投影</b>）
 * + {@code intake_profile_revision}（V7 <b>权威修订历史</b>）的<b>同一事务写入器</b>。
 *
 * <h2>🛑 一、为什么"投影 + 历史"必须由本类<b>一个方法</b>写入</h2>
 * 契约 B6 逐字：「补充 + 修订（append-only 留痕，<b>不可覆盖</b>）」。
 * 而 V5 的 {@code intake_profile} 带 {@code UNIQUE (tenant_id, customer_id)}
 * ⇒ 物理上只可能有一行 ⇒ "留痕"无处承载。V7 补了 {@code intake_profile_revision} 账本。
 * <pre>
 *   intake_profile            = 当前投影（一客户一行，B5 读它、B6 也更新它）
 *   intake_profile_revision   = 权威历史（append-only，永不覆盖）
 * </pre>
 * 🛑 两者<b>必须同一事务</b>写入。分两次事务的后果很具体：
 * <pre>
 *   投影写成功、历史写失败 → 库里"当前档案"变了，而"什么时候变的、被谁改的"没有记录
 *                          → 跨店争议复盘时，他店看到的那一版无从溯源
 * </pre>
 * 故真正的写入落点是 {@link #appendRevisionAndCoalesce}（一个事务内先 upsert 投影、
 * 再 INSERT 历史），而 {@code IntakeProfileRevisionLedger.insertWithin} 接收
 * <b>本类事务内的 {@code JdbcTemplate}</b>，共用同一连接 —— 这是"同事务"的实现机制，
 * 不是靠"两个方法紧挨着调用"的约定。
 *
 * <h2>🛑 二、投影是<b>可覆盖</b>的，这不是对 B6 的违反</h2>
 * 这是本类最容易被误读的一点，值得写清：
 * <pre>
 *   B6 的"不可覆盖"约束的是【历史】—— 即"曾经是什么样"不得被抹掉。
 *   投影是"现在是什么样"，它<b>本来就应该</b>随最后一次修订而变。
 * </pre>
 * 若把投影也做成 append-only，就必须为每列建"取值历史表"，
 * 而"现在是什么"退化为"在 N 张表里各取最新一条"—— 一次 JOIN 写错就会
 * 让 B5 读出几列来自不同时期的档案（示例：身高来自上周、体重来自上个月），
 * 而这份混合档案<b>看起来完全正常</b>。
 * <p>🔑 故本类的设计是：<b>权威在历史（append-only），易变性被限制在投影这一处</b>。
 * 若上游日后裁定"投影本身也不得就地更新"，只需把 B5 的读路径改为
 * "由历史解算最新快照"，<b>本类的历史写入侧零改动</b>。
 *
 * <h2>🛑 三、与 {@code screening_record} 属【同一次提交链】——本类<b>不</b>自行判定</h2>
 * V5 附注逐字：「🛑 与 {@code screening_record} 属【同一次提交链】；
 * <b>仅 {@code screening_record.result=通过} 才允许提交</b>。
 * 该门禁属<b>服务层顺序约束</b>，库层只保证『一客户一份』与字段域」。
 * 故本类<b>不</b>查筛查结论 —— 由服务层在调用前经
 * {@code CustomerGateGuard} 判定（403 归因也归服务层）。
 *
 * <h2>四、三处"缺失不得补 0 / 不得造值"在 SQL 层的体现</h2>
 * <pre>
 *   ① 三项体测值（height/weight/waist）：null 原样写 null，【不】 coalesce 成 0
 *      （data-dict §2.23：可空 = 未填，缺失不得补 0；record 构造期已拒 &lt;= 0）
 *   ② 多选列：空 Map 写 SQL NULL（用 Json.toJsonOrNull），与"未填"同义 ——
 *      上游对 intake_profile 的 9 个多选列<b>未要求</b>区分"空集"与"未填"
 *      （该区分只在 consent.auth_scope_json 上被 data-dict 明文要求，见那本账本）
 *   ③ updated_at 一律 now()：它与 created_at 不同，是**投影**的翻新锚点，
 *      权威时点在历史的 recorded_at 上
 * </pre>
 */
@Repository
public class IntakeProfileLedger {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IntakeProfileRevisionLedger revisions;

    public IntakeProfileLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.revisions = new IntakeProfileRevisionLedger(dataSource);
    }

    // ==================================================================
    // 一、写：投影 upsert + 历史 INSERT（同一事务）
    // ==================================================================

    private static final String UPSERT_PROJECTION_SQL =
            "INSERT INTO intake_profile ("
                    + " profile_id, tenant_id, customer_id, job_tag, height_cm, weight_kg, waist_cm,"
                    + " bp_sys, bp_dia, hr, glucose, uric_acid, sleep, diet, exercise, thermal,"
                    + " pain_sites, bowel, female_special, meridian_self_report, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?::uuid, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,"
                    + " ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?) "
                    + "ON CONFLICT (tenant_id, customer_id) DO UPDATE SET"
                    + "  job_tag = EXCLUDED.job_tag,"
                    + "  height_cm = EXCLUDED.height_cm,"
                    + "  weight_kg = EXCLUDED.weight_kg,"
                    + "  waist_cm = EXCLUDED.waist_cm,"
                    + "  bp_sys = EXCLUDED.bp_sys,"
                    + "  bp_dia = EXCLUDED.bp_dia,"
                    + "  hr = EXCLUDED.hr,"
                    + "  glucose = EXCLUDED.glucose,"
                    + "  uric_acid = EXCLUDED.uric_acid,"
                    + "  sleep = EXCLUDED.sleep,"
                    + "  diet = EXCLUDED.diet,"
                    + "  exercise = EXCLUDED.exercise,"
                    + "  thermal = EXCLUDED.thermal,"
                    + "  pain_sites = EXCLUDED.pain_sites,"
                    + "  bowel = EXCLUDED.bowel,"
                    + "  female_special = EXCLUDED.female_special,"
                    + "  meridian_self_report = EXCLUDED.meridian_self_report,"
                    + "  updated_at = now()";

    /**
     * <b>B6 的唯一写入落点</b>：在一个事务内 ① upsert 投影 ② 追加历史。
     *
     * <h2>🛑 顺序不可换：先投影、后历史</h2>
     * 先写历史再写投影的话，"历史已记下这次修订"与"投影尚未反映它"之间
     * 存在一个中间态 —— 若此时投影写失败并回滚，历史行也会随之回滚（同事务），
     * 故两者最终一致；但顺序反过来（先历史后投影）在<b>日志与触发器的可观测性</b>上
     * 会让"这次修订有没有生效"产生歧义。先投影后历史 = "先落事实，再落证据"。
     *
     * <h2>🛑 {@code supersedesRevisionId} 的取法</h2>
     * 由调用方（服务层）传入"本次修订取代的那一条"。服务层通过
     * {@link IntakeProfileRevisionLedger#findLatest} 取得它 —— 这是 append-only 的
     * 机械保证：更正不改旧行（本类<b>不提供</b>任何 UPDATE 历史的方法），
     * 而是新行声明"我取代了谁"。
     *
     * <h2>🛑 唯一冲突（并发修订）会抛，且这是正确行为</h2>
     * 两个并发修订可能拿到同一个 {@code revision_no}，此时
     * {@code uq_ipr_revision_no} 会让其中一个失败。静默让两条修订共用一个序号，
     * 会让"改过几次、哪次在前"有一个错误但看起来正常的答案。
     * 服务层应捕获并重试（重新取 {@code nextRevisionNo}），而不是在这里吞掉冲突。
     */
    public void appendRevisionAndCoalesce(String tenantId,
                                         IntakeProfileRow projection,
                                         IntakeProfileRevisionRow revision) {
        requireUuid(tenantId);
        if (projection == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "建档投影为空，拒绝写入");
        }
        if (revision == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "档案修订留痕为空，拒绝写入");
        }
        if (!projection.customerId().equals(revision.customerId())) {
            // 🛑 投影与历史指向不同客户 —— 这会让"某客户的历史"与"他的当前档案"错位，
            //    而错位不会报错（两次写入各自都合法），只会让 B5 读出别人的档案。
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档投影与修订留痕的 customer_id 不一致（投影=" + projection.customerId()
                            + ", 留痕=" + revision.customerId() + "）—— "
                            + "两者必须指向同一客户，否则投影与历史会错位到两个人身上");
        }

        tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            upsertProjectionWithin(jdbc, projection);
            // 共用本事务的 jdbc ⇒ 同一个连接 ⇒ 同一个事务
            revisions.insertWithin(jdbc, tenantId, revision, revision.createdBy());
            return null;
        });
    }

    /** 投影 upsert（<b>使用调用方事务内的 {@code JdbcTemplate}</b>）。 */
    void upsertProjectionWithin(JdbcTemplate jdbc, IntakeProfileRow row) {
        jdbc.update(UPSERT_PROJECTION_SQL,
                row.profileId().toString(),
                row.customerId().toString(),
                Json.toJsonOrNull(row.jobTag()),
                row.heightCm(),
                row.weightKg(),
                row.waistCm(),
                row.bpSys(),
                row.bpDia(),
                row.hr(),
                row.glucose(),
                row.uricAcid(),
                Json.toJsonOrNull(row.sleep()),
                Json.toJsonOrNull(row.diet()),
                Json.toJsonOrNull(row.exercise()),
                Json.toJsonOrNull(row.thermal()),
                Json.toJsonOrNull(row.painSites()),
                Json.toJsonOrNull(row.bowel()),
                Json.toJsonOrNull(row.femaleSpecial()),
                Json.toJsonOrNull(row.meridianSelfReport()),
                row.createdBy());
    }

    // ==================================================================
    // 二、读
    // ==================================================================

    private static final String SELECT_PROJECTION_COLUMNS =
            " profile_id, customer_id, job_tag, height_cm, weight_kg, waist_cm, bp_sys, bp_dia, hr,"
                    + " glucose, uric_acid, sleep, diet, exercise, thermal, pain_sites, bowel,"
                    + " female_special, meridian_self_report, created_by ";

    /**
     * 取建档投影（B5 的唯一取数入口）。
     *
     * <p>返回 {@code null} 表示该客户<b>尚未建档</b>（无投影行）—— 这是 B5 的
     * 一个正常分支（200 + 空 data），<b>不是</b>错误。B5 的 responses 声明集只有 200，
     * 故"没有档案"绝不能被实现成 404（那是契约未声明的码）。
     */
    public IntakeProfileRow findProjection(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            List<IntakeProfileRow> rows = jdbc.query(
                    "SELECT" + SELECT_PROJECTION_COLUMNS + "FROM intake_profile"
                            + " WHERE customer_id = ?::uuid",
                    IntakeProfileLedger::mapProjection,
                    customerId.toString());
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** 该客户是否已有建档投影（B6 的"首次修订 vs 后续修订"分支取数）。 */
    public boolean hasProjection(String tenantId, UUID customerId) {
        requireUuid(tenantId);
        requireUuid(customerId.toString());
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM intake_profile WHERE customer_id = ?::uuid)",
                    Boolean.class, customerId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    // ==================================================================
    // 三、投影 → 修订快照（唯一落点）
    // ==================================================================

    /**
     * 把投影转成修订留痕的 {@code snapshot_json}（<b>唯一落点</b>）。
     *
     * <h2>🛑 为什么由本类（而不是调用方）生成快照</h2>
     * 快照的键集必须与投影的列集<b>严格一一对应</b>。若让调用方自己拼一个 Map，
     * 某次新增列时就会出现"投影写了、快照漏了"—— 而那次漏记<b>不会报错</b>，
     * 只会在某天回溯"当时的档案长什么样"时发现少了一列，
     * 且此时已无法补（历史无法重建）。
     * 把两者放在同一个文件里，是为了让"加列时两处一起改"成为<b>就近</b>的事。
     *
     * <p>键名用<b>库列名</b>（{@code job_tag} / {@code height_cm} / …）：
     * 快照的身份是"档案的字段映射"，与列名同构，便于逐列核对。
     * 空 Map 写成空 Map（不是省略该键）：<b>快照必须完整</b>，
     * "某字段当时未填"本身就是需要被记下的事实。
     */
    public static Map<String, Object> snapshotOf(IntakeProfileRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_tag", row.jobTag());
        m.put("height_cm", row.heightCm());
        m.put("weight_kg", row.weightKg());
        m.put("waist_cm", row.waistCm());
        m.put("bp_sys", row.bpSys());
        m.put("bp_dia", row.bpDia());
        m.put("hr", row.hr());
        m.put("glucose", row.glucose());
        m.put("uric_acid", row.uricAcid());
        m.put("sleep", row.sleep());
        m.put("diet", row.diet());
        m.put("exercise", row.exercise());
        m.put("thermal", row.thermal());
        m.put("pain_sites", row.painSites());
        m.put("bowel", row.bowel());
        m.put("female_special", row.femaleSpecial());
        m.put("meridian_self_report", row.meridianSelfReport());
        return m;
    }

    private static IntakeProfileRow mapProjection(ResultSet rs, int i) throws SQLException {
        return new IntakeProfileRow(
                rs.getObject("profile_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                Json.toMap(rs.getString("job_tag")),
                (BigDecimal) rs.getObject("height_cm"),
                (BigDecimal) rs.getObject("weight_kg"),
                (BigDecimal) rs.getObject("waist_cm"),
                (Integer) rs.getObject("bp_sys"),
                (Integer) rs.getObject("bp_dia"),
                (Integer) rs.getObject("hr"),
                (BigDecimal) rs.getObject("glucose"),
                (BigDecimal) rs.getObject("uric_acid"),
                Json.toMap(rs.getString("sleep")),
                Json.toMap(rs.getString("diet")),
                Json.toMap(rs.getString("exercise")),
                Json.toMap(rs.getString("thermal")),
                Json.toMap(rs.getString("pain_sites")),
                Json.toMap(rs.getString("bowel")),
                Json.toMap(rs.getString("female_special")),
                Json.toMap(rs.getString("meridian_self_report")),
                rs.getString("created_by"));
    }

    // ==================================================================
    // 四、读法自描述（供端点自描述与回归用例断言）
    // ==================================================================

    /** 「投影 vs 权威历史」读法的自描述 —— 使"谁是权威"成为可断言的事实。 */
    public static Map<String, Object> describeProjectionVsHistory() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authoritative", "intake_profile_revision（V7 · append-only · 永不覆盖）");
        m.put("projection", "intake_profile（V5 · 一客户一行 · 随最后一次修订翻新）");
        m.put("written_in_same_tx",
                "IntakeProfileLedger.appendRevisionAndCoalesce 在同一事务内先 upsert 投影、"
                        + "再 INSERT 历史（复用同一连接）；分两次事务会让"
                        + "『当前档案变了但没有变更记录』成为可能");
        m.put("b6_scope",
                "契约 B6『不可覆盖』约束的是【历史】（曾经是什么样不得被抹掉）；"
                        + "投影是【现在是什么样】，本就应随最后一次修订而变");
        m.put("if_upstream_requires_immutable_projection",
                "只需把 B5 读路径改为『由历史解算最新快照』——历史写入侧零改动"
                        + "（这正是把权威放在账本上的价值）");
        m.put("no_revision_type_column",
                "V7 刻意不设『补充/修订』枚举列：「补充」「修订」两个词在上游无取值表、"
                        + "无 CHECK 引用（与 V2 的 trigger_event 同型）⇒ 按纪律不发明枚举；"
                        + "该区分由 IntakeProfileRevisionRow.describeDeltaVersus 从快照比对推导");
        m.put("missing_screening_gate",
                "与 screening_record 的『同一次提交链』门禁（仅 result=通过方可提交）"
                        + "属【服务层顺序约束】（V5 附注明文），本仓储不自行判定");
        return m;
    }

    // ==================================================================
    // 上下文（与域 B 其余账本同一套）
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
                    "标识非合法 UUID，拒绝执行建档档案 SQL"
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
}