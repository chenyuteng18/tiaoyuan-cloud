#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十三 · 反向验证（Reversal Validation）—— 证明【组织主数据开通边界门禁】有牙齿。

## 本脚本要证明什么

批次十三实测抓出：本骨架**没有任何「建租户 / 建门店 / 建员工」的通路** ——
契约 40 个 path 里零开通端点、五张组织主数据表（tenant/region/store/staff/device）
在生产代码里零 `INSERT INTO`、无启动钩子、真库全 0 行。
⇒ 把骨架部署起来是个「能跑但空」的系统。

新建的 `ProvisioningBoundaryGateTest`（6 例）把这条边界从「代码注释里的散文」
变成「构建期事实」。但它是**「断言空集 == 空集」型门禁**，
天然有**退化成恒绿**的风险（正则失效 / 注释未剥 / 路径搬家）。
故必须证明它**真的会红**。

## 注入表（五组，覆盖门禁的每一条判据）

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | `StoreRepository` 里加一条 `INSERT INTO store`（在 Java 字符串里） | `tables_declared_as_not_provisioned_have_no_writer_in_production` | **边界被移动**（有人加了建店通路） |
| C2 | 契约 `/stores` 段加一个 `post:` | `the_contract_exposes_no_provisioning_endpoint` | **只读端点变可写**（凭空长出建门店通路） |
| C3 | 契约插入新 path `/tenants:` + `post:` | 同上（path 数 40→41 先红） | **契约被扩张**（开通端点出现 / path 数哨兵） |
| C4 | 父 pom 加 `mybatis` 依赖 | `no_orm_is_on_the_classpath_which_is_what_makes_the_literal_scan_sound` | **判据前提被推翻**（ORM 会隐式写表，字面量扫描失效） |
| C5 | V14 迁移里 DELETE 掉 `app_config_history` 建表语句 | `every_migrated_table_is_classified_as_provisioned_or_not` | **账本与代码脱节**（表没了但两账还登记着 ⇒ phantom） |

🛑 **C1/C2/C3 的语义要点**：它们注入的是「**语法合法**的变更」，门禁要求变红**不是**因为
变更写错了，而是因为**边界被移动是必须显式登记的事**。这正是「差异真在」型门禁的牙齿：
**修好即红、须显式移除登记**。

## 纪律（与 100~112 同口径）

- 锚点预检（存在 + 唯一）· 元层判别力自证 · 逐字节还原 · 还原后复绿
- 🛑 **锚点优先用单行**（技能 8.7）—— 故 C1/C2/C4/C5 全用单行锚点
- 🛑 注入必须保持源/构建文件**语法合法且可编译/可解析**（112 的教训：改类名 ⇒ 编译失败 ⇒ 红的是编译器）
- 🛑 C4 注入 pom 后，`mvn` 先跑 `<phase>validate</phase>` 的 exec 门禁 ⇒ 必须在 validate 就红
      （这也是**真实的**失效模式：引入 ORM 会在构建最早期被拦下）
- 🛑 must_see 锚点一律 ASCII（表名 / 依赖名 / YAML 键）
- 🛑 Windows：`mvn.cmd` 绝对路径 + list 参数 + `shell=False`

用法：
    python.exe verification/113_provisioning_boundary_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

GATE = "ProvisioningBoundaryGateTest"
M_CLASSIFY = "every_migrated_table_is_classified_as_provisioned_or_not"
M_NO_WRITER = "tables_declared_as_not_provisioned_have_no_writer_in_production"
M_CONTRACT = "the_contract_exposes_no_provisioning_endpoint"
M_NO_ORM = "no_orm_is_on_the_classpath_which_is_what_makes_the_literal_scan_sound"
METHODS = [M_CLASSIFY, M_NO_WRITER, M_CONTRACT, M_NO_ORM]

# ---- 被测文件（相对 skeleton 根 / 父目录）----
F_STORE_REPO = "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/repository/StoreRepository.java"
F_CONTRACT = "../contract/openapi-v1.0.0.yaml"
F_POM = "pom.xml"
F_MIGRATION = "dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql"

# ---- C1：在 StoreRepository 里加一条「建门店」的写入路径（Java 字符串字面量内）----
# 🛑 单行锚点。改写的是**出站列常量**那一行 —— 语法合法、可编译。
ANCHOR_C1 = 'private static final String SELECT_COLUMNS = "store_id, name, franchise_type";'
REPL_C1 = (ANCHOR_C1
           + "\n\n    /** 反向验证注入：模拟『有人加了建门店通路』（新增一条 INSERT INTO store）。 */"
           + "\n    static final String PROBE_INSERT_STORE ="
           + "\n            \"INSERT INTO store (store_id, tenant_id, name) VALUES (?, ?, ?)\";")

# ---- C2：给契约的 /stores 段加一个 post（只读列表变可写）----
# 🛑 单行锚点：`  /stores:` 紧跟 `    get:` 在契约里恰 1 次。
ANCHOR_C2 = "  /stores:\n    get:"
REPL_C2 = "  /stores:\n    post:"

