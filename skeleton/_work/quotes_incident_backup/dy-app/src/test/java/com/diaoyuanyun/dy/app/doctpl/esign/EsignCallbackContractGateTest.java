package com.diaoyuanyun.dy.app.doctpl.esign;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-5 · 电子签回调的<b>契约门禁</b>（构建期，不依赖数据库与厂商）。
 *
 * <h2>本类守的是一条很容易被违反的纪律</h2>
 * 契约 H1 的原文（逐字）：
 * <pre>
 *   🛑 本节只冻结形态（事件枚举 / 幂等键 / 失败处置 / 数据边界），
 *      不冻结任何字段名、载荷结构、签名算法。厂商选定之前，三端不得据本节编码。
 * </pre>
 * 这句话的工程含义是：<b>可以先把"形态"做成可测代码，但不得替厂商做任何裁定</b>。
 * 于是本类做两件事：
 * <ol>
 *   <li>把已冻结的<b>三条形态</b>钉成断言（写明"这三条是契约原文，改了要同步契约"）；</li>
 *   <li><b>反向守卫</b>：断言源码里<b>没有</b>出现任何未冻结项（厂商名 / 签名算法名）——
 *       防止后人"顺手"把某个厂商的字段名或算法名写进来，从而把一个"待冻结"的接口
 *       悄悄变成"已按某厂商实现"。</li>
 * </ol>
 *
 * <h2>🛑 为什么 B-5 不落地可访问的 H1 端点（这是一个安全结论，不是省事）</h2>
 * 契约把 H1 标为 {@code x-frontier: 占位待冻结}，且 {@code 厂商签名算法} 明列在
 * {@code x-not-frozen} 里。这意味着：<b>此刻没有任何办法验签</b>。
 * 一个收不到验签的回调端点 = 一条"任何人都能构造报文把客户推进到 AGREEMENT_SIGNED"的通道，
 * 而 AGREEMENT_SIGNED 是 06 屏的门禁前置。开这种端点的风险远大于"先用占位实现"的收益。
 * 故本批只落 domain 层的形态与边界判定（零风险、可测、厂商一到即可复用），
 * 控制器留到厂商接口冻结后一并落地。
 */
@DisplayName("B-5 · 电子签回调用例形态门禁（契约 H1 三条已冻结形态 + 未冻结项守卫）")
class EsignCallbackContractGateTest {

    /** skeleton 根（与既有门禁同款定位方式）。 */
    private static final Path SKEL = Paths.get("..").toAbsolutePath().normalize();

    /** 契约文件（docs-root/contract/…）。 */
    private static final Path CONTRACT = SKEL.getParent().resolve("contract/openapi-v1.0.0.yaml");

    // ==================================================================
    // ① 事件枚举恰三值（契约原文）
    // ==================================================================

    @Test
    @DisplayName("事件枚举恰三值 {signed, rejected, expired}，且与契约文本逐字一致")
    void event_enum_is_exactly_the_three_frozen_values() {
        assertEquals(3, EsignEventType.values().length,
                "契约 H1 冻结的 event_type 恰三值；增减其一都必须同步契约 §H 与本文档");
        assertEquals(java.util.Set.of("signed", "rejected", "expired"), EsignEventType.wireNames(),
                "三值必须与契约逐字一致（小写字面量）");

        // 契约文本交叉断言：三值确实出现在契约 H1 段落里
        String yaml = read(CONTRACT);
        for (String v : EsignEventType.wireNames()) {
            assertTrue(yaml.contains(v), "契约文本里应出现事件取值: " + v);
        }
        assertTrue(yaml.contains("event_type"), "契约 H1 应声明 event_type");
        assertTrue(yaml.contains("sign_request_id"), "契约 H1 应声明幂等键的一半 sign_request_id");
    }

    @Test
    @DisplayName("🛑 只有 signed 推进门禁；rejected / expired 一律不推进（安全属性，不是暂不支持）")
    void only_signed_advances_the_gate() {
        assertTrue(EsignEventType.SIGNED.advancesGate(), "signed 必须推进门禁");
        assertFalse(EsignEventType.REJECTED.advancesGate(),
                "🛑 rejected 绝不得推进门禁 —— 否则一份被拒签的协议会把客户推进到已签状态");
        assertFalse(EsignEventType.EXPIRED.advancesGate(),
                "🛑 expired 绝不得推进门禁 —— 过期视同放弃并推进是最典型的合规事故");
    }

