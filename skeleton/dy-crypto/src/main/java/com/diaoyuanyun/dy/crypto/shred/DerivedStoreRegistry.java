package com.diaoyuanyun.dy.crypto.shred;

/**
 * <b>衍生存储清单</b> —— 数据地图的落点，删除 DAG 的顶点集。
 *
 * <h2>为什么需要一份清单，而不是"删完主库就完事"</h2>
 * 架构规格书 §8.4 / 竞析 E.3 / H.2-10：删除 DAG 必须覆盖<b>每一个衍生存储</b>,
 * 且<b>全部确认删除后才销毁源 DEK</b>。
 *
 * <p>顺序不能反。若先销毁 DEK 再去删衍生存储：
 * <ul>
 *   <li>DEK 一销毁，主体数据在<b>所有</b>存储里同时变成不可读 ——
 *       包括那些"本来只是想按 id 清掉"的缓存/索引/数仓；</li>
 *   <li>于是清理任务失去了定位手段（它连"要删哪几行"都读不出来了 ——
 *       行里的主体标识可能本身就在加密字段里）；</li>
 *   <li>结果是"密钥销毁成功，衍生存储残留"—— 对外看起来删除完成了，
 *       实际上数仓/缓存里还有可读副本。<b>这是最坏的一种失败</b>：
 *       合规动作已宣告完成，而数据仍在。</li>
 * </ul>
 * 反过来（先清理衍生存储、再销毁 DEK）没有任何代价：清理期间主库数据仍可读，
 * 清理任务能正确定位；最后销毁 DEK 是"关掉总闸"，让任何遗漏的副本变为不可恢复。
 *
 * <h2>为什么用枚举而不是配置</h2>
 * 枚举让"漏了一个衍生存储"变成<b>编译期/测试期可发现</b>的事：
 * 新增一个存储必须显式登记，且 {@link DeletionDag} 会对每个登记项要求确认。
 * 用配置则相反 —— 漏配一项不会有任何提示，只是回执里少一行。
 *
 * <h2>⚠️ 本清单的当前状态：登记位，非落地</h2>
 * 下列每一项的 {@link DerivedStore#handling} 记录的是<b>本轮的实际处置状态</b>。
 * 绝大多数是 {@link Handling#MANUAL_PENDING}（待人工确认）——
 * 不得把它们当成"已自动处理"。如实标注的理由：删除权的对外承诺是法律义务，
 * 把"登记了"说成"做完了"，风险远大于承认它还差一截。
 */
public final class DerivedStoreRegistry {

    private DerivedStoreRegistry() {
    }

    /** 本轮的处置状态。用于删除 DAG 的逐项门禁。 */
    public enum Handling {
        /** 已由本模块/本仓代码确证处理（有可执行证据）。 */
        CONFIRMED_HANDLED,
        /** <b>待人工或外部系统确认</b> —— 本轮尚未落地，删除 DAG 会据此阻断 DEK 销毁。 */
        MANUAL_PENDING,
        /** 不在本系统控制范围内（第三方处理方），须按 PIPL 委托处理条款单独主张。 */
        EXTERNAL_PROCESSOR
    }

    /**
     * 一个衍生存储。
     *
     * @param id          稳定标识（写进删除回执与审计）
     * @param description 人类可读说明
     * @param handling    本轮处置状态
     * @param why         为什么它在删除范围内 / 为什么它需要单独确认
     */
    public record DerivedStore(String id, String description, Handling handling, String why) {
    }

    /**
     * 删除 DAG 必须覆盖的衍生存储 —— 顺序即"建议的清理顺序"（先叶子后根）。
     *
     * <p>顺序的理由：叶子（缓存/索引/数仓）依赖主库定位，先清叶子可保住定位能力；
     * 主库（{@code primary_db}）放最后，DEK 销毁在其后。
     *
     * <p>登记依据：架构规格书 §8.4 明确点名的七类（主库/备份/日志/缓存/索引/数仓/第三方处理方）。
     */
    public static java.util.List<DerivedStore> all() {
        return java.util.List.of(
                new DerivedStore("cache",
                        "应用缓存 / Redis（本轮未部署 Redis，登记为预留位）",
                        Handling.MANUAL_PENDING,
                        "缓存里的健康指标副本必须随主体清除；漏掉它的表现是"
                                + "『主库已清、界面仍能看到』"),

                new DerivedStore("search_index",
                        "检索索引（若引入）",
                        Handling.MANUAL_PENDING,
                        "索引常含明文副本文档，且不参与主库删除事务"),

                new DerivedStore("analytics_warehouse",
                        "数仓 / 指标聚合层",
                        Handling.MANUAL_PENDING,
                        "聚合数据可能仍可反推个体（小样本尤甚）；ADR-09 已规定"
                                + "指标不得带 tenant_id 标签，但数仓侧需单独确认"),

                new DerivedStore("application_log",
                        "应用日志",
                        Handling.MANUAL_PENDING,
                        "本模块已禁止密文/nonce 进日志，但业务侧日志仍可能带主体标识；"
                                + "日志留存 6~12 个月，必须在删除范围内"),

                new DerivedStore("audit_log",
                        "append-only 审计日志（ADR-09 哈希链）",
                        Handling.MANUAL_PENDING,
                        "⚠️ 冲突点：审计日志按 ADR-09 是 append-only、不可删。"
                                + "处置方向是按『仅保留主体标识的可逆引用』而非明文，"
                                + "并确保审计里不含健康指标原文 —— 该口径需合规方确认"),

                new DerivedStore("backup",
                        "备份介质",
                        Handling.MANUAL_PENDING,
                        "不可变备份无法就地删除 —— 这正是 crypto-shredding 存在的理由"
                                + "（竞析 E.3）。但墓碑必须不受备份回滚影响，见 ShredTombstoneStore"),

                new DerivedStore("primary_db",
                        "主库（业务密文所在）",
                        Handling.CONFIRMED_HANDLED,
                        "本模块的 crypto-shredding 直接作用于它：密文保留但不可恢复。"
                                + "其余衍生存储清完后才执行 DEK 销毁"),

                new DerivedStore("third_party_processor",
                        "第三方处理方（若有委托处理）",
                        Handling.EXTERNAL_PROCESSOR,
                        "不在本系统控制范围内，须按 PIPL 委托处理条款单独主张删除；"
                                + "不得因为『我们这边删了』就认为已满足义务")
        );
    }

    /** 按 id 取（不存在返回 null，由调用方决定是否为配置错误）。 */
    public static DerivedStore of(String id) {
        for (DerivedStore s : all()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** 当前处于"待人工确认"的存储数 —— 删除 DAG 的阻断依据。 */
    public static int pendingCount() {
        return (int) all().stream().filter(s -> s.handling() == Handling.MANUAL_PENDING).count();
    }
}