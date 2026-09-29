package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 客户性别 —— 契约 B2 {@code CustomerCreateRequest.gender}，逐字对齐 data-dict §2.6。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  data-dict §2.6 customer.gender: string NOT NULL CHECK ∈ {男, 女}
 *  契约 CustomerCreateRequest.gender: string enum [男, 女]
 * </pre>
 *
 * <h2>🛑 只有两值，这不是"待补"而是上游冻结算法的结果</h2>
 * 性别在本系统里<b>有实际算法用途</b>：年龄分组
 * （{@code AgeGroup} 的 {@code 男16-32 / 男33-40 / … / 女14-28 / … 女43-49以上}，
 * 见契约 {@code AgeGroup}）由性别 + 年龄两维确定。
 * 故"性别"是量表分组的<b>输入之一</b>，而不是一个统计字段 ——
 * 增加第三个值（如"未知/不便透露"）会立刻让 {@code AgeGroup} 的取值集出现空洞，
 * 而那个空洞会以"某个客户算不出分组"的形式在下游爆出。
 *
 * <p>故本枚举严格两值、fail-closed；若业务确实需要第三值，
 * 正确的动作是<b>同步变更契约 {@code AgeGroup} 的取值集</b>并走「变更业务裁定」，
 * 而不是在此处放宽。
 */
public enum Gender {

    /** 男。契约 / 库层字面 {@code 男}。 */
    MALE("男", "男", "男"),

    /** 女。契约 / 库层字面 {@code 女}。 */
    FEMALE("女", "女", "女");

    private final String code;
    private final String label;
    /** {@code AgeGroup} 取值的前缀（如 {@code 男16-32} 的 {@code 男}）。 */
    private final String ageGroupPrefix;

    Gender(String code, String label, String ageGroupPrefix) {
        this.code = code;
        this.label = label;
        this.ageGroupPrefix = ageGroupPrefix;
    }

    /** 契约 / 库层字面（中文单字）。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 年龄分组前缀 —— 契约 {@code AgeGroup} 的取值以它为开头（{@code 男16-32} / {@code 女14-28}）。 */
    public String ageGroupPrefix() {
        return ageGroupPrefix;
    }

    /** 严格解析：未登记一律抛，绝不回落。 */
    public static Gender of(String code) {
        for (Gender g : values()) {
            if (g.code.equals(code)) {
                return g;
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED,
                "未登记的性别字面: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（权威来源 = data-dict §2.6 customer.gender 的 CHECK：男 / 女）—— "
                        + "🛑 不得回落：性别是契约 AgeGroup（年龄分组）的输入维度之一，"
                        + "一个猜出来的性别会让该客户被分进错误的量表组，"
                        + "而分组错误不会报错，只会让分数口径整体偏移");
    }
}