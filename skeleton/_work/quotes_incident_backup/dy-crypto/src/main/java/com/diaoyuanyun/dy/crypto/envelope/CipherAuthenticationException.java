package com.diaoyuanyun.dy.crypto.envelope;

/**
 * 密文认证失败（密钥不对 / 密文被篡改 / AAD 绑定不匹配）。
 *
 * <p>三种原因刻意<b>不区分</b>：AEAD 在设计上就不区分它们，声称能分辨等于泄露
 * 一份 oracle（攻击者可以据此逐位试出密钥）。因此本异常是"解不开"的唯一出口，
 * 而"解不开的<b>原因分类</b>"由上层按业务上下文判定（例如：DEK 已销毁 vs 密钥错）。
 */
public class CipherAuthenticationException extends RuntimeException {

    public CipherAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}