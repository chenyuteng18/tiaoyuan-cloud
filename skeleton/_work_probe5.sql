\pset pager off
\pset format unaligned
\pset tuples_only on
SELECT 'proacl_reg=' || coalesce(proacl::text,'<NULL>') FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='register_agreement';
SELECT 'proacl_lat=' || coalesce(proacl::text,'<NULL>') FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='latest_agreement_of';
