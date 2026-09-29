package com.diaoyuanyun.dy.app.derived.domain;

import java.util.List;
import java.util.UUID;

/**
 * 判定链（{@code cycle_assessment} + {@code verdict}）的<b>持久化端口</b>
 * （DIP：服务层依赖本端口，仓储层实现它）。
 *
 * <h2>🛑 本端口只有 INSERT 与 SELECT —— 一个 update 都没有</h2>
 * 与 {@code RefundReceiptPort} 同族（append-only），而<b>比它更强</b>：
 * 退款工单至少还有"结局会演进"这一件事（故 {@code RefundWorkOrderPort} 有一个受控 update），
 * 判定链<b>连这个都没有</b>。理由逐条来自 PRD §C.1.9 硬约束③：
 * <pre>
 *   「版本不可覆盖：题库、方案、判定（verdict.threshold_version）、文书模板
 *     四处只可新增版本，不可原地覆盖」
 * </pre>
 * 故本端口<b>不提供</b>以下四类方法，且这不是"还没写"：
 * <pre>
 *   ✗ updateBranch(...)          —— 结论一旦落库即为事实：改口径须重开一次判定
 *   ✗ updateConfidence(...)      —— 同上；且置信度是协商排序键，就地改会让排序失真
 *   ✗ deleteByCycle(...)         —— 删除即断链（verdict.cycle_id 是 FK）
 *   ✗ upsertById(...)            —— upsert 是"就地改写"的另一种写法
 * </pre>
 * <p>🛑 方法数从 6 增到 7（新增 {@link #findCycleAssessmentsByCustomer}）时，
 * 增的是一次<b>读取</b>，不是一条改写路径 —— 见该方法的注释说明它为什么必须存在。
 * <p>让它"不可绕过"的机械保证有三层（与 {@code RefundReceiptLedger} 同一套形态）：
 * <ol>
 *   <li><b>本类方法集</b>：没有可写出的调用序列；</li>
 *   <li><b>库层</b>：{@code verdict} / {@code cycle_assessment} 都有 {@code updated_at} 列
 *       （与退款三张账本<b>不同</b> —— 那三张刻意没有），但<b>本端口的 INSERT 列清单不含它</b>，
 *       且没有任何语句会去写它。差异是刻意的：这两张表允许未来有受控更新，
 *       而"允许"这件事必须通过<b>新增一个方法</b>来表达，不能顺手改一行 SQL；</li>
 *   <li><b>门禁</b>：{@code VerdictPersistenceBoundaryTest} 反射扫描本端口的
 *       全部方法名，出现 {@code update/delete/upsert/merge} 即红。</li>
 * </ol>
 *
 * <h2>🛑 为什么"按客户取判定历史"要放在端口上，而不是让服务层遍历周期</h2>
 * 因为 {@code verdict} 表<b>没有 customer_id 列</b>（它经 {@code cycle_id}
 * 关联到 {@code cycle_assessment}，后者才有 {@code customer_id}）。
 * 若让服务层先查周期、再逐个查判定，就会出现 N+1 次短事务 ——
 * 而每次短事务都要 {@code SET LOCAL app.tenant_id}，N 次就是 N 次上下文切换。
 * 更重要的是：N 次查询之间存在<b>事务边界</b>，两次之间客户可能又产生一次判定，
 * 于是返回的历史是"三个时点拼出来的"，而 PRD 要的是一份<b>同一时点</b>的历史
 * （判定历史要用于协商举证，拼出来的历史在举证时说不清是哪个时点的状态）。
 */
public interface VerdictPort {

    /**
     * 写入一条周期评估（{@code cycle_assessment}，判定依据）。
     *
     * <p>🛑 第二参数是<b>租户标识</b>：本端口不在 SQL 里写 {@code WHERE tenant_id = ?}，
     * 隔离完全由 V5 的 {@code FORCE ROW LEVEL SECURITY} 承担（ADR-02）。
     */
    void insertCycleAssessment(String tenantId, CycleAssessmentRow row);

    /** 按 ID 取周期评估；不存在返回 {@code null}（由服务层转 {@code NOT_FOUND(3001)}）。 */
    CycleAssessmentRow findCycleAssessment(String tenantId, UUID cycleId);

