package com.diaoyuanyun.dy.crypto.envelope;

import java.nio.charset.StandardCharsets;

/**
 * AAD（Additional Authenticated Data）绑定串 —— 把"这份密文属于谁"变成密码学事实。
 *
 * <h2>它挡的是哪一种攻击</h2>
 * 只加密的话，密文行本身是"裸"的：一个有数据库写权限的人可以把 A 客户的血氧密文行
 * 复制到 B 客户的名下（行里的 {@code customer_id} 改成 B，密文原样搬）。这条数据
 * 仍然能正常解密（密钥没变），于是 B 客户的健康档案里凭空多出 A 客户的血氧读数。
 * 加密完好无损，<b>归属关系</b>却被改掉了 —— 单纯加密挡不住这种事。
 *
 * <p>解法：把归属信息（租户 / 主体 / 字段 / DEK 版本）作为 AAD 参与认证，但不参与加密。
 * GCM 的 tag 覆盖 AAD，因此"换一个主体来解密"必然认证失败。
 *
 * <h2>为什么把 dekVersion 也放进 AAD</h2>
 * DEK 轮换会产生新版本的 DEK，密文行里存着"我用的是哪一版"。若版本号不参与认证，
 * 攻击者可以把版本号改成任意值去试别的 KEK 版本 —— 版本号在解密路径上是一个
 * <b>选择器</b>，选择器被篡改就等于密钥选择被篡改。把它纳入 AAD，便不可篡改。
 *
 * <h2>格式约定与纪律</h2>
 * 用 {@code '|'} 分隔、字段间无转义。这要求各字段值本身<b>不含</b> {@code '|'}
 * —— 由 {@link #field(String, String)} 强制校验。若不校验，一个含 {@code '|'} 的
 * subjectId 就能构造出与另一个主体完全相同的 AAD 串（AAD 碰撞），
 * 归属绑定随之失效。这是"拼接式 AAD"的经典翻车点，故在入口处拒绝而非事后补救。
 */
public final class AadBinding {

    private static final String SEP = "|";

    private AadBinding() {
    }

    /**
     * 构造归属绑定串。
     *
     * <p>字段顺序即语义顺序，且<b>版本化</b>（首位 {@code v1}）：
     * 将来若要加入 region/store（架构规格书 §8.4 提到三级归属），必须升版本号，
     * 而不是就地改字段顺序 —— 就地改会让历史密文全部解不开，且失败信息看起来
     * 像"密钥丢了"，而不是"AAD 规范改了"。
     */
    public static byte[] of(SubjectRef subject, String fieldName, int dekVersion) {
        StringBuilder sb = new StringBuilder("v1");
        sb.append(SEP).append(field(subject.tenantId(), "tenantId"));
        sb.append(SEP).append(field(subject.subjectType(), "subjectType"));
        sb.append(SEP).append(field(subject.subjectId(), "subjectId"));
        sb.append(SEP).append(field(fieldName, "fieldName"));
        sb.append(SEP).append(dekVersion);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造 <b>DEK 包裹体</b>的归属绑定串（把 DEK 包进 KEK 时用）。
     *
     * <p>与 {@link #of(SubjectRef, String, int)} 分开是因为两者的绑定对象不同：
     * 前者的绑定域是"某个主体的某个字段的某个 DEK 版本"，后者的绑定域是
     * "某主体的某个 DEK 版本被哪一代 KEK 包裹"。共用同一个串会让两类密文
     * 可以互相替换（把字段密文当成包裹体喂给解包路径），故域必须分开 ——
     * 这个设计叫<b>域分离</b>（domain separation），是密码学里"一个密钥不得服务两个用途"
     * 的具体落地。这里的 {@code "wrap"} 就是域标签。
     *
     * <p>把 {@code kekId} 纳入绑定，使"用另一代 KEK 去解包裹"必然失败：
     * 否则攻击者可以拿任意一代 KEK 逐代试，而版本号字段本身是可篡改的。
     */
    public static byte[] ofDekWrap(SubjectRef subject, int dekVersion, String kekId) {
        StringBuilder sb = new StringBuilder("v1");
        sb.append(SEP).append("wrap");
        sb.append(SEP).append(field(subject.tenantId(), "tenantId"));
        sb.append(SEP).append(field(subject.subjectType(), "subjectType"));
        sb.append(SEP).append(field(subject.subjectId(), "subjectId"));
        sb.append(SEP).append(dekVersion);
        sb.append(SEP).append(field(kekId, "kekId"));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 校验字段不含分隔符。
     *
     * <p>这是"拼接式 AAD"必须付的代价：不校验就会产生 AAD 碰撞，
     * 而碰撞的表现是"两个不同主体的数据可以互相解密"—— 一个只有专门构造才能触发、
     * 且不会以任何红点形式出现的漏洞。宁可在入口处拒绝一个含 {@code '|'} 的 id，
     * 也不要在归属绑定上留一个理论缺口。
     */
    private static String field(String v, String name) {
        if (v == null) {
            throw new IllegalArgumentException("AAD 字段 " + name + " 为 null");
        }
        if (v.indexOf('|') >= 0) {
            throw new IllegalArgumentException("AAD 字段 " + name + " 含分隔符 '|' —— "
                    + "会让不同主体构造出相同 AAD 串（归属绑定失效），必须在入口拒绝");
        }
        return v;
    }
}