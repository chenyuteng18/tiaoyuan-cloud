package com.diaoyuanyun.dy.app.fulfillment.domain;

import com.diaoyuanyun.dy.common.exception.GateMissingException;

import java.util.ArrayList;
import java.util.List;

/**
 * 履约域（契约域 D）的<b>四道闸门守卫</b> —— D1 服务核销 / D6 设备下发的执行前置。
 *
 * <h2>契约 / PRD 依据（逐字）</h2>
 * PRD <b>P0-08</b>「四道闸门执行校验」：
 * <pre>
 *   执行前校验：禁忌通过 / 知情同意书已签 / 调理协议已签 / 方案已确认且在有效期
 *   任一缺失 → 执行入口返回 403 并【给出缺失项名称（非模糊报错）】
 *   校验覆盖【设备参数下发】与【服务次数记录】两个动作
 * </pre>
 * 故本守卫服务于两个端点：D1（{@code POST /customers/{id}/visits}）与 D6
 * （{@code POST /device-dispatches}），二者共享同一份"四道闸门"语义，
 * 各自的 403 都是 {@code GATE_MISSING(2002)} + {@code data.missing_items[]}。
 *
 * <h2>四道闸门的缺失项名称（对外字面，逐字给出不能模糊）</h2>
 * <pre>
 *  ① 禁忌通过        → {@code screening_result}（与 B2 门禁同一字面）
 *  ② 知情同意书已签   → {@code informed_consent}
 *  ③ 调理协议已签     → {@code agreement_signed}
 *  ④ 方案已确认且有效 → {@code plan_approved}
 * </pre>
 * 🛑 这些字面是<b>对外冻结项</b>（前端按它做分支）。与 B2 的
 * {@code missing_items=["screening_result"]} 同族 —— 若这里用一套风格统一的"假字面"
 * （例如 {@code "gate_1"}），前端匹配会静默落空。
 *
 * <h2>🛑 为什么它只"校验"不"替调用方补"</h2>
 * 四道闸门各自是<b>另一个域的产出</b>：
 * <pre>
 *  ① 禁忌     ← 域 B 的 B1（screening_record）
 *  ② 同意书   ← 域 B 的 B3（consent）
 *  ③ 协议     ← 域 D 的 plan/agreement 链
 *  ④ 方案     ← 域 D 的 plan（status=approved）
 * </pre>
 * 本守卫只做<b>存在性断言</b>并逐字报告"缺什么"，绝不替这些域"造一条通过记录"。
 * 这使"谁负责哪一道门"在数据里可追溯，而不是把所有前置揉进一个布尔。
 *
 * <h2>🛑 断言顺序固定（就是四道门的业务顺序）</h2>
 * 禁忌 → 同意书 → 协议 → 方案，与 PRD 流程图的先后一致。
 * 顺序本身即语义：一个"没签同意书但有方案"的客户，缺失项应按流程顺序报
 * "缺同意书"，而不是"缺方案"（后者会误导排查者去补方案）。
 */
public final class FulfillmentGateGuard {

    private FulfillmentGateGuard() {
    }

    /** 四道闸门的对外缺失项字面（逐字，前端分支依据）。 */
    public static final String MISSING_SCREENING = "screening_result";
    public static final String MISSING_CONSENT = "informed_consent";
    public static final String MISSING_AGREEMENT = "agreement_signed";
    public static final String MISSING_PLAN = "plan_approved";

    /**
     * 一次四道闸门校验的输入（各闸门的"是否满足"）。
     *
     * @param screeningPassed ① 禁忌是否通过（存在 result=通过 的筛查记录）
     * @param consentSigned   ② 知情同意书是否已签
     * @param agreementSigned ③ 调理协议是否已签
     * @param planApproved    ④ 方案是否已确认且在有效期
     */
    public record GateState(
            boolean screeningPassed,
            boolean consentSigned,
            boolean agreementSigned,
            boolean planApproved) {
    }

    /**
     * 断言四道闸门全过；缺任一即抛 {@code GATE_MISSING}，并逐字给出全部缺失项。
     *
     * <p>🛑 <b>一次性报全部缺失</b>，而不是"遇见第一个就短路"：
     * P0-08 的意图是让门店一次补齐所有前置、而不是补一个报一个地来回。
     * 故本方法收集全部未过项，再一并抛。
     *
     * @param endpoint 端点名（进错误消息，供日志定位）
     * @param state    四道闸门状态
     * @throws GateMissingException {@code GATE_MISSING(2002)} —— 403 语义 + data.missing_items[]
     */
    public static void assertAllGatesPassed(String endpoint, GateState state) {
        List<String> missing = new ArrayList<>(4);
        if (!state.screeningPassed()) {
            missing.add(MISSING_SCREENING);
        }
        if (!state.consentSigned()) {
            missing.add(MISSING_CONSENT);
        }
        if (!state.agreementSigned()) {
            missing.add(MISSING_AGREEMENT);
        }
        if (!state.planApproved()) {
            missing.add(MISSING_PLAN);
        }
        if (!missing.isEmpty()) {
            throw new GateMissingException(missing,
                    endpoint + " 执行前置未满足（PRD P0-08：任一缺失 → 403 并逐字给出缺失项名，非模糊报错）");
        }
    }

    /** 四道闸门的字面清单（自描述端点用）。 */
    public static List<String> missingItemLiterals() {
        return List.of(MISSING_SCREENING, MISSING_CONSENT, MISSING_AGREEMENT, MISSING_PLAN);
    }
}