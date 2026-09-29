package com.diaoyuanyun.dy.web.idempotent;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 可重读的请求包装器: 请求体在构造时【一次性读入内存】, 之后每次 {@code getInputStream()}
 * 都返回一份全新的、位置归零的副本。
 *
 * <h2>为什么不能用 Spring 自带的 {@code ContentCachingRequestWrapper}</h2>
 * 幂等拦截器必须在 {@code preHandle} 里读请求体算 SHA-256（用于"同键不同体 → 409"判定），
 * 而控制器随后还要用 {@code @RequestBody} 再读一次同一份体。初版直接用 Spring 的
 * {@code ContentCachingRequestWrapper}，结果**带 {@code Idempotency-Key} 的 POST 一律 500**
 * （{@code 系统异常: HttpMessageNotReadableException}）。
 *
 * <p>根因（已用字节码确认，不是推测）：Spring 的该包装器是<b>单次消费</b>语义 ——
 * 其内部 {@code ContentCachingInputStream.read()} 把字节<b>透传</b>给底层
 * {@code ServletInputStream} 并在旁路缓存，而 {@code getInputStream()} 返回的是<b>同一个</b>
 * 缓存实例（非 null 即复用），<b>没有任何 reset / 回放</b>。于是拦截器读尽后底层流已到
 * EOF，控制器再读只能得到 {@code -1}（空体） ⇒ {@code HttpMessageNotReadableException}。
 * 它的设计用途是"处理器读完后，事后从 {@code getContentAsByteArray()} 取日志"，
 * <b>不支持"读在前、业务读在后"</b>这种用法。
 *
 * <p>故本类改为：构造时把体整体读入 {@code byte[]}，{@code getInputStream()} 每次新建
 * {@link ByteArrayInputStream} —— 读多少次都是完整内容，幂等拦截器与控制器的读取互不干扰。
 *
 * <p>代价与边界：请求体驻留内存一次。这与幂等哈希必须读到完整体这一需求一致；
 * 体积上限应在上游（反向代理 / 容器）限制，而非在此静默截断（截断会让哈希失真，
 * 使"同键不同体"判定失效 —— 那是静默的鉴权/幂等漏洞）。
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    /** 请求体全量字节; 恒非 null（空体时为零长数组）。 */
    private final byte[] cachedBody;

    /**
     * @param request 原始请求; 其输入流在本构造器内被读尽
     * @throws IOException 读取原始请求体失败
     */
    public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    /** 供幂等拦截器直接取用（避免再复制一次流）。 */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ByteArrayServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(cachedBody), charset));
    }

    /** 以 {@link ByteArrayInputStream} 为源, 每次实例化都是独立游标。 */
    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream in;

        ByteArrayServletInputStream(byte[] body) {
            this.in = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // 体已在内存中, 无异步读取需求; 显式抛错优于静默空实现
            throw new UnsupportedOperationException("请求体已在内存, 不支持异步读监听");
        }
    }
}