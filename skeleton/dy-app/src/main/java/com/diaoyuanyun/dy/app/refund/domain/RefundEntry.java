package com.diaoyuanyun.dy.app.refund.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 退款工单入口（PRD P0-14 双入口）。
 *
 * <h2>为什么本枚举必须持两套字面（而不是统一成一套）</h2>
 * 出口契约（{@code openapi-v1.0.0.yaml · RefundCreateRequest.entry}）声明的是
 * {@code [A门店代录, B首周期]}（<b>无空格</b>）；而库内 CHECK（V5 §2.20）落的是
 * {@code 'A 门店代录' | 'B 首周期'}（<b>有空格</b>）。两处<b>都已冻结</b>，
 * 且都不是笔误 —— 契约侧是端侧逐字比对的出站字面，库侧是已应用迁移的历史字面。
 *
 * <p>三种处置里只有一种是对的：
 * <ol>
 *   <li><b>库侧改名</b> → 让"契约与库不一致"从一个<b>可追溯的登记项</b>变成一次
 *       无声的历史改写；已有环境的旧数据会被迁移之外的路径遗漏。</li>
 *   <li><b>契约侧改名</b> → 改契约须重走冻结；P0-19 的编码前输入已于 2026-09-16 冻结，
 *       改契约等于开一条不在本次范围内的返工链（触发条款 B 的口径）。</li>
 *   <li><b>领域枚举持两套字面</b> → 出入各用各的、差异显式登记，owner 裁定后一处改。
 *       本类即选此项。</li>
 * </ol>
 *
 * <p>🛑 两个方向不得混用：{@link #contractCode()} <b>只</b>用于出入站
 * （请求体解析 / 响应体渲染），{@link #dbCode()} <b>只</b>用于持久化（SQL 参数）。
 * 若把 {@code dbCode} 渲染进响应，端侧按契约逐字比对会红 ——
 * 这正是 S1-8 契约回归门禁要抓的那一类。
 */
public enum RefundEntry {

    /** 入口 A = 门店代客录入（客户诉求 24h 内代录）。 */
    A("A门店代录", "A 门店代录"),

    /** 入口 B = 首周期双不达标 → 不经挽留主动终止退款。 */
    B("B首周期", "B 首周期");

    private final String contractCode;
    private final String dbCode;

    RefundEntry(String contractCode, String dbCode) {
        this.contractCode = contractCode;
        this.dbCode = dbCode;
    }

    /** 出站字面（与契约 {@code RefundCreateRequest.entry} 枚举逐字一致）。 */
    public String contractCode() {
        return contractCode;
    }

    /** 持久化字面（与 V5 §2.20 的 {@code entry} CHECK 逐字一致）。 */
    public String dbCode() {
        return dbCode;
    }

    /** 全部出站字面（供契约一致性断言使用）。 */
    public static List<String> allContractCodes() {
        return Arrays.stream(values()).map(RefundEntry::contractCode).toList();
    }

    /** 全部持久化字面（供库 CHECK 一致性断言使用）。 */
    public static List<String> allDbCodes() {
        return Arrays.stream(values()).map(RefundEntry::dbCode).toList();
    }

    /**
     * 按<b>入站</b>字面解析（请求体）。
     *
     * <p>🛑 刻意<b>也</b>接受 {@code dbCode}：入库行被读回、或运维用库内字面调接口时，
     * "解析失败"会让一次本来正确的调用变成 400，而错误信息指向的是调用方 ——
     * 真正的问题（两套字面）反而被掩盖。接受两套、但<b>出站只用契约字面</b>，
     * 是把宽容留在入口、把严格留在出口。
     */
    public static RefundEntry parse(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "退款工单入口（entry）必填 —— 缺失即无法判定「是否须走挽留」与「24h 代录纪律是否适用」");
        }
        String c = code.trim();
        return Arrays.stream(values())
                .filter(e -> e.contractCode.equals(c) || e.dbCode.equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "退款工单入口不在允许值内: '" + code + "'（契约字面: " + allContractCodes() + "）"));
    }

    /**
     * 是否属"门店代客录入"（入口 A）。
     *
     * <p>用途集中在两处：① 24h 代录纪律只对入口 A 计时（入口 B 由首周期双不达标自动触发，
     * 不存在"客户什么时候提出的"这一问）；② {@code requested_at} 三字段只对入口 A 有意义。
     */
    public boolean isStoreDeputyEntry() {
        return this == A;
    }
}