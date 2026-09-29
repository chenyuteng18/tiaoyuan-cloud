-- 探针：V17 自证 (b7)/(c7) 的正则【特异性】
-- 问题：bind_band 的步骤(4) SELECT 里也有 `AND tenant_id = p_tenant_id`，
--       若 (b7) 的正则是全文匹配，则"只把换机 UPDATE 的租户维度删掉"不会被抓住。
-- 本探针不依赖文件文本 —— 直接读已应用库里的 prosrc（= 迁移文本原文）。
\set ON_ERROR_STOP on
SET client_min_messages = notice;

DO
$probe$
DECLARE
    b  text;
    u  text;
    bm text;
    um text;
BEGIN
    SELECT prosrc INTO b FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'bind_band';
    SELECT prosrc INTO u FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'unbind_band';

    RAISE NOTICE '--- 现状：正则命中情况（未注入）---';
    RAISE NOTICE 'bind  旧判据 AND\s+tenant_id\s*=\s*p_tenant_id      = %', (b ~ 'AND\s+tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE 'bind  新判据 UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id = %', (b ~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE 'unbind旧判据 AND\s+tenant_id\s*=\s*p_tenant_id      = %', (u ~ 'AND\s+tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE 'unbind新判据 UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id = %', (u ~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE 'SQLSTATE-探针无需报错: 上面全是 boolean，无异常';

    -- ---- 注入 M1：只把 bind_band 换机 UPDATE 的租户维度删掉 ----
    bm := regexp_replace(b, 'WHERE\s+tenant_id\s*=\s*p_tenant_id', 'WHERE true', 'g');
    RAISE NOTICE '--- M1 bind 只删换机 UPDATE 的 tenant_id ---';
    RAISE NOTICE '注入生效 = %（应为 t）', (bm <> b);
    RAISE NOTICE '旧判据是否会抓住? = %（f 即"抓不住"= 假绿缺口）', (bm !~ 'AND\s+tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE '新判据是否会抓住? = %（t 即"抓得住"）', (bm !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id');

    -- ---- 注入 M2：只把 unbind_band 状态迁移 UPDATE 的租户维度删掉 ----
    um := regexp_replace(u,
            'AND\s+tenant_id\s*=\s*p_tenant_id(\s*AND\s+status\s*<>\s*''unbound'')',
            '\1', 'g');
    RAISE NOTICE '--- M2 unbind 只删状态迁移 UPDATE 的 tenant_id ---';
    RAISE NOTICE '注入生效 = %（应为 t）', (um <> u);
    RAISE NOTICE '旧判据是否会抓住? = %（f 即"抓不住"= 假绿缺口）', (um !~ 'AND\s+tenant_id\s*=\s*p_tenant_id');
    RAISE NOTICE '新判据是否会抓住? = %（t 即"抓得住"）', (um !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id');
END;
$probe$;