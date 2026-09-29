# -*- coding: utf-8 -*-
import io
p='dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql'
v=io.open(p,encoding='utf-8',newline='').read()
lines=v.split('\n')
probe='\n'.join(lines[183:375])   # register_sync_probe 段 (184..375)
cov='\n'.join(lines[386:657])
print('probe len',len(probe),'cov len',len(cov))
allc=[
 'ON CONFLICT (probe_id) DO NOTHING',
 "RETURN 'ALREADY_EXISTS';",
 "RETURN 'CREATED';",
 "PERFORM set_config('app.tenant_id', p_tenant_id::text, true);",
 'IF v_ctx_before IS NOT NULL',
 'IF NOT (p_history_type = ANY (v_hist_types)) THEN',
 'IF p_coverage_flag IS TRUE AND p_gap_reason IS NOT NULL THEN',
 'RETURNING (xmax = 0) INTO v_was_insert;',
 'ON CONFLICT (device_id, date) DO UPDATE',
]
print('%-62s %s %s %s' % ('anchor','V22','probe','cov'))
for c in allc:
    print('%-62s %4d %5d %4d' % (c[:60], v.count(c), probe.count(c), cov.count(c)))
print()
print('=== probe 段 (2) 一致性守卫上下文 250-266 ===')
for i in range(249,266):
    print(i+1, repr(lines[i]))
print('=== cov 段 (2) 一致性守卫定位 ===')
for i,ln in enumerate(lines,1):
    if 'v_ctx_before IS NOT NULL' in ln:
        print(i, repr(ln))
