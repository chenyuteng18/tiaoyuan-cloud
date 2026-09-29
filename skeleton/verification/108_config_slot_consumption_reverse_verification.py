#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十 · 反向验证（Reversal Validation）—— 证明【配置槽位消费台账】有牙齿。

## 本脚本要证明什么

`ConfigSlotConsumptionLedgerTest` 是"配置声明 × 生产代码消费"的台账，五条测试各守一面：

| 测试 | 它守的 |
|---|---|
| `the_declared_slot_set_equals_consumed_union_unconsumed` | ① 全集 = 已消费 ∪ 未消费，两账互斥，不得有第三个 |
| `every_consumed_slot_is_actually_referenced_by_production_code` | ② 账记"已消费"的，代码里必须真有（含代表性文件连线） |
| `every_unconsumed_slot_is_actually_unreferenced_by_production_code` | ③ 账记"未消费"的，代码里必须真没有（归零须显式） |
| `comment_only_references_are_not_counted_as_consumption` | ④ 剥注释是必要的（注释-only 引用不得算作消费） |
| `the_21_hardcoded_default_divergence_is_closed` | ⑤ #21「总部唯一可改 vs 硬编码」的差异**已消除**（接线后翻转为正向断言） |

本脚本用 5 组独立注入，逐一让这五条变红（**每条注入的期望红集写在表格里**）：

| 组 | 注入内容 | 期望变红 |
|---|---|---|
| P1 | `WebConfig` 的装配改成不经配置（回退接线） | ⑤ `..._21_...` |
| P2 | 往 seed 里加**第 47 条**槽位（`#49`），不改任何账本 | ① `..._equals_consumed_...` |
| P3 | 账本里 `CONSUMED.put(43, ...)` 的**代表性文件路径**改成不存在的文件 | ② `..._every_consumed_...` |
| P4 | 让**生产代码**引用一个"未消费"键（`cfg:audit.signal_thresholds`） | ③ `..._every_unconsumed_...` |
| P5 | 使**剥注释失效**（块注释检测被短路）| ④ `..._comment_only_...`（③ 会同时红：被注释屏蔽的 `#9/#32` 会被当成已消费） |

## 🛑 批次十一（2026-09-27）对本脚本的修订 —— 为什么 P1 必须换锚点

批次十一把 N-14 **定性为真缺陷并修复**：`CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD`
常量被**删除**（改从 `CrossStoreThresholds` 读配置），`ConfigSlotConsumptionLedgerTest`
的断言⑤也从「差异仍在」**翻转**为 `the_21_hardcoded_default_divergence_is_closed`
（「差异已消除」）。这连带使本脚本原来的 P1 锚点（改 `0.30` 字面量）**失去了注入对象** ——
锚点不存在 ⇒ 预检会直接拦下（这正是"锚点预检"的价值：它把"注入失效"从静默变成显式失败）。
故 P1 改注入**接线本身**（把 `WebConfig` 的 `fromRawConfig` 调用改回直接 `new`），
它守卫的语义不变：**"总部唯一可改"必须真的成立**（接线断了就必须红）。

## 纪律（与 102~107 同口径）

- 🛑 **跨模块注入必须先 install 依赖模块**（批次八教训）：**P2** 注入的是
  **dy-config** 的 `src/main/resources/db/config/02_slots_seed.sql`（classpath 资源），
  被测类在 **dy-app**。只跑 `-pl dy-app` 会用**本地仓库 jar 内的旧资源** ⇒ 注入不生效。
  ⇒ `NEEDS_CONFIG_INSTALL = {"P2"}`（跑 P2 前先 `mvn -o -pl dy-config install -DskipTests`）。
  其余四组（P1/P3/P4/P5）注入对象都在 **dy-app**，不受影响。
- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- 🛑 **注入必须保持源文件语法合法**：P1 只改字符串字面量 / P2 插一行 VALUES /
  P3 只改字符串字面量 / P4 只改字符串字面量 / P5 用**运行期条件**（`src.isEmpty()`）短路，
  不用 `false`（常量折叠会让后续分支变不可达代码 ⇒ 编译报错，红点就来自编译器而非断言）。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息可能乱码。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。

用法：
    python.exe verification/108_config_slot_consumption_reverse_verification.py
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

MODULE = "dy-app"
GATE_CLASS = "ConfigSlotConsumptionLedgerTest"

M_SET = "the_declared_slot_set_equals_consumed_union_unconsumed"
M_CONS = "every_consumed_slot_is_actually_referenced_by_production_code"
M_UNCONS = "every_unconsumed_slot_is_actually_unreferenced_by_production_code"
M_COMMENT = "comment_only_references_are_not_counted_as_consumption"
M_21 = "the_21_hardcoded_default_divergence_is_closed"
ALL_METHODS = [M_SET, M_CONS, M_UNCONS, M_COMMENT, M_21]

