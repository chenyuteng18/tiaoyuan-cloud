package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * B6 建档档案的一次<b>修订留痕</b>（V7 的 {@code intake_profile_revision} 一行）。
 *
 * <h2>契约逐字（本类的唯一依据）</h2>
 * <pre>
 *   B6 PATCH /customers/{id}/intake-profile
 *      description: 补充 + 修订（**append-only 留痕，不可覆盖**）
 *   data-dict §2.23 intake_profile:
 *      建档本体（客户终身：**补充/修订留痕，不可覆盖**）
 * </pre>
 * 三处上游（契约 B6 / data-dict §2.23 / V5 的 DDL 注释）说同一句话，
 * 而 V5 建的 {@code intake_profile} 带 {@code UNIQUE(tenant_id, customer_id)}
 * —— 物理上只可能有一行 ⇒ <b>"留痕"无处承载</b>。
 * 故 V7 增建 {@code intake_profile_revision} 账本，本 record 即它的一行。
 *
 * <h2>🛑 快照而非增量（本类最重要的一个决定）</h2>
 * 每行携带该次修订后的<b>完整字段映射</b>（{@link #snapshot()}），而不是 delta。
 * 理由：增量需要"重放"才能回答"当时是什么样"，而重放逻辑一旦与写入逻辑分叉
 * （例如某次修订的字段名拼错、或某字段的解析规则变了），
 * 重放出的历史会<b>静默地</b>与事实不符 —— 它不报错，只是给出一个错的过去。
 * 完整快照让每一行<b>自身即是一个可独立核对的事实</b>。
 *
 * <h2>🛑 本类<b>不含</b> {@code revisionType}（"补充"还是"修订"）</h2>
 * 上游出现过「补充」「修订」两个词，但<b>没有</b>任何取值表、也没有 CHECK 引用它们
 * （与 V2 的 {@code trigger_event} 同型：§2.25 表③ 是守卫表而非取值表）。
 * 按纪律<b>不自行发明枚举</b> ⇒ 该区分是**可从快照推导**的
 * （字段是新增还是改值：与上一版快照比对即得），不需要存一个列；
 * 存了反而会造出一份上游查无出处的取值集，并被下游当成权威使用。
 *
 * <h2>🛑 {@code supersedes} 是<b>新行指向旧行</b>，不是旧行指向新行</h2>
 * 「不可覆盖」的机械保证在此：更正不改旧行，而是由新行声明"我取代了谁"。
 * 反向指针（旧行指向新行）会要求一次 {@code UPDATE} —— 那正是"不可覆盖"被破的口子
 * （与 V6 的 {@code refund_statement.supersedes_statement_id} 同一套机制）。
 */
public record IntakeProfileRevisionRow(
        UUID revisionId,
        UUID customerId,
        int revisionNo,
        Map<String, Object> snapshot,
        UUID supersedesRevisionId,
        String reason,
        Instant recordedAt,
        UUID operatorId,
        String createdBy) {

    /** 构造期校验 —— 与 V7 的列约束一一对应。 */
    public IntakeProfileRevisionRow {
        if (revisionId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺主键 revision_id —— 它是 PK，也是 supersedes 指针的引用目标");
        }
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 customer_id —— V7 该列 NOT NULL，"
                            + "且 uq_ipr_revision_no 以它为『第几次修订』的判据之一");
        }
        if (revisionNo <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订序号必须 > 0，实际: " + revisionNo
                            + " —— V7 的 CHECK (revision_no > 0)，且序号从 1 起。"
                            + "🛑 用 0 或负数当起始会让『这个客户改过几次』这个问题无法回答");
        }
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 snapshot —— V7 该列 JSONB NOT NULL。"
                            + "🛑 空对象 {} 与 null 是两件事：前者 = 『该次修订后所有字段都无值』"
                            + "（合法：客户清空了档案，intake_profile 的列本就全部可空），"
                            + "后者 = 『一个字段都没记』，而『不可覆盖』恰恰要求每次修订都留下当时的样子");
        }
        if (recordedAt == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 recorded_at —— V7 该列 NOT NULL 且**无 DEFAULT**，这是刻意的："
                            + "它是『当时发生了什么』的锚点。若由库层在插入时取 now()，"
                            + "补录场景下它会指向补录时刻而非修订时刻，"
                            + "而『首诊门店锁定后，他店何时补充过什么』这个问题就答不准了");
        }
        if (operatorId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "档案修订留痕缺 operator_id —— U1 要求『他店只读共享 + 补充修订留痕』，"
                            + "而『谁补的』是留痕的核心要素。🛑 V7 该列可空是为了容纳历史系统行，"
                            + "写入路径**不得**利用这个可空性：一条无操作人的修订记录，"
                            + "在跨店争议复盘时无法回答『是谁在什么时候改了他店的档案』");
        }
        // 🛑 不能用 Map.copyOf：它在遇到 null【值】时抛 NullPointerException，
        //    而本快照恰恰【必然】含 null —— V5 的 intake_profile 每一列都可空
        //    （未采集的字段就是 null），snapshotOf() 忠实保留它们。
        //    后果：B6 对任何"部分填写"的档案恒 500 NullPointerException，
        //    且异常在 Spring 的 Map.ofEntries/copyOf 内部抛出，栈里看不到"快照含空值"这件事。
        //    故改用容忍 null 值的不可变副本（LinkedHashMap 保序 + 不允许改）。
        //    若此处改成"滤掉 null 值"，会让快照变成"只记录填过的字段" ——
        //    那与类注释「完整快照 vs 增量」的纪律直接冲突（字段后来被清空就无法从历史看出）。
        snapshot = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot));
    }

    /** 是否为"首次修订"（{@link #revisionNo()} = 1，通常与建档本体同一事务写入）。 */
    public boolean isFirstRevision() {
        return revisionNo == 1;
    }

    /**
     * 与上一版快照比对，得出<b>本次修订的性质</b>（替代 {@code revisionType} 列）。
     *
     * <p>返回的自描述刻意不落库 —— 它由快照本身推导（见类注释"不含 revisionType"）。
     * 用途：端点自描述、审计回溯、以及运维侧的"这次改了什么"。
     *
     * @param previous 上一版快照（首版传 {@code null}）
     */
    public Map<String, Object> describeDeltaVersus(Map<String, Object> previous) {
        Map<String, Object> added = new java.util.LinkedHashMap<>();
        Map<String, Object> changed = new java.util.LinkedHashMap<>();
        Map<String, Object> removed = new java.util.LinkedHashMap<>();
        Map<String, Object> prev = previous == null ? Map.of() : previous;
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            if (!prev.containsKey(e.getKey())) {
                added.put(e.getKey(), e.getValue());
            } else if (!java.util.Objects.equals(prev.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, Object> e : prev.entrySet()) {
            if (!snapshot.containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("revision_no", revisionNo);
        out.put("is_first", isFirstRevision());
        out.put("added", added);
        out.put("changed", changed);
        out.put("removed", removed);
        out.put("kind",
                isFirstRevision() ? "首次留痕（建档本体写入）"
                        : (added.isEmpty() && changed.isEmpty() && removed.isEmpty())
                        ? "无实质变化"
                        : (changed.isEmpty() && removed.isEmpty() ? "补充" : "修订"));
        out.put("kind_source",
                "由快照比对推导，不落库 —— 上游出现过『补充』『修订』两个词，"
                        + "但无取值表 / 无 CHECK 引用（与 V2 的 trigger_event 同型），"
                        + "按纪律不自行发明枚举");
        return out;
    }
}