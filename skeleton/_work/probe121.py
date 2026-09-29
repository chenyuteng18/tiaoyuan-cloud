#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""121 前置探测：验证候选注入的"首报错标签"是否符合预期（不入库，全在 BEGIN…ROLLBACK 里）。"""
import os
import re
import subprocess
import tempfile
from pathlib import Path

PSQL = r"C:/Program Files/PostgreSQL/17/bin/psql.exe"
HOST, PORT = "127.0.0.1", "5432"
APP_USER, APP_PWD = "diaoyuanyun", "diaoyuanyun"
SUPER_USER, SUPER_PWD = "postgres", "postgres"
DEV_DB = "diaoyuanyun_dev"

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent
MIG = SKEL / "dy-app" / "src" / "main" / "resources" / "db" / "migration" / "V20__case_archive_provisioning.sql"
MIGTEXT = MIG.read_text(encoding="utf-8")

GUARD_RE = re.compile(r"DO\s*\n\$v20_guard\$")
D = "$"


def env_with(pwd):
    return dict(os.environ, PGPASSWORD=pwd)


def func_body(mig, tag):
    a = mig.index(D + "v20_" + tag + D) + len(D + "v20_" + tag + D)
    b = mig.index(D + "v20_" + tag + D + ";")
    return mig[a:b]


def replace_body(mig, tag, old, new, expect=1):
    body = func_body(mig, tag)
    n = body.count(old)
    if n != expect:
        print(f"   ⚠️  锚点在 {tag} 里出现 {n} 次（期望 {expect}）：{old[:70]!r}")
        return None
    return mig.replace(body, body.replace(old, new), 1)


def inject_before_guard(mig, injection):
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 找不到 $v20_guard$")
    return mig[:m.start()] + "\n-- ===== 注入 =====\n" + injection + "\n-- ===== 结束 =====\n\n" + mig[m.start():]


