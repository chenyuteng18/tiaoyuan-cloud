-- 探针：确认 PG POSIX ERE 里 [^\]] 与 [^]] 的语义差异
WITH t(b) AS (VALUES ('XX ARRAY[ ''customer'', ''meridian_therapist'', ''therapist'', ''store_owner'' ] YY'::text))
SELECT
  substring(b from 'ARRAY\s*\[\s*''customer''[^\]]*\]') AS with_backslash_escape,
  length(coalesce(substring(b from 'ARRAY\s*\[\s*''customer''[^\]]*\]'),'')) AS len_a,
  (length(coalesce(substring(b from 'ARRAY\s*\[\s*''customer''[^\]]*\]'),''))
   - length(replace(coalesce(substring(b from 'ARRAY\s*\[\s*''customer''[^\]]*\]'),''), '''', ''))) AS quotes_a,
  substring(b from 'ARRAY\s*\[\s*''customer''[^]]*\]') AS with_bracket_first,
  (length(coalesce(substring(b from 'ARRAY\s*\[\s*''customer''[^]]*\]'),''))
   - length(replace(coalesce(substring(b from 'ARRAY\s*\[\s*''customer''[^]]*\]'),''), '''', ''))) AS quotes_b
FROM t;