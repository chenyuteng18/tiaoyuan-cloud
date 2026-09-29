package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.config.domain.ConfigSlot;
import com.diaoyuanyun.dy.config.domain.ValueType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@code config_slot} 的 JDBC 实现。
 *
 * <p>该表不含 tenant_id、不启用 RLS（对所有租户同一份声明，性质同 tenant /
 * schema_migration 这类框架表），故本实现直接查询、不经租户上下文。
 *
 * <p><b>它不提供任何读"值"的方法</b>：值是 {@code app_config} 的职责。
 * 这条职责分离让"某个调用方顺手从声明表拿默认值"在类型层面不成立 ——
 * 也就是不会出现第二个真相源。
 */
@Repository
public class JdbcConfigSlotRepository implements ConfigSlotRepository {

    private final JdbcTemplate jdbc;

    public JdbcConfigSlotRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private static final String SELECT_COLUMNS =
            "SELECT config_no, config_key, value_type, allowed_values, forbidden_values, "
                    + "prd_item_name, description FROM config_slot ";

    @Override
    public List<ConfigSlot> findAll() {
        return jdbc.query(SELECT_COLUMNS + "ORDER BY config_no", (rs, i) -> map(rs));
    }

    @Override
    public Optional<ConfigSlot> findByNo(long configNo) {
        List<ConfigSlot> rows = jdbc.query(SELECT_COLUMNS + "WHERE config_no = ?", (rs, i) -> map(rs), configNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<ConfigSlot> findByKey(String configKey) {
        List<ConfigSlot> rows = jdbc.query(SELECT_COLUMNS + "WHERE config_key = ?", (rs, i) -> map(rs), configKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static ConfigSlot map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConfigSlot(
                rs.getLong("config_no"),
                rs.getString("config_key"),
                ValueType.valueOf(rs.getString("value_type")),
                toStringArray(rs.getArray("allowed_values")),
                toStringArray(rs.getArray("forbidden_values")),
                rs.getString("prd_item_name"),
                rs.getString("description"));
    }

    private static String[] toStringArray(java.sql.Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return null;
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof String[] arr) {
            return arr;
        }
        if (raw instanceof Object[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = String.valueOf(arr[i]);
            }
            return out;
        }
        return null;
    }
}