def run_sql(sql, as_super=False, timeout=600):
    user, pwd = (SUPER_USER, SUPER_PWD) if as_super else (APP_USER, APP_PWD)
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
        f.write(sql)
        f.write("\nROLLBACK;\n")
        path = f.name
    try:
        p = subprocess.run([PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", DEV_DB, "-X",
                            "-f", path, "-v", "ON_ERROR_STOP=on"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", env=env_with(pwd), timeout=timeout)
        return p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


LABELS = ["自证失败(a0)", "自证失败(a1)", "自证失败(a)", "自证失败(b)", "自证失败(b1)",
          "自证失败(b2)", "自证失败(b3)", "自证失败(b4)", "自证失败(b5)", "自证失败(b6)",
          "自证失败(b7)", "自证失败(b8)", "自证失败(b9)", "自证失败(b10)", "自证失败(b11)",
          "自证失败(b12b)", "自证失败(b12c)", "自证失败(b12)", "自证失败(c)", "自证失败(c1)",
          "自证失败(c2)", "自证失败(c3)", "自证失败(c4)", "自证失败(d)", "自证失败(d2)",
          "自证失败(d3)", "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
          "自证失败(e5-a)", "自证失败(e5-b)", "自证失败(e6)", "自证失败(e7)", "自证失败(e8)",
          "自证失败(e9-a)", "自证失败(e9-b)", "自证失败(e9-c)", "自证失败(e10)", "自证失败(e11)",
          "自证失败(e12)", "自证失败(f2)"]


def label_of(out, labels):
    hits = [(out.find(lab), -len(lab), lab) for lab in labels if lab in out]
    hits = [h for h in hits if h[0] >= 0]
    return min(hits)[2] if hits else "(无标签)"


def show(name, txt, as_super=False):
    if txt is None:
        print(f"[SKIP] {name} —— 锚点失效")
        return
    ok, out = run_sql(txt, as_super=as_super)
    lab = label_of(out, LABELS) if not ok else "(未报错/全绿)"
    print(f"[{'PASS' if not ok else 'GREEN'}] {name}  → 首报错标签 = {lab}")
    if not ok:
        # 打印首条报错行
        for line in out.splitlines():
            if "自证失败" in line or "前置失败" in line or "错误" in line:
                print("        " + line.strip()[:200])
                break
    return lab


print("== 121 前置探测 ==")
print(f"   迁移 = {MIG.name}  长度 {len(MIGTEXT)}")

print()
print("--- I(e3) 客户存在性检查被摘掉（IF v_cust <> 1 → IF false）：期望【假绿】 ---")
t = replace_body(MIGTEXT, "register", "    IF v_cust <> 1 THEN", "    IF false THEN")
show("I(e3)-probe", t)

print()
print("--- I(e6) 把幂等判定改成无条件 RETURN（RAISE 变死代码，静态判据全过）：期望 (e6) ---")
old_e6 = ("    IF v_mine = 1 THEN\n"
          "        RETURN 'ALREADY_EXISTS';\n"
          "    END IF;")
print("   锚计数 =", func_body(MIGTEXT, "register").count(old_e6))
t = replace_body(MIGTEXT, "register", old_e6, "    RETURN 'ALREADY_EXISTS';")
show("I(e6)-probe", t)

print()
print("--- I(e5-b) 手环 false 被阻断（键存在且为 false ⇒ RAISE）：期望 (e5-b) ---")
anchor = "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';"
print("   锚计数 =", func_body(MIGTEXT, "register").count(anchor))
t = replace_body(MIGTEXT, "register", anchor, anchor + """
    IF (p_checklist ? 'handband_recorded_as_reference')
       AND (p_checklist ->> 'handband_recorded_as_reference') = 'false' THEN
        RAISE EXCEPTION '注入：手环项为 false 被反转为阻断（合规红线违规）';
    END IF;""")
show("I(e5-b)-probe", t)

print()
print("--- I(b6) ON CONFLICT (archive_id) → ON CONFLICT ON CONSTRAINT case_archive_pkey（语义等价）：期望 (b6) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT ON CONSTRAINT case_archive_pkey DO NOTHING;")
show("I(b6)-probe", t)

print()
print("--- I(b8) 把 `archive_id = p_archive_id` 左右交换（语义等价）：期望 (b8) ---")
t = replace_body(MIGTEXT, "register", "     WHERE archive_id = p_archive_id;",
                 "     WHERE p_archive_id = archive_id;")
show("I(b8)-probe", t)
print()
print("--- I(b6-neg) 让 ON CONFLICT (archive_id) → ON CONFLICT (tenant_id, archive_id)（错误改法）：期望 (b6) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (tenant_id, archive_id) DO NOTHING;")
show("I(b6-neg)-probe", t)

print()
print("--- I(b6-neg2) → ON CONFLICT (archive_id, tenant_id)：期望 (b6-负向) ---")
t = replace_body(MIGTEXT, "register",
                 "    ON CONFLICT (archive_id) DO NOTHING;",
                 "    ON CONFLICT (archive_id, tenant_id) DO NOTHING;")
show("I(b6-neg2)-probe", t)

print()
print("--- I(b8-real) 真正摘掉那次读（IF v_mine = 1 分支前的 SELECT 改成 WHERE false）：期望 (b8) ---")
t = replace_body(MIGTEXT, "register",
                 "     WHERE archive_id = p_archive_id;",
                 "     WHERE false;")
show("I(b8-real)-probe", t)

print()
print("--- I(b7-msgonly) 把 RAISE 改成 RETURN 'X'（分支从 RAISE 变返回值）：期望 (b7) ---")
old = ("    RAISE EXCEPTION\n"
       "        'register_case_archive: archive_id % 已被【另一租户】占用")
t = replace_body(MIGTEXT, "register", old,
                 "    RETURN 'ALREADY_EXISTS';\n"
                 "    RAISE EXCEPTION\n"
                 "        'register_case_archive: archive_id % 已被【另一租户】占用")
show("I(b7-msgonly)-probe", t)
