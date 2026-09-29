#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
系统性缺口扫描：找出所有「零生产写入方，却被【有写入方的表】用外键引用」的表。

【为什么需要它】
  B-10（band）与 B-11（device）是**同一个形态**被逐个发现的 —— 这不可持续。
  本项目一贯的做法是"把一类事实变成可机械扫描的东西"，故本脚本把这条扫描做出来：
  它回答的不是"哪张表没有写入方"（那可能有正当理由），而是
  **"哪张表没有写入方，却已经有人在写它"** —— 后者才必然导致运行期 23503。

【判据】
  对每张表 T：
    · writers(T)      = 生产代码里出现 `INSERT INTO T` 的文件数（只扫 src/main）
    · migration_fills(T) = 迁移链里出现 `INSERT INTO T`（⇒ 该表由迁移填充）
    · rows(T)         = 真库行数
    · refs_by(T)      = 用外键引用 T 的表集合
    · 缺口 ⇔ writers(T) == 0
              且 NOT migration_fills(T)          ← 🛑 这一条是本脚本第一版的缺陷
              且 refs_by(T) 中存在 S 使 writers(S) > 0

  🛑 为什么必须排除"迁移填充"（第一版漏了它，把 `config_slot` 报成了缺口）：
     `config_slot` 是**配置槽位声明字典**（46 条），由 V14 迁移直接 INSERT 填充 ——
     它**本来就不该有生产写入方**（它是一份随迁移走的声明表，不是业务台账）。
     第一版只看"生产代码有没有 INSERT"⇒ 把它误报成"写了一行引用不存在的行"。
     **误报与漏报一样会毁掉门禁的可信度**：一个恒报红的门禁会被"习惯性忽略"，
     而本仓的基本纪律是"假红会被人看见并修掉，假绿不会" —— 但"总在假红"的
     门禁会训练人忽略它，效果等同于假绿。
     判据正确形态：**"零写入方"只在"没人负责写它"时才是缺口**；
     由迁移填充的表**有人在负责写**（迁移就是它的写入方）。
     本脚本同时输出 rows(T) 作为交叉印证（迁移填充 ⇒ 真库非零行）。
