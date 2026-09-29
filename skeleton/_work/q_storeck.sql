SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
 WHERE conrelid='store'::regclass AND contype='c';
