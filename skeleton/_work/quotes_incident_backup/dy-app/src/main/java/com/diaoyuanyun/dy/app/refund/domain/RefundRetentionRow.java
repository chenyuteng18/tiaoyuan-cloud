package com.diaoyuanyun.dy.app.refund.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条挽留记录（{@code retention} 的一行）。
 *
 * <h2>🛑 为什么 {@code analysis} / {@code communication} 是 {@code String} 而不是结构化类型</h2>
 * 库里两列都是 {@code JSONB NOT NULL}。把它们建模成 {@code Map<String,Object>} 会引入
 * 一个<b>静默的类型自由度</b>：写进去的键与读出来的键不同时，编译不报错、
 * 运行不报错，只让"五维原因分析"在某个报表里少一维。而"少一维"这件事
 * 在本域的具体后果是：挽留质量评估看不见"客户到底为什么不满意"。
 *
 * <p>故本 record <b>只搬运不解释</b>：JSON 原样文本进出，
 * 结构约束由库层 {@code NOT NULL} 与写入路径的构造点（服务层）承担。
 * 若将来需要强类型，改动点是<b>引入一个 record 并在服务层做双向映射</b>，
 * 而不是让每个读路径各自 {@code cast}。
 *
 * <h2>🛑 {@code createdAt} 在写路径上<b>必须被忽略</b></h2>
 * 库里该列是 {@code DEFAULT now()}。若写入时把自己生成的时刻绑定进 INSERT，
 * 就出现"应用服务器时钟"与"数据库时钟"两个时间源 —— 而挽留时长口径是
 * {@code retention.created_at} 与 {@code refund.recorded_at} 的差。
 * 两个时钟漂移时（容器与库不同机很常见），挽留时长会出现<b>负值</b>，
 * 而报表只会显示"这个店响应得特别快"。
 *
 * <p>故本字段只服务<b>读路径</b>：仓储 INSERT 的列清单里<b>不含</b>
 * {@code created_at}，而 SELECT 里含它。构造它来写入时传入的值不会进库 ——
 * 这一点由 {@code RefundWorkOrderLedgerTest} 的"写入后读回的 createdAt 由库生成"断言守住，
 * 而不是靠本注释。
 *
 * <h2>⚠️ 本表<b>不是</b> append-only</h2>
 * V5 的 {@code retention} 带 {@code updated_at} 列，挽留记录在工单未归档前可补充
 * （客户在挽留过程中改了主意是常态）。这与 {@code refund_statement} 的
 * append-only 纪律<b>相反</b>，故两者分属两个端口的两套方法集 ——
 * 把"可改的挽留"和"不可改的原话"放进同一个接口，
 * 会让下一个需要"修正一条写错的原话"的人顺手用上挽留那条可改路径。
 *
 * @param retentionId   挽留记录主键
 * @param refundId      所属退款工单
 * @param attempts      挽留尝试次数（库 CHECK ≥ 0）
 * @param scriptVersion 话术版本（可空 —— 早期记录可能未登记版本）
 * @param result        挽留结果（{@code 接受继续服务 / 接受但需调整 / 不接受进入退款终止}）
 * @param operatorId    操作人（FK {@code staff}；证据链要求）
 * @param analysisJson  五维原因分析（JSONB 原样文本）
 * @param communicationJson 沟通记录（JSONB 原样文本）
 * @param createdAt     写入时刻（<b>读路径</b>由库回填；写路径忽略本字段）
 * @param createdBy     创建者标识
 */
public record RefundRetentionRow(
        UUID retentionId,
        UUID refundId,
        int attempts,
        String scriptVersion,
        RetentionResult result,
        UUID operatorId,
        String analysisJson,
        String communicationJson,
        Instant createdAt,
        String createdBy) {

    /** 挽留是否成功（客户留在服务关系内）—— 决定审批落点。 */
    public boolean isSuccess() {
        return result != null && result.isSuccess();
    }

    /**
     * 构造一条<b>待写入</b>的挽留记录（{@code createdAt} 置空，交由库层生成）。
     *
     * <p>提供本工厂而不是让调用方 {@code new ...(..., createdAt, ...)}：
     * 写入点若必须显式传 {@code null}，就总有一个人会"顺手填个 now()" ——
     * 而那一刻双时钟的漂移就开始进数据了。工厂把"写路径不带时间"变成默认姿势。
     */
    public static RefundRetentionRow forInsert(UUID retentionId,
                                               UUID refundId,
                                               int attempts,
                                               String scriptVersion,
                                               RetentionResult result,
                                               UUID operatorId,
                                               String analysisJson,
                                               String communicationJson,
                                               String createdBy) {
        return new RefundRetentionRow(retentionId, refundId, attempts, scriptVersion,
                result, operatorId, analysisJson, communicationJson, null, createdBy);
    }
}