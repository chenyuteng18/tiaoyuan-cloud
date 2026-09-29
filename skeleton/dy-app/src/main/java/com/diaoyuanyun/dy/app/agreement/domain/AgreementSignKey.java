package com.diaoyuanyun.dy.app.agreement.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>调理协议书四方签署的 4 个键</b> —— 逐字对齐 PRD C.1.5 §八
 * 「{@code agreement.signatures} | {@code json{客户/经络师/调理师/门店负责人:签名+日期}}
 * | 是 | <b>未签不得首次调理或退款判定</b> | <b>硬门禁</b>」，
 * 与迁移 {@code V21__agreement_offline_signing_provisioning.sql} 内
 * {@code v_sign_keys} 数组逐字对齐（该数组由 V21 自证 (b7) 机械断言"恰 4 个"）。
 *
 * <h2>🛑🛑 为什么这 4 个键是【文本】而不是布尔（与 {@link CaseArchiveSignKey} 同族但更严）</h2>
 * 归档域问的是"某件事做了吗"（布尔）与"谁做的"（文本）两件事；
 * <b>本域只问后者</b> —— PRD §八 的列口径是"签名+日期"，即
 * <b>承载签署人身份</b>，而不是"某人是否签了"。
 * <p>⇒ 一个空白的"门店负责人"<b>不是签字</b>。故本域的 4 个键
 * 都要求值<b>存在、是字符串、且非空白</b>（见 {@link AgreementRecord} 的归一）。
 * DDL 的 {@code signer jsonb NOT NULL} 只能保证整个对象不为空，
 * 挡不住它里面是 {@code {}} 或 {@code {"therapist":"   "}}。
 *
 * <h2>🛑🛑 键名语言的处置：采用英文 snake_case，并【登记为待裁】</h2>
 * PRD C.1.5 §八 用<b>中文名</b>描述这 4 方（客户 / 经络师 / 调理师 / 门店负责人），
 * 而契约里<b>没有任何地方</b>冻结过这 4 个键的<b>字面</b>。
 * 本仓既有 JSONB 范式（{@code ConsentAuthScope.auth_scope_json}、
 * {@code case_archive.staff_signs}）一律用 snake_case 英文 code。
 * <p>⇒ 本枚举<b>采用英文 snake_case</b>，三条理由：
 * <ol>
 *   <li>与本仓既有 JSONB 范式一致（同一套 code 习惯，不引入第二套）；</li>
 *   <li>对"未知键必须抛"更可机械判定（见 {@link #of}）——
 *       英文 code 与中文标签的一一对应是枚举自身的性质，
 *       而若用中文键名，"键名本身是否写对"就无法与"值是否为空"分开报错；</li>
 *   <li>库层 V21 的 {@code v_sign_keys} 数组用的就是这 4 个英文 code，
 *       而该数组被 V21 自证 (b7)/(b7b) 双重断言。
 *       <b>两处必须逐字一致</b>，否则本枚举与库层门禁会分叉。</li>
 * </ol>
 * <p>🛑 但本枚举<b>不代拍</b>"英文键名是最终裁定"这件事 ——
 * V21 文件头「待裁登记」第 1 条(a) 已逐字登记它。
 * 若最终裁定改用中文键名，须<b>同时</b>改三处：本枚举、V21 的 {@code v_sign_keys}、
 * 以及 V21 自证 (b7)/(b7b) 的断言（而不是删掉断言 —— 本仓纪律：归零要求显式动作）。
 *
 * <h2>🛑🛑 这里【没有】日期键 —— 且这是一个真实的 PRD 落差，不是遗漏</h2>
 * PRD §八 逐字要求「签名+<b>日期</b>」（即每个签署人各自带日期），
 * 而真库 {@code agreement} 表实测只有<b>一列</b> {@code signed_at TIMESTAMPTZ NOT NULL}
 * （16 列中没有 per-signer 的日期列，也没有 {@code signatures} 列 ——
 * 承载四方签署的实际列名是 <b>{@code signer}</b>）。
 * <p>⇒ <b>库层没有载体</b>表达"每人各自的签署日期"。
 * 故本枚举<b>刻意不补一个 {@code sign_date} 键</b>：
 * 补了它，那个键就没有任何库层落脚处（落进去也不被任何读点消费），
 * 而它的存在会让人以为"per-signer 日期问题已解决" —— 这正是本仓
 * 反复要防的<b>"死字段"形态</b>（与 {@code case_archive.effect_confirm_pdf} 同族）。
 * <p>本枚举的处置与 V21 一致：<b>4 方身份落在 {@code signer}，
 * 日期统一落在 {@code signed_at}</b>；落差登记在 V21 文件头待裁第 1 条(b)，
 * 三条候选路径（接受现状 / {@code signer} 值改对象 / 加 {@code signatures} 列）
 * 均<b>未裁定</b>，本枚举不代拍。
 *
 * <h2>🛑 还有一处同源落差，一并登记</h2>
 * PRD 附录 C.1 把<b>列名</b>写作 {@code signatures}（复数），
 * 而真库列名是 {@code signer}（单数）。本枚举以<b>真库为准</b>；
 * "PRD 字段名与真库列名不一致"这件事已在 V21 文件头登记 ——
 * 因为按 PRD 字面写的代码会在运行时以"列不存在"失败。
 *
 * <h2>它【不】回答什么</h2>
 * 本枚举不做"签名不可伪造"级别的任何判断 —— 那需要电子签厂商（契约 H1），
 * 而 H1 的厂商签名算法<b>尚未冻结</b>（契约逐字：厂商选定之前三端不得据本节编码）。
 * 本迁移是<b>离线签署通路</b>（运维通路形态、无 HTTP 映射、不解析任何厂商签名），
 * 故本枚举只回答"这份签署块表达完整了吗"。
 */
public enum AgreementSignKey {

    /** 客户（PRD C.1.5 §八 第 1 方）。 */
    CUSTOMER("customer", "客户"),

    /** 经络师（PRD C.1.5 §八 第 2 方；本仓既有角色名"经络调理师"）。 */
    MERIDIAN_THERAPIST("meridian_therapist", "经络师"),

    /** 调理师（PRD C.1.5 §八 第 3 方）。 */
    THERAPIST("therapist", "调理师"),

    /** 门店负责人（PRD C.1.5 §八 第 4 方）。 */
    STORE_OWNER("store_owner", "门店负责人");

    private final String code;
    private final String label;

    AgreementSignKey(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** JSONB 键字面（snake_case）。 */
    public String code() {
        return code;
    }

    /** 中文标签（供错误消息与运维回执；断言不依赖它）。 */
    public String label() {
        return label;
    }

    /** 全部 4 键（声明顺序 = PRD C.1.5 §八 的列举顺序）。 */
    public static List<AgreementSignKey> all() {
        return List.of(values());
    }

    /** 全部 4 键的码集（顺序稳定）。 */
    public static Set<String> allCodes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(AgreementSignKey::code).toList());
    }

    /**
     * 严格解析：未登记一律抛，绝不回落。
     *
     * <p>🛑 与 {@link CaseArchiveSignKey#of} 同款，但本域的后果<b>更直接</b>：
     * 忽略一个未登记键，会同时造成两件事 ——
     * ① 某一方被判为"未签"（报缺项，归因部分正确）；
     * ② 而调用方以为自己填了（下一次他会再犯）。
     * <p>在归档域，签名块缺项的后果是"这份档案不该存在"；
     * 在本域，它的后果是"<b>未签不得首次调理或退款判定</b>"这条硬门禁被绕过 ——
     * 而 `06 屏首次调理` 与退款判定都会因此放行一个客户，
     * 而协议可能从未被四方签署。
     */
    public static AgreementSignKey of(String code) {
        for (AgreementSignKey k : values()) {
            if (k.code.equals(code)) {
                return k;
            }
        }
        throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                "未登记的签署块键: " + (code == null ? "<null>" : "『" + code + "』")
                        + "（已登记 4 键: " + allCodes() + "；权威来源 = PRD C.1.5 §八）—— "
                        + "🛑 不得静默丢弃：多出一个未登记键通常意味着"
                        + "某个必签角色的键名被写错了，而静默丢弃会让"
                        + "『这个人签了字』在库里无处安放，同时又让"
                        + "『某方未签』这条硬门禁被绕过");
    }
}