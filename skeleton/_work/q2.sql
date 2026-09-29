SELECT s.relname AS src, con.conname, t.relname AS tgt,
       (SELECT a.attname FROM pg_attribute a WHERE a.attrelid=con.conrelid AND a.attnum=con.conkey[1]) AS src_col,
       (SELECT a.attname FROM pg_attribute a WHERE a.attrelid=con.confrelid AND a.attnum=con.confkey[1]) AS tgt_col,
       array_length(con.conkey,1) AS n
FROM pg_constraint con JOIN pg_class s ON s.oid=con.conrelid
JOIN pg_class t ON t.oid=con.confrelid JOIN pg_namespace n ON n.oid=s.relnamespace
WHERE n.nspname='public' AND con.contype='f'
  AND (s.relname,t.relname) IN (('store','region'),('customer','store'),('refund','customer'))
ORDER BY s.relname;
