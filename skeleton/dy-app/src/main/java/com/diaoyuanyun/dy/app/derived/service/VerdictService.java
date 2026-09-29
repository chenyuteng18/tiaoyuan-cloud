package com.diaoyuanyun.dy.app.derived.service;

import com.diaoyuanyun.dy.app.derived.domain.AdherenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.AdherenceState;
import com.diaoyuanyun.dy.app.derived.domain.CycleAssessmentRow;
import com.diaoyuanyun.dy.app.derived.domain.DerivedMetricProfile;
import com.diaoyuanyun.dy.app.derived.domain.Disposition;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdict;
import com.diaoyuanyun.dy.app.derived.domain.EffectVerdictEngine;
import com.diaoyuanyun.dy.app.derived.domain.RiskFlag;
import com.diaoyuanyun.dy.app.derived.domain.ThresholdVersionFingerprint;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranch;
import com.diaoyuanyun.dy.app.derived.domain.VerdictBranchEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictConfidenceEngine;
import com.diaoyuanyun.dy.app.derived.domain.VerdictPort;
import com.diaoyuanyun.dy.app.derived.domain.VerdictRow;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ContractTbd;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 判定链服务 —— 契约域 F1 / F2（判定结论落库与历史）的<b>唯一业务编排点</b>。
 *
 * <h2>它编排什么（顺序固定，且顺序本身就是语义）</h2>
 * <pre>
 *   ① 可见性：x-callable-roles [meridian, admin] —— 客户与调理师一律 403
 *   ② 效果：EffectVerdictEngine.decide(...)        → 候选（可能挂起）
 *   ③ 置信度：VerdictConfidenceEngine.compute(...) → C（可能挂起）
 *   ④ 路由：VerdictBranchEngine.route(...)         → D1~D5
 *   ⑤ 出口：resolveDisposition(effect × adherence × risk) → disposition
 *   ⑥ 落库：cycle_assessment（依据，<b>必然</b>写 —— 见「两阶段」）
 *          + verdict（结论，V8 起<b>含挂起</b>也写）
 * </pre>
 * 🛑 <b>顺序不得调换</b>：②③ 各自可能挂起，而"挂起"必须传导到 ④ 的分支
 * （D5 人工复核）。若先路由再算置信度，就会出现"路由说是 D1，而置信度说不可判"
 * 的自相矛盾记录 —— 而落库的是不可覆盖的依据快照，矛盾一旦落库就永久留在证据链里。
 *
 * <h2>🛑 V8 起的<b>两阶段模型</b>（契约 C4 与 F1 的落地形态）</h2>
 * 契约把「周期评估提交」（C4 {@code POST /customers/{id}/cycle-assessments}）与
 * 「判定结论落库」（F1 {@code POST /cycle-assessments/{id}/verdicts}）拆成两个
 * operationId，而两阶段共用<b>同一张</b> {@code cycle_assessment} 表
 * （{@code cycle_id} 是 PK ⇒ 一个周期一行）。区分点只有一个：
 * <pre>
 *   阶段一（C4 已提交、判定未发生）  cycle.verdict IS NULL      —— "待判定"
 *   阶段二（F1 已落结论）            cycle.verdict IS NOT NULL  —— 五值之一
 * </pre>
 * 故 F1 落库时<b>先查该周期是否已存在</b>：
 * <ul>
 *   <li><b>不存在</b> ⇒ 本调用是首个到达者，一次 INSERT 落满（既有行为，兼容老调用方
 *       与"一个调用同时提交评估并判定"的用法）；</li>
 *   <li><b>已存在且未判定</b> ⇒ 经 {@link VerdictPort#attachJudgment} 补<b>判定侧四列</b>
 *       （{@code verdict / effect_verdict / improvement_rate / metric_snapshot}）；
 *       其 SQL 的 {@code WHERE ... AND verdict IS NULL} 保证不会改写已作出的结论；</li>
 *   <li><b>已存在且已判定</b> ⇒ 抛 {@code VERSION_CONFLICT(4001)}。
 *       🛑 不静默再插一行也不改写旧行 —— 判定历史用于协商举证，
 *       "同一个周期有两条结论"会让"客户在那个周期被怎么判的"失去唯一答案。</li>
 * </ul>
 *
 * <h2>🛑 挂起（D5）<b>也落 verdict 行</b>（V8 起；此前不落）</h2>
 * V8 之前，{@code verdict.confidence} 是 {@code NUMERIC(4,3) NOT NULL}，
 * 而置信度引擎在"测量不可比"时返回 {@code null}（不是 {@code 0.000} ——
 * "无从置信"与"置信度最低"在协商排序里含义相反）。两者夹逼的结果是：
 * <b>该态下不存在任何可写的判定行</b>，故挂起只能落依据侧。
 *
 * <p>V8 放宽了该列的 NOT NULL（见 {@code V8__verdict_two_phase_alignment.sql}），
 * 于是挂起获得一个<b>合法且语义精确</b>的表达位：
 * {@code confidence = null}（不可判）+ {@code disposition} 落库（继续原方案）。
 * <b>为什么落行比不落更正确</b>：
 * <ol>
 *   <li>"人工复核"是 PRD §7.3 D1~D5 里的一等分支 —— 一个合法分支却写不出记录，
 *       是结构缺陷，不是设计；</li>
 *   <li>不落行时，"这一轮判为挂起"只存在于依据侧，结论侧看不到它 ⇒
 *       F2 只能靠"有依据而无结论"<b>反推</b>，那是推断而非事实。落行后它是可直接读的结论；</li>
 *   <li>依据侧与结论侧的 {@code branch} 现在可以逐行比对（同值），
 *       回放时"快照 branch vs 重算 branch"多了一个独立对照点。</li>
 * </ol>
 * <p>🛑 既有数据不回溯补写 ⇒ F2 的差集逻辑<b>保留不动</b>，它现在同时覆盖
 * "C4 待判定"与"V8 之前的历史挂起轮次"两种形态。历史挂起轮次在 F2 里
 * 从 {@code verdicts[]} 与 {@code suspended[]} 两处都能看到 —— 这不是重复，
 * 而是同一事实在"结论行"与"依据侧留痕"两个载体上的自然呈现。
 *
 * <h2>不变式：{@code branch != 人工复核} ⟹ {@code confidence != null}</h2>
 * 因为 {@code confidence == null} 的唯一成因是 {@code s = 不可比}，
 * 而那样 {@link #createVerdict} 算出的 {@code sameOriginComparable} 必为 {@code false}
 * ⇒ 路由在 D5 前置处就返回人工复核。V8 放宽了库层 NOT NULL 之后，
 * 这条不变式<b>只由应用层守</b>（{@link VerdictRow} 的紧凑构造器 +
 * 本方法的落库前自检）—— 两处各守一个入口，不是冗余。
 *
 * <h2>🛑 三条判定权纪律（PRD §7.3 定调 + P0-12/P0-13/P0-14）</h2>
 * <ol>
 *   <li><b>系统绝不自产 E5</b>：{@link EffectVerdictEngine} 的产物是候选、不产 E5；
 *       本服务若收到 E5 一律拒（{@link VerdictBranchEngine.BranchInput} 与
 *       {@link VerdictRow} 各守一道 —— 守的是两个入口，不是冗余）。</li>
 *   <li><b>系统绝不自动给出"退款终止"</b>：{@link #resolveDisposition} 的自动路径
 *       永不返回 {@link Disposition#REFUND_TERMINATE}（P0-14 逐字
 *       「效果类走协商工单，人在环、不可自动直出资格结论」）。</li>
 *   <li><b>结论不对外直出</b>：{@code visible_to_customer} 由库层 CHECK 恒假
 *       （Q10 口径②）；本服务不提供任何把它设为真的途径。</li>
 * </ol>
 *
 * <h2>🛑 显著阈值与回测门槛一律 {@code TBD}，绝不填数</h2>
 * PRD §7.3 / 指标规格 §3.2 只说"模块 IR ≥ <b>显著阈值</b>"，<b>没有给值</b>；
 * P0-12 的回测联合门槛（κ≥0.70 / 一致率≥85% / 漏判率≤5%）要求
 * "公式 vs 已收敛人工结论"的比对 —— 而<b>人工金标准尚未产生</b>（需 N≥100 判定单元）。
 * 故本服务把这两项以 {@link ContractTbd#TBD} 落入依据快照，
 * 并按硬纪律 #6 <b>不编一个数</b>：填一个"看起来合理"的阈值，
 * 会让一个未经校准的数直接进入面客判定链，而它<b>不会报错</b>。
 *
 * <h2>🛑 它不做的事（各有其归处）</h2>
 * <table border="1">
 *   <tr><th>不做</th><th>归处</th><th>理由</th></tr>
 *   <tr><td>口径解析（六段配置 → 已归一对象）</td><td>{@code DerivedMetricProfile}</td>
 *       <td>解析与自洽校验必须只有唯一实现，否则每个来源各漏一个校验</td></tr>
 *   <tr><td>AS / MCID / 置信度的算法</td><td>三个引擎</td>
 *       <td>算法与编排分离，使"算法有没有被改"可被单测独立断言</td></tr>
 *   <tr><td>持久化的 SQL</td><td>{@link VerdictPort}</td>
 *       <td>服务层依赖端口而非 JDBC，使"判定链能不能被置换存储"是一个可回答的问题</td></tr>
 * </table>
 *
 * <h2>🛑 S2-8：{@code threshold_version} 改为<b>服务端算出的内容寻址指纹</b></h2>
 * 在 S2-8 之前，{@code threshold_version} 是 F1 请求体里调用方传入的<b>自由字符串</b>，
 * 服务端只校验它非空白。这使 PRD §C.1.9 硬约束②的「可回放」在实现上落空：
 * <pre>
 *   调用方传 "abc" ⇒ 能成功落库 ⇒ 复盘时拿 "abc" 去问"当时按哪套门槛下的结论"，
 *                              得到的只有 "abc" 本身。
 * </pre>
 * 现在改为三步（顺序即语义）：
 * <ol>
 *   <li><b>服务端算</b>：{@link ThresholdVersionFingerprint#of} 从当前口径剖面
 *       算出 {@code tv1-…}，<b>并落库它</b>（而不是落调用方传的串）；</li>
 *   <li><b>入参只作断言</b>：若请求体仍携带 {@code threshold_version}，
 *       则它必须与算出的指纹<b>逐字相同</b>，否则拒写（{@link ErrorCode#VERSION_CONFLICT}）。
 *       这条把"传一个不存在的版本来洗白结论"从"落库之后才发现"提前到"写库之前就失败"；</li>
 *   <li><b>不传即服务端填</b>：入参可为空（缺省）。这与 {@code risk_flag} / {@code confidence}
 *       的"不传即挂起"不同 —— 它们缺席改变<b>结论</b>，而版本号缺席<b>不改变任何结论</b>，
 *       它只是"这次判定按哪套口径算的"这一事实，而该事实服务端本来就知道。
 *       故这里不存在"兜底成某个数"的问题：算出来的就是真的那个。</li>
 * </ol>
 * <p>🛑 为什么不直接把入参去掉（未冻结的契约确实允许改）：F1 无 {@code requestBody} 定义，
 * 但已经落库的历史行与在途调用都带着这个字段。保留它并<b>校验</b>，
 * 让"老调用方传对了继续可用、传错了立刻失败"成为一次可观测的迁移，
 * 而不是一次静默的接口变更（后者会让老调用方以为自己在指定版本，实际被忽略）。
 */
@Service
public class VerdictService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 调用本域所需的端角色（契约 F1/F2 逐字：{@code x-callable-roles: [meridian, admin]}）。
     *
     * <p>🛑 它<b>不含</b> {@code therapist}：契约 F 域与 G 域不同 ——
     * G 域用 {@code x-client-forbidden: true}（客户拒），F 域用
     * {@code x-client-explicitly-denied: true}（客户拒，且 F2 明确"客户与调理师端一律 403"）。
     * 故 F 域是"客户 <b>与</b> 调理师"两类被拒，比 G 域更窄。
     */
    private static final List<String> CALLABLE_TOKEN_ROLES = List.of("meridian", "manager", "area", "hq");

    private final VerdictPort ledger;
    private final EffectVerdictEngine verdictEngine;
    private final VerdictConfidenceEngine confidenceEngine;
    private final AdherenceEngine adherenceEngine;
    private final VerdictBranchEngine branchEngine;

    /**
     * 当前口径的内容寻址指纹（S2-8）。构造期算一次 —— 口径在应用启动期已解析、
     * 运行期不变（{@code DerivedEngineConfig} 是启动期 bean），故不需要每次判定重算。
     *
     * <p>🛑 若将来口径变为运行期可热更新，这里必须改成"每次取当前剖面再算"，
     * 否则会出现"新口径算出的结论带着旧版本号"—— 那正是硬约束③要堵的
     * "同一版本号指向两套口径"。
     */
    private final ThresholdVersionFingerprint thresholdFingerprint;

    public VerdictService(VerdictPort ledger,
                          EffectVerdictEngine verdictEngine,
                          VerdictConfidenceEngine confidenceEngine,
                          AdherenceEngine adherenceEngine,
                          VerdictBranchEngine branchEngine,
                          DerivedMetricProfile profile) {
        this.ledger = ledger;
        this.verdictEngine = verdictEngine;
        this.confidenceEngine = confidenceEngine;
        this.adherenceEngine = adherenceEngine;
        this.branchEngine = branchEngine;
        this.thresholdFingerprint = ThresholdVersionFingerprint.of(profile);
    }

    // ==================================================================
    // 零、可见性守卫（F1/F2 的唯一入口）
    // ==================================================================

    /**
     * 断言调用方可调本域端点（契约 F1/F2 的 {@code x-callable-roles}）。
     *
     * <h2>🛑 为什么它<b>不</b>复用 {@code VisibilityRole}</h2>
     * {@code VisibilityRole} 把 {@code manager/area/hq} <b>折叠</b>成 {@code admin} ——
     * 那是"字段组可见性"层的正确做法（契约 {@code x-visibility-matrix} 的列就是端角色）。
     * 但本域的 {@code x-callable-roles: [meridian, admin]} 是<b>端点档位</b>，
     * 而展开后的 {@code admin} 恰好就是这三个码。此处需要的正是<b>展开后</b>的集合，
     * 故直接按 token 码比较，而不是先折叠再展开（先折叠会丢掉"是哪个 admin"，
     * 而 F3 的行级 scope 恰恰要按这个区分 —— 见契约 {@code x-row-scope}）。
     *
     * <h2>fail-closed：未登记即拒，绝不回落</h2>
     * 与 {@code RefundAudienceRole.ofTokenRole} 同一条纪律：回落会让一次角色码拼写错误
     * 静默变成一次权限拒绝，而真正的成因（有人用了未登记的角色）在日志里查无此事。
     *
     * @throws BizException {@code VISIBILITY_DENIED(2001)} —— 语义是 403 而非 500
     */
    public String requireCallable(String tokenRole) {
        if (tokenRole == null || tokenRole.isBlank()) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "请求未携带 token 角色 —— 判定域不设匿名通道："
                            + "契约 F1/F2 的 x-callable-roles 是冻结项，无身份即无从判定");
        }
        String t = tokenRole.trim();
        // 🛑 客户与调理师在此显式点名拒绝（契约 F2 逐字「客户与调理师端一律 403」）——
        //    而不是靠"不在白名单里"来拒。理由：那样这两类角色的拒绝理由会与
        //    "一个拼错的角色码"共用同一条消息，而两者的处置完全不同
        //    （前者是契约行为，后者是有人在用系统不认识的角色）。
        if ("client".equals(t) || "therapist".equals(t)) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "角色『" + t + "』对判定域无可见性档位（契约 F2：客户与调理师端一律 403）。"
                            + "🔴 判定结论定位为『对内判定建议 + 置信度』"
                            + "（PRD P0-13），不面向客户展示、不可作为对外举证材料");
        }
        if (!CALLABLE_TOKEN_ROLES.contains(t)) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "token 角色不在判定域可调集合内: '" + tokenRole + "'"
                            + "（契约 F1/F2 的 x-callable-roles: [meridian, admin]；"
                            + "admin 展开 = " + CALLABLE_TOKEN_ROLES.subList(1, CALLABLE_TOKEN_ROLES.size()) + "）。"
                            + "🛑 不得回落为『拒绝』—— 那会让一个拼错的角色码静默变成一次权限收紧");
        }
        return t;
    }

    // ==================================================================
    // 一、F1 判定结论落库
    // ==================================================================

    /**
     * F1 入参 —— 一次判定的全部原始事实。
     *
     * <h2>为什么它是"原始事实"而不是"已算好的分支"</h2>
     * PRD P0-12 逐字：「判定出口 = {@code effect_verdict} × {@code adherence_state}
     * × {@code risk_flag} 组合 → {@code disposition}，<b>不得混入单一 enum</b>」。
     * 若入参自带 branch，那么"依从不足 + 明显改善"这类组合在调用点就被压平，
     * 其处置（调整后继续而非强化干预）将永远无法被推导。
     *
     * @param customerId          客户（必填 —— 用于历史查询与越权判定）
     * @param sequenceNo          第 N 次评估（CHECK ≥ 1）
     * @param baseTotal           基线模块总分；{@code null} = 缺失（⇒ 挂起）
     * @param currentTotal        复评模块总分；{@code null} = 缺失（⇒ 挂起）
     * @param sameOrigin          同源断言（不成立 ⇒ 挂起）
     * @param adherence           依从性输入（三维 A1/A3/A4；不得含 A2）
     * @param riskFlag            风险标签（经络师录入）；{@code null} ⇒ 挂起（不得默认"无"）
     * @param coreMetricImproved  核心指标是否改善/稳定（经络师结构化录入）；{@code null} ⇒ 挂起
     * @param confidenceInput     置信度输入（四项因子）
     * @param moduleScoresJson    模块分 JSON（M1–M5，0–16）
     * @param bandTrendNote       手环趋势说明（U-15）
     * @param thresholdVersion    🛑 <b>断言位，不是取值位</b>（S2-8 起语义变更）：
     *                            可空；非空时必须逐字等于服务端算出的内容寻址指纹，
     *                            否则拒写 {@code VERSION_CONFLICT(4001)}。
     *                            落库的值<b>一律取服务端算出的那个</b>，绝不取此入参。
     */
    public record CreateVerdictRequest(
            UUID customerId,
            int sequenceNo,
            Integer baseTotal,
            Integer currentTotal,
            EffectVerdictEngine.SameOriginAssert sameOrigin,
            AdherenceEngine.AdherenceInput adherence,
            RiskFlag riskFlag,
            Boolean coreMetricImproved,
            VerdictConfidenceEngine.ConfidenceInput confidenceInput,
            String moduleScoresJson,
            String bandTrendNote,
            String thresholdVersion) {

        public CreateVerdictRequest {
            if (customerId == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "customer_id 必填 —— 缺它则本次判定无法关联到任何客户，"
                                + "而 F2 的按客户取历史会永久查不到这一单");
            }
            // 🛑 S2-8：threshold_version 从"必填的空值校验"改为"可空的断言位"。
            //    旧形态要求它非空白，于是调用方<b>必须</b>自己编一个串 ——
            //    而服务端无法判断那个串对应哪套口径，硬约束②的"可回放"就此落空。
            //    新形态下服务端自己算（见 createVerdict），入参只在带上时被用来做一致性断言。
            //    这里不再做非空校验：空 = "请服务端填"，是一个合法且更安全的请求形态。
            if (thresholdVersion != null && thresholdVersion.isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "threshold_version 若携带则不得为空白字符串 —— 空白的业务含义与"
                                + "『请服务端填』相同，但在这一次里它是被显式给了一个无意义的值。"
                                + "不带该字段即可让服务端按当前口径算出并落库（推荐形态）");
            }
        }
    }

    /**
     * F1 判定结论落库（{@code POST /cycle-assessments/{id}/verdicts}）。
     *
     * <p>编排顺序见类注释。落库两条记录：{@code cycle_assessment}（依据）+
     * {@code verdict}（结论），两者都<b>只有 INSERT</b>（不可覆盖）。
     *
     * @param tenantId    租户（RLS 上下文）
     * @param cycleId     周期评估主键（由路径参数给定 —— 契约 F1 的路径即
     *                    {@code /cycle-assessments/{id}/verdicts}，故 {@code id} 就是 cycle_id）
     * @param req         判定入参
     * @param createdBy   操作人
     * @return 落库后的判定（含组合出口与依据快照）
     */
    public VerdictOutcome createVerdict(String tenantId, UUID cycleId,
                                        CreateVerdictRequest req, String createdBy) {
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "判定入参不得为空");
        }
        if (cycleId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "cycle_assessment 主键必填（契约 F1 的路径 id 即 cycle_id）");
        }

        // ① S2-8：版本号由服务端算，入参只在带上时作一致性断言（见类注释的三步说明）
        //
        // 🛑 断言放在【任何计算之前】：若调用方传了一个与当前口径不符的版本号，
        //    本次判定的前提就已经不成立，继续算下去只会产出一份"带着错版本的依据"。
        //    而依据是不可覆盖的（硬约束③）—— 一次落库即永久留在证据链里。
        //    故此处的最优处置是"立刻失败且不落任何行"，而不是"算完再拒"。
        String thresholdVersion = assertThresholdVersion(req.thresholdVersion());

        // ② 效果候选（可能挂起：同源不成立 / 基线或复评缺失 / baseline_zero）
        EffectVerdictEngine.VerdictCandidate candidate = verdictEngine.decide(
                new EffectVerdictEngine.ImprovementInput(
                        req.sameOrigin(), req.baseTotal(), req.currentTotal()));

        // ③ 依从（AS_refund；样本不足时连值都不算）
        AdherenceEngine.AdherenceResult adherence = adherenceEngine.compute(req.adherence());

        // ③′ 置信度（s=0 时挂起，不是"低置信"）
        VerdictConfidenceEngine.ConfidenceResult confidence =
                confidenceEngine.compute(req.confidenceInput());

        // ④ 路由（D1~D5）。🛑 挂起必须传导：候选挂起 ⇒ effectVerdict 传 null ⇒ D5
        EffectVerdict routedEffect = (candidate.suspended() || candidate.verdict() == null)
                ? null : candidate.verdict();
        // 同源是否可比：同源断言成立 且 置信度未因"不可比"一票否决
        //
        // 🛑 用 ConfidenceResult.suspended() 而不是读取 SameOriginStatus —— 后者<b>不在</b>
        //    结果 record 的字段里（结果只保留 confidence/suspended/suspendReason/band/
        //    factors/exponentsUsed）。这不是巧合：suspended=true 的<b>唯一</b>成因就是
        //    s=不可比（VerdictConfidenceEngine 只有 INCOMPARABLE 那一支返回 suspended=true），
        //    故结果里保留 suspended 而不再保留 s 的原始值，本就无信息损失。
        //    两者等价，由 VerdictConfidenceEngineSuspensionTest 对该等价关系做断言。
        boolean sameOriginComparable = req.sameOrigin() != null && req.sameOrigin().holds()
                && !confidence.suspended();

        VerdictBranchEngine.BranchResult routed = branchEngine.route(
                new VerdictBranchEngine.BranchInput(
                        routedEffect,
                        adherence.state(),
                        req.riskFlag(),
                        req.coreMetricImproved(),
                        sameOriginComparable));

        // ⑤ 组合出口。🛑 自动路径永不产出"退款终止"（P0-14）
        //
        // 🛑 挂起分支（D5）【不进入】组合出口求解：PRD P0-12 的三 enum 组合要求
        //    三件事实都是"已算出"的，而挂起恰恰意味着"没算出来"。
        //    用一个假枚举（如 E3稳定）填空会造出"未知被说成稳定"这类结论 ——
        //    而它会作为不可覆盖的依据永久落库。
        //    故挂起时处置走独立的收敛值：继续原方案（服务中，不产生对客户不利的推测）。
        Disposition disposition = (routed.branch() == VerdictBranch.HUMAN_REVIEW || routedEffect == null)
                ? dispositionForSuspended()
                : branchEngine.resolveDisposition(routedEffect, adherence.state(),
                        effectiveRisk(req.riskFlag(), routed.branch()));

        // ⑥ 依据快照（不可覆盖）—— 含 AS、三 enum、阈值版本、组合出口、TBD 项
        String evidenceJson = buildEvidenceSnapshot(req, candidate, adherence, confidence, routed,
                disposition, thresholdVersion, sameOriginComparable);

        Instant now = Instant.now();
        UUID verdictId = UUID.randomUUID();
        boolean suspended = routed.branch() == VerdictBranch.HUMAN_REVIEW;

        // ⑦ 落库：先依据、后结论（顺序固定 —— 结论的 FK 指向依据，
        //    反序会让一次失败留下一条悬空依据，而那条依据会被 F2 查到却无结论可读）
        //
        // 🛑 V8 起改为【按存在性二选一】：C4 是否已经落过这一行？
        //    契约把「周期评估提交」（C4）与「判定结论落库」（F1）拆成两个 operationId，
        //    而 cycle_assessment.cycle_id 是 PK ⇒ 一个周期只能有一行。故：
        //      · 该周期【不存在】⇒ 本调用是首个到达者，一次 INSERT 落满（既有行为）
        //      · 该周期【已存在且未判定】⇒ C4 先落过（verdict IS NULL），此处【补全】
        //        判定侧四列 —— 这正是 A-1 裁定「评估先落、判定后补」的落地形态
        //      · 该周期【已存在且已判定】⇒ 拒绝（append-only：想改结论只能重开判定）
        CycleAssessmentRow existing = ledger.findCycleAssessment(tenantId, cycleId);

        boolean cycleRowAlreadyExists = existing != null;
        if (existing != null && existing.branch() != null) {
            // 🛑 已判定过 ⇒ 拒。不得静默再插一行或改写旧行：
            //    判定历史要用于协商举证，"同一个周期出现两条结论"会让
            //    "客户在某个周期被怎么判的"这个问题失去唯一答案。
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "该周期评估已存在判定结论（branch=" + existing.branch().dbLabel() + "），"
                            + "拒绝重复判定: cycle_id=" + cycleId + "。"
                            + "🛑 PRD §C.1.9 硬约束③「判定不可原地覆盖」—— "
                            + "想改一个已作出的结论，唯一途径是【重开一次判定】"
                            + "（新的周期评估），而不是对同一周期再判一次");
        }

        if (!cycleRowAlreadyExists) {
            ledger.insertCycleAssessment(tenantId, new CycleAssessmentRow(
                    cycleId,
                    req.customerId(),
                    req.sequenceNo(),
                    adherence.asValue(),
                    serialise(adherenceDimensions(req)),
                    evidenceJson,
                    req.confidenceInput() == null ? null : req.confidenceInput().expectedDays(),
                    routed.branch(),
                    // 🛑 挂起时 effect_verdict 必须为 null，不得回落 E3（未知不等于稳定）
                    routedEffect,
                    adherence.state(),
                    candidate.improvementRate(),
                    req.moduleScoresJson(),
                    thresholdVersion,
                    req.bandTrendNote(),
                    now,
                    createdBy));
        } else {
            // C4 已落过依据行 ⇒ 只补判定侧四列（列清单是白名单，见 VerdictPort.attachJudgment）
            ledger.attachJudgment(tenantId, cycleId, routed.branch(), routedEffect,
                    candidate.improvementRate(), evidenceJson, now);
        }

        // ⑧ 落结论行（verdict）—— 🛑 V8 起【挂起也落】。
        //
        //    原先"挂起时不写 verdict 行"的唯一理由（库层 confidence NOT NULL 与
        //    "不可判 ≠ 0.000"夹逼）已由 V8 的 `ALTER COLUMN confidence DROP NOT NULL`
        //    解除。放宽后，挂起态获得一个【合法且语义精确】的表达位：
        //      confidence = NULL（不可判，不是最低档）+ disposition 落库（继续原方案）。
        //
        //    🛑 为什么"挂起也落一行"比"不落"更正确：
        //      ① "人工复核"是 PRD §7.3 D1~D5 里的一等分支，一个合法分支却写不出记录
        //         是结构缺陷（原缺口③的实质）；
        //      ② 不落行时，"这一轮判为挂起"这一事实只存在于依据侧（cycle.verdict），
        //         而结论侧（verdict 表）看不到它 —— 于是 F2 的差集必须靠"有依据无结论"
        //         来反推，那是【推断】而不是【事实】。落行后它就是一条直接可读的结论；
        //      ③ 依据侧与结论侧的 branch 现在可以逐行比对（同值），
        //         回放时"快照 branch vs 重算 branch"有了第二个独立对照点。
        //
        //    🛑 代价与处置：既有数据里挂起轮次仍只有依据侧留痕（V8 不回溯补写）——
        //       故 F2 的差集逻辑【保留不动】，它现在同时覆盖
        //       "C4 待判定"与"V8 之前的历史挂起轮次"两种形态。
        if (!suspended && confidence.confidence() == null) {
            // 不变式自检（见类注释与 VerdictRow 的 V8 不变式）：非挂起 ⇒ 置信度必非空。
            // 若它被改坏，这里给出的是指向成因的错误，而不是一次语义漂移。
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "路由结果为『" + routed.branch().dbLabel() + "』（非挂起）但置信度为 null —— "
                            + "该组合不该出现：置信度为空只可能来自 s=不可比，"
                            + "而那会在路由的 D5 前置处就返回『人工复核』"
                            + "（V8 放宽了库层 NOT NULL，故这条不变式现在只由应用层守）");
        }
        ledger.insertVerdict(tenantId, new VerdictRow(
                verdictId,
                cycleId,
                routed.branch(),
                // 🛑 挂起时为 null（"不可判"），非挂起时必为数值 —— 由上面那条自检保证
                confidence.confidence(),
                evidenceJson,
                thresholdVersion,
                now,
                routedEffect,
                adherence.state(),
                req.riskFlag(),
                // 🛑 V8 新增列：组合出口（PRD §C.1.7 L1794）。挂起态落"继续原方案"这一
                //    收敛值（见 dispositionForSuspended 的论证：客户仍在服务中，
                //    不产生对客户不利的推测）。
                disposition,
                createdBy));

        return new VerdictOutcome(verdictId, cycleId, routed, disposition, confidence,
                candidate, adherence, evidenceJson, now, suspended, cycleRowAlreadyExists);
    }

    // ==================================================================
    // 一′、C4 周期评估提交（两阶段的第一阶段：只落依据，不落结论）
    // ==================================================================

    /**
     * C4 周期评估提交（{@code POST /customers/{id}/cycle-assessments}）。
     *
     * <h2>🛑 它<b>只落依据</b>，判定分支刻意留 {@code null}</h2>
     * 契约把「周期评估提交」（C4）与「判定结论落库」（F1）拆成两个 operationId，
     * 而两阶段落在<b>同一张</b> {@code cycle_assessment} 表（PK = {@code cycle_id}）。
     * 故本方法落的行满足：
     * <pre>
     *   cycle.verdict      IS NULL     ← 判定尚未发生（V8 放宽该列后合法）
     *   cycle.effect_verdict IS NULL   ← 效果结论未产生
     *   cycle.improvement_rate IS NULL ← 改善率属判定侧，不做对比
     * </pre>
     * 而 <b>依据四列必须落满</b>（{@code metric_snapshot} / {@code as_dimensions_json} /
     * {@code module_scores} / {@code threshold_version}）—— 它们在提交时<b>已经算得出</b>
     * （见 V8 迁移的逐列核算表），且 PRD §C.1.9 硬约束②③ 要求它们必须落库。
     * 留一个"以后再补"的口子会让一条有结论却没依据的行成为可能，而回放拿到
     * 不完整依据<b>不会报错</b>。
     *
     * <h2>🛑 重复提交必须拒（而不是改写）</h2>
     * {@code cycle_id} 是 PK，故"同一个周期提交两次"在库层就会撞主键。
     * 本方法在<b>进入 SQL 之前</b>显式查一次，是为了把那次 23505（唯一约束冲突）
     * 换成一条说清成因的业务错误：重复提交的后果不是"多一行"，
     * 而是"这一轮的评估序号 / 依据快照到底以哪次为准"在证据链里失去唯一答案。
     *
     * @param tenantId   租户（RLS 上下文）
     * @param customerId 客户（由路径参数给定 —— C4 的路径即
     *                   {@code /customers/{id}/cycle-assessments}，故 {@code id} 就是 customer_id）
     * @param req        评估提交入参
     * @param createdBy  操作人
     * @return 新落的周期评估行（{@code branch == null} = 待判定）
     */
    public CycleAssessmentRow submitCycleAssessment(String tenantId, UUID customerId,
                                                    CreateCycleAssessmentRequest req,
                                                    String createdBy) {
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "周期评估入参不得为空（契约 C4 未冻结 requestBody，故按本域语义承接）");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "customer_id 必填（契约 C4 的路径 id 即 customer_id）");
        }
        if (req.cycleId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "cycle_id 必填 —— 评估提交要产出可被 F1 引用的主键，"
                            + "服务端生成它并与响应一并返回");
        }

        // 🛑 版本号断言与 F1 同口径（S2-8）：入参只在带上时作一致性断言，否则服务端填
        String thresholdVersion = assertThresholdVersion(req.thresholdVersion());

        // 🛑 依从：C4 阶段就能算（依从四维来自本次作答）
        AdherenceEngine.AdherenceResult adherence = adherenceEngine.compute(req.adherence());

        // 🛑 重复提交检查（在写之前，见方法注释）
        CycleAssessmentRow existing = ledger.findCycleAssessment(tenantId, req.cycleId());
        if (existing != null) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "该周期评估已存在，拒绝重复提交: cycle_id=" + req.cycleId()
                            + "（已存在行处于" + (existing.branch() == null ? "『待判定』" : "『已判定』")
                            + "阶段）。🛑 cycle_id 是主键 ⇒ 一个周期只能有一行评估依据 —— "
                            + "重复提交会让『这一轮的依据以哪次为准』失去唯一答案");
        }

        // 🛑 依据快照：阶段一只记"评估侧事实"，判定侧字段显式标为未产生
        String snapshot = buildCycleEvidenceSnapshot(req, adherence, thresholdVersion);

        Instant now = Instant.now();
        CycleAssessmentRow row = CycleAssessmentRow.awaitingVerdict(
                req.cycleId(),
                customerId,
                req.sequenceNo(),
                adherence.asValue(),
                serialise(adherenceDimensionsOf(req)),
                snapshot,
                req.expectedDays(),
                adherence.state(),
                req.moduleScoresJson(),
                thresholdVersion,
                req.bandTrendNote(),
                now,
                createdBy);

        ledger.insertCycleAssessment(tenantId, row);
        return row;
    }

    /**
     * C4 入参 —— 一次周期评估的<b>评估侧</b>原始事实。
     *
     * <h2>🛑 它与 F1 入参的重叠部分是刻意的</h2>
     * {@code cycle_id} / {@code sequence_no} / {@code adherence} / {@code module_scores} /
     * {@code band_trend_note} / {@code threshold_version} 六项两边都有：
     * 当 F1 在<b>已有</b>周期上补判定时，它必须能与 C4 落的依据行对得上，
     * 否则会出现"评估说依从达标、判定按依从不足算"这类静默分叉。
     * 实现上 F1 走 {@code attachJudgment} 只写判定侧四列，
     * <b>不覆盖</b> C4 已落的评估侧事实 —— 故那份重叠不构成两个真相源。
     *
     * <h2>🛑 它没有 {@code cycle_id} 的生成权之外的东西</h2>
     * {@code branch} / {@code effect_verdict} / {@code improvement_rate} 三项
     * <b>不在</b>本入参里：它们是判定侧产物，由 F1 产出。
     *
     * @param cycleId          周期评估主键（由调用方给定，使 C4 与 F1 能指向同一行）
     * @param sequenceNo       第 N 次评估（≥ 1；"每 7 次触发"中的序位）
     * @param adherence        依从四维（AS 的输入；可空 ⇒ 样本不足）
     * @param expectedDays     应填天数（样本护栏）
     * @param moduleScoresJson 模块分（M1–M5，各 0–16 的 JSON）
     * @param bandTrendNote    手环趋势说明（U-15 四条备注语义的载体）
     * @param thresholdVersion 阈值版本（入参携带时作一致性断言；否则服务端填）
     */
    public record CreateCycleAssessmentRequest(
            UUID cycleId,
            int sequenceNo,
            AdherenceEngine.AdherenceInput adherence,
            Integer expectedDays,
            String moduleScoresJson,
            String bandTrendNote,
            String thresholdVersion) {
    }

    /**
     * C4 依据快照 —— <b>阶段一</b>版（判定侧字段显式标"未产生"）。
     *
     * <p>🛑 为什么不留空对象或省略判定侧键：一份快照要能独立回答
     * "当时发生了什么"。若阶段一的快照里根本没有 {@code branch} 这个键，
     * 复盘时无法区分"当时还没判"与"这一版代码没记 branch"。
     * 显式写 {@code null} 并附 {@code judgement_stage} 说明，
     * 使这份依据在 F1 落结论之后仍然可读。
     *
     * <p>🛑 它<b>不</b>落 {@code significant_threshold} / {@code backtest_gates}
     * 之外的任何判定项 —— 那些在 F1 的快照里（阶段二）才有值，
     * 且 F1 会用<b>完整的</b>快照覆盖本列的 {@code metric_snapshot}
     * （见 {@code attachJudgment}：{@code metric_snapshot} 在判定侧四列之内）。
     */
    private String buildCycleEvidenceSnapshot(CreateCycleAssessmentRequest req,
                                              AdherenceEngine.AdherenceResult adherence,
                                              String thresholdVersion) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("judgement_stage", "C4-评估已提交・判定未发生");
        root.put("as_refund", adherence.asValue() == null ? null : adherence.asValue().toPlainString());
        root.put("adherence_state", adherence.state().label());
        root.put("sequence_no", req.sequenceNo());
        root.put("gap_days", req.expectedDays());
        // 🛑 判定侧三键显式写 null（见方法注释：要让复盘能区分"未判"与"没记"）
        root.putNull("branch");
        root.putNull("effect_verdict");
        root.putNull("improvement_rate");
        root.put("judgement_note",
                "本快照来自 C4 周期评估提交 —— 判定分支尚未产生（cycle.verdict IS NULL）。"
                        + "判定结论落库（F1）时会以完整快照覆盖本列，并写入 verdict 行");
        root.put("threshold_version", thresholdVersion);
        ObjectNode segments = root.putObject("threshold_version_segments");
        thresholdFingerprint.segmentFingerprints().forEach(segments::put);
        root.put("threshold_version_source",
                "服务端由当前口径剖面算出的内容寻址指纹（ThresholdVersionFingerprint）；"
                        + "与 F1 同源 —— 两阶段共用一个版本号，否则回放会拿 A 比 B");
        // 🛑 两项 TBD（硬纪律 #6：不得填数）
        root.put("significant_threshold", ContractTbd.TBD);
        root.put("backtest_gates", ContractTbd.TBD);
        return serialise(root);
    }

    /** 依从四维 → JSON（C4 入参版；与 F1 的 {@code adherenceDimensions} 同构）。 */
    private Map<String, Object> adherenceDimensionsOf(CreateCycleAssessmentRequest req) {
        if (req.adherence() == null || req.adherence().dimensions() == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_missing", true);
            out.put("_note", "依从四维未提供（调用方未传 adherence）—— 该次评估的 AS 无法回放");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        req.adherence().dimensions().forEach((code, di) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("applicable", di.applicable());
            one.put("value", di.value() == null ? null : di.value().toPlainString());
            one.put("structural_missing", di.structuralMissing());
            out.put(code, one);
        });
        out.put("_expected_days", req.adherence().expectedDays());
        return out;
    }

    // ==================================================================
    // 二、F2 判定历史
    // ==================================================================

    /**
     * F2 判定历史（{@code GET /customers/{id}/verdicts}）。
     *
     * <p>🔴 调用方必须是 {@code meridian} 或 {@code admin}（契约 {@code x-callable-roles}）——
     * 客户与调理师一律 403，由 {@link #requireCallable} 在<b>本方法第一次调用</b>时拒。
     *
     * <p>🛑 返回的是 {@code verdict} 的<b>全部历史</b>（一个客户可以有多次判定），
     * 而不是"最新一条"：判定历史用于协商举证，只给最新一条会让
     * "客户改过口径""先稳定后无改善"这类过程在数据里消失。
     */
    public List<VerdictRow> listVerdicts(String tenantId, UUID customerId) {
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 必填");
        }
        return ledger.findVerdictsByCustomer(tenantId, customerId);
    }

    /**
     * F2 的完整判定历史 —— <b>含"待判定/挂起"轮次</b>。
     *
     * <h2>🛑 为什么不能只返回 {@code verdict} 的列表</h2>
     * 有一类周期"有依据而无结论"，它们必须被显出，否则历史会静默少一段。
     * V8 前后该类的构成不同（这正是差集口径必须保留下来的原因）：
     * <table border="1">
     *   <tr><th>时点</th><th>"有依据而无结论"的成因</th></tr>
     *   <tr><td>V8 之前</td><td>仅 D5 挂起（那时 {@code confidence NOT NULL} 夹逼，
     *       挂起写不出 {@code verdict} 行）</td></tr>
     *   <tr><td>V8 起（新写入）</td><td>仅 <b>C4 已提交、判定未发生</b>
     *       （挂起现在<b>也落</b> {@code verdict} 行，见类注释「挂起也落 verdict 行」）</td></tr>
     *   <tr><td>V8 起（在读历史）</td><td><b>两者都有</b> —— 历史数据不回溯补写，
     *       故老挂起轮次仍以此形态呈现</td></tr>
     * </table>
     * 一条<b>没出现</b>的记录不会报错，只会让历史少一段。这两类轮次尤其重要：
     * 它们证明"系统在数据不全时没有硬下结论"（PRD P0-13/P0-14 的合规姿态），
     * 也解释了"为什么该客户的首次结论不是第一次评估产生的"。
     *
     * <h2>差集怎么算</h2>
     * 取客户的全部 {@code cycle_assessment}（依据侧）与全部 {@code verdict}（结论侧），
     * 按 {@code cycleId} 求差：<b>有依据而无结论</b>的周期即"待判定/挂起"轮次。
     * 两侧各一条 SQL，差集在内存完成 —— 不引入 N+1，也不引入第二个时点
     * （两条查询在同一租户上下文，见 {@code VerdictLedger} 的短事务说明）。
     *
     * <p>🛑 本方法的<b>实现自 V8 起一字未改</b>，这是刻意的：
     * 两阶段拆分（C4 先落依据、F1 后补结论）与挂起态在同一张表上用同一个谓词表达 ——
     * 「{@code verdict} 行是否存在」。前者闭合于 F1 落库时，后者永久保持差集形态。
     * 若把差集改成读 {@code cycle.verdict IS NULL}，V8 之前的历史挂起轮次
     * （其 {@code cycle.verdict} 已被写成 '人工复核'）会立刻从历史里消失。
     *
     * <p>🛑 挂起轮次（老数据）的条目里 {@code branch = 人工复核}，它<b>不是</b>一个结论，
     * 而是"这一轮需要人补数据"。消费方必须按此理解，不得把它当作判定结果统计。
     * 而"待判定"轮次（V8 起新数据）的 {@code branch} 为 {@code null} ——
     * 两者在 F2 出参里由 {@code branch} 是否为空区分。
     */
    public CustomerVerdictHistory listCustomerVerdictHistory(String tenantId, UUID customerId) {
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 必填");
        }
        List<VerdictRow> verdicts = ledger.findVerdictsByCustomer(tenantId, customerId);
        List<CycleAssessmentRow> cycles = ledger.findCycleAssessmentsByCustomer(tenantId, customerId);

        java.util.Set<UUID> cyclesWithVerdict = new java.util.HashSet<>();
        for (VerdictRow v : verdicts) {
            cyclesWithVerdict.add(v.cycleId());
        }
        List<CycleAssessmentRow> suspended = new ArrayList<>();
        for (CycleAssessmentRow c : cycles) {
            if (!cyclesWithVerdict.contains(c.cycleId())) {
                suspended.add(c);
            }
        }
        return new CustomerVerdictHistory(verdicts, suspended);
    }

    /**
     * 某客户的判定历史：结论侧 + 挂起侧。
     *
     * @param verdicts  结论侧（{@code verdict} 行，按 {@code decided_at} 升序）
     * @param suspended 挂起侧（有依据而无结论的周期，按 {@code created_at} 升序）——
     *                  {@code branch} 恒为 {@link VerdictBranch#HUMAN_REVIEW}
     */
    public record CustomerVerdictHistory(
            List<VerdictRow> verdicts,
            List<CycleAssessmentRow> suspended) {

        public CustomerVerdictHistory {
            verdicts = List.copyOf(verdicts);
            suspended = List.copyOf(suspended);
        }

        /** 结论总数（不含挂起）。 */
        public int verdictCount() {
            return verdicts.size();
        }

        /** 挂起轮次总数。 */
        public int suspendedCount() {
            return suspended.size();
        }
    }

    /** 取单条判定（staff 侧详情；不存在抛 {@code NOT_FOUND}）。 */
    public VerdictRow detail(String tenantId, UUID verdictId) {
        VerdictRow row = ledger.findVerdict(tenantId, verdictId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "判定记录不存在: " + verdictId
                            + "（或不属于当前租户 —— 跨租户不可见由 RLS 承担，"
                            + "故此处不区分『不存在』与『不属于你』，避免成为租户枚举的探测口）");
        }
        return row;
    }

    // ==================================================================
    // 内部：组合出口的两处兜底（挂起态的语义收敛）
    // ==================================================================

    /**
     * 挂起态的处置：继续原方案（服务中；不产生对客户不利的推测）。
     *
     * <h2>🛑 为什么它不是一个"待定"值</h2>
     * 挂起意味着"数据不够 / 测量不可比 / 核心指标未录"，而不是"判定失败"。
     * 此刻客户仍在服务中，故唯一不会产生对客户不利推测的处置就是"继续原方案"。
     * 若给一个 {@code TBD} 或"待定"字符串，它既不在 {@link Disposition} 的值域里
     * （会让库层 CHECK 拒写），又会让"这一单现在该怎么办"没有答案 ——
     * 而 PRD §7.3 定调句要的是"该重定方案的不被拖过去"，
     * 挂起单的正当处置是"等人补数据"，而不是"把工单卡住"。
     *
     * <h2>挂起态与"依据快照里的 {@code disposition}"并不矛盾</h2>
     * 快照里同时记了 {@code branch=人工复核} 与 {@code disposition=继续原方案} ——
     * 前者说"这一单需要人补数据"，后者说"在补上之前客户照常服务"。
     * 两者是并行的事实，不是互相覆盖的结论。
     */
    private static Disposition dispositionForSuspended() {
        return Disposition.KEEP_PLAN;
    }

    /**
     * 组合出口的 risk 入参。
     *
     * <p>🛑 它<b>只</b>服务于组合出口（{@code resolveDisposition}）——
     * 挂起分支根本不走组合出口，故这里不存在"给挂起单编一个风险标签"的问题。
     * 兜底值是 {@link RiskFlag#NONE}，且仅当<b>路由结果不是 D4</b> 时才允许。
     * 若路由结果已经是 D4（全面评估），说明风险标签确实命中过，
     * 此时把 risk 兜成 NONE 会与分支自相矛盾。
     */
    private static RiskFlag effectiveRisk(RiskFlag risk, VerdictBranch branch) {
        if (risk != null) {
            return risk;
        }
        if (branch == VerdictBranch.FULL_ASSESSMENT) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "分支为『全面评估』（D4）但风险标签为空 —— D4 的触发条件就是"
                            + "标签 ∈ {高危,新发,同病}，两者不可能同时成立。"
                            + "该矛盾会让一次安全信号的成因在证据链里断掉");
        }
        // 挂起/其他分支且标签未录入：给 NONE 是【唯一的】安全兜底 ——
        // 因为"未录入"在路由阶段已被转成 D5（挂起），不会参与 D4 的触发判定。
        return RiskFlag.NONE;
    }

    // ==================================================================
    // 内部：依据快照
    // ==================================================================

    /**
     * 构造判定依据快照（进 {@code cycle_assessment.metric_snapshot} 与
     * {@code verdict.evidence_snapshot}，两处<b>同值</b>）。
     *
     * <h2>为什么两处用同一个 JSON 串</h2>
     * {@code cycle_assessment} 是"判定依据"、{@code verdict} 是"判定结论"，
     * 两者都带 {@code evidence_snapshot} 语义的列。用同一个串的理由是：
     * 若两处各造一份，"依据"与"结论引用的依据"会在某次改动后分叉 ——
     * 而分叉的两份都是"不可覆盖"的，等于永久留下两条互相矛盾的证据。
     *
     * <h2>🛑 快照里必须带 {@code TBD} 的两项</h2>
     * 显著阈值与回测门槛（PRD 未给值 / 人工金标准未产生）。把它们以
     * {@link ContractTbd#TBD} 写进快照，而不是省略 —— 省略会让复盘时
     * 无法区分"当时口径里没有这一项"与"当时忘了记"。
     *
     * <h2>🛑 S2-8 新增：{@code threshold_version_segments}（段级指纹）</h2>
     * 版本号只能回答"口径变了没变"。复盘要回答的是"<b>哪里</b>变了"——
     * "只是 {@code as_ops_weights} 动了"（不影响判定）与 "{@code pass_threshold} 动了"
     * （直接改变本次结论）是完全不同的两件事。故快照里同时落<b>段级指纹</b>。
     *
     * <p>🛑 它们是哈希，<b>不是</b>取值：足以定位是哪一段变了，但回答不了"门槛是多少"。
     * 这与 {@code DerivedRawConfig#toString()} 不打印取值是同一条纪律 ——
     * {@code evidence_snapshot} 是契约 F1 会下发给端侧的字段（{@code VerdictData} 六项之一）。
     *
     * <p>🛑 同理落进快照的还有 {@code same_origin_comparable}：
     * 它是路由的一条真实输入（D5 前置的判据），而在 S2-8 之前快照里<b>没有</b>它 ——
     * 于是回放时算不出与当时相同的输入，只能猜。补上它，回放才有确定的输入可用。
     */
    private String buildEvidenceSnapshot(CreateVerdictRequest req,
                                         EffectVerdictEngine.VerdictCandidate candidate,
                                         AdherenceEngine.AdherenceResult adherence,
                                         VerdictConfidenceEngine.ConfidenceResult confidence,
                                         VerdictBranchEngine.BranchResult routed,
                                         Disposition disposition,
                                         String thresholdVersion,
                                         boolean sameOriginComparable) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("as_refund", adherence.asValue() == null ? null : adherence.asValue().toPlainString());
        root.put("adherence_state", adherence.state().label());
        root.put("delta", candidate.delta() == null ? null : candidate.delta());
        root.put("improvement_rate",
                candidate.improvementRate() == null ? null : candidate.improvementRate().toPlainString());
        root.put("baseline_zero", candidate.baselineZero());
        root.put("branch", routed.branch().dbLabel());
        root.put("decision_rule", routed.decisionRule());
        root.put("risk_flag", req.riskFlag() == null ? null : req.riskFlag().dbLabel());
        root.put("core_metric_improved", req.coreMetricImproved());
        root.put("disposition", disposition.dbLabel());
        root.put("disposition_auto_raised", disposition.systemMayAutoRaise());
        root.put("confidence",
                confidence.confidence() == null ? null : confidence.confidence().toPlainString());
        root.put("confidence_suspended", confidence.suspended());
        root.put("threshold_version", thresholdVersion);
        // 🛑 S2-8：段级指纹进快照（漂移定位用；是哈希不是取值，见本方法注释）
        ObjectNode segments = root.putObject("threshold_version_segments");
        thresholdFingerprint.segmentFingerprints().forEach(segments::put);
        root.put("threshold_version_source",
                "服务端由当前口径剖面算出的内容寻址指纹（ThresholdVersionFingerprint）；"
                        + "🛑 不是调用方传入的自由字符串 —— 入参若携带则必须与之逐字相同，否则拒写");
        // 🛑 S2-8：路由的一条真实输入，S2-8 之前快照里没有它 ⇒ 回放只能猜（见本方法注释）
        root.put("same_origin_comparable", sameOriginComparable);
        root.put("reason", routed.reason());
        // 🛑 两项 TBD（硬纪律 #6：不得填数）
        root.put("significant_threshold", ContractTbd.TBD);
        root.put("backtest_gates", ContractTbd.TBD);
        // 🛑 表结构缺口显式记进快照（见 Disposition.SCHEMA_GAP_NOTE）
        root.put("schema_gap_note", Disposition.SCHEMA_GAP_NOTE);
        // 候选是否需人工
        root.put("requires_human", candidate.requiresHuman() || confidence.requiresHumanReview());
        root.put("suspend_reason",
                candidate.suspendReason() != null ? candidate.suspendReason() : confidence.suspendReason());
        return serialise(root);
    }

    /** 依从四维 → JSON（{@code as_dimensions_json}，{@code NOT NULL}）。 */
    private Map<String, Object> adherenceDimensions(CreateVerdictRequest req) {
        if (req.adherence() == null || req.adherence().dimensions() == null) {
            // 🛑 不得写一个空对象了事：空对象在库里是"有值"（NOT NULL 满足），
            //    而它的业务含义是"四维全缺" —— 那会让复盘无法区分
            //    "当时没录"与"当时录了但全不适用"。故显式记一个标记。
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_missing", true);
            out.put("_note", "依从四维未提供（调用方未传 adherence）—— 该次判定的 AS 无法回放");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        req.adherence().dimensions().forEach((code, di) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("applicable", di.applicable());
            one.put("value", di.value() == null ? null : di.value().toPlainString());
            one.put("structural_missing", di.structuralMissing());
            out.put(code, one);
        });
        out.put("_expected_days", req.adherence().expectedDays());
        return out;
    }

    private static String serialise(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // 依据快照序列化失败即拒写：一份无法回放的依据不得落库（PRD §C.1.9 硬约束②）
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "判定依据快照序列化失败，拒绝落库 —— "
                            + "PRD §C.1.9 硬约束②要求判定依据『必须落库、可回放』，"
                            + "一份无法回放的快照不应出现: " + e.getMessage());
        }
    }

    /**
     * F1 的产出 —— 落库结果 + 引擎中间产物（供控制器回显与测试断言）。
     *
     * @param disposition  组合出口（🛑 自动路径永不含"退款终止"）
     * @param evidenceJson 依据快照（与库中两处同值）
     * @param suspended    是否挂起（D5）。🛑 V8 起该态<b>也落 verdict 行</b>
     *                     （{@code confidence = null}），故它不再等于"没有结论行"；
     *                     判"是否落了一条判定"请用 {@link #verdictPersisted()}，
     *                     它现在恒为 {@code true}。本字段的含义收窄为
     *                     "本轮是系统按 D5 挂起处理的"（即：结论是'交给人'）。
     * @param attachedToExistingCycle 是否在<b>已存在</b>的周期评估上补全判定
     *                     （{@code true} = C4 先落过该行，本次只补判定侧四列；
     *                      {@code false} = 本次一次性落满依据 + 结论）。
     *                     供调用方与测试区分两阶段路径。
     */
    public record VerdictOutcome(
            UUID verdictId,
            UUID cycleId,
            VerdictBranchEngine.BranchResult routed,
            Disposition disposition,
            VerdictConfidenceEngine.ConfidenceResult confidence,
            EffectVerdictEngine.VerdictCandidate candidate,
            AdherenceEngine.AdherenceResult adherence,
            String evidenceJson,
            Instant decidedAt,
            boolean suspended,
            boolean attachedToExistingCycle) {

        /**
         * 挂起态下的处置入口（见 {@link #dispositionForSuspended()} 的说明）。
         *
         * <p>挂起仍返回处置而非 {@code null}：快照里 {@code disposition} 是一个
         * <b>必然有值</b>的字段（它进 JSON，不写就会让复盘分不清"没算"与"忘了记"）。
         */
        public Disposition resolvedDisposition() {
            return disposition;
        }

        /**
         * 本次是否真的落了一条判定结论 —— <b>V8 起恒为 {@code true}</b>。
         *
         * <p>🛑 本方法在 V8 之前是 {@code !suspended}：那时挂起态写不出
         * {@code verdict} 行（库层 {@code confidence NOT NULL} 夹逼），
         * 故"挂起"与"没有结论行"是同一件事。V8 放宽该列后两者<b>解耦</b>了 ——
         * 挂起也有一条结论行（{@code confidence = null} = "不可判"）。
         *
         * <p>保留本方法（而不是删掉它、让调用方直接知道"总有行"）的理由是：
         * 它是"结论行是否存在"的<b>唯一判据</b>。若将来某条路径真的不落结论行，
         * 改这一个方法即可，而所有调用点会自动跟上 ——
         * 反之若调用方各自假设"总有行"，那一天会有一批调用点静默读到
         * 一个不存在的 verdict_id。
         */
        public boolean verdictPersisted() {
            return true;
        }
    }

    // ==================================================================
    // 三、口径自描述（可断言"阈值不在代码里"）
    // ==================================================================

    /**
     * 版本号断言 —— S2-8 的核心收口点。
     *
     * <h2>三种输入的处置（每一支都有具体理由）</h2>
     * <ol>
     *   <li><b>未携带</b>（{@code null}）⇒ 返回服务端算出的指纹。
     *       推荐形态：调用方不需要知道版本号，服务端本就知道"这次按哪套口径算"。</li>
     *   <li><b>携带且与指纹逐字相同</b> ⇒ 返回它（等于指纹，无差别）。
     *       这是一次<b>自证</b>：老调用方按新口径传对了，链路可观测。</li>
     *   <li><b>携带但不一致</b> ⇒ 抛 {@link ErrorCode#VERSION_CONFLICT}。
     *       这是本方法存在的全部意义，且它堵的是一类具体的手法：
     *       <pre>
     *         调用方传一个"看起来是版本号"的串 ⇒ 旧实现原样落库 ⇒
     *         该结论在证据链里声称自己按某个版本产生，而那个版本不对应任何真实口径。
     *       </pre>
     *       🛑 用 {@code VERSION_CONFLICT(4001)} 而不是 {@code VALIDATION_FAILED(1001)}：
     *       这不是"参数格式不对"，而是"你所说的版本与当前权威版本不一致"——
     *       与硬约束③「版本不可覆盖」同族，语义上是一次版本冲突。
     *       用 1001 会让调用方去检查格式，而真正要做的是去查当前口径版本。</li>
     * </ol>
     *
     * <h2>🛑 绝不"以入参为准"</h2>
     * 即使入参看起来更"新"、更"具体"（例如它带了模块映射后缀），也一律以服务端算出的为准。
     * 因为服务端算出的那个是<b>由真实口径推出的</b>，而入参是<b>声明的</b>——
     * 用一个声明去覆盖一个事实，正是"版本号可以随便传"这一缺口本身。
     */
    String assertThresholdVersion(String requested) {
        String computed = thresholdFingerprint.version();
        if (requested == null) {
            return computed;
        }
        String r = requested.trim();
        if (computed.equals(r)) {
            return computed;
        }
        throw new BizException(ErrorCode.VERSION_CONFLICT,
                "threshold_version 与当前口径不一致：请求携带 '" + r
                        + "'，而当前口径算出的权威版本是 '" + computed + "'。"
                        + "🛑 本服务【不】按入参落库（那正是『版本号可随便传』这一缺口的成因），"
                        + "也不静默改用权威版本（那会让调用方以为自己指定成功了）。"
                        + "不一致即拒 —— 请去掉该字段让服务端填，或先确认当前口径版本。"
                        + "（PRD §C.1.9 硬约束②③：判定依据必须可回放、判定版本不可原地覆盖）");
    }

    /** 当前口径的权威版本号（供控制器自描述与测试断言）。 */
    public String currentThresholdVersion() {
        return thresholdFingerprint.version();
    }

    /** 当前口径指纹的完整自描述（🛑 不含任何口径取值 —— 见 {@link ThresholdVersionFingerprint}）。 */
    public Map<String, Object> describeThresholdVersion() {
        return thresholdFingerprint.describe();
    }

    /** 路由规则自描述（不含任何阈值数值 —— 避免成为配置值的第二份副本）。 */
    public Map<String, Object> describeRouting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callable_token_roles", CALLABLE_TOKEN_ROLES);
        m.put("denied_token_roles", List.of("client", "therapist"));
        m.put("rules", branchEngine.describeRules());
        m.put("branches", VerdictBranch.allDbLabels());
        m.put("dispositions", Disposition.allDbLabels());
        m.put("confidence_note",
                "置信度四项合成口径来自 config #45；本响应不含任何阈值 / 指数数值 —— "
                        + "一旦出现，它就会成为配置值的第二份副本，改配置不改此处时不报错");
        // 🛑 V8 起挂起【也落】结论行 —— 消费方必须知道这条，否则会把
        //    "挂起轮次在 verdicts[] 里出现"误读成"系统给客户下了结论"。
        m.put("suspended_persists_verdict_row", true);
        m.put("suspended_persistence_note",
                "D5 人工复核【也落】verdict 行（V8 起）：confidence = null（不可判，"
                        + "与 0.000「最低置信」结构不同）+ disposition 落库（继续原方案）。"
                        + "V8 之前该态写不出结论行（库层 confidence NOT NULL 夹逼）—— "
                        + "那使『人工复核』这个合法分支无法留下记录，是结构缺陷。"
                        + "落行后依据侧与结论侧的 branch 可逐行比对（同值）。"
                        + "🛑 历史挂起轮次不回溯补写，故 F2 的差集逻辑保留不动："
                        + "它现在同时覆盖『C4 待判定』与『V8 前的历史挂起』两种形态");
        // 🛑 两阶段模型（契约 C4 + F1）—— 消费方与端侧 Codegen 必须知道这条
        m.put("two_phase_model", Map.of(
                "stage1_C4", "POST /customers/{id}/cycle-assessments —— 只落依据，"
                        + "cycle.verdict IS NULL（待判定）",
                "stage2_F1", "POST /cycle-assessments/{id}/verdicts —— 补判定侧四列 + 落 verdict 行",
                "discriminator", "cycle_assessment.verdict 是否为 NULL",
                "idempotency", "cycle_id 是 PK ⇒ 一个周期一行；F1 重复判定抛 VERSION_CONFLICT(4001)"));
        m.put("two_phase_note",
                "契约把 C4 / F1 拆成两个 operationId，而两阶段共用 cycle_assessment 一张表。"
                        + "F1 在【已有】周期上补判定时，经 attachJudgment 只写判定侧四列"
                        + "（verdict / effect_verdict / improvement_rate / metric_snapshot），"
                        + "其 SQL 的 WHERE 谓词含 `AND verdict IS NULL` ⇒ 已判定的行影响 0 行，"
                        + "PRD §C.1.9 硬约束③「不可原地覆盖」仍然成立");
        m.put("schema_gap", Disposition.SCHEMA_GAP_NOTE);
        // 🛑 S2-8：版本号由服务端算出（不是入参），并把这条事实做成可读的运行时声明
        m.put("threshold_version", thresholdFingerprint.version());
        m.put("threshold_version_computed_server_side", true);
        m.put("threshold_version_note",
                "threshold_version 是服务端由当前口径算出的内容寻址指纹（"
                        + ThresholdVersionFingerprint.PREFIX + " + SHA-256 前 "
                        + "20 位十六进制），不是调用方传入的自由字符串。"
                        + "入参若携带则必须与之逐字相同，否则拒写 VERSION_CONFLICT(4001)");
        m.put("threshold_version_segments", ThresholdVersionFingerprint.SEGMENTS);
        return m;
    }
}