-- ============================================================================
-- V18 设计探针（重写版）—— 每步错误在 DO 块内被捕获，事务不被终止
--
-- 问题 A：`ON CONFLICT (device_id) DO NOTHING` 在【既有行属于别的租户】时是什么行为？
--   若"静默 DO NOTHING 且 ROW_COUNT=0"，函数就会把一次**跨租户撞号**
--   报成 'ALREADY_EXISTS' —— 那是在对本租户撒谎（本租户里一行都没有）。
--
-- 问题 B：device_id 是【全局】主键（device_pkey），非 (tenant_id, device_id)。
--   故"同一台设备的一生只属于一个租户"是既有 schema 的既定事实 ⇒ 需不需要自建守卫？
-- ============================================================================
\set ON_ERROR_STOP off
\pset pager off

BEGIN;

INSERT INTO tenant (id, name, status)
VALUES ('18000000-0000-0000-0000-00000000000a', '探针租户 A', 'active'),
       ('18000000-0000-0000-0000-00000000000b', '探针租户 B', 'active')
ON CONFLICT (id) DO NOTHING;

-- 两租户各建一个门店（各自在自己的上下文里）
DO $p$
BEGIN
    PERFORM set_config('app.tenant_id', '18000000-0000-0000-0000-00000000000a', true);
    INSERT INTO store (store_id, tenant_id, name, franchise_type)
    VALUES ('18000000-0000-0000-0000-000000000151',
            '18000000-0000-0000-0000-00000000000a', '探针门店 A', '直营');

    PERFORM set_config('app.tenant_id', '18000000-0000-0000-0000-00000000000b', true);
    INSERT INTO store (store_id, tenant_id, name, franchise_type)
    VALUES ('18000000-0000-0000-0000-0000000001b1',
            '18000000-0000-0000-0000-00000000000b', '探针门店 B', '直营');

    PERFORM set_config('app.tenant_id', '18000000-0000-0000-0000-00000000000a', true);
END;
$p$;

\echo ''
\echo '=== 问题 B 实测: 租户 A 上下文里，device.store_id 指向【租户 B】的门店 ==='
DO $p$
DECLARE v_s text; v_m text;
BEGIN
    BEGIN
        INSERT INTO device (device_id, tenant_id, store_id, model)
        VALUES ('18000000-0000-0000-0000-0000000001ff',
                '18000000-0000-0000-0000-00000000000a',
                '18000000-0000-0000-0000-0000000001b1', '杠2');
        RAISE NOTICE 'B: 跨租户门店引用竟然成功了（device_store_id_fkey 未复合？）';
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_s = RETURNED_SQLSTATE, v_m = MESSAGE_TEXT;
        RAISE NOTICE 'B: 跨租户门店引用被 % 拒 —— %', v_s, v_m;
    END;
END;
$p$;

\echo ''
\echo '=== 让【租户 A】自己建一台设备（占据 device_id）==='
INSERT INTO device (device_id, tenant_id, store_id, model)
VALUES ('18000000-0000-0000-0000-0000000001e1',
        '18000000-0000-0000-0000-00000000000a',
        '18000000-0000-0000-0000-000000000151', '杠2');

\echo ''
\echo '=== 问题 A(正向): 租户 A 上下文里，同一 device_id 重复 ON CONFLICT DO NOTHING ==='
DO $p$
DECLARE v_rows int := -1; v_s text; v_m text;
BEGIN
    BEGIN
        INSERT INTO device (device_id, tenant_id, store_id, model)
        VALUES ('18000000-0000-0000-0000-0000000001e1',
                '18000000-0000-0000-0000-00000000000a',
                '18000000-0000-0000-0000-000000000151', '杠2')
        ON CONFLICT (device_id) DO NOTHING;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'A(正向): ROW_COUNT=% （0 ⇒ 幂等命中，返回 ALREADY_EXISTS 是正确的）', v_rows;
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_s = RETURNED_SQLSTATE, v_m = MESSAGE_TEXT;
        RAISE NOTICE 'A(正向): 抛 % —— %', v_s, v_m;
    END;
END;
$p$;

\echo ''
\echo '=== 🛑 问题 A(关键): 切到【租户 B】上下文，用 A 已占用的 device_id 做 ON CONFLICT DO NOTHING ==='
DO $p$
DECLARE v_rows int := -1; v_s text; v_m text;
BEGIN
    PERFORM set_config('app.tenant_id', '18000000-0000-0000-0000-00000000000b', true);
    BEGIN
        INSERT INTO device (device_id, tenant_id, store_id, model)
        VALUES ('18000000-0000-0000-0000-0000000001e1',
                '18000000-0000-0000-0000-00000000000b',
                '18000000-0000-0000-0000-0000000001b1', '杠2')
        ON CONFLICT (device_id) DO NOTHING;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'A(关键): ROW_COUNT=%  ⇒ 【静默 DO NOTHING】', v_rows;
    EXCEPTION WHEN others THEN
        GET STACKED DIAGNOSTICS v_s = RETURNED_SQLSTATE, v_m = MESSAGE_TEXT;
        RAISE NOTICE 'A(关键): 抛 % —— %  ⇒ 【数据库明确拒绝，不会静默】', v_s, v_m;
    END;
    RAISE NOTICE 'A(关键): 此刻租户 B 上下文里可见 device 行数 = %（0 ⇒ 若报 ALREADY_EXISTS 即撒谎）',
        (SELECT count(*) FROM device);
END;
$p$;

ROLLBACK;

\echo ''
\echo '=== 回滚后残留 ==='
SELECT 'tenant=' || (SELECT count(*) FROM tenant WHERE id IN
        ('18000000-0000-0000-0000-00000000000a','18000000-0000-0000-0000-00000000000b'))
    || ' store=' || (SELECT count(*) FROM store)
    || ' device=' || (SELECT count(*) FROM device) AS cleanup;