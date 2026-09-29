package com.diaoyuanyun.dy.config.domain;

/**
 * 系统配置实体骨架 (ADR-08)。DB 为配置真相源。
 *
 * <p>tenant_id 为 NULL 表示全局配置; 业务数字必须外置 (代码内零硬编码, X-18)。
 * 规模: 46 条非空配置(#1~#41, #43~#46, #48) + #42 空号保留 (不做复用) + #47 预留缺席 (尚未裁定)。
 */
public class SysConfig {

    private String key;
    private String value;
    private String tenantId; // NULL = 全局
    private String description;
    private long version;

    public SysConfig() {
    }

    public SysConfig(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.version = 1L;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
