package com.diaoyuanyun.dy.app.agreement.domain;

import com.diaoyuanyun.dy.app.band.repository.BandLedger;
import com.diaoyuanyun.dy.common.crypto.Hashes;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * <b>调理协议书离线签署意图</b> —— 一次"把一份已签署的调理协议书登记进系统"的完整输入。
 *
 * <h2>🛑🛑 它补的是什么：PRD 的 G1 指标在系统里本来<b>没有任何取数来源</b></h2>
 * 在 A-1 之前，{@code agreement} 在生产代码里<b>零写入方</b>、真库零行
 * （实测 {@code count(*) = 0}）—— 它是 {@code ProvisioningBoundaryGateTest}
 * 的 {@code NOT_PROVISIONED} 三张表之一。
 * <pre>
 *   PRD C.1.5 §八 逐字：「04.§八 四方签署 | agreement.signatures |
 *                        json{客户/经络师/调理师/门店负责人:签名+日期} | 是 |
 *                        <b>未签不得首次调理或退款判定</b> | <b>硬门禁</b>」
 *   PRD C.1.7 第 7 项 逐字：「签调理协议书 | agreement_signed | <b>硬门禁③</b>」
 * </pre>
 * ⇒ 缺了写入方，<b>「已签客户占比」这个取数项分母有、分子恒为 0</b>：
 * G1 的取数 SQL 无论怎么写都返回空集，而<b>没有任何一次调用会因此报错</b>。
 * 系统只是"少做了一件事"，安静地少做。
 *
 * <h2>🛑🛑🛑 三条边界：本迁移<b>不是</b> I8、<b>不是</b> H1，也不解析任何厂商签名</h2>
 * 这是本类最该被读到的一段。A-1 被两条方向相反的约束夹着：
 * <table border="1">
 *   <caption>两条约束的张力，以及本迁移站在哪里</caption>
 *   <tr><th></th><th>PRD 侧（推着"做"）</th><th>契约侧（按着"不许做"）</th></tr>
 *   <tr><td>要求</td>
 *       <td>G1「签署合规率」需要 {@code agreement} 有数据</td>
 *       <td>H1 {@code receiveEsignCallback} 与 I8 {@code renderAgreement}
 *           都是 <b>{@code x-client-forbidden: true} + {@code x-callable-roles: []}</b>，
 *           且 {@code x-frontier} 标注<b>占位待冻结</b></td></tr>
 *   <tr><td>契约逐字</td>
 *       <td>—</td>
 *       <td>「🛑 本节只冻结形态……<b>厂商选定之前，三端不得据本节编码</b>」</td></tr>
 *   <tr><td>⇒ 本迁移</td>
 *       <td colspan="2">只做<b>离线签署通路</b>（运维通路形态、<b>无 HTTP 映射</b>、
 *           不解析任何厂商签名）—— 它<b>接收</b>一份已经签好的协议（含渲染稿与 hash），
 *           把它登记进库。<b>不渲染、不推送、不接回调。</b></td></tr>
 * </table>
 * <p>🛑 <b>为什么不能实现 H1</b>（这不是保守，是它<b>在语义上就不成立</b>）：
 * H1 是"厂商回调通知系统：这份协议签好了"。实现它需要回答四个尚未冻结的问题
 * （厂商签名算法 / 载荷结构 / {@code provider} 取值域 / 投递语义），
 * 而<b>更重要</b>的是：一个未验签的回调处理器，<b>等于开一条
 * "任何人 POST 一下即可让系统认为某协议已签"的通道</b>。
 * 那会让"未签不得首次调理"这条硬门禁<b>形同虚设</b>。
 * <p>🛑 <b>为什么不能实现 I8</b>：I8 的出口是<b>厂商</b>（把渲染结果交给电子签服务去签）。
 * 厂商未选定 ⇒ 写一个"只渲染、不推送"的 I8 是<b>假实现</b>：
 * 它产出的渲染稿<b>永远签不了</b>，而它会让 {@code EndpointCoverageLedgerTest}
 * 的"已实现端点"账面<b>看起来多了一项</b>。
 * <p>⇒ 本类的 {@code renderedSnapshot} / {@code renderedHash} 是
 * <b>入参</b>（由离线签署流程提供），不是本类产出的。
 * 这一点在 {@link #renderedHashMatches()} 的重算校验里被强调：
 * 本类只<b>核验</b>摘要与正文一致，<b>不生成</b>摘要。
 *
 * <h2>🛑 构造期就校验 —— 且这是刻意的（"校验两遍"的理由）</h2>
 * 规范构造器会跑一遍形态与门禁校验，规则与迁移 V21 函数内<b>逐条一致</b>
 * （4 方签署键齐备且非空 / 条款快照非空对象 / 渲染稿非空 / hash 64 位 hex /
 * 模板指针成对）。
 * <p>🛑 为什么"两遍"而不是"只留库层"：
 * <ul>
 *   <li><b>库层那道必须在</b>：函数可以被人绕过（直接写 SQL），而 {@code agreement}
 *       的门禁<b>刻意不是表约束</b>（表上只有一条 {@code ck_agreement_plan_version_positive}
 *       CHECK，实测恰 1 条；V21 自证 (b7b) 与前置自检第 ⑦ 条双重钉住"恰 1 条"）。
 *       故库层门禁的唯一载体就是那个函数；</li>
 *   <li><b>Java 这道也要在</b>：它把"缺项"从一个<b>运行期 SQL 异常</b>
 *       提前成一个<b>可读的、在调用前就发生的</b>参数错误，
 *       并且它是 {@link AgreementSignKey} 那套键集封闭性的<b>执行者</b> ——
 *       若没有这一层，"4 方是封闭清单"这件事就只存在于枚举里，
 *       而没有东西按它行动。</li>
 * </ul>
 * <p>🛑 两处校验的措辞刻意<b>不共享常量</b>（各自写自己的消息）：它们服务不同的读者
 * （一个是应用日志/接口响应，一个是库迁移日志/psql）。但<b>键集与门禁强度必须一致</b>，
 * 而这一点由 {@code AgreementGateTest} 机械核对（枚举 ↔ V21 函数内数组）。
 *
 * <h2>🛑🛑 应用层必须<b>重算</b> hash —— 因为库层在物理上做不到</h2>
 * 实测真库 {@code pg_extension} <b>只有 plpgsql</b> ⇒ {@code pgcrypto} 未安装
 * ⇒ <b>没有 {@code digest()}</b> ⇒ 库层<b>无法</b>从 {@code rendered_snapshot}
 * 重算 SHA-256。
 * <p>⇒ V21 的 {@code register_agreement()} <b>只校验 hash 的形态</b>（64 位 hex），
 * 并把这一点逐字写进它的注释与错误消息（"不得把本条读成『库层已校验 hash 内容』"）。
 * 而"这个 hash 确实是这份正文的摘要"这条性质的守卫落在
 * {@link #renderedHashMatches()}：它复用 {@link Hashes#sha256Utf8(String)}
 * 的<b>同一口径</b>（本仓唯一的摘要口径，A-1 为此把它的可见性从包私有提升为 public），
 * 用 {@code equalsIgnoreCase} 比对（与 {@code DocFileService} 读回时的重算同款）。
 * <p>🛑 <b>为什么用 {@code equalsIgnoreCase}</b>：库里每一行 hash 都由 V21 的
 * {@code lower(...)} 归一成小写，而调用方传进来的可能是大写
 * （V21 的形态正则接受 {@code [0-9a-fA-F]}）。大小写是同一个值的不同写法，
 * 比较时归一即可 —— 这正是 {@code DocFileService} 既有重算比对的做法。
 *
 * <h2>🛑 本类【不】校验"客户当前状态必须是 PLAN_APPROVED"—— 登记为已知边界</h2>
 * PRD §八 逐字写「<b>未签不得首次调理或退款判定</b>」，且第 7 项 gating 动作
 * 逐字写「签调理协议书 → {@code agreement_signed} → 硬门禁③」。
 * <p>⇒ 这条门禁的<b>消费点</b>在 {@code CustomerGateGuard}
 * （实测该类已登记 {@code PLAN_APPROVED → AGREEMENT_SIGNED} 的跃迁），
 * <b>不在</b>本类、也不在 V21 的函数。
 * <p>🛑 为什么本类做不到它：本类<b>读不到</b> {@code customer.state}（那是 customer 域），
 * 且若在这里硬写一条"客户必须处于 PLAN_APPROVED"，等价于<b>代拍</b>
 * "签署动作必须紧跟方案通过"这条业务规则 —— 而 PRD <b>没有</b>说
 * "不能在别的状态下补签"（现实中离线纸面协议完全可能晚于方案批准几天才登记）。
 * <p>⇒ 本类把"状态机一致性"<b>显式留给调用方 / 门禁层</b>，并登记为已知边界
 * （见 V21 文件头「待裁登记」第 3 条 —— 与归档域"refund ↔ case_archive 无关联列"
 * 同族：都是"某条 PRD 规则需要一个本层拿不到的输入"）。
 *
 * <h2>🛑 为什么 tenantId 的校验复用 {@code BandLedger.validateTenantId}</h2>
 * 不另写一份白名单正则。理由与归档域逐字相同：两处口径会各自漂移，
 * "哪个更严"取决于谁先跑。
 *
 * @param tenantId               协议所属租户（必填）
 * @param agreementId            协议主键（必填）；🛑 它是<b>全局</b>主键，
 *                               故"一份协议的一生只属于一个租户"
 * @param customerId             签署客户（必填）；必须是<b>本租户内</b>存在的客户。
 *                               🛑 它同时是<b>行级 scope 的承载者</b>：
 *                               {@code agreement} 表内没有 {@code store_id}，
 *                               协议的可见范围随客户走
 * @param planId                 绑定方案（必填）；必须是<b>本租户内</b>存在的方案
 * @param planVersion            绑定方案版本（必填，须 ≥ 1）；
 *                               🛑 判据<b>必须带 version</b>：{@code (plan_id, plan_version)}
 *                               是复合外键的一端，"方案存在但那一版不存在"必须被判为不存在
 * @param refundClauseSnapshot   退款条款快照（必填，须为<b>非空对象</b>）；
 *                               🛑 PRD §2.9 逐字「退款/终止条款<b>必须快照存储·不可覆盖</b>」
 * @param breachClauseSnapshot   违约条款快照（可空）；🛑 与退款条款分开两个字段，
 *                               因为它们是 PRD §2.9 里<b>两条</b>独立的条款
 * @param signedAt               签署业务时刻（必填）；🛑 它是本域的<b>业务时刻</b>，
 *                               与 {@code created_at}（登记时刻）是两个不同的概念 ——
 *                               离线补录会让两者分叉，而 {@code latest_agreement_of}
 *                               按 {@code signed_at} 排序正是为此
 * @param signer                 四方签署（必填）；4 键须齐备且值非空白
 * @param docTemplateId          文档模板 id（可空）；🛑 与 version <b>必须成对</b>
 * @param docTemplateVersion     文档模板版本（可空）；🛑 与 id <b>必须成对</b>
 * @param renderedSnapshot       渲染稿正文（必填，非空白）；
 *                               🛑 它是<b>入参</b>不是本类产出（见类注释三边界）
 * @param renderedHash           渲染稿摘要（必填，64 位 hex）；
 *                               🛑 同样是人参，且会被 {@link #renderedHashMatches()} 重算核验
 * @param createdBy              登记者标识（可空；库层回落为 {@code agreement-offline-signing}）
 */
public record AgreementRecord(
        String tenantId,
        UUID agreementId,
        UUID customerId,
        UUID planId,
        int planVersion,
        Map<String, Object> refundClauseSnapshot,
        Map<String, Object> breachClauseSnapshot,
        Instant signedAt,
        Map<String, String> signer,
        UUID docTemplateId,
        Integer docTemplateVersion,
        String renderedSnapshot,
        String renderedHash,
        String createdBy) {

    /** {@code agreement.created_by} 的上限（{@code VARCHAR(128)}）。 */
    private static final int CREATED_BY_MAX = 128;

    /** {@code agreement.rendered_hash} 的上限（{@code VARCHAR(128)}）。 */
    private static final int RENDERED_HASH_MAX = 128;

    /** SHA-256 的规范 hex 长度（形态判据与 V21 逐字一致）。 */
    static final int SHA256_HEX_LEN = 64;

    /**
     * 规范构造器 —— 形态与门禁校验都在这里，使"一个非法的签署意图"无法被构造出来。
     *
     * <p>🛑 为什么把校验放在构造器而不是 Service：构造器是<b>唯一</b>的入口。
     * 放在 Service 里意味着"只要有人绕过 Service 直接 new 一个 record"
     * 就能拿到非法值 —— 而那正是这个 record 存在的意义
     * （它把"意图"变成一个<b>自校验</b>的值对象）。
     */
    public AgreementRecord {
        BandLedger.validateTenantId(tenantId);

        if (agreementId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：agreement_id 必填 —— 它是 agreement 表主键，"
                            + "且是本原语幂等判定的唯一键。没有它，重放无法与『新建』区分");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：customer_id 必填 —— agreement.customer_id 是 NOT NULL，"
                            + "且是复合外键 (tenant_id, customer_id) → customer 的一端。"
                            + "🛑 它同时是行级 scope 的承载者：本表内没有 store_id，"
                            + "协议的可见范围随客户走");
        }
        if (planId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：plan_id 必填 —— agreement.plan_id 是 NOT NULL，"
                            + "且是复合外键 (plan_id, plan_version) → plan 的一端。"
                            + "🛑 协议只能绑到已存在的那一版方案上（PRD §2.9 逐字：『绑定方案版本』）");
        }
        if (planVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：plan_version = " + planVersion + " 非法 —— 方案版本必须从 1 起。"
                            + "口径来源 = PRD §2.9『版本递增·不可覆盖』"
                            + "（表上 CHECK {@code ck_agreement_plan_version_positive} 同口径）。"
                            + "🛑 在 Java 侧先拦，是因为库层的失败形态是一条 23514 + 一个约束名，"
                            + "而不是一条写明『版本必须从 1 起』的业务错误");
        }
        if (signedAt == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：signed_at 必填 —— agreement.signed_at 是 NOT NULL，"
                            + "且它是本域的**业务时刻**（与 created_at 登记时刻不同，"
                            + "离线补录会让两者分叉；latest_agreement_of 按 signed_at 取最新）");
        }
        if (refundClauseSnapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：refund_clause_snapshot 必填 —— 它是 PRD §2.9"
                            + "『退款/终止条款必须快照存储·不可覆盖』的落纸载体");
        }
        if (refundClauseSnapshot.isEmpty()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "协议签署登记被阻断：refund_clause_snapshot 是【空对象】。"
                            + "口径来源 = PRD §2.9 / §四 逐字：『退款/终止条款必须快照存储·不可覆盖；"
                            + "条款改版不回溯已签协议』。"
                            + "🛑 空对象不是『一条条款都没有的合法情形』，而是**占位**："
                            + "这一列的唯一用途是事后证明『签的时候条款原文是什么』，"
                            + "空快照让这份举证责任无法履行，而它看起来『填了』。"
                            + "报 GATE_MISSING(2002, 403) 与 P0-25 同口径");
        }
        if (breachClauseSnapshot != null && breachClauseSnapshot.isEmpty()) {
            // 可空列，但"传了空对象"与"没传"是两件事 —— 归一成 null（与 V21 的口径一致）。
            breachClauseSnapshot = null;
        }
        if (signer == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：signer 必填 —— 它是 PRD C.1.5 §八 四方签署硬门禁的唯一载体");
        }
        if (renderedSnapshot == null || renderedSnapshot.isBlank()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "协议签署登记被阻断：rendered_snapshot 是空白（或仅空格）。"
                            + "口径来源 = PRD P0-27 逐字：『渲染结果必须落快照 + hash"
                            + "（否则事后无法证明『当时签的是哪一版』）』。"
                            + "🛑 空白正文与『没有正文』在举证上等价：一份空白协议无法证明任何事。"
                            + "报 GATE_MISSING(2002, 403)");
        }
        if (renderedHash == null || renderedHash.isBlank()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "协议签署登记被阻断：rendered_hash 必填（PRD P0-27 要求落快照 + hash）。"
                            + "🛑 本迁移不生成 hash（渲染是 I8 的职责，而 I8 的厂商尚未冻结）—— "
                            + "hash 由离线签署流程提供，本类只做形态校验与重算核验。"
                            + "报 GATE_MISSING(2002, 403)");
        }

        // ---- 归一 ----
        signer = normalizeSigner(signer);
        refundClauseSnapshot = Map.copyOf(refundClauseSnapshot);

        renderedHash = renderedHash.trim();
        if (renderedHash.length() > RENDERED_HASH_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：rendered_hash 长度 " + renderedHash.length()
                            + " 超过 agreement.rendered_hash 的 VARCHAR(" + RENDERED_HASH_MAX + ")");
        }
        if (!renderedHash.matches("^[0-9a-fA-F]{" + SHA256_HEX_LEN + "}$")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：rendered_hash 形态非法（长度 " + renderedHash.length() + "）。"
                            + "要求 = **64 位十六进制**（SHA-256 的规范长度）。"
                            + "🛑 三种常见非法形态与它们各自的真因："
                            + "① 长度不对（32 位 = MD5 / 40 位 = SHA-1 / 128 位 = SHA-512）—— 算法用错；"
                            + "② 带 \"sha256:\" 前缀 / base64 / 含空格 —— 那是另一种编码，"
                            + "解码它属『协议解析』，而 I8/H1 协议尚未冻结 ⇒ 应用层不得猜；"
                            + "③ 含非十六进制字符 —— 不是摘要。"
                            + "本次原值 = 『" + snippet(renderedHash) + "』");
        }

        // ---- 模板指针成对（与 V21 (6a) 逐字同款）----
        if ((docTemplateId == null) != (docTemplateVersion == null)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：模板指针不成对 —— doc_template_id = " + docTemplateId
                            + "，doc_template_version = " + docTemplateVersion + "。"
                            + "两者要么都给、要么都不给。"
                            + "🛑 单边指针的用途（回答『这份协议签的是哪个模板的哪一版』）只答一半。"
                            + "注意：**两个都不给是合法的**（该列对可空，实测 nullable）");
        }
        if (docTemplateVersion != null && docTemplateVersion < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "协议签署登记：doc_template_version = " + docTemplateVersion
                            + " 非法 —— 模板版本必须从 1 起");
        }

        if (createdBy != null) {
            createdBy = createdBy.trim();
            if (createdBy.isEmpty()) {
                createdBy = null;
            } else if (createdBy.length() > CREATED_BY_MAX) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "协议签署登记：created_by 长度 " + createdBy.length()
                                + " 超过 agreement.created_by 的 VARCHAR(" + CREATED_BY_MAX + ")");
            }
        }
    }

    /**
     * 签署块归一 + 校验（构造器专用）。
     *
     * <p>🛑 未登记键 ⇒ 抛（与 V21 (5a) 的口径一致，绝不静默忽略）；
     * 4 键<b>全部</b>必须存在、是字符串、且<b>非空白</b>。
     * <p>🛑 为什么"非空白"而不是"非空"：一个空白的"门店负责人"不是签字
     * （见 {@link AgreementSignKey} 类注释）。而这份签署块是
     * 「未签不得首次调理或退款判定」这条硬门禁的落纸依据 ——
     * 让它接受空白串，等于让一个未签的四方通过门禁。
     */
    private static Map<String, String> normalizeSigner(Map<String, String> in) {
        for (String k : in.keySet()) {
            AgreementSignKey.of(k);          // 未登记即抛，绝不回落
        }

        Map<String, String> out = new TreeMap<>();
        List<String> missing = new java.util.ArrayList<>();
        for (AgreementSignKey k : AgreementSignKey.all()) {
            String v = in.get(k.code());
            if (v == null) {
                missing.add(k.code() + "（" + k.label() + "·键缺失）");
                continue;
            }
            if (v.isBlank()) {
                missing.add(k.code() + "（" + k.label() + "·值为空白）");
                continue;
            }
            out.put(k.code(), v.trim());
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.GATE_MISSING,
                    "协议签署登记被阻断：四方签署【缺项或为空】-> " + String.join(", ", missing)
                            + "（共 " + AgreementSignKey.allCodes().size() + " 键）。"
                            + "口径来源 = PRD C.1.5 §八 逐字：『agreement.signatures | "
                            + "json{客户/经络师/调理师/门店负责人:签名+日期} | 是 | "
                            + "**未签不得首次调理或退款判定** | **硬门禁**』"
                            + " + PRD 第 7 项 gating 动作『签调理协议书 | agreement_signed | 硬门禁③』。"
                            + "🛑 空串与缺键一并拒绝：一份没有责任人签名的协议，在举证场景里"
                            + "等同于『四方里有人没签』—— 而那正是本条门禁要拦的东西。"
                            + "报 GATE_MISSING(2002, 403)，与 P0-25『硬规则缺项 → 403 且给出缺失项名称』同口径");
        }
        return Map.copyOf(out);
    }

    /**
     * 🛑🛑 <b>应用层重算校验</b>：{@code renderedHash} 是否确实是 {@code renderedSnapshot}
     * 的 SHA-256 摘要。
     *
     * <h2>为什么这条校验必须在应用层（库层做不到）</h2>
     * 实测真库 {@code pg_extension} <b>只有 plpgsql</b> ⇒ {@code pgcrypto} 未安装
     * ⇒ 无 {@code digest()} ⇒ V21 的 {@code register_agreement()} <b>只能</b>校验 hash
     * 的形态（64 位 hex），并在注释与错误消息里逐字声明
     * "不得把本条读成『库层已校验 hash 内容』"。
     * <p>⇒ 本方法就是那句话所指的落点：它复用
     * {@link Hashes#sha256Utf8(String)} 的<b>同一口径</b>
     * （本仓唯一的摘要口径），对正文重算一遍，与入参比对。
     *
     * <h2>🛑 为什么用 {@code equalsIgnoreCase} 而不是 {@code equals}</h2>
     * 入参可能带大写（V21 的形态正则接受 {@code [0-9a-fA-F]}），而库里的行一律小写
     * （V21 的 INSERT 用 {@code lower(...)} 归一）。大小写是<b>同一个值的不同写法</b>，
     * 比较时归一即可 —— 这与 {@code DocFileService} 读回时的重算比对
     * （{@code actual.equalsIgnoreCase(row.fileHash())}）逐字同款。
     *
     * <h2>🛑 为什么它返回 boolean 而不是直接抛</h2>
     * 调用方（{@link com.diaoyuanyun.dy.app.agreement.service.AgreementService}）
     * 需要在一个<b>有上下文的位置</b>抛错（消息里要带 tenant / agreement 标识，
     * 且要能与"库层形态校验失败"区分开）。本方法只回答事实，
     * 让 Service 决定如何报告 —— 这与 {@code Snapshot#hardGatesSatisfied()}
     * 只返回布尔、由门禁断言决定如何用的形态一致。
     *
     * <p>🛑 <b>它不回答"这份 hash 是不是厂商签的"</b> —— 那需要验签，
     * 而厂商签名算法尚未冻结（契约 H1 逐字：厂商选定之前三端不得据本节编码）。
     * 本方法只回答"hash 与正文自洽吗"。
     */
    public boolean renderedHashMatches() {
        String recomputed = Hashes.sha256Utf8(renderedSnapshot);
        return recomputed.equalsIgnoreCase(renderedHash);
    }

    /**
     * 应用层重算出的摘要（供错误消息与对账；小写 hex）。
     *
     * <p>🛑 它是<b>独立复算</b>的结果，不是入参的回声 —— 两者的差异正是
     * {@link #renderedHashMatches()} 要发现的东西。
     */
    public String recomputedRenderedHash() {
        return Hashes.sha256Utf8(renderedSnapshot);
    }

    /**
     * 便捷构造：不含可选尾巴（违约条款 / 模板指针 / 建档者）。
     *
     * <p>省掉一串 {@code null} 实参 —— 本仓对这种"全是 null 的尾巴"有过教训：
     * 调用点上一串裸 {@code null} 无法自证"这一个 null 是哪一个字段"。
     */
    public static AgreementRecord of(String tenantId, UUID agreementId, UUID customerId,
                                     UUID planId, int planVersion,
                                     Map<String, Object> refundClauseSnapshot,
                                     Instant signedAt, Map<String, String> signer,
                                     String renderedSnapshot, String renderedHash) {
        return new AgreementRecord(tenantId, agreementId, customerId, planId, planVersion,
                refundClauseSnapshot, null, signedAt, signer,
                null, null, renderedSnapshot, renderedHash, null);
    }

    // ==================================================================
    // 序列化（供 Ledger 走 `?::jsonb` —— 与归档域同层同口径）
    // ==================================================================

    /**
     * 唯一 {@code ObjectMapper}（线程安全，构造有成本，不每调用 new 一个）。
     *
     * <p>🛑 序列化放在 record 里而不是 Ledger 里，理由与归档域逐字相同：
     * 它与"形态归一"是同一层职责的下半段（构造器已把 {@code signer} 归一成
     * 键集确定、值已 trim 的 {@code TreeMap}），拆开会让"形态"与"形态的序列化"
     * 分居两处，而它们的正确性互相依赖。
     * <p>🛑 这是本仓<b>第三处</b> JSON 序列化落点（前两处：
     * {@code customer/repository/Json}、{@code archive/domain/CaseArchiveRecord}）。
     * 已登记为可合并项：若将来抽出公共工具，三处应一并迁移。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> OBJ_MAP = new TypeReference<>() {
    };

    /**
     * 签署块 → JSON 文本（供 {@code ?::jsonb}）。
     *
     * <p>🛑 序列化失败必须<b>抛</b>（{@code 9001} 口径断裂），不得回落成 {@code "{}"}：
     * {@code "{}"} 在本域的含义是"四方全缺"，而那是<b>一条业务上的实质断言</b>
     * （一份不该存在的协议）。把一次序列化故障伪装成"四方全缺"，
     * 会让一次代码缺陷以"这份协议没签"的面目出现 —— 归因完全错误。
     */
    public String signerJson() {
        try {
            return MAPPER.writeValueAsString(signer);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "协议签署登记：signer 序列化失败: " + e.getOriginalMessage()
                            + " —— 🛑 不静默回落成 '{}'：'{}' 在本域的含义是"
                            + "『四方签署全缺』，那是一条会被库层 RAISE 的实质断言。"
                            + "把序列化故障伪装成它，会让一次代码缺陷以"
                            + "『这份协议没签』的面目出现");
        }
    }

    /** 退款条款快照 → JSON 文本。失败处置同 {@link #signerJson}。 */
    public String refundClauseJson() {
        return toJson(refundClauseSnapshot, "refund_clause_snapshot");
    }

    /**
     * 违约条款快照 → JSON 文本，或 {@code null}（列可空）。
     *
     * <p>🛑 返回 {@code null} 而不是 {@code "null"} 字符串：调用方用
     * {@code ?::jsonb} 绑定时，前者落 NULL、后者落 JSON 的 null 值 —— 而
     * 本列的语义是"没有违约条款"，即 SQL NULL。
     */
    public String breachClauseJson() {
        return breachClauseSnapshot == null ? null : toJson(breachClauseSnapshot, "breach_clause_snapshot");
    }

    private static String toJson(Map<String, Object> m, String col) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "协议签署登记：" + col + " 序列化失败: " + e.getOriginalMessage());
        }
    }

    // ==================================================================
    // 反序列化（供 Ledger 读回 —— 与上面对称，同层同口径）
    // ==================================================================

    /**
     * JSON 文本 → 签署块。
     *
     * <p>🛑 解析失败必须<b>抛</b>，不得静默返回空 Map —— 空 Map 在本域的含义是
     * "四方全缺"，即<b>"这份协议不该存在"</b>；一次数据损坏若被读成空 Map，
     * 会让"库里躺着的东西坏了"伪装成"这份协议没签"。
     * <p>报 {@code 9001}（口径断裂）而非 {@code 1001}（入参校验）：
     * 问题在库里躺着的东西，不在调用方的请求体。
     */
    public static Map<String, String> parseSigner(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, STR_MAP);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "协议签署登记：signer 反序列化失败: " + e.getOriginalMessage()
                            + " —— 输入片段: " + snippet(json)
                            + "。🛑 不静默返回空 Map：空 Map 在本域是『四方全缺』这一实质断言");
        }
    }

    /** JSON 文本 → 条款快照（失败处置同 {@link #parseSigner}）。 */
    public static Map<String, Object> parseClause(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, OBJ_MAP);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "协议签署登记：条款快照反序列化失败: " + e.getOriginalMessage()
                            + " —— 输入片段: " + snippet(json));
        }
    }

    /** 取一段可放进错误消息的短片段（截断 200 字符 —— 与 {@code Json#snippet} 同口径）。 */
    private static String snippet(String json) {
        String s = json.replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(共 " + s.length() + " 字符)";
    }

    /** 四方签署的键集（供门禁机械核对 —— 不另抄一份键名，直接问枚举）。 */
    public static Set<String> signKeySet() {
        return new LinkedHashSet<>(AgreementSignKey.allCodes());
    }
}