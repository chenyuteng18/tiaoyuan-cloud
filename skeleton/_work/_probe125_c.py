# -*- coding: utf-8 -*-
import io
def rd(p): return io.open(p,encoding='utf-8',newline='').read()
REC='dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/domain/DailyCoverageRecord.java'
TST='dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java'
V22='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
r,t,v=rd(REC),rd(TST),rd(V22)
cands=[
 (REC,r,'        throw new BizException(ErrorCode.GATE_MISSING,'),
 (TST,t,'                + "\\\\s+(?:IS\\\\s+NULL\\\\s+OR\\\\s+" + Pattern.quote(col) + "\\\\s+)?IN\\\\s*\\\\(").matcher(sql);'),
 (TST,t,'        int at = def.indexOf("= ANY (ARRAY[");'),
 (V22,v,'    IF v_was_insert IS NULL THEN'),
 (TST,t,'if (m.find()) {'),
]
for txt,c in cands:
    print(txt.count(c),'::',c[:90])
