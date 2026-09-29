package com.diaoyuanyun.dy.app.device.service;

import com.diaoyuanyun.dy.app.device.domain.DeviceOutcome;
import com.diaoyuanyun.dy.app.device.domain.DeviceRecord;
import com.diaoyuanyun.dy.app.device.repository.DeviceLedger;
import com.diaoyuanyun.dy.audit.domain.AuditLog;
import com.diaoyuanyun.dy.audit.service.AuditLogService;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>设备建档服务</b> —— 把「往门店建设备台账」从一次裸的数据库写入，
 * 变成一次<b>带审计留痕的、原子的、可重放的</b>门店作业动作。
 *
 * <h2>🛑 这个类为什么必须存在（它比仓储多做了什么）</h2>
 * <ol>
 *   <li><b>审计留痕</b>：设备台账<b>不是</b>一份无关紧要的登记表 ——
 *       它是 {@code device_dispatch}（D6 下发）的引用目标，而 D6 的语义是
 *       「下行、<b>可追责</b>」（与手环上行"不得作不利依据"相对，见 V5 §2.14 注释）。
 *       ⇒ 一次无痕的建档，等于一次<b>无主的可追责台账起点</b>：
 *         事后无法回答"这台设备什么时候、由谁建进来的"，
 *         而"参数下发到了错误的门店"这类追责恰恰要回到这个起点。
 *       <p>🛑 与 B-10（手环绑定）的审计理由<b>不同</b>，必须说清：
 *       B-10 的理由是"应戴天分母的两端 → 影响 A3 → 影响退款资格"；
 *       本类的理由是"<b>可追责性需要起点</b>"。
 *       两条理由都指向"必须同事务审计"，但它们不是同一条理由 ——
 *       若混为一谈，下一个人会以为改掉其中一个的审计纪律是可以的。</li>
 *   <li><b>操作者身份</b>：仓储不知道"谁在做"，只写
 *       {@code created_by='device-registration'}。真正的操作者由本类的调用方
 *       （门店作业工具 / 运维脚本）提供，并落进审计 {@code actor} ——
 *       那是审计里唯一有追责含义的一栏。</li>
 * </ol>
 *
 * <h2>🛑 幂等重放【也】写审计 —— 这是裁定，不是疏漏</h2>
 * 两态都写审计：{@link DeviceOutcome#mutatedData()}（真改了数据）与
 * {@link DeviceOutcome#idempotentReplay()}（重放，数据未变）。
 * <p>理由与 B-10 逐字同款（且本处更直白）：库层 {@code ON CONFLICT DO NOTHING}
 * <b>不报错、不改数据、返回一个正常值</b>，于是"有人试图重复建一台设备"这件事，
 * 若不留痕就<b>在系统里完全不可见</b>。那可能是一次误传的 {@code device_id}，
 * 也可能是一次真实的重复建档 —— 两者都值得在事后被看见。
 * <p>一句话边界：<b>幂等保证的是"不重复建"，不是"不重复记录"</b>。
 *
 * <h2>🛑 跨租户撞号【不】留审计 —— 这是本类唯一与 B-10 不同的处置，必须写清</h2>
 * B-10 对"客户已有有效手环"（不变量胜出）也写了审计，因为那是一个
 * <b>返回值</b>（正常结果）。而本类面对的"device_id 被另一租户占用"
 * 在 V18 里是 <b>RAISE</b> ⇒ 它<b>根本走不到</b>本类的审计语句：
 * 事务在 {@code ledger.register()} 里就被异常终止了。
 * <p>🛑 这不是"漏了一条审计"，而是"这个情形没有可留痕的成功动作"。
 * 若哪天有人希望它也被留痕，正确的做法是<b>在库函数里先写一条审计再 RAISE</b>
 * （但那时审计行会被同一个 RAISE 一起回滚 —— 故实际上需要一条独立事务），
 * 或者由调用方在上层捕获后单独记录。
 * 本类刻意<b>不</b>在这里 try/catch 后补一条审计 —— 那会造出
 * "审计记了一次建档、而库里一行都没有"的不一致。
 *
 * <h2>🛑 为什么审计写入必须与建档【同一个事务】</h2>
 * 由代码结构保证，而不是靠注释提醒：仓储的 {@code register()} 内部用
 * {@code TransactionTemplate}（传播行为缺省为 {@code REQUIRED}），
 * 故它会<b>加入</b>本类开的事务，而不是另开一个。
 * ⇒ {@code set_config('app.tenant_id', ..., is_local := true)} 建立的上下文
 * 活到<b>本事务提交</b>，而审计条目也落在同一个提交里。
 * <p>🛑 这一条有一个<b>易被忽略的前提</b>：若将来有人把 {@code register()} 的
 * {@code TransactionTemplate} 改成 {@code PROPAGATION_REQUIRES_NEW}，
 * 那么建档会独立提交 —— 于是"审计失败则建档回滚"这条不变量<b>静默失效</b>。
 * 本仓对这一类有既定处置：把前提写成可断言的东西
 * （见 {@code DeviceProvisioningGateTest} 的"建档与审计同事务"用例 ——
 * 它用一次<b>注入的审计失败</b>来证明建档确实被回滚了）。
 *
 * <h2>🛑 为什么审计条目 id 必须取自 {@code append()} 的返回值</h2>
 * 理由与 {@code BandBindingService} / {@code OrganizationProvisioningService} 逐字相同：
 * {@code AuditLogService.append()} 的契约写明 <b>id 由实现生成、忽略调用方传入的</b>。
 * B-7 的初版曾塞了一个自编的 {@code UUID.randomUUID()} 并当作回执返回 ——
 * 实测 {@code SELECT count(*) FROM audit_log WHERE id = ?} 返回 <b>0</b>。
 * 故本类构造 {@code AuditLog} 时第一个参数<b>刻意留空串</b>（占位、会被忽略），
 * 并显式取 {@code append()} 的返回值。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 建档动作名 —— 落在 {@code audit_log.action}。 */
    static final String ACTION_REGISTERED = "DEVICE_REGISTERED";

    /** 归档动作名。 */
    static final String ACTION_RETIRED = "DEVICE_RETIRED";

    /** 审计目标的类型名（{@code audit_log.target_type}）。 */
    static final String TARGET_TYPE = "device";

    private final DeviceLedger ledger;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final TransactionTemplate tx;

    public DeviceService(DeviceLedger ledger,
                         ObjectProvider<AuditLogService> auditLogService,
                         DataSource dataSource) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 建档
    // ==================================================================

    /**
     * 幂等往门店建一台设备，并在同一个事务里留一条审计。
     *
     * @param record   建档意图（其构造器会先跑一遍形态校验：必填 / 列长 / 租户标识）
     * @param operator 操作者标识（写进审计 {@code actor}；必填 ——
     *                 一次无主的可追责台账起点，事后无法追责）
     * @return 建档结果（两态之一 + 本次留痕的审计 id）
     */
    public DeviceProvisioningResult register(DeviceRecord record, String operator) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "设备建档：记录不可为空");
        }
        requireOperator(operator);

        return tx.execute(status -> {
            // ① 库层原语（它加入本事务，故其建立的上下文活到本次提交）
            DeviceOutcome outcome = ledger.register(record);

            // ② 审计留痕（同一事务 —— 见类注释"为什么必须同事务"）
            //    🛑 第一个参数（id）是【占位】：append() 的实现会忽略它、
            //       自己生成真正的 id 并返回。刻意留空串，防止下一个人
            //       误以为回执取的就是这个字段（B-7 已付费记录过这个缺陷）。
            AuditLog entry = new AuditLog(
                    "",
                    record.tenantId(),
                    operator,
                    ACTION_REGISTERED,
                    TARGET_TYPE,
                    record.deviceId().toString(),
                    Instant.now(),
                    registerPayload(record, outcome),
                    null, null);   // 链字段由 AuditLogService 实现自算，不得由调用方提供

            String auditId = appendAuditOrFail(entry);

            log.info("[DEVICE-REGISTER] tenant={} device={} store={} mode={} operator={} auditId={}",
                    record.tenantId(), record.deviceId(), record.storeId(),
                    outcome.name(), operator, auditId);

            return new DeviceProvisioningResult(outcome, auditId, record.deviceId());
        });
    }

    // ==================================================================
    // 归档
    // ==================================================================

    /**
     * 幂等归档一台设备，并在同一个事务里留一条审计。
     *
     * <p>🛑 是状态迁移（{@code status → 'retired'}）而不是删除行 —— 见
     * {@link DeviceLedger#retire} 与 V18 {@code retire_device()} 的注释。
     */
    public DeviceProvisioningResult retire(String tenantId, UUID deviceId, String operator) {
        requireOperator(operator);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "归档设备：device_id 必填");
        }

        return tx.execute(status -> {
            DeviceOutcome outcome = ledger.retire(tenantId, deviceId);

            AuditLog entry = new AuditLog(
                    "",
                    tenantId,
                    operator,
                    ACTION_RETIRED,
                    TARGET_TYPE,
                    deviceId.toString(),
                    Instant.now(),
                    retirePayload(outcome),
                    null, null);

            String auditId = appendAuditOrFail(entry);

            log.info("[DEVICE-RETIRE] tenant={} device={} mode={} operator={} auditId={}",
                    tenantId, deviceId, outcome.name(), operator, auditId);

            return new DeviceProvisioningResult(outcome, auditId, deviceId);
        });
    }

    // ==================================================================
    // 读侧（运维核对 —— 全部走仓储的 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /** 该设备在本租户内的状态（无则 {@code null}）。 */
    public String statusOf(String tenantId, UUID deviceId) {
        return ledger.statusOf(tenantId, deviceId);
    }

    /** 该门店的设备台账摘要（{@code device_id / model / status / param_template_id}）。 */
    public java.util.List<String[]> devicesOfStore(String tenantId, UUID storeId) {
        return ledger.devicesOfStore(tenantId, storeId);
    }

    /** 该租户内当前有效设备数。 */
    public int countActiveInTenant(String tenantId) {
        return ledger.countActiveInTenant(tenantId);
    }

    /**
     * 🛑 <b>契约 D6 端点是否已解锁</b> —— 见 {@link DeviceLedger#isReferenceable}。
     *
     * <p>暴露到 Service 层是为了让"本迁移的验收口径"能被上层直接问出来：
     * 「这个 {@code device_id} 现在能不能被 {@code device_dispatch} 引用」。
     * 它回答的是库层会不会接受那条引用（{@code 23503} vs 成功），
     * 而不是"我能不能查到这一行"。
     */
    public boolean isDispatchable(String tenantId, UUID deviceId) {
        return ledger.isReferenceable(tenantId, deviceId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "设备建档/归档必须提供操作者标识（operator）—— 审计里 actor 是唯一有追责含义的一栏，"
                            + "不允许为空。device 是 device_dispatch（D6 下发）的引用目标，"
                            + "而 D6 的语义是『下行、可追责』：一次无主的建档，"
                            + "等于一次事后无法追责的可追责台账起点");
        }
    }

    /**
     * 追加审计条目；审计服务未装配时<b>抛错</b>（而不是降级）。
     *
     * <p>与 {@code BandBindingService#appendAuditOrFail} /
     * {@code OrganizationProvisioningService#appendAuditOrFail} 同一取舍。
     * <p>🛑 本类多一条理由而<b>不</b>只是照抄：D6 的可追责性要求"下发对象有来源"。
     * 若允许降级，会出现"设备存在但没有建档记录"——
     * 而那时连"这台设备是谁建的"都不可答，追责链在起点就断了。
     */
    private String appendAuditOrFail(AuditLog entry) {
        AuditLogService svc = auditLogService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "审计服务未装配 —— 拒绝执行设备建档/归档。"
                            + "device 是 device_dispatch（D6 下发，语义为『下行、可追责』）的引用目标，"
                            + "一次没有审计的建档无法事后补证『这台设备从哪来、谁建的』，"
                            + "而参数下发到错误门店这类追责恰恰要回到这个起点。"
                            + "故此处不回退为『建档继续、日志降级』");
        }
        return svc.append(entry);
    }

    /**
     * 建档审计 payload —— 只放<b>可公开摘要</b>。
     *
     * <p>🛑 克制原则与 {@code BandBindingService} 逐字同款，但要说明这里的具体边界：
     * {@code audit_log} 是<b>无 RLS、全租户可读</b>的
     * （见 {@code RlsCoverageGateTest} 的 {@code NON_TENANT_TABLES} 注释）。
     * 故这里只写<b>标识与模式</b>：{@code device_id} / {@code store_id}（都是 UUID）
     * + 本次走的是哪条路径 + 型号。
     * <p>🛑 为什么 {@code model} 可以写而 {@code param_template_id} 不写：
     * {@code model} 只有两个取值（{@code 杠2 / 现有}），是"这台设备属于哪一类"
     * 这种不可反推经营细节的信息；而 {@code param_template_id} 是
     * "这个租户在用哪一套参数模板"，属经营信息 —— 与 B-10 不写 {@code vendor} 同理。
     * 要查设备细节应查 {@code device} 表本身，那里有 RLS 保护。
     */
    private static String registerPayload(DeviceRecord r, DeviceOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"deviceId\":\"" + r.deviceId()
                + "\",\"storeId\":\"" + r.storeId()
                + "\",\"model\":\"" + r.model()
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /** 归档审计 payload。 */
    private static String retirePayload(DeviceOutcome o) {
        return "{\"mode\":\"" + o.name()
                + "\",\"mutated\":" + o.mutatedData()
                + "}";
    }

    /**
     * 建档 / 归档的结果。
     *
     * <p>🛑 {@code auditId} 是 {@code append()} 返回的<b>实现生成的</b> id ——
     * 供调用方对账（"这次变更的审计证据是哪一条"）。回执存在的唯一理由就是对账，
     * 故它必须是库里真实的那个 id（见类注释「为什么审计条目 id 必须取自返回值」）。
     */
    public record DeviceProvisioningResult(DeviceOutcome outcome, String auditId, UUID deviceId) {

        /** 本次调用是否真的改动了台账数据。 */
        public boolean mutated() {
            return outcome.mutatedData();
        }
    }
}