package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.config.domain.ConfigChange;

import java.util.List;
import java.util.Optional;

/**
 * 配置变更历史仓库（{@code app_config_history}）。
 *
 * <p><b>写方法刻意不存在</b>：历史行由数据库触发器在 {@code app_config} 写入时生成。
 * 应用层若能"写历史"，它也就能"不写历史"；把写权交给 DB 之后，
 * "没留痕"在这张表上成了结构性不可能（含 DBA 直连 psql 的路径）。
 *
 * <p>所有方法都要求显式传入 {@code tenantId}：一是让"这个查询属于哪个租户"
 * 在调用点可见，二是让测试可以在同一个 JVM 里交替扮演两个租户而互不干扰。
 */
public interface ConfigHistoryRepository {

    /** 某配置项的变更历史，按变更时间倒序（最新在前）。 */
    List<ConfigChange> findByConfigNo(String tenantId, long configNo);

    /** 最近一次变更；无历史返回 empty。 */
    Optional<ConfigChange> findLatest(String tenantId, long configNo);
}