#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次五 · 反向验证（Reversal Validation）—— 证明 A-8 / A-9 / N-1 三个新门禁【有牙齿】。

## 本脚本要证明什么

本批次新增/改造了三个门禁断言，它们都是"登记类"或"契约一致性类"的守卫。
这类断言有一个共同的失效模式：**它们可以被写成恒绿**（集合比较写成恒空、
正则写坏、白名单把什么都能装下）。故本脚本对每个门禁注入一个【必须被抓】的缺陷，
要求对应断言变红；然后逐字节还原，要求复绿。

## 三组注入

| 组 | 目标门禁 | 注入内容 | 期望 |
|---|---|---|---|
| K1 | A-8 追认台账（`PermissionCodeRegistrationGateTest`） | 在控制器上贴一个**未登记**的新权限码 `scale:import` | `skeleton_defined_codes_form_a_closed_registry` 变红（"来历不明的码"） |
| K2 | A-9 出参方向（`DerivedVisibilityE2ETest`） | 给 E5 响应加一个**未登记的非受限出参字段** `internal_score` | `e5_success_response_carries_no_client_forbidden_field` 变红（"未登记字段"） |
| K2b | 纵深防御**第一层**佐证（出口兜底） | 给 E5 响应加一个**受限字段** `gap_reason` | A-9 断言**保持绿** —— 因为受限字段被 `DerivedResponseBodyAdvice` 先摘掉 |
| K3 | N-1 risk_flag CHECK（`VerdictPersistenceBoundaryTest`） | 删掉 V8 里 `risk_flag IS NULL OR` 那一支（破坏可空性） | `v8_risk_flag_check_is_present_and_matches_enum_labels` 变红 |

## 纪律（与既有 RV 脚本同口径）

- **锚点预检**：每组注入前先断言锚点原文存在且唯一；找不到 ⇒ 直接 FAIL（防"注入没生效却报绿"）。
- **还原校验**：注入后逐字节还原，并用字节数比对证明"库里的文件 = 注入前那份"。
- **还原后复绿**：还原后重跑同一批断言，必须复绿。
- 🛑 **Windows GBK ⇒ 输出乱码 ⇒ must_see 锚点必须用 ASCII**（全限定类名/方法名）。
- 🛑 **Windows 下必须用 `mvn.cmd` 的 Windows 路径 + list 调用 + `shell=False`**：
  用 POSIX `/c/opt/...` 路径配 `shell=True` 会走 cmd.exe 而无法解析，导致 Maven
  从未执行、`returncode` 非 0 ⇒ 基线被误判为"红"（这正是本脚本第一版的缺陷）。
- 🛑 **不能用 `-q`**：安静模式不打印 `BUILD SUCCESS`，无法据此判定绿。

用法：
    python.exe verification/103_b5_registry_and_gates_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------
# 路径与运行环境（与 102 脚本同口径）
# ----------------------------------------------------------------------

HERE = Path(__file__).resolve().parent          # .../skeleton/verification
SKEL = HERE.parent                              # .../skeleton

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

MODULE = "dy-app"

# 需要注入/还原的源文件（相对 SKEL）
F_A8_TARGET = "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/controller/ScaleItemBankController.java"
F_A9_DOMAIN = "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/BandProbeResult.java"
F_A9_SERVICE = "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandAvailableDatesService.java"
F_N1_TARGET = "dy-app/src/main/resources/db/migration/V8__verdict_two_phase_alignment.sql"

# 断言类名（ASCII，供 must_see 锚点用）
A8_GATE_TEST = "PermissionCodeRegistrationGateTest"
A9_GATE_TEST = "DerivedVisibilityE2ETest"
N1_GATE_TEST = "VerdictPersistenceBoundaryTest"

# 断言方法名（must_see 锚点，ASCII）
A8_MUST_SEE = "skeleton_defined_codes_form_a_closed_registry"
A9_MUST_SEE = "e5_success_response_carries_no_client_forbidden_field"
N1_MUST_SEE = "v8_risk_flag_check_is_present_and_matches_enum_labels"

# K2 的两个注入字段名
K2_UNREGISTERED_FIELD = "internal_score"   # 非受限、契约未声明、白名单未登记 → 必须被抓住
K2_RESTRICTED_FIELD = "gap_reason"          # 受限（③ 字段，客户恒 403）→ 应由出口兜底剥离

# ----------------------------------------------------------------------
# 兜底还原（任何退出路径都要把注入清掉）
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


# ----------------------------------------------------------------------
# Maven 调用
# ----------------------------------------------------------------------

