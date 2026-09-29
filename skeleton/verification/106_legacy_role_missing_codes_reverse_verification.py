#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次八 · 反向验证（Reversal Validation）—— 证明【遗留角色码缺自建码】的登记式差异有牙齿。

## 本脚本要证明什么

`PermissionCodeRegistrationGateTest` 新增第七章：把「`TENANT_ADMIN` / `super_admin` 未持
`doc:write` / `audit:read`」这条此前<b>只写在注释里</b>的分叉，落成结构化登记表
`LEGACY_ROLE_MISSING_SELF_DEFINED_CODES`。它是一种**登记式断言**，其共同失效模式是
**可以写成恒绿**（表空了循环不进 / 断言方向写反）。

本脚本注入三个【必须被抓】的形态：

| 组 | 注入内容 | 期望 | 证明了哪一条 |
|---|---|---|---|
| R1 | 给 `PermissionRegistry` 加 `TENANT_ADMIN → {doc:write, audit:read}`（**把分叉修好**） | 变红 | ① 「差异真的还在」会随修复归零 —— 登记表不会停在过期事实 |
| R2 | 把登记表的角色写成 `hq`（**实际持有该码的契约角色**） | 变红 | ① 反向也有效：登记一个"已持有"的角色同样红（不是单向假绿） |
| R3 | 把登记表**清空**（`Map.of()`） | 变红 | ③ 「恰 2 个角色」防"表清空 ⇒ 循环不进 ⇒ 平凡通过" |

## 纪律（与 102~105 同口径）

- **锚点预检**：每组注入前先断言锚点原文存在且**唯一**；找不到或 >1 次 ⇒ 直接 FAIL。
- **元层判别力自证**：基线（全绿）阶段先断言待查方法名**零出现**，否则判脚本失效。
  （此法于 105 首次固化：surefire 汇总行也含类名，若"方法名出现"变恒真则"被抓"全部失效。）
- **逐字节还原** + **还原后复绿**。
- **🛑 注入必须保持源文件语法合法**：三组均改 Java **表达式字面/新增一条 put**，语法合法 ⇒
  红点只能来自断言，不会来自编译器。
- **🛑 跨模块注入必须先 install 依赖模块**（本脚本 R1 首跑"未被抓住"的真因）：
  `PermissionRegistry` 在 **dy-security**、被测类在 **dy-app** ⇒ 只跑 `-pl dy-app` 会用
  **本地仓库的旧 jar**，对 dy-security 源码的注入**根本不生效**，脚本随之误报"未被抓住"。
  这与 103 首版"POSIX 路径 + `shell=True` ⇒ Maven 从未执行"**同族**：
  **"未被抓住"必须先分辨"守护坏了"还是"注入没进到被测对象里"**。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息可能乱码。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。

用法：
    python.exe verification/106_legacy_role_missing_codes_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------
# 路径与运行环境（与 102~105 同口径）
# ----------------------------------------------------------------------

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

MODULE = "dy-app"
GATE_CLASS = "PermissionCodeRegistrationGateTest"

# 断言方法名（ASCII，供 must_see 锚点用）
M_LEGACY = "legacy_role_codes_missing_self_defined_permissions_are_registered_not_assumed"
ALL_METHODS = [M_LEGACY]

F_GATE = ("dy-app/src/test/java/com/diaoyuanyun/dy/app/"
          "security/PermissionCodeRegistrationGateTest.java")
F_REGISTRY = ("dy-security/src/main/java/com/diaoyuanyun/dy/security/"
              "permission/PermissionRegistry.java")

# ----------------------------------------------------------------------
# 注 入 定 义
# ----------------------------------------------------------------------

INJECTIONS = [
    (
        "R1",
        F_REGISTRY,
        # 在 SUPER_ADMIN 之前插入一条 TENANT_ADMIN 授权（= 把分叉"修好"）
        '        rolePermissions.put("SUPER_ADMIN", Set.of("*"));',
        '        rolePermissions.put("TENANT_ADMIN", Set.of("doc:write", "audit:read"));\n'
        '        rolePermissions.put("SUPER_ADMIN", Set.of("*"));',
        M_LEGACY,
    ),
    (
        "R2",
        F_GATE,
        # 把登记的角色写成"实际持有该码"的契约角色（登记表写错角色的形态）
        '            "TENANT_ADMIN", Set.of("doc:write", "audit:read"),',
        '            "hq", Set.of("doc:write", "audit:read"),',
        M_LEGACY,
    ),
    (
        "R3",
        F_GATE,
        # 把登记表清空（循环不进 ⇒ 若没有"恰 2 个角色"这条，会平凡通过）
        'Map.of(\n'
        '            "TENANT_ADMIN", Set.of("doc:write", "audit:read"),\n'
        '            "super_admin", Set.of("doc:write", "audit:read"));',
        'Map.of();',
        M_LEGACY,
    ),
]


