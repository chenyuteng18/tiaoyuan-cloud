package com.diaoyuanyun.dy.web.idempotent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 守护断言: <b>请求体必须可被"拦截器先读、控制器后读"重复读取</b>。
 *
 * <h2>守的是什么缺陷（已确定性复现, 不是假想）</h2>
 * 修复前, {@code IdempotencyBodyCacheFilter} 用 Spring 自带的
 * {@code ContentCachingRequestWrapper}。它在**带 {@code Idempotency-Key} 的 POST** 上
 * 导致 {@code 500 系统异常: HttpMessageNotReadableException}:
 * 幂等拦截器在 {@code preHandle} 读尽请求体算 SHA-256 之后, 控制器 {@code @RequestBody}
 * 只能读到 EOF（空体）。
 *
 * <p>根因是那个包装器**单次消费**的语义（不是配置问题）: 其内部
 * {@code ContentCachingInputStream.read()} 把字节透传给底层
 * {@code ServletInputStream} 并在旁路缓存, 而 {@code getInputStream()} 一旦创建实例就复用,
 * <b>没有 reset / 回放</b>。它的设计用途是"处理器读完后, 事后从
 * {@code getContentAsByteArray()} 取字节打日志", 不支持"先读、后读"。
 *
 * <h2>为什么本用例必须真的"读两次"（而不是只断言别的）</h2>
 * 只断言 {@code getCachedBody()} 非空、或只断言某次读取成功, 都<b>无法</b>抓住该缺陷 ——
 * 缺陷恰恰只在<b>第二次</b>读取时才显现。故本用例模拟真实顺序:
 * 先按拦截器的方式读一次（算哈希）, 再按控制器的方式读一次（要求拿到完整体）。
 *
 * <h2>反向验证（证明本门禁有牙齿）</h2>
 * 把 {@code IdempotencyBodyCacheFilter} 改回 {@code ContentCachingRequestWrapper}
 * （或让 {@code CachedBodyRequestWrapper.getInputStream()} 复用同一个流实例）,
 * 本用例必须变红: 第二次读取得到的字节数与内容不再等于原始请求体。
 */
class CachedBodyRequestWrapperTest {

    private static final String BODY = "{\"x\":1,\"note\":\"中文与符号 !@#\"}";

    private static MockHttpServletRequest requestWithBody(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContentType("application/json");
        req.setCharacterEncoding("UTF-8");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /**
     * 主断言: 连续两次读流, 两次都必须拿到完整请求体。
     *
     * <p>对应真实链路: 第 1 次 = 幂等拦截器 {@code hashBody()};
     * 第 2 次 = 控制器 {@code @RequestBody} 反序列化。
     */
    @Test
    void body_is_readable_twice_when_interceptor_reads_before_controller() throws Exception {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(requestWithBody(BODY));

        byte[] first = wrapper.getInputStream().readAllBytes();
        byte[] second = wrapper.getInputStream().readAllBytes();

        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), first,
                "第一次读（拦截器算哈希）必须拿到完整请求体");
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), second,
                "第二次读（控制器 @RequestBody）必须拿到完整请求体 —— 这正是修复前失败之处。"
                        + " 若本断言变红, 说明 getInputStream() 又变回了单次消费语义"
                        + "（例如误用 Spring 的 ContentCachingRequestWrapper）。");
    }

    /** 每次 {@code getInputStream()} 必须是独立实例（独立游标）, 不能共享同一游标。 */
    @Test
    void each_getInputStream_returns_an_independent_stream() throws Exception {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(requestWithBody(BODY));
        assertNotSame(wrapper.getInputStream(), wrapper.getInputStream(),
                "两次 getInputStream() 不得返回同一实例, 否则游标共享, 第二次读必为空");
    }

    /** 中文/多字节内容按字符集正确还原（UTF-8 不得乱码）。 */
    @Test
    void multi_byte_body_survives_cache_and_reader() throws Exception {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(requestWithBody(BODY));
        wrapper.getInputStream().readAllBytes(); // 先读一次, 模拟拦截器
        assertEquals(BODY, new String(wrapper.getCachedBody(), StandardCharsets.UTF_8),
                "缓存字节按 UTF-8 还原后必须与原始请求体完全一致");
    }

    /** 空体边界: 不得抛异常, 且读取得到零长度。 */
    @Test
    void empty_body_is_not_an_error() throws Exception {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(requestWithBody(""));
        assertEquals(0, wrapper.getInputStream().readAllBytes().length);
        assertEquals(0, wrapper.getInputStream().readAllBytes().length);
    }
}