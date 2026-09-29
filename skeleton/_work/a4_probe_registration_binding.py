#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
A-4 探针：RlsCoverageGateTest.ISOLATION_TESTS 的【值】（测试类 FQN）是否与【键】（表名）有咬合？

背景（实测抓出的真实缺口）：
  RlsCoverageGateTest 现有三条判据：
    ① 迁移含 tenant_id 的表 ⊆ 登记表（键集完整）
    ② 三源交叉：迁移 == 登记 == 真库
    ③ 登记的类可加载 且 该类 @Test 数 >= 5
  ⇒ ③ 只验"类存在且有 5 个测试"，**不验**"该类真的断言了这张表"。
  ⇒ 把一张新表登记到任一既有 >=5 @Test 的类上，三条判据全绿，而该表**行为隔离断言为零**。

本探针要回答：若新增第 ④ 条判据「登记的表名必须在该类源码里以标识形态出现」，
  会不会对**现有的 38 条登记**产生误伤（假红）？

判据形态候选（逐个实测覆盖率）：
  F1 双引号 Java 字面量： "<table>"
  F2 单引号 SQL 字面量： '<table>'
  F3 裸标识词（词边界）：  (?<![A-Za-z0-9_])<table>(?![A-Za-z0-9_])

只读，不写任何被测文件。
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent            # skeleton/_work
SKELETON = HERE.parent                            # skeleton
GATE = SKELETON / "dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java"
MIGRATION_DIR = SKELETON / "dy-app/src/main/resources/db/migration"

# 各模块的测试根（FQN -> 源文件路径的搜索面）
TEST_ROOTS = [
    SKELETON / m / "src/test/java"
    for m in ("dy-common", "dy-tenancy", "dy-security", "dy-web",
              "dy-audit", "dy-config", "dy-crypto", "dy-app")
]


def strip_comments(src: str) -> str:
    """剥掉 Java 行注释/块注释，保留字符串（与 RlsInjectionRealityGateTest 同款状态机）。"""
    out = []
    state = 0          # 0=代码 1=行注释 2=块注释 3=字符串
    i = 0
    while i < len(src):
        c = src[i]
        if state == 0:
            if c == "/" and i + 1 < len(src) and src[i + 1] == "/":
                state = 1
                i += 2
                continue
            if c == "/" and i + 1 < len(src) and src[i + 1] == "*":
                state = 2
                i += 2
                continue
            if c == '"':
                state = 3
            out.append(c)
        elif state == 1:
            if c == "\n":
                state = 0
                out.append(c)
        elif state == 2:
            if c == "*" and i + 1 < len(src) and src[i + 1] == "/":
                state = 0
                i += 2
                continue
        else:
            out.append(c)
            if c == "\\":
                if i + 1 < len(src):
                    out.append(src[i + 1])
                    i += 2
                    continue
            elif c == '"':
                state = 0
        i += 1
    return "".join(out)


def registered_pairs():
    """从门禁源码里解析 ISOLATION_TESTS 的登记对。"""
    txt = GATE.read_text(encoding="utf-8")
    # 只取 ISOLATION_TESTS 初始化块（Map.ofEntries( ... );）
    m = re.search(r"ISOLATION_TESTS\s*=\s*Map\.ofEntries\((.*?)\);", txt, re.S)
    if not m:
        raise SystemExit("❌ 未能在门禁源码里定位 ISOLATION_TESTS 初始化块")
    block = m.group(1)
    # 常量别名：private static final String V5 = "com...."; 等
    consts = dict(re.findall(
        r'private\s+static\s+final\s+String\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"', txt))
    consts.setdefault("V5", None)

    pairs = []
    for km, vm in re.findall(
            r'Map\.entry\(\s*"([^"]+)"\s*,\s*("[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s*\)', block):
        if vm.startswith('"'):
            fqn = vm.strip('"')
        else:
            if vm not in consts:
                raise SystemExit(f"❌ 常量 {vm} 未解析到值")
            fqn = consts[vm]
        pairs.append((km, fqn))
    return pairs


def find_source(fqn: str):
    rel = fqn.replace(".", "/") + ".java"
    for root in TEST_ROOTS:
        p = root / rel
        if p.is_file():
            return p
    return None


def tenant_tables_from_migrations():
    """与门禁同口径：解析 CREATE TABLE ... 体内含独立 tenant_id 列的表。"""
    create = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_][a-z0-9_]*)\s*\(",
                        re.I)
    tcol = re.compile(r"(^|[\s,(])tenant_id\s+", re.I)
    found = set()
    for p in sorted(MIGRATION_DIR.glob("V*__*.sql")):
        code = strip_comments(p.read_text(encoding="utf-8"))
        for m in create.finditer(code):
            table = m.group(1).lower()
            # 配对括号
            depth = 0
            inq = False
            body = None
            for j in range(m.end() - 1, len(code)):
                ch = code[j]
                if ch == "'":
                    inq = not inq
                elif not inq and ch == "(":
                    depth += 1
                elif not inq and ch == ")":
                    depth -= 1
                    if depth == 0:
                        body = code[m.end() - 1:j + 1]
                        break
            if body and tcol.search(body):
                found.add(table)
    for t in ("tenant", "schema_migration", "audit_log"):
        found.discard(t)
    return found


def main():
    pairs = registered_pairs()
    print(f"== A-4 探针：登记咬合实测 ==")
    print(f"   登记条数     : {len(pairs)}")
    tenant_tables = tenant_tables_from_migrations()
    print(f"   迁移租户表数 : {len(tenant_tables)}")
    print()

    missing_src = [fqn for _, fqn in pairs if find_source(fqn) is None]
    if missing_src:
        print(f"   🛑 找不到源码的类（{len(missing_src)}）: {sorted(set(missing_src))}")
    print()

    # 逐条判定
    f1_miss, f2_miss, f3_miss, none_miss = [], [], [], []
    cache = {}
    for table, fqn in sorted(pairs):
        p = find_source(fqn)
        if p is None:
            none_miss.append((table, fqn))
            continue
        if fqn not in cache:
            cache[fqn] = strip_comments(p.read_text(encoding="utf-8"))
        code = cache[fqn]
        if f'"{table}"' not in code:
            f1_miss.append((table, fqn))
        if f"'{table}'" not in code:
            f2_miss.append((table, fqn))
        if not re.search(r"(?<![A-Za-z0-9_])" + re.escape(table) + r"(?![A-Za-z0-9_])", code):
            f3_miss.append((table, fqn))

    def rep(name, miss):
        print(f"--- {name}: 未命中 {len(miss)} / {len(pairs)}")
        for table, fqn in miss:
            print(f"      {table:<24} -> {fqn.rsplit('.', 1)[-1]}")
        print()

    rep("F1 双引号 Java 字面量  \"<table>\"", f1_miss)
    rep("F2 单引号 SQL 字面量   '<table>'", f2_miss)
    rep("F3 裸标识词（词边界）", f3_miss)

    print("=== 结论 ===")
    print(f"  若新增判据用 F3（裸标识词），误伤 = {len(f3_miss)} 条")
    print(f"  若新增判据用 F2（单引号 SQL），误伤 = {len(f2_miss)} 条")
    print(f"  若新增判据用 F1（双引号 Java），误伤 = {len(f1_miss)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())