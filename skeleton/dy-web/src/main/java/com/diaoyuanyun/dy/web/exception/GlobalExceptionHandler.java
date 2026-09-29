package com.diaoyuanyun.dy.web.exception;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.exception.GateMissingException;
import com.diaoyuanyun.dy.common.exception.VisibilityDeniedException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 全局异常处理器 (ADR-06): 把 {@link BizException} 统一转 {@link Result} 信封, 回填 trace_id。
 *
 * <p><b>HTTP 状态来源</b>：直接取自 {@link ErrorCode#getHttpStatus()}，<b>不再用 if-else 分段推导</b>。
 * 初版实现按"码段"猜 HTTP（如 {@code 5xxx -> 502 BAD_GATEWAY}），与契约 §2.0 不符：
 * 契约中 {@code 5001 = BUSINESS_RULE_VIOLATED} 对应 <b>422</b>，且契约根本不存在"依赖故障"段。
 * 把映射内聚在枚举里，使"码值 + HTTP"成为同一个原子事实，可被契约一致性测试逐条比对。
 *
 * <h2>🔴 2026-09-25（S2-10）：补服务端留痕 —— 之前【一个字节的堆栈都不落盘】</h2>
 * 本类此前对 {@link Exception} 只做了一件事：把 {@code ex.getClass().getSimpleName()}
 * 塞进响应体外加一个 {@code trace_id}。响应体是给<b>调用方</b>看的
 * （契约纪律：对外只回显码与缺失项，不回显内部细节），这本身没错；
 * 错的是<b>服务端也不留</b> —— 于是运维拿着 {@code trace_id="286cfb06..."}，
 * 在全仓库日志里搜不到任何与之对应的行，<b>无法定位任何一次 500 的真因</b>。
 *
 * <p>这是本仓库第七次同型缺陷的<b>放大器</b>：前六次的缺口都还能靠"真请求 E2E 报出的
 * 具体码"反推，而第七次（B2 的 {@code BadSqlGrammarException}）只报出异常<b>类名</b>，
 * 类名既不含 SQL 也不含列名/参数 —— 排查被迫退化为"读源码猜哪条 SQL 写错了"。
 * 一个商用的多租户 SaaS 不能接受这种可观测性水平：<b>每次 500 都必然对应一条可查的堆栈</b>。
 *
 * <p>分级留痕（避免把正常的 4xx 业务拒绝也刷成 error 噪音）：
 * <ul>
 *   <li>{@code BizException} 且 HTTP ≥ 500 → {@code log.error}（真故障，带堆栈）；</li>
 *   <li>其余 {@code BizException}（4xx，正常业务拒绝，如 403 门禁）→ {@code log.warn}
 *       只记一行码与消息、<b>不带堆栈</b>（这类是预期路径，不是故障）；</li>
 *   <li>任何其它 {@link Exception} → {@code log.error}（带完整堆栈；这是本类之前缺失最严重的一支）。</li>
 * </ul>
 * 二者都带 {@code trace_id} 与 {@code uri}，使"响应体里的 trace_id"与"服务端日志行"
 * <b>可双向检索</b> —— trace_id 才算真的成了一个可用的关联键。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException ex, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        HttpStatus status = HttpStatus.resolve(ErrorCode.of(ex.getCode()).getHttpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // 分级留痕：5xx 是真故障（带堆栈）；4xx 是预期业务拒绝（一行 warn，不带堆栈）。
        if (status.is5xxServerError()) {
            log.error("业务异常(5xx) trace_id={} code={} uri={} msg={}",
                    traceId, ex.getCode(), request.getRequestURI(), ex.getDevMessage(), ex);
        } else {
            log.warn("业务拒绝({}) trace_id={} code={} uri={} msg={}",
                    status.value(), traceId, ex.getCode(), request.getRequestURI(), ex.getDevMessage());
        }
        Result<Object> body;
        if (ex instanceof GateMissingException g) {
            // 门禁缺失: 响应体回显 data.missing_items[] (ADR-05, 严禁模糊报错 H-8)
            // 注意契约要求的路径是 data.missing_items，故 data 必须是对象而非裸数组
            body = Result.failWithData(ex.getCode(), ex.getDevMessage(),
                    Map.of("missing_items", g.getMissingItems()), traceId);
        } else if (ex instanceof VisibilityDeniedException v) {
            // 可见性拒绝: 响应体回显 data.denied_fields[] (S1-5 · 契约 §3.2 / §2.1 B4)
            // 与门禁缺失同一条纪律(严禁模糊报错): 只说"无权"会让调用方只能靠猜 ——
            // 回显被拒字段名, 把"猜"变成"改一个确定的参数"。
            body = Result.failWithData(ex.getCode(), ex.getDevMessage(),
                    Map.of("denied_fields", v.getDeniedFields()), traceId);
        } else {
            body = Result.fail(ex.getCode(), ex.getDevMessage(), traceId);
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception ex, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        // 🔴 对外仍只回显类名（不回显内部细节）；但对内落【完整堆栈】——
        //    这是"响应体里的 trace_id"能与"服务端日志行"互检的唯一依据。
        log.error("未捕获异常 trace_id={} uri={} method={}",
                traceId, request.getRequestURI(), request.getMethod(), ex);
        Result<Void> body = Result.fail(ErrorCode.INTERNAL_ERROR.getCode(),
                "系统异常: " + ex.getClass().getSimpleName(), traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * 🔴 2026-09-27（C-3）：请求体/参数在<b>进入控制器之前</b>就反序列化失败 ⇒ 400·1001，不是 500·9001。
     *
     * <h2>它修复的缺陷（由 C-3 写路径矩阵的真实请求暴露）</h2>
     * <pre>
     *   POST /api/v1/refunds/{id}/retentions
     *   body: {"attempts":1,"result":"接受继续服务","analysis":{},"communication":{}}
     *   → 500 · 9001 「系统异常: HttpMessageNotReadableException」
     * </pre>
     * 成因：{@code CreateRetentionRequest} 把 {@code analysis} / {@code communication}
     * 声明为 {@code String}，而请求体给的是 JSON <b>对象</b>；Jackson 抛
     * {@link HttpMessageNotReadableException}。它此前落到 {@link #handleOther}
     * ⇒ <b>500 · 9001「系统内部错误」</b>。
     *
     * <h2>🛑 为什么这不是"错误码不精确"，而是契约违背</h2>
     * 契约 §2.0 的 {@code 1001} 逐字是「参数校验失败」并对应 <b>400</b>，
     * 且 G1 的 {@code responses} 明列 {@code '400' → ValidationFailed}。
     * 一个"调用方把字段类型写错"的请求被回答成"服务端内部错误"，
     * 会带来三个具体的坏后果：
     * <ol>
     *   <li><b>端侧行为错</b>：SDK 拿到 5xx 会按"服务端故障"重试（含退避与熔断），
     *       而这是<b>永远不会成功</b>的重试 —— 正确的动作是让开发者改请求体；</li>
     *   <li><b>告警噪音</b>：监控上"5xx 率"是服务健康度的核心指标。
     *       让每个端侧字段类型错误都计入 5xx，会让真实故障淹没在噪音里
     *       （这与 {@link #handleBiz} 那处分级留痕是同一类纪律：4xx 是预期路径，不是故障）；</li>
     *   <li><b>排查方向被带偏</b>：{@code 9001} 的消息是"系统异常: HttpMessageNotReadableException"，
     *       运维会去查服务端 —— 而问题在那个请求里。</li>
     * </ol>
     *
     * <h2>🛑 对外不回显解析细节（沿用本类一贯纪律）</h2>
     * 三个分支都只回显<b>字段语义</b>与"这是什么类型的问题"，<b>不</b>回显
     * Jackson 的原始消息（它含包名、类名与目标类型的内部路径）。
     * 与 {@code handleOther} 的"只回类名"同源：响应体是给调用方看的，
     * 内部细节留在服务端日志里（本方法仍落 {@code warn}，因这是预期路径而非故障）。
     *
     * <h2>为什么这三类合在一个方法里</h2>
     * 它们共享<b>同一个语义</b>：请求<b>在进入业务逻辑之前</b>就不合法。
     * 分开写会让"哪一类该报 400、哪一类该报 500"在三处各自表述 ——
     * 而其中一处将来被改成 500 时，另两处仍绿，症状是"同一个字段偶尔 400 偶尔 500"。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> handleUnreadableRequest(Exception ex, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        String detail;
        if (ex instanceof HttpMessageNotReadableException) {
            detail = "请求体无法解析 —— 可能的成因：JSON 语法错误、"
                    + "或某个字段的**类型**与契约不符（例如契约声明为字符串的字段传了对象/数组）。"
                    + "请按契约 Schema 核对字段类型";
        } else if (ex instanceof MethodArgumentTypeMismatchException m) {
            detail = "参数类型不匹配: " + m.getName()
                    + "（期望类型见契约；例如路径变量 / 查询参数应是 UUID 却传了其它格式）";
        } else {
            detail = "缺少必填参数";
        }
        // 归为"预期路径"而非故障：与 handleBiz 对 4xx 的分级留痕一致（不带堆栈）。
        log.warn("请求不可解析(400) trace_id={} uri={} method={} type={} msg={}",
                traceId, request.getRequestURI(), request.getMethod(),
                ex.getClass().getSimpleName(), detail);

        Result<Void> body = Result.fail(ErrorCode.VALIDATION_FAILED.getCode(), detail, traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}