package com.diaoyuanyun.dy.common.exception;

import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;

/**
 * 门禁未通过 (ADR-05)。置于 dy-common 以便 dy-web 全局处理器在不反向依赖 dy-security 的前提下使用。
 * 携带 {@code missingItems} 以便响应体回显缺失项名称 (严禁模糊报错, H-8)。
 */
public class GateMissingException extends BizException {

    private final List<String> missingItems;

    public GateMissingException(List<String> missingItems) {
        super(ErrorCode.GATE_MISSING, "门禁缺失: " + String.join(",", missingItems), missingItems);
        this.missingItems = missingItems;
    }

    /**
     * 携带<b>可读诊断原因</b>的重载（S2-10 域 B 新增）。
     *
     * <h2>为什么需要它：既有构造器的 message 只由缺失项名拼成</h2>
     * 既有构造器把 message 固定成 {@code "门禁缺失: " + join(missingItems)}，
     * 于是响应体里只有一句「门禁缺失: PROFILED」。而契约 §2.0 {@code forbidden-403} 逐字要求
     * 「message 必须给出缺失项名称 / 档位名称（<b>不得模糊报错</b>）」——
     * 给出名称是必要项，但<b>仅</b>给名称时，调用方仍不知道"为什么缺"、
     * "下一步该做什么"，特别是当同一个缺失项名对应两种成因时
     * （域 B 的 {@code screening_result} 就同时覆盖"没做筛查"与"筛查结论为不通过"两种情况，
     * 两者的下一步动作完全不同：一个是去补做筛查、一个是不该建档）。
     *
     * <p>🛑 <b>本重载不改变 {@code data.missing_items[]} 的内容</b>——
     * 缺失项名仍逐字来自上游（契约 / data-dict），{@code reason} 只进 {@code message}。
     * 这是刻意的：{@code missing_items[]} 是<b>机器可比对</b>的部分（前端按它分支、
     * 交付物按它核对），{@code message} 是<b>给人看</b>的部分（合规红线：严禁直接进客户端 UI）。
     * 两者混用会让"上游字面"被解释性文字污染。
     */
    public GateMissingException(List<String> missingItems, String reason) {
        super(ErrorCode.GATE_MISSING,
                "门禁缺失: " + String.join(",", missingItems)
                        + (reason == null || reason.isBlank() ? "" : " —— " + reason),
                missingItems);
        this.missingItems = missingItems;
    }

    public List<String> getMissingItems() {
        return missingItems;
    }
}