def log(msg: str) -> None:
    print(msg, flush=True)


# ----------------------------------------------------------------------
# 环境与 Maven
# ----------------------------------------------------------------------

def build_env() -> dict:
    env = dict(os.environ)
    java_home = env.get("JAVA_HOME")
    if not java_home or not Path(java_home).is_dir():
        java_home = JAVA_HOME_WIN
    env["JAVA_HOME"] = java_home
    parts = [str(Path(java_home) / "bin"), PG_BIN_WIN, env.get("PATH", "")]
    env["PATH"] = os.pathsep.join(p for p in parts if p)
    env.setdefault("DY_PG_HOST", "127.0.0.1")
    env.setdefault("DY_PG_PORT", "5432")
    env.setdefault("DY_PG_SUPER_PASSWORD", "postgres")
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    cmd = [MAVEN_CMD, "-o"] + args
    proc = subprocess.run(cmd, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gate() -> tuple[int, str]:
    """跑门禁。**先 install 依赖模块**，确保被测模块看到其最新源码。

    🛑🛑 为什么必须有这一步（本脚本 R1 首跑"未被抓住"的真因）：
    `PermissionRegistry` 在 **dy-security**，而被测类在 **dy-app**。
    若只跑 `-pl dy-app`，dy-app 会从 **本地仓库** 解析 dy-security 的 **jar**，
    ⇒ **对 dy-security 源码的注入根本不生效**，门禁当然保持全绿。
    此时脚本会报"未被抓住"，而真因是**注入没有生效**（不是断言没牙齿）——
    与 103 首版"POSIX 路径 + shell=True ⇒ Maven 从未真正执行"属**同一族**：
    **"注入未被抓住"必须先分辨"守护坏了"还是"注入没进到被测对象里"**。
    故：每次跑门禁前，先把依赖模块 install 到本地仓库（`-DskipTests`，不重复跑它的测试）。
    """
    code, out = run_maven(["-pl", "dy-security", "install", "-DskipTests"])
    if code != 0:
        return code, out
    return run_maven([
        "-pl", MODULE, "test",
        "-Dtest=" + GATE_CLASS,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str) -> set[str]:
    return {m for m in ALL_METHODS if m in out}


# ----------------------------------------------------------------------
# 快照 / 还原
# ----------------------------------------------------------------------

_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def snapshot(rel: str) -> bytes:
    data = (SKEL / rel).read_bytes()
    _SNAPSHOTS[rel] = data
    return data


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    for rel, data in _SNAPSHOTS.items():
        p = SKEL / rel
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


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------

def main() -> int:
    log("=" * 74)
    log("批次八 · 遗留角色码缺自建码 · 登记式差异反向验证")
    log("=" * 74)

    for rel in {F_GATE, F_REGISTRY}:
        snapshot(rel)
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    # 1) 锚点预检
    texts = {rel: _SNAPSHOTS[rel].decode("utf-8") for rel in _SNAPSHOTS}
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        log(f"[预检 {'OK ' if n == 1 else 'BAD'}] {tag} 锚点出现 {n} 次")
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # 2) 基线 + 元层判别力自证
    log("\n[基线] 注入前跑门禁（期望全绿）...")
    code, out = run_gate()
    if code != 0:
        log("[基线失败] 注入前门禁未通过 —— 后续结论无意义")
        log(out[-3000:])
        return 2
    if hits(out):
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(hits(out))} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    # 3) 注入 → 必须红 → 还原
    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入：{anchor[:64].replace(chr(10), ' ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate()
        got = hits(out2)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # 4) 复绿
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    code3, out3 = run_gate()
    green = code3 == 0
    log(f"[复绿] exit={code3} 复绿={green}")

    # 5) 汇总
    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    passed = sum(1 for _t, _e, c, _c2 in results if c)
    for tag, expect, caught, code in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    total = len(results) + 1
    ok = passed + (1 if green else 0)
    log(f"\n总体：{ok}/{total} 通过")
    log("=" * 74)
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())