-- ============================================================================
-- 审计日志表 (ADR-09): append-only + 哈希链 + 撤销 UPDATE/DELETE 权限 + 校验端点
-- 本轮只交付 SQL 结构, 不建库; dy-app 的 Flyway V1 已内联本结构。
-- 物理隔离: 审计日志与应用日志分表/分文件 (ADR-09)。
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_log
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    actor       VARCHAR(128)  NOT NULL,          -- 操作者 (staff_id 或 system)
    action      VARCHAR(64)   NOT NULL,          -- 动作类型
    target_type VARCHAR(64)   NOT NULL,          -- 目标实体类型
    target_id   VARCHAR(128)  NOT NULL,          -- 目标实体 id
    payload     TEXT,                             -- 事件详情 (JSON)
    -- 前一条记录哈希。链首约定值 = 64 个 '0'（是全 0，不是空串）。
    -- 本注释早期写作"链首为空串"，实现时改为全 0，理由: 空串使"链首"与"prev_hash 缺失"
    -- 两种状态不可区分，而全 0 是显式的"我前面没有任何东西"，且与 CHAR(64) 等宽便于肉眼比对。
    -- 权威定义在 dy-audit/.../chain/AuditChainHash.java 的 GENESIS_PREV_HASH。
    -- 注意: 该字面差异由 AuditLogTableContractTest 守护, 改这里也必须同步改实现。
    prev_hash   CHAR(64)      NOT NULL,
    hash        CHAR(64)      NOT NULL,          -- 本条 SHA-256 链哈希
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant ON audit_log (tenant_id, created_at);

