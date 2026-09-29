-- ============================================================================
-- V18 缺口复现探针（B-11 系）—— 证明「device 零写入方」不是边界事实而是功能缺陷
--
-- 形态：完全按 D6 `POST /device-dispatches` 的**生产实现路径**走
--       （PlanService.createDispatch 的真实语句序列）：
--         ① planApproved 检查        → 需要 plan 行（且 status='approved'）
--         ② INSERT INTO device_dispatch（含 store_id / device_id / plan_version）
--       🛑 生产代码里**没有任何** device / store 存在性预检 —— 这一条本身就是证据。
--
-- 用 DO 块 + 嵌套 BEGIN/EXCEPTION 逐级捕获 SQLSTATE，故错误是**被观察到的**而非中断脚本。
-- 全程一个事务，最后 ROLLBACK（本探针一行都不留）。
-- ============================================================================
\set ON_ERROR_STOP off
\pset pager off

BEGIN;

SET LOCAL app.tenant_id = '18000000-0000-0000-0000-00000000000a';

INSERT INTO tenant (id, name, status)
VALUES ('18000000-0000-0000-0000-00000000000a', 'V18 缺口探针租户', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO store (store_id, tenant_id, name, franchise_type)
VALUES ('18000000-0000-0000-0000-000000000051',
        '18000000-0000-0000-0000-00000000000a', 'V18 探针门店', '直营');

\echo ''
\echo '=== 准备态 ==='
SELECT 'store=' || (SELECT count(*) FROM store)
    || ' device=' || (SELECT count(*) FROM device)
    || ' plan='   || (SELECT count(*) FROM plan)
    || ' dispatch=' || (SELECT count(*) FROM device_dispatch) AS prep;

\echo ''
\echo '=== ① 按 D6 生产路径写 device_dispatch（device 表零行 / plan 表零行）==='
DO $probe$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    BEGIN
        INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version, store_id,
                                     device_id, param_snapshot, result, event)
        VALUES ('18000000-0000-0000-0000-0000000000d1',
                '18000000-0000-0000-0000-00000000000a',
                '18000000-0000-0000-0000-0000000000a1', 1,
                '18000000-0000-0000-0000-000000000051',
                '18000000-0000-0000-0000-0000000000e1',
                '{}'::jsonb, '成功', 'device_push');
        RAISE NOTICE '① 竟然成功了（缺口不存在？）';
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        RAISE NOTICE '① 缺口复现 SQLSTATE=% 消息=%', v_state, v_msg;
    END;
END;
$probe$;

\echo ''
\echo '=== ② 手动补一行 device（裸 INSERT，模拟"如果有人建了档"）后重试 ==='
INSERT INTO device (device_id, tenant_id, store_id, model, status)
VALUES ('18000000-0000-0000-0000-0000000000e1',
        '18000000-0000-0000-0000-00000000000a',
        '18000000-0000-0000-0000-000000000051', '杠2', 'active');

DO $probe$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    BEGIN
        INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version, store_id,
                                     device_id, param_snapshot, result, event)
        VALUES ('18000000-0000-0000-0000-0000000000d2',
                '18000000-0000-0000-0000-00000000000a',
                '18000000-0000-0000-0000-0000000000a1', 1,
                '18000000-0000-0000-0000-000000000051',
                '18000000-0000-0000-0000-0000000000e1',
                '{}'::jsonb, '成功', 'device_push');
        RAISE NOTICE '② 建档后成功（只剩 plan 缺口）';
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        RAISE NOTICE '② 建档后仍然失败: % %', v_state, v_msg;
    END;
END;
$probe$;

\echo ''
\echo '=== ③ 对照：无租户上下文时 write（应与 23503 可区分的 42501）==='
DO $probe$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    PERFORM set_config('app.tenant_id', '', true);
    BEGIN
        INSERT INTO device (device_id, tenant_id, store_id, model)
        VALUES ('18000000-0000-0000-0000-0000000000e2',
                '18000000-0000-0000-0000-00000000000a',
                '18000000-0000-0000-0000-000000000051', '杠2');
        RAISE NOTICE '③ 无上下文竟然写进去了（RLS 失效？）';
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        RAISE NOTICE '③ 无上下文被 % 拒 —— 与 23503 可区分。消息=%', v_state, v_msg;
    END;
    PERFORM set_config('app.tenant_id', '18000000-0000-0000-0000-00000000000a', true);
END;
$probe$;

ROLLBACK;

\echo ''
\echo '=== 回滚后残留检查 ==='
SELECT 'tenant=' || (SELECT count(*) FROM tenant WHERE id = '18000000-0000-0000-0000-00000000000a')
    || ' store=' || (SELECT count(*) FROM store)
    || ' device=' || (SELECT count(*) FROM device)
    || ' dispatch=' || (SELECT count(*) FROM device_dispatch) AS cleanup;