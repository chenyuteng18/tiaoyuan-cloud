package com.diaoyuanyun.dy.app.archive.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * <b>结案归档意图</b> —— 一次"把一张退款工单收口成归档档案"的完整输入。
 *
 * <h2>🛑 它补的是什么：为什么"归档"这件事在系统里本来无法发生</h2>
 * 在 B-13 之前，{@code case_archive} 在生产代码里零写入方、真库零行
 * （实测 {@code count(*) = 0}）。与前三批（{@code band} / {@code device} / {@code scale}）
 * 不同的是，本表的后果<b>不是"某条链 23503"</b>，而是：
 * <pre>
 *   PRD P0-14 逐字：「<b>四种结局全部强制归档</b>」
 *   PRD P0-25 逐字：「实现 C.3 全部 14 条规则」
 *   PRD C.1.7 逐字：「06.§八 归档 6 项 → case_archive.archive_checklist | 是
 *                    | <b>缺项→403 阻断结案</b>」
 * </pre>
 * ⇒ <b>「归档」这个动作本身无法发生</b>：退款工单走到 {@code 终止} 之后
 * 没有任何合法路径把它收口成 {@code 归档}，于是「全部强制归档」在系统里
 * <b>没有对应的实现</b>。
 * <p>本仓已把这件事逐字写在代码里（两份证据，都不是推断）：
 * <ol>
 *   <li>{@code RefundWorkOrderService} 类注释的"本类不做的事"表：
 *       「归档落 {@code case_archive} | 待落地（S2-5 之后）|
 *         归档要同时落六项清单与客户签字，属独立链路」；</li>
 *   <li>{@code InMemoryRefundWorkOrderPort.markArchivedForTest} 的注释：
 *       「生产侧的归档动作（落 {@code case_archive} + 结案清单校验）尚未落地……
 *         于是『归档后只读』这条性质<b>在测试里无从构造</b> ——
 *         而它是『全部强制归档』的下半句。本方法站在那条未来路径的位置上」。</li>
 * </ol>
 * ⇒ 即：<b>"归档后只读"这条性质此前只在测试替身里成立</b>。
 *
 * <h2>🛑🛑 本类在构造期就【阻断】硬门禁缺项 —— 且这是刻意的</h2>
 * {@link #CaseArchiveRecord} 的规范构造器会跑一遍门禁校验，规则与迁移
 * V20 函数内<b>逐条一致</b>（5 项硬门禁须为 {@code true}；警告项可缺、可为 false）。
 * <p>🛑 为什么"校验两遍"（Java 一遍、库一遍）而不是"只留库层"：
 * <ul>
 *   <li><b>库层那道必须在</b>：函数可以被人绕过（直接写 SQL），
 *       而 {@code case_archive} 的 6 项门禁<b>刻意不是表约束</b>
 *       （见 V20 文件头「核心机制四」：表约束会让
 *        {@code RlsV5EntityIsolationTest} 那条用空清单探针的门禁报 23514，
 *        一条归因完全错误的红）。故库层门禁的唯一载体就是那个函数；</li>
 *   <li><b>Java 这道也要在</b>：它把"缺项"从一个<b>运行期 SQL 异常</b>
 *       提前成一个<b>可读的、在调用前就发生的</b>参数错误，
 *       并且它是 {@code CaseArchiveChecklist} 那套类型分野的<b>执行者</b> ——
 *       若没有这一层，"硬门禁 / 警告"的区别就只存在于枚举的 {@code gate()} 字段里，
 *       而没有任何东西按它行动（一个只被声明、不被使用的分野，迟早会漂移）。</li>
 * </ul>
 * <p>🛑 两处校验的措辞刻意<b>不共享常量</b>（各自写自己的消息）：它们服务不同的读者
 * （一个是应用日志/接口响应，一个是库迁移日志/psql）。若强行统一，
 * 下一个人改一处会意外改掉另一处 —— 这是本仓"同一件事只有一处定义"的
 * <b>有意的例外</b>，理由是"它们不是同一件事"（一个校验输入、一个守住库层事实）。
 * 但<b>键集与门禁强度必须一致</b>，而这一点由
 * {@code CaseArchiveGateTest} 机械核对（枚举 ↔ V20 函数内数组）。
 *
 * <h2>🛑🛑 为什么本类【不】校验 {@code finalConclusion} 的"退款终止时必填"</h2>
 * PRD C.1.7 逐字写「{@code case_archive.final_conclusion / refund_amount / amount_basis} |
 * 否 | <b>退款终止时必填</b>」。
 * <p>而本类<b>拿不到 refund</b>：{@code refund} 与 {@code case_archive}
 * <b>没有任何关联列</b>（实测：{@code case_archive} 的 3 条约束里既没有
 * {@code refund_id}，也没有反向列；{@code refund} 的 20 列里也没有 {@code archive_id}）。
 * ⇒ 本类<b>没有任何输入</b>能回答"这次是不是退款终止"。
 * <p>🛑 把它<b>显式留给服务层</b>，而不是在这里硬写一个
 * {@code if (finalConclusion == null) throw} —— 后者等价于
 * <b>要求一切归档都必须填结论</b>（包括"继续"之后归档的），
 * 而那是一条<b>比 PRD 更严的、代拍出来的</b>规则。
 * <p>这正是「{@code refund ↔ case_archive} 无关联列」这条缺口的<b>具体表现</b>，
 * 不是一个可以用"加个 IF"绕过去的细节。见 V20 文件头「待裁登记」第 3 条。
 *
 * <h2>🛑 为什么 {@code effectConfirmPdf} 不在本记录里（有意留出的零写入列）</h2>
 * {@code case_archive} 有 {@code effect_confirm_pdf VARCHAR(512)} 一列。
 * 本记录<b>有意不收它</b>，理由不是"漏了"：它是<b>文件引用</b>，
 * 而文件存储通路尚未选定（缺口修复总规划的 G-C 待裁项）。
 * <p>收一个"不知道指向哪里"的字符串入参，会让这一列<b>看起来被填了</b>，
 * 而实际填进去的是一个无法解析的引用 —— 本仓反复要防的"死字段"形态。
 * <p>🛑 于是 {@code effect_confirm_pdf} 在本迁移之后仍然是一个<b>零写入列</b>。
 * 这是<b>已知且登记过的</b>（README 缺口清单 + V20 文件头），不是静默留下的缺口。
 * 与之相对 {@code metricsTrend} <b>收</b>：它是归档那一刻的趋势快照，
 * 属归档动作天然的输入，不依赖任何未定通路 ——
 * 两者被区别对待的理由是"是否依赖未定通路"，不是"谁更重要"。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 不另写一份白名单正则。理由与 {@code DeviceRecord} / {@code BandBindingLedger}
 * 逐字相同：两处口径会各自漂移，"哪个更严"取决于谁先跑。
 * 复用的是<b>同一个</b>租户标识形态校验 —— 这是"同一件事只有一处定义"的应用。
 *
 * @param tenantId               归档所属租户（必填）
 * @param archiveId              归档档案主键（必填）；🛑 它是<b>全局</b>主键，
 *                               故"一份归档档案的一生只属于一个租户"
 * @param customerId             归档对象客户（必填）；必须是<b>本租户内</b>存在的客户。
 *                               🛑 它同时是<b>行级 scope 的承载者</b>：
 *                               {@code case_archive} 表内没有 {@code store_id}，
 *                               归档档案的可见范围随客户走
 * @param checklist              归档清单 6 项（必填）；
 *                               5 项硬门禁须为 {@code true}，1 项警告（手环）可缺 / 可为 false
 * @param staffSigns             签名块 4 键（必填）；键须齐备且值非空串
 * @param finalConclusion        最终结论（可空）；🛑 "退款终止时必填"由服务层校验 —— 见类注释
 * @param metricsTrend           指标趋势快照 JSON（可空）
 * @param desensitizeAuthorized  脱敏是否已单独授权（必填，默认 false）；
 *                               §2.22 逐字：「脱敏须<b>单独授权</b>」
 * @param createdBy              建档者标识（可空；库层回落为 {@code case-archive-registration}）
 */
public record CaseArchiveRecord(
        String tenantId,
        UUID archiveId,
        UUID customerId,
        Map<String, Boolean> checklist,
        Map<String, String> staffSigns,
        String finalConclusion,
        String metricsTrend,
        boolean desensitizeAuthorized,
        String createdBy) {

    /** {@code case_archive.final_conclusion} 的上限（{@code VARCHAR(64)}）。 */
    private static final int CONCLUSION_MAX = 64;

    /** {@code case_archive.created_by} 的上限（{@code VARCHAR(128)}）。 */
    private static final int CREATED_BY_MAX = 128;

    /**
     * 规范构造器 —— 形态与门禁校验都在这里，使"一个非法的归档意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service：构造器是<b>唯一</b>的入口。
     * 放在 Service 里意味着"只要有人绕过 Service 直接 new 一个 record"
     * 就能拿到非法值 —— 而那正是这个 record 存在的意义
     * （它把"意图"变成一个<b>自校验</b>的值对象）。
     */
    public CaseArchiveRecord {
        BandLedger.validateTenantId(tenantId);

        if (archiveId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结案归档：archive_id 必填 —— 它是 case_archive 表主键，"
                            + "且是本原语幂等判定的唯一键。没有它，重放无法与『新建』区分");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结案归档：customer_id 必填 —— case_archive.customer_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, customer_id) → customer 的一端。"
                            + "🛑 它同时是行级 scope 的承载者：本表内没有 store_id，"
                            + "归档档案的可见范围随客户走");
        }
        if (checklist == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结案归档：archive_checklist 必填 —— 它是 P0-25 归档门禁的唯一载体");
        }
        if (staffSigns == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结案归档：staff_signs 必填 —— §2.22 逐字要求『归档须客户签字』的落纸载体");
        }

        checklist = normalizeChecklist(checklist);
        staffSigns = normalizeSigns(staffSigns);

        if (finalConclusion != null) {
            finalConclusion = finalConclusion.trim();
            if (finalConclusion.isEmpty()) {
                // 空串与 null 在 DDL 里都合法，但语义上"我传了个空串"与"我没传"是两件事。
                // 归一成 null —— 与 V20 函数里 nullif(btrim(...), '') 的口径一致
                // （两处都要归一，否则会出现"Java 说是空串、库里是 NULL"的差异）。
                finalConclusion = null;
            } else if (finalConclusion.length() > CONCLUSION_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "结案归档：final_conclusion 长度 " + finalConclusion.length()
                                + " 超过 case_archive.final_conclusion 的 VARCHAR("
                                + CONCLUSION_MAX + ")。🛑 在 Java 侧拦是因为库层的失败形态是一条 22001，"
                                + "它会以『数据截断』的面目出现在日志里，而不是『参数太长』");
            }
        }
        if (metricsTrend != null && metricsTrend.isBlank()) {
            metricsTrend = null;
        }
        if (createdBy != null) {
            createdBy = createdBy.trim();
            if (createdBy.isEmpty()) {
                createdBy = null;
            } else if (createdBy.length() > CREATED_BY_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "结案归档：created_by 长度 " + createdBy.length()
                                + " 超过 case_archive.created_by 的 VARCHAR(" + CREATED_BY_MAX + ")");
            }
        }
    }

    /**
     * 清单归一 + 门禁校验（构造器专用）。
     *
     * <h2>🛑 这里就是「手环不得反转为阻断」的 Java 侧落点</h2>
     * 遍历 5 项硬门禁要求 {@code true}；手环项<b>只查"值是不是 true/false 之外的怪东西"</b>
     * （Java 侧类型已保证是 {@code Boolean}，故实际上这里对它是<b>放行</b>）。
     * <p>🛑 为什么不像 V20 那样再查一次"值必须是布尔"：
     * Java 的 {@code Map<String, Boolean>} 在<b>编译期</b>就保证了这一点 ——
     * 在运行期再查一次是<b>不可达代码</b>，而不可达的守卫比没有守卫更坏
     * （它让人以为那里被守住了）。类型系统承担的部分，不该用运行期断言重复。
     * 库层那一道（V20 的 5c）仍然要查，因为库层收到的是无类型的 JSONB。
     */
    private static Map<String, Boolean> normalizeChecklist(Map<String, Boolean> in) {
        // 未知键 ⇒ 抛（与 ConsentAuthScope 同族，但本清单连"缺键"都不合法）
        for (String k : in.keySet()) {
            CaseArchiveChecklist.of(k);      // 未登记即抛，绝不回落
        }

        Map<String, Boolean> out = new TreeMap<>();
        Set<String> missingHard = new LinkedHashSet<>();
        for (CaseArchiveChecklist item : CaseArchiveChecklist.hardBlocking()) {
            Boolean v = in.get(item.code());
            if (v == null) {
                missingHard.add(item.code() + "（" + item.arcRule() + "·" + item.label() + "）");
            } else if (!v) {
                missingHard.add(item.code() + "（" + item.arcRule() + "·" + item.label()
                        + " = false）");
            }
            out.put(item.code(), Boolean.TRUE.equals(v));
        }
        if (!missingHard.isEmpty()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "结案归档被阻断：归档清单【硬门禁缺项】-> " + String.join(", ", missingHard)
                            + "。口径来源 = PRD C.3（逐条判定为『硬阻断』的那 5 条 ARC 规则）"
                            + " + C.1.7『缺项→403 阻断结案』 + P0-25『硬阻断规则缺项 → "
                            + "后端返回 403 且给出缺失项名称』。"
                            + "🛑 应用层抛的是 GATE_MISSING(2002, 403) —— 与 P0-25 逐字要求一致；"
                            + "消息里带上缺项名称，使调用方能自己定位是哪一项");
        }

        // 警告项（手环）：键可缺、值可为 false —— 🛑 一律不阻断
        for (CaseArchiveChecklist item : CaseArchiveChecklist.warnings()) {
            out.put(item.code(), Boolean.TRUE.equals(in.get(item.code())));
        }
        return Map.copyOf(out);
    }

    /**
     * 签名块归一 + 校验（构造器专用）。
     *
     * <p>🛑 与清单的关键差别（见 {@link CaseArchiveSignKey}）：值是<b>文本</b>，
     * 故"空串"必须被拒 —— 一个空白的"门店负责人"不是签字。
     * DDL 的 {@code NOT NULL} 只保证 {@code staff_signs} 整体不为空，
     * 挡不住它里面是 {@code {}} 或 {@code {"store_owner":""}}。
     */
    private static Map<String, String> normalizeSigns(Map<String, String> in) {
        for (String k : in.keySet()) {
            CaseArchiveSignKey.of(k);        // 未登记即抛
        }

        Map<String, String> out = new TreeMap<>();
        List<String> missing = new java.util.ArrayList<>();
        for (CaseArchiveSignKey k : CaseArchiveSignKey.all()) {
            String v = in.get(k.code());
            if (v == null || v.isBlank()) {
                missing.add(k.code() + "（" + k.label() + "）");
                continue;
            }
            out.put(k.code(), v.trim());
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "结案归档被阻断：签名块【缺项或为空】-> " + String.join(", ", missing)
                            + "。PRD C.1.7 逐字：case_archive.staff_signs = "
                            + "json{经办人,经络师,门店负责人,日期} | 是；"
                            + "§2.22 逐字：『归档须客户签字』。"
                            + "🛑 空串与缺键一并拒绝 —— 一个空白的『门店负责人』不是签字，"
                            + "而一份没有责任人签名的归档档案，在举证场景里等同于"
                            + "『没人对这次结案负责』");
        }
        return Map.copyOf(out);
    }

    /**
     * 便捷构造：不含可选尾巴（结论 / 趋势 / 建档者）。
     *
     * <p>省掉一串 {@code null} 实参 —— 本仓对这种"全是 null 的尾巴"有过教训：
     * 调用点上一串裸 {@code null} 无法自证"这一个 null 是哪一个字段"。
     */
    public static CaseArchiveRecord of(String tenantId, UUID archiveId, UUID customerId,
                                      Map<String, Boolean> checklist,
                                      Map<String, String> staffSigns) {
        return new CaseArchiveRecord(tenantId, archiveId, customerId, checklist, staffSigns,
                null, null, false, null);
    }

    // ==================================================================
    // 序列化（供 Ledger 走 `?::jsonb` —— 见下方「为什么序列化放在 record 里」）
    // ==================================================================

    /**
     * 唯一 {@code ObjectMapper}（线程安全，构造有成本，不每调用 new 一个）。
     *
     * <p>🛑 为什么序列化放在 record 里，而不是放在 Ledger 里：
     * <ol>
     *   <li><b>它与"形态归一"是同一层职责的下半段</b>：规范构造器已经把
     *       {@code checklist} / {@code staffSigns} 归一成"键集确定、值类型正确"的
     *       {@code TreeMap}（见 {@link #normalizeChecklist} / {@link #normalizeSigns}）。
     *       "把这个已归一的映射写成 JSON 文本"是同一件事的延续 ——
     *       拆到 Ledger 里会让"形态"与"形态的序列化"分居两处，
     *       而它们的正确性互相依赖（序列化一个未归一的 Map 会写出未登记键）。</li>
     *   <li><b>它保证序列化只发生一次且只有一种口径</b>：若 Ledger 自己序列化，
     *       下一个人加第二个写入方时就会写出第二份序列化代码，
     *       而两份的差别（是否排序键、null 如何表达）<b>不会报错</b>。</li>
     *   <li><b>本仓既有范式</b>：{@code derived/domain/DerivedMetricProfile} 与
     *       {@code derived/domain/ScaleStructure} 都在 domain 层持有
     *       {@code ObjectMapper}。照既有范式做，而不是发明新形态。</li>
     * </ol>
     * <p>🛑 为什么不复用 {@code customer/repository/Json}：它是<b>包私有</b>
     * （{@code final class Json}，无 {@code public}），跨包不可见；
     * 而 {@code dy-common} 下<b>没有</b>公共 JSON 工具（实测：{@code common/} 只有
     * {@code audit} / {@code exception} / {@code result} 三个子包）。
     * ⇒ 此处是本仓<b>第二处</b> JSON 序列化落点。这不是理想形态，
     * 已登记为可合并项：若将来抽出公共工具（如 {@code dy-common/.../Json}），
     * 本方法与 {@code customer.repository.Json} 应一并迁过去。
     * 本仓纪律允许"两处"的唯一理由是：现在没有第三处的容身处，
     * 而为了不写第二处就把 {@code Json} 改成 public 会让一个<b>域 B 内部</b>工具
     * 获得全仓可见性 —— 那是更大的越界。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Boolean>> BOOL_MAP = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() {
    };

    /**
     * 归档清单 → JSON 文本（供 {@code ?::jsonb}）。
     *
     * <p>🛑 序列化失败必须<b>抛</b>（{@code 5001} 口径断裂），不得回落成 {@code "{}"}：
     * {@code "{}"} 在本域的含义是"6 项门禁全缺"，而那是<b>一条业务上的实质断言</b>
     * （阻断结案）。把一次序列化故障伪装成"清单全缺"，会让一次代码缺陷
     * 以"这份档案的门禁没通过"的面目出现 —— 归因完全错误。
     * <p>🛑 空对象 {@code {}} 本身是合法输出（它会被库层的 (5b) 判成"硬门禁缺项"并 RAISE）
     * —— 但本 record 的构造器<b>不可能</b>产出空对象：它已强制 5 项硬门禁为 {@code true}。
     * 故本方法的输出必然含 6 个键。
     */
    public String checklistJson() {
        try {
            return MAPPER.writeValueAsString(checklist);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结案归档：archive_checklist 序列化失败: " + e.getOriginalMessage()
                            + " —— 🛑 不静默回落成 '{}'：'{}' 在本域的含义是"
                            + "『6 项门禁全缺』，那是一条会被库层 RAISE 的实质断言。"
                            + "把序列化故障伪装成它，会让一次代码缺陷以"
                            + "『这份档案的门禁没通过』的面目出现");
        }
    }

    /** 签名块 → JSON 文本（供 {@code ?::jsonb}）。失败处置同 {@link #checklistJson}。 */
    public String signsJson() {
        try {
            return MAPPER.writeValueAsString(staffSigns);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结案归档：staff_signs 序列化失败: " + e.getOriginalMessage()
                            + " —— 🛑 不静默回落成 '{}'：'{}' 会被库层判成『签名块缺项或为空』并 RAISE。"
                            + "一次序列化故障不该以『没人签字』的面目出现");
        }
    }

    // ==================================================================
    // 反序列化（供 Ledger 读回 —— 与上面对称，同层同口径）
    // ==================================================================

    /**
     * JSON 文本 → 归档清单。
     *
     * <p>🛑 解析失败必须<b>抛</b>，不得静默返回空 Map —— 理由与
     * {@code customer/repository/Json#toMap} 逐字同族：空 Map 在本域的含义是
     * "6 项门禁全缺"，即<b>"这份档案不该存在"</b>；一次数据损坏若被读成空 Map，
     * 会让"库里躺着的东西坏了"伪装成"这份档案的门禁全没过"。
     * <p>报 {@code 5001}（口径断裂）而非 {@code 1001}（入参校验）：
     * 问题在库里躺着的东西，不在调用方的请求体。
     */
    public static Map<String, Boolean> parseChecklist(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Boolean> parsed = MAPPER.readValue(json, BOOL_MAP);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结案归档：archive_checklist 反序列化失败: " + e.getOriginalMessage()
                            + " —— 输入片段: " + snippet(json)
                            + "。🛑 不静默返回空 Map：空 Map 在本域是『门禁全缺』这一实质断言");
        }
    }

    /** JSON 文本 → 签名块（失败处置同 {@link #parseChecklist}）。 */
    public static Map<String, String> parseSigns(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, STR_MAP);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结案归档：staff_signs 反序列化失败: " + e.getOriginalMessage()
                            + " —— 输入片段: " + snippet(json)
                            + "。🛑 不静默返回空 Map：那会把『库里躺着的东西坏了』"
                            + "伪装成『没人签字』");
        }
    }

    /** 取一段可放进错误消息的短片段（截断 200 字符 —— 与 {@code Json#snippet} 同口径）。 */
    private static String snippet(String json) {
        String s = json.replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(共 " + s.length() + " 字符)";
    }
}