# -*- coding: utf-8 -*-
import io
def rd(p): return io.open(p,encoding='utf-8',newline='').read()
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
t=rd(TST)
cands=[
 '        if (head.startsWith("SELECT") || head.startsWith("WITH")) {',
 'assertEquals("UNIQUE (device_id, date)", covUq.get("def"),',
 '        int at = def.indexOf("= ANY (ARRAY[");',
 '"[a-z_][a-z0-9_]*"',
]
for c in cands:
    print(t.count(c),'::',c)
