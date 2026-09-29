# -*- coding: utf-8 -*-
import io, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
MIG = os.path.join(HERE, "..", "dy-app", "src", "main", "resources", "db", "migration",
                   "V20__case_archive_provisioning.sql")
txt = io.open(MIG, encoding="utf-8-sig").read()
lines = txt.splitlines()
# guard DO block start
for i, l in enumerate(lines, 1):
    if l.strip() == "$v20_guard$":
        g = i
        break
print("guard $v20_guard$ at line", g)
# list all register_case_archive call sites with their following checklist (2-6 lines)
for i, l in enumerate(lines, 1):
    if re.search(r"v_mode := register_case_archive\(", l):
        rel = i - g
        nxt = " | ".join(x.strip() for x in lines[i:i+5])
        print(f"  call at mig {i} (guard rel {rel})")
        print(f"      {nxt[:230]}")
