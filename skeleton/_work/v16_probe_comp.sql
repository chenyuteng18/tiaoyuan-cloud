-- 区分：本迁移产生的复合外键 vs 迁移前就存在的 tenant_id 复合外键
-- 判据：引用列的【非 tenant_id 部分】是否是目标表的主键（迁移产生的形态）
SELECT s.relname AS src, t.relname AS tgt,
       pg_get_constraintdef(con.oid) AS def,
       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
          FROM unnest(con.confkey) WITH ORDINALITY k(attnum,ord)
          JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum) AS ref_cols,
       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
          FROM unnest(tp.conkey) WITH ORDINALITY k(attnum,ord)
          JOIN pg_attribute a ON a.attrelid = tp.conrelid AND a.attnum = k.attnum) AS tgt_pk_cols
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_constraint tp ON tp.conrelid = t.oid AND tp.contype = 'p'
WHERE con.contype = 'f'
  AND array_length(con.conkey, 1) = 2
  AND pg_get_constraintdef(con.oid) LIKE 'FOREIGN KEY (tenant_id, %'
ORDER BY 1;