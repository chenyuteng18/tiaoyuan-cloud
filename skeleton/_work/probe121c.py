exec(open('_work/probe121.py', encoding='utf-8').read().split('print("== 121 前置探测 ==")')[0])

print("== 121c 诊断 I(b6-c) 与 I(e5-b) ==")
print()

print("--- I(b6-c) 真 INSERT → (archive_id, tenant_id)：期望 (b6-负向) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (archive_id, tenant_id) DO NOTHING;")
ok, out = run_sql(t)
lab = label_of(out, LABELS)
print(f"  ok={ok} 标签={lab}")
for line in out.splitlines():
    if '自证失败' in line or '错误' in line:
        print('   ', line.strip()[:400]); break

print()
print("--- I(e5-b) 手环 false 被反转阻断：期望 (e5-b) ---")
anchor = "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';"
t = replace_body(MIGTEXT, "register", anchor, anchor + """
    IF (p_checklist ? 'handband_recorded_as_reference')
       AND (p_checklist ->> 'handband_recorded_as_reference') = 'false' THEN
        RAISE EXCEPTION '注入：手环项为 false 被反转为阻断（合规红线违规）';
    END IF;""")
ok, out = run_sql(t)
lab = label_of(out, LABELS)
print(f"  ok={ok} 标签={lab}")
for line in out.splitlines():
    if '自证失败' in line or '错误' in line:
        print('   ', line.strip()[:400]); break

print()
print("--- I(f2) 清场失效：期望 (f2) ---")
t = MIGTEXT.replace("        DELETE FROM case_archive WHERE tenant_id = v_ta;",
                    "        DELETE FROM case_archive WHERE tenant_id = v_ta AND false;", 1)
ok, out = run_sql(t)
lab = label_of(out, LABELS)
print(f"  ok={ok} 标签={lab}")
for line in out.splitlines():
    if '自证失败' in line or '错误' in line:
        print('   ', line.strip()[:400]); break
