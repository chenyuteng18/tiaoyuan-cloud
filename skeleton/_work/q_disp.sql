SELECT con.conname, t.relname AS ref_table, pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  JOIN pg_class c ON c.oid=con.conrelid
  LEFT JOIN pg_class t ON t.oid=con.confrelid
 WHERE c.relname='device_dispatch' AND con.contype='f' ORDER BY 1;
