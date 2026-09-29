package com.diaoyuanyun.dy.app.doctpl;

import com.diaoyuanyun.dy.app.doctpl.storage.InMemoryBlobStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-4 证人套件 · 契约域 I 的<b>文件通道</b>（I3 上传 / I7 下载）真请求回归。
 *
 * <h2>判据设计（每条对应一处"想当然会漏"的地方）</h2>
 * <pre>
 *  I3 上传 pdf                → 200，source_type=upload，is_active=false，file_hash 64 hex
 *  I3 同文件重复上传          → 409·4002（幂等键 (tenant, doc_type, hash) 命中）
 *  I3 mime 非三值             → 400·1001
 *  I3 超 10 MiB               → 400·1001（🛑 若容器上限配小了，这条会变成 5xx —— 正是本测试要钉的）
 *  I3 可执行载荷（MZ 头）     → 400·1001
 *  I7 超管下载                → 200 且字节逐一致 + X-File-Sha256 == 登记 file_hash
 *  I7 门店负责人（非超管）    → 403·2001（🛑 管理员有 doc:write 能过第一道，第二道必须拦住）
 *  I7 editor 形态             → 422·5001（无原件，明确报错，不返回空流）
 *  I7 原件被替换              → 500·9001（完整性校验失败，拒绝对外下发）
 * </pre>
 *
 * <h2>🛑 为什么 I7 的"防替换"能在真请求里测出来</h2>
 * 用 {@link InMemoryBlobStore} 的<b>合法 API</b> 制造"登记与实际不符"：
 * 先正常上传（ref 上内容 = A，库里登记 hash(A)），再用同一 ref 放入内容 B 并传 hash(B)
 * —— 写入自校验会通过（B 与 hash(B) 一致），但库里登记的仍是 hash(A)
 * ⇒ 下载期校验必然失败。这比反射改内部 map 干净，也不给生产代码开测试后门。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("B-4 · 契约域 I 文件通道（I3 上传 / I7 下载）真请求回归")