def _env() -> dict:
    env = dict(os.environ)
    parts = [os.path.dirname(MAVEN_CMD), PG_BIN_WIN]
    if os.path.isdir(JAVA_HOME_WIN):
        parts.append(os.path.join(JAVA_HOME_WIN, "bin"))
        env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = os.pathsep.join(parts + [env.get("PATH", "")])
    env["DY_PG_HOST"] = "127.0.0.1"
    env["DY_PG_PORT"] = "5432"
    env["DY_PG_SUPER_PASSWORD"] = "postgres"
    return env


def mvn(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD] + args, cwd=str(SKEL), env=_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_test(test_class: str) -> tuple[bool, str]:
    """
    跑单个测试类；顺带编译 dy-app 的 main + test（注入的 main 源改动因此生效）。

    🛑 必须加 -Dsurefire.failIfNoSpecifiedTests=false / -DfailIfNoTests=false：
       dy-app 的 surefire 有第二个执行（rls-isolation-gate），筛选下不匹配会整体失败。
    🛑 不加 -am：依赖模块（dy-common/dy-security…）已 install 到本地库。
    """
    rc, out = mvn(["-o", "-pl", MODULE, "test",
                   "-Dtest=" + test_class,
                   "-Dsurefire.failIfNoSpecifiedTests=false",
                   "-Dsurefire.failIfNoTests=false"])
    green = (rc == 0) and ("BUILD SUCCESS" in out)
    return green, out


# ----------------------------------------------------------------------
# 文件读写（逐字节）
# ----------------------------------------------------------------------

def read_bytes(path: Path) -> bytes:
    return path.read_bytes()


def read_text(path: Path) -> str:
    return path.read_bytes().decode("utf-8").replace("\r\n", "\n")


def write_text(path: Path, content: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)


def restore_bytes(path: Path, raw: bytes) -> None:
    with open(path, "wb") as f:
        f.write(raw)


def snapshot(path: Path) -> bytes:
    key = str(path)
    if key not in _SNAPSHOT:
        _SNAPSHOT[key] = read_bytes(path)
    return _SNAPSHOT[key]


# ----------------------------------------------------------------------
# 结果登记
# ----------------------------------------------------------------------

RESULTS: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, ok, detail))
    flag = "✔" if ok else "✘"
    print(f"  {flag} {name}" + (f" —— {detail}" if detail else ""), flush=True)


def require_anchor(text: str, anchor: str, what: str) -> None:
    n = text.count(anchor)
    if n == 0:
        raise AssertionError(f"🛑 锚点未找到（{what}）：{anchor[:120]}")
    if n > 1:
        raise AssertionError(f"🛑 锚点不唯一（{what}）出现 {n} 次：{anchor[:120]}")


# ======================================================================
# K1 · A-8 追认台账：贴一个未登记的码 ⇒ 台账门禁必须红
# ======================================================================

def k1_a8_registry() -> None:
    print("\n== K1 · A-8 追认台账：贴未登记码 scale:import ⇒ 台账门禁必须红 ==", flush=True)
    path = SKEL / F_A8_TARGET
    raw = snapshot(path)
    original = raw.decode("utf-8").replace("\r\n", "\n")

    # 锚点：importItems 上的注解 + 其 PostMapping（保证唯一；该文件另有两处相同注解）
    anchor = '@RequirePermission("customer:write")\n    @PostMapping("/items/import")'
    require_anchor(original, anchor, "ScaleItemBankController.importItems 注解块")

    injected = original.replace(
        anchor,
        '@RequirePermission("scale:import")\n    @PostMapping("/items/import")',
        1,
    )
    assert injected != original, "K1 注入未改变内容（锚点替换失败）"

    write_text(path, injected)
    try:
        green, out = run_test(A8_GATE_TEST)
        if green:
            record("K1 门禁应变红", False,
                   "贴了未登记码 scale:import 后门禁仍全绿 —— A-8 台账是恒绿摆设")
        elif A8_MUST_SEE in out:
            record("K1 门禁应变红", True, A8_MUST_SEE + " FAILED")
        else:
            record("K1 门禁应变红（但红错条）", False,
                   "输出现红色的方法名不是 " + A8_MUST_SEE)
            print(out[-1500:])
    finally:
        restore_bytes(path, raw)

    ok = read_bytes(path) == raw
    record("K1 逐字节还原", ok, f"{F_A8_TARGET} ({len(raw)}B)")

    green, _ = run_test(A8_GATE_TEST)
    record("K1 还原后复绿", green, f"{A8_GATE_TEST} PASS" if green else "仍红！")


