package com.diaoyuanyun.dy.app.derived.web;

import com.diaoyuanyun.dy.common.result.Result;
import com.diaoyuanyun.dy.security.visibility.DerivedFields;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 派生字段出口兜底（S1-5 验收③）——<b>派生字段不出现在任何响应体</b>。
 *
 * <h2>它挡的是入站拦截器<b>结构上看不见</b>的那一类泄漏</h2>
 * 入站拦截器（{@code DerivedVisibilityInterceptor}）拒的是"<b>调用方点名索取</b>"。
 * 但真实泄漏形态多数不长这样：
 * <pre>
 *   ① 控制器把内部 DTO 整只返回（字段在 DTO 里，调用方什么都没点）；
 *   ② 某个 Map&lt;String,Object&gt; 手工装配的响应顺手带上了 as_value；
 *   ③ 列表接口把同一只对象放进 items[]，逐字段注解只管住了顶层；
 *   ④ 全局序列化配置被改（注解被绕过）。
 * </pre>
 * 这四种情形里<b>请求参数里一个派生字段名都没有</b> —— 入站扫描看不见它们。
 * 只有"在写响应之前看一遍真东西"能看见。这就是本类存在的唯一理由，
 * 也是 S1-5 把②③列为<b>两条独立验收</b>而非一条的原因。
 *
 * <h2>为什么是"整体移除"而不是"置 null / 置空串"</h2>
 * 契约 §3.2 逐字：「客户可调接口的响应体中，③④ 字段<b>整体不存在</b>
 * （不是 {@code null}、不是空串、不是 {@code ""}）」。
 * 置 null 会<b>泄露字段存在性</b>：调用方从 {@code "as_value": null} 就能推断
 * "这个接口有 AS 这个字段，只是对我不显示"—— 而"存在 AS 判定"本身就是内部信息
 * （原型 D1 X-1：「客户端 ③④ 是<b>不下发</b>，不是前端隐藏」）。
 * 与 {@code FieldMasker}（dy-security）同一条纪律，本类是它在响应链路上的执行点。
 *
 * <h2>🛑 为什么判据走 {@link DerivedFields} 而不是本类自己列字段</h2>
 * 若本类自带一张清单，就会出现"入站按 A 清单拒、出口按 B 清单剥"的形态：
 * 两张清单各自自洽，合起来却漏一个字段，而漏的那次不报任何错。
 * 统一问 {@link DerivedFields} 使"是不是派生字段"只有一个答案。
 *
 * <h2>🛑 为什么用 {@code isClientForbiddenField}（④ ∪ ③）而不是 {@code isDerivedField}（仅 ④）</h2>
 * 契约 §3.2 逐字要求的是「客户可调接口的响应体中，<b>③④ 字段</b>整体不存在」——
 * 出口侧的边界是<b>两个字段组</b>。初版只摘 ④，于是 {@code gap_reason}（③）
 * 在"客户可调"的响应体里照样出去：入站扫描挡得住"点名索取"，
 * 但挡不住"某个 DTO 顺手带上了 gap_reason"—— 而后者正是本类存在的唯一理由。
 * 这个漏洞是被 {@code DerivedVisibilityE2ETest} 的反向注入探针抓出来的。
 *
 * <h2>为什么它只对客户类身份生效（不全局剥）</h2>
 * 契约 §3.1 矩阵里 ④ 对 {@code therapist/meridian/admin} 是 ✓ ——
 * E4（{@code /customers/{id}/band/derived}）就是<b>给 staff 用</b>的接口，
 * 它必须能返回这些字段。全局剥会把它一起废掉，且症状是"接口 200 但字段没了"，
 * 很容易被误诊为契约不一致。故本类的判据是<b>角色</b>，不是字段。
 *
 * <h2>顺序：排在最后（{@code LOWEST_PRECEDENCE}）</h2>
 * 必须晚于所有会改写响应体的 advice —— 否则"别人后写的字段"会绕过本类。
 * 排在最后意味着它是<b>最后一道</b>：任何更早的改写都要经过它。
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class DerivedResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(DerivedResponseBodyAdvice.class);

    private final ObjectMapper mapper;

    public DerivedResponseBodyAdvice(ObjectProvider<ObjectMapper> mapperProvider) {
        // 用容器里已配置的 ObjectMapper（而非 new 一个）：尊重应用的序列化配置，
        // 使 valueToTree 出来的树与"真序列化"结果一致 —— 否则本类过滤的是
        // 一棵与真实响应不同的树（命名策略不同就会对不上键名），过滤结果不可信。
        this.mapper = mapperProvider.getIfAvailable(ObjectMapper::new);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 对全部响应生效：可见性是全局约束，不做"哪些返回值类型才管"的白名单 ——
        // 白名单的漏项正是本类要防的那类沉默失效。
        return true;
    }

    /**
     * 在序列化<b>之前</b>把派生字段从响应数据里整体摘除。
     *
     * <p>返回原对象表示"不干预"；返回被改写的数据表示"已摘除"。
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null || !DerivedFields.isClientLike(TenantContext.role())) {
            return body;
        }
        try {
            return filter(body, request.getURI().getPath());
        } catch (RuntimeException e) {
            // 🛑 fail-closed：摘除过程出错时绝不能"原样放过"——那等于把一次异常变成一次泄漏。
            // 改为返回一个"不含 data"的信封，使泄漏不可能发生，同时把问题暴露在日志里。
            log.error("客户响应出站摘除失败，按 fail-closed 丢弃响应数据以避免泄漏: path={}",
                    request.getURI().getPath(), e);
            return failClosedEnvelope(body);
        }
    }

    // ------------------------------------------------------------------ 过滤

    private Object filter(Object body, String path) {
        Object payload = body instanceof Result<?> r ? r.getData() : body;
        if (payload == null) {
            return body;   // 无数据可摘（含失败信封）—— 不干预，保持信封原样
        }
        JsonNode tree = mapper.valueToTree(payload);
        List<String> removed = stripDerived(tree);
        if (removed.isEmpty()) {
            return body;
        }
        // 留痕：出站摘除是"某个响应构造点试图下发派生字段"的信号，值得可检索。
        // 只记路径与字段名计数，不记值 —— 值本身就是不该出站的东西。
        log.warn("客户响应出站摘除派生字段 {} 处: path={} fields={}（这条说明某个响应构造点试图下发 ③④ 字段；"
                        + "应去修复那个构造点，而不是依赖本兜底长期兜着）",
                removed.size(), path, removed);
        return rebuild(body, tree);
    }

    /**
     * 递归摘除派生字段，返回<b>被摘掉的键名</b>（可能重复，含数组多元素里的同名键）。
     *
     * <p>先收集键名再删（不能边遍历边删 {@code ObjectNode} —— 那是未定义行为）。
     */
    private static List<String> stripDerived(JsonNode node) {
        List<String> removed = new ArrayList<>();
        collectAndStrip(node, removed);
        return removed;
    }

    private static void collectAndStrip(JsonNode node, List<String> removed) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            for (String name : names) {
                if (DerivedFields.isClientForbiddenField(name)) {
                    obj.remove(name);
                    removed.add(name);
                } else {
                    collectAndStrip(obj.get(name), removed);
                }
            }
            return;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (JsonNode child : arr) {
                collectAndStrip(child, removed);
            }
        }
    }

    /**
     * 把过滤后的树装回原响应壳。
     *
     * <p>{@link Result} 走 setter 就地改写（{@code code/message/trace_id} 原样保留 ——
     * 信封四字段不可裁剪）；其余类型直接返回过滤后的树。
     *
     * <p>⚠️ 刻意<b>不</b>在 {@code data} 上重建 {@code Result}：{@code trace_id} 是
     * {@code @JsonInclude(ALWAYS)} 的，重建时若漏了一次拷贝就会让 403/500 的
     * 响应丢 {@code trace_id}，破坏契约 §2.0「四字段不得增删」。
     */
    @SuppressWarnings("unchecked")
    private Object rebuild(Object body, JsonNode filtered) {
        if (body instanceof Result<?> r) {
            ((Result<Object>) r).setData(filtered);
            return r;
        }
        return filtered;
    }

    /** fail-closed 的响应：只保留信封，丢弃 data。 */
    @SuppressWarnings("unchecked")
    private Object failClosedEnvelope(Object body) {
        if (body instanceof Result<?> r) {
            ((Result<Object>) r).setData(null);
            return r;
        }
        return mapper.createObjectNode();
    }
}