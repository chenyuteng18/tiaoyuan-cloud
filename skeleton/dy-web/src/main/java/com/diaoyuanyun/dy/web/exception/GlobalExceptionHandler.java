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
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 🔴 2026-10-01（第 73 条）：<b>路由不存在</b>不是「服务端异常」⇒ 404，不是 500·9001。
     *
     * <h2>它修复的缺陷（启动后真请求实测暴露）</h2>
     * <pre>
     *   GET /api/v1/definitely-not-exist
     *   → 500 · 9001 「系统异常: NoResourceFoundException」   ← 修复前（把「请求不合法」答成「服务端故障」）
     *   → 404 · <b>无 code 字段</b>                            ← 修复后（回到本仓既有的设计预期）
     * </pre>
     * Spring Boot 3.2+ 对「无处理器匹配」抛 {@link NoResourceFoundException}，
     * 它此前落到 {@link #handleOther} ⇒ 被当作<b>服务端内部错误</b>。
     *
     * <h2>🛑 为什么修复形态是「404 且【不带】code」，而不是「404 · 3001」</h2>
     * 这一点必须写清，否则后来者会把"没有 code"当成遗漏而"修"成 3001，从而撞坏一条既有自证用例：
     * <ol>
     *   <li><b>契约不覆盖未知路由</b>：契约 §2.0 的 {@code 404 → 3001 NOT_FOUND} 的 trigger 逐字是
     *       「资源不存在（<b>仅限本租户内确实不存在</b>）」—— 限定语指向<b>业务资源</b>
     *       （工单/客户/门店…）；「URL 拼错」不在其列。契约只定义了 40 个 path，
     *       未知路由<b>不是契约端点</b> ⇒ 「响应必须是四字段信封」这条契约纪律也不适用于它。</li>
     *   <li><b>本仓已有一处测试钉住了相反的设计预期</b>：
     *       {@code RefundWritePathMatrixE2ETest$SelfProof#the_self_proof_discriminates_a_nonexistent_path}
     *       逐字断言「不存在的路径<b>不得</b>返回 code=3001」，理由是那条自证用例
     *       <b>正是靠「未知路径无 code」来区分「端点根本没实现」与「业务层查无此单」</b>——
     *       因为正向矩阵断言的就是 404，若两者无法区分，「端点没实现」会被误判成「角色被放行」。
     *       让未知路由也返回 3001，会让那条自证用例丧失分辨力（第 55 条：判据太窄/无分辨力）。</li>
     * </ol>
     * ⇒ 故本分支<b>刻意不返回任何 body</b>：{@code codeOrZero()} 得到 0，自证用例继续成立，
     * 而 HTTP 已从 500 回到 404（不再声称「服务端故障」）。
     *
     * <h2>🛑 为什么这仍然是真缺陷（不是"错误码不精确"）</h2>
     * 把「请求根本不合法」答成 5xx，有三个具体坏后果：
     * <ol>
     *   <li><b>端侧行为错</b>：SDK 拿到 5xx 会按「服务端故障」重试（退避 + 熔断），
     *       而这是<b>永远不会成功</b>的重试 —— 正确的动作是让开发者改 URL；</li>
     *   <li><b>告警噪音</b>：监控上「5xx 率」是服务健康度的核心指标。把每个 URL 笔误
     *       都计入 5xx，会让真实故障淹没在噪音里（与 {@link #handleBiz} 那处分级留痕同一纪律）；</li>
     *   <li><b>排查方向被带偏</b>：{@code 9001} 的消息是"系统异常: NoResourceFoundException"，
     *       运维会去查服务端 —— 而问题在 URL 里。</li>
     * </ol>
     * 这与 C-3 修过的 {@code HttpMessageNotReadableException}（请求体类型错被答成 500 后改为 400·1001）
     * 是<b>同族</b>：凡「请求在进入业务逻辑之前就不合法」的各类，都不该落到 {@link #handleOther}。
     *
     * <h2>留痕：{@code warn} + 响应头里有 {@code X-Trace-Id}</h2>
     * URL 笔误是<b>预期路径</b>（客户端 bug / 扫描器噪音），不是服务端故障 ⇒ 沿用本类
     * 「4xx 是预期路径，不打堆栈、不进 error 噪音」的分级纪律。
     * 虽然本分支不回 body，但 {@code TraceIdFilter} 已把 {@code X-Trace-Id} 写进响应头，
     * 且本方法落 {@code warn}（含 uri 与 trace_id）⇒ 排查链完整（body 无 code 是本仓的设计预期，不是遗漏）。
     *
     * <h2>🛑 2026-10-01（同族枚举补齐）：为什么这个方法里住着 <b>三个</b> 异常类型</h2>
     * 第 73 条修完 {@link NoResourceFoundException} 之后，按本仓第 61/73 条的纪律
     * <b>「修一处同族时必须枚举全族」</b>，对「请求在到达业务逻辑之前就不合法」的各类做了启动后真请求实测，
     * 结果抓出<b>同族的另外两个成员也漏在 {@link #handleOther} 里</b>（都是 {@code 500 · 9001}）：
     * <pre>
     *   DELETE /api/v1/doc-templates          → 500 · 9001「系统异常: HttpRequestMethodNotSupportedException」  ← 应为 405
     *   GET    /api/v1/customers              → 500 · 9001「系统异常: HttpRequestMethodNotSupportedException」  ← 应为 405
     *   POST   /api/v1/demo/order (text/plain)→ 500 · 9001「系统异常: HttpMediaTypeNotSupportedException」      ← 应为 415
     * </pre>
     * 三者共享<b>同一个语义</b>：<b>请求本身（路径 / 方法 / 媒体类型）与任何端点都不匹配</b> ——
     * 完全不涉及服务端故障，也完全不涉及业务资源是否存在。故合在一个方法里，
     * 与 {@link #handleUnreadableRequest} 把「请求体/参数不合法」三类合在一个方法的理由同源：
     * <b>分开写会让「该报 4xx」在几处各自表述，将来一处被改成 500 时其余仍绿。</b>
     *
     * <h2>🛑 为什么这三类都【不带】code（与 {@link #handleUnreadableRequest} 的 400·1001 相反）</h2>
     * 判据不是"是不是 4xx"，而是<b>契约有没有为该情形定义过 code</b>：
     * <ul>
     *   <li>契约 §2.0 的码表只有 {@code 1001/1002/2001-2004/3001/4001/4002/5001/6001/9001} 十个 ——
     *       <b>没有 405、也没有 415</b>。既然契约从未定义「方法不支持」「媒体类型不支持」这两个情形，
     *       回一个自造 code 会让客户端落进<b>契约未声明的分支</b>；
     *       而 {@code 1001 VALIDATION_FAILED} 的 trigger 逐字是「参数类型 / 必填 / <b>约束</b>不满足」——
     *       它说的是<b>参数的值</b>不满足约束，不是"请求的方法/媒体类型不匹配"，
     *       用它去答 405/415 属于 <b>code 语义挪用</b>（正是第 73 条修的那类错误码语义反了的翻版）。</li>
     *   <li>⇒ 与 {@link NoResourceFoundException} 同款：<b>回裸状态码、不带 body</b>。
     *       这不只是"更严谨"：本仓 {@code RefundWritePathMatrixE2ETest$SelfProof} 依赖
     *       「非契约端点的失败响应<b>无 code</b>」作为分辨信号（见上），三者行为一致才不会互相干扰。</li>
     * </ul>
     * 留痕取 {@code warn}（同 {@link #handleUnreadableRequest}：这是预期路径，不是故障，不打堆栈）。
     *
     * <h2>实测已枚举到的「同族全体」与各自归属（2026-10-01，启动后真请求逐条探测）</h2>
     * <pre>
     *   NoResourceFoundException              → 404（本方法）      路径不存在
     *   HttpRequestMethodNotSupportedException→ 405（本方法）      方法不支持
     *   HttpMediaTypeNotSupportedException    → 415（本方法）      媒体类型不支持
     *   HttpMediaTypeNotAcceptableException   → 406（本方法）      Accept 不可接受
     *   HttpMessageNotReadableException       → 400 · 1001（已在 handleUnreadableRequest）
     *   MethodArgumentTypeMismatchException   → 400 · 1001（同上）
     *   MissingServletRequestParameterException→ 400 · 1001（同上）
     * </pre>
     * ⇒ <b>族里已无遗漏成员</b>。这条"枚举清单"本身即判据：将来若有人新增异常处理而漏了某类，
     * 应把它补进这张表，而不是让它继续落回 {@link #handleOther} 变成 500。
     *
     * <h2>🛑 第四员（406）是被【日志】抓出来的，不是被状态码抓出来的 —— 这条教训最值得记</h2>
     * 首次枚举时我把它写成"Spring 裸默认、实测已是裸 406、无 code，<b>无需处理</b>"，
     * 依据是 <b>HTTP 状态码看起来对</b>（实测确实是 406、且无 code）。<b>这个推断是错的。</b>
     * 复查启动日志才发现它<b>同样走了 {@link #handleOther}</b>：
     * <pre>
     *   ERROR ... 未捕获异常 ... HttpMediaTypeNotAcceptableException: No acceptable representation
     *   at ...AbstractMessageConverterMethodProcessor.writeWithMessageConverters(...)
     * </pre>
     * 即：<b>状态码碰巧是 406，但留痕打了 {@code ERROR} + 满堆栈</b> ——
     * 这违反了本类「4xx 是预期路径，不打堆栈、不进 error 噪音」的分级纪律，
     * 会把 {@code Accept} 头写错的客户端噪音计入 error 级告警指标（与 404/405/415 是<b>同一个坏后果</b>）。
     * <b>⇒ 判据：处理是否正确，要看「HTTP 状态码」和「留痕级别」两件事，不能只看状态码。</b>
     * 状态码对而留痕级别错，是"半个正确" —— 而"半个正确"在只看状态码的冒烟里长得和"全对"一模一样。
     * （本仓第 73 条的方法论同一句：<b>只看你想看的那个信号，就会漏掉没看的那一半。</b>）
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<Void> handleUnroutableRequest(Exception ex, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        HttpStatus status;
        String kind;
        if (ex instanceof NoResourceFoundException) {
            status = HttpStatus.NOT_FOUND;
            kind = "路由不存在";
        } else if (ex instanceof HttpRequestMethodNotSupportedException methodEx) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            kind = "方法不支持: " + methodEx.getMethod() + "（允许: " + methodEx.getSupportedHttpMethods() + "）";
        } else if (ex instanceof HttpMediaTypeNotSupportedException) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            kind = "媒体类型不支持";
        } else {
            status = HttpStatus.NOT_ACCEPTABLE;
            kind = "Accept 不可接受";
        }
        // URL 笔误 / 客户端用错方法 / Content-Type / Accept 都是【预期路径】(客户端 bug / 扫描器噪音)，不是服务端故障。
        log.warn("请求不可路由({}) trace_id={} uri={} method={} type={} {}",
                status.value(), traceId, request.getRequestURI(), request.getMethod(),
                ex.getClass().getSimpleName(), kind);
        // 🛑 刻意不回 body：这三类都不是契约端点行为，且契约未为它们定义 code。
        //    回 {code:9001} 会把「请求不合法」谎报成「服务端故障」；回自造 code 会让客户端落进契约未声明分支。
        return ResponseEntity.status(status).build();
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
     * 🔴 2026-09-27（C-3 起点）· 2026-10-07（第 74 条族级枚举补全）：请求体/参数在<b>进入控制器之前</b>
     * 就反序列化失败 / 类型不匹配 / 缺必填项 / Bean Validation 失败 ⇒ <b>统一 400·1001</b>，不是 500·9001。
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
     * 各分支只回显<b>字段语义</b>与"这是什么类型的问题"，<b>不</b>回显
     * Jackson 的原始消息（它含包名、类名与目标类型的内部路径）。
     * 与 {@code handleOther} 的"只回类名"同源：响应体是给调用方看的，
     * 内部细节留在服务端日志里（本方法仍落 {@code warn}，因这是预期路径而非故障）。
     *
     * <h2>🛑 2026-10-07（第 74 条族级枚举）：为什么这六类合在一个方法里，且此前漏了其中三类</h2>
     * 它们共享<b>同一个语义</b>：请求<b>在进入业务逻辑之前</b>就不合法（参数绑定 / Bean Validation 阶段）。
     * 分开写会让"哪一类该报 400、哪一类该报 500"在多处各自表述 ——
     * 而其中一处将来被改成 500 时，其余仍绿，症状是"同一个字段偶尔 400 偶尔 500"。
     * <br>
     * 第 74 条按本仓「修一处同族必须枚举全族」的纪律，对"参数绑定 / 校验"这一类做了启动后真请求实测，
     * 抓出<b>同族里此前漏在 {@link #handleOther} 的成员</b>（都是 {@code 500 · 9001}）：
     * <pre>
     *   POST /api/v1/doc-templates/uploads  （multipart，但省略必填 part "file"）
     *   → 500 · 9001 「系统异常: MissingServletRequestPartException」   ← 修复前（应为 400）
     * </pre>
     * {@code MissingServletRequestPartException} 继承自 {@link ServletRequestBindingException}，
     * 而原方法只列了 {@code HttpMessageNotReadableException / MethodArgumentTypeMismatchException /
     * MissingServletRequestParameterException} 三者 —— <b>漏了 {@code ServletRequestBindingException} 这一支
     * （含缺请求头 + 缺 multipart part）</b>，于是"客户端漏传一个 part"被当成"服务端内部错误"。
     * 另补 {@code MethodArgumentNotValidException}（@Valid 体校验失败，回显被拒字段名）——
     * 它来自 spring-web，<b>免费</b>并入；其触发前提是存在一个 Validator bean，当前骨架未引 validation starter，
     * 故该分支<b>休眠</b>（@Valid 不会真正跑校验），但一旦将来引入校验即自动生效，无需再改本类。
     * <br>
     * 🛑 <b>{@code ConstraintViolationException}（@Validated 方法级校验失败）本轮<b>刻意不并入</b></b>：
     * 它属于 {@code jakarta.validation} 包，而本仓<b>有意不引入</b> validation starter —— 引入
     * {@code spring-boot-starter-validation} 会把 {@code hibernate-validator}（含 "hibernate" 字面量）拉进 classpath，
     * 撞坏 {@code ProvisioningBoundaryGateTest#no_orm_is_on_the_classpath}（该测试用字面扫描守护
     * "classpath 无 ORM"这一架构边界，是 provisioning「表名 ⇔ 写入路径」静态判据的前提）。
     * 且当前骨架无任何 @Validated 用法、也无 Validator bean，该异常<b>根本不可达</b>；
     * 故不并入既尊重不变式、又不留不可达分支。若将来确需方法级校验，应：① 评估 ORM 边界后再决定引入方式；
     * ② 在此处补 {@code ConstraintViolationException} 分支（届时 {@code jakarta.validation} 才在 classpath）。
     *
     * <h2>🛑 实测已枚举到的「同族全体」（2026-10-07，启动后真请求逐条探测）</h2>
     * <pre>
     *   HttpMessageNotReadableException        → 400 · 1001（已在：请求体无法解析）
     *   MethodArgumentTypeMismatchException     → 400 · 1001（已在：查询/路径参数类型错）
     *   MissingServletRequestParameterException → 400 · 1001（已在：缺必填查询参数）
     *   MethodArgumentNotValidException          → 400 · 1001（本轮补：@Valid 体校验失败，回显被拒字段名；休眠，需 Validator bean）
     *   ServletRequestBindingException
     *     ├ MissingServletRequestPartException  → 400 · 1001（本轮补：缺必填 multipart part）★ 真缺口，真请求实测抓出
     *     └ MissingRequestHeaderException        → 400 · 1001（本轮补：缺必填请求头）
     * </pre>
     * ⇒ <b>本仓可达成员已无遗漏</b>（{@code ConstraintViolationException} 因上述原因不可达、不并入）。
     * 这条"枚举清单"本身即判据：将来若有人新增异常处理而漏了某类，
     * 应把它补进这张表，而不是让它继续落回 {@link #handleOther} 变成 500。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class,
            ServletRequestBindingException.class,
            MissingServletRequestPartException.class,
            MissingRequestHeaderException.class})
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
        } else if (ex instanceof MethodArgumentNotValidException v) {
            detail = "请求参数校验失败: " + formatFieldErrors(v);
        } else if (ex instanceof MissingServletRequestPartException mp) {
            detail = "缺少必填的 multipart part: " + mp.getRequestPartName();
        } else if (ex instanceof MissingRequestHeaderException mh) {
            detail = "缺少必填请求头: " + mh.getHeaderName();
        } else if (ex instanceof MissingServletRequestParameterException mp) {
            detail = "缺少必填参数: " + mp.getParameterName();
        } else {
            detail = "请求参数绑定失败";
        }
        // 归为"预期路径"而非故障：与 handleBiz 对 4xx 的分级留痕一致（不带堆栈）。
        log.warn("请求不可解析(400) trace_id={} uri={} method={} type={} msg={}",
                traceId, request.getRequestURI(), request.getMethod(),
                ex.getClass().getSimpleName(), detail);

        Result<Void> body = Result.fail(ErrorCode.VALIDATION_FAILED.getCode(), detail, traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 把 {@link MethodArgumentNotValidException} 的被拒字段名拼成一句话（不得模糊报错：
     * 调用方要的是"哪个字段不合法"，不是一句"校验失败"）。
     */
    private static String formatFieldErrors(MethodArgumentNotValidException v) {
        return v.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .collect(Collectors.joining(", ", "[", "]"));
    }
}