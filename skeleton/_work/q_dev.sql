SELECT c.relname AS source_table, con.conname, pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_class t ON t.oid = con.confrelid
 WHERE con.contype='f' AND t.relname='device'
 ORDER BY 1;
