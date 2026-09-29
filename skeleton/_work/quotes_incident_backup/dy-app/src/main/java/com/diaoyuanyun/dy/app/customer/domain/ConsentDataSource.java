package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 同意的数据来源 —— 契约域 B3 的 {@code consent.data_source}，
 * 逐字对齐 V5 {@code consent} 的 CHECK 与 DEFAULT。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  V5 L206~207: data_source VARCHAR(32) NOT NULL DEFAULT 'self-report'
 *               CHECK (data_source IN ('self-report', 'device'))
 *  data-dict §2.8: 默认 'self-report'；CHECK self-report / device
 * </pre>
 *
 * <h2>🛑 它的语义是"这条同意是怎么来的"，不是"数据从哪采的"</h2>
 * 这一点值得写清，因为列名很容易被读错：本列描述的是<b>同意行为本身</b>的采集方式 ——
 * {@code self-report} = 客户本人确认（默认），{@code device} = 经设备侧确认。
 * 它<b>不</b>描述手环数据的来源（那是 {@code band_telemetry} 的事），
 * 也<b>不</b>是"客户是否佩戴手环"（那是 {@link BandWillingness}）。
 *
 * <p>三个概念各自独立、各有其列，<b>不得互相代填</b>：
 * <pre>
 *   BandWillingness  → 意愿（戴不戴）
 *   ConsentDataSource→ 同意行为的采集方式（谁确认的）
 *   band_telemetry   → 数据本身（另有其表与口径）
 * </pre>
 *
 * <h2>默认值纪律</h2>
 * 库层 {@code DEFAULT 'self-report'} 与契约的默认口径一致，但<b>写入路径不得依赖
 * 库层默认值</b>（本仓库既有教训：库层默认值在本域已经出过一次偏差 ——
 * {@code customer.status} 的骨架默认 {@code 'pending'} 与权威 5 值不一致）。
 * 故服务层<b>显式给出</b>本值，库层默认只是兜底。
 */
public enum ConsentDataSource {

    /** 客户本人确认（默认）。契约 / 库层字面 {@code self-report}。 */
    SELF_REPORT("self-report", "客户本人确认"),

    /** 经设备侧确认。契约 / 库层字面 {@code device}。 */
    DEVICE("device", "设备侧确认");

    private final String code;
    private final String label;

    ConsentDataSource(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 库层字面（kebab-case，非中文）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 库层 / 契约的默认值。写入路径应显式传它，不依赖库层 DEFAULT。 */
    public static ConsentDataSource defaultSource() {
        return SELF_REPORT;
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static ConsentDataSource of(String code) {
        for (ConsentDataSource s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的同意来源字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记: self-report / device；权威来源 = V5 consent.data_source 的 CHECK）—— "
                        + "🛑 不得回落为 self-report：回落会把一次『来源不明』静默伪装成"
                        + "『客户本人确认』，而这两者在证据链上的证明力不同");
    }
}