# -*- coding: utf-8 -*-
import io
V22='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
v=io.open(V22,encoding='utf-8',newline='').read()
lines=v.split('\n')
for i,ln in enumerate(lines,1):
    s=ln.strip()
    if s.startswith('CREATE OR REPLACE FUNCTION') or s.startswith('$v22_') or s=='$v22_cov$;' or s.startswith('GRANT ') or s.startswith('REVOKE '):
        print(i, repr(ln))
print('total lines', len(lines))