-- 只读访问敏感个人信息也记审计 (ADR-09 逆向要求): 由业务在读取路径显式 append, 不由本 DDL 约束。
--
-- 撤销 UPDATE/DELETE 权限需在库级别执行 (示例, 由 DBA 落地):
--   REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM app_role;
--
-- 【TRUNCATE 必须一起撤销】只撤 UPDATE/DELETE 会留一个更省事的洞: TRUNCATE 既不需要
-- DELETE 权限也不需要 WHERE 条件，一条语句就能把整条链清空，且不留逐行痕迹。
-- 该前提已用真库实验验证（不依赖记忆）: 在"应用角色持有表 owner 且非超级用户"的形态下,
-- REVOKE 后 UPDATE/DELETE/TRUNCATE 三者均以 SQLSTATE 42501 被拒, INSERT 仍可;
-- REVOKE 前 UPDATE 是可用的 —— 证明这条撤销是【承重】的, 而非空操作。
-- 证据脚本: dy-audit/src/test/resources/sql/probe_owner_revoke.sql
--
-- 校验端点: 已实现于 dy-audit/.../service/JdbcAuditLogService.java 的 verifyChain()
--   —— 按"写入顺序"重算整条链并与存储 hash 比对, 返回 {valid, broken_at?};
--   写入侧用 pg_advisory_xact_lock 串行化取 prev_hash (跨实例有效, 空表亦然)。
--
-- ============================================================================
-- 【能力边界 / 过度承诺风险】verifyChain() 能发现"单点篡改", 不能防止"整体篡改"
-- ============================================================================
-- 【对外陈述必须按这一句】能力边界限于: 单点篡改必被发现; 不抗有写权限的级联重算。
-- 允许的说法: "审计链不可悄然篡改";
-- 禁止的说法: "审计链不可篡改" —— 绝对化表述, 与 Anti-Slop 纪律相悖, 且误导客户。
--
-- 本表的设计能够发现: 改任意一行的 payload/actor/... 或链字段、删任意一行的
--   【中间或链首】/ 删链首
--   -> 必然断链, 且 broken_at 精确定位到出问题的那一行。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 【能力边界之二 · 尾部截断盲区】2026-09-26 由 C-1 真请求实测挖出并登记
-- ─────────────────────────────────────────────────────────────────────────────
-- 删除链【尾部】的记录, 不会留下任何后继 —— 链只向后看, 尾部没有"后"。
--   故此时 verifyChain() 返回 valid=true, broken_at 为 null。
--   【这是攻击者最省事的动作】: 擦掉最近几步操作的痕迹, 比改中间一行安全得多。
--   现有 DoD3③（删中间行）/ ⑤（删链首）的共同点是"被删行后面还有行", 故必然失配;
--   尾部截断没有这个问题。
--   唯一可观测的迹象是 checked 条数, 而 checked 只能说明"现在有多少条",
--   不能说明"应该有多少条"。
--   已固化为可执行测试（同样【断言 valid=true】—— 断言的是坏消息）:
--     AuditChainGateTest#tail_truncation_is_not_detected_by_the_chain_alone_and_that_is_recorded
--   该用例逐字写明: 若有一天它变红, 说明补救机制已落地, 应把三处同步改写 ——
--   本文件 / AuditChainController 的 capability_envelope / 该用例自身。
--
-- 【两条盲区之外的第三条】外部锚点缺失:
--   要挡住"级联重算"需 ① HMAC 签名（密钥在 KMS）; 要挡住"尾部截断"需
--   ② 定期把 (记录条数, tail hash) 锚定到独立介质或可信时间戳服务。
--   ② 一旦落地, "checked 少于锚定条数"就成为可执行告警 —— 这正是
--   GET /audit/log-chain 把 checked 【恒】下发（而非只在失败时下发）的原因:
--   让那一天到来时不必改响应形状。二者均属 ADR-11 后续项, 已登记 backlog。
--   ③ 审计表落到 WORM(一次写入多次读取)存储, 技术上不可回写。
--
-- 但【不能】阻挡: 一个已经持有 UPDATE 权限的攻击者(例如先执行 REVOKE 再改数据的 DBA),
--   从被改动的那行开始【逐行重算 hash 并回写】, 使整条链重新自洽 ——
--   此时 verifyChain() 返回 valid=true, 被改的数据看起来就是原始记录。
--   原因: 本设计无密钥、无链外锚点。这是无密钥哈希链的固有性质, 不是实现缺陷。
--   已固化为可执行测试, 防止被误读为提供了"防篡改"这一绝对性质:
--     AuditChainGateTest#cascade_rehash_defeats_the_chain_and_this_is_documented_not_claimed_away
--   (该用例【断言 valid=true】—— 断言的是一个坏消息, 故意如此。)
--
-- 根因不是缺一个校验步骤, 而是缺少"链外信任根"。
-- 要挡住这一层需以下三条补救路径 —— 【属 ADR-11 后续项, 已登记 backlog】:
--   ① 每条记录附 HMAC 签名, 密钥在 KMS/应用侧, DBA 直连数据库也签不出来;
--   ② 定期把 (记录条数, tail hash) 锚定到独立介质或可信时间戳服务;
--   ③ 审计表落到 WORM(一次写入多次读取)存储, 技术上不可回写。
--
-- 【配套硬约束】readAllInOrder() 目前不带租户过滤(链完整性必须跨租户整体校验,
--   否则删掉某租户的行不会被发现) —— 这是有意设计。
--   🛑 该约束已于 2026-09-26（C-1）部分放开: GET /audit/log-chain 把校验结果
--   暴露为 HTTP 端点。放开的依据是"权限面恰为一个角色"——仅 hq（总部）持有
--   audit:read（PermissionRegistry），因为 audit_log 是全局单链、不属于任何单一租户，
--   而契约 F3 的 x-row-scope「门店仅本店、区域仅辖区」预设了"对象属于某租户"，
--   放 area/manager 进来会造出"本店 scope 的身份读全局对象"的语义裂缝。
--   【仍禁止】把它暴露给任何非 hq 角色、或在不加鉴权的情况下暴露。
-- ============================================================================
