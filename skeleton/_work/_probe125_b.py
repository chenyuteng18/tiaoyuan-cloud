# -*- coding: utf-8 -*-
import io
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
t=io.open(TST,encoding='utf-8',newline='').read()
lines=t.split('\n')
for i in (1712,1713,1714,1715,1716):
    print(i, repr(lines[i-1]))
