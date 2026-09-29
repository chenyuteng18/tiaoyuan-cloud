package com.diaoyuanyun.dy.app.doctpl.storage;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BlobStore} 的进程内实现（骨架默认）—— B-4。
 *
 * <h2>定位与边界（🛑 与 B-2/B-3 同一处置纪律）</h2>
 * 本类是<b>默认装配</b>，让 I3/I7 在单实例骨架里可端到端跑通、可被门禁断言；
 * 它<b>不是</b>生产存储。生产换 S3/OSS/COS 时只替换 {@link BlobStore} 的实现，
 * {@code DocFileService} / 控制器 / 测试一行不改（这就是先抽象的理由）。
 *
 * <h2>🛑 为什么这里敢用进程内实现（而 B-2 的幂等不敢）</h2>
 * B-2 的幂等键承载「防重复扣款」，多实例下内存实现会<b>静默失效且无外部症状</b>；
 * 而对象存储缺失会<b>立刻表现为 I7 下载失败</b>（响亮失败），不会被误当成功。
 * 二者失效模式的不对称性，决定了处置可以不同。
 *
 * <h2>🛑 写入必须做哈希自校验</h2>
 * {@link #put} 会重算 SHA-256 并与调用方传入的比对；不一致直接拒收。
 * 这把"上传内容在传输/缓冲过程中被改动"变成一次可定位的失败，而不是等到 I7
 * 下载时才发现原件与登记的 file_hash 不符（那时已无法判断是哪一步坏的）。
 */
@Component
public class InMemoryBlobStore implements BlobStore {

    private final ConcurrentHashMap<String, byte[]> blobs = new ConcurrentHashMap<>();

    @Override
    public void put(String ref, byte[] bytes, String sha256Hex) {
        if (ref == null || ref.isBlank()) {
            throw new BlobStoreException("对象引用（ref）为空");
        }
        if (bytes == null) {
            throw new BlobStoreException("字节内容为 null（空内容请传 byte[0]）");
        }
        String actual = sha256(bytes);
        if (sha256Hex == null || !actual.equalsIgnoreCase(sha256Hex)) {
            throw new BlobStoreException("SHA-256 自校验失败：登记=" + sha256Hex + "，实际=" + actual
                    + "（拒收，避免「原件与登记哈希不符」在下载期才暴露）");
        }
        blobs.put(ref, bytes.clone());
    }

    @Override
    public InputStream open(String ref) {
        byte[] bytes = blobs.get(ref);
        if (bytes == null) {
            throw new BlobStoreException("对象不存在: " + ref
                    + "（fail-closed：不返回空流，避免把存储故障渲染成一份空白原件）");
        }
        return new ByteArrayInputStream(bytes.clone());
    }

    @Override
    public boolean exists(String ref) {
        return ref != null && blobs.containsKey(ref);
    }

    @Override
    public Optional<Long> sizeOf(String ref) {
        byte[] bytes = blobs.get(ref);
        return bytes == null ? Optional.empty() : Optional.of((long) bytes.length);
    }

    /** 供测试/运维观测：当前驻留对象数。 */
    public int storedCount() {
        return blobs.size();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BlobStoreException("SHA-256 计算失败", e);
        }
    }
}