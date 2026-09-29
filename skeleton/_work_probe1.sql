\pset pager off
-- 1) 正则行为：\m \M 在 PG 正则里的语义
SELECT
  (E'    UPDATE agreement SET created_by = created_by;' ~* '\mUPDATE\M\s+agreement\b') AS m_indent,
  (E'UPDATE agreement SET x = 1;' ~* '\mUPDATE\M\s+agreement\b') AS m_plain,
  (E'DELETE FROM agreement WHERE x=1;' ~* '\mDELETE\M\s+FROM\s+agreement\b') AS m_del;
-- 2) 从真库读 register_agreement 的 prosrc，剥注释后测 (b10)/(b11)
WITH f AS (
  SELECT regexp_replace(prosrc, '--[^\n]*', '', 'g') AS code,
         length(prosrc) AS raw_len
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname='public' AND p.proname='register_agreement'
)
SELECT raw_len, length(code) AS code_len,
       (code ~* '\mUPDATE\M\s+agreement\b') AS hit_update,
       (code ~* '\mDELETE\M\s+FROM\s+agreement\b') AS hit_delete,
       (code ~* 'INSERT\s+INTO\s+agreement') AS hit_insert
  FROM f;
