package com.diaoyuanyun.dy.app.customer.service;

import com.diaoyuanyun.dy.app.customer.domain.BandWillingness;
import com.diaoyuanyun.dy.app.customer.domain.ConsentAuthScope;
import com.diaoyuanyun.dy.app.customer.domain.ConsentDataSource;
import com.diaoyuanyun.dy.app.customer.domain.ConsentRow;
import com.diaoyuanyun.dy.app.customer.domain.ContraindicationPolicy;
import com.diaoyuanyun.dy.app.customer.domain.CustomerArchive;
import com.diaoyuanyun.dy.app.customer.domain.CustomerDetailView;
import com.diaoyuanyun.dy.app.customer.domain.CustomerFieldVisibility;
import com.diaoyuanyun.dy.app.customer.domain.CustomerGateGuard;
import com.diaoyuanyun.dy.app.customer.domain.CustomerState;
import com.diaoyuanyun.dy.app.customer.domain.CustomerStateMachine;
import com.diaoyuanyun.dy.app.customer.domain.CustomerStatus;
import com.diaoyuanyun.dy.app.customer.domain.CustomerUpsertDraft;
import com.diaoyuanyun.dy.app.customer.domain.Gender;
import com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRevisionRow;
import com.diaoyuanyun.dy.app.customer.domain.IntakeProfileRow;
import com.diaoyuanyun.dy.app.customer.domain.ScreeningRecordRow;
import com.diaoyuanyun.dy.app.customer.domain.ScreeningResult;
import com.diaoyuanyun.dy.app.customer.domain.StateTransitionDraft;
import com.diaoyuanyun.dy.app.customer.repository.ConsentLedger;
import com.diaoyuanyun.dy.app.customer.repository.CustomerLedger;
import com.diaoyuanyun.dy.app.customer.repository.IntakeProfileLedger;
import com.diaoyuanyun.dy.app.customer.repository.IntakeProfileRevisionLedger;
import com.diaoyuanyun.dy.app.customer.repository.ScreeningLedger;
import com.diaoyuanyun.dy.app.identity.domain.StoreAnchor;
import com.diaoyuanyun.dy.app.identity.repository.StoreRepository;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.visibility.VisibilityRole;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户与档案域（契约域 B · B1~B6）的<b>唯一业务落点</b>。
 *
 * <h2>一、六行端点与本类方法的对应（契约逐行）</h2>
 * <pre>
 *  B1 POST /screening-records                → {@link #submitScreening}
 *  B2 POST /customers                        → {@link #createCustomer}
 *  B3 POST /customers/{id}/consents          → {@link #signConsent}
 *  B4 GET  /customers/{id}                   → {@link #getCustomer}
 *  B5 GET  /customers/{id}/intake-profile    → {@link #getIntakeProfile}
 *  B6 PATCH /customers/{id}/intake-profile   → {@link #patchIntakeProfile}
 * </pre>
 *
 * <h2>二、🛑 错误码严格按各端点 <b>responses 声明集</b>取值（失效模式 18）</h2>
 * <pre>
 *  B1 = 200 + 400                      ⇒ 只允许 1001；【不得】出现 403/404/5001
 *  B2 = 200 + 403(GateMissing)         ⇒ 只允许 2002（GATE_MISSING）；【不得】出现 404
 *  B3 = 200 + 403(GateMissing)         ⇒ 只允许 2002
 *  B4 = 200 + 403(VisibilityDenied) + 404(NotFound)  ⇒ 2001 / 3001
 *  B5 = 200 仅                        ⇒ 【不得】出现 403/404 —— "没有档案"是 200 + 空 data
 *  B6 = 200 仅                        ⇒ 【不得】出现 403/404
 * </pre>
 * 这不是"实现偏好"：响应集是<b>契约冻结件</b>，客户端按它写分支。
 * 报一个未声明的码会让客户端落进未定义分支 —— 而它看起来只是"多了一条错误路径"。
 * 故本类在每一处"该报什么码"的判断上，都回引对应行。
 *
 * <h2>三、🛑 B5/B6 只声明 200 —— 由此推出两条<b>不直观</b>的实现约束</h2>
 * <ol>
 *   <li><b>B5 不能对"客户不存在"报 404</b>：它只声明 200。故"查无此客户"与
 *       "此客户尚未建档"在 B5 里<b>必须</b>都是 {@code 200 + 空 data}。
 *       两者的区别对调用方无意义（都是"没有档案可看"），而对客户端是
 *       "多一个未声明分支"与"不用处理"的差别。</li>
 *   <li><b>B6 不能对"未建档"报 403</b>：它只声明 200。故 B6 允许
 *       <b>首次写入即建档</b>（{@code revision_no = 1}）。这与 B2 的门禁<b>不冲突</b>：
 *       B2 的门禁（筛查通过 + 未 REJECTED）约束的是"正式建档"这个动作，
 *       而 B6 是"补充扩展档案"—— 后者由 B3 的门禁链在<b>域 B 之外</b>约束，
 *       且上游 V5 附注只要求"与 screening_record 属同一次提交链"，未要求 B6 报 403。</li>
 * </ol>
 *
 * <h2>四、🛑 顺序纪律（三处，每一处错了都会静默）</h2>
 * <pre>
 *  ① B1：先推导结论（ContraindicationPolicy）→ 再落库 → 结论为不通过时置 REJECTED
 *     结论【必须】服务端推导，契约 ScreeningCreateRequest.required 里没有 result
 *  ② B4：先 assertIncludeAllowed（403 归因）→ 再取数 → 再裁剪 toContractData
 *     顺序反了的话，一次越权请求会先被取数、后被拒 —— 数据已进内存
 *  ③ B2：先查筛查（hasPassing / 当前态）→ 再 assertScreeningResult → 再写入
 *     🛑 【不】调 assertAdmissionChain：它内含 assertProfiled，而那是一条【自环守卫】
 *     （B2 的产出就是"建档"，故调用它时必然尚未 PROFILED ⇒ B2 恒 403），
 *     且契约 B2 只要求 screening_result=通过。详见 createCustomer 内的注释。
 *  ④ B3：先 assertProfiled（G1）→ 再写入（这里才是 G1 的正确受 guard 端点）
 *     gate 必须在写入之前，否则"被拒的建档"会留下半条记录
 * </pre>
 *
 * <h2>五、🛑 客户不下发字段 —— 本类不承担那道防线</h2>
 * {@code owner_store_id} / {@code serving_store_id} 对客户不可见，
 * 但本类<b>不</b>用手写 if 去裁它们：裁剪唯一落点是
 * {@link CustomerDetailView#toContractData}，403 归因唯一落点是
 * {@link CustomerFieldVisibility#assertIncludeAllowed}。
 * 本类只负责<b>按正确顺序调用这两者</b>。
 *
 * <h2>六、🛑 权益边界（与退款域 / 判定域同源）</h2>
 * 六行端点的 {@code x-callable-roles} 里，B1/B2/B3/B6 不含 client 而 B4/B5 含 client。
 * 前四者的"客户不可调"由 {@link #requireStaffCallable} 承担（本类实现）；
 * 后两者的裁剪由 {@link CustomerFieldVisibility} 承担。<b>两者不可互相替代</b>：
 * 前者是"这个动作你能不做"，后者是"这个字段你能不看"。
 *
 * <h2>七、🛑 为什么本类用 {@code @Service} 自注册，而 A2 的 {@code AuthMeService} 要显式装配</h2>
 * 两者的差别在于<b>依赖里有没有"需要构造期校验的口径对象"</b>：
 * <pre>
 *   AuthMeService    ← 消费 BandVisibilityMatrix / RefundVisibilityMatrix
 *                      两份矩阵的构造期即做硬锁校验，装配顺序与"共享同一份实例"
 *                      是刻意的 —— 故走 IdentityDomainConfig 显式 @Bean
 *   CustomerService  ← 消费六本账本（@Repository 自注册）、
 *                      ConfigSeedContraindicationSource（@Component 自注册，
 *                      构造期已解析并缓存 ContraindicationPolicy）、
 *                      StoreRepository（@Repository 自注册）
 * </pre>
 * 本类的七个依赖<b>全部</b>自注册，且它们的构造期校验各自在自己的类里完成
 * （{@code ConfigSeedContraindicationSource} 构造期读不到声明行即抛）。
 * 故这里再加一个显式 {@code @Bean} 只是把"生成一个对象"这件事写第二遍，
 * 且会给出一条诱人的路径：{@code new CustomerService(...)} 手工传参 ——
 * 而那条路径会绕过 {@code @Component} 的构造期解析，让"清单读不到"从构建期推迟到运行期。
 * 🛑 用注解不要用 {@code new}：让 Spring 成为唯一装配者，才有那一次构造期失败。
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    /** B1/B2/B3/B6 的可调用角色（契约逐行；admin 展开后由 {@link VisibilityRole} 承担）。 */
    private static final Set<VisibilityRole> STAFF_CALLABLE = Set.of(
            VisibilityRole.THERAPIST, VisibilityRole.MERIDIAN, VisibilityRole.ADMIN);

    /** B4/B5 的可调用角色（契约逐行；含 client）。 */
    private static final Set<VisibilityRole> DETAIL_CALLABLE = Set.of(
            VisibilityRole.CLIENT, VisibilityRole.THERAPIST,
            VisibilityRole.MERIDIAN, VisibilityRole.ADMIN);

    private final ScreeningLedger screenings;
    private final ConsentLedger consents;
    private final CustomerLedger customers;
    private final IntakeProfileLedger profiles;
    private final IntakeProfileRevisionLedger revisions;
    private final ConfigSeedContraindicationSource contraindications;
    private final StoreRepository stores;

    public CustomerService(ScreeningLedger screenings,
                           ConsentLedger consents,
                           CustomerLedger customers,
                           IntakeProfileLedger profiles,
                           IntakeProfileRevisionLedger revisions,
                           ConfigSeedContraindicationSource contraindications,
                           StoreRepository stores) {
        this.screenings = screenings;
        this.consents = consents;
        this.customers = customers;
        this.profiles = profiles;
        this.revisions = revisions;
        this.contraindications = contraindications;
        this.stores = stores;
    }

    // ==================================================================
    // B1 · POST /screening-records
    // ==================================================================

    /**
     * B1 禁忌筛查提交（硬门禁①）。
     *
     * <h2>🛑 本方法的四步顺序不可换</h2>
     * <pre>
     *  ① 取 tenant / 校验角色（契约 x-callable-roles: [therapist, meridian, admin]）
     *  ② 【服务端推导】result = ContraindicationPolicy.derive(items_json)
     *  ③ 落库（append-only；operator_id 从 token 覆写）
     *  ④ result = 不通过 ⇒ 客户状态置 REJECTED（同一条链的副作用）
     * </pre>
     * 第 ② 步是"P0-01 要求系统级硬阻断"的实现落点：契约
     * {@code ScreeningCreateRequest.required = [customer_id, items_json, operator_id]}
     * <b>没有 result</b>。若采信请求体里的同名字段，硬门禁就退化为客户端自愿申报。
     *
     * <h2>🛑 {@code operator_id} 从 token 覆写，请求体里的同名值被丢弃</h2>
     * 契约该字段的 description 逐字：「<b>服务端从 token 覆写</b>」。
     * 故本方法<b>只</b>取 token 里的 staffId，请求体里的 {@code operator_id} 一概不读 ——
     * 不是"优先用 token"，而是"那一列在这个端点上根本不被采信"。
     *
     * <h2>🛑 错误码边界（B1 = 200 + 400，无 403 / 无 500）</h2>
     * 故本方法<b>不</b>校验"客户是否存在"（那会需要一个 404，而 B1 没有它）；
     * 也不因清单未配置而抛（那会需要一个 500）。清单缺口由
     * {@link #describeContraindicationPolicy} 显式登记。
     *
     * @param customerId 契约 {@code ScreeningCreateRequest.customer_id}（必填）
     * @param itemsJson  契约 {@code items_json}（必填；结构化多选）
     * @return 契约 {@code ScreeningData}（screening_id / result / submitted_at）
     */
    public Map<String, Object> submitScreening(String customerId, Map<String, Object> itemsJson) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        requireCallable(tokenRole, STAFF_CALLABLE, "B1 POST /screening-records");

        UUID custId = parseUuid(customerId, "customer_id",
                "B1 POST /screening-records（契约 ScreeningCreateRequest.required 含 customer_id）");
        if (itemsJson == null || itemsJson.isEmpty()) {
            // B1 唯一可报的错误码是 400（1001）—— items_json 是 required 且是推导输入
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B1 缺 items_json（或为空对象）—— 契约 ScreeningCreateRequest.required 含它，"
                            + "且它是禁忌结论的【唯一推导输入】。"
                            + "🛑 空对象与 null 是两件事，但两者在这里都不可接受："
                            + "契约把 items_json 列为 required，一次空填答不是『模块记为无』"
                            + "而是『这份筛查没有内容』—— 而 B1 的硬门禁结论正是从它推出的");
        }

        // ② 服务端推导（P0-01 硬门禁的唯一落点）
        ContraindicationPolicy policy = contraindications.policy();
        List<String> hitKeys = policy.hitKeys(itemsJson);
        ScreeningResult derived = hitKeys.isEmpty() ? ScreeningResult.PASSED : ScreeningResult.REJECTED;
        if (!policy.configured()) {
            // 🛑 不抛（B1 无 500 可报）—— 但必须留下可被发现的痕迹
            log.warn("B1 禁忌筛查：禁忌清单【未配置】（{}）⇒ 推导结论恒为通过，REJECTED 终态不会发生。"
                            + "这是安全级配置缺口，须总部维护 config #8 的 items。",
                    contraindications.describeSource());
        }

        // ③ 落库（append-only）
        UUID screeningId = UUID.randomUUID();
        UUID operatorId = requireStaffId();
        Instant now = Instant.now();
        ScreeningRecordRow row = new ScreeningRecordRow(screeningId, custId, itemsJson,
                derived, operatorId, now, String.valueOf(operatorId));
        screenings.append(tenantId, row);

        // ④ 命中禁忌 ⇒ 客户状态置 REJECTED（契约 B1 description 逐字）
        if (derived == ScreeningResult.REJECTED) {
            CustomerState current = customers.findCurrentState(tenantId, custId);
            CustomerState from = current == null ? CustomerState.SCREENING : current;
            // 🛑 只写"当前态 → REJECTED"：
            //    上游跃迁表里 REJECTED 的入边来自 SCREENING（§2.25 ①）。
            //    若客户当前已越过 SCREENING（历史数据 / 复筛），这条边不存在 ⇒
            //    用 appendTransition 会触发 CustomerStateMachine 的 5001。
            //    而 B1 无 500 可报 —— 故此处只在来源合法时写跃迁，否则记 WARN。
            if (CustomerStateMachine.isAllowedTransition(from, CustomerState.REJECTED)) {
                StateTransitionDraft t = StateTransitionDraft.passed(
                        UUID.randomUUID(), from, CustomerState.REJECTED,
                        "B1:screening_contraindication_hit", operatorId, now,
                        "screening_record", screeningId.toString(), String.valueOf(operatorId));
                customers.appendTransition(tenantId, custId, t);
                customers.updateStatus(tenantId, custId, CustomerStatus.REJECTED);
                log.warn("B1 筛查命中禁忌，客户 {} 已置 REJECTED（命中项 {} 项）——"
                                + "后续 B2/B3 入口将 403 GATE_MISSING(missing_items=[screening_result])",
                        custId, hitKeys.size());
            } else {
                // 不可达的跃迁：不写（B1 无 500 可报），但必须留痕使该情形可被查到
                log.warn("B1 筛查命中禁忌，但客户 {} 当前态为 {} —— 上游跃迁表没有 {} → REJECTED 这条边，"
                                + "故本次【未】改状态。该情形需人工核对：客户可能已被建档或已终态，"
                                + "而『命中禁忌却处于服务中』本身是需要复盘的事实",
                        custId, from == null ? "<null>" : from.code(), from == null ? "<null>" : from.code());
            }
        }

        return row.toContractData();
    }

    // ==================================================================
    // B2 · POST /customers
    // ==================================================================

    /**
     * B2 建档（{@code POST /customers}）。
     *
     * <h2>🛑 门禁在写入之前（顺序纪律 ③）</h2>
     * 契约 B2 description 逐字：「需 {@code screening_result=通过}」。
     * 判据经 {@link CustomerGateGuard#assertAdmissionChain}，其中"筛查通过"的输入
     * 必须<b>查库所得</b>（{@link ScreeningLedger#hasPassing}）——不由状态推断，
     * 因为 14 态里没有"筛查通过"态（{@code SCREENING} 表示准入未完成）。
     *
     * <h2>🛑 {@code customer_id} 由 {@code screening_id} 反查</h2>
     * 契约 B2 的 {@code required} 是 {@code [name, gender, age, phone, screening_id]} ——
     * <b>没有 customer_id</b>，而出参里却有 {@code customer_id}。
     * 在"建键 → B1 → B2"这条链上，客户标识只能由 {@code screening_id} 经
     * {@code screening_record.customer_id} 得到（{@link CustomerLedger#findCustomerIdByScreening}）。
     *
     * <h2>🛑 错误码边界（B2 = 200 + 403 GateMissing，<b>无 404</b>）</h2>
     * 故"debug 该 screening_id 不存在"⇒ 门禁的 {@code screening_result} 缺失
     * ⇒ 403（{@code missing_items=["screening_result"]}），而<b>不是</b> 404。
     * 这与 B4 的 404 处置刻意不同：B4 声明了 404，B2 没有。
     *
     * @return 契约 {@code CustomerCreateData}（customer_id / status / 两个门店）
     */
    public Map<String, Object> createCustomer(CustomerArchive archive, UUID ownerStoreId,
                                              UUID servingStoreId) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        requireCallable(tokenRole, STAFF_CALLABLE, "B2 POST /customers");

        if (archive == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B2 缺请求体 —— 契约 requestBody.required = true（CustomerCreateRequest）");
        }

        // 由 screening_id 反查客户（B2 没有 customer_id 入参）
        UUID screeningId = archive.screeningId();
        UUID custId = customers.findCustomerIdByScreening(tenantId, screeningId);
        if (custId == null) {
            // 🛑 B2 无 404：归入门禁（缺 screening_result），不是"资源不存在"
            throw new com.diaoyuanyun.dy.common.exception.GateMissingException(
                    List.of(CustomerGateGuard.GateRequirement.SCREENING_RESULT.missingItemLiteral()),
                    "[B2 POST /customers] screening_id=" + screeningId
                            + " 在本租户内查不到对应客户 —— 无法确定这份筛查记录属于谁。"
                            + "🛑 此处报 403 GATE_MISSING(screening_result) 而非 404："
                            + "契约 B2 的 responses 声明集 = 200 + 403(GateMissing)，【无 404】。"
                            + "一次『资源不存在』的 404 会让客户端落进未声明分支");
        }

        // 门禁（顺序纪律 ③：写入之前）
        //
        // 🔴 2026-09-25（S2-10 真请求 E2E 抓出）—— 此处原先调用 assertAdmissionChain，
        //    它内含 ① assertScreeningResult + ② assertProfiled。而 ② 把"尚未建档"读成门禁缺失
        //    ⇒ B2【自身】【恒 403】（B2 的产出正是"建档"，故调用它时客户必然尚未 PROFILED）。
        //    这是一个【自环门禁】：守卫挂在了它自己的产出端点上，端点 100% 不可用，
        //    且报出的缺失项（PROFILED）与真实成因完全相反 —— 排查者会去"补建档"，
        //    而正确动作是"这次调用本来就该成功"。
        //
        // 🛑 B2 只受【第一级】约束：契约 B2 description 逐字「需 screening_result=通过」——
        //    没有"已建档"这一条（建档是 B2 的结果，不是它的前置）。
        //    data-dict §2.25 ③ 的 G1（未 PROFILED 不得签同意书）受 guard 端点是 B3，不是 B2。
        boolean hasPassing = screenings.hasPassing(tenantId, custId);
        CustomerState current = customers.findCurrentState(tenantId, custId);
        CustomerGateGuard.assertScreeningResult("B2 POST /customers", hasPassing, current);

        // 归属门店：owner 必填且不得由客户端提供（服务端从 token 锚点解出）
        UUID resolvedOwner = resolveOwnerStoreId(tenantId, ownerStoreId, "B2 POST /customers");

        Gender gender = archive.gender();
        CustomerStatus status = CustomerStatus.PROFILED;
        UUID transitionId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID operatorId = requireStaffId();

        CustomerUpsertDraft draft = new CustomerUpsertDraft(
                custId, archive.name(), gender, archive.age(), archive.phone(),
                screeningId, status, resolvedOwner, servingStoreId, String.valueOf(operatorId));

        int updated = customers.promoteToProfiled(tenantId, draft,
                current == null ? CustomerState.SCREENING : current,
                transitionId, "B2:profile_created", now, operatorId, String.valueOf(operatorId));
        if (updated == 0) {
            // 键不存在（B1 之前未建键）—— 依类注释第一节的依赖链，这是"链断了"。
            // 🛑 B2 无 404：以 5001 明确失败会引入未声明的码……但此处【必须】失败，
            //    因为静默成功会让调用方以为建档完成而实际没有任何行。
            //    处置：走 403 GATE_MISSING（把成因归到"准入链未完成"），
            //    它是 B2 声明集内的码，且"建键动作缺位"正是一条准入链缺口。
            throw new com.diaoyuanyun.dy.common.exception.GateMissingException(
                    List.of(CustomerGateGuard.GateRequirement.SCREENING_RESULT.missingItemLiteral()),
                    "[B2 POST /customers] 客户键 " + custId + " 在租户内不存在，建档 UPDATE 命中 0 行 —— "
                            + "准入链的『建键』环节缺位（见 CustomerLedger.describeKeyCreationGap）。"
                            + "🛑 报 403 GATE_MISSING 而非 404/5001：契约 B2 的声明集是 200 + 403，"
                            + "而『建键缺位』正是一条准入链缺口（缺 screening_record 挂载的锚点）");
        }

        return contractCustomerCreateData(custId, status, resolvedOwner, servingStoreId);
    }

    // ==================================================================
    // B3 · POST /customers/{id}/consents
    // ==================================================================

    /**
     * B3 签知情同意书（门禁 G1：未建档不得签）。
     *
     * <h2>🛑 契约 B3 <b>没有</b> requestBody，而 consent 表有四列 NOT NULL</h2>
     * 契约只有 path 参数；而 {@code auth_scope_json} / {@code band_willingness} /
     * {@code signed_at} / {@code evidence_hash} 四列均 NOT NULL。
     * 本方法<b>忠实承载</b>四个入参并强校验非空，
     * <b>不发明</b>它们的来源 —— 来源属"取参策略"，且那是一条<b>已登记的缺口</b>
     * （见 {@link #describeSignConsentGap()}）。
     *
     * <h2>🛑 {@code band_willingness} 不参与任何门禁判定</h2>
     * V5 附注逐字：「🛑 <b>拒戴不得降级服务</b>」。
     * 故 {@link BandWillingness#DECLINED} 与 {@link BandWillingness#WILLING}
     * 在本方法里走<b>完全相同</b>的路径 —— 没有任何分支读它。
     * 这一点值得在代码里"什么都不做"地体现：一旦有人在这里加一个 if，
     * 那条 if 就是"拒戴降级"的实现。
     */
    public Map<String, Object> signConsent(String customerId,
                                           Set<ConsentAuthScope> authScope,
                                           BandWillingness bandWillingness,
                                           String evidenceHash,
                                           ConsentDataSource dataSource) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        requireCallable(tokenRole, STAFF_CALLABLE, "B3 POST /customers/{id}/consents");

        UUID custId = parseUuid(customerId, "id", "B3 POST /customers/{id}/consents");
        if (authScope == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B3 缺 auth_scope —— 该列 NOT NULL。"
                            + "🛑 空集（拒绝全部四项）是【合法且有意义】的值，与 null 严格区分："
                            + "data-dict §2.8『分项勾选，可单独拒绝』");
        }
        if (bandWillingness == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B3 缺 band_willingness —— 该列 NOT NULL 且 CHECK ∈ {自愿佩戴, 暂不佩戴}。"
                            + "🛑 该值【不参与任何门禁判定】：V5 附注逐字『拒戴不得降级服务』，"
                            + "故它只被记录，不被读作任何分支条件");
        }
        if (evidenceHash == null || evidenceHash.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B3 缺 evidence_hash —— 该列 NOT NULL（契约 ADR-11 证据链哈希）。"
                            + "🛑 本方法【不】自算一个哈希兜底：那会造出一个假锚点，"
                            + "而『证据可核验』的全部意义就是锚点必须来自真实签署动作");
        }
        if (dataSource == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B3 缺 data_source —— 该列 NOT NULL。显式给出而不依赖库层 DEFAULT 'self-report'："
                            + "本域已有一次库层默认值造成偏差的先例（customer.status）");
        }

        // 门禁 G1（写入之前）：客户须已越过 PROFILED
        CustomerState current = customers.findCurrentState(tenantId, custId);
        CustomerGateGuard.assertProfiled("B3 POST /customers/{id}/consents", current);

        UUID consentId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID operatorId = requireStaffId();
        ConsentRow row = new ConsentRow(consentId, custId, authScope, bandWillingness,
                now, evidenceHash, dataSource);
        consents.append(tenantId, row, String.valueOf(operatorId));

        // 状态机：PROFILED → CONSENTED（data-dict §2.25 ① 的边）
        if (CustomerStateMachine.isAllowedTransition(current, CustomerState.CONSENTED)) {
            StateTransitionDraft t = StateTransitionDraft.passed(
                    UUID.randomUUID(), current, CustomerState.CONSENTED,
                    "B3:consent_signed", operatorId, now,
                    "consent", consentId.toString(), String.valueOf(operatorId));
            customers.appendTransition(tenantId, custId, t);
            customers.updateStatus(tenantId, custId, CustomerStatus.CONSENTED);
        }

        return contractSignConsentData(consentId);
    }

    // ==================================================================
    // B4 · GET /customers/{id}
    // ==================================================================

    /**
     * B4 客户详情（可见性裁剪重点接口）。
     *
     * <h2>🛑 顺序纪律 ②：403 归因<b>必须</b>在取数之前</h2>
     * <pre>
     *  ① CustomerFieldVisibility.assertIncludeAllowed(includes, role)  ← 越权即 403，此时未取任何数
     *  ② 取数（客户主体 + 档案 + 筛查 + 同意 + 派生）
     *  ③ CustomerDetailView.toContractData(role, includes)             ← 裁剪唯一落点
     * </pre>
     * 若把 ① 挪到 ③ 之后：一次越权请求会先把全量数据取进内存再被拒 ——
     * 结果码一样，但"被拒的请求不应触达数据"这条边界消失了。
     *
     * <h2>🛑 错误码（B4 = 200 + 403 VisibilityDenied + 404 NotFound）</h2>
     * <pre>
     *  客户不存在  → 404 NOT_FOUND（B4 声明了它；与 B2 的处置刻意不同）
     *  越权 include → 403 VISIBILITY_DENIED（回显 data.denied_fields）
     * </pre>
     */
    public Map<String, Object> getCustomer(String customerId, List<String> includes) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        VisibilityRole role = requireCallable(tokenRole, DETAIL_CALLABLE, "B4 GET /customers/{id}");

        // ① 403 归因（取数之前）
        CustomerFieldVisibility.assertIncludeAllowed(includes, role);

        UUID custId = parseUuid(customerId, "id", "B4 GET /customers/{id}");

        // ② 取数
        CustomerDetailView base = customers.findDetailView(tenantId, custId);
        if (base == null) {
            // 🛑 404 是 B4 声明集内的码（与 B5/B6 只有 200 不同）
            throw new BizException(ErrorCode.NOT_FOUND,
                    "客户 " + custId + " 在本租户内不存在 —— 契约 B4 的 responses 声明集含 404(NotFound)");
        }

        // 装配非主体字段（各账本各取一份，不在仓储里 JOIN）
        ScreeningRecordRow latestScreening = screenings.findLatest(tenantId, custId);
        ConsentRow latestConsent = consents.findLatest(tenantId, custId);
        IntakeProfileRow profile = profiles.findProjection(tenantId, custId);

        CustomerDetailView full = new CustomerDetailView(
                base.customerId(), base.name(), base.gender(), base.age(),
                profile == null ? Map.of() : IntakeProfileLedger.snapshotOf(profile),
                latestScreening == null ? null : latestScreening.result().code(),
                latestConsent == null ? null : latestConsent.bandWillingness().code(),
                base.ownerStoreId(), base.servingStoreId(),
                null,   // effect_verdict —— 派生值，域 B 不自行计算（见类注释第五节）
                null);  // as_value      —— 同上

        // ③ 裁剪（唯一落点）
        return full.toContractData(role, includes);
    }

    // ==================================================================
    // B5 · GET /customers/{id}/intake-profile
    // ==================================================================

    /**
     * B5 建档扩展档案。
     *
     * <h2>🛑 错误码（B5 = <b>仅 200</b>）</h2>
     * 故"客户不存在"与"尚未建档"都返回 {@code 200 + 空 data} ——
     * 报 404 会让客户端落进契约未声明的分支。
     * 两者的区别对调用方无意义（都是"没有档案可看"）。
     */
    public Map<String, Object> getIntakeProfile(String customerId) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        VisibilityRole role = requireCallable(tokenRole, DETAIL_CALLABLE,
                "B5 GET /customers/{id}/intake-profile");

        UUID custId = parseUuid(customerId, "id", "B5 GET /customers/{id}/intake-profile");
        IntakeProfileRow row = profiles.findProjection(tenantId, custId);
        if (row == null) {
            // 200 + 空 data（不是 404 —— B5 未声明它）
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>(IntakeProfileLedger.snapshotOf(row));
        // 🛑 档案的键集【不】按角色裁剪：契约 B5 未给 intake_profile 的子字段
        //    任何 x-visible-to（它整块属"客户可见"的字段），故此处不做角色分支。
        //    若日后契约为其子字段补了 x-visible-to，裁剪落点应在 IntakeProfileRow，
        //    而不是在这里加 if。
        out.put("role_used_for_callable_check", role.contractCode());
        return out;
    }

    // ==================================================================
    // B6 · PATCH /customers/{id}/intake-profile
    // ==================================================================

    /**
     * B6 补充 + 修订（append-only 留痕，不可覆盖）。
     *
     * <h2>🛑 唯一写入落点是 {@link IntakeProfileLedger#appendRevisionAndCoalesce}</h2>
     * 投影与历史<b>同一事务</b>写入（见那本账本的类注释）。本方法只负责：
     * <pre>
     *  ① 取 {@code revision_no}（由 max+1；并发由唯一约束兜底）
     *  ② 取 {@code supersedes_revision_id}（上一版；首次为 null）
     *  ③ 调 appendRevisionAndCoalesce
     * </pre>
     *
     * <h2>🛑 错误码（B6 = <b>仅 200</b>）</h2>
     * 故本方法<b>不</b>对"客户不存在"报 404、<b>不</b>对"未建档"报 403。
     * 首次写入即建档（{@code revision_no = 1}）。
     */
    public Map<String, Object> patchIntakeProfile(String customerId, Map<String, Object> patch) {
        String tenantId = requireTenant();
        String tokenRole = TenantContext.role();
        requireCallable(tokenRole, STAFF_CALLABLE, "B6 PATCH /customers/{id}/intake-profile");

        UUID custId = parseUuid(customerId, "id", "B6 PATCH /customers/{id}/intake-profile");
        if (patch == null || patch.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B6 补丁为空 —— 一次什么都不改的修订会让『改过几次』这个计数被无意义地抬高，"
                            + "而修订序号是跨店争议复盘时的排序依据");
        }

        UUID operatorId = requireStaffId();
        Instant now = Instant.now();
        IntakeProfileRow existing = profiles.findProjection(tenantId, custId);
        IntakeProfileRevisionRow previous = revisions.findLatest(tenantId, custId);

        IntakeProfileRow merged = mergeProfile(tenantId, custId, existing, patch, String.valueOf(operatorId));
        int nextNo = revisions.nextRevisionNo(tenantId, custId);
        Map<String, Object> snapshot = IntakeProfileLedger.snapshotOf(merged);

        IntakeProfileRevisionRow revision = new IntakeProfileRevisionRow(
                UUID.randomUUID(), custId, nextNo, snapshot,
                previous == null ? null : previous.revisionId(),
                "B6 补充/修订", now, operatorId, String.valueOf(operatorId));

        profiles.appendRevisionAndCoalesce(tenantId, merged, revision);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer_id", custId.toString());
        out.put("revision_no", nextNo);
        out.put("recorded_at", now.toString());
        out.put("delta_vs_previous", revision.describeDeltaVersus(
                previous == null ? null : previous.snapshot()));
        return out;
    }

    // ==================================================================
    // 辅助：角色 / 租户 / 取值
    // ==================================================================

    /**
     * 契约可调用角色校验。
     *
     * <h2>🛑 为什么判"契约声明集"而不判"有没有某个权限码"</h2>
     * 与 {@code StoreListService#requireCallable} 同源：判权限码等于把控制器上的
     * {@code @RequirePermission} 写第二遍（一遍改坏另一遍兜底，两层退化成一层）；
     * 判契约角色集则守护<b>契约条款本身</b>，与权限矩阵怎么配无关。
     *
     * <p>fail-closed：未登记角色一律拒，绝不回落为"当作 staff 放行"。
     *
     * <p>🛑 报 {@code VISIBILITY_DENIED(2001)} → 映射 403。
     * 该码在 B4/B5 的声明集内（VisibilityDenied），在 B1/B2/B3/B6 的声明集内
     * 表现为 2002（GATE_MISSING）之外的 403 —— 契约 §2.0 逐字把两类归入同一 HTTP 码：
     * 「『前置门禁缺失』与『可见性档位不足』统一返回 403，message 必须给出
     * 缺失项名称 / 档位名称（不得模糊报错）」。
     */
    VisibilityRole requireCallable(String tokenRole, Set<VisibilityRole> callable, String ref) {
        if (tokenRole == null || tokenRole.isBlank()) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    ref + "：请求未携带身份，无法确定可调用角色。"
                            + "契约 §2.0 forbidden-403「『前置门禁缺失』与『可见性档位不足』统一 403」——"
                            + "本端点不设匿名通道");
        }
        VisibilityRole endRole = VisibilityRole.tryOf(tokenRole.trim())
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        ref + "：token 角色未登记: '" + tokenRole + "'（已登记: "
                                + VisibilityRole.allTokenRoles() + "）—— fail-closed，"
                                + "不回落为任何端角色：回落会让一次角色码拼写错误静默变成一次权限判定"));
        if (!callable.contains(endRole)) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    ref + "：当前端角色无权调用 —— 契约该行的 x-callable-roles 不含它。"
                            + "实际=" + endRole.contractCode()
                            + "，声明集=" + describeRoles(callable)
                            + "。🛑 本拒绝刻意不依赖『他不持有某个权限码』：权限矩阵是可变配置，"
                            + "而契约条款是冻结项");
        }
        return endRole;
    }

    private static List<String> describeRoles(Set<VisibilityRole> roles) {
        List<String> out = new ArrayList<>();
        for (VisibilityRole r : roles) {
            out.add(r.contractCode());
        }
        return List.copyOf(out);
    }

    /** 取当前租户（B 域全部端点均要求租户上下文）。 */
    String requireTenant() {
        String tenantId = TenantContext.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.TENANT_MISMATCH,
                    "域 B 端点缺少租户上下文 —— RLS 隔离与写入都依赖它");
        }
        return tenantId;
    }

    /** 取 token 里的操作人（{@code operator_id} 的唯一来源，契约逐字"服务端从 token 覆写"）。 */
    UUID requireStaffId() {
        String staffId = TenantContext.staffId();
        if (staffId == null || staffId.isBlank()) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "缺少操作人身份（token 里无 staff_id）—— "
                            + "筛查记录的 operator_id 与档案修订的 operator_id 在库层均 NOT NULL，"
                            + "且契约逐字要求『服务端从 token 覆写』：本值不得由调用方提供、"
                            + "也不得缺失（缺它则本次写入没有责任人）");
        }
        UUID id = parseUuidQuiet(staffId);
        if (id == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "token 里的 staff_id 非合法 UUID: '" + staffId + "' —— "
                            + "库层 operator_id 是 UUID 列，直接传入会以类型错误失败");
        }
        return id;
    }

    /**
     * 解算归属门店（{@code owner_store_id}）。
     *
     * <h2>🛑 data-dict §2.6：「归属/首诊店，锁定档案」⇒ 必须有一个值</h2>
     * 若调用方未提供，则从 token 锚点（{@code staff.store_id}）解出 ——
     * 那是"首诊"的自然语义：客户在哪家店首次建档，该店即归属店。
     * 客户端<b>不得</b>自选归属店（否则可自选可见范围）。
     */
    private UUID resolveOwnerStoreId(String tenantId, UUID provided, String ref) {
        if (provided != null) {
            return provided;
        }
        String staffId = TenantContext.staffId();
        UUID sid = staffId == null ? null : parseUuidQuiet(staffId);
        if (sid == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    ref + "：无法解算 owner_store_id —— 请求未指定归属店，"
                            + "且 token 里没有可用的 staff_id 作为锚点。"
                            + "data-dict §2.6『owner_store_id = 归属/首诊店，锁定档案』："
                            + "缺它则客户后续归属无从判定，故不得为 null");
        }
        StoreAnchor anchor = stores.findAnchor(tenantId, sid);
        if (anchor == null || !anchor.hasStore()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    ref + "：无法解算 owner_store_id —— 操作人档案里没有门店归属（staff.store_id 为空）。"
                            + "🛑 不回落为『任意一个门店』或 null："
                            + "前者会把客户归属挂到一家与首诊无关的店，后者会破坏『锁定档案』");
        }
        return anchor.storeId();
    }

    /**
     * B6 的合并策略（投影 = 已有值 + 补丁）。
     *
     * <h2>🛑 三项体测值缺省<b>不得补 0</b>（data-dict §2.23 明文）</h2>
     * {@code height_cm} / {@code weight_kg} / {@code waist_cm} 的"可空 = 未填"，
     * 故未出现在 patch 里时保留既有值；既有值也没有时为 null，<b>不</b>写 0
     * （0 是非法值，{@link IntakeProfileRow} 构造期会拒）。
     */
    private IntakeProfileRow mergeProfile(String tenantId, UUID custId, IntakeProfileRow existing,
                                          Map<String, Object> patch, String createdBy) {
        UUID profileId = existing == null ? UUID.randomUUID() : existing.profileId();
        return new IntakeProfileRow(
                profileId, custId,
                mapOr(patch, "job_tag", existing == null ? null : existing.jobTag()),
                dec(patch, "height_cm", existing == null ? null : existing.heightCm()),
                dec(patch, "weight_kg", existing == null ? null : existing.weightKg()),
                dec(patch, "waist_cm", existing == null ? null : existing.waistCm()),
                intOr(patch, "bp_sys", existing == null ? null : existing.bpSys()),
                intOr(patch, "bp_dia", existing == null ? null : existing.bpDia()),
                intOr(patch, "hr", existing == null ? null : existing.hr()),
                dec(patch, "glucose", existing == null ? null : existing.glucose()),
                dec(patch, "uric_acid", existing == null ? null : existing.uricAcid()),
                mapOr(patch, "sleep", existing == null ? null : existing.sleep()),
                mapOr(patch, "diet", existing == null ? null : existing.diet()),
                mapOr(patch, "exercise", existing == null ? null : existing.exercise()),
                mapOr(patch, "thermal", existing == null ? null : existing.thermal()),
                mapOr(patch, "pain_sites", existing == null ? null : existing.painSites()),
                mapOr(patch, "bowel", existing == null ? null : existing.bowel()),
                mapOr(patch, "female_special", existing == null ? null : existing.femaleSpecial()),
                mapOr(patch, "meridian_self_report", existing == null ? null : existing.meridianSelfReport()),
                createdBy);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOr(Map<String, Object> patch, String key, Map<String, Object> fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        Object v = patch.get(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED,
                "B6 字段 " + key + " 应为对象（JSONB 多选），实际是 " + v.getClass().getSimpleName()
                        + " —— 该列是 JSONB，传入标量会在 ?::jsonb 强转处报类型错");
    }

    private static BigDecimal dec(Map<String, Object> patch, String key, BigDecimal fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        Object v = patch.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B6 字段 " + key + " 不是合法数值: '" + v + "'");
        }
    }

    private static Integer intOr(Map<String, Object> patch, String key, Integer fallback) {
        if (!patch.containsKey(key)) {
            return fallback;
        }
        Object v = patch.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "B6 字段 " + key + " 不是合法整数: '" + v + "'");
        }
    }

    // ==================================================================
    // 出参装配（契约 schema 逐字段）
    // ==================================================================

    /** 契约 {@code CustomerCreateData}（B2 出参）。 */
    private static Map<String, Object> contractCustomerCreateData(UUID custId, CustomerStatus status,
                                                                  UUID ownerStoreId, UUID servingStoreId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer_id", custId.toString());
        out.put("status", status.code());
        out.put("owner_store_id", ownerStoreId == null ? null : ownerStoreId.toString());
        out.put("serving_store_id", servingStoreId == null ? null : servingStoreId.toString());
        return out;
    }

    /** 契约 {@code ResultEnvelope}（B3 出参：无 schema data）。 */
    private static Map<String, Object> contractSignConsentData(UUID consentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consent_id", consentId.toString());
        return out;
    }

    // ==================================================================
    // 缺口与口径的自描述（可断言的事实）
    // ==================================================================

    /** B3 的取参缺口 —— 契约无 requestBody 而库层四列 NOT NULL。 */
    public Map<String, Object> describeSignConsentGap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", "B3 POST /customers/{id}/consents");
        m.put("contract_shape", "仅有 path 参数 CustomerId —— 契约【无 requestBody】");
        m.put("not_null_columns",
                List.of("auth_scope_json", "band_willingness", "signed_at", "evidence_hash", "data_source"));
        m.put("disposition",
                "本服务忠实承载四值并强校验非空，但不发明它们的来源 —— "
                        + "取参策略（前端表单 / 签署回执 / 设备侧）属上游待定项");
        m.put("not_invented",
                "🛑 不得为了让 B3『看起来完整』而在契约里补 requestBody —— 契约是三方冻结件；"
                        + "也不得在服务端自算 evidence_hash 兜底（那会造出假锚点）");
        return m;
    }

    /** 禁忌清单口径自描述（B1 的推导依据 + 配置缺口）。 */
    public Map<String, Object> describeContraindicationPolicy() {
        Map<String, Object> m = new LinkedHashMap<>(contraindications.describe());
        m.put("endpoint", "B1 POST /screening-records");
        m.put("derive_policy",
                "本服务在 submitScreening 里【服务端推导】result，"
                        + "不读请求体的同名字段（契约 ScreeningCreateRequest.required 无 result）");
        m.put("error_set",
                "B1 responses = 200 + 400 —— 【无 403 / 无 500】；"
                        + "故清单未配置时【照常推导 + 告警】而不抛异常");
        return m;
    }

    /**
     * 建键环节缺位的自描述（B2 依赖链的前提缺口）。
     *
     * <h2>🛑 为什么由本类委托，而不是让控制器直接问账本</h2>
     * 这两条自描述原本是账本（{@code repository} 层）的静态方法。
     * 控制器直连它们会让 {@code ArchitectureBoundaryTest} 的 <b>R2</b>
     * （控制器不得依赖数据访问层）报红 —— 而那次报红是<b>对的</b>：
     * 它拦下的不是"读一个字符串"，而是"控制器可以绕过服务层"这条先例。
     * 一旦某天有人在 {@code describeContract()} 旁边顺手加一句
     * {@code CustomerLedger.findDetailView(...)}（同样语法可行），守卫就会继续绿着。
     * 故这两条口径经本类转发：控制器只认识服务层，仓储的可见性由服务层独占。
     */
    public Map<String, Object> describeKeyCreationGap() {
        return CustomerLedger.describeKeyCreationGap();
    }

    /** 投影与历史对齐关系的自描述（含"读法待上游核对"的登记）。 */
    public Map<String, Object> describeProjectionVsHistory() {
        return IntakeProfileLedger.describeProjectionVsHistory();
    }

    // ==================================================================
    // 解析辅助
    // ==================================================================

    static UUID parseUuid(String raw, String fieldName, String ref) {
        UUID id = parseUuidQuiet(raw);
        if (id == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    ref + "：路径/入参 " + fieldName + " 不是合法 UUID: '" + raw + "'");
        }
        return id;
    }

    static UUID parseUuidQuiet(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}