    /**
     * 写入一条判定结论（{@code verdict}）。
     *
     * <p>🛑 {@code visible_to_customer} 由实现<b>显式</b>绑 {@code false}
     * （不省略该列让 DEFAULT 生效）—— 见 {@link VerdictRow} 类注释：
     * 显式绑定把可攻击面从"一次数据库变更"降到"一行常量并被 CHECK 立刻拒绝"。
     */
    void insertVerdict(String tenantId, VerdictRow row);

    /** 按 ID 取判定；不存在返回 {@code null}。 */
    VerdictRow findVerdict(String tenantId, UUID verdictId);

    /**
     * 取某客户的全部判定历史（<b>一条 SQL 完成，不做 N+1</b>，见类注释）。
     *
     * <p>排序键为 {@code decided_at, verdict_id} —— 显式给出而不是依赖库返回顺序：
     * 判定历史要用于协商举证，顺序倒置会让"先稳定后无改善"读成"先无改善后稳定"。
     */
    List<VerdictRow> findVerdictsByCustomer(String tenantId, UUID customerId);

    /**
     * 取某周期评估下的全部判定（按 {@code decided_at} 升序；空返回空列表而非 {@code null}）。
     *
     * <p>一个周期可以有多次判定（例如挂起后人工补齐数据、重开一次）——
     * 故本方法是<b>列表</b>而不是"取最新一条"。要"最新"的调用方自己取末元素，
     * 且必须显式表达这个意图。
     */
    List<VerdictRow> findVerdictsByCycle(String tenantId, UUID cycleId);

    /**
     * 取某客户的<b>全部周期评估</b>（判定依据侧，按 {@code created_at, cycle_id} 升序）。
     *
     * <h2>🛑 为什么它必须存在（否则会有一类记录静默消失）</h2>
     * {@code verdict.confidence} 是 {@code NUMERIC(4,3) NOT NULL CHECK 0–1}，
     * 而置信度引擎在 {@code s = 不可比} 时<b>返回 {@code null} 而非 {@code 0.000}</b>
     * （红线①：不可判不是最低档）。两者夹逼的结果是：该态下<b>不存在可写的判定行</b>，
     * 于是"挂起轮次"只落 {@code cycle_assessment}（依据 + {@code branch = 人工复核}）。
     *
     * <p>若 F2 只读 {@code verdict}，这些轮次就会在判定历史里<b>整体消失</b> ——
     * 而它们恰恰是最需要留痕的一类：它们证明"系统在数据不全时没有硬下结论"
     * （PRD P0-13/P0-14 的合规姿态），也解释了"为什么这个客户的第二条判定是首个结论"。
     * 一条"没出现的记录"不会报错，只会让历史少一段。
     *
     * <p>调用方（F2）把本方法与 {@link #findVerdictsByCustomer} 的结果按 {@code cycleId}
     * 做差集，即得"挂起轮次"。差集在内存里完成，不额外增加 SQL 与事务边界。
     */
    List<CycleAssessmentRow> findCycleAssessmentsByCustomer(String tenantId, UUID customerId);

