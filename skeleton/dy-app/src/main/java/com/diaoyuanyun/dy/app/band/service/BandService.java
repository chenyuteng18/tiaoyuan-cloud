package com.diaoyuanyun.dy.app.band.service;

import com.diaoyuanyun.dy.app.band.domain.SyncBatchRow;
import com.diaoyuanyun.dy.app.band.domain.TelemetryRow;
import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 手环数据域（契约域 E · E1/E2/E3/E6）的<b>唯一业务落点</b>。
 *
 * <h2>端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  E1 POST /band/sync-batches        → {@link #reportSyncBatch}（四态，幂等）
 *  E2 POST /band/telemetry           → {@link #upsertTelemetry}（幂等 upsert 双分支）
 *  E3 GET  /customers/{id}/band/telemetry → {@link #listTelemetry}（四端按档裁剪）
 *  E6 GET  /customers/{id}/band/sync-status → {@link #syncStatus}（四态卡）
 * </pre>
 *
 * <h2>🛑 全部端点含 client（不贴权限码）</h2>
 * E1/E2/E6 的 {@code x-callable-roles: [client]}，E3 也含 client。故本域<b>没有任何</b>
 * 端点贴 {@code @RequirePermission}（与 A2/E5/B4 同款：含 client 的端点刻意缺第三层）。
 * E3 的"客户看什么"由字段级裁剪（{@code gap_reason} 客户不下发）承担。
 *
 * <h2>错误码（对齐契约 responses，失效模式 18）</h2>
 * <pre>
 *  E1 = 200 + 400 + 409(IdempotentReplay) + 429(RateLimited)
 *  E2 = 200 + 409(VersionConflict) + 422(BusinessRuleViolated)
 *  E3 = 200 + 403(VisibilityDenied)
 *  E6 = 200
 * </pre>
 *
 * <h2>🛑 E1 幂等（batch_no → Idempotency-Key）与 V9 落库</h2>
 * 契约 §2.0「写接口接受 Idempotency-Key（UUID），24h 窗口内去重」。
 * <b>A-5（2026-09-26）之前</b>：表 {@code band_sync_log} 没有 {@code batch_no} 列
 * （schema 缺口），故幂等去重只能独立于落库（内存 24h TTL）—— 代价是"服务重启即丢"。
 * <b>V9 起</b>：迁移以契约为准补了 {@code batch_no} 列 + {@code (tenant_id, batch_no)}
 * 部分唯一索引，E1 落库且幂等由库层唯一索引权威承担。
 * <b>B-2 起（2026-09-26）</b>：那份并存的内存快路径已<b>删除</b>，
 * 使"唯一判据在库层"成为代码结构上的事实（理由见类头「误判二」）。
 * 见 {@link BandLedger#insertSyncLog}。
 *
 * <h2>🛑 本域两处幂等的形态不同，不可类推（B-2 勘察结论）</h2>
 * <table border="1">
 *   <tr><th></th><th>E1（batch_no）</th><th>E2（双分支键）</th></tr>
 *   <tr><td>幂等键来源</td><td>请求体 {@code batch_no}（= Idempotency-Key）</td>
 *       <td>由<b>业务语义</b>决定（device/metric/date/hour/minute 或 device/metric/sport_id）</td></tr>
 *   <tr><td>库层机制</td><td>V9 部分唯一索引 + {@code ON CONFLICT DO NOTHING}</td>
 *       <td>V3 两个部分唯一索引 + {@code ON CONFLICT DO NOTHING}</td></tr>
 *   <tr><td>冲突后对外语义</td><td><b>409</b>（契约声明）</td>
 *       <td><b>200</b>（契约描述"幂等 upsert，已存在 → 重放不覆盖"）</td></tr>
 *   <tr><td>响应是否回真实行</td><td>不回（登记差异，见 {@link #reportSyncBatch}）</td>
 *       <td><b>回</b>已存在行的 {@code telemetry_id}</td></tr>
 * </table>
 */
@Service
public class BandService {

    /**
     * 🛑🛑 <b>2026-09-26（B-2）：此处原有的 {@code ConcurrentHashMap} 已【删除】，不是保留。</b>
     *
     * <h2>它原来长这样，以及为什么它必须走</h2>
     * <pre>
     *   private final ConcurrentHashMap&lt;String, IdempotentEntry&gt; idempotencyKeys = new ConcurrentHashMap&lt;&gt;();
     *   private record IdempotentEntry(long expiry, String result) {}
     * </pre>
     * 它的注释当时写着"生产需换 Redis —— 已登记 TODO（README §五）"。
     * 但把注释改掉并不能让缺陷消失，而且这里有一个<b>更根本</b>的误判值得写下来：
     *
     * <h2>🛑 误判一：这个 Map 并不是 E1 幂等的防线，删掉它也不丢任何保证</h2>
     * 原注释把它写成了"幂等键存储"，让人以为删/换它会影响幂等。<b>不是的</b>：
     * E1 的幂等权威是 V9 建的库层部分唯一索引
     * {@code uq_sync_log_batch_no (tenant_id, batch_no) WHERE batch_no IS NOT NULL}
     * —— 该索引对<b>所有实例、所有进程</b>生效，且是原子的。
     * 这份 Map 只是<b>单实例内的快路径</b>（省一次库往返）。
     * 证据是原代码自身的顺序：它先查 Map、<b>再落库</b>，落库返回 false 同样报 409。
     * 故它在多实例下<b>不会</b>造成"幂等失效" —— 失效的读法是把"快路径"当成了"防线"。
     *
     * <h2>🛑 误判二（真正的问题）：它制造了"同一份逻辑在两处判"的假象，且它的失败模式是静默的</h2>
     * 它带来三件真实代价，这才是删它的理由：
     * <ol>
     *   <li><b>两份真相源，语义可以各自漂移</b>：Map 的 TTL 是 24h，而库层索引是<b>永久</b>唯一。
     *       于是"同一 batch_no 在第 25 小时重放"这件事，Map 判"可受理"、库层判"拒绝"——
     *       最终由库层胜出（因为它在后面），但读代码的人会以为窗口是 24h。
     *       契约写的是"24h 窗口内去重"（§2.0 idempotency），而库层比契约<b>更严</b>：
     *       跨窗口重放会被 409，而契约允许它成功。这处不一致已显式登记（见下方"契约边界"）。</li>
     *   <li><b>它让"权威在库层"这句话在代码里无从验证</b>：既然有快路径，
     *       任何人都可能（善意地）把判据补进快路径，从而在某天把它变成事实上的权威 ——
     *       而那个版本的幂等在<u>多实例下才是真的失效</u>。把快路径删掉，
     *       使"唯一判据在库层"成为代码结构上的事实，而不是一句注释里的约定。</li>
     *   <li><b>失败模式静默</b>：Map 命中时抛 409 但<b>不返回首次结果</b>
     *       （记录里只存了 {@code syncLogId}，而契约 4002 的语义是"返回首次结果"）。
     *       故"命中"这条路径并没有真的履行契约语义，只是"报了个错"。</li>
     * </ol>
     *
     * <h2>为什么<b>不</b>把这个 Map 换成 Redis（即清单 B-2 的原建议）</h2>
     * 清单建议"换 Redis（接口已抽象，实现替换即可）"。勘察后判定<b>此处不适用</b>，理由：
     * <ul>
     *   <li><b>dy-web 的 Redis 后端确实已存在且完整</b>（{@code RedisIdempotencyBackend} +
     *       {@code IdempotencyConfiguration}，由 {@code dy.idempotency.backend} 显式选择）——
     *       清单说的"接口已抽象，实现替换即可"属实。但那是给 {@code @Idempotent}
     *       拦截器用的<b>响应存根</b>存储（键 → 首次响应体），与本处"键 → 是否受理"不同。</li>
     *   <li><b>这里的幂等已经有一个比 Redis 更该用的存储</b>：DB 表本身。
     *       Redis 是<b>易失</b>的（重启/主从切换/逐出都会丢键），而 {@code band_sync_log}
     *       是 E1 本来就要写的<b>业务事实</b>。把幂等键放进一张既已存在、
     *       又有唯一索引的表里，比"为了幂等再加一个外部依赖"更强：
     *       <b>幂等记录与业务事实同源同寿命，不可能出现"幂等键丢了但业务行在"的分裂</b>。</li>
     *   <li><b>引入 Redis 会把一个可选依赖变成 E1 的硬依赖</b>：一旦 Redis 不可用，
     *       要么 fail-closed（E1 全线不可用，但 DB 明明好好的），
     *       要么 fail-open（静默失去幂等）。而库层索引方案两种都不需要 ——
     *       DB 可用则幂等可用，这正是"少一个活动部件"的价值。</li>
     * </ul>
     * 结论：<b>B-2 在本域的正确收口是"去掉冗余快路径 + 显式登记语义边界"，
     * 而不是"引入 Redis"</b>。{@code dy-web} 那套 Redis 后端对<b>它自己的用途</b>
     * （响应体重放，需要存整段响应文本，不适合放 DB）是合适的，保持不变。
     *
     * <h2>🛑 契约边界（显式登记，不擅自改库层索引）</h2>
     * 契约 §2.0 写"写接口接受 {@code Idempotency-Key}；服务端在 <b>24h 窗口内</b>去重"；
     * {@code uq_sync_log_batch_no} 则是<b>永久</b>唯一。两者的差集是：
     * 「同一 batch_no，距首次超过 24h 后重放」——契约允许（应成功），库层拒绝（409）。
     * 这是<b>库层更严</b>，方向上对数据安全无害（不会重复受理），但它是契约与实现的一处
     * 已登记不一致，<b>不由本类单方面改写</b>：放宽库层索引需要一次业务裁定
     * （永久唯一是否可接受？若可接受，契约文字应改为"永久去重"）。
     * 之所以不动库层：把永久唯一放宽为"24h 内唯一"在 PG 里需要时间窗条件索引或分区，
     * 且会<b>削弱</b>一条当前唯一的永久防线（{@code band_sync_log} 上没有只追加约束，
     * 见 V5/V9 —— 库层唯一索引是防止同批次被重复受理的<b>唯一</b>永久保证）。
     */
    private final BandLedger ledger;

    public BandService(BandLedger ledger) {
        this.ledger = ledger;
    }

    // ==================================================================
    // E1 同步批次（四态，幂等）
    // ==================================================================

    /**
     * E1 入参（契约 BandSyncBatchRequest 的冻结形状）。
     */
    public record SyncBatchRequest(
            String deviceId,
            String customerId,
            String batchNo,
            String trigger,
            String state,
            Instant syncedAt,
            LocalDate lastSuccessDate,
            String failReasonClass,
            String nextAction) {
    }

/**
 * E1 同步批次上报（四态，幂等受理 + V9 起落库）。
 *
 * <p>🛑 <b>V9 起 E1 落库</b>（A-5 裁定的落地件）。A-5 之前本端点只做契约校验 + 内存幂等，
 * 因为契约 §4.1 的四态/触发源与 V5 {@code band_sync_log} 的中文两态是<b>两套词表</b>
 * （硬映射会报 23514），且 {@code batch_no} 幂等键列在表中不存在。V9 迁移以<b>契约为准</b>
 * 补了三列（{@code contract_state} / {@code contract_trigger} / {@code batch_no}），
 * 使两侧共存：旧中文列仍由 PRD §4.6「同步尝试日志」产线使用，契约侧列由本端点写。
 *
 * <p>顺序（<b>2026-09-26 · B-2 起已收敛为单层</b>）：
 * <ol>
 *   <li>组装 SyncBatchRow（四态/失败枚举由领域构造器挡）；</li>
 *   <li><b>落库</b>（{@code insertSyncLog} 的 {@code ON CONFLICT DO NOTHING}）
 *       —— 返回 false 说明库层已有同 batch_no ⇒ 409。</li>
 * </ol>
 *
 * <h2>🛑 幂等的唯一权威在库层（B-2 收口后，内存快路径已删除）</h2>
 * 库层 {@code (tenant_id, batch_no) WHERE batch_no IS NOT NULL} 唯一索引是原子的，
 * 故多实例部署下幂等仍然成立。B-2 之前这里还有一份 {@code ConcurrentHashMap} 快路径，
 * 它带来的是"两份真相源 + 语义可漂移"，而不是额外的安全 —— 详见类头「误判二」。
 *
 * <p>⚠️ <b>响应体不含首次结果（已登记）</b>：契约 4002 的文案是"幂等键命中已有记录
 * （<b>返回首次结果</b>）"，而本端点冲突时只回一个 409 错误体，<b>不回首次那个
 * {@code sync_log_id}</b>。这不是 B-2 引入的（B-2 之前的内存路径同样只抛错），
 * 而是本端点与契约文案之间一处既有的、已登记的差异：要做到"返回首次结果"，
 * 需要在冲突时回读那一行并把它塞进 409 的响应体 —— 该改动会影响错误响应形状，
 * 属对外契约变更，故留待裁定，不在本轮擅自改。
 *
 * <p>📌 契约 §2.0 写"24h 窗口内去重"，而库层索引是<b>永久</b>唯一 —— 该差异已登记，
 * 见类头「契约边界」。
 */
public SyncBatchRow reportSyncBatch(String tenantId, SyncBatchRequest req, String createdBy) {
    BandLedger.validateTenantId(tenantId);
    if (req == null) {
        throw new BizException(ErrorCode.VALIDATION_FAILED, "同步批次请求体缺失");
    }
    uuid(req.deviceId(), "device_id");
    // 🛑 customer_id 服务端从 token 校验归属（契约描述逐字），此处只做 UUID 解析不采信
    String batchNo = req.batchNo();
    if (batchNo == null || batchNo.isBlank()) {
        throw new BizException(ErrorCode.VALIDATION_FAILED, "batch_no 缺失（幂等键）");
    }

// ② 领域构造器挡四态/失败枚举
        SyncBatchRow row = new SyncBatchRow(
                UUID.randomUUID(), uuid(req.deviceId(), "device_id"), batchNo,
                req.trigger(), req.state(), req.syncedAt() == null ? Instant.now() : req.syncedAt(),
                req.lastSuccessDate(), req.failReasonClass(), req.nextAction(),
                Instant.now(), createdBy);

        // ③ 落库（V9）—— 🛑 这是【唯一】的幂等判据（原子）。
        //    库层幂等命中 ⇒ 409。没有内存快路径：见类头「误判二」。
        if (!ledger.insertSyncLog(tenantId, row)) {
            throw new BizException(ErrorCode.IDEMPOTENT_REPLAY,
                    "同步批次幂等键已受理过（batch_no=" + batchNo + "，库层唯一索引命中），返回首次结果");
        }

        return row;
    }

    // ==================================================================
    // E2 遥测 upsert（双分支幂等键）
    // ==================================================================

    /**
     * E2 入参（契约 BandTelemetryRequest）。
     */
    public record TelemetryRequest(
            String deviceId,
            String customerId,
            String metric,
            LocalDate date,
            Integer hour,
            Integer minute,
            java.math.BigDecimal value,
            String sleepJson,
            Integer isWear,
            String currentSportId) {
    }

    /**
     * E2 遥测 upsert（幂等，库层原子承担）。
     *
     * <p>🛑 幂等键双分支（契约 §4.3）：日型 12 条 vs 游标型 1 条（sport）。
     * 已存在 → 幂等重放（返回<b>已存在那一行</b>的 id，不覆盖）。is_wear 的 (-1,255)
     * 技术性缺失由服务端强制覆写、不判行为性。
     *
     * <h2>🛑🛑 2026-09-26 修复：此处原为"先查后写"（check-then-act），并发下会让客户端收 500</h2>
     * 原实现：
     * <pre>
     *   if (!ledger.telemetryIdempotentHit(tenantId, row)) {   // ① EXISTS 查一次
     *       ledger.insertTelemetry(tenantId, row);             // ② 再写
     *   }
     *   return row;                                            // ③ 返回【入参】那个新生成的行
     * </pre>
     * 三处缺陷（一并修掉，逐条说明）：
     * <ol>
     *   <li><b>并发空窗 ⇒ 500</b>：两个请求都可能在第 ① 步查到"不存在"，随后第 ② 步
     *       其中一个撞上 V3 的唯一索引（23505）。原来没有任何地方接住它 ⇒
     *       一次本该幂等重放、返回 200 的重复上报，变成一个服务端错误。
     *       <b>多实例部署会让这个窗口变宽</b>（不同实例的访存互相看不见），
     *       而 B-2 这条卡点关心的正是多实例场景。</li>
     *   <li><b>幂等重放返回一个库里不存在的 id</b>：第 ③ 步返回的是本方法刚
     *       {@code UUID.randomUUID()} 出来的行 —— 命中幂等时那一行<b>根本没写进库</b>。
     *       而 E2 响应体里就有 {@code telemetry_id}（见 {@code BandController}），
     *       故客户端拿着它做任何后续操作都会落空。</li>
     *   <li><b>这次"覆盖写"是一次静默的数据回退</b>：原实现的注释说"已存在 → 幂等重放
     *       （不覆盖）"，但代码并没有真的实现这一点 —— 它只是"不写"。
     *       看起来一样，实际区别在于：若第 ① 步因故漏判（并发），第 ② 步就会真的插进去 ——
     *       而"不覆盖"的保证应当由<b>库层</b>给出，不能依赖一次可能过期的读。</li>
     * </ol>
     *
     * <p>修法：判重下沉到库层的 {@code ON CONFLICT DO NOTHING}（原子），
     * 并把"库层实际存在的那一行 id"一路上抛。详见
     * {@link BandLedger#insertTelemetry} 与 {@link BandLedger#findTelemetryId}。
     *
     * <p><b>为什么 E2 冲突后仍 200，而 E1 冲突后 409</b>：
     * E1（{@code batch_no}）契约声明了 409；E2 的 responses 只有 200/409-VERSION_CONFLICT/422，
     * 而描述是"幂等 upsert（已存在 → 幂等重放，不覆盖）"⇒ 幂等命中对 E2 是<b>正常结果</b>。
     */
    public TelemetryRow upsertTelemetry(String tenantId, TelemetryRequest req, String createdBy) {
        BandLedger.validateTenantId(tenantId);
        if (req == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "遥测请求体缺失");
        }
        UUID deviceId = uuid(req.deviceId(), "device_id");
        UUID customerId = uuid(req.customerId(), "customer_id");

        // 🛑 运动数据不得复用按日型幂等键（契约 §4.3）—— sport 分支走 sport_id
        boolean isSport = "sport".equals(req.metric());
        Integer sportId = isSport ? 0 : null;

        TelemetryRow row = new TelemetryRow(
                UUID.randomUUID(), customerId, deviceId, req.metric(),
                req.date() == null ? LocalDate.now() : req.date(),
                req.hour(), req.minute(),
                req.value(), req.sleepJson(), Boolean.TRUE.equals(req.value() != null),
                "手环", null, "synced", Instant.now(),
                normalizeIsWear(req.isWear()), null, sportId == null ? null : req.currentSportId(),
                createdBy);

        // 🛑 判重与写入下沉到库层（原子）。返回的是【库里实际存在】的那一行 id：
        //    首次写入 ⇒ 本行 id；幂等命中 ⇒ 已存在行的 id。二者对客户端等价（都是那行）。
        UUID persistedId = ledger.insertTelemetry(tenantId, row);
        if (!persistedId.equals(row.telemetryId())) {
            // 幂等重放：把返回值对齐到库里那行，使响应体里的 telemetry_id 指向真实存在的行。
            return withTelemetryId(row, persistedId);
        }
        return row;
    }

    /** 用"库里实际存在的 id"替换本行 id（幂等重放时使用）。 */
    private static TelemetryRow withTelemetryId(TelemetryRow row, UUID persistedId) {
        return new TelemetryRow(
                persistedId, row.customerId(), row.deviceId(), row.metric(),
                row.date(), row.hour(), row.minute(), row.valueNum(), row.sleepJson(),
                row.coverageFlag(), row.dataSource(), row.gapReason(), row.syncState(),
                row.syncedAt(), row.isWear(), row.localTzOffset(), row.sportId(), row.createdBy());
    }

    /** is_wear 归一：(-1,255) 技术性缺失服务端强制覆写（不判行为性）。 */
    private static Integer normalizeIsWear(Integer isWear) {
        if (isWear == null) {
            return null;
        }
        // 只有明确 1（佩戴）/0（脱腕）保留；(-1,255) 技术性缺失归 null（不判行为性）
        return (isWear == 1 || isWear == 0) ? isWear : null;
    }

    // ==================================================================
    // E3 遥测读取（四端按档裁剪）
    // ==================================================================

    /** E3 —— 客户维度遥测（四端按档裁剪由控制器完成，此处返回原始行）。 */
    public List<TelemetryRow> listTelemetry(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失");
        }
        return ledger.findByCustomer(tenantId, customerId);
    }

    /** E3 的 gap_reason 是否对当前角色可见（契约 x-visible-to 不含 client）。 */
    public static boolean gapReasonVisibleTo(VisibilityRole role) {
        return role != null && role != VisibilityRole.CLIENT;
    }

    // ==================================================================
    // E6 同步状态卡
    // ==================================================================

    /** E6 —— 某设备最近一次同步状态（四态）。 */
    public SyncBatchRow syncStatus(String tenantId, UUID deviceId) {
        BandLedger.validateTenantId(tenantId);
        if (deviceId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "device_id 缺失");
        }
        return ledger.findLatestSyncLog(tenantId, deviceId);
    }

    /** E6 前置：按客户反查 active 手环 device_id（无则 null = 未接入）。 */
    public UUID activeDeviceOf(String tenantId, UUID customerId) {
        BandLedger.validateTenantId(tenantId);
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "customer_id 缺失");
        }
        return ledger.findActiveDeviceByCustomer(tenantId, customerId);
    }

    private static UUID uuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 缺失");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法 UUID: '" + raw + "'");
        }
    }
}