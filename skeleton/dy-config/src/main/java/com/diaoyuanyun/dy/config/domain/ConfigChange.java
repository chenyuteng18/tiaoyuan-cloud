package com.diaoyuanyun.dy.config.domain;

import java.time.OffsetDateTime;

/**
 * 配置变更历史行 —— who / when / before / after（ADR-08 变更日志要求）。
 *
 * <p>对应 DB 表 {@code app_config_history}。该表是 append-only：
 * 历史行由<b>数据库触发器</b>写入（不是应用层写），且禁止 UPDATE / DELETE，
 * 所以任何写入路径（含 DBA 直连 psql）都会留痕、都无法篡改。
 */
public record ConfigChange(
        long id,
        String tenantId,
        long configNo,
        String configKey,
        String who,
        OffsetDateTime changedAt,
        String beforeValue,
        String afterValue,
        String op,
        long version) {

    /** 值为空（首次写入）与"空字符串值"必须区分，故 before 用 null 表示"没有前值"。 */
    public boolean isFirstWrite() {
        return beforeValue == null;
    }
}