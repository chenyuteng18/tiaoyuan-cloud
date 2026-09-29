#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次六 · 反向验证（Reversal Validation）—— 证明【契约端点覆盖总账】有牙齿。

## 本脚本要证明什么

`EndpointCoverageLedgerTest` 的四条断言都是"集合比较类"守卫，它们的共同失效模式是
**可以写成恒绿**（扫描器正则失效 ⇒ 实现侧集合恒空 ⇒ 差额集合恒空 ⇒ 全绿）。
故本脚本对每条核心断言各注入一个【必须被抓】的缺陷：

| 组 | 目标断言 | 注入内容 | 期望 |
|---|---|---|---|
| L1 | `every_contract_operation_is_implemented_or_registered_out_of_scope`（②） | 从 `OUT_OF_SCOPE` 删掉 `authLogin` 一条 | 变红（"契约有、实现无、未登记"） |
| L2 | `every_extra_endpoint_is_registered_as_internal`（③） | 在某个控制器上新增一个未登记端点 | 变红（"未登记出站面"） |
| L3 | `coverage_numbers_are_consistent`（④） | 从 `INTERNAL_ENDPOINTS` 删掉一条（制造僵尸登记） | 变红（数字不自洽 + 僵尸登记） |

## 纪律（与 102 / 103 同口径）

- **锚点预检**：每组注入前先断言锚点原文存在且唯一；找不到 ⇒ 直接 FAIL。
- **还原校验**：注入后逐字节还原，并用字节数比对证明"库里的文件 = 注入前那份"。
- **还原后复绿**：还原后重跑同一批断言，必须复绿。
- 🛑 **Windows GBK ⇒ must_see 锚点必须 ASCII**（方法名），不要用中文断言原文。
- 🛑 **Windows 下必须用 `mvn.cmd` 的 Windows 路径 + list 调用 + `shell=False`**；
  用 POSIX 路径配 `shell=True` 会走 cmd.exe 而无法解析（103 第一版的缺陷）。
- 🛑 **不能用 `-q`**（安静模式不打印 `BUILD SUCCESS`，无法据此判绿）。

用法：
    python.exe verification/104_endpoint_coverage_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------
# 路径与运行环境（与 102 / 103 同口径）
# ----------------------------------------------------------------------

HERE = Path(__file__).resolve().parent          # .../skeleton/verification
SKEL = HERE.parent                              # .../skeleton

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

MODULE = "dy-app"
GATE_CLASS = "EndpointCoverageLedgerTest"

# 断言方法名（ASCII，供 must_see 锚点用）
M_LEDGER = "every_contract_operation_is_implemented_or_registered_out_of_scope"
M_EXTRA = "every_extra_endpoint_is_registered_as_internal"
M_NUMBERS = "coverage_numbers_are_consistent"

# 目标文件（相对 SKEL）
F_GATE = ("dy-app/src/test/java/com/diaoyuanyun/dy/app/"
          "EndpointCoverageLedgerTest.java")
F_CTRL = ("dy-app/src/main/java/com/diaoyuanyun/dy/app/"
          "settlement/SettlementController.java")

# ----------------------------------------------------------------------
# 兜底还原
# ----------------------------------------------------------------------

_SNAPSHOT: dict[str, bytes] = {}


def _restore_all() -> None:
    for path, raw in list(_SNAPSHOT.items()):
        try:
            with open(path, "wb") as f:
                f.write(raw)
        except OSError:
            pass


atexit.register(_restore_all)
for _sig in (signal.SIGTERM, signal.SIGINT):
    try:
        signal.signal(_sig, lambda s, f: (_restore_all(), sys.exit(3)))
    except (ValueError, OSError):
        pass


def _env() -> dict:
    env = dict(os.environ)
    parts = [os.path.dirname(MAVEN_CMD), PG_BIN_WIN]
    if os.path.isdir(JAVA_HOME_WIN):
        parts.append(os.path.join(JAVA_HOME_WIN, "bin"))
        env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = os.pathsep.join(parts + [env.get("PATH", "")])
    return env


def run_gate() -> tuple[bool, str]:
    proc = subprocess.run(
        [MAVEN_CMD, "-o", "-pl", MODULE, "test",
         "-Dtest=" + GATE_CLASS,
         "-Dsurefire.failIfNoSpecifiedTests=false",
         "-Dsurefire.failIfNoTests=false"],
        cwd=str(SKEL), env=_env(),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    out = proc.stdout.decode("utf-8", errors="replace")
    green = (proc.returncode == 0) and ("BUILD SUCCESS" in out)
    return green, out


def read_bytes(p: Path) -> bytes:
    return p.read_bytes()


def write_text(p: Path, s: str) -> None:
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def snapshot(p: Path) -> bytes:
    k = str(p)
    if k not in _SNAPSHOT:
        _SNAPSHOT[k] = read_bytes(p)
    return _SNAPSHOT[k]


RESULTS: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, ok, detail))
    print(f"  {'✔' if ok else '✘'} {name}" + (f" —— {detail}" if detail else ""), flush=True)


def require_anchor(text: str, anchor: str, what: str) -> None:
    n = text.count(anchor)
    if n == 0:
        raise AssertionError(f"🛑 锚点未找到（{what}）：{anchor[:140]}")
    if n > 1:
        raise AssertionError(f"🛑 锚点不唯一（{what}）出现 {n} 次：{anchor[:140]}")


