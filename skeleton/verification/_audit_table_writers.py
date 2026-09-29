# -*- coding: utf-8 -*-
"""
一次性审计脚本 v2：全量「迁移建的表 × 写入方」盘点。

v1 的两个缺陷（本轮自查抓出，与 N-8 的解析器教训同类）：
  ① 未剥 SQL 注释 ⇒ V1 第 49~51 行注释里的 "CREATE TABLE IF NOT EXISTS ..."
     被正则当成真建表，凭空造出伪表 "if"。
  ② 只扫 Java ⇒ 漏掉「由 SQL 触发器 / 函数写入」这一合法写入路径
     （如 app_config_history 由 app_config_record_history() 触发器写），
     会把合法设计误报成"零写入方"。

v2 修正：SQL 侧同样剥注释后再判定；写入方分「Java」与「SQL」两栏。
只读，不改任何源文件。
"""
import os
import re

ROOT = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
MIG = os.path.join(ROOT, "dy-app/src/main/resources/db/migration")
CFG = os.path.join(ROOT, "dy-config/src/main/resources/db/config")
MODULES = ["dy-common", "dy-tenancy", "dy-security", "dy-web",
           "dy-audit", "dy-config", "dy-crypto", "dy-app"]


def strip_java(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c in '"\'':
            q = c
            out.append(c); i += 1
            while i < n:
                if src[i] == "\\":
                    out.append(src[i:i + 2]); i += 2; continue
                out.append(src[i])
                if src[i] == q:
                    i += 1; break
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c); i += 1
    return "".join(out)


def strip_sql(src):
    """剥 -- 行注释 与 /* */ 块注释，但保留 $tag$ ... $tag$ 美元引用块内的原文。"""
    out, i, n = [], 0, len(src)
    dollar = None
    while i < n:
        if dollar is not None:
            if src.startswith(dollar, i):
                out.append(dollar); i += len(dollar); dollar = None; continue
            out.append(src[i]); i += 1; continue
        c = src[i]
        if c == "$":
            m = re.match(r"\$[A-Za-z_]*\$", src[i:])
            if m:
                dollar = m.group(0); out.append(dollar); i += len(dollar); continue
        if c == "'":
            out.append(c); i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "'":
                    i += 1; break
                i += 1
            continue
        if c == "-" and i + 1 < n and src[i + 1] == "-":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c); i += 1
    return "".join(out)


# ---------- 1) 建表清单（SQL 剥注释） ----------
CREATE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_][a-z0-9_]*)", re.I)
tables = {}
sql_sources = []          # (相对路径, 剥注释后 SQL)
for d in (MIG, CFG):
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".sql") or ".bak" in fn:
            continue
        p = os.path.join(d, fn)
        raw = open(p, encoding="utf-8", errors="replace").read()
        clean = strip_sql(raw)
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        sql_sources.append((rel, clean))
        if d == MIG:
            for m in CREATE.finditer(clean):
                tables.setdefault(m.group(1).lower(), []).append(fn)

# ---------- 2) Java 生产源码（剥注释） ----------
java_sources = []
for mod in MODULES:
    base = os.path.join(ROOT, mod, "src/main/java")
    if not os.path.isdir(base):
        continue
    for dp, _, fns in os.walk(base):
        for fn in fns:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dp, fn)
            raw = open(p, encoding="utf-8", errors="replace").read()
            java_sources.append((os.path.relpath(p, ROOT).replace("\\", "/"),
                                 strip_java(raw)))

INS = lambda t: re.compile(r"INSERT\s+INTO\s+" + re.escape(t) + r"\b", re.I)
SEL = lambda t: re.compile(r"(?:FROM|JOIN)\s+" + re.escape(t) + r"\b", re.I)
UPD = lambda t: re.compile(r"UPDATE\s+" + re.escape(t) + r"\b", re.I)

print("=" * 108)
print("表 × 写入方 盘点 v2：扫 Java %d 个、SQL %d 个（均剥注释）；迁移解析出 %d 张表"
      % (len(java_sources), len(sql_sources), len(tables)))
print("=" * 108)

zero = []
for t in sorted(tables):
    jw = [r for r, s in java_sources if INS(t).search(s)]
    sw = [r for r, s in sql_sources if INS(t).search(s)]
    rd = [r for r, s in java_sources if SEL(t).search(s)]
    up = [r for r, s in java_sources if UPD(t).search(s)]
    total_w = len(jw) + len(sw)
    flag = "   <<< 零写入方" if total_w == 0 else ""
    if total_w == 0:
        zero.append((t, tables[t], rd, up))
    print("%-30s java写%-3d sql写%-3d 读%-3d 改%-3d%s"
          % (t, len(jw), len(sw), len(rd), len(up), flag))

print()
print("=" * 108)
print("零写入方（Java 与 SQL 都无 INSERT）: %d 张" % len(zero))
print("=" * 108)
for t, migs, rd, up in zero:
    print("\n### %-28s 迁移: %s" % (t, ",".join(migs)))
    print("    读取方: %s" % ("; ".join(rd[:4]) or "（无）"))
    print("    更新方: %s" % ("; ".join(up[:4]) or "（无）"))