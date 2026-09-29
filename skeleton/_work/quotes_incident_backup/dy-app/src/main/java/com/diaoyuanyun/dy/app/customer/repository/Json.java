package com.diaoyuanyun.dy.app.customer.repository;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域 B 四本账本共用的 <b>JSONB ↔ {@code Map} 互转</b>（唯一落点）。
 *
 * <h2>🛑 为什么必须有这样一个类，而不是"各处自己 new ObjectMapper"</h2>
 * 域 B 有四处 JSONB 列（{@code screening_record.items_json} /
 * {@code consent.auth_scope_json} / {@code intake_profile} 的 11 个多选列 /
 * {@code intake_profile_revision.snapshot_json}），读写都要互转。若各处自行
 * {@code new ObjectMapper()} 或自行 {@code String.split}，会立刻出现两份口径，
 * 而它们的差别<b>不会报错</b>：
 * <pre>
 *   … 一份把 {@code null} 转成 "null" 字符串，另一份转成 SQL NULL
 *   … 一份在反序列化失败时返回空 Map，另一份抛
 * </pre>
 * 第二种差别是最危险的 —— 见下。
 *
 * <h2>🛑🛑 反序列化失败必须<b>抛</b>，绝不静默返回空 Map</h2>
 * 这是本类最重要的一条。返回空 Map 会让"库里的 JSON 坏了"伪装成
 * <b>"这份档案没有字段"</b>：
 * <pre>
 *   {@code intake_profile.sleep} 里躺着一串被截断的 JSON
 *     → 真相：数据损坏，需要告警与人工介入
 *     → 静默返回 {} 后的表现：『这位客户没填睡眠』，一切正常，无人知晓
 * </pre>
 * 而域 B 的 {@code items_json} 正是 <b>B1 硬门禁结论的推导输入</b>
 * （{@code ContraindicationPolicy.derive}）——一个被静默吞成空 Map 的
 * 损坏记录，会让一次"命中了禁忌"的筛查<b>推导为通过</b>。
 * 故本类对"解析不出来"一律以明确错误失败。
 *
 * <h2>🛑 报 5001 而不是 1001</h2>
 * {@code 1001 VALIDATION_FAILED} 的语义是"<b>调用方给的入参</b>不合法"，
 * 而这四类失败全部是"<b>库里躺着的东西</b>读不出来" —— 那是口径断裂（5001）。
 * 报成 1001 会把一个数据损坏问题指向上游的请求体，排查方向直接错。
 *
 * <h2>关于"空对象 {} 与 null 是两件事"</h2>
 * 上游（{@link com.diaoyuanyun.dy.app.customer.domain.ScreeningRecordRow} /
 * {@link com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRevisionRow}）
 * 反复强调这条。本类在两个方向上都保持它：
 * <pre>
 *   toJson(null)        → "null"（字面），且调用方写入时改用 SQL NULL（见各账本）
 *   toJson({})          → "{}"
 *   toMap(null / "")    → 空 Map（SQL NULL 读回来的形态）
 *   toMap("{}")         → 空 Map（空对象读回来的形态）
 *   toMap("坏了")       → 抛 5001
 * </pre>
 * 注意 {@code toMap} 无法区分"SQL NULL"与"{@code {}}" —— 这是 JSONB 的类型特性，
 * <b>不是本类的疏忽</b>：写入侧若需要区分，靠的是 {@code ?::jsonb} 收到的是
 * 真实 {@code null} 还是 {@code "{}"} 字符串。故 {@link #toJsonOrNull} 提供
 * "空 Map → 字面 null" 的可选口径，供<b>明确需要区分</b>的列使用。
 */
final class Json {

    private Json() {
    }

    /**
     * 唯一 {@code ObjectMapper} 实例。
     *
     * <p>刻意只有一份：{@code ObjectMapper} 是<b>线程安全</b>的（其读方法安全，
     * 配置不在此处修改），且它的构造有实际成本（各模块的初始化）。
     * 每个方法各 new 一个会让域 B 每次读写都付一次初始化费，
     * 而这段开销落在<b>每个请求</b>上。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    // ==================================================================
    // 写方向：Map → JSONB
    // ==================================================================

    /**
     * {@code Map} → JSON 文本（供 {@code ?::jsonb} 强转）。
     *
     * <p>{@code null} 入参返回字面 {@code "null"}（而不是 Java 的 {@code null}）——
     * 使 {@code ?::jsonb} 得到一个合法的 JSON null 而不是 SQL NULL。
     * 🛑 需要"空 → SQL NULL"语义的列请用 {@link #toJsonOrNull}。
     */
    static String toJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "JSONB 序列化失败: " + e.getOriginalMessage()
                            + " —— 🛑 本类不静默兜底成 '{}'：那会把『这份数据写不出去』"
                            + "伪装成『这份数据是空的』，而空记录在域 B 里是合法状态"
                            + "（例如客户清空了档案），两者一旦混淆就再也分不清");
        }
    }

    /**
     * {@code Map} → JSON 文本，但<b>空 Map 返回 Java {@code null}</b>
     * （由调用方经 {@code ?::jsonb} 写成 SQL NULL）。
     *
     * <p>供<b>明确需要区分</b> "未提供" 与 "提供了空集" 的列使用。
     * 域 B 里的典型是 {@code consent.auth_scope_json}：契约与 data-dict 都要求
     * 区分"客户拒绝了全部四项"（空集）与"一个都没记"（不合法）——
     * 前者必须落成一个可辨认的 {@code []}，不能与 NULL 混同。
     */
    static String toJsonOrNull(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return toJson(value);
    }

    /**
     * 字符串集合 → JSON 数组文本（{@code auth_scope_json} 等"键集合"列专用）。
     *
     * <p>🛑 <b>空集合返回字面 {@code "[]"}，不返回 {@code null}</b>：
     * 这正是"客户可拒绝全部四项"的落库形态（见 {@link #toJsonOrNull} 的说明）。
     */
    static String toJsonArray(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "JSONB 数组序列化失败: " + e.getOriginalMessage());
        }
    }

    // ==================================================================
    // 读方向：JSONB → Map
    // ==================================================================

    /**
     * JSON 文本 → {@code Map}。
     *
     * <p>{@code null} 与空串返回<b>空 Map</b>（SQL NULL 读回来的形态）。
     * 其余解析失败一律抛 {@code 5001}（理由见类注释：绝不静默返回空 Map）。
     */
    static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "JSONB 反序列化失败: " + e.getOriginalMessage()
                            + " —— 🛑 输入片段: " + snippet(json)
                            + "。本类【不】静默返回空 Map：那会把『库里的 JSON 坏了』"
                            + "伪装成『这份档案没有字段』。域 B 的 items_json 是 B1 硬门禁"
                            + "结论的推导输入 —— 一次被吞成空 Map 的损坏记录，"
                            + "会让『命中了禁忌』的筛查被推导为【通过】。"
                            + "此处报 5001（口径断裂）而非 1001（入参校验）："
                            + "问题在库里躺着的东西，不在调用方的请求体");
        }
    }

    /** JSON 文本 → {@code List<String>}（{@code auth_scope_json} 等键集合列专用）。 */
    static List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "JSONB 数组反序列化失败: " + e.getOriginalMessage()
                            + " —— 输入片段: " + snippet(json)
                            + "。🛑 不静默返回空列表：空集在域 B 是【合法且有意义】的值"
                            + "（客户拒绝了全部授权项），静默降级会让一次数据损坏"
                            + "被读成一次真实的『全部拒绝』");
        }
    }

    /**
     * 宽松读：解析失败返回空 Map（<b>仅供诊断/自描述路径</b>）。
     *
     * <h2>🛑 使用限制（这不是"另一个 toMap"）</h2>
     * 它的唯一合法用途是"我要把库里的东西原样讲给人听"（审计回溯、运维排障），
     * 那种场景下"这里坏了"本身就是要展示的信息，抛异常反而看不到全貌。
     * <b>任何参与判定的路径都不得使用它</b> —— 判定路径必须用 {@link #toMap}，
     * 让损坏在最靠近源头处爆出。
     */
    static Map<String, Object> toMapLenient(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("⚠️_json_parse_failed", true);
            report.put("raw_snippet", snippet(json));
            report.put("error", e.getOriginalMessage());
            return report;
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 取一段可放进错误消息的短片段。
     *
     * <p>🛑 截断是刻意的：{@code items_json} 可能很大（11 个多选模块），
     * 把整份塞进错误消息会让日志失去可读性，也可能带出不该进日志的内容。
     * 200 字符足以定位"是哪个字段开始坏的"。
     */
    private static String snippet(String json) {
        String s = json.replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(共 " + s.length() + " 字符)";
    }
}