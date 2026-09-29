package com.diaoyuanyun.dy.audit.domain;

import java.time.Instant;

/**
 * 审计日志条目 (ADR-09)。append-only, 含哈希链字段 {@code prevHash}/{@code hash}
 * 支撑<b>篡改发现</b>校验。
 *
 * <p><b>措辞纪律（刻意不用"防篡改"）</b>：这两个字段能支撑的性质限于
 * "单点篡改必被发现并精确定位"；<b>不</b>抗"有 UPDATE 权限者从被改行起逐行重算 hash
 * 回写"的级联篡改（无密钥、无链外锚点的固有局限，见
 * {@code AuditLogService.verifyChain()} 与 {@code AuditChainGateTest} 的级联用例）。
 * 故此处写"篡改发现"而非"防篡改" —— 后者是绝对化表述，会构成对客户的能力误导。
 */
public record AuditLog(
        String id,
        String tenantId,
        String actor,
        String action,
        String targetType,
        String targetId,
        Instant at,
        String payload,
        String prevHash,
        String hash) {
}
