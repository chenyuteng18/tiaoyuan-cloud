package com.diaoyuanyun.dy.app.config;

import com.diaoyuanyun.dy.app.identity.domain.BandVisibilityMatrix;
import com.diaoyuanyun.dy.app.identity.domain.BandVisibilitySource;
import com.diaoyuanyun.dy.app.identity.repository.StoreRepository;
import com.diaoyuanyun.dy.app.identity.service.AuthMeService;
import com.diaoyuanyun.dy.app.refund.domain.RefundVisibilityMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 身份/组织域装配（S2-9）—— 契约域 A 的 A2 / A3 两个端点。
 *
 * <h2>🛑 为什么矩阵在这里"再装配一次"，而不是复用退款域那个 Bean</h2>
 * 本类的 {@link BandVisibilityMatrix} 与退款域的 {@link RefundVisibilityMatrix}
 * 是<b>两份独立口径</b>：{@code #43} 管手环数据（角色 × 字段组四档），
 * {@code #40} 管退款（角色级二分）。PRD / freeze §3.3 C-2 逐字：
 * 「#40 管退款、#43 管手环数据，<b>独立配置、不得合并</b>」。
 *
 * <p>故存在两个 <em>矩阵</em> Bean 是<b>刻意的</b>，不是重复装配：
 * 它们各自读不同的配置段、各有自己的硬锁、且将来可能独立变更。
 * 合并成一个"可见性矩阵"会立刻制造出"改手环档位顺手改了退款可见性"的路径 ——
 * 而那条路径不会有任何一处报错。
 *
 * <h2>🛑 启动期解析，解析失败即应用起不来</h2>
 * 与 {@code RefundDomainConfig} / {@code DerivedEngineConfig} 同一条纪律，
 * 且本域的后果与退款域同级：A2 是「可见性档位<b>唯一权威下发点</b>」，
 * 三端 UI 全靠它决定渲染分支。带着坏口径跑起来，表现不是"报错"，
 * 而是<b>客户端安静地少渲染一些区块</b>（或反过来，多渲染内部信息）。
 *
 * <h2>Bean 清单</h2>
 * <pre>
 *   BandVisibilityMatrix  ← config #43（由 ConfigSeedBandProfileSource 解析）
 *   AuthMeService         ← A2 编排（消费两份矩阵 + 门店仓储）
 *   StoreRepository       ← @Repository 自动注册（构造取 DataSource）
 * </pre>
 * {@code StoreListService} 由 {@code @Service} 自注册；它只消费
 * {@link StoreRepository} 与 {@code StoreScopeResolver}（纯静态解算），
 * 无需本类显式装配。
 *
 * <h2>⚠️ 为什么日志里不打印档位取值</h2>
 * 与退款域同源：{@code #43} 的四档取值含"客户看不看得到缺口原因 / 派生结果"
 * 这类内部口径。整段进日志会让"日志泄漏内部规则"成为一条难以撤销的事实。
 * 故此处只记来源与<b>不含取值的</b>摘要（五行齐备 = 可解析）。
 */
@Configuration
public class IdentityDomainConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityDomainConfig.class);

    /**
     * 手环可见性矩阵 —— 从 config {@code #43} 归一。
     *
     * <p>🛑 构造期即做完四件事（见 {@code BandVisibilityMatrix.fromConfigJson}）：
     * 五个角色键缺一即抛 / 每个角色的四档缺一即抛 / 含未登记字段组键即抛 /
     * 客户侧 ③④ 硬锁被破即抛。故本 Bean 的存在本身即是
     * "客户看不到缺口原因与派生结果"的证据之一。
     */
    @Bean
    public BandVisibilityMatrix bandVisibilityMatrix(BandVisibilitySource source) {
        BandVisibilityMatrix matrix = source.matrix();
        log.info("手环可见性矩阵已装载: source={} 受众行数={} 客户③④已锁={} 未配置即拒={}",
                source.describeSource(),
                matrix.rows().size(),
                matrix.customerGapReasonAndDerivedLocked(),
                matrix.unconfiguredMeansDeny());
        return matrix;
    }

    /**
     * A2 编排服务 —— 可见性档位的唯一解算点。
     *
     * <h2>🛑 它消费的<b>两份</b>矩阵为什么都必须注入</h2>
     * <pre>
     *   BandVisibilityMatrix    → band_visibility 的四档（config #43）
     *   RefundVisibilityMatrix  → refund_visibility（config #40）
     * </pre>
     * A2 的响应体同时含这两项，故它是<b>两份口径唯一的汇合点</b>。
     * 若服务层自己按角色写一个 {@code switch} 来"顺手算一下退款可见性"，
     * 就会得到与 {@code RefundVisibilityMatrix} 分叉的第二份口径 ——
     * 而分叉的那一侧（A2 下发的档位）正是客户端用来决定
     * "渲染不渲染退款区块"的依据。故这里把矩阵注入，让解算必须经过它。
     */
    @Bean
    public AuthMeService authMeService(BandVisibilityMatrix bandVisibilityMatrix,
                                       RefundVisibilityMatrix refundVisibilityMatrix,
                                       StoreRepository storeRepository) {
        return new AuthMeService(bandVisibilityMatrix, refundVisibilityMatrix, storeRepository);
    }
}