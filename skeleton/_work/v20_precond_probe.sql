\pset pager off
-- case_archive 的约束与外键形态
SELECT conname, contype, pg_get_constraintdef(oid) AS def
  FROM pg_constraint
 WHERE conrelid = 'public.case_archive'::regclass
 ORDER BY contype, conname;
-- 索引
SELECT indexname, indexdef FROM pg_indexes WHERE tablename='case_archive' ORDER BY indexname;
-- RLS
SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid='public.case_archive'::regclass;
-- 策略
SELECT polname, pg_get_expr(polqual, polrelid) AS using_, pg_get_expr(polwithcheck, polrelid) AS with_check
  FROM pg_policy WHERE polrelid='public.case_archive'::regclass;
-- customer 的 (tenant_id, id) 唯一载体是否存在
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.customer'::regclass AND contype IN ('u','p');
-- refund 的 (tenant_id, refund_id) 唯一载体
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.refund'::regclass AND contype IN ('u','p');
-- 现有函数
SELECT proname FROM pg_proc WHERE proname IN ('assert_tenant_context','register_device','register_scale','bind_band') ORDER BY proname;
-- 行数
SELECT count(*) AS case_archive_rows FROM case_archive;
SELECT count(*) AS refund_rows FROM refund;
