\pset pager off
\pset format unaligned
\pset tuples_only on
SELECT 'y_only=' || (E'  UPDATE agreement SET x=1;' ~* '\yUPDATE\y\s+agreement\y')::text;
SELECT 'm_only=' || (E'  UPDATE agreement SET x=1;' ~* '\mUPDATE\M\s+agreement')::text;
SELECT 'b_after_agreement=' || (E'agreement ' ~ 'agreement\b')::text;
SELECT 'y_after_agreement=' || (E'agreement ' ~ 'agreement\y')::text;
SELECT 'bs_is_backspace=' || ('a' || chr(8) || 'b' ~ 'a\bb')::text;
SELECT 'class_form=' || (E'  UPDATE agreement SET x=1;' ~* '(^|[^[:alnum:]_])UPDATE[[:space:]]+agreement([^[:alnum:]_]|$)')::text;
SELECT 'v17_band_b=' || (E'INSERT INTO band (id)' ~ 'INSERT\s+INTO\s+band\b')::text;
SELECT 'v17_band_y=' || (E'INSERT INTO band (id)' ~ 'INSERT\s+INTO\s+band\y')::text;