"""
import os
import re
import subprocess
from collections import defaultdict
from pathlib import Path

PSQL = os.environ.get("DY_PSQL_EXE", r"C:/Program Files/PostgreSQL/17/bin/psql.exe")
HOST = os.environ.get("DY_PG_HOST", "127.0.0.1")
PORT = os.environ.get("DY_PG_PORT", "5432")
USER = os.environ.get("DY_PG_USER", "diaoyuanyun")
PWD = os.environ.get("DY_PG_PASSWORD", "diaoyuanyun")
DB = os.environ.get("DY_PG_DB", "diaoyuanyun_dev")

HERE = Path(__file__).resolve().parent
SKELETON = HERE.parent
MODULES = ["dy-common", "dy-tenancy", "dy-security", "dy-web",
           "dy-audit", "dy-config", "dy-crypto", "dy-app"]
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"


def psql(sql: str) -> str:
    p = subprocess.run([PSQL, "-h", HOST, "-p", PORT, "-U", USER, "-d", DB,
                        "-X", "-A", "-F", "|", "-t", "-c", sql],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=dict(os.environ, PGPASSWORD=PWD))
    if p.returncode != 0:
        raise SystemExit("psql 失败: " + (p.stderr or ""))
    return p.stdout or ""


def all_tables():
    return [x for x in psql(
        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname='public' AND c.relkind='r' ORDER BY 1").splitlines() if x.strip()]


def fk_edges():
    """返回 [(source_table, target_table, constraint_name)]"""
    out = []
    for line in psql(
        "SELECT c.relname, t.relname, con.conname FROM pg_constraint con "
        "JOIN pg_class c ON c.oid=con.conrelid JOIN pg_class t ON t.oid=con.confrelid "
        "WHERE con.contype='f' ORDER BY 1,2").splitlines():
        if line.count("|") == 2:
            s, t, n = line.split("|")
            out.append((s, t, n))
    return out


def production_sources():
    """生产源码（只 src/main）—— 与门禁同口径。

    🛑 必须【保留字符串】：本仓 SQL 住在 Java 字符串常量里，
       而"表名出现在字面量里"正是本仓判"写入方存在"的口径（README 已写明：
       持久化 100% 走 JdbcTemplate + 静态 SQL 字符串）。
       ⇒ 故本扫描器必须用【保留字符串】的粒度 —— 剥掉的话什么都扫不到。
    """
    srcs = {}
    for m in MODULES:
        base = SKELETON / m / "src" / "main"
        if not base.is_dir():
            continue
        for f in base.rglob("*.java"):
            srcs[str(f.relative_to(SKELETON)).replace("\\", "/")] = f.read_text(
                encoding="utf-8", errors="replace")
    return srcs


def migration_fillers():
    """迁移链里被 INSERT 填充的表（⇒ 该表由迁移负责写，不是缺口）。

    🛑 判据正确形态：**"零写入方"只在"没人负责写它"时才是缺口**；
       由迁移填充的表有人在负责写（迁移就是它的写入方）。
    🛑 迁移里的 `--` 行注释必须剥掉：否则"注释里提到 INSERT INTO X"
       会被当成"迁移真的插了 X"—— 那就是判据被注释满足（本仓 V15 已踩过同型坑）。
    """
    fills = set()
    if not MIGRATION_DIR.is_dir():
        return fills
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    for p in files:
        text = p.read_text(encoding="utf-8", errors="replace")
        stripped = "\n".join(re.sub(r"--.*$", "", ln) for ln in text.splitlines())
        for m in re.finditer(r"INSERT\s+INTO\s+([A-Za-z_][A-Za-z0-9_]*)", stripped, re.I):
            fills.add(m.group(1))
    return fills


def row_counts(tables):
    if not tables:
        return {}
    sql = " UNION ALL ".join(f"SELECT '{t}' AS t, count(*)::text FROM {t}" for t in tables)
    out = {}
    for line in psql(sql).splitlines():
        if "|" in line:
            t, n = line.split("|", 1)
            out[t] = n
    return out


def main():
    tables = all_tables()
    edges = fk_edges()
    srcs = production_sources()
    mig_fills = migration_fillers()

    # ---- 每个表的写入方（按 Java 文件计）----
    writers = defaultdict(set)
    insert_re = {}
    for t in tables:
        insert_re[t] = re.compile(r"INSERT\s+INTO\s+" + re.escape(t) + r"(?![\w])", re.I)
    for path, text in srcs.items():
        for t in tables:
            if insert_re[t].search(text):
                writers[t].add(path)

    refs_by = defaultdict(set)
    for s, t, _ in edges:
        refs_by[t].add(s)

    zero = [t for t in tables if not writers[t] and t not in mig_fills]
    rows = row_counts(sorted(set(zero) | {"config_slot"}))

    print("=" * 100)
    print(f"扫描范围: {len(srcs)} 个生产源码文件 / {len(tables)} 张表 / {len(edges)} 条外键")
    print(f"迁移填充的表（{len(mig_fills)} 张）: {', '.join(sorted(mig_fills))}")
    print("=" * 100)

    print(f"\n零生产写入方【且未被迁移填充】的表: {len(zero)} 张")
    print(f"  {', '.join(zero)}\n")

    gaps = []
    for t in zero:
        refs = sorted(refs_by.get(t, set()))
        hot = [s for s in refs if writers[s]]
        if hot:
            gaps.append((len(hot), t, sorted(refs), sorted(hot)))

    gaps.sort(key=lambda x: (-x[0], x[1]))
    print("=" * 100)
    print(f"🛑 缺口（零写入方 + 非迁移填充，却被【有写入方】的表引用）: {len(gaps)} 张")
    print("=" * 100)
    for n, t, refs, hot in gaps:
        print(f"\n【{t}】 真库行数={rows.get(t, '?')}  被 {n} 张有写入方的表引用")
        for s in hot:
            w = sorted(writers[s])
            print(f"    ← {s}")
            for f in w[:3]:
                print(f"        writer: {f}")
            if len(w) > 3:
                print(f"        … 共 {len(w)} 个写入方文件")

    print("\n" + "=" * 100)
    print("无缺口的零写入表（引用方也都没写入方 ⇒ 尚未构成运行期失败路径）")
    print("=" * 100)
    benign = [t for t in zero if not [s for s in refs_by.get(t, set()) if writers[s]]]
    for t in sorted(benign):
        refs = sorted(refs_by.get(t, set()))
        print(f"  {t:30s} 行数={rows.get(t, '?'):>4s}  被引用: {refs if refs else '（无）'}")

    print("\n" + "=" * 100)
    print("交叉印证：被迁移填充但因故被本脚本排除的表")
    print("=" * 100)
    for t in sorted(mig_fills):
        if t in tables and not writers[t]:
            print(f"  {t:30s} 行数={rows.get(t, '?'):>4s}  ← 由迁移填充（无需生产写入方）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())