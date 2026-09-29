\pset pager off
-- case_archive 列定义
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema='public' AND table_name='case_archive'
 ORDER BY ordinal_position;
-- case_archive 的 CHECK 约束
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.case_archive'::regclass AND contype='c';
-- refund 列定义
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema='public' AND table_name='refund'
 ORDER BY ordinal_position;
-- refund 的 CHECK 约束
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.refund'::regclass AND contype='c';
-- customer 的必要列（要建 customer 行当外键前置）
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema='public' AND table_name='customer'
 ORDER BY ordinal_position;
-- customer 的 CHECK 约束
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.customer'::regclass AND contype='c';
-- customer 的其它约束
SELECT conname, contype, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='public.customer'::regclass AND contype IN ('f','u','p');
-- tenant 列
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema='public' AND table_name='tenant'
 ORDER BY ordinal_position;