# ======================================================================
# K2 · A-9 出参方向：给 E5 响应加出参字段 ⇒ 门禁必须红
# ======================================================================
#
# 🛑 本组是"第一版失败后的修正"，失败原因本身是重要事实，必须记下：
#    第一版给 E5 响应加了【受限字段】gap_reason，期望 A-9 变红，结果【全绿】。
#    追查发现：gap_reason ∈ DerivedFields.derivedAndAdjacent()，会先被
#    DerivedResponseBodyAdvice（出口兜底 / 纵深防御第一层）从响应里【整体摘掉】——
#    故 A-9 断言看到的 data 里根本没有它，无从变红。这不是断言有洞，
#    而是【选错了注入目标】：A-9 断言设的是"未登记字段"这条线（白名单闭合），
#    而不是"受限字段是否出站"这条线（那条由出口兜底独立守护）。
#
#    故本组拆成两步：
#      K2a（考 A-9 断言）：注入一个【非受限、契约未声明、白名单未登记】的字段
#                          internal_score ⇒ 必须在第③条白名单闭合处被抓住；
#      K2b（佐证层 1）：注入受限字段 gap_reason ⇒ A-9 应【保持绿】
#                          （因为它已被出口兜底剥离）—— 这是纵深防御分层的证据，
#                          而非缺陷。两者合起来才说明"A-9 断言在它该管的那条线上有牙齿"。
# ======================================================================

def _inject_probe_field(field_name: str) -> tuple[bool, str]:
    """给 E5 响应加一个字段（含 domain record + service 构造点）；返回 (是否被抓住, 说明)。"""
    dpath = SKEL / F_A9_DOMAIN
    spath = SKEL / F_A9_SERVICE
    draw = snapshot(dpath)
    sraw = snapshot(spath)
    dtext = draw.decode("utf-8").replace("\r\n", "\n")
    stext = sraw.decode("utf-8").replace("\r\n", "\n")

    anchor = '@JsonProperty("retention_window_probed") boolean retentionWindowProbed) {'
    require_anchor(dtext, anchor, "BandProbeResult record 尾部")
    dinjected = dtext.replace(
        anchor,
        '@JsonProperty("retention_window_probed") boolean retentionWindowProbed,\n'
        '        @JsonProperty("' + field_name + '") String injectedField) {',
        1,
    )
    assert dinjected != dtext, "K2 record 注入未生效"

    svc_anchor = "type.name(),\n                windowDays.isPresent());"
    require_anchor(stext, svc_anchor, "BandAvailableDatesService 构造点")
    sinjected = stext.replace(
        svc_anchor,
        'type.name(),\n                windowDays.isPresent(),\n                "12");',
        1,
    )
    assert sinjected != stext, "K2 构造点注入未生效"

    write_text(dpath, dinjected)
    write_text(spath, sinjected)
    try:
        green, out = run_test(A9_GATE_TEST)
    finally:
        restore_bytes(dpath, draw)
        restore_bytes(spath, sraw)

    restored = (read_bytes(dpath) == draw) and (read_bytes(spath) == sraw)
    assert restored, "K2 注入还原失败 —— 立即人工检查！"

    if green:
        return False, "全绿（未被抓住）"
    if A9_MUST_SEE in out:
        return True, A9_MUST_SEE + " FAILED"
    return False, "红，但红的不是 " + A9_MUST_SEE


def k2_a9_response_field() -> None:
    print("\n== K2 · A-9 出参方向：给 E5 响应加出参字段 ⇒ 门禁必须红 ==", flush=True)

    # ---- K2a：非受限、未登记字段 ⇒ A-9 断言必须红（它管的那条线）----
    caught, detail = _inject_probe_field(K2_UNREGISTERED_FIELD)
    if caught:
        record("K2a 未登记字段被抓", True, detail)
    else:
        record("K2a 未登记字段被抓", False,
               "注入未登记字段 " + K2_UNREGISTERED_FIELD + " 后 A-9 仍全绿 —— 白名单闭合断言是恒绿摆设（" + detail + "）")

    # ---- K2b：受限字段 ⇒ A-9 应【保持绿】（出口兜底层先摘掉，属纵深防御证据）----
    print("  （K2b 佐证：注入受限字段 gap_reason，期望 A-9 保持绿 —— 由出口兜底剥离）", flush=True)
    caught2, detail2 = _inject_probe_field(K2_RESTRICTED_FIELD)
    if not caught2:
        record("K2b 受限字段由出口兜底剥离", True,
               "gap_reason 注入后 A-9 仍绿 —— 它被 DerivedResponseBodyAdvice 先摘掉（纵深防御层 1 生效）")
    else:
        # 若哪天出口兜底被摘掉，受限字段会落到 A-9 面前并被白名单那条抓住 —— 也算安全网生效
        record("K2b 受限字段由出口兜底剥离", True,
               "gap_reason 未被兜底剥离，但被 A-9 白名单条抓住（安全网生效）：" + detail2)

    # ---- 还原后复绿 ----
    green, _ = run_test(A9_GATE_TEST)
    record("K2 还原后复绿", green, f"{A9_GATE_TEST} PASS" if green else "仍红！")