def injected_and_restored(path: Path, old: str, new: str, must_see: str,
                          label: str) -> None:
    """注入 → 跑门禁 → 期望红且红在 must_see → 还原 → 校验。"""
    raw = snapshot(path)
    original = raw.decode("utf-8").replace("\r\n", "\n")
    require_anchor(original, old, label)
    injected = original.replace(old, new, 1)
    assert injected != original, f"{label} 注入未改变内容"

    write_text(path, injected)
    try:
        green, out = run_gate()
        if green:
            record(label, False, f"注入后门禁仍全绿 —— {must_see} 是恒绿摆设")
        elif must_see in out:
            record(label, True, must_see + " FAILED")
        else:
            record(label, False, f"红，但红的不是 {must_see}")
            print(out[-1500:])
    finally:
        write_text_bytes(path, raw)

    ok = read_bytes(path) == raw
    record(label + " · 逐字节还原", ok, f"{path.name} ({len(raw)}B)")


def write_text_bytes(p: Path, raw: bytes) -> None:
    with open(p, "wb") as f:
        f.write(raw)


def main() -> int:
    print("=" * 72)
    print("批次六 · 反向验证：契约端点覆盖总账（② 差额 / ③ 多出 / ④ 数字自洽）")
    print("=" * 72)

    gate = SKEL / F_GATE
    ctrl = SKEL / F_CTRL

    print("\n== 锚点预检 ==", flush=True)
    try:
        g = gate.read_text(encoding="utf-8")
        require_anchor(g, 'put("authLogin",', "OUT_OF_SCOPE authLogin 条目")
        require_anchor(g, 'put("GET /verdicts/{}",', "INTERNAL_ENDPOINTS verdicts 条目")
        c = ctrl.read_text(encoding="utf-8")
        require_anchor(c, "@PostMapping(\"/preview\")", "SettlementController preview 映射")
        print("  ✔ 三处锚点均存在且唯一", flush=True)
    except AssertionError as e:
        print(f"  ✘ {e}", flush=True)
        print("🛑 锚点预检未通过，终止（未改动任何文件）", flush=True)
        return 2

    print("\n== 基线 ==", flush=True)
    green, out = run_gate()
    record("baseline 端点覆盖总账", green, GATE_CLASS + (" PASS" if green else " RED"))
    if not green:
        print(out[-2500:])
        print("\n🛑 基线不绿 —— 先修基线。", flush=True)
        return 2

    # ---- L1：把 OUT_OF_SCOPE 的一条改键名（= 不再登记）⇒ ② 必红 ----
    # 🛑 注入方式说明：必须保持 Java 语法合法。第一版直接删掉 `put("authLogin",` 整行，
    #    导致后面悬空的字符串字面量 ⇒ **编译失败**（红的是编译器，不是断言）。
    #    改为「改键名」：语义上等价于"该 operationId 不再被登记"，语法完全合法。
    print("\n== L1 · 把 OUT_OF_SCOPE 的 authLogin 改键名 ⇒ ② 差额登记门禁必须红 ==", flush=True)
    injected_and_restored(
        gate,
        'put("authLogin",',
        'put("authLoginRENAMED",',
        M_LEDGER,
        "L1 未登记出范围必红",
    )

    # ---- L2：新增一个未登记端点 ⇒ ③ 必红 ----
    print("\n== L2 · 在 SettlementController 新增未登记端点 ⇒ ③ 多出登记门禁必须红 ==", flush=True)
    injected_and_restored(
        ctrl,
        '@PostMapping("/preview")',
        '@PostMapping("/unregistered-probe")  // [L2 注入] 契约未声明、INTERNAL_ENDPOINTS 未登记\n'
        '    public Result<Object> unregisteredProbe() { return Result.ok(java.util.Map.of(), null); }\n\n'
        '    @PostMapping("/preview")',
        M_EXTRA,
        "L2 未登记出站面必红",
    )

    # ---- L3：把 INTERNAL_ENDPOINTS 一条改键名（= 端点还在、登记没了）⇒ ③僵尸登记 + ④数字 必红 ----
    # 🛑 同 L1：改键名而非删行，保持 Java 语法合法。
    print("\n== L3 · 把 INTERNAL_ENDPOINTS 的 verdicts 条目改键名 ⇒ ③僵尸登记 必须红 ==", flush=True)
    injected_and_restored(
        gate,
        'put("GET /verdicts/{}",',
        'put("GET /verdicts/RENAMED",',
        M_EXTRA,
        "L3 僵尸登记必红",
    )

    # ---- 还原后复绿 ----
    print("\n== 还原后基线 ==", flush=True)
    green, _ = run_gate()
    record("还原后复绿", green, GATE_CLASS + (" PASS" if green else " 仍红！"))

    print("\n" + "=" * 72)
    failed = [r for r in RESULTS if not r[1]]
    print(f"总体: {'PASS' if not failed else 'FAIL'}  "
          f"({len(RESULTS) - len(failed)}/{len(RESULTS)} 项通过)")
    for name, _, detail in failed:
        print(f"  ✘ {name}: {detail}")
    print("=" * 72)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())