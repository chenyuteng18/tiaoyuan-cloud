package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.config.cache.ConfigCache;
import com.diaoyuanyun.dy.config.domain.ConfigChange;
import com.diaoyuanyun.dy.config.domain.ConfigSlot;
import com.diaoyuanyun.dy.config.validation.ConfigValidator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 配置服务的<b>运行时实现</b>：DB 表为唯一真相源（ADR-08）。
 *
 * <h2>读路径不经过任何外部配置中心（可在测试中断言）</h2>
 * 本类的全部依赖注入项只有三个：{@link ConfigSlotRepository}、
 * {@link ConfigHistoryRepository}、{@link ConfigValidator}（+ 缓存）。
 * 没有任何 HTTP 客户端、没有 {@code @RefreshScope}、没有
 * Apollo / Nacos / Consul / Spring Cloud Config 的客户端。
 * 断言方式见 {@codeConfigTruthSourceIT#no_external_config_center_is_on_the_read_path}：
 * 它反射检查本类的字段类型是否落在外部配置中心包前缀内 ——
 * 这不是断言"我写的常量"，而是断言"这条路径上不存在那个东西"。
 *
 * <h2>fail-closed 的三个具体形态</h2>
 * <ol>
 *   <li>租户没有该行 → 抛（不返回默认值）；</li>
 *   <li>键在声明表里不存在 → 抛（说明调用方引用了不存在的配置项）；</li>
 *   <li>租户标识非法 UUID → 抛 2003（绝不拼进 SQL）。</li>
 * </ol>
 * 三者都抛 {@link BizException}，语义归属 VISIBILITY_DENIED(2001)（与内存实现一致，
 * 契约中无"配置缺失"专用码位，不得自造）。
 *
 * <h2>缓存键含 tenant_id</h2>
 * 见 {@link com.diaoyuanyun.dy.config.cache.ConfigCacheKey}：缓存与 RLS 上下文
 * 都取自同一个入参，两者不可能不一致。
 */
@Service
public class JdbcConfigService {

    private final ConfigSlotRepository slotRepository;
    private final ConfigHistoryRepository historyRepository;
    private final ConfigValidator validator;
    private final ConfigCache cache;
    private final JdbcConfigSupport support;

    public JdbcConfigService(ConfigSlotRepository slotRepository,
                             ConfigHistoryRepository historyRepository,
                             ConfigValidator validator,
                             javax.sql.DataSource dataSource) {
        this.slotRepository = slotRepository;
        this.historyRepository = historyRepository;
        this.validator = validator;
        this.support = new JdbcConfigSupport(dataSource);
        this.cache = new ConfigCache();
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读取必填配置；未命中即抛（fail-closed）。
     *
     * <p>读顺序：缓存（键含 tenant_id）→ DB（RLS 生效）。缓存未命中时回填，
     * 回填用的租户标识与查询用的租户标识是<b>同一个变量</b>，故不存在
     * "用 A 的租户查、按 B 的租户缓存"这类错位。
     */
    public String getRequired(String tenantId, String configKey) {
        return getOptional(tenantId, configKey)
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        "配置缺失(拒绝默认): " + configKey));
    }

    public Optional<String> getOptional(String tenantId, String configKey) {
        Optional<String> cached = cache.get(tenantId, configKey);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<ConfigSlot> slot = slotRepository.findByKey(configKey);
        if (slot.isEmpty()) {
            // 键根本不在声明表里 —— 这是调用方引用了不存在的配置项，必须让它炸出来，
            // 不能默默返回 empty（否则 getRequired 的 fail-closed 会被"键写错了"绕过）。
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "配置项未在声明表中登记: " + configKey);
        }

        Optional<String> value = support.inTenant(tenantId, () -> support.jdbc().query(
                "SELECT value FROM app_config WHERE config_no = ?",
                (rs, i) -> rs.getString("value"),
                slot.get().getConfigNo()).stream().findFirst());

        value.ifPresent(v -> cache.put(tenantId, configKey, v));
        return value;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 保存配置：先校验（服务层），再写（DB 层还会用触发器复核同一套规则）。
     *
     * <p>写前失效该租户该键的缓存 —— 否则改完还在读旧值。
     */
    public void set(String tenantId, String configKey, String value, String actor) {
        ConfigSlot slot = requireSlot(configKey);
        validator.validateValue(slot, value);

        support.inTenant(tenantId, actor, "UPSERT", () -> support.jdbc().update(
                "INSERT INTO app_config (tenant_id, config_no, value, version, updated_by) "
                        + "VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 1, ?) "
                        + "ON CONFLICT (tenant_id, config_no) DO UPDATE "
                        + "  SET value = EXCLUDED.value, "
                        + "      version = app_config.version + 1, "
                        + "      updated_by = EXCLUDED.updated_by, "
                        + "      updated_at = now()",
                slot.getConfigNo(), value, actor == null ? "unknown" : actor));

        cache.invalidate(tenantId, configKey);
    }

    /**
     * 回滚到该配置项上一次变更之前的值。
     *
     * <p>回滚 = "把旧值再写一次"，因此：
     * <ul>
     *   <li>值确实恢复（DB 里有测试证明）；</li>
     *   <li>回滚<b>本身也留下一条历史行</b>（op=ROLLBACK），历史不被抹平 ——
     *       这才符合"配置是审计证据的载体"：不能靠回滚把错误痕迹洗掉。</li>
     * </ul>
     */
    public void rollbackToPrevious(String tenantId, String configKey, String actor) {
        ConfigSlot slot = requireSlot(configKey);

        List<ConfigChange> changes = historyRepository.findByConfigNo(tenantId, slot.getConfigNo());
        // 最近一条是当前状态，它的 before_value 才是"上一版"。
        // 若最近一条本身是首写（before 为 null），则没有可回滚的前值。
        if (changes.isEmpty() || changes.get(0).isFirstWrite()) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "配置 " + configKey + " 没有可回滚的前值（history 之首为首次写入）");
        }

        String target = changes.get(0).beforeValue();

        // 回滚的目标值也要过校验：历史里可能存着"按今天口径不合法"的旧值
        // （口径收紧过）。此时回滚必须被拒，而不是把非法值写回去。
        validator.validateValue(slot, target);

        support.inTenant(tenantId, actor, "ROLLBACK", () -> support.jdbc().update(
                "UPDATE app_config "
                        + "   SET value = ?, version = version + 1, updated_by = ?, updated_at = now() "
                        + " WHERE config_no = ?",
                target, actor == null ? "unknown" : actor, slot.getConfigNo()));

        cache.invalidate(tenantId, configKey);
    }

    // ------------------------------------------------------------------
    // 历史
    // ------------------------------------------------------------------

    public List<ConfigChange> history(String tenantId, String configKey) {
        return historyRepository.findByConfigNo(tenantId, requireSlot(configKey).getConfigNo());
    }

    /** 当前生效值所在的那一行（含 version），供测试断言"回滚后 version 继续增长"。 */
    public Optional<StoredValue> stored(String tenantId, String configKey) {
        ConfigSlot slot = requireSlot(configKey);
        return support.inTenant(tenantId, () -> support.jdbc().query(
                "SELECT value, version, updated_by FROM app_config WHERE config_no = ?",
                (rs, i) -> new StoredValue(rs.getString("value"), rs.getLong("version"),
                        rs.getString("updated_by")),
                slot.getConfigNo()).stream().findFirst());
    }

    /**
     * 该租户在生效值表里的行数 —— 供测试断言"46 行且 #42 / #47 不在其中"。
     *
     * <p>这个数字<b>由 RLS 决定</b>（没有 WHERE tenant_id，隔离来自策略），
     * 所以它同时是"该租户读得到自己多少行"的证据。
     */
    public int storedRowCount(String tenantId) {
        return support.inTenant(tenantId, () -> support.jdbc().queryForObject(
                "SELECT count(*) FROM app_config", Integer.class));
    }

    /** 声明表中的全部条目（供测试断言"46 条且 #42 / #47 不在其中"）。 */
    public List<ConfigSlot> slots() {
        return slotRepository.findAll();
    }

    /** 声明表的最近一次写入人（供历史断言取 who）。 */
    public Optional<ConfigChange> latestChange(String tenantId, String configKey) {
        return historyRepository.findLatest(tenantId, requireSlot(configKey).getConfigNo());
    }

    private ConfigSlot requireSlot(String configKey) {
        return slotRepository.findByKey(configKey)
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        "配置项未在声明表中登记: " + configKey));
    }

    /** 供测试断言的生效值快照。 */
    public record StoredValue(String value, long version, String updatedBy) {
    }
}