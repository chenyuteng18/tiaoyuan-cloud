exec(open('_work/probe121.py', encoding='utf-8').read().split('print("== 121 前置探测 ==")')[0])
print("== 121d I(e5-b) 注入后输出全文 ==")
anchor = "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';"
t = replace_body(MIGTEXT, "register", anchor, anchor + """
    IF (p_checklist ? 'handband_recorded_as_reference')
       AND (p_checklist ->> 'handband_recorded_as_reference') = 'false' THEN
        RAISE EXCEPTION '注入：手环项为 false 被反转为阻断（合规红线违规）';
    END IF;""")
ok, out = run_sql(t)
print("ok =", ok)
print(out[-3000:])
