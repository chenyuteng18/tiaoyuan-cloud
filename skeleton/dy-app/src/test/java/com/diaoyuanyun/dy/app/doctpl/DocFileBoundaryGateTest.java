package com.diaoyuanyun.dy.app.doctpl;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.app.doctpl.storage.DocFileRef;
import com.diaoyuanyun.dy.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-4 · 文件通道的<b>边界门禁</b>（构建期，不依赖数据库）。
 *
 * <h2>守的是什么</h2>
 * B-4 的第一版勘察结论是「依赖对象存储接入」，但实测发现真正的缺口更靠前：
 * 契约 I3 冻结的 {@code file_size ≤ 10 MiB} 在<b>容器层就已经不可达</b> ——
 * Spring Boot 默认 multipart 上限 1MB，而当时 {@code application.yml} 里
 * <b>完全没有 multipart 配置</b>。于是：
 * <pre>
 *   上传 5 MiB 的 PDF → 容器在进入业务层之前拒掉（413/500）
 *   ⇒ 契约写着的上限是空话，且错误码语义与契约不符
 * </pre>
 * 这类缺口不会让任何既有测试变红（既有测试没有上传用例），只能靠<b>一条显式的
 * 交叉断言</b>守住。本类就是那条断言。
 *
 * <h2>为什么用"读配置文本"而不是"读注入后的 Bean"</h2>
 * 注入后的 {@code MultipartConfigElement} 只反映<b>本次测试进程的</b>生效值；
 * 而这里要守的是"配置源文件本身的意图"（含 prod profile 的默认来源）。
 * 读配置文本能同时暴露"配错了"和"没配"两种情况，且不依赖 web 环境启动。
 *
 * <h2>🛑 若本类变红，正确的修法是改配置，不是改断言</h2>
 * 断言给出的关系（容器上限 &gt; 业务上限）是契约可达成性的<b>必要条件</b>，
 * 不是"当前实现的巧合"。放宽断言会让契约重新变成空话。
 */
@DisplayName("B-4 · 文件通道边界门禁（容器上限 / 引用不变式 / fail-closed）")
class DocFileBoundaryGateTest {

    /** 业务上限（权威口径）：{@link DocTemplateRow#MAX_FILE_SIZE}。 */
    private static final long BUSINESS_MAX = DocTemplateRow.MAX_FILE_SIZE;

    // ==================================================================
    // ① 容器上限 vs 业务上限（本类存在的首要理由）
    // ==================================================================

    @Test
    @DisplayName("🛑 multipart 容器上限必须 > 业务上限（10 MiB），否则契约的 file_size 上限不可达")
    void multipart_capacity_must_exceed_business_limit() {
        Map<String, Object> spring = springSection();
        assertTrue(spring.containsKey("servlet"),
                "🛑 application.yml 的 spring 段缺 servlet.multipart 配置："
                        + "Spring Boot 默认上限 1MB < 契约 I3 的 10 MiB ⇒ 超 1MB 的上传在进入业务层"
                        + "之前就被容器拒掉。契约写着的 file_size ≤ 10 MiB 事实上不可达成。");

        @SuppressWarnings("unchecked")
        Map<String, Object> servlet = (Map<String, Object>) spring.get("servlet");
        @SuppressWarnings("unchecked")
        Map<String, Object> multipart = (Map<String, Object>) servlet.get("multipart");
        assertTrue(multipart != null, "spring.servlet.multipart 缺失");

        long maxFile = DataSize.parse(String.valueOf(multipart.get("max-file-size"))).toBytes();
        long maxRequest = DataSize.parse(String.valueOf(multipart.get("max-request-size"))).toBytes();

        assertTrue(maxFile > BUSINESS_MAX,
                "🛑 容器单文件上限（" + maxFile + " 字节）必须 > 业务上限（" + BUSINESS_MAX
                        + " 字节 = 10 MiB）。相等会让「恰好 10 MiB」的上传因表单开销而被拒；"
                        + "小于则契约的 10 MiB 完全不可达。");
        assertTrue(maxRequest >= maxFile,
                "🛑 max-request-size（" + maxRequest + "）必须 ≥ max-file-size（" + maxFile
                        + "）：前者是整包上限，小于后者时单文件上限永远达不到。");
    }

    // ==================================================================
    // ② file_ref 的构造 / 解析不变式（三段拼装与解析必须同源）
    // ==================================================================

    @Test
    @DisplayName("全部六个 doc_type 的 build→parse 往返一致（中文 doc_type 必须可编码）")
    void ref_round_trips_for_all_doc_types() {
        String tenant = "e2e00000-0000-0000-0000-00000000d0f0";
        String hash = "a".repeat(64);
        for (String docType : DocTemplateRow.DOC_TYPES) {
            String ref = DocFileRef.build(tenant, docType, 3, hash);
            DocFileRef.Parsed p = DocFileRef.parse(ref);
            assertEquals(tenant, p.tenantId(), "租户段往返不一致: " + ref);
            assertEquals(docType, p.docType(),
                    "🛑 doc_type 往返不一致（中文编码/解码不对称）: " + ref
                            + " → " + p.docType());
            assertEquals(3, p.version(), "版本段往返不一致: " + ref);
            assertEquals(hash, p.fileHash(), "哈希段往返不一致: " + ref);
        }
    }

