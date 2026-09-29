package com.diaoyuanyun.dy.config.repository;

import com.diaoyuanyun.dy.config.domain.ConfigSlot;

import java.util.List;
import java.util.Optional;

/**
 * 配置声明仓库（总部层，读 {@code config_slot}）。
 *
 * <p>只读。声明由迁移脚本（{@code 02_slots_seed.sql}）灌入，运行时不存在
 * "通过接口改声明"这条路 —— 改声明 = 改编号口径 = 必须走迁移与评审。
 */
public interface ConfigSlotRepository {

    /** 全部声明，按编号升序。#42 不在其中（空号）。 */
    List<ConfigSlot> findAll();

    /** 按编号找声明；#42 必然返回 empty。 */
    Optional<ConfigSlot> findByNo(long configNo);

    /** 按键找声明。 */
    Optional<ConfigSlot> findByKey(String configKey);
}