package com.diaoyuanyun.dy.config.cache;

/**
 * 配置缓存键 —— 租户前缀是键的<b>结构组成部分</b>，不是可选项。
 *
 * <h2>它堵的是什么洞</h2>
 * 配置读是高频操作，缓存几乎是必然的。而一旦缓存键只用 {@code config_key}，
 * 就会出现这样一条路径：租户 A 先读 {@code cfg:refund.visibility} → 缓存被填上 A 的值；
 * 租户 B 随后读同一个 key → <b>命中 A 的条目</b>。数据库侧的 RLS 一点问题都没有
 * （B 的 SELECT 确实返回 0 行），但 B 拿到了 A 的配置 —— 隔离在缓存层被绕过。
 *
 * <p>把租户 ID 编进键，使"跨租户命中"变成<b>键不相等</b>这一结构性事实，
 * 而不是依赖每条读路径都记得带上租户判断。
 *
 * <h2>为什么不用字符串拼接</h2>
 * {@code "t1:" + key} 与 {@code "t" + "1:key"} 这类拼法在含分隔符的输入上会
 * 产生歧义（不同租户/键拼出同一个串）。这里用<b>强类型元组</b>，
 * 相等性由 Java 的 record 语义保证，不依赖分隔符不出现在输入里。
 *
 * @param tenantId 租户标识；<b>不可为空</b> —— 空租户的缓存必须无处可存
 * @param configKey 配置键（如 {@code cfg:adherence.pass_threshold}）
 */
public record ConfigCacheKey(String tenantId, String configKey) {

    public ConfigCacheKey {
        if (tenantId == null || tenantId.isBlank()) {
            // fail-closed：没有租户上下文时不得构造缓存键。
            // 否则"无上下文"会退化成一个可被所有租户命中的共享槽位。
            throw new IllegalArgumentException("缓存键必须含 tenant_id 前缀：tenantId 不得为空");
        }
        if (configKey == null || configKey.isBlank()) {
            throw new IllegalArgumentException("缓存键必须含 config_key：configKey 不得为空");
        }
    }

    /** 便于日志与断言的稳定字符串形态（仅展示用，不参与相等性判断）。 */
    public String asString() {
        return tenantId + "|" + configKey;
    }
}