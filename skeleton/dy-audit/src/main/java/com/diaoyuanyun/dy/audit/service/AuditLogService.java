package com.diaoyuanyun.dy.audit.service;

import com.diaoyuanyun.dy.audit.chain.AuditChainHash;
import com.diaoyuanyun.dy.audit.domain.AuditLog;

import java.util.List;

/**
 * 审计日志服务 (ADR-09 / ADR-11)。append-only: 仅提供写入, 不允许 UPDATE/DELETE。
 *
 * <p>本接口刻意<b>没有</b> {@code update}/{@code delete} 方法。这不是"忘了写"：
 * 少一个方法就少一条可被调用的篡改路径，而库里还额外用 {@code REVOKE UPDATE, DELETE}
 * 兜底（见 {@code audit_log.sql}）—— 应用层纪律 + 数据库层权限，两道各自独立。
 *
 * <p>真实表结构见 {@code dy-audit/src/main/resources/db/audit_log.sql}；
 * 链哈希规范见 {@link AuditChainHash}（v1 冻结）。
 */
public interface AuditLogService {

    /**
     * 追加一条审计日志 (仅追加)。
     *
     * <p><b>实现必须自己计算 {@code prev_hash} 与 {@code hash}，并忽略调用方
     * 传入的这两个字段</b> —— 若信任调用方送来的 {@code hash}，链就成了"写入方自觉"，
     * 篡改者只要在写入时送一个自洽的假 hash 即可。故实现侧不读 entry 的链字段
     * （见 {@code JdbcAuditLogService.append}）。
     *
     * @return 落库记录的 id（供调用方/校验端引用；由实现生成而非调用方提供）
     */
    String append(AuditLog entry);

    /** 读取全部记录, 按"写入顺序"升序（校验端与证据留档用）。 */
    List<AuditLog> readAllInOrder();

    /**
     * 重算<b>整条链</b>并与存储值比对。
     *
     * <h2>能力边界（一句话，对外陈述必须按这句）</h2>
     * <b>限于：单点篡改必被发现；不抗有写权限的级联重算。</b>
     * 允许的说法是"审计链<b>不可悄然篡改</b>"；<b>禁止</b>的说法是"审计链不可篡改"
     * —— 后者是绝对化表述，与本项目 Anti-Slop 纪律相悖，且是对客户的能力误导。
     *
     * <h2>{@code verifyChain() == valid} 能证明什么，以及<b>不能</b>证明什么</h2>
     *
     * <p><b>能证明</b>（有外部证据支撑）：链上的每一行都与其前驱自洽 ——
     * 即"没有任何一行是被单独改动过的"。删掉任意一行、改任意一行的数据/链字段，
     * 都会让某个 {@code prev_hash} 或重算 hash 对不上，从而被定位（见
     * {@code AuditChainGateTest} 的注入用例，每条都断言了精确的 {@code broken_at}）。
     *
     * <p><b>不能证明</b>（必须在对外陈述里说清，否则是过度承诺）：本方法
     * <b>不</b>能证明数据从未被篡改。本设计没有密钥、没有链外锚点，
     * 因此一个<b>已经拿到 UPDATE 权限</b>的攻击者可以从被改动的那行开始
     * <b>逐行重算 hash 并回写</b>，让整条链重新自洽，此时 {@code verifyChain()}
     * 会返回 {@code valid=true}。这不是实现缺陷，而是哈希链（无密钥版本）的固有性质，
     * 已被 {@code AuditChainGateTest.cascade_rehash_defeats_the_chain_...} 固化为可执行事实
     * —— 该测试<b>断言 valid=true</b>，即断言一个坏消息，专门用来防止今后有人
     * 把本方法误解为提供了"防篡改"这一绝对性质。
     *
     * <p>要挡住这一层，需要链外的信任根（<b>属 ADR-11 后续项，已登记 backlog</b>）：
     * ① 每条记录带 HMAC 签名，密钥在 KMS/应用侧而非数据库；
     * ② 定期把 {@code (count, tail_hash)} 锚定到独立介质或可信时间戳；
     * ③ 落到 WORM（一次写入多次读取）存储，技术上不可回写。
     *
     * <p>另外，本方法的结论建立在 {@code ORDER BY created_at ASC, id ASC} 之上。
     * 若两行的 {@code created_at} 完全并列，正确顺序不可判定，此时本方法<b>拒答</b>
     * （返回 {@code ORDER_AMBIGUOUS}）而不是随便挑一个顺序然后宣称链有效。
     *
     * @return {@code {valid, broken_at?}}；{@code broken_at} 为第一条断链记录的 id
     */
    com.diaoyuanyun.dy.audit.chain.ChainVerification verifyChain();
}