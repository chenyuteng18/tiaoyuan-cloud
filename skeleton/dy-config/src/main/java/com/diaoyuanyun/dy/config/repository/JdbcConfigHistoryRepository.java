package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.config.domain.ConfigChange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code app_config_history} 的 JDBC 实现（只读，RLS 生效）。
 *
 * <p>读历史必须带租户上下文：历史表与生效值表用【同一个】RLS 策略，
 * 所以"租户 A 看不到租户 B 的变更历史"由数据库保证，不需要在 SQL 里加 WHERE。
 * 这正是配置表 RLS 的意义 —— 变更历史本身就是审计证据。
 */
@Repository
public class JdbcConfigHistoryRepository implements ConfigHistoryRepository {

    private final JdbcConfigSupport support;

    public JdbcConfigHistoryRepository(DataSource dataSource) {
        this.support = new JdbcConfigSupport(dataSource);
    }

    private static final String SELECT_COLUMNS =
            "SELECT id, tenant_id, config_no, config_key, who, changed_at, "
                    + "before_value, after_value, op, version FROM app_config_history ";

    @Override
    public List<ConfigChange> findByConfigNo(String tenantId, long configNo) {
        return support.inTenant(tenantId, () -> {
            JdbcTemplate jdbc = support.jdbc();
            return jdbc.query(SELECT_COLUMNS + "WHERE config_no = ? ORDER BY changed_at DESC, id DESC",
                    (rs, i) -> map(rs), configNo);
        });
    }

    @Override
    public Optional<ConfigChange> findLatest(String tenantId, long configNo) {
        List<ConfigChange> rows = findOne(tenantId, configNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<ConfigChange> findOne(String tenantId, long configNo) {
        return support.inTenant(tenantId, () -> {
            JdbcTemplate jdbc = support.jdbc();
            return jdbc.query(SELECT_COLUMNS + "WHERE config_no = ? ORDER BY changed_at DESC, id DESC LIMIT 1",
                    (rs, i) -> map(rs), configNo);
        });
    }

    private static ConfigChange map(ResultSet rs) throws SQLException {
        return new ConfigChange(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getLong("config_no"),
                rs.getString("config_key"),
                rs.getString("who"),
                rs.getObject("changed_at", OffsetDateTime.class),
                rs.getString("before_value"),
                rs.getString("after_value"),
                rs.getString("op"),
                rs.getLong("version"));
    }
}