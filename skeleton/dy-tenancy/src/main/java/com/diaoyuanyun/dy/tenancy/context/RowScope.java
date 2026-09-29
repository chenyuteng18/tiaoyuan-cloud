package com.diaoyuanyun.dy.tenancy.context;

import com.diaoyuanyun.dy.tenancy.exception.TenantMismatchException;

import java.util.Optional;

/**
 * 行级可见范围 (ADR-02 第 3 层语义 / T-7)。
 *
 * <p>own_store = 仅本门店数据; region = 本区域门店; all = 跨门店全量 (通常仅平台超管)。
 * 与 RLS 策略的 scope 维度配合, 决定租户内可见边界。
 */
public enum RowScope {

    OWN_STORE("own_store"),
    REGION("region"),
    ALL("all");

    private final String code;

    RowScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 严格解析：未知 code <b>绝不</b>回落。
     *
     * <p><b>为什么不是"回落 OWN_STORE"</b>（2026-09-21 订正 · QC-3）：
     * 回落成 {@code OWN_STORE} 看似"保守"（不越权），但它把<b>配置错误</b>变成了
     * <b>静默的数据缺失</b> —— 区域督导的 scope 若被写成 {@code "regin"}（拼错），
     * 系统不报任何错，督导只是永远看不到辖区数据。这类故障在运维侧极难归因
     * （表现为"数据好像少了"，而不是"配置错了"），且与 PRD 的 fail-closed 口径
     * （<b>未配置 / 配置非法即拒绝</b>，不得默认给值）字面冲突。
     *
     * <p>故：非法 code 一律抛出，由调用方决定如何处置（拒绝请求 / 告警 / 人工介入）。
     *
     * @throws TenantMismatchException 当 code 为 null、空串或非已知取值时
     */
    public static RowScope fromCode(String code) {
        if (code != null) {
            for (RowScope s : values()) {
                if (s.code.equals(code)) {
                    return s;
                }
            }
        }
        throw new TenantMismatchException(
                "未知的行级范围 code: " + (code == null ? "<null>" : "\"" + code + "\"")
                        + "（允许值: own_store / region / all）—— fail-closed，不回落默认范围");
    }

    /**
     * 宽容解析：未知 code 返回空。供"允许缺省"的调用点显式选择，
     * 使"我要缺省行为"与"我忘了处理非法值"在代码里可区分。
     */
    public static Optional<RowScope> tryFromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (RowScope s : values()) {
            if (s.code.equals(code)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}