\set ON_ERROR_STOP on
-- 探针：为 V16 的 JUnit 见证门禁取【真实数字】，特别是"自证对照值"。
-- 纪律：自证必须有判别力 —— 断言必须能区分"扫描器坏了"与"缺陷真的没有了"。
--       故要取"加了 RLS 过滤 vs 不加"两个数，二者必须不同。

\echo '=== 1. 单列外键，加 RLS 过滤（V16 修完应为 0）==='
SELECT count(*) AS 单列_加RLS过滤
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=1
  AND s.relrowsecurity AND t.relrowsecurity;

\echo '=== 2. 单列外键，不加 RLS 过滤（对照组：应 > 0，证明过滤器有判别力）==='
SELECT count(*) AS 单列_不加RLS过滤
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=1;

\echo '=== 3. 那些"不加过滤才数得到"的外键是谁（应指向 tenant 等非 RLS 表）==='
SELECT s.relname AS 源表, con.conname AS 约束名, t.relname AS 目标表,
       t.relrowsecurity AS 目标有RLS
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=1
  AND NOT (s.relrowsecurity AND t.relrowsecurity)
ORDER BY t.relname, s.relname, con.conname;

\echo '=== 4. 复合外键：①len=2 ②两端含tenant_id ③目标PK单列 → 期望 47 ==='
SELECT count(*) AS 复合_三条件
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
JOIN pg_constraint tp ON tp.conrelid = t.oid AND tp.contype='p'
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=2
  AND s.relrowsecurity AND t.relrowsecurity
  AND array_length(tp.conkey,1)=1
  AND EXISTS (SELECT 1 FROM unnest(con.conkey) k(attnum)
                JOIN pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=k.attnum
               WHERE a.attname='tenant_id')
  AND EXISTS (SELECT 1 FROM unnest(con.confkey) k(attnum)
                JOIN pg_attribute a ON a.attrelid=con.confrelid AND a.attnum=k.attnum
               WHERE a.attname='tenant_id');

\echo '=== 5. 复合外键：只 ①len=2 ②两端含tenant_id（去掉 ③）→ 期望 48（判别力对照）==='
SELECT count(*) AS 复合_去第三条
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=2
  AND s.relrowsecurity AND t.relrowsecurity
  AND EXISTS (SELECT 1 FROM unnest(con.conkey) k(attnum)
                JOIN pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=k.attnum
               WHERE a.attname='tenant_id')
  AND EXISTS (SELECT 1 FROM unnest(con.confkey) k(attnum)
                JOIN pg_attribute a ON a.attrelid=con.confrelid AND a.attnum=k.attnum
               WHERE a.attname='tenant_id');

\echo '=== 6. 谁是被 ③ 排除的那一个（期望 app_config_history→app_config）==='
SELECT s.relname AS 源表, con.conname, t.relname AS 目标表,
       array_length(tp.conkey,1) AS 目标PK列数
FROM pg_constraint con
JOIN pg_class s ON s.oid = con.conrelid
JOIN pg_class t ON t.oid = con.confrelid
JOIN pg_namespace n ON n.oid = s.relnamespace
JOIN pg_constraint tp ON tp.conrelid = t.oid AND tp.contype='p'
WHERE n.nspname='public' AND con.contype='f'
  AND array_length(con.conkey,1)=2
  AND s.relrowsecurity AND t.relrowsecurity
  AND array_length(tp.conkey,1) <> 1;

\echo '=== 7. 12 张载体表的 (tenant_id, pk) 唯一约束是否都在 ==='
SELECT x.t AS 表, EXISTS (
    SELECT 1 FROM pg_constraint c2
    WHERE c2.conrelid = to_regclass('public.'||quote_ident(x.t))
      AND c2.contype IN ('u','p')
      AND array_length(c2.conkey,1)=2
      AND EXISTS (SELECT 1 FROM unnest(c2.conkey) k(attnum)
                    JOIN pg_attribute a ON a.attrelid=c2.conrelid AND a.attnum=k.attnum
                   WHERE a.attname='tenant_id')) AS 有载体
FROM (VALUES ('customer'),('region'),('store'),('staff'),('device'),('scale'),
             ('band'),('band_sync_log'),('cycle_assessment'),('refund'),
             ('refund_statement'),('intake_profile_revision')) AS x(t)
ORDER BY x.t;