# -*- coding: utf-8 -*-
import io
def rd(p):
    return io.open(p, encoding='utf-8', newline='').read()
REC='dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/domain/DailyCoverageRecord.java'
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
r=rd(REC); t=rd(TST)
cands=[
 (r,'GATE_MISSING,'),
 (t,'if (lit.matches("[a-z_][a-z0-9_]*") || lit.matches("[\\u4e00-\\u9fff]+")) {'),
 (t,'"[a-z_][a-z0-9_]*"'),
 (t,'"[\\u4e00-\\u9fff]+"'),
]
for txt,c in cands:
    print(txt.count(c), '::', c)
print('---- GATE_MISSING 全部出现 ----')
for i,line in enumerate(r.split('\n'),1):
    if 'GATE_MISSING' in line:
        print(i, repr(line))
