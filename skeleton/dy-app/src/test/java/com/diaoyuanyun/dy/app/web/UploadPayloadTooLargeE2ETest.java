package com.diaoyuanyun.dy.app.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 🔴 第 75 条 · <b>上传体超过容器 multipart 上限必须答 413，不得落到 {@code handleOther} 变成 500·9001</b>。
 *
 * <h2>与第 74 条的关系</h2>
 * 第 74 条修的是"缺必填 part「file」"（参数绑定阶段，业务之前）→ 400·1001。
 * 本类修的是<b>更外面一层</b>：part「file」<b>存在但太大</b>（超过 {@code spring.servlet.multipart.max-file-size=12MB}），
 * 在<b>容器 multipart 解析阶段</b>就抛 {@code MaxUploadSizeExceededException}。两者是"请求不合法却答 500"同族的
 * 两个不同成员：10~12 MiB 由业务层以 400·1001 拦（见 {@code DocFileE2ETest#upload_rejects_oversize}），
 * >12 MiB 由本方法以 413 拦。缺了本方法，>12 MiB 一路 500。
 *
 * <h2>🛑 为什么不用 MockMvc（与第 73/74 条同一条理由）</h2>
 * 本缺陷只发生在<b>真实 DispatcherServlet 的 multipart 解析链</b>里——单测直调控制器、或 MockMvc 的部分路径，
 * 都不会触发 {@code MaxUploadSizeExceededException} 的真实抛出点（容器在解析期就拒了，控制器根本没被调用）。
 * 故用 {@code RANDOM_PORT} + 真 token + 真 13 MiB 体。
 *
 * <h2>为什么 13 MiB（而不是 10 MiB + 1）</h2>
 * 10 MiB + 1 落在 12MB 容器上限<b>之内</b>，会先到达业务层、被 {@code file_size ≤ 10 MiB} 校验以 400·1001 拒绝
 * （那是 {@code DocFileE2ETest#upload_rejects_oversize} 已经钉住的形态，与本方法要抓的 413 不是同一关）。
 * 本方法要抓的是<b>容器关</b>，必须把文件发到 12MB <b>之上</b>才能逼出 {@code MaxUploadSizeExceededException}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("第 75 条 · 上传体超容器上限必须答 413（非 500·9001）")
class UploadPayloadTooLargeE2ETest {

    /** 与 application.yml 的 dev 占位密钥一致（同 DomainAEndpointsE2ETest / DocFileE2ETest / RequestValidationEnvelopeE2ETest）。 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";
    private static final String TENANT = "e2e74000-0000-0000-0000-000000000074";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("🔴 I3 上传 13 MiB（超过容器 12MB 上限）→ 当前是 500·9001（缺 MaxUploadSizeExceededException 分支）；修复后应为 413")
    void i3_oversize_payload_returns_413_not_500() {
        // 13 MiB：明确超过 spring.servlet.multipart.max-file-size=12MB，逼出容器级 MaxUploadSizeExceededException。
        byte[] tooBig = new byte[13 * 1024 * 1024];
        tooBig[0] = '%';   // 非 PE/ELF，确保失败只来自大小而非内容类型

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token("hq")); // hq 持有 doc:write，能过鉴权过滤器（否则会先被 401/403 挡住，测不到 413）

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("doc_type", "知情同意书");
        form.add("file", new HttpEntity<>(new ByteArrayResource(tooBig) {
            @Override
            public String getFilename() {
                return "big.bin";
            }
        }, fileHeaders));
        ResponseEntity<String> resp = rest.exchange(
                "http://127.0.0.1:" + port + "/api/v1/doc-templates/uploads",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode(),
                "客户端上传体过大必须是 413 Payload Too Large，而不是 500·9001（实测 HTTP 状态 = "
                        + resp.getStatusCode() + "，响应体 = " + resp.getBody() + "）");
    }

    // ───────────────────────── 工具（与 RequestValidationEnvelopeE2ETest 同款真签名 JWT） ─────────────────────────

    private static String token(String role) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"e2e74000-0000-0000-0000-000000000075\""
                + ",\"role\":\"" + role + "\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}";
        return sign(header, payload);
    }

    private static String sign(String header, String payload) {
        String input = b64(header) + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
