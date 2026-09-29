-- (d3) 判别力自证：把 V17 的【第 2 节授权段】整体摘掉 ⇒ (d3) 必须报红。
-- 做法：不依赖文件文本，直接在事务里把两个函数的 ACL 重置为"从未 GRANT/REVOKE"形态
--       （REVOKE ALL ... FROM PUBLIC, current_user 之后 proacl 变为 {owner=X/owner}，
--        仍非 NULL —— 故更彻底的模拟是直接 UPDATE pg_proc.proacl = NULL，
--        但那是系统目录写操作，需要超级用户且被禁止）。
-- ⇒ 改用【真的摘掉授权段】的方式：本文件只用于人工预探，正式判定在 118 的 I(d3)。
\set ON_ERROR_STOP on
BEGIN;

-- 先拍照：当前 proacl
SELECT 'BEFORE' AS phase, p.proname,
       coalesce(array_to_string(p.proacl,' | '),'(NULL)') AS proacl
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname='public' AND p.proname IN ('bind_band','unbind_band') ORDER BY 1;

-- 模拟"授权段未执行"：把 ACL 收回，parsing 后 proacl 会记录 REVOKE 的效果
REVOKE EXECUTE ON FUNCTION bind_band(uuid, uuid, uuid, text, text, date, boolean, text)
    FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION unbind_band(uuid, uuid, text, date) FROM PUBLIC;

SELECT 'AFTER_REVOKE_PUBLIC' AS phase, p.proname,
       coalesce(array_to_string(p.proacl,' | '),'(NULL)') AS proacl,
       has_function_privilege(current_user, p.oid, 'EXECUTE') AS has_exec
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname='public' AND p.proname IN ('bind_band','unbind_band') ORDER BY 1;

-- (d2) 在"PUBLIC 被撤 + owner 仍在"下是否仍然为真？
SELECT 'D2_STILL_TRUE' AS note,
       count(*) AS no_priv_count
  FROM (VALUES ('bind_band(uuid,uuid,uuid,text,text,date,boolean,text)'),
               ('unbind_band(uuid,uuid,text,date)')) AS x(sig)
 WHERE NOT has_function_privilege(current_user, x.sig, 'EXECUTE');

-- (d3) 的判据在此时是否为真（proacl 非 NULL 且含显式项）？
SELECT 'D3_ACL_PRESENT' AS note,
       coalesce(array_to_string(p.proacl,' | '),'(NULL)') AS proacl
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname='public' AND p.proname='bind_band';

ROLLBACK;