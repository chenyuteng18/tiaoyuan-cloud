package com.diaoyuanyun.dy.app.doctpl.esign;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 电子签回调的<b>载荷白名单</b>（B-5）—— 契约 H1 三条已冻结形态之三的机械落实。
 *
 * <h2>契约逐字（🛑 硬约束）</h2>
 * <pre>
 * ③ 数据边界（硬约束）：回调载荷不得承载任何健康数据 / 禁忌结论 ——
 *    载荷允许承载范围白名单仅「签署事件 + 文书标识 + 租户标识 + 时间戳」。
 * </pre>
 *
 * <h2>🛑 用"白名单 + 未知字段即拒"而不是"只挑需要的字段"</h2>
 * 后一种写法（读自己关心的键、忽略其余）看起来更宽容，实际是把契约的硬约束
 * <b>降级成一句注释</b>：厂商多发一个 {@code health_summary} 字段，系统会<b>静默收下</b>，
 * 而"静默收下"意味着它进入日志、进入对象存储、进入审计快照 —— 数据边界就被穿了个洞，
 * 且没有任何一处会报警。
 *
 * <p>故本类 <b>拒绝任何白名单外的键</b>。这会让"厂商新增了一个无害字段"变成一次
 * 400（响亮失败），而不是一次静默越界。这正是这类边界该有的失效方向。
 *
 * <h2>🛑 白名单只收"标识/事件/时间"，不收任何业务内容</h2>
 * 四个允许位都是<b>无信息量的定位符</b>：
 * <pre>
 *  sign_request_id  签署请求标识（幂等键的一半）
 *  tenant_id        租户标识（多租户反解）
 *  doc_ref          文书标识（指向 agreement / doc_template，本身不含内容）
 *  occurred_at      事件发生时间戳
 * </pre>
 * 注意"文书标识"是<b>标识</b>，不是"文书正文/渲染稿" —— 后者由 I8 渲染快照在库内自持，
 * 绝不由回调带入（带入就等于让外部输入决定库内正文）。
 */
public record EsignCallbackPayload(
        String signRequestId,
        String tenantId,
        String docRef,
        String occurredAt,
        EsignEventType eventType) {

    /**
     * 载荷允许出现的键白名单（键名本身在契约里<b>未冻结</b>，故此处登记的是
     * "本骨架当前采用的键名 + 允许位清单"，供厂商接入时按实际情况替换 ——
     * 替换时只需改这里与方法体的取值，判定逻辑不受影响）。
     */
    public static final Set<String> ALLOWED_KEYS =
            Set.of("sign_request_id", "tenant_id", "doc_ref", "occurred_at", "event_type");

    /** 明确点名的高危越界字段（出现即单独报错，便于运维一眼看出是数据边界被穿）。 */
    private static final Set<String> FORBIDDEN_HINTS = Set.of(
            "health", "diagnosis", "contraindication", "禁忌", "症状", "血压", "心率",
            "sleep", "体征", "病史", "体检", "报告", "病历");

    /** 从自由形态的报文构造（🛑 先做白名单校验，再做取值）。 */
    public static EsignCallbackPayload fromRaw(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "回调载荷为空");
        }

        // ① 数据边界：白名单外的键一律拒（含"看起来无害"的新增字段）
        for (String key : raw.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                String lower = key.toLowerCase();
                for (String hint : FORBIDDEN_HINTS) {
                    if (lower.contains(hint.toLowerCase())) {
                        throw new BizException(ErrorCode.VALIDATION_FAILED,
                                "🛑 回调载荷承载了疑似健康数据 / 禁忌结论字段（契约 H1 硬约束禁止）: " + key);
                    }
                }
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "回调载荷含白名单外的字段: " + key + "（契约 H1 允许位仅 "
                                + ALLOWED_KEYS + "）—— 未知字段一律拒收，不静默忽略");
            }
        }

        // ② 取值（缺失的必填位一律拒）
        String signRequestId = require(raw, "sign_request_id");
        String tenantId = require(raw, "tenant_id");
        String docRef = require(raw, "doc_ref");
        String occurredAt = require(raw, "occurred_at");
        EsignEventType type = EsignEventType.parse(String.valueOf(raw.get("event_type")));

        return new EsignCallbackPayload(signRequestId, tenantId, docRef, occurredAt, type);
    }

    /**
     * 幂等键 = {@code sign_request_id + event_type}（契约 H1 逐字）。
     *
     * <p>🛑 键里<b>不含发生时间</b>：厂商重投时时间戳可能不同（重试时刻 vs 原事件时刻），
     * 把时间纳入键会让"同一次签署事件的重复投递"变成两条记录 ⇒ 幂等失效
     * （而 {@code signed} 事件重复处理会重复推进门禁）。
     */
    public String idempotencyKey() {
        return signRequestId + "#" + eventType.wireName();
    }

    /** 供审计/日志的安全投影（🛑 只有定位符，可安全落日志）。 */
    public Map<String, Object> safeProjection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sign_request_id", signRequestId);
        m.put("tenant_id", tenantId);
        m.put("doc_ref", docRef);
        m.put("occurred_at", occurredAt);
        m.put("event_type", eventType.wireName());
        m.put("advances_gate", eventType.advancesGate());
        return m;
    }

    private static String require(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "回调载荷缺少必填字段: " + key);
        }
        return String.valueOf(v).trim();
    }
}