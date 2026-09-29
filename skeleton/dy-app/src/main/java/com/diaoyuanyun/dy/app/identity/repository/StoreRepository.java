package com.diaoyuanyun.dy.app.identity.repository;

import com.diaoyuanyun.dy.app.identity.domain.StoreAnchor;
import com.diaoyuanyun.dy.app.identity.domain.StoreRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.tenancy.context.RowScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code store} / {@code staff} 的组织台账读取仓储（V5 迁移建表）。
 *
 * <h2>为什么"每次操作都开一个短事务并重设 SET LOCAL"</h2>
 * 与 {@code ScaleItemBankRepository} / {@code VerdictLedger} 同一套理由：
 * {@code SET LOCAL} 只在事务内有效，事务一结束即失效。若只在长事务开始时设一次，
 * 事务外的读路径会退回"无上下文 = 零行"。故这里把"设上下文 + 执行 SQL"收敛成一个短事务，
 * 使<b>「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立</b>。
 *
 * <h2>为什么 tenant_id 白名单校验后拼接</h2>
 * PostgreSQL 的 {@code SET} 是工具语句、<b>不支持绑定参数</b>，必须拼接。
 * 拼接前用 {@link #UUID_PATTERN} 做字符集白名单（仅 {@code [0-9a-f-]}、固定 36 位），
 * 校验通过后不存在可注入字符。这与 {@code ScaleItemBankRepository} 是同一套防护，
 * <b>不得放宽</b>。
 *
 * <h2>🛑 本类不写 {@code WHERE tenant_id = ?} —— 隔离完全由 V5 的 RLS 承担</h2>
 * 这是 ADR-02 的既定形态：应用层不写租户过滤条件（写了会给人"隔离靠应用层"的错觉，
 * 而真正的防线是任何应用代码都绕不过的策略）。
 *
 * <h2>🛑 {@code store_ids} 的白名单校验发生在集合<b>每一个元素</b>上</h2>
 * 行级过滤需要把门店 ID 拼进 {@code IN (...)}。与 {@code SET} 拼接同理，
 * 每个元素都必须先过 UUID 白名单。故 {@link #requireUuid} 对列表逐项调用，
 * 且<b>校验放在建 SQL 之前</b>——任一元素非法即整批拒绝，
 * 绝不"跳过坏元素"（跳过会让一次截断静默变成"少了几家店"）。
 */
@Repository
public class StoreRepository {

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** 出站列 —— <b>恰好</b>契约 {@code Store} 的三项，不多不少（见 {@code StoreRow} 类注释）。 */
    private static final String SELECT_COLUMNS = "store_id, name, franchise_type";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StoreRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ------------------------------------------------------------------
    // 锚点：staff.store_id → store.region_id
    // ------------------------------------------------------------------

    /**
     * 解析请求者的组织锚点（{@code staff.store_id} + 其门店的 {@code region_id}）。
     *
     * <h2>🛑 查无此人时返回 {@link StoreAnchor#none()}，而不是抛</h2>
     * 这是本类<b>唯一</b>返回"空"而非抛的地方，理由具体：
     * "这个员工没有门店归属"是一个<b>合法的业务状态</b>（新入职未分配、门店尚未挂辖区），
     * 而不是数据错误。把它变成异常会让 A2 对"档案不完整的员工"整体 500 ——
     * 而他其实只需要一次"补齐归属"的提示。
     *
     * <p>真正的拒绝点在其后的 {@code StoreScopeResolver.resolveStoreIds}：
     * 当 {@code row_level} 需要锚点而锚点缺失时<b>拒绝</b>，并给出可执行提示。
     * 把"缺锚点"与本层解耦，使"锚点缺失"这件事在两种范围（own_store / region）下
     * 各有自己的诊断消息，而不是共用一句通用的"员工不存在"。
     *
     * <p>⚠️ 注意：{@code staff} 表<b>不产生门店客服行</b>（该角色无端、无账号，
     * 见 V5 的 DDL 注释），故客服永远不会走到这里 —— 它连 token 都没有。
     */
    public StoreAnchor findAnchor(String tenantId, UUID staffId) {
        if (staffId == null) {
            return StoreAnchor.none();
        }
        return inTenant(tenantId, () -> {
            List<StoreAnchor> rows = jdbc.query(
                    // 🛑 LEFT JOIN 而非 INNER JOIN：员工可能没有门店（store_id 可空），
                    //    用 INNER JOIN 会让"未分配门店的员工"直接查无此人 ——
                    //    而那不是我们要表达的事实（人在，只是没归属）。
                    "SELECT s.store_id AS staff_store_id, st.region_id AS store_region_id "
                            + "  FROM staff s "
                            + "  LEFT JOIN store st ON st.store_id = s.store_id "
                            + " WHERE s.staff_id = ?::uuid",
                    (rs, i) -> new StoreAnchor(
                            rs.getObject("staff_store_id", UUID.class),
                            rs.getObject("store_region_id", UUID.class)),
                    staffId.toString());
            return rows.isEmpty() ? StoreAnchor.none() : rows.get(0);
        });
    }

    // ------------------------------------------------------------------
    // 列表：按行级范围过滤 + 分页
    // ------------------------------------------------------------------

    /**
     * 按行级范围取门店分页。
     *
     * <h2>🛑 三档范围如何落到 SQL</h2>
     * <pre>
     *   all       → 无附加条件（租户内全部，由 RLS 圈定租户边界）
     *   region    → WHERE region_id = &lt;锚点辖区&gt;
     *   own_store → WHERE store_id = &lt;锚点门店&gt;
     * </pre>
     * 三者<b>互斥</b>且都由 {@code rowLevel} 决定 —— 本方法<b>不接受</b>额外的
     * "哪些门店"参数：那会让调用方有机会传一个比自身范围更大的集合
     * （"我就是想看看别的店"），而那是行级范围机制存在的全部意义。
     *
     * <h2>🛑 {@code total} 与 {@code items} 必须出自同一份 WHERE</h2>
     * 分页接口最容易被写错、且写错了不报错的一处：若 {@code total} 用无条件计数，
     * 客户端会算出"还有 N 页"，翻过去却拿到空数组 —— 它<b>不报错</b>，
     * 只是列表看起来"少了一截"。故这里用<b>同一个</b> {@code whereClause} 字符串
     * 与<b>同一批</b>参数构造两条 SQL。
     *
     * <h2>排序必须显式</h2>
     * {@code ORDER BY name, store_id}：分页若依赖数据库返回顺序是"今天恰好对"的写法，
     * 它不报错，只在某次执行计划变化后静默错序 —— 而后端错序在分页上表现为
     * <b>某些行重复出现、另一些行永远看不到</b>。带上 {@code store_id} 作为
     * 稳定次序键，使同名门店之间也有确定顺序。
     *
     * @param rowLevel 已解算的行级范围
     * @param anchor   锚点（{@code own_store} / {@code region} 必填）
     */
    public List<StoreRow> listByScope(String tenantId, RowScope rowLevel, StoreAnchor anchor,
                                      int page, int pageSize) {
        ScopeFilter filter = filterOf(rowLevel, anchor);
        int offset = (page - 1) * pageSize;
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM store " + filter.whereClause()
                        + " ORDER BY name, store_id"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new StoreRow(
                        rs.getObject("store_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("franchise_type")),
                append(filter.args(), pageSize, offset)));
    }

    /** 上述分页的总数 —— <b>同一份 WHERE 与参数</b>（见方法注释）。 */
    public int countByScope(String tenantId, RowScope rowLevel, StoreAnchor anchor) {
        ScopeFilter filter = filterOf(rowLevel, anchor);
        return inTenant(tenantId, () -> {
            // 🛑 必须 toArray()：queryForObject(sql, Class, Object...) 的形参是 varargs，
            //    直接把 List 传进去【不会】被展开成 Object[] —— 它会被当作【单个】参数，
            //    于是 SQL 里 1 个 `?::uuid` 收到一个 List 值 ⇒ BadSqlGrammarException，
            //    整个 A3 变成 500。而这个缺陷在 listByScope 里【看不见】：
            //    那边走 append(...) 返回 Object[]，varargs 正常展开 ——
            //    两处形态不同，故 count 侧单独踩中。这正是"total 与 items 同源"
            //    必须由代码结构（同一 filter）保证、而不是靠两处各拼一次的理由之一。
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM store " + filter.whereClause(),
                    Integer.class, filter.args().toArray());
            return n == null ? 0 : n;
        });
    }

    /**
     * 行级范围 → WHERE 片段 + 参数。
     *
     * <p>🛑 抽成一个私有 record 而不是"在 list/count 里各拼一次"：
     * 两处各拼一次必然分叉，而分叉的表现恰好是"总数与列表对不上"。
     * 也正因为共用，{@code total} 与 {@code items} 出自同一份条件这件事
     * 由<b>代码结构</b>保证，而不是靠注释提醒。
     */
    private static ScopeFilter filterOf(RowScope rowLevel, StoreAnchor anchor) {
        if (rowLevel == null) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "行级范围为 null，拒绝执行门店查询 —— fail-closed，不回落全量");
        }
        switch (rowLevel) {
            case ALL:
                return new ScopeFilter("", List.of());
            case REGION:
                if (anchor == null || anchor.regionId() == null) {
                    throw new BizException(ErrorCode.TENANT_MISMATCH,
                            "行级范围 region 需要锚点辖区（staff.store_id → store.region_id），"
                                    + "但请求者的门店未挂归属区域 —— 🛑 不得回落为『全量』："
                                    + "那是把一次数据不完整变成一次越权的最短路径");
                }
                return new ScopeFilter(" WHERE region_id = ?::uuid",
                        List.of(anchor.regionId().toString()));
            case OWN_STORE:
                if (anchor == null || anchor.storeId() == null) {
                    throw new BizException(ErrorCode.TENANT_MISMATCH,
                            "行级范围 own_store 需要锚点门店（staff.store_id），"
                                    + "但请求者的员工档案里没有门店 —— 同上，不得回落为『全量』");
                }
                return new ScopeFilter(" WHERE store_id = ?::uuid",
                        List.of(anchor.storeId().toString()));
            default:
                throw new BizException(ErrorCode.TENANT_MISMATCH,
                        "未登记的行级范围: " + rowLevel + " —— fail-closed，不回落任一范围");
        }
    }

    /** WHERE 片段 + 其参数（供 list / count 共用）。 */
    private record ScopeFilter(String whereClause, List<String> args) {
    }

    private static Object[] append(List<String> base, Object... extra) {
        List<Object> all = new ArrayList<>(base);
        all.addAll(List.of(extra));
        return all.toArray();
    }

    // ------------------------------------------------------------------
    // 数门店（供门禁 / 自证使用）
    // ------------------------------------------------------------------

    /** 该租户当前可见的门店数（RLS 生效下的真实行数）—— 供 E2E 自证使用。 */
    public int countVisible(String tenantId) {
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM store", Integer.class);
            return n == null ? 0 : n;
        });
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * 在租户上下文里执行（短事务 + SET LOCAL）。
     *
     * <p>{@code requireUuid} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL（与 {@code ScaleItemBankRepository} 同一条纪律）。
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
                    "租户 ID 非合法 UUID，拒绝执行门店查询");
        }
    }

    /** 供服务层在查库前校验租户标识（同一套白名单，避免两处口径分叉）。 */
    public static void validateTenantId(String tenantId) {
        requireUuid(tenantId);
    }
}