F_TEST = ("dy-app/src/test/java/com/diaoyuanyun/dy/app/config/"
          "ConfigSlotConsumptionLedgerTest.java")
F_SETTLEMENT = ("dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/"
                "CrossStoreSettlement.java")
F_THRESHOLDS = ("dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/domain/"
                "CrossStoreThresholds.java")
F_WEBCONFIG = "dy-app/src/main/java/com/diaoyuanyun/dy/app/config/WebConfig.java"
F_SEED = "dy-config/src/main/resources/db/config/02_slots_seed.sql"

INJECTIONS = [
    (
        # 🛑 批次十一改锚点：原锚点是 CrossStoreSettlement 里的 `0.30` 字面量，
        #    而该常量已被删除（N-14 修复）⇒ 锚点不存在 ⇒ 预检会拦下。
        #    改注入【接线本身】：把 WebConfig 的"按配置注入"改回"直接 new"，
        #    它守的语义不变 ——「总部唯一可改」必须真的成立。
        "P1", F_WEBCONFIG,
        "CrossStoreThresholds.fromRawConfig(profileSource.raw()));",
        "java.math.BigDecimal.valueOf(0.30));",
        M_21,
    ),
    (
        "P2", F_SEED,
        # 往 VALUES 里插一条【第 47 条】槽位（#49），不动任何账本 ⇒ 台账应报"编号不在两账上"
        "(48, 'cfg:health_pnl.visibility', 'JSON', NULL, NULL,",
        "(49, 'cfg:probe.namespace_gap', 'INT', NULL, NULL,\n"
        " '1',\n"
        " 'INJECTION probe slot',\n"
        " 'INJECTION probe slot'),\n\n"
        "(48, 'cfg:health_pnl.visibility', 'JSON', NULL, NULL,",
        M_SET,
    ),
    (
        "P3", F_TEST,
        # 代表性文件指向一个不存在的文件 ⇒ "账本与代码的连线断了"
        'CONSUMED.put(43, "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/domain/'
        'BandVisibilityMatrix.java");',
        'CONSUMED.put(43, "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/domain/'
        'NoSuchFileInjection.java");',
        M_CONS,
    ),
    (
        # 🛑 批次十一改锚点：原锚点（"跨店拆分阈值必须在 [0,1] 内, 收到: "）在修复中
        #    被扩写为"跨店拆分阈值（config #21）必须在 [0,1] 内, 收到: " ⇒ 换成现值。
        "P4", F_THRESHOLDS,
        # 让【生产代码】引用一个当前"未消费"的键 ⇒ ③ 应红（差异归零须显式）
        '"跨店拆分阈值（config #21）必须在 [0,1] 内, 收到: " + splitThreshold',
        '"跨店拆分阈值（config #21）必须在 [0,1] 内(cfg:audit.signal_thresholds), '
        '收到: " + splitThreshold',
        M_UNCONS,
    ),
    (
        "P5", F_TEST,
        # 短路【块注释】检测（运行期条件，不用 false 常量折叠）⇒ 剥注释失效 ⇒ ④ 应红
        "                if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {",
        "                if (src.isEmpty() && i + 1 < n && src.charAt(i + 1) == '*') {",
        M_COMMENT,
    ),
]

# P2 是跨模块注入（改 dy-config 的 classpath 资源）⇒ 需先 install dy-config
NEEDS_CONFIG_INSTALL = {"P2"}


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
    env.setdefault("DY_PG_HOST", "127.0.0.1")
    env.setdefault("DY_PG_PORT", "5432")
    env.setdefault("DY_PG_SUPER_PASSWORD", "postgres")
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gate(install_config: bool = False) -> tuple[int, str]:
    """跑门禁；`install_config=True` 时先把 dy-config 装进本地仓库（跨模块注入前置）。"""
    if install_config:
        code, out = run_maven(["-pl", "dy-config", "install", "-DskipTests"])
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


def main() -> int:
    log("=" * 74)
    log("批次十 · 配置槽位消费台账 · 反向验证")
    log("=" * 74)

    for rel in {F_TEST, F_SETTLEMENT, F_THRESHOLDS, F_WEBCONFIG, F_SEED}:
        snapshot(rel)
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

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

    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入（{'跨模块 ⇒ 先 install dy-config' if tag in NEEDS_CONFIG_INSTALL else '同模块'}）："
            f"{anchor[:56].replace(chr(10), ' ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate(install_config=(tag in NEEDS_CONFIG_INSTALL))
        got = hits(out2)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    code3, out3 = run_gate(install_config=True)
    green = code3 == 0
    log(f"[复绿] exit={code3} 复绿={green}")

    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    passed = sum(1 for _t, _e, c, _c2, _g in results if c)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    total = len(results) + 1
    ok = passed + (1 if green else 0)
    log(f"\n总体：{ok}/{total} 通过")
    log("=" * 74)
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())