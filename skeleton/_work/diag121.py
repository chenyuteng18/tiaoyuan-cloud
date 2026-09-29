import re
from pathlib import Path
D=chr(36)
mig = Path('dy-app/src/main/resources/db/migration/V20__case_archive_provisioning.sql').read_text(encoding='utf-8')

def fb(tag):
    a = mig.index(D+'v20_'+tag+D)+len(D+'v20_'+tag+D)
    b = mig.index(D+'v20_'+tag+D+';')
    return mig[a:b]

REG=fb('register')
# 模拟代码态（剥注释）
code = '\n'.join(re.sub(r'--[^\n]*','',ln) for ln in REG.splitlines())

print('=== 场景：真 INSERT 改成 (archive_id, tenant_id) ===')
c2 = code.replace('ON CONFLICT (archive_id) DO NOTHING;','ON CONFLICT (archive_id, tenant_id) DO NOTHING;')
pos = r'INSERT\s+INTO\s+case_archive\M[^;]*(ON\s+CONFLICT\s+ON\s+CONSTRAINT\s+case_archive_pkey\M|ON\s+CONFLICT\s*\(\s*archive_id\s*\))'
neg = r'INSERT\s+INTO\s+case_archive\M[^;]*ON\s+CONFLICT\s*\([^)]*tenant_id'
for name,pat,txt in [('正向(b6)-改后',pos,c2),('负向(b6-neg)-改后',neg,c2),
                     ('正向(b6)-原始',pos,code),('负向(b6-neg)-原始',neg,code)]:
    m = re.search(pat, txt)
    print(f'  {name:20s}', 'HIT' if m else 'no', repr(txt[m.start():m.end()][:120]) if m else '')

print()
print('=== e5-b 注入锚点核对 ===')
warn = "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';"
print('  锚出现次数 =', REG.count(warn))
i = REG.index(warn)
print('  上下文:', repr(REG[i-100:i+80]))