    @Test
    @DisplayName("🛑 生成的引用不含非 ASCII（跨厂商/跨代理解析稳定）")
    void ref_is_always_ascii() {
        String ref = DocFileRef.build("e2e00000-0000-0000-0000-00000000d0f0",
                "隐私与授权须知", 1, "b".repeat(64));
        for (int i = 0; i < ref.length(); i++) {
            assertTrue(ref.charAt(i) < 0x80,
                    "🛑 引用里出现非 ASCII 字符（位置 " + i + "）: " + ref
                            + " —— 中文 doc_type 必须被 URL 编码，否则不同 SDK/代理的键处理口径不一。");
        }
        assertTrue(ref.contains("%"), "中文 doc_type 应体现为 %XX 转义: " + ref);
    }

    @Test
    @DisplayName("未编码的中文引用仍可解析（对手写/历史引用的向后兼容）")
    void ref_accepts_legacy_unencoded_doc_type() {
        String legacy = "oss://bucket/e2e00000-0000-0000-0000-00000000d0f0/知情同意书/1/"
                + "c".repeat(64);
        DocFileRef.Parsed p = DocFileRef.parse(legacy);
        assertEquals("知情同意书", p.docType(),
                "未编码的中文段应原样解析（URLDecoder 对非转义字符是恒等的），否则历史引用会全部失效");
    }

    // ==================================================================
    // ③ fail-closed：非法引用/非法参数一律抛出，绝不"尽力而为"
    // ==================================================================

    @Test
    @DisplayName("非法引用一律抛错（fail-closed，不返回半成品）")
    void ref_rejects_malformed_input() {
        String[] bad = {
                null,
                "",
                "   ",
                "not-a-ref",
                "oss://bucket/tenant/doc/1/short_hash",                 // 哈希不足 64 位
                "oss://bucket/tenant/doc/1/" + "d".repeat(63),          // 63 位
                "oss://bucket/tenant/doc/0/" + "d".repeat(64),          // version=0
                "oss://bucket/tenant/1/" + "d".repeat(64),              // 缺一段
                "oss://bucket/tenant/doc/1/" + "D".repeat(64),          // 大写 hex（正则只收小写）
        };
        for (String s : bad) {
            assertThrows(BizException.class, () -> DocFileRef.parse(s),
                    "非法引用必须抛错，实际接受了: " + s);
        }
    }

    @Test
    @DisplayName("构造参数非法一律抛错（version<1 / hash 非 64 位小写 hex / 空段）")
    void ref_rejects_illegal_build_args() {
        String tenant = "e2e00000-0000-0000-0000-00000000d0f0";
        String ok = "e".repeat(64);

        assertThrows(BizException.class, () -> DocFileRef.build(tenant, "知情同意书", 0, ok),
                "version=0 必须拒绝");
        assertThrows(BizException.class, () -> DocFileRef.build(tenant, "知情同意书", 1,
                "e".repeat(63)), "63 位哈希必须拒绝");
        assertThrows(BizException.class, () -> DocFileRef.build(tenant, "知情同意书", 1,
                "E".repeat(64)), "大写哈希必须拒绝（口径唯一，避免两处各写一种）");
        assertThrows(BizException.class, () -> DocFileRef.build("", "知情同意书", 1, ok),
                "空 tenant 必须拒绝");
        assertThrows(BizException.class, () -> DocFileRef.build(tenant, null, 1, ok),
                "空 doc_type 必须拒绝");
    }

    @Test
    @DisplayName("业务上限常量与契约一致（10 MiB）—— 改它必须是有意的")
    void business_limit_is_exactly_ten_mib() {
        assertEquals(10L * 1024 * 1024, BUSINESS_MAX,
                "契约 I3 与 V5 §2.27 冻结 file_size ≤ 10 MiB（10485760）。"
                        + "若此处变化，必须同步：① application.yml 的容器上限；② 本类的关系断言。");
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 读 classpath 的 application.yml 首文档，取 spring 段。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> springSection() {
        try (InputStream in = DocFileBoundaryGateTest.class.getResourceAsStream("/application.yml")) {
            assertTrue(in != null, "classpath 下找不到 application.yml");
            // 🛑 必须用 loadAll：application.yml 有多个 YAML 文档（用 `---` 分隔 dev / prod profile）。
            //    用 Yaml#load 会抛 `expected a single document in the stream`（实测踩过）。
            java.util.Iterator<Object> docs = new Yaml().loadAll(in).iterator();
            assertTrue(docs.hasNext(), "application.yml 没有任何 YAML 文档");
            Map<String, Object> root = (Map<String, Object>) docs.next();
            Object spring = root.get("spring");
            assertTrue(spring instanceof Map, "application.yml 首个文档缺少 spring 段");
            return (Map<String, Object>) spring;
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败: " + e.getMessage(), e);
        }
    }
}