    @Test
    @DisplayName("未知事件类型 fail-closed（抛错，不落默认值）")
    void unknown_event_is_rejected() {
        for (String bad : new String[]{null, "", "  ", "SIGN", "signed_x", "approved", "cancelled“}) {
            assertThrows(BizException.class, () -> EsignEventType.parse(bad),
                    ”未知事件必须抛错，实际接受了: " + bad);
        }
        // 大小写不敏感是允许的（厂商大小写口径不一），但不得因此接受未知值
        assertEquals(EsignEventType.SIGNED, EsignEventType.parse("SIGNED"));
        assertEquals(EsignEventType.SIGNED, EsignEventType.parse(" signed "));
    }

    // ==================================================================
    // ② 幂等键 = sign_request_id + event_type（契约原文）
    // ==================================================================

    @Test
    @DisplayName("幂等键 = sign_request_id + event_type；同一事件重复投递键必须相同")
    void idempotency_key_is_request_id_plus_event_type() {
        EsignCallbackPayload first = payload("req-1", "signed", "2026-09-26T10:00:00Z");
        EsignCallbackPayload retry = payload("req-1", "signed", "2026-09-26T10:00:07Z");   // 重投，时间戳不同
        assertEquals(first.idempotencyKey(), retry.idempotencyKey(),
                "🛑 幂等键【不得】包含 occurred_at：厂商重投时时间戳会变，"
                        + "把它纳入键会让同一次签署事件的重复投递变成两条记录 ⇒ 幂等失效"
                        + "（而 signed 重复处理会重复推进门禁）");
        assertEquals("req-1#signed", first.idempotencyKey(), "键形如 sign_request_id#event_type");

        // 事件不同 ⇒ 键必须不同（否则"拒签"会被当成"已签"的重投而被忽略）
        assertNotEquals(first.idempotencyKey(),
                payload("req-1", "rejected", "2026-09-26T10:00:00Z").idempotencyKey(),
                "不同事件不得共用幂等键");
    }

    // ==================================================================
    // ③ 载荷白名单（契约原文的硬约束）
    // ==================================================================

    @Test
    @DisplayName("白名单内的最小载荷可被接受（四个允许位）")
    void whitelisted_payload_is_accepted() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("sign_request_id", "req-9");
        raw.put("tenant_id", "e2e00000-0000-0000-0000-00000000d0f0");
        raw.put("doc_ref", "agreement:abc");
        raw.put("occurred_at", "2026-09-26T10:00:00Z");
        raw.put("event_type", "expired");

        EsignCallbackPayload p = EsignCallbackPayload.fromRaw(raw);
        assertEquals(EsignEventType.EXPIRED, p.eventType());
        assertFalse(p.eventType().advancesGate(), "expired 不推进门禁");
        assertEquals("req-9#expired", p.idempotencyKey());
    }

    @Test
    @DisplayName("🛑 白名单外字段一律拒收（不静默忽略 —— 否则数据边界被静默穿透）")
    void unknown_field_is_rejected_not_ignored() {
        Map<String, Object> raw = base("signed");
        raw.put("vendor_order_no", "X-1");   // 看起来无害的厂商字段，也必须拒
        BizException e = assertThrows(BizException.class, () -> EsignCallbackPayload.fromRaw(raw),
                "🛑 未知字段必须拒收：静默忽略会让厂商新增字段悄悄进入日志/存储/审计快照，"
                        + "而数据边界是契约写明的【硬约束】");
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(), e.getCode(),
                "未知字段应归 1001 参数校验失败");
    }

