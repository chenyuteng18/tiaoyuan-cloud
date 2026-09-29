\pset border 2
SELECT p.proname,
       p.prosecdef                              AS secdef,
       pg_get_userbyid(p.proowner)              AS owner,
       coalesce(array_to_string(p.proacl,' | '),'(NULL=default)') AS proacl,
       has_function_privilege(current_user, p.oid, 'EXECUTE') AS has_exec
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND p.proname IN ('bind_band','unbind_band')
 ORDER BY 1;