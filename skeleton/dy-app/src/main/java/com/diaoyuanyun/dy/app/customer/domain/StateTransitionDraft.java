package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次状态跃迁的<b>草稿</b> —— 对应 {@code customer_state_transition} 的一行（V2 建表）。
 *
 * <h2>🛑 本 record 的构造期断言 = 库层 CHECK 的镜像，两者必须一一对应</h2>
 * <pre>
 *  V2 L102~103: CONSTRAINT ck_cst_blocked_requires_missing_items
 *                 CHECK (guard_result &lt;&gt; 'blocked' OR missing_items IS NOT NULL)
 *  V2 L66~69  : to_state NOT NULL CHECK (to_state IN (14 态))
 *  V2 L62~65  : from_state 可空，非空时 ∈ 14 态
 *  V2 L79     : trigger_event NOT NULL（⚠️ 无 CHECK，权威件未枚举取值 —— 见下）
 *  V2 L73     : is_current NOT NULL DEFAULT false（唯一可更新字段）
 * </pre>
 *
 * <h2>🛑 blocked 的语义：写一行，但<b>不改当前态</b></h2>
 * V2 L81~82 逐字：「{@code passed} / {@code blocked}（§2.25 ③: blocked 记 403 缺失项，
 * <b>【不写 to_state 变更】</b>；但本表 append-only，blocked 的跃迁尝试"须留痕"
 * → <b>写一行、不改 is_current</b>）」。
 *
 * <p>这段注释容易读错，值得落到字段语义上：
 * <ul>
 *   <li>{@link #toState} 在 blocked 时是<b>"被尝试的目标态"</b>，不是"已到达的态" ——
 *       因为 CHK 要求 {@code to_state} NOT NULL，它必须有值；</li>
 *   <li>「不写 to_state 变更」的意思落在 {@code is_current} 上：
 *       blocked 行<b>不得</b>成为当前态（{@code is_current} 恒 false）。</li>
 * </ul>
 * 故本 record <b>刻意不含</b> {@code isCurrent} 字段：它是"是否首个/最新"的判定，
 * 由仓储在<b>同一事务</b>内（旧态置 false、新态置 true）决定，不由调用方传入。
 * 让调用方传它，等于把"唯一可更新字段"的决定权交给每一个调用点 ——
 * 而 {@code uq_cst_current} 这个部分唯一索引会在一处忘记置旧态为 false 时报冲突，
 * 且报的是一个约束名，不是"这里写错了"。
 *
 * <h2>🛑 {@code triggerEvent} 是自由文本，本类<b>不</b>为它建枚举</h2>
 * V2 L75~79 逐字：「{@code trigger_event}：§2.25 字段表写 'CHECK | 见下表 ③'，
 * 但 §2.25 的表 ③ 是「跃迁守卫 G1/G2」而非 trigger_event 取值表 —— 该 CHECK 的引用是
 * <b>【悬空的】</b>，权威件未逐项枚举 trigger_event 取值。按纪律<b>【不自行发明枚举】</b>，
 * 故此处只落 NOT NULL，暂不加 CHECK；待技术负责人/业务补齐取值集后走增项流程。」
 *
 * <p>故这里是 {@code String} 而<b>不是</b>枚举 —— 这是对上游缺口的忠实表达。
 * 若在此处"顺手枚举几个看起来合理的值"，就会造出一份<b>上游查无出处</b>的取值集，
 * 而它会被下游当成权威使用。
 *
 * <h2>审计字段</h2>
 * {@code created_at} / {@code updated_at} / {@code created_by} 为全表必带（V2 纪律⑤）。
 * 本 record 携带 {@link #createdBy}；{@code created_at} 由库层 {@code DEFAULT now()} 承担，
 * {@code updated_at} 仅在 {@code is_current} 翻转时由仓储更新。
 */
public record StateTransitionDraft(
        UUID transitionId,
        CustomerState fromState,
        CustomerState toState,
        String triggerEvent,
        boolean blocked,
        List<String> missingItems,
        UUID operatorId,
        Instant occurredAt,
        String refEntity,
        String refId,
        String createdBy) {

    /** 构造期校验 —— 与 V2 的 CHECK 一一对应（见类注释）。 */
    public StateTransitionDraft {
        if (transitionId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "状态跃迁缺主键 —— transition_id 是 PK，缺它则该行无法被引用");
        }
        if (toState == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "状态跃迁缺 to_state —— V2 该列 NOT NULL 且 CHECK ∈ 14 态（L66~69）。"
                            + "🛑 blocked 行也必须给 to_state（填【被尝试的目标态】）："
                            + "『不写 to_state 变更』约束的是 is_current，不是本字段");
        }
        if (triggerEvent == null || triggerEvent.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "状态跃迁缺 trigger_event —— V2 该列 NOT NULL（L79）。"
                            + "⚠️ 该列无 CHECK（权威件未枚举取值集），故此处只校验非空，"
                            + "【不发明】取值枚举");
        }
        // 与 ck_cst_blocked_requires_missing_items 一一对应（双向，比库层更严）
        List<String> items = missingItems == null ? List.of() : List.copyOf(missingItems);
        if (blocked && items.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "blocked 的跃迁尝试必须携带 missing_items —— V2 L102~103 的 "
                            + "ck_cst_blocked_requires_missing_items 明文要求；"
                            + "且契约 §2.0 forbidden-403 逐字『不得模糊报错』："
                            + "一次没有缺失项名的门禁拒绝，前端无从分支、交付物无从核对");
        }
        if (!blocked && !items.isEmpty()) {
            // 库层不拦这一侧（CHECK 只写了一个方向），但应用层必须拦：
            // passed 却带 missing_items，会让"这次到底有没有被拒"在事后读表时分不清。
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "passed 的跃迁不得携带 missing_items（实际: " + items + "）—— "
                            + "库层 CHECK 只写了 blocked ⇒ 必填 这一侧，而反向错配不会被库拦下。"
                            + "若放行，事后从表里读『这一行有没有 missing_items』这个判据就失效了："
                            + "一条 passed 行带着缺失项，看起来像被拒过，而 is_current 又是真的");
        }
        missingItems = items;
    }

    /** 是否为"首条跃迁"（建档首条，{@code from_state} 为 NULL）。 */
    public boolean isFirstTransition() {
        return fromState == null;
    }

    /** 便捷构造：passed 跃迁（无缺失项）。 */
    public static StateTransitionDraft passed(UUID transitionId,
                                             CustomerState fromState,
                                             CustomerState toState,
                                             String triggerEvent,
                                             UUID operatorId,
                                             Instant occurredAt,
                                             String refEntity,
                                             String refId,
                                             String createdBy) {
        return new StateTransitionDraft(transitionId, fromState, toState, triggerEvent,
                false, List.of(), operatorId, occurredAt, refEntity, refId, createdBy);
    }

    /**
     * 便捷构造：blocked 跃迁（携带逐字缺失项，且<b>不得</b>成为当前态）。
     *
     * <p>🛑 调用方<b>必须</b>由此路径构造 blocked 行，而不是把 {@code blocked=true}
     * 塞进 {@link #passed} 的变体 —— 前者在构造期就强制了 missing_items 非空。
     */
    public static StateTransitionDraft blocked(UUID transitionId,
                                              CustomerState fromState,
                                              CustomerState attemptedToState,
                                              String triggerEvent,
                                              List<String> missingItems,
                                              UUID operatorId,
                                              Instant occurredAt,
                                              String refEntity,
                                              String refId,
                                              String createdBy) {
        return new StateTransitionDraft(transitionId, fromState, attemptedToState, triggerEvent,
                true, missingItems, operatorId, occurredAt, refEntity, refId, createdBy);
    }
}