# ---- C3：插入一个新 path「租户开通」----
ANCHOR_C3 = "  /audit/signals:\n    get:"
REPL_C3 = "  /tenants:\n    post:\n  /audit/signals:\n    get:"

# ---- C4：加入 ORM 依赖（推翻「字面量扫描」判据的前提）----
ANCHOR_C4 = "<java.version>17</java.version>"
REPL_C4 = (ANCHOR_C4
           + "\n        <!-- 反向验证注入：模拟『引入了 ORM』（会隐式写表，静态字面量扫描失效） -->"
           + "\n        <mybatis.version>3.5.16</mybatis.version>"
           + "\n        <probe.orm.marker>mybatis</probe.orm.marker>")

# ---- C5：从迁移里删掉 app_config_history 的建表语句（表没了、账本还登记着）----
ANCHOR_C5 = "CREATE TABLE IF NOT EXISTS app_config_history"
REPL_C5 = "CREATE TABLE IF NOT EXISTS app_config_history_PROBE_REMOVED"

INJECTIONS = [
    ("C1", F_STORE_REPO, ANCHOR_C1, REPL_C1, M_NO_WRITER),
    ("C2", F_CONTRACT, ANCHOR_C2, REPL_C2, M_CONTRACT),
    ("C3", F_CONTRACT, ANCHOR_C3, REPL_C3, M_CONTRACT),
    ("C4", F_POM, ANCHOR_C4, REPL_C4, M_NO_ORM),
    ("C5", F_MIGRATION, ANCHOR_C5, REPL_C5, M_CLASSIFY),
]


def log(msg: str) -> None:
    print(msg, flush=True)


def build_env() -> dict:
    env = dict(os.environ)
    java_home = env.get("JAVA_HOME")
    if not java_home or not Path(java_home).is_dir():
        java_home = JAVA_HOME_WIN
    env["JAVA_HOME"] = java_home
    parts = [str(Path(java_home) / "bin"), PG_BIN_WIN, env.get("PATH", "")]
    env["PATH"] = os.pathsep.join(p for p in parts if p)
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gate() -> tuple[int, str]:
    return run_maven([
        "-pl", "dy-app", "test",
        "-Dtest=" + GATE,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str, methods: list[str]) -> set[str]:
    return {m for m in methods if m in out}


_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    for rel, data in _SNAPSHOTS.items():
        p = (SKEL / rel).resolve()
        p.write_bytes(data)
        now = p.read_bytes()
        log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    _RESTORED = True


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


def main() -> int:
    log("=" * 78)
    log("批次十三 · 组织主数据开通边界门禁 · 反向验证（113）")
    log("=" * 78)

    for rel in {F_STORE_REPO, F_CONTRACT, F_POM, F_MIGRATION}:
        p = (SKEL / rel).resolve()
        assert p.is_file(), f"被测文件不存在：{p}"
        _SNAPSHOTS[rel] = p.read_bytes()
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    texts = {rel: data.decode("utf-8") for rel, data in _SNAPSHOTS.items()}

    # ---- 锚点预检：存在且恰 1 次 ----
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        ok = (n == 1)
        shown = anchor.replace("\n", "\\n")[:58]
        log(f"[预检 {'OK ' if ok else 'BAD'}] {tag} 锚点出现 {n} 次 :: {shown}")
        if not ok:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # ---- 基线：注入前必须全绿 ----
    log("\n[基线] 注入前跑门禁（期望 6 例全绿）...")
    c0, out0 = run_gate()
    if c0 != 0:
        log(f"[基线失败] 注入前门禁未通过（exit={c0}）—— 后续结论无意义")
        log(out0[-4000:])
        return 2
    pre = hits(out0, METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} —— 锚点恒真，无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    # ---- 逐组注入 ----
    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log("\n" + "-" * 78)
        log(f"[{tag}] 注入 {rel}：{anchor.replace(chr(10), ' / ')[:64]} ...")
        p = (SKEL / rel).resolve()
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1, f"{tag} 锚点不唯一"
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate()
        got = hits(out2, METHODS)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        if not caught:
            # 便于诊断：打印与失败信息最相关的片段
            tail = [ln for ln in out2.splitlines()
                    if ("ProvisioningBoundary" in ln or "ERROR" in ln or "AssertionFailed" in ln
                        or "必须" in ln or "不得" in ln)]
            log("   —— 诊断片段 ——")
            for ln in tail[:14]:
                log("   " + ln[:180])
        results.append((tag, expect, caught, code2, sorted(got)))

        # 立即还原该文件
        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # ---- 复绿 ----
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    c3, _ = run_gate()
    green = (c3 == 0)
    log(f"[复绿] exit={c3} · 复绿={green}")

    log("\n" + "=" * 78)
    log("汇 总")
    log("=" * 78)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")

    passed = sum(1 for _t, _e, c, _c2, _g in results if c) + (1 if green else 0)
    total = len(results) + 1
    log(f"\n结论: {passed}/{total} {'PASS' if passed == total else 'FAIL'}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())