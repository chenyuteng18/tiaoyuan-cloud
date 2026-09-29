# -*- coding: utf-8 -*-
import io
p='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
v=io.open(p,encoding='utf-8',newline='').read()
lines=v.split('\n')
probe='\n'.join(lines[183:375])
cov='\n'.join(lines[386:657])
t=io.open('dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java',encoding='utf-8',newline='').read()
r=io.open('dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/domain/DailyCoverageRecord.java',encoding='utf-8',newline='').read()
s=io.open('dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/service/BandRefetchService.java',encoding='utf-8',newline='').read()
PROBE=[
 'ON CONFLICT (probe_id) DO NOTHING',
 "PERFORM set_config('app.tenant_id', p_tenant_id::text, true);",
 '    IF v_ctx_before IS NOT NULL',
 'IF NOT (p_history_type = ANY (v_hist_types)) THEN',
 "RETURN 'ALREADY_EXISTS';",
 'IF p_retention_days IS NOT NULL AND p_retention_days < 0 THEN',
]
COV=[
 'ON CONFLICT (device_id, date) DO UPDATE',
 'RETURNING (xmax = 0) INTO v_was_insert;',
 'IF p_coverage_flag IS TRUE AND p_gap_reason IS NOT NULL THEN',
 '    IF p_gap_reason = \'not_worn\' AND p_is_wear = ANY (v_tech_iswear) THEN',
 '    v_tech_iswear  int[]  := ARRAY[-1, 255];',
 '       AND c.customer_id = p_customer_id',
 '    IF v_was_insert IS NULL THEN',
 '    IF v_ctx_before IS NOT NULL',
]
OTHER=[
 ("T",t,'        assertEquals(targetId, row.get("target_id"), "审计 target_id 必须正确");'),
 ("R",r,'        checkFlagVersusGapReason(coverageFlag, gapReason);'),
 ("R",r,'    public static DailyCoverageRecord of('),
 ("S",s,'                    outcome.firstTime() ? ACTION_COVERAGE_REGISTERED : ACTION_COVERAGE_REFRESHED,'),
]
print('== PROBE seg ==')
for c in PROBE: print(' %3d /V22=%3d :: %s' % (probe.count(c), v.count(c), c[:64]))
print('== COV seg ==')
for c in COV: print(' %3d /V22=%3d :: %s' % (cov.count(c), v.count(c), c[:64]))
print('== OTHER ==')
for tag,txt,c in OTHER: print(' %s %3d :: %s' % (tag, txt.count(c), c[:64]))
