package com.diaoyuanyun.dy.crypto.envelope;

/**
 * <b>数据主体</b>的引用 —— per-subject DEK 的作用域键。
 *
 * <p>为什么主体必须由"租户 + 主体类型 + 主体 id"三件套构成，而不是只有 subjectId：
 * PIPL 的删除权是<b>针对自然人</b>的。同一个自然人在不同租户下是<b>两条独立的数据</b>
 * （架构规格书 §8.4「中心身份映射表」要求能表达 tenant/region/store 三级归属）。
 * 若只用 subjectId，一个自然人跨租户就会共用一个 DEK —— 于是一家租户的删除请求
 * 会连带把另一家租户的数据变成不可读（跨租户误伤），或者两家租户的删除权互相牵制。
 * 两种结果都不可接受，所以租户必须在 DEK 作用域里。
 *
 * <p>{@code subjectType} 同样不可省：客户（customer）与调理师（therapist）都可能是
 * "自然人主体"，但它们在系统里是不同实体，id 空间不同，业务上的删除规则也不同。
 * 把它写进作用域键，可以避免"customer-123"与"therapist-123"撞成同一个 DEK。
 *
 * <p>本类不可变，且是 {@link AadBinding} 的输入之一 —— 主体识别错了，
 * 解密时认证就会失败（而不是悄悄解出别人的数据）。
 */
public record SubjectRef(String tenantId, String subjectType, String subjectId) {

    public SubjectRef {
        require(tenantId, "tenantId");
        require(subjectType, "subjectType");
        require(subjectId, "subjectId");
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("SubjectRef." + name + " 不可为空 —— "
                    + "主体作用域缺任一项都会让 per-subject 粒度退化成更粗的粒度，"
                    + "而退化的方向恰好是 ADR-12 已经【否决】的『仅租户级密钥』");
        }
        // ⚠️ NUL（U+0000）必须被拒绝。
        //
        // 理由不是"输入校验洁癖"，而是这个字符恰好是本模块【作用域键的拼接分隔符】：
        // InMemorySubjectKeyStore.key() 用 "\u0000" 把三件套拼成一个字符串
        // （与 AadBinding 的 "|" 分隔符是同一类纪律）。
        // 允许分量自带 NUL，就能构造出两个【不同的】SubjectRef 却拼出【同一个】键：
        //     SubjectRef(T, "b\0c", "d")   与   SubjectRef(T, "b", "c\0d")
        // 二者 equals=false（确是两条数据），但共用同一把 DEK —— 于是
        //   · per-subject 粒度在这两个主体之间被静默击穿（退化成共享密钥）；
        //   · 对其中一个行使删除权，会把另一个也一并变成永久不可读（删除权误伤）。
        // 两个后果都是 ADR-12 与 PIPL 明确要避免的方向，且都是【静默】发生的，
        // 因此必须在构造入口结构性堵住，而不是指望调用方自觉。
        if (v.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("SubjectRef." + name + " 含 NUL（U+0000）—— "
                    + "NUL 是本模块作用域键的拼接分隔符，分量自带 NUL 会让两个不同的主体"
                    + "拼出同一个键，从而【共用同一把 DEK】：既击穿 per-subject 粒度，"
                    + "又会让删除权误伤另一个主体（销毁其一时另一个也永久不可读）。"
                    + "若业务 id 里确实需要该字符，应先做可逆编码后再传入。");
        }
    }

    /** 客户主体（心率/血氧/睡眠的承载者）。 */
    public static SubjectRef customer(String tenantId, String customerId) {
        return new SubjectRef(tenantId, "customer", customerId);
    }

    /** 调理师主体（自然人，同样受 PIPL 删除权约束）。 */
    public static SubjectRef therapist(String tenantId, String therapistId) {
        return new SubjectRef(tenantId, "therapist", therapistId);
    }

    /** 便于日志与证据输出的稳定标识（不含任何密钥材料）。 */
    public String display() {
        return tenantId + "/" + subjectType + "/" + subjectId;
    }
}