package com.diaoyuanyun.dy.app.doctpl.storage;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code doc_template.file_ref} 的机器构造器与解析器（V5 §2.27 逐字）。
 *
 * <h2>🛑 为什么要有这个类（而不是在服务里拼字符串）</h2>
 * V5 §2.27 把引用格式写死成
 * <pre>oss://&lt;bucket&gt;/&lt;tenant&gt;/&lt;doc_type&gt;/&lt;version&gt;/&lt;hash&gt;</pre>
 * 若"写入时拼"与"读取时解析"各写一遍，二者迟早分叉（多一个斜杠、doc_type 未编码…），
 * 且分叉的表现是"上传成功但下载 404"——一次部署后才暴露的故障。
 * 故拼装与解析必须来自<b>同一段代码</b>，并且这条不变式要能被断言（见配套测试）。
 *
 * <h2>🛑 doc_type 是中文 ⇒ 必须 URL 编码</h2>
 * 六个 doc_type 里四个是中文（知情同意书 / 调理协议书 / 手环数据说明 / 隐私与授权须知…）。
 * 中文字符出现在对象键里会带来两类问题：① 不同 SDK / 代理对非 ASCII 键的处理不一致
 * （有的按 UTF-8、有的按 Latin-1）；② 签名计算与 URL 转义口径不一致时签名失效。
 * 故本类对 doc_type 段做 {@code URLEncoder} 编码，解析时对称解码 ——
 * 这样对象键<b>永远是纯 ASCII</b>，跨厂商跨代理都稳定。
 *
 * <h2>不变式（由测试钉住）</h2>
 * {@code parse(build(t, dt, v, h)) == (t, dt, v, h)}，对全部六个 doc_type 成立。
 */
public final class DocFileRef {

    /** V5 §2.27 的引用前缀（逐字）。 */
    public static final String SCHEME = "oss://";

    /** 骨架默认 bucket 名；生产由部署配置注入（本类只负责格式，不负责取配置）。 */
    public static final String DEFAULT_BUCKET = "diaoyuanyun-doc";

    /**
     * 解析用正则：{@code oss://bucket/<tenant-uuid>/<encoded-doc-type>/<version>/<hash>}
     *
     * <p>doc_type 段允许 {@code %XX} 转义序列，不允许裸斜杠（否则段数会漂）。
     */
    private static final Pattern PATTERN = Pattern.compile(
            "^oss://([^/]+)/([0-9a-fA-F-]{36})/([^/]+)/(\\d+)/([0-9a-f]{64})$");

    private DocFileRef() {
    }

    /** 组装引用（bucket 取默认值）。 */
    public static String build(String tenantId, String docType, int version, String fileHash) {
        return build(DEFAULT_BUCKET, tenantId, docType, version, fileHash);
    }

    /**
     * 组装引用。
     *
     * @param bucket    存储桶
     * @param tenantId  租户 UUID（V5 的 tenant_id 为 UUID ⇒ 段内只可能是 hex 与连字符）
     * @param docType   六个中文/中文外取值之一（本方法负责编码）
     * @param version   版本号（≥ 1）
     * @param fileHash  SHA-256 hex（64 字符）
     */
    public static String build(String bucket, String tenantId, String docType, int version,
                               String fileHash) {
        if (bucket == null || bucket.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "bucket 为空，无法构造对象引用");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "tenant_id 为空，无法构造对象引用");
        }
        if (docType == null || docType.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "doc_type 为空，无法构造对象引用");
        }
        if (version < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "version 须 ≥ 1，无法构造对象引用");
        }
        if (fileHash == null || !fileHash.matches("^[0-9a-f]{64}$")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "file_hash 必须是 64 位小写 SHA-256 hex，无法构造对象引用: " + fileHash);
        }
        return SCHEME + bucket + "/" + tenantId + "/"
                + java.net.URLEncoder.encode(docType, java.nio.charset.StandardCharsets.UTF_8)
                + "/" + version + "/" + fileHash;
    }

    /** 解析结果三段（doc_type 已解码回中文原文）。 */
    public record Parsed(String bucket, String tenantId, String docType, int version, String fileHash) {
    }

    /**
     * 解析引用。
     *
     * @throws BizException 格式非法（含"不是本产品写出的引用"）
     */
    public static Parsed parse(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "对象引用为空");
        }
        Matcher m = PATTERN.matcher(ref.trim());
        if (!m.matches()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "对象引用格式非法（应形如 "
                            + SCHEME + "bucket/tenant/docType/version/sha256hex）: " + ref);
        }
        return new Parsed(
                m.group(1),
                m.group(2),
                java.net.URLDecoder.decode(m.group(3), java.nio.charset.StandardCharsets.UTF_8),
                Integer.parseInt(m.group(4)),
                m.group(5));
    }
}