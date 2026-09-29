import sys
sys.path.insert(0, '_work')
exec(open('_work/probe121.py', encoding='utf-8').read().split('print("== 121 前置探测 ==")')[0])

print("== 121b 对抗性 e2 验证 ==")
print()
print("--- GREEN 基准：原样必须全绿 ---")
show("C1", MIGTEXT)

print()
print("--- I(e2-update) DO NOTHING → DO UPDATE SET final_conclusion/staff_signs=excluded：期望 (e2) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (archive_id) DO UPDATE SET\n"
                 "        final_conclusion = excluded.final_conclusion,\n"
                 "        staff_signs      = excluded.staff_signs;")
show("I(e2-update)", t)

print()
print("--- I(e2-update2) 只改 metrics_trend（更隐蔽）：期望 (e2) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (archive_id) DO UPDATE SET\n"
                 "        metrics_trend = excluded.metrics_trend;")
show("I(e2-update2)", t)

print()
print("--- I(e2-update3) 只改 desensitize_authorized（最隐蔽）：期望 (e2) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (archive_id) DO UPDATE SET\n"
                 "        desensitize_authorized = excluded.desensitize_authorized;")
show("I(e2-update3)", t)