class DocFileE2ETest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "diaoyuanyun";

    private static final String TENANT = "e2e00000-0000-0000-0000-00000000d0f0";
    private static final String S_STAFF = "e2e00000-0000-0000-0000-0000000000f0";

    private static final String TYPE = "其他";                 // 六值之一，避开其他用例
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_MD = "text/markdown";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean seeded;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DataSource springDataSource;

    /** 🛑 用真实装配的存储实现制造"被替换"的场景（不反射、不开后门）。 */
    @Autowired
    InMemoryBlobStore blobStore;

    @BeforeEach
    void seedIfNeeded() {
        if (dataSource == null) {
            dataSource = springDataSource;
            jdbc = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
        if (!seeded) {
            seedOnce();
            seeded = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (jdbc == null || dataSource == null) {
            return;
        }
        inTenant(() -> {
            jdbc.update("DELETE FROM doc_template WHERE tenant_id = ?::uuid", TENANT);
            jdbc.update("DELETE FROM staff WHERE tenant_id = ?::uuid"
                    + " AND staff_id::text LIKE 'e2e00000-%'", TENANT);
            return null;
        });
        jdbc.update("DELETE FROM tenant WHERE id = ?::uuid", TENANT);
    }

    private static void seedOnce() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?::uuid, 'E2E租户-B4-I域文件)') "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        inTenant(() -> {
            jdbc.update("INSERT INTO staff (staff_id, tenant_id, role) "
                    + "VALUES (?::uuid, ?::uuid, '经络师') ON CONFLICT DO NOTHING", S_STAFF, TENANT);
            return null;
        });
    }

    private static <T> T inTenant(Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + TENANT + "'");
            return body.get();
        });
    }

    // ==================================================================
    // I3 上传
    // ==================================================================

    @Nested
    @DisplayName("I3 上传")
    class Upload {

        @Test
        @DisplayName("上传 pdf → 200，upload 形态、不自动活跃、file_hash 为 64 位 hex")
        void upload_pdf() {
            ResponseEntity<String> resp = upload("上传件一", MIME_PDF, pdfBytes("内容A"), token("hq"));
            assertEquals(200, resp.getStatusCodeValue(), "上传应 200: " + resp.getBody());
            JsonNode data = parse(resp.getBody()).at("/data");
            assertEquals("upload", data.at("/source_type").asText());
            assertTrue(!data.at("/is_active").asBoolean(),
                    "🛑 上传不得自动活跃（否则会撞 uq_doc_template_active 而落库失败）");
            assertEquals(64, data.at("/file_hash").asText().length(), "file_hash 应为 SHA-256 hex");
            assertTrue(data.at("/file_ref").asText().startsWith("oss://"),
                    "file_ref 应为 oss:// 引用: " + data.at("/file_ref").asText());
        }

        @Test
        @DisplayName("同文件重复上传 → 409·4002（幂等键命中，返回已有版本号）")
        void upload_same_file_is_idempotent() {
            byte[] same = pdfBytes("幂等内容");
            ResponseEntity<String> first = upload("幂等一", MIME_PDF, same, token("hq"));
            assertEquals(200, first.getStatusCodeValue(), "首传应 200: " + first.getBody());

            ResponseEntity<String> second = upload("幂等二", MIME_PDF, same, token("hq"));
            assertEquals(409, second.getStatusCodeValue(),
                    "同 (tenant, doc_type, hash) 重复上传应 409·4002: " + second.getBody());
            assertEquals(4002, parse(second.getBody()).at("/code").asInt(),
                    "错误码应为 4002 幂等重放: " + second.getBody());
        }

        @Test
        @DisplayName("mime_type 非三值 → 400·1001")
        void upload_rejects_bad_mime() {
            ResponseEntity<String> resp = upload("坏MIME", "application/zip",
                    pdfBytes("zip"), token("hq"));
            assertEquals(400, resp.getStatusCodeValue(), "非三值 mime 应 400: " + resp.getBody());
            assertEquals(1001, parse(resp.getBody()).at("/code").asInt());
        }

        @Test
        @DisplayName("🛑 超 10 MiB → 400·1001（容器上限必须大于业务上限，否则这里会变成 5xx）")
        void upload_rejects_oversize() {
            byte[] tooBig = new byte[10 * 1024 * 1024 + 1];   // 10 MiB + 1
            tooBig[0] = '%';                                   // 非 PE/ELF，确保失败只来自大小
            ResponseEntity<String> resp = upload("超限", MIME_MD, tooBig, token("hq"));
            assertEquals(400, resp.getStatusCodeValue(),
                    "🛑 超 10 MiB 应被【业务层】以 400·1001 拒绝。"
                            + "若此处是 500/413，说明 spring.servlet.multipart.max-file-size "
                            + "小于业务上限（10 MiB）——契约的 file_size ≤ 10 MiB 事实上无法达成: "
                            + resp.getBody());
            assertEquals(1001, parse(resp.getBody()).at("/code").asInt());
        }

        @Test
        @DisplayName("可执行载荷（PE/MZ 头）→ 400·1001（契约：不得作为可执行内容）")
        void upload_rejects_executable() {
            byte[] pe = new byte[128];
            pe[0] = 0x4D;   // 'M'
            pe[1] = 0x5A;   // 'Z'
            ResponseEntity<String> resp = upload("可执行", MIME_PDF, pe, token("hq"));
            assertEquals(400, resp.getStatusCodeValue(),
                    "PE/MZ 载荷应被拒（契约 I3「不得作为可执行内容被解析执行」）: " + resp.getBody());
        }
    }

    // ==================================================================
    // I7 下载
    // ==================================================================

    @Nested
    @DisplayName("I7 下载")
    class Download {

        @Test
        @DisplayName("超管下载 → 200，字节逐一致，且 X-File-Sha256 与登记一致")
        void download_by_super_admin() {
            byte[] content = pdfBytes("下载原件内容-逐字节比对");
            JsonNode up = parse(upload("可下载", MIME_PDF, content, token("hq")).getBody()).at("/data");
            String id = up.at("/template_id").asText();
            String hash = up.at("/file_hash").asText();
            int version = up.at("/version").asInt();

            ResponseEntity<byte[]> resp = rest.exchange(
                    url("/api/v1/doc-templates/" + id + "/download?version=" + up.at("/version").asInt()),
                    HttpMethod.GET, new HttpEntity<>(bearer(token("hq"))), byte[].class);

            assertEquals(200, resp.getStatusCodeValue(), "超管下载应 200");
            assertNotNull(resp.getBody(), "应返回字节流");
            assertTrue(java.util.Arrays.equals(content, resp.getBody()),
                    "🛑 下载字节必须与上传逐字节一致（套 JSON 信封会破坏这一点）");
            assertEquals(hash, resp.getHeaders().getFirst("X-File-Sha256"),
                    "响应头的 SHA-256 应与登记 file_hash 一致");
        }

        @Test
        @DisplayName("🛑 门店负责人（有 doc:write 但非超管）→ 403·2001（第二道必须拦住）")
        void download_denied_for_non_super_admin() {
            // 🛑 必须用上传返回的【实际 version】，不能硬编码 1：
            //    同租户同 doc_type 下版本号是累积的（多个用例共享本类的租户），
            //    硬编码 1 会让请求先撞 404（版本不存在）——那样测到的是"找不到"，
            //    而不是"非超管被第二道拦住"，证明力归零。（实测踩过：见 replaced_blob 用例）
            JsonNode up = parse(upload("非超管", MIME_PDF, pdfBytes("非超管内容"), token("hq"))
                    .getBody()).at("/data");
            String id = up.at("/template_id").asText();
            int version = up.at("/version").asInt();
            ResponseEntity<String> resp = rest.exchange(
                    url("/api/v1/doc-templates/" + id + "/download?version=" + version),
                    HttpMethod.GET, new HttpEntity<>(bearer(token("manager"))), String.class);
            assertEquals(403, resp.getStatusCodeValue(),
                    "manager 持有 doc:write（过第一道）但非超管，必须被第二道拒绝: " + resp.getBody());
            assertEquals(2001, parse(resp.getBody()).at("/code").asInt(),
                    "应为 2001 可见性不足: " + resp.getBody());
        }

        @Test
        @DisplayName("editor 形态无原件 → 422·5001（不返回空流）")
        void download_rejects_editor_form() {
            ResponseEntity<String> created = rest.exchange(url("/api/v1/doc-templates"),
                    HttpMethod.POST, jsonEntity("{\"doc_type\":\"到店须知\",\"title\":\"编辑器形态\","
                            + "\"content\":\"正文\"}", token("hq")), String.class);
            assertEquals(200, created.getStatusCodeValue(), "建 editor 模板应 200: " + created.getBody());
            String id = parse(created.getBody()).at("/data/template_id").asText();

            ResponseEntity<String> resp = rest.exchange(
                    url("/api/v1/doc-templates/" + id + "/download?version=1"),
                    HttpMethod.GET, new HttpEntity<>(bearer(token("hq"))), String.class);
            assertEquals(422, resp.getStatusCodeValue(),
                    "editor 形态无原件应 422·5001（明确报错，不返回空流）: " + resp.getBody());
            assertEquals(5001, parse(resp.getBody()).at("/code").asInt());
        }

        @Test
        @DisplayName("🛑 原件被替换 → 500·9001（完整性校验失败，拒绝对外下发）")
        void download_detects_replaced_blob() {
            byte[] original = pdfBytes("原件-将被替换");
            JsonNode up = parse(upload("替换测试", MIME_PDF, original, token("hq")).getBody()).at("/data");
            String ref = up.at("/file_ref").asText();
            String id = up.at("/template_id").asText();
            int version = up.at("/version").asInt();   // 🛑 实测踩过：硬编码 1 会走成 404

            // 用合法 API 把同一 ref 的内容换成另一份（传入的是新内容的真实 hash ⇒ 写入自校验通过）
            byte[] tampered = pdfBytes("被替换后的内容");
            blobStore.put(ref, tampered, sha256Hex(tampered));

            // 🛑 失败形态不唯一：业务会抛 BizException(9001)，被全局异常处理器映射成 500 的
            //    【JSON 信封】；而 TestRestTemplate 对 500 会优先尝试按声明的 String.class 解析
            //    body —— 若解析路径上先触发了别的异常（实测遇到 HashSet 乱序导致首个异常
            //    可能是 NoResourceFoundException），就直接抛错、拿不到响应体。
            //    故本用例对"抛异常"与"拿到 500 信封"两种形态都接受，但都要求 9001。
            int status;
            int errCode;
            try {
                ResponseEntity<String> resp = rest.exchange(
                        url("/api/v1/doc-templates/" + id + "/download?version=" + version),
                        HttpMethod.GET, new HttpEntity<>(bearer(token("hq"))), String.class);
                status = resp.getStatusCodeValue();
                errCode = parse(resp.getBody()).at("/code").asInt();
            } catch (org.springframework.web.client.RestClientException e) {
                String msg = String.valueOf(e.getMessage());
                assertTrue(msg.contains("9001"),
                        "🛑 原件被替换必须被拒（错误码 9001 完整性校验失败）。实际异常: " + msg);
                return;
            }
            assertEquals(500, status,
                    "原件与登记 file_hash 不符时必须拒发（否则被替换的原件会被当原件下发）");
            assertEquals(9001, errCode, "应为 9001（完整性校验失败）");
        }

        @Test
        @DisplayName("🛑 超管别名矩阵：hq / SUPER_ADMIN 可下载；TENANT_ADMIN 被第一道拦（既有分叉，显式登记）")
        void download_super_admin_alias_matrix() {
            JsonNode up = parse(upload("别名", MIME_PDF, pdfBytes("别名内容"), token("hq"))
                    .getBody()).at("/data");
            String id = up.at("/template_id").asText();
            int v = up.at("/version").asInt();   // 取实际 version，勿硬编码

            // ① 已登记权限码的超管写法 → 可下载
            for (String alias : new String[]{"hq", "SUPER_ADMIN“}) {
                ResponseEntity<byte[]> resp = rest.exchange(
                        url(”/api/v1/doc-templates/" + id + "/download?version=" + v),
                        HttpMethod.GET, new HttpEntity<>(bearer(token(alias))), byte[].class);
                assertEquals(200, resp.getStatusCodeValue(),
                        "已登记的超管写法应可下载: " + alias);
            }

            // ② 🛑 TENANT_ADMIN 是 OrgLevel.HEADQUARTERS 的登记别名，却【未登记】
            //    PermissionRegistry 的权限码 ⇒ 被第一道 @RequirePermission("doc:write") 拦下（403·2001）。
            //    这是 T-11「骨架大写码与契约小写码并存」这处分叉的延伸事实。
            //    本测试【钉住现状而非修正它】：不擅自往权限矩阵补码（那是权限 owner 的裁定），
            //    但必须让"它目前不可达"成为一条被断言的事实，而不是一个无人知道的死角。
            ResponseEntity<String> tenantAdmin = rest.exchange(
                    url("/api/v1/doc-templates/" + id + "/download?version=" + v),
                    HttpMethod.GET, new HttpEntity<>(bearer(token("TENANT_ADMIN"))), String.class);
            assertEquals(403, tenantAdmin.getStatusCodeValue(),
                    "TENANT_ADMIN 未登记 doc:write ⇒ 被第一道拦（若此处变成 200，说明权限矩阵已变更，"
                            + "应同步更新本断言与 T-11 的登记）: " + tenantAdmin.getBody());
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        return h;
    }

    private static HttpEntity<String> jsonEntity(String body, String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt);
        return new HttpEntity<>(body, h);
    }

    /** 组装 multipart 上传请求。 */
    private ResponseEntity<String> upload(String fileName, String mimeType, byte[] bytes, String jwt) {
        // 🛑 不要手动设 multipart 的 Content-Type（实测教训）：
        //    手动设成裸 `multipart/form-data` 后，FormHttpMessageConverter 认为 Content-Type
        //    已确定、不再补 boundary ⇒ 请求体有 boundary 而 header 没有 ⇒ 服务端解析不到 part
        //    ⇒ 全部上传报 MissingServletRequestPartException（表现为 500）。
        //    正确做法：只设鉴权头，让转换器自己生成带 boundary 的 Content-Type。
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource res = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName + guessExt(mimeType);
            }
        };
        // part 仍需自带 Content-Type：本测试刻意用非标准扩展名制造超限，
        // 若靠容器做 mime 猜测会推成 application/octet-stream ⇒ 被业务层以「非三值」拒成 400，
        // 那样超限用例就退化成 mime 用例、证明力归零。
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(mimeType));
        form.add("file", new HttpEntity<>(res, partHeaders));
        form.add("doc_type", TYPE);
        form.add("title", fileName);
        return rest.exchange(url("/api/v1/doc-templates/uploads"), HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

    private static String guessExt(String mimeType) {
        if (MIME_PDF.equals(mimeType)) {
            return ".pdf";
        }
        if (MIME_MD.equals(mimeType)) {
            return ".md";
        }
        return ".bin";
    }

    /** 以 PDF 魔数开头的合成内容（同时确保不触发 PE/ELF 拒收）。 */
    private static byte[] pdfBytes(String marker) {
        return ("%PDF-1.7\n" + marker + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(String role) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\""
                + ",\"tenant_id\":\"" + TENANT + "\""
                + ",\"staff_id\":\"" + S_STAFF + "\""
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

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }
}