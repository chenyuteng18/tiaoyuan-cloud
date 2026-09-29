# -*- coding: utf-8 -*-
import io
def rd(p): return io.open(p,encoding='utf-8',newline='').read()
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
V22='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
t=rd(TST); v=rd(V22)
lines22=v.split('\n')
cov='\n'.join(lines22[386:657])
c33='                + "\\s+(?:IS\\s+NULL\\s+OR\\s+" + Pattern.quote(col) + "\\s+)?IN\\s*\\(").matcher(sql);'
c34='        if (at >= 0) {'
print('c33 test=',t.count(c33))
print('c34 test=',t.count(c34))
print('tech V22full=',v.count('    v_tech_iswear  int[]  := ARRAY[-1, 255];'))
print('tech covseg=',cov.count('    v_tech_iswear  int[]  := ARRAY[-1, 255];'))
print('onconflict covseg=',cov.count('        ON CONFLICT (device_id, date) DO UPDATE'))
seg=cov.split('\n')
print('head=',repr(seg[0]))
print('tail=',repr(seg[-1]))
print('---- 154/155 context ----')
for i in range(148,160):
    print(i+387, repr(lines22[i]))
