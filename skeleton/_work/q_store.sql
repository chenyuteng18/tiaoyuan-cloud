SELECT a.attname, format_type(a.atttypid,a.atttypmod) AS type, a.attnotnull AS notnull,
       pg_get_expr(d.adbin,d.adrelid) AS default_expr
  FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
  LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 WHERE c.relname='store' AND a.attnum>0 AND NOT a.attisdropped
 ORDER BY a.attnum;
