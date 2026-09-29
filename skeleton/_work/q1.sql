SELECT count(*) AS leftover_single_col FROM pg_constraint con
 JOIN pg_class s ON s.oid=con.conrelid JOIN pg_class t ON t.oid=con.confrelid
 JOIN pg_namespace n ON n.oid=s.relnamespace
 WHERE n.nspname='public' AND con.contype='f' AND array_length(con.conkey,1)=1
   AND s.relrowsecurity AND t.relrowsecurity;
