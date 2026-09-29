package com.diaoyuanyun.dy.app.agreement.domain;

import com.diaoyuanyun.dy.common.crypto.Hashes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * <b>调理协议书读侧快照</b> —— 从 {@code agreement} 读回来的<b>一整行</b>，
 * 与 {@link AgreementRecord}（写侧意图）互为两个方向。
 *
 * <h2>🛑🛑 为什么必须是一个"整行"对象，而不是几个各自返回标量的读法</h2>
 * 与归档域的 {@code CaseArchiveSnapshot} 逐字同理，但本域多一个歧义来源：
 * <pre>
 *   signerOf(tenantId, agreementId) → null
 *     到底是"这一行不存在 / 我看不见"，还是"这一行存在、而它的签署块是空的"？
 *   renderedHashOf(tenantId, agreementId) → null
 *     到底是"这一行不存在"，还是"这一行存在、而它的 hash 没填"？
 * </pre>
 * 这两个答案的<b>后果完全不同</b>：前者意味着签署登记没发生（应先排查写入方），
 * 后者意味着签署登记发生了而某个字段留空（是数据质量问题）。
 * 把它们合并成同一个 {@code null}，会造出本仓反复要防的形态 ——
 * <b>一个无法被追问的返回值</b>。
 * <p>⇒ 本 record 把"这一行在不在"编码成<b>对象本身的 null</b>
 * （{@code snapshotOf(...) == null} ⇔ 在本租户内看不见这一行），
 * 而把"这一行里的某个字段为空"编码成<b>字段的 null</b>。
 * 两个 null 从此在不同的层级上，歧义被结构消除而不是靠注释说明。
 *
 * <h2>🛑 它是"证据快照"，故字段只读且不提供任何 setter / with</h2>
 * 协议是<b>举证材料</b>（PRD C.1.5 §八「四方签署 … 未签不得首次调理或退款判定」、
 * §2.9「条款必须快照存储·不可覆盖」）。一份可以被"顺手改一下"的证据不是证据。
 * 本 record 的所有字段都是 {@code final}（Java record 的固有性质），
 * 且<b>刻意不提供 {@code withXxx} 一类的派生方法</b> —— 需要新值就构造新对象。
 *
 * <h2>🛑 为什么它【不】做形态校验（与 {@link AgreementRecord} 的关键差别）</h2>
 * 写侧做校验（{@link AgreementRecord} 在构造期阻断四方缺项），
 * 因为那时"拒绝一次非法写入"是<b>正确且可行</b>的。
 * <p>读侧<b>不能</b>做校验：库里的历史行可能是在规则收紧之前写入的，
 * 也可能是一次 RLS 失效期间被写进来的。此时"读回来就抛错"会让
 * <b>已经躺在库里的坏数据变得无法被看见</b> —— 于是排障、举证、数据修复
 * 全都失去了入口。这是本仓在 {@code Json#toMapLenient} 的注释里
 * 已经记录过的同一族纪律：「宽松读只供诊断/自描述路径」。
 * <p>🛑 但有一个例外，它刻意<b>保留</b>校验：{@link #signerOf()} 背后的 JSON 解析
 * <b>仍然会抛</b>（见 {@link AgreementRecord#parseSigner}）。理由是 JSON 的"坏"
 * 与"值不合规"是两件事：前者是<b>读不出来</b>（无法承载，必须报），
 * 后者是<b>读出来了但不达标</b>（可以承载，供人判断）。
 *
 * <h2>🛑🛑 {@code renderedHashRecomputedMatches}：读侧也必须能重算核验</h2>
 * 库层无法验 hash（无 {@code pgcrypto} ⇒ 无 {@code digest()}），
 * 故"这个 hash 确实是这份正文的摘要"这条性质<b>只能</b>在应用层守。
 * 写侧由 {@link AgreementRecord#renderedHashMatches()} 守；
 * 读侧则由本 record 的 {@link #renderedHashRecomputedMatches()} 守 ——
 * 它用在<b>对账场景</b>：库里躺了很久的一行，它的 hash 与正文现在还自洽吗。
 * <p>🛑 两者<b>不重复</b>：写侧守的是"进来的这一次是自洽的"，
 * 读侧守的是"库里这一行现在还是自洽的"（正文列可能被 DBA 改过、
 * 或被一次错误的 UPDATE 破坏；那不会经过写侧）。
 *
 * @param tenantId              该行所属租户（🛑 读回它使"我读到的到底是谁的行"可被断言）
 * @param agreementId           协议主键
 * @param customerId            签署客户（行级 scope 的承载者）
 * @param planId                绑定方案
 * @param planVersion           绑定方案版本
 * @param refundClause          退款条款快照（已解析；见 {@link #refundClauseOf()}）
 * @param breachClause          违约条款快照（已解析，可空）
 * @param signedAt              签署业务时刻
 * @param signer                四方签署（已解析；见 {@link #signerOf()}）
 * @param docTemplateId         文档模板 id（可空）
 * @param docTemplateVersion    文档模板版本（可空）
 * @param renderedSnapshot      渲染稿正文
 * @param renderedHash          渲染稿摘要（库层已 lower() 归一为小写）
 * @param createdAt             登记时刻（🛑 与 {@code signedAt} 不同 —— 离线补录会分叉）
 * @param createdBy             登记者标识（库层回落为 {@code agreement-offline-signing}）
 */
public record AgreementSnapshot(
        String tenantId,
        UUID agreementId,
        UUID customerId,
        UUID planId,
        int planVersion,
        Map<String, Object> refundClause,
        Map<String, Object> breachClause,
        Instant signedAt,
        Map<String, String> signer,
        UUID docTemplateId,
        Integer docTemplateVersion,
        String renderedSnapshot,
        String renderedHash,
        Instant createdAt,
        String createdBy) {

    /** 四方签署块（已解析的不可变副本）。 */
    public Map<String, String> signerOf() {
        return signer;
    }

    /** 退款条款快照（已解析的不可变副本）。 */
    public Map<String, Object> refundClauseOf() {
        return refundClause;
    }

    /** 违约条款快照（已解析的不可变副本；未填时为空 Map —— 🛑 列可空，空 Map ⇔ 未填）。 */
    public Map<String, Object> breachClauseOf() {
        return breachClause;
    }

    /**
     * 🛑 <b>四方签署是否齐备且非空白</b> —— 与 {@link AgreementSignKey#all()}
     * 同源（不另抄一份键名）。
     *
     * <p>供门禁自证用：门禁断言"写进去的协议读回来时四方签署仍是齐备的"，
     * 而不是只断言"行数 = 1"—— 后者抓不住"写入时签署块被静默改写"。
     */
    public boolean fourPartySignerComplete() {
        for (AgreementSignKey k : AgreementSignKey.all()) {
            String v = signer.get(k.code());
            if (v == null || v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 🛑 <b>读侧重算核验</b>：库里的 {@code renderedHash} 现在是否仍是
     * {@code renderedSnapshot} 的 SHA-256 摘要。
     *
     * <p>见类注释「读侧也必须能重算核验」：它守的是"库里这一行<b>现在</b>还是自洽的"。
     * 与 {@link AgreementRecord#renderedHashMatches()} 共用
     * {@link Hashes#sha256Utf8(String)} 的同一口径
     * （🛑 A-1 收口：该口径原先只存在于 {@code app.doctpl.service.DocFileService}，
     * 本 domain 类引用它意味着<b>领域模型反向依赖服务层</b> ⇒ 架构门禁 R4 抓红。
     * 修法是把摘要下沉到 {@code dy-common}，而不是给 R4 开例外）。
     *
     * <p>🛑 用 {@code equalsIgnoreCase}：库里已归一为小写，但读侧不假定
     * 历史行一定是小写（升级前的行可能是混合写法）。
     */
    public boolean renderedHashRecomputedMatches() {
        if (renderedSnapshot == null || renderedHash == null) {
            return false;
        }
        String recomputed = Hashes.sha256Utf8(renderedSnapshot);
        return recomputed.equalsIgnoreCase(renderedHash);
    }

    /** 是否绑定了文档模板（= 两个模板指针都非空；见 {@link AgreementRecord} 的成对纪律）。 */
    public boolean hasDocTemplate() {
        return docTemplateId != null && docTemplateVersion != null;
    }
}