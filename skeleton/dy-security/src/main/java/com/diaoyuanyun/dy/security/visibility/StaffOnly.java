package com.diaoyuanyun.dy.security.visibility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 契约级"仅 staff 可调"声明 —— 对应契约的 {@code x-client-explicitly-denied: true}。
 *
 * <h2>它与 {@code @RequirePermission} 的分工（两者不可互相替代）</h2>
 * <ul>
 *   <li>{@code @RequirePermission} 管的是<b>功能权限码</b>（如 {@code customer:read}），
 *       来源是骨架的角色→权限映射表；它回答"你的岗位允不允许做这件事"。</li>
 *   <li>本注解管的是<b>契约里的端点可见性档位</b>（{@code x-callable-roles}），
 *       来源是三端契约的冻结行；它回答"这个端点<b>在契约上</b>是否对这类身份开放"。</li>
 * </ul>
 * 二者语义不同，故不能互相替代。本注解存在的具体理由是 E4：
 * 契约把 {@code /customers/{id}/band/derived} 标为 {@code x-client-explicitly-denied: true} ——
 * 即<b>客户调本接口一律 403，与他在参数里写了什么无关</b>。
 *
 * <h2>🛑 为什么必须有它（而不是靠入站字段扫描）</h2>
 * 入站扫描（{@code DerivedVisibilityInterceptor}）拒的是"请求里<b>点名</b>了派生字段"。
 * 但客户调 E4 时完全可以<b>一个参数都不带</b>：{@code GET /customers/{id}/band/derived} ——
 * 此时扫描结果为"未点名任何字段"，请求会一路走到控制器，
 * 而该控制器的响应体<b>整个就是派生字段</b>（{@code as_value} / {@code effect_verdict} / …）。
 * <b>出口兜底也救不了它</b>：出口兜底会把派生字段全部摘除，于是客户拿到一个
 * {@code 200} + 空 {@code data} —— 不泄漏，但契约要求的是 <b>403</b>，
 * 而调用方会以为"这个接口成功且没数据"，把一次可见性拒绝误读成一次空结果。
 *
 * <p>故客户对这类端点必须被<b>按端点</b>拒绝，而不是按字段。这就是本注解的职责。
 *
 * <h2>回显字段名（{@link #clientDeniedFields()}）</h2>
 * 契约 §3.2 要求 {@code data.denied_fields[]} 回显被拒字段名。对"整只端点拒绝"的情形，
 * 被拒的是<b>该端点的全部派生字段</b>（契约 {@code x-client-forbidden} 的语义就是"整个响应"），
 * 故由注解逐项声明并在 403 里回显 —— 使客户端能直接读到"这个端点里有哪几样东西不是给我的"，
 * 而不是只得到一句"无权"。这也是 P0-08「不得模糊报错」在端点级可见性上的落地。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StaffOnly {

    /**
     * 客户访问时回显的被拒字段名（契约 {@code data.denied_fields[]}）。
     *
     * <p>🛑 不得留空：不带被拒字段名的可见性拒绝等于模糊报错，
     * 且 {@code VisibilityDeniedException} 的构造器本身就会拒收空清单。
     */
    String[] clientDeniedFields();
}