    @Test
    @DisplayName("🛑 承载健康数据 / 禁忌结论的字段被单独点名拒绝")
    void health_and_contraindication_fields_are_specifically_rejected() {
        String[] forbidden = {"health_summary", "contraindication", "症状描述", "心率曲线", "sleep_stages“};
        for (String key : forbidden) {
            Map<String, Object> raw = base(”signed");
            raw.put(key, "…");
            BizException e = assertThrows(BizException.class, () -> EsignCallbackPayload.fromRaw(raw),
                    "🛑 契约 H1 硬约束：回调载荷不得承载任何健康数据 / 禁忌结论。漏掉该字段: " + key);
            assertTrue(String.valueOf(e.getMessage()).contains("健康数据")
                            || String.valueOf(e.getMessage()).contains("禁忌"),
                    "该场景应给出「疑似健康数据/禁忌结论」的专门提示，便于运维一眼归因: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("必填位缺失 → 拒绝（不落半成品）")
    void missing_required_fields_are_rejected() {
        for (String missing : new String[]{"sign_request_id", "tenant_id", "doc_ref",
                "occurred_at", "event_type“}) {
            Map<String, Object> raw = base(”signed");
            raw.remove(missing);
            assertThrows(BizException.class, () -> EsignCallbackPayload.fromRaw(raw),
                    "缺必填位必须拒绝: " + missing);
        }
        assertThrows(BizException.class, () -> EsignCallbackPayload.fromRaw(null), "空载荷必须拒绝");
        assertThrows(BizException.class, () -> EsignCallbackPayload.fromRaw(new HashMap<>()),
                "空 Map 必须拒绝");
    }

    @Test
    @DisplayName("安全投影只含定位符，可安全落日志（无业务内容）")
    void safe_projection_has_no_business_content() {
        Map<String, Object> proj = payload("req-3", "signed", "2026-09-26T10:00:00Z").safeProjection();
        assertEquals(EsignCallbackPayload.ALLOWED_KEYS.size() + 1, proj.size(),
                "投影应恰为白名单五键 + advances_gate");
        assertTrue(proj.keySet().containsAll(EsignCallbackPayload.ALLOWED_KEYS));
        assertTrue(proj.containsKey("advances_gate"));
    }

    // ==================================================================
    // ④ 反向守卫：未冻结项不得被写成结论
    // ==================================================================

    @Test
    @DisplayName("🛑 源码里不得出现任何厂商名 / 签名算法名（契约明确列为未冻结）")
    void not_frozen_items_must_not_appear_in_code() {
        List<Path> sources = List.of(
                SKEL.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/esign/"
                        + "EsignEventType.java"),
                SKEL.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/esign/"
                        + "EsignCallbackPayload.java"));

        // 厂商名：契约 x-not-frozen 里 provider 取值域未冻结（注释里举例用的名字也不得进代码逻辑）
        String[] vendors = {"e签宝", "腾讯电子签", "法大大", "ESIGN_BAO", "docusign", "DocuSign“};
        // 签名算法：契约 x-not-frozen 明确列出”厂商签名算法“未冻结
        String[] algorithms = {”HMAC-SHA256", "RS256", "SM2withSM3", "SHA256withRSA", "HmacSHA256“};

        for (Path src : sources) {
            String text = read(src);
            // 只检查非注释、非 javadoc 的代码行（注释里可以讨论这些名字，代码里不行）
            String code = stripComments(text);
            for (String v : vendors) {
                assertFalse(code.contains(v),
                        ”🛑 代码里出现厂商名「" + v + "」—— 契约 H1 把 provider 取值域列为【未冻结】，"
                                + "写进代码等于替厂商做了裁定。文件: " + src.getFileName());
            }
            for (String a : algorithms) {
                assertFalse(code.contains(a),
                        "🛑 代码里出现签名算法名「" + a + "」—— 契约 H1 把厂商签名算法列为【未冻结】。"
                                + "验签算法只能等厂商接口冻结后引入。文件: " + src.getFileName());
            }
        }
    }

    @Test
    @DisplayName("🛑 B-5 不得落地可访问的回调端点（未冻结验签 ⇒ 开端点=任意推进门禁）")
    void no_accessible_callback_endpoint_yet() {
        Path esignDir = SKEL.resolve("dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/esign");
        assertTrue(Files.isDirectory(esignDir), "esign 目录应存在");
        try (var stream = Files.list(esignDir)) {
            List<String> names = stream.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("EsignCallbackPayload.java", "EsignEventType.java"), names,
                    "🛑 esign 包在厂商接口冻结前只应有 domain 层两个文件。"
                            + "若出现 Controller/Service，说明有人据【未冻结】的接口编码了 —— "
                            + "那会开出一条无法验签的『推进 AGREEMENT_SIGNED 门禁』的通道。");
            for (String n : names) {
                assertFalse(n.endsWith("Controller.java"),
                        "不得存在回调控制器: " + n);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Map<String, Object> base(String eventType) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("sign_request_id", "req-1");
        raw.put("tenant_id", "e2e00000-0000-0000-0000-00000000d0f0");
        raw.put("doc_ref", "agreement:1");
        raw.put("occurred_at", "2026-09-26T10:00:00Z");
        raw.put("event_type", eventType);
        return raw;
    }

    private static EsignCallbackPayload payload(String reqId, String type, String occurredAt) {
        Map<String, Object> raw = base(type);
        raw.put("sign_request_id", reqId);
        raw.put("occurred_at", occurredAt);
        return EsignCallbackPayload.fromRaw(raw);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取失败: " + p + " —— " + e.getMessage(), e);
        }
    }

    /** 去掉行注释与块注释（javadoc 允许讨论未冻结项，代码不允许使用）。 */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int i = line.indexOf("//");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return sb.toString();
    }
}