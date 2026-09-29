package com.diaoyuanyun.dy.app.doctpl.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * 对象存储访问的最小语义面（B-4 · I3 上传 / I7 下载 的唯一存储出口）。
 *
 * <h2>🛑 为什么先抽象、后落地厂商 SDK</h2>
 * 契约 I3/I7 只冻结了「字节 + 哈希 + 引用串」三件事，<b>没有冻结任何厂商</b>：
 * <pre>
 *  file_ref   oss://&lt;bucket&gt;/&lt;tenant&gt;/&lt;doc_type&gt;/&lt;version&gt;/&lt;hash&gt;   （V5 §2.27 逐字）
 *  file_hash  SHA-256 hex（64 字符）—— 完整性校验 + 防替换
 * </pre>
 * 故「接哪家对象存储」是<b>部署期决策</b>，不是业务决策。把该决策隔离在本接口的
 * 实现之后，业务链路（校验 → 落库 / 落库 → 取字节）在换厂商时一行不改。
 *
 * <h2>🛑 三条硬约束（由契约与 PRD §2.27 逐字而来）</h2>
 * <ol>
 *   <li><b>不做任何解析执行</b>：实现只允许做「写字节 / 读字节 / 删字节」。
 *       DOCX 不得解压、不得解析宏、不得作为可执行内容被服务端加载。
 *       这条不是实现建议，是契约 I3 的原话（"上传文件不得作为可执行内容被服务端解析执行"）。</li>
 *   <li><b>fail-closed</b>：读不到就抛 {@link BlobStoreException}，绝不返回 null / 空数组
 *       让调用方误以为"文件是空的"。空字节数组是一个合法的存储状态（空文件），
 *       不能用它表达"读取失败"——两者混淆会让 I7 下载把一次存储故障变成一份空白原件。</li>
 *   <li><b>路径由调用方按 V5 规则拼好</b>：本接口不做路径推导，避免"两处各拼一次"漂移。</li>
 * </ol>
 *
 * <h2>实现清单</h2>
 * <ul>
 *   <li>{@link InMemoryBlobStore} —— 骨架 / 单测 / 单实例演示（默认）。</li>
 *   <li>（待部署期接入）S3 兼容实现 / 阿里云 OSS / 腾讯云 COS —— 三者都只需实现本接口。</li>
 * </ul>
 */
public interface BlobStore {

    /**
     * 写字节（幂等：同 ref 重复写同一份内容必须成功且不产生第二份）。
     *
     * @param ref    对象引用（形如 {@code oss://bucket/tenant/type/version/hash}）
     * @param bytes  字节内容
     * @param sha256Hex 内容的 SHA-256 hex（调用方已算好；实现可据此校验后落盘）
     * @throws BlobStoreException 写入失败（不可静默成功）
     */
    void put(String ref, byte[] bytes, String sha256Hex);

    /** 读字节；不存在或读取失败 → {@link BlobStoreException}（fail-closed）。 */
    InputStream open(String ref);

    /** 是否存在（用于幂等判定，不抛异常）。 */
    boolean exists(String ref);

    /**
     * 取字节长度。
     *
     * <p>🛑 刻意不提供 {@code byte[] readAll()}：契约 I7 是<b>字节流下载</b>，
     * 一次性 readAll 会把 10 MiB 上限的内容整体驻留堆内，且无法流式响应。
     */
    Optional<Long> sizeOf(String ref);
}