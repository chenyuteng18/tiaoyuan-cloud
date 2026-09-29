package com.diaoyuanyun.dy.app.archive.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>结案归档签名块的 4 个键</b> —— 逐字对齐 PRD C.1.7
 * 「{@code 06.签名} → {@code case_archive.staff_signs} |
 * {@code json{经办人,经络师,门店负责人,日期}} | 是」，
 * 与迁移 V20 内 {@code v_sign_keys} 数组逐字对齐。
 *
 * <h2>🛑 为什么它是<b>文本型</b>而不是布尔型（与 {@link CaseArchiveChecklist} 的差别）</h2>
 * 两份 JSONB 的键集处置<b>必须不同</b>，否则会把一个真实的门禁做成假的门禁：
 * <table border="1">
 *   <caption>两份 JSONB 的语义差异</caption>
 *   <tr><th></th><th>{@code archive_checklist}</th><th>{@code staff_signs}</th></tr>
 *   <tr><td>问的是</td><td>"这件事做了吗"</td><td>"<b>谁</b>做的，<b>何时</b>"</td></tr>
 *   <tr><td>值的形态</td><td>布尔</td><td>非空字符串</td></tr>
 *   <tr><td>空值的含义</td><td>—（布尔无"空"）</td>
 *       <td>🛑 <b>一个空白的"门店负责人"不是签字</b></td></tr>
 *   <tr><td>故校验</td><td>值必须为 {@code true}（硬门禁 5 项）</td>
 *       <td>键必须存在<b>且值非空串</b></td></tr>
 * </table>
 * <p>🛑 DDL 的 {@code NOT NULL} 只能保证 {@code staff_signs} 这个 JSONB
 * <b>整体</b>不为空，挡不住它里面是 {@code {}} 或 {@code {"store_owner":""}}。
 * 而 `06.§八 归档 6 项` 里"负责人已签字"与这份签名块是<b>两件不同的事</b>：
 * 前者是一个布尔（"签了吗"），后者是签字的<b>身份与时间</b>。
 * 两份都在库里，且都要有门禁 —— 只查布尔会让"签了但不知道谁签的"通过；
 * 只查签名块会让"四个人都签了但结论没被负责人确认"通过。
 *
 * <h2>🛑 为什么没有"客户签字"这一键</h2>
 * PRD §2.22 逐字写「脱敏须<b>单独授权</b>；归档须<b>客户签字</b>」，
 * 而 C.1.7 的签名块键集只有 {@code {经办人,经络师,门店负责人,日期}} ——
 * <b>没有客户</b>。
 * <p>两者不矛盾，但落差必须解释：客户侧的书面确认由
 * {@code case_archive.archive_checklist} 的"负责人已签字"（ARC-12）与
 * {@code consent} 域承担，而不是由本签名块承担。
 * <p>🛑 本类<b>不</b>自行补一个 {@code customer} 键 ——
 * 那会让库里出现一个<b>没有权威来源</b>的字段，而它的存在会让人以为
 * "客户签字的落纸问题已解决"。这是一个真实的口径落差，
 * 故在类注释里显式登记它，而不是用代码把它抹平。
 *
 * <h2>它【不】回答什么</h2>
 * 本类不做"签名不可伪造"级别的任何判断（那需要电子签通路，
 * 属缺口修复总规划的 G-C 待裁项）；它只回答"这份签名块表达完整了吗"。
 */
public enum CaseArchiveSignKey {

    /** 经办人（PRD C.1.7 逐字）。 */
    HANDLER("handler", "经办人"),

    /** 经络师（PRD C.1.7 逐字；本仓既有角色名"经络调理师"的签名侧简称）。 */
    MERIDIAN_THERAPIST("meridian_therapist", "经络师"),

    /** 门店负责人（PRD C.1.7 逐字）。 */
    STORE_OWNER("store_owner", "门店负责人"),

    /**
     * 日期（PRD C.1.7 逐字）。
     *
     * <p>🛑 它是<b>字符串</b>而不是日期类型：{@code staff_signs} 是 JSONB，
     * 而库层没有对它做结构约束（V5 只写了 {@code JSONB NOT NULL}）。
     * 若本类把它做成 {@code LocalDate}，就会在 Java 侧造出一份
     * <b>比库层更严</b>的口径 —— 而"哪个更严"取决于谁先跑，
     * 属本仓反复记录过的口径分叉。
     */
    SIGN_DATE("sign_date", "日期");

    private final String code;
    private final String label;

    CaseArchiveSignKey(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** JSONB 键字面（snake_case）。 */
    public String code() {
        return code;
    }

    /** 中文标签（供错误消息与运维回执）。 */
    public String label() {
        return label;
    }

    /** 全部 4 键（声明顺序 = PRD C.1.7 的列举顺序）。 */
    public static List<CaseArchiveSignKey> all() {
        return List.of(values());
    }

    /** 全部 4 键的码集（顺序稳定）。 */
    public static Set<String> allCodes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(CaseArchiveSignKey::code).toList());
    }

    /** 严格解析：未登记一律抛，绝不回落（理由同 {@link CaseArchiveChecklist#of}）。 */
    public static CaseArchiveSignKey of(String code) {
        for (CaseArchiveSignKey k : values()) {
            if (k.code.equals(code)) {
                return k;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的签名块键: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记 4 键: " + allCodes() + "；权威来源 = PRD C.1.7「06.签名」）—— "
                        + "🛑 不得静默丢弃：多出一个未登记键通常意味着"
                        + "某个必签角色的键名被改错了，而静默丢弃会让"
                        + "\"这个人签了字\"在库里无处安放");
    }
}