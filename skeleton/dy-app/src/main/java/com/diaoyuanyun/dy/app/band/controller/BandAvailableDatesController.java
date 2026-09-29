package com.diaoyuanyun.dy.app.band.controller;

import com.diaoyuanyun.dy.app.band.domain.BandProbeRequest;
import com.diaoyuanyun.dy.app.band.domain.BandProbeResult;
import com.diaoyuanyun.dy.app.band.service.BandAvailableDatesService;
import com.diaoyuanyun.dy.common.result.Result;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * S1-7 手环「设备可用日期探测」接入桩 —— 契约 §4.2（E5）。
 *
 * <h2>为什么是个"桩"而不是完整实现</h2>
 * S1-7 的验收三项（开发清单）：
 * <ol>
 *   <li>手环入参/出参契约落盘 —— 落在 {@link BandProbeRequest} / {@link BandProbeResult}
 *       两个记录类型上，字段名与契约逐字对应；</li>
 *   <li>契约含「设备可用日期枚举」接口（{@code getValidHistoryDates} 探测结果上报）—— 即本端点；</li>
 *   <li><b>N 取运行时探测值</b>，代码/契约内<b>无硬编码 N</b> —— 由服务层结构性保证。</li>
 * </ol>
 * 真机 BLE 实测（第三批、不上关键路径）之前，本端点<b>不落库</b>：
 * 它只做契约校验与纯计算。落库需要真机确认的字段（{@code bufferSize} 语义、
 * 探测数组的真实形态），提前落库会把"待实测的假设"写成 schema —— 那是带债开发（红线③）。
 *
 * <h2>权限</h2>
 * 探测上报由<b>小程序端（客户）</b>发起 —— 契约 L829-862 逐字
 * {@code x-callable-roles: [client]}，{@code x-client-forbidden: false}，
 * responses = {@code 200 / 400 / 422}（<b>没有 403</b>）。
 *
 * <p>🛑 故本端点<b>刻意不贴任何 {@code @RequirePermission}</b> —— 与 A2 {@code /auth/me} 同款。
 * 理由是一条结构性事实：{@code PermissionRegistry} <b>刻意不登记 {@code client}</b>
 * （客户的档位解算走 A2 下发，且"client 无码"是本仓库的既有基线，
 * 由 {@code PermissionCodeRegistrationGateTest} 第⑤②条钉住）。
 * 于是<b>任何一个</b> {@code @RequirePermission}（哪怕是有主的 {@code customer:read}）
 * 都会让合法的客户端探测请求恒 403 —— 而契约要求它 200。
 *
 * <p>⚠️ 本类此前的实现贴的是 {@code customer:write}，并在注释里声称
 * 「骨架的权限注册表把 {@code customer:write} 授予小程序侧角色」——
 * 那句话<b>与注册表事实相反</b>（注册表从未给 {@code client} 任何码）。
 * 这是本仓库第五/六次"注册表与调用点各自演进"的同型缺口，
 * 且它<b>躲过了全部既有门禁</b>：{@code customer:write} 有主（manager 等持有），
 * 故码级门禁全绿；而 {@code DerivedVisibilityE2ETest} 里那条对 E5 的 403 断言，
 * 命中的是<b>派生字段拦截器</b>（排在权限拦截器之前，因请求体含 {@code as_value}），
 * 与权限层无关 —— 两条路径归同一个 2001，掩盖了这个缺口。
 *
 * <p>🛑 不贴码<b>不等于</b>免除"越权索取"的防护：E5 <b>不下发</b>任何可见性受限字段
 * （{@code gap_reason} 等属"仅门店/管理端可见"，在本端点里根本不存在），
 * 且请求体里的派生键名由 {@code DerivedVisibilityInterceptor} 独立拦截
 * （先于本控制器执行，返回 403 VISIBILITY_DENIED + {@code denied_fields}）。
 * 这一点由 {@code BandAvailableDatesTest} 与 {@code DerivedVisibilityE2ETest} 共同守住。
 *
 * <p>🛑 也<b>不</b>贴 {@code @StaffOnly}：契约 {@code x-client-forbidden: false}
 * 且无 {@code x-client-explicitly-denied} ⇒ 该端点本就该对客户端开放。
 */
@RestController
@RequestMapping("/api/v1/band")
public class BandAvailableDatesController {

    private final BandAvailableDatesService service;

    public BandAvailableDatesController(BandAvailableDatesService service) {
        this.service = service;
    }

    /**
     * E5 {@code POST /api/v1/band/available-dates}。
     *
     * <p>契约 §4.2 出参：{@code probe_id} / {@code retention_window_days} /
     * {@code pull_start_date} / {@code earliest_available} / {@code latest_available}。
     *
     * <p>错误码：{@code 1001}（{@code valid_history_dates} 为空<b>且非首次绑定</b>）｜
     * {@code 5001}（{@code history_type} 不在 13 条枚举内）。
     *
     * <p>🛑 <b>不贴 {@code @RequirePermission}</b>：契约 {@code x-callable-roles: [client]}，
     * 而 {@code client} 在注册表里刻意无码 ⇒ 贴任何码都会让合法客户端请求恒 403。
     * 详见类注释「权限」一节。
     */
    @PostMapping("/available-dates")
    public Result<Object> availableDates(@RequestBody BandProbeRequest request) {
        BandProbeResult result = service.probe(request);
        return Result.ok(result, MDC.get("traceId"));
    }

    /**
     * 契约自描述（只读、免权限）：把"13 条枚举 / 12+1 分支 / N 是运行时探测值"三件事
     * 变成可被客户端与回归用例集直接读取的运行时事实，而不是只写在文档里。
     *
     * <p>它<b>不</b>下发任何窗口数值 —— 一旦这里出现天数，它就会变成事实上的 N 常量。
     */
    @PostMapping("/available-dates/contract")
    public Result<Map<String, Object>> describeContract() {
        return Result.ok(service.describeContract(), MDC.get("traceId"));
    }
}