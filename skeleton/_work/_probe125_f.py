# -*- coding: utf-8 -*-
import io
def rd(p): return io.open(p,encoding='utf-8',newline='').read()
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
V22='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
t,v=rd(TST),rd(V22)
lines22=v.split('\n')
cov='\n'.join(lines22[386:657])   # register_daily_coverage 段
print('=== cov 段长度 ===', len(cov), 'lines', cov.count('\n')+1)
cands=[
 (TST,t,'                + "\\\\s+(?:IS\\\\s+NULL\\\\s+OR\\\\s+" + Pattern.quote(col) + "\\\\s+)?IN\\\\s*\\\\(").matcher(sql);'),
 (TST,t,'        if (at >= 0) {'),
 (V22,v,'    v_tech_iswear  int[]  := ARRAY[-1, 255];'),
 (cov,cov,'    v_tech_iswear  int[]  := ARRAY[-1, 255];'),
 (cov,cov,'        ON CONFLICT (device_id, date) DO UPDATE'),
]
for txt,c in cands:
    print(txt.count(c),'::',c[:100])
print('=== cov 段头尾 ===')
print(repr(cov.split(chr(10))[0]))
print(repr(cov.split(chr(10))[-1]))
