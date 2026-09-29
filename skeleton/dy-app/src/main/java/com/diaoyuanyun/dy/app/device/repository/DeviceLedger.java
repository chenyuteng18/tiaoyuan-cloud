package com.diaoyuanyun.dy.app.device.repository;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.app.device.domain.DeviceOutcome;
import com.diaoyuanyun.dy.app.device.domain.DeviceRecord;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>设备台账写入仓储</b> —— 本仓<b>唯一</b>一个往 {@code device} 表写行的生产代码。
 *
 * <h2>🛑 它补的是什么：一张表的"零写入方"如何变成一个功能缺口</h2>
 * 在 B-11 之前，{@code device} 在生产代码里零写入方
 * （全仓唯一的 {@code INSERT INTO device} 也只在测试夹具里）、真库零行
 * （实测 {@code count(*) = 0}）。而：
 * <pre>
 *   device_dispatch.device_id UUID NOT NULL REFERENCES device (device_id)   [V5 L441]
 * </pre>
 * ⇒ 有写入方的那一侧（{@code FulfillmentLedger.insertDeviceDispatch}，
 * 由 D6 {@code POST /device-dispatches} 驱动，契约 {@code x-contract-row: D6}）
 * 引用的目标表<b>一行都没有</b> ⇒ 每次下发必然 {@code 23503}。
 *
 * <h2>🛑 与 B-10（{@code band}）的关键差别，以及由此产生的设计改动</h2>
 * 两者同型（零写入方表被有写入方表引用），但<b>推断目标不同</b>：
 * <table border="1">
 *   <caption>ON CONFLICT 推断目标里有没有租户维度</caption>
 *   <tr><th></th><th>B-10 {@code band}</th><th>B-11 {@code device}</th></tr>
 *   <tr><td>推断目标</td>
 *       <td>部分唯一索引 {@code uq_band_active_customer}
 *           = {@code (tenant_id, customer_id) WHERE status='active'}</td>
 *       <td>主键 {@code device_pkey} = {@code (device_id)}</td></tr>
 *   <tr><td>含租户维度？</td><td><b>含</b></td><td><b>不含</b></td></tr>
 *   <tr><td>跨租户误判可能？</td>
 *       <td><b>不可能</b>：别人的 {@code customer_id} 与别人的 {@code tenant_id}
 *           不会与我的组合冲突</td>
 *       <td><b>可能</b>：{@code device_id} 是全局的，别人占用了同一个 id 就会冲突</td></tr>
 * </table>
 * ⇒ 这正是 V18 必须补上"冲突了但我看不见 ⇒ 是别人的"这条判定的原因，
 * 也是 {@link DeviceOutcome} <b>刻意没有</b> {@code OWNED_BY_OTHER_TENANT} 一态的原因。
 * <p>🛑 一句话可迁移的教训：<b>"照抄上一个通路的实现"这件事本身是危险的 ——
 * 要看推断目标里有没有租户维度。</b>本仓把这条写进代码注释而不是只写进交付说明，
 * 因为下一个照抄的人是照抄代码，不是照抄交付说明。
 *
 * <h2>🛑 为什么它不是一个 Controller，也不该变成 Controller</h2>
 * 逐条核对了契约全部 40 个 path：没有任何 path 是"创建设备"。
 * 唯一的设备相关 path 是 D6 {@code POST /device-dispatches}，其 summary 逐字是
 * 「D6 设备参数下发（需方案已审核）」—— 操作的是<b>已存在的</b>门店设备，
 * 不是"创建设备"。给建档加对外端点属契约 <b>MAJOR</b> 变更，
 * 且要先回答契约回答不了的问题：「谁有权往门店建设备台账」。
 * ⇒ 与 B-7 / B-10 同型：这是<b>契约化决策</b>，不是遗漏。
 * 故本类被刻意做成一个<b>无 HTTP 映射的组件</b>
 * （{@code DeviceProvisioningGateTest} 会机械断言它不带任何 HTTP 注解）。
 *
 * <h2>🛑 为什么不在 Java 侧写 {@code INSERT INTO device}，而全部下沉到 V18 的函数</h2>
 * 三条理由，每条都对应一类会静默出错的东西：
 * <ol>
 *   <li><b>{@code ON CONFLICT (device_id)} 的推断语法 + 跨租户判定</b>：
 *       见上表。把"冲突了但我看不见"这条判定写在 Java 侧，意味着它要
 *       用两次 JDBC 往返拼出来 —— 而那是"先读后写"，会引入窗口。
 *       封在库层就是一条语句内部的事。</li>
 *   <li><b>"门店必须在本租户内存在"必须与写入原子</b>：
 *       分两步（先查门店、再插设备）在两次调用之间失败会留下
 *       "设备已建档、但它的门店校验发生在另一个事务里"这类不可解释的状态。
 *       函数体保证它们在同一个事务的同一段里。</li>
 *   <li><b>口径只有一处</b>：{@code BandBindingLedger} 与
 *       {@code OrganizationProvisioningRepository} 的类注释都已记录过这个教训 ——
 *       幂等写入<b>只在一处</b>发生（迁移里的函数），应用侧不再重复。
 *       两处都写会造出"口径分叉"：平时无害，一旦有人只改了其中一处，
 *       就会出现"同一个动作有两种结果"的静默不一致。</li>
 * </ol>
 *
 * <h2>🛑 为什么 {@code register_device} 的返回值必须被校验成枚举，而不能透传</h2>
 * 见 {@link DeviceOutcome#fromDb(String, String)}：未登记的值即抛错。
 * 理由与 {@code BandBindingOutcome#fromDb} 逐字同款 —— 若函数被改成别的口径，
 * 这里会先红，而不是让一个无法解释的字符串流进审计 payload。
 *
 * <h2>为什么用编程式事务而不是 {@code @Transactional} 注解</h2>
 * 与 {@code BandBindingLedger} 逐字同款：本类的每个公开写方法都只做
 * <b>一次</b>数据库函数调用，看似不需要显式事务。但那个函数内部会调
 * {@code set_config(..., is_local := true)} 并依赖它活到本次调用结束 ——
 * 而 {@code is_local} 的作用域是<b>事务</b>。
 * 若靠 JDBC 的自动提交，{@code set_config} 与随后的 {@code INSERT}
 * 会落在<b>两个不同的事务</b>里，上下文在 INSERT 时已经失效 ⇒ 被 RLS 拒。
 * 故这里显式开事务，且与 {@code BandLedger.inTenant} 同一形态。
 */
@Component
public class DeviceLedger {

    /** 设备状态枚举取值（{@code device.status} 的 CHECK 冻结值）——
     *  供读侧断言用，避免在 SQL 字面量里散落裸字符串。 */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_RETIRED = "retired";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DeviceLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ==================================================================
    // 写侧：两个 V18 原语
    // ==================================================================

    /**
     * 往门店建设备台账（幂等）。
     *
     * <p>整个动作 = V18 的 {@code register_device()} 一次调用。它内部完成：
     * 建立并自证租户上下文 → 校验门店在本租户内存在 → 单条
     * {@code INSERT ... ON CONFLICT (device_id) DO NOTHING} 原子写入 →
     * 按"冲突了但它是不是我的"判两态。
     *
     * <p>🛑 <b>会抛的两种情形</b>（都不是本方法的 bug，是设计）：
     * <ul>
     *   <li>门店不在本租户内 ⇒ 函数 RAISE（消息写明"门店 X 在租户 Y 内不存在"）；</li>
     *   <li>{@code device_id} 被<b>另一租户</b>占用 ⇒ 函数 RAISE
     *       （见 {@link DeviceOutcome} 类注释「跨租户撞号为什么必须是异常」）。</li>
     * </ul>
     * 调用方<b>不应</b>把这两条捕获后当成"再试一次"——它们需要人工确认。
     *
     * @return 两态之一（见 {@link DeviceOutcome}）；
     *         {@code ALREADY_EXISTS} <b>不是错误</b>
     */
    public DeviceOutcome register(DeviceRecord record) {
        if (record == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "设备建档：记录不可为空");
        }
        // 租户标识校验复用 BandLedger 的同名方法 —— 🛑 不另写一份白名单正则：
        // 两处口径会各自漂移（一处放宽、一处没放宽），而"哪个更严"取决于谁先跑。
        BandLedger.validateTenantId(record.tenantId());

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT register_device(?::uuid, ?::uuid, ?::uuid, ?, ?)",
                String.class,
                record.tenantId(),
                record.deviceId().toString(),
                record.storeId().toString(),
                record.model(),
                record.paramTemplateId()));

        return DeviceOutcome.fromDb(raw, "register");
    }

    /**
     * 归档一台设备（幂等）。
     *
     * <p>🛑 是<b>状态迁移</b>（{@code status → 'retired'}）而不是删除行。
     * 两条理由（见 V18 {@code retire_device()} 的注释）：
     * <ol>
     *   <li>{@code device_dispatch} 通过 {@code device_dispatch_device_id_fkey}
     *       引用本表（NOT NULL）—— 删掉一行会让历史下发记录失去可追溯对象，
     *       而 D6 的语义恰是「下行、<b>可追责</b>」；</li>
     *   <li>{@code retired} 与 {@code active} 的区分是"还在不在服役"，
     *       是<b>状态</b>而不是"存在与否"。</li>
     * </ol>
     * <p>🛑 副作用（刻意保留）：{@code retired} 行仍占据 {@code device_id} 主键
     * ⇒ 重放建档返回 {@code ALREADY_EXISTS}。
     * 这意味着<b>主键复用需要先物理删除归档行</b>，而那是一个显式的人工决定，
     * 不该被一次"顺手重建"静默完成。
     */
    public DeviceOutcome retire(String tenantId, UUID deviceId) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "归档设备：device_id 必填");
        }

        String raw = tx.execute(status -> jdbc.queryForObject(
                "SELECT retire_device(?::uuid, ?::uuid)",
                String.class,
                tenantId,
                deviceId.toString()));

        return DeviceOutcome.fromDb(raw, "retire");
    }

    // ==================================================================
    // 读侧（供运维核对与门禁自证 —— 全部走 inTenant，与业务读路径同一套上下文机制）
    // ==================================================================

    /**
     * 该设备在本租户内的状态（无则 {@code null}）。
     *
     * <p>🛑 这是 {@code register} / {@code retire} 的<b>可观测面</b>。
     * 与 {@code BandBindingLedger#findActiveBandId} 同款：走 {@code inTenant}，
     * 刻意不另造一条"因为我是运维所以不设上下文"的旁路 ——
     * 那种旁路迟早会被某个人接进一个对外接口。
     */
    public String statusOf(String tenantId, UUID deviceId) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询设备状态：device_id 必填");
        }
        return inTenant(tenantId, () -> {
            List<String> s = jdbc.query(
                    "SELECT status FROM device WHERE device_id = ?::uuid",
                    (rs, i) -> rs.getString(1),
                    deviceId.toString());
            return s.isEmpty() ? null : s.get(0);
        });
    }

    /**
     * 该门店在本租户内的设备台账摘要 ——
     * {@code [device_id, model, status, param_template_id]}。
     *
     * <p>🛑 为什么按 {@code store_id} 而不是全租户：{@code device} 是<b>门店级</b>台账
     * （{@code store_id} NOT NULL），"这个门店有哪些设备"是它唯一自然的组织方式。
     * 提供全租户列表会让"设备归属哪个门店"这个关键维度在读侧消失。
     */
    public List<String[]> devicesOfStore(String tenantId, UUID storeId) {
        BandLedger.validateTenantId(tenantId);
        if (storeId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询门店设备：store_id 必填");
        }
        return inTenant(tenantId, () -> jdbc.query(
                "SELECT device_id::text, model, status, coalesce(param_template_id, '') "
                        + "  FROM device WHERE store_id = ?::uuid ORDER BY created_at, device_id",
                (rs, i) -> new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                storeId.toString()));
    }

    /**
     * 该租户内当前 {@code active} 设备的行数。
     *
     * <p>供"建档确实生效"与"归档确实生效"两侧各有一个可断言的正整数 ——
     * 一个只报 {@code 0} 的读法无法区分"没有有效设备"与"我什么也看不见"。
     */
    public int countActiveInTenant(String tenantId) {
        BandLedger.validateTenantId(tenantId);
        return inTenant(tenantId, () -> {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM device WHERE status = '" + STATUS_ACTIVE + "'",
                    Integer.class);
            return n == null ? 0 : n;
        });
    }

    /**
     * 🛑 <b>契约 D6 端点是否已解锁</b> —— 本迁移<b>唯一</b>最终要回答的问题。
     *
     * <p>这不是一个"顺便提供的读法"，而是把本迁移的<b>验收口径</b>变成一个可断言的东西：
     * <b>"这个 device_id 能不能被 {@code device_dispatch} 引用"</b>。
     * 它必须回答的是"库层会不会接受这条引用"，而不是"我能不能查到这一行"——
     * 两者的差别恰是 {@code 23503} 与 {@code 200} 的差别。
     *
     * <p>🛑 为什么用 {@code EXISTS(... FOR KEY SHARE)} 而不是 {@code count(*)}：
     * {@code FOR KEY SHARE} 拿的正是外键检查所需的那一类行锁
     * ⇒ 它证明的是"这一行<b>可以</b>被外键引用"，
     * 而一个普通 {@code SELECT} 只证明"我读得到它"。
     * 对当前 schema 两者等价（复合外键的引用目标 {@code uq_tenant_device_device_id}
     * 与主键都在这张表上），但前者把意图写在语句里 ——
     * 读代码的人不必再推理一次"读得到是否等于引用得起"。
     *
     * <p>🛑 它不是"跨租户存在性探测口"：走 {@code inTenant}，
     * 故别的租户的设备在本读法下恒为 {@code false} —— 与 RLS 的可见性一致。
     */
    public boolean isReferenceable(String tenantId, UUID deviceId) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询设备可引用性：device_id 必填");
        }
        return inTenant(tenantId, () -> {
            Boolean e = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM device WHERE device_id = ?::uuid FOR KEY SHARE)",
                    Boolean.class,
                    deviceId.toString());
            return Boolean.TRUE.equals(e);
        });
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /**
     * 在租户上下文里执行（短事务 + {@code SET LOCAL}）。
     *
     * <p>与 {@code BandLedger.inTenant} / {@code BandBindingLedger.inTenant} /
     * {@code OrganizationProvisioningRepository.inTenant} 同款，理由逐一相同：
     * {@code SET LOCAL} 只在事务内有效，故把"设上下文 + 执行 SQL"收敛成一个短事务，
     * 使「未设租户上下文 = 零行」这条 fail-closed 性质在所有路径上都成立。
     *
     * <p>{@code validateTenantId} 放在事务<b>外</b>：非法租户 ID 不该开事务，
     * 更不该有机会把坏值拼进 SQL。
     */
    <T> T inTenant(String tenantId, Supplier<T> body) {
        BandLedger.validateTenantId(tenantId);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }
}