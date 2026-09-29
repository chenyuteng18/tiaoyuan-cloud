# -*- coding: utf-8 -*-
"""临时探针：统计 V18/V19 每条 prosrc 断言在【原文】与【剥离注释后】的匹配【计数】。

判定规则：
    raw_count > code_count  ⇒ 存在【只有注释能提供】的匹配
                             ⇒ 一旦代码处被改动/删除，该判据仍会通过 = 假绿风险
    raw_count == code_count ⇒ 判据的满足完全来自代码，安全
"""
import re
from pathlib import Path

MD = Path("dy-app/src/main/resources/db/migration")


def load(name):
    return (MD / name).read_text(encoding="utf-8")


def body(text, tag):
    a = text.index("$%s$" % tag) + len("$%s$" % tag)
    b = text.index("$%s$;" % tag)
    return text[a:b]


def strip_comments(s):
    out = []
    for ln in s.splitlines():
        i = ln.find("--")
        out.append(ln[:i] if i >= 0 else ln)
    return "\n".join(out)


def pg(p):
    return p.replace(r"\M", r"(?![A-Za-z0-9_])")


SPEC = {
    "V18": ("V18__device_provisioning_primitive.sql", [
        ("(b1) set_config", "v18_register", r"set_config\s*\(\s*'app\.tenant_id'"),
        ("(b2) assert_tenant_context", "v18_register", r"assert_tenant_context\s*\("),
        ("(b3) current_setting", "v18_register", r"current_setting\s*\(\s*'app\.tenant_id'"),
        ("(b4) INSERT INTO device", "v18_register", r"INSERT\s+INTO\s+device\M"),
        ("(b5) ON CONFLICT", "v18_register", r"ON\s+CONFLICT"),
        ("(b6) ON CONFLICT (device_id)", "v18_register",
         r"ON\s+CONFLICT\s*\(\s*device_id\s*\)"),
        ("(b7) RAISE..另一租户", "v18_register", r"RAISE\s+EXCEPTION[^;]*另一租户"),
        ("(b8) FROM device..", "v18_register",
         r"FROM\s+device\M[^;]*device_id\s*=\s*p_device_id"),
        ("(b9) INSERT..tenant_id", "v18_register",
         r"INSERT\s+INTO\s+device\M[^;]*tenant_id"),
        ("(c1) UPDATE device", "v18_retire", r"UPDATE\s+device\M"),
        ("(c2) DELETE FROM device", "v18_retire", r"DELETE\s+FROM\s+device\M"),
        ("(c3) SET status =", "v18_retire", r"SET\s+status\s*="),
        ("(c4) status <> retired", "v18_retire", r"status\s*<>\s*'retired'"),
        ("(c5) UPDATE device..tenant", "v18_retire",
         r"UPDATE\s+device\M[^;]*tenant_id\s*=\s*p_tenant_id"),
    ]),
    "V19": ("V19__scale_provisioning_primitive.sql", [
        ("(b1) set_config", "v19_register", r"set_config\s*\(\s*'app\.tenant_id'"),
        ("(b2) assert_tenant_context", "v19_register", r"assert_tenant_context\s*\("),
        ("(b3) current_setting", "v19_register", r"current_setting\s*\(\s*'app\.tenant_id'"),
        ("(b4) INSERT INTO scale", "v19_register", r"INSERT\s+INTO\s+scale\M"),
        ("(b5) ON CONFLICT", "v19_register", r"ON\s+CONFLICT"),
        ("(b6) ON CONFLICT (scale_id)", "v19_register",
         r"ON\s+CONFLICT\s*\(\s*scale_id\s*\)"),
        ("(b7) RAISE..另一租户", "v19_register", r"RAISE\s+EXCEPTION[^;]*另一租户"),
        ("(b8) FROM scale..", "v19_register",
         r"FROM\s+scale\M[^;]*scale_id\s*=\s*p_scale_id"),
        ("(b9) INSERT..tenant_id", "v19_register", r"INSERT\s+INTO\s+scale\M[^;]*tenant_id"),
        ("(bb) RAISE..不可覆盖", "v19_register", r"RAISE\s+EXCEPTION[^;]*不可覆盖"),
        ("(b12) max(scale_version)", "v19_register", r"max\s*\(\s*scale_version\s*\)"),
        ("(c1) UPDATE scale", "v19_deprecate", r"UPDATE\s+scale\M"),
        ("(c2) DELETE FROM scale", "v19_deprecate", r"DELETE\s+FROM\s+scale\M"),
        ("(c3) SET status =", "v19_deprecate", r"SET\s+status\s*="),
        ("(c4) status <> deprecated", "v19_deprecate", r"status\s*<>\s*'deprecated'"),
        ("(c5) UPDATE scale..tenant", "v19_deprecate",
         r"UPDATE\s+scale\M[^;]*tenant_id\s*=\s*p_tenant_id"),
    ]),
}

risky = []
for ver, (fname, checks) in SPEC.items():
    text = load(fname)
    print("=== %s ===" % ver)
    for name, tag, pat in checks:
        raw = body(text, tag)
        code = strip_comments(raw)
        rr = re.findall(pg(pat), raw)
        cc = re.findall(pg(pat), code)
        flag = ""
        if len(rr) > len(cc):
            flag = "   <== 🛑 假绿风险：注释也能满足（raw=%d code=%d）" % (len(rr), len(cc))
            risky.append((ver, name, len(rr), len(cc)))
        print("  %-32s raw=%d code=%d%s" % (name, len(rr), len(cc), flag))

print()
print("=== 汇总：假绿风险清单 ===")
for ver, name, r, c in risky:
    print("  %s %-32s raw=%d code=%d" % (ver, name, r, c))
if not risky:
    print("  （无）")