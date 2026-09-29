package com.diaoyuanyun.dy.app.archive.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * <b>归档档案读侧快照</b> —— 从 {@code case_archive} 读回来的<b>一整行</b>，
 * 与 {@link CaseArchiveRecord}（写侧意图）互为两个方向。
 *
 * <h2>🛑🛑 为什么必须是一个"整行"对象，而不是四个各自返回标量的读法</h2>
 * 本域有三个读侧问题，若各自做成一个返回 {@code String} 的方法，会立刻出现
 * <b>null 的歧义</b>：
 * <pre>
 *   finalConclusionOf(tenantId, archiveId) → null
 *     到底是"这一行不存在 / 我看不见"，还是"这一行存在、而它没填结论"？
 * </pre>
 * 这两个答案的<b>后果完全不同</b>：前者意味着归档动作没发生（应先排查写入方），
 * 后者意味着归档动作发生了而结论留空（是 P0-14 / C.1.7 的数据质量问题）。
 * 把它们合并成同一个 {@code null}，会造出本仓反复要防的形态 ——
 * <b>一个无法被追问的返回值</b>。
 * <p>⇒ 本 record 把"这一行在不在"编码成<b>对象本身的 null</b>
 * （{@code snapshotOf(...) == null} ⇔ 在本租户内看不见这一行），
 * 而把"这一行里的某个字段为空"编码成<b>字段的 null</b>。
 * 两个 null 从此在不同的层级上，歧义被结构消除而不是靠注释说明。
 *
 * <h2>🛑 它是"证据快照"，故字段只读且不提供任何 setter / with</h2>
 * 归档档案是<b>举证材料</b>（PRD P0-14「四种结局全部强制归档」、
 * C.1.7「缺项→403 阻断结案」）。一份可以被"顺手改一下"的证据不是证据。
 * 本 record 的所有字段都是 {@code final}（Java record 的固有性质），
 * 且<b>刻意不提供 {@code withXxx} 一类的派生方法</b> —— 需要新值就构造新对象，
 * 让"改动证据"这件事在代码里显眼。
 *
 * <h2>🛑 为什么它【不】做形态校验（与 {@link CaseArchiveRecord} 的关键差别）</h2>
 * 写侧做校验（{@link CaseArchiveRecord} 在构造期阻断硬门禁缺项），
 * 因为那时"拒绝一次非法写入"是<b>正确且可行</b>的。
 * <p>读侧<b>不能</b>做校验：库里的历史行可能是在规则收紧之前写入的，
 * 也可能是一次 RLS 失效期间被写进来的。此时"读回来就抛错"会让
 * <b>已经躺在库里的坏数据变得无法被看见</b> —— 于是排障、举证、数据修复
 * 全都失去了入口。这是本仓在 {@code Json#toMapLenient} 的注释里
 * 已经记录过的同一族纪律：「宽松读只供诊断/自描述路径」——
 * 本 record 正是那种路径，故它<b>原样承载</b>库里的形态，
 * 由调用方决定"这份形态意味着什么"。
 * <p>🛑 但有一个例外，它刻意<b>保留</b>校验：{@link #checklistOf()} 与
 * {@link #signsOf()} 两个访问器背后的 JSON 解析<b>仍然会抛</b>（见
 * {@link CaseArchiveRecord#parseChecklist}）。理由是 JSON 的"坏"与"值不合规"
 * 是两件事：前者是<b>读不出来</b>（无法承载，必须报），
 * 后者是<b>读出来了但不达标</b>（可以承载，供人判断）。
 *
 * @param tenantId              该行所属租户（🛑 读回它使"我读到的到底是谁的行"可被断言）
 * @param archiveId             归档档案主键
 * @param customerId            归档对象客户（行级 scope 的承载者）
 * @param checklist             归档清单（已解析；见 {@link #checklistOf()}）
 * @param staffSigns            签名块（已解析；见 {@link #signsOf()}）
 * @param finalConclusion       最终结论（可空 —— 空 = "这一行没填结论"，见类注释）
 * @param metricsTrend          指标趋势快照 JSON 原文（可空）
 * @param desensitizeAuthorized 脱敏是否已单独授权（库列为 NOT NULL DEFAULT false）
 * @param archivedAt            归档时刻
 * @param createdBy             建档者标识（库层回落为 {@code case-archive-registration}）
 */
public record CaseArchiveSnapshot(
        String tenantId,
        UUID archiveId,
        UUID customerId,
        Map<String, Boolean> checklist,
        Map<String, String> staffSigns,
        String finalConclusion,
        String metricsTrend,
        boolean desensitizeAuthorized,
        Instant archivedAt,
        String createdBy) {

    /** 归档清单（已解析的不可变副本）。 */
    @Override
    public Map<String, Boolean> checklist() {
        return checklist;
    }

    /** 同 {@link #checklist()} —— 命名更贴近调用点（"把清单取出来"）。 */
    public Map<String, Boolean> checklistOf() {
        return checklist;
    }

    /** 签名块（已解析的不可变副本）。 */
    public Map<String, String> signsOf() {
        return staffSigns;
    }

    /**
     * 🛑 <b>5 项硬门禁是否全部为 {@code true}</b> ——
     * 与 {@link CaseArchiveChecklist#hardBlocking()} 同源（不另抄一份键名）。
     *
     * <p>供门禁自证用：门禁断言"写进去的档案读回来时 5 项硬门禁仍是 true"，
     * 而不是只断言"行数 = 1"—— 后者抓不住"写入时门禁值被静默改写"。
     */
    public boolean hardGatesSatisfied() {
        for (CaseArchiveChecklist item : CaseArchiveChecklist.hardBlocking()) {
            if (!Boolean.TRUE.equals(checklist.get(item.code()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 🛑 <b>手环警告项的值</b>（{@code null} = 写入时键缺失）。
     *
     * <p>刻意提供一个<b>专门的</b>访问器，而不是让调用方自己去
     * {@code checklist().get("handband_recorded_as_reference")} 拼键名字符串：
     * 手环这一项是本域唯一一个"<b>缺项不阻断</b>"的门禁
     * （P0-25 + README §5.3「三条不得触碰」③），它的读写路径越集中，
     * 越不容易在某处被顺手"反转成阻断"。
     */
    public Boolean handbandWarningValue() {
        return checklist.get(CaseArchiveChecklist.HANDBAND_RECORDED_AS_REFERENCE.code());
    }

    /** 是否已脱敏单独授权（读侧命名，避免与写侧 {@code desensitizeAuthorized()} 混淆）。 */
    public boolean isDesensitizeAuthorized() {
        return desensitizeAuthorized;
    }
}