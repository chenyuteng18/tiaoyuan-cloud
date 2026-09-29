# -*- coding: utf-8 -*-
"""临时探针：系统检查 V18 每一条 prosrc 断言是否【仅凭注释即可满足】。"""
import re
from pathlib import Path

MD = Path("dy-app/src/main/resources/db/migration")
V18 = MD / "V18__device_provisioning_primitive.sql"
text = V18.read_text(encoding="utf-8")


def body(tag):
    a = text.index("$%s$" % tag) + len("$%s$" % tag)
    b = text.index("$%s$;" % tag)
    return text[a:b]


def strip_comments(s):
    out = []
    for ln in s.splitlines():
        i = ln.find("--")
        out.append(ln[:i] if i >= 0 else ln)
    return "\n".join(out)


# 把 PG ARE 的 \M 换成 Python 的等价写法（词尾 = 非标识符字符或行尾）
def pg(p):
    return p.replace(r"\M", r"(?![A-Za-z0-9_])")


CHECKS = [
    ("V18 (a0) rolbypassrls", "guard", r"rolbypassrls"),
    ("V18 (a1) pg_policies device", "guard", r"pg_policies[\s\S]{0,200}device"),
    ("V18 (b4) INSERT INTO device", "v18_register", r"INSERT\s+INTO\s+device\M"),
    ("V18 (b5) ON CONFLICT", "v18_register", r"ON\s+CONFLICT"),
    ("V18 (b6) ON CONFLICT (device_id)", "v18_register", r"ON\s+CONFLICT\s*\(\s*device_id\s*\)"),
    ("V18 (b7) RAISE..另一租户", "v18_register", r"RAISE\s+EXCEPTION[^;]*另一租户"),
    ("V18 (b8) FROM device..", "v18_register", r"FROM\s+device\M[^;]*device_id\s*=\s*p_device_id"),
    ("V18 (b9) INSERT..tenant_id", "v18_register", r"INSERT\s+INTO\s+device\M[^;]*tenant_id"),
    ("V18 (c1) UPDATE device", "v18_retire", r"UPDATE\s+device\M"),
    ("V18 (c2) DELETE FROM device", "v18_retire", r"DELETE\s+FROM\s+device\M"),
    ("V18 (c3) SET status =", "v18_retire", r"SET\s+status\s*="),
    ("V18 (c4) status <> retired", "v18_retire", r"status\s*<>\s*'retired'"),
    ("V18 (c5) UPDATE device..tenant", "v18_retire",
     r"UPDATE\s+device\M[^;]*tenant_id\s*=\s*p_tenant_id"),
    ("V18 (b1) set_config", "v18_register", r"set_config\s*\(\s*'app\.tenant_id'"),
    ("V18 (b2) assert_tenant_context", "v18_register", r"assert_tenant_context\s*\("),
]

for name, tag, pat in CHECKS:
    raw = text if tag == "guard" else body(tag)
    code = strip_comments(raw)
    m_raw = re.search(pg(pat), raw) is not None
    m_code = re.search(pg(pat), code) is not None
    flag = ""
    if m_raw and not m_code:
        flag = "   <== 注释即可满足（假绿风险）"
    elif not m_raw:
        flag = "   <== 正则本身未命中（写法差别，需人工确认）"
    print("%-34s raw=%-5s code=%-5s%s" % (name, m_raw, m_code, flag))