    /**
     * 受控更新：把<b>判定侧的四列</b>补到 C4 已落的一行周期评估上（两阶段拆分的第二阶段）。
     *
     * <h2>🛑 这是本端口<b>唯一</b>的 update —— 它的存在是被架构刻意预留的</h2>
     * {@link #PORT_METHOD_COUNT} 的注释早已写明：
     * <pre>
     *   verdict / cycle_assessment 都有 updated_at 列
     *   （与退款三张账本【不同】—— 那三张刻意没有），
     *   但本端口的 INSERT 列清单不含它，且没有任何语句会去写它。
     *   差异是刻意的：这两张表允许未来有受控更新，
     *   而"允许"这件事必须通过【新增一个方法】来表达，不能顺手改一行 SQL。
     * </pre>
     * 本方法就是那次"新增一个方法"。（V8 起 {@code cycle_assessment} 该表<b>有了</b>
     * {@code updated_at} 的唯一写入点，且被本方法的 SQL 列清单死死限定。）
     *
     * <h2>🛑 为什么必须有它（而不是"F1 再插一行"）</h2>
     * 契约把「周期评估提交」（C4）与「判定结论落库」（F1）拆成两个 operationId，
     * 而 {@code cycle_assessment.cycle_id} 是 PK ⇒ 一个周期只能有一行。
     * C4 先落该行（{@code verdict IS NULL} = 判定未发生），F1 再把判定侧列补上 ——
     * 这正是 A-1 裁定选项 B「评估先落、判定后补」的唯一可实现形态。
     *
     * <h2>🛑 它<b>不</b>是一次"覆盖"，而是一次"补全" —— 由 WHERE 子句机械保证</h2>
     * 实现的 SQL 恒为：
     * <pre>
     *   UPDATE cycle_assessment SET verdict = ?, effect_verdict = ?, improvement_rate = ?,
     *                               metric_snapshot = ?, updated_at = ?
     *    WHERE cycle_id = ? AND <b>verdict IS NULL</b>
     * </pre>
     * 谓词 {@code verdict IS NULL} 是这条纪律的落点：<b>一旦该周期已有结论，
     * 本次更新必然影响 0 行</b> ⇒ PRD §C.1.9 硬约束③「判定不可原地覆盖」仍然成立，
     * 只是从"整表禁止更新"收窄成"只允许从'未判定'到'已判定'这一次单向迁移"。
     * 想改一个已作出的结论，唯一途径仍是<b>重开一次判定</b>（新 cycle 行）。
     *
     * <h2>🛑 列清单就是白名单（且只有四列 + updated_at）</h2>
     * {@code verdict} / {@code effect_verdict} / {@code improvement_rate} /
     * {@code metric_snapshot} 四列是<b>判定侧</b>的产物，C4 阶段算不出；
     * 其余列（{@code as_value} / {@code as_dimensions_json} / {@code gap_days} /
     * {@code adherence_state} / {@code module_scores} / {@code threshold_version} /
     * {@code band_trend_note} / {@code customer_id} / {@code sequence_no}）
     * 都是<b>评估侧</b>事实，由 C4 落定，本方法<b>一个都不碰</b> ——
     * 让 F1 能改写 C4 已落的评估事实，等于给"事后修改依据"开了口子。
     *
     * @param cycleId           周期评估主键（C4 已落的那一行）
     * @param branch            判定分支（五值之一）
     * @param effectVerdict     效果结论；{@code null} = 挂起（D5 合法）
     * @param improvementRate   改善率；{@code null} = 不计算
     * @param metricSnapshotJson 判定侧依据快照（不可覆盖）
     * @param judgedAt          判定时刻（写入 {@code updated_at}）
     * @throws com.diaoyuanyun.dy.common.exception.BizException 该周期已存在结论（影响 0 行）时
     */
    void attachJudgment(String tenantId,
                        UUID cycleId,
                        com.diaoyuanyun.dy.app.derived.domain.VerdictBranch branch,
                        com.diaoyuanyun.dy.app.derived.domain.EffectVerdict effectVerdict,
                        java.math.BigDecimal improvementRate,
                        String metricSnapshotJson,
                        java.time.Instant judgedAt);

    /**
     * 端口方法集被钉死为 <b>8</b> 个（3 周期评估读取 + 1 周期评估受控更新 + 4 判定）。
     *
     * <p>供门禁反射断言使用：新增方法必须<b>显式</b>改
     * {@code VerdictPersistenceBoundaryTest} 里那条断言 ——
     * 使"给判定链加一个就地改写入口"变成一次必须表态的改动。
     *
     * <p>🛑 从 7 增到 8 的那一个（{@link #attachJudgment}）是<b>唯一</b>被允许的写入路径，
     * 且它的 SQL 被 {@code verdict IS NULL} 谓词收窄成单向迁移。这条"计数被钉死"
     * 的纪律不是形式主义：它让"再想加一个 update"必须同时改三个地方
     * （端口常量、门禁方法集、门禁 SQL 扫描），而不是顺手写一行 SQL。
     */
    int PORT_METHOD_COUNT = 8;

    /**
     * 租户标识校验（与退款域 {@code RefundReceiptPort.validateTenantId} 同一套白名单）。
     *
     * <p>两处共用同一套正则的理由：口径分叉处最容易出现"一处放宽了、另一处没放"，
     * 而 {@code SET LOCAL} 之前的 UUID 白名单是拼接前<b>唯一</b>的防护。
     */
    static void validateTenantId(String tenantId) {
        com.diaoyuanyun.dy.app.refund.domain.RefundReceiptPort.validateTenantId(tenantId);
    }
}