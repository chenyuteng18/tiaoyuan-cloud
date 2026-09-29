package com.diaoyuanyun.dy.app.scale.repository;

import com.diaoyuanyun.dy.app.scale.domain.ScaleItemRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code scale_item_bank} 的 JDBC 仓储（V4 迁移建表）。
 *
 * <h2>为什么"每次操作都开一个短事务并重设 SET LOCAL"</h2>
 * 与 {@code JdbcConfigSupport} 同一套理由：{@code SET LOCAL} 只在事务内有效，
 * 事务一结束即失效。若只在长事务开始时设一次，事务外的读路径会退回"无上下文 = 零行"。
 * 故这里把"设上下文 + 执行 SQL"收敛成一个短事务，使
 * <b>「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立</b>，
 * 而不是只在被 {@code @RlsScoped} 标注的方法上成立。
 *
 * <h2>为什么 tenant_id 白名单校验后拼接</h2>
 * PostgreSQL 的 {@code SET} 是工具语句、<b>不支持绑定参数</b>，必须拼接。
 * 拼接前用 {@link #UUID_PATTERN} 做字符集白名单（仅 {@code [0-9a-f-]}、固定 36 位），
 * 校验通过后不存在可注入字符。这与 {@code RlsSessionAspect} / {@code JdbcConfigSupport}
 * 是同一套防护，<b>不得放宽</b>。
 *
 * <h2>V4 的 RLS 是本仓储的前提</h2>
 * 本类<b>不</b>在 SQL 里写 {@code WHERE tenant_id = ?} —— 隔离完全由 V4 的
 * {@code FORCE ROW LEVEL SECURITY} 策略承担。这是 ADR-02 的既定形态：
 * 应用层不写租户过滤条件（写了会给人"隔离靠应用层"的错觉，
 * 而真正的防线是任何应用代码都绕不过的策略）。
 * 由此得到一个可断言的性质：若策略失效，跨租户行会立刻可见 ——
 * 这由 {@code RlsScaleItemBankIsolationTest} 的真库断言守着。
 */
@Repository
public class ScaleItemBankRepository {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ScaleItemBankRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ------------------------------------------------------------------
    // 写：导入一行题目
    // ------------------------------------------------------------------

    /** 单行 INSERT 的 SQL —— 单条与批量共用，避免两份字段清单各自漂移。 */
    private static final String INSERT_SQL =
            "INSERT INTO scale_item_bank ("
                    + " item_id, tenant_id, age_group, dimension, item_no,"
                    + " item_text, anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                    + " item_direction, version, reviewer_id, reviewed_at, created_by) "
                    + "VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * 插入一道题（单行入口）。
     *
     * <p>🛑 冲突即抛，<b>不</b>做 upsert：字典 §2.24 / PRD P0-20 明定
     * 「题目文本<b>版本化、不可覆盖</b>（改题生成新 version）」。
     * 若这里写成 {@code ON CONFLICT DO UPDATE}，改题就会覆盖旧版本，
     * 使"复评调取同源题组"失去可比性 —— 而且旧版本一旦被覆盖就再也回不来了。
     * 故唯一键冲突表现为 23505 → 由服务层转成明确的业务错误，提示调用方<b>改用新 version</b>。
     */
    public void insert(String tenantId, ScaleItemRow draft) {
        inTenant(tenantId, () -> jdbc.update(INSERT_SQL, args(draft)));
    }

    /**
     * 整批插入（<b>单事务</b>：任一行冲突则全部回滚，不留"半成功"题组）。
     *
     * <h2>为什么必须单事务，而不是"服务层 for 循环逐条 insert"</h2>
     * 逐条 insert 各自开事务（{@link #inTenant} 每次新建一个短事务），
     * 于是第 200 行撞唯一键时，前 199 行<b>已经提交</b>。
     * 那个题组从此处于"有 199 题、组卷必失败、且失败原因说不清"的中间态 ——
     * 而且它<b>不会报错</b>，只会在某个客户的组卷请求上以"题组不完整"暴露。
     * 故整批必须在同一个事务里：要么全进，要么全不进。
     *
     * <p>本方法<b>不</b>捕获 {@code DuplicateKeyException} —— 让它冒泡到服务层，
     * 由服务层翻译成"改题请用新 version"的可执行提示（仓储层不该知道业务话术）。
     *
     * @return 实际插入行数（= {@code rows.size()}；异常路径不返回）
     */
    public int insertAll(String tenantId, List<ScaleItemRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return inTenant(tenantId, () -> {
            int n = 0;
            for (ScaleItemRow row : rows) {
                n += jdbc.update(INSERT_SQL, args(row));
            }
            return n;
        });
    }

    /** 单行 INSERT 的绑定参数（与 {@link #INSERT_SQL} 的占位符一一对应）。 */
    private static Object[] args(ScaleItemRow row) {
        return new Object[]{
                row.itemId().toString(),
                row.ageGroup(),
                row.dimension(),
                row.itemNo(),
                row.itemText(),
                row.anchor0(),
                row.anchor1(),
                row.anchor2(),
                row.anchor3(),
                row.anchor4(),
                row.itemDirection(),
                row.version(),
                row.reviewerId(),
                Timestamp.from(row.reviewedAt()),
                row.createdBy()};
    }

    // ------------------------------------------------------------------
    // 读：按题组取题（组卷路径）
    // ------------------------------------------------------------------

    /**
     * 取某年龄组 + 某版本下的全部题目，按 (维度, 题序) 稳定排序。
     *
     * <p>排序<b>必须</b>显式给出：组卷要按题序生成，依赖数据库返回顺序是
     * "今天恰好对"的写法 —— 它不报错，只在某次执行计划变化后静默错序。
     */
    public List<ScaleItemRow> findByPaperKey(String tenantId, String ageGroup, String version) {
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT item_id, age_group, dimension, item_no, item_text,"
                        + " anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                        + " item_direction, version, reviewer_id, reviewed_at, created_by "
                        + "  FROM scale_item_bank "
                        + " WHERE age_group = ? AND version = ? "
                        + " ORDER BY dimension, item_no",
                (rs, i) -> new ScaleItemRow(
                        rs.getObject("item_id", UUID.class),
                        rs.getString("age_group"),
                        rs.getString("dimension"),
                        rs.getInt("item_no"),
                        rs.getString("item_text"),
                        rs.getString("anchor_0"),
                        rs.getString("anchor_1"),
                        rs.getString("anchor_2"),
                        rs.getString("anchor_3"),
                        rs.getString("anchor_4"),
                        rs.getString("item_direction"),
                        rs.getString("version"),
                        rs.getString("reviewer_id"),
                        rs.getTimestamp("reviewed_at").toInstant(),
                        rs.getString("created_by")),
                ageGroup, version));
    }

    /** 该租户当前可见的题数（RLS 生效下的真实行数）—— 供导入自证使用。 */
    public int countVisible(String tenantId) {
        return inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM scale_item_bank", Integer.class));
    }

    // ------------------------------------------------------------------
    // 读：C1 题库拉取（按分龄组 + 可选维度 + 可选版本）
    // ------------------------------------------------------------------

    private static final String SELECT_ITEMS_COLUMNS =
            "item_id, age_group, dimension, item_no, item_text,"
                    + " anchor_0, anchor_1, anchor_2, anchor_3, anchor_4,"
                    + " item_direction, version, reviewer_id, reviewed_at, created_by";

    /**
     * C1 题库拉取 —— 按分龄组（必填）+ 维度（可选）+ 版本（可选）取题。
     *
     * <p>契约 C1 的 {@code age_group} 是 {@code required}，{@code dimension} 与
     * {@code version} 均为可选。故 SQL 的动态条件只拼"存在即过滤"的三段，
     * 参数序与占位符一一对应。🛑 排序<b>必须</b>显式（dimension, item_no），
     * 否则"按题序出题"依赖数据库返回顺序 —— 那是不报错但会静默错序的写法。
     *
     * <p>🛑 入参已由服务层用 {@link com.diaoyuanyun.dy.app.scale.domain.ScaleDomain.AgeGroup}
     * / {@code Dimension} 解析为合法枚举字面，故本方法<b>不再</b>做白名单拼接校验
     * （它们经 {@code ?} 绑定，不存在注入面）；但"维度/版本是否传入"决定 SQL 骨架，
     * 所以骨架本身是<b>静态的四种组合之一</b>，无反射拼接。
     */
    public List<ScaleItemRow> findByQuery(String tenantId, String ageGroup,
                                          String dimension, String version) {
        requireUuid(tenantId);
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_ITEMS_COLUMNS)
                .append(" FROM scale_item_bank WHERE age_group = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(ageGroup);
        if (dimension != null) {
            sql.append(" AND dimension = ?");
            args.add(dimension);
        }
        if (version != null) {
            sql.append(" AND version = ?");
            args.add(version);
        }
        sql.append(" ORDER BY dimension, item_no");
        return inTenant(tenantId, () -> jdbc.query(sql.toString(),
                (rs, i) -> new ScaleItemRow(
                        rs.getObject("item_id", UUID.class),
                        rs.getString("age_group"),
                        rs.getString("dimension"),
                        rs.getInt("item_no"),
                        rs.getString("item_text"),
                        rs.getString("anchor_0"),
                        rs.getString("anchor_1"),
                        rs.getString("anchor_2"),
                        rs.getString("anchor_3"),
                        rs.getString("anchor_4"),
                        rs.getString("item_direction"),
                        rs.getString("version"),
                        rs.getString("reviewer_id"),
                        rs.getTimestamp("reviewed_at").toInstant(),
                        rs.getString("created_by")),
                args.toArray()));
    }

    /** 某题组在库中的题数（组卷前的前提校验）。 */
    public int countByPaperKey(String tenantId, String ageGroup, String version) {
        return inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM scale_item_bank WHERE age_group = ? AND version = ?",
                Integer.class, ageGroup, version));
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * 在租户上下文里执行（短事务 + SET LOCAL）。
     *
     * <p>{@code requireUuid} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL。
     */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        requireUuid(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }

    static void requireUuid(String tenantId) {
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            // 语义归属：租户标识非法 = 租户不匹配（2003/403），不是 500
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "租户 ID 非合法 UUID，拒绝执行题库 SQL");
        }
    }

    /** 供服务层在写库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }

    /** 时间戳工具（读路径用）。 */
    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}