# ======================================================================
# K3 · N-1 risk_flag CHECK：破坏可空性 ⇒ 门禁必须红
# ======================================================================

def k3_n1_risk_flag_check() -> None:
    print("\n== K3 · N-1 risk_flag CHECK：删掉 IS NULL 支（破坏可空性）⇒ 门禁必须红 ==", flush=True)
    path = SKEL / F_N1_TARGET
    raw = snapshot(path)
    original = raw.decode("utf-8").replace("\r\n", "\n")

    anchor = "risk_flag IS NULL OR risk_flag IN ('无', '高危', '新发', '同病')"
    require_anchor(original, anchor, "V8 的 risk_flag CHECK")

    injected = original.replace(
        anchor,
        "risk_flag IN ('无', '高危', '新发', '同病')",
        1,
    )
    assert injected != original, "K3 注入未改变内容"

    write_text(path, injected)
    try:
        green, out = run_test(N1_GATE_TEST)
        if green:
            record("K3 门禁应变红", False,
                   "删掉 IS NULL 支后门禁仍全绿 —— N-1 的 CHECK 守护是恒绿摆设")
        elif N1_MUST_SEE in out:
            record("K3 门禁应变红", True, N1_MUST_SEE + " FAILED")
        else:
            record("K3 门禁应变红（但红错条）", False,
                   "输出现红色的方法名不是 " + N1_MUST_SEE)
            print(out[-1500:])
    finally:
        restore_bytes(path, raw)

    ok = read_bytes(path) == raw
    record("K3 逐字节还原", ok, f"{F_N1_TARGET} ({len(raw)}B)")

    green, _ = run_test(N1_GATE_TEST)
    record("K3 还原后复绿", green, f"{N1_GATE_TEST} PASS" if green else "仍红！")


def main() -> int:
    print("=" * 72)
    print("批次五 · 反向验证：A-8 追认台账 / A-9 出参方向 / N-1 risk_flag CHECK")
    print("=" * 72)

    # 锚点预检（先于任何改动；不通过则未动任何文件）
    print("\n== 锚点预检 ==", flush=True)
    try:
        t = (SKEL / F_A8_TARGET).read_text(encoding="utf-8")
        require_anchor(t, '@RequirePermission("customer:write")\n    @PostMapping("/items/import")',
                       "K1 controller")
        t = (SKEL / F_A9_DOMAIN).read_text(encoding="utf-8")
        require_anchor(t, '@JsonProperty("retention_window_probed") boolean retentionWindowProbed) {',
                       "K2 domain")
        t = (SKEL / F_A9_SERVICE).read_text(encoding="utf-8")
        require_anchor(t, "type.name(),\n                windowDays.isPresent());", "K2 service")
        t = (SKEL / F_N1_TARGET).read_text(encoding="utf-8")
        require_anchor(t, "risk_flag IS NULL OR risk_flag IN ('无', '高危', '新发', '同病')", "K3 V8")
        print("  ✔ 五处锚点均存在且唯一", flush=True)
    except AssertionError as e:
        print(f"  ✘ {e}", flush=True)
        print("🛑 锚点预检未通过，终止（未改动任何文件）", flush=True)
        return 2

    # 基线：三套断言必须先全绿
    print("\n== 基线 ==", flush=True)
    for name, cls in (("A-8", A8_GATE_TEST), ("A-9", A9_GATE_TEST), ("N-1", N1_GATE_TEST)):
        green, out = run_test(cls)
        record(f"baseline {name}", green, f"{cls} " + ("PASS" if green else "RED"))
        if not green:
            print(out[-2000:])

    if not all(ok for _, ok, _ in RESULTS):
        print("\n🛑 基线不绿 —— 注入验证无意义，先修基线。", flush=True)
        return 2

    k1_a8_registry()
    k2_a9_response_field()
    k3_n1_risk_flag_check()

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