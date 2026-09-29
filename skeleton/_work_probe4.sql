\pset pager off
\pset format unaligned
\pset tuples_only on
-- 验证 \b 修复后的 (b10)/(b11) 命中
SELECT 'upd=' || (E'    UPDATE agreement SET x=1;' ~* '\mUPDATE\M\s+agreement\M')::text;
SELECT 'del=' || (E'DELETE FROM agreement WHERE x=1;' ~* '\mDELETE\M\s+FROM\s+agreement\M')::text;
SELECT 'ins=' || (E'INSERT INTO agreement (a) VALUES (1);' ~* '\mINSERT\M\s+INTO\s+agreement\M')::text;
-- 反向：xxUPDATE 不应命中
SELECT 'neg=' || (E'xxUPDATE agreement SET x=1;' ~* '\mUPDATE\M\s+agreement\M')::text;
-- agreement_pkey 是否单列主键
SELECT 'pkey=' || string_agg(a.attname, ',' ORDER BY a.attnum)
  FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey)
 WHERE i.indrelid='public.agreement'::regclass AND i.indisprimary;
