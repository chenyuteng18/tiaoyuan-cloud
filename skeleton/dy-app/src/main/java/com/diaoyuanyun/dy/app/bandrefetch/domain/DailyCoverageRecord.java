package com.diaoyuanyun.dy.app.bandrefetch.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <b>逐日覆盖意图</b> —— 一次"记录某手环某业务日的覆盖情况与缺口原因"的完整输入。
 *
 * <h2>🛑 它补的是什么：A3「观测率」的<b>直接取值来源</b></h2>
 * §2.8.7⑤ 明示：
 * <pre>
 *   分母 T = 台账应戴天（不得用「数据存在性」当分母，否则 A3 恒 1.0 静默虚高）
 *   分子   = 补拉历史天 + 当天中判为「有效佩戴天」的天数
 *   ② 客户同意但长期不戴 → 缺失天计 0（扣分）—— 唯一应扣的一类
 * </pre>
 * 而"某天到底算不算有效佩戴天"只能从<b>本表这一行</b>读出来：
 * {@code coverage_flag}（该日有没有数据）+ {@code gap_reason}（为什么没有）+
 * {@code is_wear}（设备自报的佩戴状态）+ {@code wear_minutes}（有效佩戴时长）。
 * 在 A-3 之前本表零写入方、真库零行 ⇒ <b>分子取不到值</b>，
 * A3 只能退化成 {@code applicable=False}。
 *
 * <h2>🛑🛑 本类的靶心：{@code not_worn} 与技术性 {@code is_wear} 的门禁（构造期阻断）</h2>
 * {@link GapReason#NOT_WORN} 是全 7 值里<b>唯一可扣分</b>的一类，
 * 而 §2.8.7⑤ 逐字要求：
 * <pre>
 *   `(-1,255)` = 技术性缺失 → **一律不判行为性（宁可少扣、不可错扣）**
 * </pre>
 * <p>故本类的规范构造器会<b>在构造期</b>拒绝"技术性 {@code isWear} 配 {@code not_worn}"
 * 这一组合，与 {@code V22} 的 (5c) <b>逐条一致</b>。
 *
 * <h3>🛑🛑 为什么"校验两遍"（Java 一遍、库一遍）而不是"只留库层"</h3>
 * <ul>
 *   <li><b>库层那道必须在</b>：函数可以被人绕过（直接写 SQL），
 *       而这一条<b>刻意不是表约束</b> —— 它不是"某列取值非法"，
 *       而是"两列取值的<b>组合</b>非法"，写成语义清晰的表约束需要一条
 *       {@code CHECK (NOT (gap_reason='not_worn' AND is_wear IN (-1,255)))}，
 *       那会让现存的 {@code RlsV5EntityIsolationTest} 空值探针（只填 {@code tenant_id}）
 *       撞上一条与它无关的约束报 23514，产生一条<b>归因完全错误的红</b>。
 *       故库层门禁的唯一载体就是那个函数；</li>
 *   <li><b>Java 这道也要在</b>：它把这条门禁从一个<b>运行期 SQL 异常</b>
 *       提前成一个<b>可读的、在调用前就发生的</b>参数错误，
 *       并且它是 {@link WearState#contradictsNotWorn()} 那套类型分野的<b>执行者</b> ——
 *       若没有这一层，"技术性 / 行为性"的区别就只存在于枚举的 {@code nature} 字段里，
 *       而没有任何东西按它行动（一个只被声明、不被使用的分野，迟早会漂移）。</li>
 * </ul>
 * <p>🛑 两处校验的措辞刻意<b>不共享常量</b>（各自写自己的消息）：它们服务不同的读者
 * （一个是应用日志 / 接口响应，一个是库迁移日志 / psql）。但
 * <b>判据必须一致</b>，而这一点由 {@code BandRefetchGateTest} 机械核对
 * （枚举 ↔ V22 的 {@code v_tech_iswear} 数组）。
 *
 * <h2>🛑 为什么本类【不】校验"人确实没戴"这条前置</h2>
 * §2.8.7⑤ 第②行的判据是<b>两条合取</b>：
 * <pre>
 *   有前置有效记录  ∧  有脱腕记录 isWear=0 / 长段规律性零数据  ∧  无技术性信号
 * </pre>
 * 后一条由本类与 V22 的 (5c) 守。而前两条需要<b>历史上下文</b>
 * （"有没有前置有效记录"要回看前两天、甚至是"长段规律性"要看完一段窗口），
 * 而本类只拿到<b>单日事实</b>。
 * <p>🛑 把它显式留给<b>调用方 / 未来的判定引擎</b>，而不是在这里硬写一个
 * {@code if (gapReason == NOT_WORN && !hasPriorValidRecord)} —— 后者需要本类
 * 去查询历史，那会让一个"值对象"变成"带 IO 的东西"，
 * 而值对象的全部价值就在于它<b>无副作用、可独立构造、可被单测</b>。
 * <p>⇒ 本类保证的是"<b>不会把技术性写成行为性</b>"，
 * 而<b>不</b>保证"任何写成 {@code not_worn} 的都真的成立"。这条边界必须写清。
 *
 * <h2>🛑 为什么 {@code wear_minutes} 与 {@code effective_wear_minutes} 是两个字段</h2>
 * 它们<b>不是</b>同义词，也不是"原始值 / 有效值"这种一般意义上的派生关系：
 * §2.8.7⑤ 的分母口径是「<b>台账应戴天</b>」，而"应戴"的判定在
 * {@code band.pause_period_json}（合规摘除期）与"是否佩戴"之间。
 * 把两者合并成一列，会让"合规摘除期内的时长"无法被剔除 ——
 * 而那是 §2.8.7⑤ 附表明确要求「暂停期<b>从分母剔除</b> + 不扣分」的落点。
 * <p>🛑 本类<b>不</b>计算 {@code effectiveWearMinutes}（不在这里减 {@code pause_period}）：
 * 那需要读 {@code band} 表，属"跨表推导"，不是值对象的职责。
 * 两列的<b>差值</b>正是"合规摘除剔除量"的可观测面，由调用方填、由审计核对。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 不另写一份白名单正则。理由与 {@code DeviceRecord} / {@code CaseArchiveRecord} /
 * {@code SyncProbeRecord} 逐字相同：两处口径会各自漂移，"哪个更严"取决于谁先跑。
 *
 * @param tenantId            覆盖记录所属租户（必填）
 * @param coverageId          覆盖记录主键（必填）；🛑 它<b>不是</b> upsert 的冲突键
 *                            （那是 {@code (device_id, date)}）—— 它只是"首次插入那一行"的身份
 * @param deviceId            手环（必填）；必须是<b>本租户内</b>存在的 {@code band}
 * @param customerId          归属客户（必填）；必须是<b>本租户内</b>存在的 {@code customer}。
 *                            🛑 它同时是<b>行级 scope 的承载者</b>：本表内没有 {@code store_id}，
 *                            可见范围随客户走
 * @param date                业务日（必填）；🛑 它是 upsert 冲突键的另一半，
 *                            也是 A3 口径里"应戴天"的唯一载体
 * @param coverageFlag        该日是否有数据（可空）；{@code NULL} = 缺失/<b>严禁补 0</b>、
 *                            {@code TRUE} = 该日有数据、{@code FALSE} = 明确判定缺失
 * @param gapReason           缺口原因（可空）；{@link GapReason} 的 7 值之一
 * @param isWear              设备自报佩戴状态（可空）；{@link WearState} 的 4 值之一
 * @param wearMinutes         当日佩戴时长（分钟，可空）
 * @param effectiveWearMinutes 剔除合规摘除后的有效佩戴时长（分钟，可空）
 * @param nAtThatTime         该日补拉时使用的 N（可空）；🛑 它是<b>证据</b>
 *                            （"这条覆盖是按哪个 N 补拉的"），§2.8.7③ 要求留痕
 * @param sourceSyncLogId     由哪次同步回捞（可空）；🛑 指向一条看不见的日志
 *                            会让 §2.8.7③「断点续传」的回溯链断开
 * @param createdBy           建档者标识（可空；库层回落 {@code band-refetch}）
 */
public record DailyCoverageRecord(
        String tenantId,
        UUID coverageId,
        UUID deviceId,
        UUID customerId,
        LocalDate date,
        Boolean coverageFlag,
        GapReason gapReason,
        WearState isWear,
        Integer wearMinutes,
        Integer effectiveWearMinutes,
        Integer nAtThatTime,
        UUID sourceSyncLogId,
        String createdBy) {

    /** {@code band_daily_coverage.created_by} 的上限（{@code VARCHAR(128)}）。 */
    private static final int CREATED_BY_MAX = 128;

    /**
     * 规范构造器 —— 形态校验与<b>核心门禁</b>都在这里，使"一个非法的覆盖意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service：构造器是<b>唯一</b>的入口。
     * 放在 Service 里意味着"只要有人绕过 Service 直接 new 一个 record"
     * 就能拿到非法值 —— 而那正是这个 record 存在的意义。
     * <p>🛑 在本域这个理由比别处更硬：这一层的失败形态是
     * <b>"客户被错扣一天"</b>，而错扣是<b>事后不会报错</b>的 ——
     * 它已经写进库里，A3 已经算过了，退款金额已经定了。
     * 唯一的拦截时机就是<b>构造那一刻</b>。
     */
    public DailyCoverageRecord {
        BandLedger.validateTenantId(tenantId);

        if (coverageId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：coverage_id 必填 —— 它是 band_daily_coverage 表主键。"
                            + "🛑 注意：它【不是】upsert 的冲突键（那是 (device_id, date)）——"
                            + "它只是『首次插入那一行』的身份；覆盖时不改写（见 V22 的 (7)）");
        }
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：device_id 必填 —— band_daily_coverage.device_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, device_id) → band(tenant_id, band_id) 的一端。"
                            + "🛑 它同时是 upsert 冲突键的一半 —— 为空则『同日覆盖』无从锚定");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：customer_id 必填 —— band_daily_coverage.customer_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, customer_id) → customer 的一端。"
                            + "🛑 它承载【行级 scope】（coverage 表内没有 store_id，可见范围随客户走）——"
                            + "为空等于这条覆盖记录没有归属，A3 的『应戴天』分母也就无从按客户归集");
        }
        if (date == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：date 必填 —— band_daily_coverage.date 是 NOT NULL。"
                            + "🛑 它是 upsert 冲突键的另一半，也是 A3 口径里『业务日』的唯一载体 ——"
                            + "§2.8.7⑤ 明示分母 = 『台账应戴天』，而『天』就是这一列");
        }
        if (nAtThatTime != null && nAtThatTime < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：n_at_that_time = " + nAtThatTime
                            + " 非法 —— N 是留存窗口天数，不可为负。"
                            + "§2.8.7③ 要求『N 随探测刷新，须留痕』，故它是【证据】"
                            + "（『这条覆盖是按哪个 N 补拉的』）");
        }
        if (wearMinutes != null && wearMinutes < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：wear_minutes = " + wearMinutes + " 非法 —— 时长不可为负");
        }
        if (effectiveWearMinutes != null && effectiveWearMinutes < 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "逐日覆盖登记：effective_wear_minutes = " + effectiveWearMinutes
                            + " 非法 —— 时长不可为负");
        }
        if (createdBy != null) {
            createdBy = createdBy.trim();
            if (createdBy.isEmpty()) {
                createdBy = null;
            } else if (createdBy.length() > CREATED_BY_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "逐日覆盖登记：created_by 长度 " + createdBy.length()
                                + " 超过 band_daily_coverage.created_by 的 VARCHAR("
                                + CREATED_BY_MAX + ")。🛑 在 Java 侧拦是因为库层的失败形态是一条 22001，"
                                + "它会以『数据截断』的面目出现在日志里，而不是『参数太长』");
            }
        }

        // ==============================================================
        // 🛑🛑 核心门禁（与 V22 的 (5c) 逐条一致）—— 本迁移的靶心
        // ==============================================================
        checkNotWornVersusTechnicalWear(gapReason, isWear);

        // ==============================================================
        // 逻辑自洽门禁（与 V22 的 (5d) 逐条一致）
        // ==============================================================
        checkFlagVersusGapReason(coverageFlag, gapReason);
    }

    /**
     * 🛑🛑 <b>核心门禁</b>：{@code not_worn} 不得与<b>技术性</b> {@code is_wear} 共存。
     *
     * <h2>依据（§2.8.7⑤ 逐字）</h2>
     * <pre>
     *   `(-1,255)` = 技术性缺失 → **一律不判行为性（宁可少扣、不可错扣）**，
     *   并须向厂商索要「设备异常原因码」把「传感器失败」与「客户脱腕」分开
     * </pre>
     *
     * <h2>🛑 为什么这条由<b>写入侧</b>守（而不是取数侧记得排除）</h2>
     * <ul>
     *   <li>{@code not_worn} 是<b>唯一可扣分</b>的缺口原因；一旦"技术性缺失"被写成
     *       {@code not_worn}，A3 就会<b>扣客户的分</b>，而 A3 进「依从性」维度、
     *       并进一步影响 {@code AS_refund} —— 这是本仓反复记载的「<b>对客户不利</b>」形态；</li>
     *   <li>取数侧"记得排除"是一个<b>会随调用点增多而失效</b>的约定：
     *       每新增一条读路径就要重记一次，而漏记<b>不会报错</b>。
     *       把这一行数据<b>挡在库外</b>，则"排除"这件事<b>永远不需要被记得</b>。</li>
     * </ul>
     * <p>⇒ 一句话：<b>不可扣的形态不该存在于库里</b>，而不是"存在于库里但取数时跳过"。
     *
     * <h2>🛑 只判"技术性"，不判"是否佩戴"</h2>
     * 本方法<b>不</b>要求 {@code not_worn} 时必须 {@code isWear == REMOVED}：
     * {@code is_wear} 可空（设备未给出），而 §2.8.7⑤ 第②行允许
     * 「<b>长段规律性零数据</b>」作为替代信号。若在这里强制要求 {@code isWear=0}，
     * 就是<b>替上游加了一条比 PRD 更严的规则</b>，会让合法的 {@code not_worn}
     * 写不进去 —— 即从"宁可少扣"翻转成"宁可错扣"。方向反了。
     * <p>🛑 这也正是 {@code V22} 的 (5c) 只写 {@code p_is_wear = ANY(v_tech_iswear)}
     * 而不写 {@code p_is_wear <> 0} 的原因 —— 两处判据必须一致。
     */
    private static void checkNotWornVersusTechnicalWear(GapReason gapReason, WearState isWear) {
        if (gapReason == null || !gapReason.meansCustomerDidNotWear()) {
            return;
        }
        if (isWear == null || !isWear.contradictsNotWorn()) {
            return;
        }
        throw new BizException(ErrorCode.GATE_MISSING,
                "逐日覆盖登记被阻断：gap_reason = not_worn 与 is_wear = " + isWear.code()
                        + "（" + isWear.name() + "，技术性缺失）不能共存。"
                        + "依据 = §2.8.7⑤ 逐字：「`(-1,255)`=技术性缺失 → "
                        + "一律不判行为性（宁可少扣、不可错扣）」。"
                        + "🛑 not_worn 是全 7 值里唯一可扣分的一类（『缺失天计 0』）；"
                        + "把 is_wear 的『技术性』取值配上 not_worn，等于把设备侧故障记成客户没戴 —— "
                        + "A3 会扣分、依从性会下降、并影响 AS_refund，"
                        + "而这一切【事后不会报错】：它已经写进库里、已经算过了。"
                        + "正确处置：技术性缺失请写 gap_reason = 'involuntary_technical'"
                        + "（或 'beyond_retention_window' / 'sync_failed'）。"
                        + "⚠️ 附：更细的『传感器失败 vs 客户脱腕』区分，须向厂商索要"
                        + "【设备异常原因码】（§2.8.7⑤ 末句，属外部依赖，尚未取证）—— "
                        + "故此处取【保守方向】（宁可少扣）"
                        + "。🛑 报 GATE_MISSING(2002, 403)：本域不是『参数格式错』，"
                        + "而是『这条数据格客户带来的后果不可接受』");
    }

    /**
     * 逻辑自洽门禁：「有数据」与「有缺口原因」<b>互斥</b>（与 V22 的 (5d) 逐条一致）。
     *
     * <h2>🛑 这不是上游口径，是本表的自洽要求</h2>
     * PRD 未逐字定义二者的配对，但：
     * <pre>
     *   coverage_flag = TRUE 表示「该日有数据」（V5 第 999 行注释逐字：true = 该日有数据）
     *   gap_reason 表示「为什么没有数据」
     * </pre>
     * ⇒ 同时成立等于说"有数据"<b>并且</b>"为什么没数据"，
     * 会让 A3 的取数逻辑出现<b>两条互斥的分支同时命中</b>。
     * <p>🛑 <b>反向不成立</b>（故只断言单向）：{@code coverage_flag IS NULL}
     * （缺失 / 未判定）与 {@code FALSE}（明确判定缺失）都<b>可以</b>配 {@code gap_reason}。
     * 一个"双向断言"会把 {@code NULL} 这个合法状态判成非法 ——
     * 而 {@code NULL}（= 缺失，<b>严禁补 0</b>）正是 §4.3 的核心纪律之一。
     */
    private static void checkFlagVersusGapReason(Boolean coverageFlag, GapReason gapReason) {
        if (Boolean.TRUE.equals(coverageFlag) && gapReason != null) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "逐日覆盖登记被阻断：coverage_flag = TRUE（该日有数据）与 gap_reason = '"
                            + gapReason.code() + "' 不能共存。"
                            + "『有数据』与『为什么没数据』是互斥的两个陈述；"
                            + "同时成立会让 A3 取数时两条互斥分支同时命中。"
                            + "若该日确有数据而只是部分缺失，请用 coverage_flag = NULL "
                            + "表达『未判定 / 部分』，而不是 TRUE。"
                            + "🛑 只断言单向 —— coverage_flag 为 NULL / FALSE 时都【可以】配 gap_reason："
                            + "NULL = 缺失（§4.3『严禁补 0』），FALSE = 明确判定缺失");
        }
    }

    /**
     * 便捷构造：只给必填四项 + 判定结果四项（覆盖 flag / 原因 / 佩戴态 / 当日 N）。
     *
     * <p>省掉一串 {@code null} 实参 —— 本仓对这种"全是 null 的尾巴"有过教训：
     * 调用点上一串裸 {@code null} 无法自证"这一个 null 是哪一个字段"。
     * <p>🛑 刻意<b>不</b>提供 {@code of(...)} 那种"只给必填"的超短重载：
     * 本 record 的三个可选尾巴（{@code wearMinutes} / {@code effectiveWearMinutes} /
     * {@code sourceSyncLogId}）都<b>承载 A3 的取数口径</b>，
     * 一个"不用填它们"的便捷入口会让调用方<b>默认走那条更省事的路</b>，
     * 而缺的正是"合规摘除剔除量"与"由哪次同步回捞"这两个可观测面。
     * 必填项无值可空，可选尾巴必须被<b>显式</b>写成 {@code null} 才能略过。
     */
    public static DailyCoverageRecord of(String tenantId, UUID coverageId, UUID deviceId,
                                        UUID customerId, LocalDate date, Boolean coverageFlag,
                                        GapReason gapReason, WearState isWear, Integer nAtThatTime) {
        return new DailyCoverageRecord(tenantId, coverageId, deviceId, customerId, date,
                coverageFlag, gapReason, isWear, null, null, nAtThatTime, null, null);
    }

    /**
     * 🛑 <b>这一行按 A3 口径是否"可扣分"</b> —— 供调用方在落库前自问。
     *
     * <p>判据<b>只看</b> {@link GapReason#chargeable()}，<b>不看</b> {@code isWear}：
     * 后者已经在构造期被用于门禁（技术性不得配 {@code not_worn}），
     * 若在这里再判一次，就形成"两处各自的扣分口径"，
     * 而它们的差别不会报错 —— 只会在某一天体现为"同一行两边结论不同"。
     * <p>🛑 它<b>不</b>包含"是否有前置有效记录"那两条前置（见类注释）——
     * 故它的语义是"<b>这条数据按登记的原因属于可扣分类</b>"，
     * 而<b>不是</b>"应当扣客户这一天"。用名字把这条差别钉住：
     * 若需要后者，那是一次<b>跨日判定</b>，属于另一条链路。
     */
    public boolean chargeableByGapReason() {
        return gapReason != null && gapReason.chargeable();
    }
}