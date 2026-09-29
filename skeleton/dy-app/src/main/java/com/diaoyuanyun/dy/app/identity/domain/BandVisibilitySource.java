package com.diaoyuanyun.dy.app.identity.domain;

/**
 * 手环可见性口径来源端口（{@code cfg:band.visibility} / config {@code #43}）。
 *
 * <h2>为什么是一个端口，而不是直接 new 一个解析器</h2>
 * 与 {@code RefundProfileSource} / {@code DerivedProfileSource} 同源：
 * 配置真相源（{@code app_config} / {@code config_slot}）目前只建在 dy-config 的
 * 独立门禁库里，应用库还没有这两张表（README「未完成项」的收尾令）。
 *
 * <p>把"口径从哪来"抽成端口，使两件事可分开演进而互不牵连：
 * <ol>
 *   <li>今天：{@code ConfigSeedBandProfileSource} 解析声明文件的 {@code #43} 行；</li>
 *   <li>收尾令完成后：换一个读 {@code app_config} 的实现即可，
 *       {@link BandVisibilityMatrix} 与 A2 服务层<b>零改动</b>。</li>
 * </ol>
 *
 * <h2>fail-closed</h2>
 * {@link #matrix()} 取不到口径一律抛（口径缺失 = 拒绝启用），
 * <b>绝不</b>返回默认口径 —— 本口径的每一格都直接决定"客户能不能看到自己的数据"
 * 与"内部缺口原因会不会外泄"。
 */
public interface BandVisibilitySource {

    /**
     * 归一化后的手环可见性矩阵。
     *
     * @throws com.diaoyuanyun.dy.common.exception.BizException 口径缺失 / 非法 / 硬锁被破时
     */
    BandVisibilityMatrix matrix();

    /** 来源自描述（进启动日志，供事故复盘时回答"这份口径是从哪来的"）。 */
    String describeSource();
}