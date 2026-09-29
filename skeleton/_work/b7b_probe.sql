WITH t(b) AS (VALUES ('    v_sign_keys      text[] := ARRAY[
        ''customer'',            -- comment A
        ''meridian_therapist'',  -- comment B
        ''therapist'',           -- comment C
        ''store_owner''          -- comment D
    ];'::text)),
n AS (SELECT regexp_replace(regexp_replace(b, '--[^\n]*', '', 'g'), '\s+', ' ', 'g') AS s FROM t),
m AS (SELECT s, substring(s from 'ARRAY\s*\[\s*''customer''[^\]]*\]') AS seg FROM n)
SELECT length(seg) AS seg_len,
       (length(s) - length(replace(s, '''', ''))) AS quotes_all,
       (length(seg) - length(replace(seg, '''', ''))) AS quotes_seg,
       seg AS seg_text
FROM m;