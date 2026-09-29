package com.diaoyuanyun.dy.config.service;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.config.domain.SysConfig;
import com.diaoyuanyun.dy.config.validation.ConfigValidator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置服务内存实现 (骨架)。真实实现以 DB 表为真相源 (ADR-08)。
 *
 * <p>fail-closed: {@link #getRequired(String)} 未命中即抛异常, 绝不返回默认值 (H-14)。
 *
 * <h2>它为什么必须存在，又为什么不能当运行时实现</h2>
 * 它存在的理由是把两条<b>与存储介质无关</b>的语义立成可独立验证的事实：
 * fail-closed 未命中即抛、保存前校验非法组合即拒。这两条在内存里就能证明，
 * 不必先连数据库（见 {@code ConfigServiceFailClosedTest}）。
 *
 * <p>它<b>不得被注入为运行时实现</b>：它没有租户维度。配置一旦只存在进程内存里，
 * 就同时失去三样东西 —— 审计留痕（谁改的）、租户隔离（RLS 无从生效）、
 * 以及与审计链同库的可举证性。运行时必须用
 * {@link com.diaoyuanyun.dy.config.repository.JdbcConfigService}：
 * DB 真相源 + 逐操作 RLS 上下文 + 数据库触发器留痕。
 *
 * <h2>🛑 B-3（2026-09-26）：为什么这里<b>去掉</b>了 {@code @Service}</h2>
 * 本类原先标着 {@code @Service}，与 {@link com.diaoyuanyun.dy.config.repository.JdbcConfigService}
 * <b>同时</b>标 {@code @Service}。这构成一个真实的装配风险，而它此前<b>没有被任何门禁覆盖</b>：
 * <ul>
 *   <li>{@code ConfigService} 接口的生产注入方<b>为零</b>（全班扫描 dy-app / dy-web /
 *       dy-security / dy-audit 均无）；</li>
 *   <li>但"接口零注入方"<b>不等于</b>"类不会被装配" —— 一旦将来有人按类型注入
 *       {@code ConfigService}（这是最自然的用法：接口名就是 {@code ConfigService}），
 *       Spring 会解析到<b>这个内存实现</b>，于是配置变更落进进程内存：
 *       重启即丢、无留痕、无 RLS。</li>
 *   <li>而且{@code JdbcConfigService} <b>不 implements</b> {@code ConfigService}
 *       （它的每个方法都要显式 {@code tenantId}，与接口的"无租户维度"签名不兼容）——
 *       所以按接口注入<b>只能</b>拿到内存实现，拿不到 DB 真相源。
 *       这不是"两个候选 Bean 的择一问题"，是"接口指向了错误的那个"。</li>
 * </ul>
 * 故此处去掉 {@code @Service}：本类<b>退化为一个纯粹被显式 {@code new} 的语义载体</b>
 * （构造仍保留，{@code ConfigServiceFailClosedTest} 因此<b>零改动</b>）。
 * 去掉注解<b>不改变</b>那两条语义断言的价值，只是不再向容器暴露一个
 * "可以被误装配成运行时实现"的 Bean。
 *
 * <p>⚠️ <b>这只是一道"防误装配"的栅栏，不是 B-3 的完整处置</b>。剩余的、已登记的真实缺口是：
 * 应用侧配置的<b>生产读路径</b>目前仍是 4 个 {@code ConfigSeed*ProfileSource}
 * 在启动期正则解析 {@code 02_slots_seed.sql} 的<b>文本</b>（而非读 {@code app_config}）。
 * V14 已把真相源三表落到应用库，故这条替换已<b>具备前提</b>，属下一步的装配收口
 * （见骨架 README「未完成项与 TODO」）。
 */
public class ConfigServiceImpl implements ConfigService {

    private final Map<String, SysConfig> store = new ConcurrentHashMap<>();
    private final ConfigValidator validator;

    public ConfigServiceImpl(ConfigValidator validator) {
        this.validator = validator;
    }

    @Override
    public String getRequired(String key) {
        SysConfig c = store.get(key);
        if (c == null) {
            // fail-closed (H-14): 未命中配置 -> 拒绝, 绝不返回默认值。
            // 语义归属: 配置缺失导致"该项不可见", 与 VISIBILITY_DENIED(2001) 一致;
            // 契约中不存在"配置缺失"专用码位, 不得自造。
            throw new BizException(ErrorCode.VISIBILITY_DENIED, "配置缺失(拒绝默认): " + key);
        }
        return c.getValue();
    }

    @Override
    public Optional<String> getOptional(String key) {
        return Optional.ofNullable(store.get(key)).map(SysConfig::getValue);
    }

    @Override
    public void set(String key, String value) {
        SysConfig candidate = new SysConfig(key, value, null);
        validator.validate(candidate); // 非法组合 -> 抛异常拒绝 (H-13)
        store.put(key, candidate);
    }
}