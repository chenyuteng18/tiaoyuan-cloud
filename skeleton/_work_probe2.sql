\pset pager off
SELECT
  (E'    UPDATE agreement SET x=1;' ~* '\yUPDATE\y\s+agreement\y') AS y_ok,
  (E'    UPDATE agreement SET x=1;' ~* '(^|[^A-Za-z_])UPDATE\s+agreement\b') AS cls_ok,
  (E'DELETE FROM agreement WHERE x=1;' ~* '(^|[^A-Za-z_])DELETE\s+FROM\s+agreement\b') AS cls_del,
  (E'xxUPDATE agreement' ~* '(^|[^A-Za-z_])UPDATE\s+agreement\b') AS cls_neg,
  (E'    UPDATE agreement SET x=1;' ~* '\mUPDATE\M\s+agreement\b') AS m_ascii
;
