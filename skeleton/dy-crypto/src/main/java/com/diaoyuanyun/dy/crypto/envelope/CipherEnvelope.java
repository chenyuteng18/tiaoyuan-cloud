package com.diaoyuanyun.dy.crypto.envelope;

import java.util.Base64;

/**
 * 密文信封 —— 落库的那一列到底存什么。
 *
 * <h2>信封里为什么必须有 {@code algorithmId} 和 {@code dekVersion}</h2>
 * 这两个字段是 DoD ⑤ "算法可替换位"与 DEK 轮换能在<b>不改历史数据</b>的前提下成立的
 * 全部依据：
 * <ul>
 *   <li>{@code algorithmId} —— 解密时按它去 {@link com.diaoyuanyun.dy.crypto.algorithm.AlgorithmRegistry}
 *       取实现。于是换算法只影响<b>新写入</b>的数据，老数据仍按老算法解，无需批量重写。
 *       若把它写死在代码里，换算法就等于一次全表迁移，而迁移窗口内必然有人读不出来。</li>
 *   <li>{@code dekVersion} —— DEK 轮换后，老密文仍指向老版本 DEK。没有它，轮换就等于
 *       让历史数据永久不可读（即"意外的 crypto-shredding"，等于把备份纪律事故固化下来）。</li>
 * </ul>
 *
 * <h2>格式（ansi text，可直接落 text 列）</h2>
 * <pre>{@code
 *   dy1:<algorithmId>:<dekVersion>:<base64(nonce)>:<base64(ciphertext)>
 * }</pre>
 * 首段 {@code dy1} 是<b>信封格式版本</b>，与 AAD 的 {@code v1} 各管一件事：
 * 前者管"这段文本怎么切分"，后者管"哪些字段参与认证"。两者独立演进，
 * 混成一个版本号会导致"只想改切分方式，却把所有历史密文的认证全部打断"。
 *
 * <h2>为什么用文本而不是 jsonb / bytea</h2>
 * 文本信封可以被 {@code psql} 直接读、被日志直接打、被 CSV 直接导出，而不会因为
 * 二进制转义在中间环节被悄悄改写。密文完整性由 AEAD tag 保证，
 * <b>不</b>依赖存储层不做转换 —— 存储层真改了内容，解密会认证失败而不是解出错的明文。
 * 这是有意选择的：让"存储层动手脚"表现为红，而不是表现为错值。
 */
public record CipherEnvelope(String algorithmId, int dekVersion, byte[] nonce, byte[] ciphertext) {

    /** 信封格式版本前缀。改动切分方式必须改它。 */
    public static final String PREFIX = "dy1";

    private static final String SEP = ":";

    public CipherEnvelope {
        if (algorithmId == null || algorithmId.isBlank()) {
            throw new IllegalArgumentException("信封缺少算法位 —— 解密时无从选择实现");
        }
        if (algorithmId.contains(SEP)) {
            throw new IllegalArgumentException("算法标识不得含 ':'（会破坏信封切分）: " + algorithmId);
        }
        if (dekVersion < 1) {
            throw new IllegalArgumentException("dekVersion 必须 >= 1，实际=" + dekVersion);
        }
        if (nonce == null || nonce.length == 0) {
            throw new IllegalArgumentException("信封缺少 nonce —— 无法解密");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("信封密文为空 —— 空密文不可能来自一次成功加密"
                    + "（AEAD 即使加密空明文也会产出 16 字节 tag），故视为损坏");
        }
    }

    /** 序列化为可落库的文本。 */
    public String serialize() {
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + SEP + algorithmId + SEP + dekVersion + SEP
                + b64.encodeToString(nonce) + SEP + b64.encodeToString(ciphertext);
    }

    /**
     * 解析落库文本。
     *
     * <p>解析失败一律抛 {@link EnvelopeFormatException}，<b>不返回 null、不做猜测性修复</b>。
     * 猜测性修复（例如"少一段就当作空 nonce"）会让损坏的数据表现为"解出来的内容不对"，
     * 而真因是信封坏了 —— 两种故障的处置方式完全不同，不该被同一段代码抹平。
     */
    public static CipherEnvelope parse(String text) {
        if (text == null || text.isBlank()) {
            throw new EnvelopeFormatException("信封文本为空");
        }
        String[] parts = text.split(SEP, -1);
        if (parts.length != 5) {
            throw new EnvelopeFormatException("信封应为 5 段（前缀:算法:版本:nonce:密文），实际 "
                    + parts.length + " 段");
        }
        if (!PREFIX.equals(parts[0])) {
            throw new EnvelopeFormatException("信封前缀应为 '" + PREFIX + "'，实际 '" + parts[0]
                    + "' —— 若这是未来版本的信封，需要先升级读取端而不是就地解析");
        }
        int version;
        try {
            version = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new EnvelopeFormatException("DEK 版本段不是整数: '" + parts[2] + "'", e);
        }
        byte[] nonce;
        byte[] ciphertext;
        try {
            nonce = Base64.getDecoder().decode(parts[3]);
            ciphertext = Base64.getDecoder().decode(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new EnvelopeFormatException("信封 base64 段非法（nonce/ciphertext）", e);
        }
        return new CipherEnvelope(parts[1], version, nonce, ciphertext);
    }

    /**
     * 诊断用：只输出可公开的元信息。
     *
     * <p><b>有意不实现 {@code toString()} 输出密文/nonce 内容</b>：密文进日志是一条
     * 常见且难查的泄露路径（日志留存 6~12 个月，且有日志访问权限的人远多于有数据访问权限的人）。
     * 真正需要密文时应当显式取 {@link #ciphertext()}，而不是靠 {@code toString()} 顺手带出来。
     */
    public String describe() {
        return PREFIX + SEP + algorithmId + SEP + dekVersion
                + SEP + "nonce=" + nonce.length + "B"
                + SEP + "ct=" + ciphertext.length + "B";
    }

    @Override
    public String toString() {
        return "CipherEnvelope[" + describe() + "]";
    }
}