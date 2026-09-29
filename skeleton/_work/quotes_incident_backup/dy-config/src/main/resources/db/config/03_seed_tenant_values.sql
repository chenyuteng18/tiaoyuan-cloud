-- ============================================================================
-- 03_seed_tenant_values.sql  —  新租户初始化：把总部默认值下发到租户层
--
-- 用法（psql）:
--   SET LOCAL app.tenant_id = '<uuid>';
--   SET LOCAL app.actor     = '<who>';
--   \i 03_seed_tenant_values.sql
--
-- 设计要点
--   1. 值【全部来自 config_slot.initial_value】—— 本脚本里不出现任何具体配置值，
--      也不会出现 "45" 这样的固定数量。新增/删除配置项只需改 02_slots_seed.sql。
--   2. 事务内必须已有 app.tenant_id：取 NULLIF(current_setting(...), '')::uuid，
--      未设上下文得到 NULL -> app_config.tenant_id NOT NULL 违约 (23502)，
--      即"没有租户上下文就写不进去"，而不是"默认写到某个租户"。
--   3. 幂等：ON CONFLICT DO NOTHING —— 已存在的租户值不会被初始化覆盖
--      （初始化不是配置变更，不得把用户改过的值重置回默认）。
-- ============================================================================
\set ON_ERROR_STOP on

INSERT INTO app_config (tenant_id, config_no, value, version, updated_by)
SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid,
       s.config_no,
       s.initial_value,
       1,
       COALESCE(NULLIF(current_setting('app.actor', true), ''), 'seed')
FROM config_slot s
ON CONFLICT (tenant_id, config_no) DO NOTHING;

-- 自证：本租户的生效值条数必须与声明条数一致，且 #42 不得存在。
DO $tenant_seed_guard$
DECLARE
    v_tenant UUID := NULLIF(current_setting('app.tenant_id', true), '')::uuid;
    n_slot   INTEGER;
    n_cfg    INTEGER;
    n_42     INTEGER;
    n_47     INTEGER;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION '未设置 app.tenant_id：没有租户上下文即拒绝写入（fail-closed）';
    END IF;

    SELECT count(*) INTO n_slot FROM config_slot;
    SELECT count(*) INTO n_cfg  FROM app_config WHERE tenant_id = v_tenant;
    SELECT count(*) INTO n_42   FROM app_config WHERE tenant_id = v_tenant AND config_no = 42;
    SELECT count(*) INTO n_47   FROM app_config WHERE tenant_id = v_tenant AND config_no = 47;

    IF n_cfg <> n_slot THEN
        RAISE EXCEPTION '租户 % 的生效值应为 % 条（=声明条数），实际 %', v_tenant, n_slot, n_cfg;
    END IF;
    IF n_42 <> 0 THEN
        RAISE EXCEPTION '租户 % 存在 #42 行 —— 空号被占用', v_tenant;
    END IF;
    -- #47 = optin-arch 线预留（尚未落 PRD）。它与 #42 性质不同（暂缺 ≠ 永久空号），
    -- 但在"当前不得出现"上一致；这里守住"实现先于裁定"不得发生（ADR 十五）。
    IF n_47 <> 0 THEN
        RAISE EXCEPTION '租户 % 存在 #47 行 —— 该号已预留但尚未落 PRD，不得先行落库', v_tenant;
    END IF;

    RAISE NOTICE 'tenant % seeded: % 条生效值, #42 空号保留, #47 预留缺席', v_tenant, n_cfg;
END;
$tenant_seed_guard$ LANGUAGE plpgsql;