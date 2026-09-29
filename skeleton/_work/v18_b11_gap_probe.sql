-- B-11 缺口复现探针（自建 store，完整证据链）
-- 与 B-10 同型：`device` 表在生产代码里【零写入方】，
-- 而 `device_dispatch` 有生产写入方（FulfillmentLedger.insertDeviceDispatch）。
\set ON_ERROR_STOP on
SET client_min_messages = notice;
BEGIN;

DO
$p$
DECLARE
    v_ta     uuid := 'b1100000-0000-0000-0000-000000000001';
    v_store  uuid := 'b1100000-0000-0000-0000-000000000051';
    v_dev    uuid := 'b1100000-0000-0000-0000-0000000000de';
    v_disp   uuid := 'b1100000-0000-0000-0000-0000000000d1';
    v_plan   uuid := 'b1100000-0000-0000-0000-0000000000e1';
BEGIN
    INSERT INTO tenant (id, name, status) VALUES (v_ta, 'B11探针租户', 'active')
        ON CONFLICT (id) DO NOTHING;

    PERFORM set_config('app.tenant_id', v_ta::text, true);

    -- 自建 store（store 必填：store_id/tenant_id/name/franchise_type）
    INSERT INTO store (store_id, tenant_id, name, franchise_type)
    VALUES (v_store, v_ta, 'B11探针门店', '直营');

    RAISE NOTICE '前置: store 行数 = %', (SELECT count(*) FROM store WHERE tenant_id = v_ta);
    RAISE NOTICE '前置: device 行数（全库） = %', (SELECT count(*) FROM device);

    -- ---- ① 未建档的 device_id 写 device_dispatch ----
    BEGIN
        INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version, store_id,
                                    device_id, param_snapshot, result, dispatched_at)
        VALUES (v_disp, v_ta, v_plan, 1, v_store, v_dev, '{}'::jsonb, '成功', now());
        RAISE NOTICE '🛑 ① 意外：未建档 device_id 竟然写成功（缺口不存在？）';
    EXCEPTION
        WHEN foreign_key_violation THEN
            RAISE NOTICE '✅ ① 缺口复现 SQLSTATE=% 消息=%', SQLSTATE, SQLERRM;
        WHEN insufficient_privilege THEN
            RAISE NOTICE '⚠️ ① 被 42501 拒（不同错因）: %', SQLERRM;
    END;

    -- ---- ② 对照：把 device 行建出来之后，同一条写必须成功 ----
    INSERT INTO device (device_id, tenant_id, store_id, model, status)
    VALUES (v_dev, v_ta, v_store, '杠2', 'active');

    BEGIN
        INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version, store_id,
                                    device_id, param_snapshot, result, dispatched_at)
        VALUES (v_disp, v_ta, v_plan, 1, v_store, v_dev, '{}'::jsonb, '成功', now());
        RAISE NOTICE '✅ ② 建档后同一条写成功 —— 对照成立（缺口真的由 device 缺行造成）';
    EXCEPTION
        WHEN others THEN
            RAISE NOTICE '🛑 ② 建档后仍然失败: % %', SQLSTATE, SQLERRM;
    END;

    -- ---- ③ 无上下文的对照（用于证明 23503 与 42501 可区分）----
    PERFORM set_config('app.tenant_id', '', true);
    BEGIN
        INSERT INTO device_dispatch (dispatch_id, tenant_id, plan_id, plan_version, store_id,
                                    device_id, param_snapshot, result, dispatched_at)
        VALUES ('b1100000-0000-0000-0000-0000000000d2', v_ta, v_plan, 1, v_store, v_dev,
                '{}'::jsonb, '成功', now());
        RAISE NOTICE '🛑 ③ 无上下文竟然写成功';
    EXCEPTION
        WHEN insufficient_privilege THEN
            RAISE NOTICE '✅ ③ 无上下文被 42501 拒 —— 与 23503 可区分';
        WHEN others THEN
            RAISE NOTICE '③ 无上下文报: % %', SQLSTATE, SQLERRM;
    END;

    -- ---- ④ 跨租户：租户 B 的 device 引用租户 A 的 store ----
    PERFORM set_config('app.tenant_id', v_ta::text, true);
    DELETE FROM device_dispatch WHERE tenant_id = v_ta;
    DELETE FROM device WHERE tenant_id = v_ta;
    DELETE FROM store  WHERE tenant_id = v_ta;

    RAISE NOTICE '清理后: device=% store=% dispatch=%',
        (SELECT count(*) FROM device WHERE tenant_id = v_ta),
        (SELECT count(*) FROM store WHERE tenant_id = v_ta),
        (SELECT count(*) FROM device_dispatch WHERE tenant_id = v_ta);
END;
$p$;

ROLLBACK;